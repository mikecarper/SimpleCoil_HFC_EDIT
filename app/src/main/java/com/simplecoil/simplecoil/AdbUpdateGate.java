package com.simplecoil.simplecoil;

import java.util.function.LongSupplier;

/** Main-thread maintenance lease; an idle check alone cannot reserve the phone. */
final class AdbUpdateGate {
    static final long LEASE_MILLIS = 180_000;
    private final LongSupplier clock;
    private String nonce, host;
    private long expiresAt;

    AdbUpdateGate(LongSupplier clock) { this.clock = clock; }

    String prepare(String requestedNonce, String requestedHost, boolean eligible) {
        if (!valid(requestedNonce, requestedHost)) return "invalid";
        if (held()) {
            if (!matches(requestedNonce, requestedHost) || !eligible) return "busy";
            return "ready";
        }
        if (!eligible) return "busy";
        nonce = requestedNonce;
        host = requestedHost;
        expiresAt = clock.getAsLong() + LEASE_MILLIS;
        return "ready";
    }

    String status(String requestedNonce, String requestedHost, boolean eligible) {
        if (!valid(requestedNonce, requestedHost)) return "invalid";
        if (matches(requestedNonce, requestedHost) && !held()) return "expired";
        if (!held()) return "absent";
        return matches(requestedNonce, requestedHost) && eligible ? "ready" : "busy";
    }

    String release(String requestedNonce, String requestedHost) {
        if (!valid(requestedNonce, requestedHost)) return "invalid";
        if (!matches(requestedNonce, requestedHost)) return held() ? "busy" : "absent";
        nonce = host = null;
        expiresAt = 0;
        return "released";
    }

    boolean held() { return nonce != null && clock.getAsLong() < expiresAt; }

    long remaining() { return held() ? Math.max(0, expiresAt - clock.getAsLong()) : 0; }

    private boolean matches(String requestedNonce, String requestedHost) {
        return nonce != null && nonce.equals(requestedNonce) && host.equals(requestedHost);
    }

    static boolean valid(String nonce, String host) {
        if (nonce == null || !nonce.matches("[0-9a-f]{32}") || host == null || host.length() > 64)
            return false;
        // No hostnames: the activity also compares this to its actual numeric TCP peer.
        if (host.indexOf(':') >= 0) return host.matches("[0-9a-fA-F:.]+(%[0-9]+)?");
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
