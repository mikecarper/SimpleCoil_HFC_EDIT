package com.simplecoil.simplecoil;

import android.content.SharedPreferences;

/** A local snapshot, captured before round state is cleared or teams change. */
final class LastGameStats {
    private static final String PREFIX = "LastGame.";
    final String mode;
    final String team;
    final int kills;
    final int deaths;
    final int hitsTaken;
    final int teamKills;
    final boolean leftEarly;
    final boolean captures;

    LastGameStats(String mode, String team, int kills, int deaths, int hitsTaken,
                  int teamKills, boolean leftEarly) {
        this(mode, team, kills, deaths, hitsTaken, teamKills, leftEarly, false);
    }

    LastGameStats(String mode, String team, int kills, int deaths, int hitsTaken,
                  int teamKills, boolean leftEarly, boolean captures) {
        this.mode = mode;
        this.team = team;
        this.kills = count(kills);
        this.deaths = count(deaths);
        this.hitsTaken = count(hitsTaken);
        this.teamKills = teamKills < 0 ? -1 : Math.min(Globals.MAX_TEAM_SCOREBOARD_VALUE, teamKills);
        this.leftEarly = leftEarly;
        this.captures = captures;
    }

    private static int count(int value) {
        return Math.max(0, Math.min(Globals.MAX_SCOREBOARD_VALUE, value));
    }

    void save(SharedPreferences preferences) {
        preferences.edit().putBoolean(PREFIX + "present", true)
                .putString(PREFIX + "mode", mode).putString(PREFIX + "team", team)
                .putInt(PREFIX + "kills", kills).putInt(PREFIX + "deaths", deaths)
                .putInt(PREFIX + "hitsTaken", hitsTaken).putInt(PREFIX + "teamKills", teamKills)
                .putBoolean(PREFIX + "leftEarly", leftEarly)
                .putBoolean(PREFIX + "captures", captures).apply();
    }

    static LastGameStats load(SharedPreferences preferences) {
        if (!FullscreenActivity.readBooleanPreference(preferences, PREFIX + "present", false))
            return null;
        return new LastGameStats(
                FullscreenActivity.readStringPreference(preferences, PREFIX + "mode", ""),
                FullscreenActivity.readStringPreference(preferences, PREFIX + "team", ""),
                FullscreenActivity.readIntPreference(preferences, PREFIX + "kills", 0),
                FullscreenActivity.readIntPreference(preferences, PREFIX + "deaths", 0),
                FullscreenActivity.readIntPreference(preferences, PREFIX + "hitsTaken", 0),
                FullscreenActivity.readIntPreference(preferences, PREFIX + "teamKills", -1),
                FullscreenActivity.readBooleanPreference(preferences, PREFIX + "leftEarly", false),
                FullscreenActivity.readBooleanPreference(preferences, PREFIX + "captures", false));
    }
}
