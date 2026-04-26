# TC22R RFID Cold Boot NPE — Sorun Özeti

## Sorun

Cihaz yeniden başlatıldıktan sonra uygulama ilk açıldığında RFID bağlantısı başarısız oluyor.
İkinci açılışta (uygulama kapatılıp açılınca) sorun yok, RFID normal çalışıyor.

## Hata

```
java.lang.NullPointerException: Attempt to invoke virtual method
'int com.zebra.rfidhost.RFIDHostEventAndReason.getKey()' on a null object reference
  at com.zebra.rfid.api3.API3UsbService.Connect(SourceFile:4)
  at com.zebra.rfid.api3.RFIDReader.connect(...)
  at RfidHandler.connectReader(RfidHandler.kt)
```

NPE tamamen Zebra SDK içinde (`API3UsbService.Connect`). Bizim kodumuzda değil.

## Gözlemler

- `connection with rfidhostService established` logu görünüyor — servis bağlı
- `GetAvailableRFIDReaderList` 1 item döndürüyor — okuyucu listede var
- `RFIDReaderAppeared` callback hiç tetiklenmiyor (liste zaten dolu olduğu için)
- Tüm denemelerde NPE tutarlı — timing ile ilgili değil
- İkinci açılışta aynı kod sorunsuz çalışıyor

## Denenenler (Hiçbiri İşe Yaramadı)

### 1. Basit try-catch
NPE yakalandı, hata loglandı. Retry yok. İkinci açılış çalışıyordu.

### 2. Aynı Readers instance üzerinde artan gecikmeli retry
Retry 1-5, her birinde daha uzun bekleme (1.5s, 3s, 4.5s, 6s, 7.5s = toplam ~22s).
Tüm retrylarda NPE. Timing kesinlikle sorun değil.

### 3. DataWedge RFID Input plugin disable + sıra değişikliği
Klavuzda "DataWedge RFID Input ile RFIDAPI3Library çakışır" bilgisine dayanarak:
- DataWedge profiline `rfid_input_enabled=false` eklendi
- `initHandlers()` içinde DataWedge önce, RFID sonra başlatıldı
Sonuç: İkinci açılış da bozuldu, NPE devam etti. Geri alındı.

### 4. Dispose + recreate Readers + retry (3x, 2s aralıklı)
Stack Overflow'da görülen pattern: NPE'de `readers.Dispose()` → `readers = null` → `new Readers()`.
Her retry'da yeni instance oluşturuldu. Tüm 4 denemede NPE.
İkinci açılışta log hiç gelmedi (yan etki). Geri alındı.

## Mevcut Kod Durumu (Temiz Hali)

`RfidHandler.kt` — orijinal hali, değişiklik yok.
`DataWedgeHandler.kt` — orijinal hali, değişiklik yok.
`MainActivity.kt` — orijinal hali, değişiklik yok.

## Son Hipotez (Henüz Denenmedi)

Log'da `RFIDReaderAppeared` hiç tetiklenmiyor çünkü `GetAvailableRFIDReaderList` zaten
okuyucuyu döndürüyor ve biz hemen `r.connect()` çağırıyoruz.

Ama rfidhost servisi bağlı olsa da, SDK içinde ilk bağlantı isteğine null response döndürüyor.
Belki rfidhost "hazır" olduğunda `RFIDReaderAppeared` callback'ini tetikliyor.

**Denenecek yaklaşım:**
NPE geldiğinde `readers.Dispose()` + `readers = null` + retry YOK.
Bunun yerine sadece bekle — rfidhost hazır olunca `RFIDReaderAppeared` tetiklenecek,
oradan `connectReader()` çağır.

Bu yaklaşım için araştırılacak soru:
"Zebra RFIDAPI3Library — `RFIDReaderAppeared` callback, `GetAvailableRFIDReaderList`
zaten okuyucu döndürdükten sonra da tetiklenir mi? Eğer tetiklenirse ne zaman?"

## Araştırma Kaynakları

- Klavuz: TC22R Flutter mimari kılavuzu (Hata 13: DataWedge RFID Input çakışması)
- Stack Overflow #77181744: Dispose+recreate pattern (işe yaramadı)
- Zebra PDF: Dispose() zorunluluğu (teyit edildi ama cold boot NPE'yi çözmedi)
- headuck/react-native-zebra-rfid: NPE bilerek import edilmiş (aynı sorunu yaşamış)
- Zebra TechDocs: `ReaderManagement.reboot()` metodu var (denenmedi)
