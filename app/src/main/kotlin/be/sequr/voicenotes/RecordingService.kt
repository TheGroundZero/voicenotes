// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that records notes and transcribes them in the background.
 * Every trigger just sends [ACTION_TOGGLE] with a language code or [Transcriber.AUTO]; a second toggle stops and
 * saves the note. [ACTION_TRANSCRIBE] (re)runs transcription for an existing note.
 *
 * Note state lives in the database; this service only moves notes through
 * RECORDING -> QUEUED -> TRANSCRIBING -> DONE/FAILED.
 */
class RecordingService : Service() {
    private var recorder: WavRecorder? = null
    private var currentNote: Future<Long>? = null
    private var pendingTranscriptions = 0 // main thread only
    private val worker = Executors.newSingleThreadExecutor() // transcription: one at a time
    private val main = Handler(Looper.getMainLooper())
    private val dao by lazy { NoteDatabase.get(this).noteDao() }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest: AudioFocusRequest? = null
    private val microphone by lazy { MicrophoneRoute(this, main) }
    private var starting = false // waiting for a Bluetooth microphone to connect
    private var startedAt = 0L // uptime when the running note began
    private var onBluetooth = false // the running note records from a Bluetooth microphone
    private val autoStop = Runnable {
        Log.i(TAG, "note reached the maximum length, stopping")
        stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (recorder == null && !starting) startRecording(intent.getStringExtra(EXTRA_LANG) ?: Transcriber.AUTO) else stopRecording()
            ACTION_STOP -> stopRecording()
            ACTION_TRANSCRIBE -> {
                val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
                if (noteId >= 0) {
                    if (recorder == null) startForeground(recording = false)
                    enqueueTranscription(
                        noteId = { noteId },
                        language = intent.getStringExtra(EXTRA_LANG),
                        model = intent.getStringExtra(EXTRA_MODEL),
                        force = intent.getBooleanExtra(EXTRA_FORCE, false),
                    )
                } else {
                    stopIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(lang: String) {
        // Triggers check this themselves (the tile opens the app to ask). A foreground service of
        // type microphone cannot start without it, so there is nothing sensible to do here.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Tones.error(this)
            stopSelf()
            return
        }
        startForeground(recording = true)
        // Pause whatever else is playing (car music, a podcast): it would leak into the note through the
        // microphone, and the car's speakers are close to it. Without focus (e.g. during a call) do not record.
        if (!requestAudioFocus()) {
            Tones.error(this)
            stopIfIdle()
            return
        }
        starting = true
        microphone.acquire { device ->
            if (!starting) {
                microphone.release() // cancelled while the Bluetooth microphone was connecting
                return@acquire
            }
            starting = false
            beginRecording(lang, device)
        }
    }

    private fun beginRecording(lang: String, device: AudioDeviceInfo?) {
        // Use timestamp up until microseconds to ensure no overlapping recordings overwriting each other
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())
        val file = File(NoteFiles.dir(this), "$stamp.wav")
        var used = device // the microphone that actually records, which is the phone's after a fallback
        try {
            recorder = try {
                WavRecorder(file, device).also { it.start() }
            } catch (e: Exception) {
                if (device == null) throw e
                // The Bluetooth microphone would not open: fall back to the phone's own rather than lose the note.
                microphone.release()
                used = null
                WavRecorder(file, null).also { it.start() }
            }
        } catch (e: Exception) {
            file.delete()
            microphone.release()
            abandonAudioFocus()
            Tones.error(this)
            stopIfIdle()
            return
        }
        currentNote = Io.executor.submit<Long> {
            dao.insert(
                Note(
                    audioFile = file.name, language = lang, createdAt = System.currentTimeMillis(), status = NoteStatus.RECORDING,
                    micSource = used?.let { BluetoothMic.sourceFor(this, it) } ?: MIC_PHONE,
                ),
            )
        }
        startedAt = SystemClock.elapsedRealtime()
        onBluetooth = used != null
        _recording.value = true
        RecordWidget.update(this)
        Tones.start(this)
        main.postDelayed(autoStop, MAX_NOTE_MS) // a forgotten or unintended recording must not run forever
    }

    private fun stopRecording() {
        main.removeCallbacks(autoStop)
        if (starting) { // a second press while the Bluetooth microphone is still connecting cancels the start
            starting = false
            microphone.release()
            abandonAudioFocus()
            stopIfIdle()
            return
        }
        val rec = recorder ?: return
        rec.stop()
        recorder = null
        microphone.release()
        abandonAudioFocus() // other apps resume
        _recording.value = false
        RecordWidget.update(this)
        Tones.stop(this)
        // The audio is already safely on disk; transcribe in the background, then shut down.
        currentNote?.let { id ->
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(recording = false))
            enqueueTranscription(noteId = { id.get() }, language = null, model = null, force = false, automatic = true)
        }
        currentNote = null
        stopIfIdle()
    }

    /**
     * [noteId] is resolved on the database thread, where a just-submitted insert has already finished.
     * QUEUED is written before the job is handed to the worker, so a note waiting behind another
     * shows as queued and never flips back from TRANSCRIBING.
     */
    private fun enqueueTranscription(noteId: () -> Long, language: String?, model: String?, force: Boolean, automatic: Boolean = false) {
        pendingTranscriptions++
        Io.executor.execute {
            val id = noteId()
            dao.setStatus(id, NoteStatus.QUEUED)
            worker.execute {
                if (automatic && !BatteryPolicy.state(this).allowsAutomatic) {
                    // Not now: the battery is low or battery saver is on. The note is kept and a job picks it up later.
                    dao.setStatus(id, NoteStatus.WAITING)
                    TranscriptionJobService.schedule(this)
                } else {
                    TranscriptionRunner.run(this, id, language, model, force)
                }
                main.post { pendingTranscriptions--; stopIfIdle() }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        // Plain media focus, not an assistant's: Android Auto treats USAGE_ASSISTANT as a voice session and the car
        // answers by pressing its own voice button, which ends in "Voice commands are not available" without an assistant.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
            .setOnAudioFocusChangeListener(::onFocusChange, main)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    /**
     * A car or another app taking the audio is no reason to lose a note, so only a phone call ends it. If the car
     * reacts to the Bluetooth microphone within seconds of the start, the note carries on with the phone's own.
     */
    private fun onFocusChange(change: Int) {
        if (change != AudioManager.AUDIOFOCUS_LOSS && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) return
        if (recorder == null) return
        val inCall = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_RINGTONE
        Log.i(TAG, "audio focus lost ($change), in call: $inCall, on bluetooth: $onBluetooth")
        if (inCall) {
            stopRecording() // keep what has been recorded so far
        } else if (onBluetooth && SystemClock.elapsedRealtime() - startedAt < CAR_REACTION_MS) {
            Log.w(TAG, "the Bluetooth link was disturbed right after the start, continuing with the phone's microphone")
            onBluetooth = false
            recorder?.useDefaultMicrophone()
            microphone.release()
            currentNote?.let { id -> Io.executor.execute { dao.setMicSource(id.get(), MIC_PHONE) } }
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Only shut down when nothing is recording or transcribing, so a new note is never cut off. */
    private fun stopIfIdle() {
        if (recorder == null && !starting && pendingTranscriptions == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoStop)
        recorder?.stop() // never lose audio if the system kills the service
        microphone.release()
        abandonAudioFocus()
        _recording.value = false
        worker.shutdown() // lets a running transcription finish
        RecordWidget.update(this)
        super.onDestroy()
    }

    private fun startForeground(recording: Boolean) =
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(recording), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

    private fun buildNotification(recording: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (recording) R.string.notif_recording else R.string.notif_transcribing))
            .apply { if (recording) addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build()) }
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_TOGGLE = "be.sequr.voicenotes.TOGGLE"
        const val ACTION_STOP = "be.sequr.voicenotes.STOP"
        const val ACTION_TRANSCRIBE = "be.sequr.voicenotes.TRANSCRIBE"
        const val EXTRA_LANG = "lang"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_MODEL = "model"
        const val EXTRA_FORCE = "force"
        private const val TAG = "RecordingService"
        private const val MAX_NOTE_MS = 10 * 60 * 1000L
        private const val CAR_REACTION_MS = 5_000L
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        private val _recording = MutableStateFlow(false)

        /** Same-process state so the tiles and the in-app button can reflect it. */
        val recording: StateFlow<Boolean> = _recording.asStateFlow()
        val isRecording: Boolean get() = _recording.value

        fun toggle(context: Context, lang: String) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_TOGGLE).putExtra(EXTRA_LANG, lang),
            )
        }

        /**
         * [language] is a code or [Transcriber.AUTO]; null keeps the note's own. [model] null means the best installed.
         * [force] skips the check for speech (the user asked for this transcription).
         */
        fun transcribe(context: Context, noteId: Long, language: String? = null, model: String? = null, force: Boolean = false) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_TRANSCRIBE)
                    .putExtra(EXTRA_NOTE_ID, noteId).putExtra(EXTRA_LANG, language).putExtra(EXTRA_MODEL, model).putExtra(EXTRA_FORCE, force),
            )
        }
    }
}
