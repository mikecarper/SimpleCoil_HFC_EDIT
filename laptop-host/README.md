# SimpleCoil laptop host

`LaptopHost` is a dedicated-host replacement for the Android host screen. It
keeps the game authority and live display on a laptop; players still use the
Android app and their BLE laser-tag hardware.

It speaks the existing protocol-14 TCP lobby, clock synchronization, GPS,
score, respawn, grenade-pairing, and UDP discovery protocols. No Node, Python,
database, cloud service, or internet connection is required.

## Run it

Install JDK 17, connect the laptop and phones to the same Wi-Fi network, then
run this from the repository root:

```bash
./laptop-host/run.sh
```

The launcher prints each usable laptop IPv4 address. On every phone, use
**Join Game** and enter that address. The app's normal UDP join step and TCP
lobby connection happen automatically. Allow the laptop through its firewall
on TCP port `17510` and UDP port `17500` if prompted.

The launcher opens a local landing page at `http://127.0.0.1:17511/`. Use its
buttons to open the two independent display windows:

- **Tactical GPS Map** — all players are shown by team, with recent movement
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
./laptop-host/run.sh --tournament
./laptop-host/run.sh --no-late-join --no-browser
```

Run `./laptop-host/run.sh --help` for every option. Keep the default TCP and
UDP ports for stock SimpleCoil phones. Tournament mode locks the existing
two-team, shared-health, single-shot ruleset.

## GPS and laser display

The Android app requests GPS and network-location fixes every 250 ms while GPS
mode is active. GPS remains the preferred source; the network provider can
produce a useful initial location while a phone is acquiring satellites. The
app forwards meaningful movement to a hosted game immediately. The laptop
forwards changed positions at up to four updates per second and sends periodic
complete snapshots to correct stale markers.

Laser lines are based on a phone that actually received a valid IR hit; they
are not guessed from proximity. The host records each endpoint's latest GPS
position with the confirmed hit. A miss has no target coordinate, so it counts
in SHOTS and accuracy but intentionally does not invent a line on the map.
