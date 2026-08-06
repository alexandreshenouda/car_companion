package com.carlauncher.companion.data.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.carlauncher.companion.CompanionApp
import java.util.concurrent.TimeUnit

/**
 * Runs [CloudSyncManager.syncAll] in the background, network-constrained, so an enabled
 * category keeps backing up without the app needing to be open. The manual "Sync now" button
 * in Cloud settings calls [CloudSyncManager.syncAll] directly instead of going through
 * WorkManager — that path wants an immediate result to show the user, not a scheduled job.
 *
 * `syncAll()` already no-ops (returns null) when signed out or the build has no Supabase
 * credentials, so this worker is safe to schedule unconditionally.
 */
class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CompanionApp).container
        return try {
            container.cloudSyncManager.syncAll()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background cloud sync failed", e)
            // Retried on WorkManager's backoff schedule rather than treated as permanent —
            // this is almost always a transient network/server issue.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CloudSyncWorker"
        private const val PERIODIC_WORK_NAME = "cloud_sync_periodic"
        private const val ONE_SHOT_WORK_NAME = "cloud_sync_immediate"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Called once at app start. Idempotent — `KEEP` means re-enqueuing on every launch
         * doesn't reset the schedule or duplicate jobs. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Called right when a category toggle flips on, so backup starts promptly instead of
         * waiting for the next periodic tick (up to 30 minutes away). */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancels both jobs — called on sign-out so a signed-out device doesn't keep waking
         * up to attempt a sync that immediately no-ops. */
        suspend fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            wm.cancelUniqueWork(ONE_SHOT_WORK_NAME)
            wm.pruneWork().await()
        }
    }
}
