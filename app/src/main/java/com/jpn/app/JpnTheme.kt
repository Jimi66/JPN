package com.jpn.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object JpnTheme {
    private const val PREFS = "jpn_settings"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun isDark(activity: Activity): Boolean {
        return when (activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .getString("interface_theme", SYSTEM)) {
            DARK -> true
            LIGHT -> false
            else -> (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun textPrimary(activity: Activity): Int = if (isDark(activity)) 0xFFEFF0F5.toInt() else 0xFF202124.toInt()
    fun textSecondary(activity: Activity): Int = if (isDark(activity)) 0xFFAEB1BC.toInt() else 0xFF666666.toInt()
    fun surface(activity: Activity): Int = if (isDark(activity)) 0xFF272930.toInt() else 0xFFF4F5F6.toInt()
    fun border(activity: Activity): Int = if (isDark(activity)) 0xFF434651.toInt() else 0xFFE2E3E7.toInt()

    fun applyBeforeCreate(activity: Activity) {
        activity.setTheme(if (isDark(activity)) R.style.JpnDarkTheme else R.style.JpnLightTheme)
    }

    fun apply(activity: Activity, root: View) {
        val dark = isDark(activity)
        val bg = if (dark) Color.rgb(24,25,30) else Color.WHITE
        val surface = if (dark) Color.rgb(39,41,48) else Color.rgb(244,245,246)
        val primary = if (dark) Color.rgb(239,240,245) else Color.rgb(32,33,36)
        val secondary = if (dark) Color.rgb(174,177,188) else Color.rgb(102,102,102)
        val border = if (dark) Color.rgb(67,70,81) else Color.rgb(226,227,231)
        root.setBackgroundColor(bg)
        fun walk(v: View) {
            if (v is android.widget.Button) {
                v.setTextColor(0xFF454545.toInt())
            } else if (v is TextView) {
                val old = v.currentTextColor
                val accent = old == 0xFF514B83.toInt() || old == 0xFF706AA5.toInt()
                val muted = old == 0xFF9A9AA0.toInt() || old == 0xFF73777D.toInt() ||
                    old == 0xFF777777.toInt() || old == 0xFF888888.toInt()
                if (!accent) v.setTextColor(if (muted) secondary else primary)
            }
            if (v is EditText) {
                v.setTextColor(primary)
                v.setHintTextColor(secondary)
                v.background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = 10f * activity.resources.displayMetrics.density
                    setStroke(activity.resources.displayMetrics.density.toInt().coerceAtLeast(1), border)
                }
            } else if (v is LinearLayout && v.background is GradientDrawable &&
                v !is android.widget.Button && v !is android.widget.ImageButton) {
                (v.background.mutate() as GradientDrawable).also {
                    it.setColor(surface)
                    v.background = it
                }
            }
            if (v is android.widget.CompoundButton && Build.VERSION.SDK_INT >= 21) {
                v.buttonTintList = ColorStateList.valueOf(if (dark) 0xFFAAA4E0.toInt() else 0xFF706AA5.toInt())
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 26) activity.window.decorView.systemUiVisibility =
            if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}