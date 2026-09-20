# SimpleCoil

Made a lot of Changes shoutout to the original Creator https://github.com/Dees-Troy/SimpleCoil

## Synchronized game starts

Network games synchronize with the host using five NTP-style clock exchanges over
the local network. No internet connection or public NTP server is required. The
least-delayed sample estimates the offset between device monotonic clocks; the app
does not change the phone's system time. This supports Android 5.1.1.

Start waits until connected players have synchronized. If the clocks are still
syncing, wait a moment and tap Start again. The host schedules one shared start
deadline using its respawn-time setting (10 seconds by default). Everyone displays
a countdown to that deadline; later respawns still use each player's own settings.
Timed games also share an end deadline, so a late packet or reconnect does not give
a player extra game time. Clock estimates refresh while connected to a dedicated
server. Actual alignment depends on network delays and device scheduling.

All participating phones must run network protocol 08 (this build). Older protocol
versions are rejected instead of starting with incompatible countdown behavior.

## Player capacity

Up to 20 players are supported, plus a separate dedicated host phone (21 phones
total). Two-team games use IDs 1-10 and 11-20. Four-team games use IDs 1-5, 6-10,
11-15, and 16-20. Existing players should check their displayed team after updating.

The start-alignment target is within one second. Clock exchanges with more than
500 ms of network delay are rejected and retried. This is not a guarantee under
Wi-Fi stalls or a suspended device; multi-phone field validation is still needed.
