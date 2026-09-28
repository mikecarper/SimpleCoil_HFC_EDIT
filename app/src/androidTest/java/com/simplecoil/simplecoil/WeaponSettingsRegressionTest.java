package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGatt;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Checks real settings/telemetry callbacks with captured blaster configuration packets. */
@RunWith(AndroidJUnit4.class)
public class WeaponSettingsRegressionTest {
    private static final String TEST_GUN_ONE = "02:00:00:00:00:01";
    private static final String TEST_GUN_TWO = "02:00:00:00:00:02";
    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity activity;
    private RecordingBluetoothService bluetooth;
    private BluetoothLeService originalBluetooth;
    private BluetoothLeService originalSecondaryBluetooth;
    private SharedPreferences preferences;
    private boolean hadShotPreference;
    private int originalShotPreference;
    private boolean hadRecoilPreference;
    private boolean originalRecoilPreference;
    private boolean hadFiringPreference;
    private int originalFiringPreference;
    private byte originalBlasterType;
    private byte originalLastTeam;
    private final Map<Field, Object> originalGlobals = new HashMap<>();
    private final Map<String, Boolean> originalPowerPreferences = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        String[] savedFields = {"mAllowSingleShotMode", "mAllowBurst3ShotMode", "mAllowAutoShotMode",
                "mCurrentFiringMode", "mGameState", "mUseGPS", "mPlayerID", "mPlayerName",
                "mGameMode", "mGameLimit", "mTimeLimit", "mLivesLimit", "mScoreLimit",
                "mOverrideLives", "mFullReload", "mBossMode", "mTournamentMode"};
        for (String name : savedFields) {
            Field field = Globals.class.getDeclaredField(name);
            originalGlobals.put(field, field.get(globals));
        }
        preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        hadShotPreference = preferences.contains("ShotMode");
        originalShotPreference = preferences.getInt("ShotMode", Globals.SHOT_MODE_SINGLE);
        hadRecoilPreference = preferences.contains("RecoilEnabled");
        originalRecoilPreference = preferences.getBoolean("RecoilEnabled", true);
        hadFiringPreference = preferences.contains(FullscreenActivity.PREF_FIRING_MODE);
        originalFiringPreference = preferences.getInt(FullscreenActivity.PREF_FIRING_MODE, 0);
        for (String address : new String[]{TEST_GUN_ONE, TEST_GUN_TWO}) {
            String key = FullscreenActivity.PREF_GUN_LOW_POWER_PREFIX + address;
            if (preferences.contains(key)) originalPowerPreferences.put(key, preferences.getBoolean(key, false));
            preferences.edit().remove(key).commit();
        }
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mUseGPS = false;
        allowModes(true, true, true);
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            // Synthetic battery telemetry must never reconfigure a physical gun
            // or persist its low-power state against the user's real MAC address.
            invoke("resetBluetoothServices", new Class<?>[0]);
            invoke("resetSecondaryBluetoothServices", new Class<?>[0]);
            if ((boolean) get("mGattReceiverRegistered")) {
                current.unregisterReceiver((BroadcastReceiver) get("mGattUpdateReceiver"));
                set("mGattReceiverRegistered", false);
            }
            set("mDeviceAddress", TEST_GUN_ONE);
            set("mSecondaryDeviceAddress", "");
            originalBluetooth = (BluetoothLeService) get("mBluetoothLeService");
            originalSecondaryBluetooth = (BluetoothLeService) get("mSecondaryBluetoothLeService");
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
                set("mSecondaryBluetoothLeService", originalSecondaryBluetooth);
                set("mBlasterType", originalBlasterType);
                set("mLastTeam", originalLastTeam);
            });
            scenario.close();
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (hadShotPreference) editor.putInt("ShotMode", originalShotPreference);
        else editor.remove("ShotMode");
        if (hadRecoilPreference) editor.putBoolean("RecoilEnabled", originalRecoilPreference);
        else editor.remove("RecoilEnabled");
        if (hadFiringPreference) editor.putInt(FullscreenActivity.PREF_FIRING_MODE, originalFiringPreference);
        else editor.remove(FullscreenActivity.PREF_FIRING_MODE);
        for (String address : new String[]{TEST_GUN_ONE, TEST_GUN_TWO}) {
            String key = FullscreenActivity.PREF_GUN_LOW_POWER_PREFIX + address;
            if (originalPowerPreferences.containsKey(key)) editor.putBoolean(key, originalPowerPreferences.get(key));
            else editor.remove(key);
        }
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
    public void outdoorBossUpdateAutomaticallyEnablesWideCone() {
        assertRangeUpdate(Globals.SHOT_MODE_FULL_AUTO, Globals.FIRING_MODE_OUTDOOR_NO_CONE, 0xff, 0xc8);
    }

    @Test public void bossRangeSelectionUpdatesBothGunsAndPreservesIndoorMode() {
        scenario.onActivity(current -> {
            enableBossRole();
            set("mCurrentShotMode", Globals.SHOT_MODE_FULL_AUTO);
            RecordingBluetoothService second = new RecordingBluetoothService();
            set("mSecondaryBluetoothLeService", second);
            set("mSecondaryConfigCharacteristic", characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            for (int requested : new int[]{Globals.FIRING_MODE_OUTDOOR_NO_CONE,
                    Globals.FIRING_MODE_INDOOR_NO_CONE, Globals.FIRING_MODE_OUTDOOR_WITH_CONE,
                    Globals.FIRING_MODE_OUTDOOR_NO_CONE}) {
                invoke("selectFiringMode", new Class<?>[]{int.class}, requested);
                boolean indoor = requested == Globals.FIRING_MODE_INDOOR_NO_CONE;
                int expected = indoor ? Globals.FIRING_MODE_INDOOR_NO_CONE
                        : Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                assertEquals(expected, Globals.getInstance().mCurrentFiringMode);
                assertEquals(expected, preferences.getInt(FullscreenActivity.PREF_FIRING_MODE, -1));
                assertMode(Globals.SHOT_MODE_FULL_AUTO);
                assertEquals(indoor ? 0x19 : 0xff, lastShotConfig()[5] & 0xff);
                assertEquals(indoor ? 0 : 0xc8, lastShotConfig()[6] & 0xff);
                org.junit.Assert.assertArrayEquals(lastShotConfig(),
                        second.configs.get(second.configs.size() - 1));
                assertEquals(current.getString(indoor ? R.string.firing_mode_indoor_no_cone
                                : R.string.firing_mode_outdoor_with_cone),
                        ((TextView) get("mFiringModeButton")).getText().toString());
            }
        });
    }

    @Test public void reconnectAppliesOutdoorBossConeWithoutOpeningSettings() {
        scenario.onActivity(current -> {
            enableBossRole();
            Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
            firstTelemetry();
            assertEquals(Globals.FIRING_MODE_OUTDOOR_WITH_CONE,
                    Globals.getInstance().mCurrentFiringMode);
            assertEquals(0xff, lastShotConfig()[5] & 0xff);
            assertEquals(0xc8, lastShotConfig()[6] & 0xff);
        });
    }

    @Test public void switchingFromBossToHunterRemovesTheWideCone() {
        scenario.onActivity(current -> {
            enableBossRole();
            Globals.getInstance().mTournamentMode = true;
            settingsUpdated();
            assertEquals(0xc8, lastShotConfig()[6] & 0xff);
            Globals.getInstance().mPlayerID = 2;
            settingsUpdated();
            assertMode(Globals.SHOT_MODE_SINGLE);
            assertEquals(0xff, lastShotConfig()[5] & 0xff);
            assertEquals(0, lastShotConfig()[6] & 0xff);
            assertEquals(Globals.FIRING_MODE_OUTDOOR_NO_CONE,
                    Globals.getInstance().mCurrentFiringMode);
        });
    }

    private void assertRangeUpdate(int mode, int firingMode, int power, int cone) {
        scenario.onActivity(current -> {
            if (mode != Globals.SHOT_MODE_SINGLE
                    || firingMode == Globals.FIRING_MODE_OUTDOOR_WITH_CONE) {
                enableBossRole();
                set("mBossWeaponInitialized", true);
            }
            set("mCurrentShotMode", mode);
            Globals.getInstance().mCurrentFiringMode = firingMode;
            settingsUpdated();
            assertEquals("Recoil and shot-mode configurations were not both sent",
                    2, bluetooth.configs.size());
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
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_SINGLE);
        });
    }

    @Test
    public void disconnectedSettingsStillUpdateTheSelectedModeAndLabel() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", Globals.SHOT_MODE_FULL_AUTO);
            set("mConfigCharacteristic", null);
            allowModes(false, true, false);
            settingsUpdated();
            assertEquals(Globals.SHOT_MODE_SINGLE, get("mCurrentShotMode"));
            assertEquals(Globals.SHOT_MODE_SINGLE, preferences.getInt("ShotMode", -1));
            assertEquals(current.getString(R.string.shot_mode_single),
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
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_SINGLE);
            assertEquals(0x19, lastShotConfig()[5] & 0xff);
        });
    }

    @Test
    public void invalidSavedModeCannotSendAZeroShotConfiguration() {
        scenario.onActivity(current -> {
            set("mCurrentShotMode", 99);
            allowModes(false, true, false);
            firstTelemetry();
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_SINGLE);
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
            enableBossRole();
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
            enableBossRole();
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
            enableBossRole();
            selectMode(Globals.SHOT_MODE_FULL_AUTO);
            identify((byte) 1);
            assertEquals(2, bluetooth.configs.size());
            assertMode(Globals.SHOT_MODE_FULL_AUTO);
            assertEquals(0x80, lastConfig()[9] & 0xff);
        });
    }

    @Test
    public void recoilCounterCannotTogglePrimaryRecoil() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = false;
            prepareTelemetry(false, 0x07);
            for (int recoilCount = 1; recoilCount <= 16; recoilCount++)
                telemetryCounters(false, ((recoilCount & 15) << 4) | 0x07);
            assertEquals(true, get("mRecoilEnabled"));
            assertTrue("Recoil cycles must not enqueue configuration writes", bluetooth.configs.isEmpty());
        });
    }

    @Test
    public void recoilCounterCannotToggleSecondaryRecoil() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = false;
            enableBossRole();
            set("mSecondaryBluetoothLeService", bluetooth);
            set("mSecondaryConfigCharacteristic", characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            prepareTelemetry(true, 0x03);
            for (int recoilCount = 1; recoilCount <= 16; recoilCount++)
                telemetryCounters(true, ((recoilCount & 15) << 4) | 0x03);
            assertEquals(true, get("mRecoilEnabled"));
            assertTrue("A second gun's recoil must not reconfigure either gun", bluetooth.configs.isEmpty());
        });
    }

    @Test
    public void powerButtonStillTogglesOnceWhenCounterWraps() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = false;
            prepareTelemetry(false, 0x0f);
            telemetryCounters(false, 0xa0);
            assertEquals(false, get("mRecoilEnabled"));
            assertEquals(1, bluetooth.configs.size());
            assertEquals(2, lastConfig()[3]);
            telemetryCounters(false, 0xb0);
            telemetryCounters(false, 0xb0);
            assertEquals(1, bluetooth.configs.size());
            telemetryCounters(false, 0xb1);
            assertEquals(true, get("mRecoilEnabled"));
            assertEquals(2, bluetooth.configs.size());
            assertEquals(3, lastConfig()[3]);
        });
    }

    @Test
    public void tournamentRecoilCyclesDoNotFloodTheGattQueue() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = true;
            prepareTelemetry(false, 0);
            telemetryCounters(false, 0x10);
            telemetryCounters(false, 0x20);
            assertTrue(bluetooth.configs.isEmpty());
            telemetryCounters(false, 0x21);
            assertEquals(true, get("mRecoilEnabled"));
            assertEquals(1, bluetooth.configs.size());
            assertEquals(3, lastConfig()[3]);
        });
    }

    @Test public void lowPowerDisablesTournamentRecoilWithoutChangingWeaponStats() {
        scenario.onActivity(current -> {
            Globals globals = Globals.getInstance();
            globals.mTournamentMode = true;
            globals.mBossMode = false;
            prepareTelemetry(false, 0);
            invoke("setRecoil", new Class<?>[]{boolean.class}, true);
            bluetooth.configs.clear();
            GunBattery battery = (GunBattery) get("mGunBattery");
            battery.setBlasterType(2);
            batteryPackets(false, 2704, 19);
            assertTrue(bluetooth.configs.isEmpty());
            batteryPackets(false, 2704, 1);
            assertEquals(1, bluetooth.configs.size());
            assertEquals(0x10, lastConfig()[0]);
            assertEquals(2, lastConfig()[2]);
            assertEquals(2, lastConfig()[3]);
            assertEquals(255, lastConfig()[4] & 255);
            assertEquals(true, get("mRecoilEnabled"));
            assertTrue(preferences.getBoolean("RecoilEnabled", false));
            assertEquals(Globals.SHOT_MODE_SINGLE, get("mCurrentShotMode"));
            assertEquals(30, globals.mFullReload);
            assertEquals(current.getString(R.string.recoil_disabled),
                    ((TextView) get("mRecoilModeTV")).getText().toString());
            assertEquals(current.getString(R.string.gun_low_power),
                    ((TextView) get("mRecoilLabelTV")).getText().toString());
            assertTrue(((TextView) current.findViewById(R.id.lobby_gun_battery))
                    .getText().toString().contains("Low power - recoil off"));
            batteryPackets(false, 3368, 100);
            assertEquals("Voltage rebound must not restart recoil", 1, bluetooth.configs.size());
        });
    }

    @Test public void cutoffRefreshesTheHudEvenWhenTheRoundedVoltageDoesNotChange() {
        scenario.onActivity(current -> {
            prepareTelemetry(false, 0);
            long[] now = {0L};
            GunBattery battery = new GunBattery(() -> now[0]);
            set("mGunBattery", battery);
            battery.setBlasterType(2);
            batteryPackets(false, 2706, 100);
            assertEquals(40, battery.voltageTenths());
            assertTrue(bluetooth.configs.isEmpty());
            now[0] = 1_000;
            batteryPackets(false, 2704, 100);
            assertTrue(bluetooth.configs.isEmpty());
            now[0] = 15_000;
            batteryPackets(false, 2704, 1);
            assertEquals(40, battery.voltageTenths());
            assertEquals(1, bluetooth.configs.size());
            assertEquals(current.getString(R.string.gun_low_power),
                    ((TextView) get("mRecoilLabelTV")).getText().toString());
        });
    }

    @Test public void recoilDipsInTheBottomThreeQuartersDoNotDisableTournamentRecoil() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = true;
            prepareTelemetry(false, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            batteryPackets(false, 3368, 25);
            batteryPackets(false, 2000, 75);
            assertTrue(bluetooth.configs.isEmpty());
            assertEquals(current.getString(R.string.recoil_enabled),
                    ((TextView) get("mRecoilModeTV")).getText().toString());
            assertTrue(((TextView) current.findViewById(R.id.lobby_gun_battery))
                    .getText().toString().contains(current.getString(R.string.gun_battery_voltage, 5.0)));
        });
    }

    @Test public void tournamentSettingsAndPowerButtonCannotOverrideLowPower() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = true;
            Globals.getInstance().mBossMode = false;
            prepareTelemetry(false, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            batteryPackets(false, 2704, 20);
            bluetooth.configs.clear();
            telemetryCounters(false, 1);
            settingsUpdated();
            invoke("setRecoil", new Class<?>[]{boolean.class}, true); // Game-start enforcement.
            int recoilWrites = 0;
            for (byte[] config : bluetooth.configs) {
                if (config[0] == 0x10 && config[2] == 2) {
                    recoilWrites++;
                    assertEquals(2, config[3]);
                }
            }
            assertEquals(3, recoilWrites);
            assertMode(Globals.SHOT_MODE_SINGLE);
            assertEquals(true, get("mRecoilEnabled"));
        });
    }

    @Test public void reconnectRemembersLowPowerUntilFreshHealthyReadings() {
        scenario.onActivity(current -> {
            Globals.getInstance().mTournamentMode = false;
            Globals.getInstance().mBossMode = false;
            prepareTelemetry(false, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            batteryPackets(false, 2704, 20);
            String key = FullscreenActivity.PREF_GUN_LOW_POWER_PREFIX + TEST_GUN_ONE;
            assertTrue(preferences.getBoolean(key, false));
            // Drop the activity's in-memory latch to exercise preference restoration.
            ((GunPowerMode[]) get("mGunPowerModes"))[0].reset(false);
            ((String[]) get("mGunPowerAddresses"))[0] = null;
            invoke("resetBatteryLevel", new Class<?>[]{boolean.class}, false);
            bluetooth.configs.clear();
            firstTelemetry();
            assertEquals(2, lastConfig()[3]); // Off even before type/voltage warm-up.
            identify((byte) 2);
            bluetooth.configs.clear();
            batteryPackets(false, 3368, 19);
            assertEquals(1, bluetooth.configs.size());
            assertEquals(3, lastConfig()[3]);
            assertFalse(preferences.contains(key));
        });
    }

    @Test public void switchingGunsCannotInheritAnotherGunsLowPowerLatch() {
        scenario.onActivity(current -> {
            Globals.getInstance().mBossMode = false;
            prepareTelemetry(false, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            batteryPackets(false, 2704, 20);
            set("mDeviceAddress", TEST_GUN_TWO);
            invoke("resetBatteryLevel", new Class<?>[]{boolean.class}, false);
            bluetooth.configs.clear();
            firstTelemetry();
            assertEquals(3, lastConfig()[3]);
            set("mDeviceAddress", TEST_GUN_ONE);
            invoke("resetBatteryLevel", new Class<?>[]{boolean.class}, false);
            bluetooth.configs.clear();
            firstTelemetry();
            assertEquals(2, lastConfig()[3]);
        });
    }

    @Test public void bossGunsHaveIndependentLowPowerCommandsAndIndicators() {
        scenario.onActivity(current -> {
            enableBossRole();
            Globals.getInstance().mTournamentMode = true;
            RecordingBluetoothService second = new RecordingBluetoothService();
            set("mSecondaryBluetoothLeService", second);
            set("mSecondaryConfigCharacteristic", characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            set("mSecondaryDeviceAddress", TEST_GUN_TWO);
            prepareTelemetry(false, 0);
            prepareTelemetry(true, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            ((GunBattery) get("mSecondGunBattery")).setBlasterType(2);
            batteryPackets(false, 3368, 20);
            batteryPackets(true, 2704, 20);
            assertTrue(bluetooth.configs.isEmpty());
            assertEquals(1, second.configs.size());
            assertEquals(2, second.configs.get(0)[3]);
            invoke("setRecoil", new Class<?>[]{boolean.class}, true);
            assertEquals(3, lastConfig()[3]);
            assertEquals(2, second.configs.get(1)[3]);
            assertEquals(android.view.View.GONE, current.findViewById(R.id.boss_primary_power_tv).getVisibility());
            assertEquals(android.view.View.VISIBLE, current.findViewById(R.id.boss_secondary_power_tv).getVisibility());
        });
    }

    @Test public void latePistolIdentificationAppliesCutoffButRifleDoesNot() {
        scenario.onActivity(current -> {
            prepareTelemetry(false, 0);
            batteryPackets(false, 2704, 100);
            assertTrue(bluetooth.configs.isEmpty());
            identify((byte) 1);
            bluetooth.configs.clear();
            batteryPackets(false, 2704, 100);
            assertTrue(bluetooth.configs.isEmpty());
            identify((byte) 2);
            assertEquals(2, bluetooth.configs.size());
            assertEquals(2, bluetooth.configs.get(0)[3]);
            assertEquals(0x10, bluetooth.configs.get(0)[0]);
        });
    }

    @Test public void failedLowPowerWriteRetriesAreBoundedAndDoNotChangeFireProfile() {
        scenario.onActivity(current -> {
            prepareTelemetry(false, 0);
            ((GunBattery) get("mGunBattery")).setBlasterType(2);
            batteryPackets(false, 2704, 20);
            byte[] off = lastConfig();
            Intent finished = new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                    .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_CONFIG_UUID)
                    .putExtra(BluetoothLeService.EXTRA_DATA, off)
                    .putExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_SUCCESS);
            BroadcastReceiver receiver = (BroadcastReceiver) get("mGattUpdateReceiver");
            receiver.onReceive(current, finished);
            assertEquals(1, bluetooth.configs.size());
            finished.putExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_FAILURE);
            for (int i = 0; i < 10; i++) receiver.onReceive(current, finished);
            batteryPackets(false, 2704, 100);
            assertEquals(3, bluetooth.configs.size());
            for (byte[] config : bluetooth.configs) org.junit.Assert.assertArrayEquals(off, config);
        });
    }

    private void batteryPackets(boolean secondary, int raw, int count) {
        byte[] packet = new byte[20];
        packet[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL - 1] = (byte) raw;
        packet[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL] = (byte) (raw >> 8);
        for (int i = 0; i < count; i++)
            invoke("updateBatteryLevel", new Class<?>[]{byte[].class, boolean.class}, packet, secondary);
    }

    private void invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void prepareTelemetry(boolean secondary, int powerCount) {
        String prefix = secondary ? "mSecondary" : "m";
        set(prefix + "TelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
        set(prefix + "Communicating", true);
        set(prefix + "LastTriggerCount", (byte) 0);
        set(prefix + "LastReloadButtonCount", (byte) 0);
        set(prefix + "LastThumbButtonCount", (byte) 0);
        set(prefix + "LastPowerButtonCount", (byte) powerCount);
        set(prefix + "LastShotCount", (byte) 30);
    }

    private void telemetryCounters(boolean secondary, int powerAndRecoil) {
        byte[] packet = new byte[20];
        packet[FullscreenActivity.RECOIL_OFFSET_POWER_COUNTER] = (byte) powerAndRecoil;
        packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = Globals.getInstance().mPlayerID;
        packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 30;
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("processTelemetryData",
                    byte[].class, boolean.class);
            method.setAccessible(true);
            method.invoke(activity, packet, secondary);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
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
        assertEquals(9, lastShotConfig()[2]);
        assertEquals(mode == Globals.SHOT_MODE_BURST ? 3 : 0xfe, lastShotConfig()[3] & 0xff);
        assertEquals(mode == Globals.SHOT_MODE_BURST ? 3 : mode == Globals.SHOT_MODE_SINGLE ? 0 : 1,
                lastShotConfig()[4] & 0xff);
    }

    private byte[] lastConfig() { return bluetooth.configs.get(bluetooth.configs.size() - 1); }

    private byte[] lastShotConfig() {
        for (int index = bluetooth.configs.size() - 1; index >= 0; index--) {
            byte[] config = bluetooth.configs.get(index);
            if (config[2] == 9)
                return config;
        }
        throw new AssertionError("No shot-mode configuration was sent");
    }

    private static void allowModes(boolean single, boolean burst, boolean automatic) {
        Globals globals = Globals.getInstance();
        globals.mAllowSingleShotMode = single;
        globals.mAllowBurst3ShotMode = burst;
        globals.mAllowAutoShotMode = automatic;
    }

    private static void enableBossRole() {
        Globals globals = Globals.getInstance();
        globals.mBossMode = true;
        globals.mPlayerID = Globals.BOSS_PLAYER_ID;
        allowModes(true, true, true);
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
