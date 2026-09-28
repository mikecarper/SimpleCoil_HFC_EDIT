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

### Shared lobby (1.23)

Open the app on every phone connected to the same Wi-Fi/AP. The new lobby
automatically discovers the host, joins it, and assigns an unused player ID.
You no longer need to pair a gun or separately choose Create/Join first.
If no host exists, a phone creates one after a short discovery period.
Simultaneous phone hosts converge on the lowest IPv4 address; a laptop or
dedicated host takes priority while the lobby is idle. An already running
match is never migrated to another host.

The screen separates Wi-Fi, your player, match rules, and the shared roster.
The bottom action always stays visible and tells you the next step. Connect
your gun, edit your name, and optionally use Team or Scan QR to choose your
team. Automatic assignment balances ordinary team games. Every player sees
who still needs a gun. Only the host changes the mode or starts the shared
countdown, after all participating players have guns ready and synchronized clocks.
There is no extra Ready button: gun pairing and clock sync happen in parallel.
Scanning, trigger-hold instructions, and Cancel appear directly in the lobby.
The host can tap **Sit out** beside a spare phone and **Play** to add it back
before starting. Sitting-out phones remain on the same hub without holding up
the countdown, entering combat, or affecting Boss health and victory counts.
They wait for the next round; only the host changes participation. A phone
promoted to host participates, and therefore needs a gun, or can instead use
the dedicated-host option. The laptop map has the same participation controls.
The compact roster is grouped by team and shows the host, gun status, clock
sync, and reconnecting players. Unnamed phones display their player ID.
Match summaries include limits, health/shields, ammo, reload, fire mode, and
the objective. All these changes preserve automatic lobby join and the single
host Start action.
Equipment, phone hotspot setup, solo practice, and manual host entry remain
available in the options.

The lobby shows a smoothed battery estimate, independently for both guns in
two-gun Boss mode. The four-AA RK-45 uses a NiMH profile and an approximate
voltage, for example `~4.8 V / Good`. Full is around 5.4 V or above; 4.8 V is
normal NiMH operating voltage, not half empty. The indicator says Recharge soon
at 4.1-4.2 V and Empty - recharge at 4.0 V or below. It does not extrapolate
precise voltages outside the measured 2.0-6.0 V range. These are coarse
voltage-based conditions, not a percentage or a per-cell safety monitor.
The earlier 2.25 V electronics test is not a suitable NiMH empty threshold.

The reading waits for the gun type and 20 real samples. It uses a rolling
15-second window, discards the lowest 75% of readings, and averages the highest
25% (rounding the retained sample count up). The display and recoil cutoff use
the same filtered voltage. Older samples expire by elapsed time, not packet
count; reconnecting clears the window. The uncalibrated six-cell SR-12
retains its legacy qualitative indicator without claiming a voltage.
See [calibration and NiMH thresholds](docs/gun-battery-calibration.md) and the
[NiMH manufacturer's guide](https://data.energizer.com/pdfs/nickelmetalhydride_appman.pdf).
At an estimated 4.0 V or below, the four-AA RK-45 automatically enters low-power
mode and turns recoil off, including in tournament games. Shot mode, firing
cadence, ammo, reload, and damage stay unchanged. The lobby and game HUD show
low power; two Boss guns are protected independently. The setting is remembered
per gun across reconnects and app restarts. Recoil stays off for that connection
to prevent voltage-rebound cycling; reconnect with a healthy pack (initial
smoothed reading at least 4.8 V) to restore normal recoil. This saves motor power
but does not shut down the gun or make an empty pack safe to keep using.

### Kill-streak voice

English kill-streak jokes use bundled recordings of Kokoro's British male
"George" voice. No voice-pack install, Internet connection, or on-phone neural
speech processing is needed. "Enemy Destroyed"
keeps the normal voice at 150% speed, followed by the existing one-second
pause before the joke. Countdown, reload, respawn, and other announcements
keep their usual voice. Non-English prompts keep their original language.

All 20 jokes are included in the APK and share the normal speech queue. If a
recording cannot be registered or queued, the joke falls back to system speech.
See [voice sources, generation, and checks](docs/british-male-voice.md).

### Ending, leaving, or joining a round (1.27)

During countdown, play, or respawn, **End / Leave game** offers two separate
actions. **Agree to end** asks for an early end; the round ends only after two
different participating players approve. Duplicate taps and sitting-out phones
do not count. Approval lasts for the current round and can be withdrawn while
waiting for a second player. Leaving also removes that player's approval.
A dedicated or laptop game master can request the vote but does not count as
a participating player. Timers, score limits, and other victory conditions
still finish the round automatically.

**Leave game** asks for confirmation and exits only your phone, without ending
everyone else's round. It is available throughout the round, including via
Back, and is no longer limited to the first 30 seconds. If the playing phone
host leaves, the remaining phone with the lowest player ID takes over the
game-state broadcasts and the join service. Leaving sits you out until you
scan back in, or until the next round opens.

To join a game already running, connect to its Wi-Fi and pair your gun, then
tap **Scan team QR to join**. Scan the team's existing respawn/base code
(`SIMPLECOIL:RESPAWN:1` through `SIMPLECOIL:RESPAWN:4`, as appropriate for the
mode). The host assigns an available slot on that team. New players and
players who left can join during countdown or play without restarting anyone
else's timer. A returning app installation keeps its kills, deaths and captures;
it cannot change teams or recover exhausted limited lives by leaving. Infected
players return through the zombie team's base. Boss scaling stays fixed at
the original countdown roster, and joining cannot create a second boss.

Departed slots can be reused in a full 32-player game. Per-seat generations
reject the former occupant's delayed state, hits and leave packets. Team totals
retain departed players' contributions. TCP stays open for infrequent roster
changes; combat still uses the existing fast UDP path. A team QR is required
for voluntary re-entry; simply waiting in the lobby does not pull you back in.

End decisions are acknowledged and retried per phone for up to 30 seconds,
independently of gameplay/lobby socket restarts. Completed-round receipts are
retained for 10 minutes to repair a returning player's stale round. Old end
messages cannot end a new round. Install 1.32 on every phone for this build.

If a round is stopped before its first 60 seconds of play have elapsed, the
next round can start without the usual 30-second between-game wait. Canceling
a countdown also permits an immediate retry. The normal synchronized start
countdown still runs; rounds lasting 60 seconds or longer retain the wait.

The lobby shows **Your previous game**: your kills, deaths, hits taken, mode,
team, and team kills (captures in CTF) when applicable. These local stats survive app restarts
and stay visible while preparing the next game. Leaving early labels them
**Stats at exit**. A canceled countdown does not replace the previous result.
Games played before installing this update cannot be recovered retroactively.

In the lobby, tap **Make this phone host** to choose a phone explicitly. Idle
phones on the same Wi-Fi move to it and suppress automatic phone hosting while
it remains reachable. The selected phone shows **This phone is the selected host**;
tap **Return to automatic hosting** to release the selection. Choosing a
different phone replaces the earlier manual selection. This selection lasts
for the current app/network session, not permanently across network changes.

Host priority is laptop, manually selected phone (including the non-playing
phone game-master screen), then automatic phone. A running round always
finishes first: a laptop appearing during a phone-hosted game takes over only
after that round ends. Phone hosting controls are disabled while a reachable
laptop is present. Automatic phones and laptops prefer the oldest host by
monotonic hosting age, with the lowest IP breaking ties within two seconds.
Simultaneous manual selections use the same tie-break; otherwise the newest
selection wins. No shared wall clock is required for host selection.

Host discovery leases expire after 15 seconds without a beacon or live TCP
traffic. A stalled lobby connection/host startup also times out after 15
seconds, and its failed endpoint is skipped for 30 seconds before retrying.
The remaining phones can resume automatic hosting; the manual/laptop choice
does not lock the lobby forever if its host disappears. Brief Wi-Fi drops
preserve the existing selection. These lobby timeouts do not interrupt play.

Lobby discovery uses a small UDP beacon/query every two seconds, not a scan
of every address. While looking for Wi-Fi, fresh radio scans are limited to
once every three seconds; scan results can still trigger an immediate join,
and tapping Wi-Fi requests an immediate scan. The AP must allow client-to-client traffic and subnet
broadcasts; turn off guest/client isolation. All phones and the laptop host
must run this protocol-28 build. A phone arriving during an active game offers
team-QR admission or waits for the next round instead of creating a separate lobby.

Network games use TCP for the lobby and an NTP-style exchange of monotonic-clock
timestamps to synchronize a start deadline. The app never changes a phone's
system clock and does not use a public NTP server.

- Lobby join immediately begins a 12-exchange clock-calibration burst before a
  start is accepted.
- Exchanges with a round-trip delay above 80 ms are discarded; the lowest-delay
  valid sample is used to estimate clock offset. That bounds the network-path
  uncertainty of an accepted estimate to 40 ms relative to the host.
- The host sends one shared start deadline and every phone shows a countdown to
  it. Timed games also use a shared end deadline.
- The network-offset target is below 50 ms on a healthy local network. Wi-Fi
  congestion, device suspension, and normal UI scheduling can still affect the
  moment a device visibly reacts.
- All participants must use network protocol 28 (this build). Older protocol
  versions are rejected rather than starting an incompatible game.

During every network game, each phone sends one subnet-directed IPv4 state
broadcast per second instead of one unicast packet per player. The fixed
1,376-byte protocol-28 snapshot fits 32 player records below the 1,472-byte UDP
payload ceiling and avoids normal IPv4 fragmentation. Each record carries
cumulative score/deaths,
health, shield, shots, player state, grenade pairing, a deduplicated important
event, and GPS rounded to 0.00001 degrees (about one metre). Everyone caches
the newer player rows they hear. The host still publishes an authoritative tick
every second; after a missing tick's 20% grace window (200 ms), a phone fills
only newer portions of that tick from its overheard cache. Late or stale data
cannot roll state back. Names, addresses, and other bulky mappings remain in
the TCP roster channel. Android holds the high-performance Wi-Fi lock during gameplay;
the multicast lock also stays active during lobby discovery so idle phones can
hear the shared host.

QR power-ups are off by default. Before starting a game, the lobby host can
choose Off or 4 through 8 different codes with the Power-ups control. All phones
receive the host's choice. During play, Scan power-up opens a small camera window
without replacing the game screen. Print eight QR codes with these exact text
payloads: `SIMPLECOIL:POWERUP:1` through `SIMPLECOIL:POWERUP:8`. A code counts
only once per life; team respawn codes never count. Every configured number of
different codes grants a random health refill, shield refill, or five-second
temporary shield boost. Death clears unused progress and allows the codes to
be collected again. With power-ups off, this gameplay scanner stays closed;
the full-screen team QR scanner still opens after death when QR respawns are enabled.

Combat feedback does not wait for that one-second state cadence. `HIT`, `OUT`,
`ALREADY DEAD`, and elimination use a 32-byte subnet broadcast that every phone
can overhear. Only the addressed phone returns a 32-byte unicast ACK, avoiding
an ACK storm with 32 players. If needed, retries at 20 ms and 60 ms are unicast
only to that target and stop as soon as its ACK arrives, for a hard ceiling of
three sends; duplicate events are ACKed but applied only once. Combat uses its
own bounded sender so a hit cannot sit behind a full 32-phone authority
fan-out. Untargeted shot feedback is sent once, while the cumulative one-second
snapshots remain the final repair path for a completely missed combat burst.

## Player capacity and hosting

The game supports up to 32 players. A separate dedicated-host phone may be used,
for a maximum of 33 phones total. Alternatively, a JDK 17 laptop can run the
included dedicated host, so no phone is consumed as the host.

| Locked tournament mode | Player IDs |
| --- | --- |
| Two teams | Team 1: 1-16; Team 2: 17-32 |
| Boss Mode | Boss: Player 1; Hunters: Players 2-32 |
| Infection | Original zombie: Player 1; Survivors: Players 2-32 |

Every game uses the same server-authoritative tournament profile: 5 health, 10 shields,
30-shot magazines, a 1.5-second reload, one damage per hit, recoil enabled,
and single-shot firing. Player and host controls cannot change those rules.
After health damage, another damaging hit restarts a 30-second inactivity
wait. Health then regenerates one point per second until full, like shields.
Eliminated players cannot regenerate.

Boss Mode is the alternate locked tournament variant. Player 1 is the boss;
everyone else is a hunter. Hunters have 2 health, 3 shields, a 30-round
magazine, no health or shield regeneration, and forced single-shot fire. The boss starts
with 5 health and 10 shields, then gains 1 health and 2 shields per hunter. For
example, against 10 hunters the boss has 15 health and 30 shields. The boss has
120 rounds, starts each game in automatic, and may switch among single, burst,
and automatic fire. With Outdoor range selected, both boss guns automatically
use the wide cone. Indoor range keeps its reduced power and no cone; hunters
do not gain the cone. The starting roster fixes boss strength for the round.
In the boss lobby, use "Gun 2..." to select another nearby SRG1 blaster.
The two blasters use the same boss player ID, health, and score, but each has its
own 120-round magazine and reload. The second gun reconnects automatically if
it was previously paired. The boss HUD shows gun 2's ammo on the left and gun
1's on the right; "--" means disconnected and "..." means reloading. Pairing a
second gun is unavailable to hunters.

Capture the Flag is a locked two-team tournament variant. Print two flag QR
codes containing `SIMPLECOIL:FLAG:1` and `SIMPLECOIL:FLAG:2`; the number is the
team that owns that flag. A live player scans the opposing flag, then scans
their own existing `SIMPLECOIL:RESPAWN:1` or `SIMPLECOIL:RESPAWN:2` base QR to
score. The robbed team hears "Your flag is stolen" once. The carrier's phone
loops its alarm sound at maximum alarm volume until the carrier scores or dies.
Death immediately returns the flag so another player can scan it. The plain
text forms `TEAM 1 FLAG` and `TEAM 2 FLAG` are also accepted. For the laptop
host, start the mode with `./laptop-host/run.sh --ctf`.

Infection starts Player 1 as the original zombie and everyone else as a
survivor. A killed survivor can run to the zombie base and scan the existing
Team 1 respawn QR before returning as a zombie, or wait the three-minute timer.
In timer-only respawn mode there is no scanner; conversion to a zombie happens
when the timer expires. Recruited zombies can be killed and use the same respawn
rule. Until the original zombie earns two kills,
incoming damage removes ammunition instead of health. After the second kill,
the original zombie can be killed normally and uses the same respawn rule to
return. Its reload takes 20% of the normal time only until its first death;
after respawning, reload speed is normal. The round ends when every survivor
has converted, or awards the win to the final uninfected player after another
survivor converts. Any survivor still alive and uninfected at the fixed
five-minute deadline also wins. Score and lives limits are disabled for this mode. On a laptop host, use
`./laptop-host/run.sh --infection`.

Balanced Random is an optional two-team assignment method on the laptop host.
Everyone joins the lobby, then the host presses **Start** to assign teams using
the kills and deaths saved on each player's phone from previous network games.
The team sizes differ by no more than two players. Player IDs no longer imply a
team in this mode, and lobby joins can take any free ID; each phone displays its
assigned team. With **QR Check-in**,
every player, including a playing phone host, scans the printed Team 1 or Team 2
respawn QR that matches their assignment. The shared 10-second countdown begins
after the final check-in, or after a 90-second check-in timeout if someone has
not scanned. Everyone keeps their assigned team when the timeout starts the game.
**No QR** starts that countdown as soon as the teams are assigned. Names can be
edited in the lobby; weapon and player rules remain locked. Clearing an app's data
also clears that phone's past-match totals.

While a QR check-in is pending, the phone repeats the assigned team number in
its spoken scan reminder. Respawn QR reminders also say the player's team.

Choose each player's desired team/ID before joining and confirm the displayed
team before starting. If an ID conflicts, a protocol-28 host automatically
moves that player to the first free ID on the same team; a full team still
rejects the join. The dedicated host is not a player. At the end of a dedicated
round, the host keeps listening but closes the current client sessions and
clears the roster;
players must join again before the next round.

## Laptop-hosted games and live displays

The repository includes a dependency-free Java 17 laptop host at
[`laptop-host/`](laptop-host/). It is protocol-compatible with the Android
dedicated host and provides a local two-window dashboard:

- An offline tactical GPS map for the two teams, including movement trails,
  position snapshots, and laser lines for verified hits and eliminations. It
  uses locally stored XYZ map tiles and still shows the tactical overlay when
  no base tiles are installed. It also has Game Master Respawn buttons for
  players currently waiting to return.
- A read-only tile service for phone maps. Joined phones learn the laptop tile
  port from the lobby and cache fetched tiles in their private app cache.
- A leaderboard with KILLS, HITS, SHOTS, and live accuracy.

Run it on the same Wi-Fi network as the phones:

```bash
./laptop-host/run.sh
```

Phones discover the laptop automatically on the same Wi-Fi. If broadcasts are
unavailable, use **Equipment and advanced options > Advanced: enter host address**
with the printed laptop IP. Open the local
dashboard's **map** and **leaderboard** display windows. GPS updates are
change-driven and forwarded at up to four times per second in hosted games.
The laptop host broadcasts the same nearby-game invitation as a phone host,
so idle phones can offer to join automatically. See
[the laptop-host guide](laptop-host/README.md) for ports, firewall guidance,
display security, and options.

## Timer, checkpoint, and Game Master respawns

The host selects **Match options > Respawn mode** in the lobby:

- **Timer only (no respawn QR)**: team players return automatically after
  **3:00**. The camera stays closed and there are no "scan your base" reminders.
- **QR checkpoint or timer** (default): scan the team's base to return early,
  or wait the same **3:00** timer.

This is a match-wide setting; joined players cannot override the host. Solo
practice and free-for-all use the configured respawn duration, **10 seconds**
by default. This setting does not lengthen the initial game-start countdown.
The dedicated-phone host has the same respawn-mode control; the laptop uses
`./laptop-host/run.sh --timer-respawn` (or `--qr-respawn` for the default).
CTF flag/base scans, optional power-ups, and optional QR team check-in remain
separate features; timer-only disables the respawn scanner, not those objectives.

With QR respawns enabled, network two-team and four-team games use team QR checkpoints after a player is
eliminated. The first game-start countdown remains unchanged. On later deaths,
the app opens its built-in phone-camera scanner automatically and gives the
player a choice:

- Scan their own team's respawn checkpoint to return immediately.
- Wait the visible three-minute respawn countdown; the scanner closes on respawn.

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
also works in timer-only mode and free-for-all games.

## Coordinates on the phone

Latitude and longitude appear in the lobby's player card and in the upper-right
corner during play. The readout shows **GPS Off** when match location sharing
is disabled, or **GPS acquiring...** until a recent location arrives. A fix
expires after 30 seconds without an update, including after GPS is disabled and
re-enabled. The app accepts Android's GPS and network-location providers; the coordinate readout
can therefore contain a network-based fix, not necessarily a satellite fix.
Zero latitude or longitude is treated as invalid rather than displayed.

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
