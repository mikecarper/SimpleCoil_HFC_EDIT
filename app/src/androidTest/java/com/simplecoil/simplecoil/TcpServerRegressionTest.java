package com.simplecoil.simplecoil;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> server = new RecordingServer());
        server.setDedicated(true);
        clients = new ConcurrentHashMap<>();
        set(server, "mClientData", clients);
        clientType = Class.forName(TcpServer.class.getName() + "$ClientData");
        clientHandler = innerInstance("ClientThread");
        Globals globals = Globals.getInstance();
        globals.mUseGPS = false;
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameMode = Globals.GAME_MODE_FFA;
        globals.mGameLimit = Globals.GAME_LIMIT_NONE;
        globals.mOnlyServerSettings = false;
        clearPlayerMaps();
    }

    @After
    public void tearDown() throws Exception {
        server.stopTcpServer();
        for (Object client : clients.values())
            invoke(client, "close", new Class<?>[0]);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
        clearPlayerMaps();
    }

    @Test
    public void repeatedRegistrationKeepsTheRegisteredConnection() throws Exception {
        assertRepeatedRegistration(false);
    }

    @Test
    public void repeatedRejoinKeepsTheRegisteredConnection() throws Exception {
        assertRepeatedRegistration(true);
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
        client(2, 9);
        eliminate(sender, 9);
        assertEquals(0, server.getScore((byte) 9).points);
        assertEquals(0, get(sender, "eliminated"));
    }

    @Test
    public void eliminationReportsInLobbyDoNotChangeScores() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Object victim = client(1, 1);
        client(2, 9);
        eliminate(victim, 9);
        assertEquals(0, server.getScore((byte) 9).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void changingToFourTeamsRecognizesNewEnemies() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Object victim = client(1, 1);
        client(2, 5);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        eliminate(victim, 5);
        assertEquals(1, server.getScore((byte) 5).points);
        assertEquals(1, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void mergingTeamsRejectsPreviouslyHostileFriendlyFire() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        Object victim = client(1, 1);
        client(2, 5);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        eliminate(victim, 5);
        assertEquals(0, server.getScore((byte) 5).points);
        assertEquals(0, server.getScore((byte) 1).eliminated);
    }

    @Test
    public void gpsTeamReflectsTheCurrentGameMode() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Object player = client(1, 5);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        parse(player, new JSONObject().put(TcpServer.JSON_GPSLONGITUDE, 10.0)
                .put(TcpServer.JSON_GPSLATITUDE, 20.0));
        assertEquals(2, Globals.getInstance().mGPSData.get((byte) 5).team);
    }

    @Test
    public void serverEnforcesIndividualScoreLimitWithoutClientRequest() throws Exception {
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 1;
        Globals.getInstance().mOnlyServerSettings = true;
        Object victim = client(1, 1);
        client(2, 9);
        eliminate(victim, 9);
        assertEquals(1, server.endRequests);
    }

    @Test
    public void serverEnforcesCombinedTeamScoreLimit() throws Exception {
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_SCORE;
        Globals.getInstance().mScoreLimit = 3;
        Object attacker = client(1, 1);
        Object teammate = client(2, 2);
        Object victim = client(3, 9);
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
        Object victim = client(2, 9);
        set(victim, "points", 20);
        eliminate(victim, 1);
        assertEquals(0, server.endRequests);
    }

    @Test
    public void disabledScoreLimitDoesNotEndRound() throws Exception {
        Globals.getInstance().mScoreLimit = 1;
        Object victim = client(1, 1);
        client(2, 9);
        eliminate(victim, 9);
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
        Object victim = client(3, 9);
        set(teammate, "points", 2);
        remove(teammate, 2);
        eliminate(victim, 1);
        assertEquals(1, server.endRequests);
    }

    @Test
    public void endingRoundClearsDepartedScoresBeforeNextGame() throws Exception {
        Object player = client(1, 1);
        set(player, "points", 7);
        set(player, "eliminated", 5);
        remove(player, 1);
        server.performRealEnd = true;
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
    public void partialFrameDoesNotReadUnavailableBytesOrBlockOtherPlayers() throws Exception {
        Object partialSender = client(1, 1);
        Object victim = client(2, 2);
        client(3, 9);
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
        eliminate(victim, 9);
        assertEquals(0, prematureReads[0]);
        assertEquals(1, server.getScore((byte) 9).points);
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
        client(2, 9);
        processMessage(sender, NetMsg.NETMSG_STARTGAME);
        assertEquals(0, server.startRequests);
    }

    @Test
    public void unregisteredConnectionCannotEndGame() throws Exception {
        Object sender = client(1, 0);
        client(2, 9);
        processMessage(sender, NetMsg.NETMSG_ENDGAME);
        assertEquals(0, server.endRequests);
    }

    @Test
    public void unregisteredConnectionLeavingCannotEndGame() throws Exception {
        Object sender = client(1, 0);
        client(2, 9);
        processMessage(sender, NetMsg.NETMSG_LEAVE);
        assertFalse(clients.containsKey(1));
        assertEquals(0, server.endRequests);
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
        Object client = innerInstance("ClientData");
        invoke(client, "initialize", new Class<?>[]{Socket.class, int.class},
                new MemorySocket(connectionID), connectionID);
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
        invoke(clientHandler, "parsePlayerInfo", new Class<?>[]{String.class, clientType}, message.toString(), client);
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

    private static final class RecordingServer extends TcpServer {
        int endRequests;
        int startRequests;
        int playerUpdates;
        boolean performRealEnd;
        final CountDownLatch roundEnded = new CountDownLatch(1);

        @Override public void sendBroadcast(Intent intent) {
            if (NetMsg.NETMSG_ENDGAME.equals(intent.getAction()))
                roundEnded.countDown();
            if (NetMsg.NETMSG_PLAYERDATAUPDATE.equals(intent.getAction()))
                playerUpdates++;
        }
        @Override public void sendAllGameInfo(int playerID) { }
        @Override public void sendTCPMessageAll(String message) { }
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
