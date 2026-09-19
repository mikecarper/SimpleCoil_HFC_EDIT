package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
            if ((accept == null || !accept.isAlive()) && (clients == null || !clients.isAlive())) return true;
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
        @Override public void sendBroadcast(Intent intent) { }
    }
}
