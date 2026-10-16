# SoundMeter BLE protocol, version 1

This is the client implementation contract for `examples/SoundMeter` on LilyGO
T-Camera ESP32-S3 v1.6 and v1.7. The board is a BLE peripheral/GATT server; the
client app is a BLE central/GATT client. It sends confirmed sound detections,
with no microphone audio or continuous level stream. Firmware constants and
encoding are in [`include/sound_ble_protocol.h`](include/sound_ble_protocol.h).
This replaces SoundMeter's ESP-NOW transport; the 32-byte ESP-NOW packet and
`SoundEventReceiver` example are incompatible with this BLE protocol.

## Discovery and GATT contract

| Item | Value |
| --- | --- |
| Default local name | `RohBot SoundMeter` (from `BLE_DEVICE_NAME` in `examples/SoundMeter/config.h`; not a unique identifier) |
| Advertised primary service | `9e7a0001-4f3b-4d61-9c8a-2b6f0e5d7100` |
| Event characteristic | `9e7a0002-4f3b-4d61-9c8a-2b6f0e5d7100` |
| Characteristic properties | Read, Notify |
| Client Characteristic Configuration Descriptor (CCCD) | `00002902-0000-1000-8000-00805f9b34fb` |
| Enable / disable notifications | CCCD bytes `01 00` / `00 00` |
| Value length | Exactly 20 bytes, binary |
| Minimum ATT MTU | 23; no larger MTU or fragmentation required |
| Pairing / bonding | Not required or configured by this firmware |
| Intended concurrency | One connected client; advertising stops during connection |

Scan by service UUID, then let the user select a device if several match. The
local name may arrive in the scan response. Use the platform's peripheral ID
to distinguish devices; do not assume every platform exposes a MAC address.
The service UUID identifies the protocol, not a particular board.

Use the platform's notification subscription API, which should configure the
CCCD. Characteristics have no write operations, commands, configuration fields,
or indications. Reads and subscriptions are accessible without authentication or
encryption; anyone in radio range can connect and read detections. A connected
client can occupy the only advertised connection opportunity.

## Client connection sequence

1. Obtain the platform's Bluetooth permissions and ensure Bluetooth is on.
2. Scan for the service UUID, select the board, connect, and discover the service
   and event characteristic. Discover handles each connection; do not hardcode them.
3. Register the notification handler, then enable notifications and await success.
4. Read the event characteristic once to get the latest snapshot. Feed reads and
   notifications through the same decoder and deduplicator. A notification can
   arrive before the read completes; a read can duplicate an event already seen.
5. On disconnect, show disconnected status and reconnect with a bounded backoff
   (for example 1, 2, 4, 8, then 10 seconds). Rediscover, resubscribe, and reread
   after every connection. The board restarts advertising from its main loop
   after disconnect, retrying advertising startup at 500 ms intervals.

No messages during a quiet period is normal. There is no heartbeat. Track the
BLE connection state independently of event arrival. `BLE WAIT` on the OLED
means no connected client; a connected client still needs to subscribe.

## Binary value layout

All multibyte integers are **little-endian**. Signed fields use two's complement.
Offsets are zero-based bytes. One notification always contains one complete
value; there is no framing prefix, text encoding, checksum, or terminator.

| Offset | Bytes | Type | Field | Meaning |
| --- | --- | --- | --- | --- |
| 0 | 1 | uint8 | `version` | Always `1` |
| 1 | 1 | uint8 | `type` | `0`: no event yet; `1`: confirmed sound event |
| 2 | 2 | uint16 | `aboveThresholdMs` | Confirmation duration, saturated at 65535 ms |
| 4 | 4 | uint32 | `bootId` | Random session identifier generated at BLE initialization |
| 8 | 4 | uint32 | `sequence` | Event counter; `0` only for initial snapshot |
| 12 | 4 | uint32 | `uptimeMs` | Board `millis()` at confirmation; wraps about every 49.7 days |
| 16 | 2 | int16 | `levelCentiDbfs` | Triggering block RMS level × 100; −12000 through 0 |
| 18 | 2 | int16 | `floorCentiDbfs` | Learned noise floor × 100; −12000 through 0 |

At startup the readable value is version 1, type 0, the current `bootId`, sequence
0, uptime 0, duration 0, and both levels −12000. This is a sentinel, not a
measurement or event. It is replaced on the first detection. Type 0 is never
sent as a notification. The readable value remains the latest event until the
next detection or reboot, including across disconnections and floor relearning.
A read does not consume the value or trigger a notification.

For type 1, sequence increments once per detection even with no client connected
or subscribed. It starts at 1 and wraps from 4294967295 to 1, skipping zero.
`bootId` can be zero; it is random, not a globally unique device identifier.
Retain device identity alongside boot ID and sequence.

Compute `levelDbfs = levelCentiDbfs / 100`, `floorDbfs = floorCentiDbfs / 100`,
and `riseDb = (levelCentiDbfs - floorCentiDbfs) / 100` using signed arithmetic.
The levels always use dBFS even when the OLED displays calibrated SPL. Zero is
digital full scale and quieter signals are negative. These are unweighted RMS
measurements, not dBA or calibrated sound pressure levels.

`aboveThresholdMs` is the consecutive qualifying audio duration at confirmation,
not the eventual total length of the sound. A value of 65535 means 65535 ms or
longer. `uptimeMs` is not a UTC timestamp; store client receipt time separately.

## Detection and delivery semantics

Default firmware learns background noise for five seconds, then detects at
least 64 ms (two 32 ms blocks) at least 10 dB above the adaptive floor and at
least −85 dBFS. It emits one event when confirmed. Sustained sounds do not emit
repeated events: rearming requires 500 ms of quiet and a two-second minimum
interval between alerts. These thresholds are firmware settings, not values
negotiated over BLE. See `examples/SoundMeter/config.h` and its README.

There is one notification attempt per event when connected, subject to CCCD
subscription. Notifications have no application acknowledgement, retry, or
delivery guarantee. An event can be missed during connection setup, radio loss,
disconnect, or client suspension. The latest event stays readable, but there is
no history queue: if several events occur while disconnected, only the latest
is recoverable. Never label the device event counter as a delivered-event count.

Deduplicate by `(platform device ID, bootId, sequence)` across notifications and
reads. For a simple client, keep the most recent sequence for each device/session
and ignore duplicate or older snapshots. Within a session, use the ring of
nonzero uint32 sequences: `delta = (new - old + 4294967295) % 4294967295`.
A delta of 0 is duplicate; 1 through 2147483647 is newer; larger is older or
ambiguous. A delta greater than 1 indicates missed detections. On a new boot ID,
reset the sequence baseline. Random boot ID collisions are possible; this is
best-effort deduplication, not persistent identity. On a first-ever read, treat
the value as historical/latest state rather than a newly occurring alert.

Reject values whose length is not 20, whose version is not 1, whose type is not
0 or 1, whose type/sequence combination is invalid, or whose levels are outside
−12000…0. Reject unknown versions/types without terminating the notification
handler; report unsupported protocol to the user. Future incompatible formats
must increment `version`; clients must not guess the layout.

## Reference decoders

These pure decoders work on both reads and notifications. They do not perform
subscription, deduplication, timestamp conversion, or UI updates.

### TypeScript / JavaScript

Pass the `DataView` supplied by the BLE API, or wrap received bytes in a
`DataView` using the buffer's actual byte offset and byte length.

```js
function decodeSoundEvent(value) {
  if (value.byteLength !== 20) throw new Error("Invalid event length");
  const version = value.getUint8(0);
  const type = value.getUint8(1);
  const sequence = value.getUint32(8, true);
  const level = value.getInt16(16, true);
  const floor = value.getInt16(18, true);
  if (version !== 1 || (type !== 0 && type !== 1))
    throw new Error("Unsupported protocol");
  if ((type === 0) !== (sequence === 0) ||
      level < -12000 || level > 0 || floor < -12000 || floor > 0)
    throw new Error("Invalid event fields");
  return {
    version, type, sequence,
    bootId: value.getUint32(4, true),
    uptimeMs: value.getUint32(12, true),
    aboveThresholdMs: value.getUint16(2, true),
    levelDbfs: level / 100,
    floorDbfs: floor / 100,
    riseDb: (level - floor) / 100,
  };
}
```

### Python

```python
import struct

def decode_sound_event(data):
    if len(data) != 20:
        raise ValueError("Invalid event length")
    version, kind, duration, boot, sequence, uptime, level, floor = struct.unpack(
        "<BBHIIIhh", data
    )
    if version != 1 or kind not in (0, 1):
        raise ValueError("Unsupported protocol")
    if ((kind == 0) != (sequence == 0)
            or not -12000 <= level <= 0 or not -12000 <= floor <= 0):
        raise ValueError("Invalid event fields")
    return dict(version=version, type=kind, bootId=boot, sequence=sequence,
                uptimeMs=uptime, aboveThresholdMs=duration,
                levelDbfs=level / 100, floorDbfs=floor / 100,
                riseDb=(level - floor) / 100)
```

## Test vector and acceptance checks

Hexadecimal value, exactly 20 bytes:

```text
01 01 40 00 78 56 34 12 01 00 00 00 10 27 00 00 60 f0 90 e8
```

Expected: version 1, type 1, duration 64 ms, boot ID `0x12345678`
(305419896), sequence 1, uptime 10000 ms, level −40.00 dBFS, floor −60.00 dBFS,
rise 20.00 dB. This vector is checked by `tests/sound_ble_protocol_test.cpp`.

A generated client should pass these checks:

- Decode the vector above and the initial type-0 snapshot correctly.
- Reject malformed lengths, unknown versions/types, invalid sequences, and levels.
- Subscribe at the default MTU and receive a full event after noise learning.
- Suppress duplicate alerts when a notification and read return the same event.
- Reconnect, resubscribe, and distinguish the retained latest event from a new one.
- Handle board reboot, uint32 uptime wrap, and sequence wrap without negative IDs.
- Show quiet, disconnected, permission-denied, and unsupported-protocol states.

The firmware uses the built-in Arduino-ESP32 BLE server API described in
[Espressif's BLE documentation](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/ble.html).
Hardware checks are required to verify radio delivery and platform-specific
permission, subscription, and background behavior.
