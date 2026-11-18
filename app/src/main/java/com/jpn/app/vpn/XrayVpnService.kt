package com.jpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import libXray.DialerController
import libXray.LibXray

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

        private const val CHANNEL_ID = "jpn_vpn"
        private const val NOTIFICATION_ID = 4101

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
                        if (startId == latestStartId) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(startId)
                        }
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
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCoreAndTunnel(sourceConfig: String) {
        stopCoreAndTunnel()

        val builder = Builder()
            .setSession("JPN")
            .setMtu(1500)
            .addDnsServer("1.1.1.1")
            .addAddress("10.111.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)

        // A temporary check routes only JPN itself through the test tunnel.
        if (testMode) builder.addAllowedApplication(packageName)

        // Apply the user routing rules only for regular VPN sessions.
        val routingPreferences = getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        val routingEnabled = routingPreferences.getBoolean("routing_enabled", false)
        val routingMode = routingPreferences.getString("routing_mode", "selected")
        val selectedPackages = routingPreferences
            .getStringSet("routing_packages", emptySet())
            ?.toSet()
            ?: emptySet()
        if (!testMode && routingEnabled && routingMode == "selected") {
            var atLeastOneAllowed = false
            selectedPackages.forEach { appPackage ->
                if (runCatching { builder.addAllowedApplication(appPackage) }.isSuccess) {
                    atLeastOneAllowed = true
                }
            }
            // An empty allow-list means Android routes every app through the VPN.
            // Restrict it to JPN itself instead, so "only selected" + no selection
            // does not unexpectedly turn into full-device routing.
            if (!atLeastOneAllowed) builder.addAllowedApplication(packageName)
        } else if (!testMode && routingEnabled) {
            selectedPackages.forEach { appPackage ->
                runCatching { builder.addDisallowedApplication(appPackage) }
            }
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("Android did not establish the VPN interface")

        // Xray's outbound sockets must bypass this VPN interface to avoid a routing loop.
        LibXray.registerDialerController(socketController)
        LibXray.registerListenerController(socketController)

        val config = JSONObject(sourceConfig)
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
                    .put("mtu", 1500)
            )
        // Recover destination domains from TLS/HTTP/QUIC traffic so that
        // GeoSite and manual domain bypass rules work with Android's TUN.
        tunInbound.put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("metadataOnly", false)
                .put("routeOnly", true)
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
        publishState("connected", if (testMode) "Тестовый туннель подключён" else "VPN подключён", testMode)
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
