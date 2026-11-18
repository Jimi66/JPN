package com.jpn.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.jpn.app.vpn.XrayVpnService
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android Quick Settings tile for toggling the JPN VPN connection.
 *
 * If Android VPN consent has already been granted, the tile starts/stops the
 * VPN service directly without opening MainActivity. MainActivity is needed
 * only for the first-time Android VPN consent screen.
 */
class QuickSettingsTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val preferences = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        val savedState = preferences.getString("vpn_state", "disconnected")

        if (savedState == "connected" || savedState == "connecting") {
            stopVpnFromTile(preferences)
            return
        }

        // Query native Xray state away from the TileService main thread. Native
        // calls can take time and must not block Android's System UI callback.
        Thread {
            if (isXrayRunning()) {
                mainHandler.post { stopVpnFromTile(preferences) }
            } else {
                mainHandler.post {
                    // Android requires an Activity for the system VPN consent prompt.
                    if (VpnService.prepare(this) != null) {
                        val consentIntent = Intent(this, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_QUICK_SETTINGS_TOGGLE
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivityAndCollapse(consentIntent)
                    } else {
                        Thread { startVpnInBackground() }.start()
                    }
                }
            }
        }.start()
    }

    private fun stopVpnFromTile(preferences: android.content.SharedPreferences) {
        // Reflect the user's tap immediately; the VPN service will publish
        // the final disconnected state once the tunnel has actually stopped.
        preferences.edit()
            .putString("vpn_state", "disconnecting")
            .putString("vpn_message", "Отключение…")
            .apply()
        refreshTile()
        XrayVpnService.stop(this)
    }

    private fun startVpnInBackground() {
        val preferences = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        try {
            val savedNodes = preferences.getString("saved_nodes_json", null)
                ?: throw IllegalStateException("Сначала добавь сервер в JPN")
            val nodes = JSONArray(savedNodes)
            if (nodes.length() == 0) {
                throw IllegalStateException("В JPN нет сохранённых серверов")
            }

            var selectedIndex = preferences.getInt("selected_node_index", -1)
            if (selectedIndex !in 0 until nodes.length()) {
                selectedIndex = 0
                preferences.edit().putInt("selected_node_index", selectedIndex).apply()
            }

            var outbound = JSONObject(nodes.getJSONObject(selectedIndex).toString())
            val serverName = outbound.optString("tag").ifBlank { "Сервер ${selectedIndex + 1}" }
            preferences.edit().putString("vpn_server_name", serverName).apply()
            val rawShareLink = outbound.optString("jpnRawShareLink")
            if (rawShareLink.isNotBlank()) {
                val parsedResponse = JSONObject(
                    LibXray.invoke(
                        JSONObject()
                            .put("apiVersion", 3)
                            .put("method", "convertShareLinksToXrayJson")
                            .put("payload", JSONObject().put("text", rawShareLink))
                            .toString()
                    )
                )
                if (!parsedResponse.optBoolean("success", false)) {
                    throw IllegalStateException("Не удалось подготовить выбранный сервер")
                }
                val parsedOutbounds = parsedResponse.optJSONObject("data")?.optJSONArray("outbounds")
                if (parsedOutbounds == null || parsedOutbounds.length() == 0) {
                    throw IllegalStateException("Xray не вернул конфигурацию сервера")
                }
                outbound = JSONObject(parsedOutbounds.getJSONObject(0).toString())
            }

            val config = buildXrayConfig(outbound, preferences)
            preferences.edit()
                .putString("vpn_state", "connecting")
                .putString("vpn_message", "Подключение…")
                .apply()
            XrayVpnService.start(this, config)
            mainHandler.post { refreshTile() }
        } catch (error: Exception) {
            preferences.edit()
                .putString("vpn_state", "error")
                .putString("vpn_message", error.message ?: "Не удалось запустить VPN")
                .apply()
            mainHandler.post {
                refreshTile()
                Toast.makeText(
                    this,
                    error.message ?: "Не удалось запустить VPN",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildXrayConfig(selectedOutbound: JSONObject, preferences: android.content.SharedPreferences): String {
        val directOutbound = JSONObject()
            .put("tag", "jpn-direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject())

        val outbounds = JSONArray()
            .put(JSONObject(selectedOutbound.toString()))
            .put(directOutbound)
        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", outbounds)
            .put("env", JSONObject().put("xray.location.asset", filesDir.absolutePath))

        if (preferences.getBoolean("site_routing_enabled", false)) {
            val domains = JSONArray()
            preferences.getString("site_direct_geosite", "").orEmpty()
                .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
                .distinct().forEach { domains.put("geosite:$it") }
            preferences.getString("site_manual_domains", "").orEmpty()
                .lineSequence().map { it.substringBefore("#").trim() }.filter { it.isNotBlank() }
                .distinct().forEach { entry ->
                    val rule = when {
                        entry.startsWith("domain:", true) ||
                            entry.startsWith("full:", true) ||
                            entry.startsWith("regexp:", true) ||
                            entry.startsWith("geosite:", true) -> entry
                        else -> "domain:$entry"
                    }
                    domains.put(rule)
                }

            val ips = JSONArray()
            preferences.getString("site_direct_geoip", "").orEmpty()
                .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
                .distinct().forEach { ips.put("geoip:$it") }
            preferences.getString("site_manual_ips", "").orEmpty()
                .lineSequence().map { it.substringBefore("#").trim() }.filter { it.isNotBlank() }
                .distinct().forEach { ips.put(it) }

            if (domains.length() > 0 || ips.length() > 0) {
                val bypassRules = JSONArray()
                if (domains.length() > 0) {
                    bypassRules.put(
                        JSONObject()
                            .put("type", "field")
                            .put("domain", domains)
                            .put("outboundTag", "jpn-direct")
                    )
                }
                if (ips.length() > 0) {
                    bypassRules.put(
                        JSONObject()
                            .put("type", "field")
                            .put("ip", ips)
                            .put("outboundTag", "jpn-direct")
                    )
                }
                config.put(
                    "routing",
                    JSONObject()
                        .put("domainStrategy", "AsIs")
                        .put("rules", bypassRules)
                )
            }
        }
        return config.toString()
    }

    private fun isXrayRunning(): Boolean {
        return runCatching {
            val response = JSONObject(
                LibXray.invoke(
                    JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "getXrayState")
                        .toString()
                )
            )
            response.opt("data") == true
        }.getOrDefault(false)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val preferences = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        val state = preferences.getString("vpn_state", "disconnected")
        val connected = state == "connected"
        val connecting = state == "connecting"
        val disconnecting = state == "disconnecting"

        val serverName = preferences.getString("vpn_server_name", "JPN")?.takeIf { it.isNotBlank() } ?: "JPN"
        tile.label = if (connected) serverName else "JPN"
        tile.icon = Icon.createWithResource(this, R.drawable.jpn_power_symbol)
        tile.state = if (connected || connecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                // Keep the server name in the live subtitle as well. Some System UI
                // implementations cache the primary tile label until the shade reopens.
                connected -> serverName
                connecting -> "Подключение…"
                disconnecting -> "Отключение…"
                state == "error" -> "Ошибка"
                else -> "Выключено"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                connected -> "VPN подключён"
                connecting -> "VPN подключается"
                disconnecting -> "VPN отключается"
                state == "error" -> preferences.getString("vpn_message", "Ошибка VPN")
                else -> "VPN выключен"
            }
        }
        tile.updateTile()
    }
}
