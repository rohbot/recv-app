# Sound Receiver for Android — BLE SoundMeter

Native Android client with background monitoring and vibrating event alerts for
the **SoundMeter BLE protocol v1** in
[BLE_PROTOCOL.md](BLE_PROTOCOL.md). Version 2 replaced the original USB serial
transport; version 3 added a foreground monitoring service and Android notifications.
Version 4 replaced the short beep with an alarm that repeats until acknowledged;
version 5 adds persistent disarming and an explicit Arm alarm button. Connect directly to a LilyGO T-Camera ESP32-S3 running
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
   On Android 13+, allow **Notifications** for event alerts and vibration. Denying
   notifications still permits monitoring, but alerts will not appear or vibrate.
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
  or start the alarm. A type-0 startup sentinel is logged as **No event yet** and never
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
  **Disconnect** (in the app or ongoing notification), or Bluetooth being turned
  off cancels retries. Leaving the app does not stop monitoring or retries.
  No events during a quiet period does not cause a reconnect.

Cards display triggering RMS dBFS, learned noise floor, rise, sender/device ID,
sequence, boot ID, receipt time, and confirmation duration. They are event
measurements, not audio, continuous telemetry, dBA, or calibrated SPL. A duration
shown as **65535+ ms** is saturated and means at least 65535 ms.

## Controls, privacy, and lifecycle

- **Disconnect** stops scanning/connecting/reconnecting and releases the BLE link.
  It does **not** silence an active alarm.
- **Events only** filters the displayed log; exports include all retained entries,
  including historical events, startup sentinels, and malformed-packet warnings.
- **Auto-scroll** follows incoming entries; disable it to inspect earlier ones.
- **Silence alarm** is prominently displayed below the connection controls. Each
  unique new detection starts a repeating alarm: two 180 ms 880 Hz beeps per
  second, with a pause between cycles. It continues in the background and with
  the screen off until this button is pressed. Additional detections do not
  stack/restart it. Pressing **Silence alarm** stops audio and **disarms** future
  alarms while keeping BLE monitoring active. The button becomes **Arm alarm**.
  You can also press Silence alarm while armed/quiet to disarm preemptively.
- **Arm alarm** re-enables alarms for future new detections and changes the button
  back to **Silence alarm**. It does not replay any detection received while
  disarmed. The armed/disarmed choice is saved and survives reconnecting,
  reopening the app, and service/process recreation. Events received while
  disarmed still update the log/cards and can show the existing notification
  and vibration, but cannot start alarm audio.
- **Test alarm** starts the same repeating sound without BLE. Stop it with
  **Silence alarm**. Test alarm is disabled while disarmed. There is no automatic
  timeout; explicit arming is required after silencing.
  The phone's **Alarm volume** controls loudness, rather than media volume.
  Historical/duplicate/older snapshots, sentinels, and malformed values do not
  trigger the alarm. Clearing the log and BLE disconnects do not silence it.
- **Download log** saves a UTF-8 snapshot with UTC receipt timestamps using
  Android's document picker. A cloud document provider may upload the chosen
  file through that provider. Monitoring continues while this picker is open.
- **Clear** resets the displayed log, metrics, received count, and missed count;
  it preserves deduplication baselines to avoid replaying alerts.
- **Notification settings** opens Android's controls for this app. The **Sound
  event alerts** channel enables notifications and a short two-pulse vibration
  by default. Historical/duplicate/older packets, sentinels, and malformed data
  never trigger an alert. Each new event updates the latest alert notification;
  it shows levels, confirmation duration, device, sequence, and receipt time.
  Alarm audio is independent of notification permission and channel vibration.
  Tapping the ongoing alarm notification opens the app to the Silence alarm button.

The latest **1,000 log entries** and deduplication baselines stay in service
memory. Rotation, Home, screen off, switching apps, destroying the UI, and
reopening it preserve the active service/session. Logs are not automatically
saved; they are lost if Android kills the process or the service is destroyed.
The app saves the selected device address/name, monitoring intent, pending alarm
state, armed/disarmed setting, and notification permission prompt state locally. There is no analytics or
internet permission. Export logs if you want a permanent copy.

Selecting a board starts Android's **connectedDevice foreground service** while
the app is visible. Its ongoing **SoundMeter monitoring** notification reports
connection/retry state and received count, opens the app when tapped, and has a
**Disconnect** action while monitoring. An active alarm adds the **mediaPlayback**
foreground-service type and keeps the service alive even if BLE is disconnected.
After monitoring is stopped, Silence alarm stops audio and removes the remaining
foreground notification. Swiping the app out of Recents does not deliberately stop
the service. Disconnect explicitly to release BLE and cancel retries; the screen
can sleep normally while monitoring. Scanning/device selection is only a visible
UI flow and is cancelled if you leave before selecting a board.

Android may restart the sticky service after a system process kill; it reconnects
to the saved device and rereads a historical snapshot, so that read does not
replay an alert. If an already active alarm was pending and is still armed, the sticky restart
resumes it separately from BLE and its snapshot read. A saved disarmed state
never restores alarm audio. This is best effort: a user force-stop/Android Active apps Stop,
phone restart, revoked Bluetooth permission, disabled Bluetooth, battery
restrictions, and system/vendor process limits can stop monitoring. Open the app
and connect again if monitoring was stopped. There is no boot auto-start or
permanent CPU wake lock. Events during an unavailable connection can be missed.

Notification permission, channel settings, Do Not Disturb, vibration settings,
and Android's alert rate limits control visible alerts and vibration. The app
respects them and shows the notification/channel status in the UI; it does not
bypass DND. Alarm playback uses Android's `USAGE_ALARM` audio attributes and
requests transient audio focus. Zero Alarm volume, system audio routing, DND,
a phone call, or another audio-focus owner can mute or pause audible playback.
The alarm stays active/acknowledgeable in the UI when audio is paused or fails;
the status explains the problem. System force-stop/process termination can stop
audio. No exact-alarm scheduling permission or forced volume changes are used.

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
clearing, rotation, document export, and a foreground service that remains alive
after Activity destruction. The background test checks that a new event posts
an alert, a historical/duplicate value does not, the channel vibrates, reopening
reattaches to the same service, and the notification Disconnect action stops it.
These fixture tests do not prove BLE
radio delivery or the platform's permission and CCCD behavior.

Version 2 was validated against a real SoundMeter on a Pixel 6a (Android 17):
discovery, subscription, initial snapshot, live event decoding, and recovery from
intermittent GATT status-8 timeouts. Version 3 adds tests for notification gating,
background lifecycle, foreground-service status, vibration-channel configuration,
and stopping monitoring from the notification.
Version 3 validation on the Pixel 6a: 29 unit tests and seven Android tests passed;
APK build and Android lint completed with no lint errors. The real BLE service
remained a foreground service after Home, and live detections appeared in the
retained session. The Android tests verified a posted event notification and the
enabled two-pulse vibration channel; physical vibration/audio perception, long
screen-off/Doze sessions, and vendor-specific battery limits require device use.

Version 4 validation covers the one-second two-beep PCM waveform (frequency,
quiet gaps, amplitude, and soft loop boundary) plus alarm latching, playback for
more than one cycle after Activity destruction, preserving the alarm after
Disconnect/Clear, silencing in the app, retaining BLE monitoring after silence,
duplicate suppression after silence, rearming on a unique new detection, and a
test alarm without BLE. The Android tests always silence alarms during cleanup.
The version 4 APK build, 32 unit tests, nine Android tests on the Pixel 6a
(Android 17), and Android lint all passed (no lint errors).

Version 5 adds Android checks for the Silence alarm → Arm alarm toggle,
blocking new detections and Test alarm while disarmed, retaining monitoring and
logging, explicit rearming without replaying skipped events, and saving disarmed
state across destruction/recreation of both the Activity and service.
The version 5 APK build, 32 unit tests, 10 Android tests on the Pixel 6a
(Android 17), and Android lint passed (no lint errors).

For hardware acceptance, verify discovery, Nearby devices denial/retry, Bluetooth
off/on, subscription at MTU 23, an actual sound alert, quiet periods, out-of-range
loss/reconnect, board reboot, no duplicate alarm from snapshot reads, cancellation
of retries, alerts while the app is backgrounded and the screen is off,
notification permission denial/channel mute, and saving/reopening the exported log. The debug APK uses this
machine's debug signing key; publishing requires release signing.

## Implementation

- `BleConnection`: service-filtered scan, selection, GATT lifecycle, notification
  subscription, initial read, stale-callback protection, setup timeout, and retry.
- `GattHandshake`: setup ordering and backoff policy.
- `ReceiverProtocol`: pure Java binary decoder and protocol UUIDs.
- `EventDeduplicator`: bounded per-device/session nonzero uint32 ordering.
- `MonitorLog`: event/snapshot classification, metrics, bounded log, and export.
- `MonitoringService`: BLE ownership, in-memory session, background alerts,
  foreground-service lifecycle, latched alarm, locally saved settings, and sticky restart.
- `AlarmPattern` / `LoopingAlarm`: generated two-beep cadence, native infinite
  audio loop using Alarm volume, audio focus, and resource cleanup.
- `MonitorNotifications`: silent ongoing status channel and a separate vibrating
  event-alert channel, notification permission checks, and Disconnect action.
- `MainActivity`: permissions, Bluetooth enable prompt, device picker, notification
  settings, and a bound observer of the service. Rendering batches updates every 50 ms.

References: [BLE_PROTOCOL.md](BLE_PROTOCOL.md),
[Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions),
[Android BLE data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data),
[background BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background),
[notification permissions](https://developer.android.com/develop/ui/views/notifications/notification-permission).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licenses.
