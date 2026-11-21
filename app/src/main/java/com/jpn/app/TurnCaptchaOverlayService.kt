package com.jpn.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jpn.app.vpn.TurnDiagnostics
import com.jpn.app.vpn.XrayVpnService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * Real Android overlay for VK CAPTCHA. Runs in the isolated :captcha process,
 * so WebView proxy configuration cannot affect JPN's other WebViews.
 */
class TurnCaptchaOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.jpn.app.turncaptcha.SHOW"
        const val ACTION_STOP = "com.jpn.app.turncaptcha.STOP"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val directExecutor = Executor { command -> command.run() }
    private lateinit var windowManager: WindowManager
    private var root: FrameLayout? = null
    private var browser: WebView? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    private var proxy: CaptchaConnectProxy? = null
    private var currentUrl = ""
    @Volatile private var tokenSubmitted = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf(startId)
            ACTION_SHOW -> {
                val url = intent.getStringExtra(XrayVpnService.EXTRA_CAPTCHA_URL).orEmpty()
                mainHandler.post { showCaptcha(url) }
            }
        }
        return START_NOT_STICKY
    }

    private fun showCaptcha(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !isAllowedVkUrl(uri)) {
            TurnDiagnostics.record(this, "Отклонён небезопасный URL CAPTCHA VK ID")
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            TurnDiagnostics.record(this, "Не выдано разрешение на плавающее окно CAPTCHA")
            Toast.makeText(this, "Разреши JPN отображаться поверх других приложений", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (root != null) {
            expandOverlay()
            if (url != currentUrl) browser?.loadUrl(url)
            return
        }

        currentUrl = url
        tokenSubmitted = false
        val network = findPhysicalNetwork()
        val port = network?.let { selected ->
            runCatching { CaptchaConnectProxy(selected).also { proxy = it }.start() }.getOrNull()
        }
        val proxySupported = port != null && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

        val overlay = FrameLayout(this)
        root = overlay
        val web = WebView(this)
        browser = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(CaptchaBridge(), "JPNCaptchaBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                progress?.visibility = View.VISIBLE
                status?.text = "Загружается страница VK ID через физическую сеть…"
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                progress?.visibility = View.GONE
                status?.text = "Пройди проверку на странице VK. Ожидаю подтверждение…"
            }

            override fun shouldOverrideUrlLoading(view: WebView?, pageUrl: String?): Boolean {
                val next = runCatching { Uri.parse(pageUrl.orEmpty()) }.getOrNull() ?: return true
                return !isAllowedVkUrl(next)
            }
        }

        buildExpandedContent()
        installDocumentStartCapture(web)
        runCatching { windowManager.addView(overlay, expandedParams()) }.onFailure { error ->
            TurnDiagnostics.record(this, "Не удалось добавить окно CAPTCHA: " + (error.message ?: "ошибка WindowManager"))
            cleanupOverlay()
            stopSelf()
            return
        }

        if (!proxySupported) {
            setStatus("Не удалось подготовить физическую сеть или локальный прокси. CAPTCHA не отправлена через VPN.")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            setStatus("Обнови Android System WebView: без раннего перехвата нельзя безопасно получить результат CAPTCHA.")
            return
        }

        val config = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:" + port)
            .addBypassRule("localhost")
            .build()
        ProxyController.getInstance().setProxyOverride(config, directExecutor, Runnable {
            if (root != null && !tokenSubmitted) {
                setStatus("Прокси готов. Загружаю VK CAPTCHA через физическую сеть телефона…")
                browser?.loadUrl(url)
            }
        })
        TurnDiagnostics.record(this, "Плавающее окно CAPTCHA показано; WebView использует физическую сеть")
    }

    private fun buildExpandedContent() {
        val overlay = root ?: return
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x99000000.toInt())

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "Подтверждение VK Calls"
            textSize = 17f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(Button(this).apply {
            text = "Свернуть"
            setOnClickListener { collapseOverlay() }
        })
        panel.addView(header)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        panel.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ))
        status = TextView(this).apply {
            text = "Открываю страницу VK ID…"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        panel.addView(status)
        browser?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            panel.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels - dp(28)).coerceAtLeast(dp(280)).coerceAtMost(dp(560))
        val height = (metrics.heightPixels - dp(72)).coerceAtLeast(dp(360)).coerceAtMost(dp(820))
        overlay.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        if (overlay.isAttachedToWindow) runCatching {
            windowManager.updateViewLayout(overlay, expandedParams())
        }
    }

    private fun collapseOverlay() {
        val overlay = root ?: return
        (browser?.parent as? ViewGroup)?.removeView(browser)
        overlay.removeAllViews()
        overlay.setBackgroundColor(Color.TRANSPARENT)
        val bubble = TextView(this).apply {
            text = "VK CAPTCHA · ожидает"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF514B83.toInt())
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(10).toFloat()
            setOnClickListener { expandOverlay() }
        }
        overlay.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        runCatching { windowManager.updateViewLayout(overlay, collapsedParams()) }
    }

    private fun expandOverlay() {
        if (root == null) return
        buildExpandedContent()
    }

    private fun expandedParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private fun collapsedParams() = WindowManager.LayoutParams(
        dp(210),
        dp(56),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(14)
        y = dp(110)
    }

    private fun setStatus(value: String) {
        status?.text = value
        TurnDiagnostics.record(this, value)
    }

    private fun findPhysicalNetwork(): Network? = runCatching {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val physical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!physical || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) null
            else network to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.maxByOrNull { if (it.second) 1 else 0 }?.first
    }.getOrNull()

    private fun installDocumentStartCapture(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            setStatus("Обнови Android System WebView: не поддерживается перехват результата CAPTCHA.")
            return
        }
        val script = """
            (function() {
              if (window.__jpnCaptchaHookInstalled) return;
              window.__jpnCaptchaHookInstalled = true;
              function capture(text) {
                try {
                  var data = typeof text === 'string' ? JSON.parse(text) : text;
                  var token = data && data.response && data.response.success_token;
                  if (typeof token === 'string' && token.length > 0 && window.JPNCaptchaBridge) {
                    window.JPNCaptchaBridge.onSuccess(token);
                  }
                } catch (e) {}
              }
              var originalOpen = XMLHttpRequest.prototype.open;
              var originalSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.__jpnCaptchaUrl = String(url || '');
                return originalOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function() {
                if (this.__jpnCaptchaUrl.indexOf('captchaNotRobot.check') >= 0) {
                  this.addEventListener('load', function() { capture(this.responseText); });
                }
                return originalSend.apply(this, arguments);
              };
              if (window.fetch) {
                var originalFetch = window.fetch;
                window.fetch = function() {
                  var arg = arguments[0];
                  var url = typeof arg === 'string' ? arg : (arg && arg.url) || '';
                  var promise = originalFetch.apply(this, arguments);
                  if (String(url).indexOf('captchaNotRobot.check') >= 0) {
                    promise.then(function(response) {
                      response.clone().text().then(capture).catch(function(){});
                    }).catch(function(){});
                  }
                  return promise;
                };
              }
            })();
        """.trimIndent()
        WebViewCompat.addDocumentStartJavaScript(
            view, script, setOf("https://id.vk.ru", "https://*.vk.ru", "https://*.vk.com")
        )
    }

    private inner class CaptchaBridge {
        @JavascriptInterface
        fun onSuccess(token: String?) {
            if (token.isNullOrBlank() || token.length > 8192 || tokenSubmitted) return
            tokenSubmitted = true
            Thread({
                val accepted = postTokenToTurnCore(token)
                mainHandler.post {
                    if (accepted) {
                        TurnDiagnostics.record(this@TurnCaptchaOverlayService, "VK CAPTCHA подтверждена; токен передан TURN-клиенту")
                        Toast.makeText(this@TurnCaptchaOverlayService, "CAPTCHA подтверждена VK", Toast.LENGTH_SHORT).show()
                        stopSelf()
                    } else {
                        tokenSubmitted = false
                        setStatus("Не удалось передать результат в TURN-клиент. Повтори проверку.")
                    }
                }
            }, "JPN-CAPTCHA-Token").start()
        }
    }

    private fun postTokenToTurnCore(token: String): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:8765/local-captcha-result").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "token=" + URLEncoder.encode(token, "UTF-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        connection.disconnect()
        code in 200..299
    }.getOrDefault(false)

    private fun isAllowedVkUrl(uri: Uri): Boolean {
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme == "https" && uri.userInfo == null &&
            (host == "id.vk.ru" || host == "id.vk.com" ||
                host.endsWith(".vk.ru") || host.endsWith(".vk.com"))
    }

    private fun cleanupOverlay() {
        val overlay = root
        root = null
        if (overlay != null && overlay.isAttachedToWindow) runCatching {
            windowManager.removeView(overlay)
        }
        browser?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("JPNCaptchaBridge")
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        browser = null
        proxy?.stop()
        proxy = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) runCatching {
            ProxyController.getInstance().clearProxyOverride(directExecutor, Runnable {})
        }
    }

    override fun onDestroy() {
        cleanupOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class CaptchaConnectProxy(private val network: Network) {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        val port: Int get() = server.localPort

        fun start(): Int {
            thread(name = "JPN-CAPTCHA-Proxy", isDaemon = true) {
                while (running) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    thread(name = "JPN-CAPTCHA-Connect", isDaemon = true) { handle(client) }
                }
            }
            return port
        }

        private fun handle(client: Socket) {
            client.use { local ->
                local.soTimeout = 20000
                val header = readHeader(local.getInputStream()) ?: return
                local.soTimeout = 0
                val parts = header.substringBefore("\r\n").split(' ')
                if (parts.size < 2 || parts[0] != "CONNECT") {
                    local.getOutputStream().write("HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val split = parts[1].lastIndexOf(':')
                if (split <= 0) {
                    local.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val host = parts[1].substring(0, split).trim('[', ']').lowercase()
                val port = parts[1].substring(split + 1).toIntOrNull()
                if (port != 443 || !isAllowedHost(host)) {
                    local.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val upstream = try {
                    val addresses = network.getAllByName(host)
                    var connected: Socket? = null
                    for (address in addresses) {
                        val candidate = network.socketFactory.createSocket()
                        try {
                            candidate.connect(InetSocketAddress(address, port), 15000)
                            connected = candidate
                            break
                        } catch (_: Exception) {
                            runCatching { candidate.close() }
                        }
                    }
                    connected ?: return
                } catch (_: Exception) {
                    local.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                upstream.use { remote ->
                    local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    val outgoing = thread(name = "JPN-CAPTCHA-Up", isDaemon = true) {
                        relay(remote.getInputStream(), local)
                    }
                    relay(local.getInputStream(), remote)
                    runCatching { remote.shutdownOutput() }
                    runCatching { outgoing.join(30000) }
                }
            }
        }

        private fun readHeader(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            val ending = byteArrayOf(13, 10, 13, 10)
            while (bytes.size() < 32768) {
                val value = input.read()
                if (value < 0) return null
                bytes.write(value)
                if (value.toByte() == ending[matched]) {
                    matched++
                    if (matched == ending.size) return bytes.toString(Charsets.ISO_8859_1.name())
                } else {
                    matched = if (value == 13) 1 else 0
                }
            }
            return null
        }

        private fun relay(input: InputStream, destination: Socket) {
            try {
                val output = destination.getOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { destination.shutdownOutput() }
            }
        }

        private fun isAllowedHost(host: String): Boolean =
            host == "id.vk.ru" || host == "id.vk.com" ||
                host.endsWith(".vk.ru") || host.endsWith(".vk.com")

        fun stop() {
            running = false
            runCatching { server.close() }
        }
    }
}
