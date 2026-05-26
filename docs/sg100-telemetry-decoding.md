# SG-100 Telemetry Decoding And UI Display Pipeline

This note documents how SG-100 input-register telemetry frames are interpreted
for display in the Android UI. It intentionally covers only decoding and
frontend engineering formatting. It does not change HID transport, CRC
generation, packet transmission, polling, register reads, or packet parsing.

Relevant code paths:

- `RegisterRepository.readInputRegisters()` requests SG-100 input registers.
- `RegisterDecoder.extractFrame()` finds the Modbus RTU frame inside the HID
  report.
- `RegisterDecoder.decode()` verifies CRC and dispatches by function code.
- `RegisterDecoder.decodeRegisterBlock()` splits data bytes into 16-bit raw
  register values.
- `EngineeringFormats.register()` applies display-only engineering scale and
  text formatting.
- `Sg100App` reads `PollingSnapshot.input` and renders the formatted values.

## Packet Structure

SG-100 telemetry responses use this Modbus-style frame layout:

```text
[Slave ID][Function][Byte Count][Data Bytes...][CRC Low][CRC High]
```

For a normal input-register response:

```text
Byte 0      Slave ID       0x01 for SG-100
Byte 1      Function       0x04 for read input registers
Byte 2      Byte count     Number of telemetry data bytes
Byte 3..N   Data bytes     16-bit register words, high byte first
Last - 1    CRC low byte   Low byte of Modbus CRC16
Last        CRC high byte  High byte of Modbus CRC16
```

The CRC is little-endian on the wire: low byte first, high byte second. Register
data words are big-endian: high byte first, low byte second.

## Example Packet

Example frame from the prompt:

```text
01 04 1A 00 00 40 00 05 DC 00 00 00 00 FC 84 00 00 00 00 00 00 00 00 00 00 F1 00 6E 62
```

Byte-level interpretation:

```text
00: 01    Slave ID
01: 04    Function code: read input registers
02: 1A    Byte count: 0x1A = 26 data bytes = 13 registers
03: 00    Data byte 0
04: 00    Data byte 1
05: 40    Data byte 2
06: 00    Data byte 3
07: 05    Data byte 4
08: DC    Data byte 5
...
25: F1    Data byte before CRC in the supplied listing
26: 00    Data byte before CRC in the supplied listing
27: 6E    CRC low byte in the supplied listing
28: 62    CRC high byte in the supplied listing
```

Important: `6E 62` are CRC bytes only. They are used to verify the frame and
must not be decoded as telemetry registers.

Important: `F1 00` are data bytes before the CRC and must not be interpreted as
CRC.

Consistency note: `0x1A` declares 26 data bytes, which means a complete frame is
`3 + 26 + 2 = 31` bytes long. The packet string above contains 24 data bytes
before `6E 62`, so the written example appears to be missing one 16-bit data
register. The correct decoding rule is still clear: the last two bytes are CRC,
and only bytes between byte count and CRC are telemetry data.

## Stage 1 - Packet Parsing

The app receives HID reports, which may contain padding or a HID report ID.
`RegisterDecoder.extractFrame()` scans candidate offsets and accepts the first
candidate frame whose CRC verifies.

For function `0x04` or `0x03`, the expected frame length is:

```text
expectedLength = 3 + byteCount + 2

3 = Slave ID + Function + Byte Count
2 = CRC Low + CRC High
```

Annotated parser logic:

```kotlin
val slaveId = frame[0]          // 0x01
val function = frame[1].u8()    // 0x04 for input registers
val byteCount = frame[2].u8()   // 0x1A = 26 data bytes

val dataStart = 3
val dataEndExclusive = dataStart + byteCount
val crcLowIndex = dataEndExclusive
val crcHighIndex = dataEndExclusive + 1
```

CRC verification deliberately excludes the final two CRC bytes from the CRC
calculation:

```kotlin
val calculated = Crc16.modbus(frame, frame.size - 2)
val received = frame[last - 1] low byte + frame[last] high byte
```

That is why CRC bytes are not part of the telemetry data section. They are
integrity metadata for the packet, not register values.

## Stage 2 - Register Extraction

After CRC verification, the data section is split into 16-bit registers. The
input register block starts at SG-100 register `30051` and contains 13 registers:
`30051..30063`.

The byte count must be even. Each pair of data bytes becomes one raw register:

```kotlin
rawValue = (highByte << 8) | lowByte
```

This is big-endian register decoding. The high byte is first in the data stream.

For a complete `0x1A` response, byte positions are:

```text
Frame bytes   Data offset   Register   Meaning
03..04        00..01        30051      PWM / Actuator Output
05..06        02..03        30052      Engine speed low 12 bits + status bits
07..08        04..05        30053      Requested speed
09..10        06..07        30054      Sync voltage
11..12        08..09        30055      Legacy packed value; not used for UI FW/CTRL display
13..14        10..11        30056      Input status bits
15..16        12..13        30057      Actuator current
17..18        14..15        30058      Actuator position
19..20        16..17        30059      Free
21..22        18..19        30060      Free
23..24        20..21        30061      Free
25..26        22..23        30062      Firmware version
27..28        24..25        30063      Controller type
29..30        n/a           CRC        CRC low, CRC high
```

## Stage 3 - Raw Value Decoding

Raw values are unsigned 16-bit integers:

```text
rawValue = (highByte << 8) | lowByte
```

Examples:

```text
05 DC -> (0x05 << 8) | 0xDC
      -> 0x05DC
      -> 1500 decimal
```

If `05 DC` is in the engine-speed register (`30052`), the engine-speed display
uses the lower 12 bits:

```kotlin
engineSpeedRpm = raw30052 and 0x0FFF
```

So:

```text
raw 0x05DC -> lower 12 bits 0x5DC -> 1500 RPM
```

In the supplied byte order, `05 DC` appears at frame bytes `07..08`, which map
to register `30053` requested speed. That also displays as `1500 RPM`.

Other raw examples:

```text
PWM raw:
1A 85 -> 0x1A85 -> 6789 raw

Sync voltage raw:
00 00 -> 0x0000 -> 0 raw

Actuator current raw:
01 F4 -> 0x01F4 -> 500 raw

Actuator position raw:
43 -> not enough bytes; registers always need two bytes
00 43 -> 0x0043 -> 67 raw
```

Status decoding:

- Register `30052` bits `12..15` are status bits:
  `Droop input status`, `Actuator overcurrent`, `Gain2 selection input`,
  `Overspeed occurred`.
- Register `30056` bits `0..7` are digital input bits:
  `Speed2 input`, `Speed3 input`, `Gain input`, `Fn key`, `Plus key`,
  `Minus key`, `Idle input`, `Pickup sensor input`.

## Stage 4 - Engineering Scaling

Raw register values and displayed engineering values are separate concepts:

```text
rawValue       = unsigned 16-bit integer decoded from packet bytes
displayValue   = rawValue / scale, only where a scale is defined
display text   = formatted displayValue + unit
```

Current frontend display scaling:

```text
Register   UI value                  Scale       Decimals   Example display
30051      PWM / Actuator Output     raw / 100   2          6789 -> 67.89%
30052      Engine Speed              raw&0x0FFF  0          1500 -> 1500 RPM
30053      Requested Speed           raw         0          1500 -> 1500 RPM
30054      Sync Voltage              raw / 1000  3          0 -> 0.000 V
30057      Actuator Current          raw / 100   2          500 -> 5.00A
30058      Actuator Position         raw         0          67 -> 67%
30062      Firmware Version          high.low    n/a        0xF100 -> 241.0
30063      Controller Type           mapped      n/a        0x006E -> 100
```

Scaling is only applied where required by the UI engineering metadata. The
frontend does not globally divide all telemetry values by 100.

PWM / Actuator Output example:

```text
Data bytes:      1A 85
Raw value:       (0x1A << 8) | 0x85 = 6789
Engineering:     6789 / 100.0 = 67.89
Display text:    67.89%
```

RPM example:

```text
Data bytes:      05 DC
Raw value:       (0x05 << 8) | 0xDC = 1500
Engineering:     no percentage scaling
Display text:    1500 RPM
```

Voltage example:

```text
Data bytes:      00 00
Raw value:       0
Engineering:     0 / 1000.0 = 0.000
Display text:    0.000 V
```

Current example:

```text
Data bytes:      01 F4
Raw value:       500
Engineering:     500 / 100.0 = 5.00
Display text:    5.00A
```

Actuator position example:

```text
Data bytes:      00 43
Raw value:       67
Engineering:     no divide-by-100 scaling
Display text:    67%
```

## Stage 5 - UI Display Formatting

`EngineeringFormats.value()` creates an `EngineeringValue`:

```kotlin
engineeringValue = raw / metadata.scale
displayValue = clamp(engineeringValue, min, max)
text = String.format(Locale.US, decimals) + unit
```

The Compose UI uses `EngineeringValue.text` for display cards and gauges:

```text
Engine Speed card        rpmEv.text
Requested Speed card     reqEv.text
PWM gauge/card           pwmEv.text
Sync Voltage card        syncEv.text
Actuator Current card    currEv.text
Actuator Position card   posEv.text
```

Firmware and controller type in the top header are display-only values sourced
directly from input registers `30062` and `30063`. The UI does not use register
`30055` for these labels.

Firmware display from `30062` separates the two register bytes with a decimal
point:

```text
Raw bytes:       F1 00
Raw register:    0xF100
High byte:       0xF1 = 241
Low byte:        0x00 = 0
Display text:    241.0
```

Controller type display from `30063` uses the controller display mapping:

```text
Raw bytes:       00 6E
Raw register:    0x006E
Raw decimal:     110
Display text:    100
```

Final display examples:

```text
1500 RPM
67.89%
0.000 V
5.00A
67%
```

## Why CRC Bytes Are Excluded

CRC bytes are not telemetry. They are generated from all previous frame bytes and
then appended to the end of the frame as:

```text
CRC Low, CRC High
```

For the example tail:

```text
... F1 00 6E 62
```

The correct split is:

```text
F1 00 = telemetry data bytes
6E 62 = CRC low/high bytes
```

Decoding `6E 62` as a register would corrupt the telemetry mapping by shifting
CRC metadata into the data model. The parser should first remove or exclude the
last two bytes, then split only the remaining data bytes into 16-bit registers.
