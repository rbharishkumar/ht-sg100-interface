package com.example.sg100usb

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var deviceText: TextView
    private lateinit var logText: TextView
    private lateinit var hexInput: EditText
    private lateinit var numberInput: EditText
    private lateinit var connectButton: Button
    private lateinit var interfaceButton: Button
    private lateinit var sendButton: Button
    private lateinit var displayButton: Button

    private var selectedDevice: UsbDevice? = null
    private var selectedInterfaceIndex = 0
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    @Volatile private var reading = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openDevice(device)
                    } else {
                        appendLog("USB permission denied.")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB attached.")
                    scanDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB detached.")
                    if (device?.deviceId == selectedDevice?.deviceId) {
                        closeDevice()
                    }
                    scanDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        registerUsbReceiver()
        scanDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeDevice()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFFF6F8F8.toInt())
        }

        root.addView(
            TextView(this).apply {
                text = "SG-100 USB Host"
                textSize = 24f
                setTextColor(0xFF102321.toInt())
                gravity = Gravity.START
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        deviceText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF263D3A.toInt())
            setPadding(0, 20, 0, 20)
        }
        root.addView(deviceText, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        buttons.addView(
            Button(this).apply {
                text = "Scan USB"
                setOnClickListener { scanDevices() }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestPermissionAndConnect() }
        }
        buttons.addView(connectButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        interfaceButton = Button(this).apply {
            text = "Use Next Interface"
            isEnabled = false
            setOnClickListener { selectNextInterface() }
        }
        root.addView(interfaceButton, LinearLayout.LayoutParams(-1, -2))

        hexInput = EditText(this).apply {
            hint = "Hex bytes to send, e.g. 01 03 00 00"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, 20, 0, 10)
        }
        root.addView(hexInput, LinearLayout.LayoutParams(-1, -2))

        sendButton = Button(this).apply {
            text = "Send Hex"
            isEnabled = false
            setOnClickListener { sendHex() }
        }
        root.addView(sendButton, LinearLayout.LayoutParams(-1, -2))

        numberInput = EditText(this).apply {
            hint = "Optional number, e.g. 1500"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, 20, 0, 10)
        }
        root.addView(numberInput, LinearLayout.LayoutParams(-1, -2))

        displayButton = Button(this).apply {
            text = "Ask Number And Send To Display"
            isEnabled = false
            setOnClickListener { askNumberAndSendToDisplay() }
        }
        root.addView(displayButton, LinearLayout.LayoutParams(-1, -2))

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF1A1F1E.toInt())
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 0)
        }

        root.addView(
            ScrollView(this).apply {
                addView(logText, LinearLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun scanDevices() {
        selectedDevice = null
        val details = StringBuilder()
        val devices = usbManager.deviceList.values

        if (devices.isEmpty()) {
            details.append("No USB device detected.\n\n")
                .append("Use an OTG adapter/cable, connect the Huegli Tech SG-100, then tap Scan USB.")
            deviceText.text = details.toString()
            connectButton.isEnabled = false
            interfaceButton.isEnabled = false
            return
        }

        devices.forEach { device ->
            if (selectedDevice == null || isLikelySg100(device)) {
                selectedDevice = device
            }
            details.append(describeDevice(device)).append("\n")
        }

        deviceText.text = details.toString()
        connectButton.isEnabled = selectedDevice != null
        interfaceButton.isEnabled = (selectedDevice?.interfaceCount ?: 0) > 1

        selectedDevice?.let {
            selectedInterfaceIndex = bestInterfaceIndex(it)
            appendLog("Selected VID=${hex16(it.vendorId)} PID=${hex16(it.productId)}")
            appendLog("Selected interface=$selectedInterfaceIndex")
        }
    }

    private fun selectNextInterface() {
        val device = selectedDevice ?: return
        selectedInterfaceIndex = (selectedInterfaceIndex + 1) % device.interfaceCount
        closeDevice()
        appendLog("Selected interface=$selectedInterfaceIndex. Tap Connect again.")
    }

    private fun isLikelySg100(device: UsbDevice): Boolean {
        if (SG100_VENDOR_ID >= 0 && SG100_PRODUCT_ID >= 0) {
            return device.vendorId == SG100_VENDOR_ID && device.productId == SG100_PRODUCT_ID
        }

        val product = safe(device.productName).lowercase(Locale.US)
        val manufacturer = safe(device.manufacturerName).lowercase(Locale.US)
        if ("sg" in product || "huegli" in product || "huegli" in manufacturer) {
            return true
        }

        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass in setOf(
                    UsbConstants.USB_CLASS_HID,
                    UsbConstants.USB_CLASS_VENDOR_SPEC,
                    UsbConstants.USB_CLASS_CDC_DATA,
                    UsbConstants.USB_CLASS_COMM
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun describeDevice(device: UsbDevice): String {
        return buildString {
            appendLine("Device: ${safe(device.deviceName)}")
            appendLine("Manufacturer: ${safe(device.manufacturerName)}")
            appendLine("Product: ${safe(device.productName)}")
            appendLine("VID: ${hex16(device.vendorId)} / decimal ${device.vendorId}")
            appendLine("PID: ${hex16(device.productId)} / decimal ${device.productId}")
            appendLine("Device class: ${device.deviceClass}")

            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                append("  Interface $index")
                append(" class=${usbInterface.interfaceClass}")
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                    append(" HID")
                }
                append(" subclass=${usbInterface.interfaceSubclass}")
                append(" protocol=${usbInterface.interfaceProtocol}")
                appendLine()

                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    append("    Endpoint address=${hex8(endpoint.address)}")
                    append(" direction=${if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}")
                    append(" type=${endpointType(endpoint.type)}")
                    append(" maxPacket=${endpoint.maxPacketSize}")
                    appendLine()
                }
            }
        }
    }

    private fun requestPermissionAndConnect() {
        val device = selectedDevice
        if (device == null) {
            Toast.makeText(this, "No USB device selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (usbManager.hasPermission(device)) {
            openDevice(device)
            return
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        closeDevice()

        val openedConnection = usbManager.openDevice(device)
        if (openedConnection == null) {
            appendLog("Could not open device.")
            return
        }

        connection = openedConnection
        selectedDevice = device
        claimedInterface = findSelectedInterface(device)

        val usbInterface = claimedInterface
        if (usbInterface == null) {
            appendLog("No HID, vendor, CDC, bulk, or interrupt interface found.")
            closeDevice()
            return
        }

        if (!openedConnection.claimInterface(usbInterface, true)) {
            appendLog("Could not claim interface ${usbInterface.id}.")
            closeDevice()
            return
        }

        findEndpoints(usbInterface)
        appendLog(
            "Connected to VID=${hex16(device.vendorId)} PID=${hex16(device.productId)} " +
                "interfaceClass=${usbInterface.interfaceClass} " +
                "in=${inEndpoint?.address?.let(::hex8) ?: "none"} " +
                "out=${outEndpoint?.address?.let(::hex8) ?: "none"}"
        )

        sendButton.isEnabled = outEndpoint != null
        displayButton.isEnabled = outEndpoint != null
        startReader()
    }

    private fun findSelectedInterface(device: UsbDevice): UsbInterface? {
        if (selectedInterfaceIndex !in 0 until device.interfaceCount) {
            selectedInterfaceIndex = bestInterfaceIndex(device)
        }
        return device.getInterface(selectedInterfaceIndex)
    }

    private fun bestInterfaceIndex(device: UsbDevice): Int {
        var fallback = 0
        var fallbackSet = false

        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            val endpoints = endpointsFor(usbInterface)

            if (!fallbackSet && usbInterface.endpointCount > 0) {
                fallback = index
                fallbackSet = true
            }

            if (endpoints.first != null && endpoints.second != null) {
                return index
            }
        }

        return fallback
    }

    private fun findEndpoints(usbInterface: UsbInterface) {
        inEndpoint = null
        outEndpoint = null

        val endpoints = endpointsFor(usbInterface)
        inEndpoint = endpoints.first
        outEndpoint = endpoints.second
    }

    private fun endpointsFor(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null

        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            val supportedType = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK

            if (!supportedType) continue

            if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) {
                input = endpoint
            } else if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) {
                output = endpoint
            }
        }

        return input to output
    }

    private fun startReader() {
        val endpoint = inEndpoint
        if (endpoint == null || connection == null) {
            appendLog("No readable IN endpoint found.")
            return
        }

        reading = true
        thread(name = "sg100-usb-reader") {
            val buffer = ByteArray(maxOf(endpoint.maxPacketSize, 64))
            while (reading) {
                val activeConnection = connection ?: break
                val count = activeConnection.bulkTransfer(endpoint, buffer, buffer.size, 500)
                if (count > 0) {
                    val received = buffer.copyOf(count)
                    runOnUiThread {
                        appendLog("RX ${toHex(received)} | ASCII ${toAscii(received)}")
                    }
                }
            }
        }
    }

    private fun sendHex() {
        val activeConnection = connection
        val endpoint = outEndpoint
        if (activeConnection == null || endpoint == null) {
            appendLog("No writable OUT endpoint.")
            return
        }

        val bytes = try {
            parseHex(hexInput.text.toString())
        } catch (error: IllegalArgumentException) {
            appendLog("Invalid hex: ${error.message}")
            return
        }

        val payload = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            bytes.copyOf(endpoint.maxPacketSize)
        } else {
            bytes
        }

        val sent = activeConnection.bulkTransfer(endpoint, payload, payload.size, 1500)
        appendLog("TX requested ${payload.size} bytes, sent $sent: ${toHex(payload)}")
    }

    private fun askNumberAndSendToDisplay() {
        val input = EditText(this).apply {
            hint = "Enter number, e.g. 1234"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(numberInput.text.toString().trim())
            selectAll()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("SG-100 Display Number")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val numberText = input.text.toString().trim()
                if (sendNumberToDisplay(numberText)) {
                    numberInput.setText(numberText)
                    dialog.dismiss()
                }
            }

            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    private fun sendNumberToDisplay(numberText: String): Boolean {
        val activeConnection = connection
        val endpoint = outEndpoint
        if (activeConnection == null || endpoint == null) {
            appendLog("No writable OUT endpoint.")
            return false
        }

        val value = numberText.toIntOrNull()
        if (value == null || value !in 0..9999) {
            appendLog("Enter a display number from 0 to 9999.")
            return false
        }

        val command = buildDisplayNumberCommand(value, endpoint)
        val sent = activeConnection.bulkTransfer(endpoint, command, command.size, 1500)
        appendLog("Display TX number=$value requested ${command.size} bytes, sent $sent: ${toHex(command)}")
        return sent >= 0
    }

    private fun buildDisplayNumberCommand(value: Int, endpoint: UsbEndpoint): ByteArray {
        /*
         * Placeholder command format.
         *
         * This sends the number as 4 ASCII digits, for example 1500 -> 31 35 30 30.
         * If Huegli's SG-100 protocol uses a header/checksum/report-id command, replace
         * this function with that packet format.
         */
        val asciiNumber = value.toString().padStart(4, '0').toByteArray(StandardCharsets.US_ASCII)

        return if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
            asciiNumber.copyOf(endpoint.maxPacketSize)
        } else {
            asciiNumber
        }
    }

    private fun closeDevice() {
        reading = false
        if (::sendButton.isInitialized) {
            sendButton.isEnabled = false
        }
        if (::displayButton.isInitialized) {
            displayButton.isEnabled = false
        }

        val activeConnection = connection
        val activeInterface = claimedInterface
        if (activeConnection != null && activeInterface != null) {
            activeConnection.releaseInterface(activeInterface)
        }
        activeConnection?.close()

        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
    }

    private fun parseHex(raw: String): ByteArray {
        val cleaned = raw
            .replace("0x", "")
            .replace("0X", "")
            .replace(",", " ")
            .replace("\n", " ")
            .trim()

        require(cleaned.isNotEmpty()) { "enter at least one byte" }

        return cleaned.split(Regex("\\s+")).map { part ->
            require(part.length <= 2) { "byte '$part' is too long" }
            part.toInt(16).toByte()
        }.toByteArray()
    }

    private fun appendLog(message: String) {
        val line = "[${DateFormat.getTimeInstance().format(Date())}] $message"
        logText.append("$line\n")
    }

    private fun endpointType(type: Int): String {
        return when (type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            else -> type.toString()
        }
    }

    private fun hex16(value: Int): String {
        return String.format(Locale.US, "0x%04X", value and 0xFFFF)
    }

    private fun hex8(value: Int): String {
        return String.format(Locale.US, "0x%02X", value and 0xFF)
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
    }

    private fun toAscii(bytes: ByteArray): String {
        return String(bytes, StandardCharsets.US_ASCII).replace(Regex("[^\\x20-\\x7E]"), ".")
    }

    private fun safe(value: String?): String {
        return value?.takeIf { it.isNotBlank() } ?: "Unknown"
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.sg100usb.USB_PERMISSION"

        /*
         * Fill these after your first scan if you want strict SG-100 matching.
         * Example:
         * private const val SG100_VENDOR_ID = 0x1234
         * private const val SG100_PRODUCT_ID = 0x5678
         */
        private const val SG100_VENDOR_ID = 0x04D8
        private const val SG100_PRODUCT_ID = 0xF1BB
    }
}
