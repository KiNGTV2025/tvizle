package io.github.romanvht.byedpi.core;

/**
 * hev-socks5-tunnel (tun2socks) yerel köprüsü.
 * DİKKAT: Paket/sınıf adı libhev-socks5-tunnel.so içindeki JNI kaydına bağlıdır — DEĞİŞTİRME.
 */
public final class TProxyService {
    static { System.loadLibrary("hev-socks5-tunnel"); }

    private TProxyService() {}

    public static native void TProxyStartService(String configPath, int tunFd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();
}
