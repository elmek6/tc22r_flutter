package com.ltrudu.rfid_sample_flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log

// DataWedge handler for Zebra TC22R barcode scanning.
// Architecture follows Zebra's prescribed sequence:
//   1. SET_CONFIG with CREATE_IF_NOT_EXIST  -> profile is persisted on device
//   2. SWITCH_TO_PROFILE in onResume        -> required on Android 14 (DataWedge
//      reverts to default profile when app is backgrounded)
//   3. Scan receiver registered with RECEIVER_EXPORTED, no DEFAULT category
//      (DataWedge sends barcode broadcasts WITHOUT android.intent.category.DEFAULT)
//   4. ENABLE_PLUGIN sent unconditionally with delay -- the SET_CONFIG result
//      broadcast is unreliable per Zebra's own documentation, so we never wait
//      for onProfileReady() before enabling the scanner.
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
    // CONFIG_MODE = CREATE_IF_NOT_EXIST so re-runs do not destroy a working profile.
    fun initialize(cb: DataWedgeHandlerInterface) {
        callback = cb
        val packageName = context.packageName

        // Listen for SET_CONFIG result (best-effort; result delivery is unreliable)
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
                    putString("intent_category", "")
                    putString("intent_delivery", "2") // 2 = BROADCAST
                })
            }

            // Scanner plugin: enabled, auto-select.
            // The SET_CONFIG result is unreliable; do NOT rely on onProfileReady()
            // to enable the scanner later. Enable it directly in the profile.
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

    // SWITCH_TO_PROFILE -- mandatory on Android 14: DataWedge reverts to the
    // default profile when the app goes to background; switching back must be
    // done explicitly on every onResume(). Profile name = package name.
    fun switchToProfile() {
        val packageName = context.packageName
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SWITCH_TO_PROFILE", packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SWITCH_TO_PROFILE=$packageName")
    }

    // ENABLE_PLUGIN -- the scanner plugin must be enabled for the active profile
    // before scanning works. Sent unconditionally (not gated on the unreliable
    // SET_CONFIG result broadcast).
    fun enableScannerPlugin() {
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "ENABLE_PLUGIN")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SCANNER_INPUT_PLUGIN=ENABLE_PLUGIN")
    }

    fun disableScannerPlugin() {
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SCANNER_INPUT_PLUGIN", "DISABLE_PLUGIN")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SCANNER_INPUT_PLUGIN=DISABLE_PLUGIN")
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

    // Register the broadcast receiver for barcode scan results.
    // IMPORTANT: NO addCategory() -- DataWedge does not include
    // android.intent.category.DEFAULT in barcode broadcasts; adding it
    // would silently filter out every scan event.
    fun startReceive() {
        val packageName = context.packageName
        if (mScanReceiver == null) {
            mScanReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    Log.d(TAG, "scan broadcast received action=" + intent.action)
                    val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
                    val typology = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
                    Log.d(TAG, "scan: type=$typology data=$data")
                    callback?.onBarcodeData(data, typology)
                }
            }
            val filter = IntentFilter("$packageName.RECVR")
            context.registerReceiver(mScanReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "Scan receiver registered for action=$packageName.RECVR (no category)")
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
    fun pullTrigger() {
        sendSoftTrigger("START_SCANNING")
    }

    fun releaseTrigger() {
        sendSoftTrigger("STOP_SCANNING")
    }

    private fun sendSoftTrigger(state: String) {
        val intent = Intent().apply {
            action = DW_ACTION
            putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", state)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "SOFT_SCAN_TRIGGER=$state")
    }

    fun isReady() = profileReady
}
