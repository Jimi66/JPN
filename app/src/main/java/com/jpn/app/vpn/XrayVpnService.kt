package com.jpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import libXray.DialerController
import libXray.LibXray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android TUN owner for the native Xray core.
 *
 * The caller must obtain VpnService.prepare() consent before starting this service.
 * EXTRA_XRAY_JSON must contain a valid Xray configuration with at least one outbound.
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.jpn.app.vpn.START"
        const val ACTION_STOP = "com.jpn.app.vpn.STOP"
        const val EXTRA_XRAY_JSON = "xray_json"
        const val EXTRA_TEST_MODE = "test_mode"
        const val ACTION_STATE_CHANGED = "com.jpn.app.vpn.STATE_CHANGED"
        const val EXTRA_TURN_CONNECTIONS = "turn_connections"
        const val EXTRA_TURN_CONNECTION_LIMIT = "turn_connection_limit"
        const val EXTRA_CAPTCHA_URL = "captcha_url"
        const val ACTION_CAPTCHA_OPENED = "com.jpn.app.vpn.CAPTCHA_OPENED"
        const val ACTION_CAPTCHA_COMPLETED = "com.jpn.app.vpn.CAPTCHA_COMPLETED"
        const val ACTION_CAPTCHA_PERMISSION_GRANTED = "com.jpn.app.vpn.CAPTCHA_PERMISSION_GRANTED"

        private const val CHANNEL_ID = "jpn_vpn"
        private const val NOTIFICATION_ID = 4101
        private const val CAPTCHA_CHANNEL_ID = "jpn_captcha"
        private const val CAPTCHA_NOTIFICATION_ID = 4102
        private const val KEY_CAPTCHA_NOTIFICATION_URL = "turn_captcha_notification_url"

        // A single process-wide queue serializes VPN lifecycle work across
        // service instances. A replacement service must not start its core
        // while the previous instance is still cleaning up.
        @Volatile private var sharedWorkerHandler: Handler? = null

        private fun lifecycleHandler(): Handler = synchronized(XrayVpnService::class.java) {
            sharedWorkerHandler ?: HandlerThread("JPN-Xray-Service").apply { start() }
                .let { thread -> Handler(thread.looper).also { sharedWorkerHandler = it } }
        }

        fun start(context: Context, xrayJson: String, testMode: Boolean = false) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_XRAY_JSON, xrayJson)
                putExtra(EXTRA_TEST_MODE, testMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun captchaOpened(context: Context) {
            runCatching { context.startService(Intent(context, XrayVpnService::class.java).apply { action = ACTION_CAPTCHA_OPENED }) }
        }

        fun captchaCompleted(context: Context, url: String) {
            runCatching {
                context.startService(Intent(context, XrayVpnService::class.java).apply {
                    action = ACTION_CAPTCHA_COMPLETED
                    putExtra(EXTRA_CAPTCHA_URL, url)
                })
            }
        }

        fun refreshCaptchaNotification(context: Context, url: String) {
            runCatching {
                context.startService(Intent(context, XrayVpnService::class.java).apply {
                    action = ACTION_CAPTCHA_PERMISSION_GRANTED
                    putExtra(EXTRA_CAPTCHA_URL, url)
                })
            }
        }

        fun stop(context: Context, testMode: Boolean = false) {
            context.startService(Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TEST_MODE, testMode)
            })
        }
    }

    private lateinit var workerHandler: Handler
    @Volatile private var latestStartId: Int = 0

    // All native Xray and TUN lifecycle operations are serialized on this thread.
    private var vpnInterface: android.os.ParcelFileDescriptor? = null
    private var turnSocketProtector: TurnSocketProtector? = null
    @Volatile private var turnProcess: java.lang.Process? = null
    private var coreStarted = false
    private var testMode = false

    private val socketController = object : DialerController {
        // gomobile exposes Go's int as Java/Kotlin Long.
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        workerHandler = lifecycleHandler()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CAPTCHA_OPENED -> {
                updateVpnNotification("VPN-подключение активно")
                return START_NOT_STICKY
            }
            ACTION_CAPTCHA_COMPLETED -> {
                val completedUrl = intent.getStringExtra(EXTRA_CAPTCHA_URL)
                val prefs = getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
                if (!completedUrl.isNullOrBlank() &&
                    prefs.getString(KEY_CAPTCHA_NOTIFICATION_URL, null) == completedUrl
                ) {
                    getSystemService(NotificationManager::class.java).cancel(CAPTCHA_NOTIFICATION_ID)
                    prefs.edit().remove(KEY_CAPTCHA_NOTIFICATION_URL).apply()
                    TurnDiagnostics.record(this, "Уведомление CAPTCHA удалено после передачи токена TURN-клиенту")
                }
                return START_NOT_STICKY
            }
            ACTION_CAPTCHA_PERMISSION_GRANTED -> {
                intent.getStringExtra(EXTRA_CAPTCHA_URL)?.let { showCaptchaNotification(it) }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                val requestedTestMode = intent.getBooleanExtra(EXTRA_TEST_MODE, false)
                workerHandler.post {
                    testMode = requestedTestMode || testMode
                    stopCoreAndTunnel()
                    publishState(
                        "disconnected",
                        if (testMode) "Тестовое подключение отключено" else "VPN отключён",
                        testMode
                    )
                    testMode = false
                    if (startId == latestStartId) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_XRAY_JSON)
                val requestedTestMode = intent.getBooleanExtra(EXTRA_TEST_MODE, false)
                if (config.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                // A foreground service must show its notification immediately;
                // the potentially blocking native startup is queued below.
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                workerHandler.post {
                    testMode = requestedTestMode
                    try {
                        startCoreAndTunnel(config)
                    } catch (error: Exception) {
                        Log.e("JPN-TURN", "VPN/TURN startup failed", error)
                        TurnDiagnostics.record(
                            this,
                            "Ошибка запуска VPN/TURN: ${error.javaClass.simpleName}: ${error.message ?: "без описания"}"
                        )
                        stopCoreAndTunnel()
                        publishState("error", error.message ?: "Не удалось запустить Xray", testMode)
                        if (startId == latestStartId) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(startId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCoreAndTunnel(sourceConfig: String) {
        stopCoreAndTunnel()

        val config = JSONObject(sourceConfig)
        val tunMtu = config.optInt("jpnTunMtu", 1500).coerceIn(1280, 9000)
        val sniffingEnabled = config.optBoolean("jpnSniffingEnabled", true)
        val turnConfig = config.optJSONObject("jpnTurn")?.let { JSONObject(it.toString()) }
        // These are JPN-side controls, not Xray configuration fields.
        config.remove("jpnTunMtu")
        config.remove("jpnSniffingEnabled")
        config.remove("jpnTurn")

        val builder = Builder()
            .setSession("JPN")
            .setMtu(tunMtu)
            .addDnsServer("1.1.1.1")
            .addAddress("10.111.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)

        // Per-app routing belongs only to ordinary TUN/VLESS sessions.
        // TURN/WireGuard must always use its own full-tunnel behavior and must
        // not inherit the app allow-/block-list configured for TUN mode.
        if (testMode) {
            builder.addAllowedApplication(packageName)
        } else if (turnConfig == null) {
            val prefs = getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("routing_enabled", false)) {
                val mode = prefs.getString("routing_mode", "selected") ?: "selected"
                val selectedPackages = prefs.getStringSet("routing_packages", emptySet())
                    ?.toSet()
                    .orEmpty()
                    .filter { app ->
                        app != packageName &&
                            runCatching { packageManager.getApplicationInfo(app, 0) }.isSuccess
                    }
                when (mode) {
                    "selected" -> {
                        // Empty or stale allow-list must never accidentally become full-tunnel.
                        val allowed = selectedPackages.ifEmpty { listOf(packageName) }
                        allowed.forEach { app -> builder.addAllowedApplication(app) }
                    }
                    "exclude" -> selectedPackages.forEach { app ->
                        builder.addDisallowedApplication(app)
                    }
                }
            }
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("Android did not establish the VPN interface")
        if (turnConfig != null && !testMode) {
            TurnDiagnostics.record(this, "Android VPN/TUN-интерфейс успешно создан")
        }

        // Start the per-socket bypass bridge only after Android has established
        // the VPN. The TURN child process will pass outbound socket FDs here via
        // SCM_RIGHTS; excluding the whole JPN package would also bypass Xray.
        if (turnConfig != null && !testMode) {
            TurnDiagnostics.record(this, "Запуск защиты TURN-сокетов")
            val protector = TurnSocketProtector(this)
            TurnDiagnostics.record(this, "Создание локального сокета защиты TURN")
            protector.start()
            turnSocketProtector = protector
            TurnDiagnostics.record(this, "Защита TURN-сокетов запущена")
            startTurnClient(turnConfig, protector.socketName)
        }

        // Xray's outbound sockets must bypass this VPN interface to avoid a routing loop.
        LibXray.registerDialerController(socketController)
        LibXray.registerListenerController(socketController)

        val env = config.optJSONObject("env") ?: JSONObject().also {
            config.put("env", it)
        }
        // Xray reads this value as an environment-variable string.
        env.put("xray.tun.fd", vpnInterface!!.fd.toString())

        val inbounds = config.optJSONArray("inbounds") ?: JSONArray().also {
            config.put("inbounds", it)
        }
        val tunInbound = JSONObject()
            .put("tag", "jpn-tun-in")
            .put("port", 0)
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    // Android assigns the interface name when Builder.establish() is called.
                    .put("name", "jpn-tun")
                    .put("mtu", tunMtu)
            )
        // Preserve destination metadata for Xray protocol handling inside the tunnel.
        tunInbound.put(
            "sniffing",
            JSONObject()
                .put("enabled", sniffingEnabled)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("metadataOnly", false)
                .put("routeOnly", false)
        )
        inbounds.put(tunInbound)

        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "runXray")
            .put("payload", JSONObject().put("xrayJson", config.toString()))

        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(response.optString("error", "Xray failed to start"))
        }
        coreStarted = true
        if (turnConfig != null && !testMode) {
            TurnDiagnostics.record(this, "Xray core успешно запущен; ожидается обмен через TURN")
        }
        val statusMessage = when {
            testMode -> "Тестовый туннель подключён"
            turnConfig != null -> {
                val threads = turnConfig.optInt("threads", 10).coerceIn(1, 64)
                val captchaCount = (threads + 9) / 10
                "Нужно будет подтвердить $captchaCount CAPTCHA"
            }
            else -> "VPN подключён"
        }
        publishState(if (turnConfig != null && !testMode) "connecting" else "connected", statusMessage, testMode)
    }

    private fun startTurnClient(turnConfig: JSONObject, socketProtectionName: String) {
        TurnDiagnostics.record(this, "Подготовка TURN-клиента")
        val peer = turnConfig.optString("peer").trim()
        val vkLink = turnConfig.optString("vkLink").trim()
        val listen = turnConfig.optString("listen", "127.0.0.1:9000").trim()
        val threads = turnConfig.optInt("threads", 40).coerceIn(1, 64)
        val streamsPerCredential = 10
        if (peer.isBlank() || vkLink.isBlank()) {
            throw IllegalStateException("Не заполнены адрес TURN-сервера и ссылка VK Calls")
        }

        // Android extracts jniLibs into nativeLibraryDir with executable
        // permissions. The Go binary is an executable, not a JNI library.
        val executable = File(applicationInfo.nativeLibraryDir, "libvkturn.so")
        if (!executable.isFile || !executable.canExecute()) {
            throw IllegalStateException("TURN-ядро не найдено в APK или не имеет права на запуск")
        }

        val command = listOf(
            executable.absolutePath,
            "-peer", peer,
            "-vk-link", vkLink,
            "-listen", listen,
            "-n", threads.toString(),
            "-streams-per-cred", streamsPerCredential.toString(),
            "-udp",
            "--manual-captcha"
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.directory(filesDir)
        builder.environment()["JPN_TURN_PROTECT_SOCKET"] = "@$socketProtectionName"
        builder.environment()["VK_PROFILE_PATH"] =
            File(filesDir, "vk_profile.json").absolutePath

        getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).edit()
            .putInt("turn_connections", 0)
            .putInt("turn_connection_limit", threads)
            .apply()
        TurnDiagnostics.record(this, "Параметры запуска: запрошено потоков: $threads; потоков на credential cache: $streamsPerCredential")
        TurnDiagnostics.record(this, "Запуск Go TURN-процесса")
        val child = builder.start()
        turnProcess = child
        TurnDiagnostics.setProcessState(this, true, null)

        Thread({
            var activeTurnConnections = 0
            runCatching {
                child.inputStream.bufferedReader().useLines { output ->
                    output.forEach { line ->
                        Log.i("JPN-TURN", line)
                        TurnDiagnostics.record(this@XrayVpnService, line)
                        when {
                            line.contains("Established DTLS connection", ignoreCase = true) -> {
                                activeTurnConnections = (activeTurnConnections + 1).coerceAtMost(threads)
                                val connectionCount = activeTurnConnections
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        publishTurnConnectionCount(connectionCount, threads)
                                        updateVpnNotification("VPN-подключение активно")
                                        publishState("connected", "VPN подключён через TURN")
                                    }
                                }
                            }
                            line.contains("Closed DTLS connection", ignoreCase = true) -> {
                                activeTurnConnections = (activeTurnConnections - 1).coerceAtLeast(0)
                                val connectionCount = activeTurnConnections
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        publishTurnConnectionCount(connectionCount, threads)
                                    }
                                }
                            }
                            line.startsWith("JPN_CAPTCHA_URL=") -> {
                                val captchaUrl = line.substringAfter("JPN_CAPTCHA_URL=").trim()
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        val opened = showTurnCaptcha(captchaUrl)
                                        publishState("connecting", "Ожидается подтверждение CAPTCHA VK", testMode)
                                        if (opened) {
                                            TurnDiagnostics.record(this@XrayVpnService, "Окно CAPTCHA открыто поверх JPN")
                                        }
                                    }
                                }
                            }
                            line.contains("manually open this URL:", ignoreCase = true) ||
                                line.contains("Open this URL in your browser:", ignoreCase = true) -> {
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        publishState("connecting", "TURN ожидает подтверждения VK Calls", testMode)
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                Log.w("JPN-TURN", "Could not read TURN core output", error)
            }
        }, "JPN-TURN-Output").apply {
            isDaemon = true
            start()
        }

        Thread({
            val exitCode = runCatching { child.waitFor() }.getOrDefault(-1)
            if (turnProcess === child) {
                workerHandler.post {
                    if (turnProcess === child) {
                        turnProcess = null
                        Log.e("JPN-TURN", "TURN core exited unexpectedly: $exitCode")
                        TurnDiagnostics.record(this@XrayVpnService, "TURN-процесс завершился с кодом: $exitCode")
                        TurnDiagnostics.setProcessState(this@XrayVpnService, false, exitCode)
                        if (coreStarted) {
                            stopCoreAndTunnel()
                            publishState("error", "TURN-клиент завершился (код: $exitCode)", testMode)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }, "JPN-TURN-Watcher").apply {
            isDaemon = true
            start()
        }

        // UDP listener creation happens immediately in the Go core. A short
        // liveness check catches missing libraries, bind errors and bad binaries
        // before Xray starts sending WireGuard packets to the local port.
        Thread.sleep(400)
        if (!child.isAlive) {
            val exitCode = runCatching { child.exitValue() }.getOrDefault(-1)
            turnProcess = null
            throw IllegalStateException("TURN-клиент не запустился (код: $exitCode)")
        }
        Log.i("JPN-TURN", "TURN client started; local listener: $listen")
        TurnDiagnostics.record(this, "TURN-процесс запущен; локальный listener: $listen")
    }

    private fun stopCoreAndTunnel() {
        clearCaptchaNotification()
        if (coreStarted) {
            runCatching {
                LibXray.invoke(
                    JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "stopXray")
                        .toString()
                )
            }
            coreStarted = false
        }

        val child = turnProcess
        turnProcess = null
        if (child != null) {
            TurnDiagnostics.record(this, "Остановка TURN-процесса")
            runCatching { child.destroy() }
            var exited = child.waitFor(1, TimeUnit.SECONDS)
            if (!exited) {
                TurnDiagnostics.record(this, "TURN-процесс не завершился штатно; принудительная остановка")
                child.destroyForcibly()
                // Do not release the listener/protection socket or allow a new
                // TURN instance to start until the old process is truly gone.
                child.waitFor()
                exited = true
            }
            val exitCode = child.exitValue()
            TurnDiagnostics.record(this, "TURN-процесс полностью остановлен (код: $exitCode)")
            TurnDiagnostics.setProcessState(this, false, exitCode)
        }

        getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).edit()
            .putInt("turn_connections", 0)
            .putInt("turn_connection_limit", 0)
            .apply()
        runCatching { turnSocketProtector?.close() }
        turnSocketProtector = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun onRevoke() {
        workerHandler.post {
            stopCoreAndTunnel()
            publishState("disconnected", "Разрешение VPN отозвано")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Cleanup is queued on the process-wide lifecycle worker. Do not stop
        // that worker here: a replacement service instance must share the same
        // queue and wait behind this cleanup before starting another TURN core.
        if (::workerHandler.isInitialized) {
            workerHandler.post {
                stopCoreAndTunnel()
                TurnDiagnostics.record(this, "Очистка VPN/TURN при уничтожении сервиса завершена")
            }
        }
        super.onDestroy()
    }


    private fun showTurnCaptcha(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val isVkHost = host == "id.vk.ru" || host == "id.vk.com" ||
            host.endsWith(".vk.ru") || host.endsWith(".vk.com")
        if (uri.scheme != "https" || !isVkHost || uri.userInfo != null) {
            TurnDiagnostics.record(this, "Отклонён небезопасный URL CAPTCHA VK ID")
            return false
        }

        return runCatching {
            showCaptchaNotification(url)
            if (com.jpn.app.MainActivity.isVisible) {
                startActivity(Intent(this, com.jpn.app.TurnCaptchaActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_CAPTCHA_URL, url)
                })
                TurnDiagnostics.record(this, "Встроенный экран VK CAPTCHA открыт в JPN")
            } else {
                getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putString(com.jpn.app.MainActivity.KEY_PENDING_TURN_CAPTCHA, url)
                    .apply()
                TurnDiagnostics.record(this, "CAPTCHA ожидает: открой JPN или нажми уведомление; URL сохранён до возвращения пользователя")
            }
            true
        }.onFailure { error ->
            TurnDiagnostics.record(this, "Не удалось открыть экран CAPTCHA: ${error.message ?: "ошибка Android"}")
        }.getOrDefault(false)
    }

    private fun clearCaptchaNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(CAPTCHA_NOTIFICATION_ID)
        }
        getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).edit()
            .remove(KEY_CAPTCHA_NOTIFICATION_URL)
            .remove(com.jpn.app.MainActivity.KEY_PENDING_TURN_CAPTCHA)
            .apply()
    }

    private fun showCaptchaNotification(url: String) {
        val prefs = getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAPTCHA_NOTIFICATION_URL, url).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTCHA_CHANNEL_ID,
                "JPN — CAPTCHA",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о необходимости подтвердить CAPTCHA VK"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openCaptchaIntent = Intent(this, com.jpn.app.TurnCaptchaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CAPTCHA_URL, url)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            4105,
            openCaptchaIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CAPTCHA_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("JPN — требуется CAPTCHA")
            .setContentText("Подтверди CAPTCHA VK, чтобы продолжить VPN")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(CAPTCHA_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            TurnDiagnostics.record(this, "Не удалось показать уведомление CAPTCHA: ${error.message ?: "ошибка Android"}")
        }
    }

    private fun publishTurnConnectionCount(connections: Int, limit: Int) {
        val safeConnections = connections.coerceIn(0, limit.coerceAtLeast(0))
        getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).edit()
            .putInt("turn_connections", safeConnections)
            .putInt("turn_connection_limit", limit)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra("state", "connected")
                .putExtra("message", "VPN подключён через TURN")
                .putExtra(EXTRA_TURN_CONNECTIONS, safeConnections)
                .putExtra(EXTRA_TURN_CONNECTION_LIMIT, limit)
                .putExtra(EXTRA_TEST_MODE, false)
        )
    }

    private fun publishState(state: String, message: String, isTestMode: Boolean = testMode) {
        if (!isTestMode) {
            getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("vpn_state", state)
                .putString("vpn_message", message)
                .apply()
        }
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra("state", state)
                .putExtra("message", message)
                .putExtra(EXTRA_TURN_CONNECTIONS, getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).getInt("turn_connections", 0))
                .putExtra(EXTRA_TURN_CONNECTION_LIMIT, getSharedPreferences("jpn_settings", Context.MODE_PRIVATE).getInt("turn_connection_limit", 0))
                .putExtra(EXTRA_TEST_MODE, isTestMode)
        )
        if (!isTestMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                this,
                ComponentName(this, com.jpn.app.QuickSettingsTileService::class.java)
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JPN VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun updateVpnNotification(message: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

    private fun buildNotification(message: String = "VPN-подключение активно"): Notification {
        val openAppIntent = Intent(this, com.jpn.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            4104,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("JPN")
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

}
