package com.jpn.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
        private const val KEY_GEOSITE = "site_direct_geosite"
        private const val KEY_GEOIP = "site_direct_geoip"
        private const val KEY_DOMAINS = "site_manual_domains"
        private const val KEY_IPS = "site_manual_ips"
        private const val KEY_GEOSITE_URL = "site_geosite_url"
        private const val KEY_GEOIP_URL = "site_geoip_url"
        private const val GEO_DEFAULT = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var enabledSwitch: Switch
    private lateinit var geositeSummary: TextView
    private lateinit var geoipSummary: TextView
    private lateinit var domainsContainer: LinearLayout
    private lateinit var ipsContainer: LinearLayout
    private lateinit var geositeUrl: EditText
    private lateinit var geoipUrl: EditText
    private var initialSnapshot = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyDirectRules()
        buildScreen()
        initialSnapshot = snapshot()
    }

    // Keep only the old Direct rules; old Proxy and Block rules have no meaning
    // in the new allow-list model. Geo rules are moved to their dedicated lists.
    private fun migrateLegacyDirectRules() {
        if (prefs.getBoolean("site_bypass_migrated_v2", false)) return
        val domains = linkedSetOf<String>()
        val ips = linkedSetOf<String>()
        val geosites = linkedSetOf<String>()
        val geoips = linkedSetOf<String>()
        fun collect(key: String) {
            prefs.getString(key, "").orEmpty().lineSequence()
                .map { it.substringBefore("#").trim() }
                .filter { it.isNotBlank() }
                .forEach { entry ->
                    when {
                        entry.startsWith("geosite:", true) -> geosites.add(entry.substringAfter(":").trim())
                        entry.startsWith("geoip:", true) -> geoips.add(entry.substringAfter(":").trim())
                        entry.matches(Regex("""^(?:\\d{1,3}\\.){3}\\d{1,3}(?:/\\d{1,2})?$""")) ||
                            entry.contains(":") -> ips.add(entry)
                        else -> domains.add(entry.removePrefix("domain:"))
                    }
                }
        }
        collect("site_direct_sites")
        collect("site_direct_ip")
        prefs.edit()
            .putString(KEY_DOMAINS, (domains + readSaved(KEY_DOMAINS)).distinct().joinToString("\n"))
            .putString(KEY_IPS, (ips + readSaved(KEY_IPS)).distinct().joinToString("\n"))
            .putString(KEY_GEOSITE, (geosites + readSaved(KEY_GEOSITE)).distinct().joinToString("\n"))
            .putString(KEY_GEOIP, (geoips + readSaved(KEY_GEOIP)).distinct().joinToString("\n"))
            .putBoolean("site_bypass_migrated_v2", true)
            .apply()
    }

    private fun readSaved(key: String): List<String> = prefs.getString(key, "").orEmpty()
        .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹ Назад"; textSize = 15f; setTextColor(0xFF514B83.toInt())
            setPadding(dp(2), dp(10), dp(12), dp(10)); setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"; textSize = 19f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this).apply {
            text = "Вкл."
            isChecked = prefs.getBoolean(KEY_ENABLED, false)
        }
        header.addView(enabledSwitch)
        root.addView(header, matchWidth())
        root.addView(TextView(this).apply {
            text = "Весь трафик идёт через VPN. Только выбранные ниже домены, GeoSite и IP-адреса будут обходить VPN."
            textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(4), 0, dp(10))
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        sectionTitle(body, "GeoSite — категории доменов")
        geositeSummary = summaryText()
        body.addView(geositeSummary, matchWidth())
        body.addView(Button(this).apply {
            text = "Выбрать категории GeoSite"
            setOnClickListener { showCategoryPicker("GeoSite", "geosite.dat", KEY_GEOSITE, geositeSummary) }
        }, matchWidth())

        sectionTitle(body, "GeoIP — категории IP-адресов")
        geoipSummary = summaryText()
        body.addView(geoipSummary, matchWidth())
        body.addView(Button(this).apply {
            text = "Выбрать категории GeoIP"
            setOnClickListener { showCategoryPicker("GeoIP", "geoip.dat", KEY_GEOIP, geoipSummary) }
        }, matchWidth())

        sectionTitle(body, "Ручные домены")
        body.addView(TextView(this).apply {
            text = "Например: example.com, domain:example.com или full:example.com — по одному правилу на строку."
            textSize = 12f; setTextColor(0xFF777777.toInt())
        })
        domainsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(domainsContainer, matchWidth())
        body.addView(Button(this).apply {
            text = "+ Добавить домен"; setOnClickListener { showManualEditor(KEY_DOMAINS, "домен", null) }
        }, matchWidth())

        sectionTitle(body, "Ручные IP и подсети")
        body.addView(TextView(this).apply {
            text = "IPv4, IPv6 и CIDR. Например: 192.168.1.10 или 192.168.0.0/16."
            textSize = 12f; setTextColor(0xFF777777.toInt())
        })
        ipsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ipsContainer, matchWidth())
        body.addView(Button(this).apply {
            text = "+ Добавить IP / подсеть"; setOnClickListener { showManualEditor(KEY_IPS, "IP-адрес или подсеть", null) }
        }, matchWidth())

        sectionTitle(body, "Базы GeoSite и GeoIP")
        geositeUrl = urlInput(body, "URL GeoSite", KEY_GEOSITE_URL, GEO_DEFAULT + "geosite.dat")
        geoipUrl = urlInput(body, "URL GeoIP", KEY_GEOIP_URL, GEO_DEFAULT + "geoip.dat")
        val downloads = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downloads.addView(Button(this).apply {
            text = "Обновить GeoSite"; setOnClickListener { downloadGeoFile(geositeUrl.text.toString(), "geosite.dat") }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        downloads.addView(Button(this).apply {
            text = "Обновить GeoIP"; setOnClickListener { downloadGeoFile(geoipUrl.text.toString(), "geoip.dat") }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        body.addView(downloads, matchWidth())

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Сохранить и вернуться"; minHeight = dp(48)
            setOnClickListener { saveSettings(); finish() }
        }, matchWidth())
        setContentView(root)
        renderManualRules(KEY_DOMAINS, domainsContainer, "домен")
        renderManualRules(KEY_IPS, ipsContainer, "IP-адрес или подсеть")
        refreshGeoSummaries()
    }

    private fun summaryText() = TextView(this).apply {
        textSize = 13f; setTextColor(0xFF555555.toInt())
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = fieldBackground()
    }

    private fun refreshGeoSummaries() {
        if (!::geositeSummary.isInitialized) return
        fun text(key: String, fileName: String): String {
            val selected = readSaved(key)
            val available = readCategories(File(filesDir, fileName)).size
            return "Выбрано: ${selected.size}" + if (available > 0) " · В базе: $available" else " · База не загружена"
        }
        geositeSummary.text = text(KEY_GEOSITE, "geosite.dat")
        geoipSummary.text = text(KEY_GEOIP, "geoip.dat")
    }

    private fun showCategoryPicker(title: String, fileName: String, key: String, summary: TextView) {
        val database = File(filesDir, fileName)
        val categories = readCategories(database)
        if (categories.isEmpty()) {
            AlertDialog.Builder(this).setTitle("$title не загружена")
                .setMessage("Сначала нажми «Обновить $title», чтобы загрузить и проверить базу категорий.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        val selected = readSaved(key).toMutableSet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val search = EditText(this).apply {
            hint = "Поиск категории"; setSingleLine(true)
        }
        content.addView(search, matchWidth())
        val scroll = ScrollView(this)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(choices)
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        fun render(filter: String) {
            choices.removeAllViews()
            categories.filter { it.contains(filter.trim(), true) }.forEach { category ->
                choices.addView(CheckBox(this).apply {
                    text = category
                    isChecked = selected.contains(category)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(category) else selected.remove(category)
                    }
                }, matchWidth())
            }
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        AlertDialog.Builder(this).setTitle("Категории $title")
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Очистить", null)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString(key, selected.sorted().joinToString("\n")).apply()
                summary.text = "Выбрано: ${selected.size} · В базе: ${categories.size}"
                markRestartPending()
            }.create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        selected.clear(); render(search.text.toString())
                    }
                }
                dialog.show()
            }
    }

    private fun renderManualRules(key: String, container: LinearLayout, label: String) {
        container.removeAllViews()
        val entries = readSaved(key)
        if (entries.isEmpty()) container.addView(TextView(this).apply {
            text = "Правил пока нет"; textSize = 13f; setTextColor(0xFF888888.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
        })
        entries.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(3), dp(4), dp(3)); background = fieldBackground()
            }
            row.addView(TextView(this).apply {
                text = value; textSize = 14f; setTextColor(0xFF202124.toInt())
                setPadding(dp(2), dp(7), dp(4), dp(7))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "✎"; textSize = 19f; setTextColor(0xFF514B83.toInt())
                setPadding(dp(9), dp(5), dp(9), dp(5))
                setOnClickListener { showManualEditor(key, label, value) }
            })
            row.addView(TextView(this).apply {
                text = "×"; textSize = 22f; setTextColor(0xFFB3261E.toInt())
                setPadding(dp(9), dp(5), dp(7), dp(5))
                setOnClickListener {
                    val updated = readSaved(key).toMutableList()
                    if (index in updated.indices) updated.removeAt(index)
                    saveList(key, updated)
                    renderManualRules(key, container, label)
                }
            })
            container.addView(row, matchWidth())
        }
    }

    private fun showManualEditor(key: String, label: String, current: String?) {
        val input = EditText(this).apply {
            setSingleLine(true); textSize = 15f
            hint = if (key == KEY_DOMAINS) "example.com" else "192.168.1.1 или 192.168.0.0/16"
            if (current != null) setText(current)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(if (current == null) "Добавить: $label" else "Изменить: $label")
            .setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    val updated = readSaved(key).toMutableList()
                    if (current != null) {
                        val index = updated.indexOf(current)
                        if (index >= 0) updated[index] = value
                    } else updated.add(value)
                    saveList(key, updated.distinct())
                    renderManualRules(key, if (key == KEY_DOMAINS) domainsContainer else ipsContainer, label)
                }
            }.show()
    }

    private fun saveList(key: String, values: List<String>) {
        prefs.edit().putString(key, values.joinToString("\n")).apply()
        markRestartPending()
    }

    private fun markRestartPending() {
        if (prefs.getString("vpn_state", "disconnected") == "connected") {
            prefs.edit().putBoolean("site_routing_restart_pending", true).apply()
        }
    }

    private fun urlInput(parent: LinearLayout, label: String, key: String, default: String): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(0xFF666666.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(2))
        })
        return EditText(this).apply {
            setText(prefs.getString(key, default) ?: default)
            textSize = 12f; setSingleLine(true); setPadding(dp(10), dp(8), dp(10), dp(8))
            background = fieldBackground()
            parent.addView(this, matchWidth())
        }
    }

    private fun downloadGeoFile(address: String, fileName: String) {
        val url = address.trim()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            Toast.makeText(this, "Укажи корректный HTTP(S) URL", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Загружаю $fileName…", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JPN-Android")
                }
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
                val categoryCount = readCategories(temp).size
                if (categoryCount == 0) throw IllegalStateException("Не удалось прочитать категории из Geo-базы")
                val target = File(filesDir, fileName)
                val backup = File(filesDir, "$fileName.bak")
                backup.delete()
                if (target.exists() && !target.renameTo(backup)) throw IllegalStateException("Не удалось сохранить старую базу")
                if (!temp.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    throw IllegalStateException("Не удалось заменить базу")
                }
                backup.delete()
                runOnUiThread {
                    markRestartPending()
                    refreshGeoSummaries()
                    Toast.makeText(this, "$fileName обновлена: категорий $categoryCount. Применится после переподключения VPN.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                File(filesDir, "$fileName.tmp").delete()
                runOnUiThread { Toast.makeText(this, "Ошибка загрузки $fileName: ${error.message}", Toast.LENGTH_LONG).show() }
            } finally { connection?.disconnect() }
        }.start()
    }

    // GeoSiteList and GeoIPList are protobuf messages. Each outer field #1 is
    // a record; field #1 inside that record is its category/country code.
    private fun readCategories(file: File): List<String> {
        if (!file.isFile || file.length() < 8) return emptyList()
        return runCatching {
            val bytes = file.readBytes()
            val result = linkedSetOf<String>()
            var offset = 0
            while (offset < bytes.size) {
                val (tag, afterTag) = readVarint(bytes, offset)
                offset = afterTag
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> offset = readVarint(bytes, offset).second
                    1 -> offset += 8
                    2 -> {
                        val (length, afterLength) = readVarint(bytes, offset)
                        offset = afterLength
                        val size = length.toInt()
                        if (size < 0 || offset + size > bytes.size) throw IllegalArgumentException("bad protobuf")
                        if (field == 1) {
                            val name = firstStringField(bytes, offset, size)
                            if (name != null && name.matches(Regex("[A-Za-z0-9_-]{1,80}"))) result.add(name)
                        }
                        offset += size
                    }
                    5 -> offset += 4
                    else -> throw IllegalArgumentException("unknown protobuf wire type")
                }
                if (offset > bytes.size) throw IllegalArgumentException("truncated protobuf")
            }
            result.sorted()
        }.getOrDefault(emptyList())
    }

    private fun firstStringField(bytes: ByteArray, start: Int, size: Int): String? {
        var offset = start
        val end = start + size
        while (offset < end) {
            val (tag, next) = readVarint(bytes, offset); offset = next
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> offset = readVarint(bytes, offset).second
                1 -> offset += 8
                2 -> {
                    val (length, afterLength) = readVarint(bytes, offset); offset = afterLength
                    val count = length.toInt()
                    if (count < 0 || offset + count > end) return null
                    if (field == 1) return String(bytes, offset, count, Charsets.UTF_8)
                    offset += count
                }
                5 -> offset += 4
                else -> return null
            }
            if (offset > end) return null
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < 64) {
            val b = bytes[offset++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return value to offset
            shift += 7
        }
        throw IllegalArgumentException("invalid protobuf varint")
    }

    private fun snapshot(): String = buildString {
        append(enabledSwitch.isChecked).append('|')
        append(geositeUrl.text).append('|').append(geoipUrl.text).append('|')
        append(readSaved(KEY_GEOSITE).joinToString(",")).append('|')
        append(readSaved(KEY_GEOIP).joinToString(",")).append('|')
        append(readSaved(KEY_DOMAINS).joinToString(",")).append('|')
        append(readSaved(KEY_IPS).joinToString(","))
    }

    private fun saveSettings() {
        val changed = snapshot() != initialSnapshot
        prefs.edit().putBoolean(KEY_ENABLED, enabledSwitch.isChecked)
            .putString(KEY_GEOSITE_URL, geositeUrl.text.toString().trim())
            .putString(KEY_GEOIP_URL, geoipUrl.text.toString().trim())
            .apply()
        if (changed) markRestartPending()
    }

    override fun onPause() {
        if (::enabledSwitch.isInitialized) saveSettings()
        super.onPause()
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(14), dp(2), dp(5))
        })
    }

    private fun fieldBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xFFF4F5F6.toInt()); cornerRadius = dp(10).toFloat()
        setStroke(dp(1), 0xFFE2E3E7.toInt())
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
