"""Application entry point."""

from __future__ import annotations

import argparse
import sys

from PySide6.QtWidgets import QApplication

from .hid_transport import HidApiTransport
from .main_window import MainWindow
from .polling import PollingController


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="SG-100 Speed Governor desktop dashboard")
    parser.add_argument("--vid", default="04D8", help="USB vendor id in hex, default 04D8")
    parser.add_argument("--pid", default="F0C7", help="USB product id in hex, default F0C7")
    parser.add_argument("--report-length", type=int, default=64)
    parser.add_argument("--timeout-ms", type=int, default=180)
    parser.add_argument("--prepend-report-id", action="store_true")
    parser.add_argument("--interval-ms", type=int, default=250)
    return parser.parse_args(argv)


def _hex_arg(value: str) -> int:
    return int(value.replace("0x", "").replace("0X", ""), 16)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    app = QApplication(sys.argv)

    transport = HidApiTransport(
        vendor_id=_hex_arg(args.vid),
        product_id=_hex_arg(args.pid),
        report_length=args.report_length,
        read_timeout_ms=args.timeout_ms,
        prepend_report_id=args.prepend_report_id,
    )
    controller = PollingController(transport, interval_ms=args.interval_ms)
    window = MainWindow(controller)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
