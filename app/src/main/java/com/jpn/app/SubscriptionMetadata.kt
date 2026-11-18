package com.jpn.app

import org.json.JSONObject
import java.net.URL

/**
 * Network-related subscription headers understood by Happ-compatible providers.
 * Values are kept with each imported outbound so manual and scheduled updates
 * use exactly the same connection metadata.
 */
internal object SubscriptionMetadata {
    private val supportedHeaders = listOf(
        "profile-title", "profile-update-interval", "subscription-userinfo",
        "profile-web-page-url", "fragment", "resolve-address", "host", "insecure",
        "announce", "support-url", "routing", "new-url", "new-domain",
        "providerid", "installid", "resolve-server-address-enable",
        "server-address-resolve-dns-ip", "server-address-resolve-dns-domain",
        "mux-enable", "mux-tcp-connections", "mux-xudp-connections", "mux-quic",
        "sniffing-enable", "preferred-ip-type", "dns-from-json-enable",
        "local-dns-enable", "subscription-request-timeout", "xray-tun-enable",
        "xray-tun-mtu", "fragmentation-enable", "fragmentation-packets",
        "fragmentation-length", "fragmentation-delay", "fragmentation-interval",
        "fragmentation-type", "fragmentation-advanced-param", "fragmentation-maxsplit",
        "noises-enable", "noises-packet-type", "noises-packet", "noises-delay",
        "noises-rand", "noises-rand-range", "fallback-url"
    )

    fun fromHeaders(headers: Map<String?, List<String>>): JSONObject {
        val normalized = headers.entries
            .filter { it.key != null }
            .associate { it.key!!.lowercase() to it.value.lastOrNull().orEmpty() }
        val result = JSONObject()
        supportedHeaders.forEach { key ->
            normalized[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { result.put(key, it) }
        }
        return result
    }

    fun boolean(metadata: JSONObject?, key: String): Boolean? {
        if (metadata == null || !metadata.has(key)) return null
        val value = metadata.optString(key).trim()
        return when {
            value.equals("true", true) || value == "1" || value.equals("yes", true) -> true
            value.equals("false", true) || value == "0" || value.equals("no", true) -> false
            else -> null
        }
    }

    fun applyToOutbound(source: JSONObject): JSONObject {
        val outbound = JSONObject(source.toString())
        val metadata = outbound.optJSONObject("jpnSubscriptionMetadata") ?: return outbound
        val host = metadata.optString("host").trim()
        val insecure = boolean(metadata, "insecure")
        val stream = outbound.optJSONObject("streamSettings") ?: return outbound

        if (host.isNotEmpty()) {
            when (stream.optString("network").lowercase()) {
                "ws" -> {
                    val settings = stream.optJSONObject("wsSettings") ?: JSONObject().also { stream.put("wsSettings", it) }
                    val headers = settings.optJSONObject("headers") ?: JSONObject().also { settings.put("headers", it) }
                    headers.put("Host", host)
                }
                "httpupgrade" -> {
                    val settings = stream.optJSONObject("httpupgradeSettings") ?: JSONObject().also { stream.put("httpupgradeSettings", it) }
                    settings.put("host", host)
                }
                "grpc" -> {
                    val settings = stream.optJSONObject("grpcSettings") ?: JSONObject().also { stream.put("grpcSettings", it) }
                    settings.put("authority", host)
                }
                "http" -> {
                    val settings = stream.optJSONObject("httpSettings") ?: JSONObject().also { stream.put("httpSettings", it) }
                    settings.put("host", org.json.JSONArray().put(host))
                }
            }
            stream.optJSONObject("tlsSettings")?.put("serverName", host)
            stream.optJSONObject("realitySettings")?.put("serverName", host)
        }

        if (insecure != null) {
            stream.optJSONObject("tlsSettings")?.put("allowInsecure", insecure)
        }

        val muxEnabled = boolean(metadata, "mux-enable")
        val muxTcp = metadata.optString("mux-tcp-connections").toIntOrNull()
            ?.takeIf { it in -1..1024 }
        val muxXudp = metadata.optString("mux-xudp-connections").toIntOrNull()
            ?.takeIf { it in -1..1024 }
        val muxQuic = metadata.optString("mux-quic").lowercase()
            .takeIf { it in setOf("reject", "allow", "skip") }
        if (muxEnabled != null || muxTcp != null || muxXudp != null || muxQuic != null) {
            val mux = outbound.optJSONObject("mux") ?: JSONObject()
            muxEnabled?.let { mux.put("enabled", it) }
            muxTcp?.let { mux.put("concurrency", it) }
            muxXudp?.let { mux.put("xudpConcurrency", it) }
            muxQuic?.let { mux.put("xudpProxyUDP443", it) }
            outbound.put("mux", mux)
        }
        return outbound
    }

    fun endpointHost(outbound: JSONObject): String? {
        val protocol = outbound.optString("protocol").lowercase()
        val address = when (protocol) {
            "vless", "vmess" -> outbound.optJSONObject("settings")
                ?.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
            "trojan", "shadowsocks", "socks", "http" -> outbound.optJSONObject("settings")
                ?.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
            "wireguard" -> outbound.optJSONObject("settings")
                ?.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint")
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (address.startsWith("[")) {
            return address.substringAfter("[").substringBefore("]").takeIf { it.isNotEmpty() }
        }
        val host = if (address.count { it == ':' } == 1) address.substringBeforeLast(':') else address
        return host.takeIf { it.isNotBlank() && !it.matches(Regex("[0-9.]+")) && !it.contains(':') }
    }

    fun addResolveAddress(dnsHosts: JSONObject, outbound: JSONObject, metadata: JSONObject?) {
        val resolved = metadata?.optString("resolve-address")?.trim().orEmpty()
        val host = endpointHost(outbound)
        if (resolved.isEmpty() || host.isNullOrBlank()) return
        if (isIpLiteral(resolved)) dnsHosts.put(host, resolved)
    }

    /**
     * Happ can resolve the proxy endpoint with a dedicated DoH resolver before
     * the tunnel starts. Recreate that resolver as a domain-scoped Xray DNS
     * server, keeping its bootstrap IP pinned in dns.hosts.
     */
    fun addServerResolutionDns(dns: JSONObject, dnsHosts: JSONObject, outbound: JSONObject, metadata: JSONObject?) {
        if (boolean(metadata, "resolve-server-address-enable") != true) return
        val endpoint = endpointHost(outbound) ?: return
        val dohUrl = metadata?.optString("server-address-resolve-dns-domain")?.trim().orEmpty()
        if (!dohUrl.startsWith("https://", true)) return

        val resolverHost = runCatching { URL(dohUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val bootstrapIp = metadata?.optString("server-address-resolve-dns-ip")?.trim().orEmpty()
        if (isIpLiteral(bootstrapIp)) dnsHosts.put(resolverHost, bootstrapIp)

        val servers = dns.optJSONArray("servers") ?: org.json.JSONArray().also { dns.put("servers", it) }
        servers.put(0, JSONObject()
            .put("address", dohUrl)
            .put("domains", org.json.JSONArray().put(endpoint)))
    }

    private fun isIpLiteral(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.contains("%")) return false
        if (candidate.contains(":")) return candidate.matches(Regex("[0-9a-fA-F:]+"))
        val parts = candidate.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun preferredIpQueryStrategy(metadata: JSONObject?): String {
        return when (metadata?.optString("preferred-ip-type")?.trim()) {
            "0", "IPv4", "ipv4" -> "UseIPv4"
            "1", "IPv6", "ipv6" -> "UseIPv6"
            else -> "UseIP"
        }
    }
}
