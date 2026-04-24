# RFID Sample App - Flutter + Zebra TC22

## Overview

Flutter application for Zebra TC22 RFID reader (Android 14). Integrates Zebra RFID SDK3 for tag reading and DataWedge Intent API for barcode scanning.

## Features

- **RFID Inventory**: Start/stop tag reading, power control, trigger-based scanning
- **Barcode Scanning**: DataWedge soft trigger + physical trigger support
- **Event Logging**: Real-time connection status and tag events

## Tech Stack

- Flutter 3.x with `flutter_hooks` ^0.20.5
- Zebra RFID SDK3 2.0.5.238
- Target: Android 14 (API 34)

## Architecture

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

## Key Solutions Applied

### RFID Connection (`RfidHandler.kt`)
- `resetAndRetry()`: `Readers.Dispose()` + 1500ms sleep + recreate clears stuck SerialInputOutputManager thread (prevents RFID_API_COMMAND_TIMEOUT)
- `configureReaderOnConnect()`: Event handler registered once on connect (not per-inventory)
- `connectionLock` synchronized block prevents concurrent SerialInputOutputManager access

### DataWedge Integration (`DataWedgeHandler.kt`)
- Dynamic `RECEIVER_EXPORTED` receiver (not static manifest) — Android 14 compliant
- No `addCategory()` on IntentFilter — DataWedge omits category from broadcasts
- RFID plugin disabled in DataWedge profile — rfidhost owns the serial port exclusively
- `SWITCH_TO_PROFILE` called on every `onResume()` (1s delay after SET_CONFIG)

### Barcode Scanner Conflict Resolution
- `ScannerHandler` (DCS Scanner SDK) **removed** — conflicts with RFID SDK, causes `RFID_API_COMMAND_TIMEOUT`
- Barcode scanning uses DataWedge Intent API only

## Project Files

| File | Description |
|------|-------------|
| `report.md` | All research, issues, and solutions consolidated |
| `project_memory.md` | Quick reference summary |
| `research_android14_datawedge.md` | DataWedge broadcast receiver research |
| `todo.md` | Completed and pending tasks |

## Unresolved Issues

1. **SET_CONFIG result broadcast not received** — DataWedge limitation, profile may be created but cannot be verified programmatically
2. **Physical trigger in barcode mode starts RFID** — `handleTriggerPress` always calls `performInventory()`, no mode distinction between RFID and barcode screens

## References

- [Zebra Developer Portal](https://developer.zebra.com)
- [Zebra TechDocs](https://techdocs.zebra.com)