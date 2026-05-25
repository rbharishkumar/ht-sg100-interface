package com.example.sg100usb.data

import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.ModbusDecodeResult
import com.example.sg100usb.protocol.ModbusPacketBuilder
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.RegisterDecoder
import com.example.sg100usb.usb.UsbHidManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class WriteResult {
    object Success : WriteResult()
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
            val rx = usbHidManager.exchange(tx, priority = true)
            packetLogger.rx("Write ack", rx)
            val decoded = RegisterDecoder.decode(rx)
            if (decoded !is ModbusDecodeResult.WriteAck) {
                return@withContext WriteResult.Failure("Controller did not acknowledge write")
            }
            val ack = decoded.response
            val expectedOffset = ModbusPacketBuilder.displayRegisterToOffset(address)
            if (ack.registerOffset != expectedOffset) {
                return@withContext WriteResult.Failure(
                    "Echo address mismatch: sent offset $expectedOffset, got ${ack.registerOffset}"
                )
            }
            if (ack.valueOrQuantity != value) {
                return@withContext WriteResult.Failure(
                    "Echo value mismatch: sent $value, got ${ack.valueOrQuantity}"
                )
            }
            WriteResult.Success
        } catch (e: Exception) {
            val reason = when {
                e.message?.contains("CRC") == true -> "CRC error: ${e.message}"
                e.message?.contains("timeout", ignoreCase = true) == true -> "Timeout: no response from device"
                else -> e.message ?: "Write failed"
            }
            WriteResult.Failure(reason)
        }
    }

    suspend fun writeMultipleRegisters(startAddress: Int, values: List<Int>): WriteResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val tx = ModbusPacketBuilder.writeMultipleRegisters(1, startAddress, values)
            packetLogger.tx("Write multiple $startAddress count=${values.size}", tx)
            val rx = usbHidManager.exchange(tx, priority = true)
            packetLogger.rx("Write multiple ack", rx)
            val decoded = RegisterDecoder.decode(rx)
            if (decoded !is ModbusDecodeResult.WriteAck) {
                return@withContext WriteResult.Failure("Controller did not acknowledge multiple write")
            }
            WriteResult.Success
        } catch (e: Exception) {
            val reason = when {
                e.message?.contains("CRC") == true -> "CRC error: ${e.message}"
                e.message?.contains("timeout", ignoreCase = true) == true -> "Timeout: no response from device"
                else -> e.message ?: "Write failed"
            }
            WriteResult.Failure(reason)
        }
    }
}
