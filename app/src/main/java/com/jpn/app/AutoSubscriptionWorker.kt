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
                connection.setRequestProperty("User-Agent", "JPN/0.4.0")
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
            val subscription = decodeBody(cleaned)
            val nodes = parseEntries(subscription)
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

    private fun parseEntries(text: String): MutableList<JSONObject> {
        val source = text.removePrefix("\uFEFF").trim()
        val result = mutableListOf<JSONObject>()
        if (source.startsWith("{")) {
            val outbounds = runCatching { JSONObject(source).optJSONArray("outbounds") }.getOrNull()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val node = outbounds.optJSONObject(i) ?: continue
                    val copy = JSONObject(node.toString())
                    if (hasTlsOrReality(copy)) {
                        if (copy.optString("tag").isBlank()) copy.put("tag", "Сервер ${i + 1}")
                        result.add(copy)
                    }
                }
                return result
            }
        }
        val links = source.lineSequence().map { it.trim() }.filter { line ->
            val scheme = line.substringBefore("://", "")
            line.contains("://") && scheme.matches(Regex("(?i)vless|vmess|trojan|ss|socks|hysteria2|hy2"))
        }
        links.forEachIndexed { index, link ->
            val name = runCatching { Uri.decode(Uri.parse(link).fragment.orEmpty()) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Сервер ${index + 1}"
            val request = JSONObject().put("apiVersion", 3).put("method", "convertShareLinksToXrayJson")
                .put("payload", JSONObject().put("text", link))
            val response = JSONObject(LibXray.invoke(request.toString()))
            if (!response.optBoolean("success", false)) return@forEachIndexed
            val outbound = response.optJSONObject("data")?.optJSONArray("outbounds")?.optJSONObject(0)
                ?: return@forEachIndexed
            val copy = JSONObject(outbound.toString())
            if (!hasTlsOrReality(copy)) return@forEachIndexed
            if (copy.optString("tag").isBlank()) copy.put("tag", name)
            result.add(copy)
        }
        return result
    }

    private fun hasTlsOrReality(node: JSONObject): Boolean {
        val security = node.optJSONObject("streamSettings")?.optString("security") ?: return false
        return security.equals("tls", true) || security.equals("reality", true)
    }

    private fun decodeBody(raw: String): String {
        val source = raw.removePrefix("\uFEFF").trim()
        if (looksLikeSubscription(source)) return source
        val compact = source.filterNot { it.isWhitespace() }
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)) {
            val decoded = runCatching { String(Base64.decode(compact, flags), StandardCharsets.UTF_8).trim() }.getOrNull()
            if (!decoded.isNullOrBlank() && looksLikeSubscription(decoded)) return decoded
        }
        return source
    }

    private fun looksLikeSubscription(value: String): Boolean {
        val s = value.lowercase()
        return s.startsWith("vless://") || s.startsWith("vmess://") || s.startsWith("trojan://") ||
            s.startsWith("ss://") || s.startsWith("socks://") || s.startsWith("hysteria2://") ||
            s.startsWith("hy2://") || s.startsWith("{") || s.startsWith("[") ||
            s.contains("\nvless://") || s.contains("\nvmess://") || s.contains("\ntrojan://") || s.contains("\nss://")
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
