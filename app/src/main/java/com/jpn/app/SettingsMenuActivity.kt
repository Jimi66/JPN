package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsMenuActivity : Activity() {
    private var renderedDark: Boolean? = null
    private var easterEggTapCount = 0
    private var lastEasterEggTap = 0L

    override fun onResume() {
        super.onResume()
        val currentDark = JpnTheme.isDark(this)
        if (renderedDark != null && renderedDark != currentDark) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val dark = JpnTheme.isDark(this)
        renderedDark = dark
        val textColor = if (dark) 0xFFF1F1F5.toInt() else 0xFF202124.toInt()
        val secondaryColor = if (dark) 0xFFB9BAC4.toInt() else 0xFF55565C.toInt()
        val cardColor = if (dark) 0xFF292B33.toInt() else 0xFFF7F7F8.toInt()
        val borderColor = if (dark) 0xFF555762.toInt() else 0xFFB9BBC2.toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(12) + insets.systemWindowInsetTop,
                dp(18), dp(20) + insets.systemWindowInsetBottom)
            insets
        }

        content.addView(TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(if (dark) 0xFFAAA4E0.toInt() else 0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(16))
            setOnClickListener { finish() }
        })

        content.addView(sectionTitle("Общие настройки", textColor))
        val generalItems = sectionCard(cardColor, borderColor)
        addSubItem(generalItems, "Тема интерфейса", textColor, secondaryColor) {
            startActivity(Intent(this, GeneralSettingsActivity::class.java))
        }
        content.addView(generalItems, sectionParams())

        content.addView(sectionTitle("Настройки TUN модуля", textColor).apply {
            setPadding(0, dp(18), 0, dp(10))
        })
        val tunItems = sectionCard(cardColor, borderColor)
        addSubItem(tunItems, "Маршрутизация приложений", textColor, secondaryColor) {
            startActivity(Intent(this, AppRoutingActivity::class.java))
        }
        addDivider(tunItems, borderColor)
        addSubItem(tunItems, "Маршрутизация сайтов", textColor, secondaryColor) {
            startActivity(Intent(this, SiteRoutingActivity::class.java))
        }
        addDivider(tunItems, borderColor)
        addSubItem(tunItems, "Автообновление подписки", textColor, secondaryColor) {
            startActivity(Intent(this, SubscriptionAutoUpdateActivity::class.java))
        }
        content.addView(tunItems, sectionParams())

        // Keep the discreet credits and the seven-tap video easter egg
        // at the bottom of the settings screen.
        content.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        content.addView(TextView(this).apply {
            text = "Created by Stephani GPT & Alex Dzh "
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF9A9AA0.toInt())
            setPadding(0, dp(8), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { handleEasterEggTap() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        JpnTheme.apply(this, scroll)
    }

    private fun handleEasterEggTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEasterEggTap > 2500L) easterEggTapCount = 0
        lastEasterEggTap = now
        easterEggTapCount++

        if (easterEggTapCount >= 7) {
            easterEggTapCount = 0
            startActivity(Intent(this, EasterEggActivity::class.java))
        }
    }

    private fun sectionTitle(title: String, color: Int) = TextView(this).apply {
        text = title
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        setTextColor(color)
        setPadding(0, dp(2), 0, dp(10))
    }

    private fun sectionCard(backgroundColor: Int, borderColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), borderColor)
        }
        clipToOutline = true
    }

    private fun addSubItem(parent: LinearLayout, title: String, textColor: Int,
        secondaryColor: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            val value = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            foreground = if (value.resourceId != 0) getDrawable(value.resourceId) else null
            setOnClickListener { action() }
            addView(TextView(this@SettingsMenuActivity).apply {
                text = title
                textSize = 16f
                setTextColor(textColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsMenuActivity).apply {
                text = "›"
                textSize = 25f
                setTextColor(secondaryColor)
            })
        }
        parent.addView(row)
    }

    private fun addDivider(parent: LinearLayout, color: Int) {
        parent.addView(View(this).apply { setBackgroundColor(color) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun sectionParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
