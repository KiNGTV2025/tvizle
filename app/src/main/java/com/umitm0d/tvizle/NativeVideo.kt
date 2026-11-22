package com.umitm0d.tvizle

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * ÜmitTV ana oynatıcı motoru: ExoPlayer (Media3), sayfanın KENDİ video alanında çalışır.
 *
 * Mimari:
 *  - Görüntü, WebView'in ARKASINDAKİ bir SurfaceView'e çizilir; WebView saydamdır ve sayfa, #vid alanının arkasını
 *    saydam yapar (umit_native_video.js), böylece video "sayfanın içinde" görünür.
 *  - Sayfadaki butonlar (oynat/duraklat, sar, ses, kalite, ses dili, en-boy, hız) sayfanın kendi kodudur;
 *    sayfa <video> API'sini kullanır, o API JS köprüsünce [UmitVideo] üzerinden buraya çevrilir.
 *  - hls.js / mpegts.js kullanılmaz: HLS, TS, MP4, MKV, DASH… hepsini ExoPlayer çözer.
 *  - Native → sayfa: 250 ms'de bir durum ({t:'st'}), izler ({t:'tr'}), hata ({t:'er'}) window.__umitNV(...) ile gider.
 */
class NativeVideo(
    private val act: Activity,
    private val root: FrameLayout,
    private val web: WebView,
    private val toJs: (String) -> Unit
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val TICK_MS = 250L
        private const val MAX_SILENT_RETRIES = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val holder = FrameLayout(act)
    private val surface = SurfaceView(act)

    private var loaded = false           // bir kaynak yüklü mü (sayfa şeffaflığı / yüzey görünürlüğü için)
    private var everReady = false
    private var retries = 0
    private var curUrl = ""
    private var fitMode = "contain"
    private var altTried = false
    private var lastUa = ""; private var lastRef = ""; private var lastKind = ""
    private var videoRatio = 0f
    private var dropped = 0
    private var volume = 1f
    private var muted = false
    private var rate = 1f
    private var showWanted = false
    private var resumeOnStart = false

    // Görüntü gelmeme koruması: ses çalıp ilk video karesi çizilmezse (donanım dekoder sorunu) yazılım dekoderine geç
    private var preferSw = false          // bir kez gerekirse sonraki yayınlarda da yazılım dekoder öncelikli
    private var firstFrame = false
    private var readySince = 0L
    private var swTried = false
    private var hasVideoTrack = false
    private val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
        val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (!preferSw || !mime.startsWith("video/")) list
        else list.sortedBy { i: MediaCodecInfo -> if (i.softwareOnly || i.name.startsWith("c2.android.") || i.name.startsWith("OMX.google.")) 0 else 1 }
    }

    private var levelRefs: List<Pair<Tracks.Group, Int>> = emptyList()
    private var audioRefs: List<Tracks.Group> = emptyList()

    val isActive: Boolean get() = loaded

    init {
        holder.clipChildren = true
        holder.visibility = View.INVISIBLE
        holder.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // WebView'in ALTINA (index 0): WebView saydam olduğu yerde video görünür
        root.addView(holder, 0, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
    }

    // ───────────── ExoPlayer ─────────────
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val renderers = DefaultRenderersFactory(act)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2000, 4000)   // IPTV: hızlı kanal açılışı
            .build()
        val p = ExoPlayer.Builder(act, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { everReady = true; retries = 0 }
                pushState()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = pushState()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = pushState()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = pushState()
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    applyFit()
                }
                pushState()
            }
            override fun onTracksChanged(tracks: Tracks) = pushTracks(tracks)
            override fun onRenderedFirstFrame() {
                firstFrame = true
                send(JSONObject().put("t", "rf"))
            }
            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }
        })
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage("tr")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)   // altyazıyı sayfa yönetiyor
            .build()
        p.setVideoSurfaceView(surface)
        applyVolume(p)
        p.setPlaybackSpeed(rate)
        player = p
        return p
    }

    private fun buildSource(url: String, ua: String, referer: String, kind: String): androidx.media3.exoplayer.source.MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setUserAgent(if (ua.isNotBlank()) ua else UA)
        if (referer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to referer))
        // IPTV: bozuk/kesik TS akışlarını toleranslı çöz
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val item = MediaItem.Builder().setUri(url)
        val low = url.lowercase()
        when {
            kind == "hls" || low.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(act, http), extractors)
            .createMediaSource(item.build())
    }

    // ───────────── Sayfadan gelen komutlar (hepsi ana iş parçacığında çağrılır) ─────────────
    fun load(url: String, ua: String, referer: String, startMs: Long, autoplay: Boolean, kind: String) {
        val p = ensurePlayer()
        curUrl = url; everReady = false; retries = 0; dropped = 0
        altTried = false; lastUa = ua; lastRef = referer; lastKind = kind
        firstFrame = false; readySince = 0L; swTried = false; hasVideoTrack = false
        levelRefs = emptyList(); audioRefs = emptyList(); videoRatio = 0f
        loaded = true
        handler.removeCallbacks(retryRun)
        p.setMediaSource(buildSource(url, ua, referer, kind))
        if (startMs > 0) p.seekTo(startMs)
        p.playWhenReady = autoplay
        p.prepare()
        refreshVisibility()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    fun play() { player?.let { p ->
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
        p.playWhenReady = true
    } }
    fun pause() { player?.playWhenReady = false }
    fun seek(ms: Long) { player?.seekTo(ms.coerceAtLeast(0L)) }
    fun volume(v: Double, m: Boolean) { volume = v.toFloat().coerceIn(0f, 1f); muted = m; player?.let { applyVolume(it) } }
    fun rate(r: Double) { rate = if (r > 0) r.toFloat() else 1f; player?.setPlaybackSpeed(rate) }
    fun retry() {
        val p = player ?: return
        handler.removeCallbacks(retryRun)
        p.prepare(); p.playWhenReady = true
    }

    fun stop() {
        handler.removeCallbacks(tick); handler.removeCallbacks(retryRun)
        loaded = false; everReady = false
        player?.let { it.stop(); it.clearMediaItems() }
        levelRefs = emptyList(); audioRefs = emptyList()
        refreshVisibility()
    }

    fun level(i: Int) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (i < 0 || i >= levelRefs.size) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        else { val (g, idx) = levelRefs[i]; b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(idx))) }
        p.trackSelectionParameters = b.build()
    }

    fun audio(i: Int) {
        val p = player ?: return
        if (i < 0 || i >= audioRefs.size) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(audioRefs[i].mediaTrackGroup, 0)).build()
    }

    /** Sayfanın #vid dikdörtgeni (CSS px) → cihaz pikseli. iw/ih: sayfanın window.innerWidth/innerHeight'i. */
    fun rect(x: Double, y: Double, w: Double, h: Double, iw: Double, ih: Double, show: Boolean, fit: String) {
        fitMode = fit
        showWanted = show
        val sx = if (iw > 0 && web.width > 0) web.width / iw else 1.0
        val sy = if (ih > 0 && web.height > 0) web.height / ih else 1.0
        val nx = (x * sx).toInt(); val ny = (y * sy).toInt()
        val nw = (w * sx).toInt().coerceAtLeast(0); val nh = (h * sy).toInt().coerceAtLeast(0)
        val lp = holder.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != nx || lp.topMargin != ny || lp.width != nw || lp.height != nh) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = nx; lp.topMargin = ny; lp.width = nw; lp.height = nh
            holder.layoutParams = lp
        }
        refreshVisibility()
        applyFit()
    }

    fun background(color: Int) { root.setBackgroundColor(color) }

    // ───────────── Yaşam döngüsü ─────────────
    fun onHostStop() {
        val p = player ?: return
        resumeOnStart = loaded && p.playWhenReady
        if (resumeOnStart) p.playWhenReady = false
    }
    fun onHostStart() {
        if (resumeOnStart) { player?.playWhenReady = true; resumeOnStart = false }
    }
    fun release() {
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        loaded = false
        runCatching { root.removeView(holder) }
    }

    // ───────────── İç işler ─────────────
    private fun applyVolume(p: ExoPlayer) { p.volume = if (muted) 0f else volume }

    private fun refreshVisibility() {
        val lp = holder.layoutParams
        holder.visibility = if (loaded && showWanted && lp.width > 0 && lp.height > 0) View.VISIBLE else View.INVISIBLE
    }

    /** Yerleşim: contain = en-boy korunur ve ortalanır; cover = kırparak doldurur; fill = uzatır. */
    private fun applyFit() {
        val p = player ?: return
        val lp = holder.layoutParams
        val w = lp.width; val h = lp.height
        val sl = surface.layoutParams as FrameLayout.LayoutParams
        var nw = ViewGroup.LayoutParams.MATCH_PARENT
        var nh = ViewGroup.LayoutParams.MATCH_PARENT
        when (fitMode) {
            "cover" -> p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            "fill" -> p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            else -> {
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                if (videoRatio > 0f && w > 0 && h > 0) {
                    if (w.toFloat() / h > videoRatio) { nh = h; nw = (h * videoRatio).toInt() }
                    else { nw = w; nh = (w / videoRatio).toInt() }
                }
            }
        }
        if (sl.width != nw || sl.height != nh) {
            sl.width = nw; sl.height = nh; sl.gravity = Gravity.CENTER
            surface.layoutParams = sl
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!loaded) return
            pushState()
            checkFirstFrame()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun checkFirstFrame() {
        val p = player ?: return
        if (firstFrame || !hasVideoTrack) return
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (readySince == 0L) { readySince = now; return }
            if (now - readySince > 4500) {
                readySince = now
                if (!swTried) { swTried = true; rebuildSoftware() }
                else if (preferSw) {
                    val f = p.videoFormat
                    send(JSONObject().put("t", "dbg").put("m", "Video çözülemedi: " + (f?.sampleMimeType ?: "?") + " " + (f?.width ?: 0) + "x" + (f?.height ?: 0)))
                    swTried = false; readySince = Long.MAX_VALUE / 2   // tekrar mesaj verme
                }
            }
        } else readySince = 0L
    }

    /** Donanım dekoder görüntü vermediyse: oynatıcıyı yazılım dekoder öncelikli yeniden kur, kaldığı yerden devam et. */
    private fun rebuildSoftware() {
        val old = player ?: return
        val pos = old.currentPosition
        val live = old.isCurrentMediaItemLive
        preferSw = true
        handler.removeCallbacks(retryRun)
        old.release(); player = null
        val p = ensurePlayer()
        p.setMediaSource(buildSource(curUrl, lastUa, lastRef, lastKind))
        if (!live && pos > 1000) p.seekTo(pos)
        p.playWhenReady = true
        p.prepare()
        send(JSONObject().put("t", "dbg").put("m", "Yazılım dekoder deneniyor…"))
    }

    private val retryRun = Runnable {
        player?.let { it.prepare(); it.playWhenReady = true }
    }

    private fun curLevelIndex(p: ExoPlayer): Int {
        val f = p.videoFormat ?: return -1
        var best = -1
        for (i in levelRefs.indices) {
            val lf = levelRefs[i].first.getTrackFormat(levelRefs[i].second)
            if (lf.height == f.height) {
                if (lf.bitrate == f.bitrate) return i
                if (best < 0) best = i
            }
        }
        return best
    }

    private fun pushState() {
        val p = player ?: return
        if (!loaded) return
        val st = when (p.playbackState) {
            Player.STATE_IDLE -> 0
            Player.STATE_BUFFERING -> 1
            Player.STATE_READY -> 2
            else -> 3
        }
        val dur = p.duration
        val live = p.isCurrentMediaItemLive || (dur == C.TIME_UNSET && st != 0)
        var dp = -1L
        if (p.isCurrentMediaItemLive) {
            val tl = p.currentTimeline
            if (!tl.isEmpty) {
                val w = tl.getWindow(p.currentMediaItemIndex, Timeline.Window())
                if (w.defaultPositionMs != C.TIME_UNSET) dp = w.defaultPositionMs
            }
        }
        val j = JSONObject()
            .put("t", "st").put("s", st)
            .put("p", p.currentPosition.coerceAtLeast(0L))
            .put("d", if (dur == C.TIME_UNSET) -1L else dur)
            .put("b", p.bufferedPosition.coerceAtLeast(0L))
            .put("pw", p.playWhenReady && st != 3)
            .put("lv", live)
            .put("w", p.videoSize.width).put("h", p.videoSize.height)
            .put("df", dropped)
            .put("dp", dp)
            .put("lvc", curLevelIndex(p))
        send(j)
    }

    private fun pushTracks(tracks: Tracks) {
        val levels = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i)) levels.add(Pair(g, i))
        }
        levelRefs = levels
        hasVideoTrack = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        val audios = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        audioRefs = audios

        val la = JSONArray()
        var fps = 0f
        for ((g, i) in levels) {
            val f = g.getTrackFormat(i)
            if (f.frameRate > fps) fps = f.frameRate
            la.put(JSONObject().put("w", f.width).put("h", f.height).put("b", f.bitrate)
                .put("vc", f.codecs ?: "").put("fps", if (f.frameRate > 0) f.frameRate.toDouble() else 0.0))
        }
        val aa = JSONArray()
        audios.forEachIndexed { idx, g ->
            val f = g.getTrackFormat(0)
            aa.put(JSONObject().put("i", idx).put("n", f.label ?: "").put("l", f.language ?: "")
                .put("ch", f.channelCount.coerceAtLeast(0)).put("sel", g.isSelected))
        }
        // ses codec'i: ilk ses parçasından
        val ac = audios.firstOrNull { it.isSelected }?.getTrackFormat(0)?.codecs ?: audios.firstOrNull()?.getTrackFormat(0)?.codecs ?: ""
        for (k in 0 until la.length()) la.getJSONObject(k).put("ac", ac)
        val p = player
        send(JSONObject().put("t", "tr").put("levels", la).put("audio", aa)
            .put("lv", if (p != null) curLevelIndex(p) else -1).put("fps", fps.toDouble()))
    }

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {   // canlı yayında geride kaldı → canlıya dön
            p.seekToDefaultPosition(); p.prepare(); return
        }
        if (everReady && retries < MAX_SILENT_RETRIES) {          // yayın oynarken koptu: sayfaya hata dönmeden yeniden dene
            retries++
            handler.removeCallbacks(retryRun)
            handler.postDelayed(retryRun, 1500)
            return
        }
        val code = e.errorCode
        if (!everReady && !altTried && code in 3000..3999) {   // yayın türü yanlış tahmin edildi (HLS ↔ düz TS): bir kez diğer türle dene
            altTried = true
            val nk = if (lastKind == "hls") "raw" else "hls"
            lastKind = nk
            p.setMediaSource(buildSource(curUrl, lastUa, lastRef, nk))
            p.playWhenReady = true
            p.prepare()
            return
        }
        val cat = when {
            code == PlaybackException.ERROR_CODE_TIMEOUT ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            code in 2000..2999 -> "net"
            code in 3000..3999 -> "parse"
            code in 4000..5999 -> "decode"
            else -> "other"
        }
        var hc = 0
        var c: Throwable? = e.cause
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) { hc = c.responseCode; break }
            c = c.cause
        }
        send(JSONObject().put("t", "er").put("cat", cat).put("c", code).put("hc", hc).put("m", e.errorCodeName))
    }

    private fun send(j: JSONObject) {
        toJs("window.__umitNV&&window.__umitNV($j)")
    }
}
