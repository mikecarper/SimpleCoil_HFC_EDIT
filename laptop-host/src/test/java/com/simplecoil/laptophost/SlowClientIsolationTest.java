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
    private static final String JSON = "SimpleCoil:30JSON";
    private static final Class<?> CLIENT;
    static {
        try { CLIENT = Class.forName("com.simplecoil.laptophost.LaptopHost$ClientConnection"); }
        catch (ClassNotFoundException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        verifyHeartbeatAndStateIsolation();
        verifyQueueBound("x", 128);
        verifyQueueBound("x".repeat(60_000), 4);
        verifyFinalFrameOrder();
        verifyFinalFrameDeadline();
        verifyClockSendTimestamp();
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
            check((Boolean) call(phone.client, "sendAndClose", String.class, "SimpleCoil:30MESGENDGAME"),
                    "Could not queue final frame");
            check(!phone.socket.closed.get(), "Socket closed before its final frame could flush");
            check(!phone.send("too late"), "Terminal queue accepted a later frame");
            blocked.release.countDown();
            await(phone.socket.closed::get, 1_000, "Socket stayed open after the final frame flushed");
            check(phone.socket.output.frames().equals(List.of("earlier", "SimpleCoil:30MESGENDGAME")),
                    "Final frame was lost or reordered before close");
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

    private static final class Fixture implements AutoCloseable {
        final LaptopHost host;
        final String previousPlayers = System.getProperty("simplecoil.players.file");
        final String previousEvent = System.getProperty("simplecoil.event.file");
        Fixture() throws Exception {
            Path directory = Files.createTempDirectory("simplecoil-slow-client");
            System.setProperty("simplecoil.players.file", directory.resolve("players.json").toString());
            System.setProperty("simplecoil.event.file", directory.resolve("event.properties").toString());
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
