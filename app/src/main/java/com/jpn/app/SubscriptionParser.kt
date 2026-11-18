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
                    if (!isSupportedOutbound(outbound)) continue
                    val copy = JSONObject(outbound.toString())
                    if (copy.optString("tag").isBlank()) copy.put("tag", "Сервер ${i + 1}")
                    imported.add(copy)
                }
                return imported
            }
        }
        if (source.startsWith("[Interface]", ignoreCase = true)) {
            runCatching { parseWireGuardConfig(source, "WireGuard") }
                .getOrNull()?.let { imported.add(it) }
            return imported
        }
        val links = source.lineSequence().map { it.trim() }.filter { line ->
            val scheme = line.substringBefore("://", "")
            line.contains("://") && scheme.matches(Regex("(?i)vless|vmess|trojan|ss|socks|hysteria2|hy2|wireguard|wg"))
        }
        links.forEachIndexed { index, link ->
            val name = runCatching { Uri.decode(Uri.parse(link).fragment.orEmpty()) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Сервер ${index + 1}"
            val scheme = link.substringBefore("://", "")
            if (scheme.equals("wireguard", true) || scheme.equals("wg", true)) {
                runCatching { parseWireGuardLink(link, name) }.getOrNull()?.let { imported.add(it) }
                return@forEachIndexed
            }
            val request = JSONObject().put("apiVersion", 3)
                .put("method", "convertShareLinksToXrayJson")
                .put("payload", JSONObject().put("text", link))
            val response = runCatching { JSONObject(LibXray.invoke(request.toString())) }.getOrNull()
                ?: return@forEachIndexed
            if (!response.optBoolean("success", false)) return@forEachIndexed
            val outbound = response.optJSONObject("data")?.optJSONArray("outbounds")?.optJSONObject(0)
                ?: return@forEachIndexed
            if (!isSupportedOutbound(outbound)) return@forEachIndexed
            val copy = JSONObject(outbound.toString())
            if (copy.optString("tag").isBlank()) copy.put("tag", name)
            imported.add(copy)
        }
        return imported
    }

    private fun isSupportedOutbound(outbound: JSONObject): Boolean {
        if (outbound.optString("protocol").equals("wireguard", true)) return true
        val security = outbound.optJSONObject("streamSettings")?.optString("security") ?: return false
        return security.equals("tls", true) || security.equals("reality", true)
    }

    /** Converts wireguard:// or wg:// subscription links into Xray WireGuard outbound JSON. */
    private fun parseWireGuardLink(raw: String, name: String): JSONObject {
        val uri = java.net.URI(raw.trim())
        val privateKey = uri.rawUserInfo?.let { Uri.decode(it) }
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В WireGuard-ссылке отсутствует приватный ключ")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В WireGuard-ссылке указан неверный адрес сервера")
        if (uri.port !in 1..65535) throw IllegalArgumentException("В WireGuard-ссылке указан неверный порт")
        val params = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split("&").filter { it.isNotBlank() }.forEach { item ->
            val key = Uri.decode(item.substringBefore("=")).lowercase()
            val value = Uri.decode(item.substringAfter("=", ""))
            if (key.isNotBlank()) params[key] = value
        }
        val address = params["address"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В WireGuard-ссылке отсутствует параметр address")
        val publicKey = params["publickey"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В WireGuard-ссылке отсутствует параметр publickey")
        val conf = buildString {
            append("[Interface]\n")
            append("PrivateKey = ").append(privateKey).append('\n')
            append("Address = ").append(address).append('\n')
            params["mtu"]?.let { append("MTU = ").append(it).append('\n') }
            params["dns"]?.let { append("DNS = ").append(it).append('\n') }
            append("[Peer]\n")
            append("PublicKey = ").append(publicKey).append('\n')
            append("Endpoint = ").append(host).append(':').append(uri.port).append('\n')
            params["allowedips"]?.let { append("AllowedIPs = ").append(it).append('\n') }
            params["presharedkey"]?.let { append("PresharedKey = ").append(it).append('\n') }
            params["persistentkeepalive"]?.let { append("PersistentKeepalive = ").append(it).append('\n') }
        }
        return parseWireGuardConfig(conf, name)
    }

    /** Converts a standard WireGuard client .conf into Xray's WireGuard outbound JSON. */
    fun parseWireGuardConfig(raw: String, name: String): JSONObject {
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        var current: MutableMap<String, String>? = null
        raw.removePrefix("\uFEFF").lineSequence().forEach { original ->
            val line = original.substringBefore("#").substringBefore(";").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                val sectionName = line.substring(1, line.length - 1).trim()
                if (!sectionName.equals("Interface", true) && !sectionName.equals("Peer", true)) {
                    current = null
                } else {
                    current = linkedMapOf<String, String>().also { sections.add(sectionName to it) }
                }
                return@forEach
            }
            val target = current ?: return@forEach
            val separator = line.indexOf("=")
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (key.isNotEmpty()) target[key] = value
        }

        val interfaceConfig = sections.firstOrNull { it.first.equals("Interface", true) }?.second
            ?: throw IllegalArgumentException("В файле отсутствует секция [Interface]")
        val privateKey = interfaceConfig["privatekey"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В конфигурации отсутствует PrivateKey")
        val addresses = splitConfigList(interfaceConfig["address"])
        if (addresses.isEmpty()) throw IllegalArgumentException("В конфигурации отсутствует Address")

        val peerSections = sections.filter { it.first.equals("Peer", true) }
        if (peerSections.isEmpty()) throw IllegalArgumentException("В конфигурации отсутствует секция [Peer]")
        if (peerSections.size > 1) {
            throw IllegalArgumentException("Пока поддерживается один раздел [Peer] в одном файле WireGuard")
        }
        val peer = peerSections.first().second
        val publicKey = peer["publickey"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В секции [Peer] отсутствует PublicKey")
        val endpoint = peer["endpoint"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("В секции [Peer] отсутствует Endpoint")
        val allowedIps = splitConfigList(peer["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") }

        val peerJson = JSONObject()
            .put("publicKey", publicKey)
            .put("endpoint", endpoint)
            .put("allowedIPs", org.json.JSONArray(allowedIps))
        peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { peerJson.put("preSharedKey", it) }
        peer["persistentkeepalive"]?.toLongOrNull()?.takeIf { it > 0 }?.let {
            peerJson.put("keepAlive", it)
        }

        val settings = JSONObject()
            .put("secretKey", privateKey)
            .put("address", org.json.JSONArray(addresses))
            .put("peers", org.json.JSONArray().put(peerJson))
            .put("isClient", true)
            // Xray's userspace WireGuard stack must run inside the existing JPN TUN.
            .put("noKernelTun", true)
        interfaceConfig["mtu"]?.toIntOrNull()?.let { settings.put("mtu", it) }
        val dns = splitConfigList(interfaceConfig["dns"]).filter { value ->
            value.contains(":") || value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
        }
        if (dns.isNotEmpty()) settings.put("remoteDNS", org.json.JSONArray(dns))

        return JSONObject()
            .put("tag", name.ifBlank { "WireGuard" })
            .put("protocol", "wireguard")
            .put("settings", settings)
    }

    private fun splitConfigList(value: String?): List<String> =
        value.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun looksLikeSubscription(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("vless://") || lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") || lower.startsWith("ss://") ||
            lower.startsWith("socks://") || lower.startsWith("hysteria2://") ||
            lower.startsWith("hy2://") || lower.startsWith("wireguard://") || lower.startsWith("wg://") || lower.startsWith("{") || lower.startsWith("[interface]") ||
            lower.contains("\nvless://") || lower.contains("\nvmess://") ||
            lower.contains("\ntrojan://") || lower.contains("\nss://") ||
            lower.contains("\nsocks://") || lower.contains("\nhysteria2://") ||
            lower.contains("\nhy2://")
    }
}
