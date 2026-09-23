/*
 * Copyright (C) 2026 SimpleCoil contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.simplecoil.simplecoil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Fixed-size, allocation-light wire format used while a peer game is running.
 * Lobby data such as names and IP addresses is deliberately kept in TCP setup.
 *
 * <p>The payload is 1,312 bytes: a 32-byte round header followed by thirty-two
 * 40-byte player records. That leaves 160 bytes below the 1,472-byte UDP
 * payload ceiling while allowing every packet to carry a complete state view.</p>
 */
final class PeerStatePacket {
    static final int MAX_UDP_PAYLOAD_BYTES = 1472;
    static final int PLAYER_CAPACITY = 32;
    static final int HEADER_BYTES = 32;
    static final int PLAYER_BYTES = 40;
    static final int PACKET_BYTES = HEADER_BYTES + PLAYER_CAPACITY * PLAYER_BYTES;

    static final int FLAG_PRESENT = 1;
    static final int FLAG_LEFT = 1 << 1;
    static final int FLAG_GPS_VALID = 1 << 2;

    static final int EVENT_NONE = 0;
    static final int EVENT_SHOT_FIRED = 1;
    static final int EVENT_HIT = 2;
    static final int EVENT_OUT = 3;
    static final int EVENT_ALREADY_DEAD = 4;
    static final int EVENT_ELIMINATED = 5;
    static final int EVENT_LEAVE = 6;
    static final int EVENT_END_GAME = 7;

    private static final int MAGIC = 0x53434F49; // "SCOI"
    private static final int FORMAT_VERSION = 1;
    private static final double GPS_SCALE = 100_000.0;

    static {
        if (PLAYER_CAPACITY != Globals.MAX_PLAYER_ID)
            throw new AssertionError("Peer packet capacity and player limit differ");
        if (PACKET_BYTES > MAX_UDP_PAYLOAD_BYTES)
            throw new AssertionError("Peer state packet exceeds the UDP payload ceiling");
    }

    private PeerStatePacket() { }

    static byte[] encode(int networkVersion, UUID roundToken, long snapshotSequence,
                         int senderID, int gameMode, PlayerState[] players) {
        if (roundToken == null || senderID < 1 || senderID > PLAYER_CAPACITY
                || snapshotSequence <= 0)
            throw new IllegalArgumentException("Invalid peer snapshot header");

        ByteBuffer buffer = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put((byte) FORMAT_VERSION);
        buffer.put((byte) networkVersion);
        buffer.put((byte) senderID);
        buffer.put((byte) gameMode);
        buffer.putLong(snapshotSequence);
        buffer.putLong(roundToken.getMostSignificantBits());
        buffer.putLong(roundToken.getLeastSignificantBits());

        for (int playerID = 1; playerID <= PLAYER_CAPACITY; playerID++) {
            PlayerState state = players != null && playerID < players.length
                    ? players[playerID] : null;
            int flags = state == null ? 0 : state.flags | FLAG_PRESENT;
            buffer.put((byte) flags);
            buffer.put((byte) playerID);
            buffer.put((byte) (state == null ? 0 : state.gameState));
            buffer.put((byte) (state == null ? 0 : state.grenadeID));
            buffer.put((byte) (state == null ? EVENT_NONE : state.eventType));
            buffer.put((byte) (state == null ? 0 : state.eventTargetID));
            buffer.putShort((short) 0);
            buffer.putInt((int) (state == null ? 0 : state.ownerSequence));
            buffer.putInt((int) (state == null ? 0 : state.eventSequence));
            buffer.putInt(state == null ? 0 : state.score);
            buffer.putInt(state == null ? 0 : state.deaths);
            buffer.putShort((short) clampUnsignedShort(state == null ? 0 : state.health));
            buffer.putShort((short) clampUnsignedShort(state == null ? 0 : state.shield));
            buffer.putShort((short) clampUnsignedShort(state == null ? 0 : state.shotsRemaining));
            buffer.putShort((short) clampUnsignedShort(state == null ? 0 : state.gpsAgeSeconds));
            buffer.putInt(state == null ? 0 : encodeCoordinate(state.latitude));
            buffer.putInt(state == null ? 0 : encodeCoordinate(state.longitude));
        }
        return buffer.array();
    }

    static Decoded decode(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || length != PACKET_BYTES
                || offset > payload.length - length)
            return null;
        ByteBuffer buffer = ByteBuffer.wrap(payload, offset, length).slice().order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || unsigned(buffer.get()) != FORMAT_VERSION)
            return null;
        int networkVersion = unsigned(buffer.get());
        int senderID = unsigned(buffer.get());
        int gameMode = unsigned(buffer.get());
        long snapshotSequence = buffer.getLong();
        UUID roundToken = new UUID(buffer.getLong(), buffer.getLong());
        if (senderID < 1 || senderID > PLAYER_CAPACITY || snapshotSequence <= 0
                || !Globals.isValidGameMode(gameMode))
            return null;

        PlayerState[] players = new PlayerState[PLAYER_CAPACITY + 1];
        for (int slot = 1; slot <= PLAYER_CAPACITY; slot++) {
            int flags = unsigned(buffer.get());
            int playerID = unsigned(buffer.get());
            int gameState = unsigned(buffer.get());
            int grenadeID = unsigned(buffer.get());
            int eventType = unsigned(buffer.get());
            int eventTargetID = unsigned(buffer.get());
            buffer.getShort();
            long ownerSequence = unsigned(buffer.getInt());
            long eventSequence = unsigned(buffer.getInt());
            int score = buffer.getInt();
            int deaths = buffer.getInt();
            int health = unsigned(buffer.getShort());
            int shield = unsigned(buffer.getShort());
            int shotsRemaining = unsigned(buffer.getShort());
            int gpsAgeSeconds = unsigned(buffer.getShort());
            double latitude = decodeCoordinate(buffer.getInt());
            double longitude = decodeCoordinate(buffer.getInt());

            if (playerID != slot)
                return null;
            if ((flags & FLAG_PRESENT) == 0)
                continue;
            if ((flags & ~(FLAG_PRESENT | FLAG_LEFT | FLAG_GPS_VALID)) != 0
                    || ownerSequence <= 0 || eventType > EVENT_END_GAME
                    || eventTargetID > PLAYER_CAPACITY || gameState > Globals.GAME_STATE_ELIMINATED
                    || !Globals.isValidGrenadeID(grenadeID) || score < 0
                    || score > Globals.MAX_SCOREBOARD_VALUE || deaths < 0
                    || deaths > Globals.MAX_SCOREBOARD_VALUE)
                return null;
            if ((flags & FLAG_GPS_VALID) != 0
                    && !Globals.isValidCoordinates(longitude, latitude))
                return null;
            players[playerID] = new PlayerState(playerID, flags, ownerSequence, eventSequence,
                    eventType, eventTargetID, gameState, grenadeID, score, deaths, health, shield,
                    shotsRemaining, gpsAgeSeconds, latitude, longitude);
        }
        return new Decoded(networkVersion, roundToken, snapshotSequence, senderID, gameMode, players);
    }

    static boolean looksLikePeerState(byte[] payload, int offset, int length) {
        if (payload == null || length < 4 || offset < 0 || offset > payload.length - length)
            return false;
        return ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt() == MAGIC;
    }

    static int encodeCoordinate(double coordinate) {
        if (Double.isNaN(coordinate) || Double.isInfinite(coordinate))
            return 0;
        return (int) Math.round(coordinate * GPS_SCALE);
    }

    static double decodeCoordinate(int coordinate) {
        return coordinate / GPS_SCALE;
    }

    private static int clampUnsignedShort(int value) {
        return Math.max(0, Math.min(0xffff, value));
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int unsigned(short value) {
        return value & 0xffff;
    }

    private static long unsigned(int value) {
        return value & 0xffffffffL;
    }

    static final class Decoded {
        final int networkVersion;
        final UUID roundToken;
        final long snapshotSequence;
        final int senderID;
        final int gameMode;
        final PlayerState[] players;

        Decoded(int networkVersion, UUID roundToken, long snapshotSequence, int senderID,
                int gameMode, PlayerState[] players) {
            this.networkVersion = networkVersion;
            this.roundToken = roundToken;
            this.snapshotSequence = snapshotSequence;
            this.senderID = senderID;
            this.gameMode = gameMode;
            this.players = players;
        }
    }

    static final class PlayerState {
        final int playerID;
        final int flags;
        final long ownerSequence;
        final long eventSequence;
        final int eventType;
        final int eventTargetID;
        final int gameState;
        final int grenadeID;
        final int score;
        final int deaths;
        final int health;
        final int shield;
        final int shotsRemaining;
        final int gpsAgeSeconds;
        final double latitude;
        final double longitude;

        PlayerState(int playerID, int flags, long ownerSequence, long eventSequence, int eventType,
                    int eventTargetID, int gameState, int grenadeID, int score, int deaths,
                    int health, int shield, int shotsRemaining, int gpsAgeSeconds,
                    double latitude, double longitude) {
            this.playerID = playerID;
            this.flags = flags;
            this.ownerSequence = ownerSequence;
            this.eventSequence = eventSequence;
            this.eventType = eventType;
            this.eventTargetID = eventTargetID;
            this.gameState = gameState;
            this.grenadeID = grenadeID;
            this.score = score;
            this.deaths = deaths;
            this.health = health;
            this.shield = shield;
            this.shotsRemaining = shotsRemaining;
            this.gpsAgeSeconds = gpsAgeSeconds;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        PlayerState withOwnerSequence(long sequence) {
            return new PlayerState(playerID, flags, sequence, eventSequence, eventType,
                    eventTargetID, gameState, grenadeID, score, deaths, health, shield,
                    shotsRemaining, gpsAgeSeconds, latitude, longitude);
        }
    }
}
