# Sound Receiver for Android

A native Android USB serial monitor for the receiver in
`../LilyGo-Cam-ESP32S3/examples/SoundEventReceiver`, matching the functionality of
`../LilyGo-Cam-ESP32S3/web_monitor`. No firmware changes or web server are required.

## Install and connect

The debug APK is built at `app/build/outputs/apk/debug/app-debug.apk`.
Copy it to your phone and open it to install (allow installation from that source
when Android asks), or install over ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Use Android 8.0 or later on a phone/tablet with **USB host / OTG support**.
2. Connect the ESP32 receiver with a USB **data** cable and an OTG adapter or
   compatible USB-C cable. The Android device must act as the USB host and power
   the receiver. Use a powered hub if the receiver needs more power.
3. Open **Sound Receiver**, tap **Connect receiver**, choose the device and serial
   port, and allow Android's USB access request. A multi-port device may require
   trying another listed port. The app uses **115200 baud, 8N1**, no flow control.
4. The receiver should report its MAC address and channel within five seconds.
   Power the T-Camera sender, wait five seconds for background learning, and
   make a loud sound. Cards update on sound events, not continuously.

The serial driver library supports CDC/ACM (including native ESP32 USB), CP210x,
CH34x, FTDI, and supported Prolific adapters. Actual compatibility still depends
on the USB interfaces exposed by your receiver and the phone's USB host support.
No root, microphone permission, browser USB API, or network connection is needed.

## Controls and session behavior

- **Connect receiver / Disconnect:** select and release a serial port. Successful
  connections reset event statistics and retain earlier log messages. DTR is
  asserted for USB CDC; opening a connection may reset some boards.
- **Event cards:** event count, latest level in dBFS, noise floor, rise above the
  floor, sequence, sender MAC, and confirmation duration. Receiver MAC/channel
  come from heartbeat lines. Sender uptime remains in the raw log.
- **Events only:** show recognized sound events; all retained lines are still
  included in exports. Unknown messages and boot output stay visible as text;
  events are teal and likely errors are orange.
- **Auto-scroll:** follow incoming lines; disable to inspect earlier messages.
- **Beep on events:** enabled by default; plays a 210 ms 880 Hz alert through
  Android's media audio output. **Test beep** also works while event beeps are
  muted. Adjust media volume and check Bluetooth/headphone routing if silent.
  Events arriving during a tone share that tone.
- **Download log:** Android's document picker saves a snapshot of all retained
  messages as UTF-8 text, with UTC receipt timestamps, to a location you choose.
  Monitoring can continue while that picker is open. Choosing a cloud document
  provider may upload the file through that provider.
- **Clear:** remove the log and reset all event statistics.

The screen stays awake while connected. Rotation/resizing preserves the session.
Leaving the app (Home, another app, screen off) disconnects USB; return and tap
Connect receiver to resume. This is a foreground monitor, without a background
recording service or notification. USB removal and read errors release the port
and display a reconnect message. There is no automatic reconnect.

Only the latest **1,000 lines** are retained. The total line/event counts can
exceed that. Each serial line is limited to 8,192 characters; overlong lines are
marked as truncated and their remainder is discarded through the next newline.
UTF-8 characters and CRLF lines can span USB reads. A final partial line is
retained when disconnecting. Logs live in memory only and are lost when Android
destroys the activity/process or you close the app. Export to keep a copy.
The app requests no Internet permission and has no analytics or automatic storage.

## Build and test

Open this directory in Android Studio, or use **JDK 17**, Android SDK Platform 35,
and the included Gradle 8.13 wrapper. Set `ANDROID_HOME` to your SDK directory
(or set `sdk.dir` in an untracked `local.properties`). First builds need internet
access to resolve Google Maven, Maven Central, JitPack, and Gradle dependencies.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an emulator or device connected:
./gradlew :app:connectedDebugAndroidTest
```

Unit tests cover the firmware protocol, malformed/overflowing values, UTF-8 split
at every byte boundary, CRLF, partial lines, truncation, retention, filtering,
statistics resets, and export formatting. Android UI tests inject wire-message
fixtures into the normal receive callback to verify metrics, filters, clearing,
rotation, document export, and the no-device message. The export test intercepts
the document picker with a test destination; it does not operate a cloud provider.
Run these UI tests without USB serial devices attached. If ADB lists the same
phone over both USB and Wi-Fi, set `ANDROID_SERIAL` to one transport when running
the Gradle command.

Validation completed: debug APK build, 15 JVM unit tests, Android lint (no
errors), and all five UI tests on a Pixel 6a running Android 17. The app also
launched in an Android 15 emulator. UI tests use sample receiver messages;
physical USB serial transport has not yet been validated.

**Hardware validation is still required:** an emulator cannot verify a physical
OTG cable, USB permission flow, DTR behavior, port choice, hot unplug during
reads, or audible output on your Android device. On the phone, check those paths
and save/reopen a log before relying on the monitor. The debug APK is signed
with the development machine's debug key; publishing requires release signing.

## Implementation and references

Native Java/Android UI, targeting API 35 with minimum API 26. `MainActivity`
owns the foreground session and UI; `UsbConnection` owns serial I/O and resource
cleanup on a worker thread. `ReceiverProtocol`, `SerialLines`, and `MonitorLog`
are Android-independent and unit-tested. UI updates are batched every 50 ms.
There are no changes to the original web monitor or firmware.

- [Android USB host and permission documentation](https://developer.android.com/develop/connectivity/usb/host)
- [usb-serial-for-android 3.11.0](https://github.com/mik3y/usb-serial-for-android), MIT licensed
- [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test)

See `THIRD_PARTY_NOTICES.md` for dependency license information.
