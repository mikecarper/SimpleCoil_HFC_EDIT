package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GpsOffset;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GpsWireRegressionTest {
    @Test public void lobbyOriginAndZeroOffsetsRoundTrip() throws Exception {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        JSONObject message = new JSONObject();
        GpsWire.putOrigin(message, origin);
        GpsWire.putPosition(message, origin, origin.latitude, origin.longitude);
        message = new JSONObject(message.toString());
        assertTrue(origin.sameOrigin(GpsWire.readOrigin(message)));
        assertEquals(0, message.getInt(GpsWire.NORTH));
        assertEquals(0, message.getInt(GpsWire.EAST));
        assertArrayEquals(new double[]{47.6, -122.1},
                GpsWire.readPosition(message, GpsWire.readOrigin(message)), 0);
    }

    @Test public void bootstrapAndOutOfAreaFixesRetainAbsolutePrecision() throws Exception {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        for (GpsOffset selected : new GpsOffset[]{null, origin}) {
            JSONObject message = new JSONObject();
            GpsWire.putOrigin(message, selected);
            GpsWire.putPosition(message, selected, 47.7123456789, -122.2123456789);
            assertFalse(message.has(GpsWire.NORTH));
            assertArrayEquals(new double[]{47.7123456789, -122.2123456789},
                    GpsWire.readPosition(new JSONObject(message.toString()), selected), 0);
        }
    }

    @Test public void nullIslandIsInvalidAsAnOriginOrPosition() throws Exception {
        JSONObject message = new JSONObject().put(GpsWire.ORIGIN, new JSONObject()
                .put(TcpServer.JSON_GPSLATITUDE, 0).put(TcpServer.JSON_GPSLONGITUDE, 0));
        try { GpsWire.readOrigin(message); fail("Null Island origin accepted"); }
        catch (JSONException expected) { }
        try { GpsWire.readPosition(message.getJSONObject(GpsWire.ORIGIN), null);
            fail("Null Island fix accepted"); }
        catch (JSONException expected) { }
    }

    @Test public void equatorAndPrimeMeridianFixesRoundTripInBothWireForms() throws Exception {
        for (double[] fix : new double[][]{{0, 51.5}, {51.5, 0}}) {
            GpsOffset origin = GpsOffset.create(fix[0], fix[1]);
            assertNotNull(origin);
            for (GpsOffset selected : new GpsOffset[]{null, origin}) {
                JSONObject message = new JSONObject();
                GpsWire.putOrigin(message, selected);
                GpsWire.putPosition(message, selected, fix[0], fix[1]);
                message = new JSONObject(message.toString());
                assertArrayEquals(fix,
                        GpsWire.readPosition(message, GpsWire.readOrigin(message)), 0);
            }
        }
    }

    @Test public void relativePositionsRequireAnOriginAndBothSignedOffsets() throws Exception {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        for (JSONObject message : new JSONObject[]{new JSONObject().put(GpsWire.NORTH, 1),
                new JSONObject().put(GpsWire.NORTH, 32768).put(GpsWire.EAST, 0),
                new JSONObject().put(GpsWire.NORTH, 1.5).put(GpsWire.EAST, 0)}) {
            try { GpsWire.readPosition(message, origin); fail("Invalid offset accepted"); }
            catch (JSONException expected) { }
        }
        try { GpsWire.readPosition(new JSONObject().put(GpsWire.NORTH, 0).put(GpsWire.EAST, 0), null);
            fail("Unconfigured offset accepted"); }
        catch (JSONException expected) { }
    }
}
