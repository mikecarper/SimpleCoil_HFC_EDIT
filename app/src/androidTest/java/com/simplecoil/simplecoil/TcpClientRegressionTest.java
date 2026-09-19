package com.simplecoil.simplecoil;

import androidx.test.ext.junit.runners.AndroidJUnit4;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the client's actual sender and reconnect replay without opening sockets. */
@RunWith(AndroidJUnit4.class)
public class TcpClientRegressionTest {
    private TcpClient client;
    private ExecutorService sender;
    private Queue<?> pending;

    @Before
    public void setUp() throws Exception {
        client = new TcpClient();
        sender = (ExecutorService) field("sendExecutor").get(client);
        pending = (Queue<?>) field("messageQueue").get(client);
        Globals.getInstance().mPairedGrenadeID = 0;
    }

    @After
    public void tearDown() throws Exception {
        sender.shutdownNow();
        assertTrue(sender.awaitTermination(3, TimeUnit.SECONDS));
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
}
