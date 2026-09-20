package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises actual server-data parsing, including interrupted map-lock acquisition. */
@RunWith(AndroidJUnit4.class)
public class TcpGameInfoRegressionTest {
    private RecordingClient client;
    private Globals globals;
    private byte originalPlayerID;
    private int originalGameMode;
    private int originalGameLimit;
    private int originalTimeLimit;
    private int originalLivesLimit;
    private int originalScoreLimit;
    private boolean originalUseGPS;
    private int originalGPSMode;
    private boolean originalOnlyServerSettings;
    private int[] originalPairings;
    private InetAddress originalPeer;
    private Map<Byte, Globals.PlayerSettings> originalSettings;
    private Map<String, Object> originalLocalSettings;
    private Map<String, Object> baselineLocalSettings;
    private Globals.PlayerSettings baselineSettings;
    private static final String[] LOCAL_SETTINGS = {"mFullHealth", "mFullReload", "mReloadTime",
            "mReloadOnEmpty", "mRespawnTime", "mDamage", "mOverrideLives", "mOverrideLivesVal",
            "mAllowSingleShotMode", "mAllowBurst3ShotMode", "mAllowAutoShotMode",
            "mCurrentFiringMode", "mAllowPlayerSettings"};

    @Before
    public void setUp() throws Exception {
        client = new RecordingClient();
        globals = Globals.getInstance();
        originalPlayerID = globals.mPlayerID;
        originalGameMode = globals.mGameMode;
        originalGameLimit = globals.mGameLimit;
        originalTimeLimit = globals.mTimeLimit;
        originalLivesLimit = globals.mLivesLimit;
        originalScoreLimit = globals.mScoreLimit;
        originalUseGPS = globals.mUseGPS;
        originalGPSMode = globals.mGPSMode;
        originalOnlyServerSettings = globals.mOnlyServerSettings;
        originalPairings = globals.mGrenadePairings.clone();
        originalLocalSettings = localSettings();
        Globals.getmPlayerSettingsSemaphore();
        try {
            originalSettings = new HashMap<>(globals.mPlayerSettings);
            globals.mPlayerSettings.clear();
            baselineSettings = new Globals.PlayerSettings();
            globals.mPlayerSettings.put((byte) 1, baselineSettings);
        } finally {
            globals.mPlayerSettingsSemaphore.release();
        }
        globals.mFullHealth = 20;
        globals.mFullReload = 30;
        globals.mAllowPlayerSettings = true;
        baselineLocalSettings = localSettings();
        globals.mPlayerID = 1;
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mGameLimit = Globals.GAME_LIMIT_TIME;
        globals.mTimeLimit = 5;
        originalPeer = InetAddress.getByName("127.0.0.2");
        clearMaps();
        globals.mTeamPlayerNameMap.put((byte) 2, "Original peer");
        globals.mTeamIPMap.put((byte) 2, originalPeer);
        globals.mIPTeamMap.put(originalPeer, (byte) 2);
        Globals.ClearGrenadePairings(true);
        globals.mGrenadePairings[1] = 2;
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::onDestroy);
        clearMaps();
        globals.mPlayerID = originalPlayerID;
        globals.mGameMode = originalGameMode;
        globals.mGameLimit = originalGameLimit;
        globals.mTimeLimit = originalTimeLimit;
        globals.mLivesLimit = originalLivesLimit;
        globals.mScoreLimit = originalScoreLimit;
        globals.mUseGPS = originalUseGPS;
        globals.mGPSMode = originalGPSMode;
        globals.mOnlyServerSettings = originalOnlyServerSettings;
        System.arraycopy(originalPairings, 0, globals.mGrenadePairings, 0, originalPairings.length);
        Globals.getmPlayerSettingsSemaphore();
        try {
            globals.mPlayerSettings.clear();
            globals.mPlayerSettings.putAll(originalSettings);
        } finally {
            globals.mPlayerSettingsSemaphore.release();
        }
        for (Map.Entry<String, Object> entry : originalLocalSettings.entrySet())
            Globals.class.getField(entry.getKey()).set(globals, entry.getValue());
    }

    @Test
    public void deeplyNestedArraysCannotCrashClientParsing() throws Exception {
        assertDeepMessageIgnored(true);
    }

    @Test
    public void deeplyNestedObjectsCannotCrashClientParsing() throws Exception {
        assertDeepMessageIgnored(false);
    }

    private void assertDeepMessageIgnored(boolean arrays) throws Exception {
        String message = TcpInputTestData.nested(10000, arrays);
        TcpInputTestData.assertFitsFrame(message);
        parse(message);
        assertOriginalRoster();
        assertTrue(client.events.isEmpty());
        parse(roster(new JSONArray().put(player(3))));
        assertEquals("Player 3", globals.getPlayerName((byte) 3));
        assertEquals(1, client.events.size());
    }

    @Test
    public void oversizedRosterNameDoesNotPartiallyApplyTheRosterOrSettings() throws Exception {
        assertInvalidRosterName(TcpInputTestData.repeat('a', 21));
    }

    @Test
    public void nonStringRosterNamesDoNotPartiallyApplyTheRosterOrSettings() throws Exception {
        for (Object name : new Object[]{JSONObject.NULL, 123, true, new JSONObject(), new JSONArray()})
            assertInvalidRosterName(name);
    }

    private void assertInvalidRosterName(Object name) throws Exception {
        parse(roster(new JSONArray().put(player(3)).put(player(4).put(TcpServer.JSON_PLAYERNAME, name)))
                .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false));
        assertOriginalRoster();
        assertOriginalSettings();
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void normalAndEmptyRosterNamesArePreserved() throws Exception {
        for (String name : new String[]{"", TcpInputTestData.repeat('a', 20), "[]{}'\"\\/"}) {
            parse(roster(new JSONArray().put(player(3).put(TcpServer.JSON_PLAYERNAME, name))));
            assertEquals(name, globals.getPlayerName((byte) 3));
        }
        assertEquals(3, client.events.size());
    }

    @Test
    public void lossyRosterIdsCannotReplacePlayersOrBundledSettings() throws Exception {
        for (Object id : new Object[]{4294967299L, -4294967293L, 3.75, "3"}) {
            parse(roster(new JSONArray().put(player(3).put(TcpServer.JSON_PLAYERID, id)))
                    .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                    .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false));
            assertOriginalRoster();
            assertOriginalSettings();
        }
    }

    @Test
    public void lossySettingsFieldsRejectTheCompleteSnapshot() throws Exception {
        for (String key : new String[]{TcpServer.JSON_PLAYERID, TcpServer.JSON_HEALTH,
                TcpServer.JSON_RELOAD_SHOTS, TcpServer.JSON_DAMAGE, TcpServer.JSON_LIVESLIMIT,
                TcpServer.JSON_FIRING_MODE}) {
            int valid = settings(2).getInt(key);
            for (Object invalid : new Object[]{4294967296L + valid, valid + 0.25, "" + valid}) {
                parse(settingsMessage(settings(1), settings(2).put(key, invalid)));
                assertOriginalSettings();
            }
        }
    }

    @Test
    public void fractionalReloadAndSpawnTimesDoNotChangePlayerSettings() throws Exception {
        for (String key : new String[]{TcpServer.JSON_RELOAD_TIME, TcpServer.JSON_SPAWN_TIME}) {
            long valid = settings(1).getLong(key);
            for (Object invalid : new Object[]{valid + 0.25, "" + valid}) {
                parse(settingsMessage(settings(1).put(key, invalid)));
                assertOriginalSettings();
            }
        }
    }

    @Test
    public void lossyGameLimitsDoNotReplaceTheRosterOrLimits() throws Exception {
        for (String key : new String[]{TcpServer.JSON_TIMELIMIT, TcpServer.JSON_LIVESLIMIT,
                TcpServer.JSON_SCORELIMIT}) {
            for (Object invalid : new Object[]{4294967303L, 7.75, "7"}) {
                parse(roster(new JSONArray().put(player(3)))
                        .put(TcpServer.JSON_LIMITS, new JSONObject().put(key, invalid)));
                assertOriginalRoster();
                assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
                assertEquals(5, globals.mTimeLimit);
                assertTrue(client.events.isEmpty());
            }
        }
    }

    @Test
    public void lossyGameModesAndStatesCannotPublishARosterUpdate() throws Exception {
        for (String key : new String[]{TcpServer.JSON_GAMEMODE, TcpServer.JSON_USEGPS,
                TcpServer.JSON_GAMESTATE}) {
            for (Object invalid : new Object[]{4294967297L, 1.75, "1"}) {
                parse(roster(new JSONArray().put(player(3))).put(TcpServer.JSON_DEDICATED, true)
                        .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_RUNNING).put(key, invalid));
                assertOriginalRoster();
                assertTrue(client.events.isEmpty());
            }
        }
    }

    @Test
    public void lossyScoresAndDeathsCannotPublishAGameUpdate() throws Exception {
        for (String key : new String[]{TcpServer.JSON_PLAYERPOINTS, TcpServer.JSON_PLAYERELIMINATED,
                TcpServer.JSON_TEAMPOINTS}) {
            for (Object invalid : new Object[]{4294967303L, 7.75, "7"}) {
                JSONObject update = new JSONObject().put(TcpServer.JSON_PLAYERPOINTS, 7)
                        .put(TcpServer.JSON_PLAYERELIMINATED, 2).put(TcpServer.JSON_TEAMPOINTS, 11)
                        .put(key, invalid);
                parse(roster(new JSONArray().put(player(3))).put(TcpServer.JSON_PLAYERGAMEUPDATE, update));
                assertOriginalRoster();
                assertTrue(client.events.isEmpty());
            }
        }
    }

    @Test
    public void fractionalRemainingTimeCannotBecomeAnEndOfRoundNotification() throws Exception {
        for (Object remaining : new Object[]{-0.5, 45.75, "45"}) {
            JSONObject update = new JSONObject().put(TcpServer.JSON_PLAYERPOINTS, 7)
                    .put(TcpServer.JSON_PLAYERELIMINATED, 2).put(TcpServer.JSON_TIMEREMAINING, remaining);
            parse(roster(new JSONArray().put(player(3))).put(TcpServer.JSON_DEDICATED, true)
                    .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_RUNNING)
                    .put(TcpServer.JSON_PLAYERGAMEUPDATE, update));
            assertOriginalRoster();
            assertTrue(client.events.isEmpty());
        }
    }

    @Test
    public void lossyPairingFieldsCannotOverwriteTheGrenadeSnapshot() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        for (String key : new String[]{TcpServer.JSON_PAIRED_GRENADE_ID, TcpServer.JSON_PLAYERID}) {
            for (Object invalid : new Object[]{4294967299L, 3.75, "3"}) {
                parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS,
                        new JSONArray().put(pairing(2, 3)).put(pairing(3, 4).put(key, invalid))));
                assertArrayEquals(before, globals.mGrenadePairings);
                assertTrue(client.events.isEmpty());
            }
        }
    }

    @Test
    public void lossyGpsIdsAndTeamsCannotEraseTheLocationSnapshot() throws Exception {
        Globals.GPSData original = putGPS(2, 1);
        for (String key : new String[]{TcpServer.JSON_PLAYERID, TcpServer.JSON_TEAM}) {
            for (Object invalid : new Object[]{4294967299L, 3.75, "3"}) {
                parse(gpsUpdate(true, gps(3, 2).put(key, invalid)));
                assertEquals(1, globals.mGPSData.size());
                assertSame(original, globals.mGPSData.get((byte) 2));
                assertTrue(client.events.isEmpty());
            }
        }
    }

    @Test
    public void malformedLaterSettingsRowDoesNotChangeEarlierPlayers() throws Exception {
        parse(settingsMessage(settings(1), new JSONObject().put(TcpServer.JSON_PLAYERID, 2)));
        assertOriginalSettings();
    }

    @Test
    public void missingSettingsPermissionDoesNotPartiallyApplySettings() throws Exception {
        JSONObject message = settingsMessage(settings(1));
        message.remove(TcpServer.JSON_ALLOWPLAYERSETTINGS);
        parse(message);
        assertOriginalSettings();
    }

    @Test
    public void invalidLaterSettingsRowRejectsTheWholeUpdate() throws Exception {
        parse(settingsMessage(settings(1), settings(2).put(TcpServer.JSON_HEALTH, 0)));
        assertOriginalSettings();
    }

    @Test
    public void duplicateSettingsPlayerIdsDoNotApplyConflictingValues() throws Exception {
        parse(settingsMessage(settings(1), settings(1).put(TcpServer.JSON_HEALTH, 80)));
        assertOriginalSettings();
    }

    @Test
    public void settingsForUnassignedPlayerIdAreRejected() throws Exception {
        parse(settingsMessage(settings(0)));
        assertOriginalSettings();
    }

    @Test
    public void malformedRosterMetadataDoesNotApplyBundledSettings() throws Exception {
        JSONObject message = roster(new JSONArray().put(player(3)));
        message.put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false);
        message.remove(TcpServer.JSON_LIMITS);
        parse(message);
        assertOriginalRoster();
        assertOriginalSettings();
    }

    @Test
    public void interruptedBundledSettingsLockDoesNotPartiallyApplyTheRoster() throws Exception {
        JSONObject message = roster(new JSONArray().put(player(3)))
                .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false);
        interruptRosterWhileWaitingOn(globals.mPlayerSettingsSemaphore, message.toString());
        assertOriginalSettings();
    }

    @Test
    public void validSettingsApplyUnsignedAmmoAndAllLocalFields() throws Exception {
        parse(settingsMessage(settings(1), settings(2)));
        assertEquals(50, globals.mFullHealth);
        assertEquals(200, globals.mFullReload & 0xff);
        assertEquals(2500, globals.mReloadTime);
        assertFalse(globals.mReloadOnEmpty);
        assertEquals(20, globals.mRespawnTime);
        assertEquals(-10, globals.mDamage);
        assertTrue(globals.mOverrideLives);
        assertEquals(3, globals.mOverrideLivesVal);
        assertFalse(globals.mAllowSingleShotMode);
        assertFalse(globals.mAllowBurst3ShotMode);
        assertTrue(globals.mAllowAutoShotMode);
        assertEquals(Globals.FIRING_MODE_INDOOR_NO_CONE, globals.mCurrentFiringMode);
        assertFalse(globals.mAllowPlayerSettings);
        assertEquals(2, globals.mPlayerSettings.size());
        assertEquals(50, globals.mPlayerSettings.get((byte) 2).health);
        assertEquals(NetMsg.NETMSG_PLAYERSETTINGSUPDATE, client.events.get(0).getAction());
        assertEquals(1, globals.mPlayerSettingsSemaphore.availablePermits());
    }

    @Test
    public void validBundledSettingsAndRosterStillPublishBothUpdates() throws Exception {
        parse(roster(new JSONArray().put(player(3)))
                .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false));
        assertEquals(50, globals.mFullHealth);
        assertEquals("Player 3", globals.mTeamPlayerNameMap.get((byte) 3));
        assertEquals(2, client.events.size());
        assertEquals(NetMsg.NETMSG_PLAYERSETTINGSUPDATE, client.events.get(0).getAction());
        assertEquals(NetMsg.NETMSG_LISTPLAYERS, client.events.get(1).getAction());
    }

    @Test
    public void stoppedClientCannotApplyAnInFlightGpsUpdate() throws Exception {
        assertCancelledGpsUpdate(false);
    }

    @Test
    public void destroyedClientCannotApplyAnInFlightGpsUpdate() throws Exception {
        assertCancelledGpsUpdate(true);
    }

    private void assertCancelledGpsUpdate(boolean destroy) throws Exception {
        Globals.GPSData original = putGPS(2, 1);
        cancelBlockedParse(globals.mGPSDataSemaphore, gpsUpdate(true, gps(3, 2)), destroy);
        assertEquals(1, globals.mGPSData.size());
        assertSame(original, globals.mGPSData.get((byte) 2));
        assertFalse(original.hasUpdate);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void stoppedClientCannotApplyAnInFlightGrenadeUpdate() throws Exception {
        assertCancelledGrenadeUpdate(false);
    }

    @Test
    public void destroyedClientCannotApplyAnInFlightGrenadeUpdate() throws Exception {
        assertCancelledGrenadeUpdate(true);
    }

    private void assertCancelledGrenadeUpdate(boolean destroy) throws Exception {
        int[] expected = globals.mGrenadePairings.clone();
        cancelBlockedParse(globals.mGrenadePairingsSemaphore,
                new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS, new JSONArray().put(pairing(3, 4))), destroy);
        assertArrayEquals(expected, globals.mGrenadePairings);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void stoppedClientCannotApplyInFlightPlayerSettings() throws Exception {
        cancelBlockedParse(globals.mPlayerSettingsSemaphore, settingsMessage(settings(1)), false);
        assertOriginalSettings();
    }

    @Test
    public void destroyedClientCannotApplyInFlightPlayerSettings() throws Exception {
        cancelBlockedParse(globals.mPlayerSettingsSemaphore, settingsMessage(settings(1)), true);
        assertOriginalSettings();
    }

    @Test
    public void stoppedClientCannotApplyAnInFlightRoster() throws Exception {
        assertCancelledRoster(false);
    }

    @Test
    public void destroyedClientCannotApplyAnInFlightRoster() throws Exception {
        assertCancelledRoster(true);
    }

    private void assertCancelledRoster(boolean destroy) throws Exception {
        cancelBlockedParse(globals.mIPTeamMapSemaphore, roster(new JSONArray().put(player(3))), destroy);
        assertOriginalRoster();
        assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void stoppingBundledRosterWhileWaitingForSettingsDoesNotHoldServiceMonitor() throws Exception {
        cancelBlockedParse(globals.mPlayerSettingsSemaphore,
                roster(new JSONArray().put(player(3)))
                        .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                        .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false), false);
        assertOriginalRoster();
        assertOriginalSettings();
    }

    @Test
    public void destroyedClientIgnoresMessagesBeforeAcquiringSharedLocks() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::onDestroy);
        parse(settingsMessage(settings(1)));
        parse(roster(new JSONArray().put(player(3))));
        parse(new JSONObject().put(TcpServer.JSON_PLAYERDATA, new JSONArray()));
        assertOriginalRoster();
        assertOriginalSettings();
    }

    private void cancelBlockedParse(Semaphore held, JSONObject message, boolean destroy) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { parse(message); } catch (Throwable error) { failure.set(error); }
        });
        Thread cancellation = new Thread(() -> {
            try {
                if (destroy) client.onDestroy();
                else client.stopTcpClient();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        held.acquire();
        try {
            worker.start();
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (!held.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertTrue("Parser did not reach the held state lock", held.hasQueuedThreads());
            cancellation.start();
            cancellation.join(1000);
            assertFalse("Stopping the service blocked on the parser's state lock", cancellation.isAlive());
            assertEquals(0, held.availablePermits());
        } finally {
            held.release();
            cancellation.join(2000);
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(1, held.availablePermits());
    }

    @Test
    public void interruptedSecondRosterLockReleasesTheFirstLock() throws Exception {
        interruptRosterWhileWaitingOn(globals.mTeamIPMapSemaphore);
    }

    @Test
    public void interruptedThirdRosterLockReleasesBothEarlierLocks() throws Exception {
        interruptRosterWhileWaitingOn(globals.mIPTeamMapSemaphore);
    }

    private void interruptRosterWhileWaitingOn(Semaphore held) throws Exception {
        interruptRosterWhileWaitingOn(held, roster(new JSONArray()).toString());
    }

    private void interruptRosterWhileWaitingOn(Semaphore held, String message) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { parse(message); } catch (Throwable error) { failure.set(error); }
        });
        held.acquire();
        try {
            worker.start();
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (!held.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertTrue("Parser did not reach held roster lock", held.hasQueuedThreads());
            worker.interrupt();
            worker.join(1500);
            assertFalse("Cancelled parser is still blocked", worker.isAlive());
            assertNull(failure.get());
            assertEquals(held == globals.mTeamPlayerNameSemaphore ? 0 : 1, globals.mTeamPlayerNameSemaphore.availablePermits());
            assertEquals(held == globals.mTeamIPMapSemaphore ? 0 : 1, globals.mTeamIPMapSemaphore.availablePermits());
            assertEquals(held == globals.mIPTeamMapSemaphore ? 0 : 1, globals.mIPTeamMapSemaphore.availablePermits());
            assertEquals(held == globals.mPlayerSettingsSemaphore ? 0 : 1, globals.mPlayerSettingsSemaphore.availablePermits());
            assertOriginalRoster();
        } finally {
            worker.interrupt();
            held.release();
            worker.join(2000);
            // Recover only the locks this isolated parser leaked on the old code,
            // so a regression failure cannot hang the remainder of the suite.
            for (Semaphore lock : Arrays.asList(globals.mTeamPlayerNameSemaphore,
                    globals.mTeamIPMapSemaphore, globals.mIPTeamMapSemaphore, globals.mPlayerSettingsSemaphore)) {
                if (lock.availablePermits() == 0 && !worker.isAlive()) lock.release();
            }
        }
    }

    @Test
    public void malformedRosterDoesNotEraseCurrentPlayersOrLimits() throws Exception {
        JSONObject broken = new JSONObject().put(TcpServer.JSON_PLAYERID, 4);
        parse(roster(new JSONArray().put(player(3)).put(broken)));
        assertOriginalRoster();
        assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void malformedGameMetadataDoesNotReplaceTheRoster() throws Exception {
        JSONObject game = roster(new JSONArray().put(player(3)));
        game.remove(TcpServer.JSON_LIMITS);
        parse(game);
        assertOriginalRoster();
        assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void validRosterStillReplacesPlayersAndAppliesLimits() throws Exception {
        JSONObject game = roster(new JSONArray().put(player(3)));
        game.put(TcpServer.JSON_LIMITS, new JSONObject().put(TcpServer.JSON_SCORELIMIT, 7));
        parse(game);
        assertEquals(1, globals.mTeamIPMap.size());
        assertEquals(InetAddress.getByName("127.0.0.3"), globals.mTeamIPMap.get((byte) 3));
        assertEquals("Player 3", globals.mTeamPlayerNameMap.get((byte) 3));
        assertEquals(Globals.GAME_LIMIT_SCORE, globals.mGameLimit);
        assertEquals(7, globals.mScoreLimit);
        assertEquals(NetMsg.NETMSG_LISTPLAYERS, client.events.get(0).getAction());
    }

    @Test
    public void conflictingPlayerIdsDoNotCreateInconsistentEndpointMaps() throws Exception {
        JSONObject duplicate = player(3).put(TcpServer.JSON_PLAYERIP, "/127.0.0.4");
        parse(roster(new JSONArray().put(player(3)).put(duplicate)));
        assertOriginalRoster();
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void conflictingAddressesDoNotCreateInconsistentEndpointMaps() throws Exception {
        JSONObject duplicate = player(4).put(TcpServer.JSON_PLAYERIP, "/127.0.0.3");
        parse(roster(new JSONArray().put(player(3)).put(duplicate)));
        assertOriginalRoster();
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void duplicateLocalPlayerInRosterDoesNotReplaceLivePlayers() throws Exception {
        parse(roster(new JSONArray().put(player(1)).put(player(1)).put(player(3))));
        assertOriginalRoster();
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void oversizedRosterDoesNotReplaceLivePlayersOrSettings() throws Exception {
        JSONArray players = new JSONArray();
        for (int id = 1; id <= Globals.MAX_PLAYER_ID; id++)
            players.put(player(id));
        // The client itself is not stored in the peer endpoint maps, so a
        // duplicate self entry previously slipped past the endpoint checks.
        players.put(player(1));
        parse(roster(players).put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(settings(1)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false));
        assertOriginalRoster();
        assertOriginalSettings();
    }

    @Test
    public void oversizedPlayerDataDoesNotPublishAScoreboard() throws Exception {
        JSONArray players = new JSONArray();
        for (int id = 1; id <= Globals.MAX_PLAYER_ID; id++)
            players.put(scoreboardPlayer(id));
        players.put(scoreboardPlayer(1));
        parse(new JSONObject().put(TcpServer.JSON_PLAYERDATA, players));
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void conflictingPlayerDataDoesNotPublishAScoreboard() throws Exception {
        parse(new JSONObject().put(TcpServer.JSON_PLAYERDATA,
                new JSONArray().put(scoreboardPlayer(2)).put(scoreboardPlayer(2))));
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void invalidPlayerDataScoresDoNotPublishAScoreboard() throws Exception {
        for (int invalid : new int[]{-1, TcpClient.MAX_SCOREBOARD_VALUE + 1}) {
            parse(new JSONObject().put(TcpServer.JSON_PLAYERDATA,
                    new JSONArray().put(scoreboardPlayer(2).put(TcpServer.JSON_PLAYERPOINTS, invalid))));
            assertTrue(client.events.isEmpty());
        }
    }

    @Test
    public void validPlayerDataPublishesAScoreboard() throws Exception {
        parse(new JSONObject().put(TcpServer.JSON_PLAYERDATA,
                new JSONArray().put(scoreboardPlayer(2))));
        assertEquals(1, client.events.size());
        assertEquals(NetMsg.NETMSG_PLAYERDATAUPDATE, client.events.get(0).getAction());
    }

    @Test
    public void emptyRemoteAddressCannotBeResolvedAsLocalhost() throws Exception {
        parse(roster(new JSONArray().put(player(3).put(TcpServer.JSON_PLAYERIP, "/"))));
        assertOriginalRoster();
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void falseDedicatedFlagDoesNotRequireDedicatedGameState() throws Exception {
        parse(roster(new JSONArray().put(player(3))).put(TcpServer.JSON_DEDICATED, false));
        assertFalse(client.isDedicatedServer());
        assertEquals(NetMsg.NETMSG_LISTPLAYERS, client.events.get(0).getAction());
    }

    @Test
    public void missingDedicatedGameStateDoesNotPartiallyApplyOtherMetadata() throws Exception {
        JSONObject game = roster(new JSONArray().put(player(3))).put(TcpServer.JSON_DEDICATED, true)
                .put(TcpServer.JSON_GAMEMODE, Globals.GAME_MODE_FFA);
        parse(game);
        assertOriginalRoster();
        assertEquals(Globals.GAME_LIMIT_TIME, globals.mGameLimit);
        assertEquals(Globals.GAME_MODE_2TEAMS, globals.mGameMode);
        assertFalse(client.isDedicatedServer());
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void dedicatedReconnectStillIncludesScoreLivesAndRemainingTime() throws Exception {
        JSONObject update = new JSONObject().put(TcpServer.JSON_PLAYERPOINTS, 7)
                .put(TcpServer.JSON_PLAYERELIMINATED, 2).put(TcpServer.JSON_TEAMPOINTS, 11)
                .put(TcpServer.JSON_TIMEREMAINING, 45);
        JSONObject game = roster(new JSONArray().put(player(1)).put(player(3)))
                .put(TcpServer.JSON_DEDICATED, true).put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_RUNNING)
                .put(TcpServer.JSON_PLAYERGAMEUPDATE, update);
        parse(game);
        assertTrue(client.isDedicatedServer());
        assertEquals(1, globals.mTeamIPMap.size());
        Intent event = client.events.get(0);
        assertTrue(event.getBooleanExtra(NetMsg.INTENT_HASGAMEUPDATE, false));
        assertEquals(Globals.GAME_STATE_RUNNING, event.getIntExtra(NetMsg.INTENT_GAMESTATE, -1));
        assertEquals(7, event.getIntExtra(NetMsg.INTENT_SCORE, -1));
        assertEquals(2, event.getIntExtra(NetMsg.INTENT_ELIMINATIONS, -1));
        assertEquals(11, event.getIntExtra(NetMsg.INTENT_TEAMSCORE, -1));
        assertEquals(45L, event.getLongExtra(NetMsg.INTENT_TIMEREMAINING, -1));
    }

    @Test
    public void invalidDedicatedGameScoresDoNotPublishOrReplaceTheRoster() throws Exception {
        Object[][] invalidValues = {
                {TcpServer.JSON_PLAYERPOINTS, -1},
                {TcpServer.JSON_PLAYERPOINTS, TcpClient.MAX_SCOREBOARD_VALUE + 1},
                {TcpServer.JSON_PLAYERELIMINATED, -1},
                {TcpServer.JSON_PLAYERELIMINATED, TcpClient.MAX_SCOREBOARD_VALUE + 1},
                {TcpServer.JSON_TEAMPOINTS, -1},
                {TcpServer.JSON_TEAMPOINTS, Globals.MAX_TEAM_SCOREBOARD_VALUE + 1}
        };
        for (Object[] invalid : invalidValues) {
            JSONObject update = new JSONObject().put(TcpServer.JSON_PLAYERPOINTS, 7)
                    .put(TcpServer.JSON_PLAYERELIMINATED, 2).put(TcpServer.JSON_TEAMPOINTS, 11);
            update.put((String) invalid[0], invalid[1]);
            JSONObject game = roster(new JSONArray().put(player(1)).put(player(3)))
                    .put(TcpServer.JSON_DEDICATED, true)
                    .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_RUNNING)
                    .put(TcpServer.JSON_PLAYERGAMEUPDATE, update);
            parse(game);
            assertOriginalRoster();
            assertFalse(client.isDedicatedServer());
            assertTrue(client.events.isEmpty());
        }
    }

    @Test
    public void malformedGrenadeSnapshotPreservesExistingPairings() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        JSONArray pairs = new JSONArray().put(pairing(2, 3))
                .put(new JSONObject().put(TcpServer.JSON_PAIRED_GRENADE_ID, 3));
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS, pairs));
        assertArrayEquals(before, globals.mGrenadePairings);
    }

    @Test
    public void invalidGrenadeSnapshotDoesNotEraseGoodPairings() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS,
                new JSONArray().put(pairing(Globals.MAX_GRENADE_IDS, 3))));
        assertArrayEquals(before, globals.mGrenadePairings);
    }

    @Test
    public void validGrenadeSnapshotReplacesOldPairingsAndAllowsAnEmptySnapshot() throws Exception {
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS, new JSONArray().put(pairing(2, 3))));
        assertEquals(Globals.INVALID_PLAYER_ID, globals.mGrenadePairings[1]);
        assertEquals(3, globals.mGrenadePairings[2]);
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS, new JSONArray()));
        for (int pairing : globals.mGrenadePairings) assertEquals(Globals.INVALID_PLAYER_ID, pairing);
    }

    @Test
    public void duplicateGrenadeIdsDoNotPartiallyReplaceTheSnapshot() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS,
                new JSONArray().put(pairing(2, 3)).put(pairing(2, 4))));
        assertArrayEquals(before, globals.mGrenadePairings);
    }

    @Test
    public void duplicateGrenadeOwnersDoNotPartiallyReplaceTheSnapshot() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS,
                new JSONArray().put(pairing(2, 3)).put(pairing(4, 3))));
        assertArrayEquals(before, globals.mGrenadePairings);
    }

    @Test
    public void zeroGrenadeIdDoesNotPartiallyReplaceTheSnapshot() throws Exception {
        int[] before = globals.mGrenadePairings.clone();
        parse(new JSONObject().put(TcpServer.JSON_GRENADE_PAIRINGS,
                new JSONArray().put(pairing(2, 3)).put(pairing(0, 4))));
        assertArrayEquals(before, globals.mGrenadePairings);
    }

    @Test
    public void incrementalGpsUpdateRefreshesThePlayersTeam() throws Exception {
        putGPS(5, 1);
        parse(gpsUpdate(false, gps(5, 2)));
        assertEquals(2, globals.mGPSData.get((byte) 5).team);
        assertTrue(globals.mGPSData.get((byte) 5).hasUpdate);
    }

    @Test
    public void explicitFalseGpsFullUpdateKeepsOtherPlayers() throws Exception {
        Globals.GPSData retained = putGPS(9, 2);
        parse(gpsUpdate(false, gps(5, 1)).put(TcpServer.JSON_GPSFULLUPDATE, false));
        assertSame(retained, globals.mGPSData.get((byte) 9));
        assertFalse(client.events.get(0).getBooleanExtra(NetMsg.INTENT_FULLUPDATE, true));
    }

    @Test
    public void malformedFullGpsUpdateDoesNotClearExistingLocations() throws Exception {
        assertMalformedGPSLeavesExistingData(true);
    }

    @Test
    public void malformedIncrementalGpsUpdateDoesNotPartiallyMovePlayers() throws Exception {
        assertMalformedGPSLeavesExistingData(false);
    }

    private void assertMalformedGPSLeavesExistingData(boolean full) throws Exception {
        Globals.GPSData original = putGPS(5, 1);
        parse(gpsUpdate(full, gps(5, 2), new JSONObject().put(TcpServer.JSON_PLAYERID, 9)));
        assertEquals(1, globals.mGPSData.size());
        assertSame(original, globals.mGPSData.get((byte) 5));
        assertEquals(1.0, original.longitude, 0.0);
        assertEquals(2.0, original.latitude, 0.0);
        assertEquals(1, original.team);
        assertFalse(original.hasUpdate);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void invalidGpsCoordinatesDoNotEraseLastKnownLocations() throws Exception {
        Globals.GPSData original = putGPS(5, 1);
        JSONObject invalid = gps(9, 2).put(TcpServer.JSON_GPSLATITUDE, 200.0);
        parse(gpsUpdate(true, invalid));
        assertSame(original, globals.mGPSData.get((byte) 5));
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void duplicateGpsPlayersDoNotPartiallyReplaceTheSnapshot() throws Exception {
        Globals.GPSData original = putGPS(5, 1);
        parse(gpsUpdate(true, gps(5, 2), gps(5, 1)));
        assertEquals(1, globals.mGPSData.size());
        assertSame(original, globals.mGPSData.get((byte) 5));
        assertEquals(1, original.team);
        assertTrue(client.events.isEmpty());
    }

    @Test
    public void zeroCoordinatePlaceholderDoesNotReplaceTheGpsSnapshot() throws Exception {
        Globals.GPSData original = putGPS(5, 1);
        double[][] placeholders = {{0, 0}, {10, 0}, {0, 20}};
        for (double[] coordinates : placeholders) {
            JSONObject placeholder = gps(5, 2).put(TcpServer.JSON_GPSLONGITUDE, coordinates[0])
                    .put(TcpServer.JSON_GPSLATITUDE, coordinates[1]);
            parse(gpsUpdate(true, placeholder));
            assertSame(original, globals.mGPSData.get((byte) 5));
            assertTrue(client.events.isEmpty());
        }
    }

    @Test
    public void validFullGpsSnapshotReplacesLocationsAndCanClearAllMarkers() throws Exception {
        putGPS(9, 2);
        parse(gpsUpdate(true, gps(5, 1)));
        assertEquals(1, globals.mGPSData.size());
        assertEquals(10.0, globals.mGPSData.get((byte) 5).longitude, 0.0);
        assertTrue(client.events.get(0).getBooleanExtra(NetMsg.INTENT_FULLUPDATE, false));
        parse(gpsUpdate(true));
        assertTrue(globals.mGPSData.isEmpty());
        assertEquals(2, client.events.size());
    }

    private Globals.GPSData putGPS(int id, int team) {
        Globals.GPSData gps = new Globals.GPSData();
        gps.longitude = 1.0;
        gps.latitude = 2.0;
        gps.team = team;
        gps.hasUpdate = false;
        globals.mGPSData.put((byte) id, gps);
        return gps;
    }

    private static JSONObject gps(int id, int team) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id).put(TcpServer.JSON_TEAM, team)
                .put(TcpServer.JSON_GPSLONGITUDE, 10.0).put(TcpServer.JSON_GPSLATITUDE, 20.0);
    }

    private static JSONObject gpsUpdate(boolean full, JSONObject... locations) throws Exception {
        JSONArray updates = new JSONArray();
        for (JSONObject location : locations) updates.put(location);
        JSONObject message = new JSONObject().put(TcpServer.JSON_GPSUPDATE, updates);
        if (full) message.put(TcpServer.JSON_GPSFULLUPDATE, true);
        return message;
    }

    private static JSONObject pairing(int grenade, int player) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PAIRED_GRENADE_ID, grenade).put(TcpServer.JSON_PLAYERID, player);
    }

    private Map<String, Object> localSettings() throws Exception {
        Map<String, Object> values = new HashMap<>();
        for (String field : LOCAL_SETTINGS) values.put(field, Globals.class.getField(field).get(globals));
        return values;
    }

    private void assertOriginalSettings() throws Exception {
        assertEquals(baselineLocalSettings, localSettings());
        assertEquals(1, globals.mPlayerSettings.size());
        assertSame(baselineSettings, globals.mPlayerSettings.get((byte) 1));
        assertEquals(20, baselineSettings.health);
        assertTrue(client.events.isEmpty());
        assertEquals(1, globals.mPlayerSettingsSemaphore.availablePermits());
    }

    private static JSONObject settingsMessage(JSONObject... players) throws Exception {
        JSONArray settings = new JSONArray();
        for (JSONObject player : players) settings.put(player);
        return new JSONObject().put(TcpServer.JSON_PLAYERSETTINGS, settings)
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false);
    }

    private static JSONObject settings(int id) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id).put(TcpServer.JSON_HEALTH, 50)
                .put(TcpServer.JSON_RELOAD_SHOTS, 200).put(TcpServer.JSON_RELOAD_TIME, 2500)
                .put(TcpServer.JSON_RELOAD_ON_EMPTY, false).put(TcpServer.JSON_SPAWN_TIME, 20)
                .put(TcpServer.JSON_DAMAGE, -10).put(TcpServer.JSON_LIVESLIMIT, 3)
                .put(TcpServer.JSON_SHOT_MODE_SINGLE, false).put(TcpServer.JSON_SHOT_MODE_BURST3, false)
                .put(TcpServer.JSON_SHOT_MODE_AUTO, true)
                .put(TcpServer.JSON_FIRING_MODE, Globals.FIRING_MODE_INDOOR_NO_CONE);
    }

    private static JSONObject player(int id) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id).put(TcpServer.JSON_PLAYERNAME, "Player " + id)
                .put(TcpServer.JSON_PLAYERIP, "/127.0.0." + id);
    }

    private static JSONObject scoreboardPlayer(int id) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id)
                .put(TcpServer.JSON_PLAYERNAME, "Player " + id)
                .put(TcpServer.JSON_PLAYERPOINTS, 1)
                .put(TcpServer.JSON_PLAYERELIMINATED, 0);
    }

    private static JSONObject roster(JSONArray players) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERS, players).put(TcpServer.JSON_LIMITS, new JSONObject())
                .put(TcpServer.JSON_GAMEMODE, Globals.GAME_MODE_2TEAMS).put(TcpServer.JSON_ONLY_SERVER_SETTINGS, false);
    }

    private void assertOriginalRoster() {
        assertEquals(1, globals.mTeamIPMap.size());
        assertEquals(originalPeer, globals.mTeamIPMap.get((byte) 2));
        assertEquals(Byte.valueOf((byte) 2), globals.mIPTeamMap.get(originalPeer));
        assertEquals("Original peer", globals.mTeamPlayerNameMap.get((byte) 2));
    }

    private void parse(JSONObject json) throws Exception { parse(json.toString()); }

    private void parse(String json) throws Exception {
        Method method = TcpClient.class.getDeclaredMethod("parseGameInfo", String.class);
        method.setAccessible(true);
        TcpInputTestData.invokeParser(method, client, json);
    }

    private void clearMaps() {
        Globals.getmGPSDataSemaphore();
        try { globals.mGPSData.clear(); } finally { globals.mGPSDataSemaphore.release(); }
        Globals.getmTeamPlayerNameSemaphore();
        try { globals.mTeamPlayerNameMap.clear(); } finally { globals.mTeamPlayerNameSemaphore.release(); }
        Globals.getmTeamIPMapSemaphore();
        try { globals.mTeamIPMap.clear(); } finally { globals.mTeamIPMapSemaphore.release(); }
        Globals.getmIPTeamMapSemaphore();
        try { globals.mIPTeamMap.clear(); } finally { globals.mIPTeamMapSemaphore.release(); }
    }

    private static final class RecordingClient extends TcpClient {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
    }
}
