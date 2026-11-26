package com.umitm0d.tvizle.dpi;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;

/**
 * ÜmitDpi: ciadpi'nin kendi çıkış soketlerini VPN tünelinin DIŞINA almak için küçük yerel sunucu.
 * ciadpi ("--protect-path") soket fd'sini unix soketle yollar, burada VpnService.protect() ile korunur.
 * Böylece uygulamanın kendi trafiği (WebView + ExoPlayer) de DPI aşmadan geçebilir, döngü oluşmaz.
 */
final class ProtectServer extends Thread {
    private static LocalSocket keep;
    static volatile String path;
    static volatile VpnService svc;
    private static boolean started;
    private LocalServerSocket server;

    static synchronized void ensure(VpnService service) {
        svc = service;
        if (started) return;
        try {
            File f = new File(service.getFilesDir(), "dpi_protect");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            String p = f.getAbsolutePath();
            LocalSocket ls = new LocalSocket();
            ls.bind(new LocalSocketAddress(p, LocalSocketAddress.Namespace.FILESYSTEM));
            LocalServerSocket srv = new LocalServerSocket(ls.getFileDescriptor());
            keep = ls;
            ProtectServer t = new ProtectServer();
            t.server = srv;
            t.setDaemon(true);
            t.setName("umitdpi-protect");
            path = p;
            started = true;
            t.start();
        } catch (Throwable ignored) { }
    }

    private static void handle(LocalSocket s) {
        try {
            s.setSoTimeout(1000);
            //noinspection ResultOfMethodCallIgnored
            s.getInputStream().read();
            FileDescriptor[] fds = s.getAncillaryFileDescriptors();
            if (fds != null) {
                for (FileDescriptor fd : fds) {
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd);
                    VpnService v = svc;
                    if (v != null) v.protect(pfd.getFd());
                    pfd.close();
                    Os.close(fd);
                }
            }
            OutputStream o = s.getOutputStream();
            o.write(1);
            o.flush();
        } catch (Throwable ignored) { }
        try { s.close(); } catch (Throwable ignored) { }
    }

    /** ciadpi argümanlarının sonuna --protect-path ekler. */
    static String[] withArgs(String[] args) {
        String p = path;
        if (p == null) return args;
        String[] r = new String[args.length + 2];
        System.arraycopy(args, 0, r, 0, args.length);
        r[args.length] = "--protect-path";
        r[args.length + 1] = p;
        return r;
    }

    @Override public void run() {
        while (true) {
            try { handle(server.accept()); } catch (IOException e) { return; }
        }
    }
}
