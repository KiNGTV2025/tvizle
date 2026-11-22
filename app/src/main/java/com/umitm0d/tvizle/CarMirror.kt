package com.umitm0d.tvizle

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Araç ekranının yüzeyine (Surface) bağlı bir VirtualDisplay oluşturur, ÜmitTV'nin CarMainActivity'sini
 * o sanal ekranda açar ve araç ekranındaki dokunuşları (tık / kaydırma) o etkinliğe aktarır.
 */
class CarMirror(private val ctx: CarContext) : SurfaceCallback {
    private val h = Handler(Looper.getMainLooper())
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var hgt = 0
    private var cx = 0f
    private var cy = 0f
    private var dragging = false
    private var downTime = 0L
    private val upRun = Runnable { endDrag() }

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        val surface = c.surface ?: return
        w = c.width; hgt = c.height
        cx = w / 2f; cy = hgt / 2f
        try {
            val cur = vd
            if (cur == null) {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                vd = dm.createVirtualDisplay("UmitTV-Car", c.width, c.height, c.dpi, surface, flags)
                launch()
            } else {
                cur.surface = surface
                cur.resize(c.width, c.height, c.dpi)
            }
        } catch (t: Throwable) {
            Log.e("UmitCar", "sanal ekran açılamadı", t)
        }
    }

    private fun launch() {
        val display = vd?.display ?: return
        val i = Intent(ctx, CarMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
        ctx.startActivity(i, opts.toBundle())
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        endDrag()
        try { vd?.surface = null } catch (_: Throwable) {}
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    // ───── Dokunuş aktarımı ─────

    private fun inject(action: Int, x: Float, y: Float) {
        val a = CarMainActivity.current ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        try { a.dispatchTouchEvent(e) } finally { e.recycle() }
    }

    private fun endDrag() {
        h.removeCallbacks(upRun)
        if (dragging) { dragging = false; inject(MotionEvent.ACTION_UP, cx, cy) }
    }

    override fun onClick(x: Float, y: Float) {
        endDrag()
        cx = x; cy = y
        inject(MotionEvent.ACTION_DOWN, x, y)
        h.postDelayed({ inject(MotionEvent.ACTION_UP, x, y) }, 50)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!dragging) { dragging = true; inject(MotionEvent.ACTION_DOWN, cx, cy) }
        cx = (cx - distanceX).coerceIn(0f, (w - 1).toFloat())
        cy = (cy - distanceY).coerceIn(0f, (hgt - 1).toFloat())
        inject(MotionEvent.ACTION_MOVE, cx, cy)
        h.removeCallbacks(upRun)
        h.postDelayed(upRun, 180)
    }

    fun release() {
        endDrag()
        try { vd?.release() } catch (_: Throwable) {}
        vd = null
        CarMainActivity.current?.finish()
    }
}
