package com.simplecoil.simplecoil;

import com.simplecoil.protocol.GameStartCountdown;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameStartCountdownTest {
    @Test public void twentyThreeSecondsIncludeTwentyForCountingAndThreeForTheClosingLine() {
        assertEquals(23_000, GameStartCountdown.DURATION_MS);
        assertEquals(23, GameStartCountdown.DURATION_SECONDS);
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
        assertEquals(11, GameStartCountdown.numberForRemainingMillis(23_001));
        assertEquals(44, GameStartCountdown.numberForRemainingMillis(90_000));
        assertEquals(Integer.MAX_VALUE,
                GameStartCountdown.numberForRemainingMillis(Long.MAX_VALUE));
    }

    @Test public void closingSlotBeginsThreeSecondsBeforePlayRatherThanAtActivation() {
        assertEquals(1, GameStartCountdown.numberForRemainingMillis(3_001));
        for (long remaining : new long[]{3_000, 2_999, 2_000, 1, 0, -1})
            assertEquals(0, GameStartCountdown.numberForRemainingMillis(remaining));
    }

    @Test public void measuredShorterClosingLineWaitsSoItEndsAtTheStartDeadline() {
        long remaining = GameStartCountdown.ENDING_VOICE_MS;
        for (long duration : new long[]{1, 750, 1_500, 2_750, 2_999}) {
            long delay = GameStartCountdown.closingVoiceDelayMillis(remaining, duration);
            assertEquals(remaining - duration, delay);
            assertEquals(remaining, delay + duration);
        }
        assertEquals(23_000, GameStartCountdown.DURATION_MS);
    }

    @Test public void threeSecondClosingLineStartsWithoutAnAdditionalDelay() {
        assertEquals(0, GameStartCountdown.closingVoiceDelayMillis(3_000, 3_000));
        assertEquals(0, GameStartCountdown.closingVoiceDelayMillis(2_999, 3_000));
    }

    @Test public void lateClosingLineNeverWaitsPastTheDeadline() {
        assertEquals(500, GameStartCountdown.closingVoiceDelayMillis(2_000, 1_500));
        for (long remaining : new long[]{1_500, 1_499, 1, 0, -1, Long.MIN_VALUE})
            assertEquals(0, GameStartCountdown.closingVoiceDelayMillis(remaining, 1_500));
    }

    @Test public void invalidMeasuredDurationsUseTheThreeSecondWindow() {
        for (long duration : new long[]{0, -1, Long.MIN_VALUE, 3_001, Long.MAX_VALUE}) {
            assertEquals(1_000, GameStartCountdown.closingVoiceDelayMillis(4_000, duration));
            assertEquals(0, GameStartCountdown.closingVoiceDelayMillis(3_000, duration));
        }
    }

    @Test public void closingLineDelayDoesNotOverflowForExtremeLongValues() {
        assertEquals(Long.MAX_VALUE - 1,
                GameStartCountdown.closingVoiceDelayMillis(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE - GameStartCountdown.ENDING_VOICE_MS,
                GameStartCountdown.closingVoiceDelayMillis(Long.MAX_VALUE, 3_000));
        assertEquals(Long.MAX_VALUE - GameStartCountdown.ENDING_VOICE_MS,
                GameStartCountdown.closingVoiceDelayMillis(Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(0, GameStartCountdown.closingVoiceDelayMillis(Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
