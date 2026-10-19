package com.simplecoil.simplecoil;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit, two-device radio test. Never changes networks in ordinary test runs. */
@RunWith(AndroidJUnit4.class)
public class WifiDirectRelayHardwareTest {
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiManager wifi;
    private boolean createdGroup;

    @Test public void directLinkSurvivesLossOfInfrastructureWifi() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Opt-in radio test", "true".equals(args.getString("relay_hardware_probe")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assertNotNull(manager);assertNotNull(wifi);
        channel = manager.initialize(context, Looper.getMainLooper(), null);
        assertNotNull(channel);
        assertNull("An existing direct group must not be disturbed", group());
        try {
            if ("owner".equals(args.getString("relay_role"))) owner();
            else client(args.getString("relay_owner"));
        } finally {
            // The other test phone may already have removed the shared group.
            if (createdGroup && group() != null) {
                try { action(listener -> manager.removeGroup(channel, listener)); }
                catch (AssertionError failure) { if (group() != null) throw failure; }
            }
        }
    }

    private void owner() throws Exception {
        int network = wifi.getConnectionInfo().getNetworkId();
        assertTrue("Owner must begin connected to a hub", network >= 0);
        action(listener -> manager.createGroup(channel, listener));createdGroup = true;
        WifiP2pGroup group = awaitGroup(true);
        WifiP2pInfo info = info();
        assertNotNull(info.groupOwnerAddress);
        assertEquals("Creating a direct group disconnected the owner's hub", network,
                wifi.getConnectionInfo().getNetworkId());
        report("OWNER_READY address=" + group.getOwner().deviceAddress + " interface=" + group.getInterface()
                + " ownerIP=" + info.groupOwnerAddress.getHostAddress() + " hubConnected=true");
        try (ServerSocket server = new ServerSocket(17542, 4, info.groupOwnerAddress)) {
            server.setSoTimeout(120_000);
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(45_000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                int count = 0;
                while (true) {
                    String message = input.readUTF();
                    if ("DONE".equals(message)) break;
                    output.writeUTF(message);output.flush();count++;
                }
                assertTrue("No relayed traffic was tested", count >= 20);
                assertEquals("Relay owner lost its infrastructure connection", network,
                        wifi.getConnectionInfo().getNetworkId());
                report("OWNER_PASS echoed=" + count + " hubConnected=true");
            }
        }
    }

    private void client(String owner) throws Exception {
        assertNotNull("Specify relay_owner device address", owner);
        action(listener -> manager.discoverPeers(channel, listener));
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        boolean discovered = false;
        while (SystemClock.elapsedRealtime() < deadline && !discovered) {
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<Boolean> found = new AtomicReference<>(false);
            manager.requestPeers(channel, peers -> {
                for (android.net.wifi.p2p.WifiP2pDevice peer : peers.getDeviceList())
                    if (owner.equalsIgnoreCase(peer.deviceAddress)) found.set(true);
                ready.countDown();
            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));discovered = found.get();
            if (!discovered) SystemClock.sleep(500);
        }
        assertTrue("Group owner was not discovered", discovered);
        WifiP2pConfig config = new WifiP2pConfig();config.deviceAddress = owner;
        config.wps.setup = WpsInfo.PBC;config.groupOwnerIntent = 0;
        action(listener -> manager.connect(channel, config, listener));createdGroup = true;
        report("CLIENT_CONNECTING owner=" + owner);
        awaitGroup(false);
        InetAddress ownerIP = null;
        long addressDeadline = SystemClock.elapsedRealtime() + 15_000;
        while (ownerIP == null && SystemClock.elapsedRealtime() < addressDeadline) {
            ownerIP = info().groupOwnerAddress;
            if (ownerIP == null) SystemClock.sleep(100);
        }
        assertNotNull(ownerIP);
        List<Integer> enabledNetworks = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ownerIP, 17542), 10_000);socket.setSoTimeout(5_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            exchange(input, output, "before-hub-loss");
            for (WifiConfiguration network : wifi.getConfiguredNetworks()) {
                if (network.status != WifiConfiguration.Status.DISABLED) {
                    enabledNetworks.add(network.networkId);
                    assertTrue(wifi.disableNetwork(network.networkId));
                }
            }
            wifi.disconnect();
            long wait = SystemClock.elapsedRealtime() + 10_000;
            while (wifi.getConnectionInfo().getNetworkId() >= 0 && SystemClock.elapsedRealtime() < wait)
                SystemClock.sleep(100);
            assertTrue("Infrastructure Wi-Fi did not disconnect", wifi.getConnectionInfo().getNetworkId() < 0);
            report("CLIENT_HUB_DISCONNECTED directGroup=" + (group() != null));
            for (int i = 0; i < 30; i++) {
                exchange(input, output, UUID.randomUUID().toString());SystemClock.sleep(500);
                assertTrue("Client silently rejoined infrastructure Wi-Fi", wifi.getConnectionInfo().getNetworkId() < 0);
            }
            output.writeUTF("DONE");output.flush();
            report("CLIENT_PASS directTrafficAfterHubLoss=30 durationSeconds=15");
        } finally {
            for (int network : enabledNetworks) wifi.enableNetwork(network, false);
            wifi.reconnect();
        }
    }

    private void exchange(DataInputStream input, DataOutputStream output, String message) throws Exception {
        output.writeUTF(message);output.flush();assertEquals(message, input.readUTF());
    }
    private WifiP2pGroup awaitGroup(boolean owner) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 90_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            WifiP2pGroup group = group();
            if (group != null && group.isGroupOwner() == owner) return group;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Direct connection was not established");
    }
    private WifiP2pGroup group() throws Exception {
        AtomicReference<WifiP2pGroup> result = new AtomicReference<>();CountDownLatch ready = new CountDownLatch(1);
        manager.requestGroupInfo(channel, value -> { result.set(value);ready.countDown(); });
        assertTrue("Group query timed out", ready.await(3, TimeUnit.SECONDS));return result.get();
    }
    private WifiP2pInfo info() throws Exception {
        AtomicReference<WifiP2pInfo> result = new AtomicReference<>();CountDownLatch ready = new CountDownLatch(1);
        manager.requestConnectionInfo(channel, value -> { result.set(value);ready.countDown(); });
        assertTrue("Connection query timed out", ready.await(3, TimeUnit.SECONDS));return result.get();
    }
    private interface Request { void run(WifiP2pManager.ActionListener listener); }
    private void action(Request request) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);AtomicReference<Integer> error = new AtomicReference<>();
        request.run(new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { ready.countDown(); }
            @Override public void onFailure(int reason) { error.set(reason);ready.countDown(); }
        });
        assertTrue("Wi-Fi Direct operation timed out", ready.await(5, TimeUnit.SECONDS));
        assertNull("Wi-Fi Direct operation failed: " + error.get(), error.get());
    }
    private void report(String message) {
        android.util.Log.i("RelayProbe", message);
        Bundle status = new Bundle();status.putString("stream", "P2P_PROBE " + message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
