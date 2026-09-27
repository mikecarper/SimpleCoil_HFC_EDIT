package com.simplecoil.simplecoil;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndGameVotesTest {
    @Test public void twoDistinctPlayersAreRequiredEvenWithRepeatedTaps() {
        long votes = EndGameVotes.playerBit(1);
        votes |= EndGameVotes.playerBit(1);
        assertFalse(EndGameVotes.hasQuorum(votes));
        votes |= EndGameVotes.playerBit(32);
        assertTrue(EndGameVotes.hasQuorum(votes));
        votes &= ~EndGameVotes.playerBit(1);
        assertFalse(EndGameVotes.hasQuorum(votes));
    }

    @Test public void operatorAndInvalidPlayerIDsDoNotCount() {
        assertEquals(0, EndGameVotes.playerBit(0));
        assertEquals(0, EndGameVotes.playerBit(-1));
        assertEquals(0, EndGameVotes.playerBit(33));
        assertEquals(0x80000000L, EndGameVotes.playerBit(32));
        assertFalse(EndGameVotes.hasQuorum(0x100000001L));
    }
}
