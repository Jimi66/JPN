package com.jpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Functional first-stage JPN screen.
 *
 * The layout is intentionally simple until the final UI sketch is supplied.
 * Subscriptions are stored locally; their complete URL is used for identity.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_SELECTED_URL = "selected_subscription_url"
        private const val VPN_REQUEST_CODE = 4102
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var subscriptionList: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var allowInsecureCheck: CheckBox
    private lateinit var hwidCookieCheck: CheckBox
    private lateinit var connectButton: Button
    private var pendingXrayConfig: String? = null

    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderSubscriptions()
        refreshVpnState()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val title = TextView(this).apply {
            text = "JPN"
            textSize = 30f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        statusView = TextView(this).apply {
            text = "Отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }

        connectButton = Button(this).apply {
            text = "Подключить"
            setOnClickListener { onConnectClicked() }
        }

        content.addView(title)
        content.addView(statusView)
        content.addView(connectButton, matchWidth())

        content.addView(sectionTitle("Подписки"))
        subscriptionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(subscriptionList)

        content.addView(sectionTitle("Добавить подписку"))
        nameInput = EditText(this).apply {
            hint = "Название (необязательно)"
            singleLine = true
            filters = arrayOf(InputFilter.LengthFilter(25))
        }
        urlInput = EditText(this).apply {
            hint = "URL подписки"
            singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        allowInsecureCheck = CheckBox(this).apply {
            text = "Разрешать небезопасные сертификаты"
        }
        hwidCookieCheck = CheckBox(this).apply {
            text = "Отправлять HWID в Cookie"
            isChecked = false
        }

        val pasteButton = Button(this).apply {
            text = "Вставить из буфера"
            setOnClickListener { pasteUrlFromClipboard() }
        }
        val addButton = Button(this).apply {
            text = "Добавить / обновить подписку"
            setOnClickListener { addSubscription() }
        }

        content.addView(nameInput, matchWidth())
        content.addView(urlInput, matchWidth())
        content.addView(pasteButton, matchWidth())
        content.addView(allowInsecureCheck)
        content.addView(hwidCookieCheck)
        content.addView(addButton, matchWidth())

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun onConnectClicked() {
        if (isVpnRunning()) {
            XrayVpnService.stop(this)
            setStatus("Отключение…")
            return
        }

        val subscription = selectedSubscription()
        if (subscription == null) {
            setStatus("Сначала добавь и выбери подписку")
            return
        }

        val config = try {
            buildXrayConfig(subscription)
        } catch (error: Exception) {
            setStatus("Ошибка конфигурации: ${error.message ?: "неизвестная ошибка"}")
            return
        }

        pendingXrayConfig = config
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
        } else {
            startVpnService(config)
        }
    }

    @Deprecated("Kept for compatibility with the minimum Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            val config = pendingXrayConfig
            pendingXrayConfig = null
            if (resultCode == RESULT_OK && config != null) {
                startVpnService(config)
            } else {
                setStatus("Разрешение Android VPN не предоставлено")
            }
        }
    }

    private fun startVpnService(config: String) {
        try {
            XrayVpnService.start(this, config)
            setStatus("Запуск VPN…")
            connectButton.text = "Отключить"
        } catch (error: Exception) {
            setStatus("Не удалось запустить VPN: ${error.message ?: "ошибка"}")
        }
    }

    private fun addSubscription() {
        val exactUrl = urlInput.text.toString()
        if (exactUrl.isEmpty()) {
            setStatus("Введи URL подписки")
            return
        }
        val parsedUrl = runCatching { URL(exactUrl) }.getOrNull()
        if (parsedUrl == null || (parsedUrl.protocol != "http" && parsedUrl.protocol != "https")) {
            setStatus("Укажи корректный HTTP или HTTPS URL")
            return
        }

        val requestedName = nameInput.text.toString()
        val allowInsecure = allowInsecureCheck.isChecked
        val hwidInCookie = hwidCookieCheck.isChecked
        setStatus("Загрузка подписки…")

        Thread {
            try {
                val subscriptionText = downloadSubscription(exactUrl, hwidInCookie)
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "convertShareLinksToXrayJson")
                    .put("payload", JSONObject().put("text", subscriptionText))
                val response = JSONObject(LibXray.invoke(request.toString()))
                if (!response.optBoolean("success", false)) {
                    throw IllegalArgumentException(
                        response.optString("error", "Не удалось разобрать подписку")
                    )
                }
                val outbounds = response.optJSONObject("data")?.optJSONArray("outbounds")
                    ?: throw IllegalArgumentException("В подписке не найдены серверы")
                if (outbounds.length() == 0) {
                    throw IllegalArgumentException("В подписке нет подходящих серверов")
                }

                mainHandler.post {
                    saveSubscription(
                        exactUrl,
                        requestedName,
                        outbounds,
                        allowInsecure,
                        hwidInCookie
                    )
                    urlInput.setText("")
                    nameInput.setText("")
                    setStatus("Подписка успешно добавлена")
                    renderSubscriptions()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    setStatus("Ошибка подписки: ${error.message ?: "неизвестная ошибка"}")
                }
            }
        }.start()
    }

    private fun downloadSubscription(exactUrl: String, hwidInCookie: Boolean): String {
        val hwid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
        val connection = (URL(exactUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "JPN/0.2.0 Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
            setRequestProperty("X-Android-Version", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            setRequestProperty("X-HWID", hwid)
            if (hwidInCookie) {
                setRequestProperty("Cookie", "HWID=$hwid")
            }
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveSubscription(
        exactUrl: String,
        requestedName: String,
        outbounds: JSONArray,
        allowInsecure: Boolean,
        hwidInCookie: Boolean
    ) {
        val subscriptions = readSubscriptions()
        var existingIndex = -1
        for (i in 0 until subscriptions.length()) {
            if (subscriptions.getJSONObject(i).optString("url") == exactUrl) {
                existingIndex = i
                break
            }
        }

        val subscription = if (existingIndex >= 0) {
            // Exact URL match: replace server data, preserve the existing name and options.
            subscriptions.getJSONObject(existingIndex)
        } else {
            JSONObject().apply {
                put("url", exactUrl)
                put("name", requestedName.ifBlank { "Подписка ${subscriptions.length() + 1}" })
                put("allowInsecure", allowInsecure)
                put("hwidInCookie", hwidInCookie)
            }
        }

        subscription.put("outbounds", JSONArray(outbounds.toString()))
        if (existingIndex >= 0) {
            subscriptions.put(existingIndex, subscription)
            setStatus("Эта подписка уже существует. Подписка успешно обновлена")
        } else {
            subscriptions.put(subscription)
        }

        preferences.edit()
            .putString(KEY_SUBSCRIPTIONS, subscriptions.toString())
            .putString(KEY_SELECTED_URL, exactUrl)
            .apply()
    }

    private fun pasteUrlFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = clipboard.primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (value.isNullOrEmpty()) {
            setStatus("В буфере обмена нет текста")
        } else {
            urlInput.setText(value)
            urlInput.setSelection(urlInput.text.length)
        }
    }

    private fun renderSubscriptions() {
        subscriptionList.removeAllViews()
        val subscriptions = readSubscriptions()
        val selectedUrl = preferences.getString(KEY_SELECTED_URL, null)

        if (subscriptions.length() == 0) {
            subscriptionList.addView(TextView(this).apply {
                text = "Пока нет добавленных подписок"
                textSize = 14f
                setPadding(0, dp(6), 0, dp(10))
            })
            return
        }

        for (i in 0 until subscriptions.length()) {
            val item = subscriptions.getJSONObject(i)
            val url = item.optString("url")
            val isSelected = url == selectedUrl
            val outbounds = item.optJSONArray("outbounds") ?: JSONArray()
            val row = Button(this).apply {
                text = (if (isSelected) "✓ " else "") +
                    item.optString("name", "Подписка") +
                    "  ·  ${outbounds.length()} сервер(ов)"
                textSize = 14f
                isAllCaps = false
                setOnClickListener {
                    preferences.edit().putString(KEY_SELECTED_URL, url).apply()
                    renderSubscriptions()
                    setStatus("Выбрана подписка: ${item.optString("name")}")
                }
                setOnLongClickListener {
                    if (isVpnRunning()) {
                        setStatus("Отключи VPN перед удалением подписки")
                    } else {
                        subscriptions.remove(i)
                        preferences.edit().putString(KEY_SUBSCRIPTIONS, subscriptions.toString()).apply()
                        renderSubscriptions()
                        setStatus("Подписка удалена")
                    }
                    true
                }
            }
            subscriptionList.addView(row, matchWidth())
        }
        subscriptionList.addView(TextView(this).apply {
            text = "Нажми на подписку, чтобы выбрать. Долгое нажатие — удалить."
            textSize = 11f
            setPadding(4, dp(4), 4, dp(8))
        })
    }

    private fun selectedSubscription(): JSONObject? {
        val selectedUrl = preferences.getString(KEY_SELECTED_URL, null) ?: return null
        val subscriptions = readSubscriptions()
        for (i in 0 until subscriptions.length()) {
            val item = subscriptions.getJSONObject(i)
            if (item.optString("url") == selectedUrl) return item
        }
        return null
    }

    private fun buildXrayConfig(subscription: JSONObject): String {
        val sourceOutbounds = subscription.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Нет серверов в подписке")
        if (sourceOutbounds.length() == 0) throw IllegalArgumentException("Нет серверов в подписке")

        val outbounds = JSONArray()
        for (i in 0 until sourceOutbounds.length()) {
            val outbound = JSONObject(sourceOutbounds.getJSONObject(i).toString())
            if (subscription.optBoolean("allowInsecure", false)) {
                val stream = outbound.optJSONObject("streamSettings")
                val tls = stream?.optJSONObject("tlsSettings")
                tls?.put("allowInsecure", true)
            }
            outbounds.put(outbound)
        }
        outbounds.put(JSONObject()
            .put("tag", "jpn-direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject()))
        outbounds.put(JSONObject()
            .put("tag", "jpn-block")
            .put("protocol", "blackhole")
            .put("settings", JSONObject()))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", outbounds)
            .toString()
    }

    private fun readSubscriptions(): JSONArray {
        val raw = preferences.getString(KEY_SUBSCRIPTIONS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun isVpnRunning(): Boolean {
        val request = JSONObject().put("apiVersion", 3).put("method", "getXrayState")
        return runCatching {
            JSONObject(LibXray.invoke(request.toString()))
                .optJSONObject("data")
                ?.optBoolean("running", false) == true
        }.getOrDefault(false)
    }

    private fun refreshVpnState() {
        val running = isVpnRunning()
        connectButton.text = if (running) "Отключить" else "Подключить"
        if (running) setStatus("VPN подключён") else setStatus("Отключено")
    }

    override fun onResume() {
        super.onResume()
        if (::connectButton.isInitialized) refreshVpnState()
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
