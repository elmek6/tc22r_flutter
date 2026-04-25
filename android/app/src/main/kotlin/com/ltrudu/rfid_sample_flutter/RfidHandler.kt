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
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.Executors

/**
 * Native RFID handler for Zebra TC22R using RFID API3 SDK3.
 *
 * Adapted from the official ZebraDevs/RFID-Android-Inventory-Sample
 * (RFIDHandler.java) into Kotlin and integrated with a Flutter EventChannel
 * sink instead of the original AsyncTask + Activity callbacks.
 *
 * Lifecycle:
 *   initialize() -> Readers(SERVICE_USB), attach listener, connect first reader
 *   startInventory() / stopInventory() -> Actions.Inventory.perform/stop
 *   setPower(idx) -> Antennas.AntennaRfConfig.setTransmitPowerIndex
 *   dispose() -> remove listener, disconnect, Readers.Dispose()
 *
 * Events emitted to Flutter via the supplied EventSink:
 *   {type:"rfidConnected",   message: String}
 *   {type:"rfidDisconnected"}
 *   {type:"rfidError",       message: String}
 *   {type:"tagData",         tags: List<{epc,rssi}>}
 *   {type:"triggerPress",    pressed: Boolean}
 *   {type:"message",         message: String}
 */
class RfidHandler(
    private val context: Context,
    private val sink: EventChannel.EventSink
) : Readers.RFIDReaderEventHandler {

    private val tag = "RfidHandler"
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var readers: Readers? = null
    @Volatile private var reader: RFIDReader? = null
    @Volatile private var device: ReaderDevice? = null

    private var maxPowerIndexValue: Int = 270
    private var eventsListener: InventoryEventsListener? = null
    private val connectionLock = Any()

    fun initialize() {
        ioExecutor.execute {
            try {
                createReaders()
                discoverAndConnect()
            } catch (e: Exception) {
                Log.e(tag, "initialize failed", e)
                emitError("init: " + e.message)
            }
        }
    }

    private fun createReaders() {
        if (readers == null) {
            // TC22R integrated reader is exposed over USB service transport.
            // Reference: ZebraDevs/RFID-Android-Inventory-Sample (CreateInstanceTask).
            readers = Readers(context, ENUM_TRANSPORT.SERVICE_USB)
            Readers.attach(this)
        }
    }

    private fun discoverAndConnect() {
        synchronized(connectionLock) {
            val r = readers ?: return
            val list = try {
                r.GetAvailableRFIDReaderList()
            } catch (e: InvalidUsageException) {
                Log.e(tag, "GetAvailableRFIDReaderList", e)
                emitError("Cannot enumerate readers: " + e.message)
                return
            }
            if (list.isNullOrEmpty()) {
                emitMessage("No RFID readers available")
                return
            }
            device = list[0]
            reader = device?.rfidReader
            connect()
        }
    }

    private fun connect() {
        val r = reader ?: return
        try {
            if (!r.isConnected) {
                r.connect()
                configureReader()
                emit(mapOf("type" to "rfidConnected", "message" to "Connected: " + r.hostName))
            }
        } catch (e: InvalidUsageException) {
            Log.e(tag, "connect InvalidUsage", e)
            emitError("connect: " + e.message)
        } catch (e: OperationFailureException) {
            Log.e(tag, "connect OperationFailure", e)
            emitError("connect: " + e.results.toString() + " / " + e.vendorMessage)
        }
    }

    private fun configureReader() {
        val r = reader ?: return
        if (!r.isConnected) return

        // Trigger info: immediate start, stop on Inventory.stop()
        val triggerInfo = TriggerInfo()
        triggerInfo.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
        triggerInfo.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE

        if (eventsListener == null) eventsListener = InventoryEventsListener()
        r.Events.addEventsListener(eventsListener)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(false)

        // RFID_MODE so the integrated barcode laser does not fire on trigger.
        r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
        r.Config.setStartTrigger(triggerInfo.StartTrigger)
        r.Config.setStopTrigger(triggerInfo.StopTrigger)

        // Power table is index based - last index = strongest power.
        maxPowerIndexValue = r.ReaderCapabilities.transmitPowerLevelValues.size - 1

        // Singulation: session S1, state A, SL all - matches Zebra reference sample.
        val singulation = r.Config.Antennas.getSingulationControl(1)
        singulation.session = SESSION.SESSION_S1
        singulation.Action.inventoryState = INVENTORY_STATE.INVENTORY_STATE_A
        singulation.Action.slFlag = SL_FLAG.SL_ALL
        r.Config.Antennas.setSingulationControl(1, singulation)

        // Clear any leftover prefilters
        r.Actions.PreFilters.deleteAll()
    }

    fun startInventory() {
        ioExecutor.execute {
            val r = reader ?: return@execute
            try {
                if (!r.isConnected) {
                    emitError("Reader not connected")
                    return@execute
                }
                r.Actions.Inventory.perform()
            } catch (e: Exception) {
                Log.e(tag, "startInventory", e)
                emitError("startInventory: " + e.message)
            }
        }
    }

    fun stopInventory() {
        ioExecutor.execute {
            val r = reader ?: return@execute
            try {
                if (r.isConnected) r.Actions.Inventory.stop()
            } catch (e: Exception) {
                Log.e(tag, "stopInventory", e)
                emitError("stopInventory: " + e.message)
            }
        }
    }

    fun setPower(powerIndex: Int) {
        ioExecutor.execute {
            val r = reader ?: return@execute
            try {
                val cfg = r.Config.Antennas.getAntennaRfConfig(1)
                cfg.transmitPowerIndex = powerIndex
                r.Config.Antennas.setAntennaRfConfig(1, cfg)
            } catch (e: Exception) {
                Log.e(tag, "setPower", e)
                emitError("setPower: " + e.message)
            }
        }
    }

    fun maxPowerIndex(): Int = maxPowerIndexValue

    fun isConnected(): Boolean = reader?.isConnected == true

    fun dispose() {
        ioExecutor.execute {
            try {
                val r = reader
                if (r != null) {
                    eventsListener?.let { r.Events.removeEventsListener(it) }
                    if (r.isConnected) r.disconnect()
                }
                readers?.Dispose()
            } catch (e: Exception) {
                Log.e(tag, "dispose", e)
            } finally {
                reader = null
                device = null
                readers = null
                emit(mapOf("type" to "rfidDisconnected"))
            }
        }
        ioExecutor.shutdown()
    }

    // --- Readers.RFIDReaderEventHandler ---

    override fun RFIDReaderAppeared(rd: ReaderDevice) {
        Log.d(tag, "RFIDReaderAppeared: " + rd.name)
        ioExecutor.execute { discoverAndConnect() }
    }

    override fun RFIDReaderDisappeared(rd: ReaderDevice) {
        Log.d(tag, "RFIDReaderDisappeared: " + rd.name)
        if (rd.name == reader?.hostName) {
            try { reader?.disconnect() } catch (_: Exception) {}
            emit(mapOf("type" to "rfidDisconnected"))
        }
    }

    // --- Tag + status callbacks ---

    private inner class InventoryEventsListener : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents) {
            val r = reader ?: return
            // Pull tags out of the SDK queue. 100 is the conventional batch size
            // used in the official Zebra inventory sample.
            val read: Array<TagData>? = try {
                r.Actions.getReadTags(100)
            } catch (ex: Exception) {
                Log.e(tag, "getReadTags", ex)
                null
            }
            if (read != null && read.isNotEmpty()) {
                val tags = read.map {
                    mapOf(
                        "epc" to (it.tagID ?: ""),
                        "rssi" to it.peakRSSI.toString()
                    )
                }
                emit(mapOf("type" to "tagData", "tags" to tags))
            }
        }

        override fun eventStatusNotify(e: RfidStatusEvents) {
            val type = e.StatusEventData.statusEventType
            if (type == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                val hh = e.StatusEventData.HandheldTriggerEventData.handheldEvent
                when (hh) {
                    HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED ->
                        emit(mapOf("type" to "triggerPress", "pressed" to true))
                    HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED ->
                        emit(mapOf("type" to "triggerPress", "pressed" to false))
                    else -> {}
                }
            } else if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                emit(mapOf("type" to "rfidDisconnected"))
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
                emitMessage("Inventory started")
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
                emitMessage("Inventory stopped")
            }
        }
    }

    // --- Helpers ---

    private fun emit(map: Map<String, Any?>) {
        // EventSink is the MainThreadEventSink from MainActivity, so it is
        // already safe to invoke from the RFID reader thread.
        try { sink.success(map) } catch (e: Exception) { Log.e(tag, "emit", e) }
    }

    private fun emitError(message: String) {
        emit(mapOf("type" to "rfidError", "message" to message))
    }

    private fun emitMessage(message: String) {
        emit(mapOf("type" to "message", "message" to message))
    }
}
