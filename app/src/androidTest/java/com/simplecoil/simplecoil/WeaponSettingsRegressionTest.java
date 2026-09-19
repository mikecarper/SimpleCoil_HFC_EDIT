package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Checks real settings/telemetry callbacks with captured blaster configuration packets. */
@RunWith(AndroidJUnit4.class)
public class WeaponSettingsRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity activity;
    private RecordingBluetoothService bluetooth;
    private BluetoothLeService originalBluetooth;
    private SharedPreferences preferences;
    private boolean hadShotPreference;
    private int originalShotPreference;
    private byte originalBlasterType;
    private byte originalLastTeam;
    private final Map<Field, Object> originalGlobals = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        String[] savedFields = {"mAllowSingleShotMode", "mAllowBurst3ShotMode", "mAllowAutoShotMode",
                "mCurrentFiringMode", "mGameState", "mUseGPS", "mPlayerID", "mPlayerName",
                "mGameMode", "mGameLimit", "mTimeLimit", "mLivesLimit", "mScoreLimit",
                "mOverrideLives", "mFullReload"};
        for (String name : savedFields) {
            Field field = Globals.class.getDeclaredField(name);
            originalGlobals.put(field, field.get(globals));
        }
        preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        hadShotPreference = preferences.contains("ShotMode");
        originalShotPreference = preferences.getInt("ShotMode", Globals.SHOT_MODE_SINGLE);
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mUseGPS = false;
        allowModes(true, true, true);
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            originalBluetooth = (BluetoothLeService) get("mBluetoothLeService");
            originalBlasterType = (byte) get("mBlasterType");
            originalLastTeam = (byte) get("mLastTeam");
            bluetooth = new RecordingBluetoothService();
            set("mBluetoothLeService", bluetooth);
            set("mConfigCharacteristic", characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            set("mTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set("mUseNetwork", false);
            set("mReady", false);
            set("mCurrentShotMode", Globals.SHOT_MODE_SINGLE);
            set("mBlasterType", (byte) 2);
            set("mLastTeam", (byte) 0);
            set("mRecoilEnabled", true);
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mOverrideLives = false;
            globals.mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            globals.mPlayerID = 0;
            globals.mFullReload = 30;
        });
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) {
            scenario.onActivity(current -> {
                set("mBluetoothLeService", originalBluetooth);
                set("mBlasterType", originalBlasterType);
                set("mLastTeam", originalLastTeam);
            });
            scenario.close();
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (hadShotPreference) editor.putInt("ShotMode", originalShotPreference);
        else editor.remove("ShotMode");
        editor.commit();
        for (Map.Entry<Field, Object> entry : originalGlobals.entrySet())
            entry.getKey().set(Globals.getInstance(), entry.getValue());
    }

    @Test
    public void indoorRangeUpdateReachesAnAlreadyAllowedSingleShotBlaster() {
        assertRangeUpdate(Globals.SHOT_MODE_SINGLE, Globals.FIRING_MODE_INDOOR_NO_CONE, 0x19, 0);
    }

    @Test
    public void outdoorConeUpdateReachesAnAlreadyAllowedBurstBlaster() {
        assertRangeUpdate(Globals.SHOT_MODE_BURST, Globals.FIRING_MODE_OUTDOOR_WITH_CONE, 0xff, 0xc8);
    }

    @Test
    public void outdoorNoConeUpdateReachesAnAlreadyAllowedAutomaticBlaster() {
        assertRangeUpdate(Globals.SHOT_MODE_FULL_AUTO, Globals.FIRING_MODE_OUTDOOR_NO_CONE, 0xff, 0);
    }

    private void assertRangeUpdate(int mode, int firingMode, int power, int cone) {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", mode);
            Globals.getInstance().mCurrentFiringMode = firingMode;
            settingsUpdated();
            assertEquals("A settings update did not configure the blaster", 1, bluetooth.configs.size());
            assertMode(mode);
            assertEquals(power, lastConfig()[5] & 0xff);
            assertEquals(cone, lastConfig()[6] & 0xff);
        });
    }

    @Test
    public void bannedSingleModeStillSelectsBurstWithOneConfigurationWrite() {
        assertRestrictedUpdate(Globals.SHOT_MODE_SINGLE, false, true, true, Globals.SHOT_MODE_BURST);
    }

    @Test
    public void bannedBurstModeStillSelectsAutomaticWithOneConfigurationWrite() {
        assertRestrictedUpdate(Globals.SHOT_MODE_BURST, true, false, true, Globals.SHOT_MODE_FULL_AUTO);
    }

    @Test
    public void bannedAutomaticModeStillSelectsSingleWithOneConfigurationWrite() {
        assertRestrictedUpdate(Globals.SHOT_MODE_FULL_AUTO, true, true, false, Globals.SHOT_MODE_SINGLE);
    }

    private void assertRestrictedUpdate(int mode, boolean single, boolean burst, boolean automatic, int expected) {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", mode);
            allowModes(single, burst, automatic);
            settingsUpdated();
            assertEquals(1, bluetooth.configs.size());
            assertMode(expected);
        });
    }

    @Test
    public void disconnectedSettingsStillUpdateTheSelectedModeAndLabel() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", Globals.SHOT_MODE_FULL_AUTO);
            set("mConfigCharacteristic", null);
            allowModes(false, true, false);
            settingsUpdated();
            assertEquals(Globals.SHOT_MODE_BURST, get("mCurrentShotMode"));
            assertEquals(Globals.SHOT_MODE_BURST, preferences.getInt("ShotMode", -1));
            assertEquals(current.getString(R.string.shot_mode_burst3),
                    ((TextView) get("mShotModeTV")).getText().toString());
            assertTrue(bluetooth.configs.isEmpty());
        });
    }

    @Test
    public void settingsStillRememberTheAllowedModeWithoutABluetoothBinding() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", Globals.SHOT_MODE_BURST);
            set("mBluetoothLeService", null);
            allowModes(true, false, false);
            settingsUpdated();
            assertEquals(Globals.SHOT_MODE_SINGLE, get("mCurrentShotMode"));
            assertEquals(Globals.SHOT_MODE_SINGLE, preferences.getInt("ShotMode", -1));
            assertTrue(bluetooth.configs.isEmpty());
        });
    }

    @Test
    public void firstTelemetryAfterReconnectAppliesRestrictionsReceivedWhileDisconnected() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", Globals.SHOT_MODE_FULL_AUTO);
            set("mConfigCharacteristic", null);
            allowModes(true, false, false);
            Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
            settingsUpdated();
            set("mConfigCharacteristic", characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            firstTelemetry();
            assertEquals(1, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_SINGLE);
            assertEquals(0x19, lastConfig()[5] & 0xff);
        });
    }

    @Test
    public void invalidSavedModeCannotSendAZeroShotConfiguration() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", 99);
            allowModes(false, true, false);
            firstTelemetry();
            assertEquals(1, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_BURST);
        });
    }

    @Test
    public void directModeSelectionCannotBypassServerRestrictions() {
        scenario.onActivity(current -> {
            allowModes(true, false, false);
            selectMode(Globals.SHOT_MODE_FULL_AUTO);
            assertMode(Globals.SHOT_MODE_SINGLE);
        });
    }

    @Test
    public void lateRifleIdentificationCorrectsTheBurstRecoil() {
        scenario.onActivity(current -> {
            selectMode(Globals.SHOT_MODE_BURST);
            assertEquals(0x80, lastConfig()[9] & 0xff);
            identify((byte) 1);
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_BURST);
            assertEquals(0x78, lastConfig()[9] & 0xff);
        });
    }

    @Test
    public void latePistolIdentificationRemovesTheOldRifleRecoilSetting() {
        scenario.onActivity(current -> {
            set("mBlasterType", (byte) 1);
            selectMode(Globals.SHOT_MODE_BURST);
            assertEquals(0x78, lastConfig()[9] & 0xff);
            identify((byte) 2);
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_BURST);
            assertEquals(0x80, lastConfig()[9] & 0xff);
        });
    }

    @Test
    public void rifleIdentificationKeepsTheAllowedAutomaticMode() {
        scenario.onActivity(current -> {
            selectMode(Globals.SHOT_MODE_FULL_AUTO);
            identify((byte) 1);
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_FULL_AUTO);
            assertEquals(0x80, lastConfig()[9] & 0xff);
        });
    }

    private void settingsUpdated() {
        ((BroadcastReceiver) get("mUDPUpdateReceiver")).onReceive(activity,
                new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
    }

    private void identify(byte type) {
        ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(activity,
                new Intent(BluetoothLeService.ID_DATA_AVAILABLE).putExtra(BluetoothLeService.EXTRA_DATA, type));
    }

    private void firstTelemetry() {
        set("mCommunicating", false);
        byte[] packet = new byte[20];
        packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 30;
        packet[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL] = 16;
        ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(activity,
                new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                        .putExtra(BluetoothLeService.EXTRA_DATA, packet));
    }

    private void selectMode(int mode) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("setShotMode", int.class);
            method.setAccessible(true);
            method.invoke(activity, mode);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void assertMode(int mode) {
        assertEquals(mode, get("mCurrentShotMode"));
        assertEquals(9, lastConfig()[2]);
        assertEquals(mode == Globals.SHOT_MODE_BURST ? 3 : 0xfe, lastConfig()[3] & 0xff);
        assertEquals(mode == Globals.SHOT_MODE_BURST ? 3 : mode == Globals.SHOT_MODE_SINGLE ? 0 : 1,
                lastConfig()[4] & 0xff);
    }

    private byte[] lastConfig() { return bluetooth.configs.get(bluetooth.configs.size() - 1); }

    private static void allowModes(boolean single, boolean burst, boolean automatic) {
        Globals globals = Globals.getInstance();
        globals.mAllowSingleShotMode = single;
        globals.mAllowBurst3ShotMode = burst;
        globals.mAllowAutoShotMode = automatic;
    }

    private Object get(String name) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void set(String name, Object value) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), 0, 0);
    }

    private static final class RecordingBluetoothService extends BluetoothLeService {
        final List<byte[]> configs = new ArrayList<>();
        @Override public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            if (GattAttributes.RECOIL_CONFIG_UUID.equals(characteristic.getUuid().toString()))
                configs.add(value.clone());
        }
    }
}
