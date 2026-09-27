package com.simplecoil.simplecoil;

/** Immutable lobby presence. Being present does not imply playing this round. */
public final class LobbyPlayer {
    public enum Status { BENCHED, RECONNECTING, NEEDS_GUN, SYNCING, READY }

    public final byte id;
    public final String name;
    public final boolean connected;
    public final boolean gunReady;
    public final boolean clockReady;
    public final boolean benched;
    public final boolean host;

    public LobbyPlayer(byte id, String name, boolean connected, boolean gunReady,
                       boolean clockReady, boolean benched, boolean host) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.connected = connected;
        this.gunReady = gunReady;
        this.clockReady = clockReady;
        this.benched = benched;
        this.host = host;
    }

    public Status status() {
        if (benched) return Status.BENCHED;
        if (!connected) return Status.RECONNECTING;
        if (!gunReady) return Status.NEEDS_GUN;
        if (!clockReady) return Status.SYNCING;
        return Status.READY;
    }

    public boolean ready() { return status() == Status.READY; }
}
