package com.simplecoil.simplecoil;

import com.simplecoil.protocol.EventMode;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventModeTest {
    @Test public void recoveryPinIsBoundToTheTransferredPlayer() {
        String old = "00000000-0000-0000-0000-000000000001", moved = "00000000-0000-0000-0000-000000000002";
        String hash = EventMode.pinHash(old, "123456");
        assertTrue(EventMode.matchesPin(old, "123456", hash));
        assertFalse(EventMode.matchesPin(moved, "123456", hash));
        assertFalse(EventMode.matchesPin(old, "654321", hash));
        assertFalse(EventMode.matchesPin(old, "12345", hash));
        assertFalse(EventMode.matchesPin(old, "123456", ""));
    }
    @Test public void eventNamesAcceptPunctuationAndRejectBlankOrControlCharacters() {
        assertTrue(EventMode.validTitle("LAZER GUN RUN"));
        assertTrue(EventMode.validTitle("Dean’s party — final round"));
        assertFalse(EventMode.validTitle(" "));
        assertFalse(EventMode.validTitle("Event\nname"));
        assertFalse(EventMode.validTitle(new String(new char[81]).replace('\0','X')));
    }
}
