"""Qt worker thread that polls SG-100 without blocking the GUI."""

from __future__ import annotations

import time

from PySide6.QtCore import QObject, QThread, Signal, Slot

from .decoder import PacketDecodeError, build_read_registers_request, decode_sg100_packet, to_hex
from .hid_transport import HidTransport, HidTransportError
from .models import ParsedPacket


class PollingWorker(QObject):
    packet_received = Signal(object)
    raw_exchange = Signal(str, str)
    error = Signal(str)
    state_changed = Signal(str)
    finished = Signal()

    def __init__(self, transport: HidTransport, interval_ms: int = 250) -> None:
        super().__init__()
        self.transport = transport
        self.interval_ms = interval_ms
        self._running = False
        self._tx_packet = build_read_registers_request()

    @Slot()
    def run(self) -> None:
        """Open the HID link and poll until `stop()` is called.

        This object lives on a QThread. It emits decoded packets to the GUI,
        and Qt delivers those signals safely back to the main thread.
        """
        self._running = True
        try:
            if not self.transport.is_open:
                self.transport.open()
            self.state_changed.emit("Connected")

            while self._running:
                started = time.monotonic()
                try:
                    rx = self.transport.exchange(self._tx_packet)
                    decoded: ParsedPacket = decode_sg100_packet(rx, tx_packet=self._tx_packet)
                    self.raw_exchange.emit(to_hex(self._tx_packet), to_hex(rx))
                    self.packet_received.emit(decoded)
                except (HidTransportError, PacketDecodeError, OSError) as exc:
                    self.error.emit(str(exc))

                elapsed_ms = int((time.monotonic() - started) * 1000)
                sleep_ms = max(0, self.interval_ms - elapsed_ms)
                time.sleep(sleep_ms / 1000.0)
        finally:
            self.transport.close()
            self.state_changed.emit("Disconnected")
            self.finished.emit()

    @Slot()
    def stop(self) -> None:
        self._running = False


class PollingController(QObject):
    packet_received = Signal(object)
    raw_exchange = Signal(str, str)
    error = Signal(str)
    state_changed = Signal(str)

    def __init__(self, transport: HidTransport, interval_ms: int = 250) -> None:
        super().__init__()
        self._thread = QThread()
        self._worker = PollingWorker(transport, interval_ms)
        self._worker.moveToThread(self._thread)

        self._thread.started.connect(self._worker.run)
        self._worker.finished.connect(self._thread.quit)
        self._worker.finished.connect(self._worker.deleteLater)
        self._thread.finished.connect(self._thread.deleteLater)

        self._worker.packet_received.connect(self.packet_received)
        self._worker.raw_exchange.connect(self.raw_exchange)
        self._worker.error.connect(self.error)
        self._worker.state_changed.connect(self.state_changed)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._worker.stop()
        self._thread.quit()
        self._thread.wait(1500)
