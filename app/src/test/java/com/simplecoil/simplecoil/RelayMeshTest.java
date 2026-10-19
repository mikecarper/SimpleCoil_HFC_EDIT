package com.simplecoil.simplecoil;

import com.simplecoil.protocol.RelayConfig;
import com.simplecoil.protocol.RelayMesh;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import static org.junit.Assert.*;

public class RelayMeshTest {
    private static final UUID A = new UUID(1, 1), B = new UUID(1, 2), C = new UUID(1, 3), KEY = new UUID(2, 1);
    private RelayConfig config(UUID key) {
        Map<UUID, RelayConfig.Member> members = new LinkedHashMap<>();
        members.put(A, new RelayConfig.Member(A, RelayConfig.ipv4("10.80.0.1"), "00:11:22:33:44:01"));
        members.put(B, new RelayConfig.Member(B, RelayConfig.ipv4("10.80.0.2"), "00:11:22:33:44:02"));
        members.put(C, new RelayConfig.Member(C, RelayConfig.ipv4("10.80.0.3"), ""));
        return new RelayConfig(key, C, B, members);
    }
    private static final class Receiver implements RelayMesh.Listener {
        final BlockingQueue<String> packets = new LinkedBlockingQueue<>();
        final BlockingQueue<Socket> sockets = new LinkedBlockingQueue<>();
        final BlockingQueue<String> diagnostics = new LinkedBlockingQueue<>();
        @Override public void datagram(InetAddress source, InetAddress destination, int port, byte[] bytes) {
            packets.add(source.getHostAddress() + ":" + port + ":" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public boolean accept(Socket socket, int port) { return port == 17510 && sockets.offer(socket); }
        @Override public void linkOpened(UUID peer) { diagnostics.add("opened"); }
        @Override public void linkClosed(UUID peer, String reason) { diagnostics.add("closed: " + reason); }
        @Override public void linkCongested(UUID peer) { diagnostics.add("congested"); }
        @Override public void handshakeFailed(String reason) { diagnostics.add("handshake: " + reason); }
    }
    private RelayMesh mesh(UUID id, RelayConfig config, Receiver receiver) throws IOException {
        return new RelayMesh(id, config, new InetSocketAddress("127.0.0.1", 0), receiver);
    }
    private static void connect(RelayMesh from, RelayMesh to) {
        from.connect(new InetSocketAddress("127.0.0.1", to.port()));
    }
    private interface Condition { boolean get(); }
    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.get() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Relay condition timed out", condition.get());
    }

    @Test public void outOfHubClientReachesHostThroughAnotherPhoneForUdpAndControl() throws Exception {
        RelayConfig config = config(KEY);Receiver ar = new Receiver(), br = new Receiver(), cr = new Receiver();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh b = mesh(B, config, br);RelayMesh c = mesh(C, config, cr)) {
            connect(a, b);connect(b, c);await(a::hostReachable);
            assertTrue(a.datagram(config.members.get(C).address, 17500, "hit".getBytes("UTF-8")));
            assertEquals("10.80.0.1:17500:hit", cr.packets.poll(3, TimeUnit.SECONDS));
            assertTrue(br.packets.isEmpty());
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000)) {
                Socket server = cr.sockets.poll(3, TimeUnit.SECONDS);assertNotNull(server);
                assertEquals(config.members.get(A).address, server.getInetAddress());
                DataOutputStream request = new DataOutputStream(client.getOutputStream());
                DataInputStream receive = new DataInputStream(server.getInputStream());
                StringBuilder large = new StringBuilder();for (int i = 0; i < 40_000; i++) large.append((char) ('a' + i % 26));
                request.writeUTF(large.toString());request.flush();assertEquals(large.toString(), receive.readUTF());
                new DataOutputStream(server.getOutputStream()).writeUTF("same-round-host-command");
                assertEquals("same-round-host-command", new DataInputStream(client.getInputStream()).readUTF());
                server.close();
            }
        }
    }

    @Test public void duplicatePathsDoNotDuplicateGameEventsAndSurviveOnePathClosing() throws Exception {
        RelayConfig config = config(KEY);Receiver ar = new Receiver(), br = new Receiver(), cr = new Receiver();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh b = mesh(B, config, br);RelayMesh c = mesh(C, config, cr)) {
            connect(a, b);connect(b, c);connect(a, c);await(() -> a.links() == 2 && b.links() == 2 && c.links() == 2);
            await(a::hostReachable);
            a.datagram(config.members.get(C).address, 17500, "one".getBytes("UTF-8"));
            assertEquals("10.80.0.1:17500:one", cr.packets.poll(3, TimeUnit.SECONDS));
            assertNull(cr.packets.poll(150, TimeUnit.MILLISECONDS));
            Socket client = a.open(config.members.get(C).address, 17510, 3000);
            Socket server = cr.sockets.poll(3, TimeUnit.SECONDS);assertNotNull(server);
            b.close();
            new DataOutputStream(client.getOutputStream()).writeUTF("after-bridge-path-loss");
            assertEquals("after-bridge-path-loss", new DataInputStream(server.getInputStream()).readUTF());
            client.close();server.close();
        }
    }

    @Test public void anotherGamesRelayKeyCannotJoin() throws Exception {
        try (RelayMesh a = mesh(A, config(KEY), new Receiver());
             RelayMesh c = mesh(C, config(new UUID(2, 2)), new Receiver())) {
            connect(a, c);Thread.sleep(200);assertEquals(0, a.links());assertEquals(0, c.links());assertFalse(a.hostReachable());
        }
    }

    @Test public void virtualSocketHonorsReadTimeoutAndRejectsUnapprovedPorts() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        try (RelayMesh a = mesh(A, config, new Receiver());RelayMesh c = mesh(C, config, cr)) {
            connect(a, c);await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000)) {
                client.setSoTimeout(40);
                try { client.getInputStream().read();fail("Read timeout ignored"); }
                catch (SocketTimeoutException expected) { }
            }
            try { a.open(config.members.get(C).address, 22, 100);fail("Unexpected service was exposed"); }
            catch (ConnectException expected) { }
        }
    }

    @Test public void defaultReadWaitsThroughLongIdleAndThenReceivesControl() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (RelayMesh a = mesh(A, config, new Receiver());RelayMesh c = mesh(C, config, cr)) {
            connect(a, c);await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);assertEquals(0, server.getSoTimeout());
                Future<String> message = reader.submit(() -> new DataInputStream(server.getInputStream()).readUTF());
                Thread.sleep(5200);
                assertFalse("An idle admitted player must not lose its control stream after five seconds", message.isDone());
                new DataOutputStream(client.getOutputStream()).writeUTF("battery-request-after-idle");
                assertEquals("battery-request-after-idle", message.get(3, TimeUnit.SECONDS));
            }
        } finally { reader.shutdownNow(); }
    }

    @Test public void stateBurstCannotCloseBlockedControlLink() throws Exception {
        RelayConfig config = config(KEY);Receiver ar = new Receiver(), cr = new Receiver();
        ExecutorService opener = Executors.newSingleThreadExecutor();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh c = mesh(C, config, cr);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            GateSocket slow = new GateSocket(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
            a.attach(slow);c.attach(listener.accept());await(() -> a.links() == 1 && c.links() == 1);
            await(a::hostReachable);slow.pause();
            a.datagram(config.members.get(C).address, 17500, new byte[]{0});
            assertTrue("Test writer did not reach its simulated radio stall", slow.waiting.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 512; i++)
                a.datagram(config.members.get(C).address, 17500, Integer.toString(i).getBytes("UTF-8"));
            Future<Socket> opened = opener.submit(() -> a.open(config.members.get(C).address, 17510, 3000));
            // The writer stays blocked while OPEN must make room in the full state queue.
            Thread.sleep(150);slow.resume();
            try (Socket client = opened.get(4, TimeUnit.SECONDS);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);assertEquals(1, a.links());assertEquals(1, c.links());
                new DataOutputStream(client.getOutputStream()).writeUTF("control-survived-state-burst");
                server.setSoTimeout(3000);
                assertEquals("control-survived-state-burst", new DataInputStream(server.getInputStream()).readUTF());
                assertFalse(ar.diagnostics.contains("closed: control queue full"));
            }
        } finally { opener.shutdownNow(); }
    }

    @Test public void reliableBurstAndRetriesSurviveCongestedBluetoothBridgeInOrder() throws Exception {
        RelayConfig config = config(KEY);Receiver ar = new Receiver(), br = new Receiver(), cr = new Receiver();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh b = mesh(B, config, br);RelayMesh c = mesh(C, config, cr);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            GateSocket slow = new GateSocket(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
            b.attach(slow);a.attach(listener.accept());connect(b, c);await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);slow.pause();
                DataOutputStream host = new DataOutputStream(server.getOutputStream());
                host.writeUTF("host-command-0");
                assertTrue(slow.waiting.await(3, TimeUnit.SECONDS));
                for (int i = 1; i < 512; i++) host.writeUTF("host-command-" + i);
                await(() -> br.diagnostics.contains("congested") || b.links() < 2);
                // At least two retry intervals elapse while none of the bridge's
                // queued host commands can reach the out-of-hub phone.
                Thread.sleep(750);
                assertEquals(1, a.links());assertEquals(2, b.links());assertEquals(1, c.links());
                assertTrue("The test must exceed the physical queue's reliable capacity",
                        br.diagnostics.contains("congested"));
                slow.resume();client.setSoTimeout(5000);server.setSoTimeout(5000);
                DataInputStream commands = new DataInputStream(client.getInputStream());
                for (int i = 0; i < 512; i++) assertEquals("host-command-" + i, commands.readUTF());
                new DataOutputStream(client.getOutputStream()).writeUTF("fresh-battery-report");
                assertEquals("fresh-battery-report", new DataInputStream(server.getInputStream()).readUTF());
                assertEquals(1, a.links());assertEquals(2, b.links());assertEquals(1, c.links());
                assertFalse(br.diagnostics.contains("closed: control queue full"));
            }
        }
    }

    @Test public void urgentCloseStillWaitsForEveryQueuedByteBeforeEndOfStream() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        try (RelayMesh a = mesh(A, config, new Receiver());RelayMesh c = mesh(C, config, cr);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            GateSocket slow = new GateSocket(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
            c.attach(slow);a.attach(listener.accept());await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);slow.pause();
                DataOutputStream host = new DataOutputStream(server.getOutputStream());
                host.writeUTF("final-command-0");
                assertTrue(slow.waiting.await(3, TimeUnit.SECONDS));
                for (int i = 1; i < 256; i++) host.writeUTF("final-command-" + i);
                server.close(); // CLOSE can overtake DATA, but carries the final byte offset.
                // A closed application's unacknowledged data must survive the
                // old five-second close grace and retain its normal retry budget.
                Thread.sleep(6000);
                slow.resume();client.setSoTimeout(3000);
                DataInputStream commands = new DataInputStream(client.getInputStream());
                for (int i = 0; i < 256; i++) assertEquals("final-command-" + i, commands.readUTF());
                assertEquals(-1, commands.read());
                assertEquals(1, a.links());assertEquals(1, c.links());
            }
        }
    }

    @Test public void triangleUnicastUsesOnlyItsDestinationLinkWhileBroadcastStillPropagates() throws Exception {
        RelayConfig config = config(KEY);Receiver ar = new Receiver(), br = new Receiver(), cr = new Receiver();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh b = mesh(B, config, br);RelayMesh c = mesh(C, config, cr)) {
            GateSocket[] ab = attach(a, b), bc = attach(b, c), ac = attach(a, c);
            await(() -> a.links() == 2 && b.links() == 2 && c.links() == 2);await(a::hostReachable);
            a.datagram(config.members.get(C).address, 17500, "unicast".getBytes("UTF-8"));
            assertEquals("10.80.0.1:17500:unicast", cr.packets.poll(3, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, ac[0].frameWrites.get(1));
            for (GateSocket link : new GateSocket[]{ab[0], ab[1], bc[0], bc[1], ac[1]})
                assertEquals("A terminal unicast was copied onto an alternate link", 0, link.frameWrites.get(1));
            a.datagram(RelayConfig.ipv4("255.255.255.255"), 17500, "broadcast".getBytes("UTF-8"));
            assertEquals("10.80.0.1:17500:broadcast", br.packets.poll(3, TimeUnit.SECONDS));
            assertEquals("10.80.0.1:17500:broadcast", cr.packets.poll(3, TimeUnit.SECONDS));
            assertNull(br.packets.poll(100, TimeUnit.MILLISECONDS));
            assertNull(cr.packets.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(ab[0].frameWrites.get(1) > 0);
            assertTrue(bc[0].frameWrites.get(1) + bc[1].frameWrites.get(1) > 0);
        }
    }

    @Test public void twoBluetoothHelpersCarryOneHostReplyAndFailOverWithoutReplacingTheStream() throws Exception {
        UUID dId = new UUID(1, 4);RelayConfig base = config(KEY);
        Map<UUID, RelayConfig.Member> members = new LinkedHashMap<>(base.members);
        members.put(dId, new RelayConfig.Member(dId, RelayConfig.ipv4("10.80.0.4"), ""));
        RelayConfig config = new RelayConfig(KEY, C, B, members);
        Receiver ar = new Receiver(), cr = new Receiver();
        try (RelayMesh a = mesh(A, config, ar);RelayMesh b = mesh(B, config, new Receiver());
             RelayMesh c = mesh(C, config, cr);RelayMesh d = mesh(dId, config, new Receiver())) {
            GateSocket[] ab = attach(a, b), ad = attach(a, d), bc = attach(b, c), dc = attach(d, c);
            attach(b, d);await(() -> a.links() == 2 && b.links() == 3 && c.links() == 2 && d.links() == 3);
            await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);client.setSoTimeout(3000);
                int throughB = bc[1].frameWrites.get(4), throughD = dc[1].frameWrites.get(4);
                new DataOutputStream(server.getOutputStream()).writeUTF("one-host-reply");
                assertEquals("one-host-reply", new DataInputStream(client.getInputStream()).readUTF());
                Thread.sleep(100);
                int sentB = bc[1].frameWrites.get(4) - throughB, sentD = dc[1].frameWrites.get(4) - throughD;
                assertEquals("The laptop duplicated one reply across both Bluetooth helpers", 1, sentB + sentD);
                assertEquals("The destination forwarded its already-delivered reply back to a helper", 0,
                        ab[0].frameWrites.get(4) + ad[0].frameWrites.get(4));
                (sentB > 0 ? b : d).close();
                await(() -> a.links() == 1 && c.links() == 1);
                new DataOutputStream(server.getOutputStream()).writeUTF("same-stream-after-helper-loss");
                assertEquals("same-stream-after-helper-loss", new DataInputStream(client.getInputStream()).readUTF());
            }
        }
    }

    @Test public void silentEstablishedWifiLinkDoesNotBlackholeTheExistingStream() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        try (RelayMesh a = mesh(A, config, new Receiver());RelayMesh b = mesh(B, config, new Receiver());
             RelayMesh c = mesh(C, config, cr)) {
            attach(a, b);GateSocket[] bc = attach(b, c), ac = attach(a, c);
            await(() -> a.links() == 2 && c.links() == 2);await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);client.setSoTimeout(3000);
                DataOutputStream host = new DataOutputStream(server.getOutputStream());
                host.writeUTF("over-direct-wifi");
                assertEquals("over-direct-wifi", new DataInputStream(client.getInputStream()).readUTF());
                ac[0].pause();ac[1].pause();
                Thread.sleep(4000); // Keep TCP established while both native directions stop carrying bytes.
                int alternate = bc[1].frameWrites.get(4);
                host.writeUTF("over-bluetooth-after-wifi-stalls");
                assertEquals("over-bluetooth-after-wifi-stalls", new DataInputStream(client.getInputStream()).readUTF());
                assertTrue(bc[1].frameWrites.get(4) > alternate);
                assertEquals(2, a.links());assertEquals(2, c.links());
            }
        }
    }

    @Test public void fullPreferredWriterUsesFreeAlternateForQueuedByteGaps() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        try (RelayMesh a = mesh(A, config, new Receiver());RelayMesh b = mesh(B, config, new Receiver());
             RelayMesh c = mesh(C, config, cr)) {
            attach(a, b);GateSocket[] bc = attach(b, c), ac = attach(a, c);
            await(() -> a.links() == 2 && c.links() == 2);await(a::hostReachable);
            try (Socket client = a.open(config.members.get(C).address, 17510, 3000);
                 Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                assertNotNull(server);client.setSoTimeout(5000);
                ac[1].pause(); // Client-to-host PINGs still keep the direct link fresh.
                DataOutputStream host = new DataOutputStream(server.getOutputStream());
                host.writeUTF("alternate-command-0");
                assertTrue(ac[1].waiting.await(3, TimeUnit.SECONDS));
                for (int i = 1; i < 384; i++) host.writeUTF("alternate-command-" + i);
                DataInputStream commands = new DataInputStream(client.getInputStream());
                for (int i = 0; i < 384; i++) assertEquals("alternate-command-" + i, commands.readUTF());
                assertTrue("The congested preferred route never tried the free helper", bc[1].frameWrites.get(4) > 0);
                assertEquals(2, a.links());assertEquals(2, c.links());
            }
        }
    }

    @Test public void samePhoneCanRestartItsMeshWhileNeighborsRetainTheSameCohortAndHistory() throws Exception {
        RelayConfig config = config(KEY);Receiver cr = new Receiver();
        try (RelayMesh b = mesh(B, config, new Receiver());RelayMesh c = mesh(C, config, cr)) {
            connect(b, c);await(b::hostReachable);
            try (RelayMesh previousPhone = mesh(A, config, new Receiver())) {
                connect(previousPhone, b);await(previousPhone::hostReachable);
                for (int i = 0; i < 64; i++)
                    previousPhone.datagram(config.members.get(C).address, 17500, ("old-" + i).getBytes("UTF-8"));
                for (int i = 0; i < 64; i++) assertNotNull(cr.packets.poll(3, TimeUnit.SECONDS));
            }
            await(() -> b.links() == 1);
            try (RelayMesh restartedPhone = mesh(A, config, new Receiver())) {
                connect(restartedPhone, b);await(restartedPhone::hostReachable);
                restartedPhone.datagram(config.members.get(C).address, 17500, "after-restart".getBytes("UTF-8"));
                assertEquals("10.80.0.1:17500:after-restart", cr.packets.poll(3, TimeUnit.SECONDS));
                try (Socket client = restartedPhone.open(config.members.get(C).address, 17510, 3000);
                     Socket server = cr.sockets.poll(3, TimeUnit.SECONDS)) {
                    assertNotNull(server);server.setSoTimeout(3000);
                    new DataOutputStream(client.getOutputStream()).writeUTF("new-stream-same-player");
                    assertEquals("new-stream-same-player", new DataInputStream(server.getInputStream()).readUTF());
                }
            }
        }
    }

    private static GateSocket[] attach(RelayMesh first, RelayMesh second) throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            GateSocket outgoing = new GateSocket(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
            GateSocket incoming = new GateSocket(listener.accept());
            first.attach(outgoing);second.attach(incoming);return new GateSocket[]{outgoing, incoming};
        }
    }

    /** Stops a real link's writes without changing its framing, handshake, or reads. */
    private static final class GateSocket extends Socket {
        private final Socket delegate;
        private boolean paused, stopped;
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicIntegerArray frameWrites = new AtomicIntegerArray(7);
        GateSocket(Socket delegate) { this.delegate = delegate; }
        synchronized void pause() { paused = true; }
        synchronized void resume() { paused = false;notifyAll(); }
        private synchronized void beforeWrite() throws IOException {
            while (paused && !stopped) {
                waiting.countDown();
                try { wait(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new InterruptedIOException(); }
            }
            if (stopped) throw new SocketException("Test link closed");
        }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException {
            OutputStream output = delegate.getOutputStream();
            return new OutputStream() {
                @Override public void write(int value) throws IOException { beforeWrite();output.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    beforeWrite();
                    if (length >= 64 && (bytes[offset] & 255) < 7) frameWrites.incrementAndGet(bytes[offset] & 255);
                    output.write(bytes, offset, length);
                }
                @Override public void flush() throws IOException { output.flush(); }
            };
        }
        @Override public void setTcpNoDelay(boolean enabled) throws SocketException { delegate.setTcpNoDelay(enabled); }
        @Override public void setKeepAlive(boolean enabled) throws SocketException { delegate.setKeepAlive(enabled); }
        @Override public void setSoTimeout(int timeout) throws SocketException { delegate.setSoTimeout(timeout); }
        @Override public void close() throws IOException {
            synchronized (this) { stopped = true;paused = false;notifyAll(); }
            delegate.close();
        }
    }

    @Test public void membershipCodecRejectsDnsDuplicateEndpointsAndUnadmittedNodes() {
        RelayConfig original = config(KEY), copy = RelayConfig.decode(original.encode());
        assertEquals(original.encode(), copy.encode());
        assertTrue(copy.accepts(A, RelayConfig.ipv4("10.80.0.1")));
        assertFalse(copy.accepts(B, RelayConfig.ipv4("10.80.0.1")));
        for (String value : new String[]{"example.com", "10.1.2.999", "010.1.2.3", "10.1.2", "::1"}) {
            try { RelayConfig.ipv4(value);fail("Invalid address accepted: " + value); }
            catch (IllegalArgumentException expected) { }
        }
        try { RelayConfig.decode(original.encode().replace("10.80.0.2", "10.80.0.1"));fail("Duplicate endpoint accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
