package com.simplecoil.laptophost;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual ordered TCP exchanges, including slow-phone metadata processing. */
public final class LargeLobbyClockSyncTest {
    private static final String JSON = "SimpleCoil:31JSON";
    private static final int SAMPLES = 12;
    private static final long MAX_SAMPLE_DELAY_MS = 80;
    private static final long METADATA_PROCESSING_MS = 60;

    public static void main(String[] args) throws Exception {
        System.setProperty("simplecoil.phoneupdates.enabled", "false");
        for (int count : new int[] {16, 24, 32}) verifyLobby(count);
        System.out.println("Large-lobby clock synchronization tests passed.");
    }

    private static void verifyLobby(int count) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count + 2);
        try (Fixture fixture = new Fixture()) {
            CountDownLatch initial = new CountDownLatch(count), start = new CountDownLatch(1);
            List<Future<Result>> results = new ArrayList<>();
            long joining = millis();
            for (int id = 1; id <= count; id++) {
                Phone phone = fixture.phone(id, id == count);
                results.add(workers.submit(() -> phone.calibrate(count, initial, start)));
            }
            check(initial.await(Math.max(1, 5_000 - (millis() - joining)), TimeUnit.MILLISECONDS),
                    "Shared-lobby TCP registration and initial metadata exceeded five seconds");
            long joinedIn = millis() - joining;
            check(joinedIn < 5_000, "Shared-lobby TCP registration exceeded five seconds");
            // Saved/operator-owned full profiles are included in the deferred
            // snapshot. Client edits below cannot replace host-owned settings.
            synchronized (field(fixture.host, "stateLock")) {
                for (Object player : ((Map<?, ?>) field(fixture.host, "players")).values()) {
                    Object settings = field(player, "settings");
                    Field health = settings.getClass().getDeclaredField("health");health.setAccessible(true);
                    health.setInt(settings, 100 + ((Number) field(player, "id")).intValue());
                }
            }
            Gate slow = fixture.slow;
            slow.armed.set(true);
            Future<?> release = workers.submit(() -> {
                try {
                    check(slow.entered.await(2, TimeUnit.SECONDS), "Slow recipient never blocked its writer");
                    Thread.sleep(350);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally { slow.release.countDown(); }
            });
            Future<?> metadata = workers.submit(() -> {
                try {
                    start.await();
                    // Simultaneous gun reconnect/readiness changes used to send
                    // a full settings/roster snapshot for every event to every
                    // phone, ahead of the next NTP reply on the same stream.
                    for (int burst = 0; burst < 3; burst++)
                        for (Phone phone : fixture.phones) {
                            phone.send(Map.of("lobbyready", false));
                            phone.send(Map.of("lobbyready", true));
                            if (burst == 0) phone.sendSettings();
                        }
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            long began = millis();
            start.countDown();
            int rejected = 0, metadataFrames = 0;
            for (Future<Result> pending : results) {
                long remaining = 5_000 - (millis() - began);
                check(remaining > 0, "Clock barrier exceeded five seconds for " + count + " phones");
                Result result = pending.get(remaining, TimeUnit.MILLISECONDS);
                check(result.accepted == SAMPLES, "Clock readiness skipped real accepted samples");
                check(result.rejected >= 1, "Delayed samples did not exercise RTT rejection");
                check(result.metadataBeforeReady == 1,
                        "Redundant full metadata was sent during calibration: " + result.metadataBeforeReady);
                rejected += result.rejected;
                metadataFrames += result.metadataBeforeReady;
            }
            long elapsed = millis() - began;
            check(joinedIn + elapsed < 5_000, "Joining and clock calibration together exceeded five seconds");
            metadata.get(1, TimeUnit.SECONDS);release.get(1, TimeUnit.SECONDS);
            for (Object client : fixture.clients) {
                check(Boolean.TRUE.equals(field(client, "clockReady")), "Host did not accept a clock-ready ACK");
                check(Boolean.TRUE.equals(field(client, "lobbyReady")), "Final gun readiness was lost");
            }
            System.out.println(count + " phones: joined in " + joinedIn
                    + " ms; all clock ACKs and latest roster in " + elapsed
                    + " ms; accepted=" + (count * SAMPLES) + ", rejected=" + rejected
                    + ", full metadata before ACK=" + metadataFrames);
        } finally {
            workers.shutdownNow();
            check(workers.awaitTermination(2, TimeUnit.SECONDS), "Phone simulator workers did not stop");
        }
    }

    private static final class Result {
        int accepted, rejected, metadataBeforeReady = 1;
    }

    private static final class Phone implements AutoCloseable {
        final int id;
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        Phone(Socket socket, int id) throws IOException {
            this.socket = socket;this.id = id;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
        synchronized void send(Map<String, Object> value) throws IOException {
            output.writeUTF(JSON + LaptopHost.Json.stringify(value));output.flush();
        }
        void sendSettings() throws IOException {
            send(Map.ofEntries(Map.entry("playersettings", true), Map.entry("health", 100 + id),
                    Map.entry("reloadshots", 30), Map.entry("reloadtime", 1500),
                    Map.entry("reloadonempty", false), Map.entry("spawntime", 10),
                    Map.entry("damage", -1), Map.entry("shotmodesingle", true),
                    Map.entry("shotmodeburst3", true), Map.entry("shotmodeauto", true),
                    Map.entry("firingmode", 1)));
        }
        Map<String, Object> read() throws Exception {
            while (true) {
                String frame = input.readUTF();
                if (frame.startsWith(JSON)) return object(LaptopHost.Json.parse(frame.substring(JSON.length())));
                if ("ping".equals(frame)) {
                    synchronized (this) { output.writeUTF("pong");output.flush(); }
                }
            }
        }
        Result calibrate(int count, CountDownLatch initial, CountDownLatch start) throws Exception {
            Map<String, Object> first;
            do { first = read(); } while (!first.containsKey("players"));
            check(first.containsKey("playersettings") && first.containsKey("gpsoffset")
                    && Boolean.TRUE.equals(first.get("dedicatedserver")), "Initial game metadata was deferred");
            initial.countDown();start.await();
            Result result = new Result();
            boolean injectDelay = true;
            while (result.accepted < SAMPLES) {
                long sent = millis();
                if (injectDelay)
                    send(Map.of("clocksync", sent, "gpslatitude", 47.6 + id / 100_000.0,
                            "gpslongitude", -122.1, "gpsage", 0));
                else send(Map.of("clocksync", sent));
                while (true) {
                    Map<String, Object> reply = read();
                    check(!reply.containsKey("gpsupdate"), "Bulk GPS bypassed the calibration queue guard");
                    if (reply.containsKey("players") || reply.containsKey("playersettings")) {
                        result.metadataBeforeReady++;
                        Thread.sleep(METADATA_PROCESSING_MS);
                    }
                    if (!(reply.get("clocksync") instanceof Number)
                            || number(reply.get("clocksync")) != sent) continue;
                    // The first response models a busy client. Remaining probes
                    // include its real socket, writer and read scheduling delay.
                    Thread.sleep(injectDelay ? 100 : 10);
                    injectDelay = false;
                    long received = millis(), hostReceived = number(reply.get("clockreceive"));
                    long hostSent = number(reply.get("clocksend"));
                    long delay = (received - sent) - (hostSent - hostReceived);
                    check(hostSent >= hostReceived, "Clock reply moved backwards");
                    if (delay >= 0 && delay <= MAX_SAMPLE_DELAY_MS) result.accepted++;
                    else result.rejected++;
                    break;
                }
            }
            send(Map.of("clockready", true));
            // The latest complete roster must follow the ACK, including edits
            // suppressed during sampling and every other phone's final ACK.
            boolean rosterReady = false, gpsReady = false;
            while (!rosterReady || !gpsReady) {
                Map<String, Object> latest = read();
                if (latest.get("gpsupdate") instanceof List
                        && !((List<?>) latest.get("gpsupdate")).isEmpty()
                        // This phone remains blocked across a GPS publication,
                        // so it specifically needs a full recovery snapshot.
                        && (id != count || Boolean.TRUE.equals(latest.get("gpsfullupdate")))) gpsReady = true;
                if (!(latest.get("players") instanceof List)) continue;
                Thread.sleep(METADATA_PROCESSING_MS);
                List<?> players = (List<?>) latest.get("players");
                if (players.size() != count) continue;
                boolean ready = true;
                for (Object value : players) {
                    Map<String, Object> player = object(value);
                    ready &= Boolean.TRUE.equals(player.get("clockready"))
                            && Boolean.TRUE.equals(player.get("lobbyready"));
                }
                if (ready) {
                    for (Object value : (List<?>) latest.get("playersettings")) {
                        Map<String, Object> settings = object(value);
                        check(number(settings.get("health")) == 100 + number(settings.get("playerID")),
                                "A deferred player settings edit was lost");
                    }
                    rosterReady = true;
                }
            }
            return result;
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static final class Gate {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    }

    private static final class GatedSocket extends Socket {
        final Socket delegate;
        final Gate gate;
        GatedSocket(Socket delegate, Gate gate) { this.delegate = delegate;this.gate = gate; }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException {
            OutputStream target = delegate.getOutputStream();
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] value, int offset, int length) throws IOException {
                    if (gate.armed.compareAndSet(true, false)) {
                        gate.entered.countDown();
                        try { gate.release.await(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();throw new IOException(interrupted);
                        }
                    }
                    target.write(value, offset, length);
                }
                @Override public void flush() throws IOException { target.flush(); }
            };
        }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public synchronized void close() throws IOException {
            gate.release.countDown();delegate.close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final LaptopHost host;
        final List<Phone> phones = new ArrayList<>();
        final List<Object> clients = new ArrayList<>();
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final Gate slow = new Gate();
        final String previousPlayers = System.getProperty("simplecoil.players.file");
        final String previousEvent = System.getProperty("simplecoil.event.file");
        final String previousReports = System.getProperty("simplecoil.reports");
        Fixture() throws Exception {
            Path directory = Files.createTempDirectory("simplecoil-clock-load");
            System.setProperty("simplecoil.players.file", directory.resolve("players.json").toString());
            System.setProperty("simplecoil.event.file", directory.resolve("event.properties").toString());
            System.setProperty("simplecoil.reports", directory.resolve("reports").toString());
            Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
            Method parse = configClass.getDeclaredMethod("parse", String[].class);parse.setAccessible(true);
            Object config = parse.invoke(null, (Object) new String[] {"--no-browser", "--no-event-mode"});
            Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
            constructor.setAccessible(true);host = constructor.newInstance(config);
            Method publish = LaptopHost.class.getDeclaredMethod("publishGps");publish.setAccessible(true);
            ((ScheduledExecutorService) field(host, "scheduler")).scheduleAtFixedRate(() -> {
                try { publish.invoke(host); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }, 0, 250, TimeUnit.MILLISECONDS);
        }
        Phone phone(int id, boolean gated) throws Exception {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0." + (id + 1)), 0));
            socket.connect(listener.getLocalSocketAddress());socket.setTcpNoDelay(true);socket.setSoTimeout(6_000);
            Socket accepted = listener.accept();accepted.setTcpNoDelay(true);
            if (gated) accepted = new GatedSocket(accepted, slow);
            Class<?> type = Class.forName("com.simplecoil.laptophost.LaptopHost$ClientConnection");
            Constructor<?> constructor = type.getDeclaredConstructor(LaptopHost.class, Socket.class, int.class);
            constructor.setAccessible(true);
            Object client = constructor.newInstance(host, accepted, id);
            clients.add(client);
            ((ExecutorService) field(host, "clientExecutor")).execute((Runnable) client);
            Phone phone = new Phone(socket, id);phones.add(phone);
            String identity = UUID.randomUUID().toString();
            phone.send(Map.of("playerID", id, "playername", "Player " + id,
                    "deviceidentity", identity, "playeridentity", identity, "lobbyready", true));
            return phone;
        }
        @Override public void close() throws Exception {
            try {
                slow.release.countDown();
                for (Phone phone : phones) phone.close();
                listener.close();
                Method stop = LaptopHost.class.getDeclaredMethod("stop");stop.setAccessible(true);stop.invoke(host);
                check(((ExecutorService) field(host, "clientExecutor")).awaitTermination(2, TimeUnit.SECONDS),
                        "Host retained a socket worker after shutdown");
                check(((ExecutorService) field(host, "scheduler")).awaitTermination(2, TimeUnit.SECONDS),
                        "Host retained a deferred roster task after shutdown");
            } finally {
                restore("simplecoil.players.file", previousPlayers);restore("simplecoil.event.file", previousEvent);
                restore("simplecoil.reports", previousReports);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static long millis() { return System.nanoTime() / 1_000_000L; }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);else System.setProperty(key, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
