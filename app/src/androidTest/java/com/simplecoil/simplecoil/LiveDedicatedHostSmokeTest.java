package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/**
 * Optional physical-network smoke test for a running laptop/Pi host.
 *
 * <p>Normal CI deliberately skips this test. Invoke it with an instrumentation argument such as
 * {@code -e hostAddress 11.11.11.63}; that makes it useful at a field setup without baking a
 * particular hub or host address into the app.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LiveDedicatedHostSmokeTest {
    private static final int DISCOVERY_PORT = 17500;
    private static final int TCP_PORT = 17510;
    private static final int TEST_PLAYER_ID = 20;
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    @Test
    public void phoneWifiCanDiscoverAndReachConfiguredDedicatedHost() throws Exception {
        String hostAddress = InstrumentationRegistry.getArguments().getString("hostAddress");
        Assume.assumeTrue("Set -e hostAddress for a live host smoke test",
                hostAddress != null && !hostAddress.trim().isEmpty());
        InetAddress host = InetAddress.getByName(hostAddress.trim());

        String joinRequest = NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN
                + NetMsg.NETWORK_VERSION + TEST_PLAYER_ID;
        try (DatagramSocket udp = new DatagramSocket(null)) {
            // The production discovery listener receives replies on port 17500, so bind this
            // physical-device test to the same port rather than accepting an ephemeral-port-only
            // response that the real app would never see.
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(DISCOVERY_PORT));
            udp.setSoTimeout(SOCKET_TIMEOUT_MS);
            byte[] request = joinRequest.getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(request, request.length, host, DISCOVERY_PORT));

            byte[] replyBuffer = new byte[256];
            DatagramPacket reply = new DatagramPacket(replyBuffer, replyBuffer.length);
            udp.receive(reply);
            String response = new String(reply.getData(), reply.getOffset(), reply.getLength(),
                    StandardCharsets.UTF_8);
            assertTrue("Unexpected discovery response: " + response,
                    response.equals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY)
                            || response.startsWith(NetMsg.MESSAGE_PREFIX
                            + NetMsg.NETMSG_SERVERREPLY_ASSIGNMENT_PREFIX));
        }

        try (Socket tcp = new Socket()) {
            tcp.connect(new InetSocketAddress(host, TCP_PORT), SOCKET_TIMEOUT_MS);
            assertTrue("The host did not establish the TCP lobby connection", tcp.isConnected());
        }
    }
}
