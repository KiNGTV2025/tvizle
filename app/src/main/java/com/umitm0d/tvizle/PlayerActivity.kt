package com.umitm0d.tvizle

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
    private var resolving = false          // Vavoo adresi çözülüyor mu
    private var vavooRetried = false
    private var vavooRefreshes = 0

    private val modeNames = arrayOf("Sığdır", "Doldur (kırp)", "Uzat", "16:9", "4:3")
    private var mode = 0
    private lateinit var chrome: FrameLayout
    private lateinit var seekRow: LinearLayout
    private lateinit var liveChip: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var posTxt: TextView
    private lateinit var durTxt: TextView
    private lateinit var playBtn: ImageView
    private lateinit var modeBtn: LinearLayout
    private lateinit var speedBtn: LinearLayout
    private lateinit var badge: LinearLayout
    private lateinit var hint: TextView
    private var shownPause: Boolean? = null
    private val ORANGE = Color.parseColor("#FF8A1F")
    private val ORANGE_LIGHT = Color.parseColor("#FFA84D")
    private val ORANGE_DEEP = Color.parseColor("#FF6A00")
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
        spinner.indeterminateTintList = ColorStateList.valueOf(ORANGE)
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
        updateModeLabel()
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

    private fun mk(oval: Boolean, radiusDp: Int, fill: Int, stroke: Int, strokeDp: Int, grad: IntArray? = null): GradientDrawable =
        GradientDrawable().apply {
            if (oval) setShape(GradientDrawable.OVAL) else setCornerRadius(dp(radiusDp).toFloat())
            if (grad != null) {
                setColors(grad)
                setOrientation(GradientDrawable.Orientation.TL_BR)
            } else setColor(fill)
            setStroke(dp(strokeDp), stroke)
        }

    /** Cam görünümlü zemin: normal = yarı saydam, odak = turuncu degrade, basılı = koyu turuncu. */
    private fun glass(oval: Boolean, radiusDp: Int = 22): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), mk(oval, radiusDp, 0, Color.parseColor("#66FFFFFF"), 1, intArrayOf(ORANGE_DEEP, Color.parseColor("#CC5500"))))
        sl.addState(intArrayOf(android.R.attr.state_focused), mk(oval, radiusDp, 0, Color.parseColor("#99FFFFFF"), 1, intArrayOf(ORANGE_LIGHT, ORANGE_DEEP)))
        sl.addState(intArrayOf(), mk(oval, radiusDp, Color.parseColor("#33FFFFFF"), Color.parseColor("#40FFFFFF"), 1))
        return sl
    }

    /** Ana oynat/duraklat düğmesi: her zaman turuncu degrade, odakta beyaz halka. */
    private fun primaryBg(): StateListDrawable {
        val sl = StateListDrawable()
        val g = intArrayOf(ORANGE_LIGHT, ORANGE_DEEP)
        sl.addState(intArrayOf(android.R.attr.state_pressed), mk(true, 0, 0, Color.parseColor("#66FFFFFF"), 1, intArrayOf(ORANGE_DEEP, Color.parseColor("#CC5500"))))
        sl.addState(intArrayOf(android.R.attr.state_focused), mk(true, 0, 0, Color.WHITE, 3, g))
        sl.addState(intArrayOf(), mk(true, 0, 0, Color.parseColor("#66FFFFFF"), 1, g))
        return sl
    }

    private fun focusPop(v: View) {
        v.setOnFocusChangeListener { _, f ->
            val sc = if (f) 1.08f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
        }
    }

    private fun icon(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** İkon + yazı kapsülü (Mod / Döndür / Ses / Altyazı / Kalite / Hız). */
    private fun pill(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val compact = compactUi()
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER
        c.minimumHeight = dp(if (compact) 38 else 44)
        c.setPadding(dp(if (compact) 12 else 16), 0, dp(if (compact) 14 else 18), 0)
        c.isFocusable = true
        c.isClickable = true
        c.background = glass(false, 22)
        val isz = dp(if (compact) 18 else 20)
        c.addView(icon(iconRes), LinearLayout.LayoutParams(isz, isz))
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = if (compact) 12.5f else 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.maxLines = 1
        t.setPadding(dp(7), 0, 0, 0)
        c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.setOnClickListener { onClick(); scheduleHide() }
        focusPop(c)
        c.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), 0, dp(4), 0) }
        return c
    }

    private fun pillLabel(c: LinearLayout): TextView = c.getChildAt(1) as TextView

    /** Yuvarlak ikon düğmesi. */
    private fun roundBtn(iconRes: Int, sizeDp: Int, iconDp: Int, primary: Boolean, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(iconRes)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp((sizeDp - iconDp) / 2)
        v.setPadding(pad, pad, pad, pad)
        v.background = if (primary) primaryBg() else glass(true)
        v.isFocusable = true
        v.isClickable = true
        v.setOnClickListener { onClick(); scheduleHide() }
        focusPop(v)
        return v
    }

    /** 10 sn geri / ileri: dairesel ok + ortada "10". */
    private fun skipBtn(forward: Boolean, onClick: () -> Unit): FrameLayout {
        val compact = compactUi()
        val f = FrameLayout(this)
        f.background = glass(true)
        f.isFocusable = true
        f.isClickable = true
        val isz = dp(if (compact) 28 else 32)
        f.addView(icon(if (forward) R.drawable.ic_pl_fwd else R.drawable.ic_pl_rew),
            FrameLayout.LayoutParams(isz, isz, Gravity.CENTER))
        val t = TextView(this)
        t.text = "10"
        t.setTextColor(Color.WHITE)
        t.textSize = if (compact) 8f else 9f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        f.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { topMargin = dp(1) })
        f.setOnClickListener { onClick(); scheduleHide() }
        focusPop(f)
        return f
    }

    private fun buildControls() {
        val compact = compactUi()
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        // Ekran ipucu balonu (Uzat, 1.5x, Altyazı yok ...)
        hint = TextView(this)
        hint.setTextColor(Color.WHITE)
        hint.textSize = 15f
        hint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        hint.background = mk(false, 20, Color.parseColor("#CC000000"), Color.parseColor("#66FF8A1F"), 1)
        hint.setPadding(dp(18), dp(9), dp(18), dp(9))
        hint.visibility = View.GONE
        root.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(28) })

        chrome = FrameLayout(this)
        chrome.visibility = View.GONE

        // Üst gölge + Kapat
        val topScrim = View(this)
        topScrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#B3000000"), Color.parseColor("#00000000")))
        chrome.addView(topScrim, FrameLayout.LayoutParams(MATCH, dp(96), Gravity.TOP))
        val csz = if (compact) 40 else 46
        val closeBtn = roundBtn(R.drawable.ic_pl_close, csz, 20, false) { finish() }
        chrome.addView(closeBtn, FrameLayout.LayoutParams(dp(csz), dp(csz), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(12), dp(20), 0) })

        // Alt panel
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#00000000"), Color.parseColor("#CC000000"), Color.parseColor("#F2000000")))
        bottom.setPadding(dp(20), dp(40), dp(20), dp(14))

        // CANLI rozeti (canlı yayında) / ilerleme çubuğu (film-dizi)
        liveChip = LinearLayout(this)
        liveChip.orientation = LinearLayout.HORIZONTAL
        liveChip.gravity = Gravity.CENTER_VERTICAL
        liveChip.background = mk(false, 12, Color.parseColor("#26FF3B30"), Color.parseColor("#66FF3B30"), 1)
        liveChip.setPadding(dp(10), dp(4), dp(12), dp(4))
        val dot = View(this)
        dot.background = mk(true, 0, Color.parseColor("#FF3B30"), Color.parseColor("#FF3B30"), 0)
        liveChip.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        val liveTxt = TextView(this)
        liveTxt.text = "CANLI"
        liveTxt.setTextColor(Color.WHITE)
        liveTxt.textSize = 11f
        liveTxt.typeface = Typeface.DEFAULT_BOLD
        liveTxt.letterSpacing = 0.12f
        liveTxt.setPadding(dp(7), 0, 0, 0)
        liveChip.addView(liveTxt, LinearLayout.LayoutParams(WRAP, WRAP))
        liveChip.visibility = View.GONE
        bottom.addView(liveChip, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })

        seekRow = LinearLayout(this)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        posTxt = TextView(this)
        durTxt = TextView(this)
        for (t in arrayOf(posTxt, durTxt)) {
            t.setTextColor(Color.parseColor("#E6FFFFFF"))
            t.textSize = 12.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        seek = SeekBar(this)
        seek.max = 1000
        seek.progressTintList = ColorStateList.valueOf(ORANGE)
        seek.thumbTintList = ColorStateList.valueOf(ORANGE_LIGHT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#40FFFFFF"))
        seek.secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
        seekRow.addView(posTxt, LinearLayout.LayoutParams(WRAP, WRAP))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        seekRow.addView(durTxt, LinearLayout.LayoutParams(WRAP, WRAP))
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
        bottom.addView(seekRow)

        // Orta satır: -10 | OYNAT | +10
        val transport = LinearLayout(this)
        transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER
        val sk = dp(if (compact) 48 else 54)
        val big = dp(if (compact) 60 else 68)
        val gap = dp(if (compact) 14 else 22)
        transport.addView(skipBtn(false) { seekBy(-10000L) },
            LinearLayout.LayoutParams(sk, sk).apply { setMargins(gap, 0, gap, 0) })
        playBtn = roundBtn(R.drawable.ic_pl_pause, if (compact) 60 else 68, if (compact) 28 else 32, true) { togglePlay() }
        transport.addView(playBtn, LinearLayout.LayoutParams(big, big).apply { setMargins(gap, 0, gap, 0) })
        transport.addView(skipBtn(true) { seekBy(10000L) },
            LinearLayout.LayoutParams(sk, sk).apply { setMargins(gap, 0, gap, 0) })
        bottom.addView(transport, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

        // Alt satır: kapsül düğmeler
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        modeBtn = pill(R.drawable.ic_pl_aspect, modeNames[0]) { cycleMode() }
        row.addView(modeBtn)
        row.addView(pill(R.drawable.ic_pl_rotate, "Döndür") { rotate() })
        row.addView(pill(R.drawable.ic_pl_audio, "Ses") { pickAudio() })
        row.addView(pill(R.drawable.ic_pl_sub, "Altyazı") { pickSubtitle() })
        row.addView(pill(R.drawable.ic_pl_quality, "Kalite") { pickQuality() })
        speedBtn = pill(R.drawable.ic_pl_speed, "Hız") { pickSpeed() }
        row.addView(speedBtn)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.isFillViewport = true
        hs.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        bottom.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        chrome.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        root.addView(chrome, FrameLayout.LayoutParams(MATCH, MATCH))

        // ÜmitTV rozeti: her zaman sol üstte
        badge = LinearLayout(this)
        badge.orientation = LinearLayout.HORIZONTAL
        badge.gravity = Gravity.CENTER_VERTICAL
        badge.background = mk(false, 20, Color.parseColor("#99000000"), Color.parseColor("#66FF8A1F"), 1)
        badge.setPadding(dp(8), dp(5), dp(13), dp(5))
        badge.alpha = 0.95f
        badge.addView(icon(R.drawable.ic_pl_logo), LinearLayout.LayoutParams(dp(20), dp(20)))
        val bt = TextView(this)
        val sp = SpannableString("ÜmitTV")
        sp.setSpan(ForegroundColorSpan(Color.WHITE), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(ORANGE), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        bt.text = sp
        bt.textSize = 13f
        bt.letterSpacing = 0.04f
        bt.setPadding(dp(6), 0, 0, 0)
        badge.addView(bt, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(20), dp(14), 0, 0) })

        updateModeLabel()
    }

    private fun isVod(): Boolean {
        val p = player ?: return false
        return p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive && p.duration > 0
    }

    /** Film/dizi → ilerleme çubuğu, canlı yayın → CANLI rozeti. */
    private fun syncRows() {
        val vod = isVod()
        seekRow.visibility = if (vod) View.VISIBLE else View.GONE
        val p = player
        val live = !vod && p != null && (p.isCurrentMediaItemLive || p.playbackState == Player.STATE_READY)
        liveChip.visibility = if (live) View.VISIBLE else View.GONE
    }

    private fun showControls() {
        controlsVisible = true
        chrome.animate().cancel()
        chrome.visibility = View.VISIBLE
        chrome.alpha = 0f
        chrome.animate().alpha(1f).setDuration(160).start()
        syncRows()
        updatePlayLabel()
        handler.removeCallbacks(tick)
        handler.post(tick)
        playBtn.requestFocus()
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tick)
        chrome.animate().cancel()
        chrome.animate().alpha(0f).setDuration(140).withEndAction {
            if (!controlsVisible) chrome.visibility = View.GONE
        }.start()
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
        syncRows()
        if (!isVod()) return
        val d = p.duration
        if (d > 0 && !seek.isPressed) seek.progress = (p.currentPosition * 1000 / d).toInt()
        if (d > 0) seek.secondaryProgress = (p.bufferedPosition * 1000 / d).toInt().coerceIn(0, 1000)
        posTxt.text = fmt(p.currentPosition)
        durTxt.text = fmt(d)
    }

    private fun updatePlayLabel() {
        val p = player
        val playing = p != null && p.playWhenReady
        if (shownPause != playing) {
            shownPause = playing
            playBtn.setImageResource(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
        }
    }

    private fun togglePlay() {
        if (player == null) { msg.visibility = View.GONE; startPlayer(); return }
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
        pillLabel(modeBtn).text = modeNames[mode]
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
            pillLabel(speedBtn).text = if (speeds[which] == 1f) "Hız" else names[which]
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        if (!VavooResolver.matches(url)) { beginPlayback(url, customUa, referer); return }
        // Vavoo adresi: önce gerçek yayın adresine çevir
        VavooResolver.cached(url)?.let { beginPlayback(it, VavooResolver.UA_APP, VavooResolver.REFERER); return }
        if (resolving) return
        resolving = true
        spinner.visibility = View.VISIBLE
        val src = url
        VavooResolver.resolveAsync(src) { r ->
            handler.post {
                resolving = false
                if (isFinishing || player != null) return@post
                if (r != null) beginPlayback(r, VavooResolver.UA_APP, VavooResolver.REFERER)
                else {
                    spinner.visibility = View.GONE
                    msg.text = ObfuscatedText.get(23)
                    msg.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun beginPlayback(playUrl: String, ua: String, ref: String) {
        if (player != null) return
        val http = ExoConfig.http(ua, ref)
        val renderers = DecoderTweaks.renderers(this)
            .setMediaCodecSelector(DecoderTweaks.selector { false })
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val extractors = ExoConfig.extractors()
        val loadControl = ExoConfig.loadControl(this)
        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
                    .setLoadErrorHandlingPolicy(ExoConfig.loadErrorPolicy()))
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
        p.trackSelectionParameters = DecoderTweaks.cap(this, p.trackSelectionParameters.buildUpon())
            .setPreferredAudioLanguage("tr")
            .setPreferredTextLanguage("tr")
            .build()
        p.setVideoSurfaceView(surface)
        val item = MediaItem.Builder().setUri(playUrl)
        if (playUrl.contains(".m3u8", ignoreCase = true)) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
            item.setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(15000).setMinOffsetMs(8000).setMaxOffsetMs(45000)
                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build())
        }
        p.setMediaItem(item.build())
        p.playWhenReady = true
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        resolving = false
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun handleError(e: PlaybackException) {
        var httpCode = 0
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                httpCode = cause.responseCode
                break
            }
            cause = cause.cause
        }
        val expiredVavooUrl = VavooResolver.matches(url) && httpCode in setOf(401, 403, 410)
        if (VavooResolver.matches(url) && vavooRefreshes < 3 &&
            (expiredVavooUrl || !vavooRetried && retries == 0 && player?.let { it.currentPosition <= 0L } != false)) {
            vavooRetried = true
            vavooRefreshes++
            VavooResolver.invalidate(url)
            releasePlayer()
            startPlayer()
            return
        }
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
