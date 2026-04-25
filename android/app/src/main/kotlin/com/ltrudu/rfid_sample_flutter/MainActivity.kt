package com.ltrudu.rfid_sample_flutter

import android.content.Intent
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter MainActivity for the Zebra TC22R RFID + Barcode sample.
 *
 * Channel contract (matches Dart side in lib/services/zebra_service.dart):
 *   MethodChannel: com.rfidsample/channel
 *     - initialize          -> Boolean
 *     - startRfidInventory  -> Boolean
 *     - stopRfidInventory   -> Boolean
 *     - startBarcodeScan    -> Boolean
 *     - stopBarcodeScan     -> Boolean
 *     - setPower(powerIndex)-> Boolean
 *     - getMaxPowerIndex    -> Int
 *     - isConnected         -> Boolean
 *     - setActiveMode(mode) -> Boolean   // "rfid" | "barcode"
 *
 *   EventChannel: com.rfidsample/events
 *     pushes Map<String, Any?> with "type" key:
 *     rfidConnected | rfidDisconnected | rfidError | tagData
 *     barcodeData   | triggerPress    | message
 *
 * Why this layout works on TC22R (RFID + barcode in one app):
 *   1. RFID API3 SDK owns the UHF radio serial port via the rfidhost service.
 *   2. The DataWedge profile we create for THIS app keeps RFID Input DISABLED
 *      (so the SDK and DataWedge never fight over the radio) and Barcode Input
 *      + Intent Output ENABLED.
 *   3. The DataWedge profile is hard-bound to our package name via APP_LIST so
 *      the broadcast intent only fires for us. The single most common reason
 *      barcodes silently fail in custom apps is that Profile0 keeps stealing
 *      the scan because no profile is associated with the package.
 *   4. The hardware trigger is routed by activeMode: in "barcode" mode we send
 *      the DataWedge SOFT_SCAN_TRIGGER START intent; in "rfid" mode we kick the
 *      RFID inventory. Without this router the trigger always falls into RFID
 *      and the barcode screen looks dead.
 */
class MainActivity : FlutterActivity() {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null

    private var rfid: RfidHandler? = null
    private var dataWedge: DataWedgeHandler? = null

    @Volatile private var activeMode: String = "rfid"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)

        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                eventSink = events
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "initialize" -> {
                    initHandlersIfNeeded()
                    result.success(true)
                }
                "startRfidInventory" -> {
                    rfid?.startInventory()
                    result.success(true)
                }
                "stopRfidInventory" -> {
                    rfid?.stopInventory()
                    result.success(true)
                }
                "startBarcodeScan" -> {
                    dataWedge?.softScanStart()
                    result.success(true)
                }
                "stopBarcodeScan" -> {
                    dataWedge?.softScanStop()
                    result.success(true)
                }
                "setPower" -> {
                    val idx = call.argument<Int>("powerIndex") ?: 0
                    rfid?.setPower(idx)
                    result.success(true)
                }
                "getMaxPowerIndex" -> result.success(rfid?.getMaxPowerIndex() ?: 270)
                "isConnected" -> result.success(rfid?.isConnected() == true)
                "setActiveMode" -> {
                    val mode = call.argument<String>("mode") ?: "rfid"
                    activeMode = if (mode == "barcode") "barcode" else "rfid"
                    if (activeMode == "barcode") {
                        // Stop the radio so the trigger is not held by RFID inventory.
                        rfid?.stopInventory()
                    } else {
                        // Make sure no soft-scan is still pending if we leave barcode tab.
                        dataWedge?.softScanStop()
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun initHandlersIfNeeded() {
        if (rfid == null) {
            rfid = RfidHandler(applicationContext) { event -> emit(event) }
            rfid!!.connect()
        }
        if (dataWedge == null) {
            dataWedge = DataWedgeHandler(applicationContext) { event -> emit(event) }
            dataWedge!!.register()
        }
    }

    private fun emit(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        runOnUiThread { sink.success(event) }
    }

    /**
     * Hardware trigger router. TC22R sends keycodes 102/103/280/281/282/293
     * depending on the build. We swallow them and route to RFID or DataWedge
     * based on activeMode.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isZebraTriggerKey(keyCode)) {
            emit(mapOf("type" to "triggerPress", "pressed" to true))
            if (activeMode == "barcode") dataWedge?.softScanStart()
            else rfid?.startInventory()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isZebraTriggerKey(keyCode)) {
            emit(mapOf("type" to "triggerPress", "pressed" to false))
            if (activeMode == "barcode") dataWedge?.softScanStop()
            else rfid?.stopInventory()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isZebraTriggerKey(keyCode: Int): Boolean {
        return keyCode == 102 || keyCode == 103 || keyCode == 280 ||
            keyCode == 281 || keyCode == 282 || keyCode == 293
    }

    override fun onResume() {
        super.onResume()
        // Re-assert our DataWedge profile every time we come back to the
        // foreground: some Zebra builds re-bind Profile0 if the app was paused
        // for a while, which is the most common cause of "barcode worked once
        // then stopped".
        dataWedge?.ensureProfile()
    }

    override fun onDestroy() {
        try { rfid?.dispose() } catch (_: Throwable) {}
        try { dataWedge?.unregister() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity is singleTop. We use broadcast intents but forward here too,
        // so a misconfigured START_ACTIVITY profile still reaches the handler.
        dataWedge?.handleIntent(intent)
    }

    companion object {
        private const val METHOD_CHANNEL = "com.rfidsample/channel"
        private const val EVENT_CHANNEL = "com.rfidsample/events"
    }
}
