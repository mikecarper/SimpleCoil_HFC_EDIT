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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Actual server FIFO/round callbacks with controlled streams, without a listening port. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class TcpServerOutboundRegressionTest {
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
    private static final String MESSAGE = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG;

    @Test public void onlyFailedStartWritesNeverConfirmTheLocalRound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket failed = fixture.add(1, new FailingSocket());
            assertTrue(fixture.server.startGame());
            fixture.settle();
            assertTrue(failed.closed);
            assertFalse("An enqueue was mistaken for an actual successful START flush",
                    fixture.server.events.contains(NetMsg.NETMSG_STARTGAME));
        }
    }

    @Test public void aHealthyStartFlushConfirmsWithoutDependingOnTheFailedRecipient() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket failed = fixture.add(1, new FailingSocket());
            MemorySocket healthy = fixture.add(17, new MemorySocket());
            assertTrue(fixture.server.startGame());
            fixture.settle();
            assertTrue(failed.closed);
            assertFalse(healthy.closed);
            List<String> frames = healthy.frames();
            assertEquals(1, frames.size());
            assertTrue(new JSONObject(frames.get(0).substring(JSON.length()))
                    .has(TcpServer.JSON_GAMESTART));
            assertEquals(1, java.util.Collections.frequency(fixture.server.events, NetMsg.NETMSG_STARTGAME));
        }
    }

    @Test public void terminalCancellationFlushesBeforeClosingTheHealthySocket() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket phone = fixture.add(1, new MemorySocket());
            fixture.server.cancelServer();
            await(() -> phone.closed && fixture.tasksEmpty(), "Cancellation did not finish");
            assertEquals(java.util.Collections.singletonList(MESSAGE + NetMsg.NETMSG_SERVERCANCEL),
                    phone.frames());
            assertFalse((boolean) get(fixture.server, "keepListening"));
        }
    }

    @Test public void rejectedRegistrationFlushesItsReplyEvenAfterRemovalFromTheRoster() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket rejected = fixture.add(9, 0, new MemorySocket());
            fixture.parse(9, new JSONObject().put(TcpServer.JSON_PLAYERID, 17)
                    .put(TcpServer.JSON_PLAYERNAME, "Joining")
                    .put(LiveJoin.IDENTITY, UUID.randomUUID().toString()).put(LiveJoin.TEAM, 2));
            await(() -> rejected.closed, "Rejected registration did not close its socket");
            assertFalse(fixture.clients.containsKey(9));
            List<String> frames = rejected.frames();
            assertEquals(1, frames.size());
            assertTrue(new JSONObject(frames.get(0).substring(JSON.length())).getBoolean(LiveJoin.REJECTED));
        }
    }

    @Test public void aQueuedEventCannotFollowAnotherIdentityOnTheSameSocketAndSeat() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket phone = fixture.add(1, new MemorySocket());
            Semaphore roster = (Semaphore) get(fixture.server, "mClientDataSemaphore");
            roster.acquire();
            try {
                fixture.sendReliable(1, "previous person's event");
                set(fixture.clients.get(1), "playerIdentity", UUID.randomUUID().toString());
            } finally { roster.release(); }
            fixture.settle();
            assertTrue(phone.frames().isEmpty());
            synchronized (fixture.clients.get(1)) {
                assertTrue(((Queue<?>) get(fixture.clients.get(1), "messageQueue")).isEmpty());
            }
        }
    }

    @Test public void anOldStreamFailureCannotRetireTheRejoinedStreamOrLoseItsReliableEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DelayedFailureSocket old = new DelayedFailureSocket();
            fixture.add(1, old);
            fixture.sendReliable(1, "retained event");
            assertTrue(old.entered.await(1, TimeUnit.SECONDS));
            MemorySocket replacement = new MemorySocket();
            fixture.sockets.add(replacement);
            Object client = fixture.clients.get(1);
            invoke(client, "rejoin", new Class<?>[] {Socket.class}, replacement);
            assertSame(replacement, get(client, "clientSocket"));
            old.released.countDown();
            fixture.settle();
            assertFalse("The old writer closed its replacement", replacement.closed);
            assertSame(replacement, get(client, "clientSocket"));
            assertEquals(java.util.Collections.singletonList("retained event"), replacement.frames());
            synchronized (client) {
                assertTrue("A flushed reliable event remained queued for another duplicate replay",
                        ((Queue<?>) get(client, "messageQueue")).isEmpty());
            }
        }
    }

    @Test public void endingAnUnflushedStartSuppressesItsLateLocalConfirmation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HeldSocket phone = new HeldSocket();
            fixture.add(1, phone);
            assertTrue(fixture.server.startGame());
            assertTrue(phone.entered.await(1, TimeUnit.SECONDS));
            fixture.server.endGame();
            await(() -> fixture.server.events.contains(NetMsg.NETMSG_ENDGAME) && fixture.tasksEmpty(),
                    "Round completion waited for the held socket");
            phone.released.countDown();
            fixture.settle();
            assertFalse("An obsolete START flush revived an ended local round",
                    fixture.server.events.contains(NetMsg.NETMSG_STARTGAME));
            List<String> frames = phone.frames();
            assertTrue(frames.get(0).startsWith(JSON));
            assertEquals(MESSAGE + NetMsg.NETMSG_ENDGAME, frames.get(frames.size() - 1));
            assertFalse("Normal round completion closed the retained lobby socket", phone.closed);
        }
    }

    @Test public void freshRegistrationCannotEnterTheFrozenRosterBeforeTheFirstStartFlush() throws Exception {
        assertAdmissionFrozenUntilStartFlush(false);
    }

    @Test public void rejoinCannotRetireTheFrozenPlayersUnflushedStartSocket() throws Exception {
        assertAdmissionFrozenUntilStartFlush(true);
    }

    private void assertAdmissionFrozenUntilStartFlush(boolean rejoin) throws Exception {
        try (Fixture fixture = new Fixture()) {
            HeldSocket originalSocket = new HeldSocket();
            fixture.add(1, originalSocket);
            Object originalClient = fixture.clients.get(1);
            assertTrue(fixture.server.startGame());
            assertTrue(originalSocket.entered.await(1, TimeUnit.SECONDS));
            assertTrue((boolean) get(fixture.server, "mStartingGame"));
            assertFalse((boolean) get(fixture.server, "mStartAnnounced"));
            MemorySocket arriving = fixture.add(2, 0, new MemorySocket());
            fixture.parse(2, new JSONObject().put(TcpServer.JSON_PLAYERID, rejoin ? 1 : 17)
                    .put(TcpServer.JSON_PLAYERNAME, "Arriving")
                    .put(TcpServer.JSON_REJOIN, rejoin)
                    .put(LiveJoin.IDENTITY, rejoin ? get(originalClient, "playerIdentity")
                            : UUID.randomUUID().toString()));
            assertTrue("An arriving socket entered between START enqueue and first flush", arriving.closed);
            assertFalse(fixture.clients.containsKey(2));
            assertSame(originalClient, fixture.clients.get(1));
            assertSame("Rejoin discarded the generation carrying START", originalSocket,
                    get(originalClient, "clientSocket"));
            assertFalse(originalSocket.closed);
            assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 17));
            originalSocket.released.countDown();
            fixture.settle();
            assertEquals(1, java.util.Collections.frequency(fixture.server.events, NetMsg.NETMSG_STARTGAME));
            assertTrue(new JSONObject(originalSocket.frames().get(0).substring(JSON.length()))
                    .has(TcpServer.JSON_GAMESTART));
        }
    }

    @Test public void playerSettingsCannotChangeWhileTheFirstStartFlushIsPending() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HeldSocket phone = new HeldSocket();
            fixture.add(1, phone);
            Globals globals = Globals.getInstance();
            globals.mAllowPlayerSettings = true;
            globals.mOnlyServerSettings = false;
            Globals.PlayerSettings original = new Globals.PlayerSettings();
            original.health = 5;
            globals.mPlayerSettings.put((byte) 1, original);
            assertTrue(fixture.server.startGame());
            assertTrue(phone.entered.await(1, TimeUnit.SECONDS));
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_PLAYERSETTINGS, true)
                    .put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_HEALTH, 99)
                    .put(TcpServer.JSON_RELOAD_SHOTS, 30).put(TcpServer.JSON_RELOAD_TIME, 1500)
                    .put(TcpServer.JSON_RELOAD_ON_EMPTY, false).put(TcpServer.JSON_SPAWN_TIME, 10)
                    .put(TcpServer.JSON_DAMAGE, -1).put(TcpServer.JSON_SHOT_MODE_SINGLE, true)
                    .put(TcpServer.JSON_SHOT_MODE_BURST3, true).put(TcpServer.JSON_SHOT_MODE_AUTO, true)
                    .put(TcpServer.JSON_FIRING_MODE, 0));
            await(fixture::tasksEmpty, "Settings response waited for the held START write");
            assertSame(original, globals.mPlayerSettings.get((byte) 1));
            assertEquals("A phone changed its weapon rules after the round roster froze", 5,
                    original.health);
            phone.released.countDown();
            fixture.settle();
        }
    }

    @Test public void exhaustedWriterSlotsRefuseOnlyThatCandidateWithoutBlockingTheRoster() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MemorySocket refused = fixture.add(1, new MemorySocket());
            Semaphore slots = (Semaphore) get(fixture.server, "mClientWriterSlots");
            slots.acquire(TcpServer.MAX_CLIENT_WRITERS);
            long started = System.nanoTime();
            try {
                fixture.server.sendTCPMessageAll("cannot acquire a writer");
                await(() -> refused.closed && fixture.tasksEmpty(), "Writer-slot refusal blocked the host");
                assertTrue("Writer-slot refusal waited for network I/O",
                        System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
                assertTrue(((Set<?>) get(fixture.server, "mClientWriters")).isEmpty());
                assertEquals(1, ((Semaphore) get(fixture.server, "mClientDataSemaphore")).availablePermits());
            } finally { slots.release(TcpServer.MAX_CLIENT_WRITERS); }
            fixture.clients.remove(1);
            MemorySocket healthy = fixture.add(17, new MemorySocket());
            fixture.server.sendTCPMessageAll("healthy after refusal");
            fixture.settle();
            assertEquals(java.util.Collections.singletonList("healthy after refusal"), healthy.frames());
            assertFalse(healthy.closed);
            assertEquals(TcpServer.MAX_CLIENT_WRITERS, slots.availablePermits()
                    + ((Set<?>) get(fixture.server, "mClientWriters")).size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingServer server = new RecordingServer();
        final Map<Integer, Object> clients = new ConcurrentHashMap<>();
        final List<MemorySocket> sockets = new ArrayList<>();
        final Object previousGlobals;

        Fixture() throws Exception {
            previousGlobals = get(Globals.class, "mInstance");
            set(Globals.class, "mInstance", null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 0;
            globals.mLocalLobbyReady = false;
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mUseGPS = false;
            globals.mTournamentMode = false;
            globals.mBalancedRandom = false;
            globals.mBossMode = false;
            globals.mInfectionMode = false;
            server.setDedicated(true);
            set(server, "mClientData", clients);
            set(server, "keepListening", true);
        }

        MemorySocket add(int player, MemorySocket socket) throws Exception { return add(player, player, socket); }

        MemorySocket add(int connection, int player, MemorySocket socket) throws Exception {
            sockets.add(socket);
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);
            constructor.setAccessible(true);
            Object client = constructor.newInstance(server);
            assertTrue((boolean) invoke(client, "initialize", new Class<?>[] {Socket.class, int.class},
                    socket, connection));
            set(client, "mPlayerID", (byte) player);
            set(client, "clockSynchronized", true);
            clients.put(connection, client);
            if (player > 0) {
                InetAddress address = InetAddress.getByName("127.0.0." + connection);
                Globals globals = Globals.getInstance();
                globals.mTeamIPMap.put((byte) player, address);
                globals.mIPTeamMap.put(address, (byte) player);
                globals.mTeamPlayerNameMap.put((byte) player, "Player " + player);
            }
            return socket;
        }

        void sendReliable(int player, String message) throws Exception {
            invoke(server, "sendTCPMessageID", new Class<?>[] {String.class, byte.class, boolean.class},
                    message, (byte) player, true);
        }

        void parse(int connection, JSONObject json) throws Exception {
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientThread");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);
            constructor.setAccessible(true);
            Object handler = constructor.newInstance(server);
            Object client = clients.get(connection);
            invoke(handler, "parsePlayerInfo", new Class<?>[] {String.class, client.getClass()},
                    json.toString(), client);
        }

        boolean tasksEmpty() {
            try {
                synchronized (get(server, "mServerStateLock")) {
                    return ((Set<?>) get(server, "mClientTasks")).isEmpty();
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        }

        void settle() throws Exception {
            await(() -> {
                if (!tasksEmpty()) return false;
                try {
                    for (Object client : clients.values()) synchronized (client) {
                        if ((boolean) get(client, "writing") || !((Queue<?>) get(client, "outbound")).isEmpty())
                            return false;
                    }
                    return !(boolean) get(server, "mStartingGame");
                } catch (Exception failure) { throw new AssertionError(failure); }
            }, "Server outbound frames or round callbacks did not settle");
        }

        @Override public void close() throws Exception {
            for (MemorySocket socket : sockets) {
                if (socket instanceof HeldSocket) ((HeldSocket) socket).released.countDown();
                socket.close();
            }
            server.onDestroy();
            await(() -> {
                try { return ((Set<?>) get(server, "mClientWriters")).isEmpty() && tasksEmpty(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }, "Server workers survived fixture cleanup");
            set(Globals.class, "mInstance", previousGlobals);
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> owner = target instanceof TcpServer ? TcpServer.class : target.getClass();
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object get(Object target, String name) throws Exception {
        Class<?> owner = target instanceof Class ? (Class<?>) target
                : target instanceof TcpServer ? TcpServer.class : target.getClass();
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> owner = target instanceof Class ? (Class<?>) target
                : target instanceof TcpServer ? TcpServer.class : target.getClass();
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class ? null : target, value);
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
        List<String> frames() throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            List<String> frames = new ArrayList<>();
            while (input.available() > 0) frames.add(input.readUTF());
            return frames;
        }
    }

    private static final class FailingSocket extends MemorySocket {
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("Controlled write failure"); }
            };
        }
    }

    private static class HeldSocket extends MemorySocket {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        boolean first = true;
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
                @Override public void write(byte[] values, int offset, int length) throws IOException {
                    if (first) {
                        first = false;
                        entered.countDown();
                        try {
                            if (!released.await(2, TimeUnit.SECONDS)) throw new IOException("Controlled write not released");
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw new IOException(cancelled);
                        }
                    }
                    if (closed) throw new IOException("Old socket closed");
                    bytes.write(values, offset, length);
                }
            };
        }
    }

    private static final class DelayedFailureSocket extends HeldSocket { }
}
