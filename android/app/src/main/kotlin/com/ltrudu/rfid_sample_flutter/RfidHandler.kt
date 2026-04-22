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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RfidHandler {

    companion object {
        const val TAG = "RFID_HANDLER"
        const val MAX_POWER = 270
        // Static so the UI can read/write the current antenna power index
        var mAntennaPower = MAX_POWER
        
        // Connection retry settings
        const val MAX_CONNECT_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 1000L
        const val SERVICE_READY_DELAY_MS = 1500L  // Increased from 500ms
    }

    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private var eventHandler: EventHandler? = null
    private var context: Context? = null

    // Prevents two simultaneous createInstance() coroutines (can happen when onCreate+onResume both call onResume)
    @Volatile private var isCreatingInstance = false

    // Keeps RFID connection alive when switching between screens (mirrors original keepConnexion flag)
    var keepConnection = false

    // Lock object for synchronizing RFID connection operations
    // Prevents concurrent access to SerialInputOutputManager
    private val connectionLock = Any()

    interface RfidHandlerInterface {
        fun onReaderConnected(message: String)
        fun onReaderDisconnected()
        fun onTagData(tagData: Array<TagData>)
        fun onMessage(message: String)
        fun handleTriggerPress(press: Boolean)
    }

    private var connectionInterface: RfidHandlerInterface? = null

    // Called once from MainActivity when the app is created
    fun onCreate(context: Context, iface: RfidHandlerInterface) {
        this.context = context
        this.connectionInterface = iface
    }

    // Called when the Flutter view becomes visible – mirrors Activity.onResume()
    fun onResume(iface: RfidHandlerInterface) {
        connectionInterface = iface
        if (readers == null) {
            if (isCreatingInstance) {
                Log.d(TAG, "createInstance already running, skipping duplicate call")
                return
            }
            // First call: enumerate transports in background, same order as original Java
            isCreatingInstance = true
            CoroutineScope(Dispatchers.IO).launch { createInstance() }
        } else {
            connectReader()
        }
    }

    fun onPause() {
        if (!keepConnection) disconnectReader()
        else connectionInterface?.onReaderDisconnected()
    }

    fun onDestroy() = dispose()

    // Enumerates SDK transports in the same priority order as the original Java sample
    private fun createInstance() {
        try {
            Log.d(TAG, "CreateInstanceTask")
            
            // NOTE: Unlike our initial approach, the original Java sample does NOT force-stop
            // Zebra services. The RFID SDK handles everything internally.
            // Removing force-stop logic as it may be causing connection timeouts.
            // If you experience issues, consider adding it back but test thoroughly.
            
            readers = Readers(context, ENUM_TRANSPORT.SERVICE_USB)
            var list = readers!!.GetAvailableRFIDReaderList()
            Log.d(TAG, "SERVICE_USB reader count: ${list.size}")

            if (list.isEmpty()) {
                Log.d(TAG, "Reader not available in SERVICE_USB, trying BLUETOOTH")
                readers!!.setTransport(ENUM_TRANSPORT.BLUETOOTH)
                list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "BLUETOOTH reader count: ${list.size}")
            }
            if (list.isEmpty()) {
                Log.d(TAG, "Reader not available in BLUETOOTH, trying SERVICE_SERIAL")
                readers!!.setTransport(ENUM_TRANSPORT.SERVICE_SERIAL)
                list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "SERVICE_SERIAL reader count: ${list.size}")
            }
            if (list.isEmpty()) {
                Log.d(TAG, "Reader not available in SERVICE_SERIAL, trying RE_SERIAL")
                readers!!.setTransport(ENUM_TRANSPORT.RE_SERIAL)
                list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "RE_SERIAL reader count: ${list.size}")
            }
            if (list.isEmpty()) {
                Log.d(TAG, "Reader not available in RE_SERIAL, trying ALL transports")
                readers!!.setTransport(ENUM_TRANSPORT.ALL)
                list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "ALL reader count: ${list.size}")
            }

            // Log found readers by name (mirrors original Java debug output)
            list.forEach { Log.d(TAG, "Found reader: ${it.name}") }

            availableRFIDReaderList = list

            // Brief delay so rfidhost service finishes binding before connect() is called.
            // Without this the SDK's RFIDHostEventAndReason can be null → NPE crash.
            // Increased from 500ms to 1500ms for more reliable service binding
            if (list.isNotEmpty()) Thread.sleep(SERVICE_READY_DELAY_MS)

            isCreatingInstance = false
            CoroutineScope(Dispatchers.Main).launch {
                if (list.isEmpty()) {
                    connectionInterface?.onMessage("No Available Readers to proceed")
                    readers = null
                } else {
                    connectReader()
                }
            }
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
            isCreatingInstance = false
            CoroutineScope(Dispatchers.Main).launch {
                connectionInterface?.onMessage("Failed to get Available Readers: ${e.info}")
                readers = null
            }
        }
    }

    @Synchronized
    private fun connectReader() {
        if (!isReaderConnected()) {
            // Use connectionLock to prevent concurrent access to SerialInputOutputManager
            CoroutineScope(Dispatchers.IO).launch {
                synchronized(connectionLock) {
                    // Check if readers is in a stale state by trying to get the list
                    var needsReset = false
                    if (readers != null) {
                        try {
                            refreshAvailableReaders()
                        } catch (e: Exception) {
                            Log.w(TAG, "refreshAvailableReaders failed: ${e.message} - needs reset")
                            needsReset = true
                        }
                    }
                    
                    if (needsReset || readers == null) {
                        // Reset the SDK state before trying to connect
                        Log.w(TAG, "SDK appears to be in stale state, triggering reset...")
                        val resetResult = resetAndRetry()
                        CoroutineScope(Dispatchers.Main).launch {
                            connectionInterface?.onReaderConnected(resetResult ?: "Reset failed")
                        }
                        return@launch
                    }
                    
                    if (reader == null) getAvailableReader()
                    var result = if (reader != null) connect() else "Failed to find reader"

                    // First attempt failed → auto-reset SDK state and retry once
                    if (!result.startsWith("Connected")) {
                        Log.w(TAG, "Initial connect failed ($result) — auto-resetting SDK...")
                        CoroutineScope(Dispatchers.Main).launch {
                            connectionInterface?.onMessage("Bağlantı başarısız, SDK sıfırlanıyor…")
                        }
                        result = resetAndRetry()
                    }

                    // Configure reader immediately after successful connection so
                    // performInventory() only needs to call perform() — no 12-call
                    // reconfiguration on each inventory start that causes timeouts.
                    if (result.startsWith("Connected")) {
                        configureReaderOnConnect()
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        connectionInterface?.onReaderConnected(result)
                    }
                }
            }
        } else {
            connectionInterface?.onReaderConnected("Reader connected")
        }
    }
    
    @Synchronized
    private fun refreshAvailableReaders() {
        try {
            readers?.let { r ->
                availableRFIDReaderList = r.GetAvailableRFIDReaderList()
                Log.d(TAG, "Refreshed reader list: ${availableRFIDReaderList?.size ?: 0} readers")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing reader list: ${e.message}")
        }
    }

    // Picks the single reader if available, otherwise searches for one containing "RFD90"
    private fun getAvailableReader() {
        synchronized(connectionLock) {
            readers?.let { r ->
                try {
                    Readers.attach(object : Readers.RFIDReaderEventHandler {
                        override fun RFIDReaderAppeared(device: ReaderDevice) {
                            connectionInterface?.onMessage("RFIDReaderAppeared")
                            connectReader()
                        }
                        override fun RFIDReaderDisappeared(device: ReaderDevice) {
                            connectionInterface?.onMessage("RFIDReaderDisappeared")
                            if (reader != null && device.name == reader!!.hostName) disconnect()
                        }
                    })
                    availableRFIDReaderList = r.GetAvailableRFIDReaderList()
                    val list = availableRFIDReaderList ?: return
                    if (list.isNotEmpty()) {
                        readerDevice = if (list.size == 1) {
                            list[0]
                        } else {
                            // Match by name prefix used in the original sample
                            list.find { it.name.contains("RFD90") } ?: list[0]
                        }
                        reader = readerDevice!!.getRFIDReader()
                    }
                } catch (e: InvalidUsageException) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun isReaderConnected(): Boolean {
        val connected = reader?.isConnected == true
        if (!connected) Log.d(TAG, "reader is not connected")
        return connected
    }

    fun connect(): String {
        // Use connectionLock to prevent concurrent access to SerialInputOutputManager
        // This prevents "Already running" IllegalStateException
        return synchronized(connectionLock) {
            try {
                if (reader != null && !reader!!.isConnected) {
                    Log.d(TAG, "Connecting to ${reader!!.hostName}")
                    reader!!.connect()
                    if (reader!!.isConnected) {
                        Log.d(TAG, "Connected successfully")
                        return "Connected: ${reader!!.hostName}"
                    }
                } else if (reader?.isConnected == true) {
                    return "Reader already connected"
                } else {
                    Log.d(TAG, "Reader is null, cannot connect")
                }
            } catch (e: InvalidUsageException) {
                Log.e(TAG, "InvalidUsageException: ${e.message}")
                return "Connection failed: ${e.message}"
            } catch (e: OperationFailureException) {
                Log.e(TAG, "OperationFailureException: ${e.vendorMessage}")
                return "Connection failed: ${e.vendorMessage} ${e.results}"
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                return "Connection error: ${e.message}"
            }
            return "Failed to connect"
        }
    }
    
    // Resets SDK state by disposing the stale Readers instance and recreating it,
    // then retries the connection once.
    //
    // SOURCE: Stack Overflow — Zebra SDK RFID_API_COMMAND_TIMEOUT on reconnect:
    // readers.Dispose() + null + sleep(1000) + new Readers() clears the stuck
    // SerialInputOutputManager thread inside rfidhost without needing force-stop.
    // NOTE: am force-stop is intentionally NOT used — it requires system permissions
    // and the research confirmed it was causing additional timeouts.
    @Synchronized
    private fun resetAndRetry(): String {
        try {
            context?.let { ctx ->
                Log.d(TAG, "SDK reset: disposing stale Readers instance...")

                // Disconnect and clear all references before Dispose
                try {
                    if (reader?.isConnected == true) reader?.disconnect()
                } catch (e: Exception) { /* ignore */ }
                reader = null
                readerDevice = null

                try {
                    readers?.Dispose()
                } catch (e: Exception) { /* ignore */ }
                readers = null
                availableRFIDReaderList = null

                // Wait for rfidhost to release SerialInputOutputManager resources
                Log.d(TAG, "Waiting 1500ms for SDK resource release...")
                Thread.sleep(1500)

                // Recreate Readers fresh — forces rfidhost to rebind cleanly
                Log.d(TAG, "Recreating Readers instance...")
                readers = Readers(ctx, ENUM_TRANSPORT.SERVICE_USB)
                Thread.sleep(1500)

                val list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "After reset — SERVICE_USB reader count: ${list.size}")

                if (list.isNotEmpty()) {
                    availableRFIDReaderList = list
                    readerDevice = list.firstOrNull { it.name.contains("TC22R") } ?: list[0]
                    reader = readerDevice!!.getRFIDReader()

                    Log.d(TAG, "Retry connection after reset...")
                    try {
                        reader!!.connect()
                        if (reader!!.isConnected) {
                            Log.d(TAG, "Connected successfully after SDK reset!")
                            configureReaderOnConnect()
                            return "Connected: ${reader!!.hostName}"
                        }
                    } catch (e: OperationFailureException) {
                        Log.w(TAG, "Reset connect failed: ${e.vendorMessage}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Reset connect failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resetAndRetry failed: ${e.javaClass.simpleName} — ${e.message}")
        }

        reader = null
        readers = null
        availableRFIDReaderList = null
        return "Bağlantı başarısız (SDK reset denendi). Cihazı yeniden başlatın."
    }

    fun disconnect(): String {
        Log.d(TAG, "Disconnect")
        return try {
            reader?.let {
                if (eventHandler != null) {
                    it.Events.removeEventsListener(eventHandler)
                    eventHandler = null
                }
                it.disconnect()
                connectionInterface?.onMessage("Disconnecting reader")
            }
            "Reader disconnected"
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
            e.message ?: "Disconnect error"
        } catch (e: OperationFailureException) {
            e.printStackTrace()
            e.message ?: "Disconnect error"
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "Disconnect error"
        }
    }

    @Synchronized
    private fun disconnectReader() {
        if (isReaderConnected()) {
            CoroutineScope(Dispatchers.IO).launch { disconnect() }
        }
    }

    private fun dispose() {
        disconnect()
        try {
            reader = null
            readers?.Dispose()
            readers = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Called once right after reader.connect() succeeds.
    // Sets up event listener, antenna config, and singulation — everything except
    // RFID_MODE which must be set/cleared around each individual inventory session.
    private fun configureReaderOnConnect() {
        Log.d(TAG, "=== configureReaderOnConnect called ===")
        if (reader?.isConnected != true) {
            Log.e(TAG, "configureReaderOnConnect: reader not connected")
            return
        }
        Log.d(TAG, "Configuring reader: ${reader!!.hostName}")
        val triggerInfo = TriggerInfo().apply {
            StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
        }
        try {
            // Register event handler once — stays registered for the life of the connection
            if (eventHandler == null) {
                eventHandler = EventHandler()
                reader!!.Events.addEventsListener(eventHandler)
                Log.d(TAG, "Event handler registered")
            }

            reader!!.Events.setHandheldEvent(true)
            reader!!.Events.setTagReadEvent(true)
            reader!!.Events.setAttachTagDataWithReadEvent(false)
            Log.d(TAG, "Events configured")

            reader!!.Config.setStartTrigger(triggerInfo.StartTrigger)
            reader!!.Config.setStopTrigger(triggerInfo.StopTrigger)

            // Power levels are index-based; use max supported by this reader
            mAntennaPower = reader!!.ReaderCapabilities.getTransmitPowerLevelValues().size - 1
            val config = reader!!.Config.Antennas.getAntennaRfConfig(1)
            config.transmitPowerIndex = mAntennaPower
            config.setrfModeTableIndex(0)
            config.tari = 0
            config.transmitFrequencyIndex = 0
            reader!!.Config.setUniqueTagReport(false)
            reader!!.Config.Antennas.setAntennaRfConfig(1, config)

            // SESSION_S0 reads all tags without state filtering
            val sc = reader!!.Config.Antennas.getSingulationControl(1)
            sc.setSession(SESSION.SESSION_S0)
            sc.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
            sc.Action.setSLFlag(SL_FLAG.SL_ALL)
            reader!!.Config.Antennas.setSingulationControl(1, sc)

            reader!!.Actions.PreFilters.deleteAll()
            Log.d(TAG, "Reader configuration complete")
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun performInventory() {
        Log.d(TAG, "=== performInventory called ===")
        if (!isReaderConnected()) {
            Log.e(TAG, "performInventory: reader not connected")
            CoroutineScope(Dispatchers.Main).launch {
                connectionInterface?.onMessage("RFID bağlı değil - önce bağlantı bekleniyor")
            }
            return
        }
        try {
            // RFID_MODE: second param false = do NOT suspend DataWedge.
            // Original Java sample uses false — matches TC22R behavior.
            reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, false)
            Log.d(TAG, "RFID_MODE set, starting inventory...")
            reader?.Actions?.Inventory?.perform()
            Log.d(TAG, "performInventory completed")
        } catch (e: InvalidUsageException) {
            Log.e(TAG, "performInventory: InvalidUsageException - ${e.message}")
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            Log.e(TAG, "performInventory: OperationFailureException - ${e.vendorMessage}")
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stopInventory() {
        try {
            reader?.Actions?.Inventory?.stop()
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    // Sets antenna transmit power by index (not dBm directly).
    // The reader exposes a discrete list of power levels; index 0 = min, max = highest supported.
    fun setAntennaPower(powerIndex: Int): String {
        if (!isReaderConnected()) return "Not connected"
        return try {
            val config = reader!!.Config.Antennas.getAntennaRfConfig(1)
            config.transmitPowerIndex = powerIndex
            config.setrfModeTableIndex(0)
            config.tari = 0
            reader!!.Config.Antennas.setAntennaRfConfig(1, config)
            mAntennaPower = powerIndex
            "Antenna power set to $powerIndex"
        } catch (e: OperationFailureException) {
            e.printStackTrace()
            "${e.results} ${e.vendorMessage}"
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
            e.message ?: "Error"
        }
    }

    fun getMaxPowerIndex(): Int {
        return try {
            if (isReaderConnected())
                reader!!.ReaderCapabilities.getTransmitPowerLevelValues().size - 1
            else MAX_POWER
        } catch (e: Exception) {
            MAX_POWER
        }
    }

    // Inner EventHandler mirrors the original Java EventHandler class:
    // reads tags on eventReadNotify, starts/stops inventory on trigger press/release.
    inner class EventHandler : RfidEventsListener {

        override fun eventReadNotify(e: RfidReadEvents) {
            Log.d(TAG, "=== eventReadNotify called ===")
            val tags = reader?.Actions?.getReadTags(100) ?: run {
                Log.e(TAG, "eventReadNotify: reader or Actions is null")
                return
            }
            Log.d(TAG, "eventReadNotify: retrieved ${tags.size} tags")
            CoroutineScope(Dispatchers.IO).launch {
                connectionInterface?.onTagData(tags)
            }
        }

        override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents) {
            Log.d(TAG, "Status Notification: ${rfidStatusEvents.StatusEventData.statusEventType}")
            val statusType = rfidStatusEvents.StatusEventData.statusEventType
            when (statusType) {
                STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                    // Use getter method to avoid NPE - HandheldTriggerEventData may be null in Kotlin
                    val handheldData = rfidStatusEvents.StatusEventData.HandheldTriggerEventData
                    if (handheldData != null) {
                        val event = handheldData.handheldEvent
                        CoroutineScope(Dispatchers.IO).launch {
                            when (event) {
                                HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED -> {
                                    Log.d(TAG, "HANDHELD_TRIGGER_PRESSED")
                                    connectionInterface?.handleTriggerPress(true)
                                }
                                HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED -> {
                                    Log.d(TAG, "HANDHELD_TRIGGER_RELEASED")
                                    connectionInterface?.handleTriggerPress(false)
                                }
                                else -> {}
                            }
                        }
                    } else {
                        Log.w(TAG, "HandheldTriggerEventData is null in eventStatusNotify")
                    }
                }
                STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        connectionInterface?.onReaderDisconnected()
                        disconnect()
                    }
                }
                else -> {}
            }
        }
    }
}
