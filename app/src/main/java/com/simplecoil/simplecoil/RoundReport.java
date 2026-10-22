package com.simplecoil.simplecoil;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable, validated personal statistics frozen by the host at round end. */
public final class RoundReport {
    public static final String KEY = "roundreport";
    public final String roundToken;
    public final int playerID, kills, deaths, shots, hits, bestKillStreak, bestDeathStreak;
    public final int skillBefore, skillAfter;
    public final long durationMs;
    public final boolean complete;

    public RoundReport(String token, int id, int kills, int deaths, int shots, int hits,
                       int bestKillStreak, int bestDeathStreak, int skillBefore, int skillAfter,
                       long durationMs, boolean complete) {
        if (!TcpServer.isValidRoundToken(token) || id < 1 || !Globals.isValidPlayerID(id)
                || !count(kills) || !count(deaths) || !count(shots) || !count(hits)
                || bestKillStreak < 0 || bestKillStreak > kills
                || bestDeathStreak < 0 || bestDeathStreak > deaths
                || skillBefore < 0 || skillBefore > 200 || skillAfter < 0 || skillAfter > 200
                || durationMs < 0 || durationMs > 7L * 24 * 60 * 60 * 1000)
            throw new IllegalArgumentException("Invalid round report");
        roundToken = token;
        playerID = id;
        this.kills = kills;
        this.deaths = deaths;
        this.shots = shots;
        this.hits = hits;
        this.bestKillStreak = bestKillStreak;
        this.bestDeathStreak = bestDeathStreak;
        this.skillBefore = skillBefore;
        this.skillAfter = skillAfter;
        this.durationMs = durationMs;
        this.complete = complete;
    }

    private static boolean count(int value) {
        return value >= 0 && value <= Globals.MAX_SCOREBOARD_VALUE;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("roundtoken", roundToken).put("playerID", playerID)
                .put("kills", kills).put("deaths", deaths).put("shots", shots).put("hits", hits)
                .put("bestkillstreak", bestKillStreak).put("bestdeathstreak", bestDeathStreak)
                .put("skillbefore", skillBefore).put("skillafter", skillAfter)
                .put("durationms", durationMs).put("complete", complete);
    }

    public static RoundReport fromJson(JSONObject value) throws JSONException {
        Object token = value.get("roundtoken");
        if (!(token instanceof String)) throw new JSONException("Invalid round report token");
        try {
            return new RoundReport((String) token, TcpJson.getInt(value, "playerID"),
                    TcpJson.getInt(value, "kills"), TcpJson.getInt(value, "deaths"),
                    TcpJson.getInt(value, "shots"), TcpJson.getInt(value, "hits"),
                    TcpJson.getInt(value, "bestkillstreak"), TcpJson.getInt(value, "bestdeathstreak"),
                    TcpJson.getInt(value, "skillbefore"), TcpJson.getInt(value, "skillafter"),
                    TcpJson.getLong(value, "durationms"), TcpJson.getBoolean(value, "complete"));
        } catch (IllegalArgumentException invalid) {
            throw new JSONException("Invalid round report values");
        }
    }
}
