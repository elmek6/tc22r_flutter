### 🔴 1. Kaynak: Zebra Android 14 Release Notes (TC22R — Mart 2026)
**Kaynak:** `zebra.com/dam/release-notes/A14/...`

> **"GUN trigger for barcode scanning is available only when the application is connected to RFID module."**

Bu TC22R'e özgü **donanım kısıtlaması**. Tetik tuşu (GUN trigger), yani fiziksel tarama düğmesi, barkod için yalnızca uygulama RFID modülüne bağlı olduğunda aktif oluyor. Bu şu anlama geliyor: **RFID SDK bağlantısı kurulmadan barkod tetiklenemez**. Mevcut kodun `onProfileReady()` → `enableDataWedgeBarcode()` akışı bu kısıtlamayı doğrular.

---

### 🔴 2. Kaynak: Zebra TechDocs — DataWedge RFID Input Sayfası (DataWedge 13.0)
**Kaynak:** `techdocs.zebra.com/datawedge/13-0/guide/input/rfid/`

> **"This feature is only supported on MC3300R, RFD40, RFD90, TC53E and EM45."**

**TC22R bu listede yok.** DataWedge'in RFID Input plug-in'i TC22R'de desteklenmiyor. Yani DataWedge profilinde `rfid_input_enabled = false` gönderseniz de göndermеseniz de — DataWedge zaten RFID'yi kendi başına TC22R'de yönetemiyor. RFID yönetimi tamamen RFID SDK3'e bırakılmış.

**Beklenen davranış:** DataWedge profili yalnızca BARCODE + INTENT plugin'leri ile çalışmalı; RFID plugin etkinleştirilmemeli (zaten desteklenmiyor).

---

### 🟡 3. Kaynak: Zebra Developer Portal — MC3300x RFID + DataWedge Çözümü
**Kaynak:** `developer.zebra.com/content/mc3300x-rfid-stopped-working-updated-datawedge`

Kullanıcı Hakan Ostrom'un kendi sorusuna verdiği kabul edilen cevap:

> **"The DW-profile used must have the setting 'Gun trigger' under RFID Input → Configure reader settings → Hardware key selected."**

> **"No other apps on the device can run simultaneous that also uses the rfid-scanning (like for instance the demo app). It seems that in that case it could result in some kind of race condition."**

Başka bir kullanıcı (Thomas Feuerstein) programatik çözümü paylaştı:
> DataWedge profil `.db` dosyasını SQLite ile açıp `rfid_key_mapping` parametresini `1` olarak ayarlamak = "Gun trigger" → Hardware key.

**Bunun TC22R karşılığı:** `RFID SDK bağlandıktan sonra barkod tetikleyicisi serbest bırakılıyor` — bu tam olarak mevcut `onProfileReady() → enableDataWedgeBarcode()` akışının mantığı.

---

### 🟡 4. Kaynak: Zebra TechDocs — Scanner Input Plug-in API
**Kaynak:** `techdocs.zebra.com/datawedge/latest/guide/api/scannerinputplugin/`

Resmi API dokümantasyonundan kritik notlar:

- `ENABLE_PLUGIN` → Scanner aktif. WAITING/SCANNING durumu broadcast edilir.
- `DISABLE_PLUGIN` → Scanner pasif. DISABLED durumu broadcast edilir.
- `SUSPEND_PLUGIN` / `RESUME_PLUGIN` → Hızlı geçiş için tercih edilen yöntem (ancak yalnızca SCANNING veya WAITING durumundayken çalışır).

> **Zebra tavsiyesi:** "To avoid the unnecessary use of enable/disable scanner API calls, Zebra recommends that apps register to be notified of changes in scanner status using GET_SCANNER_STATUS API or SCANNER_STATUS from REGISTER_FOR_NOTIFICATION API."

**Beklenen sıra:**
```
1. DataWedge profili oluştur (SET_CONFIG) → RESULT: SUCCESS beklenir
2. REGISTER_FOR_NOTIFICATION ile scanner durumu dinle
3. RFID SDK bağlandı → scanner IDLE durumuna geçmeli
4. ENABLE_PLUGIN gönder → WAITING/SCANNING durumuna geçmeli
5. Barkod scan gelince Intent broadcast alınır
```

---

### 🟡 5. Kaynak: DataWedge FAQ — TechDocs
**Kaynak:** `techdocs.zebra.com/datawedge/latest/guide/faq/`

Resmi sorun giderme rehberinden:

> **"Q: Scanning works in DWDemo but not in my own app. Why?"**
> A: By default, the DWDemo profile is built-in to send scanned data via intent to the DWDemo app. A profile would need to be configured for your app to receive the scanned data. Make sure the profile is configured with the appropriate input (e.g. Barcode input) and output (e.g. Intent or Keystroke output).

> **"Q: I can scan barcodes but they are not sent to my app. Why?"**
> A: It is likely either a profile is not associated with your app or the profile input/output is not configured properly.

> **DataWedge API çoklu iş parçacığı uyarısı:**
> "Without proper synchronization, using DataWedge Intent APIs can lead to critical issues... Concurrent API calls resulting in unpredictable results due to the lack of guaranteed execution order."

---

### 🟠 6. Kaynak: Zebra Android 13 Release Notes (TC22)
**Google önizlemesinden:** `"SPR-50041 - Resolved an issue with Datawedge was not supporting RFID Input"` — TC22'de geçmişte DataWedge ile RFID uyumsuzluğu olduğu ve sonradan düzeltildiği görülüyor.

---

## ✅ Araştırma Özeti — Doğrulanan Teşhisler

| Alan | Resmi Kaynak Bulgusu | Analiz Teşhisiyle Örtüşme |
|---|---|---|
| **Barkod tetikleyici ancak RFID bağlıyken çalışır** | TC22R Android 14 RN ✅ | ✅ RFID bağlantısı olmadan barkod çalışmaz — DOĞRU |
| **DataWedge RFID Input TC22R'de yok** | TechDocs ✅ | ✅ RFID plugin kaldırılması doğru karar |
| **onProfileReady() → ENABLE_PLUGIN akışı zorunlu** | Scanner API docs ✅ | ✅ Bu callback çağrılmazsa barkod çalışmaz — DOĞRU |
| **Race condition riski (çoklu SDK)** | Forum + FAQ ✅ | ✅ DCS SDK çakışması — DOĞRU |
| **Intent filter ve profil ilişkilendirmesi kritik** | DW FAQ ✅ | ✅ `$packageName.RECVR` doğru olmalı |

---

## 🎯 Araştırmadan Çıkan Ek Öneriler (Resmi Kaynaklardan)

**RFID SDK sırası açısından dikkat edilmesi gerekenler:**
- TC22R'de barkod tetikleyicisi (GUN trigger), uygulama RFID modülüne bağlanana kadar hardware seviyesinde kilitli. Bu yüzden `onProfileReady()` callback'i RFID bağlantısı tamamlanmadan gelmiyor olabilir — sadece DataWedge profili oluşturulduğunda değil.
- Beklenen sıra: `RFID SDK connect → onProfileReady() tetiklenir → ENABLE_PLUGIN gönderilir → barkod aktif`

**DataWedge profili için:**
- Scanner status'u `REGISTER_FOR_NOTIFICATION` ile dinlemek, `ENABLE_PLUGIN` timing sorunlarını çözer.
- `SEND_RESULT: true` ve `COMMAND_IDENTIFIER` ekleyerek SET_CONFIG sonucunu alın; eğer FAILURE dönüyorsa profil oluşturulamamış demektir.

**Eşzamanlı SDK uyarısı:**
- Cihazda aynı anda başka bir RFID kullanan uygulama (örn. 123RFID Demo, RWDemo) açıksa race condition oluşuyor — test sırasında bu uygulamaların kapalı olduğundan emin olun.

---

## DataWedge SET_CONFIG Bundle Serialization Tipleri (2026-04-24)

ADB logcat incelemesinde SET_CONFIG gönderildiğinde DataWedge'in `ClassCastException` ürettiği görüldü:

```
W Bundle: Key PLUGIN_CONFIG expected ArrayList but value was [Landroid.os.Parcelable;
W Bundle: Key APP_LIST expected Parcelable[] but value was ArrayList
```

Bu, DataWedge 11.x (TC22R Android 14) içinde farklı key'lerin farklı tip beklediğini göstermektedir.

### Resmi Zebra örneği (DataCapture1/MainActivity.java — github.com/ZebraDevs)
```java
// PARAM_LIST → putBundle (tekil Bundle)
pluginConfig.putBundle("PARAM_LIST", paramBundle);
// PLUGIN_CONFIG → putBundle (tekil Bundle, tek plugin için)
profileConfig.putBundle("PLUGIN_CONFIG", pluginConfig);
// APP_LIST → putParcelableArray (Bundle[])
profileConfig.putParcelableArray("APP_LIST", new Bundle[]{appConfig});
```

### Doğrulanan tipler (ADB ClassCastException'dan)
| Key | DataWedge'in beklediği | Kullanılacak metod |
|---|---|---|
| `APP_LIST` | `Parcelable[]` (raw array) | `putParcelableArray` |
| `PLUGIN_CONFIG` | `ArrayList` | `putParcelableArrayList` |
| `PARAM_LIST` | `Bundle` (tekil) | `putBundle` |

**Not:** Birden fazla plugin için `PLUGIN_CONFIG` üzerinde `putParcelableArrayList` kullanılmalı. Resmi örnek eski DataWedge versiyonu için `putBundle` (tek plugin) gösteriyor; DataWedge 11.x `getParcelableArrayList` çağırıyor.

