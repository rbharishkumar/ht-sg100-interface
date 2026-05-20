package com.example.sg100usb.protocol

object ModbusPacketBuilder {
    fun readInputRegisters(
        slaveId: Int = Sg100Registers.SLAVE_ID,
        startRegister: Int = Sg100Registers.INPUT_START,
        quantity: Int = Sg100Registers.INPUT_COUNT,
    ): ByteArray = readRegisters(slaveId, Sg100Registers.READ_INPUT_REGISTERS, startRegister, quantity)

    fun readHoldingRegisters(
        slaveId: Int = Sg100Registers.SLAVE_ID,
        startRegister: Int = Sg100Registers.HOLDING_START,
        quantity: Int = Sg100Registers.HOLDING_COUNT,
    ): ByteArray = readRegisters(slaveId, Sg100Registers.READ_HOLDING_REGISTERS, startRegister, quantity)

    fun writeSingleRegister(slaveId: Int, register: Int, value: Int): ByteArray {
        require(value in 0..0xFFFF) { "Register value must be 0..65535" }
        val offset = displayRegisterToOffset(register)
        val frame = byteArrayOf(
            slaveId.toByte(),
            Sg100Registers.WRITE_SINGLE_REGISTER.toByte(),
            (offset shr 8).toByte(),
            (offset and 0xFF).toByte(),
            (value shr 8).toByte(),
            (value and 0xFF).toByte(),
        )
        return Crc16.appendTo(frame)
    }

    fun writeMultipleRegisters(slaveId: Int, startRegister: Int, values: List<Int>): ByteArray {
        require(values.isNotEmpty()) { "At least one value is required" }
        require(values.size <= 123) { "Maximum Modbus multiple-write quantity is 123 registers" }
        values.forEach { require(it in 0..0xFFFF) { "Register value must be 0..65535" } }
        val offset = displayRegisterToOffset(startRegister)
        val byteCount = values.size * 2
        val body = ByteArray(7 + byteCount)
        body[0] = slaveId.toByte()
        body[1] = Sg100Registers.WRITE_MULTIPLE_REGISTERS.toByte()
        body[2] = (offset shr 8).toByte()
        body[3] = (offset and 0xFF).toByte()
        body[4] = (values.size shr 8).toByte()
        body[5] = (values.size and 0xFF).toByte()
        body[6] = byteCount.toByte()
        values.forEachIndexed { index, value ->
            val dest = 7 + index * 2
            body[dest] = (value shr 8).toByte()
            body[dest + 1] = (value and 0xFF).toByte()
        }
        return Crc16.appendTo(body)
    }

    private fun readRegisters(slaveId: Int, function: Int, startRegister: Int, quantity: Int): ByteArray {
        require(slaveId in 1..247) { "Slave id must be 1..247" }
        require(quantity in 1..125) { "Read quantity must be 1..125" }
        val offset = displayRegisterToOffset(startRegister)
        val frame = byteArrayOf(
            slaveId.toByte(),
            function.toByte(),
            (offset shr 8).toByte(),
            (offset and 0xFF).toByte(),
            (quantity shr 8).toByte(),
            (quantity and 0xFF).toByte(),
        )
        return Crc16.appendTo(frame)
    }

    fun displayRegisterToOffset(register: Int): Int {
        val base = when (register) {
            in 30001..39999 -> 30001
            in 40001..49999 -> 40001
            else -> error("Unsupported register $register")
        }
        return register - base
    }
}
