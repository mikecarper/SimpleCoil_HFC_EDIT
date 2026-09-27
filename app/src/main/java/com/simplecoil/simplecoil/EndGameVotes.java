package com.simplecoil.simplecoil;

/** One bit per participating player, never one vote per tap or packet. */
final class EndGameVotes {
    static final long VALID_MASK = 0xffffffffL;

    private EndGameVotes() { }

    static long playerBit(int playerID) {
        return playerID >= 1 && playerID <= 32 ? 1L << (playerID - 1) : 0;
    }

    static boolean hasQuorum(long votes) {
        return Long.bitCount(votes & VALID_MASK) >= 2;
    }
}
