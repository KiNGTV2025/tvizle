Selamünaleyküm 
## 10.6
- Sesli komut: 2./3. denemede dinlememe düzeltildi. SpeechRecognizer artık her oturum bitince kapatılıyor,
  yeni oturum 350 ms bekleme ile açılıyor, ERROR_BUSY/CLIENT durumunda yerelde otomatik yeniden deneniyor.
- Sayfa tarafı (tv-izle html): çift tetiklenen tuş/tıklama yutuluyor, eski dinleyici tamamen susturuluyor.

## 10.7
- Zaplama: kanal geçişinde oynatma için gereken ilk tampon 2,5 sn → 1,5 sn (düşük donanımda 1 sn → 0,8 sn).
- Sayfa: zap onayından sonra fazladan 70 ms bekleme kaldırıldı; hızlı zaplarken her önizleme kanalının logosu
  indirilip çözülmüyor (tuş sakinleşince bir kez); saat metni ağır Intl biçimleyici yerine hızlı biçimde üretiliyor.

## 10.8
- Anlık güncelleme: sayfa şifreli (.dat) sunulmaya başlayınca "yeni sürüm" şeridi sessizce çalışmaz olmuştu
  (sayfa kendi düz kopyasını okuyamıyordu). Artık sayfa, kendi build numarasını (HTML v<N>) 20 sn'de bir
  version.json'daki build ile karşılaştırıyor. Şerit "Yenile"ye basınca taze sayfa/şifreli blob çekiliyor.
- SecurePage: şifreli blob indirilirken CDN önbelleği atlanıyor (?_=zaman damgası).

## 11.1
- Hızlı açılış (TV box): uygulama açılırken sayfa yüklenmesini beklemeden son canlı kanal ExoPlayer'da hemen başlar
  (sayfa arkada yüklenir, hazır olunca arayüz yayının üstüne yumuşakça açılır). Sayfa aynı kanalı isterse yayın
  yeniden yüklenmeden devralınır. Gerekli sayfa güncellemesi: tv-izle HTML (lastLive / pageReady çağrıları).

## 11.2
- Sesli komut: 1 kez kullanınca mikrofonun/ses tanıma servisinin kilitlenip Google Asistan ve YouTube'da da çalışmaz hale gelmesi (TV box'ı kapatıp açana kadar) düzeltildi.
  SpeechRecognizer artık cancel()+destroy() ile aynı anda ve geri çağrının içinden kapatılmıyor; dinleyici sökülür, cancel gönderilir, destroy gecikmeli yapılır (Google servisinde hayalet oturum kalmaz).
  Servis bağlantısı takılırsa (6 sn içinde hazır olmazsa) oturum temiz kapatılıp yeniden denenir; stopListening sonrası yanıt gelmezse 4 sn'de mikrofon zorla bırakılır.
