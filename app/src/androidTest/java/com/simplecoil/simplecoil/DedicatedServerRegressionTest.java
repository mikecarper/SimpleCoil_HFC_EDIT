package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the dedicated server UI with recording services and synthetic callbacks. */
@RunWith(AndroidJUnit4.class)
public class DedicatedServerRegressionTest {
    private ActivityScenario<DedicatedServerActivity> scenario;
    private DedicatedServerActivity activity;
    private RecordingTcpServer tcp;
    private RecordingUDPService udp;
    private byte originalPlayerID;
    private int originalGameState;
    private int originalGameLimit;
    private int originalTimeLimit;
    private long originalRespawnTime;
    private long originalTimeRemaining;
    private boolean originalUseGPS;
    private boolean originalOnlyServerSettings;

    @Before
    public void setUp() {
        long wakeTime = SystemClock.uptimeMillis();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(
                new KeyEvent(wakeTime, wakeTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP, 0), true);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(
                new KeyEvent(wakeTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP, 0), true);
        Globals globals = Globals.getInstance();
        originalPlayerID = globals.mPlayerID;
        originalGameState = globals.mGameState;
        originalGameLimit = globals.mGameLimit;
        originalTimeLimit = globals.mTimeLimit;
        originalRespawnTime = globals.mRespawnTime;
        originalTimeRemaining = globals.mServerGameTimeRemaining;
        originalUseGPS = globals.mUseGPS;
        originalOnlyServerSettings = globals.mOnlyServerSettings;
        // Dedicated hosting temporarily owns player ID zero. Use a non-zero
        // selection to verify that closing the host restores the player screen.
        globals.mPlayerID = 7;
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mServerGameTimeRemaining = 0;
        globals.mUseGPS = false;
        globals.mOnlyServerSettings = true;
        scenario = ActivityScenario.launch(DedicatedServerActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            // Lifecycle and rotation checks need a visible activity even when
            // the unattended test device has returned to its lock screen.
            current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            // Release the activity's real startup bindings before installing fakes.
            invoke("unbindUDPService");
            invoke("unbindTcpServerService");
            current.stopService(new Intent(current, UDPListenerService.class));
            current.stopService(new Intent(current, TcpServer.class));
            tcp = new RecordingTcpServer();
            udp = new RecordingUDPService();
            set("mTcpServer", tcp);
            set("mUDPListenerService", udp);
            beginBinding(true);
            beginBinding(false);
            globals.mUseGPS = false;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mRespawnTime = 10;
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
    }

    @After
    public void tearDown() {
        if (scenario != null && scenario.getState() == Lifecycle.State.RESUMED) {
            scenario.onActivity(current -> {
                if (activity != current) {
                    // Also clean up a replacement activity if a rotation regression
                    // recreates the screen and starts real service bindings.
                    activity = current;
                    invoke("unbindUDPService");
                    invoke("unbindTcpServerService");
                    current.stopService(new Intent(current, UDPListenerService.class));
                    current.stopService(new Intent(current, TcpServer.class));
                }
            });
        }
        if (activity != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                // Test connections were never bound through Android.
                set("mTcpServerServiceBound", false);
                set("mUDPServiceBound", false);
                invoke("endGame");
            });
        }
        if (scenario != null) scenario.close();
        Globals globals = Globals.getInstance();
        globals.mPlayerID = originalPlayerID;
        globals.mGameState = originalGameState;
        globals.mGameLimit = originalGameLimit;
        globals.mTimeLimit = originalTimeLimit;
        globals.mRespawnTime = originalRespawnTime;
        globals.mServerGameTimeRemaining = originalTimeRemaining;
        globals.mUseGPS = originalUseGPS;
        globals.mOnlyServerSettings = originalOnlyServerSettings;
    }

    @Test
    public void retainedServerOnlyPolicyIsShownWhenOpeningTheHostControls() {
        scenario.onActivity(current -> {
            Switch control = current.findViewById(R.id.only_server_settings_switch);
            assertTrue("The host switch disagrees with the active policy", control.isChecked());
            assertTrue(Globals.getInstance().mOnlyServerSettings);
            assertEquals(0, tcp.gameInfoUpdates);
        });
    }

    @Test
    public void retainedServerOnlyPolicyCanBeDisabledWithOneClickAndEnabledAgain() {
        scenario.onActivity(current -> {
            Switch control = current.findViewById(R.id.only_server_settings_switch);
            control.performClick();
            assertFalse("One click should disable the retained policy", Globals.getInstance().mOnlyServerSettings);
            assertFalse(control.isChecked());
            assertEquals(1, tcp.gameInfoUpdates);
            control.performClick();
            assertTrue(Globals.getInstance().mOnlyServerSettings);
            assertTrue(control.isChecked());
            assertEquals(2, tcp.gameInfoUpdates);
        });
    }

    @Test
    public void timedRoundEndsLocallyIfTheTcpServiceDisconnects() {
        scenario.onActivity(current -> {
            CountDownTimer timer = startTimedRound();
            disconnectTcpService();
            timer.onFinish();
            assertRoundEnded();
            assertEquals(0, tcp.gameEnds);
            timer.onTick(41000);
            assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
        });
    }

    @Test
    public void endButtonStillEndsTheRoundIfTheTcpServiceDisconnects() {
        scenario.onActivity(current -> {
            receive(NetMsg.NETMSG_STARTGAME);
            assertNotNull(get("mSpawnTimer"));
            disconnectTcpService();
            button(R.id.end_game_button).performClick();
            assertRoundEnded();
            assertEquals(0, tcp.gameEnds);
        });
    }

    @Test
    public void endingARoundShowsNoPlayersAfterTcpServerClearsTheLobby() {
        scenario.onActivity(current -> {
            Map<Byte, InetAddress> originalRoster = new HashMap<>();
            Globals.getmTeamIPMapSemaphore();
            try {
                originalRoster.putAll(Globals.getInstance().mTeamIPMap);
                Globals.getInstance().mTeamIPMap.clear();
                InetAddress loopback = InetAddress.getLoopbackAddress();
                Globals.getInstance().mTeamIPMap.put((byte) 1, loopback);
                Globals.getInstance().mTeamIPMap.put((byte) 2, loopback);
            } finally {
                Globals.getInstance().mTeamIPMapSemaphore.release();
            }
            try {
                invoke("endGame");
                TextView playerCount = current.findViewById(R.id.player_count_tv);
                assertEquals(current.getString(R.string.network_player_count, 0),
                        playerCount.getText().toString());
            } finally {
                Globals.getmTeamIPMapSemaphore();
                try {
                    Globals.getInstance().mTeamIPMap.clear();
                    Globals.getInstance().mTeamIPMap.putAll(originalRoster);
                } finally {
                    Globals.getInstance().mTeamIPMapSemaphore.release();
                }
            }
        });
    }

    @Test
    public void timedRoundWithATcpServiceStillWaitsForServerCleanup() {
        scenario.onActivity(current -> {
            startTimedRound().onFinish();
            assertEquals(1, tcp.gameEnds);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertFalse(button(R.id.start_game_button).isEnabled());
            receive(NetMsg.NETMSG_ENDGAME);
            assertRoundEnded();
        });
    }

    @Test
    public void endButtonWithATcpServiceStillRequestsServerCleanup() {
        scenario.onActivity(current -> {
            receive(NetMsg.NETMSG_STARTGAME);
            button(R.id.end_game_button).performClick();
            assertEquals(1, tcp.gameEnds);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            receive(NetMsg.NETMSG_ENDGAME);
            assertRoundEnded();
        });
    }

    @Test
    public void destroyedEndButtonCannotEndAnotherScreensRound() {
        closeActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            button(R.id.end_game_button).performClick();
            assertEquals(0, tcp.gameEnds);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    private CountDownTimer startTimedRound() {
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mTimeLimit = 5;
        receive(NetMsg.NETMSG_STARTGAME);
        CountDownTimer timer = (CountDownTimer) get("mGameCountdownTimer");
        assertNotNull(timer);
        timer.cancel();
        timer.onTick(42000);
        return timer;
    }

    private void disconnectTcpService() {
        ((ServiceConnection) get("mTcpServerServiceConnection")).onServiceDisconnected(null);
        assertNull(get("mTcpServer"));
    }

    private void assertRoundEnded() {
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
        assertNull(get("mSpawnTimer"));
        assertNull(get("mGameCountdownTimer"));
        assertTrue(button(R.id.start_game_button).isEnabled());
        assertFalse(button(R.id.end_game_button).isEnabled());
        assertTrue(udp.allowJoin);
    }

    @Test
    public void remoteStartNotificationDoesNotSendAnotherStartRequest() {
        scenario.onActivity(current -> {
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals("A server notification was sent back as another request", 0, tcp.gameStarts);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertFalse(button(R.id.start_game_button).isEnabled());
            assertTrue(button(R.id.end_game_button).isEnabled());
            assertNotNull(get("mSpawnTimer"));
        });
    }

    @Test
    public void duplicateRemoteStartKeepsTheOriginalRoundTimer() {
        scenario.onActivity(current -> {
            receive(NetMsg.NETMSG_STARTGAME);
            CountDownTimer first = (CountDownTimer) get("mSpawnTimer");
            receive(NetMsg.NETMSG_STARTGAME);
            assertNotNull(first);
            assertSame(first, get("mSpawnTimer"));
            assertEquals(0, tcp.gameStarts);
        });
    }

    @Test
    public void acceptedStartStillUpdatesTheScreenWhileTcpBindingIsMissing() {
        assertAcceptedStartWithoutBinding(true);
    }

    @Test
    public void acceptedStartStillUpdatesTheScreenWhileUdpBindingIsMissing() {
        assertAcceptedStartWithoutBinding(false);
    }

    private void assertAcceptedStartWithoutBinding(boolean isTcp) {
        scenario.onActivity(current -> {
            set(serviceField(isTcp), null);
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNotNull(get("mSpawnTimer"));
            assertEquals(0, tcp.gameStarts);
        });
    }

    @Test
    public void localStartWaitsForAcknowledgementBeforeStartingTheRound() {
        scenario.onActivity(current -> {
            invoke("startGame");
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull("A queued send is not a confirmed round start", get("mSpawnTimer"));
            assertTrue(button(R.id.start_game_button).isEnabled());
            receive(NetMsg.NETMSG_STARTGAME);
            CountDownTimer first = (CountDownTimer) get("mSpawnTimer");
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals(1, tcp.gameStarts);
            assertNotNull(first);
            assertSame(first, get("mSpawnTimer"));
        });
    }

    @Test
    public void queuedStartDoesNotConsumeTheTimedRoundBeforeClientsAreNotified() {
        scenario.onActivity(current -> {
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            Globals.getInstance().mTimeLimit = 5;
            Globals.getInstance().mServerGameTimeRemaining = 0;
            invoke("startGame");
            assertNull("The timed round started before network delivery", get("mGameCountdownTimer"));
            assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            receive(NetMsg.NETMSG_STARTGAME);
            assertNotNull(get("mGameCountdownTimer"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(1, tcp.gameStarts);
        });
    }

    @Test
    public void unconfirmedStartCanBeRetriedInsteadOfLeavingTheHostInAPhantomRound() {
        scenario.onActivity(current -> {
            invoke("startGame");
            // The queued write failed and therefore produced no STARTGAME event.
            tcp.acceptStart = false;
            invoke("startGame");
            assertEquals(2, tcp.gameStarts);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get("mSpawnTimer"));
            assertTrue(button(R.id.start_game_button).isEnabled());
            tcp.acceptStart = true;
            invoke("startGame");
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals(3, tcp.gameStarts);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNotNull(get("mSpawnTimer"));
        });
    }

    @Test
    public void rejectedLocalStartKeepsTheLobbyAndTimersInactive() {
        scenario.onActivity(current -> {
            tcp.acceptStart = false;
            invoke("startGame");
            assertEquals(1, tcp.gameStarts);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertTrue(button(R.id.start_game_button).isEnabled());
            assertNull(get("mSpawnTimer"));
            assertNull(get("mGameCountdownTimer"));
        });
    }

    @Test
    public void destroyedActivityIgnoresLateGameStart() {
        closeActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals(0, tcp.gameStarts);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get("mSpawnTimer"));
        });
    }

    @Test
    public void destroyedActivityCannotEndAnotherScreensRound() {
        closeActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receive(NetMsg.NETMSG_ENDGAME);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void pausedServerStillAcceptsRemoteStarts() throws InterruptedException {
        assertBackgroundStart(Lifecycle.State.STARTED);
    }

    @Test
    public void stoppedServerStillAcceptsRemoteStarts() throws InterruptedException {
        assertBackgroundStart(Lifecycle.State.CREATED);
    }

    private void assertBackgroundStart(Lifecycle.State state) throws InterruptedException {
        keepRecordingServicesBound();
        scenario.moveToState(state);
        broadcastAndWait(NetMsg.NETMSG_STARTGAME);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNotNull(get("mSpawnTimer"));
            assertEquals(0, tcp.gameStarts);
            assertFalse(button(R.id.start_game_button).isEnabled());
        });
    }

    @Test
    public void pausedServerStillEndsAnActiveRound() throws InterruptedException {
        keepRecordingServicesBound();
        scenario.onActivity(current -> receive(NetMsg.NETMSG_STARTGAME));
        scenario.moveToState(Lifecycle.State.STARTED);
        broadcastAndWait(NetMsg.NETMSG_ENDGAME);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get("mSpawnTimer"));
            assertTrue(button(R.id.start_game_button).isEnabled());
            assertFalse(button(R.id.end_game_button).isEnabled());
        });
    }

    @Test
    public void stoppedServerStillEndsATimedRound() throws InterruptedException {
        keepRecordingServicesBound();
        scenario.onActivity(current -> {
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            Globals.getInstance().mTimeLimit = 5;
            receive(NetMsg.NETMSG_STARTGAME);
            assertNotNull(get("mGameCountdownTimer"));
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        broadcastAndWait(NetMsg.NETMSG_ENDGAME);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get("mGameCountdownTimer"));
            assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
        });
    }

    @Test
    public void stoppedServerStillRefreshesPlayerScores() throws InterruptedException {
        keepRecordingServicesBound();
        scenario.onActivity(current -> {
            tcp.firstPlayerScore = tcp.new ScoreData();
            tcp.firstPlayerScore.points = 12;
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        broadcastAndWait(NetMsg.NETMSG_PLAYERDATAUPDATE);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertNotNull(activity.mPlayerDisplayListAdapter.getItem(1));
            assertEquals(12, activity.mPlayerDisplayListAdapter.getItem(1).points);
        });
    }

    @Test
    public void destroyedServerNoLongerReceivesGameBroadcasts() throws InterruptedException {
        closeActivity();
        broadcastAndWait(NetMsg.NETMSG_STARTGAME);
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        assertNull(get("mSpawnTimer"));
    }

    @Test
    public void endingATimedRoundClearsTheAdvertisedTimeRemaining() {
        scenario.onActivity(current -> {
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_TIME;
            Globals.getInstance().mTimeLimit = 5;
            receive(NetMsg.NETMSG_STARTGAME);
            CountDownTimer timer = (CountDownTimer) get("mGameCountdownTimer");
            timer.onTick(42000);
            assertEquals(42, Globals.getInstance().mServerGameTimeRemaining);
            receive(NetMsg.NETMSG_ENDGAME);
            assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
            timer.onTick(41000);
            assertEquals("A retired timer must not restore the old round's time", 0,
                    Globals.getInstance().mServerGameTimeRemaining);
        });
    }

    @Test
    public void rotationPreservesTheSpawnTimerAndServerConnections() {
        assertRotationPreservesRound(false);
    }

    @Test
    public void rotationPreservesTheGameCountdownAndServerConnections() {
        assertRotationPreservesRound(true);
    }

    private void assertRotationPreservesRound(boolean timed) {
        keepRecordingServicesBound();
        DedicatedServerActivity original = activity;
        Object[] timer = new Object[1];
        int originalOrientation = activity.getResources().getConfiguration().orientation;
        int targetOrientation = originalOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        String timerField = timed ? "mGameCountdownTimer" : "mSpawnTimer";
        scenario.onActivity(current -> {
            Globals.getInstance().mRespawnTime = 60;
            Globals.getInstance().mGameLimit = timed ? Globals.GAME_LIMIT_TIME : Globals.GAME_LIMIT_NONE;
            Globals.getInstance().mTimeLimit = 5;
            receive(NetMsg.NETMSG_STARTGAME);
            timer[0] = get(timerField);
            assertNotNull(timer[0]);
            current.setRequestedOrientation(targetOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!original.isDestroyed()
                && original.getResources().getConfiguration().orientation != targetOrientation
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse("Rotating the server screen destroyed the running session", original.isDestroyed());
        scenario.onActivity(current -> {
            assertSame(original, current);
            assertEquals(targetOrientation, current.getResources().getConfiguration().orientation);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertSame(timer[0], get(timerField));
            assertSame(tcp, get("mTcpServer"));
            assertSame(udp, get("mUDPListenerService"));
            assertEquals(0, tcp.listenerStops);
            assertEquals(0, udp.listenerStops);
            assertFalse(button(R.id.start_game_button).isEnabled());
        });
    }

    private void keepRecordingServicesBound() {
        scenario.onActivity(current -> {
            beginBinding(true);
            beginBinding(false);
        });
    }

    private void broadcastAndWait(String action) throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch delivered = new CountDownLatch(1);
        context.sendOrderedBroadcast(new Intent(action).setPackage(context.getPackageName()), null,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        delivered.countDown();
                    }
                }, null, 0, null, null);
        assertTrue("The test broadcast did not finish", delivered.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void staleTcpBindingCannotReplaceTheCurrentServer() { assertStaleConnectIgnored(true); }

    @Test
    public void staleUdpBindingCannotReplaceTheCurrentServer() { assertStaleConnectIgnored(false); }

    private void assertStaleConnectIgnored(boolean isTcp) {
        scenario.onActivity(current -> {
            ServiceConnection old = beginBinding(isTcp);
            ServiceConnection replacement = beginBinding(isTcp);
            RecordingTcpServer oldTcp = new RecordingTcpServer();
            RecordingUDPService oldUdp = new RecordingUDPService();
            old.onServiceConnected(null, isTcp ? oldTcp.onBind(new Intent()) : oldUdp.onBind(new Intent()));
            assertSame(isTcp ? tcp : udp, get(serviceField(isTcp)));
            assertSame(replacement, get(connectionField(isTcp)));
            assertEquals(0, oldTcp.listenerStarts);
            assertEquals(0, oldUdp.listenerStarts);
        });
    }

    @Test
    public void staleTcpDisconnectCannotClearTheCurrentServer() { assertStaleDisconnectIgnored(true); }

    @Test
    public void staleUdpDisconnectCannotClearTheCurrentServer() { assertStaleDisconnectIgnored(false); }

    private void assertStaleDisconnectIgnored(boolean isTcp) {
        scenario.onActivity(current -> {
            ServiceConnection old = beginBinding(isTcp);
            beginBinding(isTcp);
            old.onServiceDisconnected(null);
            assertSame(isTcp ? tcp : udp, get(serviceField(isTcp)));
        });
    }

    @Test
    public void cancelledTcpBindingCannotRestartTheListener() { assertCancelledConnectIgnored(true); }

    @Test
    public void cancelledUdpBindingCannotRestartTheListener() { assertCancelledConnectIgnored(false); }

    private void assertCancelledConnectIgnored(boolean isTcp) {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginBinding(isTcp);
            set(boundField(isTcp), false);
            callback.onServiceConnected(null, isTcp ? tcp.onBind(new Intent()) : udp.onBind(new Intent()));
            assertEquals(0, tcp.listenerStarts);
            assertEquals(0, udp.listenerStarts);
        });
    }

    @Test
    public void currentTcpBindingStillStartsAndDisconnectsTheServer() { assertCurrentBindingWorks(true); }

    @Test
    public void udpBindingWaitsForTcpReadinessBeforeAdvertising() {
        scenario.onActivity(current -> {
            tcp.ready = false;
            ServiceConnection callback = beginBinding(false);
            callback.onServiceConnected(null, udp.onBind(new Intent()));
            assertEquals("Dedicated UDP advertised before TCP was listening", 0, udp.listenerStarts);
            tcp.ready = true;
            receive(NetMsg.NETMSG_TCPSERVERREADY);
            assertEquals(1, udp.listenerStarts);
            receive(NetMsg.NETMSG_TCPSERVERREADY);
            assertEquals("Repeated TCP-ready events restarted UDP hosting", 1, udp.listenerStarts);
        });
    }

    @Test
    public void staleTcpFailureDoesNotTearDownTheCurrentDedicatedHost() {
        scenario.onActivity(current -> {
            tcp.ready = true;
            tcp.cancellations = 0;
            udp.listenerStops = 0;
            set("mTcpServerStartupPending", false);
            receive(NetMsg.NETMSG_TCPSERVERFAILED);
            assertEquals(0, tcp.cancellations);
            assertEquals(0, udp.listenerStops);
        });
    }

    @Test
    public void udpBindingDuringARoundRestoresTheClosedLobbySetting() { assertJoinSettingRestored(false); }

    @Test
    public void udpBindingDuringARoundRestoresTheOpenLobbySetting() { assertJoinSettingRestored(true); }

    private void assertJoinSettingRestored(boolean allowJoin) {
        scenario.onActivity(current -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            ((Switch) current.findViewById(R.id.allow_join_switch)).setChecked(allowJoin);
            tcp.ready = true;
            ServiceConnection callback = beginBinding(false);
            callback.onServiceConnected(null, udp.onBind(new Intent()));
            assertEquals(1, udp.listenerStarts);
            assertEquals(1, udp.joinSettingChanges);
            assertEquals(allowJoin, udp.allowJoin);
        });
    }

    private void assertCurrentBindingWorks(boolean isTcp) {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginBinding(isTcp);
            callback.onServiceConnected(null, isTcp ? tcp.onBind(new Intent()) : udp.onBind(new Intent()));
            assertEquals(1, isTcp ? tcp.listenerStarts : udp.listenerStarts);
            if (isTcp) assertTrue(tcp.dedicated);
            callback.onServiceDisconnected(null);
            assertNull(get(serviceField(isTcp)));
        });
    }

    @Test
    public void destroyedActivityCannotRestartTcpFromALateBinding() { assertDestroyedConnectIgnored(true); }

    @Test
    public void destroyedActivityCannotRestartUdpFromALateBinding() { assertDestroyedConnectIgnored(false); }

    @Test
    public void scoreboardUpdatesNotifyTheExistingAdapterWithFreshScores() {
        scenario.onActivity(current -> {
            PlayerDisplayDataListAdapter adapter = current.mPlayerDisplayListAdapter;
            ListView list = current.findViewById(R.id.player_list);
            tcp.firstPlayerScore = tcp.new ScoreData();
            tcp.firstPlayerScore.points = 7;
            int[] changes = {0};
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() {
                    changes[0]++;
                    assertEquals(7, adapter.getItem(1).points);
                }
            });
            receive(NetMsg.NETMSG_PLAYERDATAUPDATE);
            assertEquals(1, changes[0]);
            assertSame(adapter, list.getAdapter());
        });
    }

    @Test
    public void hostScoreboardKeepsTheNameAndPointsOfADepartedPlayer() {
        assertScoreboardPlayerName(null, "Departed player");
    }

    @Test
    public void hostScoreboardPrefersARejoinedPlayersCurrentName() {
        assertScoreboardPlayerName("Current player", "Current player");
    }

    private void assertScoreboardPlayerName(String currentName, String expectedName) {
        scenario.onActivity(current -> {
            Globals globals = Globals.getInstance();
            int gameMode = globals.mGameMode;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            Globals.getmTeamPlayerNameSemaphore();
            String previousName;
            try {
                previousName = globals.mTeamPlayerNameMap.remove((byte) 1);
                if (currentName != null)
                    globals.mTeamPlayerNameMap.put((byte) 1, currentName);
            } finally { globals.mTeamPlayerNameSemaphore.release(); }
            try {
                tcp.firstPlayerScore = tcp.new ScoreData();
                tcp.firstPlayerScore.playerName = "Departed player";
                tcp.firstPlayerScore.points = 7;
                tcp.firstPlayerScore.eliminated = 5;
                receive(NetMsg.NETMSG_PLAYERDATAUPDATE);
                PlayerDisplayDataListAdapter adapter = current.mPlayerDisplayListAdapter;
                PlayerDisplayData player = adapter.getItem(1);
                assertEquals(expectedName, player.playerName);
                assertEquals(7, player.points);
                assertEquals(5, player.eliminated);
                assertFalse(player.isConnected);
                assertEquals(current.getString(R.string.player_list_team2_total, 7, 0),
                        adapter.getItem(Globals.MAX_PLAYER_ID + 1).playerName);
                View row = adapter.getView(1, null, current.findViewById(R.id.player_list));
                assertEquals(expectedName, ((TextView) row.findViewById(R.id.player_name_tv)).getText().toString());
            } finally {
                globals.mGameMode = gameMode;
                Globals.getmTeamPlayerNameSemaphore();
                try {
                    globals.mTeamPlayerNameMap.remove((byte) 1);
                    if (previousName != null)
                        globals.mTeamPlayerNameMap.put((byte) 1, previousName);
                } finally { globals.mTeamPlayerNameSemaphore.release(); }
            }
        });
    }

    @Test
    public void liveScoreboardUpdatePreservesTheScrolledPosition() {
        scenario.onActivity(current -> {
            ListView list = current.findViewById(R.id.player_list);
            // Use a fixed viewport so this checks scrolling on both phones and tablets.
            layoutScoreboard(list);
            list.setSelectionFromTop(8, -7);
            layoutScoreboard(list);
            int position = list.getFirstVisiblePosition();
            assertTrue("The fixture must scroll below the header", position > 0);
            int top = list.getChildAt(0).getTop();
            receive(NetMsg.NETMSG_PLAYERDATAUPDATE);
            layoutScoreboard(list);
            assertEquals(position, list.getFirstVisiblePosition());
            assertEquals(top, list.getChildAt(0).getTop());
        });
    }

    private static void layoutScoreboard(ListView list) {
        list.forceLayout();
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 400, 150);
    }

    private void assertDestroyedConnectIgnored(boolean isTcp) {
        ServiceConnection[] callback = new ServiceConnection[1];
        scenario.onActivity(current -> callback[0] = beginBinding(isTcp));
        closeActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            callback[0].onServiceConnected(null, isTcp ? tcp.onBind(new Intent()) : udp.onBind(new Intent()));
            assertNull(get(serviceField(isTcp)));
            assertEquals(0, tcp.listenerStarts);
            assertEquals(0, udp.listenerStarts);
        });
    }

    private void closeActivity() {
        scenario.close();
        scenario = null;
    }

    @Test
    public void closingHostAllowsCancellationToFlushBeforeStopping() {
        closeActivity();
        assertEquals(1, tcp.cancellations);
        assertEquals("The host closed sockets before cancellation could flush", 0, tcp.listenerStops);
        assertEquals(1, udp.listenerStops);
    }

    @Test
    public void closingHostRestoresPlayerStateAndEndsItsRound() {
        scenario.onActivity(current -> {
            assertEquals(0, Globals.getInstance().mPlayerID);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            Globals.getInstance().mServerGameTimeRemaining = 42;
            Globals.getInstance().mUseGPS = true;
            Globals.getInstance().mOnlyServerSettings = false;
        });
        closeActivity();
        assertEquals("Dedicated hosting erased the player's selected ID", 7,
                Globals.getInstance().mPlayerID);
        assertEquals("The closed host left the player screen inside its round",
                Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
        assertEquals(0, Globals.getInstance().mServerGameTimeRemaining);
        assertFalse("The closed host left its GPS policy active", Globals.getInstance().mUseGPS);
        assertTrue("The closed host overwrote the player's prior network policy",
                Globals.getInstance().mOnlyServerSettings);
    }

    private ServiceConnection beginBinding(boolean isTcp) {
        ServiceConnection callback = (ServiceConnection) invoke(isTcp
                ? "createTcpServerServiceConnection" : "createUDPServiceConnection");
        set(connectionField(isTcp), callback);
        set(boundField(isTcp), true);
        return callback;
    }

    private String serviceField(boolean isTcp) { return isTcp ? "mTcpServer" : "mUDPListenerService"; }
    private String connectionField(boolean isTcp) { return isTcp ? "mTcpServerServiceConnection" : "mUDPServiceConnection"; }
    private String boundField(boolean isTcp) { return isTcp ? "mTcpServerServiceBound" : "mUDPServiceBound"; }
    private Button button(int id) { return activity.findViewById(id); }

    @Test
    public void synchronizedHostCountdownUsesBroadcastDeadline() {
        scenario.onActivity(current -> {
            long deadline = SystemClock.elapsedRealtime() + 4000;
            Globals.getInstance().mRespawnTime = 90;
            ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity,
                    new Intent(NetMsg.NETMSG_STARTGAME).putExtra(NetMsg.INTENT_START_AT, deadline)
                            .putExtra(NetMsg.INTENT_END_AT, deadline + 60000));
            assertEquals(deadline, get("mRoundStartAt"));
            assertEquals(deadline + 60000, get("mRoundEndAt"));
            assertNotNull(get("mSpawnTimer"));
            assertNotNull(get("mGameCountdownTimer"));
            assertTrue(Globals.getInstance().mServerGameTimeRemaining <= 64);
            assertTrue(Globals.getInstance().mServerGameTimeRemaining >= 60);
        });
    }

    @Test
    public void delayedUnlimitedHostStartUsesOriginalChronometerBase() {
        scenario.onActivity(current -> {
            long deadline = SystemClock.elapsedRealtime() - 5000;
            ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity,
                    new Intent(NetMsg.NETMSG_STARTGAME).putExtra(NetMsg.INTENT_START_AT, deadline)
                            .putExtra(NetMsg.INTENT_END_AT, 0L));
            assertNull(get("mSpawnTimer"));
            assertEquals(deadline, ((android.widget.Chronometer) get("mGameTimer")).getBase());
        });
    }

    @Test
    public void staleSynchronizedStartCannotBeginAnotherLobbyRound() {
        scenario.onActivity(current -> {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            Intent stale = new Intent(NetMsg.NETMSG_STARTGAME)
                    .putExtra(NetMsg.INTENT_START_AT, deadline)
                    .putExtra(NetMsg.INTENT_END_AT, 0L)
                    .putExtra(NetMsg.INTENT_ROUND_ID, 4L);
            ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity, stale);
            assertEquals("A completed or cancelled round restarted the host UI",
                    Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get("mSpawnTimer"));

            tcp.scheduledStart = new Intent(NetMsg.NETMSG_STARTGAME)
                    .putExtra(NetMsg.INTENT_START_AT, deadline)
                    .putExtra(NetMsg.INTENT_END_AT, 0L)
                    .putExtra(NetMsg.INTENT_ROUND_ID, 5L);
            ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity, stale);
            assertEquals("A prior round start overtook the current lobby", Globals.GAME_STATE_NONE,
                    Globals.getInstance().mGameState);

            ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity,
                    new Intent(tcp.scheduledStart));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertNotNull(get("mSpawnTimer"));
        });
    }

    private void receive(String action) {
        ((BroadcastReceiver) get("mServerUpdateReceiver")).onReceive(activity, new Intent(action));
    }

    private Object get(String name) {
        try {
            Field field = DedicatedServerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void set(String name, Object value) {
        try {
            Field field = DedicatedServerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private Object invoke(String name) {
        try {
            Method method = DedicatedServerActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static final class RecordingTcpServer extends TcpServer {
        int gameStarts;
        int gameEnds;
        int gameInfoUpdates;
        int listenerStarts;
        int listenerStops;
        int cancellations;
        boolean acceptStart = true;
        boolean dedicated;
        boolean ready;
        ScoreData firstPlayerScore;
        Intent scheduledStart;

        @Override public boolean startGame() { gameStarts++; return acceptStart; }
        @Override public void endGame() { gameEnds++; }
        @Override void startTcpServer() { listenerStarts++; }
        @Override public void stopTcpServer() { listenerStops++; }
        @Override public void cancelServer() { cancellations++; }
        @Override public void setDedicated(boolean value) { dedicated = value; }
        @Override boolean isTcpServerReady() { return ready; }
        @Override Intent getScheduledGameStart() {
            return scheduledStart == null ? null : new Intent(scheduledStart);
        }
        @Override void clearScheduledStart() { scheduledStart = null; }
        @Override public void sendTCPMessageAll(String message) { }
        @Override public void sendAllGameInfo(int playerID) { gameInfoUpdates++; }
        @Override public ScoreData getScore(byte playerID) { return playerID == 1 ? firstPlayerScore : null; }
    }

    private static final class RecordingUDPService extends UDPListenerService {
        int listenerStarts;
        int listenerStops;
        int joinSettingChanges;
        boolean allowJoin;

        @Override public void createServer() { listenerStarts++; }
        @Override void stopListen() { listenerStops++; }
        @Override public void allowJoin(boolean value) { joinSettingChanges++; allowJoin = value; }
    }
}
