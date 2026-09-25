# SimpleCoil laptop host

`LaptopHost` is a dedicated-host replacement for the Android host screen. It
keeps the game authority and live display on a laptop; players still use the
Android app and their BLE laser-tag hardware.

It speaks the protocol-19 TCP lobby, clock synchronization, GPS,
score, respawn, grenade-pairing, and UDP discovery protocols. No Node, Python,
database, cloud service, or internet connection is required.

The map display bundles Leaflet and serves raster map tiles from the laptop.
Nothing is fetched from the internet while a game is running. Player markers,
trails, hit lines, and game-master controls continue to work even when no base
map tiles have been installed.

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

For Balanced Random teams, start the laptop with `--balanced-qr` to require
each player to scan the assigned Team 1 or Team 2 QR, or `--balanced-no-qr` to
start the 10-second countdown as soon as teams are assigned. The laptop uses
the prior kills and deaths reported by each phone and keeps team sizes within
two players. The dashboard Start button assigns teams; QR mode starts
automatically after the last check-in. These options also work with `--takeover`.
If a player does not scan, QR mode starts the same 10-second countdown after
90 seconds; that player stays on their assigned team.

To enable gameplay QR power-ups on the laptop host, add `--powerup-qr 4` through
`--powerup-qr 8` (off by default). Print eight QR codes containing the text
`SIMPLECOIL:POWERUP:1` through `SIMPLECOIL:POWERUP:8`; each phone must scan
different codes to earn each random reward. Progress resets on death.

For Boss Mode, run `./laptop-host/run.sh --boss`. Player 1 is the boss and all
other player IDs are hunters. The roster freezes when the round starts. Hunters
have 2 health, 3 shields, 30 rounds, and locked single-shot fire. The boss has
5 health and 10 shields plus 1 health and 2 shields for every hunter, carries
120 rounds, starts in automatic, and may switch between single, burst, and
automatic fire. Health and shields do not regenerate in this mode. In normal
tournament games, health starts returning one point per second after 30 seconds
without another damaging hit.

The launcher prints each usable laptop IPv4 address. On every phone, use
**Join Game** and enter that address. The app's normal UDP join step and TCP
lobby connection happen automatically. Allow the laptop through its firewall
on TCP port `17510` and UDP port `17500` if prompted.

To replace a lobby that was created on a phone, start the laptop host with
`--takeover` before the game starts. Updated phones in that idle lobby
automatically reconnect to the laptop, which then becomes the game authority:

```bash
./laptop-host/run.sh --takeover
```

The laptop's selected rules become authoritative. A takeover deliberately does
not interrupt or move an in-progress round; end that game first.

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
./laptop-host/run.sh --tiles /path/to/xyz-tiles
./laptop-host/run.sh --tile-port 17512
```

Run `./laptop-host/run.sh --help` for every option. Keep the default TCP and
UDP ports for stock SimpleCoil phones. Tournament mode is always enabled and
locks the two-team health, ammunition, reload, damage, recoil, and single-shot
rules. `--tournament` remains accepted only for compatibility with old scripts.

## GPS and laser display

The Android app requests GPS and network-location fixes every 250 ms while GPS
mode is active. GPS remains the preferred source; the network provider can
produce a useful initial location while a phone is acquiring satellites. The
app forwards meaningful movement to a hosted game immediately. The laptop
forwards changed positions at up to four updates per second and sends periodic
complete snapshots to correct stale markers.

When a phone has a fresh GPS fix, SimpleCoil also uses its UTC timestamp as an
optional refinement for the shared countdown. It does not change the phone's
system clock; the existing monotonic round-trip clock sync remains the fallback
and final guardrail when GPS time is missing or disagrees. Joining the lobby
immediately starts a 12-probe calibration burst; only samples at or below 80 ms
round trip are accepted, keeping network-path uncertainty below 50 ms.

Phones see only their own team by default; the laptop's local game-master map
still sees every player. A valid IR hit temporarily reveals the involved enemy
to each participating player's whole team for up to ten seconds. The host ends
that reveal immediately if either player is eliminated, and a miss does not
reveal a location.

Laser lines are based on a phone that actually received a valid IR hit; they
are not guessed from proximity. The host records each endpoint's latest GPS
position with the confirmed hit. A miss has no target coordinate, so it counts
in SHOTS and accuracy but intentionally does not invent a line on the map.
