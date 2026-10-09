package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GpsOffset;
import org.json.JSONException;
import org.json.JSONObject;

/** GPS setup and origin-relative TCP positions for the current protocol. */
final class GpsWire {
    static final String ORIGIN = "gpsoffset";
    static final String NORTH = "gpsn";
    static final String EAST = "gpse";

    private GpsWire() { }

    static void putOrigin(JSONObject message, GpsOffset origin) throws JSONException {
        message.put(ORIGIN, origin == null ? JSONObject.NULL : new JSONObject()
                .put(TcpServer.JSON_GPSLATITUDE, origin.latitude)
                .put(TcpServer.JSON_GPSLONGITUDE, origin.longitude));
    }

    static GpsOffset readOrigin(JSONObject message) throws JSONException {
        if (message.isNull(ORIGIN)) return null;
        JSONObject value = message.getJSONObject(ORIGIN);
        GpsOffset origin = GpsOffset.create(value.getDouble(TcpServer.JSON_GPSLATITUDE),
                value.getDouble(TcpServer.JSON_GPSLONGITUDE));
        if (origin == null) throw new JSONException("Invalid GPS origin");
        return origin;
    }

    static void putPosition(JSONObject message, GpsOffset origin, double latitude, double longitude)
            throws JSONException {
        GpsOffset.Position position = origin == null ? null : origin.encode(latitude, longitude);
        if (position == null) {
            // Bootstrap and positions outside the square retain their full absolute coordinates.
            message.put(TcpServer.JSON_GPSLATITUDE, latitude);
            message.put(TcpServer.JSON_GPSLONGITUDE, longitude);
        } else {
            message.put(NORTH, position.north);
            message.put(EAST, position.east);
        }
    }

    static double[] readPosition(JSONObject message, GpsOffset origin) throws JSONException {
        double[] position;
        if (message.has(NORTH) || message.has(EAST)) {
            position = origin == null ? null : origin.decode(TcpJson.getInt(message, NORTH),
                    TcpJson.getInt(message, EAST));
        } else {
            position = new double[]{message.getDouble(TcpServer.JSON_GPSLATITUDE),
                    message.getDouble(TcpServer.JSON_GPSLONGITUDE)};
        }
        if (position == null || !Globals.isValidCoordinates(position[1], position[0]))
            throw new JSONException("Invalid GPS position");
        return position;
    }
}
