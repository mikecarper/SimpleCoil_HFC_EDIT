package com.simplecoil.simplecoil;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises late network-service callbacks without opening network sockets. */
@RunWith(AndroidJUnit4.class)
public class FullscreenServiceLifecycleRegressionTest {
    private static final int SERVICE_UDP = 0;
    private static final int SERVICE_TCP_CLIENT = 1;
    private static final int SERVICE_TCP_SERVER = 2;

    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity activity;
    private RecordingUDPService udp;
    private RecordingTcpClient tcpClient;
    private RecordingTcpServer tcpServer;
    private int originalGameState;
    private boolean originalUseGPS;

    @Before
    public void setUp() {
        Globals globals = Globals.getInstance();
        originalGameState = globals.mGameState;
        originalUseGPS = globals.mUseGPS;
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mUseGPS = false;
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            // Replace the activity's real started bindings with synthetic ones.
            invoke("unbindUDPService");
            invoke("unbindTcpClientService");
            invoke("unbindTcpServerService");
            current.stopService(new Intent(current, UDPListenerService.class));
            current.stopService(new Intent(current, TcpClient.class));
            current.stopService(new Intent(current, TcpServer.class));
            udp = new RecordingUDPService();
            tcpClient = new RecordingTcpClient();
            tcpServer = new RecordingTcpServer();
            set("mUDPListenerService", udp);
            set("mTcpClient", tcpClient);
            set("mTcpServer", tcpServer);
            set("mUseNetwork", false);
            set("mNetworkReceiverRegistered", false);
            set("mReady", true);
            set("mIsServer", true);
        });
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.onActivity(current -> {
                // Synthetic callbacks were never registered with Android.
                set("mUDPServiceBound", false);
                set("mTcpClientServiceBound", false);
                set("mTcpServerServiceBound", false);
                set("mUDPServiceConnection", null);
                set("mTcpClientServiceConnection", null);
                set("mTcpServerServiceConnection", null);
            });
            scenario.close();
        }
        Globals globals = Globals.getInstance();
        globals.mGameState = originalGameState;
        globals.mUseGPS = originalUseGPS;
    }

    @Test
    public void staleUdpDisconnectCannotEndTheCurrentRound() {
        scenario.onActivity(current -> {
            ServiceConnection old = beginBinding(SERVICE_UDP);
            ServiceConnection replacement = beginBinding(SERVICE_UDP);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;

            old.onServiceDisconnected(null);

            assertSame(udp, get(serviceField(SERVICE_UDP)));
            assertSame(replacement, get(connectionField(SERVICE_UDP)));
            assertTrue((boolean) get("mReady"));
            assertTrue((boolean) get("mIsServer"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void staleConnectionsCannotReplaceCurrentNetworkServices() {
        assertStaleConnectIgnored(SERVICE_UDP);
        assertStaleConnectIgnored(SERVICE_TCP_CLIENT);
        assertStaleConnectIgnored(SERVICE_TCP_SERVER);
    }

    private void assertStaleConnectIgnored(int service) {
        scenario.onActivity(current -> {
            ServiceConnection old = beginBinding(service);
            ServiceConnection replacement = beginBinding(service);

            old.onServiceConnected(null, binderFor(service));

            assertSame(serviceFor(service), get(serviceField(service)));
            assertSame(replacement, get(connectionField(service)));
        });
    }

    @Test
    public void staleDisconnectsCannotClearCurrentNetworkServices() {
        assertStaleDisconnectIgnored(SERVICE_UDP);
        assertStaleDisconnectIgnored(SERVICE_TCP_CLIENT);
        assertStaleDisconnectIgnored(SERVICE_TCP_SERVER);
    }

    private void assertStaleDisconnectIgnored(int service) {
        scenario.onActivity(current -> {
            ServiceConnection old = beginBinding(service);
            beginBinding(service);

            old.onServiceDisconnected(null);

            assertSame(serviceFor(service), get(serviceField(service)));
        });
    }

    @Test
    public void cancelledConnectionsCannotRestoreNetworkServices() {
        assertCancelledConnectIgnored(SERVICE_UDP);
        assertCancelledConnectIgnored(SERVICE_TCP_CLIENT);
        assertCancelledConnectIgnored(SERVICE_TCP_SERVER);
    }

    private void assertCancelledConnectIgnored(int service) {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginBinding(service);
            set(boundField(service), false);

            callback.onServiceConnected(null, binderFor(service));

            assertSame(serviceFor(service), get(serviceField(service)));
            assertFalse((boolean) get(boundField(service)));
        });
    }

    @Test
    public void currentConnectionsStillAttachAndDisconnectServices() {
        assertCurrentConnectionWorks(SERVICE_UDP);
        assertCurrentConnectionWorks(SERVICE_TCP_CLIENT);
        assertCurrentConnectionWorks(SERVICE_TCP_SERVER);
    }

    @Test
    public void explicitNetworkShutdownStopsStartedServices() {
        scenario.onActivity(current -> {
            current.startService(new Intent(current, UDPListenerService.class));
            current.startService(new Intent(current, TcpClient.class));
            current.startService(new Intent(current, TcpServer.class));
            assertTrue(isServiceRunning(current, UDPListenerService.class));
            assertTrue(isServiceRunning(current, TcpClient.class));
            assertTrue(isServiceRunning(current, TcpServer.class));
            invoke("stopNetworkServices");
        });
        waitForServiceToStop(UDPListenerService.class);
        waitForServiceToStop(TcpClient.class);
        waitForServiceToStop(TcpServer.class);
    }

    @Test
    public void networkServicesDoNotRequestStickyRestart() {
        assertEquals(android.app.Service.START_NOT_STICKY,
                udp.onStartCommand(new Intent(), 0, 1));
        assertEquals(android.app.Service.START_NOT_STICKY,
                tcpClient.onStartCommand(new Intent(), 0, 1));
        assertEquals(android.app.Service.START_NOT_STICKY,
                tcpServer.onStartCommand(new Intent(), 0, 1));
    }

    @Test
    public void pausingAndStoppingAJoinedScreenKeepsItsClockEstimateAndNetworkBindings() {
        assertBackgroundSessionRetained(false);
    }

    @Test
    public void pausingAndStoppingThePhoneHostKeepsItsNetworkBindings() {
        assertBackgroundSessionRetained(true);
    }

    private void assertBackgroundSessionRetained(boolean hosting) {
        final ServiceConnection[] callbacks = new ServiceConnection[3];
        final GameClock[] clock = new GameClock[1];
        scenario.onActivity(current -> {
            ((android.os.Handler) get("mLobbyHandler")).removeCallbacksAndMessages(null);
            set("mUseNetwork", true);
            set("mReady", true);
            set("mLobbyJoined", true);
            set("mIsServer", hosting);
            for (int service = SERVICE_UDP; service <= SERVICE_TCP_SERVER; service++)
                callbacks[service] = beginBinding(service);
            try {
                Field estimate = TcpClient.class.getDeclaredField("mGameClock");
                estimate.setAccessible(true);
                clock[0] = (GameClock) estimate.get(tcpClient);
                for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++)
                    assertTrue(clock[0].record(1000 + sample, 6010 + sample,
                            6010 + sample, 1020 + sample));
                Field synchronizedClock = TcpClient.class.getDeclaredField("mClockSynchronized");
                synchronizedClock.setAccessible(true);
                synchronizedClock.setBoolean(tcpClient, true);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });

        scenario.moveToState(Lifecycle.State.STARTED);
        scenario.moveToState(Lifecycle.State.CREATED);

        scenario.onActivity(current -> {
            assertFalse((boolean) get("mActivityResumed"));
            assertTrue((boolean) get("mReady"));
            assertTrue((boolean) get("mLobbyJoined"));
            assertEquals(hosting, get("mIsServer"));
            for (int service = SERVICE_UDP; service <= SERVICE_TCP_SERVER; service++) {
                assertTrue((boolean) get(boundField(service)));
                assertSame(callbacks[service], get(connectionField(service)));
                assertSame(serviceFor(service), get(serviceField(service)));
            }
            assertEquals("Backgrounding stopped the TCP clock worker", 0, tcpClient.stops);
            assertEquals("Backgrounding stopped the phone host", 0, tcpServer.stops);
            assertEquals("Backgrounding stopped UDP lobby maintenance", 0, udp.stops);
            assertTrue(tcpClient.isClockSynchronized());
            assertEquals(GameClock.SAMPLES_PER_SYNC, clock[0].samples());
            assertEquals(2000, clock[0].toLocalTime(7000));
        });
    }

    @Test
    public void peerHostNameChangePublishesTheLobbySnapshot() {
        scenario.onActivity(current -> {
            set("mReady", true);
            set("mIsServer", true);

            current.publishPlayerNameChange();

            assertEquals(1, tcpServer.gameInfoRequests.size());
            assertEquals(TcpServer.SEND_ALL, (int) tcpServer.gameInfoRequests.get(0));
            assertEquals(0, tcpClient.playerNameChangeCount);
        });
    }

    @Test
    public void joinedPlayerNameChangeUsesTheTcpClient() {
        scenario.onActivity(current -> {
            set("mReady", true);
            set("mIsServer", false);

            current.publishPlayerNameChange();

            assertTrue(tcpServer.gameInfoRequests.isEmpty());
            assertEquals(1, tcpClient.playerNameChangeCount);
        });
    }

    private void assertCurrentConnectionWorks(int service) {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginBinding(service);
            callback.onServiceConnected(null, binderFor(service));
            assertSame(serviceFor(service), get(serviceField(service)));

            callback.onServiceDisconnected(null);
            assertNull(get(serviceField(service)));
        });
    }

    private void waitForServiceToStop(Class<?> serviceClass) {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (isServiceRunning(activity, serviceClass) && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(10);
        assertFalse(serviceClass.getSimpleName() + " remained running", isServiceRunning(activity, serviceClass));
    }

    private static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null)
            return false;
        String className = serviceClass.getName();
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (info.service != null && className.equals(info.service.getClassName()))
                return true;
        }
        return false;
    }

    private ServiceConnection beginBinding(int service) {
        ServiceConnection callback = (ServiceConnection) invoke(factoryMethod(service));
        set(connectionField(service), callback);
        set(boundField(service), true);
        return callback;
    }

    private IBinder binderFor(int service) {
        switch (service) {
            case SERVICE_UDP:
                return udp.onBind(new Intent());
            case SERVICE_TCP_CLIENT:
                return tcpClient.onBind(new Intent());
            case SERVICE_TCP_SERVER:
                return tcpServer.onBind(new Intent());
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private Object serviceFor(int service) {
        switch (service) {
            case SERVICE_UDP:
                return udp;
            case SERVICE_TCP_CLIENT:
                return tcpClient;
            case SERVICE_TCP_SERVER:
                return tcpServer;
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private static String serviceField(int service) {
        switch (service) {
            case SERVICE_UDP:
                return "mUDPListenerService";
            case SERVICE_TCP_CLIENT:
                return "mTcpClient";
            case SERVICE_TCP_SERVER:
                return "mTcpServer";
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private static String connectionField(int service) {
        switch (service) {
            case SERVICE_UDP:
                return "mUDPServiceConnection";
            case SERVICE_TCP_CLIENT:
                return "mTcpClientServiceConnection";
            case SERVICE_TCP_SERVER:
                return "mTcpServerServiceConnection";
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private static String boundField(int service) {
        switch (service) {
            case SERVICE_UDP:
                return "mUDPServiceBound";
            case SERVICE_TCP_CLIENT:
                return "mTcpClientServiceBound";
            case SERVICE_TCP_SERVER:
                return "mTcpServerServiceBound";
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private static String factoryMethod(int service) {
        switch (service) {
            case SERVICE_UDP:
                return "createUDPServiceConnection";
            case SERVICE_TCP_CLIENT:
                return "createTcpClientServiceConnection";
            case SERVICE_TCP_SERVER:
                return "createTcpServerServiceConnection";
            default:
                throw new AssertionError("Unknown service " + service);
        }
    }

    private Object get(String name) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void set(String name, Object value) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Object invoke(String name) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingUDPService extends UDPListenerService {
        int stops;

        @Override void stopListen() { stops++; }

        @Override
        public void startGameInviteListener() {
            // Lifecycle tests use a detached service double, not a real UDP socket.
        }
    }

    private static final class RecordingTcpClient extends TcpClient {
        int playerNameChangeCount;
        int stops;

        @Override public void stopTcpClient() { stops++; }

        @Override
        public void sendPlayerNameChange() {
            playerNameChangeCount++;
        }
    }

    private static final class RecordingTcpServer extends TcpServer {
        final List<Integer> gameInfoRequests = new ArrayList<>();
        int stops;

        @Override public void stopTcpServer() { stops++; }

        @Override
        public void sendAllGameInfo(int id) {
            gameInfoRequests.add(id);
        }
    }
}
