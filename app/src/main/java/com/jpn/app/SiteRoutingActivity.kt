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
        private const val KEY_PROXY_SITES = "site_proxy_sites"
        private const val KEY_PROXY_IP = "site_proxy_ip"
        private const val KEY_DIRECT_SITES = "site_direct_sites"
        private const val KEY_DIRECT_IP = "site_direct_ip"
        private const val KEY_BLOCK_SITES = "site_block_sites"
        private const val KEY_BLOCK_IP = "site_block_ip"
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
    private lateinit var rulesContainer: LinearLayout
    private var selectedRuleTab = "Proxy"
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
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(10) + insets.systemWindowInsetTop,
                dp(18), dp(10) + insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹ Назад"; textSize = 15f; setTextColor(0xFF514B83.toInt())
            setPadding(dp(2), dp(10), dp(12), dp(10)); setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"; textSize = 20f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this).apply {
            text = "Вкл."; isChecked = prefs.getBoolean(KEY_ENABLED, false)
        }
        header.addView(enabledSwitch)
        root.addView(header, matchWidth())
        root.addView(TextView(this).apply {
            text = "Выбери, куда направлять трафик сайтов и IP-адресов."
            textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(4), 0, dp(10))
        })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        listOf("Proxy", "Direct", "Block").forEach { tab ->
            tabs.addView(Button(this).apply {
                text = tab; isAllCaps = false
                setOnClickListener { selectedRuleTab = tab; renderRuleTab() }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        }
        body.addView(tabs, matchWidth())
        rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rulesContainer, matchWidth())
        body.addView(Button(this).apply {
            text = "+ Добавить правило"; isAllCaps = false
            setOnClickListener { showRuleEditor(selectedRuleTab, null, -1) }
        }, matchWidth())
        body.addView(TextView(this).apply {
            text = "Поддерживаются domain:, full:, regexp:, geosite:, geoip: и IP/CIDR."
            textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(dp(2), dp(5), dp(2), dp(12))
        })
        sectionTitle(body, "Основные параметры")
        globalProxySwitch = addSwitch(body, "Global Proxy — весь трафик через прокси по умолчанию", KEY_GLOBAL_PROXY, true)
        strategySpinner = addSpinner(body, "Domain Strategy", listOf("AsIs", "IPIfNonMatch", "IPOnDemand"), KEY_STRATEGY, "IPIfNonMatch")
        routeOrderSpinner = addSpinner(body, "Порядок обработки правил", listOf(
            "Block → Direct → Proxy", "Direct → Block → Proxy", "Proxy → Direct → Block",
            "Proxy → Block → Direct", "Direct → Proxy → Block", "Block → Proxy → Direct"
        ), KEY_ROUTE_ORDER, "Block → Direct → Proxy")
        sectionTitle(body, "DNS")
        remoteTypeSpinner = addSpinner(body, "Remote DNS type", listOf("DoH", "DoU"), KEY_REMOTE_DNS_TYPE, "DoH")
        addSettingInput(body, "Remote DNS address", KEY_REMOTE_DNS, "https://cloudflare-dns.com/dns-query")
        addSettingInput(body, "Remote DNS IP (для DoU)", KEY_REMOTE_DNS_IP, "1.1.1.1")
        domesticTypeSpinner = addSpinner(body, "Domestic DNS type", listOf("DoH", "DoU"), KEY_DOMESTIC_DNS_TYPE, "DoH")
        addSettingInput(body, "Domestic DNS address", KEY_DOMESTIC_DNS, "https://dns.google/dns-query")
        addSettingInput(body, "Domestic DNS IP (для DoU)", KEY_DOMESTIC_DNS_IP, "8.8.8.8")
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
        sectionTitle(body, "Дополнительно")
        fakeDnsSwitch = addSwitch(body, "Fake DNS", KEY_FAKE_DNS, false)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        footer.addView(Button(this).apply {
            text = "Сохранить и вернуться"; minHeight = dp(48)
            setOnClickListener { saveSettings(); finish() }
        }, matchWidth())
        root.addView(footer, matchWidth())
        setContentView(root)
        renderRuleTab()
    }

    private fun renderRuleTab() {
        if (!::rulesContainer.isInitialized) return
        rulesContainer.removeAllViews()
        val keys = when (selectedRuleTab) {
            "Proxy" -> KEY_PROXY_SITES to KEY_PROXY_IP
            "Direct" -> KEY_DIRECT_SITES to KEY_DIRECT_IP
            else -> KEY_BLOCK_SITES to KEY_BLOCK_IP
        }
        addRuleGroup("Сайты и домены", keys.first)
        addRuleGroup("IP-адреса и подсети", keys.second)
    }

    private fun addRuleGroup(title: String, key: String) {
        rulesContainer.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(10), dp(2), dp(4))
        })
        val entries = getRules(key)
        if (entries.isEmpty()) rulesContainer.addView(TextView(this).apply {
            text = "Правил пока нет"; textSize = 13f; setTextColor(0xFF888888.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        entries.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(4), dp(5)); background = fieldBackground()
            }
            row.addView(TextView(this).apply {
                text = value; textSize = 14f; setTextColor(0xFF202124.toInt())
                setPadding(dp(2), dp(5), dp(4), dp(5))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "✎"; textSize = 20f; setTextColor(0xFF514B83.toInt())
                gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { showRuleEditor(selectedRuleTab, key, index, value) }
            })
            row.addView(TextView(this).apply {
                text = "×"; textSize = 22f; setTextColor(0xFFB3261E.toInt())
                gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val updated = getRules(key).toMutableList()
                    if (index in updated.indices) updated.removeAt(index)
                    putRules(key, updated); renderRuleTab()
                }
            })
            rulesContainer.addView(row, matchWidth())
        }
    }

    private fun showRuleEditor(tab: String, key: String?, index: Int, current: String? = null) {
        val targetKey = key ?: when (tab) {
            "Proxy" -> KEY_PROXY_SITES
            "Direct" -> KEY_DIRECT_SITES
            else -> KEY_BLOCK_SITES
        }
        val input = EditText(this).apply {
            setSingleLine(true); textSize = 15f
            hint = "domain:example.com или geosite:category"
            if (current != null) setText(current)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "Изменить правило" else "Новое правило")
            .setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    val updated = getRules(targetKey).toMutableList()
                    if (index >= 0 && index < updated.size) updated[index] = value else updated.add(value)
                    putRules(targetKey, updated); selectedRuleTab = tab; renderRuleTab()
                }
            }.show()
    }

    private fun getRules(key: String): List<String> =
        (prefs.getString(key, "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun putRules(key: String, rules: List<String>) {
        prefs.edit().putString(key, rules.joinToString("\n")).apply()
        if (prefs.getString("vpn_state", "disconnected") == "connected") {
            prefs.edit().putBoolean("site_routing_restart_pending", true).apply()
        }
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
                val backup = File(filesDir, "$fileName.bak")
                backup.delete()
                if (target.exists() && !target.renameTo(backup)) {
                    throw IllegalStateException("Не удалось сохранить предыдущую базу")
                }
                if (!temp.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    throw IllegalStateException("Не удалось заменить файл")
                }
                backup.delete()
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
