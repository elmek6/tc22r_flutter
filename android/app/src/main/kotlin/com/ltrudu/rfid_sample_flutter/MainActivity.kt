package com.ltrudu.rfid_sample_flutter

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter MainActivity for the RFID + Barcode sample.
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
 *
 *   EventChannel: com.rfidsample/events  (Map<String, Any>)
 *     type = rfidConnected | rfidDisconnected | rfidError | tagData |
 *            barcodeData | triggerPress | message
 *
 * References (researched via Google):
 *  - ZebraDevs RFID-Android-Inventory-Sample (RFIDHandler.java)
 *  - ZebraDevs DataWedge-Flutter-Demo (MainActivity.kt + DWInterface.kt)
 *  - Flutter issue #34993: EventSink must be invoked on the Android UI thread.
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL = "com.rfidsample/channel"
        const val EVENT_CHANNEL = "com.rfidsample/events"
    }

    private lateinit var rfidHandler: RfidHandler
    private lateinit var dataWedgeHandler: DataWedgeHandler

    /**
     * Wraps Flutter's EventSink so that all events from background threads
     * (RFID reader thread, DataWedge BroadcastReceiver) are safely forwarded
     * on the Android main thread, avoiding the @UiThread crash described in
     * https://github.com/flutter/flutter/issues/34993
     */
    private class MainThreadEventSink : EventChannel.EventSink {
        @Volatile private var sink: EventChannel.EventSink? = null
        private val handler = Handler(Looper.getMainLooper())

        fun attach(sink: EventChannel.EventSink?) { this.sink = sink }
        fun detach() { this.sink = null }

        override fun success(event: Any?) {
            handler.post { sink?.success(event) }
        }
        override fun error(code: String, msg: String?, details: Any?) {
            handler.post { sink?.error(code, msg, details) }
        }
        override fun endOfStream() {
            handler.post { sink?.endOfStream() }
        }
    }

    private val eventSink = MainThreadEventSink()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Event stream Flutter -> Dart
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                    eventSink.attach(sink)
                }
                override fun onCancel(args: Any?) {
                    eventSink.detach()
                }
            })

        // Native handlers - both publish into the same EventSink wrapper.
        rfidHandler = RfidHandler(applicationContext, eventSink)
        dataWedgeHandler = DataWedgeHandler(this, eventSink)

        // Method invocations Dart -> native
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
                when (call.method) {
                    "initialize" -> {
                        rfidHandler.initialize()
                        dataWedgeHandler.register()
                        dataWedgeHandler.createOrUpdateProfile()
                        result.success(true)
                    }
                    "startRfidInventory" -> {
                        rfidHandler.startInventory()
                        result.success(true)
                    }
                    "stopRfidInventory" -> {
                        rfidHandler.stopInventory()
                        result.success(true)
                    }
                    "startBarcodeScan" -> {
                        dataWedgeHandler.softScan(true)
                        result.success(true)
                    }
                    "stopBarcodeScan" -> {
                        dataWedgeHandler.softScan(false)
                        result.success(true)
                    }
                    "setPower" -> {
                        val idx = call.argument<Int>("powerIndex") ?: 0
                        rfidHandler.setPower(idx)
                        result.success(true)
                    }
                    "getMaxPowerIndex" -> result.success(rfidHandler.maxPowerIndex())
                    "isConnected"     -> result.success(rfidHandler.isConnected())
                    else -> result.notImplemented()
                }
            }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply DataWedge profile in case another app changed the active one.
        if (this::dataWedgeHandler.isInitialized) dataWedgeHandler.switchToProfile()
    }

    override fun onDestroy() {
        if (this::rfidHandler.isInitialized) rfidHandler.dispose()
        if (this::dataWedgeHandler.isInitialized) dataWedgeHandler.unregister()
        super.onDestroy()
    }
}
