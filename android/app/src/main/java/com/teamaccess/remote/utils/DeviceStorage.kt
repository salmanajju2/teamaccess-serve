package com.teamaccess.remote.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SavedPartnerDevice(
    val id: String,
    val name: String,
    val savedPin: String,
    val lastConnected: Long = System.currentTimeMillis()
)

object DeviceStorage {

    private const val PREFS_NAME = "teamaccess_device_prefs"
    private const val KEY_PERMANENT_PIN = "permanent_pin"
    private const val KEY_UNATTENDED_ENABLED = "unattended_enabled"
    private const val KEY_SAVED_PARTNERS = "saved_partners"

    private val gson = Gson()

    // 1. Permanent Password (for Unattended Access - no need to accept manually)
    fun setPermanentPin(context: Context, pin: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PERMANENT_PIN, pin).apply()
    }

    fun getPermanentPin(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PERMANENT_PIN, null)
    }

    fun isUnattendedAccessEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_UNATTENDED_ENABLED, true)
    }

    fun setUnattendedAccessEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_UNATTENDED_ENABLED, enabled).apply()
    }

    // 2. Saved Partner Devices (One-Click Connect without typing ID/PIN again)
    fun getSavedPartners(context: Context): MutableList<SavedPartnerDevice> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SAVED_PARTNERS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<SavedPartnerDevice>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePartner(context: Context, partner: SavedPartnerDevice) {
        val list = getSavedPartners(context)
        // Remove existing entry with same ID if any
        list.removeAll { it.id == partner.id }
        list.add(0, partner) // Add to top
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVED_PARTNERS, gson.toJson(list)).apply()
    }

    fun removePartner(context: Context, partnerId: String) {
        val list = getSavedPartners(context)
        list.removeAll { it.id == partnerId }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVED_PARTNERS, gson.toJson(list)).apply()
    }
}
