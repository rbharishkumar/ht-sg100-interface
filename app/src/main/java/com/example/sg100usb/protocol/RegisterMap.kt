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
    const val HOLDING_COUNT = 27

    val input = listOf(
        RegisterDefinition(30051, "pwm", "PWM % / actuator position", "%", 0, 100),
        RegisterDefinition(30052, "engineSpeedStatus", "Engine speed bits 0-11 + status bits 12-15", "rpm", 0, 4000),
        RegisterDefinition(30053, "requestedSpeed", "Requested speed", "RPM", 0, 4000),
        RegisterDefinition(30054, "syncVoltage", "Sync voltage", "V", 0, 10000),
        RegisterDefinition(30055, "firmwarePacked", "Firmware MSB + controller LSB"),
        RegisterDefinition(30056, "inputStatus", "Input status bits"),
        RegisterDefinition(30057, "actuatorCurrent", "Actuator current"),
        RegisterDefinition(30058, "actuatorPosition", "Actuator position", "%", 0, 100),
        RegisterDefinition(30059, "free30059", "Free"),
        RegisterDefinition(30060, "free30060", "Free"),
        RegisterDefinition(30061, "free30061", "Free"),
        RegisterDefinition(30062, "firmwareVersion", "Firmware version"),
        RegisterDefinition(30063, "controllerType", "Controller type"),
    ).associateBy { it.address }

    val holding = listOf(
        RegisterDefinition(40051, "overspeed", "Overspeed setting", "RPM", 0, 6000, true, RegisterControl.SLIDER),
        RegisterDefinition(40052, "startFuel", "Start fuel position", "%", 0, 100, true, RegisterControl.SLIDER),
        RegisterDefinition(40053, "speedRamp", "Speed ramp time", "s", 0, 120, true, RegisterControl.SLIDER),
        RegisterDefinition(40054, "fuelRamp", "Fuel ramp time", "s", 0, 120, true, RegisterControl.SLIDER),
        RegisterDefinition(40055, "crankTermination", "Crank termination", "RPM", 0, 2000, true, RegisterControl.SLIDER),
        RegisterDefinition(40056, "speedTrimFs", "Speed Trim FS", "", 0, 65535, true),
        RegisterDefinition(40057, "speedTrimDs", "Speed Trim DS", "", 0, 65535, true),
        RegisterDefinition(40058, "idleSpeed", "Idle speed", "RPM", 0, 3000, true, RegisterControl.SLIDER),
        RegisterDefinition(40059, "speed3", "Speed3", "RPM", 0, 5000, true, RegisterControl.SLIDER),
        RegisterDefinition(40060, "speed2", "Speed2", "RPM", 0, 5000, true, RegisterControl.SLIDER),
        RegisterDefinition(40061, "speed1", "Speed1", "RPM", 0, 5000, true, RegisterControl.SLIDER),
        RegisterDefinition(40062, "gearTeeth", "Gear teeth", "", 1, 255, true, RegisterControl.SLIDER),
        RegisterDefinition(40063, "positionMode", "Position mode", "", 0, 1, true, RegisterControl.SWITCH),
        RegisterDefinition(40064, "positionPidD2", "Position PID D2", "", 0, 65535, true),
        RegisterDefinition(40065, "positionPidI2", "Position PID I2", "", 0, 65535, true),
        RegisterDefinition(40066, "speedPidD1", "Speed PID D1", "", 0, 65535, true),
        RegisterDefinition(40067, "speedPidI1", "Speed PID I1", "", 0, 65535, true),
        RegisterDefinition(40068, "speedPidP1", "Speed PID P1", "", 0, 65535, true),
        RegisterDefinition(40069, "configurationBits", "Configuration bits", "", 0, 65535, true),
        RegisterDefinition(40070, "pidLoopTime", "PID loop time", "ms", 0, 1000, true, RegisterControl.SLIDER),
        RegisterDefinition(40071, "pwmFrequencyDroop", "PWM frequency + droop", "", 0, 65535, true),
        RegisterDefinition(40072, "fullLoadCurrent", "Full load current", "", 0, 65535, true),
        RegisterDefinition(40073, "free40073", "Free", "", 0, 65535, true),
        RegisterDefinition(40074, "noLoadCurrent", "No load current", "", 0, 65535, true),
        RegisterDefinition(40075, "accelerationTime", "Acceleration time", "s", 0, 120, true, RegisterControl.SLIDER),
        RegisterDefinition(40076, "decelerationTime", "Deceleration time", "s", 0, 120, true, RegisterControl.SLIDER),
        RegisterDefinition(40077, "advancedConfigurationBits", "Advanced configuration bits", "", 0, 65535, true),
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
