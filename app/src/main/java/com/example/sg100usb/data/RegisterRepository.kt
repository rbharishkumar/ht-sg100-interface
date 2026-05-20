package com.example.sg100usb.data

import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.ModbusDecodeResult
import com.example.sg100usb.protocol.ModbusPacketBuilder
import com.example.sg100usb.protocol.PacketLogger
import com.example.sg100usb.protocol.RegisterDecoder
import com.example.sg100usb.usb.UsbHidManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun writeSingleRegister(address: Int, value: Int) = withContext(Dispatchers.IO) {
        val tx = ModbusPacketBuilder.writeSingleRegister(1, address, value)
        packetLogger.tx("Write $address=$value", tx)
        val rx = usbHidManager.exchange(tx)
        packetLogger.rx("Write ack", rx)
        val decoded = RegisterDecoder.decode(rx)
        require(decoded is ModbusDecodeResult.WriteAck) { "Controller did not acknowledge write" }
    }

    suspend fun writeMultipleRegisters(startAddress: Int, values: List<Int>) = withContext(Dispatchers.IO) {
        val tx = ModbusPacketBuilder.writeMultipleRegisters(1, startAddress, values)
        packetLogger.tx("Write multiple $startAddress count=${values.size}", tx)
        val rx = usbHidManager.exchange(tx)
        packetLogger.rx("Write multiple ack", rx)
        val decoded = RegisterDecoder.decode(rx)
        require(decoded is ModbusDecodeResult.WriteAck) { "Controller did not acknowledge multiple write" }
    }
}
