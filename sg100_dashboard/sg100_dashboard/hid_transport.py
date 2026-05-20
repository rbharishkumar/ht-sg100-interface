"""USB HID transport boundary for SG-100 communication.

The parser and UI do not know about hidapi. They only call `exchange()`.
If your existing HID communication layer is already proven, wrap it in this
same interface and leave the rest of the dashboard unchanged.
"""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from dataclasses import dataclass


class HidTransportError(RuntimeError):
    pass


class HidTransport(ABC):
    @abstractmethod
    def open(self) -> None:
        pass

    @abstractmethod
    def close(self) -> None:
        pass

    @property
    @abstractmethod
    def is_open(self) -> bool:
        pass

    @abstractmethod
    def exchange(self, tx_packet: bytes) -> bytes:
        """Write one packet and return the received response bytes."""
        pass


@dataclass
class HidApiTransport(HidTransport):
    vendor_id: int
    product_id: int
    path: bytes | None = None
    report_length: int = 64
    read_timeout_ms: int = 3000
    prepend_report_id: bool = True

    def __post_init__(self) -> None:
        self._device = None

    def open(self) -> None:
        try:
            import hid
        except ImportError as exc:
            raise HidTransportError("Install hidapi with: pip install hidapi") from exc

        device = hid.device()
        try:
            if self.path:
                device.open_path(self.path)
            else:
                device.open(self.vendor_id, self.product_id)
            device.set_nonblocking(True)
        except OSError as exc:
            raise HidTransportError(f"Could not open HID device: {exc}") from exc
        self._device = device

    def close(self) -> None:
        if self._device is not None:
            self._device.close()
            self._device = None

    @property
    def is_open(self) -> bool:
        return self._device is not None

    def exchange(self, tx_packet: bytes) -> bytes:
        if self._device is None:
            raise HidTransportError("HID device is not open")

        self._flush_pending_reads()
        report = self._build_report(tx_packet)
        written = self._device.write(report)
        if written != len(report):
            raise HidTransportError(f"HID write failed: wrote {written} of {len(report)} bytes")

        deadline = time.monotonic() + (self.read_timeout_ms / 1000.0)
        rx = b""
        while time.monotonic() < deadline:
            rx = bytes(self._device.read(self.report_length))
            if rx:
                break
            time.sleep(0.01)
        if not rx:
            raise HidTransportError("No HID response from SG-100")
        return self._trim_report_padding(rx)

    def _flush_pending_reads(self) -> int:
        if self._device is None:
            return 0
        flushed = 0
        while True:
            old = self._device.read(self.report_length)
            if not old:
                break
            flushed += 1
        return flushed

    def _build_report(self, tx_packet: bytes) -> list[int]:
        if self.prepend_report_id:
            raw = bytes([0x00]) + tx_packet
        else:
            raw = tx_packet
        if len(raw) > self.report_length:
            raise HidTransportError(
                f"TX packet length {len(raw)} exceeds HID report length {self.report_length}"
            )
        return list(raw + bytes(self.report_length - len(raw)))

    @staticmethod
    def _trim_report_padding(rx: bytes) -> bytes:
        """Extract the Modbus frame from a padded HID report.

        SG-100 responses are Modbus RTU frames embedded in HID reports. This
        keeps meaningful zero bytes inside the payload while removing only the
        trailing HID padding after the CRC-verified frame length.
        """
        if len(rx) >= 3:
            byte_count = rx[2]
            expected_length = 3 + byte_count + 2
            if 5 <= expected_length <= len(rx):
                return rx[:expected_length]
        return rx.rstrip(b"\x00")
