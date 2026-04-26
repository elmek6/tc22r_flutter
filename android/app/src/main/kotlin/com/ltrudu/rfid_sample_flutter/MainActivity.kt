package com.ltrudu.rfid_sample_flutter

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var eventSink: EventChannel.EventSink? = null
    private var rfid: RfidHandler? = null
    private var dataWedge: DataWedgeHandler? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "initialize" -> {
                        initHandlers()
                        result.success(null)
                    }
                    "startRfidInventory" -> {
                        dataWedge?.disableScanner()
                        rfid?.startInventory()
                        result.success(null)
                    }
                    "stopRfidInventory" -> {
                        rfid?.stopInventory()
                        dataWedge?.enableScanner()
                        result.success(null)
                    }
                    "startBarcodeScan" -> {
                        dataWedge?.softScanStart()
                        result.success(null)
                    }
                    "stopBarcodeScan" -> {
                        dataWedge?.softScanStop()
                        result.success(null)
                    }
                    "setPower" -> {
                        val dbm = call.argument<Int>("dbm") ?: 30
                        rfid?.setTransmitPower(dbm)
                        result.success(null)
                    }
                    "isConnected" -> result.success(rfid?.isConnected() == true)
                    else -> result.notImplemented()
                }
            }
    }

    private fun initHandlers() {
        if (rfid == null) {
            rfid = RfidHandler(
                applicationContext,
                onEvent = { event -> emit(event) },
                onTrigger = { pressed ->
                    if (pressed) dataWedge?.disableScanner()
                    else dataWedge?.enableScanner()
                }
            )
            rfid!!.connect()
        }
        if (dataWedge == null) {
            dataWedge = DataWedgeHandler(applicationContext) { event -> emit(event) }
            dataWedge!!.register()
            dataWedge!!.ensureProfile()
        }
    }

    private fun emit(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        runOnUiThread { sink.success(event) }
    }


    // Re-assert DataWedge profile on every resume: some Zebra builds revert
    // to Profile0 after the app is backgrounded.
    override fun onResume() {
        super.onResume()
        dataWedge?.ensureProfile()
    }

    // singleTop: forward barcode intents that arrive via START_ACTIVITY delivery.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dataWedge?.handleIntent(intent)
    }

    override fun onDestroy() {
        try { rfid?.dispose() } catch (_: Throwable) {}
        try { dataWedge?.unregister() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val METHOD_CHANNEL = "com.ltrudu.rfid_sample_flutter/method"
        private const val EVENT_CHANNEL = "com.ltrudu.rfid_sample_flutter/events"
    }
}
