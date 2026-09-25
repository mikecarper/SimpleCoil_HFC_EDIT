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
        }
        assertEquals(PowerupQrProgress.ScanResult.DUPLICATE,
                progress.scan("SIMPLECOIL:POWERUP:1", 4));
        assertEquals(8, progress.scannedCount());
        assertFalse(progress.canUnlockAnother(4));
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
            assertEquals(0, progress.progressCount(threshold));
            assertFalse(progress.canUnlockAnother(threshold));
        }
        assertTrue(progress.scan("SIMPLECOIL:POWERUP:1", 0)
                == PowerupQrProgress.ScanResult.INVALID);
    }
}
