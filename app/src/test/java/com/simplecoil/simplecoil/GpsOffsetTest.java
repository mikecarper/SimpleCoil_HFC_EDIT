package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GpsOffset;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpsOffsetTest {
    @Test public void wholeSquareUsesBothBytesAndPreservesCentimeterResolution() {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        assertNotNull(origin);
        assertTrue(GpsOffset.SPAN_METERS > 1609.344);
        for (int n : new int[]{Short.MIN_VALUE, -32123, -1, 0, 1, 32123, Short.MAX_VALUE}) {
            for (int e : new int[]{Short.MIN_VALUE, -32123, -1, 0, 1, 32123, Short.MAX_VALUE}) {
                double[] location = origin.decode(n, e);
                assertNotNull(location);
                GpsOffset.Position position = origin.encode(location[0], location[1]);
                assertNotNull(position);
                assertEquals(n, position.north);
                assertEquals(e, position.east);
            }
        }
    }

    @Test public void zeroOffsetsMeanTheRealOriginAndNullIslandStaysInvalid() {
        assertNull(GpsOffset.create(0, 0));
        assertFalse(Globals.isValidCoordinates(0, 0));
        GpsOffset origin = GpsOffset.create(-33.9, 151.2);
        GpsOffset.Position position = origin.encode(-33.9, 151.2);
        assertEquals(0, position.north);
        assertEquals(0, position.east);
        assertArrayEquals(new double[]{-33.9, 151.2}, origin.decode(0, 0), 0);
        assertNull(origin.encode(0, 0));
        assertNull(origin.encode(Double.NaN, 151.2));
        assertNull(origin.encode(-33.9, Double.POSITIVE_INFINITY));
    }

    @Test public void outOfAreaCoordinatesNeverClampOrWrap() {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        assertNull(origin.encode(47.7, -122.1));
        assertNull(origin.encode(47.6, -122.2));
        assertNull(origin.decode(32768, 0));
        assertNull(origin.decode(0, -32769));
    }

    @Test public void dateLineAndPolarFieldsRoundTrip() {
        for (GpsOffset origin : new GpsOffset[]{GpsOffset.create(10, 179.999),
                GpsOffset.create(89.999, 50), GpsOffset.create(-89.999, -50)}) {
            double[] location = origin.decode(23000, 31000);
            assertNotNull(location);
            GpsOffset.Position position = origin.encode(location[0], location[1]);
            assertNotNull(position);
            assertEquals(23000, position.north);
            assertEquals(31000, position.east);
        }
    }

    @Test public void centimeterOffsetsAndSquareEdgesSurviveExactAndNearPolarOrigins() {
        for (double latitude : new double[]{90, -90, 89.999999, -89.999999}) {
            GpsOffset origin = GpsOffset.create(latitude, 50);
            for (int north : new int[]{Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE}) {
                for (int east : new int[]{Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE}) {
                    double[] location = origin.decode(north, east);
                    assertNotNull(location);
                    GpsOffset.Position position = origin.encode(location[0], location[1]);
                    assertNotNull(position);
                    assertEquals(north, position.north);
                    assertEquals(east, position.east);
                }
            }
        }
    }

    @Test public void equatorAndPrimeMeridianOriginsRetainValidZeroAxisFixes() {
        for (double[] coordinates : new double[][]{{0, 20}, {50, 0}}) {
            GpsOffset origin = GpsOffset.create(coordinates[0], coordinates[1]);
            assertNotNull(origin);
            assertTrue(Globals.isValidCoordinates(coordinates[1], coordinates[0]));
            assertArrayEquals(coordinates, origin.decode(0, 0), 0);
            for (int offset : new int[]{Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE}) {
                int north = origin.longitude == 0 ? offset : 0;
                int east = origin.latitude == 0 ? offset : 0;
                double[] location = origin.decode(north, east);
                assertNotNull(location);
                assertEquals(0, origin.latitude == 0 ? location[0] : location[1], 0);
                GpsOffset.Position position = origin.encode(location[0], location[1]);
                assertNotNull(position);
                assertEquals(north, position.north);
                assertEquals(east, position.east);
            }
        }
    }

    @Test public void playingFieldsCanCrossEitherAxisWithoutLosingFixes() {
        for (double[] field : new double[][]{{0.001, 20, -0.001, 20},
                {50, 0.001, 50, -0.001}}) {
            GpsOffset origin = GpsOffset.create(field[0], field[1]);
            GpsOffset.Position position = origin.encode(field[2], field[3]);
            assertNotNull(position);
            double[] location = origin.decode(position.north, position.east);
            assertNotNull(location);
            assertEquals(field[2], location[0], 0.0000002);
            assertEquals(field[3], location[1], 0.0000002);
        }
    }

    @Test public void invalidSentinelNonFiniteAndOutOfRangeCoordinatesRemainRejected() {
        for (double[] coordinates : new double[][]{{0, 0}, {-0.0, 0}, {0, -0.0},
                {Double.NaN, 20}, {50, Double.NaN}, {Double.POSITIVE_INFINITY, 20},
                {50, Double.NEGATIVE_INFINITY}, {90.00001, 20}, {-90.00001, 20},
                {50, 180.00001}, {50, -180.00001}}) {
            assertFalse(GpsOffset.validCoordinates(coordinates[1], coordinates[0]));
            assertNull(GpsOffset.create(coordinates[0], coordinates[1]));
        }
    }
}
