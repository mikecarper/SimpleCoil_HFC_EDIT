package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import com.simplecoil.protocol.RoundRoster;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Native disconnect/rejoin races must preserve the person, not just their reusable seat. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class TcpServerIdentityOwnershipTest {
    private static final String ALICE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String BOB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String TOKEN = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
    private static final String EVENT = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
            + NetMsg.NETMSG_ELIMINATED + 17;

    @Test public void aDifferentPersonReusingADeadLobbySeatGetsFreshIdentityScoreAndEvents() throws Exception {
        for (boolean rejoin : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(false)) {
                fixture.failOldSocketAndQueueEvent();
                fixture.registerReplacement(BOB, rejoin);
                List<String> frames = fixture.readUntilRoster();
                assertFreshSeat(fixture, BOB, frames);
                assertEquals(2, get(fixture.candidate, "priorKills"));
                assertEquals(3, get(fixture.candidate, "priorDeaths"));
                assertNull(get(fixture.candidate, "historyRoundApplied"));
                assertFalse((boolean) get(fixture.candidate, "awaitingRespawn"));
                assertFalse((boolean) get(fixture.candidate, "clockSynchronized"));
            }
        }
    }

    @Test public void theSamePersonRetainsReliableReplayScoreAndHistoryInLobbyAndActiveRound() throws Exception {
        for (boolean active : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture(active)) {
                fixture.failOldSocketAndQueueEvent();
                fixture.registerReplacement(ALICE, true);
                List<String> frames = fixture.readUntilRoster();
                assertEquals(1, java.util.Collections.frequency(frames, EVENT));
                assertSame(fixture.old, fixture.clients.get(15));
                assertFalse(fixture.clients.containsKey(16));
                assertEquals(ALICE, get(fixture.old, "playerIdentity"));
                assertEquals(23, get(fixture.old, "points"));
                assertEquals(7, get(fixture.old, "eliminated"));
                assertEquals(TOKEN, get(fixture.old, "historyRoundApplied"));
                assertEquals(2, get(fixture.old, "priorKills"));
                assertEquals(3, get(fixture.old, "priorDeaths"));
                synchronized (fixture.old) {
                    assertTrue(((Queue<?>) get(fixture.old, "messageQueue")).isEmpty());
                }
            }
        }
    }

    @Test public void anActiveRoundStillRejectsADifferentPersonBeforeAnyReplay() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.failOldSocketAndQueueEvent();
            fixture.registerReplacement(BOB, true);
            String frame = fixture.input.readUTF();
            assertTrue(frame.startsWith(JSON));
            assertTrue(new JSONObject(frame.substring(JSON.length())).getBoolean(LiveJoin.REJECTED));
            assertEof(fixture.input);
            assertEquals(ALICE, get(fixture.old, "playerIdentity"));
            assertTrue(((Queue<?>) get(fixture.old, "messageQueue")).contains(EVENT));
        }
    }

    @Test public void aNewLegacySocketWithoutIdentityCannotInheritAnotherPersonsDeadSeat() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            String generated = (String) get(fixture.candidate, "playerIdentity");
            fixture.failOldSocketAndQueueEvent();
            fixture.registerReplacement(null, true);
            assertFreshSeat(fixture, generated, fixture.readUntilRoster());
        }
    }

    @Test public void anExistingSocketCannotChangeItsPersonOrSeatWhileKeepingPriorState() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            DataOutputStream output = new DataOutputStream(fixture.oldPhone.getOutputStream());
            for (int id : new int[] {1, 17}) {
                JSONObject registration = registration(BOB, true).put(TcpServer.JSON_PLAYERID, id);
                output.writeUTF(JSON + registration);
            }
            output.writeUTF(JSON + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 42));
            output.flush();
            fixture.releaseRoster();
            readClockReply(new DataInputStream(fixture.oldPhone.getInputStream()), 42);
            assertEquals(ALICE, get(fixture.old, "playerIdentity"));
            assertEquals((byte) 1, get(fixture.old, "mPlayerID"));
            assertEquals(23, get(fixture.old, "points"));
            assertEquals("Alice", Globals.getInstance().getPlayerName((byte) 1));
            assertFalse(Globals.getInstance().mTeamPlayerNameMap.containsKey((byte) 17));
        }
    }

    @Test public void repeatedLegacyRegistrationOnTheSameSocketRetainsItsExistingIdentity() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            DataOutputStream output = new DataOutputStream(fixture.oldPhone.getOutputStream());
            output.writeUTF(JSON + registration(null, true));
            output.flush();
            fixture.releaseRoster();
            List<String> frames = readUntilRoster(new DataInputStream(fixture.oldPhone.getInputStream()));
            assertSame(fixture.old, fixture.clients.get(15));
            assertEquals(ALICE, get(fixture.old, "playerIdentity"));
            assertEquals(23, scoreboard(frames).getInt(TcpServer.JSON_PLAYERPOINTS));
        }
    }

    @Test public void aDifferentPersonCannotDisplaceAStillConnectedLobbyOwner() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.registerReplacement(BOB, true);
            assertEof(fixture.input);
            assertSame(fixture.old, fixture.clients.get(15));
            assertNotNull(get(fixture.old, "clientSocket"));
            assertEquals(ALICE, get(fixture.old, "playerIdentity"));
            assertEquals(23, get(fixture.old, "points"));
        }
    }

    @Test public void theNextActualStartUsesTheNewPersonsIdentityAfterNormalClockCalibration() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.failOldSocketAndQueueEvent();
            fixture.registerReplacement(BOB, true);
            assertFreshSeat(fixture, BOB, fixture.readUntilRoster());
            String epoch = null;
            for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++) {
                long request = 1000 + i;
                fixture.send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, request));
                epoch = readClockReply(fixture.input, request).getString(TcpServer.JSON_CLOCK_EPOCH);
            }
            fixture.send(new JSONObject().put(TcpServer.JSON_CLOCK_READY, true)
                    .put(TcpServer.JSON_CLOCK_EPOCH, epoch));
            await(() -> {
                try { return (boolean) get(fixture.candidate, "clockSynchronized"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(fixture.server.startGame());
            JSONObject start;
            do {
                String frame = fixture.input.readUTF();
                start = frame.startsWith(JSON) ? new JSONObject(frame.substring(JSON.length())) : new JSONObject();
            } while (!start.has(TcpServer.JSON_GAMESTART));
            RoundRoster roster = RoundRoster.decode(start.getString(LiveJoin.ROSTER));
            assertNotNull(roster);
            assertNull("The previous owner's identity followed the reused seat into a new game", roster.person(ALICE));
            assertEquals(1, roster.person(BOB).id);
        }
    }

    @Test public void aNewPhoneCanJoinARunningFreeForAllThroughAStandardTeamQr() throws Exception {
        assertRunningQrJoin(Globals.GAME_MODE_FFA, 4, 2, 0);
    }

    @Test public void aNewPhoneCanJoinAndCalibrateInARunningTwoTeamMatchWithinFiveSeconds() throws Exception {
        assertRunningQrJoin(Globals.GAME_MODE_2TEAMS, 2, 17, 2);
    }

    private static void assertRunningQrJoin(int gameMode, int qrTeam, int expectedID, int expectedTeam)
            throws Exception {
        try (Fixture fixture = new Fixture(true, gameMode)) {
            long originalStart = (long) get(fixture.server, "mScheduledStart");
            long originalDuration = 60_000;
            long roundID = 7;
            set(fixture.server, "mRoundSequence", roundID);
            set(fixture.server, "mScheduledDuration", originalDuration);
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            Globals.getInstance().mTimeLimit = 1;
            long began = System.nanoTime();
            fixture.send(registration(BOB, false).put(TcpServer.JSON_PLAYERID, 17).put(LiveJoin.TEAM, qrTeam));
            fixture.releaseRoster();
            List<String> frames = fixture.readUntilRoster();
            JSONObject assigned = null;
            JSONObject game = null;
            for (String frame : frames) {
                if (!frame.startsWith(JSON)) continue;
                JSONObject value = new JSONObject(frame.substring(JSON.length()));
                if (value.has(LiveJoin.ASSIGNED)) assigned = value;
                if (value.has(TcpServer.JSON_PLAYERS)) game = value;
            }
            assertNotNull("The running host rejected the standard QR admission", assigned);
            assertEquals(expectedID, assigned.getInt(LiveJoin.ASSIGNED));
            assertEquals(TOKEN, assigned.getString(TcpServer.JSON_ROUND_TOKEN));
            assertNotNull(game);
            RoundRoster round = RoundRoster.decode(game.getString(LiveJoin.ROSTER));
            assertEquals(expectedTeam, round.person(BOB).team);
            assertEquals(expectedID, round.person(BOB).id);
            assertEquals(gameMode == Globals.GAME_MODE_FFA ? 0 : 1, round.person(ALICE).team);
            assertEquals(Globals.GAME_STATE_RUNNING, game.getInt(TcpServer.JSON_GAMESTATE));
            assertEquals(roundID, game.getLong(TcpServer.JSON_ROUND_ID));
            assertEquals(originalStart, game.getLong(TcpServer.JSON_GAMESTART));
            assertEquals(originalDuration, game.getLong(TcpServer.JSON_GAMEDURATION));
            assertEquals(1, game.getJSONObject(TcpServer.JSON_LIMITS).getInt(TcpServer.JSON_TIMELIMIT));
            for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++) {
                long request = 2000 + sample;
                fixture.send(new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, request));
                readClockReply(fixture.input, request);
            }
            fixture.send(new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
            await(() -> {
                try { return (boolean) get(fixture.candidate, "clockSynchronized"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            fixture.server.sendAllGameInfo(expectedID);
            game = null;
            for (String frame : fixture.readUntilRoster()) {
                if (!frame.startsWith(JSON)) continue;
                JSONObject value = new JSONObject(frame.substring(JSON.length()));
                if (value.has(TcpServer.JSON_PLAYERS)) game = value;
            }
            assertNotNull(game);
            boolean clockReady = false;
            org.json.JSONArray players = game.getJSONArray(TcpServer.JSON_PLAYERS);
            for (int index = 0; index < players.length(); index++) {
                JSONObject player = players.getJSONObject(index);
                if (player.getInt(TcpServer.JSON_PLAYERID) == expectedID)
                    clockReady = player.getBoolean(TcpServer.JSON_CLOCK_READY);
            }
            assertTrue("The admitted player was not clock-ready in the published roster", clockReady);
            assertEquals(roundID, game.getLong(TcpServer.JSON_ROUND_ID));
            assertEquals(originalStart, game.getLong(TcpServer.JSON_GAMESTART));
            assertEquals(originalDuration, game.getLong(TcpServer.JSON_GAMEDURATION));
            assertEquals(1, game.getJSONObject(TcpServer.JSON_LIMITS).getInt(TcpServer.JSON_TIMELIMIT));
            RoundRoster calibrated = RoundRoster.decode(game.getString(LiveJoin.ROSTER));
            assertEquals(expectedID, calibrated.person(BOB).id);
            assertEquals(expectedTeam, calibrated.person(BOB).team);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            System.out.println("Running QR join mode=" + gameMode + " registered and clock-ready in " + elapsed + "ms");
            assertTrue("Loopback admission and full clock calibration took " + elapsed + "ms", elapsed < 5_000);
            assertEquals("Late admission restarted the original match timer", originalStart,
                    (long) get(fixture.server, "mScheduledStart"));
            assertSame(fixture.candidate, fixture.clients.get(16));
            assertNotNull(get(fixture.old, "clientSocket"));
            DataOutputStream existing = new DataOutputStream(fixture.oldPhone.getOutputStream());
            existing.writeUTF(JSON + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 4242));existing.flush();
            assertEquals(4242, readClockReply(new DataInputStream(fixture.oldPhone.getInputStream()), 4242)
                    .getLong(TcpServer.JSON_CLOCK_REQUEST));
        }
    }

    private static void assertFreshSeat(Fixture fixture, String identity, List<String> frames) throws Exception {
        assertFalse("A prior person's reliable event reached a new occupant", frames.contains(EVENT));
        assertFalse("The prior person's ClientData stayed attached to the reused seat", fixture.clients.containsKey(15));
        assertSame(fixture.candidate, fixture.clients.get(16));
        assertEquals(identity, get(fixture.candidate, "playerIdentity"));
        for (String field : new String[] {"points", "eliminated", "shots", "hits"})
            assertEquals("A new occupant inherited " + field, 0, get(fixture.candidate, field));
        assertEquals(0, scoreboard(frames).getInt(TcpServer.JSON_PLAYERPOINTS));
        assertEquals(0, scoreboard(frames).getInt(TcpServer.JSON_PLAYERELIMINATED));
    }

    private static JSONObject scoreboard(List<String> frames) throws Exception {
        for (String frame : frames) {
            if (!frame.startsWith(JSON)) continue;
            JSONObject json = new JSONObject(frame.substring(JSON.length()));
            if (json.has(TcpServer.JSON_PLAYERGAMEUPDATE)) return json.getJSONObject(TcpServer.JSON_PLAYERGAMEUPDATE);
        }
        throw new AssertionError("No player-specific roster frame was received");
    }

    private static JSONObject registration(String identity, boolean rejoin) throws Exception {
        JSONObject value = new JSONObject().put(TcpServer.JSON_PLAYERID, 1)
                .put(TcpServer.JSON_PLAYERNAME, BOB.equals(identity) ? "Bob" : "Alice")
                .put(TcpServer.JSON_REJOIN, rejoin).put(TcpServer.JSON_PRIOR_KILLS, 2)
                .put(TcpServer.JSON_PRIOR_DEATHS, 3).put(TcpServer.JSON_LOBBY_READY, true);
        if (identity != null) value.put(LiveJoin.IDENTITY, identity);
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingServer server = new RecordingServer();
        final Map<Integer, Object> clients = new ConcurrentHashMap<>();
        final Object previousGlobals;
        final Object old, candidate;
        final Socket oldPhone, newPhone;
        final DataInputStream input;
        final DataOutputStream output;
        final Semaphore roster;
        final Thread poller;
        boolean held;

        Fixture(boolean active) throws Exception {
            this(active, Globals.GAME_MODE_2TEAMS);
        }

        Fixture(boolean active, int gameMode) throws Exception {
            previousGlobals = get(Globals.class, "mInstance");set(Globals.class, "mInstance", null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 0;
            globals.mGameState = active ? Globals.GAME_STATE_RUNNING : Globals.GAME_STATE_NONE;
            globals.mGameMode = gameMode;globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mUseGPS = false;globals.mBalancedRandom = false;globals.mBossMode = false;
            globals.mInfectionMode = false;globals.mTournamentMode = false;
            server.setDedicated(true);set(server, "keepListening", true);set(server, "mClientData", clients);
            if (active) {
                RoundRoster round = new RoundRoster(TOKEN);
                round.restore(new RoundRoster.Member(ALICE, 1, gameMode == Globals.GAME_MODE_FFA ? 0 : 1, 0));
                globals.mRoundRoster = round;
                set(server, "mStartAnnounced", true);set(server, "mRoundToken", TOKEN);
                set(server, "mScheduledStart", Math.max(0, SystemClock.elapsedRealtime() - 1));
            }
            Socket[] first = pair("127.0.0.15");oldPhone = first[0];
            Socket[] second = pair("127.0.0.16");newPhone = second[0];
            input = new DataInputStream(newPhone.getInputStream());output = new DataOutputStream(newPhone.getOutputStream());
            old = client(first[1], 15, 1);candidate = client(second[1], 16, 0);
            set(old, "playerIdentity", ALICE);set(old, "points", 23);set(old, "eliminated", 7);
            set(old, "shots", 44);set(old, "hits", 12);set(old, "clockSynchronized", true);
            set(old, "priorKills", 100);set(old, "priorDeaths", 55);set(old, "historyRoundApplied", TOKEN);
            set(old, "awaitingRespawn", true);
            globals.mTeamIPMap.put((byte) 1, first[1].getInetAddress());
            globals.mIPTeamMap.put(first[1].getInetAddress(), (byte) 1);globals.mTeamPlayerNameMap.put((byte) 1, "Alice");
            // Real map order services arriving connection16 before failed connection15's cleanup.
            assertEquals(Integer.valueOf(16), clients.keySet().iterator().next());
            roster = (Semaphore) get(server, "mClientDataSemaphore");roster.acquire();held = true;
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientThread");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);constructor.setAccessible(true);
            poller = new Thread((Runnable) constructor.newInstance(server), "Identity regression native read loop");
            set(server, "mClientThread", poller);poller.start();
        }

        Object client(Socket socket, int connection, int player) throws Exception {
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);constructor.setAccessible(true);
            Object value = constructor.newInstance(server);
            assertTrue((boolean) invoke(value, "initialize", new Class<?>[] {Socket.class, int.class}, socket, connection));
            set(value, "mPlayerID", (byte) player);clients.put(connection, value);return value;
        }

        void failOldSocketAndQueueEvent() throws Exception {
            oldPhone.setSoLinger(true, 0);oldPhone.close();
            assertTrue((boolean) invoke(old, "sendTCPMessage", new Class<?>[] {String.class, boolean.class}, EVENT, true));
            await(() -> {
                try { return (boolean) get(old, "connectionFailed") && get(old, "clientSocket") == null; }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(((Queue<?>) get(old, "messageQueue")).contains(EVENT));
        }

        void registerReplacement(String identity, boolean rejoin) throws Exception {
            send(registration(identity, rejoin));releaseRoster();
        }

        void send(JSONObject frame) throws Exception { output.writeUTF(JSON + frame);output.flush(); }
        List<String> readUntilRoster() throws Exception { return TcpServerIdentityOwnershipTest.readUntilRoster(input); }
        void releaseRoster() { if (held) { roster.release();held = false; } }

        @Override public void close() throws Exception {
            releaseRoster();server.onDestroy();oldPhone.close();newPhone.close();poller.join(2000);
            assertFalse("Native read loop survived cleanup", poller.isAlive());
            await(() -> {
                try { return ((Set<?>) get(server, "mClientWriters")).isEmpty(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            set(Globals.class, "mInstance", previousGlobals);
        }
    }

    private static Socket[] pair(String ip) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Socket phone = new Socket();phone.bind(new InetSocketAddress(InetAddress.getByName(ip), 0));
            phone.connect(listener.getLocalSocketAddress());phone.setSoTimeout(4000);phone.setTcpNoDelay(true);
            return new Socket[] {phone, listener.accept()};
        }
    }
    private static JSONObject readClockReply(DataInputStream input, long request) throws Exception {
        while (true) {
            String frame = input.readUTF();
            if (!frame.startsWith(JSON)) continue;
            JSONObject value = new JSONObject(frame.substring(JSON.length()));
            if (value.optLong(TcpServer.JSON_CLOCK_REQUEST, -1) == request) return value;
        }
    }
    private static List<String> readUntilRoster(DataInputStream input) throws Exception {
        List<String> frames = new ArrayList<>();
        while (true) {
            String frame = input.readUTF();frames.add(frame);
            if (frame.startsWith(JSON) && new JSONObject(frame.substring(JSON.length())).has(TcpServer.JSON_PLAYERS))
                return frames;
        }
    }
    private static void assertEof(DataInputStream input) throws Exception {
        try { input.readUTF();fail("Rejected socket stayed open"); } catch (EOFException expected) { }
    }
    private static final class RecordingServer extends TcpServer {
        @Override public void sendBroadcast(Intent value) { }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);
        }
        assertTrue("Timed out waiting for native connection state", condition.getAsBoolean());
    }
    private static Class<?> owner(Object value) {
        return value instanceof Class ? (Class<?>) value : value instanceof TcpServer ? TcpServer.class : value.getClass();
    }
    private static Object get(Object value, String field) throws Exception {
        Field item = owner(value).getDeclaredField(field);item.setAccessible(true);return item.get(value instanceof Class ? null : value);
    }
    private static void set(Object value, String field, Object replacement) throws Exception {
        Field item = owner(value).getDeclaredField(field);item.setAccessible(true);item.set(value instanceof Class ? null : value, replacement);
    }
    private static Object invoke(Object value, String method, Class<?>[] types, Object... args) throws Exception {
        Method item = owner(value).getDeclaredMethod(method, types);item.setAccessible(true);return item.invoke(value, args);
    }
}
