package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.simplecoil.protocol.GameStartCountdown;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Uses real server tasks and controlled streams to reproduce shutdown races. */
@RunWith(AndroidJUnit4.class)
public class TcpServerDispatchRegressionTest {
    private RecordingServer server;
    private WaitingSemaphore clientsLock;
    private Map<Integer, Object> clients;
    private final List<MemorySocket> sockets = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
    private Map<Byte, InetAddress> originalAddresses;
    private Map<InetAddress, Byte> originalPlayers;
    private Map<Byte, String> originalNames;
    private Map<Byte, Globals.GPSData> originalLocations;
    private int[] originalPairings;
    private int originalGameMode;
    private int originalGameState;
    private int originalGameLimit;
    private boolean originalUseGPS;
    private long originalEndVotes;
    private boolean originalEndVoteRequested;
    private com.simplecoil.protocol.RoundRoster originalRoundRoster;
    private byte originalPlayerID;
    private boolean originalLocalLobbyReady;
    private boolean originalBalanced;
    private boolean originalBalancedQr;
    private boolean originalBalancedPrepared;
    private Map<Byte, Integer> originalBalancedTeams;
    private Set<Byte> originalBalancedCheckIns;
    private Map<String, Integer> originalRetainedBalancedTeams;

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        originalBalanced = globals.mBalancedRandom;
        originalBalancedQr = globals.mBalancedRequireQr;
        originalBalancedPrepared = globals.mBalancedGamePrepared;
        originalBalancedTeams = globals.mBalancedTeams;
        originalBalancedCheckIns = globals.mBalancedCheckedIn;
        originalRetainedBalancedTeams = globals.mRetainedBalancedTeams;
        globals.mBalancedRandom = false;
        globals.clearBalancedAssignments();
        originalRoundRoster = globals.mRoundRoster;
        originalPlayerID = globals.mPlayerID;
        originalLocalLobbyReady = globals.mLocalLobbyReady;
        globals.mPlayerID = 0;
        globals.mLocalLobbyReady = false;
        globals.mRoundRoster = null;
        originalGameMode = globals.mGameMode;
        originalGameState = globals.mGameState;
        originalGameLimit = globals.mGameLimit;
        originalUseGPS = globals.mUseGPS;
        originalEndVotes = globals.mEndGameVotes;
        originalEndVoteRequested = globals.mEndGameVoteRequested;
        globals.mEndGameVotes = 0;
        globals.mEndGameVoteRequested = false;
        originalAddresses = copyAndClear(globals.mTeamIPMap, globals.mTeamIPMapSemaphore);
        originalPlayers = copyAndClear(globals.mIPTeamMap, globals.mIPTeamMapSemaphore);
        originalNames = copyAndClear(globals.mTeamPlayerNameMap, globals.mTeamPlayerNameSemaphore);
        originalLocations = copyAndClear(globals.mGPSData, globals.mGPSDataSemaphore);
        originalPairings = globals.mGrenadePairings.clone();
        Globals.ClearGrenadePairings(true);
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mGameLimit = Globals.GAME_LIMIT_NONE;
        globals.mUseGPS = false;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> server = new RecordingServer());
        server.setDedicated(true);
        clientsLock = new WaitingSemaphore();
        clients = new ConcurrentHashMap<>();
        set(server, "mClientDataSemaphore", clientsLock);
        set(server, "mClientData", clients);
        set(server, "keepListening", true);
        addClient(1, new MemorySocket());
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stopTcpServer();
        for (MemorySocket socket : sockets) socket.close();
        for (Thread worker : workers) {
            worker.interrupt();
            worker.join(2000);
        }
        if (server != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::onDestroy);
        Globals globals = Globals.getInstance();
        globals.mRoundRoster = originalRoundRoster;
        globals.mPlayerID = originalPlayerID;
        globals.mLocalLobbyReady = originalLocalLobbyReady;
        globals.mBalancedRandom = originalBalanced;
        globals.mBalancedRequireQr = originalBalancedQr;
        globals.mBalancedGamePrepared = originalBalancedPrepared;
        globals.setBalancedAssignments(originalBalancedTeams, originalBalancedCheckIns);
        globals.setRetainedBalancedTeams(originalRetainedBalancedTeams);
        restore(globals.mTeamIPMap, originalAddresses, globals.mTeamIPMapSemaphore);
        restore(globals.mIPTeamMap, originalPlayers, globals.mIPTeamMapSemaphore);
        restore(globals.mTeamPlayerNameMap, originalNames, globals.mTeamPlayerNameSemaphore);
        restore(globals.mGPSData, originalLocations, globals.mGPSDataSemaphore);
        System.arraycopy(originalPairings, 0, globals.mGrenadePairings, 0, originalPairings.length);
        globals.mGameMode = originalGameMode;
        globals.mGameState = originalGameState;
        globals.mGameLimit = originalGameLimit;
        globals.mUseGPS = originalUseGPS;
        globals.mEndGameVotes = originalEndVotes;
        globals.mEndGameVoteRequested = originalEndVoteRequested;
        for (Thread worker : workers) assertFalse("Dispatch task survived cleanup", worker.isAlive());
        assertTrue("Dispatch task crashed: " + failures, failures.isEmpty());
    }

    @Test
    public void teamQrAdmitsNewPlayerIntoRunningRoundWithoutChangingDeadline() throws Exception {
        String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        Globals globals = Globals.getInstance();
        globals.mRoundRoster = new com.simplecoil.protocol.RoundRoster(token);
        globals.mRoundRoster.restore(new com.simplecoil.protocol.RoundRoster.Member(
                java.util.UUID.randomUUID().toString(), 1, 1, 0));
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        set(server, "mStartAnnounced", true);
        set(server, "mRoundToken", token);
        set(server, "mRoundSequence", 5L);
        long startAt = SystemClock.elapsedRealtime() - 1000;
        set(server, "mScheduledStart", startAt);
        MemorySocket joining = new MemorySocket() {
            @Override public InetAddress getInetAddress() {
                try { return InetAddress.getByName("127.0.0.17"); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
        addClient(17, 0, joining);
        parseClock(17, new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAME, "Newcomer")
                .put(LiveJoin.IDENTITY, java.util.UUID.randomUUID().toString()).put(LiveJoin.TEAM, 2));
        for (Thread task : captureClientTasks()) task.join(2000);
        JSONObject assignment = readJson(joining);
        assertEquals(17, assignment.getInt(LiveJoin.ASSIGNED));
        assertEquals(token, assignment.getString(TcpServer.JSON_ROUND_TOKEN));
        assertEquals(startAt, server.getScheduledGameStart().getLongExtra(NetMsg.INTENT_START_AT, 0));
        assertEquals(1, globals.mRoundRoster.generation(17));
        assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
    }

    @Test
    public void oversizedRenameCannotDisconnectTheLobbyDuringRosterBroadcast() throws Exception {
        addClient(2, new MemorySocket());
        JSONObject rename = new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAMECHANGE, TcpInputTestData.repeat('a', 65400));
        TcpInputTestData.assertFitsFrame(rename.toString());
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(server);
        Method parser = handler.getClass().getDeclaredMethod("parsePlayerInfo", String.class, clients.get(1).getClass());
        parser.setAccessible(true);
        List<Thread> senders;
        clientsLock.acquire();
        try {
            TcpInputTestData.invokeParser(parser, handler, rename.toString(), clients.get(1));
            server.sendAllGameInfo(TcpServer.SEND_ALL);
            senders = captureClientTasks();
        } finally { clientsLock.release(); }
        for (Thread sender : senders) {
            sender.join(2000);
            assertFalse("Roster sender did not finish", sender.isAlive());
        }
        awaitOutboundWrites();
        for (MemorySocket socket : sockets) {
            assertFalse("An oversized name broke another player's TCP connection", socket.closed);
            String frame = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray())).readUTF();
            JSONObject roster = new JSONObject(frame.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                    + TcpServer.TCPPREFIX_JSON.length()));
            assertEquals(2, roster.getJSONArray(TcpServer.JSON_PLAYERS).length());
        }
        assertEquals("Player 1", Globals.getInstance().getPlayerName((byte) 1));
        assertTrue(server.events.isEmpty());
    }

    @Test
    public void maximumLengthNamesKeepAFullRosterWithinTheWireFrame() throws Exception {
        for (int id = 2; id <= Globals.MAX_PLAYER_ID; id++) addClient(id, new MemorySocket());
        // Each control character expands to six ASCII bytes in JSON.
        String name = TcpInputTestData.repeat((char) 1, 20);
        for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++)
            Globals.getInstance().mTeamPlayerNameMap.put(id, name);
        dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
        for (MemorySocket socket : sockets) {
            assertFalse(socket.closed);
            String frame = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray())).readUTF();
            JSONObject roster = TcpJson.parseObject(frame.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                    + TcpServer.TCPPREFIX_JSON.length()));
            JSONArray players = roster.getJSONArray(TcpServer.JSON_PLAYERS);
            assertEquals(Globals.MAX_PLAYER_ID, players.length());
            for (int i = 0; i < players.length(); i++)
                assertEquals(name, TcpJson.getPlayerName(players.getJSONObject(i), TcpServer.JSON_PLAYERNAME));
        }
    }

    @Test
    public void rosterBroadcastIncludesHostRespawnMode() throws Exception {
        Globals globals = Globals.getInstance();
        boolean previous = globals.mRespawnQrEnabled;
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                globals.mRespawnQrEnabled = enabled;
                sockets.get(0).bytes.reset();
                dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
                assertEquals(enabled, readJson(sockets.get(0)).getBoolean(TcpServer.JSON_RESPAWN_QR_ENABLED));
            }
        } finally {
            globals.mRespawnQrEnabled = previous;
        }
    }

    @Test
    public void interruptedBroadcastDoesNotWriteOrReleaseAnUnacquiredPermit() throws Exception {
        assertInterruptedSend(() -> server.sendTCPMessageAll("cancelled"));
    }

    @Test
    public void interruptedPlayerSendDoesNotWriteOrReleaseAnUnacquiredPermit() throws Exception {
        assertInterruptedSend(() -> sendPlayer(true));
    }

    @Test
    public void interruptedTeamSendDoesNotWriteOrReleaseAnUnacquiredPermit() throws Exception {
        assertInterruptedSend(() -> sendTeam(true, true));
    }

    @Test
    public void interruptedRosterSendDoesNotWriteOrReleaseAnUnacquiredPermit() throws Exception {
        assertInterruptedSend(() -> server.sendAllGameInfo(1));
    }

    @Test
    public void interruptedStartDoesNotStartTheGameOrReleaseAnUnacquiredPermit() throws Exception {
        assertInterruptedSend(() -> assertTrue(server.startGame()));
        assertTrue(server.events.isEmpty());
    }

    @Test
    public void interruptedEndDoesNotEraseRoundStateOrPublishAnEndEvent() throws Exception {
        Globals.GPSData location = new Globals.GPSData();
        Globals.getInstance().mGPSData.put((byte) 1, location);
        Globals.getInstance().mGrenadePairings[1] = 1;
        clientsLock.acquire();
        try {
            server.endGame();
            Thread worker = queuedWorker();
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertSame(location, Globals.getInstance().mGPSData.get((byte) 1));
            assertEquals("Player 1", Globals.getInstance().mTeamPlayerNameMap.get((byte) 1));
            assertEquals(1, Globals.getInstance().mGrenadePairings[1]);
            assertTrue(server.events.isEmpty());
            assertEquals(0, clientsLock.availablePermits());
        } finally { clientsLock.release(); }
    }

    @Test
    public void stoppingCancelsQueuedBroadcastBeforeTheClientLockIsReleased() throws Exception {
        assertStopCancelsQueuedTask(() -> server.sendTCPMessageAll("old session"));
    }

    @Test
    public void stoppingCancelsQueuedStartBeforeItCanPublishAnEvent() throws Exception {
        assertStopCancelsQueuedTask(() -> assertTrue(server.startGame()));
    }

    @Test
    public void stoppedServerRejectsNewStartRequests() {
        server.stopTcpServer();
        assertFalse("Stopped service accepted a game start", server.startGame());
    }

    @Test
    public void hostCancellationSupersedesQueuedWorkAndCoalescesRepeatedRequests() throws Exception {
        clientsLock.acquire();
        List<Thread> tasks;
        try {
            assertTrue(server.startGame());
            server.sendTCPMessageAll("obsolete message");
            server.cancelServer();
            tasks = captureClientTasks();
            assertEquals(3, tasks.size());
            server.cancelServer();
            server.sendTCPMessageAll("too late");
            server.endGame();
            assertFalse("A closing host accepted a new start", server.startGame());
            assertEquals("Cancellation queued duplicate or late work", 3, captureClientTasks().size());
        } finally { clientsLock.release(); }
        for (Thread task : tasks) {
            task.join(2000);
            assertFalse(task.isAlive());
        }
        assertCancellationFrame(sockets.get(0));
        assertTrue(sockets.get(0).closed);
        assertTrue("A closing host published a round event", server.events.isEmpty());
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void hostCancellationIncludesRegistrationsCompletedWhileItWaited() throws Exception {
        addClient(2, 0, new MemorySocket());
        dispatchThenChange(server::cancelServer, () -> {
            // Simulate an in-flight registration and another accepted connection.
            set(clients.get(2), "mPlayerID", (byte) 2);
            addClient(3, 0, new MemorySocket());
        });
        for (MemorySocket socket : sockets) {
            assertCancellationFrame(socket);
            assertTrue(socket.closed);
        }
    }

    @Test
    public void hostCancellationTimesOutWithoutReleasingAnUnacquiredClientLock() throws Exception {
        clientsLock.acquire();
        try {
            server.cancelServer();
            Thread worker = queuedWorker();
            worker.join(2500);
            assertFalse("Cancellation waited indefinitely for the client lock", worker.isAlive());
            assertTrue("Timed-out cancellation left the socket open", sockets.get(0).closed);
            assertEquals(0, sockets.get(0).bytes.size());
            assertEquals("Cancellation released another thread's client lock", 0, clientsLock.availablePermits());
        } finally { clientsLock.release(); }
    }

    @Test
    public void hostCancellationClosesAStalledWriterWithinItsDeadline() throws Exception {
        BlockingSocket blocked = new BlockingSocket();
        addClient(1, blocked);
        server.cancelServer();
        assertTrue(blocked.writing.await(2, TimeUnit.SECONDS));
        Thread worker = blocked.writer;
        workers.add(worker);
        worker.join(2500);
        assertFalse("Cancellation stayed blocked in a socket write", worker.isAlive());
        assertTrue(blocked.closed);
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void hostCancellationAlsoClearsAStoppedPeerHostsCountdown() throws Exception {
        // The local phone host is a distinct, gun-ready participant; client1
        // remains the remote player whose start frame this fixture inspects.
        Globals.getInstance().mPlayerID = 2;
        Globals.getInstance().mLocalLobbyReady = true;
        server.setDedicated(false);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(server.getScheduledGameStart() != null);
        clientsLock.acquire();
        Thread cancellation;
        try {
            server.cancelServer();
            assertTrue("A cancelled peer host retained its countdown", server.getScheduledGameStart() == null);
            cancellation = queuedWorker();
        } finally { clientsLock.release(); }
        cancellation.join(2000);
        assertFalse("Peer-host cancellation did not finish", cancellation.isAlive());
        assertTrue(sockets.get(0).closed);
    }

    private void assertCancellationFrame(MemorySocket socket) throws Exception {
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray()));
        assertEquals(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL,
                frames.readUTF());
        assertEquals("Cancellation must be the final and only frame", 0, frames.available());
    }

    @Test
    public void destroyingAnEndTaskWaitingForGpsDoesNotEraseReplacementState() throws Exception {
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        clientsLock.acquire();
        locations.acquire();
        Thread worker = null;
        boolean clientLockHeld = true;
        Globals.GPSData replacement = new Globals.GPSData();
        try {
            server.endGame();
            worker = queuedWorker();
            clientsLock.release();
            clientLockHeld = false;
            assertQueued(locations);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::onDestroy);
            Globals.getInstance().mGPSData.put((byte) 11, replacement);
            Globals.getInstance().mGrenadePairings[1] = 11;
        } finally {
            if (clientLockHeld) clientsLock.release();
            locations.release();
            if (worker != null) worker.join(1000);
        }
        assertFalse(worker.isAlive());
        assertSame("Old end task erased a replacement round's GPS", replacement,
                Globals.getInstance().mGPSData.get((byte) 11));
        assertEquals(11, Globals.getInstance().mGrenadePairings[1]);
        assertTrue(server.events.isEmpty());
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void endingGameKeepsRegistrationsLockedUntilConnectedLobbyIsRestored() throws Exception {
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        clientsLock.acquire();
        locations.acquire();
        Thread worker = null;
        boolean clientLockHeld = true;
        try {
            server.endGame();
            worker = queuedWorker();
            clientsLock.release();
            clientLockHeld = false;
            assertQueued(locations);
            assertEquals("New registrations overtook unfinished round cleanup", 0,
                    clientsLock.availablePermits());
        } finally {
            if (clientLockHeld) clientsLock.release();
            locations.release();
            if (worker != null) worker.join(1000);
        }
        assertEquals(1, clients.size());
        assertEquals("Player 1", Globals.getInstance().mTeamPlayerNameMap.get((byte) 1));
        assertFalse("Ordinary round end closed the lobby connection", sockets.get(0).closed);
        assertTrue(server.arePlayerClocksSynchronized());
        assertEquals(1, clientsLock.availablePermits());
        assertTrue(server.events.contains(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void asyncPlayerSendCannotBorrowItsCallersClientLock() throws Exception {
        assertSendWaitsForClientLock(() -> sendPlayer(false));
    }

    @Test
    public void asyncTeamSendCannotBorrowItsCallersClientLock() throws Exception {
        assertSendWaitsForClientLock(() -> sendTeam(false, true));
    }

    @Test
    public void stoppingClosesASocketWhoseWriteIgnoresThreadInterruption() throws Exception {
        BlockingSocket blocked = new BlockingSocket();
        addClient(1, blocked);
        server.sendTCPMessageAll("blocked write");
        assertTrue(blocked.writing.await(2, TimeUnit.SECONDS));
        Thread worker = blocked.writer;
        workers.add(worker);
        Thread stopping = new Thread(server::stopTcpServer);
        try {
            stopping.start();
            stopping.join(1000);
            assertFalse("Stopping waited for the blocked writer's monitor", stopping.isAlive());
            worker.join(1000);
            assertTrue("Stopping did not close the blocked socket", blocked.closed);
            assertFalse("Socket writer survived server stop", worker.isAlive());
        } finally {
            blocked.close();
            stopping.join(2000);
            worker.join(2000);
        }
    }

    @Test
    public void scoreSnapshotDoesNotWaitForAStalledNetworkWrite() throws Exception {
        AtomicReference<TcpServer.ScoreData> captured = new AtomicReference<>();
        assertDoesNotWaitForWriter(() -> captured.set(server.getScore((byte) 1)));
        assertEquals(7, captured.get().points);
        assertEquals(2, captured.get().eliminated);
        assertTrue(captured.get().isConnected);
        TcpServer.ScoreData disconnected = server.getScore((byte) 1);
        assertEquals(7, disconnected.points);
        assertEquals(2, disconnected.eliminated);
        assertFalse(disconnected.isConnected);
    }

    @Test
    public void preparingAPersonalizedRosterDoesNotWaitForAStalledWrite() throws Exception {
        assertDoesNotWaitForWriter(() -> server.sendAllGameInfo(1));
    }

    private void assertDoesNotWaitForWriter(CheckedAction action) throws Exception {
        BlockingSocket blocked = new BlockingSocket();
        addClient(1, blocked);
        set(clients.get(1), "points", 7);
        set(clients.get(1), "eliminated", 2);
        server.sendTCPMessageAll("blocked write");
        assertTrue(blocked.writing.await(2, TimeUnit.SECONDS));
        workers.add(blocked.writer);
        CountDownLatch read = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try { action.run(); }
            catch (Throwable failure) { failures.add(failure); }
            finally { read.countDown(); }
        });
        workers.add(reader);
        try {
            reader.start();
            assertTrue("A UI-readable score operation waited for socket I/O", read.await(1, TimeUnit.SECONDS));
        } finally {
            blocked.close();
            reader.join(1000);
            blocked.writer.join(1000);
            captureClientTasks();
        }
        assertFalse(reader.isAlive());
        assertFalse(blocked.writer.isAlive());
    }

    @Test
    public void repeatedEndRequestsOnlyQueueOneCleanup() throws Exception {
        clientsLock.acquire();
        List<Thread> tasks;
        try {
            server.endGame();
            queuedWorker();
            server.endGame();
            tasks = captureClientTasks();
            assertEquals("Repeated request scheduled a second destructive cleanup", 1, tasks.size());
        } finally { clientsLock.release(); }
        for (Thread task : tasks) task.join(1000);
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void repeatedStartRequestsOnlyQueueOneSend() throws Exception {
        clientsLock.acquire();
        List<Thread> tasks;
        try {
            assertTrue(server.startGame());
            queuedWorker();
            assertTrue(server.startGame());
            assertTrue(server.startGame());
            tasks = captureClientTasks();
            assertEquals("Repeated starts queued duplicate network sends", 1, tasks.size());
        } finally { clientsLock.release(); }
        for (Thread task : tasks) {
            task.join(1000);
            assertFalse(task.isAlive());
        }
        awaitOutboundWrites();
        DataInputStream messages = new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray()));
        assertStartFrame(messages.readUTF());
        assertEquals("A player received multiple starts for one round", 0, messages.available());
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void benchedUnreadyPhoneGetsWaitInsteadOfCountdownAndIsNotACombatPeer() throws Exception {
        MemorySocket bench = new MemorySocket();
        addClient(2, bench);
        set(clients.get(2), "lobbyBenched", true);
        set(clients.get(2), "lobbyReady", false);
        set(clients.get(2), "clockSynchronized", false);
        Map<Byte, LobbyPlayer> oldLobby = Globals.getInstance().mLobbyPlayers;
        try {
            assertTrue(server.areLobbyPlayersReady());
            assertTrue(server.arePlayerClocksSynchronized());
            dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
            assertStartFrame(new DataInputStream(new ByteArrayInputStream(
                    sockets.get(0).bytes.toByteArray())).readUTF());
            JSONObject wait = readJson(bench);
            assertTrue(wait.getBoolean(TcpServer.JSON_LOBBY_WAIT));
            assertFalse(wait.has(TcpServer.JSON_GAMESTART));
            assertFalse(Globals.getInstance().mTeamIPMap.containsKey((byte) 2));
            assertFalse(Globals.getInstance().mIPTeamMap.containsValue((byte) 2));
            assertTrue(Globals.getInstance().mLobbyPlayers.get((byte) 2).benched);
        } finally { Globals.getInstance().mLobbyPlayers = oldLobby; }
    }

    @Test
    public void interruptedStartReleasesItsReservationForRetry() throws Exception {
        assertInterruptedSend(() -> assertTrue(server.startGame()));
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void confirmedStartIsNotSentAgainBeforeTheHostProcessesItsBroadcast() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        int bytesSent = sockets.get(0).bytes.size();
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        clientsLock.acquire();
        try {
            assertTrue(server.startGame());
            assertTrue("The pending UI confirmation allowed a duplicate start", captureClientTasks().isEmpty());
        } finally { clientsLock.release(); }
        assertEquals(bytesSent, sockets.get(0).bytes.size());
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void cancelledCountdownAllowsImmediateRestartAfterCleanup() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        dispatchThenChange(server::endGame, () -> { });
        MemorySocket nextRound = new MemorySocket();
        addClient(1, nextRound);
        assertEquals(0, server.getNextGameStartWaitMillis());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(nextRound.bytes.size() > 0);
        assertEquals(2, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void shortRoundRestartsImmediatelyButLongRoundKeepsCooldown() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 59_000);
        dispatchThenChange(server::endGame, () -> { });
        assertEquals(0, server.getNextGameStartWaitMillis());
        addClient(1, new MemorySocket());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 60_000);
        dispatchThenChange(server::endGame, () -> { });
        assertTrue(server.getNextGameStartWaitMillis() > 29_000);
        addClient(1, new MemorySocket());
        assertFalse("A full round must keep its cooldown", server.startGame());
    }

    @Test
    public void delayedEndCleanupCannotTurnQuickRestartIntoThirtySecondWait() throws Exception {
        set(server, "mStartAnnounced", true);
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 59_000);
        Method record = TcpServer.class.getDeclaredMethod("recordRoundCooldownLocked");
        record.setAccessible(true);
        record.invoke(server);
        assertEquals(0, server.getNextGameStartWaitMillis());
        // Model cleanup arriving after the same round crosses the threshold.
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 60_000);
        server.clearScheduledStart();
        assertEquals(0, server.getNextGameStartWaitMillis());
    }

    @Test
    public void laptopHandoffCannotCancelAQueuedOrAnnouncedStart() throws Exception {
        Field listening = TcpServer.class.getDeclaredField("keepListening");
        listening.setAccessible(true);
        clientsLock.acquire();
        Thread start;
        try {
            assertTrue(server.startGame());
            start = queuedWorker();
            assertTrue(server.hasPendingOrAnnouncedStart());
            assertFalse(server.stopIdleLobbyForHandoff());
            assertTrue(listening.getBoolean(server));
        } finally { clientsLock.release(); }
        start.join(1000);
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertTrue(server.hasPendingOrAnnouncedStart());
        assertFalse(server.stopIdleLobbyForHandoff());
        dispatchThenChange(server::endGame, () -> { });
        assertFalse(server.hasPendingOrAnnouncedStart());
        assertTrue(server.stopIdleLobbyForHandoff());
        assertFalse(listening.getBoolean(server));
    }

    @Test
    public void failedStartWriteReleasesItsReservationForRetry() throws Exception {
        set(clients.get(1), "out", new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        }));
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(server.events.isEmpty());
        MemorySocket replacement = new MemorySocket();
        addClient(1, replacement);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
        assertTrue(replacement.bytes.size() > 0);
    }

    @Test
    public void activeRoundRejectsAnotherStartRequest() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        clientsLock.acquire();
        try {
            boolean accepted = server.startGame();
            captureClientTasks();
            assertFalse("A running round accepted another start", accepted);
        } finally { clientsLock.release(); }
    }

    @Test
    public void queuedStartCannotRestartARoundThatBecameActive() throws Exception {
        assertQueuedStartDoesNotRestart(Globals.GAME_STATE_RUNNING);
    }

    @Test
    public void queuedStartCannotRestartARoundWhileTheHostIsSpawning() throws Exception {
        assertQueuedStartDoesNotRestart(Globals.GAME_STATE_ELIMINATED);
    }

    private void assertQueuedStartDoesNotRestart(int state) throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> Globals.getInstance().mGameState = state);
        assertEquals("An obsolete start command was sent to players", 0, sockets.get(0).bytes.size());
        assertTrue("An obsolete start was confirmed to the host", server.events.isEmpty());
        assertEquals(state, Globals.getInstance().mGameState);
    }

    @Test
    public void endRequestDuringSharedStateCleanupDoesNotQueueAnotherEnd() throws Exception {
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        clientsLock.acquire();
        locations.acquire();
        Thread worker = null;
        boolean clientLockHeld = true;
        try {
            server.endGame();
            worker = queuedWorker();
            clientsLock.release();
            clientLockHeld = false;
            assertQueued(locations);
            server.endGame();
            assertEquals(1, captureClientTasks().size());
        } finally {
            if (clientLockHeld) clientsLock.release();
            locations.release();
            if (worker != null) worker.join(1000);
        }
        assertFalse(worker.isAlive());
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void startIsRejectedWhileRoundCleanupIsQueued() throws Exception {
        clientsLock.acquire();
        try {
            server.endGame();
            queuedWorker();
            boolean accepted = server.startGame();
            captureClientTasks();
            assertFalse("A new start overtook pending round cleanup", accepted);
        } finally { clientsLock.release(); }
    }

    @Test
    public void dedicatedEndNeedsTwoDistinctCurrentUnbenchedApprovals() throws Exception {
        addClient(2, new MemorySocket());
        addClient(32, new MemorySocket());
        set(clients.get(32), "lobbyBenched", true);
        String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        set(server, "mRoundToken", token);
        set(server, "mStartAnnounced", true);
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        Method vote = TcpServer.class.getDeclaredMethod("updateEndGameVote",
                clients.get(1).getClass(), String.class, boolean.class);
        vote.setAccessible(true);
        vote.invoke(server, null, null, false);
        assertEquals(0L, Globals.getInstance().mEndGameVotes);
        vote.invoke(server, clients.get(1), token, true);
        vote.invoke(server, clients.get(1), token, true);
        vote.invoke(server, clients.get(32), token, true);
        vote.invoke(server, clients.get(2), "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", true);
        assertEquals(1L, Globals.getInstance().mEndGameVotes);
        assertFalse(server.events.contains(NetMsg.NETMSG_ENDGAME));
        vote.invoke(server, clients.get(1), token, false);
        assertEquals(0L, Globals.getInstance().mEndGameVotes);
        vote.invoke(server, clients.get(1), token, true);
        vote.invoke(server, clients.get(2), token, true);
        for (Thread worker : captureClientTasks()) worker.join(2000);
        assertTrue(server.events.contains(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void queuedStartIsCancelledWhenAnEndIsRequestedBeforeDelivery() throws Exception {
        clientsLock.acquire();
        List<Thread> tasks;
        try {
            assertTrue(server.startGame());
            queuedWorker();
            server.endGame();
            tasks = captureClientTasks();
        } finally { clientsLock.release(); }
        for (Thread task : tasks) task.join(1000);
        assertFalse("A cancelled round was announced as started", server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertTrue(server.events.contains(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void interruptedEndReleasesTheRoundCleanupReservation() throws Exception {
        // A real long-running round records its wait even if cleanup is interrupted.
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 60_000);
        clientsLock.acquire();
        Thread start;
        try {
            server.endGame();
            Thread end = queuedWorker();
            end.interrupt();
            end.join(1000);
            assertFalse(end.isAlive());
            assertFalse("An interrupted end still starts the cooldown", server.startGame());
            set(server, "mNextGameStartAllowedAt", SystemClock.elapsedRealtime() - 1);
            assertTrue("A cancelled cleanup permanently blocked new starts", server.startGame());
            start = queuedWorker();
        } finally { clientsLock.release(); }
        start.join(1000);
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertFalse(server.events.contains(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void aLaterRoundCanStillBeEndedAfterCleanupCompletes() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        dispatchThenChange(server::endGame, () -> { });
        MemorySocket nextRound = new MemorySocket();
        addClient(2, nextRound);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        dispatchThenChange(server::endGame, () -> { });
        assertFalse(nextRound.closed);
        assertEquals(2, clients.size());
        assertTrue(server.arePlayerClocksSynchronized());
        assertEquals(2, java.util.Collections.frequency(server.events, NetMsg.NETMSG_ENDGAME));
    }

    private List<Thread> captureClientTasks() throws Exception {
        Field lockField = TcpServer.class.getDeclaredField("mServerStateLock");
        lockField.setAccessible(true);
        Field tasksField = TcpServer.class.getDeclaredField("mClientTasks");
        tasksField.setAccessible(true);
        synchronized (lockField.get(server)) {
            List<Thread> tasks = new ArrayList<>();
            for (Object entry : (Set<?>) tasksField.get(server)) {
                Thread task = (Thread) entry;
                tasks.add(task);
                task.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
                if (!workers.contains(task)) workers.add(task);
            }
            return tasks;
        }
    }

    @Test
    public void normalBroadcastStillDeliversAndReturnsItsPermit() throws Exception {
        assertSendWaitsForClientLock(() -> server.sendTCPMessageAll("delivered"));
    }

    @Test
    public void stalledClientLockBoundsTheOutboundTaskBacklog() throws Exception {
        List<Thread> tasks;
        clientsLock.acquire();
        try {
            for (int count = 0; count < TcpServer.MAX_PENDING_CLIENT_TASKS * 3; count++)
                server.sendTCPMessageAll("burst " + count);
            tasks = captureClientTasks();
            assertEquals(TcpServer.MAX_PENDING_CLIENT_TASKS, tasks.size());
        } finally { clientsLock.release(); }
        for (Thread task : tasks) {
            task.join(2000);
            assertFalse("Bounded TCP sender did not finish", task.isAlive());
        }
        awaitOutboundWrites();
        assertTrue(captureClientTasks().isEmpty());
    }

    @Test
    public void normalStartIsSentBeforeTheLocalStartEvent() throws Exception {
        clientsLock.acquire();
        Thread worker;
        try {
            assertTrue(server.startGame());
            worker = queuedWorker();
            assertTrue(server.events.isEmpty());
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        awaitOutboundWrites();
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void teamSendStillExcludesTheSenderAndOpponents() throws Exception {
        MemorySocket teammate = new MemorySocket();
        MemorySocket enemy = new MemorySocket();
        addClient(2, teammate);
        addClient(17, enemy);
        clientsLock.acquire();
        Thread worker;
        try {
            sendTeam(true, false);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        awaitOutboundWrites();
        assertEquals(0, sockets.get(0).bytes.size());
        assertEquals(0, enemy.bytes.size());
        assertEquals("team", new DataInputStream(new ByteArrayInputStream(teammate.bytes.toByteArray())).readUTF());
    }

    @Test
    public void disconnectedPlayerStillQueuesMessagesForRejoin() throws Exception {
        closeClient(1);
        clientsLock.acquire();
        Thread worker;
        try {
            sendPlayer(true);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertEquals(1, queuedMessages(1).size());
        assertEquals("player", queuedMessages(1).peek());
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void disconnectedTeammatesStillQueueMessagesWithoutQueuingForOpponents() throws Exception {
        addClient(2, new MemorySocket());
        addClient(17, new MemorySocket());
        closeClient(1);
        closeClient(2);
        closeClient(17);
        clientsLock.acquire();
        Thread worker;
        try {
            sendTeam(true, false);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertTrue(queuedMessages(1).isEmpty());
        assertTrue(queuedMessages(17).isEmpty());
        assertEquals(1, queuedMessages(2).size());
        assertEquals("team", queuedMessages(2).peek());
    }

    @Test
    public void queuedBroadcastExcludesPlayersWhoJoinAfterItWasPrepared() throws Exception {
        assertLaterJoinerDoesNotReceive(() -> server.sendTCPMessageAll("old snapshot"), 2);
    }

    @Test
    public void queuedBroadcastDoesNotFollowAReplacedConnection() throws Exception {
        assertReplacementDoesNotReceive(() -> server.sendTCPMessageAll("old snapshot"));
    }

    @Test
    public void queuedPlayerMessageDoesNotFollowAReusedPlayerId() throws Exception {
        assertReplacementDoesNotReceive(() -> sendPlayer(true));
    }

    @Test
    public void queuedTeamMessageExcludesLaterTeammates() throws Exception {
        assertLaterJoinerDoesNotReceive(() -> sendTeam(true, true), 2);
    }

    @Test
    public void queuedTeamMessageDoesNotFollowAReplacedTeammate() throws Exception {
        MemorySocket original = new MemorySocket();
        MemorySocket replacement = new MemorySocket();
        addClient(2, original);
        dispatchThenChange(() -> sendTeam(true, false), () -> {
            closeClient(2);
            clients.remove(2);
            addClient(3, 2, replacement);
        });
        assertEquals(0, original.bytes.size());
        assertEquals("New player received an earlier teammate's score event", 0, replacement.bytes.size());
    }

    @Test
    public void queuedPersonalizedRosterExcludesLaterJoiners() throws Exception {
        assertLaterJoinerDoesNotReceive(() -> server.sendAllGameInfo(1), 2);
    }

    @Test
    public void queuedBroadcastRosterExcludesLaterJoiners() throws Exception {
        assertLaterJoinerDoesNotReceive(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), 2);
    }

    @Test
    public void queuedPersonalizedRosterDoesNotFollowAReusedPlayerId() throws Exception {
        assertReplacementDoesNotReceive(() -> server.sendAllGameInfo(1));
    }

    @Test
    public void queuedPlayerMessageStillReachesTheSamePlayersRejoinedConnection() throws Exception {
        MemorySocket replacement = new MemorySocket();
        sockets.add(replacement);
        dispatchThenChange(() -> sendPlayer(true), () -> {
            Object client = clients.get(1);
            Method rejoin = client.getClass().getDeclaredMethod("rejoin", Socket.class);
            rejoin.setAccessible(true);
            rejoin.invoke(client, replacement);
        });
        assertEquals(0, sockets.get(0).bytes.size());
        assertEquals("player", new DataInputStream(new ByteArrayInputStream(replacement.bytes.toByteArray())).readUTF());
    }

    @Test
    public void nextBroadcastStillIncludesNewlyJoinedPlayers() throws Exception {
        MemorySocket newcomer = new MemorySocket();
        dispatchThenChange(() -> server.sendTCPMessageAll("old snapshot"), () -> addClient(2, newcomer));
        newcomer.bytes.reset();
        dispatchThenChange(() -> server.sendTCPMessageAll("new snapshot"), () -> { });
        assertEquals("new snapshot", new DataInputStream(new ByteArrayInputStream(newcomer.bytes.toByteArray())).readUTF());
    }

    @Test
    public void unregisteredConnectionsCannotSatisfyGameStart() throws Exception {
        set(clients.get(1), "mPlayerID", (byte) 0);
        assertFalse("An unregistered socket was treated as a ready player", server.startGame());
    }

    @Test
    public void disconnectedPlayersCannotSatisfyGameStart() throws Exception {
        closeClient(1);
        assertFalse("A disconnected player was treated as ready", server.startGame());
    }

    @Test
    public void gameStartSkipsUnregisteredConnectionsButStartsRegisteredPlayers() throws Exception {
        MemorySocket unregistered = new MemorySocket();
        addClient(2, 0, unregistered);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertEquals("An unregistered connection received a start command", 0, unregistered.bytes.size());
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void queuedBroadcastDoesNotSurviveAConnectionsNewRegistration() throws Exception {
        MemorySocket joining = new MemorySocket();
        addClient(2, 0, joining);
        dispatchThenChange(() -> server.sendTCPMessageAll("before registration"),
                () -> set(clients.get(2), "mPlayerID", (byte) 2));
        assertEquals("Newly registered player received a pre-registration snapshot", 0, joining.bytes.size());
        assertTrue(sockets.get(0).bytes.size() > 0);
    }

    @Test
    public void queuedPlayerEventCannotFollowADifferentIdentityOnTheSameRegisteredSocket() throws Exception {
        dispatchThenChange(() -> sendPlayer(true), () ->
                set(clients.get(1), "playerIdentity", java.util.UUID.randomUUID().toString()));
        assertEquals("A previous person's event followed the same socket and player ID", 0,
                sockets.get(0).bytes.size());
        assertTrue(queuedMessages(1).isEmpty());
    }

    @Test
    public void queuedPlayerMessageCannotMoveToANewlyRegisteredOwnerOfTheId() throws Exception {
        MemorySocket joining = new MemorySocket();
        addClient(2, 0, joining);
        dispatchThenChange(() -> sendPlayer(true), () -> {
            closeClient(1);
            clients.remove(1);
            set(clients.get(2), "mPlayerID", (byte) 1);
        });
        assertEquals("An old score event moved to a new registration", 0, joining.bytes.size());
        assertEquals(0, sockets.get(0).bytes.size());
    }

    @Test
    public void queuedTeamScoreStillReachesTeammatesAfterTheScorerLeaves() throws Exception {
        MemorySocket teammate = new MemorySocket();
        addClient(2, teammate);
        dispatchThenChange(() -> sendTeam(true, false), () -> {
            closeClient(1);
            clients.remove(1);
        });
        assertEquals("team", new DataInputStream(new ByteArrayInputStream(teammate.bytes.toByteArray())).readUTF());
        assertEquals(0, sockets.get(0).bytes.size());
    }

    @Test
    public void queuedStartCannotMoveToAReplacementRoster() throws Exception {
        MemorySocket replacement = new MemorySocket();
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> {
            closeClient(1);
            clients.remove(1);
            addClient(3, 1, replacement);
        });
        assertEquals("New round inherited an earlier start request", 0, replacement.bytes.size());
        assertTrue(server.events.isEmpty());
    }

    @Test
    public void lateSynchronizedJoinCancelsQueuedStartUntilTheWholeRosterIsRetried() throws Exception {
        assertLateJoinCancelsQueuedStart(true);
    }

    @Test
    public void lateUnsynchronizedJoinCannotBeSkippedByQueuedStart() throws Exception {
        assertLateJoinCancelsQueuedStart(false);
    }

    @Test
    public void peerHostKeepsLobbyOpenWhenALateJoinCancelsQueuedStart() throws Exception {
        // Client 2 joins later; the local host must occupy a different seat.
        Globals.getInstance().mPlayerID = 3;
        Globals.getInstance().mLocalLobbyReady = true;
        server.setDedicated(false);
        assertLateJoinCancelsQueuedStart(true);
    }

    private void assertLateJoinCancelsQueuedStart(boolean clockReady) throws Exception {
        MemorySocket newcomer = new MemorySocket();
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> {
            addClient(2, newcomer);
            set(clients.get(2), "clockSynchronized", clockReady);
        });
        assertNoStartPublished();
        if (!clockReady) {
            assertFalse("An unsynchronized newcomer must block a fresh start too", server.startGame());
            set(clients.get(2), "clockSynchronized", true);
        }
        assertWholeRosterCanStartAfterRetry();
    }

    @Test
    public void socketRegisteredWhileStartWasQueuedCannotBeLeftInTheLobby() throws Exception {
        addClient(2, 0, new MemorySocket());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> {
            // Model registration completing on an already accepted socket.
            set(clients.get(2), "mPlayerID", (byte) 2);
            Globals.getInstance().mTeamIPMap.put((byte) 2, InetAddress.getByName("127.0.0.2"));
            Globals.getInstance().mIPTeamMap.put(InetAddress.getByName("127.0.0.2"), (byte) 2);
            Globals.getInstance().mTeamPlayerNameMap.put((byte) 2, "Player 2");
        });
        assertNoStartPublished();
        assertWholeRosterCanStartAfterRetry();
    }

    @Test
    public void replacementPlayerCancelsQueuedStartEvenWhenAnotherRecipientRemains() throws Exception {
        addClient(2, new MemorySocket());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> {
            closeClient(1);
            clients.remove(1);
            addClient(3, 1, new MemorySocket());
        });
        assertNoStartPublished();
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertEquals(0, sockets.get(0).bytes.size());
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(1).bytes.toByteArray())).readUTF());
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(2).bytes.toByteArray())).readUTF());
        assertEquals(1, server.events.size());
    }

    @Test
    public void laterUnregisteredSocketDoesNotBlockQueuedStart() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> addClient(2, 0, new MemorySocket()));
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertEquals(0, sockets.get(1).bytes.size());
        assertEquals(1, server.events.size());
    }

    @Test
    public void disconnectedRecipientDoesNotBlockRemainingSynchronizedPlayers() throws Exception {
        addClient(2, new MemorySocket());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> closeClient(2));
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertEquals(0, sockets.get(1).bytes.size());
        assertEquals(1, server.events.size());
    }

    private void assertNoStartPublished() {
        for (MemorySocket socket : sockets)
            assertEquals("A changed roster received a partial start", 0, socket.bytes.size());
        assertTrue("A changed roster must not start the host countdown", server.events.isEmpty());
        assertTrue(server.getScheduledGameStart() == null);
    }

    private void assertWholeRosterCanStartAfterRetry() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        Intent scheduled = server.getScheduledGameStart();
        assertTrue(scheduled != null);
        for (MemorySocket socket : sockets) {
            JSONObject start = readJson(socket);
            assertEquals(scheduled.getLongExtra(NetMsg.INTENT_ROUND_ID, 0), start.getLong(TcpServer.JSON_ROUND_ID));
            assertEquals(scheduled.getStringExtra(NetMsg.INTENT_ROUND_TOKEN),
                    start.getString(TcpServer.JSON_ROUND_TOKEN));
            assertEquals(scheduled.getLongExtra(NetMsg.INTENT_START_AT, 0), start.getLong(TcpServer.JSON_GAMESTART));
        }
        assertEquals(1, server.events.size());
    }

    @Test
    public void shortRespawnDoesNotShortenTheTwentyThreeSecondGameStart() throws Exception {
        assertFixedGameStartCountdown(1, false, false);
    }

    @Test
    public void balancedStartRetainsTeamsAndRepeatedStartKeepsScansAndDeadline() throws Exception {
        Globals globals = seedBalancedLobby();
        Map<Byte, Integer> teams = new HashMap<>(globals.mBalancedTeams);
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        assertEquals(teams, globals.mBalancedTeams);
        assertTrue(globals.mBalancedCheckedIn.isEmpty());
        assertTrue(server.isBalancedGamePrepared());
        long deadline = (long) get(server, "mBalancedCheckInDeadline");
        assertTrue(deadline > SystemClock.elapsedRealtime());
        globals.setBalancedAssignments(teams, java.util.Collections.singleton((byte) 1));
        set(clients.get(1), "priorKills", 100000);
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        assertEquals(teams, globals.mBalancedTeams);
        assertEquals(java.util.Collections.singleton((byte) 1), globals.mBalancedCheckedIn);
        assertEquals(deadline, get(server, "mBalancedCheckInDeadline"));
        assertFalse(server.events.contains(NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void balancedNoQrStartRetainsTeamsWhileStillStartingNormally() throws Exception {
        Globals globals = seedBalancedLobby();
        globals.mBalancedRequireQr = false;
        Map<Byte, Integer> teams = new HashMap<>(globals.mBalancedTeams);
        assertTrue(server.prepareBalancedGame());
        for (int pass = 0; pass < 10; pass++) {
            List<Thread> tasks = captureClientTasks();
            if (tasks.isEmpty()) break;
            for (Thread task : tasks) {
                task.join(2000);
                assertFalse("Balanced start task did not finish", task.isAlive());
            }
        }
        assertEquals(teams, globals.mBalancedTeams);
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertTrue(globals.mBalancedGamePrepared);
    }

    @Test
    public void roundEndRetainsIdentityTeamsWhenReturningPlayersChangeSeats() throws Exception {
        Globals globals = seedBalancedLobby();
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        Map<String, Integer> byIdentity = new HashMap<>(globals.mRetainedBalancedTeams);
        assertEquals(4, byIdentity.size());
        List<String> identities = new ArrayList<>(byIdentity.keySet());
        dispatchThenChange(server::endGame, () -> { });
        assertEquals(byIdentity, globals.mRetainedBalancedTeams);
        assertTrue(globals.mBalancedTeams.isEmpty());
        assertFalse(globals.mBalancedGamePrepared);
        // These participants explicitly disconnect before returning in new
        // seats. Ordinary round completion keeps their old sockets admitted.
        Constructor<?> handlerConstructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        handlerConstructor.setAccessible(true);
        Object handler = handlerConstructor.newInstance(server);
        Method remove = handler.getClass().getDeclaredMethod("removeClient",
                clients.get(1).getClass(), Integer.class, boolean.class);
        remove.setAccessible(true);
        for (int id : new ArrayList<>(clients.keySet())) remove.invoke(handler, clients.get(id), id, true);
        awaitBalancedModeTasks();
        for (int index = 0; index < identities.size(); index++) {
            int id = index + 7;
            addClient(id, new MemorySocket());
            set(clients.get(id), "playerIdentity", identities.get(index));
            set(clients.get(id), "priorKills", 100000 - 10000 * index);
        }
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        for (int index = 0; index < identities.size(); index++)
            assertEquals(byIdentity.get(identities.get(index)), globals.mBalancedTeams.get((byte) (index + 7)));
        assertTrue(globals.mBalancedCheckedIn.isEmpty());
    }

    @Test
    public void explicitRebalanceRecomputesStatsButCannotAutoStartBeforeStart() throws Exception {
        Globals globals = seedBalancedLobby();
        Map<Byte, Integer> unbalanced = new HashMap<>();
        for (byte id = 1; id <= 4; id++) unbalanced.put(id, 1);
        globals.setBalancedAssignments(unbalanced, unbalanced.keySet());
        set(clients.get(1), "clockSynchronized", false);
        dispatchThenChange(() -> assertTrue(server.rebalanceTeams()), () -> { });
        assertEquals(4, globals.mBalancedTeams.size());
        assertTrue(globals.mBalancedTeams.containsValue(1));
        assertTrue(globals.mBalancedTeams.containsValue(2));
        assertTrue(globals.mBalancedCheckedIn.isEmpty());
        assertFalse(globals.mBalancedGamePrepared);
        assertEquals(-1L, get(server, "mBalancedCheckInDeadline"));
        Method confirm = TcpServer.class.getDeclaredMethod("confirmBalancedCheckIn", byte.class);
        confirm.setAccessible(true);
        for (byte id = 1; id <= 4; id++) assertFalse((boolean) confirm.invoke(server, id));
        assertFalse(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertFalse(readJson(sockets.get(0)).getBoolean(TcpServer.JSON_BALANCED_PREPARED));
    }

    @Test
    public void rebalanceIsRejectedDuringRoundAndCommittedCountdown() throws Exception {
        seedBalancedLobby();
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        assertFalse(server.rebalanceTeams());
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        set(server, "mStartingGame", true);
        assertFalse(server.rebalanceTeams());
        set(server, "mStartingGame", false);
        set(server, "mStartAnnounced", true);
        assertFalse(server.rebalanceTeams());
    }

    @Test
    public void newRegistrationAndDepartureNeverShuffleExistingTeamsOrExtendQrTimeout() throws Exception {
        Globals globals = seedBalancedLobby();
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        Map<Byte, Integer> teams = new HashMap<>(globals.mBalancedTeams);
        globals.setBalancedAssignments(teams, java.util.Collections.singleton((byte) 1));
        long deadline = (long) get(server, "mBalancedCheckInDeadline");
        MemorySocket newcomer = new MemorySocket() {
            @Override public InetAddress getInetAddress() {
                try { return InetAddress.getByName("127.0.0.5"); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
        addClient(5, 0, newcomer);
        parseClock(5, new JSONObject().put(TcpServer.JSON_PLAYERID, 5)
                .put(TcpServer.JSON_PLAYERNAME, "New player")
                .put(LiveJoin.IDENTITY, java.util.UUID.randomUUID().toString()));
        for (Thread task : captureClientTasks()) task.join(2000);
        for (byte id = 1; id <= 4; id++) assertEquals(teams.get(id), globals.mBalancedTeams.get(id));
        assertEquals(5, globals.mBalancedTeams.size());
        assertEquals(deadline, get(server, "mBalancedCheckInDeadline"));
        assertEquals(java.util.Collections.singleton((byte) 1), globals.mBalancedCheckedIn);
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(server);
        Object departing = clients.get(5);
        Method remove = handler.getClass().getDeclaredMethod("removeClient", departing.getClass(),
                Integer.class, boolean.class);
        remove.setAccessible(true);
        remove.invoke(handler, departing, 5, true);
        for (Thread task : captureClientTasks()) task.join(2000);
        assertEquals(teams, globals.mBalancedTeams);
        assertEquals(deadline, get(server, "mBalancedCheckInDeadline"));
    }

    @Test
    public void ordinaryPhoneRoundCancellationRetainsTeamsButTrueLobbyResetClearsThem() throws Exception {
        Globals globals = seedBalancedLobby();
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        Map<String, Integer> retained = new HashMap<>(globals.mRetainedBalancedTeams);
        dispatchThenChange(() -> server.cancelServer(true), () -> { });
        assertEquals(retained, globals.mRetainedBalancedTeams);
        assertTrue(globals.mBalancedTeams.isEmpty());
        assertFalse(globals.mBalancedGamePrepared);
        globals.clearBalancedAssignments();
        assertTrue(globals.mRetainedBalancedTeams.isEmpty());
    }

    @Test
    public void balancedAssignmentModePreservesTournamentOrClassicWeaponProfile() throws Exception {
        Globals globals = Globals.getInstance();
        String[] changedFields = {"mTournamentMode", "mBossMode", "mCaptureTheFlag", "mInfectionMode",
                "mInfectedPlayers", "mBossHunterCount", "mFullHealth", "mFullShields", "mFullReload",
                "mReloadTime", "mReloadOnEmpty", "mRespawnTime", "mDamage", "mOverrideLives",
                "mOverrideLivesVal", "mAllowSingleShotMode", "mAllowBurst3ShotMode", "mAllowAutoShotMode",
                "mCurrentFiringMode", "mAllowPlayerSettings", "mOnlyServerSettings", "mVibrateOnHit"};
        Map<String, Object> oldGlobals = new HashMap<>();
        for (String field : changedFields) oldGlobals.put(field, get(globals, field));
        Map<Byte, Globals.PlayerSettings> oldSettings = copyPlayerSettings();
        try {
            for (boolean tournament : new boolean[]{true, false}) {
                globals.mBalancedRandom = false;
                globals.mTournamentMode = tournament;
                globals.mBossMode = true;
                globals.mCaptureTheFlag = true;
                globals.mInfectionMode = true;
                globals.resetInfectedPlayers();
                globals.setRetainedBalancedTeams(java.util.Collections.singletonMap("old identity", 1));
                Globals.PlayerSettings player = new Globals.PlayerSettings();
                Globals.applyBossHealth(player, Globals.BOSS_PLAYER_ID, 3);
                globals.mPlayerSettings.put((byte) 1, player);

                assertTrue(server.setBalancedGameMode(true));
                awaitBalancedModeTasks();
                assertEquals(tournament, globals.mTournamentMode);
                assertFalse(globals.mBossMode);
                assertFalse(globals.mCaptureTheFlag);
                assertFalse(globals.mInfectionMode);
                assertTrue(globals.mInfectedPlayers.isEmpty());
                assertTrue(globals.mRetainedBalancedTeams.isEmpty());
                assertTrue(globals.mBalancedRandom);
                assertEquals(Globals.GAME_MODE_2TEAMS, globals.mGameMode);
                assertEquals(Globals.MAX_HEALTH, globals.mFullHealth);
                assertEquals(Globals.RELOAD_COUNT, globals.mFullReload);
                assertEquals(!tournament, globals.mAllowAutoShotMode);
                assertEquals(!tournament, globals.mAllowBurst3ShotMode);
                assertEquals(!tournament, globals.mAllowPlayerSettings);
                assertEquals(tournament, globals.mOnlyServerSettings);
                assertEquals(!tournament, player.allowShotModeAuto);
                assertEquals(!tournament, player.allowShotModeBurst3);
                assertEquals(Globals.MAX_HEALTH, player.health);
                if (tournament) assertTrue(globals.mVibrateOnHit);

                // A QR-option change is not a request to reset custom Classic settings.
                if (!tournament) {
                    globals.mFullHealth = 73;
                    globals.mDamage = -2;
                    player.health = 73;
                    player.damage = -2;
                }
                int healthBefore = globals.mFullHealth;
                int damageBefore = globals.mDamage;
                assertTrue(server.setBalancedGameMode(false));
                awaitBalancedModeTasks();
                assertEquals(tournament, globals.mTournamentMode);
                assertEquals(healthBefore, globals.mFullHealth);
                assertEquals(damageBefore, globals.mDamage);
                assertEquals(healthBefore, player.health);
                assertEquals(damageBefore, player.damage);
            }
        } finally {
            awaitBalancedModeTasks();
            for (Map.Entry<String, Object> entry : oldGlobals.entrySet())
                set(globals, entry.getKey(), entry.getValue());
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
                globals.mPlayerSettings.putAll(oldSettings);
            } finally { globals.mPlayerSettingsSemaphore.release(); }
        }
    }

    private void awaitBalancedModeTasks() throws Exception {
        for (int pass = 0; pass < 10; pass++) {
            List<Thread> tasks = captureClientTasks();
            if (tasks.isEmpty()) {
                awaitOutboundWrites();
                return;
            }
            for (Thread task : tasks) {
                task.join(2000);
                assertFalse("Balanced mode task did not finish", task.isAlive());
            }
        }
        assertTrue("Balanced mode tasks did not settle", captureClientTasks().isEmpty());
    }

    @Test
    public void qrModeToggleRetainsTeamsButSameModeKeepsArmedScans() throws Exception {
        Globals globals = seedBalancedLobby();
        dispatchThenChange(() -> assertTrue(server.prepareBalancedGame()), () -> { });
        Map<Byte, Integer> teams = new HashMap<>(globals.mBalancedTeams);
        Map<String, Integer> retained = new HashMap<>(globals.mRetainedBalancedTeams);
        globals.setBalancedAssignments(teams, java.util.Collections.singleton((byte) 1));
        long deadline = (long) get(server, "mBalancedCheckInDeadline");
        assertTrue(server.setBalancedGameMode(true));
        assertEquals(deadline, get(server, "mBalancedCheckInDeadline"));
        assertTrue(globals.mBalancedCheckedIn.contains((byte) 1));
        assertTrue(server.setBalancedGameMode(false));
        for (Thread task : captureClientTasks()) task.join(2000);
        assertEquals(teams, globals.mBalancedTeams);
        assertEquals(retained, globals.mRetainedBalancedTeams);
        assertFalse(globals.mBalancedGamePrepared);
        assertTrue(globals.mBalancedCheckedIn.isEmpty());
        assertEquals(-1L, get(server, "mBalancedCheckInDeadline"));
    }

    private Globals seedBalancedLobby() throws Exception {
        Globals globals = Globals.getInstance();
        globals.mBalancedRandom = true;
        globals.mBalancedRequireQr = true;
        Map<Byte, Integer> teams = new HashMap<>();
        teams.put((byte) 1, 1);
        for (byte id = 2; id <= 4; id++) {
            addClient(id, new MemorySocket());
            teams.put(id, id <= 2 ? 1 : 2);
        }
        globals.setBalancedAssignments(teams, teams.keySet());
        return globals;
    }

    @Test
    public void longRespawnDoesNotExtendTheTwentyThreeSecondGameStart() throws Exception {
        assertFixedGameStartCountdown(90, false, false);
    }

    @Test
    public void balancedQrGameStartsWithTheSameTwentyThreeSecondCountdown() throws Exception {
        assertFixedGameStartCountdown(90, true, true);
    }

    @Test
    public void balancedNoQrGameStartsWithTheSameTwentyThreeSecondCountdown() throws Exception {
        assertFixedGameStartCountdown(1, true, false);
    }

    private void assertFixedGameStartCountdown(long respawnTime, boolean balanced, boolean requireQr)
            throws Exception {
        Globals globals = Globals.getInstance();
        long originalRespawnTime = globals.mRespawnTime;
        boolean originalBalanced = globals.mBalancedRandom;
        boolean originalRequireQr = globals.mBalancedRequireQr;
        Map<Byte, Integer> originalTeams = globals.mBalancedTeams;
        Set<Byte> originalCheckedIn = globals.mBalancedCheckedIn;
        try {
            globals.mRespawnTime = respawnTime;
            globals.mBalancedRandom = balanced;
            globals.mBalancedRequireQr = requireQr;
            if (balanced) {
                addClient(2, new MemorySocket());
                addClient(17, new MemorySocket());
                Map<Byte, Integer> teams = new HashMap<>();
                teams.put((byte) 1, 1);
                teams.put((byte) 2, 1);
                teams.put((byte) 17, 2);
                globals.setBalancedAssignments(teams, teams.keySet());
            }
            long beforeStart = SystemClock.elapsedRealtime();
            dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
            long afterStart = SystemClock.elapsedRealtime();
            JSONObject start = readJson(sockets.get(0));
            long startAt = start.getLong(TcpServer.JSON_GAMESTART);
            assertTrue("Initial countdown was shortened", startAt >= beforeStart
                    + GameStartCountdown.DURATION_MS);
            assertTrue("Respawn settings extended the initial countdown", startAt <= afterStart
                    + GameStartCountdown.DURATION_MS);
            assertEquals(startAt, server.getScheduledGameStart().getLongExtra(NetMsg.INTENT_START_AT, 0));
            assertEquals("Starting a round changed the respawn delay", respawnTime, globals.mRespawnTime);
        } finally {
            globals.mRespawnTime = originalRespawnTime;
            globals.mBalancedRandom = originalBalanced;
            globals.mBalancedRequireQr = originalRequireQr;
            globals.setBalancedAssignments(originalTeams, originalCheckedIn);
        }
    }

    @Test
    public void closingAnnouncementCannotUnlockDedicatedCombatOrRespawnsBeforeTheDeadline() throws Exception {
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() + GameStartCountdown.ENDING_VOICE_MS);
        set(clients.get(1), "awaitingRespawn", true);
        Method roundStarted = TcpServer.class.getDeclaredMethod("hasRoundStarted");
        roundStarted.setAccessible(true);
        assertFalse("Combat was enabled during the closing announcement", (boolean) roundStarted.invoke(server));
        assertTrue("Respawns became selectable during the closing announcement",
                server.getGameMasterRespawnCandidates().isEmpty());
        assertFalse("The Game Master enabled a weapon before the start deadline",
                server.grantGameMasterRespawn((byte) 1));

        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 1);
        assertTrue((boolean) roundStarted.invoke(server));
        assertEquals(1, server.getGameMasterRespawnCandidates().size());
    }

    @Test
    public void failedStartWritesDoNotPublishAStartConfirmation() throws Exception {
        set(clients.get(1), "out", new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        }));
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue("Server confirmed a start that no client received", server.events.isEmpty());
        assertTrue(sockets.get(0).closed);
    }

    @Test
    public void aFailedStartRecipientDoesNotPreventAHealthyPlayersStartConfirmation() throws Exception {
        MemorySocket healthy = new MemorySocket();
        addClient(2, healthy);
        set(clients.get(1), "out", new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        }));
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue("Failed start recipient kept its damaged socket", sockets.get(0).closed);
        assertFalse("A failed recipient closed a healthy player's socket", healthy.closed);
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(healthy.bytes.toByteArray())).readUTF());
        assertTrue("A successfully flushed start never reached the host UI",
                server.events.contains(NetMsg.NETMSG_STARTGAME));
    }

    private void assertLaterJoinerDoesNotReceive(CheckedAction send, int playerID) throws Exception {
        MemorySocket newcomer = new MemorySocket();
        dispatchThenChange(send, () -> addClient(playerID, newcomer));
        assertEquals("Late joiner received an older update", 0, newcomer.bytes.size());
        assertTrue("Existing recipient lost its update", sockets.get(0).bytes.size() > 0);
    }

    private void assertReplacementDoesNotReceive(CheckedAction send) throws Exception {
        MemorySocket replacement = new MemorySocket();
        dispatchThenChange(send, () -> {
            closeClient(1);
            clients.remove(1);
            addClient(3, 1, replacement);
        });
        assertEquals("New connection inherited an old registration's message", 0, replacement.bytes.size());
        assertEquals(0, sockets.get(0).bytes.size());
    }

    private void dispatchThenChange(CheckedAction send, CheckedAction change) throws Exception {
        clientsLock.acquire();
        Thread worker;
        try {
            send.run();
            worker = queuedWorker();
            change.run();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse("Sender did not finish after the membership change", worker.isAlive());
        awaitOutboundWrites();
        assertEquals(1, clientsLock.availablePermits());
    }

    private void assertInterruptedSend(CheckedAction action) throws Exception {
        clientsLock.acquire();
        try {
            action.run();
            Thread worker = queuedWorker();
            worker.interrupt();
            worker.join(1000);
            assertFalse("Interrupted sender did not exit", worker.isAlive());
            assertEquals("Interrupted task wrote without acquiring the client lock", 0, sockets.get(0).bytes.size());
            assertEquals("Interrupted task released an unacquired permit", 0, clientsLock.availablePermits());
            // A terminated Android thread need not retain its interrupt flag.
            // Check the cancellation effects and permit ownership instead.
        } finally { clientsLock.release(); }
    }

    private void assertStopCancelsQueuedTask(CheckedAction action) throws Exception {
        clientsLock.acquire();
        Thread worker = null;
        try {
            action.run();
            worker = queuedWorker();
            server.stopTcpServer();
            worker.join(1000);
            assertFalse("Stopped server left a task waiting for the client lock", worker.isAlive());
            assertTrue(server.events.isEmpty());
            assertEquals(0, clientsLock.availablePermits());
        } finally {
            clientsLock.release();
            if (worker != null) worker.join(1000);
        }
        assertEquals(0, sockets.get(0).bytes.size());
        assertTrue(server.events.isEmpty());
    }

    private void assertSendWaitsForClientLock(CheckedAction action) throws Exception {
        clientsLock.acquire();
        Thread worker;
        try {
            action.run();
            worker = queuedWorker();
            assertEquals(0, sockets.get(0).bytes.size());
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        awaitOutboundWrites();
        assertTrue(sockets.get(0).bytes.size() > 0);
        assertEquals(1, clientsLock.availablePermits());
    }

    private Thread queuedWorker() throws Exception {
        assertQueued(clientsLock);
        Thread worker = clientsLock.waiters().iterator().next();
        worker.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        workers.add(worker);
        return worker;
    }

    private static void assertQueued(Semaphore lock) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (!lock.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
        assertTrue("Task did not wait for the held lock", lock.hasQueuedThreads());
    }

    private void sendPlayer(boolean queueFailed) throws Exception {
        Method method = TcpServer.class.getDeclaredMethod("sendTCPMessageID", String.class, byte.class, boolean.class);
        method.setAccessible(true);
        method.invoke(server, "player", (byte) 1, queueFailed);
    }

    private void sendTeam(boolean queueFailed, boolean includePlayer) throws Exception {
        Method method = TcpServer.class.getDeclaredMethod("sendTCPMessageTeam", String.class, byte.class,
                boolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(server, "team", (byte) 1, includePlayer, queueFailed);
    }

    @Test
    public void unsynchronizedRegisteredPlayerBlocksStartUntilAcknowledgement() throws Exception {
        set(clients.get(1), "clockSynchronized", false);
        assertFalse(server.arePlayerClocksSynchronized());
        assertFalse(server.startGame());
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, "true"));
        assertFalse("String true must not acknowledge clock sync", server.startGame());
        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++)
            parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 1000 + i));
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertTrue(server.arePlayerClocksSynchronized());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void clockReadyRequiresCompletedClockSampling() throws Exception {
        set(clients.get(1), "clockSynchronized", false);
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertFalse("A client cannot claim synchronization before sampling", server.arePlayerClocksSynchronized());

        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC - 1; i++)
            parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 2000 + i));
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertFalse("Partial sampling cannot satisfy the clock barrier", server.arePlayerClocksSynchronized());

        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 3000));
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertTrue(server.arePlayerClocksSynchronized());
    }

    @Test
    public void rejectedClockSamplesDoNotStrandPlayerAtSlowPolling() throws Exception {
        set(clients.get(1), "clockSynchronized", false);
        set(clients.get(1), "clockSamples", GameClock.SAMPLES_PER_SYNC);
        set(server, "mClockSamplingUntil", 0L);
        long before = SystemClock.elapsedRealtime();
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 5000));
        Field deadline = TcpServer.class.getDeclaredField("mClockSamplingUntil");
        deadline.setAccessible(true);
        assertTrue("Host slowed polling before the phone accepted enough samples",
                deadline.getLong(server) > before);
        assertFalse(server.arePlayerClocksSynchronized());
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertTrue(server.arePlayerClocksSynchronized());
    }

    @Test
    public void refreshBatchGetsFastPollingButIsolatedCountdownProbeDoesNot() throws Exception {
        set(clients.get(1), "clockSamples", GameClock.SAMPLES_PER_SYNC);
        set(clients.get(1), "clockSynchronized", true);
        set(clients.get(1), "lastClockRequestAt", -1L);
        set(server, "mClockSamplingUntil", 0L);
        Field deadline = TcpServer.class.getDeclaredField("mClockSamplingUntil");
        deadline.setAccessible(true);
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 5000));
        assertEquals("Isolated probe should not start a high-rate poll", 0, deadline.getLong(server));
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 5010));
        assertTrue("Follow-up calibration batch was left at slow polling",
                deadline.getLong(server) > SystemClock.elapsedRealtime());
    }

    @Test
    public void everyConnectedPlayerMustHaveASynchronizedClock() throws Exception {
        addClient(2, new MemorySocket());
        set(clients.get(2), "clockSynchronized", false);
        assertFalse(server.startGame());
        closeClient(2);
        assertTrue(server.arePlayerClocksSynchronized());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
    }

    @Test
    public void clockRequestEchoesTimestampAndIncludesMonotonicHostTimes() throws Exception {
        long before = SystemClock.elapsedRealtime();
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 123456));
        JSONObject reply = readJson(sockets.get(0));
        assertEquals(123456, reply.getLong(TcpServer.JSON_CLOCK_REQUEST));
        long received = reply.getLong(TcpServer.JSON_CLOCK_RECEIVE);
        long sent = reply.getLong(TcpServer.JSON_CLOCK_SEND);
        assertTrue(received >= before);
        assertTrue(sent >= received);
        assertTrue(sent <= SystemClock.elapsedRealtime());
    }

    @Test
    public void calibratingLargeLobbyDoesNotReceiveRepeatedRosterBursts() throws Exception {
        for (int id = 2; id <= 32; id++) addClient(id, new MemorySocket());
        for (Object client : clients.values()) set(client, "clockSynchronized", false);
        dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
        int[] initialBytes = new int[sockets.size()];
        for (int i = 0; i < sockets.size(); i++) {
            assertEquals(32, readJson(sockets.get(i)).getJSONArray(TcpServer.JSON_PLAYERS).length());
            initialBytes[i] = sockets.get(i).bytes.size();
        }
        Method settings = TcpServer.class.getDeclaredMethod("sendPlayerSettingsUpdate", int.class,
                boolean.class);
        settings.setAccessible(true);
        for (int update = 0; update < 16; update++) {
            dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
            dispatchThenChange(() -> settings.invoke(server, TcpServer.SEND_ALL, false), () -> { });
        }
        for (int i = 0; i < sockets.size(); i++)
            assertEquals("Lobby churn queued bulk frames ahead of a calibrating phone's clock replies",
                    initialBytes[i], sockets.get(i).bytes.size());

        // The next snapshot after readiness must contain the newest state,
        // rather than replaying every intermediate lobby update.
        Globals.getInstance().mTeamPlayerNameMap.put((byte) 32, "Latest name");
        for (Object client : clients.values()) set(client, "clockSynchronized", true);
        dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
        for (int i = 0; i < sockets.size(); i++) {
            DataInputStream frames = new DataInputStream(new ByteArrayInputStream(sockets.get(i).bytes.toByteArray()));
            frames.readUTF();
            JSONObject latest = readJsonFrame(frames.readUTF());
            assertTrue(latest.toString().contains("Latest name"));
            assertEquals(0, frames.available());
        }
    }

    @Test
    public void simultaneousClockAcknowledgementsPublishOneCurrentRoster() throws Exception {
        for (int id = 2; id <= 32; id++) addClient(id, new MemorySocket());
        for (Object client : clients.values()) {
            set(client, "clockSynchronized", false);
            set(client, "clockSamples", GameClock.SAMPLES_PER_SYNC);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Object pending = null;
                for (int id = 1; id <= 32; id++) {
                    parseClock(id, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
                    Object callback = get(server, "mClockRosterUpdate");
                    assertTrue(callback != null);
                    if (pending != null)
                        assertSame("Each ACK scheduled another all-phone roster", pending, callback);
                    pending = callback;
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (sockets.get(31).bytes.size() == 0 && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        awaitBalancedModeTasks();
        assertTrue(server.arePlayerClocksSynchronized());
        for (MemorySocket socket : sockets) {
            DataInputStream frames = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray()));
            JSONArray players = readJsonFrame(frames.readUTF()).getJSONArray(TcpServer.JSON_PLAYERS);
            assertEquals(32, players.length());
            for (int i = 0; i < players.length(); i++)
                assertTrue(players.getJSONObject(i).getBoolean(TcpServer.JSON_CLOCK_READY));
            assertEquals("Intermediate clock states were replayed to the phone", 0, frames.available());
        }
    }

    @Test
    public void lobbyGpsWaitsForCalibrationThenSendsAFullCurrentSnapshot() throws Exception {
        Globals globals = Globals.getInstance();
        int originalMode = globals.mGPSMode;
        try {
            globals.mUseGPS = true;
            for (int mode : new int[]{Globals.GPS_TEAMMATE, Globals.GPS_ALL}) {
                globals.mGPSMode = mode;
                sockets.get(0).bytes.reset();
                set(clients.get(1), "clockSynchronized", false);
                Method gps = TcpServer.class.getDeclaredMethod("sendGPSUpdates", List.class,
                        List.class, boolean.class);
                gps.setAccessible(true);
                dispatchThenChange(() -> gps.invoke(server, java.util.Collections.emptyList(),
                        java.util.Collections.emptyList(), true), () -> { });
                assertEquals("Bulk GPS was queued before the clock replies", 0, sockets.get(0).bytes.size());

                set(clients.get(1), "clockSynchronized", true);
                // Even an empty incremental tick must flush the full current map,
                // clearing markers from before calibration or a reconnect.
                dispatchThenChange(() -> gps.invoke(server, java.util.Collections.emptyList(),
                        java.util.Collections.emptyList(), false), () -> { });
                JSONObject current = readJson(sockets.get(0));
                assertTrue(current.getBoolean(TcpServer.JSON_GPSFULLUPDATE));
                assertEquals(0, current.getJSONArray(TcpServer.JSON_GPSUPDATE).length());
            }
        } finally { globals.mGPSMode = originalMode; }
    }

    @Test
    public void lastClockAckRetainsItsRecoverySnapshotWhenSendSlotsAreFull() throws Exception {
        set(clients.get(1), "clockSynchronized", false);
        set(clients.get(1), "clockSamples", GameClock.SAMPLES_PER_SYNC);
        Semaphore slots = (Semaphore) get(server, "mClientTaskSlots");
        android.os.Handler handler = (android.os.Handler) get(server, "mShutdownHandler");
        slots.acquire(TcpServer.MAX_PENDING_CLIENT_TASKS);
        Runnable pending;
        try {
            parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
            pending = (Runnable) get(server, "mClockRosterUpdate");
            assertTrue(pending != null);
            handler.removeCallbacks(pending);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(pending);
            assertSame("Sender backlog discarded the only post-calibration snapshot",
                    pending, get(server, "mClockRosterUpdate"));
            assertEquals(0, sockets.get(0).bytes.size());
        } finally { slots.release(TcpServer.MAX_PENDING_CLIENT_TASKS); }
        handler.removeCallbacks(pending);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(pending);
        awaitBalancedModeTasks();
        assertTrue(get(server, "mClockRosterUpdate") == null);
        assertTrue(readJson(sockets.get(0)).getJSONArray(TcpServer.JSON_PLAYERS)
                .getJSONObject(0).getBoolean(TcpServer.JSON_CLOCK_READY));
    }

    @Test
    public void clockSendTimestampExcludesWaitingForAnEarlierWriter() throws Exception {
        GateSocket socket = new GateSocket();
        addClient(1, socket);
        server.sendTCPMessageAll("earlier frame");
        assertTrue("Earlier frame did not enter the controlled writer",
                socket.writing.await(1, TimeUnit.SECONDS));
        Thread probe = new Thread(() -> {
            try { parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 123456)); }
            catch (Throwable failure) { failures.add(failure); }
        });
        workers.add(probe);
        long releasedAt = -1;
        try {
            probe.start();
            probe.join(500);
            assertFalse("Queuing a clock reply waited for an earlier socket write", probe.isAlive());
            Thread.sleep(100);
            releasedAt = SystemClock.elapsedRealtime();
        } finally {
            socket.released.countDown();
        }
        awaitOutboundWrites();
        assertTrue(failures.isEmpty());
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray()));
        assertEquals("earlier frame", frames.readUTF());
        JSONObject reply = readJsonFrame(frames.readUTF());
        assertTrue(reply.getLong(TcpServer.JSON_CLOCK_RECEIVE) < releasedAt);
        assertTrue("Writer backlog was counted as network delay",
                reply.getLong(TcpServer.JSON_CLOCK_SEND) >= releasedAt);
    }

    @Test
    public void unregisteredOrMalformedClockRequestDoesNotGetAResponse() throws Exception {
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, -1));
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, "123"));
        assertEquals(0, sockets.get(0).bytes.size());
        addClient(2, 0, new MemorySocket());
        parseClock(2, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 123));
        assertEquals(0, sockets.get(1).bytes.size());
    }

    @Test
    public void hostAndAllPlayersReceiveTheSameStartAndEndDeadlines() throws Exception {
        for (int id = 2; id <= Globals.MAX_PLAYER_ID; id++) addClient(id, new MemorySocket());
        int originalTime = Globals.getInstance().mTimeLimit;
        try {
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            Globals.getInstance().mTimeLimit = 5;
            dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
            JSONObject first = readJson(sockets.get(0));
            Intent host = server.notifications.get(0);
            for (MemorySocket socket : sockets)
                assertEquals(first.toString(), readJson(socket).toString());
            assertEquals(first.getLong(TcpServer.JSON_GAMESTART), host.getLongExtra(NetMsg.INTENT_START_AT, 0));
            assertEquals(first.getLong(TcpServer.JSON_GAMESTART) + 300000,
                    host.getLongExtra(NetMsg.INTENT_END_AT, 0));
            assertEquals(first.getLong(TcpServer.JSON_ROUND_ID), host.getLongExtra(NetMsg.INTENT_ROUND_ID, 0));
            assertEquals(first.getString(TcpServer.JSON_ROUND_TOKEN),
                    host.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        } finally { Globals.getInstance().mTimeLimit = originalTime; }
    }

    @Test
    public void finalBossHealthIsSentBeforeTheCountdownStarts() throws Exception {
        Globals globals = Globals.getInstance();
        boolean tournamentMode = globals.mTournamentMode;
        boolean bossMode = globals.mBossMode;
        boolean allowPlayerSettings = globals.mAllowPlayerSettings;
        boolean onlyServerSettings = globals.mOnlyServerSettings;
        int bossHunterCount = globals.mBossHunterCount;
        Map<Byte, Globals.PlayerSettings> savedSettings = copyPlayerSettings();
        try {
            globals.mTournamentMode = true;
            globals.mBossMode = true;
            globals.mAllowPlayerSettings = false;
            globals.mOnlyServerSettings = true;
            globals.mBossHunterCount = -1;
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
            } finally {
                globals.mPlayerSettingsSemaphore.release();
            }
            addClient(2, new MemorySocket());
            addClient(3, new MemorySocket());

            dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });

            for (MemorySocket socket : sockets) {
                DataInputStream frames = new DataInputStream(
                        new ByteArrayInputStream(socket.bytes.toByteArray()));
                JSONObject settings = readJsonFrame(frames.readUTF());
                JSONObject start = readJsonFrame(frames.readUTF());
                assertTrue(settings.getBoolean(TcpServer.JSON_BOSS_MODE));
                JSONArray players = settings.getJSONArray(TcpServer.JSON_PLAYERSETTINGS);
                assertEquals(Globals.BOSS_BASE_HEALTH + 2 * Globals.BOSS_HEALTH_PER_HUNTER,
                        playerSettings(players, Globals.BOSS_PLAYER_ID)
                                .getInt(TcpServer.JSON_HEALTH));
                assertEquals(Globals.BOSS_HUNTER_HEALTH,
                        playerSettings(players, 2).getInt(TcpServer.JSON_HEALTH));
                assertEquals(Globals.FIRING_MODE_OUTDOOR_WITH_CONE,
                        playerSettings(players, Globals.BOSS_PLAYER_ID).getInt(TcpServer.JSON_FIRING_MODE));
                assertEquals(Globals.FIRING_MODE_OUTDOOR_NO_CONE,
                        playerSettings(players, 2).getInt(TcpServer.JSON_FIRING_MODE));
                assertTrue(start.getLong(TcpServer.JSON_GAMESTART) > 0);
            }
            assertEquals(2, globals.mBossHunterCount);
        } finally {
            globals.mTournamentMode = tournamentMode;
            globals.mBossMode = bossMode;
            globals.mAllowPlayerSettings = allowPlayerSettings;
            globals.mOnlyServerSettings = onlyServerSettings;
            globals.mBossHunterCount = bossHunterCount;
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
                globals.mPlayerSettings.putAll(savedSettings);
            } finally {
                globals.mPlayerSettingsSemaphore.release();
            }
        }
    }

    @Test
    public void reconnectRequiresClockResynchronizationBeforeQueuedStart() throws Exception {
        MemorySocket replacement = new MemorySocket();
        sockets.add(replacement);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> {
            Object client = clients.get(1);
            Method rejoin = client.getClass().getDeclaredMethod("rejoin", Socket.class);
            rejoin.setAccessible(true);
            rejoin.invoke(client, replacement);
        });
        assertEquals(0, replacement.bytes.size());
        assertTrue(server.events.isEmpty());
        assertFalse(server.arePlayerClocksSynchronized());
    }

    @Test
    public void committedStartIsNotAdvertisedAsLobbyBeforeHostUiReceivesIt() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        sockets.get(0).bytes.reset();
        dispatchThenChange(() -> server.sendAllGameInfo(TcpServer.SEND_ALL), () -> { });
        JSONObject roster = readJson(sockets.get(0));
        assertEquals(Globals.GAME_STATE_RUNNING, roster.getInt(TcpServer.JSON_GAMESTATE));
        assertEquals(server.getScheduledGameStart().getLongExtra(NetMsg.INTENT_START_AT, 0),
                roster.getLong(TcpServer.JSON_GAMESTART));
    }

    @Test
    public void queuedLobbyBroadcastCannotCancelANewerSynchronizedStart() throws Exception {
        assertQueuedRosterKeepsStart(TcpServer.SEND_ALL);
    }

    @Test
    public void queuedPersonalizedLobbyUpdateCannotCancelANewerSynchronizedStart() throws Exception {
        assertQueuedRosterKeepsStart(1);
    }

    private void assertQueuedRosterKeepsStart(int playerID) throws Exception {
        addClient(2, new MemorySocket());
        List<Thread> tasks;
        clientsLock.acquire();
        try {
            assertTrue(server.startGame());
            queuedWorker();
            // The host UI still shows the lobby while the start is queued.
            // Registration or settings changes can queue a roster behind it.
            server.sendAllGameInfo(playerID);
            tasks = captureClientTasks();
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (clientsLock.getQueueLength() < 2 && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertEquals("Both senders must be queued before releasing the lock", 2, clientsLock.getQueueLength());
        } finally { clientsLock.release(); }
        for (Thread task : tasks) {
            task.join(2000);
            assertFalse("Queued start/roster task did not finish", task.isAlive());
        }
        awaitOutboundWrites();
        for (MemorySocket socket : sockets) {
            DataInputStream frames = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray()));
            int prefixLength = TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length();
            JSONObject start = new JSONObject(frames.readUTF().substring(prefixLength));
            assertFalse("The test must deliver the start before the roster", start.has(TcpServer.JSON_PLAYERS));
            JSONObject roster = new JSONObject(frames.readUTF().substring(prefixLength));
            assertEquals("An old lobby update would end the just-started client round",
                    Globals.GAME_STATE_RUNNING, roster.getInt(TcpServer.JSON_GAMESTATE));
            assertEquals(start.getLong(TcpServer.JSON_ROUND_ID), roster.getLong(TcpServer.JSON_ROUND_ID));
            assertEquals(start.getString(TcpServer.JSON_ROUND_TOKEN), roster.getString(TcpServer.JSON_ROUND_TOKEN));
            assertEquals(start.getLong(TcpServer.JSON_GAMESTART), roster.getLong(TcpServer.JSON_GAMESTART));
            assertEquals(start.getLong(TcpServer.JSON_GAMEDURATION), roster.getLong(TcpServer.JSON_GAMEDURATION));
            assertEquals(0, frames.available());
        }
    }

    @Test
    public void queuedRosterCannotRestoreDepartedPlayersOrOldNames() throws Exception {
        addClient(2, new MemorySocket());
        dispatchThenChange(() -> server.sendAllGameInfo(1), () -> {
            closeClient(2);
            clients.remove(2);
            InetAddress departed = Globals.getInstance().mTeamIPMap.remove((byte) 2);
            Globals.getInstance().mIPTeamMap.remove(departed);
            Globals.getInstance().mTeamPlayerNameMap.remove((byte) 2);
            Globals.getInstance().mTeamPlayerNameMap.put((byte) 1, "Current name");
        });
        JSONObject roster = readJson(sockets.get(0));
        JSONArray players = roster.getJSONArray(TcpServer.JSON_PLAYERS);
        assertEquals("A queued roster restored a player who already left", 1, players.length());
        assertEquals("Current name", players.getJSONObject(0).getString(TcpServer.JSON_PLAYERNAME));
        assertEquals(0, sockets.get(1).bytes.size());
    }

    @Test
    public void queuedPersonalizedRosterCannotRollBackScores() throws Exception {
        dispatchThenChange(() -> server.sendAllGameInfo(1), () -> {
            set(clients.get(1), "points", 7);
            set(clients.get(1), "eliminated", 3);
        });
        JSONObject roster = readJson(sockets.get(0));
        JSONObject score = roster.getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE);
        assertEquals(7, score.getInt(TcpServer.JSON_PLAYERPOINTS));
        assertEquals(3, score.getInt(TcpServer.JSON_PLAYERELIMINATED));
        assertEquals(7, score.getInt(TcpServer.JSON_TEAMPOINTS));
    }

    @Test
    public void pausedPeerHostCanRecoverItsStartAfterLobbyConnectionCloses() throws Exception {
        Globals.getInstance().mPlayerID = 2;
        Globals.getInstance().mLocalLobbyReady = true;
        server.setDedicated(false);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        Intent scheduled = server.getScheduledGameStart();
        assertTrue(scheduled != null);
        assertEquals(server.notifications.get(0).getLongExtra(NetMsg.INTENT_START_AT, 0),
                scheduled.getLongExtra(NetMsg.INTENT_START_AT, 0));
        server.clearScheduledStart();
        assertTrue(server.getScheduledGameStart() == null);
    }

    @Test
    public void busyFirstPlayerCannotStarveClockReadinessOfTheThirtySecondPlayer() throws Exception {
        for (int id = 2; id <= 32; id++) addClient(id, new MemorySocket());
        set(clients.get(32), "clockSynchronized", false);
        ByteArrayOutputStream flood = new ByteArrayOutputStream();
        DataOutputStream first = new DataOutputStream(flood);
        for (int i = 0; i < 500; i++)
            first.writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                    + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, i));
        ByteArrayOutputStream ready = new ByteArrayOutputStream();
        DataOutputStream lastPlayer = new DataOutputStream(ready);
        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++)
            lastPlayer.writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                    + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 1000 + i));
        lastPlayer.writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                + new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        set(clients.get(1), "in", new DataInputStream(new ByteArrayInputStream(flood.toByteArray())));
        set(clients.get(32), "in", new DataInputStream(new ByteArrayInputStream(ready.toByteArray())));
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Thread reader = new Thread((Runnable) constructor.newInstance(server));
        reader.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        workers.add(reader);
        long deadline = SystemClock.elapsedRealtime() + 1000;
        reader.start();
        while (!server.arePlayerClocksSynchronized() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        assertTrue("A busy client blocked another player's clock readiness for over a second",
                server.arePlayerClocksSynchronized());
    }

    @Test
    public void finalRoundReportPrecedesEndAndFreezesAccuracyAndStreaks() throws Exception {
        String token = prepareAnalyticsRound();
        MemorySocket shooter = (MemorySocket) sockets.get(0);
        String shooterIdentity = (String) get(clients.get(1), "playerIdentity");
        parseClock(1, scopedTelemetry(token, 1, 0, "shot")
                .put("count", 10).put("total", 10));
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        parseClock(17, scopedTelemetry(token, 2, 0, "elimination").put("attacker", 1));
        set(clients.get(17), "awaitingRespawn", false);
        parseClock(17, scopedTelemetry(token, 3, 1, "hit").put("attacker", 1));
        parseClock(17, scopedTelemetry(token, 4, 1, "elimination").put("attacker", 1));
        parseClock(1, scopedTelemetry(token, 2, 0, "elimination").put("attacker", 17));
        awaitBalancedModeTasks();
        dispatchThenChange(server::endGame, () -> { });
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(shooter.bytes.toByteArray()));
        RoundReport report = null;
        boolean sawEnd = false;
        while (frames.available() > 0) {
            String frame = frames.readUTF();
            if (frame.endsWith(TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME)) {
                assertTrue("Host closed the round before its personal report", report != null);
                sawEnd = true;
            } else if (frame.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON)) {
                JSONObject json = readJsonFrame(frame);
                if (json.has(RoundReport.KEY)) report = RoundReport.fromJson(json.getJSONObject(RoundReport.KEY));
            }
        }
        assertTrue(sawEnd);
        assertEquals(token, report.roundToken);
        assertEquals(2, report.kills);
        assertEquals(1, report.deaths);
        assertEquals(10, report.shots);
        assertEquals(2, report.hits);
        assertEquals(2, report.bestKillStreak);
        assertEquals(1, report.bestDeathStreak);
        assertTrue(report.complete);
        assertTrue(report.durationMs >= 1000);
        int previousBytes = shooter.bytes.size();
        assertFalse("Repeated completion queued another ENDGAME", server.requestOrderedRoundEnd());
        assertEquals(previousBytes, shooter.bytes.size());
        Map<?, ?> cached = (Map<?, ?>) get(server, "mCompletedRoundReports");
        RoundReport frozen = (RoundReport) cached.get(shooterIdentity);
        // The frozen report remains immutable while its lobby socket stays
        // admitted, including repeated completion requests.
        assertEquals(token, frozen.roundToken);
        assertEquals(report.kills, frozen.kills);
    }

    @Test
    public void scopedCumulativeShotsRejectCountdownOldRoundAndMalformedFrames() throws Exception {
        String token = prepareAnalyticsRound();
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() + 5000);
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 4).put("total", 4));
        assertEquals(0, server.getScore((byte) 1).shots);
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 1000);
        parseClock(1, scopedTelemetry("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 1, 0, "shot")
                .put("count", 4).put("total", 4));
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("generation", 1)
                .put("count", 4).put("total", 4));
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 4).put("total", "4"));
        JSONObject partial = scopedTelemetry(token, 1, 0, "shot").put("count", 4).put("total", 4);
        partial.remove("life");
        parseClock(1, partial);
        assertEquals(0, server.getScore((byte) 1).shots);
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 4).put("total", 12));
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 4).put("total", 99));
        parseClock(1, scopedTelemetry(token, 2, 0, "shot").put("count", 4).put("total", 12));
        assertEquals(12, server.getScore((byte) 1).shots);
    }

    @Test
    public void scopedVictimReportsDoNotReplayKillsAndGrenadesDoNotImproveAccuracy() throws Exception {
        String token = prepareAnalyticsRound();
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        Globals.getInstance().mGrenadePairings[2] = 1;
        parseClock(17, scopedTelemetry(token, 2, 0, "grenadehit").put("grenadeID", 2));
        parseClock(17, scopedTelemetry(token, 3, 0, "elimination").put("attacker", 1));
        set(clients.get(17), "awaitingRespawn", false);
        parseClock(17, scopedTelemetry(token, 4, 0, "elimination").put("attacker", 1));
        parseClock(17, scopedTelemetry(token, 5, 0, "hit").put("attacker", 1));
        assertEquals(1, server.getScore((byte) 1).points);
        assertEquals(1, server.getScore((byte) 1).hits);
        assertEquals(1, server.getScore((byte) 17).eliminated);
    }

    @Test
    public void missingTelemetrySequenceMakesAllAccuracyReportsIncomplete() throws Exception {
        String token = prepareAnalyticsRound();
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 2).put("total", 2));
        parseClock(17, scopedTelemetry(token, 2, 0, "hit").put("attacker", 1));
        dispatchThenChange(server::endGame, () -> { });
        Map<?, ?> reports = (Map<?, ?>) get(server, "mCompletedRoundReports");
        assertEquals(2, reports.size());
        for (Object value : reports.values()) assertFalse(((RoundReport) value).complete);
    }

    @Test
    public void countdownCancellationDoesNotCreateRatedRoundReports() throws Exception {
        prepareAnalyticsRound();
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() + 5000);
        dispatchThenChange(server::endGame, () -> { });
        assertTrue(((Map<?, ?>) get(server, "mCompletedRoundReports")).isEmpty());
    }

    @Test
    public void recycledSeatDoesNotInheritAnotherPlayersShotTotals() throws Exception {
        String token = prepareAnalyticsRound();
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 5).put("total", 5));
        String previousIdentity = (String) get(clients.get(1), "playerIdentity");
        Globals.getInstance().mRoundRoster.leave(1);
        String replacementIdentity = java.util.UUID.randomUUID().toString();
        com.simplecoil.protocol.RoundRoster.Member replacement = Globals.getInstance().mRoundRoster.join(
                replacementIdentity, 1, 2, false, false, 0);
        assertEquals(1, replacement.id);
        addClient(2, 1, new MemorySocket());
        set(clients.get(2), "playerIdentity", replacementIdentity);
        clients.remove(1);
        parseClock(2, scopedTelemetry(token, 1, 0, "shot").put("generation", replacement.generation)
                .put("count", 2).put("total", 2));
        dispatchThenChange(server::endGame, () -> { });
        Map<?, ?> reports = (Map<?, ?>) get(server, "mCompletedRoundReports");
        assertEquals(5, ((RoundReport) reports.get(previousIdentity)).shots);
        assertEquals(2, ((RoundReport) reports.get(replacementIdentity)).shots);
    }

    @Test
    public void remoteTelemetryContinuesWhileThePlayingPhoneHostIsRespawning() throws Exception {
        String token = prepareAnalyticsRound();
        server.setDedicated(false);
        Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 5).put("total", 5));
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        parseClock(17, scopedTelemetry(token, 2, 0, "elimination").put("attacker", 1));
        assertEquals(5, server.getScore((byte) 1).shots);
        assertEquals(1, server.getScore((byte) 1).hits);
        assertEquals(1, server.getScore((byte) 1).points);
        assertEquals(1, server.getScore((byte) 17).eliminated);
    }

    @Test
    public void participantDisconnectKeepsObservedTotalsButMarksTheRoundIncomplete() throws Exception {
        String token = prepareAnalyticsRound();
        String shooterIdentity = (String) get(clients.get(1), "playerIdentity");
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 5).put("total", 5));
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(server);
        Object departing = clients.get(17);
        Method remove = handler.getClass().getDeclaredMethod("removeClient", departing.getClass(),
                Integer.class, boolean.class);
        remove.setAccessible(true);
        remove.invoke(handler, departing, 17, false);
        awaitBalancedModeTasks();
        dispatchThenChange(server::endGame, () -> { });
        Map<?, ?> reports = (Map<?, ?>) get(server, "mCompletedRoundReports");
        RoundReport shooter = (RoundReport) reports.get(shooterIdentity);
        assertEquals(5, shooter.shots);
        assertEquals(1, shooter.hits);
        assertFalse(shooter.complete);
    }

    @Test
    public void queuedHitAndDeathNeverCreditAReplacementInTheAttackersOldSeat() throws Exception {
        String token = prepareAnalyticsRound();
        parseClock(1, scopedTelemetry(token, 1, 0, "shot").put("count", 5).put("total", 5));
        JSONObject queuedHit = scopedTelemetry(token, 1, 0, "hit").put("attacker", 1);
        JSONObject queuedDeath = scopedTelemetry(token, 2, 0, "elimination").put("attacker", 1);
        Globals.getInstance().mRoundRoster.leave(1);
        String replacementIdentity = java.util.UUID.randomUUID().toString();
        com.simplecoil.protocol.RoundRoster.Member replacement = Globals.getInstance().mRoundRoster.join(
                replacementIdentity, 1, 2, false, false, 0);
        assertEquals(1, replacement.id);
        addClient(2, 1, new MemorySocket());
        set(clients.get(2), "playerIdentity", replacementIdentity);
        clients.remove(1);
        parseClock(17, queuedHit);
        parseClock(17, queuedDeath);
        assertEquals(0, server.getScore((byte) 1).hits);
        assertEquals(0, server.getScore((byte) 1).points);
        assertEquals(1, server.getScore((byte) 17).eliminated);
        assertTrue("A stale source must not prevent the victim's dedicated respawn",
                (boolean) get(clients.get(17), "awaitingRespawn"));
        assertFalse((boolean) get(server, "mAnalyticsComplete"));
    }

    @Test
    public void malformedAttackerGenerationDoesNotConsumeAValidVictimSequence() throws Exception {
        String token = prepareAnalyticsRound();
        JSONObject missing = scopedTelemetry(token, 1, 0, "hit").put("attacker", 1);
        missing.remove("attackergeneration");
        parseClock(17, missing);
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1)
                .put("attackergeneration", "0"));
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1)
                .put("attackergeneration", 256));
        assertEquals(0, server.getScore((byte) 1).hits);
        parseClock(17, scopedTelemetry(token, 1, 0, "hit").put("attacker", 1));
        assertEquals(1, server.getScore((byte) 1).hits);
        assertTrue((boolean) get(server, "mAnalyticsComplete"));
        JSONObject malformedDeath = scopedTelemetry(token, 2, 0, "elimination").put("attacker", 1);
        malformedDeath.remove("attackergeneration");
        parseClock(17, malformedDeath);
        assertEquals(0, server.getScore((byte) 17).eliminated);
        parseClock(17, scopedTelemetry(token, 2, 0, "elimination").put("attacker", 1));
        assertEquals(1, server.getScore((byte) 17).eliminated);
        assertEquals(1, server.getScore((byte) 1).points);
    }

    @Test
    public void qrRejoinUsesMatchingOriginalHistoryBaselineRatherThanPartialTotals() throws Exception {
        String token = prepareAnalyticsRound();
        String identity = (String) get(clients.get(17), "playerIdentity");
        com.simplecoil.protocol.RoundRoster.Member member = Globals.getInstance().mRoundRoster.seat(17);
        member.kills = 2;
        member.deaths = 1;
        Globals.getInstance().mRoundRoster.leave(17);
        clients.remove(17);
        set(server, "mAnalyticsComplete", false); // A replacement control host has partial coverage.
        addClient(2, 0, returningAnalyticsSocket());
        JSONObject registration = historyRegistration(identity, token).put(LiveJoin.TEAM, 2);
        JSONObject missing = new JSONObject(registration.toString());
        missing.remove("historybaselinedeaths");
        parseClock(2, missing);
        parseClock(2, new JSONObject(registration.toString()).put("historybaselinekills", "5"));
        assertEquals(0, ((Byte) get(clients.get(2), "mPlayerID")).byteValue());
        parseClock(2, registration);
        assertEquals(5, get(clients.get(2), "priorKills"));
        assertEquals(3, get(clients.get(2), "priorDeaths"));
        awaitBalancedModeTasks();
        dispatchThenChange(server::endGame, () -> { });
        RoundReport report = (RoundReport) ((Map<?, ?>) get(server, "mCompletedRoundReports")).get(identity);
        assertEquals(com.simplecoil.protocol.RoundStatistics.skillIndex(5, 3), report.skillBefore);
        assertEquals(com.simplecoil.protocol.RoundStatistics.skillIndex(7, 4), report.skillAfter);
    }

    @Test
    public void oldHistoryBaselineHintDoesNotChangeCurrentLobbyBalanceHistory() throws Exception {
        prepareAnalyticsRound();
        String identity = (String) get(clients.get(17), "playerIdentity");
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        set(server, "mStartAnnounced", false);
        set(server, "mRoundToken", null);
        addClient(2, 0, returningAnalyticsSocket());
        parseClock(2, historyRegistration(identity, "dddddddd-dddd-dddd-dddd-dddddddddddd"));
        assertEquals(7, get(clients.get(17), "priorKills"));
        assertEquals(4, get(clients.get(17), "priorDeaths"));
        awaitBalancedModeTasks();
    }

    private JSONObject historyRegistration(String identity, String token) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, 17).put(TcpServer.JSON_PLAYERNAME, "Returning")
                .put(LiveJoin.IDENTITY, identity).put(TcpServer.JSON_REJOIN, true)
                .put(TcpServer.JSON_ROUND_ANALYTICS, true).put(TcpServer.JSON_PRIOR_KILLS, 7)
                .put(TcpServer.JSON_PRIOR_DEATHS, 4).put("historyroundtoken", token)
                .put("historybaselinekills", 5).put("historybaselinedeaths", 3);
    }

    private MemorySocket returningAnalyticsSocket() {
        return new MemorySocket() {
            @Override public InetAddress getInetAddress() {
                try { return InetAddress.getByName("127.0.0.17"); }
                catch (Exception invalid) { throw new AssertionError(invalid); }
            }
        };
    }

    private String prepareAnalyticsRound() throws Exception {
        String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        addClient(17, new MemorySocket());
        com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
        for (int id : new int[]{1, 17}) {
            String identity = (String) get(clients.get(id), "playerIdentity");
            roster.restore(new com.simplecoil.protocol.RoundRoster.Member(identity, id,
                    id == 1 ? 1 : 2, 0));
        }
        Globals.getInstance().mRoundRoster = roster;
        Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
        set(server, "mRoundToken", token);
        set(server, "mStartAnnounced", true);
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 1000);
        Method init = TcpServer.class.getDeclaredMethod("initializeRoundAnalyticsLocked", String.class, boolean.class);
        init.setAccessible(true);
        init.invoke(server, token, true);
        return token;
    }

    private JSONObject scopedTelemetry(String token, long sequence, int life, String event) throws Exception {
        JSONObject frame = new JSONObject().put("roundtoken", token).put("generation", 0)
                .put("life", life).put("telemetryseq", sequence).put("telemetry", event);
        if ("hit".equals(event) || "elimination".equals(event)) frame.put("attackergeneration", 0);
        return frame;
    }

    private void parseClock(int connection, JSONObject json) throws Exception {
        Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(server);
        Object client = clients.get(connection);
        Method parser = handler.getClass().getDeclaredMethod("parsePlayerInfo", String.class, client.getClass());
        parser.setAccessible(true);
        parser.invoke(handler, json.toString(), client);
    }

    private JSONObject readJson(MemorySocket socket) throws Exception {
        awaitOutboundWrites();
        String message = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray())).readUTF();
        return readJsonFrame(message);
    }

    private JSONObject readJsonFrame(String message) throws Exception {
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        return new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
    }

    private JSONObject playerSettings(JSONArray settings, int playerID) throws Exception {
        for (int index = 0; index < settings.length(); index++) {
            JSONObject player = settings.getJSONObject(index);
            if (player.getInt(TcpServer.JSON_PLAYERID) == playerID)
                return player;
        }
        throw new AssertionError("Missing settings for player " + playerID);
    }

    private Map<Byte, Globals.PlayerSettings> copyPlayerSettings() {
        Map<Byte, Globals.PlayerSettings> copy = new HashMap<>();
        Globals.getmPlayerSettingsSemaphore();
        try {
            for (Map.Entry<Byte, Globals.PlayerSettings> entry
                    : Globals.getInstance().mPlayerSettings.entrySet()) {
                Globals.PlayerSettings source = entry.getValue();
                Globals.PlayerSettings target = new Globals.PlayerSettings();
                target.health = source.health;
                target.shots = source.shots;
                target.reloadTime = source.reloadTime;
                target.reloadOnEmpty = source.reloadOnEmpty;
                target.spawnTime = source.spawnTime;
                target.damage = source.damage;
                target.overrideLives = source.overrideLives;
                target.lives = source.lives;
                target.allowShotModeSingle = source.allowShotModeSingle;
                target.allowShotModeBurst3 = source.allowShotModeBurst3;
                target.allowShotModeAuto = source.allowShotModeAuto;
                target.firingMode = source.firingMode;
                copy.put(entry.getKey(), target);
            }
        } finally {
            Globals.getInstance().mPlayerSettingsSemaphore.release();
        }
        return copy;
    }

    private void assertStartFrame(String frame) throws Exception {
        assertTrue(frame.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        JSONObject start = new JSONObject(frame.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
        assertTrue(start.getLong(TcpServer.JSON_ROUND_ID) > 0);
        assertTrue(start.getLong(TcpServer.JSON_GAMESTART) > 0);
        assertEquals(0, start.getLong(TcpServer.JSON_GAMEDURATION));
    }

    private void addClient(int playerID, MemorySocket socket) throws Exception {
        addClient(playerID, playerID, socket);
    }

    private void addClient(int connectionID, int playerID, MemorySocket socket) throws Exception {
        sockets.add(socket);
        Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
        Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object client = constructor.newInstance(server);
        Method initialize = type.getDeclaredMethod("initialize", Socket.class, int.class);
        initialize.setAccessible(true);
        assertTrue((boolean) initialize.invoke(client, socket, connectionID));
        set(client, "mPlayerID", (byte) playerID);
        set(client, "clockSynchronized", true);
        clients.put(connectionID, client);
        if (playerID == 0) return;
        InetAddress address = InetAddress.getByAddress(new byte[]{127, 0, 0, (byte) connectionID});
        Globals.getInstance().mTeamIPMap.put((byte) playerID, address);
        Globals.getInstance().mIPTeamMap.put(address, (byte) playerID);
        Globals.getInstance().mTeamPlayerNameMap.put((byte) playerID, "Player " + playerID);
    }

    private void closeClient(int playerID) throws Exception {
        Object client = clients.get(playerID);
        Method close = client.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(client);
    }

    private Queue<?> queuedMessages(int playerID) throws Exception {
        Object client = clients.get(playerID);
        Field queue = client.getClass().getDeclaredField("messageQueue");
        queue.setAccessible(true);
        synchronized (client) {
            return new java.util.LinkedList<>((Queue<?>) queue.get(client));
        }
    }

    /** Sender task completion only means its frames were accepted by each FIFO. */
    private void awaitOutboundWrites() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3_000;
        for (Object client : new ArrayList<>(clients.values())) {
            while (true) {
                Thread writer;
                synchronized (client) { writer = (Thread) get(client, "writeWorker"); }
                if (writer == null) break;
                if (!workers.contains(writer)) workers.add(writer);
                writer.setUncaughtExceptionHandler((thread, failure) -> failures.add(failure));
                long remaining = deadline - SystemClock.elapsedRealtime();
                assertTrue("Per-client outbound writer did not drain", remaining > 0);
                writer.join(Math.min(remaining, 100));
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = (target instanceof TcpServer ? TcpServer.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = (target instanceof TcpServer ? TcpServer.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static <K, V> Map<K, V> copyAndClear(Map<K, V> map, Semaphore lock) throws Exception {
        lock.acquire();
        try {
            Map<K, V> copy = new HashMap<>(map);
            map.clear();
            return copy;
        } finally { lock.release(); }
    }

    private static <K, V> void restore(Map<K, V> map, Map<K, V> original, Semaphore lock) throws Exception {
        lock.acquire();
        try { map.clear(); map.putAll(original); } finally { lock.release(); }
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class WaitingSemaphore extends Semaphore {
        WaitingSemaphore() { super(1); }
        Collection<Thread> waiters() { return getQueuedThreads(); }
    }

    private static final class RecordingServer extends TcpServer {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Intent> notifications = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) {
            notifications.add(new Intent(intent));
            events.add(intent.getAction());
        }
    }

    private static class MemorySocket extends Socket {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean closed;
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return bytes; }
        @Override public void close() { closed = true; }
    }

    /** Holds one frame while allowing its socket to remain usable for the next frame. */
    private static final class GateSocket extends MemorySocket {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        private boolean first = true;

        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    write(new byte[] {(byte) value}, 0, 1);
                }
                @Override public void write(byte[] values, int offset, int length) throws IOException {
                    if (first) {
                        first = false;
                        writing.countDown();
                        try {
                            if (!released.await(2, TimeUnit.SECONDS))
                                throw new IOException("Controlled frame was not released");
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Controlled writer was interrupted", cancelled);
                        }
                    }
                    if (closed) throw new IOException("Controlled socket is closed");
                    bytes.write(values, offset, length);
                }
            };
        }

        @Override public void close() { super.close();released.countDown(); }
    }

    private static final class BlockingSocket extends MemorySocket {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        volatile Thread writer;
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    writer = Thread.currentThread();
                    writing.countDown();
                    boolean interrupted = false;
                    while (!closed) {
                        try { released.await(); }
                        catch (InterruptedException e) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    throw new IOException("Socket closed while writing");
                }
            };
        }
        @Override public void close() { super.close(); released.countDown(); }
    }
}
