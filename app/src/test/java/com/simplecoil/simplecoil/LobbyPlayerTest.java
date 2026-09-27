package com.simplecoil.simplecoil;

import org.junit.Test;
import static org.junit.Assert.*;

public class LobbyPlayerTest {
    @Test public void readinessIsAutomaticButRequiresBothGunAndClock() {
        assertEquals(LobbyPlayer.Status.NEEDS_GUN, player(true, false, true, false).status());
        assertEquals(LobbyPlayer.Status.SYNCING, player(true, true, false, false).status());
        assertTrue(player(true, true, true, false).ready());
    }

    @Test public void disconnectedAndBenchedPlayersNeverAppearReady() {
        assertEquals(LobbyPlayer.Status.RECONNECTING, player(false, true, true, false).status());
        assertEquals(LobbyPlayer.Status.BENCHED, player(false, false, false, true).status());
        assertFalse(player(true, true, true, true).ready());
    }

    private LobbyPlayer player(boolean connected, boolean gun, boolean clock, boolean bench) {
        return new LobbyPlayer((byte) 2, "Player", connected, gun, clock, bench, false);
    }
}
