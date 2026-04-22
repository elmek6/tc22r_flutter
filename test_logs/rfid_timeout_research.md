# RFID_API_COMMAND_TIMEOUT Araştırma Raporu

## Sorunun Durumu: DEVAM EDİYOR - Kritik Değişiklik Yapıldı

### Yapılan Son Değişiklik (2026-04-21)
**`createInstance()` fonksiyonundan `am force-stop` kodu KALDIRILDI**

Orijinal Java sample'da `am force-stop` YOK. Biz eklemiştik ve bu timeout'a neden olabilir.

### Araştırma Soruları ve Cevapları

**S1: Orijinal ltrudu/RFIDSampleApp projesinde `am force-stop` var mı?**
- Cevap: **HAYIR** - Orijinal Java kodunda force-stop YOK
- Bizim eklediğimiz kod soruna neden olabilir

**S2: ScannerHandler orijinal projede nasıl çalışıyor?**
- Cevap: `ScannerHandler` orijinal projede `onPause()`'da `sdkHandler = null` yapıyor
- RFID ve Scanner aynı anda DEĞİL, sırayla çalışıyor (screen switch ile)

**S3: RFID bağlantısı yavaş mı?**
- Cevap: Evet - 2000ms bekleme süresi bağlantı timeout'una neden olabilir
- RFID_API_COMMAND_TIMEOUT genellikle "timeout" değil "bağlantı gecikmesi"

**S4: `isReaderConnected()` neden false döndürüyor?**
- Cevap: `reader.isConnected` property'si Kotlin'de doğru çalışmıyor olabilir
- VEYA gerçekten bağlantı kurulamıyor

### Bugünkü Hata Mesajları
```
D/RFID_HANDLER: reader is not connected
E/RFID_HANDLER: OperationFailureException: Response timeout
D/IRFIDError: RFIDPROTOAscii: GetStatus: RFID_API_COMMAND_TIMEOUTConnect size 0
```

### Uygulanan Tüm Çözümler

1. ✅ **ScannerHandler KALDIRILDI** - MainActivity'den referansları kaldırıldı
2. ✅ **DataWedge Intent API EKLENDİ** - Barcode taraması için
3. ✅ **setTriggerMode(RFID_MODE, true)** - second parameter TRUE
4. ✅ **synchronized blocks EKLENDİ** - `connectionLock` ile
5. ✅ **connect() basitleştirildi** - Tek attempt (orijinal Java gibi)
6. ✅ **am force-stop KALDIRILDI** - Orijinal Java'da yoktu!

---

## Uygulanan Çözüm (2026-04-21)

### 1. ScannerHandler Kaldırıldı
- `MainActivity.kt` - DCS Scanner SDK referansları kaldırıldı
- Sadece DataWedge Intent API kullanılıyor

### 2. DataWedge Plugin Yönetimi Eklendi
```kotlin
// RFID öncesi barcode plugin'ini disable et
disableDataWedgeBarcode()
rfidHandler.onCreate(...)
rfidHandler.onResume(...)

// RFID bağlandıktan sonra tekrar enable et
enableDataWedgeBarcode()
```

### 3. Broadcast Intent'ler
```kotlin
// Disable
dwIntent.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "DISABLE_PLUGIN")

// Enable  
dwIntent.putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "ENABLE_PLUGIN")
```

---

## Araştırma Bulguları

### TC22R'de RFID ve Barcode SDK Çakışması
- RFID SDK `SERVICE_SERIAL` üzerinden bağlanıyor
- Barcode Scanner SDK `USB_CDC` üzerinden bağlanıyor  
- İkisi aynı anda çalışamaz (tasarımsal olarak desteklenmiyor)

### BarcodeScannerLibrary.aar vs com.zebra.scannercontrol
- İkisi aynı şey - DCS Scanner SDK wrapper'ı
- ltrudu/RFIDSampleApp projede de aynı SDK kullanılıyor

### am force-stop Yeterli mi?
- **HAYIR** - sadece ayrı process'leri durdurur
- `SerialInputOutputManager` bizim app process'imizde çalışıyor
- Force-stop işe yaramıyor

---

## Orijinal ltrudu Projesiyle Fark

Orijinal projede çalışıyor ama bizim projede çalışmıyor. Olası farklar:

1. **EMDK Profile** - Orijinal projede EMDK profili yükleniyor olabilir
2. **Lifecycle yönetimi** - onCreate/onResume/onPause sırası farklı olabilir
3. **Konfigürasyon** - Build config veya manifest farklılıkları

---

## Önerilen Çözümler

### 1. Orijinal Projeyi Karşılaştır
ltrudu/RFIDSampleApp ile bizim proje arasındaki farkları bul:
- RfidHandler.java vs RfidHandler.kt
- MainActivity.java vs MainActivity.kt
- AndroidManifest.xml

### 2. EMDK Profile Installation
ProfileInstaller hatası olabilir. Orijinal projede:
```java
// RFIDHandler.java
if (!settings.getBoolean("profileInstalled", false)) {
    // Install EMDK profile
}
```

### 3. Bluetooth Transport Kullan
USB yerine Bluetooth üzerinden bağlanmayı dene:
```kotlin
readers = Readers(context, ENUM_TRANSPORT.BLUETOOTH)
```

### 4. Cold Boot
Cihazı tamamen kapatıp aç - device-wide state'i temizler

---

## Log Analizi

### Başarısız Log (Bizim proje)
```
D/RFID_HANDLER: CreateInstanceTask
D/RFID_HANDLER: Stopping all Zebra services before RFID init...
D/RFID_HANDLER: SERVICE_USB reader count: 1
D/RFID_HANDLER: Found reader: TC22R
D/RFID_HANDLER: Connect attempt 1 of 3
D/IRFIDError: RFID_API_COMMAND_TIMEOUTProtocolConfig
```

### Olması Gereken Log
```
D/RFID_HANDLER: CreateInstanceTask
D/RFID_HANDLER: SERVICE_USB reader count: 1
D/RFID_HANDLER: Found reader: TC22R
... (bağlantı başarılı)
```

**Fark:** Bizim log'da "Stopping all Zebra services" mesajı var - bu bizim eklediğimiz kod. Ama işe yaramıyor.

---

## Sonraki Adımlar

1. Orijinal ltrudu/RFIDSampleApp projesini clean olarak build et ve test et
2. Farklılıkları bulmak için dosyaları karşılaştır
3. Bizim projeye adaptasyon yap

---

## Kaynaklar

- [Zebra RFID SDK3 Documentation](https://developer.zebra.com/)
- [Stack Overflow: RFID_API_COMMAND_TIMEOUT](https://stackoverflow.com/)
- [EMDK for Android](https://developer.zebra.com/emdk)
