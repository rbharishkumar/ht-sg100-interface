package com.example.sg100usb

import android.app.Activity
import android.app.AlertDialog
import android.app.AlertDialog.BUTTON_POSITIVE
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams as LinearLayoutParams
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var rpmGauge: RpmGaugeView
    private lateinit var rpmValueText: TextView
    private lateinit var rpmSourceText: TextView
    private lateinit var deviceText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var packetMonitorText: TextView
    private lateinit var packetMonitorScroll: ScrollView
    private lateinit var connectionStatusText: TextView
    private lateinit var hexInput: EditText
    private lateinit var reportIdInput: EditText
    private lateinit var reportPayloadInput: EditText
    private lateinit var numberInput: EditText
    private lateinit var connectButton: MaterialButton
    private lateinit var interfaceButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var sendReportButton: MaterialButton
    private lateinit var saveLogsButton: MaterialButton
    private lateinit var pollTestButton: MaterialButton
    private lateinit var displayButton: MaterialButton
    private lateinit var slaveIdInput: EditText
    private lateinit var registerInput: EditText
    private lateinit var quantityInput: EditText
    private lateinit var modbusReadButton: MaterialButton
    private lateinit var baudRateInput: EditText
    private lateinit var parityInput: EditText

    private var selectedDevice: UsbDevice? = null
    private var selectedInterfaceIndex = 0
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private val logLines = mutableListOf<String>()
    private val packetMonitorLines = mutableListOf<String>()
    @Volatile private var reading = false
    @Volatile private var lastRxAtMillis = 0L

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
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        buildUi()
        registerUsbReceiver()
        scanDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeDevice()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SAVE_HID_LOG_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                saveLogsToUri(uri)
            } else {
                appendLog("Log save canceled: no destination selected.")
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedRect(fill: Int, stroke: Int, cornerDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * resources.displayMetrics.density
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun styleField(e: EditText) {
        e.background = roundedRect(0xFFFFFFFF.toInt(), 0xFFBFD6D2.toInt(), 12f)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.setHintTextColor(0xFF5A7874.toInt())
    }

    private fun sectionTitle(text: CharSequence): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0B3D38.toInt())
            letterSpacing = 0.02f
        }

    private fun microLabel(s: String): TextView =
        TextView(this).apply {
            text = s
            textSize = 12f
            setTextColor(0xFF5F7673.toInt())
        }

    private fun outlinedActionButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
            minHeight = dp(48)
        }

    private fun filledActionButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            minHeight = dp(48)
    }

    private fun lpMatchWrap(): LinearLayoutParams =
        LinearLayoutParams(ViewGroupLayoutParams.MATCH_PARENT, ViewGroupLayoutParams.WRAP_CONTENT)

    private fun buildUi() {
        val screenScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            setBackgroundColor(0xFFF0F5F4.toInt())
        }

        root.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ht_logo)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayoutParams(ViewGroupLayoutParams.MATCH_PARENT, dp(76)).apply {
                bottomMargin = dp(12)
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_title)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF052322.toInt())
            },
            lpMatchWrap().apply { bottomMargin = dp(4) },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_subtitle)
                textSize = 14f
                setTextColor(0xFF4B6965.toInt())
                setLineSpacing(2f, 1.1f)
            },
            lpMatchWrap().apply { bottomMargin = dp(16) },
        )

        root.addView(sectionTitle("RPM meter"), lpMatchWrap().apply { bottomMargin = dp(8) })

        rpmGauge = RpmGaugeView(this).apply {
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFC9DAD7.toInt(), 14f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(
            rpmGauge,
            LinearLayoutParams(ViewGroupLayoutParams.MATCH_PARENT, dp(210)).apply {
                bottomMargin = dp(8)
            },
        )

        rpmValueText = TextView(this).apply {
            text = getString(R.string.rpm_initial_value)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF052322.toInt())
        }
        root.addView(rpmValueText, lpMatchWrap().apply { bottomMargin = dp(2) })

        rpmSourceText = microLabel("Waiting for RPM data")
        root.addView(rpmSourceText, lpMatchWrap().apply { bottomMargin = dp(18) })

        root.addView(sectionTitle("Device"), lpMatchWrap().apply { bottomMargin = dp(6) })

        connectionStatusText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(connectionStatusText, lpMatchWrap().apply { bottomMargin = dp(8) })
        updateConnectionStatus(false)

        deviceText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF152422.toInt())
            setLineSpacing(3f, 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFC9DAD7.toInt(), 14f)
        }
        root.addView(
            deviceText,
            lpMatchWrap().apply {
                bottomMargin = dp(18)
            },
        )

        root.addView(sectionTitle("USB actions"), lpMatchWrap().apply { bottomMargin = dp(8) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            outlinedActionButton("Scan USB") { scanDevices() },
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            },
        )
        connectButton = outlinedActionButton("Connect") {
            if (connection != null) {
                closeDevice()
            } else {
                requestPermissionAndConnect()
            }
        }
        row.addView(
            connectButton,
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(row, lpMatchWrap().apply { bottomMargin = dp(10) })

        interfaceButton = outlinedActionButton("Use next interface") { selectNextInterface() }.apply {
            isEnabled = false
        }
        root.addView(interfaceButton, lpMatchWrap().apply { bottomMargin = dp(20) })

        root.addView(sectionTitle("Send raw hex"), lpMatchWrap().apply { bottomMargin = dp(6) })

        hexInput = EditText(this).apply {
            hint = "Hex bytes, e.g. 01 03 00 00 ..."
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            styleField(this)
        }
        root.addView(hexInput, lpMatchWrap().apply { bottomMargin = dp(10) })

        sendButton = filledActionButton("Send hex") { sendHex() }.apply { isEnabled = false }
        root.addView(sendButton, lpMatchWrap().apply { bottomMargin = dp(10) })

        pollTestButton = outlinedActionButton("Send Modbus Poll test") { sendModbusPollTest() }.apply {
            isEnabled = false
        }
        root.addView(pollTestButton, lpMatchWrap().apply { bottomMargin = dp(22) })

        root.addView(sectionTitle("Manual HID report"), lpMatchWrap().apply { bottomMargin = dp(6) })

        val reportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reportRow.addView(
            microLabel("Report ID"),
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 0.6f).apply {
                marginEnd = dp(10)
            },
        )
        reportRow.addView(
            microLabel("Payload bytes"),
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1.4f),
        )
        root.addView(reportRow, lpMatchWrap().apply { bottomMargin = dp(4) })

        val reportFields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reportIdInput = EditText(this).apply {
            setText("00")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            styleField(this)
        }
        reportFields.addView(
            reportIdInput,
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 0.6f).apply {
                marginEnd = dp(10)
            },
        )
        reportPayloadInput = EditText(this).apply {
            hint = "Hex payload bytes"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            styleField(this)
        }
        reportFields.addView(
            reportPayloadInput,
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1.4f),
        )
        root.addView(reportFields, lpMatchWrap().apply { bottomMargin = dp(10) })

        sendReportButton = filledActionButton("Send HID report") { sendCustomHidReport() }.apply {
            isEnabled = false
        }
        root.addView(sendReportButton, lpMatchWrap().apply { bottomMargin = dp(22) })

        root.addView(sectionTitle("Modbus RTU settings"), lpMatchWrap().apply { bottomMargin = dp(6) })

        root.addView(microLabel("Baud rate"), lpMatchWrap().apply { bottomMargin = dp(4) })
        baudRateInput = EditText(this).apply {
            setText(DEFAULT_USB_SERIAL_BAUD_RATE.toString())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            styleField(this)
        }
        root.addView(baudRateInput, lpMatchWrap().apply { bottomMargin = dp(10) })

        root.addView(microLabel("Parity"), lpMatchWrap().apply { bottomMargin = dp(4) })
        parityInput = EditText(this).apply {
            setText(DEFAULT_USB_SERIAL_PARITY_LABEL)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            styleField(this)
        }
        root.addView(parityInput, lpMatchWrap().apply { bottomMargin = dp(10) })

        root.addView(
            sectionTitle("Modbus read (decimal register)"),
            lpMatchWrap().apply { bottomMargin = dp(6) },
        )

        val labelsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelsRow.addView(
            microLabel("Slave ID"),
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f),
        )
        labelsRow.addView(
            microLabel("Register address"),
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(labelsRow, lpMatchWrap().apply { bottomMargin = dp(4) })

        val modbusFields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        slaveIdInput = EditText(this).apply {
            setText(DEFAULT_MODBUS_SLAVE_ID.toString())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            styleField(this)
        }
        modbusFields.addView(
            slaveIdInput,
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            },
        )

        registerInput = EditText(this).apply {
            setText(DEFAULT_MODBUS_READ_REGISTER.toString())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            styleField(this)
        }
        modbusFields.addView(
            registerInput,
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(modbusFields, lpMatchWrap().apply { bottomMargin = dp(10) })

        root.addView(microLabel("Number of registers to read"), lpMatchWrap().apply { bottomMargin = dp(4) })

        quantityInput = EditText(this).apply {
            setText("1")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            styleField(this)
        }
        root.addView(quantityInput, lpMatchWrap().apply { bottomMargin = dp(10) })

        modbusReadButton = filledActionButton("Build and send Modbus read") { sendModbusRead() }.apply {
            isEnabled = false
        }
        root.addView(modbusReadButton, lpMatchWrap().apply { bottomMargin = dp(18) })

        root.addView(
            sectionTitle("Modbus write (holding register)"),
            lpMatchWrap().apply { bottomMargin = dp(6) },
        )

        root.addView(microLabel("Value to write"), lpMatchWrap().apply { bottomMargin = dp(4) })

        numberInput = EditText(this).apply {
            hint = "e.g. 1500"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            styleField(this)
        }
        root.addView(numberInput, lpMatchWrap().apply { bottomMargin = dp(10) })

        displayButton = filledActionButton("Write Modbus register") { askModbusValueAndSend() }.apply {
            isEnabled = false
        }
        root.addView(displayButton, lpMatchWrap().apply { bottomMargin = dp(16) })

        root.addView(sectionTitle("Live packet monitor"), lpMatchWrap().apply { bottomMargin = dp(6) })

        packetMonitorText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF102321.toInt())
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(0xFFFFFFFF.toInt(), 0xFFC9DAD7.toInt(), 12f)
        }
        packetMonitorScroll = ScrollView(this).apply {
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addView(
                packetMonitorText,
                ViewGroupLayoutParams(
                    ViewGroupLayoutParams.MATCH_PARENT,
                    ViewGroupLayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            packetMonitorScroll,
            LinearLayoutParams(ViewGroupLayoutParams.MATCH_PARENT, dp(220)).apply {
                bottomMargin = dp(16)
            },
        )

        val logHeaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logHeaderRow.addView(
            sectionTitle("Activity log"),
            LinearLayoutParams(0, ViewGroupLayoutParams.WRAP_CONTENT, 1f),
        )
        saveLogsButton = outlinedActionButton("Save logs") { promptSaveLogs() }
        logHeaderRow.addView(
            saveLogsButton,
            LinearLayoutParams(ViewGroupLayoutParams.WRAP_CONTENT, ViewGroupLayoutParams.WRAP_CONTENT),
        )
        root.addView(logHeaderRow, lpMatchWrap().apply { bottomMargin = dp(6) })

        logText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF1A2423.toInt())
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(0xFFEAF3F2.toInt(), 0xFFB9D0CC.toInt(), 12f)
        }
        logScroll = ScrollView(this).apply {
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addView(
                logText,
                ViewGroupLayoutParams(
                    ViewGroupLayoutParams.MATCH_PARENT,
                    ViewGroupLayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            logScroll,
            LinearLayoutParams(ViewGroupLayoutParams.MATCH_PARENT, dp(260)),
        )

        hexInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                screenScroll.post {
                    screenScroll.smoothScrollTo(0, (hexInput.top - dp(24)).coerceAtLeast(0))
                }
            }
        }

        screenScroll.addView(
            root,
            ViewGroupLayoutParams(
                ViewGroupLayoutParams.MATCH_PARENT,
                ViewGroupLayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(screenScroll)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
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
                .append("Use an OTG adapter/cable, connect the SG-100, then tap Scan USB.")
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
        if (device.vendorId == SG100_VENDOR_ID && device.productId == SG100_PRODUCT_ID) {
            return true
        }

        val product = safe(device.productName).lowercase(Locale.US)
        val manufacturer = safe(device.manufacturerName).lowercase(Locale.US)
        if ("sg" in product || SG100_VENDOR_NAME in product || SG100_VENDOR_NAME in manufacturer) {
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
        closeDevice(logDisconnect = false)

        val openedConnection = usbManager.openDevice(device)
        if (openedConnection == null) {
            appendLog("Could not open device.")
            return
        }

        connection = openedConnection
        selectedDevice = device
        val usbInterface = findSelectedInterface(device)
        claimedInterface = usbInterface

        if (!openedConnection.claimInterface(usbInterface, true)) {
            appendLog("Could not claim interface ${usbInterface.id}.")
            closeDevice()
            return
        }

        findEndpoints(usbInterface)
        configureSerialLineIfSupported(openedConnection, usbInterface)
        appendLog(
            "Connected to VID=${hex16(device.vendorId)} PID=${hex16(device.productId)} " +
                "interfaceClass=${usbInterface.interfaceClass} " +
                "in=${inEndpoint?.address?.let(::hex8) ?: "none"} " +
                "out=${outEndpoint?.address?.let(::hex8) ?: "none"}"
        )

        val canWrite = canWriteHid()
        sendButton.isEnabled = canWrite
        pollTestButton.isEnabled = canWrite
        sendReportButton.isEnabled = canWrite
        modbusReadButton.isEnabled = canWrite
        displayButton.isEnabled = canWrite
        updateConnectionStatus(true)
        startReader()
    }

    private fun findSelectedInterface(device: UsbDevice): UsbInterface {
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

    private fun configureSerialLineIfSupported(
        activeConnection: UsbDeviceConnection,
        usbInterface: UsbInterface
    ) {
        if (usbInterface.interfaceClass !in setOf(
                UsbConstants.USB_CLASS_COMM,
                UsbConstants.USB_CLASS_CDC_DATA,
                UsbConstants.USB_CLASS_VENDOR_SPEC
            )
        ) {
            appendLog("Baud setup skipped: selected interface is not CDC/serial-like.")
            return
        }

        val baudRate = baudRateInput.text.toString().trim().toIntOrNull()
        if (baudRate == null || baudRate <= 0) {
            appendLog("Baud setup skipped: enter a valid baud rate.")
            return
        }
        val parity = parseParity(parityInput.text.toString())
        if (parity == null) {
            appendLog("Baud setup skipped: parity must be None, Even, or Odd.")
            return
        }

        val lineCoding = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            ((baudRate shr 8) and 0xFF).toByte(),
            ((baudRate shr 16) and 0xFF).toByte(),
            ((baudRate shr 24) and 0xFF).toByte(),
            USB_SERIAL_STOP_BITS_1,
            parity.first,
            USB_SERIAL_DATA_BITS_8,
        )
        val setLineCoding = activeConnection.controlTransfer(
            USB_CDC_REQUEST_TYPE_OUT,
            USB_CDC_SET_LINE_CODING,
            0,
            usbInterface.id,
            lineCoding,
            lineCoding.size,
            1000,
        )
        val setControlLine = activeConnection.controlTransfer(
            USB_CDC_REQUEST_TYPE_OUT,
            USB_CDC_SET_CONTROL_LINE_STATE,
            USB_CDC_CONTROL_DTR or USB_CDC_CONTROL_RTS,
            usbInterface.id,
            null,
            0,
            1000,
        )

        if (setLineCoding == lineCoding.size && setControlLine >= 0) {
            appendLog("Serial line configured: $baudRate 8${parity.second}1.")
        } else {
            appendLog("Serial $baudRate 8${parity.second}1 setup not accepted by this interface.")
        }
    }

    private fun parseParity(raw: String): Pair<Byte, String>? {
        return when (raw.trim().lowercase(Locale.US)) {
            "none", "n", "0" -> USB_SERIAL_PARITY_NONE to "N"
            "odd", "o", "1" -> USB_SERIAL_PARITY_ODD to "O"
            "even", "e", "2" -> USB_SERIAL_PARITY_EVEN to "E"
            else -> null
        }
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
                    lastRxAtMillis = System.currentTimeMillis()
                    runOnUiThread {
                        logHidPacket("RX", "ep=${hex8(endpoint.address)}", received, count)
                        appendReceived(received)
                    }
                }
            }
        }
    }

    private fun sendHex() {
        val activeConnection = connection
        if (activeConnection == null || !canWriteHid()) {
            appendLog("No writable HID path. Try another interface, then Connect.")
            return
        }

        val bytes = try {
            parseHex(hexInput.text.toString())
        } catch (error: IllegalArgumentException) {
            appendLog("Invalid hex: ${error.message}")
            return
        }

        val payload = bytes

        updateRpmFromModbusFrame(payload, "TX")
        val txAtMillis = System.currentTimeMillis()
        writeHidPacket(activeConnection, payload, 1500, "Raw hex")
        checkForReplyAfterTx(txAtMillis)
    }

    private fun sendCustomHidReport(): Boolean {
        val activeConnection = connection
        if (activeConnection == null || !canWriteHid()) {
            appendLog("No writable HID path. Try another interface, then Connect.")
            return false
        }

        val reportId = try {
            parseReportId(reportIdInput.text.toString())
        } catch (error: IllegalArgumentException) {
            appendLog("Invalid report ID: ${error.message}")
            return false
        }

        val payload = try {
            parseOptionalHex(reportPayloadInput.text.toString())
        } catch (error: IllegalArgumentException) {
            appendLog("Invalid report payload: ${error.message}")
            return false
        }

        val report = ByteArray(payload.size + 1)
        report[0] = reportId.toByte()
        payload.copyInto(report, destinationOffset = 1)

        val txAtMillis = System.currentTimeMillis()
        val sent = writeHidPacket(activeConnection, report, 1500, "Manual HID report")
        checkForReplyAfterTx(txAtMillis)
        return sent >= 0
    }

    private fun sendModbusPollTest() {
        hexInput.text.replace(0, hexInput.text.length, MODBUS_POLL_TEST_FRAME)
        appendLog("Expected RX: $MODBUS_POLL_TEST_EXPECTED_REPLY")
        sendHex()
    }

    private fun sendModbusRead(): Boolean {
        val activeConnection = connection
        if (activeConnection == null || !canWriteHid()) {
            appendLog("No writable HID path. Try another interface, then Connect.")
            return false
        }

        val slaveId = slaveIdInput.text.toString().trim().toIntOrNull()
        if (slaveId == null || slaveId !in 1..247) {
            appendLog("Enter a Modbus slave ID from 1 to 247.")
            return false
        }

        val displayRegister = registerInput.text.toString().trim().toIntOrNull()
        if (displayRegister == null) {
            appendLog("Enter a decimal register such as 30062 or 40061.")
            return false
        }

        val quantity = quantityInput.text.toString().trim().toIntOrNull()
        if (quantity == null || quantity !in 1..125) {
            appendLog("Enter a quantity from 1 to 125 registers.")
            return false
        }

        val request = try {
            buildModbusReadRegistersCommand(slaveId, displayRegister, quantity)
        } catch (error: IllegalArgumentException) {
            appendLog(error.message ?: "Invalid Modbus read request.")
            return false
        }

        val registerType = if (request.functionCode == MODBUS_READ_INPUT_REGISTERS) {
            "Input Registers"
        } else {
            "Holding Registers"
        }
        appendLog(
            "Register conversion: $displayRegister -> $registerType, " +
                "function=${hex8(request.functionCode)}, " +
                "zeroBased=${request.zeroBasedAddress} (${hex16(request.zeroBasedAddress)})"
        )

        val txAtMillis = System.currentTimeMillis()
        val sent = writeHidPacket(
            activeConnection,
            request.frame,
            1500,
            "Modbus read slave=$slaveId register=$displayRegister quantity=$quantity",
        )
        checkForReplyAfterTx(txAtMillis)
        return sent >= 0
    }

    private fun askModbusValueAndSend() {
        val input = EditText(this).apply {
            hint = "Enter value, e.g. 1500"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(numberInput.text.toString().trim())
            selectAll()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Write Modbus Register")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                val numberText = input.text.toString().trim()
                if (sendModbusValue(numberText)) {
                    numberInput.text.replace(0, numberInput.text.length, numberText)
                    dialog.dismiss()
                }
            }

            input.requestFocus()
            dialog.window?.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(input, SHOW_IMPLICIT)
        }

        dialog.show()
    }

    private fun sendModbusValue(numberText: String): Boolean {
        val activeConnection = connection
        if (activeConnection == null || !canWriteHid()) {
            appendLog("No writable HID path. Try another interface, then Connect.")
            return false
        }

        val value = numberText.toIntOrNull()
        if (value == null || value !in 0..0xFFFF) {
            appendLog("Enter a Modbus value from 0 to 65535.")
            return false
        }

        val slaveId = slaveIdInput.text.toString().trim().toIntOrNull()
        if (slaveId == null || slaveId !in 1..247) {
            appendLog("Enter a Modbus slave ID from 1 to 247.")
            return false
        }

        val holdingRegister = registerInput.text.toString().trim().toIntOrNull()
        if (holdingRegister == null) {
            appendLog("Enter a holding register, for example 40061 for SG300 Speed1.")
            return false
        }

        val command = try {
            buildModbusWriteSingleRegisterCommand(slaveId, holdingRegister, value)
        } catch (error: IllegalArgumentException) {
            appendLog(error.message ?: "Invalid Modbus register.")
            return false
        }

        val txAtMillis = System.currentTimeMillis()
        val sent = writeHidPacket(
            activeConnection,
            command,
            1500,
            "Modbus write slave=$slaveId register=$holdingRegister value=$value",
        )
        checkForReplyAfterTx(txAtMillis)
        return sent >= 0
    }

    private fun checkForReplyAfterTx(txAtMillis: Long) {
        val endpoint = inEndpoint
        if (endpoint == null) {
            appendLog("RX unavailable: selected interface has no IN endpoint. Try Use next interface, then Connect.")
            return
        }

        appendLog("Waiting for RX on ${hex8(endpoint.address)}...")
        thread(name = "sg100-usb-reply-check") {
            Thread.sleep(RX_TIMEOUT_CHECK_MS)
            runOnUiThread {
                if (lastRxAtMillis <= txAtMillis) {
                    appendLog("No RX after TX. Try another interface or confirm the SG-100 slave ID/register/protocol.")
                }
            }
        }
    }

    private fun buildModbusWriteSingleRegisterCommand(
        slaveId: Int,
        holdingRegister: Int,
        value: Int
    ): ByteArray {
        val registerOffset = holdingRegisterToOffset(holdingRegister)
        val frame = ByteArray(8)
        frame[0] = slaveId.toByte()
        frame[1] = MODBUS_WRITE_SINGLE_REGISTER.toByte()
        frame[2] = (registerOffset shr 8).toByte()
        frame[3] = (registerOffset and 0xFF).toByte()
        frame[4] = (value shr 8).toByte()
        frame[5] = (value and 0xFF).toByte()

        val crc = modbusCrc16(frame.copyOf(MODBUS_WRITE_SINGLE_REGISTER_FRAME_LENGTH))
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()

        return frame
    }

    private fun buildModbusReadRegistersCommand(
        slaveId: Int,
        displayRegister: Int,
        quantity: Int
    ): ModbusReadRequest {
        val converted = convertDisplayRegister(displayRegister)

        /*
         * Modbus RTU read request frame:
         * [0] Slave ID
         * [1] Function code: 0x04 for Input Registers, 0x03 for Holding Registers
         * [2..3] Start address, high byte first
         * [4..5] Quantity of 16-bit registers, high byte first
         * [6..7] CRC16, low byte first on the wire
         */
        val frame = ByteArray(MODBUS_READ_REQUEST_LENGTH)
        frame[0] = slaveId.toByte()
        frame[1] = converted.functionCode.toByte()
        frame[2] = (converted.zeroBasedAddress shr 8).toByte()
        frame[3] = (converted.zeroBasedAddress and 0xFF).toByte()
        frame[4] = (quantity shr 8).toByte()
        frame[5] = (quantity and 0xFF).toByte()

        /*
         * Modbus CRC16 starts at 0xFFFF and shifts each bit through the reversed
         * polynomial 0xA001. The CRC is appended little-endian: low byte, then high byte.
         */
        val crc = modbusCrc16(frame.copyOf(MODBUS_READ_REQUEST_WITHOUT_CRC_LENGTH))
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()

        return ModbusReadRequest(
            functionCode = converted.functionCode,
            zeroBasedAddress = converted.zeroBasedAddress,
            frame = frame,
        )
    }

    private fun convertDisplayRegister(displayRegister: Int): ConvertedRegister {
        /*
         * Many manuals show human-friendly register numbers like 30062 or 40061.
         * The leading digit tells you the table:
         *   3xxxx = Input Register table, read with function 0x04
         *   4xxxx = Holding Register table, read with function 0x03
         *
         * The RTU frame does not send that leading 3 or 4. It sends a zero-based
         * address inside the selected table. Example: 30062 means the 62nd input
         * register, so the address sent in the frame is 62 - 1 = 61 = 0x003D.
         */
        val functionCode = when (displayRegister) {
            in 30001..39999 -> MODBUS_READ_INPUT_REGISTERS
            in 40001..49999 -> MODBUS_READ_HOLDING_REGISTERS
            else -> throw IllegalArgumentException("Register must be 30001-39999 or 40001-49999.")
        }
        val zeroBasedAddress = (displayRegister % 10000) - 1
        require(zeroBasedAddress in 0..0xFFFF) { "Converted Modbus address must be 0 to 65535." }
        return ConvertedRegister(functionCode, zeroBasedAddress)
    }

    private fun holdingRegisterToOffset(holdingRegister: Int): Int {
        val offset = when (holdingRegister) {
            in 40001..49999 -> holdingRegister - 40001
            in 0..0xFFFF -> holdingRegister
            else -> throw IllegalArgumentException("Holding register must be 40001-49999 or raw 0-65535.")
        }

        require(offset in 0..0xFFFF) { "Modbus register offset must be 0 to 65535." }
        return offset
    }

    private fun modbusCrc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun closeDevice(logDisconnect: Boolean = true) {
        val wasConnected = connection != null
        reading = false
        if (::sendButton.isInitialized) {
            sendButton.isEnabled = false
        }
        if (::sendReportButton.isInitialized) {
            sendReportButton.isEnabled = false
        }
        if (::pollTestButton.isInitialized) {
            pollTestButton.isEnabled = false
        }
        if (::modbusReadButton.isInitialized) {
            modbusReadButton.isEnabled = false
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
        updateConnectionStatus(false)
        if (logDisconnect && wasConnected) {
            appendLog("Disconnected.")
        }
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

    private fun parseOptionalHex(raw: String): ByteArray {
        val normalized = raw.trim()
        return if (normalized.isEmpty()) {
            ByteArray(0)
        } else {
            parseHex(normalized)
        }
    }

    private fun parseReportId(raw: String): Int {
        val cleaned = raw
            .trim()
            .removePrefix("0x")
            .removePrefix("0X")

        require(cleaned.isNotEmpty()) { "enter a report ID" }
        require(cleaned.length <= 2) { "report ID must be one byte" }

        val reportId = cleaned.toIntOrNull(16)
            ?: cleaned.toIntOrNull()
            ?: throw IllegalArgumentException("use hex like 00 or decimal 0-255")
        require(reportId in 0..0xFF) { "report ID must be 0-255" }
        return reportId
    }

    private fun appendReceived(received: ByteArray) {
        val parsed = parseModbusResponse(received)
        if (parsed != null) {
            appendLog(parsed.summary)
            if (parsed.crcPass) {
                updateRpmFromModbusFrame(parsed.frame, "RX")
            }
        } else {
            appendLog("RX unparsed ${received.size} bytes: ${toHex(received.trimTrailingZeroBytes())}")
        }
    }

    private fun ByteArray.trimTrailingZeroBytes(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) {
            end--
        }
        return copyOf(end)
    }

    private fun extractModbusFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < MODBUS_MIN_RESPONSE_LENGTH) return null

        val function = bytes[1].toInt() and 0xFF
        val expectedLength = when {
            function and MODBUS_EXCEPTION_FLAG != 0 -> MODBUS_EXCEPTION_RESPONSE_LENGTH
            function in setOf(MODBUS_READ_HOLDING_REGISTERS, MODBUS_READ_INPUT_REGISTERS) && bytes.size >= 3 ->
                (bytes[2].toInt() and 0xFF) + MODBUS_RESPONSE_OVERHEAD
            function == MODBUS_WRITE_SINGLE_REGISTER -> MODBUS_WRITE_SINGLE_REGISTER_RESPONSE_LENGTH
            else -> null
        }

        if (
            expectedLength != null &&
            bytes.size >= expectedLength &&
            hasValidModbusCrc(bytes, expectedLength)
        ) {
            return bytes.copyOf(expectedLength)
        }

        for (length in MODBUS_MIN_RESPONSE_LENGTH..bytes.size) {
            if (hasValidModbusCrc(bytes, length)) {
                return bytes.copyOf(length)
            }
        }

        return null
    }

    private fun parseModbusResponse(received: ByteArray): ParsedModbusResponse? {
        val trimmed = received.trimTrailingZeroBytes()
        if (trimmed.size < MODBUS_MIN_RESPONSE_LENGTH) return null

        val frame = extractModbusFrame(trimmed) ?: trimmed
        if (frame.size < MODBUS_MIN_RESPONSE_LENGTH) return null

        val expectedCrc = modbusCrc16(frame.copyOf(frame.size - MODBUS_CRC_LENGTH))
        val actualCrc = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        val crcPass = expectedCrc == actualCrc
        val function = frame[1].toInt() and 0xFF

        val details = StringBuilder()
        details.append("RX Modbus ${toHex(frame)}")
        details.append(" | CRC ${if (crcPass) "PASS" else "FAIL"}")
        details.append(" expected=${hex16(expectedCrc)} actual=${hex16(actualCrc)}")

        when {
            function and MODBUS_EXCEPTION_FLAG != 0 && frame.size >= MODBUS_EXCEPTION_RESPONSE_LENGTH -> {
                val code = frame[2].toInt() and 0xFF
                details.append(" | exception ${modbusExceptionName(code)}")
            }
            function in setOf(MODBUS_READ_HOLDING_REGISTERS, MODBUS_READ_INPUT_REGISTERS) && frame.size >= MODBUS_MIN_RESPONSE_LENGTH -> {
                val byteCount = frame[2].toInt() and 0xFF
                details.append(" | dataHex=${readRegisterDataHex(frame, byteCount)}")
                details.append(" | dataDec=${readRegisterDataDecimals(frame, byteCount)}")
            }
            function == MODBUS_WRITE_SINGLE_REGISTER && frame.size == MODBUS_WRITE_SINGLE_REGISTER_RESPONSE_LENGTH -> {
                val address = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
                val value = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
                details.append(" | echoed address=${hex16(address)} value=$value (${hex16(value)})")
            }
        }

        return ParsedModbusResponse(frame = frame, crcPass = crcPass, summary = details.toString())
    }

    private fun readRegisterDataHex(frame: ByteArray, byteCount: Int): String {
        if (byteCount <= 0 || frame.size < 3 + byteCount + MODBUS_CRC_LENGTH) return "[]"
        val registers = mutableListOf<String>()
        var index = 3
        while (index + 1 < 3 + byteCount) {
            val value = ((frame[index].toInt() and 0xFF) shl 8) or (frame[index + 1].toInt() and 0xFF)
            registers.add(hex16(value))
            index += 2
        }
        return registers.joinToString(prefix = "[", postfix = "]")
    }

    private fun readRegisterDataDecimals(frame: ByteArray, byteCount: Int): String {
        if (byteCount <= 0 || frame.size < 3 + byteCount + MODBUS_CRC_LENGTH) return "[]"
        val registers = mutableListOf<String>()
        var index = 3
        while (index + 1 < 3 + byteCount) {
            val value = ((frame[index].toInt() and 0xFF) shl 8) or (frame[index + 1].toInt() and 0xFF)
            registers.add(value.toString())
            index += 2
        }
        return registers.joinToString(prefix = "[", postfix = "]")
    }

    private fun hasValidModbusCrc(bytes: ByteArray, length: Int): Boolean {
        if (length < MODBUS_MIN_RESPONSE_LENGTH || length > bytes.size) return false
        val expected = modbusCrc16(bytes.copyOf(length - MODBUS_CRC_LENGTH))
        val actual = (bytes[length - 2].toInt() and 0xFF) or
            ((bytes[length - 1].toInt() and 0xFF) shl 8)
        return expected == actual
    }

    private fun describeModbusFrame(frame: ByteArray): String {
        val function = frame[1].toInt() and 0xFF
        return when {
            function and MODBUS_EXCEPTION_FLAG != 0 && frame.size >= MODBUS_EXCEPTION_RESPONSE_LENGTH -> {
                val code = frame[2].toInt() and 0xFF
                " = exception ${modbusExceptionName(code)}"
            }
            function == MODBUS_READ_HOLDING_REGISTERS && frame.size >= MODBUS_MIN_RESPONSE_LENGTH ->
                " = read holding registers response"
            function == MODBUS_READ_INPUT_REGISTERS && frame.size >= MODBUS_MIN_RESPONSE_LENGTH ->
                " = read input registers response"
            function == MODBUS_WRITE_SINGLE_REGISTER && frame.size == MODBUS_WRITE_SINGLE_REGISTER_RESPONSE_LENGTH ->
                " = write single register response"
            else -> ""
        }
    }

    private fun updateRpmFromModbusFrame(frame: ByteArray, source: String) {
        val rpm = rpmFromModbusFrame(frame) ?: return
        rpmGauge.setRpm(rpm)
        rpmValueText.text = getString(R.string.rpm_value_format, rpm)
        rpmSourceText.text = "$source Modbus RPM value"
    }

    private fun rpmFromModbusFrame(frame: ByteArray): Int? {
        if (frame.size < MODBUS_MIN_RESPONSE_LENGTH) return null

        val function = frame[1].toInt() and 0xFF
        if (function and MODBUS_EXCEPTION_FLAG != 0) return null

        return when (function) {
            MODBUS_READ_HOLDING_REGISTERS -> {
                val byteCount = frame.getOrNull(2)?.toInt()?.and(0xFF) ?: return null
                if (byteCount < 2 || frame.size < 5) return null
                ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            }
            MODBUS_READ_INPUT_REGISTERS -> {
                val byteCount = frame.getOrNull(2)?.toInt()?.and(0xFF) ?: return null
                if (byteCount < 2 || frame.size < 5) return null
                ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            }
            MODBUS_WRITE_SINGLE_REGISTER -> {
                if (frame.size < MODBUS_WRITE_SINGLE_REGISTER_RESPONSE_LENGTH) return null
                ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            }
            else -> null
        }
    }

    private fun modbusExceptionName(code: Int): String {
        return when (code) {
            1 -> "Illegal Function"
            2 -> "Illegal Data Address"
            3 -> "Illegal Data Value"
            4 -> "Slave Device Failure"
            else -> "code $code"
        }
    }

    private fun canWriteHid(): Boolean {
        val activeInterface = claimedInterface
        return outEndpoint != null ||
            (connection != null && activeInterface?.interfaceClass == UsbConstants.USB_CLASS_HID)
    }

    private fun writeHidPacket(
        activeConnection: UsbDeviceConnection,
        packet: ByteArray,
        timeoutMillis: Int,
        label: String
    ): Int {
        val endpoint = outEndpoint
        if (endpoint != null) {
            val sent = activeConnection.bulkTransfer(endpoint, packet, packet.size, timeoutMillis)
            logHidPacket("TX", "ep=${hex8(endpoint.address)}", packet, sent, label)
            return sent
        }

        val activeInterface = claimedInterface
        if (activeInterface?.interfaceClass == UsbConstants.USB_CLASS_HID) {
            val reportId = packet.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val sent = activeConnection.controlTransfer(
                USB_HID_REQUEST_TYPE_OUT,
                USB_HID_SET_REPORT,
                (USB_HID_REPORT_TYPE_OUTPUT shl 8) or reportId,
                activeInterface.id,
                packet,
                packet.size,
                timeoutMillis,
            )
            logHidPacket("TX", "control", packet, sent, "$label SET_REPORT")
            return sent
        }

        appendLog("No writable HID path.")
        return -1
    }

    private fun logHidPacket(
        direction: String,
        transport: String,
        packet: ByteArray,
        transferCount: Int,
        label: String? = null
    ) {
        val timestamp = timestamp()
        val reportId = packet.firstOrNull()?.toInt()?.and(0xFF)
        val reportIdText = reportId?.let(::hex8) ?: "none"
        val status = if (transferCount >= 0) "bytes=$transferCount" else "error=$transferCount"
        val labelText = label?.let { " $it" } ?: ""
        val line = "[$timestamp] $direction$labelText $transport reportId=$reportIdText $status hex=${toHex(packet)}"

        packetMonitorLines.add(line)
        while (packetMonitorLines.size > MAX_PACKET_MONITOR_LINES) {
            packetMonitorLines.removeAt(0)
        }

        if (::packetMonitorText.isInitialized) {
            packetMonitorText.text = packetMonitorLines.joinToString("\n", postfix = "\n")
        }
        if (::packetMonitorScroll.isInitialized) {
            packetMonitorScroll.post {
                packetMonitorScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        appendLog("HID $direction$labelText $transport reportId=$reportIdText $status: ${toHex(packet)}")
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (!::connectionStatusText.isInitialized) return

        if (connected) {
            val device = selectedDevice
            connectionStatusText.text = "Status: Connected ${device?.let { "${hex16(it.vendorId)}:${hex16(it.productId)}" } ?: ""}"
            connectionStatusText.setTextColor(0xFF063B2A.toInt())
            connectionStatusText.background = roundedRect(0xFFE0F6EA.toInt(), 0xFF64B487.toInt(), 12f)
            if (::connectButton.isInitialized) {
                connectButton.text = "Disconnect"
            }
        } else {
            connectionStatusText.text = "Status: Disconnected"
            connectionStatusText.setTextColor(0xFF5C2626.toInt())
            connectionStatusText.background = roundedRect(0xFFFFE7E2.toInt(), 0xFFE19A8D.toInt(), 12f)
            if (::connectButton.isInitialized) {
                connectButton.text = "Connect"
            }
        }
    }

    private fun promptSaveLogs() {
        val filename = "sg100-hid-log-${System.currentTimeMillis()}.txt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(intent, SAVE_HID_LOG_REQUEST_CODE)
    }

    private fun saveLogsToUri(uri: Uri) {
        val content = buildString {
            appendLine("SG-100 HID Debug Log")
            appendLine("Saved: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine()
            appendLine("Packet monitor")
            if (packetMonitorLines.isEmpty()) {
                appendLine("(no HID packets captured)")
            } else {
                packetMonitorLines.forEach(::appendLine)
            }
            appendLine()
            appendLine("Activity log")
            if (logLines.isEmpty()) {
                appendLine("(no activity log entries)")
            } else {
                logLines.forEach(::appendLine)
            }
        }

        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Could not open selected file.")
            appendLog("Saved HID debug log.")
        } catch (error: Exception) {
            appendLog("Could not save HID debug log: ${error.message}")
        }
    }

    private fun appendLog(message: String) {
        val line = "[${timestamp()}] $message"
        logLines.add(line)
        if (::logText.isInitialized) {
            logText.append("$line\n")
        }
        if (::logScroll.isInitialized) {
            logScroll.post {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun timestamp(): String = DateFormat.getTimeInstance().format(Date())

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

    private fun safe(value: String?): String {
        return value?.takeIf { it.isNotBlank() } ?: "Unknown"
    }

    private inner class RpmGaugeView(context: Context) : android.view.View(context) {
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(14).toFloat()
            color = 0xFFD4E1DE.toInt()
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(14).toFloat()
            color = 0xFF169B73.toInt()
        }
        private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(4).toFloat()
            color = 0xFF052322.toInt()
        }
        private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF052322.toInt()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4B6965.toInt()
            textAlign = Paint.Align.CENTER
            textSize = dp(11).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        private val bounds = RectF()
        private var rpm = 0

        fun setRpm(value: Int) {
            rpm = value.coerceIn(0, RPM_GAUGE_MAX)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            val pad = dp(32).toFloat()
            val diameter = minOf(width - pad * 2, height * 1.55f)
            val left = (width - diameter) / 2f
            val top = pad
            bounds.set(left, top, left + diameter, top + diameter)

            canvas.drawArc(bounds, 180f, 180f, false, arcPaint)
            val sweep = 180f * (rpm.toFloat() / RPM_GAUGE_MAX.toFloat())
            canvas.drawArc(bounds, 180f, sweep, false, activePaint)

            drawTicks(canvas, bounds)

            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            val radius = bounds.width() / 2f - dp(24)
            val angle = Math.toRadians((180f + sweep).toDouble())
            val needleX = centerX + kotlin.math.cos(angle).toFloat() * radius
            val needleY = centerY + kotlin.math.sin(angle).toFloat() * radius
            canvas.drawLine(centerX, centerY, needleX, needleY, needlePaint)
            canvas.drawCircle(centerX, centerY, dp(8).toFloat(), hubPaint)

            canvas.drawText("0", bounds.left + dp(14), centerY + dp(24), textPaint)
            canvas.drawText(RPM_GAUGE_MAX.toString(), bounds.right - dp(20), centerY + dp(24), textPaint)
            canvas.drawText("RPM", centerX, centerY + dp(48), textPaint)
        }

        private fun drawTicks(canvas: Canvas, arcBounds: RectF) {
            val centerX = arcBounds.centerX()
            val centerY = arcBounds.centerY()
            val outer = arcBounds.width() / 2f - dp(9)
            val inner = outer - dp(11)
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF89A29E.toInt()
                strokeWidth = dp(2).toFloat()
            }

            for (index in 0..10) {
                val angle = Math.toRadians((180 + index * 18).toDouble())
                val startX = centerX + kotlin.math.cos(angle).toFloat() * inner
                val startY = centerY + kotlin.math.sin(angle).toFloat() * inner
                val endX = centerX + kotlin.math.cos(angle).toFloat() * outer
                val endY = centerY + kotlin.math.sin(angle).toFloat() * outer
                canvas.drawLine(startX, startY, endX, endY, tickPaint)
            }
        }
    }

    private data class ConvertedRegister(
        val functionCode: Int,
        val zeroBasedAddress: Int,
    )

    private data class ModbusReadRequest(
        val functionCode: Int,
        val zeroBasedAddress: Int,
        val frame: ByteArray,
    )

    private data class ParsedModbusResponse(
        val frame: ByteArray,
        val crcPass: Boolean,
        val summary: String,
    )

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.sg100usb.USB_PERMISSION"
        private const val SAVE_HID_LOG_REQUEST_CODE = 1001
        private const val MAX_PACKET_MONITOR_LINES = 200
        private const val DEFAULT_MODBUS_SLAVE_ID = 1
        private const val DEFAULT_MODBUS_READ_REGISTER = 30062
        private const val MODBUS_POLL_TEST_FRAME = "01 03 00 00 00 0A C5 CD"
        private const val MODBUS_POLL_TEST_EXPECTED_REPLY = "01 83 02 C0 F1 = Illegal Data Address"
        private const val RX_TIMEOUT_CHECK_MS = 1800L
        private const val MODBUS_MIN_RESPONSE_LENGTH = 5
        private const val MODBUS_EXCEPTION_FLAG = 0x80
        private const val MODBUS_EXCEPTION_RESPONSE_LENGTH = 5
        private const val MODBUS_RESPONSE_OVERHEAD = 5
        private const val MODBUS_CRC_LENGTH = 2
        private const val MODBUS_READ_HOLDING_REGISTERS = 0x03
        private const val MODBUS_READ_INPUT_REGISTERS = 0x04
        private const val MODBUS_WRITE_SINGLE_REGISTER = 0x06
        private const val MODBUS_READ_REQUEST_LENGTH = 8
        private const val MODBUS_READ_REQUEST_WITHOUT_CRC_LENGTH = 6
        private const val MODBUS_WRITE_SINGLE_REGISTER_RESPONSE_LENGTH = 8
        private const val MODBUS_WRITE_SINGLE_REGISTER_FRAME_LENGTH = 6
        private const val RPM_GAUGE_MAX = 3000
        private const val DEFAULT_USB_SERIAL_BAUD_RATE = 9600
        private const val USB_SERIAL_STOP_BITS_1: Byte = 0
        private const val USB_SERIAL_PARITY_NONE: Byte = 0
        private const val USB_SERIAL_PARITY_ODD: Byte = 1
        private const val USB_SERIAL_PARITY_EVEN: Byte = 2
        private const val USB_SERIAL_DATA_BITS_8: Byte = 8
        private const val DEFAULT_USB_SERIAL_PARITY_LABEL = "Even"
        private const val USB_CDC_REQUEST_TYPE_OUT = 0x21
        private const val USB_CDC_SET_LINE_CODING = 0x20
        private const val USB_CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val USB_CDC_CONTROL_DTR = 0x01
        private const val USB_CDC_CONTROL_RTS = 0x02
        private const val USB_HID_REQUEST_TYPE_OUT = 0x21
        private const val USB_HID_SET_REPORT = 0x09
        private const val USB_HID_REPORT_TYPE_OUTPUT = 0x02
        private const val SG100_VENDOR_NAME = "hue" + "gli"

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
