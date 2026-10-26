package com.simplecoil.simplecoil;

import com.simplecoil.protocol.RoundRoster;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class RoundRosterTest {
    private RoundRoster full() {
        RoundRoster roster = new RoundRoster(UUID.randomUUID().toString());
        for (int id = 1; id <= 32; id++) roster.restore(new RoundRoster.Member(
                UUID.randomUUID().toString(), id, id <= 16 ? 1 : 2, 0));
        return roster;
    }

    @Test public void replacementInFullThirtyTwoPlayerMatchGetsVacatedTeamSeat() {
        RoundRoster roster = full();
        String newcomer = UUID.randomUUID().toString();
        assertNull(roster.join(newcomer, 1, 2, false, false, 0));
        roster.update(3, 0, 7, 2, 1, false);
        roster.leave(3);
        RoundRoster.Member member = roster.join(newcomer, 1, 2, false, false, 0);
        assertEquals(3, member.id);
        assertEquals(0, member.kills);
        assertEquals(1, member.generation);
        assertFalse(roster.accepts(3, 0));
        assertTrue(roster.accepts(3, 1));
        assertEquals(7, roster.archivedScore(1, false));
        assertEquals(1, roster.archivedScore(1, true));
    }

    @Test public void returningPersonKeepsStatsAndDoesNotDoubleCountArchivedKills() {
        RoundRoster roster = full();
        String identity = roster.seat(3).identity;
        roster.update(3, 0, 7, 2, 1, false);
        roster.leave(3);
        roster.join(UUID.randomUUID().toString(), 1, 2, false, false, 0);
        roster.leave(4);
        RoundRoster.Member returned = roster.join(identity, 1, 2, false, false, 0);
        assertEquals(4, returned.id);
        assertEquals(7, returned.kills);
        assertEquals(2, returned.deaths);
        assertEquals(1, returned.captures);
        assertEquals(0, roster.archivedScore(1, false));
        RoundRoster restored = RoundRoster.decode(roster.encode());
        assertEquals(roster.encode(), restored.encode());
        assertEquals(7, restored.seat(4).kills);
    }

    @Test public void rejoinCannotSwitchTeamsOrRestoreExhaustedLives() {
        RoundRoster roster = full();
        String identity = roster.seat(3).identity;
        roster.leave(3);
        assertNull(roster.join(identity, 2, 2, false, false, 0));
        roster.update(3, 0, 1, 5, 0, false);
        assertNull(roster.join(identity, 1, 2, false, false, 5));
        assertNotNull(roster.join(identity, 1, 2, false, false, 6));
    }

    @Test public void infectedPlayerMustReturnToZombieTeam() {
        RoundRoster roster = full();
        String identity = roster.seat(17).identity;
        roster.update(17, 0, 2, 3, 0, true);
        roster.leave(17);
        assertNull(roster.join(identity, 2, 2, false, true, 0));
        RoundRoster.Member returned = roster.join(identity, 1, 2, false, true, 0);
        assertTrue(returned.infected);
        assertEquals(3, returned.deaths);
    }

    @Test public void bossSeatCannotBeDuplicatedAndRoundTripsRejectOldPackets() {
        RoundRoster roster = full();
        assertNull(roster.join(UUID.randomUUID().toString(), 1, 2, true, false, 0));
        roster.leave(1);
        assertEquals(1, roster.join(UUID.randomUUID().toString(), 1, 2, true, false, 0).id);
        RoundRoster copy = RoundRoster.decode(roster.encode());
        assertFalse(copy.accepts(1, 0));
        assertTrue(copy.accepts(1, 1));
    }

    @Test(expected = IllegalArgumentException.class) public void malformedRosterRejected() {
        RoundRoster.decode("invalid;1,2,3");
    }

    @Test public void balancedTeamCanReuseAnyFreeBlasterId() {
        RoundRoster roster = full();
        roster.seat(17).team = 1;
        roster.leave(17);
        RoundRoster.Member joined = roster.join(UUID.randomUUID().toString(), 1, 2,
                false, false, 0, true, false);
        assertNotNull(joined);
        assertEquals(17, joined.id);
        assertEquals(1, joined.team);
    }

    @Test public void freeForAllAcceptsEveryStandardTeamQrWithoutAssigningATeam() {
        RoundRoster roster = new RoundRoster(UUID.randomUUID().toString());
        for (int team = 1; team <= 4; team++) {
            RoundRoster.Member joined = roster.join(UUID.randomUUID().toString(), team, 0,
                    false, false, 0);
            assertNotNull(joined);
            assertEquals(team, joined.id);
            assertEquals(0, joined.team);
        }
        assertEquals(roster.encode(), RoundRoster.decode(roster.encode()).encode());
    }

    @Test public void fullFreeForAllRejectsNewcomersUntilASeatIsVacatedAndFencesItsOldPackets() {
        RoundRoster roster = new RoundRoster(UUID.randomUUID().toString());
        for (int id = 1; id <= RoundRoster.CAPACITY; id++)
            assertEquals(id, roster.join(UUID.randomUUID().toString(), id % 4 + 1, 0,
                    false, false, 0).id);
        String newcomer = UUID.randomUUID().toString();
        assertNull(roster.join(newcomer, 1, 0, false, false, 0));
        roster.leave(24);
        RoundRoster.Member joined = roster.join(newcomer, 4, 0, false, false, 0);
        assertEquals(24, joined.id);
        assertEquals(0, joined.team);
        assertEquals(2, joined.generation);
        assertFalse(roster.accepts(24, 1));
        assertTrue(roster.accepts(24, 2));
    }

    @Test public void freeForAllReturnKeepsStatsLivesAndSeatGenerationAcrossDifferentTeamQrs() {
        RoundRoster roster = new RoundRoster(UUID.randomUUID().toString());
        String identity = UUID.randomUUID().toString();
        RoundRoster.Member original = roster.join(identity, 1, 0, false, false, 0);
        roster.update(original.id, original.generation, 7, 2, 0, false);
        assertNull(roster.join(identity, 2, 0, false, false, 0));
        RoundRoster.Member resumed = roster.join(identity, 4, 0, false, false, 3, false, true);
        assertEquals(original.id, resumed.id);
        assertEquals(0, resumed.team);
        assertEquals(7, resumed.kills);
        assertEquals(2, resumed.deaths);
        assertFalse(roster.accepts(original.id, original.generation));
        roster.leave(resumed.id);
        assertNull(roster.join(identity, 3, 0, false, false, 2));
        RoundRoster.Member returned = roster.join(identity, 3, 0, false, false, 3);
        assertEquals(resumed.id, returned.id);
        assertEquals(7, returned.kills);
        assertEquals(2, returned.deaths);
        assertEquals(resumed.generation + 1, returned.generation);
    }

    @Test public void freeForAllRejectsInvalidQrValuesAndIncompatibleModes() {
        RoundRoster roster = new RoundRoster(UUID.randomUUID().toString());
        String identity = UUID.randomUUID().toString();
        for (int team : new int[] {-1, 0, 5})
            assertNull(roster.join(identity, team, 0, false, false, 0));
        for (int teamCount : new int[] {-1, 1, 3, 5})
            assertNull(roster.join(identity, 1, teamCount, false, false, 0));
        assertNull(roster.join(identity, 1, 0, true, false, 0));
        assertNull(roster.join(identity, 1, 0, false, true, 0));
        assertNull(roster.join(identity, 1, 0, false, false, 0, true, false));
        assertTrue(roster.members().isEmpty());
    }

    @Test public void retryAfterLostAdmissionAckFencesOldConnectionWithoutLosingStats() {
        RoundRoster roster = full();
        String identity = roster.seat(2).identity;
        roster.update(2, 0, 4, 1, 0, false);
        assertNull(roster.join(identity, 1, 2, false, false, 0));
        RoundRoster.Member returned = roster.join(identity, 1, 2, false, false, 0, false, true);
        assertEquals(2, returned.id);
        assertEquals(4, returned.kills);
        assertEquals(1, returned.generation);
        assertFalse(roster.accepts(2, 0));
        assertEquals(0, roster.archivedScore(1, false));
    }

    @Test public void reusedZombieSeatDoesNotInfectANewSurvivor() {
        Globals globals = Globals.getInstance();
        RoundRoster previousRoster = globals.mRoundRoster;
        boolean previousMode = globals.mInfectionMode;
        java.util.Set<Byte> previousInfected = globals.mInfectedPlayers;
        try {
            globals.mInfectionMode = true;
            RoundRoster roster = full();
            roster.update(17, 0, 2, 3, 0, true);
            globals.installRoundRoster(RoundRoster.decode(roster.encode()));
            assertTrue(globals.isPlayerInfected((byte) 17));
            roster.leave(17);
            RoundRoster.Member survivor = roster.join(UUID.randomUUID().toString(), 2, 2, false, true, 0);
            assertEquals(17, survivor.id);
            globals.installRoundRoster(RoundRoster.decode(roster.encode()));
            assertFalse(globals.isPlayerInfected((byte) 17));
            assertTrue(globals.isPlayerInfected((byte) 1));
        } finally {
            globals.mRoundRoster = previousRoster;
            globals.mInfectionMode = previousMode;
            globals.mInfectedPlayers = previousInfected;
        }
    }
}
