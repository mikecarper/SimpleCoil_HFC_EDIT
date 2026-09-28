package com.simplecoil.protocol;

/** Monotonic-time policy shared by phone and laptop hosts. */
public final class RoundRestart {
    public static final long EARLY_ROUND_MS = 60_000;
    public static final long NORMAL_WAIT_MS = 30_000;

    private RoundRestart() { }

    public static long allowedAt(long now, long startAt) {
        // Countdown cancellations qualify too. Unknown start time does not.
        return startAt >= 0 && now - startAt < EARLY_ROUND_MS ? now : now + NORMAL_WAIT_MS;
    }
}
