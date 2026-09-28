# Recoil and firing-rate investigation

The physical automatic-fire comparison found approximately 20 reported shots
per second with recoil on and off. Single-shot also produced one shot per
reported trigger press in both states. These checks did not reproduce a
meaningful recoil-off speed-up. Burst comparison remains pending. Do not
describe the counter fix below as a calibrated firing-rate limiter or extend
one pistol's result to all guns and modes.

## Confirmed software defect

Telemetry byte 5 contains two independent, modulo-16 counters. The low nibble
counts power-button presses; the high nibble counts recoil cycles. Reading the
whole byte as the power-button counter caused a recoil-cycle change to run the
power-button handler. That could toggle recoil in a non-tournament game, or
enqueue redundant recoil configuration writes in a tournament game.

The handler now masks out the recoil counter, for both primary and secondary
guns. Regression tests cover counter rollover, actual power-button presses,
dual-gun input, and tournament configuration traffic. They use synthetic
telemetry; they do not establish the motor or IR firing cadence on hardware.

Validation: all four new counter tests failed against the previous APK. After the
fix, all 18 focused weapon-settings tests passed on the Android 5.1.1 test phone,
as did all 95 local unit tests, the debug build, and lint (zero errors). The phone's
saved settings were unchanged by those tests.

## Physical automatic-fire comparison on 2026-09-27

The test pistol was connected to phone 44c7c in isolated Free For All solo
practice, with 30 rounds, automatic mode, and the existing rate byte of 1. Idle
battery telemetry indicated approximately 5.0 V. The operator confirmed physical
recoil during the enabled run. Successful configuration writes established
recoil-on for the first run and recoil-off for the second.

Both runs consumed all 30 rounds. The interval from the first observed decrement
to the last (29 shot intervals) was 1,463 ms with recoil on and 1,416 ms with it
off. Notification batching makes those endpoint estimates noisy. A straight-line
fit across all 30 cumulative shot counts gave 50.31 ms per shot with recoil on
and 49.92 ms off, or approximately 19.88 and 20.03 shots per second. This is
consistent with the configured 50 ms interval in both cases, not evidence of a
meaningful recoil-off boost. It does not prove identical instantaneous intervals.

These measurements use magazine telemetry, not an external optical IR detector;
they do not prove every reported shot was received by another gun. Raw capture
and analysis are retained locally in the ignored
`output/fire-rate/live-20260927/` directory. The phone clock was incorrect, so the
analysis uses monotonic `ms=` values, not logcat's wall-clock dates.

## Physical single-shot comparison on 2026-09-27

On the same gun, the operator switched to the app's existing Single profile
(trigger mode 254, interval byte 0). Recoil-off telemetry reported 10 trigger
presses and 10 magazine decrements, with nine inter-shot intervals spanning
1,609 ms. Recoil-on telemetry reported 11 presses and 11 decrements, with ten
intervals spanning 1,901 ms. The latter included a 537 ms pause. No shot-count
change was seen without a corresponding trigger-counter increment in either run.
The gun reported 11 presses in the second run even though the requested test was
10; the capture alone cannot establish the physical cause of that extra press.

Median notification spacing between shots was 147 ms off and 146.5 ms on. The
fastest observed spacing was approximately 98 ms in each run. There was no
evidence here that recoil blocked otherwise accepted trigger presses or caused
extra shots beyond the gun's reported trigger count. This is a manual-pull test,
not a controlled maximum-rate test. Single-shot still follows trigger presses;
no new uniform cooldown or software shot limiter has been added.

After the solo round ended, both diagnostic captures were disabled and the
pre-test preferences were restored and compared after restart. This restored
Tournament / 2 Teams, Single, recoil enabled, the five-minute limit, and the
existing player ID and gun pairing, and removed the test-only previous-game
snapshot from the lobby. The reserved phone was not used.

## Manufacturer documentation

- [BLE protocol, Telemetry and Config sections](https://github.com/SkyRocketToys/Recoil_Documentation/blob/master/Recoil_Protocol_BLE.docx)
- [Firmware configuration guide, Trigger Mode and Rate of Fire sections](https://github.com/SkyRocketToys/Recoil_Documentation/blob/master/Recoil_Gun_Firmware_Config_Guide.docx)

The guide defines the firing interval in 50 ms units for burst, automatic, and
charge modes. It does not document this parameter as a cooldown between separate
single-shot trigger pulls. The app's legacy profiles currently use 1 (50 ms) for
automatic and 3 (150 ms) for burst. The legacy single-shot profile uses automatic
trigger mode with a zero interval. These are configuration values, not measured
physical rates. The guide also warns that an IR firing period shorter than the
repeated transmission can cause overlapping shots.

The feedback command changes recoil independently of those profiles. Simply
resending the same interval does not prove that removing the motor's timing
effects leaves the effective rate unchanged. Changing motor power to zero is
also not an established substitute for disabling recoil on production firmware.

## Focused physical check still needed

Use a charged battery pack or a stable 5-6 V supply on the four-cell test pistol.
Keep the gun type, voltage, range setting, firing mode, and magazine size the same
between comparisons. Use an isolated, non-tournament test session to permit the
recoil toggle without changing normal tournament rules.

Compare actual magazine decrements with recoil enabled and disabled, first while
holding automatic fire, then for burst and repeated single-shot pulls. Also
compare the recoil-cycle counter. A faster sound or motor sensation alone does
not establish that more IR shots were emitted. BLE notification arrival times
have sampling and transport jitter; compare whole shot trains, not one short
interval. Several shots can occur between two notifications. Exclude reloads,
reconnects, ammo-drain game mechanics, and setting transitions from the sample.

## Debug capture

The debug APK supports `fire_timing_diagnostics`. It records raw telemetry and
configuration/command writes at the Bluetooth service, before activity delivery,
using monotonic elapsed milliseconds. `slot=0` is the primary gun; `slot=1` is the
secondary. Payload entries use Java's signed byte representation, so decode each
with `value & 0xff`. A successful GATT write is not a measured firing interval.

```sh
adb -s LGL51AL28144c7c shell am start -f 0x20000000 -n com.simplecoil.simplecoil/.FullscreenActivity --ez fire_timing_diagnostics true
adb -s LGL51AL28144c7c logcat -v time GunFire:D '*:S'
adb -s LGL51AL28144c7c shell am start -f 0x20000000 -n com.simplecoil.simplecoil/.FullscreenActivity --ez fire_timing_diagnostics false
```

Capture is off by default, disabled in release builds, and stopped when the main
activity is destroyed. It does not fire, reload, or change the gun configuration.
Respect the phone reserved for other testing; use only the explicitly assigned
test phone. The older battery-only capture is sampled once per second and is not
sufficient for this measurement.
