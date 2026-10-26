package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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
    private com.simplecoil.protocol.GpsOffset originalGpsOffset;
    private Globals.LocalGpsFix originalLocalGpsFix;
    private boolean originalUseGps;
    private java.util.Map<Byte, Globals.GPSData> originalLocations;
    private java.util.Map<Field, Object> seatTestGlobals;

    @Before
    public void setUp() {
        client = new RecordingClient();
        Globals globals = Globals.getInstance();
        originalServer = globals.mServerIP;
        originalGameState = globals.mGameState;
        originalGrenadePairing = globals.mGrenadePairings[1];
        originalPairedGrenade = globals.mPairedGrenadeID;
        originalGpsOffset = globals.mGpsOffset;
        originalLocalGpsFix = globals.mLocalGpsFix;
        originalUseGps = globals.mUseGPS;
        Globals.getmGPSDataSemaphore();
        try {
            originalLocations = new java.util.HashMap<>(globals.mGPSData);
            globals.mGPSData.clear();
        } finally { globals.mGPSDataSemaphore.release(); }
        globals.mGpsOffset = null;
        globals.mLocalGpsFix = null;
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
            if (seatTestGlobals != null)
                for (java.util.Map.Entry<Field, Object> saved : seatTestGlobals.entrySet())
                    saved.getKey().set(globals, saved.getValue());
            globals.mServerIP = originalServer;
            globals.mGameState = originalGameState;
            globals.mGrenadePairings[1] = originalGrenadePairing;
            globals.mPairedGrenadeID = originalPairedGrenade;
            globals.mGpsOffset = originalGpsOffset;
            globals.mLocalGpsFix = originalLocalGpsFix;
            globals.mUseGPS = originalUseGps;
            Globals.getmGPSDataSemaphore();
            try {
                globals.mGPSData.clear();
                globals.mGPSData.putAll(originalLocations);
            } finally { globals.mGPSDataSemaphore.release(); }
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
    public void admittedLobbyKeepsRetryingUntilTheHostOrRelayReturns() throws Exception {
        connect();
        InetAddress host = Globals.getInstance().mServerIP;
        assertTrue(client.hasClientSession(host));
        assertFalse(client.hasClientSession(InetAddress.getByName("127.0.0.2")));
        listener.close();
        peer.close();
        // Exceed the old seven-second heartbeat window plus three join attempts.
        SystemClock.sleep(12000);
        assertTrue("Admitted client stopped before the fallback could become ready", flag("keepListening"));
        assertTrue("Lobby lost its selected host while reconnecting", client.hasClientSession(host));
        assertFalse(client.actions.contains(NetMsg.NETMSG_SERVERUNREACHABLE));
        assertFalse(client.actions.contains(NetMsg.NETMSG_SERVERCANCEL));
        assertFalse(client.actions.contains(NetMsg.NETMSG_ENDGAME));
        assertNull(client.consumePendingTerminalEvent());
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", TcpServer.TCP_SERVER_PORT));
        listener.setSoTimeout(5000);
        peer = listener.accept();peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
        String registration = received.readUTF();
        assertTrue("Client did not rejoin its original session", registration.contains("\"rejoin\":true"));
        assertTrue(received.readUTF().contains(TcpServer.JSON_PLAYERSETTINGS));
        assertTrue(received.readUTF().contains(TcpServer.JSON_PAIRED_GRENADE_ID));
        synchronizeClock();
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
        client.stopTcpClient();
        assertFalse("A stopped session retained the lobby host", client.hasClientSession(host));
    }

    @Test public void relayedSessionSurvivesAQuietSixSecondBluetoothInterval() throws Exception {
        withRelayConnection(relayed -> {
            synchronizeClock();
            Field dedicated = TcpClient.class.getDeclaredField("mIsDedicatedServer");
            dedicated.setAccessible(true);dedicated.setBoolean(client, true);
            // Keep the real virtual stream open beyond both the old dedicated
            // heartbeat window (3.5 s) and the old virtual read timeout (5 s).
            SystemClock.sleep(6000);
            assertFalse("A short Bluetooth pause discarded the game stream",
                    client.actions.contains(NetMsg.NETMSG_NETWORKDISCONNECTED));
            assertFalse(relayed.isClosed());
            send(TcpServer.TCP_SERVER_PING);
            // Clock maintenance may already be queued ahead of the pong.
            // Answer it on this same stream rather than treating a legitimate
            // probe as a reconnect or depending on its scheduling interval.
            long pongDeadline = SystemClock.elapsedRealtime() + 3000;
            boolean pong = false;
            while (!pong && SystemClock.elapsedRealtime() < pongDeadline) {
                peer.setSoTimeout((int) Math.max(1, pongDeadline - SystemClock.elapsedRealtime()));
                String frame = received.readUTF();
                if (TcpClient.TCP_CLIENT_PONG.equals(frame)) {
                    pong = true;
                } else {
                    String prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
                    assertTrue("Unexpected frame while awaiting relayed pong", frame.startsWith(prefix));
                    JSONObject request = new JSONObject(frame.substring(prefix.length()));
                    assertTrue("The quiet relay unexpectedly registered a new session",
                            request.has(TcpServer.JSON_CLOCK_REQUEST));
                    send(prefix + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST,
                                    request.getLong(TcpServer.JSON_CLOCK_REQUEST))
                            .put(TcpServer.JSON_CLOCK_RECEIVE, SystemClock.elapsedRealtime())
                            .put(TcpServer.JSON_CLOCK_SEND, SystemClock.elapsedRealtime()));
                }
            }
            assertTrue("The existing relay stream did not return a pong", pong);
            assertFalse("The relay reconnected while answering the ping",
                    client.actions.contains(NetMsg.NETMSG_NETWORKDISCONNECTED));
            client.stopTcpClient();
            assertTrue("Relayed client did not stop", awaitStopped(2000));
        });
    }

    @Test public void relayedClockCalibratesWithTwelveActualSamplesDelayedOverHalfASecond() throws Exception {
        withRelayConnection(relayed -> {
            Field field = TcpClient.class.getDeclaredField("mGameClock");
            field.setAccessible(true);
            GameClock clock = (GameClock) field.get(client);
            JSONObject request = readClientJson();
            replyToDelayedClockProbe(request, 950);
            // Receiving another request proves the first reply was processed.
            request = readClientJson();
            assertEquals("An over-800 ms relay sample was accepted", 0, clock.samples());
            assertFalse(client.isClockSynchronized());
            for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++) {
                assertTrue("Expected a real relay clock probe", request.has(TcpServer.JSON_CLOCK_REQUEST));
                // Exceed the native pending-request timeout as well as its
                // 80 ms uncertainty limit. This is a real virtual socket exchange.
                replyToDelayedClockProbe(request, 550);
                JSONObject next = readClientJson();
                assertEquals(sample + 1, clock.samples());
                if (sample + 1 < GameClock.SAMPLES_PER_SYNC) {
                    assertFalse("Relay announced readiness before twelve samples", client.isClockSynchronized());
                    assertTrue(next.has(TcpServer.JSON_CLOCK_REQUEST));
                } else {
                    assertTrue("Relay did not announce a fresh completed calibration",
                            next.optBoolean(TcpServer.JSON_CLOCK_READY));
                    assertTrue(client.isClockSynchronized());
                }
                request = next;
            }
            assertFalse("Clock calibration replaced the virtual game connection", relayed.isClosed());
            assertFalse(client.actions.contains(NetMsg.NETMSG_NETWORKDISCONNECTED));
            client.stopTcpClient();
            assertFalse("An explicit stop retained relay clock readiness", client.isClockSynchronized());
        });
    }

    private void replyToDelayedClockProbe(JSONObject request, long delayMs) throws Exception {
        assertTrue(request.has(TcpServer.JSON_CLOCK_REQUEST));
        long hostTimestamp = SystemClock.elapsedRealtime();
        // Keep host processing at zero so the delay counts as network time.
        SystemClock.sleep(delayMs);
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, request.getLong(TcpServer.JSON_CLOCK_REQUEST))
                .put(TcpServer.JSON_CLOCK_RECEIVE, hostTimestamp)
                .put(TcpServer.JSON_CLOCK_SEND, hostTimestamp));
    }

    private interface RelayScenario { void run(Socket socket) throws Exception; }

    private void withRelayConnection(RelayScenario scenario) throws Exception {
        java.util.UUID phone = java.util.UUID.randomUUID(), host = java.util.UUID.randomUUID();
        InetAddress phoneAddress = InetAddress.getByName("10.80.0.2"), hostAddress = InetAddress.getByName("10.80.0.1");
        java.util.Map<java.util.UUID, com.simplecoil.protocol.RelayConfig.Member> members = new java.util.LinkedHashMap<>();
        members.put(phone, new com.simplecoil.protocol.RelayConfig.Member(phone, phoneAddress, ""));
        members.put(host, new com.simplecoil.protocol.RelayConfig.Member(host, hostAddress, ""));
        com.simplecoil.protocol.RelayConfig config = new com.simplecoil.protocol.RelayConfig(
                java.util.UUID.randomUUID(), host, host, members);
        LinkedBlockingQueue<Socket> accepted = new LinkedBlockingQueue<>();
        com.simplecoil.protocol.RelayMesh.Listener receiver = new com.simplecoil.protocol.RelayMesh.Listener() {
            @Override public void datagram(InetAddress from, InetAddress to, int port, byte[] bytes) { }
            @Override public boolean accept(Socket socket, int port) { return accepted.offer(socket); }
        };
        try (com.simplecoil.protocol.RelayMesh hostMesh = new com.simplecoil.protocol.RelayMesh(host, config,
                new InetSocketAddress("127.0.0.1", 0), receiver);
             com.simplecoil.protocol.RelayMesh phoneMesh = new com.simplecoil.protocol.RelayMesh(phone, config,
                     new InetSocketAddress("127.0.0.1", 0), receiver)) {
            phoneMesh.connect(new InetSocketAddress("127.0.0.1", hostMesh.port()));
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (!phoneMesh.hostReachable() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
            assertTrue("Test relay did not form", phoneMesh.hostReachable());
            Socket relayed = phoneMesh.open(hostAddress, TcpServer.TCP_SERVER_PORT, 3000);
            peer = accepted.poll(3, TimeUnit.SECONDS);
            assertNotNull(peer);
            peer.setSoTimeout(3000);
            received = new DataInputStream(peer.getInputStream());
            Globals.getInstance().mServerIP = hostAddress;
            client.suppliedGameSocket = relayed;
            client.startTcpClient();
            assertTrue(received.readUTF().contains(TcpServer.JSON_PLAYERNAME));
            expectPlayerConfiguration(0, Globals.getInstance().mPlayerID);
            try { scenario.run(relayed); }
            finally {
                client.stopTcpClient();
                assertTrue("Relayed client did not stop", awaitStopped(2000));
            }
        }
    }

    @Test public void nativeDedicatedSessionKeepsItsFastHeartbeatTimeout() throws Exception {
        connect();
        Field dedicated = TcpClient.class.getDeclaredField("mIsDedicatedServer");
        dedicated.setAccessible(true);dedicated.setBoolean(client, true);
        listener.setSoTimeout(5500);
        try (Socket reconnected = listener.accept()) {
            reconnected.setSoTimeout(2000);
            assertTrue("Native Wi-Fi failure detection was extended to the relay window",
                    new DataInputStream(reconnected.getInputStream()).readUTF().contains("\"rejoin\":true"));
        }
    }

    @Test public void initialUnreachableHostStillReturnsToDiscovery() throws Exception {
        listener = new ServerSocket();listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", TcpServer.TCP_SERVER_PORT));listener.close();
        Globals.getInstance().mServerIP = InetAddress.getByName("127.0.0.1");
        client.startTcpClient();
        assertTrue("Initial join never returned to discovery", awaitStopped(6000));
        assertTrue(client.actions.contains(NetMsg.NETMSG_SERVERUNREACHABLE));
        Intent pending = client.consumePendingTerminalEvent();
        assertNotNull(pending);
        assertEquals(NetMsg.NETMSG_SERVERUNREACHABLE, pending.getAction());
        assertNull(client.consumePendingTerminalEvent());
    }

    @Test
    public void clockBurstSeedsGpsOnceAndReconnectSeedsAgainWithinFiveSeconds() throws Exception {
        Globals globals = Globals.getInstance();
        globals.mUseGPS = true;
        globals.mLocalGpsFix = new Globals.LocalGpsFix(47.62, -122.10, SystemClock.elapsedRealtime());
        connect(false);
        assertClockBurstHasOneGpsSeed();
        assertNull("The fake host must leave the origin missing throughout the first burst", globals.mGpsOffset);

        // A reconnect must still seed its first request when the previous
        // connection already learned an origin, then omit the eleven repeats.
        globals.mGpsOffset = com.simplecoil.protocol.GpsOffset.create(47.62, -122.10);
        globals.mLocalGpsFix = new Globals.LocalGpsFix(47.62, -122.10, SystemClock.elapsedRealtime());
        reconnectAfterWriterFailure();
        assertTrue(readClientJson().getBoolean(TcpServer.JSON_REJOIN));
        expectPlayerConfiguration(globals.mPairedGrenadeID, globals.mPlayerID);
        assertClockBurstHasOneGpsSeed();
    }

    private void assertClockBurstHasOneGpsSeed() throws Exception {
        long started = SystemClock.elapsedRealtime();
        for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++) {
            JSONObject request = readClientJson();
            assertTrue(request.has(TcpServer.JSON_CLOCK_REQUEST));
            assertEquals("GPS fix was repeated within the clock burst", sample == 0,
                    request.has(TcpServer.JSON_GPSLATITUDE));
            assertEquals(sample == 0, request.has(TcpServer.JSON_GPSLONGITUDE));
            assertEquals(sample == 0, request.has(TcpServer.JSON_GPS_FIX_AGE));
            if (sample == 0) {
                assertEquals(47.62, request.getDouble(TcpServer.JSON_GPSLATITUDE), 0);
                assertEquals(-122.10, request.getDouble(TcpServer.JSON_GPSLONGITUDE), 0);
            }
            long receivedAt = SystemClock.elapsedRealtime();
            send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                    + new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST,
                            request.getLong(TcpServer.JSON_CLOCK_REQUEST))
                    .put(TcpServer.JSON_CLOCK_RECEIVE, receivedAt)
                    .put(TcpServer.JSON_CLOCK_SEND, SystemClock.elapsedRealtime()));
        }
        assertTrue(readClientJson().getBoolean(TcpServer.JSON_CLOCK_READY));
        assertTrue(client.isClockSynchronized());
        assertTrue("Twelve low-delay clock exchanges took five seconds",
                SystemClock.elapsedRealtime() - started < 5000);
    }

    @Test
    public void staleAndDuplicateClockRepliesCannotAdvanceCalibration() throws Exception {
        Field field = TcpClient.class.getDeclaredField("mGameClock");
        field.setAccessible(true);
        GameClock clock = (GameClock) field.get(client);
        Field pending = TcpClient.class.getDeclaredField("mPendingClockRequest");
        pending.setAccessible(true);
        Field generation = TcpClient.class.getDeclaredField("mSessionGeneration");
        generation.setAccessible(true);
        long currentGeneration = generation.getLong(client);
        Method receive = TcpClient.class.getDeclaredMethod("receiveClockSync", JSONObject.class,
                long.class, long.class);
        receive.setAccessible(true);
        pending.setLong(client, 1000);
        JSONObject reply = new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, 999)
                .put(TcpServer.JSON_CLOCK_RECEIVE, 2010).put(TcpServer.JSON_CLOCK_SEND, 2010);
        receive.invoke(client, reply, 1020L, currentGeneration);
        assertEquals(0, clock.samples());
        assertEquals(1000, pending.getLong(client));
        reply.put(TcpServer.JSON_CLOCK_REQUEST, 1000);
        receive.invoke(client, reply, 1020L, currentGeneration + 1);
        assertEquals("An obsolete session advanced the current clock", 0, clock.samples());
        assertEquals(1000, pending.getLong(client));
        receive.invoke(client, reply, 1020L, currentGeneration);
        assertEquals(1, clock.samples());
        assertEquals(-1, pending.getLong(client));
        receive.invoke(client, reply, 1030L, currentGeneration);
        assertEquals("A duplicate clock reply counted as a second sample", 1, clock.samples());
        assertFalse(client.isClockSynchronized());
    }

    @Test
    public void lostClockProbeRetriesWithoutWaitingFiveSeconds() throws Exception {
        connect();
        synchronized (client) {
            Field clock = TcpClient.class.getDeclaredField("mGameClock");
            clock.setAccessible(true);
            ((GameClock) clock.get(client)).beginSampling();
            Field pending = TcpClient.class.getDeclaredField("mPendingClockRequest");
            pending.setAccessible(true);
            pending.setLong(client, SystemClock.elapsedRealtime() - 600);
            Method request = TcpClient.class.getDeclaredMethod("requestClockSync");
            request.setAccessible(true);
            request.invoke(client);
        }
        peer.setSoTimeout(1000);
        assertTrue("Lost clock probe did not retry promptly",
                received.readUTF().contains(TcpServer.JSON_CLOCK_REQUEST));
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

    @Test public void reconnectAndHostRosterKeepTheRegisteredSeatAfterStaleGunStatus() throws Exception {
        preserveSeatTestGlobals();
        Globals globals = Globals.getInstance();
        globals.mPlayerID = 1;
        globals.mPlayerName = "Steph";
        connect();
        // Reproduce a delayed blaster packet reporting its previous lobby ID.
        globals.mPlayerID = 17;
        client.sendPlayerSettings();
        client.sendPlayerNameChange();
        client.sendPlayerGrenade();
        assertEquals(1, readClientJson().getInt(TcpServer.JSON_PLAYERID));
        assertEquals(1, readClientJson().getInt(TcpServer.JSON_PLAYERID));
        assertEquals(1, readClientJson().getInt(TcpServer.JSON_PLAYERID));
        reconnectAfterWriterFailure();
        JSONObject registration = readClientJson();
        assertTrue(registration.getBoolean(TcpServer.JSON_REJOIN));
        assertEquals("Reconnect changed the admitted player seat", 1, registration.getInt(TcpServer.JSON_PLAYERID));
        expectGrenadePairing(0, 1);
        assertEquals("Registration must not repair UI state before host acknowledgement", 17, globals.mPlayerID);

        JSONObject settings = new JSONObject().put(TcpServer.JSON_PLAYERSETTINGS,
                new JSONArray().put(seatSettings(1, 12)).put(seatSettings(17, 27)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false).put(TcpServer.JSON_TOURNAMENT_MODE, false);
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + settings, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals("Settings-only updates used the stale hardware ID", 12, globals.mFullHealth);
        assertEquals(17, globals.mPlayerID);

        JSONObject roster = seatRoster();
        JSONObject invalidRoster = new JSONObject(roster.toString()).put(TcpServer.JSON_LIMITS, "invalid");
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + invalidRoster, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals("An invalid roster repaired the local player ID", 17, globals.mPlayerID);
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + roster, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals(1, globals.mPlayerID);
        assertEquals("Another player's name was adopted from the roster", "Steph", globals.mPlayerName);
        assertEquals(12, globals.mFullHealth);
        assertFalse(globals.mTeamIPMap.containsKey((byte) 1));
        assertTrue(globals.mTeamIPMap.containsKey((byte) 17));
        assertEquals("Wild wes", globals.mTeamPlayerNameMap.get((byte) 17));

        globals.mPlayerID = 17;
        JSONObject gps = new JSONObject().put(TcpServer.JSON_GPSFULLUPDATE, true)
                .put(TcpServer.JSON_GPSUPDATE, new JSONArray().put(seatGps(1)).put(seatGps(17)));
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + gps, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertFalse("Self GPS was decoded as a remote player", globals.mGPSData.containsKey((byte) 1));
        assertTrue("Another player's GPS was discarded as self", globals.mGPSData.containsKey((byte) 17));
    }

    @Test public void validatedLiveJoinAssignmentRebindsFutureReconnects() throws Exception {
        preserveSeatTestGlobals();
        Globals globals = Globals.getInstance();
        globals.mPlayerID = 1;
        globals.mRequestedJoinTeam = 2;
        connect();
        JSONObject assignment = new JSONObject().put(LiveJoin.ASSIGNED, 17)
                .put(TcpServer.JSON_ROUND_TOKEN, java.util.UUID.randomUUID().toString());
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + assignment, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals(17, globals.mPlayerID);
        assertEquals(0, globals.mRequestedJoinTeam);
        // An unsolicited second assignment must not change that binding.
        assignment.put(LiveJoin.ASSIGNED, 2);
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + assignment, TcpServer.TCP_SERVER_PING);
        expectPong();
        assertEquals(17, globals.mPlayerID);
        globals.mPlayerID = 1;
        reconnectAfterWriterFailure();
        JSONObject registration = readClientJson();
        assertTrue(registration.getBoolean(TcpServer.JSON_REJOIN));
        assertEquals("Live join did not update the session's registered seat", 17,
                registration.getInt(TcpServer.JSON_PLAYERID));
        expectGrenadePairing(0, 17);
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
        connect(true);
    }

    private void connect(boolean synchronize) throws Exception {
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
        expectPlayerConfiguration(Globals.getInstance().mPairedGrenadeID, Globals.getInstance().mPlayerID);
        if (synchronize)
            synchronizeClock();
    }

    private void expectGrenadePairing(int expected) throws Exception {
        expectGrenadePairing(expected, Globals.getInstance().mPlayerID);
    }

    private void expectGrenadePairing(int expected, int expectedPlayerID) throws Exception {
        expectPlayerConfiguration(expected, expectedPlayerID);
        synchronizeClock();
    }

    private void expectPlayerConfiguration(int expected, int expectedPlayerID) throws Exception {
        // Registration republishes both the player's saved settings and their
        // grenade pairing. Assert the settings frame explicitly rather than
        // treating it as a malformed grenade report.
        String settingsMessage = received.readUTF();
        assertTrue(settingsMessage.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        JSONObject settings = new JSONObject(settingsMessage.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                + TcpServer.TCPPREFIX_JSON.length()));
        assertTrue(settings.getBoolean(TcpServer.JSON_PLAYERSETTINGS));
        assertEquals(expectedPlayerID, settings.getInt(TcpServer.JSON_PLAYERID));

        String message = received.readUTF();
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        JSONObject pairing = new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                + TcpServer.TCPPREFIX_JSON.length()));
        assertEquals(expected, pairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID));
        assertEquals(expectedPlayerID, pairing.getInt(TcpServer.JSON_PLAYERID));
    }

    private void reconnectAfterWriterFailure() throws Exception {
        Field output = TcpClient.class.getDeclaredField("out");
        output.setAccessible(true);output.set(client, null);
        Socket replacement = listener.accept();
        peer.close();peer = replacement;peer.setSoTimeout(3000);
        received = new DataInputStream(peer.getInputStream());
    }

    private JSONObject readClientJson() throws Exception {
        String prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
        String frame = received.readUTF();
        assertTrue("Expected a client JSON frame", frame.startsWith(prefix));
        return new JSONObject(frame.substring(prefix.length()));
    }

    private static JSONObject seatSettings(int id, int health) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id).put(TcpServer.JSON_HEALTH, health)
                .put(TcpServer.JSON_RELOAD_SHOTS, 30).put(TcpServer.JSON_RELOAD_TIME, 1000)
                .put(TcpServer.JSON_RELOAD_ON_EMPTY, false).put(TcpServer.JSON_SPAWN_TIME, 5)
                .put(TcpServer.JSON_DAMAGE, -1).put(TcpServer.JSON_SHOT_MODE_SINGLE, true)
                .put(TcpServer.JSON_SHOT_MODE_BURST3, true).put(TcpServer.JSON_SHOT_MODE_AUTO, true)
                .put(TcpServer.JSON_FIRING_MODE, Globals.FIRING_MODE_OUTDOOR_NO_CONE);
    }

    private static JSONObject seatRoster() throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERS, new JSONArray()
                .put(new JSONObject().put(TcpServer.JSON_PLAYERID, 1).put(TcpServer.JSON_PLAYERNAME, "Steph")
                        .put(TcpServer.JSON_PLAYERIP, "127.0.0.1"))
                .put(new JSONObject().put(TcpServer.JSON_PLAYERID, 17).put(TcpServer.JSON_PLAYERNAME, "Wild wes")
                        .put(TcpServer.JSON_PLAYERIP, "127.0.0.17")))
                .put(TcpServer.JSON_PLAYERSETTINGS, new JSONArray().put(seatSettings(1, 12)).put(seatSettings(17, 27)))
                .put(TcpServer.JSON_ALLOWPLAYERSETTINGS, false).put(TcpServer.JSON_LIMITS, new JSONObject())
                .put(TcpServer.JSON_GAMEMODE, Globals.GAME_MODE_2TEAMS)
                .put(TcpServer.JSON_TOURNAMENT_MODE, false).put(TcpServer.JSON_ONLY_SERVER_SETTINGS, true)
                .put(TcpServer.JSON_DEDICATED, true).put(TcpServer.JSON_HOST_KIND, "L")
                .put(TcpServer.JSON_GAMESTATE, Globals.GAME_STATE_NONE)
                .put(com.simplecoil.protocol.EventMode.PLAYER_IDENTITY, java.util.UUID.randomUUID().toString());
    }

    private static JSONObject seatGps(int id) throws Exception {
        return new JSONObject().put(TcpServer.JSON_PLAYERID, id).put(TcpServer.JSON_TEAM, 1)
                .put(TcpServer.JSON_GPSLATITUDE, 47.62).put(TcpServer.JSON_GPSLONGITUDE, -122.10);
    }

    private void preserveSeatTestGlobals() throws Exception {
        seatTestGlobals = new java.util.LinkedHashMap<>();
        for (Field field : Globals.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            Object value = field.get(Globals.getInstance());
            // Roster commits replace scalar/immutable state, but clear and fill
            // these public maps in place. Preserve their entries as well.
            if (value instanceof java.util.Map) value = new java.util.HashMap<>((java.util.Map<?, ?>) value);
            seatTestGlobals.put(field, value);
        }
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
    public void peerStartKeepsRosterChannelAndSurvivesUntilPausedActivityResumes() throws Exception {
        connect();
        long startAt = SystemClock.elapsedRealtime() + 10000;
        send(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON
                + TcpServer.createStartInfo(1, startAt, 60000));
        long deadline = SystemClock.elapsedRealtime() + 1500;
        while (!client.actions.contains(NetMsg.NETMSG_STARTGAME) && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10);
        assertFalse("Peer start closed the roster channel", awaitStopped(50));
        send(TcpServer.TCP_SERVER_PING);
        expectPong();
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
        // The peer can read the flushed pong before its sender task clears
        // mPongQueued. Serialize the next synthetic ping after that cleanup;
        // production heartbeats arrive a second apart instead of back to back.
        Field field = TcpClient.class.getDeclaredField("sendExecutor");
        field.setAccessible(true);
        ((ExecutorService) field.get(client)).submit(() -> { }).get(2, TimeUnit.SECONDS);
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
        volatile Socket suppliedGameSocket;
        @Override Socket connectGameSocket(Socket nativeSocket, InetAddress address) throws IOException {
            Socket supplied = suppliedGameSocket;
            if (supplied == null) return super.connectGameSocket(nativeSocket, address);
            suppliedGameSocket = null;
            nativeSocket.close();
            return supplied;
        }
        @Override public void sendBroadcast(Intent intent) {
            notifications.add(new Intent(intent));
            actions.add(intent.getAction());
        }
        // This fixture exercises lifecycle cleanup without registering a real receiver.
        @Override public void unregisterReceiver(BroadcastReceiver receiver) { }
    }
}
