package com.jpn.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AutoSubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SubscriptionAutoUpdateActivity.KEY_ENABLED, false)) return Result.success()
        val address = prefs.getString("subscription_url", null)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        return try {
            val connection = URL(address).openConnection() as HttpURLConnection
            val body: String
            var title: String? = null
            var announce: String? = null
            var traffic: String? = null
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "JPN/${BuildConfig.VERSION_NAME}")
                SubscriptionRequestHeaders.apply(applicationContext, connection)
                connection.setRequestProperty("Accept", "text/plain, application/json, */*")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > 16 * 1024 * 1024) throw IllegalStateException("Subscription too large")
                        output.write(buffer, 0, count)
                    }
                }
                title = decodeTitle(connection.getHeaderField("profile-title"))
                announce = decodeAnnouncement(connection.getHeaderField("announce"))
                traffic = connection.getHeaderField("subscription-userinfo")?.trim()?.takeIf { it.isNotBlank() }
                body = String(output.toByteArray(), StandardCharsets.UTF_8)
            } finally { connection.disconnect() }

            var bodyAnnouncement: String? = null
            val cleaned = body.lineSequence().filter { line ->
                val lineText = line.trim()
                if (lineText.startsWith("#announce:", true)) {
                    bodyAnnouncement = decodeAnnouncement(lineText.substringAfter(":")); false
                } else true
            }.joinToString("\n")
            val subscription = SubscriptionParser.decodeBody(cleaned)
            val nodes = SubscriptionParser.parseEntries(subscription)
            if (nodes.isEmpty()) throw IllegalStateException("No compatible servers in subscription")
            prefs.edit()
                .putString("subscription_content", subscription)
                .putString("subscription_name", title ?: runCatching { URL(address).host }.getOrNull())
                .putString("subscription_announce", announce ?: bodyAnnouncement)
                .putString("subscription_traffic", traffic)
                .putString("saved_nodes_json", JSONArray(nodes).toString())
                .apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }


    private fun decodeAnnouncement(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = if (value.startsWith("base64:", true)) runCatching {
            String(Base64.decode(value.substringAfter(":").filterNot { it.isWhitespace() }, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
        }.getOrNull() else value
        return decoded?.takeIf { it.isNotBlank() && it.none { ch -> ch == '\u0000' } }?.take(500)
    }

    private fun decodeTitle(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = value.removePrefix("base64:").removePrefix("BASE64:").filterNot { it.isWhitespace() }
        val decoded = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).firstNotNullOfOrNull { flags ->
            runCatching {
                String(Base64.decode(compact, flags), StandardCharsets.UTF_8).trim()
                    .takeIf { it.isNotBlank() && !it.any { ch -> ch.code == 65533 || (ch.isISOControl() && !ch.isWhitespace()) } }
            }.getOrNull()
        }
        return decoded ?: value
    }
}
