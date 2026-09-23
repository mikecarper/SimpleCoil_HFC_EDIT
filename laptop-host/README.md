# SimpleCoil laptop host

`LaptopHost` is a dedicated-host replacement for the Android host screen. It
keeps the game authority and live display on a laptop; players still use the
Android app and their BLE laser-tag hardware.

It speaks the protocol-18 TCP lobby, clock synchronization, GPS,
score, respawn, grenade-pairing, and UDP discovery protocols. No Node, Python,
database, cloud service, or internet connection is required.

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

For Boss Mode, run `./laptop-host/run.sh --boss`. Player 1 is the boss and all
other player IDs are hunters. The roster freezes when the round starts. Hunters
have 2 health, 3 shields, 30 rounds, and locked single-shot fire. The boss has
5 health and 10 shields plus 1 health and 2 shields for every hunter, carries
120 rounds, starts in automatic, and may switch between single, burst, and
automatic fire. Shields do not regenerate in this mode.

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

- **Tactical GPS Map** — the laptop game master sees all players by team, with recent movement
  trails and short laser lines for verified hits and eliminations. The laser
  endpoints are captured when the hit is confirmed, so a later GPS movement
  does not shift the trace. It also lists one **Game Master Respawn** button
  for each eligible eliminated player.
- **Leaderboard** — KILLS, HITS, SHOTS, and accuracy (`hits / shots`) update
  live. The map window also contains Start, End Game, and Game Master Respawn
  controls.

The dashboard binds to `127.0.0.1` by default, so only the laptop can control
the game. To show it on another trusted display, use
`--dashboard-bind 0.0.0.0`; do not expose that option to an untrusted network,
because the local dashboard intentionally has no login prompt.

## Useful options

```bash
./laptop-host/run.sh --teams 2 --duration-minutes 20 --score-limit 50
./laptop-host/run.sh --no-late-join --no-browser
./laptop-host/run.sh --takeover
./laptop-host/run.sh --boss
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
