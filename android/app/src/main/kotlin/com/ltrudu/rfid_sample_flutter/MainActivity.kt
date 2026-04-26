package com.ltrudu.rfid_sample_flutter

import android.content.Intent
import android.view.KeyEvent
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
                    "isConnected" -> result.success(rfid?.isConnected() == true)
                    else -> result.notImplemented()
                }
            }
    }

    private fun initHandlers() {
        if (rfid == null) {
            rfid = RfidHandler(applicationContext) { event -> emit(event) }
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

    // Hardware trigger → start/stop RFID inventory.
    // DataWedge intercepts the upper (barcode) trigger before it reaches here,
    // so these keycodes are the lower RFID trigger on TC22R.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isZebraTrigger(keyCode)) {
            dataWedge?.disableScanner()
            rfid?.startInventory()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isZebraTrigger(keyCode)) {
            rfid?.stopInventory()
            dataWedge?.enableScanner()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isZebraTrigger(keyCode: Int): Boolean =
        keyCode == 102 || keyCode == 103 || keyCode == 280 ||
            keyCode == 281 || keyCode == 282 || keyCode == 293

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
