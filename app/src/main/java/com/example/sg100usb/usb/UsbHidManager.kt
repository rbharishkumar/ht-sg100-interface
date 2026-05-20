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
import android.os.SystemClock
import com.example.sg100usb.protocol.hex8
import com.example.sg100usb.protocol.hex16
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
            val message = if (detected.isEmpty()) {
                "No USB devices visible to Android. Check OTG cable, power, and Android USB permission."
            } else {
                "USB device visible, but no readable HID-style interface found. See Debug device list."
            }
            _state.update {
                it.copy(
                    message = message,
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
                    message = "Waiting for Android USB permission: ${candidate.reason}",
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
            message = "Connected ${device.vendorId.hex16()}:${device.productId.hex16()} ($reason)",
            detectedDevices = describeConnectedDevices(),
        )
    }

    @Synchronized
    fun exchange(packet: ByteArray, timeoutMs: Int = 10000): ByteArray {
        val conn = connection ?: error("USB HID is not connected")
        val input = inEndpoint ?: error("No HID IN endpoint")
        val out = outEndpoint ?: error("No HID OUT endpoint selected. Debug tab shows the detected interfaces.")
        val attempts = mutableListOf<String>()

        flushPendingReads(conn, input)
        val terminalStyleReport = outputReport(packet, includeReportId = true)
        val terminalStyleWritten = conn.bulkTransfer(out, terminalStyleReport, terminalStyleReport.size, WRITE_TIMEOUT_MS)
        attempts += "with report id wrote $terminalStyleWritten/${terminalStyleReport.size}"
        if (terminalStyleWritten > 0) {
            readHidResponse(conn, input, timeoutMs)?.let { return it }
        }

        flushPendingReads(conn, input)
        val directEndpointReport = outputReport(packet, includeReportId = false)
        val directWritten = conn.bulkTransfer(out, directEndpointReport, directEndpointReport.size, WRITE_TIMEOUT_MS)
        attempts += "without report id wrote $directWritten/${directEndpointReport.size}"
        if (directWritten > 0) {
            readHidResponse(conn, input, timeoutMs)?.let { return it }
        }

        error("No HID response after ${timeoutMs}ms (${attempts.joinToString("; ")})")
    }

    private fun readHidResponse(conn: UsbDeviceConnection, input: UsbEndpoint, timeoutMs: Int): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val buffer = ByteArray(HID_REPORT_LENGTH)
            val count = conn.bulkTransfer(input, buffer, buffer.size, READ_SLICE_TIMEOUT_MS)
            if (count > 0) return buffer.copyOf(count)
            Thread.sleep(READ_POLL_SLEEP_MS)
        }
        return null
    }

    @Synchronized
    fun close() {
        val conn = connection
        val intf = claimedInterface
        if (conn != null && intf != null) conn.releaseInterface(intf)
        conn?.close()
        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
        _state.value = UsbHidState(message = "Disconnected")
    }

    fun dispose() {
        close()
        runCatching { appContext.unregisterReceiver(receiver) }
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

    private fun flushPendingReads(conn: UsbDeviceConnection, input: UsbEndpoint): Int {
        var flushed = 0
        val deadline = SystemClock.elapsedRealtime() + FLUSH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val buffer = ByteArray(HID_REPORT_LENGTH)
            val count = conn.bulkTransfer(input, buffer, buffer.size, FLUSH_READ_TIMEOUT_MS)
            if (count <= 0) break
            flushed += 1
        }
        return flushed
    }

    private fun describeEndpoint(endpoint: UsbEndpoint?): String? =
        endpoint?.let { "${it.address.hex8()} max=${it.maxPacketSize}" }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.sg100usb.USB_PERMISSION"
        private const val HID_REPORT_LENGTH = 64
        private const val WRITE_TIMEOUT_MS = 1500
        private const val READ_SLICE_TIMEOUT_MS = 1
        private const val READ_POLL_SLEEP_MS = 10L
        private const val FLUSH_TIMEOUT_MS = 250L
        private const val FLUSH_READ_TIMEOUT_MS = 1
        private const val SG100_VENDOR_ID = 0x04D8
        private const val SG100_PRODUCT_ID = 0xF1BB
    }
}
