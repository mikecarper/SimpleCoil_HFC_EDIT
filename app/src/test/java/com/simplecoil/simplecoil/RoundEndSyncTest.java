package com.simplecoil.simplecoil;

import org.junit.Test;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class RoundEndSyncTest {
    private static final String TOKEN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NEXT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private Map<InetAddress, Integer> players(int count) throws Exception {
        Map<InetAddress, Integer> players = new HashMap<>();
        for (int id = 1; id <= count; id++) players.put(InetAddress.getByName("127.0.0." + id), id);
        return players;
    }

    @Test public void thirtyTwoPlayersConvergeDespiteLostEndsAndAcknowledgements() throws Exception {
        RoundEndSync sync = new RoundEndSync();
        Map<InetAddress, Integer> roster = players(32);
        sync.remember(TOKEN, 3, roster, null, 0, true);
        int[] attempts = new int[33];
        boolean[] ended = new boolean[33];
        for (long now = 0; now < 2000; now += 100) {
            for (RoundEndSync.Delivery delivery : sync.due(now)) {
                int id = roster.get(delivery.destination);
                if (++attempts[id] <= id % 3) continue; // Lose zero, one, or two commits.
                RoundEndSync.Packet packet = RoundEndSync.parse(delivery.message);
                assertNotNull(packet);
                assertEquals(TOKEN, packet.token);
                assertEquals(3, packet.votes);
                if (!ended[id]) { ended[id] = true; continue; } // Lose first ACK too.
                sync.acknowledge(TOKEN, delivery.destination, now);
            }
        }
        for (int id = 1; id <= 32; id++) {
            assertTrue("Player " + id + " missed the end", ended[id]);
            assertEquals(id % 3 + 2, attempts[id]);
        }
        assertFalse(sync.retrying(2000));
    }

    @Test public void acknowledgementsAreEndpointAndRoundScoped() throws Exception {
        RoundEndSync sync = new RoundEndSync();
        Map<InetAddress, Integer> roster = players(2);
        InetAddress local = InetAddress.getByName("127.0.0.1");
        InetAddress remote = InetAddress.getByName("127.0.0.2");
        sync.remember(TOKEN, 3, roster, local, 0, true);
        assertEquals(1, sync.due(0).size());
        sync.acknowledge(NEXT, remote, 1);
        sync.acknowledge(TOKEN, local, 1);
        assertEquals(1, sync.due(100).size());
        sync.acknowledge(TOKEN, remote, 101);
        assertTrue(sync.due(200).isEmpty());
        assertFalse(sync.remember(TOKEN, 3, roster, local, 201, true));
        assertTrue(sync.due(1000).isEmpty());
    }

    @Test public void retriesStopButReturningOldPlayerStillGetsRepair() throws Exception {
        RoundEndSync sync = new RoundEndSync();
        InetAddress remote = InetAddress.getByName("127.0.0.2");
        sync.remember(TOKEN, 3, players(2), null, 0, true);
        assertTrue(sync.due(RoundEndSync.RETRY_WINDOW_MS).isEmpty());
        assertFalse(sync.retrying(RoundEndSync.RETRY_WINDOW_MS));
        sync.remember(NEXT, 3, players(2), null, 31_000, false);
        assertNotNull(sync.repair(TOKEN, remote, 32_000));
        assertNull(sync.repair(TOKEN, remote, 32_001));
        assertNotNull(sync.repair(TOKEN, remote, 33_000));
        assertNull(sync.repair(TOKEN, InetAddress.getByName("127.0.0.99"), 34_000));
        assertNull(sync.repair(TOKEN, remote, RoundEndSync.RETAIN_MS));
    }

    @Test public void malformedAndIncompatiblePacketsAreRejected() {
        for (String message : new String[]{"", "ROUNDEND:", "ROUNDEND:0:" + TOKEN + ":3",
                "ROUNDEND:" + NetMsg.NETWORK_VERSION + ":bad:3",
                "ROUNDEND:" + NetMsg.NETWORK_VERSION + ":" + TOKEN + ":100000000",
                "ROUNDEND:" + NetMsg.NETWORK_VERSION + ":" + TOKEN + ":-1"})
            assertNull(message, RoundEndSync.parse(message));
        assertTrue(RoundEndSync.parse(RoundEndSync.ack(TOKEN)).acknowledgement);
    }

    @Test public void completedRoundHistoryIsBounded() throws Exception {
        RoundEndSync sync = new RoundEndSync();
        sync.remember(TOKEN, 3, players(2), null, 0, false);
        for (int index = 0; index < RoundEndSync.MAX_ROUNDS; index++)
            sync.remember(UUID.randomUUID().toString(), 3, players(2), null, 1, false);
        assertFalse(sync.contains(TOKEN, 1));
    }
}
