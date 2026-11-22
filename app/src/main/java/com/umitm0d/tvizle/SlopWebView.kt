package com.umitm0d.tvizle

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.hypot

/**
 * Araç ekranı yansıtma (Android Auto yansıtıcıları vb.) dokunuşları titrek / eksik iletir:
 *  - parmak sabitken küçük kaymalar kaydırma sayılır,
 *  - kaydırma sırasında MOVE olayları kaybolup DOWN -> uzak bir noktada UP gelirse "dokunma" sanılır
 *    ve parmağın altındaki kanal açılır.
 * Bu sınıf küçük titreşimleri yutar ve MOVE'suz uzak UP'yi dokunma yerine İPTAL olarak iletir.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SlopWebView(c: Context) : WebView(c) {
    private val slop = 18f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var active = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) { moved = true; return super.onTouchEvent(e) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; active = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active && !moved) {
                    if (hypot(e.x - downX, e.y - downY) < slop) return true   // titreşimi yut
                    moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val far = active && !moved && hypot(e.x - downX, e.y - downY) > slop
                active = false
                if (far) {
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    val r = super.onTouchEvent(cancel)
                    cancel.recycle()
                    return r
                }
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        return super.onTouchEvent(e)
    }
}
