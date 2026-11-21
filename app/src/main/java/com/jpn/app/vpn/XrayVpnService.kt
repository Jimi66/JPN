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
        const val EXTRA_CAPTCHA_URL = "captcha_url"

        private const val CHANNEL_ID = "jpn_vpn"
        private const val NOTIFICATION_ID = 4101
        private const val CAPTCHA_CHANNEL_ID = "jpn_turn_captcha"
        private const val CAPTCHA_NOTIFICATION_ID = 4103

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

        fun stop(context: Context, testMode: Boolean = false) {
            context.startService(Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TEST_MODE, testMode)
            })
        }
    }

    private lateinit var workerThread: HandlerThread
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
        workerThread = HandlerThread("JPN-Xray-Service").apply { start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
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

        // Diagnostic ping mode is intentionally limited to JPN itself.
        // Normal VPN sessions may use Android-level per-app tunneling.
        if (testMode) {
            builder.addAllowedApplication(packageName)
        } else {
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
            turnSocketProtector = TurnSocketProtector(this).also { it.start() }
            TurnDiagnostics.record(this, "Защита TURN-сокетов запущена")
            startTurnClient(turnConfig)
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
            turnConfig != null -> "TURN-клиент запущен, ожидаю соединение"
            else -> "VPN подключён"
        }
        publishState(if (turnConfig != null && !testMode) "connecting" else "connected", statusMessage, testMode)
    }

    private fun startTurnClient(turnConfig: JSONObject) {
        TurnDiagnostics.record(this, "Подготовка TURN-клиента")
        val peer = turnConfig.optString("peer").trim()
        val vkLink = turnConfig.optString("vkLink").trim()
        val listen = turnConfig.optString("listen", "127.0.0.1:9000").trim()
        val threads = turnConfig.optInt("threads", 12).coerceIn(1, 64)
        val streamsPerCredential = turnConfig.optInt("streamsPerCredential", 12).coerceIn(1, 64)
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
        builder.environment()["JPN_TURN_PROTECT_SOCKET"] = "@jpn.turn.protect"
        builder.environment()["VK_PROFILE_PATH"] =
            File(filesDir, "vk_profile.json").absolutePath

        TurnDiagnostics.record(this, "Запуск Go TURN-процесса")
        val child = builder.start()
        turnProcess = child
        TurnDiagnostics.setProcessState(this, true, null)

        Thread({
            runCatching {
                child.inputStream.bufferedReader().useLines { output ->
                    output.forEach { line ->
                        Log.i("JPN-TURN", line)
                        TurnDiagnostics.record(this@XrayVpnService, line)
                        when {
                            line.startsWith("JPN_CAPTCHA_URL=") -> {
                                val captchaUrl = line.substringAfter("JPN_CAPTCHA_URL=").trim()
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        showTurnCaptchaNotification(captchaUrl)
                                        publishState("connecting", "Ожидается подтверждение CAPTCHA VK", testMode)
                                        TurnDiagnostics.record(this@XrayVpnService, "VK CAPTCHA передана во встроенный WebView через уведомление")
                                    }
                                }
                            }
                            line.contains("Established DTLS connection", ignoreCase = true) -> {
                                workerHandler.post {
                                    if (turnProcess === child && coreStarted) {
                                        publishState("connected", "VPN подключён через TURN", testMode)
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
            TurnDiagnostics.setProcessState(this, false, null)
        }
        if (child != null) {
            runCatching { child.destroy() }
            val exited = runCatching { child.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) runCatching { child.destroyForcibly() }
        }

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
        // Keep cleanup off Android's main thread. quitSafely() drains this task
        // and any lifecycle work already queued ahead of it.
        if (::workerHandler.isInitialized) {
            workerHandler.post { stopCoreAndTunnel() }
            workerThread.quitSafely()
        }
        super.onDestroy()
    }


    private fun showTurnCaptchaNotification(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "http" || uri.host !in setOf("localhost", "127.0.0.1", "::1") || uri.port != 8765) {
            TurnDiagnostics.record(this, "Отклонён небезопасный URL CAPTCHA")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            TurnDiagnostics.record(this, "Уведомление CAPTCHA не отправлено: нет разрешения POST_NOTIFICATIONS")
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        if (!notificationManager.areNotificationsEnabled()) {
            TurnDiagnostics.record(this, "Уведомление CAPTCHA не отправлено: уведомления JPN отключены в Android")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CAPTCHA_CHANNEL_ID,
                    "JPN — CAPTCHA",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Запросы на подтверждение CAPTCHA VK для TURN-подключения"
                    setShowBadge(true)
                }
            )
            val channel = notificationManager.getNotificationChannel(CAPTCHA_CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                TurnDiagnostics.record(this, "Уведомление CAPTCHA не отправлено: канал JPN — CAPTCHA отключён")
                return
            }
        }

        val intent = Intent(this, com.jpn.app.TurnCaptchaActivity::class.java).apply {
            putExtra(EXTRA_CAPTCHA_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            CAPTCHA_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CAPTCHA_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        })
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("JPN — требуется проверка VK")
            .setContentText("Нажмите, чтобы открыть CAPTCHA")
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "Открыть CAPTCHA", pendingIntent)
            .build()

        try {
            notificationManager.notify(CAPTCHA_NOTIFICATION_ID, notification)
            TurnDiagnostics.record(this, "Уведомление CAPTCHA успешно отправлено в отдельный канал JPN — CAPTCHA")
        } catch (error: SecurityException) {
            TurnDiagnostics.record(this, "Android отклонил уведомление CAPTCHA: ${error.message ?: "SecurityException"}")
        }
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

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("JPN")
            .setContentText("VPN-подключение активно")
            .setOngoing(true)
            .build()
    }
}
