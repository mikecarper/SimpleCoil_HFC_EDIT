package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import static org.junit.Assert.assertEquals;

/** Verifies that old or corrupt saved profile values cannot poison the next connection. */
@RunWith(AndroidJUnit4.class)
public class SavedProfileRegressionTest {
    private static final String PREF_PLAYER_NAME = "PlayerName";

    private SharedPreferences preferences;
    private Object savedPlayerName;
    private Object savedDeviceAddress;
    private Object savedGameMode;
    private String originalGlobalPlayerName;
    private boolean originalUseGPS;
    private int originalGameState;
    private int originalGameMode;
    private ActivityScenario<FullscreenActivity> scenario;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        savedPlayerName = preferences.getAll().get(PREF_PLAYER_NAME);
        savedDeviceAddress = preferences.getAll().get(FullscreenActivity.PREF_DEVICE_ADDRESS);
        savedGameMode = preferences.getAll().get(FullscreenActivity.PREF_GAME_MODE);
        originalGlobalPlayerName = Globals.getInstance().mPlayerName;
        originalUseGPS = Globals.getInstance().mUseGPS;
        originalGameState = Globals.getInstance().mGameState;
        originalGameMode = Globals.getInstance().mGameMode;
        preferences.edit()
                .putString(PREF_PLAYER_NAME, repeat('x', TcpJson.MAX_PLAYER_NAME_LENGTH + 1))
                .putString(FullscreenActivity.PREF_DEVICE_ADDRESS, "00:11:22:33:44:GG")
                .putString(FullscreenActivity.PREF_GAME_MODE, "not an integer")
                .commit();
        Globals.getInstance().mUseGPS = false;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        scenario = ActivityScenario.launch(FullscreenActivity.class);
    }

    @After
    public void tearDown() {
        if (scenario != null)
            scenario.close();
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            restorePreference(editor, PREF_PLAYER_NAME, savedPlayerName);
            restorePreference(editor, FullscreenActivity.PREF_DEVICE_ADDRESS, savedDeviceAddress);
            restorePreference(editor, FullscreenActivity.PREF_GAME_MODE, savedGameMode);
            editor.commit();
        }
        Globals.getInstance().mPlayerName = originalGlobalPlayerName;
        Globals.getInstance().mUseGPS = originalUseGPS;
        Globals.getInstance().mGameState = originalGameState;
        Globals.getInstance().mGameMode = originalGameMode;
    }

    @Test
    public void invalidSavedProfileValuesAreReplacedBeforeTheyCanStartAConnection() {
        scenario.onActivity(activity -> {
            assertEquals("Player", Globals.getInstance().mPlayerName);
            assertEquals("Player", preferences.getString(PREF_PLAYER_NAME, null));
            assertEquals("", preferences.getString(FullscreenActivity.PREF_DEVICE_ADDRESS, null));
            assertEquals(Globals.GAME_MODE_2TEAMS, Globals.getInstance().mGameMode);
            assertEquals(Globals.GAME_MODE_2TEAMS,
                    preferences.getInt(FullscreenActivity.PREF_GAME_MODE, -1));
            assertEquals(View.GONE, activity.findViewById(R.id.reconnect_weapon_button).getVisibility());
        });
    }

    @SuppressWarnings("unchecked")
    private static void restorePreference(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String)
            editor.putString(key, (String) value);
        else if (value instanceof Integer)
            editor.putInt(key, (Integer) value);
        else if (value instanceof Boolean)
            editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Long)
            editor.putLong(key, (Long) value);
        else if (value instanceof Float)
            editor.putFloat(key, (Float) value);
        else if (value instanceof Set)
            editor.putStringSet(key, (Set<String>) value);
        else
            editor.remove(key);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            result.append(value);
        return result.toString();
    }
}
