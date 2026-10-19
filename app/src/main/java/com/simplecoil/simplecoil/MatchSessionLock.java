package com.simplecoil.simplecoil;

/** A joined match does not expire when discovery beacons or local game state disappear. */
final class MatchSessionLock {
    private String host;
    private String token;

    MatchSessionLock(String host, String token) {
        this.host = host == null ? "" : host;
        this.token = token == null ? "" : token;
    }

    boolean active() { return !host.isEmpty(); }
    String host() { return host; }
    String token() { return token; }

    boolean allowsHost(String candidate) {
        return !active() || host.equals(candidate);
    }

    boolean join(String candidate, String roundToken) {
        if (candidate == null || candidate.isEmpty() || !allowsHost(candidate)) return false;
        host = candidate;
        if (roundToken != null && !roundToken.isEmpty()) token = roundToken;
        return true;
    }

    /** Only a validated handoff of this same round may change the endpoint. */
    boolean handoff(String candidate, String roundToken) {
        if (!active() || token.isEmpty() || !token.equals(roundToken)
                || candidate == null || candidate.isEmpty()) return false;
        host = candidate;
        return true;
    }

    boolean complete(String roundToken) {
        if (!active() || token.isEmpty() || !token.equals(roundToken)) return false;
        clear();
        return true;
    }

    void clear() { host = token = ""; }
}
