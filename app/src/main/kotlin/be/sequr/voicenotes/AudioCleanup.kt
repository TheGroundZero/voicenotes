// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Optional, off by default: removes a note's recording some days after the note was transcribed, to save space. The text
 * and every transcript version stay. A note whose transcription failed, is waiting, or has no speech keeps its audio,
 * because the audio is the only thing left to try again with. The user confirms before the first run (Storage screen).
 */
object AudioCleanup {
    private const val JOB_ID = 103

    data class Preview(val notes: Int, val bytes: Long)

    /** Recordings first transcribed before this moment are due when removal after [days] days is on. */
    fun cutoff(nowMillis: Long, days: Int): Long = nowMillis - TimeUnit.DAYS.toMillis(days.toLong())

    /** What a removal after [days] days would delete right now (blocking). */
    fun preview(context: Context, days: Int): Preview {
        val due = NoteDatabase.get(context).noteDao().audioRemovable(cutoff(System.currentTimeMillis(), days))
        return Preview(due.size, due.sumOf { NoteFiles.audio(context, it).length() })
    }

    /** Removes what is due under the current setting; does nothing when it is off. Returns the number of recordings removed (blocking). */
    fun run(context: Context): Int {
        val days = Settings.autoRemovalDays(context)
        if (days <= 0) return 0
        val dao = NoteDatabase.get(context).noteDao()
        return dao.audioRemovable(cutoff(System.currentTimeMillis(), days)).count { removeAudio(context, it) }
    }

    /** Deletes one note's recording and remembers it; the note itself and its text stay (blocking). */
    fun removeAudio(context: Context, note: Note): Boolean {
        val file = NoteFiles.audio(context, note)
        if (file.exists() && !file.delete()) return false
        NoteDatabase.get(context).noteDao().markAudioDeleted(note.id)
        return true
    }

    /** Space taken by recordings that are still there, and the notes that no longer have one (blocking). */
    fun usage(context: Context): Pair<Preview, Int> {
        val dao = NoteDatabase.get(context).noteDao()
        val remaining = dao.withAudio()
        return Preview(remaining.size, remaining.sumOf { NoteFiles.audio(context, it).length() }) to dao.countAudioRemoved()
    }

    /** Keeps a daily check scheduled while removal is on (jobs are lost on reboot until the app starts again). */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (Settings.autoRemovalDays(context) <= 0) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AudioCleanupJobService::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setRequiresBatteryNotLow(true)
            .build()
        scheduler.schedule(job)
    }
}

/** The daily run of [AudioCleanup]. */
class AudioCleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            AudioCleanup.run(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
