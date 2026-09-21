package com.simplecoil.simplecoil;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the server's real parser and client loop using in-memory sockets only. */
@RunWith(AndroidJUnit4.class)
public class TcpServerRegressionTest {
    private RecordingServer server;
    private Object clientHandler;
    private Class<?> clientType;
    private Map<Integer, Object> clients;
    private int[] originalPairings;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> server = new RecordingServer());
        server.setDedicated(true);
        clients = new ConcurrentHashMap<>();
        set(server, "mClientData", clients);
        clientType = Class.forName(TcpServer.class.getName() + "$ClientData");
        clientHandler = innerInstance("ClientThread");
        Globals globals = Globals.getInstance();
        originalPairings = globals.mGrenadePairings.clone();
        Globals.ClearGrenadePairings(true);
        globals.mUseGPS = false;
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameMode = Globals.GAME_MODE_FFA;
        globals.mGameLimit = Globals.GAME_LIMIT_NONE;
        globals.mOnlyServerSettings = false;
        globals.mTournamentMode = false;
        clearPlayerMaps();
    }

    @After
    public void tearDown() throws Exception {
        server.stopTcpServer();
        for (Object client : clients.values())
            invoke(client, "close", new Class<?>[0]);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
        Globals.getInstance().mTournamentMode = false;
        System.arraycopy(originalPairings, 0, Globals.getInstance().mGrenadePairings, 0, originalPairings.length);
        clearPlayerMaps();
    }

    @Test
    public void deeplyNestedArraysCannotCrashServerParsing() throws Exception {
        assertDeepMessageIgnored(true);
    }

    @Test
    public void deeplyNestedObjectsCannotCrashServerParsing() throws Exception {
        assertDeepMessageIgnored(false);
    }

    private void assertDeepMessageIgnored(boolean arrays) throws Exception {
        Object player = client(1, 0);
        String message = TcpInputTestData.nested(10000, arrays);
        TcpInputTestData.assertFitsFrame(message);
        parse(player, message);
        assertEquals((byte) 0, get(player, "mPlayerID"));
        assertTrue(Globals.getInstance().mTeamPlayerNameMap.isEmpty());
        register(player, 1, false);
        assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
    }

    @Test
    public void oversizedPlayerNamesCannotEnterTheLobby() throws Exception {
        Object player = client(1, 0);
        for (int length : new int[]{21, 65400}) {
            JSONObject hello = new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                    .put(TcpServer.JSON_PLAYERNAME, TcpInputTestData.repeat('a', length));
            TcpInputTestData.assertFitsFrame(hello.toString());
            parse(player, hello);
            assertEquals((byte) 0, get(player, "mPlayerID"));
            assertTrue(Globals.getInstance().mTeamPlayerNameMap.isEmpty());
            assertTrue(Globals.getInstance().mTeamIPMap.isEmpty());
        }
        register(player, 1, false);
        assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
    }

    @Test
    public void oversizedRenameCannotReplaceTheExistingPlayerName() throws Exception {
        Object player = client(1, 1);
        for (int length : new int[]{21, 65400}) {
            JSONObject rename = new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                    .put(TcpServer.JSON_PLAYERNAMECHANGE, TcpInputTestData.repeat('a', length));
            TcpInputTestData.assertFitsFrame(rename.toString());
            parse(player, rename);
            assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
            assertEquals(0, server.playerUpdates);
        }
    }

    @Test
    public void oversizedRejoinCannotDisplaceTheExistingConnection() throws Exception {
        Object original = client(1, 1);
        Socket originalSocket = (Socket) get(original, "clientSocket");
        Object replacement = client(2, 0);
        parse(replacement, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_REJOIN, true)
                .put(TcpServer.JSON_PLAYERNAME, TcpInputTestData.repeat('a', 21)));
        assertSame(originalSocket, get(original, "clientSocket"));
        assertFalse(originalSocket.isClosed());
        assertEquals((byte) 0, get(replacement, "mPlayerID"));
        assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
    }

    @Test
    public void nonStringNamesCannotEnterTheLobby() throws Exception {
        Object player = client(1, 0);
        for (Object name : new Object[]{JSONObject.NULL, 123, true, new JSONObject(), new org.json.JSONArray()}) {
            parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAME, name));
            assertEquals((byte) 0, get(player, "mPlayerID"));
            assertTrue(Globals.getInstance().mTeamPlayerNameMap.isEmpty());
        }
    }

    @Test
    public void nonStringRenameCannotOverwriteTheExistingPlayerName() throws Exception {
        Object player = client(1, 1);
        for (Object name : new Object[]{JSONObject.NULL, 123, true, new JSONObject(), new org.json.JSONArray()}) {
            parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAMECHANGE, name));
            assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
            assertEquals(0, server.playerUpdates);
        }
    }

    @Test
    public void normalAndEmptyNamesStillWorkForRegistrationAndRename() throws Exception {
        Object player = client(1, 0);
        String name = TcpInputTestData.repeat('a', 20);
        parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAME, name));
        assertEquals(name, Globals.getInstance().getPlayerName((byte) 1));
        for (String replacement : new String[]{"", "[]{}'\"\\/", name}) {
            parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                    .put(TcpServer.JSON_PLAYERNAMECHANGE, replacement));
            assertEquals(replacement, Globals.getInstance().getPlayerName((byte) 1));
        }
        assertEquals(3, server.playerUpdates);
    }

    @Test
    public void lossyPlayerIdsCannotRegisterAsAnotherPlayer() throws Exception {
        Object player = client(1, 0);
        for (Object id : new Object[]{4294967297L, -4294967295L, 1.75, "1", "1.75"}) {
            parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, id)
                    .put(TcpServer.JSON_PLAYERNAME, "Invalid ID"));
            assertEquals((byte) 0, get(player, "mPlayerID"));
            assertTrue(Globals.getInstance().mTeamPlayerNameMap.isEmpty());
            assertTrue(Globals.getInstance().mTeamIPMap.isEmpty());
        }
        register(player, 1, false);
        assertEquals((byte) 1, get(player, "mPlayerID"));
    }

    @Test
    public void overflowingRejoinIdCannotDisplaceTheExistingConnection() throws Exception {
        Object original = client(1, 1);
        Socket originalSocket = (Socket) get(original, "clientSocket");
        Object replacement = client(2, 0);
        parse(replacement, new JSONObject().put(TcpServer.JSON_PLAYERID, 4294967297L)
                .put(TcpServer.JSON_REJOIN, true).put(TcpServer.JSON_PLAYERNAME, "Replacement"));
        assertSame(originalSocket, get(original, "clientSocket"));
        assertFalse(originalSocket.isClosed());
        assertEquals((byte) 0, get(replacement, "mPlayerID"));
        assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
    }

    @Test
    public void lossyRenameIdsCannotPassTheIdentityCheck() throws Exception {
        Object player = client(1, 1);
        for (Object id : new Object[]{4294967297L, 1.75, "1"}) {
            parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, id)
                    .put(TcpServer.JSON_PLAYERNAMECHANGE, "Invalid rename"));
            assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
            assertEquals(0, server.playerUpdates);
        }
    }

    @Test
    public void lossyGrenadeIdsAndOwnersCannotChangeExistingPairings() throws Exception {
        Object player = client(1, 1);
        Globals.getInstance().mGrenadePairings[2] = 1;
        Globals.getInstance().mGrenadePairings[3] = 11;
        int before = server.messages.size();
        for (String key : new String[]{TcpServer.JSON_PLAYERID, TcpServer.JSON_PAIRED_GRENADE_ID}) {
            int valid = key.equals(TcpServer.JSON_PLAYERID) ? 1 : 3;
            for (Object invalid : new Object[]{4294967296L + valid, valid + 0.75, "" + valid}) {
                parse(player, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                        .put(TcpServer.JSON_PAIRED_GRENADE_ID, 3).put(key, invalid));
                assertEquals(1, Globals.getInstance().mGrenadePairings[2]);
                assertEquals(11, Globals.getInstance().mGrenadePairings[3]);
                assertEquals(before, server.messages.size());
            }
        }
    }

    @Test
    public void newGrenadePairingRemovesThatPlayersPreviousPairing() throws Exception {
        Object player = client(1, 1);
        Globals.getInstance().mGrenadePairings[2] = 1;
        Globals.getInstance().mGrenadePairings[4] = 11;
        pair(player, 1, 3);
        assertEquals(Globals.INVALID_PLAYER_ID, Globals.getInstance().mGrenadePairings[2]);
        assertEquals(1, Globals.getInstance().mGrenadePairings[3]);
        assertEquals(11, Globals.getInstance().mGrenadePairings[4]);
        assertEquals(2, lastPairings().length());
    }

    @Test
    public void unpairClearsAllOfThePlayersStaleGrenadesAndPublishesEmptySnapshot() throws Exception {
        Object player = client(1, 1);
        Globals.getInstance().mGrenadePairings[2] = 1;
        Globals.getInstance().mGrenadePairings[3] = 1;
        pair(player, 1, 0);
        for (int owner : Globals.getInstance().mGrenadePairings)
            assertEquals(Globals.INVALID_PLAYER_ID, owner);
        assertEquals(0, lastPairings().length());
        assertEquals(1, Globals.getInstance().mGrenadePairingsSemaphore.availablePermits());
    }

    @Test
    public void unpairDoesNotClearAnotherPlayersGrenade() throws Exception {
        Object player = client(1, 1);
        Globals.getInstance().mGrenadePairings[2] = 1;
        Globals.getInstance().mGrenadePairings[3] = 11;
        pair(player, 1, 0);
        assertEquals(Globals.INVALID_PLAYER_ID, Globals.getInstance().mGrenadePairings[2]);
        assertEquals(11, Globals.getInstance().mGrenadePairings[3]);
        assertEquals(1, lastPairings().length());
    }

    @Test
    public void invalidGrenadeOrSpoofedOwnerCannotRemoveExistingPairings() throws Exception {
        Object player = client(1, 1);
        Globals.getInstance().mGrenadePairings[2] = 1;
        Globals.getInstance().mGrenadePairings[3] = 11;
        int before = server.messages.size();
        pair(player, 1, Globals.MAX_GRENADE_IDS);
        pair(player, 11, 0);
        assertEquals(1, Globals.getInstance().mGrenadePairings[2]);
        assertEquals(11, Globals.getInstance().mGrenadePairings[3]);
        assertEquals(before, server.messages.size());
    }

    private void pair(Object client, int player, int grenade) throws Exception {
        parse(client, new JSONObject().put(TcpServer.JSON_PLAYERID, player)
                .put(TcpServer.JSON_PAIRED_GRENADE_ID, grenade));
    }

    private org.json.JSONArray lastPairings() throws Exception {
        String message = server.messages.get(server.messages.size() - 1);
        return new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                + TcpServer.TCPPREFIX_JSON.length())).getJSONArray(TcpServer.JSON_GRENADE_PAIRINGS);
    }

    @Test
    public void nonNumericEliminationDoesNotDropRegisteredPlayer() throws Exception {
        assertMalformedEliminationIgnored("bad");
    }

    @Test
    public void missingEliminationPlayerDoesNotDropRegisteredPlayer() throws Exception {
        assertMalformedEliminationIgnored("");
    }

    @Test
    public void overflowingEliminationPlayerDoesNotDropRegisteredPlayer() throws Exception {
        assertMalformedEliminationIgnored("999999999999999999999");
    }

    private void assertMalformedEliminationIgnored(String id) throws Exception {
        Object player = client(1, 1);
        int before = server.playerUpdates;
        processMessage(player, NetMsg.NETMSG_ELIMINATED + id);
        assertEquals("Malformed message must not trigger a disconnect update", before, server.playerUpdates);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void repeatedRegistrationKeepsTheRegisteredConnection() throws Exception {
        assertRepeatedRegistration(false);
    }

    @Test
    public void repeatedRejoinKeepsTheRegisteredConnection() throws Exception {
        assertRepeatedRegistration(true);
    }

    @Test
    public void duplicateRegistrationWithoutAnExplicitRejoinCannotDisplaceAConnectedPlayer() throws Exception {
        Object original = client(1, 1);
        Socket originalSocket = (Socket) get(original, "clientSocket");

        Object unmarkedDuplicate = client(2, 0);
        Socket unmarkedDuplicateSocket = (Socket) get(unmarkedDuplicate, "clientSocket");
        register(unmarkedDuplicate, 1, false);
        assertSame(original, clients.get(1));
        assertSame(originalSocket, get(original, "clientSocket"));
        assertFalse(originalSocket.isClosed());
        assertTrue(unmarkedDuplicateSocket.isClosed());
        assertFalse(clients.containsKey(2));

        Object falseFlagDuplicate = client(3, 0);
        Socket falseFlagDuplicateSocket = (Socket) get(falseFlagDuplicate, "clientSocket");
        parse(falseFlagDuplicate, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAME, "Duplicate")
                .put(TcpServer.JSON_REJOIN, false));
        assertSame(original, clients.get(1));
        assertSame(originalSocket, get(original, "clientSocket"));
        assertFalse(originalSocket.isClosed());
        assertTrue(falseFlagDuplicateSocket.isClosed());
        assertFalse(clients.containsKey(3));
    }

    private void assertRepeatedRegistration(boolean rejoin) throws Exception {
        Object client = client(1, 1);
        Socket socket = (Socket) get(client, "clientSocket");
        set(client, "points", 7);
        register(client, 1, rejoin);
        assertEquals(1, clients.size());
        assertSame(client, clients.get(1));
        assertSame(socket, get(client, "clientSocket"));
        assertFalse(socket.isClosed());
        assertEquals(7, server.getScore((byte) 1).points);
    }

    @Test
    public void registeredConnectionCannotChangePlayerIdentity() throws Exception {
        Object client = client(1, 1);
        register(client, 2, false);
        assertEquals((byte) 1, get(client, "mPlayerID"));
        assertNotNull(server.getScore((byte) 1));
        assertNull(server.getScore((byte) 2));
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 2));
    }

    @Test
    public void scoreLookupBeforeTcpStartupReturnsNoScore() throws Exception {
        set(server, "mClientData", null);
        assertNull("An empty startup scoreboard must not crash", server.getScore((byte) 1));
    }

    @Test
    public void oneEndpointCannotRegisterAsTwoDifferentPlayers() throws Exception {
        Object original = client(1, 1);
        Object duplicateEndpoint = client(2, 0, new MemorySocket(1));
        Socket duplicateSocket = (Socket) get(duplicateEndpoint, "clientSocket");

        register(duplicateEndpoint, 2, false);

        assertSame(original, clients.get(1));
        assertFalse(((Socket) get(original, "clientSocket")).isClosed());
        assertTrue("The conflicting socket was left connected", duplicateSocket.isClosed());
        assertFalse("The conflicting endpoint entered the client roster", clients.containsKey(2));
        assertEquals(Byte.valueOf((byte) 1), Globals.getInstance().mIPTeamMap.get(
                ((Socket) get(original, "clientSocket")).getInetAddress()));
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 2));
    }

    @Test
    public void removingAlreadyClosedClientDoesNotCrashOrLeaveEndpoints() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object client = client(1, 1);
        InetAddress address = ((Socket) get(client, "clientSocket")).getInetAddress();
        invoke(client, "close", new Class<?>[0]);
        try {
            remove(client, 1);
        } finally {
            // The pre-fix crash abandoned this lock. Recover it in this isolated
            // fixture so a failing regression cannot hang the rest of the suite.
            if (Globals.getInstance().mIPTeamMapSemaphore.availablePermits() == 0)
                Globals.getInstance().mIPTeamMapSemaphore.release();
        }
        assertFalse(clients.containsKey(1));
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 1));
        assertFalse(Globals.getInstance().mIPTeamMap.containsKey(address));
        assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 1));
    }

    @Test
    public void unregisteredConnectionCannotAwardEliminationPoints() throws Exception {
        Object sender = client(1, 0);
        client(2, 11);
        eliminate(sender, 11);
        assertEquals(0, server.getScore((byte) 11).points);
        assertEquals(0, get(sender, "eliminated"));
    }

    @Test
    public void eliminationReportsInLobbyDoNotChangeScores() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object victim = client(1, 1);
        client(2, 11);
        eliminate(victim, 11);
        assertEquals(0, server.getScore((byte) 11).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void eliminationReportsDuringSharedCountdownDoNotChangeScores() throws Exception {
        Object victim = client(1, 1);
        client(2, 11);
        set(server, "mScheduledStart", android.os.SystemClock.elapsedRealtime() + 10000);
        eliminate(victim, 11);
        assertEquals(0, server.getScore((byte) 11).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void changingToFourTeamsRecognizesNewEnemies() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Object victim = client(1, 1);
        client(2, 6);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        eliminate(victim, 6);
        assertEquals(1, server.getScore((byte) 6).points);
        assertEquals(1, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void mergingTeamsRejectsPreviouslyHostileFriendlyFire() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        Object victim = client(1, 1);
        client(2, 6);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        eliminate(victim, 6);
        assertEquals(0, server.getScore((byte) 6).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void repeatedEliminationsCannotOverflowTheScoreboard() throws Exception {
        Object victim = client(1, 1);
        Object attacker = client(2, 11);
        set(victim, "eliminated", Globals.MAX_SCOREBOARD_VALUE);
        set(attacker, "points", Globals.MAX_SCOREBOARD_VALUE);

        eliminate(victim, 11);

        assertEquals(Globals.MAX_SCOREBOARD_VALUE, server.getScore((byte) 1).eliminated);
        assertEquals(Globals.MAX_SCOREBOARD_VALUE, server.getScore((byte) 11).points);
    }

    @Test
    public void gpsTeamReflectsTheCurrentGameMode() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Object player = client(1, 6);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        parse(player, new JSONObject().put(TcpServer.JSON_GPSLONGITUDE, 10.0)
                .put(TcpServer.JSON_GPSLATITUDE, 20.0));
        assertEquals(2, Globals.getInstance().mGPSData.get((byte) 6).team);
    }

    @Test
    public void zeroCoordinatePlaceholderDoesNotMoveThePlayer() throws Exception {
        Object player = client(1, 6);
        parse(player, new JSONObject().put(TcpServer.JSON_GPSLONGITUDE, 10.0)
                .put(TcpServer.JSON_GPSLATITUDE, 20.0));
        Globals.GPSData saved = Globals.getInstance().mGPSData.get((byte) 6);
        saved.hasUpdate = false;
        double[][] placeholders = {{0, 0}, {10, 0}, {0, 20}};
        for (double[] coordinates : placeholders) {
            parse(player, new JSONObject().put(TcpServer.JSON_GPSLONGITUDE, coordinates[0])
                    .put(TcpServer.JSON_GPSLATITUDE, coordinates[1]));
            assertSame(saved, Globals.getInstance().mGPSData.get((byte) 6));
            assertEquals(10, saved.longitude, 0);
            assertEquals(20, saved.latitude, 0);
            assertFalse(saved.hasUpdate);
        }
    }

    @Test
    public void serverEnforcesIndividualScoreLimitWithoutClientRequest() throws Exception {
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 1;
        Globals.getInstance().mOnlyServerSettings = true;
        Object victim = client(1, 1);
        client(2, 11);
        eliminate(victim, 11);
        assertEquals(1, server.endRequests);
    }

    @Test
    public void serverEnforcesCombinedTeamScoreLimit() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 3;
        Object attacker = client(1, 1);
        Object teammate = client(2, 2);
        Object victim = client(3, 11);
        set(attacker, "points", 1);
        set(teammate, "points", 1);
        eliminate(victim, 1);
        assertEquals(1, server.endRequests);
    }

    @Test
    public void enemyPointsDoNotCountTowardTeamScoreLimit() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 3;
        client(1, 1);
        Object victim = client(2, 11);
        set(victim, "points", 20);
        eliminate(victim, 1);
        assertEquals(0, server.endRequests);
    }

    @Test
    public void disabledScoreLimitDoesNotEndRound() throws Exception {
        Globals.getInstance().mScoreLimit = 1;
        Object victim = client(1, 1);
        client(2, 11);
        eliminate(victim, 11);
        assertEquals(0, server.endRequests);
    }

    @Test
    public void leavingMidRoundPreservesScoresAndSpentLives() throws Exception {
        Object player = client(1, 1);
        set(player, "points", 7);
        set(player, "eliminated", 5);
        remove(player, 1);
        TcpServer.ScoreData score = server.getScore((byte) 1);
        assertNotNull(score);
        assertEquals(7, score.points);
        assertEquals(5, score.eliminated);
        assertFalse(score.isConnected);
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 1));
    }

    @Test
    public void rejoiningAfterLeaveRestoresScoresAndSpentLives() throws Exception {
        Object player = client(1, 1);
        set(player, "points", 7);
        set(player, "eliminated", 5);
        remove(player, 1);
        client(2, 1);
        TcpServer.ScoreData score = server.getScore((byte) 1);
        assertEquals(7, score.points);
        assertEquals(5, score.eliminated);
        assertEquals(true, score.isConnected);
    }

    @Test
    public void leavingLobbyDoesNotReserveAnOldScore() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object player = client(1, 1);
        set(player, "points", 7);
        remove(player, 1);
        assertNull(server.getScore((byte) 1));
    }

    @Test
    public void disconnectedPlayerCanRejoinWithScoresAndNewEndpoint() throws Exception {
        Object existing = client(1, 1);
        set(existing, "points", 7);
        set(existing, "eliminated", 3);
        InetAddress oldAddress = ((Socket) get(existing, "clientSocket")).getInetAddress();
        invoke(existing, "close", new Class<?>[0]);
        Object replacement = client(2, 0);
        Socket newSocket = (Socket) get(replacement, "clientSocket");
        register(replacement, 1, true);
        assertSame(existing, clients.get(1));
        assertFalse(clients.containsKey(2));
        assertSame(newSocket, get(existing, "clientSocket"));
        assertFalse(newSocket.isClosed());
        assertEquals(7, server.getScore((byte) 1).points);
        assertEquals(3, server.getScore((byte) 1).eliminated);
        assertEquals(newSocket.getInetAddress(), Globals.getInstance().mTeamIPMap.get((byte) 1));
        assertFalse(Globals.getInstance().mIPTeamMap.containsKey(oldAddress));
    }

    @Test
    public void departedTeammatePointsStillCountTowardVictory() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 3;
        client(1, 1);
        Object teammate = client(2, 2);
        Object victim = client(3, 11);
        set(teammate, "points", 2);
        remove(teammate, 2);
        eliminate(victim, 1);
        assertEquals(1, server.endRequests);
    }

    @Test
    public void liveScoreboardIncludesPlayersWhoLeftTheRound() throws Exception {
        Object departed = client(1, 1);
        Object remaining = client(2, 2);
        set(departed, "points", 7);
        set(departed, "eliminated", 5);
        set(remaining, "points", 3);
        remove(departed, 1);
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 1));
        assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 1));
        set(server, "keepListening", true);
        server.sendPlayerData(TcpServer.SEND_ALL);
        assertDepartedScore(readScoreboard(outputFor(remaining)));
    }

    @Test
    public void finalScoreboardIncludesPlayersWhoRanOutOfLives() throws Exception {
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_LIVES;
        Object eliminated = client(1, 1);
        Object remaining = client(2, 2);
        set(eliminated, "points", 7);
        set(eliminated, "eliminated", 5);
        set(remaining, "points", 3);
        // Running out of lives sends LEAVE, removing the player from the lobby.
        remove(eliminated, 1);
        ByteArrayOutputStream output = outputFor(remaining);
        server.performRealEnd = true;
        set(server, "keepListening", true);
        server.endGame();
        assertTrue(server.roundEnded.await(3, TimeUnit.SECONDS));
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        assertDepartedScore(readScoreboard(frames));
        assertEquals(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME,
                frames.readUTF());
        assertNull("Round history must still be cleared for the next game", server.getScore((byte) 1));
    }

    @Test
    public void leavingRetainsTheLatestPlayerNameOnlyInRoundHistory() throws Exception {
        Object departed = client(1, 1);
        parse(departed, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAMECHANGE, "Renamed player"));
        remove(departed, 1);
        assertEquals("Renamed player", server.getScore((byte) 1).playerName);
        assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 1));
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 1));
    }

    @Test
    public void rejoinedPlayerHasOneScoreboardRowWithTheirCurrentName() throws Exception {
        Object departed = client(1, 1);
        Object remaining = client(2, 2);
        set(departed, "points", 7);
        set(departed, "eliminated", 5);
        remove(departed, 1);
        Object replacement = client(3, 1);
        parse(replacement, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAMECHANGE, "Rejoined player"));
        set(server, "keepListening", true);
        server.sendPlayerData(TcpServer.SEND_ALL);
        JSONArray players = readScoreboard(outputFor(remaining));
        assertEquals(2, players.length());
        JSONObject score = scoreFor(players, 1);
        assertEquals("Rejoined player", score.getString(TcpServer.JSON_PLAYERNAME));
        assertEquals(7, score.getInt(TcpServer.JSON_PLAYERPOINTS));
        assertEquals(5, score.getInt(TcpServer.JSON_PLAYERELIMINATED));
        assertTrue(score.has(TcpServer.JSON_PLAYERIP));
    }

    @Test
    public void leavingTheLobbyDoesNotAddAPlayerToTheRoundScoreboard() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object departed = client(1, 1);
        Object remaining = client(2, 2);
        set(departed, "points", 7);
        remove(departed, 1);
        set(server, "keepListening", true);
        server.sendPlayerData(TcpServer.SEND_ALL);
        JSONArray players = readScoreboard(outputFor(remaining));
        assertEquals(1, players.length());
        assertEquals(2, players.getJSONObject(0).getInt(TcpServer.JSON_PLAYERID));
        assertNull(server.getScore((byte) 1));
    }

    @Test
    public void playerRemovalDoesNotDeadlockWithTheGpsPublisher() throws Exception {
        Object departed = client(1, 1);
        Globals globals = Globals.getInstance();
        Semaphore originalLocations = globals.mGPSDataSemaphore;
        PausingFirstAcquireSemaphore locations = new PausingFirstAcquireSemaphore();
        Thread removal = null;
        Thread publisher = null;
        Throwable[] failures = new Throwable[2];
        globals.mGPSDataSemaphore = locations;
        try {
            globals.mUseGPS = true;
            set(server, "keepListening", true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
            Runnable update = (Runnable) get(server, "mGPSRunnable");
            ((android.os.Handler) get(server, "mGPSHandler")).removeCallbacks(update);

            removal = new Thread(() -> {
                try {
                    remove(departed, 1);
                } catch (Throwable error) {
                    failures[0] = error;
                }
            }, "SimpleCoil remove player");
            removal.start();
            assertTrue("Player removal did not acquire the GPS lock",
                    locations.awaitFirstAcquire(2000));

            publisher = new Thread(update, "SimpleCoil GPS publisher");
            publisher.setUncaughtExceptionHandler((thread, error) -> failures[1] = error);
            publisher.start();
            assertTrue("GPS publisher did not reach the location lock",
                    awaitQueued(locations, 2000));

            locations.resumeFirstAcquire();
            removal.join(2000);
            publisher.join(2000);
            assertFalse("Player removal deadlocked with the GPS publisher", removal.isAlive());
            assertFalse("GPS publisher deadlocked with player removal", publisher.isAlive());
            assertNull("Player removal failed", failures[0]);
            assertNull("GPS publisher failed", failures[1]);
            assertEquals("Player removal lost the required full GPS refresh", 20,
                    get(server, "mGPSIntervalCount"));
        } finally {
            locations.resumeFirstAcquire();
            if (publisher != null && publisher.isAlive())
                publisher.interrupt();
            if (removal != null && removal.isAlive())
                removal.interrupt();
            if (publisher != null)
                publisher.join(2000);
            if (removal != null)
                removal.join(2000);
            globals.mGPSDataSemaphore = originalLocations;
            globals.mUseGPS = false;
        }
    }

    @Test
    public void twentyPlayerScoreboardKeepsEveryResultWhenNineteenPlayersLeave() throws Exception {
        for (int id = 1; id <= 20; id++) {
            Object player = client(id, id);
            set(player, "points", id);
            set(player, "eliminated", 2);
        }
        for (int id = 1; id < 20; id++) remove(clients.get(id), id);
        set(server, "keepListening", true);
        server.sendPlayerData(TcpServer.SEND_ALL);
        JSONArray players = readScoreboard(outputFor(clients.get(20)));
        assertEquals(20, players.length());
        for (int id = 1; id <= 20; id++) {
            JSONObject score = scoreFor(players, id);
            assertEquals("Player " + id, score.getString(TcpServer.JSON_PLAYERNAME));
            assertEquals(id, score.getInt(TcpServer.JSON_PLAYERPOINTS));
            assertEquals(2, score.getInt(TcpServer.JSON_PLAYERELIMINATED));
        }
        assertEquals("Score history changed the active roster", 1, Globals.getInstance().mTeamIPMap.size());
    }

    private void assertDepartedScore(JSONArray players) throws Exception {
        assertEquals("The scoreboard lost a player who already spent their lives", 2, players.length());
        JSONObject departed = scoreFor(players, 1);
        assertEquals("Player 1", departed.getString(TcpServer.JSON_PLAYERNAME));
        assertEquals(7, departed.getInt(TcpServer.JSON_PLAYERPOINTS));
        assertEquals(5, departed.getInt(TcpServer.JSON_PLAYERELIMINATED));
        assertFalse("Score history must not restore a departed network endpoint", departed.has(TcpServer.JSON_PLAYERIP));
        assertEquals(3, scoreFor(players, 2).getInt(TcpServer.JSON_PLAYERPOINTS));
    }

    private JSONObject scoreFor(JSONArray players, int playerID) throws Exception {
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.getJSONObject(i);
            if (player.getInt(TcpServer.JSON_PLAYERID) == playerID)
                return player;
        }
        throw new AssertionError("Missing scoreboard player " + playerID);
    }

    private ByteArrayOutputStream outputFor(Object player) throws Exception {
        return (ByteArrayOutputStream) ((Socket) get(player, "clientSocket")).getOutputStream();
    }

    private JSONArray readScoreboard(ByteArrayOutputStream output) throws Exception {
        return readScoreboard(new DataInputStream(new ByteArrayInputStream(output.toByteArray())));
    }

    private JSONArray readScoreboard(DataInputStream frames) throws Exception {
        String frame = frames.readUTF();
        String prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
        assertTrue(frame.startsWith(prefix));
        return new JSONObject(frame.substring(prefix.length())).getJSONArray(TcpServer.JSON_PLAYERDATA);
    }

    @Test
    public void endingRoundClearsDepartedScoresBeforeNextGame() throws Exception {
        Object player = client(1, 1);
        set(player, "points", 7);
        set(player, "eliminated", 5);
        remove(player, 1);
        server.performRealEnd = true;
        set(server, "keepListening", true);
        server.endGame();
        assertTrue(server.roundEnded.await(3, TimeUnit.SECONDS));
        assertNull(server.getScore((byte) 1));
        client(2, 1);
        assertEquals(0, server.getScore((byte) 1).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void failedReconnectKeepsQueuedEventsForTheNextConnection() throws Exception {
        Object player = client(1, 1);
        invoke(player, "close", new Class<?>[0]);
        queue(player, "first event");
        queue(player, "second event");
        invoke(player, "rejoin", new Class<?>[]{Socket.class}, new MemorySocket(2, new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Link lost"); }
        }));
        assertEquals(2, ((Queue<?>) get(player, "messageQueue")).size());

        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        invoke(player, "rejoin", new Class<?>[]{Socket.class}, new MemorySocket(3, delivered));
        DataInputStream messages = new DataInputStream(new ByteArrayInputStream(delivered.toByteArray()));
        assertEquals("first event", messages.readUTF());
        assertEquals("second event", messages.readUTF());
        assertEquals(0, messages.available());
        assertTrue(((Queue<?>) get(player, "messageQueue")).isEmpty());
    }

    @Test
    public void reconnectWithUnavailableOutputClosesPartialConnectionAndKeepsEvents() throws Exception {
        Object player = client(1, 1);
        invoke(player, "close", new Class<?>[0]);
        queue(player, "event");
        MemorySocket failedSocket = new MemorySocket(2, null);
        invoke(player, "rejoin", new Class<?>[]{Socket.class}, failedSocket);
        assertTrue(failedSocket.isClosed());
        assertNull(get(player, "clientSocket"));
        assertNull(get(player, "in"));
        assertFalse(server.getScore((byte) 1).isConnected);
        assertEquals(1, ((Queue<?>) get(player, "messageQueue")).size());
    }

    @Test
    public void disconnectedClientQueueKeepsRecentEventsWithinItsLimit() throws Exception {
        Object player = client(1, 1);
        invoke(player, "close", new Class<?>[0]);
        for (int index = 0; index < TcpServer.MAX_QUEUED_CLIENT_EVENTS + 3; index++)
            queue(player, "event " + index);

        Queue<?> pending = (Queue<?>) get(player, "messageQueue");
        assertEquals(TcpServer.MAX_QUEUED_CLIENT_EVENTS, pending.size());
        assertEquals("event 3", pending.peek());
    }

    @Test
    public void partialFrameDoesNotReadUnavailableBytesOrBlockOtherPlayers() throws Exception {
        Object partialSender = client(1, 1);
        Object victim = client(2, 2);
        client(3, 11);
        final int[] prematureReads = {0};
        InputStream fragment = new InputStream() {
            private boolean headerByteRead;
            @Override public int available() { return headerByteRead ? 0 : 1; }
            @Override public int read() throws IOException {
                if (headerByteRead) {
                    prematureReads[0]++;
                    throw new IOException("The rest of this frame has not arrived yet");
                }
                headerByteRead = true;
                return 0;
            }
        };
        set(partialSender, "in", new DataInputStream(fragment));
        eliminate(victim, 11);
        assertEquals(0, prematureReads[0]);
        assertEquals(1, server.getScore((byte) 11).points);
    }

    @Test
    public void writeFailureRemovesDisconnectedPlayerFromLobby() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object failed = client(1, 1);
        Object other = client(2, 0);
        failNextWrite(failed);
        queue(failed, "event");
        eliminate(other, 1);
        assertFalse(clients.containsKey(1));
        assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 1));
        assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 1));
    }

    @Test
    public void writeFailureDuringGameKeepsScoresAndAnnouncesDisconnect() throws Exception {
        Object failed = client(1, 1);
        Object other = client(2, 0);
        set(failed, "points", 7);
        set(failed, "eliminated", 3);
        int updatesBeforeFailure = server.playerUpdates;
        failNextWrite(failed);
        queue(failed, "event");
        eliminate(other, 1);
        assertSame(failed, clients.get(1));
        assertEquals(7, server.getScore((byte) 1).points);
        assertEquals(3, server.getScore((byte) 1).eliminated);
        assertFalse(server.getScore((byte) 1).isConnected);
        assertEquals(1, ((Queue<?>) get(failed, "messageQueue")).size());
        assertEquals(updatesBeforeFailure + 1, server.playerUpdates);
    }

    private void failNextWrite(Object client) throws Exception {
        set(client, "out", new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Link lost"); }
        }));
    }

    @Test
    public void unregisteredConnectionCannotStartGame() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object sender = client(1, 0);
        client(2, 11);
        processMessage(sender, NetMsg.NETMSG_STARTGAME);
        assertEquals(0, server.startRequests);
    }

    @Test
    public void unregisteredConnectionCannotEndGame() throws Exception {
        Object sender = client(1, 0);
        client(2, 11);
        processMessage(sender, NetMsg.NETMSG_ENDGAME);
        assertEquals(0, server.endRequests);
    }

    @Test
    public void unregisteredConnectionLeavingCannotEndGame() throws Exception {
        Object sender = client(1, 0);
        client(2, 11);
        processMessage(sender, NetMsg.NETMSG_LEAVE);
        assertFalse(clients.containsKey(1));
        assertEquals(0, server.endRequests);
    }

    @Test
    public void voluntaryQuitDoesNotEndTheRemainingFfaRound() throws Exception {
        Object quitter = client(1, 1);
        client(2, 11);

        processMessage(quitter, NetMsg.NETMSG_QUIT);

        assertFalse(clients.containsKey(1));
        assertEquals("A voluntary quit ended the remaining player's round", 0, server.endRequests);
        assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
    }

    @Test
    public void tournamentClientCannotEndTheHostRound() throws Exception {
        Globals.getInstance().mTournamentMode = true;
        Object clientRequestingEnd = client(1, 1);
        client(2, 11);

        processMessage(clientRequestingEnd, NetMsg.NETMSG_ENDGAME);

        assertFalse(clients.containsKey(1));
        assertEquals("A tournament client ended the host's round", 0, server.endRequests);
        assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
    }

    @Test
    public void tournamentClientLeaveCannotEndTheHostRound() throws Exception {
        Globals.getInstance().mTournamentMode = true;
        Object leavingClient = client(1, 1);
        client(2, 11);

        processMessage(leavingClient, NetMsg.NETMSG_LEAVE);

        assertFalse(clients.containsKey(1));
        assertEquals("A tournament client leave ended the host's round", 0, server.endRequests);
        assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
    }

    @Test
    public void registeredPlayerCanStillStartGameWhenAllowed() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object sender = client(1, 1);
        processMessage(sender, NetMsg.NETMSG_STARTGAME);
        assertEquals(1, server.startRequests);
    }

    @Test
    public void registeredPlayerCannotRestartRunningGame() throws Exception {
        Object sender = client(1, 1);
        processMessage(sender, NetMsg.NETMSG_STARTGAME);
        assertEquals(0, server.startRequests);
    }

    private void queue(Object client, String message) throws Exception {
        invoke(client, "sendTCPMessage", new Class<?>[]{String.class, boolean.class}, message, true);
    }

    private Object client(int connectionID, int playerID) throws Exception {
        return client(connectionID, playerID, new MemorySocket(connectionID));
    }

    private Object client(int connectionID, int playerID, Socket socket) throws Exception {
        Object client = innerInstance("ClientData");
        invoke(client, "initialize", new Class<?>[]{Socket.class, int.class},
                socket, connectionID);
        clients.put(connectionID, client);
        if (playerID != 0)
            register(client, playerID, false);
        return client;
    }

    private void register(Object client, int playerID, boolean rejoin) throws Exception {
        JSONObject hello = new JSONObject().put(TcpServer.JSON_PLAYERID, playerID)
                .put(TcpServer.JSON_PLAYERNAME, "Player " + playerID);
        if (rejoin) hello.put(TcpServer.JSON_REJOIN, true);
        parse(client, hello);
    }

    private void parse(Object client, JSONObject message) throws Exception {
        parse(client, message.toString());
    }

    private void parse(Object client, String message) throws Exception {
        Method parser = clientHandler.getClass().getDeclaredMethod("parsePlayerInfo", String.class, clientType);
        parser.setAccessible(true);
        TcpInputTestData.invokeParser(parser, clientHandler, message, client);
    }

    private void remove(Object client, int connectionID) throws Exception {
        invoke(clientHandler, "removeClient", new Class<?>[]{clientType, Integer.class, boolean.class},
                client, connectionID, true);
    }

    private void eliminate(Object victim, int attackerID) throws Exception {
        processMessage(victim, NetMsg.NETMSG_ELIMINATED + attackerID);
    }

    private void processMessage(Object sender, String message) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
                + message);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray()) {
            @Override
            public synchronized int read() {
                int result = super.read();
                if (available() == 0) server.stopTcpServer();
                return result;
            }

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                int result = super.read(buffer, offset, length);
                if (available() == 0) server.stopTcpServer();
                return result;
            }
        };
        set(sender, "in", new DataInputStream(input));
        set(server, "keepListening", true);
        // The final byte stops the loop after it processes this complete protocol frame.
        ((Runnable) clientHandler).run();
    }

    private Object innerInstance(String name) throws Exception {
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$" + name)
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(server);
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = (object instanceof TcpServer ? TcpServer.class : object.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = (object instanceof TcpServer ? TcpServer.class : object.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object invoke(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, arguments);
    }

    private static void clearPlayerMaps() {
        Globals globals = Globals.getInstance();
        Globals.getmTeamIPMapSemaphore();
        try { globals.mTeamIPMap.clear(); } finally { globals.mTeamIPMapSemaphore.release(); }
        Globals.getmIPTeamMapSemaphore();
        try { globals.mIPTeamMap.clear(); } finally { globals.mIPTeamMapSemaphore.release(); }
        Globals.getmTeamPlayerNameSemaphore();
        try { globals.mTeamPlayerNameMap.clear(); } finally { globals.mTeamPlayerNameSemaphore.release(); }
        Globals.getmGPSDataSemaphore();
        try { globals.mGPSData.clear(); } finally { globals.mGPSDataSemaphore.release(); }
    }

    private static boolean awaitQueued(Semaphore semaphore, long timeout) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!semaphore.hasQueuedThreads()
                && android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        return semaphore.hasQueuedThreads();
    }

    /** Pauses exactly one successful acquisition so a lock-order race can be reproduced. */
    private static final class PausingFirstAcquireSemaphore extends Semaphore {
        private final CountDownLatch firstAcquire = new CountDownLatch(1);
        private final CountDownLatch resumeFirstAcquire = new CountDownLatch(1);
        private boolean pauseNextAcquire = true;

        PausingFirstAcquireSemaphore() {
            super(1);
        }

        @Override
        public void acquire() throws InterruptedException {
            super.acquire();
            boolean pause;
            synchronized (this) {
                pause = pauseNextAcquire;
                pauseNextAcquire = false;
            }
            if (pause) {
                firstAcquire.countDown();
                resumeFirstAcquire.await();
            }
        }

        boolean awaitFirstAcquire(long timeout) throws InterruptedException {
            return firstAcquire.await(timeout, TimeUnit.MILLISECONDS);
        }

        void resumeFirstAcquire() {
            resumeFirstAcquire.countDown();
        }
    }

    private static final class RecordingServer extends TcpServer {
        int endRequests;
        int startRequests;
        int playerUpdates;
        boolean performRealEnd;
        final List<String> messages = new ArrayList<>();
        final CountDownLatch roundEnded = new CountDownLatch(1);

        @Override public void sendBroadcast(Intent intent) {
            if (NetMsg.NETMSG_ENDGAME.equals(intent.getAction()))
                roundEnded.countDown();
            if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(intent.getAction()))
                playerUpdates++;
        }
        @Override public void sendAllGameInfo(int playerID) { }
        @Override public void sendTCPMessageAll(String message) { messages.add(message); }
        @Override public boolean startGame() {
            startRequests++;
            return true;
        }
        @Override public void endGame() {
            endRequests++;
            if (performRealEnd) super.endGame();
        }
    }

    private static final class MemorySocket extends Socket {
        private final InetAddress address;
        private final OutputStream output;
        private boolean closed;

        MemorySocket(int id) throws IOException {
            this(id, new ByteArrayOutputStream());
        }

        MemorySocket(int id, OutputStream output) throws IOException {
            address = InetAddress.getByAddress(new byte[]{127, 0, 0, (byte) id});
            this.output = output;
        }

        @Override public InetAddress getInetAddress() { return address; }
        @Override public boolean isClosed() { return closed; }
        @Override public synchronized void close() { closed = true; }
        @Override public InputStream getInputStream() throws IOException {
            if (closed) throw new IOException("Socket closed");
            return new ByteArrayInputStream(new byte[0]);
        }
        @Override public OutputStream getOutputStream() throws IOException {
            if (closed || output == null) throw new IOException("Socket output unavailable");
            return output;
        }
    }
}
