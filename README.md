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
- All participants must use network protocol 14 (this build). Older protocol
  versions are rejected rather than starting an incompatible game.

Peer-game UDP events are scoped to a per-round nonce, and retransmitted score
events are deduplicated. Delayed packets from an earlier round therefore cannot
alter the next round's score, roster, grenade pairing, or end state.

## Player capacity and hosting

The game supports up to 20 players. A separate dedicated-host phone may be used,
for a maximum of 21 phones total. Alternatively, a JDK 17 laptop can run the
included dedicated host, so no phone is consumed as the host.

| Mode | Player IDs |
| --- | --- |
| Two teams | Team 1: 1-10; Team 2: 11-20 |
| Four teams | Teams 1-4: 1-5, 6-10, 11-15, 16-20 |
| Free-for-all | 1-20 |

Choose each player's desired team/ID before joining and confirm the displayed
team before starting. If an ID conflicts, a protocol-14 host automatically
moves that player to the first free ID on the same team; a full team still
rejects the join. The dedicated host is not a player. At the end of a dedicated
round, the host keeps listening but closes the current client sessions and
clears the roster;
players must join again before the next round.

## Laptop-hosted games and live displays

The repository includes a dependency-free Java 17 laptop host at
[`laptop-host/`](laptop-host/). It is protocol-compatible with the Android
dedicated host and provides a local two-window dashboard:

- A tactical GPS map for the two teams, including movement trails, position
  snapshots, and laser lines for verified hits and eliminations. It also has
  Game Master Respawn buttons for players currently waiting to return.
- A leaderboard with KILLS, HITS, SHOTS, and live accuracy.

Run it on the same Wi-Fi network as the phones:

```bash
./laptop-host/run.sh
```

Use the printed laptop IP in the app's **Join Game** flow, then open the local
dashboard's **map** and **leaderboard** display windows. GPS updates are
change-driven and forwarded at up to four times per second in hosted games.
The laptop host broadcasts the same nearby-game invitation as a phone host,
so idle phones can offer to join automatically. See
[the laptop-host guide](laptop-host/README.md) for ports, firewall guidance,
display security, and options.

## Checkpoint and Game Master respawns

Network two-team and four-team games use team QR checkpoints after a player is
eliminated. The first game-start countdown remains unchanged. On later deaths,
the app opens its built-in phone-camera scanner automatically and gives the
player a choice:

- Scan their own team's respawn checkpoint to return immediately.
- Close the scanner and wait the visible three-minute respawn countdown.

Print one distinct QR code for each team. The preferred payloads are
`SIMPLECOIL:RESPAWN:1` through `SIMPLECOIL:RESPAWN:4`; the simple forms
`TEAM 1 RESPAWN` through `TEAM 4 RESPAWN` are accepted too. A checkpoint for a
different team or an unrelated QR code is rejected. The app needs Camera
permission and a camera-equipped phone; without either, the player can still
wait for the timer.

For dedicated-host games, the host verifies the player's recorded elimination
before honoring a checkpoint request. The dedicated-host screen also has a
**Game Master Respawn** control. It lists only connected players currently
waiting to respawn and can return one of them immediately. This manual option
also works for free-for-all games, where QR checkpoints are not used.

## Build

Install JDK 17 and Android SDK Platform 34, then set `JAVA_HOME` and
`ANDROID_HOME` as appropriate for your machine.

```bash
./gradlew assembleDebug
```

The launcher name defaults to `Dean's 11th Birthday Blaster Bash`. Override it
for a particular build with the `appName` Gradle property:

```bash
./gradlew assembleDebug -PappName="Your Event Name"
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Release APKs must be signed with a keystore that is backed up securely; the
same signing key is required for every future update. Copy
[`release.properties.example`](release.properties.example) to the ignored
`release.properties` file, fill in the keystore details, then build:

```bash
./gradlew assembleRelease
```

You may instead provide `releaseStoreFile`, `releaseStorePassword`,
`releaseKeyAlias`, and `releaseKeyPassword` as Gradle `-P` properties. Do not
commit the keystore or credentials. The signed APK is written to
`app/build/outputs/apk/release/app-release.apk`.

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
2. Grant the Bluetooth, location, and (for checkpoint respawns) Camera
   permissions requested by the app.
3. Join every phone to the same Wi-Fi network and verify the host has a usable
   local IPv4 address.
4. Select unique player IDs, connect the supported BLE hardware, and then join
   the host.
5. Wait for clock synchronization before pressing Start; if prompted, wait a
   moment and try again.

For development and contribution history, see the original
[SimpleCoil repository](https://github.com/Dees-Troy/SimpleCoil).
