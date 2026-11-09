package com.dwisnulabs.agent

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class StatusSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            AppState.init(applicationContext)
            if (!DwisnuLabsLockService.isRunning) {
                DwisnuLabsLockService.start(applicationContext)
            }
            FirebaseDeviceManager.init(applicationContext)
            FirebaseDeviceManager.syncStatus(applicationContext)
            KeepAliveSyncReceiver.schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Periodic status sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "StatusSyncWorker"
        private const val WORK_NAME = "parental_periodic_status_sync"

        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<StatusSyncWorker>(15, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue periodic work: ${e.message}")
            }
        }
    }
}

