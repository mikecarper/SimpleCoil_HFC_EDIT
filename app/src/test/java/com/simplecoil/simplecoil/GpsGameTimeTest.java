package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpsGameTimeTest {
    private static final long GPS_UTC = 1_800_000_000_000L;

    @Test
    public void convertsFreshGpsUtcToAndFromMonotonicTime() {
        GpsGameTime time = new GpsGameTime();
        time.recordGpsFix(GPS_UTC, 1_000, 1_020);

        assertEquals(GPS_UTC + 9_000, time.utcTimeAtElapsed(10_000, 10_000));
        assertEquals(10_000, time.elapsedTimeForUtc(GPS_UTC + 9_000, 10_000));
    }

    @Test
    public void rejectsInvalidAndStaleFixes() {
        GpsGameTime time = new GpsGameTime();
        time.recordGpsFix(0, 1_000, 1_000);
        assertEquals(GpsGameTime.INVALID_TIME, time.utcTimeAtElapsed(2_000, 2_000));

        time.recordGpsFix(GPS_UTC, 1_000, 62_000);
        assertEquals(GpsGameTime.INVALID_TIME, time.utcTimeAtElapsed(2_000, 2_000));
    }

    @Test
    public void remoteClockSampleActsAsARecentHostReference() {
        GpsGameTime time = new GpsGameTime();
        time.recordNetworkGpsTime(GPS_UTC, 4_000);

        assertTrue(GpsGameTime.isValidUtcTime(time.utcTimeAtElapsed(4_500, 4_500)));
        assertEquals(4_500, time.elapsedTimeForUtc(GPS_UTC + 500, 4_500));
        assertFalse(GpsGameTime.isValidUtcTime(0));
    }
}
