# Sound Receiver for Android — BLE SoundMeter

Native Android client for the **SoundMeter BLE protocol v1** in
[BLE_PROTOCOL.md](BLE_PROTOCOL.md). Version 2 replaces the original USB serial
transport. Connect directly to a LilyGO T-Camera ESP32-S3 running
`../LilyGo-Cam-ESP32S3/examples/SoundMeter` BLE firmware. The old ESP-NOW
`SoundEventReceiver` receiver and USB serial firmware are incompatible.

## Install and connect

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install by copying
it to the phone and opening it, or use ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# With wireless ADB:
adb -s 192.168.1.166:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Use an Android 8.0+ phone/tablet with Bluetooth LE and power the SoundMeter.
2. Tap **Connect SoundMeter**. Allow **Nearby devices** on Android 12+, or
   **Location** on Android 8–11 (required by those Android versions for scanning).
   Android 8–11 also needs Location services enabled. The app does not derive
   location from scan results. If Bluetooth is off, accept Android's enable prompt.
3. Select your board in the scan picker. It shows the advertised name, Android
   device address, and signal strength. The default name is **RohBot SoundMeter**;
   names are not unique. Scanning filters by the protocol's service UUID and
   stops after 12 seconds. Discovered boards remain selectable afterward.
4. The app discovers services, enables notifications, then reads the latest
   event snapshot. **Connected · subscribed** indicates the BLE subscription is
   established; a quiet board sends no heartbeat. Wait five seconds for initial
   noise learning, then make a loud sound to trigger a confirmed detection.

No pairing/bonding, USB cable, browser, microphone permission, or internet
connection is required. Only one client can connect to the board at a time;
other clients cannot discover it while it is connected.

## Events, snapshots, and delivery

The app decodes exactly 20 bytes in little-endian order at the default ATT MTU.
It validates version, type, length, type/sequence combinations, and signed level
ranges. Unsupported or malformed packets produce a visible warning and log
entry without stopping the notification handler. Boot ID, unsigned sequence,
wrapping board uptime, duration, levels, source, and raw hex are retained in the
human-readable log. Local receipt timestamps are separate from board uptime.

- **New events received** counts unique newly observed detections, not the
  board's sequence counter. A first snapshot from an unseen device/boot is marked
  **Historical**: it updates the latest cards but does not increment this count
  or beep. A type-0 startup sentinel is logged as **No event yet** and never
  populates measurement cards.
- Reads and notifications share deduplication by Android peripheral address,
  boot ID, and sequence. Duplicate/older snapshots are ignored. Sequence ordering
  uses the nonzero uint32 ring, so `4294967295 → 1` works correctly. A changed
  boot ID starts a new baseline. Recovered newer reads from a known session count
  as new detections; their log source is `read`.
- Sequence gaps report estimated missed detections separately from received
  events. First observations and new boot IDs cannot establish a missed count.
  The log records each detected gap and the status message shows the cumulative
  estimate. Notifications have no delivery guarantee. The board retains only
  the latest event, so missed intermediate detections cannot be downloaded.
- Deduplication baselines survive disconnect/reconnect and **Clear**, in memory
  for the latest 64 device/boot pairs. Process/activity destruction loses them.
  BLE addresses may change and random boot IDs may collide; this is best effort.
- After connection loss/setup failure, reconnect attempts wait **1, 2, 4, 8, then
  10 seconds**, capped at 10 seconds thereafter. Each attempt has a 20-second
  setup timeout and repeats discovery, subscription, and snapshot reading.
  **Disconnect**, leaving the app, or Bluetooth being turned off cancels retries.
  No events during a quiet period does not cause a reconnect.

Cards display triggering RMS dBFS, learned noise floor, rise, sender/device ID,
sequence, boot ID, receipt time, and confirmation duration. They are event
measurements, not audio, continuous telemetry, dBA, or calibrated SPL. A duration
shown as **65535+ ms** is saturated and means at least 65535 ms.

## Controls, privacy, and lifecycle

- **Disconnect** stops scanning/connecting/reconnecting and releases the BLE link.
- **Events only** filters the displayed log; exports include all retained entries,
  including historical events, startup sentinels, and malformed-packet warnings.
- **Auto-scroll** follows incoming entries; disable it to inspect earlier ones.
- **Beep on events** plays the same 880 Hz, 210 ms media-volume alert as the
  original monitor. Overlapping alerts share a tone. **Test beep** also works
  when event beeps are muted. Historical snapshots never beep.
- **Download log** saves a UTF-8 snapshot with UTC receipt timestamps using
  Android's document picker. A cloud document provider may upload the chosen
  file through that provider. Monitoring continues while this picker is open.
- **Clear** resets the displayed log, metrics, received count, and missed count;
  it preserves deduplication baselines to avoid replaying alerts.

The latest **1,000 log entries** are kept in memory. No automatic disk storage,
analytics, or internet permission. Rotation/resizing preserves the session and
monitoring keeps the screen awake while subscribed. Leaving the app normally
releases BLE and cancels retries; return and reconnect manually. This is a
foreground monitor without a background service. Runtime permission/Bluetooth
enable dialogs and the document picker are exceptions within the connection
and export flows. Activity/process destruction loses logs and baselines.

The firmware does not require BLE authentication or encryption. Nearby clients
can read detections or occupy the single available connection; see the protocol
contract for the firmware's transport limits.

## Build and test

Open this directory in Android Studio, or use **JDK 17**, Android SDK Platform 35,
and the included Gradle 8.13 wrapper. Set `ANDROID_HOME`, or `sdk.dir` in an
untracked `local.properties`. Initial dependency resolution needs internet access
(Google Maven, Maven Central, and Gradle); JitPack/USB drivers are no longer used.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With an unlocked phone connected (choose one ADB transport):
ANDROID_SERIAL=192.168.1.166:5555 ./gradlew :app:connectedDebugAndroidTest
```

Unit tests cover the firmware's reference vector, startup sentinel, malformed
payloads, unsigned limits, signed hundredths, saturated duration, notification/read
races, historical reads, older/duplicate suppression, gaps, sequence and uptime
wrap, reboot/device isolation, retention/export, Clear semantics, GATT setup
ordering, and capped backoff. Android UI tests inject binary read/notification
values through the production receive callback to verify metrics, historical
snapshots, unsupported-protocol recovery, quiet/reconnect states, filtering,
clearing, rotation, and document export. These fixture tests do not prove BLE
radio delivery or the platform's permission and CCCD behavior.

Validation on the connected Pixel 6a (Android 17): 28 unit tests, six Android UI
tests, APK build, and Android lint passed (no lint errors). A real SoundMeter was
successfully discovered, subscribed, and read as a valid type-0 startup snapshot.
Live events then populated the count and level/floor/rise/duration cards (19 new
detections observed). Automatic reconnection was observed after intermittent GATT
status-8 link timeouts. Audible output and the remaining hardware acceptance
cases still need checking.

For hardware acceptance, verify discovery, Nearby devices denial/retry, Bluetooth
off/on, subscription at MTU 23, an actual sound alert, quiet periods, out-of-range
loss/reconnect, board reboot, no duplicate beep from snapshot reads, cancellation
of retries, and saving/reopening the exported log. The debug APK uses this
machine's debug signing key; publishing requires release signing.

## Implementation

- `BleConnection`: service-filtered scan, selection, GATT lifecycle, notification
  subscription, initial read, stale-callback protection, setup timeout, and retry.
- `GattHandshake`: setup ordering and backoff policy.
- `ReceiverProtocol`: pure Java binary decoder and protocol UUIDs.
- `EventDeduplicator`: bounded per-device/session nonzero uint32 ordering.
- `MonitorLog`: event/snapshot classification, metrics, bounded log, and export.
- `MainActivity`: Android permissions, Bluetooth enable prompt, device picker,
  foreground lifecycle, and UI. Rendering batches updates every 50 ms.

References: [BLE_PROTOCOL.md](BLE_PROTOCOL.md),
[Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions),
[Android BLE data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licenses.
