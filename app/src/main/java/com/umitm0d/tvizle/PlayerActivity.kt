package com.umitm0d.tvizle

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * ÜmitTV'nin kendi oynatıcısı (Media3 / ExoPlayer). Sayfadaki "harici oynatıcı" butonu bunu açar.
 * Kumanda: OK = oynat/duraklat (hata varsa yeniden dene), ◀ ▶ = 10 sn geri/ileri (sadece film/dizi), Geri = çık.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var spinner: ProgressBar
    private lateinit var msg: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var url = ""
    private var retries = 0
    private var videoRatio = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: ""
        if (url.isBlank()) { finish(); return }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        msg = TextView(this)
        msg.setTextColor(Color.WHITE)
        msg.textSize = 18f
        msg.gravity = Gravity.CENTER
        msg.setPadding(48, 48, 48, 48)
        msg.visibility = View.GONE
        root.addView(msg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun startPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setUserAgent(UA)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retries = 0
                    msg.visibility = View.GONE
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    fitVideo()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
        p.setVideoSurfaceView(surface)
        val item = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(item.build())
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun handleError(e: PlaybackException) {
        if (retries < 3) {
            retries++
            msg.text = "Bağlantı koptu, yeniden deneniyor ($retries/3)…"
            msg.visibility = View.VISIBLE
            handler.postDelayed({
                player?.let {
                    it.prepare()
                    it.playWhenReady = true
                }
            }, 1500)
        } else {
            spinner.visibility = View.GONE
            msg.text = "Oynatılamadı (" + e.errorCodeName + ")\nTekrar denemek için OK'a bas, çıkmak için Geri."
            msg.visibility = View.VISIBLE
        }
    }

    /** Videoyu en-boy oranını bozmadan ekrana sığdırır. */
    private fun fitVideo() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0 || videoRatio <= 0f) return
        val nw: Int
        val nh: Int
        if (w.toFloat() / h > videoRatio) {
            nh = h
            nw = (h * videoRatio).toInt()
        } else {
            nw = w
            nh = (w / videoRatio).toInt()
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            surface.layoutParams = lp
        }
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        if (p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive) {
            p.seekTo((p.currentPosition + ms).coerceAtLeast(0L))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val p = player
                if (p != null) {
                    if (p.playerError != null) {
                        retries = 0
                        msg.visibility = View.GONE
                        p.prepare()
                        p.playWhenReady = true
                    } else {
                        p.playWhenReady = !p.playWhenReady
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10000L); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000L); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
