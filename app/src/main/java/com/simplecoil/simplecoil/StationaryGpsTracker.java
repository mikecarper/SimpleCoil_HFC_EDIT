package com.simplecoil.simplecoil;

/** Conservative local stationary-player evidence, independent of Android and network packets. */
final class StationaryGpsTracker {
    static final long STATIONARY_MS = 20_000L;
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private boolean tracking;
    private double anchorLongitude;
    private double anchorLatitude;
    private float anchorAccuracyMeters;
    private long stationaryObservedAt;
    private long stationaryFixElapsedMs;
    private long lastFixElapsedMs;
    private long lastClockElapsedMs;

    void observe(boolean reliable, double longitude, double latitude, float accuracyMeters,
            long fixElapsedMs, long nowMs) {
        if (!reliable || !validCoordinates(longitude, latitude)
                || !InactivityGpsPolicy.isReliable(true, accuracyMeters, fixElapsedMs, nowMs)
                || (tracking && nowMs < lastClockElapsedMs)) {
            reset();
            return;
        }
        lastClockElapsedMs = nowMs;
        if (!tracking) {
            begin(longitude, latitude, accuracyMeters, fixElapsedMs, nowMs);
            return;
        }
        // A cached heartbeat or out-of-order fix is not new evidence of standing still.
        if (fixElapsedMs <= lastFixElapsedMs)
            return;
        // Do not carry stationary evidence across a gap in measured or received good fixes.
        if (fixElapsedMs - lastFixElapsedMs > InactivityGpsPolicy.MAX_FIX_AGE_MS
                || nowMs - lastFixElapsedMs > InactivityGpsPolicy.MAX_FIX_AGE_MS) {
            begin(longitude, latitude, accuracyMeters, fixElapsedMs, nowMs);
            return;
        }
        float movementMeters = InactivityGpsPolicy.movementThresholdMeters(
                anchorAccuracyMeters, accuracyMeters);
        if (distanceMeters(anchorLongitude, anchorLatitude, longitude, latitude) >= movementMeters) {
            begin(longitude, latitude, accuracyMeters, fixElapsedMs, nowMs);
            return;
        }
        // Retain the original anchor so small successive steps cannot hide actual movement.
        lastFixElapsedMs = fixElapsedMs;
    }

    boolean isStationary(long nowMs) {
        if (!tracking)
            return false;
        if (nowMs < lastClockElapsedMs
                || !InactivityGpsPolicy.isReliable(true, anchorAccuracyMeters, lastFixElapsedMs, nowMs)) {
            reset();
            return false;
        }
        lastClockElapsedMs = nowMs;
        // Both live observation time and genuine measurement progress must span the grace period.
        return nowMs - stationaryObservedAt >= STATIONARY_MS
                && lastFixElapsedMs - stationaryFixElapsedMs >= STATIONARY_MS;
    }

    void reset() {
        tracking = false;
        anchorLongitude = Double.NaN;
        anchorLatitude = Double.NaN;
        anchorAccuracyMeters = Float.NaN;
        stationaryObservedAt = 0L;
        stationaryFixElapsedMs = 0L;
        lastFixElapsedMs = 0L;
        lastClockElapsedMs = 0L;
    }

    private void begin(double longitude, double latitude, float accuracyMeters,
            long fixElapsedMs, long nowMs) {
        tracking = true;
        anchorLongitude = longitude;
        anchorLatitude = latitude;
        anchorAccuracyMeters = accuracyMeters;
        stationaryObservedAt = nowMs;
        stationaryFixElapsedMs = fixElapsedMs;
        lastFixElapsedMs = fixElapsedMs;
        lastClockElapsedMs = nowMs;
    }

    private static boolean validCoordinates(double longitude, double latitude) {
        return !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && longitude >= -180.0 && longitude <= 180.0
                && latitude >= -90.0 && latitude <= 90.0
                && (longitude != 0.0 || latitude != 0.0);
    }

    private static double distanceMeters(double firstLongitude, double firstLatitude,
            double secondLongitude, double secondLatitude) {
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double halfLatitudeDifference = Math.sin((secondLatitudeRadians - firstLatitudeRadians) / 2.0);
        double halfLongitudeDifference = Math.sin(Math.toRadians(secondLongitude - firstLongitude) / 2.0);
        double haversine = halfLatitudeDifference * halfLatitudeDifference
                + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
                * halfLongitudeDifference * halfLongitudeDifference;
        // Rounding at antipodal points must not feed sqrt/asin a value outside their domains.
        return 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, haversine))));
    }
}
