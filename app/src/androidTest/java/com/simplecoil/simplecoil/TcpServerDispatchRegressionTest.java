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

    @Before
    public void setUp() throws Exception {
        Globals globals = Globals.getInstance();
        originalGameMode = globals.mGameMode;
        originalGameState = globals.mGameState;
        originalGameLimit = globals.mGameLimit;
        originalUseGPS = globals.mUseGPS;
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
        restore(globals.mTeamIPMap, originalAddresses, globals.mTeamIPMapSemaphore);
        restore(globals.mIPTeamMap, originalPlayers, globals.mIPTeamMapSemaphore);
        restore(globals.mTeamPlayerNameMap, originalNames, globals.mTeamPlayerNameSemaphore);
        restore(globals.mGPSData, originalLocations, globals.mGPSDataSemaphore);
        System.arraycopy(originalPairings, 0, globals.mGrenadePairings, 0, originalPairings.length);
        globals.mGameMode = originalGameMode;
        globals.mGameState = originalGameState;
        globals.mGameLimit = originalGameLimit;
        globals.mUseGPS = originalUseGPS;
        for (Thread worker : workers) assertFalse("Dispatch task survived cleanup", worker.isAlive());
        assertTrue("Dispatch task crashed: " + failures, failures.isEmpty());
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
    public void endingGameKeepsRegistrationsLockedUntilSharedRosterIsCleared() throws Exception {
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
            assertEquals("New registrations could be erased by unfinished round cleanup", 0,
                    clientsLock.availablePermits());
        } finally {
            if (clientLockHeld) clientsLock.release();
            locations.release();
            if (worker != null) worker.join(1000);
        }
        assertTrue(clients.isEmpty());
        assertTrue(Globals.getInstance().mTeamPlayerNameMap.isEmpty());
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
        DataInputStream messages = new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray()));
        assertStartFrame(messages.readUTF());
        assertEquals("A player received multiple starts for one round", 0, messages.available());
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
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
    public void completedRoundCleanupAllowsTheNextRoundToStart() throws Exception {
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        dispatchThenChange(server::endGame, () -> { });
        MemorySocket nextRound = new MemorySocket();
        addClient(1, nextRound);
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(nextRound.bytes.size() > 0);
        assertEquals(2, java.util.Collections.frequency(server.events, NetMsg.NETMSG_STARTGAME));
        assertEquals(1, java.util.Collections.frequency(server.events, NetMsg.NETMSG_ENDGAME));
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
        clientsLock.acquire();
        Thread start;
        try {
            server.endGame();
            Thread end = queuedWorker();
            end.interrupt();
            end.join(1000);
            assertFalse(end.isAlive());
            assertTrue("A cancelled cleanup permanently blocked new starts", server.startGame());
            start = queuedWorker();
        } finally { clientsLock.release(); }
        start.join(1000);
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertFalse(server.events.contains(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void aLaterRoundCanStillBeEndedAfterCleanupCompletes() throws Exception {
        dispatchThenChange(server::endGame, () -> { });
        MemorySocket nextRound = new MemorySocket();
        addClient(2, nextRound);
        dispatchThenChange(server::endGame, () -> { });
        assertTrue(nextRound.closed);
        assertTrue(clients.isEmpty());
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
        assertStartFrame(new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void teamSendStillExcludesTheSenderAndOpponents() throws Exception {
        MemorySocket teammate = new MemorySocket();
        MemorySocket enemy = new MemorySocket();
        addClient(2, teammate);
        addClient(11, enemy);
        clientsLock.acquire();
        Thread worker;
        try {
            sendTeam(true, false);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
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
        addClient(11, new MemorySocket());
        closeClient(1);
        closeClient(2);
        closeClient(11);
        clientsLock.acquire();
        Thread worker;
        try {
            sendTeam(true, false);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertTrue(queuedMessages(1).isEmpty());
        assertTrue(queuedMessages(11).isEmpty());
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
    public void failedStartWritesDoNotPublishAStartConfirmation() throws Exception {
        set(clients.get(1), "out", new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        }));
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue("Server confirmed a start that no client received", server.events.isEmpty());
        assertTrue(sockets.get(0).closed);
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
        parseClock(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        assertTrue(server.arePlayerClocksSynchronized());
        dispatchThenChange(() -> assertTrue(server.startGame()), () -> { });
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
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
        } finally { Globals.getInstance().mTimeLimit = originalTime; }
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
        for (MemorySocket socket : sockets) {
            DataInputStream frames = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray()));
            int prefixLength = TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length();
            JSONObject start = new JSONObject(frames.readUTF().substring(prefixLength));
            assertFalse("The test must deliver the start before the roster", start.has(TcpServer.JSON_PLAYERS));
            JSONObject roster = new JSONObject(frames.readUTF().substring(prefixLength));
            assertEquals("An old lobby update would end the just-started client round",
                    Globals.GAME_STATE_RUNNING, roster.getInt(TcpServer.JSON_GAMESTATE));
            assertEquals(start.getLong(TcpServer.JSON_ROUND_ID), roster.getLong(TcpServer.JSON_ROUND_ID));
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
    public void busyFirstPlayerCannotStarveClockReadinessOfTheTwentiethPlayer() throws Exception {
        for (int id = 2; id <= 20; id++) addClient(id, new MemorySocket());
        set(clients.get(20), "clockSynchronized", false);
        ByteArrayOutputStream flood = new ByteArrayOutputStream();
        DataOutputStream first = new DataOutputStream(flood);
        for (int i = 0; i < 500; i++)
            first.writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                    + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, i));
        ByteArrayOutputStream ready = new ByteArrayOutputStream();
        new DataOutputStream(ready).writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                + new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
        set(clients.get(1), "in", new DataInputStream(new ByteArrayInputStream(flood.toByteArray())));
        set(clients.get(20), "in", new DataInputStream(new ByteArrayInputStream(ready.toByteArray())));
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
        String message = new DataInputStream(new ByteArrayInputStream(socket.bytes.toByteArray())).readUTF();
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        return new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
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
        return (Queue<?>) queue.get(client);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = (target instanceof TcpServer ? TcpServer.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
