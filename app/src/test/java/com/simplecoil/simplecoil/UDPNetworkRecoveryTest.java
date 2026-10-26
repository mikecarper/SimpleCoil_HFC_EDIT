package com.simplecoil.simplecoil;

import android.content.Intent;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the real UDP worker and sockets through lobby/round recovery. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class UDPNetworkRecoveryTest {
    @Test public void startWhileStoppedWorkerRetiresEventuallyRestoresAUsableListener() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            Thread original = fixture.worker();
            synchronized (get(fixture.service, "mListenerStateLock")) {
                // The old receive worker cannot finish cleanup until this
                // lock is released. Starting now deterministically hits the
                // old still-alive worker instead of depending on timing.
                fixture.service.stopListen();
                assertTrue(original.isAlive());
                fixture.service.startListenForUDPMessage();
            }
            await(() -> fixture.worker() != null && fixture.worker() != original && fixture.ready(),
                    "An immediate restart left UDP discovery permanently stopped");
            original.join(1_000);
            assertFalse(original.isAlive());
            assertNotSame(original, fixture.worker());
            fixture.assertDiscoveryReceived();
            assertTrue(fixture.service.multicastLock.isHeld());
        }
    }

    @Test public void socketFailureRebindsWithoutLosingDiscoveryOrStartingAnotherWorker() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            DatagramSocket original = fixture.socket();
            Thread worker = fixture.worker();
            original.close();
            await(() -> fixture.socket() != original && fixture.ready(),
                    "A failed UDP socket was not replaced");
            assertSame("Socket recovery started overlapping listeners", worker, fixture.worker());
            fixture.assertDiscoveryReceived();
            assertTrue(fixture.service.multicastLock.isHeld());
        }
    }

    @Test public void aLaterStopCancelsAQueuedListenerRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            Thread original = fixture.worker();
            synchronized (get(fixture.service, "mListenerStateLock")) {
                fixture.service.stopListen();
                fixture.service.startListenForUDPMessage();
                fixture.service.stopListen();
            }
            await(() -> fixture.worker() == null, "A cancelled listener did not finish stopping");
            original.join(1_000);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
            assertFalse(original.isAlive());
            assertFalse("A stale restart reopened UDP after an explicit stop", fixture.ready());
            assertNull(fixture.worker());
            assertFalse(fixture.service.multicastLock.isHeld());
            if (fixture.service.wifiLock != null) assertFalse(fixture.service.wifiLock.isHeld());
        }
    }

    @Test public void joiningWhileThePreviousListenerRetiresStillExchangesDiscoveryPackets() throws Exception {
        try (Fixture fixture = new Fixture();
             DatagramSocket host = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.3"), 0))) {
            fixture.start();host.setSoTimeout(2_000);
            Globals.getInstance().mLobbyAutoAssign = false;
            Thread original = fixture.worker();
            synchronized (get(fixture.service, "mListenerStateLock")) {
                fixture.service.stopListen();
                assertTrue(original.isAlive());
                fixture.service.joinServer(host.getLocalAddress(), host.getLocalPort());
            }
            assertFalse("A retiring worker made a fresh join report failure",
                    fixture.hasEvent(NetMsg.NETMSG_FAILEDTOJOIN));
            await(() -> fixture.ready() && get(fixture.service, "mJoinTimer") != null,
                    "A fresh join never obtained its replacement UDP listener");
            assertEquals(true, get(fixture.service, "mScanRunning"));
            ((CountDownTimer) get(fixture.service, "mJoinTimer")).onTick(1_500);
            DatagramPacket request = new DatagramPacket(new byte[100], 100);host.receive(request);
            assertEquals(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN + NetMsg.NETWORK_VERSION + "1",
                    new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8));
            byte[] reply = (NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY).getBytes(StandardCharsets.UTF_8);
            host.send(new DatagramPacket(reply, reply.length, InetAddress.getByName("127.0.0.1"), 17500));
            await(() -> fixture.hasEvent(NetMsg.NETMSG_SERVERREPLY), "Replacement listener lost the host's reply");
            assertFalse(fixture.hasEvent(NetMsg.NETMSG_FAILEDTOJOIN));
            assertEquals(host.getLocalAddress(), Globals.getInstance().mServerIP);
            assertEquals(false, get(fixture.service, "mScanRunning"));
            fixture.assertDiscoveryReceived();
        }
    }

    @Test public void eightRoundsKeepTheLobbySocketAndWifiPriorityUsable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            DatagramSocket original = fixture.socket();
            Thread worker = fixture.worker();
            set(fixture.service, "mIsListService", true);
            for (int round = 0; round < 8; round++) {
                Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
                fixture.service.startGame(true, UUID.randomUUID().toString(), true);
                assertTrue(fixture.service.multicastLock.isHeld());
                assertTrue(fixture.service.wifiLock.isHeld());
                fixture.assertDiscoveryReceived();
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                fixture.service.finishRoundToLobby(true);
                assertSame("A round replaced the retained lobby socket", original, fixture.socket());
                assertSame(worker, fixture.worker());
                assertTrue("Round end disabled lobby multicast", fixture.service.multicastLock.isHeld());
                assertTrue("Round end allowed the retained lobby Wi-Fi connection to sleep",
                        fixture.service.wifiLock.isHeld());
                fixture.assertDiscoveryReceived();
            }
        }
    }

    @Test public void anExplicitStopReleasesBothWifiLocksAndEndsTheWorker() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.service.startGame(true, UUID.randomUUID().toString(), true);
            assertTrue(fixture.service.multicastLock.isHeld());
            assertTrue(fixture.service.wifiLock.isHeld());
            Thread worker = fixture.worker();
            fixture.service.stopListen();
            await(() -> fixture.worker() == null, "Explicit stop retained a UDP worker");
            worker.join(1_000);
            assertFalse(worker.isAlive());
            assertFalse(fixture.ready());
            assertFalse(fixture.service.multicastLock.isHeld());
            assertFalse(fixture.service.wifiLock.isHeld());
        }
    }

    @Test public void passiveInvitationListeningKeepsMulticastWithoutHighPerformanceWifi() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.service.startGameInviteListener();
            await(fixture::ready, "Passive invitation listener did not bind");
            assertEquals(true, get(fixture.service, "mPassiveInviteListener"));
            assertTrue(fixture.service.multicastLock.isHeld());
            assertTrue("A passive invitation listener held high-performance Wi-Fi",
                    fixture.service.wifiLock == null || !fixture.service.wifiLock.isHeld());
            fixture.assertDiscoveryReceived();
            fixture.service.stopListen();
            await(() -> fixture.worker() == null, "Passive listener did not stop");
            assertFalse(fixture.service.multicastLock.isHeld());
            assertTrue(fixture.service.wifiLock == null || !fixture.service.wifiLock.isHeld());
        }
    }

    @Test public void aNewLocalAddressInvalidatesNativeSendsWithoutDiscardingTheRound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.dhcp("127.0.0.1", 24);fixture.start();
            String token = UUID.randomUUID().toString();
            fixture.service.startGame(true, token, false);
            Globals globals = Globals.getInstance();
            globals.mTeamIPMap.put((byte) 2, InetAddress.getByName("127.0.0.2"));
            globals.mIPTeamMap.put(InetAddress.getByName("127.0.0.2"), (byte) 2);
            Thread worker = fixture.worker();
            DatagramSocket listener = fixture.socket();
            DatagramSocket oldSend = fixture.sendSocket();
            long generation = (long) get(fixture.service, "mSendGeneration");
            long listenerGeneration = (long) get(fixture.service, "mListenerGeneration");

            fixture.service.networkAddress = InetAddress.getByName("127.0.0.9");
            fixture.dhcp("127.0.0.9", 24);fixture.refreshAddresses();
            assertEquals(fixture.service.networkAddress, get(fixture.service, "mMyIP"));
            assertEquals(InetAddress.getByName("127.0.0.255"), get(fixture.service, "mBroadcastAddress"));
            assertTrue("An address change kept the old native send handle", oldSend.isClosed());
            assertNull(get(fixture.service, "mSendSocket"));
            assertEquals(generation + 1, get(fixture.service, "mSendGeneration"));
            assertEquals(listenerGeneration, get(fixture.service, "mListenerGeneration"));
            assertSame(listener, fixture.socket());assertSame(worker, fixture.worker());
            assertEquals(token, get(fixture.service, "mPeerRoundToken"));
            assertEquals(token, get(fixture.service, "mStateRoundToken"));
            assertEquals(InetAddress.getByName("127.0.0.2"), globals.mTeamIPMap.get((byte) 2));
            assertEquals(Byte.valueOf((byte) 2), globals.mIPTeamMap.get(InetAddress.getByName("127.0.0.2")));

            fixture.service.sendUDPMessage("after-address-change", fixture.peer.getLocalAddress(),
                    fixture.peer.getLocalPort());
            fixture.peer.setSoTimeout(2_000);
            DatagramPacket received = new DatagramPacket(new byte[100], 100);fixture.peer.receive(received);
            assertEquals("after-address-change", new String(received.getData(), received.getOffset(),
                    received.getLength(), StandardCharsets.UTF_8));
            DatagramSocket replacement = (DatagramSocket) get(fixture.service, "mSendSocket");
            assertNotNull(replacement);assertNotSame(oldSend, replacement);
            fixture.assertDiscoveryReceived();
        }
    }

    @Test public void aNewDhcpMaskRefreshesBroadcastEvenWhenTheLocalAddressIsUnchanged() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.dhcp("127.0.0.1", 24);fixture.start();fixture.refreshAddresses();
            assertEquals(InetAddress.getByName("127.0.0.255"), get(fixture.service, "mBroadcastAddress"));
            DatagramSocket send = fixture.sendSocket();
            long generation = (long) get(fixture.service, "mSendGeneration");
            fixture.dhcp("127.0.0.1", 16);fixture.refreshAddresses();
            assertEquals(InetAddress.getByName("127.0.0.1"), get(fixture.service, "mMyIP"));
            assertEquals("A cached /24 broadcast survived new DHCP information",
                    InetAddress.getByName("127.0.255.255"), get(fixture.service, "mBroadcastAddress"));
            assertSame("A netmask update discarded a usable send handle", send,
                    get(fixture.service, "mSendSocket"));
            assertFalse(send.isClosed());
            assertEquals(generation, get(fixture.service, "mSendGeneration"));
            fixture.assertDiscoveryReceived();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingService service;
        final DatagramSocket peer;
        final Object previousGlobals;

        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            previousGlobals = singleton.get(null);singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mServerIP = InetAddress.getByName("127.0.0.1");
            service = new RecordingService();
            service.networkAddress = InetAddress.getByName("127.0.0.1");
            set(service, "mMyIP", InetAddress.getByName("127.0.0.1"));
            set(service, "mBroadcastAddress", InetAddress.getByName("127.255.255.255"));
            peer = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0));
        }
        void start() throws Exception {
            service.startListenForUDPMessage();
            await(this::ready, "UDP listener did not bind");
        }
        DatagramSocket socket() throws Exception { return (DatagramSocket) get(service, "mSocket"); }
        DatagramSocket sendSocket() throws Exception {
            Method method = UDPListenerService.class.getDeclaredMethod("getSendSocketLocked");
            method.setAccessible(true);
            synchronized (get(service, "mSendLock")) { return (DatagramSocket) method.invoke(service); }
        }
        void dhcp(String localAddress, int prefix) throws Exception {
            byte[] raw = InetAddress.getByName(localAddress).getAddress();
            DhcpInfo lease = new DhcpInfo();
            for (int index = 0; index < 4; index++) lease.ipAddress |= (raw[index] & 255) << (index * 8);
            int mask = -1 << (32 - prefix);
            lease.netmask = Integer.reverseBytes(mask);
            WifiManager wifi = (WifiManager) RuntimeEnvironment.getApplication().getSystemService(Context.WIFI_SERVICE);
            Shadows.shadowOf(wifi).setDhcpInfo(lease);
        }
        void refreshAddresses() throws Exception {
            Method method = UDPListenerService.class.getDeclaredMethod("refreshNetworkAddressesLocked");
            method.setAccessible(true);
            synchronized (get(service, "mListenerStateLock")) { method.invoke(service); }
        }
        Thread worker() throws Exception { return (Thread) get(service, "mUDPMessageThread"); }
        boolean ready() throws Exception {
            Method method = UDPListenerService.class.getDeclaredMethod("isListenerReady");
            method.setAccessible(true);return (boolean) method.invoke(service);
        }
        int discoveries() {
            int count = 0;
            for (Intent event : service.events) if (NetMsg.NETMSG_SHAREDLOBBY.equals(event.getAction())) count++;
            return count;
        }
        boolean hasEvent(String action) {
            for (Intent event : service.events) if (action.equals(event.getAction())) return true;
            return false;
        }
        void assertDiscoveryReceived() throws Exception {
            int previous = discoveries();
            byte[] message = (NetMsg.MESSAGE_PREFIX + SharedLobby.announcement(false, false))
                    .getBytes(StandardCharsets.UTF_8);
            peer.send(new DatagramPacket(message, message.length, InetAddress.getByName("127.0.0.1"), 17500));
            await(() -> discoveries() == previous + 1, "The live UDP socket lost a nearby phone's beacon");
        }
        @Override public void close() throws Exception {
            Thread worker = worker();
            try {
                service.onDestroy();
                peer.close();
                if (worker != null) {
                    worker.join(3_000);
                    assertFalse("UDP worker survived destruction", worker.isAlive());
                }
                for (Field field : UDPListenerService.class.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
                }
            } finally {
                Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
                singleton.set(null, previousGlobals);
            }
        }
    }

    private static final class RecordingService extends UDPListenerService {
        final List<Intent> events = new CopyOnWriteArrayList<>();
        volatile InetAddress networkAddress = InetAddress.getLoopbackAddress();
        RecordingService() { attachBaseContext(RuntimeEnvironment.getApplication()); }
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
        @Override InetAddress currentNetworkAddress() { return networkAddress; }
    }

    private interface Condition { boolean isTrue() throws Exception; }
    private static void await(Condition condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.isTrue() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MILLISECONDS);
            Thread.sleep(2);
        }
        assertTrue(failure, condition.isTrue());
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = UDPListenerService.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = UDPListenerService.class.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
}
