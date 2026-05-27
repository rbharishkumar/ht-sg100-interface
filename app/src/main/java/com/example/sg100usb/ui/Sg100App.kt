@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sg100usb.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.sg100usb.data.EditableRegister
import com.example.sg100usb.data.GraphPoint
import com.example.sg100usb.data.GraphSeries
import com.example.sg100usb.data.PollingSnapshot
import com.example.sg100usb.data.SpeedRecording
import com.example.sg100usb.data.WriteStatus
import com.example.sg100usb.format.EngineeringFormats
import com.example.sg100usb.protocol.Sg100Registers
import com.example.sg100usb.protocol.engineSpeedRpm
import com.example.sg100usb.protocol.requestedSpeedRpm
import com.example.sg100usb.protocol.syncVoltage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

// ── Huegli Tech — light / white theme ────────────────────────────────────────
private val PageBg    = Color(0xFFF4F8F5)   // off-white, barely-green tint
private val HeaderBg  = Color(0xFFFFFFFF)   // pure white header
private val PanelBg   = Color(0xFFFFFFFF)   // white cards
private val PanelBg2  = Color(0xFFEDF4EE)   // light input background
private val GraphBg   = Color(0xFF091209)   // keep graph dark for line contrast
private val BorderClr = Color(0xFFCADCCC)   // soft grey-green border
private val TrackClr  = Color(0xFFD6E8D8)   // light slider track
private val HtGreen   = Color(0xFF1B7A39)   // HT green (dark enough for white bg)
private val HtGreenLt = Color(0xFF22A04D)   // lighter accent
private val AmberA    = Color(0xFFB06000)   // amber, readable on white
private val RedA      = Color(0xFFBD2020)   // red
private val TextMain  = Color(0xFF162419)   // near-black
private val TextLabel = Color(0xFF3A5C40)   // dark green-grey label
private val TextMuted = Color(0xFF7A9880)   // muted

// ── Navigation ────────────────────────────────────────────────────────────────
private enum class HtTab { MainConfig, Pid, Graph, Records, Monitor }

private val navItems = listOf(
    HtTab.MainConfig to "Config",
    HtTab.Pid        to "PID",
    HtTab.Graph      to "Graph",
    HtTab.Records    to "Records",
    HtTab.Monitor    to "Monitor",
)

// ── Root composable ───────────────────────────────────────────────────────────
@Composable
fun Sg100App(viewModel: DashboardViewModel) {
    var tab by remember { mutableStateOf(HtTab.MainConfig) }

    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            background    = PageBg,
            surface       = PanelBg,
            primary       = HtGreen,
            secondary     = HtGreenLt,
            tertiary      = AmberA,
            error         = RedA,
            onBackground  = TextMain,
            onSurface     = TextMain,
            onPrimary     = Color.White,
        )
    ) {
        Scaffold(
            containerColor = PageBg,
            bottomBar = { HtBottomNav(tab) { tab = it } },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(PageBg)
            ) {
                HtAppHeader(viewModel)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        HtTab.MainConfig -> {
                            val polling  by viewModel.polling.collectAsState()
                            val settings by viewModel.settings.collectAsState()
                            val resetting by viewModel.resettingDefaults.collectAsState()
                            MainConfigScreen(
                                polling = polling,
                                settings = settings,
                                isResettingDefaults = resetting,
                                onEdit = viewModel::editRegister,
                                onWrite = viewModel::writeRegister,
                                onClearStatus = viewModel::clearWriteStatus,
                                onResetDefaults = viewModel::resetToDefaults,
                            )
                        }
                        HtTab.Pid -> {
                            val settings by viewModel.settings.collectAsState()
                            val polling  by viewModel.polling.collectAsState()
                            PidConfigScreen(
                                polling = polling,
                                settings = settings,
                                onEdit = viewModel::editRegister,
                                onWrite = viewModel::writeRegister,
                                onClearStatus = viewModel::clearWriteStatus,
                            )
                        }
                        HtTab.Graph -> {
                            val graph    by viewModel.graph.collectAsState()
                            val recording by viewModel.isRecording.collectAsState()
                            val duration  by viewModel.recordingDurationSec.collectAsState()
                            SpeedGraphScreen(
                                graph = graph,
                                isRecording = recording,
                                recordingDurationSec = duration,
                                onZoom = viewModel::setGraphZoom,
                                onStartRecording = viewModel::startRecording,
                                onStopRecording = viewModel::stopAndSaveRecording,
                            )
                        }
                        HtTab.Records -> {
                            val recordings by viewModel.savedRecordings.collectAsState()
                            SpeedRecordsScreen(
                                recordings = recordings,
                                onDelete = viewModel::deleteRecording,
                            )
                        }
                        HtTab.Monitor -> {
                            val polling by viewModel.polling.collectAsState()
                            MonitorScreen(polling = polling)
                        }
                    }
                }
            }
        }
    }
}

// ── App Header ────────────────────────────────────────────────────────────────
@Composable
private fun HtAppHeader(viewModel: DashboardViewModel) {
    val usb     by viewModel.usbState.collectAsState()
    val polling by viewModel.polling.collectAsState()
    val connected = usb.connected
    val online    = polling.controllerOnline

    val statusColor = when {
        connected && online -> HtGreen
        connected           -> AmberA
        else                -> TextMuted
    }
    val statusText = when {
        connected && online  -> "Online"
        connected            -> "USB Linked"
        usb.permissionPending -> "Permission"
        else                 -> "Offline"
    }
    val barText = when {
        connected && online  -> "Connected to SG-100  ·  ${formatOne(polling.pollingRateHz)} Hz"
        connected            -> "SG-100 linked — starting telemetry…"
        usb.permissionPending -> "Tap OK to allow USB access"
        else                 -> "Searching for SG-100…"
    }
    val barPulsing = !(connected && online)
    val pulse = rememberPulse(barPulsing)

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(3.dp)
            .background(HeaderBg)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "HT-SG100 Speed Governor",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Huegli Tech Configuration Interface",
                    color = TextMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Status pill
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val p = rememberPulse(barPulsing)
                Box(Modifier.size(7.dp + (p * 2).dp).background(statusColor, CircleShape))
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Status bar
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(statusColor.copy(alpha = 0.07f + pulse * 0.04f))
                .border(1.dp, statusColor.copy(alpha = 0.22f + pulse * 0.1f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size((6 + pulse * 2).dp).background(statusColor.copy(alpha = 0.8f + pulse * 0.2f), CircleShape))
            Text(barText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Bottom Nav ────────────────────────────────────────────────────────────────
@Composable
private fun HtBottomNav(selected: HtTab, onSelect: (HtTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(HeaderBg)
            .border(1.dp, BorderClr)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navItems.forEach { (tab, label) ->
            val active = selected == tab
            val bg by animateColorAsState(
                if (active) HtGreen.copy(alpha = 0.18f) else Color.Transparent,
                animationSpec = tween(200), label = "navBg",
            )
            Column(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HtNavIcon(tab, if (active) HtGreen else TextMuted)
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    color = if (active) TextMain else TextMuted,
                    fontSize = 9.sp,
                    fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HtNavIcon(tab: HtTab, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = 2.dp.toPx()
        when (tab) {
            HtTab.MainConfig -> {
                // Grid icon (4 squares)
                val gap = 2.dp.toPx(); val half = (size.width - gap) / 2f
                drawRoundRect(color, Offset(0f, 0f), Size(half, half), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(s))
                drawRoundRect(color, Offset(half + gap, 0f), Size(half, half), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(s))
                drawRoundRect(color, Offset(0f, half + gap), Size(half, half), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(s))
                drawRoundRect(color, Offset(half + gap, half + gap), Size(half, half), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(s))
            }
            HtTab.Pid -> {
                // Sliders icon
                val w = size.width; val h = size.height
                listOf(0.2f, 0.5f, 0.8f).forEachIndexed { i, yf ->
                    val y = h * yf
                    drawLine(color.copy(alpha = 0.4f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
                    val xPos = w * listOf(0.3f, 0.6f, 0.45f)[i]
                    drawCircle(color, 3.dp.toPx(), Offset(xPos, y))
                }
            }
            HtTab.Graph -> {
                // Chart line icon
                val pts = listOf(0f to 0.7f, 0.25f to 0.4f, 0.5f to 0.55f, 0.75f to 0.2f, 1f to 0.35f)
                    .map { (x, y) -> Offset(x * size.width, y * size.height) }
                for (i in 1 until pts.size) {
                    drawLine(color, pts[i - 1], pts[i], s, StrokeCap.Round)
                }
                drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height * 0.8f), Offset(size.width, size.height * 0.8f), 1.dp.toPx())
            }
            HtTab.Records -> {
                // File icon
                val w = size.width; val h = size.height
                val fold = w * 0.35f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f); lineTo(w - fold, 0f); lineTo(w, fold)
                    lineTo(w, h); lineTo(0f, h); close()
                }
                drawPath(path, color, style = Stroke(s))
                drawLine(color.copy(alpha = 0.5f), Offset(0.2f * w, h * 0.45f), Offset(0.8f * w, h * 0.45f), 1.dp.toPx())
                drawLine(color.copy(alpha = 0.5f), Offset(0.2f * w, h * 0.62f), Offset(0.8f * w, h * 0.62f), 1.dp.toPx())
                drawLine(color.copy(alpha = 0.5f), Offset(0.2f * w, h * 0.79f), Offset(0.65f * w, h * 0.79f), 1.dp.toPx())
            }
            HtTab.Monitor -> {
                // Eye icon — oval outline + pupil dot
                val cx = size.width / 2f; val cy = size.height / 2f
                val rx = size.width * 0.46f; val ry = size.height * 0.30f
                drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), style = Stroke(s))
                drawCircle(color, ry * 0.55f, Offset(cx, cy))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 1 — Main Configuration
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MainConfigScreen(
    polling: PollingSnapshot,
    settings: Map<Int, EditableRegister>,
    isResettingDefaults: Boolean,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
    onResetDefaults: () -> Unit,
) {
    val input = polling.input
    val rpmEv = EngineeringFormats.rpm(input?.engineSpeedRpm ?: 0)
    val pwmEv = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER)

    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = PanelBg,
            titleContentColor = TextMain,
            textContentColor = TextLabel,
            title = { Text("Reset to Factory Defaults?", fontWeight = FontWeight.Black) },
            text = {
                Text(
                    "This will write the factory default value to all writable holding registers on the SG-100. Current configuration will be overwritten.\n\nMake sure the device is connected before proceeding.",
                    fontSize = 13.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = { showResetDialog = false; onResetDefaults() },
                    colors = ButtonDefaults.buttonColors(containerColor = RedA, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Reset All", fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Live governor gauges ──────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(86.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactGaugePanel(
                label = "ENGINE RPM",
                value = rpmEv.displayValue,
                max   = 4000.0,
                text  = "${rpmEv.displayValue.toInt()}",
                unit  = "RPM",
                tint  = HtGreen,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CompactGaugePanel(
                label = "PWM OUTPUT",
                value = pwmEv.displayValue.coerceAtMost(100.0),
                max   = 100.0,
                text  = pwmEv.text,
                unit  = "PWM",
                tint  = AmberA,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // ── Speed Parameters ──────────────────────────────────────────────────
        CompactSectionLabel("Speed Parameters")
        val speedParams = listOf(
            40061 to "Speed 1",
            40060 to "Speed 2",
            40059 to "Speed 3",
            40057 to "Trim DS",
            40056 to "Trim FS",
            40058 to "Idle Speed",
            40070 to "Loop Time",
            40075 to "Accel",
            40076 to "Decel",
        )
        ParamGrid(speedParams, settings, onEdit, onWrite, onClearStatus)

        // ── Options ───────────────────────────────────────────────────────────
        CompactSectionLabel("Options")
        val optionParams = listOf(
            40051 to "Overspeed",
            40052 to "Start Fuel",
            40055 to "Crank Term",
            40054 to "Fuel Ramp",
            40053 to "Speed Ramp",
            40062 to "Gear Teeth",
        )
        ParamGrid(optionParams, settings, onEdit, onWrite, onClearStatus)

        // ── Configuration Flags ───────────────────────────────────────────────
        CompactSectionLabel("Configuration Flags")
        ConfigFlagsSection(settings = settings, onWrite = onWrite)

        // ── Reset button ───────────────────────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        CompactResetButton(isResettingDefaults) { showResetDialog = true }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CompactGaugePanel(
    label: String,
    value: Double,
    max: Double,
    text: String,
    unit: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = TextLabel, fontSize = 8.sp, fontWeight = FontWeight.Black)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularGauge(value, max, tint, Modifier.fillMaxSize(), stroke = 10f, sweep = 220f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
                Text(unit, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun CompactSectionLabel(title: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(50)).background(HtGreen))
        Text(title.uppercase(Locale.US), color = HtGreen, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Box(Modifier.weight(1f).height(1.dp).background(BorderClr))
    }
}

@Composable
private fun ParamGrid(
    params: List<Pair<Int, String>>,
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        params.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (address, label) ->
                    CompactParamCard(
                        label    = label,
                        address  = address,
                        editable = settings[address],
                        onEdit   = onEdit,
                        onWrite  = onWrite,
                        onClearStatus = onClearStatus,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < 3) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CompactParamCard(
    label: String,
    address: Int,
    editable: EditableRegister?,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editable == null) {
        Box(
            modifier.height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBg)
                .border(1.dp, BorderClr, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("--", color = TextMuted, fontSize = 9.sp) }
        return
    }

    val def = editable.register.definition
    var text by remember(editable.editedValue) { mutableStateOf(editable.editedValue.toString()) }

    LaunchedEffect(editable.writeStatus) {
        if (editable.writeStatus == WriteStatus.Success) {
            kotlinx.coroutines.delay(3000)
            onClearStatus(address)
        }
    }

    val borderColor = when (editable.writeStatus) {
        WriteStatus.Success -> HtGreen
        WriteStatus.Error   -> RedA
        WriteStatus.Pending -> AmberA
        WriteStatus.Idle    -> if (editable.dirty) AmberA else BorderClr
    }

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelBg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Text(label, color = TextLabel, fontSize = 8.sp, fontWeight = FontWeight.Black,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = text,
            onValueChange = { new ->
                text = new.filter(Char::isDigit).take(5)
                text.toIntOrNull()?.let { v -> onEdit(address, v.coerceIn(def.min, def.max)) }
            },
            singleLine = true,
            textStyle = TextStyle(
                color = TextMain, fontSize = 13.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(PanelBg2)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                ) { inner() }
            },
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(def.unit, color = TextMuted, fontSize = 7.sp)
            when (editable.writeStatus) {
                WriteStatus.Pending -> Text("…", color = AmberA, fontSize = 9.sp, fontWeight = FontWeight.Black)
                WriteStatus.Success -> Text("✓", color = HtGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                WriteStatus.Error   -> Text("!", color = RedA, fontSize = 9.sp, fontWeight = FontWeight.Black)
                WriteStatus.Idle    -> if (editable.dirty) {
                    Box(
                        Modifier.size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(HtGreen.copy(alpha = 0.25f))
                            .clickable { onWrite(address, editable.editedValue) },
                        contentAlignment = Alignment.Center,
                    ) { Text("W", color = HtGreen, fontSize = 8.sp, fontWeight = FontWeight.Black) }
                } else {
                    Text("${editable.register.raw}", color = TextMuted, fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable
private fun CompactResetButton(isResetting: Boolean, onClick: () -> Unit) {
    val pulse = rememberPulse(isResetting)
    val borderColor = if (isResetting) AmberA.copy(alpha = 0.4f + pulse * 0.3f) else RedA.copy(alpha = 0.3f)
    val bgColor     = if (isResetting) AmberA.copy(alpha = 0.07f + pulse * 0.04f) else RedA.copy(alpha = 0.07f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .then(if (!isResetting) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size((7 + pulse * 2).dp).background(if (isResetting) AmberA else RedA, CircleShape))
        Text(
            if (isResetting) "Resetting to factory defaults…" else "Reset to Factory Defaults",
            color = if (isResetting) AmberA else RedA,
            fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
        )
        if (!isResetting) Text("RESET", color = RedA, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 2 — PID Configuration
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PidConfigScreen(
    polling: PollingSnapshot,
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
) {
    val input = polling.input
    val rpmEv = EngineeringFormats.rpm(input?.engineSpeedRpm ?: 0)
    val pwmEv = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Live RPM + PWM strip
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveValuePanel("ENGINE RPM", "${rpmEv.displayValue.toInt()}", "RPM", HtGreen, Modifier.weight(1f).fillMaxHeight())
            LiveValuePanel("PWM OUTPUT", pwmEv.text, "PWM", AmberA, Modifier.weight(1f).fillMaxHeight())
            LiveValuePanel("REQ SPEED",
                "${EngineeringFormats.register(input, Sg100Registers.REQUESTED_SPEED_REGISTER).displayValue.toInt()}",
                "RPM", HtGreenLt, Modifier.weight(1f).fillMaxHeight())
        }

        // Speed PID 1
        HtPanel(title = "Speed PID 1", accent = HtGreen) {
            PidSliderRow("P Gain", 40068, settings, onEdit, onWrite, onClearStatus)
            PidSliderRow("I Int",  40067, settings, onEdit, onWrite, onClearStatus)
            PidSliderRow("D Der",  40066, settings, onEdit, onWrite, onClearStatus)
        }

        // Position PID 1
        HtPanel(title = "Position PID 1", accent = HtGreenLt) {
            PidSliderRow("P Gain", 40065, settings, onEdit, onWrite, onClearStatus)
            PidSliderRow("I Int",  40064, settings, onEdit, onWrite, onClearStatus)
            PidSliderRow("D Der",  40063, settings, onEdit, onWrite, onClearStatus)
        }

        // Load currents
        HtPanel(title = "Load Current", accent = AmberA) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("FULL LOAD CURRENT", color = TextLabel, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    settings[40072]?.let { e ->
                        var txt by remember(e.editedValue) { mutableStateOf(e.editedValue.toString()) }
                        LaunchedEffect(e.writeStatus) { if (e.writeStatus == WriteStatus.Success) { kotlinx.coroutines.delay(3000); onClearStatus(40072) } }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BasicTextField(
                                value = txt,
                                onValueChange = { new -> txt = new.filter(Char::isDigit).take(4); txt.toIntOrNull()?.let { v -> onEdit(40072, v) } },
                                singleLine = true,
                                textStyle = TextStyle(color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                decorationBox = { i -> Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(5.dp)).background(PanelBg2).padding(horizontal = 5.dp, vertical = 3.dp)) { i() } }
                            )
                            Text("A", color = TextMuted, fontSize = 10.sp)
                            if (e.dirty) {
                                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(HtGreen.copy(alpha = 0.2f)).clickable { onWrite(40072, e.editedValue) }, contentAlignment = Alignment.Center) {
                                    Text("W", color = HtGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    } ?: Text("--", color = TextMuted, fontSize = 12.sp)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(BorderClr))
                Column(Modifier.weight(1f)) {
                    Text("NO LOAD CURRENT", color = TextLabel, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    settings[40074]?.let { e ->
                        var txt by remember(e.editedValue) { mutableStateOf(e.editedValue.toString()) }
                        LaunchedEffect(e.writeStatus) { if (e.writeStatus == WriteStatus.Success) { kotlinx.coroutines.delay(3000); onClearStatus(40074) } }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BasicTextField(
                                value = txt,
                                onValueChange = { new -> txt = new.filter(Char::isDigit).take(4); txt.toIntOrNull()?.let { v -> onEdit(40074, v) } },
                                singleLine = true,
                                textStyle = TextStyle(color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                decorationBox = { i -> Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(5.dp)).background(PanelBg2).padding(horizontal = 5.dp, vertical = 3.dp)) { i() } }
                            )
                            Text("A", color = TextMuted, fontSize = 10.sp)
                            if (e.dirty) {
                                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(HtGreen.copy(alpha = 0.2f)).clickable { onWrite(40074, e.editedValue) }, contentAlignment = Alignment.Center) {
                                    Text("W", color = HtGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    } ?: Text("--", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LiveValuePanel(label: String, value: String, unit: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelBg)
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = TextLabel, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(unit, color = TextMuted, fontSize = 7.sp)
    }
}

@Composable
private fun HtPanel(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(PanelBg2, PanelBg)))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(50)).background(accent))
            Text(title.uppercase(Locale.US), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun PidSliderRow(
    label: String,
    address: Int,
    settings: Map<Int, EditableRegister>,
    onEdit: (Int, Int) -> Unit,
    onWrite: (Int, Int) -> Unit,
    onClearStatus: (Int) -> Unit,
) {
    val editable = settings[address] ?: return
    val def = editable.register.definition

    LaunchedEffect(editable.writeStatus) {
        if (editable.writeStatus == WriteStatus.Success) {
            kotlinx.coroutines.delay(3000)
            onClearStatus(address)
        }
    }

    val accentColor = when (editable.writeStatus) {
        WriteStatus.Success -> HtGreen
        WriteStatus.Error   -> RedA
        WriteStatus.Pending -> AmberA
        WriteStatus.Idle    -> if (editable.dirty) AmberA else HtGreen
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(46.dp))
        Slider(
            value = editable.editedValue.toFloat(),
            onValueChange = { onEdit(address, it.toInt()) },
            valueRange = def.min.toFloat()..def.max.toFloat(),
            enabled = editable.writeStatus != WriteStatus.Pending,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = TrackClr,
            ),
        )
        Text(
            String.format(Locale.US, "%.1f", editable.editedValue / 10.0),
            color = if (editable.dirty) AmberA else TextMain,
            fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(4.dp))
        val canWrite = editable.dirty && editable.writeStatus != WriteStatus.Pending
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    when (editable.writeStatus) {
                        WriteStatus.Success -> HtGreen.copy(alpha = 0.25f)
                        WriteStatus.Error   -> RedA.copy(alpha = 0.25f)
                        WriteStatus.Pending -> AmberA.copy(alpha = 0.15f)
                        WriteStatus.Idle    -> if (canWrite) HtGreen.copy(alpha = 0.2f) else TrackClr
                    }
                )
                .then(if (canWrite) Modifier.clickable { onWrite(address, editable.editedValue) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (editable.writeStatus) {
                    WriteStatus.Pending -> "…"
                    WriteStatus.Success -> "✓"
                    WriteStatus.Error   -> "!"
                    WriteStatus.Idle    -> "W"
                },
                color = when (editable.writeStatus) {
                    WriteStatus.Success -> HtGreen
                    WriteStatus.Error   -> RedA
                    WriteStatus.Pending -> AmberA
                    WriteStatus.Idle    -> if (canWrite) HtGreen else TextMuted
                },
                fontSize = 10.sp, fontWeight = FontWeight.Black,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 3 — Speed Graph
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SpeedGraphScreen(
    graph: GraphSeries,
    isRecording: Boolean,
    recordingDurationSec: Long,
    onZoom: (Float) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val zoomLevels = listOf(1f to "Auto", 2f to "±100", 3f to "±50", 5f to "±20")

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Control bar
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelBg)
                .border(1.dp, BorderClr, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pulse = rememberPulse(isRecording)
            // Record / Stop button
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRecording) RedA.copy(alpha = 0.12f + pulse * 0.06f) else HtGreen.copy(alpha = 0.12f))
                    .border(1.dp, if (isRecording) RedA.copy(alpha = 0.5f + pulse * 0.2f) else HtGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { if (isRecording) onStopRecording() else onStartRecording() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp + (pulse * 2).dp).background(if (isRecording) RedA else HtGreen, CircleShape))
                Text(
                    if (isRecording) "■  ${formatDuration(recordingDurationSec)}" else "●  Record",
                    color = if (isRecording) RedA else HtGreen,
                    fontSize = 11.sp, fontWeight = FontWeight.Black,
                )
            }

            Spacer(Modifier.weight(1f))

            // Zoom chips
            zoomLevels.forEach { (zoom, label) ->
                val active = graph.zoom == zoom
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) HtGreen.copy(alpha = 0.2f) else TrackClr)
                        .border(1.dp, if (active) HtGreen.copy(alpha = 0.5f) else BorderClr, RoundedCornerShape(6.dp))
                        .clickable { onZoom(zoom) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(label, color = if (active) HtGreen else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // RPM graph
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(GraphBg)
                .border(1.dp, HtGreen.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ENGINE RPM", color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(
                    graph.rpm.lastOrNull()?.let { String.format(Locale.US, "%.0f RPM", it.value) } ?: "-- RPM",
                    color = HtGreen, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                drawTrendLine(graph.rpm, graph.zoom, HtGreen, size, 2.5f)
            }
        }

        // PWM graph
        Column(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(GraphBg)
                .border(1.dp, AmberA.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PWM / ACTUATOR", color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(
                    graph.pwm.lastOrNull()?.let { String.format(Locale.US, "%.1f%%", it.value) } ?: "--%",
                    color = AmberA, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                drawTrendLine(graph.pwm, graph.zoom, AmberA, size, 2f)
            }
        }

        // Live value strip
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBg)
                .border(1.dp, BorderClr, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val rpm     = graph.rpm.lastOrNull()?.value
            val pwm     = graph.pwm.lastOrNull()?.value
            val current = graph.actuatorCurrent.lastOrNull()?.value
            LiveReadout("RPM", rpm?.let { String.format(Locale.US, "%.0f", it) } ?: "--", HtGreen)
            LiveReadout("PWM", pwm?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--", AmberA)
            LiveReadout("CURRENT", current?.let { String.format(Locale.US, "%.2f A", it) } ?: "--", RedA)
        }
    }
}

@Composable
private fun LiveReadout(label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 4 — Speed Records
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SpeedRecordsScreen(
    recordings: List<SpeedRecording>,
    onDelete: (SpeedRecording) -> Unit,
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(50)).background(HtGreen))
            Column {
                Text("Speed Records", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("CSV recordings saved to device", color = TextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.size(48.dp)) {
                        val s = 2.dp.toPx()
                        val w = size.width; val h = size.height; val fold = w * 0.32f
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f); lineTo(w - fold, 0f); lineTo(w, fold)
                            lineTo(w, h); lineTo(0f, h); close()
                        }
                        drawPath(path, TextMuted.copy(alpha = 0.4f), style = Stroke(s))
                        drawLine(TextMuted.copy(alpha = 0.3f), Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), s)
                        drawLine(TextMuted.copy(alpha = 0.3f), Offset(w * 0.2f, h * 0.65f), Offset(w * 0.8f, h * 0.65f), s)
                    }
                    Text("No recordings yet", color = TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    Text("Go to the Graph tab and tap  ●  Record", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recordings, key = { it.id }) { rec ->
                    RecordingTile(
                        recording = rec,
                        onShare = {
                            try {
                                val file = File(rec.filePath)
                                if (file.exists()) {
                                    val uri: Uri = FileProvider.getUriForFile(context, "com.example.sg100usb.provider", file)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "SG-100 Speed Recording — ${rec.fileName}")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Export ${rec.fileName}"))
                                }
                            } catch (_: Exception) {}
                        },
                        onDelete = { onDelete(rec) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingTile(
    recording: SpeedRecording,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PanelBg,
            titleContentColor = TextMain,
            textContentColor = TextLabel,
            title = { Text("Delete recording?", fontWeight = FontWeight.Black) },
            text  = { Text("This cannot be undone.", fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = RedA),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Delete", fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextMuted) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, HtGreen.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(HtGreen, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(recording.fileName, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val dur = if (recording.durationSec > 0L) formatDuration(recording.durationSec) else "saved"
            val pts = if (recording.pointCount > 0) "${recording.pointCount} pts" else ""
            val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(recording.createdAt))
            Text(dur, color = TextMuted, fontSize = 11.sp)
            if (pts.isNotEmpty()) Text("·  $pts", color = TextMuted, fontSize = 11.sp)
            Text("·  $date", color = TextMuted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // Share button
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(HtGreen.copy(alpha = 0.15f))
                    .border(1.dp, HtGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onShare)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("↑", color = HtGreen, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text("Share", color = HtGreen, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(8.dp))
            // Delete button
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RedA.copy(alpha = 0.10f))
                    .border(1.dp, RedA.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { confirmDelete = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✕  Delete", color = RedA, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Drawing / Utilities
// ─────────────────────────────────────────────────────────────────────────────
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
        val left     = (size.width  - diameter) / 2f
        val top      = (size.height - diameter) / 2f
        val arcSize  = Size(diameter, diameter)
        val start    = 90f + (360f - sweep) / 2f
        drawArc(TrackClr, start, sweep, false, Offset(left, top), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
        val gaugeColor = when {
            animated < 0.72f -> tint
            animated < 0.90f -> AmberA
            else             -> RedA
        }
        if (animated > 0f) {
            drawArc(gaugeColor, start, sweep * animated, false, Offset(left, top), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
        }
        val angle  = Math.toRadians((start + sweep * animated).toDouble())
        val radius = diameter / 2f
        val centre = Offset(left + radius, top + radius)
        val dot    = Offset(centre.x + cos(angle).toFloat() * radius, centre.y + sin(angle).toFloat() * radius)
        drawCircle(gaugeColor.copy(alpha = 0.2f), strokePx * 0.7f, dot)
        drawCircle(gaugeColor, strokePx * 0.35f, dot)
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
        drawLine(BorderClr, Offset(0f, centerY), Offset(canvasSize.width, centerY), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        return
    }
    val start = points.size - visible
    var maxV = points[start].value; var minV = points[start].value
    for (i in start + 1 until points.size) {
        val v = points[i].value
        if (v > maxV) maxV = v
        if (v < minV) minV = v
    }
    maxV = maxV.coerceAtLeast(minV + 1f)
    val range = (maxV - minV).coerceAtLeast(1f)
    val stepX = canvasSize.width / (visible - 1)
    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
        drawLine(BorderClr.copy(alpha = 0.5f), Offset(0f, canvasSize.height * (1f - frac)), Offset(canvasSize.width, canvasSize.height * (1f - frac)), strokeWidth = 0.7.dp.toPx())
    }
    for (i in 1 until visible) {
        val a = points[start + i - 1]; val b = points[start + i]
        val x1 = stepX * (i - 1); val y1 = canvasSize.height - ((a.value - minV) / range) * canvasSize.height
        val x2 = stepX * i;       val y2 = canvasSize.height - ((b.value - minV) / range) * canvasSize.height
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = strokeDp.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun rememberPulse(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseFloat",
    )
    return if (active) pulse else 0f
}

private fun formatOne(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun formatDuration(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return if (m > 0) "${m}m ${s.toString().padStart(2, '0')}s" else "${s}s"
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration Flags — bit-level toggle section for packed registers
// ─────────────────────────────────────────────────────────────────────────────

private data class BitOption(val label: String, val register: Int, val bit: Int)

private val configFlagOptions = listOf(
    BitOption("CAN Bus Mode",         40069, 12),
    BitOption("Synch / Load Share",   40069, 10),
    BitOption("Binary Spd Up/Dn",     40069,  9),
    BitOption("Ext Speed Trim",       40069,  8),
    BitOption("Adj Actuator Output",  40069, 11),
    BitOption("Enable Droop",         40071, 12),
)

@Composable
private fun ConfigFlagsSection(
    settings: Map<Int, EditableRegister>,
    onWrite: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 2-column grid of bit toggles
        configFlagOptions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    val regRaw   = settings[opt.register]?.register?.raw ?: 0
                    val isPending = settings[opt.register]?.writeStatus == WriteStatus.Pending
                    val isActive = (regRaw ushr opt.bit) and 1 == 1
                    BitToggleCard(
                        label     = opt.label,
                        isActive  = isActive,
                        isPending = isPending,
                        modifier  = Modifier.weight(1f),
                        onToggle  = {
                            val newVal = if (isActive) regRaw and (1 shl opt.bit).inv()
                                        else           regRaw or  (1 shl opt.bit)
                            onWrite(opt.register, newVal)
                        },
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
        // Relay Config — mutually exclusive (bit 14 of 40071)
        RelaySegmentRow(
            register   = 40071,
            bit        = 14,
            settings   = settings,
            onWrite    = onWrite,
        )
    }
}

@Composable
private fun BitToggleCard(
    label: String,
    isActive: Boolean,
    isPending: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor  = if (isActive) HtGreen.copy(alpha = 0.10f) else PanelBg
    val border   = if (isActive) HtGreen.copy(alpha = 0.45f) else BorderClr
    val textClr  = if (isActive) HtGreen else TextLabel

    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .then(if (!isPending) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = textClr, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        // Mini toggle pill
        Box(
            Modifier
                .width(30.dp).height(16.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isActive) HtGreen else TrackClr),
            contentAlignment = if (isActive) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(2.dp).size(12.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun RelaySegmentRow(
    register: Int,
    bit: Int,
    settings: Map<Int, EditableRegister>,
    onWrite: (Int, Int) -> Unit,
) {
    val regRaw    = settings[register]?.register?.raw ?: 0
    val isPending = settings[register]?.writeStatus == WriteStatus.Pending
    // bit 14 = 1 → Overspeed relay; bit 14 = 0 → Crank Speed relay
    val isOverspeed = (regRaw ushr bit) and 1 == 1

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelBg)
            .border(1.dp, BorderClr, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Relay Config", color = TextLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        // Crank Speed option
        RelayOption(
            label     = "Crank Speed",
            selected  = !isOverspeed,
            isPending = isPending,
            onClick   = { onWrite(register, regRaw and (1 shl bit).inv()) },
        )
        Spacer(Modifier.width(4.dp))
        // Overspeed option
        RelayOption(
            label     = "Overspeed",
            selected  = isOverspeed,
            isPending = isPending,
            onClick   = { onWrite(register, regRaw or (1 shl bit)) },
        )
    }
}

@Composable
private fun RelayOption(label: String, selected: Boolean, isPending: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) HtGreen.copy(alpha = 0.12f) else TrackClr)
            .border(1.dp, if (selected) HtGreen.copy(alpha = 0.5f) else BorderClr, RoundedCornerShape(8.dp))
            .then(if (!isPending) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape)
            .background(if (selected) HtGreen else BorderClr)
            .border(1.dp, if (selected) HtGreen else TextMuted, CircleShape))
        Text(label, color = if (selected) HtGreen else TextMuted,
            fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 5 — Monitor (read-only live view)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MonitorScreen(polling: PollingSnapshot) {
    val input = polling.input
    val rpm     = input?.engineSpeedRpm ?: 0
    val pwmEv   = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER)
    val reqSpd  = input?.requestedSpeedRpm ?: 0
    val syncV   = input?.syncVoltage ?: 0.0
    val actCurr = EngineeringFormats.register(input, 30057)
    val actPos  = EngineeringFormats.register(input, 30058)
    val fw      = input?.value(30062) ?: 0
    val ctrl    = input?.value(30063) ?: 0
    val statusBits = input?.statusBits ?: emptyMap()
    val inputBits  = input?.inputBits  ?: emptyMap()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Big live gauges ───────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactGaugePanel("ENGINE RPM", rpm.toDouble(), 4000.0, "$rpm", "RPM", HtGreen, Modifier.weight(1f).fillMaxHeight())
            CompactGaugePanel("PWM OUTPUT", pwmEv.displayValue.coerceAtMost(100.0), 100.0, pwmEv.text, "PWM", AmberA, Modifier.weight(1f).fillMaxHeight())
        }

        // ── Secondary readings ────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadonlyTile("REQ SPEED",  "$reqSpd RPM",                    HtGreen,  Modifier.weight(1f).fillMaxHeight())
            ReadonlyTile("SYNC V",     String.format(Locale.US, "%.3f V", syncV),  HtGreenLt, Modifier.weight(1f).fillMaxHeight())
            ReadonlyTile("ACT CURRENT",actCurr.text,                     AmberA,   Modifier.weight(1f).fillMaxHeight())
            ReadonlyTile("ACT POS",    actPos.text,                      RedA,     Modifier.weight(1f).fillMaxHeight())
        }

        // ── Governor status bits ──────────────────────────────────────────────
        MonitorPanel(title = "Governor Status") {
            val statusOrder = listOf(
                "Droop input status",
                "Actuator overcurrent",
                "Gain2 selection input",
                "Overspeed occurred",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                statusOrder.forEach { label ->
                    val active = statusBits[label] == true
                    StatusBitChip(label = label.take(14), active = active, tint = if (label.contains("Overspeed")) RedA else HtGreen, modifier = Modifier.weight(1f))
                }
            }
        }

        // ── Digital inputs ────────────────────────────────────────────────────
        MonitorPanel(title = "Digital Inputs") {
            val inputOrder = listOf(
                "Speed2 input",
                "Speed3 input",
                "Gain input",
                "Fn key",
                "Plus key",
                "Minus key",
                "Idle input",
                "Pickup sensor input",
            )
            val shortLabels = listOf("Speed 2", "Speed 3", "Gain", "Fn Key", "Plus", "Minus", "Idle", "Pickup")
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                inputOrder.chunked(4).forEachIndexed { rowIdx, rowKeys ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        rowKeys.forEachIndexed { colIdx, key ->
                            val active = inputBits[key] == true
                            val shortLabel = shortLabels.getOrElse(rowIdx * 4 + colIdx) { key.take(7) }
                            StatusBitChip(label = shortLabel, active = active, tint = HtGreenLt, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ── Device info ───────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBg)
                .border(1.dp, BorderClr, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val fwText = if (fw > 0) String.format(Locale.US, "%.2f", fw / 100.0) else "--"
            val ctrlText = when (ctrl) { 50 -> "SG-50"; 110 -> "SG-110"; 2008300 -> "SG-2008300"; 0 -> "--"; else -> "$ctrl" }
            ReadonlyInfoPair("Firmware", fwText, HtGreen)
            Box(Modifier.width(1.dp).fillMaxHeight().background(BorderClr))
            ReadonlyInfoPair("Controller", ctrlText, HtGreenLt)
            Box(Modifier.width(1.dp).fillMaxHeight().background(BorderClr))
            ReadonlyInfoPair("Poll Rate", if (polling.controllerOnline) "${formatOne(polling.pollingRateHz)} Hz" else "offline", if (polling.controllerOnline) AmberA else TextMuted)
        }
    }
}

@Composable
private fun ReadonlyTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelBg)
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = TextMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(value, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MonitorPanel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelBg)
            .border(1.dp, BorderClr, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(3.dp).height(11.dp).clip(RoundedCornerShape(50)).background(HtGreen))
            Text(title.uppercase(Locale.US), color = TextLabel, fontSize = 9.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        content()
    }
}

@Composable
private fun StatusBitChip(label: String, active: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) tint.copy(alpha = 0.12f) else TrackClr)
            .border(1.dp, if (active) tint.copy(alpha = 0.5f) else BorderClr, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (active) tint else BorderClr, CircleShape))
        Text(label, color = if (active) tint else TextMuted, fontSize = 8.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.Normal,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReadonlyInfoPair(label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}
