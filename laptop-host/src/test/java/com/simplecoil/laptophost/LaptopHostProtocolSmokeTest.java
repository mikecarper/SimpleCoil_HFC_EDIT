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
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** End-to-end smoke test for the wire protocol used by stock Android clients. */
public final class LaptopHostProtocolSmokeTest {
    private static final int TCP_PORT = 19_510;
    private static final int UDP_PORT = 19_500;
    private static final int DASHBOARD_PORT = 19_511;
    private static final String JSON_PREFIX = "SimpleCoil:19JSON";
    private static final String MESSAGE_PREFIX = "SimpleCoil:19MESG";
    private static final long GPS_UTC_BASE = 1_800_000_000_000L;

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
                 FakePhone blueTeammate = new FakePhone("127.0.0.5", 2, "Blue Two");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red");
                 FakePhone redTeammate = new FakePhone("127.0.0.6", 18, "Red Two")) {
                first.synchronizeClock();
                blueTeammate.synchronizeClock();
                second.synchronizeClock();
                redTeammate.synchronizeClock();
                first.send(JSON_PREFIX + "{\"gpslongitude\":-122.1,\"gpslatitude\":47.6}");
                second.send(JSON_PREFIX + "{\"gpslongitude\":-122.2,\"gpslatitude\":47.7}");
                require(post("/api/start").contains("\"ok\":true"), "host did not start the round");
                String startFrame = first.readUntil(frame -> frame.contains("\"gpsstarttime\""));
                require(startFrame != null,
                        "GPS-calibrated start time was not delivered to the phone");
                String roundToken = jsonString(startFrame, "roundtoken");
                require(roundToken != null, "start frame omitted its round token");
                first.sendStateGossip(roundToken, 41, 7);
                require(first.receivesAuthorityState(roundToken, 41, 7),
                        "host tick did not merge and return a player's state gossip");
                first.sendCombat(roundToken, 9, 2, 17);
                require(first.receivesAuthorityCombat(roundToken, 1, 9, 2, 17),
                        "host tick did not retain an overheard compact combat event");
                Thread.sleep(1_150);
                require(get("/api/state").contains("\"state\":\"running\""),
                        "round did not leave the start countdown");
                verifyLateJoinBlocked();
                verifyDirectLateJoinBlocked();

                first.send(JSON_PREFIX + "{\"telemetry\":\"shot\",\"count\":3}");
                require(!first.receivesFor(frame -> gpsFrameContainsPlayer(frame, 17), 600),
                        "a shot/miss revealed an enemy GPS location");
                second.send(JSON_PREFIX + "{\"telemetry\":\"hit\",\"attacker\":1}");
                require(first.readUntil(frame -> gpsFrameContainsPlayer(frame, 17)) != null,
                        "a confirmed hit did not reveal the targeted enemy to the shooter");
                require(blueTeammate.readUntil(frame -> gpsFrameContainsPlayer(frame, 17)) != null,
                        "a confirmed hit did not reveal the targeted enemy to the shooter's team");
                require(second.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)) != null,
                        "a confirmed hit did not reveal the shooter to the target");
                require(redTeammate.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)) != null,
                        "a confirmed hit did not reveal the shooter to the target's team");
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                String kill = first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17"));
                require(kill != null, "scorer did not receive the authoritative kill frame");

                String state = get("/api/state");
                require(state.contains("\"kills\":1"), "dashboard did not record a kill");
                require(state.contains("\"hits\":1"), "dashboard did not record a hit");
                require(state.contains("\"shots\":3"), "dashboard did not record shots");
                require(state.contains("\"kind\":\"kill\""), "dashboard did not receive a laser event");
                require(state.contains("\"shooterLongitude\":-122.1"),
                        "laser did not retain the shooter's event position");
                require(post("/api/respawn?id=17").contains("\"ok\":true"),
                        "game-master respawn was not accepted");
                require(second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                        "respawning player did not receive the grant");
                require(post("/api/end").contains("\"ok\":true"), "host did not end the round");
                String finishedState = get("/api/state");
                require(finishedState.contains("\"canStart\":false"),
                        "dashboard allowed another round during the cooldown");
                require(post("/api/start").contains("Next game can start in"),
                        "host accepted a new round before the 30-second wait");
            }
            System.out.println("LaptopHost protocol smoke test passed.");
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
        verifyBalancedQr();
        System.out.println("LaptopHost balanced QR smoke test passed.");
        verifyBalancedNoQr();
        System.out.println("LaptopHost balanced no-QR smoke test passed.");
        verifyBossMode();
        System.out.println("LaptopHost Boss Mode smoke test passed.");
        if (args.length > 0 && "--verify-timeout".equals(args[0])) {
            verifyBalancedQrTimeout();
            System.out.println("LaptopHost 90-second QR timeout smoke test passed.");
        }
    }

    private static void verifyBalancedQr() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--balanced-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT))
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            verifyBalancedJoinCanCrossOldTeamBoundary();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("Waiting up to 90 seconds"),
                        "balanced QR mode did not wait for check-in");
                require(first.readUntil(frame -> frame.contains("\"balancedrandom\":true")
                        && frame.contains("\"team\":")) != null,
                        "balanced assignments were not sent to the phones");
                first.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
                require(!first.receivesFor(frame -> frame.contains("\"gamestart\""), 300),
                        "one QR check-in started the game early");
                second.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "the final QR check-in did not start the shared countdown");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBalancedJoinCanCrossOldTeamBoundary() throws Exception {
        List<DatagramSocket> pendingJoins = new ArrayList<>();
        try {
            for (int index = 0; index < 17; index++) {
                DatagramSocket socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0." + (20 + index), UDP_PORT));
                socket.setSoTimeout(1_000);
                pendingJoins.add(socket);
                byte[] request = "SimpleCoil:JOIN191".getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(request, request.length,
                        InetAddress.getByName("127.0.0.1"), UDP_PORT));
                byte[] reply = new byte[128];
                DatagramPacket packet = new DatagramPacket(reply, reply.length);
                socket.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                int expectedID = index + 1;
                String expected = "SimpleCoil:SERVERREPLY" + (expectedID == 1 ? "" : ":" + expectedID);
                require(expected.equals(message), "balanced lobby remained capped at sixteen IDs per team");
            }
        } finally {
            for (DatagramSocket socket : pendingJoins)
                socket.close();
        }
    }

    private static void verifyBalancedNoQr() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--balanced-no-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT))
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"),
                        "balanced no-QR mode did not start without check-in");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "balanced no-QR countdown was not sent");
                require(get("/api/state").contains("\"balancedQr\":false"),
                        "balanced no-QR state was not exposed");
                verifyDirectLateJoinBlocked();
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBalancedQrTimeout() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--balanced-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT))
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("90 seconds"),
                        "QR check-in timeout was not announced");
                long assignedAt = System.nanoTime();
                require(get("/api/state").contains("\"checkedInCount\":0"),
                        "the unscanned lobby was not retained");
                boolean started = false;
                while (System.nanoTime() - assignedAt < 96_000_000_000L) {
                    String state = get("/api/state");
                    if (state.contains("\"state\":\"countdown\"")
                            || state.contains("\"state\":\"running\"")) {
                        require(System.nanoTime() - assignedAt >= 89_000_000_000L,
                                "QR timeout started the round too early");
                        require(state.contains("\"playerCount\":2")
                                && state.contains("\"checkedInCount\":0"),
                                "unscanned players lost their assigned places");
                        started = true;
                        break;
                    }
                    first.send("pong");
                    second.send("pong");
                    Thread.sleep(1_000);
                }
                require(started, "an unscanned player kept the game from starting after 90 seconds");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "QR timeout start was not delivered to a phone");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBossMode() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--boss",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone boss = new FakePhone("127.0.0.3", 1, "Boss");
                 FakePhone hunter = new FakePhone("127.0.0.2", 2, "Hunter")) {
                String roster = boss.readUntil(frame -> frame.contains("\"bossmode\":true")
                        && frame.contains("\"playerID\":2"));
                require(roster != null, "Boss Mode was not advertised to the phones");
                require(roster.contains("\"playerID\":1,\"health\":6,\"reloadshots\":120")
                                && roster.contains("\"shotmodeburst3\":true,\"shotmodeauto\":true"),
                        "boss did not receive scaled health, 120 rounds, and unlocked fire modes");
                require(roster.contains("\"playerID\":2,\"health\":2,\"reloadshots\":30")
                                && roster.contains("\"playerID\":2,\"health\":2")
                                && roster.contains("\"shotmodeburst3\":false,\"shotmodeauto\":false"),
                        "hunter did not receive the locked single-shot profile");
                boss.synchronizeClock();
                hunter.synchronizeClock();
                long readyDeadline = System.nanoTime() + 3_000_000_000L;
                while (!get("/api/state").contains("\"canStart\":true")
                        && System.nanoTime() < readyDeadline)
                    Thread.sleep(25);
                String startResponse = post("/api/start");
                require(startResponse.contains("\"ok\":true"),
                        "a valid Boss roster did not start: " + startResponse);
                require(boss.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "Boss Mode countdown was not delivered");
                verifyDirectLateJoinBlockedAs(3);
            }
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
            byte[] request = "SimpleCoil:JOIN191".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SERVERREPLY".equals(message),
                    "UDP discovery reply was incompatible: " + message);
        }
    }

    private static void verifyLateJoinBlocked() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.4", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = "SimpleCoil:JOIN192".getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SAMETEAM".equals(message), "late discovery join was not refused");
        }
    }

    private static void verifyDirectLateJoinBlocked() throws Exception {
        verifyDirectLateJoinBlockedAs(2);
    }

    private static void verifyDirectLateJoinBlockedAs(int playerID) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.4", 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(1_000);
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeUTF(JSON_PREFIX + "{\"playerID\":" + playerID
                    + ",\"playername\":\"Late\"}");
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

    private static boolean gpsFrameContainsPlayer(String frame, int playerID) {
        return frame.startsWith(JSON_PREFIX) && frame.contains("\"gpsupdate\"")
                && frame.contains("\"playerID\":" + playerID);
    }

    private static String jsonString(String frame, String key) {
        String prefix = "\"" + key + "\":\"";
        int start = frame.indexOf(prefix);
        if (start < 0)
            return null;
        start += prefix.length();
        int end = frame.indexOf('"', start);
        return end < 0 ? null : frame.substring(start, end);
    }

    private static final class FakePhone implements AutoCloseable {
        private static final int STATE_PACKET_BYTES = 1_312;
        private static final int STATE_PACKET_MAGIC = 0x53434F49;
        private final Socket socket = new Socket();
        private final DatagramSocket udp;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final int playerID;

        FakePhone(String localAddress, int playerID, String name) throws IOException {
            this.playerID = playerID;
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(localAddress, UDP_PORT));
            udp.setSoTimeout(3_000);
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(2_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            send(JSON_PREFIX + "{\"playerID\":" + playerID + ",\"playername\":\"" + name + "\"}");
            readUntil(frame -> frame.startsWith(JSON_PREFIX + "{\"players\""));
        }

        void sendStateGossip(String roundToken, long ownerSequence, int score) throws IOException {
            ByteBuffer packet = ByteBuffer.allocate(STATE_PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
            UUID token = UUID.fromString(roundToken);
            packet.putInt(STATE_PACKET_MAGIC);
            packet.put((byte) 1); // state-packet format
            packet.put((byte) 19); // network protocol
            packet.put((byte) playerID);
            packet.put((byte) 2); // two-team mode
            packet.putLong(1); // sender packet sequence
            packet.putLong(token.getMostSignificantBits());
            packet.putLong(token.getLeastSignificantBits());
            for (int id = 1; id <= 32; id++) {
                boolean present = id == playerID;
                packet.put((byte) (present ? 1 : 0));
                packet.put((byte) id);
                packet.put((byte) (present ? 1 : 0)); // running
                packet.put((byte) 0); // grenade
                packet.put((byte) 0); // event
                packet.put((byte) 0); // target
                packet.putShort((short) 0);
                packet.putInt((int) (present ? ownerSequence : 0));
                packet.putInt(0);
                packet.putInt(present ? score : 0);
                packet.putInt(0);
                packet.putShort((short) (present ? 5 : 0));
                packet.putShort((short) (present ? 10 : 0));
                packet.putShort((short) (present ? 29 : 0));
                packet.putShort((short) 0);
                packet.putInt(0);
                packet.putInt(0);
            }
            byte[] payload = packet.array();
            udp.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("127.0.0.1"), UDP_PORT));
        }

        void sendCombat(String roundToken, long eventSequence, int eventType, int targetID)
                throws IOException {
            ByteBuffer packet = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
            UUID token = UUID.fromString(roundToken);
            packet.putInt(0x53434F43); // SCOC
            packet.put((byte) 1); // combat-packet format
            packet.put((byte) 19); // network protocol
            packet.put((byte) 1); // event, not ACK
            packet.put((byte) eventType);
            packet.put((byte) playerID);
            packet.put((byte) targetID);
            packet.putShort((short) 0);
            packet.putInt((int) eventSequence);
            packet.putLong(token.getMostSignificantBits());
            packet.putLong(token.getLeastSignificantBits());
            byte[] payload = packet.array();
            udp.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("127.0.0.1"), UDP_PORT));
        }

        boolean receivesAuthorityState(String roundToken, long ownerSequence, int score)
                throws IOException {
            UUID expectedToken = UUID.fromString(roundToken);
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] payload = new byte[STATE_PACKET_BYTES];
                DatagramPacket datagram = new DatagramPacket(payload, payload.length);
                try {
                    udp.receive(datagram);
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (datagram.getLength() != STATE_PACKET_BYTES)
                    continue;
                ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                if (packet.getInt() != STATE_PACKET_MAGIC || (packet.get(6) & 0xff) != 0)
                    continue;
                UUID token = new UUID(packet.getLong(16), packet.getLong(24));
                if (!expectedToken.equals(token))
                    continue;
                int rowOffset = 32 + (playerID - 1) * 40;
                if ((packet.get(rowOffset) & 1) != 0
                        && (packet.getInt(rowOffset + 8) & 0xffffffffL) == ownerSequence
                        && packet.getInt(rowOffset + 16) == score)
                    return true;
            }
            return false;
        }

        boolean receivesAuthorityCombat(String roundToken, int senderID, long eventSequence,
                                        int eventType, int targetID) throws IOException {
            UUID expectedToken = UUID.fromString(roundToken);
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] payload = new byte[STATE_PACKET_BYTES];
                DatagramPacket datagram = new DatagramPacket(payload, payload.length);
                try {
                    udp.receive(datagram);
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (datagram.getLength() != STATE_PACKET_BYTES)
                    continue;
                ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                if (packet.getInt() != STATE_PACKET_MAGIC || (packet.get(6) & 0xff) != 0)
                    continue;
                UUID token = new UUID(packet.getLong(16), packet.getLong(24));
                if (!expectedToken.equals(token))
                    continue;
                int rowOffset = 32 + (senderID - 1) * 40;
                if ((packet.get(rowOffset) & 1) != 0
                        && (packet.getInt(rowOffset + 12) & 0xffffffffL) == eventSequence
                        && (packet.get(rowOffset + 4) & 0xff) == eventType
                        && (packet.get(rowOffset + 5) & 0xff) == targetID)
                    return true;
            }
            return false;
        }

        void synchronizeClock() throws IOException {
            for (int sample = 0; sample < 12; sample++) {
                long stamp = System.nanoTime() / 1_000_000L;
                send(JSON_PREFIX + "{\"clocksync\":" + stamp + ",\"gpsclock\":"
                        + (GPS_UTC_BASE + stamp) + "}");
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

        boolean receivesFor(Predicate<String> wanted, long durationMillis) throws IOException {
            long deadline = System.nanoTime() + durationMillis * 1_000_000L;
            int previousTimeout = socket.getSoTimeout();
            try {
                while (System.nanoTime() < deadline) {
                    long remainingMillis = Math.max(1,
                            (deadline - System.nanoTime() + 999_999L) / 1_000_000L);
                    socket.setSoTimeout((int) Math.min(previousTimeout, remainingMillis));
                    try {
                        if (wanted.test(input.readUTF()))
                            return true;
                    } catch (SocketTimeoutException expected) {
                        return false;
                    }
                }
                return false;
            } finally {
                socket.setSoTimeout(previousTimeout);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                socket.close();
            } finally {
                udp.close();
            }
        }
    }
}
