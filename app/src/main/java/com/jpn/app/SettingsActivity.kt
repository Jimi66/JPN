package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"
        private const val KEY_ROUTING_ENABLED = "routing_enabled"
    }

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

        val routing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
            // Keep the rounded card background and show a clipped ripple on press.
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
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AppRoutingActivity::class.java))
            }
        }
        routing.addView(TextView(this).apply {
            text = "Маршрутизация приложений"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val routingSwitch = Switch(this).apply {
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ROUTING_ENABLED, false)
            contentDescription = "Маршрутизация приложений включена"
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ROUTING_ENABLED, checked)
                    .apply()
            }
        }
        routing.addView(routingSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(routing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Keep the credits discreetly anchored to the bottom of Settings.
        root.addView(android.view.View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(TextView(this).apply {
            text = "Stephanie GPT & Alex DZH"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF9A9AA0.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
