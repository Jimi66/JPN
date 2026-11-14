package com.jpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
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
        const val ACTION_STATE_CHANGED = "com.jpn.app.vpn.STATE_CHANGED"

        private const val CHANNEL_ID = "jpn_vpn"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, xrayJson: String) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_XRAY_JSON, xrayJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private var vpnInterface: android.os.ParcelFileDescriptor? = null
    private var coreStarted = false

    private val socketController = object : DialerController {
        // gomobile exposes Go's int as Java/Kotlin Long.
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCoreAndTunnel()
                publishState("disconnected", "VPN отключён")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_XRAY_JSON)
                if (config.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                try {
                    startCoreAndTunnel(config)
                } catch (error: Exception) {
                    stopCoreAndTunnel()
                    publishState("error", error.message ?: "Не удалось запустить Xray")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
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
            .addAddress("10.111.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)

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
        inbounds.put(
            JSONObject()
                .put("tag", "jpn-tun-in")
                .put("port", 0)
                .put("protocol", "tun")
                .put(
                    "settings",
                    JSONObject()
                        .put("name", "jpn-tun")
                        .put("mtu", 1500)
                )
        )

        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "runXray")
            .put("payload", JSONObject().put("xrayJson", config.toString()))

        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(response.optString("error", "Xray failed to start"))
        }
        coreStarted = true
        publishState("connected", "VPN подключён")
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
        stopCoreAndTunnel()
        publishState("disconnected", "Разрешение VPN отозвано")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCoreAndTunnel()
        super.onDestroy()
    }


    private fun publishState(state: String, message: String) {
        getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("vpn_state", state)
            .putString("vpn_message", message)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra("state", state)
                .putExtra("message", message)
        )
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
            .setContentText("Xray VPN is running")
            .setOngoing(true)
            .build()
    }
}
