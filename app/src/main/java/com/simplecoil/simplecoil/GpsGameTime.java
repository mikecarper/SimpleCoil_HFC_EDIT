package com.simplecoil.simplecoil;

/**
 * Maps a recent GPS UTC fix onto Android's monotonic elapsed clock. The app
 * never changes the device clock; this mapping is used only to refine an
 * already host-authorized game start when both phones have fresh GPS time.
 */
final class GpsGameTime {
    static final long INVALID_TIME = -1L;
    private static final long EARLIEST_GPS_UTC_MS = 1_577_836_800_000L; // 2020-01-01
    private static final long MAX_FIX_AGE_MS = 60_000L;

    private long utcAtReference = INVALID_TIME;
    private long elapsedAtReference = INVALID_TIME;

    static boolean isValidUtcTime(long utcTime) {
        return utcTime >= EARLIEST_GPS_UTC_MS && utcTime <= GameClock.MAX_TIMESTAMP;
    }

    synchronized void recordGpsFix(long utcTime, long elapsedAtFix, long elapsedNow) {
        if (!isValidUtcTime(utcTime) || elapsedAtFix < 0 || elapsedNow < elapsedAtFix
                || elapsedNow - elapsedAtFix > MAX_FIX_AGE_MS)
            return;
        utcAtReference = utcTime;
        elapsedAtReference = elapsedAtFix;
    }

    /** A peer reports its extrapolated current GPS time during clock sync. */
    synchronized void recordNetworkGpsTime(long utcTime, long elapsedNow) {
        if (!isValidUtcTime(utcTime) || elapsedNow < 0)
            return;
        utcAtReference = utcTime;
        elapsedAtReference = elapsedNow;
    }

    synchronized long utcTimeAtElapsed(long elapsedTime, long elapsedNow) {
        if (!isFresh(elapsedNow) || elapsedTime < 0)
            return INVALID_TIME;
        return add(utcAtReference, elapsedTime - elapsedAtReference);
    }

    synchronized long elapsedTimeForUtc(long utcTime, long elapsedNow) {
        if (!isFresh(elapsedNow) || !isValidUtcTime(utcTime))
            return INVALID_TIME;
        return add(elapsedAtReference, utcTime - utcAtReference);
    }

    private boolean isFresh(long elapsedNow) {
        return utcAtReference != INVALID_TIME && elapsedAtReference >= 0 && elapsedNow >= elapsedAtReference
                && elapsedNow - elapsedAtReference <= MAX_FIX_AGE_MS;
    }

    private static long add(long value, long delta) {
        if ((delta > 0 && value > Long.MAX_VALUE - delta)
                || (delta < 0 && value < Long.MIN_VALUE - delta))
            return INVALID_TIME;
        return value + delta;
    }
}
