package com.teamaccess.remote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teamaccess.remote.R
import com.teamaccess.remote.network.ConnectionManager
import org.json.JSONObject
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

        @Volatile
        private var instance: TeamAccessForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, TeamAccessForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}")
            }
        }

        fun refreshLocation(context: Context) {
            val svc = instance
            if (svc != null) svc.startLocationTracking() else start(context)
        }
    }

    private var heartbeatScheduler: ScheduledExecutorService? = null
    private var locationManager: LocationManager? = null
    private var locationStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            sendLocationToServer(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannel()
        startServiceNotification()

        val connectionManager = ConnectionManager.getInstance(applicationContext)

        // 10-second heartbeat — keep signaling alive 24/7
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeatScheduler?.scheduleWithFixedDelay({
            try {
                if (!connectionManager.signalingClient.isConnected()) {
                    Log.i(TAG, "Heartbeat: reconnecting signaling...")
                    connectionManager.signalingClient.connect()
                } else {
                    // Send ping to keep server alive (Render free tier)
                    connectionManager.signalingClient.sendPing()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
        }, 5, 10, TimeUnit.SECONDS)

        // Start location tracking
        startLocationTracking()

        Log.i(TAG, "TeamAccessForegroundService started 24/7")
    }

    // ══════════════════════════════════════
    //  LOCATION TRACKING
    // ══════════════════════════════════════
    private fun startLocationTracking() {
        try {
            val hasFineLoc = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarseLoc = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLoc && !hasCoarseLoc) {
                Log.w(TAG, "Location permission not granted — skipping GPS tracking")
                return
            }
            if (locationStarted) return

            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val provider = when {
                locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider == null) {
                Log.w(TAG, "No location provider available")
                return
            }

            // Request updates every 30 seconds or 10 meter movement
            locationManager!!.requestLocationUpdates(
                provider, 30_000L, 10f, locationListener, mainHandler.looper
            )

            // Send last known location immediately
            val lastKnown = locationManager!!.getLastKnownLocation(provider)
            if (lastKnown != null) {
                sendLocationToServer(lastKnown)
            }

            locationStarted = true
            Log.i(TAG, "✅ Location tracking started via $provider")
        } catch (e: Exception) {
            Log.e(TAG, "Location tracking error: ${e.message}")
        }
    }

    private fun sendLocationToServer(location: Location) {
        try {
            val cm = ConnectionManager.getInstance(applicationContext)
            if (cm.signalingClient.isConnected()) {
                val lat = "%.6f".format(location.latitude)
                val lng = "%.6f".format(location.longitude)
                val acc = location.accuracy.toInt().toString()
                cm.signalingClient.sendLocationUpdate(lat, lng, acc, cm.deviceId)
                Log.d(TAG, "Location sent: $lat, $lng (±${acc}m)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendLocation error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeamAccess Remote — Online 🟢")
            .setContentText("Device active, GPS tracking, ready for remote access")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLoc = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            try {
                val types = if (hasLoc) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    // Location type without permission crashes the service on Android 14+,
                    // which also drops the cloud connection and the device never shows online.
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground with types failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    Log.e(TAG, "startForeground failed: ${e2.message}")
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "TeamAccess Background Connection", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TeamAccess connected to cloud server"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        locationStarted = false
        heartbeatScheduler?.shutdownNow()
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) {}
        Log.w(TAG, "TeamAccessForegroundService stopped — restarting...")
        start(applicationContext)
    }
}
