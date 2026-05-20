# SG-100 Desktop Dashboard

Python/PySide6 desktop monitor for the Huegli SG-100 Speed Governor.

The app is split into small modules:

- `hid_transport.py` owns USB HID reads/writes.
- `decoder.py` validates Modbus RTU packets and decodes the 13-register SG-100 block.
- `polling.py` polls the controller every 250 ms on a worker thread.
- `main_window.py` and `widgets.py` update the UI on the Qt thread.
- `models.py` and `register_map.py` hold structured decoded data and expandable register definitions.

Install:

```powershell
cd sg100_dashboard
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Run:

```powershell
python -m sg100_dashboard.main --vid 04D8 --pid F1BB
```

The default read request is function `04`, start address `30051`, quantity `13`.
The HID write is a strict 64-byte report: report id `00`, then the Modbus frame,
then zero padding, matching the known-working SG100 terminal.
If your existing HID layer is already working, adapt it to `HidTransport` by implementing
`open()`, `close()`, `is_open`, and `exchange(tx_packet)`.
