package com.jpn.app

import android.app.Activity
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jpn.app.vpn.XrayVpnService
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Loads the real VK ID CAPTCHA page and returns its success token to the waiting TURN core. */
class TurnCaptchaActivity : Activity() {
    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    @Volatile private var tokenSubmitted = false

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
            text = "Открой настоящую страницу VK и пройди проверку. После подтверждения подключение продолжится автоматически."
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
                status?.text = "Загружается страница VK ID…"
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
        browser.loadUrl(captchaUrl)
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
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
