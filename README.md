# tvizle
Otomatik oluşturuldu


## PiP ve çoklu ekran düzeltmeleri (24.11.2026)

- Android `MainActivity` manifest kaydına PiP ve yeniden boyutlandırılabilir pencere desteği eklendi.
- `UmitVideo` köprüsüne `pipSupported()`, `enterPip()` ve `setAutoPip()` eklendi. WebView'in kendi PiP API'si bulunmasa da yerel Android PiP yolu kullanılabilir.
- PiP sırasında WebView arayüzü gizlenir, yerel ExoPlayer yüzeyi pencereye genişletilir ve Activity `onPause` çağrısında oynatma durdurulmaz.
- PiP otomatik geçişi yalnızca tekli ana yayın oynarken yapılır; çoklu ekran kutuları açıkken PiP'ye geçiş engellenir.
- `tv-izle-pip-grid-duzeltilmis.html` dosyasında PiP düğmesinin görünürlük koşulu yerel Android köprüsünü de dikkate alacak şekilde güncellendi.
- Çoklu ekranın mevcut `gLoad/gStop/gStopAll/gMute/gRect` köprüsü korunmuştur; `GridVideo` aktif kutu durumunu PiP kararına bildirir.

Not: Bu ortamda Android SDK/Gradle wrapper bulunmadığından APK derlemesi çalıştırılamadı. HTML içindeki 192 satır içi JavaScript bloğu Node.js sözdizimi kontrolünden hatasız geçti. HTML dosyası uzaktaki sayfa olarak sunuluyorsa, PiP düğmesi düzeltmesinin etkili olması için bu HTML'nin de sayfanın barındırıldığı yere yüklenmesi gerekir.


## ÜmitDpi (DNS Koruma/DoH yerine) — 26.11.2026

DNS sağlayıcısı + DNS Koruma (DoH) özelliği kaldırıldı; yerine **ÜmitDpi** geldi (ISS engeli aşma, ByeDPI/ciadpi + tun2socks tabanlı yerel VPN).

Android tarafı
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libbyedpi.so` ve `libhev-socks5-tunnel.so` (Ümit TV 1.9.0.5 APK'sından). x86_64'te kütüphane yok → ÜmitDpi "desteklenmiyor" görünür, uygulama normal çalışır.
- `io.github.romanvht.byedpi.core.ByeDpiProxy` / `TProxyService`: JNI sembolleri .so içinde bu paket/sınıf adlarına bağlı — **adlarını/paketini değiştirme** (R8 için `proguard-rules.pro`'ya keep kuralı eklendi).
- `com.umitm0d.tvizle.dpi`: `UmitDpi` (durum, ayarlar, otomatik strateji testi), `UmitDpiVpnService` (VpnService), `ProtectServer` (ciadpi soketlerini VPN dışına alır; uygulamanın kendi trafiği de DPI aşmadan geçer), `UmitDpiBridge` (JS köprüsü: `window.UmitDpi`).
- `MainActivity`: köprü kaydı, VPN izin sonucu (`REQ_VPN`), "otomatik aç" ise açılışta başlat / çıkışta kapat.
- `AndroidManifest.xml`: `FOREGROUND_SERVICE` izni + `BIND_VPN_SERVICE` servisi.
- `assets/umitdpi_strategies.list` (71 hazır strateji) ve `umitdpi_sites.list` (test siteleri).

Sayfa (tv-izle HTML) tarafı: Ayarlar > **ÜmitDpi** (aç/kapat, otomatik aç, otomatik strateji seç), yan menüde **DPI** düğmesi. HTML'yi sayfanın barındırıldığı yere de yüklemek gerekir (`tv-izle_fix_perf_v9_umitdpi.html`).

Not: Bu ortamda Gradle/Android SDK olmadığından APK derlenemedi. Yeni Java dosyaları android.jar'a karşı derleme kontrolünden geçti; `MainActivity.kt` değişiklikleri derleyici ile doğrulanmadı.
