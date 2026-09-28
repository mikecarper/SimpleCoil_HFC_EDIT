package com.simplecoil.simplecoil;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class GunBatteryTest {
    private long now;

    @Test public void needsRealReadingAndKnownGunType() {
        GunBattery battery = new GunBattery(() -> now);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        battery.setBlasterType(2);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        battery.reset();
        addSamples(battery, 16 * 256, 20);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        battery.setBlasterType(99);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        battery.setBlasterType(2);
        assertEquals(GunBattery.Level.HIGH, battery.level());
    }

    @Test public void fourCellNimhBandsRespectTheNormalVoltagePlateau() {
        assertVolts(5.6, GunBattery.Level.HIGH);
        assertVolts(5.4, GunBattery.Level.HIGH);
        assertVolts(5.3, GunBattery.Level.GOOD);
        assertVolts(4.8, GunBattery.Level.GOOD);
        assertVolts(4.6, GunBattery.Level.GOOD);
        assertVolts(4.5, GunBattery.Level.LOW);
        assertVolts(4.3, GunBattery.Level.LOW);
        assertVolts(4.2, GunBattery.Level.CRITICAL);
        assertVolts(4.1, GunBattery.Level.CRITICAL);
        assertVolts(4.0, GunBattery.Level.EMPTY);
        assertVolts(2.25, GunBattery.Level.EMPTY);
    }

    @Test public void rifleUsesSixCellThresholds() {
        assertReading(1, 15 * 256 - 1, GunBattery.Level.CRITICAL);
        assertReading(1, 15 * 256, GunBattery.Level.LOW);
        assertReading(1, 18 * 256 - 1, GunBattery.Level.LOW);
        assertReading(1, 18 * 256, GunBattery.Level.GOOD);
        assertReading(1, 21 * 256 - 1, GunBattery.Level.GOOD);
        assertReading(1, 21 * 256, GunBattery.Level.HIGH);
    }

    @Test public void bytesAreUnsignedAndLittleEndian() {
        assertReading(2, 0x09ff, GunBattery.Level.EMPTY);
        assertReading(2, 0x0bff, GunBattery.Level.GOOD);
        assertReading(2, 0x0dff, GunBattery.Level.GOOD);
        assertReading(1, 0xffff, GunBattery.Level.HIGH);
    }

    @Test public void oneRecoilDipDoesNotImmediatelyShowEmpty() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        for (int i = 0; i < 100; i++) battery.addSample((byte) 0, (byte) 16);
        now = 1_000;
        battery.addSample((byte) 0, (byte) 0);
        assertEquals(GunBattery.Level.HIGH, battery.level());
        for (int i = 1; i < 100; i++) battery.addSample((byte) 0, (byte) 0);
        assertEquals(GunBattery.Level.HIGH, battery.level());
        now = 15_000;
        assertEquals(GunBattery.Level.EMPTY, battery.level());
        for (int i = 0; i < 100; i++) battery.addSample((byte) 0, (byte) 12);
        assertEquals(GunBattery.Level.GOOD, battery.level());
    }

    @Test public void firstReadingIsNotBiasedByInventedFullSample() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 9 * 256, 1);
        assertEquals(2304.0, battery.snapshot().mean, 0.0);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
        addSamples(battery, 9 * 256, 18);
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
        addSamples(battery, 9 * 256, 1);
        assertEquals(GunBattery.Level.EMPTY, battery.level());
        assertEquals(34, battery.voltageTenths());
    }

    @Test public void resetClearsSamplesAndGunType() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(1);
        for (int i = 0; i < 120; i++) battery.addSample((byte) 0, (byte) 24);
        battery.reset();
        battery.addSample((byte) 0, (byte) 12);
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        battery.setBlasterType(2);
        addSamples(battery, 12 * 256, 19);
        assertEquals(GunBattery.Level.GOOD, battery.level());
    }

    @Test public void measuredReferencesRoundToTheirReportedSupplyVoltages() {
        int[] raw = {4024, 3714, 3368, 3033, 2713, 2384, 2018, 1705, 1537, 1389};
        int[] tenths = {60, 55, 50, 45, 40, 35, 30, 25, 22, 20};
        for (int i = 0; i < raw.length; i++) {
            GunBattery battery = new GunBattery(() -> now);
            battery.setBlasterType(2);
            addSamples(battery, raw[i], 100);
            assertEquals("Reference raw=" + raw[i], tenths[i], battery.voltageTenths());
        }
    }

    @Test public void voltageIsUnavailableForUncalibratedGunTypesAndAfterReset() {
        GunBattery battery = new GunBattery(() -> now);
        addSamples(battery, 3368, 100);
        for (int type : new int[]{0, 1, 99}) {
            battery.setBlasterType(type);
            assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
            assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageMillivolts());
        }
        battery.setBlasterType(2);
        assertEquals(50, battery.voltageTenths());
        battery.reset();
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
        battery.setBlasterType(2);
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageMillivolts());
    }

    @Test public void cutoffVoltageKeepsMoreResolutionThanTheDisplay() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 2706, 100);
        assertEquals(40, battery.voltageTenths());
        assertEquals(4000.0, battery.voltageMillivolts(), 2.0);
        assertTrue(battery.voltageMillivolts() > 4000);
        now = 15_000;
        addSamples(battery, 2704, 100);
        assertEquals(40, battery.voltageTenths());
        assertTrue(battery.voltageMillivolts() < 4000);
        assertFalse(battery.isUncalibratedType());
        battery.setBlasterType(1);
        assertTrue(battery.isUncalibratedType());
        battery.reset();
        assertFalse(battery.isUncalibratedType());
    }

    @Test public void outsideCalibrationReturnsBoundsRatherThanExtrapolatedVolts() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 0, 100);
        assertEquals(GunBattery.VOLTAGE_BELOW_RANGE, battery.voltageTenths());
        assertEquals(GunBattery.Level.EMPTY, battery.level());
        addSamples(battery, 0xffff, 100);
        assertEquals(GunBattery.VOLTAGE_ABOVE_RANGE, battery.voltageTenths());
        assertEquals(GunBattery.Level.HIGH, battery.level());
    }

    @Test public void windowTracksVoltageChangesWithinTheSameBand() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 3368, 100);
        assertEquals(50, battery.voltageTenths());
        assertEquals(GunBattery.Level.GOOD, battery.level());
        now = 1_000;
        addSamples(battery, 3236, 1);
        assertEquals(50, battery.voltageTenths());
        addSamples(battery, 3236, 99);
        assertEquals(50, battery.voltageTenths());
        now = 15_000;
        assertEquals(48, battery.voltageTenths());
        assertEquals(GunBattery.Level.GOOD, battery.level());
    }

    @Test public void diagnosticSnapshotIncludesFractionAndUnsignedExtremes() {
        GunBattery battery = new GunBattery(() -> now);
        assertEquals(0, battery.snapshot().count);
        assertTrue(Double.isNaN(battery.snapshot().mean));
        battery.setBlasterType(2);
        battery.addSample((byte) 0, (byte) 0);
        battery.addSample((byte) 0xff, (byte) 0xff);
        GunBattery.Snapshot snapshot = battery.snapshot();
        assertEquals(2, snapshot.type);
        assertEquals(2, snapshot.count);
        assertEquals(0xffff, snapshot.latest);
        assertEquals(0, snapshot.minimum);
        assertEquals(0xffff, snapshot.maximum);
        assertEquals(32767.5, snapshot.mean, 0.00001);
        assertEquals(65535.0, snapshot.upperQuartileMean, 0.0);
    }

    @Test public void diagnosticWindowAndResetDoNotIncludeEvictedSamples() {
        GunBattery battery = new GunBattery(() -> now);
        battery.addSample((byte) 0, (byte) 0);
        now = 1;
        for (int i = 0; i < 100; i++) battery.addSample((byte) 1, (byte) 16);
        now = 15_000;
        GunBattery.Snapshot snapshot = battery.snapshot();
        assertEquals(100, snapshot.count);
        assertEquals(4097, snapshot.minimum);
        assertEquals(4097, snapshot.maximum);
        assertEquals(4097, snapshot.latest);
        assertEquals(4097.0, snapshot.mean, 0.00001);
        battery.reset();
        assertEquals(0, battery.snapshot().count);
        assertEquals(-1, battery.snapshot().latest);
        battery.addSample((byte) 2, (byte) 8);
        assertEquals(2050, battery.snapshot().minimum);
        assertEquals(2050, battery.snapshot().maximum);
    }

    @Test public void averagesOnlyTheHighestQuarterNotTheMaximumOrPercentile() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 1000, 15);
        for (int raw : new int[]{3000, 3200, 3400, 3600, 3800}) addSamples(battery, raw, 1);
        assertEquals(1600.0, battery.snapshot().mean, 0.0);
        assertEquals(3400.0, battery.snapshot().upperQuartileMean, 0.0);
        assertEquals(expectedMillivolts(3400.0), battery.voltageMillivolts());
        assertEquals(50, battery.voltageTenths());
    }

    @Test public void partialQuartilesRoundUpToAWholeReading() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 1000, 15);
        for (int raw : new int[]{2800, 3000, 3200, 3400, 3600, 3800}) addSamples(battery, raw, 1);
        assertEquals(21, battery.snapshot().count);
        assertEquals(3300.0, battery.snapshot().upperQuartileMean, 0.0);
        assertEquals(expectedMillivolts(3300.0), battery.voltageMillivolts());
    }

    @Test public void repeatedValuesCountAsSeparateReadingsAtTheQuartileBoundary() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 1000, 10);
        addSamples(battery, 3000, 8);
        addSamples(battery, 4000, 2);
        assertEquals(3400.0, battery.snapshot().upperQuartileMean, 0.0);
        assertEquals(expectedMillivolts(3400.0), battery.voltageMillivolts());
    }

    @Test public void hundredsOfNewPacketsDoNotEvictReadingsBeforeFifteenSeconds() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 3368, 100);
        now = 1_000;
        addSamples(battery, 2000, 300);
        assertEquals(400, battery.snapshot().count);
        assertEquals(expectedMillivolts(3368), battery.voltageMillivolts());
        now = 14_999;
        assertEquals(expectedMillivolts(3368), battery.voltageMillivolts());
        now = 15_000;
        assertEquals(300, battery.snapshot().count);
        assertEquals(expectedMillivolts(2000), battery.voltageMillivolts());
    }

    @Test public void expiryWithoutNewPacketsReturnsUnknownInsteadOfAStaleVoltage() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 3368, 20);
        now = 14_999;
        assertEquals(50, battery.voltageTenths());
        now = 15_000;
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageMillivolts());
        assertEquals(GunBattery.Level.UNKNOWN, battery.level());
        assertEquals(0, battery.snapshot().count);
        assertTrue(Double.isNaN(battery.snapshot().upperQuartileMean));
        addSamples(battery, 2704, 19);
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageMillivolts());
        addSamples(battery, 2704, 1);
        assertEquals(expectedMillivolts(2704), battery.voltageMillivolts());
    }

    @Test public void partialExpiryRequiresTwentyRemainingReadings() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 3368, 1);
        now = 1_000;
        addSamples(battery, 3368, 19);
        assertEquals(50, battery.voltageTenths());
        now = 15_000;
        assertEquals(19, battery.snapshot().count);
        assertEquals(GunBattery.VOLTAGE_UNKNOWN, battery.voltageTenths());
    }

    @Test public void growingAWrappedRingPreservesSampleTimesAndValues() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        addSamples(battery, 1000, 128);
        now = 10_000;
        addSamples(battery, 2000, 128);
        now = 15_000;
        addSamples(battery, 3000, 129);
        assertEquals(257, battery.snapshot().count);
        assertEquals((128.0 * 2000 + 129.0 * 3000) / 257, battery.snapshot().mean, 0.00001);
        assertEquals(2000, battery.snapshot().minimum);
        assertEquals(3000, battery.snapshot().latest);
        now = 25_000;
        assertEquals(129, battery.snapshot().count);
        assertEquals(3000.0, battery.snapshot().mean, 0.0);
        assertEquals(expectedMillivolts(3000), battery.voltageMillivolts());
    }

    @Test public void rollingFilterMatchesSortedReferenceAcrossGapsAndVariableRates() {
        GunBattery battery = new GunBattery(() -> now);
        battery.setBlasterType(2);
        ArrayDeque<long[]> reference = new ArrayDeque<>();
        Random random = new Random(151525);
        for (int i = 0; i < 10_000; i++) {
            if (i >= 1200) now += random.nextInt(60);
            if (i % 2500 == 2499) now += 20_000;
            int raw = i % 101 == 0 ? 0xffff : random.nextInt(4000);
            addSamples(battery, raw, 1);
            reference.addLast(new long[]{now, raw});
            while (now - reference.peekFirst()[0] >= 15_000) reference.removeFirst();
            if (i % 31 != 0) continue;
            int[] sorted = new int[reference.size()];
            int index = 0;
            long total = 0;
            for (long[] sample : reference) {
                sorted[index++] = (int) sample[1];
                total += sample[1];
            }
            Arrays.sort(sorted);
            int keep = (sorted.length + 3) / 4;
            long upperTotal = 0;
            for (int j = sorted.length - keep; j < sorted.length; j++) upperTotal += sorted[j];
            double expected = (double) upperTotal / keep;
            GunBattery.Snapshot snapshot = battery.snapshot();
            assertEquals(sorted.length, snapshot.count);
            assertEquals(raw, snapshot.latest);
            assertEquals(sorted[0], snapshot.minimum);
            assertEquals(sorted[sorted.length - 1], snapshot.maximum);
            assertEquals((double) total / sorted.length, snapshot.mean, 0.00001);
            assertEquals(expected, snapshot.upperQuartileMean, 0.00001);
            assertEquals(sorted.length < 20 ? GunBattery.VOLTAGE_UNKNOWN
                    : expectedMillivolts(expected), battery.voltageMillivolts());
        }
    }

    private static int expectedMillivolts(double rawMean) {
        return Math.max(0, (int) Math.round((rawMean * 0.0015050374743 - 0.0708648104) * 1000));
    }

    private static void assertReading(int type, int raw, GunBattery.Level expected) {
        GunBattery battery = new GunBattery(() -> 0L);
        battery.setBlasterType(type);
        addSamples(battery, raw, 20);
        assertEquals(expected, battery.level());
    }

    private static void assertVolts(double volts, GunBattery.Level expected) {
        int raw = (int) Math.round((volts + 0.0708648104) / 0.0015050374743);
        assertReading(2, raw, expected);
    }

    private static void addSamples(GunBattery battery, int raw, int count) {
        for (int i = 0; i < count; i++)
            battery.addSample((byte) raw, (byte) (raw >> 8));
    }
}
