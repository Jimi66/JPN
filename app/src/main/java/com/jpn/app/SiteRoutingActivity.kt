package com.jpn.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class SiteRoutingActivity : Activity() {
    companion object {
        private const val PREFS = "jpn_settings"
        private const val KEY_ENABLED = "site_routing_enabled"
        private const val KEY_RULES = "site_routing_rules"
        private const val KEY_STRATEGY = "site_domain_strategy"
        private const val KEY_PROXY = "site_proxy_rules"
        private const val KEY_DIRECT = "site_direct_rules"
        private const val KEY_BLOCK = "site_block_rules"
    }

    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var enabledSwitch: Switch
    private lateinit var strategySpinner: Spinner
    private lateinit var proxyInput: EditText
    private lateinit var directInput: EditText
    private lateinit var blockInput: EditText
    private var initialEnabled = false
    private var initialStrategy = "IPIfNonMatch"
    private var initialProxy = ""
    private var initialDirect = ""
    private var initialBlock = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyRules()
        buildScreen()
        initialEnabled = enabledSwitch.isChecked
        initialStrategy = strategySpinner.selectedItem?.toString() ?: "IPIfNonMatch"
        initialProxy = proxyInput.text.toString()
        initialDirect = directInput.text.toString()
        initialBlock = blockInput.text.toString()
    }

    private fun migrateLegacyRules() {
        if (preferences.contains(KEY_PROXY) || preferences.contains(KEY_DIRECT) || preferences.contains(KEY_BLOCK)) return
        val legacy = runCatching { JSONArray(preferences.getString(KEY_RULES, "[]")) }.getOrDefault(JSONArray())
        val grouped = mutableMapOf("proxy" to mutableListOf<String>(), "direct" to mutableListOf<String>(), "block" to mutableListOf<String>())
        for (i in 0 until legacy.length()) {
            val rule = legacy.optJSONObject(i) ?: continue
            val value = rule.optString("value").trim()
            if (value.isNotBlank()) grouped[rule.optString("action", "proxy")]?.add(value)
        }
        preferences.edit()
            .putString(KEY_PROXY, grouped["proxy"]!!.joinToString("\n"))
            .putString(KEY_DIRECT, grouped["direct"]!!.joinToString("\n"))
            .putString(KEY_BLOCK, grouped["block"]!!.joinToString("\n"))
            .apply()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(12) + insets.systemWindowInsetTop, dp(18), dp(12) + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "‹   Назад"; textSize = 18f; setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12)); setOnClickListener { finish() }
        })
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"; textSize = 21f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        enabledSwitch = Switch(this).apply {
            isChecked = preferences.getBoolean(KEY_ENABLED, false)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(KEY_ENABLED, checked).apply() }
        }
        titleRow.addView(enabledSwitch)
        root.addView(titleRow, matchWidth().apply { bottomMargin = dp(5) })
        root.addView(TextView(this).apply {
            text = "Добавляй правила в нужный раздел. Каждая строка — отдельное правило; строки с # считаются комментариями. Правила проверяются в порядке Block → Direct → Proxy."
            textSize = 14f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(5), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Domain Strategy"; textSize = 14f; setTextColor(0xFF555555.toInt())
            setPadding(dp(2), dp(2), dp(2), dp(4))
        })
        strategySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SiteRoutingActivity, android.R.layout.simple_spinner_dropdown_item, listOf("IPIfNonMatch", "AsIs"))
            setSelection(if (preferences.getString(KEY_STRATEGY, "IPIfNonMatch") == "AsIs") 1 else 0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString(KEY_STRATEGY, if (position == 1) "AsIs" else "IPIfNonMatch").apply()
                }
            }
        }
        root.addView(strategySpinner, matchWidth().apply { bottomMargin = dp(8) })

        val content = android.widget.ScrollView(this)
        val sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blockInput = addRuleSection(sections, "Block", "geosite:category-ads-all\n# example.com\ngeoip:xx", KEY_BLOCK)
        directInput = addRuleSection(sections, "Direct", "geosite:category-private\n192.168.0.0/16\n10.0.0.0/8", KEY_DIRECT)
        proxyInput = addRuleSection(sections, "Proxy", "geosite:geolocation-!ru\ngeoip:us", KEY_PROXY)
        content.addView(sections)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Поддерживаются domain:, full:, regexp:, geosite:, geoip:, IP и CIDR. Изменения применяются при следующем запуске VPN."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFF777777.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        setContentView(root)
    }

    private fun addRuleSection(parent: LinearLayout, title: String, hint: String, key: String): EditText {
        parent.addView(TextView(this).apply {
            text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF514B83.toInt()); setPadding(dp(2), dp(12), dp(2), dp(5))
        })
        val input = EditText(this).apply {
            setText(preferences.getString(key, "")); this.hint = hint
            textSize = 14f; minLines = 3; gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt()); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFE2E3E7.toInt())
            }
        }
        parent.addView(input, matchWidth().apply { bottomMargin = dp(4) })
        return input
    }

    override fun onPause() {
        if (::proxyInput.isInitialized) {
            val changed = enabledSwitch.isChecked != initialEnabled ||
                (strategySpinner.selectedItem?.toString() ?: "IPIfNonMatch") != initialStrategy ||
                proxyInput.text.toString() != initialProxy ||
                directInput.text.toString() != initialDirect ||
                blockInput.text.toString() != initialBlock

            preferences.edit()
                .putString(KEY_PROXY, proxyInput.text.toString())
                .putString(KEY_DIRECT, directInput.text.toString())
                .putString(KEY_BLOCK, blockInput.text.toString())
                .apply()

            if (changed && preferences.getString("vpn_state", "disconnected") == "connected") {
                preferences.edit().putBoolean("site_routing_restart_pending", true).apply()
            }
        }
        super.onPause()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
