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

import static org.junit.Assert.assertEquals;

/** Verifies that old or corrupt saved profile values cannot poison the next connection. */
@RunWith(AndroidJUnit4.class)
public class SavedProfileRegressionTest {
    private static final String PREF_PLAYER_NAME = "PlayerName";

    private SharedPreferences preferences;
    private boolean hadPlayerName;
    private boolean hadDeviceAddress;
    private String savedPlayerName;
    private String savedDeviceAddress;
    private String originalGlobalPlayerName;
    private boolean originalUseGPS;
    private int originalGameState;
    private ActivityScenario<FullscreenActivity> scenario;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        hadPlayerName = preferences.contains(PREF_PLAYER_NAME);
        hadDeviceAddress = preferences.contains(FullscreenActivity.PREF_DEVICE_ADDRESS);
        savedPlayerName = preferences.getString(PREF_PLAYER_NAME, null);
        savedDeviceAddress = preferences.getString(FullscreenActivity.PREF_DEVICE_ADDRESS, null);
        originalGlobalPlayerName = Globals.getInstance().mPlayerName;
        originalUseGPS = Globals.getInstance().mUseGPS;
        originalGameState = Globals.getInstance().mGameState;
        preferences.edit()
                .putString(PREF_PLAYER_NAME, repeat('x', TcpJson.MAX_PLAYER_NAME_LENGTH + 1))
                .putString(FullscreenActivity.PREF_DEVICE_ADDRESS, "00:11:22:33:44:GG")
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
            if (hadPlayerName) editor.putString(PREF_PLAYER_NAME, savedPlayerName);
            else editor.remove(PREF_PLAYER_NAME);
            if (hadDeviceAddress) editor.putString(FullscreenActivity.PREF_DEVICE_ADDRESS, savedDeviceAddress);
            else editor.remove(FullscreenActivity.PREF_DEVICE_ADDRESS);
            editor.commit();
        }
        Globals.getInstance().mPlayerName = originalGlobalPlayerName;
        Globals.getInstance().mUseGPS = originalUseGPS;
        Globals.getInstance().mGameState = originalGameState;
    }

    @Test
    public void invalidSavedProfileValuesAreReplacedBeforeTheyCanStartAConnection() {
        scenario.onActivity(activity -> {
            assertEquals("Player", Globals.getInstance().mPlayerName);
            assertEquals("Player", preferences.getString(PREF_PLAYER_NAME, null));
            assertEquals("", preferences.getString(FullscreenActivity.PREF_DEVICE_ADDRESS, null));
            assertEquals(View.GONE, activity.findViewById(R.id.reconnect_weapon_button).getVisibility());
        });
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            result.append(value);
        return result.toString();
    }
}
