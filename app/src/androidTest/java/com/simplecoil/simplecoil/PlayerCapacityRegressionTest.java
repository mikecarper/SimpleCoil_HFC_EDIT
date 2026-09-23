package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlayerCapacityRegressionTest {
    @Test public void thirtyTwoPlayersFitTheIdRangeAndHitPacketEncoding() {
        assertEquals(32, Globals.MAX_PLAYER_ID);
        for (int id = 1; id <= Globals.MAX_PLAYER_ID; id++) {
            assertTrue(Globals.isValidPlayerID(id));
            byte encoded = (byte) (id << 2);
            assertEquals(id, (encoded & 0xff) >> 2);
            assertTrue((encoded & 0xff) != Globals.GRENADE_PLAYER_ID);
        }
        assertFalse(Globals.isValidPlayerID(33));
        assertFalse(Globals.isValidPlayerID(256));
        assertFalse(Globals.isValidPlayerID(-1));
    }

    @Test public void twoTeamsHaveSixteenPlayersEach() { assertTeams(2, 16); }
    @Test public void fourTeamsHaveEightPlayersEach() { assertTeams(4, 8); }

    @Test public void freeForAllKeepsAllPlayersDistinct() {
        Globals globals = Globals.getInstance();
        int originalMode = globals.mGameMode;
        try {
            globals.mGameMode = Globals.GAME_MODE_FFA;
            for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++)
                assertEquals(id, globals.calcNetworkTeam(id));
        } finally { globals.mGameMode = originalMode; }
    }

    private void assertTeams(int teams, int perTeam) {
        Globals globals = Globals.getInstance();
        int originalMode = globals.mGameMode;
        try {
            globals.mGameMode = teams;
            int[] members = new int[teams + 1];
            for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++) {
                int team = globals.calcNetworkTeam(id);
                assertEquals((id - 1) / perTeam + 1, team);
                members[team]++;
            }
            for (int team = 1; team <= teams; team++) assertEquals(perTeam, members[team]);
        } finally { globals.mGameMode = originalMode; }
    }
}
