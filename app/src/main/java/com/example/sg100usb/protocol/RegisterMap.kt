package com.example.sg100usb.protocol

data class RegisterDefinition(
    val address: Int,
    val key: String,
    val label: String,
    val unit: String = "",
    val min: Int = 0,
    val max: Int = 65535,
    val writable: Boolean = false,
    val control: RegisterControl = RegisterControl.NUMERIC,
    val default: Int = 0,
    val note: String = "",
)

enum class RegisterControl {
    NUMERIC,
    SLIDER,
    SWITCH,
    DROPDOWN,
}

object Sg100Registers {
    const val SLAVE_ID = 1
    const val READ_HOLDING_REGISTERS = 0x03
    const val READ_INPUT_REGISTERS = 0x04
    const val WRITE_SINGLE_REGISTER = 0x06
    const val WRITE_MULTIPLE_REGISTERS = 0x10
    const val INPUT_START = 30051
    const val INPUT_COUNT = 13
    const val PWM_REGISTER = 30051
    const val ENGINE_SPEED_REGISTER = 30052
    const val ENGINE_SPEED_MASK = 0x0FFF
    const val REQUESTED_SPEED_REGISTER = 30053
    const val SYNC_VOLTAGE_REGISTER = 30054
    const val HOLDING_START = 40051
    const val HOLDING_COUNT = 31

    val input = listOf(
        RegisterDefinition(30051, "pwm", "PWM / Actuator Position", "%", 0, 100, default = 100,
            note = "Actuator output percentage."),
        RegisterDefinition(30052, "engineSpeedStatus", "Engine Speed + Status Bits", "rpm", 0, 4095, default = 0,
            note = "Bits 0-11: Engine speed in RPM. Bit 12: Droop input. Bit 13: Actuator overcurrent. Bit 14: Gain2 selection. Bit 15: Overspeed occurred."),
        RegisterDefinition(30053, "requestedSpeed", "Requested Speed", "RPM", 0, 4000, default = 0),
        RegisterDefinition(30054, "syncVoltage", "Sync Voltage", "V", 0, 10000, default = 0,
            note = "Raw value / 1000 = Volts (0.000 - 10.000 V)."),
        RegisterDefinition(30055, "firmwarePacked", "Firmware + Controller Type (Legacy)", "", 0, 65535, default = 0,
            note = "Not used in latest firmware. Kept for old firmware compatibility. Use registers 30062 and 30063 instead."),
        RegisterDefinition(30056, "inputStatus", "Digital Input Status", "", 0, 65535, default = 0,
            note = "Bit 0: Speed2 input. Bit 1: Speed3 input. Bit 2: Gain input. Bit 3: Fn key. Bit 4: Plus key. Bit 5: Minus key. Bit 6: Idle input. Bit 7: Pickup sensor input. Bits 8-15: Free."),
        RegisterDefinition(30057, "actuatorCurrent", "Actuator Current", "A", 0, 600, default = 0,
            note = "Raw value / 100 = Amps (0.00 - 6.00 A)."),
        RegisterDefinition(30058, "actuatorPosition", "Actuator Position", "%", 0, 1000, default = 0,
            note = "Raw value / 10 = % (0.0 - 100.0%)."),
        RegisterDefinition(30059, "free30059", "Free", ""),
        RegisterDefinition(30060, "free30060", "Free", ""),
        RegisterDefinition(30061, "free30061", "Free", "",
            note = "Reserved for compatibility with HSG and ddd controllers."),
        RegisterDefinition(30062, "firmwareVersion", "Firmware Version", "", 0, 65535, default = 0,
            note = "Current firmware version number."),
        RegisterDefinition(30063, "controllerType", "Controller Type", "", 0, 65535, default = 0,
            note = "Valid values: 50, 110, 2008300."),
    ).associateBy { it.address }

    val holding = listOf(
        RegisterDefinition(40051, "overspeed", "Overspeed Setting", "RPM", 0, 4000, true, RegisterControl.SLIDER, default = 2000),
        RegisterDefinition(40052, "startFuel", "Start Fuel Position", "%", 0, 100, true, RegisterControl.SLIDER, default = 50),
        RegisterDefinition(40053, "speedRamp", "Speed Ramp Time", "s", 0, 100, true, RegisterControl.SLIDER, default = 3),
        RegisterDefinition(40054, "fuelRamp", "Fuel Ramp Time", "s", 0, 100, true, RegisterControl.SLIDER, default = 1),
        RegisterDefinition(40055, "crankTermination", "Crank Termination", "RPM", 0, 2000, true, RegisterControl.SLIDER, default = 200),
        RegisterDefinition(40056, "speedTrimFs", "Speed Trim FS", "RPM", 0, 6000, true, RegisterControl.NUMERIC, default = 1800),
        RegisterDefinition(40057, "speedTrimDs", "Speed Trim DS", "RPM", 0, 6000, true, RegisterControl.NUMERIC, default = 1500),
        RegisterDefinition(40058, "idleSpeed", "Idle Speed", "RPM", 0, 3000, true, RegisterControl.SLIDER, default = 1300),
        RegisterDefinition(40059, "speed3", "Speed 3", "RPM", 0, 5000, true, RegisterControl.SLIDER, default = 1300),
        RegisterDefinition(40060, "speed2", "Speed 2", "RPM", 0, 5000, true, RegisterControl.SLIDER, default = 1400),
        RegisterDefinition(40061, "speed1", "Speed 1", "RPM", 0, 5000, true, RegisterControl.SLIDER, default = 1500),
        RegisterDefinition(40062, "gearTeethPositionMode", "Gear Teeth + Position Mode", "", 0, 65535, true, RegisterControl.NUMERIC, default = 120,
            note = "PACKED REGISTER. LSB (bits 0-7): Gear teeth count (range 50-255, default 120). MSB (bits 8-15): Position mode (0-4, default 0). When writing, preserve the other field: new_value = (position_mode shl 8) or gear_teeth."),
        RegisterDefinition(40063, "positionPidD2", "Position PID D2", "", 0, 1000, true, RegisterControl.NUMERIC, default = 300,
            note = "Raw / 10 = display value (default 30.0)."),
        RegisterDefinition(40064, "positionPidI2", "Position PID I2", "", 0, 1000, true, RegisterControl.NUMERIC, default = 100,
            note = "Raw / 10 = display value (default 10.0)."),
        RegisterDefinition(40065, "positionPidP2", "Position PID P2", "", 0, 1000, true, RegisterControl.NUMERIC, default = 500,
            note = "Raw / 10 = display value (default 50.0)."),
        RegisterDefinition(40066, "positionPidD1", "Position PID D1", "", 0, 1000, true, RegisterControl.NUMERIC, default = 400,
            note = "Raw / 10 = display value (default 40.0)."),
        RegisterDefinition(40067, "speedPidI1", "Speed PID I1", "", 0, 1000, true, RegisterControl.NUMERIC, default = 100,
            note = "Raw / 10 = display value (default 10.0)."),
        RegisterDefinition(40068, "speedPidP1", "Speed PID P1", "", 0, 1000, true, RegisterControl.NUMERIC, default = 500,
            note = "Raw / 10 = display value (default 50.0)."),
        RegisterDefinition(40069, "configCurrentLimit", "Configuration Bits + Current Limit", "", 0, 65535, true, RegisterControl.NUMERIC, default = 70,
            note = "PACKED REGISTER. LSB (bits 0-7): Current limit % (0-100, default 70%). MSB (bits 8-15): Bit 8: Speed trim selection. Bit 9: Binary speed up/down. Bit 10: Synchronization. Bit 11: Adjust actuator output. Bit 12: CAN bus. Bit 13: LeadLag. Bit 14: FastSpeed/SoftCouple. Bit 15: ACTUCOM."),
        RegisterDefinition(40070, "pidLoopTime", "PID Loop Time", "ms", 0, 255, true, RegisterControl.SLIDER, default = 10,
            note = "PACKED REGISTER. LSB (bits 0-7): PID loop time in ms (0-255, default 10 ms). MSB (bits 8-15): Free."),
        RegisterDefinition(40071, "pwmFrequencyDroop", "PWM Frequency + Droop", "", 0, 65535, true, RegisterControl.NUMERIC, default = 0,
            note = "PACKED REGISTER. Bits 0-7: Droop % (0.0-10.0%, step 0.5%, default 5.0%). Bits 8-11: PWM frequency select (30,50,70,90,110,130,150,190 Hz). Bit 12: Droop input status. Bit 14: Relay config (0=CRANK, 1=OVERSPEED)."),
        RegisterDefinition(40072, "fullLoadCurrent", "Full Load Current", "A", 0, 600, true, RegisterControl.NUMERIC, default = 200,
            note = "Raw / 100 = Amps (0.00-6.00 A, default 2.00 A)."),
        RegisterDefinition(40073, "free40073", "Free (Not Used)", "", 0, 0, false, RegisterControl.NUMERIC, default = 0,
            note = "Not used. Do not write."),
        RegisterDefinition(40074, "noLoadCurrent", "No Load Current", "A", 0, 600, true, RegisterControl.NUMERIC, default = 200,
            note = "Raw / 100 = Amps (0.00-6.00 A, default 2.00 A)."),
        RegisterDefinition(40075, "accelerationTime", "Acceleration Time", "s", 0, 250, true, RegisterControl.SLIDER, default = 50,
            note = "Raw / 10 = seconds (0.0-25.0 s, default 5.0 s)."),
        RegisterDefinition(40076, "decelerationTime", "Deceleration Time", "s", 0, 250, true, RegisterControl.SLIDER, default = 50,
            note = "Raw / 10 = seconds (0.0-25.0 s, default 5.0 s)."),
        RegisterDefinition(40077, "advancedConfig", "Advanced Configuration", "", 0, 65535, true, RegisterControl.NUMERIC, default = 0,
            note = "PACKED REGISTER. Bit 0: OverCurrent Protection (1=Enable). Bit 1: J1939/DST (1=J1939). Bit 2: Speed Trim source (1=Ext, 0=Speed3). Bit 3: Overspeed Latching (1=Latching). Bit 4: Ext speed input polarity (1=Negative). Bits 5-7: Free. Bits 8-15: Bias Voltage (0-100 = 0.0-10.0 V, default 50 = 5.0 V)."),
        RegisterDefinition(40078, "reserved40078", "Reserved (Do Not Write)", "", 0, 65535, false, RegisterControl.NUMERIC, default = 10,
            note = "Undocumented factory register. Do not write."),
        RegisterDefinition(40079, "reserved40079", "Reserved (Do Not Write)", "", 0, 65535, false, RegisterControl.NUMERIC, default = 10,
            note = "Undocumented factory register. Do not write."),
        RegisterDefinition(40080, "reserved40080", "Reserved (Do Not Write)", "", 0, 65535, false, RegisterControl.NUMERIC, default = 160,
            note = "Undocumented factory register. Do not write."),
        RegisterDefinition(40081, "reserved40081", "Reserved (Do Not Write)", "", 0, 65535, false, RegisterControl.NUMERIC, default = 5120,
            note = "Undocumented factory register. Do not write."),
    ).associateBy { it.address }

    val status30052Bits = linkedMapOf(
        12 to "Droop input status",
        13 to "Actuator overcurrent",
        14 to "Gain2 selection input",
        15 to "Overspeed occurred",
    )

    val input30056Bits = linkedMapOf(
        0 to "Speed2 input",
        1 to "Speed3 input",
        2 to "Gain input",
        3 to "Fn key",
        4 to "Plus key",
        5 to "Minus key",
        6 to "Idle input",
        7 to "Pickup sensor input",
    )
}
