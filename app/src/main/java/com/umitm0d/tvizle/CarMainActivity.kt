package com.umitm0d.tvizle

import android.os.Bundle

/**
 * Android Auto'da araç ekranındaki sanal ekranda (VirtualDisplay) çalışan ÜmitTV arayüzü.
 * Telefondaki MainActivity ile aynı arayüzdür; yalnızca ayrı bir görev olarak sanal ekranda açılır.
 */
class CarMainActivity : MainActivity() {
    companion object {
        @Volatile
        var current: CarMainActivity? = null
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
