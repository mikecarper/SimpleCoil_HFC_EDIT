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
        String[] bad = {"LOBBYSTATE:24:D:OPEN", "LOBBYSTATE:25:D", "LOBBYSTATE:25:X:OPEN:0",
                "LOBBYSTATE:25:P:BAD:0", "LOBBYSTATE:25:P:OPEN:extra",
                "LOBBYSTATE:25:P:OPEN:-1", "LOBBYSTATE:25:P:OPEN:99999999999999999", "", null};
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

    @Test public void olderHostWinsEvenIfNewPhoneHasLowerAddress() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host older = new SharedLobby.Host(InetAddress.getByName("192.168.1.200"), false,
                false, 100_000, 60_000);
        lobby.observe(older);
        lobby.observe(host("192.168.1.2", false, false, 100_000));
        assertSame(older, lobby.best(100_000));
        SharedLobby another = new SharedLobby();
        another.observe(new SharedLobby.Host(older.address, false, false, 1_000_000, 60_000));
        another.observe(host("192.168.1.2", false, false, 1_000_000));
        assertEquals(older.address, another.best(1_000_000).address);
    }

    @Test public void oldestCohortTieBreakIsIndependentOfArrivalOrder() throws Exception {
        SharedLobby.Host[] hosts = {
                new SharedLobby.Host(InetAddress.getByName("192.168.1.200"), false, false, 10_000, 8_000),
                new SharedLobby.Host(InetAddress.getByName("192.168.1.30"), false, false, 10_000, 6_500),
                new SharedLobby.Host(InetAddress.getByName("192.168.1.2"), false, false, 10_000, 5_000)};
        for (int order = 0; order < 3; order++) {
            SharedLobby lobby = new SharedLobby();
            for (int i = 0; i < 3; i++) lobby.observe(hosts[(order + i) % 3]);
            assertSame(hosts[1], lobby.best(10_000));
        }
    }

    @Test public void tcpAndRoundReturnKeepHostButPermanentLossStillFailsOver() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host old = new SharedLobby.Host(InetAddress.getByName("192.168.1.200"), false,
                false, 20_000, 20_000);
        lobby.observe(old);
        lobby.touch(old.address, 34_000);
        lobby.observe(host("192.168.1.2", false, false, 34_000));
        assertEquals(old.address, lobby.best(36_000).address);
        lobby.awaitReturn(old.address, 40_000);
        assertFalse(lobby.best(40_000).playing);
        assertEquals(old.startedAt, lobby.best(40_000).startedAt);
        lobby.observe(host("192.168.1.2", false, false, 54_000));
        assertEquals(old.address, lobby.best(54_000).address);
        assertEquals("192.168.1.2", lobby.best(55_000).address.getHostAddress());
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

    @Test public void hostRolesRoundTripWithoutMistakingAPhoneMasterForALaptop() throws Exception {
        for (SharedLobby.Kind kind : SharedLobby.Kind.values()) {
            SharedLobby.Host parsed = SharedLobby.parse(InetAddress.getByName("192.168.1.20"),
                    SharedLobby.announcement(kind, false, 1234), 5000);
            assertNotNull(parsed);
            assertEquals(kind, parsed.kind);
            assertEquals(kind.dedicated, parsed.dedicated);
            assertEquals(3766, parsed.startedAt);
        }
    }

    @Test public void laptopBeatsEveryIdlePhoneRegardlessOfAgeOrAddress() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host laptop = new SharedLobby.Host(InetAddress.getByName("192.168.1.250"),
                SharedLobby.Kind.LAPTOP, false, 100_000, 0);
        for (SharedLobby.Kind kind : new SharedLobby.Kind[]{SharedLobby.Kind.AUTO_PHONE,
                SharedLobby.Kind.MANUAL_PHONE, SharedLobby.Kind.DEDICATED_PHONE}) {
            SharedLobby.Host phone = new SharedLobby.Host(InetAddress.getByName("192.168.1.2"),
                    kind, false, 100_000, 90_000);
            assertTrue(SharedLobby.preferred(laptop, phone));
            lobby.observe(phone);
        }
        lobby.observe(laptop);
        assertSame(laptop, lobby.best(100_000));
    }

    @Test public void manualSelectionBeatsAutoAndANewerSelectionReplacesTheOldOne() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host olderManual = new SharedLobby.Host(InetAddress.getByName("192.168.1.2"),
                SharedLobby.Kind.MANUAL_PHONE, false, 100_000, 50_000);
        SharedLobby.Host newerManual = new SharedLobby.Host(InetAddress.getByName("192.168.1.200"),
                SharedLobby.Kind.MANUAL_PHONE, false, 100_000, 0);
        lobby.observe(new SharedLobby.Host(InetAddress.getByName("192.168.1.1"),
                SharedLobby.Kind.AUTO_PHONE, false, 100_000, 99_000));
        lobby.observe(olderManual);
        assertSame(olderManual, lobby.best(100_000));
        lobby.observe(newerManual);
        assertSame(newerManual, lobby.best(100_000));
        assertTrue(SharedLobby.preferred(newerManual, olderManual));
    }

    @Test public void thirtyTwoPhonesAgreeOnSimultaneousManualClaimsInAnyArrivalOrder() throws Exception {
        java.util.List<SharedLobby.Host> hosts = new java.util.ArrayList<>();
        for (int id = 1; id <= 32; id++)
            hosts.add(new SharedLobby.Host(InetAddress.getByName("192.168.1." + id),
                    id == 17 || id == 31 ? SharedLobby.Kind.MANUAL_PHONE : SharedLobby.Kind.AUTO_PHONE,
                    false, 100_000, id * 10));
        java.util.Random random = new java.util.Random(27);
        for (int round = 0; round < 100; round++) {
            java.util.Collections.shuffle(hosts, random);
            SharedLobby lobby = new SharedLobby();
            for (SharedLobby.Host host : hosts) lobby.observe(host);
            assertEquals("192.168.1.17", lobby.best(100_000).address.getHostAddress());
        }
    }

    @Test public void runningRoundFinishesBeforeLaptopTakesOver() throws Exception {
        SharedLobby lobby = new SharedLobby();
        SharedLobby.Host phone = new SharedLobby.Host(InetAddress.getByName("192.168.1.2"),
                SharedLobby.Kind.MANUAL_PHONE, true, 100_000, 50_000);
        SharedLobby.Host laptop = new SharedLobby.Host(InetAddress.getByName("192.168.1.250"),
                SharedLobby.Kind.LAPTOP, false, 100_000, 0);
        lobby.observe(phone);
        lobby.observe(laptop);
        assertSame(phone, lobby.best(100_001));
        assertSame(laptop, lobby.laptop(100_001));
        lobby.awaitReturn(phone.address, 100_002);
        assertSame(laptop, lobby.best(100_002));
    }

    @Test public void vanishedSelectedHostExpiresAndDeadEndpointBackoffIsBounded() throws Exception {
        for (SharedLobby.Kind kind : new SharedLobby.Kind[]{SharedLobby.Kind.MANUAL_PHONE, SharedLobby.Kind.LAPTOP}) {
            SharedLobby lobby = new SharedLobby();
            SharedLobby.Host selected = new SharedLobby.Host(InetAddress.getByName("192.168.1.250"),
                    kind, false, 0, 0);
            SharedLobby.Host fallback = host("192.168.1.2", false, false, 1000);
            lobby.observe(selected); lobby.observe(fallback);
            assertSame(selected, lobby.best(SharedLobby.EXPIRY_MS - 1));
            assertSame(fallback, lobby.best(SharedLobby.EXPIRY_MS));
            lobby.observe(new SharedLobby.Host(selected.address, kind, false, 20_000, 20_000));
            lobby.failed(selected.address, 20_000);
            // Even an endpoint that still beacons cannot trap clients in endless failed joins.
            for (int now = 20_000; now < 50_000; now += 1000) {
                lobby.observe(new SharedLobby.Host(selected.address, kind, false, now, now));
                lobby.observe(new SharedLobby.Host(fallback.address, false, false, now));
                assertEquals(fallback.address, lobby.best(now).address);
            }
            assertEquals(selected.address, lobby.best(50_000).address);
        }
    }

    @Test public void consecutiveFailedJoinsDoNotForgetTheOtherFailedEndpoint() throws Exception {
        SharedLobby lobby = new SharedLobby();
        InetAddress laptop = InetAddress.getByName("192.168.1.250");
        InetAddress manual = InetAddress.getByName("192.168.1.200");
        lobby.failed(laptop, 10_000);
        lobby.clearHosts();
        lobby.failed(manual, 25_000);
        lobby.observe(new SharedLobby.Host(laptop, SharedLobby.Kind.LAPTOP, false, 25_000, 0));
        lobby.observe(new SharedLobby.Host(manual, SharedLobby.Kind.MANUAL_PHONE, false, 25_000, 0));
        SharedLobby.Host fallback = host("192.168.1.2", false, false, 25_000);
        lobby.observe(fallback);
        assertSame(fallback, lobby.best(25_000));
        assertTrue(lobby.isBackedOff(laptop, 39_999));
        assertFalse(lobby.isBackedOff(laptop, 40_000));
        assertTrue(lobby.isBackedOff(manual, 40_000));
        // A different AP must not inherit the old subnet's failure exclusions.
        lobby.clear();
        assertFalse(lobby.isBackedOff(manual, 40_000));
    }
}
