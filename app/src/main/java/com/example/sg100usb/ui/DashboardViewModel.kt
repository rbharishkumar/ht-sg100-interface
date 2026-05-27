package com.example.sg100usb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sg100usb.data.EditableRegister
import com.example.sg100usb.data.GraphSeries
import com.example.sg100usb.data.PollingManager
import com.example.sg100usb.data.PollingSnapshot
import com.example.sg100usb.data.RealTimeGraphManager
import com.example.sg100usb.data.RegisterRepository
import com.example.sg100usb.data.SettingsManager
import com.example.sg100usb.data.WriteResult
import com.example.sg100usb.protocol.PacketLogEntry
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.Sg100Registers
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

    private var reconnectJob: Job? = null

    init {
        // Attempt to connect immediately — device may already be plugged in at launch.
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

            // Mark every register as pending so the UI shows progress immediately
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

    override fun onCleared() {
        reconnectJob?.cancel()
        pollingManager.stop()
        usbHidManager.dispose()
        super.onCleared()
    }

    companion object {
        private const val RECONNECT_INTERVAL_MS = 3000L
    }
}
