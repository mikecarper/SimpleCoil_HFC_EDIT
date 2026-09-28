package com.simplecoil.simplecoil;

import com.simplecoil.protocol.RoundRestart;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoundRestartTest {
    @Test public void stoppedBeforeSixtySecondsNeedsNoBetweenGameWait() {
        assertEquals(100_000, RoundRestart.allowedAt(100_000, 40_001));
        assertEquals(100_000, RoundRestart.allowedAt(100_000, 99_000));
    }

    @Test public void sixtySecondsOrLongerKeepsNormalThirtySecondWait() {
        assertEquals(130_000, RoundRestart.allowedAt(100_000, 40_000));
        assertEquals(130_000, RoundRestart.allowedAt(100_000, 0));
    }

    @Test public void countdownCancellationCanBeRestartedImmediately() {
        assertEquals(100_000, RoundRestart.allowedAt(100_000, 110_000));
        assertEquals(100_000, RoundRestart.allowedAt(100_000, 100_000));
    }

    @Test public void unknownStartTimeDoesNotBypassCooldown() {
        assertEquals(130_000, RoundRestart.allowedAt(100_000, -1));
    }

    @Test public void policyDoesNotDependOnThePhonesIncorrectWallClock() {
        long uptime = 100L * 24 * 60 * 60 * 1000;
        assertEquals(uptime, RoundRestart.allowedAt(uptime, uptime - 59_999));
        assertEquals(uptime + 30_000, RoundRestart.allowedAt(uptime, uptime - 60_000));
    }
}
