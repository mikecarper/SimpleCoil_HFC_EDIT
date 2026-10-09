package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GameStartCountdown;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameStartCountdownTest {
    @Test public void twentyFourSecondsIncludeTwentyForCountingAndFourForTheClosingLine() {
        assertEquals(24_000, GameStartCountdown.DURATION_MS);
        assertEquals(24, GameStartCountdown.DURATION_SECONDS);
        assertEquals(GameStartCountdown.DURATION_MS,
                GameStartCountdown.SPOKEN_MAX * GameStartCountdown.VOICE_STEP_MS
                        + GameStartCountdown.ENDING_VOICE_MS);
        for (int number = 10; number >= 1; number--) {
            long boundary = GameStartCountdown.ENDING_VOICE_MS
                    + number * GameStartCountdown.VOICE_STEP_MS;
            assertEquals(number, GameStartCountdown.numberForRemainingMillis(boundary));
            assertEquals(number, GameStartCountdown.numberForRemainingMillis(boundary - 1));
            assertEquals(number, GameStartCountdown.numberForRemainingMillis(boundary - 1_999));
            assertEquals(number - 1, GameStartCountdown.numberForRemainingMillis(boundary - 2_000));
        }
        assertEquals(0, GameStartCountdown.numberForRemainingMillis(0));
        assertEquals(0, GameStartCountdown.numberForRemainingMillis(-1));
        assertEquals(0, GameStartCountdown.numberForRemainingMillis(Long.MIN_VALUE));
    }

    @Test public void longerExplicitDeadlinesDoNotBeginTheSpokenTenEarly() {
        assertEquals(11, GameStartCountdown.numberForRemainingMillis(24_001));
        assertEquals(43, GameStartCountdown.numberForRemainingMillis(90_000));
        assertEquals(Integer.MAX_VALUE,
                GameStartCountdown.numberForRemainingMillis(Long.MAX_VALUE));
    }

    @Test public void zeroIsSpokenFourSecondsBeforePlayRatherThanAtActivation() {
        assertEquals(1, GameStartCountdown.numberForRemainingMillis(4_001));
        for (long remaining : new long[]{4_000, 3_999, 2_000, 1, 0, -1})
            assertEquals(0, GameStartCountdown.numberForRemainingMillis(remaining));
    }
}
