/*
 * Copyright (C) 2026 SimpleCoil contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.simplecoil.simplecoil;

import java.util.UUID;

/**
 * Small fixed-size combat event used on the latency-sensitive broadcast path.
 * Every phone may observe an event, while only its addressed target acts and
 * returns an acknowledgement. Manual byte access avoids a ByteBuffer object in
 * the hottest UDP parser on the original Android 5.1 hardware.
 */
final class CombatPacket {
    static final int PACKET_BYTES = 32;
    static final int KIND_EVENT = 1;
    static final int KIND_ACK = 2;

    private static final int MAGIC = 0x53434f43; // "SCOC"
    private static final int FORMAT_VERSION = 1;

    private CombatPacket() { }

    static byte[] encode(int networkVersion, UUID roundToken, int kind, int eventType,
                         int senderID, int targetID, long eventSequence) {
        if (networkVersion < 0 || networkVersion > 0xff || roundToken == null
                || (kind != KIND_EVENT && kind != KIND_ACK)
                || !isCombatEvent(eventType)
                || !Globals.isValidPlayerID(senderID)
                || targetID < 0 || targetID > Globals.MAX_PLAYER_ID
                || eventSequence <= 0 || eventSequence > 0xffffffffL)
            throw new IllegalArgumentException("Invalid combat packet");
        if (kind == KIND_EVENT && eventType == PeerStatePacket.EVENT_SHOT_FIRED)
            targetID = 0;
        else if (targetID == 0)
            throw new IllegalArgumentException("Targeted combat packet has no target");

        byte[] packet = new byte[PACKET_BYTES];
        putInt(packet, 0, MAGIC);
        packet[4] = (byte) FORMAT_VERSION;
        packet[5] = (byte) networkVersion;
        packet[6] = (byte) kind;
        packet[7] = (byte) eventType;
        packet[8] = (byte) senderID;
        packet[9] = (byte) targetID;
        putInt(packet, 12, (int) eventSequence);
        putLong(packet, 16, roundToken.getMostSignificantBits());
        putLong(packet, 24, roundToken.getLeastSignificantBits());
        return packet;
    }

    static Decoded decode(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || length != PACKET_BYTES
                || offset > payload.length - length || getInt(payload, offset) != MAGIC
                || unsigned(payload[offset + 4]) != FORMAT_VERSION
                || payload[offset + 10] != 0 || payload[offset + 11] != 0)
            return null;
        int networkVersion = unsigned(payload[offset + 5]);
        int kind = unsigned(payload[offset + 6]);
        int eventType = unsigned(payload[offset + 7]);
        int senderID = unsigned(payload[offset + 8]);
        int targetID = unsigned(payload[offset + 9]);
        long sequence = getInt(payload, offset + 12) & 0xffffffffL;
        if ((kind != KIND_EVENT && kind != KIND_ACK) || !isCombatEvent(eventType)
                || !Globals.isValidPlayerID(senderID) || sequence <= 0)
            return null;
        if (kind == KIND_EVENT && eventType == PeerStatePacket.EVENT_SHOT_FIRED) {
            if (targetID != 0)
                return null;
        } else if (!Globals.isValidPlayerID(targetID)) {
            return null;
        }
        return new Decoded(networkVersion, kind, eventType, senderID, targetID, sequence,
                getLong(payload, offset + 16), getLong(payload, offset + 24));
    }

    static boolean looksLikeCombat(byte[] payload, int offset, int length) {
        return payload != null && offset >= 0 && length >= 4
                && offset <= payload.length - length && getInt(payload, offset) == MAGIC;
    }

    static boolean isCombatEvent(int eventType) {
        return eventType >= PeerStatePacket.EVENT_SHOT_FIRED
                && eventType <= PeerStatePacket.EVENT_ELIMINATED;
    }

    private static int unsigned(byte value) { return value & 0xff; }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int getInt(byte[] source, int offset) {
        return unsigned(source[offset]) << 24 | unsigned(source[offset + 1]) << 16
                | unsigned(source[offset + 2]) << 8 | unsigned(source[offset + 3]);
    }

    private static void putLong(byte[] target, int offset, long value) {
        putInt(target, offset, (int) (value >>> 32));
        putInt(target, offset + 4, (int) value);
    }

    private static long getLong(byte[] source, int offset) {
        return (getInt(source, offset) & 0xffffffffL) << 32
                | getInt(source, offset + 4) & 0xffffffffL;
    }

    static final class Decoded {
        final int networkVersion;
        final int kind;
        final int eventType;
        final int senderID;
        final int targetID;
        final long eventSequence;
        final long roundTokenMost;
        final long roundTokenLeast;

        Decoded(int networkVersion, int kind, int eventType, int senderID, int targetID,
                long eventSequence, long roundTokenMost, long roundTokenLeast) {
            this.networkVersion = networkVersion;
            this.kind = kind;
            this.eventType = eventType;
            this.senderID = senderID;
            this.targetID = targetID;
            this.eventSequence = eventSequence;
            this.roundTokenMost = roundTokenMost;
            this.roundTokenLeast = roundTokenLeast;
        }

        boolean matchesRound(UUID token) {
            return token != null && token.getMostSignificantBits() == roundTokenMost
                    && token.getLeastSignificantBits() == roundTokenLeast;
        }
    }
}
