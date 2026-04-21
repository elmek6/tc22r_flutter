package com.ltrudu.rfid_sample_flutter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zebra.rfid.api3.TagData
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {

    companion object {
        const val TAG = "MAIN_ACTIVITY"
        const val METHOD_CHANNEL = "com.rfidsample/channel"
        const val EVENT_CHANNEL = "com.rfidsample/events"
        const val BT_PERMISSION_CODE = 100
    }

    private val rfidHandler = RfidHandler()

    // scannerHandler is null on EM45 (no external scanner port), same guard as original sample
    private var scannerHandler: ScannerHandler? = null

    // DataWedge fallback: used when DSC SDK finds no scanner (mirrors ScannerActivity.java)
    private var dataWedgeHandler: DataWedgeHandler? = null
    private var isUsingDataWedge = false

    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // True after the first successful initialize() call – prevents double-init
    private var initialized = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        setupEventChannel(flutterEngine)
        setupMethodChannel(flutterEngine)
    }

    private fun setupEventChannel(flutterEngine: FlutterEngine) {
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    eventSink = sink
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    private fun setupMethodChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    // Flutter calls this once when the app is ready
                    "initialize" -> {
                        initializeHandlers()
                        result.success(null)
                    }

                    "startRfidInventory" -> {
                        CoroutineScope(Dispatchers.IO).launch { rfidHandler.performInventory() }
                        result.success(null)
                    }

                    "stopRfidInventory" -> {
                        CoroutineScope(Dispatchers.IO).launch { rfidHandler.stopInventory() }
                        result.success(null)
                    }

                    // Soft-trigger: DSC SDK first, DataWedge fallback second
                    // mirrors ScannerActivity.java btScan onClick + TriggerDWScanner()
                    "startBarcodeScan" -> {
                        when {
                            scannerHandler?.hasDetectedScanner() == true ->
                                scannerHandler!!.pullTrigger()
                            isUsingDataWedge ->
                                dataWedgeHandler?.pullTrigger()
                        }
                        result.success(null)
                    }

                    "stopBarcodeScan" -> {
                        when {
                            scannerHandler?.hasDetectedScanner() == true ->
                                scannerHandler!!.releaseTrigger()
                            isUsingDataWedge ->
                                dataWedgeHandler?.releaseTrigger()
                        }
                        result.success(null)
                    }

                    // powerIndex is the raw index into the reader's power table (not dBm)
                    "setPower" -> {
                        val powerIndex = call.argument<Int>("powerIndex") ?: 0
                        CoroutineScope(Dispatchers.IO).launch {
                            val msg = rfidHandler.setAntennaPower(powerIndex)
                            sendEvent(mapOf("type" to "message", "message" to msg))
                        }
                        result.success(null)
                    }

                    "getMaxPowerIndex" -> {
                        result.success(rfidHandler.getMaxPowerIndex())
                    }

                    "isConnected" -> {
                        result.success(rfidHandler.isReaderConnected())
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun initializeHandlers() {
        val rfidInterface = buildRfidInterface()

        // On Android 12+ we need BLUETOOTH_CONNECT at runtime before touching the SDK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    BT_PERMISSION_CODE
                )
                return
            }
        }

        rfidHandler.onCreate(this, rfidInterface)
        // Activity is already resumed when Flutter calls "initialize", so trigger onResume manually
        rfidHandler.onResume(rfidInterface)

        val model = Build.MODEL
        // EM45 has no external scanner port – skip ScannerHandler the same way the original does
        if (!model.contains("EM45", ignoreCase = true)) {
            scannerHandler = ScannerHandler(this)
        }

        // If DSC SDK found no scanner, fall back to DataWedge (mirrors ScannerActivity.java)
        if (scannerHandler?.hasDetectedScanner() != true) {
            Log.d(TAG, "No DSC SDK scanner – initializing DataWedge fallback")
            dataWedgeHandler = DataWedgeHandler(this)
            dataWedgeHandler!!.initialize(object : DataWedgeHandler.DataWedgeHandlerInterface {
                override fun onBarcodeData(data: String, typology: String) {
                    sendEvent(mapOf("type" to "barcodeData", "data" to data, "symbology" to typology))
                }
                override fun onProfileReady() {
                    Log.d(TAG, "DataWedge profile confirmed ready")
                }
                override fun onProfileError(error: String) {
                    Log.w(TAG, "DataWedge profile error (non-fatal): $error")
                }
            })
            // Profile is created/updated async, but DataWedge will activate it as soon as
            // the app is foreground. Register receiver immediately so scans are not missed.
            isUsingDataWedge = true
            dataWedgeHandler!!.startReceive()
        }

        initialized = true
    }

    private fun buildRfidInterface(): RfidHandler.RfidHandlerInterface {
        return object : RfidHandler.RfidHandlerInterface {
            override fun onReaderConnected(message: String) {
                sendEvent(mapOf("type" to "rfidConnected", "message" to message))
            }
            override fun onReaderDisconnected() {
                sendEvent(mapOf("type" to "rfidDisconnected"))
            }
            override fun onTagData(tagData: Array<TagData>) {
                val tags = tagData.map {
                    mapOf("epc" to (it.tagID ?: ""), "rssi" to it.peakRSSI.toString())
                }
                sendEvent(mapOf("type" to "tagData", "tags" to tags))
            }
            override fun onMessage(message: String) {
                sendEvent(mapOf("type" to "message", "message" to message))
            }
            // Physical trigger mirrors TagInventoryActivity.handleTriggerPress()
            override fun handleTriggerPress(press: Boolean) {
                if (press) CoroutineScope(Dispatchers.IO).launch { rfidHandler.performInventory() }
                else CoroutineScope(Dispatchers.IO).launch { rfidHandler.stopInventory() }
                sendEvent(mapOf("type" to "triggerPress", "pressed" to press))
            }
        }
    }

    private fun buildScannerInterface(): ScannerHandler.ScannerHandlerInterface {
        return object : ScannerHandler.ScannerHandlerInterface {
            override fun onBarcodeData(data: String, symbology: Int) {
                sendEvent(mapOf("type" to "barcodeData", "data" to data, "symbology" to symbology))
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == BT_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeHandlers()
            } else {
                sendEvent(mapOf("type" to "message", "message" to "Bluetooth permissions not granted"))
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()
        if (!initialized) return
        rfidHandler.onResume(buildRfidInterface())
        if (scannerHandler?.hasDetectedScanner() == true) {
            scannerHandler!!.onResume(buildScannerInterface())
        } else if (isUsingDataWedge) {
            dataWedgeHandler?.startReceive()
        }
    }

    override fun onPause() {
        rfidHandler.onPause()
        if (scannerHandler?.hasDetectedScanner() == true) {
            scannerHandler!!.onPause()
        } else if (isUsingDataWedge) {
            dataWedgeHandler?.stopReceive()
        }
        super.onPause()
    }

    override fun onDestroy() {
        rfidHandler.onDestroy()
        super.onDestroy()
    }

    // Always delivers events on the main thread to satisfy Flutter's threading model
    private fun sendEvent(data: Map<String, Any?>) {
        mainHandler.post {
            eventSink?.success(data)
        }
    }
}
