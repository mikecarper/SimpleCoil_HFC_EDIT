package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BalancedTeamsTest {
    @Test public void experiencedGroupCanBalanceFourAgainstSix() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        players.add(new BalancedTeams.Player((byte) 1, 100, 0));
        players.add(new BalancedTeams.Player((byte) 2, 100, 0));
        players.add(new BalancedTeams.Player((byte) 3, 100, 0));
        for (byte id = 4; id <= 10; id++)
            players.add(new BalancedTeams.Player(id, 0, 0));

        Map<Byte, Integer> teams = BalancedTeams.assign(players, new Random(42));
        assertEquals(teams.get((byte) 1), teams.get((byte) 2));
        assertEquals(teams.get((byte) 1), teams.get((byte) 3));
        int strongTeam = teams.get((byte) 1);
        int strongTeamCount = 0;
        for (int team : teams.values()) {
            if (team == strongTeam)
                strongTeamCount++;
        }
        assertEquals(4, strongTeamCount);
    }

    @Test public void fullLobbyStaysWithinTwoPlayersPerTeam() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        for (byte id = 1; id <= Globals.MAX_PLAYER_ID; id++)
            players.add(new BalancedTeams.Player(id, 0, 0));

        Map<Byte, Integer> teams = BalancedTeams.assign(players, new Random(7));
        int teamOne = 0;
        for (int team : teams.values()) {
            if (team == 1)
                teamOne++;
        }
        assertEquals(Globals.MAX_PLAYER_ID, teams.size());
        assertTrue(Math.abs(Globals.MAX_PLAYER_ID - 2 * teamOne) <= 2);
    }
}
