package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StationaryGpsTrackerTest {
    private static final double LONGITUDE = -122.0;
    private static final double LATITUDE = 47.0;
    private static final double METERS_PER_LATITUDE_DEGREE = Math.PI * 6_371_008.8 / 180.0;

    @Test public void stationaryGraceIsTwentySeconds() {
        assertEquals(20_000L, StationaryGpsTracker.STATIONARY_MS);
        assertFalse(new StationaryGpsTracker().isStationary(1_000));
    }

    @Test public void freshAdvancingFixesBecomeStationaryAtTheInclusiveBoundary() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        observe(tracker, 6_000);
        observe(tracker, 11_000);
        observe(tracker, 20_999);
        assertFalse(tracker.isStationary(20_999));
        observe(tracker, 21_000);
        assertTrue(tracker.isStationary(21_000));
    }

    @Test public void cachedHeartbeatsCannotEstablishStationaryEvidence() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 1_000, 6_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 1_000, 11_000);
        assertFalse(tracker.isStationary(11_000));
        assertFalse(tracker.isStationary(11_001));
        observe(tracker, 11_001);
        observe(tracker, 21_001);
        assertFalse(tracker.isStationary(21_001));
        observe(tracker, 31_001);
        assertTrue(tracker.isStationary(31_001));
    }

    @Test public void delayedFirstObservationDoesNotSkipTheArrivalGrace() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 1_000, 11_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 11_000, 11_000);
        assertFalse(tracker.isStationary(11_000));
        observe(tracker, 16_000);
        observe(tracker, 21_000);
        assertFalse(tracker.isStationary(21_000));
        observe(tracker, 26_000);
        assertFalse(tracker.isStationary(30_999));
        observe(tracker, 31_000);
        assertTrue(tracker.isStationary(31_000));
    }

    @Test public void wallClockGraceAloneDoesNotSubstituteForAdvancingMeasurements() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        observe(tracker, 11_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 20_999, 21_000);
        assertFalse(tracker.isStationary(21_000));
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 21_000, 21_001);
        assertTrue(tracker.isStationary(21_001));
    }

    @Test public void staleEvidenceExpiresAndNewFixStartsAFreshGrace() {
        StationaryGpsTracker tracker = stationaryTracker();
        assertTrue(tracker.isStationary(31_000));
        assertFalse(tracker.isStationary(31_001));
        observe(tracker, 31_001);
        observe(tracker, 41_001);
        observe(tracker, 51_000);
        assertFalse(tracker.isStationary(51_000));
        observe(tracker, 51_001);
        assertTrue(tracker.isStationary(51_001));
    }

    @Test public void aMeasuredFixGapOverTenSecondsRestartsTheGrace() {
        StationaryGpsTracker tracker = stationaryTracker();
        observe(tracker, 31_001);
        assertFalse(tracker.isStationary(31_001));
        observe(tracker, 41_001);
        observe(tracker, 51_001);
        assertTrue(tracker.isStationary(51_001));
    }

    @Test public void anUnobservedFreshnessGapRestartsEvenWhenMeasuredFixesAreClose() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 22_000, 31_001);
        assertFalse(tracker.isStationary(31_001));
        observe(tracker, 31_500);
        observe(tracker, 36_000);
        observe(tracker, 41_001);
        observe(tracker, 46_000);
        observe(tracker, 51_001);
        assertTrue(tracker.isStationary(51_001));
    }

    @Test public void duplicateAndOlderReliableSamplesDoNotMoveTheAnchorOrResetTheGrace() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(true, LONGITUDE, latitudeAtMeters(100), 1f, 21_000, 21_001);
        assertTrue(tracker.isStationary(21_001));
        tracker.observe(true, LONGITUDE, latitudeAtMeters(100), 1f, 20_999, 21_002);
        assertTrue(tracker.isStationary(21_002));
        observe(tracker, 22_000);
        assertTrue(tracker.isStationary(22_000));
    }

    @Test public void duplicateUnreliableSampleStillClearsEvidence() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(false, LONGITUDE, LATITUDE, 1f, 21_000, 21_001);
        assertFalse(tracker.isStationary(21_001));
        observe(tracker, 21_002);
        observe(tracker, 31_002);
        observe(tracker, 41_002);
        assertTrue(tracker.isStationary(41_002));
    }

    @Test public void jitterBelowTheMinimumDistancePreservesTheStationaryAnchor() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(2.9), 1f, 6_000, 6_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(-2.9), 1f, 11_000, 11_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(2.9), 1f, 21_000, 21_000);
        assertTrue(tracker.isStationary(21_000));
    }

    @Test public void movementBeyondThreeMetersStartsANewGrace() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(true, LONGITUDE, latitudeAtMeters(3.01), 1f, 22_000, 22_000);
        assertFalse(tracker.isStationary(22_000));
        tracker.observe(true, LONGITUDE, latitudeAtMeters(3.01), 1f, 32_000, 32_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(3.01), 1f, 42_000, 42_000);
        assertTrue(tracker.isStationary(42_000));
    }

    @Test public void successiveSmallStepsAreMeasuredFromAFixedAnchor() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(2), 1f, 6_000, 6_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(4), 1f, 11_000, 11_000);
        assertFalse(tracker.isStationary(11_000));
        tracker.observe(true, LONGITUDE, latitudeAtMeters(4), 1f, 21_000, 21_000);
        assertFalse(tracker.isStationary(21_000));
        tracker.observe(true, LONGITUDE, latitudeAtMeters(4), 1f, 31_000, 31_000);
        assertTrue(tracker.isStationary(31_000));
    }

    @Test public void movementMustExceedCombinedEndpointAccuracy() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        tracker.observe(true, LONGITUDE, LATITUDE, 5f, 1_000, 1_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(9.9), 5f, 11_000, 11_000);
        tracker.observe(true, LONGITUDE, latitudeAtMeters(9.9), 5f, 21_000, 21_000);
        assertTrue(tracker.isStationary(21_000));
        tracker.observe(true, LONGITUDE, latitudeAtMeters(10.1), 5f, 22_000, 22_000);
        assertFalse(tracker.isStationary(22_000));
    }

    @Test public void invalidAccuracyOrMissingQualityClearsEvidence() {
        for (float accuracy : new float[]{0f, -1f, 5.001f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            StationaryGpsTracker tracker = stationaryTracker();
            tracker.observe(true, LONGITUDE, LATITUDE, accuracy, 22_000, 22_000);
            assertFalse(tracker.isStationary(22_000));
        }
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(false, LONGITUDE, LATITUDE, 1f, 22_000, 22_000);
        assertFalse(tracker.isStationary(22_000));
    }

    @Test public void nonfiniteOutOfBoundsAndZeroZeroPositionsClearEvidence() {
        for (double[] position : new double[][]{{0, 0}, {-0.0, -0.0}, {180.001, LATITUDE},
                {-180.001, LATITUDE}, {LONGITUDE, 90.001}, {LONGITUDE, -90.001},
                {Double.NaN, LATITUDE}, {LONGITUDE, Double.NaN},
                {Double.POSITIVE_INFINITY, LATITUDE}, {LONGITUDE, Double.NEGATIVE_INFINITY}}) {
            StationaryGpsTracker tracker = stationaryTracker();
            tracker.observe(true, position[0], position[1], 1f, 22_000, 22_000);
            assertFalse(tracker.isStationary(22_000));
        }
    }

    @Test public void validEquatorAndPrimeMeridianPositionsAreNotRejected() {
        for (double[] position : new double[][]{{0, LATITUDE}, {LONGITUDE, 0}, {180, 90}, {-180, -90}}) {
            StationaryGpsTracker tracker = new StationaryGpsTracker();
            tracker.observe(true, position[0], position[1], 1f, 1_000, 1_000);
            tracker.observe(true, position[0], position[1], 1f, 11_000, 11_000);
            tracker.observe(true, position[0], position[1], 1f, 21_000, 21_000);
            assertTrue(tracker.isStationary(21_000));
        }
    }

    @Test public void longitudeWrapIsSmallMovementRatherThanAnEntireEarth() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        tracker.observe(true, 179.999999, 1, 1f, 1_000, 1_000);
        tracker.observe(true, -179.999999, 1, 1f, 11_000, 11_000);
        tracker.observe(true, 179.999999, 1, 1f, 21_000, 21_000);
        assertTrue(tracker.isStationary(21_000));
    }

    @Test public void antipodalMovementDoesNotOverflowTheDistanceCalculation() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(true, 58, -47, 1f, 22_000, 22_000);
        assertFalse(tracker.isStationary(22_000));
    }

    @Test public void invalidStaleAndFutureMeasurementTimesClearEvidence() {
        for (long fix : new long[]{0, -1, Long.MIN_VALUE, 11_999, 22_001, Long.MAX_VALUE}) {
            StationaryGpsTracker tracker = stationaryTracker();
            tracker.observe(true, LONGITUDE, LATITUDE, 1f, fix, 22_000);
            assertFalse(tracker.isStationary(22_000));
        }
    }

    @Test public void backwardClockOnObservationOrQueryClearsEvidence() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, 20_999, 20_999);
        assertFalse(tracker.isStationary(21_000));
        tracker = stationaryTracker();
        assertFalse(tracker.isStationary(20_999));
        assertFalse(tracker.isStationary(21_000));
    }

    @Test public void extremeClockValuesCannotWrapIntoAFreshStationaryResult() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, Long.MAX_VALUE - 20_000, Long.MAX_VALUE - 20_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, Long.MAX_VALUE - 10_000, Long.MAX_VALUE - 10_000);
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, Long.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(tracker.isStationary(Long.MAX_VALUE));
        assertFalse(tracker.isStationary(Long.MIN_VALUE));
        tracker = stationaryTracker();
        assertFalse(tracker.isStationary(Long.MAX_VALUE));
    }

    @Test public void resetRequiresEntirelyNewStationaryEvidence() {
        StationaryGpsTracker tracker = stationaryTracker();
        tracker.reset();
        assertFalse(tracker.isStationary(21_000));
        observe(tracker, 22_000);
        assertFalse(tracker.isStationary(22_000));
        observe(tracker, 32_000);
        observe(tracker, 42_000);
        assertTrue(tracker.isStationary(42_000));
    }

    private static StationaryGpsTracker stationaryTracker() {
        StationaryGpsTracker tracker = new StationaryGpsTracker();
        observe(tracker, 1_000);
        observe(tracker, 11_000);
        observe(tracker, 21_000);
        assertTrue(tracker.isStationary(21_000));
        return tracker;
    }

    private static void observe(StationaryGpsTracker tracker, long elapsedMs) {
        tracker.observe(true, LONGITUDE, LATITUDE, 1f, elapsedMs, elapsedMs);
    }

    private static double latitudeAtMeters(double northMeters) {
        return LATITUDE + northMeters / METERS_PER_LATITUDE_DEGREE;
    }
}
