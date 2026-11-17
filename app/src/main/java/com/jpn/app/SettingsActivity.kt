package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {

    private var easterEggTapCount = 0
    private var lastEasterEggTap = 0L


    override fun onCreate(savedInstanceState: Bundle?) {
        JpnTheme.applyBeforeCreate(this)
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

        val themeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showThemePicker() }
        }
        themeCard.addView(TextView(this).apply {
            text = "Тема интерфейса"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        themeCard.addView(TextView(this).apply {
            text = themeLabel()
            textSize = 14f
            setTextColor(0xFF514B83.toInt())
        })
        root.addView(themeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

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

        root.addView(routing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val siteRouting = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
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
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, SiteRoutingActivity::class.java))
            }
        }
        siteRouting.addView(TextView(this).apply {
            text = "Маршрутизация сайтов"
            textSize = 16f
            setTextColor(0xFF202124.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(siteRouting, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Keep the credits discreetly anchored to the bottom of Settings.
        root.addView(android.view.View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(TextView(this).apply {
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

        setContentView(root)
        JpnTheme.apply(this, root)
    }

    private fun themeLabel(): String = when (
        getSharedPreferences("jpn_settings", MODE_PRIVATE).getString("interface_theme", JpnTheme.SYSTEM)
    ) {
        JpnTheme.LIGHT -> "Светлая"
        JpnTheme.DARK -> "Тёмная"
        else -> "Как в системе"
    }

    private fun showThemePicker() {
        val labels = arrayOf("Как в системе", "Светлая", "Тёмная")
        val values = arrayOf(JpnTheme.SYSTEM, JpnTheme.LIGHT, JpnTheme.DARK)
        val prefs = getSharedPreferences("jpn_settings", MODE_PRIVATE)
        val selected = values.indexOf(prefs.getString("interface_theme", JpnTheme.SYSTEM)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Тема интерфейса")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putString("interface_theme", values[which]).apply()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Отмена", null)
            .show()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
