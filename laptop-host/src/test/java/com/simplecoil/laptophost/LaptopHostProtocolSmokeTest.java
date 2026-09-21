package com.simplecoil.laptophost;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Predicate;

/** End-to-end smoke test for the wire protocol used by stock Android clients. */
public final class LaptopHostProtocolSmokeTest {
    private static final int TCP_PORT = 19_510;
    private static final int UDP_PORT = 19_500;
    private static final int DASHBOARD_PORT = 19_511;
    private static final String JSON_PREFIX = "SimpleCoil:14JSON";
    private static final String MESSAGE_PREFIX = "SimpleCoil:14MESG";

    public static void main(String[] args) throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1",
                "--no-late-join")
                .redirectErrorStream(true)
                .start();
        try {
            waitForHost();
            require(get("/map").contains("Tactical GPS Map"), "map display was unavailable");
            require(get("/leaderboard").contains("Leaderboard"), "leaderboard display was unavailable");
            verifyUdpDiscovery();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 11, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                first.send(JSON_PREFIX + "{\"gpslongitude\":-122.1,\"gpslatitude\":47.6}");
                second.send(JSON_PREFIX + "{\"gpslongitude\":-122.2,\"gpslatitude\":47.7}");
                require(post("/api/start").contains("\"ok\":true"), "host did not start the round");
                Thread.sleep(1_150);
                require(get("/api/state").contains("\"state\":\"running\""),
                        "round did not leave the start countdown");
                verifyLateJoinBlocked();
                verifyDirectLateJoinBlocked();

                first.send(JSON_PREFIX + "{\"telemetry\":\"shot\",\"count\":3}");
                second.send(JSON_PREFIX + "{\"telemetry\":\"hit\",\"attacker\":1}");
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                String kill = first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED11"));
                require(kill != null, "scorer did not receive the authoritative kill frame");

                String state = get("/api/state");
                require(state.contains("\"kills\":1"), "dashboard did not record a kill");
                require(state.contains("\"hits\":1"), "dashboard did not record a hit");
                require(state.contains("\"shots\":3"), "dashboard did not record shots");
                require(state.contains("\"kind\":\"kill\""), "dashboard did not receive a laser event");
                require(state.contains("\"shooterLongitude\":-122.1"),
                        "laser did not retain the shooter's event position");
                require(post("/api/respawn?id=11").contains("\"ok\":true"),
                        "game-master respawn was not accepted");
                require(second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                        "respawning player did not receive the grant");
            }
            System.out.println("LaptopHost protocol smoke test passed.");
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void waitForHost() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (get("/api/state").contains("\"state\""))
                    return;
            } catch (IOException ignored) {
            }
            Thread.sleep(50);
        }
        throw new AssertionError("laptop host did not start");
    }

    private static void verifyUdpDiscovery() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.3", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = "SimpleCoil:JOIN141".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SERVERREPLY".equals(message), "UDP discovery reply was incompatible");
        }
    }

    private static void verifyLateJoinBlocked() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.4", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = "SimpleCoil:JOIN142".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SAMETEAM".equals(message), "late discovery join was not refused");
        }
    }

    private static void verifyDirectLateJoinBlocked() throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.4", 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(1_000);
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeUTF(JSON_PREFIX + "{\"playerID\":2,\"playername\":\"Late\"}");
            output.flush();
            try {
                new DataInputStream(socket.getInputStream()).readUTF();
                throw new AssertionError("late direct TCP join was not refused");
            } catch (EOFException expected) {
                // The host closes a direct registration that bypasses discovery.
            }
        }
    }

    private static String get(String path) throws IOException {
        return request(path, "GET");
    }

    private static String post(String path) throws IOException {
        return request(path, "POST");
    }

    private static String request(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + DASHBOARD_PORT + path)
                .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(500);
        connection.setReadTimeout(1_000);
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
        connection.disconnect();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static final class FakePhone implements AutoCloseable {
        private final Socket socket = new Socket();
        private final DataInputStream input;
        private final DataOutputStream output;

        FakePhone(String localAddress, int playerID, String name) throws IOException {
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(2_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            send(JSON_PREFIX + "{\"playerID\":" + playerID + ",\"playername\":\"" + name + "\"}");
            readUntil(frame -> frame.startsWith(JSON_PREFIX + "{\"players\""));
        }

        void synchronizeClock() throws IOException {
            for (int sample = 0; sample < 5; sample++) {
                long stamp = System.nanoTime() / 1_000_000L;
                send(JSON_PREFIX + "{\"clocksync\":" + stamp + "}");
                String reply = readUntil(frame -> frame.startsWith(JSON_PREFIX) && frame.contains("\"clockreceive\""));
                require(reply != null, "clock sample was not answered");
            }
            send(JSON_PREFIX + "{\"clockready\":true}");
        }

        void send(String frame) throws IOException {
            output.writeUTF(frame);
            output.flush();
        }

        String readUntil(Predicate<String> wanted) throws IOException {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline) {
                String frame = input.readUTF();
                if (wanted.test(frame))
                    return frame;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
