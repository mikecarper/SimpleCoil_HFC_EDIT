package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.simplecoil.protocol.Powerups;
import com.simplecoil.protocol.RoundRoster;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Source-bound QR grants and local-clock recovery, without network or camera hardware. */
@RunWith(AndroidJUnit4.class)
public class PowerupNetworkRegressionTest {
    private RecordingServer server;
    private RecordingClient client;
    private RoundRoster roster;
    private Object source;
    private Object parser;
    private Class<?> clientType;
    private Map<Integer, Object> connections;
    private final List<MemorySocket> sockets = new ArrayList<>();
    private Globals globals;
    private RoundRoster savedRoster;
    private Object[] savedRuntime;
    private Object savedRuntimeToken;
    private Set<Byte> savedInfected;
    private byte savedID;
    private int savedMode, savedState, savedRequired;
    private boolean savedCapture, savedBoss, savedInfection, savedBalanced;

    @Before
    public void setUp() throws Exception {
        globals = Globals.getInstance();
        savedRoster = globals.mRoundRoster;
        savedRuntime = ((Object[]) field(Globals.class, "mPowerupRuntime").get(globals)).clone();
        savedRuntimeToken = field(Globals.class, "mPowerupRuntimeToken").get(globals);
        savedInfected = globals.mInfectedPlayers;
        savedID = globals.mPlayerID;
        savedMode = globals.mGameMode;
        savedState = globals.mGameState;
        savedRequired = globals.mPowerupQrRequired;
        savedCapture = globals.mCaptureTheFlag;
        savedBoss = globals.mBossMode;
        savedInfection = globals.mInfectionMode;
        savedBalanced = globals.mBalancedRandom;
        globals.mPlayerID = 1;
        globals.mGameMode = Globals.GAME_MODE_FFA;
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mPowerupQrRequired = 2;
        globals.mCaptureTheFlag = false;
        globals.mBossMode = false;
        globals.mInfectionMode = false;
        globals.mBalancedRandom = false;
        roster = new RoundRoster(UUID.randomUUID().toString());
        for (int id = 1; id <= 3; id++) {
            RoundRoster.Member member = roster.join(UUID.randomUUID().toString(), 1, 1, false, false, 0);
            assertEquals(id, member.id);
        }
        globals.installRoundRoster(roster);
        for (int id = 1; id <= 3; id++) alive(id, 0);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            server = new RecordingServer();
            client = new RecordingClient();
        });
        server.setDedicated(true);
        connections = new ConcurrentHashMap<>();
        set(server, "mClientData", connections);
        set(server, "keepListening", true);
        set(server, "mStartAnnounced", true);
        set(server, "mRoundToken", roster.token);
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 1);
        clientType = Class.forName(TcpServer.class.getName() + "$ClientData");
        Constructor<?> parserConstructor = Class.forName(TcpServer.class.getName() + "$ClientThread")
                .getDeclaredConstructor(TcpServer.class);
        parserConstructor.setAccessible(true);
        parser = parserConstructor.newInstance(server);
        source = connection(1);
        connection(2);
        connection(3);
        GameClock clock = (GameClock) field(TcpClient.class, "mGameClock").get(client);
        long now = SystemClock.elapsedRealtime();
        for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++)
            assertTrue(clock.record(now, now + 5000, now + 5000, now));
        set(client, "mClockSynchronized", true);
    }

    @After
    public void tearDown() throws Exception {
        for (Object connection : connections.values()) invoke(connection, "close", new Class<?>[0]);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            server.onDestroy();
            client.onDestroy();
        });
        globals.mRoundRoster = savedRoster;
        globals.mInfectedPlayers = savedInfected;
        System.arraycopy(savedRuntime, 0, field(Globals.class, "mPowerupRuntime").get(globals), 0, savedRuntime.length);
        field(Globals.class, "mPowerupRuntimeToken").set(globals, savedRuntimeToken);
        globals.mPlayerID = savedID;
        globals.mGameMode = savedMode;
        globals.mGameState = savedState;
        globals.mPowerupQrRequired = savedRequired;
        globals.mCaptureTheFlag = savedCapture;
        globals.mBossMode = savedBoss;
        globals.mInfectionMode = savedInfection;
        globals.mBalancedRandom = savedBalanced;
    }

    @Test
    public void incompleteScansAreSavedAndDuplicateCompletedMaskDoesNotGrantAgain() throws Exception {
        request(1, 0, roster.token);
        JSONObject partial = lastFrame(0);
        assertEquals(0, partial.getJSONArray(TcpServer.JSON_POWERUP_GRANTS).length());
        assertEquals(1, scan(partial, 1).getInt("mask"));
        request(3, 0, roster.token);
        JSONObject complete = lastFrame(0);
        JSONObject grant = complete.getJSONObject(TcpServer.JSON_POWERUP_GRANT);
        assertEquals(1, grant.getInt("source"));
        assertEquals(0, grant.getInt("sourcelife"));
        assertEquals(1, scan(complete, 1).getInt("earned"));
        assertEquals(2, scan(complete, 1).getInt("spent"));
        assertEquals(2, grant.getInt("cost"));
        assertEquals(grant.toString(), lastFrame(1).getJSONObject(TcpServer.JSON_POWERUP_GRANT).toString());
        assertEquals(grant.toString(), lastFrame(2).getJSONObject(TcpServer.JSON_POWERUP_GRANT).toString());
        request(3, 0, roster.token);
        assertFalse(lastFrame(0).has(TcpServer.JSON_POWERUP_GRANT));
        assertEquals(1, lastFrame(0).getJSONArray(TcpServer.JSON_POWERUP_GRANTS).length());
    }

    @Test
    public void bulkCatchupGrantsEachEarnedCreditOnlyOnce() throws Exception {
        request(255, 0, roster.token);
        assertEquals(4, lastFrame(0).getJSONArray(TcpServer.JSON_POWERUP_GRANTS).length());
        assertEquals(4, scan(lastFrame(0), 1).getInt("earned"));
        request(255, 0, roster.token);
        assertEquals(4, lastFrame(0).getJSONArray(TcpServer.JSON_POWERUP_GRANTS).length());
        assertFalse(lastFrame(0).has(TcpServer.JSON_POWERUP_GRANT));
    }

    @Test
    public void fallingBehindReducesScanCostWithoutRepricingAlreadySpentCheckpoints() throws Exception {
        globals.mPowerupQrRequired = 4;
        roster.update(2, roster.generation(2), 10, 0, 0, false);
        request(3, 0, roster.token);
        assertEquals(2, lastFrame(0).getJSONObject(TcpServer.JSON_POWERUP_GRANT).getInt("cost"));
        assertEquals(2, scan(lastFrame(0), 1).getInt("spent"));
        roster.update(1, roster.generation(1), 10, 0, 0, false);
        request(15, 0, roster.token);
        assertFalse(lastFrame(0).has(TcpServer.JSON_POWERUP_GRANT));
        assertEquals(4, scan(lastFrame(0), 1).getInt("required"));
        assertEquals(2, scan(lastFrame(0), 1).getInt("spent"));
        request(63, 0, roster.token);
        assertEquals(4, lastFrame(0).getJSONObject(TcpServer.JSON_POWERUP_GRANT).getInt("cost"));
        assertEquals(6, scan(lastFrame(0), 1).getInt("spent"));
        roster.update(2, roster.generation(2), 20, 0, 0, false);
        request(63, 0, roster.token);
        assertFalse(lastFrame(0).has(TcpServer.JSON_POWERUP_GRANT));
        request(255, 0, roster.token);
        assertEquals(2, lastFrame(0).getJSONObject(TcpServer.JSON_POWERUP_GRANT).getInt("cost"));
        assertEquals(8, scan(lastFrame(0), 1).getInt("spent"));
        assertEquals(3, scan(lastFrame(0), 1).getInt("earned"));
    }

    @Test
    public void departedTeamPointsStillReduceTheLosingTeamsScanCost() throws Exception {
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mPowerupQrRequired = 4;
        RoundRoster replacement = new RoundRoster(roster.token);
        replacement.restore(new RoundRoster.Member(roster.seat(1).identity, 1, 1, 1));
        RoundRoster.Member departed = new RoundRoster.Member(UUID.randomUUID().toString(), 17, 2, 1);
        departed.kills = 10;
        departed.active = false;
        replacement.restore(departed);
        replacement.restore(new RoundRoster.Member(UUID.randomUUID().toString(), 17, 2, 2));
        roster = replacement;
        globals.installRoundRoster(roster);
        alive(1, 0);
        alive(17, 0);
        assertEquals(10, roster.archivedScore(2, false));
        request(3, 0, roster.token);
        assertEquals(2, lastFrame(0).getJSONObject(TcpServer.JSON_POWERUP_GRANT).getInt("cost"));
        assertEquals(2, scan(lastFrame(0), 1).getInt("required"));
    }

    @Test
    public void countdownWrongRoundDeadOrFutureLifeCannotClaimRewards() throws Exception {
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() + 4000);
        request(3, 0, roster.token);
        assertEquals(0, sockets.get(0).bytes.size());
        set(server, "mScheduledStart", SystemClock.elapsedRealtime() - 1);
        request(3, 0, UUID.randomUUID().toString());
        request(3, 1, roster.token);
        globals.recordPowerupRuntime(roster.token, 1, roster.generation(1), 0,
                Globals.GAME_STATE_ELIMINATED, 0, false);
        request(3, 0, roster.token);
        assertEquals(0, sockets.get(0).bytes.size());
        alive(1, 0);
        set(source, "awaitingRespawn", true);
        request(3, 0, roster.token);
        assertEquals(0, sockets.get(0).bytes.size());
    }

    @Test
    public void staleIdentityCannotUseAReplacementPlayersSeat() throws Exception {
        set(source, "playerIdentity", UUID.randomUUID().toString());
        request(3, 0, roster.token);
        assertEquals(0, sockets.get(0).bytes.size());
    }

    @Test
    public void queuedUiGrantKeepsTheSourceIdentityFromBeforeASeatReplacement() throws Exception {
        String original = roster.seat(1).identity;
        Powerups.Grant boost = new Powerups.Grant(1, 1, 0, Powerups.DOUBLE_DAMAGE,
                new int[]{1}, new int[]{0}, SystemClock.elapsedRealtime() + 15000, 2);
        Intent queued = TcpServer.powerupIntent(boost, roster.token, false);
        RoundRoster replacement = new RoundRoster(roster.token);
        String next = UUID.randomUUID().toString();
        replacement.restore(new RoundRoster.Member(next, 1, 1, 2));
        globals.installRoundRoster(replacement);
        assertEquals(original, queued.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));
        assertFalse(next.equals(queued.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY)));
        assertEquals(next, TcpServer.powerupIntent(boost, roster.token, true)
                .getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));
    }

    @Test
    public void sameIdentityQrResumeKeepsTheCapturedPowerupOwnerDespiteNewGeneration() throws Exception {
        String identity = roster.seat(1).identity;
        Powerups.Grant boost = new Powerups.Grant(1, 1, 0, Powerups.DOUBLE_DAMAGE,
                new int[]{1}, new int[]{0}, SystemClock.elapsedRealtime() + 15000, 2);
        Intent before = TcpServer.powerupIntent(boost, roster.token, false);
        RoundRoster resumed = new RoundRoster(roster.token);
        resumed.restore(new RoundRoster.Member(identity, 1, 1, 2));
        globals.installRoundRoster(resumed);
        assertEquals(before.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY),
                TcpServer.powerupIntent(boost, resumed.token, true)
                        .getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));
        assertEquals(identity, before.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));
    }

    @Test
    public void deathInvalidatesBuffsAndOldScanCreditsButTheNewLifeCanEarnAgain() throws Exception {
        request(3, 0, roster.token);
        alive(1, 1);
        request(3, 0, roster.token);
        request(1, 1, roster.token);
        JSONArray remaining = lastFrame(0).getJSONArray(TcpServer.JSON_POWERUP_GRANTS);
        for (int n = 0; n < remaining.length(); n++) {
            Powerups.Grant grant = TcpServer.readPowerupGrant(remaining.getJSONObject(n),
                    lastFrame(0).getLong(TcpServer.JSON_POWERUP_CLOCK));
            for (int target : grant.targets)
                assertTrue("Dead source retained a personal effect", target != 1);
        }
        assertEquals(1, scan(lastFrame(0), 1).getInt("life"));
        assertEquals(0, scan(lastFrame(0), 1).getInt("earned"));
        request(3, 1, roster.token);
        assertEquals(1, lastFrame(0).getJSONObject(TcpServer.JSON_POWERUP_GRANT).getInt("sourcelife"));
    }

    @Test
    public void clientConvertsHostDeadlineAndDoesNotRepeatAnInstantGrant() throws Exception {
        long localNow = SystemClock.elapsedRealtime(), hostNow = localNow + 5000;
        Powerups.Grant heal = new Powerups.Grant(1, 1, 0, Powerups.HEAL_TEAM,
                new int[]{1, 2}, new int[]{0, 0}, hostNow + 3000, 2);
        JSONObject frame = snapshot(hostNow, heal).put(TcpServer.JSON_POWERUP_GRANT, TcpServer.powerupGrantJson(heal));
        receive(frame);
        assertEquals(1, client.events.size());
        Intent event = client.events.get(0);
        assertFalse(event.getBooleanExtra(NetMsg.INTENT_POWERUP_SNAPSHOT, true));
        assertEquals(localNow + 3000, event.getLongExtra(NetMsg.INTENT_POWERUP_EXPIRES, 0));
        assertArrayEquals(new int[]{1, 2}, event.getIntArrayExtra(NetMsg.INTENT_POWERUP_TARGETS));
        assertArrayEquals(new int[]{0, 0}, event.getIntArrayExtra(NetMsg.INTENT_POWERUP_LIVES));
        assertEquals(roster.seat(1).identity, event.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));
        client.acknowledgePowerup(1);
        receive(frame);
        client.replayPowerups();
        assertEquals(1, client.events.size());
    }

    @Test
    public void missedLiveInstantBroadcastIsRetryableUntilTheActivityAcknowledgesIt() throws Exception {
        long hostNow = SystemClock.elapsedRealtime() + 5000;
        Powerups.Grant heal = new Powerups.Grant(1, 1, 0, Powerups.HEAL_TEAM,
                new int[]{1}, new int[]{0}, hostNow + 3000, 2);
        receive(snapshot(hostNow, heal).put(TcpServer.JSON_POWERUP_GRANT, TcpServer.powerupGrantJson(heal)));
        client.events.clear();
        client.replayPowerups();
        assertEquals(1, client.events.size());
        assertFalse(client.events.get(0).getBooleanExtra(NetMsg.INTENT_POWERUP_SNAPSHOT, true));
        client.acknowledgePowerup(1);
        client.replayPowerups();
        assertEquals(1, client.events.size());
    }

    @Test
    public void playingHostAlsoReplaysUnacknowledgedLiveEffectsWithoutReapplyingSnapshots() throws Exception {
        server.setDedicated(false);
        long now = SystemClock.elapsedRealtime();
        Powerups.Grant heal = new Powerups.Grant(1, 1, 0, Powerups.HEAL_TEAM,
                new int[]{1}, new int[]{0}, now + 3000, 2);
        assertTrue(server.restorePowerupAuthority(snapshot(now, heal)));
        invoke(server, "publishPowerupStateLocked", new Class<?>[]{Powerups.Grant.class}, heal);
        assertEquals(1, server.events.size());
        server.events.clear();
        server.replayPowerups();
        assertEquals(1, server.events.size());
        assertFalse(server.events.get(0).getBooleanExtra(NetMsg.INTENT_POWERUP_SNAPSHOT, true));
        server.acknowledgePowerup(1);
        server.replayPowerups();
        assertEquals(1, server.events.size());
    }

    @Test
    public void olderSameSequenceSnapshotCannotReplaceNewerConsumedCheckpointState() throws Exception {
        long hostNow = SystemClock.elapsedRealtime() + 5000;
        Powerups.Snapshot newer = new Powerups.Snapshot(1, hostNow, new ArrayList<>(),
                Arrays.asList(new Powerups.ScanState(1, 0, 1, 0, 2)));
        receive(TcpServer.powerupSnapshotJson(roster.token, newer));
        Powerups.Snapshot older = new Powerups.Snapshot(1, hostNow - 1, new ArrayList<>(),
                Arrays.asList(new Powerups.ScanState(1, 0, 0, 0, 0)));
        receive(TcpServer.powerupSnapshotJson(roster.token, older));
        assertEquals(1, scan(client.powerupAuthoritySnapshot(), 1).getInt("mask"));
        assertFalse(server.restorePowerupAuthority(null));
    }

    @Test
    public void timedSnapshotReplaysForResumeAndExportsTheOriginalLocalDeadline() throws Exception {
        long localNow = SystemClock.elapsedRealtime(), hostNow = localNow + 5000;
        Powerups.Grant buff = new Powerups.Grant(1, 1, 0, Powerups.INVULNERABLE,
                new int[]{1}, new int[]{0}, hostNow + 15000, 2);
        receive(snapshot(hostNow, buff));
        assertEquals(1, client.events.size());
        assertTrue(client.events.get(0).getBooleanExtra(NetMsg.INTENT_POWERUP_SNAPSHOT, false));
        client.replayPowerups();
        assertEquals(2, client.events.size());
        JSONObject exported = client.powerupAuthoritySnapshot();
        assertNotNull(exported);
        assertEquals(localNow + 15000, exported.getJSONArray(TcpServer.JSON_POWERUP_GRANTS)
                .getJSONObject(0).getLong("expires"));
        assertTrue(server.restorePowerupAuthority(exported));
        request(1, 0, roster.token);
        assertEquals(localNow + 15000, lastFrame(0).getJSONArray(TcpServer.JSON_POWERUP_GRANTS)
                .getJSONObject(0).getLong("expires"));
    }

    @Test
    public void reconnectSnapshotNeverAppliesAnInstantEffectAndMalformedSnapshotIsAtomic() throws Exception {
        long hostNow = SystemClock.elapsedRealtime() + 5000;
        Powerups.Grant damage = new Powerups.Grant(1, 1, 0, Powerups.DAMAGE_TEAM,
                new int[]{2}, new int[]{0}, hostNow + 3000, 2);
        JSONObject frame = snapshot(hostNow, damage);
        receive(frame);
        assertEquals(0, client.events.size());
        JSONObject before = client.powerupAuthoritySnapshot();
        frame.getJSONArray(TcpServer.JSON_POWERUP_GRANTS).getJSONObject(0)
                .put("lives", new JSONArray().put(1));
        receive(frame);
        assertEquals(0, client.events.size());
        assertEquals(before.getJSONObject(TcpServer.JSON_POWERUP_STATE).toString(),
                client.powerupAuthoritySnapshot().getJSONObject(TcpServer.JSON_POWERUP_STATE).toString());
        globals.mRoundRoster = new RoundRoster(UUID.randomUUID().toString());
        assertNull(client.powerupAuthoritySnapshot());
        client.replayPowerups();
        assertEquals(0, client.events.size());
    }

    @Test
    public void largestAuthoritySnapshotStaysBelowTheModifiedUtfBudget() throws Exception {
        long now = GameClock.MAX_TIMESTAMP - 15001;
        int[] ids = new int[32], lives = new int[32];
        List<Powerups.ScanState> players = new ArrayList<>();
        for (int n = 0; n < ids.length; n++) {
            ids[n] = n + 1;
            lives[n] = Globals.MAX_SCOREBOARD_VALUE;
            players.add(new Powerups.ScanState(n + 1, lives[n], 255, 4, 2));
        }
        List<Powerups.Grant> grants = new ArrayList<>();
        long nextId = GameClock.MAX_TIMESTAMP - 1;
        for (int n = 0; n < Powerups.MAX_ACTIVE_GRANTS; n++)
            grants.add(new Powerups.Grant(nextId - Powerups.MAX_ACTIVE_GRANTS + n,
                    32, lives[0], Powerups.SHIELD_TEAM, ids, lives, now + 15000, 2));
        JSONObject frame = TcpServer.powerupSnapshotJson(roster.token,
                new Powerups.Snapshot(nextId, now, grants, players));
        frame.put(TcpServer.JSON_POWERUP_GRANT, TcpServer.powerupGrantJson(grants.get(grants.size() - 1)));
        String message = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + frame;
        assertTrue(message.length() < 60000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new java.io.DataOutputStream(bytes).writeUTF(message);
        assertTrue(bytes.size() < 60002);
        assertEquals(message, new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUTF());
        Powerups.Snapshot decoded = TcpServer.readPowerupSnapshot(frame);
        assertEquals(128, decoded.grants.size());
        assertTrue(new Powerups.Authority().restore(decoded, now));
        for (Powerups.Grant grant : decoded.grants) {
            assertArrayEquals(ids, grant.targets);
            assertArrayEquals(lives, grant.targetLives);
        }
    }

    @Test
    public void compactRecipientsAndLegacyArraysRoundTripWithoutCoercion() throws Exception {
        long now = SystemClock.elapsedRealtime();
        Powerups.Grant grant = new Powerups.Grant(1, 1, 0, Powerups.SHIELD_TEAM,
                new int[]{1, 32}, new int[]{0, Globals.MAX_SCOREBOARD_VALUE}, now + 15000, 2);
        JSONObject compact = TcpServer.powerupGrantJson(grant);
        assertEquals(24, compact.getString("recipients").length());
        assertFalse(compact.has("targets"));
        assertFalse(compact.has("lives"));
        Powerups.Grant decoded = TcpServer.readPowerupGrant(compact, now);
        assertArrayEquals(grant.targets, decoded.targets);
        assertArrayEquals(grant.targetLives, decoded.targetLives);
        JSONObject legacy = new JSONObject(compact.toString());
        legacy.remove("recipients");
        legacy.put("targets", new JSONArray().put(1).put(32));
        legacy.put("lives", new JSONArray().put(0).put(Globals.MAX_SCOREBOARD_VALUE));
        decoded = TcpServer.readPowerupGrant(legacy, now);
        assertArrayEquals(grant.targets, decoded.targets);
        assertArrayEquals(grant.targetLives, decoded.targetLives);
        for (JSONObject invalid : Arrays.asList(
                new JSONObject(compact.toString()).put("recipients", 1),
                new JSONObject(compact.toString()).put("recipients", "00000000"),
                new JSONObject(compact.toString()).put("recipients", "00000001ffffffff"),
                new JSONObject(compact.toString()).put("recipients", "800000007fffffff"),
                new JSONObject(compact.toString()).put("recipients", compact.getString("recipients") + "0"),
                new JSONObject(compact.toString()).put("targets", new JSONArray().put(1)),
                new JSONObject(compact.toString()).put("lives", new JSONArray().put(0)),
                new JSONObject(legacy.toString()).put("lives", new JSONArray().put("0").put(1)))) {
            try {
                TcpServer.readPowerupGrant(invalid, now);
                fail("Malformed or ambiguous power-up recipients were accepted: " + invalid);
            } catch (org.json.JSONException expected) { }
        }
    }

    @Test
    public void full32PlayerRewardSetSurvivesClientRecoveryAndHostHandoff() throws Exception {
        RoundRoster full = new RoundRoster(roster.token);
        List<Powerups.ScanState> players = new ArrayList<>();
        List<Powerups.Grant> grants = new ArrayList<>();
        long hostNow = SystemClock.elapsedRealtime() + 5000;
        for (int id = 1; id <= 32; id++) {
            full.restore(new RoundRoster.Member(UUID.randomUUID().toString(), id, 1, 1));
            players.add(new Powerups.ScanState(id, 0, 255, 4, 2, 8));
            for (int reward = 0; reward < 4; reward++) {
                grants.add(new Powerups.Grant(grants.size() + 1, id, 0, Powerups.INVULNERABLE,
                        new int[]{id}, new int[]{0}, hostNow + 15000, 2));
            }
        }
        roster = full;
        globals.installRoundRoster(roster);
        for (int id = 1; id <= 32; id++) alive(id, 0);
        receive(TcpServer.powerupSnapshotJson(roster.token,
                new Powerups.Snapshot(129, hostNow, grants, players)));
        assertEquals(128, client.events.size());
        JSONObject recovered = client.powerupAuthoritySnapshot();
        assertNotNull(recovered);
        assertEquals(128, TcpServer.readPowerupSnapshot(recovered).grants.size());
        client.replayPowerups();
        assertEquals(256, client.events.size());
        assertTrue(server.restorePowerupAuthority(recovered));
        Powerups.Snapshot handedOff = TcpServer.readPowerupSnapshot(server.powerupAuthoritySnapshot());
        assertEquals(128, handedOff.grants.size());
        int[] perPlayer = new int[33];
        for (Powerups.Grant restored : handedOff.grants) perPlayer[restored.source]++;
        for (int id = 1; id <= 32; id++) assertEquals("Recovery lost player " + id + " rewards", 4, perPlayer[id]);
    }

    @Test
    public void disconnectedClientRetriesAll128LiveInstantRewardsAfterRejoining() throws Exception {
        RoundRoster full = new RoundRoster(roster.token);
        List<Powerups.ScanState> players = new ArrayList<>();
        List<Powerups.Grant> grants = new ArrayList<>();
        long hostNow = SystemClock.elapsedRealtime() + 5000;
        for (int id = 1; id <= 32; id++) {
            full.restore(new RoundRoster.Member(UUID.randomUUID().toString(), id, 1, 1));
            players.add(new Powerups.ScanState(id, 0, 255, 4, 2, 8));
            for (int reward = 0; reward < 4; reward++) {
                grants.add(new Powerups.Grant(grants.size() + 1, id, 0, Powerups.HEAL_TEAM,
                        new int[]{1}, new int[]{0}, hostNow + Powerups.durationMillis(Powerups.HEAL_TEAM), 2));
            }
        }
        roster = full;
        globals.installRoundRoster(roster);
        for (int id = 1; id <= 32; id++) alive(id, 0);
        invoke(source, "close", new Class<?>[0]);
        for (Powerups.Grant grant : grants) {
            JSONObject frame = TcpServer.powerupSnapshotJson(roster.token,
                    new Powerups.Snapshot(129, hostNow, grants, players));
            frame.put(TcpServer.JSON_POWERUP_GRANT, TcpServer.powerupGrantJson(grant));
            assertFalse((Boolean) invoke(source, "sendTCPMessage",
                    new Class<?>[]{String.class, boolean.class},
                    TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON + frame, true));
        }
        assertEquals(128, ((java.util.Queue<?>) field(clientType, "messageQueue").get(source)).size());
        MemorySocket recovered = new MemorySocket();
        sockets.add(recovered);
        invoke(source, "rejoin", new Class<?>[]{Socket.class}, recovered);
        DataInputStream frames = new DataInputStream(new ByteArrayInputStream(recovered.bytes.toByteArray()));
        for (int id = 1; id <= 128; id++) {
            String wire = frames.readUTF();
            JSONObject frame = new JSONObject(wire.substring(TcpServer.TCPMESSAGE_PREFIX.length()
                    + TcpServer.TCPPREFIX_JSON.length()));
            // Verify wire retention independently of the three-second delivery
            // deadline. Slow-device construction/parsing must not refresh it.
            Powerups.Grant delivered = TcpServer.readPowerupGrant(
                    frame.getJSONObject(TcpServer.JSON_POWERUP_GRANT), hostNow);
            assertEquals(id, delivered.id);
            assertEquals(Powerups.HEAL_TEAM, delivered.kind);
            assertEquals(hostNow + Powerups.durationMillis(Powerups.HEAL_TEAM), delivered.expiresAt);
            assertArrayEquals(new int[]{1}, delivered.targets);
            assertArrayEquals(new int[]{0}, delivered.targetLives);
        }
        assertEquals(0, frames.available());
        assertTrue(((java.util.Queue<?>) field(clientType, "messageQueue").get(source)).isEmpty());
    }

    private void alive(int id, int life) {
        RoundRoster.Member member = roster.seat(id);
        roster.update(id, member.generation, member.kills, life, member.captures, member.infected);
        globals.recordPowerupRuntime(roster.token, id, member.generation, life,
                Globals.GAME_STATE_RUNNING, 20, false);
    }

    private Object connection(int id) throws Exception {
        Constructor<?> constructor = clientType.getDeclaredConstructor(TcpServer.class);
        constructor.setAccessible(true);
        Object connection = constructor.newInstance(server);
        MemorySocket socket = new MemorySocket();
        sockets.add(socket);
        assertTrue((Boolean) invoke(connection, "initialize", new Class<?>[]{Socket.class, int.class}, socket, id));
        set(connection, "mPlayerID", (byte) id);
        set(connection, "playerIdentity", roster.seat(id).identity);
        connections.put(id, connection);
        return connection;
    }

    private void request(int mask, int life, String token) throws Exception {
        // A forged JSON source is irrelevant: the parser binds the sender to
        // its registered connection before authorizing the request.
        JSONObject message = new JSONObject().put(TcpServer.JSON_POWERUP_SCAN, mask)
                .put(TcpServer.JSON_POWERUP_LIFE, life).put(TcpServer.JSON_ROUND_TOKEN, token).put("source", 3);
        invoke(parser, "parsePlayerInfo", new Class<?>[]{String.class, clientType}, message.toString(), source);
    }

    private void receive(JSONObject frame) throws Exception {
        invoke(client, "parseGameInfo", new Class<?>[]{String.class}, frame.toString());
    }

    private JSONObject snapshot(long clock, Powerups.Grant grant) throws Exception {
        List<Powerups.ScanState> players = new ArrayList<>();
        for (int id = 1; id <= 3; id++) players.add(new Powerups.ScanState(id, 0, 0, 0, 0));
        return TcpServer.powerupSnapshotJson(roster.token,
                new Powerups.Snapshot(grant.id + 1, clock, Arrays.asList(grant), players));
    }

    private JSONObject lastFrame(int socket) throws Exception {
        DataInputStream messages = new DataInputStream(new ByteArrayInputStream(sockets.get(socket).bytes.toByteArray()));
        String last = null;
        while (messages.available() > 0) last = messages.readUTF();
        assertNotNull(last);
        return new JSONObject(last.substring(TcpServer.TCPMESSAGE_PREFIX.length() + TcpServer.TCPPREFIX_JSON.length()));
    }

    private static JSONObject scan(JSONObject frame, int id) throws Exception {
        JSONArray players = frame.getJSONObject(TcpServer.JSON_POWERUP_STATE).getJSONArray("players");
        for (int n = 0; n < players.length(); n++) if (players.getJSONObject(n).getInt("id") == id)
            return players.getJSONObject(n);
        throw new AssertionError("Missing power-up scan state");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof TcpServer ? TcpServer.class
                : target instanceof TcpClient ? TcpClient.class : target.getClass();
        field(type, name).set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Class<?> type = target instanceof TcpServer ? TcpServer.class
                : target instanceof TcpClient ? TcpClient.class : target.getClass();
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static final class MemorySocket extends Socket {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return bytes; }
        @Override public void setTcpNoDelay(boolean value) { }
    }

    private static final class RecordingServer extends TcpServer {
        final List<Intent> events = new ArrayList<>();
        @Override public void sendBroadcast(Intent intent) {
            if (NetMsg.NETMSG_POWERUP.equals(intent.getAction())) events.add(new Intent(intent));
        }
    }

    private static final class RecordingClient extends TcpClient {
        final List<Intent> events = new ArrayList<>();
        @Override public void sendBroadcast(Intent intent) {
            if (NetMsg.NETMSG_POWERUP.equals(intent.getAction())) events.add(new Intent(intent));
        }
    }
}
