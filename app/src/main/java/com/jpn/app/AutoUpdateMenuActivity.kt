package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class AutoUpdateMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val dark = JpnTheme.isDark(this)
        val primary = if (dark) 0xFFF1F1F5.toInt() else 0xFF202124.toInt()
        val secondary = if (dark) 0xFFB9BAC4.toInt() else 0xFF55565C.toInt()
        val card = if (dark) 0xFF292B33.toInt() else 0xFFF7F7F8.toInt()
        val border = if (dark) 0xFF555762.toInt() else 0xFFB9BBC2.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(18), dp(12) + insets.systemWindowInsetTop,
                dp(18), dp(20) + insets.systemWindowInsetBottom)
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
            text = "Автообновление"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary)
            setPadding(0, 0, 0, dp(16))
        })

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), border)
            }
            clipToOutline = true
        }

        addMenuItem(cardLayout, "Автообновление подписки", primary, secondary) {
            startActivity(Intent(this, SubscriptionAutoUpdateActivity::class.java))
        }
        cardLayout.addView(View(this).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        addMenuItem(cardLayout, "Автообновление Geo-баз", primary, secondary) {
            startActivity(Intent(this, GeoDatabaseAutoUpdateActivity::class.java))
        }

        root.addView(cardLayout)
        setContentView(root)
        JpnTheme.apply(this, root)
    }

    private fun addMenuItem(parent: LinearLayout, title: String, textColor: Int,
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
            addView(TextView(this@AutoUpdateMenuActivity).apply {
                text = title
                textSize = 16f
                setTextColor(textColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@AutoUpdateMenuActivity).apply {
                text = "›"
                textSize = 25f
                setTextColor(secondaryColor)
            })
        }
        parent.addView(row)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
