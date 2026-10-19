package com.simplecoil.simplecoil;

import org.junit.Test;
import static org.junit.Assert.*;

public class MatchSessionLockTest {
    @Test public void disconnectAndRecreationKeepTheOriginalHost() {
        MatchSessionLock session = new MatchSessionLock(null, null);
        assertTrue(session.join("192.168.1.10", "round-one"));
        // Restore the persisted lock after local state and discovery are lost.
        session = new MatchSessionLock(session.host(), session.token());
        assertTrue(session.active());
        assertTrue(session.allowsHost("192.168.1.10"));
        assertFalse(session.allowsHost("192.168.1.20"));
        assertFalse(session.allowsHost(null));
        assertFalse(session.join("192.168.1.20", "side-game"));
        assertEquals("round-one", session.token());
    }

    @Test public void playingBeaconCanLockBeforeTheStartArrives() {
        MatchSessionLock session = new MatchSessionLock(null, null);
        assertTrue(session.join("10.42.0.1", null));
        assertTrue(session.active());
        assertFalse(session.complete("old-round"));
        assertTrue(session.join("10.42.0.1", "current-round"));
        assertFalse(session.complete("old-round"));
        assertTrue(session.complete("current-round"));
        assertFalse(session.active());
    }

    @Test public void onlyTheCurrentRoundCanHandOffTheSameGame() {
        MatchSessionLock session = new MatchSessionLock("10.0.0.1", "round-one");
        assertFalse(session.handoff("10.0.0.2", "old-round"));
        assertEquals("10.0.0.1", session.host());
        assertTrue(session.handoff("10.0.0.2", "round-one"));
        assertFalse(session.allowsHost("10.0.0.1"));
        assertTrue(session.allowsHost("10.0.0.2"));
    }

    @Test public void confirmedEndLetsTheNextLobbyChooseAHost() {
        MatchSessionLock session = new MatchSessionLock("10.0.0.1", "round-one");
        session.clear();
        assertFalse(session.active());
        assertTrue(session.allowsHost("10.0.0.2"));
        assertTrue(session.join("10.0.0.2", "round-two"));
    }
}
