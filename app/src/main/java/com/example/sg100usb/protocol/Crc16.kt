package com.example.sg100usb.protocol

object Crc16 {
    fun modbus(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (index in 0 until length) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun appendTo(frameWithoutCrc: ByteArray): ByteArray {
        val crc = modbus(frameWithoutCrc)
        return frameWithoutCrc + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    fun verify(frame: ByteArray): CrcResult {
        if (frame.size < 4) return CrcResult(false, 0, 0)
        val calculated = modbus(frame, frame.size - 2)
        val received = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        return CrcResult(calculated == received, received, calculated)
    }
}

data class CrcResult(
    val ok: Boolean,
    val received: Int,
    val calculated: Int,
)
