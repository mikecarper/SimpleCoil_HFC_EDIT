package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.*;

/** Exercises real UDP sends over loopback, with deterministic sender backlogs. */
@RunWith(AndroidJUnit4.class)
public class UDPDispatchRegressionTest {
    private RecordingService service;
    private Object sendLock;
    private DatagramSocket originalPeer;
    private DatagramSocket laterPeer;
    private final List<Thread> senders = new ArrayList<>();
    private Map<InetAddress, Byte> originalPlayers;
    private Map<Byte, InetAddress> originalAddresses;

    @Before
    public void setUp() throws Exception {
        service = new RecordingService();
        Field lock = UDPListenerService.class.getDeclaredField("mSendLock");
        lock.setAccessible(true);
        sendLock = lock.get(service);
        Globals globals = Globals.getInstance();
        originalPlayers = copyAndClear(globals.mIPTeamMap, globals.mIPTeamMapSemaphore);
        originalAddresses = copyAndClear(globals.mTeamIPMap, globals.mTeamIPMapSemaphore);
        originalPeer = peer("127.0.0.2");
        laterPeer = peer("127.0.0.3");
        register(originalPeer, 2);
    }

    @After
    public void tearDown() throws Exception {
        if (service != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(service::onDestroy);
        for (Thread sender : senders) {
            sender.join(2000);
            assertFalse("UDP sender survived cleanup", sender.isAlive());
        }
        if (originalPeer != null) originalPeer.close();
        if (laterPeer != null) laterPeer.close();
        Globals globals = Globals.getInstance();
        restore(globals.mIPTeamMap, originalPlayers, globals.mIPTeamMapSemaphore);
        restore(globals.mTeamIPMap, originalAddresses, globals.mTeamIPMapSemaphore);
    }

    @Test
    public void queuedBroadcastDoesNotReachPlayersWhoJoinedLater() throws Exception {
        Thread sender;
        synchronized (sendLock) {
            sender = queue(() -> service.sendUDPMessageAll(NetMsg.NETMSG_SHOTFIRED));
            register(laterPeer, 3);
        }
        await(sender);
        assertMessage(originalPeer, NetMsg.NETMSG_SHOTFIRED);
        assertNoMessage(laterPeer);
    }

    @Test
    public void repeatedEndDoesNotReachPlayersWhoJoinedLater() throws Exception {
        Thread sender;
        synchronized (sendLock) {
            sender = queue(service::endGame);
            register(laterPeer, 3);
        }
        await(sender);
        for (int i = 0; i < 3; i++) assertMessage(originalPeer, NetMsg.NETMSG_ENDGAME);
        assertNoMessage(laterPeer);
    }

    @Test
    public void queuedBroadcastCannotEndAReplacementSessionWithTheSamePeers() throws Exception {
        assertReplacementCancels(() -> service.sendUDPMessageAll(NetMsg.NETMSG_ENDGAME));
    }

    @Test
    public void queuedDirectMessageCannotAffectAReplacementSession() throws Exception {
        assertReplacementCancels(() -> service.sendUDPMessage(NetMsg.NETMSG_ELIMINATED, (byte) 2));
    }

    private void assertReplacementCancels(Runnable send) throws Exception {
        Thread sender;
        synchronized (sendLock) {
            sender = queue(send);
            service.stopListen();
            service.joinServer(originalPeer.getLocalAddress(), 17509);
            service.endScanning();
            register(originalPeer, 2);
        }
        await(sender);
        assertNoMessage(originalPeer);
        service.sendUDPMessageAll(NetMsg.NETMSG_SHOTFIRED);
        assertMessage(originalPeer, NetMsg.NETMSG_SHOTFIRED);
    }

    @Test
    public void destroyingServiceDiscardsQueuedGameEvents() throws Exception {
        Thread sender;
        synchronized (sendLock) {
            sender = queue(() -> service.sendUDPMessageAll(NetMsg.NETMSG_ELIMINATED));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(service::onDestroy);
        }
        await(sender);
        assertNoMessage(originalPeer);
    }

    @Test
    public void queuedEndStillReachesPeersAfterTheLocalListenerStops() throws Exception {
        assertStopDrains(service::endGame, NetMsg.NETMSG_ENDGAME, 3);
    }

    @Test
    public void queuedLeaveStillReachesPeersAfterTheLocalListenerStops() throws Exception {
        assertStopDrains(() -> service.sendUDPMessageAll(NetMsg.NETMSG_LEAVE), NetMsg.NETMSG_LEAVE, 1);
    }

    private void assertStopDrains(Runnable send, String message, int count) throws Exception {
        Thread sender;
        synchronized (sendLock) {
            sender = queue(send);
            service.stopListen();
        }
        await(sender);
        for (int i = 0; i < count; i++) assertMessage(originalPeer, message);
        assertNoMessage(originalPeer);
    }

    @Test
    public void laterBroadcastIncludesNewlyJoinedPlayers() throws Exception {
        register(laterPeer, 3);
        service.sendUDPMessageAll(NetMsg.NETMSG_SHOTFIRED);
        assertMessage(originalPeer, NetMsg.NETMSG_SHOTFIRED);
        assertMessage(laterPeer, NetMsg.NETMSG_SHOTFIRED);
    }

    // The caller holds the send lock, so the new worker cannot finish early.
    private Thread queue(Runnable send) throws Exception {
        Set<Thread> previous = Thread.getAllStackTraces().keySet();
        send.run();
        long deadline = SystemClock.elapsedRealtime() + 2000;
        do {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (previous.contains(thread) || thread.getState() != Thread.State.BLOCKED)
                    continue;
                for (StackTraceElement frame : entry.getValue()) {
                    if (frame.getClassName().equals(UDPListenerService.class.getName())) {
                        senders.add(thread);
                        return thread;
                    }
                }
            }
            Thread.sleep(10);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("UDP sender did not wait for the held send lock");
    }

    private static void await(Thread sender) throws Exception {
        sender.join(2000);
        assertFalse("UDP sender did not drain", sender.isAlive());
    }

    private static DatagramSocket peer(String address) throws Exception {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(address), 17500));
        socket.setSoTimeout(2000);
        return socket;
    }

    private static void assertMessage(DatagramSocket peer, String message) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[500], 500);
        peer.receive(packet);
        assertEquals(NetMsg.MESSAGE_PREFIX + message,
                new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
    }

    private static void assertNoMessage(DatagramSocket peer) throws Exception {
        peer.setSoTimeout(200);
        try {
            peer.receive(new DatagramPacket(new byte[500], 500));
            fail("An obsolete UDP event reached a peer");
        } catch (SocketTimeoutException expected) {
            // No queued event belongs to this peer/session.
        } finally { peer.setSoTimeout(2000); }
    }

    private static void register(DatagramSocket peer, int id) {
        Globals globals = Globals.getInstance();
        Globals.getmIPTeamMapSemaphore();
        try { globals.mIPTeamMap.put(peer.getLocalAddress(), (byte) id); }
        finally { globals.mIPTeamMapSemaphore.release(); }
        Globals.getmTeamIPMapSemaphore();
        try { globals.mTeamIPMap.put((byte) id, peer.getLocalAddress()); }
        finally { globals.mTeamIPMapSemaphore.release(); }
    }

    private static <K, V> Map<K, V> copyAndClear(Map<K, V> map, Semaphore lock) throws Exception {
        lock.acquire();
        try {
            Map<K, V> copy = new HashMap<>(map);
            map.clear();
            return copy;
        } finally { lock.release(); }
    }

    private static <K, V> void restore(Map<K, V> map, Map<K, V> original, Semaphore lock) throws Exception {
        if (original == null) return;
        lock.acquire();
        try { map.clear(); map.putAll(original); }
        finally { lock.release(); }
    }

    private static final class RecordingService extends UDPListenerService {
        @Override public void startListenForUDPMessage() { }
        @Override public void sendBroadcast(Intent intent) { }
    }
}
