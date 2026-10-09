package com.simplecoil.simplecoil;

import com.simplecoil.protocol.Powerups;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class PowerupsTest {
    private final Map<Integer, Integer> teams = map(1, 1, 2, 1, 3, 2, 4, 2, 5, 3);
    private final Map<Integer, Integer> scores = map(1, 2, 2, 8, 3, 5);
    private final Set<Integer> alive = new HashSet<>(Arrays.asList(1, 2, 3, 4));

    @Test public void everyWeightedTicketSelectsExactlyItsDocumentedReward() {
        assertTickets(2, 2, true, new int[]{0, 6, 24, 6, 20, 12, 18, 6, 6, 2});
        assertTickets(2, 3, true, new int[]{0, 12, 24, 12, 20, 12, 18, 12, 12, 4});
        assertTickets(2, 7, true, new int[]{0, 24, 24, 24, 20, 12, 18, 24, 24, 8});
        assertTickets(0, 0, false, new int[]{0, 6, 24, 0, 20, 12, 18, 0, 6, 2});
        assertTickets(Integer.MIN_VALUE, Integer.MAX_VALUE, false,
                new int[]{0, 24, 24, 0, 20, 12, 18, 0, 24, 8});
        assertArrayEquals(Powerups.weights(0, 0, true), Powerups.weights(-1, -5, true));
    }

    @Test public void cumulativeCatchupConsumesFourCreditsButNeverReplaysThem() {
        Powerups.Authority authority = seeded();
        for (int number = 1; number <= 4; number++) {
            Powerups.Grant grant = request(authority, 0, 0xff, 2, Powerups.SHIELD_PLAYER, 1000);
            assertNotNull(grant);
            assertEquals(number, grant.id);
            assertEquals(0, grant.sourceLife);
        }
        assertNull(request(authority, 0, 0xff, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        assertEquals(0xff, authority.snapshot(1000).players.get(0).mask);
        assertEquals(4, authority.snapshot(1000).players.get(0).earned);
    }

    @Test public void incompleteMasksCannotUnlockAndRegressionsCannotEraseProgress() {
        Powerups.Authority authority = seeded();
        assertNull(request(authority, 0, 1, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 2, 2, Powerups.SHIELD_PLAYER, 1000));
        assertEquals(1, authority.snapshot(1000).players.get(0).mask);
        assertNotNull(request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 15, 4, Powerups.SHIELD_PLAYER, 1000));
        assertEquals(15, authority.snapshot(1000).players.get(0).mask);
    }

    @Test public void deathGenerationsResetScansAndRejectAllPreviousLifeRequests() {
        Powerups.Authority authority = seeded();
        assertNotNull(request(authority, 0, 3, 2, Powerups.DOUBLE_DAMAGE, 1000));
        assertTrue(authority.setPlayerLife(1, 1));
        assertFalse(authority.setPlayerLife(1, 0));
        assertTrue(authority.activeGrants(1000).isEmpty());
        assertNull(request(authority, 0, 0xff, 2, Powerups.DOUBLE_DAMAGE, 1000));
        assertNull(request(authority, 1, 1, 2, Powerups.DOUBLE_DAMAGE, 1000));
        Powerups.Grant next = request(authority, 1, 3, 2, Powerups.DOUBLE_DAMAGE, 1000);
        assertNotNull(next);
        assertEquals(1, next.sourceLife);
        assertArrayEquals(new int[]{1}, next.targetLives);
        assertEquals(2, next.id);
    }

    @Test public void rewardsUseLivingTeamsAndPreserveEachRecipientsGeneration() {
        for (int kind = Powerups.EMP; kind <= Powerups.INVULNERABLE; kind++) {
            Powerups.Authority authority = seeded();
            authority.setPlayerLife(2, 2);
            authority.setPlayerLife(3, 1);
            authority.setPlayerLife(4, 3);
            Powerups.Grant grant = request(authority, 0, 3, 2, kind, 1000);
            assertEquals(kind, grant.kind);
            int[] expected = kind == Powerups.EMP || kind == Powerups.DAMAGE_TEAM ? new int[]{3, 4}
                    : kind == Powerups.SHIELD_TEAM || kind == Powerups.HEAL_TEAM ? new int[]{1, 2}
                    : new int[]{1};
            int[] expectedLives = kind == Powerups.EMP || kind == Powerups.DAMAGE_TEAM ? new int[]{1, 3}
                    : kind == Powerups.SHIELD_TEAM || kind == Powerups.HEAL_TEAM ? new int[]{0, 2}
                    : new int[]{0};
            assertArrayEquals(expected, grant.targets);
            assertArrayEquals(expectedLives, grant.targetLives);
            assertEquals(1000 + Powerups.durationMillis(kind), grant.expiresAt);
            assertEquals(2, grant.scanCost);
            assertFalse("Dead player was targeted", contains(grant.targets, 5));
        }
        assertEquals(15_000L, Powerups.durationMillis(Powerups.INVULNERABLE));
        assertEquals(10_000L, Powerups.durationMillis(Powerups.EMP));
        assertEquals(3_000L, Powerups.durationMillis(Powerups.HEAL_TEAM));
        assertEquals(3_000L, Powerups.durationMillis(Powerups.DAMAGE_TEAM));
        assertEquals(0, Powerups.durationMillis(10));
        assertTrue(Powerups.isInstant(Powerups.DAMAGE_TEAM));
        assertFalse(Powerups.isInstant(Powerups.EMP));
        assertEquals(50, Powerups.SHIELD_BONUS_PERCENT);
        assertEquals(25, Powerups.DAMAGE_PERCENT);
    }

    @Test public void ffaTreatsAllOtherPlayersAsEnemiesAndSoloRewardsAreNeverNoOps() {
        Powerups.Authority authority = new Powerups.Authority();
        Map<Integer, Integer> ffaTeams = map(1, 0, 2, 0, 3, 0);
        Map<Integer, Integer> ffaScores = map(1, 0, 2, 12, 3, 1);
        Set<Integer> ffaAlive = new HashSet<>(Arrays.asList(1, 2, 3));
        Powerups.Grant enemyReward = authority.request(1, 0, 3, 2, 0, ffaTeams, ffaScores,
                ffaAlive, 1000, forKind(Powerups.DAMAGE_TEAM, 0, 12, false));
        assertEquals(Powerups.DAMAGE_TEAM, enemyReward.kind);
        assertArrayEquals(new int[]{2, 3}, enemyReward.targets);
        for (int unusable : new int[]{Powerups.EMP, Powerups.DAMAGE_TEAM}) {
            Powerups.Grant solo = new Powerups.Authority().request(1, 0, 3, 2, 0,
                    map(1, 0), map(1, 0), Collections.singleton(1), 1000,
                    forKind(unusable, 0, 0, false));
            assertEquals(Powerups.SHIELD_PLAYER, solo.kind);
            assertArrayEquals(new int[]{1}, solo.targets);
        }
    }

    @Test public void farBehindTeamsNeedFewerScansButSpentCodesAreNeverRepriced() {
        assertEquals(8, Powerups.effectiveRequired(8, 10, 14));
        assertEquals(7, Powerups.effectiveRequired(8, 10, 15));
        assertEquals(5, Powerups.effectiveRequired(8, 10, 25));
        assertEquals(2, Powerups.effectiveRequired(8, 0, Integer.MAX_VALUE));
        assertEquals(2, Powerups.effectiveRequired(2, 0, 30));
        assertEquals(8, Powerups.effectiveRequired(8, 30, 0));
        assertEquals(0, Powerups.effectiveRequired(0, 0, 30));
        Powerups.Authority authority = seeded();
        assertNull(request(authority, 0, 31, 8, Powerups.SHIELD_PLAYER, 1000));
        Powerups.Grant first = request(authority, 0, 31,
                Powerups.effectiveRequired(8, 10, 25), Powerups.SHIELD_PLAYER, 1000);
        assertNotNull(first);
        assertEquals(5, first.scanCost);
        assertEquals(5, authority.snapshot(1000).players.get(0).spent);
        assertNull(request(authority, 0, 31, 2, Powerups.SHIELD_PLAYER, 1000));
        Powerups.Grant second = request(authority, 0, 255, 2, Powerups.SHIELD_PLAYER, 1000);
        assertNotNull(second);
        assertEquals(2, second.scanCost);
        assertEquals(7, authority.snapshot(1000).players.get(0).spent);
        assertEquals(2, authority.snapshot(1000).players.get(0).earned);
        assertNull(request(authority, 0, 255, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 255, 8, Powerups.SHIELD_PLAYER, 1000));
        Powerups.Authority nextHost = new Powerups.Authority();
        assertTrue(nextHost.restore(authority.snapshot(1000), 1000));
        assertEquals(5, nextHost.activeGrants(1000).get(0).scanCost);
        assertEquals(2, nextHost.activeGrants(1000).get(1).scanCost);
        assertNull(request(nextHost, 0, 255, 2, Powerups.SHIELD_PLAYER, 1000));
        assertTrue(nextHost.setPlayerLife(1, 1));
        assertNotNull(request(nextHost, 1, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        assertEquals(2, nextHost.snapshot(1000).players.get(0).spent);
    }

    @Test public void targetDeathAndSeatReplacementRemoveObsoleteEffects() {
        Powerups.Authority authority = seeded();
        Powerups.Grant emp = request(authority, 0, 3, 2, Powerups.EMP, 1000);
        assertArrayEquals(new int[]{3, 4}, emp.targets);
        authority.setPlayerLife(3, 1);
        assertArrayEquals(new int[]{4}, authority.activeGrants(1000).get(0).targets);
        assertNull(request(authority, 0, 3, 2, Powerups.EMP, 1000));
        authority.resetPlayer(4);
        assertTrue(authority.activeGrants(1000).isEmpty());
        authority.resetPlayer(1);
        assertNotNull(request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        authority.resetRound();
        assertTrue(authority.activeGrants(1000).isEmpty());
        assertTrue(authority.snapshot(1000).players.isEmpty());
        assertEquals(1, authority.snapshot(1000).nextId);
    }

    @Test public void sourceDeathPreservesOtherLivingRecipientsAndTheirOriginalExpiry() {
        for (int kind : new int[]{Powerups.EMP, Powerups.SHIELD_TEAM}) {
            Powerups.Authority authority = seeded();
            Powerups.Grant grant = request(authority, 0, 3, 2, kind, 1000);
            authority.setPlayerLife(1, 1);
            Powerups.Grant active = authority.activeGrants(2000).get(0);
            assertEquals(0, active.sourceLife);
            assertEquals(grant.expiresAt, active.expiresAt);
            assertArrayEquals(kind == Powerups.EMP ? new int[]{3, 4} : new int[]{2}, active.targets);
            Powerups.Authority nextHost = new Powerups.Authority();
            assertTrue(nextHost.restore(authority.snapshot(2000), 2000));
            assertEquals(grant.expiresAt, nextHost.activeGrants(2000).get(0).expiresAt);
            authority.resetPlayer(1);
            assertTrue(authority.activeGrants(2000).isEmpty());
        }
    }

    @Test public void snapshotsPreserveCreditsAndRebaseWithoutRefreshingObservedBuffs() {
        Powerups.Authority first = seeded();
        Powerups.Grant grant = request(first, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000);
        Powerups.Snapshot snapshot = first.snapshot(2000);
        Powerups.Authority second = new Powerups.Authority();
        assertTrue(second.restore(snapshot, 50_000));
        assertEquals(64_000, second.activeGrants(50_000).get(0).expiresAt);
        assertNull(request(second, 0, 3, 2, Powerups.SHIELD_PLAYER, 50_000));
        assertTrue(second.restore(snapshot, 51_000));
        assertEquals(64_000, second.activeGrants(51_000).get(0).expiresAt);
        assertTrue(second.activeGrants(64_000).isEmpty());
        assertTrue(second.restore(snapshot, 65_000));
        assertTrue("An old snapshot resurrected an expired ID", second.activeGrants(65_000).isEmpty());
        Powerups.Grant next = request(second, 0, 15, 2, Powerups.SHIELD_PLAYER, 65_000);
        assertEquals(grant.id + 1, next.id);
    }

    @Test public void arraysAndSnapshotsCannotMutateAuthorityState() {
        Powerups.Authority authority = seeded();
        Powerups.Grant grant = request(authority, 0, 3, 2, Powerups.EMP, 1000);
        grant.targets[0] = 32;
        grant.targetLives[0] = 999;
        Powerups.Snapshot snapshot = authority.snapshot(1000);
        assertArrayEquals(new int[]{3, 4}, snapshot.grants.get(0).targets);
        snapshot.grants.get(0).targets[0] = 31;
        assertArrayEquals(new int[]{3, 4}, authority.activeGrants(1000).get(0).targets);
    }

    @Test public void malformedSnapshotsAreRejectedAtomically() {
        Powerups.Authority first = seeded();
        Powerups.Grant grant = request(first, 0, 3, 2, Powerups.SHIELD_PLAYER, 1000);
        Powerups.Snapshot valid = first.snapshot(1000);
        List<Powerups.Snapshot> invalid = new ArrayList<>();
        invalid.add(new Powerups.Snapshot(1, 1000, valid.grants, valid.players));
        invalid.add(new Powerups.Snapshot(2, -1, valid.grants, valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, Arrays.asList(grant, grant), valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, Collections.singletonList(new Powerups.Grant(
                1, 1, 0, Powerups.SHIELD_PLAYER, new int[]{1}, new int[]{1}, 16_000, 2)), valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, Collections.singletonList(new Powerups.Grant(
                1, 1, 0, 10, new int[]{1}, new int[]{0}, 16_000, 2)), valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, Collections.singletonList(new Powerups.Grant(
                1, 1, 0, Powerups.EMP, new int[]{4, 3}, new int[]{0, 0}, 11_000, 2)), valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, Collections.singletonList(new Powerups.Grant(
                1, 1, 0, Powerups.SHIELD_PLAYER, new int[]{1}, new int[]{0}, 16_001, 2)), valid.players));
        invalid.add(new Powerups.Snapshot(2, 1000, valid.grants,
                Arrays.asList(new Powerups.ScanState(1, 0, 3, 1, 2), new Powerups.ScanState(1, 0, 3, 1, 2))));
        invalid.add(new Powerups.Snapshot(2, 1000, valid.grants,
                Collections.singletonList(new Powerups.ScanState(1, 0, 255, 5, 2))));
        invalid.add(new Powerups.Snapshot(2, 1000, valid.grants,
                Collections.singletonList(new Powerups.ScanState(1, 0, 256, 0, 2))));
        invalid.add(new Powerups.Snapshot(100, 1000,
                Collections.nCopies(Powerups.MAX_ACTIVE_GRANTS + 1, grant), valid.players));
        Powerups.Authority receiver = new Powerups.Authority();
        for (Powerups.Snapshot snapshot : invalid) {
            assertFalse(receiver.restore(snapshot, 5000));
            assertEquals(1, receiver.snapshot(5000).nextId);
            assertTrue(receiver.snapshot(5000).players.isEmpty());
            assertTrue(receiver.activeGrants(5000).isEmpty());
        }
        assertTrue(receiver.restore(valid, 5000));
        assertNotNull(request(receiver, 0, 15, 2, Powerups.SHIELD_PLAYER, 5000));
        assertFalse("An older snapshot rolled scan credits back", receiver.restore(valid, 5000));
    }

    @Test public void allThirtyTwoPlayersCanRecoverTheirFourUnexpiredRewardsAndSpentScans() {
        Powerups.Authority authority = new Powerups.Authority();
        Map<Integer, Integer> allTeams = new HashMap<>();
        Set<Integer> allAlive = new HashSet<>();
        for (int player = 1; player <= 32; player++) {
            allTeams.put(player, 1);
            allAlive.add(player);
            authority.setPlayerLife(player, 0);
        }
        int[] distinctKinds = {Powerups.FULL_AUTO, Powerups.FULL_AUTO_90,
                Powerups.DOUBLE_DAMAGE, Powerups.INVULNERABLE};
        for (int player = 1; player <= 32; player++) for (int kind : distinctKinds)
            assertNotNull(authority.request(player, 0, 255, 2, 1, allTeams, map(1, 0), allAlive,
                    1000, forKind(kind, 0, 0, true)));
        Powerups.Snapshot snapshot = authority.snapshot(1000);
        assertEquals(32, snapshot.players.size());
        assertEquals(128, Powerups.MAX_ACTIVE_GRANTS);
        assertEquals(128, snapshot.grants.size());
        assertEquals(1, snapshot.grants.get(0).id);
        assertEquals(128, snapshot.grants.get(127).id);
        assertEquals(129, snapshot.nextId);
        Powerups.Authority nextHost = new Powerups.Authority();
        assertTrue(nextHost.restore(snapshot, 5000));
        List<Powerups.Grant> restored = nextHost.activeGrants(5000);
        assertEquals(128, restored.size());
        for (int index = 0; index < restored.size(); index++) {
            Powerups.Grant grant = restored.get(index);
            assertEquals(index + 1, grant.id);
            assertEquals(distinctKinds[index % 4], grant.kind);
            assertArrayEquals(new int[]{index / 4 + 1}, grant.targets);
            assertArrayEquals(new int[]{0}, grant.targetLives);
            assertEquals(20000, grant.expiresAt);
        }
        for (int player = 1; player <= 32; player++) {
            Powerups.ScanState state = nextHost.snapshot(5000).players.get(player - 1);
            assertEquals(255, state.mask);
            assertEquals(4, state.earned);
            assertEquals(8, state.spent);
            assertNull(nextHost.request(player, 0, 255, 2, 1, allTeams, map(1, 0), allAlive,
                    5000, forKind(Powerups.SHIELD_PLAYER, 0, 0, true)));
        }
    }

    @Test public void newLivesKeepRecoveryRecordsBoundedAfterOneHundredTwentyEightRewards() {
        Powerups.Authority authority = new Powerups.Authority();
        Map<Integer, Integer> allTeams = new HashMap<>();
        Set<Integer> allAlive = new HashSet<>();
        for (int player = 1; player <= 32; player++) {
            allTeams.put(player, 1);
            allAlive.add(player);
            authority.setPlayerLife(player, 0);
        }
        for (int player = 1; player <= 32; player++) for (int credit = 0; credit < 4; credit++)
            assertNotNull(authority.request(player, 0, 255, 2, 1, allTeams, map(1, 0), allAlive,
                    1000, forKind(Powerups.SHIELD_TEAM, 0, 0, true)));
        // A source's next life strips that old target, but its teammate effects
        // remain valid and unexpired. A new reward must still obey the bound.
        for (int player = 1; player <= 2; player++) {
            assertTrue(authority.setPlayerLife(player, 1));
            assertNotNull(authority.request(player, 1, 3, 2, 1, allTeams, map(1, 0), allAlive,
                    1000, forKind(Powerups.SHIELD_TEAM, 0, 0, true)));
            Powerups.Snapshot snapshot = authority.snapshot(1000);
            assertEquals(128, snapshot.grants.size());
            assertEquals(player + 1, snapshot.grants.get(0).id);
            assertEquals(128 + player, snapshot.grants.get(127).id);
            assertEquals(129 + player, snapshot.nextId);
            assertTrue(new Powerups.Authority().restore(snapshot, 5000));
        }
    }

    @Test public void compactRecipientsRoundTripAllPlayersAndMaximumNonnegativeLives() {
        int[] targets = new int[32], lives = new int[32];
        for (int index = 0; index < targets.length; index++) {
            targets[index] = index + 1;
            lives[index] = Integer.MAX_VALUE - index;
        }
        String encoded = Powerups.encodeRecipients(recipientGrant(targets, lives));
        assertEquals(264, encoded.length());
        assertEquals("ffffffff", encoded.substring(0, 8));
        Powerups.Recipients decoded = Powerups.decodeRecipients(encoded);
        assertArrayEquals(targets, decoded.targets);
        assertArrayEquals(lives, decoded.targetLives);
        assertArrayEquals(lives, Powerups.decodeRecipients(encoded.toUpperCase(java.util.Locale.ROOT)).targetLives);
        assertEquals("8000000000000000",
                Powerups.encodeRecipients(recipientGrant(new int[]{32}, new int[]{0})));
        assertEquals("000000017fffffff",
                Powerups.encodeRecipients(recipientGrant(new int[]{1}, new int[]{Integer.MAX_VALUE})));
        assertArrayEquals(new int[]{32}, Powerups.decodeRecipients("8000000000000000").targets);
    }

    @Test public void compactRecipientsCanonicalizeUnsortedTargetsWithoutChangingTheirLives() {
        String encoded = Powerups.encodeRecipients(recipientGrant(new int[]{32, 1, 17},
                new int[]{Integer.MAX_VALUE, 0, 17}));
        assertEquals("8001000100000000000000117fffffff", encoded);
        Powerups.Recipients decoded = Powerups.decodeRecipients(encoded);
        assertArrayEquals(new int[]{1, 17, 32}, decoded.targets);
        assertArrayEquals(new int[]{0, 17, Integer.MAX_VALUE}, decoded.targetLives);
        assertEquals(encoded, Powerups.encodeRecipients(recipientGrant(decoded.targets, decoded.targetLives)));
    }

    @Test public void compactRecipientsRejectMalformedCountsCharactersAndLifeBounds() {
        for (String encoded : Arrays.asList(null, "", "00000000", "0000000000000000",
                "00000001", "000000010000000", "00000001000000000", "0000000300000000",
                "000000010000000000000000", "0000000180000000", "00000001ffffffff",
                "g000000100000000", "000000010000000g", "00000001+0000000", "00000001-0000000",
                "000000010000000 ", "ffffffff" + String.join("", Collections.nCopies(33, "00000000")),
                "000000010000000" + (char) 0xff26, "000000010000000" + (char) 0)) {
            try {
                Powerups.decodeRecipients(encoded);
                fail("Malformed recipients were accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void compactRecipientsRejectInvalidGrantArraysAndDuplicatePlayers() {
        List<Powerups.Grant> invalid = Arrays.asList(null, recipientGrant(null, new int[]{0}),
                recipientGrant(new int[]{1}, null), recipientGrant(new int[0], new int[0]),
                recipientGrant(new int[]{1, 2}, new int[]{0}),
                recipientGrant(new int[]{1, 1}, new int[]{0, 1}),
                recipientGrant(new int[]{0}, new int[]{0}), recipientGrant(new int[]{33}, new int[]{0}),
                recipientGrant(new int[]{1}, new int[]{-1}),
                recipientGrant(new int[33], new int[33]));
        for (Powerups.Grant grant : invalid) {
            try {
                Powerups.encodeRecipients(grant);
                fail("Invalid grant recipients were accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void invalidRequestsDoNotCreateCreditsOrUnboundedExpirations() {
        Powerups.Authority authority = seeded();
        for (int mask : new int[]{-1, 256}) assertNull(request(authority, 0, mask, 2, Powerups.SHIELD_PLAYER, 1000));
        for (int required : new int[]{0, 1, 9}) assertNull(request(authority, 0, 3, required, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, -1, 3, 2, Powerups.SHIELD_PLAYER, 1000));
        assertNull(request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, -1));
        assertNull(authority.request(5, 0, 3, 2, 3, teams, scores, alive, 1000, new Random(1)));
        assertNull(authority.request(1, 0, 3, 2, 0, teams, scores, alive, 1000, new Random(1)));
        assertNull(authority.request(1, 0, 3, 2, 1, teams, scores, alive, 1000, null));
        assertEquals(1, authority.snapshot(1000).nextId);
        Powerups.Grant boundary = request(authority, 0, 3, 2, Powerups.SHIELD_PLAYER, Long.MAX_VALUE - 10);
        assertEquals(Long.MAX_VALUE, boundary.expiresAt);
        assertNull(request(authority, 0, 15, 2, Powerups.SHIELD_PLAYER, Long.MAX_VALUE));
    }

    private Powerups.Authority seeded() {
        Powerups.Authority authority = new Powerups.Authority();
        for (int player : teams.keySet()) authority.setPlayerLife(player, 0);
        return authority;
    }

    private static Powerups.Grant recipientGrant(int[] targets, int[] lives) {
        return new Powerups.Grant(1, 1, 0, Powerups.SHIELD_TEAM, targets, lives, 16000, 2);
    }

    private Powerups.Grant request(Powerups.Authority authority, int life, int mask, int required, int kind, long now) {
        return authority.request(1, life, mask, required, 1, teams, scores, alive, now, forKind(kind, 2, 8, true));
    }

    private static Random forKind(int kind, int ownScore, int enemyScore, boolean teamGame) {
        int ticket = 0;
        int[] weights = Powerups.weights(ownScore, enemyScore, teamGame);
        assertTrue(weights[kind] > 0);
        for (int previous = 1; previous < kind; previous++) ticket += weights[previous];
        return new TicketRandom(ticket);
    }

    private static void assertTickets(int own, int enemy, boolean teamGame, int[] expected) {
        int total = 0;
        for (int count : expected) total += count;
        int[] observed = new int[10];
        for (int ticket = 0; ticket < total; ticket++)
            observed[Powerups.choose(new TicketRandom(ticket), own, enemy, teamGame)]++;
        assertArrayEquals(expected, observed);
    }

    private static Map<Integer, Integer> map(int... pairs) {
        Map<Integer, Integer> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private static final class TicketRandom extends Random {
        private final int ticket;
        TicketRandom(int ticket) { this.ticket = ticket; }
        @Override public int nextInt(int bound) {
            assertTrue(ticket >= 0 && ticket < bound);
            return ticket;
        }
    }
}
