package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the client's actual sender and reconnect replay without opening sockets. */
@RunWith(AndroidJUnit4.class)
public class TcpClientRegressionTest {
    private TcpClient client;
    private ExecutorService sender;
    private Queue<?> pending;
    private byte originalPairedGrenade;

    @Before
    public void setUp() throws Exception {
        client = new TcpClient();
        sender = (ExecutorService) field("sendExecutor").get(client);
        pending = (Queue<?>) field("messageQueue").get(client);
        originalPairedGrenade = Globals.getInstance().mPairedGrenadeID;
        Globals.getInstance().mPairedGrenadeID = 0;
    }

    @After
    public void tearDown() throws Exception {
        sender.shutdownNow();
        assertTrue(sender.awaitTermination(3, TimeUnit.SECONDS));
        Globals.getInstance().mPairedGrenadeID = originalPairedGrenade;
    }

    @Test
    public void registrationSynchronizesUnpairedGrenadeState() throws Exception {
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(delivered));
        register();
        awaitSender();
        DataInputStream messages = messages(delivered);
        assertRegistration(messages.readUTF());
        assertGrenadePairing(messages.readUTF(), 0);
    }

    @Test
    public void reconnectRepublishesUnpairingThatWasLostWhileOffline() throws Exception {
        Globals.getInstance().mPairedGrenadeID = 3;
        ByteArrayOutputStream firstConnection = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(firstConnection));
        register();
        awaitSender();
        DataInputStream initial = messages(firstConnection);
        assertRegistration(initial.readUTF());
        assertGrenadePairing(initial.readUTF(), 3);

        field("out").set(client, null);
        Globals.getInstance().mPairedGrenadeID = 0;
        client.sendPlayerGrenade();
        awaitSender();
        ByteArrayOutputStream replacement = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(replacement));
        register();
        awaitSender();
        DataInputStream rejoined = messages(replacement);
        assertRegistration(rejoined.readUTF());
        assertGrenadePairing(rejoined.readUTF(), 0);
        assertEquals(0, rejoined.available());
    }

    @Test
    public void failedReconnectRetainsQueuedEventsAndReplaysThemInOrder() throws Exception {
        client.sendTCPMessage("first event", true);
        client.sendTCPMessage("second event", true);
        field("out").set(client, new DataOutputStream(failingOutput()));
        register();
        awaitSender();
        assertEquals(2, pending.size());

        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(delivered));
        register();
        awaitSender();
        DataInputStream messages = messages(delivered);
        assertRegistration(messages.readUTF());
        assertEquals("first event", messages.readUTF());
        assertEquals("second event", messages.readUTF());
        assertGrenadePairing(messages.readUTF(), 0);
        assertEquals(0, messages.available());
        assertTrue(pending.isEmpty());
    }

    @Test
    public void replayRemovesOnlySuccessfullyFlushedEvents() throws Exception {
        client.sendTCPMessage("first event", true);
        client.sendTCPMessage("second event", true);
        field("out").set(client, new DataOutputStream(new ByteArrayOutputStream() {
            private int flushes;
            @Override public void flush() throws IOException {
                if (++flushes >= 3) throw new IOException("Link lost during second event");
            }
        }));
        register();
        awaitSender();
        assertEquals(1, pending.size());
        assertEquals("second event", pending.peek());
    }

    @Test
    public void eventWaitingOnOldConnectionIsReplayedAfterNewRegistration() throws Exception {
        CountDownLatch releaseSender = new CountDownLatch(1);
        sender.execute(() -> {
            try { releaseSender.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        try {
            field("out").set(client, new DataOutputStream(failingOutput()));
            client.sendTCPMessage("event during disconnect", true);
            field("out").set(client, new DataOutputStream(delivered));
            register();
        } finally {
            releaseSender.countDown();
        }
        awaitSender();
        DataInputStream messages = messages(delivered);
        assertRegistration(messages.readUTF());
        assertTrue("Queued event must reach the replacement connection", messages.available() > 0);
        assertEquals("event during disconnect", messages.readUTF());
        assertGrenadePairing(messages.readUTF(), 0);
        assertEquals(0, messages.available());
        assertTrue(pending.isEmpty());
    }

    @Test
    public void stoppedSenderDoesNotDiscardPersistentEvents() throws Exception {
        sender.shutdown();
        field("out").set(client, new DataOutputStream(new ByteArrayOutputStream()));
        client.sendTCPMessage("event", true);
        assertEquals(1, pending.size());
    }

    @Test
    public void persistentEventQueueKeepsRecentEventsWithinItsLimit() {
        for (int index = 0; index < TcpClient.MAX_QUEUED_PERSISTENT_MESSAGES + 3; index++)
            client.sendTCPMessage("event " + index, true);

        assertEquals(TcpClient.MAX_QUEUED_PERSISTENT_MESSAGES, pending.size());
        assertEquals("event 3", pending.peek());
    }

    @Test
    public void ordinarySendBacklogStaysWithinItsConfiguredLimit() throws Exception {
        CountDownLatch release = blockSender();
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        try {
            field("out").set(client, new DataOutputStream(delivered));
            for (int index = 0; index < TcpClient.MAX_PENDING_SEND_TASKS * 3; index++)
                client.sendTCPMessage("burst " + index);
            assertEquals(TcpClient.MAX_PENDING_SEND_TASKS,
                    ((ThreadPoolExecutor) sender).getQueue().size());
        } finally {
            release.countDown();
        }
        awaitSender();
        DataInputStream messages = messages(delivered);
        int count = 0;
        while (messages.available() > 0) {
            messages.readUTF();
            count++;
        }
        assertEquals(TcpClient.MAX_PENDING_SEND_TASKS, count);
    }

    @Test
    public void repeatedPongsShareOneQueuedWrite() throws Exception {
        CountDownLatch release = blockSender();
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        try {
            field("out").set(client, new DataOutputStream(delivered));
            Method pong = TcpClient.class.getDeclaredMethod("sendPong");
            pong.setAccessible(true);
            for (int index = 0; index < TcpClient.MAX_PENDING_SEND_TASKS * 3; index++)
                pong.invoke(client);
            assertEquals(1, ((ThreadPoolExecutor) sender).getQueue().size());
        } finally {
            release.countDown();
        }
        awaitSender();
        DataInputStream messages = messages(delivered);
        assertEquals(TcpClient.TCP_CLIENT_PONG, messages.readUTF());
        assertEquals(0, messages.available());
    }

    @Test
    public void failedRegistrationPreventsReplayOnAPartiallyWrittenStream() throws Exception {
        client.sendTCPMessage("event", true);
        final int[] flushes = {0};
        field("out").set(client, new DataOutputStream(new ByteArrayOutputStream() {
            @Override public void flush() throws IOException {
                if (++flushes[0] == 1) throw new IOException("Registration failed");
            }
        }));
        register();
        awaitSender();
        assertEquals(1, flushes[0]);
        assertEquals(1, pending.size());
        assertNull(field("out").get(client));
    }

    @Test
    public void invalidLocalGpsBroadcastsAreNotSent() throws Exception {
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(delivered));
        double[][] invalid = {{0, 0}, {0, 20}, {10, 0}, {Double.NaN, 20}, {10, Double.POSITIVE_INFINITY},
                {181, 20}, {-181, 20}, {10, 91}, {10, -91}};
        for (double[] coordinates : invalid)
            receiveLocation(new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LONGITUDE, coordinates[0])
                    .putExtra(NetMsg.INTENT_LATITUDE, coordinates[1]));
        awaitSender();
        assertEquals(0, delivered.size());
    }

    @Test
    public void incompleteLocalGpsBroadcastsDoNotInventZeroCoordinates() throws Exception {
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(delivered));
        receiveLocation(new Intent(NetMsg.NETMSG_GPSLOCUPDATE));
        receiveLocation(new Intent(NetMsg.NETMSG_GPSLOCUPDATE).putExtra(NetMsg.INTENT_LATITUDE, 20.0));
        receiveLocation(new Intent(NetMsg.NETMSG_GPSLOCUPDATE).putExtra(NetMsg.INTENT_LONGITUDE, 10.0));
        awaitSender();
        assertEquals(0, delivered.size());
    }

    @Test
    public void validLocalGpsBroadcastsPreserveTheirCoordinates() throws Exception {
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        field("out").set(client, new DataOutputStream(delivered));
        double[][] valid = {{10, 20}, {-10, -20}};
        for (double[] coordinates : valid)
            receiveLocation(new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LONGITUDE, coordinates[0])
                    .putExtra(NetMsg.INTENT_LATITUDE, coordinates[1]));
        awaitSender();
        DataInputStream messages = messages(delivered);
        for (double[] coordinates : valid) {
            String message = messages.readUTF();
            JSONObject gps = new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                    + TcpServer.TCPPREFIX_JSON.length()));
            assertEquals(coordinates[0], gps.getDouble(TcpServer.JSON_GPSLONGITUDE), 0);
            assertEquals(coordinates[1], gps.getDouble(TcpServer.JSON_GPSLATITUDE), 0);
        }
        assertEquals(0, messages.available());
    }

    private void receiveLocation(Intent intent) throws Exception {
        ((BroadcastReceiver) field("mGPSUpdateReceiver").get(client)).onReceive(null, intent);
    }

    private void register() throws Exception {
        Method method = TcpClient.class.getDeclaredMethod("sendPlayerInfo", boolean.class);
        method.setAccessible(true);
        method.invoke(client, true);
    }

    private void awaitSender() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        sender.execute(drained::countDown);
        assertTrue("TCP sender did not finish", drained.await(3, TimeUnit.SECONDS));
    }

    private CountDownLatch blockSender() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sender.execute(() -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue("TCP sender did not begin blocking", entered.await(3, TimeUnit.SECONDS));
        return release;
    }

    private static Field field(String name) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static OutputStream failingOutput() {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Link lost"); }
        };
    }

    private static DataInputStream messages(ByteArrayOutputStream bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void assertRegistration(String message) {
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        assertTrue(message.contains(TcpServer.JSON_PLAYERNAME));
    }

    private static void assertGrenadePairing(String message, int expected) throws Exception {
        assertTrue(message.startsWith(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON));
        JSONObject pairing = new JSONObject(message.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                + TcpServer.TCPPREFIX_JSON.length()));
        assertEquals(expected, pairing.getInt(TcpServer.JSON_PAIRED_GRENADE_ID));
        assertEquals(Globals.getInstance().mPlayerID, pairing.getInt(TcpServer.JSON_PLAYERID));
    }
}
