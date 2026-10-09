// SPDX-License-Identifier: Apache-2.0
package com.simplecoil.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Shared, Android-free power-up selection and host-authoritative scan accounting. */
public final class Powerups {
    public static final int EMP = 1;
    public static final int SHIELD_PLAYER = 2;
    public static final int SHIELD_TEAM = 3;
    public static final int FULL_AUTO = 4;
    public static final int FULL_AUTO_90 = 5;
    public static final int DOUBLE_DAMAGE = 6;
    public static final int HEAL_TEAM = 7;
    public static final int DAMAGE_TEAM = 8;
    public static final int INVULNERABLE = 9;
    public static final int MIN_REQUIRED = 2;
    public static final int MAX_REQUIRED = 8;
    public static final int MAX_PLAYERS = 32;
    public static final int MAX_ACTIVE_GRANTS = MAX_PLAYERS * (MAX_REQUIRED / MIN_REQUIRED);
    public static final long BUFF_MS = 15_000L;
    public static final long EMP_MS = 10_000L;
    public static final long INSTANT_DELIVERY_MS = 3_000L;
    public static final int SHIELD_BONUS_PERCENT = 50;
    public static final int DAMAGE_PERCENT = 25;

    private Powerups() { }

    public static boolean isValidRequiredCount(int count) {
        return count >= MIN_REQUIRED && count <= MAX_REQUIRED;
    }

    /** One fewer required distinct scan per five points behind, never fewer than two. */
    public static int effectiveRequired(int base, int ownScore, int bestEnemyScore) {
        if (!isValidRequiredCount(base)) return 0;
        long deficit = Math.max(0L, (long) Math.max(0, bestEnemyScore) - Math.max(0, ownScore));
        return (int) Math.max(MIN_REQUIRED, base - deficit / 5);
    }

    public static boolean isValidKind(int kind) {
        return kind >= EMP && kind <= INVULNERABLE;
    }

    public static boolean isInstant(int kind) {
        return kind == HEAL_TEAM || kind == DAMAGE_TEAM;
    }

    public static long durationMillis(int kind) {
        if (!isValidKind(kind)) return 0;
        return isInstant(kind) ? INSTANT_DELIVERY_MS : kind == EMP ? EMP_MS : BUFF_MS;
    }

    /**
     * Baseline weights by ID are 6,24,6,20,12,18,6,6,2 (100 tickets).
     * Losing by 1-4 points doubles EMP/team-wide/invulnerability weights;
     * losing by 5+ quadruples them. Personal rewards keep their base weights.
     * FFA excludes teammate shields/healing. Negative/missing scores mean zero.
     */
    public static int[] weights(int ownScore, int bestEnemyScore, boolean teamGame) {
        int[] weights = {0, 6, 24, 6, 20, 12, 18, 6, 6, 2};
        long deficit = (long) Math.max(0, bestEnemyScore) - Math.max(0, ownScore);
        int multiplier = deficit >= 5 ? 4 : deficit > 0 ? 2 : 1;
        for (int kind : new int[]{EMP, SHIELD_TEAM, HEAL_TEAM, DAMAGE_TEAM, INVULNERABLE})
            weights[kind] *= multiplier;
        if (!teamGame) {
            weights[SHIELD_TEAM] = 0;
            weights[HEAL_TEAM] = 0;
        }
        return weights;
    }

    public static int choose(Random random, int ownScore, int bestEnemyScore, boolean teamGame) {
        if (random == null) throw new IllegalArgumentException("A random source is required");
        int[] weights = weights(ownScore, bestEnemyScore, teamGame);
        int total = 0;
        for (int weight : weights) total += weight;
        int ticket = random.nextInt(total);
        for (int kind = EMP; kind <= INVULNERABLE; kind++) {
            ticket -= weights[kind];
            if (ticket < 0) return kind;
        }
        throw new AssertionError("Power-up weights did not cover the ticket");
    }

    public static final class Grant {
        public final long id;
        public final int source;
        public final int sourceLife;
        public final int kind;
        public final int[] targets;
        public final int[] targetLives;
        public final long expiresAt;
        public final int scanCost;

        public Grant(long id, int source, int sourceLife, int kind, int[] targets,
                     int[] targetLives, long expiresAt) {
            this(id, source, sourceLife, kind, targets, targetLives, expiresAt, 0);
        }

        public Grant(long id, int source, int sourceLife, int kind, int[] targets,
                     int[] targetLives, long expiresAt, int scanCost) {
            this.id = id;
            this.source = source;
            this.sourceLife = sourceLife;
            this.kind = kind;
            this.targets = targets == null ? null : targets.clone();
            this.targetLives = targetLives == null ? null : targetLives.clone();
            this.expiresAt = expiresAt;
            this.scanCost = scanCost;
        }
    }

    /** Decoded recipient IDs and their corresponding life generations, ordered by player ID. */
    public static final class Recipients {
        public final int[] targets;
        public final int[] targetLives;

        private Recipients(int[] targets, int[] targetLives) {
            this.targets = targets;
            this.targetLives = targetLives;
        }
    }

    /**
     * Compact ASCII wire recipients: an eight-hex-digit player mask followed
     * by eight hex digits per recipient's nonnegative life, in ascending ID
     * order. The complete 32-player value occupies at most 264 characters.
     * Input ordering is preserved through the corresponding lives, then
     * canonicalized; invalid IDs, duplicate recipients, or lives are rejected.
     */
    public static String encodeRecipients(Grant grant) {
        if (grant == null || grant.targets == null || grant.targetLives == null
                || grant.targets.length < 1 || grant.targets.length > MAX_PLAYERS
                || grant.targets.length != grant.targetLives.length)
            throw new IllegalArgumentException("Invalid power-up recipients");
        long mask = 0;
        int[] lives = new int[MAX_PLAYERS + 1];
        for (int index = 0; index < grant.targets.length; index++) {
            int player = grant.targets[index], life = grant.targetLives[index];
            if (!validPlayer(player) || life < 0)
                throw new IllegalArgumentException("Invalid power-up recipient");
            long bit = 1L << (player - 1);
            if ((mask & bit) != 0) throw new IllegalArgumentException("Duplicate power-up recipient");
            mask |= bit;
            lives[player] = life;
        }
        StringBuilder encoded = new StringBuilder(8 + 8 * grant.targets.length);
        appendHexWord(encoded, mask);
        for (int player = 1; player <= MAX_PLAYERS; player++)
            if ((mask & (1L << (player - 1))) != 0) appendHexWord(encoded, lives[player]);
        return encoded.toString();
    }

    /** Reject malformed values atomically; only ASCII hexadecimal digits are accepted. */
    public static Recipients decodeRecipients(String encoded) {
        if (encoded == null || encoded.length() < 16 || encoded.length() > 264)
            throw new IllegalArgumentException("Invalid power-up recipient length");
        long mask = readHexWord(encoded, 0);
        int count = Long.bitCount(mask);
        if (count == 0 || encoded.length() != 8 + 8 * count)
            throw new IllegalArgumentException("Invalid power-up recipient count");
        int[] targets = new int[count], lives = new int[count];
        for (int player = 1, index = 0; player <= MAX_PLAYERS; player++) {
            if ((mask & (1L << (player - 1))) == 0) continue;
            long life = readHexWord(encoded, 8 + 8 * index);
            if (life > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid power-up recipient life");
            targets[index] = player;
            lives[index++] = (int) life;
        }
        return new Recipients(targets, lives);
    }

    private static void appendHexWord(StringBuilder encoded, long value) {
        String digits = "0123456789abcdef";
        for (int shift = 28; shift >= 0; shift -= 4)
            encoded.append(digits.charAt((int) ((value >>> shift) & 15)));
    }

    private static long readHexWord(String encoded, int offset) {
        long value = 0;
        for (int index = offset; index < offset + 8; index++) {
            char digit = encoded.charAt(index);
            int nibble = digit >= '0' && digit <= '9' ? digit - '0'
                    : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                    : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
            if (nibble < 0) throw new IllegalArgumentException("Invalid power-up recipient digit");
            value = (value << 4) | nibble;
        }
        return value;
    }

    /** Player generations are authoritative cumulative deaths, not client timestamps. */
    public static final class ScanState {
        public final int player;
        public final int life;
        public final int mask;
        public final int earned;
        public final int required;
        public final int spent;

        public ScanState(int player, int life, int mask, int earned, int required) {
            this(player, life, mask, earned, required,
                    (long) earned * required > Integer.MAX_VALUE ? -1 : earned * required);
        }

        public ScanState(int player, int life, int mask, int earned, int required, int spent) {
            this.player = player;
            this.life = life;
            this.mask = mask;
            this.earned = earned;
            this.required = required;
            this.spent = spent;
        }
    }

    /**
     * Complete bounded host state. Expirations use capturedAt's clock; restore
     * rebases their remaining duration to the new host's clock. Hosts serialize
     * these simple fields in their existing wire format and round-token scope.
     */
    public static final class Snapshot {
        public final long nextId;
        public final long capturedAt;
        public final List<Grant> grants;
        public final List<ScanState> players;

        public Snapshot(long nextId, long capturedAt, List<Grant> grants, List<ScanState> players) {
            this.nextId = nextId;
            this.capturedAt = capturedAt;
            List<Grant> copies = new ArrayList<>();
            if (grants != null) for (Grant grant : grants) copies.add(copy(grant));
            this.grants = Collections.unmodifiableList(copies);
            this.players = Collections.unmodifiableList(players == null
                    ? new ArrayList<ScanState>() : new ArrayList<>(players));
        }
    }

    public static final class Authority {
        private final Map<Integer, PlayerState> players = new TreeMap<>();
        private final Map<Long, Grant> grants = new TreeMap<>();
        private long nextId = 1;

        /**
         * The host must validate life against its authoritative deaths and seed
         * all recipients with setPlayerLife before requesting a reward. Teams
         * map player IDs to team IDs (0=FFA). Scores map team IDs to team scores
         * in team games, and player IDs to player scores in FFA.
         *
         * Cumulative-mask jumps recover missed scans. Each call consumes at most
         * one earned threshold credit; hosts can drain at most four calls.
         * Each reward spends its current required number of distinct scans,
         * so changing the threshold never reprices scans already consumed.
         * QR checkpoints are an honor system, not cryptographic scan proof.
         */
        public synchronized Grant request(int sourceID, int life, int scannedMask, int required,
                                          int ownTeam, Map<Integer, Integer> teams,
                                          Map<Integer, Integer> scores, Set<Integer> alive,
                                          long now, Random random) {
            if (!validPlayer(sourceID) || life < 0 || !validMask(scannedMask)
                    || !isValidRequiredCount(required) || ownTeam < 0 || ownTeam > 4
                    || teams == null || scores == null || alive == null || random == null
                    || now < 0 || !alive.contains(sourceID)
                    || !Integer.valueOf(ownTeam).equals(teams.get(sourceID))) return null;
            PlayerState previous = players.get(sourceID);
            if (previous != null && (life < previous.life
                    || life == previous.life && (scannedMask & previous.mask) != previous.mask)) return null;
            setPlayerLife(sourceID, life);
            PlayerState state = players.get(sourceID);
            state.mask = scannedMask;
            state.required = required;
            if (Integer.bitCount(scannedMask) - state.spent < required || nextId == Long.MAX_VALUE)
                return null;

            boolean teamGame = ownTeam != 0;
            List<Integer> allies = new ArrayList<>();
            List<Integer> enemies = new ArrayList<>();
            for (int player = 1; player <= MAX_PLAYERS; player++) {
                if (!alive.contains(player)) continue;
                Integer team = teams.get(player);
                if (team == null || team < 0 || team > 4) continue;
                if (!players.containsKey(player)) setPlayerLife(player, 0);
                if (player == sourceID || teamGame && team == ownTeam) allies.add(player);
                else enemies.add(player);
            }
            int ownScore = score(scores, teamGame ? ownTeam : sourceID);
            int bestEnemyScore = 0;
            for (Integer competitor : scores.keySet()) {
                if (competitor == null || (teamGame ? competitor < 1 || competitor > 4
                        || competitor == ownTeam : !validPlayer(competitor) || competitor == sourceID)) continue;
                bestEnemyScore = Math.max(bestEnemyScore, score(scores, competitor));
            }
            int kind = choose(random, ownScore, bestEnemyScore, teamGame);
            List<Integer> selected = kind == EMP || kind == DAMAGE_TEAM ? enemies
                    : kind == SHIELD_TEAM || kind == HEAL_TEAM ? allies
                    : Collections.singletonList(sourceID);
            // A solo survivor cannot receive a useless enemy-wide reward.
            if (selected.isEmpty()) {
                kind = SHIELD_PLAYER;
                selected = Collections.singletonList(sourceID);
            }
            long expiry = addDuration(now, durationMillis(kind));
            if (expiry <= now) return null;
            int[] targets = new int[selected.size()];
            int[] targetLives = new int[selected.size()];
            for (int i = 0; i < targets.length; i++) {
                targets[i] = selected.get(i);
                targetLives[i] = players.get(targets[i]).life;
            }
            Grant grant = new Grant(nextId++, sourceID, life, kind, targets, targetLives, expiry, required);
            state.earned++;
            state.spent += required;
            prune(now);
            grants.put(grant.id, copy(grant));
            // Retain the latest bounded recovery records; clients still honor
            // each already-received effect's original expiration.
            while (grants.size() > MAX_ACTIVE_GRANTS) grants.remove(grants.keySet().iterator().next());
            return grant;
        }

        /** Seed known recipient generations; advancing a life cancels its old effects. */
        public synchronized boolean setPlayerLife(int player, int life) {
            if (!validPlayer(player) || life < 0) return false;
            PlayerState state = players.get(player);
            if (state != null && life < state.life) return false;
            if (state == null || life > state.life) {
                if (state != null) removeEffects(player, false);
                players.put(player, new PlayerState(life));
            }
            return true;
        }

        /** Forget a replaced/removed seat, not merely a delayed scan request. */
        public synchronized void resetPlayer(int player) {
            players.remove(player);
            removeEffects(player, true);
        }

        /** Grant IDs are scoped to the host's existing round token. */
        public synchronized void resetRound() {
            players.clear();
            grants.clear();
            nextId = 1;
        }

        public synchronized List<Grant> activeGrants(long now) {
            return snapshot(now).grants;
        }

        public synchronized Snapshot snapshot(long now) {
            if (now < 0) throw new IllegalArgumentException("Negative power-up clock");
            prune(now);
            List<ScanState> scans = new ArrayList<>();
            for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
                PlayerState state = entry.getValue();
                scans.add(new ScanState(entry.getKey(), state.life, state.mask, state.earned, state.required, state.spent));
            }
            return new Snapshot(nextId, now, new ArrayList<>(grants.values()), scans);
        }

        /** Atomically validate/import a complete snapshot and rebase its remaining durations. */
        public synchronized boolean restore(Snapshot snapshot, long now) {
            if (snapshot == null || now < 0 || snapshot.capturedAt < 0
                    || snapshot.nextId < nextId || snapshot.nextId < 1
                    || snapshot.players.size() > MAX_PLAYERS
                    || snapshot.grants.size() > MAX_ACTIVE_GRANTS) return false;
            Map<Integer, PlayerState> restoredPlayers = new TreeMap<>();
            for (ScanState scan : snapshot.players) {
                if (scan == null || !validPlayer(scan.player) || scan.life < 0 || !validMask(scan.mask)
                        || scan.earned < 0 || scan.spent < 0 || scan.spent > Integer.bitCount(scan.mask)
                        || scan.earned > scan.spent / MIN_REQUIRED
                        || restoredPlayers.containsKey(scan.player)) return false;
                if (scan.required == 0 ? scan.mask != 0 || scan.earned != 0 || scan.spent != 0
                        : !isValidRequiredCount(scan.required)
                        || scan.spent < scan.earned * MIN_REQUIRED
                        || scan.spent > scan.earned * MAX_REQUIRED) return false;
                PlayerState state = new PlayerState(scan.life);
                state.mask = scan.mask;
                state.earned = scan.earned;
                state.required = scan.required;
                state.spent = scan.spent;
                restoredPlayers.put(scan.player, state);
            }
            for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
                PlayerState old = entry.getValue(), replacement = restoredPlayers.get(entry.getKey());
                if (replacement == null || replacement.life < old.life
                        || replacement.life == old.life && ((replacement.mask & old.mask) != old.mask
                        || replacement.earned < old.earned
                        || replacement.spent < old.spent)) return false;
            }
            Map<Long, Grant> restoredGrants = new TreeMap<>();
            Set<Long> ids = new HashSet<>();
            for (Grant grant : snapshot.grants) {
                if (!validGrant(grant, restoredPlayers) || grant.id >= snapshot.nextId
                        || !ids.add(grant.id)) return false;
                if (grant.expiresAt <= snapshot.capturedAt) continue;
                long remaining = grant.expiresAt - snapshot.capturedAt;
                if (remaining > durationMillis(grant.kind)) return false;
                long expiry = addDuration(now, remaining);
                Grant existing = grants.get(grant.id);
                // Replaying the same snapshot must never refresh an observed
                // effect, or resurrect an ID already expired/evicted locally.
                if (grant.id < nextId && existing == null) continue;
                if (existing != null) expiry = Math.min(expiry, existing.expiresAt);
                if (expiry > now) restoredGrants.put(grant.id, new Grant(grant.id, grant.source,
                        grant.sourceLife, grant.kind, grant.targets, grant.targetLives, expiry, grant.scanCost));
            }
            players.clear();
            players.putAll(restoredPlayers);
            grants.clear();
            grants.putAll(restoredGrants);
            nextId = snapshot.nextId;
            return true;
        }

        private void prune(long now) {
            Iterator<Grant> iterator = grants.values().iterator();
            while (iterator.hasNext()) if (iterator.next().expiresAt <= now) iterator.remove();
        }

        private void removeEffects(int player, boolean removedSeat) {
            Iterator<Map.Entry<Long, Grant>> iterator = grants.entrySet().iterator();
            List<Grant> replacements = new ArrayList<>();
            while (iterator.hasNext()) {
                Grant grant = iterator.next().getValue();
                // A source's death does not cancel an EMP or teammate shield
                // already awarded to other living targets. Replacing its seat
                // is different: that identity's recovery records are discarded.
                if (removedSeat && grant.source == player) { iterator.remove(); continue; }
                int found = Arrays.binarySearch(grant.targets, player);
                if (found < 0) continue;
                iterator.remove();
                if (grant.targets.length == 1) continue;
                int[] targets = new int[grant.targets.length - 1];
                int[] lives = new int[targets.length];
                for (int i = 0, out = 0; i < grant.targets.length; i++) {
                    if (i == found) continue;
                    targets[out] = grant.targets[i];
                    lives[out++] = grant.targetLives[i];
                }
                replacements.add(new Grant(grant.id, grant.source, grant.sourceLife,
                        grant.kind, targets, lives, grant.expiresAt, grant.scanCost));
            }
            for (Grant grant : replacements) grants.put(grant.id, grant);
        }
    }

    private static final class PlayerState {
        final int life;
        int mask;
        int earned;
        int required;
        int spent;
        PlayerState(int life) { this.life = life; }
    }

    private static boolean validGrant(Grant grant, Map<Integer, PlayerState> players) {
        if (grant == null || grant.id < 1 || !isValidKind(grant.kind) || grant.expiresAt < 0
                || !isValidRequiredCount(grant.scanCost)
                || !validPlayer(grant.source) || grant.sourceLife < 0
                || grant.targets == null || grant.targetLives == null
                || grant.targets.length == 0 || grant.targets.length > MAX_PLAYERS
                || grant.targets.length != grant.targetLives.length) return false;
        PlayerState source = players.get(grant.source);
        if (source == null || source.life < grant.sourceLife) return false;
        int previous = 0;
        for (int i = 0; i < grant.targets.length; i++) {
            int player = grant.targets[i];
            PlayerState target = players.get(player);
            if (!validPlayer(player) || player <= previous || target == null
                    || target.life != grant.targetLives[i]) return false;
            previous = player;
        }
        return true;
    }

    private static Grant copy(Grant grant) {
        return grant == null ? null : new Grant(grant.id, grant.source, grant.sourceLife,
                grant.kind, grant.targets, grant.targetLives, grant.expiresAt, grant.scanCost);
    }

    private static int score(Map<Integer, Integer> scores, int key) {
        Integer score = scores.get(key);
        return score == null ? 0 : Math.max(0, score);
    }

    private static long addDuration(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static boolean validPlayer(int player) { return player >= 1 && player <= MAX_PLAYERS; }
    private static boolean validMask(int mask) { return mask >= 0 && mask <= 0xff; }
}
