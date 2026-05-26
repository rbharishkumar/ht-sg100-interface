package com.example.sg100usb.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.sg100usb.protocol.hex8
import com.example.sg100usb.protocol.hex16
import com.example.sg100usb.protocol.RegisterDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class UsbDeviceInfo(
    val name: String = "No SG-100 connected",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val interfaceId: Int = -1,
    val inEndpoint: String = "none",
    val outEndpoint: String = "none",
)

data class UsbHidState(
    val connected: Boolean = false,
    val permissionPending: Boolean = false,
    val deviceInfo: UsbDeviceInfo = UsbDeviceInfo(),
    val message: String = "Disconnected",
    val detectedDevices: List<String> = emptyList(),
)

private data class CandidateDevice(
    val device: UsbDevice,
    val reason: String,
)

private data class HidCommand(
    val packet: ByteArray,
    val timeoutMs: Int,
    val response: CompletableDeferred<ByteArray>,
)

class UsbHidManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(USB_SERVICE) as UsbManager
    private val _state = MutableStateFlow(UsbHidState())
    val state: StateFlow<UsbHidState> = _state.asStateFlow()

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var pendingDevice: UsbDevice? = null
    private var preferredReportIdShape: Boolean? = null
    private var rxJob: Job? = null
    private var txJob: Job? = null
    private var activeCommand: HidCommand? = null

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val priorityTxQueue = Channel<HidCommand>(TX_QUEUE_CAPACITY)
    private val pollingTxQueue = Channel<HidCommand>(TX_QUEUE_CAPACITY)
    private val rxQueue = Channel<ByteArray>(RX_QUEUE_CAPACITY)

    private val permissionIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    _state.update { it.copy(permissionPending = false) }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openDevice(device ?: pendingDevice)
                    } else {
                        _state.update { it.copy(message = "USB permission denied") }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectBestDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.deviceId == pendingDevice?.deviceId) close()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun connectBestDevice() {
        val detected = describeConnectedDevices()
        val candidate = findBestDevice()
        if (candidate == null) {
            _state.update {
                it.copy(
                    message = "Searching for SG100…",
                    detectedDevices = detected,
                )
            }
            return
        }
        val device = candidate.device
        pendingDevice = device
        if (!usbManager.hasPermission(device)) {
            _state.update {
                it.copy(
                    permissionPending = true,
                    message = "Tap OK to allow USB access",
                    detectedDevices = detected,
                )
            }
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        openDevice(device, candidate.reason)
    }

    @Synchronized
    private fun openDevice(device: UsbDevice?, reason: String = "manual open") {
        if (device == null) return
        close()
        val hidInterface = selectInterface(device)
        if (hidInterface == null) {
            _state.update { it.copy(message = "SG-100 found, but no HID interrupt interface found") }
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            _state.update { it.copy(message = "Could not open USB device") }
            return
        }
        if (!conn.claimInterface(hidInterface, true)) {
            conn.close()
            _state.update { it.copy(message = "Could not claim HID interface") }
            return
        }
        val endpoints = selectEndpoints(hidInterface)
        if (endpoints.first == null) {
            conn.releaseInterface(hidInterface)
            conn.close()
            _state.update { it.copy(message = "HID IN interrupt endpoint not found") }
            return
        }
        connection = conn
        claimedInterface = hidInterface
        inEndpoint = endpoints.first
        outEndpoint = endpoints.second
        startWorkers(conn, endpoints.first, endpoints.second)
        _state.value = UsbHidState(
            connected = true,
            deviceInfo = UsbDeviceInfo(
                name = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                interfaceId = hidInterface.id,
                inEndpoint = describeEndpoint(endpoints.first) ?: "none",
                outEndpoint = describeEndpoint(endpoints.second) ?: "control SET_REPORT",
            ),
            message = "Connected to SG100",
            detectedDevices = describeConnectedDevices(),
        )
    }

    /**
     * Exclusive write path for FC06 holding-register writes.
     *
     * Stops the background RX/TX workers so no polling FC04 traffic can land in the
     * receive buffer during the write window. Sends the packet directly via bulkTransfer
     * and reads the echo with a tight polling loop. Workers are always restarted in the
     * finally block so the telemetry path is never permanently disrupted.
     */
    suspend fun exclusiveWrite(packet: ByteArray, timeoutMs: Int = 500): ByteArray {
        val conn = connection ?: error("USB HID is not connected")
        val inp  = inEndpoint  ?: error("No IN endpoint")
        val out  = outEndpoint ?: error("No OUT endpoint")

        // Stop workers and drain every queue/buffer so the bus is silent.
        val txToCancel = txJob
        val rxToCancel = rxJob
        txJob = null
        rxJob = null
        activeCommand?.response?.completeExceptionally(
            IllegalStateException("Interrupted by exclusive write")
        )
        activeCommand = null
        drainTxQueue(priorityTxQueue)
        drainTxQueue(pollingTxQueue)
        txToCancel?.cancelAndJoin()
        rxToCancel?.cancelAndJoin()
        drainRxQueue()

        return withContext(Dispatchers.IO) {
            try {
                val ifaceId = claimedInterface?.id ?: 0
                // HID SET_REPORT: report ID goes in wValue low byte, NOT in the data buffer.
                // Using outputReport(false) sends the raw 64-byte Modbus frame without a
                // 0x00 prefix, which matches what HidD_SetOutputReport actually puts on the wire.
                val ctrlData = outputReport(packet, false)
                val ctrlWritten = conn.controlTransfer(
                    0x21, 0x09, 0x0200, ifaceId,
                    ctrlData, ctrlData.size, WRITE_TIMEOUT_MS,
                )
                val usedCtrl = ctrlWritten >= 0
                if (!usedCtrl) {
                    // Fall back: interrupt/bulk OUT endpoint (original path).
                    val shape  = preferredReportIdShape ?: true
                    val report = outputReport(packet, shape)
                    val bulkWritten = conn.bulkTransfer(out, report, report.size, WRITE_TIMEOUT_MS)
                    if (bulkWritten <= 0) error("HID write failed: ctrl=$ctrlWritten bulk=$bulkWritten")
                }

                // Read echo via interrupt IN endpoint regardless of write path.
                val deadline = System.currentTimeMillis() + timeoutMs
                var response: ByteArray? = null
                while (System.currentTimeMillis() < deadline && response == null) {
                    val buf   = ByteArray(HID_REPORT_LENGTH)
                    val waitMs = (deadline - System.currentTimeMillis())
                        .coerceIn(1L, 50L).toInt()
                    val count = conn.bulkTransfer(inp, buf, buf.size, waitMs)
                    if (count > 0) {
                        response = RegisterDecoder.extractFrame(buf.copyOf(count))
                    }
                }
                response ?: error("No HID response within ${timeoutMs}ms (ctrl=${if (usedCtrl) "ok" else "fail"})")
            } finally {
                startWorkers(conn, inp, out)
            }
        }
    }

    suspend fun exchange(
        packet: ByteArray,
        timeoutMs: Int = RESPONSE_TIMEOUT_MS,
        priority: Boolean = false,
    ): ByteArray {
        if (connection == null || inEndpoint == null || outEndpoint == null) {
            error("USB HID is not connected")
        }
        val command = HidCommand(packet, timeoutMs, CompletableDeferred())
        val result = if (priority) {
            priorityTxQueue.trySend(command)
        } else {
            pollingTxQueue.trySend(command)
        }
        if (result.isFailure) error("USB command queue is full")
        return command.response.await()
    }

    @Synchronized
    fun close() {
        stopWorkers()
        val conn = connection
        val intf = claimedInterface
        if (conn != null && intf != null) conn.releaseInterface(intf)
        conn?.close()
        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
        preferredReportIdShape = null
        _state.value = UsbHidState(message = "SG100 disconnected")
    }

    fun dispose() {
        close()
        workerScope.coroutineContext[Job]?.cancel()
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun startWorkers(conn: UsbDeviceConnection, input: UsbEndpoint?, out: UsbEndpoint?) {
        if (input == null || out == null) return
        stopWorkers()
        drainRxQueue()
        rxJob = workerScope.launch {
            while (isActive) {
                val buffer = ByteArray(HID_REPORT_LENGTH)
                val count = conn.bulkTransfer(input, buffer, buffer.size, RX_READ_TIMEOUT_MS)
                if (count > 0) {
                    rxQueue.trySend(buffer.copyOf(count))
                }
            }
        }
        txJob = workerScope.launch {
            while (isActive) {
                val command = nextCommand()
                processCommand(conn, out, command)
            }
        }
    }

    private fun stopWorkers() {
        val jobs = listOfNotNull(rxJob, txJob)
        rxJob = null
        txJob = null
        if (jobs.isNotEmpty()) {
            runBlocking {
                jobs.forEach { it.cancelAndJoin() }
            }
        }
        activeCommand?.response?.completeExceptionally(IllegalStateException("USB connection closed"))
        activeCommand = null
        drainTxQueue(priorityTxQueue)
        drainTxQueue(pollingTxQueue)
        drainRxQueue()
    }

    private suspend fun nextCommand(): HidCommand {
        priorityTxQueue.tryReceive().getOrNull()?.let { return it }
        return select {
            priorityTxQueue.onReceive { it }
            pollingTxQueue.onReceive { it }
        }
    }

    private suspend fun processCommand(conn: UsbDeviceConnection, out: UsbEndpoint, command: HidCommand) {
        activeCommand = command
        try {
            val attempts = mutableListOf<String>()
            val reportShapes = preferredReportIdShape
                ?.let { listOf(it, !it) }
                ?: listOf(true, false)

            drainRxQueue()
            for (includeReportId in reportShapes) {
                val report = outputReport(command.packet, includeReportId)
                val written = conn.bulkTransfer(out, report, report.size, WRITE_TIMEOUT_MS)
                attempts += "${if (includeReportId) "with" else "without"} report id wrote $written/${report.size}"
                if (written <= 0) continue

                val response = waitForResponse(command.timeoutMs)
                if (response != null) {
                    preferredReportIdShape = includeReportId
                    command.response.complete(response)
                    return
                }
            }

            command.response.completeExceptionally(
                IllegalStateException("No HID response after ${command.timeoutMs}ms (${attempts.joinToString("; ")})")
            )
        } finally {
            if (!command.response.isCompleted) {
                command.response.completeExceptionally(IllegalStateException("USB command cancelled"))
            }
            if (activeCommand == command) activeCommand = null
        }
    }

    private suspend fun waitForResponse(timeoutMs: Int): ByteArray? {
        // Accumulate HID reports in case the Modbus frame spans more than one 64-byte report.
        // A 31-register holding response is 67 bytes, which overflows one report by 3 bytes.
        val modbusBuffer = mutableListOf<Byte>()
        var payloadOffset = -1  // detected from first report: 1 if report-ID byte present, else 0
        var validResponse: ByteArray? = null
        withTimeoutOrNull(timeoutMs.toLong()) {
            while (validResponse == null) {
                val report = rxQueue.receive()
                // Always try the single-report path first (handles the common 27-register case).
                if (modbusBuffer.isEmpty()) {
                    val frame = RegisterDecoder.extractFrame(report)
                    if (frame != null) {
                        validResponse = frame
                        return@withTimeoutOrNull
                    }
                }
                // Frame is not complete in this report — start/continue multi-report assembly.
                if (payloadOffset == -1) {
                    payloadOffset = if (report.isNotEmpty() && report[0] == 0.toByte()) 1 else 0
                }
                for (i in payloadOffset until report.size) modbusBuffer.add(report[i])
                if (modbusBuffer.size > MAX_MULTI_REPORT_BYTES) break
                val frame = RegisterDecoder.extractFrame(modbusBuffer.toByteArray())
                if (frame != null) validResponse = frame
            }
        }
        return validResponse
    }

    private fun drainRxQueue() {
        while (rxQueue.tryReceive().isSuccess) {
            // Keep only responses that arrive after the current TX command.
        }
    }

    private fun drainTxQueue(queue: Channel<HidCommand>) {
        while (true) {
            val command = queue.tryReceive().getOrNull() ?: return
            command.response.completeExceptionally(IllegalStateException("USB connection closed"))
        }
    }

    private fun findBestDevice(): CandidateDevice? {
        val devices = usbManager.deviceList.values
        fun usable(device: UsbDevice): Boolean = selectInterface(device) != null

        return devices.firstOrNull { device ->
            device.vendorId == SG100_VENDOR_ID &&
                device.productId == SG100_PRODUCT_ID &&
                usable(device)
        }?.let { CandidateDevice(it, "exact SG-100 VID/PID") }
            ?: devices.firstOrNull { device ->
                device.vendorId == SG100_VENDOR_ID && usable(device)
            }?.let { CandidateDevice(it, "Microchip/SG-100 vendor match, alternate PID ${it.productId.hex16()}") }
            ?: devices.firstOrNull { device ->
                usable(device) && device.deviceName.contains("usb", ignoreCase = true)
            }?.let { CandidateDevice(it, "first compatible USB HID-style device") }
            ?: devices.firstOrNull { usable(it) }?.let { CandidateDevice(it, "first compatible interface") }
    }

    private fun describeConnectedDevices(): List<String> =
        usbManager.deviceList.values.map { device ->
            val classes = (0 until device.interfaceCount).joinToString("/") { index ->
                val intf = device.getInterface(index)
                val endpoints = (0 until intf.endpointCount).joinToString(",") { endpointIndex ->
                    val endpoint = intf.getEndpoint(endpointIndex)
                    val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    "$direction:${endpoint.address.hex8()}:max=${endpoint.maxPacketSize}"
                }
                "if$index:id=${intf.id},class=${intf.interfaceClass},sub=${intf.interfaceSubclass},proto=${intf.interfaceProtocol},eps=[$endpoints]"
            }
            "${device.deviceName} VID=${device.vendorId.hex16()} PID=${device.productId.hex16()} $classes"
        }

    private fun selectInterface(device: UsbDevice): UsbInterface? {
        var anyWithInOut: UsbInterface? = null
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            val endpoints = selectEndpoints(usbInterface)
            val hasInput = endpoints.first != null
            val hasOutput = endpoints.second != null
            val isHid = usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID
            if (isHid && hasInput && hasOutput) {
                return usbInterface
            }
            if (anyWithInOut == null && hasInput && hasOutput) {
                anyWithInOut = usbInterface
            }
        }
        return anyWithInOut
    }

    private fun selectEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT && endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
        }
        return input to output
    }

    private fun outputReport(packet: ByteArray, includeReportId: Boolean): ByteArray {
        /*
         * SG-100 HID writes match the verified Python terminal:
         * byte 0 is HID report id 0x00, followed by the Modbus RTU frame,
         * then zero padding to the fixed 64-byte HID report size.
         *
         * Android bulkTransfer writes directly to the endpoint, while hidapi
         * treats byte 0 as a report id. Try both shapes so Android can match
         * the controller's actual endpoint expectation.
         */
        val prefixSize = if (includeReportId) 1 else 0
        require(packet.size + prefixSize <= HID_REPORT_LENGTH) { "Packet is larger than HID report size" }
        return ByteArray(HID_REPORT_LENGTH).also { report ->
            if (includeReportId) report[0] = 0x00
            packet.copyInto(report, destinationOffset = prefixSize)
        }
    }

    private fun describeEndpoint(endpoint: UsbEndpoint?): String? =
        endpoint?.let { "${it.address.hex8()} max=${it.maxPacketSize}" }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.sg100usb.USB_PERMISSION"
        private const val HID_REPORT_LENGTH = 64
        private const val TX_QUEUE_CAPACITY = 32
        private const val RX_QUEUE_CAPACITY = 64
        private const val WRITE_TIMEOUT_MS = 250
        private const val RESPONSE_TIMEOUT_MS = 300
        private const val RX_READ_TIMEOUT_MS = 20
        private const val MAX_MULTI_REPORT_BYTES = 192  // 3 × 64-byte reports max
        private const val SG100_VENDOR_ID = 0x04D8
        private const val SG100_PRODUCT_ID = 0xF1BB
    }
}
