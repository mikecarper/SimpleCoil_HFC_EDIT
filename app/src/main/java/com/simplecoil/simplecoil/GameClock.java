package com.simplecoil.simplecoil;

/** NTP-style offset estimation using monotonic clocks, without changing device time. */
final class GameClock {
    // A short burst of measurements gives NTP's lowest-round-trip selection a
    // much better chance of avoiding a Wi-Fi queueing spike. Twelve exchanges
    // still finish in well under a second on the game LAN.
    static final int SAMPLES_PER_SYNC = 12;
    static final long MAX_ROUND_TRIP_MS = 5000;
    // With an NTP-style midpoint, an otherwise unknowable asymmetric path can
    // move the result by at most half the measured network round trip. Reject
    // anything above 80 ms so a completed sync has at most 40 ms of network
    // uncertainty relative to the host (before normal local scheduling jitter).
    static final long MAX_SAMPLE_DELAY_MS = 80;
    static final long MAX_TIMESTAMP = Long.MAX_VALUE / 4;
    private long offset;
    private long bestRoundTrip = Long.MAX_VALUE;
    private long candidateOffset;
    private long candidateRoundTrip = Long.MAX_VALUE;
    private int samples;
    private boolean hasEstimate;
    private boolean sampling;

    GameClock() {
        beginSampling();
    }

    void reset() {
        offset = 0;
        hasEstimate = false;
        bestRoundTrip = Long.MAX_VALUE;
        beginSampling();
    }

    void beginSampling() {
        samples = 0;
        candidateOffset = 0;
        candidateRoundTrip = Long.MAX_VALUE;
        sampling = true;
    }

    boolean record(long clientSend, long hostReceive, long hostSend, long clientReceive) {
        if (!validTimestamp(clientSend) || !validTimestamp(hostReceive)
                || !validTimestamp(hostSend) || !validTimestamp(clientReceive)
                || clientReceive < clientSend || hostSend < hostReceive)
            return false;
        long roundTrip = (clientReceive - clientSend) - (hostSend - hostReceive);
        if (roundTrip < 0 || roundTrip > MAX_SAMPLE_DELAY_MS)
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
