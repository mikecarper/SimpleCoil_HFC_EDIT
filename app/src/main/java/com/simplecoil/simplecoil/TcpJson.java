package com.simplecoil.simplecoil;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;

/** Bounds untrusted protocol data before Android's recursive JSON parser sees it. */
final class TcpJson {
    static final int MAX_NESTING_DEPTH = 16;
    // Matches player_name_dialog.xml and keeps a full roster within writeUTF's frame limit.
    static final int MAX_PLAYER_NAME_LENGTH = 20;
    private static final int MAX_MESSAGE_LENGTH = 65535;

    private TcpJson() { }

    static JSONObject parseObject(String message) throws JSONException {
        if (message == null || message.length() > MAX_MESSAGE_LENGTH)
            throw new JSONException("Invalid network JSON size");
        // A small frame can still contain thousands of nested arrays or objects. Walk
        // tokens iteratively first; do not catch StackOverflowError after parsing it.
        try (JsonReader reader = new JsonReader(new StringReader(message))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT)
                throw new JSONException("Network JSON must be an object");
            int depth = 0;
            JsonToken token;
            while ((token = reader.peek()) != JsonToken.END_DOCUMENT) {
                switch (token) {
                    case BEGIN_OBJECT:
                    case BEGIN_ARRAY:
                        if (++depth > MAX_NESTING_DEPTH)
                            throw new JSONException("Network JSON is nested too deeply");
                        if (token == JsonToken.BEGIN_OBJECT) reader.beginObject();
                        else reader.beginArray();
                        break;
                    case END_OBJECT:
                        reader.endObject();
                        depth--;
                        break;
                    case END_ARRAY:
                        reader.endArray();
                        depth--;
                        break;
                    case NAME:
                        reader.nextName();
                        break;
                    case STRING:
                    case NUMBER:
                        reader.nextString();
                        break;
                    case BOOLEAN:
                        reader.nextBoolean();
                        break;
                    case NULL:
                        reader.nextNull();
                        break;
                    default:
                        throw new JSONException("Unexpected network JSON token");
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new JSONException("Malformed network JSON");
        }
        return new JSONObject(message);
    }

    static String getPlayerName(JSONObject player, String key) throws JSONException {
        Object value = player.get(key);
        // getString() coerces null, numbers and containers into player names.
        if (!(value instanceof String) || ((String) value).length() > MAX_PLAYER_NAME_LENGTH)
            throw new JSONException("Invalid player name");
        return (String) value;
    }

    static int getInt(JSONObject object, String key) throws JSONException {
        long value = getLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new JSONException("Network integer is out of range: " + key);
        return (int) value;
    }

    static long getLong(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        // Integer protocol fields are emitted as JSON integer literals. Android's
        // getters also accept strings and doubles, truncating fractions or wrapping
        // oversized integers before the caller's ID/settings validation can run.
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new JSONException("Expected a network integer: " + key);
        return ((Number) value).longValue();
    }
}
