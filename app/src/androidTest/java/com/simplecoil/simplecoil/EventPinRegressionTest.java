package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.simplecoil.protocol.EventMode;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the release PIN verifier on the phone's actual Android runtime. */
@RunWith(AndroidJUnit4.class)
public class EventPinRegressionTest {
    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";

    @Test public void shortAndSixDigitPinsUnlockOnlyTheMatchingPlayer() {
        for (String pin : new String[]{"07", "000007"}) {
            String hash = EventMode.pinHash(PLAYER, pin);
            assertTrue(EventMode.matchesPin(PLAYER, pin, hash));
            assertFalse(EventMode.matchesPin(OTHER, pin, hash));
            assertFalse(EventMode.matchesPin(PLAYER, "7", hash));
            assertFalse(EventMode.matchesPin(PLAYER, "70", hash));
        }
    }

    @Test public void existingLongPinsRemainUsableAfterAnUpdate() {
        for (String pin : new String[]{"0123456", "01234567"}) {
            assertFalse(EventMode.validPin(pin));
            assertTrue(EventMode.matchesPin(PLAYER, pin, EventMode.pinHash(PLAYER, pin)));
        }
        assertFalse(EventMode.matchesPin(PLAYER, "123456789", EventMode.pinHash(PLAYER, "123456789")));
    }
}
