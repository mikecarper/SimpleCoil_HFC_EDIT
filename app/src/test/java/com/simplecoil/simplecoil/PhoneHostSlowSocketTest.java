package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.Looper;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.Queue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A stalled native writer must not strand the other phones in a retained lobby. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhoneHostSlowSocketTest {
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;

    @Test public void completedDeadlineCannotCloseASocketUsedByANewerWrite() throws Exception {
        Socket socket = new Socket();
        Class<?> type = Class.forName(TcpServer.class.getName() + "$SocketWriteDeadline");
        Constructor<?> constructor = type.getDeclaredConstructor(Socket.class);constructor.setAccessible(true);
        Method complete = type.getDeclaredMethod("complete");complete.setAccessible(true);
        try {
            Runnable oldWrite = (Runnable) constructor.newInstance(socket);
            assertTrue((boolean) complete.invoke(oldWrite));
            Runnable nextWrite = (Runnable) constructor.newInstance(socket);
            oldWrite.run();
            assertFalse("A completed watchdog closed a socket reused by a newer write", socket.isClosed());
            nextWrite.run();
            assertTrue("The current stalled write did not close its own socket", socket.isClosed());
            assertFalse("An expired write was accepted as a successful completion",
                    (boolean) complete.invoke(nextWrite));
        } finally { socket.close(); }
    }

    @Test public void stalledPhoneCannotBlockAnotherClockReplyOrNewAdmission() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture()) {
            fixture.connect(1);
            Phone healthy = fixture.connect(2);
            driveUntil(() -> fixture.registered(1) && fixture.registered(2)
                    && fixture.settled(), 3_000, "Initial phone registrations did not settle");
            fixture.blockWritesTo(1);
            fixture.server.sendTCPMessageAll(JSON + "{}");
            assertTrue("The controlled socket never entered its blocked write",
                    fixture.blocked.entered.await(2, TimeUnit.SECONDS));
            Semaphore clientAccess = (Semaphore) get(fixture.server, "mClientDataSemaphore");
            assertTrue("A native writer retained the shared client semaphore",
                    clientAccess.tryAcquire(200, TimeUnit.MILLISECONDS));
            clientAccess.release();

            long started = millis();
            healthy.send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 1234));
            Future<JSONObject> clock = reader.submit(() -> healthy.readClockReply(1234));
            // This goes through the real accept loop, not a simulated map insertion.
            Phone newcomer = fixture.connect(3);
            driveUntil(() -> clock.isDone() && fixture.registered(3), 5_000,
                    "One blocked writer held the host's client lock for over five seconds; "
                            + "a healthy clock request and native TCP admission could not proceed");
            assertEquals(1234, clock.get(1, TimeUnit.SECONDS).getLong(TcpServer.JSON_CLOCK_REQUEST));
            assertTrue("The healthy clock/admission exceeded five seconds", millis() - started < 5_000);
            long elapsed = millis() - started;
            driveUntil(() -> fixture.blocked.closed, 2_000,
                    "The native write watchdog did not close its own stalled connection");
            assertFalse("A healthy connection was closed with the stalled phone", healthy.socket.isClosed());
            assertFalse("The joining phone was closed with the stalled phone", newcomer.socket.isClosed());
            assertTrue("The phone host stopped instead of isolating the slow phone", fixture.server.isTcpServerReady());
            System.out.println("Phone host slow socket: clock and admission recovered in "
                    + elapsed + " ms");
        } finally {
            reader.shutdownNow();
            assertTrue("The healthy reader survived cleanup", reader.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void sixStalledPhonesCannotDelayHealthyClockOrNativeAdmission() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture()) {
            for (int id = 1; id <= 6; id++) fixture.connect(id);
            Phone healthy = fixture.connect(7);
            driveUntil(() -> fixture.registeredThrough(7) && fixture.settled(), 4_000,
                    "Initial seven-phone registrations did not settle");
            List<ControlledOutput> stalled = new ArrayList<>();
            for (int id = 1; id <= 6; id++) stalled.add(fixture.blockWritesTo(id));
            fixture.server.sendTCPMessageAll(JSON + "{}");
            assertTrue("The controlled native write did not start",
                    stalled.get(0).entered.await(2, TimeUnit.SECONDS));
            long started = millis();
            healthy.send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 1234));
            Future<JSONObject> clock = reader.submit(() -> healthy.readClockReply(1234));
            Phone newcomer = fixture.connect(8);
            driveUntil(() -> clock.isDone() && fixture.registered(8), 2_000,
                    "Six stalled phones delayed a healthy clock reply or native admission");
            assertEquals(1234, clock.get(1, TimeUnit.SECONDS).getLong(TcpServer.JSON_CLOCK_REQUEST));
            long elapsed = millis() - started;
            assertTrue("The healthy clock and admission exceeded the five-second target", elapsed < 5_000);
            assertFalse(healthy.socket.isClosed());assertFalse(newcomer.socket.isClosed());
            for (ControlledOutput blocked : stalled)
                assertTrue("The blocked phones were still serialized behind a shared writer",
                        blocked.entered.await(100, TimeUnit.MILLISECONDS));
            assertTrue(fixture.server.isTcpServerReady());
            assertTrue(((Set<?>) get(fixture.server, "mClientWriters")).size() <= TcpServer.MAX_CLIENT_WRITERS);
            System.out.println("Six stalled phones: healthy clock and native admission completed in "
                    + elapsed + " ms (audit baseline 7216 ms)");
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void eachClientWritesFramesInEnqueueOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone phone = fixture.connect(1);
            driveUntil(() -> fixture.registered(1) && fixture.settled(), 3_000,
                    "Phone did not finish registration");
            Object client = fixture.client(1);
            Method send = client.getClass().getDeclaredMethod("sendTCPMessage", String.class, boolean.class);
            send.setAccessible(true);
            for (int i = 0; i < 64; i++) assertTrue((boolean) send.invoke(client, "ordered-" + i, false));
            int received = 0;
            while (received < 64) {
                String frame = phone.input.readUTF();
                if (!frame.startsWith("ordered-")) continue;
                assertEquals("ordered-" + received, frame);
                received++;
            }
            driveUntil(fixture::settled, 2_000, "The ordered queue did not drain");
        }
    }

    @Test public void frameAndByteBacklogsAreBoundedAndRetireOnlyTheirOwnPhone() throws Exception {
        for (boolean largeFrames : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.connect(1);
                Phone healthy = fixture.connect(2);
                driveUntil(() -> fixture.registeredThrough(2) && fixture.settled(), 3_000,
                        "Phones did not finish registration");
                Object client = fixture.client(1);
                ControlledOutput blocked = fixture.blockWritesTo(1);
                Method send = client.getClass().getDeclaredMethod("sendTCPMessage", String.class, boolean.class);
                send.setAccessible(true);
                assertTrue((boolean) send.invoke(client, "in-flight", false));
                assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
                char[] chars = new char[largeFrames ? 65500 : 32];
                java.util.Arrays.fill(chars, 'a');
                String message = new String(chars);
                int accepted = 0;
                while ((boolean) send.invoke(client, message, true)) {
                    accepted++;
                    assertTrue("The outbound queue grew without a bound", accepted <= TcpServer.MAX_PENDING_CLIENT_WRITES);
                }
                if (largeFrames) assertTrue("The byte cap did not bound large frames", accepted <= 16);
                else assertEquals(TcpServer.MAX_PENDING_CLIENT_WRITES, accepted);
                synchronized (client) {
                    assertTrue(((Queue<?>) get(client, "outbound")).isEmpty());
                    assertEquals(0, get(client, "outboundBytes"));
                    assertTrue(((Queue<?>) get(client, "messageQueue")).size() <= TcpServer.MAX_QUEUED_CLIENT_EVENTS);
                }
                assertTrue("Backpressure did not cancel the captured native write", blocked.closed);
                healthy.send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 2345));
                assertEquals(2345, healthy.readClockReply(2345).getLong(TcpServer.JSON_CLOCK_REQUEST));
                assertTrue(fixture.server.isTcpServerReady());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingServer server = new RecordingServer();
        final List<Phone> phones = new ArrayList<>();
        final ControlledOutput blocked = new ControlledOutput();
        final List<ControlledOutput> blockedWrites = new ArrayList<>();
        final Object previousGlobals;

        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);previousGlobals = singleton.get(null);singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 32;
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_FFA;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mUseGPS = false;
            globals.mBalancedRandom = false;
            globals.mBossMode = false;
            globals.mInfectionMode = false;
            server.setDedicated(false);
            try {
                server.startTcpServer();
                driveUntil(server::isTcpServerReady, 3_000, "Phone host listener did not start");
            } catch (Exception | AssertionError failure) {
                server.onDestroy();
                singleton.set(null, previousGlobals);
                throw failure;
            }
        }

        Phone connect(int id) throws Exception {
            Socket socket = new Socket();
            try {
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0." + (id + 1)), 0));
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), TcpServer.TCP_SERVER_PORT), 1_000);
                socket.setTcpNoDelay(true);socket.setSoTimeout(7_000);
                Phone phone = new Phone(socket);phones.add(phone);
                phone.send(new JSONObject().put(TcpServer.JSON_PLAYERID, id)
                        .put(TcpServer.JSON_PLAYERNAME, "Player " + id)
                        .put(LiveJoin.IDENTITY, String.format("00000000-0000-0000-0000-%012d", 100 + id))
                        .put(TcpServer.JSON_LOBBY_READY, true));
                return phone;
            } catch (Exception failure) {
                socket.close();throw failure;
            }
        }

        @SuppressWarnings("unchecked") Map<Integer, Object> clients() throws Exception {
            return (Map<Integer, Object>) get(server, "mClientData");
        }

        boolean registered(int id) {
            try {
                Map<Integer, Object> clients = clients();
                if (clients == null) return false;
                for (Object client : clients.values())
                    if (((byte) get(client, "mPlayerID") & 255) == id) return true;
                return false;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        boolean registeredThrough(int maximum) {
            for (int id = 1; id <= maximum; id++) if (!registered(id)) return false;
            return true;
        }

        Object client(int id) throws Exception {
            for (Object candidate : clients().values())
                if (((byte) get(candidate, "mPlayerID") & 255) == id) return candidate;
            throw new AssertionError("No client for player " + id);
        }

        boolean settled() {
            try {
                synchronized (get(server, "mServerStateLock")) {
                    if (!((Set<?>) get(server, "mClientTasks")).isEmpty()) return false;
                }
                for (Object client : clients().values()) {
                    synchronized (client) {
                        if ((boolean) get(client, "writing") || !((Queue<?>) get(client, "outbound")).isEmpty())
                            return false;
                    }
                }
                return true;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        ControlledOutput blockWritesTo(int id) throws Exception {
            ControlledOutput blocked = blockedWrites.isEmpty() ? this.blocked : new ControlledOutput();
            blockedWrites.add(blocked);
            Semaphore access = (Semaphore) get(server, "mClientDataSemaphore");
            assertTrue("Could not install the controlled writer", access.tryAcquire(2, TimeUnit.SECONDS));
            try {
                Object client = null;
                for (Object candidate : clients().values())
                    if (((byte) get(candidate, "mPlayerID") & 255) == id) client = candidate;
                assertTrue("The slow phone was not registered", client != null);
                Socket nativeSocket = (Socket) get(client, "clientSocket");
                set(client, "clientSocket", new CloseControlledSocket(nativeSocket, blocked));
                set(client, "out", new DataOutputStream(new BufferedOutputStream(blocked)));
            } finally { access.release(); }
            return blocked;
        }

        @Override public void close() throws Exception {
            // Release first, even if the assertion failed before a watchdog existed.
            // Socket writes need not react to a thread interruption.
            blocked.close();
            for (ControlledOutput output : blockedWrites) output.close();
            List<Thread> workers = new ArrayList<>();
            try {
                synchronized (get(server, "mServerStateLock")) {
                    Thread accept = (Thread) get(server, "mServerThread");
                    Thread poll = (Thread) get(server, "mClientThread");
                    if (accept != null) workers.add(accept);
                    if (poll != null) workers.add(poll);
                    for (Object task : (Set<?>) get(server, "mClientTasks")) workers.add((Thread) task);
                    for (Object task : (Set<?>) get(server, "mClientWriters")) workers.add((Thread) task);
                }
                server.onDestroy();
                for (Phone phone : phones) phone.socket.close();
                for (Thread worker : workers) {
                    worker.join(2_000);
                    assertFalse("Phone-host worker survived cleanup: " + worker.getName(), worker.isAlive());
                }
                assertEquals("A writer permit leaked", TcpServer.MAX_CLIENT_WRITERS,
                        ((Semaphore) get(server, "mClientWriterSlots")).availablePermits());
                ScheduledThreadPoolExecutor watchdog = (ScheduledThreadPoolExecutor) get(server, "mWriteWatchdog");
                assertTrue("Write watchdog survived service destruction", watchdog.awaitTermination(2, TimeUnit.SECONDS));
            } finally {
                Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
                singleton.set(null, previousGlobals);
            }
        }
    }

    private static final class Phone {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        Phone(Socket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }
        void send(JSONObject frame) throws IOException { output.writeUTF(JSON + frame);output.flush(); }
        JSONObject readClockReply(long request) throws Exception {
            while (true) {
                String frame = input.readUTF();
                if (!frame.startsWith(JSON)) continue;
                JSONObject json = new JSONObject(frame.substring(JSON.length()));
                if (json.optLong(TcpServer.JSON_CLOCK_REQUEST, -1) == request) return json;
            }
        }
    }

    /** Blocks like a full native send buffer and ignores interrupts until socket close. */
    private static final class ControlledOutput extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        volatile boolean closed;
        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            entered.countDown();
            boolean interrupted = false;
            while (!closed) {
                try { released.await(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw new IOException("Controlled native socket closed during blocked write");
        }
        @Override public void close() { closed = true;released.countDown(); }
    }

    /** The host must close its captured socket without waiting for the writer monitor. */
    private static final class CloseControlledSocket extends Socket {
        final Socket nativeSocket;
        final ControlledOutput blocked;
        CloseControlledSocket(Socket nativeSocket, ControlledOutput blocked) {
            this.nativeSocket = nativeSocket;this.blocked = blocked;
        }
        @Override public InetAddress getInetAddress() { return nativeSocket.getInetAddress(); }
        @Override public synchronized void close() throws IOException { blocked.close();nativeSocket.close(); }
    }

    private static final class RecordingServer extends TcpServer {
        @Override public void sendBroadcast(Intent intent) { }
    }

    private static void driveUntil(BooleanSupplier condition, long timeout, String failure) throws Exception {
        long deadline = millis() + timeout;
        while (!condition.getAsBoolean() && millis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS);
            Thread.sleep(10);
        }
        assertTrue(failure, condition.getAsBoolean());
    }
    private static long millis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static Object get(Object target, String name) throws Exception {
        Field field = (target instanceof TcpServer ? TcpServer.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = (target instanceof TcpServer ? TcpServer.class : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);field.set(target, value);
    }
}
