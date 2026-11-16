package com.jpn.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SiteRoutingActivity : Activity() {
    companion object {
        private const val PREFS = "jpn_settings"
        private const val KEY_ENABLED = "site_routing_enabled"
        private const val KEY_STRATEGY = "site_domain_strategy"
        private const val KEY_GLOBAL_PROXY = "site_global_proxy"
        private const val KEY_ROUTE_ORDER = "site_route_order"
        private const val KEY_FAKE_DNS = "site_fake_dns"
        private const val KEY_REMOTE_DNS_TYPE = "site_remote_dns_type"
        private const val KEY_REMOTE_DNS = "site_remote_dns"
        private const val KEY_REMOTE_DNS_IP = "site_remote_dns_ip"
        private const val KEY_DOMESTIC_DNS_TYPE = "site_domestic_dns_type"
        private const val KEY_DOMESTIC_DNS = "site_domestic_dns"
        private const val KEY_DOMESTIC_DNS_IP = "site_domestic_dns_ip"
        private const val KEY_DNS_HOSTS = "site_dns_hosts"
        private const val KEY_GEOSITE_URL = "site_geosite_url"
        private const val KEY_GEOIP_URL = "site_geoip_url"
        private const val GEO_DEFAULT = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"
        private val RULE_KEYS = listOf(
            "site_proxy_sites", "site_proxy_ip",
            "site_direct_sites", "site_direct_ip",
            "site_block_sites", "site_block_ip"
        )
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val ruleInputs = linkedMapOf<String, EditText>()
    private val settingInputs = linkedMapOf<String, EditText>()
    private lateinit var enabledSwitch: Switch
    private lateinit var globalProxySwitch: Switch
    private lateinit var fakeDnsSwitch: Switch
    private lateinit var strategySpinner: Spinner
    private lateinit var routeOrderSpinner: Spinner
    private lateinit var remoteTypeSpinner: Spinner
    private lateinit var domesticTypeSpinner: Spinner
    private var initialSnapshot = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyRules()
        buildScreen()
        initialSnapshot = snapshot()
    }

    private fun migrateLegacyRules() {
        // Preserve rules from the previous three-list editor.
        val oldKeys = listOf("site_proxy_rules", "site_direct_rules", "site_block_rules")
        val newKeys = listOf("site_proxy_sites", "site_direct_sites", "site_block_sites")
        val edit = prefs.edit()
        oldKeys.forEachIndexed { index, old ->
            val new = newKeys[index]
            if (!prefs.contains(new) && prefs.contains(old)) edit.putString(new, prefs.getString(old, ""))
        }
        edit.apply()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "‹   Назад"; textSize = 18f; setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12)); setOnClickListener { finish() }
        })
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"; textSize = 21f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this).apply {
            text = "Вкл."
            isChecked = prefs.getBoolean(KEY_ENABLED, false)
        }
        titleRow.addView(enabledSwitch)
        root.addView(titleRow, matchWidth())
        root.addView(TextView(this).apply {
            text = "Настройки применяются при переподключении VPN. Для geosite: и geoip: нужны загруженные базы Xray."
            textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(5), 0, dp(10))
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        sectionTitle(body, "Основные")
        globalProxySwitch = addSwitch(body, "Global Proxy — весь трафик через прокси по умолчанию", KEY_GLOBAL_PROXY, true)
        strategySpinner = addSpinner(body, "Domain Strategy", listOf("AsIs", "IPIfNonMatch", "IPOnDemand"), KEY_STRATEGY, "IPIfNonMatch")
        routeOrderSpinner = addSpinner(body, "Порядок обработки правил", listOf(
            "Block → Direct → Proxy", "Direct → Block → Proxy", "Proxy → Direct → Block",
            "Proxy → Block → Direct", "Direct → Proxy → Block", "Block → Proxy → Direct"
        ), KEY_ROUTE_ORDER, "Block → Direct → Proxy")

        sectionTitle(body, "DNS")
        remoteTypeSpinner = addSpinner(body, "Remote DNS type", listOf("DoH", "DoU"), KEY_REMOTE_DNS_TYPE, "DoH")
        addSettingInput(body, "Remote DNS address", KEY_REMOTE_DNS, "https://cloudflare-dns.com/dns-query")
        addSettingInput(body, "Remote DNS IP (для DoH / bootstrap)", KEY_REMOTE_DNS_IP, "1.1.1.1")
        domesticTypeSpinner = addSpinner(body, "Domestic DNS type", listOf("DoH", "DoU"), KEY_DOMESTIC_DNS_TYPE, "DoH")
        addSettingInput(body, "Domestic DNS address", KEY_DOMESTIC_DNS, "https://dns.google/dns-query")
        addSettingInput(body, "Domestic DNS IP (для DoH / bootstrap)", KEY_DOMESTIC_DNS_IP, "8.8.8.8")
        addSettingInput(body, "DNS Hosts — домен = IP, по одной записи на строку", KEY_DNS_HOSTS, "")

        sectionTitle(body, "Geo-базы Xray")
        addSettingInput(body, "GeoSite file URL", KEY_GEOSITE_URL, GEO_DEFAULT + "geosite.dat")
        addSettingInput(body, "GeoIP file URL", KEY_GEOIP_URL, GEO_DEFAULT + "geoip.dat")
        val geoButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        geoButtons.addView(Button(this).apply {
            text = "Обновить GeoSite"; setOnClickListener { downloadGeoFile(KEY_GEOSITE_URL, "geosite.dat") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        geoButtons.addView(Button(this).apply {
            text = "Обновить GeoIP"; setOnClickListener { downloadGeoFile(KEY_GEOIP_URL, "geoip.dat") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(geoButtons, matchWidth())
        body.addView(TextView(this).apply {
            text = "Файлы хранятся в закрытом каталоге приложения. При ошибке загрузки предыдущая рабочая база сохраняется."
            textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(4), 0, dp(8))
        })

        sectionTitle(body, "Правила маршрутизации")
        addRuleInput(body, "Proxy Sites", "geosite:geolocation-!ru\ndomain:example.com", RULE_KEYS[0])
        addRuleInput(body, "Proxy IP", "geoip:us\n203.0.113.0/24", RULE_KEYS[1])
        addRuleInput(body, "Direct Sites", "geosite:category-private\ndomain:example.local", RULE_KEYS[2])
        addRuleInput(body, "Direct IP", "geoip:private\n192.168.0.0/16\n10.0.0.0/8\n172.16.0.0/12", RULE_KEYS[3])
        addRuleInput(body, "Block Sites", "geosite:category-ads-all", RULE_KEYS[4])
        addRuleInput(body, "Block IP", "", RULE_KEYS[5])
        body.addView(TextView(this).apply {
            text = "Добавляй по одному правилу на строку. Удалить правило можно удалением строки. Поддерживаются domain:, full:, regexp:, geosite:, geoip:, IP и CIDR. Категории geosite зависят от выбранного файла GeoSite."
            textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(8), 0, dp(12))
        })

        sectionTitle(body, "Дополнительно")
        fakeDnsSwitch = addSwitch(body, "Fake DNS", KEY_FAKE_DNS, false)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Сохранить и вернуться"
            setOnClickListener { saveSettings(); finish() }
        }, matchWidth())
        setContentView(root)
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(14), dp(2), dp(5))
        })
    }

    private fun addSwitch(parent: LinearLayout, label: String, key: String, default: Boolean): Switch {
        val control = Switch(this).apply {
            text = label; textSize = 14f; isChecked = prefs.getBoolean(key, default)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        parent.addView(control, matchWidth())
        return control
    }

    private fun addSpinner(parent: LinearLayout, label: String, values: List<String>, key: String, default: String): Spinner {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(2))
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SiteRoutingActivity, android.R.layout.simple_spinner_dropdown_item, values)
            val saved = prefs.getString(key, default) ?: default
            setSelection(values.indexOf(saved).takeIf { it >= 0 } ?: values.indexOf(default).coerceAtLeast(0))
        }
        parent.addView(spinner, matchWidth())
        return spinner
    }

    private fun addSettingInput(parent: LinearLayout, label: String, key: String, default: String): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(2))
        })
        val input = EditText(this).apply {
            setText(prefs.getString(key, default) ?: default)
            textSize = 13f; setSingleLine(label != "DNS Hosts — домен = IP, по одной записи на строку")
            if (label.startsWith("DNS Hosts")) { minLines = 2; gravity = Gravity.TOP }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = fieldBackground()
        }
        settingInputs[key] = input
        parent.addView(input, matchWidth())
        return input
    }

    private fun addRuleInput(parent: LinearLayout, label: String, hint: String, key: String) {
        parent.addView(TextView(this).apply {
            text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(12), dp(2), dp(4))
        })
        val input = EditText(this).apply {
            setText(prefs.getString(key, "") ?: "")
            this.hint = hint; textSize = 13f; minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = fieldBackground()
        }
        ruleInputs[key] = input
        parent.addView(input, matchWidth())
    }

    private fun fieldBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xFFF4F5F6.toInt()); cornerRadius = dp(10).toFloat()
        setStroke(dp(1), 0xFFE2E3E7.toInt())
    }

    private fun downloadGeoFile(key: String, fileName: String) {
        val address = settingInputs[key]?.text?.toString()?.trim().orEmpty()
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            Toast.makeText(this, "Укажи корректный HTTP(S) URL", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Загружаю $fileName…", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JPN-Android")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                val temp = File(filesDir, "$fileName.tmp")
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 100L * 1024 * 1024) throw IllegalStateException("Файл больше 100 МБ")
                            output.write(buffer, 0, count)
                        }
                        if (total < 1024) throw IllegalStateException("Файл слишком маленький")
                    }
                }
                val target = File(filesDir, fileName)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IllegalStateException("Не удалось заменить файл")
                }
                runOnUiThread {
                    prefs.edit().putBoolean("site_routing_restart_pending", true).apply()
                    Toast.makeText(this, "$fileName загружен. База будет применена при переподключении VPN.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                File(filesDir, "$fileName.tmp").delete()
                runOnUiThread {
                    Toast.makeText(this, "Ошибка загрузки $fileName: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } finally { connection?.disconnect() }
        }.start()
    }

    private fun snapshot(): String = buildString {
        append(enabledSwitch.isChecked).append('|').append(globalProxySwitch.isChecked).append('|')
        append(fakeDnsSwitch.isChecked).append('|').append(strategySpinner.selectedItem).append('|')
        append(routeOrderSpinner.selectedItem).append('|').append(remoteTypeSpinner.selectedItem).append('|')
        append(domesticTypeSpinner.selectedItem).append('|')
        settingInputs.forEach { (key, view) -> append(key).append('=').append(view.text).append('|') }
        ruleInputs.forEach { (key, view) -> append(key).append('=').append(view.text).append('|') }
    }

    private fun saveSettings() {
        val changed = snapshot() != initialSnapshot
        val edit = prefs.edit()
            .putBoolean(KEY_ENABLED, enabledSwitch.isChecked)
            .putBoolean(KEY_GLOBAL_PROXY, globalProxySwitch.isChecked)
            .putBoolean(KEY_FAKE_DNS, fakeDnsSwitch.isChecked)
            .putString(KEY_STRATEGY, strategySpinner.selectedItem.toString())
            .putString(KEY_ROUTE_ORDER, routeOrderSpinner.selectedItem.toString())
            .putString(KEY_REMOTE_DNS_TYPE, remoteTypeSpinner.selectedItem.toString())
            .putString(KEY_DOMESTIC_DNS_TYPE, domesticTypeSpinner.selectedItem.toString())
        settingInputs.forEach { (key, view) -> edit.putString(key, view.text.toString().trim()) }
        ruleInputs.forEach { (key, view) -> edit.putString(key, view.text.toString()) }
        edit.apply()
        if (changed && prefs.getString("vpn_state", "disconnected") == "connected") {
            prefs.edit().putBoolean("site_routing_restart_pending", true).apply()
        }
    }

    override fun onPause() {
        if (::enabledSwitch.isInitialized) saveSettings()
        super.onPause()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
