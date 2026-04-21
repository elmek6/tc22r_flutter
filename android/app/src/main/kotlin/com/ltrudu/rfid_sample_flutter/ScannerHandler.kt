package com.ltrudu.rfid_sample_flutter

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.zebra.scannercontrol.DCSScannerInfo
import com.zebra.scannercontrol.DCSSDKDefs
import com.zebra.scannercontrol.FirmwareUpdateEvent
import com.zebra.scannercontrol.IDcsSdkApiDelegate
import com.zebra.scannercontrol.SDKHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Handles barcode scanning using the Zebra BarcodeScanner SDK (BarcodeScannerLibrary.aar).
// This mirrors ScannerHandler.java from the original sample exactly:
// USB-CDC → SNAPI → BT-LE → BT-Normal transport detection order.
class ScannerHandler(private val context: Context) : IDcsSdkApiDelegate {

    companion object {
        const val TAG = "SCAN_HANDLER"
    }

    private var sdkHandler: SDKHandler? = null
    private var scannerID = -1

    interface ScannerHandlerInterface {
        fun onBarcodeData(data: String, symbology: Int)
    }

    private var scannerHandlerCallback: ScannerHandlerInterface? = null

    init {
        initializeSDK()
    }

    // IDcsSdkApiDelegate – only dcssdkEventBarcode carries useful data for our use case
    override fun dcssdkEventScannerAppeared(info: DCSScannerInfo) {}
    override fun dcssdkEventScannerDisappeared(id: Int) {}
    override fun dcssdkEventCommunicationSessionEstablished(info: DCSScannerInfo) {}
    override fun dcssdkEventCommunicationSessionTerminated(id: Int) {}
    override fun dcssdkEventImage(data: ByteArray, id: Int) {}
    override fun dcssdkEventVideo(data: ByteArray, id: Int) {}
    override fun dcssdkEventBinaryData(data: ByteArray, id: Int) {}
    override fun dcssdkEventFirmwareUpdate(event: FirmwareUpdateEvent) {}
    override fun dcssdkEventAuxScannerAppeared(info: DCSScannerInfo, info2: DCSScannerInfo) {}

    override fun dcssdkEventBarcode(barcodeData: ByteArray, barcodeType: Int, fromScannerID: Int) {
        val s = String(barcodeData)
        scannerHandlerCallback?.onBarcodeData(s, barcodeType)
        Log.d(TAG, "barcode=$s  symbology=$barcodeType")
    }

    // Called when the screen becomes visible – re-registers callback and re-initializes SDK
    fun onResume(callback: ScannerHandlerInterface) {
        scannerHandlerCallback = callback
        initializeSDK()
    }

    fun onPause() {
        disconnect()
        sdkHandler = null
    }

    private fun initializeSDK() {
        sdkHandler = SDKHandler(context, true)
        sdkHandler!!.dcssdkSetDelegate(this)

        // Subscribe to scanner availability, session, and barcode events
        var mask = 0
        mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value
        mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value
        mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value
        mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value
        mask = mask or DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value

        sdkHandler!!.dcssdkEnableAvailableScannersDetection(true)
        sdkHandler!!.dcssdkSubsribeForEvents(mask)

        val available = getAllAvailableScanners()
        if (available.isNotEmpty()) {
            scannerID = available[0].scannerID
            sdkHandler!!.dcssdkEstablishCommunicationSession(scannerID)
        } else {
            Log.d(TAG, "No scanners available via DSC SDK")
        }
    }

    fun hasDetectedScanner() = scannerID != -1

    // Checks all transports in same order as original Java sample
    private fun getAllAvailableScanners(): List<DCSScannerInfo> {
        val all = mutableListOf<DCSScannerInfo>()
        all.addAll(getScannersForMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_USB_CDC))
        all.addAll(getScannersForMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_SNAPI))
        if (isBluetoothActive()) {
            all.addAll(getScannersForMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_LE))
            all.addAll(getScannersForMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_BT_NORMAL))
        }
        return all
    }

    private fun getScannersForMode(mode: DCSSDKDefs.DCSSDK_MODE): List<DCSScannerInfo> {
        val result = sdkHandler?.dcssdkSetOperationalMode(mode)
        return if (result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS) {
            @Suppress("UNCHECKED_CAST")
            sdkHandler?.dcssdkGetAvailableScannersList() as? List<DCSScannerInfo> ?: emptyList()
        } else emptyList()
    }

    private fun isBluetoothActive(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    @Synchronized
    private fun disconnect() {
        Log.d(TAG, "Disconnect")
        try {
            sdkHandler?.dcssdkTerminateCommunicationSession(scannerID)
            sdkHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Soft-trigger pull: fires the scanner beam programmatically
    fun pullTrigger() {
        val xml = "<inArgs><scannerID>$scannerID</scannerID></inArgs>"
        CoroutineScope(Dispatchers.IO).launch {
            executeCommand(DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_PULL_TRIGGER, xml, null, scannerID)
        }
    }

    fun releaseTrigger() {
        val xml = "<inArgs><scannerID>$scannerID</scannerID></inArgs>"
        CoroutineScope(Dispatchers.IO).launch {
            executeCommand(DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_DEVICE_RELEASE_TRIGGER, xml, null, scannerID)
        }
    }

    private fun executeCommand(
        opCode: DCSSDKDefs.DCSSDK_COMMAND_OPCODE,
        inXML: String,
        outXML: StringBuilder?,
        scannerID: Int
    ): Boolean {
        val out = outXML ?: StringBuilder()
        val result = sdkHandler?.dcssdkExecuteCommandOpCodeInXMLForScanner(opCode, inXML, out, scannerID)
        Log.d(TAG, "execute command returned $result")
        return result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS
    }
}
