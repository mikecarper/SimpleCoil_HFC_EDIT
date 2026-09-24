package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class CombatPacketTest {
    @Test
    public void targetedEventAndAckRoundTripInThirtyTwoBytes() {
        UUID token = UUID.randomUUID();
        byte[] event = CombatPacket.encode(NetMsg.NETWORK_VERSION_NUMBER, token,
                CombatPacket.KIND_EVENT, PeerStatePacket.EVENT_HIT, 32, 1, 0xffffffffL);
        assertEquals(32, event.length);
        CombatPacket.Decoded decoded = CombatPacket.decode(event, 0, event.length);
        assertNotNull(decoded);
        assertEquals(CombatPacket.KIND_EVENT, decoded.kind);
        assertEquals(32, decoded.senderID);
        assertEquals(1, decoded.targetID);
        assertEquals(0xffffffffL, decoded.eventSequence);
        assertTrue(decoded.matchesRound(token));

        byte[] ack = CombatPacket.encode(NetMsg.NETWORK_VERSION_NUMBER, token,
                CombatPacket.KIND_ACK, decoded.eventType, 1, 32, decoded.eventSequence);
        CombatPacket.Decoded decodedAck = CombatPacket.decode(ack, 0, ack.length);
        assertNotNull(decodedAck);
        assertEquals(CombatPacket.KIND_ACK, decodedAck.kind);
        assertEquals(1, decodedAck.senderID);
        assertEquals(32, decodedAck.targetID);
    }

    @Test
    public void shotFiredIsUntargetedAndMalformedPacketsAreRejected() {
        byte[] shot = CombatPacket.encode(NetMsg.NETWORK_VERSION_NUMBER, UUID.randomUUID(),
                CombatPacket.KIND_EVENT, PeerStatePacket.EVENT_SHOT_FIRED, 7, 19, 4);
        CombatPacket.Decoded decoded = CombatPacket.decode(shot, 0, shot.length);
        assertNotNull(decoded);
        assertEquals(0, decoded.targetID);
        assertNull(CombatPacket.decode(shot, 0, shot.length - 1));
        shot[10] = 1;
        assertNull(CombatPacket.decode(shot, 0, shot.length));
    }
}
