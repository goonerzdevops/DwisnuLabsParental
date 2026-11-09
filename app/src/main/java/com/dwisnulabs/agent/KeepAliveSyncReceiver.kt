package com.dwisnulabs.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class KeepAliveSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        Log.d(TAG, "Periodic sync alarm received.")
        AppState.init(appContext)
        if (!DwisnuLabsLockService.isRunning) {
            DwisnuLabsLockService.start(appContext)
        }
        FirebaseDeviceManager.init(appContext)
        FirebaseDeviceManager.syncStatus(appContext)
        schedule(appContext)
    }

    companion object {
        private const val TAG = "KeepAliveSync"
        private const val REQUEST_CODE = 2045
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            try {
                val appContext = context.applicationContext
                val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(appContext, KeepAliveSyncReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
                val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to schedule periodic sync: ${e.message}")
            }
        }
    }
}

