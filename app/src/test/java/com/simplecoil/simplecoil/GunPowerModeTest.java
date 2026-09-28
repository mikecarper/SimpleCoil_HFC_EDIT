package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.*;

public class GunPowerModeTest {
    private long now;

    @Test public void cutoffIsInclusiveAndDoesNotUseTheRoundedDisplay() {
        GunPowerMode power = new GunPowerMode();
        assertFalse(power.updateVoltage(4040));
        assertFalse(power.updateVoltage(4001));
        assertTrue(power.recoilEnabled(true, false));
        assertTrue(power.updateVoltage(4000));
        assertTrue(power.isLowPower());
        assertFalse(power.recoilEnabled(true, false));
    }

    @Test public void recoilStaysEnabledBetweenTheOldAndNewCutoffs() {
        GunPowerMode power = new GunPowerMode();
        for (int millivolts : new int[]{4400, 4300, 4200, 4100, 4040, 4001}) {
            assertFalse(power.updateVoltage(millivolts));
            assertFalse(power.isLowPower());
            assertTrue(power.recoilEnabled(true, false));
            assertTrue(power.recoilEnabled(false, true));
        }
    }

    @Test public void lowPowerOverridesTournamentButCannotChangeItsOtherRules() {
        GunPowerMode power = new GunPowerMode();
        assertTrue(power.recoilEnabled(false, true));
        byte[] normal = power.recoilCommand(false, true);
        power.updateVoltage(3900);
        assertFalse(power.recoilEnabled(true, true));
        assertFalse(power.recoilEnabled(false, true));
        byte[] low = power.recoilCommand(true, true);
        assertEquals(20, low.length);
        assertEquals(0x10, low[0]);
        assertEquals(2, low[2]);
        assertEquals(3, normal[3]);
        assertEquals(2, low[3]);
        assertEquals(255, low[4] & 255);
        for (int i = 0; i < low.length; i++)
            if (i != 3) assertEquals("Only the feedback flag can change: byte " + i, normal[i], low[i]);
    }

    @Test public void voltageReboundAndRepeatedTelemetryCannotToggleTheMotor() {
        GunPowerMode power = new GunPowerMode();
        assertTrue(power.updateVoltage(3900));
        for (int i = 0; i < 1000; i++) {
            assertFalse(power.updateVoltage(i % 2 == 0 ? 3990 : 5000));
            assertTrue(power.isLowPower());
        }
    }

    @Test public void rememberedLatchIsActiveBeforeFreshTelemetryArrives() {
        GunPowerMode power = new GunPowerMode();
        power.reset(true);
        assertTrue(power.isLowPower());
        assertFalse(power.updateVoltage(GunBattery.VOLTAGE_UNKNOWN));
        assertFalse(power.recoilEnabled(true, true));
        assertFalse(power.updateVoltage(4799));
        assertTrue(power.isLowPower());
        // A later rise is not evidence of a replaced pack on this connection.
        assertFalse(power.updateVoltage(5500));
    }

    @Test public void healthyFreshConnectionRecoversAt4800() {
        GunPowerMode power = new GunPowerMode();
        power.reset(true);
        assertFalse(power.updateVoltage(-1));
        assertTrue(power.updateVoltage(4800));
        assertFalse(power.isLowPower());
        assertTrue(power.recoilEnabled(false, true));
        assertFalse(power.recoilEnabled(false, false));
        assertFalse(power.updateVoltage(4800));
        assertTrue(power.updateVoltage(4000));
    }

    @Test public void independentGunsDoNotShareBatteryOverrides() {
        GunPowerMode first = new GunPowerMode();
        GunPowerMode second = new GunPowerMode();
        first.updateVoltage(3900);
        second.updateVoltage(5100);
        assertFalse(first.recoilEnabled(true, true));
        assertTrue(second.recoilEnabled(true, true));
        first.reset(false); // A different gun must not inherit the first gun's latch.
        assertTrue(first.recoilEnabled(true, true));
    }

    @Test public void freshUnknownAndUncalibratedReadingsCannotDisableRecoil() {
        GunPowerMode power = new GunPowerMode();
        GunBattery battery = new GunBattery(() -> now);
        addSamples(battery, 0, 100);
        for (int type : new int[]{0, 1, 99}) {
            battery.setBlasterType(type);
            assertFalse(power.updateVoltage(battery.voltageMillivolts()));
            assertFalse(power.isLowPower());
        }
        battery.setBlasterType(2);
        assertTrue(power.updateVoltage(battery.voltageMillivolts()));
        assertTrue(power.isLowPower()); // Below-range pistol voltage is still low.
    }

    @Test public void realSamplesWarmUpAndFilterDipsBeforeTheCutoff() {
        GunPowerMode power = new GunPowerMode();
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 3033, 19); // Approximately 4.49 V.
        assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        addSamples(battery, 3033, 81);
        assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        now = 1_000;
        addSamples(battery, 2704, 1); // One short dip must not activate low power.
        assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        addSamples(battery, 2704, 99);
        assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        now = 15_000;
        assertTrue(power.updateVoltage(battery.voltageMillivolts()));
    }

    @Test public void stableLowBatteryTriggersOnlyAfterTheTwentiethReading() {
        GunPowerMode power = new GunPowerMode();
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        for (int i = 0; i < 19; i++) {
            addSamples(battery, 2704, 1); // Approximately 3.999 V.
            assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        }
        addSamples(battery, 2704, 1);
        assertTrue(power.updateVoltage(battery.voltageMillivolts()));
    }

    @Test public void bottomSeventyFivePercentCannotTriggerLowPower() {
        GunPowerMode power = new GunPowerMode();
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        // Even a majority of sagging readings must not replace the healthy top quarter.
        addSamples(battery, 3368, 25);
        addSamples(battery, 2000, 75);
        assertFalse(power.updateVoltage(battery.voltageMillivolts()));
        assertTrue(power.recoilEnabled(false, true));
        assertEquals(50, battery.voltageTenths());
    }

    private static void addSamples(GunBattery battery, int raw, int count) {
        for (int i = 0; i < count; i++) battery.addSample((byte) raw, (byte) (raw >> 8));
    }
}
