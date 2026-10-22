package com.simplecoil.simplecoil;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.simplecoil.protocol.RoundRoster;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RoundReportTcpRegressionTest {
    private static final String TOKEN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private TcpClient client;
    private ExecutorService sender;
    private int originalGameState;
    private int originalGameMode;
    private boolean originalBalanced;
    private byte originalPlayerID;
    private RoundRoster originalRoster;

    @Before
    public void setUp() throws Exception {
        client = new TcpClient();
        sender = (ExecutorService) get("sendExecutor");
        Globals globals = Globals.getInstance();
        originalGameState = globals.mGameState;
        originalGameMode = globals.mGameMode;
        originalBalanced = globals.mBalancedRandom;
        originalPlayerID = globals.mPlayerID;
        originalRoster = globals.mRoundRoster;
        globals.mPlayerID = 1;
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mBalancedRandom = false;
        globals.mRoundRoster = new RoundRoster(TOKEN);
        globals.mRoundRoster.restore(new RoundRoster.Member("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 1, 1, 0));
        globals.mRoundRoster.restore(new RoundRoster.Member("cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 0));
        globals.mRoundRoster.restore(new RoundRoster.Member("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", 2, 1, 0));
        set("mStatisticsRoundToken", TOKEN);
        set("mStatisticsStartAt", SystemClock.elapsedRealtime() - 1000);
    }

    @After
    public void tearDown() throws Exception {
        sender.shutdownNow();
        assertTrue(sender.awaitTermination(3, TimeUnit.SECONDS));
        Globals globals = Globals.getInstance();
        globals.mGameState = originalGameState;
        globals.mGameMode = originalGameMode;
        globals.mBalancedRandom = originalBalanced;
        globals.mPlayerID = originalPlayerID;
        globals.mRoundRoster = originalRoster;
    }

    @Test
    public void completedHostReportSurvivesCountdownTokenCleanupAndDuplicateEnd() throws Exception {
        set("mActiveStartToken", null);
        parse(new JSONObject().put(RoundReport.KEY, report().toJson()));
        RoundReport first = client.getCompletedRoundReport(TOKEN);
        assertNotNull(first);
        assertEquals(30, first.shots);
        assertEquals(5, first.hits);
        parse(new JSONObject().put(RoundReport.KEY, new RoundReport(TOKEN, 1, 9, 2, 90, 9,
                9, 2, 100, 120, 60000, true).toJson()));
        assertSame(first, client.getCompletedRoundReport(TOKEN));
        assertNull(client.getCompletedRoundReport("cccccccc-cccc-cccc-cccc-cccccccccccc"));
    }

    @Test
    public void malformedOldAndWrongSeatReportsNeverPartiallyReplaceCache() throws Exception {
        JSONObject valid = report().toJson();
        String[] keys = {"kills", "deaths", "shots", "hits", "bestkillstreak", "bestdeathstreak",
                "skillbefore", "skillafter", "durationms", "complete"};
        for (String key : keys) {
            JSONObject malformed = new JSONObject(valid.toString());
            malformed.remove(key);
            parse(new JSONObject().put(RoundReport.KEY, malformed));
            assertNull("Missing " + key + " left partial stats", client.getCompletedRoundReport(TOKEN));
            malformed = new JSONObject(valid.toString());
            malformed.put(key, "1");
            parse(new JSONObject().put(RoundReport.KEY, malformed));
            assertNull(client.getCompletedRoundReport(TOKEN));
        }
        parse(new JSONObject().put(RoundReport.KEY, new JSONObject(valid.toString()).put("playerID", 17)));
        parse(new JSONObject().put(RoundReport.KEY, new JSONObject(valid.toString())
                .put("roundtoken", "cccccccc-cccc-cccc-cccc-cccccccccccc")));
        parse(new JSONObject().put(RoundReport.KEY, new JSONObject(valid.toString()).put("bestkillstreak", 50)));
        assertNull(client.getCompletedRoundReport(TOKEN));
        parse(new JSONObject().put(RoundReport.KEY, valid));
        assertNotNull(client.getCompletedRoundReport(TOKEN));
    }

    @Test
    public void bothGunsShareCumulativeShotsAndReconnectReplaysWithoutResettingSequence() throws Exception {
        ByteArrayOutputStream firstStream = new ByteArrayOutputStream();
        set("out", new DataOutputStream(firstStream));
        client.reportHostedShots(3);
        client.reportHostedShots(4);
        awaitSender();
        DataInputStream first = new DataInputStream(new ByteArrayInputStream(firstStream.toByteArray()));
        assertEquals(3, json(first.readUTF()).getInt("total"));
        JSONObject paired = json(first.readUTF());
        assertEquals(7, paired.getInt("total"));
        assertEquals(2, paired.getLong("telemetryseq"));
        set("out", null);
        client.reportHostedShots(2);
        ByteArrayOutputStream replacementStream = new ByteArrayOutputStream();
        set("out", new DataOutputStream(replacementStream));
        Method drain = TcpClient.class.getDeclaredMethod("sendQueuedMessages");
        drain.setAccessible(true);
        drain.invoke(client);
        awaitSender();
        JSONObject recovered = json(new DataInputStream(new ByteArrayInputStream(replacementStream.toByteArray())).readUTF());
        assertEquals(9, recovered.getInt("total"));
        assertEquals(3, recovered.getLong("telemetryseq"));
        assertEquals(TOKEN, recovered.getString("roundtoken"));
    }

    @Test
    public void countdownAndFutureLifeDeathsDoNotInventGunShots() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        set("out", new DataOutputStream(bytes));
        set("mStatisticsStartAt", SystemClock.elapsedRealtime() + 5000);
        client.reportHostedShots(3);
        awaitSender();
        assertEquals(0, bytes.size());
        set("mStatisticsStartAt", SystemClock.elapsedRealtime() - 1000);
        client.reportRoundElimination(17, 0);
        awaitSender();
        JSONObject death = json(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUTF());
        assertEquals("elimination", death.getString("telemetry"));
        assertEquals(0, death.getInt("life"));
        assertFalse(death.has("total"));
    }

    @Test
    public void newClientServiceResumesHostShotTotalAndSequenceWithoutFabricatingShots() throws Exception {
        JSONObject resume = new JSONObject().put("roundtoken", TOKEN).put("generation", 0)
                .put("life", 0).put("telemetryseq", 10L).put("total", 20);
        parse(new JSONObject().put(TcpServer.JSON_TELEMETRY_RESUME, resume));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        set("out", new DataOutputStream(bytes));
        client.reportHostedShots(5);
        awaitSender();
        JSONObject shot = json(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUTF());
        assertEquals(25, shot.getInt("total"));
        assertEquals(11, shot.getLong("telemetryseq"));
        JSONObject forgedResume = new JSONObject(resume.toString()).put("generation", 1).put("total", 99);
        parse(new JSONObject().put(TcpServer.JSON_TELEMETRY_RESUME, forgedResume));
        assertEquals(25, get("mTelemetryShots"));
    }

    @Test
    public void queuedVictimHitAndDeathKeepTheAttackersCapturedSeatGeneration() throws Exception {
        set("out", null);
        client.reportHostedHit(17);
        client.reportRoundElimination(17, 0);
        Globals.getInstance().mRoundRoster.leave(17);
        RoundRoster.Member replacement = Globals.getInstance().mRoundRoster.join(
                "dddddddd-dddd-dddd-dddd-dddddddddddd", 2, 2, false, false, 0);
        assertEquals(17, replacement.id);
        assertEquals(1, replacement.generation);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        set("out", new DataOutputStream(bytes));
        Method drain = TcpClient.class.getDeclaredMethod("sendQueuedMessages");
        drain.setAccessible(true);
        drain.invoke(client);
        awaitSender();
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        JSONObject hit = json(frames.readUTF()), death = json(frames.readUTF());
        assertEquals("hit", hit.getString("telemetry"));
        assertEquals("elimination", death.getString("telemetry"));
        assertEquals(0, hit.getInt("attackergeneration"));
        assertEquals(0, death.getInt("attackergeneration"));
        assertEquals(17, hit.getInt("attacker"));
        assertEquals(17, death.getInt("attacker"));
    }

    @Test
    public void friendlyAndSelfGrenadeDeathsRemainReportedWithoutFriendlyKillCredit() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        set("out", new DataOutputStream(bytes));
        client.reportRoundElimination(2, 0); // A teammate's grenade.
        Globals.getInstance().mRoundRoster.update(1, 0, 0, 1, 0, false);
        client.reportRoundElimination(1, 1); // The victim's own grenade.
        Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
        Globals.getInstance().mRoundRoster.update(1, 0, 0, 2, 0, false);
        client.reportRoundElimination(1, 2); // Self damage stays uncredited in FFA too.
        client.reportRoundElimination(17, 2); // Opponent credit still carries its generation.
        awaitSender();
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        for (int life = 0; life < 3; life++) {
            JSONObject death = json(frames.readUTF());
            assertEquals("elimination", death.getString("telemetry"));
            assertEquals(0, death.getInt("attacker"));
            assertEquals(life, death.getInt("life"));
            assertEquals(life + 1, death.getLong("telemetryseq"));
            assertFalse(death.has("attackergeneration"));
        }
        JSONObject hostile = json(frames.readUTF());
        assertEquals(17, hostile.getInt("attacker"));
        assertEquals(0, hostile.getInt("attackergeneration"));
        assertEquals(4, hostile.getLong("telemetryseq"));
    }

    private RoundReport report() {
        return new RoundReport(TOKEN, 1, 4, 2, 30, 5, 3, 2, 100, 108, 60000, true);
    }

    private void parse(JSONObject json) throws Exception {
        Method method = TcpClient.class.getDeclaredMethod("parseGameInfo", String.class);
        method.setAccessible(true);
        method.invoke(client, json.toString());
    }

    private JSONObject json(String frame) throws Exception {
        return new JSONObject(frame.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
    }

    private void awaitSender() throws Exception {
        sender.submit(() -> { }).get(3, TimeUnit.SECONDS);
    }

    private void set(String name, Object value) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private Object get(String name) throws Exception {
        Field field = TcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }
}
