package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression checks for the signal used to disambiguate nearby blasters. */
public class WeaponPairingTest {
    @Test
    public void heldTriggerRequiresTheLiveTriggerBit() {
        assertFalse(FullscreenActivity.isTriggerHeld(null));
        assertFalse(FullscreenActivity.isTriggerHeld(
                new byte[FullscreenActivity.RECOIL_OFFSET_BUTTONS]));

        byte[] telemetry = new byte[FullscreenActivity.RECOIL_OFFSET_BUTTONS + 1];
        assertFalse(FullscreenActivity.isTriggerHeld(telemetry));

        telemetry[FullscreenActivity.RECOIL_OFFSET_BUTTONS] =
                (byte) FullscreenActivity.RECOIL_RELOAD_BIT;
        assertFalse(FullscreenActivity.isTriggerHeld(telemetry));

        telemetry[FullscreenActivity.RECOIL_OFFSET_BUTTONS] =
                (byte) FullscreenActivity.RECOIL_TRIGGER_BIT;
        assertTrue(FullscreenActivity.isTriggerHeld(telemetry));
    }
}
