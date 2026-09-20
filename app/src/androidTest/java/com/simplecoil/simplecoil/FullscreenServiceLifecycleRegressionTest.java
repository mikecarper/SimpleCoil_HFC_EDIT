package com.simplecoil.simplecoil;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    private static final class RecordingUDPService extends UDPListenerService { }

    private static final class RecordingTcpClient extends TcpClient { }

    private static final class RecordingTcpServer extends TcpServer { }
}
