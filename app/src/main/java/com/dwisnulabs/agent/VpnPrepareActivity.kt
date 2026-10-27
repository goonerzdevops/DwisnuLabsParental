package com.dwisnulabs.agent

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

class VpnPrepareActivity : Activity() {

    companion object {
        private const val TAG = "VpnPrepareActivity"
        private const val REQUEST_CODE_VPN = 7788
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Log.d(TAG, "Requesting VPN consent dialog...")
            startActivityForResult(prepareIntent, REQUEST_CODE_VPN)
        } else {
            Log.d(TAG, "VPN already prepared, starting KillSwitch service.")
            DwisnuLabsKillSwitchService.start(this)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "VPN consent GRANTED by user!")
                DwisnuLabsKillSwitchService.start(this)
            } else {
                Log.w(TAG, "VPN consent DENIED or canceled: $resultCode")
            }
        }
        finish()
    }
}
