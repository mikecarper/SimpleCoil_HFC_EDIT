package com.simplecoil.simplecoil;

import android.content.Context;
import com.simplecoil.protocol.RoundRoster;
import java.util.UUID;

/** TCP-only admission metadata, separate from the latency-sensitive combat path. */
final class LiveJoin {
    static final String IDENTITY = "playeridentity";
    static final String TEAM = "jointeam";
    static final String ROSTER = "roundroster";
    static final String ASSIGNED = "joinedplayerid";
    static final String REJECTED = "joinrejected";
    static final String INTENT = "qr_live_join";

    static synchronized String identity(Context context) {
        if (context == null || context instanceof android.content.ContextWrapper
                && ((android.content.ContextWrapper) context).getBaseContext() == null)
            return "00000000-0000-0000-0000-000000000001";
        android.content.SharedPreferences prefs = context.getSharedPreferences("PlayerIdentity", 0);
        String value = prefs.getString(IDENTITY, null);
        if (!RoundRoster.validIdentity(value)) {
            value = UUID.randomUUID().toString();
            prefs.edit().putString(IDENTITY, value).apply();
        }
        return value;
    }

    static int generation(int id) {
        RoundRoster roster = Globals.getInstance().mRoundRoster;
        return roster == null ? 0 : roster.generation(id);
    }

    static boolean accepts(int id, int generation) {
        RoundRoster roster = Globals.getInstance().mRoundRoster;
        return roster == null ? generation == 0 : roster.accepts(id, generation);
    }

    static boolean active(int id) {
        RoundRoster roster = Globals.getInstance().mRoundRoster;
        RoundRoster.Member member = roster == null ? null : roster.seat(id);
        return roster == null || member != null && member.active;
    }

    private LiveJoin() { }
}
