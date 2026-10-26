package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.Looper;

import org.json.JSONArray;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Runs the Android host's real polling loop and writers over ordered TCP sockets. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PhoneClockSyncLoadTest {
    private static final String JSON = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
    private static final long METADATA_PROCESSING_MS = 60;

    @Test
    public void phoneHostCalibratesLargeLobbiesWithinFiveSeconds() throws Exception {
        for (int gpsMode : new int[] {Globals.GPS_TEAMMATE, Globals.GPS_ALL})
            for (int count : new int[] {16, 24, 32}) verifyLobby(count, gpsMode);
    }

    @Test
    public void retainedClockEpochNeedsAFreshProbeBeforeReady() throws Exception {
        try (Fixture fixture = new Fixture(1, Globals.GPS_ALL)) {
            String epoch = (String) get(fixture.server, "mClockEpoch");
            java.util.UUID.fromString(epoch);
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true)
                    .put(TcpServer.JSON_CLOCK_EPOCH, epoch));
            assertFalse("An epoch alone bypassed clock sampling",
                    fixture.server.arePlayerClocksSynchronized());

            JSONObject reply = fixture.clockReply(1, 1000);
            assertEquals(epoch, reply.getString(TcpServer.JSON_CLOCK_EPOCH));
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, "true")
                    .put(TcpServer.JSON_CLOCK_EPOCH, epoch));
            assertFalse(fixture.server.arePlayerClocksSynchronized());
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true)
                    .put(TcpServer.JSON_CLOCK_EPOCH, 123));
            assertFalse("A non-string epoch acknowledged a retained clock",
                    fixture.server.arePlayerClocksSynchronized());
            fixture.parse(1, new JSONObject().put(TcpServer.JSON_CLOCK_READY, true)
                    .put(TcpServer.JSON_CLOCK_EPOCH, epoch));
            assertTrue("A fresh same-host probe did not restore clock readiness",
                    fixture.server.arePlayerClocksSynchronized());
            assertEquals(1, get(fixture.clients.get(1), "clockSamples"));
        }
    }

    @Test
    public void missingOrStaleClockEpochStillNeedsFullCalibration() throws Exception {
        for (String epoch : new String[] {null, "previous-host-epoch"}) {
            try (Fixture fixture = new Fixture(1, Globals.GPS_ALL)) {
                fixture.clockReply(1, 1000);
                JSONObject ready = new JSONObject().put(TcpServer.JSON_CLOCK_READY, true);
                if (epoch != null) ready.put(TcpServer.JSON_CLOCK_EPOCH, epoch);
                fixture.parse(1, ready);
                assertFalse("An absent or old epoch bypassed full clock calibration",
                        fixture.server.arePlayerClocksSynchronized());
                for (int sample = 1; sample < GameClock.SAMPLES_PER_SYNC; sample++)
                    fixture.clockReply(1, 1000 + sample);
                fixture.parse(1, ready);
                assertTrue("Legacy full calibration no longer satisfies clock readiness",
                        fixture.server.arePlayerClocksSynchronized());
            }
        }
    }

    @Test
    public void clockEpochIsStableAcrossClientsAndReconnectsButChangesForNewHost() throws Exception {
        try (Fixture fixture = new Fixture(2, Globals.GPS_ALL)) {
            String epoch = fixture.clockReply(1, 1000).getString(TcpServer.JSON_CLOCK_EPOCH);
            assertEquals(epoch, fixture.clockReply(2, 1000).getString(TcpServer.JSON_CLOCK_EPOCH));
            fixture.reconnect(1);
            fixture.server.clearScheduledStart();
            assertEquals(epoch, fixture.clockReply(1, 2000).getString(TcpServer.JSON_CLOCK_EPOCH));
            assertNotEquals("A replacement host reused the previous clock identity", epoch,
                    get(new RecordingServer(), "mClockEpoch"));
        }
    }

    @Test
    public void ordinaryRoundEndKeepsSocketsAndClocksForTheNextRound() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        WallClock clock = new WallClock();
        try (Fixture fixture = new Fixture(2, Globals.GPS_ALL)) {
            Globals globals = Globals.getInstance();
            globals.mAllowPlayerSettings = false;
            CountDownLatch initialized = new CountDownLatch(2), start = new CountDownLatch(1);
            List<Future<Result>> calibration = new ArrayList<>();
            for (Phone phone : fixture.phones)
                calibration.add(workers.submit(() -> phone.calibrate(2, initialized, start)));
            fixture.server.sendAllGameInfo(TcpServer.SEND_ALL);
            driveUntil(() -> initialized.getCount() == 0, clock, 3_000, "Initial roster missing");
            fixture.startPolling();start.countDown();
            driveUntil(() -> allDone(calibration), clock, 5_000, "Initial clocks did not synchronize");
            for (Future<Result> future : calibration) assertEquals(12, future.get().accepted);
            assertTrue(fixture.server.startGame());
            driveUntil(() -> fixture.server.getScheduledGameStart() != null && settled(fixture.server),
                    clock, 2_000, "First round did not start");
            String firstToken = fixture.server.getScheduledGameStart()
                    .getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
            for (Phone phone : fixture.phones) assertEquals(firstToken,
                    phone.readStart().getString(TcpServer.JSON_ROUND_TOKEN));
            // Make this a completed short round rather than a cancelled countdown.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS);
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            set(fixture.server, "mScheduledStart", android.os.SystemClock.elapsedRealtime() - 1_000);
            Object shooter = fixture.clients.get(1);
            Socket original = (Socket) get(shooter, "clientSocket");
            long lastProbe = (long) get(shooter, "lastClockRequestAt");
            set(shooter, "points", 3);set(shooter, "eliminated", 1);
            set(shooter, "shots", 6);set(shooter, "hits", 3);set(shooter, "awaitingRespawn", true);
            Map<?, ?> analytics = (Map<?, ?>) get(fixture.server, "mRoundAnalytics");
            Object stats = analytics.get(get(shooter, "playerIdentity"));
            set(stats, "kills", 3);set(stats, "deaths", 1);
            set(stats, "shots", 6);set(stats, "hits", 3);
            List<Future<RoundReport>> endings = new ArrayList<>();
            for (Phone phone : fixture.phones)
                endings.add(workers.submit(() -> { return phone.readRoundEnd(); }));
            assertTrue(fixture.server.requestOrderedRoundEnd());
            driveUntil(() -> allDone(endings) && settled(fixture.server), clock, 2_000,
                    "Final scores/report/ENDGAME did not arrive");
            RoundReport report = endings.get(0).get();
            assertEquals(firstToken, report.roundToken);
            assertEquals(3, report.kills);assertEquals(1, report.deaths);
            assertEquals(6, report.shots);assertEquals(3, report.hits);
            for (Future<RoundReport> ending : endings) assertEquals(firstToken, ending.get().roundToken);
            assertSame("Round end replaced the TCP socket", original, get(shooter, "clientSocket"));
            assertFalse(original.isClosed());
            assertEquals(2, fixture.clients.size());
            assertEquals(2, globals.mTeamIPMap.size());
            assertTrue(fixture.server.arePlayerClocksSynchronized());
            assertEquals(12, get(shooter, "clockSamples"));
            assertEquals(lastProbe, get(shooter, "lastClockRequestAt"));
            assertEquals("Final scores disappeared before the local UI could save them", 3,
                    fixture.server.getScore((byte) 1).points);
            assertEquals(3, get(shooter, "priorKills"));
            assertEquals(1, get(shooter, "priorDeaths"));
            assertTrue(((Map<?, ?>) get(fixture.server, "mRoundAnalytics")).isEmpty());
            int completions = 0;
            for (Intent event : fixture.server.events)
                if (NetMsg.NETMSG_ENDGAME.equals(event.getAction())) {
                    assertTrue(event.getBooleanExtra(TcpServer.EXTRA_ROUND_REPORTS_DELIVERED, false));
                    completions++;
                }
            assertEquals(1, completions);
            assertFalse("A duplicate end replayed the old reports", fixture.server.requestOrderedRoundEnd());
            // The Activity has not yet left RUNNING while it saves statistics.
            // A trailing legacy combat frame must not mutate the frozen score.
            globals.mGameMode = Globals.GAME_MODE_FFA;
            Phone victim = fixture.phones.get(1);
            victim.sendControl(NetMsg.NETMSG_ELIMINATED + "1");
            victim.sendControl(NetMsg.NETMSG_PLAYERDATAREQUEST);
            JSONObject finalScores;
            do { finalScores = victim.read(); } while (!finalScores.has(TcpServer.JSON_PLAYERDATA));
            JSONArray rows = finalScores.getJSONArray(TcpServer.JSON_PLAYERDATA);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (row.getInt(TcpServer.JSON_PLAYERID) == 1)
                    assertEquals("Post-round combat changed the frozen score", 3,
                            row.getInt(TcpServer.JSON_PLAYERPOINTS));
            }
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            fixture.server.sendAllGameInfo(TcpServer.SEND_ALL);
            driveUntil(() -> settled(fixture.server), clock, 2_000, "Post-round lobby did not publish");
            for (Phone phone : fixture.phones) {
                JSONObject lobby;
                do { lobby = phone.read(); } while (!lobby.has(TcpServer.JSON_PLAYERS));
                assertEquals("Queued roster restarted a round before its UI finished saving",
                        Globals.GAME_STATE_NONE, lobby.getInt(TcpServer.JSON_GAMESTATE));
                assertFalse(lobby.getBoolean(TcpServer.JSON_LOBBY_PLAYING));
                assertFalse(lobby.has(TcpServer.JSON_GAMESTART));
            }
            // The activity finishes saving statistics before returning the host
            // to its existing lobby. No registration or clock probe is sent.
            globals.mGameState = Globals.GAME_STATE_NONE;
            fixture.server.clearScheduledStart();
            globals.mInfectionMode = true;
            globals.setPlayerInfected((byte) 2, true);
            assertTrue(fixture.server.startGame());
            driveUntil(() -> fixture.server.getScheduledGameStart() != null && settled(fixture.server),
                    clock, 2_000, "Second round required clocks to synchronize again");
            String nextToken = fixture.server.getScheduledGameStart()
                    .getStringExtra(NetMsg.INTENT_ROUND_TOKEN);
            assertNotEquals(firstToken, nextToken);
            for (Phone phone : fixture.phones)
                assertEquals(nextToken, phone.readStart().getString(TcpServer.JSON_ROUND_TOKEN));
            assertSame(original, get(shooter, "clientSocket"));
            assertEquals(lastProbe, get(shooter, "lastClockRequestAt"));
            assertEquals(12, get(shooter, "clockSamples"));
            assertEquals(0, fixture.server.getScore((byte) 1).points);
            assertEquals(0, fixture.server.getScore((byte) 1).shots);
            assertEquals(false, get(shooter, "awaitingRespawn"));
            assertEquals("A previously infected survivor kept the old role", 2,
                    globals.mRoundRoster.seat(2).team);
            assertFalse(globals.mInfectedPlayers.contains((byte) 2));
            List<Future<RoundReport>> cancelledCountdown = new ArrayList<>();
            for (Phone phone : fixture.phones)
                cancelledCountdown.add(workers.submit(() -> phone.readRoundEnd(false)));
            assertTrue(fixture.server.requestOrderedRoundEnd());
            driveUntil(() -> allDone(cancelledCountdown) && settled(fixture.server), clock, 2_000,
                    "Next countdown cancellation did not return to the same lobby");
            for (Future<RoundReport> cancelled : cancelledCountdown) assertNull(cancelled.get());
            assertTrue(fixture.server.arePlayerClocksSynchronized());
            fixture.server.cancelServer();
            driveUntil(() -> original.isClosed() && settled(fixture.server), clock, 2_000,
                    "Explicit cancellation did not close the lobby socket");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static boolean settled(TcpServer server) throws Exception {
        synchronized (get(server, "mServerStateLock")) {
            return ((Set<?>) get(server, "mClientTasks")).isEmpty();
        }
    }

    private void verifyLobby(int count, int gpsMode) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count + 1);
        WallClock clock = new WallClock();
        try (Fixture fixture = new Fixture(count, gpsMode)) {
            CountDownLatch initialized = new CountDownLatch(count), start = new CountDownLatch(1);
            List<Future<Result>> results = new ArrayList<>();
            for (Phone phone : fixture.phones)
                results.add(workers.submit(() -> phone.calibrate(count, initialized, start)));
            fixture.server.sendAllGameInfo(TcpServer.SEND_ALL);
            driveUntil(() -> initialized.getCount() == 0, clock, 3_000,
                    "The first game settings did not reach every phone");
            fixture.startPolling();
            Future<?> churn = workers.submit(() -> {
                start.await();
                // Gun reconnects are real incoming frames. Their roster sends
                // compete with clock replies for the host's client lock.
                for (int burst = 0; burst < 16; burst++) {
                    fixture.server.sendAllGameInfo(TcpServer.SEND_ALL);
                    fixture.server.sendPlayerSettings(TcpServer.SEND_ALL, false, false);
                    fixture.server.requestGPSRefresh();
                    if (burst >= 3) continue;
                    for (Phone phone : fixture.phones) {
                        phone.send(new JSONObject().put(TcpServer.JSON_LOBBY_READY, false));
                        phone.send(new JSONObject().put(TcpServer.JSON_LOBBY_READY, true));
                    }
                }
                return null;
            });
            long began = millis();
            start.countDown();
            driveUntil(() -> allDone(results), clock, 5_000,
                    count + " phones exceeded the five-second synchronization barrier");
            long elapsed = millis() - began;
            int rejected = 0;
            for (Future<Result> future : results) {
                Result result = future.get();
                assertEquals(GameClock.SAMPLES_PER_SYNC, result.accepted);
                assertTrue("The slow sample did not exercise the real RTT rejection", result.rejected > 0);
                assertEquals("Repeated game settings queued ahead of clock replies", 1,
                        result.rostersBeforeReady);
                assertEquals("Bulk GPS queued ahead of clock replies", 0, result.gpsBeforeReady);
                assertEquals("Standalone settings queued ahead of clock replies", 0,
                        result.settingsBeforeReady);
                rejected += result.rejected;
            }
            churn.get(1, TimeUnit.SECONDS);
            assertTrue("The host did not accept every real clock-ready ACK",
                    fixture.server.arePlayerClocksSynchronized());
            assertEquals(count, Globals.getInstance().mGPSData.size());
            for (Object client : fixture.clients.values())
                assertEquals("The latest gun-ready update was lost", true, get(client, "lobbyReady"));
            System.out.println("Android phone host GPS " + gpsMode + ": " + count
                    + " phones synchronized with latest roster/GPS in "
                    + elapsed + " ms; accepted=" + (count * GameClock.SAMPLES_PER_SYNC)
                    + ", rejected=" + rejected);
        } finally {
            workers.shutdownNow();
            assertTrue("Phone workers survived cleanup", workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static boolean allDone(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) if (!future.isDone()) return false;
        return true;
    }

    private interface Condition { boolean isTrue() throws Exception; }

    private static void driveUntil(Condition condition, WallClock clock, long timeout,
                                   String failure) throws Exception {
        long deadline = millis() + timeout;
        while (!condition.isTrue() && millis() < deadline) {
            clock.pump();
            Thread.sleep(2);
        }
        clock.pump();
        assertTrue(failure, condition.isTrue());
    }

    /** Keep Robolectric's host clock/handlers tied to real elapsed socket time. */
    private static final class WallClock {
        long lastNanos = System.nanoTime();
        void pump() {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastNanos);
            if (elapsed > 0) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(elapsed, TimeUnit.MILLISECONDS);
                lastNanos += TimeUnit.MILLISECONDS.toNanos(elapsed);
            } else Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static final class Result {
        int accepted, rejected, rostersBeforeReady = 1, gpsBeforeReady, settingsBeforeReady;
    }

    private static final class Phone implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final int id;
        Phone(Socket socket, int id) throws Exception {
            this.socket = socket;
            this.id = id;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
        synchronized void send(JSONObject value) throws Exception {
            output.writeUTF(JSON + value);output.flush();
        }
        synchronized void sendControl(String message) throws Exception {
            output.writeUTF(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + message);
            output.flush();
        }
        JSONObject read() throws Exception {
            while (true) {
                String frame = input.readUTF();
                if (frame.startsWith(JSON)) return new JSONObject(frame.substring(JSON.length()));
                if (TcpServer.TCP_SERVER_PING.equals(frame)) {
                    synchronized (this) { output.writeUTF(TcpClient.TCP_CLIENT_PONG);output.flush(); }
                }
            }
        }
        JSONObject readStart() throws Exception {
            while (true) {
                JSONObject frame = read();
                if (frame.has(TcpServer.JSON_GAMESTART)) return frame;
            }
        }
        RoundReport readRoundEnd() throws Exception {
            return readRoundEnd(true);
        }
        RoundReport readRoundEnd(boolean expectReport) throws Exception {
            boolean scores = false;
            RoundReport report = null;
            while (true) {
                String frame = input.readUTF();
                if ((TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG
                        + NetMsg.NETMSG_ENDGAME).equals(frame)) {
                    assertTrue("ENDGAME overtook final scores", scores);
                    if (expectReport) assertTrue("ENDGAME overtook the personal report", report != null);
                    else assertNull("A cancelled countdown replayed the previous round's report", report);
                    return report;
                }
                if (!frame.startsWith(JSON)) continue;
                JSONObject json = new JSONObject(frame.substring(JSON.length()));
                if (json.has(TcpServer.JSON_PLAYERDATA)) scores = true;
                if (json.has(RoundReport.KEY)) {
                    assertTrue("Personal report overtook final scores", scores);
                    report = RoundReport.fromJson(json.getJSONObject(RoundReport.KEY));
                }
            }
        }
        Result calibrate(int count, CountDownLatch initialized, CountDownLatch start) throws Exception {
            JSONObject first = read();
            assertTrue("Initial settings were deferred", first.has(TcpServer.JSON_PLAYERS)
                    && first.has(TcpServer.JSON_PLAYERSETTINGS));
            initialized.countDown();start.await();
            GameClock estimate = new GameClock();
            Result result = new Result();
            boolean firstProbe = true;
            while (estimate.samples() < GameClock.SAMPLES_PER_SYNC) {
                long sent = millis();
                JSONObject request = new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, sent);
                if (firstProbe) {
                    // Match TcpClient's once-per-connection GPS bootstrap.
                    request.put(TcpServer.JSON_GPSLATITUDE, 47.62 + id * 0.00001)
                            .put(TcpServer.JSON_GPSLONGITUDE, -122.10)
                            .put(TcpServer.JSON_GPS_FIX_AGE, 0);
                }
                send(request);
                while (true) {
                    JSONObject reply = read();
                    if (reply.has(TcpServer.JSON_PLAYERS)) {
                        result.rostersBeforeReady++;Thread.sleep(METADATA_PROCESSING_MS);
                    }
                    if (reply.has(TcpServer.JSON_GPSUPDATE)) {
                        result.gpsBeforeReady++;Thread.sleep(METADATA_PROCESSING_MS);
                    }
                    if (reply.has(TcpServer.JSON_PLAYERSETTINGS) && !reply.has(TcpServer.JSON_PLAYERS)) {
                        result.settingsBeforeReady++;Thread.sleep(METADATA_PROCESSING_MS);
                    }
                    if (!reply.has(TcpServer.JSON_CLOCK_REQUEST)
                            || reply.getLong(TcpServer.JSON_CLOCK_REQUEST) != sent) continue;
                    // Exercise rejection without relaxing GameClock's real
                    // 80 ms Wi-Fi limit or counting replies as accepted samples.
                    if (firstProbe) Thread.sleep(100);
                    firstProbe = false;
                    if (estimate.record(sent, reply.getLong(TcpServer.JSON_CLOCK_RECEIVE),
                            reply.getLong(TcpServer.JSON_CLOCK_SEND), millis())) result.accepted++;
                    else result.rejected++;
                    break;
                }
            }
            send(new JSONObject().put(TcpServer.JSON_CLOCK_READY, true));
            boolean currentRoster = false, currentGps = false;
            while (!currentRoster || !currentGps) {
                JSONObject update = read();
                if (update.has(TcpServer.JSON_GPSUPDATE)) {
                    currentGps = update.optBoolean(TcpServer.JSON_GPSFULLUPDATE)
                            && update.getJSONArray(TcpServer.JSON_GPSUPDATE).length() > 0;
                }
                if (!update.has(TcpServer.JSON_PLAYERS)) continue;
                JSONArray roster = update.getJSONArray(TcpServer.JSON_PLAYERS);
                currentRoster = roster.length() == count
                        && !update.getBoolean(TcpServer.JSON_ALLOWPLAYERSETTINGS);
                for (int index = 0; index < roster.length(); index++) {
                    JSONObject player = roster.getJSONObject(index);
                    currentRoster &= player.getBoolean(TcpServer.JSON_CLOCK_READY)
                            && player.getBoolean(TcpServer.JSON_LOBBY_READY);
                }
            }
            return result;
        }
        @Override public void close() throws Exception { socket.close(); }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingServer server = new RecordingServer();
        final Map<Integer, Object> clients = new ConcurrentHashMap<>();
        final List<Phone> phones = new ArrayList<>();
        final List<Thread> tasks = new ArrayList<>();
        final Object previousGlobals;
        Thread polling;
        Fixture(int count, int gpsMode) throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            previousGlobals = singleton.get(null);singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 0;
            globals.mLocalLobbyReady = false;
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mUseGPS = true;
            globals.mGPSMode = gpsMode;
            server.setDedicated(true);
            set(server, "mClientData", clients);set(server, "keepListening", true);
            Class<?> type = Class.forName(TcpServer.class.getName() + "$ClientData");
            Constructor<?> constructor = type.getDeclaredConstructor(TcpServer.class);
            constructor.setAccessible(true);
            Method initialize = type.getDeclaredMethod("initialize", Socket.class, int.class);
            initialize.setAccessible(true);
            try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                for (int id = 1; id <= count; id++) {
                    Socket peer = new Socket();
                    peer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0." + (id + 1)), 0));
                    peer.connect(listener.getLocalSocketAddress());peer.setTcpNoDelay(true);
                    peer.setSoTimeout(6_000);
                    Socket accepted = listener.accept();
                    Object client = constructor.newInstance(server);
                    assertTrue((boolean) initialize.invoke(client, accepted, id));
                    set(client, "mPlayerID", (byte) id);
                    clients.put(id, client);phones.add(new Phone(peer, id));
                    globals.mTeamIPMap.put((byte) id, accepted.getInetAddress());
                    globals.mIPTeamMap.put(accepted.getInetAddress(), (byte) id);
                    globals.mTeamPlayerNameMap.put((byte) id, "Player " + id);
                }
            }
        }
        void startPolling() throws Exception {
            Constructor<?> constructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                    .getDeclaredConstructor(TcpServer.class);
            constructor.setAccessible(true);
            polling = new Thread((Runnable) constructor.newInstance(server), "Phone clock load polling");
            set(server, "mClientThread", polling);polling.start();
        }
        void parse(int id, JSONObject value) throws Exception {
            Class<?> threadType = Class.forName(TcpServer.class.getName() + "$ClientThread");
            Constructor<?> constructor = threadType.getDeclaredConstructor(TcpServer.class);
            constructor.setAccessible(true);
            Method parse = threadType.getDeclaredMethod("parsePlayerInfo", String.class,
                    clients.get(id).getClass());
            parse.setAccessible(true);
            parse.invoke(constructor.newInstance(server), value.toString(), clients.get(id));
        }
        JSONObject clockReply(int id, long sent) throws Exception {
            parse(id, new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, sent));
            JSONObject frame;
            do { frame = phones.get(id - 1).read(); }
            while (!frame.has(TcpServer.JSON_CLOCK_REQUEST));
            assertEquals(sent, frame.getLong(TcpServer.JSON_CLOCK_REQUEST));
            return frame;
        }
        void reconnect(int id) throws Exception {
            Object client = clients.get(id);
            Method close = client.getClass().getDeclaredMethod("close");close.setAccessible(true);
            close.invoke(client);
            phones.get(id - 1).close();
            try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                Socket peer = new Socket(listener.getInetAddress(), listener.getLocalPort());
                peer.setTcpNoDelay(true);peer.setSoTimeout(6_000);
                Method initialize = client.getClass().getDeclaredMethod("initialize", Socket.class, int.class);
                initialize.setAccessible(true);
                assertTrue((boolean) initialize.invoke(client, listener.accept(), id));
                phones.set(id - 1, new Phone(peer, id));
            }
        }
        @Override public void close() throws Exception {
            try {
                Object lock = get(server, "mServerStateLock");
                Thread gps = (Thread) get(server, "mGPSWorkerThread");
                synchronized (lock) {
                    for (Object task : (Set<?>) get(server, "mClientTasks")) tasks.add((Thread) task);
                }
                server.stopTcpServer();
                for (Phone phone : phones) phone.close();
                if (polling != null) {
                    polling.join(2_000);assertFalse("Host polling survived cleanup", polling.isAlive());
                }
                for (Thread task : tasks) {
                    task.join(2_000);assertFalse("Host dispatch survived cleanup", task.isAlive());
                }
                if (gps != null) {
                    gps.join(2_000);assertFalse("Host GPS worker survived cleanup", gps.isAlive());
                }
            } finally {
                Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
                singleton.set(null, previousGlobals);
            }
        }
    }

    private static final class RecordingServer extends TcpServer {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
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
