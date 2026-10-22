package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
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

    @Test public void changedStatsNeverMoveRetainedPlayers() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        Map<Byte, Integer> previous = new HashMap<>();
        for (byte id = 1; id <= 8; id++) {
            previous.put(id, id <= 4 ? 1 : 2);
            players.add(new BalancedTeams.Player(id, id <= 4 ? 1000 : 0, id <= 4 ? 0 : 1000));
        }
        for (int seed = 0; seed < 20; seed++)
            assertEquals(previous, BalancedTeams.retain(players, previous, new Random(seed)));
    }

    @Test public void newcomersJoinTheSmallerTeamWithoutShufflingExistingPlayers() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        Map<Byte, Integer> previous = new HashMap<>();
        for (byte id = 1; id <= 6; id++) {
            players.add(new BalancedTeams.Player(id, 0, 0));
            if (id <= 4) previous.put(id, id <= 3 ? 1 : 2);
        }
        Map<Byte, Integer> teams = BalancedTeams.retain(players, previous, new Random(3));
        for (byte id = 1; id <= 4; id++) assertEquals(previous.get(id), teams.get(id));
        assertEquals(Integer.valueOf(2), teams.get((byte) 5));
        assertEquals(Integer.valueOf(2), teams.get((byte) 6));
    }

    @Test public void departuresPruneOnlyMissingSeatsEvenIfTeamsBecomeUnequal() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        Map<Byte, Integer> previous = new HashMap<>();
        for (byte id = 1; id <= 8; id++) {
            previous.put(id, id <= 4 ? 1 : 2);
            if (id <= 5) players.add(new BalancedTeams.Player(id, 0, 0));
        }
        Map<Byte, Integer> teams = BalancedTeams.retain(players, previous, new Random(9));
        assertEquals(5, teams.size());
        for (byte id = 1; id <= 5; id++) assertEquals(previous.get(id), teams.get(id));
    }

    @Test public void soleUnknownReturningSeatCanBeAssignedBeforeOtherPlayersReconnect() {
        List<BalancedTeams.Player> players = new ArrayList<>();
        players.add(new BalancedTeams.Player((byte) 7, 0, 0));
        Map<Byte, Integer> teams = BalancedTeams.retain(players, new HashMap<>(), new Random(1));
        assertEquals(1, teams.size());
        assertTrue(teams.get((byte) 7) == 1 || teams.get((byte) 7) == 2);
    }
}
