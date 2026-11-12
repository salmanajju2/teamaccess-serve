package com.teamaccess.remote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.teamaccess.remote.databinding.ActivityMainBinding
import com.teamaccess.remote.network.ConnectionManager
import com.teamaccess.remote.service.RemoteInputAccessibilityService
import com.teamaccess.remote.service.ScreenCaptureService
import com.teamaccess.remote.service.TeamAccessForegroundService
import com.teamaccess.remote.utils.DeviceStorage
import com.teamaccess.remote.utils.PermissionHelper
import com.teamaccess.remote.webrtc.SignalingClient

class MainActivity : AppCompatActivity(), SignalingClient.SignalingCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var connectionManager: ConnectionManager

    // ── MediaProjection launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateUiState()
        } else {
            Toast.makeText(this, "Screen share permission denied. Please allow it.", Toast.LENGTH_LONG).show()
        }
    }

    // ── Permission launcher (Mic + Location)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied — mic/location may not work", Toast.LENGTH_LONG).show()
        }
        updateUiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start 24/7 background service immediately
        TeamAccessForegroundService.start(this)

        // Initialize signaling connection
        connectionManager = ConnectionManager.getInstance(this)
        connectionManager.signalingClient.addListener(this)

        binding.tvDeviceName.text = connectionManager.deviceName

        // Show prominent disclosure on first run
        checkProminentDisclosure()

        setupButtons()
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
        checkBatteryOptimizationStatus()
        updateUiState()
    }

    // ══════════════════════════════════════
    //  REQUEST ALL RUNTIME PERMISSIONS
    // ══════════════════════════════════════
    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()

        // Microphone
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        // Location (fine)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ══════════════════════════════════════
    //  BUTTON SETUP
    // ══════════════════════════════════════
    private fun setupButtons() {
        binding.btnOpenAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnToggleShare.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                stopScreenShareService()
            } else {
                startScreenShareFlow()
            }
        }
    }

    private fun startScreenShareFlow() {
        // Check accessibility first
        if (RemoteInputAccessibilityService.instance == null) {
            Toast.makeText(this, "Please enable Accessibility permission first!", Toast.LENGTH_LONG).show()
            PermissionHelper.openAccessibilitySettings(this)
            return
        }
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun stopScreenShareService() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUiState()
    }

    // ══════════════════════════════════════
    //  UI STATE
    // ══════════════════════════════════════
    private fun updateUiState() {
        val isSharing = ScreenCaptureService.isRunning
        val isConnected = connectionManager.signalingClient.isConnected()

        // Screen share button
        if (isSharing) {
            binding.tvConnectionStatus.text = "● Screen Sharing Active — Remote access ready"
            binding.tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleShare.text = "⏹ Stop Screen Share"
            binding.btnToggleShare.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            binding.tvConnectionStatus.text = "Tap below to allow remote screen access"
            binding.tvConnectionStatus.setTextColor(Color.parseColor("#64748B"))
            binding.btnToggleShare.text = "⚡ Start 24/7 Remote Access"
            binding.btnToggleShare.setBackgroundColor(Color.parseColor("#2563EB"))
        }

        // Cloud connection status
        if (isConnected) {
            binding.tvCloudStatus.text = "● Connected to Cloud Server ✓"
            binding.tvCloudStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvCloudStatus.text = "○ Connecting to Cloud Server..."
            binding.tvCloudStatus.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    private fun checkAccessibilityStatus() {
        val isEnabled = RemoteInputAccessibilityService.instance != null
        if (isEnabled) {
            binding.tvAccessibilityBadge.text = "Active ✓"
            binding.tvAccessibilityBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
            binding.tvAccessibilityBadge.setTextColor(Color.parseColor("#059669"))
            binding.btnOpenAccessibility.text = "Remote Touch Control Granted ✓"
            binding.btnOpenAccessibility.isEnabled = false
        } else {
            binding.tvAccessibilityBadge.text = "Disabled"
            binding.tvAccessibilityBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
            binding.tvAccessibilityBadge.setTextColor(Color.parseColor("#DC2626"))
            binding.btnOpenAccessibility.text = "Grant Remote Control Permission"
            binding.btnOpenAccessibility.isEnabled = true
        }
    }

    private fun checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            if (isIgnoring) {
                binding.tvBatteryBadge.text = "Unrestricted ✓"
                binding.tvBatteryBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
                binding.tvBatteryBadge.setTextColor(Color.parseColor("#059669"))
                binding.btnIgnoreBattery.text = "Background Battery Unrestricted ✓"
                binding.btnIgnoreBattery.isEnabled = false
            } else {
                binding.tvBatteryBadge.text = "Restricted!"
                binding.tvBatteryBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                binding.tvBatteryBadge.setTextColor(Color.parseColor("#D97706"))
                binding.btnIgnoreBattery.text = "Enable Unrestricted Background Battery"
                binding.btnIgnoreBattery.isEnabled = true
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Toast.makeText(this, "Go to Settings → Battery → Unrestricted", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkProminentDisclosure() {
        if (!PermissionHelper.isDisclosureAccepted(this)) {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_disclosure, null)
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            dialogView.findViewById<MaterialButton>(R.id.btnAccept).setOnClickListener {
                PermissionHelper.setDisclosureAccepted(this, true)
                dialog.dismiss()
                checkAccessibilityStatus()
                requestIgnoreBatteryOptimizations()
                requestAllPermissions()
            }

            dialogView.findViewById<MaterialButton>(R.id.btnDecline).setOnClickListener {
                dialog.dismiss()
                finish()
            }

            dialog.show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ══════════════════════════════════════
    //  SIGNALING CALLBACKS
    // ══════════════════════════════════════
    override fun onConnected() {
        runOnUiThread { updateUiState() }
    }

    override fun onRegistered(peerId: String) {
        runOnUiThread { updateUiState() }
    }

    override fun onIncomingConnection(fromId: String) {
        runOnUiThread {
            Toast.makeText(this, "Remote session incoming!", Toast.LENGTH_SHORT).show()
            if (!ScreenCaptureService.isRunning) {
                startScreenShareFlow()
            }
        }
    }

    override fun onConnectAccepted(targetId: String) {}

    override fun onConnectError(message: String) {
        runOnUiThread {
            Toast.makeText(this, "Connect error: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSessionEnded(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Session ended: $reason", Toast.LENGTH_SHORT).show()
            updateUiState()
        }
    }

    override fun onError(err: String) {
        runOnUiThread { updateUiState() }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.signalingClient.removeListener(this)
        // Note: Do NOT destroy connectionManager — background stays alive!
    }
}
