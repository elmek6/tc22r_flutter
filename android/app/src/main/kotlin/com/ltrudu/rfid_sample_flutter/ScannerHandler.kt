package com.ltrudu.rfid_sample_flutter

/**
 * Intentionally empty placeholder.
 *
 * Earlier versions of this sample used the Zebra Barcode Scanner (DCS) SDK
 * via a class named ScannerHandler. That SDK opens the same serial port that
 * the integrated RFID reader (rfidhost / RFID API3 SDK3) needs, which on
 * TC22R causes RFID_API_COMMAND_TIMEOUT errors during inventory.
 *
 * Reference (researched via Google):
 *  - Zebra Developer Community threads describing the conflict between the
 *    DCS BarcodeScanner SDK and the RFID3 SDK on TC-series devices.
 *
 * The current architecture therefore performs barcode scanning purely via
 * the DataWedge Intent API (see DataWedgeHandler.kt), so this file is kept
 * only to preserve the historical class name and avoid breaking imports.
 */
internal object ScannerHandler {
    // No implementation. All barcode work lives in DataWedgeHandler.
}
