package com.umitm0d.tvizle

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.PixelCopy
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
        private const val MAX_SILENT_RETRIES = 6      // oynarken kopmada sayfaya hata gitmeden önce sessiz deneme sayısı
        private const val MAX_RESOLVE_TRIES = 3       // Vavoo çözümü başarısızsa sayfaya hata vermeden önce deneme sayısı
        private const val MAX_MID_RESOLVES = 3        // oynarken hata sonrası yeniden çözme hakkı
        private const val VAVOO_REFRESH_MS = 6 * 60 * 1000L // yaklaşık 8 dk imza süresinden önce yenile
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val holder = FrameLayout(act)
    private val surface = SurfaceView(act)
    // Donarak kanal geçişi: önceki kanalın son karesi bu ImageView'de tutulur (SurfaceView'in ÜSTÜNDE, WebView'in ALTINDA)
    private val freezeImg = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    // 4K: SurfaceView/donanım overlay yolu bazı SoC'lerde (Allwinner) bozuk renk üretiyor; 4K'da GPU yolu (TextureView) kullanılır
    private var tex: TextureView? = null
    private var useTex = false
    private val vview: View get() = if (useTex) (tex ?: surface) else surface
    private fun setTexture(on: Boolean) {
        if (on == useTex) return
        val p = player ?: return
        useTex = on
        if (on) {
            val t = tex ?: TextureView(act).also {
                tex = it
                holder.addView(it, holder.indexOfChild(freezeImg).coerceAtLeast(0), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
            t.visibility = View.VISIBLE
            surface.visibility = View.GONE
            p.setVideoTextureView(t)
        } else {
            tex?.visibility = View.GONE
            surface.visibility = View.VISIBLE
            p.setVideoSurfaceView(surface)
        }
        applyFit()
    }

    private var forceTex = false          // SurfaceView görüntü vermedi: bu oturumda TextureView'da kal
    private var frameFix = 0              // görüntü gelmeme kurtarma aşaması (0: yok, 1: yüzey yeniden bağlandı, 2: TextureView)
    private var loaded = false           // bir kaynak yüklü mü (sayfa şeffaflığı / yüzey görünürlüğü için)
    private var everReady = false
    private var retries = 0
    private var curUrl = ""
    /** Kayıt için: oynatıcının şu an kullandığı (çözülmüş) adres ve başlıkları. */
    fun recSource(): Array<String> = arrayOf(curUrl, lastUa, lastRef)
    val isPlayingForPip: Boolean get() = loaded && (player?.isPlaying == true || player?.playWhenReady == true)
    // PiP için yalnızca isPlaying'e bağlı kalma: canlı yayın tamponlarken
    // player BUFFERING durumunda olabilir ama video yüzeyi ve medya kaynağı hazırdır.
    val hasMediaForPip: Boolean get() = loaded && player != null && player?.playbackState != Player.STATE_IDLE
    fun pipAspectRatio(): android.util.Rational {
        val w = videoRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val clamped = w.coerceIn(0.5f, 2.39f)
        return android.util.Rational((clamped * 1000).toInt().coerceAtLeast(500), 1000)
    }

    /** PiP sırasında çalışan video SurfaceView bağlantısını değiştirme.
     * Surface'i değiştirmek oynatıcıda ses devam ederken video çıkışını kaybettirebilir.
     * Yalnızca mevcut holder ve video görünümünü küçülen Activity penceresine uydur.
     */
    fun setPipMode(enabled: Boolean) {
        if (enabled) {
            holder.visibility = if (loaded) View.VISIBLE else View.GONE
            // bringToFront() KALDIRILDI: PiP'den sonra video katmanı WebView'in üstünde kalıp modalları örtüyordu.
            holder.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            holder.post {
                if (loaded) {
                    holder.visibility = View.VISIBLE
                    vview.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                    holder.requestLayout()
                    vview.requestLayout()
                    holder.invalidate()
                }
            }
        } else {
            // Video katmanı her zaman WebView'in ALTINDA (index 0) olmalı; modallar videonun üstünde görünsün.
            (holder.parent as? ViewGroup)?.let { par ->
                if (par.indexOfChild(holder) != 0) {
                    val lp = holder.layoutParams
                    par.removeView(holder)
                    par.addView(holder, 0, lp)
                }
            }
            reapply()
        }
    }
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

    // Vavoo: sayfanın verdiği adres Vavoo ise gerçek yayın adresine çevrilir (VavooResolver)
    private var resolveGen = 0            // eski/iptal edilmiş çözümlemelerin sonucunu yok saymak için
    private var vavooSrc = ""             // sayfadan gelen özgün Vavoo adresi ("" = Vavoo değil)
    private var vavooRetried = false      // yayın hiç açılmazsa bir kez adresi yeniden çözdür
    private var vavooMidRetries = 0       // oynarken kopmada sessiz yeniden çözme sayısı (kararlı oynayınca sıfırlanır)
    private var readyStableAt = 0L
    private var playedOk = false          // bu kanal (sayfanın load() çağrısı) için en az bir kez oynadı mı; yeniden çözmeler bunu SIFIRLAMAZ
    private var resolveTries = 0          // art arda başarısız Vavoo çözüm sayısı
    private var proactiveRefreshGen = 0
    private val proactiveVavooRefresh = object : Runnable {
        override fun run() {
            val src = vavooSrc
            val gen = proactiveRefreshGen
            if (!loaded || src.isBlank() || !VavooResolver.matches(src)) return
            VavooResolver.resolveAsync(src) { fresh ->
                handler.post {
                    if (!loaded || gen != proactiveRefreshGen || src != vavooSrc) return@post
                    if (fresh != null && fresh != curUrl) {
                        val p = player ?: return@post
                        val wasPlaying = p.playWhenReady
                        val live = p.isCurrentMediaItemLive
                        val pos = p.currentPosition
                        curUrl = fresh
                        p.setMediaSource(buildSource(fresh, VavooResolver.UA_APP, VavooResolver.REFERER, lastKind))
                        if (!live && pos > 0L) p.seekTo(pos) else p.seekToDefaultPosition()
                        p.playWhenReady = wasPlaying
                        p.prepare()
                        send(JSONObject().put("t", "dbg").put("m", "Yayın adresi kesinti öncesi yenilendi"))
                    }
                    // Çözüm sunucusu geçici hata verirse 30 sn sonra tekrar dene.
                    handler.postDelayed(this, if (fresh == null) 30_000L else VAVOO_REFRESH_MS)
                }
            }
        }
    }

    // Görüntü gelmeme koruması: ses çalıp ilk video karesi çizilmezse (donanım dekoder sorunu) yazılım dekoderine geç
    private var preferSw = act.getSharedPreferences("umittv", android.content.Context.MODE_PRIVATE).getString("dec", "") == "sw"   // sw: yazılım dekoder öncelikli (kalıcı tercih ya da donanım hata verince otomatik)
    private var decName = ""
    private var lastDiag = ""
    private var firstFrame = false
    private var readySince = 0L
    private var swTried = false
    private var hasVideoTrack = false
    init { DecoderTweaks.demoteAllwinner = act.getSharedPreferences("umittv", android.content.Context.MODE_PRIVATE).getString("dec", "") == "demoteAw" }
    private val codecSelector = DecoderTweaks.selector { preferSw }

    private var levelRefs: List<Pair<Tracks.Group, Int>> = emptyList()
    private var audioRefs: List<Tracks.Group> = emptyList()

    val isActive: Boolean get() = loaded

    init {
        holder.clipChildren = true
        holder.visibility = View.INVISIBLE
        holder.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        holder.addView(freezeImg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // WebView'in ALTINA (index 0): WebView saydam olduğu yerde video görünür
        root.addView(holder, 0, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
    }

    // ───────────── ExoPlayer ─────────────
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val renderers = DecoderTweaks.renderers(act)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val loadControl = ExoConfig.loadControl()   // harici oynatıcıyla aynı tampon ayarı
        val p = ExoPlayer.Builder(act, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false) // false: audio focus'u yönetme; WebView ses/önizleme odak alınca ExoPlayer sesi %20'ye düşüp geri gelmiyordu
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { everReady = true; playedOk = true; retries = 0; resolveTries = 0; if (readyStableAt == 0L) readyStableAt = android.os.SystemClock.elapsedRealtime() } else if (state != Player.STATE_BUFFERING) readyStableAt = 0L
                pushState()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = pushState()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = pushState()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = pushState()
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) applyDisplayMode(videoSize.height)
                if (videoSize.height >= 2000) setTexture(true) else if (videoSize.height in 1..1500) setTexture(forceTex)
                if (videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    applyFit()
                }
                pushState()
            }
            override fun onTracksChanged(tracks: Tracks) {
                pushTracks(tracks)
                if (audioRefs.isEmpty()) {
                    val bad = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && !it.isSupported }
                    if (bad.isNotEmpty()) send(JSONObject().put("t", "dbg")
                        .put("m", "Ses codec'i desteklenmiyor: " + (bad[0].getTrackFormat(0).sampleMimeType ?: "?")))
                }
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                newFrameSeen = true
                if (freezeHold && freezeEndWanted) handler.postDelayed({ if (freezeEndWanted) freezeClear() }, 90)   // yeni kare göründü: donuk kareyi yumuşakça kaldır
                send(JSONObject().put("t", "rf"))
                try {   // teşhis: hangi dekoder, hangi biçim/çözünürlük (sayfada kısa bildirim olarak görünür)
                    val f = player?.videoFormat
                    val d = (f?.sampleMimeType ?: "?").removePrefix("video/") + " " + (f?.width ?: 0) + "x" + (f?.height ?: 0) +
                        " | " + (if (decName.isEmpty()) "?" else decName) + (if (preferSw || DecoderTweaks.isSwName(decName)) " [SW]" else " [HW]")
                    if (d != lastDiag) { lastDiag = d; send(JSONObject().put("t", "dbg").put("m", d)) }
                } catch (_: Throwable) {}
            }
            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }
            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                healAudio("ses çıkışı hatası")
            }
            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                healAudio("ses dekoder hatası")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                                   initializedTimestampMs: Long, initializationDurationMs: Long) {
                decName = decoderName
            }
        })
        p.trackSelectionParameters = applyCap(p.trackSelectionParameters.buildUpon())
            .setPreferredAudioLanguage("tr")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)   // altyazıyı sayfa yönetiyor
            .build()
        if (useTex && tex != null) p.setVideoTextureView(tex) else p.setVideoSurfaceView(surface)
        applyVolume(p)
        p.setPlaybackSpeed(rate)
        player = p
        if (!ffmpegChecked) {   // teşhis: FFmpeg ses dekoderi yüklendi mi (bir kez bildirir)
            ffmpegChecked = true
            val ok = runCatching {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("isAvailable").invoke(null) as? Boolean
            }.getOrNull()
            send(JSONObject().put("t", "dbg").put("m", "FFmpeg ses: " + when (ok) { true -> "AÇIK"; false -> "yüklenemedi"; else -> "pakette yok" }))
        }
        handler.removeCallbacks(healRun); handler.postDelayed(healRun, 5000)
        return p
    }

    // Ses kendiliğinden kesilirse (ses parçası/dekoder düşmüş ama video akıyor) ses hattını yeniden kur
    private var lastHeal = 0L
    private var ffmpegChecked = false
    private val healRun = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && loaded && p.playbackState == Player.STATE_READY && p.playWhenReady &&
                p.audioFormat == null && audioRefs.isNotEmpty()) healAudio("ses parçası çalmıyor")
            handler.postDelayed(this, 5000)
        }
    }
    private fun healAudio(why: String) {
        val p = player ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeal < 8000L) return
        lastHeal = now
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        handler.postDelayed({
            player?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                applyVolume(it)
            }
        }, 300)
        send(JSONObject().put("t", "dbg").put("m", "Ses yeniden başlatılıyor: $why"))
    }

    private fun buildSource(url: String, ua: String, referer: String, kind: String): androidx.media3.exoplayer.source.MediaSource {
        val http = ExoConfig.http(ua, referer)          // harici oynatıcıyla aynı bağlantı ayarı
        val extractors = ExoConfig.extractors()
        val item = MediaItem.Builder().setUri(url)
        val low = url.lowercase()
        when {
            kind == "hls" || low.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        // Canlı yayında geçici parça/liste hatası oynatıcıyı düşürmesin: 8 deneme, 1 sn aralıkla (varsayılan 3 deneme, katlanarak artan bekleme)
        // (Eski satır-içi politika sonsuz yeniden denemeye yol açabiliyordu; ortak, sınırlı politika ExoConfig'de.)
        val policy = ExoConfig.loadErrorPolicy()
        if (low.contains(".m3u8") || kind == "hls") {
            // Canlı kenara fazla yapışma: Vavoo/CDN parçası gecikince oynatıcı hemen tampon boşaltmasın.
            // 15 sn hedef gecikme, ağ dalgalanmasında daha geniş toparlanma payı bırakır.
            item.setLiveConfiguration(MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(15000).setMinOffsetMs(8000).setMaxOffsetMs(45000)
                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build())
        }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(act, http), extractors)
        factory.setLoadErrorHandlingPolicy(policy)
        return factory.createMediaSource(item.build())
    }

    // ───────────── Sayfadan gelen komutlar (hepsi ana iş parçacığında çağrılır) ─────────────
    fun load(url: String, ua: String, referer: String, startMs: Long, autoplay: Boolean, kind: String) {
        val gen = ++resolveGen
        proactiveRefreshGen++
        handler.removeCallbacks(proactiveVavooRefresh)
        newFrameSeen = false      // bu yükleme için henüz kare çizilmedi (donuk kare bunu bekler)
        playedOk = false          // yeni kanal: her şey baştan (yeniden çözmeler bunu sıfırlamaz)
        if (!VavooResolver.matches(url)) {
            vavooSrc = ""
            loadResolved(url, ua, referer, startMs, autoplay, kind)
            return
        }
        vavooSrc = url
        vavooRetried = false; vavooMidRetries = 0; resolveTries = 0
        resolveVavoo(url, gen, startMs, autoplay, kind)
    }

    /** Vavoo adresini (önbellekten ya da arka planda) çözer, sonra normal yükleme yoluna girer. */
    private fun resolveVavoo(src: String, gen: Int, startMs: Long, autoplay: Boolean, kind: String) {
        VavooResolver.cached(src)?.let {
            loadResolved(it, VavooResolver.UA_APP, VavooResolver.REFERER, startMs, autoplay, kind)
            return
        }
        VavooResolver.resolveAsync(src) { r ->
            handler.post {
                if (gen != resolveGen) return@post          // bu arada başka kanal / durdur
                if (r != null) {
                    resolveTries = 0
                    loadResolved(r, VavooResolver.UA_APP, VavooResolver.REFERER, startMs, autoplay, kind)
                } else if (++resolveTries < MAX_RESOLVE_TRIES) {
                    // Vavoo imza/çözüm sunucusu geçici yanıt vermedi: sayfaya hata (=kanalı baştan yükle + logo) yerine kendimiz tekrar dene
                    send(JSONObject().put("t", "dbg").put("m", String.format(java.util.Locale.ROOT, ObfuscatedText.get(24), resolveTries, MAX_RESOLVE_TRIES)))
                    handler.postDelayed({ if (gen == resolveGen) resolveVavoo(src, gen, startMs, autoplay, kind) }, 1500L * resolveTries)
                } else {
                    resolveTries = 0
                    send(JSONObject().put("t", "er").put("cat", "net").put("c", 0).put("hc", 0)
                        .put("m", ObfuscatedText.get(25)))
                }
            }
        }
    }

    private fun loadResolved(url: String, ua: String, referer: String, startMs: Long, autoplay: Boolean, kind: String) {
        val p = ensurePlayer()
        curUrl = url; everReady = false; retries = 0; dropped = 0
        altTried = false; lastUa = ua; lastRef = referer; lastKind = kind
        firstFrame = false; readySince = 0L; swTried = false; hasVideoTrack = false; readyStableAt = 0L; frameFix = 0
        levelRefs = emptyList(); audioRefs = emptyList(); videoRatio = 0f
        loaded = true
        if (vavooSrc.isNotBlank() && VavooResolver.matches(vavooSrc)) {
            proactiveRefreshGen++
            handler.removeCallbacks(proactiveVavooRefresh)
            handler.postDelayed(proactiveVavooRefresh, VAVOO_REFRESH_MS)
        }
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
    // Çoklu ekran açılırken ana oynatıcının ses ve oynatma durumunu sakla;
    // kapanışta sayfanın eski mute olayları bu durumu ezmesin.
    private var gridSnapVol = -1f
    private var gridSnapMuted = false
    private var gridSnapPlaying = false
    private var gridEndAt = 0L
    fun gridBegin() {
        if (gridSnapVol >= 0f) return
        gridSnapVol = volume
        gridSnapMuted = muted
        gridSnapPlaying = player?.playWhenReady == true
    }
    fun gridEnd() {
        if (gridSnapVol < 0f) return
        // Çoklu ekrandan çıkınca ana yayın MUTLAKA duyulsun: sayfa ana videoyu çoklu ekrana girerken sessize aldığı için
        // anlık görüntü (snapshot) sessiz kalıyor ve çıkışta ses geri gelmiyordu.
        volume = if (gridSnapVol > 0.01f) gridSnapVol.coerceIn(0f, 1f) else 1f
        muted = false
        val restorePlaying = gridSnapPlaying || player?.isCurrentMediaItemLive == true
        gridSnapVol = -1f
        gridEndAt = android.os.SystemClock.elapsedRealtime()
        player?.let { p ->
            applyVolume(p)
            if (restorePlaying && loaded) {
                if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
                p.playWhenReady = true
            }
        }
    }
    fun volume(v: Double, m: Boolean) {
        // Çoklu ekran açıkken WebView'in grid hücrelerinden gelen mute/volume
        // olayları ana oynatıcının ses durumunu değiştirmesin. gridEnd() saklanan
        // ana ses durumunu tek noktadan geri yükler.
        if (gridSnapVol >= 0f) return
        // Kapanıştan hemen sonra sırada kalmış eski mute olayı da ana sesi kapatmasın.
        if (m && android.os.SystemClock.elapsedRealtime() - gridEndAt < 2500L) return
        val was = muted
        volume = v.toFloat().coerceIn(0f, 1f); muted = m; player?.let { applyVolume(it) }
        if (m != was) {
            val dbg = "SES-TANI ana: " + (if (m) "SESSİZ" else "AÇIK") + " (sayfa istedi, vol=" + (volume * 100).toInt() + ")"
            act.runOnUiThread { runCatching { web.evaluateJavascript("window.__vlmDbg&&window.__vlmDbg('" + dbg + "')", null) } }
        }
    }
    fun rate(r: Double) { rate = if (r > 0) r.toFloat() else 1f; player?.setPlaybackSpeed(rate) }
    fun retry() {
        val p = player ?: return
        handler.removeCallbacks(retryRun)
        p.prepare(); p.playWhenReady = true
    }

    fun stop() {
        resolveGen++
        handler.removeCallbacks(tick); handler.removeCallbacks(retryRun)
        loaded = false; everReady = false
        restoreDisplayMode()
        player?.let { it.stop(); it.clearMediaItems() }
        levelRefs = emptyList(); audioRefs = emptyList()
        refreshVisibility()
    }

    // ───────────── 4K: ekran çıkışını 2160p'ye al ─────────────
    // Kutu HDMI çıkışı 1080p iken 4K video küçültülerek ekrana verilir; ucuz SoC'lerde bu yol bozuk/renkli görüntü üretir.
    // 4K video açılınca (ve ekran 4K modu destekliyorsa) çıkış modu 3840x2160'a alınır, 4K'dan çıkınca eski moda dönülür.
    private var dispModeSet = false
    // TextureView yolu 4K'yı GPU ile ekrana ölçekliyor; ekranı 4K'ya almak GPU'yu (WebView + 4K katman) gereksiz yoruyor ve kasıyor. Kapalı.
    private val AUTO_4K_MODE = false
    private fun applyDisplayMode(h: Int) {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        try {
            val w = act.window
            val lp = w.attributes
            if (AUTO_4K_MODE && h >= 2000 && !dispModeSet) {
                @Suppress("DEPRECATION")
                val d = act.windowManager.defaultDisplay
                val cur = d.mode
                if (cur.physicalHeight >= 2160) return
                val best = d.supportedModes
                    .filter { it.physicalHeight >= 2160 && it.physicalWidth >= 3840 }
                    .minByOrNull { Math.abs(it.refreshRate - cur.refreshRate) } ?: return
                lp.preferredDisplayModeId = best.modeId
                w.attributes = lp
                dispModeSet = true
                send(JSONObject().put("t", "dbg").put("m", "Ekran çıkışı 4K moduna alındı (" + best.physicalWidth + "x" + best.physicalHeight + ")"))
            } else if (h in 1..1500) restoreDisplayMode()
        } catch (_: Throwable) {}
    }
    private fun restoreDisplayMode() {
        if (!dispModeSet || android.os.Build.VERSION.SDK_INT < 23) return
        try {
            val w = act.window
            val lp = w.attributes
            lp.preferredDisplayModeId = 0
            w.attributes = lp
        } catch (_: Throwable) {}
        dispModeSet = false
    }

    /** Sayfadan: "sw" = yazılım dekoder öncelikli, "hw" = donanım (varsayılan). Kalıcıdır. */
    fun setDecoder(mode: String) {
        val sw = mode == "sw"
        act.getSharedPreferences("umittv", android.content.Context.MODE_PRIVATE).edit().putString("dec", if (sw) "sw" else "hw").apply()
        if (sw == preferSw) return
        preferSw = sw
        val old = player ?: return
        if (!loaded) return
        val pos = old.currentPosition
        val live = old.isCurrentMediaItemLive
        handler.removeCallbacks(retryRun)
        old.release(); player = null
        val p = ensurePlayer()
        p.setMediaSource(buildSource(curUrl, lastUa, lastRef, lastKind))
        if (!live && pos > 1000) p.seekTo(pos)
        p.playWhenReady = true
        p.prepare()
        lastDiag = ""
    }

    /** Sayfadan gelen en yüksek çözünürlük sınırı (0 = varsayılan: TV'de 1080p, telefonda sınırsız). */
    private var maxH = 0
    private fun applyCap(b: androidx.media3.common.TrackSelectionParameters.Builder): androidx.media3.common.TrackSelectionParameters.Builder =
        if (maxH > 0) b.setMaxVideoSize(Int.MAX_VALUE, if (maxH >= 1080) 1088 else maxH)
        else DecoderTweaks.cap(act, b)
    fun setMaxHeight(h: Int) {
        maxH = if (h in 144..4320) h else 0
        val p = player ?: return
        p.trackSelectionParameters = applyCap(p.trackSelectionParameters.buildUpon()).build()
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
    private var rectPend: Runnable? = null
    private var lastRect: DoubleArray? = null
    /** WebView güncelleme şeridi vb. yüzünden aşağı itildiyse (üst/sol kenar boşluğu) video katmanı da aynı kadar kayar. */
    private fun webOffX(): Int = (web.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
    private fun webOffY(): Int = (web.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    /** WebView'in konumu değişince (şerit inip çıkarken) son bilinen dikdörtgeni anında yeni konuma taşır. */
    fun reapply() {
        val r = lastRect ?: return
        val iw = r[4]; val ih = r[5]
        val sx = if (iw > 0 && web.width > 0) web.width / iw else 1.0
        val sy = if (ih > 0 && web.height > 0) web.height / ih else 1.0
        val lp = holder.layoutParams as? FrameLayout.LayoutParams ?: return
        val nx = (r[0] * sx).toInt() + webOffX(); val ny = (r[1] * sy).toInt() + webOffY()
        if (lp.leftMargin != nx || lp.topMargin != ny) { lp.leftMargin = nx; lp.topMargin = ny; holder.layoutParams = lp }
    }
    fun rect(x: Double, y: Double, w: Double, h: Double, iw: Double, ih: Double, show: Boolean, fit: String) {
        // donuk kare gösterilirken (kanal geçişi) sayfanın "gizle" çağrısını yok say: son kare yerinde kalsın
        if (freezeHold && (!show || w <= 2.0 || h <= 2.0)) return
        fitMode = fit
        showWanted = show
        lastRect = doubleArrayOf(x, y, w, h, iw, ih)
        val sx = if (iw > 0 && web.width > 0) web.width / iw else 1.0
        val sy = if (ih > 0 && web.height > 0) web.height / ih else 1.0
        val nx = (x * sx).toInt() + webOffX(); val ny = (y * sy).toInt() + webOffY()
        val nw = (w * sx).toInt().coerceAtLeast(0); val nh = (h * sy).toInt().coerceAtLeast(0)
        val apply = Runnable {
            rectPend = null
            val lp = holder.layoutParams as FrameLayout.LayoutParams
            if (lp.leftMargin != nx || lp.topMargin != ny || lp.width != nw || lp.height != nh) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = nx; lp.topMargin = ny; lp.width = nw; lp.height = nh
                holder.layoutParams = lp
            }
            refreshVisibility()
            applyFit()
        }
        rectPend?.let { handler.removeCallbacks(it); rectPend = null }
        val cur = holder.layoutParams
        // 4K (TextureView): sayfa animasyonu (kanal listesi açılırken) 100 ms'de bir yeni boyut gönderiyor; her biri
        // yerleşim + GPU yeniden çizimi demek ve zayıf kutuda donma yapıyor. Animasyon bitince tek seferde uygula.
        if (useTex && show && cur.width > 0 && cur.height > 0 && nw > 0 && nh > 0) {
            rectPend = apply
            handler.postDelayed(apply, 180)
        } else apply.run()
    }

    fun background(color: Int) { root.setBackgroundColor(color) }

    // ───────────── Donarak kanal geçişi (eski hls.js oynatıcıdaki canvas karesinin yerine) ─────────────
    // Sayfa kanal değiştirirken freezeBegin() çağırır: o anki kare bitmap olarak alınıp video alanında tutulur,
    // stop()/yeni load() sırasında video alanı gizlenmez. Yeni yayının ilk karesi çizilince (ya da sayfa freezeEnd deyince) kaldırılır.
    private var freezeHold = false
    private var freezeEndWanted = false
    private var newFrameSeen = true
    private var freezeSeq = 0
    private val freezeTimeout = Runnable { freezeClear() }

    fun freezeBegin() {
        if (freezeHold) {   // art arda geçiş: eldeki donuk kare kalsın, süreyi uzat
            freezeEndWanted = false
            handler.removeCallbacks(freezeTimeout); handler.postDelayed(freezeTimeout, 8000)
            return
        }
        if (!loaded || !firstFrame || holder.visibility != View.VISIBLE) return
        val v = vview
        val w = v.width; val h = v.height
        if (w <= 0 || h <= 0) return
        freezeHold = true
        freezeEndWanted = false
        val seq = ++freezeSeq
        handler.removeCallbacks(freezeTimeout); handler.postDelayed(freezeTimeout, 8000)
        try {
            val sc = Math.min(1f, 960f / Math.max(w, h))
            val bmp = Bitmap.createBitmap(Math.max(2, (w * sc).toInt()), Math.max(2, (h * sc).toInt()), Bitmap.Config.ARGB_8888)
            val t = tex
            if (useTex && t != null) {
                t.getBitmap(bmp)
                showFreezeBitmap(bmp, seq)
            } else if (Build.VERSION.SDK_INT >= 24) {
                PixelCopy.request(surface, bmp, { res -> if (res == PixelCopy.SUCCESS) showFreezeBitmap(bmp, seq) }, handler)
            }
            // bitmap alınamazsa bile video alanı görünür kalır (yüzeyde son kare çoğu cihazda yerinde durur)
        } catch (_: Throwable) {}
    }

    private fun showFreezeBitmap(bmp: Bitmap, seq: Int) {
        if (!freezeHold || seq != freezeSeq) return
        freezeImg.animate().cancel()
        freezeImg.alpha = 1f
        freezeImg.setImageBitmap(bmp)
        freezeImg.visibility = View.VISIBLE
    }

    /** force: yeni yayın beklenmeden hemen bırak (sayfa artık ExoPlayer kullanmıyorsa). */
    fun freezeEnd(force: Boolean) {
        if (!freezeHold) return
        if (force || newFrameSeen) freezeClear()
        else {   // yeni yayının ilk karesini bekle; gelmezse kısa sürede bırak
            freezeEndWanted = true
            handler.removeCallbacks(freezeTimeout); handler.postDelayed(freezeTimeout, 2500)
        }
    }

    private fun freezeClear() {
        handler.removeCallbacks(freezeTimeout)
        if (!freezeHold) return
        freezeHold = false; freezeEndWanted = false
        freezeSeq++
        if (freezeImg.visibility == View.VISIBLE) {
            freezeImg.animate().alpha(0f).setDuration(160).withEndAction {
                if (!freezeHold) { freezeImg.visibility = View.GONE; freezeImg.setImageDrawable(null) }
            }.start()
        }
        refreshVisibility()
    }

    // ───────────── Ambient ışık: görüntüden küçük örnek (eski sayfa <video>'dan canvas'a çiziyordu, ExoPlayer'da <video> boş) ─────────────
    fun sample(w: Int, h: Int) {
        if (!loaded || !firstFrame || w < 1 || h < 1 || w > 64 || h > 64 || holder.visibility != View.VISIBLE) return
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val t = tex
            if (useTex && t != null) { t.getBitmap(bmp); pushSample(bmp) }
            else if (Build.VERSION.SDK_INT >= 24) PixelCopy.request(surface, bmp, { res -> if (res == PixelCopy.SUCCESS) pushSample(bmp) }, handler)
        } catch (_: Throwable) {}
    }

    private fun pushSample(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val a = JSONArray()
        for (c in px) { a.put((c shr 16) and 255); a.put((c shr 8) and 255); a.put(c and 255) }
        send(JSONObject().put("t", "amb").put("w", w).put("h", h).put("d", a))
    }

    // ───────────── Yaşam döngüsü ─────────────
    fun onHostStop() {
        val p = player ?: return
        resumeOnStart = loaded && p.playWhenReady
        if (resumeOnStart) p.playWhenReady = false
    }
    fun onHostStart() {
        if (loaded) handler.postDelayed({ if (loaded && !firstFrame) reattachSurface() }, 400)   // ön plana dönüşte yüzey yeniden kurulmuş olabilir
        if (resumeOnStart) { player?.playWhenReady = true; resumeOnStart = false }
    }
    fun release() {
        proactiveRefreshGen++
        handler.removeCallbacks(proactiveVavooRefresh)
        handler.removeCallbacksAndMessages(null)
        restoreDisplayMode()
        player?.release(); player = null
        loaded = false
        runCatching { root.removeView(holder) }
    }

    // ───────────── İç işler ─────────────
    private fun applyVolume(p: ExoPlayer) { p.volume = if (muted) 0f else volume }

    private fun refreshVisibility() {
        val lp = holder.layoutParams
        holder.visibility = if ((loaded || freezeHold) && showWanted && lp.width > 0 && lp.height > 0) View.VISIBLE else View.INVISIBLE
    }

    /** Yerleşim: contain = en-boy korunur ve ortalanır; cover = kırparak doldurur; fill = uzatır. */
    private fun applyFit() {
        val p = player ?: return
        val lp = holder.layoutParams
        val w = lp.width; val h = lp.height
        val vv = vview
        val sl = vv.layoutParams as FrameLayout.LayoutParams
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
            vv.layoutParams = sl
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!loaded) return
            pushState()
            checkFirstFrame()
            if ((vavooMidRetries > 0 || vavooRetried) && readyStableAt != 0L && android.os.SystemClock.elapsedRealtime() - readyStableAt > 30000L) { vavooMidRetries = 0; vavooRetried = false }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun checkFirstFrame() {
        val p = player ?: return
        if (firstFrame || !hasVideoTrack) return
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (readySince == 0L) { readySince = now; return }
            val el = now - readySince
            // 1. aşama (2,5 sn): ses çalıyor ama kare çizilmedi → video yüzeyini yeniden bağla (güncelleme/ön plana dönüş sonrası kopan yüzey)
            if (frameFix == 0 && el > 2500) { frameFix = 1; reattachSurface(); return }
            if (el > 4500) {
                readySince = now
                if (!swTried) { swTried = true; rebuildSoftware() }
                else if (frameFix < 2 && !useTex) {   // 3. aşama: SurfaceView hiç görüntü vermiyor → GPU yolu (TextureView)
                    frameFix = 2; forceTex = true; setTexture(true)
                    send(JSONObject().put("t", "dbg").put("m", "Görüntü yüzeyi TextureView'a alındı"))
                }
                else if (preferSw) {
                    val f = p.videoFormat
                    send(JSONObject().put("t", "dbg").put("m", "Video çözülemedi: " + (f?.sampleMimeType ?: "?") + " " + (f?.width ?: 0) + "x" + (f?.height ?: 0)))
                    swTried = false; readySince = Long.MAX_VALUE / 2   // tekrar mesaj verme
                }
            }
        } else readySince = 0L
    }

    /** Video çıkışını (SurfaceView / TextureView) oynatıcıya yeniden bağlar; yerleşimi ve görünürlüğü tazeler. */
    private fun reattachSurface() {
        val p = player ?: return
        try {
            if (useTex && tex != null) { p.clearVideoTextureView(tex); p.setVideoTextureView(tex) }
            else { p.clearVideoSurfaceView(surface); p.setVideoSurfaceView(surface) }
        } catch (_: Throwable) {}
        holder.requestLayout()
        refreshVisibility()
        applyFit()
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
        // Donanım dekoder çöktü/başlamadı (4000-4999): sayfaya hata (=logo + kanalı baştan yükle) göndermeden yazılım dekoderine geç
        if (e.errorCode in 4000..4999 && !preferSw) {
            send(JSONObject().put("t", "dbg").put("m", "Dekoder hatası (" + e.errorCodeName + "): yazılım dekoder"))
            rebuildSoftware()
            return
        }
        var hc0 = 0
        run { var c: Throwable? = e.cause; while (c != null) { if (c is HttpDataSource.InvalidResponseCodeException) { hc0 = c.responseCode; break }; c = c.cause } }
        val diag = e.errorCodeName + (if (hc0 > 0) " HTTP $hc0" else "")
        // NOT: eskiden burada everReady kullanılıyordu; yeniden çözme everReady'yi sıfırladığı için ikinci kopmada hata doğrudan sayfaya gidip
        // kanal baştan yükleniyordu (logo ekranı). playedOk yalnızca sayfanın yeni load()'unda sıfırlanır.
        if (playedOk) {
            // Vavoo imzalı adresinde 401/403/410 geldiyse aynı bayat URL'yi tekrar deneme;
            // önbelleği temizleyip gerçek yayın adresini yeniden çöz.
            if (vavooSrc.isNotEmpty() && hc0 in setOf(401, 403, 410) &&
                vavooMidRetries < MAX_MID_RESOLVES) {
                vavooMidRetries++
                send(JSONObject().put("t", "dbg").put("m", String.format(java.util.Locale.ROOT, ObfuscatedText.get(26), vavooMidRetries, MAX_MID_RESOLVES)))
                VavooResolver.invalidate(vavooSrc)
                resolveVavoo(vavooSrc, ++resolveGen, 0L, true, lastKind)
                return
            }
            // 1) ilk kopma: aynı adresle hızlı yeniden dene (ağ dalgalanması çoğu zaman budur)
            if (retries == 0) {
                retries++
                send(JSONObject().put("t", "dbg").put("m", "Yayın koptu ($diag): yeniden bağlanılıyor"))
                handler.removeCallbacks(retryRun)
                handler.postDelayed(retryRun, 800)
                return
            }
            // 2) Vavoo: token/adres bayatlamış olabilir → sessizce yeniden çözdür
            if (vavooSrc.isNotEmpty() && vavooMidRetries < MAX_MID_RESOLVES) {
                vavooMidRetries++
                send(JSONObject().put("t", "dbg").put("m", String.format(java.util.Locale.ROOT, ObfuscatedText.get(27), diag, vavooMidRetries, MAX_MID_RESOLVES)))
                VavooResolver.invalidate(vavooSrc)
                resolveVavoo(vavooSrc, ++resolveGen, 0L, true, lastKind)
                return
            }
            // 3) kalan sessiz denemeler, giderek artan bekleme ile
            if (retries < MAX_SILENT_RETRIES) {
                retries++
                handler.removeCallbacks(retryRun)
                handler.postDelayed(retryRun, (1000L * retries).coerceAtMost(4000L))
                return
            }
        } else if (vavooSrc.isNotEmpty() && !vavooRetried) {   // hiç açılmadı: adres bayatlamış olabilir, bir kez yeniden çözdür
            vavooRetried = true
            VavooResolver.invalidate(vavooSrc)
            resolveVavoo(vavooSrc, ++resolveGen, 0L, true, lastKind)
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
