package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GunBatteryHistory;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class GunBatteryHistoryTest {
    private static final String PLAYER = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER = "10000000-0000-0000-0000-000000000002";
    private static final String ROUND = "20000000-0000-0000-0000-000000000001";
    private static final String NEXT = "20000000-0000-0000-0000-000000000002";
    private static final String GUN = "AA:BB:CC:DD:EE:01", SECOND = "AA:BB:CC:DD:EE:02";

    @Test public void recordsOnlyValidCalibratedReadingsDuringARound() {
        GunBatteryHistory history = new GunBatteryHistory(20);
        assertFalse(history.observe(GUN, 0, 2, 48));
        history.begin(ROUND, PLAYER, 1);
        assertFalse(history.observe(GUN, 0, 1, 48));
        assertFalse(history.observe(GUN, 0, 2, -1));
        assertFalse(history.observe(null, 0, 2, 48));
        assertTrue(history.observe(GUN, 0, 2, 48));
        assertTrue(history.observe(GUN.toLowerCase(), 0, 2, 43));
        assertFalse(history.observe(GUN, 0, 2, 55));
        assertEquals(43, minimum(history, 0, 0));
        history.finish();
        assertFalse(history.observe(GUN, 0, 2, 40));
        assertEquals(43, minimum(history, 0, 0));
    }

    @Test public void eachGunAndRoundHasAnIndependentMinimumIncludingSwappedGuns() {
        GunBatteryHistory history = new GunBatteryHistory(20);
        history.begin(ROUND, PLAYER, 1);
        history.observe(GUN, 0, 2, 42);
        history.observe(SECOND, 1, 2, 49);
        history.observe("AA:BB:CC:DD:EE:03", 0, 2, 54);
        history.begin(ROUND, PLAYER, 2); // Same-round reconnect cannot erase a low.
        history.observe(GUN, 0, 2, 55);
        assertEquals(42, minimum(history, 0, 0));
        assertEquals(49, minimum(history, 0, 1));
        assertEquals(54, minimum(history, 0, 2));
        history.begin(NEXT, PLAYER, 3);
        history.observe(GUN, 0, 2, 55);
        assertEquals(42, minimum(history, 0, 0));
        assertEquals(55, minimum(history, 1, 0));
    }

    @Test public void restoresAndReplaysWithoutRaisingOrDuplicatingMinimums() {
        GunBatteryHistory phone = new GunBatteryHistory(20), laptop = new GunBatteryHistory(100);
        phone.begin(ROUND, PLAYER, 1);phone.observe(GUN, 0, 2, 48);
        assertTrue(laptop.merge(phone.snapshot(), PLAYER));
        assertFalse(laptop.merge(phone.snapshot(), PLAYER));
        GunBatteryHistory restored = new GunBatteryHistory(20);
        restored.merge(phone.snapshot(), null);
        assertFalse(restored.observe(GUN, 0, 2, 40)); // Restoring doesn't start an idle round.
        restored.begin(ROUND, PLAYER, 2);restored.observe(GUN, 0, 2, 41);
        assertTrue(laptop.merge(restored.snapshot(), PLAYER));
        assertFalse(laptop.merge(phone.snapshot(), PLAYER)); // Older retry.
        assertEquals(1, laptop.snapshot().size());
        assertEquals(41, minimum(laptop, 0, 0));
    }

    @Test public void phoneTransferCannotAttributePreviousPlayersReadingsToNewPlayer() {
        GunBatteryHistory phone = new GunBatteryHistory(20), laptop = new GunBatteryHistory(100);
        phone.begin(ROUND, PLAYER, 1);phone.observe(GUN, 0, 2, 40);
        phone.begin(NEXT, OTHER, 2);phone.observe(GUN, 0, 2, 55);
        assertTrue(laptop.merge(phone.snapshot(), OTHER));
        assertEquals(1, laptop.snapshot().size());
        assertEquals(OTHER, laptop.snapshot().get(0).get("player"));
        assertEquals(55, minimum(laptop, 0, 0));
    }

    @Test public void retainsOnlyConfiguredNumberOfRounds() {
        GunBatteryHistory history = new GunBatteryHistory(2);
        history.begin(ROUND, PLAYER, 1);history.observe(GUN, 0, 2, 40);
        history.begin(NEXT, PLAYER, 2);history.observe(GUN, 0, 2, 50);
        history.begin("20000000-0000-0000-0000-000000000003", PLAYER, 3);
        assertEquals(2, history.snapshot().size());
        assertEquals(NEXT, history.snapshot().get(0).get("round"));
    }

    @Test public void malformedReportCannotPartiallyMerge() {
        GunBatteryHistory phone = new GunBatteryHistory(20), laptop = new GunBatteryHistory(100);
        phone.begin(ROUND, PLAYER, 1);phone.observe(GUN, 0, 2, 48);
        List<Object> invalid = new ArrayList<>(phone.snapshot());invalid.add("broken");
        try { laptop.merge(invalid, PLAYER);fail("Malformed report accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(laptop.snapshot().isEmpty()); }
        Map<String, Object> round = phone.snapshot().get(0);
        gun(round, 0).put("minimumTenths", 48.5);
        try { laptop.merge(java.util.Collections.singletonList(round), PLAYER);fail("Fractional voltage accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(laptop.snapshot().isEmpty()); }
    }

    @Test public void calibrationRangeMarkersArePreserved() {
        GunBatteryHistory history = new GunBatteryHistory(20);
        history.begin(ROUND, PLAYER, 1);history.observe(GUN, 0, 2, 61);
        assertEquals(61, minimum(history, 0, 0));
        history.observe(GUN, 0, 2, 19);
        assertEquals(19, minimum(history, 0, 0));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> gun(Map<String, Object> round, int index) {
        return (Map<String, Object>) ((List<?>) round.get("guns")).get(index);
    }
    private static int minimum(GunBatteryHistory history, int round, int gun) {
        return ((Number) gun(history.snapshot().get(round), gun).get("minimumTenths")).intValue();
    }
}
