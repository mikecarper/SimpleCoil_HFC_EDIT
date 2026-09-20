package com.simplecoil.simplecoil;

import android.content.DialogInterface;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the actual dialog, including Android's button-dismiss behavior. */
@RunWith(AndroidJUnit4.class)
public class PlayerSettingsRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private PlayerSettingsAlertDialog dialog;
    private RecordingClient client;
    private RecordingServer server;
    private final Map<Field, Object> originalGlobals = new HashMap<>();
    private Map<Byte, Globals.PlayerSettings> originalSettings;
    private SharedPreferences preferences;
    private Object originalVibrationPreference;

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        String[] savedFields = {"mFullHealth", "mFullReload", "mReloadTime", "mReloadOnEmpty",
                "mRespawnTime", "mDamage", "mOverrideLives", "mOverrideLivesVal",
                "mAllowSingleShotMode", "mAllowBurst3ShotMode", "mAllowAutoShotMode",
                "mCurrentFiringMode", "mVibrateOnHit", "mAllowPlayerSettings", "mGameState", "mUseGPS",
                "mGameMode", "mGameLimit", "mTimeLimit", "mLivesLimit", "mScoreLimit", "mPlayerID"};
        for (String name : savedFields) {
            Field field = Globals.class.getDeclaredField(name);
            originalGlobals.put(field, field.get(globals));
        }
        Globals.getmPlayerSettingsSemaphore();
        try {
            originalSettings = new HashMap<>(globals.mPlayerSettings);
            globals.mPlayerSettings.clear();
        } finally { globals.mPlayerSettingsSemaphore.release(); }
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mUseGPS = false;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
        originalVibrationPreference = preferences.getAll().get(FullscreenActivity.PREF_VIBRATE_ON_HIT);
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(activity -> {
            globals.mFullHealth = 20;
            globals.mFullReload = 30;
            globals.mReloadTime = 1500;
            globals.mReloadOnEmpty = false;
            globals.mRespawnTime = 10;
            globals.mDamage = -1;
            globals.mOverrideLives = false;
            globals.mOverrideLivesVal = 0;
            globals.mAllowSingleShotMode = true;
            globals.mAllowBurst3ShotMode = true;
            globals.mAllowAutoShotMode = true;
            client = new RecordingClient();
            server = new RecordingServer();
            dialog = new PlayerSettingsAlertDialog(activity);
        });
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) {
            scenario.onActivity(activity -> {
                if (dialog != null) dialog.dismiss();
            });
            scenario.close();
        }
        Globals globals = Globals.getInstance();
        if (originalSettings != null) {
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
                globals.mPlayerSettings.putAll(originalSettings);
            } finally { globals.mPlayerSettingsSemaphore.release(); }
        }
        for (Map.Entry<Field, Object> entry : originalGlobals.entrySet())
            entry.getKey().set(globals, entry.getValue());
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            if (originalVibrationPreference instanceof Boolean)
                editor.putBoolean(FullscreenActivity.PREF_VIBRATE_ON_HIT,
                        (Boolean) originalVibrationPreference);
            else
                editor.remove(FullscreenActivity.PREF_VIBRATE_ON_HIT);
            editor.commit();
        }
    }

    @Test
    public void localMaximumSettingsRoundTripWithoutTruncation() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mFullHealth = 1000;
            globals.mRespawnTime = 1000;
            globals.mDamage = -1000;
            globals.mOverrideLives = true;
            globals.mOverrideLivesVal = 1000;
        });
        show(false);
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertLocalMaxima();
            assertEquals(1, client.saves);
            assertFalse(dialog.isShowing());
        });
    }

    @Test
    public void serverMaximumSettingsRoundTripWithoutTruncation() {
        scenario.onActivity(activity -> {
            Globals.PlayerSettings settings = new Globals.PlayerSettings();
            settings.health = 1000;
            settings.spawnTime = 1000;
            settings.damage = -1000;
            settings.overrideLives = true;
            settings.lives = 1000;
            Globals.getmPlayerSettingsSemaphore();
            try { Globals.getInstance().mPlayerSettings.put((byte) 1, settings); }
            finally { Globals.getInstance().mPlayerSettingsSemaphore.release(); }
        });
        show(true);
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            Globals.PlayerSettings settings = Globals.getInstance().mPlayerSettings.get((byte) 1);
            assertEquals(1000, settings.health);
            assertEquals(1000, settings.spawnTime);
            assertEquals(-1000, settings.damage);
            assertEquals(1000, settings.lives);
            assertEquals(1, server.saves);
            assertFalse(dialog.isShowing());
        });
    }

    @Test
    public void allFourFieldsAcceptTheSupportedMaximum() {
        show(false);
        scenario.onActivity(activity -> {
            field(R.id.health_et).setText("1000");
            field(R.id.spawn_time_et).setText("1000");
            field(R.id.damage_et).setText("1000");
            field(R.id.lives_et).setText("1000");
            toggle(R.id.override_lives_limit_switch).setChecked(true);
        });
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> assertLocalMaxima());
    }

    @Test
    public void invalidLocalShotModesKeepTheUnsavedEditsOpen() {
        show(false);
        enterInvalidShotModes();
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertTrue("Validation discarded the open dialog", dialog.isShowing());
            assertEquals("77", field(R.id.health_et).getText().toString());
            assertEquals(20, Globals.getInstance().mFullHealth);
            assertEquals(0, client.saves);
        });
    }

    @Test
    public void invalidServerShotModesKeepTheDialogOpenWithoutPublishing() {
        show(true);
        enterInvalidShotModes();
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertTrue(dialog.isShowing());
            assertEquals("77", field(R.id.health_et).getText().toString());
            assertTrue(Globals.getInstance().mPlayerSettings.isEmpty());
            assertEquals(0, server.saves);
        });
    }

    @Test
    public void correctingShotModesSavesThePreservedEditsOnce() {
        show(false);
        enterInvalidShotModes();
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertTrue(dialog.isShowing());
            toggle(R.id.shot_mode_single).setChecked(true);
        });
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertEquals(77, Globals.getInstance().mFullHealth);
            assertTrue(Globals.getInstance().mAllowSingleShotMode);
            assertFalse(Globals.getInstance().mAllowBurst3ShotMode);
            assertFalse(Globals.getInstance().mAllowAutoShotMode);
            assertEquals(1, client.saves);
            assertFalse(dialog.isShowing());
        });
    }

    @Test
    public void cancelDoesNotPublishEdits() {
        show(false);
        scenario.onActivity(activity -> field(R.id.health_et).setText("77"));
        click(DialogInterface.BUTTON_NEGATIVE);
        scenario.onActivity(activity -> {
            assertEquals(20, Globals.getInstance().mFullHealth);
            assertEquals(0, client.saves);
            assertFalse(dialog.isShowing());
        });
    }

    @Test
    public void localResetRestoresTheDefaultFiringRangeBeforePublishing() {
        scenario.onActivity(activity -> Globals.getInstance().mCurrentFiringMode =
                Globals.FIRING_MODE_INDOOR_NO_CONE);
        show(false);
        scenario.onActivity(activity -> dialog.findViewById(R.id.reset_defaults_button).performClick());
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertEquals(Globals.FIRING_MODE_OUTDOOR_NO_CONE,
                    Globals.getInstance().mCurrentFiringMode);
            assertEquals(1, client.saves);
        });
    }

    @Test
    public void localVibrationPreferenceIsSavedAndServerSettingsDoNotExposeIt() {
        scenario.onActivity(activity -> Globals.getInstance().mVibrateOnHit = false);
        show(false);
        scenario.onActivity(activity -> {
            assertFalse(toggle(R.id.vibrate_phone_switch).isChecked());
            toggle(R.id.vibrate_phone_switch).setChecked(true);
        });
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertTrue(Globals.getInstance().mVibrateOnHit);
            assertTrue(preferences.getBoolean(FullscreenActivity.PREF_VIBRATE_ON_HIT, false));
            dialog = new PlayerSettingsAlertDialog(activity);
        });
        show(true);
        scenario.onActivity(activity -> assertEquals(View.GONE,
                toggle(R.id.vibrate_phone_switch).getVisibility()));
    }

    @Test
    public void serverPolicyCanBeDisabledWhileTheServiceBindingIsMissing() {
        assertPolicySavedWithoutBinding(true, false);
    }

    @Test
    public void serverPolicyCanBeEnabledWhileTheServiceBindingIsMissing() {
        assertPolicySavedWithoutBinding(false, true);
    }

    private void assertPolicySavedWithoutBinding(boolean original, boolean replacement) {
        scenario.onActivity(activity -> {
            Globals.getInstance().mAllowPlayerSettings = original;
            dialog.setServer((byte) 1, null);
            dialog.show();
            toggle(R.id.allow_player_settings_switch).setChecked(replacement);
        });
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertEquals(replacement, Globals.getInstance().mAllowPlayerSettings);
            assertFalse(dialog.isShowing());
            dialog = new PlayerSettingsAlertDialog(activity);
            dialog.setServer((byte) 1, null);
            dialog.show();
            assertEquals(replacement, toggle(R.id.allow_player_settings_switch).isChecked());
        });
    }

    @Test
    public void serverPolicyIsSavedBeforePublishingTheSettings() {
        scenario.onActivity(activity -> Globals.getInstance().mAllowPlayerSettings = true);
        show(true);
        scenario.onActivity(activity -> toggle(R.id.allow_player_settings_switch).setChecked(false));
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertFalse(Globals.getInstance().mAllowPlayerSettings);
            assertEquals(1, server.saves);
            assertFalse(server.policyAtSave);
        });
    }

    @Test
    public void cancellingServerSettingsDoesNotChangeThePolicy() {
        scenario.onActivity(activity -> Globals.getInstance().mAllowPlayerSettings = true);
        show(true);
        scenario.onActivity(activity -> toggle(R.id.allow_player_settings_switch).setChecked(false));
        click(DialogInterface.BUTTON_NEGATIVE);
        scenario.onActivity(activity -> {
            assertTrue(Globals.getInstance().mAllowPlayerSettings);
            assertEquals(0, server.saves);
        });
    }

    @Test
    public void invalidServerSettingsDoNotChangeThePolicyUntilCorrected() {
        scenario.onActivity(activity -> Globals.getInstance().mAllowPlayerSettings = true);
        show(true);
        enterInvalidShotModes();
        scenario.onActivity(activity -> toggle(R.id.allow_player_settings_switch).setChecked(false));
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertTrue(Globals.getInstance().mAllowPlayerSettings);
            assertEquals(0, server.saves);
            assertTrue(dialog.isShowing());
            toggle(R.id.shot_mode_single).setChecked(true);
        });
        click(DialogInterface.BUTTON_POSITIVE);
        scenario.onActivity(activity -> {
            assertFalse(Globals.getInstance().mAllowPlayerSettings);
            assertEquals(1, server.saves);
            assertFalse(dialog.isShowing());
        });
    }

    private void show(boolean isServer) {
        scenario.onActivity(activity -> {
            if (isServer) dialog.setServer((byte) 1, server);
            else dialog.setLocal(client);
            dialog.show();
        });
    }

    private void enterInvalidShotModes() {
        scenario.onActivity(activity -> {
            field(R.id.health_et).setText("77");
            toggle(R.id.shot_mode_single).setChecked(false);
            toggle(R.id.shot_mode_burst3).setChecked(false);
            toggle(R.id.shot_mode_auto).setChecked(false);
        });
    }

    private void click(int button) {
        scenario.onActivity(activity -> dialog.getButton(button).performClick());
        // AlertDialog's default button listener queues dismissal on a Handler.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void assertLocalMaxima() {
        Globals globals = Globals.getInstance();
        assertEquals(1000, globals.mFullHealth);
        assertEquals(1000, globals.mRespawnTime);
        assertEquals(-1000, globals.mDamage);
        assertEquals(1000, globals.mOverrideLivesVal);
    }

    private EditText field(int id) { return dialog.findViewById(id); }
    private Switch toggle(int id) { return dialog.findViewById(id); }

    private static final class RecordingClient extends TcpClient {
        int saves;
        @Override public void sendPlayerSettings() { saves++; }
    }

    private static final class RecordingServer extends TcpServer {
        int saves;
        boolean policyAtSave;
        @Override public void sendPlayerSettings(int id, boolean applyAll, boolean allowSettings) {
            saves++;
            policyAtSave = Globals.getInstance().mAllowPlayerSettings;
        }
    }
}
