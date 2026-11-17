package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class SettingsMenuActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                dp(18),
                dp(12) + insets.systemWindowInsetTop,
                dp(18),
                dp(12) + insets.systemWindowInsetBottom
            )
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
            text = "Настройки"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, 0, 0, dp(16))
        })

        root.addView(menuItem("Настройки TUN модуля") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        root.addView(menuItem("Общие настройки") {
            startActivity(Intent(this, GeneralSettingsActivity::class.java))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        JpnTheme.apply(this, root)
    }

    private fun menuItem(title: String, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x26000000),
                null,
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@SettingsMenuActivity).apply {
                text = title
                textSize = 16f
                setTextColor(0xFF202124.toInt())
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
