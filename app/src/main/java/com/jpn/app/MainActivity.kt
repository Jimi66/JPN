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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
private class PowerSymbolDrawable(private val color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - size) / 2f
        val top = b.top + (b.height() - size) / 2f
        val stroke = size * 0.115f
        paint.strokeWidth = stroke
        val inset = stroke * 1.05f
        val arc = android.graphics.RectF(
            left + inset,
            top + size * 0.22f,
            left + size - inset,
            top + size - inset
        )
        canvas.drawArc(arc, 135f, 270f, false, paint)
        canvas.drawLine(
            left + size / 2f, top + size * 0.10f,
            left + size / 2f, top + size * 0.48f,
            paint
        )
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

class MainActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"

        private const val KEY_SUBSCRIPTION_URL = "subscription_url"
        private const val KEY_SUBSCRIPTION_NAME = "subscription_name"
        private const val KEY_SUBSCRIPTION_ANNOUNCE = "subscription_announce"
        private const val KEY_SUBSCRIPTION_TRAFFIC = "subscription_traffic"
        private const val KEY_SUBSCRIPTION_CONTENT = "subscription_content"
        private const val KEY_SAVED_NODES = "saved_nodes_json"
        private const val KEY_VPN_STATE = "vpn_state"
        private const val KEY_SELECTED_NODE_INDEX = "selected_node_index"
        private const val VPN_REQUEST_CODE = 4102
        const val ACTION_QUICK_SETTINGS_TOGGLE = "com.jpn.app.action.QUICK_SETTINGS_TOGGLE"
        private const val MAX_SUBSCRIPTION_BYTES = 16 * 1024 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var subscriptionInput: EditText

    private lateinit var loadSubscriptionButton: Button
    private lateinit var nodesContainer: ListView
    private lateinit var nodesHeader: TextView
    private lateinit var subscriptionAnnouncementView: TextView
    private lateinit var subscriptionTrafficView: TextView
    private lateinit var vpnButton: ImageButton
    private lateinit var addSubscriptionButton: Button
    private var pendingXrayConfig: String? = null
    private var pendingSelectedOutbound: JSONObject? = null
    private var pendingSelectedName: String? = null
    private val nodes = mutableListOf<JSONObject>()
    private var selectedNodeIndex = -1
    private var lastHapticVpnState: String? = null
    private var pingCheckRunning = false
    private var pingWaitingForVpnStop = false
    private var pingRestoreVpn = false
    private var pingSuppressHaptics = false
    private var pingButtonForCheck: ImageButton? = null
    @Volatile private var lastTestVpnError: String? = null
    @Volatile private var testVpnStopCompleted = false
    @Volatile private var downloadedSubscriptionName: String? = null
    @Volatile private var downloadedSubscriptionAnnouncement: String? = null
    @Volatile private var downloadedSubscriptionTraffic: String? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return
            if (intent.getBooleanExtra(XrayVpnService.EXTRA_TEST_MODE, false)) {
                if (state == "error") {
                    lastTestVpnError = intent.getStringExtra("message") ?: "Неизвестная ошибка запуска Xray"
                    testVpnStopCompleted = true
                } else if (state == "disconnected") {
                    // This acknowledgement is sent after the service closes the TUN descriptor.
                    testVpnStopCompleted = true
                }
                return
            }
            val message = intent.getStringExtra("message") ?: state
            showState(state, message)
            if (pingRestoreVpn && (state == "connected" || state == "error")) {
                pingRestoreVpn = false
                pingSuppressHaptics = false
            }
            if (state == "disconnected" && pingWaitingForVpnStop) {
                pingWaitingForVpnStop = false
                pingCheckRunning = false
                mainHandler.postDelayed({ checkServersLatency(pingButtonForCheck) }, 250)
            }
            if (state == "disconnected" && pendingSelectedOutbound != null) {
                val outbound = pendingSelectedOutbound
                val name = pendingSelectedName ?: "Выбранный сервер"
                pendingSelectedOutbound = null
                pendingSelectedName = null
                if (outbound != null) mainHandler.postDelayed({ connectToOutbound(outbound, name) }, 300)
            }
        }
    }

    private var appliedDarkTheme: Boolean = false
    private var launchedFromQuickSettings: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedFromQuickSettings = intent?.action == ACTION_QUICK_SETTINGS_TOGGLE
        if (launchedFromQuickSettings) setTheme(R.style.JpnVpnConsentTheme)
        super.onCreate(savedInstanceState)
        if (!launchedFromQuickSettings) JpnTheme.applyBeforeCreate(this)
        appliedDarkTheme = JpnTheme.isDark(this)
        lastHapticVpnState = preferences.getString(KEY_VPN_STATE, "disconnected")
        buildScreen()
        continueMainInitialization()

    }


    private fun continueMainInitialization() {
        restoreSavedSubscription()
        AutoSubscriptionScheduler.update(this)
        refreshVpnState()
        handleQuickSettingsIntent(intent)
        if (nodes.isEmpty() && !launchedFromQuickSettings) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Серверы не добавлены")
                .setMessage("Нажмите «+», чтобы добавить подписку.")
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleQuickSettingsIntent(intent)
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
        val currentDarkTheme = JpnTheme.isDark(this)
        if (currentDarkTheme != appliedDarkTheme) {
            recreate()
            return
        }
    }

    private fun applyMainIconTint(button: ImageButton) {
        val color = if (JpnTheme.isDark(this)) 0xFFF1F1F5.toInt() else 0xFF202124.toInt()
        button.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun buildScreen() {
        val screen = android.widget.FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        statusView = TextView(this).apply {
            text = "Отключено"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(12))
            setTextIsSelectable(false)
            isClickable = false
            isLongClickable = false
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

        vpnButton = ImageButton(this).apply {
            contentDescription = "Включить или выключить VPN"
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(43), dp(43), dp(43), dp(43))
            setImageResource(R.drawable.jpn_power_symbol)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF706AA5.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFE1E3F7.toInt())
            }
            // Force the view's outline to follow the oval background, not the
            // rectangular ImageButton bounds supplied by the platform theme.
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            minimumWidth = 0
            minimumHeight = 0
            elevation = dp(12).toFloat()
            foreground = null
            stateListAnimator = null
            setOnClickListener {
                pendingSelectedOutbound = null
                pendingSelectedName = null
                if (isVpnRunning()) {
                    XrayVpnService.stop(this@MainActivity)
                    setStatus("Отключение VPN…")
                } else {
                    if (selectedNodeIndex !in nodes.indices && nodes.isNotEmpty()) {
                        selectedNodeIndex = 0
                        preferences.edit().putInt(KEY_SELECTED_NODE_INDEX, selectedNodeIndex).apply()
                        renderNodes()
                    }
                    val selected = nodes.getOrNull(selectedNodeIndex)
                    if (selected == null) {
                        setStatus("Сначала добавь сервер")
                    } else {
                        val name = selected.optString("tag")
                            .ifBlank { "Сервер ${selectedNodeIndex + 1}" }
                        connectToOutbound(selected, name)
                    }
                }
            }
        }
        val vpnArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, 0)
            addView(vpnButton, LinearLayout.LayoutParams(dp(156), dp(156)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            addView(statusView, matchWidth())
        }
        root.addView(vpnArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(108)
            bottomMargin = dp(12)
        })

        nodesHeader = TextView(this).apply {
            text = "Серверы пока не добавлены"
            textSize = 16f
            setPadding(0, 0, 0, 0)
        }
        subscriptionAnnouncementView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF73777D.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = android.view.View.GONE
        }
        subscriptionTrafficView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF73777D.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = android.view.View.GONE
        }
        val subscriptionHeaderTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(4), dp(10))
            addView(nodesHeader, matchWidth())
            addView(subscriptionAnnouncementView, matchWidth())
            addView(subscriptionTrafficView, matchWidth())
        }
        val nodesMenuButton = ImageButton(this).apply {
            setImageResource(R.drawable.jpn_subscription_edit)
            applyMainIconTint(this)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Изменить список серверов"
            tooltipText = "Редактировать подписку"
            val selectableBackground = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)
            background = getDrawable(selectableBackground.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { anchor ->
                val popupContent = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.WHITE)
                        cornerRadius = dp(12).toFloat()
                    }
                }
                lateinit var popupWindow: android.widget.PopupWindow
                val deleteRow = TextView(this@MainActivity).apply {
                    text = "Удалить подписку"
                    textSize = 14f
                    setTextColor(0xFF202124.toInt())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    minHeight = dp(48)
                    background = selectableItemBackground()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popupWindow.dismiss()
                        confirmDeleteSubscription()
                    }
                }
                popupContent.addView(deleteRow)
                popupWindow = android.widget.PopupWindow(
                    popupContent, dp(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
                ).apply {
                    elevation = dp(8).toFloat()
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                }
                popupWindow.showAsDropDown(anchor)
            }
        }
        val refreshSubscriptionButton = ImageButton(this).apply {
            setImageResource(R.drawable.jpn_subscription_refresh)
            applyMainIconTint(this)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Обновить подписку"
            tooltipText = "Обновить подписку"
            val selectableBackground = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectableBackground, true)
            background = getDrawable(selectableBackground.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                val savedUrl = preferences.getString(KEY_SUBSCRIPTION_URL, null)
                if (savedUrl.isNullOrBlank()) {
                    setStatus("Нет сохранённой ссылки на подписку")
                } else {
                    subscriptionInput.setText(savedUrl)
                    loadSubscription()
                }
            }
        }
        val pingButton = ImageButton(this).apply {
            setImageResource(R.drawable.jpn_ping_speedometer)
            applyMainIconTint(this)
            contentDescription = "Проверка серверов"
            tooltipText = "Проверка серверов"
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val selectableBackground = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectableBackground, true)
            background = getDrawable(selectableBackground.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isEnabled = true
            isClickable = true
            alpha = 1f
            setOnClickListener {
                checkServersLatency(this)
            }
        }
        val nodesHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(subscriptionHeaderTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pingButton, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(refreshSubscriptionButton, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(nodesMenuButton, LinearLayout.LayoutParams(dp(36), dp(36)))
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
                setColor(JpnTheme.surface(this@MainActivity))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), JpnTheme.border(this@MainActivity))
            }
            clipToOutline = true
            addView(nodesHeaderRow, matchWidth())
            addView(nodesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ))
        }
        serverCard.visibility = if (nodes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        root.addView(serverCard, matchWidth())

        addSubscriptionButton = Button(this).apply {
            text = "+"
            textSize = 30f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, dp(3))
            contentDescription = "Добавить подписку"
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFE1E3F7.toInt())
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
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.jpn_settings_gear)
            applyMainIconTint(this)
            contentDescription = "Настройки"
            val selectableBackground = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectableBackground, true)
            background = getDrawable(selectableBackground.resourceId)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsMenuActivity::class.java))
            }
        }
        screen.addView(root, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val addButtonParams = android.widget.FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(20)
            bottomMargin = dp(16)
        }
        screen.addView(addSubscriptionButton, addButtonParams)
        val settingsButtonParams = android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(8)
            marginStart = dp(12)
        }
        screen.addView(settingsButton, settingsButtonParams)
        screen.setOnApplyWindowInsetsListener { _, insets ->
            addButtonParams.bottomMargin = dp(16) + insets.systemWindowInsetBottom
            addSubscriptionButton.layoutParams = addButtonParams
            settingsButtonParams.topMargin = dp(8) + insets.systemWindowInsetTop
            settingsButton.layoutParams = settingsButtonParams
            insets
        }
        setContentView(screen)
        if (!launchedFromQuickSettings) JpnTheme.apply(this, screen)
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
                val bodyMetadata = extractAnnouncementFromBody(body)
                val subscriptionAnnouncement = downloadedSubscriptionAnnouncement ?: bodyMetadata.second
                val subscriptionName = downloadedSubscriptionName
                    ?: runCatching { URL(subscriptionUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
                val subscriptionText = decodeSubscriptionBody(bodyMetadata.first)
                val imported = parseSubscriptionEntries(subscriptionText)
                mainHandler.post {
                    nodes.clear()
                    nodes.addAll(imported)
                    preferences.edit()
                        .putString(KEY_SUBSCRIPTION_CONTENT, subscriptionText)
                        .putString(KEY_SUBSCRIPTION_NAME, subscriptionName)
                        .putString(KEY_SUBSCRIPTION_ANNOUNCE, subscriptionAnnouncement)
                        .putString(KEY_SUBSCRIPTION_TRAFFIC, downloadedSubscriptionTraffic)
                        .putString(KEY_SAVED_NODES, JSONArray(imported).toString())
                        .apply()
                    AutoSubscriptionScheduler.update(this@MainActivity)
                    renderNodes()
                    loadSubscriptionButton.isEnabled = true
                    refreshVpnState()
                    checkServersLatency()
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
            connection.setRequestProperty("User-Agent", "JPN/${BuildConfig.VERSION_NAME}")
            SubscriptionRequestHeaders.apply(this@MainActivity, connection)
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
            val profileTitleHeader = connection.getHeaderField("profile-title")
            val profileTitle = profileTitleHeader
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            downloadedSubscriptionName = profileTitle?.let { decodeProfileTitle(it) }
            downloadedSubscriptionAnnouncement = connection.getHeaderField("announce")
                ?.let { decodeAnnouncement(it) }
            downloadedSubscriptionTraffic = connection.getHeaderField("subscription-userinfo")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeProfileTitle(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        fun usable(candidate: String?): String? {
            val text = candidate?.trim() ?: return null
            if (text.isBlank() || text.any { it.code == 65533 }) return null
            if (text.any { it.isISOControl() && !it.isWhitespace() }) return null
            return text.takeIf { it.any { char -> char.isLetterOrDigit() } }
        }

        // profile-title may be explicitly wrapped as "base64:<payload>".
        val encodedValue = value.removePrefix("base64:").removePrefix("BASE64:")
        val compact = encodedValue.filterNot { it.isWhitespace() }
        val decoded = listOf(
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        ).firstNotNullOfOrNull { flags ->
            runCatching {
                usable(String(Base64.decode(compact, flags), StandardCharsets.UTF_8))
            }.getOrNull()
        }
        if (decoded != null) return decoded

        // Some servers expose a UTF-8 header through a Latin-1 decoder.
        val repaired = runCatching {
            val bytes = ByteArray(value.length) { index -> value[index].code.toByte() }
            usable(String(bytes, StandardCharsets.UTF_8))
        }.getOrNull()
        return repaired ?: usable(value)
    }

    private fun formatSubscriptionTraffic(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // 3x-ui sends: upload=N; download=N; total=N; expire=N.
        // total=0 is a valid unlimited plan, not missing traffic data.
        val fields = raw.split(Regex("\\s*[,;]\\s*")).mapNotNull { item ->
            val pair = item.split("=", limit = 2)
            if (pair.size != 2) null else pair[0].trim().lowercase() to pair[1].trim().toLongOrNull()
        }.toMap()
        val uploaded = fields["upload"]?.takeIf { it >= 0L } ?: return null
        val downloaded = fields["download"]?.takeIf { it >= 0L } ?: return null
        val total = fields["total"]?.takeIf { it >= 0L } ?: return null
        val used = (uploaded.toDouble() + downloaded.toDouble()).toLong()
        if (total == 0L) {
            return "Использовано: ${formatTrafficBytes(used)} · безлимит"
        }
        val percent = (used.toDouble() * 100.0 / total.toDouble()).toInt()
        return "Использовано: ${formatTrafficBytes(used)} из ${formatTrafficBytes(total)} · $percent%"
    }

    private fun formatTrafficBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        val digits = if (unit == 0 || value >= 100.0) 0 else if (value >= 10.0) 1 else 2
        return "%.${digits}f %s".format(java.util.Locale.getDefault(), value, units[unit])
    }

    private fun decodeAnnouncement(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val decoded = if (value.startsWith("base64:", ignoreCase = true)) {
            val payload = value.substringAfter(":").filterNot { it.isWhitespace() }
            runCatching {
                String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            }.getOrNull()
        } else value
        return decoded?.takeIf { it.isNotBlank() && it.none { ch -> ch == '\u0000' } }?.take(500)
    }

    private fun extractAnnouncementFromBody(body: String): Pair<String, String?> {
        var announcement: String? = null
        val cleaned = body.lineSequence().filter { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#announce:", ignoreCase = true)) {
                announcement = decodeAnnouncement(trimmed.substringAfter(":"))
                false
            } else true
        }.joinToString("\n")
        return cleaned to announcement
    }

    private fun decodeSubscriptionBody(body: String): String =
        SubscriptionParser.decodeBody(body)


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
    private fun parseSubscriptionEntries(text: String): MutableList<JSONObject> =
        SubscriptionParser.parseEntries(text)

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
                    .remove(KEY_SUBSCRIPTION_ANNOUNCE)
                    .remove(KEY_SUBSCRIPTION_TRAFFIC)
                    .remove(KEY_SAVED_NODES)
                    .apply()
                AutoSubscriptionScheduler.update(this@MainActivity)
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

    /**
     * Measures the selected Xray outbound directly through libXray.
     * This follows Happ's proxy-delay check and does not create an Android VPN/TUN interface.
     */
    private fun checkServersLatency(button: ImageButton? = null) {
        if (pingCheckRunning) return
        if (nodes.isEmpty()) {
            setStatus("Нет серверов для проверки")
            return
        }

        pingCheckRunning = true
        pingButtonForCheck = button
        button?.isEnabled = false
        button?.alpha = 0.45f
        vpnButton.isEnabled = false
        nodesContainer.isEnabled = false

        // libXray pingBatch refuses to run while the regular VPN core is alive.
        // Stop the tunnel, perform checks in isolation, then restore the same server.
        if (isVpnRunning()) {
            pingRestoreVpn = true
            pingSuppressHaptics = true
            pingWaitingForVpnStop = true
            setStatus("Временно отключаю VPN для проверки серверов…")
            XrayVpnService.stop(this)
            return
        }

        val snapshot = nodes.map { JSONObject(it.toString()) }
        setStatus("Подготавливаю проверку серверов…")

        Thread {
            try {
                snapshot.forEachIndexed { index, savedNode ->
                    mainHandler.post {
                        setStatus("Проверяю сервер ${index + 1} из ${snapshot.size}…")
                    }

                    var result: Int? = null
                    var failure: String? = null
                    try {
                        val outbound = resolveOutboundForCheck(savedNode)
                        val config = buildXrayConfig(outbound)
                        val request = JSONObject()
                            .put("apiVersion", 3)
                            .put("method", "pingBatch")
                            .put(
                                "payload",
                                JSONObject()
                                    .put(
                                        "configs",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("xrayJson", config)
                                                .put("outboundTag", outbound.optString("tag"))
                                        )
                                    )
                                    .put("timeout", 7)
                                    .put("url", "https://www.gstatic.com/generate_204")
                            )
                        val response = JSONObject(LibXray.invoke(request.toString()))
                        if (!response.optBoolean("success", false)) {
                            throw IllegalStateException(
                                response.optString("error").ifBlank { "Ошибка проверки Xray" }
                            )
                        }
                        val pingResult = response.optJSONObject("data")
                            ?.optJSONArray("results")
                            ?.optJSONObject(0)
                            ?: throw IllegalStateException("Xray не вернул результат проверки")
                        if (pingResult.optBoolean("success", false)) {
                            result = pingResult.optLong("delay").coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        } else {
                            throw IllegalStateException(
                                pingResult.optString("error").ifBlank { "Сервер не ответил" }
                            )
                        }
                    } catch (error: Exception) {
                        failure = generateSequence(error as Throwable?) { it.cause }
                            .take(5)
                            .joinToString(" → ") { cause ->
                                "${cause.javaClass.simpleName}: ${cause.message ?: "без текста"}"
                            }
                    }

                    mainHandler.post {
                        if (index < nodes.size) {
                            nodes[index].remove("jpnPingMs")
                            nodes[index].remove("jpnPingFailed")
                            nodes[index].remove("jpnPingError")
                            if (result != null) {
                                nodes[index].put("jpnPingMs", result)
                            } else {
                                nodes[index].put("jpnPingFailed", true)
                                nodes[index].put("jpnPingError", failure ?: "Причина не определена")
                            }
                            renderNodes()
                        }
                    }
                }
            } finally {
                mainHandler.post {
                    pingCheckRunning = false
                    button?.isEnabled = true
                    button?.alpha = 1f
                    vpnButton.isEnabled = true
                    nodesContainer.isEnabled = true
                    val available = nodes.count { it.optInt("jpnPingMs", -1) >= 0 }
                    if (pingRestoreVpn) {
                        val restoreIndex = selectedNodeIndex.takeIf { it in nodes.indices }
                            ?: preferences.getInt(KEY_SELECTED_NODE_INDEX, -1)
                        val selected = nodes.getOrNull(restoreIndex)
                        if (selected != null) {
                            val name = selected.optString("tag").ifBlank { "Сервер ${restoreIndex + 1}" }
                            setStatus("Проверка завершена: доступно $available из ${nodes.size}. Восстанавливаю VPN…")
                            connectToOutbound(selected, name)
                        } else {
                            pingRestoreVpn = false
                            pingSuppressHaptics = false
                            setStatus("Проверка завершена: доступно $available из ${nodes.size}. Не удалось восстановить VPN: сервер не выбран")
                        }
                    } else {
                        setStatus("Проверка завершена: доступно $available из ${nodes.size}")
                    }
                }
            }
        }.start()
    }

    private fun resolveOutboundForCheck(node: JSONObject): JSONObject {
        val rawShareLink = node.optString("jpnRawShareLink")
        if (rawShareLink.isBlank()) return JSONObject(node.toString())
        val parsed = parseShareLinks(rawShareLink)
        if (parsed.length() == 0) throw IllegalArgumentException("Не удалось разобрать конфигурацию сервера")
        return JSONObject(parsed.getJSONObject(0).toString())
    }

    private fun describeConnectionParameters(outbound: JSONObject): String {
        val values = mutableListOf<String>()
        fun addIfPresent(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isNotEmpty() && !normalized.equals("none", ignoreCase = true)) {
                values.add(normalized.uppercase(java.util.Locale.ROOT))
            }
        }

        addIfPresent(outbound.optString("protocol"))
        val streamSettings = outbound.optJSONObject("streamSettings")
        addIfPresent(streamSettings?.optString("network"))
        addIfPresent(streamSettings?.optString("security"))
        return values.joinToString(" / ")
    }

    private fun renderNodes() {
        // ListView is an AdapterView: its rows are owned by the adapter.
        // Calling removeAllViews() here throws UnsupportedOperationException
        // and crashes the activity after subscription/QR import.
        nodesHeader.text = if (nodes.isEmpty()) {
            "Серверы не найдены"
        } else {
            val savedName = preferences.getString(KEY_SUBSCRIPTION_NAME, null)
            decodeProfileTitle(savedName.orEmpty())
                ?: runCatching {
                    URL(preferences.getString(KEY_SUBSCRIPTION_URL, null)).host
                }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Подписка"
        }

        val announcement = preferences.getString(KEY_SUBSCRIPTION_ANNOUNCE, null).orEmpty().trim()
        subscriptionAnnouncementView.text = announcement
        subscriptionAnnouncementView.visibility = if (nodes.isNotEmpty() && announcement.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE

        val trafficText = formatSubscriptionTraffic(preferences.getString(KEY_SUBSCRIPTION_TRAFFIC, null))
        subscriptionTrafficView.text = trafficText.orEmpty()
        subscriptionTrafficView.visibility = if (nodes.isNotEmpty() && trafficText != null) android.view.View.VISIBLE else android.view.View.GONE

        selectedNodeIndex = selectedNodeIndex.takeIf { it in nodes.indices } ?: -1
        nodesContainer.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = nodes.size
            override fun getItem(position: Int): JSONObject = nodes[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(16), dp(9))
                    minimumHeight = dp(44)

                    val serverTexts = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            tag = "server_name"
                            textSize = 16f
                            setTextColor(0xFF202124.toInt())
                            gravity = Gravity.CENTER_VERTICAL
                        }, matchWidth())
                        addView(TextView(this@MainActivity).apply {
                            tag = "server_parameters"
                            textSize = 11f
                            setTextColor(0xFF73777D.toInt())
                            gravity = Gravity.CENTER_VERTICAL
                            visibility = android.view.View.GONE
                        }, matchWidth())
                    }
                    addView(serverTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    addView(TextView(this@MainActivity).apply {
                        tag = "server_latency"
                        textSize = 14f
                        setTextColor(0xFF60646A.toInt())
                        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                        setPadding(dp(12), 0, 0, 0)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }

                val node = nodes[position]
                val name = node.optString("tag").ifBlank { "Сервер ${position + 1}" }
                val latency = node.optInt("jpnPingMs", -1)
                val latencyText = when {
                    latency >= 0 -> "$latency мс"
                    node.optBoolean("jpnPingFailed", false) -> "∞"
                    node.optBoolean("jpnNeedsValidation", false) -> "не проверен"
                    else -> ""
                }

                row.findViewWithTag<TextView>("server_name").text = name
                val connectionParameters = describeConnectionParameters(node)
                row.findViewWithTag<TextView>("server_parameters").apply {
                    text = connectionParameters
                    visibility = if (connectionParameters.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                }
                row.findViewWithTag<TextView>("server_latency").apply {
                    text = latencyText
                    visibility = if (latencyText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                }

                if (position == selectedNodeIndex) {
                    row.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    row.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (JpnTheme.isDark(this@MainActivity)) 0xFF484B56.toInt() else 0xFFE8E9F2.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                    row.findViewWithTag<TextView>("server_name").setTextColor(JpnTheme.textPrimary(this@MainActivity))
                    row.findViewWithTag<TextView>("server_latency").setTextColor(JpnTheme.textSecondary(this@MainActivity))
                } else {
                    row.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    row.findViewWithTag<TextView>("server_name").setTextColor(JpnTheme.textPrimary(this@MainActivity))
                    row.findViewWithTag<TextView>("server_latency").setTextColor(JpnTheme.textSecondary(this@MainActivity))
                }
                return row
            }
        }
        nodesContainer.visibility = if (nodes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (::addSubscriptionButton.isInitialized) {
            addSubscriptionButton.visibility = if (nodes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        (nodesContainer.parent as? android.view.View)?.visibility =
            if (nodes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        // Size the list to its actual rows (up to 280dp); longer lists scroll
        // inside the card instead of leaving a large empty area underneath.
        nodesContainer.post {
            val adapter = nodesContainer.adapter ?: return@post
            val availableWidth = nodesContainer.width
            if (availableWidth <= 0 || adapter.count == 0) return@post
            var contentHeight = 0
            for (index in 0 until adapter.count) {
                val row = adapter.getView(index, null, nodesContainer)
                row.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        availableWidth, android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        0, android.view.View.MeasureSpec.UNSPECIFIED
                    )
                )
                contentHeight += row.measuredHeight
            }
            contentHeight += nodesContainer.dividerHeight * (adapter.count - 1).coerceAtLeast(0)
            val params = nodesContainer.layoutParams
            params.height = contentHeight.coerceAtMost(dp(280))
            nodesContainer.layoutParams = params
        }
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
        getSharedPreferences("jpn_settings", MODE_PRIVATE)
            .edit()
            .putString("vpn_server_name", name)
            .apply()

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
        // All ordinary traffic uses the selected server. DNS packets from the
        // Android TUN are handed to Xray's DNS outbound and resolved over DoH.
        val dnsOutbound = JSONObject()
            .put("tag", "jpn-dns-out")
            .put("protocol", "dns")
            .put("settings", JSONObject())

        // IP-literal DoH endpoints avoid a bootstrap DNS dependency.
        val dns = JSONObject()
            .put("servers", JSONArray()
                .put("https://1.1.1.1/dns-query")
                .put("https://8.8.8.8/dns-query"))
            .put("queryStrategy", "UseIP")

        // The selected proxy is the first/default outbound. There are no
        // freedom/direct routes or GeoSite/GeoIP/manual bypass rules.
        val outbounds = JSONArray()
            .put(JSONObject(selectedOutbound.toString()))
            .put(dnsOutbound)
        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", dns)
            .put("outbounds", outbounds)

        val routingRules = JSONArray().put(JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray().put("jpn-tun-in"))
            .put("network", "tcp,udp")
            .put("port", "53")
            .put("outboundTag", "jpn-dns-out"))

        config.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", routingRules))
        return config.toString()
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
        if (launchedFromQuickSettings) finish()
    }

    private fun handleQuickSettingsIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_SETTINGS_TOGGLE) return
        intent.action = null

        if (isVpnRunning()) {
            setStatus("Отключение VPN…")
            XrayVpnService.stop(this)
            return
        }

        if (selectedNodeIndex !in nodes.indices) {
            selectedNodeIndex = preferences.getInt(KEY_SELECTED_NODE_INDEX, -1)
        }
        if (selectedNodeIndex !in nodes.indices && nodes.isNotEmpty()) {
            selectedNodeIndex = 0
            preferences.edit().putInt(KEY_SELECTED_NODE_INDEX, selectedNodeIndex).apply()
            renderNodes()
        }

        val selected = nodes.getOrNull(selectedNodeIndex)
        if (selected == null) {
            setStatus("Сначала добавь сервер в JPN")
            return
        }
        val name = selected.optString("tag").ifBlank { "Сервер ${selectedNodeIndex + 1}" }
        connectToOutbound(selected, name)
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
                checkServersLatency()
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
        updateVpnButtonAppearance(running)
        if (running) {
            setStatus("VPN подключён")
        } else {
            val state = preferences.getString(KEY_VPN_STATE, "disconnected")
            val message = preferences.getString("vpn_message", "Отключено")
            setStatus(if (state == "error") message ?: "Ошибка VPN" else "Отключено")
        }
    }

    private fun showState(state: String, message: String) {
        updateVpnButtonAppearance(state == "connected" || state == "connecting")
        setStatus(message)

        // Vibrate only after a real stable-state transition. Ignore the
        // temporary disconnect while switching between servers.
        if (!pingSuppressHaptics &&
            (state == "connected" || state == "disconnected") &&
            state != lastHapticVpnState && pendingSelectedOutbound == null
        ) {
            vibrateForVpnTransition()
            lastHapticVpnState = state
        }
    }

    private fun vibrateForVpnTransition() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(65)
            }
        }
    }

    private fun updateVpnButtonAppearance(running: Boolean) {
        if (!::vpnButton.isInitialized) return
        val backgroundColor = if (running) 0xFF706AA5.toInt() else if (JpnTheme.isDark(this)) 0xFF343640.toInt() else 0xFFE1E3F7.toInt()
        val symbolColor = if (running) 0xFFE1E3F7.toInt() else if (JpnTheme.isDark(this)) 0xFFAAA4E0.toInt() else 0xFF706AA5.toInt()
        vpnButton.setImageResource(R.drawable.jpn_power_symbol)
        vpnButton.imageTintList = android.content.res.ColorStateList.valueOf(symbolColor)
        vpnButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(backgroundColor)
        }
        vpnButton.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        vpnButton.clipToOutline = true
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(subscriptionInput.windowToken, HIDE_NOT_ALWAYS)
        subscriptionInput.clearFocus()
    }

    /**
     * Apply standard Android pressed/ripple feedback to every settings row.
     * Reuse this helper for all settings items added in the future.
     */
    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return if (value.resourceId != 0) getDrawable(value.resourceId) else null
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
