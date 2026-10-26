package com.simplecoil.simplecoil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** An updater must not replace a restored match before the game's UI catches up. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateActivityGuardTest {
    @Test public void onlyAnIdleForegroundPhoneAllowsInstallation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.allowed());
            set(fixture.activity, "mActivityResumed", false);
            assertFalse(fixture.allowed());
            set(fixture.activity, "mActivityResumed", true);
            for (int state : new int[] {Globals.GAME_STATE_ELIMINATED, Globals.GAME_STATE_RUNNING}) {
                Globals.getInstance().mGameState = state;
                assertFalse(fixture.allowed());
            }
        }
    }

    @Test public void restoredOrRemotelyActiveRoundsRemainProtectedWhileLocalStateIsIdle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.activity, "mMatchSession", new MatchSessionLock("192.168.1.2", "saved-round"));
            assertFalse(fixture.allowed());
            set(fixture.activity, "mMatchSession", new MatchSessionLock(null, null));
            Globals.getInstance().mLobbyRoundActive = true;
            assertFalse(fixture.allowed());
            Globals.getInstance().mLobbyRoundActive = false;
            Globals.getInstance().mWaitingForLobbyRoundEnd = true;
            assertFalse(fixture.allowed());
            Globals.getInstance().mWaitingForLobbyRoundEnd = false;
            assertTrue(fixture.allowed());
        }
    }

    @Test public void announcedStartsPendingReportsAndQrPairingCannotBeInterrupted() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.activity, "mIsServer", true);
            set(fixture.activity, "mTcpServer", new PendingStartServer());
            assertFalse(fixture.allowed());
            set(fixture.activity, "mTcpServer", null);
            for (String field : new String[] {"mPeerOrderedEndPending", "mClientRoundEndPending",
                    "mTriggerPairingActive", "mCollectingNewWeaponCandidates",
                    "mTeamAssignmentQrScannerActive", "mPowerupQrScannerActive"}) {
                set(fixture.activity, field, true);
                assertFalse(field, fixture.allowed());
                set(fixture.activity, field, false);
            }
            assertTrue(fixture.allowed());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FullscreenActivity activity = new FullscreenActivity();
        final Object previousGlobals;
        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);
            previousGlobals = singleton.get(null);
            singleton.set(null, null);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mActivityResumed", true);
        }
        boolean allowed() throws Exception {
            Method guard = FullscreenActivity.class.getDeclaredMethod("canInstallAppUpdate");
            guard.setAccessible(true);
            return (boolean) guard.invoke(activity);
        }
        @Override public void close() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);
            singleton.set(null, previousGlobals);
        }
    }

    private static final class PendingStartServer extends TcpServer {
        @Override boolean hasPendingOrAnnouncedStart() { return true; }
    }

    private static void set(Object activity, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
}
