package com.simplecoil.simplecoil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AdbUpdateActivityGuardTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String HOST = "192.168.1.44";

    @Test public void adbRequiresTheActualConnectedLaptopAndTheCompleteIdleGuard() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.activity.canPrepareAdbUpdate(HOST));
            assertFalse(fixture.activity.canPrepareAdbUpdate("192.168.1.45"));
            fixture.client.connected = false;
            assertFalse(fixture.activity.canPrepareAdbUpdate(HOST));
            fixture.client.connected = true;
            fixture.client.kind = SharedLobby.Kind.MANUAL_PHONE;
            assertFalse(fixture.activity.canPrepareAdbUpdate(HOST));
            fixture.client.kind = SharedLobby.Kind.LAPTOP;
            fixture.activity.focused = false;
            assertFalse(fixture.activity.canPrepareAdbUpdate(HOST));
            fixture.activity.focused = true;
            for (String field : new String[] {"mGrenadePairingInProgress",
                    "mScanningSecondary", "mPeerOrderedEndPending", "mClientRoundEndPending",
                    "mTriggerPairingActive", "mCollectingNewWeaponCandidates",
                    "mTeamAssignmentQrScannerActive", "mPowerupQrScannerActive"}) {
                set(fixture.activity, field, true);
                assertFalse(field, fixture.activity.canPrepareAdbUpdate(HOST));
                set(fixture.activity, field, false);
            }
            // This flag starts true before the first game; a real countdown also has an active round.
            set(fixture.activity, "mStartGameTimer", true);
            assertTrue(fixture.activity.canPrepareAdbUpdate(HOST));
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            assertFalse(fixture.activity.canPrepareAdbUpdate(HOST));
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(fixture.activity, "mMatchSession", new MatchSessionLock(HOST, "saved-round"));
            assertFalse(fixture.activity.canPrepareAdbUpdate(HOST));
        }
    }

    @Test public void leaseBlocksLocalEntryAndSelfInstallButCanStillBeAttested() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.selfInstallAllowed());
            assertEquals("ready", fixture.gate.prepare(NONCE, HOST, fixture.activity.canPrepareAdbUpdate(HOST)));
            assertFalse(fixture.selfInstallAllowed());
            assertTrue("The lease must not reject its own final idle check", fixture.activity.canPrepareAdbUpdate(HOST));
            assertTrue(fixture.activity.dispatchTouchEvent(null));
            assertTrue(fixture.activity.dispatchKeyEvent(null));
            fixture.activity.onBackPressed();
            Method start = FullscreenActivity.class.getDeclaredMethod("startGame", android.content.Intent.class);
            start.setAccessible(true);
            start.invoke(fixture.activity, new Object[] {null});
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            fixture.gate.release(NONCE, HOST);
            assertTrue(fixture.selfInstallAllowed());
        }
    }

    @Test public void anIdlePhoneAwaitingItsStoredGunCanStillUpdate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.activity, "mDeviceAddress", "AA:BB:CC:DD:EE:FF");
            set(fixture.activity, "mPrimaryReconnectActive", true);
            set(fixture.activity, "mScanning", true);
            assertTrue(fixture.activity.canPrepareAdbUpdate(HOST));
            assertTrue(fixture.selfInstallAllowed());
            set(fixture.activity, "mCollectingNewWeaponCandidates", true);
            assertFalse("An explicit new-weapon pairing must remain protected",
                    fixture.activity.canPrepareAdbUpdate(HOST));
        }
    }

    @Test public void onlyTheLiveSelectedLaptopsManagementDefersSelfInstallation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Globals.getInstance().mAdbManagedUpdates = true;
            assertFalse(fixture.selfInstallAllowed());
            assertFalse(AdbUpdateReceiver.held());
            assertTrue(fixture.activity.canPrepareAdbUpdate(HOST));
            fixture.client.connected = false;
            assertTrue("A disconnected laptop cannot suppress the fallback updater", fixture.selfInstallAllowed());
            fixture.client.connected = true;
            Globals.getInstance().mServerIP = InetAddress.getByName("192.168.1.45");
            assertTrue("A stale management flag cannot refer to a different selected host", fixture.selfInstallAllowed());
            Globals.getInstance().mServerIP = fixture.client.host;
            fixture.client.kind = SharedLobby.Kind.MANUAL_PHONE;
            assertTrue("A phone host cannot suppress the fallback updater", fixture.selfInstallAllowed());
            fixture.client.kind = SharedLobby.Kind.LAPTOP;
            Globals.getInstance().mAdbManagedUpdates = false;
            assertTrue(fixture.selfInstallAllowed());
        }
    }

    private static final class FocusedActivity extends FullscreenActivity {
        boolean focused = true;
        @Override public boolean hasWindowFocus() { return focused; }
    }

    private static final class UpdateClient extends TcpClient {
        final InetAddress host = InetAddress.getByName(HOST);
        boolean connected = true;
        SharedLobby.Kind kind = SharedLobby.Kind.LAPTOP;
        UpdateClient() throws Exception { }
        @Override public boolean isDedicatedServer() { return kind.dedicated; }
        @Override synchronized boolean hasClientSession(InetAddress selected) {
            return connected && host.equals(selected);
        }
        @Override synchronized SharedLobby.Host lobbyHost(long now) {
            return connected ? new SharedLobby.Host(host, kind, false, now, 0) : null;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FocusedActivity activity = new FocusedActivity();
        final UpdateClient client = new UpdateClient();
        final AdbUpdateGate gate;
        final Object previousGlobals;
        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);
            previousGlobals = singleton.get(null);
            singleton.set(null, null);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            Globals.getInstance().mServerIP = client.host;
            set(activity, "mActivityResumed", true);
            set(activity, "mUseNetwork", true);
            set(activity, "mTcpClient", client);
            Field field = AdbUpdateReceiver.class.getDeclaredField("GATE");
            field.setAccessible(true);
            gate = (AdbUpdateGate) field.get(null);
            clearGate();
        }
        boolean selfInstallAllowed() throws Exception {
            Method guard = FullscreenActivity.class.getDeclaredMethod("canInstallAppUpdate");
            guard.setAccessible(true);
            return (boolean) guard.invoke(activity);
        }
        private void clearGate() throws Exception {
            gate.release(NONCE, HOST);
        }
        @Override public void close() throws Exception {
            clearGate();
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);
            singleton.set(null, previousGlobals);
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
