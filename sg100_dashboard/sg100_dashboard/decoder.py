"""Modbus RTU packet parser and SG-100 register decoder."""

from __future__ import annotations

from dataclasses import dataclass

from .models import DecodedStatus, ParsedPacket, RegisterValue
from .register_map import (
    INPUT_30056_BITS,
    READ_INPUT_REGISTERS,
    REGISTER_COUNT,
    REGISTER_MAP,
    REGISTER_START,
    SG100_SLAVE_ID,
    STATUS_30052_BITS,
)


class PacketDecodeError(ValueError):
    """Raised when an SG-100 response frame is malformed or fails CRC."""


@dataclass(frozen=True)
class ParsedRegisterBlock:
    slave_id: int
    function_code: int
    byte_count: int
    register_words: list[int]
    crc_received: int
    crc_calculated: int
    crc_ok: bool


def bytes_to_uint16(high: int, low: int) -> int:
    """Convert two Modbus data bytes into one unsigned 16-bit register word."""
    return ((high & 0xFF) << 8) | (low & 0xFF)


def crc16_modbus(data: bytes) -> int:
    """Return the standard Modbus RTU CRC16 value as an integer.

    The wire format is low byte first, so a calculated CRC of 0xC8B5 appears
    in the packet as B5 C8.
    """
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x0001:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF


def verify_crc(frame: bytes) -> tuple[bool, int, int]:
    """Validate the two trailing CRC bytes of a Modbus RTU frame."""
    if len(frame) < 4:
        return False, 0, 0
    calculated = crc16_modbus(frame[:-2])
    received = frame[-2] | (frame[-1] << 8)
    return calculated == received, received, calculated


def decode_status_bits(value: int, bit_map: dict[int, str]) -> DecodedStatus:
    """Decode a register bitfield using a bit-number-to-label map.

    Bit numbers follow normal least-significant-bit numbering. For example,
    bit 15 is tested with mask 0x8000 and bit 0 is tested with mask 0x0001.
    """
    return DecodedStatus(
        raw=value & 0xFFFF,
        flags={label: bool(value & (1 << bit)) for bit, label in bit_map.items()},
    )


def parse_register_block(
    frame: bytes,
    *,
    expected_slave_id: int = SG100_SLAVE_ID,
    expected_function_code: int = READ_INPUT_REGISTERS,
    expected_register_count: int = REGISTER_COUNT,
    require_crc: bool = True,
) -> ParsedRegisterBlock:
    """Parse the raw Modbus RTU response into a contiguous register word list.

    Expected response shape:

    - byte 0: slave id
    - byte 1: function code, 0x04 for input-register reads
    - byte 2: data byte count, 26 for 13 registers
    - bytes 3..28: register payload, big-endian words
    - final two bytes: Modbus CRC, low byte then high byte
    """
    if len(frame) < 5:
        raise PacketDecodeError(f"Frame too short: {len(frame)} bytes")

    slave_id = frame[0]
    function_code = frame[1]
    byte_count = frame[2]
    expected_byte_count = expected_register_count * 2
    expected_length = 3 + expected_byte_count + 2

    if slave_id != expected_slave_id:
        raise PacketDecodeError(f"Unexpected slave id 0x{slave_id:02X}")
    if function_code != expected_function_code:
        if function_code & 0x80:
            code = frame[2] if len(frame) > 2 else 0
            raise PacketDecodeError(f"Modbus exception response 0x{function_code:02X}, code {code}")
        raise PacketDecodeError(f"Unexpected function code 0x{function_code:02X}")
    if byte_count != expected_byte_count:
        raise PacketDecodeError(f"Unexpected byte count {byte_count}; expected {expected_byte_count}")
    if len(frame) != expected_length:
        raise PacketDecodeError(f"Unexpected frame length {len(frame)}; expected {expected_length}")

    crc_ok, crc_received, crc_calculated = verify_crc(frame)
    if require_crc and not crc_ok:
        raise PacketDecodeError(
            f"CRC mismatch received=0x{crc_received:04X} calculated=0x{crc_calculated:04X}"
        )

    payload = frame[3 : 3 + byte_count]
    register_words = [
        bytes_to_uint16(payload[index], payload[index + 1])
        for index in range(0, len(payload), 2)
    ]
    return ParsedRegisterBlock(
        slave_id=slave_id,
        function_code=function_code,
        byte_count=byte_count,
        register_words=register_words,
        crc_received=crc_received,
        crc_calculated=crc_calculated,
        crc_ok=crc_ok,
    )


def decode_sg100_packet(frame: bytes, *, tx_packet: bytes = b"") -> ParsedPacket:
    """Decode one SG-100 30051..30063 response into UI-ready structured data."""
    block = parse_register_block(frame)
    registers: dict[int, RegisterValue] = {}

    for offset, raw_value in enumerate(block.register_words):
        address = REGISTER_START + offset
        definition = REGISTER_MAP[address]
        registers[address] = RegisterValue(
            address=address,
            key=definition.key,
            label=definition.label,
            raw=raw_value,
            hex_value=f"{raw_value:04X}",
            value=definition.decoder(raw_value),
            unit=definition.unit,
        )

    status = decode_status_bits(registers[30052].raw, STATUS_30052_BITS)
    input_status = decode_status_bits(registers[30056].raw, INPUT_30056_BITS)

    return ParsedPacket(
        slave_id=block.slave_id,
        function_code=block.function_code,
        byte_count=block.byte_count,
        registers=registers,
        status=status,
        input_status=input_status,
        tx_hex=to_hex(tx_packet),
        rx_hex=to_hex(frame),
        crc_received=block.crc_received,
        crc_calculated=block.crc_calculated,
        crc_ok=block.crc_ok,
    )


def build_read_registers_request(
    *,
    slave_id: int = SG100_SLAVE_ID,
    start_zero_based: int = 50,
    quantity: int = REGISTER_COUNT,
) -> bytes:
    """Build the SG-100 block-read request with a valid Modbus RTU CRC."""
    body = bytes(
        [
            slave_id & 0xFF,
            READ_INPUT_REGISTERS,
            (start_zero_based >> 8) & 0xFF,
            start_zero_based & 0xFF,
            (quantity >> 8) & 0xFF,
            quantity & 0xFF,
        ]
    )
    crc = crc16_modbus(body)
    return body + bytes([crc & 0xFF, (crc >> 8) & 0xFF])


def to_hex(data: bytes) -> str:
    return " ".join(f"{byte:02X}" for byte in data)


def from_hex(text: str) -> bytes:
    cleaned = text.replace(",", " ").replace("0x", "").replace("0X", "")
    parts = [part for part in cleaned.split() if part]
    return bytes(int(part, 16) for part in parts)
