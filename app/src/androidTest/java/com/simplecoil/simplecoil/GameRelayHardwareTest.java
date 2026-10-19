package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.simplecoil.protocol.RelayConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in test of the production service on two real phones, including complete Wi-Fi loss. */
@RunWith(AndroidJUnit4.class)
public class GameRelayHardwareTest {
    private final AtomicInteger datagrams = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final AtomicReference<BluetoothLeService> gun = new AtomicReference<>();
    private final AtomicInteger telemetry = new AtomicInteger(), gunDisconnects = new AtomicInteger();

    @Test public void gameServiceSurvivesHubAndDirectWifiLoss() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Opt-in radio test", "true".equals(args.getString("relay_service_probe")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UUID self = RelayConfig.uuid(GameRelayService.node(context));
        UUID host = RelayConfig.uuid(args.getString("relay_host_node"));
        UUID client = RelayConfig.uuid(args.getString("relay_client_node"));
        boolean owner = self.equals(host);
        assertTrue("Unexpected test phone", owner || self.equals(client));
        Map<UUID, RelayConfig.Member> members = new LinkedHashMap<>();
        members.put(host, new RelayConfig.Member(host, RelayConfig.ipv4(args.getString("relay_host_ip")),
                args.getString("relay_host_p2p"), args.getString("relay_host_bt")));
        members.put(client, new RelayConfig.Member(client, RelayConfig.ipv4(args.getString("relay_client_ip")),
                "", args.getString("relay_client_bt")));
        RelayConfig config = new RelayConfig(RelayConfig.uuid(args.getString("relay_key")), host, host, members);
        InetAddress hostIP = members.get(host).address;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assertTrue("Test must begin with Wi-Fi enabled", wifi.isWifiEnabled());
        String savedConfig = context.getSharedPreferences("GameRelay", 0).getString("config", null);
        UDPListenerService receiver = new UDPListenerService() {
            @Override public void receiveRelayedDatagram(InetAddress source, byte[] payload) {
                datagrams.incrementAndGet();
                if (owner) GameRelayService.mirror(source, 17500, payload);
            }
        };
        TcpServer server = new TcpServer() {
            @Override public boolean acceptRelaySocket(Socket socket, int port) {
                if (!owner || port != 17510) return false;
                accepted.add(socket);
                new Thread(() -> {
                    try {
                        socket.setSoTimeout(90_000);
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                        while (true) {
                            String text = input.readUTF();output.writeUTF(text);output.flush();
                            if (text.equals("DONE")) { finished.countDown();break; }
                        }
                    } catch (IOException ignored) { }
                }, "relay hardware echo").start();
                return true;
            }
        };
        List<Integer> enabled = new ArrayList<>();
        boolean gunProbe = "true".equals(args.getString("relay_gun"));
        boolean gunBound = false, gunReceiverRegistered = false;
        BroadcastReceiver gunReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (BluetoothLeService.TELEMETRY_DATA_AVAILABLE.equals(intent.getAction())) telemetry.incrementAndGet();
                else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(intent.getAction())) gunDisconnects.incrementAndGet();
                else if (gun.get() != null && BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(intent.getAction())) {
                    for (BluetoothGattService service : gun.get().getSupportedGattServices()) {
                        BluetoothGattCharacteristic characteristic = service.getCharacteristic(BluetoothLeService.UUID_RECOIL_TELEMETRY);
                        if (characteristic != null) gun.get().setCharacteristicNotification(characteristic, true);
                    }
                }
            }
        };
        ServiceConnection gunConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                BluetoothLeService service = ((BluetoothLeService.LocalBinder) binder).getService();
                gun.set(service);service.initialize();
                service.connect(context.getSharedPreferences(FullscreenActivity.PREF_NAME, 0)
                        .getString(FullscreenActivity.PREF_DEVICE_ADDRESS, ""));
            }
            @Override public void onServiceDisconnected(ComponentName name) { gun.set(null); }
        };
        try {
            if (gunProbe) {
                IntentFilter filter = new IntentFilter(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
                filter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
                filter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
                context.registerReceiver(gunReceiver, filter);gunReceiverRegistered = true;
                gunBound = context.bindService(new Intent(context, BluetoothLeService.class), gunConnection, Context.BIND_AUTO_CREATE);
                assertTrue("Could not bind the paired gun", gunBound);
                await(() -> telemetry.get() >= 3, 30_000, "Paired gun did not send telemetry; turn it on");
                report("GUN_READY telemetry=" + telemetry.get());
            }
            GameRelayService.register(receiver);GameRelayService.register(server);
            GameRelayService.configure(context, config);
            if (owner) {
                report("HOST_READY");
                assertTrue("Client did not finish both radio-loss tests", finished.await(180, TimeUnit.SECONDS));
                assertTrue("Datagrams did not reach the game service", datagrams.get() >= 20);
                report("HOST_PASS datagrams=" + datagrams.get());
            } else {
                await(() -> GameRelayService.reachable(hostIP), 60_000, "Initial game link did not connect");
                try (Socket initial = GameRelayService.open(hostIP, 17510, 5000)) { exchange(initial, "initial"); }
                report("WAITING_FOR_DIRECT_INVITATION");
                WifiP2pManager p2p = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
                WifiP2pManager.Channel channel = p2p.initialize(context, Looper.getMainLooper(), null);
                await(() -> {
                    AtomicBoolean connected = new AtomicBoolean();CountDownLatch ready = new CountDownLatch(1);
                    p2p.requestGroupInfo(channel, group -> { connected.set(group != null && !group.isGroupOwner());ready.countDown(); });
                    ready.await(3, TimeUnit.SECONDS);return connected.get();
                }, 75_000, "Wi-Fi Direct invitation was not accepted");
                // Leave Wi-Fi on, disconnect only the hub: Wi-Fi Direct must carry the traffic.
                for (WifiConfiguration network : wifi.getConfiguredNetworks()) {
                    if (network.status != WifiConfiguration.Status.DISABLED) {
                        enabled.add(network.networkId);assertTrue(wifi.disableNetwork(network.networkId));
                    }
                }
                wifi.disconnect();
                await(() -> wifi.getConnectionInfo().getNetworkId() < 0, 15_000, "Hub did not disconnect");
                SystemClock.sleep(5000);
                await(() -> GameRelayService.reachable(hostIP), 60_000, "Direct game link did not connect; accept the Android invitation");
                try (Socket direct = GameRelayService.open(hostIP, 17510, 5000)) {
                    for (int i = 0; i < 20; i++) { exchange(direct, "direct:" + i);datagram(hostIP);SystemClock.sleep(300); }
                }
                report("DIRECT_PASS hubDisconnected=true");
                int gunBeforeFallback = telemetry.get();
                // Complete Wi-Fi radio loss removes the direct group too. Bluetooth must take over.
                assertTrue(wifi.setWifiEnabled(false));
                await(() -> !wifi.isWifiEnabled(), 15_000, "Wi-Fi did not turn off");
                SystemClock.sleep(5000);
                await(() -> GameRelayService.reachable(hostIP), 60_000, "Bluetooth fallback did not reach the same host");
                try (Socket bluetooth = GameRelayService.open(hostIP, 17510, 8000)) {
                    for (int i = 0; i < 30; i++) {
                        assertFalse("Wi-Fi silently re-enabled", wifi.isWifiEnabled());
                        exchange(bluetooth, "bluetooth:" + i);datagram(hostIP);SystemClock.sleep(300);
                    }
                    exchange(bluetooth, "DONE");
                }
                report("BLUETOOTH_PASS wifiEnabled=false streamMessages=30 datagrams=" + datagrams.get());
                if (gunProbe) {
                    assertTrue("Gun telemetry stopped while Bluetooth relayed the game", telemetry.get() >= gunBeforeFallback + 3);
                    assertEquals("Relay disconnected the BLE gun", 0, gunDisconnects.get());
                    report("GUN_COEXISTENCE_PASS telemetryDuringFallback=" + (telemetry.get() - gunBeforeFallback));
                }
            }
        } finally {
            if (!wifi.isWifiEnabled()) {
                wifi.setWifiEnabled(true);
                await(wifi::isWifiEnabled, 20_000, "Could not restore Wi-Fi");
            }
            for (int network : enabled) wifi.enableNetwork(network, false);
            if (!owner) wifi.reconnect();
            for (Socket socket : accepted) try { socket.close(); } catch (IOException ignored) { }
            GameRelayService.unregister(receiver);GameRelayService.unregister(server);
            if (gun.get() != null) gun.get().close();
            if (gunBound) context.unbindService(gunConnection);
            if (gunReceiverRegistered) context.unregisterReceiver(gunReceiver);
            context.stopService(new Intent(context, GameRelayService.class));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            context.getSharedPreferences("GameRelay", 0).edit().putString("config", savedConfig).commit();
        }
    }
    private void exchange(Socket socket, String text) throws Exception {
        assertNotNull(socket);socket.setSoTimeout(8000);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeUTF(text);output.flush();
        assertEquals(text, new DataInputStream(socket.getInputStream()).readUTF());
    }
    private void datagram(InetAddress host) throws Exception {
        int before = datagrams.get();
        assertTrue(GameRelayService.mirror(host, 17500, new byte[] {42, 7}));
        await(() -> datagrams.get() > before, 5000, "Game UDP did not return through the relay");
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, long timeout, String failure) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.get() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue(failure, condition.get());
    }
    private void report(String message) {
        android.util.Log.i("RelayProbe", message);
        Bundle status = new Bundle();status.putString("stream", "RELAY_PROBE " + message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
