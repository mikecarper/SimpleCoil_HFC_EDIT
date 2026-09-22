package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.CountDownTimer;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Tests the real UDP parser and discovery callbacks, plus real listener lifetime. */
@RunWith(AndroidJUnit4.class)
public class UDPRegressionTest {
    private static final String PEER_ROUND_TOKEN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private RecordingService service;
    private InetAddress teammate;
    private InetAddress enemy;
    private InetAddress stranger;
    private int originalGameState;
    private int originalGameMode;
    private byte originalPlayerID;
    private InetAddress originalServer;
    private boolean originalTournamentMode;

    @Before
    public void setUp() throws Exception {
        service = new RecordingService();
        teammate = InetAddress.getByName("127.0.0.2");
        enemy = InetAddress.getByName("127.0.0.3");
        stranger = InetAddress.getByName("127.0.0.4");
        set(service, "mMyIP", InetAddress.getByName("127.0.0.1"));
        Globals globals = Globals.getInstance();
        originalGameState = globals.mGameState;
        originalGameMode = globals.mGameMode;
        originalPlayerID = globals.mPlayerID;
        originalServer = globals.mServerIP;
        originalTournamentMode = globals.mTournamentMode;
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mPlayerID = 1;
        globals.mServerIP = null;
        globals.mTournamentMode = false;
        clearPlayers();
    }

    @After
    public void tearDown() throws Exception {
        destroy(service);
        Thread worker = (Thread) get(service, "mUDPMessageThread");
        if (worker != null) {
            worker.join(3000);
            assertFalse("UDP listener did not stop", worker.isAlive());
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Globals globals = Globals.getInstance();
        globals.mGameState = originalGameState;
        globals.mGameMode = originalGameMode;
        globals.mPlayerID = originalPlayerID;
        globals.mServerIP = originalServer;
        globals.mTournamentMode = originalTournamentMode;
        clearPlayers();
    }

    @Test
    public void unknownSenderCannotEndGameOrReportTeamPointsAndShots() throws Exception {
        receive(stranger, NetMsg.NETMSG_ENDGAME);
        receive(stranger, NetMsg.NETMSG_TEAMELIMINATED);
        receive(stranger, NetMsg.NETMSG_SHOTFIRED);
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void opponentCannotAwardPointsToOurTeam() throws Exception {
        register(enemy, 11);
        receive(enemy, NetMsg.NETMSG_TEAMELIMINATED);
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void teammateStillAwardsTeamPoints() throws Exception {
        register(teammate, 2);
        receive(teammate, NetMsg.NETMSG_TEAMELIMINATED);
        assertEquals(NetMsg.NETMSG_TEAMELIMINATED, service.events.get(0).getAction());
    }

    @Test
    public void teamMembershipUsesCurrentGameMode() throws Exception {
        register(teammate, 6);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
        receive(teammate, NetMsg.NETMSG_TEAMELIMINATED);
        assertTrue(service.events.isEmpty());
        Globals.getInstance().mGameMode = Globals.GAME_MODE_2TEAMS;
        receive(teammate, NetMsg.NETMSG_TEAMELIMINATED);
        assertEquals(1, service.events.size());
    }

    @Test
    public void freeForAllIgnoresTeamScorePackets() throws Exception {
        register(teammate, 2);
        Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
        receive(teammate, NetMsg.NETMSG_TEAMELIMINATED);
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void knownPeerAndCurrentServerCanStillEndGame() throws Exception {
        register(enemy, 11);
        receive(enemy, NetMsg.NETMSG_ENDGAME);
        Globals.getInstance().mServerIP = stranger;
        receive(stranger, NetMsg.NETMSG_ENDGAME);
        assertEquals(2, service.events.size());
        assertEquals(NetMsg.NETMSG_ENDGAME, service.events.get(0).getAction());
        assertEquals(NetMsg.NETMSG_ENDGAME, service.events.get(1).getAction());
    }

    @Test
    public void tournamentRejectsBareUdpEndGameFromAKnownPlayer() throws Exception {
        Globals.getInstance().mTournamentMode = true;
        register(enemy, 11);

        receive(enemy, NetMsg.NETMSG_ENDGAME);

        assertTrue("A tournament player ended the host through UDP", service.events.isEmpty());
    }

    @Test
    public void peerEndGameIsBoundToTheCurrentRoundToken() throws Exception {
        final String firstRound = "11111111-1111-1111-1111-111111111111";
        final String secondRound = "22222222-2222-2222-2222-222222222222";
        register(enemy, 11);
        service.startGame(true, secondRound);

        receive(enemy, NetMsg.NETMSG_ENDGAME);
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + firstRound);
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + secondRound + "x");
        assertTrue("A bare, stale, or malformed peer ENDGAME ended the round", service.events.isEmpty());

        service.endGame();
        assertEquals(NetMsg.NETMSG_PEER_ENDGAME + secondRound, service.sentMessages.get(0));
        assertEquals(3, (int) service.repeatCounts.get(0));

        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + secondRound);
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_ENDGAME, service.events.get(0).getAction());
        assertEquals(secondRound, service.events.get(0).getStringExtra(NetMsg.INTENT_ROUND_TOKEN));

        service.events.clear();
        service.startGame(true, firstRound);
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + secondRound);
        assertTrue("A previous round's ENDGAME ended the replacement round", service.events.isEmpty());
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + firstRound);
        assertEquals(NetMsg.NETMSG_ENDGAME, service.events.get(0).getAction());
    }

    @Test
    public void tournamentPeerEndGameMustComeFromTheHostEndpoint() throws Exception {
        Globals.getInstance().mTournamentMode = true;
        Globals.getInstance().mServerIP = teammate;
        register(teammate, 2);
        register(enemy, 11);
        service.startGame(true, PEER_ROUND_TOKEN);

        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);
        assertTrue("A tournament peer ended the host's round", service.events.isEmpty());

        receive(teammate, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_ENDGAME, service.events.get(0).getAction());
    }

    @Test
    public void peerEndGameIsRetainedUntilTheActivityCanConsumeIt() throws Exception {
        final String replacementRound = "77777777-7777-7777-7777-777777777777";
        register(enemy, 11);
        service.startGame(true, PEER_ROUND_TOKEN);

        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);
        Intent pendingEvent = service.consumePendingPeerEndGame();
        assertNotNull("A paused activity lost the authenticated peer ENDGAME", pendingEvent);
        assertEquals(NetMsg.NETMSG_ENDGAME, pendingEvent.getAction());
        assertEquals(PEER_ROUND_TOKEN, pendingEvent.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        assertNull("A retained peer ENDGAME was delivered more than once",
                service.consumePendingPeerEndGame());

        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);
        service.startGame(true, replacementRound);
        assertNull("A previous round's retained ENDGAME crossed into its replacement",
                service.consumePendingPeerEndGame());
    }

    @Test
    public void peerEndDuringPausedStartIsRetainedUntilTheRoundBecomesActive() throws Exception {
        register(enemy, 11);

        // TcpClient broadcasts starts asynchronously. Exercise an ENDGAME that
        // arrives before UDPListenerService has handled that start broadcast.
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);
        assertTrue("A pre-start peer ENDGAME was delivered as a live event", service.events.isEmpty());
        assertNull("A pre-start peer ENDGAME was consumable before its round was active",
                service.consumePendingPeerEndGame());

        service.startGame(true, PEER_ROUND_TOKEN);
        Intent pendingEvent = service.consumePendingPeerEndGame();
        assertNotNull("A matching peer ENDGAME was lost while the activity was paused", pendingEvent);
        assertEquals(PEER_ROUND_TOKEN,
                pendingEvent.getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
    }

    @Test
    public void candidatePeerEndCannotCrossThroughADedicatedStart() throws Exception {
        register(enemy, 11);
        receive(enemy, NetMsg.NETMSG_PEER_ENDGAME + PEER_ROUND_TOKEN);

        service.startGame(false, null);
        service.startGame(true, PEER_ROUND_TOKEN);
        assertNull("A tokened peer ENDGAME crossed through a dedicated round",
                service.consumePendingPeerEndGame());
    }

    @Test
    public void stalePeerScoreAndLeaveCannotCrossIntoANewRound() throws Exception {
        final String oldRound = "33333333-3333-3333-3333-333333333333";
        final String currentRound = "44444444-4444-4444-4444-444444444444";
        register(teammate, 2);
        register(enemy, 11);
        service.startGame(true, currentRound);

        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + oldRound + ":1");
        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + oldRound + ":11:1");
        receive(enemy, NetMsg.NETMSG_PEER_LEAVE + oldRound);
        assertTrue("A prior-round peer packet changed the current round", service.events.isEmpty());
        assertEquals(Byte.valueOf((byte) 11), Globals.getInstance().mIPTeamMap.get(enemy));

        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + currentRound + ":1");
        assertEquals(NetMsg.NETMSG_ELIMINATED, service.events.get(0).getAction());
        assertEquals(currentRound, service.events.get(0).getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
        receive(enemy, NetMsg.NETMSG_PEER_LEAVE + currentRound);
        assertEquals(NetMsg.NETMSG_LEAVE, service.events.get(1).getAction());
        assertEquals(currentRound, service.events.get(1).getStringExtra(NetMsg.INTENT_ROUND_TOKEN));
    }

    @Test
    public void fixedCommandsRejectTrailingGarbageWithoutRemovingPlayer() throws Exception {
        register(teammate, 2);
        String[] commands = {NetMsg.NETMSG_SHOTFIRED, NetMsg.NETMSG_HIT, NetMsg.NETMSG_OUT,
                NetMsg.NETMSG_ALREADYDEAD,
                NetMsg.NETMSG_ELIMINATED, NetMsg.NETMSG_LEAVE, NetMsg.NETMSG_ENDGAME,
                NetMsg.NETMSG_TEAMELIMINATED, NetMsg.NETMSG_SAMETEAM, NetMsg.NETMSG_ERROR};
        for (String command : commands) receive(teammate, command + "garbage");
        assertTrue(service.events.isEmpty());
        assertEquals(Byte.valueOf((byte) 2), Globals.getInstance().mIPTeamMap.get(teammate));
    }

    @Test
    public void udpDiscoveryDoesNotCreateARosterEntryBeforeTcpRegistration() throws Exception {
        set(service, "mIsListService", true);
        receive(teammate, NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "2");

        assertTrue("UDP discovery created an unauthenticated player entry",
                Globals.getInstance().mIPTeamMap.isEmpty());
        assertTrue("UDP discovery created an unauthenticated endpoint entry",
                Globals.getInstance().mTeamIPMap.isEmpty());
    }

    @Test
    public void conflictingDiscoveryAssignsFirstFreeSlotOnTheSameTeam() throws Exception {
        set(service, "mIsListService", true);
        // The host is player 1, and player 2 already occupies the first other
        // slot on team 1. A joining player that requested 1 must receive 3,
        // not a slot from the opposing team.
        register(enemy, 2);

        receive(teammate, NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1");

        assertEquals(1, service.endpointMessages.size());
        assertEquals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY + ":3",
                service.endpointMessages.get(0));
        assertEquals(teammate, service.endpointRecipients.get(0));
        assertEquals(Globals.getInstance().calcNetworkTeam((byte) 1),
                Globals.getInstance().calcNetworkTeam((byte) 3));
        assertEquals("UDP discovery must not create an unauthenticated roster entry", 1,
                Globals.getInstance().mTeamIPMap.size());
    }

    @Test
    public void simultaneousConflictingDiscoveriesReceiveDistinctReservedSlots() throws Exception {
        set(service, "mIsListService", true);

        receive(teammate, NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1");
        receive(enemy, NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1");

        assertEquals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY + ":2",
                service.endpointMessages.get(0));
        assertEquals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY + ":3",
                service.endpointMessages.get(1));
        assertEquals("Discovery reservations must not become roster entries", 0,
                Globals.getInstance().mTeamIPMap.size());
    }

    @Test
    public void assignedServerReplyCarriesReplacementIdIntoTheTcpJoin() throws Exception {
        beginJoin(teammate);

        receive(teammate, NetMsg.NETMSG_SERVERREPLY_ASSIGNMENT_PREFIX + "3");

        assertEquals(1, service.events.size());
        Intent reply = service.events.get(0);
        assertEquals(NetMsg.NETMSG_SERVERREPLY, reply.getAction());
        assertEquals(3, reply.getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0));
        assertEquals(teammate, Globals.getInstance().mServerIP);
        assertFalse(flag("mScanRunning"));
    }

    @Test
    public void passiveListenerForwardsOnlyAValidGameInvitation() throws Exception {
        set(service, "mPassiveInviteListener", true);
        receive(teammate, NetMsg.NETMSG_GAMEINVITE_PREFIX + NetMsg.NETWORK_VERSION + ":"
                + PEER_ROUND_TOKEN);

        assertEquals(1, service.events.size());
        Intent invite = service.events.get(0);
        assertEquals(NetMsg.NETMSG_GAMEINVITE, invite.getAction());
        assertEquals(teammate.getHostAddress(),
                invite.getStringExtra(UDPListenerService.INTENT_SERVERIP));
        assertEquals(PEER_ROUND_TOKEN,
                invite.getStringExtra(UDPListenerService.INTENT_GAME_INVITE_TOKEN));

        service.events.clear();
        receive(enemy, NetMsg.NETMSG_GAMEINVITE_PREFIX + "99:" + PEER_ROUND_TOKEN);
        receive(enemy, NetMsg.NETMSG_GAMEINVITE_PREFIX + NetMsg.NETWORK_VERSION + ":not-a-token");
        assertTrue("Malformed or incompatible invitations reached the UI", service.events.isEmpty());
    }

    @Test
    public void activeNetworkSessionIgnoresGameInvitations() throws Exception {
        receive(teammate, NetMsg.NETMSG_GAMEINVITE_PREFIX + NetMsg.NETWORK_VERSION + ":"
                + PEER_ROUND_TOKEN);
        assertTrue("A lobby or game session was replaced by an unsolicited invite", service.events.isEmpty());
    }

    @Test
    public void passiveListenerForwardsOnlyACompatibleLobbyInvitation() throws Exception {
        set(service, "mPassiveInviteListener", true);
        receive(teammate, NetMsg.NETMSG_LOBBYINVITE_PREFIX + NetMsg.NETWORK_VERSION);

        assertEquals(1, service.events.size());
        Intent invite = service.events.get(0);
        assertEquals(NetMsg.NETMSG_LOBBYINVITE, invite.getAction());
        assertEquals(teammate.getHostAddress(),
                invite.getStringExtra(UDPListenerService.INTENT_SERVERIP));

        service.events.clear();
        receive(enemy, NetMsg.NETMSG_LOBBYINVITE_PREFIX + "99");
        assertTrue("An incompatible lobby invitation reached the UI", service.events.isEmpty());
    }

    @Test
    public void idleLobbyForwardsTakeoverButPeerRoundDoesNot() throws Exception {
        set(service, "keepListening", true);
        receive(teammate, NetMsg.NETMSG_HOSTTAKEOVER_PREFIX + NetMsg.NETWORK_VERSION);

        assertEquals(1, service.events.size());
        Intent takeover = service.events.get(0);
        assertEquals(NetMsg.NETMSG_HOSTTAKEOVER, takeover.getAction());
        assertEquals(teammate.getHostAddress(),
                takeover.getStringExtra(UDPListenerService.INTENT_SERVERIP));

        service.events.clear();
        set(service, "mPeerGame", true);
        receive(enemy, NetMsg.NETMSG_HOSTTAKEOVER_PREFIX + NetMsg.NETWORK_VERSION);
        assertTrue("A takeover packet affected a running peer game", service.events.isEmpty());
    }

    @Test
    public void invitedJoinReusesThePassiveListenerInsteadOfReportingFailure() throws Exception {
        set(service, "mPassiveInviteListener", true);
        set(service, "doneListening", false);
        set(service, "keepListening", true);
        set(service, "mReadyToScan", 1);

        assertTrue(service.joinGameInvite(teammate));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(flag("mScanRunning"));
        assertFalse(flag("mPassiveInviteListener"));
        assertEquals("Promoting a passive listener must retain its bound-socket readiness", 1,
                ((Integer) get(service, "mReadyToScan")).intValue());
        assertEquals(1, service.listenerStarts);
        assertTrue("Accepting an invite emitted a spurious join failure", service.events.isEmpty());
    }

    @Test
    public void hostInvitationTemporarilyAllowsLateDiscovery() throws Exception {
        set(service, "keepListening", true);
        set(service, "mBroadcastAddress", teammate);
        set(service, "mIsListService", false);

        assertTrue(service.inviteNearbyPlayers(PEER_ROUND_TOKEN));
        assertTrue(flag("mIsListService"));
        assertTrue(flag("mInviteTemporarilyAllowsJoin"));

        service.allowJoin(true);
        assertTrue(flag("mIsListService"));
        assertFalse("An explicit host policy must supersede the invitation timer",
                flag("mInviteTemporarilyAllowsJoin"));
    }

    @Test
    public void conflictingDiscoveryDoesNotDeadlockWithLobbyReplacement() throws Exception {
        Globals globals = Globals.getInstance();
        Semaphore originalTeams = globals.mTeamIPMapSemaphore;
        PausingFirstAcquireSemaphore teams = new PausingFirstAcquireSemaphore();
        Thread discovery = null;
        Thread replacement = null;
        Throwable[] failures = new Throwable[2];
        globals.mTeamIPMapSemaphore = teams;
        try {
            set(service, "mIsListService", true);
            discovery = new Thread(() -> {
                try {
                    receive(teammate, NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1");
                } catch (Throwable error) {
                    failures[0] = error;
                }
            }, "SimpleCoil duplicate discovery");
            discovery.start();
            assertTrue("Duplicate discovery did not acquire the team map",
                    teams.awaitFirstAcquire(2000));

            replacement = new Thread(() -> {
                try {
                    service.joinServer(enemy, 17509);
                } catch (Throwable error) {
                    failures[1] = error;
                }
            }, "SimpleCoil replace UDP lobby");
            replacement.start();
            assertTrue("Lobby replacement did not reach the team map",
                    awaitQueued(teams, 2000));

            teams.resumeFirstAcquire();
            discovery.join(2000);
            replacement.join(2000);
            assertFalse("Duplicate discovery deadlocked with lobby replacement", discovery.isAlive());
            assertFalse("Lobby replacement deadlocked with duplicate discovery", replacement.isAlive());
            assertNull("Duplicate discovery failed", failures[0]);
            assertNull("Lobby replacement failed", failures[1]);
            assertEquals(1, service.listenerStarts);
        } finally {
            teams.resumeFirstAcquire();
            if (replacement != null && replacement.isAlive())
                replacement.interrupt();
            if (discovery != null && discovery.isAlive())
                discovery.interrupt();
            if (replacement != null)
                replacement.join(2000);
            if (discovery != null)
                discovery.join(2000);
            globals.mTeamIPMapSemaphore = originalTeams;
        }
    }

    @Test
    public void udpLeaveCannotRemoveALiveTcpRosterEntry() throws Exception {
        register(teammate, 2);
        receive(teammate, NetMsg.NETMSG_LEAVE);

        assertEquals(Byte.valueOf((byte) 2), Globals.getInstance().mIPTeamMap.get(teammate));
        assertEquals(teammate, Globals.getInstance().mTeamIPMap.get((byte) 2));
        assertTrue("UDP leave bypassed TCP roster lifecycle", service.events.isEmpty());
    }

    @Test
    public void peerLeaveRemovesPeerRosterAndNotifiesRemainingPlayers() throws Exception {
        register(teammate, 2);
        startPeerGame();

        receive(teammate, NetMsg.NETMSG_PEER_LEAVE + PEER_ROUND_TOKEN);

        assertTrue(Globals.getInstance().mIPTeamMap.isEmpty());
        assertTrue(Globals.getInstance().mTeamIPMap.isEmpty());
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_LEAVE, service.events.get(0).getAction());
        assertEquals(2, service.events.get(0).getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0));
    }

    @Test
    public void peerLeaveCannotOvertakeASequencedFinalScoreEvent() throws Exception {
        register(teammate, 2);
        register(enemy, 11);
        startPeerGame();

        receive(enemy, NetMsg.NETMSG_PEER_LEAVE + PEER_ROUND_TOKEN);
        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":1");
        assertEquals(2, service.events.size());
        assertEquals(NetMsg.NETMSG_ELIMINATED, service.events.get(1).getAction());
        assertEquals(11, service.events.get(1).getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0));

        receive(teammate, NetMsg.NETMSG_PEER_LEAVE + PEER_ROUND_TOKEN);
        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":11:1");
        assertEquals(4, service.events.size());
        assertEquals(NetMsg.NETMSG_TEAMELIMINATED, service.events.get(3).getAction());
    }

    @Test
    public void peerGrenadePairingTracksKnownPlayerAndRejectsStaleOrUnknownUpdates() throws Exception {
        register(teammate, 2);
        Globals globals = Globals.getInstance();
        int[] originalPairings;
        Globals.getmGrenadePairingsSemaphore();
        try {
            originalPairings = globals.mGrenadePairings.clone();
            for (int index = 0; index < globals.mGrenadePairings.length; index++)
                globals.mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
            globals.mGrenadePairings[4] = 2;
        } finally {
            globals.mGrenadePairingsSemaphore.release();
        }
        try {
            startPeerGame();
            receive(teammate, NetMsg.NETMSG_GRENADEPAIR
                    + "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb:1:5");
            assertEquals("A prior-round pairing changed the current round", Globals.INVALID_PLAYER_ID,
                    globals.mGrenadePairings[5]);
            receive(teammate, NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":1:3");
            assertEquals(2, globals.mGrenadePairings[3]);
            assertEquals("A new pairing retained the player's old grenade", Globals.INVALID_PLAYER_ID,
                    globals.mGrenadePairings[4]);

            receive(teammate, NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":2:0");
            assertEquals("A peer disarm did not clear its prior pairing", Globals.INVALID_PLAYER_ID,
                    globals.mGrenadePairings[3]);
            receive(teammate, NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":1:4");
            assertEquals("A delayed pairing revived after a newer disarm", Globals.INVALID_PLAYER_ID,
                    globals.mGrenadePairings[4]);

            receive(stranger, NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":3:5");
            assertEquals("An unregistered sender claimed a grenade", Globals.INVALID_PLAYER_ID,
                    globals.mGrenadePairings[5]);
        } finally {
            Globals.getmGrenadePairingsSemaphore();
            try {
                System.arraycopy(originalPairings, 0, globals.mGrenadePairings, 0,
                        globals.mGrenadePairings.length);
            } finally {
                globals.mGrenadePairingsSemaphore.release();
            }
        }
    }

    @Test
    public void peerGrenadePublisherUpdatesLocalStateAndRepeatsTheLatestSnapshot() throws Exception {
        register(teammate, 2);
        Globals globals = Globals.getInstance();
        byte originalPairedGrenade = globals.mPairedGrenadeID;
        int[] originalPairings;
        Globals.getmGrenadePairingsSemaphore();
        try {
            originalPairings = globals.mGrenadePairings.clone();
            for (int index = 0; index < globals.mGrenadePairings.length; index++)
                globals.mGrenadePairings[index] = Globals.INVALID_PLAYER_ID;
        } finally {
            globals.mGrenadePairingsSemaphore.release();
        }
        try {
            startPeerGame();
            globals.mPairedGrenadeID = 3;
            service.publishPeerGrenadePairing();
            assertEquals(1, globals.mGrenadePairings[3]);
            assertEquals(NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":1:3", service.sentMessages.get(0));
            assertEquals(3, (int) service.repeatCounts.get(0));

            globals.mPairedGrenadeID = 0;
            service.publishPeerGrenadePairing();
            assertEquals(Globals.INVALID_PLAYER_ID, globals.mGrenadePairings[3]);
            assertEquals(NetMsg.NETMSG_GRENADEPAIR + PEER_ROUND_TOKEN + ":2:0", service.sentMessages.get(1));
            assertEquals(3, (int) service.repeatCounts.get(1));

            service.startGame(false);
            globals.mPairedGrenadeID = 4;
            service.publishPeerGrenadePairing();
            assertEquals("Dedicated games must retain TCP-authoritative pairings", 2,
                    service.sentMessages.size());
            assertEquals(Globals.INVALID_PLAYER_ID, globals.mGrenadePairings[4]);
        } finally {
            globals.mPairedGrenadeID = originalPairedGrenade;
            Globals.getmGrenadePairingsSemaphore();
            try {
                System.arraycopy(originalPairings, 0, globals.mGrenadePairings, 0,
                        globals.mGrenadePairings.length);
            } finally {
                globals.mGrenadePairingsSemaphore.release();
            }
        }
    }

    @Test
    public void peerEliminationEventsAreSequencedAndCannotAwardDuplicatePoints() throws Exception {
        register(enemy, 11);
        register(teammate, 2);
        startPeerGame();

        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":1");
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_ELIMINATED, service.events.get(0).getAction());
        assertEquals(11, service.events.get(0).getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0));
        assertEquals(1L, service.events.get(0).getLongExtra(NetMsg.INTENT_EVENT_SEQUENCE, 0));

        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":1");
        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":0");
        receive(teammate, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":2");
        receive(stranger, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":2");
        assertEquals("Duplicate, invalid, teammate, or unknown score events changed the score", 1,
                service.events.size());

        receive(enemy, NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":2");
        assertEquals(2, service.events.size());
        assertEquals(2L, service.events.get(1).getLongExtra(NetMsg.INTENT_EVENT_SEQUENCE, 0));
    }

    @Test
    public void peerTeamEliminationEventsAreSequencedAndValidateBothTeams() throws Exception {
        register(teammate, 2);
        register(enemy, 11);
        startPeerGame();

        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":11:1");
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_TEAMELIMINATED, service.events.get(0).getAction());

        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":11:1");
        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":2:2");
        receive(enemy, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":2:1");
        assertEquals("Repeated or invalid team score relays changed the team score", 1,
                service.events.size());

        receive(teammate, NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":11:2");
        assertEquals(2, service.events.size());
    }

    @Test
    public void peerScorePublisherRetriesSequencedEventsAndLeaves() throws Exception {
        register(teammate, 2);
        register(enemy, 11);
        startPeerGame();

        service.publishPeerElimination((byte) 11);
        assertEquals(1, service.directMessages.size());
        assertEquals(NetMsg.NETMSG_PEER_ELIMINATED + PEER_ROUND_TOKEN + ":1", service.directMessages.get(0));
        assertEquals(Byte.valueOf((byte) 11), service.directRecipients.get(0));
        assertEquals(3, (int) service.directRepeatCounts.get(0));

        service.publishPeerTeamElimination((byte) 11, 1, (byte) 2);
        assertEquals(2, service.directMessages.size());
        assertEquals(NetMsg.NETMSG_PEER_TEAMELIMINATED + PEER_ROUND_TOKEN + ":11:1", service.directMessages.get(1));
        assertEquals(Byte.valueOf((byte) 2), service.directRecipients.get(1));
        assertEquals(3, (int) service.directRepeatCounts.get(1));

        service.announcePeerLeave();
        assertEquals(NetMsg.NETMSG_PEER_LEAVE + PEER_ROUND_TOKEN, service.sentMessages.get(0));
        assertEquals(3, (int) service.repeatCounts.get(0));

        service.startGame(false);
        service.publishPeerElimination((byte) 11);
        service.publishPeerTeamElimination((byte) 11, 2, (byte) 2);
        service.announcePeerLeave();
        assertEquals(2, service.directMessages.size());
        assertEquals(1, service.sentMessages.size());
    }

    @Test
    public void validCombatFeedbackEventsKeepTheirPlayerIds() throws Exception {
        register(enemy, 11);
        String[] commands = {NetMsg.NETMSG_HIT, NetMsg.NETMSG_OUT, NetMsg.NETMSG_ALREADYDEAD,
                NetMsg.NETMSG_ELIMINATED};
        for (String command : commands) receive(enemy, command);
        assertEquals(commands.length, service.events.size());
        for (int index = 0; index < commands.length; index++) {
            assertEquals(commands[index], service.events.get(index).getAction());
            assertEquals(11, service.events.get(index).getByteExtra(UDPListenerService.INTENT_PLAYERID, (byte) 0));
        }
    }

    @Test
    public void lateIdConflictCannotKickPlayerOutOfActiveGame() throws Exception {
        receive(teammate, NetMsg.NETMSG_SAMETEAM);
        receive(teammate, NetMsg.NETMSG_VERSIONERROR);
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void manualJoinIgnoresRepliesAndRejectionsFromAnotherHost() throws Exception {
        beginJoin(teammate);
        receive(stranger, NetMsg.NETMSG_SERVERREPLY);
        receive(stranger, NetMsg.NETMSG_SAMETEAM);
        receiveRaw(stranger, NetMsg.NETMSG_VERSIONERROR);
        receive(teammate, NetMsg.NETMSG_SERVERREPLY + "garbage");
        assertTrue(service.events.isEmpty());
        assertTrue(flag("mScanRunning"));
        assertNull(Globals.getInstance().mServerIP);
    }

    @Test
    public void successfulReplyCancelsTimeoutAndKeepsListenerRunning() throws Exception {
        CountDownTimer pending = beginJoin(teammate);
        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        runOnMain(pending::onFinish);
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_SERVERREPLY, service.events.get(0).getAction());
        assertEquals(teammate, Globals.getInstance().mServerIP);
        assertFalse(flag("mScanRunning"));
        assertTrue(flag("keepListening"));
        assertNull(get(service, "mJoinTimer"));
    }

    @Test
    public void broadcastDiscoveryAcceptsAReplyFromTheDiscoveredHost() throws Exception {
        InetAddress broadcast = InetAddress.getByName("127.255.255.255");
        set(service, "mBroadcastAddress", broadcast);
        beginJoin(broadcast);
        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        assertEquals(teammate, Globals.getInstance().mServerIP);
        assertEquals(NetMsg.NETMSG_SERVERREPLY, service.events.get(0).getAction());
    }

    @Test
    public void versionRejectionIsRecognizedWithAndWithoutPrefix() throws Exception {
        beginJoin(teammate);
        receiveRaw(teammate, NetMsg.NETMSG_VERSIONERROR);
        assertEquals(NetMsg.NETMSG_VERSIONERROR, service.events.get(0).getAction());
        assertFalse(flag("mScanRunning"));
        assertFalse(flag("keepListening"));
        beginJoin(teammate);
        receive(teammate, NetMsg.NETMSG_VERSIONERROR);
        assertEquals(2, service.events.size());
        assertEquals(NetMsg.NETMSG_VERSIONERROR, service.events.get(1).getAction());
    }

    @Test
    public void duplicateIdRejectionStopsRetriesWithoutAFollowupTimeout() throws Exception {
        CountDownTimer pending = beginJoin(teammate);
        receive(teammate, NetMsg.NETMSG_SAMETEAM);
        runOnMain(pending::onFinish);
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_SAMETEAM, service.events.get(0).getAction());
        assertFalse(flag("mScanRunning"));
        assertFalse(flag("keepListening"));
    }

    @Test
    public void oldTimeoutCannotCancelTheNextJoinAttempt() throws Exception {
        CountDownTimer old = beginJoin(teammate);
        service.stopListen();
        CountDownTimer current = beginJoin(enemy);
        runOnMain(old::onFinish);
        assertTrue(flag("mScanRunning"));
        assertSame(current, get(service, "mJoinTimer"));
        assertTrue(service.events.isEmpty());
        receive(enemy, NetMsg.NETMSG_SERVERREPLY);
        assertEquals(enemy, Globals.getInstance().mServerIP);
    }

    @Test
    public void currentTimeoutStillFailsExactlyOnce() throws Exception {
        CountDownTimer current = beginJoin(teammate);
        runOnMain(current::onFinish);
        runOnMain(current::onFinish);
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_FAILEDTOJOIN, service.events.get(0).getAction());
        assertFalse(flag("keepListening"));
    }

    @Test
    public void stoppingBeforeTimerIsPostedDoesNotStartNewRetries() throws Exception {
        runOnMain(() -> {
            service.joinServer(teammate, 17509);
            service.stopListen();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull(get(service, "mJoinTimer"));
        assertFalse(flag("mScanRunning"));
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void destroyedServiceCannotRestartDiscoveryOrListener() throws Exception {
        beginJoin(teammate);
        destroy(service);
        service.realListener = true;
        service.joinServer(teammate, 17509);
        service.startListenForUDPMessage();
        assertFalse(flag("keepListening"));
        assertFalse(flag("mScanRunning"));
        assertNull(get(service, "mJoinTimer"));
        assertNull(get(service, "mUDPMessageThread"));
    }

    @Test
    public void invalidEndpointsFailWithoutStartingNetworkWork() throws Exception {
        service.joinServer((InetAddress) null, 17509);
        service.joinServer(teammate, null);
        service.joinServer(teammate, 0);
        service.joinServer(teammate, 65536);
        assertEquals(4, service.events.size());
        assertEquals(0, service.listenerStarts);
        assertFalse(flag("mScanRunning"));
        assertNull(get(service, "mJoinTimer"));
    }

    @Test
    public void missingDhcpLeaseDoesNotCreateAGlobalBroadcastAddress() throws Exception {
        assertNull(UDPListenerService.broadcastAddressForDhcp(0, 0));
        assertNull(UDPListenerService.broadcastAddressForDhcp(0, 0x00FFFFFF));
        assertEquals("192.168.1.255", UDPListenerService.broadcastAddressForDhcp(
                0x0101A8C0, 0x00FFFFFF).getHostAddress());
    }

    @Test
    public void failedHostCreationCancelsAnOlderJoinBeforeItCanSucceed() throws Exception {
        // A user can select Create Server while a previous discovery request is
        // still shutting down. Reporting that failure alone leaves the old scan
        // able to accept a late SERVERREPLY and join a server the user abandoned.
        set(service, "doneListening", false);
        set(service, "keepListening", true);
        set(service, "mScanRunning", true);
        set(service, "mJoinAddress", teammate);
        set(service, "mBroadcastScan", false);

        service.createServer();

        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_FAILEDTOJOIN, service.events.get(0).getAction());
        assertFalse(flag("keepListening"));
        assertFalse(flag("mScanRunning"));

        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        assertEquals("A late discovery reply restarted the abandoned join", 1, service.events.size());
        assertNull(Globals.getInstance().mServerIP);
    }

    @Test
    public void failedReplacementJoinCancelsAnOlderJoinBeforeItCanSucceed() throws Exception {
        // A second Join can race an existing discovery listener while it is
        // shutting down. Reporting that failure alone leaves the earlier scan
        // able to accept a late SERVERREPLY for a host the player replaced.
        set(service, "doneListening", false);
        set(service, "keepListening", true);
        set(service, "mScanRunning", true);
        set(service, "mJoinAddress", teammate);
        set(service, "mBroadcastScan", false);

        service.joinServer(enemy, 17509);

        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_FAILEDTOJOIN, service.events.get(0).getAction());
        assertFalse(flag("keepListening"));
        assertFalse(flag("mScanRunning"));

        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        assertEquals("A late discovery reply restarted the abandoned join", 1, service.events.size());
        assertNull(Globals.getInstance().mServerIP);
    }

    @Test
    public void failedBroadcastJoinCancelsAnOlderJoinBeforeDhcpLookup() throws Exception {
        // The normal Join button must retire an active scan before checking the
        // current Wi-Fi lease. A lost lease used to report failure but leave the
        // old scan able to accept a late SERVERREPLY.
        set(service, "doneListening", false);
        set(service, "keepListening", true);
        set(service, "mScanRunning", true);
        set(service, "mJoinAddress", teammate);
        set(service, "mBroadcastScan", false);

        service.joinServer();

        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_FAILEDTOJOIN, service.events.get(0).getAction());
        assertFalse(flag("keepListening"));
        assertFalse(flag("mScanRunning"));

        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        assertEquals("A late discovery reply restarted the abandoned join", 1, service.events.size());
        assertNull(Globals.getInstance().mServerIP);
    }

    @Test
    public void emptyNormalizedAddressDoesNotJoinLocalhost() throws Exception {
        service.joinServer("/ ");
        assertEquals(1, service.events.size());
        assertEquals(NetMsg.NETMSG_FAILEDTOJOIN, service.events.get(0).getAction());
        assertEquals(0, service.listenerStarts);
    }

    @Test
    public void cancelledLookupCannotRestartDiscovery() throws Exception {
        synchronized (get(service, "mListenerStateLock")) {
            service.joinServer(teammate.getHostAddress());
            service.endScanning();
        }
        awaitLookups();
        assertEquals(0, service.listenerStarts);
        assertFalse(flag("mScanRunning"));
        assertTrue(service.events.isEmpty());
    }

    @Test
    public void newerJoinSupersedesPendingLookup() throws Exception {
        synchronized (get(service, "mListenerStateLock")) {
            service.joinServer(teammate.getHostAddress());
            service.joinServer(enemy, 17509);
        }
        awaitLookups();
        assertEquals(1, service.listenerStarts);
        receive(teammate, NetMsg.NETMSG_SERVERREPLY);
        assertTrue(service.events.isEmpty());
        receive(enemy, NetMsg.NETMSG_SERVERREPLY);
        assertEquals(enemy, Globals.getInstance().mServerIP);
    }

    @Test
    public void rapidManualJoinsUseOneBoundedLatestLookupQueue() throws Exception {
        service.blockFirstLookup = true;
        service.joinServer(teammate.getHostAddress());
        assertTrue("First lookup did not start", service.lookupStarted.await(2000, TimeUnit.MILLISECONDS));

        for (int count = 0; count < 64; count++)
            service.joinServer(enemy.getHostAddress());

        ThreadPoolExecutor executor = (ThreadPoolExecutor) get(service, "mLookupExecutor");
        assertEquals(1, executor.getPoolSize());
        assertTrue(executor.getQueue().size() <= UDPListenerService.MAX_PENDING_SERVER_LOOKUPS);

        service.releaseLookup.countDown();
        awaitLookups();
        assertEquals(1, service.listenerStarts);
    }

    @Test
    public void serviceRestartCommandCannotUndoAnExplicitStop() throws Exception {
        service.stopListen();
        service.onStartCommand(null, 0, 1);
        assertFalse(flag("keepListening"));
    }

    @Test
    public void actualUdpJoinAndReplyStillWork() throws Exception {
        service.realListener = true;
        try (DatagramSocket host = new DatagramSocket(new InetSocketAddress(teammate, 0))) {
            host.setSoTimeout(3000);
            service.joinServer(teammate, host.getLocalPort());
            DatagramPacket request = new DatagramPacket(new byte[100], 100);
            host.receive(request);
            assertEquals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1",
                    new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8));
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (get(service, "mSocket") == null && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertNotNull(get(service, "mSocket"));
            byte[] reply = (NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY).getBytes(StandardCharsets.UTF_8);
            host.send(new DatagramPacket(reply, reply.length, InetAddress.getByName("127.0.0.1"), 17500));
            deadline = SystemClock.elapsedRealtime() + 2000;
            while (service.events.isEmpty() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertEquals(NetMsg.NETMSG_SERVERREPLY, service.events.get(0).getAction());
            assertEquals(teammate, Globals.getInstance().mServerIP);
            assertFalse(flag("mScanRunning"));
        }
    }

    @Test
    public void closedListenerDoesNotLeaveStaleHostReadiness() throws Exception {
        set(service, "keepListening", true);
        Method method = UDPListenerService.class.getDeclaredMethod("listenForMessage",
                InetAddress.class, Integer.class, Integer.class);
        method.setAccessible(true);
        Thread listener = new Thread(() -> {
            try {
                method.invoke(service, InetAddress.getLoopbackAddress(), 0, 5000);
            } catch (Exception ignored) {
                // Closing the socket below intentionally ends this direct listener.
            }
        }, "UDP readiness regression");
        listener.start();

        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (get(service, "mSocket") == null && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        DatagramSocket socket = (DatagramSocket) get(service, "mSocket");
        assertNotNull("Listener did not bind", socket);
        assertEquals(1, ((Integer) get(service, "mReadyToScan")).intValue());

        socket.close();
        listener.join(2000);
        assertFalse("Closed UDP listener did not exit", listener.isAlive());
        assertNull("Closed listener remained published", get(service, "mSocket"));
        assertEquals("Closed listener remained ready", 0,
                ((Integer) get(service, "mReadyToScan")).intValue());
    }

    @Test
    public void stoppingOldInstanceDoesNotStopCurrentUdpListener() throws Exception {
        service.realListener = true;
        register(teammate, 2);
        service.startListenForUDPMessage();
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (get(service, "mSocket") == null && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        assertNotNull(get(service, "mSocket"));
        RecordingService old = new RecordingService();
        try {
            destroy(old);
            assertTrue(flag("keepListening"));
            try (DatagramSocket sender = new DatagramSocket(new InetSocketAddress(teammate, 0))) {
                byte[] bytes = (NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SHOTFIRED).getBytes(StandardCharsets.UTF_8);
                sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), 17500));
            }
            deadline = SystemClock.elapsedRealtime() + 2000;
            while (service.events.isEmpty() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertEquals(NetMsg.NETMSG_SHOTFIRED, service.events.get(0).getAction());
        } finally {
            destroy(old);
        }
    }

    private CountDownTimer beginJoin(InetAddress address) throws Exception {
        service.joinServer(address, 17509);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        CountDownTimer timer = (CountDownTimer) get(service, "mJoinTimer");
        assertNotNull(timer);
        return timer;
    }

    private void receive(InetAddress sender, String message) throws Exception {
        receiveRaw(sender, NetMsg.MESSAGE_PREFIX + message);
    }

    private void startPeerGame() {
        service.startGame(true, PEER_ROUND_TOKEN);
    }

    private void receiveRaw(InetAddress sender, String message) throws Exception {
        Method method = UDPListenerService.class.getDeclaredMethod("processMessage", InetAddress.class, String.class);
        method.setAccessible(true);
        method.invoke(service, sender, message);
    }

    private boolean flag(String name) throws Exception { return (boolean) get(service, name); }

    private static void register(InetAddress address, int id) {
        Globals.getmIPTeamMapSemaphore();
        try { Globals.getInstance().mIPTeamMap.put(address, (byte) id); }
        finally { Globals.getInstance().mIPTeamMapSemaphore.release(); }
        Globals.getmTeamIPMapSemaphore();
        try { Globals.getInstance().mTeamIPMap.put((byte) id, address); }
        finally { Globals.getInstance().mTeamIPMapSemaphore.release(); }
    }

    private static void clearPlayers() {
        Globals.getmIPTeamMapSemaphore();
        try { Globals.getInstance().mIPTeamMap.clear(); }
        finally { Globals.getInstance().mIPTeamMapSemaphore.release(); }
        Globals.getmTeamIPMapSemaphore();
        try { Globals.getInstance().mTeamIPMap.clear(); }
        finally { Globals.getInstance().mTeamIPMapSemaphore.release(); }
    }

    private static boolean awaitQueued(Semaphore semaphore, long timeout) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!semaphore.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        return semaphore.hasQueuedThreads();
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = UDPListenerService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = UDPListenerService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void runOnMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void awaitLookups() throws Exception {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("SimpleCoil UDP lookup".equals(thread.getName())) {
                thread.join(2000);
                assertFalse("Numeric-address lookup did not finish", thread.isAlive());
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void destroy(UDPListenerService target) { runOnMain(target::onDestroy); }

    /** Pauses exactly one successful acquisition so a lock-order race can be reproduced. */
    private static final class PausingFirstAcquireSemaphore extends Semaphore {
        private final CountDownLatch firstAcquire = new CountDownLatch(1);
        private final CountDownLatch resumeFirstAcquire = new CountDownLatch(1);
        private boolean pauseNextAcquire = true;

        PausingFirstAcquireSemaphore() {
            super(1);
        }

        @Override
        public void acquire() throws InterruptedException {
            super.acquire();
            boolean pause;
            synchronized (this) {
                pause = pauseNextAcquire;
                pauseNextAcquire = false;
            }
            if (pause) {
                firstAcquire.countDown();
                resumeFirstAcquire.await();
            }
        }

        boolean awaitFirstAcquire(long timeout) throws InterruptedException {
            return firstAcquire.await(timeout, TimeUnit.MILLISECONDS);
        }

        void resumeFirstAcquire() {
            resumeFirstAcquire.countDown();
        }
    }

    private static final class RecordingService extends UDPListenerService {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        final List<String> sentMessages = new CopyOnWriteArrayList<>();
        final List<Integer> repeatCounts = new CopyOnWriteArrayList<>();
        final List<String> directMessages = new CopyOnWriteArrayList<>();
        final List<Byte> directRecipients = new CopyOnWriteArrayList<>();
        final List<Integer> directRepeatCounts = new CopyOnWriteArrayList<>();
        final List<String> endpointMessages = new CopyOnWriteArrayList<>();
        final List<InetAddress> endpointRecipients = new CopyOnWriteArrayList<>();
        boolean realListener;
        int listenerStarts;
        boolean blockFirstLookup;
        final CountDownLatch lookupStarted = new CountDownLatch(1);
        final CountDownLatch releaseLookup = new CountDownLatch(1);

        RecordingService() {
            // These parser tests construct the service directly. Give that
            // test double the same application context Android gives a real
            // bound service, including after a join clears its cached IP.
            attachBaseContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        }

        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
        @Override public void sendUDPMessageAllRepeat(String message, int repeatCount) {
            sentMessages.add(message);
            repeatCounts.add(repeatCount);
        }
        @Override public void sendUDPMessageRepeat(String message, Byte playerID, int repeatCount) {
            directMessages.add(message);
            directRecipients.add(playerID);
            directRepeatCounts.add(repeatCount);
        }
        @Override void sendUDPMessage(String message, InetAddress address, Integer port) {
            endpointMessages.add(message);
            endpointRecipients.add(address);
            if (realListener)
                super.sendUDPMessage(message, address, port);
        }
        @Override public void startListenForUDPMessage() {
            if (realListener) super.startListenForUDPMessage();
            else listenerStarts++;
        }
        @Override InetAddress resolveServerAddress(String address) throws java.net.UnknownHostException {
            if (blockFirstLookup) {
                blockFirstLookup = false;
                lookupStarted.countDown();
                try {
                    releaseLookup.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.net.UnknownHostException("Lookup interrupted");
                }
            }
            return super.resolveServerAddress(address);
        }
    }
}
