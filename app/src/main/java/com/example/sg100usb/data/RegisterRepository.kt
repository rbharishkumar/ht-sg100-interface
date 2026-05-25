package com.example.sg100usb.data

import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.ModbusDecodeResult
import com.example.sg100usb.protocol.ModbusPacketBuilder
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.RegisterDecoder
import com.example.sg100usb.usb.UsbHidManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class WriteResult {
    object Success : WriteResult()
    data class SuccessWithHolding(val block: DecodedRegisterBlock) : WriteResult()
    data class Failure(val reason: String) : WriteResult()
}

class RegisterRepository(
    private val usbHidManager: UsbHidManager,
    private val packetLogger: PacketLogger,
) {
    suspend fun connect() = withContext(Dispatchers.IO) {
        usbHidManager.connectBestDevice()
    }

    suspend fun readInputRegisters(): DecodedRegisterBlock = withContext(Dispatchers.IO) {
        val tx = ModbusPacketBuilder.readInputRegisters()
        packetLogger.tx("Read input 30051..30063", tx)
        val rx = usbHidManager.exchange(tx)
        packetLogger.rx("Input response", rx)
        when (val decoded = RegisterDecoder.decode(rx)) {
            is ModbusDecodeResult.RegisterBlock -> decoded.block
            is ModbusDecodeResult.ExceptionResponse -> error("Modbus exception ${decoded.exceptionCode}")
            is ModbusDecodeResult.WriteAck -> error("Unexpected write acknowledgement")
        }
    }

    suspend fun readHoldingRegisters(): DecodedRegisterBlock = withContext(Dispatchers.IO) {
        val tx = ModbusPacketBuilder.readHoldingRegisters()
        packetLogger.tx("Read holding 40051..40077", tx)
        val rx = usbHidManager.exchange(tx)
        packetLogger.rx("Holding response", rx)
        when (val decoded = RegisterDecoder.decode(rx)) {
            is ModbusDecodeResult.RegisterBlock -> decoded.block
            is ModbusDecodeResult.ExceptionResponse -> error("Modbus exception ${decoded.exceptionCode}")
            is ModbusDecodeResult.WriteAck -> error("Unexpected write acknowledgement")
        }
    }

    suspend fun writeSingleRegister(address: Int, value: Int): WriteResult {
        val offset = ModbusPacketBuilder.displayRegisterToOffset(address)

        packetLogger.message("WRITE", "Workers stopping — exclusive bus access")

        // Write-enable: PC software writes 0x80 to register 40079 before configuration writes.
        // Using FC16 (write multiple) so this works even if the device only supports FC16.
        val writeEnableTx = ModbusPacketBuilder.writeMultipleRegisters(1, WRITE_ENABLE_REGISTER, listOf(WRITE_ENABLE_VALUE))
        packetLogger.tx("FC16 Write-enable | addr=$WRITE_ENABLE_REGISTER value=0x${WRITE_ENABLE_VALUE.toString(16).uppercase()}", writeEnableTx)
        try {
            val enableRx = usbHidManager.exclusiveWrite(writeEnableTx, timeoutMs = WRITE_ECHO_TIMEOUT_MS)
            packetLogger.rx("Write-enable echo", enableRx)
        } catch (e: Exception) {
            packetLogger.message("WRITE", "Write-enable no echo: ${e.message} — proceeding with main write")
        }
        delay(WRITE_SETTLE_MS)

        // Main write via FC16 (write multiple registers, count=1).
        // Some Modbus devices only support FC16 for configuration registers, not FC06.
        val fc16Tx = ModbusPacketBuilder.writeMultipleRegisters(1, address, listOf(value))
        packetLogger.tx(
            "FC16 Write | addr=$address" +
                " offset=0x${offset.toString(16).uppercase().padStart(4, '0')}" +
                " value=$value (0x${value.toString(16).uppercase().padStart(4, '0')})",
            fc16Tx,
        )

        // Step 1 — exclusive write with FC16.
        var echoVerified = false
        try {
            val rx = usbHidManager.exclusiveWrite(fc16Tx, timeoutMs = WRITE_ECHO_TIMEOUT_MS)
            packetLogger.rx("FC16 Echo RX", rx)
            val decoded = RegisterDecoder.decode(rx)
            if (decoded is ModbusDecodeResult.WriteAck) {
                val ack = decoded.response
                // FC16 echo carries the register count (1), not the written value, so only check offset.
                if (ack.registerOffset == offset) {
                    echoVerified = true
                    packetLogger.message(
                        "WRITE",
                        "ACK verified | FC${ack.functionCode.toString(16).uppercase()}" +
                            " offset=0x${offset.toString(16).uppercase().padStart(4, '0')}" +
                            " value=$value",
                    )
                } else {
                    packetLogger.message(
                        "WRITE",
                        "ACK offset mismatch | expected offset=$offset got offset=${ack.registerOffset}",
                    )
                }
            } else {
                packetLogger.message("WRITE", "No write echo (got ${decoded::class.simpleName}) — will readback")
            }
        } catch (e: Exception) {
            packetLogger.message("WRITE", "Write echo error: ${e.message} — will readback")
        }

        if (echoVerified) return WriteResult.Success

        // Step 2 — no valid echo; workers are restarted by exclusiveWrite's finally block,
        // so we can use normal exchange for the FC03 readback.
        return try {
            delay(WRITE_SETTLE_MS)
            val readTx = ModbusPacketBuilder.readHoldingRegisters()
            packetLogger.tx("FC03 Post-write readback | addr=$address", readTx)
            val readRx = usbHidManager.exchange(readTx, priority = true)
            packetLogger.rx("FC03 Readback RX", readRx)
            when (val decoded = RegisterDecoder.decode(readRx)) {
                is ModbusDecodeResult.RegisterBlock -> {
                    val stored = decoded.block.value(address)
                    packetLogger.message("WRITE", "Readback | addr=$address stored=$stored wrote=$value")
                    WriteResult.SuccessWithHolding(decoded.block)
                }
                else -> WriteResult.Failure("FC03 readback returned unexpected response")
            }
        } catch (e: Exception) {
            WriteResult.Failure(
                when {
                    e.message?.contains("CRC") == true          -> "CRC error: ${e.message}"
                    e.message?.contains("No HID response") == true -> "No response from device"
                    else -> e.message ?: "Write failed"
                }
            )
        }
    }

    suspend fun writeMultipleRegisters(startAddress: Int, values: List<Int>): WriteResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val tx = ModbusPacketBuilder.writeMultipleRegisters(1, startAddress, values)
            packetLogger.tx("Write multiple $startAddress count=${values.size}", tx)
            try {
                val rx = usbHidManager.exchange(tx, timeoutMs = WRITE_ECHO_TIMEOUT_MS, priority = true)
                packetLogger.rx("Write multiple ack", rx)
            } catch (_: Exception) {
                packetLogger.message("WRITE", "No echo for multi-write — verifying by readback")
            }
            delay(WRITE_SETTLE_MS)
            val readTx = ModbusPacketBuilder.readHoldingRegisters()
            val readRx = usbHidManager.exchange(readTx)
            when (val decoded = RegisterDecoder.decode(readRx)) {
                is ModbusDecodeResult.RegisterBlock -> WriteResult.SuccessWithHolding(decoded.block)
                else -> WriteResult.Failure("Unexpected response to readback")
            }
        } catch (e: Exception) {
            WriteResult.Failure(e.message ?: "Multi-write failed")
        }
    }

    companion object {
        private const val WRITE_ECHO_TIMEOUT_MS = 300
        private const val WRITE_SETTLE_MS = 200L
        private const val WRITE_ENABLE_REGISTER = 40079
        private const val WRITE_ENABLE_VALUE = 0x80
    }
}
