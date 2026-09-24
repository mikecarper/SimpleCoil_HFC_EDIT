package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

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

    static void record(Context context, int kills, int deaths) {
        if (context == null || context instanceof ContextWrapper
                && ((ContextWrapper) context).getBaseContext() == null)
            return;
        SharedPreferences preferences = context.getSharedPreferences(FullscreenActivity.PREF_NAME,
                Context.MODE_PRIVATE);
        preferences.edit()
                .putInt(KILLS, Math.min(MAX_TOTAL, read(context, KILLS) + Math.max(0, kills)))
                .putInt(DEATHS, Math.min(MAX_TOTAL, read(context, DEATHS) + Math.max(0, deaths)))
                .apply();
    }
}
