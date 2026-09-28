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
