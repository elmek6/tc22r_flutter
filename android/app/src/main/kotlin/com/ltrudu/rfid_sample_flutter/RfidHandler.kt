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
            CoroutineScope(Dispatchers.IO).launch {
                // Always refresh the reader list to get a fresh reference
                if (readers != null) {
                    refreshAvailableReaders()
                }
                if (reader == null) getAvailableReader()
                val result = if (reader != null) connect() else "Failed to find reader"
                CoroutineScope(Dispatchers.Main).launch {
                    connectionInterface?.onReaderConnected(result)
                }
            }
        } else {
            connectionInterface?.onReaderConnected("Reader connected")
        }
    }
    
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

    fun isReaderConnected(): Boolean {
        val connected = reader?.isConnected == true
        if (!connected) Log.d(TAG, "reader is not connected")
        return connected
    }

    fun connect(): String {
        var lastException: Exception? = null
        var delay = INITIAL_RETRY_DELAY_MS
        
        for (attempt in 1..MAX_CONNECT_RETRIES) {
            try {
                // Refresh reader reference before each attempt
                if (reader == null) {
                    Log.d(TAG, "Refreshing reader reference for attempt $attempt")
                    refreshAvailableReaders()
                    if (availableRFIDReaderList?.isNotEmpty() == true) {
                        readerDevice = availableRFIDReaderList!!.firstOrNull { it.name.contains("TC22R") } 
                            ?: availableRFIDReaderList!![0]
                        reader = readerDevice?.getRFIDReader()
                    }
                }
                
                if (reader != null && !reader!!.isConnected) {
                    Log.d(TAG, "Connect attempt $attempt of $MAX_CONNECT_RETRIES")
                    reader!!.connect()
                    if (reader!!.isConnected) {
                        Log.d(TAG, "Connected successfully on attempt $attempt")
                        return "Connected: ${reader!!.hostName}"
                    }
                } else if (reader?.isConnected == true) {
                    return "Reader already connected"
                } else {
                    Log.d(TAG, "Reader is null, cannot connect")
                }
            } catch (e: InvalidUsageException) {
                lastException = e
                Log.w(TAG, "Connect attempt $attempt failed: InvalidUsageException - ${e.message}")
            } catch (e: OperationFailureException) {
                lastException = e
                Log.w(TAG, "Connect attempt $attempt failed: OperationFailureException - ${e.vendorMessage}")
            } catch (e: Exception) {
                // SDK can throw NullPointerException internally (e.g. RFIDHostEventAndReason null)
                // when the rfidhost service is not yet ready.
                lastException = e
                Log.w(TAG, "Connect attempt $attempt failed: ${e.javaClass.simpleName} - ${e.message}")
            }
            
            // Reset reader for next attempt
            reader = null
            
            // Exponential backoff before retry (except on last attempt)
            if (attempt < MAX_CONNECT_RETRIES) {
                Log.d(TAG, "Waiting ${delay}ms before retry...")
                Thread.sleep(delay)
                delay *= 2  // Exponential backoff
            }
        }
        
        // All retries exhausted - attempt SDK reset and one final retry
        val errorMsg = lastException?.let {
            when (it) {
                is OperationFailureException -> "Connection failed: ${it.vendorMessage} ${it.results}"
                is InvalidUsageException -> "Error: ${it.message}"
                else -> "Connection error: ${it.message}"
            }
        } ?: "Failed to connect after $MAX_CONNECT_RETRIES attempts"
        
        // Check if this is a timeout error that might be fixed by resetting the rfidhost service
        val isTimeoutError = lastException is OperationFailureException && 
            (lastException as OperationFailureException).vendorMessage.contains("timeout", ignoreCase = true)
        
        if (isTimeoutError) {
            Log.w(TAG, "Timeout detected - attempting rfidhost service reset...")
            return resetAndRetry() ?: errorMsg
        }
        
        Log.e(TAG, errorMsg)
        return errorMsg
    }
    
    // Resets the com.zebra.rfidhost service to clear stale SerialInputOutputManager state,
    // then creates a fresh Readers instance and retries the connection once.
    //
    // SOURCE: Stack Overflow - Zebra SDK exception RFID_API_COMMAND_TIMEOUT when reconnecting
    // https://stackoverflow.com/a/...
    // Fix requires: readers.Dispose() + set to null + wait 500ms + create new Readers()
    // This pattern clears the internal SerialInputOutputManager thread that gets stuck.
    private fun resetAndRetry(): String {
        try {
            context?.let { ctx ->
                // Step 1: Force-stop the rfidhost service to reset SDK internal state
                Log.d(TAG, "Force-stopping com.zebra.rfidhost service...")
                Runtime.getRuntime().exec("am force-stop com.zebra.rfidhost")
                Thread.sleep(2000)  // Wait for service to fully terminate
                
                // Step 2: Clear all reader references and dispose
                Log.d(TAG, "Clearing reader references...")
                try {
                    if (reader?.isConnected() == true) {
                        reader?.disconnect()
                    }
                } catch (e: Exception) { /* ignore */ }
                reader = null
                readerDevice = null
                
                try {
                    readers?.Dispose()
                } catch (e: Exception) { /* ignore */ }
                readers = null
                availableRFIDReaderList = null
                
                // CRITICAL: Wait for SDK to fully release native resources
                // Without this delay, the new Readers instance will have stale state
                Log.d(TAG, "Waiting 1000ms for SDK cleanup...")
                Thread.sleep(1000)
                
                // Step 3: Recreate Readers instance fresh
                Log.d(TAG, "Recreating Readers instance...")
                readers = Readers(ctx, ENUM_TRANSPORT.SERVICE_USB)
                val list = readers!!.GetAvailableRFIDReaderList()
                Log.d(TAG, "After reset - SERVICE_USB reader count: ${list.size}")
                
                if (list.isNotEmpty()) {
                    availableRFIDReaderList = list
                    readerDevice = list.firstOrNull { it.name.contains("TC22R") } ?: list[0]
                    reader = readerDevice!!.getRFIDReader()
                    
                    // Brief delay for service binding
                    Thread.sleep(SERVICE_READY_DELAY_MS)
                    
                    // Step 4: Single connection attempt on fresh state
                    Log.d(TAG, "Retry connection after reset...")
                    reader!!.connect()
                    if (reader!!.isConnected) {
                        Log.d(TAG, "Connected successfully after rfidhost reset!")
                        return "Connected after reset: ${reader!!.hostName}"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reset retry failed: ${e.javaClass.simpleName} - ${e.message}")
        }
        
        // If reset retry also failed, clear state and return original error
        try {
            if (reader?.isConnected() == true) {
                reader?.disconnect()
            }
            reader = null
            readers?.Dispose()
            readers = null
        } catch (e: Exception) { /* ignore */ }
        reader = null
        readers = null
        availableRFIDReaderList = null
        
        return "Connection failed (reset attempted): RFID_API_COMMAND_TIMEOUT"
    }

    fun disconnect(): String {
        Log.d(TAG, "Disconnect")
        return try {
            reader?.let {
                if (eventHandler != null) it.Events.removeEventsListener(eventHandler)
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

    // Configures the reader for RFID inventory mode.
    // Mirrors ConfigureReaderForInventory() in the original Java sample exactly:
    // trigger mode RFID, SESSION_S0, max power, no prefilters.
    fun configureReaderForInventory() {
        Log.d(TAG, "=== configureReaderForInventory called ===")
        if (reader?.isConnected != true) {
            Log.e(TAG, "configureReaderForInventory: reader not connected")
            return
        }
        Log.d(TAG, "ConfigureReaderForInventory ${reader!!.hostName}")

        val triggerInfo = TriggerInfo().apply {
            StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
        }
        try {
            // Re-register event listener to avoid duplicates
            if (eventHandler == null) {
                eventHandler = EventHandler()
                reader!!.Events.addEventsListener(eventHandler)
                Log.d(TAG, "Added event handler (was null)")
            } else {
                reader!!.Events.removeEventsListener(eventHandler)
                eventHandler = EventHandler()
                reader!!.Events.addEventsListener(eventHandler)
                Log.d(TAG, "Re-added event handler (was not null)")
            }

            reader!!.Events.setHandheldEvent(true)
            reader!!.Events.setTagReadEvent(true)
            reader!!.Events.setAttachTagDataWithReadEvent(false)
            Log.d(TAG, "Events configured - handheld=true, tagRead=true")

            // RFID_MODE prevents the barcode laser from firing during inventory
            reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, false)
            reader!!.Config.startTrigger = triggerInfo.StartTrigger
            reader!!.Config.stopTrigger = triggerInfo.StopTrigger
            Log.d(TAG, "Trigger mode set to RFID_MODE")

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
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun performInventory() {
        Log.d(TAG, "=== performInventory called ===")
        try {
            Log.d(TAG, "Calling configureReaderForInventory...")
            configureReaderForInventory()
            Log.d(TAG, "Calling reader.Actions.Inventory.perform...")
            reader?.Actions?.Inventory?.perform()
            Log.d(TAG, "performInventory completed successfully")
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
            when (rfidStatusEvents.StatusEventData.statusEventType) {
                STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                    val event = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent
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
