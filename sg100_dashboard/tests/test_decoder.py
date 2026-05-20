from sg100_dashboard.decoder import (
    build_read_registers_request,
    crc16_modbus,
    decode_sg100_packet,
    from_hex,
    parse_register_block,
    verify_crc,
)


SAMPLE = (
    "01 04 1A 00 00 40 00 05 DB 00 00 00 00 00 84 "
    "00 00 00 00 00 00 00 00 00 00 00 FB 00 37 B5 C8"
)


def test_crc_and_register_alignment():
    frame = from_hex(SAMPLE)
    ok, received, calculated = verify_crc(frame)
    assert ok
    assert received == 0xC8B5
    assert calculated == 0xC8B5

    block = parse_register_block(frame)
    assert block.byte_count == 26
    assert block.register_words == [
        0x0000,
        0x4000,
        0x05DB,
        0x0000,
        0x0000,
        0x0084,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x00FB,
        0x0037,
    ]


def test_decode_sg100_packet():
    packet = decode_sg100_packet(from_hex(SAMPLE))
    assert packet.engine_speed_rpm == 0
    assert packet.requested_speed_rpm == 1499
    assert packet.pwm_percent == 0
    assert packet.sync_voltage == 0.0
    assert packet.registers[30052].raw == 0x4000
    assert packet.status.flags["Gain2 selection input"] is True
    assert packet.status.flags["Overspeed occurred"] is False
    assert packet.input_status.flags["Gain input"] is True
    assert packet.input_status.flags["Pickup sensor input"] is True
    assert packet.firmware_version == 0x00FB
    assert packet.controller_type == 0x0037


def test_default_request_crc():
    assert build_read_registers_request().hex(" ").upper() == "01 04 00 32 00 0D 90 00"


def test_engine_speed_uses_register_30052_low_12_bits():
    words = [
        0x0000,
        0x8ABC,
        0x05DB,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x00FB,
        0x0037,
    ]
    payload = b"".join(word.to_bytes(2, "big") for word in words)
    body = bytes([0x01, 0x04, len(payload)]) + payload
    crc = crc16_modbus(body)
    frame = body + bytes([crc & 0xFF, (crc >> 8) & 0xFF])

    packet = decode_sg100_packet(frame)

    assert packet.registers[30052].raw == 0x8ABC
    assert packet.engine_speed_rpm == 0x0ABC


def test_sync_voltage_is_scaled_to_thousandths():
    words = [
        0x0032,
        0x0000,
        0x05DB,
        0x0FA8,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x0000,
        0x00FB,
        0x0037,
    ]
    payload = b"".join(word.to_bytes(2, "big") for word in words)
    body = bytes([0x01, 0x04, len(payload)]) + payload
    crc = crc16_modbus(body)
    frame = body + bytes([crc & 0xFF, (crc >> 8) & 0xFF])

    packet = decode_sg100_packet(frame)

    assert packet.pwm_percent == 50
    assert packet.sync_voltage == 4.008
