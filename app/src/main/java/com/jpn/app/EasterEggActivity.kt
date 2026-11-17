package com.jpn.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView

class EasterEggActivity : Activity() {

    private lateinit var videoView: VideoView
    private var prepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(Color.BLACK)
        videoView = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnPreparedListener { player ->
                prepared = true
                player.isLooping = false
                start()
            }
            setOnCompletionListener {
                finish()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(
                    this@EasterEggActivity,
                    "Не удалось воспроизвести пасхалку",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                true
            }
        }

        setContentView(
            videoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.easter_egg}")
        videoView.setVideoURI(videoUri)
    }

    override fun onPause() {
        if (::videoView.isInitialized && prepared && videoView.isPlaying) {
            videoView.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::videoView.isInitialized && prepared && !videoView.isPlaying) {
            videoView.start()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        finish()
    }
}
