package com.simplecoil.simplecoil;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Small, expiring directory for one lobby on the local Wi-Fi network. */
final class SharedLobby {
    static final String QUERY = "LOBBYQUERY:";
    static final String BEACON = "LOBBYSTATE:";
    static final long EXPIRY_MS = 7_000;
    static final long DISCOVERY_MS = 3_000;

    static final class Host {
        final InetAddress address;
        final boolean dedicated;
        final boolean playing;
        final long seenAt;

        Host(InetAddress address, boolean dedicated, boolean playing, long seenAt) {
            this.address = address;
            this.dedicated = dedicated;
            this.playing = playing;
            this.seenAt = seenAt;
        }
    }

    private final Map<InetAddress, Host> hosts = new HashMap<>();

    static String announcement(boolean dedicated, boolean playing) {
        return BEACON + NetMsg.NETWORK_VERSION + ":" + (dedicated ? "D" : "P")
                + ":" + (playing ? "PLAY" : "OPEN");
    }

    static Host parse(InetAddress source, String message, long now) {
        if (source == null || source.getAddress().length != 4 || source.isAnyLocalAddress()
                || source.isMulticastAddress() || message == null)
            return null;
        String[] fields = message.split(":", -1);
        if (fields.length != 4 || !"LOBBYSTATE".equals(fields[0])
                || !NetMsg.NETWORK_VERSION.equals(fields[1])
                || !("D".equals(fields[2]) || "P".equals(fields[2]))
                || !("OPEN".equals(fields[3]) || "PLAY".equals(fields[3])))
            return null;
        return new Host(source, "D".equals(fields[2]), "PLAY".equals(fields[3]), now);
    }

    void observe(Host host) {
        if (host != null)
            hosts.put(host.address, host);
    }

    void clear() { hosts.clear(); }

    Host best(long now) {
        Host best = null;
        Iterator<Host> iterator = hosts.values().iterator();
        while (iterator.hasNext()) {
            Host host = iterator.next();
            if (now - host.seenAt >= EXPIRY_MS) {
                iterator.remove();
                continue;
            }
            if (best == null || preferred(host, best))
                best = host;
        }
        return best;
    }

    /** Every phone makes the same choice, even when two hosts start simultaneously. */
    static boolean preferred(Host candidate, Host current) {
        if (candidate.playing != current.playing)
            return candidate.playing;
        if (candidate.dedicated != current.dedicated)
            return candidate.dedicated;
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
