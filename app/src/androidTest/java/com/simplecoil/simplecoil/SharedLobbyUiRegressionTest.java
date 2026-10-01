package com.simplecoil.simplecoil;

import android.graphics.Rect;
import android.graphics.Bitmap;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class SharedLobbyUiRegressionTest {
    @Test public void hostBannerConfirmsOptOutAndRemembersItAfterReopening() throws Exception {
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        String key = FullscreenActivity.PREF_PHONE_HOSTING_ENABLED;
        boolean hadSaved = preferences.contains(key);
        boolean saved = preferences.getBoolean(key, true);
        preferences.edit().putBoolean(key, true).commit();
        Globals globals = Globals.getInstance();
        globals.mGameState = Globals.GAME_STATE_NONE;
        java.net.InetAddress local = java.net.InetAddress.getByName("192.168.1.20");
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            Object[] originalServer = new Object[1];
            RecordingServer[] server = new RecordingServer[1];
            try {
                scenario.onActivity(activity -> {
                    ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                    invoke(activity, "leaveSharedLobby");
                    originalServer[0] = get(activity, "mTcpServer");
                    server[0] = new RecordingServer();
                    set(activity, "mTcpServer", server[0]);
                    set(activity, "mUseNetwork", true);
                    set(activity, "mIsServer", true);
                    set(activity, "mReady", true);
                    set(activity, "mLobbyJoined", true);
                    invoke(activity, "renderLobby");
                    View banner = activity.findViewById(R.id.lobby_host_banner);
                    assertTrue(banner.isClickable());
                    banner.performClick();
                    AlertDialog dialog = (AlertDialog) get(activity, "mStopHostingDialog");
                    assertNotNull(dialog);
                    assertTrue(dialog.isShowing());
                });
                onView(withText(R.string.lobby_keep_hosting)).perform(click());
                scenario.onActivity(activity -> {
                    assertTrue((Boolean) get(activity, "mIsServer"));
                    assertTrue(canHost(activity, local, android.os.SystemClock.elapsedRealtime()));
                    assertFalse(server[0].cancelled);

                    activity.findViewById(R.id.lobby_host_banner).performClick();
                    server[0].pendingStart = true;
                });
                onView(withText(R.string.lobby_stop_hosting)).perform(click());
                scenario.onActivity(activity -> {
                    assertTrue("Stale confirmation stopped a committed round",
                            (Boolean) get(activity, "mIsServer"));
                    assertFalse(server[0].cancelled);
                    assertTrue(preferences.getBoolean(key, false));
                    server[0].pendingStart = false;

                    activity.findViewById(R.id.lobby_host_banner).performClick();
                });
                onView(withText(R.string.lobby_stop_hosting)).perform(click());
                scenario.onActivity(activity -> {
                    assertTrue(server[0].cancelled);
                    assertFalse((Boolean) get(activity, "mIsServer"));
                    assertFalse((Boolean) get(activity, "mReady"));
                    assertTrue("Stopping hosting disabled shared Wi-Fi play",
                            (Boolean) get(activity, "mUseNetwork"));
                    assertFalse(canHost(activity, local, android.os.SystemClock.elapsedRealtime() + 60_000));
                    assertFalse(preferences.getBoolean(key, true));
                    assertEquals(View.GONE, activity.findViewById(R.id.lobby_host_banner).getVisibility());
                });
            } finally {
                scenario.onActivity(activity -> {
                    set(activity, "mTcpServer", originalServer[0]);
                    if (server[0] != null) server[0].onDestroy();
                });
            }
            scenario.recreate();
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                assertFalse("Reopening the app enabled hosting again",
                        (Boolean) get(activity, "mPhoneHostingEnabled"));
                assertFalse(canHost(activity, local, android.os.SystemClock.elapsedRealtime()));
                invoke(activity, "setPhoneHostingEnabled", new Class<?>[]{boolean.class}, true);
                ((SharedLobby) get(activity, "mSharedLobby")).clear();
                assertTrue(canHost(activity, local, android.os.SystemClock.elapsedRealtime()));
                assertTrue(preferences.getBoolean(key, false));
            });
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadSaved) editor.putBoolean(key, saved);
            else editor.remove(key);
            editor.commit();
            globals.mGameState = Globals.GAME_STATE_NONE;
        }
    }

    @Test public void matchOptionsSetAndShowGrenadeDamage() {
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        boolean hadSaved = preferences.contains(FullscreenActivity.PREF_GRENADE_DAMAGE);
        int saved = preferences.getInt(FullscreenActivity.PREF_GRENADE_DAMAGE,
                Globals.DAMAGE_PER_HIT);
        Globals globals = Globals.getInstance();
        int previous = globals.mGrenadeDamage;
        globals.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario =
                     ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                set(activity, "mReady", false);
                invoke(activity, "showLobbyMatchOptions");
            });
            onView(withText(R.string.grenade_damage_rule)).perform(click());
            onView(withText(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getString(R.string.grenade_damage_choice, 5))).perform(click());
            scenario.onActivity(activity -> {
                assertEquals(-5, globals.mGrenadeDamage);
                assertTrue(((TextView) activity.findViewById(R.id.lobby_rules))
                        .getText().toString().contains(activity.getString(
                                R.string.grenade_damage_summary, 5)));
            });
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadSaved) editor.putInt(FullscreenActivity.PREF_GRENADE_DAMAGE, saved);
            else editor.remove(FullscreenActivity.PREF_GRENADE_DAMAGE);
            editor.commit();
            globals.mGrenadeDamage = previous;
        }
    }

    @Test public void manualHostControlCannotOverrideALaptopOrRunningRound() throws Exception {
        Globals g = Globals.getInstance();
        g.mGameState = Globals.GAME_STATE_NONE;
        java.net.InetAddress local = java.net.InetAddress.getByName("192.168.1.20");
        java.net.InetAddress remote = java.net.InetAddress.getByName("192.168.1.250");
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mPhoneHostingEnabled", true);
                long now = android.os.SystemClock.elapsedRealtime();
                SharedLobby directory = (SharedLobby) get(activity, "mSharedLobby");
                directory.observe(new SharedLobby.Host(remote, SharedLobby.Kind.AUTO_PHONE, false, now, 50_000));
                assertFalse(canHost(activity, local, now));
                set(activity, "mManualPhoneHost", true);
                set(activity, "mManualHostSelectedAt", now);
                assertTrue(canHost(activity, local, now));
                set(activity, "mIsServer", true);
                invoke(activity, "renderLobby");
                assertEquals(activity.getString(R.string.lobby_you_manual_host),
                        ((TextView) activity.findViewById(R.id.lobby_host_banner)).getText().toString());
                assertEquals(activity.getString(R.string.lobby_host_auto),
                        ((TextView) activity.findViewById(R.id.lobby_host_button)).getText().toString());
                directory.observe(new SharedLobby.Host(remote, SharedLobby.Kind.LAPTOP, false, now, 0));
                assertFalse(canHost(activity, local, now));
                invoke(activity, "renderLobby");
                assertEquals(activity.getString(R.string.lobby_laptop_controls_host),
                        ((TextView) activity.findViewById(R.id.lobby_host_button)).getText().toString());
                assertFalse(activity.findViewById(R.id.lobby_host_button).isEnabled());
                assertTrue("Missing laptop blocked hosting forever", canHost(activity, local, now + SharedLobby.EXPIRY_MS));
                directory.observe(new SharedLobby.Host(remote, SharedLobby.Kind.AUTO_PHONE, true, now, 0));
                assertFalse("Manual selection must not stop another round", canHost(activity, local, now));
                set(activity, "mIsServer", false);
                invoke(activity, "clearManualPhoneHost");
            });
        }
    }

    private static boolean canHost(FullscreenActivity activity, java.net.InetAddress local, long now) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("canCreatePhoneLobby",
                    java.net.InetAddress.class, long.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(activity, local, now);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void lobbyBatteryWaitsForReadingAndClearsOnDisconnect() {
        Globals g = Globals.getInstance();
        boolean oldBoss = g.mBossMode;
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                isolateBatteryTest(activity);
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                g.mBossMode = false;
                set(activity, "mCommunicating", false);
                invoke(activity, "resetBatteryLevel", new Class<?>[]{boolean.class}, false);
                invoke(activity, "renderLobby");
                TextView label = activity.findViewById(R.id.lobby_gun_battery);
                assertEquals(activity.getString(R.string.gun_battery,
                        activity.getString(R.string.gun_battery_disconnected)), label.getText().toString());
                set(activity, "mCommunicating", true);
                GunBattery battery = (GunBattery) get(activity, "mGunBattery");
                battery.setBlasterType(2);
                invoke(activity, "updateBatteryDisplay");
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_reading)));
                batteryPacket(activity, false, 9);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_nimh_empty)));
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage, 3.4)));
                assertEquals(label.getText().toString(),
                        activity.findViewById(R.id.battery_iv).getContentDescription().toString());
                invoke(activity, "resetBluetoothServices");
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_disconnected)));
                set(activity, "mCommunicating", true);
                invoke(activity, "updateBatteryDisplay");
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_reading)));
            });
        } finally {
            g.mBossMode = oldBoss;
        }
    }

    @Test public void bossLobbyShowsSeparateGunBatteriesWithoutClipping() throws Exception {
        Globals g = Globals.getInstance();
        boolean oldBoss = g.mBossMode;
        byte oldPlayer = g.mPlayerID;
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                isolateBatteryTest(activity);
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mLastGameStatsLoaded", true);
                set(activity, "mLastGameStats", null);
                set(activity, "mCommunicating", true);
                set(activity, "mSecondaryCommunicating", true);
                set(activity, "mSecondaryDeviceAddress", "AA:BB:CC:DD:EE:FF");
                g.mBossMode = true;
                g.mPlayerID = Globals.BOSS_PLAYER_ID;
                GunBattery primary = (GunBattery) get(activity, "mGunBattery");
                GunBattery secondary = (GunBattery) get(activity, "mSecondGunBattery");
                primary.reset();
                secondary.reset();
                primary.setBlasterType(2);
                secondary.setBlasterType(2);
                batteryRawPackets(activity, false, 3714, 20);
                batteryRawPackets(activity, true, 2704, 20);
                invoke(activity, "renderLobby");
                TextView first = activity.findViewById(R.id.lobby_gun_battery);
                TextView second = activity.findViewById(R.id.lobby_second_gun_battery);
                assertEquals(activity.getString(R.string.gun_one_battery,
                        activity.getString(R.string.gun_battery_voltage_state,
                                activity.getString(R.string.gun_battery_voltage, 5.5),
                                activity.getString(R.string.gun_battery_nimh_full))), first.getText().toString());
                assertEquals(activity.getString(R.string.gun_two_battery,
                        activity.getString(R.string.gun_battery_low_power_state,
                                activity.getString(R.string.gun_battery_voltage_state,
                                        activity.getString(R.string.gun_battery_voltage, 4.0),
                                        activity.getString(R.string.gun_battery_nimh_empty)))), second.getText().toString());
                assertEquals(View.VISIBLE, second.getVisibility());
            });
            scenario.onActivity(activity -> {
                Rect visible = new Rect();
                for (int id : new int[]{R.id.lobby_gun_battery, R.id.lobby_second_gun_battery,
                        R.id.lobby_primary_button}) {
                    View view = activity.findViewById(id);
                    assertTrue(view.getGlobalVisibleRect(visible));
                    assertEquals(view.getHeight(), visible.height());
                }
            });
            capture("lobby-gun-batteries.png");
            scenario.onActivity(activity -> {
                invoke(activity, "resetSecondaryBluetoothServices");
                TextView first = activity.findViewById(R.id.lobby_gun_battery);
                TextView second = activity.findViewById(R.id.lobby_second_gun_battery);
                assertTrue(first.getText().toString().contains(activity.getString(R.string.gun_battery_nimh_full)));
                assertTrue(second.getText().toString().contains(activity.getString(R.string.gun_battery_disconnected)));
                g.mBossMode = false;
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, second.getVisibility());
            });
        } finally {
            g.mBossMode = oldBoss;
            g.mPlayerID = oldPlayer;
        }
    }

    private static void batteryPacket(FullscreenActivity activity, boolean secondary, int high) {
        batteryRawPackets(activity, secondary, high * 256, 20);
    }

    private static void isolateBatteryTest(FullscreenActivity activity) {
        // UI samples must not disable a connected physical gun's recoil, and
        // synthetic low-voltage latches must not leak into normal preferences.
        invoke(activity, "resetBluetoothServices");
        invoke(activity, "resetSecondaryBluetoothServices");
        set(activity, "mDeviceAddress", "");
        set(activity, "mSecondaryDeviceAddress", "");
        android.content.SharedPreferences isolated = activity.getSharedPreferences("GunBatteryUiTest", 0);
        isolated.edit().clear().commit();
        set(activity, "sharedPreferences", isolated);
        invoke(activity, "resetBatteryLevel", new Class<?>[]{boolean.class}, false);
        invoke(activity, "resetBatteryLevel", new Class<?>[]{boolean.class}, true);
    }

    private static void batteryRawPackets(FullscreenActivity activity, boolean secondary,
                                         int raw, int count) {
        byte[] data = new byte[20];
        data[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL - 1] = (byte) raw;
        data[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL] = (byte) (raw >> 8);
        for (int i = 0; i < count; i++)
            invoke(activity, "updateBatteryLevel", new Class<?>[]{byte[].class, boolean.class}, data, secondary);
    }

    @Test public void lobbyVoltageUpdatesWithinGoodBandAndDoesNotExtrapolate() {
        Globals g = Globals.getInstance();
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                isolateBatteryTest(activity);
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mCommunicating", true);
                long[] now = {0L};
                GunBattery battery = new GunBattery(() -> now[0]);
                set(activity, "mGunBattery", battery);
                battery.setBlasterType(2);
                batteryRawPackets(activity, false, 3368, 19);
                invoke(activity, "updateBatteryDisplay");
                TextView label = activity.findViewById(R.id.lobby_gun_battery);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_reading)));
                batteryRawPackets(activity, false, 3368, 1);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage, 5.0)));
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_good)));
                // No lobby pulse/render call: telemetry must update the voltage
                // even when the qualitative condition is still Good.
                now[0] = 1_000;
                batteryRawPackets(activity, false, 3236, 60);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage, 5.0)));
                now[0] = 15_000;
                // Expiring the old high readings changes the displayed value,
                // even though one more of the same low readings does not.
                batteryRawPackets(activity, false, 3236, 1);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage, 4.8)));
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_good)));
                now[0] = 30_000;
                batteryRawPackets(activity, false, 0, 100);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage_below)));
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_nimh_empty)));
                batteryRawPackets(activity, false, 0xffff, 100);
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_voltage_above)));
                battery.setBlasterType(1);
                invoke(activity, "updateBatteryDisplay");
                assertFalse(label.getText().toString().contains(" V"));
                assertTrue(label.getText().toString().contains(activity.getString(R.string.gun_battery_high)));
                invoke(activity, "resetBluetoothServices");
                assertFalse(label.getText().toString().contains(" V"));
            });
        }
    }

    @Test public void runningGameOffersTeamQrAndDisablesManualTeamSwitch() throws Exception {
        Globals g = Globals.getInstance();
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mCommunicating", true);
                set(activity, "mIsServer", false);
                g.mLocalLobbyBenched = true;
                g.mWaitingForLobbyRoundEnd = true;
                g.mLobbyRoundActive = true;
                g.mRequestedJoinTeam = 0;
                invoke(activity, "renderLobby");
                assertTrue(activity.findViewById(R.id.lobby_scan_button).isEnabled());
                assertFalse(activity.findViewById(R.id.lobby_team_button).isEnabled());
                TextView action = activity.findViewById(R.id.lobby_primary_button);
                assertTrue(action.isEnabled());
                assertEquals(activity.getString(R.string.lobby_join_by_qr), action.getText().toString());
            });
            capture("live-join-lobby.png");
        } finally {
            g.mLocalLobbyBenched = g.mWaitingForLobbyRoundEnd = g.mLobbyRoundActive = false;
        }
    }

    @Test public void previousGameCardFitsAndKeepsNextActionVisible() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mLastGameStatsLoaded", true);
                set(activity, "mLastGameStats", null);
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, activity.findViewById(R.id.lobby_last_game).getVisibility());
                set(activity, "mLastGameStats", new LastGameStats("Tournament (2 teams)", "Team 1",
                        7, 3, 25, 12, false));
                invoke(activity, "renderLobby");
            });
            scenario.onActivity(activity -> {
                View card = activity.findViewById(R.id.lobby_last_game);
                View primary = activity.findViewById(R.id.lobby_primary_button);
                Rect bounds = new Rect();
                assertTrue(card.getGlobalVisibleRect(bounds));
                assertEquals(card.getHeight(), bounds.height());
                assertTrue(primary.getGlobalVisibleRect(bounds));
                assertEquals(primary.getHeight(), bounds.height());
                for (int id : new int[]{R.id.lobby_last_game_kills, R.id.lobby_last_game_deaths,
                        R.id.lobby_last_game_hits}) {
                    TextView stat = activity.findViewById(id);
                    assertEquals("Stat label wrapped unexpectedly", 2, stat.getLineCount());
                }
            });
            capture("previous-game-lobby.png");
        }
    }

    @Test public void teamAndHostAreProminentInLobbyAndDuringPlay() throws Exception {
        Globals g = Globals.getInstance();
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mIsServer", true);
                g.mGameMode = Globals.GAME_MODE_2TEAMS;
                g.mTournamentMode = true;
                g.mBossMode = false;
                g.mInfectionMode = false;
                g.mCaptureTheFlag = false;
                g.mBalancedRandom = false;
                g.mPlayerID = 1;
                invoke(activity, "displayCurrentTeam");
                invoke(activity, "renderLobby");
                float scale = activity.getResources().getDisplayMetrics().scaledDensity;
                TextView team = activity.findViewById(R.id.lobby_team);
                assertEquals(32f, team.getTextSize() / scale, 0.1f);
                assertTrue(team.getTypeface().isBold());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.lobby_host_banner).getVisibility());
                TextView hudTeam = activity.findViewById(R.id.team_label_tv);
                assertEquals(32f, hudTeam.getTextSize() / scale, 0.1f);
            });
            capture("host-lobby.png");
            scenario.onActivity(activity -> {
                g.mGameState = Globals.GAME_STATE_RUNNING;
                // Apply game-start visibility without starting real gun/network timers.
                for (String name : new String[]{"mStartGameButton", "mGameLimitButton",
                        "mPlayerSettingsButton", "mTeamQrScanButton", "mPowerupSettingsButton"})
                    ((View) get(activity, name)).setVisibility(View.GONE);
                for (String name : new String[]{"mTeamMinusButton", "mTeamPlusButton",
                        "mUseNetworkingButton", "mFiringModeButton"})
                    ((View) get(activity, name)).setVisibility(View.INVISIBLE);
                invoke(activity, "displayInGameNetworkingOptions");
                invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, true);
                invoke(activity, "renderLobby");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.game_host_badge).getVisibility());
            });
            scenario.onActivity(activity -> {
                TextView team = activity.findViewById(R.id.team_label_tv);
                TextView player = activity.findViewById(R.id.team_tv);
                assertTrue("Large team label overlaps player label", team.getBottom() <= player.getTop());
                assertEquals(1, team.getLineCount());
            });
            capture("host-game.png");
            scenario.onActivity(activity -> {
                g.mPlayerID = 17;
                invoke(activity, "displayCurrentTeam");
                set(activity, "mIsServer", false);
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, activity.findViewById(R.id.game_host_badge).getVisibility());
                assertEquals(activity.getString(R.string.team_number_label, 2),
                        ((TextView) activity.findViewById(R.id.team_label_tv)).getText().toString());
            });
            capture("team-two-game.png");
            scenario.onActivity(activity -> {
                g.mGameState = Globals.GAME_STATE_NONE;
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, activity.findViewById(R.id.lobby_host_banner).getVisibility());
            });
        } finally { g.mGameState = Globals.GAME_STATE_NONE; }
    }

    private static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Android 5's compositor can still be showing the preceding frame after
        // the main queue is idle. Let the newly measured views reach the display.
        android.os.SystemClock.sleep(250);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "ui-regression");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }

    @Test public void briefHubDropPreservesHostAndLongDropNeverCancelsPeers() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                Object originalServer = get(activity, "mTcpServer");
                RecordingServer server = new RecordingServer();
                try {
                    set(activity, "mTcpServer", server);
                    set(activity, "mIsServer", true);
                    set(activity, "mReady", true);
                    set(activity, "mLobbyJoined", true);
                    set(activity, "mLobbyNetworkAddress", "192.168.1.2/1");
                    assertFalse(updateNetwork(activity, null, 1000));
                    assertTrue((Boolean) get(activity, "mReady"));
                    assertFalse(server.stopped);
                    assertTrue(updateNetwork(activity, "192.168.1.2/1", 2000));
                    assertNull(get(activity, "mLobbyNotice"));
                    assertTrue((Boolean) get(activity, "mIsServer"));
                    assertFalse(updateNetwork(activity, null, 3000));
                    assertFalse(updateNetwork(activity, null, 3000 + SharedLobby.EXPIRY_MS));
                    assertTrue(server.stopped);
                    assertFalse("A Wi-Fi drop sent a host cancellation", server.cancelled);
                    assertFalse((Boolean) get(activity, "mReady"));
                } finally {
                    set(activity, "mTcpServer", originalServer);
                    server.onDestroy();
                }
            });
        }
    }

    private static boolean updateNetwork(FullscreenActivity activity, String address, long now) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("updateLobbyNetwork", String.class, long.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(activity, address, now);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static class RecordingServer extends TcpServer {
        boolean cancelled;
        boolean stopped;
        boolean pendingStart;
        @Override boolean hasPendingOrAnnouncedStart() { return pendingStart; }
        @Override public void cancelServer() { cancelled = true; }
        @Override public void stopTcpServer() { stopped = true; }
    }

    @Test public void pairingInstructionsAndCancelAreOnTheVisibleLobby() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mCommunicating", false);
                set(activity, "mConnected", true);
                set(activity, "mWeaponSyncTriggerHintShown", true);
                invoke(activity, "renderLobby");
                assertEquals(activity.getString(R.string.connect_status_hold_trigger),
                        ((TextView) activity.findViewById(R.id.lobby_gun_status)).getText().toString());
                assertEquals(activity.getString(R.string.cancel),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                assertTrue(activity.findViewById(R.id.lobby_primary_button).isEnabled());
                activity.findViewById(R.id.lobby_primary_button).performClick();
                assertFalse((Boolean) get(activity, "mConnected"));
                assertEquals(activity.getString(R.string.lobby_gun_action),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
            });
        }
    }

    @Test public void thirtyTwoPlayersAreGroupedAndSittingOutCannotStart() {
        Globals g = Globals.getInstance();
        boolean oldBench = g.mLocalLobbyBenched;
        byte oldID = g.mPlayerID;
        Map<Byte, LobbyPlayer> oldPlayers = g.mLobbyPlayers;
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mReady", true);
                set(activity, "mLobbyJoined", true);
                set(activity, "mIsServer", false);
                set(activity, "mCommunicating", false);
                g.mGameMode = Globals.GAME_MODE_2TEAMS;
                g.mPlayerID = 1;
                g.mLocalLobbyBenched = true;
                Map<Byte, LobbyPlayer> players = new HashMap<>();
                for (byte id = 1; id <= 32; id++)
                    players.put(id, new LobbyPlayer(id, "Player", true, true, true, id == 1, id == 17));
                g.mLobbyPlayers = players;
                invoke(activity, "renderLobby");
                assertEquals(35, ((LinearLayout) activity.findViewById(R.id.lobby_roster)).getChildCount());
                assertFalse(activity.findViewById(R.id.lobby_primary_button).isEnabled());
                assertEquals(activity.getString(R.string.lobby_benched),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.lobby_roster_title)).getText()
                        .toString().contains("31"));
            });
        } finally {
            g.mLocalLobbyBenched = oldBench;
            g.mPlayerID = oldID;
            g.mLobbyPlayers = oldPlayers;
        }
    }

    @Test public void lobbyWorksBeforeGunPairingAndKeepsActionVisible() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue((Boolean) get(activity, "mUseNetwork"));
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mCommunicating", false);
                invoke(activity, "renderLobby");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.lobby_panel).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.connect_layout).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.play_layout).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.map_fragment).getVisibility());
                assertEquals(activity.getString(R.string.lobby_gun_action),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
            });
            scenario.onActivity(activity -> {
                View primary = activity.findViewById(R.id.lobby_primary_button);
                Rect visible = new Rect();
                assertTrue("Main action is off screen", primary.getGlobalVisibleRect(visible));
                assertEquals("Main action is clipped", primary.getHeight(), visible.height());
                View dock = activity.findViewById(R.id.lobby_action_bar);
                assertEquals(dock.getHeight(), activity.findViewById(R.id.scrollView).getPaddingBottom());
                Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, dock.getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.play_layout).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.map_fragment).getVisibility());
                assertEquals(0, activity.findViewById(R.id.scrollView).getPaddingBottom());
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                invoke(activity, "renderLobby");
                assertEquals(View.VISIBLE, dock.getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.map_fragment).getVisibility());
            });
        } finally { Globals.getInstance().mGameState = Globals.GAME_STATE_NONE; }
    }

    @Test public void setupPresentsGunBeforeWifi() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mCommunicating", false);
                invoke(activity, "renderLobby");
                LinearLayout panel = activity.findViewById(R.id.lobby_panel);
                assertTrue(panel.indexOfChild(activity.findViewById(R.id.lobby_gun_card))
                        < panel.indexOfChild(activity.findViewById(R.id.lobby_wifi_card)));
                assertEquals(activity.getString(R.string.lobby_gun_action),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                if (!(Boolean) invokeResult(activity, "isGameNetworkAvailable")) {
                    set(activity, "mCommunicating", true);
                    invoke(activity, "renderLobby");
                    assertEquals(activity.getString(R.string.lobby_wifi_needed),
                            ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                }
            });
        }
    }

    @Test public void appLaunchPromptsWhenLegacyLocationIsNotHighAccuracy() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P);
        int mode = android.provider.Settings.Secure.getInt(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver(),
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF);
        org.junit.Assume.assumeTrue(mode != android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse((Boolean) invokeResult(activity, "isLocationReadyForTracking"));
                assertTrue((Boolean) get(activity, "mLocationSettingsPromptShown"));
                androidx.appcompat.app.AlertDialog dialog =
                        (androidx.appcompat.app.AlertDialog) get(activity, "mLocationSettingsDialog");
                assertNotNull(dialog);
                assertTrue(dialog.isShowing());
            });
        }
    }

    private static Object get(Object target, String name) {
        try { Field f = FullscreenActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = FullscreenActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }
    private static Object invokeResult(Object target, String name) {
        try { Method m = FullscreenActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = FullscreenActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
