package com.example.sg100usb.protocol

import java.util.Locale

data class RegisterValue(
    val definition: RegisterDefinition,
    val raw: Int,
) {
    val hex: String = raw.hex16()
}

data class DecodedRegisterBlock(
    val startRegister: Int,
    val functionCode: Int,
    val registers: Map<Int, RegisterValue>,
    val statusBits: Map<String, Boolean> = emptyMap(),
    val inputBits: Map<String, Boolean> = emptyMap(),
    val crc: CrcResult,
    val rawHex: String,
) {
    fun value(address: Int): Int = registers[address]?.raw ?: 0
}

data class WriteResponse(
    val functionCode: Int,
    val registerOffset: Int,
    val valueOrQuantity: Int,
    val crc: CrcResult,
    val rawHex: String,
)

sealed class ModbusDecodeResult {
    data class RegisterBlock(val block: DecodedRegisterBlock) : ModbusDecodeResult()
    data class WriteAck(val response: WriteResponse) : ModbusDecodeResult()
    data class ExceptionResponse(val functionCode: Int, val exceptionCode: Int, val rawHex: String) : ModbusDecodeResult()
}

object RegisterDecoder {
    fun extractFrame(report: ByteArray): ByteArray? {
        if (report.size < 5) return null
        for (offset in 0..1) {
            val frame = extractFrameAt(report, offset)
            if (frame != null) return frame
        }
        for (offset in 2 until report.size - 4) {
            val frame = extractFrameAt(report, offset)
            if (frame != null) return frame
        }
        return null
    }

    private fun extractFrameAt(report: ByteArray, offset: Int): ByteArray? {
        if (report.size - offset < 5) return null
        val function = report[offset + 1].u8()
        val expectedLength = when {
            function and 0x80 != 0 -> 5
            function == Sg100Registers.READ_INPUT_REGISTERS || function == Sg100Registers.READ_HOLDING_REGISTERS -> {
                if (report.size - offset < 3) return null
                3 + report[offset + 2].u8() + 2
            }
            function == Sg100Registers.WRITE_SINGLE_REGISTER -> 8
            function == Sg100Registers.WRITE_MULTIPLE_REGISTERS -> 8
            else -> null
        }
        if (expectedLength != null && report.size - offset >= expectedLength) {
            val candidate = report.copyOfRange(offset, offset + expectedLength)
            if (Crc16.verify(candidate).ok) return candidate
        }
        for (length in 5..(report.size - offset)) {
            val candidate = report.copyOfRange(offset, offset + length)
            if (Crc16.verify(candidate).ok) return candidate
        }
        return null
    }

    fun decode(frame: ByteArray): ModbusDecodeResult {
        val modbusFrame = extractFrame(frame) ?: error("No valid Modbus RTU frame found")
        val crc = Crc16.verify(modbusFrame)
        require(crc.ok) { "CRC mismatch received=${crc.received.hex16()} calculated=${crc.calculated.hex16()}" }
        val function = modbusFrame[1].u8()
        if (function and 0x80 != 0) {
            return ModbusDecodeResult.ExceptionResponse(function, modbusFrame[2].u8(), modbusFrame.toHex())
        }
        return when (function) {
            Sg100Registers.READ_INPUT_REGISTERS -> ModbusDecodeResult.RegisterBlock(
                decodeRegisterBlock(modbusFrame, Sg100Registers.INPUT_START, Sg100Registers.input, crc)
            )
            Sg100Registers.READ_HOLDING_REGISTERS -> ModbusDecodeResult.RegisterBlock(
                decodeRegisterBlock(modbusFrame, Sg100Registers.HOLDING_START, Sg100Registers.holding, crc)
            )
            Sg100Registers.WRITE_SINGLE_REGISTER, Sg100Registers.WRITE_MULTIPLE_REGISTERS -> ModbusDecodeResult.WriteAck(
                WriteResponse(
                    functionCode = function,
                    registerOffset = wordAt(modbusFrame, 2),
                    valueOrQuantity = wordAt(modbusFrame, 4),
                    crc = crc,
                    rawHex = modbusFrame.toHex(),
                )
            )
            else -> error("Unsupported function ${function.hex8()}")
        }
    }

    private fun decodeRegisterBlock(
        frame: ByteArray,
        startRegister: Int,
        map: Map<Int, RegisterDefinition>,
        crc: CrcResult,
    ): DecodedRegisterBlock {
        val byteCount = frame[2].u8()
        require(byteCount % 2 == 0) { "Register byte count must be even" }
        val values = linkedMapOf<Int, RegisterValue>()
        var payloadIndex = 3
        repeat(byteCount / 2) { offset ->
            val address = startRegister + offset
            val raw = wordAt(frame, payloadIndex)
            val definition = map[address] ?: RegisterDefinition(address, "r$address", "Register $address")
            values[address] = RegisterValue(definition, raw)
            payloadIndex += 2
        }
        return DecodedRegisterBlock(
            startRegister = startRegister,
            functionCode = frame[1].u8(),
            registers = values,
            statusBits = if (startRegister == Sg100Registers.INPUT_START) decodeBits(values[30052]?.raw ?: 0, Sg100Registers.status30052Bits) else emptyMap(),
            inputBits = if (startRegister == Sg100Registers.INPUT_START) decodeBits(values[30056]?.raw ?: 0, Sg100Registers.input30056Bits) else emptyMap(),
            crc = crc,
            rawHex = frame.toHex(),
        )
    }

    private fun decodeBits(value: Int, bitMap: Map<Int, String>): Map<String, Boolean> =
        bitMap.entries.associate { (bit, label) -> label to ((value and (1 shl bit)) != 0) }
}

fun wordAt(bytes: ByteArray, index: Int): Int =
    ((bytes[index].u8()) shl 8) or bytes[index + 1].u8()

fun Byte.u8(): Int = toInt() and 0xFF

fun Int.hex8(): String = String.format(Locale.US, "0x%02X", this and 0xFF)

fun Int.hex16(): String = String.format(Locale.US, "0x%04X", this and 0xFFFF)

fun ByteArray.toHex(): String = joinToString(" ") { String.format(Locale.US, "%02X", it.u8()) }
