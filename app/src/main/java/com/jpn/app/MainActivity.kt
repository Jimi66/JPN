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
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import libXray.LibXray
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
        private const val KEY_SUBSCRIPTION_CONTENT = "subscription_content"
        private const val KEY_SAVED_NODES = "saved_nodes_json"
        private const val KEY_VPN_STATE = "vpn_state"
        private const val KEY_SELECTED_NODE_INDEX = "selected_node_index"
        private const val VPN_REQUEST_CODE = 4102
        private const val MAX_SUBSCRIPTION_BYTES = 16 * 1024 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var subscriptionInput: EditText

    private lateinit var loadSubscriptionButton: Button
    private lateinit var nodesContainer: ListView
    private lateinit var nodesHeader: TextView
    private var pendingXrayConfig: String? = null
    private var pendingSelectedOutbound: JSONObject? = null
    private var pendingSelectedName: String? = null
    private val nodes = mutableListOf<JSONObject>()
    private var selectedNodeIndex = -1

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
        restoreSavedSubscription()
        refreshVpnState()
        if (nodes.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Серверы не добавлены")
                .setMessage("Нажмите «+», чтобы добавить подписку.")
                .setPositiveButton("Понятно", null)
                .show()
        }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        val title = TextView(this).apply {
            text = "JPN"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }

        statusView = TextView(this).apply {
            text = "Отключено"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(12))
            setTextIsSelectable(true)
        }

        // The subscription field remains available to the existing import
        // pipeline, but is no longer part of the main screen.
        subscriptionInput = EditText(this).apply {
            hint = "https://адрес-подписки"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(preferences.getString(KEY_SUBSCRIPTION_URL, ""))
            visibility = android.view.View.GONE
        }
        loadSubscriptionButton = Button(this).apply {
            text = "Загрузить / обновить подписку"
            visibility = android.view.View.GONE
            setOnClickListener { loadSubscription() }
        }

        val vpnButton = Button(this).apply {
            text = "ВКЛ / ВЫКЛ"
            textSize = 17f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF258A45.toInt())
                setStroke(dp(3), 0xFF176B34.toInt())
            }
            setOnClickListener {
                pendingSelectedOutbound = null
                pendingSelectedName = null
                if (isVpnRunning()) {
                    XrayVpnService.stop(this@MainActivity)
                    setStatus("Отключение VPN…")
                } else {
                    val selected = nodes.getOrNull(selectedNodeIndex)
                    if (selected == null) {
                        setStatus("Сначала выбери сервер")
                    } else {
                        val name = selected.optString("tag")
                            .ifBlank { "Сервер ${selectedNodeIndex + 1}" }
                        connectToOutbound(selected, name)
                    }
                }
            }
        }
        root.addView(title, matchWidth())
        root.addView(vpnButton, LinearLayout.LayoutParams(dp(156), dp(156)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })
        root.addView(statusView, matchWidth())

        nodesHeader = TextView(this).apply {
            text = "Серверы пока не добавлены"
            textSize = 16f
            setPadding(dp(12), dp(12), dp(4), dp(8))
        }
        val nodesMenuButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            contentDescription = "Изменить список серверов"
            val selectableBackground = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)
            background = getDrawable(selectableBackground.resourceId)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { anchor ->
                android.widget.PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Удалить подписку").setOnMenuItemClickListener {
                        confirmDeleteSubscription()
                        true
                    }
                }.show()
            }
        }
        val nodesHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nodesHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nodesMenuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        nodesContainer = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFFD6D6D6.toInt())
            dividerHeight = dp(1)
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ ->
                val node = nodes.getOrNull(position) ?: return@setOnItemClickListener
                selectedNodeIndex = position
                preferences.edit().putInt(KEY_SELECTED_NODE_INDEX, selectedNodeIndex).apply()
                renderNodes()
                val name = node.optString("tag").ifBlank { "Сервер ${position + 1}" }
                if (isVpnRunning()) {
                    connectToOutbound(node, name)
                } else {
                    setStatus("Выбран сервер: $name. VPN выключен — для подключения нажми «Вкл/Выкл».")
                }
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFFD9DDE1.toInt())
            }
            clipToOutline = true
            addView(nodesHeaderRow, matchWidth())
            addView(nodesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
            ))
        }
        root.addView(serverCard, matchWidth())

        val spacer = android.view.View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val addButton = Button(this).apply {
            text = "+"
            textSize = 30f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, dp(3))
            contentDescription = "Добавить подписку"
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF258A45.toInt())
            }
            setOnClickListener { anchor ->
                android.widget.PopupMenu(this@MainActivity, anchor).apply {
                    menu.add("Ввести ссылку вручную").setOnMenuItemClickListener {
                        showManualSubscriptionDialog()
                        true
                    }
                    menu.add("Вставить ссылку из буфера").setOnMenuItemClickListener {
                        pasteFromClipboard()
                        val value = subscriptionInput.text.toString().trim()
                        if (value.startsWith("https://", ignoreCase = true)) loadSubscription()
                        true
                    }
                    menu.add("Сканировать QR-код").setOnMenuItemClickListener {
                        scanSubscriptionQr()
                        true
                    }
                }.show()
            }
        }
        bottomRow.addView(addButton, LinearLayout.LayoutParams(dp(60), dp(60)))
        root.addView(bottomRow, matchWidth())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun showManualSubscriptionDialog() {
        val input = EditText(this).apply {
            hint = "https://адрес-подписки"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(subscriptionInput.text)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Добавить подписку")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Загрузить") { _, _ ->
                subscriptionInput.setText(input.text.toString().trim())
                loadSubscription()
            }
            .show()
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
                val imported = parseSubscriptionEntries(subscriptionText)
                mainHandler.post {
                    nodes.clear()
                    nodes.addAll(imported)
                    preferences.edit()
                        .putString(KEY_SUBSCRIPTION_CONTENT, subscriptionText)
                        .putString(KEY_SAVED_NODES, JSONArray(imported).toString())
                        .apply()
                    renderNodes()
                    loadSubscriptionButton.isEnabled = true
                    val rejectedIndexes = findRejectedCandidateIndexes(subscriptionText)
                    val acceptedForUse = nodes.count { !it.optBoolean("jpnNeedsValidation", false) }
                    val rejected = (sourceCount - acceptedForUse).coerceAtLeast(0)
                    val rejectedDetails = if (rejectedIndexes.isNotEmpty()) {
                        "; требуют проверки: ${rejectedIndexes.joinToString("; ")}"
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
                        "Подписка обработана: найдено $sourceCount, добавлено защищённых TLS/REALITY серверов ${nodes.size}, пропущено $rejected$rejectedDetails$parameterSummary"
                    } else {
                        "В подписке не найдено поддерживаемых серверов с защитой TLS/REALITY"
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
            val validation = runCatching {
                JSONObject(
                    LibXray.invoke(
                        JSONObject()
                            .put("apiVersion", 3)
                            .put("method", "convertShareLinksToXrayJson")
                            .put("payload", JSONObject().put("text", candidate.first))
                            .toString()
                    )
                )
            }.getOrNull()

            if (validation?.optBoolean("success", false) == true) {
                null
            } else {
                val rawError = validation?.optString("error").orEmpty().lowercase()
                val safeReason = when {
                    "invalid vless identifier" in rawError -> "неверный идентификатор VLESS"
                    "unsupported vless encryption value" in rawError -> "Xray не поддерживает указанное значение encryption VLESS"
                    "missing required vless encryption=none setting" in rawError -> "в конфигурации VLESS отсутствует обязательное encryption=none"
                    "vless encryption field used in an invalid configuration section" in rawError -> "параметр encryption VLESS находится не в том разделе конфигурации"
                    "unsupported vless flow" in rawError -> "неподдерживаемый режим VLESS"
                    "invalid server address or port" in rawError -> "неверный адрес сервера или порт"
                    "unsupported or invalid transport settings" in rawError -> "неподдерживаемые или некорректные параметры транспорта"
                    "invalid tls/reality settings" in rawError -> "некорректные параметры TLS/Reality"
                    "xray rejected the outbound configuration" in rawError -> "Xray отклонил конфигурацию"
                    else -> "ошибка разбора или проверки конфигурации Xray"
                }
                "№${index + 1} (${candidate.second}): $safeReason"
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
            "№${index + 1} security=$security, transport=$network, flow=${yesNo(flowPresent)}, " +
                "SNI=${yesNo(sniPresent)}, pbk=${yesNo(publicKeyPresent)}, sid=${yesNo(shortIdPresent)}, " +
                "fp=${yesNo(fingerprintPresent)}, path=${yesNo(pathPresent)}, host=${yesNo(hostPresent)}, " +
                "serviceName=${yesNo(serviceNamePresent)}"
        }
    }

    /**
     * Imports every subscription entry independently. A failed entry is retained
     * as a private raw link and can be retried when the user taps it.
     */
    private fun parseSubscriptionEntries(text: String): MutableList<JSONObject> {
        val source = text.removePrefix("\uFEFF").trim()
        val imported = mutableListOf<JSONObject>()

        if (source.startsWith("{")) {
            val outbounds = runCatching {
                JSONObject(source).optJSONArray("outbounds")
            }.getOrNull()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    val copy = JSONObject(outbound.toString())
                    if (!hasTlsOrRealityProtection(copy)) continue
                    if (copy.optString("tag").isBlank()) copy.put("tag", "Сервер ${i + 1}")
                    imported.add(copy)
                }
                return imported
            }
        }

        val links = source.lineSequence().map { it.trim() }
            .filter { line ->
                val scheme = line.substringBefore("://", "")
                line.contains("://") &&
                    scheme.matches(Regex("(?i)vless|vmess|trojan|ss|socks|hysteria2|hy2"))
            }.toList()

        links.forEachIndexed { index, link ->
            val fallbackName = "Сервер ${index + 1}"
            val displayName = runCatching {
                val fragment = android.net.Uri.parse(link).fragment
                fragment?.let { android.net.Uri.decode(it) }?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallbackName
            val parsed = runCatching { parseShareLinks(link) }.getOrNull()
            val outbound = parsed?.optJSONObject(0)
            if (outbound != null && hasTlsOrRealityProtection(outbound)) {
                val copy = JSONObject(outbound.toString())
                if (copy.optString("tag").isBlank()) copy.put("tag", displayName)
                imported.add(copy)
            }
        }
        return imported
    }

    /**
     * JPN only lists servers protected by TLS or REALITY.
     * Other security modes (including none) are intentionally excluded.
     */
    private fun hasTlsOrRealityProtection(outbound: JSONObject): Boolean {
        val streamSettings = outbound.optJSONObject("streamSettings") ?: return false
        val security = streamSettings.optString("security")
        return security.equals("tls", ignoreCase = true) ||
            security.equals("reality", ignoreCase = true)
    }

    private fun confirmDeleteSubscription() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Удалить подписку?")
            .setMessage("Будут удалены адрес подписки и все сохранённые серверы. Активное VPN-подключение при этом не отключится.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                preferences.edit()
                    .remove(KEY_SUBSCRIPTION_URL)
                    .remove(KEY_SUBSCRIPTION_CONTENT)
                    .remove(KEY_SAVED_NODES)
                    .apply()
                subscriptionInput.setText("")
                nodes.clear()
                renderNodes()
                setStatus("Подписка и сохранённые серверы удалены")
            }
            .show()
    }

    private fun restoreSavedSubscription() {
        val saved = preferences.getString(KEY_SAVED_NODES, null) ?: return
        runCatching {
            val array = JSONArray(saved)
            nodes.clear()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { nodes.add(JSONObject(it.toString())) }
            }
            renderNodes()
        }
    }

    private fun safeImportError(message: String?): String {
        val error = message.orEmpty().lowercase()
        return when {
            "invalid server address or port" in error -> "неверный адрес сервера или порт"
            "invalid vless identifier" in error -> "неверный идентификатор VLESS"
            "unsupported vless encryption value" in error -> "Xray не поддерживает указанное значение encryption VLESS"
            "missing required vless encryption=none setting" in error -> "в конфигурации VLESS отсутствует обязательное encryption=none"
            "vless encryption field used in an invalid configuration section" in error -> "параметр encryption VLESS находится не в том разделе конфигурации"
            "unsupported vless flow" in error -> "неподдерживаемый режим VLESS"
            "unsupported or invalid transport" in error -> "неподдерживаемые параметры транспорта"
            "invalid tls" in error || "reality" in error -> "некорректные параметры TLS/Reality"
            else -> "ошибка разбора конфигурации"
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
        // ListView is an AdapterView: its rows are owned by the adapter.
        // Calling removeAllViews() here throws UnsupportedOperationException
        // and crashes the activity after subscription/QR import.
        nodesHeader.text = if (nodes.isEmpty()) {
            "Серверы не найдены"
        } else {
            "Доступные серверы: ${nodes.size}. Нажми на сервер для подключения:"
        }

        selectedNodeIndex = selectedNodeIndex.takeIf { it in nodes.indices } ?: -1
        val labels = nodes.mapIndexed { index, node ->
            val name = node.optString("tag").ifBlank { "Сервер ${index + 1}" }
            if (node.optBoolean("jpnNeedsValidation", false)) "$name · не проверен" else name
        }
        nodesContainer.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            labels
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val row = super.getView(position, convertView, parent) as TextView
                row.paintFlags = row.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
                row.setBackgroundColor(
                    if (position == selectedNodeIndex) 0xFFD8F5D0.toInt()
                    else android.graphics.Color.TRANSPARENT
                )
                return row
            }
        }
        nodesContainer.visibility = if (nodes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun connectToOutbound(outbound: JSONObject, name: String) {
        val rawShareLink = outbound.optString("jpnRawShareLink")
        if (rawShareLink.isNotBlank()) {
            hideKeyboard()
            setStatus("Проверяю сервер: $name…")
            Thread {
                try {
                    val parsed = parseShareLinks(rawShareLink)
                    if (parsed.length() == 0) throw IllegalArgumentException("Xray не вернул конфигурацию сервера")
                    val resolved = JSONObject(parsed.getJSONObject(0).toString())
                    mainHandler.post { connectToOutbound(resolved, name) }
                } catch (error: Exception) {
                    mainHandler.post {
                        setStatus("Сервер сохранён, но пока не поддерживается: ${safeImportError(error.message)}")
                    }
                }
            }.start()
            return
        }
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

    private fun scanSubscriptionQr() {
        setStatus("Открываю сканер QR-кода…")
        GmsBarcodeScanning.getClient(this)
            .startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue?.trim()
                if (value.isNullOrBlank()) {
                    setStatus("В QR-коде нет текста")
                } else {
                    handleScannedQr(value)
                }
            }
            .addOnCanceledListener {
                setStatus("Сканирование QR-кода отменено")
            }
            .addOnFailureListener {
                setStatus("Не удалось открыть сканер QR-кодов")
            }
    }

    private fun handleScannedQr(value: String) {
        // Always show the exact text recognized by the QR scanner in the
        // subscription field before starting the corresponding import flow.
        subscriptionInput.setText(value)
        subscriptionInput.setSelection(subscriptionInput.text.length)

        if (value.startsWith("https://", ignoreCase = true)) {
            loadSubscription()
            return
        }

        // Raw share links / subscription payloads are parsed in the background
        // immediately, just like pressing the subscription load action.
        setStatus("Загружаю данные из QR-кода…")
        Thread {
            val imported = runCatching {
                val decoded = decodeSubscriptionBody(value)
                parseSubscriptionEntries(decoded)
            }.getOrElse {
                mainHandler.post {
                    setStatus("QR распознан, но ссылка не поддерживается")
                }
                return@Thread
            }

            mainHandler.post {
                if (imported.isEmpty()) {
                    setStatus("В QR-коде не найдено серверов с защитой TLS или REALITY")
                    return@post
                }

                nodes.addAll(imported)
                preferences.edit()
                    .putString(KEY_SAVED_NODES, JSONArray(nodes).toString())
                    .apply()
                renderNodes()
                setStatus("Из QR-кода добавлено серверов: ${imported.size}. Всего в списке: ${nodes.size}")
            }
        }.start()
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
