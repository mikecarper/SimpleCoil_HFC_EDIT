package com.simplecoil.simplecoil;

import com.simplecoil.protocol.RoundStatistics;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoundStatisticsTest {
    @Test public void skillUsesExistingSmoothedStrengthIndex() {
        assertEquals(100, RoundStatistics.skillIndex(0, 0));
        assertEquals(100, RoundStatistics.skillIndex(9, 9));
        assertEquals(150, RoundStatistics.skillIndex(10, 0));
        assertEquals(50, RoundStatistics.skillIndex(0, 10));
        assertEquals(125, RoundStatistics.skillIndex(20, 10));
        assertEquals(75, RoundStatistics.skillIndex(10, 20));
    }

    @Test public void invalidAndExtremeHistoriesAreBoundedWithoutOverflow() {
        assertEquals(100, RoundStatistics.skillIndex(-1, -1));
        assertEquals(199, RoundStatistics.skillIndex(Long.MAX_VALUE, 0));
        assertEquals(1, RoundStatistics.skillIndex(0, Long.MAX_VALUE));
        assertEquals(100, RoundStatistics.skillIndex(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test public void accuracyRequiresShotsAndKnownGunHits() {
        assertNull(RoundStatistics.accuracyPercent(0, 0));
        assertNull(RoundStatistics.accuracyPercent(-1, 1));
        assertNull(RoundStatistics.accuracyPercent(10, -1));
        assertEquals(0.0, RoundStatistics.accuracyPercent(10, 0), 0.0001);
        assertEquals(30.0, RoundStatistics.accuracyPercent(10, 3), 0.0001);
        assertEquals(100.0, RoundStatistics.accuracyPercent(10, 12), 0.0001);
        assertEquals(100.0, RoundStatistics.accuracyPercent(Long.MAX_VALUE, Long.MAX_VALUE), 0.0001);
    }

    @Test public void oppositeEventsResetRunsButNotBestStreaks() {
        RoundStatistics.Streaks streaks = new RoundStatistics.Streaks();
        streaks.onKill();
        streaks.onKill();
        assertEquals(2, streaks.currentKillStreak());
        streaks.onDeath();
        streaks.onDeath();
        streaks.onDeath();
        assertEquals(0, streaks.currentKillStreak());
        assertEquals(3, streaks.currentDeathStreak());
        streaks.onKill();
        assertEquals(0, streaks.currentDeathStreak());
        assertEquals(2, streaks.bestKillStreak());
        assertEquals(3, streaks.bestDeathStreak());
        streaks.reset();
        assertEquals(0, streaks.currentKillStreak());
        assertEquals(0, streaks.currentDeathStreak());
        assertEquals(0, streaks.bestKillStreak());
        assertEquals(0, streaks.bestDeathStreak());
    }
}
