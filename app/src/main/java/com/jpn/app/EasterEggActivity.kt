package com.jpn.app

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.Toast

class EasterEggActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var resumeWhenPrepared = true
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        textureView = TextureView(this).apply {
            surfaceTextureListener = this@EasterEggActivity
            isOpaque = true
        }
        setContentView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: android.graphics.SurfaceTexture,
        width: Int,
        height: Int
    ) {
        surface = Surface(surfaceTexture)
        startVideo()
    }

    private fun startVideo() {
        if (mediaPlayer != null || surface == null || isFinishing) return

        try {
            val descriptor = resources.openRawResourceFd(R.raw.easter_egg)
            val player = MediaPlayer()
            mediaPlayer = player
            player.setSurface(surface)
            player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()

            player.setOnPreparedListener {
                prepared = true
                if (resumeWhenPrepared && !completed) it.start()
            }
            player.setOnCompletionListener {
                completed = true
                finish()
            }
            player.setOnErrorListener { _, _, _ ->
                Toast.makeText(
                    this@EasterEggActivity,
                    "Не удалось воспроизвести пасхалку",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                true
            }
            player.setOnVideoSizeChangedListener { mp, videoWidth, videoHeight ->
                if (videoWidth > 0 && videoHeight > 0) {
                    val viewWidth = textureView.width.toFloat()
                    val viewHeight = textureView.height.toFloat()
                    val videoRatio = videoWidth.toFloat() / videoHeight
                    val viewRatio = viewWidth / viewHeight
                    val scaleX = if (videoRatio > viewRatio) 1f else viewRatio / videoRatio
                    val scaleY = if (videoRatio > viewRatio) videoRatio / viewRatio else 1f
                    textureView.scaleX = scaleX
                    textureView.scaleY = scaleY
                }
            }
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            player.isLooping = false
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            Toast.makeText(this, "Не удалось открыть видео пасхалки", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surface: android.graphics.SurfaceTexture,
        width: Int,
        height: Int
    ) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        releasePlayer()
        this.surface?.release()
        this.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit

    override fun onPause() {
        resumeWhenPrepared = false
        if (prepared) mediaPlayer?.let { if (it.isPlaying) it.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumeWhenPrepared = true
        if (prepared && !completed) {
            mediaPlayer?.let { if (!it.isPlaying) it.start() }
        }
    }

    private fun releasePlayer() {
        prepared = false
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        finish()
    }
}
