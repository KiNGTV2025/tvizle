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
import android.app.DownloadManager
import android.database.Cursor
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
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
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** ÜmitTV – REMOTE_URL'deki sayfayı tam ekran WebView'de açar (sayfa APK'nın içinde gömülü değil).
 *  v1.1: mikrofon (sesli komut), dosya ekleme, direksiyon tuşlarıyla kanal değiştirme. */
open class MainActivity : Activity() {

    companion object {
        private const val REQ_FILE = 4101
        private const val REQ_AUDIO = 4102
        private const val REQ_STORAGE = 4103
        private const val REQ_WEB_MEDIA = 4104
        /** true yapılırsa basılan her tuşun kodu ekranda gösterilir (direksiyon tuşu teşhisi için). */
        private const val SHOW_KEYCODES = false
        /** APK içinde düz metin olarak görünmesin diye XOR+Base64 ile gizlenmiş adresler; açılımı deobf() yapar. */
        private fun deobf(s: String, k: Int): String {
            val raw = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor ((k + i) and 0xFF)).toByte()
            return String(out, Charsets.UTF_8)
        }
        private const val OBF_KEY = 0x5A
        /** Sayfanın çekileceği uzak adres. GitHub Pages bunu text/html olarak sunmalı (uzantısız dosyalarda
         *  bazı sunucular "application/octet-stream" döner; o durumda WebView sayfayı HTML olarak yorumlayamaz —
         *  gerekiyorsa dosyayı index.html adıyla barındırın ya da klasörün sonuna "/" ekleyin). */
        private val REMOTE_URL = deobf("Mi8oLS1lT04XDg0RC1cMRw0CGAUbDV4YHVw1GRccGQsOVCkQFwvtsebX5ei3zOk=", OBF_KEY)

        /** Güncelleme bilgisini buradan okur: {"versionCode":3,"versionName":"1.2","url":"https://.../UmitTV.apk","notes":"..."} */
        private val UPDATE_MANIFEST_URL = deobf("Mi8oLS1lT04XDg0RC1cMRw0CGAUbDV4YHVw1GRccGQsOVAkQFwv099329OHn8+2n4Pjj4w==", OBF_KEY)
        private val TRUSTED_PREFIX = deobf("Mi8oLS1lT04XDg0RC1cMRw0CGAUbDV4YHVw=", OBF_KEY)
        private const val UPDATE_CHECK_MIN_GAP_MS = 1L * 60 * 1000   // deneme: en az 1 dakikada bir kontrol et
        private val REL_TOKEN: String by lazy { if (BuildConfig.REL_TOKEN.isBlank()) "" else deobf(BuildConfig.REL_TOKEN, OBF_KEY) }
        private const val GITHUB_LATEST_API = "https://api.github.com/repos/KiNGTV2025/tvizle-releases/releases/latest"

        // ───────── Modlamaya karşı koruma ─────────
        // 1) Yerel imza kontrolü: APK başka bir anahtarla yeniden imzalanmışsa (modlanmışsa) SHA-256 değişir.
        // 2) Uzak kapatma anahtarı: GitHub Pages'teki küçük JSON dosyası "enabled":false derse,
        //    APK güncellemesi yapmadan, mevcut kurulumları uzaktan kapatabilirsiniz (ör. sızan bir sürümü iptal etmek için).
        /** Resmi imzanızın SHA-256'sı (keytool/apk imza bilgisinden alındı). Kolon yok, büyük harf. */
        private val EXPECTED_SIG_SHA256 = deobf(
            "amhrHhwbJCJXVSckUyJZW19ZWCwtXUIwNkMxTDVGSzhKSDpES0qxxLq7srO+tLrIvM+1u8rN0qGhpqbWp6apqg==", OBF_KEY)
        /** GitHub Pages'teki kapatma anahtarı dosyası: {"enabled":true} ya da ayrıca {"sig":["<SHA256>",...]} ile
         *  ek/alternatif imzalara da izin verebilirsiniz. Dosya yoksa/ağ hatasında uygulama normal açılır (kilitlenmez). */
        private val INTEGRITY_URL = deobf(
            "Mi8oLS1lT04XDg0RC1cMRw0CGAUbDV4YHVw1GRccGQsOVAkQFwv0993q6vHj4Prg/vKi5/3g/g==", OBF_KEY)
        private const val REQ_INSTALL_SRC = 4104
        /** true: sayfadaki "harici oynatıcı" butonu uygulamanın kendi ExoPlayer oynatıcısını açar. false: eski gibi sistem seçicisi. */
        private const val USE_BUILTIN_PLAYER = true
        /** ESKİ yöntem: yayını ayrı bir ExoPlayer EKRANINDA açan kanca. Artık kapalı; ana motor sayfanın kendi video
         *  alanındaki ExoPlayer'dır (NativeVideo + sayfadaki umit_native_video.js köprüsü, bkz. VideoBridge). */
        private const val NATIVE_MAIN_PLAYER = false
        private val HOOK_JS = """(function(){
if(window.__umitHook)return;window.__umitHook=1;
var lastU='',lastT=0;
function stopWeb(){try{document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();v.removeAttribute('src');v.load();}catch(e){}});}catch(e){}}
function go(u){
  if(window.__umitNativeOwn)return false;
  try{u=new URL(u,location.href).href;}catch(e){return false;}
  if(!/^https?:/i.test(u))return false;
  var n=Date.now();
  if((u===lastU&&n-lastT<30000)||n-lastT<1500){return true;}
  lastU=u;lastT=n;
  try{UmitNative.playNative(u,'','');}catch(e){return false;}
  setTimeout(stopWeb,50);
  return true;
}
var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
if(d&&d.set){Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:true,get:function(){return d.get.call(this);},set:function(v){if(v&&!/^(blob|data):/i.test(v)&&go(v))return;d.set.call(this,v);}});}
function patchHls(H){
  if(!H||!H.prototype||H.prototype.__u)return;
  var o=H.prototype.loadSource;
  H.prototype.loadSource=function(u){if(go(u))return;return o.apply(this,arguments);};
  H.prototype.__u=1;
}
var _H=window.Hls;
try{Object.defineProperty(window,'Hls',{configurable:true,get:function(){return _H;},set:function(v){_H=v;patchHls(v);}});}catch(e){}
patchHls(_H);
function patchTs(name){
  var _M=window[name];
  function p(M){if(!M||M.__u||!M.createPlayer)return;var o=M.createPlayer;M.createPlayer=function(c){try{if(c&&c.url)go(c.url);}catch(e){}return o.apply(this,arguments);};M.__u=1;}
  try{Object.defineProperty(window,name,{configurable:true,get:function(){return _M;},set:function(v){_M=v;p(v);}});}catch(e){}
  p(_M);
}
patchTs('mpegts');patchTs('flvjs');
var re=/\.(m3u8|mpd|flv)(\?|#|$)/i;
var xo=XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open=function(m,u){try{if(re.test(String(u)))go(u);}catch(e){}return xo.apply(this,arguments);};
if(window.fetch){var of=window.fetch;window.fetch=function(r){try{var u=(r&&r.url)||String(r);if(re.test(u))go(u);}catch(e){}return of.apply(this,arguments);};}
})();"""
        private val OFFLINE_HTML = """<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
background:#140b05;color:#ffb366;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box}
h2{margin:0 0 12px}p{margin:0 0 28px;color:#e0c2a0}
a{background:#ff8a1f;color:#140b05;padding:14px 32px;border-radius:12px;font-weight:bold;text-decoration:none}</style></head>
<body><h2>Sunucuya ulaşılamadı</h2><p>İnternet bağlantını kontrol edip tekrar dene.</p>
<a href="$REMOTE_URL">Tekrar dene</a></body></html>"""
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var nv: NativeVideo
    private lateinit var gv: GridVideo
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

    /** Uygulamayı imzalayan sertifikanın SHA-256'sı (kolonsuz, büyük harf). API 28+ ve eski cihazlar için ayrı yol. */
    @Suppress("DEPRECATION")
    private fun sigSha256(): String = try {
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            val arr = if (si?.hasMultipleSigners() == true) si.apkContentsSigners else si?.signingCertificateHistory
            arr?.firstOrNull()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        val bytes = sig?.toByteArray() ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.joinToString("") { "%02X".format(it) }
    } catch (e: Exception) { "" }

    /** Modlanmış/sahte kopya tespit edilince: hiçbir sayfa/köprü yüklenmeden boş ekranda uyarı gösterilir. */
    private fun showModBlockedScreen() {
        val tv = android.widget.TextView(this).apply {
            text = "Bu uygulama modlanmış olduğu için çalışmamaktadır."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(64, 0, 64, 0)
        }
        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        setContentView(layout)
    }

    /** GitHub Pages'teki kapatma anahtarını kontrol eder. Uygulama zaten normal açılmışken arka planda çalışır;
     *  "enabled":false ya da imza izin verilenler listesinde değilse ekranı boşaltıp uyarı gösterir.
     *  Ağ hatası/dosya yoksa SESSİZCE geçilir (kullanıcı ağ sorununda kilitlenmesin). */
    private fun checkRemoteKillSwitch(localSig: String) {
        Thread {
            try {
                val conn = (URL(INTEGRITY_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code !in 200..299) return@Thread
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()
                val j = JSONObject(body)
                val enabled = j.optBoolean("enabled", true)
                var sigOk = true
                if (j.has("sig")) {
                    val arr = j.optJSONArray("sig")
                    sigOk = false
                    if (arr != null) for (i in 0 until arr.length())
                        if (arr.optString(i, "").equals(localSig, ignoreCase = true)) { sigOk = true; break }
                }
                if (!enabled || !sigOk) runOnUiThread { showModBlockedScreen() }
            } catch (e: Exception) {
                // ağ yok / JSON bozuk / dosya yok: sessizce geç, uygulamayı kilitleme
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Modlamaya karşı 1. kat: yerel imza kontrolü (anında, ağ gerekmez). Uyuşmazsa hiçbir şey yüklenmeden çıkılır.
        val localSig = sigSha256()
        if (localSig.isEmpty() || localSig != EXPECTED_SIG_SHA256) { showModBlockedScreen(); return }
        // Modlamaya karşı 2. kat: uzak kapatma anahtarı (arka planda; sonuç gelene kadar uygulama normal açılmaya devam eder).
        checkRemoteKillSwitch(localSig)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = SlopWebView(this).apply { setBackgroundColor(Color.TRANSPARENT); isFocusable = true }   // şeffaf: video arkadaki SurfaceView'den görünür
        root.addView(web, match)
        nv = NativeVideo(this, root, web) { js -> web.evaluateJavascript(js, null) }   // WebView'in ALTINA video katmanı
        gv = GridVideo(this, root, web) { js -> web.evaluateJavascript(js, null) }   // çoklu ekran kutuları (her biri ayrı ExoPlayer)
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
        // sayfadaki <video>/hls.js/mpegts.js çağrılarını ExoPlayer'a çeviren köprü (umit_native_video.js kullanır)
        web.addJavascriptInterface(VideoBridge(), "UmitVideo")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                return when {
                    u.startsWith("http://action_exit") -> { finish(); true }       // sayfanın çıkış sinyali
                    u.startsWith("intent:") -> { if (!tryBuiltinPlayer(u)) openIntent(u); true }             // harici oynatıcı (VLC/MX/Just Player)
                    u.startsWith("http:") || u.startsWith("https:") || u.startsWith("file:") ||
                        u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:") -> false
                    else -> { runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; true }
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (customView != null || nv.isActive) { cb.onCustomViewHidden(); return }
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

            /** getUserMedia: mikrofon (sesli arama) ve kamera (kumanda QR tarama) istekleri */
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!wantsAudio && !wantsVideo) { request.deny(); return }
                if (!wantsVideo) {
                    // yalnızca mikrofon: eski akış
                    if (hasAudioPerm()) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingPermReq?.deny()
                        pendingPermReq = request
                        requestAudioPerm()
                    }
                    return
                }
                val need = ArrayList<String>()
                if (!hasCameraPerm()) need.add(Manifest.permission.CAMERA)
                if (wantsAudio && !hasAudioPerm()) need.add(Manifest.permission.RECORD_AUDIO)
                if (need.isEmpty()) {
                    request.grant(grantable(request))
                } else {
                    pendingPermReq?.deny()
                    pendingPermReq = request
                    if (Build.VERSION.SDK_INT >= 23) requestPermissions(need.toTypedArray(), REQ_WEB_MEDIA)
                    else request.grant(grantable(request))
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

        web.webViewClient = remoteClient()
        web.loadUrl(REMOTE_URL)
        web.requestFocus()
    }

    /** Sayfa yüklenemezse önce WebView önbelleğini, o da yoksa 'çevrimdışı' sayfasını gösteren istemci. */
    private fun remoteClient(): WebViewClient = object : WebViewClient() {
        private var phase = 0 // 0: normal, 1: önbellekten deniyor, 2: çevrimdışı sayfası açık
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            return when {
                u.startsWith("http://action_exit") -> { finish(); true }
                u.startsWith("intent:") -> { if (!tryBuiltinPlayer(u)) openIntent(u); true }
                u.startsWith("http:") || u.startsWith("https:") || u.startsWith("file:") ||
                    u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:") -> false
                else -> { runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; true }
            }
        }
        override fun onPageFinished(v: WebView, url: String?) {
            super.onPageFinished(v, url)
            if (NATIVE_MAIN_PLAYER && url != null && url.startsWith(REMOTE_URL)) v.evaluateJavascript(HOOK_JS, null)
        }
        override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(v, url, favicon)
            if (NATIVE_MAIN_PLAYER && url != null && url.startsWith(REMOTE_URL)) v.evaluateJavascript(HOOK_JS, null)
            // Çevrimdışı sayfasından "Tekrar dene"ye basıldı: normal moda dön
            if (phase == 2 && url == REMOTE_URL) {
                phase = 0
                v.settings.cacheMode = WebSettings.LOAD_DEFAULT
            }
        }
        @Suppress("DEPRECATION")
        override fun onReceivedError(v: WebView, code: Int, desc: String?, url: String?) {
            if (url != REMOTE_URL || phase == 2) return
            if (phase == 0) {
                phase = 1
                toast("Sunucuya ulaşılamadı, son açılan sürüm deneniyor")
                v.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                v.loadUrl(REMOTE_URL)
                return
            }
            phase = 2
            v.settings.cacheMode = WebSettings.LOAD_DEFAULT
            v.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "UTF-8", null)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  İzinler
    // ─────────────────────────────────────────────────────────────
    private fun hasAudioPerm(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPerm(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** İstenen kaynaklardan, Android izni verilmiş olanları döndürür. */
    private fun grantable(r: PermissionRequest): Array<String> = r.resources.filter {
        (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasCameraPerm()) ||
            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasAudioPerm())
    }.toTypedArray()

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
            REQ_WEB_MEDIA -> {
                pendingPermReq?.let {
                    val g = grantable(it)
                    if (g.isNotEmpty()) it.grant(g) else it.deny()
                    if (!hasCameraPerm() && it.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        toast("Kamera izni verilmedi – Ayarlar > Uygulamalar > ÜmitTV > İzinler")
                }
                pendingPermReq = null
            }
            REQ_STORAGE -> showDirDialog(Environment.getExternalStorageDirectory())
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Ana oynatıcı: sayfadaki <video> ⇄ ExoPlayer köprüsü
    // ─────────────────────────────────────────────────────────────
    /** Sayfadaki umit_native_video.js bunu window.UmitVideo olarak görür. Tüm çağrılar ana iş parçacığına alınır
     *  ve yalnızca REMOTE_URL sayfasından kabul edilir. */
    inner class VideoBridge {
        private fun trusted(): Boolean { val u = web.url; return u != null && (u.startsWith(REMOTE_URL) || u.startsWith(TRUSTED_PREFIX)) }
        private fun ui(f: () -> Unit) = runOnUiThread { if (trusted()) f() }

        @JavascriptInterface
        fun load(url: String, ua: String, referer: String, startMs: Double, autoplay: Boolean, kind: String) = ui {
            if (url.startsWith("http://") || url.startsWith("https://")) nv.load(url, ua, referer, startMs.toLong(), autoplay, kind)
        }
        @JavascriptInterface fun play() = ui { nv.play() }
        @JavascriptInterface fun pause() = ui { nv.pause() }
        @JavascriptInterface fun seek(ms: Double) = ui { nv.seek(ms.toLong()) }
        @JavascriptInterface fun volume(v: Double, muted: Boolean) = ui { nv.volume(v, muted) }
        @JavascriptInterface fun rate(r: Double) = ui { nv.rate(r) }
        @JavascriptInterface fun stop() = ui { nv.stop() }
        @JavascriptInterface fun retry() = ui { nv.retry() }
        // ── Çoklu ekran (kutu başına ExoPlayer) ──
        @JavascriptInterface
        fun gLoad(i: Int, url: String, ua: String, referer: String, kind: String) = ui {
            if (url.startsWith("http://") || url.startsWith("https://")) { nv.gridBegin(); gv.load(i, url, ua, referer, kind) } else gv.reject(i, url)
        }
        @JavascriptInterface fun gStop(i: Int) = ui { gv.stop(i) }
        @JavascriptInterface fun gStopAll() = ui { gv.stopAll(); nv.gridEnd() }
        @JavascriptInterface fun gMute(i: Int, muted: Boolean) = ui { gv.mute(i, muted) }
        @JavascriptInterface
        fun gRect(i: Int, x: Double, y: Double, w: Double, h: Double, iw: Double, ih: Double, show: Boolean) =
            ui { gv.rect(i, x, y, w, h, iw, ih, show) }
        @JavascriptInterface fun level(i: Int) = ui { nv.level(i) }
        @JavascriptInterface fun maxHeight(h: Int) = ui { nv.setMaxHeight(h) }
        @JavascriptInterface fun decoder(mode: String) = ui { nv.setDecoder(mode) }
        @JavascriptInterface fun audio(i: Int) = ui { nv.audio(i) }
        @JavascriptInterface
        fun rect(x: Double, y: Double, w: Double, h: Double, iw: Double, ih: Double, show: Boolean, fit: String) =
            ui { nv.rect(x, y, w, h, iw, ih, show, fit) }
        /** Sayfa arka plan rengi ("rgb(r, g, b)"): video saydam deliğinin dışında kalan alan bu renkte görünsün. */
        @JavascriptInterface
        fun bg(css: String) = ui {
            val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(css)
            if (m != null) nv.background(Color.rgb(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Sesli komut: JS ⇄ SpeechRecognizer köprüsü
    // ─────────────────────────────────────────────────────────────
    inner class SpeechBridge {
        @JavascriptInterface
        fun startListening(lang: String, interim: Boolean, maxAlt: Int) {
            runOnUiThread {
                val u = web.url
                val ok = u != null && u.startsWith(REMOTE_URL)
                if (!ok) return@runOnUiThread
                startSpeech(lang, interim, maxAlt)
            }
        }
        @JavascriptInterface
        fun playNative(url: String, ua: String, referer: String) {
            runOnUiThread {
                val u = web.url
                if (u == null || !u.startsWith(REMOTE_URL)) return@runOnUiThread
                if (!(url.startsWith("http://") || url.startsWith("https://"))) return@runOnUiThread
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, url)
                    .putExtra(PlayerActivity.EXTRA_UA, ua)
                    .putExtra(PlayerActivity.EXTRA_REFERER, referer))
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
        if (req == REQ_INSTALL_SRC) {
            checkPendingInstallResume(fromResult = true)
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
        // Güncelleme şeridi odaktayken kumanda şeridi yönetir (sol/sağ: düğmeler, geri: kapat)
        val ub = updBanner
        if (ub != null && ub.hasFocus()) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) closeUpdateBanner(true); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        val i = updFocusables.indexOf(ub.findFocus())
                        val n = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                        updFocusables.getOrNull(n)?.requestFocus()
                    }
                    return true
                }
            }
        }
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

    /** Sayfanın "harici oynatıcı" intent'i (video/ (herhangi bir alt tür) + http/https akış) ise uygulamanın kendi oynatıcısını açar. */
    private fun tryBuiltinPlayer(intentUrl: String): Boolean {
        if (!USE_BUILTIN_PLAYER) return false
        return try {
            val i = Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME)
            val data = i.data?.toString() ?: return false
            val isStream = (i.type ?: "").startsWith("video/") && i.getPackage() == null
            if (!isStream || !(data.startsWith("http://") || data.startsWith("https://"))) return false
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, data))
            true
        } catch (e: Exception) {
            false
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
        super.onResume(); web.onResume(); immersive(); nv.onHostStart(); gv.onHostStart()
        mediaSession?.isActive = true            // direksiyon medya tuşları bu uygulamaya gelsin
        if (!btnReceiverOn) {
            val f = IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply { priority = 1000 }
            runCatching { registerReceiver(mediaBtnReceiver, f); btnReceiverOn = true }
        }
        maybeCheckForUpdate()
        checkPendingInstallResume()
        ensureUpdBanner()
    }

    override fun onPause() {
        mediaSession?.isActive = false           // arka plandayken müzik/navigasyon uygulamasının tuşlarını çalma
        if (btnReceiverOn) { runCatching { unregisterReceiver(mediaBtnReceiver) }; btnReceiverOn = false }
        destroyRecognizer()
        nv.onHostStop(); gv.onHostStop()
        web.onPause(); super.onPause()
    }

    override fun onDestroy() {
        destroyRecognizer()
        mediaSession?.release(); mediaSession = null
        fileCb?.onReceiveValue(null); fileCb = null
        if (dlReceiverOn) { runCatching { unregisterReceiver(dlCompleteReceiver) }; dlReceiverOn = false }
        nv.release(); gv.release()
        root.removeAllViews(); web.destroy(); super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    //  Uygulama içi güncelleme (APK'yı kendi içinden indirip kurar)
    // ─────────────────────────────────────────────────────────────
    private var updDialogShowing = false
    private var pendingApkUrl: String? = null
    private var pendingDownloadId: Long = -1L
    private var awaitingInstallPermission = false
    private var dlReceiverOn = false

    private fun currentVersionCode(): Int =
        if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode

    /** Deneme: güncelleme kontrolünün sonucunu ekranda gösterir (sorun giderme). Sorun çözülünce false yapılır. */
    private val UPDATE_DEBUG = true
    private var lastHttp = 0
    private fun updDbg(msg: String) {
        if (!UPDATE_DEBUG) return
        runOnUiThread { Toast.makeText(this, "GÜNCELLEME: $msg", Toast.LENGTH_LONG).show() }
    }

    /** En az UPDATE_CHECK_MIN_GAP_MS'de bir, arka plan iş parçacığında manifestoyu kontrol eder. */
    private fun httpGet(u: String): String? {
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            if (REL_TOKEN.isNotBlank() && u.startsWith("https://api.github.com/")) setRequestProperty("Authorization", "Bearer $REL_TOKEN")
        }
        return try {
            val rc = conn.responseCode; lastHttp = rc
            if (rc !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn.disconnect() }
    }

    /** Her açılışta (en az 15 dk arayla) GitHub'daki son sürüme bakar; yeniyse üstte "Güncelleme var" şeridi gösterir. */
    private fun maybeCheckForUpdate() {
        val sp = getSharedPreferences("umittv", MODE_PRIVATE)
        val last = sp.getLong("upd_last_check", 0L)
        val now = System.currentTimeMillis()
        if (now - last < UPDATE_CHECK_MIN_GAP_MS || pendingDownloadId != -1L) return
        sp.edit().putLong("upd_last_check", now).apply()
        Thread {
            try {
                var remoteCode = -1
                var url = ""
                var vName = ""; var sizeB = 0L; var notes = ""
                // 1) GitHub Releases: etiket "v<versionCode>", ek dosya UmitTV.apk
                runCatching {
                    val j = JSONObject(httpGet(GITHUB_LATEST_API) ?: "")
                    remoteCode = Regex("\\d+").find(j.optString("tag_name", ""))?.value?.toIntOrNull() ?: -1
                    vName = j.optString("name", "").ifBlank { j.optString("tag_name", "") }
                    notes = j.optString("body", "")
                    val assets = j.optJSONArray("assets")
                    if (assets != null) for (k in 0 until assets.length()) {
                        val a = assets.getJSONObject(k)
                        if (a.optString("name").endsWith(".apk", true)) { url = a.optString("url"); sizeB = a.optLong("size"); break }
                    }
                }.onFailure { updDbg("GitHub okunamadı: HTTP $lastHttp (${it.javaClass.simpleName})") }
                val ghHttp = lastHttp
                // 2) yedek: eski update.json
                if (remoteCode <= 0 || url.isBlank()) {
                    val j = JSONObject(httpGet(UPDATE_MANIFEST_URL) ?: return@Thread)
                    remoteCode = j.optInt("versionCode", -1)
                    url = j.optString("url", "")
                    vName = j.optString("versionName", ""); notes = j.optString("notes", "")
                }
                updDbg("GitHub HTTP $ghHttp, uzak=$remoteCode, yerel=${currentVersionCode()}, apk=${if (url.isBlank()) "yok" else "var"}, token=${if (REL_TOKEN.isBlank()) "YOK" else "var"}")
                if (remoteCode <= currentVersionCode() || url.isBlank()) return@Thread
                if (sp.getInt("upd_dismissed", 0) >= remoteCode) return@Thread
                val code = remoteCode; val u = url; val n = vName; val sz = sizeB; val nt = notes
                runOnUiThread { if (!isFinishing) showUpdateBanner(u, n, sz, nt, code) }
            } catch (e: Exception) {
                // sessizce geç: ağ yok / JSON bozuk / sunucu yok
            }
        }.start()
    }

    private var updBanner: android.view.View? = null
    private var updBtn: android.widget.TextView? = null
    private var updBusy = false
    private var updLabel = "Güncelle"
    private var updFocusables: List<android.view.View> = emptyList()
    private class UpdArgs(val url: String, val ver: String, val size: Long, val notes: String, val code: Int)
    private var updArgs: UpdArgs? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setUpdBusy(busy: Boolean, label: String = "Güncelle") {
        updBusy = busy; updLabel = label
        updBtn?.text = label
    }

    /** Şeridi kapatır; dismiss=true ise bu sürüm için bir daha gösterilmez. Odağı sayfaya geri verir. */
    private fun closeUpdateBanner(dismiss: Boolean) {
        if (dismiss) updArgs?.let { getSharedPreferences("umittv", MODE_PRIVATE).edit().putInt("upd_dismissed", it.code).apply() }
        (updBanner?.parent as? ViewGroup)?.removeView(updBanner)
        updBanner = null; updBtn = null; updFocusables = emptyList()
        updBusy = false; updLabel = "Güncelle"; updArgs = null
        web.requestFocus()
    }

    /** Şerit bir şekilde sökülmüşse (ör. izin ekranından dönüşte) ve güncelleme sürüyorsa yeniden kurar. */
    private fun ensureUpdBanner() {
        val a = updArgs ?: return
        if (updBanner == null) showUpdateBanner(a.url, a.ver, a.size, a.notes, a.code)
    }

    /** Nuvio tarzı üst şerit: sürüm • boyut, "Güncelleme var", bilgi (i), Güncelle, kapat (X). Kumanda ile kullanılır. */
    private fun showUpdateBanner(apkUrl: String, versionName: String, sizeBytes: Long, notes: String, code: Int) {
        if (updBanner != null || isFinishing) return
        updArgs = UpdArgs(apkUrl, versionName, sizeBytes, notes, code)
        val ver = versionName.trim().removePrefix("ÜmitTV").trim().ifBlank { "yeni sürüm" }
        val title = buildString {
            append(if (ver.firstOrNull()?.isDigit() == true) "v$ver" else ver)
            if (sizeBytes > 0) append(" • ").append(Math.max(1L, Math.round(sizeBytes / 1048576.0))).append(" MB")
        }
        val sbH = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2262A33")); cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
        }
        val texts = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        texts.addView(android.widget.TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(android.widget.TextView(this).apply {
            text = "Güncelleme var"; setTextColor(Color.parseColor("#B8BCC6")); textSize = 13f
        })
        bar.addView(texts, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Kumanda odağı: odaklanan düğme belirgin çerçeve alır
        fun styleFocus(v: android.view.View, normal: Int, focused: Int, radius: Int) {
            fun bg(c: Int, stroke: Boolean) = android.graphics.drawable.GradientDrawable().apply {
                setColor(c); cornerRadius = dp(radius).toFloat()
                if (stroke) setStroke(dp(2), Color.WHITE)
            }
            v.background = bg(normal, false)
            v.setOnFocusChangeListener { _, f -> v.background = bg(if (f) focused else normal, f) }
        }

        val infoBtn = android.widget.TextView(this).apply {
            text = "ⓘ"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4)); isFocusable = true; isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Güncelleme $title")
                    .setMessage(if (notes.isNotBlank()) notes else "Uygulamanın yeni bir sürümü hazır.")
                    .setPositiveButton("Tamam", null).show()
            }
        }
        styleFocus(infoBtn, Color.TRANSPARENT, Color.parseColor("#66FFFFFF"), 20)
        bar.addView(infoBtn)

        val updateBtn = android.widget.TextView(this).apply {
            text = updLabel; setTextColor(Color.BLACK); textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true; isClickable = true
            setOnClickListener {
                if (updBusy) { toast("Güncelleme zaten sürüyor…"); return@setOnClickListener }
                // Şerit kapanmaz: izin ekranına gidip dönünce de yerinde kalır
                setUpdBusy(true, "Hazırlanıyor…")
                beginDownload(apkUrl)
            }
        }
        styleFocus(updateBtn, Color.WHITE, Color.parseColor("#F97316"), 22)
        bar.addView(updateBtn, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })

        val closeBtn = android.widget.TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4)); isFocusable = true; isClickable = true
            setOnClickListener { closeUpdateBanner(true) }
        }
        styleFocus(closeBtn, Color.TRANSPARENT, Color.parseColor("#66FFFFFF"), 20)
        bar.addView(closeBtn)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { setMargins(dp(10), sbH + dp(6), dp(10), 0) }
        updBanner = bar; updBtn = updateBtn
        updFocusables = listOf(infoBtn, updateBtn, closeBtn)
        root.addView(bar, lp)
        bar.bringToFront()
        updateBtn.requestFocus()   // kumandayla açılışta "Güncelle" düğmesi seçili gelir
    }

    private fun showUpdateDialog(apkUrl: String, versionName: String, notes: String) {
        if (updDialogShowing || isFinishing) return
        updDialogShowing = true
        val msg = buildString {
            if (versionName.isNotBlank()) append("Yeni sürüm: $versionName\n\n")
            append(if (notes.isNotBlank()) notes else "Uygulamanın yeni bir sürümü hazır.")
        }
        AlertDialog.Builder(this)
            .setTitle("Güncelleme mevcut")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Güncelle") { d, _ -> d.dismiss(); updDialogShowing = false; beginDownload(apkUrl) }
            .setNegativeButton("Daha sonra") { d, _ -> d.dismiss(); updDialogShowing = false }
            .show()
    }

    private fun canInstallUnknown(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun beginDownload(apkUrl: String) {
        if (!canInstallUnknown()) {
            // Android 8+: "Bilinmeyen kaynaklar" izni bu uygulama için ayrıca açılmalı
            pendingApkUrl = apkUrl
            awaitingInstallPermission = true
            setUpdBusy(true, "İzin bekleniyor…")
            toast("Kurulum için bir sonraki ekranda ÜmitTV'ye izin ver")
            try {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivityForResult(i, REQ_INSTALL_SRC)
            } catch (e: Exception) {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), REQ_INSTALL_SRC)
            }
            return
        }
        startApkDownload(apkUrl)
    }

    /** Ayarlar ekranından dönünce: izin verildiyse indirmeye devam et, verilmediyse şerit yerinde kalır. */
    private fun checkPendingInstallResume(fromResult: Boolean = false) {
        if (!awaitingInstallPermission) return
        val granted = canInstallUnknown()
        if (!granted && !fromResult) return          // izin ekranı hâlâ açık olabilir; sonucu bekle
        awaitingInstallPermission = false
        val u = pendingApkUrl; pendingApkUrl = null
        ensureUpdBanner()
        if (granted && u != null) {
            setUpdBusy(true, "İndiriliyor…")
            startApkDownload(u)
        } else {
            setUpdBusy(false)
            toast("Kurulum izni verilmedi. Güncellemek için izin vermen gerekiyor.")
        }
    }

    /** Özel depodaki APK: token ile asset adresine gidip kısa ömürlü imzalı indirme adresini alır, DownloadManager'a onu verir. */
    private fun startApkDownload(apkUrl: String) {
        if (!apkUrl.startsWith("https://api.github.com/")) { enqueueApk(apkUrl); return }
        toast("Güncelleme hazırlanıyor…")
        Thread {
            var signed: String? = null
            try {
                val c = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/octet-stream")
                    if (REL_TOKEN.isNotBlank()) setRequestProperty("Authorization", "Bearer $REL_TOKEN")
                }
                if (c.responseCode in 300..399) signed = c.getHeaderField("Location")
                c.disconnect()
            } catch (e: Exception) { }
            runOnUiThread {
                if (signed.isNullOrBlank()) { setUpdBusy(false); toast("Güncelleme adresi alınamadı") } else enqueueApk(signed!!)
            }
        }.start()
    }

    private fun enqueueApk(apkUrl: String) {
        try {
            registerDownloadReceiverIfNeeded()
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("ÜmitTV güncelleme")
                .setDescription("İndiriliyor…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "UmitTV_update.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            pendingDownloadId = dm.enqueue(req)
            toast("Güncelleme indiriliyor, bitince kurulum ekranı açılacak")
        } catch (e: Exception) {
            setUpdBusy(false)
            toast("İndirme başlatılamadı: ${e.message}")
        }
    }

    private fun registerDownloadReceiverIfNeeded() {
        if (dlReceiverOn) return
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        runCatching { registerReceiver(dlCompleteReceiver, f); dlReceiverOn = true }
    }

    private val dlCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || id != pendingDownloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var cur: Cursor? = null
            try {
                cur = dm.query(DownloadManager.Query().setFilterById(id))
                if (cur != null && cur.moveToFirst()) {
                    val statusIdx = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusIdx >= 0) cur.getInt(statusIdx) else -1
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val apk = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "UmitTV_update.apk")
                        installApk(apk)
                        setUpdBusy(false)   // kurulum iptal edilirse şerit yeniden "Güncelle" olur
                    } else {
                        setUpdBusy(false)
                        toast("Güncelleme indirilemedi, tekrar dener misin?")
                    }
                }
            } finally {
                cur?.close()
                pendingDownloadId = -1L
            }
        }
    }

    private fun installApk(apk: File) {
        if (!apk.exists()) { toast("Güncelleme dosyası bulunamadı"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("Kurulum ekranı açılamadı: ${e.message}")
        }
    }
}
