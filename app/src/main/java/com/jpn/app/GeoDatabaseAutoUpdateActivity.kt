package com.jpn.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class GeoDatabaseAutoUpdateActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jpn_settings", MODE_PRIVATE) }
    private lateinit var intervalValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)
        val dark = JpnTheme.isDark(this)
        val primary = if (dark) 0xFFF1F1F5.toInt() else 0xFF202124.toInt()
        val secondary = if (dark) 0xFFB9BAC4.toInt() else 0xFF55565C.toInt()
        val card = if (dark) 0xFF292B33.toInt() else 0xFFF4F5F6.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                dp(18), dp(12) + insets.systemWindowInsetTop,
                dp(18), dp(20) + insets.systemWindowInsetBottom
            )
            insets
        }

        root.addView(TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(if (dark) 0xFFAAA4E0.toInt() else 0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(16))
            setOnClickListener { finish() }
        })

        root.addView(TextView(this).apply {
            text = "Автообновление Geo-баз"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary)
            setPadding(0, 0, 0, dp(16))
        })

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
        }
        toggleRow.addView(TextView(this).apply {
            text = "Обновлять автоматически"
            textSize = 16f
            setTextColor(primary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toggleRow.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(KEY_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
                AutoGeoDatabaseScheduler.update(this@GeoDatabaseAutoUpdateActivity)
            }
        })
        root.addView(toggleRow)

        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showIntervalPicker() }
        }
        intervalRow.addView(TextView(this).apply {
            text = "Период обновления"
            textSize = 16f
            setTextColor(primary)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        intervalValue = TextView(this).apply {
            textSize = 15f
            setTextColor(secondary)
        }
        intervalRow.addView(intervalValue)
        root.addView(intervalRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

        root.addView(TextView(this).apply {
            text = "GeoSite и GeoIP обновляются в фоновом режиме при наличии интернета. Новые файлы устанавливаются только после успешной загрузки и проверки. Android может немного сдвигать время запуска."
            textSize = 13f
            setTextColor(secondary)
            setPadding(dp(4), dp(12), dp(4), 0)
        })

        updateIntervalLabel()
        setContentView(root)
        JpnTheme.apply(this, root)
    }

    private fun showIntervalPicker() {
        val hours = intArrayOf(6, 12, 24)
        val labels = arrayOf("Каждые 6 часов", "Каждые 12 часов", "Каждые 24 часа")
        val selected = hours.indexOf(
            prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        ).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Период обновления")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putInt(KEY_INTERVAL, hours[which]).apply()
                updateIntervalLabel()
                AutoGeoDatabaseScheduler.update(this)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateIntervalLabel() {
        intervalValue.text = "Каждые ${prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)} ч  ›"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_ENABLED = "geo_database_auto_update_enabled"
        const val KEY_INTERVAL = "geo_database_auto_update_interval_hours"
        const val DEFAULT_INTERVAL = 12
    }
}
