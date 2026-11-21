package com.jpn.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jpn.app.vpn.XrayVpnService

/** In-app browser for the local VK CAPTCHA reverse proxy provided by the TURN core. */
class TurnCaptchaActivity : Activity() {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val url = intent.getStringExtra(XrayVpnService.EXTRA_CAPTCHA_URL).orEmpty()
        if (!isAllowedCaptchaUrl(url)) {
            Toast.makeText(this, "Недопустимый адрес CAPTCHA", Toast.LENGTH_LONG).show()
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

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ))
        root.addView(TextView(this).apply {
            text = "Пройди проверку VK. После успешного подтверждения авторизация продолжится автоматически."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        })

        val browser = WebView(this)
        webView = browser
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, true)
        }
        browser.webChromeClient = WebChromeClient()
        browser.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                progress.visibility = View.GONE
                view?.let { checkForCompletion(it, 0) }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        JpnTheme.apply(this, root)
        browser.loadUrl(url)
    }

    private fun checkForCompletion(view: WebView, attempt: Int) {
        if (isFinishing || attempt > 120) return
        view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { result ->
            if (result?.contains("Done!") == true) {
                Toast.makeText(this, "CAPTCHA пройдена, продолжаю подключение", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                handler.postDelayed({ checkForCompletion(view, attempt + 1) }, 500)
            }
        }
    }

    private fun isAllowedCaptchaUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "http" &&
            uri.host in setOf("localhost", "127.0.0.1", "::1") &&
            uri.port == 8765
    }.getOrDefault(false)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
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
