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

    suspend fun writeSingleRegister(address: Int, value: Int): WriteResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val tx = ModbusPacketBuilder.writeSingleRegister(1, address, value)
            packetLogger.tx("Write $address=$value", tx)

            // Send the write. The SG-100 may not echo FC06 responses — catch the
            // timeout silently and fall through to readback verification instead.
            try {
                val rx = usbHidManager.exchange(tx, timeoutMs = WRITE_ECHO_TIMEOUT_MS, priority = true)
                packetLogger.rx("Write echo", rx)
                val decoded = RegisterDecoder.decode(rx)
                if (decoded is ModbusDecodeResult.WriteAck) {
                    val ack = decoded.response
                    val expectedOffset = ModbusPacketBuilder.displayRegisterToOffset(address)
                    if (ack.registerOffset != expectedOffset || ack.valueOrQuantity != value) {
                        return@withContext WriteResult.Failure(
                            "Echo mismatch: sent offset=$expectedOffset value=$value, got offset=${ack.registerOffset} value=${ack.valueOrQuantity}"
                        )
                    }
                }
            } catch (_: Exception) {
                packetLogger.message("WRITE", "No echo — verifying by readback")
            }

            // Give the device time to commit the value, then read back all
            // holding registers to confirm and refresh the Configure screen.
            delay(WRITE_SETTLE_MS)
            val readTx = ModbusPacketBuilder.readHoldingRegisters()
            packetLogger.tx("Post-write readback", readTx)
            val readRx = usbHidManager.exchange(readTx)
            packetLogger.rx("Readback response", readRx)

            when (val decoded = RegisterDecoder.decode(readRx)) {
                is ModbusDecodeResult.RegisterBlock -> {
                    val actual = decoded.block.value(address)
                    if (actual == value) {
                        WriteResult.SuccessWithHolding(decoded.block)
                    } else {
                        WriteResult.Failure("Readback mismatch: wrote $value, device returned $actual")
                    }
                }
                else -> WriteResult.Failure("Unexpected response to readback")
            }
        } catch (e: Exception) {
            WriteResult.Failure(
                when {
                    e.message?.contains("CRC") == true -> "CRC error: ${e.message}"
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
        private const val WRITE_ECHO_TIMEOUT_MS = 150
        private const val WRITE_SETTLE_MS = 100L
    }
}
