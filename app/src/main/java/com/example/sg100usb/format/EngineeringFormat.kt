package com.example.sg100usb.format

import com.example.sg100usb.protocol.DecodedRegisterBlock
import com.example.sg100usb.protocol.Sg100Registers
import java.util.Locale

data class RegisterEngineeringMetadata(
    val address: Int,
    val displayLabel: String,
    val unit: String = "",
    val scale: Double = 1.0,
    val decimals: Int = 0,
    val unitSeparator: String = " ",
    val min: Double? = null,
    val max: Double? = null,
)

data class EngineeringValue(
    val raw: Int,
    val value: Double,
    val displayValue: Double,
    val text: String,
    val metadata: RegisterEngineeringMetadata,
    val outOfRange: Boolean,
)

object EngineeringFormats {
    val engineRpm = RegisterEngineeringMetadata(
        address = Sg100Registers.ENGINE_SPEED_REGISTER,
        displayLabel = "Engine Speed",
        unit = "RPM",
        min = 0.0,
        max = 4000.0,
    )

    val registers = mapOf(
        Sg100Registers.PWM_REGISTER to RegisterEngineeringMetadata(
            address = Sg100Registers.PWM_REGISTER,
            displayLabel = "PWM / Position",
            unit = "%",
            scale = 1.0,
            decimals = 0,
            unitSeparator = "",
            min = 0.0,
            // No max clamp: if the device sends values > 100 the real number will be
            // visible, allowing engineers to identify the correct scale factor.
            // Expected range 0-100 for direct-percent encoding; if consistently >100
            // the device uses a higher-resolution encoding (e.g. 0-1000 → scale=10).
        ),
        Sg100Registers.REQUESTED_SPEED_REGISTER to RegisterEngineeringMetadata(
            address = Sg100Registers.REQUESTED_SPEED_REGISTER,
            displayLabel = "Requested Speed",
            unit = "RPM",
            min = 0.0,
            max = 4000.0,
        ),
        Sg100Registers.SYNC_VOLTAGE_REGISTER to RegisterEngineeringMetadata(
            address = Sg100Registers.SYNC_VOLTAGE_REGISTER,
            displayLabel = "Sync Voltage",
            unit = "V",
            scale = 1000.0,
            decimals = 3,
            min = 0.0,
        ),
        30057 to RegisterEngineeringMetadata(
            address = 30057,
            displayLabel = "Actuator Current",
            unit = "A",
            scale = 100.0,
            decimals = 2,
            unitSeparator = "",
            min = 0.0,
        ),
        30058 to RegisterEngineeringMetadata(
            address = 30058,
            displayLabel = "Actuator Position",
            unit = "%",
            scale = 1.0,
            decimals = 0,
            unitSeparator = "",
            min = 0.0,
            // No max clamp for same reason as PWM_REGISTER above.
        ),
    )

    fun register(block: DecodedRegisterBlock?, address: Int): EngineeringValue {
        val metadata = registers[address] ?: RegisterEngineeringMetadata(address, "Register $address")
        return value(block?.value(address) ?: 0, metadata)
    }

    fun rpm(raw: Int): EngineeringValue = value(raw, engineRpm)

    fun value(raw: Int, metadata: RegisterEngineeringMetadata): EngineeringValue {
        val engineeringValue = raw / metadata.scale
        val displayValue = clamp(engineeringValue, metadata)
        val outOfRange = engineeringValue != displayValue
        return EngineeringValue(
            raw = raw,
            value = engineeringValue,
            displayValue = displayValue,
            text = format(displayValue, metadata),
            metadata = metadata,
            outOfRange = outOfRange,
        )
    }

    private fun format(value: Double, metadata: RegisterEngineeringMetadata): String {
        val number = if (metadata.decimals == 0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.${metadata.decimals}f", value)
        }
        return if (metadata.unit.isBlank()) {
            number
        } else {
            "$number${metadata.unitSeparator}${metadata.unit}"
        }
    }

    private fun clamp(value: Double, metadata: RegisterEngineeringMetadata): Double =
        value
            .let { metadata.min?.let(it::coerceAtLeast) ?: it }
            .let { metadata.max?.let(it::coerceAtMost) ?: it }
}
