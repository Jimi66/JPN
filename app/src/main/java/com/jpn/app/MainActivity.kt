package com.jpn.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Initial JPN bootstrap screen.
 *
 * This is deliberately only a buildable project foundation.
 * VPN service, proxy core, subscription parsing and routing are not implemented yet.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = getString(R.string.bootstrap_status)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        container.addView(title)
        container.addView(status)
        setContentView(container)
    }
}
