package com.jpn.app

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

/** Loads VK CAPTCHA through a local CONNECT proxy whose upstream sockets use the physical network. */
class TurnCaptchaActivity : Activity() {
    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    private var connectProxy: CaptchaConnectProxy? = null
    @Volatile private var tokenSubmitted = false
    private val directExecutor = Executor { command -> command.run() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val captchaUrl = intent.getStringExtra(XrayVpnService.EXTRA_CAPTCHA_URL).orEmpty()
        val uri = runCatching { Uri.parse(captchaUrl) }.getOrNull()
        if (uri == null || !isAllowedVkUrl(uri)) {
            Toast.makeText(this, "Недопустимый адрес CAPTCHA VK", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val physicalNetwork = findPhysicalNetwork()
        val proxyPort = physicalNetwork?.let { network ->
            runCatching {
                CaptchaConnectProxy(network).also { connectProxy = it }.start()
            }.getOrNull()
        }
        val canProxy = proxyPort != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "Подтверждение VK Calls"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(Button(this).apply {
            text = "Закрыть"
            setOnClickListener { finish() }
        })
        root.addView(header)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ))
        status = TextView(this).apply {
            text = when {
                physicalNetwork == null ->
                    "Не найдена физическая сеть с доступом в интернет. CAPTCHA не открыта, чтобы не отправлять её через VPN."
                proxyPort == null ->
                    "Не удалось запустить локальный сетевой прокси. CAPTCHA не открыта."
                !canProxy ->
                    "Текущая версия Android System WebView не поддерживает настройку прокси. Обнови Android System WebView."
                else ->
                    "VK CAPTCHA загружается через локальный прокси и физическую сеть телефона, в обход JPN VPN."
            }
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        val browser = WebView(this)
        webView = browser
        browser.settings.apply {
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
            setAcceptThirdPartyCookies(browser, true)
        }
        browser.addJavascriptInterface(CaptchaBridge(), "JPNCaptchaBridge")
        installDocumentStartCapture(browser)
        browser.webChromeClient = WebChromeClient()
        browser.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                progress?.visibility = View.VISIBLE
                status?.text = "Загружается страница VK ID через физическую сеть…"
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                progress?.visibility = View.GONE
                status?.text = "Пройди проверку на странице VK. Ожидаю подтверждение…"
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val next = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return true
                return !isAllowedVkUrl(next)
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        JpnTheme.apply(this, root)

        if (canProxy) {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$proxyPort")
                .addBypassRule("localhost")
                .build()
            ProxyController.getInstance().setProxyOverride(config, directExecutor, Runnable {
                if (!isFinishing && !isDestroyed) {
                    status?.text = "Локальный прокси готов. Загружаю VK CAPTCHA напрямую через сеть телефона…"
                    browser.loadUrl(captchaUrl)
                }
            })
        } else {
            progress?.visibility = View.GONE
        }
    }

    /** Selects a validated physical network. All external proxy sockets are explicitly created on it. */
    private fun findPhysicalNetwork(): Network? = runCatching {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.allNetworks
            .mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                val physicalTransport =
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!physicalTransport ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) null
                else network to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            .maxByOrNull { (_, validated) -> if (validated) 1 else 0 }
            ?.first
    }.getOrNull()

    /**
     * A loopback-only HTTP CONNECT proxy. It never decrypts TLS: it opens the upstream
     * TCP socket using the selected Network's SocketFactory and relays encrypted bytes.
     */
    private class CaptchaConnectProxy(private val network: Network) {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var running = true
        val port: Int get() = server.localPort

        fun start(): Int {
            thread(name = "JPN-CAPTCHA-Proxy", isDaemon = true) {
                while (running) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    thread(name = "JPN-CAPTCHA-Connect", isDaemon = true) {
                        handle(client)
                    }
                }
            }
            return port
        }

        private fun handle(client: Socket) {
            client.use { local ->
                local.soTimeout = 20000
                val header = readHeader(local.getInputStream()) ?: return
                local.soTimeout = 0
                val requestLine = header.substringBefore("\r\n")
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "CONNECT") {
                    local.getOutputStream().write("HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val authority = parts[1]
                val split = authority.lastIndexOf(':')
                if (split <= 0) {
                    local.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val host = authority.substring(0, split).trim('[', ']').lowercase()
                val port = authority.substring(split + 1).toIntOrNull()
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
                    remote.soTimeout = 0
                    local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    val upstreamToClient = thread(name = "JPN-CAPTCHA-Up", isDaemon = true) {
                        relay(remote.getInputStream(), local)
                    }
                    relay(local.getInputStream(), remote)
                    runCatching { remote.shutdownOutput() }
                    runCatching { upstreamToClient.join(30000) }
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

    private fun installDocumentStartCapture(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            status?.text = "Эта версия Android System WebView не поддерживает ранний перехват ответа VK. Обнови Android System WebView и повтори попытку."
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
            view,
            script,
            setOf("https://id.vk.ru", "https://*.vk.ru", "https://*.vk.com")
        )
    }

    inner class CaptchaBridge {
        @JavascriptInterface
        fun onSuccess(token: String?) {
            if (token.isNullOrBlank() || token.length > 8192 || tokenSubmitted) return
            tokenSubmitted = true
            Thread({
                val accepted = postTokenToTurnCore(token)
                runOnUiThread {
                    if (accepted) {
                        status?.text = "VK подтвердил проверку. Продолжаю подключение…"
                        Toast.makeText(this@TurnCaptchaActivity, "CAPTCHA подтверждена VK", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        tokenSubmitted = false
                        status?.text = "Не удалось передать результат в TURN-клиент. Повтори проверку."
                    }
                }
            }, "JPN-CAPTCHA-Token").start()
        }
    }

    private fun postTokenToTurnCore(token: String): Boolean = runCatching {
        val connection = (URL("http://127.0.0.1:8765/local-captcha-result").openConnection() as HttpURLConnection)
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

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("JPNCaptchaBridge")
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webView = null
        connectProxy?.stop()
        connectProxy = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(directExecutor, Runnable {})
            }
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
