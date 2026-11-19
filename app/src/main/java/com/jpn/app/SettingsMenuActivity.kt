package com.jpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsMenuActivity : Activity() {
    private var renderedDark: Boolean? = null
    private var easterEggTapCount = 0
    private var lastEasterEggTap = 0L
    private var easterEggContainer: FrameLayout? = null
    private var easterEggTexture: AspectRatioTextureView? = null
    private var easterEggPlayer: MediaPlayer? = null
    private var videoPrepared = false
    private var videoCompleted = false

    override fun onResume() {
        super.onResume()
        val currentDark = JpnTheme.isDark(this)
        if (renderedDark != null && renderedDark != currentDark) {
            recreate()
        } else {
            if (videoPrepared && !videoCompleted) easterEggPlayer?.start()
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
            val labels = arrayOf("Как в системе", "Светлая", "Тёмная")
            val values = arrayOf(JpnTheme.SYSTEM, JpnTheme.LIGHT, JpnTheme.DARK)
            val prefs = getSharedPreferences("jpn_settings", MODE_PRIVATE)
            val selected = values.indexOf(
                prefs.getString("interface_theme", JpnTheme.SYSTEM)
            ).coerceAtLeast(0)
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
        content.addView(generalItems, sectionParams())

        content.addView(sectionTitle("Настройки Tun режима", textColor).apply {
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
        addSubItem(tunItems, "Автообновление", textColor, secondaryColor) {
            startActivity(Intent(this, AutoUpdateMenuActivity::class.java))
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
            setOnClickListener { handleEasterEggTap(content) }
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

    private fun handleEasterEggTap(content: LinearLayout) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEasterEggTap > 2500L) easterEggTapCount = 0
        lastEasterEggTap = now
        easterEggTapCount++
        if (easterEggTapCount >= 7) {
            easterEggTapCount = 0
            if (easterEggContainer?.visibility == View.VISIBLE) return
            showInlineEasterEgg(content)
        }
    }

    private fun showInlineEasterEgg(content: LinearLayout) {
        releaseEasterEggPlayer()
        videoPrepared = false
        videoCompleted = false
        val videoWidth = ((content.width - content.paddingLeft - content.paddingRight) * 0.74f).toInt()
        val container = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                cornerRadius = dp(14).toFloat()
            }
            clipToOutline = true
            alpha = 0f
        }
        val texture = AspectRatioTextureView(this)
        container.addView(texture, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        easterEggContainer = container
        easterEggTexture = texture
        content.addView(container, LinearLayout.LayoutParams(
            videoWidth, dp(180)
        ).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        container.post { (content.parent as? ScrollView)?.smoothScrollTo(0, content.height) }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture,
                width: Int, height: Int) {
                try {
                    val player = MediaPlayer()
                    easterEggPlayer = player
                    player.setDataSource(this@SettingsMenuActivity,
                        Uri.parse("android.resource://$packageName/${R.raw.easter_egg}"))
                    player.setSurface(Surface(surfaceTexture))
                    player.setOnVideoSizeChangedListener { _, sourceWidth, sourceHeight ->
                        texture.setVideoSize(sourceWidth, sourceHeight)
                        if (sourceWidth > 0 && sourceHeight > 0) {
                            container.layoutParams = container.layoutParams.apply {
                                height = (videoWidth * sourceHeight.toFloat() / sourceWidth).toInt()
                            }
                        }
                    }
                    player.setOnPreparedListener { mp ->
                        videoPrepared = true
                        mp.isLooping = false
                        container.animate()
                            .alpha(1f)
                            .setDuration(350L)
                            .start()
                        mp.start()
                    }
                    player.setOnCompletionListener {
                        videoCompleted = true
                        videoPrepared = false
                        hideEasterEggContainer(container)
                        releaseEasterEggPlayer()
                    }
                    player.setOnErrorListener { _, _, _ ->
                        hideEasterEggContainer(container)
                        videoCompleted = true
                        videoPrepared = false
                        releaseEasterEggPlayer()
                        android.widget.Toast.makeText(this@SettingsMenuActivity,
                            "Не удалось воспроизвести видео пасхалки",
                            android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    player.prepareAsync()
                } catch (_: Exception) {
                    hideEasterEggContainer(container)
                    releaseEasterEggPlayer()
                    android.widget.Toast.makeText(this@SettingsMenuActivity,
                        "Не удалось воспроизвести видео пасхалки",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture,
                width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
    }

    private fun hideEasterEggContainer(container: FrameLayout) {
        container.animate()
            .alpha(0f)
            .setDuration(250L)
            .withEndAction {
                container.visibility = View.GONE
            }
            .start()
    }

    private fun releaseEasterEggPlayer() {
        try {
            easterEggPlayer?.setSurface(null)
            easterEggPlayer?.release()
        } catch (_: Exception) { }
        easterEggPlayer = null
    }

    private class AspectRatioTextureView(context: android.content.Context) : TextureView(context) {
        private var videoWidth = 0
        private var videoHeight = 0
        fun setVideoSize(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                videoWidth = width
                videoHeight = height
                requestLayout()
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (videoWidth <= 0 || videoHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
                setMeasuredDimension(maxWidth, maxHeight)
                return
            }
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            var width = maxWidth.toFloat()
            var height = width / ratio
            if (height > maxHeight) {
                height = maxHeight.toFloat()
                width = height * ratio
            }
            setMeasuredDimension(width.toInt(), height.toInt())
        }
    }

    override fun onPause() {
        if (easterEggPlayer?.isPlaying == true) easterEggPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        releaseEasterEggPlayer()
        easterEggTexture = null
        easterEggContainer = null
        super.onDestroy()
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
