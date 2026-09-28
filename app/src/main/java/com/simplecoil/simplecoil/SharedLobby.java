package com.simplecoil.simplecoil;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Small, expiring directory for one lobby on the local Wi-Fi network. */
final class SharedLobby {
    static final String QUERY = "LOBBYQUERY:";
    static final String BEACON = "LOBBYSTATE:";
    static final long EXPIRY_MS = 15_000;
    static final long DISCOVERY_MS = 3_000;
    static final long AGE_TIE_MS = 2_000;
    static final long MAX_HOST_AGE_MS = 365L * 24 * 60 * 60 * 1000;
    static final long CONNECTION_TIMEOUT_MS = 15_000;
    static final long FAILED_HOST_BACKOFF_MS = 30_000;

    enum Kind {
        AUTO_PHONE("P", 0, false), MANUAL_PHONE("M", 1, false),
        DEDICATED_PHONE("D", 1, true), LAPTOP("L", 2, true);

        final String wire;
        final int priority;
        final boolean dedicated;

        Kind(String wire, int priority, boolean dedicated) {
            this.wire = wire;
            this.priority = priority;
            this.dedicated = dedicated;
        }

        static Kind parse(String wire) {
            for (Kind kind : values()) if (kind.wire.equals(wire)) return kind;
            return null;
        }
    }

    static final class Host {
        final InetAddress address;
        final boolean dedicated;
        final Kind kind;
        final boolean playing;
        final long seenAt;
        final long startedAt;

        Host(InetAddress address, boolean dedicated, boolean playing, long seenAt) {
            this(address, dedicated, playing, seenAt, 0);
        }

        Host(InetAddress address, boolean dedicated, boolean playing, long seenAt, long age) {
            this(address, dedicated ? Kind.DEDICATED_PHONE : Kind.AUTO_PHONE, playing, seenAt, age);
        }

        Host(InetAddress address, Kind kind, boolean playing, long seenAt, long age) {
            this.address = address;
            this.kind = kind;
            this.dedicated = kind.dedicated;
            this.playing = playing;
            this.seenAt = seenAt;
            this.startedAt = seenAt - Math.max(0, Math.min(MAX_HOST_AGE_MS, age));
        }
    }

    private final Map<InetAddress, Host> hosts = new HashMap<>();
    private final Map<InetAddress, Long> failedUntil = new HashMap<>();

    static String announcement(boolean dedicated, boolean playing) {
        return announcement(dedicated, playing, 0);
    }

    static String announcement(boolean dedicated, boolean playing, long age) {
        return announcement(dedicated ? Kind.DEDICATED_PHONE : Kind.AUTO_PHONE, playing, age);
    }

    static String announcement(Kind kind, boolean playing, long age) {
        return BEACON + NetMsg.NETWORK_VERSION + ":" + kind.wire
                + ":" + (playing ? "PLAY" : "OPEN") + ":"
                + Math.max(0, Math.min(MAX_HOST_AGE_MS, age));
    }

    static Host parse(InetAddress source, String message, long now) {
        if (source == null || source.getAddress().length != 4 || source.isAnyLocalAddress()
                || source.isMulticastAddress() || message == null)
            return null;
        String[] fields = message.split(":", -1);
        if (fields.length != 5 || !"LOBBYSTATE".equals(fields[0])
                || !NetMsg.NETWORK_VERSION.equals(fields[1])
                || Kind.parse(fields[2]) == null
                || !("OPEN".equals(fields[3]) || "PLAY".equals(fields[3])))
            return null;
        try {
            long age = Long.parseLong(fields[4]);
            if (age < 0 || age > MAX_HOST_AGE_MS) return null;
            return new Host(source, Kind.parse(fields[2]), "PLAY".equals(fields[3]), now, age);
        } catch (NumberFormatException ignored) { return null; }
    }

    void observe(Host host) {
        if (host != null)
            hosts.put(host.address, host);
    }

    void clear() { hosts.clear(); failedUntil.clear(); }

    void clearHosts() { hosts.clear(); }

    void failed(InetAddress address, long now) {
        if (address == null) return;
        hosts.remove(address);
        failedUntil.put(address, now + FAILED_HOST_BACKOFF_MS);
    }

    boolean isBackedOff(InetAddress address, long now) {
        Long until = failedUntil.get(address);
        if (until == null) return false;
        if (now < until) return true;
        failedUntil.remove(address);
        return false;
    }

    Host get(InetAddress address, long now) {
        Host host = hosts.get(address);
        return host != null && now - host.seenAt < EXPIRY_MS && !isBackedOff(address, now) ? host : null;
    }

    Host laptop(long now) {
        Host best = null;
        for (Host host : hosts.values())
            if (host.kind == Kind.LAPTOP && get(host.address, now) != null
                    && (best == null || preferred(host, best))) best = host;
        return best;
    }

    /** A working TCP connection is stronger evidence than a missed Wi-Fi beacon. */
    void touch(InetAddress address, long now) {
        Host host = hosts.get(address);
        if (host != null) observe(new Host(address, host.kind, host.playing, now, now - host.startedAt));
    }

    /** Give the same host time to reopen its lobby between rounds. */
    void awaitReturn(InetAddress address, long now) {
        Host host = hosts.get(address);
        // Keep a laptop observed during this round. It wins as soon as play ends.
        if (address != null) observe(new Host(address, host == null ? Kind.AUTO_PHONE : host.kind,
                false, now, host == null ? 0 : now - host.startedAt));
    }

    Host best(long now) {
        boolean playing = false;
        int priority = -1;
        long reference = 0;
        Iterator<Host> iterator = hosts.values().iterator();
        while (iterator.hasNext()) {
            Host host = iterator.next();
            if (now - host.seenAt >= EXPIRY_MS) {
                iterator.remove();
                continue;
            }
            if (isBackedOff(host.address, now)) continue;
            if (priority < 0 || host.playing && !playing
                    || host.playing == playing && host.kind.priority > priority) {
                playing = host.playing;
                priority = host.kind.priority;
                reference = host.startedAt;
            } else if (host.playing == playing && host.kind.priority == priority) {
                // A new explicit phone selection replaces an older one. Auto
                // hosts and laptops instead keep the oldest established host.
                reference = priority == 1 ? Math.max(reference, host.startedAt)
                        : Math.min(reference, host.startedAt);
            }
        }
        // Select one age cohort first, then a total tie-break order. Pairwise
        // age tolerances alone are non-transitive with three simultaneous hosts.
        Host best = null;
        for (Host host : hosts.values())
            if (host.playing == playing && host.kind.priority == priority
                    && !isBackedOff(host.address, now) && Math.abs(host.startedAt - reference) <= AGE_TIE_MS
                    && (best == null || tiePreferred(host, best))) best = host;
        return best;
    }

    /** Every phone makes the same choice, even when two hosts start simultaneously. */
    static boolean preferred(Host candidate, Host current) {
        if (candidate.playing != current.playing)
            return candidate.playing;
        if (candidate.kind.priority != current.kind.priority)
            return candidate.kind.priority > current.kind.priority;
        if (Math.abs(candidate.startedAt - current.startedAt) > AGE_TIE_MS)
            return candidate.kind.priority == 1 ? candidate.startedAt > current.startedAt
                    : candidate.startedAt < current.startedAt;
        return tiePreferred(candidate, current);
    }

    private static boolean tiePreferred(Host candidate, Host current) {
        byte[] first = candidate.address.getAddress();
        byte[] second = current.address.getAddress();
        for (int i = 0; i < Math.min(first.length, second.length); i++) {
            int comparison = Integer.compare(first[i] & 255, second[i] & 255);
            if (comparison != 0)
                return comparison < 0;
        }
        return false;
    }
}
