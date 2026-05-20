@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sg100usb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sg100usb.data.EditableRegister
import com.example.sg100usb.data.GraphPoint
import com.example.sg100usb.data.GraphSeries
import com.example.sg100usb.data.PollingSnapshot
import com.example.sg100usb.protocol.PacketLogEntry
import com.example.sg100usb.protocol.RegisterControl
import com.example.sg100usb.protocol.Sg100Registers
import com.example.sg100usb.protocol.engineSpeedRpm
import com.example.sg100usb.protocol.hex16
import com.example.sg100usb.protocol.pwmOrActuatorPercent
import com.example.sg100usb.protocol.render
import com.example.sg100usb.protocol.requestedSpeedRpm
import com.example.sg100usb.protocol.syncVoltage
import com.example.sg100usb.usb.UsbHidState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Background = Color(0xFFE8EDF1)
private val Header = Color(0xFF17232B)
private val Panel = Color(0xFFF8FAFB)
private val PanelAlt = Color(0xFFEFF3F5)
private val Border = Color(0xFFC7D0D7)
private val Accent = Color(0xFF008064)
private val Cyan = Color(0xFF1D5F99)
private val Amber = Color(0xFFB77900)
private val Danger = Color(0xFFC9363D)
private val Muted = Color(0xFF596872)
private val CardText = Color(0xFF111820)

@Composable
fun Sg100App(viewModel: DashboardViewModel) {
    val usb by viewModel.usbState.collectAsState()
    val polling by viewModel.polling.collectAsState()
    val graph by viewModel.graph.collectAsState()
    val logs by viewModel.packetLog.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var screen by remember { mutableIntStateOf(0) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Background,
            surface = Panel,
            primary = Accent,
            secondary = Cyan,
            tertiary = Amber,
            onBackground = CardText,
            onSurface = CardText,
        )
    ) {
        Scaffold(
            containerColor = Background,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Background)
            ) {
                HeaderBar(
                    usb = usb,
                    online = polling.controllerOnline,
                    onConnect = viewModel::connect,
                    onStart = viewModel::startPolling,
                    onStop = viewModel::stopPolling,
                )
                PcTabStrip(screen = screen, onScreenChange = { screen = it })
                when (screen) {
                    0 -> DashboardScreen(polling, usb, logs)
                    1 -> TrendsScreen(graph, onZoom = viewModel::setGraphZoom)
                    2 -> ConfigurationScreen(settings, viewModel::editRegister, viewModel::writeRegister)
                    3 -> DebugScreen(usb, polling, logs)
                }
            }
        }
    }
}

@Composable
private fun PcTabStrip(screen: Int, onScreenChange: (Int) -> Unit) {
    val tabs = listOf("MONITOR", "TRENDS", "SETTINGS", "DEBUG")
    TabRow(
        selectedTabIndex = screen,
        containerColor = Color(0xFFF3F6F8),
        contentColor = Accent,
    ) {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected = screen == index,
                onClick = { onScreenChange(index) },
                text = {
                    Text(
                        label,
                        color = if (screen == index) Accent else Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                },
            )
        }
    }
}

@Composable
private fun HeaderBar(
    usb: UsbHidState,
    online: Boolean,
    onConnect: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Header)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HT SG-100", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("Speed Governor Service Console", color = Color(0xFFC7D4DA), fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("HID", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Text("04D8:F1BB", color = Color(0xFFC7D4DA), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Led("USB", usb.connected)
            Led("ONLINE", online)
            Led("STRICT HID", usb.connected)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f).height(42.dp),
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("CONNECT", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            Button(
                modifier = Modifier.weight(1f).height(42.dp),
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("START", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            Button(
                modifier = Modifier.weight(0.72f).height(42.dp),
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF344650), contentColor = Color.White),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("STOP", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
        Text(usb.message, color = Color(0xFFC7D4DA), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DashboardScreen(snapshot: PollingSnapshot, usb: UsbHidState, logs: List<PacketLogEntry>) {
    val input = snapshot.input
    val rpm = input?.engineSpeedRpm ?: 0
    val requestedRpm = input?.requestedSpeedRpm ?: 0
    val pwm = input?.pwmOrActuatorPercent ?: 0
    val sync = input?.syncVoltage ?: 0.0
    val current = input?.value(30057) ?: 0
    val position = input?.value(30058) ?: 0

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (input == null) {
            item {
                EmptyStateCard(
                    usb = usb,
                    message = snapshot.error ?: "Connect the SG-100 and press Start to read live Modbus/HID data."
                )
            }
        }
        item {
            LiveStatusPanel(usb = usb, snapshot = snapshot, logs = logs)
        }
        item {
            TelemetryOverview(
                rpm = rpm,
                requestedRpm = requestedRpm,
                pwm = pwm,
                sync = sync,
                current = current,
                position = position,
                firmware = input?.value(30062)?.hex16() ?: "--",
                controller = input?.value(30063)?.hex16() ?: "--",
            )
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 560.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        IndustrialCard(Modifier.weight(1f).height(185.dp)) {
                            Gauge("ENGINE RPM", rpm, 4000, Accent)
                        }
                        IndustrialCard(Modifier.weight(1f).height(185.dp)) {
                            Gauge("PWM / POS", pwm, 100, Amber)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        IndustrialCard(Modifier.weight(1.4f).height(290.dp)) {
                            Gauge("ENGINE RPM", rpm, 4000, Accent)
                        }
                        IndustrialCard(Modifier.weight(1f).height(290.dp)) {
                            Gauge("PWM / POS", pwm, 100, Amber)
                        }
                    }
                }
            }
        }
        item {
            IndustrialCard {
                SectionTitle("Status Panel")
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Led("Overspeed", input?.statusBits?.get("Overspeed occurred") == true)
                    Led("Overcurrent", input?.statusBits?.get("Actuator overcurrent") == true)
                    Led("Gain2", input?.statusBits?.get("Gain2 selection input") == true)
                    Led("Droop", input?.statusBits?.get("Droop input status") == true)
                    Led("USB Connected", usb.connected)
                    Led("Controller Online", snapshot.controllerOnline)
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sg100Registers.input30056Bits.values.forEach { label: String ->
                        Led(label, input?.inputBits?.get(label) == true)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryOverview(
    rpm: Int,
    requestedRpm: Int,
    pwm: Int,
    sync: Double,
    current: Int,
    position: Int,
    firmware: String,
    controller: String,
) {
    IndustrialCard {
        SectionTitle("Live Telemetry")
        Spacer(Modifier.height(8.dp))
        Column {
            TelemetryRow("Engine Speed", "$rpm RPM", "Requested Speed", "$requestedRpm RPM")
            Rule()
            TelemetryRow("PWM / Position", "$pwm %", "Actuator Position", "$position %")
            Rule()
            TelemetryRow("Actuator Current", current.toString(), "Sync Voltage", "${String.format(Locale.US, "%.3f", sync)} V")
            Rule()
            TelemetryRow("Firmware / Type", "$firmware / $controller", "RX Block", "30051..30063")
        }
    }
}

@Composable
private fun TelemetryRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueCell(leftLabel, leftValue, Modifier.weight(1f))
        ValueCell(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun ValueCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = CardText, fontSize = 21.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).background(Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, color = CardText, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE1E7EB)))
}

@Composable
private fun LiveStatusPanel(
    usb: UsbHidState,
    snapshot: PollingSnapshot,
    logs: List<PacketLogEntry>,
) {
    IndustrialCard {
        SectionTitle("Live Activity")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatusBox("USB", if (usb.connected) "CONNECTED" else "DISCONNECTED", usb.connected, Modifier.weight(1f))
            StatusBox("CONTROLLER", if (snapshot.controllerOnline) "ONLINE" else "WAITING", snapshot.controllerOnline, Modifier.weight(1f))
        }
        if (snapshot.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("Last error: ${snapshot.error}", color = Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF121A20), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF2B3A44), RoundedCornerShape(6.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val visibleLogs = logs.takeLast(6)
            if (visibleLogs.isEmpty()) {
                Text("No packets yet. Tap Connect, then Start.", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("TX: 01 04 00 32 00 0D 90 00", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            } else {
                visibleLogs.forEach { entry ->
                    Text(entry.render(), color = Color(0xFFD7FFF1), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusBox(label: String, value: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(if (active) Color(0xFFEAF7F2) else Color(0xFFFFF6E6), RoundedCornerShape(4.dp))
            .border(1.dp, if (active) Color(0xFFB9DDD1) else Color(0xFFE7C98B), RoundedCornerShape(4.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (active) Accent else Amber, CircleShape))
        Column {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
            Text(value, color = CardText, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TrendsScreen(graph: GraphSeries, onZoom: (Float) -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IndustrialCard(Modifier.height(130.dp)) {
            Text("Time Zoom ${"%.1f".format(graph.zoom)}x", color = CardText, fontWeight = FontWeight.Bold)
            Slider(value = graph.zoom, onValueChange = onZoom, valueRange = 1f..6f)
        }
        IndustrialCard(Modifier.weight(1f)) {
            TrendGraph("RPM", graph.rpm, Accent, graph.zoom)
        }
        IndustrialCard(Modifier.weight(1f)) {
            TrendGraph("PWM %", graph.pwm, Amber, graph.zoom)
        }
        IndustrialCard(Modifier.weight(1f)) {
            TrendGraph("Actuator Current", graph.actuatorCurrent, Danger, graph.zoom)
        }
    }
}

@Composable
private fun ConfigurationScreen(
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Holding Register Configuration", color = CardText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Edits are written with Modbus function 06. Start polling reads live input block 30051..30063.", color = Muted)
        }
        items<EditableRegister>(settings.values.sortedBy { it.register.definition.address }) { editable ->
            ConfigRow(editable, onEdit, onWrite)
        }
    }
}

@Composable
private fun DebugScreen(usb: UsbHidState, snapshot: PollingSnapshot, logs: List<PacketLogEntry>) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IndustrialCard {
            Text("USB Device", color = CardText, fontWeight = FontWeight.Bold)
            Text("VID/PID: ${usb.deviceInfo.vendorId.hex16()} / ${usb.deviceInfo.productId.hex16()}", color = Muted)
            Text("Interface: ${usb.deviceInfo.interfaceId}  IN: ${usb.deviceInfo.inEndpoint}  OUT: ${usb.deviceInfo.outEndpoint}", color = Muted)
            Text("Polling: ${"%.1f".format(snapshot.pollingRateHz)} Hz  Error: ${snapshot.error ?: "none"}", color = Muted)
            Text("Input CRC: ${snapshot.input?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "--"}", color = Muted)
            Text("Holding CRC: ${snapshot.holding?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "--"}", color = Muted)
            Spacer(Modifier.height(8.dp))
            Text("Detected USB devices", color = CardText, fontWeight = FontWeight.Bold)
            if (usb.detectedDevices.isEmpty()) {
                Text("No USB devices listed by Android UsbManager yet.", color = Muted)
            } else {
                usb.detectedDevices.forEach { line ->
                    Text(line, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        IndustrialCard(Modifier.weight(1f)) {
            Text("Packet Log", color = CardText, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxHeight()) {
                items<PacketLogEntry>(logs.reversed()) { entry ->
                    Text(entry.render(), color = Color(0xFFD3E4E0), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(
    editable: EditableRegister,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
) {
    val definition = editable.register.definition
    var text by remember(editable.editedValue) { mutableStateOf(editable.editedValue.toString()) }
    IndustrialCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${definition.address}  ${definition.label}", color = CardText, fontWeight = FontWeight.Bold)
                Text("Current ${editable.register.raw} ${definition.unit}   ${if (editable.dirty) "modified" else "synced"}", color = Muted)
                if (definition.max <= 6000 && definition.control != RegisterControl.SWITCH) {
                    Slider(
                        value = editable.editedValue.toFloat(),
                        onValueChange = { onEdit(definition.address, it.toInt()) },
                        valueRange = definition.min.toFloat()..definition.max.toFloat(),
                    )
                }
            }
            if (definition.control == RegisterControl.SWITCH) {
                Switch(checked = editable.editedValue != 0, onCheckedChange = { onEdit(definition.address, if (it) 1 else 0) })
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter(Char::isDigit)
                        text.toIntOrNull()?.let { value -> onEdit(definition.address, value) }
                    },
                    modifier = Modifier.width(110.dp),
                    singleLine = true,
                    label = { Text("Value") },
                )
            }
            Button(
                onClick = { onWrite(definition.address, editable.editedValue) },
                enabled = editable.dirty,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) {
                Text("Write")
            }
        }
    }
}

@Composable
private fun EmptyStateCard(usb: UsbHidState, message: String) {
    IndustrialCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(74.dp)
                    .background(Color(0xFFEAF4F1), CircleShape)
                    .border(2.dp, if (usb.connected) Accent else Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (usb.connected) "HID" else "USB", color = if (usb.connected) Accent else Amber, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("Waiting for SG-100 live data", color = CardText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(message, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Start sends TX: 01 04 00 32 00 0D 90 00", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Any, unit: String, modifier: Modifier = Modifier.width(178.dp)) {
    Card(
        modifier = modifier.height(82.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text("$value $unit".trim(), color = CardText, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Led(label: String, active: Boolean) {
    Row(
        Modifier
            .border(1.dp, if (active) Accent else Border, RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFFE6F6F1) else Color(0xFFF4F6F7), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(9.dp).background(if (active) Accent else Color(0xFF9AA8AF), CircleShape))
        Text(label, color = if (active) CardText else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IndustrialCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Panel, RoundedCornerShape(6.dp))
            .border(1.dp, Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
        content = content,
    )
}

@Composable
private fun Gauge(label: String, value: Int, max: Int, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val size = min(size.width, size.height * 1.6f) - stroke * 2
            val topLeft = Offset((this.size.width - size) / 2f, stroke)
            drawArc(Color(0xFFD4DEE4), 180f, 180f, false, topLeft, Size(size, size), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 180f, 180f * (value.coerceIn(0, max).toFloat() / max), false, topLeft, Size(size, size), style = Stroke(stroke, cap = StrokeCap.Round))
            val center = Offset(topLeft.x + size / 2f, topLeft.y + size / 2f)
            val angle = Math.toRadians((180 + 180f * value.coerceIn(0, max) / max).toDouble())
            val radius = size / 2f - stroke
            drawLine(CardText, center, Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(CardText, 8.dp.toPx(), center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), color = CardText, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TrendGraph(title: String, points: List<GraphPoint>, color: Color, zoom: Float) {
    Column {
        Text(title, color = CardText, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxSize().padding(top = 8.dp)) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF13211E), Color(0xFF0B1412))))
            val visible = points.takeLast((240 / zoom).toInt().coerceAtLeast(20))
            if (visible.size < 2) return@Canvas
            val maxValue = visible.maxOf { it.value }.coerceAtLeast(1f)
            val minValue = visible.minOf { it.value }
            val range = (maxValue - minValue).coerceAtLeast(1f)
            val stepX = size.width / (visible.size - 1)
            for (index in 1 until visible.size) {
                val a = visible[index - 1]
                val b = visible[index]
                val x1 = stepX * (index - 1)
                val y1 = size.height - ((a.value - minValue) / range) * size.height
                val x2 = stepX * index
                val y2 = size.height - ((b.value - minValue) / range) * size.height
                drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
