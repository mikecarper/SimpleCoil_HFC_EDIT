package com.simplecoil.simplecoil;

import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.simplecoil.protocol.RelayMesh;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in check against an isolated laptop host, using the real phone TCP client. */
@RunWith(AndroidJUnit4.class)
public class LaptopRelayHardwareTest {
    @Test public void actualClientRejoinsSameRoundThroughNeighbor() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Opt-in laptop radio test", "true".equals(args.getString("relay_laptop_probe")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean bridge = "bridge".equals(args.getString("relay_role"));
        Globals globals = Globals.getInstance();
        globals.mPlayerID = (byte) (bridge ? 1 : 17);
        globals.mPlayerName = context.getSharedPreferences(FullscreenActivity.PREF_NAME, 0).getString("PlayerName", "Relay phone");
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mServerIP = InetAddress.getByName(args.getString("relay_laptop_ip"));
        globals.mLocalLobbyReady = true;
        AtomicReference<TcpClient> client = new AtomicReference<>();
        CountDownLatch bound = new CountDownLatch(1), started = new CountDownLatch(1), ended = new CountDownLatch(1);
        BroadcastReceiver events = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (GameRelayService.CAPABILITIES_CHANGED.equals(intent.getAction())) {
                    if (client.get() != null) client.get().sendLobbyReady();
                } else if (NetMsg.NETMSG_STARTGAME.equals(intent.getAction())) {
                    globals.mGameState = Globals.GAME_STATE_RUNNING;started.countDown();
                } else if (NetMsg.NETMSG_ENDGAME.equals(intent.getAction())) ended.countDown();
            }
        };
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                client.set(((TcpClient.LocalBinder) binder).getService());bound.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { client.set(null); }
        };
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean wasWifiEnabled = wifi.isWifiEnabled();
        String savedRelay = context.getSharedPreferences("GameRelay", 0).getString("config", null);
        IntentFilter filter = new IntentFilter(GameRelayService.CAPABILITIES_CHANGED);
        filter.addAction(NetMsg.NETMSG_STARTGAME);filter.addAction(NetMsg.NETMSG_ENDGAME);
        context.registerReceiver(events, filter);
        boolean binding = false;
        try {
            GameRelayService.initialize(context);
            binding = context.bindService(new Intent(context, TcpClient.class), connection, Context.BIND_AUTO_CREATE);
            assertTrue(binding);assertTrue(bound.await(5, TimeUnit.SECONDS));client.get().startTcpClient();
            await(() -> client.get().hasLobbyConnection() && client.get().isClockSynchronized(), 25_000, "Phone did not join the laptop");
            assertFalse("Working Wi-Fi should remain primary", socket(client.get()) instanceof RelayMesh.VirtualSocket);
            report("NATIVE_READY role=" + (bridge ? "bridge" : "client"));
            assertTrue("Laptop did not start the test round", started.await(90, TimeUnit.SECONDS));
            assertNotNull(globals.mRoundRoster);String token = globals.mRoundRoster.token;
            if (bridge) {
                assertTrue("Host end command did not reach the bridge", ended.await(120, TimeUnit.SECONDS));
                // A real lobby keeps its relay service running after the round.
                // Keep this test bridge alive while the last end frame is forwarded.
                SystemClock.sleep(5000);
                report("BRIDGE_PASS");
            } else {
                SystemClock.sleep(2000);
                assertTrue(wifi.setWifiEnabled(false));
                await(() -> !wifi.isWifiEnabled(), 15_000, "Wi-Fi radio did not turn off");
                report("WIFI_OFF");
                await(() -> client.get().hasLobbyConnection() && socket(client.get()) instanceof RelayMesh.VirtualSocket,
                        70_000, "Actual TCP client did not fall back through the neighbor");
                assertEquals("Fallback changed the match", token, globals.mRoundRoster.token);
                client.get().sendBatteryStatus(new JSONObject().put("phone", 37).put("charging", false)
                        .put("guns", new org.json.JSONArray()));
                report("RELAY_CONNECTED sameRound=true");
                await(() -> "Relay verified".equals(globals.mPlayerName), 30_000, "Laptop name edit did not reach the out-of-range phone");
                assertFalse(wifi.isWifiEnabled());
                assertEquals(token, globals.mRoundRoster.token);
                report("HOST_EDIT_PASS wifiEnabled=false sameRound=true");
                assertTrue("Host end command did not reach the out-of-range phone", ended.await(30, TimeUnit.SECONDS));
                report("CLIENT_PASS");
            }
        } finally {
            if (client.get() != null) client.get().stopTcpClient();
            if (binding) context.unbindService(connection);
            context.unregisterReceiver(events);
            context.stopService(new Intent(context, GameRelayService.class));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            context.getSharedPreferences("GameRelay", 0).edit().putString("config", savedRelay).commit();
            if (wasWifiEnabled && !wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
            if (wasWifiEnabled) { await(wifi::isWifiEnabled, 20_000, "Could not restore Wi-Fi");wifi.reconnect(); }
        }
    }
    private Socket socket(TcpClient client) throws Exception {
        Field field = TcpClient.class.getDeclaredField("mActiveSocket");field.setAccessible(true);return (Socket) field.get(client);
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, long timeout, String failure) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.get() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue(failure, condition.get());
    }
    private void report(String text) {
        Bundle status = new Bundle();status.putString("stream", "LAPTOP_RELAY " + text + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
