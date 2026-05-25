package com.example.sg100usb.data

import android.os.SystemClock
import com.example.sg100usb.format.EngineeringFormats
import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.Sg100Registers
import com.example.sg100usb.protocol.engineSpeedRpm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val packetLogger: PacketLogger,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(PollingSnapshot())
    val snapshot: StateFlow<PollingSnapshot> = _snapshot.asStateFlow()
    private var job: Job? = null
    private var consecutiveFailures = 0

    fun start(intervalMs: Long = 100L) {
        if (job?.isActive == true) return
        packetLogger.message("POLL", "Started 100 ms SG-100 input polling: 01 04 00 32 00 0D 90 00")
        job = scope.launch {
            var nextPollAt = SystemClock.elapsedRealtime()
            while (isActive) {
                runCatching {
                    val input = repository.readInputRegisters()
                    val rpm = input.engineSpeedRpm
                    val pwm = EngineeringFormats.register(input, Sg100Registers.PWM_REGISTER).displayValue.toFloat()
                    val current = EngineeringFormats.register(input, 30057).displayValue.toFloat()
                    graphManager.add(rpm.toFloat(), pwm, current)
                    consecutiveFailures = 0
                    _snapshot.value = PollingSnapshot(
                        input = input,
                        holding = _snapshot.value.holding,
                        pollingRateHz = 1000f / intervalMs,
                        controllerOnline = true,
                        error = null,
                    )
                }.onFailure { error ->
                    consecutiveFailures += 1
                    packetLogger.message("POLL RETRY", error.message ?: "Unknown polling error")
                    if (consecutiveFailures >= VISIBLE_FAILURE_THRESHOLD) {
                        _snapshot.value = _snapshot.value.copy(
                            controllerOnline = false,
                            error = "Communication retrying",
                        )
                    }
                }
                nextPollAt += intervalMs
                val now = SystemClock.elapsedRealtime()
                if (nextPollAt < now) nextPollAt = now + intervalMs
                delay((nextPollAt - now).coerceAtLeast(10L))
            }
        }
    }

    fun stop() {
        packetLogger.message("POLL", "Stopped")
        consecutiveFailures = 0
        job?.cancel()
        job = null
    }

    private var pollWasRunning = false

    fun pause() {
        pollWasRunning = job?.isActive == true
        if (pollWasRunning) {
            job?.cancel()
            job = null
            packetLogger.message("POLL", "Paused for write")
        }
    }

    fun resume() {
        if (pollWasRunning) {
            pollWasRunning = false
            start()
            packetLogger.message("POLL", "Resumed after write")
        }
    }

    private companion object {
        const val VISIBLE_FAILURE_THRESHOLD = 3
    }
}
