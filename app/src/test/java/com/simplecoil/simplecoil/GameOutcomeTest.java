package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class GameOutcomeTest {
    @Test public void teamResultUsesOpponentTeamTotalAndReportsFirstPlaceTie() {
        Globals globals = Globals.getInstance();
        int oldMode = globals.mGameMode;
        boolean oldBoss = globals.mBossMode;
        boolean oldBalanced = globals.mBalancedRandom;
        try {
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mBossMode = false;
            globals.mBalancedRandom = false;
            int[] scores = unknownScores();
            scores[17] = 3;
            scores[18] = 4;
            assertEquals(GameOutcome.Result.WON,
                    GameOutcome.result(globals, (byte) 1, 2, 8, scores));
            assertEquals(GameOutcome.Result.TIED,
                    GameOutcome.result(globals, (byte) 1, 2, 7, scores));
            assertEquals(GameOutcome.Result.LOST,
                    GameOutcome.result(globals, (byte) 1, 2, 6, scores));
        } finally {
            globals.mGameMode = oldMode;
            globals.mBossMode = oldBoss;
            globals.mBalancedRandom = oldBalanced;
        }
    }

    @Test public void freeForAllResultNeedsAnOpponentScore() {
        Globals globals = Globals.getInstance();
        int oldMode = globals.mGameMode;
        try {
            globals.mGameMode = Globals.GAME_MODE_FFA;
            int[] scores = unknownScores();
            assertEquals(GameOutcome.Result.UNKNOWN,
                    GameOutcome.result(globals, (byte) 1, 5, 0, scores));
            scores[2] = 6;
            assertEquals(GameOutcome.Result.LOST,
                    GameOutcome.result(globals, (byte) 1, 5, 0, scores));
            scores[2] = 5;
            assertEquals(GameOutcome.Result.TIED,
                    GameOutcome.result(globals, (byte) 1, 5, 0, scores));
            scores[2] = 4;
            assertEquals(GameOutcome.Result.WON,
                    GameOutcome.result(globals, (byte) 1, 5, 0, scores));
        } finally {
            globals.mGameMode = oldMode;
        }
    }

    private static int[] unknownScores() {
        int[] scores = new int[Globals.MAX_PLAYER_ID + 1];
        Arrays.fill(scores, -1);
        return scores;
    }
}
