package com.simplecoil.simplecoil;

import org.junit.Test;
import com.simplecoil.protocol.GpsOffset;

import java.util.UUID;
import java.nio.ByteBuffer;

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
                    37.77491 + id / 100_000.0, -122.41942 - id / 100_000.0,
                    id == 32 ? 1 : 0, id == 32 ? 7 : 0).withGeneration(id, 255);
        }

        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 9, 32,
                Globals.GAME_MODE_FFA, players, GpsOffset.create(37.77491, -122.41942));

        assertEquals(1264, encoded.length);
        assertTrue(encoded.length <= PeerStatePacket.MAX_UDP_PAYLOAD_BYTES);
        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertEquals(32, decoded.senderID);
        assertEquals(32, decoded.players[32].playerID);
        assertEquals(37.77523, decoded.players[32].latitude, 0.0000002);
        assertEquals(-122.41974, decoded.players[32].longitude, 0.0000002);
        assertEquals(255, decoded.players[32].shotsRemaining);
        assertEquals(1, decoded.players[32].carriedFlagTeam);
        assertEquals(7, decoded.players[32].ctfCaptures);
        assertEquals(32, decoded.players[32].generation);
        assertEquals(255, decoded.players[32].targetGeneration);
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

    @Test
    public void infectionFlagRoundTripsWithoutGrowingPacket() {
        PeerStatePacket.PlayerState[] players = new PeerStatePacket.PlayerState[33];
        players[2] = new PeerStatePacket.PlayerState(2,
                PeerStatePacket.FLAG_PRESENT | PeerStatePacket.FLAG_INFECTED,
                3, 0, PeerStatePacket.EVENT_NONE, 0, Globals.GAME_STATE_RUNNING,
                0, 0, 0, 5, 10, 30, 0, 0, 0);
        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 1, 2, Globals.GAME_MODE_2TEAMS, players);
        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertEquals(PeerStatePacket.FLAG_PRESENT | PeerStatePacket.FLAG_INFECTED,
                decoded.players[2].flags);
        assertEquals(1264, encoded.length);
    }

    @Test public void zeroOffsetsKeepAValidFixAndPacketsDecodeWithoutPreviousUpdates() {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        double[] location = origin.decode(-30001, 31002);
        PeerStatePacket.PlayerState[] players = gpsPlayer(location[0], location[1]);
        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 200, 1, Globals.GAME_MODE_FFA, players, origin);
        ByteBuffer bytes = ByteBuffer.wrap(encoded);
        assertEquals(-30001, bytes.getShort(PeerStatePacket.HEADER_BYTES + 32));
        assertEquals(31002, bytes.getShort(PeerStatePacket.HEADER_BYTES + 34));
        PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertTrue(origin.sameOrigin(decoded.origin));
        assertEquals(location[0], decoded.players[1].latitude, 0.00000001);
        assertEquals(location[1], decoded.players[1].longitude, 0.00000001);

        players = gpsPlayer(origin.latitude, origin.longitude);
        encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 1, 1, Globals.GAME_MODE_FFA, players, origin);
        decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertTrue((decoded.players[1].flags & PeerStatePacket.FLAG_GPS_VALID) != 0);
        assertEquals(origin.latitude, decoded.players[1].latitude, 0);
        assertEquals(origin.longitude, decoded.players[1].longitude, 0);
    }

    @Test public void missingInvalidAndOutsideFixesDoNotCorruptCombatState() {
        GpsOffset origin = GpsOffset.create(47.6, -122.1);
        for (double[] location : new double[][]{{0, 0}, {47.7, -122.1}, {Double.NaN, -122.1}}) {
            byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                    UUID.randomUUID(), 1, 1, Globals.GAME_MODE_FFA,
                    gpsPlayer(location[0], location[1]), origin);
            PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
            assertNotNull(decoded);
            assertEquals(17, decoded.players[1].score);
            assertEquals(0, decoded.players[1].flags & PeerStatePacket.FLAG_GPS_VALID);
            assertTrue(Double.isNaN(decoded.players[1].latitude));
        }
        byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                UUID.randomUUID(), 1, 1, Globals.GAME_MODE_FFA, gpsPlayer(47.6, -122.1));
        assertNotNull(PeerStatePacket.decode(encoded, 0, encoded.length));
        // A malformed Null Island origin and the old format are rejected.
        ByteBuffer.wrap(encoded).putDouble(32, 0).putDouble(40, 0);
        assertNull(PeerStatePacket.decode(encoded, 0, encoded.length));
        encoded[4] = 3;
        assertNull(PeerStatePacket.decode(encoded, 0, encoded.length));
    }

    @Test public void snapshotsPreserveValidGpsOnTheEquatorAndPrimeMeridian() {
        for (double[] coordinates : new double[][]{{0, 20}, {50, 0}}) {
            GpsOffset origin = GpsOffset.create(coordinates[0], coordinates[1]);
            byte[] encoded = PeerStatePacket.encode(NetMsg.NETWORK_VERSION_NUMBER,
                    UUID.randomUUID(), 1, 1, Globals.GAME_MODE_FFA,
                    gpsPlayer(coordinates[0], coordinates[1]), origin);
            PeerStatePacket.Decoded decoded = PeerStatePacket.decode(encoded, 0, encoded.length);
            assertNotNull(decoded);
            assertTrue(origin.sameOrigin(decoded.origin));
            assertTrue((decoded.players[1].flags & PeerStatePacket.FLAG_GPS_VALID) != 0);
            assertEquals(coordinates[0], decoded.players[1].latitude, 0);
            assertEquals(coordinates[1], decoded.players[1].longitude, 0);
        }
    }

    private static PeerStatePacket.PlayerState[] gpsPlayer(double latitude, double longitude) {
        PeerStatePacket.PlayerState[] players = new PeerStatePacket.PlayerState[33];
        players[1] = new PeerStatePacket.PlayerState(1,
                PeerStatePacket.FLAG_PRESENT | PeerStatePacket.FLAG_GPS_VALID,
                1, 0, PeerStatePacket.EVENT_NONE, 0, Globals.GAME_STATE_RUNNING,
                0, 17, 0, 5, 10, 30, 0, latitude, longitude);
        return players;
    }
}
