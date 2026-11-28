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
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import android.graphics.drawable.GradientDrawable
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Canvas
import android.animation.ValueAnimator
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** ÜmitTV – REMOTE_URL'deki sayfayı tam ekran WebView'de açar (sayfa APK'nın içinde gömülü değil).
 *  v1.1: mikrofon (sesli komut), dosya ekleme, direksiyon tuşlarıyla kanal değiştirme. */
open class MainActivity : Activity() {
    // PiP: sayfa düğmesi ve Android ana ekrana geçişi aynı yerel oynatıcıyı kullanır.
    @Volatile private var autoPipEnabled = true
    private var pipLayoutActive = false
    @Volatile private var lastPipFailure: String = ""
    /** Televizyon / TV kutusu: PiP bu cihazlarda hiçbir yoldan açılmaz (düğme, otomatik, ana ekrana basış). */
    private val onTvDevice: Boolean by lazy { runCatching { DecoderTweaks.isTv(this) }.getOrDefault(false) }


    companion object {
        private const val REQ_FILE = 4101
        private const val REQ_AUDIO = 4102
        private const val REQ_VOICE_UI = 4110
        private const val REQ_STORAGE = 4103
        private const val REQ_WEB_MEDIA = 4104
        /** true yapılırsa basılan her tuşun kodu ekranda gösterilir (direksiyon tuşu teşhisi için). */
        private const val SHOW_KEYCODES = false
        /** APP_SEC'in XOR anahtarı — açılış artık NativeGuard.unlockSecret() (native/.so) içinde,
         *  yalnızca imza doğrulandıktan sonra yapılıyor; bkz. NativeGuard.kt ve native-lib.cpp. */
        private const val OBF_KEY = 0x5A

        // ───── AES-256-GCM ile gizlenmiş sabitler: anahtar TEK PARÇA halinde hiçbir yerde yazılı değil.
        // 4 ayrı (anlamsız isimli) dizi + maskeleme + SHA-256 geçişinden sonra oluşuyor; statik string
        // taramasıyla ya da gözle okunarak elde edilemez, yalnızca bu kodu ÇALIŞTIRARAK üretilebilir.
        private const val S_MASK = 0x7E
        private val S_P1 = intArrayOf(2, 107, 150, 153, 63, 36, 85, 248)
        private val S_P2 = intArrayOf(89, 141, 206, 157, 242, 101, 75, 108)
        private val S_P3 = intArrayOf(137, 42, 164, 216, 187, 239, 55, 95)
        private val S_P4 = intArrayOf(159, 205, 163, 170, 157, 218, 218, 111)
        private fun sKey(): ByteArray {
            val seed = ByteArray(32)
            var i = 0
            for (p in arrayOf(S_P1, S_P2, S_P3, S_P4)) for (v in p) { seed[i] = (v xor S_MASK).toByte(); i++ }
            return MessageDigest.getInstance("SHA-256").digest(seed)
        }
        /** b64 = iv(12) + AES-256-GCM şifreli metin + tag. Statik taramada ne URL ne anahtar görünür. */
        private fun sdeobf(b64: String): String {
            val data = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKey(), "AES"), GCMParameterSpec(128, iv))
            return String(c.doFinal(ct), Charsets.UTF_8)
        }
        /** SpeechRecognizer yok edildikten sonra yenisini açmadan önce beklenecek süre (ms). */
        private const val REC_COOLDOWN_MS = 900L
        /** destroy() öncesi cancel()'ın Google ses servisine ulaşması için beklenen süre (ms). */
        private const val REC_DESTROY_DELAY_MS = 300L
        /** onReadyForSpeech bu sürede gelmezse servis bağlantısı takılmış sayılır (ms). */
        private const val REC_READY_TIMEOUT_MS = 6000L
        /** stopListening() sonrası sonuç/hata gelmezse oturum zorla kapatılır (ms). */
        private const val REC_STOP_GRACE_MS = 4000L
        /** Sayfanın çekileceği uzak adres. GitHub Pages bunu text/html olarak sunmalı (uzantısız dosyalarda
         *  bazı sunucular "application/octet-stream" döner; o durumda WebView sayfayı HTML olarak yorumlayamaz —
         *  gerekiyorsa dosyayı index.html adıyla barındırın ya da klasörün sonuna "/" ekleyin). */
        private val REMOTE_URL = sdeobf("KFbhd3CFmVZZ25071PS+RQS7dYsbYE84+CwLl4qap7u1pI3hPs7rxmcy+DNtxlBTgj8/3hwe/x1Fknx6wJNFEntwd+iUDy493BpP")

        /** Güncelleme bilgisini buradan okur: {"versionCode":3,"versionName":"1.2","url":"https://.../UmitTV.apk","notes":"..."} */
        private val UPDATE_MANIFEST_URL = sdeobf("1Slcw75nbapZQYImNhOmyCwGqcKE7+HqNq578Kx4Eyg7la/u45CpTkwj29iqokvtsVeCKHEdZQ44sDdvUqZM9QeiHZDBZHoaOVnShFFYUPg=")
        private val TRUSTED_PREFIX = sdeobf("okekkDQySlNzMYzkDDpnseCTpOSwO91caKGc4hJTCKrCztm+1CRiM3SwQO/MhrnZQ2L4hHOU")
        private const val UPDATE_CHECK_MIN_GAP_MS = 30L * 1000   // en az 30 sn arayla kontrol (Worker istek kotasını korumak için)
        /** Uygulama açıkken kaç sn'de bir yeni sürüme bakılır. */
        private const val UPDATE_POLL_MS = 60L * 1000
        /** Cloudflare Worker adresi (worker.js). Örn: "https://umittv-update.KULLANICI.workers.dev". Boşsa doğrudan GitHub'a bakılır. */
        private val UPDATE_WORKER_URL = sdeobf("fdsmMrt5wooAjrK/yu4vbhpAHl05P3U8NHEsLr9KJ9DNo9qRgjV9JNaObXWKYt6VKxAst0cHJPqtoiuGxQ==")
        /** Worker'da APP_KEY secret'ı tanımlıysa buraya aynısını yaz; yoksa boş bırak. */
        private val UPDATE_WORKER_KEY = sdeobf("DoGl3C15eN6/yeaxYS1KwLhqi2RZgiCxBqe36H3NrJIuP829TrLIF3kG1bo3iaPV9XCKvtcj5Q6l5z80QE4FVAYJrK07atKG5DI7iA==")
        /** onCreate() içinde sigSha256() ile hesaplanıp buraya yazılır (bkz. aşağısı); APP_SEC
         *  bunu native'e göndererek açılır. Dex'teki mod-kontrolü smali'den silinse bile burası
         *  GERÇEK hesaplanan imzayı tutar, native tarafı yine de kendi karşılaştırmasını yapar. */
        private var localSigCache: String = ""
        private val GITHUB_LATEST_API = sdeobf("PM0+LaTe5yHrgOGbOlAZz+W4fueh3GKJSerrpJG5WCFhLMQOivLC6pXekDuXsJR2iOicxgmTFZH8nY8CCU+yuzYIuxIWD/OPF4uhZNo2yudhMS3is+g8aDgRf/XZZyjun2XU")

        // ───────── Modlamaya karşı koruma ─────────
        // 1) Yerel imza kontrolü: APK başka bir anahtarla yeniden imzalanmışsa (modlanmışsa) SHA-256 değişir.
        // 2) Uzak kapatma anahtarı: GitHub Pages'teki küçük JSON dosyası "enabled":false derse,
        //    APK güncellemesi yapmadan, mevcut kurulumları uzaktan kapatabilirsiniz (ör. sızan bir sürümü iptal etmek için).
        /** Resmi imzanızın SHA-256'sı (keytool/apk imza bilgisinden alındı). Kolon yok, büyük harf. */
        private val EXPECTED_SIG_SHA256 = sdeobf("TxmzIJG59Ex9hRjuLS/VMHxoS3bsgveHZrn++SDrOgKtVy4A6K6AxHkg/DruQxuo3a/0eoPblshnnBTZh2zhf07dH/PkumJiPocOul8nWVjG8gdsEl44UjL0O5Q=")
        /** GitHub Pages'teki kapatma anahtarı dosyası: {"enabled":true} ya da ayrıca {"sig":["<SHA256>",...]} ile
         *  ek/alternatif imzalara da izin verebilirsiniz. Dosya yoksa/ağ hatasında uygulama normal açılır (kilitlenmez). */
        private val INTEGRITY_URL = sdeobf("zE7oMOCAZWNukbeDTn80qebhywPewUFGlvaaJaL3UhtQFAzXLXRBjt7kumetfafExx6CCuyjleCrf+GeOp9WEw4UjsDiRTo3M3szuRFTFLKFIqw=")
        /** Şifreli sayfa blob'u (Pages'te düz HTML yok) ve anahtar adresi (Worker /pk). */
        private val BLOB_URL = REMOTE_URL + ".dat"
        /** Ek Worker adresleri (başka Cloudflare hesaplarındaki AYNI kod). Her biri sdeobf("...") ile eklenir;
         *  şifreli metni tools/obf_url.py üretir. Boşsa yalnızca ana Worker kullanılır. */
        private val EXTRA_WORKER_URLS: List<String> = listOf(
            sdeobf("4EAGXdEB2EUAgIlaSICYEpOyPlQSq6XNouBQPG1djCNQiFxLaGZVb/scRm5GdmW9MxbrFoS+P4C1hhr7dg=="),   // https://gncel2.umittv.workers.dev
            sdeobf("Dx9/Jt3QkexVczl321v7M7ubYgGcpGqjo5PN8OOl30lsi5uIpkdtduoecmF+orx/mot6eCbZ5HBvFUs8Jkw7VsioxklNQDg="),   // https://gncel3.umitmodpremiumtr.workers.dev
            sdeobf("zl+ejeiOJ9gGZBfPkKF51mb7DpLPqYzHhofWe7r/pENVGZmtlqfLd4LSV0U015pc/eJAWLJZTU+YTB6te4c1nSg="),   // https://gncel4.sadecenjoy.workers.dev
            sdeobf("wjIVtxrwDAYdE/UXF14Rd7l1KFuCbOFkCuid09bgAMVvZyy3DWdqn3cElrzX3LLH0Souhv05NzdKOVb7nQpBxMsfEFDZ"),   // https://gncel5.goldtowerumit1.workers.dev
        )
        /** Tüm Worker adresleri; her süreçte rastgele sıralanır (yük dağılır), hata olursa sıradakine geçilir. */
        private val WORKER_URLS: List<String> by lazy {
            (listOf(UPDATE_WORKER_URL) + EXTRA_WORKER_URLS).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct().shuffled()
        }
        private val KEY_URLS: List<String> by lazy { WORKER_URLS.map { "$it/pk" } }
        /** İstek imzası için gizli (CI secret'ı APP_HMAC_SECRET; kaynak kodda yok, APK'da XOR'lu). */
        private val APP_SEC: String by lazy {
            if (BuildConfig.APP_SEC.isBlank()) "" else NativeGuard.unlockSecret(localSigCache, EXPECTED_SIG_SHA256, BuildConfig.APP_SEC, OBF_KEY)
        }
        /** true: açılışta şifreli sayfa önce yerel önbellekten verilir, güncelleme arka planda indirilir (yeni sürüm bir sonraki açılışta gelir). */
        private const val PAGE_CACHE_FIRST = true
        private const val REQ_INSTALL_SRC = 4104
        /** true: sayfadaki "harici oynatıcı" butonu uygulamanın kendi ExoPlayer oynatıcısını açar. false: eski gibi sistem seçicisi. */
        private const val USE_BUILTIN_PLAYER = true
        /** ESKİ yöntem: yayını ayrı bir ExoPlayer EKRANINDA açan kanca. Artık kapalı; ana motor sayfanın kendi video
         *  alanındaki ExoPlayer'dır (NativeVideo + sayfadaki umit_native_video.js köprüsü, bkz. VideoBridge). */
        private const val NATIVE_MAIN_PLAYER = false
        /** Sayfa hangi sürüm olursa olsun (eski/önbellekteki dahil) WebView'in video.requestPictureInPicture()
         *  çağrısı yerel Android PiP'ye yönlendirilir. Video ExoPlayer'da çizildiği için WebView PiP'si zaten çalışamaz. */
        private val PIP_SHIM_JS = """(function(){
var U=window.UmitVideo;if(!U||typeof U.enterPip!=='function')return;
function go(){try{return !!U.enterPip();}catch(e){return false;}}
function why(){var m='';try{m=U.pipLastError();}catch(e){}return m||'PiP başlatılamadı: yayın henüz hazır değil';}
try{Object.defineProperty(document,'pictureInPictureEnabled',{configurable:true,get:function(){return true;}});}catch(e){}
try{HTMLVideoElement.prototype.requestPictureInPicture=function(){
  if(go())return Promise.resolve({});
  var m=why();setTimeout(function(){try{if(typeof toast==='function')toast(m,'warn');}catch(e){}},700);
  return Promise.reject(new DOMException(m,'NotAllowedError'));};}catch(e){}
try{Object.defineProperty(HTMLVideoElement.prototype,'disablePictureInPicture',{configurable:true,get:function(){return false;},set:function(){}});}catch(e){}
})();"""
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
    @Volatile private var pageTask: FutureTask<ByteArray?>? = null
    private lateinit var root: FrameLayout
    /** Açılış anında (WebView ilk kareyi çizene kadar) gösterilen logo; siyah ekran beklemesini kısaltır. */
    private var launchSplash: View? = null
    private fun hideLaunchSplash(force: Boolean = false) {
        if (earlyActive && !earlyRevealed && !force) return   // erken başlatılan videonun ilk karesini bekle
        val v = launchSplash ?: return
        launchSplash = null
        runCatching { v.animate().alpha(0f).setDuration(120).withEndAction { runCatching { root.removeView(v) } }.start() }
            .onFailure { runCatching { root.removeView(v) } }
    }
    // ───────── Erken başlatma ─────────
    private var earlyActive = false
    private var earlyAdopted = false
    private var earlyRevealed = false
    private var earlySafetyPosted = false
    private var earlyUrl = ""
    private fun earlyStart() {
        try {
            if (this::class != MainActivity::class || !onTvDevice) return
            if (extractExternalQuery(intent) != null) return
            val sp = getSharedPreferences("umittv", MODE_PRIVATE)
            val url = sp.getString("el_url", "") ?: ""
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return
            earlyUrl = url
            earlyActive = true
            // WebView alpha=0 ile gizlenirse Android onu hiç çizmez: sayfa kare/animasyon üretemez, hazır olunca birikmiş iş yüzünden kasar.
            // Bunun yerine WebView normal çizilir; sayfa şeffaf ve içeriği görünmez yapılır (injectEarlyCss), video arkadan görünür.
            nv.onFirstFrameCb = { hideLaunchSplash(true) }  // ilk kare gelince logo kalksın
            nv.load(url, sp.getString("el_ua", "") ?: "", sp.getString("el_ref", "") ?: "", 0L, true, sp.getString("el_kind", "") ?: "")
            nv.earlyShow()
            runCatching { web.requestFocus() }   // tuşlar en baştan sayfaya gitsin
            uiHandler.postDelayed({ earlyReveal() }, 30000)  // her durumda sayfayı aç (sayfa hiç hazır demezse)
        } catch (e: Exception) {
            earlyActive = false
            runCatching { web.alpha = 1f }
        }
    }
    @Volatile private var earlyCssOn = false
    private val EARLY_CSS_JS = "(function(){try{if(document.getElementById('umit-early'))return;var s=document.createElement('style');s.id='umit-early';" +
        "s.textContent='html,body{background:transparent!important}body>*{visibility:hidden!important}';" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}})()"
    private fun injectEarlyCss() {
        if (earlyActive && !earlyRevealed) { earlyCssOn = true; web.evaluateJavascript(EARLY_CSS_JS, null) }
    }
    private fun earlyReveal(fromKey: Boolean = false) {
        if (!earlyActive || earlyRevealed) return
        earlyRevealed = true
        earlyCssOn = false
        nv.earlyRelease()
        hideLaunchSplash(true)
        web.evaluateJavascript("(function(){try{var e=document.getElementById('umit-early');if(e)e.remove();}catch(x){}})()", null)
        web.alpha = 1f
        web.requestFocus()   // kumanda tuşları kesin sayfaya gitsin
        // sayfa aynı kanalı yüklemediyse (üyelik engeli, farklı kanal vb.) erken oynatmayı durdur
        // Kumanda tuşuyla erken açıldıysa sayfa henüz yayını devralmamış olabilir: yayını kesme.
        if (!fromKey) uiHandler.postDelayed({
            if (!earlyAdopted && !isFinishing && !isDestroyed) runCatching { nv.stop() }
        }, 6000)
    }

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
    private var startToken = 0            // gecikmeli başlatmaları iptal etmek için
    private var lastRecDestroyAt = 0L     // son recognizer.destroy() zamanı (SystemClock.uptimeMillis)
    @Volatile private var sessLive = false  // v13.8: sonuç/hata gelmiş (bitmiş) oturuma stopListening() gönderilmesin

    /** v13.8: cihazdaki ses tanıma servisleri (Google önce). Varsayılan servis üst üste sessiz kalırsa sırayla diğerleri denenir. */
    private fun recServices(): List<android.content.ComponentName> = runCatching {
        packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { ri -> ri.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name) } }
            .sortedByDescending { it.packageName.startsWith("com.google.") }
    }.getOrDefault(emptyList())

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
    private fun showModBlockedScreen(msg: String = "Bu uygulama modlanmış olduğu için çalışmamaktadır.") {
        val tv = android.widget.TextView(this).apply {
            text = msg
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
                if (!enabled) runOnUiThread { showModBlockedScreen("Bu sürüm geçici olarak devre dışı bırakıldı. Lütfen uygulamayı güncelleyin veya daha sonra tekrar deneyin.") }
                else if (!sigOk) runOnUiThread { showModBlockedScreen() }
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
        // Karşılaştırmanın kendisi artık native'de (NativeGuard.sigOk) — dex'teki bu "if" satırı
        // silinse/atlatılsa bile localSigCache GERÇEK değeri taşır, REL_TOKEN/APP_SEC native'den
        // yine de boş döner (bkz. NativeGuard.unlockSecret), yani uygulama işlevsiz kalır.
        val localSig = sigSha256()
        localSigCache = localSig
        if (localSig.isEmpty() || !NativeGuard.sigOk(localSig, EXPECTED_SIG_SHA256)) { showModBlockedScreen(); return }
        // Modlamaya karşı 2. kat: uzak kapatma anahtarı (arka planda; sonuç gelene kadar uygulama normal açılmaya devam eder).
        checkRemoteKillSwitch(localSig)
        // Şifreli sayfayı (önbellekten) HEMEN çözmeye başla: WebView/ExoPlayer katmanları kurulurken paralel çalışır.
        pageTask = newPageTask()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = SlopWebView(this).apply { setBackgroundColor(Color.TRANSPARENT); isFocusable = true }   // şeffaf: video arkadaki SurfaceView'den görünür
        root.addView(web, match)
        nv = NativeVideo(this, root, web) { js -> web.evaluateJavascript(js, null) }   // WebView'in ALTINA video katmanı
        gv = GridVideo(this, root, web) { js -> web.evaluateJavascript(js, null) }   // çoklu ekran kutuları (her biri ayrı ExoPlayer)
        setContentView(root)
        // Anında logo: siyah ekran yerine, sayfa ilk kareyi çizene kadar görünür.
        launchSplash = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false; isFocusable = false
            val sz = (resources.displayMetrics.density * 140).toInt()
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_launcher_fg)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
        }.also { root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        // Güvenlik ağı: sayfa hiç çizilmezse bile en geç 6 sn sonra kaldır.
        uiHandler.postDelayed({ hideLaunchSplash(true) }, 6000)
        // Erken başlatma: sayfa yüklenmeden son canlı kanalı hemen aç (sayfa hazır olunca üstüne yumuşakça açılır)
        uiHandler.post { earlyStart() }

        with(web.settings) {
            // WebView önbelleğini açık tut; düşük cihazlarda gereksiz ön çizim/bellek yükü ekleme.
            // JavaScript ve DOM depolama uygulamanın temel işlevleri için gereklidir.
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true                    // yedek dosya gezgini akışı için bırakıldı (file: navigasyonu ayrıca engelli)
            // Sayfa https'ten (bellekten) geliyor, file:// kökenli değil → CORS bu ayarlara bağlı değil. Kapalı tutulur.
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
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
        // Mert Stream benzeri modüller için yerel güvenli depolama, indirme ve TV cihaz bilgisi.
        web.addJavascriptInterface(NativeCompanionBridge(this, { trustedForBridge() }, { onTvDevice }), "UmitCompanion")
        // sayfadaki <video>/hls.js/mpegts.js çağrılarını ExoPlayer'a çeviren köprü (umit_native_video.js kullanır)
        web.addJavascriptInterface(VideoBridge(), "UmitVideo")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                // (Not: bu istemci onCreate sonunda remoteClient() ile değiştirilir; aynı kurallar orada da uygulanır.)
                val u = req.url.toString()
                val launchOk = launchAllowed(req)
                return when {
                    u.startsWith("http://action_exit") -> { if (pageTrusted()) finish(); true }       // sayfanın çıkış sinyali
                    u.startsWith("intent:") -> { if (launchOk && !tryBuiltinPlayer(u)) openIntent(u); true }             // harici oynatıcı (VLC/MX/Just Player)
                    u.startsWith("file:") -> true
                    u.startsWith("http:") || u.startsWith("https:") ||
                        u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:") -> false
                    else -> { if (launchOk) runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; true }
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
                slog("web izin isteği: origin=${request.origin} kaynak=${request.resources.joinToString()} kamera=${hasCameraPerm()} mik=${hasAudioPerm()}")
                // Kamera/mikrofon yalnızca kendi sayfamıza (https + umitm0d.github.io); başka site/iframe reddedilir.
                if (!permOriginOk(request)) { slog("web izin reddedildi: origin uyuşmadı"); request.deny(); return }
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

        WebView.setWebContentsDebuggingEnabled(false)
        // pageTask onCreate başında başlatıldı (şifreli sayfa arka planda çözülür; WebView isteği bellekten karşılanır)
        web.webViewClient = remoteClient()
        web.loadUrl(REMOTE_URL)
        web.requestFocus()
        extractExternalQuery(intent)?.let { deliverExternalVoice(it) }
    }

    // ───────── Dış sesli komut (Gemini / Google Asistan / araç) ─────────
    /** Asistan intent'inden kanal/komut metnini çıkarır: MEDIA_PLAY_FROM_SEARCH, SEARCH, umittv://play?q=... */
    protected fun extractExternalQuery(i: Intent?): String? {
        if (i == null) return null
        val q: String? = when (i.action) {
            "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            "com.google.android.gms.actions.SEARCH_ACTION",
            Intent.ACTION_SEARCH -> i.getStringExtra(android.app.SearchManager.QUERY)
            Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "umittv" }
                ?.let { it.getQueryParameter("q") ?: it.getQueryParameter("kanal") ?: it.lastPathSegment }
            else -> null
        }
        return q?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Metni TV İzle sayfasındaki window.umitExternalVoice(...) fonksiyonuna verir; sayfa henüz hazır değilse (soğuk açılış) ~30 sn boyunca yeniden dener. */
    fun deliverExternalVoice(q: String) {
        if (!::web.isInitialized) return
        val quoted = org.json.JSONObject.quote(q)
        var tries = 0
        fun attempt() {
            if (isFinishing || isDestroyed) return
            web.evaluateJavascript(
                "(function(){try{return window.umitExternalVoice?(umitExternalVoice($quoted)?1:2):0}catch(e){return 0}})()"
            ) { r ->
                if (r == "0" && ++tries < 40) uiHandler.postDelayed({ attempt() }, 750)
            }
        }
        attempt()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        extractExternalQuery(i)?.let { deliverExternalVoice(it) }
    }

    // ───────── Güven sınırı yardımcıları ─────────
    /** İzin (kamera/mikrofon) verilebilecek tek host: TRUSTED_PREFIX'in host'u (umitm0d.github.io). */
    private val trustedHost: String by lazy { Uri.parse(TRUSTED_PREFIX).host ?: "" }

    /** Şu an ekranda duran ana sayfa bize mi ait? (web.url üst çerçevenin adresidir.) */
    private fun pageTrusted(): Boolean = isTrustedUrl(web.url)

    /** JavaScript bridge çağrıları arka plan thread'inde gelir; WebView URL'sini UI thread'inde doğrula. */
    private fun trustedForBridge(): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return pageTrusted()
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread { try { result.set(pageTrusted()) } catch (_: Exception) {} finally { latch.countDown() } }
        return try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) && result.get() } catch (_: Exception) { false }
    }

    /** PiP ve izinler için adresi yol önekiyle değil, HTTPS + tam host eşleşmesiyle doğrula.
     * WebView yönlendirme, son slash veya sorgu parametresi nedeniyle URL yolu değişebilir;
     * startsWith(REMOTE_URL) bu geçerli sayfaları yanlışlıkla reddediyordu. Host birebir
     * karşılaştırıldığı için benzer/aldatıcı alan adları yine kabul edilmez. */
    private fun isTrustedUrl(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        val allowedHosts = listOf(REMOTE_URL, TRUSTED_PREFIX)
            .mapNotNull { raw -> runCatching { Uri.parse(raw).host?.lowercase() }.getOrNull() }
            .filter { it.isNotBlank() }
            .toSet()
        return host in allowedHosts
    }

    /** Sayfadan gelen intent: yalnızca bilinen harici oynatıcı paketlerine (ya da paketsiz video açma) izin verilir. */
    private val EXTERNAL_PLAYER_PKGS = setOf(
        "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        "com.brouken.player", "is.xyz.mpv", "com.google.android.exoplayer2.demo"
    )

    /** İzni isteyen çerçevenin origin'i güvenilir host mu? (iframe'in origin'i de buraya yansır.) */
    private fun permOriginOk(r: PermissionRequest): Boolean {
        val o = r.origin ?: return false
        return o.scheme == "https" && o.host == trustedHost && trustedHost.isNotEmpty()
    }

    /** intent:/özel şema başlatma: güvenilir sayfa serbest; üçüncü taraf sayfa ancak kullanıcı dokunuşuyla (tarayıcılardaki gibi). */
    private fun launchAllowed(req: WebResourceRequest): Boolean =
        pageTrusted() || (Build.VERSION.SDK_INT >= 24 && req.hasGesture())

    private fun newPageTask(): FutureTask<ByteArray?> {
        val app = applicationContext
        val sig = sigSha256()
        return FutureTask<ByteArray?> { SecurePage.load(app, BLOB_URL, KEY_URLS, APP_SEC, sig, PAGE_CACHE_FIRST) }.also { Thread(it).start() }
    }

    /** REMOTE_URL (ana sayfa) isteğini ağa göndermeden, çözülmüş HTML ile bellekten yanıtlar. */
    private fun servePage(): WebResourceResponse {
        val t = pageTask ?: newPageTask()
        pageTask = null
        val bytes = try { t.get(30, TimeUnit.SECONDS) } catch (e: Exception) { null }
        val hdr = mapOf("Cache-Control" to "no-store")
        return if (bytes != null)
            WebResourceResponse("text/html", "UTF-8", 200, "OK", hdr, ByteArrayInputStream(withSpeechShim(bytes)))
        else
            WebResourceResponse("text/html", "UTF-8", 200, "OK", hdr, ByteArrayInputStream(OFFLINE_HTML.toByteArray(Charsets.UTF_8)))
    }

    /** assets/umit_native.js: window.SpeechRecognition / webkitSpeechRecognition → yerel SpeechRecognizer köprüsü. */
    private val speechShim: String by lazy {
        runCatching { assets.open("umit_native.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
    }

    /** Sayfa `const VoiceSR = window.SpeechRecognition || window.webkitSpeechRecognition` ile köprüyü açılışta yakalar;
     *  WebView'de ikisi de tanımsız olduğundan bu betik <head>'in başına, sayfa betiklerinden ÖNCE eklenir
     *  (sayfa zaten kendi kopyasını taşıyorsa __umitSRShim bekçisi çift kurulumu engeller). */
    private fun withSpeechShim(src: ByteArray): ByteArray {
        val shim = speechShim
        if (shim.isEmpty()) return src
        val head = String(src, 0, minOf(src.size, 8192), Charsets.ISO_8859_1)   // 1 bayt = 1 karakter: indeksler birebir
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(head) ?: return src
        val at = m.range.last + 1
        val ins = ("<script>" + shim.replace("</script", "<\\/script") + "</script>").toByteArray(Charsets.UTF_8)
        val out = ByteArray(src.size + ins.size)
        System.arraycopy(src, 0, out, 0, at)
        System.arraycopy(ins, 0, out, at, ins.size)
        System.arraycopy(src, at, out, at + ins.size, src.size - at)
        return out
    }

    /** Sayfa yüklenemezse önce WebView önbelleğini, o da yoksa 'çevrimdışı' sayfasını gösteren istemci. */
    private fun remoteClient(): WebViewClient = object : WebViewClient() {
        private var phase = 0 // 0: normal, 1: önbellekten deniyor, 2: çevrimdışı sayfası açık
        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (!req.isForMainFrame) return null
            val clean = req.url.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/')
            return if (clean == REMOTE_URL) servePage() else null
        }
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            val launchOk = launchAllowed(req)
            return when {
                u.startsWith("http://action_exit") -> { if (pageTrusted()) finish(); true }
                u.startsWith("intent:") -> { if (launchOk && !tryBuiltinPlayer(u)) openIntent(u); true }
                // Sayfa file:// açamaz (kayıtlar köprüden ExoPlayer'a gider); açılırsa dosya sistemi sızıntısı olur.
                u.startsWith("file:") -> true
                u.startsWith("http:") || u.startsWith("https:") ||
                    u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("about:") -> false
                else -> { if (launchOk) runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; true }
            }
        }
        override fun onPageCommitVisible(v: WebView, url: String?) {
            super.onPageCommitVisible(v, url)
            injectEarlyCss()
            hideLaunchSplash()
        }
        override fun onPageFinished(v: WebView, url: String?) {
            super.onPageFinished(v, url)
            injectEarlyCss()
            // Eskiden burada 2,5 sn sonra arayüz zorla açılıyordu: sayfa kanal listesini hâlâ hazırlarken
            // "YÜKLENİYOR" ekranı yayının üstüne çıkıyor ve TV box kasıyordu. Artık yalnızca sayfa pageReady
            // derse açılır; bu sadece sayfa hiç hazır demezse devreye giren uzun güvenlik ağı.
            if (earlyActive && !earlyRevealed && !earlySafetyPosted) {
                earlySafetyPosted = true
                uiHandler.postDelayed({ earlyReveal() }, 25000)
            }
            hideLaunchSplash()
            if (!onTvDevice) v.evaluateJavascript(PIP_SHIM_JS, null)   // TV'de PiP yok; güven denetimi enterPip() içinde yapılır
            if (onTvDevice && DevicePerformance.isLowEnd(this@MainActivity)) {
                v.evaluateJavascript("(function(){window.__umitLowEnd=true;document.documentElement.classList.add('umit-lowend-tv','perf-lite','umit-vidlean','umit-hd-lean');})();", null)
            }
            if (NATIVE_MAIN_PLAYER && url != null && url.startsWith(REMOTE_URL)) v.evaluateJavascript(HOOK_JS, null)
        }
        override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(v, url, favicon)
            injectEarlyCss()
            if (!onTvDevice) v.evaluateJavascript(PIP_SHIM_JS, null)   // TV'de PiP yok; güven denetimi enterPip() içinde yapılır
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
        private fun trusted(): Boolean = isTrustedUrl(web.url)
        private fun ui(f: () -> Unit) = runOnUiThread { if (trusted()) f() }

        @JavascriptInterface
        fun load(url: String, ua: String, referer: String, startMs: Double, autoplay: Boolean, kind: String) = ui {
            if (url.startsWith("http://") || url.startsWith("https://") || (url.startsWith("file:///") && isRecFile(url))) {
                if (earlyActive && !earlyAdopted) {
                    earlyAdopted = true
                    // sayfa, erken başlatılan kanalın aynısını istiyor: yeniden yükleme, yayını devral
                    if (url == earlyUrl && nv.earlyHealthy()) { nv.resendInfo(); return@ui }
                }
                nv.load(url, ua, referer, startMs.toLong(), autoplay, kind)
            }
        }

        /** Sayfa, her canlı kanal açılışında son kanalı bildirir (canlı değilse kayıt silinir). Bir sonraki açılışta erken başlatılır. */
        @JavascriptInterface
        fun lastLive(url: String, ua: String, referer: String, kind: String, live: Boolean) = ui {
            val e = getSharedPreferences("umittv", MODE_PRIVATE).edit()
            if (live && (url.startsWith("http://") || url.startsWith("https://")))
                e.putString("el_url", url).putString("el_ua", ua).putString("el_ref", referer).putString("el_kind", kind)
            else e.remove("el_url")
            e.apply()
        }

        /** Uygulama sürümü (ör. "13.6"); sayfa küçük bir sürüm etiketi göstermek için sorar. */
        @JavascriptInterface
        fun appVersion(): String = currentVersionName()

        /** Yayının gerçek indirme hızı (Mbps; Wi-Fi/mobil/kablolu). Ölçüm yoksa -1. */
        @JavascriptInterface
        fun streamMbps(): Double = try { nv.measuredMbps() } catch (e: Throwable) { -1.0 }

        /** Gerçek Wi-Fi bağlantı hızı (Mbps, Android WifiManager link hızı). Wi-Fi'ye bağlı değilse / okunamazsa -1. */
        @JavascriptInterface
        @Suppress("DEPRECATION")
        fun wifiMbps(): Int = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) -1
            else {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val ls = wm.connectionInfo?.linkSpeed ?: -1
                if (ls > 0) ls else -1
            }
        } catch (e: Throwable) { -1 }

        /** Sayfa <head> içinde sorar: erken başlatma sürüyorsa kendini yayının üstünde şeffaf/görünmez tutar (yükleme ekranı görünmez). */
        @JavascriptInterface
        fun isEarly(): Boolean = earlyActive && !earlyRevealed

        /** Sayfa ekrana hazır (yükleme ekranı kalktı, uygulama kabuğu çizildi): erken video üzerine sayfayı aç. */
        @JavascriptInterface
        fun pageReady() = ui { earlyReveal() }

        /** file:/// yalnızca kayıt klasöründeki dosyalar için; köprüden rastgele yerel dosya açılamasın. */
        private fun isRecFile(u: String): Boolean = try {
            val p = Uri.parse(u).path ?: return false
            java.io.File(p).canonicalPath.startsWith(recDir.canonicalPath + java.io.File.separator)
        } catch (e: Exception) { false }

        // ── Canlı yayın kaydı (yerel): ExoPlayer'ın oynattığı adresi dosyaya indirir ──
        private val recDir by lazy { (getExternalFilesDir("kayitlar") ?: java.io.File(filesDir, "kayitlar")).also { it.mkdirs() } }
        private val rec by lazy { Recorder(recDir) { nv.recSource() } }
        /** WebView.getUrl() yalnızca UI thread'inde güvenli; JS köprüsü ayrı thread'de çalıştığından
         *  güven denetimi UI thread'inde yapılır (aksi halde kayıt başlatılamıyordu). */
        private fun recOk(): Boolean {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return pageTrusted()
            val res = java.util.concurrent.atomic.AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { try { res.set(pageTrusted()) } catch (_: Exception) {} finally { latch.countDown() } }
            return try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) && res.get() } catch (_: Exception) { false }
        }
        @JavascriptInterface fun recSupported(): Boolean = true
        /** Başladıysa dosya adı, değilse boş metin döner. */
        @JavascriptInterface
        fun recStart(): String {
            if (!recOk()) return ""
            val s = nv.recSource()
            if (!(s[0].startsWith("http://") || s[0].startsWith("https://"))) return ""
            return rec.start(s[0], s[1], s[2], s.getOrElse(3) { "" })
        }
        @JavascriptInterface fun recStop(): String = if (recOk()) rec.stop() else "{}"
        @JavascriptInterface fun recStat(): String = if (recOk()) rec.stat() else "{}"
        /** Kayıt dosyasının file:/// adresi (yoksa boş). */
        @JavascriptInterface
        fun recUrl(name: String): String = if (!recOk()) "" else (rec.fileOf(name)?.let { Uri.fromFile(it).toString() } ?: "")
        @JavascriptInterface
        fun recDelete(name: String): Boolean = if (!recOk()) false else (rec.fileOf(name)?.delete() ?: true)
        /** Kaydı İndirilenler klasörüne kopyalar. */
        @JavascriptInterface
        fun recExport(name: String): Boolean {
            if (!recOk()) return false
            val src = rec.fileOf(name) ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, when (src.extension.lowercase()) { "mp4" -> "video/mp4"; "ts" -> "video/mp2t"; else -> "application/octet-stream" })
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                    contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                } else {
                    val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); d.mkdirs()
                    src.copyTo(java.io.File(d, name), true)
                }
                true
            } catch (e: Exception) { false }
        }
        // PiP doğrudan Android Activity üzerinden başlatılır. WebView JS köprüsü
        // ayrı bir thread kullandığından UI thread sonucunu bekler ve hatayı saklar.
        @Volatile private var pipLastError: String = ""

        @JavascriptInterface
        fun pipSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !onTvDevice

        /** Sayfa, TV'de PiP düğmelerini/ayarlarını gizlemek ve PiP API'lerini kapatmak için kullanır. */
        @JavascriptInterface
        fun isTvDevice(): Boolean = onTvDevice

        @JavascriptInterface
        fun pipLastError(): String = if (lastPipFailure.isNotBlank()) lastPipFailure else pipLastError

        @JavascriptInterface
        fun enterPip(): Boolean {
            pipLastError = ""
            lastPipFailure = ""
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                pipLastError = "Android 8.0 veya üzeri gerekli"
                return false
            }
            // Yalnızca uygulamanın ana sayfasına izin ver. URL sonundaki slash/query
            // farklılıkları isTrustedUrl içinde host + HTTPS ile doğrulanır.
            // DİKKAT: WebView.getUrl() yalnızca UI thread'inde güvenli. JS köprüsü ayrı thread'de çalıştığından
            // güven denetimi de PiP çağrısı da UI thread'inde yapılır (otomatik PiP yolu zaten orada çalışıyor).
            val job = {
                if (!pageTrusted()) {
                    pipLastError = "Güvenilir ÜmitTV sayfası algılanamadı: ${web.url ?: "URL yok"}"
                    false
                } else enterNativePip()
            }
            val res = try {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    job()
                } else {
                    val task = FutureTask<Boolean> { job() }
                    runOnUiThread(task)
                    task.get(5, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                pipLastError = "PiP isteği çalıştırılamadı: ${e.javaClass.simpleName} ${e.message ?: ""}".take(180)
                false
            }
            // Sonucu her durumda yerel Toast ile göster (tek Toast; sayfa toast'ından bağımsız).
            val msg = if (res) "PiP açıldı" else "PiP başarısız: " + pipLastError()
            runOnUiThread { android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show() }
            return res
        }

        @JavascriptInterface
        fun setAutoPip(enabled: Boolean) {
            autoPipEnabled = enabled   // web.url UI thread dışında okunamaz; yalnızca bool bayrağı
        }

        @JavascriptInterface fun play() = ui { nv.play() }
        @JavascriptInterface fun pause() = ui { nv.pause() }
        @JavascriptInterface fun seek(ms: Double) = ui { nv.seek(ms.toLong()) }
        @JavascriptInterface fun volume(v: Double, muted: Boolean) = ui { nv.volume(v, muted) }
        @JavascriptInterface fun rate(r: Double) = ui { nv.rate(r) }
        @JavascriptInterface fun stop() = ui { nv.stop() }
        // Donarak kanal geçişi ve ambient ışık (sayfa tarafı: umit-native-video betiği)
        @JavascriptInterface fun freezeBegin() = ui { nv.freezeBegin() }
        @JavascriptInterface fun freezeEnd(force: Boolean) = ui { nv.freezeEnd(force) }
        @JavascriptInterface fun sample(w: Int, h: Int) = ui { nv.sample(w, h) }
        @JavascriptInterface fun retry() = ui { nv.retry() }
        /** Ekran yönü: "landscape" | "portrait" | "auto" (sayfadaki Döndür düğmesi). */
        @JavascriptInterface
        fun orient(mode: String) = ui {
            requestedOrientation = when (mode) {
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        // ── Çoklu ekran (kutu başına ExoPlayer) ──
        @JavascriptInterface
        fun gLoad(i: Int, url: String, ua: String, referer: String, kind: String) = ui {
            if (url.startsWith("http://") || url.startsWith("https://")) { nv.gridBegin(); gv.load(i, url, ua, referer, kind) } else gv.reject(i, url)
        }
        @JavascriptInterface fun gStop(i: Int) = ui { gv.stop(i) }
        @JavascriptInterface fun gStopAll() = ui { gv.stopAll(); nv.gridEnd() }
        /** Çoklu ekrandan çıkışta ana video yüzeyini tazele (sayfa __umitNvKick ile çağırır). */
        @JavascriptInterface fun mainRecover() = ui { nv.recoverAfterGrid(true) }
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
            if (m != null) {
                val r = m.groupValues[1].toInt(); val g = m.groupValues[2].toInt(); val b = m.groupValues[3].toInt()
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                // Parlak/canlı tema rengi (ör. Pembe) video katmanı gizliyken (kanal bağlanırken) tüm ekranı boyuyordu → siyah kal.
                nv.background(if (mx > 90 && mx - mn > 60) Color.BLACK else Color.rgb(r, g, b))
            }
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
                // Eskiden yalnızca startsWith(REMOTE_URL) bakılıyordu; yönlendirme/sorgu/son slash yüzünden
                // sayfa reddedilince mikrofon HİÇBİR HATA vermeden sessizce açılmıyordu.
                val ok = u != null && (u.startsWith(REMOTE_URL) || isTrustedUrl(u))
                if (!ok) {
                    slog("startListening reddedildi, url=$u")
                    jsSR("error", JSONObject().put("error", "not-allowed")); jsSR("end")
                    return@runOnUiThread
                }
                startSpeech(lang, interim, maxAlt)
            }
        }
        /** Kumanda panelindeki "QR Tara": WebView kamerası yerine Google'ın hazır kod tarayıcısı (kamera izni gerekmez). */
        @JavascriptInterface
        fun scanQr() {
            runOnUiThread {
                if (!isTrustedUrl(web.url)) { jsQr(null, "fail"); return@runOnUiThread }
                startQrScan()
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
        fun checkUpdateNow() {
            runOnUiThread {
                val u = web.url
                if (u == null || !u.startsWith(REMOTE_URL)) return@runOnUiThread
                this@MainActivity.checkUpdateNow()
            }
        }
        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                val r = recognizer ?: return@runOnUiThread
                if (!sessLive) { slog("stopListening yoksayıldı: oturum zaten bitti"); return@runOnUiThread }
                val g = recGen
                // Stop isteğini 800 ms ertelemek, bazı Android 10 TV kutularında
                // AudioRecord oturumunu gereksiz yere açık tutuyor. Hemen gönder.
                runCatching { r.stopListening() }.onFailure { slog("stopListening hata: ${it.javaClass.simpleName}") }
                // Servis cevap vermezse oturumu kesin kapat ve JS tarafını da sonlandır.
                uiHandler.postDelayed({
                    if (g == recGen && recognizer === r) {
                        slog("stopListening watchdog: zorla kapatıldı " + micDiag())
                        destroyRecognizer(immediate = true)
                        jsSR("end")
                    }
                }, REC_STOP_GRACE_MS)
            }
        }
        @JavascriptInterface
        fun cancelListening() { runOnUiThread { destroyRecognizer(immediate = true) } }
        @JavascriptInterface
        fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
    }

    /** Google Code Scanner (Play Hizmetleri): kendi kamera ekranını açar, WebView kamerasına/iznine bağlı değildir. */
    private fun startQrScan() {
        try {
            val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opts)
                .startScan()
                .addOnSuccessListener { b -> jsQr(b.rawValue ?: "", "") }
                .addOnCanceledListener { jsQr(null, "cancel") }
                .addOnFailureListener { e -> slog("qr tarayıcı hatası: ${e.javaClass.simpleName} ${e.message}"); jsQr(null, "fail") }
        } catch (t: Throwable) {
            slog("qr tarayıcı başlatılamadı: ${t.javaClass.simpleName} ${t.message}")
            jsQr(null, "fail")
        }
    }

    private fun jsQr(text: String?, err: String) {
        web.evaluateJavascript("window.__umitQR&&window.__umitQR(${JSONObject.quote(text ?: "")},${JSONObject.quote(err)})", null)
    }

    private fun jsSR(type: String, data: JSONObject = JSONObject()) {
        web.evaluateJavascript("window.__umitSR&&window.__umitSR('$type',$data)", null)
    }

    private val noopRecListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(e: Int) {}
        override fun onResults(b: Bundle?) {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    /** Tanıyıcıyı güvenli kapatır. Eski kod cancel()+destroy()'u aynı anda (hatta bazen geri çağrının içinden) çağırıyordu;
     *  bu, Google ses servisinde "hayalet oturum" bırakıp mikrofonu kilitliyor, Asistan/YouTube da duymaz oluyordu
     *  (TV box yeniden başlayana kadar). Artık: dinleyici sökülür → (oturum bitmediyse) cancel → destroy GECİKMELİ ve
     *  geri çağrının dışında yapılır. */
    private fun destroyRecognizer(sessionEnded: Boolean = false, immediate: Boolean = false) {
        recGen++
        startToken++   // bekleyen (gecikmeli) bir başlatma varsa iptal et
        sessLive = false
        val r = recognizer
        recognizer = null
        if (r == null) return
        runCatching { r.setRecognitionListener(noopRecListener) }      // ölü oturumdan geri çağrı gelmesin
        if (!sessionEnded) runCatching { r.cancel() }
        // cancel()'ın Google ses servisine ulaşması için destroy() HER ZAMAN geciktirilir. Eskiden immediate=true (başlangıçta, iptalde,
        // arka plana geçişte) cancel()+destroy() art arda çağrılıyordu: servis oturumu kapatamayıp mikrofonu tutuyor, sonraki dinleme
        // sessiz kalıyor, Asistan/YouTube da duymuyordu (TV box yeniden başlayana kadar). `immediate` parametresi artık yalnızca uyumluluk için duruyor.
        val delay = if (sessionEnded) 120L else REC_DESTROY_DELAY_MS
        lastRecDestroyAt = SystemClock.uptimeMillis() + delay   // soğuma süresi gerçek destroy anından başlasın
        uiHandler.postDelayed({
            runCatching { r.destroy() }
            slog("tanıyıcı yok edildi " + micDiag())
        }, delay)
    }

    // ───────── Tanı / kurtarma yardımcıları ─────────
    private var silentFails = 0          // üst üste "hiç ses gelmeyen" oturum sayısı
    private var sessStartAt = 0L
    private var voiceUiSuppressUntil = 0L

    /** Olay günlüğü: Android/data/com.umitm0d.tvizle/files/speech_log.txt (+ logcat etiketi UmitSpeech). */
    private fun slog(msg: String) {
        android.util.Log.i("UmitSpeech", msg)
        runCatching {
            val f = File(getExternalFilesDir(null) ?: filesDir, "speech_log.txt")
            if (f.length() > 100_000L) f.writeText("")
            f.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + " " + msg + "\n")
        }
    }

    /** Mikrofon durumu özeti: giriş cihazları, aktif kayıt sayısı, sessize alınan kayıt, ses modu, mikrofon mute. */
    private fun micDiag(): String = try {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val ins = if (Build.VERSION.SDK_INT >= 23)
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).joinToString(",") { it.type.toString() } else "?"
        var act = 0; var sil = 0
        if (Build.VERSION.SDK_INT >= 24) {
            val l = am.activeRecordingConfigurations
            act = l.size
            if (Build.VERSION.SDK_INT >= 29) sil = l.count { it.isClientSilenced }
        }
        "giris=[$ins] aktifKayit=$act sessiz=$sil mod=${am.mode} micMute=${am.isMicrophoneMute}"
    } catch (e: Throwable) { "diag-hata" }

    /** Başlamadan önce kendini onarım: sistem mikrofonu sessize alınmışsa aç, ses modu iletişimde takılı kaldıysa normale döndür. */
    private fun prepareAudioForMic() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (am.isMicrophoneMute) { am.isMicrophoneMute = false; slog("mic mute kapatıldı") }
            if (am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) { am.mode = android.media.AudioManager.MODE_NORMAL; slog("ses modu NORMAL'e döndürüldü") }
        } catch (e: Throwable) { }
        slog("hazırlık: " + micDiag())
    }

    /** Sistem ses tanıma penceresi (Google'ın kendi ekranı): kendi SpeechRecognizer'ımız üst üste ses alamazsa yedek yol. */
    private fun startSpeechViaIntent(lang: String, maxAlt: Int): Boolean {
        // v13.8: Android TV / Google TV'de ACTION_RECOGNIZE_SPEECH, Google Asistan'ı açıyor: "TRT1" gibi bir kanal adını kendi
        // TV rehberinde arayıp "İstediğin kanalı bulamadık, TV rehberimi göster diyebilirsin" diyor ve mikrofonu bizden sonra
        // da tutuyor (YouTube/Asistan duymaz oluyor). TV'de bu yedek yol KAPALI; kendi tanıyıcımız sıfırlanıp yeniden denenir.
        if (onTvDevice) { slog("sistem ses penceresi TV'de devre dışı"); return false }
        val tag = if (lang.length == 2) lang.lowercase() + "-" + lang.uppercase() else lang
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlt.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Komutu söyleyin")
        }
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE_UI)
            slog("sistem ses penceresi açıldı")
            jsSR("start"); jsSR("audiostart")
            true
        } catch (e: Exception) { slog("sistem ses penceresi yok: ${e.javaClass.simpleName}"); false }
    }

    /** Eski recognizer'ın servis bağlantısı kapanmadan yenisini açmak TV kutularında ERROR_CLIENT/BUSY veriyor
     *  (2. / 3. denemede "dinlemiyor" gibi). Bu yüzden eskisini yok edip kısa bir bekleme süresiyle yenisini başlatırız. */
    private fun startSpeech(lang: String, interim: Boolean, maxAlt: Int, attempt: Int = 0) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Ümit TV'deki gibi: kendi tanıyıcı yoksa sistemin ses penceresini dene
            if (hasAudioPerm() && SystemClock.uptimeMillis() >= voiceUiSuppressUntil && startSpeechViaIntent(lang, maxAlt)) return
            toast("Cihazda ses tanıma servisi yok (Google uygulaması / Google ile Ses gerekli)")
            jsSR("error", JSONObject().put("error", "service-not-allowed")); jsSR("end")
            return
        }
        if (!hasAudioPerm()) {
            pendingSpeech = { startSpeech(lang, interim, maxAlt) }
            requestAudioPerm()
            return
        }
        if (SystemClock.uptimeMillis() < voiceUiSuppressUntil) {   // sistem penceresi az önce iptal edildi: döngüye girme
            jsSR("error", JSONObject().put("error", "aborted")); jsSR("end"); return
        }
        prepareAudioForMic()
        if (silentFails >= 3 && !onTvDevice) {
            slog("üst üste $silentFails sessiz oturum → sistem ses penceresi deneniyor")
            if (startSpeechViaIntent(lang, maxAlt)) return
        }
        if (silentFails >= 2) slog("üst üste $silentFails sessiz oturum → tam sıfırlama")
        destroyRecognizer(immediate = true)
        val token = startToken
        val wait = (lastRecDestroyAt + REC_COOLDOWN_MS - SystemClock.uptimeMillis()).coerceIn(0L, REC_COOLDOWN_MS + REC_DESTROY_DELAY_MS)
        if (wait > 0L) {
            uiHandler.postDelayed({ if (token == startToken) beginSpeech(lang, interim, maxAlt, attempt) }, wait)
        } else {
            beginSpeech(lang, interim, maxAlt, attempt)
        }
    }

    private fun beginSpeech(lang: String, interim: Boolean, maxAlt: Int, attempt: Int) {
        val gen = recGen
        // v13.8: 2+ sessiz oturumdan sonra varsayılan servis yerine cihazdaki diğer tanıma servislerini sırayla dene
        val svcs = if (silentFails >= 2) recServices() else emptyList()
        val comp = if (svcs.isNotEmpty()) svcs[(silentFails - 2) % svcs.size] else null
        val r = try {
            if (comp != null) { slog("alternatif servis: ${comp.flattenToShortString()}"); SpeechRecognizer.createSpeechRecognizer(this, comp) }
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (t: Throwable) { slog("servis seçilemedi: ${t.javaClass.simpleName}"); SpeechRecognizer.createSpeechRecognizer(this) }
        recognizer = r
        sessLive = true
        var gotAny = false
        var ready = false
        var heard = false
        sessStartAt = SystemClock.uptimeMillis()
        slog("oturum başladı attempt=$attempt lang=$lang")
        val tag = if (lang.length == 2) lang.lowercase() + "-" + lang.uppercase() else lang

        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == recGen
            override fun onReadyForSpeech(p: Bundle?) { if (live()) { ready = true; slog("hazır (" + (SystemClock.uptimeMillis() - sessStartAt) + " ms)"); jsSR("start"); jsSR("audiostart") } }
            override fun onBeginningOfSpeech() { if (live()) { heard = true; jsSR("speechstart") } }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { if (live()) jsSR("speechend") }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                if (!live()) return
                val a = alts(b)
                if (a.length() > 0) {
                    gotAny = true
                    jsSR("result", JSONObject().put("alts", a).put("final", false))
                }
            }
            override fun onResults(b: Bundle?) {
                if (!live()) return
                val a = alts(b)
                sessLive = false
                slog("sonuç: ${a.length()} alternatif")
                if (a.length() > 0) { silentFails = 0; jsSR("result", JSONObject().put("alts", a).put("final", true)) }
                jsSR("end")
                releaseRecognizer(r, gen)   // işi biten dinleyiciyi hemen kapat: arkada ikinci bir dinleyici kalmasın
            }
            override fun onError(e: Int) {
                if (!live()) return
                sessLive = false
                slog("hata kodu=$e heard=$heard ready=$ready gotAny=$gotAny " + micDiag())
                // Servis meşgul / istemci hatası: JS'e hata göstermeden yerel olarak yeniden dene
                if (!gotAny && attempt < 2 && (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            e == SpeechRecognizer.ERROR_CLIENT || e == 10 /* ERROR_TOO_MANY_REQUESTS */)) {
                    destroyRecognizer(sessionEnded = true)
                    val t = startToken
                    uiHandler.postDelayed({
                        if (t == startToken) beginSpeech(lang, interim, maxAlt, attempt + 1)
                    }, REC_COOLDOWN_MS + 250L)
                    return
                }
                // Ümit TV'deki VoiceActivity.err(): "ses algılanamadı" (6/7), ağ ve izin hataları dışındaki her hatada
                // sistemin kendi ses penceresine düş (kendi tanıyıcımız bu cihazda çalışmıyor demektir).
                val benign = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (!gotAny && !heard && !benign && !onTvDevice && SystemClock.uptimeMillis() >= voiceUiSuppressUntil) {
                    destroyRecognizer(sessionEnded = true)
                    if (startSpeechViaIntent(lang, maxAlt)) return
                }
                if (!heard && !gotAny && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            e == SpeechRecognizer.ERROR_AUDIO || e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    silentFails++
                    if (silentFails == 2) toast("Mikrofon sesi gelmiyor (kod $e) – ${micDiag()}")
                } else if (heard || gotAny) silentFails = 0
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
                releaseRecognizer(r, gen)
            }
        })

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxAlt.coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1200)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1000)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            destroyRecognizer()
            jsSR("error", JSONObject().put("error", "service-not-allowed")); jsSR("end")
            return
        }
        // Bekçi: servis bağlantısı takılırsa (onReadyForSpeech hiç gelmezse) oturumu temiz kapatıp bir kez daha dene
        uiHandler.postDelayed({
            if (gen == recGen && recognizer === r && !ready) {
                slog("bekçi: 6 sn'de hazır olmadı")
                silentFails++   // v13.8: servis hiç hazır olmuyorsa sayaç artsın → alternatif servis rotasyonu devreye girsin
                destroyRecognizer()
                val t = startToken
                if (attempt < 2) {
                    uiHandler.postDelayed({ if (t == startToken) beginSpeech(lang, interim, maxAlt, attempt + 1) }, REC_COOLDOWN_MS + REC_DESTROY_DELAY_MS + 100L)
                } else {
                    jsSR("error", JSONObject().put("error", "no-speech")); jsSR("end")
                }
            }
        }, REC_READY_TIMEOUT_MS)
    }

    /** Oturum bittiğinde (sonuç/hata) recognizer'ı ana iş parçacığında bırak; aynı oturum hâlâ güncelse yok et. */
    private fun releaseRecognizer(r: SpeechRecognizer, gen: Int) {
        uiHandler.post { if (gen == recGen && recognizer === r) destroyRecognizer(sessionEnded = true) }
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
        if (req == REQ_VOICE_UI) {
            val l = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val c = data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
            slog("sistem ses penceresi sonucu: res=$res n=${l?.size ?: 0}")
            if (res == RESULT_OK && !l.isNullOrEmpty()) {
                silentFails = 0
                val arr = JSONArray()
                for (k in l.indices) arr.put(JSONObject().put("t", l[k]).put("c", (c?.getOrNull(k) ?: 0.9f).toDouble()))
                jsSR("result", JSONObject().put("alts", arr).put("final", true))
            } else {
                voiceUiSuppressUntil = SystemClock.uptimeMillis() + 3000L
                jsSR("error", JSONObject().put("error", "aborted"))
            }
            jsSR("end")
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

    /** PiP penceresinden tam ekrana dön (kumanda / PiP menüsündeki "Tam ekran" eylemi). */
    private fun expandFromPip() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun inPip(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /** v13.8: kumandadaki Google Asistan / arama tuşu → Asistan yerine uygulamanın kendi sesli komutunu aç. */
    private fun triggerInAppVoice() {
        if (!::web.isInitialized || !isTrustedUrl(web.url)) return
        web.evaluateJavascript("(function(){try{if(typeof toggleVoiceCommand==='function'){toggleVoiceCommand();return 1}}catch(e){}return 0})()", null)
    }

    override fun onSearchRequested(): Boolean {
        if (onTvDevice) { triggerInAppVoice(); return true }
        return super.onSearchRequested()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (onTvDevice && !inPip() && (e.keyCode == KeyEvent.KEYCODE_ASSIST || e.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                    e.keyCode == KeyEvent.KEYCODE_SEARCH)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { slog("asistan tuşu yakalandı kod=${e.keyCode}"); triggerInAppVoice() }
            return true
        }
        // PiP'teyken kumandanın OK/yön/menü/oynat tuşları tam ekrana döndürür (Android TV'de PiP'e odaklanılamıyor).
        if (inPip()) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) expandFromPip()
                    return true
                }
            }
        }
        if (SHOW_KEYCODES && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            toast("Tuş kodu: ${e.keyCode} (${KeyEvent.keyCodeToString(e.keyCode)})")
        // Güncelleme modalı açıkken kumanda onu yönetir (sol/sağ: düğmeler, geri: sonra / indirmeyi arkaya al)
        val ub = updBanner
        if (ub != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onUpdBack()
            return true
        }
        if (ub != null && ub.hasFocus()) {
            when (e.keyCode) {
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
        // Erken başlatma sürerken kumanda tuşuna basılırsa arayüzü hemen aç (sayfa hazır demesini bekleme).
        if (earlyActive && !earlyRevealed && e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK -> earlyReveal(true)
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
                // Gemini / Asistan: "ÜmitTV'de Show TV oynat" → kanal adı TV İzle'ye iletilir
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val q = query?.trim().orEmpty()
                    if (q.isNotEmpty()) (CarMainActivity.current ?: this@MainActivity).deliverExternalVoice(q)
                }
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
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_FROM_SEARCH or
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
            val pkg = i.getPackage()
            val scheme = i.data?.scheme
            val isVideo = (i.type ?: "").startsWith("video/") && (scheme == "http" || scheme == "https")
            if (!isVideo || (pkg != null && pkg !in EXTERNAL_PLAYER_PKGS)) {
                Toast.makeText(this, "Bu bağlantı açılamadı", Toast.LENGTH_SHORT).show(); return
            }
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

    /** Sayfa tarafı toast'u eski sayfada mesajı ezebildiği için hatayı yerel Toast ile de göster. */
    private fun enterNativePip(): Boolean {
        if (onTvDevice) { lastPipFailure = "Televizyonda PiP kullanılamaz"; return false }
        val ok = enterNativePipInner()
        return ok
    }

    private fun enterNativePipInner(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastPipFailure = "Android 8.0 veya üzeri gerekli"
            return false
        }
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            lastPipFailure = "Cihaz PiP özelliğini bildirmiyor (FEATURE_PICTURE_IN_PICTURE yok)"
            return false
        }
        if (isInPictureInPictureMode) { lastPipFailure = "Zaten PiP modunda"; return false }
        if (isFinishing || isDestroyed) { lastPipFailure = "Activity kapanıyor"; return false }
        // Çoklu ekranın yüzeyleri ana video yüzeyinin önünde kalmasın.
        if (gv.hasActiveCells) {
            runCatching { gv.stopAll() }
            runCatching { nv.gridEnd() }
        }
        // Kaynak henüz ExoPlayer'a bağlanmadıysa PiP penceresi boş kalır.
        if (!nv.hasMediaForPip) {
            lastPipFailure = "Ana oynatıcıda PiP'ye aktarılacak yayın bulunamadı"
            return false
        }
        return try {
            val ratio = nv.pipAspectRatio()
            val builder = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
            runCatching {
                val pi = android.app.PendingIntent.getActivity(
                    this, 7001,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                val act = android.app.RemoteAction(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_zoom),
                    "Tam ekran", "ÜmitTV'yi tam ekran yap", pi)
                builder.setActions(listOf(act))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(false)
            }
            val accepted = enterPictureInPictureMode(builder.build())
            if (!accepted) {
                // Cihaz/ROM isteği reddetti; JS tarafında açıklayıcı hata göster.
                lastPipFailure = "Android PiP isteğini reddetti. Ayarlar > Uygulamalar > ÜmitTV > Resim içinde resim iznini kontrol et."
            }
            accepted
        } catch (e: Throwable) {
            lastPipFailure = "Android PiP hatası: ${e.javaClass.simpleName} ${e.message ?: ""}".take(180)
            false
        }
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!onTvDevice && autoPipEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nv.isPlayingForPip && !gv.hasActiveCells) {
            enterNativePip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipLayoutActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // INVISIBLE WebView'in SurfaceControl katmanı bazı cihazlarda PiP'de
            // native SurfaceView'in üstünde siyah kalabiliyor. GONE katmanı kaldırır.
            web.visibility = View.GONE
            nv.setPipMode(true)
        } else {
            nv.setPipMode(false)
            web.visibility = View.VISIBLE
            immersive()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStart() {
        super.onStart()
        // Activity yeniden görünür olduğunda WebView zamanlayıcılarını tekrar aç.
        // pauseTimers/resumeTimers WebView motorundaki JS zamanlayıcılarını da durdurur/başlatır.
        runCatching { web.resumeTimers() }
    }

    override fun onStop() {
        // Uygulama gerçekten görünür değilken (Home/başka uygulama) WebView'in
        // setInterval/setTimeout döngülerinin arka planda CPU tüketmesini engelle.
        // onPause zaten video ve mikrofonu durdurur; burada JS zamanlayıcılarını
        // tamamen askıya alıyoruz. Geri dönünce onStart bunları yeniden açar.
        runCatching { web.pauseTimers() }
        super.onStop()
    }

    override fun onResume() {
        super.onResume(); web.onResume(); immersive(); nv.onHostStart(); gv.onHostStart()
        mediaSession?.isActive = true            // direksiyon medya tuşları bu uygulamaya gelsin
        if (!btnReceiverOn) {
            val f = IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply { priority = 1000 }
            runCatching { registerReceiver(mediaBtnReceiver, f); btnReceiverOn = true }
        }
        maybeCheckForUpdate()
        startUpdateWatch()
        checkPendingInstallResume()
        ensureUpdBanner()
    }

    override fun onPause() {
        stopUpdateWatch()
        mediaSession?.isActive = false           // arka plandayken müzik/navigasyon uygulamasının tuşlarını çalma
        if (btnReceiverOn) { runCatching { unregisterReceiver(mediaBtnReceiver) }; btnReceiverOn = false }
        // Başka uygulamaya geçişte Android ses tanıma servisini ve mikrofonu hemen bırak.
        destroyRecognizer(immediate = true)
        if (!pipLayoutActive && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)) {
            // WebView tarafındaki HTML5 video/ses oturumlarını da kapat; yalnızca
            // ExoPlayer'ı durdurmak, sayfa içindeki ek medya akışlarını durdurmayabilir.
            runCatching {
                web.evaluateJavascript("(function(){try{window.dispatchEvent(new Event('umit-app-background'));}catch(e){}try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();});}catch(e){}try{if(window.voiceRecognition){try{window.voiceRecognition.abort();}catch(e){}}}catch(e){}})()", null)
            }
            nv.onHostStop(); gv.onHostStop()
        }
        web.onPause(); super.onPause()
    }

    override fun onDestroy() {
        destroyRecognizer(immediate = true)
        mediaSession?.release(); mediaSession = null
        fileCb?.onReceiveValue(null); fileCb = null
        if (dlReceiverOn) { runCatching { unregisterReceiver(dlCompleteReceiver) }; dlReceiverOn = false }
        stopUpdPoll()
        nv.release(); gv.release()
        root.removeAllViews(); web.destroy(); super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    //  Uygulama içi güncelleme (APK'yı kendi içinden indirip kurar)
    // ─────────────────────────────────────────────────────────────
    private var updDialogShowing = false
    private var pendingApkUrl: String? = null
    private var pendingDownloadId: Long = -1L
    private var pendingApkName: String = "UmitTV_update.apk"
    private var awaitingInstallPermission = false
    private var dlReceiverOn = false

    private fun currentVersionCode(): Int =
        if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode

    /** Deneme: güncelleme kontrolünün sonucunu ekranda gösterir (sorun giderme). Sorun çözülünce false yapılır. */
    private val UPDATE_DEBUG = false
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
        }
        return try {
            val rc = conn.responseCode; lastHttp = rc
            if (rc !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn.disconnect() }
    }

    /** Uygulama ön plandayken UPDATE_POLL_MS'de bir yeni sürüm kontrolü (yeni release yayınlanır yayınlanmaz şerit çıkar). */
    private var updWatchOn = false
    private val updWatch = object : Runnable {
        override fun run() {
            if (!updWatchOn || isFinishing) return
            maybeCheckForUpdate()
            uiHandler.postDelayed(this, UPDATE_POLL_MS)
        }
    }
    private fun startUpdateWatch() {
        if (updWatchOn) return
        updWatchOn = true
        uiHandler.postDelayed(updWatch, UPDATE_POLL_MS)
    }
    private fun stopUpdateWatch() { updWatchOn = false; uiHandler.removeCallbacks(updWatch) }

    /** Her açılışta (en az 15 dk arayla) GitHub'daki son sürüme bakar; yeniyse üstte "Güncelleme var" şeridi gösterir. */
    private fun maybeCheckForUpdate(manual: Boolean = false) {
        val sp = getSharedPreferences("umittv", MODE_PRIVATE)
        val last = sp.getLong("upd_last_check", 0L)
        val now = System.currentTimeMillis()
        if (manual) {
            if (pendingDownloadId != -1L || updArgs != null) { toastUpd("Güncelleme zaten hazır / indiriliyor"); return }
            sp.edit().remove("upd_snooze_until").remove("upd_snooze_code").remove("upd_dismissed").apply()   // elle kontrolde bekletme yok sayılır
            toastUpd("Güncelleme kontrol ediliyor…")
        } else if (now - last < UPDATE_CHECK_MIN_GAP_MS || pendingDownloadId != -1L || updArgs != null) return
        sp.edit().putLong("upd_last_check", now).apply()
        Thread {
            try {
                var remoteCode = -1
                var url = ""
                var vName = ""; var sizeB = 0L; var notes = ""
                var src = "yok"; var wHttp = 0; var gHttp = 0
                var sha = ""
                // 0) Cloudflare Worker: anlık, tek istek, GitHub limiti/token gerektirmez
                // Birden çok Worker varsa sırayla denenir (biri kota/hata verirse sıradakine geçilir).
                for (w in WORKER_URLS) {
                    runCatching {
                        val q = if (UPDATE_WORKER_KEY.isNotBlank()) "?k=" + UPDATE_WORKER_KEY else ""
                        val j = JSONObject(httpGet("$w/latest$q") ?: "")
                        remoteCode = j.optInt("versionCode", -1)
                        url = j.optString("url", "")
                        vName = j.optString("versionName", ""); notes = j.optString("notes", ""); sizeB = j.optLong("size")
                        sha = j.optString("sha256", "").trim().lowercase()
                        if (remoteCode > 0) src = "Worker"
                    }
                    if (remoteCode > 0 && url.isNotBlank()) break
                }
                wHttp = lastHttp
                // 1) GitHub Releases (Worker yoksa/çalışmazsa): etiket "v<versionCode>", ek dosya UmitTV.apk
                if (remoteCode <= 0 || url.isBlank()) runCatching {
                    val j = JSONObject(httpGet(GITHUB_LATEST_API) ?: "")
                    remoteCode = UpdateUtil.codeFromTag(j.optString("tag_name", ""))
                    vName = j.optString("name", "").ifBlank { j.optString("tag_name", "") }
                    notes = j.optString("body", "")
                    val assets = j.optJSONArray("assets")
                    if (assets != null) for (k in 0 until assets.length()) {
                        val a = assets.getJSONObject(k)
                        if (a.optString("name").endsWith(".apk", true)) { url = a.optString("url"); sizeB = a.optLong("size"); break }
                    }
                    if (remoteCode > 0) src = "GitHub"
                }.onFailure { updDbg("GitHub okunamadı: HTTP $lastHttp (${it.javaClass.simpleName})") }
                val ghHttp = lastHttp; gHttp = ghHttp
                // 2) yedek: eski update.json
                if (remoteCode <= 0 || url.isBlank()) {
                    val j = JSONObject(httpGet(UPDATE_MANIFEST_URL) ?: run { if (manual) toastUpd("Kontrol edilemedi: sunucuya ulaşılamadı"); return@Thread })
                    remoteCode = j.optInt("versionCode", -1)
                    url = j.optString("url", "")
                    vName = j.optString("versionName", ""); notes = j.optString("notes", "")
                    src = "update.json"
                }
                updDbg("GitHub HTTP $ghHttp, uzak=$remoteCode, yerel=${currentVersionCode()}, apk=${if (url.isBlank()) "yok" else "var"}")
                if (remoteCode <= currentVersionCode() || url.isBlank()) {
                    if (manual) showUpdDiag(
                        if (remoteCode <= 0 || url.isBlank()) "Kontrol edilemedi" else "Güncel sürümdesin",
                        diagText(remoteCode, vName, src, wHttp, gHttp, url.isNotBlank(), null))
                    return@Thread
                }
                if (sp.getInt("upd_dismissed", 0) >= remoteCode) return@Thread
                // "Sonra"/iptal bekletmesi yalnızca O sürüm için geçerli; daha yeni bir sürüm çıkınca hemen gösterilir
                if (System.currentTimeMillis() < sp.getLong("upd_snooze_until", 0L) && remoteCode <= sp.getInt("upd_snooze_code", 0)) return@Thread
                val code = remoteCode; val u = url; val n = vName; val sz = sizeB; val nt = notes
                updExpectedSha = if (src == "Worker" && UpdateUtil.isValidSha256(sha)) sha else ""
                runOnUiThread { if (!isFinishing) showUpdateBanner(u, n, sz, nt, code) }
            } catch (e: Exception) {
                // otomatik kontrolde sessizce geç: ağ yok / JSON bozuk / sunucu yok
                if (manual) showUpdDiag("Kontrol edilemedi", diagText(-1, "", "yok", lastHttp, lastHttp, false, e))
            }
        }.start()
    }

    /** Elle kontrol sonucu: yerel/sunucu sürümü, kaynak ve HTTP kodları (sorun gidermek için). */
    private fun diagText(remote: Int, rName: String, src: String, wHttp: Int, gHttp: Int, hasApk: Boolean, err: Exception?): String {
        val sp = getSharedPreferences("umittv", MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("Yerel sürüm: v").append(currentVersionName()).append(" (kod ").append(currentVersionCode()).append(")\n")
        sb.append("Sunucudaki sürüm: ").append(if (remote > 0) "v$rName (kod $remote)" else "alınamadı").append("\n")
        sb.append("Kaynak: ").append(src).append("\n")
        sb.append("Worker HTTP: ").append(wHttp).append(if (WORKER_URLS.isEmpty()) " (Worker adresi tanımsız)" else "").append("\n")
        sb.append("GitHub HTTP: ").append(gHttp).append("\n")
        sb.append("APK adresi: ").append(if (hasApk) "var" else "yok").append("\n")
        sb.append("Bekletme: ").append(if (System.currentTimeMillis() < sp.getLong("upd_snooze_until", 0L)) "aktif (kod " + sp.getInt("upd_snooze_code", 0) + ")" else "yok").append("\n")
        if (err != null) sb.append("Hata: ").append(err.javaClass.simpleName).append(" ").append(err.message ?: "").append("\n")
        return sb.toString().trim()
    }

    private fun showUpdDiag(title: String, text: String) {
        runOnUiThread { if (!isFinishing) runCatching { android.app.AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Tamam", null).show() } }
    }

    private fun toastUpd(msg: String) { runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }

    private fun currentVersionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    /** Ayarlar'daki "Güncellemeyi kontrol et" düğmesinden çağrılır: bekletmeyi yok sayıp hemen kontrol eder, sonucu toast ile söyler. */
    private fun checkUpdateNow() { maybeCheckForUpdate(true) }

    // ── Güncelleme modalı: durumlar ──────────────────────────────
    private var updBanner: View? = null                       // tam ekran modal (karartma + kart)
    private var updCard: View? = null
    private var updBtn: android.widget.TextView? = null       // ana düğme (Güncelle / Yükle / Tekrar dene)
    private var updSecBtn: android.widget.TextView? = null    // "Sonra"
    private var updStatusTv: android.widget.TextView? = null
    private var updPctTv: android.widget.TextView? = null
    private var updDetailTv: android.widget.TextView? = null
    private var updProgView: UpdProgressView? = null
    private var updProgBox: View? = null
    private var updInfoBtn: View? = null
    // Seçili tema renkleri (sayfadaki CSS değişkenlerinden okunur; okunamazsa turuncu varsayılan)
    private var thBg = Color.parseColor("#FF1A1208"); private var thAcc = Color.parseColor("#FFF97316")
    private var thOn = Color.parseColor("#FF1A0F05"); private var thText = Color.parseColor("#FFFFF8E7")
    private var updThemeApply: (() -> Unit)? = null

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    private fun blendColor(base: Int, over: Int, t: Float): Int = Color.rgb(
        (Color.red(base) * (1 - t) + Color.red(over) * t).toInt(),
        (Color.green(base) * (1 - t) + Color.green(over) * t).toInt(),
        (Color.blue(base) * (1 - t) + Color.blue(over) * t).toInt())
    private fun parseCssColor(v: String, def: Int): Int = try { Color.parseColor(v.trim().let { if (it.length == 4 && it[0] == '#') "#" + it[1] + it[1] + it[2] + it[2] + it[3] + it[3] else it }) or (0xFF shl 24) } catch (e: Exception) { def }

    /** Sayfadaki seçili temanın renklerini (--bg3, --acc, --on-acc, --text) okuyup şeride uygular. */
    private fun readPageTheme() {
        val js = "(function(){try{var s=getComputedStyle(document.documentElement);return ['--bg3','--acc','--on-acc','--text'].map(function(k){return s.getPropertyValue(k).trim()}).join('|')}catch(e){return ''}})()"
        runCatching {
            web.evaluateJavascript(js) { v ->
                val parts = (v ?: "").trim('"').split('|')
                if (parts.size == 4 && parts.all { it.startsWith("#") }) {
                    thBg = parseCssColor(parts[0], thBg); thAcc = parseCssColor(parts[1], thAcc)
                    thOn = parseCssColor(parts[2], thOn); thText = parseCssColor(parts[3], thText)
                    updThemeApply?.invoke()
                }
            }
        }
    }
    private var updFocusables: List<View> = emptyList()
    private var updBusy = false
    private var updHidden = false                             // indirme sürerken modal gizlendi
    private var updState = UPD_IDLE
    private var updReadyApk: File? = null
    private class UpdArgs(val url: String, val ver: String, val size: Long, val notes: String, val code: Int)
    private var updArgs: UpdArgs? = null

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Modalı animasyonla kaldırır. dismiss=true ise bu sürüm bir daha gösterilmez. Odağı sayfaya geri verir. */
    private fun closeUpdateBanner(dismiss: Boolean) {
        if (dismiss) updArgs?.let { getSharedPreferences("umittv", MODE_PRIVATE).edit().putInt("upd_dismissed", it.code).apply() }
        removeUpdModalViews()
        updBusy = false; updState = UPD_IDLE; updArgs = null; updHidden = false; updReadyApk = null
        stopUpdPoll()
        web.requestFocus()
    }

    /** "Sonra": bu sürümü kalıcı susturmaz, 6 saat sonra tekrar hatırlatır. */
    private fun snoozeUpdate() {
        getSharedPreferences("umittv", MODE_PRIVATE).edit()
            .putLong("upd_snooze_until", System.currentTimeMillis() + 6L * 60 * 60 * 1000).putInt("upd_snooze_code", updArgs?.code ?: Int.MAX_VALUE).apply()
        closeUpdateBanner(false)
    }

    /** İndirme sürerken modalı gizler; indirme arkada devam eder, bitince kurulum açılır. */
    private fun hideUpdateModal() {
        updHidden = true
        removeUpdModalViews()
        web.requestFocus()
    }

    private fun onUpdBack() { if (updBusy) hideUpdateModal() else snoozeUpdate() }

    private var webTopAnim: ValueAnimator? = null
    /** Web sayfasını modalın altına iter (modal butonların üstüne binmesin) ve yumuşakça geri alır. */
    private fun animateWebTop(target: Int) {
        var lp = web.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp === match) { lp = FrameLayout.LayoutParams(lp); web.layoutParams = lp }   // ortak 'match' nesnesini bozma
        if (lp.topMargin == target) return
        webTopAnim?.cancel()
        webTopAnim = ValueAnimator.ofInt(lp.topMargin, target).apply {
            duration = 320; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val l = web.layoutParams as? FrameLayout.LayoutParams ?: return@addUpdateListener
                // Alt kenar boşluğu üstün tersi: WebView yüksekliği DEĞİŞMEZ (sayfa yeniden dizilmez, video ölçeği şaşmaz,
                // 1.5 GB'lık kutularda güncelleme sonrası kasma/yarım kalan ekran olmaz); sadece aşağı kayar.
                val v = it.animatedValue as Int
                l.topMargin = v; l.bottomMargin = -v; web.layoutParams = l
                runCatching { nv.reapply(); gv.reapply() }   // video katmanı WebView ile birlikte kaysın
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    val l = web.layoutParams as? FrameLayout.LayoutParams ?: return
                    if (l.topMargin == 0 && l.bottomMargin != 0) { l.bottomMargin = 0; web.layoutParams = l }
                    runCatching { nv.reapply(); gv.reapply() }
                }
            })
            start()
        }
    }

    private fun removeUpdModalViews() {
        val ov = updBanner
        updBanner = null; updCard = null; updBtn = null; updSecBtn = null
        updStatusTv = null; updPctTv = null; updDetailTv = null; updProgView = null; updProgBox = null; updInfoBtn = null; updThemeApply = null
        updFocusables = emptyList()
        animateWebTop(0)
        if (ov == null) return
        ov.animate().translationY(-(ov.height + dp(40)).toFloat()).alpha(0f).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { (ov.parent as? ViewGroup)?.removeView(ov) }.start()
    }

    /** Güncelleme bitti: modal yukarı çıkıp kaybolur. İptal edilen kurulum için 10 dk sonra tekrar hatırlatır. */
    private fun finishUpdateModal() {
        getSharedPreferences("umittv", MODE_PRIVATE).edit()
            .putLong("upd_snooze_until", System.currentTimeMillis() + 10L * 60 * 1000).putInt("upd_snooze_code", updArgs?.code ?: Int.MAX_VALUE).apply()
        closeUpdateBanner(false)
    }

    /** Modal bir şekilde sökülmüşse (ör. izin ekranından dönüşte) ve güncelleme sürüyorsa yeniden kurar. */
    private fun ensureUpdBanner() {
        val a = updArgs ?: return
        if (updHidden) return
        if (updBanner == null) showUpdateBanner(a.url, a.ver, a.size, a.notes, a.code, false)
    }

    private fun fmtMb(bytes: Long): String = String.format(java.util.Locale.forLanguageTag("tr-TR"), "%.1f", bytes / 1048576.0)

    private fun fmtSpeed(bps: Float): String =
        if (bps >= 1048576f) String.format(java.util.Locale.forLanguageTag("tr-TR"), "%.1f MB/sn", bps / 1048576f)
        else String.format(java.util.Locale.forLanguageTag("tr-TR"), "%d KB/sn", (bps / 1024f).toInt())

    /** Üstten animasyonla inen, sayfayı aşağı iten (butonlara binmeyen) güncelleme modalı. Kumanda ile kullanılır. */
    private fun showUpdateBanner(apkUrl: String, versionName: String, sizeBytes: Long, notes: String, code: Int, chime: Boolean = true) {
        if (updBanner != null || isFinishing) return
        updHidden = false
        updArgs = UpdArgs(apkUrl, versionName, sizeBytes, notes, code)
        val ver = versionName.trim().removePrefix("ÜmitTV").trim().ifBlank { "yeni sürüm" }
        val verLabel = if (ver.firstOrNull()?.isDigit() == true) "v$ver" else ver
        val sub = verLabel + if (sizeBytes > 0) "  •  " + Math.max(1L, Math.round(sizeBytes / 1048576.0)).toString() + " MB" else ""
        val ORANGE = Color.parseColor("#F97316"); val AMBER = Color.parseColor("#FBBF24")
        val sbH = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }

        val title = verLabel + if (sizeBytes > 0) "  •  " + Math.max(1L, Math.round(sizeBytes / 1048576.0)).toString() + " MB" else ""

        // Düz üst şerit: arkada soldan sağa dolan ilerleme, önde simge • metin • düğmeler. Renkler seçili temadan gelir.
        val card = FrameLayout(this).apply { elevation = dp(16).toFloat() }
        val prog = UpdProgressView(this)
        card.addView(prog, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), sbH + dp(10), dp(12), dp(10))
        }
        val cloud = UpdCloudIcon(this)
        row.addView(cloud, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)))
        val texts = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val titleTv = android.widget.TextView(this).apply {
            text = title; textSize = 16.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1
        }
        texts.addView(titleTv)
        val subTv = android.widget.TextView(this).apply { text = "Güncelleme var"; textSize = 13f; maxLines = 1 }
        texts.addView(subTv)
        row.addView(texts, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14); rightMargin = dp(8) })

        // kind 0: tema vurgu rengiyle dolu (i, Güncelle/Kur) — kind 1: saydam (✕)
        fun roundBg(kind: Int, r: Int, focused: Boolean) = GradientDrawable().apply {
            setColor(if (kind == 0) thAcc else Color.TRANSPARENT); cornerRadius = dp(r).toFloat()
            if (focused) setStroke(dp(3), thText)
        }
        fun styleBtn(v: View, kind: Int, r: Int) {
            v.background = roundBg(kind, r, false)
            v.setOnFocusChangeListener { _, f ->
                v.background = roundBg(kind, r, f)
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
            }
        }
        val infoBtn = android.widget.TextView(this).apply {
            text = "i"; textSize = 17f; gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            isFocusable = true; isClickable = true
            setOnClickListener {
                val msg = notes.trim().ifBlank { title }
                runCatching { android.app.AlertDialog.Builder(this@MainActivity).setTitle("ÜmitTV " + ver).setMessage(msg).setPositiveButton("Tamam", null).show() }
            }
        }
        row.addView(infoBtn, android.widget.LinearLayout.LayoutParams(dp(34), dp(34)))
        val priBtn = android.widget.TextView(this).apply {
            text = "Güncelle"; textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); isFocusable = true; isClickable = true
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { onUpdPrimaryClick() }
        }
        row.addView(priBtn, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(12) })
        val xBtn = android.widget.TextView(this).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            setOnClickListener { if (!updBusy) snoozeUpdate() }
        }
        row.addView(xBtn, android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        card.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.isFocusable = true

        // Seçili temanın renklerini şeride uygular (önce varsayılan turuncu, sayfadan okununca güncellenir)
        updThemeApply = {
            card.setBackgroundColor(thBg)
            prog.setFillColor(blendColor(thBg, thAcc, 0.30f))
            cloud.setIconColor(thText)
            titleTv.setTextColor(thText)
            subTv.setTextColor(if (updBusy || updState == UPD_ERR) thText else withAlpha(thText, 0.68f))
            infoBtn.setTextColor(thOn); priBtn.setTextColor(thOn); xBtn.setTextColor(thText)
            styleBtn(infoBtn, 0, 17); styleBtn(priBtn, 0, 22); styleBtn(xBtn, 1, 22)
        }
        updThemeApply?.invoke()
        readPageTheme()

        updBanner = card; updCard = card; updBtn = priBtn; updSecBtn = xBtn; updInfoBtn = infoBtn
        updStatusTv = subTv; updPctTv = android.widget.TextView(this); updDetailTv = android.widget.TextView(this)
        updProgView = prog; updProgBox = card

        // sayfayı kartın altına it: kart hiçbir butonun üstüne binmesin
        card.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, _ ->
            if (updBanner === card) animateWebTop(b)
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        root.addView(card, lp)
        card.bringToFront()

        // yukarıdan aşağı iniş animasyonu
        card.alpha = 0f; card.translationY = -dp(160).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        setUpdState(updState)
        if (updState == UPD_DL && pendingDownloadId != -1L) startUpdPoll()
        if (chime) {
            playUpdateChime()
            runCatching { card.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
        }
    }

    private fun onUpdPrimaryClick() {
        when (updState) {
            UPD_IDLE, UPD_ERR -> startUpdateFlow()
            UPD_READY -> {
                val apk = updReadyApk
                if (apk != null && apk.exists()) installApk(apk) else startUpdateFlow()
            }
            else -> { /* indirme sürüyor */ }
        }
    }

    private fun startUpdateFlow() {
        val a = updArgs ?: return
        updReadyApk = null
        setUpdState(UPD_PREP)
        beginDownload(a.url)
    }

    /** Modalın tüm görünümünü duruma göre günceller. */
    private fun setUpdState(st: Int, msg: String = "") {
        updState = st
        updBusy = st == UPD_PREP || st == UPD_PERM || st == UPD_DL || st == UPD_INSTALLING
        val pri = updBtn ?: return
        val x = updSecBtn ?: return
        val sub = updStatusTv; val pv = updProgView
        sub?.setTextColor(if (updBusy) thText else withAlpha(thText, 0.68f))
        fun buttons(showPri: Boolean, showX: Boolean) {
            pri.visibility = if (showPri) View.VISIBLE else View.GONE
            x.visibility = if (showX) View.VISIBLE else View.GONE
        }
        when (st) {
            UPD_IDLE -> { buttons(true, true); pri.text = "Güncelle"; sub?.text = "Güncelleme var"; pv?.indeterminate = false; pv?.setTarget(0f) }
            UPD_PREP -> { buttons(false, false); sub?.text = "Hazırlanıyor…"; pv?.indeterminate = true }
            UPD_PERM -> { buttons(false, false); sub?.text = "İzin bekleniyor… (açılan ekranda izni aç, sonra geri dön)"; pv?.indeterminate = true }
            UPD_DL -> { buttons(false, false); sub?.text = "İndiriliyor…"; if (pv != null && pv.target <= 0f) pv.indeterminate = true }
            UPD_INSTALLING -> { buttons(false, false); sub?.text = "Kuruluma geçiliyor…"; pv?.indeterminate = false; pv?.setTarget(0f) }
            UPD_READY -> { buttons(true, true); pri.text = "Kur"; sub?.text = "Yeni sürüm kurulmaya hazır."; pv?.indeterminate = false; pv?.setTarget(0f) }
            UPD_ERR -> {
                buttons(true, true); pri.text = "Tekrar dene"
                sub?.text = msg.ifBlank { "Bir sorun oluştu" }; sub?.setTextColor(Color.parseColor("#FFF87171"))
                pv?.indeterminate = false; pv?.setTarget(0f)
            }
        }
        val info = updInfoBtn
        updFocusables = listOfNotNull(info, pri.takeIf { it.visibility == View.VISIBLE }, x.takeIf { it.visibility == View.VISIBLE })
        if (pri.visibility == View.VISIBLE) pri.requestFocus() else (updCard ?: info)?.requestFocus()   // odak web sayfasına kaçmasın
    }

    // ── İndirme ilerlemesi (DownloadManager'ı 250 ms'de bir okur) ──
    private var updPollOn = false
    private var updLastBytes = 0L
    private var updLastT = 0L
    private var updSpeed = 0f
    private val updPoll = object : Runnable {
        override fun run() {
            if (!updPollOn || pendingDownloadId == -1L) { updPollOn = false; return }
            pollDownloadOnce()
            uiHandler.postDelayed(this, 250)
        }
    }

    private fun startUpdPoll() {
        if (updPollOn) return
        updPollOn = true; updLastBytes = 0L; updLastT = SystemClock.uptimeMillis(); updSpeed = 0f
        uiHandler.post(updPoll)
    }

    private fun stopUpdPoll() { updPollOn = false; uiHandler.removeCallbacks(updPoll) }

    private fun pollDownloadOnce() {
        val id = pendingDownloadId
        if (id == -1L) return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var cur: Cursor? = null
        try {
            cur = dm.query(DownloadManager.Query().setFilterById(id))
            if (cur == null || !cur.moveToFirst()) return
            val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            var total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            if (total <= 0L) total = updArgs?.size ?: 0L
            val pv = updProgView
            if ((st == DownloadManager.STATUS_PENDING || (st == DownloadManager.STATUS_RUNNING && got <= 0L)) &&
                SystemClock.uptimeMillis() - dlStartT > 8000L && !ownDlTried) {
                fallbackToOwnDownload(); return        // 8 sn'dir başlamadı: DownloadManager takıldı
            }
            if (st == DownloadManager.STATUS_PENDING) {
                updStatusTv?.text = "Sıraya alındı…"; pv?.indeterminate = true; return
            }
            if (st == DownloadManager.STATUS_PAUSED) {
                updStatusTv?.text = "Bağlantı bekleniyor…"; updDetailTv?.text = "İnternet geri gelince devam eder"; return
            }
            // hız (yumuşatılmış) ve kalan süre
            val now = SystemClock.uptimeMillis()
            val dt = now - updLastT
            if (dt >= 400L) {
                val inst = (got - updLastBytes) * 1000f / dt
                updSpeed = if (updSpeed <= 0f) inst else updSpeed * 0.7f + inst * 0.3f
                updLastBytes = got; updLastT = now
            }
            updStatusTv?.text = "İndiriliyor…"
            if (total > 0L) {
                val p = (got.toFloat() / total).coerceIn(0f, 1f)
                pv?.indeterminate = false; pv?.setTarget(p)
                updStatusTv?.text = "İndiriliyor " + (p * 100).toInt() + "%"
                val sb = StringBuilder(fmtMb(got)).append(" / ").append(fmtMb(total)).append(" MB")
                if (updSpeed > 20_000f) {
                    sb.append("  •  ").append(fmtSpeed(updSpeed))
                    val left = ((total - got) / updSpeed).toInt()
                    sb.append("  •  ").append(if (left < 60) "~$left sn" else "~${(left + 30) / 60} dk")
                }
                updDetailTv?.text = sb.toString()
            } else {
                pv?.indeterminate = true
                updPctTv?.text = ""
                updDetailTv?.text = fmtMb(got) + " MB indirildi"
            }
        } catch (e: Exception) {
            // sessizce geç
        } finally { cur?.close() }
    }

    // ── Premium bildirim sesi: yumuşak çan arpeji (kod ile üretilir, dosya gerekmez) ──
    private fun playUpdateChime() {
        Thread {
            var track: AudioTrack? = null
            try {
                val sr = 44100
                val secs = 2.0
                val n = (sr * secs).toInt()
                val dry = FloatArray(n)
                val freq = doubleArrayOf(659.25, 880.00, 1108.73, 1318.51)   // E5 A5 C#6 E6 (A majör arpej)
                val start = doubleArrayOf(0.00, 0.10, 0.20, 0.32)
                val amp = doubleArrayOf(0.50, 0.46, 0.42, 0.56)
                for (k in freq.indices) {
                    val s0 = (start[k] * sr).toInt()
                    val decay = if (k == 3) 2.2 else 3.4
                    for (i in s0 until n) {
                        val t = (i - s0) / sr.toDouble()
                        if (t > 1.7) break
                        val env = (1.0 - Math.exp(-t * 700.0)) * Math.exp(-t * decay)
                        val w = 2.0 * Math.PI * freq[k] * t
                        val s = Math.sin(w) +
                            0.30 * Math.sin(2.0 * w) * Math.exp(-t * 3.0) +
                            0.10 * Math.sin(3.01 * w) * Math.exp(-t * 6.0) +
                            0.04 * Math.sin(5.4 * w) * Math.exp(-t * 12.0)
                        dry[i] += (amp[k] * env * s).toFloat()
                    }
                }
                // yumuşak yankı
                val d1 = (0.16 * sr).toInt(); val d2 = (0.33 * sr).toInt()
                val mix = FloatArray(n)
                for (i in 0 until n) {
                    var v = dry[i]
                    if (i >= d1) v += dry[i - d1] * 0.26f
                    if (i >= d2) v += dry[i - d2] * 0.12f
                    mix[i] = v
                }
                var peak = 0.0001f
                for (v in mix) { val a = Math.abs(v); if (a > peak) peak = a }
                val g = 0.55f / peak
                val fade = (0.25 * sr).toInt()
                val pcm = ShortArray(n)
                for (i in 0 until n) {
                    var v = mix[i] * g
                    val left = n - i
                    if (left < fade) v *= left / fade.toFloat()
                    pcm[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(pcm, 0, pcm.size)
                t.play()
                Thread.sleep((secs * 1000).toLong() + 200L)
            } catch (e: Throwable) {
                // ses çalınamazsa sessizce geç
            } finally {
                try { track?.stop() } catch (e: Throwable) { }
                try { track?.release() } catch (e: Throwable) { }
            }
        }.start()
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
            // Android, "bilinmeyen kaynaklar" izni verilince uygulama sürecini öldürebilir (özellikle TV box'larda): durumu kalıcı tut.
            getSharedPreferences("umittv", MODE_PRIVATE).edit().putString("upd_perm_url", apkUrl).putLong("upd_perm_at", System.currentTimeMillis()).apply()
            setUpdState(UPD_PERM)
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

    /** Ayarlar ekranından dönünce: izin verildiyse indirmeye devam et, verilmediyse modal yerinde kalır. */
    private fun checkPendingInstallResume(fromResult: Boolean = false) {
        if (!awaitingInstallPermission) {
            // Uygulama izin ekranındayken öldürüldüyse (süreç yeniden başladı): izin verildiyse indirmeyi kaldığı yerden başlat
            val sp = getSharedPreferences("umittv", MODE_PRIVATE)
            val pu = sp.getString("upd_perm_url", null)
            if (pu != null) {
                val fresh = System.currentTimeMillis() - sp.getLong("upd_perm_at", 0L) < 10L * 60 * 1000
                if (canInstallUnknown() || !fresh) {
                    sp.edit().remove("upd_perm_url").remove("upd_perm_at").apply()
                    if (fresh && updState != UPD_DL && updState != UPD_PREP && pendingDownloadId == -1L && !ownDlActive) {
                        updHidden = false
                        ensureUpdBanner()
                        setUpdState(UPD_PREP)
                        startApkDownload(pu)
                    }
                }
            }
            return
        }
        val granted = canInstallUnknown()
        if (!granted && !fromResult) return          // izin ekranı hâlâ açık olabilir; sonucu bekle
        awaitingInstallPermission = false
        getSharedPreferences("umittv", MODE_PRIVATE).edit().remove("upd_perm_url").remove("upd_perm_at").apply()
        val u = pendingApkUrl; pendingApkUrl = null
        updHidden = false
        ensureUpdBanner()
        if (granted && u != null) {
            setUpdState(UPD_PREP)
            startApkDownload(u)
        } else {
            setUpdState(UPD_ERR, "Kurulum izni verilmedi")
        }
    }

    /** Özel depodaki APK: token ile asset adresine gidip kısa ömürlü imzalı indirme adresini alır, DownloadManager'a onu verir. */
    /** Worker'ın bildirdiği APK SHA-256 (küçük harf hex); boşsa doğrulama atlanır (Android yine imza uyumunu denetler). */
    @Volatile private var updExpectedSha: String = ""

    private fun fileSha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun startApkDownload(apkUrl: String) {
        if (!apkUrl.startsWith("https://api.github.com/")) { enqueueApk(apkUrl); return }
        Thread {
            var signed: String? = null
            try {
                val c = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/octet-stream")
                }
                if (c.responseCode in 300..399) signed = c.getHeaderField("Location")
                c.disconnect()
            } catch (e: Exception) { }
            runOnUiThread {
                if (signed.isNullOrBlank()) setUpdState(UPD_ERR, "Güncelleme adresi alınamadı") else enqueueApk(signed!!)
            }
        }.start()
    }

    private fun enqueueApk(apkUrl: String) {
        try {
            registerDownloadReceiverIfNeeded()
            // Önceki denemelerden kalan eski APK'lar yanlışlıkla kurulmasın
            runCatching {
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
                    ?.filter { it.name.startsWith("UmitTV_update") }?.forEach { it.delete() }
            }
            pendingApkName = "UmitTV_update_" + System.currentTimeMillis() + ".apk"
            lastApkUrl = apkUrl; ownDlTried = false; dlStartT = SystemClock.uptimeMillis()
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("ÜmitTV güncelleme")
                .setDescription("İndiriliyor…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, pendingApkName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            pendingDownloadId = dm.enqueue(req)
            setUpdState(UPD_DL)
            startUpdPoll()
        } catch (e: Exception) {
            // DownloadManager kullanılamıyor (bazı TV box'larda kapalı/bozuk): uygulama içi indirmeye geç
            startOwnDownload(apkUrl)
        }
    }

    // ── Uygulama içi indirici: DownloadManager "Sıraya alındı"da takılırsa (Botech/WZone gibi kutular) devreye girer ──
    private var lastApkUrl = ""
    private var dlStartT = 0L
    private var ownDlTried = false
    @Volatile private var ownDlActive = false

    private fun fallbackToOwnDownload() {
        val id = pendingDownloadId
        stopUpdPoll()
        pendingDownloadId = -1L
        if (id != -1L) runCatching { (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        startOwnDownload(lastApkUrl)
    }

    private fun ownProgress(got: Long, total: Long) {
        val pv = updProgView
        updStatusTv?.text = "İndiriliyor…"
        if (total > 0L) {
            val p = (got.toFloat() / total).coerceIn(0f, 1f)
            pv?.indeterminate = false; pv?.setTarget(p)
            updStatusTv?.text = "İndiriliyor " + (p * 100).toInt() + "%"
            updDetailTv?.text = fmtMb(got) + " / " + fmtMb(total) + " MB"
        } else {
            pv?.indeterminate = true
            updDetailTv?.text = fmtMb(got) + " MB indirildi"
        }
    }

    private fun startOwnDownload(url: String) {
        if (ownDlActive) return
        if (url.isBlank()) { setUpdState(UPD_ERR, "Güncelleme adresi yok"); return }
        ownDlTried = true; ownDlActive = true
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        runCatching {
            dir.mkdirs()
            dir.listFiles()?.filter { it.name.startsWith("UmitTV_update") }?.forEach { it.delete() }
        }
        pendingApkName = "UmitTV_update_" + System.currentTimeMillis() + ".apk"
        val out = File(dir, pendingApkName)
        setUpdState(UPD_DL)
        updStatusTv?.text = "İndiriliyor…"; updProgView?.indeterminate = true
        val expected = updArgs?.size ?: 0L
        Thread {
            var err: String? = null
            try {
                fun open(addr: String): HttpURLConnection = (URL(addr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 25000; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "UmitTV")
                }
                var u = url
                var hops = 0
                var conn = open(u)
                while (true) {
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 6) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank()) throw java.io.IOException("yönlendirme hatası")
                        u = URL(URL(u), loc).toString(); hops++
                        conn = open(u)
                        continue
                    }
                    if (code !in 200..299) { conn.disconnect(); throw java.io.IOException("HTTP $code") }
                    break
                }
                val total = conn.contentLength.toLong().takeIf { it > 0L } ?: expected
                var got = 0L
                var lastUi = 0L
                conn.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            val now = SystemClock.uptimeMillis()
                            if (now - lastUi > 250L) { lastUi = now; val g = got; runOnUiThread { ownProgress(g, total) } }
                        }
                    }
                }
                conn.disconnect()
                if (total > 0L && got < total) throw java.io.IOException("eksik indirme")
            } catch (e: Exception) { err = e.message ?: "bağlantı hatası" }
            runOnUiThread {
                ownDlActive = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err == null) onApkDownloaded(out)
                else {
                    runCatching { out.delete() }
                    if (updHidden) { updHidden = false; ensureUpdBanner() }
                    setUpdState(UPD_ERR, "İndirme başarısız: $err")
                }
            }
        }.start()
    }

    /** İndirme (DownloadManager ya da uygulama içi) bitti: doğrula ve kuruluma geç. */
    private fun onApkDownloaded(apk: File) {
        val want = updExpectedSha
        if (want.isNotEmpty() && fileSha256(apk) != want) {
            runCatching { apk.delete() }
            if (updHidden) { updHidden = false; ensureUpdBanner() }
            setUpdState(UPD_ERR, "İndirilen dosya doğrulanamadı (SHA-256), tekrar dene")
            return
        }
        updReadyApk = apk
        if (updHidden) { updHidden = false; ensureUpdBanner() }
        setUpdState(UPD_INSTALLING)
        uiHandler.postDelayed({
            if (isFinishing) return@postDelayed
            if (installApk(apk)) { setUpdState(UPD_READY); uiHandler.postDelayed({ if (!isFinishing && updState == UPD_READY) finishUpdateModal() }, 1600) }
            else { updReadyApk = null; setUpdState(UPD_ERR, "İndirilen dosya geçersiz") }
        }, 700)
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
                    stopUpdPoll()
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        onApkDownloaded(File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), pendingApkName))
                    } else if (!ownDlTried && lastApkUrl.isNotBlank()) {
                        pendingDownloadId = -1L
                        startOwnDownload(lastApkUrl)     // DownloadManager başarısız: uygulama içi indirmeyi dene
                    } else {
                        if (updHidden) { updHidden = false; ensureUpdBanner() }
                        setUpdState(UPD_ERR, "İndirme başarısız oldu")
                    }
                }
            } finally {
                cur?.close()
                pendingDownloadId = -1L
            }
        }
    }

    private fun installApk(apk: File): Boolean {
        if (!apk.exists()) { toast("Güncelleme dosyası bulunamadı"); return false }
        // Dosya gerçekten daha yeni bir ÜmitTV APK'sı mı? (bozuk/eski dosya "Uygulama yüklenmedi" hatası verir)
        val info = runCatching { packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        if (info == null) { toast("İndirilen dosya bozuk (" + (apk.length() / 1024) + " KB), tekrar dene"); return false }
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        if (info.packageName != packageName || newCode <= currentVersionCode()) {
            toast("İndirilen dosya eski sürüm (kod $newCode, kurulu ${currentVersionCode()}), tekrar dene"); return false
        }
        return try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            true
        } catch (e: Exception) {
            toast("Kurulum ekranı açılamadı: ${e.message}")
            false
        }
    }
}

// ── Güncelleme modalı yardımcıları ───────────────────────────────
private const val UPD_IDLE = 0
private const val UPD_PREP = 1
private const val UPD_PERM = 2
private const val UPD_DL = 3
private const val UPD_INSTALLING = 4
private const val UPD_READY = 5
private const val UPD_ERR = 6

/** Şeridin arkasında soldan sağa dolan düz ilerleme alanı (belirsiz modda kayan bant). */
private class UpdProgressView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4A4950") }
    private var bandColor = Color.parseColor("#FF3A3940")
    fun setFillColor(c: Int) { fill.color = c; bandColor = c; invalidate() }
    /** Ebeveyn WRAP_CONTENT iken ekranı kaplamasın: içeriğin yüksekliğini ebeveyn belirler. */
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize(0, w), resolveSize(0, h)) }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    var target = 0f
        private set
    private var phase = 0f
    var indeterminate = false
        set(v) { field = v; invalidate() }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            shown += (target - shown) * 0.18f
            invalidate()
        }
    }

    fun setTarget(p: Float) { target = p.coerceIn(0f, 1f) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (indeterminate) {
            val seg = w * 0.35f
            val x = -seg + (w + seg) * phase
            band.shader = LinearGradient(x, 0f, x + seg, 0f,
                intArrayOf(Color.TRANSPARENT, bandColor, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(x, 0f, x + seg, h, band)
        } else if (shown > 0.002f) {
            c.drawRect(0f, 0f, w * shown, h, fill)
        }
    }
}

/** Beyaz "bulut + aşağı ok" simgesi (Material cloud_download). */
private class UpdCloudIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    fun setIconColor(c: Int) { p.color = c; invalidate() }
    private val path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        moveTo(19.35f, 10.04f)
        cubicTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
        cubicTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
        cubicTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
        cubicTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
        lineTo(19f, 20f)
        cubicTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
        cubicTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
        close()
        moveTo(17f, 13f); lineTo(12f, 18f); lineTo(7f, 13f); lineTo(10f, 13f); lineTo(10f, 9f)
        lineTo(14f, 9f); lineTo(14f, 13f); lineTo(17f, 13f); close()
    }
    override fun onDraw(c: Canvas) {
        val sc = Math.min(width, height) / 24f
        c.save(); c.scale(sc, sc); c.drawPath(path, p); c.restore()
    }
}
