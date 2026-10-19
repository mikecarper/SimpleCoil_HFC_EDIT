package com.simplecoil.simplecoil;

import com.simplecoil.protocol.RelayConfig;
import com.simplecoil.protocol.RelayMesh;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
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
        @Override public void datagram(InetAddress source, InetAddress destination, int port, byte[] bytes) {
            packets.add(source.getHostAddress() + ":" + port + ":" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public boolean accept(Socket socket, int port) { return port == 17510 && sockets.offer(socket); }
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
