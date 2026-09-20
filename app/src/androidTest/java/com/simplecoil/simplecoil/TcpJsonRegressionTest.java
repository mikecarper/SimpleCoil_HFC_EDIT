package com.simplecoil.simplecoil;

import android.content.res.XmlResourceParser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Checks the input bound without recursive test-fixture construction or parsing. */
@RunWith(AndroidJUnit4.class)
public class TcpJsonRegressionTest {
    @Test
    public void arraysAtTheDepthLimitStillParse() throws Exception {
        assertTrue(TcpJson.parseObject(TcpInputTestData.nested(TcpJson.MAX_NESTING_DEPTH - 1, true)).has("unused"));
    }

    @Test
    public void objectsAtTheDepthLimitStillParse() throws Exception {
        assertTrue(TcpJson.parseObject(TcpInputTestData.nested(TcpJson.MAX_NESTING_DEPTH - 1, false)).has("unused"));
    }

    @Test
    public void arraysBeyondTheDepthLimitAreRejected() throws Exception {
        assertRejected(TcpInputTestData.nested(TcpJson.MAX_NESTING_DEPTH, true));
    }

    @Test
    public void objectsBeyondTheDepthLimitAreRejected() throws Exception {
        assertRejected(TcpInputTestData.nested(TcpJson.MAX_NESTING_DEPTH, false));
    }

    @Test
    public void mixedNestingCannotBypassTheDepthLimit() throws Exception {
        StringBuilder json = new StringBuilder("{\"unused\":");
        for (int i = 0; i < 5000; i++) json.append("[{\"x\":");
        json.append('0');
        for (int i = 0; i < 5000; i++) json.append("}]");
        json.append('}');
        TcpInputTestData.assertFitsFrame(json.toString());
        assertRejected(json.toString());
    }

    @Test
    public void bracketsInsideStringsDoNotCountAsNesting() throws Exception {
        String value = TcpInputTestData.nested(5000, true);
        JSONObject source = new JSONObject().put("unused", value);
        assertEquals(value, TcpJson.parseObject(source.toString()).getString("unused"));
    }

    @Test
    public void escapedQuotesBackslashesAndBracketsInKeysAndValuesStillParse() throws Exception {
        String value = "\\\"[]{}' // /* #";
        JSONObject source = new JSONObject().put(value, value);
        assertEquals(value, TcpJson.parseObject(source.toString()).getString(value));
        assertEquals("[{}]", TcpJson.parseObject("{\"value\":\"\\u005b\\u007b\\u007d\\u005d\"}").getString("value"));
    }

    @Test
    public void completeShallowMessagesKeepAllScalarTypes() throws Exception {
        JSONObject json = TcpJson.parseObject("{\"array\":[0,-5,1.25,1e3,true,false,null,\"x\"],\"empty\":{}}");
        JSONArray array = json.getJSONArray("array");
        assertEquals(0, array.getInt(0));
        assertEquals(-5, array.getInt(1));
        assertEquals(1.25, array.getDouble(2), 0.0);
        assertEquals(1000, array.getInt(3));
        assertTrue(array.getBoolean(4));
        assertFalse(array.getBoolean(5));
        assertTrue(array.isNull(6));
        assertEquals("x", array.getString(7));
        assertEquals(0, json.getJSONObject("empty").length());
    }

    @Test
    public void shallowArraysAreNotConfusedWithDeepNesting() throws Exception {
        JSONArray entries = new JSONArray();
        for (int i = 0; i < 3000; i++) entries.put(i);
        JSONObject source = new JSONObject().put("array", entries);
        assertEquals(3000, TcpJson.parseObject(source.toString()).getJSONArray("array").length());
    }

    @Test
    public void nullEmptyAndNonObjectDocumentsAreRejected() throws Exception {
        for (String json : new String[]{null, "", " ", "[]", "null", "1", "true", "\"text\""})
            assertRejected(json);
    }

    @Test
    public void truncatedOrMismatchedDocumentsAreRejected() throws Exception {
        for (String json : new String[]{"{", "{\"a\":[}", "{\"a\":[1,2]", "{\"a\":\"value}", "{\"a\":}"})
            assertRejected(json);
    }

    @Test
    public void invalidEscapesAreRejectedWithoutUncheckedExceptions() throws Exception {
        assertRejected("{\"a\":\"\\uZZZZ\"}");
        assertRejected("{\"a\":\"\\u01\"}");
    }

    @Test
    public void trailingObjectsOrGarbageCannotHideAfterAValidMessage() throws Exception {
        assertRejected("{}{}");
        assertRejected("{} trailing");
        assertEquals(0, TcpJson.parseObject(" \t{}\r\n ").length());
    }

    @Test
    public void nonJsonSyntaxCannotHideNestedValues() throws Exception {
        for (String json : new String[]{"{unquoted:1}", "{'a':1}", "{/* comment */\"a\":1}", "{\"a\":1,}"})
            assertRejected(json);
    }

    @Test
    public void inputLargerThanAnyWireFrameIsRejected() throws Exception {
        assertRejected(new JSONObject().put("unused", TcpInputTestData.repeat('a', 65536)).toString());
    }

    @Test
    public void playerNameLimitMatchesTheExistingNameEditor() throws Exception {
        String android = "http://schemas.android.com/apk/res/android";
        try (XmlResourceParser layout = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getLayout(R.layout.player_name_dialog)) {
            while (layout.next() != XmlPullParser.END_DOCUMENT) {
                if (layout.getEventType() == XmlPullParser.START_TAG
                        && layout.getAttributeResourceValue(android, "id", 0) == R.id.player_name_et) {
                    assertEquals(TcpJson.MAX_PLAYER_NAME_LENGTH, layout.getAttributeIntValue(android, "maxLength", -1));
                    return;
                }
            }
        }
        fail("Player name editor was not found");
    }

    @Test
    public void integerReadsPreserveBothSignedIntBoundaries() throws Exception {
        for (int value : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1}) {
            JSONObject json = TcpJson.parseObject(new JSONObject().put("value", value).toString());
            assertEquals(value, TcpJson.getInt(json, "value"));
            assertEquals(value, TcpJson.getLong(json, "value"));
        }
    }

    @Test
    public void integerReadsRejectValuesThatWouldWrapIntoRange() throws Exception {
        for (long value : new long[]{(long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1,
                4294967297L, -4294967295L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            JSONObject json = TcpJson.parseObject(new JSONObject().put("value", value).toString());
            assertIntegerReadRejected(json, false);
        }
    }

    @Test
    public void longReadsPreserveValuesBeyondDoublePrecision() throws Exception {
        for (long value : new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE,
                Long.MAX_VALUE - 1, 9007199254740993L, -9007199254740993L}) {
            JSONObject json = TcpJson.parseObject(new JSONObject().put("value", value).toString());
            assertEquals(value, TcpJson.getLong(json, "value"));
        }
    }

    @Test
    public void longReadsRejectOverflowInsteadOfSaturating() throws Exception {
        for (String value : new String[]{"9223372036854775808", "-9223372036854775809", "1e100"}) {
            JSONObject json = TcpJson.parseObject("{\"value\":" + value + "}");
            assertIntegerReadRejected(json, true);
        }
    }

    @Test
    public void integerFieldsRejectFractionalAndFloatingPointEncodings() throws Exception {
        for (String value : new String[]{"1.75", "-0.5", "1.00000000000000001", "1.0", "1e0"}) {
            JSONObject json = TcpJson.parseObject("{\"value\":" + value + "}");
            assertIntegerReadRejected(json, false);
            assertIntegerReadRejected(json, true);
        }
    }

    @Test
    public void integerFieldsDoNotCoerceStringsBooleansNullOrContainers() throws Exception {
        for (Object value : new Object[]{"1", "-5", "1.75", "1e2", "NaN", "Infinity",
                true, false, JSONObject.NULL, new JSONObject(), new JSONArray()}) {
            JSONObject json = TcpJson.parseObject(new JSONObject().put("value", value).toString());
            assertIntegerReadRejected(json, false);
            assertIntegerReadRejected(json, true);
        }
    }

    @Test
    public void missingIntegerFieldsRaiseCheckedParseErrors() throws Exception {
        assertIntegerReadRejected(new JSONObject(), false);
        assertIntegerReadRejected(new JSONObject(), true);
    }

    private static void assertIntegerReadRejected(JSONObject json, boolean readLong) throws Exception {
        try {
            if (readLong) TcpJson.getLong(json, "value");
            else TcpJson.getInt(json, "value");
            fail("Invalid integer field was accepted");
        } catch (JSONException expected) {
            // Callers already handle checked parse failures without changing game state.
        }
    }

    private static void assertRejected(String message) throws Exception {
        try {
            TcpJson.parseObject(message);
            fail("Invalid network JSON was accepted");
        } catch (JSONException expected) {
            // Rejection must not escape as StackOverflowError or a runtime exception.
        } catch (StackOverflowError overflow) {
            throw new AssertionError("Network JSON caused StackOverflowError");
        }
    }
}
