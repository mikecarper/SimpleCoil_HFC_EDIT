package com.simplecoil.simplecoil;

import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowTextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Host-only countdown cancellation uses the current token and never falls back to a vote or end. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = HostCountdownCancelTest.Labels.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HostCountdownCancelTest {
    private static final String TOKEN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NEXT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Test public void aCurrentNetworkHostCanCancelBeforeTheMonotonicDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.allowed(TOKEN));
            assertFalse(fixture.allowed(null));
            assertFalse(fixture.allowed(NEXT));
            assertTrue(fixture.cancel(TOKEN));
            assertEquals(1, fixture.server.cancellations);
            assertEquals(TOKEN, fixture.server.cancelledToken);
            assertEquals(0, fixture.server.ordinaryEnds);
            assertEquals(0, fixture.server.directEnds);
            assertEquals(0, fixture.udp.votes);
            assertTrue((boolean) get(fixture.activity, "mPeerOrderedEndPending"));
            assertEquals(GameOutcome.Result.UNKNOWN, get(fixture.activity, "mPeerOrderedEndOutcome"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertNull(get(fixture.activity, "mSpawnTimer"));
            assertNull(get(fixture.activity, "mGameCountdownTimer"));
            assertFalse((boolean) get(fixture.activity, "mRoundStartVoicePending"));
        }
    }

    @Test public void participantsAndOfflineGamesCannotUseTheHostOnlyDispatch() throws Exception {
        for (String disabled : new String[] {"mUseNetwork", "mIsServer", "mStartGameTimer"}) {
            try (Fixture fixture = new Fixture()) {
                set(fixture.activity, disabled, false);
                assertFalse(disabled, fixture.allowed(TOKEN));
                assertFalse(disabled, fixture.cancel(TOKEN));
                fixture.assertNoDispatchOrFreeze();
            }
        }
        try (Fixture fixture = new Fixture()) {
            set(fixture.activity, "mTcpServer", null);
            assertFalse(fixture.allowed(TOKEN));
            assertFalse(fixture.cancel(TOKEN));
            fixture.assertNoDispatchOrFreeze();
        }
    }

    @Test public void aDialogCapturedForAnOlderRoundCannotCancelTheNextRound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.allowed(TOKEN));
            set(fixture.activity, "mActivePeerRoundToken", NEXT);
            fixture.server.token = NEXT;
            assertFalse(fixture.cancel(TOKEN));
            fixture.assertNoDispatchOrFreeze();
        }
    }

    @Test public void aDialogThatExpiresAtGoCannotEndTheRunningRoundOrCastAVote() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.allowed(TOKEN));
            // The UI countdown flag can lag the authoritative elapsed-time deadline.
            fixture.server.startAt = SystemClock.elapsedRealtime() + 1;
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
            assertFalse(fixture.allowed(TOKEN));
            assertFalse(fixture.cancel(TOKEN));
            fixture.assertNoDispatchOrFreeze();
            set(fixture.activity, "mStartGameTimer", false);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            assertFalse(fixture.cancel(TOKEN));
            fixture.assertNoDispatchOrFreeze();
        }
    }

    @Test public void anAlreadyEndedCountdownCannotBeCancelledAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            assertFalse(fixture.allowed(TOKEN));
            assertFalse(fixture.cancel(TOKEN));
            fixture.assertNoDispatchOrFreeze();
        }
    }

    @Test public void theServerRechecksAuthorityAtDispatchWithoutAnOrdinaryEndFallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.rejectOnDispatch = true;
            assertTrue(fixture.allowed(TOKEN));
            assertFalse(fixture.cancel(TOKEN));
            assertEquals(1, fixture.server.cancellations);
            assertEquals(TOKEN, fixture.server.cancelledToken);
            assertEquals(0, fixture.server.ordinaryEnds);
            assertEquals(0, fixture.server.directEnds);
            assertEquals(0, fixture.udp.votes);
            assertFalse((boolean) get(fixture.activity, "mPeerOrderedEndPending"));
            assertSame(fixture.spawn, get(fixture.activity, "mSpawnTimer"));
            assertSame(fixture.countdown, get(fixture.activity, "mGameCountdownTimer"));
            assertTrue((boolean) get(fixture.activity, "mRoundStartVoicePending"));
        }
    }

    @Test public void ordinaryRoundEndRemainsUnavailableDuringTheCountdown() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertFalse((boolean) invoke(fixture.activity, "requestPeerOrderedRoundEnd", new Class<?>[0]));
            fixture.assertNoDispatchOrFreeze();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BareActivity activity = new BareActivity();
        final RecordingServer server = new RecordingServer();
        final RecordingUdp udp = new RecordingUdp();
        final CountDownTimer spawn = timer(), countdown = timer();
        final Object previousGlobals;

        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            previousGlobals = singleton.get(null);singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mGameState = Globals.GAME_STATE_ELIMINATED;
            set(activity, "mUseNetwork", true);
            set(activity, "mIsServer", true);
            set(activity, "mStartGameTimer", true);
            set(activity, "mActivePeerRoundToken", TOKEN);
            set(activity, "mTcpServer", server);
            set(activity, "mUDPListenerService", udp);
            set(activity, "mGameTimer", new Chronometer(RuntimeEnvironment.getApplication()));
            set(activity, "mSpawnTimer", spawn);
            set(activity, "mGameCountdownTimer", countdown);
            set(activity, "mRoundStartVoicePending", true);
            for (String name : new String[] {"mShotsRemainingLabelTV", "mShotsRemainingTV", "mHealthLabelTV",
                    "mRecoilLabelTV", "mRecoilModeTV"})
                set(activity, name, new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mReloadBar", new ProgressBar(RuntimeEnvironment.getApplication()));
            for (String name : new String[] {"mLobbyShotsLabelLayoutParams", "mLobbyShotsLayoutParams",
                    "mLobbyReloadLayoutParams", "mLobbyHealthLabelLayoutParams",
                    "mLobbyRecoilLabelLayoutParams", "mLobbyRecoilLayoutParams"})
                set(activity, name, new RelativeLayout.LayoutParams(100, 40));
        }

        boolean allowed(String token) throws Exception {
            return (boolean) invoke(activity, "canHostCancelCountdown", new Class<?>[] {String.class}, token);
        }

        boolean cancel(String token) throws Exception {
            return (boolean) invoke(activity, "requestPeerOrderedRoundEnd", new Class<?>[] {String.class}, token);
        }

        void assertNoDispatchOrFreeze() throws Exception {
            assertEquals(0, server.cancellations);
            assertEquals(0, server.ordinaryEnds);
            assertEquals(0, server.directEnds);
            assertEquals(0, udp.votes);
            assertFalse((boolean) get(activity, "mPeerOrderedEndPending"));
            assertSame(spawn, get(activity, "mSpawnTimer"));
            assertSame(countdown, get(activity, "mGameCountdownTimer"));
            assertTrue((boolean) get(activity, "mRoundStartVoicePending"));
        }

        @Override public void close() throws Exception {
            for (Field field : FullscreenActivity.class.getDeclaredFields()) {
                field.setAccessible(true);Object value = field.get(activity);
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
                else if (value instanceof CountDownTimer) ((CountDownTimer) value).cancel();
            }
            server.onDestroy();udp.onDestroy();
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            singleton.set(null, previousGlobals);
        }
    }

    private static final class BareActivity extends FullscreenActivity {
        @Override public <T extends View> T findViewById(int id) { return null; }
    }

    private static final class RecordingServer extends TcpServer {
        String token = TOKEN, cancelledToken;
        long startAt = SystemClock.elapsedRealtime() + 10_000;
        boolean rejectOnDispatch;
        int cancellations, ordinaryEnds, directEnds;
        @Override public boolean canCancelGameBeforeStart(String requested) {
            return token.equals(requested) && SystemClock.elapsedRealtime() < startAt;
        }
        @Override public boolean cancelGameBeforeStart(String requested) {
            cancellations++;cancelledToken = requested;
            return !rejectOnDispatch && canCancelGameBeforeStart(requested);
        }
        @Override public boolean requestOrderedRoundEnd() { ordinaryEnds++;return true; }
        @Override public void endGame() { directEnds++; }
        @Override public void sendBroadcast(Intent intent) { }
    }

    private static final class RecordingUdp extends UDPListenerService {
        int votes;
        @Override public boolean voteToEndGame(String token, boolean approve) { votes++;return true; }
        @Override public void sendBroadcast(Intent intent) { }
    }

    /** Resource-free test widgets still run real Activity cancellation and freeze logic. */
    @Implements(TextView.class)
    public static class Labels extends ShadowTextView {
        @RealObject private TextView view;
        @Implementation protected void setText(int resourceId) { view.setText("Label"); }
    }

    private static CountDownTimer timer() {
        return new CountDownTimer(10_000, 1_000) {
            @Override public void onTick(long remaining) { }
            @Override public void onFinish() { }
        };
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = FullscreenActivity.class.getDeclaredMethod(name, types);method.setAccessible(true);
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
