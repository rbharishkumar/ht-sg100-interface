"""Register definitions for the SG-100 13-register telemetry block.

The controller is read as one contiguous Modbus input-register block because
the SG-100 board has been observed to reject or mishandle single-register reads.
Function 04 returns two bytes per register, most significant byte first.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class RegisterDefinition:
    address: int
    key: str
    label: str
    unit: str = ""
    decoder: Callable[[int], object] = int


REGISTER_START = 30051
REGISTER_COUNT = 13
SG100_SLAVE_ID = 0x01
READ_INPUT_REGISTERS = 0x04

# Modbus PDU addresses are zero-based. For 3xxxx input registers, many tools
# enter 30051 while the wire address is 50. This request reads 30051..30063.
REGISTER_START_ZERO_BASED = REGISTER_START - 30001


def identity(value: int) -> int:
    return value


def high_byte(value: int) -> int:
    return (value >> 8) & 0xFF


def low_byte(value: int) -> int:
    return value & 0xFF


REGISTER_MAP: dict[int, RegisterDefinition] = {
    30051: RegisterDefinition(30051, "pwm_percent", "PWM % / Actuator Position", "%", identity),
    30052: RegisterDefinition(30052, "engine_speed_status", "Engine Speed bits 0-11 + Status Bits 12-15", "RPM", identity),
    30053: RegisterDefinition(30053, "requested_speed_rpm", "Requested Speed", "RPM", identity),
    30054: RegisterDefinition(30054, "sync_voltage", "Sync Voltage", "V", identity),
    30055: RegisterDefinition(30055, "firmware_controller_packed", "Firmware MSB + Controller LSB", "", identity),
    30056: RegisterDefinition(30056, "input_status_bits", "Input Status Bits", "", identity),
    30057: RegisterDefinition(30057, "actuator_current", "Actuator Current", "", identity),
    30058: RegisterDefinition(30058, "actuator_position", "Actuator Position", "", identity),
    30059: RegisterDefinition(30059, "free_30059", "Free", "", identity),
    30060: RegisterDefinition(30060, "free_30060", "Free", "", identity),
    30061: RegisterDefinition(30061, "free_30061", "Free", "", identity),
    30062: RegisterDefinition(30062, "firmware_version", "Firmware Version", "", identity),
    30063: RegisterDefinition(30063, "controller_type", "Controller Type", "", identity),
}


STATUS_30052_BITS: dict[int, str] = {
    12: "Droop input status",
    13: "Actuator overcurrent",
    14: "Gain2 selection input",
    15: "Overspeed occurred",
}

INPUT_30056_BITS: dict[int, str] = {
    0: "Speed2 input",
    1: "Speed3 input",
    2: "Gain input",
    3: "Fn key",
    4: "Plus key",
    5: "Minus key",
    6: "Idle input",
    7: "Pickup sensor input",
}
