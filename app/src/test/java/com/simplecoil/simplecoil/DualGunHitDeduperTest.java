package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DualGunHitDeduperTest {
    @Test public void sameShotSeenByBothBossGunsCountsOnce() {
        DualGunHitDeduper deduper = new DualGunHitDeduper();
        assertTrue(deduper.accept(0, 17, 3, 1000));
        assertFalse(deduper.accept(1, 17, 3, 1100));
        assertTrue(deduper.accept(1, 17, 4, 1100));
        assertTrue(deduper.accept(1, 18, 3, 1100));
        assertTrue(deduper.accept(1, 17, 3, 2000));
    }

    @Test public void resetClearsRecentHitHistory() {
        DualGunHitDeduper deduper = new DualGunHitDeduper();
        assertTrue(deduper.accept(0, 2, 1, 1000));
        deduper.reset();
        assertTrue(deduper.accept(1, 2, 1, 1100));
    }

    @Test public void grenadeCommandUsesItsFullIdAcrossBothSensors() {
        DualGunHitDeduper deduper = new DualGunHitDeduper();
        assertTrue(deduper.accept(0, 33, 0x91, 1000));
        assertFalse(deduper.accept(1, 33, 0x91, 1100));
        assertTrue(deduper.accept(1, 33, 0x11, 1100));
    }
}
