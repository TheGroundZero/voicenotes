// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The media side of Voice Notes. Two things hang off one [MediaSession]:
 *
 * - **Android Auto (and any car head unit that browses media apps).** The app shows up in the car's app list
 *   with one item, "Record a note"; selecting it, or pressing play, starts a note and pressing pause stops it.
 *   Only media and messaging apps can be side-loaded into Android Auto (its "Unknown sources" developer
 *   option), and there is no note-taking category, so Voice Notes presents itself as a media app.
 * - **A headset or steering-wheel play/pause button**, when the user has switched that on: the service then
 *   runs as a foreground service so the session survives, and claims the media buttons (see [claimMediaButtons]).
 *
 * Android decides which media session receives the buttons (usually the one that played last), so this can
 * compete with a music app; that part needs testing in the car.
 */
class VoiceNotesMediaService : MediaBrowserService() {
    private var session: MediaSession? = null
    private var lastBrowseAt = 0L // when a car (or other media UI) last connected to browse us
    private var recordingSince = 0L // when the running note started (elapsed realtime)
    private var wasRecording = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val s = MediaSession(this, "Voice Notes").apply {
            setSessionActivity(PendingIntent.getActivity(this@VoiceNotesMediaService, 0, Intent(this@VoiceNotesMediaService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    logCall("onPlay")
                    // Android Auto "resumes" the last media app right after it connects (bench log: 1.2 s after it first
                    // browsed us, and it retries 5 s later), and cannot be told apart from a person pressing play. A note
                    // must never start by itself, so Android Auto has to use an explicit action: the "Record a note"
                    // item or the Record button. (Other controllers, such as the lock screen, may still play.)
                    if (!RecordingService.isRecording && session?.currentControllerInfo?.packageName == ANDROID_AUTO) {
                        Log.i(TAG, "ignored: a plain play from Android Auto never starts a note")
                        return
                    }
                    startNote()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    logCall("onCustomAction($action)")
                    if (action == ACTION_RECORD) startNote()
                }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { logCall("onPlayFromMediaId"); startNote() }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) { logCall("onPlayFromSearch"); startNote() } // "Hey Google, play Voice Notes"
                override fun onPause() { logCall("onPause"); stopNote() }
                override fun onStop() { logCall("onStop"); stopNote() }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (event != null && event.keyCode in TOGGLE_KEYS) {
                        // One press, one toggle: act on the key-down only, and ignore auto-repeat.
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            val fromCar = session?.currentControllerInfo?.packageName == ANDROID_AUTO
                            val sinceStart = SystemClock.elapsedRealtime() - recordingSince
                            if (ignoreCarKey(fromCar, RecordingService.isRecording, sinceStart)) {
                                // Seen in a car: the head unit sent a pause key 0.13 s after a note started (reversing) and ended it.
                                Log.i(TAG, "ignored: ${KeyEvent.keyCodeToString(event.keyCode)} from the car, ${sinceStart} ms after the note started")
                            } else {
                                RecordingService.toggle(this@VoiceNotesMediaService, Transcriber.AUTO)
                            }
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
        session = s
        sessionToken = s.sessionToken
        scope.launch { RecordingService.recording.collect { render(s, it) } }
    }

    /** Who asked, and how soon after a car connected: what separates a person from an automatic resume. */
    private fun logCall(name: String) {
        val caller = session?.currentControllerInfo?.packageName
        val sinceBrowse = if (lastBrowseAt == 0L) -1 else SystemClock.elapsedRealtime() - lastBrowseAt
        Log.i(TAG, "$name from $caller, ${sinceBrowse} ms after the car browsed us, recording=${RecordingService.isRecording}")
    }

    private fun startNote() {
        if (!RecordingService.isRecording) RecordingService.toggle(this, Transcriber.AUTO)
    }

    private fun stopNote() {
        if (RecordingService.isRecording) RecordingService.toggle(this, Transcriber.AUTO)
    }

    /** Started by the app (not bound by a car) when the user turns the headset button on. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            Settings.setHeadsetButton(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        session?.let { claimMediaButtons(it) }
        return START_STICKY
    }

    /** Only the car's media UI (and the system) may browse; the one item offered holds no personal data. */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val callers = packageManager.getPackagesForUid(clientUid).orEmpty()
        if (callers.none { it in TRUSTED_BROWSERS || it == packageName }) return null
        lastBrowseAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "onGetRoot from ${callers.joinToString()} recentHint=${rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT)}")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val record = MediaDescription.Builder()
            .setMediaId(RECORD_ID)
            .setTitle("Record a note")
            .setSubtitle("Tap to start, press pause to stop")
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(record, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    /**
     * Android hands the media buttons to the session that played most recently (that is how a headset
     * button resumes a paused music app). A session that only claims to be playing is ignored: the app
     * has to produce real audio. So play a few hundred milliseconds of silence while reporting "playing";
     * afterwards the session stays the last one that played, like a paused music player. No audio focus
     * is requested, so other apps are not interrupted.
     */
    private fun claimMediaButtons(s: MediaSession) {
        s.setPlaybackState(playbackState(playing = true))
        val bytes = SAMPLE_RATE / 1000 * SILENCE_MS * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(ByteArray(bytes), 0, bytes)
        track.play()
        scope.launch {
            delay(SILENCE_MS + 200L)
            track.release()
            render(s, RecordingService.isRecording)
        }
    }

    private fun render(s: MediaSession, recording: Boolean) {
        if (recording && !wasRecording) recordingSince = SystemClock.elapsedRealtime()
        wasRecording = recording
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (recording) "Recording a note" else "Voice note")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (recording) "Press pause to stop" else "Choose Record a note")
                .build(),
        )
        s.setPlaybackState(playbackState(playing = recording))
    }

    /**
     * Idle: no plain "play" is offered, only starting from the "Record a note" item, by voice, or with the Record
     * button, so there is nothing for a car to resume on its own. Recording: pause and stop end the note.
     */
    private fun playbackState(playing: Boolean): PlaybackState {
        val builder = PlaybackState.Builder()
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, if (playing) 1f else 0f)
        if (playing) {
            builder.setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
        } else {
            builder.setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_RECORD, "Record a note", R.drawable.ic_mic).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_triggers), NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(
            this, 2, Intent(this, VoiceNotesMediaService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_headset_on))
            .setContentText(getString(R.string.notif_headset_hint))
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_turn_off), off).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VoiceNotesMedia"
        private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        private const val ACTION_RECORD = "record"
        private const val ACTION_TURN_OFF = "be.sequr.voicenotes.HEADSET_OFF"
        private const val CHANNEL_ID = "triggers"
        private const val NOTIF_ID = 3
        private const val ROOT_ID = "root"
        private const val RECORD_ID = "record"
        private const val SAMPLE_RATE = 8_000
        private const val SILENCE_MS = 400
        /** A key the car forwards this soon after a note started is the car's own doing, not a person stopping the note. */
        private const val CAR_KEY_GRACE_MS = 3_000L
        private val TOGGLE_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        )
        /** Android Auto, the Google services that host it, the Assistant, and System UI. */
        private val TRUSTED_BROWSERS = setOf(
            "com.google.android.projection.gearhead", "com.google.android.gms", "com.google.android.googlequicksearchbox",
            "com.android.systemui", "android",
        )

        /** Whether a toggle key is to be ignored: sent by Android Auto while a note that started less than [CAR_KEY_GRACE_MS] ago is running. */
        internal fun ignoreCarKey(fromAndroidAuto: Boolean, recording: Boolean, sinceStartMs: Long): Boolean =
            fromAndroidAuto && recording && sinceStartMs in 0 until CAR_KEY_GRACE_MS

        fun turnOn(context: Context) {
            Settings.setHeadsetButton(context, true)
            context.startForegroundService(Intent(context, VoiceNotesMediaService::class.java))
        }

        fun turnOff(context: Context) {
            Settings.setHeadsetButton(context, false)
            context.stopService(Intent(context, VoiceNotesMediaService::class.java))
        }

        /** Restart the service after the app process was killed or the phone rebooted, if the user left it on. */
        fun restoreIfEnabled(context: Context) {
            if (Settings.headsetButton(context)) context.startForegroundService(Intent(context, VoiceNotesMediaService::class.java))
        }
    }
}
