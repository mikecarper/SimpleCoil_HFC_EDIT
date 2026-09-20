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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Tests the real UDP parser and discovery callbacks, plus real listener lifetime. */
@RunWith(AndroidJUnit4.class)
public class UDPRegressionTest {
    private RecordingService service;
    private InetAddress teammate;
    private InetAddress enemy;
    private InetAddress stranger;
    private int originalGameState;
    private int originalGameMode;
    private byte originalPlayerID;
    private InetAddress originalServer;

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
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mPlayerID = 1;
        globals.mServerIP = null;
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
    public void fixedCommandsRejectTrailingGarbageWithoutRemovingPlayer() throws Exception {
        register(teammate, 2);
        String[] commands = {NetMsg.NETMSG_SHOTFIRED, NetMsg.NETMSG_HIT, NetMsg.NETMSG_OUT,
                NetMsg.NETMSG_ELIMINATED, NetMsg.NETMSG_LEAVE, NetMsg.NETMSG_ENDGAME,
                NetMsg.NETMSG_TEAMELIMINATED, NetMsg.NETMSG_SAMETEAM, NetMsg.NETMSG_ERROR};
        for (String command : commands) receive(teammate, command + "garbage");
        assertTrue(service.events.isEmpty());
        assertEquals(Byte.valueOf((byte) 2), Globals.getInstance().mIPTeamMap.get(teammate));
    }

    @Test
    public void validHitOutAndEliminationKeepTheirPlayerIds() throws Exception {
        register(enemy, 11);
        String[] commands = {NetMsg.NETMSG_HIT, NetMsg.NETMSG_OUT, NetMsg.NETMSG_ELIMINATED};
        for (String command : commands) receive(enemy, command);
        assertEquals(3, service.events.size());
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

    private static final class RecordingService extends UDPListenerService {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        boolean realListener;
        int listenerStarts;
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
        @Override public void startListenForUDPMessage() {
            if (realListener) super.startListenForUDPMessage();
            else listenerStarts++;
        }
    }
}
