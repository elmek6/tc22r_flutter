package com.ltrudu.rfid_sample_flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log

// DataWedge fallback for barcode scanning when DSC SDK finds no scanner.
// Uses raw DataWedge intents (SET_CONFIG + broadcast receiver) — no content provider
// permission needed, unlike CreateProfileHelper which queries the content provider.
// Profile is persistent on the device; CREATE_IF_NOT_EXIST means re-runs are safe.
class DataWedgeHandler(private val context: Context) {

    companion object {
        const val TAG = "DW_HANDLER"
        private const val DW_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DW_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
        private const val DW_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
        private const val DW_RESULT_INFO = "com.symbol.datawedge.api.RESULT_INFO"
        private const val DW_SEND_RESULT = "com.symbol.datawedge.api.SEND_RESULT"
    }

    interface DataWedgeHandlerInterface {
        fun onBarcodeData(data: String, typology: String)
        fun onProfileReady()
        fun onProfileError(error: String)
    }

    private var callback: DataWedgeHandlerInterface? = null
    private var mScanReceiver: BroadcastReceiver? = null
    private var mResultReceiver: BroadcastReceiver? = null
    private var profileReady = false

    // Creates a DataWedge profile for this package via SET_CONFIG intent.
    // No content provider permission needed – intent is fire-and-forget with result callback.
    fun initialize(cb: DataWedgeHandlerInterface) {
        callback = cb
        val packageName = context.packageName

        // Listen for SET_CONFIG result
        registerResultReceiver(packageName)

        // Build profile config bundle
        val profileConfig = Bundle().apply {
            putString("PROFILE_NAME", packageName)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")

            // Bind this profile to our package
            val appConfig = Bundle().apply {
                putString("PACKAGE_NAME", packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }
            putParcelableArray("APP_LIST", arrayOf(appConfig))

            // Intent plugin: deliver barcodes as broadcast
            val intentPlugin = Bundle().apply {
                putString("PLUGIN_NAME", "INTENT")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("intent_output_enabled", "true")
                    putString("intent_action", "$packageName.RECVR")
                    putString("intent_category", "android.intent.category.DEFAULT")
                    putString("intent_delivery", "2") // 2 = BROADCAST
                })
            }

            // Scanner plugin: auto-select, enabled
            val scannerPlugin = Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("scanner_input_enabled", "true")
                    putString("scanner_selection", "auto")
                })
            }

            // Keystroke plugin: off
            val keystrokePlugin = Bundle().apply {
                putString("PLUGIN_NAME", "KEYSTROKE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("keystroke_output_enabled", "false")
                })
            }

            // RFID plugin: disabled so DataWedge releases the TC22r serial port,
            // allowing the RFID SDK3 (rfidhost service) to take exclusive control.
            val rfidPlugin = Bundle().apply {
                putString("PLUGIN_NAME", "RFID")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("rfid_input_enabled", "false")
                })
            }

            putParcelableArray("PLUGIN_CONFIG", arrayOf(intentPlugin, scannerPlugin, keystrokePlugin, rfidPlugin))
        }

        val intent = Intent().apply {
            action = DW_ACTION
            putExtra(DW_SET_CONFIG, profileConfig)
            putExtra(DW_SEND_RESULT, "true")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SET_CONFIG sent for profile: $packageName")
    }

    private fun registerResultReceiver(packageName: String) {
        if (mResultReceiver != null) return
        mResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val resultInfo = intent.getBundleExtra(DW_RESULT_INFO)
                val result = resultInfo?.getString("RESULT") ?: "UNKNOWN"
                val command = resultInfo?.getString("COMMAND") ?: ""
                Log.d(TAG, "DW result: command=$command result=$result")
                if (result == "SUCCESS" && command.contains("SET_CONFIG")) {
                    profileReady = true
                    callback?.onProfileReady()
                } else if (result == "FAILURE") {
                    callback?.onProfileError("SET_CONFIG failed: $result")
                }
            }
        }
        val filter = IntentFilter(DW_RESULT_ACTION)
        context.registerReceiver(mResultReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun unregisterResultReceiver() {
        mResultReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { /* already unregistered */ }
            mResultReceiver = null
        }
    }

    // Register the broadcast receiver and start listening for scan results.
    // Call from Activity.onResume().
    fun startReceive() {
        val packageName = context.packageName
        if (mScanReceiver == null) {
            mScanReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // DataWedge delivers barcode data via these standard extras
                    val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
                    val typology = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
                    Log.d(TAG, "scan: type=$typology data=$data")
                    callback?.onBarcodeData(data, typology)
                }
            }
            val filter = IntentFilter("$packageName.RECVR").apply {
                addCategory("android.intent.category.DEFAULT")
            }
            context.registerReceiver(mScanReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "Scan receiver registered")
        }
    }

    // Unregister receiver. Call from Activity.onPause().
    fun stopReceive() {
        mScanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { /* already unregistered */ }
            mScanReceiver = null
            Log.d(TAG, "Scan receiver unregistered")
        }
        unregisterResultReceiver()
    }

    // Soft trigger: tell DataWedge to start scanning programmatically.
    // DataWedge API 6.2+ – works on all Zebra Android devices running DataWedge 6.2+.
    fun pullTrigger() {
        sendSoftTrigger("START_SCANNING")
    }

    fun releaseTrigger() {
        sendSoftTrigger("STOP_SCANNING")
    }

    private fun sendSoftTrigger(state: String) {
        val intent = android.content.Intent().apply {
            action = "com.symbol.datawedge.api.ACTION"
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", state)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SOFT_SCAN_TRIGGER=$state")
    }

    fun isReady() = profileReady
}
