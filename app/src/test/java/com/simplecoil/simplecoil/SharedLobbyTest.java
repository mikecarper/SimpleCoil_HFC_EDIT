package com.simplecoil.simplecoil;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class SharedLobbyTest {
    private SharedLobby.Host host(String ip, boolean dedicated, boolean playing, long time)
            throws Exception {
        return SharedLobby.parse(InetAddress.getByName(ip),
                SharedLobby.announcement(dedicated, playing), time);
    }

    @Test public void beaconRoundTripsAndRejectsMalformedOrOldProtocol() throws Exception {
        SharedLobby.Host parsed = host("192.168.1.20", true, true, 42);
        assertNotNull(parsed);
        assertTrue(parsed.dedicated);
        assertTrue(parsed.playing);
        assertEquals(42, parsed.seenAt);
        String[] bad = {"LOBBYSTATE:21:D:OPEN", "LOBBYSTATE:24:D", "LOBBYSTATE:24:X:OPEN",
                "LOBBYSTATE:24:P:BAD", "LOBBYSTATE:24:P:OPEN:extra", "", null};
        for (String value : bad)
            assertNull(SharedLobby.parse(parsed.address, value, 1));
        assertNull(host("0.0.0.0", false, false, 1));
        assertNull(host("224.0.0.1", false, false, 1));
        assertNull(host("::1", false, false, 1));
    }

    @Test public void allPhonesConvergeRegardlessOfBeaconOrder() throws Exception {
        SharedLobby.Host[] candidates = {host("192.168.1.200", false, false, 10),
                host("192.168.1.30", false, false, 10), host("192.168.1.3", false, false, 10)};
        for (int first = 0; first < candidates.length; first++) {
            SharedLobby lobby = new SharedLobby();
            for (int i = 0; i < candidates.length; i++)
                lobby.observe(candidates[(first + i) % candidates.length]);
            assertSame(candidates[2], lobby.best(11));
        }
    }

    @Test public void dedicatedWinsIdleElectionButNeverReplacesRunningRound() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host phone = host("192.168.1.2", false, false, 10);
        SharedLobby.Host laptop = host("192.168.1.200", true, false, 10);
        SharedLobby.Host playing = host("192.168.1.250", false, true, 10);
        lobby.observe(phone); lobby.observe(laptop);
        assertSame(laptop, lobby.best(10));
        lobby.observe(playing);
        assertSame(playing, lobby.best(10));
        assertFalse(SharedLobby.preferred(laptop, playing));
    }

    @Test public void staleBeaconsExpireAndNewRoundStateReplacesOld() throws Exception {
        SharedLobby lobby = new SharedLobby();
        lobby.observe(host("192.168.1.2", false, true, 0));
        SharedLobby.Host replacement = host("192.168.1.2", false, false, 100);
        lobby.observe(replacement);
        assertSame(replacement, lobby.best(SharedLobby.EXPIRY_MS + 99));
        assertNull(lobby.best(SharedLobby.EXPIRY_MS + 100));
        lobby.observe(replacement); lobby.clear();
        assertNull(lobby.best(100));
    }
}
