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
