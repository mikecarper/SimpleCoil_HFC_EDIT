package com.simplecoil.simplecoil;

import org.junit.Test;
import static org.junit.Assert.*;

public class GameClockTest {
    @Test public void convertsAHostClockAheadOfTheClient() {
        GameClock clock = new GameClock();
        assertTrue(clock.record(1000, 6010, 6015, 1025));
        assertEquals(2000, clock.toLocalTime(7000));
    }

    @Test public void convertsAHostClockBehindTheClient() {
        GameClock clock = new GameClock();
        assertTrue(clock.record(6000, 1010, 1015, 6025));
        assertEquals(7000, clock.toLocalTime(2000));
    }

    @Test public void choosesLowestNetworkDelayNotLowestTotalDuration() {
        GameClock clock = new GameClock();
        assertTrue(clock.record(1000, 6010, 7010, 2020)); // 20 ms network, 1000 ms processing
        assertTrue(clock.record(3000, 8040, 8040, 3050)); // 50 ms network, asymmetric
        assertEquals(4000, clock.toLocalTime(9000));
        assertEquals(2, clock.samples());
    }

    @Test public void rejectsImpossibleAndUnboundedSamples() {
        GameClock clock = new GameClock();
        assertFalse(clock.record(-1, 20, 20, 30));
        assertFalse(clock.record(40, 20, 20, 30));
        assertFalse(clock.record(10, 30, 20, 40));
        assertFalse(clock.record(10, 20, 100, 40));
        assertFalse(clock.record(10, 20, 20, 5011));
        assertFalse(clock.record(10, Long.MAX_VALUE, Long.MAX_VALUE, 40));
        assertEquals(0, clock.samples());
    }

    @Test public void largeSafeTimestampsDoNotOverflow() {
        GameClock clock = new GameClock();
        assertTrue(clock.record(0, GameClock.MAX_TIMESTAMP - 10, GameClock.MAX_TIMESTAMP - 10, 20));
        assertEquals(20, clock.toLocalTime(GameClock.MAX_TIMESTAMP));
    }

    @Test public void rejectsSamplesTooUncertainForOneSecondGroupAlignment() {
        GameClock clock = new GameClock();
        assertFalse(clock.record(1000, 2000, 2000, 1501));
        assertEquals(0, clock.samples());
        assertTrue(clock.record(1000, 2000, 2000, 1500));
    }

    @Test public void twentyDifferentClocksStayWithinOneSecondEvenWithAsymmetricDelay() {
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        for (int player = 0; player < 20; player++) {
            long actualOffset = player * 100000L;
            long outbound = player * 25L;
            long inbound = 500 - outbound;
            GameClock clock = new GameClock();
            assertTrue(clock.record(1000, 1000 + actualOffset + outbound,
                    1000 + actualOffset + outbound, 1000 + outbound + inbound));
            long translatedBackToHost = clock.toLocalTime(5000000) + actualOffset;
            earliest = Math.min(earliest, translatedBackToHost);
            latest = Math.max(latest, translatedBackToHost);
        }
        assertTrue(latest - earliest < 1000);
    }

    @Test public void refreshKeepsPreviousEstimateUntilAFullNewBatchArrives() {
        GameClock clock = new GameClock();
        clock.record(1000, 6010, 6010, 1020);
        clock.beginSampling();
        assertEquals(0, clock.samples());
        assertEquals(2000, clock.toLocalTime(7000));
        clock.record(2000, 7020, 7020, 2020);
        assertEquals(2000, clock.toLocalTime(7000));
        for (int index = 1; index < GameClock.SAMPLES_PER_SYNC; index++)
            clock.record(3000 + index, 8020 + index, 8020 + index, 3020 + index);
        assertEquals(1990, clock.toLocalTime(7000));
    }

    @Test public void lateJoinMayTranslateToBeforeClientBoot() {
        GameClock clock = new GameClock();
        clock.record(1000, 6010, 6010, 1020);
        assertEquals(-4000, clock.toLocalTime(1000));
    }

    @Test(expected = IllegalArgumentException.class) public void resetDiscardsPreviousHost() {
        GameClock clock = new GameClock();
        clock.record(1000, 6010, 6010, 1020);
        clock.reset();
        clock.toLocalTime(7000);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidDeadline() {
        GameClock clock = new GameClock();
        clock.record(1000, 6010, 6010, 1020);
        clock.toLocalTime(Long.MAX_VALUE);
    }
}
