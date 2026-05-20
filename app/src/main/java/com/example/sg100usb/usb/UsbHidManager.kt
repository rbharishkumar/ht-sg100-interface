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
        val device = findBestDevice()
        if (device == null) {
            _state.update {
                it.copy(
                    message = "No SG-100 HID device found for VID 04D8 PID F1BB",
                    detectedDevices = detected,
                )
            }
            return
        }
        pendingDevice = device
        if (!usbManager.hasPermission(device)) {
            _state.update {
                it.copy(
                    permissionPending = true,
                    message = "Waiting for Android USB permission",
                    detectedDevices = detected,
                )
            }
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        openDevice(device)
    }

    @Synchronized
    private fun openDevice(device: UsbDevice?) {
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
        if (endpoints.first == null || endpoints.second == null) {
            conn.releaseInterface(hidInterface)
            conn.close()
            _state.update { it.copy(message = "HID IN/OUT interrupt endpoints not found") }
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
                inEndpoint = describeEndpoint(endpoints.first),
                outEndpoint = describeEndpoint(endpoints.second),
            ),
            message = "Connected ${device.vendorId.hex16()}:${device.productId.hex16()}",
            detectedDevices = describeConnectedDevices(),
        )
    }

    @Synchronized
    fun exchange(packet: ByteArray, timeoutMs: Int = 3000): ByteArray {
        val conn = connection ?: error("USB HID is not connected")
        val out = outEndpoint ?: error("No HID OUT endpoint")
        val input = inEndpoint ?: error("No HID IN endpoint")
        flushPendingReads(conn, input)
        val report = outputReport(packet)
        val written = conn.bulkTransfer(out, report, report.size, WRITE_TIMEOUT_MS)
        require(written > 0) { "HID write failed: $written" }
        val buffer = ByteArray(HID_REPORT_LENGTH)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = conn.bulkTransfer(input, buffer, buffer.size, READ_SLICE_TIMEOUT_MS)
            if (count > 0) return buffer.copyOf(count)
            Thread.sleep(10)
        }
        error("No response from controller after ${timeoutMs}ms")
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

    private fun findBestDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull { device ->
            device.vendorId == SG100_VENDOR_ID &&
                device.productId == SG100_PRODUCT_ID &&
                selectInterface(device) != null
        } ?: devices.firstOrNull { device ->
            device.vendorId == SG100_VENDOR_ID &&
                selectInterface(device) != null
        }
    }

    private fun describeConnectedDevices(): List<String> =
        usbManager.deviceList.values.map { device ->
            val classes = (0 until device.interfaceCount).joinToString("/") { index ->
                val intf = device.getInterface(index)
                "if$index:class=${intf.interfaceClass},eps=${intf.endpointCount}"
            }
            "${device.deviceName} VID=${device.vendorId.hex16()} PID=${device.productId.hex16()} $classes"
        }

    private fun selectInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                val endpoints = selectEndpoints(usbInterface)
                if (endpoints.first != null && endpoints.second != null) return usbInterface
            }
        }
        return null
    }

    private fun selectEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
        }
        return input to output
    }

    private fun outputReport(packet: ByteArray): ByteArray {
        /*
         * SG-100 HID writes match the verified Python terminal:
         * byte 0 is HID report id 0x00, followed by the Modbus RTU frame,
         * then zero padding to the fixed 64-byte HID report size.
         *
         * This is not USB serial / COM-port traffic. It is an interrupt OUT
         * HID report carrying Modbus-style bytes.
         */
        require(packet.size + 1 <= HID_REPORT_LENGTH) { "Packet is larger than HID report size" }
        return ByteArray(HID_REPORT_LENGTH).also { report ->
            report[0] = 0x00
            packet.copyInto(report, destinationOffset = 1)
        }
    }

    private fun flushPendingReads(conn: UsbDeviceConnection, input: UsbEndpoint) {
        val buffer = ByteArray(HID_REPORT_LENGTH)
        repeat(16) {
            val count = conn.bulkTransfer(input, buffer, buffer.size, 1)
            if (count <= 0) return
        }
    }

    private fun describeEndpoint(endpoint: UsbEndpoint?): String =
        endpoint?.let { "${it.address.hex8()} max=${it.maxPacketSize}" } ?: "none"

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.sg100usb.USB_PERMISSION"
        private const val HID_REPORT_LENGTH = 64
        private const val WRITE_TIMEOUT_MS = 1500
        private const val READ_SLICE_TIMEOUT_MS = 50
        private const val SG100_VENDOR_ID = 0x04D8
        private const val SG100_PRODUCT_ID = 0xF1BB
    }
}
