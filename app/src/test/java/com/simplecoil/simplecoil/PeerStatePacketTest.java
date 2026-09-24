package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class PeerStatePacketTest {
    @Test
    public void completeThirtyTwoPlayerSnapshotFitsPayloadCeiling() {
        PeerStatePacket.PlayerState[] players = new PeerStatePacket.PlayerState[33];
        for (int id = 1; id <= 32; id++) {
            players[id] = new PeerStatePacket.PlayerState(id,
                    PeerStatePacket.FLAG_PRESENT | PeerStatePacket.FLAG_GPS_VALID,
                    id, id + 100L, PeerStatePacket.EVENT_HIT, id == 32 ? 1 : id + 1,
                    Globals.GAME_STATE_RUNNING, id % Globals.MAX_GRENADE_IDS,
                    10_000 + id, 500 + id, 1000, 250, 255, 30,
                    37.77491 + id / 100_000.0, -122.41942 - id / 100_000.0);
        }

        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 9, 32,
                Globals.GAME_MODE_FFA, players);

        assertEquals(1312, encoded.length);
        assertTrue(encoded.length <= PeerStatePacket.MAX_UDP_PAYLOAD_BYTES);
        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertEquals(32, decoded.senderID);
        assertEquals(32, decoded.players[32].playerID);
        assertEquals(37.77523, decoded.players[32].latitude, 0.0000051);
        assertEquals(-122.41974, decoded.players[32].longitude, 0.0000051);
        assertEquals(255, decoded.players[32].shotsRemaining);
    }

    @Test
    public void rejectsOversizedTruncatedAndCorruptPackets() {
        PeerStatePacket.PlayerState[] players = new PeerStatePacket.PlayerState[33];
        players[1] = new PeerStatePacket.PlayerState(1, PeerStatePacket.FLAG_PRESENT,
                1, 0, PeerStatePacket.EVENT_NONE, 0, Globals.GAME_STATE_RUNNING,
                0, 0, 0, 20, 5, 30, 0, 0, 0);
        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 1, 1,
                Globals.GAME_MODE_FFA, players);

        assertNull(PeerStatePacket.decode(encoded, 0, encoded.length - 1));
        encoded[0] = 0;
        assertNull(PeerStatePacket.decode(encoded, 0, encoded.length));
    }

    @Test
    public void authorityTickUsesReservedSenderZero() {
        PeerStatePacket.PlayerState[] players = new PeerStatePacket.PlayerState[33];
        players[7] = new PeerStatePacket.PlayerState(7, PeerStatePacket.FLAG_PRESENT,
                12, 0, PeerStatePacket.EVENT_NONE, 0, Globals.GAME_STATE_RUNNING,
                0, 2, 1, 15, 4, 29, 0, 0, 0);

        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 4, 0, Globals.GAME_MODE_2TEAMS, players);
        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);

        assertNotNull(decoded);
        assertEquals(0, decoded.senderID);
        assertEquals(12, decoded.players[7].ownerSequence);
    }
}
