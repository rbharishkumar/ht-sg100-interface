# SG-100 Android USB Host

This is a native Android USB Host app for detecting and communicating with a Huegli Tech SG-100 over USB.

What it does:

- Detects USB devices connected to the Android phone/tablet through OTG.
- Displays the device VID and PID in hexadecimal and decimal.
- Displays HID/interface class, subclass, protocol, endpoint address, endpoint type, and packet size.
- Requests Android USB permission.
- Opens the best HID/vendor/CDC interface it can find.
- Reads incoming interrupt/bulk endpoint data and displays it as hex and ASCII.
- Sends raw hex bytes through an OUT interrupt/bulk endpoint when one exists.

Important notes:

- Huegli publicly documents SG-100 PC configuration software, but the actual USB command protocol is not published in the product page/manual material I found. This app gives you the VID/PID and raw USB communication path; to perform real SG-100 configuration or telemetry commands, you need Huegli's protocol command bytes or a captured trace from the official Windows software.
- After the first scan, if you want the app to select only the SG-100, edit `SG100_VENDOR_ID` and `SG100_PRODUCT_ID` in `MainActivity.kt` with the displayed values.
- Your Android device must support USB OTG/USB Host mode.
- Some phones require the SG-100 to be externally powered or connected through a powered USB hub.

Build:

1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle.
3. Build and install on your Android device.
4. Connect SG-100 using an OTG adapter.
5. Open the app and tap `Scan USB`, then `Connect`.

If SG-100 appears as HID, look for `Interface class=3 HID`.
The VID/PID lines are the values you asked for, for example:

```text
VID: 0x1234 / decimal 4660
PID: 0x5678 / decimal 22136
```
