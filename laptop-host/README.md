# SimpleCoil laptop host

`LaptopHost` is a dedicated-host replacement for the Android host screen. It
keeps the game authority and live display on a laptop; players still use the
Android app and their BLE laser-tag hardware.

It speaks the protocol-31 TCP lobby, clock synchronization, GPS,
score, respawn, grenade-pairing, and UDP discovery protocols. No Node, Python,
database, cloud service, or internet connection is required.
Use this host with the 1.58 phone app; the network protocol remains 31.

The map display bundles Leaflet and serves raster map tiles from the laptop.
Nothing is fetched from the internet while a game is running. Player markers,
trails, hit lines, and game-master controls continue to work even when no base
map tiles have been installed.

When a gun supplies a valid grenade ID in its damage report, the receiving
phone forwards that hit over Wi-Fi. The map shows a red circle at each hit player's recent GPS
position for eight seconds. When four or more distinct players with recent GPS
positions are hit by the same grenade within two seconds, it also shows a red
dot at their geographic center. This dot estimates the middle of the hit
players, not the grenade's measured location. Hits without a recent GPS fix
are counted but cannot be placed on the map.
The tested grenades omit their ID from damage reports, so those hits damage
everyone in range but cannot currently produce these grenade map markers.

During a round, the laptop continues sending an authoritative state tick once
per second by broadcast and per-phone unicast. Phones also broadcast their
fixed-size state snapshots to the whole local network once per second. If a
phone misses a host tick, it waits the 20% drift allowance (200 ms) and then
fills only newer rows from the player snapshots it overheard; delayed data
never replaces newer state.

Latency-sensitive combat remains phone-to-phone: every phone can overhear each
32-byte initial event, only its target sends a unicast ACK, and any 20 ms and
60 ms retries are unicast only to that target. The laptop also records valid
overheard events and includes the newest one in its next authority tick,
providing a slower final repair without adding per-player ACK traffic.

## Run it

Install JDK 17, connect the laptop and phones to the same Wi-Fi network, then
run this from the repository root:

```bash
./laptop-host/run.sh
```

## After-round review

After a round finishes, **Review last round** appears on the launcher, map, and
leaderboard. It opens `/round-report`, with final per-player accuracy, best kill
and death streaks, and the skill index before and after the round. Accuracy is
opponent-confirmed gun IR hits divided by gun shots; grenade damage is excluded,
multiple-target hits are capped at 100%, and zero shots shows N/A. A kill streak
ends on a death; a death streak ends on a kill. Skill uses the existing cumulative
kills/deaths balancing index (0-200, neutral 100), not Elo; different game modes
are not normalized. This does not automatically rebalance or move any players.

The review includes an interactive SVG timeline with a time axis and one lane
per player. Filter shots, confirmed IR hits, kills/deaths, respawns, power-ups,
flag captures, grenade hits, and joins/disconnects. Hover or focus an event for
details. Time zero is actual GO, not the countdown, and timestamps are when the
host received reports, not exact physical trigger times. No GPS fix is needed.
Shot activity is grouped into one-second buckets. At most 2,000 shot buckets
and 12,000 total events are retained, plus the final end marker; the review
clearly reports any omitted batches. Totals keep updating after this limit.
Older phones, telemetry gaps, and disconnections mark the data as incomplete;
unobserved hits or streaks are not invented.

Reports are frozen before phones leave the round, and remain available in the
retained lobby or when a new round starts. Normal game-over keeps each phone's
connection, clock calibration, identity, gun readiness and participation. The
next Start resets round scores and authority without another join or clock burst;
the usual cooldown and gun-readiness checks still apply. Explicit Quit or host
shutdown closes the connection. A cancelled countdown
does not replace the latest completed round. Stable player identities preserve
statistics across reconnections and keep a new occupant of the same player ID
separate from its former occupant. The host sends the frozen final scoreboard,
then each upgraded phone's small personal report, then the end-game message in
one ordered batch. This keeps the final score and win/loss announcement in sync;
the full timeline stays on the laptop.

Completed reports are saved as JSON under `laptop-host/reports` by default.
Use `--reports /path/to/directory` to choose another app-owned directory. Existing
files are never overwritten or deleted. The latest ten reports are selectable
and reload on host restart; older saved reports remain retrievable by their
round token. JSON downloads contain names and game statistics, not GPS tracks.
The read-only `/api/reports` lists report summaries, and
`/api/report?roundtoken=UUID` retrieves one full report. The frequently refreshed
`/api/state` does not include the full timeline.

For Balanced Random teams, start the laptop with `--balanced-qr` to require
each player to scan the assigned Team 1 or Team 2 QR, or `--balanced-no-qr` to
start the 23-second countdown as soon as teams are assigned. The laptop uses
the prior kills and deaths reported by each phone and keeps team sizes within
two players. The dashboard Start button assigns the first teams; QR mode starts
automatically after the last check-in. These options also work with `--takeover`.
If a player does not scan, QR mode starts the same 23-second countdown after
90 seconds; that player stays on their assigned team.
Teams stay together between games, including when phones reconnect with new
player IDs. New players join the smaller team (or the weaker team when sizes
are equal) without moving anyone already assigned. Use **Rebalance teams** on
the map dashboard to make a fresh stats-based split in the lobby. Rebalancing
resets team QR check-ins but does not start a game; press Start when ready.
Each QR round requires fresh check-ins and its own 90-second timeout.

Ordinary rounds also start with a shared 23-second countdown. An explicit
`--start-delay 1` through `--start-delay 1000` overrides that duration for
ordinary rounds; Balanced Random rounds always use 23 seconds. Phones announce
the countdown from "10 Mississippi" to "0 Mississippi" every two seconds over
the first 20 seconds. The final three seconds leave time for
"All out of Mississippi's." before play starts. "May the odds be ever in
your favor." follows as play starts.

To enable gameplay QR power-ups on the laptop host, add `--powerup-qr 2` through
`--powerup-qr 8` (off by default). Print eight QR codes containing the text
`SIMPLECOIL:POWERUP:1` through `SIMPLECOIL:POWERUP:8`; each phone must scan
different codes to earn each random reward. Progress resets on death. The host
validates each scan set and grants a reward only once per completed set during
active play; duplicate reports, stale lives, and reconnects cannot award it again.
Teams behind in the current score receive stronger rewards more often.
For every five points a team trails the leading opponent, its scan requirement
drops by one from the configured base, down to a minimum of two. For example,
an eight-code game requires seven codes when five points behind and six when
ten points behind. Codes already spent on a reward cannot be reused when the
requirement changes; only unspent progress counts toward another reward.

Rewards are EMP (10 seconds); 50% extra shields for the player or their team;
automatic fire; automatic fire with a 90-round magazine; double damage;
full-health healing for the team; a burst damaging every living enemy for 25%
of normal health; invulnerability; or a rare Airstrike. Airstrike eliminates
living opponents confirmed stationary for at least 20 seconds by satellite GPS
fixes no more than 10 seconds old with positive reported accuracy of 5 meters
or better. Moving players and players without good GPS are exempt. Shields do
not save stationary victims; existing invulnerability and the protected
original zombie rules still apply. Temporary buffs last 15 seconds unless
otherwise noted. The host sends one grant to every phone, so team effects and
attacker damage buffs agree across the game. Reconnecting phones receive the
remaining active grants without restarting their timers.

For Boss Mode, run `./laptop-host/run.sh --boss`. Player 1 is the boss and all
other player IDs are hunters. Boss scaling freezes when the round starts. Hunters
have 2 health, 3 shields, 30 rounds, and locked single-shot fire. The boss has
5 health and 10 shields plus 1 health and 2 shields for every hunter, carries
120 rounds, starts in automatic, and may switch between single, burst, and
automatic fire. Outdoor range automatically enables the wide cone on both
boss guns; Indoor range remains reduced-power with no cone. Hunters keep
their no-cone profile. Health and shields do not regenerate in this mode. In normal
tournament games, health starts returning one point per second after 30 seconds
without another damaging hit.

For Capture the Flag, run `./laptop-host/run.sh --ctf`. Print flag QRs with
`SIMPLECOIL:FLAG:1` and `SIMPLECOIL:FLAG:2`; the number is the owning team.
Players take the opposing flag and score by scanning their own existing
`SIMPLECOIL:RESPAWN:1` or `SIMPLECOIL:RESPAWN:2` base QR.

For Infection, run `./laptop-host/run.sh --infection`. Player 1 is the original
zombie. Killed survivors scan the Team 1 respawn QR to join the zombie team;
recruited zombies use the same QR after later deaths. Players can also wait
the three-minute respawn timer; with `--timer-respawn`, no camera opens and
killed survivors convert after the ten-second timer. Hits drain the original
zombie's ammunition until that player earns two kills. After the second kill,
the original zombie can die and scan Team 1 or wait the timer to return. That player's reload
takes 20% of normal only until the original zombie's first death, then returns
to normal speed. The host ends the round when all connected survivors have
converted, or when conversions leave one final uninfected player. Infection is
always a five-minute round; players still alive and uninfected at the deadline
win.

Phones running this release automatically discover and join this host on the same
Wi-Fi/AP, even before their guns are paired. The laptop announces its lobby
every two seconds and takes priority over idle phone hosts. Automatic player
assignment uses free IDs and balances ordinary teams. The host waits until
all participating players have a connected gun and synchronized clock before
starting. The map's lobby roster shows each player's readiness and has a
**Sit out / Play** control. Spare phones can stay in the lobby without blocking
the round; they receive no start command and do not affect scoring, teams, or
Boss health. Change participation before starting, with no extra Ready button
needed on the phones.

During clock calibration, each phone receives its initial lobby settings once.
Repeated roster, settings and GPS snapshots wait until that phone acknowledges
its clock samples, then it receives the current state. Clock-ready broadcasts
are grouped for 75 ms so a large lobby finishing together does not flood the
phones with complete roster copies. Active-round updates keep their normal order.

The laptop starts in **Event Mode**: the operator starts and ends rounds, edits
players, and controls the event. **End game** ends the round directly. Phone
start commands and name/rule edits cannot override the operator. Use
`--no-event-mode` for the ordinary phone controls and two-player end-approval
workflow. Use protocol-31 phones with this host and update the laptop and every
phone together; the previous protocol is not compatible. This build also
includes the Event Mode, kiosk, battery, and player-transfer controls from 1.51.

During the initial countdown, **Cancel countdown** lets the laptop host return
everyone to the lobby without player approvals, in either event mode. The
connections and clock sync stay ready for an immediate restart, and a canceled
countdown does not replace the previous round's report.
Confirmations apply only to the round and action shown when they opened. An
old confirmation cannot cancel a replacement round, and **Cancel countdown**
expires at the scheduled start instead of ending live play or requesting votes.
Reload existing dashboard tabs after updating the host. The local `/api/end`
control now requires the displayed `roundtoken` and an explicit `action`:
`cancel-countdown`, `end-round` (live Event Mode), or `request-end-vote`
(live play with `--no-event-mode`). Unscoped or expired requests are rejected.

With `--no-event-mode`, after the scheduled start time the map's **End game**
button asks participating players to approve an early end. Two different
players must agree; the non-playing
laptop operator and sitting-out phones do not count. The button shows the
approval count. Players can withdraw approval or confirm **Leave game** on
their phone at any time; leaving does not end the shared round. Timer and
victory-condition endings remain automatic.

Phone scoreboard dialogs open only after a player-list request. Host edits,
reconnections, and round changes do not open that dialog. Final results still
update each phone's round summary. Scoreboards show actual players without
empty player slots.

Nearby phone relays prepare automatically after admission.
Version 1.51 prepares one Bluetooth buddy when a phone's Wi-Fi RSSI reaches
-67 dBm or weaker, sampling once per second. Signal recovery at -62 dBm stops
new preparation attempts, while existing links remain available. Wi-Fi stays
primary. Association loss triggers preparation on the next check; expired host
heartbeats no longer add a five-second wait before searching other buddies.
Cold Bluetooth setup still has a 15-second cancellation limit.
Three stationary 1.51 Wi-Fi-off cuts returned confirmed bidirectional traffic
in 6.1-8.4 seconds from the radio-off command, with 18 fresh battery checks and
no further game reconnects during the steady windows. Wi-Fi was restored after
each cut. RSSI thresholds have automated coverage; fading signal and outdoor
range remain untested.
Keep one or more phones within reach of the laptop's Wi-Fi. Phones beyond that
coverage can forward the same game's UDP traffic and TCP host controls through
nearby phones over Bluetooth Classic. A nearby phone forwards Bluetooth traffic
over its working Wi-Fi connection to the laptop, and relays replies back. Enable
Bluetooth on all phones. Version 1.40 removes automatic Wi-Fi Direct invitations;
the tested LG Android 5.1.1 phones need no pairing confirmation for this fallback.
The laptop also disables Wi-Fi Direct preparation on admitted 1.39 phones.
The laptop listens for game relays on TCP 17514 (the configured game TCP port + 4).
Phones retain their admitted logical addresses and identities across radio loss.
The host reserves a disconnected relay player's seat while it reconnects; an
explicit Leave releases that seat. Disconnected phones are marked offline.
The private player-control API reports `viaRelay` for a relayed control connection.
A continuous chain back to the laptop is required; an isolated group cannot
reach a host outside every available radio path.
An admitted phone keeps its fallback membership after losing its lobby TCP
connection, so it can return through a neighbor before the round starts.
The live version 1.50 bench kept one phone's Wi-Fi radio off for 197.6 seconds,
with all 20 host refresh controls received and 20 fresh gun reports returned.
Clock readiness stayed true and the 182-second stability window had no game
reconnects. Wi-Fi was restored afterward. Physical range remains untested.
Version 1.50 removes the five-second virtual read timeout and uses bounded
relay queues that coalesce retries instead of disconnecting under congestion.
Unicast traffic prefers a fresh single path and stops at its destination. Stale
or full preferred paths can fail over through another neighbor. Broadcasts
continue to propagate, and restarted apps use fresh packet sequence ranges.
Returning phones retire older roster seats for the same saved player, keeping
the current relay address authoritative. Phones retain the host-assigned slot
when a gun reports its previous ID. Bluetooth setup allows 15 seconds and
cancels Classic discovery before dialing.
The host queues writes independently for each phone, so a slow relay cannot
delay other phones' heartbeats or dashboard actions. Terminal messages flush
before closing, and clock replies are timestamped by their writer.
Clock probes reach the original host. Relay clients accept network round trips
up to 800 ms, require twelve samples and use the least-delayed estimate; native
Wi-Fi retains its 80 ms limit. Relay network timing uncertainty is bounded to
400 ms per phone, independently of UI scheduling or gun latency.

A round stopped before 60 seconds of actual play can be restarted without
the usual 30-second between-game wait. A canceled countdown can also be
restarted immediately. The synchronized countdown remains in place for the
next round; a round lasting 60 seconds or longer still requires the wait.

Outside Event Mode, during a running match, a new or returning phone can scan its team's base QR
to join. This explicit QR admission also works with `--no-late-join`, which
still blocks ordinary discovery/manual late joins. Returning phones preserve
their round counters and current team (including infection), while new players
start at zero. Vacated slots can be reused without accepting the previous
occupant's delayed combat packets. Joining does not reset the round deadline
or rescale the boss.

The launcher also prints each usable laptop IPv4 address. If automatic
discovery is blocked, open **Equipment and advanced options > Advanced: enter
host address** on the phone. The app's UDP join step and TCP lobby connection
then happen automatically. Disable AP client isolation and allow the laptop through its firewall
on TCP ports `17510` and `17514`, and UDP port `17500` if prompted.

Idle phone lobbies migrate automatically to the laptop, including manually
selected phone hosts, regardless of their hosting age. A laptop always has
priority over phones. While the laptop is reachable, phones disable both
manual hosting and automatic host election. Without a laptop, the phone
lobby's **Make this phone host** control selects a phone for everyone else to
join. A newer explicit phone selection replaces an older one.

For an extra takeover announcement, start the laptop host with
`--takeover` before the game starts. Updated phones in that idle lobby
automatically reconnect to the laptop, which then becomes the game authority:

```bash
./laptop-host/run.sh --takeover
```

The laptop's selected rules become authoritative. If a phone-hosted round is
already in progress, it finishes on that host first; the phones move to the
laptop afterward. This also applies to `--takeover`. Among multiple laptops,
phones prefer the oldest host, with lowest IP breaking near-simultaneous ties.
Discovery leases and stalled lobby joins expire after 15 seconds; failed
endpoints are skipped for 30 seconds. Outside Event Mode, phones can fall back to automatic hosting if the laptop
disappears. Event phones keep the organizer restrictions while disconnected.

The launcher opens a local landing page at `http://127.0.0.1:17511/`. Use its
buttons to open the two independent display windows:

- **Tactical GPS Map** - the laptop game master sees all players by team, with recent movement
  trails and short laser lines for verified hits and eliminations. The laser
  endpoints are captured when the hit is confirmed, so a later GPS movement
  does not shift the trace. It also lists one **Game Master Respawn** button
  for each eligible eliminated player.
- **Leaderboard** - KILLS, HITS, SHOTS, and accuracy (`hits / shots`) update
  live. The map window also contains Start, End Game, and Game Master Respawn
  controls.

The dashboard binds to `127.0.0.1` by default, so only the laptop can control
the game. To show it on another trusted display, use
`--dashboard-bind 0.0.0.0`; do not expose that option to an untrusted network,
because the local dashboard intentionally has no login prompt.

## Players, saved profiles, and Event Mode

Open **Players & Event Mode** from the host home page or **Players** on the map
(`http://127.0.0.1:17511/players`). Select a connected player or a saved profile.
The laptop can edit names, teams, participation, health, ammunition, reload
behavior/time, respawn time, damage, lives, firing modes, and firing range.
Names can change during a round; teams, participation, gameplay profiles,
and phone transfers change in the lobby. Boss, CTF, and Infection retain their
mode-specific teams and weapon rules.

Saving a gameplay profile enables custom host rules so phones apply the profile
rather than replacing it with tournament defaults. **Restore default gameplay**
removes that player's override. Once every override has been removed, ordinary
tournament rules apply again. Game Master respawns respect the player's lives
limit. Custom respawn times require the updated phone app.

Profiles and recorded game statistics are saved privately on the laptop at
`~/.local/share/simplecoil/players.json`, using atomic writes. They survive phone
disconnects and host restarts. To replace a phone, connect the replacement to the
lobby, select the saved player, and use **Move to another phone**. The profile,
team, settings, and history move together; the previous phone is unassigned.
Transfers require a current roster selection and cannot happen during a round.

The roster and editor show the phone's battery percentage, charging state,
and battery condition for each connected blaster (including both Boss guns).
Blaster levels are estimates, with voltage shown only for calibrated hardware.
Phones report every five seconds; **Check batteries now** requests a fresh report.
Disconnected or old readings are marked stale; an unknown reading is not zero.

**Lowest gun voltage per round** saves each calibrated gun’s minimum displayed
estimate with its Bluetooth ID and player. Values at or below 4.5 V are flagged
for a battery check in the roster and player editor. The minimum survives gun
reconnects and stays low if batteries are replaced mid-round; the next round
starts a new record. Both Boss guns and guns swapped during a round are tracked
independently. Unknown readings and uncalibrated rifles cannot create a voltage
record. These are minima of the existing smoothed estimate, not raw recoil dips.
Phones retain their latest 20 rounds and resend them when connected. The laptop
keeps 100 rounds per player in its private player directory, including after a
phone transfer or host restart. A replacement phone’s old player readings are
not assigned to its new player. Update phones to 1.37 to send round history.

Expand **Event name, Event Mode & Kiosk Mode** to rename the event and control
phone restrictions. The launcher is **LAZER GUN RUN**; the event name changes
inside the phone app and on the host displays. The event settings and organizer
PIN are kept privately in `~/.config/simplecoil/event.properties`.
New event settings generate a two-digit organizer PIN. Existing configured PINs
stay unchanged; choose a new two-to-six-digit PIN in event settings to shorten
one, including leading zeros. Previously configured seven- or eight-digit PINs
remain valid after a restart and may be kept unchanged while editing other
event settings; replacements must use two to six digits. Update phones to
1.56 before choosing a PIN shorter than six digits so offline unlock accepts it.

Kiosk Mode uses Android lock task mode on provisioned device-owner phones.
Ordinary phones show Android's screen-pinning confirmation and retain the
system unpin gesture. The host reports **locked**, **screen pinned**, or
**unlocked** separately. Release one phone by clearing its pinning checkbox,
or turn off Kiosk Mode / Event Mode and apply to release everyone. Restrictions
survive a lost Wi-Fi connection and app restart. The organizer PIN also unlocks
offline, using the lobby's **Organizer unlock** button or Back during play.
Three incorrect attempts impose a one-minute retry delay.

See the host's **Phone setup** page (`/event-setup`) and
[Android's lock task guide](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).
For a prepared, event-owned phone with USB debugging enabled and no personal
accounts/device owner, install the app and provision its optional admin component:

```sh
adb shell dpm set-device-owner com.simplecoil.simplecoil/.EventDeviceAdminReceiver
```

This setup does not erase or remove accounts from phones. Android may reject
provisioning on a phone that is already set up. Enabling an ordinary device-admin
permission alone does not grant device-owner kiosk authority.

## Host the game Wi-Fi

### Phone updates from the laptop

Updated phones check their connected laptop host for app updates over the game
Wi-Fi. Only the laptop needs internet access to fetch the latest stable GitHub
release. It downloads one exact release APK in the background and verifies its
size and GitHub SHA-256 digest before offering either metadata or APK bytes.
Phones still verify the package, signing certificate, version, and digest before
asking Android to install it. Android's installation approval is still required.

The read-only phone service uses the existing map-tile port, normally `17512`:
`GET /api/app-update/latest` and `GET /api/app-update/apk/<asset-id>`. The metadata
keeps the original GitHub download URL; the phone derives the local APK route
from the verified numeric asset ID. There is no upload or arbitrary-file route.
An empty or warming cache returns `503`, so phones retry while the laptop works.

The verified cache survives host restarts and can serve phones with no internet
connection, including when the laptop is offline. It lives in
`~/.cache/simplecoil/phone-updates` and retains three immutable APKs, each at most
100 MiB. A refresh checks GitHub at most once per minute and preserves the last
good release if the network fails. Cache validation, upstream downloads, and LAN
APK transfers pause or abort during countdowns and running rounds. Two separate
update stream slots keep slow update downloads out of the game and dashboard
workers. Leave the host running in the lobby while updating phones.

For isolated deployments or tests, `simplecoil.phoneupdates.cache` overrides the
cache directory and `simplecoil.phoneupdates.enabled=false` disables background
update fetching. These are JVM system properties; they do not change game rules.

On Linux with NetworkManager, open **Wi-Fi hotspot** on the landing page or
**Wi-Fi** on the tactical map (`http://127.0.0.1:17511/wifi`). Choose an
AP-capable adapter, set the network name and 2.4 GHz channel, then
select **Start hotspot**. The default is an open network named
`Recoil-SimpleCoil`, with no password, so the Android app can find and join it
automatically. Keep the `Recoil` prefix so game phones recognize the network.
You can choose **WPA2 — password protected** under **Wi-Fi access** instead;
save its password once in each phone's Wi-Fi settings before opening the game.
The laptop's game
address is `10.42.0.1`; DHCP, DNS, and optional internet sharing are provided by
NetworkManager. Wi-Fi client isolation is disabled so phones can exchange
combat events directly. A second adapter can keep the laptop connected to the
internet.

**Stop hotspot** disconnects the phones and restores the adapter's previous
connection, when there was one. An unsuccessful start also attempts to restore
that connection. Edit the settings while the hotspot is running and select
**Apply changes** to stop and restart it with the new settings. Draft edits
remain in the form when status refreshes. Invalid settings are rejected before
the hotspot stops; if restarting fails, the host attempts to restore the
previous working hotspot. Apply also works during a game round and leaves the
game host running, while phones disconnect until they rejoin. Starting or
stopping the hotspot separately is blocked during countdowns and running
rounds. These controls remain restricted to this laptop even if the rest of
the dashboard is bound to all interfaces. Saved settings, including the
password, are private to the current user in
`~/.config/simplecoil/hotspot.properties`. The host does not start the hotspot
automatically at launch; the saved adapter selection is retained without
falling back to another adapter.
The NetworkManager hotspot profile is created in memory from those saved
settings, avoiding Ubuntu Netplan's profile rewrites when the SSID changes.
After a NetworkManager restart or reboot, use **Start hotspot** to recreate it.

### TP-Link TL-WN722N v2/v3 on Ubuntu 24.04

Check the hardware revision: the USB device `2357:010c` uses the RTL8188EUS
chipset. The stock `rtl8xxxu` driver on this computer's Ubuntu 6.8 kernel
reports no AP capability. The following optional setup uses the
[rtl8188eus driver](https://github.com/aircrack-ng/rtl8188eus) with AP support.
The included patch handles Ubuntu's backported monitor-channel callback,
corrects the driver's variable-length IE declaration for kernel bounds checks,
and gives this build its own DKMS version. It was built and tested on kernel
`6.8.0-147-generic` with Secure Boot disabled.

Install DKMS, the compiler tools, `bc`, and headers for the running kernel if
needed. Build as your normal user, from the repository root:

```bash
git clone https://github.com/aircrack-ng/rtl8188eus.git /tmp/simplecoil-rtl8188eus
git -C /tmp/simplecoil-rtl8188eus checkout af3bf004458f76b7aec33e9ba552cd382ed1f5c3
git -C /tmp/simplecoil-rtl8188eus apply "$PWD/tools/tlwn722n-ubuntu-compat.patch"
make -C /tmp/simplecoil-rtl8188eus -j4 KVER="$(uname -r)" KSRC="/lib/modules/$(uname -r)/build"
sudo ./tools/install-tlwn722n-driver.sh /tmp/simplecoil-rtl8188eus
```

Installing and loading a kernel driver requires administrator authentication.
The installer registers a root-owned source copy with DKMS for kernel updates
and prefers `8188eu` over `rtl8xxxu`; it leaves the Intel adapter alone. Stop
any connection using the TP-Link before installing, then select the TP-Link
in the game's Wi-Fi controls. To restore the stock driver, stop the hotspot
and run:

```bash
sudo ./tools/install-tlwn722n-driver.sh --remove
```

## Install offline map tiles

The host reads standard XYZ PNG tiles from this directory layout:

```text
laptop-host/maps/
  15/5234/12663.png
  16/10468/25326.png
```

The numbers are `zoom/x/y.png`. Put the game area's tiles in
`laptop-host/maps`, restart the host, and use **Fit players** after the phones
have GPS fixes. The map reports whether it found tiles and their zoom range.
Downloaded maps are ignored by Git because even a small high-detail area can
contain many large files.

To show the installed area before phones have GPS fixes, add an optional
`laptop-host/maps/map.json` alongside the zoom directories:

```json
{
  "center": [47.6221479, -122.1028626],
  "zoom": 18,
  "bounds": [[47.613, -122.117], [47.632, -122.089]],
  "attribution": "&copy; OpenStreetMap contributors",
  "version": "park-map-1"
}
```

Coordinates use `[latitude, longitude]`; bounds are southwest and northeast.
The display opens at the configured center and zoom, then fits players when
their first fresh GPS fixes arrive. Attribution appears on the map. Invalid
metadata is ignored, and the initial zoom stays within the installed tile range.
Change `version` when replacing existing tiles so the browser fetches the new
images instead of reusing its cached map.

Use **+** or the mouse wheel on the tactical map to magnify imagery up to 8×
beyond its native size. Extra zoom enlarges the existing pixels.

The laptop also exposes those tiles to joined phones on read-only TCP port
`17512`. The lobby tells each phone which tile port to use, so the native phone
map automatically requests `http://LAPTOP_IP:17512/tiles/{z}/{x}/{y}.png`.
Each phone keeps fetched tiles in its private app cache, separated by laptop IP
and port. Android may evict that cache when storage is low; the laptop remains
the source for any cache miss. Allow inbound TCP `17512` through the laptop
firewall along with the normal game ports.

Two compatible downloaders are:

- [offline-map-tile-downloader](https://github.com/Cyclenerd/offline-map-tile-downloader),
  an MIT-licensed tool with a browser area selector and rate limiting. It puts
  a map-style directory above the XYZ folders, so start SimpleCoil with the
  selected style directory, for example:

  ```bash
  ./laptop-host/run.sh --tiles /path/to/downloader/maps/OSM
  ```

- [map-tiles-downloader](https://github.com/tekk/map-tiles-downloader), the
  command-line tool originally considered for this host. Its bounding-box
  output can be written directly to the default directory:

  ```bash
  pipx install mt-downloader
  mt-downloader bbox SOUTH WEST NORTH EAST --max-zoom 18 -o laptop-host/maps
  ```

The latter tool uses a noncommercial license, so it is not bundled with this
Apache-licensed project. Both downloaders are optional preparation tools; the
SimpleCoil host itself serves the resulting tiles. Check the chosen map
provider's terms before downloading, select only the game area, and avoid
large high-zoom downloads from public community tile servers.

## Useful options

```bash
./laptop-host/run.sh --teams 2 --duration-minutes 20 --score-limit 50
./laptop-host/run.sh --no-late-join --no-browser
./laptop-host/run.sh --takeover
./laptop-host/run.sh --boss
./laptop-host/run.sh --timer-respawn
./laptop-host/run.sh --tiles /path/to/xyz-tiles
./laptop-host/run.sh --tile-port 17512
```

Run `./laptop-host/run.sh --help` for every option. Keep the default TCP and
UDP ports for stock SimpleCoil phones. Tournament rules are the default. The laptop player editor can enable custom
profiles for ordinary two-team events. `--tournament` remains accepted for
compatibility with old scripts.

## Respawn mode

Default QR team respawns allow a checkpoint scan or a three-minute fallback.
Add `--timer-respawn` for ten-second team respawns with the camera closed;
`--qr-respawn` restores the default QR-or-timer choice. A saved custom host
profile keeps its explicitly configured wait in either mode. The host
publishes this rule to every phone.
Game Master respawn remains available in either mode. CTF objectives and
optional power-up/team-check-in QRs are independent of respawn scanning.

## GPS and laser display

The Android app requests GPS and network-location fixes every 250 ms while GPS
mode is active. GPS remains the preferred source; the network provider can
produce a useful initial location while a phone is acquiring satellites. The
app forwards meaningful movement to a hosted game immediately. The laptop
forwards changed positions at up to four updates per second and sends periodic
complete snapshots to correct stale markers.

Protocol 31 shares a fixed lobby GPS origin alongside clock synchronization.
The first fresh player fix establishes the laptop's origin. In-area updates
use signed 16-bit north/east offsets in 2.5 cm units over a 1,638.4 m square
(about 1.02 miles). Every binary snapshot carries its origin and remains
independently decodable after packet loss. Fixes outside the square retain
full absolute coordinates over TCP. The host rejects invalid (0, 0) fixes
and mismatched relative origins; zero offsets at a valid origin are accepted.
The 2.5 cm figure describes encoding precision, not the phone's GPS accuracy.
Stationary phones refresh fresh fixes every five seconds. Position updates retain
the original measurement age; current map markers and new hit lines omit fixes
older than 15 seconds, while historical trails remain visible.

When a phone has a fresh GPS fix, SimpleCoil also uses its UTC timestamp as an
optional refinement for the shared countdown. It does not change the phone's
system clock; the existing monotonic round-trip clock sync remains the fallback
and final guardrail when GPS time is missing or disagrees. Joining the lobby
immediately starts a 12-probe calibration burst. Native Wi-Fi accepts samples up
to 80 ms round trip; relay sockets allow up to 800 ms. Choosing the least-delayed
valid sample bounds network timing uncertainty to 40 ms per native phone or
400 ms per relayed phone, before UI scheduling and hardware latency.

The offset continues against each phone's monotonic clock while its game screen
is in the background. Connected phones refresh it every 30 seconds without
discarding the current estimate. An automatic reconnect to the same host may
resume a calibration under five minutes old after one low-delay probe validates
the saved offset and the host's clock epoch. A host restart, transport change,
stale estimate, or clock disagreement requires a fresh 12-probe calibration.
Explicit Leave clears the offset. The optional `clockepoch` field keeps this
compatible with existing protocol-31 phones, which still use the full batch.

Phones see only their own team by default; the laptop's local game-master map
still sees every player. A valid IR hit temporarily reveals the involved enemy
to each participating player's whole team for up to ten seconds. The host ends
that reveal immediately if either player is eliminated, and a miss does not
reveal a location.

Laser lines are based on a phone that actually received a valid IR hit; they
are not guessed from proximity. The host records each endpoint's latest GPS
position with the confirmed hit. A miss has no target coordinate, so it counts
in SHOTS and accuracy but intentionally does not invent a line on the map.

## Automated checks

From the repository root, compile the dependency-free host smoke test, then
run just the post-round report checks (omit `--reports-only` for the full
protocol suite):

```bash
javac --release 17 --add-modules jdk.httpserver -d laptop-host/out \
  shared/src/main/java/com/simplecoil/protocol/*.java \
  laptop-host/src/main/java/com/simplecoil/laptophost/*.java \
  laptop-host/src/test/java/com/simplecoil/laptophost/*.java
java --add-modules jdk.httpserver -cp laptop-host/out \
  com.simplecoil.laptophost.LaptopHostProtocolSmokeTest --reports-only
java --add-modules jdk.httpserver -cp laptop-host/out \
  com.simplecoil.laptophost.LargeLobbyClockSyncTest
java --add-modules jdk.httpserver -cp laptop-host/out \
  com.simplecoil.laptophost.PhoneUpdateCacheTest
node --test laptop-host/src/test/js/*.test.cjs
```

Report smoke tests use temporary report directories, not the operator's archive.
The phone-update checks use an injected upstream, temporary cache, and real local
HTTP clients. When running other host suites, set
`JAVA_TOOL_OPTIONS=-Dsimplecoil.phoneupdates.enabled=false` to keep their host
subprocesses from fetching live GitHub releases.
The JavaScript checks exercise the actual embedded review code with a minimal
DOM harness, including SVG lanes/events, filters, partial-data notices, and safe
names. They complement rather than replace a browser or physical-gun check.
The large-lobby check uses 16, 24 and 32 TCP peers with concurrent gun-readiness,
settings and GPS updates, delayed metadata processing and a blocked writer. It
requires joining plus twelve accepted clock samples and current lobby state in
under five seconds. It preserves the native 80 ms sample limit; physical Wi-Fi
and blaster behavior still need a device check.
Set `SIMPLECOIL_TIMELINE_SVG` to a local output filename when running the Node
check to save its standalone SVG fixture for visual inspection.
