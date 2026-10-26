package com.simplecoil.simplecoil;

import com.simplecoil.protocol.EventMode;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventModeTest {
    @Test public void newOrganizerPinsDefaultToTwoDigitsAndAllowUpToSix() {
        assertEquals(2, EventMode.DEFAULT_PIN_DIGITS);
        for (String pin : new String[]{"00", "07", "123", "1234", "12345", "000007", "123456"})
            assertTrue(pin, EventMode.validPin(pin));
        for (String pin : new String[]{"", "7", "1234567", "12345678", "12a", " 12", "12 ", "1\n", "\u0661\u0662"})
            assertFalse(pin, EventMode.validPin(pin));
        assertFalse(EventMode.validPin(null));
    }

    @Test public void shortAndSixDigitRecoveryPinsKeepLeadingZerosAndPlayerBinding() {
        String identity = "00000000-0000-0000-0000-000000000001";
        String other = "00000000-0000-0000-0000-000000000002";
        for (String pin : new String[]{"07", "000007"}) {
            String hash = EventMode.pinHash(identity, pin);
            assertTrue(EventMode.matchesPin(identity, pin, hash));
            assertFalse(EventMode.matchesPin(other, pin, hash));
            assertFalse(EventMode.matchesPin(identity, "7", hash));
            assertFalse(EventMode.matchesPin(identity, "70", hash));
        }
        assertNotEquals(EventMode.pinHash(identity, "07"), EventMode.pinHash(identity, "000007"));
    }

    @Test public void existingSevenAndEightDigitPinsStillUnlockWithoutAllowingNewOnes() {
        String identity = "00000000-0000-0000-0000-000000000001";
        for (String pin : new String[]{"0123456", "01234567"}) {
            assertFalse(EventMode.validPin(pin));
            assertTrue(EventMode.validStoredPin(pin));
            assertTrue(EventMode.matchesPin(identity, pin, EventMode.pinHash(identity, pin)));
            assertFalse(EventMode.matchesPin(identity, "123456", EventMode.pinHash(identity, pin)));
        }
        assertFalse(EventMode.validStoredPin("123456789"));
        assertFalse(EventMode.matchesPin(identity, "123456789", EventMode.pinHash(identity, "123456789")));
    }

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
