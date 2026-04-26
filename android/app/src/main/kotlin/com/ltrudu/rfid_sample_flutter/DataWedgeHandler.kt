package com.ltrudu.rfid_sample_flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log

class DataWedgeHandler(
    private val context: Context,
    private val onEvent: (Map<String, Any?>) -> Unit
) {
    private val TAG = "DataWedgeHandler"

    companion object {
        private const val DW_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DW_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"

        // Intent action DataWedge will broadcast scan results to.
        // Must match EXACTLY what we set in the INTENT plugin config below.
        private const val SCAN_ACTION = "com.ltrudu.rfid_sample_flutter.SCAN"

        private const val EXTRA_DATA = "com.symbol.datawedge.data_string"
        private const val EXTRA_LABEL = "com.symbol.datawedge.label_type"

        private const val EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
        private const val EXTRA_CREATE_PROFILE = "com.symbol.datawedge.api.CREATE_PROFILE"
        private const val EXTRA_SWITCH_TO_PROFILE = "com.symbol.datawedge.api.SWITCH_TO_PROFILE"
        private const val EXTRA_SOFT_SCAN_TRIGGER = "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER"

        const val PROFILE_NAME = "TC22RFlutter"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                SCAN_ACTION -> {
                    val data = intent.getStringExtra(EXTRA_DATA) ?: return
                    val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                    onEvent(mapOf("type" to "barcodeData", "data" to data, "symbology" to label))
                }
                DW_RESULT_ACTION -> {
                    val res = intent.getStringExtra("RESULT")
                    if (res != null) {
                        onEvent(mapOf("type" to "message", "message" to "DW: $res"))
                    }
                }
            }
        }
    }

    private var registered = false

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(SCAN_ACTION)
            addAction(DW_RESULT_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        Log.d(TAG, "BroadcastReceiver registered")
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister", e)
        }
        registered = false
    }

    // Called on every onResume so our profile stays active even if DataWedge
    // reverted to Profile0 while the app was in the background.
    fun ensureProfile() {
        try {
            sendCommand(EXTRA_CREATE_PROFILE, PROFILE_NAME)
            context.sendBroadcast(Intent(DW_ACTION).apply { putExtra(EXTRA_SET_CONFIG, buildConfig()) })
            sendCommand(EXTRA_SWITCH_TO_PROFILE, PROFILE_NAME)
            Log.d(TAG, "ensureProfile: $PROFILE_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "ensureProfile", e)
        }
    }

    fun softScanStart() = sendCommand(EXTRA_SOFT_SCAN_TRIGGER, "START_SCANNING")
    fun softScanStop() = sendCommand(EXTRA_SOFT_SCAN_TRIGGER, "STOP_SCANNING")

    // Forward singleTop onNewIntent in case profile is set to START_ACTIVITY delivery.
    fun handleIntent(intent: Intent) {
        if (intent.action == SCAN_ACTION) {
            val data = intent.getStringExtra(EXTRA_DATA) ?: return
            val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
            onEvent(mapOf("type" to "barcodeData", "data" to data, "symbology" to label))
        }
    }

    private fun sendCommand(key: String, value: String) {
        context.sendBroadcast(Intent(DW_ACTION).apply { putExtra(key, value) })
    }

    private fun buildConfig(): Bundle {
        val pkg = context.packageName

        val appConfig = Bundle().apply {
            putString("PACKAGE_NAME", pkg)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        // BARCODE plugin: enable scanner, auto-detect, common decoders.
        val barcodeConfig = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("scanner_input_enabled", "true")
                putString("scanner_selection", "auto")
                putString("decoder_ean13", "true")
                putString("decoder_ean8", "true")
                putString("decoder_code128", "true")
                putString("decoder_qrcode", "true")
                putString("decoder_datamatrix", "true")
            })
        }

        // INTENT plugin: broadcast scan results to SCAN_ACTION.
        // intent_delivery "2" = Broadcast Intent (required for Android 8+).
        val intentConfig = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("intent_output_enabled", "true")
                putString("intent_action", SCAN_ACTION)
                putString("intent_delivery", "2")
            })
        }

        // KEYSTROKE plugin: disable so scan data does not type into focused fields.
        val keystrokeConfig = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("keystroke_output_enabled", "false")
            })
        }

        return Bundle().apply {
            putString("PROFILE_NAME", PROFILE_NAME)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf(appConfig))
            putParcelableArrayList(
                "PLUGIN_CONFIG",
                arrayListOf(barcodeConfig, intentConfig, keystrokeConfig)
            )
        }
    }
}
