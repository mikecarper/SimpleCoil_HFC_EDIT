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
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real client socket/reader: normal round ends must not retire the lobby clock. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class TcpClientRoundRetentionTest {
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
    private static final String CONTROL = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG;

    @Test public void phoneAndLaptopRoundsReuseTheSameSocketAndClock() throws Exception {
        for (boolean dedicated : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(dedicated)) {
                GameClock clock = (GameClock) field(fixture.client, "mGameClock");
                long generation = (long) field(fixture.client, "mSessionGeneration");
                String first = UUID.randomUUID().toString();
                fixture.send(plan(1, first));
                await(() -> fixture.starts() == 1);
                RoundReport report = new RoundReport(first, 1, 3, 1, 20, 5, 2, 1, 100, 110, 60000, true);
                fixture.send(new JSONObject().put(RoundReport.KEY, report.toJson()));
                fixture.end();
                await(() -> fixture.ends() == 1);
                assertTrue(fixture.client.isClockSynchronized());
                assertTrue(fixture.client.hasLobbyConnection());
                assertEquals(dedicated, fixture.client.isDedicatedServer());
                assertEquals(generation, field(fixture.client, "mSessionGeneration"));
                assertEquals(3, fixture.client.getCompletedRoundReport(first).kills);
                assertNull(fixture.client.consumePendingGameStart(0));

                Intent end = fixture.client.consumePendingTerminalEvent();
                assertNotNull(end);
                assertTrue(end.getBooleanExtra(TcpClient.EXTRA_ROUND_END, false));
                assertEquals(first, end.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
                assertNull(fixture.client.consumePendingTerminalEvent());
                // A late old start, including a roster-bearing start, cannot
                // restart the completed round while the UI returns to lobby.
                fixture.send(plan(1, first));
                fixture.send(roster(dedicated).put(TcpServer.JSON_ROUND_ID, 1)
                        .put(TcpServer.JSON_ROUND_TOKEN, first)
                        .put(TcpServer.JSON_GAMESTART, SystemClock.elapsedRealtime() + 1000)
                        .put(TcpServer.JSON_GAMEDURATION, 60000));
                fixture.ping();
                assertEquals(1, fixture.starts());

                String second = UUID.randomUUID().toString();
                fixture.send(plan(2, second));
                await(() -> fixture.starts() == 2);
                // Delayed UI cleanup for round one must leave round two intact.
                fixture.client.finishRoundToLobby(first);
                fixture.client.finishRoundToLobby(null);
                assertEquals(second, fixture.client.consumePendingGameStart(0)
                        .getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
                fixture.end();
                await(() -> fixture.ends() == 2);
                fixture.ping();
                assertSame(clock, field(fixture.client, "mGameClock"));
                assertEquals(GameClock.SAMPLES_PER_SYNC, clock.samples());
                assertEquals(1, fixture.client.connections);
                assertTrue(fixture.client.isClockSynchronized());
                // A real host shutdown still stops the client and its clock.
                fixture.write(CONTROL + NetMsg.NETMSG_SERVERCANCEL);
                await(() -> !(boolean) field(fixture.client, "isListening"));
                assertFalse(fixture.client.isClockSynchronized());
                assertFalse(fixture.client.hasLobbyConnection());
            }
        }
    }

    @Test public void localCompletionCannotReplayItsRoundOrDiscardTheNextStart() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            String first = UUID.randomUUID().toString();
            fixture.send(plan(1, first));await(() -> fixture.starts() == 1);
            fixture.client.finishRoundToLobby(first);
            fixture.send(plan(1, first));fixture.ping();
            assertEquals(1, fixture.starts());
            assertTrue(fixture.client.isClockSynchronized());
            String second = UUID.randomUUID().toString();
            fixture.send(plan(2, second));await(() -> fixture.starts() == 2);
            fixture.client.finishRoundToLobby(first);
            assertEquals(second, fixture.client.consumePendingGameStart(0)
                    .getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        }
    }

    @Test public void anExpiredStartEndsOnlyItsRoundAndKeepsCalibration() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            String expired = UUID.randomUUID().toString();
            ShadowSystemClock.advanceBy(Duration.ofMillis(100));
            fixture.send(TcpServer.createStartInfo(1, 0, 1, expired));
            await(() -> fixture.ends() == 1);
            assertTrue(fixture.client.isClockSynchronized());
            assertTrue(fixture.client.hasLobbyConnection());
            fixture.send(plan(1, expired));fixture.ping();
            assertEquals(0, fixture.starts());
            fixture.send(plan(2, UUID.randomUUID().toString()));
            await(() -> fixture.starts() == 1);
            fixture.ping();
            assertEquals(1, fixture.client.connections);
        }
    }

    @Test public void restartedHostMayRestartRoundNumbersButNotACompletedToken() throws Exception {
        RecordingClient client = new RecordingClient();
        try {
            seedClock(client);
            String previous = UUID.randomUUID().toString();
            parse(client, plan(3, previous));
            client.finishRoundToLobby(previous);
            Method reconnectReset = TcpClient.class.getDeclaredMethod("resetClockSyncLocked");
            reconnectReset.setAccessible(true);reconnectReset.invoke(client);
            seedClock(client);
            parse(client, plan(3, previous));
            assertEquals("A reconnect revived an already completed token", 1, client.events.size());
            String restarted = UUID.randomUUID().toString();
            parse(client, plan(1, restarted));
            assertEquals(2, client.events.size());
            assertEquals(restarted, client.events.get(1).getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        } finally { client.onDestroy(); }
    }

    @Test public void pausedUiCanFinishTheOldRoundBeforeConsumingTheNextStart() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            fixture.send(plan(1, first));await(() -> fixture.starts() == 1);
            fixture.send(new JSONObject().put(RoundReport.KEY,
                    new RoundReport(first, 1, 2, 1, 10, 3, 2, 1, 100, 105, 60000, true).toJson()));
            fixture.end();await(() -> fixture.ends() == 1);
            fixture.send(plan(2, second));await(() -> fixture.starts() == 2);
            Intent previous = fixture.client.consumePendingTerminalEvent();
            assertEquals(first, previous.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
            assertEquals(2, fixture.client.getCompletedRoundReport(first).kills);
            fixture.client.finishRoundToLobby(first);
            assertEquals(second, fixture.client.consumePendingGameStart(0)
                    .getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
            assertTrue(fixture.client.isClockSynchronized());
        }
    }

    private static void seedClock(TcpClient client) throws Exception {
        GameClock clock = (GameClock) field(client, "mGameClock");
        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++)
            assertTrue(clock.record(1000, 1000, 1000, 1000));
        Field ready = TcpClient.class.getDeclaredField("mClockSynchronized");
        ready.setAccessible(true);ready.setBoolean(client, true);
    }
    private static void parse(TcpClient client, JSONObject value) throws Exception {
        Method parser = TcpClient.class.getDeclaredMethod("parseGameInfo", String.class);
        parser.setAccessible(true);parser.invoke(client, value.toString());
    }

    private static JSONObject plan(int id, String token) {
        return TcpServer.createStartInfo(id, SystemClock.elapsedRealtime() + 1000, 60000, token);
    }
    private static JSONObject roster(boolean dedicated) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERS, new JSONArray().put(new JSONObject()
                .put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAME, "Player 1")
                .put(TcpServer.JSON_PLAYERIP, "127.0.0.1")))
                .put(TcpServer.JSON_LIMITS, new JSONObject())
                .put(TcpServer.JSON_GAMEMODE, Globals.GAME_MODE_2TEAMS)
                .put(TcpServer.JSON_TOURNAMENT_MODE, false)
                .put(TcpServer.JSON_ONLY_SERVER_SETTINGS, false)
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, true)
                .put(TcpServer.JSON_DEDICATED, dedicated)
                .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_NONE);
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.ready() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Client did not complete the expected transition", condition.ready());
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingClient client = new RecordingClient();
        final Socket peer;
        final DataInputStream input;
        final DataOutputStream output;
        final Field globalsSingleton;
        final Object oldGlobals;
        Fixture(boolean dedicated) throws Exception {
            globalsSingleton = Globals.class.getDeclaredField("mInstance");globalsSingleton.setAccessible(true);
            oldGlobals = globalsSingleton.get(null);globalsSingleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;globals.mPlayerName = "Player 1";
            globals.mServerIP = InetAddress.getLoopbackAddress();
            globals.mGameState = Globals.GAME_STATE_NONE;globals.mUseGPS = false;
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                listener.setSoTimeout(3000);
                client.supplied = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                client.supplied.setTcpNoDelay(true);
                peer = listener.accept();peer.setTcpNoDelay(true);peer.setSoTimeout(3000);
            }
            input = new DataInputStream(peer.getInputStream());
            output = new DataOutputStream(peer.getOutputStream());
            client.startTcpClient();
            int samples = 0;
            while (true) {
                String frame = input.readUTF();
                if (!frame.startsWith(JSON)) continue;
                JSONObject request = new JSONObject(frame.substring(JSON.length()));
                if (request.optBoolean(TcpServer.JSON_CLOCK_READY)) break;
                if (!request.has(TcpServer.JSON_CLOCK_REQUEST)) continue;
                ShadowSystemClock.advanceBy(Duration.ofMillis(2));
                long now = SystemClock.elapsedRealtime();
                send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST,
                        request.getLong(TcpServer.JSON_CLOCK_REQUEST))
                        .put(TcpServer.JSON_CLOCK_RECEIVE, now).put(TcpServer.JSON_CLOCK_SEND, now));
                samples++;
            }
            assertEquals(GameClock.SAMPLES_PER_SYNC, samples);
            send(roster(dedicated));await(client::hasLobbyConnection);
        }
        void send(JSONObject value) throws Exception { write(JSON + value); }
        void write(String frame) throws Exception { output.writeUTF(frame);output.flush(); }
        void end() throws Exception { write(CONTROL + NetMsg.NETMSG_ENDGAME); }
        void ping() throws Exception {
            write(TcpServer.TCP_SERVER_PING);
            assertEquals("A round end retriggered registration or clock sampling",
                    TcpClient.TCP_CLIENT_PONG, input.readUTF());
        }
        int starts() { return count(NetMsg.NETMSG_STARTGAME); }
        int ends() { return count(NetMsg.NETMSG_ENDGAME); }
        int count(String action) {
            int count = 0;
            for (Intent event : client.events) if (action.equals(event.getAction())) count++;
            return count;
        }
        @Override public void close() throws Exception {
            client.onDestroy();peer.close();
            await(() -> !(boolean) field(client, "isListening"));
            globalsSingleton.set(null, oldGlobals);
        }
    }
    private static final class RecordingClient extends TcpClient {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        Socket supplied;
        int connections;
        @Override Socket connectGameSocket(Socket nativeSocket, InetAddress address) throws IOException {
            nativeSocket.close();connections++;
            if (connections > 1) throw new IOException("Unexpected round-end reconnect");
            return supplied;
        }
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
    }
}
