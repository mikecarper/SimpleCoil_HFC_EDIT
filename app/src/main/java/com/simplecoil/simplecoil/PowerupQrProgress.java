// SPDX-License-Identifier: Apache-2.0

package com.simplecoil.simplecoil;

/** Tracks distinct power-up checkpoints for one life. */
final class PowerupQrProgress {
    static final int MIN_REQUIRED = com.simplecoil.protocol.Powerups.MIN_REQUIRED;
    static final int MAX_REQUIRED = com.simplecoil.protocol.Powerups.MAX_REQUIRED;
    private static final String PREFIX = "SIMPLECOIL:POWERUP:";

    enum ScanResult { INVALID, DUPLICATE, PROGRESS, UNLOCKED }

    private int scannedMask;
    private int spent;

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
        return scannedCount() - spent >= requiredCount
                ? ScanResult.UNLOCKED : ScanResult.PROGRESS;
    }

    int scannedCount() {
        return Integer.bitCount(scannedMask);
    }

    int scannedMask() {
        return scannedMask;
    }

    int progressCount(int requiredCount) {
        return isValidRequiredCount(requiredCount) ? scannedCount() - spent : 0;
    }

    boolean canUnlockAnother(int requiredCount) {
        return isValidRequiredCount(requiredCount)
                && MAX_REQUIRED - spent >= requiredCount;
    }

    /** Acknowledge only a new source-owned grant's recorded cost, not its current threshold. */
    boolean consumeReward(int scanCost) {
        if (!isValidRequiredCount(scanCost) || scannedCount() - spent < scanCost) return false;
        spent += scanCost;
        return true;
    }

    /** Merge delayed authoritative progress without losing locally scanned/unacknowledged codes. */
    boolean syncHostProgress(int mask, int spentCount) {
        if (mask < 0 || mask > 255 || spentCount < 0 || spentCount > Integer.bitCount(mask)) return false;
        scannedMask |= mask;
        spent = Math.max(spent, spentCount);
        return true;
    }

    void reset() {
        scannedMask = 0;
        spent = 0;
    }
}
