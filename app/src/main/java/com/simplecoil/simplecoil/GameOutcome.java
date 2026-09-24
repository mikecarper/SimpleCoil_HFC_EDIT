package com.simplecoil.simplecoil;

/** Compares the local player or team with known round scores. */
final class GameOutcome {
    private GameOutcome() { }

    enum Result { WON, LOST, TIED, UNKNOWN }

    static Result result(Globals globals, byte localPlayerID, int localScore, int localTeamScore,
                       int[] scores) {
        if (globals == null || scores == null || !Globals.isValidPlayerID(localPlayerID)
                || localPlayerID <= 0)
            return Result.UNKNOWN;
        int gameMode = globals.mGameMode;
        if (gameMode == Globals.GAME_MODE_FFA) {
            boolean hasOpponent = false;
            boolean tied = false;
            for (int id = 1; id <= Globals.MAX_PLAYER_ID && id < scores.length; id++) {
                if (id == (localPlayerID & 0xff) || scores[id] < 0)
                    continue;
                hasOpponent = true;
                if (scores[id] > localScore)
                    return Result.LOST;
                if (scores[id] == localScore)
                    tied = true;
            }
            return !hasOpponent ? Result.UNKNOWN : tied ? Result.TIED : Result.WON;
        }
        if (gameMode != Globals.GAME_MODE_2TEAMS && gameMode != Globals.GAME_MODE_4TEAMS)
            return Result.UNKNOWN;
        int localTeam = globals.calcNetworkTeam(localPlayerID);
        if (localTeam < 1 || localTeam > gameMode)
            return Result.UNKNOWN;
        long[] otherTeamScores = new long[gameMode + 1];
        boolean[] otherTeamSeen = new boolean[gameMode + 1];
        for (int id = 1; id <= Globals.MAX_PLAYER_ID && id < scores.length; id++) {
            if (scores[id] < 0)
                continue;
            int team = globals.calcNetworkTeam((byte) id);
            if (team > 0 && team <= gameMode && team != localTeam) {
                otherTeamScores[team] += scores[id];
                otherTeamSeen[team] = true;
            }
        }
        boolean hasOpponent = false;
        boolean tied = false;
        for (int team = 1; team <= gameMode; team++) {
            if (otherTeamSeen[team]) {
                hasOpponent = true;
                if (otherTeamScores[team] > localTeamScore)
                    return Result.LOST;
                if (otherTeamScores[team] == localTeamScore)
                    tied = true;
            }
        }
        return !hasOpponent ? Result.UNKNOWN : tied ? Result.TIED : Result.WON;
    }
}
