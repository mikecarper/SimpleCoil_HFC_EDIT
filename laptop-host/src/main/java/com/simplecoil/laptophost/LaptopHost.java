/*
 * Copyright (C) 2026 SimpleCoil contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.laptophost;

import com.simplecoil.protocol.RoundRoster;
import com.simplecoil.protocol.GpsOffset;
import com.simplecoil.protocol.GameStartCountdown;
import com.simplecoil.protocol.Powerups;
import com.simplecoil.protocol.EventMode;
import com.simplecoil.protocol.RoundStatistics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A desktop dedicated host for the current SimpleCoil protocol.
 *
 * <p>Phones still exchange latency-sensitive blaster events directly over UDP.
 * This process owns the lobby, NTP-style start deadline, score authority, GPS
 * snapshots, and the local browser dashboard. Keeping the dashboard path out
 * of the combat path means it remains useful with a full 32-player game.</p>
 */
public final class LaptopHost {
    private static final int NETWORK_VERSION = 31;
    private static final String MESSAGE_PREFIX = "SimpleCoil:";
    private static final String TCP_PREFIX = MESSAGE_PREFIX + NETWORK_VERSION;
    private static final String TCP_JSON_PREFIX = TCP_PREFIX + "JSON";
    private static final String TCP_MESSAGE_PREFIX = TCP_PREFIX + "MESG";
    private static final String JSON_GPS_CLOCK = "gpsclock";
    private static final String JSON_GPS_START_TIME = "gpsstarttime";
    private static final String JSON_CLOCK_EPOCH = "clockepoch";
    // Must match the Android client's GameClock.SAMPLES_PER_SYNC. The client
    // selects the lowest-delay measurement and rejects RTTs above 80 ms, which
    // bounds its network timing uncertainty to less than 50 ms.
    private static final int CLOCK_SAMPLES_REQUIRED = 12;
    private static final long CLOCK_ROSTER_COALESCE_MS = 75;
    private static final int ROSTER_UTF_BUDGET = 60_000;

    private static final int MAX_PLAYERS = 32;
    private static final int STATE_PACKET_BYTES = 48 + MAX_PLAYERS * 38;
    private static final int UDP_RECEIVE_BYTES = 1_472;
    private static final int STATE_PACKET_MAGIC = 0x53434F49;
    private static final int STATE_PACKET_FORMAT = 4;
    private static final int COMBAT_PACKET_BYTES = 32;
    private static final int COMBAT_PACKET_MAGIC = 0x53434F43;
    private static final int COMBAT_PACKET_FORMAT = 2;
    private static final int MAX_GRENADE_IDS = 16;
    private static final int MAX_SCOREBOARD_VALUE = Integer.MAX_VALUE / MAX_PLAYERS;
    private static final long CLOCK_MAX_VALUE = Long.MAX_VALUE / 4;
    private static final long GPS_PUBLISH_INTERVAL_MS = 250;
    private static final int GPS_FULL_UPDATE_EVERY_TICKS = 20;
    private static final long GPS_STALE_AFTER_MS = 15_000;
    private static final long GPS_MAX_REPORTED_FIX_AGE_MS = 30_000;
    private static final long GPS_CLOCK_MAX_AGE_MS = 60_000;
    private static final long EARLIEST_GPS_UTC_MS = 1_577_836_800_000L; // 2020-01-01
    private static final long HIT_ENEMY_GPS_REVEAL_MS = 10_000;
    private static final long LASER_LIFETIME_MS = 1_500;
    private static final long GRENADE_BURST_WINDOW_MS = 2_000;
    private static final long GRENADE_MARKER_LIFETIME_MS = 8_000;
    private static final long JOIN_ASSIGNMENT_TIMEOUT_MS = 10_000;
    private static final long BALANCED_QR_CHECK_IN_TIMEOUT_MS = 90_000;
    // Match the dedicated phone host's practical heartbeat window. Stop showing
    // a disconnected phone as online while retaining admitted relay seats.
    private static final long CLIENT_IDLE_TIMEOUT_MS = 35_000;

    private static final String MSG_PING = "ping";
    private static final String MSG_PONG = "pong";
    private static final String MSG_ELIMINATED = "ELIMINATED";
    private static final String MSG_TEAM_ELIMINATED = "TEAMELIMINATED";
    private static final String MSG_RESPAWN_REQUEST = "RESPAWNREQUEST";
    private static final String MSG_RESPAWN_GRANTED = "RESPAWNGRANTED";
    private static final String MSG_RESPAWN_COMPLETE = "RESPAWNCOMPLETE";
    private static final String MSG_PLAYER_DATA_REQUEST = "PLAYERDATAREQUEST";
    private static final String MSG_START_GAME = "STARTGAME";
    private static final String MSG_END_GAME = "ENDGAME";
    private static final String MSG_LEAVE = "LEAVE";
    private static final String MSG_QUIT = "QUIT";
    private static final String MSG_CLOCK_WAITING = "CLOCKSYNCWAITING";
    private static final String MSG_VERSION_ERROR = "VERSIONERROR";
    private static final String MSG_SERVER_REPLY = "SERVERREPLY";
    private static final String MSG_SAME_TEAM = "SAMETEAM";
    private static final String MSG_HOST_TAKEOVER = "HOSTTAKEOVER";

    private final HostConfig config;
    private final long clockOriginNanos = System.nanoTime();
    // A cached offset is valid only for this monotonic clock's host lifetime.
    private final String clockEpoch = UUID.randomUUID().toString();
    private final Object stateLock = new Object();
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    // Operator edits belong to a person, never to a reusable blaster/player ID.
    private final Map<String, String> hostPlayerNames = new HashMap<>();
    private final Map<String, Boolean> hostPlayerParticipation = new HashMap<>();
    private volatile Map<String, Integer> hostPlayerTeams = Collections.emptyMap();
    private final Map<String, Settings> hostPlayerSettings = new HashMap<>();
    private final Set<String> releasedPhones = new HashSet<>();
    private final EventSettings eventSettings = new EventSettings();
    private boolean eventMode = true;
    private boolean eventPinning = true;
    private String eventRevision = UUID.randomUUID().toString();
    private final Map<String, String> playerEventRevisions = new HashMap<>();
    private boolean hostCustomRules;
    private final PlayerDirectory playerDirectory;
    private RoundRoster roundRoster;
    private RoundReview roundReview;
    // Reports are frozen before any end-game socket is closed. Rejoining the
    // lobby or starting another round must not replace the previous results.
    private final Deque<RoundReport> roundReports = new ArrayDeque<>();
    private static final int MAX_REPORT_HISTORY = 10;
    private static final int MAX_TIMELINE_EVENTS = 12_000;
    private static final int MAX_SHOT_TIMELINE_EVENTS = 2_000;
    private final Powerups.Authority powerupAuthority = new Powerups.Authority();
    private volatile Map<Integer, Integer> balancedTeams = Collections.emptyMap();
    // Phones reconnect after each round and can receive a different seat ID.
    // Keep the team attached to the person until the operator rebalances.
    private final Map<String, Integer> retainedBalancedTeams = new HashMap<>();
    private final Set<Integer> balancedCheckedIn = new HashSet<>();
    // Relative to clockOriginNanos; zero when no QR lobby is waiting.
    private long balancedCheckInDeadline;
    private final Random balanceRandom = new Random();
    private final Map<Integer, ClientConnection> activeClients = new HashMap<>();
    private final Map<InetAddress, PendingAssignment> pendingAssignments = new HashMap<>();
    private final Map<Integer, Integer> grenadeOwners = new HashMap<>();
    private final Deque<LaserEvent> lasers = new ArrayDeque<>();
    private final Deque<GrenadeBurst> grenadeBursts = new ArrayDeque<>();
    private final StateRow[] gossipState = new StateRow[MAX_PLAYERS + 1];
    private final long[] gossipStateReceivedAt = new long[MAX_PLAYERS + 1];
    private final long[] lastGossipPacketSequence = new long[MAX_PLAYERS + 1];
    // A compact combat event can beat its sender's first full state row to the
    // laptop. Retain only the latest fixed-width event per player so the next
    // one-second authority tick can still repair phones that missed the burst.
    private final long[] observedCombatSequence = new long[MAX_PLAYERS + 1];
    private final int[] observedCombatType = new int[MAX_PLAYERS + 1];
    private final int[] observedCombatTarget = new int[MAX_PLAYERS + 1];
    private final int[] observedCombatTargetGeneration = new int[MAX_PLAYERS + 1];
    private long authorityTickSequence;
    private volatile GpsOffset gpsOffset;
    private long endVotes;
    private boolean endVoteRequested;
    // Recipient player ID -> the enemy player IDs that a confirmed IR hit may
    // reveal. These maps are protected by stateLock. Every active teammate of
    // each participant shares the reveal, but no other enemy is exposed.
    private final Map<Integer, Map<Integer, Long>> enemyGpsRevealUntil = new HashMap<>();
    private final Set<Integer> gpsVisibilityRefreshPlayers = new HashSet<>();
    private final HotspotManager hotspot = new HotspotManager();
    private final AtomicInteger nextClientID = new AtomicInteger();
    private final Set<ClientConnection> allClients = ConcurrentHashMap.newKeySet();
    private final UUID relayKey = UUID.randomUUID(), relayNode = UUID.randomUUID();
    private volatile com.simplecoil.protocol.RelayMesh relayMesh;
    private volatile com.simplecoil.protocol.RelayConfig relayConfig;
    private InetAddress relayAddress;

    private void acceptGameSocket(Socket socket) throws IOException {
        // Native sockets and relay streams use the same heartbeat policy. A
        // transport read timeout must not silently override the 35-second grace.
        socket.setSoTimeout(0);socket.setTcpNoDelay(true);socket.setKeepAlive(true);
        clientExecutor.execute(new ClientConnection(socket, nextClientID.incrementAndGet()));
    }
    private void relayCapabilities(Player player, Map<String, Object> json) {
        if (!(json.get("relaynode") instanceof String)) return;
        try {
            player.relayNode = com.simplecoil.protocol.RelayConfig.uuid((String) json.get("relaynode"));
            player.relayBluetooth = json.get("relaybt") instanceof String ? (String) json.get("relaybt") : "";
        } catch (IllegalArgumentException ignored) { }
    }
    private String relayConfigurationLocked() {
        if (relayAddress == null) return null;
        Map<UUID, com.simplecoil.protocol.RelayConfig.Member> members = new LinkedHashMap<>();
        members.put(relayNode, new com.simplecoil.protocol.RelayConfig.Member(relayNode, relayAddress, ""));
        Set<InetAddress> addresses = new HashSet<>();addresses.add(relayAddress);
        List<Player> candidates = new ArrayList<>(players.values());
        candidates.sort(java.util.Comparator.comparing((Player player) -> !player.connected));
        for (Player player : candidates) if (player.relayNode != null && !members.containsKey(player.relayNode) && player.address != null
                && addresses.add(player.address))
            members.put(player.relayNode, new com.simplecoil.protocol.RelayConfig.Member(player.relayNode, player.address, "", player.relayBluetooth));
        // Retain admitted relay membership across lobby disconnects and seat
        // changes while Bluetooth reconnects. Prefer
        // current addresses and keep the remaining cohort within the 32-phone cap.
        com.simplecoil.protocol.RelayConfig previous = relayConfig;
        if (previous != null) for (com.simplecoil.protocol.RelayConfig.Member member : previous.members.values()) {
            if (members.size() >= 33) break;
            if (!members.containsKey(member.id) && addresses.add(member.address)) members.put(member.id, member);
        }
        if (members.size() < 2) return null;
        try {
            // Advertising the laptop as owner with no P2P address also prevents
            // 1.39 phones from creating groups or sending Android invitations.
            com.simplecoil.protocol.RelayConfig next = new com.simplecoil.protocol.RelayConfig(relayKey, relayNode, relayNode, members);
            if (relayMesh == null) {
                relayMesh = new com.simplecoil.protocol.RelayMesh(relayNode, next,
                        new InetSocketAddress(config.tcpPort + 4), new com.simplecoil.protocol.RelayMesh.Listener() {
                    @Override public void datagram(InetAddress source, InetAddress target, int port, byte[] payload) {
                        if (port == config.udpPort) dispatchGameDatagram(source, payload, 0, payload.length);
                    }
                    @Override public boolean accept(Socket socket, int port) {
                        if (stopping || port != config.tcpPort) return false;
                        try { acceptGameSocket(socket);return true; }
                        catch (IOException | RuntimeException failure) { return false; }
                    }
                });
            } else relayMesh.update(next);
            relayConfig = next;return next.encode();
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Game relay unavailable: " + failure.getMessage());return null;
        }
    }
    private void dispatchGameDatagram(InetAddress source, byte[] payload, int offset, int length) {
        if (CombatPacket.looksLike(payload, offset, length)) handleCombatEvent(source, payload, offset, length);
        else if (StatePacket.looksLike(payload, offset, length)) handleStateGossip(source, payload, offset, length);
        else handleUdpDiscovery(source, new String(payload, offset, length, StandardCharsets.UTF_8));
    }
    private void sendGameDatagram(DatagramSocket socket, DatagramPacket packet) throws IOException {
        com.simplecoil.protocol.RelayMesh mesh = relayMesh;
        InetAddress target = packet.getAddress();byte[] ip = target.getAddress();
        if (ip.length == 4 && (ip[3] & 255) == 255) target = com.simplecoil.protocol.RelayConfig.ipv4("255.255.255.255");
        boolean relayed = mesh != null && mesh.datagram(target, packet.getPort(),
                java.util.Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
        try { socket.send(packet); } catch (IOException failure) { if (!relayed) throw failure; }
    }

    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SimpleCoil laptop client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService dashboardExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SimpleCoil dashboard");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "SimpleCoil laptop scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean stopping;
    private ServerSocket tcpServer;
    private DatagramSocket udpServer;
    private HttpServer dashboard;
    private HttpServer tileServer;
    private final PhoneUpdateCache phoneUpdates = new PhoneUpdateCache(this::phoneUpdatesIdle);
    private final AdbPhoneUpdater adbPhoneUpdates = new AdbPhoneUpdater(
            Path.of(System.getProperty("simplecoil.adb.updates.file", Path.of(
                    System.getProperty("user.home"), ".config", "simplecoil", "adb-phone-updates.json").toString())),
            this::phoneUpdatesIdle, phoneUpdates::adbRelease, this::acquireAdbPhoneLease);
    // Guarded by stateLock. START and Wi-Fi changes wait for a prepared phone install.
    private Object adbPhoneLease;
    private String lastAdbUpdatePolicy = "";
    private Thread tcpAcceptThread;
    private Thread udpThread;

    private volatile RoundState roundState = RoundState.LOBBY;
    private long roundID;
    private String roundToken;
    private long roundStartAt;
    private long roundDuration;
    private long roundEndAt;
    private long nextRoundStartAllowedAt;
    // Boss health is fixed from the roster that existed when Start was
    // accepted. A temporary Wi-Fi disconnect must not lower the boss's maximum
    // health before that hunter has a chance to reconnect.
    private int bossHunterCount;
    // A laptop normally has no GPS receiver. During clock sync a phone with a
    // fresh GPS fix can provide an optional UTC reference for the shared start.
    // The normal monotonic NTP exchange remains the required fallback.
    private long gpsUtcAtReference = -1;
    private long gpsElapsedAtReference = -1;
    private int gpsTick;
    // Guarded by stateLock. Readiness often arrives from a whole lobby at once.
    private ScheduledFuture<?> clockRosterBroadcast;

    private LaptopHost(HostConfig config) {
        this.config = config;
        eventMode = config.eventMode && eventSettings.enabled();
        eventPinning = eventSettings.kiosk();
        try {
            playerDirectory = new PlayerDirectory(config.playerStore);
            Map<String, Integer> teams = new HashMap<>();
            for (PlayerDirectory.Profile profile : playerDirectory.profiles.values()) {
                if (profile.hostNamed) hostPlayerNames.put(profile.id, profile.name);
                if (profile.benched != null) hostPlayerParticipation.put(profile.id, profile.benched);
                if (profile.team > 0) teams.put(profile.id, profile.team);
                if (profile.settings != null) {
                    Settings settings = Settings.fromHostJson(profile.settings);
                    if (settings == null) throw new IOException("Invalid settings for saved player " + profile.name);
                    hostPlayerSettings.put(profile.id, settings);
                }
            }
            hostPlayerTeams = Collections.unmodifiableMap(teams);
            hostCustomRules = !hostPlayerSettings.isEmpty() && !fixedModeRules();
        } catch (IOException exception) { throw new IllegalStateException(exception.getMessage(), exception); }
    }

    public static void main(String[] args) throws Exception {
        HostConfig config = HostConfig.parse(args);
        if (config.showHelp) {
            System.out.println(HostConfig.usage());
            return;
        }

        LaptopHost host = new LaptopHost(config);
        host.start();
        Runtime.getRuntime().addShutdownHook(new Thread(host::stop, "SimpleCoil laptop shutdown"));
        host.printStartupInstructions();
        if (!config.noBrowser)
            host.openDashboard();

        // The socket and dashboard workers are daemon threads. Keep the host alive
        // until Ctrl+C, a service manager, or the shutdown hook stops it.
        new CountDownLatch(1).await();
    }

    private void start() throws IOException {
        loadSavedReports();
        tcpServer = new ServerSocket();
        tcpServer.setReuseAddress(true);
        tcpServer.bind(new InetSocketAddress(config.tcpPort));

        udpServer = new DatagramSocket(null);
        udpServer.setReuseAddress(true);
        udpServer.setBroadcast(true);
        udpServer.bind(new InetSocketAddress(config.udpPort));

        dashboard = HttpServer.create(new InetSocketAddress(config.dashboardBind, config.dashboardPort), 0);
        dashboard.setExecutor(dashboardExecutor);
        dashboard.createContext("/", this::handleDashboardIndex);
        dashboard.createContext("/map", exchange -> sendHtml(exchange, MAP_PAGE));
        dashboard.createContext("/leaderboard", exchange -> sendHtml(exchange, LEADERBOARD_PAGE));
        dashboard.createContext("/wifi", this::handleWifiPage);
        dashboard.createContext("/phone-updates", this::handlePhoneUpdatesPage);
        dashboard.createContext("/players", this::handlePlayersPage);
        dashboard.createContext("/event-setup", exchange -> sendFile(exchange,
                config.webDirectory.resolve("event-setup.html"), "text/html; charset=utf-8", "no-store"));
        dashboard.createContext("/assets/players.js", exchange -> sendFile(exchange,
                config.webDirectory.resolve("players.js"), "text/javascript; charset=utf-8", "no-store"));
        dashboard.createContext("/api/wifi", this::handleWifiApi);
        dashboard.createContext("/api/phone-updates", this::handlePhoneUpdatesApi);
        dashboard.createContext("/api/players", this::handlePlayersApi);
        dashboard.createContext("/api/event", this::handleEventApi);
        dashboard.createContext("/round-report", exchange -> sendHtml(exchange, ROUND_REPORT_PAGE));
        dashboard.createContext("/assets/leaflet/", this::handleLeafletAsset);
        dashboard.createContext("/tiles/", this::handleMapTile);
        dashboard.createContext("/api/map-config", this::handleMapConfigApi);
        dashboard.createContext("/api/state", this::handleStateApi);
        dashboard.createContext("/api/reports", this::handleReportsApi);
        dashboard.createContext("/api/report", this::handleReportApi);
        dashboard.createContext("/api/start", this::handleStartApi);
        dashboard.createContext("/api/rebalance", this::handleRebalanceApi);
        dashboard.createContext("/api/end", this::handleEndApi);
        dashboard.createContext("/api/respawn", this::handleRespawnApi);
        dashboard.createContext("/api/bench", this::handleBenchApi);
        dashboard.start();

        tileServer = HttpServer.create(new InetSocketAddress(config.tileBind, config.tilePort), 0);
        tileServer.setExecutor(dashboardExecutor);
        tileServer.createContext("/tiles/", this::handleMapTile);
        tileServer.createContext("/api/map-config", this::handleMapConfigApi);
        tileServer.createContext("/api/app-update/", phoneUpdates::handle);
        tileServer.start();
        phoneUpdates.start();
        adbPhoneUpdates.start();

        tcpAcceptThread = new Thread(this::runTcpAcceptLoop, "SimpleCoil laptop TCP accept");
        tcpAcceptThread.setDaemon(true);
        tcpAcceptThread.start();

        udpThread = new Thread(this::runUdpLoop, "SimpleCoil laptop UDP discovery");
        udpThread.setDaemon(true);
        udpThread.start();

        scheduler.scheduleAtFixedRate(this::sendHeartbeats, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::publishAuthorityStateTick, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::publishGps, GPS_PUBLISH_INTERVAL_MS,
                GPS_PUBLISH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::advanceRoundClock, 100, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::disconnectUnresponsiveClients, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::broadcastSharedLobby, 0, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::publishAdbUpdatePolicy, 0, 2, TimeUnit.SECONDS);
        if (config.takeover)
            scheduler.scheduleAtFixedRate(this::broadcastHostTakeover, 0, 1, TimeUnit.SECONDS);
    }

    private void stop() {
        if (stopping)
            return;
        synchronized (stateLock) { savePlayerDirectoryLocked(); }
        if (relayMesh != null) relayMesh.close();
        stopping = true;
        adbPhoneUpdates.close();
        phoneUpdates.close();
        closeQuietly(tcpServer);
        if (udpServer != null)
            udpServer.close();
        if (dashboard != null)
            dashboard.stop(0);
        if (tileServer != null)
            tileServer.stop(0);

        synchronized (stateLock) {
            activeClients.clear();
        }
        for (ClientConnection client : new ArrayList<>(allClients))
            client.close();
        scheduler.shutdownNow();
        clientExecutor.shutdownNow();
        // Let queued report writes finish without holding the combat/state lock.
        dashboardExecutor.shutdown();
        try {
            if (!dashboardExecutor.awaitTermination(2, TimeUnit.SECONDS))
                dashboardExecutor.shutdownNow();
        } catch (InterruptedException interrupted) {
            dashboardExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void printStartupInstructions() {
        System.out.println("SimpleCoil laptop host is ready.");
        System.out.println("  TCP lobby port: " + config.tcpPort + "  |  UDP discovery port: " + config.udpPort);
        System.out.println("  Dashboard: http://" + config.dashboardBind + ":" + config.dashboardPort + "/");
        System.out.println("  Offline map tiles: " + config.tileDirectory);
        System.out.println("  Phone tile port: " + config.tilePort + " (read-only)");
        List<String> addresses = localIPv4Addresses();
        if (!addresses.isEmpty()) {
            System.out.println("  Phones: in SimpleCoil choose Join Game and enter one of these laptop IPs:");
            for (String address : addresses)
                System.out.println("    " + address);
        } else {
            System.out.println("  Phones: join this laptop's LAN IPv4 address.");
        }
        System.out.println("  Open /map and /leaderboard in separate browser windows for the live displays.");
    }

    private void openDashboard() {
        try {
            if (Desktop.isDesktopSupported())
                Desktop.getDesktop().browse(new URI("http://127.0.0.1:" + config.dashboardPort + "/"));
        } catch (Exception ignored) {
            // A headless laptop or a desktop with no registered browser can still
            // use the printed URL.
        }
    }

    private void runTcpAcceptLoop() {
        while (!stopping) {
            try {
                Socket socket = tcpServer.accept();
                acceptGameSocket(socket);
            } catch (SocketException e) {
                if (!stopping)
                    System.err.println("TCP listener stopped: " + e.getMessage());
                return;
            } catch (IOException e) {
                if (!stopping)
                    System.err.println("TCP accept failed: " + e.getMessage());
            }
        }
    }

    private void runUdpLoop() {
        // Keep headroom above the exact state size so an oversized datagram is
        // observable as oversized and rejected instead of being truncated into
        // what looks like a valid packet.
        byte[] buffer = new byte[UDP_RECEIVE_BYTES];
        while (!stopping) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpServer.receive(packet);
                dispatchGameDatagram(packet.getAddress(), packet.getData(), packet.getOffset(), packet.getLength());
            } catch (SocketException e) {
                if (!stopping)
                    System.err.println("UDP discovery stopped: " + e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                if (!stopping)
                    System.err.println("Ignoring malformed UDP discovery packet: " + e.getMessage());
            }
        }
    }

    private void handleUdpDiscovery(InetAddress source, String message) {
        if (source != null && (MESSAGE_PREFIX + "LOBBYQUERY:" + NETWORK_VERSION).equals(message)) {
            sendUdp(sharedLobbyAnnouncement(), source);
            return;
        }
        if (source == null || message == null || !message.startsWith(MESSAGE_PREFIX + "JOIN"))
            return;
        String request = message.substring((MESSAGE_PREFIX + "JOIN").length());
        if (request.length() < 3 || !request.startsWith(String.valueOf(NETWORK_VERSION))) {
            sendUdp(MSG_VERSION_ERROR, source);
            return;
        }
        int requestedID;
        if (request.equals(NETWORK_VERSION + "QR")) {
            sendUdp(MESSAGE_PREFIX + MSG_SERVER_REPLY, source);
            return;
        }
        try {
            requestedID = Integer.parseInt(request.substring(String.valueOf(NETWORK_VERSION).length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (requestedID != 0 && !validPlayerID(requestedID))
            return;

        int assignedID = reservePlayerID(source, requestedID);
        if (assignedID <= 0) {
            sendUdp(MESSAGE_PREFIX + MSG_SAME_TEAM, source);
            return;
        }
        String reply = MESSAGE_PREFIX + MSG_SERVER_REPLY;
        if (assignedID != requestedID)
            reply += ":" + assignedID;
        sendUdp(reply, source);
    }

    private int reservePlayerID(InetAddress source, int requestedID) {
        synchronized (stateLock) {
            long now = elapsedMillis();
            removeExpiredAssignmentsLocked(now);
            PendingAssignment existing = pendingAssignments.get(source);
            if (existing != null)
                return existing.playerID;
            if (requestedID == 0) {
                for (Player player : players.values())
                    if ((player.connected || player.relayNode != null) && source.equals(player.address))
                        return player.id;
                int preferredTeam = 0;
                if (!config.boss && !config.infection && !assignedTeamsEnabledLocked()
                        && config.gameMode != GameMode.FFA) {
                    int[] teamSizes = new int[5];
                    for (Player player : players.values())
                        if (player.connected)
                            teamSizes[teamFor(player.id)]++;
                    for (PendingAssignment pending : pendingAssignments.values())
                        teamSizes[teamFor(pending.playerID)]++;
                    preferredTeam = 1;
                    for (int team = 2; team <= config.gameMode.wireValue; team++)
                        if (teamSizes[team] < teamSizes[preferredTeam])
                            preferredTeam = team;
                }
                for (int candidate = 1; candidate <= MAX_PLAYERS; candidate++) {
                    if (preferredTeam != 0 && teamFor(candidate) != preferredTeam)
                        continue;
                    Player player = players.get(candidate);
                    if (player != null && (player.connected || player.relayNode != null))
                        continue;
                    boolean reserved = false;
                    for (PendingAssignment pending : pendingAssignments.values())
                        if (pending.playerID == candidate)
                            reserved = true;
                    if (!reserved) {
                        requestedID = candidate;
                        break;
                    }
                }
                if (requestedID == 0)
                    return 0;
            }

            // A discovery datagram can be retransmitted after its TCP
            // registration already completed. Keep that player's original
            // identity instead of treating the retry as a new join and moving
            // it into an unrelated free slot on the same team.
            Player requestedPlayer = players.get(requestedID);
            if (requestedPlayer != null && source.equals(requestedPlayer.address))
                return requestedID;
            if (isRoundActiveLocked() && (assignedTeamsEnabledLocked() || !config.allowLateJoin))
                return -1;

            int requestedTeam = teamFor(requestedID);
            for (int candidate = 1; candidate <= MAX_PLAYERS; candidate++) {
                if (config.gameMode != GameMode.FFA && !assignedTeamsEnabledLocked()
                        && teamFor(candidate) != requestedTeam)
                    continue;
                Player player = players.get(candidate);
                boolean sameKnownEndpoint = player != null && source.equals(player.address);
                if (player != null && (player.connected
                        || (isRoundActiveLocked() || player.relayNode != null) && !sameKnownEndpoint))
                    continue;
                if (reservedByAnotherEndpointLocked(candidate, source))
                    continue;
                pendingAssignments.put(source, new PendingAssignment(candidate, now + JOIN_ASSIGNMENT_TIMEOUT_MS));
                return candidate;
            }
            return -1;
        }
    }

    private boolean reservedByAnotherEndpointLocked(int playerID, InetAddress source) {
        for (Map.Entry<InetAddress, PendingAssignment> entry : pendingAssignments.entrySet()) {
            if (!source.equals(entry.getKey()) && entry.getValue().playerID == playerID)
                return true;
        }
        return false;
    }

    private void removeExpiredAssignmentsLocked(long now) {
        pendingAssignments.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private void sendUdp(String message, InetAddress destination) {
        if (destination == null || udpServer == null || udpServer.isClosed())
            return;
        try {
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            udpServer.send(new DatagramPacket(payload, payload.length, destination, config.udpPort));
        } catch (IOException ignored) {
            // Discovery is retried by the phone; one lost reply should not affect
            // the TCP host or dashboard.
        }
    }

    private void handleStateGossip(InetAddress source, byte[] payload, int offset, int length) {
        StatePacket decoded = StatePacket.decode(payload, offset, length);
        if (decoded == null || decoded.networkVersion != NETWORK_VERSION || decoded.senderID <= 0
                || decoded.gameMode != config.gameMode.wireValue)
            return;
        synchronized (stateLock) {
            if (!isRoundActiveLocked() || roundToken == null
                    || !roundToken.equals(decoded.roundToken.toString()))
                return;
            Player player = players.get(decoded.senderID);
            StateRow owned = decoded.rows[decoded.senderID];
            if (player == null || player.benched() || owned == null || !source.equals(player.address)
                    || roundRoster != null && !roundRoster.accepts(decoded.senderID, owned.generation)
                    || decoded.packetSequence <= lastGossipPacketSequence[decoded.senderID])
                return;
            lastGossipPacketSequence[decoded.senderID] = decoded.packetSequence;
            StateRow current = gossipState[decoded.senderID];
            if (current == null || owned.ownerSequence > current.ownerSequence) {
                updateRoundStateLocked();
                if (roundState == RoundState.RUNNING && roundReview != null) {
                    int capturesBefore = current == null ? 0 : current.ctfCaptures;
                    if (owned.ctfCaptures > capturesBefore)
                        roundReview.event(elapsedMillis(), "objective", player, null,
                                owned.ctfCaptures - capturesBefore, "Flag capture(s)");
                }
                // The TCP lobby owns the fixed origin. A delayed or inconsistent
                // GPS header must not move a marker, but its combat counters can
                // still repair phones that missed the sender's earlier state.
                if ((owned.flags & StatePacket.FLAG_GPS_VALID) != 0
                        && (gpsOffset == null || !gpsOffset.sameOrigin(decoded.origin)))
                    owned = owned.withoutGps();
                if (config.infection && current != null
                        && (current.flags & StatePacket.FLAG_INFECTED) != 0
                        && (owned.flags & StatePacket.FLAG_INFECTED) == 0)
                    owned = owned.withFlags(owned.flags | StatePacket.FLAG_INFECTED);
                if (owned.eventSequence > observedCombatSequence[decoded.senderID]) {
                    observedCombatSequence[decoded.senderID] = owned.eventSequence;
                    observedCombatType[decoded.senderID] = owned.eventType;
                    observedCombatTarget[decoded.senderID] = owned.eventTargetID;
                    observedCombatTargetGeneration[decoded.senderID] = owned.targetGeneration;
                }
                gossipState[decoded.senderID] = mergeObservedCombatLocked(
                        decoded.senderID, owned);
                gossipStateReceivedAt[decoded.senderID] = elapsedMillis();
                if (roundRoster != null) roundRoster.update(decoded.senderID, owned.generation,
                        player.kills, player.deaths, owned.ctfCaptures,
                        (owned.flags & StatePacket.FLAG_INFECTED) != 0);
            }
        }
    }

    private void handleCombatEvent(InetAddress source, byte[] payload, int offset, int length) {
        CombatPacket decoded = CombatPacket.decode(payload, offset, length);
        if (decoded == null || decoded.networkVersion != NETWORK_VERSION
                || decoded.kind != CombatPacket.KIND_EVENT)
            return;
        synchronized (stateLock) {
            if (!isRoundActiveLocked() || roundToken == null
                    || !roundToken.equals(decoded.roundToken.toString()))
                return;
            Player player = players.get(decoded.senderID);
            if (player == null || player.benched() || player.address == null || !source.equals(player.address)
                    || roundRoster != null && (!roundRoster.accepts(decoded.senderID, decoded.generation)
                    || decoded.targetID != 0 && !roundRoster.accepts(decoded.targetID, decoded.targetGeneration))
                    || decoded.eventSequence <= observedCombatSequence[decoded.senderID])
                return;
            observedCombatSequence[decoded.senderID] = decoded.eventSequence;
            observedCombatType[decoded.senderID] = decoded.eventType;
            observedCombatTarget[decoded.senderID] = decoded.targetID;
            observedCombatTargetGeneration[decoded.senderID] = decoded.targetGeneration;
            StateRow current = gossipState[decoded.senderID];
            if (current != null)
                gossipState[decoded.senderID] = mergeObservedCombatLocked(
                        decoded.senderID, current);
        }
    }

    // Caller holds stateLock.
    private StateRow mergeObservedCombatLocked(int playerID, StateRow row) {
        long sequence = observedCombatSequence[playerID];
        if (row == null || sequence <= row.eventSequence)
            return row;
        return new StateRow(row.flags, row.ownerSequence, sequence, row.gameState,
                row.grenadeID, observedCombatType[playerID], observedCombatTarget[playerID],
                row.score, row.deaths, row.health, row.shield, row.shotsRemaining,
                row.gpsAgeSeconds, row.latitude, row.longitude, row.carriedFlagTeam,
                row.ctfCaptures).withGeneration(row.generation, observedCombatTargetGeneration[playerID]);
    }

    private void publishAuthorityStateTick() {
        final byte[] payload;
        final List<InetAddress> recipients = new ArrayList<>();
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (!isRoundActiveLocked() || roundToken == null)
                return;
            UUID token;
            try {
                token = UUID.fromString(roundToken);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (authorityTickSequence >= 0xffffffffL)
                return;
            long now = elapsedMillis();
            StateRow[] snapshot = gossipState.clone();
            for (int playerID = 1; playerID <= MAX_PLAYERS; playerID++) {
                StateRow row = snapshot[playerID];
                if (row != null && (row.flags & StatePacket.FLAG_GPS_VALID) != 0) {
                    long elapsedSeconds = Math.max(0, now - gossipStateReceivedAt[playerID]) / 1_000;
                    snapshot[playerID] = row.withGpsAge((int) Math.min(0xffff,
                            row.gpsAgeSeconds + elapsedSeconds));
                }
            }
            payload = StatePacket.encode(NETWORK_VERSION, token, ++authorityTickSequence,
                    config.gameMode.wireValue, snapshot, gpsOffset);
            // Broadcast lets every nearby phone hear the same tick. Targeted
            // copies retain normal Wi-Fi unicast acknowledgements and make a
            // congested access point's broadcast loss much less noticeable.
            for (Player player : players.values()) {
                if (player.connected && !player.benched() && player.address != null
                        && !recipients.contains(player.address))
                    recipients.add(player.address);
            }
        }
        DatagramSocket socket = udpServer;
        if (socket == null || socket.isClosed())
            return;
        for (InetAddress address : broadcastAddresses()) {
            if (!recipients.contains(address))
                recipients.add(address);
        }
        for (InetAddress address : recipients) {
            try {
                sendGameDatagram(socket, new DatagramPacket(payload, payload.length, address, config.udpPort));
            } catch (IOException ignored) {
                // The next one-second authority tick repairs a dropped send.
            }
        }
    }

    private void broadcastGameInvite(String token) {
        if (!validRoundToken(token))
            return;
        scheduler.execute(() -> {
            String invite = MESSAGE_PREFIX + "GAMEINVITE:" + NETWORK_VERSION + ":" + token;
            byte[] payload = invite.getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                for (InetAddress address : broadcastAddresses()) {
                    DatagramPacket packet = new DatagramPacket(payload, payload.length, address, config.udpPort);
                    for (int attempt = 0; attempt < 3; attempt++)
                        sendGameDatagram(socket, packet);
                }
            } catch (IOException ignored) {
                // Invites are an optional convenience. Manual Join Game remains
                // available even when a network blocks broadcast traffic.
            }
        });
    }

    /**
     * Announce an explicitly requested pre-game takeover. Repeating the packet
     * makes a one-off Android multicast loss harmless while keeping the signal
     * local to the connected Wi-Fi networks.
     */
    private String sharedLobbyAnnouncement() {
        synchronized (stateLock) {
            return MESSAGE_PREFIX + "LOBBYSTATE:" + NETWORK_VERSION + ":L:"
                    + (isRoundActiveLocked() ? "PLAY" : "OPEN") + ":"
                    + Math.min(365L * 24 * 60 * 60 * 1000, elapsedMillis());
        }
    }

    private void broadcastSharedLobby() {
        if (stopping)
            return;
        for (InetAddress address : broadcastAddresses())
            sendUdp(sharedLobbyAnnouncement(), address);
    }

    private void broadcastHostTakeover() {
        if (stopping)
            return;
        String announcement = MESSAGE_PREFIX + MSG_HOST_TAKEOVER + ":" + NETWORK_VERSION;
        byte[] payload = announcement.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (InetAddress address : broadcastAddresses()) {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, address, config.udpPort);
                sendGameDatagram(socket, packet);
            }
        } catch (IOException ignored) {
            // A host can still be joined manually if this Wi-Fi blocks broadcast.
        }
    }

    private void handleFrame(ClientConnection client, String frame) {
        if (frame == null || client.closed)
            return;
        client.lastReceivedAt = elapsedMillis();
        if (MSG_PONG.equals(frame))
            return;
        if (frame.startsWith(TCP_JSON_PREFIX)) {
            try {
                Object value = Json.parse(frame.substring(TCP_JSON_PREFIX.length()));
                if (value instanceof Map)
                    handleJson(client, castObject(value));
            } catch (IllegalArgumentException e) {
                System.err.println("Ignoring malformed TCP JSON from client " + client.clientID + ": " + e.getMessage());
            }
        } else if (frame.startsWith(TCP_MESSAGE_PREFIX)) {
            handleMessage(client, frame.substring(TCP_MESSAGE_PREFIX.length()));
        } else if (frame.startsWith(MESSAGE_PREFIX)) {
            client.sendAndClose(MSG_VERSION_ERROR);
        }
    }

    private void handleJson(ClientConnection client, Map<String, Object> json) {
        if (json.containsKey(EventMode.BATTERY)) {
            handleBatteryStatus(client, json.get(EventMode.BATTERY));
            return;
        }
        if (json.containsKey(EventMode.LOCK_STATUS)) {
            synchronized (stateLock) {
                if (players.get(client.playerID) != null && players.get(client.playerID).connection == client && Set.of("locked", "pinned", "none", "error")
                        .contains(json.get(EventMode.LOCK_STATUS)))
                    client.eventLockStatus = (String) json.get(EventMode.LOCK_STATUS);
            }
            return;
        }
        if (json.containsKey("powerupscan")) {
            handlePowerupScan(client, json);
            return;
        }
        if (json.containsKey("endvote")) {
            if (json.get("endvote") instanceof Boolean && json.get("roundtoken") instanceof String)
                requestEndVote(client, (String) json.get("roundtoken"), (Boolean) json.get("endvote"));
            return;
        }
        if (json.containsKey("lobbyready") && !json.containsKey("playername")) {
            synchronized (stateLock) {
                if (playerForClientLocked(client) == null
                        || !(json.get("lobbyready") instanceof Boolean))
                    return;
                if (!isRoundActiveLocked())
                    client.lobbyReady = (Boolean) json.get("lobbyready");
                relayCapabilities(players.get(client.playerID), json);
            }
            broadcastRoster();
            return;
        }
        if (json.containsKey("clocksync") || json.containsKey("clockready")) {
            handleClockSync(client, json);
            return;
        }
        if (json.containsKey("gpslongitude") || json.containsKey("gpsn") || json.containsKey("gpse")) {
            handleGps(client, json);
            return;
        }
        if (json.containsKey("telemetry")) {
            handleCombatTelemetry(client, json);
            return;
        }
        if (json.containsKey("playersettings")) {
            handlePlayerSettings(client, json);
            return;
        }
        if (json.containsKey("playernamechange")) {
            handlePlayerNameChange(client, json);
            return;
        }
        if (json.containsKey("balancedcheckin")) {
            handleBalancedCheckIn(client, json);
            return;
        }
        if (json.containsKey("pairedgrenadeID")) {
            handleGrenadePairing(client, json);
            return;
        }
        if (json.containsKey("playerID") && json.containsKey("playername"))
            registerPlayer(client, json);
    }

    private void handleClockSync(ClientConnection client, Map<String, Object> json) {
        if (json.containsKey("clocksync")) {
            long sentAt = longValue(json.get("clocksync"), -1);
            if (sentAt < 0 || sentAt > CLOCK_MAX_VALUE || client.playerID <= 0)
                return;
            long received = elapsedMillis();
            if (json.containsKey("gpslongitude")) handleGps(client, json);
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("gpsoffset", gpsOriginMap(gpsOffset));
            reply.put("clocksync", sentAt);
            reply.put("clockreceive", received);
            reply.put("clocksend", elapsedMillis());
            reply.put(JSON_CLOCK_EPOCH, clockEpoch);
            synchronized (stateLock) {
                if (json.containsKey(JSON_GPS_CLOCK))
                    recordGpsClockLocked(longValue(json.get(JSON_GPS_CLOCK), -1), received);
                long gpsTime = gpsTimeAtElapsedLocked(elapsedMillis());
                if (validGpsUtcTime(gpsTime))
                    reply.put(JSON_GPS_CLOCK, gpsTime);
                client.clockSamples = Math.min(CLOCK_SAMPLES_REQUIRED, client.clockSamples + 1);
            }
            client.sendClockReply(reply);
        } else if (Boolean.TRUE.equals(json.get("clockready"))
                && (client.clockSamples >= CLOCK_SAMPLES_REQUIRED
                // A client may resume its own recent, RTT-validated estimate
                // after one fresh probe proves the same host clock is alive.
                // Legacy ACKs and mismatched epochs still require the full batch.
                || client.clockSamples > 0 && clockEpoch.equals(json.get(JSON_CLOCK_EPOCH)))) {
            synchronized (stateLock) {
                if (playerForClientLocked(client) == null || client.clockReady) return;
                client.clockReady = true;
                if (client.rosterPending) sendRoster(client);
                scheduleClockRosterBroadcastLocked();
            }
        }
    }

    private void handleBatteryStatus(ClientConnection client, Object value) {
        if (!(value instanceof Map)) return;
        Map<String, Object> report = castObject(value);
        int phone = intValue(report.get("phone"), -2);
        if (phone < -1 || phone > 100 || !(report.get("charging") instanceof Boolean)
                || !(report.get("guns") instanceof List) || ((List<?>) report.get("guns")).size() > 2) return;
        List<Object> guns = new ArrayList<>();
        for (Object item : (List<?>) report.get("guns")) {
            if (!(item instanceof Map)) return;
            Map<String, Object> gun = castObject(item);
            int volts = intValue(gun.get("voltageTenths"), -2), type = intValue(gun.get("type"), -1);
            if (!(gun.get("connected") instanceof Boolean) || !(gun.get("lowPower") instanceof Boolean)
                    || type < 0 || type > 2 || volts != -1 && (volts < 19 || volts > 61)
                    || !Set.of("unknown", "high", "good", "low", "critical", "empty").contains(gun.get("level"))) return;
            guns.add(mapOf("connected", gun.get("connected"), "lowPower", gun.get("lowPower"),
                    "type", type, "level", gun.get("level"), "voltageTenths", volts,
                    "id", gun.get("id") instanceof String && com.simplecoil.protocol.GunBatteryHistory.validGun((String) gun.get("id"))
                            ? ((String) gun.get("id")).toUpperCase(Locale.ROOT) : null));
        }
        synchronized (stateLock) {
            Player player = players.get(client.playerID);
            if (player == null || player.connection != client || !player.connected) return;
            PlayerDirectory.Profile profile = playerDirectory.profiles.get(player.identity);
            if (profile != null && report.containsKey("rounds")) {
                if (profile.gunBatteryRounds.merge(report.get("rounds"), player.identity))
                    savePlayerDirectoryLocked();
            }
            player.battery = mapOf("phone", phone, "charging", report.get("charging"), "guns", guns);
            player.batteryAt = elapsedMillis();
        }
    }

    private void handlePowerupScan(ClientConnection client, Map<String, Object> json) {
        List<String> frames = new ArrayList<>();
        List<ClientConnection> recipients;
        synchronized (stateLock) {
            updateRoundStateLocked();
            Player source = playerForClientLocked(client);
            int mask = intValue(json.get("powerupscan"), -1);
            int life = intValue(json.get("poweruplife"), -1);
            if (roundState != RoundState.RUNNING || roundToken == null
                    || !roundToken.equals(json.get("roundtoken"))
                    || config.powerupQrRequired < Powerups.MIN_REQUIRED
                    || config.powerupQrRequired > Powerups.MAX_REQUIRED
                    || source == null || !powerupPlayerAliveLocked(source)
                    || mask < 1 || mask > 255 || life != powerupPlayerLifeLocked(source))
                return;
            Map<Integer, Integer> teams = new HashMap<>();
            Map<Integer, Integer> scores = new HashMap<>();
            Set<Integer> alive = new HashSet<>();
            refreshPowerupLivesLocked();
            if (config.gameMode != GameMode.FFA)
                for (int team = 1; team <= config.gameMode.wireValue; team++)
                    scores.put(team, powerupTeamScoreLocked(team));
            for (Player player : players.values()) {
                if (player.benched()) continue;
                if (config.gameMode == GameMode.FFA) scores.put(player.id, player.kills);
                if (!player.connected) continue;
                int team = config.gameMode == GameMode.FFA ? 0 : teamFor(player.id);
                teams.put(player.id, team);
                if (powerupPlayerAliveLocked(player)) alive.add(player.id);
            }
            long now = elapsedMillis();
            int ownTeam = config.gameMode == GameMode.FFA ? 0 : teamFor(source.id);
            int ownScoreKey = ownTeam == 0 ? source.id : ownTeam;
            int bestEnemyScore = 0;
            for (Map.Entry<Integer, Integer> score : scores.entrySet())
                if (score.getKey() != ownScoreKey) bestEnemyScore = Math.max(bestEnemyScore, score.getValue());
            int required = Powerups.effectiveRequired(config.powerupQrRequired,
                    scores.getOrDefault(ownScoreKey, 0), bestEnemyScore);
            List<Powerups.Grant> grants = new ArrayList<>();
            // A reconnect may report several newly scanned codes at once.
            // At most four two-code rewards can be earned from an eight-bit set.
            for (int count = 0; count < 4; count++) {
                Powerups.Grant grant = powerupAuthority.request(source.id, life, mask,
                        required, ownTeam, teams, scores, alive, now, balanceRandom);
                if (grant == null) break;
                grants.add(grant);
                if (roundReview != null)
                    roundReview.event(now, "powerup", source, null, 1,
                            "Power-up " + powerupName(grant.kind));
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            putPowerupSnapshotLocked(snapshot, now);
            snapshot.put("roundtoken", roundToken);
            if (grants.isEmpty()) frames.add(jsonFrame(snapshot));
            for (Powerups.Grant grant : grants) {
                Map<String, Object> payload = new LinkedHashMap<>(snapshot);
                payload.put("powerupgrant", powerupGrantMap(grant));
                frames.add(jsonFrame(payload));
            }
            recipients = connectedClientsLocked();
        }
        // All phones need attacker buff records, even if they are not a target.
        for (String frame : frames)
            for (ClientConnection recipient : recipients) recipient.send(frame);
    }

    /** Caller holds stateLock. A request cannot advance its own life counter. */
    private int powerupPlayerLifeLocked(Player player) {
        StateRow row = gossipState[player.id];
        return Math.max(player.deaths, row == null ? 0 : row.deaths);
    }

    /** Caller holds stateLock. Source-owned health repairs delayed death reports. */
    private boolean powerupPlayerAliveLocked(Player player) {
        if (!player.connected || player.benched() || player.awaitingRespawn || player.eliminated)
            return false;
        StateRow row = gossipState[player.id];
        return row == null || (row.flags & StatePacket.FLAG_LEFT) == 0
                && row.gameState == 1 && row.health > 0;
    }

    /** Caller holds stateLock. Keep each grant's original source and target lives. */
    private void refreshPowerupLivesLocked() {
        for (Player player : players.values())
            powerupAuthority.setPlayerLife(player.id, powerupPlayerLifeLocked(player));
    }

    /** Caller holds stateLock. Use the current game's objective, not prior-round skill. */
    private int powerupTeamScoreLocked(int team) {
        if (!config.captureTheFlag) return teamKillsLocked(team);
        long total = roundRoster == null ? 0 : roundRoster.archivedScore(team, true);
        for (Player player : players.values()) {
            StateRow row = gossipState[player.id];
            if (teamFor(player.id) == team && row != null) total += row.ctfCaptures;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static Map<String, Object> powerupGrantMap(Powerups.Grant grant) {
        return mapOf("id", grant.id, "source", grant.source, "sourcelife", grant.sourceLife,
                "kind", grant.kind, "recipients", Powerups.encodeRecipients(grant), "expires", grant.expiresAt,
                "cost", grant.scanCost);
    }

    /** Caller holds stateLock. Expiry is absolute in the clock accompanying this snapshot. */
    private void putPowerupSnapshotLocked(Map<String, Object> game, long now) {
        refreshPowerupLivesLocked();
        Powerups.Snapshot snapshot = powerupAuthority.snapshot(now);
        List<Object> grants = new ArrayList<>();
        List<Object> scanned = new ArrayList<>();
        for (Powerups.Grant grant : snapshot.grants) grants.add(powerupGrantMap(grant));
        for (Powerups.ScanState player : snapshot.players)
            scanned.add(mapOf("id", player.player, "life", player.life, "mask", player.mask,
                    "earned", player.earned, "required", player.required, "spent", player.spent));
        game.put("powerupgrants", grants);
        game.put("powerupclock", snapshot.capturedAt);
        game.put("powerupstate", mapOf("nextid", snapshot.nextId, "players", scanned));
    }

    private static Map<String, Object> gpsOriginMap(GpsOffset origin) {
        return origin == null ? null : mapOf("gpslatitude", origin.latitude,
                "gpslongitude", origin.longitude);
    }

    private static GpsOffset readGpsOrigin(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> map = castObject(value);
        return GpsOffset.create(doubleValue(map.get("gpslatitude"), Double.NaN),
                doubleValue(map.get("gpslongitude"), Double.NaN));
    }

    private static void putGpsPosition(Map<String, Object> update, GpsOffset origin,
                                       double latitude, double longitude) {
        GpsOffset.Position position = origin == null ? null : origin.encode(latitude, longitude);
        if (position == null) {
            update.put("gpslatitude", latitude);
            update.put("gpslongitude", longitude);
        } else {
            update.put("gpsn", position.north);
            update.put("gpse", position.east);
        }
    }

    private void handleGps(ClientConnection client, Map<String, Object> json) {
        boolean originChosen = false;
        synchronized (stateLock) {
            Player player = playerForClientLocked(client);
            if (player == null || player.benched()) return;
            int ageMs = json.containsKey("gpsage") ? intValue(json.get("gpsage"), -1) : 0;
            if (ageMs < 0 || ageMs > GPS_MAX_REPORTED_FIX_AGE_MS) return;
            double longitude, latitude;
            if (json.containsKey("gpsn") || json.containsKey("gpse")) {
                if (gpsOffset == null || !gpsOffset.sameOrigin(readGpsOrigin(json.get("gpsoffset"))))
                    return;
                double n = doubleValue(json.get("gpsn"), Double.NaN);
                double e = doubleValue(json.get("gpse"), Double.NaN);
                if (!Double.isFinite(n) || !Double.isFinite(e) || n != Math.rint(n) || e != Math.rint(e)
                        || n < Short.MIN_VALUE || n > Short.MAX_VALUE
                        || e < Short.MIN_VALUE || e > Short.MAX_VALUE) return;
                double[] position = gpsOffset.decode((int) n, (int) e);
                if (position == null) return;
                latitude = position[0];
                longitude = position[1];
            } else {
                longitude = doubleValue(json.get("gpslongitude"), Double.NaN);
                latitude = doubleValue(json.get("gpslatitude"), Double.NaN);
            }
            if (!validCoordinates(longitude, latitude)) return;
            if (gpsOffset == null) {
                gpsOffset = GpsOffset.create(latitude, longitude);
                originChosen = true;
            }
            player.longitude = longitude;
            player.latitude = latitude;
            // A valid phone fix can predate this host process. Negative sample
            // times are real ages, so reserve a separate unset sentinel.
            player.lastGpsAt = elapsedMillis() - ageMs;
            player.gpsDirty = true;
            player.addTrail(longitude, latitude, player.lastGpsAt);
        }
        if (originChosen) broadcastRoster();
    }

    private void handleCombatTelemetry(ClientConnection client, Map<String, Object> json) {
        Object eventValue = json.get("telemetry");
        if (!(eventValue instanceof String))
            return;
        String event = (String) eventValue;
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (roundState != RoundState.RUNNING)
                return;
            Player reporter = playerForClientLocked(client);
            if (reporter == null)
                return;
            if (!Set.of("shot", "hit", "grenadehit", "elimination").contains(event)) return;
            if (!validTelemetryScopeLocked(reporter, json)) return;
            int resolvedAttackerID = 0;
            boolean staleSource = false;
            // Reject malformed bodies before consuming their replay sequence.
            // Missing or string attackers must not become an unknown (0) death.
            if ("shot".equals(event)) {
                int count = intValue(json.get("count"), -1);
                if (!integerLiteral(json.get("count")) || count < 1 || count > 255) return;
                if (json.containsKey("telemetryseq") && (!integerLiteral(json.get("total"))
                        || intValue(json.get("total"), -1) < 0
                        || intValue(json.get("total"), -1) > MAX_SCOREBOARD_VALUE)) return;
            } else if ("hit".equals(event) || "elimination".equals(event)) {
                int attackerID = intValue(json.get("attacker"), -1);
                Player attacker = players.get(attackerID);
                if (!integerLiteral(json.get("attacker")) || reporter.awaitingRespawn
                        || attackerID != 0 && !validPlayerID(attackerID)
                        || attackerID == 0 && !"elimination".equals(event)) return;
                resolvedAttackerID = attackerID;
                if (attackerID > 0 && json.containsKey("telemetryseq")) {
                    int sourceGeneration = intValue(json.get("attackergeneration"), -1);
                    if (!integerLiteral(json.get("attackergeneration"))
                            || sourceGeneration < 0 || sourceGeneration > 255) return;
                    RoundRoster.Member source = roundRoster == null ? null : roundRoster.seat(attackerID);
                    staleSource = attacker == null || !attacker.connected || source == null || !source.active
                            || source.generation != sourceGeneration
                            || !source.identity.equals(attacker.identity);
                    if (staleSource) resolvedAttackerID = 0;
                }
                if (attackerID > 0 && !staleSource && (attacker == null || !attacker.connected
                        || attacker == reporter || !areOpponents(attackerID, reporter.id))) return;
            } else {
                int grenadeID = intValue(json.get("grenadeID"), -1);
                if (!integerLiteral(json.get("grenadeID")) || grenadeID <= 0
                        || grenadeID >= MAX_GRENADE_IDS || reporter.awaitingRespawn) return;
            }
            if (roundReview == null || !roundReview.acceptTelemetry(reporter, json))
                return;
            if (staleSource) {
                // A well-formed queued report can outlive the source's seat.
                // Never give a replacement that person's hit or kill. The
                // victim's death still advances life and permits respawn.
                roundReview.person(reporter).complete = false;
                if ("hit".equals(event)) return;
            }
            if ("shot".equals(event)) {
                int count = intValue(json.get("count"), 0);
                if (json.containsKey("total")) {
                    int total = intValue(json.get("total"), -1);
                    if (total < 0 || total > MAX_SCOREBOARD_VALUE) return;
                    count = Math.max(0, total - roundReview.person(reporter).shots);
                }
                if (count >= 1 && (json.containsKey("total") || count <= 255)) {
                    reporter.shots = addScore(reporter.shots, count);
                    roundReview.shots(elapsedMillis(), reporter, count);
                }
            } else if ("hit".equals(event)) {
                int attackerID = resolvedAttackerID;
                Player attacker = players.get(attackerID);
                if (attacker == null || attacker == reporter || !attacker.connected
                        || !areOpponents(attacker.id, reporter.id))
                    return;
                attacker.hits = addScore(attacker.hits, 1);
                roundReview.hit(elapsedMillis(), attacker, reporter);
                reporter.lastGrenadeHitAt = 0;
                addLaserLocked(attacker, reporter, "hit");
                grantEnemyGpsRevealLocked(attacker.id, reporter.id);
            } else if ("grenadehit".equals(event)) {
                int grenadeID = intValue(json.get("grenadeID"), 0);
                if (grenadeID <= 0 || grenadeID >= MAX_GRENADE_IDS)
                    return;
                if (!addGrenadeHitLocked(grenadeID, reporter))
                    return;
                int ownerID = grenadeOwners.getOrDefault(grenadeID, 0);
                Player owner = players.get(ownerID);
                if (owner != null && owner != reporter && owner.connected
                        && areOpponents(owner.id, reporter.id)) {
                    reporter.lastGrenadeHitAt = elapsedMillis();
                    reporter.lastGrenadeOwnerID = ownerID;
                    // The elimination UDP event can reach the host before
                    // this TCP telemetry. Remove its shooter-to-victim line.
                    lasers.removeIf(laser -> laser.shooterID == ownerID
                            && laser.targetID == reporter.id && "kill".equals(laser.kind)
                            && elapsedMillis() - laser.createdAt <= GRENADE_BURST_WINDOW_MS);
                }
                roundReview.event(elapsedMillis(), "grenade", owner, reporter, 1,
                        "Grenade #" + grenadeID + " hit");
            } else if ("elimination".equals(event)) {
                processElimination(client, String.valueOf(resolvedAttackerID));
            }
        }
    }

    /** Caller holds stateLock. Optional protocol-31 fields are all-or-nothing. */
    private boolean validTelemetryScopeLocked(Player reporter, Map<String, Object> json) {
        boolean scoped = json.containsKey("roundtoken") || json.containsKey("generation")
                || json.containsKey("life") || json.containsKey("telemetryseq") || json.containsKey("total")
                || json.containsKey("attackergeneration");
        if (!scoped) return !reporter.analyticsCapable;
        RoundRoster.Member member = roundRoster == null ? null : roundRoster.seat(reporter.id);
        int life = intValue(json.get("life"), -1);
        return roundToken != null && roundToken.equals(json.get("roundtoken"))
                && member != null && member.identity.equals(reporter.identity)
                && integerLiteral(json.get("generation")) && integerLiteral(json.get("life"))
                && integerLiteral(json.get("telemetryseq"))
                && intValue(json.get("generation"), -1) == member.generation
                && life >= 0 && ("shot".equals(json.get("telemetry"))
                        ? life <= reporter.deaths : life == reporter.deaths)
                && longValue(json.get("telemetryseq"), -1) > 0;
    }

    private static boolean integerLiteral(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    private void handlePlayerSettings(ClientConnection client, Map<String, Object> json) {
        boolean changed = false;
        synchronized (stateLock) {
            Player player = playerForClientLocked(client);
            if (player == null)
                return;
            if (isRoundActiveLocked())
                return;
            if (!config.onlyServerSettings && !config.tournament) {
                Settings candidate = Settings.fromClientJson(json);
                if (candidate != null) {
                    player.settings = candidate;
                    changed = true;
                }
            }
        }
        // Reply even after a rejected edit so a client cannot keep showing an
        // old local profile when the laptop host owns the rules.
        if (changed || config.onlyServerSettings || config.tournament)
            broadcastSettings();
    }

    private void handlePlayerNameChange(ClientConnection client, Map<String, Object> json) {
        String name = validPlayerName(json.get("playernamechange"));
        if (name == null)
            return;
        synchronized (stateLock) {
            Player player = playerForClientLocked(client);
            if (player == null || isRoundActiveLocked())
                return;
            if (!eventMode) player.name = hostPlayerNames.getOrDefault(player.identity, name);
        }
        broadcastRoster();
    }

    private void handleBalancedCheckIn(ClientConnection client, Map<String, Object> json) {
        boolean readyToStart;
        synchronized (stateLock) {
            if (!balancedQrEnabledLocked() || !Boolean.TRUE.equals(json.get("balancedcheckin"))
                    || roundState != RoundState.LOBBY || !balancedTeams.containsKey(client.playerID)
                    || balancedCheckInDeadline == 0
                    || playerForClientLocked(client) == null)
                return;
            balancedCheckedIn.add(client.playerID);
            readyToStart = balancedCheckInDeadline > 0
                    && balancedCheckedIn.containsAll(balancedTeams.keySet());
        }
        broadcastRoster();
        if (readyToStart)
            startRound();
    }

    private void handleGrenadePairing(ClientConnection client, Map<String, Object> json) {
        int playerID = intValue(json.get("playerID"), 0);
        int grenadeID = intValue(json.get("pairedgrenadeID"), -1);
        if (grenadeID < 0 || grenadeID >= MAX_GRENADE_IDS || playerID != client.playerID)
            return;
        synchronized (stateLock) {
            if (playerForClientLocked(client) == null)
                return;
            grenadeOwners.entrySet().removeIf(entry -> entry.getValue() == playerID);
            if (grenadeID != 0) {
                grenadeOwners.remove(grenadeID);
                grenadeOwners.put(grenadeID, playerID);
            }
        }
        broadcastGrenadePairings();
    }

    private void registerPlayer(ClientConnection client, Map<String, Object> json) {
        int requestedID = intValue(json.get("playerID"), 0);
        String name = validPlayerName(json.get("playername"));
        boolean rejoin = Boolean.TRUE.equals(json.get("rejoin"));
        String deviceIdentity = json.get(EventMode.DEVICE_IDENTITY) instanceof String ? (String) json.get(EventMode.DEVICE_IDENTITY)
                : json.get("playeridentity") instanceof String ? (String) json.get("playeridentity")
                : UUID.nameUUIDFromBytes((client.address().getHostAddress() + ":" + requestedID)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        int requestedTeam = intValue(json.get("jointeam"), 0);
        if (!RoundRoster.validIdentity(deviceIdentity)) return;
        int priorKills = intValue(json.get("priorkills"), 0);
        int priorDeaths = intValue(json.get("priordeaths"), 0);
        boolean historyProvided = json.containsKey("historyroundtoken")
                || json.containsKey("historybaselinekills") || json.containsKey("historybaselinedeaths");
        String historyToken = json.get("historyroundtoken") instanceof String
                ? (String) json.get("historyroundtoken") : null;
        int historyKills = intValue(json.get("historybaselinekills"), -1);
        int historyDeaths = intValue(json.get("historybaselinedeaths"), -1);
        if (historyProvided && (!validReportToken(historyToken)
                || !integerLiteral(json.get("historybaselinekills"))
                || !integerLiteral(json.get("historybaselinedeaths"))
                || historyKills < 0 || historyKills > 100_000 || historyDeaths < 0 || historyDeaths > 100_000))
            return;
        if (!validPlayerID(requestedID) || name == null)
            return;
        if (priorKills < 0 || priorKills > 100_000 || priorDeaths < 0 || priorDeaths > 100_000)
            return;

        List<ClientConnection> replaced = new ArrayList<>();
        boolean accepted = false;
        synchronized (stateLock) {
            PlayerDirectory.Profile profile = playerDirectory.forDevice(deviceIdentity, name);
            String identity = profile.id;
            if (profile.hostNamed || eventMode) name = profile.name;
            else profile.name = name;
            if (client.playerID != 0 && client.playerID != requestedID)
                return;
            // A new arrival opens the next lobby without disturbing the
            // connected players or their already-calibrated host clocks.
            if (roundState == RoundState.FINISHED)
                resetForNewLobbyLocked();
            removeExpiredAssignmentsLocked(elapsedMillis());
            rememberRoundStatsLocked();

            RoundRoster.Member joined = null;
            if (isRoundActiveLocked() && requestedTeam > 0 && roundRoster != null) {
                joined = roundRoster.join(identity, requestedTeam, config.gameMode == GameMode.FFA
                        ? 0 : config.gameMode.wireValue, config.boss, config.infection, config.livesLimit,
                        assignedTeamsEnabledLocked(), true);
                if (joined == null) {
                    client.send(jsonFrame(mapOf("joinrejected", true)));
                    return;
                }
                requestedID = joined.id;
                Player old = players.get(requestedID);
                if (old != null && old.connection != null) replaced.add(old.connection);
                if (old == null || !old.identity.equals(identity))
                    powerupAuthority.resetPlayer(requestedID);
                players.remove(requestedID);
                activeClients.remove(requestedID);
                gossipState[requestedID] = new StateRow(1 | (joined.infected ? 8 : 0), 1, 0,
                        2, 0, 0, 0, joined.kills, joined.deaths, 0, 0, 0, 0, 0, 0, 0, joined.captures)
                        .withGeneration(joined.generation, 0);
                lastGossipPacketSequence[requestedID] = observedCombatSequence[requestedID] = 0;
                clearEnemyGpsRevealsForPlayerLocked(requestedID);
                endVotes &= ~(1L << (requestedID - 1));
                if (assignedTeamsEnabledLocked()) {
                    Map<Integer, Integer> teams = new HashMap<>(balancedTeams);
                    teams.put(requestedID, joined.team);
                    balancedTeams = teams;
                    balancedCheckedIn.add(requestedID);
                }
                pendingAssignments.remove(client.address());
                client.send(jsonFrame(mapOf("joinedplayerid", requestedID, "roundtoken", roundToken)));
            } else if (requestedTeam > 0) {
                client.send(jsonFrame(mapOf("joinrejected", true)));
                return;
            }

            PendingAssignment assignment = pendingAssignments.get(client.address());
            if (assignment != null && assignment.playerID != requestedID) {
                client.close();
                return;
            }
            if (assignment != null)
                pendingAssignments.remove(client.address());

            Player existing = players.get(requestedID);
            if (existing != null && existing.relayNode != null && !existing.identity.equals(identity)) {
                client.close();
                return;
            }
            if (joined == null && isRoundActiveLocked() && roundRoster != null) {
                RoundRoster.Member member = roundRoster.person(identity);
                RoundRoster.Member occupant = roundRoster.seat(requestedID);
                if (member != null && (!member.active || member.id != requestedID)
                        || occupant != null && !occupant.identity.equals(identity)) {
                    client.send(jsonFrame(mapOf("joinrejected", true)));
                    return;
                }
            }
            // Normal phones discover over UDP first, but a TCP client can be
            // pointed directly at the laptop. Honor --no-late-join for that
            // path too, while retaining reconnects for players already in the
            // current round.
            if (joined == null && isRoundActiveLocked() && (existing == null && !config.allowLateJoin
                    || assignedTeamsEnabledLocked() && !balancedTeams.containsKey(requestedID))) {
                client.close();
                return;
            }
            if (existing != null && existing.connected && existing.connection != client) {
                if (!rejoin || !client.address().equals(existing.address)) {
                    client.close();
                    return;
                }
                replaced.add(existing.connection);
            }
            for (Player player : players.values()) {
                if (player.id != requestedID && player.connected && client.address().equals(player.address)
                        && !player.identity.equals(identity)) {
                    client.close();
                    return;
                }
            }
            if (existing != null && !existing.connected && isRoundActiveLocked()
                    && !client.address().equals(existing.address) && !rejoin) {
                client.close();
                return;
            }
            // Discovery knows an endpoint before TCP proves its saved identity.
            // A changed phone address can therefore receive a different seat.
            // Once admitted, retire every older seat for that same person.
            boolean movedSeat = retireOtherIdentitySeatsLocked(identity, requestedID, replaced);
            if (existing == null) {
                existing = new Player(requestedID, name, client.address(), defaultSettings(requestedID));
                players.put(requestedID, existing);
            }
            if (!existing.identity.equals(identity))
                balancedCheckedIn.remove(requestedID);
            existing.identity = identity;
            existing.deviceIdentity = deviceIdentity;
            Settings editedSettings = hostPlayerSettings.get(identity);
            if (editedSettings != null && !fixedModeRules()) existing.settings = editedSettings;
            if (joined != null) { existing.kills = joined.kills; existing.deaths = joined.deaths; }
            if (isRoundActiveLocked() && roundRoster != null && roundRoster.person(identity) == null) {
                // Retain the explicit operator-controlled legacy invite path too.
                RoundRoster.Member member = new RoundRoster.Member(identity, requestedID,
                        config.gameMode == GameMode.FFA ? 0 : teamFor(requestedID), 0);
                roundRoster.restore(member);
            }
            existing.name = hostPlayerNames.getOrDefault(identity, name);
            client.lobbyReady = !Boolean.FALSE.equals(json.get("lobbyready"));
            client.lobbyBenched = joined != null ? false : hostPlayerParticipation.getOrDefault(identity,
                    existing.connection != null ? existing.benched()
                            : Boolean.TRUE.equals(json.get("lobbybenched")));
            boolean sameRoundBaseline = historyProvided && isRoundActiveLocked()
                    && roundToken != null && roundToken.equals(historyToken);
            // Saved laptop totals may already include this round's partial
            // scores. Compare frozen baselines before calculating final skill.
            boolean profileIncludesRound = roundToken != null && roundToken.equals(profile.lastRound);
            int savedKills = Math.max(0, profile.totalKills - (profileIncludesRound ? profile.lastKills : 0));
            int savedDeaths = Math.max(0, profile.totalDeaths - (profileIncludesRound ? profile.lastDeaths : 0));
            existing.priorKills = Math.max(sameRoundBaseline ? historyKills : priorKills,
                    Math.min(100_000, savedKills));
            existing.priorDeaths = Math.max(sameRoundBaseline ? historyDeaths : priorDeaths,
                    Math.min(100_000, savedDeaths));
            existing.analyticsCapable = Boolean.TRUE.equals(json.get("roundanalytics"));
            existing.address = client.address();
            existing.connected = true;
            existing.connection = client;
            relayCapabilities(existing, json);
            if (existing.relayNode != null && relayAddress == null) relayAddress = client.socket.getLocalAddress();
            client.playerID = requestedID;
            client.eventControlVersion = intValue(json.get(EventMode.CAPABILITY), 0);
            activeClients.put(requestedID, client);
            if (isRoundActiveLocked() && roundReview != null) {
                roundReview.enroll(existing, config.gameMode == GameMode.FFA ? 0 : teamFor(existing.id));
                roundReview.event(elapsedMillis(), "join", existing, null, 1,
                        rejoin ? "Reconnected" : "Joined during round");
            }
            if (roundState == RoundState.LOBBY && (replaced.isEmpty() || movedSeat)) invalidateTeamAssignmentsLocked();
            accepted = true;
            savePlayerDirectoryLocked();
        }
        for (ClientConnection previous : replaced)
            if (previous != client) previous.close();
        if (accepted) {
            broadcastRoster();
            broadcastGrenadePairings();
            String resume = null;
            synchronized (stateLock) {
                Player player = playerForClientLocked(client);
                ReviewPlayer person = roundReview == null || player == null ? null : roundReview.person(player);
                RoundRoster.Member member = roundRoster == null ? null : roundRoster.seat(client.playerID);
                if (isRoundActiveLocked() && player != null && player.analyticsCapable
                        && person != null && member != null)
                    resume = jsonFrame(mapOf("telemetryresume", mapOf("roundtoken", roundToken,
                            "generation", member.generation, "life", person.deaths,
                            "telemetryseq", person.telemetrySequence, "total", person.shots)));
            }
            if (resume != null) client.send(resume);
        }
    }

    /** Caller holds stateLock and has validated the new registration. */
    private boolean retireOtherIdentitySeatsLocked(String identity, int keepID, List<ClientConnection> replaced) {
        boolean removed = false;
        Iterator<Player> iterator = players.values().iterator();
        while (iterator.hasNext()) {
            Player previous = iterator.next();
            if (previous.id == keepID || !previous.identity.equals(identity)) continue;
            recordPlayerProfileLocked(previous);
            if (previous.connection != null) replaced.add(previous.connection);
            iterator.remove();
            activeClients.remove(previous.id);
            grenadeOwners.entrySet().removeIf(entry -> entry.getValue() == previous.id);
            pendingAssignments.entrySet().removeIf(entry -> entry.getValue().playerID == previous.id);
            clearEnemyGpsRevealsForPlayerLocked(previous.id);
            endVotes &= ~(1L << (previous.id - 1));
            powerupAuthority.resetPlayer(previous.id);
            gossipState[previous.id] = null;
            gossipStateReceivedAt[previous.id] = 0;
            lastGossipPacketSequence[previous.id] = observedCombatSequence[previous.id] = 0;
            observedCombatType[previous.id] = observedCombatTarget[previous.id] = 0;
            removed = true;
        }
        return removed;
    }

    private void handleMessage(ClientConnection client, String message) {
        if (message == null || client.playerID <= 0)
            return;
        if (MSG_LEAVE.equals(message) || MSG_QUIT.equals(message)) {
            synchronized (stateLock) {
                client.left = true;
                rememberRoundStatsLocked();
                if (roundRoster != null) roundRoster.leave(client.playerID);
            }
            client.close();
            return;
        }
        if (client.lobbyBenched) return;
        if (message.startsWith(MSG_ELIMINATED)) {
            synchronized (stateLock) {
                Player reporter = playerForClientLocked(client);
                if (reporter != null && reporter.analyticsCapable) return;
                if (roundReview != null && reporter != null && roundReview.person(reporter) != null)
                    roundReview.person(reporter).complete = false;
            }
            processElimination(client, message.substring(MSG_ELIMINATED.length()));
            return;
        }
        if (MSG_RESPAWN_REQUEST.equals(message)) {
            if (config.respawnQrEnabled)
                grantRespawn(client.playerID);
            return;
        }
        if (MSG_RESPAWN_COMPLETE.equals(message)) {
            synchronized (stateLock) {
                updateRoundStateLocked();
                Player player = playerForClientLocked(client);
                if (roundState == RoundState.RUNNING && player != null && player.awaitingRespawn) {
                    player.awaitingRespawn = false;
                    player.eliminated = false;
                    if (roundReview != null)
                        roundReview.event(elapsedMillis(), "respawn", player, null, 1, "Respawn completed");
                }
            }
            return;
        }
        if (MSG_PLAYER_DATA_REQUEST.equals(message)) {
            // Legacy phones open a modal for every playerdata frame. Keep this
            // response opt-in; automatic roster, edit and round updates stay silent.
            client.send(playerDataFrame());
            return;
        }
        if (MSG_START_GAME.equals(message)) {
            if (eventMode || assignedTeamsEnabledLocked())
                return; // The laptop operator fixes the roster being balanced.
            StartResult result = startRound();
            if (!result.ok && result.clockWaiting)
                client.send(messageFrame(MSG_CLOCK_WAITING));
            return;
        }
        if (MSG_END_GAME.equals(message)) {
            // Round-scoped JSON approvals replace single-player ENDGAME requests.
            return;
        }
    }

    private void processElimination(ClientConnection victimConnection, String attackerText) {
        int attackerID;
        try {
            attackerID = Integer.parseInt(attackerText);
        } catch (NumberFormatException e) {
            return;
        }
        ClientConnection scorerConnection = null;
        List<ClientConnection> scorerTeam = Collections.emptyList();
        boolean reachedScoreLimit = false;
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (roundState != RoundState.RUNNING || attackerID != 0 && !validPlayerID(attackerID))
                return;
            Player victim = playerForClientLocked(victimConnection);
            Player attacker = players.get(attackerID);
            if (victim == null || attackerID != 0 && (attacker == null || attacker == victim
                    || !attacker.connected || !areOpponents(attacker.id, victim.id)) || victim.awaitingRespawn)
                return;
            victim.awaitingRespawn = true;
            victim.eliminated = true;
            victim.deaths = addScore(victim.deaths, 1);
            if (attacker != null) attacker.kills = addScore(attacker.kills, 1);
            if (roundReview != null) roundReview.kill(elapsedMillis(), attacker, victim);
            if (victim.lastGrenadeOwnerID != attackerID
                    || elapsedMillis() - victim.lastGrenadeHitAt > GRENADE_BURST_WINDOW_MS)
                addLaserLocked(attacker, victim, "kill");
            victim.lastGrenadeHitAt = 0;
            // A death ends the short confirmed-hit map reveal immediately so
            // a respawn cannot inherit an opponent's last known position.
            clearEnemyGpsRevealsForPlayerLocked(victim.id);
            if (attacker != null) clearEnemyGpsRevealsForPlayerLocked(attacker.id);
            scorerConnection = attacker == null ? null : attacker.connection;
            if (attacker != null && config.gameMode != GameMode.FFA) {
                scorerTeam = new ArrayList<>();
                int scoringTeam = teamFor(attacker.id);
                for (Player player : players.values()) {
                    if (player.connected && !player.benched() && player.connection != null && teamFor(player.id) == scoringTeam)
                        scorerTeam.add(player.connection);
                }
            }
            reachedScoreLimit = attacker != null && hasReachedScoreLimitLocked(attacker);
        }
        if (scorerConnection != null)
            scorerConnection.send(messageFrame(MSG_ELIMINATED + victimConnection.playerID));
        for (ClientConnection teammate : scorerTeam) {
            if (teammate != scorerConnection)
                teammate.send(messageFrame(MSG_TEAM_ELIMINATED));
        }
        if (reachedScoreLimit)
            endRound("score limit reached");
    }

    private StartResult startRound() {
        List<ClientConnection> recipients;
        String startFrame;
        String token;
        boolean publishedAssignment = false;
        synchronized (stateLock) {
            if (adbPhoneLease != null)
                return StartResult.error("Wait for the phone update to finish.", false);
            if (hotspot.isBusy())
                return StartResult.error("Wait for the Wi-Fi change to finish.", false);
            updateRoundStateLocked();
            long cooldown = Math.max(0, nextRoundStartAllowedAt - elapsedMillis());
            if (cooldown > 0)
                return StartResult.error("Next game can start in "
                        + ((cooldown + 999) / 1_000) + " seconds.", false);
            if (roundState == RoundState.FINISHED)
                resetForNewLobbyLocked();
            if (roundState != RoundState.LOBBY)
                return StartResult.error("A game is already running.", false);
            recipients = playingClientsLocked();
            if (recipients.size() < 2)
                return StartResult.error("At least two phones must join first.", false);
            if (config.boss && !hasPlayingLeaderLocked())
                return StartResult.error("Boss Mode requires Player 1 and at least one hunter.", false);
            if (config.infection && !hasPlayingLeaderLocked())
                return StartResult.error("Infection requires Player 1 and at least one survivor.", false);
            for (ClientConnection client : recipients) {
                if (!client.lobbyReady)
                    return StartResult.error("Waiting for every player to connect a gun.", false);
                if (!client.clockReady)
                    return StartResult.error("Waiting for phone clock synchronization.", true);
            }
            if (assignedTeamsEnabledLocked() && balancedTeams.isEmpty()) {
                balancedTeams = computeBalancedTeamsLocked();
                rememberBalancedTeamsLocked();
                publishedAssignment = true;
            } else if (config.balanced && hostPlayerTeams.isEmpty()) {
                publishedAssignment = synchronizeBalancedTeamsLocked();
            }
            if (balancedQrEnabledLocked() && balancedCheckInDeadline == 0) {
                balancedCheckedIn.clear();
                balancedCheckInDeadline = balancedQrEnabledLocked()
                        ? elapsedMillis() + BALANCED_QR_CHECK_IN_TIMEOUT_MS : 0;
                publishedAssignment = true;
            }
        }
        if (publishedAssignment)
            broadcastRoster();
        synchronized (stateLock) {
            if (adbPhoneLease != null)
                return StartResult.error("Wait for the phone update to finish.", false);
            if (hotspot.isBusy())
                return StartResult.error("Wait for the Wi-Fi change to finish.", false);
            updateRoundStateLocked();
            long cooldown = Math.max(0, nextRoundStartAllowedAt - elapsedMillis());
            if (cooldown > 0)
                return StartResult.error("Next game can start in "
                        + ((cooldown + 999) / 1_000) + " seconds.", false);
            if (roundState != RoundState.LOBBY)
                return StartResult.error("A game is already running.", false);
            recipients = playingClientsLocked();
            if (recipients.size() < 2)
                return StartResult.error("At least two phones must join first.", false);
            if (config.boss && !hasPlayingLeaderLocked())
                return StartResult.error("Boss Mode requires Player 1 and at least one hunter.", false);
            if (config.infection && !hasPlayingLeaderLocked())
                return StartResult.error("Infection requires Player 1 and at least one survivor.", false);
            for (ClientConnection client : recipients) {
                if (!client.lobbyReady)
                    return StartResult.error("Waiting for every player to connect a gun.", false);
                if (!client.clockReady)
                    return StartResult.error("Waiting for phone clock synchronization.", true);
            }
            if (assignedTeamsEnabledLocked() && balancedTeams.size() != recipients.size())
                return StartResult.error("The lobby roster changed. Press Start again.", false);
            if (balancedQrEnabledLocked() && !balancedCheckedIn.containsAll(balancedTeams.keySet())
                    && (balancedCheckInDeadline == 0 || elapsedMillis() < balancedCheckInDeadline))
                return StartResult.waitingForQr();
            roundID++;
            grenadeBursts.clear();
            roundToken = UUID.randomUUID().toString();
            roundRoster = new RoundRoster(roundToken);
            powerupAuthority.resetRound();
            for (ClientConnection client : recipients) {
                Player player = players.get(client.playerID);
                RoundRoster.Member member = new RoundRoster.Member(player.identity, player.id,
                        config.gameMode == GameMode.FFA ? 0 : teamFor(player.id), 0);
                member.infected = config.infection && player.id == 1;
                roundRoster.restore(member);
            }
            endVotes = 0;
            endVoteRequested = false;
            token = roundToken;
            authorityTickSequence = 0;
            for (int playerID = 0; playerID <= MAX_PLAYERS; playerID++) {
                gossipState[playerID] = null;
                gossipStateReceivedAt[playerID] = 0;
                lastGossipPacketSequence[playerID] = 0;
                observedCombatSequence[playerID] = 0;
                observedCombatType[playerID] = 0;
                observedCombatTarget[playerID] = 0;
            }
            roundStartAt = elapsedMillis() + (config.balanced ? GameStartCountdown.DURATION_MS
                    : config.startDelaySeconds * 1_000L);
            roundDuration = config.durationMinutes * 60_000L;
            roundEndAt = roundDuration == 0 ? 0 : roundStartAt + roundDuration;
            roundState = RoundState.COUNTDOWN;
            roundReview = new RoundReview(roundToken, roundID, roundStartAt,
                    config.boss ? "boss" : config.infection ? "infection"
                            : config.captureTheFlag ? "capture-the-flag" : "teams");
            if (config.boss)
                bossHunterCount = Math.max(1, recipients.size() - 1);
            balancedCheckInDeadline = 0;
            for (Player player : players.values()) {
                player.kills = 0;
                player.deaths = 0;
                player.shots = 0;
                player.hits = 0;
                player.lastGrenadeHitAt = 0;
                player.lastGrenadeOwnerID = 0;
                player.awaitingRespawn = false;
                player.eliminated = false;
                if (player.connected && !player.benched())
                    roundReview.enroll(player, config.gameMode == GameMode.FFA ? 0 : teamFor(player.id));
            }
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("roundid", roundID);
            start.put("roundtoken", roundToken);
            start.put("roundroster", roundRoster.encode());
            start.put("gamestart", roundStartAt);
            start.put("gameduration", roundDuration);
            long gpsStartTime = gpsTimeAtElapsedLocked(roundStartAt);
            if (validGpsUtcTime(gpsStartTime))
                start.put(JSON_GPS_START_TIME, gpsStartTime);
            startFrame = jsonFrame(start);
            // Queue the announcement before releasing round state. An immediate
            // end must follow this start on every retained connection.
            for (ClientConnection client : recipients)
                client.send(startFrame);
            for (ClientConnection client : connectedClientsLocked())
                if (client.lobbyBenched) client.send(jsonFrame(mapOf("lobbywait", true)));
        }
        // Refresh the lobby immediately after the start frame so a phone joining
        // near the edge sees dedicated-host state and the shared deadline.
        broadcastRoster();
        broadcastGameInvite(token);
        return StartResult.ok();
    }

    /** Caller holds stateLock. Cache assignments by person rather than temporary seat ID. */
    private void rememberBalancedTeamsLocked() {
        for (Player player : players.values()) {
            Integer team = balancedTeams.get(player.id);
            if (team != null)
                retainedBalancedTeams.put(player.identity, team);
        }
    }

    /** Caller holds stateLock. Prune departures and place newcomers without moving their teammates. */
    private boolean synchronizeBalancedTeamsLocked() {
        if (!config.balanced || !hostPlayerTeams.isEmpty() || retainedBalancedTeams.isEmpty())
            return false; // The first Start still balances the complete initial lobby.
        Map<Integer, Integer> assigned = new HashMap<>();
        int[] counts = new int[3];
        int[] strengths = new int[3];
        List<Player> newcomers = new ArrayList<>();
        for (Player player : players.values()) {
            if (!player.connected || player.benched()) continue;
            Integer team = retainedBalancedTeams.get(player.identity);
            if (team == null) {
                newcomers.add(player);
            } else {
                assigned.put(player.id, team);
                counts[team]++;
                strengths[team] += player.strength();
            }
        }
        for (Player player : newcomers) {
            int team = counts[1] < counts[2] ? 1 : counts[2] < counts[1] ? 2
                    : strengths[1] <= strengths[2] ? 1 : 2;
            assigned.put(player.id, team);
            retainedBalancedTeams.put(player.identity, team);
            counts[team]++;
            strengths[team] += player.strength();
        }
        if (assigned.equals(balancedTeams)) return false;
        // Only changed seats need to check in again; unrelated arrivals or
        // departures must not reset other players' scans or extend the timeout.
        balancedCheckedIn.removeIf(id -> !assigned.containsKey(id)
                || !assigned.get(id).equals(balancedTeams.get(id)));
        balancedTeams = Collections.unmodifiableMap(assigned);
        return true;
    }

    private StartResult rebalanceTeams() {
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (isRoundActiveLocked() || !config.balanced || !hostPlayerTeams.isEmpty()
                    || config.gameMode != GameMode.TWO_TEAMS)
                return StartResult.error("Teams can only be rebalanced in a Balanced Random lobby.", false);
            if (playingClientsLocked().size() < 2)
                return StartResult.error("At least two phones must join first.", false);
            if (roundState == RoundState.FINISHED) resetForNewLobbyLocked();
            balancedTeams = computeBalancedTeamsLocked();
            retainedBalancedTeams.clear();
            rememberBalancedTeamsLocked();
            balancedCheckedIn.clear();
            balancedCheckInDeadline = 0;
        }
        broadcastRoster();
        return new StartResult(true, "Teams rebalanced. Press Start when ready.", false);
    }

    /** Caller holds stateLock. Dynamic programming keeps a 32-player lobby practical. */
    private Map<Integer, Integer> computeBalancedTeamsLocked() {
        if (!hostPlayerTeams.isEmpty()) {
            Map<Integer, Integer> manual = new HashMap<>();
            for (Player player : players.values())
                if (player.connected && !player.benched()) manual.put(player.id,
                        hostPlayerTeams.getOrDefault(player.identity, baseTeamFor(player.id)));
            return Collections.unmodifiableMap(manual);
        }
        List<Player> candidates = new ArrayList<>();
        for (Player player : players.values()) {
            if (player.connected && !player.benched())
                candidates.add(player);
        }
        Collections.shuffle(candidates, balanceRandom);
        int total = 0;
        for (Player player : candidates)
            total += player.strength();
        int playerCount = candidates.size();
        boolean[][][] reachable = new boolean[playerCount + 1][playerCount + 1][total + 1];
        reachable[0][0][0] = true;
        for (int index = 0; index < playerCount; index++) {
            int strength = candidates.get(index).strength();
            for (int count = 0; count <= index; count++) {
                for (int sum = 0; sum <= total; sum++) {
                    if (!reachable[index][count][sum])
                        continue;
                    reachable[index + 1][count][sum] = true;
                    reachable[index + 1][count + 1][sum + strength] = true;
                }
            }
        }
        int bestCount = 0;
        int bestStrength = 0;
        int bestGap = Integer.MAX_VALUE;
        int ties = 0;
        for (int count = 1; count < playerCount; count++) {
            if (Math.abs(playerCount - 2 * count) > 2)
                continue;
            for (int strength = 0; strength <= total; strength++) {
                if (!reachable[playerCount][count][strength])
                    continue;
                int gap = Math.abs(total - 2 * strength);
                if (gap < bestGap || (gap == bestGap && balanceRandom.nextInt(++ties) == 0)) {
                    if (gap < bestGap)
                        ties = 1;
                    bestGap = gap;
                    bestCount = count;
                    bestStrength = strength;
                }
            }
        }
        boolean[] onTeamOne = new boolean[playerCount];
        int remainingCount = bestCount;
        int remainingStrength = bestStrength;
        for (int index = playerCount; index > 0; index--) {
            int strength = candidates.get(index - 1).strength();
            boolean canExclude = reachable[index - 1][remainingCount][remainingStrength];
            boolean canInclude = remainingCount > 0 && remainingStrength >= strength
                    && reachable[index - 1][remainingCount - 1][remainingStrength - strength];
            if (canInclude && (!canExclude || balanceRandom.nextBoolean())) {
                onTeamOne[index - 1] = true;
                remainingCount--;
                remainingStrength -= strength;
            }
        }
        boolean flip = balanceRandom.nextBoolean();
        Map<Integer, Integer> assigned = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            int team = onTeamOne[index] ? 1 : 2;
            assigned.put(candidates.get(index).id, flip ? 3 - team : team);
        }
        return Collections.unmodifiableMap(assigned);
    }

    private boolean requestEndVote(ClientConnection voter, String token, boolean approve) {
        final boolean agreed;
        synchronized (stateLock) {
            if (eventMode && voter != null) return false;
            updateRoundStateLocked();
            if (!isRoundActiveLocked() || roundToken == null) return false;
            if (!roundToken.equals(token) || voter != null && playerForClientLocked(voter) == null)
                return false;
            long participants = 0;
            for (ClientConnection client : playingClientsLocked())
                participants |= 1L << (client.playerID - 1);
            endVotes &= participants;
            if (voter != null) {
                long bit = 1L << (voter.playerID - 1);
                endVotes = approve ? endVotes | bit : endVotes & ~bit;
            }
            endVoteRequested = true;
            agreed = Long.bitCount(endVotes) >= 2;
        }
        broadcastEndVotes();
        if (agreed) endRound("two players agreed to end");
        return true;
    }

    private void broadcastEndVotes() {
        final List<ClientConnection> recipients;
        final String frame;
        synchronized (stateLock) {
            if (!isRoundActiveLocked() || !endVoteRequested || roundToken == null) return;
            recipients = playingClientsLocked();
            frame = jsonFrame(mapOf("endvote", true, "endvotes", endVotes, "roundtoken", roundToken));
        }
        for (ClientConnection client : recipients) client.send(frame);
    }

    private void endRound(String reason) {
        List<ClientConnection> recipients;
        RoundReport report;
        final String finalPlayerData;
        Map<ClientConnection, Map<String, Object>> personalFrames = new HashMap<>();
        synchronized (stateLock) {
            if (roundState != RoundState.COUNTDOWN && roundState != RoundState.RUNNING)
                return;
            updateRoundStateLocked();
            rememberRoundStatsLocked();
            finalPlayerData = playerDataFrame();
            report = roundReview == null || !roundReview.hasStarted ? null
                    : roundReview.freeze(elapsedMillis(), reason);
            if (report != null) {
                roundReports.addFirst(report);
                while (roundReports.size() > MAX_REPORT_HISTORY) roundReports.removeLast();
            }
            roundState = RoundState.FINISHED;
            rememberBalancedTeamsLocked();
            balancedCheckedIn.clear();
            balancedCheckInDeadline = 0;
            nextRoundStartAllowedAt = Math.max(nextRoundStartAllowedAt,
                    com.simplecoil.protocol.RoundRestart.allowedAt(elapsedMillis(), roundStartAt));
            recipients = connectedClientsLocked();
            enemyGpsRevealUntil.clear();
            gpsVisibilityRefreshPlayers.addAll(activeClients.keySet());
            savePlayerDirectoryLocked();
            if (report != null) for (ClientConnection client : recipients) {
                Player player = players.get(client.playerID);
                if (player != null && report.personal.containsKey(player.identity))
                    personalFrames.put(client, report.personal.get(player.identity));
            }
            for (ClientConnection client : recipients) {
                // Keep the final batch contiguous, and queue it before another
                // Start can prepare the retained lobby. ENDGAME ends the round;
                // heartbeats, clock refreshes and the next round reuse this TCP
                // relationship and its already-calibrated clock.
                synchronized (client.outboundLock) {
                    client.send(finalPlayerData);
                    Map<String, Object> personal = personalFrames.get(client);
                    if (personal != null) client.send(jsonFrame(mapOf("roundreport", personal)));
                    client.send(messageFrame(MSG_END_GAME));
                }
            }
        }
        if (report != null) persistReport(report);
        System.out.println("Game finished: " + reason);
    }

    private boolean grantRespawn(int playerID) {
        ClientConnection recipient = null;
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (roundState != RoundState.RUNNING)
                return false;
            Player player = players.get(playerID);
            if (player == null || player.benched() || !player.connected || !player.awaitingRespawn || player.connection == null)
                return false;
            int lives = player.settings.overrideLives ? player.settings.lives : config.livesLimit;
            if (lives > 0 && player.deaths >= lives) return false;
            player.awaitingRespawn = false;
            player.eliminated = false;
            if (roundReview != null)
                roundReview.event(elapsedMillis(), "respawn", player, null, 1, "Respawn granted");
            recipient = player.connection;
        }
        recipient.send(messageFrame(MSG_RESPAWN_GRANTED));
        return true;
    }

    private void advanceRoundClock() {
        boolean timeExpired = false;
        boolean captureLimitReached = false;
        boolean infectionFinished = false;
        boolean qrCheckInTimedOut = false;
        synchronized (stateLock) {
            updateRoundStateLocked();
            timeExpired = roundState == RoundState.RUNNING && roundEndAt > 0 && elapsedMillis() >= roundEndAt;
            captureLimitReached = roundState == RoundState.RUNNING
                    && config.captureTheFlag && config.scoreLimit > 0
                    && ctfCaptureLimitReachedLocked();
            infectionFinished = roundState == RoundState.RUNNING && config.infection
                    && infectionRoundFinishedLocked();
            qrCheckInTimedOut = balancedQrEnabledLocked() && roundState == RoundState.LOBBY
                    && !balancedTeams.isEmpty() && balancedCheckInDeadline > 0
                    && elapsedMillis() >= balancedCheckInDeadline;
        }
        if (timeExpired)
            endRound("time limit reached");
        else if (captureLimitReached)
            endRound("capture limit reached");
        else if (infectionFinished)
            endRound("Infection winner decided");
        if (qrCheckInTimedOut)
            startRound();
    }

    /** Caller holds stateLock. Player 1 is infected from the opening countdown. */
    private boolean allPlayersInfectedLocked() {
        if (playingClientsLocked().size() < 2 || !hasPlayingLeaderLocked())
            return false;
        for (int playerID : activeClients.keySet()) {
            if (activeClients.get(playerID).lobbyBenched) continue;
            if (playerID == 1)
                continue;
            StateRow row = gossipState[playerID];
            if (row == null || (row.flags & StatePacket.FLAG_INFECTED) == 0)
                return false;
        }
        return true;
    }

    /** Caller holds stateLock. End on full conversion or after conversion leaves one survivor. */
    private boolean infectionRoundFinishedLocked() {
        if (allPlayersInfectedLocked())
            return true;
        if (playingClientsLocked().size() < 3 || !hasPlayingLeaderLocked())
            return false;
        int infected = 1;
        for (int playerID : activeClients.keySet()) {
            if (activeClients.get(playerID).lobbyBenched) continue;
            if (playerID == 1)
                continue;
            StateRow row = gossipState[playerID];
            if (row != null && (row.flags & StatePacket.FLAG_INFECTED) != 0)
                infected++;
        }
        return infected >= 2 && playingClientsLocked().size() - infected == 1;
    }

    // Caller holds stateLock. Capture totals are source-owned cumulative
    // counters relayed in the same authority tick as the rest of player state.
    private boolean ctfCaptureLimitReachedLocked() {
        int[] totals = new int[3];
        if (roundRoster != null)
            for (int team = 1; team <= 2; team++) totals[team] = Math.min(0xffff,
                    roundRoster.archivedScore(team, true));
        for (int playerID = 1; playerID <= MAX_PLAYERS; playerID++) {
            StateRow row = gossipState[playerID];
            if (row == null)
                continue;
            int team = teamFor(playerID);
            if (team >= 1 && team <= 2) {
                totals[team] = Math.min(Integer.MAX_VALUE,
                        totals[team] + row.ctfCaptures);
                if (totals[team] >= config.scoreLimit)
                    return true;
            }
        }
        return false;
    }

    private void updateRoundStateLocked() {
        if (roundState == RoundState.COUNTDOWN && elapsedMillis() >= roundStartAt) {
            roundState = RoundState.RUNNING;
            if (roundReview != null) roundReview.started();
        }
    }

    private void sendHeartbeats() {
        for (ClientConnection client : connectedClients())
            client.send(MSG_PING);
    }

    private void disconnectUnresponsiveClients() {
        long now = elapsedMillis();
        List<ClientConnection> stale = new ArrayList<>();
        synchronized (stateLock) {
            for (ClientConnection client : activeClients.values()) {
                if (!client.closed && now - client.lastReceivedAt > CLIENT_IDLE_TIMEOUT_MS)
                    stale.add(client);
            }
        }
        for (ClientConnection client : stale) {
            if (client.socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket)
                System.err.println("Game relay client " + client.clientID + " heartbeat timed out");
            client.close();
        }
    }

    /** Caller holds stateLock. */
    private void grantEnemyGpsRevealLocked(int firstPlayerID, int secondPlayerID) {
        if (config.gpsMode != GpsMode.TEAMMATE || config.gameMode == GameMode.FFA
                || !areOpponents(firstPlayerID, secondPlayerID))
            return;
        long expiresAt = elapsedMillis() + HIT_ENEMY_GPS_REVEAL_MS;
        grantEnemyGpsRevealLocked(firstPlayerID, secondPlayerID, expiresAt);
        int firstTeam = teamFor(firstPlayerID);
        int secondTeam = teamFor(secondPlayerID);
        for (Player player : players.values()) {
            if (!player.connected)
                continue;
            if (teamFor(player.id) == firstTeam)
                grantEnemyGpsRevealLocked(player.id, secondPlayerID, expiresAt);
            else if (teamFor(player.id) == secondTeam)
                grantEnemyGpsRevealLocked(player.id, firstPlayerID, expiresAt);
        }
    }

    /** Caller holds stateLock. */
    private void grantEnemyGpsRevealLocked(int recipientID, int enemyID, long expiresAt) {
        Map<Integer, Long> reveals = enemyGpsRevealUntil.get(recipientID);
        if (reveals == null) {
            reveals = new HashMap<>();
            enemyGpsRevealUntil.put(recipientID, reveals);
        }
        reveals.put(enemyID, expiresAt);
        gpsVisibilityRefreshPlayers.add(recipientID);
    }

    /** Caller holds stateLock. */
    private void expireEnemyGpsRevealsLocked(long now) {
        java.util.Iterator<Map.Entry<Integer, Map<Integer, Long>>> recipients
                = enemyGpsRevealUntil.entrySet().iterator();
        while (recipients.hasNext()) {
            Map.Entry<Integer, Map<Integer, Long>> recipient = recipients.next();
            boolean changed = false;
            java.util.Iterator<Map.Entry<Integer, Long>> enemies = recipient.getValue().entrySet().iterator();
            while (enemies.hasNext()) {
                Long expiresAt = enemies.next().getValue();
                if (expiresAt == null || expiresAt <= now) {
                    enemies.remove();
                    changed = true;
                }
            }
            if (changed)
                gpsVisibilityRefreshPlayers.add(recipient.getKey());
            if (recipient.getValue().isEmpty())
                recipients.remove();
        }
    }

    /** Caller holds stateLock. */
    private void clearEnemyGpsRevealsForPlayerLocked(int playerID) {
        if (enemyGpsRevealUntil.remove(playerID) != null)
            gpsVisibilityRefreshPlayers.add(playerID);
        java.util.Iterator<Map.Entry<Integer, Map<Integer, Long>>> recipients
                = enemyGpsRevealUntil.entrySet().iterator();
        while (recipients.hasNext()) {
            Map.Entry<Integer, Map<Integer, Long>> recipient = recipients.next();
            if (recipient.getValue().remove(playerID) != null)
                gpsVisibilityRefreshPlayers.add(recipient.getKey());
            if (recipient.getValue().isEmpty())
                recipients.remove();
        }
    }

    private void publishGps() {
        List<Position> changedPositions = new ArrayList<>();
        List<Position> fullSnapshot = new ArrayList<>();
        List<ClientConnection> recipients;
        boolean fullUpdate;
        boolean visibilityRefreshPending;
        synchronized (stateLock) {
            if (!config.useGps)
                return;
            fullUpdate = ++gpsTick >= GPS_FULL_UPDATE_EVERY_TICKS;
            if (fullUpdate)
                gpsTick = 0;
            long now = elapsedMillis();
            expireEnemyGpsRevealsLocked(now);
            for (Player player : players.values()) {
                if (!player.connected || player.benched() || !validCoordinates(player.longitude, player.latitude)
                        || player.lastGpsAt == Long.MIN_VALUE
                        || now - player.lastGpsAt > GPS_STALE_AFTER_MS)
                    continue;
                Position position = new Position(player.id, teamFor(player.id), player.longitude,
                        player.latitude, player.lastGpsAt);
                fullSnapshot.add(position);
                if (player.gpsDirty || fullUpdate)
                    changedPositions.add(position);
                player.gpsDirty = false;
            }
            visibilityRefreshPending = !gpsVisibilityRefreshPlayers.isEmpty();
            if (changedPositions.isEmpty() && !fullUpdate && !visibilityRefreshPending)
                return;
            recipients = connectedClientsLocked();
        }
        GpsOffset origin = gpsOffset;
        for (ClientConnection client : recipients) {
            int recipientTeam = teamFor(client.playerID);
            final Set<Integer> revealedEnemies;
            final boolean recipientFullUpdate;
            synchronized (stateLock) {
                if (roundState == RoundState.LOBBY && !client.clockReady
                        && !(client.socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket)) {
                    // Clock replies share the native phone's ordered TCP stream.
                    // Retain a full refresh instead of filling its calibration
                    // queue with periodic GPS snapshots. Relay GPS is also used
                    // for nearby discovery and keeps its existing delivery.
                    gpsVisibilityRefreshPlayers.add(client.playerID);
                    continue;
                }
                Map<Integer, Long> reveals = enemyGpsRevealUntil.get(client.playerID);
                revealedEnemies = reveals == null ? Collections.emptySet() : new HashSet<>(reveals.keySet());
                recipientFullUpdate = gpsVisibilityRefreshPlayers.remove(client.playerID) || fullUpdate;
            }
            List<Position> source = recipientFullUpdate ? fullSnapshot : changedPositions;
            List<Object> updates = new ArrayList<>();
            for (Position position : source) {
                long ageMs = elapsedMillis() - position.fixAt;
                if (ageMs < 0 || ageMs > GPS_STALE_AFTER_MS) continue;
                if (config.gpsMode == GpsMode.TEAMMATE && config.gameMode != GameMode.FFA
                        && position.team != recipientTeam && !revealedEnemies.contains(position.playerID))
                    continue;
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("playerID", position.playerID);
                update.put("team", position.team);
                putGpsPosition(update, origin, position.latitude, position.longitude);
                update.put("gpsage", ageMs);
                updates.add(update);
            }
            if (updates.isEmpty() && !recipientFullUpdate)
                continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("gpsupdate", updates);
            payload.put("gpsoffset", gpsOriginMap(origin));
            if (recipientFullUpdate)
                payload.put("gpsfullupdate", true);
            client.send(jsonFrame(payload));
        }
    }

    private void broadcastRoster() {
        for (ClientConnection client : connectedClients())
            sendRoster(client);
    }

    private void sendRoster(ClientConnection client) {
        synchronized (stateLock) {
            if (stopping || playerForClientLocked(client) == null) return;
            // The first snapshot establishes game settings, assignment and host
            // identity. Rebuilding it for every join or gun/clock-ready change
            // fills old phones' receive queues ahead of their clock replies.
            // One current snapshot after calibration includes every deferred edit.
            if (roundState == RoundState.LOBBY && client.initialRosterSent && !client.clockReady) {
                client.rosterPending = true;
                return;
            }
            for (String frame : rosterFrames(client.playerID))
                if (!client.send(frame)) return;
            client.initialRosterSent = true;
            client.rosterPending = false;
        }
    }

    /** Caller holds stateLock. Build the snapshot when dispatched, never in advance. */
    private void scheduleClockRosterBroadcastLocked() {
        if (stopping || clockRosterBroadcast != null) return;
        try {
            clockRosterBroadcast = scheduler.schedule(() -> {
                synchronized (stateLock) {
                    clockRosterBroadcast = null;
                    if (stopping) return;
                }
                broadcastRoster();
            }, CLOCK_ROSTER_COALESCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            // Shutdown already owns the connections; no delayed task may reopen them.
        }
    }

    private void broadcastSettings() {
        String frame = settingsFrame();
        for (ClientConnection client : connectedClients()) {
            synchronized (stateLock) {
                if (roundState == RoundState.LOBBY && !client.clockReady && client.initialRosterSent)
                    client.rosterPending = true;
                else client.send(frame);
            }
        }
    }

    private void broadcastGrenadePairings() {
        String frame = grenadePairingsFrame();
        for (ClientConnection client : connectedClients())
            client.send(frame);
    }

    private List<String> rosterFrames(int targetPlayerID) {
        synchronized (stateLock) {
            updateRoundStateLocked();
            rememberRoundStatsLocked();
            List<Object> roster = new ArrayList<>();
            for (Player player : players.values()) {
                if (!player.connected)
                    continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playername", player.name);
                item.put("playerID", player.id);
                item.put("playerIP", player.address.getHostAddress());
                item.put("lobbyready", player.connection != null && player.connection.lobbyReady);
                item.put("clockready", player.connection != null && player.connection.clockReady);
                item.put("lobbyconnected", player.connected);
                item.put("lobbybenched", player.benched());
                Integer assignedTeam = balancedTeams.get(player.id);
                if (assignedTeam != null) {
                    item.put("team", assignedTeam);
                    item.put("balancedcheckin", balancedCheckedIn.contains(player.id));
                }
                roster.add(item);
            }
            Map<String, Object> limits = new LinkedHashMap<>();
            if (config.durationMinutes > 0)
                limits.put("timelimit", config.durationMinutes);
            if (config.livesLimit > 0)
                limits.put("liveslimit", config.livesLimit);
            if (config.scoreLimit > 0)
                limits.put("scorelimit", config.scoreLimit);

            Map<String, Object> game = new LinkedHashMap<>();
            game.put("players", roster);
            String relay = relayConfigurationLocked();
            if (relay != null) game.put(com.simplecoil.protocol.RelayConfig.WIRE, relay);
            game.put("gpsoffset", gpsOriginMap(gpsOffset));
            game.put("lobbyplaying", isRoundActiveLocked());
            game.put("hostkind", "L");
            game.put("hostage", Math.min(365L * 24 * 60 * 60 * 1000, elapsedMillis()));
            game.put("limits", limits);
            game.put("gamemode", config.gameMode.wireValue);
            game.put("balancedrandom", assignedTeamsEnabledLocked());
            game.put("balancedqr", balancedQrEnabledLocked());
            game.put(EventMode.ASSIGNED_TEAMS, !hostPlayerTeams.isEmpty() && !fixedModeRules());
            game.put(EventMode.CUSTOM_RULES, hostCustomRules);
            game.put(EventMode.ENABLED, eventMode);
            game.put(EventMode.TITLE, eventSettings.title());
            game.put(EventMode.PINNING, eventMode && eventPinning && !releasedPhones.contains(
                    players.containsKey(targetPlayerID) ? players.get(targetPlayerID).identity : ""));
            game.put(EventMode.REVISION, playerEventRevisions.getOrDefault(
                    players.containsKey(targetPlayerID) ? players.get(targetPlayerID).identity : "", eventRevision));
            if (players.containsKey(targetPlayerID)) game.put(EventMode.PLAYER_IDENTITY, players.get(targetPlayerID).identity);
            if (players.containsKey(targetPlayerID)) game.put(EventMode.PIN_HASH,
                    EventMode.pinHash(players.get(targetPlayerID).identity, eventSettings.pin()));
            game.put("balancedprepared", isRoundActiveLocked() || balancedCheckInDeadline > 0);
            game.put("powerupqrrequired", config.powerupQrRequired);
            game.put("respawnqrenabled", config.respawnQrEnabled);
            game.put("bossmode", config.boss);
            game.put("capturetheflag", config.captureTheFlag);
            game.put("infectionmode", config.infection);
            game.put("dedicatedserver", true);
            game.put("maptileport", config.tilePort);
            Player updateTarget = players.get(targetPlayerID);
            game.put("adbmanagedupdates", updateTarget != null && updateTarget.address != null
                    && adbPhoneUpdates.managesAddress(updateTarget.address.getHostAddress()));
            game.put("gamestate", isRoundActiveLocked() ? 1 : 0);
            if (config.useGps)
                game.put("usegps", config.gpsMode.wireValue);
            game.put("allowplayersettings", !config.onlyServerSettings && !config.tournament);
            game.put("playersettings", settingsArrayLocked());
            game.put("onlyserversettings", config.onlyServerSettings || config.tournament);
            game.put("tournamentmode", config.tournament && !hostCustomRules);
            if (isRoundActiveLocked()) {
                putPowerupSnapshotLocked(game, elapsedMillis());
                if (roundRoster != null) game.put("roundroster", roundRoster.encode());
                game.put("roundid", roundID);
                game.put("roundtoken", roundToken);
                game.put("gamestart", roundStartAt);
                game.put("gameduration", roundDuration);
                if (roundEndAt > 0)
                    game.put("timeremaining", secondsRemainingLocked());
            }
            Player target = players.get(targetPlayerID);
            if (target != null) {
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("points", target.kills);
                update.put("eliminated", target.deaths);
                if (config.gameMode != GameMode.FFA)
                    update.put("teampoints", teamKillsLocked(teamFor(target.id)));
                if (roundEndAt > 0 && isRoundActiveLocked())
                    update.put("timeremaining", secondsRemainingLocked());
                game.put("playergameupdate", update);
            }
            String frame = jsonFrame(game);
            if (!game.containsKey("powerupstate") || modifiedUtfBytes(frame) <= ROSTER_UTF_BUDGET)
                return Collections.singletonList(frame);
            // Archived round history and many team-wide grants can together
            // exceed writeUTF's 65,535-byte framing limit. Preserve the exact
            // snapshot clock/expiry, but send it separately after the roster.
            Map<String, Object> snapshot = mapOf("powerupgrants", game.remove("powerupgrants"),
                    "powerupstate", game.remove("powerupstate"), "powerupclock", game.remove("powerupclock"),
                    "roundtoken", roundToken);
            return List.of(jsonFrame(game), jsonFrame(snapshot));
        }
    }

    private static int modifiedUtfBytes(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            bytes += character >= 1 && character <= 0x7f ? 1 : character <= 0x7ff ? 2 : 3;
        }
        return bytes;
    }

    private String settingsFrame() {
        synchronized (stateLock) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("allowplayersettings", !config.onlyServerSettings && !config.tournament);
            payload.put("playersettings", settingsArrayLocked());
            payload.put("onlyserversettings", config.onlyServerSettings || config.tournament);
            payload.put("tournamentmode", config.tournament && !hostCustomRules);
            payload.put(EventMode.CUSTOM_RULES, hostCustomRules);
            payload.put("bossmode", config.boss);
            payload.put("capturetheflag", config.captureTheFlag);
            payload.put("infectionmode", config.infection);
            return jsonFrame(payload);
        }
    }

    private List<Object> settingsArrayLocked() {
        List<Object> settings = new ArrayList<>();
        int hunterCount = config.boss
                ? (isRoundActiveLocked() ? bossHunterCount : Math.max(0, playingClientsLocked().size() - 1)) : 0;
        for (Player player : players.values()) {
            if (!player.connected)
                continue;
            if (config.boss)
                player.settings.applyBoss(player.id == 1, hunterCount);
            settings.add(player.settings.toJson(player.id));
        }
        return settings;
    }

    private String grenadePairingsFrame() {
        synchronized (stateLock) {
            List<Object> pairings = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : grenadeOwners.entrySet()) {
                Map<String, Object> pairing = new LinkedHashMap<>();
                pairing.put("pairedgrenadeID", entry.getKey());
                pairing.put("playerID", entry.getValue());
                pairings.add(pairing);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("grenadepairings", pairings);
            return jsonFrame(payload);
        }
    }

    private String playerDataFrame() {
        synchronized (stateLock) {
            List<Object> data = new ArrayList<>();
            for (Player player : players.values()) {
                if (player.benched()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playername", player.name);
                item.put("playerID", player.id);
                if (player.connected)
                    item.put("playerIP", player.address.getHostAddress());
                item.put("points", player.kills);
                item.put("eliminated", player.deaths);
                item.put("shots", player.shots);
                item.put("hits", player.hits);
                data.add(item);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("playerdata", data);
            return jsonFrame(payload);
        }
    }

    private boolean hasReachedScoreLimitLocked(Player scorer) {
        if (config.scoreLimit <= 0 || config.captureTheFlag || config.infection)
            return false;
        if (config.gameMode == GameMode.FFA)
            return scorer.kills >= config.scoreLimit;
        return teamKillsLocked(teamFor(scorer.id)) >= config.scoreLimit;
    }

    private int teamKillsLocked(int team) {
        int total = roundRoster == null ? 0 : roundRoster.archivedScore(team, false);
        for (Player player : players.values()) {
            if (teamFor(player.id) == team)
                total = addScore(total, player.kills);
        }
        return total;
    }

    private void rememberRoundStatsLocked() {
        if (roundRoster == null) return;
        for (Player player : players.values()) {
            RoundRoster.Member member = roundRoster.seat(player.id);
            if (member == null || !member.identity.equals(player.identity)) continue;
            StateRow row = gossipState[player.id];
            roundRoster.update(player.id, member.generation, player.kills, player.deaths,
                    row == null ? member.captures : row.ctfCaptures,
                    row != null && (row.flags & StatePacket.FLAG_INFECTED) != 0);
            if (roundReview != null && isRoundActiveLocked())
                roundReview.synchronize(player, row == null ? member.captures : row.ctfCaptures);
        }
    }

    private void recordPlayerProfileLocked(Player player) {
        PlayerDirectory.Profile profile = playerDirectory.profiles.get(player.identity);
        if (profile == null) return;
        profile.name = player.name;
        profile.lastTeam = teamFor(player.id);
        if (roundRoster != null && roundRoster.person(player.identity) != null)
            profile.record(roundToken, player.kills, player.deaths, player.shots, player.hits);
    }

    private boolean savePlayerDirectoryLocked() {
        for (Player player : players.values()) recordPlayerProfileLocked(player);
        for (PlayerDirectory.Profile profile : playerDirectory.profiles.values()) {
            profile.hostNamed = hostPlayerNames.containsKey(profile.id);
            if (profile.hostNamed) profile.name = hostPlayerNames.get(profile.id);
            profile.benched = hostPlayerParticipation.get(profile.id);
            profile.team = hostPlayerTeams.getOrDefault(profile.id, 0);
            Settings settings = hostPlayerSettings.get(profile.id);
            profile.settings = settings == null ? null : settings.toJson(0);
            if (profile.settings != null) profile.settings.remove("playerID");
        }
        try { playerDirectory.save();return true; }
        catch (IOException exception) {
            System.err.println("Unable to save player profiles: " + exception.getMessage());
            return false;
        }
    }

    private void addLaserLocked(Player shooter, Player target, String kind) {
        if (shooter == null || target == null)
            return;
        long now = elapsedMillis();
        while (!lasers.isEmpty() && now - lasers.peekFirst().createdAt > LASER_LIFETIME_MS)
            lasers.removeFirst();
        LaserEvent last = lasers.peekLast();
        // A victim reports the verified hit once. Guard against a duplicate
        // report arriving from a reconnect at nearly the same instant.
        if (last != null && last.shooterID == shooter.id && last.targetID == target.id
                && now - last.createdAt < 100 && last.kind.equals(kind))
            return;
        // Store a position snapshot with the event. Rendering against a
        // player's later live marker makes a verified hit line appear to bend
        // toward movement that happened after the shot.
        boolean shooterFresh = shooter.lastGpsAt != Long.MIN_VALUE
                && now - shooter.lastGpsAt <= GPS_STALE_AFTER_MS
                && validCoordinates(shooter.longitude, shooter.latitude);
        boolean targetFresh = target.lastGpsAt != Long.MIN_VALUE
                && now - target.lastGpsAt <= GPS_STALE_AFTER_MS
                && validCoordinates(target.longitude, target.latitude);
        lasers.addLast(new LaserEvent(shooter.id, target.id, kind, now,
                shooterFresh ? shooter.longitude : Double.NaN,
                shooterFresh ? shooter.latitude : Double.NaN,
                targetFresh ? target.longitude : Double.NaN,
                targetFresh ? target.latitude : Double.NaN));
        while (lasers.size() > 100)
            lasers.removeFirst();
    }

    private boolean addGrenadeHitLocked(int grenadeID, Player target) {
        long now = elapsedMillis();
        while (!grenadeBursts.isEmpty()
                && now - grenadeBursts.peekFirst().firstAt > GRENADE_MARKER_LIFETIME_MS)
            grenadeBursts.removeFirst();
        GrenadeBurst burst = null;
        for (java.util.Iterator<GrenadeBurst> it = grenadeBursts.descendingIterator(); it.hasNext();) {
            GrenadeBurst candidate = it.next();
            if (candidate.grenadeID == grenadeID
                    && now - candidate.firstAt <= GRENADE_BURST_WINDOW_MS) {
                burst = candidate;
                break;
            }
        }
        if (burst == null) {
            burst = new GrenadeBurst(grenadeID, now);
            grenadeBursts.addLast(burst);
        }
        // A burst counts each receiving player once. A GPS fix that predates
        // the hit by too long cannot be used to estimate the grenade's center.
        if (burst.hits.containsKey(target.id))
            return false;
        boolean fresh = target.lastGpsAt != Long.MIN_VALUE
                && now - target.lastGpsAt <= GPS_STALE_AFTER_MS
                && validCoordinates(target.longitude, target.latitude);
        burst.hits.put(target.id, new GrenadeHit(target.id,
                fresh ? target.longitude : Double.NaN,
                fresh ? target.latitude : Double.NaN));
        while (grenadeBursts.size() > 30)
            grenadeBursts.removeFirst();
        return true;
    }

    private Player playerForClientLocked(ClientConnection client) {
        Player player = players.get(client.playerID);
        return player != null && player.connection == client && player.connected
                && (!isRoundActiveLocked() || !player.benched()) ? player : null;
    }

    private List<ClientConnection> playingClientsLocked() {
        List<ClientConnection> result = connectedClientsLocked();
        result.removeIf(client -> client.lobbyBenched);
        return result;
    }

    private boolean hasPlayingLeaderLocked() {
        ClientConnection leader = activeClients.get(1);
        return leader != null && !leader.lobbyBenched;
    }

    private List<ClientConnection> connectedClients() {
        synchronized (stateLock) {
            return connectedClientsLocked();
        }
    }

    private List<ClientConnection> connectedClientsLocked() {
        List<ClientConnection> result = new ArrayList<>();
        for (Player player : players.values()) {
            if (player.connected && player.connection != null && !player.connection.closed)
                result.add(player.connection);
        }
        return result;
    }

    private boolean isRoundActiveLocked() {
        return roundState == RoundState.COUNTDOWN || roundState == RoundState.RUNNING;
    }

    private boolean phoneUpdatesIdle() {
        return !stopping && roundState != RoundState.COUNTDOWN && roundState != RoundState.RUNNING;
    }

    private AdbPhoneUpdater.Lease acquireAdbPhoneLease(String serial, String address) throws IOException {
        synchronized (stateLock) {
            if (!phoneUpdatesIdle() || hotspot.isBusy() || adbPhoneLease != null || balancedCheckInDeadline > 0)
                throw new IOException("Finish the round and any Wi-Fi change before updating phones.");
            ClientConnection registered = null;
            for (Player player : players.values()) {
                ClientConnection client = player.connection;
                if (player.connected && client != null && !client.closed && client.initialRosterSent
                        && player.address != null && address.equals(player.address.getHostAddress())
                        && !(client.socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket)
                        && elapsedMillis() - client.lastReceivedAt <= 10_000) {
                    if (registered != null) throw new IOException("More than one player is using this phone address.");
                    registered = client;
                }
            }
            if (registered == null) throw new IOException("Join this laptop's idle lobby before updating the phone.");
            String hostAddress = registered.socket.getLocalAddress().getHostAddress();
            Object reservation = new Object();
            adbPhoneLease = reservation;
            return new AdbPhoneUpdater.Lease() {
                @Override public String hostAddress() { return hostAddress; }
                @Override public boolean stillAllowed() {
                    synchronized (stateLock) {
                        return adbPhoneLease == reservation && phoneUpdatesIdle() && !hotspot.isBusy();
                    }
                }
                @Override public void close() {
                    synchronized (stateLock) {
                        if (adbPhoneLease == reservation) adbPhoneLease = null;
                    }
                }
            };
        }
    }

    private void publishAdbUpdatePolicy() {
        boolean changed;
        synchronized (stateLock) {
            if (stopping) return;
            StringBuilder policy = new StringBuilder();
            for (Player player : players.values()) {
                if (player.connected && player.address != null
                        && adbPhoneUpdates.managesAddress(player.address.getHostAddress()))
                    policy.append(player.id).append(':').append(player.address.getHostAddress()).append(';');
            }
            String current = policy.toString();
            changed = !lastAdbUpdatePolicy.equals(current);
            lastAdbUpdatePolicy = current;
        }
        if (changed) broadcastRoster();
    }

    private int secondsRemainingLocked() {
        if (roundEndAt == 0)
            return 0;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE,
                (roundEndAt - elapsedMillis() + 999) / 1_000));
    }

    private void resetForNewLobbyLocked() {
        savePlayerDirectoryLocked();
        powerupAuthority.resetRound();
        // A new round is not a new host session. Retain live participants,
        // profiles, pairings, the GPS origin and per-connection clock readiness.
        // Offline final-score rows remain in the saved report, not the next lobby.
        players.values().removeIf(player -> !player.connected || player.connection == null
                || player.connection.closed);
        activeClients.entrySet().removeIf(entry -> players.get(entry.getKey()) == null
                || players.get(entry.getKey()).connection != entry.getValue());
        for (Player player : players.values()) {
            PlayerDirectory.Profile profile = playerDirectory.profiles.get(player.identity);
            player.priorKills = Math.min(100_000, Math.max(addScore(player.priorKills, player.kills),
                    profile == null ? 0 : profile.totalKills));
            player.priorDeaths = Math.min(100_000, Math.max(addScore(player.priorDeaths, player.deaths),
                    profile == null ? 0 : profile.totalDeaths));
            player.awaitingRespawn = false;
            player.eliminated = false;
        }
        pendingAssignments.clear();
        grenadeOwners.entrySet().removeIf(entry -> !players.containsKey(entry.getValue()));
        lasers.clear();
        grenadeBursts.clear();
        enemyGpsRevealUntil.clear();
        gpsVisibilityRefreshPlayers.clear();
        gpsVisibilityRefreshPlayers.addAll(activeClients.keySet());
        balancedTeams = Collections.emptyMap();
        balancedCheckedIn.clear();
        balancedCheckInDeadline = 0;
        roundState = RoundState.LOBBY;
        roundToken = null;
        roundRoster = null;
        roundReview = null;
        endVotes = 0;
        endVoteRequested = false;
        roundStartAt = 0;
        roundDuration = 0;
        roundEndAt = 0;
        bossHunterCount = 0;
        authorityTickSequence = 0;
        for (int playerID = 0; playerID <= MAX_PLAYERS; playerID++) {
            gossipState[playerID] = null;
            gossipStateReceivedAt[playerID] = 0;
            lastGossipPacketSequence[playerID] = 0;
            observedCombatSequence[playerID] = 0;
            observedCombatType[playerID] = 0;
            observedCombatTarget[playerID] = 0;
        }
        // Retained sockets do not re-register between games. Reapply the saved
        // identity assignments here instead of relying on a new join to do it.
        invalidateTeamAssignmentsLocked();
    }

    private void disconnect(ClientConnection client) {
        boolean changed = false;
        synchronized (stateLock) {
            Player player = players.get(client.playerID);
            if (player != null && player.connection == client) {
                recordPlayerProfileLocked(player);
                savePlayerDirectoryLocked();
                if (isRoundActiveLocked() && roundReview != null) {
                    roundReview.synchronize(player, gossipState[player.id] == null ? 0
                            : gossipState[player.id].ctfCaptures);
                    roundReview.event(elapsedMillis(), "drop", player, null, 1, "Disconnected / left");
                    roundReview.person(player).complete = false;
                }
                activeClients.remove(player.id);
                endVotes &= ~(1L << (player.id - 1));
                player.connection = null;
                player.connected = false;
                clearEnemyGpsRevealsForPlayerLocked(player.id);
                if (!isRoundActiveLocked() && roundState != RoundState.FINISHED
                        && (client.left || player.relayNode == null)) {
                    players.remove(player.id);
                    grenadeOwners.entrySet().removeIf(entry -> entry.getValue() == player.id);
                    invalidateTeamAssignmentsLocked();
                }
                changed = true;
            }
        }
        if (changed && !stopping) {
            broadcastEndVotes();
            broadcastRoster();
        }
    }

    private long elapsedMillis() {
        return (System.nanoTime() - clockOriginNanos) / 1_000_000L;
    }

    private static boolean validGpsUtcTime(long utcTime) {
        return utcTime >= EARLIEST_GPS_UTC_MS && utcTime <= CLOCK_MAX_VALUE;
    }

    /** Caller holds stateLock. A phone reports its extrapolated current GPS UTC. */
    private void recordGpsClockLocked(long utcTime, long elapsedNow) {
        if (!validGpsUtcTime(utcTime) || elapsedNow < 0)
            return;
        gpsUtcAtReference = utcTime;
        gpsElapsedAtReference = elapsedNow;
    }

    /** Caller holds stateLock. */
    private long gpsTimeAtElapsedLocked(long elapsedTime) {
        long now = elapsedMillis();
        if (!validGpsUtcTime(gpsUtcAtReference) || gpsElapsedAtReference < 0
                || now < gpsElapsedAtReference || now - gpsElapsedAtReference > GPS_CLOCK_MAX_AGE_MS)
            return -1;
        long delta = elapsedTime - gpsElapsedAtReference;
        if ((delta > 0 && gpsUtcAtReference > Long.MAX_VALUE - delta)
                || (delta < 0 && gpsUtcAtReference < Long.MIN_VALUE - delta))
            return -1;
        long utcTime = gpsUtcAtReference + delta;
        return validGpsUtcTime(utcTime) ? utcTime : -1;
    }

    private int teamFor(int playerID) {
        if (!validPlayerID(playerID))
            return -1;
        if (config.boss)
            return playerID == 1 ? 1 : 2;
        if (config.infection) {
            StateRow row = gossipState[playerID];
            return playerID == 1 || row != null
                    && (row.flags & StatePacket.FLAG_INFECTED) != 0 ? 1 : 2;
        }
        if (assignedTeamsEnabledLocked()) {
            Integer assigned = balancedTeams.get(playerID);
            if (assigned != null)
                return assigned;
            Player player = players.get(playerID);
            if (player != null && hostPlayerTeams.containsKey(player.identity))
                return hostPlayerTeams.get(player.identity);
        }
        return baseTeamFor(playerID);
    }

    private int baseTeamFor(int playerID) {
        if (config.gameMode == GameMode.FFA)
            return playerID;
        if (config.gameMode == GameMode.FOUR_TEAMS)
            return ((playerID - 1) / (MAX_PLAYERS / 4)) + 1;
        return playerID <= MAX_PLAYERS / 2 ? 1 : 2;
    }

    private boolean assignedTeamsEnabledLocked() {
        return config.balanced || !hostPlayerTeams.isEmpty() && !fixedModeRules();
    }

    private boolean fixedModeRules() {
        return config.boss || config.captureTheFlag || config.infection;
    }

    private boolean balancedQrEnabledLocked() {
        return config.balancedQr && hostPlayerTeams.isEmpty();
    }

    private void invalidateTeamAssignmentsLocked() {
        if (config.balanced && hostPlayerTeams.isEmpty() && !retainedBalancedTeams.isEmpty()) {
            synchronizeBalancedTeamsLocked();
            return;
        }
        balancedTeams = hostPlayerTeams.isEmpty() || fixedModeRules()
                ? Collections.emptyMap() : computeBalancedTeamsLocked();
        balancedCheckedIn.clear();
        balancedCheckInDeadline = 0;
    }

    private boolean areOpponents(int firstID, int secondID) {
        if (players.get(firstID) != null && players.get(firstID).benched()
                || players.get(secondID) != null && players.get(secondID).benched()) return false;
        return config.gameMode == GameMode.FFA || teamFor(firstID) != teamFor(secondID);
    }

    private Settings defaultSettings(int playerID) {
        Settings settings = config.tournament ? Settings.tournament(config.respawnQrEnabled) : new Settings();
        if (config.boss)
            settings.applyBoss(playerID == 1, Math.max(0, playingClientsLocked().size() - 1));
        return settings;
    }

    private static int addScore(int value, int addition) {
        if (addition <= 0)
            return value;
        return value > MAX_SCOREBOARD_VALUE - addition ? MAX_SCOREBOARD_VALUE : value + addition;
    }

    private static boolean validPlayerID(int playerID) {
        return playerID >= 1 && playerID <= MAX_PLAYERS;
    }

    private static boolean validCoordinates(double longitude, double latitude) {
        return GpsOffset.validCoordinates(longitude, latitude);
    }

    private static boolean validRoundToken(String token) {
        try {
            return token != null && UUID.fromString(token).toString().equalsIgnoreCase(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String validPlayerName(Object value) {
        if (!(value instanceof String))
            return null;
        String name = ((String) value).trim();
        if (name.isEmpty() || name.length() > 20)
            return null;
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index)))
                return null;
        }
        return name;
    }

    private static int intValue(Object value, int fallback) {
        if (!(value instanceof Number))
            return fallback;
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            return fallback;
        return (int) number;
    }

    private static long longValue(Object value, long fallback) {
        if (!(value instanceof Number))
            return fallback;
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > CLOCK_MAX_VALUE)
            return fallback;
        return (long) number;
    }

    private static double doubleValue(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static String jsonFrame(Object value) {
        return TCP_JSON_PREFIX + Json.stringify(value);
    }

    private static String messageFrame(String message) {
        return TCP_MESSAGE_PREFIX + message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null)
            return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private final class ClientConnection implements Runnable {
        private final Socket socket;
        private final int clientID;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final Object outboundLock = new Object();
        private final Deque<OutboundFrame> outbound = new ArrayDeque<>();
        private static final int MAX_OUTBOUND_FRAMES = 128;
        private static final int MAX_OUTBOUND_BYTES = 256 * 1024;
        private static final long FINAL_WRITE_TIMEOUT_MS = 5_000;
        private int outboundBytes, outboundFrames;
        private boolean writerActive, outboundClosing;
        private ScheduledFuture<?> finalWriteTimeout;
        private volatile boolean closed;
        private volatile boolean left;
        private volatile int playerID;
        private volatile boolean clockReady;
        // Guarded by stateLock. A deferred roster also carries current settings.
        private boolean initialRosterSent, rosterPending;
        private volatile boolean lobbyReady = true;
        private volatile boolean lobbyBenched;
        private volatile int clockSamples;
        private volatile int eventControlVersion;
        private volatile String eventLockStatus = "unknown";
        private volatile long lastReceivedAt;

        ClientConnection(Socket socket, int clientID) throws IOException {
            this.socket = socket;
            this.clientID = clientID;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            lastReceivedAt = elapsedMillis();
            allClients.add(this);
            if (stopping) close();
        }

        InetAddress address() {
            return socket.getInetAddress();
        }

        @Override
        public void run() {
            try {
                while (!closed && !stopping)
                    handleFrame(this, input.readUTF());
            } catch (IOException failure) {
                // A phone leaving Wi-Fi or using Leave Game is an ordinary
                // disconnect; the roster update below is the useful result.
                if (!closed && !stopping && socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket)
                    System.err.println("Game relay client " + clientID + " read ended: " + failure.getClass().getSimpleName());
            } finally {
                close();
                disconnect(this);
            }
        }

        boolean send(String message) {
            return enqueue(message, null, false);
        }

        boolean sendClockReply(Map<String, Object> reply) {
            Map<String, Object> snapshot = new LinkedHashMap<>(reply);
            // Reserve the longest possible timestamp; assign the actual value
            // only when this reply reaches the writer, after earlier frames.
            snapshot.put("clocksend", Long.MAX_VALUE);
            return enqueue(jsonFrame(snapshot), snapshot, false);
        }

        boolean sendAndClose(String message) {
            return enqueue(message, null, true);
        }

        /** A slow phone must never hold up scheduler, dashboard, or state-lock work. */
        private boolean enqueue(String message, Map<String, Object> clockReply, boolean closeAfter) {
            if (message == null || closed) return false;
            int bytes = utfFrameBytes(message);
            boolean startWriter = false, failed = bytes < 0;
            synchronized (outboundLock) {
                if (closed || outboundClosing) return false;
                if (failed || outboundFrames >= MAX_OUTBOUND_FRAMES
                        || bytes > MAX_OUTBOUND_BYTES - outboundBytes) failed = true;
                else {
                    outbound.addLast(new OutboundFrame(message, clockReply, bytes, closeAfter));
                    outboundBytes += bytes;outboundFrames++;
                    if (closeAfter) {
                        outboundClosing = true;
                        try {
                            finalWriteTimeout = scheduler.schedule(this::close,
                                    FINAL_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        } catch (RejectedExecutionException stopped) { failed = true; }
                    }
                    if (!writerActive) { writerActive = true;startWriter = true; }
                }
            }
            if (failed) { close();return false; }
            if (startWriter) {
                try { clientExecutor.execute(this::drainOutbound); }
                catch (RejectedExecutionException stopped) { close();return false; }
            }
            return true;
        }

        private void drainOutbound() {
            while (true) {
                OutboundFrame frame;
                synchronized (outboundLock) {
                    if (closed || (frame = outbound.pollFirst()) == null) {
                        writerActive = false;return;
                    }
                }
                try {
                    String message = frame.message;
                    if (frame.clockReply != null) {
                        frame.clockReply.put("clocksend", elapsedMillis());
                        message = jsonFrame(frame.clockReply);
                    }
                    output.writeUTF(message);output.flush();
                } catch (IOException failure) {
                    if (!closed && !stopping && socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket)
                        System.err.println("Game relay client " + clientID + " write failed: " + failure.getClass().getSimpleName());
                    close();return;
                }
                synchronized (outboundLock) {
                    if (!closed) { outboundBytes -= frame.bytes;outboundFrames--; }
                }
                if (frame.closeAfter) { close();return; }
            }
        }

        void close() {
            synchronized (outboundLock) {
                if (closed) return;
                closed = true;
                outbound.clear();outboundBytes = outboundFrames = 0;
                if (finalWriteTimeout != null) finalWriteTimeout.cancel(false);
            }
            allClients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class OutboundFrame {
        final String message;
        final Map<String, Object> clockReply;
        final int bytes;
        final boolean closeAfter;
        OutboundFrame(String message, Map<String, Object> clockReply, int bytes, boolean closeAfter) {
            this.message = message;this.clockReply = clockReply;this.bytes = bytes;this.closeAfter = closeAfter;
        }
    }

    /** DataOutputStream.writeUTF uses modified UTF-8, including a two-byte length. */
    private static int utfFrameBytes(String message) {
        int bytes = 0;
        for (int index = 0; index < message.length(); index++) {
            int value = message.charAt(index);
            bytes += value >= 1 && value <= 127 ? 1 : value <= 2047 ? 2 : 3;
            if (bytes > 65535) return -1;
        }
        return bytes + 2;
    }

    private enum RoundState {
        LOBBY,
        COUNTDOWN,
        RUNNING,
        FINISHED
    }

    private enum GameMode {
        FFA(1),
        TWO_TEAMS(2),
        FOUR_TEAMS(4);

        final int wireValue;

        GameMode(int wireValue) {
            this.wireValue = wireValue;
        }
    }

    private enum GpsMode {
        TEAMMATE(1),
        ALL(2);

        final int wireValue;

        GpsMode(int wireValue) {
            this.wireValue = wireValue;
        }
    }

    private static final class PendingAssignment {
        final int playerID;
        final long expiresAt;

        PendingAssignment(int playerID, long expiresAt) {
            this.playerID = playerID;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Position {
        final int playerID;
        final int team;
        final double longitude;
        final double latitude;
        final long fixAt;

        Position(int playerID, int team, double longitude, double latitude, long fixAt) {
            this.playerID = playerID;
            this.team = team;
            this.longitude = longitude;
            this.latitude = latitude;
            this.fixAt = fixAt;
        }
    }

    private static final class GeoPoint {
        final double longitude;
        final double latitude;
        final long createdAt;

        GeoPoint(double longitude, double latitude, long createdAt) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.createdAt = createdAt;
        }
    }

    private static final class LaserEvent {
        final int shooterID;
        final int targetID;
        final String kind;
        final long createdAt;
        final double shooterLongitude;
        final double shooterLatitude;
        final double targetLongitude;
        final double targetLatitude;

        LaserEvent(int shooterID, int targetID, String kind, long createdAt,
                   double shooterLongitude, double shooterLatitude,
                   double targetLongitude, double targetLatitude) {
            this.shooterID = shooterID;
            this.targetID = targetID;
            this.kind = kind;
            this.createdAt = createdAt;
            this.shooterLongitude = shooterLongitude;
            this.shooterLatitude = shooterLatitude;
            this.targetLongitude = targetLongitude;
            this.targetLatitude = targetLatitude;
        }
    }

    private static final class GrenadeHit {
        final int playerID;
        final double longitude;
        final double latitude;

        GrenadeHit(int playerID, double longitude, double latitude) {
            this.playerID = playerID;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    private static final class GrenadeBurst {
        final int grenadeID;
        final long firstAt;
        final Map<Integer, GrenadeHit> hits = new LinkedHashMap<>();

        GrenadeBurst(int grenadeID, long firstAt) {
            this.grenadeID = grenadeID;
            this.firstAt = firstAt;
        }
    }

    private static final class Player {
        final int id;
        String name;
        String identity = UUID.randomUUID().toString();
        String deviceIdentity;
        InetAddress address;
        ClientConnection connection;
        boolean connected;
        boolean benched() { return connection != null && connection.lobbyBenched; }
        Settings settings;
        double longitude = Double.NaN;
        double latitude = Double.NaN;
        long lastGpsAt = Long.MIN_VALUE;
        long lastGrenadeHitAt;
        int lastGrenadeOwnerID;
        boolean gpsDirty;
        int kills;
        int deaths;
        int priorKills;
        int priorDeaths;
        int shots;
        int hits;
        boolean analyticsCapable;
        boolean awaitingRespawn;
        boolean eliminated;
        UUID relayNode;
        String relayBluetooth = "";
        Map<String, Object> battery;
        long batteryAt = Long.MIN_VALUE;
        final Deque<GeoPoint> trail = new ArrayDeque<>();

        Player(int id, String name, InetAddress address, Settings settings) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.settings = settings;
        }

        int strength() {
            return RoundStatistics.skillIndex(priorKills, priorDeaths);
        }

        void addTrail(double longitude, double latitude, long now) {
            GeoPoint newest = trail.peekLast();
            if (newest == null || newest.longitude != longitude || newest.latitude != latitude)
                trail.addLast(new GeoPoint(longitude, latitude, now));
            while (trail.size() > 30)
                trail.removeFirst();
        }
    }

    private static String powerupName(int kind) {
        String[] names = {"Unknown", "EMP", "Player shields", "Team shields", "Full auto",
                "Full auto / 90 rounds", "Double damage", "Heal team", "Damage opponents",
                "Invulnerability", "Airstrike"};
        return kind > 0 && kind < names.length ? names[kind] : names[0];
    }

    /** Mutable only under stateLock; no GPS or high-rate dashboard traffic is needed. */
    private static final class RoundReview {
        final String token;
        final long id;
        final long startAt;
        final String mode;
        final Map<String, ReviewPlayer> people = new LinkedHashMap<>();
        final List<Map<String, Object>> events = new ArrayList<>();
        final Map<String, Map<String, Object>> lastShotBuckets = new HashMap<>();
        int shotEvents;
        int omittedEvents;
        boolean hasStarted;
        boolean frozen;

        RoundReview(String token, long id, long startAt, String mode) {
            this.token = token; this.id = id; this.startAt = startAt; this.mode = mode;
        }

        void enroll(Player player, int team) {
            ReviewPlayer person = people.get(player.identity);
            if (person == null) {
                person = new ReviewPlayer("p" + people.size(), player.name, player.id, team,
                        player.priorKills, player.priorDeaths);
                person.complete = player.analyticsCapable;
                people.put(player.identity, person);
            }
            person.name = player.name;
            person.playerID = player.id;
            if (!person.seats.contains(player.id)) person.seats.add(player.id);
        }

        ReviewPlayer person(Player player) { return player == null ? null : people.get(player.identity); }

        void started() {
            if (frozen || hasStarted) return;
            hasStarted = true;
            events.add(mapOf("timeMs", 0L, "kind", "start", "actor", null,
                    "target", null, "count", 1, "detail", "Round started"));
        }

        boolean acceptTelemetry(Player reporter, Map<String, Object> json) {
            ReviewPlayer person = person(reporter);
            if (person == null || frozen) return false;
            if (!json.containsKey("telemetryseq")) {
                person.complete = false;
                return true; // Old protocol-31 phones do not supply replay guards.
            }
            long sequence = longValue(json.get("telemetryseq"), -1);
            if (sequence <= person.telemetrySequence) return false;
            if (sequence > person.telemetrySequence + 1)
                person.complete = false;
            person.telemetrySequence = sequence;
            return true;
        }

        void synchronize(Player player, int captures) {
            ReviewPlayer person = person(player);
            if (person == null || frozen) return;
            if (player.kills > person.kills || player.deaths > person.deaths) person.complete = false;
            person.kills = Math.max(person.kills, player.kills);
            person.deaths = Math.max(person.deaths, player.deaths);
            person.captures = Math.max(person.captures, captures);
        }

        void shots(long now, Player player, int count) {
            ReviewPlayer person = person(player);
            if (person == null || frozen) return;
            person.shots = addScore(person.shots, count);
            long time = Math.max(0, now - startAt);
            long bucket = time / 1_000;
            Map<String, Object> previous = lastShotBuckets.get(person.key);
            if (previous != null && longValue(previous.get("timeMs"), 0) / 1_000 == bucket) {
                previous.put("count", addScore(intValue(previous.get("count"), 0), count));
                return;
            }
            if (shotEvents >= MAX_SHOT_TIMELINE_EVENTS || events.size() >= MAX_TIMELINE_EVENTS) {
                omittedEvents++; return;
            }
            Map<String, Object> event = event(now, "shot", player, null, count,
                    "Shots grouped by player / one-second receipt bucket");
            if (event != null) { shotEvents++; lastShotBuckets.put(person.key, event); }
        }

        void hit(long now, Player attacker, Player victim) {
            ReviewPlayer person = person(attacker);
            if (person == null || frozen) return;
            person.hits = addScore(person.hits, 1);
            event(now, "hit", attacker, victim, 1, "Opponent phone confirmed an IR hit");
        }

        void kill(long now, Player attacker, Player victim) {
            ReviewPlayer scorer = person(attacker), target = person(victim);
            if (target == null || frozen) return;
            if (scorer != null) {
                scorer.kills = addScore(scorer.kills, 1);
                scorer.streaks.onKill();
            }
            target.deaths = addScore(target.deaths, 1);
            target.streaks.onDeath();
            if (scorer != null) event(now, "kill", attacker, victim, 1, "Elimination confirmed by victim");
            event(now, "death", victim, attacker, 1, "Eliminated");
        }

        Map<String, Object> event(long now, String kind, Player actor, Player target,
                                  int count, String detail) {
            if (frozen || !hasStarted) return null;
            if (events.size() >= MAX_TIMELINE_EVENTS) { omittedEvents++; return null; }
            ReviewPlayer source = person(actor), destination = person(target);
            Map<String, Object> event = mapOf("timeMs", Math.max(0, now - startAt), "kind", kind,
                    "actor", source == null ? null : source.key,
                    "target", destination == null ? null : destination.key,
                    "count", count, "detail", detail);
            events.add(event);
            return event;
        }

        RoundReport freeze(long now, String reason) {
            if (frozen) return null;
            long duration = hasStarted ? Math.max(0, now - startAt) : 0;
            // End always survives truncation, and a cancelled countdown does
            // not claim any actual play or invent a start event.
            events.add(mapOf("timeMs", duration, "kind", "end", "actor", null,
                    "target", null, "count", 1, "detail", reason));
            List<Object> players = new ArrayList<>();
            Map<String, Map<String, Object>> personal = new HashMap<>();
            boolean complete = people.values().stream().allMatch(player -> player.complete);
            for (Map.Entry<String, ReviewPlayer> entry : people.entrySet()) {
                ReviewPlayer player = entry.getValue();
                Map<String, Object> item = player.summary(token, duration);
                item.put("complete", complete);
                personal.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(item)));
                item.put("key", player.key);
                item.put("name", player.name);
                item.put("team", player.team);
                item.put("seatIDs", new ArrayList<>(player.seats));
                item.put("captures", player.captures);
                item.put("accuracy", RoundStatistics.accuracyPercent(player.shots, player.hits));
                players.add(item);
            }
            Map<String, Object> report = mapOf("roundtoken", token, "roundid", id,
                    "mode", mode, "durationMs", duration, "finishedAtUtc", System.currentTimeMillis(),
                    "reason", reason, "started", hasStarted, "complete", complete,
                    "timing", "Host receipt times relative to actual GO; not exact trigger timestamps",
                    "accuracyDefinition", "Opponent-confirmed gun IR hits / gun shots; grenades excluded",
                    "skillDefinition", "Cumulative kills/deaths balancing index, 0..200; neutral 100. Not Elo; modes are not normalized",
                    "shotGroupingMs", 1_000, "omittedEvents", omittedEvents,
                    "players", players, "events", new ArrayList<>(events));
            frozen = true;
            return new RoundReport(token, Json.stringify(report), report, personal);
        }
    }

    private static final class ReviewPlayer {
        final String key;
        String name;
        int playerID;
        final int team;
        final int priorKills;
        final int priorDeaths;
        final List<Integer> seats = new ArrayList<>();
        int kills, deaths, shots, hits, captures;
        final RoundStatistics.Streaks streaks = new RoundStatistics.Streaks();
        long telemetrySequence;
        boolean complete = true;

        ReviewPlayer(String key, String name, int playerID, int team, int priorKills, int priorDeaths) {
            this.key = key; this.name = name; this.playerID = playerID; this.team = team;
            this.priorKills = priorKills; this.priorDeaths = priorDeaths;
        }

        Map<String, Object> summary(String token, long duration) {
            return mapOf("roundtoken", token, "playerID", playerID, "kills", kills, "deaths", deaths,
                    "shots", shots, "hits", hits, "bestkillstreak", streaks.bestKillStreak(),
                    "bestdeathstreak", streaks.bestDeathStreak(),
                    "skillbefore", RoundStatistics.skillIndex(priorKills, priorDeaths),
                    "skillafter", RoundStatistics.skillIndex(addScore(priorKills, kills), addScore(priorDeaths, deaths)),
                    "durationms", duration, "complete", complete);
        }
    }

    private static final class RoundReport {
        final String token;
        final String json;
        final Map<String, Object> metadata;
        final Map<String, Map<String, Object>> personal;

        RoundReport(String token, String json, Map<String, Object> metadata,
                    Map<String, Map<String, Object>> personal) {
            this.token = token; this.json = json;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.personal = Collections.unmodifiableMap(new HashMap<>(personal));
        }

        Map<String, Object> description() {
            return mapOf("roundtoken", token, "roundid", metadata.get("roundid"),
                    "durationMs", metadata.get("durationMs"), "mode", metadata.get("mode"),
                    "finishedAtUtc", metadata.get("finishedAtUtc"), "reason", metadata.get("reason"));
        }
    }

    /** Protocol-21 fixed-size state gossip and authority tick. */
    private static final class StatePacket {
        static final int FLAG_PRESENT = 1;
        static final int FLAG_LEFT = 1 << 1;
        static final int FLAG_GPS_VALID = 1 << 2;
        static final int FLAG_INFECTED = 1 << 3;

        final int networkVersion;
        final UUID roundToken;
        final long packetSequence;
        final int senderID;
        final int gameMode;
        final StateRow[] rows;
        final GpsOffset origin;

        StatePacket(int networkVersion, UUID roundToken, long packetSequence, int senderID,
                    int gameMode, StateRow[] rows, GpsOffset origin) {
            this.networkVersion = networkVersion;
            this.roundToken = roundToken;
            this.packetSequence = packetSequence;
            this.senderID = senderID;
            this.gameMode = gameMode;
            this.rows = rows;
            this.origin = origin;
        }

        static boolean looksLike(byte[] payload, int offset, int length) {
            return payload != null && offset >= 0 && length >= 4
                    && offset <= payload.length - length
                    && ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                    == STATE_PACKET_MAGIC;
        }

        static StatePacket decode(byte[] payload, int offset, int length) {
            if (payload == null || offset < 0 || length != STATE_PACKET_BYTES
                    || offset > payload.length - length)
                return null;
            ByteBuffer buffer = ByteBuffer.wrap(payload, offset, length).slice()
                    .order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != STATE_PACKET_MAGIC || u8(buffer.get()) != STATE_PACKET_FORMAT)
                return null;
            int networkVersion = u8(buffer.get());
            int senderID = u8(buffer.get());
            int gameMode = u8(buffer.get());
            long packetSequence = buffer.getLong();
            UUID token = new UUID(buffer.getLong(), buffer.getLong());
            double originLatitude = buffer.getDouble();
            double originLongitude = buffer.getDouble();
            GpsOffset origin = GpsOffset.create(originLatitude, originLongitude);
            if (origin == null && !(Double.isNaN(originLatitude) && Double.isNaN(originLongitude)))
                return null;
            if (senderID < 0 || senderID > MAX_PLAYERS || packetSequence <= 0
                    || gameMode != 1 && gameMode != 2 && gameMode != 4)
                return null;
            StateRow[] rows = new StateRow[MAX_PLAYERS + 1];
            for (int slot = 1; slot <= MAX_PLAYERS; slot++) {
                int flags = u8(buffer.get());
                int playerID = u8(buffer.get());
                int gameState = u8(buffer.get());
                int grenadeID = u8(buffer.get());
                int eventType = u8(buffer.get());
                int eventTargetID = u8(buffer.get());
                int carriedFlagTeam = u8(buffer.get());
                int ctfCaptures = u8(buffer.get());
                long ownerSequence = u32(buffer.getInt());
                long eventSequence = u32(buffer.getInt());
                int score = buffer.getInt();
                int deaths = buffer.getInt();
                int health = u16(buffer.getShort());
                int shield = u16(buffer.getShort());
                int shotsRemaining = u16(buffer.getShort());
                int gpsAgeSeconds = u16(buffer.getShort());
                int north = buffer.getShort();
                int east = buffer.getShort();
                double[] position = (flags & FLAG_GPS_VALID) == 0 || origin == null
                        ? null : origin.decode(north, east);
                double latitude = position == null ? Double.NaN : position[0];
                double longitude = position == null ? Double.NaN : position[1];
                int generation = u8(buffer.get());
                int targetGeneration = u8(buffer.get());
                if (playerID != slot)
                    return null;
                if ((flags & FLAG_PRESENT) == 0)
                    continue;
                if ((flags & ~(FLAG_PRESENT | FLAG_LEFT | FLAG_GPS_VALID | FLAG_INFECTED | 16)) != 0
                        || ownerSequence <= 0 || gameState > 2 || grenadeID >= MAX_GRENADE_IDS
                        || eventType > 8 || eventTargetID > MAX_PLAYERS
                        || carriedFlagTeam > 2 || score < 0
                        || score > MAX_SCOREBOARD_VALUE || deaths < 0
                        || deaths > MAX_SCOREBOARD_VALUE)
                    return null;
                if ((flags & FLAG_GPS_VALID) != 0
                        && !validCoordinates(longitude, latitude))
                    return null;
                rows[playerID] = new StateRow(flags, ownerSequence, eventSequence, gameState,
                        grenadeID, eventType, eventTargetID, score, deaths, health, shield,
                        shotsRemaining, gpsAgeSeconds, latitude, longitude,
                        carriedFlagTeam, ctfCaptures).withGeneration(generation, targetGeneration);
            }
            return new StatePacket(networkVersion, token, packetSequence, senderID, gameMode, rows, origin);
        }

        static byte[] encode(int networkVersion, UUID token, long sequence, int gameMode,
                             StateRow[] rows, GpsOffset origin) {
            ByteBuffer buffer = ByteBuffer.allocate(STATE_PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(STATE_PACKET_MAGIC);
            buffer.put((byte) STATE_PACKET_FORMAT);
            buffer.put((byte) networkVersion);
            buffer.put((byte) 0); // authoritative host
            buffer.put((byte) gameMode);
            buffer.putLong(sequence);
            buffer.putLong(token.getMostSignificantBits());
            buffer.putLong(token.getLeastSignificantBits());
            buffer.putDouble(origin == null ? Double.NaN : origin.latitude);
            buffer.putDouble(origin == null ? Double.NaN : origin.longitude);
            for (int playerID = 1; playerID <= MAX_PLAYERS; playerID++) {
                StateRow row = rows == null ? null : rows[playerID];
                int flags = row == null ? 0 : row.flags | FLAG_PRESENT;
                GpsOffset.Position position = row == null || origin == null
                        || (flags & FLAG_GPS_VALID) == 0 ? null : origin.encode(row.latitude, row.longitude);
                if (position == null) flags &= ~FLAG_GPS_VALID;
                buffer.put((byte) flags);
                buffer.put((byte) playerID);
                buffer.put((byte) (row == null ? 0 : row.gameState));
                buffer.put((byte) (row == null ? 0 : row.grenadeID));
                buffer.put((byte) (row == null ? 0 : row.eventType));
                buffer.put((byte) (row == null ? 0 : row.eventTargetID));
                buffer.put((byte) (row == null ? 0 : row.carriedFlagTeam));
                buffer.put((byte) (row == null ? 0 : row.ctfCaptures));
                buffer.putInt((int) (row == null ? 0 : row.ownerSequence));
                buffer.putInt((int) (row == null ? 0 : row.eventSequence));
                buffer.putInt(row == null ? 0 : row.score);
                buffer.putInt(row == null ? 0 : row.deaths);
                buffer.putShort((short) clamp16(row == null ? 0 : row.health));
                buffer.putShort((short) clamp16(row == null ? 0 : row.shield));
                buffer.putShort((short) clamp16(row == null ? 0 : row.shotsRemaining));
                buffer.putShort((short) clamp16(row == null ? 0 : row.gpsAgeSeconds));
                buffer.putShort((short) (position == null ? 0 : position.north));
                buffer.putShort((short) (position == null ? 0 : position.east));
                buffer.put((byte) (row == null ? 0 : row.generation));
                buffer.put((byte) (row == null ? 0 : row.targetGeneration));
            }
            return buffer.array();
        }

        private static int clamp16(int value) { return Math.max(0, Math.min(0xffff, value)); }
        private static int u8(byte value) { return value & 0xff; }
        private static int u16(short value) { return value & 0xffff; }
        private static long u32(int value) { return value & 0xffffffffL; }
    }

    private static final class StateRow {
        final int flags;
        final long ownerSequence;
        final long eventSequence;
        final int gameState;
        final int grenadeID;
        final int eventType;
        final int eventTargetID;
        final int score;
        final int deaths;
        final int health;
        final int shield;
        final int shotsRemaining;
        final int gpsAgeSeconds;
        final double latitude;
        final double longitude;
        final int carriedFlagTeam;
        final int ctfCaptures;
        int generation, targetGeneration;

        StateRow(int flags, long ownerSequence, long eventSequence, int gameState, int grenadeID,
                 int eventType, int eventTargetID, int score, int deaths, int health, int shield,
                 int shotsRemaining, int gpsAgeSeconds, double latitude, double longitude,
                 int carriedFlagTeam, int ctfCaptures) {
            this.flags = flags;
            this.ownerSequence = ownerSequence;
            this.eventSequence = eventSequence;
            this.gameState = gameState;
            this.grenadeID = grenadeID;
            this.eventType = eventType;
            this.eventTargetID = eventTargetID;
            this.score = score;
            this.deaths = deaths;
            this.health = health;
            this.shield = shield;
            this.shotsRemaining = shotsRemaining;
            this.gpsAgeSeconds = gpsAgeSeconds;
            this.latitude = latitude;
            this.longitude = longitude;
            this.carriedFlagTeam = carriedFlagTeam;
            this.ctfCaptures = ctfCaptures;
        }

        StateRow withFlags(int replacementFlags) {
            return new StateRow(replacementFlags, ownerSequence, eventSequence, gameState,
                    grenadeID, eventType, eventTargetID, score, deaths, health, shield,
                    shotsRemaining, gpsAgeSeconds, latitude, longitude,
                    carriedFlagTeam, ctfCaptures).withGeneration(generation, targetGeneration);
        }

        StateRow withoutGps() {
            return new StateRow(flags & ~StatePacket.FLAG_GPS_VALID, ownerSequence, eventSequence,
                    gameState, grenadeID, eventType, eventTargetID, score, deaths, health, shield,
                    shotsRemaining, gpsAgeSeconds, Double.NaN, Double.NaN,
                    carriedFlagTeam, ctfCaptures).withGeneration(generation, targetGeneration);
        }

        StateRow withGpsAge(int ageSeconds) {
            return new StateRow(flags, ownerSequence, eventSequence, gameState,
                    grenadeID, eventType, eventTargetID, score, deaths, health, shield,
                    shotsRemaining, ageSeconds, latitude, longitude,
                    carriedFlagTeam, ctfCaptures).withGeneration(generation, targetGeneration);
        }

        StateRow withGeneration(int generation, int targetGeneration) {
            this.generation = generation; this.targetGeneration = targetGeneration;
            return this;
        }
    }

    /** Protocol-21 compact event; phones ACK only the addressed target. */
    private static final class CombatPacket {
        static final int KIND_EVENT = 1;
        static final int KIND_ACK = 2;

        final int networkVersion;
        final int kind;
        final int eventType;
        final int senderID;
        final int targetID;
        final long eventSequence;
        final UUID roundToken;
        int generation, targetGeneration;

        CombatPacket(int networkVersion, int kind, int eventType, int senderID,
                     int targetID, long eventSequence, UUID roundToken) {
            this.networkVersion = networkVersion;
            this.kind = kind;
            this.eventType = eventType;
            this.senderID = senderID;
            this.targetID = targetID;
            this.eventSequence = eventSequence;
            this.roundToken = roundToken;
        }

        static boolean looksLike(byte[] payload, int offset, int length) {
            return payload != null && offset >= 0 && length >= 4
                    && offset <= payload.length - length
                    && ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                    == COMBAT_PACKET_MAGIC;
        }

        static CombatPacket decode(byte[] payload, int offset, int length) {
            if (payload == null || offset < 0 || length != COMBAT_PACKET_BYTES
                    || offset > payload.length - length)
                return null;
            ByteBuffer buffer = ByteBuffer.wrap(payload, offset, length).slice()
                    .order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != COMBAT_PACKET_MAGIC
                    || u8(buffer.get()) != COMBAT_PACKET_FORMAT)
                return null;
            int networkVersion = u8(buffer.get());
            int kind = u8(buffer.get());
            int eventType = u8(buffer.get());
            int senderID = u8(buffer.get());
            int targetID = u8(buffer.get());
            int generation = u8(buffer.get());
            int targetGeneration = u8(buffer.get());
            long eventSequence = u32(buffer.getInt());
            UUID token = new UUID(buffer.getLong(), buffer.getLong());
            if ((kind != KIND_EVENT && kind != KIND_ACK) || eventType < 1 || eventType > 5
                    || !validPlayerID(senderID) || eventSequence <= 0)
                return null;
            if (kind == KIND_EVENT && eventType == 1) {
                if (targetID != 0)
                    return null;
            } else if (!validPlayerID(targetID)) {
                return null;
            }
            CombatPacket decoded = new CombatPacket(networkVersion, kind, eventType, senderID, targetID,
                    eventSequence, token);
            decoded.generation = generation; decoded.targetGeneration = targetGeneration;
            return decoded;
        }

        private static int u8(byte value) { return value & 0xff; }
        private static long u32(int value) { return value & 0xffffffffL; }
    }

    private static final class Settings {
        int health = 5;
        int reloadShots = 30;
        long reloadTime = 1_500;
        boolean reloadOnEmpty;
        long spawnTime = 10;
        int damage = -1;
        boolean overrideLives;
        int lives;
        boolean allowSingle = true;
        boolean allowBurst = true;
        boolean allowAuto = true;
        int firingMode;

        static Settings tournament(boolean respawnQrEnabled) {
            Settings settings = new Settings();
            // Keep the published default consistent when one player's edited
            // profile enables custom host rules for the whole lobby.
            settings.spawnTime = respawnQrEnabled ? 180 : 10;
            settings.allowBurst = false;
            settings.allowAuto = false;
            return settings;
        }

        void applyBoss(boolean boss, int hunterCount) {
            health = boss ? 5 + Math.max(0, hunterCount) : 2;
            reloadShots = boss ? 120 : 30;
            reloadTime = 1_500;
            reloadOnEmpty = false;
            spawnTime = 10;
            damage = -1;
            allowSingle = true;
            allowBurst = boss;
            allowAuto = boss;
            // Phones retain their indoor/outdoor field choice. Outdoors, both
            // boss guns use the wide cone; hunters retain their no-cone profile.
            if (boss && firingMode != 2)
                firingMode = 1;
            else if (!boss)
                firingMode = 0;
        }

        static Settings fromClientJson(Map<String, Object> json) {
            Settings settings = new Settings();
            settings.health = intValue(json.get("health"), -1);
            settings.reloadShots = intValue(json.get("reloadshots"), -1);
            settings.reloadTime = longValue(json.get("reloadtime"), -1);
            settings.reloadOnEmpty = Boolean.TRUE.equals(json.get("reloadonempty"));
            settings.spawnTime = longValue(json.get("spawntime"), -1);
            settings.damage = intValue(json.get("damage"), Integer.MIN_VALUE);
            settings.overrideLives = json.containsKey("liveslimit");
            settings.lives = settings.overrideLives ? intValue(json.get("liveslimit"), -1) : 0;
            settings.allowSingle = Boolean.TRUE.equals(json.get("shotmodesingle"));
            settings.allowBurst = Boolean.TRUE.equals(json.get("shotmodeburst3"));
            settings.allowAuto = Boolean.TRUE.equals(json.get("shotmodeauto"));
            settings.firingMode = intValue(json.get("firingmode"), -1);
            if (settings.health < 1 || settings.health > 1_000
                    || settings.reloadShots < 1 || settings.reloadShots > 255
                    || settings.reloadTime < 0 || settings.reloadTime > 10_000
                    || settings.spawnTime < 1 || settings.spawnTime > 1_000
                    || settings.damage < -1_000 || settings.damage > -1
                    || settings.lives < 0 || settings.lives > 1_000
                    || (!settings.allowSingle && !settings.allowBurst && !settings.allowAuto)
                    || settings.firingMode < 0 || settings.firingMode > 2)
                return null;
            return settings;
        }

        static Settings fromHostJson(Map<String, Object> json) {
            Set<String> fields = Set.of("health", "reloadshots", "reloadtime", "reloadonempty", "spawntime",
                    "damage", "liveslimit", "shotmodesingle", "shotmodeburst3", "shotmodeauto", "firingmode");
            if (!fields.containsAll(json.keySet())) return null;
            for (String field : List.of("reloadonempty", "shotmodesingle", "shotmodeburst3", "shotmodeauto"))
                if (!(json.get(field) instanceof Boolean)) return null;
            return fromClientJson(json);
        }

        Map<String, Object> toJson(int playerID) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("playerID", playerID);
            json.put("health", health);
            json.put("reloadshots", reloadShots);
            json.put("reloadtime", reloadTime);
            json.put("reloadonempty", reloadOnEmpty);
            json.put("spawntime", spawnTime);
            json.put("damage", damage);
            if (overrideLives)
                json.put("liveslimit", lives);
            json.put("shotmodesingle", allowSingle);
            json.put("shotmodeburst3", allowBurst);
            json.put("shotmodeauto", allowAuto);
            json.put("firingmode", firingMode);
            return json;
        }
    }

    private static final class StartResult {
        final boolean ok;
        final String message;
        final boolean clockWaiting;

        private StartResult(boolean ok, String message, boolean clockWaiting) {
            this.ok = ok;
            this.message = message;
            this.clockWaiting = clockWaiting;
        }

        static StartResult ok() {
            return new StartResult(true, "The synchronized countdown has started.", false);
        }

        static StartResult waitingForQr() {
            return new StartResult(true, "Teams assigned. Waiting up to 90 seconds for team QR check-ins.", false);
        }

        static StartResult error(String message, boolean clockWaiting) {
            return new StartResult(false, message, clockWaiting);
        }
    }

    private void handleDashboardIndex(HttpExchange exchange) throws IOException {
        sendHtml(exchange, INDEX_PAGE);
    }

    private void handleStateApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use GET."));
            return;
        }
        sendJson(exchange, 200, dashboardState());
    }

    private boolean localWifiRequest(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            URI address = URI.create("http://" + host);
            if (!Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(address.getHost())
                    || address.getPort() != exchange.getLocalAddress().getPort()
                    || address.getRawUserInfo() != null || !address.getRawPath().isEmpty()) return false;
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equalsIgnoreCase("http://" + host)) return false;
            String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            return site == null || "same-origin".equals(site) || "none".equals(site);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    private void handleWifiPage(HttpExchange exchange) throws IOException {
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Open Wi-Fi controls on this laptop."));
            return;
        }
        sendFile(exchange, config.webDirectory.resolve("wifi.html"), "text/html; charset=utf-8", "no-store");
    }

    private void handlePhoneUpdatesPage(HttpExchange exchange) throws IOException {
        if (!"/phone-updates".equals(exchange.getRequestURI().getRawPath())
                || exchange.getRequestURI().getRawQuery() != null) {
            sendJson(exchange, 404, mapOf("ok", false, "message", "Not found."));
            return;
        }
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Open phone update controls on this laptop."));
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("ok", false, "message", "Use GET."));
            return;
        }
        sendFile(exchange, config.webDirectory.resolve("phone-updates.html"), "text/html; charset=utf-8", "no-store");
    }

    private void handlePhoneUpdatesApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!"/api/phone-updates".equals(exchange.getRequestURI().getRawPath())
                || exchange.getRequestURI().getRawQuery() != null) {
            sendJson(exchange, 404, mapOf("ok", false, "message", "Not found."));
            return;
        }
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Phone updates are controlled only on this laptop."));
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, adbPhoneUpdates.snapshot());
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("ok", false, "message", "Use GET or POST."));
            return;
        }
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                || !"1".equals(exchange.getRequestHeaders().getFirst("X-SimpleCoil-Control"))) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Use this laptop's phone update controls."));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            sendJson(exchange, 413, mapOf("ok", false, "message", "Phone update request is too large."));
            return;
        }
        Map<?, ?> request;
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException();
            request = (Map<?, ?>) parsed;
        } catch (IllegalArgumentException invalid) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Provide a valid phone update request."));
            return;
        }
        Object action = request.get("action"), serial = request.get("serial");
        boolean accepted;
        if ("set-enabled".equals(action) && request.size() == 2 && request.get("enabled") instanceof Boolean)
            accepted = adbPhoneUpdates.setEnabled((Boolean) request.get("enabled"));
        else if (request.size() == 2 && serial instanceof String && "enroll-usb".equals(action))
            accepted = adbPhoneUpdates.enrollUsb((String) serial);
        else if (request.size() == 2 && serial instanceof String && "retry".equals(action))
            accepted = adbPhoneUpdates.retry((String) serial);
        else if (request.size() == 2 && serial instanceof String && "remove".equals(action))
            accepted = adbPhoneUpdates.remove((String) serial);
        else {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Unknown phone update action or invalid fields."));
            return;
        }
        if (accepted) broadcastRoster();
        sendJson(exchange, accepted ? 200 : 409, mapOf("ok", accepted,
                "message", accepted ? "Phone update request accepted."
                        : "Phone update request deferred. Check the phone's status and try again.",
                "status", adbPhoneUpdates.snapshot()));
    }

    private void handlePlayersPage(HttpExchange exchange) throws IOException {
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Open player controls on this laptop."));
            return;
        }
        sendFile(exchange, config.webDirectory.resolve("players.html"), "text/html; charset=utf-8", "no-store");
    }

    private Map<String, Object> playerControlsState() {
        synchronized (stateLock) {
            Map<String, Object> state = dashboardState();
            state.put("eventMode", eventMode);
            state.put("kioskMode", eventPinning);
            state.put("organizerPin", eventSettings.pin());
            state.put("customRules", hostCustomRules);
            state.put("manualTeams", !hostPlayerTeams.isEmpty());
            boolean specialMode = config.boss || config.captureTheFlag || config.infection;
            state.put("canEditTeams", !specialMode && config.gameMode == GameMode.TWO_TEAMS);
            state.put("canEditSettings", !specialMode);
            state.put("rulesNote", specialMode ? "This game mode fixes teams and weapon rules. Names and participation are editable."
                    : "Saving gameplay settings enables custom host rules. Phones cannot change those settings.");
            List<Object> saved = new ArrayList<>();
            for (PlayerDirectory.Profile profile : playerDirectory.profiles.values()) {
                Map<String, Object> details = profile.toJson();
                details.put("eventLocked", !releasedPhones.contains(profile.id));
                if (details.get("settings") == null) details.put("settings", defaultSettings(1).toJson(0));
                saved.add(details);
            }
            state.put("savedPlayers", saved);
            for (Object item : (List<?>) state.get("players")) {
                Map<String, Object> details = castObject(item);
                Player player = players.get(intValue(details.get("id"), 0));
                details.put("identity", player.identity);
                details.put("hostNamed", hostPlayerNames.containsKey(player.identity));
                details.put("settings", player.settings.toJson(player.id));
                details.put("customSettings", hostPlayerSettings.containsKey(player.identity));
                details.put("eventLocked", !releasedPhones.contains(player.identity));
                details.put("eventCapable", player.connection != null && player.connection.eventControlVersion >= 1);
                details.put("lockStatus", player.connection == null ? "offline" : player.connection.eventLockStatus);
                details.put("viaRelay", player.connection != null && player.connection.socket instanceof com.simplecoil.protocol.RelayMesh.VirtualSocket);
                details.put("battery", player.battery);
                PlayerDirectory.Profile profile = playerDirectory.profiles.get(player.identity);
                details.put("gunBatteryRounds", profile == null ? List.of() : profile.gunBatteryRounds.snapshot());
                details.put("batteryAgeMs", player.batteryAt == Long.MIN_VALUE ? null : Math.max(0, elapsedMillis() - player.batteryAt));
            }
            return state;
        }
    }

    private void handlePlayersApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Player editing is available only on this laptop."));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, playerControlsState());
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("ok", false, "message", "Use GET or POST."));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                || !"1".equals(exchange.getRequestHeaders().getFirst("X-SimpleCoil-Control"))) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Use the laptop's player controls."));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            sendJson(exchange, 413, mapOf("ok", false, "message", "Player settings are too large."));
            return;
        }
        Map<String, Object> request;
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException();
            request = castObject(parsed);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Invalid player settings."));
            return;
        }
        if ("transfer".equals(request.get("action"))) {
            handlePlayerTransfer(exchange, request);
            return;
        }
        if ("battery".equals(request.get("action"))) {
            ClientConnection target;
            synchronized (stateLock) {
                Player player = players.get(intValue(request.get("id"), 0));
                if (player == null || !player.identity.equals(request.get("identity")) || !player.connected
                        || player.connection == null || player.connection.eventControlVersion < 2) {
                    sendJson(exchange, 409, mapOf("ok", false, "message", "Connect a phone with the updated app to check its batteries."));
                    return;
                }
                target = player.connection;
            }
            target.send(jsonFrame(mapOf(EventMode.BATTERY_REQUEST, true)));
            sendJson(exchange, 200, mapOf("ok", true, "message", "Battery refresh requested. Readings update when the phone responds.", "status", playerControlsState()));
            return;
        }
        int playerID = intValue(request.get("id"), 0);
        String name = validPlayerName(request.get("name"));
        if (request.containsKey("id") && !validPlayerID(playerID) || !(request.get("identity") instanceof String)
                || !RoundRoster.validIdentity((String) request.get("identity"))) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Choose a player from the current roster."));
            return;
        }
        if (request.containsKey("name") && name == null) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Names must contain 1–20 characters and no control characters."));
            return;
        }
        if (request.containsKey("benched") && !(request.get("benched") instanceof Boolean)
                || request.containsKey("eventLocked") && !(request.get("eventLocked") instanceof Boolean)
                || request.containsKey("resetSettings") && !Boolean.TRUE.equals(request.get("resetSettings"))
                || request.containsKey("settings") && request.containsKey("resetSettings")
                || !Set.of("id", "identity", "name", "benched", "team", "settings", "resetSettings", "eventLocked").containsAll(request.keySet())
                || request.keySet().stream().allMatch(key -> "id".equals(key) || "identity".equals(key))) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Invalid player edit."));
            return;
        }
        int team = intValue(request.get("team"), 0);
        Settings settings = request.get("settings") instanceof Map
                ? Settings.fromHostJson(castObject(request.get("settings"))) : null;
        if (request.containsKey("team") && (team < 1 || team > 2)
                || request.containsKey("settings") && settings == null) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Invalid team or gameplay settings. Check the field limits and allow at least one firing mode."));
            return;
        }
        synchronized (stateLock) {
            updateRoundStateLocked();
            String identity = (String) request.get("identity");
            PlayerDirectory.Profile profile = playerDirectory.profiles.get(identity);
            Player player = request.containsKey("id") ? players.get(playerID) : players.values().stream()
                    .filter(member -> member.identity.equals(identity)).findFirst().orElse(null);
            if (profile == null || request.containsKey("id") && (player == null || !player.identity.equals(identity))) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "This player has left or changed ID. Select them again."));
                return;
            }
            boolean lobbyEdit = request.containsKey("benched") || request.containsKey("team")
                    || request.containsKey("settings") || request.containsKey("resetSettings");
            if (lobbyEdit && isRoundActiveLocked()) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "Teams, participation, and gameplay settings can only change in the lobby."));
                return;
            }
            if ((request.containsKey("team") || request.containsKey("settings") || request.containsKey("resetSettings"))
                    && (config.boss || config.captureTheFlag || config.infection)) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "This game mode fixes teams and weapon rules."));
                return;
            }
            // Validate the entire request before committing any field.
            if (request.containsKey("name")) {
                hostPlayerNames.put(identity, name);
                profile.name = name;
                if (player != null) player.name = name;
            }
            if (request.containsKey("benched")) {
                boolean benched = Boolean.TRUE.equals(request.get("benched"));
                hostPlayerParticipation.put(identity, benched);
                if (player != null && player.connection != null) player.connection.lobbyBenched = benched;
            }
            if (request.containsKey("team")) {
                Map<String, Integer> teams = new HashMap<>(hostPlayerTeams);
                if (teams.isEmpty()) for (Player member : players.values())
                    teams.put(member.identity, teamFor(member.id));
                teams.put(identity, team);
                hostPlayerTeams = Collections.unmodifiableMap(teams);
            }
            if (settings != null) {
                hostPlayerSettings.put(identity, settings);
                if (player != null) player.settings = settings;
                hostCustomRules = true;
            } else if (request.containsKey("resetSettings")) {
                hostPlayerSettings.remove(identity);
                if (player != null) player.settings = defaultSettings(player.id);
                hostCustomRules = !hostPlayerSettings.isEmpty();
            }
            if (request.containsKey("eventLocked")) {
                if (Boolean.TRUE.equals(request.get("eventLocked"))) releasedPhones.remove(identity);
                else releasedPhones.add(identity);
                playerEventRevisions.put(identity, UUID.randomUUID().toString());
            }
            if (request.containsKey("team") || request.containsKey("benched")) invalidateTeamAssignmentsLocked();
            if (!savePlayerDirectoryLocked()) {
                sendJson(exchange, 503, mapOf("ok", false, "message", "Player changed in memory, but the laptop could not save the profile. Check available disk space and retry."));
                return;
            }
        }
        broadcastRoster();
        sendJson(exchange, 200, mapOf("ok", true, "message", "Player updated.", "status", playerControlsState()));
    }

    private void handlePlayerTransfer(HttpExchange exchange, Map<String, Object> request) throws IOException {
        int targetID = intValue(request.get("targetId"), 0);
        if (!(request.get("identity") instanceof String) || !(request.get("targetIdentity") instanceof String)
                || !validPlayerID(targetID) || !Set.of("action", "identity", "targetId", "targetIdentity").containsAll(request.keySet())) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Choose a saved player and a replacement phone."));
            return;
        }
        ClientConnection previous = null;
        synchronized (stateLock) {
            updateRoundStateLocked();
            Player target = players.get(targetID);
            PlayerDirectory.Profile profile = playerDirectory.profiles.get(request.get("identity"));
            if (isRoundActiveLocked() || profile == null || target == null || !target.connected
                    || !target.identity.equals(request.get("targetIdentity")) || target.identity.equals(profile.id)) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "Transfers require a different connected phone in the lobby. Refresh the roster and try again."));
                return;
            }
            Player source = players.values().stream().filter(player -> player.identity.equals(profile.id)).findFirst().orElse(null);
            int desiredTeam = source != null ? teamFor(source.id) : profile.team > 0 ? profile.team
                    : profile.lastTeam > 0 ? profile.lastTeam : teamFor(target.id);
            if ((config.boss || config.captureTheFlag || config.infection) && desiredTeam != teamFor(target.id)) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "In this game mode, connect the replacement phone using the same team or Boss role first."));
                return;
            }
            if (roundState == RoundState.FINISHED) {
                // Save the completed identities before rebinding a retained
                // socket. Its former player's scores cannot follow the new person.
                resetForNewLobbyLocked();
                target.kills = target.deaths = target.shots = target.hits = 0;
            }
            if (source != null) {
                previous = source.connection;
                players.remove(source.id);activeClients.remove(source.id);
                grenadeOwners.entrySet().removeIf(entry -> entry.getValue() == source.id);
            }
            playerDirectory.bind(profile.id, target.deviceIdentity);
            releasedPhones.remove(profile.id);
            target.identity = profile.id;target.name = profile.name;
            target.settings = hostPlayerSettings.getOrDefault(profile.id, defaultSettings(target.id));
            target.priorKills = Math.min(100_000, profile.totalKills);
            target.priorDeaths = Math.min(100_000, profile.totalDeaths);
            target.connection.lobbyBenched = Boolean.TRUE.equals(profile.benched);
            if (!config.boss && !config.captureTheFlag && !config.infection) {
                Map<String, Integer> teams = new HashMap<>(hostPlayerTeams);
                if (teams.isEmpty()) for (Player member : players.values()) teams.put(member.identity, baseTeamFor(member.id));
                teams.put(profile.id, desiredTeam);hostPlayerTeams = Collections.unmodifiableMap(teams);
            }
            invalidateTeamAssignmentsLocked();
            if (!savePlayerDirectoryLocked()) {
                sendJson(exchange, 503, mapOf("ok", false, "message", "The phone changed, but its profile could not be saved. Check disk space and retry."));
                return;
            }
        }
        if (previous != null) {
            previous.sendAndClose(jsonFrame(mapOf(EventMode.TRANSFERRED_OUT, true)));
        }
        broadcastRoster();
        sendJson(exchange, 200, mapOf("ok", true, "message", "Player moved to the replacement phone. The original phone is unassigned.",
                "status", playerControlsState()));
    }

    private void handleEventApi(HttpExchange exchange) throws IOException {
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Event controls are available only on this laptop."));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("ok", false, "message", "Use POST."));
            return;
        }
        if (!"application/json".equalsIgnoreCase(String.valueOf(exchange.getRequestHeaders()
                .getFirst("Content-Type")).split(";", 2)[0].trim())
                || !"1".equals(exchange.getRequestHeaders().getFirst("X-SimpleCoil-Control"))) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Use the laptop's event controls."));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            sendJson(exchange, 413, mapOf("ok", false, "message", "Event settings are too large."));
            return;
        }
        Map<String, Object> request;
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException();
            request = castObject(parsed);
            if (!(request.get("enabled") instanceof Boolean) || !(request.get("kiosk") instanceof Boolean)
                    || !(request.get("pin") instanceof String)
                    || request.containsKey("title") && !EventMode.validTitle(request.get("title") instanceof String ? (String) request.get("title") : null)
                    || !Set.of("enabled", "kiosk", "pin", "title").containsAll(request.keySet()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Choose Event Mode, Kiosk Mode, and an organizer PIN with 2-6 digits."));
            return;
        }
        try {
            synchronized (stateLock) {
                if (!eventSettings.acceptsPin((String) request.get("pin")))
                    throw new IllegalArgumentException("Use an organizer PIN with 2-6 digits.");
                eventSettings.save((String) request.get("pin"), request.containsKey("title") ? (String) request.get("title") : eventSettings.title(),
                        Boolean.TRUE.equals(request.get("enabled")), Boolean.TRUE.equals(request.get("kiosk")));
                eventMode = Boolean.TRUE.equals(request.get("enabled"));
                eventPinning = Boolean.TRUE.equals(request.get("kiosk"));
                releasedPhones.clear();
                eventRevision = UUID.randomUUID().toString();
                playerEventRevisions.clear();
            }
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Use an organizer PIN with 2-6 digits, or keep the existing PIN unchanged."));
            return;
        } catch (IOException exception) {
            sendJson(exchange, 503, mapOf("ok", false, "message", "Unable to save the organizer PIN."));
            return;
        }
        broadcastRoster();
        sendJson(exchange, 200, mapOf("ok", true, "message", eventMode ? "Event controls applied." : "Event Mode released on all connected phones.",
                "status", playerControlsState()));
    }

    private void handleWifiApi(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!localWifiRequest(exchange)) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Wi-Fi controls are available only on this laptop."));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 200, hotspot.status());
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("ok", false, "message", "Use GET or POST."));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                || !"1".equals(exchange.getRequestHeaders().getFirst("X-SimpleCoil-Control"))) {
            sendJson(exchange, 403, mapOf("ok", false, "message", "Use the laptop's Wi-Fi controls."));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            sendJson(exchange, 413, mapOf("ok", false, "message", "Wi-Fi settings are too large."));
            return;
        }
        Map<?, ?> request;
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException();
            request = (Map<?, ?>) parsed;
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Provide valid Wi-Fi settings."));
            return;
        }
        synchronized (stateLock) {
            if (adbPhoneLease != null) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "Wait for the phone update to finish before changing Wi-Fi."));
                return;
            }
            if (isRoundActiveLocked() && !"apply".equals(request.get("action"))) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "Finish the round before changing Wi-Fi."));
                return;
            }
            if (!hotspot.beginChange()) {
                sendJson(exchange, 409, mapOf("ok", false, "message", "A Wi-Fi change is already in progress."));
                return;
            }
        }
        int code = 200;
        String action = String.valueOf(request.get("action"));
        String message;
        try {
            hotspot.control(action, request);
            message = switch (action) {
                case "start" -> "Hotspot started. Connect the phones to this network.";
                case "apply" -> "Hotspot restarted with the new settings. Phones can rejoin now.";
                case "stop" -> "Hotspot stopped.";
                default -> "Wi-Fi settings saved.";
            };
        } catch (IllegalArgumentException exception) {
            code = 400;
            message = exception.getMessage();
        } catch (IOException exception) {
            code = 503;
            message = exception.getMessage();
        } finally {
            hotspot.endChange();
        }
        sendJson(exchange, code, mapOf("ok", code == 200, "message", message, "status", hotspot.status()));
    }

    private void handleMapConfigApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use GET."));
            return;
        }
        int minimumZoom = Integer.MAX_VALUE;
        int maximumZoom = -1;
        if (Files.isDirectory(config.tileDirectory)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(config.tileDirectory)) {
                for (Path entry : entries) {
                    if (!Files.isDirectory(entry))
                        continue;
                    try {
                        int zoom = Integer.parseInt(entry.getFileName().toString());
                        if (zoom >= 0 && zoom <= 22) {
                            minimumZoom = Math.min(minimumZoom, zoom);
                            maximumZoom = Math.max(maximumZoom, zoom);
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore metadata and unrelated directories in the tile root.
                    }
                }
            }
        }
        boolean available = maximumZoom >= 0;
        Map<String, Object> mapConfig = mapOf("available", available,
                "minZoom", available ? minimumZoom : 0,
                "maxZoom", available ? maximumZoom : 19);
        Path metadataFile = config.tileDirectory.resolve("map.json");
        if (available && Files.isRegularFile(metadataFile)) {
            try {
                if (Files.size(metadataFile) <= 16_384
                        && Json.parse(Files.readString(metadataFile)) instanceof Map<?, ?> metadata) {
                    if (validMapPoint(metadata.get("center"))) {
                        mapConfig.put("center", metadata.get("center"));
                        mapConfig.put("zoom", Math.max(minimumZoom, Math.min(maximumZoom,
                                intValue(metadata.get("zoom"), Math.min(18, maximumZoom)))));
                    }
                    if (metadata.get("bounds") instanceof List<?> bounds && bounds.size() == 2
                            && validMapPoint(bounds.get(0)) && validMapPoint(bounds.get(1))) {
                        List<?> southwest = (List<?>) bounds.get(0);
                        List<?> northeast = (List<?>) bounds.get(1);
                        if (doubleValue(southwest.get(0), 0) < doubleValue(northeast.get(0), 0)
                                && doubleValue(southwest.get(1), 0) < doubleValue(northeast.get(1), 0))
                            mapConfig.put("bounds", bounds);
                    }
                    if (metadata.get("attribution") instanceof String attribution)
                        mapConfig.put("attribution", attribution);
                    if (metadata.get("version") instanceof String version)
                        mapConfig.put("version", version);
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // Optional metadata must not prevent existing XYZ tiles from loading.
            }
        }
        sendJson(exchange, 200, mapConfig);
    }

    private static boolean validMapPoint(Object value) {
        return value instanceof List<?> point && point.size() == 2
                && validCoordinates(doubleValue(point.get(1), Double.NaN),
                        doubleValue(point.get(0), Double.NaN));
    }

    private void handleLeafletAsset(HttpExchange exchange) throws IOException {
        String prefix = "/assets/leaflet/";
        String path = exchange.getRequestURI().getPath();
        if (path == null || !path.startsWith(prefix)) {
            sendStatus(exchange, 404);
            return;
        }
        String relative = path.substring(prefix.length());
        Set<String> allowed = Set.of("leaflet.js", "leaflet.css", "images/layers.png",
                "images/layers-2x.png", "images/marker-icon.png", "images/marker-icon-2x.png",
                "images/marker-shadow.png");
        if (!allowed.contains(relative)) {
            sendStatus(exchange, 404);
            return;
        }
        String contentType = relative.endsWith(".css") ? "text/css; charset=utf-8"
                : relative.endsWith(".js") ? "text/javascript; charset=utf-8" : "image/png";
        sendFile(exchange, config.webDirectory.resolve("leaflet").resolve(relative), contentType,
                "public, max-age=86400");
    }

    private void handleMapTile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path == null ? new String[0] : path.split("/");
        if (parts.length != 5 || !"tiles".equals(parts[1]) || !parts[4].endsWith(".png")) {
            sendStatus(exchange, 404);
            return;
        }
        int zoom;
        long x;
        long y;
        try {
            zoom = Integer.parseInt(parts[2]);
            x = Long.parseLong(parts[3]);
            y = Long.parseLong(parts[4].substring(0, parts[4].length() - 4));
        } catch (NumberFormatException e) {
            sendStatus(exchange, 404);
            return;
        }
        if (zoom < 0 || zoom > 22 || x < 0 || y < 0 || x >= (1L << zoom) || y >= (1L << zoom)) {
            sendStatus(exchange, 404);
            return;
        }
        Path tile = config.tileDirectory.resolve(String.valueOf(zoom)).resolve(String.valueOf(x))
                .resolve(y + ".png");
        if (!Files.isDirectory(config.tileDirectory)) {
            sendStatus(exchange, 404);
            return;
        }
        Path root = config.tileDirectory.toRealPath();
        if (!Files.exists(tile)) {
            sendStatus(exchange, 404);
            return;
        }
        Path resolved = tile.toRealPath();
        if (!resolved.startsWith(root)) {
            sendStatus(exchange, 404);
            return;
        }
        sendFile(exchange, resolved, "image/png", "public, max-age=604800, immutable");
    }

    private void handleStartApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use POST."));
            return;
        }
        StartResult result = startRound();
        sendJson(exchange, result.ok ? 200 : 409, mapOf("ok", result.ok, "message", result.message));
    }

    private void handleEndApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use POST."));
            return;
        }
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        String token = query.get("roundtoken"), action = query.get("action");
        if (!validReportToken(token) || !Set.of("cancel-countdown", "end-round", "request-end-vote").contains(action == null ? "" : action)) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "Reload the map and confirm the current round action."));
            return;
        }
        int status = 409;
        boolean accepted = false;
        String message = "This round or action has changed. Review the current round and try again.";
        synchronized (stateLock) {
            // Bind the displayed confirmation to both its round and intent.
            // Advance the actual GO deadline before allowing countdown cancellation.
            updateRoundStateLocked();
            if (token.equals(roundToken)) {
                if ("cancel-countdown".equals(action) && roundState == RoundState.COUNTDOWN) {
                    endRound("host cancelled countdown");
                    accepted = true;
                    message = "Countdown cancelled by the host.";
                } else if ("end-round".equals(action) && eventMode && roundState == RoundState.RUNNING) {
                    endRound("event host ended the round");
                    accepted = true;
                    message = "Round ended by the event host.";
                } else if ("request-end-vote".equals(action) && !eventMode && roundState == RoundState.RUNNING) {
                    accepted = requestEndVote(null, token, false);
                    if (accepted) message = "Waiting for two players to approve on their phones.";
                }
            }
            if (accepted) status = 200;
        }
        sendJson(exchange, status, mapOf("ok", accepted, "message", message));
    }

    private void handleRebalanceApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use POST."));
            return;
        }
        StartResult result = rebalanceTeams();
        sendJson(exchange, result.ok ? 200 : 409, mapOf("ok", result.ok, "message", result.message));
    }

    private void handleBenchApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use POST."));
            return;
        }
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        int playerID;
        try { playerID = Integer.parseInt(query.get("id")); }
        catch (RuntimeException e) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "A player ID is required."));
            return;
        }
        String value = query.get("benched");
        if (!"true".equals(value) && !"false".equals(value)) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "A participation choice is required."));
            return;
        }
        boolean changed = false;
        synchronized (stateLock) {
            ClientConnection client = activeClients.get(playerID);
            if (!isRoundActiveLocked() && client != null && !client.closed) {
                client.lobbyBenched = Boolean.parseBoolean(value);
                Player player = players.get(playerID);
                if (player != null) hostPlayerParticipation.put(player.identity, client.lobbyBenched);
                invalidateTeamAssignmentsLocked();
                savePlayerDirectoryLocked();
                changed = true;
            }
        }
        if (changed) broadcastRoster();
        sendJson(exchange, changed ? 200 : 409, mapOf("ok", changed,
                "message", changed ? "Lobby updated." : "Participation can only change in the lobby."));
    }

    private void handleRespawnApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use POST."));
            return;
        }
        String idValue = queryParameters(exchange.getRequestURI()).get("id");
        int playerID;
        try {
            playerID = Integer.parseInt(idValue);
        } catch (RuntimeException e) {
            sendJson(exchange, 400, mapOf("ok", false, "message", "A player ID is required."));
            return;
        }
        boolean granted = grantRespawn(playerID);
        sendJson(exchange, granted ? 200 : 409, mapOf("ok", granted,
                "message", granted ? "Respawn granted." : "That player is not waiting to respawn."));
    }

    private Map<String, Object> dashboardState() {
        synchronized (stateLock) {
            updateRoundStateLocked();
            long now = elapsedMillis();
            while (!lasers.isEmpty() && now - lasers.peekFirst().createdAt > LASER_LIFETIME_MS)
                lasers.removeFirst();

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", roundState.name().toLowerCase(Locale.ROOT));
            state.put("roundtoken", roundToken);
            state.put("eventMode", eventMode);
            state.put("eventTitle", eventSettings.title());
            state.put("gameMode", config.gameMode.wireValue);
            state.put("bossMode", config.boss);
            state.put("infectionMode", config.infection);
            state.put("respawnQrEnabled", config.respawnQrEnabled);
            List<ClientConnection> playing = playingClientsLocked();
            state.put("playerCount", playing.size());
            state.put("benchedCount", activeClients.size() - playing.size());
            state.put("clockReady", clockReadyCountLocked());
            state.put("balancedQr", balancedQrEnabledLocked());
            state.put("balancedRandom", config.balanced && hostPlayerTeams.isEmpty());
            state.put("canRebalance", !isRoundActiveLocked() && config.balanced && hostPlayerTeams.isEmpty()
                    && config.gameMode == GameMode.TWO_TEAMS && playing.size() >= 2);
            state.put("assignedCount", balancedTeams.size());
            state.put("checkedInCount", balancedCheckedIn.size());
            state.put("checkInRemainingMs", balancedQrEnabledLocked() && roundState == RoundState.LOBBY
                    && balancedCheckInDeadline > 0
                    ? Math.max(0, balancedCheckInDeadline - now) : 0);
            state.put("startInMs", roundState == RoundState.COUNTDOWN ? Math.max(0, roundStartAt - now) : 0);
            state.put("remainingMs", roundEndAt > 0 && isRoundActiveLocked()
                    ? Math.max(0, roundEndAt - now) : 0);
            state.put("cooldownMs", Math.max(0, nextRoundStartAllowedAt - now));
            state.put("phoneUpdateInProgress", adbPhoneLease != null);
            state.put("canStart", adbPhoneLease == null && !hotspot.isBusy()
                    && (roundState == RoundState.LOBBY || roundState == RoundState.FINISHED)
                    && playing.size() >= 2
                    && now >= nextRoundStartAllowedAt
                    && (!config.boss || hasPlayingLeaderLocked())
                    && (!config.infection || hasPlayingLeaderLocked())
                    && clockReadyCountLocked() == playing.size()
                    && playing.stream().allMatch(client -> client.lobbyReady)
                    && (!balancedQrEnabledLocked() || balancedCheckInDeadline == 0));
            state.put("gunsReady", playing.stream()
                    .filter(client -> client.lobbyReady).count());
            state.put("endVoteRequested", endVoteRequested);
            state.put("endVoteCount", Long.bitCount(endVotes));
            state.put("hostPort", config.tcpPort);
            state.put("latestReport", roundReports.isEmpty() ? null : roundReports.peekFirst().token);

            List<Object> playerList = new ArrayList<>();
            for (Player player : players.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", player.id);
                item.put("name", player.name);
                item.put("team", teamFor(player.id));
                item.put("connected", player.connected);
                item.put("gunReady", player.connection != null && player.connection.lobbyReady);
                item.put("clockReady", player.connection != null && player.connection.clockReady);
                item.put("benched", player.benched());
                item.put("awaitingRespawn", player.awaitingRespawn);
                int lives = player.settings.overrideLives ? player.settings.lives : config.livesLimit;
                item.put("canRespawn", player.awaitingRespawn && (lives == 0 || player.deaths < lives));
                item.put("kills", player.kills);
                item.put("deaths", player.deaths);
                item.put("shots", player.shots);
                item.put("hits", player.hits);
                item.put("accuracy", player.shots == 0 ? null
                        : Math.min(100.0, (100.0 * player.hits) / player.shots));
                long gpsAgeMs = player.lastGpsAt == Long.MIN_VALUE ? -1 : Math.max(0, now - player.lastGpsAt);
                item.put("gpsAgeMs", gpsAgeMs < 0 ? null : gpsAgeMs);
                if (gpsAgeMs >= 0 && gpsAgeMs <= GPS_STALE_AFTER_MS
                        && validCoordinates(player.longitude, player.latitude)) {
                    item.put("longitude", player.longitude);
                    item.put("latitude", player.latitude);
                }
                List<Object> trail = new ArrayList<>();
                for (GeoPoint point : player.trail)
                    trail.add(mapOf("longitude", point.longitude, "latitude", point.latitude));
                item.put("trail", trail);
                playerList.add(item);
            }
            state.put("players", playerList);

            List<Object> laserList = new ArrayList<>();
            for (LaserEvent laser : lasers) {
                Map<String, Object> item = mapOf("shooter", laser.shooterID, "target", laser.targetID,
                        "kind", laser.kind, "ageMs", now - laser.createdAt);
                if (validCoordinates(laser.shooterLongitude, laser.shooterLatitude)) {
                    item.put("shooterLongitude", laser.shooterLongitude);
                    item.put("shooterLatitude", laser.shooterLatitude);
                }
                if (validCoordinates(laser.targetLongitude, laser.targetLatitude)) {
                    item.put("targetLongitude", laser.targetLongitude);
                    item.put("targetLatitude", laser.targetLatitude);
                }
                laserList.add(item);
            }
            state.put("lasers", laserList);

            while (!grenadeBursts.isEmpty()
                    && now - grenadeBursts.peekFirst().firstAt > GRENADE_MARKER_LIFETIME_MS)
                grenadeBursts.removeFirst();
            List<Object> burstList = new ArrayList<>();
            for (GrenadeBurst burst : grenadeBursts) {
                List<Object> hitList = new ArrayList<>();
                double latitudeSum = 0;
                double longitudeSinSum = 0;
                double longitudeCosSum = 0;
                for (GrenadeHit hit : burst.hits.values()) {
                    if (!validCoordinates(hit.longitude, hit.latitude))
                        continue;
                    hitList.add(mapOf("playerID", hit.playerID,
                            "longitude", hit.longitude, "latitude", hit.latitude));
                    latitudeSum += hit.latitude;
                    longitudeSinSum += Math.sin(Math.toRadians(hit.longitude));
                    longitudeCosSum += Math.cos(Math.toRadians(hit.longitude));
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("grenadeID", burst.grenadeID);
                item.put("hitCount", burst.hits.size());
                item.put("ageMs", now - burst.firstAt);
                item.put("hits", hitList);
                if (hitList.size() >= 4) {
                    item.put("centerLatitude", latitudeSum / hitList.size());
                    item.put("centerLongitude", Math.toDegrees(
                            Math.atan2(longitudeSinSum, longitudeCosSum)));
                }
                burstList.add(item);
            }
            state.put("grenadeBursts", burstList);
            return state;
        }
    }

    private void handleReportsApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use GET.")); return;
        }
        List<Object> reports = new ArrayList<>();
        synchronized (stateLock) {
            for (RoundReport report : roundReports) reports.add(report.description());
        }
        sendJson(exchange, 200, mapOf("reports", reports));
    }

    private void handleReportApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, mapOf("error", "Use GET.")); return;
        }
        String token = queryParameters(exchange.getRequestURI()).get("roundtoken");
        RoundReport selected = null;
        synchronized (stateLock) {
            if (token == null && !roundReports.isEmpty()) selected = roundReports.peekFirst();
            else for (RoundReport report : roundReports)
                if (report.token.equals(token)) { selected = report; break; }
        }
        if (selected != null) { sendReportJson(exchange, selected.json); return; }
        // Only UUID filenames may be read; an arbitrary query cannot escape
        // the app-owned report directory or expose another local file.
        if (validReportToken(token)) {
            Path file = config.reportDirectory.resolve(token + ".json");
            if (Files.isRegularFile(file) && Files.size(file) <= 8_000_000) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                try {
                    Object parsed = Json.parse(json);
                    if (parsed instanceof Map && token.equals(castObject(parsed).get("roundtoken"))) {
                        sendReportJson(exchange, json); return;
                    }
                } catch (IllegalArgumentException ignored) { }
            }
        }
        sendJson(exchange, 404, mapOf("error", "No completed round report was found."));
    }

    private static boolean validReportToken(String token) {
        if (token == null) return false;
        try { return UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static void sendReportJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void persistReport(RoundReport report) {
        // Disk work never blocks the combat/state lock or the final TCP frames.
        dashboardExecutor.execute(() -> {
            try {
                Files.createDirectories(config.reportDirectory);
                Files.writeString(config.reportDirectory.resolve(report.token + ".json"), report.json,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException e) {
                System.err.println("Round report remains available in the dashboard; could not save "
                        + config.reportDirectory + ": " + e.getMessage());
            }
        });
    }

    private void loadSavedReports() {
        if (!Files.isDirectory(config.reportDirectory)) return;
        List<Path> latest = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(config.reportDirectory, "*.json")) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                if (!validReportToken(filename.substring(0, filename.length() - 5))
                        || !Files.isRegularFile(file)) continue;
                latest.add(file);
                latest.sort((a, b) -> {
                    try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                    catch (IOException ignored) { return 0; }
                });
                if (latest.size() > MAX_REPORT_HISTORY) latest.remove(latest.size() - 1);
            }
            for (Path file : latest) {
                if (Files.size(file) > 8_000_000) continue;
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Object parsed;
                try { parsed = Json.parse(json); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!(parsed instanceof Map)) continue;
                Map<String, Object> data = castObject(parsed);
                String token = file.getFileName().toString().replace(".json", "");
                if (token.equals(data.get("roundtoken")) && data.get("players") instanceof List
                        && data.get("events") instanceof List) {
                    roundReports.addLast(new RoundReport(token, json, data, Collections.emptyMap()));
                    roundID = Math.max(roundID, longValue(data.get("roundid"), 0));
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load saved round reports: " + e.getMessage());
        }
    }

    private int clockReadyCountLocked() {
        int count = 0;
        for (ClientConnection client : activeClients.values()) {
            if (client.clockReady && !client.lobbyBenched)
                count++;
        }
        return count;
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        if (html == INDEX_PAGE || html == MAP_PAGE || html == LEADERBOARD_PAGE)
            html = html.replace("</body>", POST_ROUND_LINK_SCRIPT + "</body>");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendFile(HttpExchange exchange, Path file, String contentType,
                                 String cacheControl) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendStatus(exchange, 405);
            return;
        }
        if (!Files.isRegularFile(file)) {
            sendStatus(exchange, 404);
            return;
        }
        long size = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, size);
            Files.copy(file, exchange.getResponseBody());
        }
        exchange.close();
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> queryParameters(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isEmpty())
            return Collections.emptyMap();
        Map<String, String> values = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2)
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        return result;
    }

    private static List<String> localIPv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual())
                    continue;
                Enumeration<InetAddress> candidates = network.getInetAddresses();
                while (candidates.hasMoreElements()) {
                    InetAddress address = candidates.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress())
                        addresses.add(address.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        addresses.sort((first, second) -> addressPriority(first) - addressPriority(second));
        return addresses;
    }

    private static int addressPriority(String address) {
        if (address.startsWith("192.168."))
            return 0;
        if (address.startsWith("10."))
            return 1;
        if (address.startsWith("172."))
            return 2;
        return 3;
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual())
                    continue;
                for (InterfaceAddress interfaceAddress : network.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast instanceof Inet4Address && !addresses.contains(broadcast))
                        addresses.add(broadcast);
                }
            }
        } catch (SocketException ignored) {
        }
        try {
            InetAddress universal = InetAddress.getByName("255.255.255.255");
            if (!addresses.contains(universal))
                addresses.add(universal);
        } catch (IOException ignored) {
        }
        return addresses;
    }

    private static final class HostConfig {
        private static final Path HOST_DIRECTORY = Path.of(
                System.getProperty("simplecoil.home", "laptop-host")).toAbsolutePath().normalize();
        int tcpPort = 17_510;
        int udpPort = 17_500;
        int dashboardPort = 17_511;
        String dashboardBind = "127.0.0.1";
        int tilePort = 17_512;
        String tileBind = "0.0.0.0";
        GameMode gameMode = GameMode.TWO_TEAMS;
        // Phones see teammates by default. The laptop dashboard is a trusted
        // game-master display and remains an all-player view.
        GpsMode gpsMode = GpsMode.TEAMMATE;
        boolean useGps = true;
        boolean onlyServerSettings = true;
        boolean tournament = true;
        boolean boss;
        boolean captureTheFlag;
        boolean infection;
        boolean balanced;
        boolean balancedQr;
        int powerupQrRequired;
        boolean respawnQrEnabled = true;
        boolean allowLateJoin = true;
        boolean takeover;
        int startDelaySeconds = GameStartCountdown.DURATION_SECONDS;
        int durationMinutes = 5;
        int scoreLimit;
        int livesLimit;
        Path tileDirectory = HOST_DIRECTORY.resolve("maps");
        Path webDirectory = HOST_DIRECTORY.resolve("web");
        Path playerStore = Path.of(System.getProperty("simplecoil.players.file",
                Path.of(System.getProperty("user.home"), ".local", "share", "simplecoil", "players.json").toString()));
        Path reportDirectory = Path.of(System.getProperty("simplecoil.reports",
                HOST_DIRECTORY.resolve("reports").toString())).toAbsolutePath().normalize();
        boolean noBrowser;
        boolean eventMode = true;
        boolean showHelp;

        static HostConfig parse(String[] args) {
            HostConfig config = new HostConfig();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    config.showHelp = true;
                    return config;
                }
                if ("--tournament".equals(argument)) {
                    config.tournament = true;
                    config.onlyServerSettings = true;
                    config.gameMode = GameMode.TWO_TEAMS;
                    continue;
                }
                if ("--boss".equals(argument)) {
                    config.boss = true;
                    config.tournament = true;
                    config.onlyServerSettings = true;
                    config.gameMode = GameMode.TWO_TEAMS;
                    config.allowLateJoin = false;
                    continue;
                }
                if ("--ctf".equals(argument)) {
                    config.captureTheFlag = true;
                    config.tournament = true;
                    config.onlyServerSettings = true;
                    config.gameMode = GameMode.TWO_TEAMS;
                    continue;
                }
                if ("--infection".equals(argument)) {
                    config.infection = true;
                    config.tournament = true;
                    config.onlyServerSettings = true;
                    config.gameMode = GameMode.TWO_TEAMS;
                    config.scoreLimit = 0;
                    config.livesLimit = 0;
                    continue;
                }
                if ("--balanced-qr".equals(argument) || "--balanced-no-qr".equals(argument)) {
                    config.balanced = true;
                    config.balancedQr = "--balanced-qr".equals(argument);
                    config.gameMode = GameMode.TWO_TEAMS;
                    continue;
                }
                if ("--no-browser".equals(argument)) {
                    config.noBrowser = true;
                    continue;
                }
                if ("--no-event-mode".equals(argument)) {
                    config.eventMode = false;
                    continue;
                }
                if ("--no-late-join".equals(argument)) {
                    config.allowLateJoin = false;
                    continue;
                }
                if ("--timer-respawn".equals(argument) || "--qr-respawn".equals(argument)) {
                    config.respawnQrEnabled = "--qr-respawn".equals(argument);
                    continue;
                }
                if ("--takeover".equals(argument)) {
                    config.takeover = true;
                    continue;
                }
                if (index + 1 >= args.length)
                    throw new IllegalArgumentException("Missing value for " + argument + "\n\n" + usage());
                String value = args[++index];
                switch (argument) {
                    case "--tcp-port":
                        config.tcpPort = port(value, argument);
                        break;
                    case "--udp-port":
                        config.udpPort = port(value, argument);
                        break;
                    case "--dashboard-port":
                        config.dashboardPort = port(value, argument);
                        break;
                    case "--dashboard-bind":
                        config.dashboardBind = value;
                        break;
                    case "--tile-port":
                        config.tilePort = port(value, argument);
                        break;
                    case "--tile-bind":
                        config.tileBind = value;
                        break;
                    case "--tiles":
                        config.tileDirectory = Path.of(value).toAbsolutePath().normalize();
                        break;
                    case "--reports":
                        config.reportDirectory = Path.of(value).toAbsolutePath().normalize();
                        break;
                    case "--teams":
                        if ("2".equals(value)) {
                            config.gameMode = GameMode.TWO_TEAMS;
                        } else {
                            throw new IllegalArgumentException(
                                    "Tournament rules are locked to two teams");
                        }
                        break;
                    case "--gps":
                        if ("all".equalsIgnoreCase(value))
                            config.gpsMode = GpsMode.ALL;
                        else if ("team".equalsIgnoreCase(value))
                            config.gpsMode = GpsMode.TEAMMATE;
                        else
                            throw new IllegalArgumentException("--gps must be all or team");
                        break;
                    case "--start-delay":
                        config.startDelaySeconds = bounded(value, argument, 1, 1_000);
                        break;
                    case "--duration-minutes":
                        config.durationMinutes = bounded(value, argument, 0, 100);
                        break;
                    case "--score-limit":
                        config.scoreLimit = bounded(value, argument, 0, 100);
                        break;
                    case "--lives-limit":
                        config.livesLimit = bounded(value, argument, 0, 100);
                        break;
                    case "--powerup-qr":
                        config.powerupQrRequired = bounded(value, argument, 2, 8);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option " + argument + "\n\n" + usage());
                }
            }
            // The operator may choose how two teams are assigned, but cannot loosen the weapon
            // profile or change the match to four teams/free-for-all.
            config.tournament = true;
            config.onlyServerSettings = true;
            config.gameMode = GameMode.TWO_TEAMS;
            if (config.boss && config.balanced)
                throw new IllegalArgumentException("--boss cannot be combined with balanced teams");
            if (config.captureTheFlag && (config.boss || config.balanced))
                throw new IllegalArgumentException("--ctf cannot be combined with boss or balanced teams");
            if (config.infection && (config.boss || config.captureTheFlag || config.balanced))
                throw new IllegalArgumentException("--infection cannot be combined with boss, ctf, or balanced teams");
            if (config.infection) {
                config.scoreLimit = 0;
                config.livesLimit = 0;
                config.durationMinutes = 5;
            }
            return config;
        }

        private static int port(String value, String option) {
            return bounded(value, option, 1, 65_535);
        }

        private static int bounded(String value, String option, int minimum, int maximum) {
            try {
                int number = Integer.parseInt(value);
                if (number < minimum || number > maximum)
                    throw new IllegalArgumentException();
                return number;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
            }
        }

        static String usage() {
            return "Usage: java --add-modules jdk.httpserver -cp out com.simplecoil.laptophost.LaptopHost [options]\n"
                    + "  --teams 2                   Tournament team layout is locked to two teams\n"
                    + "  --tournament                Default rules; host can edit player profiles\n"
                    + "  --boss                      Player 1 vs everyone; fixed Boss rules\n"
                    + "  --ctf                       Two-team QR capture-the-flag rules\n"
                    + "  --infection                 Player 1 starts an Infection match\n"
                    + "  --balanced-qr               Balance past stats; require team QR check-in\n"
                    + "  --balanced-no-qr            Balance past stats; start without QR\n"
                    + "  --duration-minutes 0..100   0 means unlimited (default: 5)\n"
                    + "  --score-limit 0..100        0 means unlimited\n"
                    + "  --lives-limit 0..100        0 means unlimited\n"
                    + "  --powerup-qr 2..8           Enable QR power-ups (default: off)\n"
                    + "  --reports PATH             Save completed-round JSON reports here\n"
                    + "  --timer-respawn            Ten-second team respawns without QR scanning\n"
                    + "  --qr-respawn               QR checkpoint or timer respawns (default)\n"
                    + "  --gps all|team              Phone GPS visibility (default: team)\n"
                    + "  --start-delay 1..1000       Normal countdown seconds (default: "
                    + GameStartCountdown.DURATION_SECONDS + "; balanced: fixed "
                    + GameStartCountdown.DURATION_SECONDS + ")\n"
                    + "  --tcp-port PORT             Must remain 17510 for stock phones\n"
                    + "  --udp-port PORT             Must remain 17500 for stock phones\n"
                    + "  --dashboard-port PORT       Local dashboard (default: 17511)\n"
                    + "  --dashboard-bind ADDRESS    Defaults to 127.0.0.1\n"
                    + "  --tile-port PORT            Read-only phone tiles (default: 17512)\n"
                    + "  --tile-bind ADDRESS         Phone tile bind (default: 0.0.0.0)\n"
                    + "  --tiles DIRECTORY           Offline XYZ tiles (default: laptop-host/maps)\n"
                    + "  --no-late-join              Reject joins after the start\n"
                    + "  --takeover                  Replace an idle phone-hosted lobby on this Wi-Fi\n"
                    + "  --no-event-mode             Allow ordinary phone controls in the lobby\n"
                    + "  --no-browser                Do not open the dashboard automatically";
        }
    }

    /** Tiny dependency-free JSON implementation for the protocol and dashboard. */
    static final class Json {
        private Json() {
        }

        static Object parse(String text) {
            if (text == null)
                throw new IllegalArgumentException("null JSON");
            Parser parser = new Parser(text);
            Object value = parser.value(0);
            parser.skipWhitespace();
            if (!parser.atEnd())
                throw new IllegalArgumentException("unexpected JSON data");
            return value;
        }

        static String stringify(Object value) {
            StringBuilder output = new StringBuilder();
            append(output, value);
            return output.toString();
        }

        private static void append(StringBuilder output, Object value) {
            if (value == null) {
                output.append("null");
            } else if (value instanceof String) {
                appendString(output, (String) value);
            } else if (value instanceof Boolean) {
                output.append(value);
            } else if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number))
                    output.append("null");
                else
                    output.append(value);
            } else if (value instanceof Map) {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!first)
                        output.append(',');
                    first = false;
                    appendString(output, String.valueOf(entry.getKey()));
                    output.append(':');
                    append(output, entry.getValue());
                }
                output.append('}');
            } else if (value instanceof Collection) {
                output.append('[');
                boolean first = true;
                for (Object item : (Collection<?>) value) {
                    if (!first)
                        output.append(',');
                    first = false;
                    append(output, item);
                }
                output.append(']');
            } else {
                appendString(output, String.valueOf(value));
            }
        }

        private static void appendString(StringBuilder output, String value) {
            output.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"': output.append("\\\""); break;
                    case '\\': output.append("\\\\"); break;
                    case '\b': output.append("\\b"); break;
                    case '\f': output.append("\\f"); break;
                    case '\n': output.append("\\n"); break;
                    case '\r': output.append("\\r"); break;
                    case '\t': output.append("\\t"); break;
                    default:
                        if (character < 0x20)
                            output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                        else
                            output.append(character);
                }
            }
            output.append('"');
        }

        private static final class Parser {
            private final String text;
            private int position;

            Parser(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return position >= text.length();
            }

            void skipWhitespace() {
                while (!atEnd() && Character.isWhitespace(text.charAt(position)))
                    position++;
            }

            Object value(int depth) {
                if (depth > 64)
                    throw new IllegalArgumentException("JSON nesting is too deep");
                skipWhitespace();
                if (atEnd())
                    throw new IllegalArgumentException("unexpected end of JSON");
                char first = text.charAt(position);
                if (first == '{')
                    return object(depth + 1);
                if (first == '[')
                    return array(depth + 1);
                if (first == '"')
                    return string();
                if (first == 't' && take("true"))
                    return Boolean.TRUE;
                if (first == 'f' && take("false"))
                    return Boolean.FALSE;
                if (first == 'n' && take("null"))
                    return null;
                if (first == '-' || (first >= '0' && first <= '9'))
                    return number();
                throw new IllegalArgumentException("invalid JSON value");
            }

            Map<String, Object> object(int depth) {
                expect('{');
                Map<String, Object> object = new LinkedHashMap<>();
                skipWhitespace();
                if (takeChar('}'))
                    return object;
                while (true) {
                    skipWhitespace();
                    if (atEnd() || text.charAt(position) != '"')
                        throw new IllegalArgumentException("object key must be a string");
                    String key = string();
                    skipWhitespace();
                    expect(':');
                    Object value = value(depth);
                    object.put(key, value);
                    skipWhitespace();
                    if (takeChar('}'))
                        return object;
                    expect(',');
                }
            }

            List<Object> array(int depth) {
                expect('[');
                List<Object> array = new ArrayList<>();
                skipWhitespace();
                if (takeChar(']'))
                    return array;
                while (true) {
                    array.add(value(depth));
                    skipWhitespace();
                    if (takeChar(']'))
                        return array;
                    expect(',');
                }
            }

            String string() {
                expect('"');
                StringBuilder value = new StringBuilder();
                while (!atEnd()) {
                    char character = text.charAt(position++);
                    if (character == '"')
                        return value.toString();
                    if (character == '\\') {
                        if (atEnd())
                            throw new IllegalArgumentException("unfinished JSON escape");
                        char escaped = text.charAt(position++);
                        switch (escaped) {
                            case '"': value.append('"'); break;
                            case '\\': value.append('\\'); break;
                            case '/': value.append('/'); break;
                            case 'b': value.append('\b'); break;
                            case 'f': value.append('\f'); break;
                            case 'n': value.append('\n'); break;
                            case 'r': value.append('\r'); break;
                            case 't': value.append('\t'); break;
                            case 'u':
                                if (position + 4 > text.length())
                                    throw new IllegalArgumentException("short unicode escape");
                                try {
                                    value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                                } catch (NumberFormatException e) {
                                    throw new IllegalArgumentException("invalid unicode escape");
                                }
                                position += 4;
                                break;
                            default:
                                throw new IllegalArgumentException("invalid JSON escape");
                        }
                    } else {
                        if (character < 0x20)
                            throw new IllegalArgumentException("control character in JSON string");
                        value.append(character);
                    }
                }
                throw new IllegalArgumentException("unterminated JSON string");
            }

            Number number() {
                int start = position;
                if (takeChar('-') && atEnd())
                    throw new IllegalArgumentException("invalid JSON number");
                if (takeChar('0')) {
                    // A leading zero is legal only for zero itself.
                } else {
                    requireDigits();
                }
                boolean decimal = false;
                if (takeChar('.')) {
                    decimal = true;
                    requireDigits();
                }
                if (!atEnd() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                    decimal = true;
                    position++;
                    if (!atEnd() && (text.charAt(position) == '+' || text.charAt(position) == '-'))
                        position++;
                    requireDigits();
                }
                String raw = text.substring(start, position);
                try {
                    // A numeric Double/Long ternary promotes both branches to
                    // double, losing integer precision for long grant IDs.
                    if (decimal) return Double.valueOf(raw);
                    return Long.valueOf(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid JSON number");
                }
            }

            void requireDigits() {
                int start = position;
                while (!atEnd() && Character.isDigit(text.charAt(position)))
                    position++;
                if (start == position)
                    throw new IllegalArgumentException("expected JSON digit");
            }

            boolean take(String value) {
                if (!text.regionMatches(position, value, 0, value.length()))
                    return false;
                position += value.length();
                return true;
            }

            boolean takeChar(char expected) {
                if (!atEnd() && text.charAt(position) == expected) {
                    position++;
                    return true;
                }
                return false;
            }

            void expect(char expected) {
                skipWhitespace();
                if (!takeChar(expected))
                    throw new IllegalArgumentException("expected '" + expected + "'");
            }
        }
    }

    private static final String POST_ROUND_LINK_SCRIPT = """
            <script>(()=>{const link=document.createElement('a');link.textContent='Review last round';link.hidden=true;link.style.cssText='color:#b4d9ff;font-size:15px;white-space:nowrap;padding:8px';(document.querySelector('header')||document.querySelector('.buttons')||document.querySelector('h1')?.parentElement||document.body).append(link);async function reports(){try{const r=await fetch('/api/reports',{cache:'no-store'}),data=await r.json(),latest=data.reports?.[0];link.hidden=!latest;if(latest)link.href='/round-report?roundtoken='+encodeURIComponent(latest.roundtoken)}catch(e){}setTimeout(reports,5000)}reports()})();</script>
            """;

    private static final String ROUND_REPORT_PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>SimpleCoil Round Review</title>
            <style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0b121b;color:#eef4f9;font:16px system-ui,sans-serif}main{max-width:1600px;margin:auto;padding:24px}header{display:flex;gap:14px;align-items:center;flex-wrap:wrap}h1{margin:0;flex:1}a{color:#7cbcff}select,button{padding:9px;background:#162b3e;color:#fff;border:1px solid #436079;border-radius:5px}p{color:#b9c9d6;line-height:1.5}.cards{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.card{background:#152332;border:1px solid #2b455b;border-radius:8px;padding:12px 18px;min-width:160px}.card strong{display:block;font-size:25px}.note{font-size:13px;color:#9eb1c2}.warning{color:#ffd68a}fieldset{border:1px solid #2b455b;border-radius:7px;display:flex;gap:14px;flex-wrap:wrap;margin:18px 0}label{white-space:nowrap}.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px}.scroll{overflow:auto;background:#111e2b;border:1px solid #2b455b;border-radius:8px}svg{display:block;min-width:1100px}table{border-collapse:collapse;width:100%;background:#111e2b}th,td{text-align:left;padding:11px 12px;border-bottom:1px solid #2b455b}th{color:#adc2d5;font-size:13px}h2{margin:28px 0 10px}#empty{padding:30px 0}.legend{font-size:14px}svg [tabindex]:focus{stroke:#fff;stroke-width:4;outline:none}@media(max-width:650px){main{padding:14px}h1{font-size:23px}th,td{padding:9px}}</style></head>
            <body><main><header><h1>Round Review</h1><a href="/map">Live map</a><a href="/leaderboard">Leaderboard</a><label>Round <select id="round" aria-label="Completed round"></select></label><a id="download" hidden>Download JSON</a></header>
            <p id="status" role="status">Waiting for a completed round...</p><div id="empty">Results and a timeline appear after a round finishes.</div><section id="review" hidden>
            <div id="cards" class="cards"></div><p id="quality" class="warning"></p><p id="definitions" class="note"></p>
            <h2>What happened</h2><p class="note">Time starts at actual GO, not the countdown. Each lane is one player, even if their phone reconnects with a different player ID. Hover or focus an event for details. Shot activity is grouped into one-second host-receipt buckets.</p>
            <fieldset id="filters"><legend>Timeline events</legend></fieldset><div class="scroll"><svg id="timeline" role="img" aria-label="Round timeline by player"></svg></div>
            <h2>After-round player statistics</h2><div class="scroll"><table><thead><tr><th scope="col">Player / team</th><th scope="col">Kills</th><th scope="col">Deaths</th><th scope="col">IR hits / shots</th><th scope="col">Accuracy</th><th scope="col">Best kill streak</th><th scope="col">Best death streak</th><th scope="col">Skill before / after</th><th scope="col">Data</th></tr></thead><tbody id="rows"></tbody></table></div>
            <p class="note">A streak is consecutive kills without a death, or deaths without a kill. Skill is the existing cumulative kills/deaths balancing index (0-200, neutral 100), not Elo or a mode-adjusted competitive rating.</p></section></main>
            <script>
            const $=s=>document.querySelector(s),select=$('#round'),status=$('#status'),review=$('#review'),rows=$('#rows'),svg=$('#timeline');let report=null,selected=new URLSearchParams(location.search).get('roundtoken'),manual=!!selected,loaded=null;
            const categories=[['shot','Shots','#8294aa'],['hit','IR hits','#f6d76e'],['kill','Kills','#76dc9b'],['death','Deaths','#ff617b'],['respawn','Respawns','#64c8ea'],['powerup','Power-ups','#c49aff'],['objective','Objectives','#ffb565'],['grenade','Grenade hits','#de8870'],['join','Joins','#b3d574'],['drop','Disconnects','#aaa'],['start','Start / end','#fff']],colors=Object.fromEntries(categories.map(c=>[c[0],c[2]]));colors.end=colors.start;const enabled=new Set(categories.filter(c=>c[0]!=='shot').map(c=>c[0]));
            for(const [kind,name,color] of categories){const label=document.createElement('label'),check=document.createElement('input'),dot=document.createElement('span');check.type='checkbox';check.checked=enabled.has(kind);check.onchange=()=>{check.checked?enabled.add(kind):enabled.delete(kind);drawTimeline()};dot.className='dot';dot.style.backgroundColor=color;label.append(check,dot,document.createTextNode(name));$('#filters').append(label)}
            function formatTime(ms){const seconds=Math.floor(Math.max(0,Number(ms)||0)/1000);return Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0')}
            function cell(value){const td=document.createElement('td');td.textContent=value;return td}
            function draw(){if(!report)return;review.hidden=false;$('#empty').hidden=true;status.textContent='Round '+report.roundid+' / '+report.mode+' / '+report.reason;$('#download').hidden=false;$('#download').href='/api/report?roundtoken='+encodeURIComponent(report.roundtoken);$('#download').download='SimpleCoil-round-'+report.roundid+'.json';$('#cards').replaceChildren();for(const [label,value] of [['Actual play',formatTime(report.durationMs)],['Players',report.players.length],['Kills',report.players.reduce((n,p)=>n+p.kills,0)],['IR hits / shots',report.players.reduce((n,p)=>n+p.hits,0)+' / '+report.players.reduce((n,p)=>n+p.shots,0)]]){const box=document.createElement('div'),strong=document.createElement('strong');box.className='card';strong.textContent=value;box.append(strong,document.createTextNode(label));$('#cards').append(box)}$('#quality').textContent=(report.started?'':'Countdown cancelled; no actual play. ')+(report.complete?'':'Some telemetry was incomplete or came from older phones; observed totals and streaks may be lower bounds. ')+(report.omittedEvents?report.omittedEvents+' event batches omitted by the bounded timeline; totals remain available.':'');$('#definitions').textContent=report.accuracyDefinition+'. '+report.timing+'.';rows.replaceChildren();for(const p of [...report.players].sort((a,b)=>b.kills-a.kills||a.playerID-b.playerID)){const tr=document.createElement('tr');tr.append(...[p.name+' / Team '+p.team,p.kills,p.deaths,p.hits+' / '+p.shots,p.accuracy==null?'N/A':p.accuracy.toFixed(1)+'%',p.bestkillstreak,p.bestdeathstreak,p.skillbefore+' / '+p.skillafter,p.complete?'Observed':'Partial'].map(cell));rows.append(tr)}drawTimeline()}
            function node(tag,attrs,text){const n=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [k,v] of Object.entries(attrs||{}))n.setAttribute(k,String(v));if(text!==undefined)n.textContent=text;return n}
            function drawTimeline(){if(!report)return;svg.replaceChildren();const w=1400,left=230,right=32,top=70,lane=46,people=report.players,h=top+(people.length+1)*lane+45,duration=Math.max(1000,Number(report.durationMs)||0),byKey=new Map(people.map(p=>[p.key,p])),laneY=new Map(people.map((p,i)=>[p.key,top+(i+1)*lane])),x=t=>left+Math.min(duration,Math.max(0,Number(t)||0))/duration*(w-left-right);svg.setAttribute('viewBox','0 0 '+w+' '+h);svg.style.width=w+'px';svg.style.height=h+'px';svg.append(node('title',{},'Round '+report.roundid+' timeline'),node('desc',{},'Player lanes with confirmed hits, kills, deaths, respawns and other observed round events. Times are host receipt times after actual GO.'));for(let i=0;i<=10;i++){const t=i*duration/10,at=x(t);svg.append(node('line',{x1:at,y1:45,x2:at,y2:h-38,stroke:'#294153'}),node('text',{x:at,y:28,fill:'#c2d4e2','text-anchor':'middle','font-size':13},formatTime(t)))}svg.append(node('text',{x:12,y:top,fill:'#9fb5c7','font-size':13},'ROUND'));for(const p of people){const y=laneY.get(p.key);svg.append(node('line',{x1:left,y1:y,x2:w-right,y2:y,stroke:'#294153'}));const text=node('text',{x:12,y:y+5,fill:p.team===1?'#79baff':'#ff9d9e','font-size':14},p.name.length>25?p.name.slice(0,25)+'...':p.name);text.append(node('title',{},p.name+' / Team '+p.team+' / IDs '+p.seatIDs.join(', ')));svg.append(text)}
            for(const e of report.events){const category=e.kind==='end'?'start':e.kind;if(!enabled.has(category))continue;const at=x(e.timeMs),y=laneY.get(e.actor)||top,color=colors[e.kind]||'#fff',actor=byKey.get(e.actor),target=byKey.get(e.target);if(target&&laneY.has(e.target)&&['hit','kill','grenade'].includes(e.kind))svg.append(node('line',{x1:at,y1:y,x2:at,y2:laneY.get(e.target),stroke:color,'stroke-opacity':.38,'stroke-width':2}));const dot=node(e.kind==='death'?'rect':'circle',e.kind==='death'?{x:at-5,y:y-5,width:10,height:10,fill:color}:{cx:at,cy:y,r:e.kind==='shot'?Math.min(7,3+Math.log2(1+e.count)):5,fill:color});const description=formatTime(e.timeMs)+' / '+e.kind+' / '+(actor?actor.name:'Round')+(target?' -> '+target.name:'')+(e.count>1?' / '+e.count:'')+' / '+e.detail;dot.setAttribute('tabindex','0');dot.setAttribute('role','img');dot.setAttribute('aria-label',description);dot.append(node('title',{},description));svg.append(dot)} }
            async function load(token){try{const r=await fetch('/api/report'+(token?'?roundtoken='+encodeURIComponent(token):''),{cache:'no-store'});if(!r.ok)throw Error();report=await r.json();loaded=report.roundtoken;selected=loaded;draw()}catch(e){status.textContent='No completed report available for this round.'}}
            select.onchange=()=>{manual=true;selected=select.value;load(selected)};
            async function refresh(){try{const r=await fetch('/api/reports',{cache:'no-store'});if(!r.ok)throw Error();const list=(await r.json()).reports;select.replaceChildren();for(const item of list){const option=document.createElement('option');option.value=item.roundtoken;option.textContent='Round '+item.roundid+' / '+item.mode+' / '+formatTime(item.durationMs);select.append(option)}if(!manual&&list.length)selected=list[0].roundtoken;if(selected){select.value=selected;if(loaded!==selected)await load(selected)}}catch(e){status.textContent='Disconnected from laptop host.'}setTimeout(refresh,5000)}refresh();
            </script></body></html>
            """;

    private static final String INDEX_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>LAZER GUN RUN · Laptop Host</title>
            <style>body{margin:0;background:#10141b;color:#eef3f8;font:18px system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}main{max-width:660px;padding:32px}h1{margin-top:0}.buttons{display:flex;gap:14px;flex-wrap:wrap}a,button{border:0;border-radius:8px;background:#2585d8;color:white;padding:14px 18px;font:inherit;text-decoration:none;cursor:pointer}p{line-height:1.5;color:#c6d1dc}.note{font-size:.9em;color:#9dadbc}</style></head>
            <body><main><h1 id="event-title">LAZER GUN RUN</h1><p>Open the live map and leaderboard as two independent display windows. Keep this page local to the laptop; game controls are available on the map display.</p>
            <div class="buttons"><a href="/map" target="simplecoil-map">Open map display</a><a href="/leaderboard" target="simplecoil-leaderboard">Open leaderboard</a><button onclick="openDisplays()">Open both displays</button><a href="/wifi" target="simplecoil-wifi">Wi-Fi hotspot</a><a href="/phone-updates" target="simplecoil-phone-updates">Phone updates</a><a href="/players" target="simplecoil-players">Players &amp; Event Mode</a></div>
            <p class="note">Phones join the laptop's printed LAN IP from <em>Join Game</em>. The dashboard uses the local browser only unless the host was started with <code>--dashboard-bind 0.0.0.0</code>.</p>
            <script>fetch('/api/state',{cache:'no-store'}).then(r=>r.json()).then(s=>{document.querySelector('#event-title').textContent=s.eventTitle||'LAZER GUN RUN'}).catch(()=>{});function openDisplays(){window.open('/map','simplecoil-map','popup=yes');window.open('/leaderboard','simplecoil-leaderboard','popup=yes')}</script></main></body></html>
            """;

    private static final String MAP_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>SimpleCoil Tactical Map</title>
            <link rel="stylesheet" href="/assets/leaflet/leaflet.css">
            <style>
            :root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0a1017;color:#eef4f9;font:15px system-ui,sans-serif;overflow:hidden}header{height:60px;display:flex;align-items:center;gap:12px;padding:9px 14px;background:#111c27;border-bottom:1px solid #274257}h1{font-size:20px;margin:0;white-space:nowrap}#status{color:#b8c8d8;flex:1}button{border:0;border-radius:7px;padding:9px 12px;background:#267ec8;color:#fff;font:inherit;cursor:pointer}button.danger{background:#b83d46}button:disabled{opacity:.45;cursor:not-allowed}#map{width:100vw;height:calc(100vh - 60px);background-color:#0d1720;background-image:linear-gradient(#203445 1px,transparent 1px),linear-gradient(90deg,#203445 1px,transparent 1px);background-size:50px 50px}.legend{position:fixed;z-index:1000;right:16px;bottom:16px;max-width:340px;background:#101a25e8;border:1px solid #34516a;border-radius:8px;padding:10px;line-height:1.7}.swatch{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px}.team1{background:#49a5ff}.team2{background:#ff5d67}#tile-status{color:#ffd98a;font-size:12px}#respawns:empty{display:none}#respawns{border-top:1px solid #34516a;margin-top:7px;padding-top:7px}.respawn{font-size:12px;padding:5px 7px;margin:3px 3px 0 0;background:#466c32}.player-label{background:#101a25e8;border:1px solid #7890a5;color:#fff;font-weight:700;box-shadow:none}.player-label:before{border-top-color:#7890a5}.leaflet-control-zoom a{background:#152332;color:#fff;border-color:#35516a}.leaflet-control-zoom a:hover{background:#22384b;color:#fff}@media(max-width:760px){header{gap:6px;padding:7px}h1{font-size:15px}#status{font-size:11px}button{padding:7px 8px;font-size:12px}}
            </style></head><body><header><h1>Tactical GPS Map</h1><span id="status">Connecting...</span><button onclick="window.open('/players','simplecoil-players')">Players</button><button onclick="window.open('/wifi','simplecoil-wifi')">Wi-Fi</button><button onclick="window.open('/phone-updates','simplecoil-phone-updates')">Phone updates</button><button id="fit">Fit players</button><button id="rebalance" hidden disabled>Rebalance teams</button><button id="start">Start game</button><button id="end" class="danger">End game</button></header><div id="map"></div><div class="legend"><span class="swatch team1"></span>Team 1 &nbsp;<span class="swatch team2"></span>Team 2<br>Bright lines are verified hits; red lines are eliminations.<br>Red circles are grenade hits; a red dot estimates the center after 4 mapped hits.<br><span id="tile-status">Checking offline maps...</span><div id="respawns"></div><div id="lobby-players" style="max-height:40vh;overflow:auto"></div></div>
            <script src="/assets/leaflet/leaflet.js"></script><script>
            const status=document.querySelector('#status'),start=document.querySelector('#start'),rebalance=document.querySelector('#rebalance'),end=document.querySelector('#end'),fitButton=document.querySelector('#fit'),respawns=document.querySelector('#respawns'),tileStatus=document.querySelector('#tile-status'),lobbyPlayers=document.querySelector('#lobby-players');let lobbySignature='';
            const map=L.map('map',{preferCanvas:true,zoomControl:true,attributionControl:true}).setView([20,0],2),players=new Map();let state=null,laserLayers=[],grenadeLayers=[],fitted=false,mapConfig={maxZoom:19};
            function color(team){return team===1?'#49a5ff':team===2?'#ff5d67':`hsl(${(team*57)%360} 90% 63%)`}
            function valid(p){return p&&Number.isFinite(p.longitude)&&Number.isFinite(p.latitude)&&(p.longitude!==0||p.latitude!==0)&&p.longitude>=-180&&p.longitude<=180&&p.latitude>=-90&&p.latitude<=90}function ll(p){return[p.latitude,p.longitude]}
            async function setupTiles(){try{const r=await fetch('/api/map-config',{cache:'no-store'});mapConfig=await r.json();const tileUrl='/tiles/{z}/{x}/{y}.png'+(mapConfig.version?'?v='+encodeURIComponent(mapConfig.version):'');const tiles=L.tileLayer(tileUrl,{minZoom:mapConfig.minZoom,maxZoom:mapConfig.maxZoom+3,maxNativeZoom:mapConfig.maxZoom,bounds:mapConfig.bounds,attribution:mapConfig.attribution||'',noWrap:true,keepBuffer:4,updateWhenIdle:false});let loaded=false;tiles.on('tileload',()=>{if(!loaded){loaded=true;tileStatus.textContent='Offline map loaded.'}});tiles.addTo(map);tileStatus.textContent=mapConfig.available?'Offline tiles ready; move to the game area.':'No offline tiles installed; GPS and combat overlays still work.';if(!(state?.players||[]).some(valid)){if(mapConfig.center)map.setView(mapConfig.center,mapConfig.zoom);else if(mapConfig.bounds)map.fitBounds(mapConfig.bounds,{padding:[30,30],maxZoom:18})}}catch(e){tileStatus.textContent='Offline map configuration unavailable.'}}
            async function control(path){try{const r=await fetch(path,{method:'POST'}),j=await r.json();status.textContent=j.message||(j.ok?'Done.':'Unable to complete action.')}catch(e){status.textContent='Host control error.'}}
            start.onclick=()=>control('/api/start');rebalance.onclick=()=>{if(confirm('Rebalance teams using player stats? Team QR check-ins will reset.'))control('/api/rebalance')};
            end.onclick=()=>{const token=state?.roundtoken,action=state?.state==='countdown'?'cancel-countdown':state?.eventMode?'end-round':'request-end-vote',prompt=action==='cancel-countdown'?'Cancel the countdown for all players?':action==='end-round'?'End the round for all players?':'Ask two participating players to approve ending the round?';if(token&&confirm(prompt))control('/api/end?roundtoken='+encodeURIComponent(token)+'&action='+encodeURIComponent(action))};fitButton.onclick=()=>fitPlayers(true);
            function drawRespawns(s){respawns.replaceChildren();for(const p of s.players||[]){if(!p.canRespawn||p.benched||!p.connected)continue;const b=document.createElement('button');b.className='respawn';b.textContent=`Respawn ${p.name} #${p.id}`;b.onclick=()=>control('/api/respawn?id='+encodeURIComponent(p.id));respawns.append(b)}}
            function drawLobby(s){const visible=s.state==='lobby';lobbyPlayers.hidden=!visible;if(!visible){lobbySignature='';return}const list=[...(s.players||[])].sort((a,b)=>Number(a.benched)-Number(b.benched)||a.team-b.team||a.id-b.id);const sig=JSON.stringify(list.map(p=>[p.id,p.name,p.team,p.connected,p.gunReady,p.clockReady,p.benched]));if(sig===lobbySignature)return;lobbySignature=sig;lobbyPlayers.replaceChildren();for(const p of list){const row=document.createElement('div'),name=document.createElement('span'),b=document.createElement('button');row.style.cssText='display:flex;gap:8px;align-items:center;margin-top:6px';name.style.flex='1';const status=p.benched?'Sitting out':!p.connected?'Reconnecting':!p.gunReady?'Needs gun':!p.clockReady?'Syncing clock':'Ready';name.textContent=`Team ${p.team} / ${p.name} #${p.id} - ${status}`;b.textContent=p.benched?'Play':'Sit out';b.disabled=!p.connected;b.onclick=()=>{b.disabled=true;control('/api/bench?id='+encodeURIComponent(p.id)+'&benched='+(!p.benched)).finally(()=>{lobbySignature=''})};const edit=document.createElement('button');edit.textContent='Edit';edit.onclick=()=>window.open('/players?id='+encodeURIComponent(p.id),'simplecoil-players');row.append(name,edit,b);lobbyPlayers.append(row)}}
            function label(s){if(!s)return 'Connecting...';const p=s.playerCount||0,ready=s.clockReady||0;if(s.state==='countdown')return `Starting in ${Math.ceil(s.startInMs/1000)}s - ${p} players`;if(s.state==='running')return s.remainingMs?`Running - ${Math.ceil(s.remainingMs/1000)}s left - ${p} players`:`Running - ${p} players`;if(s.cooldownMs>0)return `Finished - next game in ${Math.ceil(s.cooldownMs/1000)}s`;if(s.state==='finished')return 'Finished - final scores visible';if(s.balancedQr&&s.checkInRemainingMs>0)return `Team QR check-ins ${s.checkedInCount}/${s.assignedCount}`;const missing=(s.players||[]).filter(x=>x.connected&&!x.benched&&!x.gunReady).map(x=>x.name+' #'+x.id);return `${p} players - guns ${s.gunsReady||0}/${p} - clocks ${ready}/${p}`+(missing.length?' - Needs gun: '+missing.join(', '):'')}
            function fitPlayers(force){const points=(state?.players||[]).filter(valid).map(ll),maxZoom=Number.isFinite(mapConfig.maxZoom)?mapConfig.maxZoom:18;if(!points.length)return;if(points.length===1)map.setView(points[0],Math.min(18,maxZoom));else map.fitBounds(points,{padding:[70,70],maxZoom:Math.min(18,maxZoom)});if(force||!fitted)fitted=true}
            function drawPlayers(s){const visible=new Set();for(const p of s.players.filter(p=>!p.benched)){const points=(p.trail||[]).filter(valid).map(ll);if(!valid(p)&&!points.length)continue;visible.add(p.id);let item=players.get(p.id);if(!item){const trail=L.polyline([],{color:color(p.team),weight:3,opacity:.48,interactive:false}).addTo(map);item={marker:null,trail};players.set(p.id,item)}const opacity=p.connected?1:.4;if(valid(p)){if(!item.marker)item.marker=L.circleMarker(ll(p),{radius:11,color:'#fff',weight:2,fillColor:color(p.team),fillOpacity:1}).addTo(map).bindTooltip('',{permanent:true,direction:'top',className:'player-label',offset:[0,-9]});item.marker.setLatLng(ll(p)).setRadius(p.awaitingRespawn?8:11).setStyle({fillColor:color(p.team),opacity,fillOpacity:opacity});const label=document.createElement('span');label.textContent=`${p.name} #${p.id}${p.awaitingRespawn?' - RESPAWN':''}`;item.marker.setTooltipContent(label)}else if(item.marker){map.removeLayer(item.marker);item.marker=null}item.trail.setStyle({color:color(p.team),opacity:.48*opacity});item.trail.setLatLngs(points)}for(const [id,item] of players){if(visible.has(id))continue;if(item.marker)map.removeLayer(item.marker);map.removeLayer(item.trail);players.delete(id)}}
            function drawLasers(s){for(const layer of laserLayers)map.removeLayer(layer);laserLayers=[];const byId=Object.fromEntries(s.players.filter(valid).map(p=>[p.id,p]));function point(event,prefix,fallback){const p={longitude:event[prefix+'Longitude'],latitude:event[prefix+'Latitude']};return valid(p)?p:fallback}for(const event of s.lasers||[]){const a=point(event,'shooter',byId[event.shooter]),b=point(event,'target',byId[event.target]);if(!valid(a)||!valid(b))continue;const kill=event.kind==='kill',opacity=Math.max(0,1-event.ageMs/1500);laserLayers.push(L.polyline([ll(a),ll(b)],{color:kill?'#ff3047':'#fff36b',weight:kill?7:5,opacity,interactive:false}).addTo(map))}}
            function drawGrenades(s){for(const layer of grenadeLayers)map.removeLayer(layer);grenadeLayers=[];for(const burst of s.grenadeBursts||[]){const opacity=Math.max(0,1-burst.ageMs/8000);for(const hit of burst.hits||[]){if(!valid(hit))continue;grenadeLayers.push(L.circleMarker(ll(hit),{radius:17,color:'#ff3047',weight:3,opacity,fillColor:'#ff3047',fillOpacity:.24*opacity,interactive:true}).addTo(map).bindTooltip(`Grenade #${burst.grenadeID} hit player #${hit.playerID}`))}const center={latitude:burst.centerLatitude,longitude:burst.centerLongitude};if(!valid(center))continue;grenadeLayers.push(L.circleMarker(ll(center),{radius:8,color:'#fff',weight:2,opacity,fillColor:'#ff3047',fillOpacity:opacity,interactive:true}).addTo(map).bindTooltip(`Estimated grenade #${burst.grenadeID} center (${burst.hits.length} mapped hits)`))}}
            function draw(s){document.title=(s.eventTitle||'LAZER GUN RUN')+' - Tactical Map';status.textContent=(s.eventTitle||'LAZER GUN RUN')+' - '+label(s);start.disabled=!s.canStart;rebalance.hidden=!s.balancedRandom;rebalance.disabled=!s.canRebalance;end.disabled=!['countdown','running'].includes(s.state);end.textContent=s.state==='countdown'?'Cancel countdown':!end.disabled&&!s.eventMode&&s.endVoteRequested?`End game (${s.endVoteCount}/2)`:'End game';drawRespawns(s);drawLobby(s);drawPlayers(s);drawLasers(s);drawGrenades(s);if(!fitted&&(s.players||[]).some(valid))fitPlayers(false)}
            async function refresh(){try{const r=await fetch('/api/state',{cache:'no-store'});if(!r.ok)throw Error();state=await r.json();draw(state)}catch(e){status.textContent='Disconnected from laptop host.'}setTimeout(refresh,150)}setupTiles();refresh();
            </script></body></html>
            """;

    private static final String LEADERBOARD_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>SimpleCoil Leaderboard</title>
            <style>:root{color-scheme:dark}body{margin:0;background:#0b121b;color:#eef4f9;font:18px system-ui,sans-serif;padding:26px}h1{margin:0 0 4px}#subtitle{color:#aebdca;margin:0 0 22px}table{width:100%;border-collapse:collapse;background:#111c27;border-radius:10px;overflow:hidden}th,td{padding:13px 14px;text-align:left;border-bottom:1px solid #263c50}th{color:#aabdd0;font-size:.78em;letter-spacing:.06em;text-transform:uppercase}tr:last-child td{border:0}.team1 td:first-child{border-left:6px solid #49a5ff}.team2 td:first-child{border-left:6px solid #ff5d67}.offline{opacity:.48}.dead{color:#ffb0b7}#empty{color:#aebdca;padding:24px 0}@media(max-width:600px){body{padding:14px;font-size:15px}th,td{padding:9px 7px}}</style></head><body><h1>Leaderboard</h1><p id="subtitle">Connecting...</p><table><thead><tr><th>Player</th><th>Team</th><th>Kills</th><th>Hits</th><th>Shots</th><th>Accuracy</th></tr></thead><tbody id="rows"></tbody></table><p id="empty"></p>
            <script>const rows=document.querySelector('#rows'),subtitle=document.querySelector('#subtitle'),empty=document.querySelector('#empty');function pct(p){return p.accuracy==null?'-':Math.round(p.accuracy)+'%'}function title(s){if(s.state==='countdown')return `Starting in ${Math.ceil(s.startInMs/1000)} seconds`;if(s.state==='running')return s.remainingMs?`Game running - ${Math.ceil(s.remainingMs/1000)} seconds remaining`:'Game running';if(s.state==='finished')return 'Final scores';return `Lobby - ${s.clockReady||0}/${s.playerCount||0} clocks synchronized`};function draw(s){document.title=(s.eventTitle||'LAZER GUN RUN')+' · Leaderboard';subtitle.textContent=(s.eventTitle||'LAZER GUN RUN')+' · '+title(s);const players=[...(s.players||[])].filter(p=>!p.benched).sort((a,b)=>b.kills-a.kills||b.accuracy-a.accuracy||b.hits-a.hits||a.id-b.id);rows.replaceChildren();empty.textContent=players.length?'':'Waiting for players to join...';for(const p of players){const tr=document.createElement('tr');tr.className=`team${p.team}${p.connected?'':' offline'}`;const name=document.createElement('td');name.textContent=p.name+(p.awaitingRespawn?' - RESPAWN':'');if(p.awaitingRespawn)name.className='dead';const vals=[p.team,p.kills,p.hits,p.shots,pct(p)];tr.append(name,...vals.map(v=>{const td=document.createElement('td');td.textContent=v;return td}));rows.append(tr)}}async function refresh(){try{const r=await fetch('/api/state',{cache:'no-store'});if(!r.ok)throw Error();draw(await r.json())}catch(e){subtitle.textContent='Disconnected from laptop host.'}setTimeout(refresh,200)}refresh();</script></body></html>
            """;
}
