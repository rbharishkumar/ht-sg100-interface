"""Data models passed between parser, polling worker, and UI."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime


@dataclass(frozen=True)
class RegisterValue:
    address: int
    key: str
    label: str
    raw: int
    hex_value: str
    value: object
    unit: str = ""


@dataclass(frozen=True)
class DecodedStatus:
    raw: int
    flags: dict[str, bool]


@dataclass(frozen=True)
class ParsedPacket:
    slave_id: int
    function_code: int
    byte_count: int
    registers: dict[int, RegisterValue]
    status: DecodedStatus
    input_status: DecodedStatus
    tx_hex: str = ""
    rx_hex: str = ""
    crc_received: int = 0
    crc_calculated: int = 0
    crc_ok: bool = False
    received_at: datetime = field(default_factory=datetime.now)

    @property
    def requested_speed_rpm(self) -> int | None:
        return _int_value(self.registers, 30053)

    @property
    def engine_speed_rpm(self) -> int | None:
        raw = _int_value(self.registers, 30052)
        if raw is None:
            return None
        return raw & 0x0FFF

    @property
    def pwm_percent(self) -> int | None:
        return _int_value(self.registers, 30051)

    @property
    def sync_voltage(self) -> float | None:
        raw = _int_value(self.registers, 30054)
        if raw is None:
            return None
        return raw / 1000.0

    @property
    def register_30051_raw(self) -> int | None:
        return _int_value(self.registers, 30051)

    @property
    def actuator_current(self) -> int | None:
        return _int_value(self.registers, 30057)

    @property
    def actuator_position(self) -> int | None:
        return _int_value(self.registers, 30058)

    @property
    def firmware_version(self) -> int | None:
        return _int_value(self.registers, 30062)

    @property
    def controller_type(self) -> int | None:
        return _int_value(self.registers, 30063)

    @property
    def firmware_controller_packed(self) -> tuple[int, int] | None:
        raw = _int_value(self.registers, 30055)
        if raw is None:
            return None
        return ((raw >> 8) & 0xFF, raw & 0xFF)


def _int_value(registers: dict[int, RegisterValue], address: int) -> int | None:
    value = registers.get(address)
    if value is None:
        return None
    return int(value.raw)
