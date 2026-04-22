# RFID Sample App - Proje Deneyimi ve Öğrenilen Dersler

## Zebra TC22 RFID SDK3 Bağlantı Sorunları ve Çözümler

### Problem: RFID_API_COMMAND_TIMEOUT

**Belirtiler:**
- İlk bağlantı başarılı (cold boot sonrası)
- Uygulama kapatılıp açıldığında bağlantı başarısız
- Hata: `RFID_API_COMMAND_TIMEOUT`
- `SerialInputOutputManager: Already running` hatası loglarda

**Kök Neden:**
- Zebra SDK'nın dahili durumu (SerialInputOutputManager thread) app kapatılınca temizlenmiyor
- `rfidhost` servis process'i device-wide state tutuyor
- `Readers` objesi dispose edilmeden yeniden oluşturulursa eski state'ten etkileniyor

**Stack Overflow Çözümü:**
```kotlin
// readers.Dispose() + set to null + bekle + yeni Readers()
readers?.Dispose()  // Büyük D - Java method
readers = null
Thread.sleep(1000)  // 500-1000ms bekleme
readers = Readers(context, ENUM_TRANSPORT.SERVICE_USB)
```

### Önemli Notlar

1. **Dispose() büyük D ile yazılır** - Kotlin'de Java interop case-sensitive
2. **Service binding gecikmesi** - 1500ms bekleme RFIDHostEventAndReason null NPE'yi önler
3. **Force-stop rfidhost yetkisi** - System app değilse çalışmaz
4. **Cold boot gerekebilir** - Device-wide state'i temizlemek için güç döngüsü

### Event Handler Kayıt Hatası

**Problem:** Tag okunuyor ama ekranda görünmüyor (beep var)

**Sebep:** Event handler her performInventory'da yeniden ekleniyor, eski listener kaldırılmıyordu

**Çözüm:**
```kotlin
if (eventHandler == null) {
    eventHandler = EventHandler()
    reader!!.Events.addEventsListener(eventHandler)
} else {
    reader!!.Events.removeEventsListener(eventHandler)
    eventHandler = EventHandler()
    reader!!.Events.addEventsListener(eventHandler)
}
```

### Start/Stop Mimarisi

**Neden var:**
- Battery tasarrufu - sürekli okuma pil tüketir
- Sunucu yükü - her tag verisi transfer edilir
- Trigger kontrolü - basılı tutunca oku, bırakınca dur

### DataWedge Conflict

- TC22 barcode scanner DataWedge ile çalışıyor
- RFID aktifken barcode laser devre dışı kalmalı (RFID_MODE)
- DataWedge hatası: "Already running" - conflict göstergesi

### Barcode Scanner SDK Conflict (ÖNEMLİ) ✅ ÇÖZÜLDÜ

**Problem:** 
- `RFID_API_COMMAND_TIMEOUT` ve `SerialInputOutputManager: Already running` crash
- ScannerHandler (DCS Scanner SDK) RFID ile çakışıyor

**Sebep:**
- TC22R'de RFID SDK ve Barcode Scanner SDK aynı anda çalışamaz
- İkisi aynı USB transport katmanını kullanıyor
- Zebra'nın DCS Scanner SDK'ı enterprise cihazlarda önerilmez

**Çözüm (Uygulandı - 2026-04-21):**
- ScannerHandler kaldırıldı
- Sadece DataWedge Intent API kullanılıyor
- RFID SDK doğrudan bağlanıyor (DataWedge'e gerek yok)

**MainActivity değişiklikleri:**
- `scannerHandler = ScannerHandler(this)` kaldırıldı
- Sadece `dataWedgeHandler` kullanılıyor
- `startBarcodeScan`/`stopBarcodeScan` DataWedge'i kullanıyor

**Not:** ScannerHandler.java dosyası hâlâ projede var ama kullanılmıyor. Gelecekte tamamen kaldırılabilir.

### SDK Versiyonu

- Eski: 2.0.5.214
- Yeni: 2.0.5.238
- Yeni versiyon daha stable, daha iyi error handling

---

## Flutter Hooks Entegrasyonu

### pubspec.yaml Eklemeleri

```yaml
dependencies:
  flutter_hooks: ^0.20.5
```

### HookWidget Kullanımı

```dart
class RfidScreen extends HookWidget {
  @override
  Widget build(BuildContext context) {
    // useState - state management
    final tags = useState<List<TagItem>>([]);
    final status = useState<String>('Initializing...');
    
    // useEffect - side effects (componentDidMount gibi)
    useEffect(() {
      // Stream dinleme, initialization
      final sub = service.events.listen(_onEvent);
      return () => sub.cancel(); // cleanup
    }, []);
    
    // useRef - mutable reference (instance variable yerine)
    final scrollController = useRef(ScrollController());
    
    return Container();
  }
}
```

### useState vs useRef

- **useState**: UI render etmesi gereken değerler için
- **useRef**: Render tetiklemesi gerekmeyen mutable değerler için

---

Son güncelleme: 2026-04-21