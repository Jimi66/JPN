package com.jpn.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.util.UUID

/**
 * Adds the same device-identification headers used by Happ-compatible
 * subscription providers. The Android ID is stable for this app signing key;
 * a persisted UUID is used only when Android does not provide one.
 */
object SubscriptionRequestHeaders {
    private const val PREFS = "jpn_settings"
    private const val KEY_FALLBACK_HWID = "subscription_fallback_hwid"

    fun apply(context: Context, connection: HttpURLConnection) {
        connection.setRequestProperty("X-HWID", getHwid(context))
        connection.setRequestProperty("X-Device-OS", "Android")
        connection.setRequestProperty("X-Ver-OS", Build.VERSION.RELEASE ?: "")
        connection.setRequestProperty("X-Device-Model", Build.MODEL ?: "Android")
    }

    @Synchronized
    private fun getHwid(context: Context): String {
        val appContext = context.applicationContext
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("9774d56d682e549c", ignoreCase = true)
        }
        if (androidId != null) return androidId

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_FALLBACK_HWID, null)?.takeIf { it.isNotBlank() }
        if (saved != null) return saved

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_HWID, generated).commit()
        return generated
    }
}
