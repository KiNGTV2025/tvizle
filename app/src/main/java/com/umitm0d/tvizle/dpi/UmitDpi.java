package com.umitm0d.tvizle.dpi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.github.romanvht.byedpi.core.ByeDpiProxy;

/**
 * ÜmitDpi — ISS engelini (SNI/DPI) aşmak için ByeDPI (ciadpi) + tun2socks tabanlı yerel VPN.
 * DNS koruma (DoH) özelliğinin yerine geçer. Durum bilgisini sayfaya (WebView) iter.
 */
public final class UmitDpi {
    private UmitDpi() {}

    public static final int REQ_VPN = 4110;
    static final String PREFS = "umit_dpi_prefs";
    static final int TEST_PORT = 1081;
    static final String DEFAULT_SNI = "www.iana.org";
    /** Varsayılan strateji (Otomatik seç ile değiştirilebilir). */
    public static final String DEFAULT_STRATEGY =
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9 :+,._{}=/\\-]{1,700}$");

    public interface Listener { void onJs(String js); }

    private static volatile Listener listener;
    static volatile String state = "off";   // off | starting | on | error
    static volatile String error = "";
    private static Boolean sup;
    private static volatile boolean testing, testCancel;

    public static void setListener(Listener l) { listener = l; }

    // ───────────────────────── destek / ayarlar ─────────────────────────
    public static synchronized boolean supported() {
        if (sup == null) {
            try {
                System.loadLibrary("byedpi");
                System.loadLibrary("hev-socks5-tunnel");
                sup = Boolean.TRUE;
            } catch (Throwable t) {
                sup = Boolean.FALSE;
            }
        }
        return sup;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
    public static boolean isAuto(Context c) { return prefs(c).getBoolean("auto", false); }
    public static void setAuto(Context c, boolean v) { prefs(c).edit().putBoolean("auto", v).apply(); }
    public static String getStrategy(Context c) {
        String s = prefs(c).getString("strategy", DEFAULT_STRATEGY);
        return (s == null || !SAFE.matcher(s).matches()) ? DEFAULT_STRATEGY : s;
    }
    /** Yalnızca güvenli karakterlerden oluşan stratejiyi kaydeder (yerel koda argüman olarak gider). */
    public static boolean setStrategy(Context c, String s) {
        if (s == null) return false;
        s = s.trim().replaceAll("\\s+", " ");
        if (!SAFE.matcher(s).matches()) return false;
        prefs(c).edit().putString("strategy", s).apply();
        return true;
    }
    static int getPort(Context c) { return 1080; }
    static String getDns(Context c) { return "8.8.8.8"; }

    // ───────────────────────── durum ─────────────────────────
    public static String stateJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("supported", supported());
            o.put("state", state);
            o.put("error", error);
            o.put("auto", c != null && isAuto(c));
            o.put("strategy", c != null ? getStrategy(c) : DEFAULT_STRATEGY);
            o.put("testing", testing);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    static void setState(String s, String e) {
        state = s;
        error = e == null ? "" : e;
        JSONObject o = new JSONObject();
        try { o.put("state", state); o.put("error", error); o.put("testing", testing); } catch (Throwable ignored) { }
        push("window.__umitDpiState&&window.__umitDpiState(" + o + ")");
    }

    static void push(String js) {
        Listener l = listener;
        if (l != null) { try { l.onJs(js); } catch (Throwable ignored) { } }
    }

    // ───────────────────────── başlat / durdur ─────────────────────────
    public static void start(Activity a) {
        if (!supported()) { setState("error", "Bu cihaz mimarisi desteklenmiyor"); return; }
        if ("on".equals(state) || "starting".equals(state)) return;
        if (testing) { setState("off", "Strateji testi sürerken açılamaz"); return; }
        Intent p;
        try { p = VpnService.prepare(a); } catch (Throwable t) { setState("error", "VPN izni alınamadı"); return; }
        if (p != null) {
            setState("starting", "");
            try { a.startActivityForResult(p, REQ_VPN); }
            catch (Throwable t) { setState("error", "VPN izin ekranı açılamadı"); }
        } else {
            launch(a);
        }
    }

    public static void onVpnResult(Activity a, int res) {
        if (res == Activity.RESULT_OK) launch(a); else setState("off", "VPN izni verilmedi");
    }

    static void launch(Context c) {
        setState("starting", "");
        Intent i = new Intent(c, UmitDpiVpnService.class).setAction(UmitDpiVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) {
            setState("error", "Servis başlatılamadı");
        }
    }

    public static void stop(Context c) {
        UmitDpiVpnService s = UmitDpiVpnService.instance;
        if (s != null) s.requestStop();
        else if (!"off".equals(state)) setState("off", "");
    }

    /** Uygulama açılırken: "otomatik aç" seçiliyse ve VPN izni daha önce verildiyse sessizce başlatır. */
    public static void autoStart(Context c) {
        try {
            if (!isAuto(c) || !supported()) return;
            if (VpnService.prepare(c) == null) launch(c);
        } catch (Throwable ignored) { }
    }

    /** Uygulamadan çıkarken: "otomatik" modda VPN de kapanır. */
    public static void onAppExit(Context c) {
        try { if (isAuto(c)) stop(c); } catch (Throwable ignored) { }
    }

    // ───────────────────────── ciadpi argümanları ─────────────────────────
    static String[] buildArgs(String strategy, int port, String protectPath) {
        String s = (strategy == null ? DEFAULT_STRATEGY : strategy).replace("{sni}", DEFAULT_SNI).trim();
        ArrayList<String> a = new ArrayList<String>();
        a.add("ciadpi");
        a.add("-i127.0.0.1");   // yalnızca yerel; dışarıdan erişilemez
        a.add("-p" + port);
        for (String t : s.split("\\s+")) if (!t.isEmpty()) a.add(t);
        if (protectPath != null) { a.add("--protect-path"); a.add(protectPath); }
        return a.toArray(new String[0]);
    }

    static boolean waitPort(int port, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (Throwable ignored) {
                try { Thread.sleep(120); } catch (InterruptedException e) { return false; }
            } finally {
                try { s.close(); } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    // ───────────────────────── hazır stratejiler ─────────────────────────
    public static List<String> readAssetLines(Context c, String name) {
        ArrayList<String> r = new ArrayList<String>();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getAssets().open(name), "UTF-8"));
            String l;
            while ((l = br.readLine()) != null) { l = l.trim(); if (!l.isEmpty()) r.add(l); }
            br.close();
        } catch (Throwable ignored) { }
        return r;
    }

    public static String strategiesJson(Context c) {
        JSONArray a = new JSONArray();
        for (String s : readAssetLines(c, "umitdpi_strategies.list")) a.put(s);
        return a.toString();
    }

    // ───────────────────────── otomatik strateji seçimi ─────────────────────────
    public static boolean isTesting() { return testing; }
    public static void cancelTest() { testCancel = true; }

    private static void pushTest(int i, int n, int ok, int total, boolean done, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("i", i); o.put("n", n); o.put("ok", ok); o.put("total", total);
            o.put("done", done); o.put("msg", msg == null ? "" : msg);
        } catch (Throwable ignored) { }
        push("window.__umitDpiTest&&window.__umitDpiTest(" + o + ")");
    }

    /** Hazır stratejileri sırayla dener, hedef sitelere en çok ulaşanı kaydeder. ÜmitDpi KAPALIYKEN çalışır. */
    public static void autoPick(final Context ctx) {
        if (testing) return;
        if (!supported()) { pushTest(0, 0, 0, 0, true, "Bu cihaz mimarisi desteklenmiyor"); return; }
        if (!"off".equals(state)) { pushTest(0, 0, 0, 0, true, "Önce ÜmitDpi'yi kapat"); return; }
        testing = true; testCancel = false;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String best = null; int bestOk = -1; int total = 0; String msg = "";
                try {
                    List<String> strategies = readAssetLines(ctx, "umitdpi_strategies.list");
                    List<String> sites = readAssetLines(ctx, "umitdpi_sites.list");
                    total = sites.size();
                    String cur = getStrategy(ctx);
                    if (!strategies.contains(cur)) strategies.add(0, cur);
                    int n = strategies.size();
                    if (total == 0 || n == 0) { msg = "Test listesi bulunamadı"; return; }
                    for (int i = 0; i < n && !testCancel; i++) {
                        pushTest(i + 1, n, Math.max(bestOk, 0), total, false, "");
                        final ByeDpiProxy px = new ByeDpiProxy();
                        final String[] args = buildArgs(strategies.get(i), TEST_PORT, null);
                        Thread pt = new Thread(new Runnable() {
                            @Override public void run() { try { px.startProxy(args); } catch (Throwable ignored) { } }
                        }, "umitdpi-testproxy");
                        pt.setDaemon(true);
                        pt.start();
                        int ok = 0;
                        if (waitPort(TEST_PORT, 2500)) ok = countOk(sites, TEST_PORT);
                        try { px.stopProxy(); } catch (Throwable ignored) { }
                        try { pt.join(1500); } catch (InterruptedException ignored) { }
                        if (pt.isAlive()) { try { px.jniForceClose(); } catch (Throwable ignored) { } }
                        if (ok > bestOk) { bestOk = ok; best = strategies.get(i); }
                        if (ok * 10 >= total * 9) break;   // %90+ yeterli
                    }
                    if (testCancel) msg = "Test iptal edildi";
                    else if (best != null && bestOk > 0) { setStrategy(ctx, best); msg = bestOk + "/" + total + " site açıldı — strateji kaydedildi"; }
                    else msg = "Hiçbir strateji siteye ulaşamadı (ağ bağlantını kontrol et)";
                } catch (Throwable e) {
                    msg = "Test hatası";
                } finally {
                    testing = false;
                    pushTest(0, 0, Math.max(bestOk, 0), total, true, msg);
                }
            }
        }, "umitdpi-test");
        t.setDaemon(true);
        t.start();
    }

    private static int countOk(List<String> sites, int port) {
        final Proxy px = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
        ExecutorService ex = Executors.newFixedThreadPool(6);
        ArrayList<Future<Boolean>> fs = new ArrayList<Future<Boolean>>();
        for (final String s : sites) {
            fs.add(ex.submit(new Callable<Boolean>() {
                @Override public Boolean call() { return checkSite(s, px); }
            }));
        }
        int ok = 0;
        for (Future<Boolean> f : fs) {
            try { if (f.get(7, TimeUnit.SECONDS)) ok++; } catch (Throwable ignored) { }
        }
        ex.shutdownNow();
        return ok;
    }

    private static boolean checkSite(String host, Proxy px) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://" + host + "/").openConnection(px);
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            return c.getResponseCode() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }
}
