package com.simplecoil.laptophost;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A blocked radio writer must not stall other phones or laptop controls. No network is used. */
public final class SlowClientIsolationTest {
    private static final String JSON = "SimpleCoil:31JSON";
    private static final Class<?> CLIENT;
    static {
        try { CLIENT = Class.forName("com.simplecoil.laptophost.LaptopHost$ClientConnection"); }
        catch (ClassNotFoundException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("simplecoil.phoneupdates.enabled", "false");
        System.setProperty("simplecoil.adb.updates.enabled", "false");
        if (args.length == 1 && "--clock-epoch-only".equals(args[0])) {
            verifyClockEpochResume();
            System.out.println("Clock epoch resume tests passed.");
            return;
        }
        verifyHeartbeatAndStateIsolation();
        verifyQueueBound("x", 128);
        verifyQueueBound("x".repeat(60_000), 4);
        verifyFinalFrameOrder();
        verifyRoundFinalBatch();
        verifyRetainedLobbyAssignments();
        verifyFinalFrameDeadline();
        verifyClockSendTimestamp();
        verifyClockEpochResume();
        verifyShutdownClosesUnregisteredWriter();
        System.out.println("Slow-client isolation tests passed.");
    }

    private static void verifyHeartbeatAndStateIsolation() throws Exception {
        ExecutorService calls = Executors.newCachedThreadPool();
        try (Fixture fixture = new Fixture()) {
            Phone slow = fixture.phone(1, true), healthy = fixture.phone(2, true);
            Gate blocked = slow.socket.output.blockNextWrite();
            check(slow.send("blocked"), "Initial slow frame was rejected");
            blocked.awaitEntered();
            long started = System.nanoTime();
            Future<?> heartbeat = calls.submit(() -> uncheckedCall(fixture.host, "sendHeartbeats"));
            heartbeat.get(750, TimeUnit.MILLISECONDS);
            await(() -> healthy.socket.output.frames().contains("ping"), 750,
                    "A blocked phone delayed the healthy phone's heartbeat");
            check(System.nanoTime() - started < 1_000_000_000L, "Healthy heartbeat exceeded one second");

            Phone rejected = fixture.phone(3, false);
            Gate rejectedWrite = rejected.socket.output.blockNextWrite();
            Map<String, Object> join = fixture.registration(3);
            join.put("jointeam", 1); // Rejected in the lobby while holding stateLock.
            calls.submit(() -> uncheckedCall(fixture.host, "registerPlayer", CLIENT, rejected.client,
                    Map.class, join)).get(750, TimeUnit.MILLISECONDS);
            rejectedWrite.awaitEntered();
            calls.submit(() -> uncheckedCall(fixture.host, "dashboardState")).get(750, TimeUnit.MILLISECONDS);
            check(!healthy.socket.closed.get(), "A slow join rejection disconnected another phone");
        } finally { calls.shutdownNow(); }
    }

    private static void verifyQueueBound(String payload, int accepted) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone slow = fixture.phone(1, false), healthy = fixture.phone(2, false);
            Gate blocked = slow.socket.output.blockNextWrite();
            check(slow.send(payload), "Initial frame was rejected");
            blocked.awaitEntered();
            for (int index = 1; index < accepted; index++)
                check(slow.send(payload), "Queue rejected a frame before its configured bound");
            check(!slow.send(payload), "An unresponsive phone exceeded its bounded queue");
            check(slow.socket.closed.get(), "Queue overflow did not close the slow phone");
            check(!healthy.socket.closed.get() && healthy.send("healthy"), "Overflow affected another phone");
            await(() -> healthy.socket.output.frames().contains("healthy"), 750,
                    "Healthy writer stopped after another phone overflowed");
            check(!slow.send("after close"), "Closed client accepted another frame");
        }
    }

    private static void verifyFinalFrameOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone phone = fixture.phone(1, false);
            Gate blocked = phone.socket.output.blockNextWrite();
            check(phone.send("earlier"), "Could not queue earlier frame");
            blocked.awaitEntered();
            check((Boolean) call(phone.client, "sendAndClose", String.class, "SimpleCoil:31MESGENDGAME"),
                    "Could not queue final frame");
            check(!phone.socket.closed.get(), "Socket closed before its final frame could flush");
            check(!phone.send("too late"), "Terminal queue accepted a later frame");
            blocked.release.countDown();
            await(phone.socket.closed::get, 1_000, "Socket stayed open after the final frame flushed");
            check(phone.socket.output.frames().equals(List.of("earlier", "SimpleCoil:31MESGENDGAME")),
                    "Final frame was lost or reordered before close");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verifyRoundFinalBatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone slow = fixture.phone(1, true), healthy = fixture.phone(17, true);
            String token = UUID.randomUUID().toString();
            Class<?> reviewClass = Class.forName("com.simplecoil.laptophost.LaptopHost$RoundReview");
            Constructor<?> constructor = reviewClass.getDeclaredConstructor(String.class, long.class,
                    long.class, String.class);
            constructor.setAccessible(true);
            Object review = constructor.newInstance(token, 1L, 0L, "teams");
            Map<Integer, Object> players = (Map<Integer, Object>) field(fixture.host, "players");
            for (Object player : players.values())
                call(review, "enroll", player.getClass(), player, int.class,
                        (Integer) field(player, "id") == 1 ? 1 : 2);
            call(review, "started");
            setField(fixture.host, "roundReview", review);
            setField(fixture.host, "roundToken", token);
            Class<? extends Enum> stateClass = (Class<? extends Enum>) field(fixture.host, "roundState").getClass();
            setField(fixture.host, "roundState", Enum.valueOf(stateClass, "RUNNING"));

            await(() -> outboundEmpty(slow.client) && outboundEmpty(healthy.client), 1_000,
                    "Registration frames did not flush before the round ended");
            slow.socket.output.clear();healthy.socket.output.clear();
            Gate blocked = slow.socket.output.blockNextWrite();
            check(slow.send("earlier"), "Could not block the slow phone before round end");
            blocked.awaitEntered();
            call(fixture.host, "endRound", String.class, "final-batch regression");
            check(!slow.socket.closed.get(), "Round end closed a phone before its report flushed");
            check(slow.send("post-round heartbeat"), "Round end closed the retained control connection");
            await(() -> outboundEmpty(healthy.client), 1_000, "Slow phone blocked another phone's final report");
            check(!healthy.socket.closed.get(), "Round completion disconnected a healthy phone");
            checkFinalRoundFrames(healthy.socket.output.frames(), 0);
            blocked.release.countDown();
            await(() -> outboundEmpty(slow.client), 1_000, "Final report never flushed to the recovered phone");
            check(!slow.socket.closed.get(), "Round completion disconnected the recovered phone");
            checkFinalRoundFrames(slow.socket.output.frames(), 1);
            check(slow.socket.output.frames().get(4).equals("post-round heartbeat"),
                    "A retained connection interleaved traffic into the final batch");
        }
    }

    private static void checkFinalRoundFrames(List<String> frames, int prefix) {
        check(frames.size() >= prefix + 3 && frames.get(prefix).startsWith(JSON)
                        && frames.get(prefix).contains("\"playerdata\"")
                        && frames.get(prefix + 1).contains("\"roundreport\"")
                        && frames.get(prefix + 2).equals("SimpleCoil:31MESGENDGAME"),
                "Final scoreboard, personal report and ENDGAME were lost or reordered: " + frames);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void verifyRetainedLobbyAssignments() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone first = fixture.phone(1, true), second = fixture.phone(2, true);
            Phone benched = fixture.phone(17, true);
            setField(first.client, "clockReady", true);setField(second.client, "clockReady", true);
            setField(benched.client, "lobbyBenched", true);
            setField(field(fixture.host, "config"), "balanced", true);
            Map<Integer, Object> players = (Map<Integer, Object>) field(fixture.host, "players");
            Map<String, Integer> retained = (Map<String, Integer>) field(fixture.host, "retainedBalancedTeams");
            retained.put((String) field(players.get(1), "identity"), 2);
            retained.put((String) field(players.get(2), "identity"), 1);
            setField(fixture.host, "balancedTeams", Map.of(1, 2, 2, 1));
            Object origin = com.simplecoil.protocol.GpsOffset.create(47.6, -122.1);
            setField(fixture.host, "gpsOffset", origin);
            Class<? extends Enum> state = (Class<? extends Enum>) field(fixture.host, "roundState").getClass();
            setField(fixture.host, "roundState", Enum.valueOf(state, "FINISHED"));
            synchronized (field(fixture.host, "stateLock")) { call(fixture.host, "resetForNewLobbyLocked"); }
            check(field(fixture.host, "balancedTeams").equals(Map.of(1, 2, 2, 1)),
                    "A retained lobby rebalanced identities without a new join or operator request");
            check(((Map<?, ?>) field(fixture.host, "activeClients")).size() == 3
                            && Boolean.TRUE.equals(field(first.client, "clockReady"))
                            && Boolean.TRUE.equals(field(second.client, "clockReady"))
                            && Boolean.TRUE.equals(field(benched.client, "lobbyBenched")),
                    "Next-lobby preparation discarded clock readiness or participation");
            check(field(fixture.host, "gpsOffset") == origin, "Next-lobby preparation replaced the GPS origin");
        }
    }

    private static void verifyFinalFrameDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone phone = fixture.phone(1, false);
            Gate blocked = phone.socket.output.blockNextWrite();
            check(phone.send("stuck"), "Could not start blocked writer");
            blocked.awaitEntered();
            call(phone.client, "sendAndClose", String.class, JSON + "{\"transferredout\":true}");
            await(phone.socket.closed::get, 6_500, "A stuck terminal write had no finite close deadline");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyClockSendTimestamp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone phone = fixture.phone(1, true);
            Gate blocked = phone.socket.output.blockNextWrite();
            check(phone.send("earlier"), "Could not block the clock reply behind an earlier frame");
            blocked.awaitEntered();
            call(fixture.host, "handleClockSync", CLIENT, phone.client, Map.class, Map.of("clocksync", 99));
            long queuedAt = (Long) call(fixture.host, "elapsedMillis");
            await(() -> (Long) uncheckedCall(fixture.host, "elapsedMillis") > queuedAt + 25, 750,
                    "Host clock did not advance");
            long releasedAt = (Long) call(fixture.host, "elapsedMillis");
            blocked.release.countDown();
            await(() -> phone.socket.output.frames().stream().anyMatch(frame -> frame.startsWith(JSON)
                    && frame.contains("\"clocksync\":99")), 750, "Queued clock reply was not written");
            String reply = phone.socket.output.frames().stream().filter(frame -> frame.startsWith(JSON)
                    && frame.contains("\"clocksync\":99")).findFirst().orElseThrow();
            Map<String, Object> parsed = (Map<String, Object>) LaptopHost.Json.parse(reply.substring(JSON.length()));
            check(((Number) parsed.get("clocksend")).longValue() >= releasedAt,
                    "Clock send timestamp included time spent waiting in the output queue");
            check(((Number) parsed.get("clockreceive")).longValue() <= queuedAt,
                    "Clock receive timestamp was changed by the output queue");
        }
    }

    private static void verifyShutdownClosesUnregisteredWriter() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Phone phone = fixture.phone(1, false);
            Gate blocked = phone.socket.output.blockNextWrite();
            phone.send("unregistered blocked writer");blocked.awaitEntered();
            call(fixture.host, "stop");
            check(phone.socket.closed.get(), "Shutdown left an unregistered writer open");
            ExecutorService workers = (ExecutorService) field(fixture.host, "clientExecutor");
            check(workers.awaitTermination(1, TimeUnit.SECONDS), "Shutdown left a blocked output worker running");
        }
    }

    private static void verifyClockEpochResume() throws Exception {
        String oldEpoch;
        try (Fixture fixture = new Fixture()) {
            Phone cached = fixture.phone(1, true);
            String epoch = (String) field(fixture.host, "clockEpoch");
            check(UUID.fromString(epoch).toString().equals(epoch), "Host clock epoch is not a UUID");
            acknowledgeClock(fixture, cached, Map.of("clockready", true, "clockepoch", epoch));
            check(!clockReady(cached), "A cached epoch acknowledged an unprobed connection");
            call(fixture.host, "handleClockSync", CLIENT, cached.client, Map.class, Map.of("clocksync", -1));
            acknowledgeClock(fixture, cached, Map.of("clockready", true, "clockepoch", epoch));
            check(!clockReady(cached), "An invalid clock probe satisfied the resume barrier");
            check(epoch.equals(clockProbe(fixture, cached, 101).get("clockepoch")),
                    "Clock reply omitted the stable host epoch");
            acknowledgeClock(fixture, cached, Map.of("clockready", true));
            check(!clockReady(cached), "A legacy ACK skipped the twelve-probe barrier");
            acknowledgeClock(fixture, cached, Map.of("clockready", true, "clockepoch", UUID.randomUUID().toString()));
            check(!clockReady(cached), "A stale epoch resumed the cached clock");
            acknowledgeClock(fixture, cached, Map.of("clockready", true, "clockepoch", 101));
            check(!clockReady(cached), "A non-string epoch resumed the cached clock");
            acknowledgeClock(fixture, cached, Map.of("clockready", "true", "clockepoch", epoch));
            check(!clockReady(cached), "String true acknowledged a cached clock");
            acknowledgeClock(fixture, cached, Map.of("clockready", true, "clockepoch", epoch));
            check(clockReady(cached) && (Integer) field(cached.client, "clockSamples") == 1,
                    "A matching epoch did not resume after exactly one fresh clock probe");
            synchronized (field(fixture.host, "stateLock")) { call(fixture.host, "resetForNewLobbyLocked"); }
            check(epoch.equals(clockProbe(fixture, cached, 102).get("clockepoch")),
                    "Next-lobby preparation changed the host clock epoch");

            Phone legacy = fixture.phone(2, true), stale = fixture.phone(17, true);
            String wrongEpoch = UUID.randomUUID().toString();
            for (int sample = 1; sample <= 12; sample++) {
                check(epoch.equals(clockProbe(fixture, legacy, 200 + sample).get("clockepoch"))
                                && epoch.equals(clockProbe(fixture, stale, 300 + sample).get("clockepoch")),
                        "Connections on the same host received different clock epochs");
                acknowledgeClock(fixture, legacy, Map.of("clockready", true));
                acknowledgeClock(fixture, stale, Map.of("clockready", true, "clockepoch", wrongEpoch));
                check(clockReady(legacy) == (sample == 12) && clockReady(stale) == (sample == 12),
                        "Missing/stale epochs did not preserve the full legacy sampling barrier");
            }
            oldEpoch = epoch;
        }
        try (Fixture restarted = new Fixture()) {
            Phone phone = restarted.phone(1, true);
            String epoch = (String) clockProbe(restarted, phone, 401).get("clockepoch");
            check(!oldEpoch.equals(epoch), "A new host instance reused its predecessor's clock epoch");
            acknowledgeClock(restarted, phone, Map.of("clockready", true, "clockepoch", oldEpoch));
            check(!clockReady(phone), "A previous host lifetime resumed against the restarted host");
            acknowledgeClock(restarted, phone, Map.of("clockready", true, "clockepoch", epoch));
            check(clockReady(phone), "The restarted host did not accept its own epoch after a probe");
        }
    }

    private static void acknowledgeClock(Fixture fixture, Phone phone, Map<String, Object> value) throws Exception {
        call(fixture.host, "handleClockSync", CLIENT, phone.client, Map.class, value);
    }

    private static boolean clockReady(Phone phone) throws Exception {
        return Boolean.TRUE.equals(field(phone.client, "clockReady"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> clockProbe(Fixture fixture, Phone phone, long sentAt) throws Exception {
        call(fixture.host, "handleClockSync", CLIENT, phone.client, Map.class, Map.of("clocksync", sentAt));
        await(() -> outboundEmpty(phone.client), 1_000, "Clock epoch probe did not flush");
        for (String frame : phone.socket.output.frames()) {
            if (!frame.startsWith(JSON)) continue;
            Map<String, Object> reply = (Map<String, Object>) LaptopHost.Json.parse(frame.substring(JSON.length()));
            if (reply.get("clocksync") instanceof Number
                    && ((Number) reply.get("clocksync")).longValue() == sentAt) return reply;
        }
        throw new AssertionError("Clock probe did not produce a reply");
    }

    private static final class Fixture implements AutoCloseable {
        final LaptopHost host;
        final String previousPlayers = System.getProperty("simplecoil.players.file");
        final String previousEvent = System.getProperty("simplecoil.event.file");
        final String previousReports = System.getProperty("simplecoil.reports");
        Fixture() throws Exception {
            Path directory = Files.createTempDirectory("simplecoil-slow-client");
            System.setProperty("simplecoil.players.file", directory.resolve("players.json").toString());
            System.setProperty("simplecoil.event.file", directory.resolve("event.properties").toString());
            System.setProperty("simplecoil.reports", directory.resolve("reports").toString());
            Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
            Method parse = configClass.getDeclaredMethod("parse", String[].class);parse.setAccessible(true);
            Object config = parse.invoke(null, (Object) new String[]{"--no-browser"});
            Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
            constructor.setAccessible(true);host = constructor.newInstance(config);
        }
        Phone phone(int id, boolean registered) throws Exception {
            FakeSocket socket = new FakeSocket(id);
            Constructor<?> constructor = CLIENT.getDeclaredConstructor(LaptopHost.class, Socket.class, int.class);
            constructor.setAccessible(true);
            Object client = constructor.newInstance(host, socket, id);
            Phone phone = new Phone(client, socket);
            if (registered) {
                call(host, "registerPlayer", CLIENT, client, Map.class, registration(id));
                await(() -> outboundEmpty(client), 1_000,
                        "Initial registration did not flush");
                socket.output.clear();
            }
            return phone;
        }
        Map<String, Object> registration(int id) {
            String identity = UUID.randomUUID().toString();
            return new LinkedHashMap<>(Map.of("playerID", id, "playername", "Player " + id,
                    "deviceidentity", identity, "playeridentity", identity));
        }
        @Override public void close() throws Exception {
            try {
                call(host, "stop");
                check(((ExecutorService) field(host, "clientExecutor")).awaitTermination(1, TimeUnit.SECONDS),
                        "A fixture left a client worker running");
            } finally {
                restore("simplecoil.players.file", previousPlayers);restore("simplecoil.event.file", previousEvent);
                restore("simplecoil.reports", previousReports);
            }
        }
    }

    private static final class Phone {
        final Object client;final FakeSocket socket;
        Phone(Object client, FakeSocket socket) { this.client = client;this.socket = socket; }
        boolean send(String frame) throws Exception { return (Boolean) call(client, "send", String.class, frame); }
    }

    private static final class FakeSocket extends Socket {
        final AtomicBoolean closed = new AtomicBoolean();
        final RecordingOutput output = new RecordingOutput(closed);
        final InetAddress address;
        FakeSocket(int id) throws Exception { address = InetAddress.getByName("127.0.0." + (id + 1)); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InetAddress getInetAddress() { return address; }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public void close() { closed.set(true);output.releaseBlockedWrite(); }
    }

    private static final class Gate {
        final AtomicBoolean claimed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void awaitEntered() throws Exception {
            check(entered.await(1, TimeUnit.SECONDS), "Fake socket writer never reached the blocked write");
        }
    }

    private static final class RecordingOutput extends OutputStream {
        final AtomicBoolean closed;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile Gate gate;
        RecordingOutput(AtomicBoolean closed) { this.closed = closed; }
        Gate blockNextWrite() { gate = new Gate();return gate; }
        void releaseBlockedWrite() { Gate current = gate;if (current != null) current.release.countDown(); }
        void clear() { synchronized (bytes) { bytes.reset(); } }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            Gate current = gate;
            if (current != null && current.claimed.compareAndSet(false, true)) {
                current.entered.countDown();
                try { current.release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException(interrupted); }
            }
            if (closed.get()) throw new IOException("Fake socket is closed");
            synchronized (bytes) { bytes.write(value, offset, length); }
        }
        List<String> frames() {
            byte[] snapshot;synchronized (bytes) { snapshot = bytes.toByteArray(); }
            List<String> frames = new ArrayList<>();
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot))) {
                while (input.available() > 0) frames.add(input.readUTF());
            } catch (IOException failure) { throw new AssertionError("Incomplete output frame", failure); }
            return frames;
        }
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        int count = arguments.length / 2;Class<?>[] types = new Class<?>[count];Object[] values = new Object[count];
        for (int index = 0; index < count; index++) {
            types[index] = (Class<?>) arguments[index * 2];values[index] = arguments[index * 2 + 1];
        }
        Method method = target.getClass().getDeclaredMethod(name, types);method.setAccessible(true);
        return method.invoke(target, values);
    }
    private static Object uncheckedCall(Object target, String name, Object... arguments) {
        try { return call(target, name, arguments); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
    private static boolean outboundEmpty(Object target) {
        try {
            synchronized (field(target, "outboundLock")) { return (Integer) field(target, "outboundFrames") == 0; }
        }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);else System.setProperty(key, previous);
    }
    private static void await(BooleanSupplier predicate, long milliseconds, String message) throws Exception {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(predicate.getAsBoolean(), message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
