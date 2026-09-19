package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.Switch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private long originalRespawnTime;
    private boolean originalUseGPS;

    @Before
    public void setUp() {
        Globals globals = Globals.getInstance();
        originalPlayerID = globals.mPlayerID;
        originalGameState = globals.mGameState;
        originalGameLimit = globals.mGameLimit;
        originalRespawnTime = globals.mRespawnTime;
        originalUseGPS = globals.mUseGPS;
        globals.mGameState = Globals.GAME_STATE_NONE;
        scenario = ActivityScenario.launch(DedicatedServerActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            // Release the activity's real startup bindings before installing fakes.
            invoke("unbindUDPService");
            invoke("unbindTcpServerService");
            current.stopService(new Intent(current, UDPListenerService.class));
            current.stopService(new Intent(current, TcpServer.class));
            tcp = new RecordingTcpServer();
            udp = new RecordingUDPService();
            set("mTcpServer", tcp);
            set("mUDPListenerService", udp);
            globals.mUseGPS = false;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mRespawnTime = 10;
        });
    }

    @After
    public void tearDown() {
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
        globals.mRespawnTime = originalRespawnTime;
        globals.mUseGPS = originalUseGPS;
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
    public void localStartRequestsOnceAndItsAcknowledgementDoesNotRestartTheTimer() {
        scenario.onActivity(current -> {
            invoke("startGame");
            CountDownTimer first = (CountDownTimer) get("mSpawnTimer");
            receive(NetMsg.NETMSG_STARTGAME);
            assertEquals(1, tcp.gameStarts);
            assertNotNull(first);
            assertSame(first, get("mSpawnTimer"));
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
    public void currentUdpBindingStillStartsAndDisconnectsTheServer() { assertCurrentBindingWorks(false); }

    @Test
    public void udpBindingDuringARoundRestoresTheClosedLobbySetting() { assertJoinSettingRestored(false); }

    @Test
    public void udpBindingDuringARoundRestoresTheOpenLobbySetting() { assertJoinSettingRestored(true); }

    private void assertJoinSettingRestored(boolean allowJoin) {
        scenario.onActivity(current -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            ((Switch) current.findViewById(R.id.allow_join_switch)).setChecked(allowJoin);
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
        int listenerStarts;
        boolean acceptStart = true;
        boolean dedicated;

        @Override public boolean startGame() { gameStarts++; return acceptStart; }
        @Override void startTcpServer() { listenerStarts++; }
        @Override public void setDedicated(boolean value) { dedicated = value; }
        @Override public void sendTCPMessageAll(String message) { }
        @Override public void sendAllGameInfo(int playerID) { }
    }

    private static final class RecordingUDPService extends UDPListenerService {
        int listenerStarts;
        int joinSettingChanges;
        boolean allowJoin;

        @Override public void createServer() { listenerStarts++; }
        @Override public void allowJoin(boolean value) { joinSettingChanges++; allowJoin = value; }
    }
}
