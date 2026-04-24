# RFID Sample App - TODO

## ✅ Completed

### RFID Connection
- [x] Solve `RFID_API_COMMAND_TIMEOUT` on reconnect — `resetAndRetry()` with `Readers.Dispose()` + 1500ms sleep + recreate
- [x] Event handler registered once on connect (`configureReaderOnConnect()`), not per-inventory
- [x] `connectionLock` synchronized block prevents concurrent SerialInputOutputManager access
- [x] `SERVICE_READY_DELAY_MS` 1500ms prevents RFIDHostEventAndReason null NPE

### DataWedge Integration
- [x] Dynamic `RECEIVER_EXPORTED` receiver (not static manifest) for Android 14
- [x] No `addCategory()` on IntentFilter — DataWedge omits category from broadcasts
- [x] RFID plugin disabled in DataWedge profile — rfidhost owns serial port exclusively
- [x] `SWITCH_TO_PROFILE` called on every `onResume()` (1s delay after SET_CONFIG)
- [x] `resetAndRetry()` without `am force-stop` — works without system permissions

### Barcode Scanner Conflict
- [x] Remove `ScannerHandler` (DCS Scanner SDK) — conflicts with RFID SDK
- [x] Use DataWedge Intent API only for barcode scanning
- [x] Handle both DataWedge string symbology and DSC SDK int symbology in `BarcodeDataEvent`

### Flutter UI
- [x] Convert `rfid_screen.dart` to HookWidget with `useState`/`useEffect`/`useRef`
- [x] Create separate `barcode_screen.dart` for barcode scanning
- [x] Add `label` field to `BarcodeDataEvent` for DataWedge symbology string

### Documentation
- [x] Create `report.md` with all research and solutions consolidated
- [x] Create `project_memory.md` for quick reference
- [x] Create `research_android14_datawedge.md` for DataWedge research
- [x] Update `README.md` with project overview and architecture

## ⚠️ Unresolved Issues

### High Priority
- [ ] **Physical trigger in barcode mode starts RFID** — `handleTriggerPress` always calls `performInventory()`. Need mode check to distinguish between RFID and barcode screens.
- [ ] **SET_CONFIG result broadcast not received** — DataWedge limitation, profile may be created but cannot be verified programmatically. Zebra documentation confirms this is expected.

### Low Priority
- [ ] `ScannerHandler.java` still exists in project but is unused — can be removed
- [ ] Force-stop research notes can be removed since `resetAndRetry()` without force-stop works

## 📋 Known Limitations

1. Cold boot is the only reliable way to reset SDK state after SerialInputOutputManager gets stuck
2. DataWedge profile must be manually verified in DataWedge app settings
3. RFID SDK and Barcode Scanner SDK cannot run simultaneously on TC22R

---

Last updated: 2026-04-22