"""Reusable dashboard widgets."""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPen
from PySide6.QtWidgets import QFrame, QGridLayout, QLabel, QSizePolicy, QVBoxLayout, QWidget


class MetricCard(QFrame):
    def __init__(self, label: str, unit: str = "") -> None:
        super().__init__()
        self.unit = unit
        self.setObjectName("metricCard")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(6)

        self.label = QLabel(label)
        self.label.setObjectName("metricLabel")
        self.value = QLabel("N/A")
        self.value.setObjectName("metricValue")
        self.value.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        layout.addWidget(self.label)
        layout.addWidget(self.value)

    def set_value(self, value: object | None, unit: str | None = None) -> None:
        if value is None:
            self.value.setText("N/A")
            return
        suffix = unit if unit is not None else self.unit
        text = f"{value} {suffix}".strip()
        self.value.setText(text)


class LedIndicator(QFrame):
    def __init__(self, label: str) -> None:
        super().__init__()
        self._active = False
        self.setObjectName("ledRow")
        layout = QGridLayout(self)
        layout.setContentsMargins(10, 8, 10, 8)
        layout.setHorizontalSpacing(10)

        self.dot = QLabel()
        self.dot.setFixedSize(14, 14)
        self.text = QLabel(label)
        self.text.setObjectName("ledLabel")
        layout.addWidget(self.dot, 0, 0)
        layout.addWidget(self.text, 0, 1)
        layout.setColumnStretch(1, 1)
        self.set_active(False)

    def set_active(self, active: bool) -> None:
        self._active = active
        color = "#e24646" if active else "#61716d"
        bg = "#ffe5e5" if active else "#eef3f1"
        self.dot.setStyleSheet(f"border-radius: 7px; background: {color};")
        self.setStyleSheet(f"QFrame#ledRow {{ background: {bg}; border-radius: 6px; }}")


class RpmGauge(QWidget):
    def __init__(self, maximum: int = 3000) -> None:
        super().__init__()
        self.maximum = maximum
        self._rpm = 0
        self.setMinimumHeight(230)

    def set_rpm(self, rpm: int | None) -> None:
        self._rpm = max(0, min(self.maximum, int(rpm or 0)))
        self.update()

    def paintEvent(self, event) -> None:
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        rect = self.rect().adjusted(28, 22, -28, 22)
        size = min(rect.width(), rect.height() * 1.8)
        x = rect.center().x() - size / 2
        y = rect.top()
        arc_rect = self.rect().__class__(int(x), int(y), int(size), int(size))

        base_pen = QPen(QColor("#d7e2df"), 16, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap)
        active_pen = QPen(QColor("#1e9c78"), 16, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap)
        painter.setPen(base_pen)
        painter.drawArc(arc_rect, 180 * 16, -180 * 16)

        sweep = int(-180 * 16 * (self._rpm / self.maximum))
        painter.setPen(active_pen)
        painter.drawArc(arc_rect, 180 * 16, sweep)

        painter.setPen(QPen(QColor("#16302d"), 4, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        center_x = arc_rect.center().x()
        center_y = arc_rect.center().y()
        angle = 180 - 180 * (self._rpm / self.maximum)
        import math

        radius = arc_rect.width() / 2 - 28
        needle_x = center_x + math.cos(math.radians(angle)) * radius
        needle_y = center_y - math.sin(math.radians(angle)) * radius
        painter.drawLine(center_x, center_y, int(needle_x), int(needle_y))

        painter.setBrush(QColor("#16302d"))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawEllipse(center_x - 7, center_y - 7, 14, 14)

        painter.setPen(QColor("#415854"))
        font = QFont()
        font.setBold(True)
        font.setPointSize(11)
        painter.setFont(font)
        painter.drawText(self.rect().adjusted(0, 0, 0, -14), Qt.AlignmentFlag.AlignBottom | Qt.AlignmentFlag.AlignHCenter, f"{self._rpm} RPM")
