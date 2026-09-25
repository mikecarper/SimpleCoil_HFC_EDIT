// SPDX-License-Identifier: Apache-2.0
package com.simplecoil.simplecoil;

/** Suppress one IR hit reported by both boss blasters without merging their shot counters. */
final class DualGunHitDeduper {
    private static final long CROSS_GUN_WINDOW_MS = 750;
    // Player IDs occupy 1-32; index 33 identifies a grenade event. Grenade
    // commands use the full shot byte, unlike ordinary shots' three-bit ID.
    private final long[][] lastSeenAt = new long[34][256];
    private final int[][] lastSeenSlot = new int[34][256];

    boolean accept(int slot, int attackerId, int shotId, long now) {
        if (attackerId < 1 || attackerId > 33 || shotId < 0 || shotId > 255)
            return true;
        long previous = lastSeenAt[attackerId][shotId];
        if (previous != 0 && lastSeenSlot[attackerId][shotId] != slot
                && now >= previous && now - previous <= CROSS_GUN_WINDOW_MS)
            return false;
        lastSeenAt[attackerId][shotId] = now;
        lastSeenSlot[attackerId][shotId] = slot;
        return true;
    }

    void reset() {
        for (int attacker = 1; attacker < lastSeenAt.length; attacker++) {
            java.util.Arrays.fill(lastSeenAt[attacker], 0);
            java.util.Arrays.fill(lastSeenSlot[attacker], 0);
        }
    }
}
