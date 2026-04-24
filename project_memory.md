# RFID Sample App - Project Memory

## Summary

Flutter RFID application for Zebra TC22 device. Integrates Zebra RFID SDK3 and DataWedge for barcode scanning on Android 14.

## Key Solutions Applied

### RFID Connection Issues
- `resetAndRetry()`: `Readers.Dispose()` + 1500ms sleep + recreate clears stuck `SerialInputOutputManager` thread
- `configureReaderOnConnect()`: Event handler registered once on connect (not per-inventory)
- `connectionLock` synchronized block prevents concurrent SerialInputOutputManager access

### DataWedge Integration
- Dynamic `RECEIVER_EXPORTED` receiver (not static manifest)
- No `addCategory()` on IntentFilter — DataWedge omits category from broadcasts
- RFID plugin disabled in DataWedge profile — rfidhost owns the serial port
- `SWITCH_TO_PROFILE` called on every `onResume()` (1s delay after SET_CONFIG)

### Barcode Scanner Conflict
- `ScannerHandler` (DCS Scanner SDK) **removed** — conflicts with RFID SDK, causes `RFID_API_COMMAND_TIMEOUT`
- Barcode scanning uses DataWedge Intent API only

## File Structure

```
lib/
  main.dart                  # App entry, tab navigation
  screens/
    rfid_screen.dart         # HookWidget - RFID inventory
    barcode_screen.dart      # StatefulWidget - barcode scanning
  services/
    zebra_service.dart       # Platform channel wrapper

android/app/src/main/kotlin/.../
  MainActivity.kt            # Method/Event channels, lifecycle
  RfidHandler.kt             # RFID SDK management
  DataWedgeHandler.kt        # DataWedge API wrapper
```

## Tech Stack

- Flutter with `flutter_hooks` ^0.20.5
- Zebra RFID SDK3 2.0.5.238
- Target: Android 14 (API 34)

## Unresolved Issues

1. **SET_CONFIG result broadcast not received** — DataWedge limitation, profile may be created but cannot be verified programmatically
2. **Physical trigger in barcode mode starts RFID** — `handleTriggerPress` always calls `performInventory()`, no mode distinction between RFID and barcode screens

---

Last updated: 2026-04-22