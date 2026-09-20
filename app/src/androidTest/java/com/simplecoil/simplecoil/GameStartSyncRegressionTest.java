package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Real protocol parsing with deterministic clock samples, no wall-clock changes. */
@RunWith(AndroidJUnit4.class)
public class GameStartSyncRegressionTest {
    private RecordingClient client;
    private static final long OFFSET = 500000;

    @Before public void setUp() { client = new RecordingClient(); }

    @After public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::onDestroy);
    }

    @Test public void fiveSamplesAreRequiredBeforeStartIsPublished() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        parse(plan(1, deadline, 60000));
        assertTrue(client.events.isEmpty());
        for (int i = 0; i < 4; i++) sample(OFFSET);
        assertFalse(client.isClockSynchronized());
        assertTrue(client.events.isEmpty());
        sample(OFFSET);
        assertTrue(client.isClockSynchronized());
        assertEquals(1, client.events.size());
        Intent start = client.events.get(0);
        assertEquals(deadline, start.getLongExtra(NetMsg.INTENT_START_AT, -1));
        assertEquals(deadline + 60000, start.getLongExtra(NetMsg.INTENT_END_AT, -1));
        assertTrue(TcpServer.isValidRoundToken(start.getStringExtra(NetMsg.INTENT_ROUND_TOKEN)));
        // This parser-only client deliberately has no TCP writer. Reaching the
        // synchronized start proves its clock-ready state without asserting a
        // socket write that the test double cannot record.
    }

    @Test public void duplicateAndOlderRoundCannotRestartCountdown() throws Exception {
        synchronize();
        JSONObject start = plan(2, SystemClock.elapsedRealtime() + 10000, 0);
        parse(start);
        parse(start);
        parse(plan(1, SystemClock.elapsedRealtime() + 20000, 0));
        assertEquals(1, client.events.size());
    }

    @Test public void olderRoundCannotReplaceANewerPendingStartBeforeClockSync() throws Exception {
        long now = SystemClock.elapsedRealtime();
        parse(plan(2, now + 10000, 0));
        parse(plan(1, now + 20000, 0));
        synchronize();
        assertEquals(1, client.events.size());
        assertEquals(2, client.events.get(0).getLongExtra(NetMsg.INTENT_ROUND_ID, 0));
    }

    @Test public void startIsRetainedForPausedActivityAndConsumedOnlyOnce() throws Exception {
        synchronize();
        parse(plan(1, SystemClock.elapsedRealtime() + 10000, 0));
        long id = client.events.get(0).getLongExtra(TcpClient.EXTRA_START_EVENT_ID, 0);
        assertTrue(id > 0);
        assertNull(client.consumePendingGameStart(id + 1));
        Intent start = client.consumePendingGameStart(id);
        assertNotNull(start);
        assertFalse(start.hasExtra(TcpClient.EXTRA_START_EVENT_ID));
        assertNull(client.consumePendingGameStart(0));
    }

    @Test public void stoppingDiscardsClockAndPendingStart() throws Exception {
        synchronize();
        parse(plan(1, SystemClock.elapsedRealtime() + 10000, 0));
        client.stopTcpClient();
        assertFalse(client.isClockSynchronized());
        assertNull(client.consumePendingGameStart(0));
    }

    @Test public void destructionDiscardsPendingStart() throws Exception {
        synchronize();
        parse(plan(1, SystemClock.elapsedRealtime() + 10000, 0));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::onDestroy);
        assertNull(client.consumePendingGameStart(0));
    }

    @Test public void expiredTimedRoundEndsInsteadOfStartingAnotherCountdown() throws Exception {
        synchronize();
        parse(plan(1, SystemClock.elapsedRealtime() - 2000, 1000));
        assertEquals(1, client.events.size());
        assertEquals(NetMsg.NETMSG_ENDGAME, client.events.get(0).getAction());
        assertNull(client.consumePendingGameStart(0));
    }

    @Test public void lateArrivalKeepsOriginalDeadline() throws Exception {
        synchronize();
        long startAt = SystemClock.elapsedRealtime() - 1000;
        parse(plan(1, startAt, 60000));
        assertEquals(startAt, client.events.get(0).getLongExtra(NetMsg.INTENT_START_AT, 0));
        assertEquals(startAt + 60000, client.events.get(0).getLongExtra(NetMsg.INTENT_END_AT, 0));
    }

    @Test public void malformedAndExcessivelyFuturePlansAreIgnored() throws Exception {
        synchronize();
        long now = SystemClock.elapsedRealtime();
        parse(plan(0, now, 0));
        parse(plan(1, now, -1));
        parse(plan(1, now, Long.MAX_VALUE));
        parse(plan(1, now, 0).put(TcpServer.JSON_GAMESTART, "1000"));
        parse(plan(1, now, 0).put(TcpServer.JSON_GAMESTART, Long.MAX_VALUE));
        JSONObject missingRoundToken = plan(1, now, 0);
        missingRoundToken.remove(TcpServer.JSON_ROUND_TOKEN);
        parse(missingRoundToken);
        parse(plan(1, now, 0).put(TcpServer.JSON_ROUND_TOKEN, "not-a-round-token"));
        parse(plan(1, now + Globals.MAX_RESPAWN_TIME_SECONDS * 1000 + 100000, 0));
        assertTrue(client.events.isEmpty());
        parse(plan(1, now + 10000, 0));
        assertEquals(1, client.events.size());
    }

    @Test public void unsolicitedAndInvalidRepliesDoNotMarkClockReady() throws Exception {
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < 5; i++) reply(now, now + OFFSET, now + OFFSET, now + 20);
        assertFalse(client.isClockSynchronized());
        for (int i = 0; i < 5; i++) {
            set("mPendingClockRequest", now);
            reply(now, now + OFFSET, now + OFFSET - 1, now + 20);
        }
        assertFalse(client.isClockSynchronized());
        synchronize();
        assertTrue(client.isClockSynchronized());
    }

    private JSONObject plan(long round, long localStart, long duration) {
        return TcpServer.createStartInfo(round, localStart + OFFSET, duration);
    }

    private void synchronize() throws Exception {
        for (int i = 0; i < GameClock.SAMPLES_PER_SYNC; i++) sample(OFFSET);
    }

    private void sample(long offset) throws Exception {
        long now = SystemClock.elapsedRealtime();
        set("mPendingClockRequest", now);
        reply(now, now + offset + 10, now + offset + 10, now + 20);
    }

    private void reply(long sent, long received, long hostSent, long localReceived) throws Exception {
        JSONObject json = new JSONObject().put(TcpServer.JSON_CLOCK_REQUEST, sent)
                .put(TcpServer.JSON_CLOCK_RECEIVE, received).put(TcpServer.JSON_CLOCK_SEND, hostSent);
        Method method = TcpClient.class.getDeclaredMethod("receiveClockSync", JSONObject.class, long.class, long.class);
        method.setAccessible(true);
        method.invoke(client, json, localReceived, 0L);
    }

    private void parse(JSONObject json) throws Exception {
        Method method = TcpClient.class.getDeclaredMethod("parseGameInfo", String.class);
        method.setAccessible(true);
        method.invoke(client, json.toString());
    }

    private void set(String name, Object value) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private static final class RecordingClient extends TcpClient {
        final List<Intent> events = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        @Override public void sendBroadcast(Intent intent) { events.add(new Intent(intent)); }
        @Override public void sendTCPMessage(String message) { messages.add(message); }
    }
}
