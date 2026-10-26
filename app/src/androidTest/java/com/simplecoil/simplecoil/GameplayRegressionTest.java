package com.simplecoil.simplecoil;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AnimationDrawable;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.widget.Chronometer;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.simplecoil.protocol.GameStartCountdown;
import com.simplecoil.protocol.Powerups;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the real activity's timer and telemetry paths, with no blaster or network traffic. */
@RunWith(AndroidJUnit4.class)
public class GameplayRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity testActivity;
    private RecordingBluetoothService bluetooth;
    private RecordingUDPService udp;
    private BluetoothLeService originalBluetooth;
    private UDPListenerService originalUDP;
    private TcpClient originalTcpClient;
    private TcpServer originalTcpServer;
    private RecordingTcpClient tcp;
    private byte originalPairedGrenade;
    private int originalGrenadeDamage;
    private int originalEliminationCount;
    private boolean eliminationCountCaptured;
    private int originalPowerupQrRequired;
    private boolean originalRespawnQrEnabled;
    private boolean originalLobbyBenched;
    private boolean originalWaitingForRoundEnd;
    private String originalSittingOutToken;
    private com.simplecoil.protocol.RoundRoster originalRoundRoster;
    private boolean originalBalancedRandom, originalBalancedRequireQr, originalBalancedPrepared, originalLobbyRoundActive;
    private Map<Byte, Integer> originalBalancedTeams;
    private Set<Byte> originalBalancedCheckedIn;
    private Map<String, Integer> originalRetainedBalancedTeams;
    private SharedPreferences matchPreferences;
    private Map<String, ?> originalMatchPreferences;

    @Before
    public void setUp() {
        Globals initialGlobals = Globals.getInstance();
        originalBalancedRandom = initialGlobals.mBalancedRandom;
        originalBalancedRequireQr = initialGlobals.mBalancedRequireQr;
        originalBalancedPrepared = initialGlobals.mBalancedGamePrepared;
        originalLobbyRoundActive = initialGlobals.mLobbyRoundActive;
        originalBalancedTeams = initialGlobals.mBalancedTeams;
        originalBalancedCheckedIn = initialGlobals.mBalancedCheckedIn;
        originalRetainedBalancedTeams = initialGlobals.mRetainedBalancedTeams;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            originalEliminationCount = (int) get(null, "mEliminationCount");
            eliminationCountCaptured = true;
            set(null, "mEliminationCount", 0);
        });
        originalRoundRoster = Globals.getInstance().mRoundRoster;
        Globals.getInstance().mRoundRoster = null;
        Globals.getInstance().mRequestedJoinTeam = 0;
        originalPairedGrenade = Globals.getInstance().mPairedGrenadeID;
        originalGrenadeDamage = Globals.getInstance().mGrenadeDamage;
        originalPowerupQrRequired = Globals.getInstance().mPowerupQrRequired;
        originalRespawnQrEnabled = Globals.getInstance().mRespawnQrEnabled;
        originalLobbyBenched = Globals.getInstance().mLocalLobbyBenched;
        originalWaitingForRoundEnd = Globals.getInstance().mWaitingForLobbyRoundEnd;
        originalSittingOutToken = Globals.getInstance().mSittingOutRoundToken;
        Globals.getInstance().mWaitingForLobbyRoundEnd = false;
        Globals.getInstance().mSittingOutRoundToken = null;
        Globals.getInstance().mLocalLobbyBenched = false;
        Globals.getInstance().mPairedGrenadeID = 0;
        Globals.getInstance().mGrenadeDamage = Globals.DAMAGE_PER_HIT;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mEndGameVotes = 0;
        Globals.getInstance().mEndGameVoteRequested = false;
        Globals.getInstance().mUseGPS = false;
        matchPreferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("MatchSession", Context.MODE_PRIVATE);
        originalMatchPreferences = new HashMap<>(matchPreferences.getAll());
        matchPreferences.edit().clear().commit();
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(activity -> {
            testActivity = activity;
            originalBluetooth = (BluetoothLeService) get(activity, "mBluetoothLeService");
            originalUDP = (UDPListenerService) get(activity, "mUDPListenerService");
            originalTcpClient = (TcpClient) get(activity, "mTcpClient");
            originalTcpServer = (TcpServer) get(activity, "mTcpServer");
            bluetooth = new RecordingBluetoothService();
            udp = new RecordingUDPService();
            tcp = new RecordingTcpClient();
            set(activity, "mBluetoothLeService", bluetooth);
            set(activity, "mUDPListenerService", udp);
            set(activity, "mTcpClient", tcp);
            set(activity, "mTcpServer", new TcpServer());
            set(activity, "mCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(activity, "mUseNetwork", true);
            set(activity, "mPhoneHostingEnabled", true);
            set(activity, "mCommunicating", true);
            set(activity, "mReloading", 0);
            set(activity, "mNetworkTeam", 1);
            set(activity, "mHealth", 20);
            set(activity, "mShield", 0);
            set(activity, "mLastTeam", (byte) 1);
            set(activity, "mLastShotCount", (byte) 30);
            set(activity, "mHasLivesLimit", false);
            // Deaths survive real activity recreation, but separate tests
            // must not inherit a previous test's life generation.
            set(activity, "mEliminationCount", 0);
            set(activity, "mStartGameTimer", false);
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);
            // Skip the cosmetic animation so damage tests do not leave delayed UI work.
            set(activity, "mHitAnimation", new AnimationDrawable());
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mBalancedRandom = false;
            globals.mBalancedRequireQr = true;
            globals.clearBalancedAssignments();
            globals.mLobbyRoundActive = false;
            globals.mFullShields = 0;
            globals.mFullReload = 30;
            globals.mRespawnTime = 10;
            globals.mRespawnQrEnabled = true;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mOverrideLives = false;
            globals.mOnlyServerSettings = false;
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
                Globals.PlayerSettings first = new Globals.PlayerSettings();
                first.damage = -5;
                globals.mPlayerSettings.put((byte) 17, first);
                Globals.PlayerSettings second = new Globals.PlayerSettings();
                second.damage = -7;
                globals.mPlayerSettings.put((byte) 18, second);
            } finally {
                globals.mPlayerSettingsSemaphore.release();
            }
        });
    }

    @After
    public void tearDown() {
        if (scenario == null) {
            restoreEliminationCount();
            restoreMatchPreferences();
            return;
        }
        // A live game's rendering/confirmation dialog can keep Android 5's
        // idle handler pending. Stop it on the main thread before waiting for
        // ActivityScenario's lifecycle cleanup to become idle.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FullscreenActivity activity = testActivity;
            set(activity, "mUseNetwork", false);
            invoke(activity, "endGame");
            set(activity, "mBluetoothLeService", originalBluetooth);
            set(activity, "mUDPListenerService", originalUDP);
            set(activity, "mTcpClient", originalTcpClient);
            set(activity, "mTcpServer", originalTcpServer);
            Globals.getInstance().mFullShields = Globals.MAX_SHIELDS;
            Globals.getInstance().mFullHealth = Globals.MAX_HEALTH;
            Globals.getInstance().mFullReload = Globals.RELOAD_COUNT;
            Globals.getInstance().mBossMode = false;
            Globals.getInstance().mInfectionMode = false;
            Globals.getInstance().resetInfectedPlayers();
            Globals.getInstance().mReloadTime = Globals.RELOAD_TIME_MILLISECONDS;
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            Globals.getInstance().mOverrideLives = false;
            set(activity, "mHasLivesLimit", false);
        });
        try {
            scenario.close();
        } finally {
            restoreEliminationCount();
            restoreMatchPreferences();
        }
        if (udp != null)
            udp.onDestroy();
        Globals.getInstance().mPairedGrenadeID = originalPairedGrenade;
        Globals.getInstance().mGrenadeDamage = originalGrenadeDamage;
        Globals.getInstance().mPowerupQrRequired = originalPowerupQrRequired;
        Globals.getInstance().mRespawnQrEnabled = originalRespawnQrEnabled;
        Globals.getInstance().mLocalLobbyBenched = originalLobbyBenched;
        Globals.getInstance().mWaitingForLobbyRoundEnd = originalWaitingForRoundEnd;
        Globals.getInstance().mSittingOutRoundToken = originalSittingOutToken;
        Globals.getInstance().mRoundRoster = originalRoundRoster;
        Globals.getInstance().mRequestedJoinTeam = 0;
        Globals.getInstance().mBalancedRandom = originalBalancedRandom;
        Globals.getInstance().mBalancedRequireQr = originalBalancedRequireQr;
        Globals.getInstance().mBalancedGamePrepared = originalBalancedPrepared;
        Globals.getInstance().mBalancedTeams = originalBalancedTeams;
        Globals.getInstance().mBalancedCheckedIn = originalBalancedCheckedIn;
        Globals.getInstance().mRetainedBalancedTeams = originalRetainedBalancedTeams;
        Globals.getInstance().mLobbyRoundActive = originalLobbyRoundActive;
    }

    private void restoreMatchPreferences() {
        if (matchPreferences == null || originalMatchPreferences == null) return;
        SharedPreferences.Editor editor = matchPreferences.edit().clear();
        for (Map.Entry<String, ?> entry : originalMatchPreferences.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
        }
        editor.commit();
    }

    private void restoreEliminationCount() {
        if (eliminationCountCaptured)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    set(null, "mEliminationCount", originalEliminationCount));
    }

    @Test
    public void balancedLobbyHostRebalanceButtonRequestsExactlyOneRebalance() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                RecordingLobbyTcpServer server = prepareBalancedLobby(activity);
                invoke(activity, "renderLobby");
                View button = activity.findViewById(R.id.lobby_rebalance_button);
                assertEquals(View.VISIBLE, button.getVisibility());
                assertTrue(button.isEnabled());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.lobby_rebalance_hint).getVisibility());

                button.performClick();

                assertEquals(1, server.rebalances);
            }
        });
    }

    @Test
    public void balancedLobbyRebalanceRejectsClientsCountdownPendingStartsAndUnavailableHost() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                RecordingLobbyTcpServer server = prepareBalancedLobby(activity);
                View button = activity.findViewById(R.id.lobby_rebalance_button);
                set(activity, "mIsServer", false);
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, button.getVisibility());
                invoke(activity, "rebalanceLobbyTeams");
                set(activity, "mIsServer", true);
                for (int blocked = 0; blocked < 6; blocked++) {
                    server.ready = blocked != 0;
                    server.pendingStart = blocked == 1;
                    set(activity, "mLobbyTakeoverPending", blocked == 2);
                    set(activity, "mReady", blocked != 3);
                    Globals.getInstance().mLobbyRoundActive = blocked == 4;
                    Globals.getInstance().mGameState = blocked == 5
                            ? Globals.GAME_STATE_ELIMINATED : Globals.GAME_STATE_NONE;
                    invoke(activity, "renderLobby");
                    assertFalse("Unsafe lobby state enabled Rebalance", button.isEnabled());
                    invoke(activity, "rebalanceLobbyTeams");
                }
                assertEquals("A rejected action still reached the host", 0, server.rebalances);
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                Globals.getInstance().mLobbyRoundActive = false;
                set(activity, "mLobbyTakeoverPending", false);
                set(activity, "mReady", true);
                server.pendingStart = false;
                server.ready = true;
                Globals.getInstance().mBalancedRandom = false;
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, button.getVisibility());
                invoke(activity, "rebalanceLobbyTeams");
                assertEquals(0, server.rebalances);
            }
        });
    }

    @Test
    public void balancedRoundEndRetainsModeAndUuidTeamsButClearsRoundCheckIns() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences preferences = new BalancedModePreferences(activity)) {
                RecordingLobbyTcpServer server = prepareBalancedLobby(activity);
                // A network-selected mode can differ from the old local preference.
                preferences.preferences.edit().putBoolean(FullscreenActivity.PREF_BALANCED_MODE, false).commit();
                Globals globals = Globals.getInstance();
                Map<String, Integer> remembered = Collections.singletonMap(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 2);
                globals.setRetainedBalancedTeams(remembered);
                globals.setBalancedAssignments(Collections.singletonMap((byte) 1, 2),
                        Collections.singleton((byte) 1));
                globals.mBalancedGamePrepared = true;
                globals.mGameState = Globals.GAME_STATE_RUNNING;

                invoke(activity, "endGame");

                assertTrue(globals.mBalancedRandom);
                assertTrue(globals.mBalancedRequireQr);
                assertEquals(Globals.GAME_MODE_2TEAMS, globals.mGameMode);
                assertEquals(remembered, globals.mRetainedBalancedTeams);
                assertTrue(globals.mBalancedTeams.isEmpty());
                assertTrue(globals.mBalancedCheckedIn.isEmpty());
                assertFalse(globals.mBalancedGamePrepared);
                assertEquals(1, server.preservedCancellations);
                assertFalse((boolean) get(activity, "mReady"));
            }
        });
    }

    @Test
    public void leavingBalancedRoundClearsRememberedTeamsAndReturnsToLocalDefaultMode() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences preferences = new BalancedModePreferences(activity)) {
                prepareBalancedLobby(activity);
                preferences.preferences.edit().putBoolean(FullscreenActivity.PREF_BALANCED_MODE, false)
                        .putBoolean(FullscreenActivity.PREF_TOURNAMENT_MODE, false)
                        .putInt(FullscreenActivity.PREF_GAME_MODE, Globals.GAME_MODE_4TEAMS).commit();
                Globals globals = Globals.getInstance();
                globals.setRetainedBalancedTeams(Collections.singletonMap(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 2));
                globals.mGameState = Globals.GAME_STATE_RUNNING;

                invoke(activity, "endGame", new Class<?>[]{boolean.class}, true);

                assertFalse(globals.mBalancedRandom);
                assertEquals(Globals.GAME_MODE_4TEAMS, globals.mGameMode);
                assertTrue(globals.mRetainedBalancedTeams.isEmpty());
            }
        });
    }

    @Test
    public void balancedQrScanWaitsForRoundPreparationEvenWhenTeamsAreRetained() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                prepareBalancedLobby(activity);
                Globals globals = Globals.getInstance();
                globals.setBalancedAssignments(Collections.singletonMap(globals.mPlayerID, 1), Collections.emptySet());
                globals.mBalancedGamePrepared = false;
                assertEquals(false, invokeResult(activity, "isBalancedQrCheckInPending"));
                assertEquals(false, invokeResult(activity, "isTeamAssignmentScanAvailable"));
                invoke(activity, "renderLobby");
                assertFalse(activity.findViewById(R.id.lobby_scan_button).isEnabled());

                globals.mBalancedGamePrepared = true;

                assertEquals(true, invokeResult(activity, "isBalancedQrCheckInPending"));
                assertEquals(true, invokeResult(activity, "isTeamAssignmentScanAvailable"));
                globals.setBalancedAssignments(globals.mBalancedTeams, Collections.singleton(globals.mPlayerID));
                assertEquals(false, invokeResult(activity, "isBalancedQrCheckInPending"));
            }
        });
    }

    @Test
    public void balancedClientRebalanceClosesOldCheckInScannerAndCancelsPendingResume() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                prepareBalancedLobby(activity);
                set(activity, "mIsServer", false);
                Globals globals = Globals.getInstance();
                globals.setBalancedAssignments(Collections.singletonMap(globals.mPlayerID, 2), Collections.emptySet());
                globals.mBalancedGamePrepared = true;
                set(activity, "mTeamAssignmentQrScannerActive", true);
                set(activity, "mTeamAssignmentQrOpenWhenResumed", true);
                View overlay = activity.findViewById(R.id.respawn_qr_scanner_overlay);
                overlay.setVisibility(View.VISIBLE);

                // The client receives the new assignment before another Start prepares check-in.
                globals.mBalancedGamePrepared = false;
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_BALANCEDLOBBY));

                assertEquals(false, get(activity, "mTeamAssignmentQrScannerActive"));
                assertEquals(false, get(activity, "mTeamAssignmentQrOpenWhenResumed"));
                assertEquals(View.GONE, overlay.getVisibility());
                assertEquals(false, invokeResult(activity, "isBalancedQrCheckInPending"));
                assertFalse(activity.findViewById(R.id.lobby_scan_button).isEnabled());
            }
        });
    }

    @Test
    public void balancedLobbyUpdateDoesNotCloseGameplayRespawnOrCtfScanners() {
        scenario.onActivity(activity -> {
            boolean previousCtf = Globals.getInstance().mCaptureTheFlag;
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                prepareBalancedLobby(activity);
                set(activity, "mIsServer", false);
                View overlay = activity.findViewById(R.id.respawn_qr_scanner_overlay);
                for (boolean ctf : new boolean[]{false, true}) {
                    invoke(activity, "hideQrScanner");
                    Globals.getInstance().mGameState = ctf
                            ? Globals.GAME_STATE_RUNNING : Globals.GAME_STATE_ELIMINATED;
                    Globals.getInstance().mCaptureTheFlag = ctf;
                    set(activity, ctf ? "mCtfQrScannerActive" : "mRespawnQrScannerActive", true);
                    set(activity, ctf ? "mCtfQrOpenWhenResumed" : "mRespawnQrOpenWhenResumed", true);
                    overlay.setVisibility(View.VISIBLE);

                    receiveNetwork(activity, new Intent(NetMsg.NETMSG_BALANCEDLOBBY));

                    assertEquals("A lobby refresh closed a gameplay scanner", true,
                            get(activity, ctf ? "mCtfQrScannerActive" : "mRespawnQrScannerActive"));
                    assertEquals("A lobby refresh canceled gameplay scanner recovery", true,
                            get(activity, ctf ? "mCtfQrOpenWhenResumed" : "mRespawnQrOpenWhenResumed"));
                    assertEquals(View.VISIBLE, overlay.getVisibility());
                }
            } finally {
                Globals.getInstance().mCaptureTheFlag = previousCtf;
            }
        });
    }

    @Test
    public void balancedModeMenuOffersQrAndNoQrAndChangesOnlyAnUncommittedHostLobby() {
        scenario.onActivity(activity -> {
            try (BalancedModePreferences ignored = new BalancedModePreferences(activity)) {
                RecordingLobbyTcpServer server = prepareBalancedLobby(activity);
                Globals.getInstance().mBalancedRandom = false;
                Globals.getInstance().applyTournamentRules();
                PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
                menu.getMenuInflater().inflate(R.menu.game_mode_menu, menu.getMenu());
                assertTrue(menu.getMenu().findItem(R.id.game_mode_balanced_qr_item).isVisible());
                assertTrue(menu.getMenu().findItem(R.id.game_mode_balanced_no_qr_item).isVisible());
                assertTrue(menu.getMenu().findItem(R.id.game_mode_balanced_qr_item).isEnabled());
                assertTrue(menu.getMenu().findItem(R.id.game_mode_balanced_no_qr_item).isEnabled());
                assertTrue(activity.onMenuItemClick(menu.getMenu().findItem(R.id.game_mode_balanced_no_qr_item)));
                assertTrue(Globals.getInstance().mBalancedRandom);
                assertFalse(Globals.getInstance().mBalancedRequireQr);
                assertTrue("Selecting balanced teams disabled tournament rules", Globals.getInstance().mTournamentMode);
                assertTrue(Globals.getInstance().mVibrateOnHit);
                assertFalse(Globals.getInstance().mAllowAutoShotMode);
                assertFalse(Globals.getInstance().mAllowBurst3ShotMode);
                assertEquals(Globals.RELOAD_COUNT, Globals.getInstance().mFullReload);
                assertEquals(1, server.modeChanges);
                assertTrue(activity.onMenuItemClick(menu.getMenu().findItem(R.id.game_mode_balanced_qr_item)));
                assertTrue(Globals.getInstance().mBalancedRequireQr);
                assertEquals(2, server.modeChanges);
                server.pendingStart = true;
                activity.onMenuItemClick(menu.getMenu().findItem(R.id.game_mode_balanced_no_qr_item));
                set(activity, "mIsServer", false);
                server.pendingStart = false;
                activity.onMenuItemClick(menu.getMenu().findItem(R.id.game_mode_balanced_no_qr_item));
                assertEquals("A committed round or client changed the host's mode", 2, server.modeChanges);
                assertTrue(Globals.getInstance().mBalancedRequireQr);
            }
        });
    }

    private RecordingLobbyTcpServer prepareBalancedLobby(FullscreenActivity activity) {
        Globals globals = Globals.getInstance();
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mGameMode = Globals.GAME_MODE_2TEAMS;
        globals.mTournamentMode = false;
        globals.mBossMode = false;
        globals.mCaptureTheFlag = false;
        globals.mInfectionMode = false;
        globals.mBalancedRandom = true;
        globals.mBalancedRequireQr = true;
        globals.mBalancedGamePrepared = false;
        globals.mLobbyRoundActive = false;
        set(activity, "mUseNetwork", true);
        set(activity, "mReady", true);
        set(activity, "mIsServer", true);
        set(activity, "mLobbyTakeoverPending", false);
        RecordingLobbyTcpServer server = new RecordingLobbyTcpServer();
        set(activity, "mTcpServer", server);
        return server;
    }

    @Test
    public void qrRejoinKeepsCountersAndBypassesOnlyTheNewRoundCooldown() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mLocalLobbyBenched = true;
            globals.mWaitingForLobbyRoundEnd = true;
            globals.mRoundRoster = new com.simplecoil.protocol.RoundRoster(token);
            com.simplecoil.protocol.RoundRoster.Member member = new com.simplecoil.protocol.RoundRoster.Member(
                    LiveJoin.identity(activity), 1, 1, 2);
            member.kills = 7; member.deaths = 2;
            globals.mRoundRoster.restore(member);
            set(activity, "mReady", true);
            set(activity, "mNextGameStartAllowedAt", android.os.SystemClock.elapsedRealtime() + 30000);
            Intent join = new Intent(NetMsg.NETMSG_STARTGAME).putExtra(LiveJoin.INTENT, true)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, token).putExtra(NetMsg.INTENT_ROUND_ID, 3L)
                    .putExtra(NetMsg.INTENT_START_AT, android.os.SystemClock.elapsedRealtime() - 1000)
                    .putExtra(NetMsg.INTENT_END_AT, android.os.SystemClock.elapsedRealtime() + 60000);
            invoke(activity, "startGame", new Class<?>[]{Intent.class}, join);
            assertEquals(7, get(activity, "mScore"));
            assertEquals(2, get(activity, "mEliminationCount"));
            assertFalse(globals.mLocalLobbyBenched);
            assertFalse(globals.mWaitingForLobbyRoundEnd);
            assertEquals(token, get(activity, "mActivePeerRoundToken"));
            assertTrue(globals.mGameState != Globals.GAME_STATE_NONE);
        });
    }

    @Test
    public void previousGameStatsSurviveDuplicateEndAndAbortedCountdown() {
        scenario.onActivity(activity -> {
            set(activity, "mScore", 7);
            set(activity, "mEliminationCount", 3);
            set(activity, "mHitsTaken", 25);
            set(activity, "mTeamScore", 12);
            invoke(activity, "endGame");
            assertEquals(View.VISIBLE, activity.findViewById(R.id.lobby_last_game).getVisibility());
            assertEquals(activity.getString(R.string.lobby_stat_kills, 7),
                    ((TextView) activity.findViewById(R.id.lobby_last_game_kills)).getText().toString());
            assertEquals(activity.getString(R.string.lobby_stat_deaths, 3),
                    ((TextView) activity.findViewById(R.id.lobby_last_game_deaths)).getText().toString());
            assertEquals(activity.getString(R.string.lobby_stat_hits, 25),
                    ((TextView) activity.findViewById(R.id.lobby_last_game_hits)).getText().toString());
            assertEquals(activity.getString(R.string.lobby_stat_team_kills, 12),
                    ((TextView) activity.findViewById(R.id.lobby_last_game_team)).getText().toString());
            set(activity, "mScore", 0);
            invoke(activity, "endGame");
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mStartGameTimer", true);
            invoke(activity, "endGame");
            // Load from storage, not from the activity's cached snapshot.
            set(activity, "mLastGameStats", null);
            set(activity, "mLastGameStatsLoaded", false);
            invoke(activity, "renderLobby");
            LastGameStats saved = (LastGameStats) get(activity, "mLastGameStats");
            assertEquals(7, saved.kills);
            assertEquals(3, saved.deaths);
            assertEquals(25, saved.hitsTaken);
            assertEquals(12, saved.teamKills);
            assertEquals(false, saved.leftEarly);
        });
    }

    @Test
    public void roundAnalyticsCountsBothGunDeltasButNotCountdownReloadEmpOrReconnect() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                set(activity, "mRoundShots", 0);
                set(activity, "mLastShotCount", (byte) 30);
                set(activity, "mSecondaryLastShotCount", (byte) 30);
                powerupAmmoTelemetry(activity, false, 27);
                powerupAmmoTelemetry(activity, true, 25);
                assertEquals(8, get(activity, "mRoundShots"));
                powerupAmmoTelemetry(activity, true, 25);
                assertEquals(8, get(activity, "mRoundShots"));
                set(activity, "mStartGameTimer", true);
                powerupAmmoTelemetry(activity, false, 26);
                set(activity, "mStartGameTimer", false);
                set(activity, "mReloading", 1);
                powerupAmmoTelemetry(activity, false, 0);
                set(activity, "mReloading", 0);
                set(activity, "mLastShotCount", (byte) 30);
                ((long[]) get(activity, "mPowerupUntil"))[Powerups.EMP] = SystemClock.elapsedRealtime() - 1;
                powerupAmmoTelemetry(activity, false, 0);
                ((long[]) get(activity, "mPowerupUntil"))[Powerups.EMP] = 0;
                set(activity, "mSecondaryCommunicating", false);
                powerupAmmoTelemetry(activity, true, 20);
                assertEquals("A reconnect sample is a baseline, not five shots", 8,
                        get(activity, "mRoundShots"));
            }
        });
    }

    @Test
    public void roundAnalyticsCountsConfirmationsBeforeAnimationThrottleButNotAlreadyDeadOrCountdown() {
        scenario.onActivity(activity -> {
            set(activity, "mRoundConfirmations", 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_OUT));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ALREADYDEAD));
            assertEquals(3, get(activity, "mRoundConfirmations"));
            set(activity, "mStartGameTimer", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(3, get(activity, "mRoundConfirmations"));
            assertEquals(0, get(activity, "mScore"));
        });
    }

    @Test
    public void roundAnalyticsStreaksFollowKillCreditAndDeathsRatherThanVoicePlayback() {
        scenario.onActivity(activity -> {
            com.simplecoil.protocol.RoundStatistics.Streaks streaks =
                    (com.simplecoil.protocol.RoundStatistics.Streaks) get(activity, "mRoundStreaks");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            invoke(activity, "handlePlayerElimination", new Class<?>[]{byte.class}, (byte) 17);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            invoke(activity, "handlePlayerElimination", new Class<?>[]{byte.class}, (byte) 17);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(3, streaks.bestKillStreak());
            assertEquals(2, streaks.bestDeathStreak());
            assertEquals(3, streaks.currentKillStreak());
            assertEquals(0, streaks.currentDeathStreak());
        });
    }

    @Test
    public void completedMatchingHostReportOverridesLocalEstimatesAndPersistsPostRoundAccuracy() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "11111111-1111-1111-1111-111111111111";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                set(activity, "mScore", 2);
                set(activity, "mEliminationCount", 1);
                tcp.completedReport = new RoundReport(token, 1, 5, 2, 20, 9, 3, 1, 100, 118, 60000, true);
                invoke(activity, "endGame");
                LastGameStats stats = LastGameStats.load(ignored.preferences);
                assertEquals(5, stats.kills);
                assertEquals(2, stats.deaths);
                assertEquals(20, stats.shots);
                assertEquals(9, stats.confirmedHits);
                assertEquals(3, stats.bestKillStreak);
                assertTrue(stats.analyticsComplete);
                assertEquals(activity.getString(R.string.lobby_stat_accuracy, 45.0),
                        ((TextView) activity.findViewById(R.id.lobby_last_game_accuracy)).getText().toString());
                assertEquals(activity.getString(R.string.lobby_stat_skill, 100, 118, 18),
                        ((TextView) activity.findViewById(R.id.lobby_last_game_skill)).getText().toString());
                int history = PlayerHistory.kills(activity);
                invoke(activity, "endGame");
                assertEquals(history, PlayerHistory.kills(activity));
            }
        });
    }

    @Test
    public void staleHostReportDoesNotInventAccuracyAndAbortedCountdownPreservesPriorSnapshot() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "11111111-1111-1111-1111-111111111111";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                set(activity, "mRoundShots", 10);
                set(activity, "mRoundConfirmations", 3);
                set(activity, "mScore", 2);
                tcp.completedReport = new RoundReport("22222222-2222-2222-2222-222222222222",
                        1, 9, 4, 50, 20, 5, 2, 100, 125, 60000, true);
                invoke(activity, "endGame");
                LastGameStats saved = LastGameStats.load(ignored.preferences);
                assertEquals(2, saved.kills);
                assertEquals(10, saved.shots);
                assertEquals(3, saved.confirmedHits);
                assertFalse(saved.analyticsComplete);
                assertEquals(activity.getString(R.string.lobby_stat_accuracy_unavailable),
                        ((TextView) activity.findViewById(R.id.lobby_last_game_accuracy)).getText().toString());
                int kills = PlayerHistory.kills(activity);
                Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
                set(activity, "mStartGameTimer", true);
                set(activity, "mScore", 100);
                invoke(activity, "endGame");
                assertEquals(2, LastGameStats.load(ignored.preferences).kills);
                assertEquals(kills, PlayerHistory.kills(activity));
            }
        });
    }

    @Test
    public void balancedPhoneHostWaitsForOrderedReportsAndKeepsItsRoundTeamAndWin() {
        withRecordingSpeech((activity, speech) -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                Globals globals = Globals.getInstance();
                globals.mBalancedRandom = true;
                Map<Byte, Integer> teams = new HashMap<>();
                teams.put((byte) 1, 2);
                teams.put((byte) 17, 2);
                teams.put((byte) 2, 1);
                globals.setBalancedAssignments(teams, Collections.emptySet());
                set(activity, "mIsServer", true);
                set(activity, "mTeamScore", 10);
                RecordingOrderedEndServer server = new RecordingOrderedEndServer();
                set(activity, "mTcpServer", server);
                udp.roundScores = new int[Globals.MAX_PLAYER_ID + 1];
                Arrays.fill(udp.roundScores, -1);
                udp.roundScores[17] = 12;
                udp.roundScores[2] = 7;
                assertEquals(GameOutcome.Result.WON, invokeResult(activity, "completedRoundOutcome"));
                invoke(activity, "endUDPGame");
                invoke(activity, "endGame");
                assertEquals(1, server.endRequests);
                assertEquals(0, udp.endRequests);
                assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
                globals.clearBalancedRoundState();
                assertEquals(GameOutcome.Result.LOST, invokeResult(activity, "completedRoundOutcome"));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                        .putExtra(TcpServer.EXTRA_ROUND_REPORTS_DELIVERED, true));
                assertEquals(Globals.GAME_STATE_NONE, globals.mGameState);
                LastGameStats saved = LastGameStats.load(ignored.preferences);
                assertEquals(activity.getString(R.string.lobby_team_format, 2), saved.team);
                assertEquals("normal:1.0:" + activity.getString(R.string.game_end_won_voice_prompt),
                        speech.queued.get(speech.queued.size() - 1));
            }
        });
    }

    @Test
    public void newRoundResetsAnalyticsWithoutUpdatingTheRatingDuringCountdown() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                int priorKills = PlayerHistory.kills(activity);
                int priorDeaths = PlayerHistory.deaths(activity);
                set(activity, "mUseNetwork", false);
                set(activity, "mRoundShots", 22);
                set(activity, "mRoundConfirmations", 7);
                com.simplecoil.protocol.RoundStatistics.Streaks streaks =
                        (com.simplecoil.protocol.RoundStatistics.Streaks) get(activity, "mRoundStreaks");
                streaks.onKill(); streaks.onKill(); streaks.onDeath();
                invoke(activity, "startGame");
                assertEquals(0, get(activity, "mRoundShots"));
                assertEquals(0, get(activity, "mRoundConfirmations"));
                assertEquals(0, streaks.bestKillStreak());
                assertEquals(0, streaks.bestDeathStreak());
                assertEquals(priorKills, PlayerHistory.kills(activity));
                assertEquals(priorDeaths, PlayerHistory.deaths(activity));
                invoke(activity, "endGame");
                assertEquals(priorKills, PlayerHistory.kills(activity));
                assertEquals(priorDeaths, PlayerHistory.deaths(activity));
            }
        });
    }

    @Test
    public void incompleteHostReportCannotEraseAcceptedLocalProgressOrUseItsPartialRating() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "33333333-3333-3333-3333-333333333333";
                int priorKills = PlayerHistory.kills(activity), priorDeaths = PlayerHistory.deaths(activity);
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                set(activity, "mRoundShots", 12);
                set(activity, "mScore", 4);
                set(activity, "mEliminationCount", 3);
                com.simplecoil.protocol.RoundStatistics.Streaks streaks =
                        (com.simplecoil.protocol.RoundStatistics.Streaks) get(activity, "mRoundStreaks");
                streaks.onKill(); streaks.onKill(); streaks.onKill();
                streaks.onDeath(); streaks.onDeath(); streaks.onDeath();
                tcp.completedReport = new RoundReport(token, 1, 2, 1, 6, 2, 1, 1, 77, 0, 60000, false);
                invoke(activity, "endGame");
                LastGameStats stats = LastGameStats.load(ignored.preferences);
                assertEquals(4, stats.kills);
                assertEquals(3, stats.deaths);
                assertEquals(12, stats.shots);
                assertEquals(2, stats.confirmedHits);
                assertEquals(3, stats.bestKillStreak);
                assertEquals(3, stats.bestDeathStreak);
                assertEquals(77, stats.skillBefore);
                assertEquals(com.simplecoil.protocol.RoundStatistics.skillIndex(
                        (long) priorKills + 4, (long) priorDeaths + 3), stats.skillAfter);
                assertFalse(stats.analyticsComplete);
            }
        });
    }

    @Test
    public void timedClientEndStopsBothGunsButKeepsTcpUntilTheOrderedHostReport() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity);
                 PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                String token = "44444444-4444-4444-4444-444444444444";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                set(activity, "mRoundShots", 15);
                tcp.connectedToLobby = true;
                invoke(activity, "finishTimedGame");
                assertTrue((boolean) get(activity, "mClientRoundEndPending"));
                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
                assertEquals(0, tcp.stopRequests);
                assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
                assertEquals(2, guns.second.writes.get(guns.second.writes.size() - 1)[2]);
                invoke(activity, "finishReload");
                invoke(activity, "finishSecondaryReload");
                invoke(activity, "finishSpawn", new Class<?>[]{boolean.class}, false);
                powerupAmmoTelemetry(activity, false, 0);
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
                assertEquals(15, get(activity, "mRoundShots"));
                assertEquals(0, get(activity, "mRoundConfirmations"));
                assertEquals(0, get(activity, "mScore"));
                assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
                tcp.completedReport = new RoundReport(token, 1, 4, 1, 15, 6, 2, 1, 100, 120, 60000, true);
                deliverRecordedTcpEnd(activity);
                assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                assertClientStopped();
                assertTrue(LastGameStats.load(ignored.preferences).analyticsComplete);
                assertEquals(activity.getString(R.string.lobby_stat_accuracy, 40.0),
                        ((TextView) activity.findViewById(R.id.lobby_last_game_accuracy)).getText().toString());
            }
        });
    }

    @Test
    public void matchingUdpEndWaitsForTcpReportAndUnrelatedRoundEndDoesNotFinishIt() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "55555555-5555-5555-5555-555555555555";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                tcp.connectedToLobby = true;
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, token));
                assertTrue((boolean) get(activity, "mClientRoundEndPending"));
                assertEquals(0, tcp.stopRequests);
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, "66666666-6666-6666-6666-666666666666"));
                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
                tcp.completedReport = new RoundReport(token, 1, 1, 0, 10, 2, 1, 0, 100, 109, 60000, true);
                deliverRecordedTcpEnd(activity);
                assertClientStopped();
                assertEquals(2, LastGameStats.load(ignored.preferences).confirmedHits);
                assertTrue(LastGameStats.load(ignored.preferences).analyticsComplete);
            }
        });
    }

    @Test
    public void clientReportTimeoutFinishesWithLimitedStatsAndCannotRunTwice() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "77777777-7777-7777-7777-777777777777";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                set(activity, "mRoundShots", 10);
                set(activity, "mScore", 3);
                tcp.connectedToLobby = true;
                invoke(activity, "endGame");
                Runnable timeout = (Runnable) get(activity, "mClientRoundEndTimeout");
                assertNotNull(timeout);
                assertEquals(0, tcp.stopRequests);
                timeout.run();
                assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                assertClientStopped();
                int stoppedAfterTimeout = tcp.stopRequests;
                LastGameStats saved = LastGameStats.load(ignored.preferences);
                assertEquals(3, saved.kills);
                assertEquals(10, saved.shots);
                assertFalse(saved.analyticsComplete);
                assertEquals(activity.getString(R.string.lobby_stat_accuracy_unavailable),
                        ((TextView) activity.findViewById(R.id.lobby_last_game_accuracy)).getText().toString());
                int history = PlayerHistory.kills(activity);
                timeout.run();
                assertEquals(stoppedAfterTimeout, tcp.stopRequests);
                assertEquals(history, PlayerHistory.kills(activity));
            }
        });
    }

    @Test
    public void newerRoundCancelsTheOlderClientReportTimeout() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "88888888-8888-8888-8888-888888888888";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                tcp.connectedToLobby = true;
                invoke(activity, "endGame");
                Runnable timeout = (Runnable) get(activity, "mClientRoundEndTimeout");
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                set(activity, "mUseNetwork", false);
                invoke(activity, "startGame");
                assertFalse((boolean) get(activity, "mClientRoundEndPending"));
                assertNull(get(activity, "mClientRoundEndTimeout"));
                timeout.run();
                assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
                assertTrue((boolean) get(activity, "mStartGameTimer"));
                assertEquals(0, tcp.stopRequests);
                invoke(activity, "endGame");
            }
        });
    }

    @Test
    public void explicitLeaveBypassesClientReportWaitAndCancelsItsTimeout() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "99999999-9999-9999-9999-999999999999";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                tcp.connectedToLobby = true;
                invoke(activity, "endGame");
                Runnable timeout = (Runnable) get(activity, "mClientRoundEndTimeout");
                assertNotNull(timeout);
                assertEquals(0, tcp.stopRequests);
                invoke(activity, "endGame", new Class<?>[]{boolean.class}, true);
                assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                int stoppedAfterLeave = tcp.stopRequests;
                assertTrue("Explicit leave must close the client", stoppedAfterLeave > 0);
                assertTrue(LastGameStats.load(ignored.preferences).leftEarly);
                assertNull(get(activity, "mClientRoundEndTimeout"));
                timeout.run();
                assertEquals("The cancelled timeout must not stop the client again",
                        stoppedAfterLeave, tcp.stopRequests);
            }
        });
    }

    @Test
    public void stoppedClientDoesNotDelayRoundCleanupForAMissingReport() {
        scenario.onActivity(activity -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
                set(activity, "mActivePeerRoundToken", token);
                set(activity, "mRoundAnalyticsToken", token);
                tcp.connectedToLobby = false;
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, token));
                assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                assertFalse((boolean) get(activity, "mClientRoundEndPending"));
                assertNull(get(activity, "mClientRoundEndTimeout"));
                assertClientStopped();
                assertFalse(LastGameStats.load(ignored.preferences).analyticsComplete);
            }
        });
    }

    @Test
    public void finalClientScoreboardRepairsTheDeadlineOutcomeIncludingTiedVoice() {
        withRecordingSpeech((activity, speech) -> {
            try (RoundAnalyticsPreferences ignored = new RoundAnalyticsPreferences(activity)) {
                Globals globals = Globals.getInstance();
                globals.mGameMode = Globals.GAME_MODE_FFA;
                String[] tokens = {"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "cccccccc-cccc-cccc-cccc-cccccccccccc"};
                for (int index = 0; index < tokens.length; index++) {
                    String token = tokens[index];
                    globals.mGameMode = Globals.GAME_MODE_FFA;
                    globals.mGameState = Globals.GAME_STATE_RUNNING;
                    set(activity, "mActivePeerRoundToken", token);
                    set(activity, "mRoundAnalyticsToken", token);
                    set(activity, "mScore", 5);
                    udp.roundScores = new int[Globals.MAX_PLAYER_ID + 1];
                    Arrays.fill(udp.roundScores, -1);
                    udp.roundScores[17] = 1;
                    assertEquals(GameOutcome.Result.WON, invokeResult(activity, "completedRoundOutcome"));
                    tcp.connectedToLobby = true;
                    invoke(activity, "endGame");
                    int enemyScore = index == 0 ? 5 : 3;
                    try {
                        org.json.JSONArray players = new org.json.JSONArray()
                                .put(finalScoreboardPlayer(1, 3, 1))
                                .put(finalScoreboardPlayer(17, enemyScore, 0));
                        String frame = new org.json.JSONObject().put(TcpServer.JSON_PLAYERDATA, players).toString();
                        invoke(activity, "captureFinalClientScoreboard", new Class<?>[]{String.class}, frame);
                    } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
                    tcp.completedReport = new RoundReport(token, 1, 3, 1, 10, 2, 2, 1, 100, 114, 60000, true);
                    deliverRecordedTcpEnd(activity);
                    int prompt = index == 0 ? R.string.game_end_lost_voice_prompt : R.string.game_end_tied_voice_prompt;
                    assertEquals("normal:1.0:" + activity.getString(prompt),
                            speech.queued.get(speech.queued.size() - 1));
                    assertEquals(3, LastGameStats.load(ignored.preferences).kills);
                }
            }
        });
    }

    private static org.json.JSONObject finalScoreboardPlayer(int id, int kills, int deaths)
            throws org.json.JSONException {
        return new org.json.JSONObject().put(TcpServer.JSON_PLAYERID, id)
                .put(TcpServer.JSON_PLAYERNAME, "Player " + id)
                .put(TcpServer.JSON_PLAYERPOINTS, kills).put(TcpServer.JSON_PLAYERELIMINATED, deaths);
    }

    private void assertClientStopped() {
        assertTrue("Round cleanup must stop the TCP client", tcp.stopRequests > 0);
        assertFalse("A stopped client must not retain its lobby connection", tcp.hasLobbyConnection());
    }

    private void deliverRecordedTcpEnd(FullscreenActivity activity) {
        tcp.pendingTerminal = new Intent(NetMsg.NETMSG_ENDGAME);
        receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                .putExtra(TcpClient.EXTRA_TERMINAL_EVENT_ID, 123L));
    }

    @Test
    public void leavingSavesLivesUsedAndMatchingCompletionClearsSitOutOnly() {
        scenario.onActivity(activity -> {
            String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            Globals globals = Globals.getInstance();
            set(activity, "mActivePeerRoundToken", token);
            set(activity, "mHasLivesLimit", true);
            set(activity, "mLives", 5);
            set(activity, "mEliminationCount", 2);
            set(activity, "mScore", 4);
            invoke(activity, "quitGame");
            assertEquals(0, udp.endRequests);
            assertTrue(globals.mLocalLobbyBenched);
            assertTrue(globals.mWaitingForLobbyRoundEnd);
            assertEquals(token, globals.mSittingOutRoundToken);
            LastGameStats saved = (LastGameStats) get(activity, "mLastGameStats");
            assertTrue(saved.leftEarly);
            assertEquals(3, saved.deaths);
            assertEquals(4, saved.kills);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ROUNDCOMPLETE)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
            assertTrue(globals.mLocalLobbyBenched);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ROUNDCOMPLETE)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, token));
            assertEquals(false, globals.mLocalLobbyBenched);
            assertEquals(false, globals.mWaitingForLobbyRoundEnd);
            assertEquals(false, globals.mLobbyRoundActive);
            assertNull(globals.mSittingOutRoundToken);
            assertSame("Round completion replaced the saved exit stats", saved, get(activity, "mLastGameStats"));
        });
    }

    @Test
    public void matchingRoundCompletionEndsGameButOldReceiptCannotEndNextRound() {
        scenario.onActivity(activity -> {
            String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            set(activity, "mActivePeerRoundToken", token);
            Globals globals = Globals.getInstance();
            globals.mLocalLobbyBenched = true;
            globals.mWaitingForLobbyRoundEnd = true;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ROUNDCOMPLETE)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, token));
            assertEquals(Globals.GAME_STATE_NONE, globals.mGameState);
            assertEquals(false, globals.mLocalLobbyBenched);
            assertEquals(false, globals.mWaitingForLobbyRoundEnd);
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mActivePeerRoundToken", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ROUNDCOMPLETE)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, token));
            assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
        });
    }

    @Test
    public void cancellingPeerHostAllowsCancellationToFlushBeforeStopping() {
        scenario.onActivity(activity -> {
            int[] calls = new int[3];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
                @Override public void cancelServer(boolean preserveBalancedTeams) { cancelServer(); }
                @Override public void stopTcpServer() { calls[1]++; }
                @Override public void sendTCPMessageAll(String message) { calls[2]++; }
            });
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mIsServer", true);
            set(activity, "mReady", true);
            PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
            assertTrue(activity.onMenuItemClick(menu.getMenu().add(0, R.id.cancel_server_item, 0, "Cancel")));
            assertEquals(1, calls[0]);
            assertEquals("The host must let cancellation close the connection", 0, calls[1]);
            assertEquals("Cancellation must not use a separate racing broadcast", 0, calls[2]);
            assertEquals(false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void runningGameShowsLocalGpsCoordinatesInTheUpperRightHud() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mUseGPS = true;
            TextView coordinates = activity.findViewById(R.id.gps_coordinates_tv);

            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(View.VISIBLE, coordinates.getVisibility());
            assertEquals(activity.getString(R.string.gps_acquiring), coordinates.getText().toString());

            Intent fix = new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LATITUDE, 47.60621)
                    .putExtra(NetMsg.INTENT_LONGITUDE, -122.33207);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fix);
            assertEquals(activity.getString(R.string.gps_coordinates_format,
                    47.60621, -122.33207), coordinates.getText().toString());

            globals.mGameState = Globals.GAME_STATE_NONE;
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(View.GONE, coordinates.getVisibility());
        });
    }

    @Test
    public void localGpsReadoutRetainsSixDecimalPlacesInTheLobbyAndGame() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mUseGPS = true;
            assertEquals("LAT %1$.6f\nLON %2$.6f",
                    activity.getString(R.string.gps_coordinates_format));
            TextView hud = activity.findViewById(R.id.gps_coordinates_tv);
            TextView lobby = activity.findViewById(R.id.lobby_gps_coordinates);
            Intent fix = new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LATITUDE, 47.6062119)
                    .putExtra(NetMsg.INTENT_LONGITUDE, -122.3320719);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fix);
            String first = hud.getText().toString();
            assertEquals(activity.getString(R.string.gps_coordinates_format,
                    47.6062119, -122.3320719), first);
            assertEquals(first, lobby.getText().toString());

            // Both fixes round to the same old five-place display, but the
            // sixth digit now exposes the finer change without quantizing it.
            fix.putExtra(NetMsg.INTENT_LATITUDE, 47.6062149)
                    .putExtra(NetMsg.INTENT_LONGITUDE, -122.3320749);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fix);
            assertEquals(activity.getString(R.string.gps_coordinates_format,
                    47.6062149, -122.3320749), hud.getText().toString());
            assertFalse(first.equals(hud.getText().toString()));
            assertEquals(hud.getText().toString(), lobby.getText().toString());
            assertEquals(47.6062149, (double) get(activity, "mLastGpsLatitude"), 0);
            assertEquals(-122.3320749, (double) get(activity, "mLastGpsLongitude"), 0);
        });
    }

    @Test
    public void gpsFitsWhileGrenadePairingControlsStayHidden() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mUseGPS = true;
            globals.mPairedGrenadeID = 3;
            set(activity, "mPendingGrenadeID", (byte) 4);
            activity.findViewById(R.id.connect_layout).setVisibility(View.GONE);
            activity.findViewById(R.id.play_layout).setVisibility(View.VISIBLE);
            invoke(activity, "updateGpsCoordinatesDisplay");
            invoke(activity, "updateGrenadePairingDisplay");
            ((TextView) activity.findViewById(R.id.gps_coordinates_tv)).setText(
                    activity.getString(R.string.gps_coordinates_format, 47.60621, -122.33207));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            View team = activity.findViewById(R.id.team_tv);
            View gps = activity.findViewById(R.id.gps_coordinates_tv);
            View grenade = activity.findViewById(R.id.grenade_status_tv);
            View confirm = activity.findViewById(R.id.grenade_confirm_button);
            View shots = activity.findViewById(R.id.shots_label_tv);
            View play = activity.findViewById(R.id.play_layout);
            int[] teamPosition = new int[2];
            int[] gpsPosition = new int[2];
            int[] shotsPosition = new int[2];
            int[] playPosition = new int[2];
            team.getLocationOnScreen(teamPosition);
            gps.getLocationOnScreen(gpsPosition);
            shots.getLocationOnScreen(shotsPosition);
            play.getLocationOnScreen(playPosition);
            assertTrue("GPS overlaps the team number",
                    gpsPosition[1] >= teamPosition[1] + team.getHeight());
            assertEquals(View.GONE, grenade.getVisibility());
            assertEquals(View.GONE, confirm.getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.lobby_grenade_status).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.lobby_grenade_confirm_button)
                    .getVisibility());
            assertTrue("GPS overlaps combat controls",
                    shotsPosition[1] >= gpsPosition[1] + gps.getHeight());
            assertTrue("GPS extends past the screen edge",
                    gpsPosition[0] >= playPosition[0]
                            && gpsPosition[0] + gps.getWidth() <= playPosition[0] + play.getWidth());
        });
    }

    @Test
    public void returningToPracticeTurnsLocalGpsTrackingBackOn() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mUseGPS = false;
            set(activity, "mUseNetwork", false);
            invoke(activity, "setReady", new Class<?>[]{boolean.class}, false);
            assertTrue(globals.mUseGPS);
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(activity.getString(R.string.gps_acquiring),
                    ((TextView) activity.findViewById(R.id.lobby_gps_coordinates))
                            .getText().toString());
        });
    }

    @Test
    public void gameLockUsesTheFullscreenWindowFlagForTheStatusBar() {
        scenario.onActivity(activity -> {
            invoke(activity, "enableGameLock");
            assertTrue((activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);

            invoke(activity, "disableGameLock");
            assertEquals(0, activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_FULLSCREEN);
        });
    }

    @Test
    public void lobbyShowsCoordinatesAndGpsOffIsExplicitDuringPlay() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mUseGPS = true;
            TextView lobby = activity.findViewById(R.id.lobby_gps_coordinates);
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(activity.getString(R.string.gps_acquiring), lobby.getText().toString());
            Intent fix = new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LATITUDE, 47.60621)
                    .putExtra(NetMsg.INTENT_LONGITUDE, -122.33207);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fix);
            assertEquals(activity.getString(R.string.gps_coordinates_format,
                    47.60621, -122.33207), lobby.getText().toString());

            globals.mUseGPS = false;
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            invoke(activity, "updateGpsCoordinatesDisplay");
            TextView hud = activity.findViewById(R.id.gps_coordinates_tv);
            assertEquals(View.VISIBLE, hud.getVisibility());
            assertEquals(activity.getString(R.string.gps_mode_disabled), hud.getText().toString());
            assertEquals(hud.getText().toString(), lobby.getText().toString());
        });
    }

    @Test
    public void oldGpsFixesExpireAndDoNotReturnAfterGpsIsReenabled() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mUseGPS = true;
            TextView coordinates = activity.findViewById(R.id.gps_coordinates_tv);
            long now = SystemClock.elapsedRealtime();
            Intent stale = new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                    .putExtra(NetMsg.INTENT_LATITUDE, 47.60621)
                    .putExtra(NetMsg.INTENT_LONGITUDE, -122.33207)
                    .putExtra(NetMsg.INTENT_GPS_FIX_ELAPSED_MS,
                            now - Globals.GPS_DISPLAY_FIX_MAX_AGE_MS - 1);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, stale);
            assertEquals(activity.getString(R.string.gps_acquiring), coordinates.getText().toString());

            Intent fresh = new Intent(stale).putExtra(NetMsg.INTENT_GPS_FIX_ELAPSED_MS, now);
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fresh);
            assertEquals(activity.getString(R.string.gps_coordinates_format,
                    47.60621, -122.33207), coordinates.getText().toString());

            set(activity, "mLastGpsFixElapsedMs", now - Globals.GPS_DISPLAY_FIX_MAX_AGE_MS - 1);
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(activity.getString(R.string.gps_acquiring), coordinates.getText().toString());
            globals.mUseGPS = false;
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(activity.getString(R.string.gps_mode_disabled), coordinates.getText().toString());
            globals.mUseGPS = true;
            invoke(activity, "updateGpsCoordinatesDisplay");
            assertEquals(activity.getString(R.string.gps_acquiring), coordinates.getText().toString());
        });
    }

    @Test
    public void cancellingPeerHostEndsAnActiveRoundLocally() {
        scenario.onActivity(activity -> {
            int[] calls = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
                @Override public void cancelServer(boolean preserveBalancedTeams) { cancelServer(); }
            });
            set(activity, "mIsServer", true);
            set(activity, "mReady", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
            assertTrue(activity.onMenuItemClick(menu.getMenu().add(0, R.id.cancel_server_item, 0, "Cancel")));
            assertEquals(1, calls[0]);
            assertEquals("Cancelling the host left its own round active", Globals.GAME_STATE_NONE,
                    Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void hostKeepsTenureAcrossRoundsButNotWhenLeaving() {
        scenario.onActivity(activity -> {
            set(activity, "mIsServer", true);
            udp.hostingSince = Math.max(0, SystemClock.elapsedRealtime() - 30_000);
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { }
                @Override public void cancelServer(boolean preserveBalancedTeams) { cancelServer(); }
                @Override boolean isTcpServerReady() { return true; }
            });
            invoke(activity, "endGame");
            assertEquals(udp.hostingSince, get(activity, "mResumePeerHostSince"));
            set(activity, "mPeerHostCreationPending", true);
            invoke(activity, "startPeerUdpServerIfTcpReady");
            assertEquals(udp.hostingSince, udp.resumedSince);
            assertEquals(1, udp.serverCreates);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mIsServer", true);
            invoke(activity, "quitGame");
            assertEquals("Leaving the game must not immediately reclaim hosting", -1L,
                    get(activity, "mResumePeerHostSince"));
        });
    }

    @Test
    public void endingPeerHostedGameCancelsItsTcpHost() {
        scenario.onActivity(activity -> {
            int[] calls = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
                @Override public void cancelServer(boolean preserveBalancedTeams) { cancelServer(); }
            });
            set(activity, "mIsServer", true);
            set(activity, "mReady", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            invoke(activity, "endGame");

            assertEquals("Ending a peer game left its TCP host running", 1, calls[0]);
            assertEquals(false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void invitedPlayerQuitsWithoutBroadcastingEndGame() {
        scenario.onActivity(activity -> {
            int[] quits = new int[1];
            set(activity, "mTcpClient", new TcpClient() {
                @Override public boolean isDedicatedServer() { return true; }
                @Override public void quitGame() { quits[0]++; }
                @Override public void stopTcpClient() { }
            });
            set(activity, "mJoinedFromGameInvite", true);
            set(activity, "mReady", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            invoke(activity, "quitGame");

            assertEquals(1, quits[0]);
            assertEquals("An invited player ended the shared game", 0, udp.endRequests);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mJoinedFromGameInvite"));
        });
    }

    @Test
    public void tournamentPlayerCanLeaveEvenAfterThirtySeconds() {
        final FullscreenActivity[] current = new FullscreenActivity[1];
        scenario.onActivity(activity -> {
            current[0] = activity;
            tcp.dedicated = true;
            Globals.getInstance().mTournamentMode = true;
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() - 60_000);
            invoke(activity, "updateInGameEndControl");
            assertEquals(View.VISIBLE, activity.findViewById(R.id.end_network_game_button)
                    .getVisibility());
            invoke(activity, "confirmQuitGame");
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
            assertTrue(dialog.isShowing());
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FullscreenActivity activity = current[0];
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            invoke(activity, "confirmQuitGame");
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(0, udp.endRequests);
        });
    }

    @Test
    public void tournamentHostKeepsEndGameControl() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mTournamentMode = true;
            set(activity, "mIsServer", true);
            activity.findViewById(R.id.end_network_game_button).setVisibility(View.GONE);

            invoke(activity, "updateInGameEndControl");

            TextView endControl = activity.findViewById(R.id.end_network_game_button);
            assertEquals(View.VISIBLE, endControl.getVisibility());
            assertEquals(activity.getString(R.string.game_controls_button), endControl.getText().toString());
        });
    }

    @Test
    public void firstEndApprovalDoesNotStopThePhoneAndBackRequiresConfirmation() {
        final FullscreenActivity[] current = new FullscreenActivity[1];
        scenario.onActivity(activity -> {
            current[0] = activity;
            set(activity, "mActivePeerRoundToken", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            invoke(activity, "confirmEndGame");
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FullscreenActivity activity = current[0];
            assertEquals(1, udp.endVoteRequests);
            assertEquals(0, udp.endRequests);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            activity.onBackPressed();
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
            assertTrue(dialog.isShowing());
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void peerHostCanConfirmLeavingWithoutEndingOtherPhones() {
        scenario.onActivity(activity -> {
            set(activity, "mIsServer", true);
            invoke(activity, "confirmQuitGame");
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(1, udp.peerQuitAnnouncements);
            assertEquals(0, udp.endRequests);
            assertEquals(0, udp.peerLeaveAnnouncements);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void lateInvitedPlayerCanStillLeave() {
        scenario.onActivity(activity -> {
            set(activity, "mJoinedFromGameInvite", true);
            set(activity, "mHasSynchronizedStart", true);
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() - 30_001);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            invoke(activity, "updateInGameEndControl");
            assertEquals(View.VISIBLE, activity.findViewById(R.id.end_network_game_button).getVisibility());
        });
    }

    @Test
    public void failedPeerHostDiscoveryCancelsItsTcpListener() {
        scenario.onActivity(activity -> {
            int[] calls = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
            });
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mIsServer", false);
            set(activity, "mPeerHostCreationPending", true);
            set(activity, "mReady", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_FAILEDTOJOIN));
            assertEquals("Failed UDP discovery left the TCP listener running", 1, calls[0]);
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void peerHostDoesNotAdvertiseBeforeTcpListenerIsReady() {
        org.junit.Assume.assumeTrue("Phone hosting requires an active network address",
                Globals.getIPAddress(testActivity) != null);
        scenario.onActivity(activity -> {
            int[] listenerStarts = new int[1];
            boolean[] listenerReady = new boolean[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override void startTcpServer() { listenerStarts[0]++; }
                @Override boolean isTcpServerReady() { return listenerReady[0]; }
            });
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mIsServer", false);
            set(activity, "mReady", false);
            PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
            assertTrue(activity.onMenuItemClick(menu.getMenu().add(0, R.id.create_server_item, 0, "Host")));
            assertEquals(1, listenerStarts[0]);
            assertEquals("Peer host advertised before its TCP listener was ready", 0, udp.serverCreates);
            listenerReady[0] = true;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TCPSERVERREADY));
            assertEquals(1, udp.serverCreates);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TCPSERVERREADY));
            assertEquals("Repeated TCP-ready events restarted UDP hosting", 1, udp.serverCreates);
        });
    }

    @Test
    public void startCreatesPeerLobbyBeforeTryingToStartANetworkRound() {
        org.junit.Assume.assumeTrue("Phone hosting requires an active network address",
                Globals.getIPAddress(testActivity) != null);
        scenario.onActivity(activity -> {
            int[] listenerStarts = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override void startTcpServer() { listenerStarts[0]++; }
                @Override boolean isTcpServerReady() { return false; }
            });
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            Globals.getInstance().mPlayerID = 1;
            set(activity, "mUseNetwork", true);
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);

            activity.findViewById(R.id.start_game_button).performClick();

            assertEquals(1, listenerStarts[0]);
            assertEquals(true, get(activity, "mPeerHostCreationPending"));
            assertEquals("Start advertised a lobby before its TCP listener was ready", 0,
                    udp.serverCreates);
        });
    }

    @Test
    public void failedPlayerDiscoveryDoesNotCancelAnUnrelatedTcpListener() {
        scenario.onActivity(activity -> {
            int[] calls = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
            });
            set(activity, "mPeerHostCreationPending", false);
            set(activity, "mReady", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_FAILEDTOJOIN));
            assertEquals("A player discovery failure stopped an unrelated TCP listener", 0, calls[0]);
        });
    }

    @Test
    public void lateUdpSuccessCannotReviveAFailedPeerHost() {
        scenario.onActivity(activity -> {
            int[] tcpCancellations = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { tcpCancellations[0]++; }
                @Override boolean isTcpServerReady() { return false; }
            });
            set(activity, "mPeerHostCreationPending", true);
            set(activity, "mPeerUdpServerStarting", true);
            set(activity, "mIsServer", false);
            set(activity, "mReady", false);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TCPSERVERFAILED));
            assertEquals(1, tcpCancellations[0]);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_SERVERCREATED));
            assertEquals("Late UDP success revived a failed TCP host", false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
            assertEquals(2, udp.serverCancellations);
        });
    }

    @Test
    public void firstGrenadeDisarmNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(false, 0x3D);
    }

    @Test
    public void secondGrenadeDisarmNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(true, 0x3D);
    }

    @Test
    public void firstGrenadeResetNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(false, 0x3E);
    }

    @Test
    public void secondGrenadeResetNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(true, 0x3E);
    }

    private void assertGrenadeUnpairNotifiesServer(boolean secondSlot, int command) {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            Globals.getInstance().mPairedGrenadeID = 3;
            byte[] packet = grenadePacket(secondSlot, command);
            receiveTelemetry(activity, packet);
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertEquals(1, tcp.messages.size());
            assertTrue(tcp.messages.get(0).contains("\"" + TcpServer.JSON_PAIRED_GRENADE_ID + "\":0"));
            receiveTelemetry(activity, packet);
            assertEquals("Repeated disarm telemetry should not resend the update", 1, tcp.messages.size());
            assertEquals(20, get(activity, "mHealth"));
        });
    }

    @Test
    public void grenadePairingWithReservedZeroIdIsIgnored() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            receiveTelemetry(activity, grenadePacket(false, 0x0F));
            receiveTelemetry(activity, grenadePacket(true, 0x0F));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void validGrenadePairingStillNotifiesDedicatedServer() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            receiveTelemetry(activity, grenadePacket(true, 0x3F));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertTrue(tcp.messages.isEmpty());
            invoke(activity, "confirmGrenadePairing");
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            assertEquals(1, tcp.messages.size());
            assertTrue(tcp.messages.get(0).contains("\"" + TcpServer.JSON_PAIRED_GRENADE_ID + "\":3"));
        });
    }

    @Test
    public void grenadeHoldAndReleaseRequireConfirmationToPair() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            receiveTelemetry(activity, grenadePacket(false, 0x0E));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertEquals(activity.getString(R.string.grenade_status_pairing),
                    ((TextView) activity.findViewById(R.id.lobby_grenade_status))
                            .getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.grenade_status_tv).getVisibility());
            assertTrue(tcp.messages.isEmpty());

            receiveTelemetry(activity, grenadePacket(true, 0x3F));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertEquals(activity.getString(R.string.grenade_status_confirm, 3),
                    ((TextView) activity.findViewById(R.id.lobby_grenade_status))
                            .getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.lobby_grenade_confirm_button)
                    .getVisibility());
            assertTrue(tcp.messages.isEmpty());
            invoke(activity, "confirmGrenadePairing");
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            assertEquals(activity.getString(R.string.grenade_status_paired),
                    ((TextView) activity.findViewById(R.id.lobby_grenade_status))
                            .getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.grenade_status_tv).getVisibility());
            assertEquals(1, tcp.messages.size());
        });
    }

    @Test
    public void nearbyGrenadeHoldDoesNotReplaceAnExistingPairWithoutConfirmation() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPairedGrenadeID = 3;
            receiveTelemetry(activity, grenadePacket(false, 0x0E));
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            receiveTelemetry(activity, grenadePacket(false, 0x4F));
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            assertEquals(0, udp.peerGrenadePairingPublishes);
            assertEquals(View.GONE, activity.findViewById(R.id.lobby_grenade_confirm_button)
                    .getVisibility());
        });
    }

    @Test
    public void validGrenadePairingPublishesToPeerRoster() {
        scenario.onActivity(activity -> {
            tcp.dedicated = false;
            receiveTelemetry(activity, grenadePacket(true, 0x3F));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertEquals(0, udp.peerGrenadePairingPublishes);
            invoke(activity, "confirmGrenadePairing");
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            assertEquals("Peer pairing did not leave the local phone", 1,
                    udp.peerGrenadePairingPublishes);
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void grenadePairingStatusTracksReplacementAndMatchingDisarm() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPairedGrenadeID = 3;

            // The start-pair signal may be missed, so a new ID can still be confirmed.
            receiveTelemetry(activity, grenadePacket(false, 0x4F));
            assertEquals(3, globals.mPairedGrenadeID);
            invoke(activity, "confirmGrenadePairing");
            assertEquals(4, globals.mPairedGrenadeID);
            String paired = activity.getString(R.string.grenade_status_paired);
            assertEquals(paired, ((TextView) activity.findViewById(R.id.lobby_grenade_status))
                    .getText().toString());
            assertEquals(paired, ((TextView) activity.findViewById(R.id.grenade_status_tv))
                    .getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.grenade_status_tv).getVisibility());

            receiveTelemetry(activity, grenadePacket(false, 0x3D));
            assertEquals("Another grenade's disarm removed the current pairing", 4,
                    globals.mPairedGrenadeID);
            receiveTelemetry(activity, grenadePacket(false, 0x4D));
            assertEquals(0, globals.mPairedGrenadeID);
            assertEquals(activity.getString(R.string.grenade_status_unpaired),
                    ((TextView) activity.findViewById(R.id.lobby_grenade_status)).getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.grenade_status_tv).getVisibility());
        });
    }

    @Test
    public void sequencedPeerEliminationUsesTheSharedStateWithoutUnicastFanout() {
        scenario.onActivity(activity -> {
            Intent event = new Intent(NetMsg.NETMSG_ELIMINATED)
                    .putExtra(UDPListenerService.INTENT_PLAYERID, (byte) 17)
                    .putExtra(NetMsg.INTENT_EVENT_SEQUENCE, 42L);
            // Android 5.1 can pause an ActivityScenario between setup and this
            // callback. Model a real, registered network receiver while the
            // incoming peer event is delivered.
            boolean receiverRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (!receiverRegistered)
                set(activity, "mNetworkReceiverRegistered", true);
            try {
                receiveNetwork(activity, event);
                assertEquals(0, udp.peerTeamEliminationPublishes);
                assertEquals(1, get(activity, "mScore"));
                assertEquals(1, get(activity, "mTeamScore"));
            } finally {
                if (!receiverRegistered)
                    set(activity, "mNetworkReceiverRegistered", false);
            }
        });
    }

    @Test
    public void stalePeerRoundEventsCannotAffectANewerRound() {
        scenario.onActivity(activity -> {
            final String oldRound = "55555555-5555-5555-5555-555555555555";
            final String currentRound = "66666666-6666-6666-6666-666666666666";
            set(activity, "mActivePeerRoundToken", currentRound);
            set(activity, "mScore", 3);
            set(activity, "mTeamScore", 4);
            boolean receiverRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (!receiverRegistered)
                set(activity, "mNetworkReceiverRegistered", true);
            try {
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, oldRound));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, oldRound));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_LEAVE)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, oldRound));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, oldRound));
                assertEquals(3, get(activity, "mScore"));
                assertEquals(4, get(activity, "mTeamScore"));
                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);

                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED)
                        .putExtra(NetMsg.INTENT_ROUND_TOKEN, currentRound));
                assertEquals(4, get(activity, "mScore"));
                assertEquals(5, get(activity, "mTeamScore"));
            } finally {
                if (!receiverRegistered)
                    set(activity, "mNetworkReceiverRegistered", false);
            }
        });
    }

    private static byte[] grenadePacket(boolean secondSlot, int command) {
        byte[] packet = telemetryPacket(0, 0, 0, 0);
        packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2 : FullscreenActivity.RECOIL_OFFSET_HIT_BY1]
                = (byte) Globals.GRENADE_PLAYER_ID;
        packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID : FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID]
                = (byte) command;
        return packet;
    }

    @Test
    public void fatalHitKeepsKillerVisibleAfterEarlierHitFeedbackExpires() throws InterruptedException {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            set(activity, "mHealth", 6);
            telemetry(activity, 17, 1, 0, 0);
            assertEquals(1, get(activity, "mHealth"));
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitIV")).getVisibility());
            telemetry(activity, 18, 1, 0, 0);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(View.VISIBLE, ((View) get(activity, "mEliminatedByTV")).getVisibility());
        });
        Thread.sleep(600);
        scenario.onActivity(activity -> {
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals("The earlier hit hid the respawn screen's killer name", View.VISIBLE,
                    ((View) get(activity, "mEliminatedByTV")).getVisibility());
        });
    }

    @Test
    public void fatalHitCancelsTheEarlierNamesFadeAnimation() {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            set(activity, "mHealth", 6);
            telemetry(activity, 17, 1, 0, 0);
            telemetry(activity, 18, 1, 0, 0);
            assertNull("The killer name must not inherit the nonfatal hit's fade",
                    ((View) get(activity, "mEliminatedByTV")).getAnimation());
            assertNull(get(activity, "mHitAnimation"));
        });
    }

    @Test
    public void endingRoundStopsIncomingAndOutgoingHitAnimations() {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            telemetry(activity, 17, 1, 0, 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            AnimationDrawable incoming = (AnimationDrawable) get(activity, "mHitAnimation");
            AnimationDrawable outgoing = (AnimationDrawable) get(activity, "mHitPlayerAnimation");
            assertTrue(incoming.isRunning());
            assertTrue(outgoing.isRunning());
            invoke(activity, "endGame");
            assertNull("The finished round retained its incoming animation", get(activity, "mHitAnimation"));
            assertNull("The finished round retained its hit confirmation", get(activity, "mHitPlayerAnimation"));
            assertEquals(false, incoming.isRunning());
            assertEquals(false, outgoing.isRunning());
        });
    }

    @Test
    public void incomingHitFlashesTheFullScreenDarkRed() {
        scenario.onActivity(activity -> {
            telemetry(activity, 17, 1, 0, 0);

            assertEquals("A confirmed incoming hit must flash the whole background", View.VISIBLE,
                    ((View) get(activity, "mIncomingHitFlashView")).getVisibility());
            FrameLayout.LayoutParams flash = (FrameLayout.LayoutParams)
                    ((View) get(activity, "mIncomingHitFlashView")).getLayoutParams();
            RelativeLayout.LayoutParams icon = (RelativeLayout.LayoutParams)
                    ((View) get(activity, "mHitIV")).getLayoutParams();
            assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, flash.width);
            assertEquals(RelativeLayout.TRUE,
                    icon.getRules()[RelativeLayout.CENTER_IN_PARENT]);
        });
    }

    @Test
    public void incomingHitUsesTheSensorSideForTheRedFlashAndIcon() {
        scenario.onActivity(activity -> {
            byte[] left = telemetryPacket(17, 1, 0, 0);
            left[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SENSOR] =
                    (byte) (FullscreenActivity.HIT_SENSOR_LEFT << 4);
            receiveTelemetry(activity, left);

            FrameLayout.LayoutParams flash = (FrameLayout.LayoutParams)
                    ((View) get(activity, "mIncomingHitFlashView")).getLayoutParams();
            RelativeLayout.LayoutParams icon = (RelativeLayout.LayoutParams)
                    ((View) get(activity, "mHitIV")).getLayoutParams();
            assertEquals(Gravity.START, flash.gravity);
            assertEquals(RelativeLayout.TRUE,
                    icon.getRules()[RelativeLayout.ALIGN_PARENT_START]);

            invoke(activity, "clearIncomingHitFeedback");
            byte[] right = telemetryPacket(17, 2, 0, 0);
            right[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SENSOR] =
                    (byte) (FullscreenActivity.HIT_SENSOR_RIGHT << 4);
            receiveTelemetry(activity, right);

            flash = (FrameLayout.LayoutParams)
                    ((View) get(activity, "mIncomingHitFlashView")).getLayoutParams();
            icon = (RelativeLayout.LayoutParams) ((View) get(activity, "mHitIV")).getLayoutParams();
            assertEquals(Gravity.END, flash.gravity);
            assertEquals(RelativeLayout.TRUE,
                    icon.getRules()[RelativeLayout.ALIGN_PARENT_END]);
        });
    }

    @Test
    public void sensorMaskDecodingFallsBackToCenterForAmbiguousHits() {
        assertEquals(FullscreenActivity.HIT_SENSOR_LEFT,
                FullscreenActivity.hitSensorMask((byte) 0x2f));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_LEFT,
                FullscreenActivity.resolveIncomingHitDirection(FullscreenActivity.HIT_SENSOR_LEFT));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_RIGHT,
                FullscreenActivity.resolveIncomingHitDirection(FullscreenActivity.HIT_SENSOR_RIGHT));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_LEFT,
                FullscreenActivity.resolveIncomingHitDirection(
                        FullscreenActivity.HIT_SENSOR_CENTER | FullscreenActivity.HIT_SENSOR_LEFT));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_RIGHT,
                FullscreenActivity.resolveIncomingHitDirection(
                        FullscreenActivity.HIT_SENSOR_CENTER | FullscreenActivity.HIT_SENSOR_RIGHT));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_CENTER,
                FullscreenActivity.resolveIncomingHitDirection(FullscreenActivity.HIT_SENSOR_CENTER));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_CENTER,
                FullscreenActivity.resolveIncomingHitDirection(
                        FullscreenActivity.HIT_SENSOR_LEFT | FullscreenActivity.HIT_SENSOR_RIGHT));
        assertEquals(FullscreenActivity.INCOMING_HIT_DIRECTION_CENTER,
                FullscreenActivity.resolveIncomingHitDirection(0));
    }

    @Test
    public void centerAndSideHitPlacesTheMarkerBetweenThem() {
        scenario.onActivity(activity -> {
            byte[] centerRight = telemetryPacket(17, 1, 0, 0);
            centerRight[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SENSOR] = (byte) ((
                    FullscreenActivity.HIT_SENSOR_CENTER
                            | FullscreenActivity.HIT_SENSOR_RIGHT) << 4);
            receiveTelemetry(activity, centerRight);

            View marker = (View) get(activity, "mHitIV");
            RelativeLayout.LayoutParams markerLayout =
                    (RelativeLayout.LayoutParams) marker.getLayoutParams();
            FrameLayout.LayoutParams flashLayout = (FrameLayout.LayoutParams)
                    ((View) get(activity, "mIncomingHitFlashView")).getLayoutParams();
            assertEquals(RelativeLayout.TRUE,
                    markerLayout.getRules()[RelativeLayout.CENTER_IN_PARENT]);
            assertTrue("Center/right marker was not shifted toward the right",
                    marker.getTranslationX() > 0);
            assertEquals(Gravity.END, flashLayout.gravity);
        });
    }

    @Test
    public void confirmedHitShowsLargeHitFeedback() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));

            TextView feedback = (TextView) get(activity, "mHitConfirmationTV");
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertEquals(activity.getString(R.string.combat_feedback_hit), feedback.getText().toString());
        });
    }

    @Test
    public void deadTargetAcknowledgementShowsAlreadyDeadFeedback() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ALREADYDEAD));

            TextView feedback = (TextView) get(activity, "mHitConfirmationTV");
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertEquals(activity.getString(R.string.combat_feedback_already_dead),
                    feedback.getText().toString());
        });
    }

    @Test
    public void eliminatedPlayerAcknowledgesNewEnemyShotWithoutTakingAnotherHit() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            int originalHealth = (int) get(activity, "mHealth");
            int originalHitsTaken = (int) get(activity, "mHitsTaken");

            telemetry(activity, 17, 1, 0, 0);

            assertEquals(Collections.singletonList(NetMsg.NETMSG_ALREADYDEAD + ":17"), udp.messages);
            assertEquals(originalHealth, get(activity, "mHealth"));
            assertEquals(originalHitsTaken, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void nextRoundCanShowHitConfirmationBeforeThePreviousCleanupDeadline() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            invoke(activity, "endGame");
            set(activity, "mUseNetwork", false);
            set(activity, "mNextGameStartAllowedAt", SystemClock.elapsedRealtime() - 1);
            invoke(activity, "startGame");
            CountDownTimer spawn = (CountDownTimer) get(activity, "mSpawnTimer");
            spawn.cancel();
            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
            spawn.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mStartGameTimer"));
            assertEquals(0, get(activity, "mRespawnShieldBonus"));
            set(activity, "mUseNetwork", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_OUT));
            assertEquals("The old animation suppressed this round's hit confirmation", View.VISIBLE,
                    ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
    }

    @Test
    public void newRoundDoesNotReuseHitDeduplicationOrHitCounter() {
        scenario.onActivity(activity -> {
            telemetry(activity, 17, 1, 0, 0);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));

            invoke(activity, "endGame");
            set(activity, "mUseNetwork", false);
            set(activity, "mNextGameStartAllowedAt", SystemClock.elapsedRealtime() - 1);
            invoke(activity, "startGame");
            CountDownTimer spawn = (CountDownTimer) get(activity, "mSpawnTimer");
            spawn.cancel();
            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
            spawn.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mStartGameTimer"));
            assertEquals(0, get(activity, "mRespawnShieldBonus"));

            // The offline start is only used to avoid requiring a peer round
            // nonce here. Restore the network damage table before verifying
            // that the old shot ID no longer suppresses a real hit.
            set(activity, "mUseNetwork", true);

            int shieldBeforeHit = (int) get(activity, "mShield");
            telemetry(activity, 17, 1, 0, 0);
            assertEquals("A reused weapon shot ID was filtered as a prior round's hit",
                    shieldBeforeHit - 5, get(activity, "mShield"));
            assertEquals("The hit counter carried over from the prior round", 1,
                    get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void completedRoundBlocksNextStartForThirtySeconds() {
        scenario.onActivity(activity -> {
            invoke(activity, "endGame");
            set(activity, "mUseNetwork", false);
            long deadline = (long) get(activity, "mNextGameStartAllowedAt");
            assertTrue(deadline - SystemClock.elapsedRealtime() > 29_000);

            invoke(activity, "startGame");
            assertEquals(false, get(activity, "mStartGameTimer"));

            set(activity, "mNextGameStartAllowedAt", SystemClock.elapsedRealtime() - 1);
            invoke(activity, "startGame");
            assertEquals(true, get(activity, "mStartGameTimer"));
        });
    }

    @Test
    public void shortOfflineRoundCanRestartWithoutTheBetweenGameWait() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            set(activity, "mLocalRoundStartedAt", SystemClock.elapsedRealtime() - 59_000);
            invoke(activity, "endGame");
            assertTrue((long) get(activity, "mNextGameStartAllowedAt") <= SystemClock.elapsedRealtime());
            invoke(activity, "startGame");
            assertEquals(true, get(activity, "mStartGameTimer"));
        });
    }

    @Test
    public void hostAcceptedQuickRestartOverridesADelayedClientsLocalWait() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
            set(activity, "mNextGameStartAllowedAt", SystemClock.elapsedRealtime() + 30_000);
            Intent start = new Intent(NetMsg.NETMSG_STARTGAME)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                    .putExtra(NetMsg.INTENT_ROUND_ID, 3L)
                    .putExtra(NetMsg.INTENT_START_AT, SystemClock.elapsedRealtime() + 10_000);
            invoke(activity, "startGame", new Class<?>[]{Intent.class}, start);
            assertEquals(true, get(activity, "mStartGameTimer"));
        });
    }

    @Test
    public void stalledManualHostJoinTimesOutWithoutAffectingARunningRound() {
        scenario.onActivity(activity -> {
            ((android.os.Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
            java.net.InetAddress host;
            try { host = java.net.InetAddress.getByName("192.168.1.250"); }
            catch (Exception e) { throw new AssertionError(e); }
            SharedLobby directory = (SharedLobby) get(activity, "mSharedLobby");
            long now = SystemClock.elapsedRealtime();
            directory.observe(new SharedLobby.Host(host, SharedLobby.Kind.MANUAL_PHONE, false, now, 0));
            set(activity, "mReady", true);
            set(activity, "mManualPhoneHost", true);
            set(activity, "mManualHostSelectedAt", now - 20_000);
            set(activity, "mLobbyTakeoverPending", true);
            set(activity, "mLobbyTarget", host);
            set(activity, "mLobbyConnectionDeadline", now);
            invoke(activity, "updateLobbyConnection", new Class<?>[]{long.class}, now);
            assertEquals("Host timeout interrupted play", true, get(activity, "mReady"));
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            Globals.getInstance().mRequestedJoinTeam = 1;
            invoke(activity, "updateLobbyConnection", new Class<?>[]{long.class}, now);
            assertEquals(false, get(activity, "mReady"));
            assertEquals(false, get(activity, "mManualPhoneHost"));
            assertEquals(false, get(activity, "mLobbyTakeoverPending"));
            assertEquals(0, Globals.getInstance().mRequestedJoinTeam);
            assertTrue(directory.isBackedOff(host, now + 29_999));
            assertFalse(directory.isBackedOff(host, now + 30_000));
        });
    }

    @Test
    public void committedCountdownDefersLaptopTakeoverBeforeStartUiReceivesItsBroadcast() {
        scenario.onActivity(activity -> {
            ((android.os.Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            TcpServer original = (TcpServer) get(activity, "mTcpServer");
            TcpServer starting = new TcpServer() {
                @Override boolean hasPendingOrAnnouncedStart() { return true; }
                @Override public boolean isTcpServerReady() { return true; }
            };
            try {
                set(activity, "mTcpServer", starting);
                set(activity, "mIsServer", true);
                set(activity, "mReady", true);
                set(activity, "mLobbyConnectionDeadline", 1L);
                long now = SystemClock.elapsedRealtime();
                java.net.InetAddress laptop;
                try { laptop = java.net.InetAddress.getByName("192.168.1.250"); }
                catch (Exception e) { throw new AssertionError(e); }
                ((SharedLobby) get(activity, "mSharedLobby")).observe(new SharedLobby.Host(laptop,
                        SharedLobby.Kind.LAPTOP, false, now, 0));
                assertEquals(true, invokeResult(activity, "hasCommittedRound"));
                invoke(activity, "updateSharedLobby");
                invoke(activity, "updateLobbyConnection", new Class<?>[]{long.class}, now);
                assertEquals(true, get(activity, "mIsServer"));
                assertEquals(true, get(activity, "mReady"));
                assertEquals(false, get(activity, "mLobbyTakeoverPending"));
                invoke(activity, "renderLobby");
                assertFalse(activity.findViewById(R.id.lobby_host_button).isEnabled());
            } finally {
                set(activity, "mIsServer", false);
                set(activity, "mTcpServer", original);
                starting.onDestroy();
            }
        });
    }

    @Test
    public void creditedKillStreakResetsWhenThePlayerIsEliminated() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            KillStreakVoice voice = (KillStreakVoice) get(activity, "mKillStreakVoice");
            assertEquals(R.string.kill_streak_3_voice_prompt, voice.recordKill());

            set(activity, "mHealth", 5);
            telemetry(activity, 17, 1, 0, 0);
            assertEquals(0, voice.recordKill());
        });
    }

    @Test
    public void creditedKillsQueueTheKillCueBeforeTheStreakLine() {
        scenario.onActivity(activity -> {
            @SuppressWarnings("unchecked")
            ArrayDeque<Integer> pending = (ArrayDeque<Integer>) get(activity, "mPendingVoicePrompts");
            set(activity, "mCountdownSpeechReady", false);

            receiveNetwork(activity, new Intent(NetMsg.NETMSG_OUT));
            assertTrue(pending.isEmpty());
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));

            assertEquals(Arrays.asList(R.string.enemy_destroyed_voice_prompt,
                    R.string.enemy_destroyed_voice_prompt,
                    R.string.kill_streak_2_voice_prompt), new ArrayList<>(pending));
        });
    }

    @Test
    public void britishMaleVoiceIsScopedToTheJokeAndKeepsTheKillCuePause() {
        withRecordingSpeech((activity, speech) -> {
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.enemy_destroyed_voice_prompt);
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            invoke(activity, "announceReloadReminder");
            assertEquals(Arrays.asList("normal:1.5:" + activity.getString(R.string.enemy_destroyed_voice_prompt),
                    "silence:1000", "en-gb-x-rjs-local:1.0:" + activity.getString(R.string.kill_streak_2_voice_prompt),
                    "normal:1.0:" + activity.getString(R.string.reload_voice_prompt)), speech.queued);
            assertEquals(speech.normal, speech.current);
        });
    }

    @Test
    public void unavailableBritishVoiceKeepsTheJokeAudibleInTheNormalVoice() {
        withRecordingSpeech((activity, speech) -> {
            speech.rejectMaleVoice = true;
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            assertEquals(Arrays.asList("silence:1000", "normal:1.0:"
                    + activity.getString(R.string.kill_streak_2_voice_prompt)), speech.queued);
            assertEquals(speech.normal, speech.current);
            assertEquals(null, get(activity, "mKillStreakSpeechVoice"));
        });
    }

    @Test
    public void failedJokeStillRestoresNormalVoiceForTheNextInstruction() {
        withRecordingSpeech((activity, speech) -> {
            speech.failNextUtterance = true;
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            assertEquals(speech.normal, speech.current);
            invoke(activity, "announceReloadReminder");
            assertEquals(Arrays.asList("silence:1000", "normal:1.0:"
                    + activity.getString(R.string.reload_voice_prompt)), speech.queued);
        });
    }

    private interface SpeechCheck {
        void run(FullscreenActivity activity, RecordingSpeech speech);
    }

    private void withRecordingSpeech(SpeechCheck check) {
        scenario.onActivity(activity -> {
            Object original = get(activity, "mCountdownSpeech");
            Object ready = get(activity, "mCountdownSpeechReady");
            Object normal = get(activity, "mDefaultSpeechVoice");
            Object streak = get(activity, "mKillStreakSpeechVoice");
            Object clips = get(activity, "mBundledAnnouncementAudioKeys");
            Object closingAudio = get(activity, "mGameStartClosingAudio");
            Object closingDuration = get(activity, "mGameStartClosingDurationMs");
            RecordingSpeech speech = new RecordingSpeech(activity);
            try {
                set(activity, "mCountdownSpeech", speech);
                set(activity, "mCountdownSpeechReady", true);
                set(activity, "mDefaultSpeechVoice", speech.normal);
                set(activity, "mKillStreakSpeechVoice", speech.britishMale);
                set(activity, "mBundledAnnouncementAudioKeys", new HashMap<String, String>());
                set(activity, "mGameStartClosingAudio", null);
                set(activity, "mGameStartClosingDurationMs", GameStartCountdown.ENDING_VOICE_MS);
                check.run(activity, speech);
            } finally {
                invoke(activity, "cancelScheduledGameStartClosingVoice");
                set(activity, "mRoundStartVoicePending", false);
                set(activity, "mCountdownSpeech", original);
                set(activity, "mCountdownSpeechReady", ready);
                set(activity, "mDefaultSpeechVoice", normal);
                set(activity, "mKillStreakSpeechVoice", streak);
                set(activity, "mBundledAnnouncementAudioKeys", clips);
                set(activity, "mGameStartClosingAudio", closingAudio);
                set(activity, "mGameStartClosingDurationMs", closingDuration);
                speech.shutdown();
            }
        });
    }

    private static final class RecordingSpeech extends android.speech.tts.TextToSpeech {
        final android.speech.tts.Voice normal = new android.speech.tts.Voice("normal", java.util.Locale.US,
                400, 200, false, Collections.emptySet());
        final android.speech.tts.Voice britishMale = new android.speech.tts.Voice("en-gb-x-rjs-local",
                java.util.Locale.UK, 400, 200, false, Collections.emptySet());
        android.speech.tts.Voice current = normal;
        final List<String> queued = new ArrayList<>();
        final List<Integer> queueModes = new ArrayList<>();
        final Map<String, Integer> clips = new HashMap<>();
        float rate = 1;
        boolean rejectMaleVoice;
        boolean failNextUtterance;
        boolean rejectClip;
        boolean throwOnClip;
        int rejectRegistration;

        RecordingSpeech(Context context) { super(context, status -> { }); }

        @Override public int setVoice(android.speech.tts.Voice voice) {
            if (rejectMaleVoice && voice == britishMale) return ERROR;
            current = voice;
            return SUCCESS;
        }
        @Override public int setSpeechRate(float value) { rate = value; return SUCCESS; }
        @Override public int addSpeech(CharSequence text, String packageName, int resource) {
            if (resource == rejectRegistration) return ERROR;
            clips.put(text.toString(), resource);
            return SUCCESS;
        }
        @Override public int speak(CharSequence text, int queueMode, android.os.Bundle params, String id) {
            if (clips.containsKey(text.toString())) {
                assertNotNull(params);
                assertEquals(id, params.getString(Engine.KEY_PARAM_UTTERANCE_ID));
                if (throwOnClip) throw new IllegalStateException("Simulated clip failure");
                if (rejectClip) return ERROR;
                queued.add("clip:" + clips.get(text.toString()));
                queueModes.add(queueMode);
                return SUCCESS;
            }
            if (failNextUtterance) {
                failNextUtterance = false;
                throw new IllegalStateException("Simulated broken speech engine");
            }
            queued.add(current.getName() + ":" + rate + ":" + text);
            queueModes.add(queueMode);
            return SUCCESS;
        }
        @Override public int playSilentUtterance(long duration, int queueMode, String id) {
            queued.add("silence:" + duration);
            queueModes.add(queueMode);
            return SUCCESS;
        }
    }

    @Test
    public void bundledRomanJokeKeepsTheCuePauseAndNormalInstructionVoice() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            set(activity, "mKillStreakSpeechVoice", null);
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.enemy_destroyed_voice_prompt);
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            invoke(activity, "announceReloadReminder");
            assertEquals(Arrays.asList("normal:1.5:" + activity.getString(R.string.enemy_destroyed_voice_prompt),
                    "silence:1000", "clip:" + R.raw.kill_streak_2,
                    "normal:1.0:" + activity.getString(R.string.reload_voice_prompt)), speech.queued);
            assertEquals(Arrays.asList(1, 1, 1, 1), speech.queueModes);
            assertEquals(speech.normal, speech.current);
        });
    }

    @Test
    public void bundledGameEndUsesTheCorrectClipAndFlushesQueuedSpeech() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            int[] prompts = {R.string.game_end_won_voice_prompt,
                    R.string.game_end_lost_voice_prompt, R.string.game_end_tied_voice_prompt};
            int[] resources = {R.raw.game_end_won, R.raw.game_end_lost, R.raw.game_end_tied};
            for (int i = 0; i < prompts.length; i++) {
                invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, prompts[i]);
                assertEquals("clip:" + resources[i], speech.queued.get(i));
                assertEquals(android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                        (int) speech.queueModes.get(i));
            }
            assertEquals(speech.normal, speech.current);
        });
    }

    @Test
    public void rejectedGameEndClipFallsBackToNormalSpeech() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            speech.rejectClip = true;
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.game_end_won_voice_prompt);
            assertEquals(Collections.singletonList("normal:1.0:"
                    + activity.getString(R.string.game_end_won_voice_prompt)), speech.queued);
            assertEquals(Collections.singletonList(android.speech.tts.TextToSpeech.QUEUE_FLUSH),
                    speech.queueModes);
            assertEquals(speech.normal, speech.current);
        });
    }

    @Test
    public void bundledRegistrationCoversJokesAndGameEndAndSkipsTranslations() {
        withRecordingSpeech((activity, speech) -> {
            Map<String, String> keys = BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US);
            assertEquals(23, keys.size());
            assertEquals(23, speech.clips.size());
            assertTrue(keys.containsKey(activity.getString(R.string.game_end_won_voice_prompt)));
            assertTrue(keys.containsKey(activity.getString(R.string.game_end_lost_voice_prompt)));
            assertTrue(keys.containsKey(activity.getString(R.string.game_end_tied_voice_prompt)));
            assertFalse(keys.containsKey(activity.getString(R.string.enemy_destroyed_voice_prompt)));
            speech.clips.clear();
            assertTrue(BundledAnnouncementAudio.register(activity, speech, java.util.Locale.GERMANY).isEmpty());
            assertTrue(speech.clips.isEmpty());
        });
    }

    @Test
    public void rejectedBundledClipFallsBackToRealTextInsteadOfItsPrivateKey() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            set(activity, "mKillStreakSpeechVoice", null);
            speech.rejectClip = true;
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            assertEquals(Arrays.asList("silence:1000", "normal:1.0:"
                    + activity.getString(R.string.kill_streak_2_voice_prompt)), speech.queued);
            assertEquals(22, ((Map<?, ?>) get(activity, "mBundledAnnouncementAudioKeys")).size());
        });
    }

    @Test
    public void throwingBundledClipStillSpeaksTheJokeAndNextInstruction() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            set(activity, "mKillStreakSpeechVoice", null);
            speech.throwOnClip = true;
            invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, R.string.kill_streak_2_voice_prompt);
            invoke(activity, "announceReloadReminder");
            assertEquals(Arrays.asList("silence:1000", "normal:1.0:"
                    + activity.getString(R.string.kill_streak_2_voice_prompt), "normal:1.0:"
                    + activity.getString(R.string.reload_voice_prompt)), speech.queued);
        });
    }

    @Test
    public void oneRejectedRegistrationDoesNotLoseOtherRecordings() {
        withRecordingSpeech((activity, speech) -> {
            speech.rejectRegistration = R.raw.kill_streak_2;
            Map<String, String> keys = BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US);
            assertEquals(22, keys.size());
            assertFalse(keys.containsKey(activity.getString(R.string.kill_streak_2_voice_prompt)));
            assertTrue(keys.containsKey(activity.getString(R.string.kill_streak_3_voice_prompt)));
        });
    }

    @Test
    public void normalSpeechWithMatchingWordsDoesNotUseTheKillStreakRecording() {
        withRecordingSpeech((activity, speech) -> {
            set(activity, "mBundledAnnouncementAudioKeys", BundledAnnouncementAudio.register(activity, speech, java.util.Locale.US));
            String text = activity.getString(R.string.kill_streak_2_voice_prompt);
            invoke(activity, "speakGameText", new Class<?>[]{android.speech.tts.TextToSpeech.class,
                    CharSequence.class, float.class, int.class, String.class, boolean.class},
                    speech, text, 1.0f, 1, "ordinary", false);
            assertEquals(Collections.singletonList("normal:1.0:" + text), speech.queued);
        });
    }

    @Test
    public void deathDropsQueuedSpeechBeforeStartingTheTeamQrReminder() {
        scenario.onActivity(activity -> {
            @SuppressWarnings("unchecked")
            ArrayDeque<Integer> pending = (ArrayDeque<Integer>) get(activity, "mPendingVoicePrompts");
            pending.addLast(R.string.enemy_destroyed_voice_prompt);
            pending.addLast(R.string.kill_streak_5_voice_prompt);
            set(activity, "mHealth", 5);

            telemetry(activity, 17, 1, 0, 0);

            assertTrue(pending.isEmpty());
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(true, get(activity, "mRespawnQrVoiceReminderActive"));
        });
    }

    @Test
    public void endingRoundImmediatelyClearsScoreFeedback() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(View.VISIBLE, ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            invoke(activity, "endGame");
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreasePlayerNameTV")).getVisibility());
        });
    }

    @Test
    public void staleScoreCleanupCannotHideNewerFeedback() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            Runnable oldCleanup = (Runnable) get(activity, "mScoreFeedbackCleanup");
            assertNotNull(oldCleanup);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            Runnable currentCleanup = (Runnable) get(activity, "mScoreFeedbackCleanup");
            assertNotNull(currentCleanup);
            assertNotSame(oldCleanup, currentCleanup);
            oldCleanup.run();
            assertEquals(2, get(activity, "mScore"));
            assertEquals("The first kill's cleanup hid the newer score feedback", View.VISIBLE,
                    ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            assertEquals(View.VISIBLE, ((View) get(activity, "mScoreIncreasePlayerNameTV")).getVisibility());
            assertSame(currentCleanup, get(activity, "mScoreFeedbackCleanup"));
            currentCleanup.run();
            ((android.os.Handler) get(activity, "mCombatFeedbackHandler")).removeCallbacks(currentCleanup);
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreasePlayerNameTV")).getVisibility());
            assertNull(get(activity, "mScoreFeedbackCleanup"));
        });
    }

    @Test
    public void ordinaryHitFeedbackStillExpires() throws InterruptedException {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            telemetry(activity, 17, 1, 0, 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitIV")).getVisibility());
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
        Thread.sleep(600);
        scenario.onActivity(activity -> {
            assertNull(get(activity, "mHitAnimation"));
            assertNull(get(activity, "mHitPlayerAnimation"));
            assertEquals(View.GONE, ((View) get(activity, "mHitIV")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
    }

    @Test
    public void zeroDelayReloadRefillsImmediately() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 0;
            invoke(activity, "startReload");
            completeWrite(activity);
            assertEquals("The refill command must be sent even with a zero delay", 2, bluetooth.writes.size());
            assertEquals(4, bluetooth.writes.get(1)[2]);
            assertEquals(30, bluetooth.writes.get(1)[6]);
            assertNull(get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void emptyMagazineStartsReloadVoiceReminderUntilAmmoReturns() {
        scenario.onActivity(activity -> {
            invoke(activity, "setShotsRemaining", new Class<?>[]{byte.class}, (byte) 0);
            assertEquals(true, get(activity, "mReloadVoiceReminderActive"));
            assertEquals(3000L, get(activity, "RELOAD_VOICE_REMINDER_INTERVAL_MS"));

            invoke(activity, "setShotsRemaining", new Class<?>[]{byte.class}, (byte) 30);
            assertEquals(false, get(activity, "mReloadVoiceReminderActive"));
        });
    }

    @Test
    public void eliminationStopsReloadVoiceReminder() {
        scenario.onActivity(activity -> {
            invoke(activity, "setShotsRemaining", new Class<?>[]{byte.class}, (byte) 0);
            assertEquals(true, get(activity, "mReloadVoiceReminderActive"));

            invoke(activity, "startInactivityVoiceReminder");
            assertEquals(true, get(activity, "mInactivityReminderActive"));

            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            assertEquals(false, get(activity, "mReloadVoiceReminderActive"));
            assertEquals(false, get(activity, "mInactivityReminderActive"));
        });
    }

    @Test
    public void inactivityReminderStartsAtFortySecondsAndRepeatsAtTenSeconds() {
        scenario.onActivity(activity -> {
            long before = SystemClock.elapsedRealtime();
            invoke(activity, "startInactivityVoiceReminder");

            assertEquals(true, get(activity, "mInactivityReminderActive"));
            assertEquals(40_000L, get(activity, "INACTIVITY_FIRST_REMINDER_MS"));
            assertEquals(10_000L, get(activity, "INACTIVITY_REPEAT_REMINDER_MS"));
            long deadline = (long) get(activity, "mNextInactivityReminderAt");
            assertTrue(deadline >= before + 40_000L);
        });
    }

    @Test
    public void gameStartVoiceCountsMississippisEveryTwoSecondsWithoutRepeats() {
        withRecordingSpeech((activity, speech) -> {
            invoke(activity, "resetGameStartCountdownSpeech");
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class},
                    GameStartCountdown.DURATION_MS + 1);
            assertTrue("An early host deadline must not invent an eleventh Mississippi", speech.queued.isEmpty());
            List<String> expected = new ArrayList<>();
            List<Integer> expectedQueueModes = new ArrayList<>();
            for (int number = GameStartCountdown.SPOKEN_MAX; number > 0; number--) {
                long remaining = GameStartCountdown.ENDING_VOICE_MS
                        + number * GameStartCountdown.VOICE_STEP_MS;
                invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, remaining);
                invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, remaining - 1);
                // A clock refinement that moves the deadline later cannot repeat a number.
                invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, remaining + 1);
                expected.add("normal:1.0:" + activity.getString(R.string.game_start_mississippi_voice_prompt, number));
                expectedQueueModes.add(android.speech.tts.TextToSpeech.QUEUE_FLUSH);
            }
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, GameStartCountdown.ENDING_VOICE_MS);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, GameStartCountdown.ENDING_VOICE_MS - 1);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, 0L);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, -1L);
            expected.add("normal:1.0:" + activity.getString(R.string.game_start_countdown_finished_voice_prompt));
            expectedQueueModes.add(android.speech.tts.TextToSpeech.QUEUE_ADD);
            assertEquals(expected, speech.queued);
            assertEquals(expectedQueueModes, speech.queueModes);
            assertEquals(0, get(activity, "mLastSpokenGameStartSecond"));
        });
    }

    @Test
    public void synchronizedCountdownPreloadsAmmoButKeepsGameplayLockedThroughClosingLine() {
        withRecordingSpeech((activity, speech) -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + GameStartCountdown.DURATION_MS;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            assertNotNull(timer);
            timer.cancel(); // Drive callbacks manually, without a real-time callback left behind.
            assertEquals(Collections.singletonList("normal:1.0:"
                    + activity.getString(R.string.game_start_mississippi_voice_prompt, 10)), speech.queued);
            speech.queued.clear();
            speech.queueModes.clear();
            completeRoundStartReload(activity, false);
            int preparedWrites = bluetooth.writes.size();
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + GameStartCountdown.ENDING_VOICE_MS);

            timer.onTick(0);

            assertCountdownClosingLineKeepsGameplayLocked(activity, speech, timer, preparedWrites);
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + 1000);
            timer.onTick(Long.MAX_VALUE);
            assertCountdownClosingLineKeepsGameplayLocked(activity, speech, timer, preparedWrites);
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() - 1);

            timer.onFinish();

            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertEquals(false, get(activity, "mStartGameTimer"));
            assertEquals("GO must not reload ammunition already prepared during countdown",
                    preparedWrites, bluetooth.writes.size());
            assertMississippiFinishAndRoundStart(activity, speech);
            timer.onFinish();
            assertEquals("A stale timer repeated the end-of-countdown speech", 2, speech.queued.size());
        });
    }

    @Test
    public void offlineStartWaitsTwentyThreeSecondsIncludingClosingLineBuffer() {
        withRecordingSpeech((activity, speech) -> {
            Globals globals = Globals.getInstance();
            long previousRespawnTime = globals.mRespawnTime;
            try {
                globals.mGameState = Globals.GAME_STATE_NONE;
                globals.mRespawnTime = 90;
                set(activity, "mUseNetwork", false);
                long before = SystemClock.elapsedRealtime();
                invoke(activity, "startGame");
                long after = SystemClock.elapsedRealtime();
                long deadline = (long) get(activity, "mLocalGameStartAt");
                assertTrue(deadline >= before + GameStartCountdown.DURATION_MS);
                assertTrue(deadline <= after + GameStartCountdown.DURATION_MS);
                assertEquals(Globals.GAME_STATE_ELIMINATED, globals.mGameState);
                CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
                assertNotNull(timer);
                timer.cancel(); // Drive the absolute deadline without sleeping for 23 seconds.
                assertEquals(Collections.singletonList("normal:1.0:"
                        + activity.getString(R.string.game_start_mississippi_voice_prompt, 10)), speech.queued);

                // Timer arguments can be stale; only the absolute deadline permits play.
                timer.onTick(0);
                assertEquals(Globals.GAME_STATE_ELIMINATED, globals.mGameState);
                assertSame(timer, get(activity, "mSpawnTimer"));
                assertEquals(1, speech.queued.size());
                speech.queued.clear();
                speech.queueModes.clear();
                completeRoundStartReload(activity, false);
                int preparedWrites = bluetooth.writes.size();
                set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() + GameStartCountdown.ENDING_VOICE_MS);
                timer.onTick(0);
                assertCountdownClosingLineKeepsGameplayLocked(activity, speech, timer, preparedWrites);
                set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() + 1000);
                timer.onTick(Long.MAX_VALUE);
                assertCountdownClosingLineKeepsGameplayLocked(activity, speech, timer, preparedWrites);
                set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
                timer.onTick(Long.MAX_VALUE);
                assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
                assertNull(get(activity, "mSpawnTimer"));
                assertEquals("GO must not reload ammunition already prepared during countdown",
                        preparedWrites, bluetooth.writes.size());
                assertMississippiFinishAndRoundStart(activity, speech);
            } finally {
                globals.mRespawnTime = previousRespawnTime;
            }
        });
    }

    @Test
    public void roundStartCountdownPreloadsBothBossGunsWithoutMagazineDelayOrGoRefill() {
        scenario.onActivity(activity -> {
            long previousReloadTime = Globals.getInstance().mReloadTime;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                Globals.getInstance().mReloadTime = 10_000L;
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                completeRoundStartReload(activity, false);
                completeRoundStartReload(activity, true);
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
                countdown.onTick(0);
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());

                finishRoundStartCountdown(activity, countdown);

                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
                assertEquals("GO reloaded the prepared primary gun", primaryWrites, bluetooth.writes.size());
                assertEquals("GO reloaded the prepared second gun", secondaryWrites, guns.second.writes.size());
            } finally {
                Globals.getInstance().mReloadTime = previousReloadTime;
            }
        });
    }

    @Test
    public void roundStartLateDisableAcksRefillBothGunsOnceWithoutOvertakingPendingWrites() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                byte[] primaryDisable = ((byte[]) get(activity, "mPendingReloadCommand")).clone();
                byte[] secondaryDisable = ((byte[]) get(activity, "mSecondaryPendingReloadCommand")).clone();
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                invoke(activity, "finishReload");
                invoke(activity, "finishSecondaryReload");
                finishRoundStartCountdown(activity, countdown);
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());

                for (boolean secondary : new boolean[]{false, true}) {
                    byte[] disable = secondary ? secondaryDisable : primaryDisable;
                    RecordingBluetoothService service = secondary ? guns.second : bluetooth;
                    int before = service.writes.size();
                    completePowerupWrite(activity, secondary, disable, BluetoothGatt.GATT_SUCCESS);
                    byte[] refill = ((byte[]) get(activity,
                            secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand")).clone();
                    assertEquals(4, refill[2]);
                    assertEquals(before + 1, service.writes.size());
                    assertNull(get(activity, secondary ? "mSecondaryReloadTimer" : "mReloadTimer"));
                    completePowerupWrite(activity, secondary, disable, BluetoothGatt.GATT_SUCCESS);
                    assertEquals("A duplicate disable ACK repeated the refill", before + 1, service.writes.size());
                    completePowerupWrite(activity, secondary, refill, BluetoothGatt.GATT_SUCCESS);
                    completePowerupWrite(activity, secondary, refill, BluetoothGatt.GATT_SUCCESS);
                    assertEquals(0, get(activity, secondary ? "mSecondaryReloading" : "mReloading"));
                    assertEquals(before + 1, service.writes.size());
                }
            }
        });
    }

    @Test
    public void roundStartCancelledCountdownIgnoresBothOldReloadAcksAndTimer() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                byte[] primaryDisable = ((byte[]) get(activity, "mPendingReloadCommand")).clone();
                byte[] secondaryDisable = ((byte[]) get(activity, "mSecondaryPendingReloadCommand")).clone();
                completePowerupWrite(activity, false, primaryDisable, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, secondaryDisable, BluetoothGatt.GATT_SUCCESS);
                byte[] primaryRefill = ((byte[]) get(activity, "mPendingReloadCommand")).clone();
                byte[] secondaryRefill = ((byte[]) get(activity, "mSecondaryPendingReloadCommand")).clone();
                invoke(activity, "endGame");
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();

                completePowerupWrite(activity, false, primaryDisable, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, secondaryDisable, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, false, primaryRefill, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, secondaryRefill, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                countdown.onTick(0);
                countdown.onFinish();

                assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                assertEquals(3, get(activity, "mReloading"));
                assertEquals(3, get(activity, "mSecondaryReloading"));
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());
                assertNull(get(activity, "mSpawnTimer"));
            }
        });
    }

    @Test
    public void roundStartRediscoveredGunsPrepareDuringCountdownInsteadOfWaitingForGo() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                completeRoundStartReload(activity, false);
                completeRoundStartReload(activity, true);
                int preparedSecondaryWrites = guns.second.writes.size();
                receivePowerupGatt(activity, new Intent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED));
                assertEquals("Primary discovery restarted the already prepared second gun",
                        preparedSecondaryWrites, guns.second.writes.size());
                completeRoundStartReload(activity, false);
                receivePowerupGatt(activity, new Intent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
                        .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, 1));
                completeRoundStartReload(activity, true);
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                finishRoundStartCountdown(activity, countdown);
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());
            }
        });
    }

    @Test
    public void roundStartFailedDisableWritesRetryAtMostThreeTimesBeforeGo() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                for (int attempt = 1; attempt <= 3; attempt++) {
                    assertEquals(attempt, get(activity, "mCountdownPrimaryReloadAttempts"));
                    assertEquals(attempt, get(activity, "mCountdownSecondaryReloadAttempts"));
                    completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                            BluetoothGatt.GATT_FAILURE);
                    completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                            BluetoothGatt.GATT_FAILURE);
                    assertEquals(3, get(activity, "mReloading"));
                    assertEquals(3, get(activity, "mSecondaryReloading"));
                    int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                    invoke(activity, "prepareRoundStartReloads");
                    assertEquals("The primary retry ignored its one-second backoff", primaryWrites, bluetooth.writes.size());
                    assertEquals("The secondary retry ignored its one-second backoff", secondaryWrites, guns.second.writes.size());
                    set(activity, "mCountdownPrimaryReloadRetryAt", 0L);
                    set(activity, "mCountdownSecondaryReloadRetryAt", 0L);
                    invoke(activity, "prepareRoundStartReloads");
                    assertEquals(primaryWrites + (attempt < 3 ? 1 : 0), bluetooth.writes.size());
                    assertEquals(secondaryWrites + (attempt < 3 ? 1 : 0), guns.second.writes.size());
                }
                assertNull(get(activity, "mPendingReloadCommand"));
                assertNull(get(activity, "mSecondaryPendingReloadCommand"));
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                finishRoundStartCountdown(activity, countdown);
                assertEquals("A gun that never prepared still needs the GO fallback", primaryWrites + 1,
                        bluetooth.writes.size());
                assertEquals(secondaryWrites + 1, guns.second.writes.size());
                assertEquals(4, ((byte[]) get(activity, "mPendingReloadCommand"))[2]);
                assertEquals(4, ((byte[]) get(activity, "mSecondaryPendingReloadCommand"))[2]);
            }
        });
    }

    @Test
    public void roundStartFailedSecondaryRefillRetryDoesNotDisturbPreparedPrimaryGun() {
        assertRoundStartFailedRefillRetryDoesNotDisturbOtherGun(true);
    }

    @Test
    public void roundStartFailedPrimaryRefillRetryDoesNotDisturbPreparedSecondGun() {
        assertRoundStartFailedRefillRetryDoesNotDisturbOtherGun(false);
    }

    private void assertRoundStartFailedRefillRetryDoesNotDisturbOtherGun(boolean secondary) {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                completeRoundStartReload(activity, !secondary);
                String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
                completePowerupWrite(activity, secondary, (byte[]) get(activity, pendingField),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, secondary, (byte[]) get(activity, pendingField),
                        BluetoothGatt.GATT_FAILURE);
                RecordingBluetoothService preparedGun = secondary ? bluetooth : guns.second;
                int preparedWrites = preparedGun.writes.size();
                set(activity, secondary ? "mCountdownSecondaryReloadRetryAt" : "mCountdownPrimaryReloadRetryAt", 0L);
                invoke(activity, "prepareRoundStartReloads");
                assertEquals(preparedWrites, preparedGun.writes.size());
                assertEquals(0, get(activity, secondary ? "mReloading" : "mSecondaryReloading"));
                completeRoundStartReload(activity, secondary);
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                finishRoundStartCountdown(activity, countdown);
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());
            }
        });
    }

    @Test
    public void roundStartEmpBlockedDisableAcksRefillBothGunsOnceAfterProtectionClears() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                CountDownTimer countdown = beginRoundStartCountdown(activity);
                long[] powerupUntil = (long[]) get(activity, "mPowerupUntil");
                powerupUntil[Powerups.EMP] = SystemClock.elapsedRealtime() + Powerups.durationMillis(Powerups.EMP);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                assertEquals(4, get(activity, "mReloading"));
                assertEquals(4, get(activity, "mSecondaryReloading"));
                assertNull(get(activity, "mPendingReloadCommand"));
                assertNull(get(activity, "mSecondaryPendingReloadCommand"));
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                invoke(activity, "finishUnblockedRoundStartReloads");
                invoke(activity, "prepareRoundStartReloads");
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());

                powerupUntil[Powerups.EMP] = 0;
                invoke(activity, "finishUnblockedRoundStartReloads");
                invoke(activity, "finishUnblockedRoundStartReloads");

                assertEquals(primaryWrites + 1, bluetooth.writes.size());
                assertEquals(secondaryWrites + 1, guns.second.writes.size());
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                finishRoundStartCountdown(activity, countdown);
                assertEquals(primaryWrites + 1, bluetooth.writes.size());
                assertEquals(secondaryWrites + 1, guns.second.writes.size());
            }
        });
    }

    @Test
    public void roundStartPreparedGunTelemetryCannotScoreReportShotsOrReloadBeforeGo() {
        scenario.onActivity(activity -> {
            boolean previousReloadOnEmpty = Globals.getInstance().mReloadOnEmpty;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                beginRoundStartCountdown(activity);
                completeRoundStartReload(activity, false);
                completeRoundStartReload(activity, true);
                Globals.getInstance().mReloadOnEmpty = true;
                set(activity, "mUseNetwork", true);
                tcp.dedicated = true;
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                int udpMessages = udp.messages.size(), health = (int) get(activity, "mHealth");
                int hits = (int) get(activity, "mHitsTaken");
                int primaryEmptyPulls = (int) get(activity, "mEmptyTriggerCount");
                int secondaryEmptyPulls = (int) get(activity, "mSecondaryEmptyTriggerCount");
                for (boolean secondary : new boolean[]{false, true}) {
                    byte[] packet = telemetryPacket(17, 1, 0, 0);
                    packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 0;
                    packet[FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x11;
                    receivePowerupGatt(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                            .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                            .putExtra(BluetoothLeService.EXTRA_DATA, packet));
                }
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
                assertEquals(0, get(activity, "mScore"));
                assertEquals(0, get(activity, "mTeamScore"));
                assertEquals(health, get(activity, "mHealth"));
                assertEquals(hits, get(activity, "mHitsTaken"));
                assertEquals(0, tcp.hostedShots);
                assertEquals(udpMessages, udp.messages.size());
                assertEquals(primaryEmptyPulls, get(activity, "mEmptyTriggerCount"));
                assertEquals(secondaryEmptyPulls, get(activity, "mSecondaryEmptyTriggerCount"));
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());
                assertEquals(0, get(activity, "mReloading"));
                assertEquals(0, get(activity, "mSecondaryReloading"));
            } finally {
                Globals.getInstance().mReloadOnEmpty = previousReloadOnEmpty;
            }
        });
    }

    private CountDownTimer beginRoundStartCountdown(FullscreenActivity activity) {
        set(activity, "mUseNetwork", false);
        set(activity, "mNextGameStartAllowedAt", 0L);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        invoke(activity, "startGame");
        CountDownTimer countdown = (CountDownTimer) get(activity, "mSpawnTimer");
        assertNotNull(countdown);
        countdown.cancel();
        assertEquals(true, get(activity, "mStartGameTimer"));
        assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        return countdown;
    }

    private static void completeRoundStartReload(FullscreenActivity activity, boolean secondary) {
        String stateField = secondary ? "mSecondaryReloading" : "mReloading";
        String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
        assertEquals("The gun did not begin countdown preparation", 4, get(activity, stateField));
        byte[] disable = ((byte[]) get(activity, pendingField)).clone();
        assertEquals(2, disable[2]);
        completePowerupWrite(activity, secondary, disable, BluetoothGatt.GATT_SUCCESS);
        assertNull("Countdown preparation added the ordinary magazine reload delay",
                get(activity, secondary ? "mSecondaryReloadTimer" : "mReloadTimer"));
        assertEquals(2, get(activity, stateField));
        byte[] refill = ((byte[]) get(activity, pendingField)).clone();
        assertEquals(4, refill[2]);
        assertEquals((int) invokeResult(activity, "activeMagazineSize"), refill[6] & 255);
        completePowerupWrite(activity, secondary, refill, BluetoothGatt.GATT_SUCCESS);
        assertEquals(0, get(activity, stateField));
        assertNull(get(activity, pendingField));
        assertEquals(refill[6], get(activity, secondary ? "mSecondaryLastShotCount" : "mLastShotCount"));
    }

    private static void finishRoundStartCountdown(FullscreenActivity activity, CountDownTimer countdown) {
        set(activity, (boolean) get(activity, "mHasSynchronizedStart")
                ? "mSynchronizedStartAt" : "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
        countdown.onTick(0);
        assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        assertEquals(false, get(activity, "mStartGameTimer"));
        assertNull(get(activity, "mSpawnTimer"));
    }

    @Test
    public void delayedSpeechEngineKeepsZeroAndFinishLineBeforeTheRoundStartPrompt() {
        withRecordingSpeech((activity, speech) -> {
            invoke(activity, "resetGameStartCountdownSpeech");
            set(activity, "mCountdownSpeechReady", false);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, GameStartCountdown.DURATION_MS);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, GameStartCountdown.ENDING_VOICE_MS);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, 0L);
            invoke(activity, "announceRoundStart");
            @SuppressWarnings("unchecked")
            ArrayDeque<Integer> pending = (ArrayDeque<Integer>) get(activity, "mPendingVoicePrompts");
            assertEquals(Arrays.asList(R.string.game_start_countdown_finished_voice_prompt,
                    R.string.game_start_voice_prompt), new ArrayList<>(pending));
            assertTrue(speech.queued.isEmpty());

            // Replay exactly as the real engine's readiness callback does.
            set(activity, "mCountdownSpeechReady", true);
            while (!pending.isEmpty())
                invoke(activity, "speakRoundVoicePrompt", new Class<?>[]{int.class}, pending.removeFirst());
            assertMississippiFinishAndRoundStart(activity, speech);
        });
    }

    private void assertCountdownClosingLineKeepsGameplayLocked(FullscreenActivity activity, RecordingSpeech speech,
                                                              CountDownTimer timer, int preparedWrites) {
        assertEquals(Collections.singletonList("normal:1.0:"
                + activity.getString(R.string.game_start_countdown_finished_voice_prompt)), speech.queued);
        assertEquals(Collections.singletonList(android.speech.tts.TextToSpeech.QUEUE_ADD), speech.queueModes);
        assertEquals("The closing line must not start gameplay early",
                Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        assertEquals(true, get(activity, "mStartGameTimer"));
        assertSame(timer, get(activity, "mSpawnTimer"));
        assertEquals("The starting magazine must already be ready", 0, get(activity, "mReloading"));
        assertEquals((int) invokeResult(activity, "activeMagazineSize"), ((byte) get(activity, "mLastShotCount")) & 255);
        invoke(activity, "finishReload");
        assertEquals("The closing line repeated an already completed refill", preparedWrites, bluetooth.writes.size());
    }

    private static void assertMississippiFinishAndRoundStart(FullscreenActivity activity, RecordingSpeech speech) {
        assertEquals(Arrays.asList("normal:1.0:"
                        + activity.getString(R.string.game_start_countdown_finished_voice_prompt),
                "normal:1.0:" + activity.getString(R.string.game_start_voice_prompt)), speech.queued);
        assertEquals("The round-start phrase must not flush zero or the finish line",
                Arrays.asList(android.speech.tts.TextToSpeech.QUEUE_ADD,
                        android.speech.tts.TextToSpeech.QUEUE_ADD), speech.queueModes);
    }

    @Test
    public void measuredClosingLineWaitsUntilItCanEndAtTheSharedStart() {
        withRecordingSpeech((activity, speech) -> {
            RecordingClosingAudio audio = new RecordingClosingAudio(activity, speech, 2_000);
            set(activity, "mGameStartClosingAudio", audio);
            set(activity, "mGameStartClosingDurationMs", audio.durationMillis());
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + GameStartCountdown.DURATION_MS;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            timer.cancel();
            speech.queued.clear();
            speech.queueModes.clear();
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + GameStartCountdown.ENDING_VOICE_MS);
            timer.onTick(0);
            Runnable scheduled = (Runnable) get(activity, "mGameStartClosingVoice");
            assertNotNull(scheduled);
            assertEquals(0, audio.plays);
            assertTrue(speech.queued.isEmpty());
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);

            // An early callback rechecks the absolute deadline instead of playing early.
            scheduled.run();
            assertEquals(0, audio.plays);
            assertNotNull(get(activity, "mGameStartClosingVoice"));
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + 1_999);
            timer.onTick(0);
            assertEquals(1, audio.plays);
            assertNull(get(activity, "mGameStartClosingVoice"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);

            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() - 1);
            timer.onFinish();
            assertEquals("No speech completion wait may hold up the shared game start",
                    Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(true, get(activity, "mRoundStartVoicePending"));
            assertTrue(speech.queued.isEmpty());
            audio.finish();
            assertEquals(Collections.singletonList("normal:1.0:"
                    + activity.getString(R.string.game_start_voice_prompt)), speech.queued);
            assertEquals(Collections.singletonList(android.speech.tts.TextToSpeech.QUEUE_ADD), speech.queueModes);
            assertEquals(false, get(activity, "mRoundStartVoicePending"));
            audio.finish();
            assertEquals(1, speech.queued.size());
        });
    }

    @Test
    public void closingCompletionCannotStartTheGameBeforeTheAuthoritativeDeadline() {
        withRecordingSpeech((activity, speech) -> {
            RecordingClosingAudio audio = new RecordingClosingAudio(activity, speech, 2_000);
            set(activity, "mGameStartClosingAudio", audio);
            set(activity, "mGameStartClosingDurationMs", audio.durationMillis());
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + GameStartCountdown.DURATION_MS;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            timer.cancel();
            int lockedWrites = bluetooth.writes.size();
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + 1_999);
            timer.onTick(0);
            audio.finish();
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(lockedWrites, bluetooth.writes.size());
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() - 1);
            timer.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals("normal:1.0:" + activity.getString(R.string.game_start_voice_prompt),
                    speech.queued.get(speech.queued.size() - 1));
            assertFalse(speech.queued.contains("silence:1000"));
        });
    }

    @Test
    public void canceledClosingCallbackCannotSpeakInANewerRound() {
        withRecordingSpeech((activity, speech) -> {
            RecordingClosingAudio audio = new RecordingClosingAudio(activity, speech, 2_000);
            set(activity, "mGameStartClosingAudio", audio);
            set(activity, "mGameStartClosingDurationMs", audio.durationMillis());
            set(activity, "mStartGameTimer", true);
            set(activity, "mHasSynchronizedStart", false);
            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() + GameStartCountdown.ENDING_VOICE_MS);
            invoke(activity, "resetGameStartCountdownSpeech");
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, GameStartCountdown.ENDING_VOICE_MS);
            Runnable old = (Runnable) get(activity, "mGameStartClosingVoice");
            assertNotNull(old);
            invoke(activity, "resetGameStartCountdownSpeech");
            old.run();
            assertNull(get(activity, "mGameStartClosingVoice"));
            assertEquals(0, audio.plays);
            assertTrue(speech.queued.isEmpty());

            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() + 1_000);
            invoke(activity, "announceGameStartCountdown", new Class<?>[]{long.class}, 1_000L);
            Runnable completion = audio.completion;
            set(activity, "mGameControlGeneration", (long) get(activity, "mGameControlGeneration") + 1);
            set(activity, "mRoundStartVoicePending", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            completion.run();
            assertTrue(speech.queued.isEmpty());
        });
    }

    @Test
    public void clockRefinementRealignsPlayingClosingAudioOrRearmsALaterClip() {
        withRecordingSpeech((activity, speech) -> {
            RecordingClosingAudio audio = new RecordingClosingAudio(activity, speech, 2_000);
            set(activity, "mGameStartClosingAudio", audio);
            set(activity, "mGameStartClosingDurationMs", audio.durationMillis());
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + GameStartCountdown.DURATION_MS;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            timer.cancel();
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + 1_999);
            timer.onTick(0);
            assertTrue(audio.playing);
            long refined = SystemClock.elapsedRealtime() + 1_500;
            receiveNetwork(activity, synchronizedStart(refined, refined + 60000)
                    .putExtra(NetMsg.INTENT_START_TIME_ADJUSTMENT, true));
            assertTrue(audio.alignedRemaining > 0 && audio.alignedRemaining <= 1_500);
            assertEquals(1, audio.plays);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);

            long later = SystemClock.elapsedRealtime() + 3_000;
            receiveNetwork(activity, synchronizedStart(later, later + 60000)
                    .putExtra(NetMsg.INTENT_START_TIME_ADJUSTMENT, true));
            assertFalse(audio.playing);
            Runnable scheduled = (Runnable) get(activity, "mGameStartClosingVoice");
            assertNotNull(scheduled);
            assertEquals(1, audio.plays);
            set(activity, "mSynchronizedStartAt", SystemClock.elapsedRealtime() + 1_999);
            timer.onTick(0);
            assertTrue(audio.playing);
            assertEquals(2, audio.plays);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        });
    }

    private static class RecordingClosingAudio extends CountdownClosingAudio {
        final long duration;
        int plays;
        boolean playing;
        Runnable completion;
        long alignedRemaining;
        RecordingClosingAudio(Context context, RecordingSpeech speech, long duration) {
            super(context, speech, "Closing countdown test", () -> { });
            this.duration = duration;
        }
        @Override boolean isReady() { return true; }
        @Override long durationMillis() { return duration; }
        @Override boolean play(Runnable finished) {
            plays++;
            playing = true;
            completion = finished;
            return true;
        }
        @Override boolean isPlaying() { return playing; }
        @Override void alignToRemaining(long remaining) { alignedRemaining = remaining; }
        @Override void stop() { playing = false; completion = null; }
        void finish() {
            playing = false;
            if (completion != null) {
                Runnable finished = completion;
                completion = null;
                finished.run();
            }
        }
    }

    @Test
    public void triggerPullRestartsTheInactivityDelay() {
        scenario.onActivity(activity -> {
            invoke(activity, "startInactivityVoiceReminder");
            set(activity, "mNextInactivityReminderAt", 1L);
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 1;
            long before = SystemClock.elapsedRealtime();

            receiveTelemetry(activity, packet);

            assertTrue((long) get(activity, "mNextInactivityReminderAt")
                    >= before + 40_000L);
        });
    }

    @Test
    public void gpsJitterDoesNotCountAsMovementButThreeMetersDoes() {
        scenario.onActivity(activity -> {
            set(activity, "mLastGpsLongitude", -122.0);
            set(activity, "mLastGpsLatitude", 47.0);
            invoke(activity, "startInactivityVoiceReminder");
            set(activity, "mNextInactivityReminderAt", 123L);

            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class},
                    new Intent().putExtra(NetMsg.INTENT_LONGITUDE, -122.0)
                            .putExtra(NetMsg.INTENT_LATITUDE, 47.000005));
            assertEquals(123L, get(activity, "mNextInactivityReminderAt"));

            long before = SystemClock.elapsedRealtime();
            invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class},
                    new Intent().putExtra(NetMsg.INTENT_LONGITUDE, -122.0)
                            .putExtra(NetMsg.INTENT_LATITUDE, 47.00005));
            assertTrue((long) get(activity, "mNextInactivityReminderAt")
                    >= before + 40_000L);
        });
    }

    @Test
    public void reliableGpsJitterDoesNotCountAsMovementButThreeMetersDoes() {
        scenario.onActivity(activity -> {
            long sample = SystemClock.elapsedRealtime();
            startReliableGpsInactivity(activity, 1f, sample - 1000L);
            try {
                set(activity, "mNextInactivityReminderAt", 123L);
                receiveInactivityGps(activity, inactivityGpsFix(47.000005, 1f, sample - 500L));
                assertEquals(123L, get(activity, "mNextInactivityReminderAt"));

                long before = SystemClock.elapsedRealtime();
                receiveInactivityGps(activity, inactivityGpsFix(47.00005, 1f, sample));
                assertInactivityDelayReset(activity, before);
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void reliableStationaryGpsStillVoicesAfterEitherGunTrigger() {
        withRecordingSpeech((activity, speech) -> {
            boolean resumed = (boolean) get(activity, "mActivityResumed");
            set(activity, "mActivityResumed", true);
            try (PowerupDualGunFixture fixture = new PowerupDualGunFixture(activity)) {
                startReliableGpsInactivity(activity, 1f, SystemClock.elapsedRealtime() - 1000L);
                set(activity, "mNextInactivityReminderAt", 1L);
                for (boolean secondary : new boolean[]{false, true}) {
                    inactivityTrigger(activity, secondary);
                    assertEquals("A stationary GPS player postponed the reminder by firing", 1L,
                            get(activity, "mNextInactivityReminderAt"));
                }
                long before = SystemClock.elapsedRealtime();
                ((Runnable) get(activity, "mInactivityVoiceReminder")).run();
                assertEquals(1, speech.queued.size());
                assertInactivityVoice(activity, speech.queued.get(0));
                assertEquals(Collections.singletonList(android.speech.tts.TextToSpeech.QUEUE_ADD), speech.queueModes);
                assertTrue((long) get(activity, "mNextInactivityReminderAt") >= before + 10_000L);
            } finally {
                invoke(activity, "stopInactivityVoiceReminder");
                set(activity, "mActivityResumed", resumed);
            }
        });
    }

    @Test
    public void gpsMovementMustExceedCombinedFixUncertainty() {
        scenario.onActivity(activity -> {
            long sample = SystemClock.elapsedRealtime();
            startReliableGpsInactivity(activity, 3f, sample - 1000L);
            try {
                set(activity, "mNextInactivityReminderAt", 123L);
                // About 4.5m exceeds the old 3m threshold but remains inside 3m + 3m uncertainty.
                receiveInactivityGps(activity, inactivityGpsFix(47.00004, 3f, sample - 500L));
                assertEquals(123L, get(activity, "mNextInactivityReminderAt"));
                long before = SystemClock.elapsedRealtime();
                receiveInactivityGps(activity, inactivityGpsFix(47.00008, 3f, sample));
                assertInactivityDelayReset(activity, before);
                assertEquals(47.00008, (double) get(activity, "mInactivityGpsLatitude"), 0);
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void duplicateAndOlderGpsMeasurementsCannotResetInactivity() {
        scenario.onActivity(activity -> {
            long sample = SystemClock.elapsedRealtime();
            startReliableGpsInactivity(activity, 1f, sample - 1000L);
            try {
                set(activity, "mNextInactivityReminderAt", 123L);
                receiveInactivityGps(activity, inactivityGpsFix(47.0003, 1f, sample - 1000L));
                assertEquals(123L, get(activity, "mNextInactivityReminderAt"));
                receiveInactivityGps(activity, inactivityGpsFix(47.0004, 1f, sample - 1001L));
                assertEquals(123L, get(activity, "mNextInactivityReminderAt"));
                assertEquals(47.0, (double) get(activity, "mInactivityGpsLatitude"), 0);
                assertEquals(sample - 1000L, get(activity, "mInactivityLastGpsSampleElapsedMs"));
                long before = SystemClock.elapsedRealtime();
                receiveInactivityGps(activity, inactivityGpsFix(47.0003, 1f, sample - 500L));
                assertInactivityDelayReset(activity, before);
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void olderPoorAndNetworkGpsCannotDowngradeANewerReliableFix() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture fixture = new PowerupDualGunFixture(activity)) {
                long sample = SystemClock.elapsedRealtime();
                startReliableGpsInactivity(activity, 1f, sample - 500L);
                set(activity, "mNextInactivityReminderAt", 123L);
                List<Intent> reordered = Arrays.asList(
                        inactivityGpsFix(47.0003, 20f, sample - 10_001L),
                        inactivityGpsFix(47.0004, 1f, sample - 10_001L)
                                .putExtra(NetMsg.INTENT_GPS_PROVIDER,
                                        android.location.LocationManager.NETWORK_PROVIDER));
                for (Intent fix : reordered) {
                    receiveInactivityGps(activity, fix);
                    assertEquals(123L, get(activity, "mNextInactivityReminderAt"));
                    assertEquals(true, get(activity, "mInactivityUsesGps"));
                    assertEquals(47.0, (double) get(activity, "mInactivityGpsLatitude"), 0);
                    assertEquals(47.0, (double) get(activity, "mLastGpsLatitude"), 0);
                    assertEquals(-122.0, (double) get(activity, "mLastGpsLongitude"), 0);
                    assertEquals(sample - 500L, get(activity, "mLastGpsFixElapsedMs"));
                    assertEquals(sample - 500L, get(activity, "mInactivityLastGpsSampleElapsedMs"));
                    assertEquals(true, get(activity, "mLastGpsHasAccuracy"));
                    assertEquals(1f, (float) get(activity, "mLastGpsAccuracyMeters"), 0);
                    assertEquals(android.location.LocationManager.GPS_PROVIDER,
                            get(activity, "mLastGpsProvider"));
                    for (boolean secondary : new boolean[]{false, true}) {
                        set(activity, secondary ? "mSecondaryLastTriggerCount" : "mLastTriggerCount", (byte) 0);
                        inactivityTrigger(activity, secondary);
                        assertEquals("An older fix allowed trigger-only fallback", 123L,
                                get(activity, "mNextInactivityReminderAt"));
                    }
                }
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void unusableGpsPreservesTriggerActivityForBothGuns() {
        scenario.onActivity(activity -> {
            try (PowerupDualGunFixture fixture = new PowerupDualGunFixture(activity)) {
                long now = SystemClock.elapsedRealtime();
                List<Intent> fixes = Arrays.asList(
                        inactivityGpsFix(47.0, 1f, now).putExtra(NetMsg.INTENT_GPS_HAS_ACCURACY, false),
                        inactivityGpsFix(47.0, 6f, now),
                        inactivityGpsFix(47.0, 0f, now),
                        inactivityGpsFix(47.0, Float.NaN, now),
                        inactivityGpsFix(47.0, Float.POSITIVE_INFINITY, now),
                        inactivityGpsFix(47.0, 1f, now).putExtra(NetMsg.INTENT_GPS_PROVIDER,
                                android.location.LocationManager.NETWORK_PROVIDER),
                        inactivityGpsFix(47.0, 1f, now - InactivityGpsPolicy.MAX_FIX_AGE_MS - 1L),
                        new Intent().putExtra(NetMsg.INTENT_LONGITUDE, -122.0)
                                .putExtra(NetMsg.INTENT_LATITUDE, 47.0),
                        inactivityGpsFix(47.0, 1f, now));
                fixes.get(8).removeExtra(NetMsg.INTENT_GPS_FIX_ELAPSED_MS);
                for (int quality = 0; quality < fixes.size(); quality++) {
                    invoke(activity, "stopInactivityVoiceReminder");
                    // Each quality case represents its own fresh local state,
                    // not a reordered measurement behind the preceding case.
                    set(activity, "mLastGpsHasMeasurementTime", false);
                    Globals.getInstance().mUseGPS = true;
                    receiveInactivityGps(activity, fixes.get(quality));
                    invoke(activity, "startInactivityVoiceReminder");
                    assertEquals("Unusable GPS case " + quality + " enabled GPS-only inactivity", false,
                            get(activity, "mInactivityUsesGps"));
                    for (boolean secondary : new boolean[]{false, true}) {
                        set(activity, secondary ? "mSecondaryLastTriggerCount" : "mLastTriggerCount", (byte) 0);
                        set(activity, "mNextInactivityReminderAt", 1L);
                        long before = SystemClock.elapsedRealtime();
                        inactivityTrigger(activity, secondary);
                        assertInactivityDelayReset(activity, before);
                    }
                }
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void inactivityGpsModeTransitionsGrantOnlyOneFortySecondGrace() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mUseGPS = true;
            receiveInactivityGps(activity, inactivityGpsFix(47.0, 20f, SystemClock.elapsedRealtime()));
            invoke(activity, "startInactivityVoiceReminder");
            try {
                assertEquals(false, get(activity, "mInactivityUsesGps"));
                set(activity, "mNextInactivityReminderAt", 1L);
                long before = SystemClock.elapsedRealtime();
                receiveInactivityGps(activity, inactivityGpsFix(47.0, 1f, SystemClock.elapsedRealtime()));
                assertEquals(true, get(activity, "mInactivityUsesGps"));
                assertInactivityDelayReset(activity, before);
                set(activity, "mNextInactivityReminderAt", 123L);
                invoke(activity, "refreshInactivityGpsMode", new Class<?>[]{long.class}, SystemClock.elapsedRealtime());
                invoke(activity, "refreshInactivityGpsMode", new Class<?>[]{long.class}, SystemClock.elapsedRealtime());
                assertEquals("An unchanged reliable mode repeatedly renewed its grace", 123L,
                        get(activity, "mNextInactivityReminderAt"));

                before = SystemClock.elapsedRealtime();
                receiveInactivityGps(activity, inactivityGpsFix(47.0, 20f, SystemClock.elapsedRealtime()));
                assertEquals(false, get(activity, "mInactivityUsesGps"));
                assertInactivityDelayReset(activity, before);
                set(activity, "mNextInactivityReminderAt", 321L);
                invoke(activity, "refreshInactivityGpsMode", new Class<?>[]{long.class}, SystemClock.elapsedRealtime());
                invoke(activity, "refreshInactivityGpsMode", new Class<?>[]{long.class}, SystemClock.elapsedRealtime());
                assertEquals("An unchanged fallback mode repeatedly renewed its grace", 321L,
                        get(activity, "mNextInactivityReminderAt"));
            } finally { invoke(activity, "stopInactivityVoiceReminder"); }
        });
    }

    @Test
    public void expiredGoodGpsGetsOneGraceThenUsesLegacyInactivityVoice() {
        withRecordingSpeech((activity, speech) -> {
            boolean resumed = (boolean) get(activity, "mActivityResumed");
            set(activity, "mActivityResumed", true);
            startReliableGpsInactivity(activity, 1f, SystemClock.elapsedRealtime() - 1000L);
            try {
                set(activity, "mLastGpsFixElapsedMs",
                        SystemClock.elapsedRealtime() - InactivityGpsPolicy.MAX_FIX_AGE_MS - 1L);
                set(activity, "mNextInactivityReminderAt", 1L);
                long before = SystemClock.elapsedRealtime();
                Runnable reminder = (Runnable) get(activity, "mInactivityVoiceReminder");
                reminder.run();
                assertEquals(false, get(activity, "mInactivityUsesGps"));
                assertInactivityDelayReset(activity, before);
                assertTrue(speech.queued.isEmpty());
                set(activity, "mNextInactivityReminderAt", 1L);
                reminder.run();
                assertEquals("Stale GPS renewed its transition grace instead of allowing a reminder", 1,
                        speech.queued.size());
                assertInactivityVoice(activity, speech.queued.get(0));
            } finally {
                invoke(activity, "stopInactivityVoiceReminder");
                set(activity, "mActivityResumed", resumed);
            }
        });
    }

    @Test
    public void deathClearsGpsInactivityStateAndRejectsOldReminder() {
        withRecordingSpeech((activity, speech) -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            assertTrue(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            Runnable reminder = (Runnable) get(activity, "mInactivityVoiceReminder");
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            assertGpsInactivityStopped(activity);
            speech.queued.clear();
            reminder.run();
            assertTrue("A dead player heard a stale inactivity callback", speech.queued.isEmpty());
        });
    }

    @Test
    public void pauseClearsGpsInactivityStateAndRejectsOldReminder() {
        withRecordingSpeech((activity, speech) -> {
            boolean registered = (boolean) get(activity, "mGattReceiverRegistered");
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
                assertTrue(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
                Runnable reminder = (Runnable) get(activity, "mInactivityVoiceReminder");
                activity.onPause();
                try {
                    assertGpsInactivityStopped(activity);
                    speech.queued.clear();
                    reminder.run();
                    assertTrue("A paused player heard a stale inactivity callback", speech.queued.isEmpty());
                } finally {
                    if (registered) activity.onResume();
                    invoke(activity, "stopInactivityVoiceReminder");
                }
            }
        });
    }

    private static Intent inactivityGpsFix(double latitude, float accuracy, long measuredAt) {
        return new Intent(NetMsg.NETMSG_GPSLOCUPDATE)
                .putExtra(NetMsg.INTENT_LONGITUDE, -122.0).putExtra(NetMsg.INTENT_LATITUDE, latitude)
                .putExtra(NetMsg.INTENT_GPS_FIX_ELAPSED_MS, measuredAt)
                .putExtra(NetMsg.INTENT_GPS_HAS_ACCURACY, true)
                .putExtra(NetMsg.INTENT_GPS_ACCURACY_METERS, accuracy)
                .putExtra(NetMsg.INTENT_GPS_PROVIDER, android.location.LocationManager.GPS_PROVIDER);
    }

    private static void receiveInactivityGps(FullscreenActivity activity, Intent fix) {
        invoke(activity, "receiveLocalGpsLocation", new Class<?>[]{Intent.class}, fix);
    }

    private static void startReliableGpsInactivity(FullscreenActivity activity, float accuracy, long measuredAt) {
        Globals.getInstance().mUseGPS = true;
        receiveInactivityGps(activity, inactivityGpsFix(47.0, accuracy, measuredAt));
        invoke(activity, "startInactivityVoiceReminder");
        assertEquals(true, get(activity, "mInactivityUsesGps"));
    }

    private static void inactivityTrigger(FullscreenActivity activity, boolean secondary) {
        byte[] packet = telemetryPacket(0, 0, 0, 0);
        packet[FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 1;
        receivePowerupGatt(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                .putExtra(BluetoothLeService.EXTRA_DATA, packet));
    }

    private static void assertInactivityDelayReset(FullscreenActivity activity, long before) {
        long deadline = (long) get(activity, "mNextInactivityReminderAt");
        assertTrue("Activity did not restore the full forty-second delay", deadline >= before + 40_000L);
        assertTrue("Activity scheduled more than forty seconds from now",
                deadline <= SystemClock.elapsedRealtime() + 40_000L);
    }

    private static void assertInactivityVoice(FullscreenActivity activity, String queued) {
        boolean found = false;
        for (int prompt : new int[]{R.string.inactivity_battle_voice_prompt, R.string.inactivity_recruit_voice_prompt,
                R.string.inactivity_legs_voice_prompt, R.string.inactivity_breaking_news_voice_prompt,
                R.string.inactivity_battlefield_voice_prompt, R.string.inactivity_movement_tutorial_voice_prompt,
                R.string.inactivity_laser_camping_voice_prompt, R.string.inactivity_standing_still_voice_prompt})
            found |= queued.equals("normal:1.0:" + activity.getString(prompt));
        assertTrue("The due reminder did not speak an inactivity prompt: " + queued, found);
    }

    private static void assertGpsInactivityStopped(FullscreenActivity activity) {
        assertEquals(false, get(activity, "mInactivityReminderActive"));
        assertEquals(false, get(activity, "mInactivityUsesGps"));
        assertTrue(Double.isNaN((double) get(activity, "mInactivityGpsLatitude")));
        assertTrue(Double.isNaN((double) get(activity, "mInactivityGpsLongitude")));
        assertEquals(-1L, get(activity, "mInactivityLastGpsSampleElapsedMs"));
        assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
    }

    @Test
    public void shotsCounterIsMuchLargerDuringPlay() {
        scenario.onActivity(activity -> {
            TextView shots = (TextView) get(activity, "mShotsRemainingTV");
            float lobbySize = shots.getTextSize();

            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, true);
            assertTrue(shots.getTextSize() > lobbySize * 2);

            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, false);
            assertEquals(lobbySize, shots.getTextSize(), 0.01f);
        });
    }

    @Test
    public void singleGunPlayCentersAmmoAndMovesHealthBelowIt() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            set(activity, "mSecondaryDeviceAddress", "");

            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, true);
            TextView shots = activity.findViewById(R.id.shots_remaining_tv);
            TextView health = activity.findViewById(R.id.health_label_tv);
            TextView recoil = activity.findViewById(R.id.recoil_tv);
            RelativeLayout.LayoutParams shotsLayout =
                    (RelativeLayout.LayoutParams) shots.getLayoutParams();
            RelativeLayout.LayoutParams healthLayout =
                    (RelativeLayout.LayoutParams) health.getLayoutParams();
            RelativeLayout.LayoutParams recoilLayout =
                    (RelativeLayout.LayoutParams) recoil.getLayoutParams();
            assertEquals(RelativeLayout.TRUE,
                    shotsLayout.getRules()[RelativeLayout.CENTER_HORIZONTAL]);
            assertEquals(0, recoilLayout.getRules()[RelativeLayout.CENTER_HORIZONTAL]);
            assertEquals(RelativeLayout.TRUE,
                    recoilLayout.getRules()[RelativeLayout.ALIGN_PARENT_START]);
            assertEquals(R.id.shots_remaining_tv,
                    healthLayout.getRules()[RelativeLayout.BELOW]);

            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, false);
            shotsLayout = (RelativeLayout.LayoutParams) shots.getLayoutParams();
            healthLayout = (RelativeLayout.LayoutParams) health.getLayoutParams();
            recoilLayout = (RelativeLayout.LayoutParams) recoil.getLayoutParams();
            assertEquals(0, shotsLayout.getRules()[RelativeLayout.CENTER_HORIZONTAL]);
            assertEquals(RelativeLayout.TRUE,
                    recoilLayout.getRules()[RelativeLayout.CENTER_HORIZONTAL]);
            assertEquals(R.id.shot_mode_tv, healthLayout.getRules()[RelativeLayout.BELOW]);
        });
    }

    @Test
    public void dualGunPlayKeepsTheAmmoRowAndMovesHealthBelowIt() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            set(activity, "mSecondaryDeviceAddress", "AA:BB:CC:DD:EE:FF");

            invoke(activity, "updateSecondGunControls");
            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, true);
            View ammoRow = activity.findViewById(R.id.boss_ammo_row);
            TextView shots = activity.findViewById(R.id.shots_remaining_tv);
            TextView health = activity.findViewById(R.id.health_label_tv);
            RelativeLayout.LayoutParams shotsLayout =
                    (RelativeLayout.LayoutParams) shots.getLayoutParams();
            RelativeLayout.LayoutParams healthLayout =
                    (RelativeLayout.LayoutParams) health.getLayoutParams();
            assertEquals(View.VISIBLE, ammoRow.getVisibility());
            assertEquals(View.INVISIBLE, shots.getVisibility());
            assertEquals(0, shotsLayout.getRules()[RelativeLayout.CENTER_HORIZONTAL]);
            assertEquals(R.id.boss_ammo_row, healthLayout.getRules()[RelativeLayout.BELOW]);

            set(activity, "mSecondaryDeviceAddress", "");
            invoke(activity, "setPlayingShotsCounter", new Class<?>[]{boolean.class}, false);
        });
    }

    @Test
    public void gameModeCanBeChangedByTheLobbyHostButNotDuringPlay() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);
            assertEquals(true, invokeResult(activity, "canChangeGameModeInLobby"));
            assertTrue(((View) get(activity, "mGameModeTV")).hasOnClickListeners());

            set(activity, "mReady", true);
            assertEquals(false, invokeResult(activity, "canChangeGameModeInLobby"));

            set(activity, "mIsServer", true);
            assertEquals(true, invokeResult(activity, "canChangeGameModeInLobby"));

            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            assertEquals(false, invokeResult(activity, "canChangeGameModeInLobby"));
        });
    }

    @Test
    public void replacedReloadTimerCannotRefillNewReload() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer oldTimer = (CountDownTimer) get(activity, "mReloadTimer");
            set(activity, "mUseNetwork", false);
            invoke(activity, "endGame");
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mReloading", 0);
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer currentTimer = (CountDownTimer) get(activity, "mReloadTimer");
            int writesBeforeStaleCallback = bluetooth.writes.size();
            oldTimer.onFinish();
            assertEquals(writesBeforeStaleCallback, bluetooth.writes.size());
            assertSame(currentTimer, get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void endingGameHidesReloadProgressWhileStillDisablingTheWeapon() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            set(activity, "mReloading", 0);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            invoke(activity, "endGame");

            assertEquals("Round teardown left the reload spinner visible", View.GONE,
                    ((View) get(activity, "mReloadBar")).getVisibility());
            assertEquals(View.VISIBLE, ((View) get(activity, "mShotsRemainingTV")).getVisibility());
            assertEquals(3, get(activity, "mReloading"));
            assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
        });
    }

    @Test
    public void assignedServerReplyChangesIdBeforeTcpRegistration() {
        scenario.onActivity(activity -> {
            boolean receiverRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (!receiverRegistered)
                set(activity, "mNetworkReceiverRegistered", true);
            try {
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                Globals.getInstance().mPlayerID = 1;

                receiveNetwork(activity, new Intent(NetMsg.NETMSG_SERVERREPLY)
                        .putExtra(UDPListenerService.INTENT_PLAYERID, (byte) 2));

                assertEquals(2, Globals.getInstance().mPlayerID);
                assertEquals(1, tcp.startRequests);
                assertEquals(2, tcp.playerIdWhenStarted);
                assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
            } finally {
                if (!receiverRegistered)
                    set(activity, "mNetworkReceiverRegistered", false);
            }
        });
    }

    @Test
    public void configurationWriteCannotStartReloadTimer() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            completeWrite(activity, GattAttributes.RECOIL_CONFIG_UUID,
                    bluetooth.writes.get(0), BluetoothGatt.GATT_SUCCESS);
            assertNull(get(activity, "mReloadTimer"));
            assertEquals(1, get(activity, "mReloading"));
        });
    }

    @Test
    public void differentCommandCannotStartReloadTimer() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            byte[] otherCommand = bluetooth.writes.get(0).clone();
            otherCommand[0] += 0x10;
            completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                    otherCommand, BluetoothGatt.GATT_SUCCESS);
            assertNull(get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void duplicateWriteCompletionCannotRestartReloadTimer() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer timer = (CountDownTimer) get(activity, "mReloadTimer");
            completeWrite(activity);
            assertSame(timer, get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void roundStartPausedDisableAcksPrepareBothGunsImmediatelyOnResume() {
        assertRoundStartAckRecoveredOnResume(false);
    }

    @Test
    public void roundStartPausedRefillAcksRestoreBothLoadedGunsWithoutAnotherWrite() {
        assertRoundStartAckRecoveredOnResume(true);
    }

    private void assertRoundStartAckRecoveredOnResume(boolean refill) {
        scenario.onActivity(activity -> {
            long previousReloadTime = Globals.getInstance().mReloadTime;
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                Globals.getInstance().mReloadTime = 10_000L;
                beginRoundStartCountdown(activity);
                if (refill) {
                    completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                            BluetoothGatt.GATT_SUCCESS);
                    completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                            BluetoothGatt.GATT_SUCCESS);
                }
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = fixture.dual.second.writes.size();
                runReloadResumeCallbacks(activity, () -> {
                    retainPendingReloadCompletion(activity, bluetooth, false, SystemClock.elapsedRealtime());
                    retainPendingReloadCompletion(activity, fixture.dual.second, true, SystemClock.elapsedRealtime());
                    assertEquals(refill ? 2 : 4, get(activity, "mReloading"));
                    assertEquals(refill ? 2 : 4, get(activity, "mSecondaryReloading"));
                }, () -> {
                    assertEquals(refill ? 0 : 2, get(activity, "mReloading"));
                    assertEquals(refill ? 0 : 2, get(activity, "mSecondaryReloading"));
                    assertNull(get(activity, "mReloadTimer"));
                    assertNull(get(activity, "mSecondaryReloadTimer"));
                    assertEquals(primaryWrites + (refill ? 0 : 1), bluetooth.writes.size());
                    assertEquals(secondaryWrites + (refill ? 0 : 1), fixture.dual.second.writes.size());
                    if (refill) {
                        assertNull(get(activity, "mPendingReloadCommand"));
                        assertNull(get(activity, "mSecondaryPendingReloadCommand"));
                        int magazine = (int) invokeResult(activity, "activeMagazineSize");
                        assertEquals(magazine, ((byte) get(activity, "mLastShotCount")) & 255);
                        assertEquals(magazine, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
                    } else {
                        assertEquals(4, ((byte[]) get(activity, "mPendingReloadCommand"))[2]);
                        assertEquals(4, ((byte[]) get(activity, "mSecondaryPendingReloadCommand"))[2]);
                    }
                    int firstWrites = bluetooth.writes.size(), secondWrites = fixture.dual.second.writes.size();
                    invoke(activity, "recoverCompletedReloadWrites");
                    assertEquals(firstWrites, bluetooth.writes.size());
                    assertEquals(secondWrites, fixture.dual.second.writes.size());
                    assertEquals("Recovering ammunition started the game early",
                            Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
                });
            } finally {
                Globals.getInstance().mReloadTime = previousReloadTime;
            }
        });
    }

    @Test
    public void ordinaryDeathKeepsBothGunsDisabledUntilRespawnUnlikeRoundStartCountdown() {
        scenario.onActivity(activity -> {
            boolean previousQrEnabled = Globals.getInstance().mRespawnQrEnabled;
            Globals.getInstance().mRespawnQrEnabled = false;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
                CountDownTimer respawn = (CountDownTimer) get(activity, "mSpawnTimer");
                assertNotNull(respawn);
                respawn.cancel();
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = guns.second.writes.size();
                assertEquals(false, get(activity, "mStartGameTimer"));
                assertEquals(3, get(activity, "mReloading"));
                assertEquals(3, get(activity, "mSecondaryReloading"));
                invoke(activity, "prepareRoundStartReloads");
                invoke(activity, "finishReload");
                invoke(activity, "finishSecondaryReload");
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, guns.second.writes.size());

                respawn.onFinish();

                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
                assertEquals(primaryWrites + 1, bluetooth.writes.size());
                assertEquals(secondaryWrites + 1, guns.second.writes.size());
                assertEquals(4, ((byte[]) get(activity, "mPendingReloadCommand"))[2]);
                assertEquals(4, ((byte[]) get(activity, "mSecondaryPendingReloadCommand"))[2]);
            } finally {
                Globals.getInstance().mRespawnQrEnabled = previousQrEnabled;
            }
        });
    }

    @Test
    public void pausedPrimaryDisableCompletionResumesItsRemainingReloadWait() {
        assertPausedReloadCompletionRecovered(false, false, false);
    }

    @Test
    public void pausedSecondaryDisableCompletionResumesItsRemainingReloadWait() {
        assertPausedReloadCompletionRecovered(true, false, false);
    }

    @Test
    public void pausedPrimaryAgedDisableCompletionRefillsWithoutAnotherWait() {
        assertPausedReloadCompletionRecovered(false, false, true);
    }

    @Test
    public void pausedSecondaryAgedDisableCompletionRefillsWithoutAnotherWait() {
        assertPausedReloadCompletionRecovered(true, false, true);
    }

    @Test
    public void pausedPrimaryRefillCompletionRestoresAmmoOnResume() {
        assertPausedReloadCompletionRecovered(false, true, false);
    }

    @Test
    public void pausedSecondaryRefillCompletionRestoresAmmoOnResume() {
        assertPausedReloadCompletionRecovered(true, true, false);
    }

    /** Exercise actual lifecycle callbacks without API-22's unreliable foreground task restore. */
    private void assertPausedReloadCompletionRecovered(boolean secondary, boolean refill, boolean aged) {
        String stateField = secondary ? "mSecondaryReloading" : "mReloading";
        String timerField = secondary ? "mSecondaryReloadTimer" : "mReloadTimer";
        String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
        String shotsField = secondary ? "mSecondaryLastShotCount" : "mLastShotCount";
        scenario.onActivity(activity -> {
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                Globals.getInstance().mReloadTime = 10_000L;
                set(activity, shotsField, (byte) 0);
                if (secondary) invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
                else invoke(activity, "startReload");
                RecordingBluetoothService service = secondary ? fixture.dual.second : bluetooth;
                if (refill) {
                    completePowerupWrite(activity, secondary, (byte[]) get(activity, pendingField),
                            BluetoothGatt.GATT_SUCCESS);
                    CountDownTimer timer = (CountDownTimer) get(activity, timerField);
                    assertNotNull(timer);
                    timer.cancel();
                    timer.onFinish();
                }
                byte[] pending = ((byte[]) get(activity, pendingField)).clone();
                assertEquals(refill ? 2 : 1, get(activity, stateField));
                assertNull(get(activity, timerField));
                int writesBeforeResume = service.writes.size();
                runReloadResumeCallbacks(activity, () -> {
                    BluetoothGattCharacteristic command = (BluetoothGattCharacteristic) get(activity,
                            secondary ? "mSecondaryCommandCharacteristic" : "mCommandCharacteristic");
                    long completedAt = SystemClock.elapsedRealtime() - (aged ? 10_001L : 0L);
                    Intent completion = retainedReloadCompletion(secondary, pending, BluetoothGatt.GATT_SUCCESS,
                            completedAt);
                    service.retainWriteResult(command, completion);
                    // The original broadcast arrives while paused and must be ignored.
                    ((BroadcastReceiver) get(activity, "mGattUpdateReceiver")).onReceive(activity, completion);
                    assertEquals(refill ? 2 : 1, get(activity, stateField));
                    assertNotNull(get(activity, pendingField));
                    assertNull(get(activity, timerField));
                }, () -> {
                    if (refill) {
                        assertEquals(0, get(activity, stateField));
                        assertEquals(pending[6], get(activity, shotsField));
                        assertNull(get(activity, pendingField));
                        assertEquals(writesBeforeResume, service.writes.size());
                    } else if (aged) {
                        assertEquals("Resume added a second full reload wait", 2, get(activity, stateField));
                        assertNull(get(activity, timerField));
                        assertEquals(writesBeforeResume + 1, service.writes.size());
                        assertEquals(4, service.writes.get(service.writes.size() - 1)[2]);
                    } else {
                        assertEquals(1, get(activity, stateField));
                        assertNotNull(get(activity, timerField));
                        assertNull(get(activity, pendingField));
                        assertEquals(writesBeforeResume, service.writes.size());
                    }
                    Object timer = get(activity, timerField);
                    int writes = service.writes.size();
                    invoke(activity, "recoverCompletedReloadWrites");
                    assertSame("Replaying a retained ACK restarted its timer", timer, get(activity, timerField));
                    assertEquals("Replaying a retained ACK issued another refill", writes, service.writes.size());
                    assertEquals("Recovering one gun changed the other gun's reload state", 0,
                            get(activity, secondary ? "mReloading" : "mSecondaryReloading"));
                });
            }
        });
    }

    @Test
    public void reloadResumeIgnoresStaleOrUnmatchedCompletionForBothGuns() {
        scenario.onActivity(activity -> {
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                Globals.getInstance().mReloadTime = 10_000L;
                invoke(activity, "startReload");
                invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
                for (boolean secondary : new boolean[]{false, true}) {
                    RecordingBluetoothService service = secondary ? fixture.dual.second : bluetooth;
                    String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
                    BluetoothGattCharacteristic command = (BluetoothGattCharacteristic) get(activity,
                            secondary ? "mSecondaryCommandCharacteristic" : "mCommandCharacteristic");
                    byte[] pending = ((byte[]) get(activity, pendingField)).clone();
                    byte[] stale = pending.clone();
                    stale[0] += 0x10;
                    service.retainWriteResult(command, retainedReloadCompletion(secondary, stale,
                            BluetoothGatt.GATT_SUCCESS, SystemClock.elapsedRealtime()));
                    invokeRegisteredReloadRecovery(activity);
                    assertEquals(1, get(activity, secondary ? "mSecondaryReloading" : "mReloading"));
                    assertNull(get(activity, secondary ? "mSecondaryReloadTimer" : "mReloadTimer"));
                    assertTrue(Arrays.equals(pending, (byte[]) get(activity, pendingField)));
                    service.retainWriteResult(characteristic(GattAttributes.RECOIL_COMMAND_UUID),
                            retainedReloadCompletion(secondary, pending, BluetoothGatt.GATT_SUCCESS,
                                    SystemClock.elapsedRealtime()));
                    invokeRegisteredReloadRecovery(activity);
                    assertNull(get(activity, secondary ? "mSecondaryReloadTimer" : "mReloadTimer"));
                    assertTrue(Arrays.equals(pending, (byte[]) get(activity, pendingField)));
                }
            }
        });
    }

    @Test
    public void reloadResumeCannotEnableEliminatedPlayersFromRetainedCompletion() {
        assertProtectedReloadRecovery(false);
    }

    @Test
    public void reloadResumeCannotEnableEitherGunDuringEmp() {
        assertProtectedReloadRecovery(true);
    }

    @Test
    public void retainedRoundEndWinsBeforeAgedReloadCompletionsOnResume() {
        scenario.onActivity(activity -> {
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                Globals.getInstance().mReloadTime = 10_000L;
                invoke(activity, "startReload");
                invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = fixture.dual.second.writes.size();
                runReloadResumeCallbacks(activity, () -> {
                    retainPendingReloadCompletion(activity, bluetooth, false, SystemClock.elapsedRealtime() - 10_001L);
                    retainPendingReloadCompletion(activity, fixture.dual.second, true,
                            SystemClock.elapsedRealtime() - 10_001L);
                    finishTcpSession(NetMsg.NETMSG_ENDGAME);
                }, () -> {
                    assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
                    assertEquals(3, get(activity, "mReloading"));
                    assertEquals(3, get(activity, "mSecondaryReloading"));
                    assertNull(get(activity, "mReloadTimer"));
                    assertNull(get(activity, "mSecondaryReloadTimer"));
                    assertNull(tcp.consumePendingTerminalEvent());
                    for (int n = primaryWrites; n < bluetooth.writes.size(); n++)
                        assertEquals("Resume refilled the primary gun before applying ENDGAME", 2,
                                bluetooth.writes.get(n)[2]);
                    for (int n = secondaryWrites; n < fixture.dual.second.writes.size(); n++)
                        assertEquals("Resume refilled the second gun before applying ENDGAME", 2,
                                fixture.dual.second.writes.get(n)[2]);
                });
            }
        });
    }

    @Test
    public void retainedEmpSnapshotPreventsRefillRecoveryBeforePowerupReplay() {
        assertRetainedEmpReloadRecovery(0);
    }

    @Test
    public void expiredRetainedEmpDoesNotBlockRefillRecovery() {
        assertRetainedEmpReloadRecovery(1);
    }

    @Test
    public void wrongLifeRetainedEmpDoesNotBlockRefillRecovery() {
        assertRetainedEmpReloadRecovery(2);
    }

    @Test
    public void wrongRoundRetainedEmpDoesNotBlockRefillRecovery() {
        assertRetainedEmpReloadRecovery(3);
    }

    private void assertRetainedEmpReloadRecovery(int staleKind) {
        scenario.onActivity(activity -> {
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                Globals globals = Globals.getInstance();
                globals.mPowerupQrRequired = 2;
                globals.mReloadTime = 10_000L;
                String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
                com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
                roster.restore(new com.simplecoil.protocol.RoundRoster.Member(LiveJoin.identity(activity), 1, 1, 1));
                roster.restore(new com.simplecoil.protocol.RoundRoster.Member(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 17, 2, 1));
                globals.mRoundRoster = roster;
                set(activity, "mActivePeerRoundToken", token);
                for (boolean secondary : new boolean[]{false, true}) {
                    if (secondary) invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
                    else invoke(activity, "startReload");
                    String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
                    completePowerupWrite(activity, secondary, (byte[]) get(activity, pendingField),
                            BluetoothGatt.GATT_SUCCESS);
                    CountDownTimer timer = (CountDownTimer) get(activity,
                            secondary ? "mSecondaryReloadTimer" : "mReloadTimer");
                    assertNotNull(timer);
                    timer.cancel();
                    timer.onFinish();
                    set(activity, secondary ? "mSecondaryLastShotCount" : "mLastShotCount", (byte) 0);
                }
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = fixture.dual.second.writes.size();
                long now = SystemClock.elapsedRealtime();
                Powerups.Grant emp = new Powerups.Grant(752, 17, 0, Powerups.EMP, new int[]{1},
                        new int[]{staleKind == 2 ? 1 : 0}, staleKind == 1 ? now - 1L : now + 5_000L, 2);
                String snapshotToken = staleKind == 3 ? "cccccccc-cccc-cccc-cccc-cccccccccccc" : token;
                try {
                    tcp.retainedPowerupState = TcpServer.powerupSnapshotJson(snapshotToken,
                            new Powerups.Snapshot(emp.id + 1, now, Collections.singletonList(emp),
                                    Collections.emptyList()));
                } catch (org.json.JSONException e) { throw new AssertionError(e); }
                runReloadResumeCallbacks(activity, () -> {
                    retainPendingReloadCompletion(activity, bluetooth, false, SystemClock.elapsedRealtime());
                    retainPendingReloadCompletion(activity, fixture.dual.second, true, SystemClock.elapsedRealtime());
                }, () -> {
                    assertEquals(primaryWrites, bluetooth.writes.size());
                    assertEquals(secondaryWrites, fixture.dual.second.writes.size());
                    if (staleKind == 0) {
                        assertEquals("Resume enabled the primary gun before the retained EMP replay", 2,
                                get(activity, "mReloading"));
                        assertEquals("Resume enabled the second gun before the retained EMP replay", 2,
                                get(activity, "mSecondaryReloading"));
                        assertEquals((byte) 0, get(activity, "mLastShotCount"));
                        assertEquals((byte) 0, get(activity, "mSecondaryLastShotCount"));
                        assertNotNull(get(activity, "mPendingReloadCommand"));
                        assertNotNull(get(activity, "mSecondaryPendingReloadCommand"));
                        // Deliver through the normal registered network receiver after its asynchronous replay.
                        receiveNetwork(activity, TcpServer.powerupIntent(emp, token, true));
                        assertEquals(3, get(activity, "mReloading"));
                        assertEquals(3, get(activity, "mSecondaryReloading"));
                        assertTrue(((long[]) get(activity, "mPowerupUntil"))[Powerups.EMP]
                                > SystemClock.elapsedRealtime());
                        assertEquals(primaryWrites + 1, bluetooth.writes.size());
                        assertEquals(secondaryWrites + 1, fixture.dual.second.writes.size());
                        assertEquals(2, bluetooth.writes.get(primaryWrites)[2]);
                        assertEquals(2, fixture.dual.second.writes.get(secondaryWrites)[2]);
                    } else {
                        assertEquals(0, get(activity, "mReloading"));
                        assertEquals(0, get(activity, "mSecondaryReloading"));
                        assertEquals((byte) 30, get(activity, "mLastShotCount"));
                        assertEquals((byte) 30, get(activity, "mSecondaryLastShotCount"));
                        assertNull(get(activity, "mPendingReloadCommand"));
                        assertNull(get(activity, "mSecondaryPendingReloadCommand"));
                    }
                });
            } finally {
                tcp.retainedPowerupState = null;
            }
        });
    }

    private static void retainPendingReloadCompletion(FullscreenActivity activity,
            RecordingBluetoothService service, boolean secondary, long completedAt) {
        BluetoothGattCharacteristic command = (BluetoothGattCharacteristic) get(activity,
                secondary ? "mSecondaryCommandCharacteristic" : "mCommandCharacteristic");
        byte[] pending = (byte[]) get(activity,
                secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand");
        service.retainWriteResult(command,
                retainedReloadCompletion(secondary, pending, BluetoothGatt.GATT_SUCCESS, completedAt));
    }

    private static void runReloadResumeCallbacks(FullscreenActivity activity, Runnable whilePaused,
            Runnable afterResume) {
        boolean wasRegistered = (boolean) get(activity, "mGattReceiverRegistered");
        if (wasRegistered) {
            activity.onPause();
            assertTrue("Pausing must keep the gun receiver active",
                    (boolean) get(activity, "mGattReceiverRegistered"));
            // The newer lifecycle keeps GATT reception alive in the background.
            // Model a delivery gap explicitly when testing retained ACK recovery.
            activity.unregisterReceiver((BroadcastReceiver) get(activity, "mGattUpdateReceiver"));
            set(activity, "mGattReceiverRegistered", false);
        }
        boolean resumed = false;
        try {
            assertFalse((boolean) get(activity, "mGattReceiverRegistered"));
            whilePaused.run();
            resumed = true;
            activity.onResume();
            afterResume.run();
        } finally {
            if (!resumed) activity.onResume();
            if (!wasRegistered) {
                if ((boolean) get(activity, "mGattReceiverRegistered")) {
                    activity.unregisterReceiver((BroadcastReceiver) get(activity, "mGattUpdateReceiver"));
                    set(activity, "mGattReceiverRegistered", false);
                }
                activity.onPause();
            }
        }
    }

    private void assertProtectedReloadRecovery(boolean emp) {
        scenario.onActivity(activity -> {
            try (ReloadResumeFixture fixture = new ReloadResumeFixture(activity, bluetooth)) {
                if (emp) {
                    Globals.getInstance().mPowerupQrRequired = 2;
                    applyPowerup(activity, personalPowerup(751, Powerups.EMP, 0), true);
                } else {
                    Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
                    invoke(activity, "startReload", new Class<?>[]{int.class}, 3);
                }
                int primaryWrites = bluetooth.writes.size(), secondaryWrites = fixture.dual.second.writes.size();
                for (boolean secondary : new boolean[]{false, true}) {
                    RecordingBluetoothService service = secondary ? fixture.dual.second : bluetooth;
                    BluetoothGattCharacteristic command = (BluetoothGattCharacteristic) get(activity,
                            secondary ? "mSecondaryCommandCharacteristic" : "mCommandCharacteristic");
                    byte[] pending = (byte[]) get(activity,
                            secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand");
                    service.retainWriteResult(command, retainedReloadCompletion(secondary, pending,
                            BluetoothGatt.GATT_SUCCESS, SystemClock.elapsedRealtime() - 10_001L));
                }
                invokeRegisteredReloadRecovery(activity);
                assertEquals(3, get(activity, "mReloading"));
                assertEquals(3, get(activity, "mSecondaryReloading"));
                assertNull(get(activity, "mReloadTimer"));
                assertNull(get(activity, "mSecondaryReloadTimer"));
                assertNull(get(activity, "mPendingReloadCommand"));
                assertNull(get(activity, "mSecondaryPendingReloadCommand"));
                assertEquals(primaryWrites, bluetooth.writes.size());
                assertEquals(secondaryWrites, fixture.dual.second.writes.size());
            }
        });
    }

    private static Intent retainedReloadCompletion(boolean secondary, byte[] data, int status, long completedAt) {
        return new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_COMMAND_UUID)
                .putExtra(BluetoothLeService.EXTRA_STATUS, status)
                .putExtra(BluetoothLeService.EXTRA_COMPLETED_AT, completedAt)
                .putExtra(BluetoothLeService.EXTRA_DATA, data.clone());
    }

    private static void invokeRegisteredReloadRecovery(FullscreenActivity activity) {
        boolean registered = (boolean) get(activity, "mGattReceiverRegistered");
        set(activity, "mGattReceiverRegistered", true);
        try { invoke(activity, "recoverCompletedReloadWrites"); }
        finally { set(activity, "mGattReceiverRegistered", registered); }
    }

    @Test
    public void failedRefillRetriesTwiceWithoutInventingAmmoThenAllowsAManualReload() {
        scenario.onActivity(activity -> {
            boolean previouslyConnected = bluetooth.simulateConnected;
            bluetooth.simulateConnected = true;
            try {
                set(activity, "mLastShotCount", (byte) 0);
                invoke(activity, "startReload");
                invoke(activity, "finishReload");
                byte[] failed = bluetooth.writes.get(1);
                completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                        failed, BluetoothGatt.GATT_FAILURE);
                for (int retry = 1; retry <= 2; retry++) {
                    assertEquals("A pending retry released the reload state", 2, get(activity, "mReloading"));
                    assertEquals((byte) 0, get(activity, "mLastShotCount"));
                    assertEquals("Retry ignored its backoff", retry + 1, bluetooth.writes.size());
                    Runnable pending = (Runnable) get(activity, "mReloadWriteRetry");
                    assertNotNull(pending);
                    ((android.os.Handler) get(activity, "mWeaponPairingHandler")).removeCallbacks(pending);
                    pending.run();
                    byte[] repeated = bluetooth.writes.get(bluetooth.writes.size() - 1);
                    assertEquals(4, repeated[2]);
                    assertEquals(failed[6], repeated[6]);
                    assertTrue("A retry reused the failed command ID", failed[0] != repeated[0]);
                    assertEquals(retry + 2, bluetooth.writes.size());
                    completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                            repeated, BluetoothGatt.GATT_FAILURE);
                    failed = repeated;
                }
                assertEquals((byte) 0, get(activity, "mLastShotCount"));
                assertEquals(0, get(activity, "mReloading"));
                assertNull(get(activity, "mReloadWriteRetry"));
                assertNull(get(activity, "mPendingReloadCommand"));
                invoke(activity, "startReload");
                assertEquals(5, bluetooth.writes.size());
                assertEquals(2, bluetooth.writes.get(4)[2]);
            } finally {
                bluetooth.simulateConnected = previouslyConnected;
            }
        });
    }

    @Test
    public void eliminationOverridesPendingRefillAndIgnoresItsCompletion() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            invoke(activity, "finishReload");
            byte[] oldRefill = bluetooth.writes.get(1);
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            assertEquals(3, get(activity, "mReloading"));
            assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
            completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                    oldRefill, BluetoothGatt.GATT_SUCCESS);
            assertEquals(3, get(activity, "mReloading"));
            completeWrite(activity);
            assertNull(get(activity, "mReloadTimer"));
            ((CountDownTimer) get(activity, "mSpawnTimer")).onFinish();
            assertEquals(4, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
            completeWrite(activity);
            assertEquals(0, get(activity, "mReloading"));
        });
    }

    @Test
    public void telemetryUsesEachBroadcastPacketEvenAfterCharacteristicChanges() {
        scenario.onActivity(activity -> {
            byte[] first = telemetryPacket(17, 1, 0, 0);
            byte[] second = telemetryPacket(17, 2, 0, 0);
            ((BluetoothGattCharacteristic) get(activity, "mTelemetryCharacteristic")).setValue(second);
            receiveTelemetry(activity, first);
            receiveTelemetry(activity, second);
            assertEquals(10, get(activity, "mHealth"));
        });
    }

    @Test
    public void bluetoothResetCancelsReloadAndDiscardsOldCharacteristics() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer timer = (CountDownTimer) get(activity, "mReloadTimer");
            invoke(activity, "resetBluetoothServices");
            assertNull(get(activity, "mReloadTimer"));
            assertNull(get(activity, "mPendingReloadCommand"));
            assertNull(get(activity, "mCommandCharacteristic"));
            assertNull(get(activity, "mTelemetryCharacteristic"));
            assertNull(get(activity, "mConfigCharacteristic"));
            assertEquals(3, get(activity, "mReloading"));
            int sent = bluetooth.writes.size();
            timer.onFinish();
            assertEquals(sent, bluetooth.writes.size());
        });
    }

    @Test
    public void rediscoveringBlasterStartsFreshReloadAfterInterruptedRefill() {
        scenario.onActivity(activity -> {
            set(activity, "mReloading", 2);
            BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
            receiver.onReceive(activity, new Intent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED));
            assertEquals(1, get(activity, "mReloading"));
            assertEquals(1, bluetooth.writes.size());
            assertEquals(2, bluetooth.writes.get(0)[2]);
        });
    }

    @Test
    public void lateJoinPreservesServerScoresAndRemainingLives() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 2, 12, 60));
            assertEquals(7, get(activity, "mScore"));
            assertEquals(12, get(activity, "mTeamScore"));
            assertEquals(3, get(activity, "mEliminationCount"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertTrue((Boolean) get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void reconnectTranslatesDeathsToRemainingLives() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            invoke(activity, "setGameLimit");
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receiveNetwork(activity, gameUpdate(7, 2, 12, 60));
            assertEquals(3, get(activity, "mEliminationCount"));
        });
    }

    @Test
    public void unlimitedLifeOverrideKeepsTheDeathCountOnReconnect() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            receiveNetwork(activity, gameUpdate(7, 8, 12, 60));
            assertEquals(8, get(activity, "mEliminationCount"));
            assertEquals(false, get(activity, "mHasLivesLimit"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void unchangedSettingsPreserveRemainingLivesInTheHud() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 3);
        });
    }

    @Test
    public void increasingLifeLimitPreservesDeathsAlreadyTaken() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 8);
            assertLifeCount(activity, 6);
            assertEquals(8, get(activity, "mLives"));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 6);
        });
    }

    @Test
    public void decreasingLifeLimitPreservesDeathsAlreadyTaken() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 4);
            assertLifeCount(activity, 2);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void addingLifeLimitConvertsDeathsToRemainingLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 0, 2);
            changeLifeOverride(activity, 5);
            assertLifeCount(activity, 3);
            assertEquals(true, get(activity, "mHasLivesLimit"));
        });
    }

    @Test
    public void unlimitedOverrideConvertsRemainingLivesBackToDeaths() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 0);
            assertLifeCount(activity, 2);
            assertEquals(false, get(activity, "mHasLivesLimit"));
            changeLifeOverride(activity, 6);
            assertLifeCount(activity, 4);
        });
    }

    @Test
    public void removingOverrideRestoresGlobalLimitWithoutRestoringSpentLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 8, 2);
            Globals.getInstance().mLivesLimit = 5;
            Globals.getInstance().mOverrideLives = false;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 3);
        });
    }

    @Test
    public void exhaustedNewLimitCancelsRespawnAndLeavesDedicatedRound() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            prepareRoundLives(activity, 5, 3);
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Player 11");
            CountDownTimer pendingSpawn = (CountDownTimer) get(activity, "mSpawnTimer");
            changeLifeOverride(activity, 3);
            assertLifeCount(activity, 0);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertEquals(Collections.singletonList(TcpServer.TCPMESSAGE_PREFIX
                    + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE), tcp.messages);
            pendingSpawn.onFinish();
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertEquals("Repeated settings must not leave again", 1, tcp.messages.size());
        });
    }

    @Test
    public void exhaustedNewLimitLeavesPeerRoundWithoutEndingOtherPlayersGame() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 0, 4);
            changeLifeOverride(activity, 3);
            assertLifeCount(activity, 0);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.peerLeaveAnnouncements);
            assertTrue(udp.messages.isEmpty());
            assertEquals(0, udp.endRequests);
        });
    }

    @Test
    public void exhaustedNewLimitEndsOfflineRoundWithoutSendingNetworkMessages() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            prepareRoundLives(activity, 5, 3);
            changeLifeOverride(activity, 2);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertLifeCount(activity, 0);
            assertTrue(udp.messages.isEmpty());
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void changingLivesInTheLobbyDoesNotSpendThePreviousRoundsLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 3);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            changeLifeOverride(activity, 2);
            assertEquals("2", ((TextView) get(activity, "mEliminationCountTV")).getText().toString());
            assertEquals(2, get(activity, "mLives"));
            assertTrue(tcp.messages.isEmpty());
            assertTrue(udp.messages.isEmpty());
        });
    }

    private void prepareRoundLives(FullscreenActivity activity, int limit, int deaths) {
        Globals globals = Globals.getInstance();
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameLimit = limit == 0 ? Globals.GAME_LIMIT_NONE : Globals.GAME_LIMIT_LIVES;
        globals.mLivesLimit = limit;
        globals.mOverrideLives = false;
        set(activity, "mHasLivesLimit", limit != 0);
        set(activity, "mLives", limit);
        set(activity, "mEliminationCount", limit == 0 ? deaths : limit - deaths);
        ((TextView) get(activity, "mEliminationCountTV")).setText(
                String.valueOf(limit == 0 ? deaths : limit - deaths));
    }

    private void changeLifeOverride(FullscreenActivity activity, int limit) {
        // Per-player life overrides are locked by tournament rules. The host
        // may still change the round's global life limit.
        Globals.getInstance().mGameLimit = limit == 0
                ? Globals.GAME_LIMIT_NONE : Globals.GAME_LIMIT_LIVES;
        Globals.getInstance().mLivesLimit = limit;
        receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
    }

    private void assertLifeCount(FullscreenActivity activity, int count) {
        assertEquals(count, get(activity, "mEliminationCount"));
        assertEquals(String.valueOf(count), ((TextView) get(activity, "mEliminationCountTV")).getText().toString());
    }

    @Test
    public void exhaustedPlayerCannotRespawnByRejoining() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 5, 12, 60));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(0, get(activity, "mEliminationCount"));
            assertNull(get(activity, "mSpawnTimer"));
            assertTrue(tcp.messages.contains(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE));
        });
    }

    @Test
    public void joiningExpiredRoundDoesNotStartAnotherSpawnOrCountdown() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 2, 12, 0));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertNull(get(activity, "mGameCountdownTimer"));
            assertEquals(false, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void dedicatedCountdownEndsLocallyWithoutWaitingForServerReply() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            invoke(activity, "startGameCountdown", new Class<?>[]{long.class}, 0L);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertEquals(false, tcp.messages.contains(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME));
        });
    }

    @Test
    public void peerCountdownBroadcastsGameEndBeforeLocalTeardown() {
        scenario.onActivity(activity -> {
            tcp.dedicated = false;

            invoke(activity, "startGameCountdown", new Class<?>[]{long.class}, 0L);

            assertEquals("A peer match expired without notifying the other phones", 1,
                    udp.endRequests);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void playerListUpdateCannotExposeLobbyControlsDuringRound() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(0, 0, 0, 60));
            assertEquals(View.GONE, ((View) get(activity, "mStartGameButton")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mPlayerSettingsButton")).getVisibility());
            assertTrue(((View) get(activity, "mFiringModeButton")).getVisibility() != View.VISIBLE);
        });
    }

    @Test
    public void finishedServerSnapshotDoesNotStartLobbyCountdown() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            set(activity, "mLobbyJoined", false);
            boolean previousReady = Globals.getInstance().mLocalLobbyReady;
            Globals.getInstance().mLocalLobbyReady = true;
            try {
                receiveNetwork(activity, gameUpdate(7, 5, 12, 0)
                        .putExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_NONE));
                assertNull(get(activity, "mGameCountdownTimer"));
                assertEquals(false, get(activity, "mGameTimerRunning"));
                assertEquals(7, get(activity, "mScore"));
                assertEquals(1, tcp.messages.size());
                String prefix = TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_JSON;
                assertTrue(tcp.messages.get(0).startsWith(prefix));
                try {
                    assertTrue(new org.json.JSONObject(tcp.messages.get(0).substring(prefix.length()))
                            .getBoolean(TcpServer.JSON_LOBBY_READY));
                } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
                assertFalse(tcp.messages.contains(TcpServer.TCPMESSAGE_PREFIX
                        + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME));
            } finally {
                Globals.getInstance().mLocalLobbyReady = previousReady;
            }
        });
    }

    @Test
    public void lateStartAnnouncementCannotRestartRoundAfterLeaving() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            set(activity, "mReady", true);
            invoke(activity, "endGame");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_STARTGAME));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void lateScoreMessagesCannotChangeFinishedRound() {
        scenario.onActivity(activity -> {
            set(activity, "mScore", 7);
            set(activity, "mTeamScore", 12);
            invoke(activity, "endGame");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(7, get(activity, "mScore"));
            assertEquals(12, get(activity, "mTeamScore"));
        });
    }

    @Test
    public void readyLobbyStillAcceptsServerStart() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            invoke(activity, "setGameLimit");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_STARTGAME));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void respawningPlayerStillReceivesEarnedPoints() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            set(activity, "mScore", 0);
            set(activity, "mTeamScore", 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(1, get(activity, "mScore"));
            assertEquals(1, get(activity, "mTeamScore"));
        });
    }

    @Test
    public void peerScoreCountersSaturateRatherThanOverflow() {
        scenario.onActivity(activity -> {
            set(activity, "mScore", Globals.MAX_SCOREBOARD_VALUE);
            set(activity, "mTeamScore", Globals.MAX_TEAM_SCOREBOARD_VALUE);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(Globals.MAX_SCOREBOARD_VALUE, get(activity, "mScore"));
            assertEquals(Globals.MAX_TEAM_SCOREBOARD_VALUE, get(activity, "mTeamScore"));
        });
    }

    @Test
    public void ownKillReachingCombinedTeamLimitEndsPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_2TEAMS);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(2, get(activity, "mScore"));
            assertEquals(5, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void teammateKillReachingLimitEndsFourTeamPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_4TEAMS);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(1, get(activity, "mScore"));
            assertEquals(5, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void freeForAllIgnoresTeamScoreNotifications() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(4, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void dedicatedScoreLimitDoesNotSendAnEndOrLeaveRequest() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            tcp.dedicated = true;
            Globals.getInstance().mOnlyServerSettings = true;
            set(activity, "mScore", 4);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertTrue(tcp.messages.isEmpty());
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void disabledOrInvalidScoreLimitDoesNotEndRound() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            Globals.getInstance().mScoreLimit = 0;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            Globals.getInstance().mScoreLimit = 1;
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(0, udp.endRequests);
        });
    }

    @Test
    public void individualLimitStillEndsFreeForAllPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            set(activity, "mScore", 4);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void roundEndReceivedWhilePausedIsAppliedOnResume() {
        receiveWhilePaused(NetMsg.NETMSG_ENDGAME);
        scenario.onActivity(activity -> assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState));
    }

    @Test
    public void peerRoundEndReceivedWhilePausedIsAppliedOnResume() {
        scenario.onActivity(activity -> {
            final String roundToken = "88888888-8888-8888-8888-888888888888";
            udp.startGame(true, roundToken);
            set(activity, "mActivePeerRoundToken", roundToken);
            boolean wasRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (wasRegistered)
                activity.onPause();
            try {
                retainPeerEndGame(udp, roundToken);
            } finally {
                activity.onResume();
                if (!wasRegistered)
                    activity.onPause();
            }
            assertEquals("A peer ENDGAME received while paused left the round active",
                    Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull("The resumed activity did not consume the retained peer ENDGAME",
                    udp.consumePendingPeerEndGame());
        });
    }

    @Test
    public void peerEndAfterPausedStartIsAppliedAfterTheStartOnResume() {
        scenario.onActivity(activity -> {
            final String roundToken = "99999999-9999-9999-9999-999999999999";
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
            set(activity, "mActivePeerRoundToken", null);
            boolean wasRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (wasRegistered)
                activity.onPause();
            try {
                // This reproduces a TCP start and a matching UDP ENDGAME both
                // arriving while the activity's receiver is paused.
                udp.preparePeerRound(roundToken);
                retainPeerEndGame(udp, roundToken);
                queuePendingPeerStart(roundToken);
            } finally {
                activity.onResume();
                if (!wasRegistered)
                    activity.onPause();
            }
            assertEquals("The resumed peer round remained active after its retained ENDGAME",
                    Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull("The resumed activity did not consume the pending peer start",
                    tcp.consumePendingGameStart(0));
            assertNull("The resumed activity did not consume the retained peer ENDGAME",
                    udp.consumePendingPeerEndGame());
        });
    }

    @Test
    public void serverCancellationReceivedWhilePausedIsAppliedOnResume() {
        receiveWhilePaused(NetMsg.NETMSG_SERVERCANCEL);
        scenario.onActivity(activity -> assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState));
    }

    @Test
    public void hubConnectionLossCannotEndAnActiveRound() {
        receiveWhilePaused(NetMsg.NETMSG_SERVERUNREACHABLE);
        scenario.onActivity(activity -> {
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mLobbyJoined"));
        });
    }

    @Test
    public void hubConnectionLossReturnsLobbyToQuietDiscovery() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
            set(activity, "mLobbyJoined", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_SERVERUNREACHABLE));
            assertEquals(false, get(activity, "mReady"));
            assertEquals(false, get(activity, "mLobbyJoined"));
            assertEquals(activity.getString(R.string.lobby_reconnecting), get(activity, "mLobbyNotice"));
        });
    }

    @Test
    public void versionRejectionReceivedWhilePausedClearsLobbyReadiness() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
        });
        receiveWhilePaused(NetMsg.NETMSG_VERSIONERROR);
        scenario.onActivity(activity -> assertEquals(false, get(activity, "mReady")));
    }

    @Test
    public void duplicateTerminalNotificationCannotEndAnotherRound() {
        scenario.onActivity(activity -> {
            finishTcpSession(NetMsg.NETMSG_ENDGAME);
            Intent notification = tcp.broadcasts.get(tcp.broadcasts.size() - 1);
            receiveNetwork(activity, notification);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receiveNetwork(activity, notification);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void lateNetworkEndCannotStopAnOfflineRound() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_SERVERCANCEL));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_VERSIONERROR));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void staleNetworkCallbackAfterUnregistrationCannotEndTheRound() {
        scenario.onActivity(activity -> {
            boolean registered = (boolean) get(activity, "mNetworkReceiverRegistered");
            set(activity, "mNetworkReceiverRegistered", false);
            try {
                receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            } finally {
                set(activity, "mNetworkReceiverRegistered", registered);
            }
        });
    }

    @Test
    public void roundEndClearsReadinessEvenBeforeThePlayerHasSpawned() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void versionRejectionAlsoStopsAnActiveRound() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_VERSIONERROR));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mReady"));
        });
    }

    private void prepareScoreLimit(FullscreenActivity activity, int mode) {
        Globals globals = Globals.getInstance();
        globals.mGameMode = mode;
        globals.mGameLimit = Globals.GAME_LIMIT_SCORE;
        globals.mScoreLimit = 5;
        set(activity, "mScore", 1);
        set(activity, "mTeamScore", 4);
    }

    private void finishTcpSession(String action) {
        try {
            // This helper models a terminal frame from a live TCP worker. The
            // recording service does not open a real socket, so mark its session
            // active before invoking the worker's terminal path directly.
            Field listening = TcpClient.class.getDeclaredField("keepListening");
            listening.setAccessible(true);
            listening.setBoolean(tcp, true);
            Method finish = TcpClient.class.getDeclaredMethod("finishServerSession", String.class);
            finish.setAccessible(true);
            finish.invoke(tcp, action);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void receiveWhilePaused(String action) {
        // Exercise the actual registration/lifecycle callbacks on the main thread.
        // ActivityScenario's helper activity cannot reliably restore this API-22
        // device's foreground task after moveToState(STARTED).
        scenario.onActivity(activity -> {
            boolean wasRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (wasRegistered)
                activity.onPause();
            try {
                finishTcpSession(action);
            } finally {
                activity.onResume();
                // The device may already have backgrounded the scenario. Restore
                // its original receiver state after exercising the resume callback.
                if (!wasRegistered)
                    activity.onPause();
            }
        });
    }

    private static void retainPeerEndGame(UDPListenerService service, String roundToken) {
        try {
            Field field = UDPListenerService.class.getDeclaredField("mPendingPeerEndGame");
            field.setAccessible(true);
            field.set(service, new Intent(NetMsg.NETMSG_ENDGAME)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void queuePendingPeerStart(String roundToken) {
        try {
            Field field = TcpClient.class.getDeclaredField("mPendingGameStartEvent");
            field.setAccessible(true);
            field.set(tcp, new Intent(NetMsg.NETMSG_STARTGAME)
                    .putExtra(NetMsg.INTENT_START_AT, SystemClock.elapsedRealtime() + 5000)
                    .putExtra(NetMsg.INTENT_END_AT, 0L)
                    .putExtra(NetMsg.INTENT_ROUND_ID, 1L)
                    .putExtra(NetMsg.INTENT_ROUND_TOKEN, roundToken)
                    .putExtra(NetMsg.INTENT_PEER_GAME, true));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void initialCountdownUsesHostDeadlineInsteadOfPersonalRespawnTime() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Globals.getInstance().mRespawnTime = 90;
            long deadline = SystemClock.elapsedRealtime() + 4000;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            String countdown = ((TextView) get(activity, "mSpawnInTV")).getText().toString();
            assertTrue(countdown, countdown.startsWith("Game starts in "));
            assertTrue(countdown, !countdown.contains("90"));
            assertEquals(deadline, get(activity, "mSynchronizedStartAt"));
            assertEquals(true, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void initialCountdownTracksOnlyItsOwnClockAdjustment() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + GameStartCountdown.DURATION_MS;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            timer.onTick(0);
            assertEquals("A timer argument replaced the shared absolute deadline",
                    Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertSame(timer, get(activity, "mSpawnTimer"));

            long adjustedDeadline = deadline - 250;
            receiveNetwork(activity, synchronizedStart(adjustedDeadline, adjustedDeadline + 60000)
                    .putExtra(NetMsg.INTENT_START_TIME_ADJUSTMENT, true));
            assertSame(timer, get(activity, "mSpawnTimer"));
            assertEquals(adjustedDeadline, get(activity, "mSynchronizedStartAt"));
            assertEquals(adjustedDeadline + 60000, get(activity, "mSynchronizedEndAt"));

            receiveNetwork(activity, synchronizedStart(deadline - 500, deadline + 59500)
                    .putExtra(NetMsg.INTENT_ROUND_ID, 2L)
                    .putExtra(NetMsg.INTENT_START_TIME_ADJUSTMENT, true));
            assertEquals(adjustedDeadline, get(activity, "mSynchronizedStartAt"));
        });
    }

    @Test
    public void laterTeamRespawnUsesCheckpointWaitAndCannotResetRoundTimer() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() - 1000;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            Object roundTimer = get(activity, "mGameCountdownTimer");
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Hit");
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertTrue(((TextView) get(activity, "mSpawnInTV")).getText().toString().contains("03:00"));
            assertSame(roundTimer, get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void timerOnlyTeamRespawnKeepsThreeMinutesWithoutCameraOrQrReminders() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Globals globals = Globals.getInstance();
            globals.mRespawnQrEnabled = false;
            long deadline = SystemClock.elapsedRealtime() - 1000;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 600000));
            Object roundTimer = get(activity, "mGameCountdownTimer");
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Hit");
            assertEquals(Globals.GAME_STATE_ELIMINATED, globals.mGameState);
            assertEquals(activity.getString(R.string.respawn_timer_wait_label, 3, 0),
                    ((TextView) get(activity, "mSpawnInTV")).getText().toString());
            assertEquals(false, get(activity, "mRespawnQrScannerActive"));
            assertEquals(false, get(activity, "mRespawnQrVoiceReminderActive"));
            assertEquals(View.GONE, activity.findViewById(R.id.respawn_qr_scanner_overlay).getVisibility());
            assertSame(roundTimer, get(activity, "mGameCountdownTimer"));
            CountDownTimer timer = (CountDownTimer) get(activity, "mSpawnTimer");
            assertNotNull(timer);
            timer.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertSame(roundTimer, get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void timerOnlyInfectionConvertsKilledSurvivorBeforeRespawning() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mRespawnQrEnabled = false;
            globals.mInfectionMode = true;
            globals.mPlayerID = 2;
            globals.resetInfectedPlayers();
            assertFalse(globals.isPlayerInfected((byte) 2));
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Zombie");
            ((CountDownTimer) get(activity, "mSpawnTimer")).onFinish();
            assertTrue(globals.isPlayerInfected((byte) 2));
            assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
        });
    }

    @Test
    public void lateUnlimitedStartUsesOriginalChronometerBase() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() - 5000;
            receiveNetwork(activity, synchronizedStart(deadline, 0));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(deadline, ((Chronometer) get(activity, "mGameTimer")).getBase());
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void staleZeroSecondsCannotOverrideSynchronizedRejoinDeadline() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            receiveNetwork(activity, gameUpdate(7, 2, 12, 0)
                    .putExtra(NetMsg.INTENT_START_AT, deadline).putExtra(NetMsg.INTENT_END_AT, deadline + 60000));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(7, get(activity, "mScore"));
            assertEquals(true, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void expiredSynchronizedStartNeverArmsBlaster() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, synchronizedStart(SystemClock.elapsedRealtime() - 5000,
                    SystemClock.elapsedRealtime() - 1000));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void initialCountdownCannotReceivePointsAndCancelledTimerCannotStartGame() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, synchronizedStart(SystemClock.elapsedRealtime() + 10000, 0));
            CountDownTimer countdown = (CountDownTimer) get(activity, "mSpawnTimer");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(0, get(activity, "mScore"));
            assertEquals(0, get(activity, "mTeamScore"));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            countdown.onFinish();
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void startIsRetainedUntilActivityServicesAreBound() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Intent start = synchronizedStart(SystemClock.elapsedRealtime() + 10000, 0)
                    .putExtra(TcpClient.EXTRA_START_EVENT_ID, 42L);
            try {
                Field pending = TcpClient.class.getDeclaredField("mPendingGameStartEvent");
                pending.setAccessible(true);
                pending.set(tcp, start);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            Object server = get(activity, "mTcpServer");
            set(activity, "mTcpServer", null);
            receiveNetwork(activity, start);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            set(activity, "mTcpServer", server);
            boolean registered = (boolean) get(activity, "mNetworkReceiverRegistered");
            set(activity, "mNetworkReceiverRegistered", true);
            try { invoke(activity, "consumePendingServerEvent"); }
            finally { set(activity, "mNetworkReceiverRegistered", registered); }
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertNull(tcp.consumePendingGameStart(0));
            Object timer = get(activity, "mSpawnTimer");
            receiveNetwork(activity, start);
            assertSame(timer, get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void pausedPeerHostRecoversItsOriginalDeadlineAndRejectsCancelledStart() {
        scenario.onActivity(activity -> {
            set(activity, "mReady", true);
            set(activity, "mIsServer", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            TcpServer server = (TcpServer) get(activity, "mTcpServer");
            try {
                for (String fieldName : new String[]{"mScheduledStart", "mRoundSequence", "mStartAnnounced"}) {
                    Field field = TcpServer.class.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (fieldName.equals("mStartAnnounced")) field.setBoolean(server, true);
                    else field.setLong(server, fieldName.equals("mRoundSequence") ? 1 : deadline);
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            boolean registered = (boolean) get(activity, "mNetworkReceiverRegistered");
            set(activity, "mNetworkReceiverRegistered", true);
            try { invoke(activity, "consumePendingServerEvent"); }
            finally { set(activity, "mNetworkReceiverRegistered", registered); }
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(deadline, get(activity, "mSynchronizedStartAt"));
            invoke(activity, "endGame");
            set(activity, "mReady", true);
            set(activity, "mIsServer", true);
            receiveNetwork(activity, synchronizedStart(deadline, 0));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    private static Intent synchronizedStart(long startAt, long endAt) {
        return new Intent(NetMsg.NETMSG_STARTGAME).putExtra(NetMsg.INTENT_START_AT, startAt)
                .putExtra(NetMsg.INTENT_END_AT, endAt).putExtra(NetMsg.INTENT_ROUND_ID, 1L);
    }

    private void prepareDedicatedJoin(FullscreenActivity activity) {
        tcp.dedicated = true;
        set(activity, "mReady", true);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_LIVES | Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mLivesLimit = 5;
        Globals.getInstance().mTimeLimit = 5;
    }

    private static Intent gameUpdate(int score, int deaths, int teamScore, long remainingSeconds) {
        return new Intent(NetMsg.NETMSG_LISTPLAYERS)
                .putExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_RUNNING)
                .putExtra(NetMsg.INTENT_HASGAMEUPDATE, true)
                .putExtra(NetMsg.INTENT_SCORE, score)
                .putExtra(NetMsg.INTENT_ELIMINATIONS, deaths)
                .putExtra(NetMsg.INTENT_TEAMSCORE, teamScore)
                .putExtra(NetMsg.INTENT_TIMEREMAINING, remainingSeconds);
    }

    private static void receiveNetwork(FullscreenActivity activity, Intent intent) {
        ((BroadcastReceiver) get(activity, "mUDPUpdateReceiver")).onReceive(activity, intent);
    }

    @Test
    public void zeroRemainingGameTimeEndsRound() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            invoke(activity, "startGameCountdown", new Class<?>[]{long.class}, 0L);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void originalZombieBecomesVulnerableAfterTwoKills() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mInfectionMode = true;
            globals.mPlayerID = 1;

            set(activity, "mScore", 0);
            assertEquals(true, invokeResult(activity, "isOriginalZombieProtected"));
            set(activity, "mScore", 1);
            assertEquals(true, invokeResult(activity, "isOriginalZombieProtected"));
            set(activity, "mScore", 2);
            assertEquals(false, invokeResult(activity, "isOriginalZombieProtected"));

            globals.mReloadTime = 5_000;
            set(activity, "mEliminationCount", 0);
            assertEquals(1_000L, invokeResult(activity, "activeReloadTime"));
            set(activity, "mEliminationCount", 1);
            assertEquals(5_000L, invokeResult(activity, "activeReloadTime"));

            // Recruited zombies never receive the original zombie's protection.
            globals.mPlayerID = 2;
            set(activity, "mScore", 0);
            assertEquals(false, invokeResult(activity, "isOriginalZombieProtected"));
            set(activity, "mHealth", 1);
            assertEquals(GameOutcome.Result.WON,
                    invokeResult(activity, "completedRoundOutcome"));
            globals.setPlayerInfected((byte) 2, true);
            assertEquals(GameOutcome.Result.LOST,
                    invokeResult(activity, "completedRoundOutcome"));
        });
    }

    @Test
    public void hitInSecondSlotUsesConfiguredDamageOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 0, 0, 17, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void twoDifferentAttackersApplyTheirOwnDamageOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 17, 1, 18, 1);
            assertEquals(8, get(activity, "mHealth"));
        });
    }

    @Test
    public void benchedBlasterCannotDamagePlayersInEitherHitSlot() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            Map<Byte, LobbyPlayer> originalRoster = globals.mLobbyPlayers;
            int originalDamage = globals.mDamage;
            globals.mLobbyPlayers = Collections.singletonMap((byte) 17,
                    new LobbyPlayer((byte) 17, "Spare", true, true, true, true, false));
            try {
                telemetry(activity, 17, 1, 18, 1);
                assertEquals(13, get(activity, "mHealth"));
                assertEquals(1, get(activity, "mHitsTaken"));
                telemetry(activity, 18, 2, 17, 2);
                assertEquals(6, get(activity, "mHealth"));
                assertEquals(2, get(activity, "mHitsTaken"));

                // Sitting out a network round must not disable solo practice.
                set(activity, "mUseNetwork", false);
                globals.mDamage = -1;
                telemetry(activity, 17, 3, 0, 0);
                assertEquals(5, get(activity, "mHealth"));
            } finally {
                globals.mLobbyPlayers = originalRoster;
                globals.mDamage = originalDamage;
                set(activity, "mUseNetwork", true);
            }
        });
    }

    @Test
    public void repeatedShotInBothSlotsAndNextPacketCountsOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 17, 1, 17, 1);
            assertEquals(15, get(activity, "mHealth"));
            telemetry(activity, 17, 1, 17, 1);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void differentShotsFromSameAttackerBothCount() {
        scenario.onActivity(activity -> {
            telemetry(activity, 17, 1, 17, 2);
            assertEquals(10, get(activity, "mHealth"));
        });
    }

    @Test
    public void friendlyHitDoesNotAddDamageToEnemyHit() {
        scenario.onActivity(activity -> {
            telemetry(activity, 2, 1, 17, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void offlineHitsUseTheConfiguredLocalDamage() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            int originalDamage = globals.mDamage;
            boolean receiverRegistered = (boolean) get(activity, "mGattReceiverRegistered");
            try {
                set(activity, "mUseNetwork", false);
                // API-22 can background an ActivityScenario while this test is
                // running. Exercise the receiver's active-session path directly.
                set(activity, "mGattReceiverRegistered", true);
                globals.mDamage = -5;

                telemetry(activity, 17, 1, 0, 0);

                assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
                assertEquals(1, get(activity, "mHitsTaken"));
                assertEquals("Offline damage setting was ignored", 15, get(activity, "mHealth"));
            } finally {
                globals.mDamage = originalDamage;
                set(activity, "mUseNetwork", true);
                set(activity, "mGattReceiverRegistered", receiverRegistered);
            }
        });
    }

    @Test
    public void lastNetworkLifeLeavesWithoutRespawning() {
        scenario.onActivity(activity -> {
            set(activity, "mHasLivesLimit", true);
            set(activity, "mEliminationCount", 1);
            set(activity, "mHealth", 5);
            telemetry(activity, 17, 1, 0, 0);
            assertEquals(1, udp.peerEliminationPublishes);
            assertEquals(Byte.valueOf((byte) 17), udp.lastPeerEliminationRecipient);
            assertEquals(1, udp.peerLeaveAnnouncements);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void thirtySecondPlayerCanBeConfiguredOnTheBlasterAndScoreHits() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPlayerID = 32;
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            invoke(activity, "setTeam");
            assertEquals(32, bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
            Globals.getInstance().mPlayerID = 1;
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mNetworkTeam", 1);
            telemetry(activity, 32, 1, 0, 0);
            assertEquals(19, get(activity, "mHealth"));
        });
    }

    @Test
    public void zeroBlasterStatusCannotEraseTheSelectedPlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(0);
    }

    @Test
    public void unsupportedBlasterStatusCannotReplaceTheSelectedPlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(33);
    }

    @Test
    public void unsignedBlasterStatusCannotInstallANegativePlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(255);
    }

    private void assertInvalidTelemetryPlayerIdIgnored(int playerID) {
        scenario.onActivity(activity -> {
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = (byte) playerID;
            packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 29;
            receiveTelemetry(activity, packet);
            assertEquals("Invalid telemetry replaced our registered player ID", 1, Globals.getInstance().mPlayerID);
            assertEquals((byte) 1, get(activity, "mLastTeam"));
            assertEquals(1, get(activity, "mNetworkTeam"));
            assertEquals("Other valid fields in the packet should still be processed", (byte) 29,
                    get(activity, "mLastShotCount"));
            invoke(activity, "startReload");
            assertEquals("Reload must still configure our selected ID", 1,
                    bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
        });
    }

    @Test
    public void unsupportedFirstHitCannotTakeTheLastLife() {
        assertUnsupportedHitIgnored(false);
    }

    @Test
    public void unsupportedSecondHitCannotTakeTheLastLife() {
        assertUnsupportedHitIgnored(true);
    }

    private void assertUnsupportedHitIgnored(boolean secondSlot) {
        scenario.onActivity(activity -> {
            set(activity, "mHealth", 1);
            set(activity, "mHasLivesLimit", true);
            set(activity, "mEliminationCount", 1);
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2
                    : FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) (33 << 2);
            receiveTelemetry(activity, packet);
            assertEquals("An unsupported attacker consumed our last life", 1, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mEliminationCount"));
            assertEquals(0, get(activity, "mHitsTaken"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertTrue(udp.messages.isEmpty());
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void invalidHitSlotDoesNotHideAValidHitInTheSamePacket() {
        scenario.onActivity(activity -> {
            telemetry(activity, 33, 1, 17, 1);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void reservedAndUnsignedHitSourcesCannotCauseDamage() {
        scenario.onActivity(activity -> {
            for (int source : new int[]{1, 2, 3, 132, 133, 164, 166, 168, 255}) {
                byte[] packet = telemetryPacket(0, 0, 0, 0);
                packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) source;
                receiveTelemetry(activity, packet);
                assertEquals("Invalid source " + source + " caused damage", 20, get(activity, "mHealth"));
                assertEquals("Invalid source " + source + " counted as a hit", 0, get(activity, "mHitsTaken"));
            }
            assertTrue(udp.messages.isEmpty());
        });
    }

    @Test
    public void validThirtySecondPlayerStatusStillUpdatesTheDisplayedTeam() {
        scenario.onActivity(activity -> {
            // A gun may choose the local ID before admission; the host owns it
            // once the phone has joined or committed a network round.
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mLobbyJoined", false);
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = 32;
            receiveTelemetry(activity, packet);
            assertEquals(32, Globals.getInstance().mPlayerID);
            assertEquals((byte) 32, get(activity, "mLastTeam"));
            assertEquals(2, get(activity, "mNetworkTeam"));
        });
    }

    @Test
    public void initialGameSpawnDoesNotReceiveRespawnShieldBonus() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mFullShields = 10;
            set(activity, "mUseNetwork", false);
            invoke(activity, "startGame");
            CountDownTimer initialSpawn = (CountDownTimer) get(activity, "mSpawnTimer");
            assertTrue(initialSpawn != null);
            initialSpawn.cancel();
            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
            initialSpawn.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mStartGameTimer"));
            assertEquals(0, get(activity, "mRespawnShieldBonus"));
            assertNull(get(activity, "mRespawnShieldBoostTimer"));
        });
    }

    @Test
    public void respawnShieldBonusAbsorbsDamageWithoutReducingNormalShields() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mFullShields = 10;
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            ((CountDownTimer) get(activity, "mSpawnTimer")).onFinish();

            CountDownTimer boost = (CountDownTimer) get(activity, "mRespawnShieldBoostTimer");
            ProgressBar shieldBar = activity.findViewById(R.id.shield_pb);
            assertTrue("Respawn boost must have a five-second expiry", boost != null);
            assertEquals(10, get(activity, "mShield"));
            assertEquals(5, get(activity, "mRespawnShieldBonus"));
            assertEquals(15, shieldBar.getMax());
            assertEquals(15, shieldBar.getProgress());

            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -3);
            assertEquals(10, get(activity, "mShield"));
            assertEquals(2, get(activity, "mRespawnShieldBonus"));
            assertEquals(12, shieldBar.getProgress());

            boost.onFinish();
            assertEquals(10, get(activity, "mShield"));
            assertEquals(0, get(activity, "mRespawnShieldBonus"));
            assertEquals(10, shieldBar.getMax());
            assertEquals(10, shieldBar.getProgress());
        });
    }

    @Test
    public void damagePastRespawnShieldBonusRemainsAfterBoostExpires() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mFullShields = 10;
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            ((CountDownTimer) get(activity, "mSpawnTimer")).onFinish();
            CountDownTimer boost = (CountDownTimer) get(activity, "mRespawnShieldBoostTimer");
            assertTrue(boost != null);

            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -7);
            assertEquals(0, get(activity, "mRespawnShieldBonus"));
            assertEquals(8, get(activity, "mShield"));
            assertTrue("Damage to normal shields must still regenerate normally",
                    get(activity, "mShieldTimer") != null);

            boost.onFinish();
            assertEquals(8, get(activity, "mShield"));
            ProgressBar shieldBar = activity.findViewById(R.id.shield_pb);
            assertEquals(10, shieldBar.getMax());
            assertEquals(8, shieldBar.getProgress());
        });
    }

    @Test
    public void healthRegeneratesAfterThirtySecondsThenTicksOncePerSecond() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mFullHealth = 20;
            globals.mFullShields = 0;
            globals.mBossMode = false;
            set(activity, "mHealth", 20);
            set(activity, "mShield", 0);

            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -5);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(30_000L, get(activity, "HEALTH_REGEN_DELAY_MILLISECONDS"));
            CountDownTimer initialDelay = (CountDownTimer) get(activity, "mHealthTimer");
            assertTrue("Health damage must arm the inactivity timer", initialDelay != null);

            initialDelay.onFinish();
            assertEquals(16, get(activity, "mHealth"));
            CountDownTimer nextTick = (CountDownTimer) get(activity, "mHealthTimer");
            assertTrue("Health must continue regenerating after the first point", nextTick != null);
            assertNotSame(initialDelay, nextTick);

            // A retired timer must not apply its point twice.
            initialDelay.onFinish();
            assertEquals(16, get(activity, "mHealth"));
            nextTick.onFinish();
            assertEquals(17, get(activity, "mHealth"));
        });
    }

    @Test
    public void anotherHitRestartsHealthInactivityAndEliminationCannotRegenerate() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mFullHealth = 20;
            globals.mFullShields = 0;
            globals.mBossMode = false;
            set(activity, "mHealth", 20);
            set(activity, "mShield", 0);

            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -5);
            CountDownTimer first = (CountDownTimer) get(activity, "mHealthTimer");
            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -1);
            CountDownTimer restarted = (CountDownTimer) get(activity, "mHealthTimer");
            assertNotSame("A later hit must restart the thirty-second wait", first, restarted);
            first.onFinish();
            assertEquals(14, get(activity, "mHealth"));

            set(activity, "mHealth", 1);
            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -1);
            assertEquals(0, get(activity, "mHealth"));
            assertNull("An eliminated player must not regenerate", get(activity, "mHealthTimer"));
        });
    }

    @Test
    public void bossModeKeepsItsNoRegenerationRule() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mFullHealth = 20;
            globals.mFullShields = 10;
            globals.mBossMode = true;
            set(activity, "mHealth", 20);
            set(activity, "mShield", 0);

            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -5);
            assertEquals(15, get(activity, "mHealth"));
            assertNull(get(activity, "mHealthTimer"));
            assertNull(get(activity, "mShieldTimer"));
        });
    }

    @Test
    public void respawnQrScannerStaysOpenWhileTheTimeoutRuns() {
        scenario.onActivity(activity -> {
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            CountDownTimer timeout = (CountDownTimer) get(activity, "mSpawnTimer");
            assertTrue("Respawn must keep its fallback timeout", timeout != null);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);

            boolean cameraAvailable = activity.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED);
            if (cameraAvailable) {
                View scanner = activity.findViewById(R.id.respawn_qr_scanner_overlay);
                View cancel = activity.findViewById(R.id.qr_scanner_cancel_button);
                assertEquals(true, get(activity, "mRespawnQrScannerActive"));
                assertEquals(View.VISIBLE, scanner.getVisibility());
                assertEquals(View.GONE, cancel.getVisibility());
                invoke(activity, "handleRespawnQrCode", new Class<?>[]{String.class}, "not a respawn code");
                assertEquals("An invalid QR must leave the camera open", View.VISIBLE,
                        scanner.getVisibility());
                activity.onBackPressed();
                assertEquals("Back must not dismiss the respawn camera", View.VISIBLE,
                        scanner.getVisibility());
                assertSame(timeout, get(activity, "mSpawnTimer"));
                androidx.appcompat.app.AlertDialog leave =
                        (androidx.appcompat.app.AlertDialog) get(activity, "mGameExitDialog");
                assertTrue("Back must offer a confirmed exit while respawning", leave.isShowing());
                leave.dismiss();
            }

            timeout.onFinish();
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertEquals(View.GONE, activity.findViewById(R.id.respawn_qr_scanner_overlay)
                    .getVisibility());
        });
    }

    @Test
    public void powerupRouletteReallyAnimatesRevealsAndDrawsOnThePhone() throws InterruptedException {
        float animatorScale = rouletteAnimatorScale();
        org.junit.Assume.assumeTrue("Animation duration must fit the bounded phone test",
                animatorScale >= 0 && animatorScale <= 2);
        PowerupRouletteView[] roulette = new PowerupRouletteView[1];
        String[] reward = new String[1];
        CountDownLatch announced = new CountDownLatch(1);
        AtomicInteger announcements = new AtomicInteger();
        try {
            scenario.onActivity(activity -> {
                roulette[0] = activity.findViewById(R.id.powerup_roulette);
                reward[0] = activity.getString(PowerupPresentation.name(Powerups.SHIELD_PLAYER));
                roulette[0].showReward(reward[0], () -> {
                    announcements.incrementAndGet();
                    announced.countDown();
                });
            });
            assertTrue("The real roulette animation never announced its reward",
                    announced.await(10, TimeUnit.SECONDS));
            assertEquals(1, announcements.get());
            scenario.onActivity(activity -> {
                PowerupRouletteView view = roulette[0];
                assertEquals(View.VISIBLE, view.getVisibility());
                assertEquals(reward[0], view.getContentDescription().toString());
                // Disabled animations may complete before Android's next layout.
                // Measure the real XML-sized view before exercising its Canvas path.
                int width = view.getLayoutParams().width;
                int height = view.getLayoutParams().height;
                assertTrue(width > 0 && height > 0);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                try {
                    view.draw(new Canvas(bitmap));
                    assertTrue("The roulette Canvas renderer produced no visible pixels",
                            bitmap.getPixel(width / 2, height / 2) != 0);
                } finally {
                    bitmap.recycle();
                }
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (roulette[0] != null) {
                    roulette[0].cancel();
                    assertEquals(View.GONE, roulette[0].getVisibility());
                }
            });
        }
    }

    @Test
    public void queuedRealPowerupRouletteSpinKeepsTheSecondRevealVisible() throws InterruptedException {
        float animatorScale = rouletteAnimatorScale();
        org.junit.Assume.assumeTrue("Queue timing requires enabled, reasonably scaled animations",
                animatorScale >= .25f && animatorScale <= 2);
        PowerupRouletteView[] roulette = new PowerupRouletteView[1];
        String[] secondReward = new String[1];
        CountDownLatch announced = new CountDownLatch(2);
        AtomicInteger announcements = new AtomicInteger();
        // At faster scales, the obsolete first hide fires after the second
        // reveal. Observe beyond it, before the second reward's own hide.
        long observeAfter = Math.max(100L, (long) Math.ceil(2200 * (1f - animatorScale)) + 100L);
        CountDownLatch oldHideBoundaryPassed = new CountDownLatch(1);
        int[] observedVisibility = {-1};
        String[] observedReward = new String[1];
        try {
            scenario.onActivity(activity -> {
                roulette[0] = activity.findViewById(R.id.powerup_roulette);
                secondReward[0] = activity.getString(PowerupPresentation.name(Powerups.FULL_AUTO_90));
                roulette[0].showReward(activity.getString(PowerupPresentation.name(Powerups.SHIELD_PLAYER)), () -> {
                    announcements.incrementAndGet();
                    announced.countDown();
                    roulette[0].showReward(secondReward[0], () -> {
                        announcements.incrementAndGet();
                        roulette[0].postDelayed(() -> {
                            observedVisibility[0] = roulette[0].getVisibility();
                            observedReward[0] = roulette[0].getContentDescription().toString();
                            oldHideBoundaryPassed.countDown();
                        }, observeAfter);
                        announced.countDown();
                    });
                });
            });
            assertTrue("The first reward's old hide callback cancelled the next real spin",
                    announced.await(12, TimeUnit.SECONDS));
            assertEquals(2, announcements.get());
            assertTrue("The main-thread reveal observation never ran",
                    oldHideBoundaryPassed.await(3, TimeUnit.SECONDS));
            assertEquals("An earlier reward hid the newer revealed reward", View.VISIBLE,
                    observedVisibility[0]);
            assertEquals(secondReward[0], observedReward[0]);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (roulette[0] != null) {
                    roulette[0].cancel();
                    assertEquals(View.GONE, roulette[0].getVisibility());
                }
            });
        }
    }

    private static float rouletteAnimatorScale() {
        return android.provider.Settings.Global.getFloat(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    @Test
    public void everyPickedPowerupSpeaksItsNameOnceAfterTheReveal() throws NoSuchFieldException {
        Field spinField = PowerupRouletteView.class.getDeclaredField("spin");
        spinField.setAccessible(true);
        withRecordingSpeech((activity, speech) -> {
            Globals.getInstance().mPowerupQrRequired = 2;
            PowerupRouletteView roulette = activity.findViewById(R.id.powerup_roulette);
            assertNotNull(roulette);
            assertEquals(true, get(activity, "mActivityResumed"));
            List<String> expected = new ArrayList<>();
            try {
                for (int kind = Powerups.EMP; kind <= Powerups.AIRSTRIKE; kind++) {
                    // Target another player so this isolates the picker's announcement
                    // from EMP, damage and ammunition effects on the local blaster.
                    Powerups.Grant grant = new Powerups.Grant(50000 + kind, 1, 0, kind,
                            new int[]{17}, new int[]{0},
                            SystemClock.elapsedRealtime() + Powerups.durationMillis(kind));
                    applyPowerup(activity, grant, false);
                    android.animation.ValueAnimator spin =
                            (android.animation.ValueAnimator) spinField.get(roulette);
                    if (spin != null) {
                        assertEquals("Reward was spoken before its reveal", expected, speech.queued);
                        // Complete the real animation and its real reveal callback.
                        spin.end();
                    }
                    expected.add("normal:1.0:" + activity.getString(PowerupPresentation.voice(kind)));
                    assertEquals(expected, speech.queued);
                    assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD,
                            (int) speech.queueModes.get(expected.size() - 1));
                    assertEquals(activity.getString(PowerupPresentation.name(kind)),
                            roulette.getContentDescription().toString());

                    applyPowerup(activity, grant, false);
                    applyPowerup(activity, new Powerups.Grant(60000 + kind, 1, 0, kind,
                            new int[]{17}, new int[]{0},
                            SystemClock.elapsedRealtime() + Powerups.durationMillis(kind)), true);
                    assertEquals("Replayed or recovered rewards must not repeat speech",
                            expected, speech.queued);
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            } finally {
                roulette.cancel();
            }
        });
    }

    @Test
    public void powerupQrPreviewUsesASmallWindowWithoutChangingRespawnScanning() {
        scenario.onActivity(activity -> {
            View preview = activity.findViewById(R.id.powerup_qr_scanner_window);
            View respawn = activity.findViewById(R.id.respawn_qr_scanner_overlay);
            View powerupButton = activity.findViewById(R.id.powerup_scan_button);
            ViewGroup.LayoutParams previewSize = preview.getLayoutParams();
            ViewGroup.LayoutParams respawnSize = respawn.getLayoutParams();
            assertTrue(previewSize.width > 0
                    && previewSize.width < activity.getResources().getDisplayMetrics().widthPixels);
            assertTrue(previewSize.height > 0
                    && previewSize.height < activity.getResources().getDisplayMetrics().heightPixels);
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, respawnSize.width);
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, respawnSize.height);

            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            Globals.getInstance().mPowerupQrRequired = 0;
            invoke(activity, "updatePowerupScanButton");
            assertEquals(View.GONE, powerupButton.getVisibility());
            assertEquals(View.GONE, preview.getVisibility());

            Globals.getInstance().mPowerupQrRequired = 4;
            invoke(activity, "updatePowerupScanButton");
            assertEquals(View.VISIBLE, powerupButton.getVisibility());
            assertEquals(View.GONE, preview.getVisibility());

            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            invoke(activity, "updatePowerupScanButton");
            assertEquals(View.GONE, powerupButton.getVisibility());
            assertEquals(View.GONE, preview.getVisibility());
        });
    }

    @Test
    public void deathClearsPowerupQrProgress() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 4;
            globals.mFullShields = 10;
            set(activity, "mUseNetwork", false);
            set(activity, "mShield", 3);
            set(activity, "mHealth", globals.mFullHealth);
            PowerupQrProgress progress = (PowerupQrProgress) get(activity, "mPowerupQrProgress");
            progress.scan("SIMPLECOIL:POWERUP:1", 4);
            progress.scan("SIMPLECOIL:POWERUP:2", 4);
            assertEquals(2, progress.scannedCount());
            applyPowerup(activity, personalPowerup(101, Powerups.SHIELD_PLAYER, 0), true);
            applyPowerup(activity, personalPowerup(102, Powerups.DOUBLE_DAMAGE, 0), true);
            applyPowerup(activity, personalPowerup(103, Powerups.INVULNERABLE, 0), true);
            assertEquals(5, get(activity, "mPowerupShieldBonus"));

            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            assertEquals(0, progress.scannedCount());
            assertEquals(0, get(activity, "mPowerupShieldBonus"));
            for (long until : (long[]) get(activity, "mPowerupUntil")) assertEquals(0, until);
            assertTrue(((Map<?, ?>) get(activity, "mPowerupDoubleDamage")).isEmpty());
            assertEquals(View.GONE, activity.findViewById(R.id.powerup_scan_button).getVisibility());
            CountDownTimer spawn = (CountDownTimer) get(activity, "mSpawnTimer");
            spawn.onFinish();
            assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.powerup_scan_button).getVisibility());
        });
    }

    @Test
    public void powerupShieldBufferLastsFifteenSecondsWithoutErasingNormalShieldDamage() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 4;
            globals.mFullShields = 10;
            set(activity, "mShield", 10);
            set(activity, "mHealth", globals.mFullHealth);

            Powerups.Grant grant = personalPowerup(201, Powerups.SHIELD_PLAYER, 0);
            applyPowerup(activity, grant, true);

            assertEquals(5, get(activity, "mPowerupShieldBonus"));
            assertEquals(0, get(activity, "mRespawnShieldBonus"));
            assertEquals(grant.expiresAt, ((long[]) get(activity, "mPowerupUntil"))[Powerups.SHIELD_PLAYER]);
            ProgressBar shieldBar = activity.findViewById(R.id.shield_pb);
            assertEquals(15, shieldBar.getMax());
            assertEquals(15, shieldBar.getProgress());
            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -3);
            assertEquals(2, get(activity, "mPowerupShieldBonus"));
            assertEquals(10, get(activity, "mShield"));
            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -7);
            assertEquals(0, get(activity, "mPowerupShieldBonus"));
            assertEquals(5, get(activity, "mShield"));
            expirePowerup(activity, Powerups.SHIELD_PLAYER);
            assertEquals("Expired boost retained an oversized health bar", 10, shieldBar.getMax());
            assertEquals("Normal shield damage was undone when the buffer expired", 5, get(activity, "mShield"));
            assertEquals(5, shieldBar.getProgress());
        });
    }

    @Test
    public void powerupInvulnerabilityRejectsHitsUntilItsFifteenSecondExpiry() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPowerupQrRequired = 2;
            set(activity, "mShield", 0);
            set(activity, "mHealth", 20);
            applyPowerup(activity, personalPowerup(301, Powerups.INVULNERABLE, 0), true);
            telemetry(activity, 17, 1, 0, 0);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            expirePowerup(activity, Powerups.INVULNERABLE);
            telemetry(activity, 17, 2, 0, 0);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void powerupAutoNinetyOverridesTournamentAndRestoresNormalModeAndMagazine() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            boolean previousTournament = globals.mTournamentMode;
            android.content.SharedPreferences preferences = (android.content.SharedPreferences) get(activity, "sharedPreferences");
            String key = (String) get(activity, "PREF_SHOT_MODE");
            boolean hadPrevious = preferences.contains(key);
            int previousPreference = preferences.getInt(key, Globals.SHOT_MODE_SINGLE);
            try {
                globals.mPowerupQrRequired = 2;
                globals.mTournamentMode = true;
                globals.mFullReload = 30;
                set(activity, "mCurrentShotMode", Globals.SHOT_MODE_SINGLE);
                preferences.edit().putInt(key, Globals.SHOT_MODE_SINGLE).commit();
                applyPowerup(activity, personalPowerup(401, Powerups.FULL_AUTO_90, 0), true);
                assertEquals(Globals.SHOT_MODE_FULL_AUTO, get(activity, "mCurrentShotMode"));
                assertEquals(90, invokeResult(activity, "activeMagazineSize"));
                assertEquals("A temporary reward overwrote the saved firing preference",
                        Globals.SHOT_MODE_SINGLE, preferences.getInt(key, -1));
                assertEquals(90, bluetooth.writes.get(bluetooth.writes.size() - 1)[6] & 255);
                set(activity, "mReloading", 1);
                invoke(activity, "finishReload");
                assertEquals(90, bluetooth.writes.get(bluetooth.writes.size() - 1)[6] & 255);
                set(activity, "mLastShotCount", (byte) 90);
                expirePowerup(activity, Powerups.FULL_AUTO_90);
                assertEquals(Globals.SHOT_MODE_SINGLE, get(activity, "mCurrentShotMode"));
                assertEquals(30, invokeResult(activity, "activeMagazineSize"));
                assertEquals(30, bluetooth.writes.get(bluetooth.writes.size() - 1)[6] & 255);
                assertEquals(Globals.SHOT_MODE_SINGLE, preferences.getInt(key, -1));
            } finally {
                globals.mTournamentMode = previousTournament;
                android.content.SharedPreferences.Editor editor = preferences.edit();
                if (hadPrevious) editor.putInt(key, previousPreference);
                else editor.remove(key);
                editor.commit();
            }
        });
    }

    @Test
    public void powerupEmpLocksBothGunsForTenSecondsAndRestoresOnlyTheirRemainingAmmo() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPowerupQrRequired = 2;
            RecordingBluetoothService second = new RecordingBluetoothService();
            Object previousSecond = get(activity, "mSecondaryBluetoothLeService");
            Object previousCommand = get(activity, "mSecondaryCommandCharacteristic");
            try {
                set(activity, "mSecondaryBluetoothLeService", second);
                set(activity, "mSecondaryCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
                set(activity, "mSecondaryReloading", 0);
                set(activity, "mLastShotCount", (byte) 17);
                set(activity, "mSecondaryLastShotCount", (byte) 11);
                applyPowerup(activity, personalPowerup(501, Powerups.EMP, 0), true);
                assertEquals(3, get(activity, "mReloading"));
                assertEquals(3, get(activity, "mSecondaryReloading"));
                assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
                assertEquals(2, second.writes.get(second.writes.size() - 1)[2]);
                int firstWrites = bluetooth.writes.size(), secondWrites = second.writes.size();
                invoke(activity, "finishReload");
                invoke(activity, "finishSecondaryReload");
                assertEquals(firstWrites, bluetooth.writes.size());
                assertEquals(secondWrites, second.writes.size());
                expirePowerup(activity, Powerups.EMP);
                byte[] firstAmmo = bluetooth.writes.get(bluetooth.writes.size() - 1);
                byte[] secondAmmo = second.writes.get(second.writes.size() - 1);
                assertEquals(4, firstAmmo[2]);
                assertEquals(4, secondAmmo[2]);
                assertEquals(17, firstAmmo[6] & 255);
                assertEquals(11, secondAmmo[6] & 255);
                assertEquals(View.GONE, ((View) get(activity, "mPowerupEmpOverlay")).getVisibility());
            } finally {
                set(activity, "mSecondaryBluetoothLeService", previousSecond);
                set(activity, "mSecondaryCommandCharacteristic", previousCommand);
                second.onDestroy();
            }
        });
    }

    @Test
    public void simultaneousEmpAndNinetyExpiryRestoresBothGunsWithoutOverwritingSavedAmmo() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPowerupQrRequired = 2;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                applyPowerup(activity, personalPowerup(511, Powerups.FULL_AUTO_90, 0), true);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                set(activity, "mLastShotCount", (byte) 47);
                set(activity, "mSecondaryLastShotCount", (byte) 11);
                applyPowerup(activity, personalPowerup(512, Powerups.EMP, 0), true);
                powerupAmmoTelemetry(activity, false, 0);
                powerupAmmoTelemetry(activity, true, 0);
                assertEquals(0, ((byte) get(activity, "mLastShotCount")) & 255);
                assertEquals(0, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
                int firstWrites = bluetooth.writes.size(), secondWrites = guns.second.writes.size();
                ((long[]) get(activity, "mPowerupUntil"))[Powerups.FULL_AUTO_90] = SystemClock.elapsedRealtime() - 1;
                expirePowerup(activity, Powerups.EMP);
                assertEquals("90-round cleanup replaced the EMP restoration", firstWrites + 1, bluetooth.writes.size());
                assertEquals(secondWrites + 1, guns.second.writes.size());
                byte[] primary = (byte[]) get(activity, "mPendingReloadCommand");
                byte[] secondary = (byte[]) get(activity, "mSecondaryPendingReloadCommand");
                assertEquals(4, primary[2]);
                assertEquals(4, secondary[2]);
                assertEquals("Saved ammo must also respect the expired magazine capacity", 30, primary[6] & 255);
                assertEquals(11, secondary[6] & 255);
                assertTrue(Arrays.equals(primary, bluetooth.writes.get(firstWrites)));
                assertTrue(Arrays.equals(secondary, guns.second.writes.get(secondWrites)));
                assertEquals(2, get(activity, "mReloading"));
                assertEquals(2, get(activity, "mSecondaryReloading"));
                completePowerupWrite(activity, false, primary, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(30, ((byte) get(activity, "mLastShotCount")) & 255);
                assertEquals(11, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
            }
        });
    }

    @Test
    public void ninetyExpiryPreservesBothOrdinaryReloadTimersAndRefillsToNormalCapacity() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPowerupQrRequired = 2;
            Globals.getInstance().mReloadTime = 10000;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                applyPowerup(activity, personalPowerup(521, Powerups.FULL_AUTO_90, 0), true);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                set(activity, "mLastShotCount", (byte) 5);
                set(activity, "mSecondaryLastShotCount", (byte) 7);
                invoke(activity, "startReload");
                invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_SUCCESS);
                CountDownTimer firstTimer = (CountDownTimer) get(activity, "mReloadTimer");
                CountDownTimer secondTimer = (CountDownTimer) get(activity, "mSecondaryReloadTimer");
                assertNotNull(firstTimer);
                assertNotNull(secondTimer);
                powerupAmmoTelemetry(activity, false, 0);
                powerupAmmoTelemetry(activity, true, 0);
                int firstWrites = bluetooth.writes.size(), secondWrites = guns.second.writes.size();
                expirePowerup(activity, Powerups.FULL_AUTO_90);
                assertEquals("Expiry finished an ordinary reload early", firstWrites, bluetooth.writes.size());
                assertEquals(secondWrites, guns.second.writes.size());
                assertEquals(1, get(activity, "mReloading"));
                assertEquals(1, get(activity, "mSecondaryReloading"));
                assertSame(firstTimer, get(activity, "mReloadTimer"));
                assertSame(secondTimer, get(activity, "mSecondaryReloadTimer"));
                firstTimer.cancel();
                secondTimer.cancel();
                firstTimer.onFinish();
                secondTimer.onFinish();
                assertNull(get(activity, "mReloadTimer"));
                assertNull(get(activity, "mSecondaryReloadTimer"));
                byte[] primary = (byte[]) get(activity, "mPendingReloadCommand");
                byte[] secondary = (byte[]) get(activity, "mSecondaryPendingReloadCommand");
                assertEquals(30, primary[6] & 255);
                assertEquals(30, secondary[6] & 255);
                completePowerupWrite(activity, false, primary, BluetoothGatt.GATT_SUCCESS);
                completePowerupWrite(activity, true, secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(30, ((byte) get(activity, "mLastShotCount")) & 255);
                assertEquals(30, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
            }
        });
    }

    @Test
    public void ninetyExpiryCapsBothPendingRefillsAndIgnoresTheirOldAcknowledgements() {
        scenario.onActivity(activity -> assertExpiredNinetyRefillAcknowledgements(activity, true));
    }

    @Test
    public void ninetyRefillAcknowledgementAfterDeadlineCapsBothGunsBeforeTheExpiryTick() {
        scenario.onActivity(activity -> assertExpiredNinetyRefillAcknowledgements(activity, false));
    }

    private void assertExpiredNinetyRefillAcknowledgements(FullscreenActivity activity, boolean runExpiryFirst) {
        Globals.getInstance().mPowerupQrRequired = 2;
        try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
            applyPowerup(activity, personalPowerup(531, Powerups.FULL_AUTO_90, 0), true);
            byte[] oldPrimary = (byte[]) get(activity, "mPendingReloadCommand");
            byte[] oldSecondary = (byte[]) get(activity, "mSecondaryPendingReloadCommand");
            assertEquals(90, oldPrimary[6] & 255);
            assertEquals(90, oldSecondary[6] & 255);
            // Disabled telemetry may arrive while the 90-round refill write is in flight.
            powerupAmmoTelemetry(activity, false, 0);
            powerupAmmoTelemetry(activity, true, 0);
            int firstWrites = bluetooth.writes.size(), secondWrites = guns.second.writes.size();
            ((long[]) get(activity, "mPowerupUntil"))[Powerups.FULL_AUTO_90] = SystemClock.elapsedRealtime() - 1;
            if (runExpiryFirst) expirePowerup(activity, Powerups.FULL_AUTO_90);
            completePowerupWrite(activity, false, oldPrimary, BluetoothGatt.GATT_SUCCESS);
            completePowerupWrite(activity, true, oldSecondary, BluetoothGatt.GATT_SUCCESS);
            byte[] primary = (byte[]) get(activity, "mPendingReloadCommand");
            byte[] secondary = (byte[]) get(activity, "mSecondaryPendingReloadCommand");
            assertNotNull(primary);
            assertNotNull(secondary);
            assertEquals(30, primary[6] & 255);
            assertEquals(30, secondary[6] & 255);
            assertFalse(Arrays.equals(oldPrimary, primary));
            assertFalse(Arrays.equals(oldSecondary, secondary));
            assertEquals(firstWrites + 1, bluetooth.writes.size());
            assertEquals(secondWrites + 1, guns.second.writes.size());
            assertEquals(2, get(activity, "mReloading"));
            assertEquals(2, get(activity, "mSecondaryReloading"));
            assertEquals(0, ((byte) get(activity, "mLastShotCount")) & 255);
            assertEquals(0, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
            // Duplicate old ACKs and the later expiry tick cannot replace the capped refill.
            completePowerupWrite(activity, false, oldPrimary, BluetoothGatt.GATT_SUCCESS);
            completePowerupWrite(activity, true, oldSecondary, BluetoothGatt.GATT_SUCCESS);
            expirePowerup(activity, Powerups.FULL_AUTO_90);
            assertSame(primary, get(activity, "mPendingReloadCommand"));
            assertSame(secondary, get(activity, "mSecondaryPendingReloadCommand"));
            assertEquals(firstWrites + 1, bluetooth.writes.size());
            assertEquals(secondWrites + 1, guns.second.writes.size());
            completePowerupWrite(activity, false, primary, BluetoothGatt.GATT_SUCCESS);
            completePowerupWrite(activity, true, secondary, BluetoothGatt.GATT_SUCCESS);
            assertEquals(0, get(activity, "mReloading"));
            assertEquals(0, get(activity, "mSecondaryReloading"));
            assertEquals(30, ((byte) get(activity, "mLastShotCount")) & 255);
            assertEquals(30, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
        }
    }

    @Test
    public void empZeroAmmoTelemetryAfterFailedDisableWritesDoesNotReportShotsOrStartReloads() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            boolean previousReloadOnEmpty = globals.mReloadOnEmpty;
            globals.mPowerupQrRequired = 2;
            globals.mReloadOnEmpty = true;
            tcp.dedicated = true;
            try (PowerupDualGunFixture guns = new PowerupDualGunFixture(activity)) {
                set(activity, "mLastShotCount", (byte) 17);
                set(activity, "mSecondaryLastShotCount", (byte) 11);
                applyPowerup(activity, personalPowerup(541, Powerups.EMP, 0), true);
                completePowerupWrite(activity, false, (byte[]) get(activity, "mPendingReloadCommand"),
                        BluetoothGatt.GATT_FAILURE);
                completePowerupWrite(activity, true, (byte[]) get(activity, "mSecondaryPendingReloadCommand"),
                        BluetoothGatt.GATT_FAILURE);
                assertEquals(0, get(activity, "mReloading"));
                assertEquals(0, get(activity, "mSecondaryReloading"));
                int shotsBefore = tcp.hostedShots;
                int firstWrites = bluetooth.writes.size(), secondWrites = guns.second.writes.size();
                int udpMessages = udp.messages.size();
                set(activity, "mLastShotFired", 0L);
                powerupAmmoTelemetry(activity, false, 0);
                powerupAmmoTelemetry(activity, true, 0);
                assertEquals("EMP's forced zero was counted as fired shots", shotsBefore, tcp.hostedShots);
                assertEquals(udpMessages, udp.messages.size());
                assertEquals(firstWrites, bluetooth.writes.size());
                assertEquals(secondWrites, guns.second.writes.size());
                assertEquals(0, get(activity, "mReloading"));
                assertEquals(0, get(activity, "mSecondaryReloading"));
                assertEquals(0, ((byte) get(activity, "mLastShotCount")) & 255);
                assertEquals(0, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
                assertEquals(17, get(activity, "mEmpSavedPrimaryAmmo"));
                assertEquals(11, get(activity, "mEmpSavedSecondaryAmmo"));
                // A failed disable can leave nonzero telemetry just before the
                // deadline. Its later forced zero is still not a real shot until
                // the expiry tick begins the saved-ammo restoration.
                powerupAmmoTelemetry(activity, false, 17);
                powerupAmmoTelemetry(activity, true, 11);
                ((long[]) get(activity, "mPowerupUntil"))[Powerups.EMP] = SystemClock.elapsedRealtime() - 1;
                set(activity, "mLastShotFired", 0L);
                powerupAmmoTelemetry(activity, false, 0);
                powerupAmmoTelemetry(activity, true, 0);
                assertEquals("The EMP deadline preceded its restoration tick", shotsBefore, tcp.hostedShots);
                assertEquals(udpMessages, udp.messages.size());
                assertEquals(firstWrites, bluetooth.writes.size());
                assertEquals(secondWrites, guns.second.writes.size());
                assertEquals(0, get(activity, "mReloading"));
                assertEquals(0, get(activity, "mSecondaryReloading"));
                assertEquals(0, ((byte) get(activity, "mLastShotCount")) & 255);
                assertEquals(0, ((byte) get(activity, "mSecondaryLastShotCount")) & 255);
            } finally {
                globals.mReloadOnEmpty = previousReloadOnEmpty;
            }
        });
    }

    @Test
    public void powerupInstantTeamEffectsApplyOnceAndNeverReplayFromSnapshots() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            int previousFullHealth = globals.mFullHealth;
            try {
                globals.mPowerupQrRequired = 2;
                globals.mFullHealth = 8;
                set(activity, "mHealth", 2);
                Powerups.Grant heal = targetedPowerup(601, 2, 0, Powerups.HEAL_TEAM, 0);
                applyPowerup(activity, heal, false);
                assertEquals(8, get(activity, "mHealth"));
                set(activity, "mHealth", 3);
                applyPowerup(activity, heal, false);
                applyPowerup(activity, targetedPowerup(602, 2, 0, Powerups.HEAL_TEAM, 0), true);
                assertEquals(3, get(activity, "mHealth"));
                set(activity, "mHealth", 8);
                Powerups.Grant damage = targetedPowerup(603, 17, 0, Powerups.DAMAGE_TEAM, 0);
                applyPowerup(activity, damage, false);
                assertEquals(6, get(activity, "mHealth"));
                applyPowerup(activity, damage, false);
                applyPowerup(activity, targetedPowerup(604, 17, 0, Powerups.DAMAGE_TEAM, 0), true);
                assertEquals(6, get(activity, "mHealth"));
            } finally {
                globals.mFullHealth = previousFullHealth;
            }
        });
    }

    @Test
    public void powerupTeamDamageUsesNormalEliminationAndLifeAccounting() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            set(activity, "mHealth", 1);
            set(activity, "mShield", 0);
            set(activity, "mEliminationCount", 0);
            applyPowerup(activity, targetedPowerup(701, 17, 0, Powerups.DAMAGE_TEAM, 0), false);
            assertEquals(Globals.GAME_STATE_ELIMINATED, globals.mGameState);
            assertEquals(1, get(activity, "mEliminationCount"));
            assertEquals(1, udp.peerEliminationPublishes);
            assertEquals(Byte.valueOf((byte) 17), udp.lastPeerEliminationRecipient);
            assertNotNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void airstrikeEliminatesStationaryPlayersThroughAllShieldsDespiteEitherGunTrigger() {
        withRecordingSpeech((activity, speech) -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            globals.mFullShields = 10;
            set(activity, "mHealth", 20);
            set(activity, "mShield", 10);
            set(activity, "mRespawnShieldBonus", 5);
            set(activity, "mPowerupShieldBonus", 5);
            try (PowerupDualGunFixture fixture = new PowerupDualGunFixture(activity)) {
                primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
                inactivityTrigger(activity, false);
                inactivityTrigger(activity, true);
                assertTrue(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
                applyPowerup(activity, targetedPowerup(2001, 17, 0, Powerups.AIRSTRIKE, 0), false);
                assertEquals(Globals.GAME_STATE_ELIMINATED, globals.mGameState);
                assertTrue((int) get(activity, "mHealth") <= 0);
                assertEquals(1, get(activity, "mEliminationCount"));
                assertEquals(1, udp.peerEliminationPublishes);
                assertEquals(Byte.valueOf((byte) 17), udp.lastPeerEliminationRecipient);
                assertNotNull(get(activity, "mSpawnTimer"));
                assertTrue(speech.queued.contains("normal:1.0:"
                        + activity.getString(R.string.powerup_airstrike_eliminated_voice)));
                assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
                assertFalse((boolean) get(activity, "mInactivityReminderActive"));
            }
        });
    }

    @Test
    public void airstrikeRequiresTheFullStationaryObservationPeriod() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS - 5000L);
            assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            applyPowerup(activity, targetedPowerup(2002, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeSparesAPlayerWhoseLatestReliableFixShowsMovement() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            receiveInactivityGps(activity, inactivityGpsFix(47.0002, 1f, SystemClock.elapsedRealtime()));
            assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            applyPowerup(activity, targetedPowerup(2003, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeNeverTreatsPoorNetworkMissingOrStaleGpsAsStationary() {
        scenario.onActivity(activity -> {
            for (int quality = 0; quality < 7; quality++) {
                primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
                switch (quality) {
                    case 0: set(activity, "mLastGpsHasAccuracy", false); break;
                    case 1: set(activity, "mLastGpsAccuracyMeters", 6f); break;
                    case 2: set(activity, "mLastGpsProvider", android.location.LocationManager.NETWORK_PROVIDER); break;
                    case 3: set(activity, "mLastGpsHasMeasurementTime", false); break;
                    case 4: set(activity, "mLastGpsFixElapsedMs", SystemClock.elapsedRealtime()
                            - InactivityGpsPolicy.MAX_FIX_AGE_MS - 1L); break;
                    case 5: Globals.getInstance().mUseGPS = false; break;
                    default: set(activity, "mLastGpsAccuracyMeters", Float.NaN); break;
                }
                applyPowerup(activity, targetedPowerup(2010 + quality, 17, 0, Powerups.AIRSTRIKE, 0), false);
                assertEquals("Unusable GPS case " + quality + " caused an elimination", 20,
                        get(activity, "mHealth"));
                assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
                assertEquals(0, udp.peerEliminationPublishes);
            }
        });
    }

    @Test
    public void newerInvalidGpsClearsAirstrikeEvidenceWithoutReplacingTheLastGoodCoordinates() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            long previous = (long) get(activity, "mLastGpsFixElapsedMs");
            receiveInactivityGps(activity, inactivityGpsFix(0.0, 1f, previous - 1L)
                    .putExtra(NetMsg.INTENT_LONGITUDE, 0.0));
            assertTrue("An older reordered invalid fix discarded newer stationary evidence",
                    stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            receiveInactivityGps(activity, inactivityGpsFix(0.0, 1f, SystemClock.elapsedRealtime())
                    .putExtra(NetMsg.INTENT_LONGITUDE, 0.0));
            assertEquals(47.0, (double) get(activity, "mLastGpsLatitude"), 0.0);
            assertEquals(-122.0, (double) get(activity, "mLastGpsLongitude"), 0.0);
            assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            applyPowerup(activity, targetedPowerup(2018, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            // Good GPS returning starts new evidence; the previous stationary
            // period cannot turn one fresh fix into an immediate elimination.
            receiveInactivityGps(activity, inactivityGpsFix(47.0, 1f, SystemClock.elapsedRealtime()));
            assertFalse(stationaryGpsTracker(activity).isStationary(SystemClock.elapsedRealtime()));
            applyPowerup(activity, targetedPowerup(2019, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeRejectsSelfAndAlliesButSameIdRangeIsAnEnemyInFreeForAll() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            applyPowerup(activity, targetedPowerup(2020, 1, 0, Powerups.AIRSTRIKE, 0), false);
            applyPowerup(activity, targetedPowerup(2021, 2, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
            Globals.getInstance().mGameMode = Globals.GAME_MODE_FFA;
            applyPowerup(activity, targetedPowerup(2022, 2, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(Byte.valueOf((byte) 2), udp.lastPeerEliminationRecipient);
        });
    }

    @Test
    public void airstrikeCannotCrossTheTargetsDeathGenerationOrHitAnAlreadyDeadPlayer() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            set(activity, "mEliminationCount", 1);
            applyPowerup(activity, targetedPowerup(2030, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            set(activity, "mHealth", 0);
            applyPowerup(activity, targetedPowerup(2031, 17, 0, Powerups.AIRSTRIKE, 1), false);
            assertEquals(0, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mEliminationCount"));
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void awardedAirstrikeRemainsValidWhenItsCasterDiesBeforeLiveDelivery() {
        scenario.onActivity(activity -> {
            com.simplecoil.protocol.RoundRoster roster = airstrikeRoster();
            roster.seat(17).deaths = 1;
            Globals.getInstance().mRoundRoster = roster;
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            applyPowerup(activity, targetedPowerup(2040, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(1, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeSnapshotsNeverKillAndLiveRetriesCannotApplyTwice() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            Powerups.Grant recovered = targetedPowerup(2050, 17, 0, Powerups.AIRSTRIKE, 0);
            applyPowerup(activity, recovered, true);
            applyPowerup(activity, recovered, false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
            Powerups.Grant live = targetedPowerup(2051, 17, 0, Powerups.AIRSTRIKE, 0);
            applyPowerup(activity, live, false);
            assertEquals(1, udp.peerEliminationPublishes);
            // Model a later respawn. Even a changed retry target generation
            // must not permit the same grant ID to be applied for a second life.
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mHealth", 20);
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            applyPowerup(activity, targetedPowerup(2051, 17, 0, Powerups.AIRSTRIKE, 1), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mEliminationCount"));
            assertEquals(1, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeHonorsInvulnerabilityUntilItExpires() {
        scenario.onActivity(activity -> {
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            ((long[]) get(activity, "mPowerupUntil"))[Powerups.INVULNERABLE]
                    = SystemClock.elapsedRealtime() + Powerups.BUFF_MS;
            applyPowerup(activity, targetedPowerup(2060, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
            ((long[]) get(activity, "mPowerupUntil"))[Powerups.INVULNERABLE] = 0;
            applyPowerup(activity, targetedPowerup(2061, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(1, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeHonorsOriginalZombieProtectionWithoutDrainingItsAmmo() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mInfectionMode = true;
            globals.setPlayerInfected((byte) 1, true);
            set(activity, "mScore", 0);
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            assertEquals(true, invokeResult(activity, "isOriginalZombieProtected"));
            applyPowerup(activity, targetedPowerup(2070, 17, 0, Powerups.AIRSTRIKE, 0), false);
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(30, ((byte) get(activity, "mLastShotCount")) & 255);
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    @Test
    public void airstrikeRejectsExpiredAndWrongRoundOrReplacedCasterIntentsBeforeDeduplication() {
        scenario.onActivity(activity -> {
            com.simplecoil.protocol.RoundRoster roster = airstrikeRoster();
            Globals.getInstance().mRoundRoster = roster;
            set(activity, "mActivePeerRoundToken", roster.token);
            primeAirstrikeStationarity(activity, StationaryGpsTracker.STATIONARY_MS);
            Powerups.Grant expired = new Powerups.Grant(2080, 17, 0, Powerups.AIRSTRIKE,
                    new int[]{1}, new int[]{0}, SystemClock.elapsedRealtime() - 1L, 2);
            applyPowerup(activity, expired, false);
            assertFalse(((java.util.Set<?>) get(activity, "mSeenPowerupGrants")).contains(expired.id));
            Powerups.Grant live = targetedPowerup(2081, 17, 0, Powerups.AIRSTRIKE, 0);
            Intent wrongRound = TcpServer.powerupIntent(live, "cccccccc-cccc-cccc-cccc-cccccccccccc", false);
            invoke(activity, "receivePowerup", new Class<?>[]{Intent.class}, wrongRound);
            assertFalse(((java.util.Set<?>) get(activity, "mSeenPowerupGrants")).contains(live.id));
            Intent replacedSource = TcpServer.powerupIntent(live, roster.token, false);
            com.simplecoil.protocol.RoundRoster.Member replacement = new com.simplecoil.protocol.RoundRoster.Member(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd", 17, 2, 2);
            roster.restore(replacement);
            invoke(activity, "receivePowerup", new Class<?>[]{Intent.class}, replacedSource);
            assertFalse(((java.util.Set<?>) get(activity, "mSeenPowerupGrants")).contains(live.id));
            assertEquals(20, get(activity, "mHealth"));
            assertEquals(0, udp.peerEliminationPublishes);
        });
    }

    private static StationaryGpsTracker stationaryGpsTracker(FullscreenActivity activity) {
        return (StationaryGpsTracker) get(activity, "mStationaryGpsTracker");
    }

    private static void primeAirstrikeStationarity(FullscreenActivity activity, long observationMs) {
        Globals.getInstance().mPowerupQrRequired = 2;
        long now = SystemClock.elapsedRealtime();
        long latestMeasurement = now - 1L;
        set(activity, "mLastGpsHasMeasurementTime", false);
        startReliableGpsInactivity(activity, 1f, now - 2L);
        StationaryGpsTracker tracker = stationaryGpsTracker(activity);
        tracker.reset();
        for (long sample = latestMeasurement - observationMs; sample < latestMeasurement; sample += 5000L)
            tracker.observe(true, -122.0, 47.0, 1f, sample, sample);
        tracker.observe(true, -122.0, 47.0, 1f, latestMeasurement, latestMeasurement);
        receiveInactivityGps(activity, inactivityGpsFix(47.0, 1f, latestMeasurement));
    }

    private static com.simplecoil.protocol.RoundRoster airstrikeRoster() {
        com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        roster.restore(new com.simplecoil.protocol.RoundRoster.Member(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 1, 1, 1));
        roster.restore(new com.simplecoil.protocol.RoundRoster.Member(
                "cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 1));
        return roster;
    }

    @Test
    public void powerupOldTargetLifeAndOldDamageSourceLifeCannotSurviveRespawning() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            globals.mFullShields = 10;
            set(activity, "mEliminationCount", 1);
            applyPowerup(activity, targetedPowerup(801, 2, 0, Powerups.SHIELD_TEAM, 0), true);
            assertEquals(0, get(activity, "mPowerupShieldBonus"));
            applyPowerup(activity, targetedPowerup(802, 2, 0, Powerups.SHIELD_TEAM, 1), true);
            assertEquals(5, get(activity, "mPowerupShieldBonus"));
            com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            com.simplecoil.protocol.RoundRoster.Member enemy = new com.simplecoil.protocol.RoundRoster.Member(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 17, 2, 1);
            enemy.deaths = 1;
            roster.restore(enemy);
            globals.mRoundRoster = roster;
            applyPowerup(activity, new Powerups.Grant(803, 17, 0, Powerups.DOUBLE_DAMAGE,
                    new int[]{17}, new int[]{0}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS), true);
            assertEquals(-5, powerupDamageFrom(activity, (byte) 17));
            applyPowerup(activity, new Powerups.Grant(804, 17, 1, Powerups.DOUBLE_DAMAGE,
                    new int[]{17}, new int[]{1}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS), true);
            assertEquals(-10, powerupDamageFrom(activity, (byte) 17));
            enemy.deaths = 2;
            assertEquals("A respawned source retained doubled hit damage", -5,
                    powerupDamageFrom(activity, (byte) 17));
            applyPowerup(activity, new Powerups.Grant(805, 17, 2, Powerups.DOUBLE_DAMAGE,
                    new int[]{17}, new int[]{2}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS), true);
            assertEquals(-10, powerupDamageFrom(activity, (byte) 17));
            com.simplecoil.protocol.RoundRoster.Member replacement = new com.simplecoil.protocol.RoundRoster.Member(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 2);
            replacement.deaths = 2;
            roster.restore(replacement);
            assertEquals("A replacement occupant inherited the previous identity's damage boost", -5,
                    powerupDamageFrom(activity, (byte) 17));
        });
    }

    @Test
    public void powerupDoubleDamageSurvivesSameIdentityReconnectButNotSeatReplacement() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            String identity = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
            com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
            com.simplecoil.protocol.RoundRoster.Member enemy = new com.simplecoil.protocol.RoundRoster.Member(
                    identity, 17, 2, 1);
            enemy.deaths = 2;
            roster.restore(enemy);
            globals.mRoundRoster = roster;
            applyPowerup(activity, new Powerups.Grant(1101, 17, 2, Powerups.DOUBLE_DAMAGE,
                    new int[]{17}, new int[]{2}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS), true);
            assertEquals(-10, powerupDamageFrom(activity, (byte) 17));

            com.simplecoil.protocol.RoundRoster reconnectedRoster = new com.simplecoil.protocol.RoundRoster(token);
            com.simplecoil.protocol.RoundRoster.Member reconnected = new com.simplecoil.protocol.RoundRoster.Member(
                    identity, 17, 2, 2);
            reconnected.deaths = 2;
            reconnectedRoster.restore(reconnected);
            globals.mRoundRoster = reconnectedRoster;
            assertEquals("A same-person reconnect lost its still-active damage boost", -10,
                    powerupDamageFrom(activity, (byte) 17));

            com.simplecoil.protocol.RoundRoster.Member replacement = new com.simplecoil.protocol.RoundRoster.Member(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 3);
            replacement.deaths = 2;
            reconnectedRoster.restore(replacement);
            assertEquals("A different person inherited the prior occupant's damage boost", -5,
                    powerupDamageFrom(activity, (byte) 17));
        });
    }

    @Test
    public void powerupIntentCapturedBeforeSourceReplacementIsRejectedWithoutConsumingGrant() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
            com.simplecoil.protocol.RoundRoster.Member enemy = new com.simplecoil.protocol.RoundRoster.Member(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 17, 2, 1);
            roster.restore(enemy);
            globals.mRoundRoster = roster;
            set(activity, "mActivePeerRoundToken", token);
            Powerups.Grant grant = new Powerups.Grant(1201, 17, 0, Powerups.DOUBLE_DAMAGE,
                    new int[]{17}, new int[]{0}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS, 2);
            Intent captured = TcpServer.powerupIntent(grant, token, false);
            assertEquals("The service must capture the grant's original source identity", enemy.identity,
                    captured.getStringExtra(NetMsg.INTENT_POWERUP_SOURCE_IDENTITY));

            com.simplecoil.protocol.RoundRoster.Member replacement = new com.simplecoil.protocol.RoundRoster.Member(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 2);
            roster.restore(replacement);
            invoke(activity, "receivePowerup", new Class<?>[]{Intent.class}, captured);

            assertFalse(((java.util.Set<?>) get(activity, "mSeenPowerupGrants")).contains(grant.id));
            assertFalse(((Map<?, ?>) get(activity, "mPowerupDoubleDamage")).containsKey(17));
            assertFalse(((Map<?, ?>) get(activity, "mPowerupDoubleDamageOwners")).containsKey(17));
            assertEquals(-5, powerupDamageFrom(activity, (byte) 17));
        });
    }

    @Test
    public void powerupGrantIsDeferredDuringCountdownAndAcceptedOnlyOnceAfterPlayStarts() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mPowerupQrRequired = 2;
            globals.mFullShields = 10;
            globals.mGameState = Globals.GAME_STATE_ELIMINATED;
            set(activity, "mUseNetwork", false);
            set(activity, "mStartGameTimer", true);
            set(activity, "mHasSynchronizedStart", false);
            set(activity, "mLocalGameStartAt", SystemClock.elapsedRealtime() - 1);
            Powerups.Grant grant = personalPowerup(901, Powerups.SHIELD_PLAYER, 0);
            assertEquals(false, invokeResult(activity, "applyPowerupGrant",
                    new Class<?>[]{Powerups.Grant.class, boolean.class}, grant, true));
            assertFalse(((java.util.Set<?>) get(activity, "mSeenPowerupGrants")).contains(grant.id));
            assertEquals(0, get(activity, "mPowerupShieldBonus"));

            invoke(activity, "finishSpawn", new Class<?>[]{boolean.class}, false);

            assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
            assertEquals(false, get(activity, "mStartGameTimer"));
            assertEquals(true, invokeResult(activity, "applyPowerupGrant",
                    new Class<?>[]{Powerups.Grant.class, boolean.class}, grant, true));
            assertEquals(5, get(activity, "mPowerupShieldBonus"));
            invoke(activity, "takeDamage", new Class<?>[]{int.class}, -3);
            assertEquals(true, invokeResult(activity, "applyPowerupGrant",
                    new Class<?>[]{Powerups.Grant.class, boolean.class}, grant, true));
            assertEquals("A replayed grant replenished an already damaged buffer", 2,
                    get(activity, "mPowerupShieldBonus"));
        });
    }

    @Test
    public void powerupHudUsesArchivedAndLiveStandingsWithoutReusingSpentScans() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            boolean previousCtf = globals.mCaptureTheFlag;
            try {
                globals.mPowerupQrRequired = 6;
                globals.mCaptureTheFlag = false;
                // ActivityScenario recreation preserves this static counter;
                // the authoritative scan fixture below explicitly describes life zero.
                set(activity, "mEliminationCount", 0);
                assertEquals(0, invokeResult(activity, "currentDeathCount"));
                String token = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
                com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
                com.simplecoil.protocol.RoundRoster.Member own = new com.simplecoil.protocol.RoundRoster.Member(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 1, 1, 1);
                own.kills = 4;
                com.simplecoil.protocol.RoundRoster.Member archived = new com.simplecoil.protocol.RoundRoster.Member(
                        "cccccccc-cccc-cccc-cccc-cccccccccccc", 17, 2, 1);
                archived.kills = 6;
                archived.active = false;
                com.simplecoil.protocol.RoundRoster.Member enemy = new com.simplecoil.protocol.RoundRoster.Member(
                        "dddddddd-dddd-dddd-dddd-dddddddddddd", 17, 2, 2);
                enemy.kills = 8;
                roster.restore(own);
                roster.restore(archived);
                roster.restore(enemy);
                globals.mRoundRoster = roster;
                set(activity, "mActivePeerRoundToken", token);
                assertEquals(4, invokeResult(activity, "effectivePowerupRequired"));
                PowerupQrProgress progress = (PowerupQrProgress) get(activity, "mPowerupQrProgress");
                for (int code = 1; code <= 3; code++) progress.scan("SIMPLECOIL:POWERUP:" + code, 6);
                invoke(activity, "updatePowerupScanButton");
                TextView button = activity.findViewById(R.id.powerup_scan_button);
                assertEquals(activity.getString(R.string.powerup_scan_progress, 3, 4), button.getText().toString());
                progress.scan("SIMPLECOIL:POWERUP:4", 4);
                applyPowerup(activity, new Powerups.Grant(1001, 1, 0, Powerups.SHIELD_PLAYER,
                        new int[]{1}, new int[]{0}, SystemClock.elapsedRealtime() + Powerups.BUFF_MS, 4), true);
                assertEquals("Network progress is committed by host state, not a reordered grant", 4,
                        progress.progressCount(4));
                org.json.JSONObject player = new org.json.JSONObject().put("id", 1).put("life", 0)
                        .put("mask", 15).put("spent", 4).put("required", 4);
                org.json.JSONObject snapshot = new org.json.JSONObject().put(TcpServer.JSON_ROUND_TOKEN, token)
                        .put("powerupstate", new org.json.JSONObject().put("players", new org.json.JSONArray().put(player)));
                assertEquals(true, invokeResult(activity, "hostConfirmedPowerupScans",
                        new Class<?>[]{org.json.JSONObject.class}, snapshot));
                assertEquals(0, progress.progressCount(4));
                progress.scan("SIMPLECOIL:POWERUP:5", 4);
                progress.scan("SIMPLECOIL:POWERUP:6", 4);
                enemy.kills = 100;
                assertEquals(2, invokeResult(activity, "effectivePowerupRequired"));
                invoke(activity, "updatePowerupScanButton");
                assertEquals(activity.getString(R.string.powerup_scan_progress, 2, 2), button.getText().toString());
                player.put("mask", 63).put("spent", 6).put("required", 2);
                assertEquals(true, invokeResult(activity, "hostConfirmedPowerupScans",
                        new Class<?>[]{org.json.JSONObject.class}, snapshot));
                assertEquals(63, progress.scannedMask());
                assertEquals(0, progress.progressCount(2));
                assertFalse(progress.consumeReward(2));
                player.put("mask", 15).put("spent", 4).put("required", 4);
                assertEquals(false, invokeResult(activity, "hostConfirmedPowerupScans",
                        new Class<?>[]{org.json.JSONObject.class}, snapshot));
                assertEquals(63, progress.scannedMask());
                assertEquals(0, progress.progressCount(2));
            } catch (org.json.JSONException e) {
                throw new AssertionError(e);
            } finally {
                globals.mCaptureTheFlag = previousCtf;
            }
        });
    }

    private static Powerups.Grant personalPowerup(long id, int kind, int life) {
        return targetedPowerup(id, Globals.getInstance().mPlayerID & 255, life, kind, life);
    }

    private static Powerups.Grant targetedPowerup(long id, int source, int sourceLife, int kind, int targetLife) {
        return new Powerups.Grant(id, source, sourceLife, kind,
                new int[]{Globals.getInstance().mPlayerID & 255}, new int[]{targetLife},
                SystemClock.elapsedRealtime() + Powerups.durationMillis(kind));
    }

    private static void applyPowerup(FullscreenActivity activity, Powerups.Grant grant, boolean snapshot) {
        invoke(activity, "applyPowerupGrant", new Class<?>[]{Powerups.Grant.class, boolean.class}, grant, snapshot);
    }

    private static void completePowerupWrite(FullscreenActivity activity, boolean secondary, byte[] command, int status) {
        receivePowerupGatt(activity, new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_COMMAND_UUID)
                .putExtra(BluetoothLeService.EXTRA_STATUS, status)
                .putExtra(BluetoothLeService.EXTRA_DATA, command));
    }

    private static void powerupAmmoTelemetry(FullscreenActivity activity, boolean secondary, int shots) {
        byte[] packet = telemetryPacket(0, 0, 0, 0);
        packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = (byte) shots;
        receivePowerupGatt(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                .putExtra(BluetoothLeService.EXTRA_DATA, packet));
    }

    private static void receivePowerupGatt(FullscreenActivity activity, Intent intent) {
        boolean registered = (boolean) get(activity, "mGattReceiverRegistered");
        set(activity, "mGattReceiverRegistered", true);
        try {
            ((BroadcastReceiver) get(activity, "mGattUpdateReceiver")).onReceive(activity, intent);
        } finally {
            set(activity, "mGattReceiverRegistered", registered);
        }
    }

    private static void expirePowerup(FullscreenActivity activity, int kind) {
        ((long[]) get(activity, "mPowerupUntil"))[kind] = SystemClock.elapsedRealtime() - 1;
        android.os.Handler handler = (android.os.Handler) get(activity, "mPowerupHandler");
        Runnable expiry = (Runnable) get(activity, "mPowerupExpiryTick");
        handler.removeCallbacks(expiry);
        expiry.run();
        handler.removeCallbacks(expiry);
    }

    private static int powerupDamageFrom(FullscreenActivity activity, byte source) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("getPlayerDamage", byte.class);
            method.setAccessible(true);
            return (int) method.invoke(activity, source);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeResult(FullscreenActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(activity, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void bossSecondGunReloadDoesNotChangeFirstGunReloadState() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            globals.mFullReload = Globals.BOSS_RELOAD_COUNT;
            RecordingBluetoothService second = new RecordingBluetoothService();
            set(activity, "mSecondaryBluetoothLeService", second);
            set(activity, "mSecondaryCommandCharacteristic",
                    characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mSecondaryReloading", 0);
            set(activity, "mReloading", 0);
            int firstWrites = bluetooth.writes.size();

            invoke(activity, "startSecondaryReload", new Class<?>[]{int.class}, 1);
            assertEquals(firstWrites, bluetooth.writes.size());
            assertEquals(1, get(activity, "mSecondaryReloading"));
            assertEquals(0, get(activity, "mReloading"));
            assertEquals(0x02, second.writes.get(0)[2]);
            assertEquals(Globals.BOSS_PLAYER_ID, second.writes.get(0)[4]);

            BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
            boolean registered = (boolean) get(activity, "mGattReceiverRegistered");
            set(activity, "mGattReceiverRegistered", true);
            try {
                receiver.onReceive(activity, new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                        .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, 1)
                        .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_COMMAND_UUID)
                        .putExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_SUCCESS)
                        .putExtra(BluetoothLeService.EXTRA_DATA, second.writes.get(0)));
            } finally {
                set(activity, "mGattReceiverRegistered", registered);
            }
            CountDownTimer timer = (CountDownTimer) get(activity, "mSecondaryReloadTimer");
            assertTrue(timer != null);
            timer.onFinish();
            assertEquals(0x04, second.writes.get(1)[2]);
            assertEquals(0, get(activity, "mReloading"));
        });
    }

    @Test
    public void bossFirstGunReloadDoesNotFinishSecondGunReload() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            globals.mFullReload = Globals.BOSS_RELOAD_COUNT;
            RecordingBluetoothService second = new RecordingBluetoothService();
            set(activity, "mSecondaryBluetoothLeService", second);
            set(activity, "mSecondaryCommandCharacteristic",
                    characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mReloading", 1);
            set(activity, "mSecondaryReloading", 1);

            invoke(activity, "finishReload");

            assertEquals(1, bluetooth.writes.size());
            assertEquals(0x04, bluetooth.writes.get(0)[2]);
            assertEquals(0, second.writes.size());
            assertEquals(1, get(activity, "mSecondaryReloading"));
        });
    }

    @Test
    public void twoBossGunSensorsDoNotApplyTheSameHitTwice() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            set(activity, "mSecondaryBluetoothLeService", new RecordingBluetoothService());
            set(activity, "mSecondaryTelemetryCharacteristic",
                    characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(activity, "mSecondaryCommunicating", true);
            set(activity, "mSecondaryReloading", 0);
            set(activity, "mSecondaryLastShotCount", (byte) 30);
            byte[] hit = telemetryPacket(17, 1, 0, 0);
            receiveTelemetry(activity, hit);
            assertEquals(15, get(activity, "mHealth"));
            receiveTelemetry(activity, telemetryPacket(0, 0, 0, 0));
            BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
            boolean registered = (boolean) get(activity, "mGattReceiverRegistered");
            set(activity, "mGattReceiverRegistered", true);
            try {
                receiver.onReceive(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                        .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, 1)
                        .putExtra(BluetoothLeService.EXTRA_DATA, hit));
            } finally {
                set(activity, "mGattReceiverRegistered", registered);
            }
            assertEquals(15, get(activity, "mHealth"));
            assertEquals((byte) 30, get(activity, "mLastShotCount"));
            assertEquals((byte) 30, get(activity, "mSecondaryLastShotCount"));
        });
    }

    @Test
    public void teamQrAssignmentUsesRespawnCodeAndSkipsAnOccupiedPlayerId() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mPlayerID = 0;
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);
            invoke(activity, "setTeam");
            assertEquals("A fresh blaster needs the QR team-selection option", View.VISIBLE,
                    ((View) get(activity, "mTeamQrScanButton")).getVisibility());

            Map<Byte, InetAddress> originalEndpoints;
            Globals.getmTeamIPMapSemaphore();
            try {
                originalEndpoints = new HashMap<>(globals.mTeamIPMap);
                globals.mTeamIPMap.clear();
                globals.mTeamIPMap.put((byte) 17, InetAddress.getLoopbackAddress());
            } finally {
                globals.mTeamIPMapSemaphore.release();
            }
            try {
                invoke(activity, "assignTeamFromQrCode", new Class<?>[]{String.class},
                        "TEAM 2 RESPAWN");
                assertEquals("The scanned team did not skip its occupied player ID", 18,
                        globals.mPlayerID);
                assertEquals(2, globals.calcNetworkTeam(globals.mPlayerID));
                assertEquals(View.VISIBLE, ((View) get(activity, "mTeamQrScanButton")).getVisibility());
                assertEquals(18, bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
            } finally {
                Globals.getmTeamIPMapSemaphore();
                try {
                    globals.mTeamIPMap.clear();
                    globals.mTeamIPMap.putAll(originalEndpoints);
                } finally {
                    globals.mTeamIPMapSemaphore.release();
                }
            }
        });
    }

    @Test
    public void teamQrAssignmentRejectsCodesForUnavailableTeams() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mPlayerID = 1;
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);
            invoke(activity, "assignTeamFromQrCode", new Class<?>[]{String.class},
                    "SIMPLECOIL:RESPAWN:3");
            assertEquals("A two-team game accepted Team 3", 1, globals.mPlayerID);
        });
    }

    @Test
    public void grenadeDamageUsesTheMatchRuleInsteadOfOwnersGunDamage() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGrenadeDamage = -3;
            Globals.getmGrenadePairingsSemaphore();
            int previousOwner;
            try {
                previousOwner = globals.mGrenadePairings[3];
                globals.mGrenadePairings[3] = 17;
            } finally { globals.mGrenadePairingsSemaphore.release(); }
            try {
                receiveTelemetry(activity, grenadePacket(false, 0x31));
                assertEquals(17, get(activity, "mHealth"));
                assertEquals(1, get(activity, "mHitsTaken"));
            } finally {
                Globals.getmGrenadePairingsSemaphore();
                try { globals.mGrenadePairings[3] = previousOwner; }
                finally { globals.mGrenadePairingsSemaphore.release(); }
            }
        });
    }

    @Test
    public void teammateGrenadeDamagesFriendlyTeam() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            Globals.getmGrenadePairingsSemaphore();
            int previousOwner;
            try {
                previousOwner = globals.mGrenadePairings[3];
                globals.mGrenadePairings[3] = 2;
            } finally { globals.mGrenadePairingsSemaphore.release(); }
            try {
                receiveTelemetry(activity, grenadePacket(false, 0x31));
                assertEquals("A teammate grenade should damage everyone", 19,
                        get(activity, "mHealth"));
                assertEquals(1, get(activity, "mHitsTaken"));
            } finally {
                Globals.getmGrenadePairingsSemaphore();
                try { globals.mGrenadePairings[3] = previousOwner; }
                finally { globals.mGrenadePairingsSemaphore.release(); }
            }
        });
    }

    @Test
    public void idFreeGrenadeHitDamagesPairedPlayersTeam() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPairedGrenadeID = 3;
            receiveTelemetry(activity, grenadePacket(false, 0x01));
            assertEquals(19, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
            assertTrue(udp.messages.isEmpty());
        });
    }

    @Test
    public void idFreeEnemyGrenadeUsesTheMatchDamage() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGrenadeDamage = -3;
            receiveTelemetry(activity, grenadePacket(false, 0x01));
            assertEquals(17, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void idFreeGrenadeHitWithNoOwnerDamagesEveryone() {
        scenario.onActivity(activity -> {
            receiveTelemetry(activity, grenadePacket(false, 0x01));
            assertEquals(19, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void unpairedGrenadeUsesMatchDamageWithoutAnInvalidPseudoPlayer() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            globals.mGrenadeDamage = -2;
            Globals.getmGrenadePairingsSemaphore();
            int previousOwner;
            try {
                previousOwner = globals.mGrenadePairings[3];
                globals.mGrenadePairings[3] = Globals.INVALID_PLAYER_ID;
            } finally { globals.mGrenadePairingsSemaphore.release(); }
            try {
                byte[] packet = telemetryPacket(17, 1, 0, 0);
                packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2] = (byte) Globals.GRENADE_PLAYER_ID;
                packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID] = 0x31;
                receiveTelemetry(activity, packet);
                assertEquals("An unpaired grenade should use the match's grenade damage", 13,
                        get(activity, "mHealth"));
                assertTrue(udp.messages.contains(NetMsg.NETMSG_HIT + ":17"));
                assertTrue("An unpaired grenade was credited to pseudo-player 41",
                        !udp.messages.contains(NetMsg.NETMSG_HIT + ":41"));
            } finally {
                Globals.getmGrenadePairingsSemaphore();
                try { globals.mGrenadePairings[3] = previousOwner; }
                finally { globals.mGrenadePairingsSemaphore.release(); }
            }
        });
    }

    @Test
    public void repeatedGrenadeDamageTelemetryOnlyDamagesOnce() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            Globals.getmGrenadePairingsSemaphore();
            int previousOwner;
            try {
                previousOwner = globals.mGrenadePairings[3];
                globals.mGrenadePairings[3] = 17;
            } finally { globals.mGrenadePairingsSemaphore.release(); }
            try {
                byte[] packet = grenadePacket(false, 0x31);
                receiveTelemetry(activity, packet);
                receiveTelemetry(activity, packet);
                assertEquals("Repeated grenade telemetry applied damage twice", 19, get(activity, "mHealth"));
                assertEquals(1, get(activity, "mHitsTaken"));
            } finally {
                Globals.getmGrenadePairingsSemaphore();
                try { globals.mGrenadePairings[3] = previousOwner; }
                finally { globals.mGrenadePairingsSemaphore.release(); }
            }
        });
    }

    private void completeWrite(FullscreenActivity activity) {
        completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                bluetooth.writes.get(bluetooth.writes.size() - 1), BluetoothGatt.GATT_SUCCESS);
    }

    private static void completeWrite(FullscreenActivity activity, String uuid, byte[] data, int status) {
        BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
        receiver.onReceive(activity, new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                .putExtra(BluetoothLeService.EXTRA_UUID, uuid)
                .putExtra(BluetoothLeService.EXTRA_STATUS, status)
                .putExtra(BluetoothLeService.EXTRA_DATA, data));
    }

    private static void telemetry(FullscreenActivity activity, int firstPlayer, int firstShot,
                                  int secondPlayer, int secondShot) {
        byte[] packet = telemetryPacket(firstPlayer, firstShot, secondPlayer, secondShot);
        ((BluetoothGattCharacteristic) get(activity, "mTelemetryCharacteristic")).setValue(packet);
        receiveTelemetry(activity, packet);
    }

    private static void receiveTelemetry(FullscreenActivity activity, byte[] packet) {
        BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
        // Android 5.1 can move an ActivityScenario behind the instrumentation
        // activity while its test callback is running. Model an active GATT
        // delivery without leaving the lifecycle flag changed for teardown.
        boolean receiverRegistered = (boolean) get(activity, "mGattReceiverRegistered");
        if (!receiverRegistered)
            set(activity, "mGattReceiverRegistered", true);
        try {
            receiver.onReceive(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                    .putExtra(BluetoothLeService.EXTRA_DATA, packet));
        } finally {
            if (!receiverRegistered)
                set(activity, "mGattReceiverRegistered", false);
        }
    }

    private static byte[] telemetryPacket(int firstPlayer, int firstShot, int secondPlayer, int secondShot) {
        byte[] packet = new byte[20];
        packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = 1;
        packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 30;
        packet[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL] = 16;
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) (firstPlayer << 2);
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID] = (byte) firstShot;
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2] = (byte) (secondPlayer << 2);
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID] = (byte) secondShot;
        return packet;
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), 0, 0);
    }

    private static Object get(FullscreenActivity activity, String name) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(FullscreenActivity activity, String name, Object value) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(FullscreenActivity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private static void invoke(FullscreenActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeResult(FullscreenActivity activity, String name) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingBluetoothService extends BluetoothLeService {
        final List<byte[]> writes = new ArrayList<>();
        final Map<BluetoothGattCharacteristic, Intent> retainedWriteResults = new java.util.IdentityHashMap<>();
        boolean simulateConnected;

        @Override
        public synchronized boolean connect(String address) {
            return simulateConnected || super.connect(address);
        }

        @Override
        public synchronized boolean isConnected() {
            return simulateConnected || super.isConnected();
        }

        void retainWriteResult(BluetoothGattCharacteristic characteristic, Intent completed) {
            retainedWriteResults.put(characteristic, new Intent(completed)
                    .putExtra(BluetoothLeService.EXTRA_DATA,
                            completed.getByteArrayExtra(BluetoothLeService.EXTRA_DATA).clone()));
        }

        @Override
        public synchronized Intent getCompletedWriteResult(BluetoothGattCharacteristic characteristic, byte[] value) {
            Intent completed = retainedWriteResults.get(characteristic);
            if (completed == null || !Arrays.equals(value, completed.getByteArrayExtra(BluetoothLeService.EXTRA_DATA)))
                return null;
            return new Intent(completed).putExtra(BluetoothLeService.EXTRA_DATA,
                    completed.getByteArrayExtra(BluetoothLeService.EXTRA_DATA).clone());
        }

        @Override
        public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
            writes.add(characteristic.getValue().clone());
        }

        @Override
        public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            writes.add(value.clone());
        }

        @Override
        public List<BluetoothGattService> getSupportedGattServices() {
            BluetoothGattService service = new BluetoothGattService(
                    UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            return Collections.singletonList(service);
        }

        @Override
        public synchronized void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
            // Discovery tests use only simulated characteristics.
        }
    }

    /** Keep real resume callbacks from contacting hardware while retaining their receiver sequencing. */
    private static final class ReloadResumeFixture implements AutoCloseable {
        final PowerupDualGunFixture dual;
        final FullscreenActivity activity;
        final RecordingBluetoothService primary;
        final Object previousAddress, previousSecondAddress, previousConnected, previousSecondConnected;
        final Object previousSecondBound;

        ReloadResumeFixture(FullscreenActivity activity, RecordingBluetoothService primary) {
            this.activity = activity;
            this.primary = primary;
            previousAddress = get(activity, "mDeviceAddress");
            previousSecondAddress = get(activity, "mSecondaryDeviceAddress");
            previousConnected = get(activity, "mConnected");
            previousSecondConnected = get(activity, "mSecondaryConnected");
            previousSecondBound = get(activity, "mSecondaryBLEServiceBound");
            dual = new PowerupDualGunFixture(activity);
            primary.simulateConnected = true;
            dual.second.simulateConnected = true;
            set(activity, "mDeviceAddress", "AA:BB:CC:DD:EE:01");
            set(activity, "mSecondaryDeviceAddress", "AA:BB:CC:DD:EE:02");
            set(activity, "mConnected", true);
            set(activity, "mSecondaryConnected", true);
            // applyBossWeaponRole() runs on resume too; it must not bind a real second service.
            set(activity, "mSecondaryBLEServiceBound", true);
        }

        @Override public void close() {
            primary.retainedWriteResults.clear();
            primary.simulateConnected = false;
            CountDownTimer timer = (CountDownTimer) get(activity, "mReloadTimer");
            if (timer != null) timer.cancel();
            set(activity, "mReloadTimer", null);
            set(activity, "mDeviceAddress", previousAddress);
            set(activity, "mSecondaryDeviceAddress", previousSecondAddress);
            set(activity, "mConnected", previousConnected);
            set(activity, "mSecondaryConnected", previousSecondConnected);
            set(activity, "mSecondaryBLEServiceBound", previousSecondBound);
            dual.close();
        }
    }

    /** Both real GATT receiver paths, with only the physical blasters replaced. */
    private static final class PowerupDualGunFixture implements AutoCloseable {
        private final FullscreenActivity activity;
        final RecordingBluetoothService second = new RecordingBluetoothService();
        private final Object previousService, previousCommand, previousTelemetry;
        private final Object previousConfig, previousSecondConfig, previousCommunicating;
        private final boolean previousBoss;

        PowerupDualGunFixture(FullscreenActivity activity) {
            this.activity = activity;
            previousService = get(activity, "mSecondaryBluetoothLeService");
            previousCommand = get(activity, "mSecondaryCommandCharacteristic");
            previousTelemetry = get(activity, "mSecondaryTelemetryCharacteristic");
            previousConfig = get(activity, "mConfigCharacteristic");
            previousSecondConfig = get(activity, "mSecondaryConfigCharacteristic");
            previousCommunicating = get(activity, "mSecondaryCommunicating");
            previousBoss = Globals.getInstance().mBossMode;
            Globals.getInstance().mBossMode = true;
            Globals.getInstance().mFullReload = 30;
            set(activity, "mSecondaryBluetoothLeService", second);
            set(activity, "mSecondaryCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mSecondaryTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(activity, "mSecondaryCommunicating", true);
            set(activity, "mSecondaryReloading", 0);
            // Isolate ammo writes from unrelated firing-mode configuration writes.
            set(activity, "mConfigCharacteristic", null);
            set(activity, "mSecondaryConfigCharacteristic", null);
            for (String field : new String[]{"mLastTriggerCount", "mLastReloadButtonCount",
                    "mLastThumbButtonCount", "mLastPowerButtonCount", "mSecondaryLastTriggerCount",
                    "mSecondaryLastReloadButtonCount", "mSecondaryLastThumbButtonCount", "mSecondaryLastPowerButtonCount"})
                set(activity, field, (byte) 0);
        }

        @Override public void close() {
            CountDownTimer timer = (CountDownTimer) get(activity, "mSecondaryReloadTimer");
            if (timer != null) timer.cancel();
            set(activity, "mSecondaryReloadTimer", null);
            set(activity, "mSecondaryPendingReloadCommand", null);
            set(activity, "mSecondaryReloading", 3);
            set(activity, "mSecondaryBluetoothLeService", previousService);
            set(activity, "mSecondaryCommandCharacteristic", previousCommand);
            set(activity, "mSecondaryTelemetryCharacteristic", previousTelemetry);
            set(activity, "mConfigCharacteristic", previousConfig);
            set(activity, "mSecondaryConfigCharacteristic", previousSecondConfig);
            set(activity, "mSecondaryCommunicating", previousCommunicating);
            Globals.getInstance().mBossMode = previousBoss;
            second.onDestroy();
        }
    }

    private static final class RecordingLobbyTcpServer extends TcpServer {
        boolean ready = true;
        boolean pendingStart;
        int rebalances, modeChanges, preservedCancellations;
        @Override boolean isTcpServerReady() { return ready; }
        @Override boolean hasPendingOrAnnouncedStart() { return pendingStart; }
        @Override public boolean rebalanceTeams() { rebalances++; return true; }
        @Override public boolean isBalancedGamePrepared() { return Globals.getInstance().mBalancedGamePrepared; }
        @Override public boolean setBalancedGameMode(boolean requireQr) {
            modeChanges++;
            Globals globals = Globals.getInstance();
            globals.mBalancedRandom = true;
            globals.mBalancedRequireQr = requireQr;
            globals.mBalancedGamePrepared = false;
            globals.clearBalancedCheckIns();
            return true;
        }
        @Override public void cancelServer() { cancelServer(false); }
        @Override public void cancelServer(boolean preserveBalancedTeams) {
            if (preserveBalancedTeams) {
                preservedCancellations++;
                Globals.getInstance().clearBalancedRoundState();
            } else Globals.getInstance().clearBalancedAssignments();
        }
        @Override public void stopTcpServer() { Globals.getInstance().clearBalancedAssignments(); }
        @Override void clearScheduledStart() { pendingStart = false; }
        @Override public void sendAllGameInfo(int playerID) { }
    }

    private static final class RecordingOrderedEndServer extends TcpServer {
        int endRequests;
        @Override public boolean requestOrderedRoundEnd() { endRequests++; return true; }
        @Override public RoundReport freezeCompletedRoundReport(String token) { return null; }
        @Override void clearScheduledStart() { }
        @Override public void cancelServer(boolean preserveBalancedTeams) { }
    }

    private static final class BalancedModePreferences implements AutoCloseable {
        final SharedPreferences preferences;
        final Map<String, Object> saved = new HashMap<>();
        final String[] keys = {FullscreenActivity.PREF_BALANCED_MODE, FullscreenActivity.PREF_BALANCED_QR,
                FullscreenActivity.PREF_TOURNAMENT_MODE, FullscreenActivity.PREF_BOSS_MODE,
                FullscreenActivity.PREF_CTF_MODE, FullscreenActivity.PREF_INFECTION_MODE,
                FullscreenActivity.PREF_GAME_MODE};
        BalancedModePreferences(FullscreenActivity activity) {
            preferences = activity.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
            Map<String, ?> all = preferences.getAll();
            for (String key : keys) if (all.containsKey(key)) saved.put(key, all.get(key));
        }
        @Override public void close() {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value == null) editor.remove(key);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                    editor.putStringSet(key, strings);
                }
            }
            editor.commit();
        }
    }

    private static final class RoundAnalyticsPreferences implements AutoCloseable {
        final SharedPreferences preferences;
        final Map<String, Object> saved = new HashMap<>();
        RoundAnalyticsPreferences(FullscreenActivity activity) {
            preferences = activity.getSharedPreferences(FullscreenActivity.PREF_NAME, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
                if (analyticsKey(entry.getKey())) saved.put(entry.getKey(), entry.getValue());
        }
        private static boolean analyticsKey(String key) {
            return key.startsWith("LastGame.") || key.startsWith("HistoryRound")
                    || key.startsWith("HistoryBaseline") || key.startsWith("BalancedPrior");
        }
        @Override public void close() {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet())
                if (analyticsKey(key)) editor.remove(key);
            for (Map.Entry<String, Object> entry : saved.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            }
            editor.commit();
        }
    }

    private static final class RecordingTcpClient extends TcpClient {
        boolean dedicated;
        boolean connectedToLobby;
        int stopRequests;
        Intent pendingTerminal;
        int hostedShots;
        int startRequests;
        byte playerIdWhenStarted;
        final List<String> messages = new ArrayList<>();
        final List<Intent> broadcasts = new ArrayList<>();
        org.json.JSONObject retainedPowerupState;
        RoundReport completedReport;

        @Override public synchronized boolean hasLobbyConnection() { return connectedToLobby; }
        // Event-policy status is independent of the gameplay frames asserted here.
        @Override public void sendEventLockStatus(String status) { }
        @Override public synchronized Intent consumePendingTerminalEvent(long id) {
            if (id != 123 || pendingTerminal == null) return super.consumePendingTerminalEvent(id);
            Intent terminal = pendingTerminal;
            pendingTerminal = null;
            // Deliberately leave the fake connection live: the activity must
            // retain the consumed terminal marker even after its ID is removed.
            return terminal;
        }

        @Override public synchronized RoundReport getCompletedRoundReport(String token) {
            // Deliberately return even a mismatched token to exercise the UI's
            // independent round/player guard, not just the service's guard.
            return completedReport;
        }

        @Override
        public synchronized org.json.JSONObject powerupAuthoritySnapshot() {
            return retainedPowerupState == null ? super.powerupAuthoritySnapshot() : retainedPowerupState;
        }

        @Override
        public void sendBroadcast(Intent intent) { broadcasts.add(intent); }

        @Override
        public boolean isDedicatedServer() {
            return dedicated;
        }

        @Override
        public void reportHostedShots(int count) {
            hostedShots += count;
            super.reportHostedShots(count);
        }

        @Override
        void startTcpClient() {
            startRequests++;
            playerIdWhenStarted = Globals.getInstance().mPlayerID;
        }

        @Override
        public void sendTCPMessage(String message, boolean queueMessage) {
            messages.add(message);
        }

        @Override
        public void stopTcpClient() {
            // No network connection is opened by this test client.
            stopRequests++;
            connectedToLobby = false;
        }
    }

    private static final class RecordingUDPService extends UDPListenerService {
        int[] roundScores;
        @Override public int[] getRoundScoresSnapshot() {
            return roundScores == null ? super.getRoundScoresSnapshot() : roundScores.clone();
        }
        long hostingSince = -1;
        long resumedSince = -1;
        @Override long hostStartedAt() { return hostingSince; }
        @Override void resumeHostTenure(long startedAt) { resumedSince = startedAt; }
        final List<String> messages = new ArrayList<>();
        int endRequests;
        int endVoteRequests;
        int peerQuitAnnouncements;
        int serverCreates;
        int serverCancellations;
        int peerGrenadePairingPublishes;
        int peerEliminationPublishes;
        Byte lastPeerEliminationRecipient;
        int peerLeaveAnnouncements;
        int peerTeamEliminationPublishes;
        Byte lastPeerTeamEliminatedPlayer;
        long lastPeerTeamEventSequence;
        Byte lastPeerTeamRecipient;

        RecordingUDPService() {
            // Production services always have an application context. The
            // detached test double needs one for gameplay Wi-Fi locks too.
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            attachBaseContext(context.getApplicationContext());
        }

        @Override
        public void endGame() { endRequests++; }

        @Override
        public boolean voteToEndGame(String token, boolean approve) {
            endVoteRequests++;
            return true;
        }

        @Override
        public void announcePeerQuit() { peerQuitAnnouncements++; }

        @Override
        public void createServer() { serverCreates++; }

        @Override
        public void cancelServer() { serverCancellations++; }

        @Override
        public void sendUDPMessage(String message, Byte playerID) {
            messages.add(message + ":" + playerID);
        }

        @Override
        public void sendUDPMessageAll(String message) {
            messages.add(message + ":all");
        }

        @Override
        public void publishPeerGrenadePairing() {
            peerGrenadePairingPublishes++;
        }

        @Override
        public void publishPeerElimination(byte scoringPlayerID) {
            peerEliminationPublishes++;
            lastPeerEliminationRecipient = scoringPlayerID;
        }

        @Override
        public void publishPeerTeamElimination(byte eliminatedPlayerID, long sequence,
                                               byte teammateID) {
            peerTeamEliminationPublishes++;
            lastPeerTeamEliminatedPlayer = eliminatedPlayerID;
            lastPeerTeamEventSequence = sequence;
            lastPeerTeamRecipient = teammateID;
        }

        @Override
        public void announcePeerLeave() {
            peerLeaveAnnouncements++;
        }

        @Override
        public void startGameInviteListener() {
            // This detached fake deliberately has no Android service context or socket.
        }

        @Override
        void stopListen() {
            // No sockets are opened by this test service.
        }
    }
}
