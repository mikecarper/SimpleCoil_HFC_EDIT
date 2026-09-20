# SimpleCoil

SimpleCoil is an Android companion app for supported Bluetooth LE laser-tag
hardware. This fork is based on [Dees-Troy/SimpleCoil](https://github.com/Dees-Troy/SimpleCoil)
and retains the project's Apache-2.0 license.

## Platform support

- Minimum Android version: Android 5.0 / API 21. Android 5.1.1 is supported.
- Build SDK: API 34; target SDK: API 29.
- A Bluetooth LE-capable device is required by the app manifest.
- Network games require all phones to use the same local Wi-Fi network with a
  usable IPv4/DHCP lease. Internet access is not required.

## Multiplayer and synchronized starts

Network games use TCP for the lobby and an NTP-style exchange of monotonic-clock
timestamps to synchronize a start deadline. The app never changes a phone's
system clock and does not use a public NTP server.

- Each player completes five valid clock exchanges before a start is accepted.
- Exchanges with a round-trip delay above 500 ms are discarded; the lowest-delay
  valid sample is used to estimate clock offset.
- The host sends one shared start deadline and every phone shows a countdown to
  it. Timed games also use a shared end deadline.
- The design target is alignment within one second on a healthy local network;
  Wi-Fi congestion, device suspension, or poor signal can still affect it.
- All participants must use network protocol 12 (this build). Older protocol
  versions are rejected rather than starting an incompatible game.

Peer-game UDP events are scoped to a per-round nonce, and retransmitted score
events are deduplicated. Delayed packets from an earlier round therefore cannot
alter the next round's score, roster, grenade pairing, or end state.

## Player capacity and hosting

The game supports up to 20 players. A separate dedicated-host phone may be used,
for a maximum of 21 phones total.

| Mode | Player IDs |
| --- | --- |
| Two teams | Team 1: 1-10; Team 2: 11-20 |
| Four teams | Teams 1-4: 1-5, 6-10, 11-15, 16-20 |
| Free-for-all | 1-20 |

Give every player a unique ID and confirm the displayed team before starting.
The dedicated host is not a player. At the end of a dedicated round, the host
keeps listening but closes the current client sessions and clears the roster;
players must join again before the next round.

## Build

Install JDK 17 and Android SDK Platform 34, then set `JAVA_HOME` and
`ANDROID_HOME` as appropriate for your machine.

```bash
./gradlew assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

Run the local verification gate with:

```bash
./gradlew assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest
```

Instrumented tests are compiled by that command but require an ADB-connected
Android device to run. Install the APKs and run them with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.simplecoil.simplecoil.test/androidx.test.runner.AndroidJUnitRunner
```

## Field checklist

1. Install the same build on every participating phone.
2. Grant the Bluetooth and location permissions requested by the app.
3. Join every phone to the same Wi-Fi network and verify the host has a usable
   local IPv4 address.
4. Select unique player IDs, connect the supported BLE hardware, and then join
   the host.
5. Wait for clock synchronization before pressing Start; if prompted, wait a
   moment and try again.

For development and contribution history, see the original
[SimpleCoil repository](https://github.com/Dees-Troy/SimpleCoil).
