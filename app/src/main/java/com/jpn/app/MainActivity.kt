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
import android.util.Base64
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodManager.HIDE_NOT_ALWAYS
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import libXray.LibXray
import com.jpn.app.vpn.XrayVpnService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * JPN MVP: HTTP(S) subscription import and Xray server selection.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"

        private const val KEY_SUBSCRIPTION_URL = "subscription_url"
        private const val KEY_VPN_STATE = "vpn_state"
        private const val VPN_REQUEST_CODE = 4102
        private const val MAX_SUBSCRIPTION_BYTES = 16 * 1024 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var subscriptionInput: EditText

    private lateinit var loadSubscriptionButton: Button
    private lateinit var nodesContainer: LinearLayout
    private lateinit var nodesHeader: TextView
    private var pendingXrayConfig: String? = null
    private var pendingSelectedOutbound: JSONObject? = null
    private var pendingSelectedName: String? = null
    private val nodes = mutableListOf<JSONObject>()

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return
            val message = intent.getStringExtra("message") ?: state
            showState(state, message)
            if (state == "disconnected" && pendingSelectedOutbound != null) {
                val outbound = pendingSelectedOutbound
                val name = pendingSelectedName ?: "Выбранный сервер"
                pendingSelectedOutbound = null
                pendingSelectedName = null
                if (outbound != null) mainHandler.postDelayed({ connectToOutbound(outbound, name) }, 300)
            }
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
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }

        val title = TextView(this).apply {
            text = "JPN"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "VPN на базе Xray"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }
        statusView = TextView(this).apply {
            text = "Отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
            // Allow long-press selection and copying of subscription diagnostics.
            setTextIsSelectable(true)
        }

        val subscriptionTitle = sectionTitle("Подписка")
        subscriptionInput = EditText(this).apply {
            hint = "https://адрес-подписки"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(preferences.getString(KEY_SUBSCRIPTION_URL, ""))
        }
        loadSubscriptionButton = Button(this).apply {
            text = "Загрузить / обновить подписку"
            setOnClickListener { loadSubscription() }
        }
        nodesHeader = TextView(this).apply {
            text = "Серверы пока не загружены"
            textSize = 15f
            setPadding(0, dp(14), 0, dp(6))
        }
        nodesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val pasteButton = Button(this).apply {
            text = "Вставить ссылку из буфера"
            setOnClickListener { pasteFromClipboard() }
        }
        val disconnectButton = Button(this).apply {
            text = "Отключить VPN"
            setOnClickListener {
                pendingSelectedOutbound = null
                pendingSelectedName = null
                XrayVpnService.stop(this@MainActivity)
                setStatus("Отключение VPN…")
            }
        }

        content.addView(title, matchWidth())
        content.addView(subtitle, matchWidth())
        content.addView(statusView, matchWidth())
        content.addView(subscriptionTitle, matchWidth())
        content.addView(subscriptionInput, matchWidth())
        content.addView(pasteButton, matchWidth())
        content.addView(loadSubscriptionButton, matchWidth())
        content.addView(nodesHeader, matchWidth())
        content.addView(nodesContainer, matchWidth())
        content.addView(disconnectButton, matchWidth())


        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun loadSubscription() {
        val subscriptionUrl = subscriptionInput.text.toString().trim()
        val parsedUrl = runCatching { URL(subscriptionUrl) }.getOrNull()
        if (parsedUrl == null || parsedUrl.protocol != "https") {
            setStatus("Укажи корректную HTTPS-ссылку на подписку")
            return
        }

        preferences.edit().putString(KEY_SUBSCRIPTION_URL, subscriptionUrl).apply()
        loadSubscriptionButton.isEnabled = false
        setStatus("Загружаю подписку…")

        Thread {
            try {
                val body = downloadSubscription(subscriptionUrl)
                val subscriptionText = decodeSubscriptionBody(body)
                val sourceCount = countSubscriptionCandidates(subscriptionText)
                val parsedNodes = parseShareLinks(subscriptionText)
                if (parsedNodes.length() == 0) {
                    throw IllegalArgumentException("В подписке не найдено поддерживаемых серверов")
                }

                val imported = ArrayList<JSONObject>()
                for (index in 0 until parsedNodes.length()) {
                    val node = JSONObject(parsedNodes.getJSONObject(index).toString())
                    if (node.optString("tag").isBlank()) {
                        node.put("tag", "Сервер ${index + 1}")
                    }
                    imported.add(node)
                }

                mainHandler.post {
                    nodes.clear()
                    nodes.addAll(imported)
                    renderNodes()
                    loadSubscriptionButton.isEnabled = true
                    val rejectedIndexes = findRejectedCandidateIndexes(subscriptionText)
                    val rejected = (sourceCount - nodes.size).coerceAtLeast(0)
                    val rejectedDetails = if (rejectedIndexes.isNotEmpty()) {
                        "; отклонены: ${rejectedIndexes.joinToString("; ")}"
                    } else {
                        ""
                    }
                    val parameterDetails = describeSubscriptionCandidates(subscriptionText)
                    val parameterSummary = if (parameterDetails.isNotEmpty()) {
                        "; параметры VLESS: ${parameterDetails.joinToString("; ")}"
                    } else {
                        ""
                    }
                    val diagnostic = if (sourceCount > 0) {
                        "Подписка: найдено $sourceCount, принято ${nodes.size}, отклонено $rejected$rejectedDetails$parameterSummary"
                    } else {
                        "Подписка загружена: серверов ${nodes.size} (формат источника не позволяет посчитать записи)"
                    }
                    setStatus(diagnostic)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    loadSubscriptionButton.isEnabled = true
                    setStatus("Ошибка подписки: ${error.message ?: "не удалось загрузить"}")
                }
            }
        }.start()
    }

    private fun downloadSubscription(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "JPN/0.4.0")
            connection.setRequestProperty("Accept", "text/plain, application/json, */*")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("сервер вернул HTTP $code")
            }

            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SUBSCRIPTION_BYTES) {
                        throw IllegalStateException("подписка превышает 16 МБ")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSubscriptionBody(body: String): String {
        val source = body.removePrefix("\uFEFF").trim()
        if (looksLikeSubscription(source)) return source

        val compact = source.filterNot { it.isWhitespace() }
        val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flag in flags) {
            val decoded = runCatching {
                String(Base64.decode(compact, flag), StandardCharsets.UTF_8).trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank() && looksLikeSubscription(decoded)) {
                return decoded
            }
        }
        // libXray also recognizes supported Base64 subscription wrappers.
        return source
    }

    private fun looksLikeSubscription(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("vless://") ||
            lower.startsWith("vmess://") ||
            lower.startsWith("trojan://") ||
            lower.startsWith("ss://") ||
            lower.startsWith("socks://") ||
            lower.startsWith("hysteria2://") ||
            lower.startsWith("hy2://") ||
            lower.startsWith("{") ||
            lower.startsWith("[") ||
            lower.contains("\nvless://") ||
            lower.contains("\nvmess://") ||
            lower.contains("\ntrojan://") ||
            lower.contains("\nss://")
    }

    /**
     * Counts share-link entries in decoded subscription text without logging or
     * displaying any server addresses, IDs, passwords, or other credentials.
     * For Xray JSON subscriptions, counts the outbounds array.
     */
    private fun countSubscriptionCandidates(text: String): Int {
        val source = text.removePrefix("\uFEFF").trim()
        if (source.startsWith("{")) {
            val jsonCount = runCatching {
                JSONObject(source).optJSONArray("outbounds")?.length()
            }.getOrNull()
            if (jsonCount != null) return jsonCount
        }

        val schemePattern = Regex(
            "(?i)(?:^|[\\r\\n])\\s*(?:vless|vmess|trojan|ss|socks|hysteria2|hy2)://"
        )
        val directCount = schemePattern.findAll(source).count()
        if (directCount > 0) return directCount

        // A subscription may be Base64 wrapped. Decode only for counting;
        // libXray remains responsible for the actual parsing and validation.
        val compact = source.filterNot { it.isWhitespace() }
        val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
        for (flag in flags) {
            val decoded = runCatching {
                String(Base64.decode(compact, flag), StandardCharsets.UTF_8).trim()
            }.getOrNull() ?: continue
            val decodedCount = schemePattern.findAll(decoded).count()
            if (decodedCount > 0) return decodedCount
            if (decoded.startsWith("{")) {
                val jsonCount = runCatching {
                    JSONObject(decoded).optJSONArray("outbounds")?.length()
                }.getOrNull()
                if (jsonCount != null) return jsonCount
            }
        }
        return 0
    }

    /**
     * Re-validates each subscription entry independently to identify which
     * entry numbers are rejected by libXray. No link contents or credentials
     * are logged or shown to the user.
     */
    private fun findRejectedCandidateIndexes(text: String): List<String> {
        val source = text.removePrefix("\uFEFF").trim()
        val candidates = mutableListOf<Pair<String, String>>()

        if (source.startsWith("{")) {
            val outbounds = runCatching {
                JSONObject(source).optJSONArray("outbounds")
            }.getOrNull()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    val protocol = outbound.optString("protocol").ifBlank { "неизвестный протокол" }
                    val config = JSONObject().put("outbounds", JSONArray().put(outbound))
                    candidates.add(config.toString() to protocol)
                }
            }
        } else {
            val lines = source.lineSequence().map { it.trim() }
                .filter { line ->
                    val scheme = line.substringBefore("://", "")
                    line.contains("://") &&
                        scheme.matches(Regex("(?i)vless|vmess|trojan|ss|socks|hysteria2|hy2"))
                }
            for (line in lines) {
                val protocol = line.substringBefore("://").uppercase()
                    .let { if (it == "HY2") "HYSTERIA2" else it }
                candidates.add(line to protocol)
            }
        }

        if (candidates.isEmpty()) {
            val compact = source.filterNot { it.isWhitespace() }
            val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
            for (flag in flags) {
                val decoded = runCatching {
                    String(Base64.decode(compact, flag), StandardCharsets.UTF_8).trim()
                }.getOrNull() ?: continue
                if (decoded.isNotBlank() && decoded != source) {
                    val result = findRejectedCandidateIndexes(decoded)
                    if (result.isNotEmpty()) return result
                }
            }
            return emptyList()
        }

        return candidates.mapIndexedNotNull { index, candidate ->
            val accepted = runCatching {
                val response = JSONObject(
                    LibXray.invoke(
                        JSONObject()
                            .put("apiVersion", 3)
                            .put("method", "convertShareLinksToXrayJson")
                            .put("payload", JSONObject().put("text", candidate.first))
                            .toString()
                    )
                )
                response.optBoolean("success", false)
            }.getOrDefault(false)

            if (accepted) {
                null
            } else {
                // libXray hides raw builder errors because they may contain credentials.
                "№${index + 1} (${candidate.second}): ошибка проверки/сборки конфигурации Xray"
            }
        }
    }

    /**
     * Shows only non-secret VLESS transport/security metadata for comparison.
     * Values such as server, UUID, password, SNI, keys, host and paths are never shown.
     */
    private fun describeSubscriptionCandidates(text: String): List<String> {
        val source = text.removePrefix("\uFEFF").trim()
        val candidates = mutableListOf<Pair<String, String>>()

        if (source.startsWith("{")) {
            val outbounds = runCatching {
                JSONObject(source).optJSONArray("outbounds")
            }.getOrNull()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    if (!outbound.optString("protocol").equals("vless", true)) continue
                    candidates.add("json" to outbound.toString())
                }
            }
        } else {
            source.lineSequence().map { it.trim() }
                .filter { it.startsWith("vless://", true) }
                .forEach { candidates.add("uri" to it) }
        }

        if (candidates.isEmpty()) {
            val compact = source.filterNot { it.isWhitespace() }
            val flags = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
            for (flag in flags) {
                val decoded = runCatching {
                    String(Base64.decode(compact, flag), StandardCharsets.UTF_8).trim()
                }.getOrNull() ?: continue
                if (decoded.isNotBlank() && decoded != source) {
                    val result = describeSubscriptionCandidates(decoded)
                    if (result.isNotEmpty()) return result
                }
            }
            return emptyList()
        }

        return candidates.mapIndexed { index, candidate ->
            val security: String
            val network: String
            val flowPresent: Boolean
            val sniPresent: Boolean
            val publicKeyPresent: Boolean
            val shortIdPresent: Boolean
            val fingerprintPresent: Boolean
            val pathPresent: Boolean
            val hostPresent: Boolean
            val serviceNamePresent: Boolean

            if (candidate.first == "json") {
                val outbound = JSONObject(candidate.second)
                val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
                security = stream.optString("security").ifBlank { "не задана" }
                network = stream.optString("network").ifBlank { "не задан" }
                val settings = outbound.optJSONObject("settings") ?: JSONObject()
                val vnext = settings.optJSONArray("vnext")
                val user = vnext?.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)
                flowPresent = !user?.optString("flow").isNullOrBlank()
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                sniPresent = !tls.optString("serverName").isBlank() ||
                    !reality.optString("serverName").isBlank()
                publicKeyPresent = !reality.optString("publicKey").isBlank()
                shortIdPresent = !reality.optString("shortId").isBlank()
                fingerprintPresent = !tls.optString("fingerprint").isBlank() ||
                    !reality.optString("fingerprint").isBlank()
                val transport = stream.optJSONObject("wsSettings")
                    ?: stream.optJSONObject("httpSettings")
                    ?: stream.optJSONObject("splithttpSettings")
                    ?: JSONObject()
                pathPresent = !transport.optString("path").isBlank()
                hostPresent = !transport.optJSONObject("headers")?.optString("Host").isNullOrBlank() ||
                    !transport.optString("host").isBlank()
                serviceNamePresent = !stream.optJSONObject("grpcSettings")
                    ?.optString("serviceName").isNullOrBlank()
            } else {
                val uri = android.net.Uri.parse(candidate.second)
                security = uri.getQueryParameter("security") ?: "не задана"
                network = uri.getQueryParameter("type") ?: "не задан"
                flowPresent = !uri.getQueryParameter("flow").isNullOrBlank()
                sniPresent = !uri.getQueryParameter("sni").isNullOrBlank()
                publicKeyPresent = !uri.getQueryParameter("pbk").isNullOrBlank()
                shortIdPresent = !uri.getQueryParameter("sid").isNullOrBlank()
                fingerprintPresent = !uri.getQueryParameter("fp").isNullOrBlank()
                pathPresent = !uri.getQueryParameter("path").isNullOrBlank()
                hostPresent = !uri.getQueryParameter("host").isNullOrBlank()
                serviceNamePresent = !uri.getQueryParameter("serviceName").isNullOrBlank()
            }

            fun yesNo(value: Boolean) = if (value) "есть" else "нет"
            "№\${index + 1} security=$security, transport=$network, flow=\${yesNo(flowPresent)}, " +
                "SNI=\${yesNo(sniPresent)}, pbk=\${yesNo(publicKeyPresent)}, sid=\${yesNo(shortIdPresent)}, " +
                "fp=\${yesNo(fingerprintPresent)}, path=\${yesNo(pathPresent)}, host=\${yesNo(hostPresent)}, " +
                "serviceName=\${yesNo(serviceNamePresent)}"
        }
    }

    private fun parseShareLinks(text: String): JSONArray {
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", text))
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success", false)) {
            throw IllegalArgumentException(
                response.optString("error").ifBlank { "Xray не смог разобрать подписку" }
            )
        }
        return response.optJSONObject("data")?.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Xray не вернул список серверов")
    }

    private fun renderNodes() {
        nodesContainer.removeAllViews()
        nodesHeader.text = if (nodes.isEmpty()) {
            "Серверы не найдены"
        } else {
            "Доступные серверы: ${nodes.size}. Нажми на сервер для подключения:"
        }

        nodes.forEachIndexed { index, node ->
            val name = node.optString("tag").ifBlank { "Сервер ${index + 1}" }
            val button = Button(this).apply {
                text = name
                setOnClickListener { connectToOutbound(node, name) }
            }
            nodesContainer.addView(button, matchWidth())
        }
    }

    private fun connectToOutbound(outbound: JSONObject, name: String) {
        if (isVpnRunning()) {
            pendingSelectedOutbound = JSONObject(outbound.toString())
            pendingSelectedName = name
            setStatus("Переключаюсь на сервер: $name…")
            XrayVpnService.stop(this)
            return
        }

        hideKeyboard()
        setStatus("Подключаюсь: $name…")

        Thread {
            try {
                val config = buildXrayConfig(outbound)
                mainHandler.post {
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
                    setStatus("Ошибка конфигурации: ${error.message ?: "не удалось подготовить"}")
                }
            }
        }.start()
    }

    private fun buildXrayConfig(selectedOutbound: JSONObject): String {
        val outbounds = JSONArray()
        outbounds.put(JSONObject(selectedOutbound.toString()))
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
            setStatus("Разрешение Android VPN не предоставлено")
        }
    }

    private fun startVpnService(config: String) {
        try {
            XrayVpnService.start(this, config)
            setStatus("Запуск VPN…")
        } catch (error: Exception) {
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
        subscriptionInput.setText(value)
        subscriptionInput.setSelection(subscriptionInput.text.length)
        setStatus("Ссылка вставлена в поле «Подписка»")
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
        if (running) {
            setStatus("VPN подключён")
        } else {
            val state = preferences.getString(KEY_VPN_STATE, "disconnected")
            val message = preferences.getString("vpn_message", "Отключено")
            setStatus(if (state == "error") message ?: "Ошибка VPN" else "Отключено")
        }
    }

    private fun showState(state: String, message: String) {
        setStatus(message)
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(subscriptionInput.windowToken, HIDE_NOT_ALWAYS)
        subscriptionInput.clearFocus()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
