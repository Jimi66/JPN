package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val back = TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnClickListener { finish() }
        }
        root.addView(back)

        root.addView(TextView(this).apply {
            text = "Настройки TUN модуля"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, 0, 0, dp(16))
        })

        val routing = TextView(this).apply {
            text = "Маршрутизация приложений"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AppRoutingActivity::class.java))
            }
        }
        root.addView(routing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
