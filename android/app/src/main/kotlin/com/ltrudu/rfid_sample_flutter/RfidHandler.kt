package com.ltrudu.rfid_sample_flutter

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.INVENTORY_STATE
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.SESSION
import com.zebra.rfid.api3.SL_FLAG
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import com.zebra.rfid.api3.TagData
import com.zebra.rfid.api3.TriggerInfo
import java.util.concurrent.Executors

class RfidHandler(
    private val context: Context,
    private val onEvent: (Map<String, Any?>) -> Unit,
    private val onTrigger: ((pressed: Boolean) -> Unit)? = null
) : Readers.RFIDReaderEventHandler {

    private val TAG = "RfidHandler"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var readers: Readers? = null
    @Volatile private var reader: RFIDReader? = null
    @Volatile private var device: ReaderDevice? = null

    private var maxPowerIdx = 270
    private var eventsListener: InventoryEventsListener? = null

    fun connect() {
        executor.execute {
            try {
                if (readers == null) {
                    readers = Readers(context, ENUM_TRANSPORT.SERVICE_USB)
                    Readers.attach(this)
                }
                val list = try {
                    readers!!.GetAvailableRFIDReaderList()
                } catch (e: InvalidUsageException) {
                    onEvent(mapOf("type" to "rfidError", "message" to "Enumerate: ${e.message}"))
                    return@execute
                }
                if (list.isNullOrEmpty()) {
                    onEvent(mapOf("type" to "message", "message" to "No RFID reader found"))
                    return@execute
                }
                device = list[0]
                reader = device?.rfidReader
                connectReader()
            } catch (e: Exception) {
                Log.e(TAG, "connect", e)
                onEvent(mapOf("type" to "rfidError", "message" to "connect: ${e.message}"))
            }
        }
    }

    private fun connectReader() {
        val r = reader ?: return
        try {
            if (!r.isConnected) {
                r.connect()
                configureReader()
                onEvent(mapOf("type" to "rfidConnected", "message" to "Connected: ${r.hostName}"))
            }
        } catch (e: InvalidUsageException) {
            onEvent(mapOf("type" to "rfidError", "message" to "connect: ${e.message}"))
        } catch (e: OperationFailureException) {
            onEvent(mapOf("type" to "rfidError", "message" to "connect: ${e.results} / ${e.vendorMessage}"))
        }
    }

    private fun configureReader() {
        val r = reader ?: return
        if (!r.isConnected) return

        val triggerInfo = TriggerInfo()
        triggerInfo.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
        triggerInfo.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE

        if (eventsListener == null) eventsListener = InventoryEventsListener()
        r.Events.addEventsListener(eventsListener)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(false)

        // RFID_MODE: RFID SDK owns the handheld trigger (lower button on TC22R).
        // DataWedge keeps the upper (barcode) trigger.
        r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
        r.Config.setStartTrigger(triggerInfo.StartTrigger)
        r.Config.setStopTrigger(triggerInfo.StopTrigger)

        maxPowerIdx = r.ReaderCapabilities.transmitPowerLevelValues.size - 1

        // Set antenna to maximum transmit power — default index 0 is near-zero power and reads nothing.
        try {
            val antennaRfConfig = r.Config.Antennas.getAntennaRfConfig(1)
            antennaRfConfig.transmitPowerIndex = maxPowerIdx
            r.Config.Antennas.setAntennaRfConfig(1, antennaRfConfig)
        } catch (e: Exception) {
            Log.w(TAG, "setAntennaRfConfig failed: ${e.message}")
        }

        val singulation = r.Config.Antennas.getSingulationControl(1)
        singulation.session = SESSION.SESSION_S1
        singulation.Action.inventoryState = INVENTORY_STATE.INVENTORY_STATE_A
        singulation.Action.slFlag = SL_FLAG.SL_ALL
        r.Config.Antennas.setSingulationControl(1, singulation)

        r.Actions.PreFilters.deleteAll()
    }

    fun startInventory() {
        executor.execute {
            val r = reader ?: return@execute
            try {
                if (r.isConnected) r.Actions.Inventory.perform()
            } catch (e: Exception) {
                Log.e(TAG, "startInventory", e)
                onEvent(mapOf("type" to "rfidError", "message" to "startInventory: ${e.message}"))
            }
        }
    }

    fun stopInventory() {
        executor.execute {
            val r = reader ?: return@execute
            try {
                if (r.isConnected) r.Actions.Inventory.stop()
            } catch (e: Exception) {
                Log.e(TAG, "stopInventory", e)
            }
        }
    }

    fun isConnected(): Boolean = reader?.isConnected == true

    fun getMaxPowerIndex(): Int = maxPowerIdx

    fun dispose() {
        executor.execute {
            try {
                val r = reader
                if (r != null) {
                    eventsListener?.let { r.Events.removeEventsListener(it) }
                    if (r.isConnected) r.disconnect()
                }
                readers?.Dispose()
            } catch (e: Exception) {
                Log.e(TAG, "dispose", e)
            } finally {
                reader = null
                device = null
                readers = null
                onEvent(mapOf("type" to "rfidDisconnected"))
            }
        }
        executor.shutdown()
    }

    // --- Readers.RFIDReaderEventHandler ---

    override fun RFIDReaderAppeared(rd: ReaderDevice) {
        Log.d(TAG, "RFIDReaderAppeared: ${rd.name}")
        connect()
    }

    override fun RFIDReaderDisappeared(rd: ReaderDevice) {
        Log.d(TAG, "RFIDReaderDisappeared: ${rd.name}")
        if (rd.name == reader?.hostName) {
            try { reader?.disconnect() } catch (_: Exception) {}
            onEvent(mapOf("type" to "rfidDisconnected"))
        }
    }

    // --- Tag + status callbacks ---

    private inner class InventoryEventsListener : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents) {
            val r = reader ?: return
            val tags: Array<TagData>? = try {
                r.Actions.getReadTags(100)
            } catch (ex: Exception) {
                Log.e(TAG, "getReadTags", ex)
                null
            }
            if (!tags.isNullOrEmpty()) {
                val list = tags.map {
                    mapOf("epc" to (it.tagID ?: ""), "rssi" to it.peakRSSI.toString())
                }
                onEvent(mapOf("type" to "tagData", "tags" to list))
            }
        }

        override fun eventStatusNotify(e: RfidStatusEvents) {
            val t = e.StatusEventData.statusEventType
            when (t) {
                STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                    val hh = e.StatusEventData.HandheldTriggerEventData.handheldEvent
                    when (hh) {
                        HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED -> {
                            onTrigger?.invoke(true)
                            startInventory()
                            onEvent(mapOf("type" to "triggerPress", "pressed" to true))
                        }
                        HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED -> {
                            stopInventory()
                            onTrigger?.invoke(false)
                            onEvent(mapOf("type" to "triggerPress", "pressed" to false))
                        }
                        else -> {}
                    }
                }
                STATUS_EVENT_TYPE.DISCONNECTION_EVENT ->
                    onEvent(mapOf("type" to "rfidDisconnected"))
                STATUS_EVENT_TYPE.INVENTORY_START_EVENT ->
                    onEvent(mapOf("type" to "message", "message" to "Inventory started"))
                STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT ->
                    onEvent(mapOf("type" to "message", "message" to "Inventory stopped"))
                else -> {}
            }
        }
    }
}
