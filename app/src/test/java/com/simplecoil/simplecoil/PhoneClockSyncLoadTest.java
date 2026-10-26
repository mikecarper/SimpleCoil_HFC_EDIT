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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        JSONObject read() throws Exception {
            while (true) {
                String frame = input.readUTF();
                if (frame.startsWith(JSON)) return new JSONObject(frame.substring(JSON.length()));
                if (TcpServer.TCP_SERVER_PING.equals(frame)) {
                    synchronized (this) { output.writeUTF(TcpClient.TCP_CLIENT_PONG);output.flush(); }
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
        @Override public void sendBroadcast(Intent intent) { }
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
