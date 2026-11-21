package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class TurnSettingsActivity : Activity() {

    private val notificationPermissionRequestCode = 4103
    private var saveAfterPermissionResult = false

    private lateinit var enabledSwitch: Switch
    private lateinit var peerInput: EditText
    private lateinit var vkLinkInput: EditText
    private lateinit var portInput: EditText
    private lateinit var threadsInput: EditText
    private lateinit var streamsInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val prefs = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(12) + insets.systemWindowInsetTop,
                dp(18), dp(20) + insets.systemWindowInsetBottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnClickListener { finish() }
        })

        root.addView(TextView(this).apply {
            text = "TURN-клиент"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })

        enabledSwitch = Switch(this).apply {
            text = "Использовать TURN для WireGuard"
            textSize = 16f
            isChecked = prefs.getBoolean(KEY_ENABLED, false)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        root.addView(enabledSwitch)

        root.addView(TextView(this).apply {
            text = "TURN-клиент будет принимать WireGuard UDP-трафик локально и передавать его через VK Calls на твой TURN-сервер. Остальные типы серверов JPN не затрагиваются."
            textSize = 13f
            setPadding(0, dp(2), 0, dp(14))
        })

        peerInput = addField(root, "Адрес TURN-сервера (host:port)",
            prefs.getString(KEY_PEER, "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        vkLinkInput = addField(root, "Ссылка VK Calls",
            prefs.getString(KEY_VK_LINK, "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        portInput = addField(root, "Локальный UDP-порт",
            prefs.getString(KEY_LOCAL_PORT, "9000") ?: "9000",
            InputType.TYPE_CLASS_NUMBER)

        threadsInput = addField(root, "Количество потоков",
            prefs.getString(KEY_THREADS, "12") ?: "12",
            InputType.TYPE_CLASS_NUMBER)

        streamsInput = addField(root, "Потоков на одну учётную запись",
            prefs.getString(KEY_STREAMS_PER_CRED, "12") ?: "12",
            InputType.TYPE_CLASS_NUMBER)

        root.addView(TextView(this).apply {
            text = "На первом этапе реализован UDP-режим. Для подключения выбери в JPN WireGuard-профиль. Локальный адрес будет 127.0.0.1, порт задаётся выше. Обработка капчи VK через WebView добавляется отдельным этапом."
            textSize = 12f
            setPadding(0, dp(10), 0, dp(18))
        })

        val save = Button(this).apply {
            text = "Сохранить"
            setOnClickListener { saveSettings() }
        }
        root.addView(save, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "Диагностика TURN"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Журнал запуска и соединения поможет определить, на каком этапе останавливается подключение."
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "Открыть журнал диагностики"
            setOnClickListener {
                startActivity(Intent(this@TurnSettingsActivity, TurnDiagnosticsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        JpnTheme.apply(this, scroll)
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        inputType: Int
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(8), 0, dp(5))
        })
        return EditText(this).apply {
            this.inputType = inputType
            setSingleLine(true)
            setText(value)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun saveSettings() {
        val peer = peerInput.text.toString().trim()
        val vkLink = vkLinkInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val threads = threadsInput.text.toString().trim().toIntOrNull()
        val streams = streamsInput.text.toString().trim().toIntOrNull()
        val enabled = enabledSwitch.isChecked

        if (enabled && peer.isBlank()) {
            peerInput.error = "Укажи адрес TURN-сервера"
            return
        }
        if (enabled && (!vkLink.startsWith("https://") && !vkLink.startsWith("http://"))) {
            vkLinkInput.error = "Укажи ссылку VK Calls"
            return
        }
        if (port == null || port !in 1024..65535) {
            portInput.error = "Порт должен быть от 1024 до 65535"
            return
        }
        if (threads == null || threads !in 1..64) {
            threadsInput.error = "Количество потоков: от 1 до 64"
            return
        }
        if (streams == null || streams !in 1..64) {
            streamsInput.error = "Потоков на учётную запись: от 1 до 64"
            return
        }

        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            saveAfterPermissionResult = true
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequestCode
            )
            return
        }

        persistSettings(enabled, peer, vkLink, port, threads, streams)
    }

    private fun persistSettings(
        enabled: Boolean,
        peer: String,
        vkLink: String,
        port: Int,
        threads: Int,
        streams: Int
    ) {
        getSharedPreferences("jpn_settings", MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_PEER, peer)
            .putString(KEY_VK_LINK, vkLink)
            .putString(KEY_LOCAL_PORT, port.toString())
            .putString(KEY_THREADS, threads.toString())
            .putString(KEY_STREAMS_PER_CRED, streams.toString())
            .apply()

        Toast.makeText(
            this,
            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) "Настройки сохранены. Без разрешения Android уведомление CAPTCHA не появится"
            else "Настройки TURN сохранены",
            Toast.LENGTH_LONG
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequestCode || !saveAfterPermissionResult) return
        saveAfterPermissionResult = false

        val peer = peerInput.text.toString().trim()
        val vkLink = vkLinkInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: return
        val threads = threadsInput.text.toString().trim().toIntOrNull() ?: return
        val streams = streamsInput.text.toString().trim().toIntOrNull() ?: return
        persistSettings(enabledSwitch.isChecked, peer, vkLink, port, threads, streams)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_ENABLED = "turn_enabled"
        const val KEY_PEER = "turn_peer"
        const val KEY_VK_LINK = "turn_vk_link"
        const val KEY_LOCAL_PORT = "turn_local_port"
        const val KEY_THREADS = "turn_threads"
        const val KEY_STREAMS_PER_CRED = "turn_streams_per_cred"
    }
}
