package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.Collator
import java.util.Locale

class AppRoutingActivity : Activity() {

    companion object {
        private const val PREFS = "jpn_settings"
        const val EXTRA_ROUTING_PROFILE = "routing_profile"
        const val PROFILE_TUN = "tun"
        const val PROFILE_TURN = "turn"
        private const val KEY_ROUTING_MODE = "routing_mode"
        private const val KEY_ROUTING_ENABLED = "routing_enabled"
        private const val KEY_ROUTING_PACKAGES = "routing_packages"
        private const val KEY_TURN_ROUTING_MODE = "turn_routing_mode"
        private const val KEY_TURN_ROUTING_ENABLED = "turn_routing_enabled"
        private const val KEY_TURN_ROUTING_PACKAGES = "turn_routing_packages"
        private const val MODE_EXCLUDE = "exclude"
        private const val MODE_SELECTED = "selected"
    }

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable
    )

    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val isTurnProfile by lazy {
        intent.getStringExtra(EXTRA_ROUTING_PROFILE) == PROFILE_TURN
    }
    private val modeKey by lazy { if (isTurnProfile) KEY_TURN_ROUTING_MODE else KEY_ROUTING_MODE }
    private val enabledKey by lazy { if (isTurnProfile) KEY_TURN_ROUTING_ENABLED else KEY_ROUTING_ENABLED }
    private val packagesKey by lazy { if (isTurnProfile) KEY_TURN_ROUTING_PACKAGES else KEY_ROUTING_PACKAGES }
    private val selectedPackages = linkedSetOf<String>()
    private var apps: List<AppEntry> = emptyList()
    private lateinit var appList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var selectedCount: TextView
    private lateinit var modeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)
        selectedPackages.addAll(
            preferences.getStringSet(packagesKey, emptySet()) ?: emptySet()
        )
        loadApps()
        buildScreen()
        renderApps()
    }

    private fun loadApps() {
        val collator = Collator.getInstance(Locale.getDefault())
        apps = packageManager.getInstalledApplications(0)
            .mapNotNull { info ->
                if (info.packageName == packageName ||
                    packageManager.getLaunchIntentForPackage(info.packageName) == null
                ) return@mapNotNull null

                runCatching {
                    AppEntry(
                        packageName = info.packageName,
                        label = packageManager.getApplicationLabel(info).toString(),
                        icon = packageManager.getApplicationIcon(info)
                    )
                }.getOrNull()
            }
            .sortedWith { first, second -> collator.compare(first.label, second.label) }
    }

    private fun buildScreen() {
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

        val title = TextView(this).apply {
            text = if (isTurnProfile) "Маршрутизация приложений — TURN" else "Маршрутизация приложений — TUN"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
        }
        root.addView(title)

        val description = TextView(this).apply {
            text = if (isTurnProfile) "Выбери приложения, трафик которых будет проходить через TURN." else "Выбери приложения, трафик которых будет проходить через TUN."
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(5), 0, dp(12))
        }
        root.addView(description)

        val routingToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        val routingToggleLabel = TextView(this).apply {
            text = "Включить маршрутизацию приложений"
            textSize = 15f
            setTextColor(0xFF202124.toInt())
        }
        routingToggleRow.addView(
            routingToggleLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val routingSwitch = Switch(this).apply {
            isChecked = preferences.getBoolean(enabledKey, false)
            contentDescription = "Маршрутизация приложений включена"
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(enabledKey, checked).apply()
            }
        }
        routingToggleRow.addView(routingSwitch)
        root.addView(routingToggleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF4F5F6.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        val excludeMode = RadioButton(this).apply {
            id = 1
            text = "Все приложения через VPN, кроме выбранных"
            textSize = 14f
        }
        val selectedMode = RadioButton(this).apply {
            id = 2
            text = "Только выбранные приложения через VPN"
            textSize = 14f
        }
        modeGroup.addView(selectedMode)
        modeGroup.addView(excludeMode)
        modeGroup.check(
            if (preferences.getString(modeKey, MODE_SELECTED) == MODE_SELECTED) 2 else 1
        )
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.edit()
                .putString(modeKey, if (checkedId == 2) MODE_SELECTED else MODE_EXCLUDE)
                .apply()
        }
        root.addView(modeGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        searchInput = EditText(this).apply {
            hint = "Поиск приложений"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(searchInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(12) })

        selectedCount = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(2), dp(8), dp(2), dp(6))
        }
        root.addView(selectedCount)

        val scroll = ScrollView(this)
        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(appList)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val note = TextView(this).apply {
            text = "Изменения применяются при следующем запуске VPN."
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        root.addView(note)
        setContentView(root)
        JpnTheme.apply(this, root)

        // Keep routing mode labels readable in dark theme, including when
        // the routing switch is currently off.
        val modeTextColor = JpnTheme.textPrimary(this)
        val modeTextColors = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled)
            ),
            intArrayOf(modeTextColor, modeTextColor)
        )
        for (i in 0 until modeGroup.childCount) {
            (modeGroup.getChildAt(i) as? RadioButton)?.apply {
                setTextColor(modeTextColors)
                isEnabled = true
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderApps()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val query = searchInput.text.toString().trim()
        val visibleApps = apps
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<AppEntry> { selectedPackages.contains(it.packageName) }
                    .thenComparator { first, second ->
                        Collator.getInstance(Locale.getDefault()).compare(first.label, second.label)
                    }
            )

        selectedCount.text = "Выбрано приложений: ${selectedPackages.size}"

        if (visibleApps.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "Приложения не найдены"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(24))
            })
            return
        }

        visibleApps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(10)
            })

            val checkBox = CheckBox(this).apply {
                text = app.label
                textSize = 15f
                isChecked = selectedPackages.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(app.packageName)
                    else selectedPackages.remove(app.packageName)
                    preferences.edit()
                        .putStringSet(packagesKey, HashSet(selectedPackages))
                        .apply()
                    selectedCount.text = "Выбрано приложений: ${selectedPackages.size}"
                    renderApps()
                }
            }
            row.addView(checkBox, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            appList.addView(row)
            appList.addView(View(this).apply {
                setBackgroundColor(0xFFE8E8E8.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
