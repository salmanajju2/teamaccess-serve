package com.teamaccess.remote.utils

import android.content.Context
import kotlin.random.Random

object IdGenerator {

    private const val PREF_NAME = "teamaccess_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        if (!existingId.isNullOrEmpty()) {
            return existingId
        }

        // Generate persistent 9-digit ID
        val rawNum = Random.nextLong(100_000_000L, 999_999_999L).toString()
        val formatted = "${rawNum.substring(0, 3)}-${rawNum.substring(3, 6)}-${rawNum.substring(6, 9)}"

        prefs.edit().putString(KEY_DEVICE_ID, formatted).apply()
        return formatted
    }

    fun generateSessionPin(): String {
        return Random.nextInt(1000, 9999).toString()
    }
}
