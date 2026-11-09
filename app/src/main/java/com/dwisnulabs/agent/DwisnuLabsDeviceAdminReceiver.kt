package com.dwisnulabs.agent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class DwisnuLabsDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Aplikasi Parental Control ini dikelola oleh Orang Tua untuk mengatur waktu layar perangkat keluarga."
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, DwisnuLabsDeviceAdminReceiver::class.java)
        }

        fun isActive(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(getComponentName(context))
            } catch (_: Exception) {
                false
            }
        }

        fun lockScreenNow(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(getComponentName(context))) {
                    dpm.lockNow()
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

