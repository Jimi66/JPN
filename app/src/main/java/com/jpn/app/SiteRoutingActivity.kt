package com.jpn.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
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
        private const val KEY_DOMAIN_STRATEGY = "site_domain_strategy"
    }

    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val rules = mutableListOf<JSONObject>()
    private lateinit var rulesContainer: LinearLayout
    private lateinit var enabledSwitch: Switch
    private lateinit var strategySpinner: Spinner
    private lateinit var valueInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var actionSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = runCatching {
            JSONArray(preferences.getString(KEY_RULES, "[]"))
        }.getOrDefault(JSONArray())
        for (i in 0 until saved.length()) {
            saved.optJSONObject(i)?.let { rules.add(JSONObject(it.toString())) }
        }
        buildScreen()
        renderRules()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(12) + insets.systemWindowInsetTop,
                dp(18), dp(12) + insets.systemWindowInsetBottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnClickListener { finish() }
        })

        root.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Задавай правила для доменов и IP-адресов. Правила применяются сверху вниз."
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(5), 0, dp(12))
        })

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = cardBackground()
        }
        toggleRow.addView(TextView(this).apply {
            text = "Включить маршрутизацию сайтов"
            textSize = 15f
            setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        enabledSwitch = Switch(this).apply {
            isChecked = preferences.getBoolean(KEY_ENABLED, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(KEY_ENABLED, checked).apply()
            }
        }
        toggleRow.addView(enabledSwitch)
        root.addView(toggleRow, matchWidth().apply { bottomMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Domain Strategy"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(2), dp(2), dp(2), dp(4))
        })
        strategySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SiteRoutingActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("IPIfNonMatch", "AsIs"))
            setSelection(if (preferences.getString(KEY_DOMAIN_STRATEGY, "IPIfNonMatch") == "AsIs") 1 else 0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferences.edit().putString(KEY_DOMAIN_STRATEGY,
                        if (position == 1) "AsIs" else "IPIfNonMatch").apply()
                }
            }
        }
        root.addView(strategySpinner, matchWidth().apply { bottomMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Новое правило"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        valueInput = EditText(this).apply {
            hint = "Домен или IP, например example.com"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(valueInput, matchWidth().apply { bottomMargin = dp(8) })

        val selectors = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SiteRoutingActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Домен", "IP"))
        }
        selectors.addView(typeSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SiteRoutingActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Proxy", "Direct", "Block"))
        }
        selectors.addView(actionSpinner, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(selectors)

        root.addView(Button(this).apply {
            text = "Добавить правило"
            setOnClickListener { addRule() }
        }, matchWidth().apply { topMargin = dp(8); bottomMargin = dp(12) })

        val scroll = android.widget.ScrollView(this)
        rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rulesContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Изменения применяются при следующем запуске VPN."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        setContentView(root)
    }

    private fun addRule() {
        val value = valueInput.text.toString().trim()
        if (value.isBlank()) {
            valueInput.error = "Укажи домен или IP"
            return
        }
        val isIp = typeSpinner.selectedItemPosition == 1
        val valid = if (isIp) {
            value.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$")) ||
                value.contains(":")
        } else {
            value.matches(Regex("^(?i)([a-z0-9-]+\\.)*[a-z0-9-]+$")) ||
                value.startsWith("domain:") || value.startsWith("full:")
        }
        if (!valid) {
            valueInput.error = if (isIp) "Проверь IP-адрес или CIDR" else "Проверь доменное имя"
            return
        }
        val action = when (actionSpinner.selectedItemPosition) {
            1 -> "direct"
            2 -> "block"
            else -> "proxy"
        }
        rules.add(JSONObject()
            .put("type", if (isIp) "ip" else "domain")
            .put("value", value)
            .put("action", action))
        saveRules()
        valueInput.text.clear()
        renderRules()
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "Правила пока не добавлены"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF777777.toInt())
                setPadding(dp(12), dp(24), dp(12), dp(24))
            })
            return
        }
        rules.forEachIndexed { index, rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = cardBackground()
            }
            val action = when (rule.optString("action")) {
                "direct" -> "Direct"
                "block" -> "Block"
                else -> "Proxy"
            }
            row.addView(TextView(this).apply {
                text = "\${rule.optString("value")}\n\${rule.optString("type").uppercase()} · $action"
                textSize = 14f
                setTextColor(0xFF202124.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "Удалить"
                textSize = 13f
                setTextColor(0xFF514B83.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    rules.removeAt(index)
                    saveRules()
                    renderRules()
                }
            })
            rulesContainer.addView(row, matchWidth().apply { bottomMargin = dp(6) })
        }
    }

    private fun saveRules() {
        val array = JSONArray()
        rules.forEach { array.put(it) }
        preferences.edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun cardBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xFFF4F5F6.toInt())
        cornerRadius = dp(14).toFloat()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
