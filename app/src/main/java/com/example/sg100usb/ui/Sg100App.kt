@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sg100usb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sg100usb.data.EditableRegister
import com.example.sg100usb.data.WriteStatus
import com.example.sg100usb.data.GraphPoint
import com.example.sg100usb.data.GraphSeries
import com.example.sg100usb.data.PollingSnapshot
import com.example.sg100usb.format.EngineeringFormats
import com.example.sg100usb.format.EngineeringValue
import com.example.sg100usb.protocol.PacketLogEntry
import com.example.sg100usb.protocol.RegisterControl
import com.example.sg100usb.protocol.Sg100Registers
import com.example.sg100usb.protocol.engineSpeedRpm
import com.example.sg100usb.protocol.hex16
import com.example.sg100usb.usb.UsbHidState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val PageBg = Color(0xFF070B10)
private val HeaderBg = Color(0xFF0B1118)
private val PanelBg = Color(0xFF101821)
private val PanelBg2 = Color(0xFF141F2B)
private val GraphBg = Color(0xFF0A1118)
private val BorderClr = Color(0xFF223140)
private val TrackClr = Color(0xFF263343)
private val BlueA = Color(0xFF20A7FF)
private val CyanA = Color(0xFF6DEBFF)
private val GreenA = Color(0xFF31D475)
private val AmberA = Color(0xFFFFB33D)
private val RedA = Color(0xFFFF4E59)
private val TextMain = Color(0xFFF2F7FF)
private val TextLabel = Color(0xFFA7B7C8)
private val TextMuted = Color(0xFF66778A)

private val navItems = listOf(
    NavItem("Dashboard", NavGlyph.Dashboard),
    NavItem("Trends", NavGlyph.Trends),
    NavItem("Configure", NavGlyph.Configure),
    NavItem("Diagnostics", NavGlyph.Diagnostics),
)

private data class NavItem(val label: String, val glyph: NavGlyph)

private enum class NavGlyph {
    Dashboard,
    Trends,
    Configure,
    Diagnostics,
}

private data class StatusItem(
    val label: String,
    val active: Boolean,
    val activeColor: Color,
)

@Composable
fun Sg100App(viewModel: DashboardViewModel) {
    var screen by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = PageBg,
            surface = PanelBg,
            primary = BlueA,
            secondary = GreenA,
            tertiary = AmberA,
            error = RedA,
            onBackground = TextMain,
            onSurface = TextMain,
        )
    ) {
        Scaffold(
            containerColor = PageBg,
            bottomBar = { BottomNav(screen) { screen = it } },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(PageBg)
            ) {
                AppHeader(viewModel)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PageBg)
                ) {
                    when (screen) {
                        0 -> {
                            val usb by viewModel.usbState.collectAsState()
                            val polling by viewModel.polling.collectAsState()
                            val graph by viewModel.graph.collectAsState()
                            MonitorScreen(polling, usb, graph)
                        }
                        1 -> {
                            val graph by viewModel.graph.collectAsState()
                            TrendsScreen(graph, onZoom = viewModel::setGraphZoom)
                        }
                        2 -> {
                            val settings by viewModel.settings.collectAsState()
                            val resettingDefaults by viewModel.resettingDefaults.collectAsState()
                            ConfigScreen(
                                settings = settings,
                                onEdit = viewModel::editRegister,
                                onWrite = viewModel::writeRegister,
                                onClearStatus = viewModel::clearWriteStatus,
                                onResetDefaults = viewModel::resetToDefaults,
                                isResettingDefaults = resettingDefaults,
                            )
                        }
                        3 -> {
                            val usb by viewModel.usbState.collectAsState()
                            val polling by viewModel.polling.collectAsState()
                            val logs by viewModel.packetLog.collectAsState()
                            DebugScreen(usb, polling, logs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(viewModel: DashboardViewModel) {
    val usb by viewModel.usbState.collectAsState()
    val polling by viewModel.polling.collectAsState()
    val input = polling.input
    val connected = usb.connected
    val controllerOnline = polling.controllerOnline
    val statusColor = when {
        connected && controllerOnline -> GreenA
        connected -> AmberA
        else -> TextMuted
    }
    val statusText = when {
        connected && controllerOnline -> "Online"
        connected -> "USB linked"
        usb.permissionPending -> "Permission"
        else -> "Offline"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(HeaderBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "SG-100 Speed Governor",
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "HID telemetry controller",
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectionPill(statusText, statusColor, controllerOnline)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderChip("USB", if (connected) "HID active" else "No link", if (connected) GreenA else TextMuted)
            HeaderChip("FW", formatFirmware(input?.value(30062)), BlueA)
            HeaderChip("CTRL", formatControllerType(input?.value(30063)), CyanA)
            HeaderChip("POLL", if (polling.pollingRateHz > 0f) "${formatOne(polling.pollingRateHz)} Hz" else "Idle", AmberA)
        }
        Spacer(Modifier.height(10.dp))
        AutoStatusBar(
            connected = connected,
            controllerOnline = controllerOnline,
            permissionPending = usb.permissionPending,
            pollingHz = polling.pollingRateHz,
        )
    }
}

@Composable
private fun ConnectionPill(text: String, color: Color, pulsing: Boolean) {
    val pulse = rememberPulse(pulsing)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (pulsing) 0.18f + pulse * 0.08f else 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp + (pulse * 2).dp)
                .background(color, CircleShape)
        )
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderChip(label: String, value: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1720))
            .border(1.dp, BorderClr, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AutoStatusBar(
    connected: Boolean,
    controllerOnline: Boolean,
    permissionPending: Boolean,
    pollingHz: Float,
) {
    val (text, color, pulsing) = when {
        connected && controllerOnline ->
            Triple("Connected to SG100  ·  ${formatOne(pollingHz)} Hz", GreenA, false)
        connected ->
            Triple("SG100 linked — starting telemetry…", AmberA, true)
        permissionPending ->
            Triple("Tap OK to allow USB access", AmberA, true)
        else ->
            Triple("Searching for SG100…", TextMuted, true)
    }
    val pulse = rememberPulse(pulsing)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.07f + pulse * 0.05f))
            .border(1.dp, color.copy(alpha = 0.22f + pulse * 0.1f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size((7 + pulse * 2).dp)
                .background(color.copy(alpha = 0.8f + pulse * 0.2f), CircleShape)
        )
        Text(
            text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(HeaderBg)
            .border(1.dp, BorderClr)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        navItems.forEachIndexed { index, item ->
            val active = selected == index
            val bg by animateColorAsState(
                targetValue = if (active) BlueA.copy(alpha = 0.16f) else Color.Transparent,
                animationSpec = tween(220),
                label = "navBg",
            )
            Column(
                Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NavGlyphCanvas(item.glyph, if (active) BlueA else TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    color = if (active) TextMain else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NavGlyphCanvas(glyph: NavGlyph, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        when (glyph) {
            NavGlyph.Dashboard -> {
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(2.dp.toPx(), 4.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(color, center, Offset(size.width * 0.78f, size.height * 0.38f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color, 2.dp.toPx(), center)
            }
            NavGlyph.Trends -> {
                val y0 = size.height * 0.72f
                drawLine(color.copy(alpha = 0.35f), Offset(0f, y0), Offset(size.width, y0), strokeWidth = 1.dp.toPx())
                drawLine(color, Offset(1.dp.toPx(), size.height * 0.68f), Offset(size.width * 0.35f, size.height * 0.42f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.35f, size.height * 0.42f), Offset(size.width * 0.66f, size.height * 0.55f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.66f, size.height * 0.55f), Offset(size.width - 1.dp.toPx(), size.height * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            NavGlyph.Configure -> {
                drawCircle(color, 6.dp.toPx(), center, style = Stroke(stroke))
                drawLine(color, Offset(center.x, 0f), Offset(center.x, 4.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(center.x, size.height - 4.dp.toPx()), Offset(center.x, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(0f, center.y), Offset(4.dp.toPx(), center.y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width - 4.dp.toPx(), center.y), Offset(size.width, center.y), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            NavGlyph.Diagnostics -> {
                drawCircle(color, 8.dp.toPx(), center, style = Stroke(stroke))
                drawLine(color, Offset(center.x, 5.dp.toPx()), Offset(center.x, center.y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color, 1.5.dp.toPx(), Offset(center.x, size.height * 0.72f))
            }
        }
    }
}

@Composable
private fun MonitorScreen(snapshot: PollingSnapshot, usb: UsbHidState, graph: GraphSeries) {
    val input = snapshot.input
    val rpmEv = EngineeringFormats.rpm(input?.engineSpeedRpm ?: 0)
    val pwmEv = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER)
    val reqEv = EngineeringFormats.register(input, Sg100Registers.REQUESTED_SPEED_REGISTER)
    val syncEv = EngineeringFormats.register(input, Sg100Registers.SYNC_VOLTAGE_REGISTER)
    val currEv = EngineeringFormats.register(input, 30057)
    val posEv = EngineeringFormats.register(input, 30058)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                DashboardTopStrip(snapshot, usb)
            }
            item {
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroRpmCard(rpmEv, reqEv, Modifier.weight(1.1f).height(280.dp))
                        Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            PwmGaugeCard(pwmEv, Modifier.fillMaxWidth().height(133.dp))
                            PositionCard(posEv, Modifier.fillMaxWidth().height(133.dp))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroRpmCard(rpmEv, reqEv, Modifier.fillMaxWidth().heightIn(min = 300.dp))
                        PwmGaugeCard(pwmEv, Modifier.fillMaxWidth().height(160.dp))
                        PositionCard(posEv, Modifier.fillMaxWidth().height(136.dp))
                    }
                }
            }
            item {
                ResponsiveTelemetryGrid(reqEv, pwmEv, syncEv, currEv, posEv, wide)
            }
            item {
                StatusSection(statusItems(snapshot, usb))
            }
            item {
                DashboardTrends(graph)
            }
        }
    }
}

@Composable
private fun DashboardTopStrip(snapshot: PollingSnapshot, usb: UsbHidState) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HealthChip("Connection", if (usb.connected) "Connected" else "Disconnected", usb.connected, GreenA)
        HealthChip("Controller", if (snapshot.controllerOnline) "Running" else "No data", snapshot.controllerOnline, GreenA)
        HealthChip("Input CRC", snapshot.input?.crc?.let { if (it.ok) "Pass" else "Fail" } ?: "Pending", snapshot.input?.crc?.ok == true, GreenA)
        HealthChip("Last Error", snapshot.error ?: "None", snapshot.error != null, AmberA)
    }
}

@Composable
private fun HealthChip(label: String, value: String, active: Boolean, activeColor: Color) {
    val tint = if (active) activeColor else TextMuted
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) tint.copy(alpha = 0.14f) else Color(0xFF0D151D))
            .border(1.dp, if (active) tint.copy(alpha = 0.35f) else BorderClr, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusGlyph(active, tint)
        Column {
            Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeroRpmCard(rpm: EngineeringValue, requested: EngineeringValue, modifier: Modifier = Modifier) {
    TelemetryPanel(
        modifier,
        accent = BlueA,
        brush = Brush.verticalGradient(listOf(Color(0xFF122D42), PanelBg)),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("ENGINE SPEED", color = TextLabel, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text("Live governor telemetry", color = TextMuted, fontSize = 11.sp)
            }
            LiveBadge("LIVE", BlueA)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            CircularGauge(
                value = rpm.displayValue,
                max = 4000.0,
                tint = BlueA,
                modifier = Modifier.fillMaxSize(),
                stroke = 18f,
                sweep = 250f,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    splitValue(rpm.text).first,
                    color = TextMain,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("RPM", color = BlueA, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ValuePair("Requested", requested.text, BlueA)
            ValuePair("Range", "0-4000 RPM", TextMuted)
        }
    }
}

@Composable
private fun PwmGaugeCard(ev: EngineeringValue, modifier: Modifier = Modifier) {
    TelemetryPanel(modifier, accent = AmberA) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PWM / ACTUATOR OUTPUT", color = TextLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(ev.text, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                CircularGauge(
                    value = ev.displayValue.coerceAtMost(100.0),
                    max = 100.0,
                    tint = AmberA,
                    modifier = Modifier.fillMaxSize(),
                    stroke = 13f,
                    sweep = 270f,
                )
                Text(ev.text, color = AmberA, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PositionCard(ev: EngineeringValue, modifier: Modifier = Modifier) {
    val progress = (ev.displayValue / 100.0).coerceIn(0.0, 1.0).toFloat()
    TelemetryPanel(modifier, accent = GreenA) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("ACTUATOR POSITION", color = TextLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(ev.text, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            if (ev.outOfRange || ev.raw > 100) {
                Text("RAW ${ev.raw}", color = AmberA, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(14.dp))
        LinearTelemetryBar(progress, GreenA)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "25", "50", "75", "100%").forEach {
                Text(it, color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ResponsiveTelemetryGrid(
    requested: EngineeringValue,
    pwm: EngineeringValue,
    sync: EngineeringValue,
    current: EngineeringValue,
    position: EngineeringValue,
    wide: Boolean,
) {
    val cards = listOf(
        Triple("Requested Speed", requested.text, BlueA),
        Triple("PWM / Actuator Output", pwm.text, AmberA),
        Triple("Sync Voltage", sync.text, CyanA),
        Triple("Actuator Current", current.text, if (current.outOfRange) RedA else GreenA),
        Triple("Actuator Position", position.text, GreenA),
    )
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { card ->
                CompactTelemetryCard(card.first, card.second, card.third, Modifier.weight(1f).height(112.dp))
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.chunked(2).forEach { rowCards ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCards.forEach { card ->
                        CompactTelemetryCard(card.first, card.second, card.third, Modifier.weight(1f).height(116.dp))
                    }
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CompactTelemetryCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    TelemetryPanel(modifier, accent = tint) {
        Text(label.uppercase(Locale.US), color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 2)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = tint,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusSection(items: List<StatusItem>) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeading("Device Status", "Live I/O and governor state")
        Spacer(Modifier.height(10.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                StatusChip(item)
            }
        }
    }
}

@Composable
private fun StatusChip(item: StatusItem) {
    val bg by animateColorAsState(
        targetValue = if (item.active) item.activeColor.copy(alpha = 0.16f) else Color(0xFF0F171F),
        animationSpec = tween(260),
        label = "statusBg",
    )
    val border by animateColorAsState(
        targetValue = if (item.active) item.activeColor.copy(alpha = 0.42f) else BorderClr,
        animationSpec = tween(260),
        label = "statusBorder",
    )
    Row(
        Modifier
            .widthIn(min = 150.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatusGlyph(item.active, if (item.active) item.activeColor else TextMuted)
        Column {
            Text(item.label, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (item.active) "Active" else "Inactive", color = if (item.active) item.activeColor else TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DashboardTrends(graph: GraphSeries) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeading("Telemetry Trends", "Recent live samples")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTrend("Engine RPM", graph.rpm, BlueA, graph.zoom, Modifier.fillMaxWidth().height(96.dp))
            MiniTrend("PWM / Actuator", graph.pwm, AmberA, graph.zoom, Modifier.fillMaxWidth().height(96.dp))
            MiniTrend("Actuator Current", graph.actuatorCurrent, RedA, graph.zoom, Modifier.fillMaxWidth().height(96.dp))
        }
    }
}

@Composable
private fun CircularGauge(
    value: Double,
    max: Double,
    tint: Color,
    modifier: Modifier = Modifier,
    stroke: Float = 14f,
    sweep: Float = 250f,
) {
    val target = (value / max).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "gauge",
    )
    Canvas(modifier) {
        val strokePx = stroke.dp.toPx()
        val diameter = min(size.width, size.height) - strokePx * 2f
        val left = (size.width - diameter) / 2f
        val top = (size.height - diameter) / 2f
        val arcSize = Size(diameter, diameter)
        val start = 90f + (360f - sweep) / 2f
        drawArc(
            TrackClr,
            start,
            sweep,
            false,
            Offset(left, top),
            arcSize,
            style = Stroke(strokePx, cap = StrokeCap.Round),
        )
        val gaugeColor = when {
            animated < 0.72f -> tint
            animated < 0.9f -> AmberA
            else -> RedA
        }
        if (animated > 0f) {
            drawArc(
                gaugeColor,
                start,
                sweep * animated,
                false,
                Offset(left, top),
                arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round),
            )
        }
        val angle = Math.toRadians((start + sweep * animated).toDouble())
        val radius = diameter / 2f
        val center = Offset(left + radius, top + radius)
        val dot = Offset(
            center.x + cos(angle).toFloat() * radius,
            center.y + sin(angle).toFloat() * radius,
        )
        drawCircle(gaugeColor.copy(alpha = 0.2f), strokePx * 0.75f, dot)
        drawCircle(gaugeColor, strokePx * 0.38f, dot)
    }
}

@Composable
private fun LinearTelemetryBar(progress: Float, tint: Color) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "bar",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(50))
            .background(TrackClr)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(tint.copy(alpha = 0.72f), tint, CyanA.copy(alpha = 0.8f))
                    )
                )
        )
    }
}

@Composable
private fun StatusGlyph(active: Boolean, color: Color) {
    val pulse = rememberPulse(active)
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(18.dp)
                .background(color.copy(alpha = if (active) 0.13f + pulse * 0.14f else 0.09f), CircleShape)
        )
        Box(
            Modifier
                .size(if (active) 8.dp + (pulse * 2).dp else 7.dp)
                .background(color, CircleShape)
        )
    }
}

@Composable
private fun LiveBadge(label: String, tint: Color) {
    val pulse = rememberPulse(true)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f + pulse * 0.08f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp + (pulse * 2).dp).background(tint, CircleShape))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ValuePair(label: String, value: String, tint: Color) {
    Column {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TelemetryPanel(
    modifier: Modifier = Modifier,
    accent: Color = BlueA,
    brush: Brush = Brush.verticalGradient(listOf(PanelBg2, PanelBg)),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(brush)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(50))
                .background(BlueA)
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MiniTrend(
    title: String,
    points: List<GraphPoint>,
    color: Color,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    TelemetryPanel(modifier, accent = color) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title.uppercase(Locale.US), color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Black)
            points.lastOrNull()?.let {
                Text(formatTwo(it.value), color = color, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(GraphBg)
        ) {
            drawTrendLine(points, zoom, color, size, strokeDp = 2f)
        }
    }
}

@Composable
private fun TrendsScreen(graph: GraphSeries, onZoom: (Float) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TelemetryPanel(Modifier.fillMaxWidth(), accent = BlueA) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text("TREND ZOOM", color = TextLabel, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Text("${formatOne(graph.zoom)}x", color = BlueA, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = graph.zoom,
                        onValueChange = onZoom,
                        valueRange = 1f..6f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = BlueA,
                            activeTrackColor = BlueA,
                            inactiveTrackColor = TrackClr,
                        ),
                    )
                }
            }
        }
        item { FullTrend("Engine RPM", graph.rpm, BlueA, graph.zoom, Modifier.fillMaxWidth().height(230.dp)) }
        item { FullTrend("PWM / Actuator", graph.pwm, AmberA, graph.zoom, Modifier.fillMaxWidth().height(230.dp)) }
        item { FullTrend("Actuator Current", graph.actuatorCurrent, RedA, graph.zoom, Modifier.fillMaxWidth().height(230.dp)) }
    }
}

@Composable
private fun FullTrend(
    title: String,
    points: List<GraphPoint>,
    color: Color,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    TelemetryPanel(modifier, accent = color) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("Live graph stream", color = TextMuted, fontSize = 11.sp)
            }
            points.lastOrNull()?.let {
                Text(formatTwo(it.value), color = color, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(GraphBg)
        ) {
            drawTrendLine(points, zoom, color, size, strokeDp = 2.5f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrendLine(
    points: List<GraphPoint>,
    zoom: Float,
    color: Color,
    canvasSize: Size,
    strokeDp: Float,
) {
    if (canvasSize.height < 2f) return
    val visible = min(points.size, (240 / zoom).toInt().coerceAtLeast(10))
    if (visible < 2) {
        val centerY = canvasSize.height * 0.5f
        drawLine(
            BorderClr,
            Offset(0f, centerY),
            Offset(canvasSize.width, centerY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        return
    }
    val start = points.size - visible
    var maxV = points[start].value
    var minV = points[start].value
    for (i in start + 1 until points.size) {
        val v = points[i].value
        if (v > maxV) maxV = v
        if (v < minV) minV = v
    }
    maxV = maxV.coerceAtLeast(minV + 1f)
    val range = (maxV - minV).coerceAtLeast(1f)
    val stepX = canvasSize.width / (visible - 1)
    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
        val y = canvasSize.height * (1f - frac)
        drawLine(BorderClr.copy(alpha = 0.5f), Offset(0f, y), Offset(canvasSize.width, y), strokeWidth = 0.7.dp.toPx())
    }
    for (i in 1 until visible) {
        val a = points[start + i - 1]
        val b = points[start + i]
        val x1 = stepX * (i - 1)
        val y1 = canvasSize.height - ((a.value - minV) / range) * canvasSize.height
        val x2 = stepX * i
        val y2 = canvasSize.height - ((b.value - minV) / range) * canvasSize.height
        drawLine(
            color,
            Offset(x1, y1),
            Offset(x2, y2),
            strokeWidth = strokeDp.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ConfigScreen(
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
    onResetDefaults: () -> Unit,
    isResettingDefaults: Boolean,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = PanelBg,
            titleContentColor = TextMain,
            textContentColor = TextLabel,
            title = {
                Text("Reset to Factory Defaults?", fontWeight = FontWeight.Black)
            },
            text = {
                Text(
                    "This will write the factory default value to all 26 writable holding registers " +
                    "on the SG-100. Current configuration will be overwritten.\n\n" +
                    "Make sure the device is connected before proceeding.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetDefaults()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedA, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Reset All", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading("Configure", "Holding registers — connect first to load device values")
        }
        item {
            ResetDefaultsButton(
                onClick = { showResetDialog = true },
                isResetting = isResettingDefaults,
            )
        }
        if (settings.isEmpty()) {
            item {
                TelemetryPanel(Modifier.fillMaxWidth(), accent = TextMuted) {
                    Text(
                        "No register values loaded. Press Connect to read current device settings.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
        items(settings.values.sortedBy { it.register.definition.address }) { editable ->
            ConfigRow(editable, onEdit, onWrite, onClearStatus)
        }
    }
}

@Composable
private fun ResetDefaultsButton(onClick: () -> Unit, isResetting: Boolean) {
    val pulse = rememberPulse(isResetting)
    val borderColor = if (isResetting) AmberA.copy(alpha = 0.4f + pulse * 0.3f) else RedA.copy(alpha = 0.35f)
    val bgColor = if (isResetting) AmberA.copy(alpha = 0.08f + pulse * 0.05f) else RedA.copy(alpha = 0.08f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .then(if (!isResetting) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Warning dot
        Box(
            Modifier
                .size((8 + pulse * 3).dp)
                .background(
                    if (isResetting) AmberA.copy(alpha = 0.8f + pulse * 0.2f) else RedA,
                    CircleShape,
                )
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (isResetting) "Resetting to factory defaults…" else "Reset to Factory Defaults",
                color = if (isResetting) AmberA else RedA,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (isResetting)
                    "Writing default values to all registers, please wait"
                else
                    "Overwrites all 26 holding registers with factory values",
                color = TextMuted,
                fontSize = 11.sp,
            )
        }
        if (!isResetting) {
            Text(
                "RESET",
                color = RedA,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ConfigRow(
    editable: EditableRegister,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
) {
    val def = editable.register.definition
    var text by remember(editable.editedValue) { mutableStateOf(editable.editedValue.toString()) }

    val accentColor = when (editable.writeStatus) {
        WriteStatus.Success -> GreenA
        WriteStatus.Error -> RedA
        WriteStatus.Pending -> AmberA
        WriteStatus.Idle -> if (editable.dirty) AmberA else BlueA
    }

    // Auto-clear success status after 3 seconds
    androidx.compose.runtime.LaunchedEffect(editable.writeStatus) {
        if (editable.writeStatus == WriteStatus.Success) {
            kotlinx.coroutines.delay(3000)
            onClearStatus(def.address)
        }
    }

    TelemetryPanel(Modifier.fillMaxWidth(), accent = accentColor) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(def.label, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(
                    "${def.address}  Device value: ${editable.register.raw}  Default: ${def.default} ${def.unit}".trim(),
                    color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            when (editable.writeStatus) {
                WriteStatus.Pending -> Text("SENDING…", color = AmberA, fontSize = 10.sp, fontWeight = FontWeight.Black)
                WriteStatus.Success -> Text("WRITE OK", color = GreenA, fontSize = 10.sp, fontWeight = FontWeight.Black)
                WriteStatus.Error -> Text("FAILED", color = RedA, fontSize = 10.sp, fontWeight = FontWeight.Black)
                WriteStatus.Idle -> if (editable.dirty) Text("MODIFIED", color = AmberA, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
        if (editable.writeStatus == WriteStatus.Error && editable.writeError != null) {
            Spacer(Modifier.height(4.dp))
            Text(editable.writeError, color = RedA, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (def.note.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(def.note, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (def.control == RegisterControl.SLIDER && def.max > 0) {
            Slider(
                value = editable.editedValue.toFloat(),
                onValueChange = { onEdit(def.address, it.toInt()) },
                valueRange = def.min.toFloat()..def.max.toFloat(),
                enabled = editable.writeStatus != WriteStatus.Pending,
                colors = SliderDefaults.colors(
                    thumbColor = BlueA,
                    activeTrackColor = BlueA,
                    inactiveTrackColor = TrackClr,
                ),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (def.control == RegisterControl.SWITCH) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Switch(
                        checked = editable.editedValue != 0,
                        onCheckedChange = { onEdit(def.address, if (it) 1 else 0) },
                        enabled = editable.writeStatus != WriteStatus.Pending,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextMain,
                            checkedTrackColor = BlueA,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = TrackClr,
                        ),
                    )
                    Text(
                        if (editable.editedValue != 0) "Enabled" else "Disabled",
                        color = TextLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter(Char::isDigit)
                        text.toIntOrNull()?.let { v -> onEdit(def.address, v) }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = editable.writeStatus != WriteStatus.Pending,
                    label = { Text("Value (${def.min}–${def.max})", color = TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueA,
                        unfocusedBorderColor = BorderClr,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        cursorColor = BlueA,
                        focusedLabelColor = BlueA,
                        unfocusedLabelColor = TextMuted,
                    ),
                )
            }
            Button(
                onClick = { onWrite(def.address, editable.editedValue) },
                enabled = editable.dirty && editable.writeStatus != WriteStatus.Pending && def.writable,
                modifier = Modifier.height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (editable.writeStatus) {
                        WriteStatus.Success -> GreenA
                        WriteStatus.Error -> RedA
                        else -> BlueA
                    },
                    contentColor = Color.White,
                    disabledContainerColor = TrackClr,
                    disabledContentColor = TextMuted,
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    when (editable.writeStatus) {
                        WriteStatus.Pending -> "…"
                        WriteStatus.Success -> "OK"
                        WriteStatus.Error -> "Retry"
                        WriteStatus.Idle -> "Write"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun DebugScreen(usb: UsbHidState, snapshot: PollingSnapshot, logs: List<PacketLogEntry>) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeading("Diagnostics", "USB, CRC, communication log, and register reference")
        }
        item {
            TelemetryPanel(Modifier.fillMaxWidth(), accent = BlueA) {
                Text("USB Device", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                MonoRow("VID / PID", "${usb.deviceInfo.vendorId.hex16()} / ${usb.deviceInfo.productId.hex16()}")
                MonoRow("Interface", "${usb.deviceInfo.interfaceId}  IN ${usb.deviceInfo.inEndpoint}  OUT ${usb.deviceInfo.outEndpoint}")
                MonoRow("Poll", "${formatOne(snapshot.pollingRateHz)} Hz")
                MonoRow("Input CRC", snapshot.input?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "PENDING")
                MonoRow("Holding CRC", snapshot.holding?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "PENDING")
                MonoRow("Error", snapshot.error ?: "none")
                if (usb.detectedDevices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Detected USB devices", color = TextLabel, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    usb.detectedDevices.forEach { line ->
                        Text(line, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            TelemetryPanel(Modifier.fillMaxWidth().height(420.dp), accent = GreenA) {
                Text("Communication Log", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxHeight()) {
                    if (logs.isEmpty()) {
                        item {
                            Text("No communication yet.", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    } else {
                        items(logs, key = { it.id }) { entry ->
                            val color = when (entry.direction) {
                                "TX" -> BlueA
                                "RX" -> GreenA
                                else -> TextMuted
                            }
                            Text(entry.displayText, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        item {
            RegisterReferenceSection()
        }
    }
}

@Composable
private fun RegisterReferenceSection() {
    Column(Modifier.fillMaxWidth()) {
        SectionHeading("Register Reference", "Full SG-100 Modbus register map")
        Spacer(Modifier.height(10.dp))
        TelemetryPanel(Modifier.fillMaxWidth(), accent = CyanA) {
            Text("Input Registers (Read Only)", color = CyanA, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            com.example.sg100usb.protocol.Sg100Registers.input.values
                .sortedBy { it.address }
                .forEach { def ->
                    RegisterRefRow(def)
                }
        }
        Spacer(Modifier.height(10.dp))
        TelemetryPanel(Modifier.fillMaxWidth(), accent = AmberA) {
            Text("Holding Registers (Read / Write)", color = AmberA, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            com.example.sg100usb.protocol.Sg100Registers.holding.values
                .sortedBy { it.address }
                .forEach { def ->
                    RegisterRefRow(def)
                }
        }
    }
}

@Composable
private fun RegisterRefRow(def: com.example.sg100usb.protocol.RegisterDefinition) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                def.address.toString(),
                color = CyanA,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(52.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(def.label, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (def.writable) {
                        Text("W", color = AmberA, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
                val meta = buildString {
                    if (def.unit.isNotEmpty()) append(def.unit).append("  ")
                    if (def.min != 0 || def.max != 65535) append("${def.min}–${def.max}  ")
                    if (def.default != 0) append("default: ${def.default}")
                }.trim()
                if (meta.isNotEmpty()) {
                    Text(meta, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (def.note.isNotEmpty()) {
                    Text(def.note, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderClr.copy(alpha = 0.5f)))
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(92.dp), textAlign = TextAlign.End)
        Text(value, color = TextLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private fun statusItems(snapshot: PollingSnapshot, usb: UsbHidState): List<StatusItem> {
    val input = snapshot.input
    val speed2 = input?.inputBits?.get("Speed2 input") == true
    val speed3 = input?.inputBits?.get("Speed3 input") == true
    val gainInput = input?.inputBits?.get("Gain input") == true
    val pickupSensorPresent = (input?.engineSpeedRpm ?: 0) > 1
    return listOf(
        StatusItem("Connection", usb.connected, GreenA),
        StatusItem("Controller", snapshot.controllerOnline, GreenA),
        StatusItem("Overspeed", input?.statusBits?.get("Overspeed occurred") == true, RedA),
        StatusItem("Over Current", input?.statusBits?.get("Actuator overcurrent") == true, RedA),
        StatusItem("Droop", input?.statusBits?.get("Droop input status") == true, AmberA),
        StatusItem("Gain 2", input?.statusBits?.get("Gain2 selection input") == true, AmberA),
        StatusItem("Function Key", input?.inputBits?.get("Fn key") == true, BlueA),
        StatusItem("Plus Key", input?.inputBits?.get("Plus key") == true, BlueA),
        StatusItem("Minus Key", input?.inputBits?.get("Minus key") == true, BlueA),
        StatusItem("Idle Input", input?.inputBits?.get("Idle input") == true, GreenA),
        StatusItem("Pickup Sensor Input", pickupSensorPresent, GreenA),
        StatusItem("Speed Inputs", speed2 || speed3 || gainInput, CyanA),
        StatusItem("Speed 2 Input", speed2, CyanA),
        StatusItem("Speed 3 Input", speed3, CyanA),
        StatusItem("Gain Input", gainInput, CyanA),
    )
}

@Composable
private fun rememberPulse(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseFloat",
    )
    return if (active) pulse else 0f
}

private fun splitValue(text: String): Pair<String, String> {
    val parts = text.split(" ", limit = 2)
    return parts.first() to parts.getOrElse(1) { "" }
}

private fun formatOne(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun formatTwo(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatFirmware(raw: Int?): String {
    if (raw == null || raw == 0) return "--"
    val major = raw / 100
    val minor = raw % 100
    return "$major.${minor.toString().padStart(2, '0')}"
}

private fun formatControllerType(raw: Int?): String {
    if (raw == null || raw == 0) return "--"
    return when (raw) {
        0x006E -> "100"
        else -> raw.toString()
    }
}
