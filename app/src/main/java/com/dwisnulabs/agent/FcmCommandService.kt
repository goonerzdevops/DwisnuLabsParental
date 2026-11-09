package com.dwisnulabs.agent

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmCommandService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed.")
        AppState.init(applicationContext)
        AppState.fcmToken = token
        FirebaseDeviceManager.updateFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val appContext = applicationContext
        AppState.init(appContext)

        if (!DwisnuLabsLockService.isRunning) {
            DwisnuLabsLockService.start(appContext)
        }
        KeepAliveSyncReceiver.schedule(appContext)
        StatusSyncWorker.schedule(appContext)
        FirebaseDeviceManager.init(appContext)

        val action = message.data["action"]
        val timestamp = message.data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
        if (!action.isNullOrBlank()) {
            Log.d(TAG, "Received FCM push action: $action (ts=$timestamp)")
            FirebaseDeviceManager.onIncomingCommand(appContext, action, timestamp)
        } else {
            FirebaseDeviceManager.syncStatus(appContext)
        }
    }

    companion object {
        private const val TAG = "FcmCommandService"
    }
}

