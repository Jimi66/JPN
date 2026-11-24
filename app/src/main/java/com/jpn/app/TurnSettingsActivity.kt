package com.jpn.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class TurnSettingsActivity : Activity() {

    private lateinit var vkLinkInput: EditText
    private lateinit var threadsInput: EditText
    private lateinit var streamsPerCredInput: EditText

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
            text = "Ссылка VK Call"
            textSize = 22f
            setPadding(0, 0, 0, dp(14))
        })

        root.addView(TextView(this).apply {
            text = "Ссылка VK Call"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(5))
        })
        vkLinkInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = "Вставь ссылку VK Call"
            setText(prefs.getString(KEY_VK_LINK, "").orEmpty())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(vkLinkInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "Общее количество подключений"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(5))
        })
        threadsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Например, 5"
            setText(prefs.getString(KEY_THREADS, "5") ?: "5")
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(threadsInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "Подключений на один набор учётных данных"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(5))
        })
        streamsPerCredInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Например, 5"
            setText(prefs.getString(KEY_STREAMS_PER_CRED, "5") ?: "5")
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(streamsPerCredInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener { saveSettings() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        JpnTheme.apply(this, scroll)
    }

    private fun saveSettings() {
        val link = vkLinkInput.text.toString().trim()
        if (link.isNotEmpty() &&
            !link.startsWith("https://", true) &&
            !link.startsWith("http://", true)
        ) {
            vkLinkInput.error = "Укажи корректную ссылку VK Call"
            return
        }
        val threads = threadsInput.text.toString().trim().toIntOrNull()
        if (threads == null || threads !in 1..64) {
            threadsInput.error = "Укажи число от 1 до 64"
            return
        }
        val streamsPerCred = streamsPerCredInput.text.toString().trim().toIntOrNull()
        if (streamsPerCred == null || streamsPerCred !in 1..64) {
            streamsPerCredInput.error = "Укажи число от 1 до 64"
            return
        }
        getSharedPreferences("jpn_settings", MODE_PRIVATE).edit()
            .putString(KEY_VK_LINK, link)
            .putString(KEY_THREADS, threads.toString())
            .putString(KEY_STREAMS_PER_CRED, streamsPerCred.toString())
            .apply()
        Toast.makeText(this, "Настройки TURN сохранены", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        // Retained preference keys keep existing TURN configuration compatible.
        const val KEY_ENABLED = "turn_enabled"
        const val KEY_PEER = "turn_peer"
        const val KEY_VK_LINK = "turn_vk_link"
        const val KEY_LOCAL_PORT = "turn_local_port"
        const val KEY_THREADS = "turn_threads"
        const val KEY_STREAMS_PER_CRED = "turn_streams_per_cred"
    }
}
