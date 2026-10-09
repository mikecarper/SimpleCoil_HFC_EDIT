package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PowerupQrProgressTest {
    @Test public void onlyDedicatedCheckpointCodesCount() {
        assertEquals(1, PowerupQrProgress.checkpointFromQrCode("SIMPLECOIL:POWERUP:1"));
        assertEquals(8, PowerupQrProgress.checkpointFromQrCode("simplecoil:powerup:8"));
        assertEquals(0, PowerupQrProgress.checkpointFromQrCode("SIMPLECOIL:RESPAWN:1"));
        assertEquals(0, PowerupQrProgress.checkpointFromQrCode("SIMPLECOIL:POWERUP:9"));
        assertEquals(0, PowerupQrProgress.checkpointFromQrCode("SIMPLECOIL:POWERUP:01"));
    }

    @Test public void fourCodesUnlockTwicePerLifeAndDuplicatesDoNotCount() {
        PowerupQrProgress progress = new PowerupQrProgress();
        for (int number = 1; number <= 8; number++) {
            PowerupQrProgress.ScanResult result = progress.scan(
                    "SIMPLECOIL:POWERUP:" + number, 4);
            assertEquals(number % 4 == 0 ? PowerupQrProgress.ScanResult.UNLOCKED
                    : PowerupQrProgress.ScanResult.PROGRESS, result);
            if (result == PowerupQrProgress.ScanResult.UNLOCKED) assertTrue(progress.consumeReward(4));
        }
        assertEquals(PowerupQrProgress.ScanResult.DUPLICATE,
                progress.scan("SIMPLECOIL:POWERUP:1", 4));
        assertEquals(8, progress.scannedCount());
        assertEquals(255, progress.scannedMask());
        assertFalse(progress.canUnlockAnother(4));
    }

    @Test public void twoThroughEightDistinctCheckpointsUnlockOnlyEarnedCredits() {
        for (int required = 2; required <= 8; required++) {
            PowerupQrProgress progress = new PowerupQrProgress();
            int unlocked = 0;
            for (int checkpoint = 1; checkpoint <= 8; checkpoint++) {
                PowerupQrProgress.ScanResult result = progress.scan("SIMPLECOIL:POWERUP:" + checkpoint, required);
                if (result == PowerupQrProgress.ScanResult.UNLOCKED) {
                    unlocked++;
                    assertTrue(progress.consumeReward(required));
                }
                assertEquals((1 << checkpoint) - 1, progress.scannedMask());
                assertEquals(PowerupQrProgress.ScanResult.DUPLICATE,
                        progress.scan("SIMPLECOIL:POWERUP:" + checkpoint, required));
            }
            assertEquals(8 / required, unlocked);
            assertFalse(progress.canUnlockAnother(required));
            progress.reset();
            assertEquals(0, progress.scannedMask());
            assertTrue(progress.canUnlockAnother(required));
        }
        assertFalse(PowerupQrProgress.isValidRequiredCount(1));
        assertFalse(PowerupQrProgress.isValidRequiredCount(9));
    }

    @Test public void deathResetsProgressAndFiveThroughEightAreValidThresholds() {
        PowerupQrProgress progress = new PowerupQrProgress();
        for (int threshold = 5; threshold <= 8; threshold++) {
            progress.reset();
            for (int number = 1; number < threshold; number++)
                assertEquals(PowerupQrProgress.ScanResult.PROGRESS,
                        progress.scan("SIMPLECOIL:POWERUP:" + number, threshold));
            assertEquals(threshold - 1, progress.progressCount(threshold));
            progress.reset();
            assertEquals(0, progress.scannedCount());
            for (int number = 1; number <= threshold; number++)
                progress.scan("SIMPLECOIL:POWERUP:" + number, threshold);
            assertTrue(progress.consumeReward(threshold));
            assertEquals(0, progress.progressCount(threshold));
            assertFalse(progress.canUnlockAnother(threshold));
        }
        assertTrue(progress.scan("SIMPLECOIL:POWERUP:1", 0)
                == PowerupQrProgress.ScanResult.INVALID);
    }

    @Test public void variableThresholdAndDelayedHostSyncNeverReuseSpentCodes() {
        PowerupQrProgress progress = new PowerupQrProgress();
        for (int checkpoint = 1; checkpoint <= 5; checkpoint++)
            assertEquals(PowerupQrProgress.ScanResult.PROGRESS,
                    progress.scan("SIMPLECOIL:POWERUP:" + checkpoint, 8));
        assertEquals(5, progress.progressCount(5));
        assertTrue(progress.consumeReward(5));
        assertEquals(0, progress.progressCount(2));
        assertFalse(progress.consumeReward(2));
        assertTrue(progress.syncHostProgress(255, 7));
        assertEquals(1, progress.progressCount(2));
        assertFalse(progress.consumeReward(2));
        assertTrue(progress.syncHostProgress(3, 2));
        assertEquals(255, progress.scannedMask());
        assertEquals(1, progress.progressCount(2));
        assertFalse(progress.syncHostProgress(256, 0));
        assertFalse(progress.syncHostProgress(255, 9));
        assertFalse(progress.consumeReward(0));
        progress.reset();
        assertEquals(0, progress.scannedMask());
        assertEquals(0, progress.progressCount(2));
    }
}
