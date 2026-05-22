@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sg100usb.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ── Light industrial colour palette ──────────────────────────────────────────
// Inspired by Siemens SIMATIC HMI, Woodward governor software, and
// professional embedded-control-system interfaces.
private val PageBg      = Color(0xFFECEFF2)   // cool neutral page background
private val PanelBg     = Color(0xFFFFFFFF)   // white panel surfaces
private val CardBg      = Color(0xFFF4F7FA)   // off-white card interiors
private val GraphBg     = Color(0xFFEBF0F5)   // light blue-grey graph canvas
private val BorderClr   = Color(0xFFCDD5DD)   // soft card borders
private val DividerClr  = Color(0xFFDDE3EB)   // strip separators
private val NavRailBg   = Color(0xFFE4EAF0)   // nav rail — slightly cooler than page
private val TrackClr    = Color(0xFFDDE4EA)   // gauge / bar track
private val BlueA       = Color(0xFF1565C0)   // industrial blue — primary
private val TealA       = Color(0xFF00796B)   // teal — healthy / secondary
private val AmberA      = Color(0xFFBF5000)   // dark amber — caution
private val RedA        = Color(0xFFB71C1C)   // dark red — fault
private val GreenA      = Color(0xFF2E7D32)   // dark green — online
private val TxtMain     = Color(0xFF1A2530)   // near-black primary text
private val TxtLabel    = Color(0xFF5E7282)   // grey-blue label text
private val TxtMuted    = Color(0xFF98A8B3)   // muted secondary text
private val NeedleClr   = Color(0xFF37474F)   // dark steel needle

// ── Root composable ────────────────────────────────────────────────────────────
@Composable
fun Sg100App(viewModel: DashboardViewModel) {
    var screen by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            background  = PageBg,
            surface     = PanelBg,
            primary     = BlueA,
            secondary   = TealA,
            tertiary    = AmberA,
            error       = RedA,
            onBackground = TxtMain,
            onSurface    = TxtMain,
        )
    ) {
        Scaffold(containerColor = PageBg) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(PageBg)
            ) {
                AppHeader(viewModel)
                Row(Modifier.fillMaxSize()) {
                    NavRail(screen) { screen = it }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(BorderClr))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            0 -> {
                                val usb     by viewModel.usbState.collectAsState()
                                val polling by viewModel.polling.collectAsState()
                                val graph   by viewModel.graph.collectAsState()
                                MonitorScreen(polling, usb, graph)
                            }
                            1 -> {
                                val graph by viewModel.graph.collectAsState()
                                TrendsScreen(graph, onZoom = viewModel::setGraphZoom)
                            }
                            2 -> {
                                val settings by viewModel.settings.collectAsState()
                                ConfigScreen(settings, viewModel::editRegister, viewModel::writeRegister)
                            }
                            3 -> {
                                val usb     by viewModel.usbState.collectAsState()
                                val polling by viewModel.polling.collectAsState()
                                val logs    by viewModel.packetLog.collectAsState()
                                DebugScreen(usb, polling, logs)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── App header ─────────────────────────────────────────────────────────────────
@Composable
private fun AppHeader(viewModel: DashboardViewModel) {
    val usb     by viewModel.usbState.collectAsState()
    val polling by viewModel.polling.collectAsState()

    val dotColor = when {
        usb.connected && polling.controllerOnline -> GreenA
        usb.connected                             -> AmberA
        else                                      -> TxtMuted
    }
    val statusText = when {
        usb.connected && polling.controllerOnline -> "Device Online"
        usb.connected                             -> "Connecting to Device"
        else                                      -> "Device Offline"
    }
    val statusColor = if (usb.connected && polling.controllerOnline) GreenA else TxtMuted

    Column(Modifier.fillMaxWidth().background(PanelBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Nameplate ────────────────────────────────────────────
            Column(Modifier.width(132.dp)) {
                Text(
                    "HUEGLI TECH",
                    color         = Color(0xFF1C3049),
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    "HT-SG100",
                    color         = TxtMuted,
                    fontSize      = 9.sp,
                    fontFamily    = FontFamily.Monospace,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(1.dp).height(28.dp).background(DividerClr))
            Spacer(Modifier.width(14.dp))

            // ── Connection state ─────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            if (polling.pollingRateHz > 0f) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(DividerClr))
                Spacer(Modifier.width(12.dp))
                Text(
                    "${"%.1f".format(polling.pollingRateHz)} Hz",
                    color      = TxtMuted,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // ── Flexible gap — pushes controls to the right ──────────
            Spacer(Modifier.weight(1f))

            // ── Action controls ──────────────────────────────────────
            Box(Modifier.width(1.dp).height(28.dp).background(DividerClr))
            Spacer(Modifier.width(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HdrButton("Connect",       BlueA,             viewModel::connect)
                HdrButton("Start Polling", TealA,             viewModel::startPolling)
                HdrButton("Stop Polling",  Color(0xFF4A6070), viewModel::stopPolling)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderClr))
    }
}

// ── Navigation rail ────────────────────────────────────────────────────────────
private val navItems = listOf("Dashboard", "Trends", "Configure", "Diagnostics")

@Composable
private fun NavRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .fillMaxHeight()
            .background(NavRailBg)
    ) {
        Spacer(Modifier.height(10.dp))
        navItems.forEachIndexed { i, label ->
            val active = selected == i
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clickable { onSelect(i) }
                    .background(if (active) BlueA.copy(alpha = 0.09f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(if (active) BlueA else Color.Transparent)
                )
                Text(
                    label,
                    color      = if (active) BlueA else TxtLabel,
                    fontSize   = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier   = Modifier.padding(start = 12.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HdrButton(label: String, tint: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tint.copy(alpha = 0.13f),
            contentColor   = tint,
        ),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.1.sp)
    }
}

// ── Monitor screen — three-column command bridge ───────────────────────────────
@Composable
private fun MonitorScreen(snapshot: PollingSnapshot, usb: UsbHidState, graph: GraphSeries) {
    val input  = snapshot.input
    val rpm    = input?.engineSpeedRpm ?: 0
    val rpmEv  = EngineeringFormats.rpm(rpm)
    val pwmEv  = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER)
    val reqEv  = EngineeringFormats.register(input, Sg100Registers.REQUESTED_SPEED_REGISTER)
    val syncEv = EngineeringFormats.register(input, Sg100Registers.SYNC_VOLTAGE_REGISTER)
    val currEv = EngineeringFormats.register(input, 30057)
    val posEv  = EngineeringFormats.register(input, 30058)

    BoxWithConstraints(Modifier.fillMaxSize().background(PageBg)) {
        if (maxWidth >= 680.dp) {
            Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── Left: gauges ──────────────────────────────────────────────
                Column(
                    Modifier
                        .weight(0.26f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("INSTRUMENTS")
                    Panel(Modifier.weight(1.05f).fillMaxWidth()) {
                        ArcGauge(
                            label     = "ENGINE SPEED",
                            value     = rpmEv.displayValue,
                            max       = 4000.0,
                            text      = rpmEv.text,
                            fillColor = BlueA,
                            modifier  = Modifier.fillMaxSize(),
                        )
                    }
                    Panel(Modifier.weight(0.95f).fillMaxWidth()) {
                        ArcGauge(
                            label     = "PWM / ACTUATOR",
                            value     = pwmEv.displayValue.coerceAtMost(100.0),
                            max       = 100.0,
                            text      = pwmEv.text,
                            fillColor = AmberA,
                            modifier  = Modifier.fillMaxSize(),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip("FW", formatFirmware(input?.value(30062)), Modifier.weight(1f))
                        InfoChip("Controller", formatControllerType(input?.value(30063)), Modifier.weight(1f))
                    }
                }

                // ── Centre: telemetry + actuator + status ─────────────────────
                Column(
                    Modifier
                        .weight(0.44f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("LIVE TELEMETRY")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TelCard("ENGINE SPEED",    rpmEv.text,  BlueA,  Modifier.weight(1f))
                        TelCard("REQUESTED SPEED", reqEv.text,  TxtMain, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TelCard(
                            label     = "ACTUATOR CURRENT",
                            value     = currEv.text,
                            valueColor = if (currEv.outOfRange) RedA else TxtMain,
                            modifier  = Modifier.weight(1f),
                        )
                        TelCard("SYNC VOLTAGE", syncEv.text, TxtMain, Modifier.weight(1f))
                    }
                    ActuatorBar(posEv, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    StatusPanel(snapshot, usb)
                }

                // ── Right: inline trend rails ─────────────────────────────────
                Column(
                    Modifier
                        .weight(0.30f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("REAL-TIME TRENDS")
                    MiniTrend("ENGINE RPM",      graph.rpm,           BlueA,  graph.zoom, Modifier.weight(1f).fillMaxWidth())
                    MiniTrend("PWM / ACTUATOR",  graph.pwm,           AmberA, graph.zoom, Modifier.weight(1f).fillMaxWidth())
                    MiniTrend("ACT. CURRENT",    graph.actuatorCurrent, RedA,  graph.zoom, Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val hz = snapshot.pollingRateHz
                        Text(
                            if (hz > 0f) "${"%.1f".format(hz)} Hz" else "—",
                            color = TxtMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        )
                        val ok = snapshot.input?.crc?.ok == true
                        Text(
                            "CRC ${if (ok) "OK" else "ERR"}",
                            color = if (ok) GreenA else RedA,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        } else {
            NarrowMonitor(snapshot, usb, rpmEv, pwmEv, reqEv, syncEv, currEv, posEv)
        }
    }
}

// ── Arc gauge ──────────────────────────────────────────────────────────────────
@Composable
private fun ArcGauge(
    label: String,
    value: Double,
    max: Double,
    text: String,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx  = 11.dp.toPx()
            val diam      = min(size.width, size.height * 1.65f) - strokePx * 2f
            val topLeft   = Offset((size.width - diam) / 2f, strokePx)
            val arcSz     = Size(diam, diam)

            // Track
            drawArc(TrackClr, 180f, 180f, false, topLeft, arcSz, style = Stroke(strokePx, cap = StrokeCap.Round))

            // Value arc — transitions through caution and alarm ranges
            val frac = (value.coerceIn(0.0, max) / max).toFloat()
            val color = when {
                frac < 0.70f -> fillColor
                frac < 0.90f -> AmberA
                else          -> RedA
            }
            if (frac > 0f) {
                drawArc(color, 180f, 180f * frac, false, topLeft, arcSz, style = Stroke(strokePx, cap = StrokeCap.Round))
            }

            // Needle — dark steel appearance
            val cx    = topLeft.x + diam / 2f
            val cy    = topLeft.y + diam / 2f
            val angle = Math.toRadians(180.0 + 180.0 * frac)
            val r     = diam / 2f - strokePx
            drawLine(
                NeedleClr,
                Offset(cx, cy),
                Offset(cx + cos(angle).toFloat() * r, cy + sin(angle).toFloat() * r),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(TrackClr,   5.dp.toPx(), Offset(cx, cy))
            drawCircle(NeedleClr,  3.dp.toPx(), Offset(cx, cy))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Text(
                text,
                color = TxtMain,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, color = TxtLabel, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        }
    }
}

// ── Telemetry card ─────────────────────────────────────────────────────────────
@Composable
private fun TelCard(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            label,
            color = TxtLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Actuator position bar ──────────────────────────────────────────────────────
@Composable
private fun ActuatorBar(ev: EngineeringValue, modifier: Modifier = Modifier) {
    val raw      = ev.raw
    val display  = ev.displayValue
    // For display purposes clamp to 0-100 visually; show raw number in text
    val barFrac  = (display / 100.0).coerceIn(0.0, 1.0).toFloat()
    val barColor = when {
        barFrac < 0.80f -> TealA
        barFrac < 0.95f -> AmberA
        else             -> RedA
    }
    Panel(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ACTUATOR POSITION", color = TxtLabel, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ev.text, color = TxtMain, fontSize = 14.sp, fontWeight = FontWeight.Black)
                if (ev.outOfRange || raw > 100) {
                    Text("raw $raw", color = AmberA, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(TrackClr, RoundedCornerShape(3.dp))
        ) {
            if (barFrac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(barFrac)
                        .fillMaxHeight()
                        .background(barColor, RoundedCornerShape(3.dp))
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "25", "50", "75", "100%").forEach {
                Text(it, color = TxtMuted, fontSize = 8.sp)
            }
        }
    }
}

// ── Status LED panel ───────────────────────────────────────────────────────────
// Maps protocol bit-label strings → operator-readable display labels (UI layer only).
private val inputBitDisplayLabels = mapOf(
    "Speed2 input"        to "Speed 2 Input",
    "Speed3 input"        to "Speed 3 Input",
    "Gain input"          to "Gain Input",
    "Fn key"              to "Function Key",
    "Plus key"            to "Plus Key",
    "Minus key"           to "Minus Key",
    "Idle input"          to "Idle Input",
    "Pickup sensor input" to "Pickup Sensor Input",
)

@Composable
private fun StatusPanel(snapshot: PollingSnapshot, usb: UsbHidState) {
    val input = snapshot.input
    Panel(Modifier.fillMaxWidth()) {
        Text(
            "DEVICE STATUS",
            color         = TxtLabel,
            fontSize      = 9.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(10.dp))

        // ── Three-column status groups ─────────────────────────────
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatusGroup("CONNECTION", Modifier.weight(1f)) {
                SLed("USB Connection", usb.connected,             GreenA)
                SLed("Controller",     snapshot.controllerOnline, GreenA)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DividerClr))
            StatusGroup("FAULT CONDITIONS", Modifier.weight(1f)) {
                SLed("Overspeed",    input?.statusBits?.get("Overspeed occurred")   == true, RedA)
                SLed("Over Current", input?.statusBits?.get("Actuator overcurrent") == true, RedA)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DividerClr))
            StatusGroup("GOVERNOR MODE", Modifier.weight(1f)) {
                SLed("Droop",  input?.statusBits?.get("Droop input status")    == true, AmberA)
                SLed("Gain 2", input?.statusBits?.get("Gain2 selection input") == true, AmberA)
            }
        }

        // ── Digital inputs ─────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerClr))
        Spacer(Modifier.height(8.dp))
        Text(
            "DIGITAL INPUTS",
            color         = TxtMuted,
            fontSize      = 8.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
        ) {
            Sg100Registers.input30056Bits.values.forEach { protocolLabel ->
                val displayLabel = inputBitDisplayLabels[protocolLabel] ?: protocolLabel
                SLed(displayLabel, input?.inputBits?.get(protocolLabel) == true, TealA)
            }
        }

        if (snapshot.error != null) {
            Spacer(Modifier.height(6.dp))
            Text("⚠ ${snapshot.error}", color = AmberA, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            title,
            color         = TxtMuted,
            fontSize      = 8.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Composable
private fun SLed(label: String, active: Boolean, activeColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (active) activeColor else Color(0xFFCDD5DD), CircleShape))
        Text(
            label,
            color      = if (active) activeColor else TxtLabel,
            fontSize   = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Mini trend graph ───────────────────────────────────────────────────────────
@Composable
private fun MiniTrend(
    title: String,
    points: List<GraphPoint>,
    color: Color,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    val latest = points.lastOrNull()?.value
    Panel(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = TxtLabel, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
            if (latest != null) {
                Text(
                    "%.1f".format(latest),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(GraphBg, RoundedCornerShape(3.dp))
        ) {
            drawTrendLine(points, zoom, color, size, strokeDp = 1.5f)
        }
    }
}

// ── Trends tab (full width) ────────────────────────────────────────────────────
@Composable
private fun TrendsScreen(graph: GraphSeries, onZoom: (Float) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ZOOM", color = TxtLabel, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
                Text(
                    "${"%.1f".format(graph.zoom)}×",
                    color = BlueA,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp),
                )
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
        FullTrend("ENGINE RPM",     graph.rpm,             BlueA,  graph.zoom, Modifier.weight(1f).fillMaxWidth())
        FullTrend("PWM / ACTUATOR", graph.pwm,             AmberA, graph.zoom, Modifier.weight(1f).fillMaxWidth())
        FullTrend("ACT. CURRENT",   graph.actuatorCurrent, RedA,   graph.zoom, Modifier.weight(1f).fillMaxWidth())
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
    val latest = points.lastOrNull()?.value
    Panel(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = TxtMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (latest != null) {
                Text(
                    "%.2f".format(latest),
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(GraphBg, RoundedCornerShape(4.dp))
        ) {
            drawTrendLine(points, zoom, color, size, strokeDp = 2f)
        }
    }
}

// Shared Canvas drawing logic extracted to avoid duplication
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrendLine(
    points: List<GraphPoint>,
    zoom: Float,
    color: Color,
    canvasSize: Size,
    strokeDp: Float,
) {
    if (canvasSize.height < 2f) return
    val visible = min(points.size, (240 / zoom).toInt().coerceAtLeast(10))
    if (visible < 2) return
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
    val gridColor = Color(0xFFD5DDE5)
    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
        val y = canvasSize.height * (1f - frac)
        drawLine(gridColor, Offset(0f, y), Offset(canvasSize.width, y), strokeWidth = 0.5.dp.toPx())
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

// ── Configuration screen ───────────────────────────────────────────────────────
@Composable
private fun ConfigScreen(
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Holding Registers", color = TxtMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Modbus function 06 writes. Polling reads input registers 30051–30063.",
                color = TxtLabel, fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        items(settings.values.sortedBy { it.register.definition.address }) { editable ->
            ConfigRow(editable, onEdit, onWrite)
        }
    }
}

@Composable
private fun ConfigRow(
    editable: EditableRegister,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
) {
    val def = editable.register.definition
    var text by remember(editable.editedValue) { mutableStateOf(editable.editedValue.toString()) }
    Panel(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        def.address.toString(),
                        color = TxtMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(def.label, color = TxtMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Current: ${editable.register.raw} ${def.unit}",
                        color = TxtLabel, fontSize = 10.sp,
                    )
                    if (editable.dirty) {
                        Text("● modified", color = AmberA, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (def.max <= 6000 && def.control != RegisterControl.SWITCH) {
                    Slider(
                        value = editable.editedValue.toFloat(),
                        onValueChange = { onEdit(def.address, it.toInt()) },
                        valueRange = def.min.toFloat()..def.max.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = BlueA,
                            activeTrackColor = BlueA,
                            inactiveTrackColor = TrackClr,
                        ),
                    )
                }
            }
            if (def.control == RegisterControl.SWITCH) {
                Switch(
                    checked = editable.editedValue != 0,
                    onCheckedChange = { onEdit(def.address, if (it) 1 else 0) },
                    colors = SwitchDefaults.colors(checkedThumbColor = BlueA, checkedTrackColor = BlueA.copy(alpha = 0.35f)),
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter(Char::isDigit)
                        text.toIntOrNull()?.let { v -> onEdit(def.address, v) }
                    },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    label = { Text("Value", color = TxtLabel, fontSize = 10.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueA,
                        unfocusedBorderColor = BorderClr,
                        focusedTextColor = TxtMain,
                        unfocusedTextColor = TxtMain,
                        cursorColor = BlueA,
                    ),
                )
            }
            Button(
                onClick = { onWrite(def.address, editable.editedValue) },
                enabled = editable.dirty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlueA,
                    contentColor = Color.White,
                    disabledContainerColor = TrackClr,
                    disabledContentColor = TxtMuted,
                ),
                shape = RoundedCornerShape(3.dp),
            ) {
                Text("WRITE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Debug screen ───────────────────────────────────────────────────────────────
@Composable
private fun DebugScreen(usb: UsbHidState, snapshot: PollingSnapshot, logs: List<PacketLogEntry>) {
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Text("USB Device", color = TxtMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            MonoRow("VID / PID",   "${usb.deviceInfo.vendorId.hex16()} / ${usb.deviceInfo.productId.hex16()}")
            MonoRow("Interface",   "${usb.deviceInfo.interfaceId}   IN: ${usb.deviceInfo.inEndpoint}   OUT: ${usb.deviceInfo.outEndpoint}")
            MonoRow("Poll",        "${"%.1f".format(snapshot.pollingRateHz)} Hz")
            MonoRow("Input CRC",   snapshot.input?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "—")
            MonoRow("Holding CRC", snapshot.holding?.crc?.let { if (it.ok) "PASS" else "FAIL" } ?: "—")
            MonoRow("Error",       snapshot.error ?: "none")
            if (usb.detectedDevices.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Detected USB devices", color = TxtMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                usb.detectedDevices.forEach { line ->
                    Text(line, color = TxtLabel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Text("Communication Log", color = TxtMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            LazyColumn(Modifier.fillMaxHeight()) {
                if (logs.isEmpty()) {
                    item { Text("No communication yet.", color = TxtMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                } else {
                    items(logs, key = { it.id }) { entry ->
                        val color = when (entry.direction) {
                            "TX"  -> Color(0xFF1050A0)
                            "RX"  -> Color(0xFF0E7050)
                            else  -> TxtLabel
                        }
                        Text(entry.displayText, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ── Narrow fallback (phones / portrait) ───────────────────────────────────────
@Composable
private fun NarrowMonitor(
    snapshot: PollingSnapshot,
    usb: UsbHidState,
    rpmEv: EngineeringValue,
    pwmEv: EngineeringValue,
    reqEv: EngineeringValue,
    syncEv: EngineeringValue,
    currEv: EngineeringValue,
    posEv: EngineeringValue,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel(Modifier.weight(1f).height(165.dp)) {
                    ArcGauge("ENGINE SPEED",   rpmEv.displayValue,                       4000.0, rpmEv.text, BlueA,  Modifier.fillMaxSize())
                }
                Panel(Modifier.weight(1f).height(165.dp)) {
                    ArcGauge("PWM / ACTUATOR", pwmEv.displayValue.coerceAtMost(100.0), 100.0, pwmEv.text, AmberA, Modifier.fillMaxSize())
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelCard("ENGINE SPEED",    rpmEv.text,  BlueA,   Modifier.weight(1f))
                TelCard("REQUESTED SPEED", reqEv.text,  TxtMain, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelCard("ACT. CURRENT", currEv.text, if (currEv.outOfRange) RedA else TxtMain, Modifier.weight(1f))
                TelCard("SYNC VOLTAGE", syncEv.text, TxtMain, Modifier.weight(1f))
            }
        }
        item { ActuatorBar(posEv, Modifier.fillMaxWidth()) }
        item { StatusPanel(snapshot, usb) }
    }
}

// ── Shared layout primitives ───────────────────────────────────────────────────
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(PanelBg, RoundedCornerShape(5.dp))
            .border(1.dp, BorderClr, RoundedCornerShape(5.dp))
            .padding(11.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(3.dp).height(11.dp).background(BlueA, RoundedCornerShape(1.dp)))
        Text(text, color = TxtLabel, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun InfoChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(CardBg, RoundedCornerShape(3.dp))
            .border(1.dp, BorderClr, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, color = TxtMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        Text(value, color = TxtLabel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = TxtMuted, fontSize = 10.sp, modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
        Text(value, color = TxtLabel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── Formatting helpers ─────────────────────────────────────────────────────────
private fun formatFirmware(raw: Int?): String {
    if (raw == null || raw == 0) return "--"
    return "v${(raw shr 8) and 0xFF}.${(raw and 0xFF).toString().padStart(2, '0')}"
}

private fun formatControllerType(raw: Int?): String {
    if (raw == null || raw == 0) return "--"
    return "0x${raw.toString(16).uppercase().padStart(4, '0')}"
}
