package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Runs the real TCP client against a loopback server, with no activity receiving broadcasts. */
@RunWith(AndroidJUnit4.class)
public class TcpClientSessionRegressionTest {
    private RecordingClient client;
    private ServerSocket listener;
    private Socket peer;
    private DataInputStream received;
    private InetAddress originalServer;
    private int originalGameState;
    private int originalGrenadePairing;
    private byte originalPairedGrenade;

    @Before
    public void setUp() {
        client = new RecordingClient();
        Globals globals = Globals.getInstance();
        originalServer = globals.mServerIP;
        originalGameState = globals.mGameState;
        originalGrenadePairing = globals.mGrenadePairings[1];
        originalPairedGrenade = globals.mPairedGrenadeID;
        globals.mPairedGrenadeID = 0;
        globals.mGameState = Globals.GAME_STATE_NONE;
    }

    @After
    public void tearDown() throws Exception {
        try {
            client.stopTcpClient();
            if (peer != null) peer.close();
            if (listener != null) listener.close();
            assertTrue("Client worker did not stop", awaitStopped(3000));
        } finally {
            destroy(client);
            Globals globals = Globals.getInstance();
            globals.mServerIP = originalServer;
            globals.mGameState = originalGameState;
            globals.mGrenadePairings[1] = originalGrenadePairing;
            globals.mPairedGrenadeID = originalPairedGrenade;
        }
    }

    @Test
    public void invalidProtocolPrefixCannotStartGame() throws Exception {
        connect();
        String wrongPrefix = TcpServer.TCPMESSAGE_PREFIX.replace('S', 'X');
        send(wrongPrefix + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_STARTGAME, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertFalse(client.actions.contains(NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void incompatibleVersionCannotChangeGrenadePairings() throws Exception {
        connect();
        Globals.getInstance().mGrenadePairings[1] = 1;
        send(NetMsg.MESSAGE_PREFIX + "00" + TcpServer.TCPPREFIX_JSON
                + "{\"grenadepairings\":[]}", TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals(1, Globals.getInstance().mGrenadePairings[1]);
    }

    @Test
    public void malformedEliminationDoesNotDisconnectAHealthySession() throws Exception {
        connect();
        send(control(NetMsg.NETMSG_ELIMINATED + "not-a-number"), TcpServer.TCP_SERVER_PING);
        expectPong();
        assertFalse(client.actions.contains(NetMsg.NETMSG_ELIMINATED));
        assertTrue(flag("keepListening"));
    }

    @Test
    public void stoppedSessionCannotCommitGpsDataAlreadyReadFromTheSocket() throws Exception {
        connect();
        Globals globals = Globals.getInstance();
        Globals.GPSData replacement = new Globals.GPSData();
        replacement.longitude = 30;
        replacement.latitude = 40;
        globals.mGPSDataSemaphore.acquire();
        try {
            send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                    + "{\"gpsupdate\":[],\"gpsfullupdate\":true}");
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (!globals.mGPSDataSemaphore.hasQueuedThreads() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertTrue("Client did not parse the incoming GPS message", globals.mGPSDataSemaphore.hasQueuedThreads());
            client.stopTcpClient();
            // Simulate the replacement session publishing its location while the
            // old TCP reader is still waiting to apply the previous host's data.
            globals.mGPSData.clear();
            globals.mGPSData.put((byte) 4, replacement);
        } finally {
            globals.mGPSDataSemaphore.release();
        }
        try {
            assertTrue("Stopped client kept its reader alive", awaitStopped(2000));
            assertEquals(1, globals.mGPSData.size());
            assertEquals(replacement, globals.mGPSData.get((byte) 4));
            assertFalse(client.actions.contains(NetMsg.NETMSG_GPSDATAUPDATE));
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            Globals.getmGPSDataSemaphore();
            try { globals.mGPSData.clear(); }
            finally { globals.mGPSDataSemaphore.release(); }
        }
    }

    @Test
    public void serverCancellationStopsWithoutAnActivityReceiver() throws Exception {
        assertTerminalMessage(control(NetMsg.NETMSG_SERVERCANCEL), NetMsg.NETMSG_SERVERCANCEL);
    }

    @Test
    public void versionRejectionStopsWithoutAnActivityReceiver() throws Exception {
        assertTerminalMessage(NetMsg.NETMSG_VERSIONERROR, NetMsg.NETMSG_VERSIONERROR);
    }

    @Test
    public void roundEndStopsWithoutAnActivityReceiver() throws Exception {
        assertTerminalMessage(control(NetMsg.NETMSG_ENDGAME), NetMsg.NETMSG_ENDGAME);
    }

    @Test
    public void serviceDestructionStopsTheConnectedWorker() throws Exception {
        connect();
        destroy(client);
        assertTrue("Destroyed service kept its TCP worker running", awaitStopped(1500));
        assertEquals(-1, peer.getInputStream().read());
    }

    @Test
    public void newSessionDoesNotReplayEventsFromAnOldRound() throws Exception {
        client.sendTCPMessage(control(NetMsg.NETMSG_ELIMINATED + "9"), true);
        connect();
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
    }

    @Test
    public void destroyedServiceCannotRestartItsWorker() throws Exception {
        destroy(client);
        client.startTcpClient();
        assertFalse(flag("isListening"));
    }

    @Test
    public void stoppingOldServiceInstanceDoesNotStopCurrentSession() throws Exception {
        connect();
        RecordingClient oldInstance = new RecordingClient();
        try {
            oldInstance.stopTcpClient();
            assertTrue(flag("keepListening"));
            send(TcpServer.TCP_SERVER_PING);
            expectPong();
        } finally {
            destroy(oldInstance);
        }
    }

    @Test
    public void stoppingDedicatedClientClearsDedicatedModeForTheNextLobby() throws Exception {
        Field dedicated = TcpClient.class.getDeclaredField("mIsDedicatedServer");
        dedicated.setAccessible(true);
        dedicated.setBoolean(client, true);

        client.stopTcpClient();

        assertFalse("A stopped dedicated session affected the next lobby", client.isDedicatedServer());
    }

    @Test
    public void registeredProtocolStillDeliversGameEvents() throws Exception {
        connect();
        Field dedicated = TcpClient.class.getDeclaredField("mIsDedicatedServer");
        dedicated.setAccessible(true);
        dedicated.set(client, true);
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                        + TcpServer.createStartInfo(1, SystemClock.elapsedRealtime() + 10000, 60000),
                control(NetMsg.NETMSG_ELIMINATED + "9"),
                control(NetMsg.NETMSG_TEAMELIMINATED), TcpServer.TCP_SERVER_PING);
        expectPong();
        assertTrue(client.actions.contains(NetMsg.NETMSG_STARTGAME));
        assertTrue(client.actions.contains(NetMsg.NETMSG_ELIMINATED));
        assertTrue(client.actions.contains(NetMsg.NETMSG_TEAMELIMINATED));
    }

    @Test
    public void reconnectRemainsBoundToTheOriginalServer() throws Exception {
        connect();
        Globals.getInstance().mServerIP = InetAddress.getByAddress(new byte[]{127, 0, 0, 2});
        // Simulate write failure after discovery has updated the shared address.
        Field output = TcpClient.class.getDeclaredField("out");
        output.setAccessible(true);
        output.set(client, null);
        Socket replacement = listener.accept();
        peer.close();
        peer = replacement;
        peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
        assertTrue(received.readUTF().contains(TcpServer.JSON_REJOIN));
        expectGrenadePairing(0);
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
    }

    @Test
    public void reconnectSynchronizesGrenadeUnpairedDuringConnectionLoss() throws Exception {
        Globals.getInstance().mPairedGrenadeID = 3;
        connect();
        Globals.getInstance().mPairedGrenadeID = 0;
        Field output = TcpClient.class.getDeclaredField("out");
        output.setAccessible(true);
        output.set(client, null);
        Socket replacement = listener.accept();
        peer.close();
        peer = replacement;
        peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
        assertTrue(received.readUTF().contains(TcpServer.JSON_REJOIN));
        expectGrenadePairing(0);
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
    }

    @Test
    public void missingServerAddressDoesNotStartAWorker() throws Exception {
        Globals.getInstance().mServerIP = null;
        client.startTcpClient();
        assertFalse(flag("isListening"));
    }

    @Test
    public void destructionInterruptsStartupWaitingForSharedState() throws Exception {
        Globals globals = Globals.getInstance();
        globals.mServerIP = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        globals.mGPSDataSemaphore.acquire();
        try {
            client.startTcpClient();
            destroy(client);
            assertTrue("Destroyed service remained blocked during startup", awaitStopped(1500));
            assertEquals(0, globals.mGPSDataSemaphore.availablePermits());
        } finally {
            globals.mGPSDataSemaphore.release();
        }
        assertEquals(1, globals.mGPSDataSemaphore.availablePermits());
    }

    @Test
    public void leaveFlushesQueuedScoreBeforeClosingConnection() throws Exception {
        connect();
        CountDownLatch releaseSender = blockSender();
        try {
            client.sendTCPMessage(control(NetMsg.NETMSG_ELIMINATED + "9"), true);
            client.leaveServer();
            assertFalse("Connection closed before queued events could be sent", awaitStopped(350));
        } finally {
            releaseSender.countDown();
        }
        assertEquals(control(NetMsg.NETMSG_ELIMINATED + "9"), received.readUTF());
        assertEquals(control(NetMsg.NETMSG_LEAVE), received.readUTF());
        assertTrue(awaitStopped(1500));
    }

    @Test
    public void explicitStopFlushesPendingEndGameCommand() throws Exception {
        connect();
        CountDownLatch releaseSender = blockSender();
        try {
            client.sendTCPMessage(control(NetMsg.NETMSG_ENDGAME));
            client.stopTcpClient();
            assertFalse("Pending end-game command was abandoned", awaitStopped(350));
        } finally {
            releaseSender.countDown();
        }
        assertEquals(control(NetMsg.NETMSG_ENDGAME), received.readUTF());
        assertTrue(awaitStopped(1500));
    }

    @Test
    public void stopHasBoundedWaitForAStuckSender() throws Exception {
        connect();
        CountDownLatch releaseSender = blockSender();
        try {
            client.stopTcpClient();
            assertTrue("Stop waited indefinitely for sender", awaitStopped(2000));
            assertEquals(-1, peer.getInputStream().read());
        } finally {
            releaseSender.countDown();
        }
    }

    @Test
    public void serverCancellationDoesNotWaitForQueuedOutgoingEvents() throws Exception {
        connect();
        CountDownLatch releaseSender = blockSender();
        try {
            send(control(NetMsg.NETMSG_SERVERCANCEL));
            assertTrue(awaitStopped(800));
        } finally {
            releaseSender.countDown();
        }
    }

    @Test
    public void stopInterruptsStartupWaitingForSharedState() throws Exception {
        Globals globals = Globals.getInstance();
        globals.mServerIP = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        globals.mGPSDataSemaphore.acquire();
        try {
            client.startTcpClient();
            client.stopTcpClient();
            assertTrue("Stopped client remained blocked during startup", awaitStopped(1500));
            assertEquals(0, globals.mGPSDataSemaphore.availablePermits());
        } finally {
            globals.mGPSDataSemaphore.release();
        }
    }

    @Test
    public void terminalEventIsRetainedUntilConsumedExactlyOnce() throws Exception {
        assertTerminalMessage(control(NetMsg.NETMSG_ENDGAME), NetMsg.NETMSG_ENDGAME);
        Intent notification = client.notifications.get(client.notifications.size() - 1);
        long eventId = notification.getLongExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID, 0);
        assertTrue(eventId > 0);
        assertNull(client.consumePendingTerminalEvent(eventId + 1));
        Intent pending = client.consumePendingTerminalEvent(eventId);
        assertNotNull(pending);
        assertEquals(NetMsg.NETMSG_ENDGAME, pending.getAction());
        assertFalse(pending.hasExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID));
        assertNull(client.consumePendingTerminalEvent());
        assertNull(client.consumePendingTerminalEvent(eventId));
    }

    @Test
    public void terminalEventAlreadyReadBeforeManualStopIsIgnored() throws Exception {
        connect();
        client.stopTcpClient();
        Method finish = TcpClient.class.getDeclaredMethod("finishServerSession", String.class);
        finish.setAccessible(true);
        finish.invoke(client, NetMsg.NETMSG_ENDGAME);
        assertFalse("A closed session published a stale terminal event",
                client.actions.contains(NetMsg.NETMSG_ENDGAME));
        assertNull(client.consumePendingTerminalEvent());
    }

    @Test
    public void newSessionDiscardsUnconsumedTerminalEvent() throws Exception {
        assertTerminalMessage(control(NetMsg.NETMSG_SERVERCANCEL), NetMsg.NETMSG_SERVERCANCEL);
        Intent notification = client.notifications.get(client.notifications.size() - 1);
        long oldId = notification.getLongExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID, 0);
        peer.close();
        client.startTcpClient();
        peer = listener.accept();
        peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
        assertTrue(received.readUTF().startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        expectGrenadePairing(0);
        assertNull(client.consumePendingTerminalEvent(oldId));
        assertNull(client.consumePendingTerminalEvent());
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
    }

    @Test
    public void destroyedServiceDiscardsUnconsumedTerminalEvent() throws Exception {
        assertTerminalMessage(control(NetMsg.NETMSG_ENDGAME), NetMsg.NETMSG_ENDGAME);
        destroy(client);
        assertNull(client.consumePendingTerminalEvent());
    }

    private CountDownLatch blockSender() throws Exception {
        Field field = TcpClient.class.getDeclaredField("sendExecutor");
        field.setAccessible(true);
        ExecutorService sender = (ExecutorService) field.get(client);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sender.execute(() -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        return release;
    }

    private void connect() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(loopback, TcpServer.TCP_SERVER_PORT));
        listener.setSoTimeout(3000);
        Globals.getInstance().mServerIP = loopback;
        client.startTcpClient();
        peer = listener.accept();
        peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
        assertTrue(received.readUTF().startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        expectGrenadePairing(Globals.getInstance().mPairedGrenadeID);
    }

    private void expectGrenadePairing(int expected) throws Exception {
        String message = received.readUTF();
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        JSONObject pairing = new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                + TcpServer.TCPPREFIX_JSON.length()));
        assertEquals(expected, pairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID));
        assertEquals(Globals.getInstance().mPlayerID, pairing.getInt(TcpServer.JSON_PLAYERID));
        synchronizeClock();
    }

    private void synchronizeClock() throws Exception {
        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++) {
            String frame = received.readUTF();
            long receivedAt = SystemClock.elapsedRealtime();
            JSONObject request = new JSONObject(frame.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                    + TcpServer.TCPPREFIX_JSON.length()));
            request.put(TcpServer.JSON_CLOCK_RECEIVE, receivedAt)
                    .put(TcpServer.JSON_CLOCK_SEND, SystemClock.elapsedRealtime());
            send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + request);
        }
        assertTrue(received.readUTF().contains(TcpServer.JSON_CLOCK_READY));
        assertTrue(client.isClockSynchronized());
    }

    @Test
    public void startWithoutDeadlineIsIgnoredBySynchronizedProtocol() throws Exception {
        connect();
        send(control(NetMsg.NETMSG_STARTGAME), TcpServer.TCP_SERVER_PING);
        expectPong();
        assertFalse(client.actions.contains(NetMsg.NETMSG_STARTGAME));
    }

    @Test
    public void peerStartStopsReconnectAndSurvivesUntilPausedActivityResumes() throws Exception {
        connect();
        long startAt = SystemClock.elapsedRealtime() + 10000;
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                + TcpServer.createStartInfo(1, startAt, 60000));
        assertTrue("Peer start tried to reconnect without an activity", awaitStopped(1500));
        assertEquals(-1, peer.getInputStream().read());
        Intent pending = client.consumePendingGameStart(0);
        assertNotNull(pending);
        assertEquals(NetMsg.NETMSG_STARTGAME, pending.getAction());
        // Real loopback sampling has scheduler jitter, but must preserve the deadline.
        assertTrue(Math.abs(startAt - pending.getLongExtra(NetMsg.INTENT_START_AT, 0)) < 250);
        assertFalse(client.actions.contains(NetMsg.NETMSG_SERVERCANCEL));
    }

    private void send(String... messages) throws Exception {
        DataOutputStream output = new DataOutputStream(peer.getOutputStream());
        for (String message : messages) output.writeUTF(message);
        output.flush();
    }

    private void expectPong() throws Exception {
        assertEquals(TcpClient.TCP_CLIENT_PONG, received.readUTF());
    }

    private void assertTerminalMessage(String message, String action) throws Exception {
        connect();
        send(message);
        assertTrue("Client reconnected after " + action, awaitStopped(1500));
        assertTrue(client.actions.contains(action));
        assertFalse(client.actions.contains(NetMsg.NETMSG_NETWORKCONNECTED));
    }

    private boolean awaitStopped(long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (flag("isListening") && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        return !flag("isListening");
    }

    private boolean flag(String name) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(client);
    }

    private static String control(String message) {
        return TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + message;
    }

    private static void destroy(TcpClient client) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::onDestroy);
    }

    private static final class RecordingClient extends TcpClient {
        final List<String> actions = new CopyOnWriteArrayList<>();
        final List<Intent> notifications = new CopyOnWriteArrayList<>();
        @Override public void sendBroadcast(Intent intent) {
            notifications.add(new Intent(intent));
            actions.add(intent.getAction());
        }
        // This fixture exercises lifecycle cleanup without registering a real receiver.
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { }
    }
}
