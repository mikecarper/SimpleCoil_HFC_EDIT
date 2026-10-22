package com.simplecoil.protocol;

/** Shared post-round calculations, independent of Android and the host UI. */
public final class RoundStatistics {
    private static final long MAX_HISTORY = 100_000L;

    private RoundStatistics() { }

    /** The existing smoothed kill/death strength index, not an Elo rating. */
    public static int skillIndex(long kills, long deaths) {
        long safeKills = Math.max(0L, Math.min(MAX_HISTORY, kills));
        long safeDeaths = Math.max(0L, Math.min(MAX_HISTORY, deaths));
        return (int) (100L + 100L * (safeKills - safeDeaths)
                / (safeKills + safeDeaths + 10L));
    }

    /**
     * Confirmed gun hits divided by shots, capped at 100%. A wide-cone shot may
     * hit more than one player. Missing counters and zero shots have no ratio.
     */
    public static Double accuracyPercent(long shots, long confirmedGunHits) {
        if (shots <= 0L || confirmedGunHits < 0L) return null;
        return Math.min(100.0, 100.0 * ((double) confirmedGunHits / (double) shots));
    }

    /** Runs of accepted kills or deaths in event order, reset by the opposite event. */
    public static final class Streaks {
        private int kills;
        private int deaths;
        private int bestKills;
        private int bestDeaths;

        public void reset() {
            kills = deaths = bestKills = bestDeaths = 0;
        }

        public void onKill() {
            if (kills < Integer.MAX_VALUE) kills++;
            deaths = 0;
            bestKills = Math.max(bestKills, kills);
        }

        public void onDeath() {
            if (deaths < Integer.MAX_VALUE) deaths++;
            kills = 0;
            bestDeaths = Math.max(bestDeaths, deaths);
        }

        public int currentKillStreak() { return kills; }
        public int currentDeathStreak() { return deaths; }
        public int bestKillStreak() { return bestKills; }
        public int bestDeathStreak() { return bestDeaths; }
    }
}
