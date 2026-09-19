package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
            Globals.getInstance().mGPSData.put((byte) 9, replacement);
            Globals.getInstance().mGrenadePairings[1] = 9;
        } finally {
            if (clientLockHeld) clientsLock.release();
            locations.release();
            if (worker != null) worker.join(1000);
        }
        assertFalse(worker.isAlive());
        assertSame("Old end task erased a replacement round's GPS", replacement,
                Globals.getInstance().mGPSData.get((byte) 9));
        assertEquals(9, Globals.getInstance().mGrenadePairings[1]);
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
        assertEquals(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME,
                new DataInputStream(new ByteArrayInputStream(sockets.get(0).bytes.toByteArray())).readUTF());
        assertTrue(server.events.contains(NetMsg.NETMSG_STARTGAME));
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void teamSendStillExcludesTheSenderAndOpponents() throws Exception {
        MemorySocket teammate = new MemorySocket();
        MemorySocket enemy = new MemorySocket();
        addClient(2, teammate);
        addClient(9, enemy);
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
        addClient(9, new MemorySocket());
        closeClient(1);
        closeClient(2);
        closeClient(9);
        clientsLock.acquire();
        Thread worker;
        try {
            sendTeam(true, false);
            worker = queuedWorker();
        } finally { clientsLock.release(); }
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertTrue(queuedMessages(1).isEmpty());
        assertTrue(queuedMessages(9).isEmpty());
        assertEquals(1, queuedMessages(2).size());
        assertEquals("team", queuedMessages(2).peek());
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

    private void addClient(int playerID, MemorySocket socket) throws Exception {
        sockets.add(socket);
        Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
        Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object client = constructor.newInstance(server);
        Method initialize = type.getDeclaredMethod("initialize", Socket.class, int.class);
        initialize.setAccessible(true);
        assertTrue((boolean) initialize.invoke(client, socket, playerID));
        set(client, "mPlayerID", (byte) playerID);
        clients.put(playerID, client);
        InetAddress address = InetAddress.getByAddress(new byte[]{127, 0, 0, (byte) playerID});
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
        @Override public void sendBroadcast(Intent intent) { events.add(intent.getAction()); }
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
