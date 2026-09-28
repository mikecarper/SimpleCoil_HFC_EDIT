package com.simplecoil.simplecoil;

import java.util.Map;
import java.util.TreeMap;

/** Upper-quartile voltage estimate, not a measurement of remaining capacity. */
final class GunBattery {
    enum Level { UNKNOWN, HIGH, GOOD, LOW, CRITICAL, EMPTY }

    interface Clock { long nowMillis(); }

    static final long WINDOW_MILLIS = 15_000;
    private static final int VOLTAGE_MIN_SAMPLES = 20;
    static final int VOLTAGE_UNKNOWN = -1;
    static final int VOLTAGE_BELOW_RANGE = 19;
    static final int VOLTAGE_ABOVE_RANGE = 61;
    // One four-AA RK-45, ten user-reported supply settings from 2.0 to 6.0 V.
    // See docs/gun-battery-calibration.md. Do not apply this fit to the SR-12.
    private static final double PISTOL_VOLTS_PER_RAW_UNIT = 0.0015050374743;
    private static final double PISTOL_VOLTS_OFFSET = -0.0708648104;
    private final Clock clock;
    // Keep every reading in the time window, regardless of telemetry rate.
    // Reuse primitive rings and count distinct values instead of sorting the
    // window or allocating sample records for every packet. Grow only as needed.
    private int[] samples = new int[256];
    private long[] receivedAt = new long[256];
    private final TreeMap<Integer, Integer> frequencies = new TreeMap<>();
    private int count;
    private int first;
    private long total;
    private int blasterType;
    private boolean estimateDirty = true;
    private double upperQuartileMean = Double.NaN;

    GunBattery(Clock clock) {
        this.clock = clock;
    }

    void reset() {
        count = first = blasterType = 0;
        total = 0;
        frequencies.clear();
        estimateDirty = true;
        upperQuartileMean = Double.NaN;
    }

    void setBlasterType(int type) {
        blasterType = type;
    }

    void addSample(byte low, byte high) {
        long now = clock.nowMillis();
        expireSamples(now);
        // The proprietary telemetry contains a little-endian raw reading, not volts.
        int sample = (low & 0xff) | ((high & 0xff) << 8);
        if (count == samples.length) growWindow();
        int next = (first + count) % samples.length;
        samples[next] = sample;
        receivedAt[next] = now;
        count++;
        total += sample;
        Integer frequency = frequencies.get(sample);
        frequencies.put(sample, frequency == null ? 1 : frequency + 1);
        estimateDirty = true;
    }

    private void growWindow() {
        int[] largerSamples = new int[samples.length * 2];
        long[] largerTimes = new long[receivedAt.length * 2];
        int tail = samples.length - first;
        System.arraycopy(samples, first, largerSamples, 0, tail);
        System.arraycopy(samples, 0, largerSamples, tail, first);
        System.arraycopy(receivedAt, first, largerTimes, 0, tail);
        System.arraycopy(receivedAt, 0, largerTimes, tail, first);
        samples = largerSamples;
        receivedAt = largerTimes;
        first = 0;
    }

    private void expireSamples(long now) {
        // A reading exactly 15 seconds old has left the window. All callers use
        // monotonic time, so network/GPS/wall-clock corrections cannot retain it.
        while (count > 0 && now - receivedAt[first] >= WINDOW_MILLIS) {
            int sample = samples[first];
            int frequency = frequencies.get(sample);
            if (frequency == 1) frequencies.remove(sample);
            else frequencies.put(sample, frequency - 1);
            total -= sample;
            first = (first + 1) % samples.length;
            count--;
            estimateDirty = true;
        }
        if (count == 0) first = 0;
    }

    private double upperQuartileMean() {
        if (!estimateDirty) return upperQuartileMean;
        upperQuartileMean = Double.NaN;
        if (count > 0) {
            // Readings are indivisible: keep ceil(N / 4), including ties only
            // as needed to fill that many positions, not every tied reading.
            int keep = (count + 3) / 4;
            int remaining = keep;
            long sum = 0;
            for (Map.Entry<Integer, Integer> entry : frequencies.descendingMap().entrySet()) {
                int take = Math.min(remaining, entry.getValue());
                sum += (long) entry.getKey() * take;
                remaining -= take;
                if (remaining == 0) break;
            }
            upperQuartileMean = (double) sum / keep;
        }
        estimateDirty = false;
        return upperQuartileMean;
    }

    static final class Snapshot {
        final int type, count, latest, minimum, maximum;
        final double mean, upperQuartileMean;

        Snapshot(int type, int count, int latest, int minimum, int maximum,
                 double mean, double upperQuartileMean) {
            this.type = type;
            this.count = count;
            this.latest = latest;
            this.minimum = minimum;
            this.maximum = maximum;
            this.mean = mean;
            this.upperQuartileMean = upperQuartileMean;
        }
    }

    /** Diagnostics retain the ordinary raw mean alongside the filtered estimate. */
    Snapshot snapshot() {
        expireSamples(clock.nowMillis());
        if (count == 0)
            return new Snapshot(blasterType, 0, -1, -1, -1, Double.NaN, Double.NaN);
        return new Snapshot(blasterType, count, samples[(first + count - 1) % samples.length],
                frequencies.firstKey(), frequencies.lastKey(), (double) total / count,
                upperQuartileMean());
    }

    private double estimatedVolts() {
        expireSamples(clock.nowMillis());
        if (blasterType != 2 || count < VOLTAGE_MIN_SAMPLES) return Double.NaN;
        // The calibration is linear and increasing. Selecting/averaging the
        // highest raw readings before converting is equivalent to doing it in
        // volts, without losing precision to per-reading display rounding.
        return upperQuartileMean() * PISTOL_VOLTS_PER_RAW_UNIT + PISTOL_VOLTS_OFFSET;
    }

    /** One-decimal estimate; sentinel values indicate unknown or outside calibration. */
    int voltageTenths() {
        double volts = estimatedVolts();
        if (Double.isNaN(volts)) return VOLTAGE_UNKNOWN;
        // Round before testing the range so noise at a measured endpoint does
        // not alternate between e.g. 6.0 V and >6.0 V. Never claim an exact
        // extrapolated voltage outside the range we actually measured.
        int tenths = (int) Math.round(volts * 10.0);
        return Math.max(VOLTAGE_BELOW_RANGE, Math.min(VOLTAGE_ABOVE_RANGE, tenths));
    }

    /** Internal cutoff input, not a claim of millivolt measurement accuracy. */
    int voltageMillivolts() {
        double volts = estimatedVolts();
        if (Double.isNaN(volts)) return VOLTAGE_UNKNOWN;
        // Below the calibrated range still means low, not an unknown reading.
        return Math.max(0, (int) Math.round(volts * 1000.0));
    }

    boolean isUncalibratedType() {
        return blasterType != 0 && blasterType != 2;
    }

    Level level() {
        if (blasterType == 2) {
            int tenths = voltageTenths();
            if (tenths == VOLTAGE_UNKNOWN) return Level.UNKNOWN;
            // Four NiMH AA cells: about 5.6 V just charged, a long plateau
            // around 4.8 V, and empty near 4.0 V. These are coarse UI bands,
            // not a linear percentage or permission to discharge to 2.25 V.
            if (tenths >= 54) return Level.HIGH;
            if (tenths >= 46) return Level.GOOD;
            if (tenths > 42) return Level.LOW;
            if (tenths > 40) return Level.CRITICAL;
            return Level.EMPTY;
        }
        expireSamples(clock.nowMillis());
        if (count == 0 || blasterType != 1) return Level.UNKNOWN;
        long average = total / count;
        // The six-cell rifle has not been voltage-calibrated. Retain its raw
        // legacy indicator rather than inventing calibrated volts or a %.
        if (average >= 21 * 256) return Level.HIGH;
        if (average >= 18 * 256) return Level.GOOD;
        if (average >= 15 * 256) return Level.LOW;
        return Level.CRITICAL;
    }
}
