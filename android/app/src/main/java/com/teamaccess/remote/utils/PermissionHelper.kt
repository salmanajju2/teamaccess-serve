package com.teamaccess.remote.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.teamaccess.remote.service.RemoteInputAccessibilityService

object PermissionHelper {

    private const val PREFS_NAME = "teamaccess_disclosure_prefs"
    private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"

    fun isDisclosureAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
    }

    fun setDisclosureAccepted(context: Context, accepted: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted).apply()
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false

        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val targetServiceName = "${context.packageName}/${RemoteInputAccessibilityService::class.java.canonicalName}"

        for (service in enabledServices) {
            if (service.id.equals(targetServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
