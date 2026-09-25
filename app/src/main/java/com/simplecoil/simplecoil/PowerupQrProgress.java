// SPDX-License-Identifier: Apache-2.0

package com.simplecoil.simplecoil;

/** Tracks distinct power-up checkpoints for one life. */
final class PowerupQrProgress {
    static final int MIN_REQUIRED = 4;
    static final int MAX_REQUIRED = 8;
    private static final String PREFIX = "SIMPLECOIL:POWERUP:";

    enum ScanResult { INVALID, DUPLICATE, PROGRESS, UNLOCKED }

    private int scannedMask;

    static boolean isValidRequiredCount(int count) {
        return count >= MIN_REQUIRED && count <= MAX_REQUIRED;
    }

    static int checkpointFromQrCode(String contents) {
        if (contents == null || contents.length() > 64)
            return 0;
        String value = contents.trim();
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                || value.length() != PREFIX.length() + 1)
            return 0;
        char number = value.charAt(PREFIX.length());
        return number >= '1' && number <= '8' ? number - '0' : 0;
    }

    ScanResult scan(String contents, int requiredCount) {
        if (!isValidRequiredCount(requiredCount))
            return ScanResult.INVALID;
        int checkpoint = checkpointFromQrCode(contents);
        if (checkpoint == 0)
            return ScanResult.INVALID;
        int bit = 1 << (checkpoint - 1);
        if ((scannedMask & bit) != 0)
            return ScanResult.DUPLICATE;
        scannedMask |= bit;
        return Integer.bitCount(scannedMask) % requiredCount == 0
                ? ScanResult.UNLOCKED : ScanResult.PROGRESS;
    }

    int scannedCount() {
        return Integer.bitCount(scannedMask);
    }

    int progressCount(int requiredCount) {
        return isValidRequiredCount(requiredCount) ? scannedCount() % requiredCount : 0;
    }

    boolean canUnlockAnother(int requiredCount) {
        return isValidRequiredCount(requiredCount)
                && scannedCount() / requiredCount < MAX_REQUIRED / requiredCount;
    }

    void reset() {
        scannedMask = 0;
    }
}
