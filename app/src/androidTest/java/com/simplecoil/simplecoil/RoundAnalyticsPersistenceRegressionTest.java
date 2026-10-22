package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.simplecoil.protocol.RoundStatistics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Persistence checks use their own preferences, never the player's profile. */
@RunWith(AndroidJUnit4.class)
public class RoundAnalyticsPersistenceRegressionTest {
    private static final String TOKEN = "11111111-1111-1111-1111-111111111111";
    private SharedPreferences preferences;
    private Context context;

    @Before public void setUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = target.getSharedPreferences("RoundAnalyticsPersistenceTest", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
        };
    }

    @After public void tearDown() { preferences.edit().clear().commit(); }

    @Test public void olderSavedRoundHasUnknownAnalyticsInsteadOfZeroPercentAccuracy() {
        preferences.edit().putBoolean("LastGame.present", true)
                .putInt("LastGame.kills", 7).putInt("LastGame.deaths", 3).commit();
        LastGameStats stats = LastGameStats.load(preferences);
        assertEquals(7, stats.kills);
        assertEquals(3, stats.deaths);
        assertFalse(stats.analyticsAvailable);
        assertFalse(stats.analyticsComplete);
        assertNull(RoundStatistics.accuracyPercent(stats.shots, stats.confirmedHits));
    }

    @Test public void newSnapshotPreservesAccuracyStreaksAndSkillAfterReload() {
        new LastGameStats("Teams", "Team 1", 7, 3, 12, 15, false, false,
                true, true, 40, 9, 4, 2, 100, 120).save(preferences);
        LastGameStats stats = LastGameStats.load(preferences);
        assertTrue(stats.analyticsAvailable);
        assertTrue(stats.analyticsComplete);
        assertEquals(40, stats.shots);
        assertEquals(9, stats.confirmedHits);
        assertEquals(4, stats.bestKillStreak);
        assertEquals(2, stats.bestDeathStreak);
        assertEquals(100, stats.skillBefore);
        assertEquals(120, stats.skillAfter);
        assertEquals(22.5, RoundStatistics.accuracyPercent(stats.shots, stats.confirmedHits), 0.001);
    }

    @Test public void startingRoundDoesNotChangeSkillAndRejoinKeepsOriginalBaseline() {
        assertEquals(100, PlayerHistory.beginRound(context, TOKEN));
        assertEquals(100, PlayerHistory.skillIndex(context));
        PlayerHistory.recordRound(context, TOKEN, 4, 1);
        int after = RoundStatistics.skillIndex(4, 1);
        assertEquals(after, PlayerHistory.skillIndex(context));
        assertEquals(100, PlayerHistory.beginRound(context, TOKEN));
        PlayerHistory.recordRound(context, TOKEN, 4, 1);
        assertEquals(4, PlayerHistory.kills(context));
        assertEquals(1, PlayerHistory.deaths(context));
        PlayerHistory.recordRound(context, TOKEN, 6, 2);
        assertEquals(6, PlayerHistory.kills(context));
        assertEquals(2, PlayerHistory.deaths(context));
        assertEquals(100, PlayerHistory.beginRound(context, TOKEN));
    }

    @Test public void migrationRecoversBaselineForAnAlreadyPartiallyRecordedRound() {
        preferences.edit().putInt("BalancedPriorKills", 13).putInt("BalancedPriorDeaths", 8)
                .putString("HistoryRound", TOKEN).putInt("HistoryRoundKills", 3)
                .putInt("HistoryRoundDeaths", 2).commit();
        assertEquals(RoundStatistics.skillIndex(10, 6), PlayerHistory.beginRound(context, TOKEN));
        assertEquals(13, PlayerHistory.kills(context));
        assertEquals(8, PlayerHistory.deaths(context));
    }

    @Test public void sameRoundRegistrationUsesOriginalBaselineAfterPartialLeaveAndRejoin() {
        PlayerHistory.record(context, 10, 6);
        PlayerHistory.beginRound(context, TOKEN);
        PlayerHistory.recordRound(context, TOKEN, 3, 2);
        assertEquals(13, PlayerHistory.kills(context));
        assertEquals(8, PlayerHistory.deaths(context));
        assertEquals(10, PlayerHistory.baselineKills(context, TOKEN));
        assertEquals(6, PlayerHistory.baselineDeaths(context, TOKEN));
        PlayerHistory.recordRound(context, TOKEN, 5, 3);
        assertEquals(10, PlayerHistory.baselineKills(context, TOKEN));
        assertEquals(6, PlayerHistory.baselineDeaths(context, TOKEN));
        String next = "22222222-2222-2222-2222-222222222222";
        assertEquals(15, PlayerHistory.baselineKills(context, next));
        assertEquals(9, PlayerHistory.baselineDeaths(context, next));
        assertEquals(15, PlayerHistory.baselineKills(context, null));
        assertEquals(9, PlayerHistory.baselineDeaths(context, "invalid"));
    }

    @Test public void baselineIsCapturedBeforeHistorySaturatesEvenWithoutAnExplicitStartCall() {
        PlayerHistory.record(context, PlayerHistory.MAX_TOTAL - 1, 0);
        PlayerHistory.recordRound(context, TOKEN, 20, 1);
        assertEquals(PlayerHistory.MAX_TOTAL, PlayerHistory.kills(context));
        assertEquals(PlayerHistory.MAX_TOTAL - 1, PlayerHistory.baselineKills(context, TOKEN));
        assertEquals(0, PlayerHistory.baselineDeaths(context, TOKEN));
    }

    @Test public void registrationBaselineTokenSurvivesPartialLeaveAndOnlyChangesForNewRound() {
        assertNull(PlayerHistory.baselineRoundToken(context));
        assertNull(PlayerHistory.baselineRoundToken(null));
        PlayerHistory.beginRound(context, TOKEN);
        PlayerHistory.recordRound(context, TOKEN, 3, 2);
        assertEquals(TOKEN, PlayerHistory.baselineRoundToken(context));
        assertEquals(3, PlayerHistory.kills(context));
        assertEquals(2, PlayerHistory.deaths(context));
        String next = "22222222-2222-2222-2222-222222222222";
        PlayerHistory.beginRound(context, next);
        assertEquals(next, PlayerHistory.baselineRoundToken(context));
        preferences.edit().putString("HistoryBaselineRound", "invalid").commit();
        assertNull(PlayerHistory.baselineRoundToken(context));
        preferences.edit().putInt("HistoryBaselineRound", 1).commit();
        assertNull(PlayerHistory.baselineRoundToken(context));
    }

    @Test public void historySaturatesWithoutIntegerOverflowAndDuplicateRoundCannotAddAgain() {
        PlayerHistory.recordRound(context, TOKEN, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(PlayerHistory.MAX_TOTAL, PlayerHistory.kills(context));
        assertEquals(PlayerHistory.MAX_TOTAL, PlayerHistory.deaths(context));
        PlayerHistory.recordRound(context, TOKEN, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(100, PlayerHistory.skillIndex(context));
    }

    @Test public void unattachedContextsDoNotCrashOrInventHistory() {
        PlayerHistory.recordRound(null, TOKEN, 5, 3);
        assertEquals(100, PlayerHistory.beginRound(null, TOKEN));
        assertEquals(0, PlayerHistory.baselineKills(null, TOKEN));
        assertEquals(0, PlayerHistory.baselineDeaths(null, TOKEN));
    }
}
