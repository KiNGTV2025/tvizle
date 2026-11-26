package com.umitm0d.tvizle.dpi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.umitm0d.tvizle.MainActivity;

import java.io.File;
import java.io.FileOutputStream;

import io.github.romanvht.byedpi.core.ByeDpiProxy;
import io.github.romanvht.byedpi.core.TProxyService;

/** ÜmitDpi VPN servisi: ciadpi (SOCKS5, yalnızca 127.0.0.1) + hev-socks5-tunnel (tun → socks). */
public class UmitDpiVpnService extends VpnService {
    public static final String ACTION_START = "com.umitm0d.tvizle.dpi.START";
    public static final String ACTION_STOP = "com.umitm0d.tvizle.dpi.STOP";
    private static final String CHANNEL = "umitdpi";
    private static final int NOTIF_ID = 7710;

    static volatile UmitDpiVpnService instance;

    private final Object lock = new Object();
    private volatile boolean running, stopping;
    private boolean tunnelStarted;
    private ParcelFileDescriptor tun;
    private ByeDpiProxy proxy;
    private Thread proxyThread;
    private File cfg;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            requestStop();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        new Thread(new Runnable() { @Override public void run() { doStart(); } }, "umitdpi-start").start();
        return START_NOT_STICKY;
    }

    @Override public void onRevoke() {
        requestStop();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        instance = null;
        if (running) requestStop();
        super.onDestroy();
    }

    // ───────────────────────── bildirim ─────────────────────────
    private void startForegroundCompat() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "ÜmitDpi", NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(this, CHANNEL);
            } else {
                b = new Notification.Builder(this);
            }
            Intent open = new Intent(this, MainActivity.class);
            Intent stop = new Intent(this, UmitDpiVpnService.class).setAction(ACTION_STOP);
            b.setSmallIcon(getApplicationInfo().icon)
             .setContentTitle("ÜmitDpi açık")
             .setContentText("ISS engeli aşma etkin")
             .setOngoing(true)
             .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
             .addAction(new Notification.Action.Builder(null, "Kapat",
                 PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build());
            startForeground(NOTIF_ID, b.build());
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── başlat ─────────────────────────
    private void doStart() {
        synchronized (lock) {
            if (running || stopping) return;
            running = true;
        }
        try {
            UmitDpi.setState("starting", "");
            ProtectServer.ensure(this);
            final int port = UmitDpi.getPort(this);
            final String[] args = UmitDpi.buildArgs(UmitDpi.getStrategy(this), port, ProtectServer.path);
            final ByeDpiProxy px = new ByeDpiProxy();
            proxy = px;
            proxyThread = new Thread(new Runnable() {
                @Override public void run() {
                    int code;
                    try { code = px.startProxy(args); } catch (Throwable t) { code = -2; }
                    if (running) {
                        final int c = code;
                        new Thread(new Runnable() {
                            @Override public void run() { stopAll("ÜmitDpi durdu (kod " + c + ")"); }
                        }).start();
                    }
                }
            }, "umitdpi-proxy");
            proxyThread.start();
            if (!UmitDpi.waitPort(port, 4000)) throw new IllegalStateException("Yerel proxy başlamadı");
            startTun2Socks(port);
            UmitDpi.setState("on", "");
        } catch (Throwable t) {
            String m = t.getMessage();
            stopAll(m == null || m.isEmpty() ? "ÜmitDpi başlatılamadı" : m);
        }
    }

    private void startTun2Socks(int port) throws Exception {
        String conf = "tunnel:\n  mtu: 8500\nmisc:\n  task-stack-size: 81920\nsocks5:\n"
            + "  address: 127.0.0.1\n  port: " + port + "\n  udp: udp\n";
        File f = File.createTempFile("config", "tmp", getCacheDir());
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(conf.getBytes("UTF-8")); } finally { o.close(); }
        cfg = f;

        Builder b = new Builder();
        b.setSession("ÜmitDpi");
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        b.addAddress("10.10.10.10", 32).addRoute("0.0.0.0", 0);
        b.addDnsServer(UmitDpi.getDns(this));
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        // Uygulamanın kendisi de tünelden geçer (ciadpi soketleri ProtectServer ile korunur → döngü yok).
        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) throw new IllegalStateException("VPN kurulamadı (izin verilmemiş olabilir)");
        tun = pfd;
        TProxyService.TProxyStartService(f.getAbsolutePath(), pfd.getFd());
        tunnelStarted = true;
    }

    // ───────────────────────── durdur ─────────────────────────
    void requestStop() {
        new Thread(new Runnable() { @Override public void run() { stopAll(null); } }, "umitdpi-stop").start();
    }

    private void stopAll(String err) {
        synchronized (lock) {
            if (stopping) return;
            stopping = true;
            running = false;
        }
        try {
            if (tunnelStarted) {
                try { TProxyService.TProxyStopService(); } catch (Throwable ignored) { }
                tunnelStarted = false;
            }
            if (cfg != null) { //noinspection ResultOfMethodCallIgnored
                cfg.delete(); cfg = null; }
            if (tun != null) { try { tun.close(); } catch (Throwable ignored) { } tun = null; }
            ByeDpiProxy px = proxy;
            Thread pt = proxyThread;
            if (px != null) { try { px.stopProxy(); } catch (Throwable ignored) { } }
            if (pt != null) {
                try { pt.join(2500); } catch (InterruptedException ignored) { }
                if (pt.isAlive() && px != null) {
                    try { px.jniForceClose(); } catch (Throwable ignored) { }
                    try { pt.join(1000); } catch (InterruptedException ignored) { }
                }
            }
            proxy = null; proxyThread = null;
            UmitDpi.setState(err == null ? "off" : "error", err);
            try { stopForeground(true); } catch (Throwable ignored) { }
            stopSelf();
        } finally {
            synchronized (lock) { stopping = false; }
        }
    }
}
