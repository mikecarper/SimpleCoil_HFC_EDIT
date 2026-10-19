package com.simplecoil.simplecoil;

/** Decides when to prepare a nearby path without changing the primary Wi-Fi path. */
final class RelayPreparationPolicy {
    private static final int PREPARE_RSSI_DBM = -67, RECOVER_RSSI_DBM = -62;
    private static final long INITIAL_HOST_GRACE_MS = 3500;
    private final long startedAt;
    private boolean weakWifi, hostSeen;

    RelayPreparationPolicy(long startedAt) { this.startedAt = startedAt; }

    boolean shouldConnect(long now, boolean associated, int rssi, boolean hostReachable, boolean hasBuddy) {
        // Android uses -127 for an unavailable reading. It is not a measured
        // weak signal, and must not defeat the startup grace on healthy Wi-Fi.
        if (!associated) weakWifi = false;
        else if (rssi > -127 && rssi < 0) {
            if (rssi <= PREPARE_RSSI_DBM) weakWifi = true;
            else if (rssi >= RECOVER_RSSI_DBM) weakWifi = false;
        }
        if (hostReachable) hostSeen = true;
        boolean hostMissing = !hostReachable && (hostSeen || now - startedAt >= INITIAL_HOST_GRACE_MS);
        return hostMissing || !hasBuddy && (!associated || weakWifi);
    }
}
