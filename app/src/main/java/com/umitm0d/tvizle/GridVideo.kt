package com.umitm0d.tvizle

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.view.SurfaceHolder
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
        var refreshGen = 0; var refreshTask: Runnable? = null
        var lastRect: DoubleArray? = null
        // Görüntü gelmeme koruması (ana oynatıcıdaki gibi): ses çalıp kare çizilmezse yüzeyi yeniden bağla → TextureView → yazılım dekoder
        var tex: TextureView? = null; var useTex = false; var forceTex = false
        var preferSw = false
        var frameShown = false; var hasVideo = false
        var readySince = 0L; var frameFix = 0; var wgen = 0
        var revealed = false          // sayfaya 'ready' bildirildi mi (logo kalkar)
        var bufSince = 0L; var bgen = 0; var sgen = 0; var stgen = 0
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
        // Ana oynatıcıyla aynı SurfaceView katman sırasını kullan.
        // MediaOverlay, WebView'in saydam video penceresiyle çakışıp bazı cihazlarda
        // yalnızca ses duyulmasına ve video yüzeyinin siyah kalmasına yol açabiliyor.
        // Bu nedenle varsayılan SurfaceView sıralaması korunur.
        holder.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val c = Cell(holder, surface)
        // SurfaceView'in ilk oluşturulması sırasında holder INVISIBLE kalırsa bazı Android
        // sürümlerinde surfaceCreated hiç tetiklenmiyor. Yüzey hazır olduğunda ilgili
        // ExoPlayer'a açıkça yeniden bağla; her hücre kendi callback'ini taşır.
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                handler.post {
                    if (cells.getOrNull(i) !== c || !c.loaded) return@post
                    c.holder.visibility = View.VISIBLE
                    c.surface.visibility = View.VISIBLE
                    // TextureView aktifse SurfaceView'e yeniden bağlanma:
                    // bu, TextureView'e çizilen kareleri siyah yüzeyle değiştiriyordu.
                    if (!c.useTex) {
                        c.player?.let { p ->
                            runCatching { p.setVideoSurfaceView(c.surface) }
                        }
                    }
                    c.holder.requestLayout()
                    applyFit(c)
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) handler.post {
                    if (cells.getOrNull(i) === c && c.loaded && !c.useTex) {
                        c.player?.let { p -> runCatching { p.setVideoSurfaceView(c.surface) } }
                    }
                }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                // Media3 yüzey yok oluşunu kendi takip eder; eski yüzeye dokunma.
            }
        })
        val at = root.indexOfChild(web).let { if (it < 0) 0 else it }
        root.addView(holder, at, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        cells[i] = c
        return c
    }

    private fun ensurePlayer(i: Int, c: Cell): ExoPlayer {
        c.player?.let { return it }
        val sel = DecoderTweaks.selector { c.preferSw }
        val renderers = DecoderTweaks.renderers(act).setMediaCodecSelector(sel).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val lowEnd = DevicePerformance.isLowEnd(act)
        val lcBuilder = DefaultLoadControl.Builder().setBufferDurationsMs(
            if (lowEnd) 6000 else 8000,
            if (lowEnd) 16000 else 20000,
            1500,
            if (lowEnd) 2500 else 3000
        )
        if (lowEnd) lcBuilder.setTargetBufferBytes(8 * 1024 * 1024)
        val lc = lcBuilder.build()
        val p = ExoPlayer.Builder(act, renderers)
            .setLoadControl(lc)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Çoklu ekranda dekoder yükünü azalt. 2 GB TV Box'ta her hücre en fazla 540p.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if (lowEnd) 960 else 1280, if (lowEnd) 540 else 720)
            .setPreferredAudioLanguage("tr")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    c.everReady = true; c.retries = 0; c.bufSince = 0L; c.bgen++
                    // İlk kare çizildiyse (ya da yayın sadece sesse) hemen hazır de. Kare olayı gecikirse/hiç gelmezse
                    // 1,2 sn sonra yine de hazır de: aksi halde ses çalarken logo sonsuza dek kutuyu örter.
                    if (c.frameShown || !c.hasVideo) reveal(i, c)
                    else {
                        val sg = ++c.sgen
                        handler.postDelayed({
                            if (c.sgen == sg && c.loaded && c.player?.playbackState == Player.STATE_READY) reveal(i, c)
                        }, 1200)
                    }
                } else {
                    c.readySince = 0L
                    if (state == Player.STATE_BUFFERING) {
                        if (c.bufSince == 0L) c.bufSince = SystemClock.elapsedRealtime()
                        if (c.revealed) {
                            // Oynarken takılma: 1,2 sn'den uzun sürerse sayfa küçük yükleniyor halkası gösterir
                            val bg = ++c.bgen
                            handler.postDelayed({
                                if (c.bgen == bg && c.loaded && c.player?.playbackState == Player.STATE_BUFFERING)
                                    send(i, JSONObject().put("t", "st").put("s", "buf"))
                            }, 1200)
                        }
                    }
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                c.hasVideo = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            }
            override fun onRenderedFirstFrame() {
                c.frameShown = true
                if (!c.everReady) { c.everReady = true; c.retries = 0 }
                refresh(c)
                reveal(i, c)
                send(i, JSONObject().put("t", "fr"))
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

    /** Sayfaya "kutu hazır" bildirir; kutu logosu kalkar. Tekrar tekrar çağrılabilir (takılmadan kurtulunca da). */
    private fun reveal(i: Int, c: Cell) {
        c.revealed = true
        send(i, JSONObject().put("t", "ready"))
    }

    /** Takılma bekçisi: oynarken 15 sn'den uzun BUFFERING'de kalan canlı yayını yeniden hazırlar. */
    private fun watchStall(i: Int, c: Cell) {
        val g = ++c.stgen
        val r = object : Runnable {
            override fun run() {
                if (c.stgen != g || cells.getOrNull(i) !== c || !c.loaded) return
                val p = c.player
                if (p != null && c.everReady && p.playbackState == Player.STATE_BUFFERING && c.bufSince != 0L &&
                    SystemClock.elapsedRealtime() - c.bufSince > 15_000L) {
                    c.bufSince = SystemClock.elapsedRealtime()
                    send(i, JSONObject().put("t", "st").put("s", "rec"))
                    p.seekToDefaultPosition(); p.prepare(); p.playWhenReady = true
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(r, 2000)
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
        // Çoklu ekran kutuları WebView'in saydam katmanlarıyla üst üste geldiği için
        // SurfaceView bazı cihazlarda ses üretirken görüntüyü siyah bırakabiliyor.
        // Her kutuda TextureView'i yayın başlamadan önce bağla; böylece çözünürlük
        // değişimini beklemeden aynı composited view katmanında çizim yapılır.
        // TextureView bu cihazlarda (qcom OMX) ses var/görüntü yok sorununa yol açtı. Ana oynatıcıda kanıtlanmış
        // SurfaceView yolu kullanılır; kare gelmezse watchFrames() kendisi TextureView'e geçer, 4K'da onVideoSizeChanged geçer.
        c.forceTex = false
        setTexture(c, false)
        val g = ++c.rgen
        c.src = url; c.vavooRetried = false
        c.refreshGen++
        c.refreshTask?.let { handler.removeCallbacks(it) }; c.refreshTask = null
        c.kind = kind
        c.altTried = false; c.everReady = false; c.retries = 0; c.ratio = 0f
        c.frameShown = false; c.hasVideo = false; c.readySince = 0L; c.frameFix = 0
        c.revealed = false; c.bufSince = 0L; c.sgen++
        c.loaded = true
        p.stop()
        // gLoad bazen WebView'in ilk gRect ölçümünden önce gelir. Tutucu sıfır
        // ölçüdeyken SurfaceView oluşturulmasını beklemek yerine görünürlüğü aç;
        // ölçü geldiğinde rect() gerçek boyutu uygular.
        c.holder.visibility = View.VISIBLE
        c.surface.visibility = View.VISIBLE
        refresh(c)
        c.holder.post {
            if (cells.getOrNull(i) === c && c.loaded && !c.useTex && c.surface.holder.surface.isValid) {
                runCatching { c.player?.setVideoSurfaceView(c.surface) }
            }
        }
        startDiag(i, c)
        watchFrames(i, c)
        watchStall(i, c)
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
                else send(i, JSONObject().put("t", "er").put("c", -3).put("m", ObfuscatedText.get(25)))
            }
        }
    }

    private fun scheduleVavooRefresh(i: Int, c: Cell) {
        if (!VavooResolver.matches(c.src) || !c.loaded) return
        val g = ++c.refreshGen
        val task = object : Runnable {
            override fun run() {
                if (!c.loaded || c.refreshGen != g || cells.getOrNull(i) !== c) return
                val original = c.src
                // Aşama 1: yeni Vavoo adresini arka planda çöz.
                VavooResolver.resolveAsync(original) { fresh ->
                    if (fresh == null || fresh == c.url) {
                        handler.post {
                            if (c.loaded && c.refreshGen == g && c.src == original && cells.getOrNull(i) === c)
                                handler.postDelayed(this, if (fresh == null) 10_000L else 4 * 60 * 1000L)
                        }
                        return@resolveAsync
                    }
                    // Aşama 2: yeni adresi doğrula; doğrulanana kadar mevcut player devam eder.
                    VavooResolver.preflightAsync(fresh) { ready ->
                        handler.post {
                            if (!c.loaded || c.refreshGen != g || c.src != original || cells.getOrNull(i) !== c) return@post
                            if (ready) {
                                val p = c.player ?: return@post
                                val wasPlaying = p.playWhenReady
                                val live = p.isCurrentMediaItemLive
                                val pos = p.currentPosition
                                c.url = fresh; c.ua = VavooResolver.UA_APP; c.ref = VavooResolver.REFERER
                                p.setMediaSource(buildSource(c, c.kind))
                                if (!live && pos > 0L) p.seekTo(pos) else p.seekToDefaultPosition()
                                p.playWhenReady = wasPlaying
                                p.prepare()
                                handler.postDelayed(this, 4 * 60 * 1000L)
                            } else {
                                handler.postDelayed(this, 10_000L)
                            }
                        }
                    }
                }
            }
        }
        c.refreshTask = task
        handler.postDelayed(task, 4 * 60 * 1000L)
    }

    private fun startSource(c: Cell, url: String, ua: String, ref: String) {
        val p = c.player ?: return
        c.url = url; c.ua = ua; c.ref = ref
        p.setMediaSource(buildSource(c, c.kind))
        p.playWhenReady = true
        p.prepare()
        val i = cells.indexOf(c)
        if (i >= 0) scheduleVavooRefresh(i, c)
    }

    fun mute(i: Int, muted: Boolean) {
        val c = cell(i) ?: return
        c.muted = muted
        c.player?.volume = if (muted) 0f else 1f
    }

    fun stop(i: Int) {
        val c = cells.getOrNull(i) ?: return
        c.loaded = false; c.revealed = false; c.rgen++; c.wgen++; c.bgen++; c.sgen++; c.stgen++; c.refreshGen++
        c.refreshTask?.let { handler.removeCallbacks(it) }; c.refreshTask = null
        // Durdurulan hücrede yalnızca medyayı temizlemek video decoder/buffer belleğini
        // tutabilir. Hücre tekrar açıldığında ensurePlayer() yeni, temiz bir oynatıcı kurar.
        c.player?.let { p -> runCatching { p.release() } }
        c.player = null
        c.holder.visibility = View.INVISIBLE
        refresh(c)
    }

    fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        for (i in 0 until MAX_CELLS) {
            val c = cells[i] ?: continue
            c.loaded = false; c.showWanted = false; c.refreshGen++
            c.refreshTask?.let { handler.removeCallbacks(it) }; c.refreshTask = null
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

    /** Tanı satırı: sayfa yalnızca yavaş açılışta (6 sn+) küçük gri yazı olarak gösterir. */
    private fun dbg(i: Int, s: String) {
        send(i, JSONObject().put("t", "dg").put("m", s))
    }

    private fun startDiag(i: Int, c: Cell) {
        val g = ++c.gen
        c.t0 = SystemClock.elapsedRealtime()
        c.lastErr = ""
        val r = object : Runnable {
            override fun run() {
                if (c.gen != g || !c.loaded || c.frameShown || c.revealed) return
                val sec = (SystemClock.elapsedRealtime() - c.t0) / 1000
                val st = c.player?.let { stateName(it.playbackState) } ?: "yok"
                val sf = if (c.useTex) {
                    if (c.tex?.isAvailable == true) "Texture var" else "Texture YOK"
                } else {
                    if (c.surface.holder.surface.isValid) "Surface var" else "Surface YOK"
                }
                dbg(i, st + " · " + sf + " · " + sec + "s" + (if (c.lastErr.isNotEmpty()) " · " + c.lastErr else ""))
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
