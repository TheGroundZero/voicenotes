// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Transcribes the notes that are waiting (low battery, battery saver, or interrupted by a restart) once the system
 * says the battery is fine or the phone is charging. The notes are checked against [BatteryPolicy] again, because
 * the system's "battery not low" level is not ours; if they still have to wait the job is retried later.
 * Runs inside the system's job time limit; a note cut off by it is picked up again by the next run.
 */
class TranscriptionJobService : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val dao = NoteDatabase.get(this).noteDao()
            var again = false
            for (id in dao.waitingIds()) {
                if (stopped || !BatteryPolicy.state(this).allowsAutomatic) {
                    again = true
                    break
                }
                TranscriptionRunner.run(this, id, language = null, model = null, force = false, onlyIfWaiting = true)
            }
            jobFinished(params, again || dao.waitingIds().isNotEmpty())
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true // run again later
    }

    companion object {
        private const val JOB_BATTERY_NOT_LOW = 101
        private const val JOB_CHARGING = 102

        /** Ask the system to run waiting notes as soon as the battery is not low, or the phone is charging. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, TranscriptionJobService::class.java)
            scheduler.schedule(JobInfo.Builder(JOB_BATTERY_NOT_LOW, service).setRequiresBatteryNotLow(true).build())
            scheduler.schedule(JobInfo.Builder(JOB_CHARGING, service).setRequiresCharging(true).build())
        }
    }
}
