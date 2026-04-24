---
name: Android 14 DataWedge Broadcast Research
description: TC22R Android 14 + DataWedge 11.x broadcast receiver çalışma kuralları, araştırma sonuçları
type: project
---

# Android 14 + DataWedge Broadcast Araştırması

Cihaz: Zebra TC22R, Android 14 (API 34), DataWedge 11.x

## Soru 1 — RECEIVER_EXPORTED yeterli mi?

Evet, `Context.RECEIVER_EXPORTED` ile dinamik kayıt Android 14'te zorunlu. Belirtilmezse `SecurityException` fırlatılır.
DataWedge'den gelen broadcast'lar bu flag ile dinamik receiver'a ULAŞABİLİR — ama önemli uyarılar var.

## Soru 2 — DataWedge profil doğrulama

- Cihazda DataWedge uygulamasını aç → Profiles listesi
- Profil adı = paket adı (com.ltrudu.rfid_sample_flutter) olarak görünür
- Scanner enabled/disabled durumu ve intent output konfigürasyonu profil detayında görülür

## Soru 3 — Android 14 Implicit Broadcast Kısıtlaması ⚠️ KRİTİK

**Android 14 (API 34) hedefliyorsa:** DataWedge implicit broadcast gönderirse (sadece action, package belirtmeden) uygulamaya ulaşmayabilir.

**Geçici çözüm A — Statik Manifest Receiver:** Receiver'ı AndroidManifest.xml'e `android:exported="true"` ile ekle. Custom action'lar (sistem action'ı olmayan) manifest-declared static receiver'lara Android 8+'dan beri ulaşır — Android 14'te de çalışır.

**Geçici çözüm B — Dinamik receiver + setPackage:** DataWedge'in broadcast'ına `setPackage()` ile paket adı eklemesi gerekir. DataWedge'in bunu profil bağlantısından otomatik yapması beklenir ama garantili değil.

**Gerçek durum (araştırma 2):**
- DataWedge, intent output'u `intent.setPackage(targetPackageName)` ile **explicit** gönderiyor — implicit DEĞİL.
- Dolayısıyla hem static hem de dynamic `RECEIVER_EXPORTED` receiver çalışır.
- Kategori filtresi OLMADAN dynamic receiver tercih edilmeli (daha basit).

**Uygulanan çözüm:** Dynamic `RECEIVER_EXPORTED` receiver, category filter olmadan.

**Why:** Static receiver denendi ama static receivers Android 8+ targetSdk 26+ ile implicit broadcast alamaz — bu yanlış bir hipotezdi. DataWedge aslında explicit gönderiyor ama category filter varsa eşleşme bozuluyor.

**How to apply:** `IntentFilter` oluştururken `addCategory()` EKLEME. `RECEIVER_EXPORTED` flag yeterli.

---

## Araştırma 2 — Restart Sonrası Çalışmama (2026-04-22)

### Bulgu: SWITCH_TO_PROFILE çağrılmıyor

DataWedge profil geçişi otomatik DEĞİL. Zebra TechDocs:
> "Since DataWedge automatically switches Profile when an activity is paused, Zebra recommends calling this API from the onResume method of the activity."

**Davranış:**
- App arka plana gidince → DataWedge Default Profile'a geçiyor
- App geri gelince → DataWedge otomatik geri DÖNMÜYOR
- `onResume()` içinde `SWITCH_TO_PROFILE` çağrılmadan profil aktif olmuyor

**Çözüm kodu (MainActivity.onResume):**
```kotlin
val intent = Intent()
intent.action = "com.symbol.datawedge.api.ACTION"
intent.putExtra("com.symbol.datawedge.api.SWITCH_TO_PROFILE", packageName)
sendBroadcast(intent)
```

**How to apply:** Her `onResume()` içinde, `initialized` kontrolünden sonra, SWITCH_TO_PROFILE çağır. Profil adı = paket adı (`com.ltrudu.rfid_sample_flutter`).

---

## Araştırma 3 — SET_CONFIG Sonucu Neden Gelmiyor (2026-04-22)

### Bulgu: Bilinen DataWedge Limitasyonu

RESULT_ACTION broadcast'ı gelmemesi **belgelenmiş bir DataWedge sorunudur**, kod hatası değil.

Zebra kendi dökümantasyonunda: *"Zebra recommends implementing a retry mechanism if a response from DataWedge is not received from the intent API sent to DataWedge."*

**Önemli:** SET_CONFIG result gelmese de, profil cihazda **başarıyla oluşturulmuş olabilir.** Profil doğrulamak için `GET_PROFILES_LIST` API kullanılmalı (result broadcast'a güvenme).

**Android 14 etkisi:** ~500ms ek gecikme var. Result broadcast unreliable olmayı devam ettiriyor.

**How to apply:** SET_CONFIG sonucu bekleme — `onProfileReady()` callback'ine kritik iş koyma. Profil zaten oluştu kabul et. SWITCH_TO_PROFILE'ı SET_CONFIG'dan sonra 1 saniye delay ile gönder (profil işlenmeden önce switch edilirse DataWedge profili bulamaz).

---

Last updated: 2026-04-22