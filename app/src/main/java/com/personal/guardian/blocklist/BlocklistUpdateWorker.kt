package com.personal.guardian.blocklist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.personal.guardian.util.GuardianLog
import java.util.concurrent.TimeUnit

/**
 * Stage 2 — periodic, unattended blocklist refresh.
 *
 * WorkManager runs this every ~2 days (spec: "every few days"), only when a network
 * is available, surviving reboots and process death. This is what satisfies the
 * "updates automatically and periodically without manual intervention" requirement.
 */
class BlocklistUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val count = BlocklistManager.refreshNow(applicationContext)
            GuardianLog.i(applicationContext, "Scheduled blocklist refresh finished: $count domains.")
            Result.success()
        } catch (t: Throwable) {
            GuardianLog.e(applicationContext, "Scheduled blocklist refresh failed; will retry.", t)
            // Retry with exponential backoff; the previous cache stays in effect.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "guardian_blocklist_refresh"
        private const val REPEAT_INTERVAL_HOURS = 48L // every two days

        /**
         * Registers the periodic work if not already scheduled. Uses KEEP so we do
         * not reset the schedule (or trigger an immediate run) on every app/service
         * start. An initial one-off fetch is triggered separately by the UI/service
         * when there is no cache yet.
         */
        fun ensureScheduled(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            GuardianLog.i(context, "Blocklist periodic refresh scheduled (every ${REPEAT_INTERVAL_HOURS}h).")
        }

        /**
         * Forces an immediate refresh by replacing the periodic work so the first
         * run happens now. Used on first launch when there is no cached list.
         */
        fun refreshNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            GuardianLog.i(context, "Blocklist immediate refresh requested.")
        }
    }
}
