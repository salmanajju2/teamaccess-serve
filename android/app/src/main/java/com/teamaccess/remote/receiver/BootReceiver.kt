package com.teamaccess.remote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.teamaccess.remote.network.ConnectionManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device rebooted. Initializing 24/7 background connection...")
            com.teamaccess.remote.service.TeamAccessForegroundService.start(context)
        }
    }
}
