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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** An updater must not replace a restored match before the game's UI catches up. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateActivityGuardTest {
    @Test public void laptopUpdatesUseOnlyTheFreshRegisteredSelectedLaptopEndpoint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UpdateClient client = new UpdateClient();
            set(fixture.activity, "mUseNetwork", true);
            set(fixture.activity, "mTcpClient", client);
            Globals.getInstance().mServerIP = client.host;
            Globals.getInstance().mMapTilePort = 19512;
            LaptopUpdateSource.Endpoint endpoint = fixture.endpoint();
            assertNotNull(endpoint);
            assertEquals(client.host, endpoint.host);
            assertEquals(19512, endpoint.port);
            Globals.getInstance().mMapTilePort = 0;
            assertEquals(17512, fixture.endpoint().port);
            for (SharedLobby.Kind kind : new SharedLobby.Kind[] {SharedLobby.Kind.AUTO_PHONE,
                    SharedLobby.Kind.MANUAL_PHONE, SharedLobby.Kind.DEDICATED_PHONE}) {
                client.kind = kind;
                assertNull("A phone host was treated as a laptop", fixture.endpoint());
            }
            client.kind = SharedLobby.Kind.LAPTOP;
            client.registered = false;
            assertNull(fixture.endpoint());
            client.registered = true;
            client.connected = false;
            assertNull(fixture.endpoint());
            client.connected = true;
            Globals.getInstance().mServerIP = InetAddress.getByName("192.168.1.3");
            assertNull("An old TCP connection supplied the newly selected host", fixture.endpoint());
            Globals.getInstance().mServerIP = client.host;
            set(fixture.activity, "mIsServer", true);
            assertNull(fixture.endpoint());
            set(fixture.activity, "mIsServer", false);
            set(fixture.activity, "mUseNetwork", false);
            assertNull(fixture.endpoint());
        }
    }

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
        LaptopUpdateSource.Endpoint endpoint() throws Exception {
            Method method = FullscreenActivity.class.getDeclaredMethod("connectedLaptopUpdateEndpoint");
            method.setAccessible(true);
            return (LaptopUpdateSource.Endpoint) method.invoke(activity);
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

    private static final class UpdateClient extends TcpClient {
        final InetAddress host;
        boolean connected = true;
        boolean registered = true;
        SharedLobby.Kind kind = SharedLobby.Kind.LAPTOP;
        UpdateClient() throws Exception { host = InetAddress.getByName("192.168.1.2"); }
        @Override public boolean isDedicatedServer() { return kind.dedicated; }
        @Override synchronized boolean hasClientSession(InetAddress selected) {
            return connected && host.equals(selected);
        }
        @Override synchronized SharedLobby.Host lobbyHost(long now) {
            return connected && registered ? new SharedLobby.Host(host, kind, false, now, 0) : null;
        }
    }

    private static void set(Object activity, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
}
