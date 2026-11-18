package com.jpn.app

import android.net.Uri
import android.util.Base64
import libXray.LibXray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Shared subscription decoder/parser for manual, QR and automatic imports. */
internal object SubscriptionParser {
    fun decodeBody(raw: String): String {
        val source = raw.removePrefix("\uFEFF").trim()
        if (looksLikeSubscription(source)) return source
        val compact = source.filterNot { it.isWhitespace() }
        val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        for (flag in flags) {
            val decoded = runCatching {
                String(Base64.decode(compact, flag), StandardCharsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank() && looksLikeSubscription(decoded)) return decoded
        }
        return source
    }

    fun parseEntries(text: String): MutableList<JSONObject> {
        val source = decodeBody(text)
        val imported = mutableListOf<JSONObject>()
        if (source.startsWith("{")) {
            val outbounds = runCatching { JSONObject(source).optJSONArray("outbounds") }.getOrNull()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    if (!hasTlsOrRealityProtection(outbound)) continue
                    val copy = JSONObject(outbound.toString())
                    if (copy.optString("tag").isBlank()) copy.put("tag", "Сервер ${i + 1}")
                    imported.add(copy)
                }
                return imported
            }
        }
        val links = source.lineSequence().map { it.trim() }.filter { line ->
            val scheme = line.substringBefore("://", "")
            line.contains("://") && scheme.matches(Regex("(?i)vless|vmess|trojan|ss|socks|hysteria2|hy2"))
        }
        links.forEachIndexed { index, link ->
            val name = runCatching { Uri.decode(Uri.parse(link).fragment.orEmpty()) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Сервер ${index + 1}"
            val request = JSONObject().put("apiVersion", 3)
                .put("method", "convertShareLinksToXrayJson")
                .put("payload", JSONObject().put("text", link))
            val response = runCatching { JSONObject(LibXray.invoke(request.toString())) }.getOrNull()
                ?: return@forEachIndexed
            if (!response.optBoolean("success", false)) return@forEachIndexed
            val outbound = response.optJSONObject("data")?.optJSONArray("outbounds")?.optJSONObject(0)
                ?: return@forEachIndexed
            if (!hasTlsOrRealityProtection(outbound)) return@forEachIndexed
            val copy = JSONObject(outbound.toString())
            if (copy.optString("tag").isBlank()) copy.put("tag", name)
            imported.add(copy)
        }
        return imported
    }

    private fun hasTlsOrRealityProtection(outbound: JSONObject): Boolean {
        val security = outbound.optJSONObject("streamSettings")?.optString("security") ?: return false
        return security.equals("tls", true) || security.equals("reality", true)
    }

    private fun looksLikeSubscription(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("vless://") || lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") || lower.startsWith("ss://") ||
            lower.startsWith("socks://") || lower.startsWith("hysteria2://") ||
            lower.startsWith("hy2://") || lower.startsWith("{") || lower.startsWith("[") ||
            lower.contains("\nvless://") || lower.contains("\nvmess://") ||
            lower.contains("\ntrojan://") || lower.contains("\nss://") ||
            lower.contains("\nsocks://") || lower.contains("\nhysteria2://") ||
            lower.contains("\nhy2://")
    }
}
