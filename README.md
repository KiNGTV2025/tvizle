Selamünaleyküm 
## 10.6
- Sesli komut: 2./3. denemede dinlememe düzeltildi. SpeechRecognizer artık her oturum bitince kapatılıyor,
  yeni oturum 350 ms bekleme ile açılıyor, ERROR_BUSY/CLIENT durumunda yerelde otomatik yeniden deneniyor.
- Sayfa tarafı (tv-izle html): çift tetiklenen tuş/tıklama yutuluyor, eski dinleyici tamamen susturuluyor.

## 10.7
- Zaplama: kanal geçişinde oynatma için gereken ilk tampon 2,5 sn → 1,5 sn (düşük donanımda 1 sn → 0,8 sn).
- Sayfa: zap onayından sonra fazladan 70 ms bekleme kaldırıldı; hızlı zaplarken her önizleme kanalının logosu
  indirilip çözülmüyor (tuş sakinleşince bir kez); saat metni ağır Intl biçimleyici yerine hızlı biçimde üretiliyor.
