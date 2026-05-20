"""Main PySide6 industrial monitoring window."""

from __future__ import annotations

from PySide6.QtCore import Qt, Slot
from PySide6.QtWidgets import (
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QPlainTextEdit,
    QScrollArea,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from .models import ParsedPacket
from .polling import PollingController
from .widgets import LedIndicator, MetricCard, RpmGauge


class MainWindow(QMainWindow):
    def __init__(self, controller: PollingController) -> None:
        super().__init__()
        self.controller = controller
        self.setWindowTitle("SG-100 Speed Governor Monitor")
        self.resize(1280, 820)
        self.metric_cards: dict[str, MetricCard] = {}
        self.status_leds: dict[str, LedIndicator] = {}
        self.input_leds: dict[str, LedIndicator] = {}

        self._build_ui()
        self._wire_controller()

    def _build_ui(self) -> None:
        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setContentsMargins(18, 18, 18, 18)
        layout.setSpacing(14)

        header = QHBoxLayout()
        title = QLabel("SG-100 Speed Governor Monitor")
        title.setObjectName("title")
        self.connection = QLabel("Disconnected")
        self.connection.setObjectName("connectionBadge")
        self.start_button = QPushButton("Start")
        self.stop_button = QPushButton("Stop")
        self.stop_button.setEnabled(False)
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(self.connection)
        header.addWidget(self.start_button)
        header.addWidget(self.stop_button)
        layout.addLayout(header)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(self._build_dashboard_panel())
        splitter.addWidget(self._build_debug_panel())
        splitter.setStretchFactor(0, 3)
        splitter.setStretchFactor(1, 2)
        layout.addWidget(splitter, 1)

        self.setCentralWidget(root)
        self.setStyleSheet(STYLE)

    def _build_dashboard_panel(self) -> QWidget:
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        body = QWidget()
        body_layout = QVBoxLayout(body)
        body_layout.setSpacing(14)

        gauge_box = QGroupBox("Engine Speed")
        gauge_layout = QVBoxLayout(gauge_box)
        self.rpm_gauge = RpmGauge(maximum=4000)
        self.engine_speed_text = QLabel("N/A")
        self.engine_speed_text.setObjectName("largeReadout")
        gauge_layout.addWidget(self.rpm_gauge)
        gauge_layout.addWidget(self.engine_speed_text, alignment=Qt.AlignmentFlag.AlignCenter)
        body_layout.addWidget(gauge_box)

        metric_grid = QGridLayout()
        metrics = [
            ("requested_speed", "Requested Speed", "RPM"),
            ("pwm", "PWM / Actuator Cmd", "%"),
            ("sync_voltage", "Sync Voltage", "V"),
            ("actuator_current", "Actuator Current", ""),
            ("actuator_position", "Actuator Position", ""),
            ("firmware", "Firmware Version", ""),
            ("controller_type", "Controller Type", ""),
        ]
        for index, (key, label, unit) in enumerate(metrics):
            card = MetricCard(label, unit)
            self.metric_cards[key] = card
            metric_grid.addWidget(card, index // 3, index % 3)
        body_layout.addLayout(metric_grid)

        status_box = QGroupBox("Status Indicators")
        status_grid = QGridLayout(status_box)
        for index, label in enumerate(
            ["Overspeed occurred", "Gain2 selection input", "Actuator overcurrent", "Droop input status"]
        ):
            led = LedIndicator(label)
            self.status_leds[label] = led
            status_grid.addWidget(led, index // 2, index % 2)
        body_layout.addWidget(status_box)

        input_box = QGroupBox("Input Status")
        input_grid = QGridLayout(input_box)
        input_labels = [
            "Speed2 input",
            "Speed3 input",
            "Gain input",
            "Fn key",
            "Plus key",
            "Minus key",
            "Idle input",
            "Pickup sensor input",
        ]
        for index, label in enumerate(input_labels):
            led = LedIndicator(label)
            self.input_leds[label] = led
            input_grid.addWidget(led, index // 2, index % 2)
        body_layout.addWidget(input_box)
        body_layout.addStretch(1)

        scroll.setWidget(body)
        return scroll

    def _build_debug_panel(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.setSpacing(12)

        crc_box = QGroupBox("Packet Debug")
        crc_layout = QGridLayout(crc_box)
        self.tx_label = QLabel("N/A")
        self.rx_label = QLabel("N/A")
        self.crc_label = QLabel("N/A")
        self.raw_label = QLabel("N/A")
        for row, (name, widget) in enumerate(
            [("TX", self.tx_label), ("RX", self.rx_label), ("CRC", self.crc_label), ("Raw Hex", self.raw_label)]
        ):
            key = QLabel(name)
            key.setObjectName("debugKey")
            widget.setWordWrap(True)
            widget.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            crc_layout.addWidget(key, row, 0)
            crc_layout.addWidget(widget, row, 1)
        layout.addWidget(crc_box)

        self.table = QTableWidget(13, 5)
        self.table.setHorizontalHeaderLabels(["Register", "Name", "Hex", "Decimal", "Unit"])
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setStretchLastSection(True)
        layout.addWidget(self.table, 1)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(300)
        layout.addWidget(self.log, 1)
        return panel

    def _wire_controller(self) -> None:
        self.start_button.clicked.connect(self._start_polling)
        self.stop_button.clicked.connect(self._stop_polling)
        self.controller.packet_received.connect(self.update_from_packet)
        self.controller.raw_exchange.connect(self.update_raw_exchange)
        self.controller.error.connect(self.show_error)
        self.controller.state_changed.connect(self.update_connection_state)

    @Slot()
    def _start_polling(self) -> None:
        self.start_button.setEnabled(False)
        self.stop_button.setEnabled(True)
        self.controller.start()

    @Slot()
    def _stop_polling(self) -> None:
        self.stop_button.setEnabled(False)
        self.start_button.setEnabled(True)
        self.controller.stop()

    @Slot(object)
    def update_from_packet(self, packet: ParsedPacket) -> None:
        engine_speed = packet.engine_speed_rpm
        requested = packet.requested_speed_rpm
        self.rpm_gauge.set_rpm(engine_speed)
        self.engine_speed_text.setText("N/A" if engine_speed is None else f"{engine_speed} RPM")
        self.metric_cards["requested_speed"].set_value(requested)
        self.metric_cards["pwm"].set_value(packet.pwm_percent)
        self.metric_cards["sync_voltage"].set_value(
            None if packet.sync_voltage is None else f"{packet.sync_voltage:.3f}"
        )
        self.metric_cards["actuator_current"].set_value(packet.actuator_current)
        self.metric_cards["actuator_position"].set_value(packet.actuator_position)
        self.metric_cards["firmware"].set_value(_hex_byte(packet.firmware_version))
        self.metric_cards["controller_type"].set_value(_hex_byte(packet.controller_type))

        for label, active in packet.status.flags.items():
            if label in self.status_leds:
                self.status_leds[label].set_active(active)
        for label, active in packet.input_status.flags.items():
            if label in self.input_leds:
                self.input_leds[label].set_active(active)

        for row, register in enumerate(sorted(packet.registers.values(), key=lambda item: item.address)):
            values = [
                str(register.address),
                register.label,
                register.hex_value,
                str(register.raw),
                register.unit,
            ]
            for column, value in enumerate(values):
                self.table.setItem(row, column, QTableWidgetItem(value))

        self.crc_label.setText(
            f"{'OK' if packet.crc_ok else 'FAIL'}  received=0x{packet.crc_received:04X} "
            f"calculated=0x{packet.crc_calculated:04X}"
        )
        self.raw_label.setText(packet.rx_hex)
        self.log.appendPlainText(
            f"{packet.received_at:%H:%M:%S.%f} RX OK engine={engine_speed} rpm requested={requested} rpm"
        )

    @Slot(str, str)
    def update_raw_exchange(self, tx_hex: str, rx_hex: str) -> None:
        self.tx_label.setText(tx_hex)
        self.rx_label.setText(rx_hex)

    @Slot(str)
    def show_error(self, message: str) -> None:
        self.log.appendPlainText(f"ERROR: {message}")

    @Slot(str)
    def update_connection_state(self, state: str) -> None:
        self.connection.setText(state)
        connected = state.lower() == "connected"
        self.connection.setProperty("connected", connected)
        self.connection.style().unpolish(self.connection)
        self.connection.style().polish(self.connection)
        if not connected:
            self.start_button.setEnabled(True)
            self.stop_button.setEnabled(False)

    def closeEvent(self, event) -> None:
        self.controller.stop()
        super().closeEvent(event)


def _hex_byte(value: int | None) -> str | None:
    if value is None:
        return None
    return f"0x{value & 0xFFFF:04X}"


STYLE = """
QWidget {
    background: #f2f5f4;
    color: #142421;
    font-family: Segoe UI, Arial;
    font-size: 13px;
}
QLabel#title {
    font-size: 24px;
    font-weight: 700;
}
QLabel#connectionBadge {
    border: 1px solid #d5978c;
    border-radius: 6px;
    padding: 7px 12px;
    background: #ffe7e1;
    color: #5a2621;
    font-weight: 700;
}
QLabel#connectionBadge[connected="true"] {
    border-color: #69ad87;
    background: #e1f5e8;
    color: #073b2b;
}
QPushButton {
    border: 1px solid #7fa29d;
    border-radius: 6px;
    padding: 8px 14px;
    background: #ffffff;
    font-weight: 600;
}
QPushButton:hover {
    background: #edf5f2;
}
QPushButton:disabled {
    color: #87928f;
    background: #e4e9e7;
}
QGroupBox {
    border: 1px solid #cad7d4;
    border-radius: 8px;
    margin-top: 12px;
    padding: 14px;
    background: #ffffff;
    font-weight: 700;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 6px;
}
QFrame#metricCard {
    border: 1px solid #cad7d4;
    border-radius: 8px;
    background: #ffffff;
}
QLabel#metricLabel {
    color: #5a6d69;
    font-size: 12px;
}
QLabel#metricValue {
    font-size: 23px;
    font-weight: 700;
}
QLabel#largeReadout {
    font-size: 24px;
    font-weight: 700;
}
QLabel#debugKey {
    color: #5a6d69;
    font-weight: 700;
}
QTableWidget, QPlainTextEdit {
    border: 1px solid #cad7d4;
    border-radius: 6px;
    background: #ffffff;
    selection-background-color: #cfe7df;
    font-family: Consolas, monospace;
}
"""
