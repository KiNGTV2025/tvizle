# ───────────────────────────────────────────────────────────────────────────
# ÜmitTV / tvizle — R8 kuralları
# Amaç: (1) build'in kırılmaması (reflection/annotation ile çağrılan sınıfları
# koru), (2) geri kalan HER ŞEYİN adını karart (MT Manager/jadx ile açılsa bile
# a.b.c gibi anlamsız kalsın).
# ───────────────────────────────────────────────────────────────────────────

# Satır numaraları obfuscate sürümde de gerçek dosya adını göstermesin diye
# kaynak dosya adını gizle (crash raporlarında sadece "SourceFile" görünür).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── WebView <-> JS köprüleri (MainActivity içindeki SpeechBridge/VideoBridge/
#    GridBridge vb.) — WebView bu metodları JS tarafından reflection ile
#    çağırır; isimleri değişirse "UmitNative.*"/"UmitVideo.*" JS çağrıları
#    sessizce bozulur (video/sesli komut çalışmaz hale gelir).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Android Auto / Car App Library — servis ve şablonlar framework tarafından
#    isimle (manifest + reflection) bulunuyor; kütüphane kendi consumer-rules
#    dosyasını getirir ama proje sınıflarını (CarMainActivity, UmitCarService,
#    UmitCarScreen) açıkça koruyoruz.
-keep class com.umitm0d.tvizle.UmitCarService { *; }
-keep class com.umitm0d.tvizle.UmitCarScreen { *; }
-keep class com.umitm0d.tvizle.CarMainActivity { *; }
-dontwarn androidx.car.app.**

# ── media3 / ExoPlayer — DefaultRenderersFactory, EXTENSION_RENDERER_MODE_PREFER
#    ile FFmpeg ses dekoderini (ve diğer uzantı renderer'ları) sınıf adıyla
#    (Class.forName) arayıp yükler; adı karartılırsa dekoder sessizce bulunamaz.
-dontwarn androidx.media3.**
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.exoplayer.ext.ffmpeg.FfmpegAudioRenderer { *; }
-keep class org.jellyfin.media3.ffmpegdecoder.** { *; }
# NativeVideo.kt: Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("isAvailable")...
-keep class * extends androidx.media3.exoplayer.Renderer
-keep class * extends androidx.media3.exoplayer.audio.AudioSink

# ── Android Keystore / kripto (SecurePage.kt) — Cipher/KeyGenerator Provider
#    tarafından sınıf adıyla aranan algoritma sağlayıcılarını etkilemesin diye
#    javax.crypto ve java.security ağaçlarına dokunma.
-dontwarn javax.crypto.**
-keep class com.umitm0d.tvizle.SecurePage { *; }

# ── Native (.so) köprüsü — JNI, Kotlin "external fun" imzalarını metot adıyla
#    bulur; isim karartılırsa UnsatisfiedLinkError ile çöker.
-keep class com.umitm0d.tvizle.NativeGuard { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── FileProvider (manifest'te adı geçiyor; AGP genelde otomatik korur ama
#    garanti olsun).
-keep class androidx.core.content.FileProvider

# ── Genel: enum'ların values()/valueOf() kullanımı bazı kütüphanelerde
#    reflection ile yapılabiliyor.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable CREATOR alanları (Android framework bunları reflection ile
#    bulur; isim karartılsa bile alan adı CREATOR sabit kalmalı).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── Release derlemede hata ayıklama log'larını (Log.d/.v) tamamen sil:
#    hem APK'yı küçültür hem de dex içinde kalabilecek açıklayıcı debug
#    metinlerini (akış adresleri, token durum mesajları vb.) azaltır.
#    NOT: updDbg() gibi KENDİ fonksiyonlarınız Log kullanmıyorsa bu kural
#    onları etkilemez; sadece android.util.Log çağrılarını hedefler.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# ── Daha agresif karartma: paketleri tek bir düz pakette topla, erişim
#    belirleyicilerini (public/private) optimizasyon için gevşet.
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# Kotlin metadata'sı dex'te kalmaya devam eder (kaldırılamaz) ama onu
# gerektiren bir reflection kullanmıyoruz; yine de coroutines/kotlin runtime
# için standart uyarıları sustur.
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ── ÜmitDpi (ByeDPI + hev-socks5-tunnel) — .so içindeki JNI sembolleri/kayıtları bu paket/sınıf adlarına
#    bağlı; R8 yeniden adlandırırsa UnsatisfiedLinkError olur. ADLARI KORU.
-keep class io.github.romanvht.byedpi.core.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.umitm0d.tvizle.dpi.UmitDpiVpnService { *; }
