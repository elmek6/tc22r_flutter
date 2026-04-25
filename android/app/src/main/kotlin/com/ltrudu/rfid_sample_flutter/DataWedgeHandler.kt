package com.ltrudu.rfid_sample_flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.flutter.plugin.common.EventChannel

/**
 * DataWedge integration for the Zebra TC22R barcode scanner.
 *
 * Adapted from the official ZebraDevs/DataWedge-Flutter-Demo
 * (DWInterface.kt + MainActivity.kt) and the Zebra Developer Portal post
 * "How to configure a DataWedge profile programatically in an Android Kotlin app".
 *
 * Approach:
 *  - Register a dynamic BroadcastReceiver with RECEIVER_EXPORTED on
 *    Android 13+ (Tiramisu) so DataWedge can deliver scan intents.
 *    See Flutter issue #34993 / Android 14 receiver export rule.
 *  - createOrUpdateProfile() pushes a SET_CONFIG bundle that:
 *      * binds the profile to this app's package + MainActivity,
 *      * enables the BARCODE plugin and the INTENT plugin (broadcast),
 *      * disables KEYSTROKE so the scan does not type into the focused field,
 *      * leaves the RFID plugin alone - the integrated RFID reader is
 *        owned by the native RFID SDK3 (rfidhost) on TC22R, so DataWedge
 *        must NOT also try to drive it.
 *  - softScan() emits the SOFT_SCAN_TRIGGER intent so the Flutter UI can
 *    start/stop the laser without a physical hardware key.
 */
class DataWedgeHandler(
    private val context: Context,
    private val sink: EventChannel.EventSink
) {
    private val tag = "DataWedgeHandler"

    companion object {
        private const val DW_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DW_RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
        private const val DW_CATEGORY = "android.intent.category.DEFAULT"

        // Custom intent action used by the BARCODE -> INTENT output plugin.
        private const val SCAN_INTENT_ACTION =
            "com.ltrudu.rfid_sample_flutter.SCAN"

        // DataWedge extras (from techdocs.zebra.com/datawedge intent api).
        private const val EXTRA_DATA = "com.symbol.datawedge.data_string"
        private const val EXTRA_LABEL = "com.symbol.datawedge.label_type"

        // SET_CONFIG / CREATE_PROFILE / SWITCH_TO_PROFILE / SOFT_SCAN_TRIGGER keys
        private const val EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
        private const val EXTRA_CREATE_PROFILE = "com.symbol.datawedge.api.CREATE_PROFILE"
        private const val EXTRA_SWITCH_TO_PROFILE = "com.symbol.datawedge.api.SWITCH_TO_PROFILE"
        private const val EXTRA_SOFT_SCAN_TRIGGER = "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER"

        const val PROFILE_NAME = "RfidSampleFlutter"
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                SCAN_INTENT_ACTION -> {
                    val data = intent.getStringExtra(EXTRA_DATA) ?: return
                    val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
                    sink.success(
                        mapOf(
                            "type" to "barcodeData",
                            "data" to data,
                            "symbology" to label // String like "LABEL-TYPE-EAN128"
                        )
                    )
                }
                DW_RESULT_ACTION -> {
                    // Optional: DataWedge ack. Surface as a generic message so
                    // the Flutter log can show profile-create results.
                    val cmd = intent.getStringExtra("RESULT")
                    if (cmd != null) {
                        sink.success(
                            mapOf("type" to "message", "message" to "DW result: " + cmd)
                        )
                    }
                }
            }
        }
    }

    private var registered = false

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(SCAN_INTENT_ACTION)
            addAction(DW_RESULT_ACTION)
            // NB: do NOT addCategory(DEFAULT) on the scan filter.
            // DataWedge does not always set the DEFAULT category on broadcasts;
            // the simpler filter is more reliable on Android 13/14.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        Log.d(tag, "BroadcastReceiver registered")
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {
            Log.w(tag, "unregister", e)
        }
        registered = false
    }

    fun createOrUpdateProfile() {
        try {
            // 1) Ensure the profile exists.
            sendCommand(EXTRA_CREATE_PROFILE, PROFILE_NAME)

            // 2) Push the configuration bundle.
            val setConfig = buildSetConfigBundle()
            val intent = Intent(DW_ACTION).apply {
                putExtra(EXTRA_SET_CONFIG, setConfig)
            }
            context.sendBroadcast(intent)

            // 3) Activate the profile.
            sendCommand(EXTRA_SWITCH_TO_PROFILE, PROFILE_NAME)
            Log.d(tag, "DataWedge profile created / updated: " + PROFILE_NAME)
        } catch (e: Exception) {
            Log.e(tag, "createOrUpdateProfile", e)
        }
    }

    fun switchToProfile() {
        sendCommand(EXTRA_SWITCH_TO_PROFILE, PROFILE_NAME)
    }

    fun softScan(start: Boolean) {
        sendCommand(EXTRA_SOFT_SCAN_TRIGGER, if (start) "START_SCANNING" else "STOP_SCANNING")
    }

    // -------------------------------------------------------------------- //

    private fun sendCommand(extraKey: String, value: String) {
        val intent = Intent(DW_ACTION).apply {
            putExtra(extraKey, value)
        }
        context.sendBroadcast(intent)
    }

    private fun buildSetConfigBundle(): Bundle {
        val pkg = context.packageName

        val appConfig = Bundle().apply {
            putString("PACKAGE_NAME", pkg)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        // BARCODE plugin: enable scanner with auto detection.
        val barcodeParams = Bundle().apply {
            putString("scanner_input_enabled", "true")
            putString("scanner_selection", "auto")
            putString("decoder_ean13", "true")
            putString("decoder_ean8", "true")
            putString("decoder_code128", "true")
            putString("decoder_qrcode", "true")
            putString("decoder_datamatrix", "true")
        }
        val barcodeConfig = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", barcodeParams)
        }

        // INTENT plugin: broadcast scan results to our custom action.
        val intentParams = Bundle().apply {
            putString("intent_output_enabled", "true")
            putString("intent_action", SCAN_INTENT_ACTION)
            putString("intent_delivery", "2") // 2 = broadcast
        }
        val intentConfig = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", intentParams)
        }

        // Disable keystroke output - we deliver via broadcast intent only.
        val keystrokeConfig = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "true")
            putBundle(
                "PARAM_LIST",
                Bundle().apply { putString("keystroke_output_enabled", "false") }
            )
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
