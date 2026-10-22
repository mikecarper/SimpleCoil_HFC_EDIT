package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InactivityGpsPolicyTest {
    @Test public void qualityLimitsMatchTheInactivityContract() {
        assertEquals(10_000L, InactivityGpsPolicy.MAX_FIX_AGE_MS);
        assertEquals(5f, InactivityGpsPolicy.MAX_ACCURACY_METERS, 0f);
        assertEquals(3f, InactivityGpsPolicy.MIN_MOVEMENT_METERS, 0f);
    }

    @Test public void onlySatelliteProviderFixesAreReliable() {
        assertTrue(InactivityGpsPolicy.isReliable(true, 1f, 1_000, 1_000));
        assertFalse(InactivityGpsPolicy.isReliable(false, 1f, 1_000, 1_000));
    }

    @Test public void accuracyMustBeFinitePositiveAndNoWorseThanFiveMeters() {
        for (float accuracy : new float[]{Float.MIN_VALUE, 1f, 4.999f, 5f})
            assertTrue(InactivityGpsPolicy.isReliable(true, accuracy, 1_000, 1_000));
        for (float accuracy : invalidAccuracies())
            assertFalse(InactivityGpsPolicy.isReliable(true, accuracy, 1_000, 1_000));
    }

    @Test public void tenSecondAgeBoundaryIsInclusive() {
        assertTrue(InactivityGpsPolicy.isReliable(true, 5f, 1_000, 1_000));
        assertTrue(InactivityGpsPolicy.isReliable(true, 5f, 1_000, 10_999));
        assertTrue(InactivityGpsPolicy.isReliable(true, 5f, 1_000, 11_000));
        assertFalse(InactivityGpsPolicy.isReliable(true, 5f, 1_000, 11_001));
    }

    @Test public void unsetNegativeAndFutureMeasurementTimesAreRejected() {
        for (long fix : new long[]{0, -1, Long.MIN_VALUE})
            assertFalse(InactivityGpsPolicy.isReliable(true, 1f, fix, 1_000));
        for (long now : new long[]{0, -1, Long.MIN_VALUE})
            assertFalse(InactivityGpsPolicy.isReliable(true, 1f, 1, now));
        assertFalse(InactivityGpsPolicy.isReliable(true, 1f, 1_001, 1_000));
    }

    @Test public void extremeClockValuesCannotWrapIntoAFreshFix() {
        assertTrue(InactivityGpsPolicy.isReliable(true, 1f, Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(InactivityGpsPolicy.isReliable(true, 1f,
                Long.MAX_VALUE - 10_000L, Long.MAX_VALUE));
        assertFalse(InactivityGpsPolicy.isReliable(true, 1f,
                Long.MAX_VALUE - 10_001L, Long.MAX_VALUE));
        assertFalse(InactivityGpsPolicy.isReliable(true, 1f, 1, Long.MAX_VALUE));
        assertFalse(InactivityGpsPolicy.isReliable(true, 1f, Long.MAX_VALUE, Long.MIN_VALUE));
        assertFalse(InactivityGpsPolicy.isReliable(true, 1f, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test public void preciseFixesStillRequireThreeMetersOfMovement() {
        assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(0.5f, 0.5f), 0f);
        assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(1f, 2f), 0f);
        assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(Float.MIN_VALUE, Float.MIN_VALUE), 0f);
    }

    @Test public void movementMustExceedTheCombinedAccuracyUncertainty() {
        assertEquals(4f, InactivityGpsPolicy.movementThresholdMeters(2f, 2f), 0f);
        assertEquals(5f, InactivityGpsPolicy.movementThresholdMeters(2f, 3f), 0f);
        assertEquals(5f, InactivityGpsPolicy.movementThresholdMeters(3f, 2f), 0f);
        assertEquals(10f, InactivityGpsPolicy.movementThresholdMeters(5f, 5f), 0f);
    }

    @Test public void invalidUncertaintyInEitherFixUsesTheMinimumFallback() {
        for (float accuracy : invalidAccuracies()) {
            assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(accuracy, 5f), 0f);
            assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(5f, accuracy), 0f);
            assertEquals(3f, InactivityGpsPolicy.movementThresholdMeters(accuracy, accuracy), 0f);
        }
    }

    private static float[] invalidAccuracies() {
        return new float[]{0f, -0f, -1f, 5.001f, Float.MAX_VALUE,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
    }
}
