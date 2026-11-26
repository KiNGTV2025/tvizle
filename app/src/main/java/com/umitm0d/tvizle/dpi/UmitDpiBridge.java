package com.umitm0d.tvizle.dpi;

import android.app.Activity;
import android.webkit.JavascriptInterface;

/**
 * Sayfadan (tv-izle HTML) ÜmitDpi'yi yönetmek için JS köprüsü: window.UmitDpi.*
 * Değiştiren çağrılar yalnızca güvenilir sayfadan (Trust) ve UI thread'inde çalışır.
 */
public final class UmitDpiBridge {
    public interface Trust { boolean ok(); }

    private final Activity act;
    private final Trust trust;

    public UmitDpiBridge(Activity a, Trust t) { act = a; trust = t; }

    private void ui(final Runnable r) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() { try { if (trust.ok()) r.run(); } catch (Throwable ignored) { } }
        });
    }

    @JavascriptInterface public boolean supported() { return UmitDpi.supported(); }
    @JavascriptInterface public String state() { return UmitDpi.stateJson(act); }
    @JavascriptInterface public String strategies() { return UmitDpi.strategiesJson(act); }
    @JavascriptInterface public boolean isAuto() { return UmitDpi.isAuto(act); }

    @JavascriptInterface public void start() { ui(new Runnable() { @Override public void run() { UmitDpi.start(act); } }); }
    @JavascriptInterface public void stop() { ui(new Runnable() { @Override public void run() { UmitDpi.stop(act); } }); }
    @JavascriptInterface public void setAuto(final boolean v) { ui(new Runnable() { @Override public void run() { UmitDpi.setAuto(act, v); } }); }
    @JavascriptInterface public void setStrategy(final String s) { ui(new Runnable() { @Override public void run() { UmitDpi.setStrategy(act, s); } }); }
    @JavascriptInterface public void autoPick() { ui(new Runnable() { @Override public void run() { UmitDpi.autoPick(act.getApplicationContext()); } }); }
    @JavascriptInterface public void cancelTest() { ui(new Runnable() { @Override public void run() { UmitDpi.cancelTest(); } }); }
}
