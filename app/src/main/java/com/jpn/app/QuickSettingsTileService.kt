package com.jpn.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android Quick Settings tile for toggling the JPN VPN connection.
 * Starting requires the normal Android VPN consent flow, handled by MainActivity.
 */
class QuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_SETTINGS_TOGGLE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                4103,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val preferences = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        val state = preferences.getString("vpn_state", "disconnected")
        val connected = state == "connected"
        val connecting = state == "connecting"

        tile.label = "JPN VPN"
        tile.icon = Icon.createWithResource(this, R.drawable.jpn_power_symbol)
        tile.state = if (connected || connecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                connected -> "Подключено"
                connecting -> "Подключение…"
                state == "error" -> "Ошибка"
                else -> "Выключено"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                connected -> "VPN подключён"
                connecting -> "VPN подключается"
                state == "error" -> preferences.getString("vpn_message", "Ошибка VPN")
                else -> "VPN выключен"
            }
        }
        tile.updateTile()
    }
}
