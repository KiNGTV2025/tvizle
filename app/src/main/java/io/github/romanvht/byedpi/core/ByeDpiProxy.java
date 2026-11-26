package io.github.romanvht.byedpi.core;

/**
 * ByeDPI (ciadpi) yerel köprüsü.
 * DİKKAT: Paket/sınıf adı libbyedpi.so içindeki JNI sembollerine bağlıdır
 * (Java_io_github_romanvht_byedpi_core_ByeDpiProxy_*) — adını/paketini DEĞİŞTİRME.
 */
public final class ByeDpiProxy {
    static { System.loadLibrary("byedpi"); }

    private native int jniStartProxy(String[] args);
    private native int jniStopProxy();
    public native int jniForceClose();

    /** Proxy durana kadar BLOKE eder; ayrı bir thread'de çağır. */
    public int startProxy(String[] args) { return jniStartProxy(args); }
    public int stopProxy() { return jniStopProxy(); }
}
