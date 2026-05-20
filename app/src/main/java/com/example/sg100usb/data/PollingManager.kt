package com.example.sg100usb.data

import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.PacketLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

data class PollingSnapshot(
    val input: DecodedRegisterBlock? = null,
    val holding: DecodedRegisterBlock? = null,
    val pollingRateHz: Float = 0f,
    val controllerOnline: Boolean = false,
    val error: String? = null,
)

class PollingManager(
    private val repository: RegisterRepository,
    private val graphManager: RealTimeGraphManager,
    private val settingsManager: SettingsManager,
    private val packetLogger: PacketLogger,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(PollingSnapshot())
    val snapshot: StateFlow<PollingSnapshot> = _snapshot.asStateFlow()
    private var job: Job? = null

    fun start(intervalMs: Long = 250L) {
        if (job?.isActive == true) return
        packetLogger.message("POLL", "Started 250 ms SG-100 polling")
        job = scope.launch {
            while (isActive) {
                val elapsed = measureTimeMillis {
                    runCatching {
                        val input = repository.readInputRegisters()
                        val holding = runCatching { repository.readHoldingRegisters() }
                            .onFailure { packetLogger.message("HOLDING READ", it.message ?: "Holding register read failed") }
                            .getOrNull()
                        val rpm = input.value(30053)
                        val pwm = input.value(30051)
                        val current = input.value(30057)
                        graphManager.add(rpm, pwm, current)
                        holding?.let { settingsManager.loadHoldingRegisters(it.registers) }
                        _snapshot.value = PollingSnapshot(
                            input = input,
                            holding = holding ?: _snapshot.value.holding,
                            pollingRateHz = 1000f / intervalMs,
                            controllerOnline = true,
                        )
                    }.onFailure { error ->
                        packetLogger.message("POLL ERROR", error.message ?: "Unknown polling error")
                        _snapshot.value = _snapshot.value.copy(controllerOnline = false, error = error.message)
                    }
                }
                delay((intervalMs - elapsed).coerceAtLeast(10L))
            }
        }
    }

    fun stop() {
        packetLogger.message("POLL", "Stopped")
        job?.cancel()
        job = null
    }
}
