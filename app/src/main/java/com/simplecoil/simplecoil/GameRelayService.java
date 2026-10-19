package com.simplecoil.simplecoil;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.net.wifi.*;
import android.net.wifi.p2p.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.simplecoil.protocol.RelayConfig;
import com.simplecoil.protocol.RelayMesh;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Keeps the admitted game reachable through nearby phones when infrastructure Wi-Fi drops. */
public final class GameRelayService extends Service {
    public static final String CAPABILITIES_CHANGED = "com.simplecoil.simplecoil.RELAY_CAPABILITIES";
    private static volatile GameRelayService instance;
    private static volatile RelayConfig requested;
    private static volatile UDPListenerService udp;
    private static volatile TcpServer tcp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private volatile RelayConfig config;
    private volatile RelayMesh mesh;
    private boolean foreground;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private static final UUID BLUETOOTH_SERVICE = UUID.fromString("7493b200-9f40-48f2-ae5a-b9410bf17f31");
    private final ExecutorService bluetoothWorkers = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, BluetoothLink> bluetoothLinks = new ConcurrentHashMap<>();
    private volatile BluetoothServerSocket bluetoothServer;
    private volatile boolean bluetoothStarting, bluetoothConnecting, destroyed;
    private long nextBluetoothAttempt, hostMissingSince;
    private int nextBuddy;

    private static boolean attached(Context context) {
        return context != null && (!(context instanceof ContextWrapper) || ((ContextWrapper) context).getBaseContext() != null);
    }
    public static String node(Context context) { return LiveJoin.deviceIdentity(context); }
    // Keep the legacy capability field compatible with 1.39 hosts, without
    // advertising a transport that requires an Android connection invitation.
    public static String p2p() { return ""; }
    @SuppressWarnings("deprecation")
    public static String bluetoothAddress() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            String address = adapter == null ? "" : adapter.getAddress();
            return RelayConfig.validMac(address) && !"02:00:00:00:00:00".equalsIgnoreCase(address) ? address : "";
        } catch (SecurityException unavailable) { return ""; }
    }
    public static InetAddress canonicalAddress() {
        GameRelayService service = instance;RelayMesh mesh = service == null ? null : service.mesh;
        return mesh == null ? null : mesh.localAddress();
    }
    public static void initialize(Context context) {
        if (attached(context)) context.getApplicationContext().startService(new Intent(context, GameRelayService.class));
    }
    public static void configure(Context context, RelayConfig next) {
        if (!attached(context) || !next.members.containsKey(RelayConfig.uuid(node(context)))) return;
        requested = next;initialize(context);
        GameRelayService service = instance;
        if (service != null) service.main.post(service::applyConfiguration);
    }
    public static void register(UDPListenerService service) { udp = service; }
    public static void unregister(UDPListenerService service) { if (udp == service) udp = null; }
    public static void register(TcpServer service) { tcp = service; }
    public static void unregister(TcpServer service) { if (tcp == service) tcp = null; }
    public static boolean configuredFor(InetAddress host) {
        GameRelayService service = instance;
        RelayConfig config = service == null ? null : service.config;
        return service != null && service.mesh != null && config != null && host != null
                && host.equals(config.members.get(config.host).address);
    }
    public static boolean reachable(InetAddress host) {
        GameRelayService service = instance;RelayMesh mesh = service == null ? null : service.mesh;
        RelayConfig config = service == null ? null : service.config;
        return mesh != null && config != null && host != null
                && host.equals(config.members.get(config.host).address) && mesh.hostReachable();
    }
    public static Socket open(InetAddress host, int port, int timeout) throws IOException {
        GameRelayService service = instance;
        if (service == null || service.mesh == null || !reachable(host)) return null;
        return service.mesh.open(host, port, timeout);
    }
    public static boolean mirror(InetAddress destination, int port, byte[] bytes) {
        GameRelayService service = instance;RelayMesh mesh = service == null ? null : service.mesh;
        if (mesh == null || destination == null || port != 17500) return false;
        // Infrastructure broadcasts retain their meaning on the separate direct subnet.
        byte[] ip = destination.getAddress();
        if (ip.length == 4 && (ip[3] & 255) == 255) destination = RelayConfig.ipv4("255.255.255.255");
        return mesh.datagram(destination, port, bytes);
    }
    public static String status(Context context) {
        GameRelayService service = instance;
        if (service == null || service.config == null) return "";
        if (service.mesh != null && service.mesh.hostReachable()) {
            boolean hub = service.wifi != null && service.wifi.getConnectionInfo().getNetworkId() >= 0;
            return context.getString(hub ? R.string.relay_ready : R.string.relay_connected);
        }
        return context.getString(R.string.relay_preparing);
    }

    @Override public void onCreate() {
        super.onCreate();instance = this;
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        retireDirectRelay();
        if (requested == null) {
            String saved = getSharedPreferences("GameRelay", MODE_PRIVATE).getString("config", null);
            String matchHost = getSharedPreferences("MatchSession", MODE_PRIVATE).getString("host", null);
            if (saved != null && matchHost != null) {
                try {
                    RelayConfig restored = RelayConfig.decode(saved);
                    if (restored.members.get(restored.host).address.getHostAddress().equals(matchHost)) requested = restored;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        applyConfiguration();main.post(pulse);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { applyConfiguration();return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void applyConfiguration() {
        RelayConfig next = requested;
        UUID self = RelayConfig.uuid(node(this));
        if (next == null || !next.members.containsKey(self)) return;
        try {
            if (mesh != null && config != null && config.key.equals(next.key)
                    && config.members.get(self).address.equals(next.members.get(self).address)) mesh.update(next);
            else {
                if (mesh != null) mesh.close();
                mesh = new RelayMesh(self, next, new InetSocketAddress(17514), new RelayMesh.Listener() {
                    @Override public void datagram(InetAddress source, InetAddress target, int port, byte[] payload) {
                        UDPListenerService listener = udp;
                        if (listener != null && port == 17500) listener.receiveRelayedDatagram(source, payload);
                    }
                    @Override public boolean accept(Socket socket, int port) {
                        TcpServer server = tcp;return server != null && server.acceptRelaySocket(socket, port);
                    }
                });
            }
            config = next;
            if (hostMissingSince == 0) hostMissingSince = SystemClock.elapsedRealtime();
            getSharedPreferences("GameRelay", MODE_PRIVATE).edit().putString("config", next.encode()).apply();
            prioritizeConnection();
            startBluetoothRelay();
        } catch (IOException | RuntimeException failure) { Log.w("GameRelay", "Unable to start game relay", failure); }
    }

    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (mesh != null && config != null) {
                UUID self = RelayConfig.uuid(node(GameRelayService.this));
                if (!self.equals(config.host)) mesh.connect(new InetSocketAddress(config.members.get(config.host).address, 17514));
                startBluetoothRelay();
                long now = SystemClock.elapsedRealtime();
                if (mesh.hostReachable()) hostMissingSince = now;
                else if (now - hostMissingSince >= 5000) connectBluetoothBuddy();
            }
            main.postDelayed(this, 1000);
        }
    };
    /** Remove a previous build's game-owned direct group; never initiate a new one. */
    private void retireDirectRelay() {
        SharedPreferences preferences = getSharedPreferences("GameRelay", MODE_PRIVATE);
        if (preferences.getBoolean("directRelayRetiredV2", false)) return;
        String saved = preferences.getString("config", null);
        RelayConfig previous;
        try { previous = saved == null ? null : RelayConfig.decode(saved); }
        catch (IllegalArgumentException ignored) { previous = null; }
        if (previous == null || previous.members.get(previous.owner).p2p.isEmpty()) {
            preferences.edit().putBoolean("directRelayRetiredV2", true).apply();return;
        }
        WifiP2pManager direct = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (direct == null) return;
        WifiP2pManager.Channel legacy = direct.initialize(this, Looper.getMainLooper(), null);
        if (legacy == null) return;
        RelayConfig retired = previous;
        Runnable finished = () -> {
            preferences.edit().putBoolean("directRelayRetiredV2", true).apply();
            if (Build.VERSION.SDK_INT >= 27) legacy.close();
        };
        Runnable cleanup = () -> direct.requestGroupInfo(legacy, group -> {
            boolean gameGroup = group != null && (group.isGroupOwner() && retired.owner.equals(RelayConfig.uuid(node(this)))
                    || group.getOwner() != null && retired.members.get(retired.owner).p2p.equalsIgnoreCase(group.getOwner().deviceAddress));
            // The designated owner may have changed while phones rejoined the laptop.
            if (group != null && group.getOwner() != null)
                for (RelayConfig.Member member : retired.members.values())
                    if (member.p2p.equalsIgnoreCase(group.getOwner().deviceAddress)) gameGroup = true;
            if (!gameGroup) { finished.run();return; }
            direct.removeGroup(legacy, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { finished.run(); }
                @Override public void onFailure(int reason) {
                    Log.d("GameRelay", "Previous direct group cleanup deferred: " + reason);
                    if (Build.VERSION.SDK_INT >= 27) legacy.close();
                }
            });
        });
        direct.cancelConnect(legacy, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { cleanup.run(); }
            @Override public void onFailure(int reason) { cleanup.run(); }
        });
    }

    @SuppressWarnings("deprecation")
    private void startBluetoothRelay() {
        if (bluetoothServer != null || bluetoothStarting || destroyed) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        bluetoothStarting = true;
        bluetoothWorkers.execute(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord("LAZER GUN RUN nearby", BLUETOOTH_SERVICE);
                bluetoothServer = server;bluetoothStarting = false;
                while (!destroyed) admitBluetooth(server.accept(), false);
            } catch (IOException | SecurityException failure) {
                if (!destroyed) Log.d("GameRelay", "Bluetooth relay listener unavailable", failure);
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
                bluetoothServer = null;bluetoothStarting = false;
            }
        });
    }
    @SuppressWarnings("deprecation")
    private void connectBluetoothBuddy() {
        if (bluetoothConnecting || config == null || mesh == null || destroyed
                || SystemClock.elapsedRealtime() < nextBluetoothAttempt) return;
        java.util.List<RelayConfig.Member> peers = new java.util.ArrayList<>(config.members.values());
        UUID self = RelayConfig.uuid(node(this));
        for (int i = 0; i < peers.size(); i++) {
            RelayConfig.Member peer = peers.get((nextBuddy++ & 0x7fffffff) % peers.size());
            if (peer.id.equals(self) || peer.bluetooth.isEmpty() || bluetoothLinks.containsKey(peer.bluetooth)) continue;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            bluetoothConnecting = true;nextBluetoothAttempt = SystemClock.elapsedRealtime() + 3000;
            bluetoothWorkers.execute(() -> {
                BluetoothSocket socket = null;
                Runnable cancel = null;
                try {
                    socket = adapter.getRemoteDevice(peer.bluetooth).createInsecureRfcommSocketToServiceRecord(BLUETOOTH_SERVICE);
                    BluetoothSocket pending = socket;
                    cancel = () -> { try { pending.close(); } catch (IOException ignored) { } };
                    main.postDelayed(cancel, 8000);socket.connect();main.removeCallbacks(cancel);
                    admitBluetooth(socket, true);socket = null;
                } catch (IOException | SecurityException failure) {
                    Log.d("GameRelay", "Nearby Bluetooth peer not reachable: " + peer.id);
                } finally {
                    if (cancel != null) main.removeCallbacks(cancel);
                    if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                    bluetoothConnecting = false;
                }
            });
            return;
        }
    }
    private void admitBluetooth(BluetoothSocket socket, boolean outgoing) throws IOException {
        String mac = socket.getRemoteDevice().getAddress().toUpperCase(java.util.Locale.ROOT);
        RelayConfig current = config;RelayMesh currentMesh = mesh;
        RelayConfig.Member member = null;
        if (current != null) for (RelayConfig.Member candidate : current.members.values())
            if (mac.equalsIgnoreCase(candidate.bluetooth)) { member = candidate;break; }
        if (member == null || currentMesh == null || destroyed) { socket.close();return; }
        BluetoothLink link = new BluetoothLink(socket, outgoing, member);
        BluetoothLink previous;
        synchronized (bluetoothLinks) {
            previous = bluetoothLinks.get(mac);
            boolean preferOutgoing = RelayConfig.uuid(node(this)).compareTo(member.id) < 0;
            if (previous != null && !previous.isClosed() && previous.outgoing == preferOutgoing) { link.close();return; }
            bluetoothLinks.put(mac, link);
        }
        if (previous != null) previous.close();
        currentMesh.attach(link);
        Log.i("GameRelay", "Nearby Bluetooth link established with " + member.id);
    }
    private final class BluetoothLink extends Socket {
        private final BluetoothSocket socket;
        private final InputStream input;
        private final String mac;
        final boolean outgoing;
        private volatile boolean closed;
        private volatile int timeout = 3000;
        BluetoothLink(BluetoothSocket socket, boolean outgoing, RelayConfig.Member member) throws IOException {
            this.socket = socket;this.outgoing = outgoing;mac = member.bluetooth;input = socket.getInputStream();
        }
        @Override public void setTcpNoDelay(boolean enabled) { }
        @Override public void setKeepAlive(boolean enabled) { }
        @Override public void setSoTimeout(int timeout) { this.timeout = timeout; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isConnected() { return !closed && socket.isConnected(); }
        @Override public OutputStream getOutputStream() throws IOException { return socket.getOutputStream(); }
        @Override public InputStream getInputStream() { return new InputStream() {
            @Override public int read() throws IOException { byte[] one = new byte[1];return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                long end = SystemClock.elapsedRealtime() + (timeout == 0 ? 8000 : timeout);
                while (!closed) {
                    int available = input.available();
                    if (available > 0) return input.read(bytes, offset, Math.min(available, length));
                    if (SystemClock.elapsedRealtime() >= end) throw new SocketTimeoutException("Bluetooth relay timed out");
                    try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new java.io.InterruptedIOException(); }
                }
                return -1;
            }
        }; }
        @Override public void close() throws IOException { closed = true;bluetoothLinks.remove(mac, this);socket.close(); }
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("WakelockTimeout")
    private void prioritizeConnection() {
        if (!foreground) {
            String channel = "game_relay";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notifications.createNotificationChannel(new NotificationChannel(channel,
                        getString(R.string.relay_notification_title), NotificationManager.IMPORTANCE_LOW));
            }
            Intent open = new Intent(this, FullscreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            startForeground(17522, new NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_gun_connection)
                    .setContentTitle(getString(R.string.relay_notification_title)).setContentText(getString(R.string.relay_notification_body))
                    .setContentIntent(PendingIntent.getActivity(this, 17522, open, flags))
                    .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build());
            foreground = true;
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleCoil:GameRelay");wake.setReferenceCounted(false);wake.acquire();
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SimpleCoil:GameRelay");
                wifiLock.setReferenceCounted(false);wifiLock.acquire();
            }
        }
    }
    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (mesh != null) mesh.close();
        if (bluetoothServer != null) try { bluetoothServer.close(); } catch (IOException ignored) { }
        for (BluetoothLink link : bluetoothLinks.values()) try { link.close(); } catch (IOException ignored) { }
        bluetoothWorkers.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (foreground) stopForeground(true);
        if (instance == this) { instance = null;requested = null; }
        super.onDestroy();
    }
}
