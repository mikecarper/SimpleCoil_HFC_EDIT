package com.simplecoil.simplecoil;

/** NTP-style offset estimation using monotonic clocks, without changing device time. */
final class GameClock {
    // A short burst of measurements gives NTP's lowest-round-trip selection a
    // much better chance of avoiding a Wi-Fi queueing spike. Twelve exchanges
    // still finish in well under a second on the game LAN.
    static final int SAMPLES_PER_SYNC = 12;
    static final long MAX_ROUND_TRIP_MS = 5000;
    // With an NTP-style midpoint, an otherwise unknowable asymmetric path can
    // move the result by at most half the measured network round trip.
    // Native Wi-Fi rejects anything above 80 ms, bounding network uncertainty
    // to 40 ms per phone before local scheduling jitter.
    static final long MAX_SAMPLE_DELAY_MS = 80;
    // The nearby relay uses real samples with at most 400 ms uncertainty per
    // phone. Two relay phones can differ by at most 800 ms from network delay,
    // leaving 200 ms toward the game's one-second goal for local scheduling.
    // This bounds the network contribution, not total UI or hardware latency.
    static final long MAX_RELAY_SAMPLE_DELAY_MS = 800;
    // Keep a committed offset through short transport interruptions. Normal
    // background batches renew it every 30 seconds; an old estimate must earn
    // a full calibration again rather than accumulate unbounded clock drift.
    static final long MAX_RETAINED_AGE_MS = 5 * 60 * 1000;
    static final long MAX_TIMESTAMP = Long.MAX_VALUE / 4;
    private long offset;
    private long bestRoundTrip = Long.MAX_VALUE;
    private long candidateOffset;
    private long candidateRoundTrip = Long.MAX_VALUE;
    private int samples;
    private boolean hasEstimate;
    private boolean sampling;
    private long calibratedAt = -1;

    GameClock() {
        beginSampling();
    }

    void reset() {
        offset = 0;
        hasEstimate = false;
        bestRoundTrip = Long.MAX_VALUE;
        calibratedAt = -1;
        beginSampling();
    }

    void beginSampling() {
        samples = 0;
        candidateOffset = 0;
        candidateRoundTrip = Long.MAX_VALUE;
        sampling = true;
    }

    boolean record(long clientSend, long hostReceive, long hostSend, long clientReceive) {
        return record(clientSend, hostReceive, hostSend, clientReceive, MAX_SAMPLE_DELAY_MS);
    }

    boolean recordRelay(long clientSend, long hostReceive, long hostSend, long clientReceive) {
        return record(clientSend, hostReceive, hostSend, clientReceive, MAX_RELAY_SAMPLE_DELAY_MS);
    }

    private boolean record(long clientSend, long hostReceive, long hostSend, long clientReceive,
                           long maximumDelay) {
        long roundTrip = sampleDelay(clientSend, hostReceive, hostSend, clientReceive, maximumDelay);
        if (roundTrip < 0)
            return false;
        long measuredOffset = ((hostReceive - clientSend) + (hostSend - clientReceive)) / 2;
        if (sampling) {
            // Select the least-delayed exchange to reduce asymmetric queueing
            // error.  A refresh is collected separately from the currently
            // committed estimate so one mediocre first reply cannot make an
            // already-scheduled game jump before the full batch completes.
            if (roundTrip < candidateRoundTrip) {
                candidateOffset = measuredOffset;
                candidateRoundTrip = roundTrip;
            }
            samples++;
            if (!hasEstimate) {
                // A caller may inspect an initial estimate before all samples
                // arrive, but TcpClient does not publish a round until the full
                // calibration batch completes.
                offset = candidateOffset;
                bestRoundTrip = candidateRoundTrip;
                hasEstimate = true;
            }
            if (samples >= SAMPLES_PER_SYNC) {
                offset = candidateOffset;
                bestRoundTrip = candidateRoundTrip;
                sampling = false;
                calibratedAt = clientReceive;
            }
        } else if (roundTrip < bestRoundTrip) {
            // During a live countdown use only a strictly better one-shot
            // probe.  This makes refinements stable and keeps the host load
            // bounded to one request per phone per second.
            offset = measuredOffset;
            bestRoundTrip = roundTrip;
        }
        return true;
    }

    boolean hasRecentCalibration(long now) {
        return calibratedAt >= 0 && now >= calibratedAt && now - calibratedAt <= MAX_RETAINED_AGE_MS;
    }

    static boolean usableSample(long clientSend, long hostReceive, long hostSend,
                                long clientReceive, boolean relay) {
        return sampleDelay(clientSend, hostReceive, hostSend, clientReceive,
                relay ? MAX_RELAY_SAMPLE_DELAY_MS : MAX_SAMPLE_DELAY_MS) >= 0;
    }

    /** Caller must also verify that this is the same host clock epoch. */
    boolean resume(long clientSend, long hostReceive, long hostSend, long clientReceive, boolean relay) {
        long maximumDelay = relay ? MAX_RELAY_SAMPLE_DELAY_MS : MAX_SAMPLE_DELAY_MS;
        if (!hasRecentCalibration(clientReceive)
                || sampleDelay(clientSend, hostReceive, hostSend, clientReceive, maximumDelay) < 0)
            return false;
        long measuredOffset = ((hostReceive - clientSend) + (hostSend - clientReceive)) / 2;
        if (Math.abs(measuredOffset - offset) > maximumDelay)
            return false;
        // Revalidation does not replace the good estimate with one noisier
        // sample or renew its age. The next complete background batch does.
        sampling = false;
        samples = SAMPLES_PER_SYNC;
        return true;
    }

    private static long sampleDelay(long clientSend, long hostReceive, long hostSend,
                                    long clientReceive, long maximumDelay) {
        if (!validTimestamp(clientSend) || !validTimestamp(hostReceive)
                || !validTimestamp(hostSend) || !validTimestamp(clientReceive)
                || clientReceive < clientSend || hostSend < hostReceive)
            return -1;
        long roundTrip = (clientReceive - clientSend) - (hostSend - hostReceive);
        return roundTrip < 0 || roundTrip > maximumDelay ? -1 : roundTrip;
    }

    int samples() { return samples; }

    long toLocalTime(long hostTime) {
        if (!hasEstimate || !validTimestamp(hostTime))
            throw new IllegalArgumentException("No usable host clock estimate");
        return hostTime - offset;
    }

    static boolean validTimestamp(long timestamp) {
        return timestamp >= 0 && timestamp <= MAX_TIMESTAMP;
    }
}
