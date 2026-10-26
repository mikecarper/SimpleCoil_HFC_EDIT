package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
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
import java.time.Duration;
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
import static org.junit.Assert.*;

/** Host cancellation uses the initial monotonic deadline and retains the shared lobby. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class HostCountdownCancellationTest {
    private static final String MESSAGE = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG;

    @Test public void hostCanCancelWithoutVotesAndImmediatelyRestartWithTheSameClocksAndSockets() throws Exception {
        for (boolean dedicated : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(dedicated)) {
                MemorySocket first = fixture.add(1, false);
                MemorySocket second = fixture.add(17, false);
                String token = fixture.start();
                Object firstClient = fixture.clients.get(1);
                Object secondClient = fixture.clients.get(17);
                assertTrue(fixture.server.canCancelGameBeforeStart(token));
                assertEquals(0L, Globals.getInstance().mEndGameVotes);
                assertFalse(Globals.getInstance().mEndGameVoteRequested);
                assertTrue(fixture.server.cancelGameBeforeStart(token));
                fixture.settle();
                assertEquals(1, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
                for (MemorySocket phone : new MemorySocket[] {first, second}) {
                    assertEquals(1, java.util.Collections.frequency(phone.frames(), MESSAGE + NetMsg.NETMSG_ENDGAME));
                    assertFalse(phone.frames().contains(MESSAGE + NetMsg.NETMSG_SERVERCANCEL));
                    assertFalse("A countdown cancel closed a retained lobby phone", phone.closed);
                }
                assertSame(firstClient, fixture.clients.get(1));
                assertSame(secondClient, fixture.clients.get(17));
                assertTrue((boolean) get(firstClient, "clockSynchronized"));
                assertTrue((boolean) get(secondClient, "clockSynchronized"));
                assertTrue((boolean) get(fixture.server, "keepListening"));
                assertEquals(0L, fixture.server.getNextGameStartWaitMillis());
                assertNull(fixture.server.getScheduledGameStart());
                assertEquals(0L, Globals.getInstance().mEndGameVotes);
                assertFalse(Globals.getInstance().mEndGameVoteRequested);
                assertTrue(((Map<?, ?>) get(fixture.server, "mCompletedRoundReports")).isEmpty());
                String next = fixture.start();
                assertNotEquals(token, next);
                assertFalse("An old dialog cancelled the replacement countdown", fixture.server.cancelGameBeforeStart(token));
                assertTrue(fixture.server.canCancelGameBeforeStart(next));
            }
        }
    }

    @Test public void availabilityEndsExactlyAtTheInitialDeadline() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.add(1, false);
            String token = fixture.start();
            long deadline = fixture.deadline();
            advanceTo(deadline - 1);
            assertTrue(fixture.server.canCancelGameBeforeStart(token));
            advanceTo(deadline);
            assertFalse(fixture.server.canCancelGameBeforeStart(token));
            assertFalse(fixture.server.cancelGameBeforeStart(token));
            advanceTo(deadline + 1);
            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            assertFalse("A player's later respawn reopened initial-countdown cancellation",
                    fixture.server.canCancelGameBeforeStart(token));
            assertFalse(fixture.server.cancelGameBeforeStart(token));
            assertEquals(0, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
            assertNotNull(fixture.server.getScheduledGameStart());
        }
    }

    @Test public void acceptedBeforeDeadlineRemainsAcceptedWhenEndWorkerRunsAfterIt() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            MemorySocket phone = fixture.add(1, false);
            String token = fixture.start();
            long deadline = fixture.deadline();
            Semaphore roster = (Semaphore) get(fixture.server, "mClientDataSemaphore");
            roster.acquire();
            try {
                advanceTo(deadline - 1);
                assertTrue(fixture.server.cancelGameBeforeStart(token));
                assertTrue("Cancellation did not reserve END atomically", (boolean) get(fixture.server, "mEndingGame"));
                assertFalse(fixture.server.cancelGameBeforeStart(token));
                advanceTo(deadline + 70_000);
            } finally { roster.release(); }
            fixture.settle();
            assertEquals(1, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
            assertEquals(1, java.util.Collections.frequency(phone.frames(), MESSAGE + NetMsg.NETMSG_ENDGAME));
            assertFalse(phone.closed);
            assertEquals("A delayed pre-start cancellation imposed a played-round cooldown",
                    0L, fixture.server.getNextGameStartWaitMillis());
            assertTrue("A cancelled countdown was saved as a played round",
                    ((Map<?, ?>) get(fixture.server, "mCompletedRoundReports")).isEmpty());
        }
    }

    @Test public void pendingFirstFlushCanBeCancelledWithoutALateStartRevivingIt() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            MemorySocket held = fixture.add(1, true);
            assertTrue(fixture.server.startGame());
            assertTrue(held.entered.await(1, TimeUnit.SECONDS));
            String token = (String) get(fixture.server, "mRoundToken");
            assertNull(fixture.server.getScheduledGameStart());
            assertTrue(fixture.server.canCancelGameBeforeStart(token));
            assertTrue(fixture.server.cancelGameBeforeStart(token));
            await(() -> fixture.eventCount(NetMsg.NETMSG_ENDGAME) == 1 && fixture.tasksEmpty(),
                    "Pending cancellation did not finish");
            assertEquals("A cancelled pending START was announced locally", 0,
                    fixture.eventCount(NetMsg.NETMSG_STARTGAME));
            assertFalse((boolean) get(fixture.server, "mStartingGame"));
            assertTrue("The cancelled first flush kept the next countdown reserved", fixture.server.startGame());
            await(() -> {
                try {
                    Object current = get(fixture.server, "mRoundToken");
                    return current instanceof String && !token.equals(current);
                }
                catch (Exception failure) { throw new AssertionError(failure); }
            }, "The replacement countdown did not reserve a fresh token");
            String next = (String) get(fixture.server, "mRoundToken");
            held.released.countDown();
            fixture.settle();
            assertEquals("The old first flush revived a second local countdown", 1,
                    fixture.eventCount(NetMsg.NETMSG_STARTGAME));
            assertEquals(next, fixture.server.getScheduledGameStart().getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
            List<String> frames = held.frames();
            int end = frames.indexOf(MESSAGE + NetMsg.NETMSG_ENDGAME);
            assertTrue("The cancelled round's END did not precede the replacement START", end >= 0 && end < frames.size() - 1);
            assertFalse(held.closed);
            assertTrue((boolean) get(fixture.clients.get(1), "clockSynchronized"));
            assertEquals(0L, fixture.server.getNextGameStartWaitMillis());
        }
    }

    @Test public void staleMissingAndMalformedTokensCannotCancelTheCurrentCountdown() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.add(1, false);
            String current = fixture.start();
            for (String invalid : new String[] {null, "", "invalid", UUID.randomUUID().toString()}) {
                assertFalse(fixture.server.canCancelGameBeforeStart(invalid));
                assertFalse(fixture.server.cancelGameBeforeStart(invalid));
            }
            assertTrue(fixture.server.canCancelGameBeforeStart(current));
            assertFalse((boolean) get(fixture.server, "mEndingGame"));
            assertEquals(0, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
        }
    }

    @Test public void stoppedCancelledEndingAndCompletedSessionsCannotBeCancelledAgain() throws Exception {
        for (String flag : new String[] {"mDestroyed", "mEndingGame", "mRoundEndDelivered", "keepListening", "mCancellationThread"}) {
            try (Fixture fixture = new Fixture(true)) {
                fixture.add(1, false);
                String token = fixture.start();
                set(fixture.server, flag, flag.equals("mCancellationThread") ? Thread.currentThread()
                        : !flag.equals("keepListening"));
                assertFalse(flag, fixture.server.canCancelGameBeforeStart(token));
                assertFalse(flag, fixture.server.cancelGameBeforeStart(token));
                assertEquals(0, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
                if (flag.equals("mCancellationThread")) set(fixture.server, flag, null);
            }
        }
    }

    @Test public void aRemotePlayersSingleVoteStillCannotCancelTheHostsCountdown() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.add(1, false);fixture.add(17, false);
            String token = fixture.start();
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_END_VOTE, true)
                    .put(TcpServer.JSON_ROUND_TOKEN, token));
            fixture.settle();
            assertEquals(0, fixture.eventCount(NetMsg.NETMSG_ENDGAME));
            assertEquals(EndGameVotes.playerBit(1), Globals.getInstance().mEndGameVotes);
            assertNotNull(fixture.server.getScheduledGameStart());
            assertTrue(fixture.server.canCancelGameBeforeStart(token));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingServer server = new RecordingServer();
        final Map<Integer, Object> clients = new ConcurrentHashMap<>();
        final List<MemorySocket> sockets = new ArrayList<>();
        final Object previousGlobals;
        Fixture(boolean dedicated) throws Exception {
            previousGlobals = get(Globals.class, "mInstance");set(Globals.class, "mInstance", null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = dedicated ? 0 : (byte) 32;
            globals.mLocalLobbyReady = true;
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mUseGPS = false;
            globals.mBalancedRandom = false;globals.mBossMode = false;globals.mInfectionMode = false;
            server.setDedicated(dedicated);
            set(server, "mClientData", clients);set(server, "keepListening", true);
        }
        MemorySocket add(int id, boolean hold) throws Exception {
            MemorySocket socket = new MemorySocket(id, hold);sockets.add(socket);
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);constructor.setAccessible(true);
            Object client = constructor.newInstance(server);
            assertTrue((boolean) invoke(client, "initialize", new Class<?>[] {Socket.class, int.class}, socket, id));
            set(client, "mPlayerID", (byte) id);set(client, "clockSynchronized", true);
            clients.put(id, client);
            InetAddress address = socket.getInetAddress();
            Globals globals = Globals.getInstance();
            globals.mTeamIPMap.put((byte) id, address);globals.mIPTeamMap.put(address, (byte) id);
            globals.mTeamPlayerNameMap.put((byte) id, "Player " + id);
            return socket;
        }
        String start() throws Exception {
            int before = eventCount(NetMsg.NETMSG_STARTGAME);
            assertTrue(server.startGame());
            await(() -> eventCount(NetMsg.NETMSG_STARTGAME) == before + 1, "START did not flush and announce");
            settle();
            return server.getScheduledGameStart().getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
        }
        long deadline() { return server.getScheduledGameStart().getLongExtra(NetMsg.INTENT_START_AT, -1); }
        int eventCount(String action) {
            int count = 0;for (Intent event : server.events) if (action.equals(event.getAction())) count++;
            return count;
        }
        void parse(int id, JSONObject frame) throws Exception {
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientThread");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);constructor.setAccessible(true);
            Object client = clients.get(id);
            invoke(constructor.newInstance(server), "parsePlayerInfo", new Class<?>[] {String.class, client.getClass()},
                    frame.toString(), client);
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
                        if ((boolean) get(client, "writing") || !((Queue<?>) get(client, "outbound")).isEmpty()) return false;
                    }
                    return !(boolean) get(server, "mStartingGame");
                } catch (Exception failure) { throw new AssertionError(failure); }
            }, "Server round tasks or ordered writes did not finish");
        }
        @Override public void close() throws Exception {
            for (MemorySocket socket : sockets) socket.released.countDown();
            server.onDestroy();
            await(() -> {
                try { return tasksEmpty() && ((Set<?>) get(server, "mClientWriters")).isEmpty(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }, "Server worker survived cleanup");
            set(Globals.class, "mInstance", previousGlobals);
        }
    }
    private static final class RecordingServer extends TcpServer {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) { events.add(intent); }
    }
    private static final class MemorySocket extends Socket {
        final int id;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        volatile boolean closed;
        boolean hold;
        MemorySocket(int id, boolean hold) { this.id = id;this.hold = hold; }
        @Override public InetAddress getInetAddress() {
            try { return InetAddress.getByName("127.0.0." + id); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
                @Override public void write(byte[] values, int offset, int length) throws IOException {
                    if (hold) {
                        hold = false;entered.countDown();
                        try { if (!released.await(2, TimeUnit.SECONDS)) throw new IOException("Held START timed out"); }
                        catch (InterruptedException cancelled) { Thread.currentThread().interrupt();throw new IOException(cancelled); }
                    }
                    if (closed) throw new IOException("Socket closed");
                    bytes.write(values, offset, length);
                }
            };
        }
        @Override public void close() { closed = true;released.countDown(); }
        List<String> frames() throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            List<String> frames = new ArrayList<>();while (input.available() > 0) frames.add(input.readUTF());return frames;
        }
    }
    private static void advanceTo(long target) {
        long delay = target - SystemClock.elapsedRealtime();
        assertTrue("Cannot move monotonic time backwards", delay >= 0);
        ShadowSystemClock.advanceBy(Duration.ofMillis(delay));
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);
        }
        assertTrue(message, condition.getAsBoolean());
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> owner = target instanceof TcpServer ? TcpServer.class : target.getClass();
        Method method = owner.getDeclaredMethod(name, types);method.setAccessible(true);return method.invoke(target, args);
    }
    private static Object get(Object target, String name) throws Exception {
        Class<?> owner = target instanceof Class ? (Class<?>) target
                : target instanceof TcpServer ? TcpServer.class : target.getClass();
        Field field = owner.getDeclaredField(name);field.setAccessible(true);return field.get(target instanceof Class ? null : target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> owner = target instanceof Class ? (Class<?>) target
                : target instanceof TcpServer ? TcpServer.class : target.getClass();
        Field field = owner.getDeclaredField(name);field.setAccessible(true);field.set(target instanceof Class ? null : target, value);
    }
}
