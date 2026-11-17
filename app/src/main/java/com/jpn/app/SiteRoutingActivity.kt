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
    private lateinit var entriesContainer: LinearLayout
    private lateinit var addButton: Button
    private lateinit var routingBody: LinearLayout
    private lateinit var routingScroll: ScrollView
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
                        entry.matches(Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?:/\d{1,2})?$""")) ||
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(10) + insets.systemWindowInsetTop, dp(18), dp(10) + insets.systemWindowInsetBottom); insets
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "‹ Назад"; textSize = 15f; setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(10), dp(12), dp(10)); setOnClickListener { finish() } })
        header.addView(TextView(this).apply { text = "Маршрутизация сайтов"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF202124.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, matchWidth())
        val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(12), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFF4F5F6.toInt()); cornerRadius = dp(14).toFloat() } }
        toggleRow.addView(TextView(this).apply { text = "Включить маршрутизацию сайтов"; textSize = 15f; setTextColor(0xFF202124.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this).apply { isChecked = prefs.getBoolean(KEY_ENABLED, true); contentDescription = "Маршрутизация сайтов включена" }
        toggleRow.addView(enabledSwitch)
        root.addView(toggleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        routingScroll = ScrollView(this)
        routingBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        entriesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        routingBody.addView(entriesContainer, matchWidth()); routingScroll.addView(routingBody)
        root.addView(routingScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton = Button(this).apply { text = "Добавить"; minHeight = dp(50); setOnClickListener { showAddMenu() } }
        root.addView(addButton, matchWidth())
        root.addView(Button(this).apply { text = "Сохранить и вернуться"; setOnClickListener { saveSettings(); finish() } }, matchWidth())
        setContentView(root); renderUnifiedEntries()
        enabledSwitch.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_ENABLED, checked).apply(); markRestartPending(); setRoutingOptionsEnabled(checked) }
        setRoutingOptionsEnabled(enabledSwitch.isChecked); initialSnapshot = snapshot()
    }

    private fun showAddMenu() {
        AlertDialog.Builder(this).setTitle("Что добавить?").setItems(arrayOf("GeoIP", "GeoSite", "IP", "Домен")) { _, which ->
            when (which) { 0 -> showCategoryPicker("GeoIP", "geoip.dat", KEY_GEOIP); 1 -> showCategoryPicker("GeoSite", "geosite.dat", KEY_GEOSITE); 2 -> showManualEditor(KEY_IPS, "IP-адрес или подсеть", null); 3 -> showManualEditor(KEY_DOMAINS, "домен", null) }
        }.show()
    }

    private fun renderUnifiedEntries() {
        if (!::entriesContainer.isInitialized) return
        entriesContainer.removeAllViews()
        val groups = listOf(
            Triple("GeoIP", KEY_GEOIP, ""),
            Triple("GeoSite", KEY_GEOSITE, ""),
            Triple("IP", KEY_IPS, "IP-адрес или подсеть"),
            Triple("Домен", KEY_DOMAINS, "домен")
        )
        val hasEntries = groups.any { readSaved(it.second).isNotEmpty() }
        if (!hasEntries) {
            entriesContainer.addView(TextView(this).apply {
                text = "Список пока пуст. Нажми «Добавить», чтобы создать первое правило."
                textSize = 14f; setTextColor(0xFF888888.toInt()); gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            }, matchWidth())
            return
        }

        groups.forEach { (type, key, editLabel) ->
            val values = readSaved(key)
            if (values.isNotEmpty()) {
                sectionTitle(entriesContainer, type)
                values.forEach { value ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(13), dp(11), dp(13), dp(11))
                        background = fieldBackground()
                        isLongClickable = true
                        setOnLongClickListener {
                            AlertDialog.Builder(this@SiteRoutingActivity)
                                .setTitle("Удалить запись?")
                                .setMessage("$type: $value")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить") { _, _ ->
                                    val updated = readSaved(key).toMutableList()
                                    updated.remove(value)
                                    saveList(key, updated)
                                    renderUnifiedEntries()
                                }.show()
                            true
                        }
                    }
                    row.addView(TextView(this@SiteRoutingActivity).apply {
                        text = value; textSize = 14f; setTextColor(0xFF202124.toInt())
                    })
                    if (editLabel.isNotEmpty()) {
                        row.setOnClickListener { showManualEditor(key, editLabel, value) }
                    }
                    entriesContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                }
            }
        }
    }

    private fun setRoutingOptionsEnabled(enabled: Boolean) {
        fun update(view: View) {
            view.isEnabled = enabled
            if (view is LinearLayout) {
                for (index in 0 until view.childCount) update(view.getChildAt(index))
            }
        }
        update(routingBody)
        routingScroll.isEnabled = enabled
        addButton.isEnabled = enabled
        routingBody.alpha = if (enabled) 1f else 0.45f
        addButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun showCategoryPicker(title: String, fileName: String, key: String) {
        val categories = readCategories(File(filesDir, fileName))
        if (categories.isEmpty()) { AlertDialog.Builder(this).setTitle("База $title не загружена").setMessage("Сначала обнови базы GeoSite и GeoIP.").setNegativeButton("Позже", null).setPositiveButton("Обновить базы") { _, _ -> downloadGeoFiles() }.show(); return }
        val selected = readSaved(key).toMutableSet()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val search = EditText(this).apply { hint = "Поиск категории"; setSingleLine(true) }; content.addView(search, matchWidth())
        val scroll = ScrollView(this); val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(choices); content.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        fun render(filter: String) { choices.removeAllViews(); categories.filter { it.contains(filter.trim(), true) }.forEach { category -> choices.addView(CheckBox(this).apply { text = category; isChecked = selected.contains(category); setOnCheckedChangeListener { _, checked -> if (checked) selected.add(category) else selected.remove(category) } }, matchWidth()) } }
        render("")
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }; override fun afterTextChanged(s: Editable?) {} })
        AlertDialog.Builder(this).setTitle("Категории $title").setView(content).setNegativeButton("Отмена", null).setNeutralButton("Очистить", null).setPositiveButton("Сохранить") { _, _ -> saveList(key, selected.sorted()); renderUnifiedEntries() }.create().also { dialog ->
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { selected.clear(); render(search.text.toString()) } }; dialog.show()
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
                    renderUnifiedEntries()
                }
            }.show()
    }

    private fun saveList(key: String, values: List<String>) {
        prefs.edit().putString(key, values.joinToString("\n")).apply()
        markRestartPending()
        if (::entriesContainer.isInitialized) renderUnifiedEntries()
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

    private fun downloadGeoFiles() {
        val geositeAddress = prefs.getString(KEY_GEOSITE_URL, GEO_DEFAULT + "geosite.dat") ?: GEO_DEFAULT + "geosite.dat"
        val geoipAddress = prefs.getString(KEY_GEOIP_URL, GEO_DEFAULT + "geoip.dat") ?: GEO_DEFAULT + "geoip.dat"
        Toast.makeText(this, "Загружаю GeoSite и GeoIP…", Toast.LENGTH_SHORT).show()
        Thread {
            val results = listOf("GeoSite" to runCatching { downloadGeoFileNow(geositeAddress, "geosite.dat") }, "GeoIP" to runCatching { downloadGeoFileNow(geoipAddress, "geoip.dat") })
            val succeeded = results.filter { it.second.isSuccess }; val failed = results.filter { it.second.isFailure }
            runOnUiThread {
                if (succeeded.isNotEmpty()) markRestartPending()
                val details = (succeeded.map { it.first + ": обновлена (" + it.second.getOrThrow() + " категорий)" } + failed.map { it.first + ": ошибка — " + (it.second.exceptionOrNull()?.message ?: "неизвестная ошибка") }).joinToString("\n")
                AlertDialog.Builder(this).setTitle("Обновление баз").setMessage(if (failed.isEmpty()) "Обе базы успешно обновлены. Изменения применятся после переподключения VPN." else details).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun downloadGeoFileNow(address: String, fileName: String): Int {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JPN-Android")
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
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
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Не удалось сохранить старую базу")
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IllegalStateException("Не удалось заменить базу")
            }
            backup.delete()
            return categoryCount
        } catch (error: Exception) {
            File(filesDir, "$fileName.tmp").delete()
            throw error
        } finally {
            connection?.disconnect()
        }
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
        append(enabledSwitch.isChecked).append("|")
        append(readSaved(KEY_GEOSITE).joinToString(",")).append("|")
        append(readSaved(KEY_GEOIP).joinToString(",")).append("|")
        append(readSaved(KEY_DOMAINS).joinToString(",")).append("|")
        append(readSaved(KEY_IPS).joinToString(","))
    }

    private fun saveSettings() {
        val changed = snapshot() != initialSnapshot
        prefs.edit().putBoolean(KEY_ENABLED, enabledSwitch.isChecked).apply()
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
