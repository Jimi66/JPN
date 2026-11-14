package com.jpn.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodManager.HIDE_NOT_ALWAYS
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import libXray.LibXray
import com.jpn.app.vpn.XrayVpnService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JPN MVP: connect to one VLESS node pasted by the user.
 * HTTP subscription management and additional engines are intentionally out of scope.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"
        private const val KEY_LAST_VLESS = "last_vless_link"
        private const val KEY_VPN_STATE = "vpn_state"
        private const val VPN_REQUEST_CODE = 4102
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var vlessInput: EditText
    private lateinit var connectButton: Button
    private var pendingXrayConfig: String? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return
            val message = intent.getStringExtra("message") ?: state
            showState(state, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshVpnState()
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(XrayVpnService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStateReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(vpnStateReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::connectButton.isInitialized) refreshVpnState()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "JPN"
            textSize = 32f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Простое VPN-подключение"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(28))
        }

        statusView = TextView(this).apply {
            text = "Отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }

        vlessInput = EditText(this).apply {
            hint = "Вставь ссылку vless://…"
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(preferences.getString(KEY_LAST_VLESS, ""))
        }

        val pasteButton = Button(this).apply {
            text = "Вставить из буфера"
            setOnClickListener { pasteFromClipboard() }
        }

        connectButton = Button(this).apply {
            text = "Подключить"
            setOnClickListener { onConnectClicked() }
        }

        root.addView(title, matchWidth())
        root.addView(subtitle, matchWidth())
        root.addView(statusView, matchWidth())
        root.addView(vlessInput, matchWidth())
        root.addView(pasteButton, matchWidth())
        root.addView(connectButton, matchWidth())
        setContentView(root)
    }

    private fun onConnectClicked() {
        if (isVpnRunning()) {
            connectButton.isEnabled = false
            XrayVpnService.stop(this)
            setStatus("Отключение VPN…")
            return
        }

        val link = vlessInput.text.toString().trim()
        if (!link.startsWith("vless://", ignoreCase = true)) {
            setStatus("Нужна VLESS-ссылка, начинающаяся с vless://")
            return
        }

        preferences.edit().putString(KEY_LAST_VLESS, link).apply()
        hideKeyboard()
        connectButton.isEnabled = false
        setStatus("Проверка VLESS-ссылки…")

        Thread {
            try {
                val config = buildXrayConfigFromVless(link)
                mainHandler.post {
                    connectButton.isEnabled = true
                    pendingXrayConfig = config
                    val permissionIntent = VpnService.prepare(this)
                    if (permissionIntent != null) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
                    } else {
                        startVpnService(config)
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    connectButton.isEnabled = true
                    setStatus("Ошибка VLESS-ссылки: ${error.message ?: "не удалось разобрать"}")
                }
            }
        }.start()
    }

    private fun buildXrayConfigFromVless(link: String): String {
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", link))

        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success", false)) {
            throw IllegalArgumentException(
                response.optString("error").ifBlank { "Xray не смог разобрать ссылку" }
            )
        }

        val sourceOutbounds = response.optJSONObject("data")?.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В ссылке не найден VLESS-сервер")
        if (sourceOutbounds.length() == 0) {
            throw IllegalArgumentException("В ссылке не найден VLESS-сервер")
        }

        val outbounds = JSONArray()
        for (index in 0 until sourceOutbounds.length()) {
            outbounds.put(JSONObject(sourceOutbounds.getJSONObject(index).toString()))
        }

        // Keep the parsed VLESS outbound first: Xray uses it as the default outbound.
        outbounds.put(
            JSONObject()
                .put("tag", "jpn-direct")
                .put("protocol", "freedom")
                .put("settings", JSONObject())
        )
        outbounds.put(
            JSONObject()
                .put("tag", "jpn-block")
                .put("protocol", "blackhole")
                .put("settings", JSONObject())
        )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", outbounds)
            .toString()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return

        val config = pendingXrayConfig
        pendingXrayConfig = null
        if (resultCode == RESULT_OK && config != null) {
            startVpnService(config)
        } else {
            connectButton.isEnabled = true
            setStatus("Разрешение Android VPN не предоставлено")
        }
    }

    private fun startVpnService(config: String) {
        try {
            XrayVpnService.start(this, config)
            connectButton.isEnabled = false
            setStatus("Запуск VPN…")
        } catch (error: Exception) {
            connectButton.isEnabled = true
            setStatus("Не удалось запустить VPN: ${error.message ?: "ошибка"}")
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()

        if (value.isNullOrBlank()) {
            setStatus("В буфере обмена нет текста")
            return
        }
        vlessInput.setText(value)
        vlessInput.setSelection(vlessInput.text.length)
        setStatus("Ссылка вставлена")
    }

    private fun isVpnRunning(): Boolean {
        val savedState = preferences.getString(KEY_VPN_STATE, "disconnected")
        if (savedState == "connected") return true

        return runCatching {
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "getXrayState")
            val response = JSONObject(LibXray.invoke(request.toString()))
            response.opt("data") == true
        }.getOrDefault(false)
    }

    private fun refreshVpnState() {
        val running = isVpnRunning()
        connectButton.text = if (running) "Отключить" else "Подключить"
        connectButton.isEnabled = true
        if (running) {
            setStatus("VPN подключён")
        } else {
            val state = preferences.getString(KEY_VPN_STATE, "disconnected")
            val message = preferences.getString("vpn_message", "Отключено")
            setStatus(if (state == "error") message ?: "Ошибка VPN" else "Отключено")
        }
    }

    private fun showState(state: String, message: String) {
        connectButton.isEnabled = true
        connectButton.text = if (state == "connected") "Отключить" else "Подключить"
        setStatus(message)
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(vlessInput.windowToken, HIDE_NOT_ALWAYS)
        vlessInput.clearFocus()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
