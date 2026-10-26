package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** A retained clock must be verified against the same live host before reuse. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class TcpClientStickyClockTest {
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
    private static final long OFFSET = 5000;

    @Test public void automaticReconnectRevalidatesOneProbeWithoutReplacingTheOffset() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String epoch = UUID.randomUUID().toString();
            fixture.calibrate(fixture.current, epoch, OFFSET, GameClock.SAMPLES_PER_SYNC);
            GameClock clock = clock(fixture.client);
            long generation = (long) get(fixture.client, "mSessionGeneration");
            assertEquals(95000, clock.toLocalTime(100000));
            fixture.current.send(roster());
            await(fixture.client::hasLobbyConnection);

            Connection replacement = fixture.addConnection();
            // Close the client's actual socket so its reader follows the real
            // automatic reconnect path, including registration and send order.
            fixture.current.clientSocket.close();
            await(() -> fixture.client.connections.get() == 2);
            await(() -> (boolean) get(fixture.client, "mClockResumePending"));
            assertFalse("An unverified reconnect exposed clock readiness", fixture.client.isClockSynchronized());
            assertEquals(GameClock.SAMPLES_PER_SYNC, clock.samples());
            fixture.calibrate(replacement, epoch, OFFSET + 6, 1);
            replacement.send(roster());
            await(fixture.client::hasLobbyConnection);
            assertSame(clock, clock(fixture.client));
            assertEquals(generation, get(fixture.client, "mSessionGeneration"));
            assertEquals("One noisier verification probe replaced the committed offset",
                    95000, clock.toLocalTime(100000));
            replacement.output.writeUTF(TcpServer.TCP_SERVER_PING);
            replacement.output.flush();
            assertEquals(TcpClient.TCP_CLIENT_PONG, replacement.input.readUTF());

            fixture.client.stopTcpClient();
            await(() -> !(boolean) get(fixture.client, "isListening"));
            assertFalse(fixture.client.isClockSynchronized());
            assertEquals(0, clock.samples());
            assertNull(get(fixture.client, "mClockEpoch"));
            try { clock.toLocalTime(100000);fail("Explicit stop retained a previous lobby's offset"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void changedMissingOrNewlyAdvertisedEpochAndChangedOffsetRequireTwelveSamples() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            RecordingClient client = new RecordingClient();
            try {
                String previous = variant == 3 ? null : UUID.randomUUID().toString();
                String next = variant == 0 || variant == 3 ? UUID.randomUUID().toString()
                        : variant == 1 ? null : previous;
                long nextOffset = variant == 2 ? OFFSET + 500 : OFFSET;
                seed(client, previous);
                if (variant != 3) reconnect(client);
                for (int sample = 1; sample <= GameClock.SAMPLES_PER_SYNC; sample++) {
                    reply(client, next, nextOffset, 20);
                    assertEquals(sample, clock(client).samples());
                    assertEquals("A changed host clock resumed without a full calibration",
                            sample == GameClock.SAMPLES_PER_SYNC, client.isClockSynchronized());
                }
                assertEquals(100000 - nextOffset, clock(client).toLocalTime(100000));
                assertEquals(next, get(client, "mClockEpoch"));
            } finally { client.onDestroy(); }
        }
    }

    @Test public void excessiveDelayCannotResumeUntilALowDelayProbeIsAccepted() throws Exception {
        RecordingClient client = new RecordingClient();
        try {
            String epoch = UUID.randomUUID().toString();
            seed(client, epoch);
            reconnect(client);
            reply(client, epoch, OFFSET, GameClock.MAX_SAMPLE_DELAY_MS + 1);
            assertFalse(client.isClockSynchronized());
            assertTrue((boolean) get(client, "mClockResumePending"));
            assertEquals(GameClock.SAMPLES_PER_SYNC, clock(client).samples());
            assertEquals(95000, clock(client).toLocalTime(100000));
            reply(client, epoch, OFFSET + 10, 20);
            assertTrue(client.isClockSynchronized());
            assertFalse((boolean) get(client, "mClockResumePending"));
            assertEquals(95000, clock(client).toLocalTime(100000));
        } finally { client.onDestroy(); }
    }

    @Test public void backgroundRefreshKeepsReadinessAndCommittedOffsetUntilItsTwelfthReply() throws Exception {
        RecordingClient client = new RecordingClient();
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        try {
            String epoch = UUID.randomUUID().toString();
            seed(client, epoch);
            set(client, "keepListening", true);
            set(client, "out", new DataOutputStream(written));
            set(client, "mLastClockSync", SystemClock.elapsedRealtime());
            ShadowSystemClock.advanceBy(Duration.ofMillis(30000));
            invoke(client, "requestClockSync", new Class<?>[0]);
            assertTrue(client.isClockSynchronized());
            assertEquals(0, clock(client).samples());
            for (int sample = 1; sample <= GameClock.SAMPLES_PER_SYNC; sample++) {
                final int expectedFrames = sample;
                await(() -> frameCount(written) >= expectedFrames
                        && (long) get(client, "mPendingClockRequest") >= 0);
                long sent = (long) get(client, "mPendingClockRequest");
                ShadowSystemClock.advanceBy(Duration.ofMillis(20));
                receive(client, response(sent, epoch, OFFSET + 40, 20), sent + 20);
                assertTrue("A normal background refresh hid an already calibrated clock",
                        client.isClockSynchronized());
                assertEquals(sample, clock(client).samples());
                assertEquals(sample < GameClock.SAMPLES_PER_SYNC ? 95000 : 94960,
                        clock(client).toLocalTime(100000));
            }
            assertEquals(GameClock.SAMPLES_PER_SYNC, frameCount(written));
        } finally { client.onDestroy(); }
    }

    @Test public void reconnectDuringBackgroundSamplingRetainsTheLastCommittedOffset() throws Exception {
        RecordingClient client = new RecordingClient();
        try {
            String epoch = UUID.randomUUID().toString();
            seed(client, epoch);
            clock(client).beginSampling();
            reply(client, epoch, OFFSET + 40, 20);
            assertEquals(1, clock(client).samples());
            assertEquals(95000, clock(client).toLocalTime(100000));
            reconnect(client);
            assertTrue((boolean) get(client, "mClockResumePending"));
            reply(client, epoch, OFFSET + 6, 20);
            assertTrue(client.isClockSynchronized());
            assertEquals(GameClock.SAMPLES_PER_SYNC, clock(client).samples());
            assertEquals(95000, clock(client).toLocalTime(100000));
        } finally { client.onDestroy(); }
    }

    @Test public void expiredCalibrationFallsBackToAFullBatchOnReconnect() throws Exception {
        RecordingClient client = new RecordingClient();
        try {
            String epoch = UUID.randomUUID().toString();
            seed(client, epoch);
            ShadowSystemClock.advanceBy(Duration.ofMillis(GameClock.MAX_RETAINED_AGE_MS + 1));
            reconnect(client);
            assertFalse((boolean) get(client, "mClockResumePending"));
            assertEquals(0, clock(client).samples());
            for (int sample = 1; sample <= GameClock.SAMPLES_PER_SYNC; sample++) {
                reply(client, epoch, OFFSET, 20);
                assertEquals(sample == GameClock.SAMPLES_PER_SYNC, client.isClockSynchronized());
            }
        } finally { client.onDestroy(); }
    }

    private static void seed(TcpClient client, String epoch) throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
        long now = SystemClock.elapsedRealtime();
        for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++)
            assertTrue(clock(client).record(now - 20, now - 10 + OFFSET, now - 10 + OFFSET, now));
        set(client, "mClockSynchronized", true);
        set(client, "mClockEpoch", epoch);
    }

    private static void reconnect(TcpClient client) throws Exception {
        invoke(client, "resetClockSyncLocked", new Class<?>[]{boolean.class}, true);
        assertFalse(client.isClockSynchronized());
    }

    private static void reply(TcpClient client, String epoch, long offset, long delay) throws Exception {
        long sent = SystemClock.elapsedRealtime();
        set(client, "mPendingClockRequest", sent);
        ShadowSystemClock.advanceBy(Duration.ofMillis(delay));
        receive(client, response(sent, epoch, offset, delay), sent + delay);
    }

    private static JSONObject response(long sent, String epoch, long offset, long delay) throws Exception {
        long host = sent + offset + delay / 2;
        JSONObject reply = new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, sent)
                .put(TcpServer.JSON_CLOCK_RECEIVE, host).put(TcpServer.JSON_CLOCK_SEND, host);
        if (epoch != null) reply.put(TcpServer.JSON_CLOCK_EPOCH, epoch);
        return reply;
    }

    private static void receive(TcpClient client, JSONObject reply, long received) throws Exception {
        invoke(client, "receiveClockSync", new Class<?>[]{JSONObject.class, long.class, long.class},
                reply, received, get(client, "mSessionGeneration"));
    }

    private static int frameCount(ByteArrayOutputStream written) throws Exception {
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(written.toByteArray()));
        int count = 0;
        while (frames.available() > 0) {
            try {
                frames.readUTF();
                count++;
            } catch (EOFException incomplete) {
                break;
            }
        }
        return count;
    }

    private static JSONObject roster() throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERS, new JSONArray().put(new JSONObject()
                .put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAME, "Player 1")
                .put(TcpServer.JSON_PLAYERIP, "127.0.0.1")))
                .put(TcpServer.JSON_LIMITS, new JSONObject())
                .put(TcpServer.JSON_GAMEMODE, Globals.GAME_MODE_2TEAMS)
                .put(TcpServer.JSON_TOURNAMENT_MODE, false)
                .put(TcpServer.JSON_ONLY_SERVER_SETTINGS, false)
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, true)
                .put(TcpServer.JSON_DEDICATED, true)
                .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_NONE);
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.ready() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Client did not complete the expected transition", condition.ready());
    }

    private static GameClock clock(TcpClient client) throws Exception { return (GameClock) get(client, "mGameClock"); }
    private static Object get(TcpClient client, String name) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);field.setAccessible(true);return field.get(client);
    }
    private static void set(TcpClient client, String name, Object value) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);field.setAccessible(true);field.set(client, value);
    }
    private static void invoke(TcpClient client, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = TcpClient.class.getDeclaredMethod(name, types);method.setAccessible(true);method.invoke(client, args);
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingClient client = new RecordingClient();
        final List<Connection> connections = new CopyOnWriteArrayList<>();
        final Field singleton;
        final Object previousGlobals;
        Connection current;
        Fixture() throws Exception {
            singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            previousGlobals = singleton.get(null);singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;globals.mPlayerName = "Player 1";
            globals.mServerIP = InetAddress.getLoopbackAddress();
            globals.mGameState = Globals.GAME_STATE_NONE;globals.mUseGPS = false;
            current = addConnection();
            client.startTcpClient();
        }
        Connection addConnection() throws Exception {
            Connection connection = new Connection();
            connections.add(connection);
            client.supplied.offer(connection.clientSocket);
            return connection;
        }
        void calibrate(Connection connection, String epoch, long offset, int expected) throws Exception {
            int samples = 0;
            while (true) {
                String frame = connection.input.readUTF();
                if (!frame.startsWith(JSON)) continue;
                JSONObject request = new JSONObject(frame.substring(JSON.length()));
                if (request.optBoolean(TcpServer.JSON_CLOCK_READY)) {
                    assertEquals(epoch, request.getString(TcpServer.JSON_CLOCK_EPOCH));
                    break;
                }
                if (!request.has(TcpServer.JSON_CLOCK_REQUEST)) continue;
                long sent = request.getLong(TcpServer.JSON_CLOCK_REQUEST);
                ShadowSystemClock.advanceBy(Duration.ofMillis(2));
                connection.send(response(sent, epoch, offset, 2));
                samples++;
            }
            assertEquals(expected, samples);
            assertTrue(client.isClockSynchronized());
        }
        @Override public void close() throws Exception {
            client.onDestroy();
            for (Connection connection : connections) connection.close();
            await(() -> !(boolean) get(client, "isListening"));
            singleton.set(null, previousGlobals);
        }
    }

    private static final class Connection implements AutoCloseable {
        final Socket clientSocket, peer;
        final DataInputStream input;
        final DataOutputStream output;
        Connection() throws Exception {
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                listener.setSoTimeout(3000);
                clientSocket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                clientSocket.setTcpNoDelay(true);
                peer = listener.accept();peer.setTcpNoDelay(true);peer.setSoTimeout(4000);
            }
            input = new DataInputStream(peer.getInputStream());
            output = new DataOutputStream(peer.getOutputStream());
        }
        void send(JSONObject value) throws Exception { output.writeUTF(JSON + value);output.flush(); }
        @Override public void close() throws Exception { clientSocket.close();peer.close(); }
    }

    private static final class RecordingClient extends TcpClient {
        final LinkedBlockingQueue<Socket> supplied = new LinkedBlockingQueue<>();
        final AtomicInteger connections = new AtomicInteger();
        final List<Intent> events = new CopyOnWriteArrayList<>();
        @Override Socket connectGameSocket(Socket nativeSocket, InetAddress address) throws IOException {
            nativeSocket.close();
            try {
                Socket socket = supplied.poll(3, TimeUnit.SECONDS);
                if (socket == null) throw new IOException("No supplied reconnect socket");
                connections.incrementAndGet();
                return socket;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Reconnect interrupted", e);
            }
        }
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
    }
}
