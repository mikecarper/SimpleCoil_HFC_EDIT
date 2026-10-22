package com.simplecoil.simplecoil;

/** GPS quality and uncertainty rules for inactivity reminders, independent of Android. */
final class InactivityGpsPolicy {
    static final long MAX_FIX_AGE_MS = 10_000L;
    static final float MAX_ACCURACY_METERS = 5f;
    static final float MIN_MOVEMENT_METERS = 3f;

    private InactivityGpsPolicy() { }

    static boolean isReliable(boolean satelliteProvider, float accuracyMeters,
            long fixElapsedMs, long nowMs) {
        if (!satelliteProvider || !validAccuracy(accuracyMeters)
                || fixElapsedMs <= 0L || fixElapsedMs > nowMs)
            return false;
        // Both times are now positive and ordered, so this subtraction cannot overflow.
        return nowMs - fixElapsedMs <= MAX_FIX_AGE_MS;
    }

    static float movementThresholdMeters(float anchorAccuracy, float currentAccuracy) {
        if (!validAccuracy(anchorAccuracy) || !validAccuracy(currentAccuracy))
            return MIN_MOVEMENT_METERS;
        // Quality-bounded inputs keep the uncertainty sum finite and at most ten meters.
        return Math.max(MIN_MOVEMENT_METERS, anchorAccuracy + currentAccuracy);
    }

    private static boolean validAccuracy(float accuracy) {
        return !Float.isNaN(accuracy) && !Float.isInfinite(accuracy)
                && accuracy > 0f && accuracy <= MAX_ACCURACY_METERS;
    }
}
