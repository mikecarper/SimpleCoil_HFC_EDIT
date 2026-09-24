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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final int NETWORK_VERSION = 19;
    private static final String MESSAGE_PREFIX = "SimpleCoil:";
    private static final String TCP_PREFIX = MESSAGE_PREFIX + NETWORK_VERSION;
    private static final String TCP_JSON_PREFIX = TCP_PREFIX + "JSON";
    private static final String TCP_MESSAGE_PREFIX = TCP_PREFIX + "MESG";
    private static final String JSON_GPS_CLOCK = "gpsclock";
    private static final String JSON_GPS_START_TIME = "gpsstarttime";
    // Must match the Android client's GameClock.SAMPLES_PER_SYNC. The client
    // selects the lowest-delay measurement and rejects RTTs above 80 ms, which
    // bounds its network timing uncertainty to less than 50 ms.
    private static final int CLOCK_SAMPLES_REQUIRED = 12;

    private static final int MAX_PLAYERS = 32;
    private static final int STATE_PACKET_BYTES = 32 + MAX_PLAYERS * 40;
    private static final int UDP_RECEIVE_BYTES = 1_472;
    private static final int STATE_PACKET_MAGIC = 0x53434F49;
    private static final int STATE_PACKET_FORMAT = 1;
    private static final int COMBAT_PACKET_BYTES = 32;
    private static final int COMBAT_PACKET_MAGIC = 0x53434F43;
    private static final int COMBAT_PACKET_FORMAT = 1;
    private static final int MAX_GRENADE_IDS = 16;
    private static final int MAX_SCOREBOARD_VALUE = Integer.MAX_VALUE / MAX_PLAYERS;
    private static final long CLOCK_MAX_VALUE = Long.MAX_VALUE / 4;
    private static final long GPS_PUBLISH_INTERVAL_MS = 250;
    private static final int GPS_FULL_UPDATE_EVERY_TICKS = 20;
    private static final long GPS_STALE_AFTER_MS = 15_000;
    private static final long GPS_CLOCK_MAX_AGE_MS = 60_000;
    private static final long EARLIEST_GPS_UTC_MS = 1_577_836_800_000L; // 2020-01-01
    private static final long HIT_ENEMY_GPS_REVEAL_MS = 10_000;
    private static final long LASER_LIFETIME_MS = 1_500;
    private static final long JOIN_ASSIGNMENT_TIMEOUT_MS = 10_000;
    private static final long BALANCED_QR_CHECK_IN_TIMEOUT_MS = 90_000;
    private static final long NEXT_GAME_WAIT_MS = 30_000;
    // Match the dedicated phone host's practical heartbeat window. A Wi-Fi
    // disappearance must not leave a ghost player occupying an ID or marker.
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
    private final Object stateLock = new Object();
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private volatile Map<Integer, Integer> balancedTeams = Collections.emptyMap();
    private final Set<Integer> balancedCheckedIn = new HashSet<>();
    // Relative to clockOriginNanos; zero when no QR lobby is waiting.
    private long balancedCheckInDeadline;
    private final Random balanceRandom = new Random();
    private final Map<Integer, ClientConnection> activeClients = new HashMap<>();
    private final Map<InetAddress, PendingAssignment> pendingAssignments = new HashMap<>();
    private final Map<Integer, Integer> grenadeOwners = new HashMap<>();
    private final Deque<LaserEvent> lasers = new ArrayDeque<>();
    private final StateRow[] gossipState = new StateRow[MAX_PLAYERS + 1];
    private final long[] lastGossipPacketSequence = new long[MAX_PLAYERS + 1];
    // A compact combat event can beat its sender's first full state row to the
    // laptop. Retain only the latest fixed-width event per player so the next
    // one-second authority tick can still repair phones that missed the burst.
    private final long[] observedCombatSequence = new long[MAX_PLAYERS + 1];
    private final int[] observedCombatType = new int[MAX_PLAYERS + 1];
    private final int[] observedCombatTarget = new int[MAX_PLAYERS + 1];
    private long authorityTickSequence;
    // Recipient player ID -> the enemy player IDs that a confirmed IR hit may
    // reveal. These maps are protected by stateLock. Every active teammate of
    // each participant shares the reveal, but no other enemy is exposed.
    private final Map<Integer, Map<Integer, Long>> enemyGpsRevealUntil = new HashMap<>();
    private final Set<Integer> gpsVisibilityRefreshPlayers = new HashSet<>();
    private final AtomicInteger nextClientID = new AtomicInteger();
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
    private Thread tcpAcceptThread;
    private Thread udpThread;

    private RoundState roundState = RoundState.LOBBY;
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

    private LaptopHost(HostConfig config) {
        this.config = config;
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
        dashboard.createContext("/api/state", this::handleStateApi);
        dashboard.createContext("/api/start", this::handleStartApi);
        dashboard.createContext("/api/end", this::handleEndApi);
        dashboard.createContext("/api/respawn", this::handleRespawnApi);
        dashboard.start();

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
        if (config.takeover)
            scheduler.scheduleAtFixedRate(this::broadcastHostTakeover, 0, 1, TimeUnit.SECONDS);
    }

    private void stop() {
        if (stopping)
            return;
        stopping = true;
        closeQuietly(tcpServer);
        if (udpServer != null)
            udpServer.close();
        if (dashboard != null)
            dashboard.stop(0);

        List<ClientConnection> clients;
        synchronized (stateLock) {
            clients = new ArrayList<>(activeClients.values());
            activeClients.clear();
        }
        for (ClientConnection client : clients)
            client.close();
        scheduler.shutdownNow();
        clientExecutor.shutdownNow();
        dashboardExecutor.shutdownNow();
    }

    private void printStartupInstructions() {
        System.out.println("SimpleCoil laptop host is ready.");
        System.out.println("  TCP lobby port: " + config.tcpPort + "  |  UDP discovery port: " + config.udpPort);
        System.out.println("  Dashboard: http://" + config.dashboardBind + ":" + config.dashboardPort + "/");
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
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                ClientConnection client = new ClientConnection(socket, nextClientID.incrementAndGet());
                clientExecutor.execute(client);
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
                if (CombatPacket.looksLike(packet.getData(), packet.getOffset(),
                        packet.getLength())) {
                    handleCombatEvent(packet.getAddress(), packet.getData(), packet.getOffset(),
                            packet.getLength());
                } else if (StatePacket.looksLike(packet.getData(), packet.getOffset(), packet.getLength())) {
                    handleStateGossip(packet.getAddress(), packet.getData(), packet.getOffset(),
                            packet.getLength());
                } else {
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                            StandardCharsets.UTF_8);
                    handleUdpDiscovery(packet.getAddress(), message);
                }
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
        if (source == null || message == null || !message.startsWith(MESSAGE_PREFIX + "JOIN"))
            return;
        String request = message.substring((MESSAGE_PREFIX + "JOIN").length());
        if (request.length() < 3 || !request.startsWith(String.valueOf(NETWORK_VERSION))) {
            sendUdp(MSG_VERSION_ERROR, source);
            return;
        }
        int requestedID;
        try {
            requestedID = Integer.parseInt(request.substring(String.valueOf(NETWORK_VERSION).length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (!validPlayerID(requestedID))
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

            // A discovery datagram can be retransmitted after its TCP
            // registration already completed. Keep that player's original
            // identity instead of treating the retry as a new join and moving
            // it into an unrelated free slot on the same team.
            Player requestedPlayer = players.get(requestedID);
            if (requestedPlayer != null && source.equals(requestedPlayer.address))
                return requestedID;
            if (isRoundActiveLocked() && (config.balanced || !config.allowLateJoin))
                return -1;

            int requestedTeam = teamFor(requestedID);
            for (int candidate = 1; candidate <= MAX_PLAYERS; candidate++) {
                if (config.gameMode != GameMode.FFA && !config.balanced
                        && teamFor(candidate) != requestedTeam)
                    continue;
                Player player = players.get(candidate);
                boolean sameKnownEndpoint = player != null && source.equals(player.address);
                if (player != null && (player.connected || (isRoundActiveLocked() && !sameKnownEndpoint)))
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
            if (player == null || owned == null || !source.equals(player.address)
                    || decoded.packetSequence <= lastGossipPacketSequence[decoded.senderID])
                return;
            lastGossipPacketSequence[decoded.senderID] = decoded.packetSequence;
            StateRow current = gossipState[decoded.senderID];
            if (current == null || owned.ownerSequence > current.ownerSequence) {
                if (owned.eventSequence > observedCombatSequence[decoded.senderID]) {
                    observedCombatSequence[decoded.senderID] = owned.eventSequence;
                    observedCombatType[decoded.senderID] = owned.eventType;
                    observedCombatTarget[decoded.senderID] = owned.eventTargetID;
                }
                gossipState[decoded.senderID] = mergeObservedCombatLocked(
                        decoded.senderID, owned);
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
            if (player == null || player.address == null || !source.equals(player.address)
                    || decoded.eventSequence <= observedCombatSequence[decoded.senderID])
                return;
            observedCombatSequence[decoded.senderID] = decoded.eventSequence;
            observedCombatType[decoded.senderID] = decoded.eventType;
            observedCombatTarget[decoded.senderID] = decoded.targetID;
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
                row.gpsAgeSeconds, row.latitude, row.longitude);
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
            payload = StatePacket.encode(NETWORK_VERSION, token, ++authorityTickSequence,
                    config.gameMode.wireValue, gossipState);
            // Broadcast lets every nearby phone hear the same tick. Targeted
            // copies retain normal Wi-Fi unicast acknowledgements and make a
            // congested access point's broadcast loss much less noticeable.
            for (Player player : players.values()) {
                if (player.connected && player.address != null
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
                socket.send(new DatagramPacket(payload, payload.length, address, config.udpPort));
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
                        socket.send(packet);
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
    private void broadcastHostTakeover() {
        if (stopping)
            return;
        String announcement = MESSAGE_PREFIX + MSG_HOST_TAKEOVER + ":" + NETWORK_VERSION;
        byte[] payload = announcement.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (InetAddress address : broadcastAddresses()) {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, address, config.udpPort);
                socket.send(packet);
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
            client.send(MSG_VERSION_ERROR);
            client.close();
        }
    }

    private void handleJson(ClientConnection client, Map<String, Object> json) {
        if (json.containsKey("clocksync") || json.containsKey("clockready")) {
            handleClockSync(client, json);
            return;
        }
        if (json.containsKey("gpslongitude")) {
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
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("clocksync", sentAt);
            reply.put("clockreceive", received);
            reply.put("clocksend", elapsedMillis());
            synchronized (stateLock) {
                if (json.containsKey(JSON_GPS_CLOCK))
                    recordGpsClockLocked(longValue(json.get(JSON_GPS_CLOCK), -1), received);
                long gpsTime = gpsTimeAtElapsedLocked(elapsedMillis());
                if (validGpsUtcTime(gpsTime))
                    reply.put(JSON_GPS_CLOCK, gpsTime);
                client.clockSamples = Math.min(CLOCK_SAMPLES_REQUIRED, client.clockSamples + 1);
            }
            client.send(jsonFrame(reply));
        } else if (Boolean.TRUE.equals(json.get("clockready"))
                && client.clockSamples >= CLOCK_SAMPLES_REQUIRED) {
            client.clockReady = true;
        }
    }

    private void handleGps(ClientConnection client, Map<String, Object> json) {
        double longitude = doubleValue(json.get("gpslongitude"), Double.NaN);
        double latitude = doubleValue(json.get("gpslatitude"), Double.NaN);
        if (!validCoordinates(longitude, latitude))
            return;
        synchronized (stateLock) {
            Player player = playerForClientLocked(client);
            if (player == null)
                return;
            player.longitude = longitude;
            player.latitude = latitude;
            player.lastGpsAt = elapsedMillis();
            player.gpsDirty = true;
            player.addTrail(longitude, latitude, player.lastGpsAt);
        }
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
            if ("shot".equals(event)) {
                int count = intValue(json.get("count"), 0);
                if (count >= 1 && count <= 255)
                    reporter.shots = addScore(reporter.shots, count);
            } else if ("hit".equals(event)) {
                int attackerID = intValue(json.get("attacker"), 0);
                Player attacker = players.get(attackerID);
                if (attacker == null || attacker == reporter || !attacker.connected
                        || !areOpponents(attacker.id, reporter.id))
                    return;
                attacker.hits = addScore(attacker.hits, 1);
                addLaserLocked(attacker, reporter, "hit");
                grantEnemyGpsRevealLocked(attacker.id, reporter.id);
            }
        }
    }

    private void handlePlayerSettings(ClientConnection client, Map<String, Object> json) {
        boolean changed = false;
        synchronized (stateLock) {
            Player player = playerForClientLocked(client);
            if (player == null)
                return;
            if (roundState != RoundState.LOBBY)
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
            if (player == null || roundState != RoundState.LOBBY)
                return;
            player.name = name;
        }
        broadcastRoster();
        broadcastPlayerData();
    }

    private void handleBalancedCheckIn(ClientConnection client, Map<String, Object> json) {
        boolean readyToStart;
        synchronized (stateLock) {
            if (!config.balancedQr || !Boolean.TRUE.equals(json.get("balancedcheckin"))
                    || roundState != RoundState.LOBBY || !balancedTeams.containsKey(client.playerID)
                    || playerForClientLocked(client) == null)
                return;
            balancedCheckedIn.add(client.playerID);
            readyToStart = balancedCheckedIn.containsAll(balancedTeams.keySet());
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
        int priorKills = intValue(json.get("priorkills"), 0);
        int priorDeaths = intValue(json.get("priordeaths"), 0);
        if (!validPlayerID(requestedID) || name == null)
            return;
        if (priorKills < 0 || priorKills > 100_000 || priorDeaths < 0 || priorDeaths > 100_000)
            return;

        ClientConnection replaced = null;
        boolean accepted = false;
        synchronized (stateLock) {
            if (client.playerID != 0 && client.playerID != requestedID)
                return;
            if (roundState == RoundState.FINISHED && activeClients.isEmpty())
                resetForNewLobbyLocked();
            removeExpiredAssignmentsLocked(elapsedMillis());

            PendingAssignment assignment = pendingAssignments.get(client.address());
            if (assignment != null && assignment.playerID != requestedID) {
                client.close();
                return;
            }
            if (assignment != null)
                pendingAssignments.remove(client.address());

            Player existing = players.get(requestedID);
            // Normal phones discover over UDP first, but a TCP client can be
            // pointed directly at the laptop. Honor --no-late-join for that
            // path too, while retaining reconnects for players already in the
            // current round.
            if (isRoundActiveLocked() && (existing == null && !config.allowLateJoin
                    || config.balanced && !balancedTeams.containsKey(requestedID))) {
                client.close();
                return;
            }
            if (existing != null && existing.connected && existing.connection != client) {
                if (!rejoin || !client.address().equals(existing.address)) {
                    client.close();
                    return;
                }
                replaced = existing.connection;
            }
            for (Player player : players.values()) {
                if (player.id != requestedID && player.connected && client.address().equals(player.address)) {
                    client.close();
                    return;
                }
            }
            if (existing != null && !existing.connected && isRoundActiveLocked()
                    && !client.address().equals(existing.address) && !rejoin) {
                client.close();
                return;
            }
            if (existing == null) {
                existing = new Player(requestedID, name, client.address(), defaultSettings(requestedID));
                players.put(requestedID, existing);
            }
            existing.name = name;
            existing.priorKills = priorKills;
            existing.priorDeaths = priorDeaths;
            existing.address = client.address();
            existing.connected = true;
            existing.connection = client;
            existing.awaitingRespawn = false;
            client.playerID = requestedID;
            activeClients.put(requestedID, client);
            if (config.balanced && roundState == RoundState.LOBBY && replaced == null) {
                balancedTeams = Collections.emptyMap();
                balancedCheckedIn.clear();
                balancedCheckInDeadline = 0;
            }
            accepted = true;
        }
        if (replaced != null && replaced != client)
            replaced.close();
        if (accepted) {
            broadcastRoster();
            broadcastGrenadePairings();
        }
    }

    private void handleMessage(ClientConnection client, String message) {
        if (message == null || client.playerID <= 0)
            return;
        if (MSG_LEAVE.equals(message) || MSG_QUIT.equals(message)) {
            client.close();
            return;
        }
        if (message.startsWith(MSG_ELIMINATED)) {
            processElimination(client, message.substring(MSG_ELIMINATED.length()));
            return;
        }
        if (MSG_RESPAWN_REQUEST.equals(message)) {
            grantRespawn(client.playerID);
            return;
        }
        if (MSG_RESPAWN_COMPLETE.equals(message)) {
            synchronized (stateLock) {
                Player player = playerForClientLocked(client);
                if (player != null)
                    player.awaitingRespawn = false;
            }
            return;
        }
        if (MSG_PLAYER_DATA_REQUEST.equals(message)) {
            client.send(playerDataFrame());
            return;
        }
        if (MSG_START_GAME.equals(message)) {
            if (config.balanced)
                return; // The laptop operator fixes the roster being balanced.
            StartResult result = startRound();
            if (!result.ok && result.clockWaiting)
                client.send(messageFrame(MSG_CLOCK_WAITING));
            return;
        }
        if (MSG_END_GAME.equals(message)) {
            if (config.tournament)
                client.close();
            else
                endRound("ended by a player");
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
            if (roundState != RoundState.RUNNING || !validPlayerID(attackerID))
                return;
            Player victim = playerForClientLocked(victimConnection);
            Player attacker = players.get(attackerID);
            if (victim == null || attacker == null || attacker == victim || !attacker.connected
                    || !areOpponents(attacker.id, victim.id) || victim.awaitingRespawn)
                return;
            victim.awaitingRespawn = true;
            victim.eliminated = true;
            victim.deaths = addScore(victim.deaths, 1);
            attacker.kills = addScore(attacker.kills, 1);
            addLaserLocked(attacker, victim, "kill");
            // A death ends the short confirmed-hit map reveal immediately so
            // a respawn cannot inherit an opponent's last known position.
            clearEnemyGpsRevealsForPlayerLocked(victim.id);
            clearEnemyGpsRevealsForPlayerLocked(attacker.id);
            scorerConnection = attacker.connection;
            if (config.gameMode != GameMode.FFA) {
                scorerTeam = new ArrayList<>();
                int scoringTeam = teamFor(attacker.id);
                for (Player player : players.values()) {
                    if (player.connected && player.connection != null && teamFor(player.id) == scoringTeam)
                        scorerTeam.add(player.connection);
                }
            }
            reachedScoreLimit = hasReachedScoreLimitLocked(attacker);
        }
        if (scorerConnection != null)
            scorerConnection.send(messageFrame(MSG_ELIMINATED + victimConnection.playerID));
        for (ClientConnection teammate : scorerTeam) {
            if (teammate != scorerConnection)
                teammate.send(messageFrame(MSG_TEAM_ELIMINATED));
        }
        broadcastPlayerData();
        if (reachedScoreLimit)
            endRound("score limit reached");
    }

    private StartResult startRound() {
        List<ClientConnection> recipients;
        String startFrame;
        String token;
        boolean publishedAssignment = false;
        synchronized (stateLock) {
            updateRoundStateLocked();
            long cooldown = Math.max(0, nextRoundStartAllowedAt - elapsedMillis());
            if (cooldown > 0)
                return StartResult.error("Next game can start in "
                        + ((cooldown + 999) / 1_000) + " seconds.", false);
            if (roundState != RoundState.LOBBY)
                return StartResult.error("A game is already running.", false);
            recipients = connectedClientsLocked();
            if (recipients.size() < 2)
                return StartResult.error("At least two phones must join first.", false);
            if (config.boss && !activeClients.containsKey(1))
                return StartResult.error("Boss Mode requires Player 1 and at least one hunter.", false);
            for (ClientConnection client : recipients) {
                if (!client.clockReady)
                    return StartResult.error("Waiting for phone clock synchronization.", true);
            }
            if (config.balanced && balancedTeams.isEmpty()) {
                balancedTeams = computeBalancedTeamsLocked();
                balancedCheckedIn.clear();
                balancedCheckInDeadline = config.balancedQr
                        ? elapsedMillis() + BALANCED_QR_CHECK_IN_TIMEOUT_MS : 0;
                publishedAssignment = true;
            }
        }
        if (publishedAssignment)
            broadcastRoster();
        synchronized (stateLock) {
            updateRoundStateLocked();
            long cooldown = Math.max(0, nextRoundStartAllowedAt - elapsedMillis());
            if (cooldown > 0)
                return StartResult.error("Next game can start in "
                        + ((cooldown + 999) / 1_000) + " seconds.", false);
            if (roundState != RoundState.LOBBY)
                return StartResult.error("A game is already running.", false);
            recipients = connectedClientsLocked();
            if (recipients.size() < 2)
                return StartResult.error("At least two phones must join first.", false);
            if (config.boss && !activeClients.containsKey(1))
                return StartResult.error("Boss Mode requires Player 1 and at least one hunter.", false);
            for (ClientConnection client : recipients) {
                if (!client.clockReady)
                    return StartResult.error("Waiting for phone clock synchronization.", true);
            }
            if (config.balanced && balancedTeams.size() != recipients.size())
                return StartResult.error("The lobby roster changed. Press Start again.", false);
            if (config.balancedQr && !balancedCheckedIn.containsAll(balancedTeams.keySet())
                    && (balancedCheckInDeadline == 0 || elapsedMillis() < balancedCheckInDeadline))
                return StartResult.waitingForQr();
            roundID++;
            roundToken = UUID.randomUUID().toString();
            token = roundToken;
            authorityTickSequence = 0;
            for (int playerID = 0; playerID <= MAX_PLAYERS; playerID++) {
                gossipState[playerID] = null;
                lastGossipPacketSequence[playerID] = 0;
                observedCombatSequence[playerID] = 0;
                observedCombatType[playerID] = 0;
                observedCombatTarget[playerID] = 0;
            }
            roundStartAt = elapsedMillis() + (config.balanced ? 10 : config.startDelaySeconds) * 1_000L;
            roundDuration = config.durationMinutes * 60_000L;
            roundEndAt = roundDuration == 0 ? 0 : roundStartAt + roundDuration;
            roundState = RoundState.COUNTDOWN;
            if (config.boss)
                bossHunterCount = Math.max(1, recipients.size() - 1);
            balancedCheckInDeadline = 0;
            for (Player player : players.values()) {
                player.kills = 0;
                player.deaths = 0;
                player.shots = 0;
                player.hits = 0;
                player.awaitingRespawn = false;
                player.eliminated = false;
            }
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("roundid", roundID);
            start.put("roundtoken", roundToken);
            start.put("gamestart", roundStartAt);
            start.put("gameduration", roundDuration);
            long gpsStartTime = gpsTimeAtElapsedLocked(roundStartAt);
            if (validGpsUtcTime(gpsStartTime))
                start.put(JSON_GPS_START_TIME, gpsStartTime);
            startFrame = jsonFrame(start);
        }
        for (ClientConnection client : recipients)
            client.send(startFrame);
        // Refresh the lobby immediately after the start frame so a phone joining
        // near the edge sees dedicated-host state and the shared deadline.
        broadcastRoster();
        broadcastGameInvite(token);
        return StartResult.ok();
    }

    /** Caller holds stateLock. Dynamic programming keeps a 32-player lobby practical. */
    private Map<Integer, Integer> computeBalancedTeamsLocked() {
        List<Player> candidates = new ArrayList<>();
        for (Player player : players.values()) {
            if (player.connected)
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

    private void endRound(String reason) {
        List<ClientConnection> recipients;
        synchronized (stateLock) {
            if (roundState != RoundState.COUNTDOWN && roundState != RoundState.RUNNING)
                return;
            roundState = RoundState.FINISHED;
            nextRoundStartAllowedAt = Math.max(nextRoundStartAllowedAt,
                    elapsedMillis() + NEXT_GAME_WAIT_MS);
            recipients = connectedClientsLocked();
        }
        System.out.println("Game finished: " + reason);
        for (ClientConnection client : recipients) {
            client.send(messageFrame(MSG_END_GAME));
            client.close();
        }
    }

    private boolean grantRespawn(int playerID) {
        ClientConnection recipient = null;
        synchronized (stateLock) {
            updateRoundStateLocked();
            if (roundState != RoundState.RUNNING)
                return false;
            Player player = players.get(playerID);
            if (player == null || !player.connected || !player.awaitingRespawn || player.connection == null)
                return false;
            player.awaitingRespawn = false;
            player.eliminated = false;
            recipient = player.connection;
        }
        recipient.send(messageFrame(MSG_RESPAWN_GRANTED));
        broadcastPlayerData();
        return true;
    }

    private void advanceRoundClock() {
        boolean timeExpired = false;
        boolean qrCheckInTimedOut = false;
        synchronized (stateLock) {
            updateRoundStateLocked();
            timeExpired = roundState == RoundState.RUNNING && roundEndAt > 0 && elapsedMillis() >= roundEndAt;
            qrCheckInTimedOut = config.balancedQr && roundState == RoundState.LOBBY
                    && !balancedTeams.isEmpty() && balancedCheckInDeadline > 0
                    && elapsedMillis() >= balancedCheckInDeadline;
        }
        if (timeExpired)
            endRound("time limit reached");
        if (qrCheckInTimedOut)
            startRound();
    }

    private void updateRoundStateLocked() {
        if (roundState == RoundState.COUNTDOWN && elapsedMillis() >= roundStartAt)
            roundState = RoundState.RUNNING;
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
        for (ClientConnection client : stale)
            client.close();
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
                if (!player.connected || !validCoordinates(player.longitude, player.latitude)
                        || now - player.lastGpsAt > GPS_STALE_AFTER_MS)
                    continue;
                Position position = new Position(player.id, teamFor(player.id), player.longitude, player.latitude);
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
        for (ClientConnection client : recipients) {
            int recipientTeam = teamFor(client.playerID);
            final Set<Integer> revealedEnemies;
            final boolean recipientFullUpdate;
            synchronized (stateLock) {
                Map<Integer, Long> reveals = enemyGpsRevealUntil.get(client.playerID);
                revealedEnemies = reveals == null ? Collections.emptySet() : new HashSet<>(reveals.keySet());
                recipientFullUpdate = fullUpdate || gpsVisibilityRefreshPlayers.remove(client.playerID);
            }
            List<Position> source = recipientFullUpdate ? fullSnapshot : changedPositions;
            List<Object> updates = new ArrayList<>();
            for (Position position : source) {
                if (config.gpsMode == GpsMode.TEAMMATE && config.gameMode != GameMode.FFA
                        && position.team != recipientTeam && !revealedEnemies.contains(position.playerID))
                    continue;
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("playerID", position.playerID);
                update.put("team", position.team);
                update.put("gpslongitude", position.longitude);
                update.put("gpslatitude", position.latitude);
                updates.add(update);
            }
            if (updates.isEmpty() && !recipientFullUpdate)
                continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("gpsupdate", updates);
            if (recipientFullUpdate)
                payload.put("gpsfullupdate", true);
            client.send(jsonFrame(payload));
        }
    }

    private void broadcastRoster() {
        for (ClientConnection client : connectedClients())
            client.send(rosterFrame(client.playerID));
    }

    private void broadcastSettings() {
        String frame = settingsFrame();
        for (ClientConnection client : connectedClients())
            client.send(frame);
    }

    private void broadcastGrenadePairings() {
        String frame = grenadePairingsFrame();
        for (ClientConnection client : connectedClients())
            client.send(frame);
    }

    private void broadcastPlayerData() {
        String frame = playerDataFrame();
        for (ClientConnection client : connectedClients())
            client.send(frame);
    }

    private String rosterFrame(int targetPlayerID) {
        synchronized (stateLock) {
            updateRoundStateLocked();
            List<Object> roster = new ArrayList<>();
            for (Player player : players.values()) {
                if (!player.connected)
                    continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playername", player.name);
                item.put("playerID", player.id);
                item.put("playerIP", player.address.getHostAddress());
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
            game.put("limits", limits);
            game.put("gamemode", config.gameMode.wireValue);
            game.put("balancedrandom", config.balanced);
            game.put("balancedqr", config.balancedQr);
            game.put("bossmode", config.boss);
            game.put("dedicatedserver", true);
            game.put("gamestate", isRoundActiveLocked() ? 1 : 0);
            if (config.useGps)
                game.put("usegps", config.gpsMode.wireValue);
            game.put("allowplayersettings", !config.onlyServerSettings && !config.tournament);
            game.put("playersettings", settingsArrayLocked());
            game.put("onlyserversettings", config.onlyServerSettings || config.tournament);
            game.put("tournamentmode", config.tournament);
            if (isRoundActiveLocked()) {
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
            return jsonFrame(game);
        }
    }

    private String settingsFrame() {
        synchronized (stateLock) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("allowplayersettings", !config.onlyServerSettings && !config.tournament);
            payload.put("playersettings", settingsArrayLocked());
            payload.put("onlyserversettings", config.onlyServerSettings || config.tournament);
            payload.put("tournamentmode", config.tournament);
            payload.put("bossmode", config.boss);
            return jsonFrame(payload);
        }
    }

    private List<Object> settingsArrayLocked() {
        List<Object> settings = new ArrayList<>();
        int hunterCount = config.boss
                ? (isRoundActiveLocked() ? bossHunterCount : Math.max(0, activeClients.size() - 1)) : 0;
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
        if (config.scoreLimit <= 0)
            return false;
        if (config.gameMode == GameMode.FFA)
            return scorer.kills >= config.scoreLimit;
        return teamKillsLocked(teamFor(scorer.id)) >= config.scoreLimit;
    }

    private int teamKillsLocked(int team) {
        int total = 0;
        for (Player player : players.values()) {
            if (teamFor(player.id) == team)
                total = addScore(total, player.kills);
        }
        return total;
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
        lasers.addLast(new LaserEvent(shooter.id, target.id, kind, now,
                shooter.longitude, shooter.latitude, target.longitude, target.latitude));
        while (lasers.size() > 100)
            lasers.removeFirst();
    }

    private Player playerForClientLocked(ClientConnection client) {
        Player player = players.get(client.playerID);
        return player != null && player.connection == client && player.connected ? player : null;
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

    private int secondsRemainingLocked() {
        if (roundEndAt == 0)
            return 0;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE,
                (roundEndAt - elapsedMillis() + 999) / 1_000));
    }

    private void resetForNewLobbyLocked() {
        players.clear();
        activeClients.clear();
        pendingAssignments.clear();
        grenadeOwners.clear();
        lasers.clear();
        enemyGpsRevealUntil.clear();
        gpsVisibilityRefreshPlayers.clear();
        balancedTeams = Collections.emptyMap();
        balancedCheckedIn.clear();
        balancedCheckInDeadline = 0;
        roundState = RoundState.LOBBY;
        roundToken = null;
        roundStartAt = 0;
        roundDuration = 0;
        roundEndAt = 0;
        bossHunterCount = 0;
        authorityTickSequence = 0;
        for (int playerID = 0; playerID <= MAX_PLAYERS; playerID++) {
            gossipState[playerID] = null;
            lastGossipPacketSequence[playerID] = 0;
            observedCombatSequence[playerID] = 0;
            observedCombatType[playerID] = 0;
            observedCombatTarget[playerID] = 0;
        }
    }

    private void disconnect(ClientConnection client) {
        boolean changed = false;
        synchronized (stateLock) {
            Player player = players.get(client.playerID);
            if (player != null && player.connection == client) {
                activeClients.remove(player.id);
                player.connection = null;
                player.connected = false;
                player.awaitingRespawn = false;
                clearEnemyGpsRevealsForPlayerLocked(player.id);
                if (!isRoundActiveLocked() && roundState != RoundState.FINISHED) {
                    players.remove(player.id);
                    grenadeOwners.entrySet().removeIf(entry -> entry.getValue() == player.id);
                    balancedTeams = Collections.emptyMap();
                    balancedCheckedIn.clear();
                    balancedCheckInDeadline = 0;
                }
                changed = true;
            }
        }
        if (changed && !stopping) {
            broadcastRoster();
            broadcastPlayerData();
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
        if (config.balanced) {
            Integer assigned = balancedTeams.get(playerID);
            if (assigned != null)
                return assigned;
        }
        if (config.gameMode == GameMode.FFA)
            return playerID;
        if (config.gameMode == GameMode.FOUR_TEAMS)
            return ((playerID - 1) / (MAX_PLAYERS / 4)) + 1;
        return playerID <= MAX_PLAYERS / 2 ? 1 : 2;
    }

    private boolean areOpponents(int firstID, int secondID) {
        return config.gameMode == GameMode.FFA || teamFor(firstID) != teamFor(secondID);
    }

    private Settings defaultSettings(int playerID) {
        Settings settings = config.tournament ? Settings.tournament() : new Settings();
        if (config.boss)
            settings.applyBoss(playerID == 1, Math.max(0, activeClients.size() - 1));
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
        // Match the Android app's policy: (0, 0), a zero latitude, or a zero
        // longitude is an unusable GPS fix for a real game field.
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude != 0.0 && latitude != 0.0
                && longitude >= -180.0 && longitude <= 180.0
                && latitude >= -90.0 && latitude <= 90.0;
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
        private final Object outputLock = new Object();
        private volatile boolean closed;
        private volatile int playerID;
        private volatile boolean clockReady;
        private volatile int clockSamples;
        private volatile long lastReceivedAt;

        ClientConnection(Socket socket, int clientID) throws IOException {
            this.socket = socket;
            this.clientID = clientID;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            lastReceivedAt = elapsedMillis();
        }

        InetAddress address() {
            return socket.getInetAddress();
        }

        @Override
        public void run() {
            try {
                while (!closed && !stopping)
                    handleFrame(this, input.readUTF());
            } catch (IOException ignored) {
                // A phone leaving Wi-Fi or using Leave Game is an ordinary
                // disconnect; the roster update below is the useful result.
            } finally {
                close();
                disconnect(this);
            }
        }

        boolean send(String message) {
            if (message == null || closed)
                return false;
            synchronized (outputLock) {
                if (closed)
                    return false;
                try {
                    output.writeUTF(message);
                    output.flush();
                    return true;
                } catch (IOException e) {
                    close();
                    return false;
                }
            }
        }

        void close() {
            if (closed)
                return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
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

        Position(int playerID, int team, double longitude, double latitude) {
            this.playerID = playerID;
            this.team = team;
            this.longitude = longitude;
            this.latitude = latitude;
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

    private static final class Player {
        final int id;
        String name;
        InetAddress address;
        ClientConnection connection;
        boolean connected;
        Settings settings;
        double longitude = Double.NaN;
        double latitude = Double.NaN;
        long lastGpsAt;
        boolean gpsDirty;
        int kills;
        int deaths;
        int priorKills;
        int priorDeaths;
        int shots;
        int hits;
        boolean awaitingRespawn;
        boolean eliminated;
        final Deque<GeoPoint> trail = new ArrayDeque<>();

        Player(int id, String name, InetAddress address, Settings settings) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.settings = settings;
        }

        int strength() {
            long observations = (long) priorKills + priorDeaths;
            return (int) (100 + 100L * (priorKills - (long) priorDeaths) / (observations + 10));
        }

        void addTrail(double longitude, double latitude, long now) {
            GeoPoint newest = trail.peekLast();
            if (newest == null || newest.longitude != longitude || newest.latitude != latitude)
                trail.addLast(new GeoPoint(longitude, latitude, now));
            while (trail.size() > 30)
                trail.removeFirst();
        }
    }

    /** Protocol-19 fixed-size state gossip and authority tick. */
    private static final class StatePacket {
        static final int FLAG_PRESENT = 1;
        static final int FLAG_LEFT = 1 << 1;
        static final int FLAG_GPS_VALID = 1 << 2;

        final int networkVersion;
        final UUID roundToken;
        final long packetSequence;
        final int senderID;
        final int gameMode;
        final StateRow[] rows;

        StatePacket(int networkVersion, UUID roundToken, long packetSequence, int senderID,
                    int gameMode, StateRow[] rows) {
            this.networkVersion = networkVersion;
            this.roundToken = roundToken;
            this.packetSequence = packetSequence;
            this.senderID = senderID;
            this.gameMode = gameMode;
            this.rows = rows;
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
                buffer.getShort();
                long ownerSequence = u32(buffer.getInt());
                long eventSequence = u32(buffer.getInt());
                int score = buffer.getInt();
                int deaths = buffer.getInt();
                int health = u16(buffer.getShort());
                int shield = u16(buffer.getShort());
                int shotsRemaining = u16(buffer.getShort());
                int gpsAgeSeconds = u16(buffer.getShort());
                int latitude = buffer.getInt();
                int longitude = buffer.getInt();
                if (playerID != slot)
                    return null;
                if ((flags & FLAG_PRESENT) == 0)
                    continue;
                if ((flags & ~(FLAG_PRESENT | FLAG_LEFT | FLAG_GPS_VALID)) != 0
                        || ownerSequence <= 0 || gameState > 2 || grenadeID >= MAX_GRENADE_IDS
                        || eventType > 7 || eventTargetID > MAX_PLAYERS || score < 0
                        || score > MAX_SCOREBOARD_VALUE || deaths < 0
                        || deaths > MAX_SCOREBOARD_VALUE)
                    return null;
                if ((flags & FLAG_GPS_VALID) != 0
                        && !validCoordinates(longitude / 100_000.0, latitude / 100_000.0))
                    return null;
                rows[playerID] = new StateRow(flags, ownerSequence, eventSequence, gameState,
                        grenadeID, eventType, eventTargetID, score, deaths, health, shield,
                        shotsRemaining, gpsAgeSeconds, latitude, longitude);
            }
            return new StatePacket(networkVersion, token, packetSequence, senderID, gameMode, rows);
        }

        static byte[] encode(int networkVersion, UUID token, long sequence, int gameMode,
                             StateRow[] rows) {
            ByteBuffer buffer = ByteBuffer.allocate(STATE_PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(STATE_PACKET_MAGIC);
            buffer.put((byte) STATE_PACKET_FORMAT);
            buffer.put((byte) networkVersion);
            buffer.put((byte) 0); // authoritative host
            buffer.put((byte) gameMode);
            buffer.putLong(sequence);
            buffer.putLong(token.getMostSignificantBits());
            buffer.putLong(token.getLeastSignificantBits());
            for (int playerID = 1; playerID <= MAX_PLAYERS; playerID++) {
                StateRow row = rows == null ? null : rows[playerID];
                buffer.put((byte) (row == null ? 0 : row.flags | FLAG_PRESENT));
                buffer.put((byte) playerID);
                buffer.put((byte) (row == null ? 0 : row.gameState));
                buffer.put((byte) (row == null ? 0 : row.grenadeID));
                buffer.put((byte) (row == null ? 0 : row.eventType));
                buffer.put((byte) (row == null ? 0 : row.eventTargetID));
                buffer.putShort((short) 0);
                buffer.putInt((int) (row == null ? 0 : row.ownerSequence));
                buffer.putInt((int) (row == null ? 0 : row.eventSequence));
                buffer.putInt(row == null ? 0 : row.score);
                buffer.putInt(row == null ? 0 : row.deaths);
                buffer.putShort((short) clamp16(row == null ? 0 : row.health));
                buffer.putShort((short) clamp16(row == null ? 0 : row.shield));
                buffer.putShort((short) clamp16(row == null ? 0 : row.shotsRemaining));
                buffer.putShort((short) clamp16(row == null ? 0 : row.gpsAgeSeconds));
                buffer.putInt(row == null ? 0 : row.latitude);
                buffer.putInt(row == null ? 0 : row.longitude);
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
        final int latitude;
        final int longitude;

        StateRow(int flags, long ownerSequence, long eventSequence, int gameState, int grenadeID,
                 int eventType, int eventTargetID, int score, int deaths, int health, int shield,
                 int shotsRemaining, int gpsAgeSeconds, int latitude, int longitude) {
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
        }
    }

    /** Protocol-19 compact event; phones ACK only the addressed target. */
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
            if (buffer.getShort() != 0)
                return null;
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
            return new CombatPacket(networkVersion, kind, eventType, senderID, targetID,
                    eventSequence, token);
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

        static Settings tournament() {
            Settings settings = new Settings();
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
            // Boss players may change range/cone in the app. This default is
            // Outdoor / No Cone; hunters remain pinned to it.
            if (!boss)
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
        endRound("ended by the laptop operator");
        sendJson(exchange, 200, mapOf("ok", true));
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
            state.put("gameMode", config.gameMode.wireValue);
            state.put("bossMode", config.boss);
            state.put("playerCount", activeClients.size());
            state.put("clockReady", clockReadyCountLocked());
            state.put("balancedQr", config.balancedQr);
            state.put("assignedCount", balancedTeams.size());
            state.put("checkedInCount", balancedCheckedIn.size());
            state.put("checkInRemainingMs", config.balancedQr && roundState == RoundState.LOBBY
                    && balancedCheckInDeadline > 0
                    ? Math.max(0, balancedCheckInDeadline - now) : 0);
            state.put("startInMs", roundState == RoundState.COUNTDOWN ? Math.max(0, roundStartAt - now) : 0);
            state.put("remainingMs", roundEndAt > 0 && isRoundActiveLocked()
                    ? Math.max(0, roundEndAt - now) : 0);
            state.put("cooldownMs", Math.max(0, nextRoundStartAllowedAt - now));
            state.put("canStart", roundState == RoundState.LOBBY && activeClients.size() >= 2
                    && now >= nextRoundStartAllowedAt
                    && (!config.boss || activeClients.containsKey(1))
                    && clockReadyCountLocked() == activeClients.size()
                    && (!config.balancedQr || balancedTeams.isEmpty()));
            state.put("hostPort", config.tcpPort);

            List<Object> playerList = new ArrayList<>();
            for (Player player : players.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", player.id);
                item.put("name", player.name);
                item.put("team", teamFor(player.id));
                item.put("connected", player.connected);
                item.put("awaitingRespawn", player.awaitingRespawn);
                item.put("kills", player.kills);
                item.put("deaths", player.deaths);
                item.put("shots", player.shots);
                item.put("hits", player.hits);
                item.put("accuracy", player.shots == 0 ? null
                        : Math.min(100.0, (100.0 * player.hits) / player.shots));
                item.put("gpsAgeMs", player.lastGpsAt == 0 ? null : now - player.lastGpsAt);
                if (validCoordinates(player.longitude, player.latitude)) {
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
            return state;
        }
    }

    private int clockReadyCountLocked() {
        int count = 0;
        for (ClientConnection client : activeClients.values()) {
            if (client.clockReady)
                count++;
        }
        return count;
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
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
        int tcpPort = 17_510;
        int udpPort = 17_500;
        int dashboardPort = 17_511;
        String dashboardBind = "127.0.0.1";
        GameMode gameMode = GameMode.TWO_TEAMS;
        // Phones see teammates by default. The laptop dashboard is a trusted
        // game-master display and remains an all-player view.
        GpsMode gpsMode = GpsMode.TEAMMATE;
        boolean useGps = true;
        boolean onlyServerSettings = true;
        boolean tournament = true;
        boolean boss;
        boolean balanced;
        boolean balancedQr;
        boolean allowLateJoin = true;
        boolean takeover;
        int startDelaySeconds = 10;
        int durationMinutes = 5;
        int scoreLimit;
        int livesLimit;
        boolean noBrowser;
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
                if ("--no-late-join".equals(argument)) {
                    config.allowLateJoin = false;
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
                    + "  --tournament                Accepted for compatibility; always enabled\n"
                    + "  --boss                      Player 1 vs everyone; fixed Boss rules\n"
                    + "  --balanced-qr               Balance past stats; require team QR check-in\n"
                    + "  --balanced-no-qr            Balance past stats; start without QR\n"
                    + "  --duration-minutes 0..100   0 means unlimited (default: 5)\n"
                    + "  --score-limit 0..100        0 means unlimited\n"
                    + "  --lives-limit 0..100        0 means unlimited\n"
                    + "  --gps all|team              Phone GPS visibility (default: team)\n"
                    + "  --start-delay 1..1000       Shared countdown seconds (default: 10)\n"
                    + "  --tcp-port PORT             Must remain 17510 for stock phones\n"
                    + "  --udp-port PORT             Must remain 17500 for stock phones\n"
                    + "  --dashboard-port PORT       Local dashboard (default: 17511)\n"
                    + "  --dashboard-bind ADDRESS    Defaults to 127.0.0.1\n"
                    + "  --no-late-join              Reject joins after the start\n"
                    + "  --takeover                  Replace an idle phone-hosted lobby on this Wi-Fi\n"
                    + "  --no-browser                Do not open the dashboard automatically";
        }
    }

    /** Tiny dependency-free JSON implementation for the protocol and dashboard. */
    private static final class Json {
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
                    return decimal ? Double.valueOf(raw) : Long.valueOf(raw);
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

    private static final String INDEX_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>SimpleCoil Laptop Host</title>
            <style>body{margin:0;background:#10141b;color:#eef3f8;font:18px system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}main{max-width:660px;padding:32px}h1{margin-top:0}.buttons{display:flex;gap:14px;flex-wrap:wrap}a,button{border:0;border-radius:8px;background:#2585d8;color:white;padding:14px 18px;font:inherit;text-decoration:none;cursor:pointer}p{line-height:1.5;color:#c6d1dc}.note{font-size:.9em;color:#9dadbc}</style></head>
            <body><main><h1>SimpleCoil Laptop Host</h1><p>Open the live map and leaderboard as two independent display windows. Keep this page local to the laptop; game controls are available on the map display.</p>
            <div class="buttons"><a href="/map" target="simplecoil-map">Open map display</a><a href="/leaderboard" target="simplecoil-leaderboard">Open leaderboard</a><button onclick="openDisplays()">Open both displays</button></div>
            <p class="note">Phones join the laptop's printed LAN IP from <em>Join Game</em>. The dashboard uses the local browser only unless the host was started with <code>--dashboard-bind 0.0.0.0</code>.</p>
            <script>function openDisplays(){window.open('/map','simplecoil-map','popup=yes');window.open('/leaderboard','simplecoil-leaderboard','popup=yes')}</script></main></body></html>
            """;

    private static final String MAP_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>SimpleCoil Tactical Map</title>
            <style>
            :root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0a1017;color:#eef4f9;font:15px system-ui,sans-serif;overflow:hidden}header{height:60px;display:flex;align-items:center;gap:16px;padding:10px 16px;background:#111c27;border-bottom:1px solid #274257}h1{font-size:20px;margin:0;white-space:nowrap}#status{color:#b8c8d8;flex:1}button{border:0;border-radius:7px;padding:9px 13px;background:#267ec8;color:#fff;font:inherit;cursor:pointer}button.danger{background:#b83d46}button:disabled{opacity:.45;cursor:not-allowed}#map{display:block;width:100vw;height:calc(100vh - 60px);touch-action:none}.legend{position:fixed;right:16px;bottom:16px;background:#101a25d9;border:1px solid #34516a;border-radius:8px;padding:10px;line-height:1.7}.swatch{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px}.team1{background:#49a5ff}.team2{background:#ff5d67}.dead{opacity:.45}#respawns:empty{display:none}#respawns{border-top:1px solid #34516a;margin-top:7px;padding-top:7px}.respawn{font-size:12px;padding:5px 7px;margin:3px 3px 0 0;background:#466c32}@media(max-width:650px){header{gap:8px}h1{font-size:15px}#status{font-size:12px}}
            </style></head><body><header><h1>Tactical GPS Map</h1><span id="status">Connecting…</span><button id="start">Start game</button><button id="end" class="danger">End game</button></header><canvas id="map"></canvas><div class="legend"><span class="swatch team1"></span>Team 1 &nbsp;<span class="swatch team2"></span>Team 2<br>Bright lines are verified hits; red lines are eliminations.<div id="respawns"></div></div>
            <script>
            const canvas=document.querySelector('#map'),ctx=canvas.getContext('2d');let state=null;
            const status=document.querySelector('#status'),start=document.querySelector('#start'),end=document.querySelector('#end'),respawns=document.querySelector('#respawns');
            function fit(){const d=devicePixelRatio||1;canvas.width=innerWidth*d;canvas.height=(innerHeight-60)*d;canvas.style.width=innerWidth+'px';canvas.style.height=(innerHeight-60)+'px';ctx.setTransform(d,0,0,d,0,0);draw()};addEventListener('resize',fit);fit();
            async function control(path){try{const r=await fetch(path,{method:'POST'}),j=await r.json();status.textContent=j.message|| (j.ok?'Done.':'Unable to complete action.')}catch(e){status.textContent='Host control error.'}}
            start.onclick=()=>control('/api/start');end.onclick=()=>control('/api/end');
            function drawRespawns(s){respawns.replaceChildren();for(const p of s.players||[]){if(!p.awaitingRespawn)continue;const b=document.createElement('button');b.className='respawn';b.textContent=`Respawn ${p.name} #${p.id}`;b.onclick=()=>control('/api/respawn?id='+encodeURIComponent(p.id));respawns.append(b)}}
            function color(team){return team===1?'#49a5ff':team===2?'#ff5d67':`hsl(${(team*57)%360} 90% 63%)`}
            function label(s){if(!s)return 'Connecting...';const p=s.playerCount||0,ready=s.clockReady||0;if(s.state==='countdown')return `Starting in ${Math.ceil(s.startInMs/1000)}s - ${p} players`;if(s.state==='running')return s.remainingMs?`Running - ${Math.ceil(s.remainingMs/1000)}s left - ${p} players`:`Running - ${p} players`;if(s.cooldownMs>0)return `Finished - final scores visible; next game in ${Math.ceil(s.cooldownMs/1000)}s`;if(s.state==='finished')return 'Finished - final scores remain visible';if(s.balancedQr&&s.assignedCount)return `Team QR check-ins ${s.checkedInCount}/${s.assignedCount} - auto-start in ${Math.ceil(s.checkInRemainingMs/1000)}s`;return `${p} players - clocks ${ready}/${p}`}
            function draw(){const w=innerWidth,h=innerHeight-60;ctx.clearRect(0,0,w,h);ctx.fillStyle='#0d1720';ctx.fillRect(0,0,w,h);if(!state){respawns.replaceChildren();return}status.textContent=label(state);start.disabled=!state.canStart;end.disabled=!['countdown','running'].includes(state.state);drawRespawns(state);const ps=state.players.filter(p=>Number.isFinite(p.longitude)&&Number.isFinite(p.latitude));if(!ps.length){ctx.fillStyle='#aab9c6';ctx.font='18px system-ui';ctx.fillText('Waiting for usable GPS fixes…',24,36);return}
              let minLat=Math.min(...ps.map(p=>p.latitude)),maxLat=Math.max(...ps.map(p=>p.latitude)),minLon=Math.min(...ps.map(p=>p.longitude)),maxLon=Math.max(...ps.map(p=>p.longitude));let midLat=(minLat+maxLat)/2,metersLat=Math.max(30,(maxLat-minLat)*111320),metersLon=Math.max(30,(maxLon-minLon)*111320*Math.cos(midLat*Math.PI/180));let scale=Math.min((w-100)/metersLon,(h-100)/metersLat);function xy(p){return{x:w/2+(p.longitude-(minLon+maxLon)/2)*111320*Math.cos(midLat*Math.PI/180)*scale,y:h/2-(p.latitude-(minLat+maxLat)/2)*111320*scale}}
              ctx.strokeStyle='#203445';ctx.lineWidth=1;for(let x=0;x<w;x+=50){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,h);ctx.stroke()}for(let y=0;y<h;y+=50){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(w,y);ctx.stroke()}
              for(const p of ps){const t=(p.trail||[]).filter(q=>Number.isFinite(q.longitude)&&Number.isFinite(q.latitude));if(t.length>1){ctx.strokeStyle=color(p.team)+'66';ctx.lineWidth=2;ctx.beginPath();t.forEach((q,i)=>{const pt=xy(q);i?ctx.lineTo(pt.x,pt.y):ctx.moveTo(pt.x,pt.y)});ctx.stroke()}}
              const byId=Object.fromEntries(ps.map(p=>[p.id,p]));function savedPoint(l,prefix,fallback){const longitude=l[prefix+'Longitude'],latitude=l[prefix+'Latitude'];return Number.isFinite(longitude)&&Number.isFinite(latitude)?{longitude,latitude}:fallback}for(const l of state.lasers||[]){const a=byId[l.shooter],b=byId[l.target];if(!a||!b)continue;const aa=xy(savedPoint(l,'shooter',a)),bb=xy(savedPoint(l,'target',b)),alpha=Math.max(0,1-l.ageMs/1500);ctx.save();ctx.globalAlpha=alpha;ctx.strokeStyle=l.kind==='kill'?'#ff3047':'#fff36b';ctx.shadowColor=ctx.strokeStyle;ctx.shadowBlur=12;ctx.lineWidth=l.kind==='kill'?6:4;ctx.beginPath();ctx.moveTo(aa.x,aa.y);ctx.lineTo(bb.x,bb.y);ctx.stroke();ctx.restore()}
              for(const p of ps){const q=xy(p);ctx.save();ctx.globalAlpha=p.connected?1:.4;ctx.fillStyle=color(p.team);ctx.strokeStyle='#fff';ctx.lineWidth=2;ctx.beginPath();ctx.arc(q.x,q.y,p.awaitingRespawn?8:11,0,Math.PI*2);ctx.fill();ctx.stroke();ctx.fillStyle='#fff';ctx.font='bold 13px system-ui';ctx.textAlign='center';ctx.fillText(`${p.name} #${p.id}`,q.x,q.y-17);if(p.awaitingRespawn){ctx.fillStyle='#ffb1b7';ctx.fillText('RESPAWN',q.x,q.y+29)}ctx.restore()}
            }
            async function refresh(){try{const r=await fetch('/api/state',{cache:'no-store'});if(!r.ok)throw Error();state=await r.json();draw()}catch(e){status.textContent='Disconnected from laptop host.'}setTimeout(refresh,150)}refresh();
            </script></body></html>
            """;

    private static final String LEADERBOARD_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>SimpleCoil Leaderboard</title>
            <style>:root{color-scheme:dark}body{margin:0;background:#0b121b;color:#eef4f9;font:18px system-ui,sans-serif;padding:26px}h1{margin:0 0 4px}#subtitle{color:#aebdca;margin:0 0 22px}table{width:100%;border-collapse:collapse;background:#111c27;border-radius:10px;overflow:hidden}th,td{padding:13px 14px;text-align:left;border-bottom:1px solid #263c50}th{color:#aabdd0;font-size:.78em;letter-spacing:.06em;text-transform:uppercase}tr:last-child td{border:0}.team1 td:first-child{border-left:6px solid #49a5ff}.team2 td:first-child{border-left:6px solid #ff5d67}.offline{opacity:.48}.dead{color:#ffb0b7}#empty{color:#aebdca;padding:24px 0}@media(max-width:600px){body{padding:14px;font-size:15px}th,td{padding:9px 7px}}</style></head><body><h1>Leaderboard</h1><p id="subtitle">Connecting…</p><table><thead><tr><th>Player</th><th>Team</th><th>Kills</th><th>Hits</th><th>Shots</th><th>Accuracy</th></tr></thead><tbody id="rows"></tbody></table><p id="empty"></p>
            <script>const rows=document.querySelector('#rows'),subtitle=document.querySelector('#subtitle'),empty=document.querySelector('#empty');function pct(p){return p.accuracy==null?'—':Math.round(p.accuracy)+'%'}function title(s){if(s.state==='countdown')return `Starting in ${Math.ceil(s.startInMs/1000)} seconds`;if(s.state==='running')return s.remainingMs?`Game running · ${Math.ceil(s.remainingMs/1000)} seconds remaining`:'Game running';if(s.state==='finished')return 'Final scores';return `Lobby · ${s.clockReady||0}/${s.playerCount||0} clocks synchronized`};function draw(s){subtitle.textContent=title(s);const players=[...(s.players||[])].sort((a,b)=>b.kills-a.kills||b.accuracy-a.accuracy||b.hits-a.hits||a.id-b.id);rows.replaceChildren();empty.textContent=players.length?'':'Waiting for players to join…';for(const p of players){const tr=document.createElement('tr');tr.className=`team${p.team}${p.connected?'':' offline'}`;const name=document.createElement('td');name.textContent=p.name+(p.awaitingRespawn?' · RESPAWN':'');if(p.awaitingRespawn)name.className='dead';const vals=[p.team,p.kills,p.hits,p.shots,pct(p)];tr.append(name,...vals.map(v=>{const td=document.createElement('td');td.textContent=v;return td}));rows.append(tr)}}async function refresh(){try{const r=await fetch('/api/state',{cache:'no-store'});if(!r.ok)throw Error();draw(await r.json())}catch(e){subtitle.textContent='Disconnected from laptop host.'}setTimeout(refresh,200)}refresh();</script></body></html>
            """;
}
