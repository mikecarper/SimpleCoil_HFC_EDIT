# SimpleCoil

SimpleCoil is an Android companion app for supported Bluetooth LE laser-tag
hardware. This fork is based on [Dees-Troy/SimpleCoil](https://github.com/Dees-Troy/SimpleCoil)
and retains the project's Apache-2.0 license.

## Latest release (1.52)

[Download version 1.52](https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/tag/1.52)
for post-round statistics and saved laptop timelines, stable teams with explicit
rebalancing, the GPS-based Airstrike reward, movement reminders, and improved
countdown and gun-command recovery. Each of the ten power-ups has a spoken
announcement after its reveal.

Update every phone and use the matching laptop host from this release's source
download. Version 1.52 uses network protocol 31; version 1.51 uses protocol 30
and cannot join the same game. Android 5.0 or newer is required.

The 1.52 APK uses the configured release signing key, which differs from the
debug key used for the published 1.51 APK. Android cannot install 1.52 as an
in-place update over 1.51. Reinstalling removes the old app's local settings
and player history, so save any information you need before switching.

## Platform support

- Minimum Android version: Android 5.0 / API 21. Android 5.1.1 is supported.
- Build SDK: API 34; target SDK: API 29.
- A Bluetooth LE-capable device is required by the app manifest.
- Network games require all phones to use the same local Wi-Fi network with a
  usable IPv4/DHCP lease. Internet access is not required.

## Multiplayer and synchronized starts

### Shared lobby (1.23)

Open the app on every phone and pair each gun first. Wi-Fi connects
automatically when configured; if needed, use the Wi-Fi button to join the same
network/AP. The lobby automatically discovers the host, joins it, and assigns
an unused player ID. You do not need to separately choose Create/Join.
If no host exists, a phone creates one after a short discovery period.
Simultaneous phone hosts converge on the lowest IPv4 address; a laptop or
dedicated host takes priority while the lobby is idle. An already running
match is never migrated to another host.

Tap the lobby's host bar to choose Stop hosting or Keep hosting. Stopping leaves
the phone on Wi-Fi, waiting to join another host, and disables lobby hosting
across app restarts. Choose Make this phone host to enable hosting again.
Lobby discovery stays active before a round, so competing hosts can converge
before Start is tapped.

The screen presents gun and player setup first, then Wi-Fi status, match rules,
and the shared roster.
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
The host can choose grenade damage in Match options before a round. The choices
are 1, 2, 3, 5, 10, and 15 damage per hit; the default is 1. This applies to
paired and unpaired grenade hits on every phone, without changing gun damage.
Grenade blasts also damage the thrower's team because the gun's damage report
does not include a grenade ID. Pairing is unnecessary for damage, and grenade
pairing text and controls are hidden to save screen space.
The dedicated phone host has the same setting. The compact roster is grouped
by team and shows the host, gun status, clock
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

Each phone saves the lowest reported calibrated voltage for each physical gun
in every round. Reconnecting or replacing batteries cannot erase that round’s
low. The laptop’s **Players → Phone & gun batteries** shows these readings by
player and gun Bluetooth ID, flagging 4.5 V or below for a battery check. Phones
retain 20 rounds for later delivery; the laptop keeps the latest 100 rounds per
player across restarts and phone transfers. These are minima of the smoothed
displayed estimate, not instantaneous raw voltage dips.

Gun telemetry and same-host network recovery continue when another app covers
the game. Connected guns use an ongoing notification and a partial wake lock;
returning to the game does not reconnect a healthy Bluetooth session. Quitting
the app releases those resources.

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

Bluetooth command recovery does not delay or schedule individual trigger pulls.
A command, read, or notification-setup operation without a completion for five
seconds retires that gun's stale connection and starts the normal reconnect path
instead of leaving a disabled magazine stuck indefinitely. Each Boss gun has
its own recovery. Returning to an already connected gun does not reconnect it
or trigger an extra reload. Reload confirmations received while the app is
paused are recovered on resume, retaining the original completion time so the
configured reload wait is not restarted.
During play, a failed reload stage retries twice, 250 ms apart, using fresh
command IDs. Retrying the refill does not repeat the reload wait or change the
other gun. The periodic game update also recovers missed confirmations and
resumes an ordinary reload whose wait finished while temporarily blocked.
Elimination, EMP, round completion and disconnect cancel obsolete retries;
exhausted retries leave another manual reload available.

### Recorded announcements

English game over results and all 20 kill streak lines use clips extracted
from the supplied Roman recording. "Enemy Destroyed" keeps the normal voice
at 150% speed, followed by the existing one-second pause before the streak
line. Countdown, reload, respawn, and other announcements keep their usual
voice. Non-English prompts keep their original language.

The 23 clips share the normal speech queue. If a recording cannot be
registered or queued, its line falls back to system speech. See
[audio source, extraction, and checks](docs/announcement-audio.md).

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

To join a game already running, pair your gun and connect to its Wi-Fi, then
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
messages cannot end a new round. Install 1.52 on every phone for this build.

Phones prepare a nearby relay after joining the same host. Normal hub Wi-Fi
remains the primary connection. If it fails, Bluetooth Classic links can forward
game traffic through other admitted phones. A phone with hub Wi-Fi forwards that
traffic to the original host, and sends replies back over Bluetooth.
The original host, player identities, round and operator controls stay in use;
losing the hub does not create a separate game. Version 1.40 removes automatic
Wi-Fi Direct connections and their Android invitations. Leave Bluetooth enabled
on all phones and Wi-Fi enabled on the phones within hub range. No phone-to-phone
pairing or confirmation is required on the tested LG Android 5.1.1 phones. The
app keeps these connections alive in the background.

An active phone lobby keeps its Wi-Fi performance and multicast locks between
rounds; passive invitation listening holds only the multicast lock. Leaving or
stopping releases both. UDP discovery safely restarts if a new join arrives
while the previous listener is stopping, and refreshes local/broadcast address
caches as the network changes. Stale discovery errors for the connected host
do not close its registered TCP lobby while fresh traffic proves it is healthy.
Phone hosts close a client connection whose socket write stalls for one second,
allowing other phones' clock replies, heartbeats and new joins to continue.

Version 1.51 checks the current Wi-Fi RSSI once per second and prepares one
Bluetooth buddy at -67 dBm or weaker, before hub coverage disappears. The weak
signal state clears at -62 dBm; an established buddy stays connected, so signal
fluctuations do not repeatedly tear down and rebuild the path. Wi-Fi remains
primary. Missing RSSI readings are ignored. Wi-Fi disconnection starts
preparation immediately on the next check, and expired host heartbeats try other
buddies without the previous additional five-second wait. Cold Bluetooth setup
can still take time; its 15-second cancellation limit and reliable-stream retry
window remain in place.

Three stationary version 1.51 Wi-Fi-off cuts returned confirmed bidirectional
controls and gun reports in 6.1-8.4 seconds from the radio-off command
(2.9-4.6 seconds after the radio was confirmed off). All 18 battery checks
passed, with no game reconnects, link closures or congestion during the three
steady windows. Wi-Fi was restored after each cut. Automated tests cover the
RSSI thresholds; gradual signal loss and outdoor range remain untested.

Version 1.43 keeps an admitted lobby connected to its original host while a
Bluetooth fallback forms, including past the initial lobby timeout and changes
between Wi-Fi and Bluetooth. A selected game on SlowFi or another Wi-Fi name also
stops the app's repeated Recoil network scans while that Wi-Fi stays associated.
An attention chime plays once when Android opens a screen-pinning confirmation;
ordinary reconnects do not replay it. The chime uses the phone's media volume.

Version 1.50 hardens nearby relays against idle timeouts and congestion.
Control acknowledgements and heartbeats take priority; queued retries coalesce,
and a full bounded queue recovers without closing the Bluetooth link. Pending
terminal bytes keep their 15-second retry window after a stream closes.
Unicast packets prefer one fresh direct or learned path and stop at their
destination. Stale or congested paths can use another neighbor, broadcasts
still propagate, and app restarts receive a fresh packet-sequence range.
Bluetooth setup cancels Classic discovery and allows 15 seconds to connect.
The host's player assignment stays authoritative when a blaster reports its old
ID; the app reprograms the gun instead of adopting another player's slot.

Clock probes still reach the original host. Actual relay sockets allow up to
800 ms of network round-trip delay and wait up to two seconds for a probe;
native Wi-Fi keeps its 80 ms delay limit. Both require twelve fresh samples and
choose the least-delayed estimate. The relay bound allows up to 400 ms of
network timing uncertainty per phone; it does not bound UI or gun latency.

The phone player list opens only after tapping its button. Host player updates,
reconnections and round completion do not open it automatically. It lists actual
players and team totals without empty player slots.

This requires a continuous path of nearby phones back to the original host.
Bluetooth provides the neighbor links; at least one phone must still reach the host.
A completely isolated phone must move back into reach. The relay carries game
traffic only, and does not extend Internet access. Radio range depends on the
phones, obstacles and interference; there is no guaranteed distance increase.

The version 1.50 live bench kept one phone's Wi-Fi radio disabled for 197.6
seconds. Bluetooth through a nearby Wi-Fi-connected phone returned all 20 host
battery requests and 20 fresh gun reports. The 182-second stability window had
no game reconnects, and clock readiness stayed true. Wi-Fi was restored afterward.
This was a stationary bench check; physical range extension remains untested.

Once a network match starts, each participant stays bound to that match's host.
Joining a host also disables automatic hosting on that participant, including
when it loses Wi-Fi before receiving the start. To choose a different phone host
for a later game, use **Make this phone host** in the idle lobby.
Losing Wi-Fi or the gun connection, sitting out, leaving the round, and reopening
the player app cannot enable a separate phone-hosted game. The phone reconnects
to the original host, and keeps hosting, practice, and new-game controls locked
until the match ends or that host confirms its lobby is open again. A deliberate
handoff of the same running match remains supported.

If a round is stopped before its first 60 seconds of play have elapsed, the
next round can start without the usual 30-second between-game wait. Canceling
a countdown also permits an immediate retry. The normal synchronized start
countdown still runs; rounds lasting 60 seconds or longer retain the wait.

The lobby shows **Your previous game**: your kills, deaths, hits taken, mode,
team, and team kills (captures in CTF) when applicable. These local stats survive app restarts
and stay visible while preparing the next game. Leaving early labels them
**Stats at exit**. A canceled countdown does not replace the previous result.
Games played before installing this update cannot be recovered retroactively.

After actual play ends, this card also shows shots, confirmed gun-hit accuracy,
best kill and death streaks, and skill before/after the round. Skill uses the
existing smoothed cumulative kills/deaths team-balancing index (neutral 100),
not Elo. Host reports take precedence over local estimates; missing telemetry
is labeled partial and unavailable accuracy is never presented as zero percent.
At the end deadline, shooting stops immediately. Phones keep the control
connection open for up to two seconds to receive the final host summary; a
missing summary falls back to clearly labeled local statistics.
The laptop game master can open **Review last round** for a player-lane timeline,
event filters, the final statistics, and a downloadable JSON report. Reports
survive lobby resets and are saved locally; they do not require GPS or a cloud
service. See [laptop-host/README.md](laptop-host/README.md) for report storage.

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
must run this protocol-31 build. A phone arriving during an active game offers
team-QR admission or waits for the next round instead of creating a separate lobby.

Network games use TCP for the lobby and an NTP-style exchange of monotonic-clock
timestamps to synchronize a start deadline. The app never changes a phone's
system clock and does not use a public NTP server.

The lobby also shares a fixed GPS origin, chosen from the host's fresh fix or
its first player's fresh fix. Positions use north/east offsets in 2.5 cm units
across a 1,638.4 m square (about 1.02 miles), centered on that origin. The
origin stays fixed during the match and host handoff. Each update refers to
the origin, so packet loss cannot accumulate position drift. Before an origin
is available, or outside that square, TCP carries full absolute coordinates;
the UDP record omits the unrepresentable GPS fix without changing combat data.
This is encoding resolution, not improved GPS measurement accuracy. Invalid
fixes, including (0, 0), never establish an origin or a map marker. An offset
of (0, 0) is valid and means the player's position equals the real origin.
Stationary players refresh their position every five seconds while GPS samples
remain fresh. TCP updates retain the measurement's age, so relaying a position
does not turn an old fix into a new one. Expired fixes are removed from the map.

- Lobby join immediately begins a 12-exchange clock-calibration burst before a
  start is accepted.
- The host offset runs against the phone's monotonic clock, independently of
  the game screen and changes to the phone's date/time. Background refreshes
  run every 30 seconds while connected and keep the committed offset usable
  until a complete new batch arrives. Automatic reconnects can reuse a recent
  offset after one low-delay probe confirms the same host clock epoch and
  agrees with the saved estimate. A different host epoch, transport, stale
  estimate (over five minutes), or clock disagreement requires all 12 samples.
  Explicit Leave clears the saved offset.
- Normal round completion returns to the same lobby without closing the host
  connection or discarding clock calibration. Final scores and personal reports
  arrive before the end event; the next round uses a fresh token and scores.
  Explicit Leave and host shutdown still close the connection.
- During initial calibration, the host sends the first lobby snapshot and
  defers repeated roster/settings updates until that phone is synchronized.
  Clock-ready broadcasts are grouped briefly so 16-32 phones finishing together
  do not flood each other's clock replies. Each connection seeds GPS once in
  its clock probes; regular position updates continue separately.
- Native Wi-Fi discards exchanges above 80 ms round-trip delay; relay sockets
  allow up to 800 ms. Both use the lowest-delay valid sample, bounding network
  timing uncertainty to 40 ms per native phone or 400 ms per relayed phone.
- The host sends one shared start deadline and every phone shows a countdown to
  it, normally 23 seconds away. The voice counts "10 Mississippi" through
  "0 Mississippi" every two seconds, then says "All out of Mississippi's."
  The count takes 20 seconds, followed by a three-second closing slot. The
  translated closing line is prepared and timed to end at the shared 23-second
  play deadline, with trailing audio silence removed and no added pause
  afterward. Solo starts use the same timing. Timed
  games also use a shared end deadline. If speech preparation fails, gameplay
  still starts on time.
- Both connected guns reload at the beginning of that countdown, including
  the boss's second gun. A successfully prepared gun is not reloaded again at
  the play deadline. Failed writes are retried during the countdown, and a
  reconnect prepares only that gun. The hardware refill also unlocks its
  trigger, so early pulls can consume ammo, but hits, firing announcements,
  and scoring do not count until the round starts. Death respawns still refill
  at the respawn point.
- The network-offset target is below 50 ms on a healthy native Wi-Fi connection.
  Bluetooth fallback uses the larger bound above. Wi-Fi
  congestion, device suspension, and normal UI scheduling can still affect the
  moment a device visibly reacts.
- All participants must use network protocol 31 (this build). Older protocol
  versions are rejected rather than starting an incompatible game.

During every network game, each phone sends one subnet-directed IPv4 state
broadcast per second instead of one unicast packet per player. The fixed
1,264-byte protocol-31 snapshot fits 32 player records below the 1,472-byte UDP
payload ceiling and avoids normal IPv4 fragmentation. Each record carries
cumulative score/deaths,
health, shield, shots, player state, grenade pairing, a deduplicated important
event, and two signed 16-bit GPS offsets with 2.5 cm encoding resolution. The
48-byte header carries the agreed origin so every snapshot is independently
decodable. Everyone caches
the newer player rows they hear. The host still publishes an authoritative tick
every second; after a missing tick's 20% grace window (200 ms), a phone fills
only newer portions of that tick from its overheard cache. Late or stale data
cannot roll state back. Names, addresses, and other bulky mappings remain in
the TCP roster channel. Android holds the high-performance Wi-Fi lock during gameplay;
the multicast lock also stays active during lobby discovery so idle phones can
hear the shared host.

QR power-ups are off by default. Before starting a game, the lobby host can
choose Off or 2 through 8 different codes with the Power-ups control. All phones
receive the host's choice. During play, Scan power-up opens a small camera window
without replacing the game screen. Print eight QR codes with these exact text
payloads: `SIMPLECOIL:POWERUP:1` through `SIMPLECOIL:POWERUP:8`. A code counts
only once per life; team respawn codes never count. Every configured number of
different codes unlocks a spinning mystery box with original arcade sounds and
a spoken reward. Death clears progress and personal buffs, allowing the codes to
be collected again. With power-ups off, this gameplay scanner stays closed;
the full-screen team QR scanner still opens after death when QR respawns are enabled.

The host chooses the reward from current match scores, not prior player history.
Teams behind by 1-4 points get twice the normal weight for EMP, team shield,
team heal, enemy-team damage, invulnerability, and airstrike; a gap of 5+ gives four times
the weight. It is still random, not a guaranteed comeback reward. CTF uses flag
captures. Personal rewards retain their base weights. Free-for-all excludes
teammate-only shields/healing.

Scan effort also adjusts to the live score gap: every five points behind the
leading opponent reduces the required scans by one, down to a minimum of two.
For example, a configured six scans becomes five when five points behind,
four when ten points behind, and two when twenty points behind. Catching up
restores the configured requirement. Progress is retained while the requirement
changes, but codes already spent on a reward cannot be spent again this life.

| Reward | Effect |
| --- | --- |
| EMP | Opposing blasters and game displays disrupted for 10 seconds; phone/network remain working |
| Player shield boost | 50% extra shields as a disposable buffer for 15 seconds |
| Team shield boost | Same buffer for living teammates for 15 seconds |
| Full auto | Temporary full-auto permission for 15 seconds, including tournament play |
| Full auto / 90 rounds | Full auto and a 90-round magazine for 15 seconds, then restore normal limits |
| Double damage | Twice normal shot damage for 15 seconds |
| Heal team | Restore every living teammate's health; does not respawn dead players |
| Damage enemy team | Hit living opponents for 25% of normal health, absorbed by shields first |
| Invulnerability | Ignore incoming damage for 15 seconds |
| Airstrike | Eliminate living opponents confirmed stationary by good GPS for at least 20 seconds; all shields are consumed, existing invulnerability/protected-zombie rules still apply |

Airstrike is a rare random reward with a base weight of two tickets, like
invulnerability. Each target phone checks its own unrounded GPS history when
the live grant arrives: continuous satellite fixes must report positive accuracy
of 5 meters or better and remain no more than 10 seconds old. Both observed
time and genuinely advancing measurements must span 20 seconds. Meaningful
movement restarts that window; tiny jitter and repeated cached fixes cannot
establish or renew stationary evidence. Missing, disabled, poor, or stale GPS
spares the player, so indoor players are not guessed to be stationary. Trigger
pulls do not protect a stationary player. The normal elimination, score-credit,
and respawn paths apply. This uses the existing power-up QR codes and adds no
GPS quality fields to the gameplay UDP packet.

Rare grants and bounded recovery snapshots use reliable TCP without increasing
the gameplay UDP packet size. Recovery retains 128 grants, enough for all 32
players to unlock four rewards in one life. Compact recipient masks and life
counters keep even the largest recovery message below the 60 KB TCP budget.
The retry queue also holds a full 128-reward burst, including instant effects.
Recovery history is bounded to the most recent 128 records; unusually rapid
unlocks across multiple respawns can exceed that history, while effects already
applied still honor their original expiry.
Round tokens, per-life scan masks, grant IDs, and
original expiry times prevent stale or duplicated rewards. Instant healing,
damage, and airstrikes are not replayed from recovery snapshots. Everyone must
update to the protocol-31 build before joining the same match; older clients
cannot safely participate in airstrike rounds.

Combat feedback does not wait for that one-second state cadence. `HIT`, `OUT`,
`ALREADY DEAD`, and elimination use a 32-byte subnet broadcast that every phone
can overhear. Only the addressed phone returns a 32-byte unicast ACK, avoiding
an ACK storm with 32 players. If needed, retries at 20 ms and 60 ms are unicast
only to that target and stop as soon as its ACK arrives, for a hard ceiling of
three sends; duplicate events are ACKed but applied only once. Combat uses its
own bounded sender so a hit cannot sit behind a full 32-phone authority
fan-out. Untargeted shot feedback is sent once, while the cumulative one-second
snapshots remain the final repair path for a completely missed combat burst.

Movement voice reminders begin after 40 seconds of inactivity, then repeat
every 10 seconds. With GPS enabled and a satellite fix no more than 10 seconds
old reporting positive accuracy of 5 meters or better, firing either blaster
does not postpone them. Movement must cover at least 3 meters and the combined
reported uncertainty of the anchor and current fixes; small jitters and cached
or older measurements do not count. When GPS is poor, missing, stale,
network-derived, or disabled, the existing trigger-based behavior remains, including
empty pulls and either boss gun. Acquiring or losing good GPS starts one new
40-second grace period. Reload and respawn instructions keep voice priority;
death, pausing, and leaving the game cancel movement reminders.

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
30-shot magazines, a 1.5-second reload, one damage per gun hit, recoil enabled,
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

Balanced Random is an optional two-team assignment method on phone and laptop hosts.
Everyone joins the lobby, then the host presses **Start** to assign teams using
the kills and deaths saved on each player's phone from previous network games.
The initial split and an explicit rebalance differ by no more than two players. Player IDs no longer imply a
team in this mode, and lobby joins can take any free ID; each phone displays its
assigned team. With **QR Check-in**,
every player, including a playing phone host, scans the printed Team 1 or Team 2
respawn QR that matches their assignment. The shared 23-second countdown begins
after the final check-in, or after a 90-second check-in timeout if someone has
not scanned. Everyone keeps their assigned team when the timeout starts the game.
**No QR** starts that countdown as soon as teams are ready after Start. Names can be
edited in the lobby; team assignment preserves the selected Tournament or Classic weapon rules. Clearing an app's data
also clears that phone's past-match totals.

Teams stay the same between rounds by default. Membership follows each player's
identity when phones reconnect, even if their player numbers change. New arrivals
join a smaller/weaker team without moving existing players; departures do not
trigger a new split. Uneven departures can therefore leave uneven teams until
the host chooses **Rebalance teams** in the phone or laptop lobby.

Rebalance uses the latest saved match stats, clears QR check-ins, and never
starts a game by itself. Press **Start** afterward. QR mode opens a fresh
90-second check-in window each round; pressing Start again while waiting does
not erase scans or restart that timeout. Rebalance is unavailable during the
countdown or gameplay.

While a QR check-in is pending, the phone repeats the assigned team number in
its spoken scan reminder. Respawn QR reminders also say the player's team.

Choose each player's desired team/ID before joining and confirm the displayed
team before starting. If an ID conflicts, a protocol-31 host automatically
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
by default. Initial game-start countdowns are independently set to 23 seconds.
The dedicated-phone host has the same respawn-mode control; the laptop uses
`./laptop-host/run.sh --timer-respawn` (or `--qr-respawn` for the default).
CTF flag/base scans, optional power-ups, and optional QR team check-in remain
separate features; timer-only disables the respawn scanner, not those objectives.

With QR respawns enabled, network two-team and four-team games use team QR checkpoints after a player is
eliminated. Initial game starts still use the 23-second countdown. On later deaths,
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
corner during play, with six decimal places from the phone's local location
fix. The extra displayed precision does not improve the sensor's accuracy or
change the compact network packets. The readout shows **GPS Off** when match location sharing
is disabled, or **GPS acquiring...** until a recent location arrives. A fix
expires after 30 seconds without an update, including after GPS is disabled and
re-enabled. The app accepts Android's GPS and network-location providers; the coordinate readout
can therefore contain a network-based fix, not necessarily a satellite fix.
The placeholder pair `(0,0)` is rejected. A zero latitude or longitude on its
own is valid, so locations on the equator or prime meridian still work.

The bundled map library's hidden-map startup worker is explicitly retired when
its view is destroyed before a rendering surface exists. Optional legacy map
analytics are suppressed before controller creation because those requests are
not owned or cancelled by the controller. UDP service teardown also prevents a
sender from reopening its outbound socket after destruction.

## Build

Install JDK 17 and Android SDK Platform 34, then set `JAVA_HOME` and
`ANDROID_HOME` as appropriate for your machine.

```bash
./gradlew assembleDebug
```

The launcher name defaults to `LAZER GUN RUN`. Override it
for a particular build with the `appName` Gradle property. The laptop’s
**Players & Event Mode** page can change the event name displayed inside the
app at runtime, while the launcher name stays fixed:

```bash
./gradlew assembleDebug -PappName="Your Event Name"
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Printable QR posters

The [1.52 release](https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/tag/1.52)
includes a 14-page QR PDF in US Letter portrait format (8.5 x 11 in),
plus a ZIP with the complete PDF, individual pages, and a payload index.
Each tall sheet has a descriptive header directly above one large centered
black-and-white QR code, and a small footer with the release version and page
number. All team, flag, and power-up titles sit close below their QR codes.
Open the PDF and print; it requests actual-size printing.
PDF bookmarks and the ZIP index also identify each page.

- Pages 1-4: Teams 1-4. The same codes serve team selection, joining,
  Balanced Random check-in, respawn, and CTF home bases.
- Pages 5-6: Team 1 and Team 2 CTF flags.
- Pages 7-14: Power-UP 1-8. These identify distinct checkpoints,
  not fixed rewards; each can unlock any eligible random power-up.

The print pack starts with Team 1 and omits the app-download cover.
The release print pack contains no gun-specific pairing codes.
Team codes do not contain a host address or Wi-Fi
credentials; connect to the game network first.

To regenerate the PDF, use a virtual environment with `reportlab` and `qrcode`
installed, then run:

```sh
python tools/build_release_qrs.py --version 1.52 --portrait --skip-download
```

This generates the 14 in-game codes, one per sheet.
The generator writes `output/pdf/LAZER-GUN-RUN-1.52-QR-Codes-US-Letter.pdf`
with vector QR graphics, titles close below each code, descriptive headers close
above, and small footers.
Use `--qr-only` instead for plain landscape QR sheets, or omit both options for
the original instructional layout.
Omit `--skip-download` to include the app-download cover. That QR expects the
release asset `LAZER-GUN-RUN-1.52.apk`; use `--apk-name` for a different filename.

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
3. Pair each phone with its supported BLE gun and set the player name or team.
4. Let Wi-Fi connect automatically, or use the Wi-Fi button if needed. Verify
   all phones are on the same network and the host has a local IPv4 address.
   The lobby joins automatically.
5. Wait for clock synchronization before pressing Start; if prompted, wait a
   moment and try again.

For development and contribution history, see the original
[SimpleCoil repository](https://github.com/Dees-Troy/SimpleCoil).
