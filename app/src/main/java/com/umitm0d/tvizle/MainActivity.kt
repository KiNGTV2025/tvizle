package com.umitm0d.tvizle

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/** ÜmitTV – assets/index.html'i tam ekran WebView'de açar. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

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
        }

        web.loadUrl("file:///android_asset/index.html")
        web.requestFocus()
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

    override fun onResume() { super.onResume(); web.onResume(); immersive() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { root.removeAllViews(); web.destroy(); super.onDestroy() }
}
