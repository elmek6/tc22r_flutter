package com.ltrudu.rfid_sample_flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log

// DataWedge handler following Zebra's ScannerActivity.java pattern exactly
// Key changes based on official Zebra samples:
// 1. Intent action: com.packagename.ACTION (not .RECVR) 
// 2. Profile initially enabled scanner_input_enabled=true
// 3. No addCategory() in receiver filter
// 4. Scanner plugin starts enabled, not disabled
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

    // Initialize DataWedge profile following Zebra's pattern
    fun initialize(cb: DataWedgeHandlerInterface) {
        callback = cb
        val packageName = context.packageName

        // Listen for SET_CONFIG result
        registerResultReceiver(packageName)

        // Build profile config - following Zebra's ScannerActivity.java
        val profileConfig = Bundle().apply {
            putString("PROFILE_NAME", packageName)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")

            // App association
            val appConfig = Bundle().apply {
                putString("PACKAGE_NAME", packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }
            putParcelableArray("APP_LIST", arrayOf(appConfig))

            // Intent plugin: deliver barcodes as broadcast
            // KEY FIX: Use .ACTION suffix like Zebra's BasicIntent1 sample
            val intentPlugin = Bundle().apply {
                putString("PLUGIN_NAME", "INTENT")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("intent_output_enabled", "true")
                    putString("intent_action", "$packageName.ACTION")
                    putString("intent_category", "android.intent.category.DEFAULT")
                    putString("intent_delivery", "2") // 2 = BROADCAST
                })
            }

            // Scanner plugin: ENABLED from start (following Zebra's pattern)
            val scannerPlugin = Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("scanner_input_enabled", "true")
                    putString("scanner_selection", "auto")
                })
            }

            // Keystroke plugin: disabled
            val keystrokePlugin = Bundle().apply {
                putString("PLUGIN_NAME", "KEYSTROKE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("keystroke_output_enabled", "false")
                })
            }

            putParcelableArray("PLUGIN_CONFIG", arrayOf(intentPlugin, scannerPlugin, keystrokePlugin))
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
            try { context.unregisterReceiver(it) } catch (e: Exception) { }
            mResultReceiver = null
        }
    }

    // Register receiver for scan results
    // KEY FIX: Use .ACTION action, and NO addCategory() like Zebra's BasicIntent1
    fun startReceive() {
        val packageName = context.packageName
        if (mScanReceiver == null) {
            mScanReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    Log.d(TAG, "Received broadcast: " + intent.action)
                    val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
                    val typology = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
                    Log.d(TAG, "Barcode: type=$typology data=$data")
                    callback?.onBarcodeData(data, typology)
                }
            }
            // KEY FIX: Filter matches Zebra's BasicIntent1 - .ACTION suffix, no category
            val filter = IntentFilter("$packageName.ACTION")
            context.registerReceiver(mScanReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "Scan receiver registered for action: $packageName.ACTION")
        }
    }

    fun stopReceive() {
        mScanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { }
            mScanReceiver = null
            Log.d(TAG, "Scan receiver unregistered")
        }
        unregisterResultReceiver()
    }

    // Switch to profile (for onResume)
    fun switchToProfile() {
        val packageName = context.packageName
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SWITCH_TO_PROFILE", packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SWITCH_TO_PROFILE: $packageName")
    }

    // Soft trigger
    fun pullTrigger() {
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "START_SCANNING")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SOFT_SCAN_TRIGGER: START_SCANNING")
    }

    fun releaseTrigger() {
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "STOP_SCANNING")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SOFT_SCAN_TRIGGER: STOP_SCANNING")
    }

    fun isReady() = profileReady
}
