# ADB&Fastboot Tool v0.2

Android → Android OTG ile ADB ve fastboot komutları gönderen uygulama (Kotlin, Jetpack Compose, Material 3 Expressive).

## Özellikler
- ADB (kendi RSA anahtarıyla), bootloader/recovery'ye yeniden başlatma, otomatik yeniden bağlanma
- Fastboot: getvar, kilit açma izni kontrolü, kilit açma, recovery/boot/vendor_boot yükleme, yeniden başlatma
- Hazırlık listesi, marka notları, konsol (kopyala/paylaş)

## GitHub'da derleme
1. Bu klasörün İÇİNDEKİLERİ (`.github` dahil) yeni bir GitHub deposunun köküne yükle.
2. Actions sekmesi → "APK derle" çalışır (ya da "Run workflow").
3. İş bitince sayfanın altındaki **Artifacts → ADBFastbootTool-debug-apk** dosyasını indir, zip'ten çıkan APK'yı host telefona kur.
4. `v0.2` gibi bir etiket (tag) atarsan APK, Releases'e de yüklenir.

## Test sırası
1. Hazırlık sekmesini doldur.
2. Hedef telefonda USB hata ayıklamayı aç, OTG ile bağla, "Cihaz bağla" → izinleri ver.
3. Komutlar → "Bootloader'a yeniden başlat" (otomatik fastboot'a geçer).
4. Fastboot'ta "Cihaz bilgilerini oku" ve "Kilit açma iznini kontrol et".
5. Kilit açma ve görüntü yükleme en sonda; önce yedek al.
