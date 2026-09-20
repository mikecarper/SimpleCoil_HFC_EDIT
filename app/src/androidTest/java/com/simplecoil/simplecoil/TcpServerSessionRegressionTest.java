package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises service lifetime and real listening sockets on the Android device. */
@RunWith(AndroidJUnit4.class)
public class TcpServerSessionRegressionTest {
    private RecordingServer server;
    private Socket peer;
    private boolean originalUseGPS;
    private int originalGameState;
    private int originalGrenadePairing;

    @Before
    public void setUp() {
        server = createServer();
        Globals globals = Globals.getInstance();
        originalUseGPS = globals.mUseGPS;
        originalGameState = globals.mGameState;
        originalGrenadePairing = globals.mGrenadePairings[1];
        globals.mUseGPS = false;
        globals.mGameState = Globals.GAME_STATE_NONE;
    }

    @After
    public void tearDown() throws Exception {
        server.stopTcpServer();
        if (peer != null) peer.close();
        destroy(server);
        assertTrue("Server workers did not stop", awaitStopped(server, 3000));
        Globals globals = Globals.getInstance();
        globals.mUseGPS = originalUseGPS;
        globals.mGameState = originalGameState;
        globals.mGrenadePairings[1] = originalGrenadePairing;
        Globals.getmGPSDataSemaphore();
        try { globals.mGPSData.clear(); } finally { globals.mGPSDataSemaphore.release(); }
    }

    @Test
    public void stoppingOldInstanceDoesNotStopCurrentServer() throws Exception {
        connect();
        RecordingServer oldInstance = createServer();
        try {
            oldInstance.stopTcpServer();
            assertTrue("Old service stopped the current listener", (boolean) get(server, "keepListening"));
            server.sendTCPMessageAll("still-listening");
            assertEquals("still-listening", new DataInputStream(peer.getInputStream()).readUTF());
        } finally {
            destroy(oldInstance);
        }
    }

    @Test
    public void serviceDestructionClosesListenerAndConnectedClients() throws Exception {
        connect();
        destroy(server);
        assertTrue("Destroyed service retained its workers", awaitStopped(server, 1500));
        assertEquals(-1, peer.getInputStream().read());
        assertPortCanBeReused();
    }

    @Test
    public void hostCancellationReachesPlayersBeforeTheConnectionCloses() throws Exception {
        connect();
        Semaphore clientsLock = (Semaphore) get(server, "mClientDataSemaphore");
        clientsLock.acquire();
        try {
            // A pending roster/registration can delay the cancellation sender.
            server.cancelServer();
        } finally { clientsLock.release(); }
        assertEquals(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_SERVERCANCEL,
                new DataInputStream(peer.getInputStream()).readUTF());
        assertEquals(-1, peer.getInputStream().read());
        assertTrue("Cancelled host retained its workers", awaitStopped(server, 2000));
        assertPortCanBeReused();
    }

    @Test
    public void hostCancellationRetiresItsAnnouncedCountdown() throws Exception {
        connect();
        set(server, "mStartAnnounced", true);
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() + 10000);
        set(server, "mRoundSequence", 1L);
        assertNotNull(server.getScheduledGameStart());
        server.cancelServer();
        assertTrue("Cancelled host retained its workers", awaitStopped(server, 2000));
        assertNull("A cancelled server must not restore its old countdown on the next bind",
                server.getScheduledGameStart());
    }

    @Test
    public void expiredCancellationCannotCloseAReplacementLobby() throws Exception {
        connect();
        Semaphore clientsLock = (Semaphore) get(server, "mClientDataSemaphore");
        Runnable timeout;
        clientsLock.acquire();
        try {
            server.cancelServer();
            timeout = (Runnable) get(server, "mCancellationTimeout");
            assertNotNull(timeout);
        } finally { clientsLock.release(); }
        assertTrue("Cancelled host retained its workers", awaitStopped(server, 2000));
        peer.close();
        connect();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(timeout);
        assertTrue("An old cancellation timeout stopped the new listener", (boolean) get(server, "keepListening"));
        String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + "new session";
        server.sendTCPMessageAll(message);
        assertEquals(message, new DataInputStream(peer.getInputStream()).readUTF());
    }

    @Test
    public void destroyedServiceCannotRestart() throws Exception {
        destroy(server);
        server.startTcpServer();
        assertFalse((boolean) get(server, "keepListening"));
        assertNull(get(server, "mServerThread"));
    }

    @Test
    public void failedBindDoesNotResetExistingGameState() throws Exception {
        connect();
        Globals.GPSData gps = new Globals.GPSData();
        Globals.getmGPSDataSemaphore();
        try { Globals.getInstance().mGPSData.put((byte) 1, gps); }
        finally { Globals.getInstance().mGPSDataSemaphore.release(); }
        Globals.getInstance().mGrenadePairings[1] = 9;
        RecordingServer competingServer = createServer();
        try {
            competingServer.startTcpServer();
            assertTrue("Competing listener did not exit", awaitStopped(competingServer, 2000));
            assertSame(gps, Globals.getInstance().mGPSData.get((byte) 1));
            assertEquals(9, Globals.getInstance().mGrenadePairings[1]);
            assertTrue((boolean) get(server, "keepListening"));
            server.sendTCPMessageAll("original-session");
            assertEquals("original-session", new DataInputStream(peer.getInputStream()).readUTF());
        } finally {
            competingServer.stopTcpServer();
            destroy(competingServer);
        }
    }

    @Test
    public void stoppingDuringStartupReleasesPortWithoutUncaughtException() throws Exception {
        Semaphore gpsLock = Globals.getInstance().mGPSDataSemaphore;
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        gpsLock.acquire();
        Thread worker = null;
        try {
            server.startTcpServer();
            worker = (Thread) get(server, "mServerThread");
            worker.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
            assertTrue("Startup did not reach the held GPS lock", awaitQueued(gpsLock, 2000));
            server.stopTcpServer();
            worker.join(1000);
            assertFalse("Stopped startup kept waiting on shared state", worker.isAlive());
            assertTrue("Interrupted startup crashed: " + failures, failures.isEmpty());
            assertPortCanBeReused();
        } finally {
            gpsLock.release();
            if (worker != null) worker.join(2000);
        }
    }

    @Test
    public void stoppingWhileAcceptWaitsForClientLockDoesNotLeakSocketOrPermit() throws Exception {
        server.startTcpServer();
        Semaphore clientsLock = (Semaphore) get(server, "mClientDataSemaphore");
        clientsLock.acquire();
        try {
            peer = connectToPort();
            assertTrue("Accept did not reach the held client lock", awaitQueued(clientsLock, 2000));
            server.stopTcpServer();
            assertTrue("Stopped accept kept waiting on client lock", awaitStopped(server, 1000));
            assertEquals("Unacquired lock was released", 0, clientsLock.availablePermits());
            assertEquals(-1, peer.getInputStream().read());
            assertPortCanBeReused();
        } finally {
            clientsLock.release();
        }
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void stoppedServerCanStartAnotherCleanSession() throws Exception {
        connect();
        server.stopTcpServer();
        assertTrue(awaitStopped(server, 2000));
        assertEquals(-1, peer.getInputStream().read());
        peer.close();
        connect();
        server.sendTCPMessageAll("new-session");
        assertEquals("new-session", new DataInputStream(peer.getInputStream()).readUTF());
    }

    @Test
    public void destroyingClientWorkerWaitingForLockDoesNotAddAPermit() throws Exception {
        connect();
        Semaphore clientsLock = (Semaphore) get(server, "mClientDataSemaphore");
        clientsLock.acquire();
        try {
            assertTrue(awaitQueued(clientsLock, 2000));
            destroy(server);
            assertTrue("Destroyed client worker kept waiting", awaitStopped(server, 1000));
            assertEquals(0, clientsLock.availablePermits());
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            clientsLock.release();
        }
        assertEquals(1, clientsLock.availablePermits());
    }

    @Test
    public void stoppingClientWorkerWaitingForLockDoesNotRequireServiceDestruction() throws Exception {
        connect();
        Semaphore clientsLock = (Semaphore) get(server, "mClientDataSemaphore");
        clientsLock.acquire();
        try {
            assertTrue(awaitQueued(clientsLock, 2000));
            server.stopTcpServer();
            assertTrue("Stopped client worker kept waiting", awaitStopped(server, 1000));
            assertEquals(0, clientsLock.availablePermits());
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            clientsLock.release();
        }
        assertEquals(1, clientsLock.availablePermits());
        assertTrue(awaitStopped(server, 2000));
        peer.close();
        connect();
        server.sendTCPMessageAll("after-cancelled-lock-wait");
        assertEquals("after-cancelled-lock-wait", new DataInputStream(peer.getInputStream()).readUTF());
    }

    @Test
    public void stoppingDuringRegistrationCancelsTheClientWorker() throws Exception {
        connect();
        Semaphore endpointLock = Globals.getInstance().mIPTeamMapSemaphore;
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread worker = (Thread) get(server, "mClientThread");
        worker.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        endpointLock.acquire();
        try {
            new DataOutputStream(peer.getOutputStream()).writeUTF(TcpServer.TCPMESSAGE_PREFIX
                    + TcpServer.TCPPREFIX_JSON + "{\"playerID\":1,\"playername\":\"Player 1\"}");
            assertTrue("Registration did not reach the held endpoint lock", awaitQueued(endpointLock, 2000));
            server.stopTcpServer();
            assertTrue("Stopped registration retained a worker", awaitStopped(server, 1000));
            assertTrue("Cancelled registration crashed: " + failures, failures.isEmpty());
            assertEquals(0, endpointLock.availablePermits());
            assertEquals(1, ((Semaphore) get(server, "mClientDataSemaphore")).availablePermits());
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            endpointLock.release();
        }
    }

    @Test
    public void stoppingDuringIncomingGpsUpdateDoesNotPublishTheCancelledLocation() throws Exception {
        connect();
        Object client = ((Map<?, ?>) get(server, "mClientData")).values().iterator().next();
        Field playerID = client.getClass().getDeclaredField("mPlayerID");
        playerID.setAccessible(true);
        playerID.set(client, (byte) 1);
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        locations.acquire();
        try {
            new DataOutputStream(peer.getOutputStream()).writeUTF(TcpServer.TCPMESSAGE_PREFIX
                    + TcpServer.TCPPREFIX_JSON + "{\"gpslongitude\":10,\"gpslatitude\":20}");
            assertTrue("Incoming location did not reach the held GPS lock", awaitQueued(locations, 2000));
            server.stopTcpServer();
            assertTrue("Stopped GPS update retained a worker", awaitStopped(server, 1000));
            assertTrue(Globals.getInstance().mGPSData.isEmpty());
            assertEquals(0, locations.availablePermits());
            assertEquals(1, ((Semaphore) get(server, "mClientDataSemaphore")).availablePermits());
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            locations.release();
        }
    }

    @Test
    public void destroyingDuringRegistrationDoesNotCrashOrLeakClientLock() throws Exception {
        connect();
        Semaphore endpointLock = Globals.getInstance().mIPTeamMapSemaphore;
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread worker = (Thread) get(server, "mClientThread");
        worker.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        endpointLock.acquire();
        try {
            new DataOutputStream(peer.getOutputStream()).writeUTF(TcpServer.TCPMESSAGE_PREFIX
                    + TcpServer.TCPPREFIX_JSON + "{\"playerID\":1,\"playername\":\"Player 1\"}");
            assertTrue("Registration did not reach the held endpoint lock", awaitQueued(endpointLock, 2000));
            destroy(server);
            assertTrue("Cancelled registration retained a worker", awaitStopped(server, 1000));
            assertTrue("Cancelled registration crashed: " + failures, failures.isEmpty());
            assertEquals(0, endpointLock.availablePermits());
            assertEquals(1, ((Semaphore) get(server, "mClientDataSemaphore")).availablePermits());
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            endpointLock.release();
        }
    }

    @Test
    public void gpsSchedulingBelongsToEachServiceInstance() throws Exception {
        Globals.getInstance().mUseGPS = true;
        RecordingServer oldInstance = createServer();
        try {
            set(oldInstance, "keepListening", true);
            set(server, "keepListening", true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(oldInstance::sendGPSData);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
            Runnable current = (Runnable) get(server, "mGPSRunnable");
            assertNotNull(current);
            destroy(oldInstance);
            assertSame(current, get(server, "mGPSRunnable"));
            assertTrue((boolean) get(server, "mGPSRunning"));
            assertTrue((boolean) get(server, "keepListening"));
        } finally {
            oldInstance.stopTcpServer();
            destroy(oldInstance);
        }
    }

    @Test
    public void stopCancelsGpsCallbackBeforeItsFirstRun() throws Exception {
        Globals.getInstance().mUseGPS = true;
        set(server, "keepListening", true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
        Runnable pending = (Runnable) get(server, "mGPSRunnable");
        assertNotNull("Pending callback cannot be cancelled until it runs", pending);
        server.stopTcpServer();
        assertFalse((boolean) get(server, "mGPSRunning"));
        assertNull(get(server, "mGPSRunnable"));
        Globals.GPSData gps = new Globals.GPSData();
        gps.hasUpdate = true;
        Globals.getInstance().mGPSData.put((byte) 1, gps);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(pending);
        assertTrue("Stale callback consumed another session's GPS update", gps.hasUpdate);
        assertNull(get(server, "mGPSRunnable"));
    }

    @Test
    public void staleGpsCallbackCannotReplaceTheNewSessionsCallback() throws Exception {
        Globals.getInstance().mUseGPS = true;
        set(server, "keepListening", true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
        Runnable old = (Runnable) get(server, "mGPSRunnable");
        assertNotNull(old);
        server.stopTcpServer();
        set(server, "keepListening", true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
        Runnable current = (Runnable) get(server, "mGPSRunnable");
        assertNotNull(current);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(old);
        assertSame(current, get(server, "mGPSRunnable"));
        assertTrue((boolean) get(server, "mGPSRunning"));
    }

    @Test
    public void stationaryPlayersGpsUsesTheCurrentTeamLayout() throws Exception {
        int originalMode = Globals.getInstance().mGameMode;
        try {
            Globals.getInstance().mUseGPS = true;
            Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
            Globals.GPSData location = new Globals.GPSData();
            location.team = 1; // Player 6's old team in a two-team lobby.
            location.hasUpdate = true;
            Globals.getInstance().mGPSData.put((byte) 6, location);
            set(server, "keepListening", true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
            Runnable update = (Runnable) get(server, "mGPSRunnable");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
            assertEquals(1, server.messages.size());
            String message = server.messages.get(0);
            JSONObject gps = new JSONObject(message.substring((TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON).length()));
            assertEquals(2, gps.getJSONArray(TcpServer.JSON_GPSUPDATE).getJSONObject(0).getInt(TcpServer.JSON_TEAM));
        } finally {
            Globals.getInstance().mGameMode = originalMode;
        }
    }

    @Test
    public void forcedFullGpsUpdatePublishesAnEmptyLocationTable() throws Exception {
        Runnable update = scheduleGpsUpdate();
        set(server, "mGPSIntervalCount", 20);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
        assertEquals(1, server.messages.size());
        JSONObject snapshot = gpsMessage(0);
        assertTrue(snapshot.getBoolean(TcpServer.JSON_GPSFULLUPDATE));
        assertEquals(0, snapshot.getJSONArray(TcpServer.JSON_GPSUPDATE).length());
        assertEquals(0, get(server, "mGPSIntervalCount"));
    }

    @Test
    public void emptyGpsTableStillAdvancesPeriodicFullUpdates() throws Exception {
        Runnable update = scheduleGpsUpdate();
        set(server, "mGPSIntervalCount", 19);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
        assertTrue(server.messages.isEmpty());
        assertEquals(20, get(server, "mGPSIntervalCount"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
        assertEquals(1, server.messages.size());
        assertTrue(gpsMessage(0).getBoolean(TcpServer.JSON_GPSFULLUPDATE));
    }

    @Test
    public void forcedFullGpsUpdateIncludesStationaryPlayers() throws Exception {
        Runnable update = scheduleGpsUpdate();
        Globals.GPSData location = new Globals.GPSData();
        location.longitude = 10;
        location.latitude = 20;
        location.hasUpdate = false;
        Globals.getInstance().mGPSData.put((byte) 2, location);
        set(server, "mGPSIntervalCount", 20);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
        assertEquals(1, server.messages.size());
        assertTrue(gpsMessage(0).getBoolean(TcpServer.JSON_GPSFULLUPDATE));
        assertEquals(1, gpsMessage(0).getJSONArray(TcpServer.JSON_GPSUPDATE).length());
        assertEquals(1, Globals.getInstance().mGPSDataSemaphore.availablePermits());
    }

    @Test
    public void refreshRequestedWhileGpsCallbackWaitsForLocationsIsNotLost() throws Exception {
        Runnable update = scheduleGpsUpdate();
        ((android.os.Handler) get(server, "mGPSHandler")).removeCallbacks(update);
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        Thread worker = new Thread(update);
        locations.acquire();
        try {
            worker.start();
            assertTrue("GPS callback did not reach the held location lock", awaitQueued(locations, 2000));
            // A player leaving or joining requests a full refresh while the
            // current incremental update is still being prepared.
            set(server, "mGPSIntervalCount", 20);
        } finally {
            locations.release();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertEquals("A newer refresh request was consumed by an incremental update", 20,
                get(server, "mGPSIntervalCount"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(update);
        assertEquals(1, server.messages.size());
        assertTrue(gpsMessage(0).getBoolean(TcpServer.JSON_GPSFULLUPDATE));
    }

    @Test
    public void stoppingWhileGpsCallbackWaitsDoesNotConsumeLocationUpdates() throws Exception {
        assertWaitingGpsUpdateIsCancelled(server::stopTcpServer);
        assertNull(get(server, "mGPSRunnable"));
        assertFalse((boolean) get(server, "mGPSRunning"));
    }

    @Test
    public void destroyingWhileGpsCallbackWaitsDoesNotConsumeLocationUpdates() throws Exception {
        assertWaitingGpsUpdateIsCancelled(() -> destroy(server));
        assertNull(get(server, "mGPSRunnable"));
        assertFalse((boolean) get(server, "mGPSRunning"));
    }

    @Test
    public void disablingGpsWhileCallbackWaitsDoesNotSendOrConsumeLocations() throws Exception {
        assertWaitingGpsUpdateIsCancelled(() -> Globals.getInstance().mUseGPS = false);
        assertNull(get(server, "mGPSRunnable"));
        assertFalse((boolean) get(server, "mGPSRunning"));
    }

    @Test
    public void replacedGpsCallbackCannotConsumeTheNewSessionsLocationUpdates() throws Exception {
        Runnable[] replacement = new Runnable[1];
        assertWaitingGpsUpdateIsCancelled(() -> {
            server.stopTcpServer();
            set(server, "keepListening", true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
            replacement[0] = (Runnable) get(server, "mGPSRunnable");
            ((android.os.Handler) get(server, "mGPSHandler")).removeCallbacks(replacement[0]);
        });
        assertNotNull(replacement[0]);
        assertSame(replacement[0], get(server, "mGPSRunnable"));
        assertTrue((boolean) get(server, "mGPSRunning"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(replacement[0]);
        assertEquals(1, server.messages.size());
        assertFalse(Globals.getInstance().mGPSData.get((byte) 2).hasUpdate);
    }

    private void assertWaitingGpsUpdateIsCancelled(CheckedAction cancel) throws Exception {
        Runnable update = scheduleGpsUpdate();
        ((android.os.Handler) get(server, "mGPSHandler")).removeCallbacks(update);
        Globals.GPSData location = new Globals.GPSData();
        location.longitude = 10;
        location.latitude = 20;
        location.hasUpdate = true;
        Semaphore locations = Globals.getInstance().mGPSDataSemaphore;
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread worker = new Thread(update);
        worker.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        locations.acquire();
        try {
            Globals.getInstance().mGPSData.put((byte) 2, location);
            worker.start();
            assertTrue("GPS callback did not reach the held location lock", awaitQueued(locations, 2000));
            cancel.run();
        } finally {
            locations.release();
            worker.join(2000);
        }
        assertFalse("GPS callback did not finish", worker.isAlive());
        assertTrue("GPS callback crashed: " + failures, failures.isEmpty());
        assertTrue("Cancelled callback consumed a pending location", location.hasUpdate);
        assertTrue("Cancelled callback published locations", server.messages.isEmpty());
        assertEquals(1, locations.availablePermits());
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private Runnable scheduleGpsUpdate() throws Exception {
        Globals.getInstance().mUseGPS = true;
        Globals.getmGPSDataSemaphore();
        try { Globals.getInstance().mGPSData.clear(); }
        finally { Globals.getInstance().mGPSDataSemaphore.release(); }
        set(server, "keepListening", true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(server::sendGPSData);
        return (Runnable) get(server, "mGPSRunnable");
    }

    private JSONObject gpsMessage(int index) throws Exception {
        String message = server.messages.get(index);
        return new JSONObject(message.substring((TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON).length()));
    }

    private void connect() throws Exception {
        server.startTcpServer();
        peer = connectToPort();
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Map<?, ?> clients = (Map<?, ?>) get(server, "mClientData");
            if (clients != null && !clients.isEmpty()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Listener did not accept the connection");
    }

    private static Socket connectToPort() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (true) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                        TcpServer.TCP_SERVER_PORT), 200);
                socket.setSoTimeout(2000);
                return socket;
            } catch (java.io.IOException error) {
                socket.close();
                if (SystemClock.elapsedRealtime() >= deadline) throw error;
                Thread.sleep(10);
            }
        }
    }

    private static boolean awaitStopped(TcpServer target, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            Thread accept = (Thread) get(target, "mServerThread");
            Thread clients = (Thread) get(target, "mClientThread");
            synchronized (get(target, "mServerStateLock")) {
                if ((accept == null || !accept.isAlive()) && (clients == null || !clients.isAlive())
                        && ((Set<?>) get(target, "mClientTasks")).isEmpty()) return true;
            }
            Thread.sleep(10);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static boolean awaitQueued(Semaphore semaphore, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!semaphore.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        return semaphore.hasQueuedThreads();
    }

    private static void assertPortCanBeReused() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(TcpServer.TCP_SERVER_PORT));
        }
    }

    private static RecordingServer createServer() {
        RecordingServer[] result = new RecordingServer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result[0] = new RecordingServer());
        result[0].setDedicated(true);
        return result[0];
    }

    private static void destroy(TcpServer target) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(target::onDestroy);
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = TcpServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = TcpServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static final class RecordingServer extends TcpServer {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) { }
        @Override public void sendTCPMessageAll(String message, boolean queueMessage) {
            messages.add(message);
            super.sendTCPMessageAll(message, queueMessage);
        }
    }
}
