package com.umitm0d.tvizle

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ÜmitTV – assets/umittv_core.dat'i tam ekran WebView'de açar.
 *  v1.1: mikrofon (sesli komut), dosya ekleme, direksiyon tuşlarıyla kanal değiştirme. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_FILE = 4101
        private const val REQ_AUDIO = 4102
        private const val REQ_STORAGE = 4103
        /** true yapılırsa basılan her tuşun kodu ekranda gösterilir (direksiyon tuşu teşhisi için). */
        private const val SHOW_KEYCODES = false
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    // dosya seçici
    private var fileCb: ValueCallback<Array<Uri>>? = null

    // mikrofon
    private var pendingPermReq: PermissionRequest? = null
    private var pendingSpeech: (() -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null
    private var recGen = 0

    // direksiyon tuşları
    private var mediaSession: MediaSession? = null
    private var lastZap = 0L
    private var btnReceiverOn = false

    private val match = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true }
        root.addView(web, match)
        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowFileAccessFromFileURLs = true        // yayın/API isteklerinde CORS engeli olmasın
            allowUniversalAccessFromFileURLs = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // sayfadaki webkitSpeechRecognition → yerel SpeechRecognizer köprüsü (umit_native.js kullanır)
        web.addJavascriptInterface(SpeechBridge(), "UmitNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                return when {
                    u.startsWith("http://action_exit") -> { finish(); true }       // sayfanın çıkış sinyali
                    u.startsWith("intent:") -> { openIntent(u); true }             // harici oynatıcı (VLC/MX/Just Player)
                    u.startsWith("http:") || u.startsWith("https:") || u.startsWith("file:") ||
                        u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:") -> false
                    else -> { runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; true }
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = v; customCb = cb
                web.visibility = View.GONE
                root.addView(v, match)
            }
            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden(); customCb = null
                web.visibility = View.VISIBLE
                web.requestFocus()
            }

            /** getUserMedia vb. mikrofon istekleri */
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) { request.deny(); return }
                if (hasAudioPerm()) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingPermReq?.deny()
                    pendingPermReq = request
                    requestAudioPerm()
                }
            }

            /** <input type="file"> – yerel M3U/M3U8/TXT, altyazı, yedek dosyası vb. */
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    startActivityForResult(i, REQ_FILE)
                } catch (e: Exception) {
                    // Araç head unit'lerinde çoğu zaman sistem dosya seçici yok → yerleşik gezgin
                    pickFileFallback()
                }
                return true
            }
        }

        setupMediaSession()
        askAudioOnce()

        web.loadUrl("file:///android_asset/umittv_core.dat")
        web.requestFocus()
    }

    // ─────────────────────────────────────────────────────────────
    //  İzinler
    // ─────────────────────────────────────────────────────────────
    private fun hasAudioPerm(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPerm() {
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    /** Mikrofon iznini ilk açılışta bir kez sor, sonra her açılışta rahatsız etme. */
    private fun askAudioOnce() {
        val sp = getSharedPreferences("umittv", MODE_PRIVATE)
        if (!hasAudioPerm() && !sp.getBoolean("audio_asked", false)) {
            sp.edit().putBoolean("audio_asked", true).apply()
            requestAudioPerm()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
        when (code) {
            REQ_AUDIO -> {
                pendingPermReq?.let {
                    if (ok) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny()
                }
                pendingPermReq = null
                val p = pendingSpeech; pendingSpeech = null
                if (ok) p?.invoke() else if (p != null) {
                    toast("Mikrofon izni verilmedi – Ayarlar > Uygulamalar > ÜmitTV > İzinler")
                    jsSR("error", JSONObject().put("error", "not-allowed")); jsSR("end")
                }
            }
            REQ_STORAGE -> showDirDialog(Environment.getExternalStorageDirectory())
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Sesli komut: JS ⇄ SpeechRecognizer köprüsü
    // ─────────────────────────────────────────────────────────────
    inner class SpeechBridge {
        @JavascriptInterface
        fun startListening(lang: String, interim: Boolean, maxAlt: Int) {
            runOnUiThread {
                if (web.url?.startsWith("file:///android_asset/") != true) return@runOnUiThread
                startSpeech(lang, interim, maxAlt)
            }
        }
        @JavascriptInterface
        fun stopListening() { runOnUiThread { runCatching { recognizer?.stopListening() } } }
        @JavascriptInterface
        fun cancelListening() { runOnUiThread { destroyRecognizer() } }
        @JavascriptInterface
        fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
    }

    private fun jsSR(type: String, data: JSONObject = JSONObject()) {
        web.evaluateJavascript("window.__umitSR&&window.__umitSR('$type',$data)", null)
    }

    private fun destroyRecognizer() {
        recGen++
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun startSpeech(lang: String, interim: Boolean, maxAlt: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Cihazda ses tanıma servisi yok (Google uygulaması / Google ile Ses gerekli)")
            jsSR("error", JSONObject().put("error", "service-not-allowed")); jsSR("end")
            return
        }
        if (!hasAudioPerm()) {
            pendingSpeech = { startSpeech(lang, interim, maxAlt) }
            requestAudioPerm()
            return
        }
        destroyRecognizer()
        val gen = recGen
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val tag = if (lang.length == 2) lang.lowercase() + "-" + lang.uppercase() else lang

        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == recGen
            override fun onReadyForSpeech(p: Bundle?) { if (live()) { jsSR("start"); jsSR("audiostart") } }
            override fun onBeginningOfSpeech() { if (live()) jsSR("speechstart") }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { if (live()) jsSR("speechend") }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                if (!live()) return
                val a = alts(b)
                if (a.length() > 0)
                    jsSR("result", JSONObject().put("alts", a).put("final", false))
            }
            override fun onResults(b: Bundle?) {
                if (!live()) return
                val a = alts(b)
                if (a.length() > 0) jsSR("result", JSONObject().put("alts", a).put("final", true))
                jsSR("end")
            }
            override fun onError(e: Int) {
                if (!live()) return
                val code = when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "no-speech"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "network"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_CLIENT -> "aborted"
                    12, 13 -> "language-not-supported"
                    else -> "service-not-allowed"
                }
                jsSR("error", JSONObject().put("error", code))
                jsSR("end")
            }
        })

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlt.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            destroyRecognizer()
            jsSR("error", JSONObject().put("error", "service-not-allowed")); jsSR("end")
        }
    }

    private fun alts(b: Bundle?): JSONArray {
        val arr = JSONArray()
        if (b == null) return arr
        val l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return arr
        val c = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        for (i in l.indices) {
            arr.put(JSONObject().put("t", l[i]).put("c", (c?.getOrNull(i) ?: 0.9f).toDouble()))
        }
        return arr
    }

    // ─────────────────────────────────────────────────────────────
    //  Dosya ekleme
    // ─────────────────────────────────────────────────────────────
    private fun finishFile(uris: Array<Uri>?) {
        fileCb?.onReceiveValue(uris)
        fileCb = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_FILE) {
            val r = WebChromeClient.FileChooserParams.parseResult(res, data)
            finishFile(r)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
    }

    /** Sistem dosya seçicisi olmayan cihazlar için basit yerleşik dosya gezgini (USB dahil). */
    private fun pickFileFallback() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        showDirDialog(Environment.getExternalStorageDirectory())
    }

    private fun showDirDialog(dir: File) {
        val items = ArrayList<Pair<String, File>>()
        dir.parentFile?.let { items.add(Pair("⬆  ..  (üst klasör)", it)) }
        val kids = (dir.listFiles() ?: emptyArray<File>())
            .filter { !it.name.startsWith(".") && it.canRead() }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        for (k in kids) items.add(Pair((if (k.isDirectory) "📁  " else "📄  ") + k.name, k))

        AlertDialog.Builder(this)
            .setTitle(dir.absolutePath)
            .setItems(items.map { it.first }.toTypedArray()) { d, which ->
                val f = items[which].second
                d.dismiss()
                if (f.isDirectory) showDirDialog(f) else finishFile(arrayOf(Uri.fromFile(f)))
            }
            .setNegativeButton("İptal") { _, _ -> finishFile(null) }
            .setOnCancelListener { finishFile(null) }
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    //  Direksiyon tuşları → kanal değiştir
    // ─────────────────────────────────────────────────────────────
    /** +1: sonraki kanal, -1: önceki kanal, 0: ilgisiz tuş */
    private fun zapDirection(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 272 /* MEDIA_SKIP_FORWARD */ -> 1
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 273 /* MEDIA_SKIP_BACKWARD */ -> -1
        else -> 0
    }

    /** Canlı yayında ChannelUp/Down, VOD'da MediaTrackNext/Previous (ileri/geri sar) olarak sayfaya iletir. */
    private fun zap(next: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastZap < 120) return        // aynı basışın birden fazla yoldan gelmesini engelle
        lastZap = now
        val js = "(function(n){try{" +
            "var live=(typeof mediaMode!=='undefined'&&mediaMode==='live');" +
            "var key=live?(n?'ChannelUp':'ChannelDown'):(n?'MediaTrackNext':'MediaTrackPrevious');" +
            "var kc=live?(n?166:167):(n?87:88);" +
            "var o={key:key,code:key,keyCode:kc,which:kc,bubbles:true,cancelable:true};" +
            "var t=document.activeElement||document.body;" +
            "t.dispatchEvent(new KeyboardEvent('keydown',o));" +
            "t.dispatchEvent(new KeyboardEvent('keyup',o));" +
            "}catch(e){}})(" + (if (next) "true" else "false") + ")"
        web.evaluateJavascript(js, null)
    }

    private fun playPause() {
        web.evaluateJavascript(
            "(function(){try{var o={key:'MediaPlayPause',code:'MediaPlayPause',keyCode:179,which:179,bubbles:true,cancelable:true};" +
                "var t=document.activeElement||document.body;t.dispatchEvent(new KeyboardEvent('keydown',o));" +
                "t.dispatchEvent(new KeyboardEvent('keyup',o));}catch(e){}})()", null
        )
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (SHOW_KEYCODES && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            toast("Tuş kodu: ${e.keyCode} (${KeyEvent.keyCodeToString(e.keyCode)})")
        val dir = zapDirection(e.keyCode)
        if (dir != 0) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) zap(dir > 0)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** Bazı head unit'ler direksiyon tuşlarını MediaSession üzerinden aktif medya uygulamasına gönderir. */
    private fun setupMediaSession() {
        try {
            val s = MediaSession(this, "UmitTV")
            @Suppress("DEPRECATION")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            s.setCallback(object : MediaSession.Callback() {
                override fun onSkipToNext() { zap(true) }
                override fun onSkipToPrevious() { zap(false) }
                override fun onPlay() { playPause() }
                override fun onPause() { playPause() }
            })
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "ÜmitTV")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Canlı TV")
                    .build()
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            mediaSession = s
        } catch (e: Exception) { mediaSession = null }
    }

    /** MEDIA_BUTTON yayını gönderen firmware'ler için. */
    private val mediaBtnReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val e: KeyEvent? = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            if (e != null && e.action == KeyEvent.ACTION_DOWN) {
                val d = zapDirection(e.keyCode)
                if (d != 0) zap(d > 0)
            }
        }
    }

    private fun openIntent(url: String) {
        try {
            val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Harici oynatıcı bulunamadı (VLC / MX Player / Just Player kur)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Harici oynatıcı açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Geri tuşunu sayfaya Escape olarak ilet; çıkışı sayfa kendisi (çift geri) yönetir. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { web.webChromeClient?.onHideCustomView(); return }
        val js = "(function(){var o={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true};" +
            "var t=document.activeElement||document.body;" +
            "t.dispatchEvent(new KeyboardEvent('keydown',o));t.dispatchEvent(new KeyboardEvent('keyup',o));return 1;})()"
        web.evaluateJavascript(js) { r -> if (r == null || r == "null") finish() }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onResume() {
        super.onResume(); web.onResume(); immersive()
        mediaSession?.isActive = true            // direksiyon medya tuşları bu uygulamaya gelsin
        if (!btnReceiverOn) {
            val f = IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply { priority = 1000 }
            runCatching { registerReceiver(mediaBtnReceiver, f); btnReceiverOn = true }
        }
    }

    override fun onPause() {
        mediaSession?.isActive = false           // arka plandayken müzik/navigasyon uygulamasının tuşlarını çalma
        if (btnReceiverOn) { runCatching { unregisterReceiver(mediaBtnReceiver) }; btnReceiverOn = false }
        destroyRecognizer()
        web.onPause(); super.onPause()
    }

    override fun onDestroy() {
        destroyRecognizer()
        mediaSession?.release(); mediaSession = null
        fileCb?.onReceiveValue(null); fileCb = null
        root.removeAllViews(); web.destroy(); super.onDestroy()
    }
}
