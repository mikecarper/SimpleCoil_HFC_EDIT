package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Queued discovery errors must not destroy a registered, live phone-host lobby. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class UDPFailoverUiTest {
    private static final String[] FAILURES = {
            NetMsg.NETMSG_FAILEDTOJOIN, NetMsg.NETMSG_SERVERUNREACHABLE
    };

    @Test public void sameOrUnscopedQueuedFailuresKeepARegisteredPhoneLobby() throws Exception {
        for (String action : FAILURES) {
            for (boolean scoped : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture()) {
                    Intent failure = new Intent(action);
                    if (scoped) failure.putExtra(UDPListenerService.INTENT_SERVERIP,
                            fixture.host.getHostAddress());
                    fixture.receive(failure);

                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mReady"));
                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mLobbyJoined"));
                    assertTrue(fixture.client.hasLobbyConnection());
                    assertTrue(fixture.client.hasClientSession(fixture.host));
                    assertSame(fixture.writer, get(TcpClient.class, fixture.client, "out"));
                    assertNull(get(FullscreenActivity.class, fixture.activity, "mLobbyNotice"));
                    SharedLobby directory = (SharedLobby) get(FullscreenActivity.class,
                            fixture.activity, "mSharedLobby");
                    assertFalse(directory.isBackedOff(fixture.host, SystemClock.elapsedRealtime()));
                }
            }
        }
    }

    @Test public void anUnregisteredDisconnectedOrExpiredConnectionCannotHideFailure() throws Exception {
        for (String action : FAILURES) {
            for (String missing : new String[] {"registration", "writer", "session", "traffic"}) {
                try (Fixture fixture = new Fixture()) {
                    if (missing.equals("registration"))
                        set(TcpClient.class, fixture.client, "mLobbyRegistered", false);
                    else if (missing.equals("writer"))
                        set(TcpClient.class, fixture.client, "out", null);
                    else if (missing.equals("session"))
                        set(TcpClient.class, fixture.client, "keepListening", false);
                    else
                        set(TcpClient.class, fixture.client, "mLastServerTrafficAt",
                                SystemClock.elapsedRealtime() - SharedLobby.EXPIRY_MS);
                    assertFalse(missing + " suppressed a real failure",
                            fixture.ignores(new Intent(action), false));
                }
            }
        }
    }

    @Test public void aDifferentEndpointOrHostChangeStillAllowsFailureHandling() throws Exception {
        for (String changing : new String[] {"failure", "target", "global", "session", "handoff"}) {
            try (Fixture fixture = new Fixture()) {
                Intent failure = new Intent(NetMsg.NETMSG_FAILEDTOJOIN);
                if (changing.equals("failure"))
                    failure.putExtra(UDPListenerService.INTENT_SERVERIP, fixture.other.getHostAddress());
                else if (changing.equals("target"))
                    set(FullscreenActivity.class, fixture.activity, "mLobbyTarget", fixture.other);
                else if (changing.equals("global"))
                    Globals.getInstance().mServerIP = fixture.other;
                else if (changing.equals("session"))
                    set(TcpClient.class, fixture.client, "mSessionServerAddress", fixture.other);
                else
                    set(FullscreenActivity.class, fixture.activity, "mLobbyTakeoverPending", true);
                assertFalse(changing + " was mistaken for the connected lobby", fixture.ignores(failure, false));
            }
        }
    }

    @Test public void tcpTerminalAndOtherControlEventsAreNeverIgnored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Intent unreachable = new Intent(NetMsg.NETMSG_SERVERUNREACHABLE);
            assertTrue(fixture.ignores(unreachable, false));
            assertFalse("A current TCP terminal event was hidden", fixture.ignores(unreachable, true));
            for (String action : new String[] {NetMsg.NETMSG_SERVERCANCEL, NetMsg.NETMSG_VERSIONERROR,
                    NetMsg.NETMSG_ENDGAME, NetMsg.NETMSG_QRJOINREJECTED})
                assertFalse(fixture.ignores(new Intent(action), false));
            fixture.client.stopTcpClient();
            assertFalse("A stopped TCP session was treated as healthy", fixture.ignores(unreachable, false));
        }
    }

    @Test public void aTrueTcpTerminalStillMarksTheRunningPlayerDisconnected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            RecordingImageView status = new RecordingImageView(RuntimeEnvironment.getApplication());
            set(FullscreenActivity.class, fixture.activity, "mNetworkStatusIV", status);
            Method finish = TcpClient.class.getDeclaredMethod("finishServerSession", String.class);
            finish.setAccessible(true);
            finish.invoke(fixture.client, NetMsg.NETMSG_SERVERUNREACHABLE);
            Intent terminal = (Intent) get(TcpClient.class, fixture.client, "mPendingTerminalEvent");
            assertFalse(fixture.client.hasClientSession(fixture.host));
            fixture.receive(new Intent(terminal));
            assertFalse("A real TCP failure was ignored",
                    (boolean) get(FullscreenActivity.class, fixture.activity, "mLobbyJoined"));
            assertTrue("A network drop ended an active round",
                    (boolean) get(FullscreenActivity.class, fixture.activity, "mReady"));
            assertTrue(status.disconnected);
            assertNull(fixture.client.consumePendingTerminalEvent());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FullscreenActivity activity = new FullscreenActivity();
        final TcpClient client = new RecordingClient();
        final DataOutputStream writer = new DataOutputStream(new ByteArrayOutputStream());
        final InetAddress host = InetAddress.getByName("192.168.1.2");
        final InetAddress other = InetAddress.getByName("192.168.1.3");
        final Object previousGlobals;

        Fixture() throws Exception {
            previousGlobals = get(Globals.class, null, "mInstance");
            set(Globals.class, null, "mInstance", null);
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_NONE;
            globals.mServerIP = host;
            set(FullscreenActivity.class, activity, "mUseNetwork", true);
            set(FullscreenActivity.class, activity, "mReady", true);
            set(FullscreenActivity.class, activity, "mLobbyJoined", true);
            set(FullscreenActivity.class, activity, "mNetworkReceiverRegistered", true);
            set(FullscreenActivity.class, activity, "mLobbyTarget", host);
            set(FullscreenActivity.class, activity, "mTcpClient", client);
            set(TcpClient.class, client, "keepListening", true);
            set(TcpClient.class, client, "mSessionServerAddress", host);
            set(TcpClient.class, client, "mLobbyRegistered", true);
            set(TcpClient.class, client, "mLobbyHostAddress", host);
            set(TcpClient.class, client, "mLastServerTrafficAt", SystemClock.elapsedRealtime());
            set(TcpClient.class, client, "out", writer);
        }

        void receive(Intent failure) throws Exception {
            ((BroadcastReceiver) get(FullscreenActivity.class, activity, "mUDPUpdateReceiver"))
                    .onReceive(activity, failure);
        }

        boolean ignores(Intent failure, boolean terminal) throws Exception {
            Method method = FullscreenActivity.class.getDeclaredMethod("shouldIgnoreStaleDiscoveryFailure",
                    Intent.class, boolean.class);
            method.setAccessible(true);
            return (boolean) method.invoke(activity, failure, terminal);
        }

        @Override public void close() throws Exception {
            for (Field field : FullscreenActivity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
            }
            client.onDestroy();
            set(Globals.class, null, "mInstance", previousGlobals);
        }
    }

    private static final class RecordingClient extends TcpClient {
        @Override public void sendBroadcast(Intent intent) { }
    }

    private static final class RecordingImageView extends ImageView {
        boolean disconnected;
        RecordingImageView(Context context) { super(context); }
        @Override public void setImageResource(int resource) {
            disconnected = resource == R.drawable.ic_network_disconnected_24dp;
        }
    }

    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
