package com.simplecoil.simplecoil;

/** NTP-style offset estimation using monotonic clocks, without changing device time. */
final class GameClock {
    static final int SAMPLES_PER_SYNC = 5;
    static final long MAX_ROUND_TRIP_MS = 5000;
    static final long MAX_TIMESTAMP = Long.MAX_VALUE / 4;
    private long offset;
    private long bestRoundTrip = Long.MAX_VALUE;
    private int samples;
    private boolean hasEstimate;

    void reset() {
        offset = 0;
        hasEstimate = false;
        beginSampling();
    }

    void beginSampling() {
        samples = 0;
        bestRoundTrip = Long.MAX_VALUE;
    }

    boolean record(long clientSend, long hostReceive, long hostSend, long clientReceive) {
        if (!validTimestamp(clientSend) || !validTimestamp(hostReceive)
                || !validTimestamp(hostSend) || !validTimestamp(clientReceive)
                || clientReceive < clientSend || hostSend < hostReceive)
            return false;
        long roundTrip = (clientReceive - clientSend) - (hostSend - hostReceive);
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MS)
            return false;
        // Select the least-delayed exchange to reduce asymmetric queueing error.
        if (roundTrip < bestRoundTrip) {
            offset = ((hostReceive - clientSend) + (hostSend - clientReceive)) / 2;
            bestRoundTrip = roundTrip;
            hasEstimate = true;
        }
        samples++;
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
