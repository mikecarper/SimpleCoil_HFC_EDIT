package com.simplecoil.protocol;

/** A fixed lobby origin with signed 16-bit north/east offsets in 25 mm units. */
public final class GpsOffset {
    public static final double METERS_PER_UNIT = 0.025;
    public static final double SPAN_METERS = 65536 * METERS_PER_UNIT;
    private static final double EARTH_RADIUS_METERS = 6371008.8;

    public final double latitude;
    public final double longitude;
    private final double sinLatitude;
    private final double cosLatitude;

    private GpsOffset(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        sinLatitude = Math.sin(Math.toRadians(latitude));
        cosLatitude = Math.cos(Math.toRadians(latitude));
    }

    public static GpsOffset create(double latitude, double longitude) {
        return validCoordinates(longitude, latitude) ? new GpsOffset(latitude, longitude) : null;
    }

    public static boolean validCoordinates(double longitude, double latitude) {
        // Only the pair (0,0) is the unset-fix sentinel; either axis alone is valid.
        return finite(longitude) && finite(latitude) && (longitude != 0 || latitude != 0)
                && longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** Absolute position -> origin-relative offsets; never clamp or wrap an out-of-area fix. */
    public Position encode(double latitude, double longitude) {
        if (!validCoordinates(longitude, latitude)) return null;
        double lat = Math.toRadians(latitude);
        double deltaLon = Math.toRadians(longitude - this.longitude);
        double east = Math.cos(lat) * Math.sin(deltaLon);
        double north = cosLatitude * Math.sin(lat) - sinLatitude * Math.cos(lat) * Math.cos(deltaLon);
        double sinDistance = Math.hypot(east, north);
        double cosDistance = sinLatitude * Math.sin(lat) + cosLatitude * Math.cos(lat) * Math.cos(deltaLon);
        double distance = Math.atan2(sinDistance, cosDistance);
        if (distance * EARTH_RADIUS_METERS > SPAN_METERS) return null;
        double scale = sinDistance == 0 ? EARTH_RADIUS_METERS
                : EARTH_RADIUS_METERS * distance / sinDistance;
        double n = north * scale / METERS_PER_UNIT;
        double e = east * scale / METERS_PER_UNIT;
        if (n < Short.MIN_VALUE - 1e-6 || n > Short.MAX_VALUE + 1e-6
                || e < Short.MIN_VALUE - 1e-6 || e > Short.MAX_VALUE + 1e-6) return null;
        return new Position((int) Math.round(n), (int) Math.round(e));
    }

    /** Offsets -> absolute position. Spherical projection also handles the date line and poles. */
    public double[] decode(int north, int east) {
        if (north < Short.MIN_VALUE || north > Short.MAX_VALUE
                || east < Short.MIN_VALUE || east > Short.MAX_VALUE) return null;
        if (north == 0 && east == 0) return new double[]{latitude, longitude};
        double n = north * METERS_PER_UNIT;
        double e = east * METERS_PER_UNIT;
        double radius = Math.hypot(n, e);
        double distance = radius / EARTH_RADIUS_METERS;
        double sin = Math.sin(distance);
        double cos = Math.cos(distance);
        // Resolve the rotated unit vector before converting to latitude. asin(z)
        // loses small distances near either pole because z rounds to +/-1.
        double x = cos * cosLatitude - n * sin * sinLatitude / radius;
        double y = e * sin / radius;
        double z = cos * sinLatitude + n * sin * cosLatitude / radius;
        double lat = Math.toDegrees(Math.atan2(z, Math.hypot(x, y)));
        double lon = longitude + Math.toDegrees(Math.atan2(y, x));
        lon = ((lon + 180) % 360 + 360) % 360 - 180;
        return validCoordinates(lon, lat) ? new double[]{lat, lon} : null;
    }

    public boolean sameOrigin(GpsOffset other) {
        return other != null && Double.compare(latitude, other.latitude) == 0
                && Double.compare(longitude, other.longitude) == 0;
    }

    public static final class Position {
        public final int north;
        public final int east;

        private Position(int north, int east) {
            this.north = north;
            this.east = east;
        }
    }
}
