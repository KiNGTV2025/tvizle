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


## Mert Stream eşdeğer yerel entegrasyonları (26.11.2026)

- `NativeCompanionBridge.kt` eklendi. HTML modülleri için Android Keystore tabanlı AES-GCM şifreli kalıcı depolama sağlar.
- Stremio eklenti manifest listesi native şifreli depoya yazılır; Android uygulaması yeniden açıldığında aynı eklentiler korunur.
- Trakt access/refresh token alanları için güvenli saklama anahtarları hazırlandı (Trakt OAuth akışı ayrı olarak HTML tarafında tamamlanmalıdır).
- Güvenilir HTML sayfası için Android DownloadManager indirme köprüsü ve TV cihaz bilgisi eklendi.
- `UmitCompanion` JavaScript arayüzü yalnızca uygulamanın güvenilir HTTPS sayfası açıkken işlem yapar.
- Uygulama sürümü `10.4` olarak yükseltildi; CI iş akışı bu sürümden yeni APK üretir.

Bu değişiklikler Mert Stream APK'sından kod kopyalamaz; APK'da tespit edilen Stremio, Trakt, spor merkezi ve takvim gibi işlevlerin Ümit TV mimarisinde kullanılabilmesi için yerel altyapı sağlar. ESPN/TMDB/Stremio veri ekranları HTML tarafındadır. Trakt tam OAuth senkronizasyonu, bildirim zamanlayıcısı ve gerçek cihazlar arası senkronizasyon bu pakette henüz tamamlanmış değildir.

Derleme bu çalışma ortamında çalıştırılmadı (Android SDK/Gradle kurulu değil). GitHub Actions iş akışı Gradle 8.13 + Android SDK/NDK ile APK'yı derlemek üzere yapılandırılmıştır.
