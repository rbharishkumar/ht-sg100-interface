package com.example.sg100usb.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sg100usb.data.EditableRegister
import com.example.sg100usb.data.GraphSeries
import com.example.sg100usb.data.PollingManager
import com.example.sg100usb.data.PollingSnapshot
import com.example.sg100usb.data.RealTimeGraphManager
import com.example.sg100usb.data.RecordedPoint
import com.example.sg100usb.data.RegisterRepository
import com.example.sg100usb.data.SettingsManager
import com.example.sg100usb.data.SpeedRecording
import com.example.sg100usb.data.WriteResult
import com.example.sg100usb.format.EngineeringFormats
import com.example.sg100usb.protocol.PacketLogEntry
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.Sg100Registers
import com.example.sg100usb.protocol.engineSpeedRpm
import com.example.sg100usb.usb.UsbHidManager
import com.example.sg100usb.usb.UsbHidState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val packetLogger = PacketLogger()
    private val usbHidManager = UsbHidManager(application)
    private val graphManager = RealTimeGraphManager()
    private val settingsManager = SettingsManager()
    private val repository = RegisterRepository(usbHidManager, packetLogger)
    private val pollingManager = PollingManager(repository, graphManager, packetLogger, viewModelScope)

    val usbState: StateFlow<UsbHidState> = usbHidManager.state
    val polling: StateFlow<PollingSnapshot> = pollingManager.snapshot
    val graph: StateFlow<GraphSeries> = graphManager.series
    val packetLog: StateFlow<List<PacketLogEntry>> = packetLogger.entries
    val settings: StateFlow<Map<Int, EditableRegister>> = settingsManager.edited

    private val _resettingDefaults = MutableStateFlow(false)
    val resettingDefaults: StateFlow<Boolean> = _resettingDefaults.asStateFlow()

    // ── Recording ────────────────────────────────────────────────────────────
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<SpeedRecording>>(emptyList())
    val savedRecordings: StateFlow<List<SpeedRecording>> = _savedRecordings.asStateFlow()

    private val recordingBuffer = mutableListOf<RecordedPoint>()
    private var recordingStartMs = 0L
    private var recordingTimerJob: Job? = null
    private var recordingCollectJob: Job? = null

    private var reconnectJob: Job? = null

    init {
        usbHidManager.connectBestDevice()
        startReconnectLoop()

        viewModelScope.launch {
            usbHidManager.state
                .map { it.connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        reconnectJob?.cancel()
                        loadHoldingRegisters()
                        pollingManager.start()
                    } else {
                        pollingManager.stop()
                        startReconnectLoop()
                    }
                }
        }

        // Load existing recordings from disk on startup
        loadExistingRecordings()
    }

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            while (isActive) {
                delay(RECONNECT_INTERVAL_MS)
                val s = usbHidManager.state.value
                if (!s.connected && !s.permissionPending) {
                    usbHidManager.connectBestDevice()
                }
            }
        }
    }

    private suspend fun loadHoldingRegisters() {
        repeat(3) { attempt ->
            if (attempt > 0) delay(400)
            val result = runCatching { repository.readHoldingRegisters() }
            result.onSuccess { block ->
                settingsManager.loadHoldingRegisters(block.registers)
                packetLogger.message("CFG", "Loaded ${block.registers.size} holding registers")
                return
            }
            result.onFailure {
                packetLogger.message("CFG", "Holding read attempt ${attempt + 1} failed: ${it.message}")
            }
        }
    }

    fun editRegister(address: Int, value: Int) = settingsManager.edit(address, value)

    fun writeRegister(address: Int, value: Int) {
        viewModelScope.launch {
            settingsManager.markPending(address)
            pollingManager.pause()
            val result = try {
                repository.writeSingleRegister(address, value)
            } catch (e: Exception) {
                WriteResult.Failure(e.message ?: "Unexpected write error")
            }
            pollingManager.resume()
            when (result) {
                is WriteResult.Success -> {
                    settingsManager.markClean(address, value)
                    packetLogger.message("WRITE", "OK $address=$value (echo confirmed)")
                }
                is WriteResult.SuccessWithHolding -> {
                    val actual = result.block.value(address)
                    settingsManager.loadHoldingRegisters(result.block.registers)
                    settingsManager.markClean(address, actual)
                    packetLogger.message("WRITE", "OK $address=$actual (readback confirmed)")
                }
                is WriteResult.Failure -> {
                    settingsManager.markError(address, result.reason)
                    packetLogger.message("WRITE", "FAIL $address: ${result.reason}")
                }
            }
        }
    }

    fun clearWriteStatus(address: Int) = settingsManager.clearWriteStatus(address)

    fun resetToDefaults() {
        if (_resettingDefaults.value) return
        viewModelScope.launch {
            _resettingDefaults.value = true
            val writableRegisters = Sg100Registers.holding.values
                .filter { it.writable }
                .sortedBy { it.address }

            writableRegisters.forEach { def -> settingsManager.markPending(def.address) }

            pollingManager.pause()
            packetLogger.message("RESET", "Writing factory defaults to ${writableRegisters.size} registers")

            try {
                writableRegisters.forEach { def ->
                    val result = try {
                        repository.writeSingleRegister(def.address, def.default)
                    } catch (e: Exception) {
                        WriteResult.Failure(e.message ?: "Write error")
                    }
                    when (result) {
                        is WriteResult.Success -> {
                            settingsManager.markClean(def.address, def.default)
                            packetLogger.message("RESET", "OK ${def.address}=${def.default}")
                        }
                        is WriteResult.SuccessWithHolding -> {
                            val actual = result.block.value(def.address)
                            settingsManager.loadHoldingRegisters(result.block.registers)
                            settingsManager.markClean(def.address, actual)
                            packetLogger.message("RESET", "OK ${def.address}=$actual (readback)")
                        }
                        is WriteResult.Failure -> {
                            settingsManager.markError(def.address, result.reason)
                            packetLogger.message("RESET", "FAIL ${def.address}: ${result.reason}")
                        }
                    }
                }
                packetLogger.message("RESET", "Factory defaults write sequence complete")
            } finally {
                pollingManager.resume()
                _resettingDefaults.value = false
            }
        }
    }

    fun setGraphZoom(zoom: Float) = graphManager.setZoom(zoom)

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        recordingBuffer.clear()
        recordingStartMs = System.currentTimeMillis()
        _recordingDurationSec.value = 0L

        recordingTimerJob = viewModelScope.launch {
            while (isActive && _isRecording.value) {
                delay(1000L)
                _recordingDurationSec.value = (System.currentTimeMillis() - recordingStartMs) / 1000L
            }
        }

        recordingCollectJob = viewModelScope.launch {
            polling.collect { snapshot ->
                if (_isRecording.value && snapshot.controllerOnline && snapshot.input != null) {
                    val rpm = snapshot.input.engineSpeedRpm.toFloat()
                    val pwm = EngineeringFormats.register(snapshot.input, Sg100Registers.PWM_REGISTER)
                        .displayValue.toFloat()
                    val current = EngineeringFormats.register(snapshot.input, 30057)
                        .displayValue.toFloat()
                    synchronized(recordingBuffer) {
                        recordingBuffer.add(RecordedPoint(System.currentTimeMillis(), rpm, pwm, current))
                    }
                }
            }
        }
    }

    fun stopAndSaveRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingCollectJob?.cancel()
        val durationSec = _recordingDurationSec.value
        _recordingDurationSec.value = 0L

        val snapshot = synchronized(recordingBuffer) { recordingBuffer.toList() }
        recordingBuffer.clear()

        if (snapshot.isEmpty()) return

        viewModelScope.launch {
            val saved = saveRecordingToFile(snapshot, durationSec)
            if (saved != null) {
                _savedRecordings.value = listOf(saved) + _savedRecordings.value
                packetLogger.message("REC", "Saved ${saved.fileName} (${snapshot.size} points)")
            }
        }
    }

    fun deleteRecording(recording: SpeedRecording) {
        runCatching { File(recording.filePath).delete() }
        _savedRecordings.value = _savedRecordings.value.filter { it.id != recording.id }
    }

    private fun saveRecordingToFile(points: List<RecordedPoint>, durationSec: Long): SpeedRecording? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "SG100_$ts.csv"
            val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: getApplication<Application>().filesDir
            val file = File(dir, fileName)
            file.bufferedWriter().use { w ->
                w.write("timestamp_ms,time_sec,rpm,pwm_percent,actuator_current_a\n")
                val t0 = points.first().timeMs
                points.forEach { p ->
                    w.write("${p.timeMs},${String.format(Locale.US, "%.3f", (p.timeMs - t0) / 1000.0)},${p.rpm},${p.pwmPercent},${p.actuatorCurrentA}\n")
                }
            }
            SpeedRecording(
                id = System.currentTimeMillis(),
                fileName = fileName,
                filePath = file.absolutePath,
                durationSec = durationSec,
                pointCount = points.size,
                createdAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            packetLogger.message("REC", "Save failed: ${e.message}")
            null
        }
    }

    private fun loadExistingRecordings() {
        viewModelScope.launch {
            val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: return@launch
            val files = dir.listFiles { f -> f.name.startsWith("SG100_") && f.name.endsWith(".csv") }
                ?: return@launch
            val loaded = files.sortedByDescending { it.lastModified() }.map { f ->
                SpeedRecording(
                    id = f.lastModified(),
                    fileName = f.name,
                    filePath = f.absolutePath,
                    durationSec = 0L,
                    pointCount = 0,
                    createdAt = f.lastModified(),
                )
            }
            _savedRecordings.value = loaded
        }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        recordingTimerJob?.cancel()
        recordingCollectJob?.cancel()
        pollingManager.stop()
        usbHidManager.dispose()
        super.onCleared()
    }

    companion object {
        private const val RECONNECT_INTERVAL_MS = 3000L
    }
}
