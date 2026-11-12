package com.teamaccess.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teamaccess.remote.R
import com.teamaccess.remote.network.ConnectionManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TeamAccessForegroundService : Service() {

    companion object {
        private const val TAG = "TeamAccessService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "teamaccess_service_channel"

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TeamAccessForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TeamAccessForegroundService: ${e.message}")
            }
        }
    }

    private var heartbeatScheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startServiceNotification()

        val connectionManager = ConnectionManager.getInstance(applicationContext)

        // Robust 10-second heartbeat to ensure connection never drops
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeatScheduler?.scheduleWithFixedDelay({
            try {
                if (!connectionManager.signalingClient.isConnected()) {
                    Log.i(TAG, "Heartbeat check: signaling disconnected. Reconnecting now...")
                    connectionManager.signalingClient.connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
        }, 5, 10, TimeUnit.SECONDS)

        Log.i(TAG, "TeamAccessForegroundService started and running 24/7.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeamAccess Remote - Online 🟢")
            .setContentText("Device is active and ready for remote access")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TeamAccess Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TeamAccess Remote connected to cloud server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        heartbeatScheduler?.shutdownNow()
        Log.w(TAG, "TeamAccessForegroundService stopped. Auto-restarting...")
        start(applicationContext)
    }
}
