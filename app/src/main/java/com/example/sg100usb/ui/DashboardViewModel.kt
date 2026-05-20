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
import com.example.sg100usb.protocol.PacketLogEntry
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.usb.UsbHidManager
import com.example.sg100usb.usb.UsbHidState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val packetLogger = PacketLogger()
    private val usbHidManager = UsbHidManager(application)
    private val graphManager = RealTimeGraphManager()
    private val settingsManager = SettingsManager()
    private val repository = RegisterRepository(usbHidManager, packetLogger)
    private val pollingManager = PollingManager(repository, graphManager, settingsManager, packetLogger, viewModelScope)

    val usbState: StateFlow<UsbHidState> = usbHidManager.state
    val polling: StateFlow<PollingSnapshot> = pollingManager.snapshot
    val graph: StateFlow<GraphSeries> = graphManager.series
    val packetLog: StateFlow<List<PacketLogEntry>> = packetLogger.entries
    val settings: StateFlow<Map<Int, EditableRegister>> = settingsManager.edited

    fun connect() {
        viewModelScope.launch {
            packetLogger.message("USB", "Connect requested")
            runCatching { repository.connect() }.onFailure {
                packetLogger.message("USB", it.message ?: "Connection failed")
            }
        }
    }

    fun startPolling() {
        viewModelScope.launch {
            packetLogger.message("USB", "Connect requested before polling")
            runCatching { repository.connect() }.onFailure {
                packetLogger.message("USB", it.message ?: "Connection failed")
            }
            pollingManager.start()
        }
    }

    fun stopPolling() = pollingManager.stop()

    fun editRegister(address: Int, value: Int) = settingsManager.edit(address, value)

    fun writeRegister(address: Int, value: Int) {
        viewModelScope.launch {
            runCatching {
                repository.writeSingleRegister(address, value)
                settingsManager.markClean(address, value)
            }.onFailure {
                packetLogger.message("WRITE", it.message ?: "Write failed")
            }
        }
    }

    fun setGraphZoom(zoom: Float) = graphManager.setZoom(zoom)

    override fun onCleared() {
        pollingManager.stop()
        usbHidManager.dispose()
        super.onCleared()
    }
}
