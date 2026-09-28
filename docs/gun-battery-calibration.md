# Gun battery voltage and NiMH indicator

The RK-45 pistol uses four AA cells. This app's default pistol battery
indicator assumes NiMH rechargeable cells, as specified for these games.
It displays estimated total pack voltage and a coarse condition, not a
remaining-capacity percentage.

## Voltage calibration

One RK-45 (reported type 2, firmware 13024) was powered from a variable supply.
The user supplied the voltage settings; an independent meter reading at the
gun contacts was not confirmed. The raw telemetry reading is an unsigned
little-endian value from bytes 6 and 7. Each accepted snapshot averaged 100
real samples, with an additional six-second settling interval for later
captures. Snapshots overlap; they are not independent sets of 100 readings.

| User-reported supply | Mean raw reading |
| --- | ---: |
| 6.00 V | 4024.096842 |
| 5.50 V | 3713.574737 |
| 5.00 V | 3367.926316 |
| 4.50 V | 3032.780000 |
| 4.00 V | 2713.172000 |
| 3.50 V | 2384.154737 |
| 3.00 V | 2017.993684 |
| 2.50 V | 1705.398947 |
| 2.25 V | 1536.991111 |
| 2.00 V | 1389.412000 |

The ordinary least-squares fit used by `GunBattery` is:

```
estimated volts = 0.0015050374743 * upper-quartile raw mean - 0.0708648104
```

R-squared is 0.9998498 and the largest residual is approximately 0.034 V.
This measures agreement with the supply settings, not independently verified
accuracy, inter-gun variation, or performance under recoil. Readings are
displayed to one decimal with `~`; outside the measured range the UI shows
`<2.0 V` or `>6.0 V` instead of an extrapolated number. Rounding is performed
before the range check to avoid flickering at the calibration endpoints.

The conversion is restricted to type 2 pistols. A six-AA SR-12 needs its own
voltage calibration; its previous qualitative thresholds remain unchanged.
Unknown types and disconnected guns must never inherit the pistol estimate.

## NiMH display policy

Four NiMH cells have a normal operating plateau around 4.8 V, with a freshly
charged open-circuit pack around 5.6 V. Voltage is a poor capacity gauge across
that plateau. These application-selected bands intentionally avoid a linear
percentage and use the rounded, smoothed pack voltage:

| Displayed pack voltage | Condition |
| --- | --- |
| 5.4 V or higher | Full |
| 4.6-5.3 V | Good |
| 4.3-4.5 V | Low |
| 4.1-4.2 V | Recharge soon |
| 4.0 V or lower | Empty - recharge |

The 4.0 V empty indication replaces the earlier user-selected 2.25 V
electronics-only endpoint now that NiMH is specified. It is not a guarantee
that all four individual cells are above a safe voltage. Use matched cells
and recharge the set when empty; the app does not measure individual cells
or physically isolate the batteries.

The estimator keeps every reading received within the last 15 seconds,
discards the lowest 75%, and averages the highest 25%. With a sample count
that is not divisible by four, it retains `ceil(count / 4)` whole readings.
Duplicate readings count separately; a tie at the boundary does not include
extra readings. The calibration is linear and increasing, so filtering raw
values before conversion gives the same result as filtering unrounded volts.
Both the displayed voltage and recoil cutoff use this estimate.

It waits for 20 real, unexpired samples (about one second in our captures),
then uses the available window without delaying startup for a full 15 seconds.
Samples expire when they reach 15 seconds old. Monotonic elapsed time keeps
GPS/network clock adjustments out of this calculation. Packet bursts do not
evict younger readings; a telemetry gap cannot leave an old high reading in
the estimate indefinitely. Disconnects and gun changes clear the window.
The SR-12 retains its uncalibrated raw-mean qualitative thresholds.

This filter reduces the influence of short recoil-related dips. It can also
retain a higher estimate for up to 15 seconds after a sustained voltage drop;
it is not instantaneous under-load protection. Diagnostics report the full
window's ordinary raw mean separately from `upperQuartileMean`.

UI labels refresh when the displayed voltage changes, including when old
readings expire while the condition stays Good. The telemetry path caches
the filtered estimate and does not allocate UI strings for each notification
when the displayed reading is unchanged.

## Low-voltage firing observations

At a user-reported 2.0 V, BLE discovery, connection, and idle telemetry worked.
A later solo-practice test had 30 rounds loaded with Single and recoil On.
The user then reported that the gun powered down, and telemetry stopped.
The one-second diagnostic log did not capture a held trigger or the exact
shutdown transient; recoil motion and optical shot output were not confirmed.
Supply current limiting and power-contact interruptions were not excluded.

The user subsequently reported a 4.0 V firing cutoff. The recoil cutoff was
initially discussed at 4.2 V, implemented at 4.4 V, then moved to 4.0 V at the
user's request.

## Automatic low-power mode

For the calibrated four-AA RK-45, an estimated smoothed voltage of 4.0 V or
below disables recoil, including in tournament mode. The policy waits for the
same 20-sample/type warm-up as the indicator and uses the estimate before
rounding to tenths. A displayed `~4.0 V` slightly above the actual threshold
does not yet trigger it. The millivolt-valued internal comparison is not a
claim of millivolt measurement accuracy.

Only the recoil-feedback bit is changed. Shot mode, cadence, ammo, reload,
damage, and muzzle-flash behavior remain the same. A low-power gun cannot be
forced back to recoil On by the power button, tournament rules, game start,
or settings updates. Two Boss guns have independent protection. A failed
recoil-off BLE write is retried at most twice; steady battery telemetry does
not repeatedly enqueue commands. A failed/disconnected BLE link cannot
guarantee motor control; reconnecting reapplies the intended setting.

Low power is latched for the connection and stored against the gun's MAC
address across reconnects and app restarts. This prevents the voltage rebound
after recoil stops from repeatedly restarting the motor. A fresh connection
whose first smoothed reading is at least 4.8 V clears the latch. Replace or
recharge the pack, then reconnect; ordinary mid-connection voltage recovery
does not clear it. A saved latch is enforced while new readings warm up.
The uncalibrated six-AA rifle does not use this pistol cutoff.

The lobby battery label and playing HUD indicate low power without hiding the
Empty - recharge warning. This feature is motor-power saving, not battery
isolation or permission to discharge NiMH to the electronics-only cutoff.
Actual NiMH firing endurance and voltage sag still need field validation.

## Sources

- [Energizer NiMH handbook](https://data.energizer.com/pdfs/nickelmetalhydride_appman.pdf),
  discharge profile, state-of-charge measurement, and discharge termination.
- [Energizer AA NiMH datasheet](https://data.energizer.com/pdfs/nh15-2300.pdf),
  rated discharge conditions.
- [Recoil telemetry notes](https://wiki.lazerswarm.com/wiki/Recoil:Bluetooth_Protocol_Details#Telemetry),
  byte layout. Voltage coefficients above come from this calibration, not
  from that protocol reference.
