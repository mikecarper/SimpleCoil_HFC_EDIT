package com.simplecoil.protocol;

/** Shared initial round timing; death/respawn timers are independent. */
public final class GameStartCountdown {
    public static final int SPOKEN_MAX = 10;
    public static final long VOICE_STEP_MS = 2_000L;
    public static final long ENDING_VOICE_MS = 3_000L;
    public static final long DURATION_MS = SPOKEN_MAX * VOICE_STEP_MS + ENDING_VOICE_MS;
    public static final int DURATION_SECONDS = (int) (DURATION_MS / 1_000L);

    private GameStartCountdown() {}

    /** Count for 20 seconds, then allow three seconds for the closing line before play. */
    public static int numberForRemainingMillis(long remainingMillis) {
        if (remainingMillis <= ENDING_VOICE_MS) return 0;
        // Subtract before rounding up so even Long.MAX_VALUE cannot overflow.
        long number = 1 + (remainingMillis - ENDING_VOICE_MS - 1) / VOICE_STEP_MS;
        return (int) Math.min(number, Integer.MAX_VALUE);
    }

    /** Align a measured closing line with the unchanged start deadline. */
    public static long closingVoiceDelayMillis(long remainingMillis, long closingDurationMillis) {
        long duration = closingDurationMillis > 0 && closingDurationMillis <= ENDING_VOICE_MS
                ? closingDurationMillis : ENDING_VOICE_MS;
        // Compare before subtracting so negative/extreme remaining values cannot overflow.
        return remainingMillis > duration ? remainingMillis - duration : 0L;
    }
}
