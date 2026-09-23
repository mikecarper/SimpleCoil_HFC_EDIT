package com.simplecoil.simplecoil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Assigns two teams using previous kills and deaths, with at most two players' difference. */
final class BalancedTeams {
    static final class Player {
        final byte id;
        final int kills;
        final int deaths;

        Player(byte id, int kills, int deaths) {
            this.id = id;
            this.kills = Math.max(0, kills);
            this.deaths = Math.max(0, deaths);
        }

        int strength() {
            // Ten neutral observations keep a new player's rating near average.
            long games = (long) kills + deaths;
            return (int) (100 + 100L * (kills - (long) deaths) / (games + 10));
        }
    }

    private BalancedTeams() { }

    static Map<Byte, Integer> assign(List<Player> players, Random random) {
        if (players == null || players.size() < 2 || players.size() > Globals.MAX_PLAYER_ID)
            throw new IllegalArgumentException("Balanced teams need 2 to "
                    + Globals.MAX_PLAYER_ID + " players");
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, random);
        int total = 0;
        for (Player player : shuffled)
            total += player.strength();
        int playerCount = shuffled.size();
        boolean[][][] reachable = new boolean[playerCount + 1][playerCount + 1][total + 1];
        reachable[0][0][0] = true;
        for (int index = 0; index < playerCount; index++) {
            int strength = shuffled.get(index).strength();
            for (int count = 0; count <= index; count++) {
                for (int sum = 0; sum <= total; sum++) {
                    if (!reachable[index][count][sum])
                        continue;
                    reachable[index + 1][count][sum] = true;
                    reachable[index + 1][count + 1][sum + strength] = true;
                }
            }
        }

        int bestCount = 0;
        int bestStrength = 0;
        int bestGap = Integer.MAX_VALUE;
        int ties = 0;
        for (int count = 1; count < playerCount; count++) {
            if (Math.abs(playerCount - 2 * count) > 2)
                continue;
            for (int strength = 0; strength <= total; strength++) {
                if (!reachable[playerCount][count][strength])
                    continue;
                int gap = Math.abs(total - 2 * strength);
                if (gap < bestGap || (gap == bestGap && random.nextInt(++ties) == 0)) {
                    if (gap < bestGap)
                        ties = 1;
                    bestGap = gap;
                    bestCount = count;
                    bestStrength = strength;
                }
            }
        }

        boolean[] onTeamOne = new boolean[playerCount];
        int remainingCount = bestCount;
        int remainingStrength = bestStrength;
        for (int index = playerCount; index > 0; index--) {
            int strength = shuffled.get(index - 1).strength();
            boolean canExclude = reachable[index - 1][remainingCount][remainingStrength];
            boolean canInclude = remainingCount > 0 && remainingStrength >= strength
                    && reachable[index - 1][remainingCount - 1][remainingStrength - strength];
            if (canInclude && (!canExclude || random.nextBoolean())) {
                onTeamOne[index - 1] = true;
                remainingCount--;
                remainingStrength -= strength;
            }
        }
        boolean flip = random.nextBoolean();
        Map<Byte, Integer> result = new HashMap<>();
        for (int index = 0; index < shuffled.size(); index++) {
            int team = onTeamOne[index] ? 1 : 2;
            result.put(shuffled.get(index).id, flip ? 3 - team : team);
        }
        return result;
    }
}
