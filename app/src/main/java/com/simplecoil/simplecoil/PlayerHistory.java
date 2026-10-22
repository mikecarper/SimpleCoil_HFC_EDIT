package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.simplecoil.protocol.RoundStatistics;

/** Match totals stored on the player's own phone, independent of their lobby name or ID. */
final class PlayerHistory {
    private static final String KILLS = "BalancedPriorKills";
    private static final String DEATHS = "BalancedPriorDeaths";
    static final int MAX_TOTAL = 100000;

    private PlayerHistory() { }

    static int kills(Context context) {
        return read(context, KILLS);
    }

    static int deaths(Context context) {
        return read(context, DEATHS);
    }

    private static int read(Context context, String key) {
        // Unit-style service instances have not been attached to Android yet.
        // Registration can still proceed with an empty history in that state.
        if (context == null || context instanceof ContextWrapper
                && ((ContextWrapper) context).getBaseContext() == null)
            return 0;
        SharedPreferences preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME,
                Context.MODE_PRIVATE);
        return Math.max(0, Math.min(MAX_TOTAL,
                FullscreenActivity.readIntPreference(preferences, key, 0)));
    }

    static synchronized void record(Context context, int kills, int deaths) {
        if (context == null || context instanceof ContextWrapper
                && ((ContextWrapper) context).getBaseContext() == null)
            return;
        SharedPreferences preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME,
                Context.MODE_PRIVATE);
        preferences.edit()
                .putInt(KILLS, total(read(context, KILLS), kills))
                .putInt(DEATHS, total(read(context, DEATHS), deaths))
                .apply();
    }

    static int skillIndex(Context context) {
        return RoundStatistics.skillIndex(kills(context), deaths(context));
    }

    /** Read-only registration hint; a host must match it to its active round. */
    static synchronized String baselineRoundToken(Context context) {
        if (!attached(context)) return null;
        SharedPreferences prefs = context.getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        String token = FullscreenActivity.readStringPreference(prefs, "HistoryBaselineRound", "");
        return TcpServer.isValidRoundToken(token) ? token : null;
    }

    /** Remember a baseline without changing the rating until the round ends. */
    static synchronized int beginRound(Context context, String token) {
        if (!attached(context) || !TcpServer.isValidRoundToken(token)) return skillIndex(context);
        SharedPreferences prefs = context.getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        if (token.equals(FullscreenActivity.readStringPreference(prefs, "HistoryBaselineRound", "")))
            return RoundStatistics.skillIndex(
                    FullscreenActivity.readIntPreference(prefs, "HistoryBaselineKills", 0),
                    FullscreenActivity.readIntPreference(prefs, "HistoryBaselineDeaths", 0));
        boolean same = token.equals(FullscreenActivity.readStringPreference(prefs, "HistoryRound", ""));
        int baselineKills = (int) Math.max(0L, kills(context) - (same
                ? Math.max(0L, FullscreenActivity.readIntPreference(prefs, "HistoryRoundKills", 0)) : 0L));
        int baselineDeaths = (int) Math.max(0L, deaths(context) - (same
                ? Math.max(0L, FullscreenActivity.readIntPreference(prefs, "HistoryRoundDeaths", 0)) : 0L));
        prefs.edit().putString("HistoryBaselineRound", token)
                .putInt("HistoryBaselineKills", baselineKills)
                .putInt("HistoryBaselineDeaths", baselineDeaths).apply();
        return RoundStatistics.skillIndex(baselineKills, baselineDeaths);
    }

    /** Registration of the same round must not advertise its partial results twice. */
    static synchronized int baselineKills(Context context, String token) {
        if (!attached(context) || !TcpServer.isValidRoundToken(token)) return kills(context);
        beginRound(context, token);
        return read(context, "HistoryBaselineKills");
    }

    static synchronized int baselineDeaths(Context context, String token) {
        if (!attached(context) || !TcpServer.isValidRoundToken(token)) return deaths(context);
        beginRound(context, token);
        return read(context, "HistoryBaselineDeaths");
    }

    static synchronized void recordRound(Context context, String token, int kills, int deaths) {
        if (!attached(context)) return;
        if (!TcpServer.isValidRoundToken(token)) { record(context, kills, deaths); return; }
        beginRound(context, token);
        SharedPreferences prefs = context.getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
        boolean same = token.equals(FullscreenActivity.readStringPreference(prefs, "HistoryRound", ""));
        int priorKills = same ? Math.max(0, FullscreenActivity.readIntPreference(prefs, "HistoryRoundKills", 0)) : 0;
        int priorDeaths = same ? Math.max(0, FullscreenActivity.readIntPreference(prefs, "HistoryRoundDeaths", 0)) : 0;
        // Totals and the deduplication watermark move together, including when
        // a phone leaves and rejoins the same round under a different lobby ID.
        prefs.edit().putInt(KILLS, total(read(context, KILLS), Math.max(0, kills - priorKills)))
                .putInt(DEATHS, total(read(context, DEATHS), Math.max(0, deaths - priorDeaths)))
                .putString("HistoryRound", token).putInt("HistoryRoundKills", Math.max(kills, priorKills))
                .putInt("HistoryRoundDeaths", Math.max(deaths, priorDeaths)).apply();
    }

    private static int total(int prior, int added) {
        return (int) Math.min(MAX_TOTAL, (long) prior + Math.max(0, added));
    }

    private static boolean attached(Context context) {
        return context != null && (!(context instanceof ContextWrapper)
                || ((ContextWrapper) context).getBaseContext() != null);
    }
}
