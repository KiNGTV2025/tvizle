package com.umitm0d.tvizle

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * ÜmitTV'nin kendi oynatıcısı (Media3 / ExoPlayer). Sayfadaki "harici oynatıcı" butonu bunu açar.
 * Dokunma / OK: kontrol panelini aç-kapa. Panelde: 10 sn geri, oynat/duraklat, 10 sn ileri, en-boy modu
 * (Sığdır / Doldur / Uzat / 16:9 / 4:3), ekranı döndür, ses dili seç, kapat. Geri = panel açıksa kapat, değilse çık.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_UA = "ua"
        const val EXTRA_REFERER = "referer"
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var spinner: ProgressBar
    private lateinit var msg: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var url = ""
    private var customUa = ""
    private var referer = ""
    private var retries = 0
    private var videoRatio = 0f

    private val modeNames = arrayOf("Sığdır", "Doldur (kırp)", "Uzat", "16:9", "4:3")
    private var mode = 0
    private lateinit var controls: LinearLayout
    private lateinit var seekRow: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeTxt: TextView
    private lateinit var playBtn: TextView
    private lateinit var modeBtn: TextView
    private lateinit var hint: TextView
    private lateinit var subView: SubtitleView
    private var controlsVisible = false
    private var landscape = true
    private val hideRunnable = Runnable { hideControls() }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (controlsVisible) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: ""
        customUa = intent.getStringExtra(EXTRA_UA) ?: ""
        referer = intent.getStringExtra(EXTRA_REFERER) ?: ""
        if (url.isBlank()) { finish(); return }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        subView = SubtitleView(this)
        subView.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        subView.setFractionalTextSize(0.0533f)
        subView.setBottomPaddingFraction(0.12f)
        root.addView(subView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        buildControls()
        setContentView(root)
        mode = getSharedPreferences("umit_player", Context.MODE_PRIVATE).getInt("mode2", 2).coerceIn(0, modeNames.size - 1)
        root.setOnClickListener { if (controlsVisible) hideControls() else showControls() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        hideSystemUi()
    }

    // ───────────── Kontrol paneli ─────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Telefon / dikey / küçük ekran: düğmeleri küçült, çubuk yana kaydırılır. */
    private fun compactUi(): Boolean {
        val dm = resources.displayMetrics
        val shortDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return shortDp < 600f || dm.widthPixels < dm.heightPixels
    }

    private fun makeBtn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        val compact = compactUi()
        t.textSize = if (compact) 14f else 16f
        t.gravity = Gravity.CENTER
        t.minWidth = dp(if (compact) 44 else 56)
        t.minHeight = dp(44)
        t.setPadding(dp(if (compact) 10 else 14), dp(if (compact) 8 else 10), dp(if (compact) 10 else 14), dp(if (compact) 8 else 10))
        t.isFocusable = true
        t.isClickable = true
        fun bg(c: Int): GradientDrawable = GradientDrawable().apply {
            setColor(c); cornerRadius = dp(10).toFloat()
        }
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), bg(Color.parseColor("#FF8A1F")))
        sl.addState(intArrayOf(android.R.attr.state_pressed), bg(Color.parseColor("#CC6A10")))
        sl.addState(intArrayOf(), bg(Color.parseColor("#B3140B05")))
        t.background = sl
        t.setOnClickListener { onClick(); scheduleHide() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), 0, dp(4), 0)
        t.layoutParams = lp
        return t
    }

    private fun buildControls() {
        hint = TextView(this)
        hint.setTextColor(Color.WHITE)
        hint.textSize = 18f
        hint.setBackgroundColor(Color.parseColor("#99000000"))
        hint.setPadding(dp(16), dp(8), dp(16), dp(8))
        hint.visibility = View.GONE
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(Color.parseColor("#80000000"))
        controls.setPadding(dp(16), dp(8), dp(16), dp(16))
        controls.visibility = View.GONE

        seekRow = LinearLayout(this)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        seek = SeekBar(this)
        seek.max = 1000
        timeTxt = TextView(this)
        timeTxt.setTextColor(Color.WHITE)
        timeTxt.textSize = 14f
        seekRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(timeTxt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = player ?: return
                val d = p.duration
                if (d > 0) p.seekTo(d * progress / 1000)
                scheduleHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar) { handler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar) { scheduleHide() }
        })
        controls.addView(seekRow)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.addView(makeBtn("⏪ 10") { seekBy(-10000L) })
        playBtn = makeBtn("⏸") { togglePlay() }
        row.addView(playBtn)
        row.addView(makeBtn("10 ⏩") { seekBy(10000L) })
        modeBtn = makeBtn("") { cycleMode() }
        row.addView(modeBtn)
        row.addView(makeBtn("⟳ Döndür") { rotate() })
        row.addView(makeBtn("🔊 Ses") { pickAudio() })
        row.addView(makeBtn("CC Altyazı") { pickSubtitle() })
        row.addView(makeBtn("HD Kalite") { pickQuality() })
        row.addView(makeBtn("⚡ Hız") { pickSpeed() })
        row.addView(makeBtn("✕ Kapat") { finish() })
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.isFillViewport = true
        hs.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.addView(hs)

        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        updateModeLabel()
    }

    private fun isVod(): Boolean {
        val p = player ?: return false
        return p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive && p.duration > 0
    }

    private fun showControls() {
        controlsVisible = true
        controls.visibility = View.VISIBLE
        seekRow.visibility = if (isVod()) View.VISIBLE else View.GONE
        updatePlayLabel()
        handler.removeCallbacks(tick)
        handler.post(tick)
        playBtn.requestFocus()
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        controls.visibility = View.GONE
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tick)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 5000)
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun updateProgress() {
        val p = player ?: return
        updatePlayLabel()
        if (!isVod()) { seekRow.visibility = View.GONE; return }
        seekRow.visibility = View.VISIBLE
        val d = p.duration
        if (d > 0 && !seek.isPressed) seek.progress = (p.currentPosition * 1000 / d).toInt()
        timeTxt.text = " " + fmt(p.currentPosition) + " / " + fmt(d)
    }

    private fun updatePlayLabel() {
        val p = player
        playBtn.text = if (p != null && p.playWhenReady) "⏸" else "▶"
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playerError != null) {
            retries = 0
            msg.visibility = View.GONE
            p.prepare()
            p.playWhenReady = true
        } else {
            p.playWhenReady = !p.playWhenReady
        }
        updatePlayLabel()
    }

    private fun updateModeLabel() {
        modeBtn.text = "▭ " + modeNames[mode]
    }

    private fun cycleMode() {
        mode = (mode + 1) % modeNames.size
        getSharedPreferences("umit_player", Context.MODE_PRIVATE).edit().putInt("mode2", mode).apply()
        updateModeLabel()
        fitVideo()
        showHint(modeNames[mode])
    }

    private fun showHint(t: String) {
        hint.text = t
        hint.visibility = View.VISIBLE
        handler.removeCallbacks(hintHide)
        handler.postDelayed(hintHide, 1500)
    }

    private val hintHide = Runnable { hint.visibility = View.GONE }

    private fun rotate() {
        landscape = resources.displayMetrics.widthPixels < resources.displayMetrics.heightPixels   // şu an dikeyse → yatay yap
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun showList(title: String, names: Array<String>, onPick: (Int) -> Unit) {
        handler.removeCallbacks(hideRunnable)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which -> onPick(which) }
            .setOnDismissListener { hideSystemUi(); scheduleHide() }
            .show()
    }

    private fun trackName(f: androidx.media3.common.Format, i: Int, fallback: String): String =
        f.label ?: f.language ?: (fallback + " " + (i + 1))

    private fun pickSubtitle() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (groups.isEmpty()) { showHint("Altyazı yok"); return }
        val names = arrayOf("Kapalı") + groups.mapIndexed { i, g -> trackName(g.getTrackFormat(0), i, "Altyazı") }
        showList("Altyazı", names) { which ->
            val b = p.trackSelectionParameters.buildUpon()
            if (which == 0) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                subView.setCues(emptyList())
            } else {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                b.setOverrideForType(TrackSelectionOverride(groups[which - 1].mediaTrackGroup, 0))
            }
            p.trackSelectionParameters = b.build()
        }
    }

    private fun pickQuality() {
        val p = player ?: return
        val g = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
        if (g == null) { showHint("Tek kalite var"); return }
        val idx = (0 until g.length).filter { g.isTrackSupported(it) }
            .sortedByDescending { g.getTrackFormat(it).height }
        val names = arrayOf("Otomatik") + idx.map {
            val f = g.getTrackFormat(it)
            f.height.toString() + "p" + (if (f.bitrate > 0) " · " + (f.bitrate / 1000) + " kbps" else "")
        }
        showList("Kalite", names) { which ->
            val b = p.trackSelectionParameters.buildUpon()
            if (which == 0) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            else b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(idx[which - 1])))
            p.trackSelectionParameters = b.build()
        }
    }

    private fun pickSpeed() {
        val p = player ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val names = speeds.map { (if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()) + "x" }.toTypedArray()
        showList("Oynatma hızı", names) { which ->
            p.setPlaybackSpeed(speeds[which])
            showHint(names[which])
        }
    }

    private fun pickAudio() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (groups.isEmpty()) { showHint("Ses seçeneği yok"); return }
        val names = groups.mapIndexed { i, g ->
            val f = g.getTrackFormat(0)
            val n = f.label ?: f.language ?: ("Ses " + (i + 1))
            if (f.channelCount > 0) n + " (" + f.channelCount + " kanal)" else n
        }.toTypedArray()
        handler.removeCallbacks(hideRunnable)
        AlertDialog.Builder(this)
            .setTitle("Ses")
            .setItems(names) { _, which ->
                val g = groups[which]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                    .build()
            }
            .setOnDismissListener { hideSystemUi(); scheduleHide() }
            .show()
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
            .setUserAgent(if (customUa.isNotBlank()) customUa else UA)
        if (referer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to referer))
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        // IPTV için: bozuk/kesik TS akışlarını daha toleranslı çöz, hızlı kanal açılışı
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2000, 4000)
            .build()
        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retries = 0
                    msg.visibility = View.GONE
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                subView.setCues(cueGroup.cues)
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
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage("tr")
            .setPreferredTextLanguage("tr")
            .build()
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

    /** Seçili moda göre videoyu yerleştirir: Sığdır / Doldur (kırp) / Uzat / 16:9 / 4:3. */
    private fun fitVideo() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0 || videoRatio <= 0f) return
        var nw = w
        var nh = h
        val r = when (mode) { 3 -> 16f / 9f; 4 -> 4f / 3f; else -> videoRatio }
        when (mode) {
            2 -> { nw = w; nh = h }
            1 -> {
                if (w.toFloat() / h > r) { nw = w; nh = (w / r).toInt() } else { nh = h; nw = (h * r).toInt() }
            }
            else -> {
                if (w.toFloat() / h > r) { nh = h; nw = (h * r).toInt() } else { nw = w; nh = (w / r).toInt() }
            }
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (controlsVisible) { hideControls(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10000L); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000L); return true }
        }
        if (!controlsVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showControls(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isVod()) seekBy(10000L) else showControls(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (isVod()) seekBy(-10000L) else showControls(); return true }
            }
        } else {
            scheduleHide()
        }
        return super.onKeyDown(keyCode, event)
    }
}
