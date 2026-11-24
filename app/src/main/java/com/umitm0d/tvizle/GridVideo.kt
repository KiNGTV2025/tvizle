package com.umitm0d.tvizle

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.json.JSONObject

/**
 * Çoklu ekran (2-4 kanal): her kutu için ayrı bir ExoPlayer + SurfaceView.
 * Yüzeyler WebView'in ARKASINDA durur; sayfa kutuların dikdörtgenini [rect] ile bildirir (ana oynatıcıyla aynı yöntem).
 * Native → sayfa: window.__umitGV({i:kutu, t:'ready'|'sz'|'er', ...})
 */
class GridVideo(
    private val act: Activity,
    private val root: FrameLayout,
    private val web: WebView,
    private val toJs: (String) -> Unit
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        private const val MAX_CELLS = 4
        private const val MAX_SILENT_RETRIES = 3
    }

    private class Cell(val holder: FrameLayout, val surface: SurfaceView) {
        var player: ExoPlayer? = null
        var loaded = false
        var showWanted = false
        var url = ""; var ua = ""; var ref = ""; var kind = ""
        var altTried = false; var everReady = false; var retries = 0
        var muted = true
        var ratio = 0f
        var resumeOnStart = false
        var gen = 0; var t0 = 0L; var lastErr = ""
        var src = ""; var rgen = 0; var vavooRetried = false
        var lastRect: DoubleArray? = null
        // Görüntü gelmeme koruması (ana oynatıcıdaki gibi): ses çalıp kare çizilmezse yüzeyi yeniden bağla → TextureView → yazılım dekoder
        var tex: TextureView? = null; var useTex = false; var forceTex = false
        var preferSw = false
        var frameShown = false; var hasVideo = false
        var readySince = 0L; var frameFix = 0; var wgen = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cells = arrayOfNulls<Cell>(MAX_CELLS)
    val hasActiveCells: Boolean get() = cells.any { it?.loaded == true }

    private fun cell(i: Int): Cell? {
        if (i < 0 || i >= MAX_CELLS) return null
        cells[i]?.let { return it }
        val holder = FrameLayout(act)
        holder.clipChildren = true
        holder.visibility = View.INVISIBLE
        val surface = SurfaceView(act)
        // Ana oynatıcının yüzeyinin ÜSTÜNDE (media overlay), WebView'in altında kalsın: aksi halde ana video katmanı kutuları örtüp "ses var, görüntü yok" yapıyordu
        surface.setZOrderMediaOverlay(true)
        holder.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val at = root.indexOfChild(web).let { if (it < 0) 0 else it }
        root.addView(holder, at, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        val c = Cell(holder, surface)
        cells[i] = c
        return c
    }

    private fun ensurePlayer(i: Int, c: Cell): ExoPlayer {
        c.player?.let { return it }
        val sel = DecoderTweaks.selector { c.preferSw }
        val renderers = DecoderTweaks.renderers(act).setMediaCodecSelector(sel).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(8000, 20000, 1500, 3000).build()
        val p = ExoPlayer.Builder(act, renderers)
            .setLoadControl(lc)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // çok kutuda dekoder yükünü azalt: en fazla 720p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1280, 720)
            .setPreferredAudioLanguage("tr")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    c.everReady = true; c.retries = 0
                    // Logo ancak ilk kare çizilince kalkar (yoksa ses gelir, ekran boş kalır). Videosuz (sadece ses) akışta hemen hazır de.
                    if (c.frameShown || !c.hasVideo) send(i, JSONObject().put("t", "ready"))
                } else if (state != Player.STATE_BUFFERING) c.readySince = 0L
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                c.hasVideo = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            }
            override fun onRenderedFirstFrame() {
                c.frameShown = true
                if (!c.everReady) { c.everReady = true; c.retries = 0 }
                refresh(c)
                send(i, JSONObject().put("t", "ready"))
            }
            override fun onVideoSizeChanged(vs: VideoSize) {
                if (vs.height >= 2000) setTexture(c, true)        // 4K: SurfaceView/HW overlay yolu bozuk/boş çıkabiliyor → GPU yolu
                else if (vs.height in 1..1500) setTexture(c, c.forceTex)
                if (vs.height > 0) {
                    c.ratio = vs.width * vs.pixelWidthHeightRatio / vs.height
                    applyFit(c)
                    send(i, JSONObject().put("t", "sz").put("w", vs.width).put("h", vs.height))
                }
            }
            override fun onPlayerError(e: PlaybackException) = handleError(i, c, e)
        })
        val t = c.tex
        if (c.useTex && t != null) p.setVideoTextureView(t) else p.setVideoSurfaceView(c.surface)
        p.volume = if (c.muted) 0f else 1f
        c.player = p
        return p
    }

    private fun buildSource(c: Cell, kind: String): androidx.media3.exoplayer.source.MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setUserAgent(if (c.ua.isNotBlank()) c.ua else UA)
        if (c.ref.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to c.ref))
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val item = MediaItem.Builder().setUri(c.url)
        val low = c.url.lowercase()
        when {
            kind == "hls" || low.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(act, http), extractors)
            .setLoadErrorHandlingPolicy(ExoConfig.loadErrorPolicy())
            .createMediaSource(item.build())
    }

    // ───────────── Sayfadan gelen komutlar (ana iş parçacığı) ─────────────
    fun load(i: Int, url: String, ua: String, ref: String, kind: String) {
        val c = cell(i) ?: return
        val p = ensurePlayer(i, c)
        val g = ++c.rgen
        c.src = url; c.vavooRetried = false
        c.kind = kind
        c.altTried = false; c.everReady = false; c.retries = 0; c.ratio = 0f
        c.frameShown = false; c.hasVideo = false; c.readySince = 0L; c.frameFix = 0
        c.loaded = true
        p.stop()
        refresh(c)
        startDiag(i, c)
        watchFrames(i, c)
        if (!VavooResolver.matches(url)) {
            startSource(c, url, ua, ref)
            return
        }
        // Vavoo adresleri düz oynatılamaz: tek kanal oynatıcıdaki gibi önce çözülür (yoksa kutu sonsuza dek BUFFERING'de kalır)
        resolveThenStart(i, c, g)
    }

    private fun resolveThenStart(i: Int, c: Cell, g: Int) {
        val src = c.src
        VavooResolver.cached(src)?.let {
            startSource(c, it, VavooResolver.UA_APP, VavooResolver.REFERER); return
        }
        VavooResolver.resolveAsync(src) { r ->
            handler.post {
                if (c.rgen != g || !c.loaded) return@post
                if (r != null) startSource(c, r, VavooResolver.UA_APP, VavooResolver.REFERER)
                else send(i, JSONObject().put("t", "er").put("c", -3).put("m", "VAVOO_RESOLVE"))
            }
        }
    }

    private fun startSource(c: Cell, url: String, ua: String, ref: String) {
        val p = c.player ?: return
        c.url = url; c.ua = ua; c.ref = ref
        p.setMediaSource(buildSource(c, c.kind))
        p.playWhenReady = true
        p.prepare()
    }

    fun mute(i: Int, muted: Boolean) {
        val c = cell(i) ?: return
        c.muted = muted
        c.player?.volume = if (muted) 0f else 1f
        android.widget.Toast.makeText(act, "SES-TANI kutu " + (i + 1) + ": " + (if (muted) "SESSİZ" else "AÇIK"), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun stop(i: Int) {
        val c = cells.getOrNull(i) ?: return
        c.loaded = false; c.rgen++; c.wgen++
        c.player?.let { it.stop(); it.clearMediaItems() }
        refresh(c)
    }

    fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        for (i in 0 until MAX_CELLS) {
            val c = cells[i] ?: continue
            c.loaded = false; c.showWanted = false
            c.player?.release(); c.player = null
            c.holder.visibility = View.INVISIBLE
            root.removeView(c.holder)
            cells[i] = null
        }
    }

    private fun webOffX(): Int = (web.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
    private fun webOffY(): Int = (web.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    /** WebView güncelleme şeridi yüzünden aşağı itilince ızgara hücrelerini aynı kadar kaydırır. */
    fun reapply() {
        for (c in cells) {
            if (c == null) continue
            val r = c.lastRect ?: continue
            val sx = if (r[4] > 0 && web.width > 0) web.width / r[4] else 1.0
            val sy = if (r[5] > 0 && web.height > 0) web.height / r[5] else 1.0
            val lp = c.holder.layoutParams as? FrameLayout.LayoutParams ?: continue
            val nx = (r[0] * sx).toInt() + webOffX(); val ny = (r[1] * sy).toInt() + webOffY()
            if (lp.leftMargin != nx || lp.topMargin != ny) { lp.leftMargin = nx; lp.topMargin = ny; c.holder.layoutParams = lp }
        }
    }

    fun rect(i: Int, x: Double, y: Double, w: Double, h: Double, iw: Double, ih: Double, show: Boolean) {
        val c = cell(i) ?: return
        c.lastRect = doubleArrayOf(x, y, w, h, iw, ih)
        val sx = if (iw > 0 && web.width > 0) web.width / iw else 1.0
        val sy = if (ih > 0 && web.height > 0) web.height / ih else 1.0
        val nx = (x * sx).toInt() + webOffX(); val ny = (y * sy).toInt() + webOffY()
        val nw = (w * sx).toInt().coerceAtLeast(0); val nh = (h * sy).toInt().coerceAtLeast(0)
        val lp = c.holder.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != nx || lp.topMargin != ny || lp.width != nw || lp.height != nh) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = nx; lp.topMargin = ny; lp.width = nw; lp.height = nh
            c.holder.layoutParams = lp
        }
        c.showWanted = show
        refresh(c)
        applyFit(c)
    }

    // ───────────── Yaşam döngüsü ─────────────
    fun onHostStop() {
        for (c in cells) {
            val p = c?.player ?: continue
            c.resumeOnStart = c.loaded && p.playWhenReady
            if (c.resumeOnStart) p.playWhenReady = false
        }
    }
    fun onHostStart() {
        for (c in cells) {
            if (c != null && c.resumeOnStart) { c.player?.playWhenReady = true; c.resumeOnStart = false }
            if (c != null) handler.postDelayed({ if (c.loaded && !c.frameShown) reattach(c) }, 400)   // ön plana dönüşte yüzey yeniden kurulmuş olabilir
        }
    }
    fun release() { stopAll() }

    // ───────────── İç işler ─────────────
    private fun refresh(c: Cell) {
        val lp = c.holder.layoutParams
        // Sayfa kutuyu ancak 'ready' gelince göstermek ister (logo kalkınca show=true). Ama kutu INVISIBLE iken SurfaceView'in
        // yüzeyi oluşmaz; yeni Media3 (1.9+) yüzey olmadan hazır olmadığı için 'ready' hiç gelmez ve kutu sonsuza dek
        // "Açmaya çalışıyorum.." der. Bu yüzden ilk hazır oluşa kadar (yükleme sırasında) kutu görünür kalır; üstünü sayfadaki logo örter.
        val visible = c.loaded && lp.width > 0 && lp.height > 0 && (c.showWanted || !c.frameShown)
        c.holder.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun applyFit(c: Cell) {
        val lp = c.holder.layoutParams
        val w = lp.width; val h = lp.height
        val vv = vview(c)
        val sl = vv.layoutParams as FrameLayout.LayoutParams
        var nw = ViewGroup.LayoutParams.MATCH_PARENT
        var nh = ViewGroup.LayoutParams.MATCH_PARENT
        if (c.ratio > 0f && w > 0 && h > 0) {   // contain: en-boy korunur, ortalanır
            if (w.toFloat() / h > c.ratio) { nh = h; nw = (h * c.ratio).toInt() }
            else { nw = w; nh = (w / c.ratio).toInt() }
        }
        if (sl.width != nw || sl.height != nh) {
            sl.width = nw; sl.height = nh; sl.gravity = Gravity.CENTER
            vv.layoutParams = sl
        }
    }

    private fun vview(c: Cell): View = if (c.useTex) (c.tex ?: c.surface) else c.surface

    private fun setTexture(c: Cell, on: Boolean) {
        if (on == c.useTex) return
        val p = c.player ?: return
        c.useTex = on
        if (on) {
            val t = c.tex ?: TextureView(act).also {
                c.tex = it
                c.holder.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
            t.visibility = View.VISIBLE
            c.surface.visibility = View.GONE
            p.setVideoTextureView(t)
        } else {
            c.tex?.visibility = View.GONE
            c.surface.visibility = View.VISIBLE
            p.setVideoSurfaceView(c.surface)
        }
        applyFit(c)
    }

    private fun reattach(c: Cell) {
        val p = c.player ?: return
        try {
            val t = c.tex
            if (c.useTex && t != null) { p.clearVideoTextureView(t); p.setVideoTextureView(t) }
            else { p.clearVideoSurfaceView(c.surface); p.setVideoSurfaceView(c.surface) }
        } catch (_: Throwable) {}
        c.holder.requestLayout()
        refresh(c)
        applyFit(c)
    }

    private fun rebuildSoftware(i: Int, c: Cell) {
        val old = c.player ?: return
        c.preferSw = true
        c.player = null
        old.release()
        val p = ensurePlayer(i, c)
        p.setMediaSource(buildSource(c, c.kind))
        p.playWhenReady = true
        p.prepare()
    }

    /** Ses çalıyor ama ilk kare çizilmiyorsa kademeli kurtarma: yüzeyi yeniden bağla (2,5 sn) → TextureView (3 sn) → yazılım dekoder (3,5 sn). */
    private fun watchFrames(i: Int, c: Cell) {
        val g = ++c.wgen
        val r = object : Runnable {
            override fun run() {
                if (c.wgen != g || !c.loaded || c.frameShown) return
                val p = c.player
                if (p != null && c.hasVideo && p.playbackState == Player.STATE_READY && p.playWhenReady) {
                    val now = SystemClock.elapsedRealtime()
                    if (c.readySince == 0L) c.readySince = now
                    else {
                        val el = now - c.readySince
                        when {
                            c.frameFix == 0 && el > 2500 -> { c.frameFix = 1; c.readySince = now; reattach(c) }
                            c.frameFix == 1 && el > 3000 -> {
                                c.frameFix = 2; c.readySince = now
                                if (!c.useTex) { c.forceTex = true; setTexture(c, true) } else reattach(c)
                            }
                            c.frameFix == 2 && el > 3500 -> { c.frameFix = 3; c.readySince = now; rebuildSoftware(i, c) }
                        }
                    }
                } else c.readySince = 0L
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(r, 500)
    }

    private fun handleError(i: Int, c: Cell, e: PlaybackException) {
        val p = c.player ?: return
        c.lastErr = e.errorCodeName + (e.cause?.message?.let { ": " + it.take(70) } ?: "")
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
        if (c.everReady && c.retries < MAX_SILENT_RETRIES) {
            c.retries++
            handler.postDelayed({ c.player?.let { it.prepare(); it.playWhenReady = true } }, 1500)
            return
        }
        if (!c.everReady && !c.vavooRetried && VavooResolver.matches(c.src)) {   // süresi dolmuş/bozuk çözülmüş adres: bir kez yeniden çözdür
            c.vavooRetried = true
            VavooResolver.invalidate(c.src)
            resolveThenStart(i, c, ++c.rgen)
            return
        }
        if (!c.everReady && !c.altTried && e.errorCode in 3000..3999) {   // HLS ↔ düz TS yanlış tahmini: bir kez diğeriyle dene
            c.altTried = true
            c.kind = if (c.kind == "hls") "raw" else "hls"
            p.setMediaSource(buildSource(c, c.kind))
            p.playWhenReady = true
            p.prepare()
            return
        }
        send(i, JSONObject().put("t", "er").put("c", e.errorCode).put("m", e.errorCodeName))
    }


    // ───────────── Tanı: hücredeki "Açmaya çalışıyorum.." metnine canlı durum yazar; 20 sn hazır olmazsa hata bildirir ─────────────
    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "?"
    }

    private fun dbg(i: Int, s: String) {
        val q = JSONObject.quote(s)
        toJs("(function(){try{var l=document.querySelectorAll('.umit-grid-logo')[$i];" +
            "if(!l||l.style.visibility==='hidden')return;var m=l.lastElementChild;" +
            "if(m&&/^Açmaya/.test(m.textContent))m.textContent='Açmaya çalışıyorum.. ['+$q+']';}catch(e){}})()")
    }

    private fun startDiag(i: Int, c: Cell) {
        val g = ++c.gen
        c.t0 = SystemClock.elapsedRealtime()
        c.lastErr = ""
        val r = object : Runnable {
            override fun run() {
                if (c.gen != g || !c.loaded || c.frameShown) return
                val sec = (SystemClock.elapsedRealtime() - c.t0) / 1000
                val st = c.player?.let { stateName(it.playbackState) } ?: "yok"
                val sf = if (c.surface.holder.surface.isValid) "var" else "YOK"
                dbg(i, st + " · yüzey " + sf + " · " + sec + "s" + (if (c.lastErr.isNotEmpty()) " · " + c.lastErr else ""))
                if (sec >= 30) { send(i, JSONObject().put("t", "er").put("c", -1).put("m", "zaman aşımı: $st yüzey $sf ${c.lastErr}")); return }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(r)
    }

    /** MainActivity: http(s) olmayan adres geldiyse sayfaya hata bildir (sessizce yutulmasın). */
    fun reject(i: Int, url: String) {
        dbg(i, "adres http değil: " + url.take(40))
        send(i, JSONObject().put("t", "er").put("c", -2).put("m", "bad url"))
    }

    private fun send(i: Int, j: JSONObject) {
        j.put("i", i)
        toJs("window.__umitGV&&window.__umitGV($j)")
    }
}
