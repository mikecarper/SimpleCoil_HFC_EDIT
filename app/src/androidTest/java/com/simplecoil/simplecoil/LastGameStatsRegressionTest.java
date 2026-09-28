package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LastGameStatsRegressionTest {
    @Test public void absentCorruptAndSavedHistoryAreHandled() {
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("LastGameStatsRegressionTest", Context.MODE_PRIVATE);
        try {
            preferences.edit().clear().commit();
            assertNull(LastGameStats.load(preferences));
            new LastGameStats("Free for all", "", 7, 2, 15, -1, true).save(preferences);
            LastGameStats result = LastGameStats.load(preferences);
            assertNotNull(result);
            assertEquals("Free for all", result.mode);
            assertEquals(7, result.kills);
            assertEquals(2, result.deaths);
            assertEquals(15, result.hitsTaken);
            assertEquals(-1, result.teamKills);
            assertTrue(result.leftEarly);
            new LastGameStats("CTF", "Team 1", 7, 2, 15, 3, false, true).save(preferences);
            assertTrue(LastGameStats.load(preferences).captures);
            preferences.edit().putString("LastGame.kills", "not a number")
                    .putInt("LastGame.deaths", -50).putInt("LastGame.hitsTaken", Integer.MAX_VALUE)
                    .putInt("LastGame.mode", 7).commit();
            result = LastGameStats.load(preferences);
            assertEquals(0, result.kills);
            assertEquals(0, result.deaths);
            assertEquals(Globals.MAX_SCOREBOARD_VALUE, result.hitsTaken);
            assertEquals("", result.mode);
        } finally { preferences.edit().clear().commit(); }
    }
}
