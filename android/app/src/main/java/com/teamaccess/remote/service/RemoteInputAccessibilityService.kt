package com.teamaccess.remote.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.teamaccess.remote.model.TouchConstants
import com.teamaccess.remote.utils.DeviceStorage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteInputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputService"
        var instance: RemoteInputAccessibilityService? = null
            private set

        init {
            try {
                System.loadLibrary("teamaccess_native")
            } catch (e: Throwable) {
                Log.w(TAG, "Native library load note: ${e.message}")
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentGesturePath: Path? = null
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var gestureStartTime: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RemoteInputAccessibilityService connected and ready.")
        try {
            com.teamaccess.remote.network.ConnectionManager.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting from accessibility service: ${e.message}")
        }
    }

    /**
     * Auto-confirms MediaProjection / Screen Cast confirmation dialog for Unattended Access.
     * When remote partner connects, this auto-clicks "Start now" / "Allow" so no human needs to touch Phone!
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (DeviceStorage.isUnattendedAccessEnabled(this)) {
            val root = rootInActiveWindow ?: return
            val autoClickTargets = listOf(
                "Start now",
                "Start",
                "Allow",
                "While using the app",
                "shuru karein",
                "Start recording"
            )

            for (targetText in autoClickTargets) {
                val nodes = root.findAccessibilityNodeInfosByText(targetText)
                for (node in nodes) {
                    if (node.isClickable) {
                        Log.i(TAG, "Unattended Access: Auto-clicking confirmation button: $targetText")
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    } else if (node.parent != null && node.parent.isClickable) {
                        Log.i(TAG, "Unattended Access: Auto-clicking parent button for: $targetText")
                        node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "RemoteInputAccessibilityService destroyed.")
    }

    /**
     * Injects touch events received from WebRTC DataChannel.
     * Guaranteed to execute on Main Looper Thread to prevent IllegalStateException.
     */
    fun injectRemoteTouch(data: ByteArray) {
        if (data.size < 16) return

        mainHandler.post {
            try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val action = buffer.get().toInt() and 0xFF
                val pointerId = buffer.get().toInt() and 0xFF
                val normX = buffer.float
                val normY = buffer.float
                val extraData = buffer.int
                val timestamp = buffer.short.toInt() and 0xFFFF

                processTouchOnMainThread(action, normX, normY, extraData)
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting remote touch: ${e.message}")
            }
        }
    }

    private fun processTouchOnMainThread(action: Int, normX: Float, normY: Float, extraData: Int) {
        val displayMetrics = resources.displayMetrics
        val screenX = normX.coerceIn(0f, 1f) * displayMetrics.widthPixels
        val screenY = normY.coerceIn(0f, 1f) * displayMetrics.heightPixels

        when (action) {
            TouchConstants.ACTION_DOWN -> {
                startX = screenX
                startY = screenY
                lastX = screenX
                lastY = screenY
                gestureStartTime = System.currentTimeMillis()
                currentGesturePath = Path().apply {
                    moveTo(screenX, screenY)
                }
            }

            TouchConstants.ACTION_MOVE -> {
                currentGesturePath?.lineTo(screenX, screenY)
                lastX = screenX
                lastY = screenY
            }

            TouchConstants.ACTION_UP -> {
                val path = currentGesturePath ?: Path().apply {
                    moveTo(screenX, screenY)
                }

                // Ensure at least 1px displacement so Android OS doesn't reject zero-length stroke
                val delta = Math.hypot((screenX - startX).toDouble(), (screenY - startY).toDouble())
                if (delta < 2.0) {
                    path.lineTo(screenX + 1f, screenY + 1f)
                }

                val duration = (System.currentTimeMillis() - gestureStartTime).coerceIn(40, 600)
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                try {
                    dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "Touch gesture dispatched successfully at ($screenX, $screenY)")
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Touch gesture cancelled by system at ($screenX, $screenY)")
                        }
                    }, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch gesture: ${e.message}")
                }

                currentGesturePath = null
            }

            TouchConstants.KEY_EVENT -> {
                handleSystemNavigationKey(extraData)
            }
        }
    }

    private fun handleSystemNavigationKey(keyCode: Int) {
        Log.i(TAG, "Executing system navigation key: $keyCode")
        when (keyCode) {
            TouchConstants.KEY_BACK, 4 -> performGlobalAction(GLOBAL_ACTION_BACK)
            TouchConstants.KEY_HOME, 3 -> performGlobalAction(GLOBAL_ACTION_HOME)
            TouchConstants.KEY_RECENTS, 187 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> Log.w(TAG, "Unhandled navigation keyCode: $keyCode")
        }
    }
}
