package com.simplecoil.laptophost;

import com.simplecoil.protocol.GpsOffset;
import com.simplecoil.protocol.GameStartCountdown;
import com.simplecoil.protocol.Powerups;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** End-to-end smoke test for the wire protocol used by stock Android clients. */
public final class LaptopHostProtocolSmokeTest {
    private static final int TCP_PORT = 19_510;
    private static final int UDP_PORT = 19_500;
    private static final int DASHBOARD_PORT = 19_511;
    private static final int TILE_PORT = 19_512;
    private static final int NETWORK_VERSION = 31;
    private static final String JSON_PREFIX = "SimpleCoil:" + NETWORK_VERSION + "JSON";
    private static final String MESSAGE_PREFIX = "SimpleCoil:" + NETWORK_VERSION + "MESG";
    private static final long GPS_UTC_BASE = 1_800_000_000_000L;
    private static final HttpClient WIFI_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public static void main(String[] args) throws Exception {
        System.setProperty("simplecoil.reports", Files.createTempDirectory("simplecoil-smoke-reports").toString());
        System.setProperty("simplecoil.players.file", Files.createTempDirectory("simplecoil-smoke-players")
                .resolve("players.json").toString());
        System.setProperty("simplecoil.event.file", Files.createTempDirectory("simplecoil-smoke-event")
                .resolve("event.properties").toString());
        if (args.length == 1 && "--reports-only".equals(args[0])) {
            verifyRoundReports();
            verifyRoundReportBounds();
            verifyQueuedSourceSlotReuse();
            System.out.println("Post-round statistics and timeline smoke tests passed");
            return;
        }
        if (args.length == 1 && "--balanced-only".equals(args[0])) {
            verifyBalancedQr();
            verifyBalancedNoQr();
            verifyBalancedTeamRetention();
            System.out.println("Balanced-team retention and rebalance protocol smoke tests passed");
            return;
        }
        if (args.length == 1 && "--powerups-only".equals(args[0])) {
            verifyPowerupRecipientWire();
            verifyAirstrikeHostWireSnapshot();
            verifyPowerupStandaloneFrameBound();
            verifyPowerupSnapshotFraming();
            verifyPowerupRequests(2);
            verifyPowerupRequests(8);
            verifyPowerupRequests(0);
            verifyDynamicPowerupRequirement();
            System.out.println("Host-authoritative power-up protocol smoke tests passed");
            return;
        }
        if (args.length == 1 && "--countdown-only".equals(args[0])) {
            verifyNormalStartCountdown(false);
            verifyNormalStartCountdown(true);
            verifyBalancedQr();
            verifyBalancedNoQr();
            verifyBalancedTeamRetention();
            System.out.println("Game-start countdown protocol smoke tests passed");
            return;
        }
        if (args.length == 1 && "--gps-only".equals(args[0])) {
            verifyGpsOffsets();
            System.out.println("GPS offset protocol smoke test passed");
            return;
        }
        if (args.length == 1 && "--dead-rejoin-only".equals(args[0])) {
            verifyDeadRejoin();
            System.out.println("Dead-player reconnect smoke test passed");
            return;
        }
        if (args.length == 1 && "--respawn-only".equals(args[0])) {
            verifyRespawnMode(false);
            verifyRespawnMode(true);
            System.out.println("Respawn-mode protocol smoke tests passed");
            return;
        }
        if (args.length == 1 && "--boss-only".equals(args[0])) {
            verifyBossMode();
            System.out.println("Boss-mode protocol smoke test passed");
            return;
        }
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        Path tiles = Files.createTempDirectory("simplecoil-map-tiles");
        Path testTile = tiles.resolve(Path.of("0", "0", "0.png"));
        Files.createDirectories(testTile.getParent());
        byte[] testTileBytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        Files.write(testTile, testTileBytes);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1",
                "--powerup-qr", "4",
                "--no-late-join", "--tiles", tiles.toString(), "--tile-port",
                String.valueOf(TILE_PORT))
                .redirectErrorStream(true)
                .start();
        try {
            waitForHost();
            require(get("/map").contains("Tactical GPS Map"), "map display was unavailable");
            require(get("/map").contains("/assets/leaflet/leaflet.js"),
                    "map display did not load the bundled renderer");
            require(get("/map").contains("drawGrenades(s)"),
                    "map display did not draw grenade hit circles");
            require(get("/assets/leaflet/leaflet.js").contains("Leaflet"),
                    "bundled map renderer was unavailable");
            require(get("/api/map-config").contains("\"available\":true"),
                    "offline map tile configuration was not detected");
            Path mapMetadata = tiles.resolve("map.json");
            Files.writeString(mapMetadata, "{\"center\":[47.6221479,-122.1028626],\"zoom\":18,"
                    + "\"bounds\":[[47.61,-122.12],[47.64,-122.08]],"
                    + "\"version\":\"test-map-1\",\"attribution\":\"OpenStreetMap contributors\"}");
            String mapConfig = get("/api/map-config");
            require(mapConfig.contains("\"center\":[47.6221479,-122.1028626]")
                            && mapConfig.contains("\"zoom\":0")
                            && mapConfig.contains("\"bounds\":[[47.61,-122.12],[47.64,-122.08]]")
                            && mapConfig.contains("\"version\":\"test-map-1\"")
                            && mapConfig.contains("OpenStreetMap contributors"),
                    "offline map view and attribution were not published with a supported zoom");
            require(new String(getBytes(TILE_PORT, "/api/map-config"), StandardCharsets.UTF_8)
                            .equals(mapConfig),
                    "phones and dashboard received different map configuration");
            Files.writeString(mapMetadata, "{invalid");
            require(get("/api/map-config").contains("\"available\":true"),
                    "invalid optional metadata prevented offline tiles from loading");
            Files.delete(mapMetadata);
            require(java.util.Arrays.equals(getBytes("/tiles/0/0/0.png"), testTileBytes),
                    "offline map tile was not served intact");
            require(java.util.Arrays.equals(getBytes("/tiles/0/0/0.png?v=test-map-1"), testTileBytes),
                    "versioned offline map tile was not served intact");
            require(java.util.Arrays.equals(getBytes(TILE_PORT, "/tiles/0/0/0.png"), testTileBytes),
                    "read-only phone map tile service was unavailable");
            require(responseCode("/tiles/0/0/1.png") == 404,
                    "invalid tile coordinates were accepted");
            require(get("/leaderboard").contains("Leaderboard"), "leaderboard display was unavailable");
            require(get("/wifi").contains("Start hotspot"), "Wi-Fi controls were unavailable");
            require(get("/api/wifi").contains("\"devices\":"), "Wi-Fi adapter status was unavailable");
            HttpURLConnection phoneWifi = open(TILE_PORT, "/api/wifi", "GET");
            require(phoneWifi.getResponseCode() == 404, "phone map service exposed Wi-Fi controls");
            phoneWifi.disconnect();
            require(wifiResponseCode("GET", null, "https://untrusted.example", false) == 403,
                    "another website could read Wi-Fi credentials");
            require(wifiResponseCode("POST", "{\"action\":\"stop\"}", null, false) == 403,
                    "Wi-Fi mutation did not require its control header");
            require(wifiResponseCode("POST", "{invalid", null, true) == 400,
                    "invalid Wi-Fi JSON was accepted");
            require(wifiResponseCode("POST", "x".repeat(4097), null, true) == 413,
                    "oversized Wi-Fi settings were accepted");
            require(wifiResponseCode("POST", "{\"action\":\"invalid\"}", null, true) == 400,
                    "unknown Wi-Fi actions were accepted");
            verifyUdpDiscovery();
            verifySharedLobbyDiscovery();
            verifyPreviousProtocolRejected();
            String completedToken;
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone blueTeammate = new FakePhone("127.0.0.5", 2, "Blue Two");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red");
                 FakePhone redTeammate = new FakePhone("127.0.0.6", 18, "Red Two");
                 FakePhone spare = new FakePhone("127.0.0.7", 3, "Spare")) {
                require(first.lastRoster.contains("\"hostkind\":\"L\"")
                                && first.lastRoster.contains("\"hostage\":"),
                        "TCP roster did not identify the laptop when UDP beacons are missed");
                require(first.readUntil(frame -> frame.startsWith(JSON_PREFIX + "{\"players\"")
                                && frame.contains("\"powerupqrrequired\":4")) != null,
                        "laptop host did not advertise the power-up QR requirement");
                first.synchronizeClock();
                blueTeammate.synchronizeClock();
                second.synchronizeClock();
                redTeammate.synchronizeClock();
                first.send(JSON_PREFIX + "{\"lobbyready\":false}");
                require(first.readUntil(frame -> frame.contains("\"lobbyready\":false")) != null,
                        "gun readiness was not shared with the lobby");
                require(post("/api/start").contains("connect a gun"),
                        "host started with an unready player");
                first.send(JSON_PREFIX + "{\"lobbyready\":true}");
                require(first.readUntil(frame -> frame.contains("\"lobbyready\":true")
                                && !frame.contains("\"lobbyready\":false")) != null,
                        "gun readiness did not recover after connection");
                spare.send(JSON_PREFIX + "{\"lobbyready\":false}");
                require(first.readUntil(frame -> frame.contains("\"lobbyready\":false")) != null,
                        "spare phone did not publish its missing gun");
                require(post("/api/bench?id=3&benched=true").contains("\"ok\":true"),
                        "host could not sit out the spare phone");
                require(first.readUntil(frame -> frame.contains("\"lobbybenched\":true")) != null,
                        "bench status was not shared with players");
                require(get("/api/state").contains("\"benchedCount\":1"),
                        "spare phone was not retained in the lobby");
                spare.send(JSON_PREFIX + "{\"lobbybenched\":false}");
                require(get("/api/state").contains("\"playerCount\":4"),
                        "spare phone counted as an active player or unbenched itself");
                first.send(JSON_PREFIX + "{\"gpslongitude\":-122.1,\"gpslatitude\":47.6}");
                second.send(JSON_PREFIX + "{\"gpslongitude\":-122.2,\"gpslatitude\":47.7}");
                require(post("/api/start").contains("\"ok\":true"), "host did not start the round");
                require(spare.readUntil(frame -> frame.contains("\"lobbywait\":true")) != null,
                        "spare phone did not stay in the lobby at round start");
                require(!spare.receivesFor(frame -> frame.contains("\"gamestart\"")
                                && !frame.contains("\"players\""), 150),
                        "spare phone received a direct game start");
                require(post("/api/bench?id=3&benched=false").contains("\"ok\":false"),
                        "host changed participation mid-round");
                String startFrame = first.readUntil(frame -> frame.contains("\"gpsstarttime\""));
                require(startFrame != null,
                        "GPS-calibrated start time was not delivered to the phone");
                String roundToken = jsonString(startFrame, "roundtoken");
                require(roundToken != null, "start frame omitted its round token");
                first.sendStateGossip(roundToken, 41, 7);
                require(first.receivesAuthorityState(roundToken, 41, 7),
                        "host tick did not merge and return a player's state gossip");
                first.sendCombat(roundToken, 9, 2, 17);
                require(first.receivesAuthorityCombat(roundToken, 1, 9, 2, 17),
                        "host tick did not retain an overheard compact combat event");
                Thread.sleep(1_150);
                require(get("/api/state").contains("\"state\":\"running\""),
                        "round did not leave the start countdown");
                require(wifiResponseCode("POST", "{\"action\":\"stop\"}", null, true) == 409,
                        "Wi-Fi could be changed during an active round");
                require(wifiResponseCode("POST", "{\"action\":\"apply\",\"ssid\":\"\"}", null, true) == 400,
                        "Apply was blocked during a round instead of validating the requested settings");
                verifyLateJoinBlocked();
                verifyDirectLateJoinBlocked();

                first.send(JSON_PREFIX + "{\"telemetry\":\"shot\",\"count\":3}");
                require(!first.receivesFor(frame -> gpsFrameContainsPlayer(frame, 17), 600),
                        "a shot/miss revealed an enemy GPS location");
                second.send(JSON_PREFIX + "{\"telemetry\":\"hit\",\"attacker\":1}");
                require(first.readUntil(frame -> gpsFrameContainsPlayer(frame, 17)) != null,
                        "a confirmed hit did not reveal the targeted enemy to the shooter");
                require(blueTeammate.readUntil(frame -> gpsFrameContainsPlayer(frame, 17)) != null,
                        "a confirmed hit did not reveal the targeted enemy to the shooter's team");
                require(second.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)) != null,
                        "a confirmed hit did not reveal the shooter to the target");
                require(redTeammate.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)) != null,
                        "a confirmed hit did not reveal the shooter to the target's team");
                blueTeammate.send(JSON_PREFIX + "{\"gpslongitude\":-122.3,\"gpslatitude\":47.8}");
                redTeammate.send(JSON_PREFIX + "{\"gpslongitude\":-122.4,\"gpslatitude\":47.9}");
                String grenadeHit = JSON_PREFIX + "{\"telemetry\":\"grenadehit\",\"grenadeID\":3}";
                first.send(grenadeHit);
                blueTeammate.send(grenadeHit);
                second.send(grenadeHit);
                String threeHits = waitForDashboard(value -> value.contains("\"grenadeID\":3")
                        && value.contains("\"hitCount\":3"));
                require(threeHits != null && !threeHits.contains("\"centerLatitude\""),
                        "grenade center appeared before four distinct mapped hits");
                redTeammate.send(grenadeHit);
                redTeammate.send(grenadeHit);
                String fourHits = waitForDashboard(value -> value.contains("\"grenadeID\":3")
                        && value.contains("\"hitCount\":4")
                        && value.contains("\"centerLatitude\":47.75"));
                require(fourHits != null, "four grenade victims did not produce an estimated center");
                require(fourHits.contains("\"centerLongitude\":"),
                        "grenade estimate omitted longitude");
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                String kill = first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17"));
                require(kill != null, "scorer did not receive the authoritative kill frame");

                String state = get("/api/state");
                require(state.contains("\"kills\":1"), "dashboard did not record a kill");
                require(state.contains("\"hits\":1"), "dashboard did not record a hit");
                require(state.contains("\"shots\":3"), "dashboard did not record shots");
                require(state.contains("\"kind\":\"kill\""), "dashboard did not receive a laser event");
                require(state.contains("\"shooterLongitude\":-122.1"),
                        "laser did not retain the shooter's event position");
                require(post("/api/respawn?id=17").contains("\"ok\":true"),
                        "game-master respawn was not accepted");
                require(second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                        "respawning player did not receive the grant");
                first.send(JSON_PREFIX + "{\"playerID\":1,\"pairedgrenadeID\":4}");
                require(first.readUntil(frame -> frame.contains("\"grenadepairings\"")) != null,
                        "grenade pairing was not shared");
                second.send(JSON_PREFIX + "{\"telemetry\":\"grenadehit\",\"grenadeID\":4}");
                require(waitForDashboard(value -> value.contains("\"grenadeID\":4")
                        && value.contains("\"hits\":1")) != null,
                        "paired grenade hit incorrectly changed gun IR accuracy");
                require(post("/api/end").contains("\"ok\":true"), "host did not request approval");
                require(first.readUntil(frame -> frame.contains("\"endvotes\":0")) != null,
                        "the operator incorrectly counted as a player approval");
                String activeToken = roundToken;
                first.send(MESSAGE_PREFIX + "ENDGAME");
                require(!first.receivesFor(frame -> frame.equals(MESSAGE_PREFIX + "ENDGAME"), 100),
                        "an unscoped single-player ENDGAME bypassed approval");
                first.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                require(second.readUntil(frame -> frame.contains("\"endvotes\":1")) != null,
                        "first approval was not shared");
                first.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                spare.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                second.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"stale\"}");
                require(!first.receivesFor(frame -> frame.equals(MESSAGE_PREFIX + "ENDGAME"), 100),
                        "duplicate, benched, or stale votes ended the round");
                first.send(JSON_PREFIX + "{\"endvote\":false,\"roundtoken\":\"" + activeToken + "\"}");
                require(second.readUntil(frame -> frame.contains("\"endvotes\":0")) != null,
                        "withdrawing approval did not clear the vote");
                blueTeammate.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                require(first.readUntil(frame -> frame.contains("\"endvotes\":2")) != null,
                        "teammate approval was not recorded");
                blueTeammate.send(MESSAGE_PREFIX + "QUIT");
                require(first.readUntil(frame -> frame.contains("\"endvotes\":0")) != null,
                        "leaving player retained a vote");
                blueTeammate.close();
                String replacementIdentity = UUID.randomUUID().toString();
                try (FakePhone replacement = new FakePhone("127.0.0.8", 2, "Replacement",
                        replacementIdentity, 1)) {
                    require(replacement.playerID == 2, "QR join did not reuse the departed team seat");
                    require(roundToken.equals(jsonString(replacement.lastRoster, "roundtoken")),
                            "QR join changed the running round token");
                    require(replacement.lastRoster.contains("\"points\":0"),
                            "new player inherited somebody else's kills");
                    replacement.synchronizeClock();
                    replacement.sendStateGossip(roundToken, 2, 0);
                    require(replacement.receivesAuthorityState(roundToken, 2, 0),
                            "replacement player's sequence restarted but the host rejected it");
                    require(first.readUntil(frame -> frame.contains("\"playername\":\"Replacement\"")) != null,
                            "existing players did not learn the new roster");
                    replacement.send(MESSAGE_PREFIX + "QUIT");
                    require(first.readUntil(frame -> frame.contains("\"players\"")
                                    && !frame.contains("\"playername\":\"Replacement\"")) != null,
                            "replacement departure did not update the roster");
                }
                try (FakePhone returning = new FakePhone("127.0.0.9", 2, "Replacement Returns",
                        replacementIdentity, 1)) {
                    require(returning.playerID == 2 && returning.seatGeneration == 2,
                            "same person could not scan back into the running match");
                    returning.send(MESSAGE_PREFIX + "QUIT");
                }
                require(get("/api/state").contains("\"state\":\"running\""),
                        "leaving or joining stopped the existing match");
                first.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                second.send(JSON_PREFIX + "{\"endvote\":true,\"roundtoken\":\"" + activeToken + "\"}");
                require(first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ENDGAME")) != null,
                        "two different approvals did not end the game");
                String finishedState = get("/api/state");
                require(finishedState.contains("\"cooldownMs\":0"),
                        "round stopped under 60 seconds incorrectly required a cooldown");
                require(!post("/api/start").contains("Next game can start in"),
                        "host delayed an early-stop restart by 30 seconds");
                completedToken = activeToken;
            }
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue Next Round");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red Next Round")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"),
                        "fresh lobby could not start immediately after a short round");
                String newStart = first.readUntil(frame -> frame.contains("\"gamestart\""));
                require(newStart != null && !completedToken.equals(jsonString(newStart, "roundtoken")),
                        "quick restart reused the previous round identity");
                require(second.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "quick restart failed to send a synchronized start to both phones");
            }
            System.out.println("LaptopHost protocol smoke test passed.");
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
        if (args.length == 1 && "--core-only".equals(args[0])) return;
        verifyNormalStartCountdown(false);
        verifyNormalStartCountdown(true);
        System.out.println("LaptopHost normal start countdown smoke tests passed.");
        verifyGpsOffsets();
        System.out.println("LaptopHost GPS offset smoke test passed.");
        verifyBalancedQr();
        System.out.println("LaptopHost balanced QR smoke test passed.");
        verifyBalancedNoQr();
        System.out.println("LaptopHost balanced no-QR smoke test passed.");
        verifyBalancedTeamRetention();
        System.out.println("LaptopHost retained-team and manual rebalance smoke test passed.");
        verifyPowerupRecipientWire();
        verifyAirstrikeHostWireSnapshot();
        verifyPowerupStandaloneFrameBound();
        verifyPowerupSnapshotFraming();
        verifyPowerupRequests(2);
        verifyPowerupRequests(8);
        verifyPowerupRequests(0);
        verifyDynamicPowerupRequirement();
        System.out.println("LaptopHost host-authoritative power-up smoke tests passed.");
        verifyDeadRejoin();
        verifyRespawnMode(false);
        verifyRespawnMode(true);
        verifyBossMode();
        System.out.println("LaptopHost Boss Mode smoke test passed.");
        verifyCaptureTheFlagMode();
        System.out.println("LaptopHost Capture the Flag smoke test passed.");
        verifyInfectionMode();
        System.out.println("LaptopHost Infection smoke test passed.");
        verifyRoundReports();
        verifyRoundReportBounds();
        verifyQueuedSourceSlotReuse();
        System.out.println("LaptopHost post-round statistics and timeline smoke tests passed.");
        if (args.length > 0 && "--verify-timeout".equals(args[0])) {
            verifyBalancedQrTimeout();
            System.out.println("LaptopHost 90-second QR timeout smoke test passed.");
        }
    }

    private static void verifyGpsOffsets() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--gps", "all", "--tcp-port", String.valueOf(TCP_PORT), "--udp-port",
                String.valueOf(UDP_PORT), "--dashboard-port", String.valueOf(DASHBOARD_PORT),
                "--start-delay", "1").redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "GPS Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "GPS Red")) {
                require(first.lastRoster.contains("\"gpsoffset\":null"), "missing fix created an origin");
                String reply = first.probeClock(",\"gpslatitude\":0,\"gpslongitude\":0");
                require(reply.contains("\"gpsoffset\":null"), "Null Island created a lobby origin");
                require(!get("/api/state").contains("\"latitude\":"),
                        "Null Island created a map marker or trail");
                for (String age : List.of("-1", "1.5", "30001", "1e309", "\"0\"", "null")) {
                    reply = first.probeClock(",\"gpslatitude\":47.6,\"gpslongitude\":-122.1,\"gpsage\":" + age);
                    require(reply.contains("\"gpsoffset\":null"),
                            "invalid GPS age created an origin: " + age);
                }
                reply = first.probeClock(",\"gpslatitude\":47.6,\"gpslongitude\":-122.1,\"gpsage\":10000");
                String originJson = "{\"gpslatitude\":47.6,\"gpslongitude\":-122.1}";
                require(reply.contains("\"gpsoffset\":" + originJson), "clock sync did not share the GPS origin");
                require(second.readUntil(frame -> frame.contains("\"gpsoffset\":" + originJson)) != null,
                        "lobby did not distribute its chosen origin");
                double bootstrapAge = jsonNumber(currentPlayerState(get("/api/state"), 1), "gpsAgeMs");
                require(bootstrapAge >= 10000 && bootstrapAge < 12000,
                        "clock bootstrap renewed the measurement age at receipt");
                String agedUpdate = second.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)
                        && jsonNumber(frame, "gpsage") >= 10000);
                require(agedUpdate != null && jsonNumber(agedUpdate, "gpsage") < 15000,
                        "GPS snapshot did not preserve the original measurement age");
                GpsOffset origin = GpsOffset.create(47.6, -122.1);
                double[] expected = origin.decode(-30001, 31002);
                first.send(JSON_PREFIX + "{\"gpsoffset\":" + originJson
                        + ",\"gpsn\":-30001,\"gpse\":31002}");
                first.probeClock("");
                require(closeTo(jsonNumber(get("/api/state"), "latitude"), expected[0])
                        && closeTo(jsonNumber(get("/api/state"), "longitude"), expected[1]),
                        "signed two-byte GPS offsets lost precision");
                require(second.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)
                        && frame.contains("\"gpsn\":-30001") && frame.contains("\"gpse\":31002")) != null,
                        "host did not forward compact GPS offsets");
                first.send(JSON_PREFIX + "{\"gpsoffset\":{\"gpslatitude\":48,\"gpslongitude\":-122.1},"
                        + "\"gpsn\":0,\"gpse\":0}");
                first.send(JSON_PREFIX + "{\"gpsoffset\":" + originJson + ",\"gpsn\":32768,\"gpse\":0}");
                first.send(JSON_PREFIX + "{\"gpslatitude\":0,\"gpslongitude\":0}");
                for (String age : List.of("-1", "1.5", "30001", "1e309", "\"0\"", "null"))
                    first.send(JSON_PREFIX + "{\"gpslatitude\":48,\"gpslongitude\":-123,\"gpsage\":" + age + "}");
                first.probeClock("");
                require(closeTo(jsonNumber(get("/api/state"), "latitude"), expected[0]),
                        "invalid or mismatched offsets replaced the valid fix");
                first.send(JSON_PREFIX + "{\"gpslatitude\":47.7123456789,\"gpslongitude\":-122.2123456789}");
                first.probeClock("");
                require(second.readUntil(frame -> gpsFrameContainsPlayer(frame, 1)
                        && frame.contains("\"gpslatitude\":47.7123456789")) != null,
                        "out-of-area GPS did not retain absolute precision");
                first.send(JSON_PREFIX + "{\"gpsoffset\":" + originJson + ",\"gpsn\":0,\"gpse\":0}");
                first.probeClock("");
                require(closeTo(jsonNumber(get("/api/state"), "latitude"), 47.6),
                        "zero offsets were confused with Null Island");
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "GPS test round could not start");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                String token = jsonString(start, "roundtoken");
                first.sendStateGossip(token, 88, 17, 4, origin, -30001, 31002);
                require(first.receivesAuthorityGps(token, -30001, 31002),
                        "binary GPS state did not round-trip through the laptop host");
                require(first.receivesAuthorityGps(token, -30001, 31002, 1),
                        "cached authority ticks kept an old GPS fix permanently fresh");
                first.sendStateGossip(token, 89, 18, 4, GpsOffset.create(47.6001, -122.1), 0, 0);
                require(first.receivesAuthorityState(token, 89, 18, false),
                        "foreign-origin GPS was relayed, or its combat state was discarded");
                first.sendStateGossip(token, 90, 19, 4, origin, 0, 0);
                require(first.receivesAuthorityGps(token, 0, 0),
                        "valid GPS did not recover after a foreign-origin packet");
                String state = get("/api/state");
                long ageMs = (long) jsonNumber(currentPlayerState(state, 1), "gpsAgeMs");
                Thread.sleep(Math.max(1, 15_050 - ageMs));
                state = get("/api/state");
                require(!Double.isFinite(jsonNumber(currentPlayerState(state, 1), "latitude")),
                        "dashboard kept a stale GPS fix as a current marker");
                require(state.contains("\"trail\":[{\"longitude\":-122.1,\"latitude\":47.6}"),
                        "expired GPS deleted the player's historical trail");
                second.send(JSON_PREFIX + "{\"telemetry\":\"hit\",\"attacker\":1}");
                second.probeClock("");
                state = get("/api/state");
                require(!state.contains("\"shooterLatitude\":") && !state.contains("\"targetLatitude\":"),
                        "new hit lines used stale or missing GPS coordinates");
                first.send(JSON_PREFIX + "{\"gpslatitude\":47.8,\"gpslongitude\":-122.3,\"gpsage\":20000}");
                first.probeClock("");
                state = get("/api/state");
                require(jsonNumber(currentPlayerState(state, 1), "gpsAgeMs") >= 20000
                                && !Double.isFinite(jsonNumber(currentPlayerState(state, 1), "latitude")),
                        "an already stale measurement was renewed into a current marker");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) host.destroyForcibly();
        }
    }

    private static double jsonNumber(String text, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"" + key + "\":([-0-9.eE+]+)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.NaN;
    }

    private static String currentPlayerState(String state, int playerID) {
        String prefix = "{\"id\":" + playerID + ",";
        int begin = state.indexOf(prefix);
        int end = begin < 0 ? -1 : state.indexOf("\"trail\":", begin);
        require(end >= 0, "dashboard did not contain player " + playerID);
        return state.substring(begin, end);
    }

    private static boolean closeTo(double actual, double expected) {
        return Math.abs(actual - expected) < 1e-8;
    }

    private static void verifyNormalStartCountdown(boolean explicitOverride) throws Exception {
        require(GameStartCountdown.DURATION_SECONDS == 23 && GameStartCountdown.DURATION_MS == 23_000,
                "the shared default start countdown must be twenty-three seconds");
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver",
                "-cp", classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT)));
        if (explicitOverride) {
            command.add("--start-delay");
            command.add("1");
        }
        Process host = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                String before = first.probeClock("");
                require(post("/api/start").contains("\"ok\":true"),
                        "normal countdown could not start");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                require(start != null, "normal countdown was not sent");
                requireCountdownDuration(first, before, start,
                        explicitOverride ? 1_000 : GameStartCountdown.DURATION_MS);
                String otherStart = second.readUntil(frame -> frame.contains("\"gamestart\""));
                require(otherStart != null && jsonNumber(start, "gamestart")
                                == jsonNumber(otherStart, "gamestart"),
                        "phones did not receive the same start deadline");
                if (!explicitOverride) {
                    String state = get("/api/state");
                    require(state.contains("\"state\":\"countdown\"")
                                    && jsonNumber(state, "startInMs") > 0
                                    && jsonNumber(state, "startInMs") <= GameStartCountdown.DURATION_MS,
                            "normal default countdown was not twenty-three seconds");
                }
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) host.destroyForcibly();
        }
    }

    private static void verifyPowerupRequests(int required) throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver",
                "-cp", classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1"));
        if (required > 0) {
            command.add("--powerup-qr");
            command.add(String.valueOf(required));
        }
        Process host = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone teammate = new FakePhone("127.0.0.4", 2, "Blue Teammate");
                 FakePhone enemy = new FakePhone("127.0.0.2", 17, "Red")) {
                require(first.lastRoster.contains("\"powerupqrrequired\":" + required),
                        "the two-to-eight-code setting was not advertised");
                first.synchronizeClock();
                teammate.synchronizeClock();
                enemy.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "power-up round did not start");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                String token = jsonString(start, "roundtoken");
                require(token != null, "power-up test did not receive a round identity");
                sendPowerupScan(first, token, "255", "0");
                require(!first.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                        "a countdown scan earned a reward before actual play");
                long runningDeadline = System.nanoTime() + 2_000_000_000L;
                while (!get("/api/state").contains("\"state\":\"running\"")
                        && System.nanoTime() < runningDeadline) Thread.sleep(25);
                require(get("/api/state").contains("\"state\":\"running\""),
                        "power-up test round did not enter active play");
                if (required == 0) {
                    sendPowerupScan(first, token, "255", "0");
                    require(!first.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                            "disabled power-ups still granted a reward");
                    return;
                }
                for (String mask : List.of("0", "256", "1.5", "null", "\"255\""))
                    sendPowerupScan(first, token, mask, "0");
                sendPowerupScan(first, UUID.randomUUID().toString(), "255", "0");
                for (String life : List.of("-1", "1", "0.5", "null", "\"0\""))
                    sendPowerupScan(first, token, "255", life);
                int partialMask = (1 << (required - 1)) - 1;
                sendPowerupScan(first, token, String.valueOf(partialMask), "0");
                sendPowerupScan(first, token, String.valueOf(partialMask), "0");
                require(!first.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                        "malformed, stale, repeated, or incomplete scans granted a reward");
                int completedMask = (1 << required) - 1;
                sendPowerupScan(first, token, String.valueOf(completedMask), "0");
                String grant = first.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                require(grant != null, "the completed distinct scan set did not earn a reward");
                assertPowerupGrant(grant, token, 0);
                double id = jsonNumber(powerupGrantObject(grant), "id");
                double expiry = jsonNumber(powerupGrantObject(grant), "expires");
                String teammateGrant = teammate.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                String enemyGrant = enemy.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                require(teammateGrant != null && enemyGrant != null
                                && jsonNumber(powerupGrantObject(teammateGrant), "id") == id
                                && jsonNumber(powerupGrantObject(enemyGrant), "id") == id,
                        "the host did not send the same grant to every phone");
                sendPowerupScan(teammate, token, "255", "0");
                long lastBulkId = (long) id;
                for (int credit = 0; credit < 8 / required; credit++) {
                    String bulk = teammate.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                    require(bulk != null && jsonNumber(powerupGrantObject(bulk), "source") == 2
                                    && jsonNumber(powerupGrantObject(bulk), "id") > lastBulkId,
                            "the host did not drain each earned credit from a catch-up mask");
                    lastBulkId = (long) jsonNumber(powerupGrantObject(bulk), "id");
                    final long expectedId = lastBulkId;
                    require(first.readUntil(frame -> isPowerupGrant(frame)
                                    && jsonNumber(powerupGrantObject(frame), "id") == expectedId) != null
                                    && enemy.readUntil(frame -> isPowerupGrant(frame)
                                    && jsonNumber(powerupGrantObject(frame), "id") == expectedId) != null,
                            "a catch-up grant was not broadcast to all phones");
                }
                sendPowerupScan(teammate, token, "255", "0");
                require(!teammate.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                        "the same catch-up mask earned extra rewards");
                first.close();
                String identity = UUID.nameUUIDFromBytes("127.0.0.3:1".getBytes(StandardCharsets.UTF_8)).toString();
                try (FakePhone returned = new FakePhone("127.0.0.3", 1, "Blue Reconnected", identity, 0, true)) {
                    String snapshot = returned.lastRoster;
                    require(snapshot.contains("\"powerupgrants\":[{\"id\":" + (long) id + ",")
                                    && jsonNumber(snapshot, "expires") == expiry
                                    && snapshot.contains("\"powerupstate\":{\"nextid\":")
                                    && snapshot.contains("\"mask\":" + completedMask + ",\"earned\":1"),
                            "reconnect lost original expiry or the consumed scan set");
                    sendPowerupScan(returned, token, String.valueOf(completedMask), "0");
                    require(!returned.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                            "reconnect awarded the completed set twice");
                    returned.close();
                    try (FakePhone qrReturned = new FakePhone("127.0.0.3", 1, "Blue QR Resume", identity, 1, true)) {
                        qrReturned.sendStateGossip(token, 2, 0);
                        require(qrReturned.receivesAuthorityState(token, 2, 0),
                                "the QR-resumed player's living state did not arrive");
                        sendPowerupScan(qrReturned, token, String.valueOf(completedMask), "0");
                        require(!qrReturned.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                                "same-identity QR resume awarded the completed set twice");
                        qrReturned.send(MESSAGE_PREFIX + "ELIMINATED17");
                        qrReturned.probeClock("");
                        sendPowerupScan(qrReturned, token, "255", "1");
                        require(!qrReturned.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                                "an eliminated player earned a reward");
                        qrReturned.send(MESSAGE_PREFIX + "RESPAWNREQUEST");
                        require(qrReturned.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                                "the power-up test player did not respawn");
                        sendPowerupScan(qrReturned, token, "255", "0");
                        sendPowerupScan(qrReturned, token, String.valueOf(partialMask), "1");
                        require(!qrReturned.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                                "death retained old progress or accepted the previous life");
                        sendPowerupScan(qrReturned, token, String.valueOf(completedMask), "1");
                        String nextGrant = qrReturned.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                        require(nextGrant != null && jsonNumber(powerupGrantObject(nextGrant), "id") > id,
                                "a fresh-life completed set did not get a new grant identity");
                        assertPowerupGrant(nextGrant, token, 1);
                    }
                }
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) host.destroyForcibly();
        }
    }

    private static void sendPowerupScan(FakePhone phone, String token, String mask, String life) throws IOException {
        phone.send(JSON_PREFIX + "{\"powerupscan\":" + mask + ",\"poweruplife\":" + life
                + ",\"roundtoken\":\"" + token + "\"}");
    }

    private static boolean isPowerupGrant(String frame) {
        return frame.startsWith(JSON_PREFIX) && frame.contains("\"powerupgrant\":{");
    }

    private static String powerupGrantObject(String frame) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"powerupgrant\":(\\{[^}]*\\})").matcher(frame);
        require(matcher.find(), "frame did not contain a live power-up grant");
        return matcher.group(1);
    }

    private static void assertPowerupGrant(String frame, String token, int life) throws Exception {
        assertPowerupGrant(frame, token, 1, life);
    }

    private static void assertPowerupGrant(String frame, String token, int source, int life) throws Exception {
        String grant = powerupGrantObject(frame);
        int kind = (int) jsonNumber(grant, "kind");
        require(kind >= Powerups.EMP && kind <= Powerups.AIRSTRIKE && jsonNumber(grant, "id") > 0
                        && jsonNumber(grant, "source") == source && jsonNumber(grant, "sourcelife") == life
                        && token.equals(jsonString(frame, "roundtoken")),
                "the reward did not contain a valid source, life, kind, and round");
        require(jsonNumber(grant, "cost") >= 2 && jsonNumber(grant, "cost") <= 8,
                "the reward did not retain its original scan cost");
        require(jsonNumber(grant, "expires") - jsonNumber(frame, "powerupclock") == Powerups.durationMillis(kind),
                "the reward expiry did not use the authoritative duration");
        Powerups.Grant decoded = readPowerupGrant(grant);
        boolean enemyWide = kind == Powerups.EMP || kind == Powerups.DAMAGE_TEAM || kind == Powerups.AIRSTRIKE;
        boolean teammateWide = kind == Powerups.SHIELD_TEAM || kind == Powerups.HEAL_TEAM;
        int[] expectedTargets = enemyWide ? new int[]{17}
                : teammateWide ? new int[]{1, 2} : new int[]{source};
        require(java.util.Arrays.equals(decoded.targets, expectedTargets),
                "the reward used the wrong team targets");
        int[] expectedLives = enemyWide ? new int[]{0}
                : teammateWide ? source == 1 ? new int[]{life, 0} : new int[]{0, life}
                : new int[]{life};
        require(java.util.Arrays.equals(decoded.targetLives, expectedLives),
                "the reward lost the targets' original lives");
        require(grant.contains("\"recipients\":") && !grant.contains("\"targets\":")
                        && !grant.contains("\"lives\":"),
                "the laptop did not emit only compact recipients");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> parseHostObject(String text) throws Exception {
        Class<?> jsonClass = Class.forName("com.simplecoil.laptophost.LaptopHost$Json");
        java.lang.reflect.Method parse = jsonClass.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        return (java.util.Map<String, Object>) parse.invoke(null, text);
    }

    private static long hostJsonNumber(java.util.Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing numeric " + field);
        return ((Number) value).longValue();
    }

    /** The laptop emits grants; this phone-side smoke decoder also accepts legacy recipient arrays. */
    private static Powerups.Grant readPowerupGrant(String text) throws Exception {
        java.util.Map<String, Object> object = parseHostObject(text);
        int[] targets, lives;
        if (object.containsKey("recipients")) {
            if (object.containsKey("targets") || object.containsKey("lives")
                    || !(object.get("recipients") instanceof String))
                throw new IllegalArgumentException("Ambiguous compact power-up recipients");
            Powerups.Recipients recipients = Powerups.decodeRecipients((String) object.get("recipients"));
            targets = recipients.targets;
            lives = recipients.targetLives;
        } else {
            Object targetValue = object.get("targets"), lifeValue = object.get("lives");
            if (!(targetValue instanceof List) || !(lifeValue instanceof List))
                throw new IllegalArgumentException("Missing legacy power-up recipients");
            List<?> targetList = (List<?>) targetValue, lifeList = (List<?>) lifeValue;
            if (targetList.size() != lifeList.size() || targetList.isEmpty() || targetList.size() > 32)
                throw new IllegalArgumentException("Invalid legacy power-up recipients");
            targets = new int[targetList.size()];
            lives = new int[targets.length];
            for (int index = 0; index < targets.length; index++) {
                if (!(targetList.get(index) instanceof Number) || !(lifeList.get(index) instanceof Number))
                    throw new IllegalArgumentException("Invalid legacy power-up recipient");
                targets[index] = ((Number) targetList.get(index)).intValue();
                lives[index] = ((Number) lifeList.get(index)).intValue();
            }
            // Canonical validation is shared with the compact encoder.
            Powerups.Recipients recipients = Powerups.decodeRecipients(Powerups.encodeRecipients(
                    new Powerups.Grant(1, 1, 0, Powerups.SHIELD_TEAM, targets, lives, 1, 2)));
            targets = recipients.targets;
            lives = recipients.targetLives;
        }
        return new Powerups.Grant(hostJsonNumber(object, "id"), (int) hostJsonNumber(object, "source"),
                (int) hostJsonNumber(object, "sourcelife"), (int) hostJsonNumber(object, "kind"),
                targets, lives, hostJsonNumber(object, "expires"), (int) hostJsonNumber(object, "cost"));
    }

    private static String powerupWireObject(Powerups.Grant grant) throws Exception {
        java.lang.reflect.Method grantMap = LaptopHost.class.getDeclaredMethod("powerupGrantMap", Powerups.Grant.class);
        grantMap.setAccessible(true);
        java.lang.reflect.Method jsonFrame = LaptopHost.class.getDeclaredMethod("jsonFrame", Object.class);
        jsonFrame.setAccessible(true);
        return ((String) jsonFrame.invoke(null, grantMap.invoke(null, grant))).substring(JSON_PREFIX.length());
    }

    private static void verifyPowerupRecipientWire() throws Exception {
        Powerups.Grant original = new Powerups.Grant(1_000_000_000_000_000_123L, 32, Integer.MAX_VALUE / 32,
                Powerups.SHIELD_TEAM, new int[]{32, 1, 17}, new int[]{Integer.MAX_VALUE, 0, 67_108_863},
                1_000_000_000_000_010_000L, 2);
        String compact = powerupWireObject(original);
        Powerups.Grant decoded = readPowerupGrant(compact);
        require(decoded.id == original.id && decoded.expiresAt == original.expiresAt
                        && decoded.source == original.source && decoded.sourceLife == original.sourceLife
                        && decoded.kind == original.kind && decoded.scanCost == original.scanCost
                        && java.util.Arrays.equals(decoded.targets, new int[]{1, 17, 32})
                        && java.util.Arrays.equals(decoded.targetLives, new int[]{0, 67_108_863, Integer.MAX_VALUE}),
                "compact recipient round trip changed a grant or its corresponding lives");
        String legacy = "{\"id\":1,\"source\":1,\"sourcelife\":0,\"kind\":3,"
                + "\"targets\":[32,1],\"lives\":[67108863,0],\"expires\":15000,\"cost\":2}";
        Powerups.Grant legacyGrant = readPowerupGrant(legacy);
        require(java.util.Arrays.equals(legacyGrant.targets, new int[]{1, 32})
                        && java.util.Arrays.equals(legacyGrant.targetLives, new int[]{0, 67_108_863}),
                "legacy recipient arrays lost their corresponding life generations");
        for (String ambiguous : new String[]{compact.substring(0, compact.length() - 1) + ",\"targets\":[1]}",
                compact.substring(0, compact.length() - 1) + ",\"lives\":[0]}"}) {
            boolean rejected = false;
            try { readPowerupGrant(ambiguous); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "mixed compact and legacy recipients were accepted");
        }
    }

    private static void verifyAirstrikeHostWireSnapshot() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--powerup-qr", "2"});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method stop = LaptopHost.class.getDeclaredMethod("stop");
        stop.setAccessible(true);
        try {
            long clock = 1000;
            Powerups.Grant original = new Powerups.Grant(1, 1, 0, Powerups.AIRSTRIKE,
                    new int[]{17, 18}, new int[]{0, 2}, clock + Powerups.INSTANT_DELIVERY_MS, 2);
            List<Powerups.ScanState> players = List.of(new Powerups.ScanState(1, 0, 3, 1, 2, 2),
                    new Powerups.ScanState(17, 0, 0, 0, 0, 0), new Powerups.ScanState(18, 2, 0, 0, 0, 0));
            java.lang.reflect.Field authorityField = LaptopHost.class.getDeclaredField("powerupAuthority");
            authorityField.setAccessible(true);
            Powerups.Authority authority = (Powerups.Authority) authorityField.get(host);
            require(authority.restore(new Powerups.Snapshot(2, clock, List.of(original), players), clock),
                    "the laptop could not restore a current-life Airstrike");
            String liveWire = powerupWireObject(original);
            Powerups.Grant live = readPowerupGrant(liveWire);
            require(live.kind == Powerups.AIRSTRIKE && Powerups.isInstant(live.kind)
                            && live.expiresAt - clock == 3000
                            && java.util.Arrays.equals(live.targets, new int[]{17, 18})
                            && java.util.Arrays.equals(live.targetLives, new int[]{0, 2}),
                    "Airstrike live wire lost its instant delivery window or recipient lives");
            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            java.lang.reflect.Method putSnapshot = LaptopHost.class.getDeclaredMethod(
                    "putPowerupSnapshotLocked", java.util.Map.class, long.class);
            putSnapshot.setAccessible(true);
            putSnapshot.invoke(host, snapshot, 2000L);
            String token = UUID.randomUUID().toString();
            snapshot.put("roundtoken", token);
            java.lang.reflect.Method jsonFrame = LaptopHost.class.getDeclaredMethod("jsonFrame", Object.class);
            jsonFrame.setAccessible(true);
            String frame = (String) jsonFrame.invoke(null, snapshot);
            require(frame.startsWith(JSON_PREFIX) && token.equals(jsonString(frame, "roundtoken"))
                            && frame.contains("\"kind\":" + Powerups.AIRSTRIKE)
                            && jsonNumber(frame, "expires") == 4000
                            && jsonNumber(frame, "powerupclock") == 2000,
                    "Airstrike reconnect snapshot lost kind, original expiry, clock, or round scope");
            java.util.Map<String, Object> decoded = parseHostObject(frame.substring(JSON_PREFIX.length()));
            List<?> grants = (List<?>) decoded.get("powerupgrants");
            require(grants.size() == 1, "the Airstrike snapshot changed its grant count");
            String grantWire = ((String) jsonFrame.invoke(null, grants.get(0))).substring(JSON_PREFIX.length());
            Powerups.Grant recoveredGrant = readPowerupGrant(grantWire);
            Powerups.Snapshot recovery = new Powerups.Snapshot(2, 2000, List.of(recoveredGrant), players);
            Powerups.Authority nextHost = new Powerups.Authority();
            require(nextHost.restore(recovery, 50_000), "Airstrike wire recovery failed validation");
            require(nextHost.activeGrants(50_000).get(0).expiresAt == 52_000,
                    "Airstrike recovery restarted the full delivery window");
            require(nextHost.restore(recovery, 51_000)
                            && nextHost.activeGrants(51_000).get(0).expiresAt == 52_000,
                    "Airstrike replay refreshed its delivery expiry");
            require(nextHost.activeGrants(52_000).isEmpty()
                            && nextHost.restore(recovery, 53_000)
                            && nextHost.activeGrants(53_000).isEmpty(),
                    "Airstrike replay resurrected an expired grant");
        } finally {
            stop.invoke(host);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyPowerupStandaloneFrameBound() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--powerup-qr", "2"});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method stop = LaptopHost.class.getDeclaredMethod("stop");
        stop.setAccessible(true);
        try {
            require(Powerups.MAX_ACTIVE_GRANTS == 128, "recovery no longer retains all four rewards for 32 lives");
            int maxLife = Integer.MAX_VALUE / 32;
            long clock = 1_000_000_000_000_000_000L;
            long firstId = 1_000_000_000_000_000_001L;
            int[] targets = new int[32], lives = new int[32];
            List<Powerups.ScanState> players = new ArrayList<>();
            List<Powerups.Grant> grants = new ArrayList<>();
            for (int id = 1; id <= 32; id++) {
                targets[id - 1] = id;
                lives[id - 1] = maxLife;
                players.add(new Powerups.ScanState(id, maxLife, 255, 4, 2, 8));
            }
            for (int index = 0; index < Powerups.MAX_ACTIVE_GRANTS; index++)
                grants.add(new Powerups.Grant(firstId + index, index % 32 + 1, maxLife,
                        Powerups.SHIELD_TEAM, targets, lives, clock + Powerups.BUFF_MS, 2));
            java.lang.reflect.Field authorityField = LaptopHost.class.getDeclaredField("powerupAuthority");
            authorityField.setAccessible(true);
            Powerups.Authority authority = (Powerups.Authority) authorityField.get(host);
            require(authority.restore(new Powerups.Snapshot(firstId + grants.size(), clock, grants, players), clock),
                    "the 128-grant bound fixture could not be restored");
            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            java.lang.reflect.Method putSnapshot = LaptopHost.class.getDeclaredMethod(
                    "putPowerupSnapshotLocked", java.util.Map.class, long.class);
            putSnapshot.setAccessible(true);
            putSnapshot.invoke(host, snapshot, clock);
            snapshot.put("roundtoken", UUID.randomUUID().toString());
            java.lang.reflect.Method grantMap = LaptopHost.class.getDeclaredMethod("powerupGrantMap", Powerups.Grant.class);
            grantMap.setAccessible(true);
            snapshot.put("powerupgrant", grantMap.invoke(null, grants.get(grants.size() - 1)));
            java.lang.reflect.Method jsonFrame = LaptopHost.class.getDeclaredMethod("jsonFrame", Object.class);
            jsonFrame.setAccessible(true);
            String frame = (String) jsonFrame.invoke(null, snapshot);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) { output.writeUTF(frame); }
            require(bytes.size() - 2 < 60_000,
                    "128 all-player grants exceeded the standalone modified-UTF frame budget: " + (bytes.size() - 2));
            try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                require(frame.equals(input.readUTF()), "writeUTF/readUTF changed the standalone recovery frame");
            }
            java.util.Map<String, Object> decoded = parseHostObject(frame.substring(JSON_PREFIX.length()));
            List<?> grantObjects = (List<?>) decoded.get("powerupgrants");
            require(grantObjects.size() == 128 && hostJsonNumber(decoded, "powerupclock") == clock,
                    "standalone recovery lost active grants or the original 19-digit clock");
            List<Powerups.Grant> restored = new ArrayList<>();
            for (int index = 0; index < grantObjects.size(); index++) {
                String object = ((String) jsonFrame.invoke(null, grantObjects.get(index))).substring(JSON_PREFIX.length());
                Powerups.Grant grant = readPowerupGrant(object);
                require(grant.id == firstId + index && grant.expiresAt == clock + Powerups.BUFF_MS
                                && grant.targets.length == 32 && grant.targetLives[31] == maxLife
                                && jsonString(object, "recipients").length() == 264,
                        "standalone recovery changed a full-recipient grant");
                restored.add(grant);
            }
            Powerups.Authority recovered = new Powerups.Authority();
            require(recovered.restore(new Powerups.Snapshot(firstId + grants.size(), clock, restored, players), clock),
                    "compact standalone recovery failed authority validation");
            require(recovered.activeGrants(clock).size() == 128,
                    "compact standalone recovery evicted valid current-life rewards");
            System.out.println("128-grant standalone modified-UTF bytes: " + (bytes.size() - 2));
        } finally {
            stop.invoke(host);
        }
    }

    private static void verifyDynamicPowerupRequirement() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1", "--powerup-qr", "8")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone teammate = new FakePhone("127.0.0.4", 2, "Blue Teammate");
                 FakePhone enemy = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                teammate.synchronizeClock();
                enemy.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "adaptive power-up round did not start");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                String token = jsonString(start, "roundtoken");
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (!get("/api/state").contains("\"state\":\"running\"")
                        && System.nanoTime() < deadline) Thread.sleep(25);
                require(get("/api/state").contains("\"state\":\"running\""), "adaptive round stayed in countdown");
                sendPowerupScan(first, token, "255", "0");
                String initial = first.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                require(initial != null && jsonNumber(powerupGrantObject(initial), "cost") == 8,
                        "equal teams did not pay the configured eight-code cost");
                require(teammate.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant) != null
                                && enemy.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant) != null,
                        "initial adaptive reward was not broadcast");
                int previousScore = 0;
                for (int score : new int[]{5, 10, 30}) {
                    for (int kill = previousScore; kill < score; kill++) {
                        teammate.send(MESSAGE_PREFIX + "ELIMINATED17");
                        teammate.send(MESSAGE_PREFIX + "RESPAWNREQUEST");
                        require(teammate.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                                "score-gap fixture could not respawn its victim");
                    }
                    previousScore = score;
                    require(jsonNumber(currentPlayerState(get("/api/state"), 17), "kills") == score,
                            "the host did not retain the opponent's current team score");
                    int required = score == 5 ? 7 : score == 10 ? 6 : 2;
                    sendPowerupScan(first, token, "255", "0");
                    require(!first.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                            "a cheaper requirement reused the eight already-spent codes");
                    int partial = (1 << (required - 1)) - 1;
                    int complete = (1 << required) - 1;
                    sendPowerupScan(teammate, token, String.valueOf(partial), String.valueOf(score));
                    require(!teammate.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                            "the adaptive threshold granted too early");
                    sendPowerupScan(teammate, token, String.valueOf(complete), String.valueOf(score));
                    String grant = teammate.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                    require(grant != null && jsonNumber(powerupGrantObject(grant), "cost") == required
                                    && grant.contains("\"required\":" + required + ",\"spent\":" + required),
                            "the host did not apply the adaptive threshold or retain spent-code accounting");
                    assertPowerupGrant(grant, token, 2, score);
                    long id = (long) jsonNumber(powerupGrantObject(grant), "id");
                    require(first.readUntil(frame -> isPowerupGrant(frame)
                                    && jsonNumber(powerupGrantObject(frame), "id") == id) != null
                                    && enemy.readUntil(frame -> isPowerupGrant(frame)
                                    && jsonNumber(powerupGrantObject(frame), "id") == id) != null,
                            "an adaptive reward was not broadcast to every phone");
                }
                enemy.send(MESSAGE_PREFIX + "LEAVE");
                enemy.close();
                require(waitForDashboard(state -> currentPlayerState(state, 17)
                        .contains("\"connected\":false")) != null, "the opponent did not leave");
                sendPowerupScan(teammate, token, "7", "30");
                require(!teammate.receivesFor(LaptopHostProtocolSmokeTest::isPowerupGrant, 100),
                        "the departed opponent caused spent codes to be reused");
                sendPowerupScan(teammate, token, "15", "30");
                String afterDeparture = teammate.readUntil(LaptopHostProtocolSmokeTest::isPowerupGrant);
                require(afterDeparture != null && jsonNumber(powerupGrantObject(afterDeparture), "cost") == 2,
                        "departed opponent scores stopped counting toward the catch-up threshold");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) host.destroyForcibly();
        }
    }

    private static void verifyPowerupSnapshotFraming() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--powerup-qr", "2"});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method stop = LaptopHost.class.getDeclaredMethod("stop");
        stop.setAccessible(true);
        try {
            String token = UUID.randomUUID().toString();
            com.simplecoil.protocol.RoundRoster roster = new com.simplecoil.protocol.RoundRoster(token);
            int maxScore = Integer.MAX_VALUE / 32;
            for (int index = 0; index < com.simplecoil.protocol.RoundRoster.HISTORY_LIMIT; index++) {
                int id = index % 32 + 1;
                com.simplecoil.protocol.RoundRoster.Member member = new com.simplecoil.protocol.RoundRoster.Member(
                        UUID.randomUUID().toString(), id, id <= 16 ? 1 : 2, index / 32);
                member.kills = member.deaths = maxScore;
                member.captures = 255;
                member.active = false;
                roster.restore(member);
            }
            setHostField(host, "roundRoster", roster);
            setHostField(host, "roundToken", token);
            Object running = java.util.Arrays.stream(Class.forName(
                    "com.simplecoil.laptophost.LaptopHost$RoundState").getEnumConstants())
                    .filter(value -> "RUNNING".equals(value.toString())).findFirst().orElseThrow();
            setHostField(host, "roundState", running);
            Class<?> settingsClass = Class.forName("com.simplecoil.laptophost.LaptopHost$Settings");
            java.lang.reflect.Method settingsMethod = settingsClass.getDeclaredMethod("tournament");
            settingsMethod.setAccessible(true);
            Class<?> playerClass = Class.forName("com.simplecoil.laptophost.LaptopHost$Player");
            java.lang.reflect.Constructor<?> playerConstructor = playerClass.getDeclaredConstructor(
                    int.class, String.class, InetAddress.class, settingsClass);
            playerConstructor.setAccessible(true);
            java.lang.reflect.Field playersField = LaptopHost.class.getDeclaredField("players");
            playersField.setAccessible(true);
            Object hostPlayers = playersField.get(host);
            java.lang.reflect.Method putPlayer = java.util.Map.class.getMethod("put", Object.class, Object.class);
            for (int id = 1; id <= 32; id++) {
                Object player = playerConstructor.newInstance(id, String.format(java.util.Locale.ROOT, "%020d", id),
                        InetAddress.getByName("127.0.0." + id), settingsMethod.invoke(null));
                setHostField(player, "connected", true);
                setHostField(player, "identity", roster.seat(id).identity);
                setHostField(player, "kills", maxScore);
                setHostField(player, "deaths", maxScore);
                putPlayer.invoke(hostPlayers, id, player);
            }
            java.lang.reflect.Field authorityField = LaptopHost.class.getDeclaredField("powerupAuthority");
            authorityField.setAccessible(true);
            Powerups.Authority authority = (Powerups.Authority) authorityField.get(host);
            java.util.Map<Integer, Integer> teams = new java.util.HashMap<>();
            java.util.Set<Integer> alive = new java.util.HashSet<>();
            for (int id = 1; id <= 32; id++) {
                authority.setPlayerLife(id, maxScore);
                teams.put(id, id <= 16 ? 1 : 2);
                alive.add(id);
            }
            java.util.Random empOnly = new java.util.Random(0) {
                @Override public int nextInt(int bound) { return 0; }
            };
            for (int id = 1; id <= 32; id++)
                for (int credit = 0; credit < 4; credit++)
                    require(authority.request(id, maxScore, 255, 2, teams.get(id), teams,
                            java.util.Collections.emptyMap(), alive, 0, empOnly) != null,
                            "large snapshot fixture did not earn its grant");
            java.lang.reflect.Method framesMethod = LaptopHost.class.getDeclaredMethod("rosterFrames", int.class);
            framesMethod.setAccessible(true);
            Object delivered = framesMethod.invoke(host, 0);
            require(delivered instanceof List, "roster delivery did not return framed snapshots");
            List<?> frames = (List<?>) delivered;
            require(frames.size() == 2 && ((String) frames.get(0)).contains("\"roundroster\":")
                            && !((String) frames.get(0)).contains("\"powerupgrants\":")
                            && ((String) frames.get(1)).contains("\"powerupgrants\":[")
                            && token.equals(jsonString((String) frames.get(1), "roundtoken")),
                    "oversized roster and active grants were not split without losing round scope");
            try (DataOutputStream output = new DataOutputStream(new java.io.ByteArrayOutputStream())) {
                for (Object frame : frames) output.writeUTF((String) frame);
            }
        } finally {
            stop.invoke(host);
        }
    }

    private static void setHostField(Object host, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = host.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(host, value);
    }

    private static Object getHostField(Object host, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = host.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(host);
    }

    @SuppressWarnings("unchecked")
    private static void verifyRoundReports() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Path directory = Files.createTempDirectory("simplecoil-round-reports");
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--no-browser", "--start-delay", "1",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--tile-port", String.valueOf(TILE_PORT),
                "--reports", directory.toString()});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method startHost = LaptopHost.class.getDeclaredMethod("start");
        java.lang.reflect.Method stopHost = LaptopHost.class.getDeclaredMethod("stop");
        java.lang.reflect.Method endRound = LaptopHost.class.getDeclaredMethod("endRound", String.class);
        startHost.setAccessible(true); stopHost.setAccessible(true); endRound.setAccessible(true);
        String firstIdentity = UUID.randomUUID().toString(), secondIdentity = UUID.randomUUID().toString();
        String completed;
        String frozen;
        try {
            startHost.invoke(host); waitForHost();
            require(responseCode("/api/report") == 404, "unfinished round exposed a final report");
            require(get("/api/reports").contains("\"reports\":[]"), "fresh host invented a report");
            String page = get("/round-report");
            require(page.contains("<svg id=\"timeline\"") && page.contains("createElementNS")
                            && page.contains("Timeline events") && !page.contains("innerHTML"),
                    "round review did not provide a safe, filterable SVG timeline");
            require(responseCode("/api/report?roundtoken=../../README.md") == 404,
                    "report lookup accepted a non-UUID path");
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue \"<script>\\", firstIdentity, 0, false, true);
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red", secondIdentity, 0, false, true)) {
                first.send(JSON_PREFIX + "{\"telemetry\":\"shot\",\"count\":99}");
                first.synchronizeClock(); second.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "report fixture did not start");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\"")
                        && !frame.contains("\"players\""));
                completed = jsonString(start, "roundtoken");
                sendScopedTelemetry(first, completed, 99, 0, "shot", ",\"total\":99,\"count\":99");
                first.probeClock("");
                require(waitForDashboard(s -> s.contains("\"state\":\"running\"")) != null,
                        "report fixture did not finish countdown");
                // Rejected bodies must not consume sequence 1 or invent an
                // unknown-source death, allowing the next valid frame through.
                for (String bad : new String[]{"", ",\"attacker\":\"1\"", ",\"attacker\":1.5",
                        ",\"attacker\":false", ",\"attacker\":null", ",\"attacker\":999"})
                    sendScopedTelemetry(second, completed, 1, 0, "elimination", bad);
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":10,\"count\":\"10\"");
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":10,\"count\":0");
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":10,\"count\":4294967306");
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":4294967306,\"count\":10");
                sendScopedTelemetry(second, completed, 1, 0, "grenadehit", ",\"grenadeID\":\"1\"");
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":10,\"count\":10");
                sendScopedTelemetry(first, completed, 1, 0, "shot", ",\"total\":10,\"count\":10");
                sendScopedTelemetry(first, UUID.randomUUID().toString(), 2, 0, "shot", ",\"total\":99,\"count\":89");
                sendScopedTelemetry(second, completed, 1, 0, "hit", ",\"attacker\":1");
                sendScopedTelemetry(second, completed, 1, 0, "hit", ",\"attacker\":1");
                sendScopedTelemetry(second, completed, 2, 0, "elimination", ",\"attacker\":1");
                sendScopedTelemetry(second, completed, 2, 0, "elimination", ",\"attacker\":1");
                second.probeClock("");
                second.send(MESSAGE_PREFIX + "RESPAWNCOMPLETE"); second.probeClock("");
                sendScopedTelemetry(second, completed, 3, 1, "elimination", ",\"attacker\":1");
                second.probeClock("");
                second.send(MESSAGE_PREFIX + "RESPAWNCOMPLETE"); second.probeClock("");
                sendScopedTelemetry(first, completed, 2, 0, "elimination", ",\"attacker\":17");
                first.probeClock("");
                require(!get("/api/state").contains("\"events\""), "high-rate live state included the full timeline");
                endRound.invoke(host, "report regression");
                String[] firstBeforeReport = {null};
                String personal = first.readUntil(frame -> {
                    if (frame.contains("\"roundreport\"")) return true;
                    firstBeforeReport[0] = frame;
                    return false;
                });
                require(personal != null && personal.contains("\"bestkillstreak\":2")
                                && personal.contains("\"skillafter\":107"),
                        "personal final report did not arrive before shutdown with streak / skill stats");
                require(firstBeforeReport[0] != null && firstBeforeReport[0].startsWith(JSON_PREFIX)
                                && firstBeforeReport[0].contains("\"playerdata\""),
                        "frozen final scoreboard did not immediately precede the personal report");
                List<java.util.Map<String, Object>> finalScores = (List<java.util.Map<String, Object>>)
                        parseHostObject(firstBeforeReport[0].substring(JSON_PREFIX.length())).get("playerdata");
                java.util.Map<String, Object> finalBlue = finalScores.stream().filter(p -> hostJsonNumber(p, "playerID") == 1)
                        .findFirst().orElseThrow();
                java.util.Map<String, Object> finalRed = finalScores.stream().filter(p -> hostJsonNumber(p, "playerID") == 17)
                        .findFirst().orElseThrow();
                require(hostJsonNumber(finalBlue, "points") == 2 && hostJsonNumber(finalBlue, "eliminated") == 1
                                && hostJsonNumber(finalRed, "points") == 1 && hostJsonNumber(finalRed, "eliminated") == 2,
                        "frozen final scoreboard omitted the last elimination / disagreed with the report");
                require(first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ENDGAME")) != null,
                        "ENDGAME did not follow the personal report");
                String[] secondBeforeReport = {null};
                require(second.readUntil(frame -> {
                    if (frame.contains("\"roundreport\"")) return true;
                    secondBeforeReport[0] = frame;
                    return false;
                }) != null && firstBeforeReport[0].equals(secondBeforeReport[0])
                                && second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ENDGAME")) != null,
                        "not every client received the same final scoreboard before its report / ENDGAME");
                frozen = get("/api/report?roundtoken=" + completed);
                java.util.Map<String, Object> report = parseHostObject(frozen);
                List<java.util.Map<String, Object>> people = (List<java.util.Map<String, Object>>) report.get("players");
                java.util.Map<String, Object> blue = people.get(0), red = people.get(1);
                require(hostJsonNumber(blue, "kills") == 2 && hostJsonNumber(blue, "deaths") == 1
                                && hostJsonNumber(blue, "shots") == 10 && hostJsonNumber(blue, "hits") == 1
                                && ((Number) blue.get("accuracy")).doubleValue() == 10.0
                                && hostJsonNumber(blue, "bestkillstreak") == 2
                                && hostJsonNumber(red, "bestdeathstreak") == 2 && red.get("accuracy") == null,
                        "round report counters, streaks, replay protection or zero-shot accuracy failed");
                require("Blue \"<script>\\".equals(blue.get("name")), "report JSON mangled an escaped name");
                require(Boolean.TRUE.equals(report.get("started")) && Boolean.TRUE.equals(report.get("complete")),
                        "complete scoped round was marked missing or never started");
                List<java.util.Map<String, Object>> events = (List<java.util.Map<String, Object>>) report.get("events");
                require(events.stream().filter(e -> "kill".equals(e.get("kind"))).count() == 3
                                && events.stream().anyMatch(e -> "respawn".equals(e.get("kind")))
                                && events.stream().allMatch(e -> hostJsonNumber(e, "timeMs") >= 0
                                    && hostJsonNumber(e, "timeMs") <= hostJsonNumber(report, "durationMs")),
                        "timeline duplicated an elimination, missed a respawn or used countdown time");
            }
            require(waitForDashboard(s -> s.contains("\"playerCount\":0")) != null, "end-game sockets did not close");
            try (FakePhone first = new FakePhone("127.0.0.3", 5, "Blue next", firstIdentity, 0);
                 FakePhone second = new FakePhone("127.0.0.2", 21, "Red next", secondIdentity, 0)) {
                require(frozen.equals(get("/api/report?roundtoken=" + completed)), "new lobby reset frozen report");
                first.synchronizeClock(); second.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "second report fixture did not start");
                require(frozen.equals(get("/api/report?roundtoken=" + completed)), "new round reset frozen report");
                endRound.invoke(host, "cancelled countdown");
                require(frozen.equals(get("/api/report")), "cancelled countdown replaced the completed report");
            }
            for (int attempt = 0; attempt < 50 && !Files.exists(directory.resolve(completed + ".json")); attempt++)
                Thread.sleep(20);
            require(Files.readString(directory.resolve(completed + ".json")).equals(frozen),
                    "saved report differed from final dashboard report");
            ((java.util.Deque<?>) getHostField(host, "roundReports")).clear();
            require(frozen.equals(get("/api/report?roundtoken=" + completed)), "saved report was not retrievable");
        } finally { stopHost.invoke(host); }
        LaptopHost restarted = constructor.newInstance(config);
        try {
            startHost.invoke(restarted); waitForHost();
            require(get("/api/reports").contains(completed) && get("/api/report").equals(frozen),
                    "restart did not reload the completed report");
        } finally { stopHost.invoke(restarted); }
    }

    @SuppressWarnings("unchecked")
    private static void verifyQueuedSourceSlotReuse() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--no-browser", "--start-delay", "1",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--tile-port", String.valueOf(TILE_PORT),
                "--reports", Files.createTempDirectory("simplecoil-slot-reuse-reports").toString()});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method start = LaptopHost.class.getDeclaredMethod("start");
        java.lang.reflect.Method stop = LaptopHost.class.getDeclaredMethod("stop");
        java.lang.reflect.Method end = LaptopHost.class.getDeclaredMethod("endRound", String.class);
        start.setAccessible(true); stop.setAccessible(true); end.setAccessible(true);
        try {
            start.invoke(host); waitForHost();
            try (FakePhone old = new FakePhone("127.0.0.3", 1, "Original source", UUID.randomUUID().toString(), 0, false, true);
                 FakePhone victim = new FakePhone("127.0.0.2", 17, "Victim", UUID.randomUUID().toString(), 0, false, true)) {
                old.synchronizeClock(); victim.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"), "slot-reuse round did not start");
                String token = jsonString(old.readUntil(frame -> frame.contains("\"gamestart\"")
                        && !frame.contains("\"players\"")), "roundtoken");
                require(waitForDashboard(s -> s.contains("\"state\":\"running\"")) != null,
                        "slot-reuse countdown did not finish");
                sendScopedTelemetry(old, token, 1, 0, "shot", ",\"total\":4,\"count\":4");
                old.probeClock("");
                old.send(MESSAGE_PREFIX + "LEAVE");
                require(waitForDashboard(s -> currentPlayerState(s, 1).contains("\"connected\":false")) != null,
                        "original source did not vacate its slot");
                String replacementIdentity = UUID.randomUUID().toString();
                try (FakePhone replacement = new FakePhone("127.0.0.8", 1, "Replacement source",
                        replacementIdentity, 1, false, true,
                        ",\"priorkills\":12,\"priordeaths\":1,\"historyroundtoken\":\"" + token
                                + "\",\"historybaselinekills\":10,\"historybaselinedeaths\":0");
                     FakePhone previousRound = new FakePhone("127.0.0.9", 18, "Prior baseline",
                        UUID.randomUUID().toString(), 2, false, true,
                        ",\"priorkills\":20,\"priordeaths\":4,\"historyroundtoken\":\"" + UUID.randomUUID()
                                + "\",\"historybaselinekills\":5,\"historybaselinedeaths\":0")) {
                    require(replacement.playerID == 1 && replacement.seatGeneration == 1,
                            "new identity did not reuse source slot with a new generation");
                    for (String bad : new String[]{"", "\"10\"", "-1", "100001", "1.5", "true", "4294967306"}) {
                        String baseline = bad.isEmpty() ? "" : ",\"historybaselinekills\":" + bad;
                        replacement.send(JSON_PREFIX + "{\"playerID\":1,\"playername\":\"Bad baseline\","
                                + "\"playeridentity\":\"" + replacementIdentity + "\",\"roundanalytics\":true,"
                                + "\"historyroundtoken\":\"" + token + "\",\"historybaselinedeaths\":0" + baseline + "}");
                    }
                    replacement.probeClock("");
                    require(currentPlayerState(get("/api/state"), 1).contains("Replacement source"),
                            "malformed same-round history committed registration changes");
                    sendScopedTelemetry(replacement, token, 1, 0, "shot", ",\"total\":7,\"count\":7");
                    replacement.probeClock("");
                    // Malformed or absent source generations must not consume
                    // victim sequence 1 before its legitimate current-source hit.
                    victim.send(JSON_PREFIX + "{\"telemetry\":\"hit\",\"roundtoken\":\"" + token
                            + "\",\"generation\":0,\"life\":0,\"telemetryseq\":1,\"attacker\":1}");
                    for (String bad : new String[]{"\"1\"", "-1", "256", "1.5", "true", "null", "4294967297"})
                        sendScopedTelemetry(victim, token, 1, 0, "hit",
                                ",\"attacker\":1,\"attackergeneration\":" + bad);
                    sendScopedTelemetry(victim, token, 1, 0, "hit", ",\"attacker\":1,\"attackergeneration\":1");
                    // These queued reports refer to the departed generation 0,
                    // not the connected generation-1 replacement of the same ID.
                    sendScopedTelemetry(victim, token, 2, 0, "hit", ",\"attacker\":1,\"attackergeneration\":0");
                    sendScopedTelemetry(victim, token, 3, 0, "elimination", ",\"attacker\":1,\"attackergeneration\":0");
                    sendScopedTelemetry(victim, token, 3, 0, "elimination", ",\"attacker\":1,\"attackergeneration\":0");
                    victim.probeClock("");
                    String state = get("/api/state");
                    require(currentPlayerState(state, 1).contains("\"kills\":0")
                                    && currentPlayerState(state, 1).contains("\"hits\":1")
                                    && currentPlayerState(state, 17).contains("\"deaths\":1")
                                    && currentPlayerState(state, 17).contains("\"awaitingRespawn\":true"),
                            "queued old-source telemetry credited a replacement or lost the victim death");
                    victim.send(MESSAGE_PREFIX + "RESPAWNCOMPLETE"); victim.probeClock("");
                    sendScopedTelemetry(victim, token, 4, 1, "elimination", ",\"attacker\":1,\"attackergeneration\":1");
                    victim.probeClock("");
                    require(replacement.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17")) != null,
                            "stale-source death blocked respawn / the next legitimate elimination");
                    end.invoke(host, "queued source regression");
                    java.util.Map<String, Object> report = parseHostObject(get("/api/report?roundtoken=" + token));
                    List<java.util.Map<String, Object>> people = (List<java.util.Map<String, Object>>) report.get("players");
                    java.util.Map<String, Object> original = people.stream().filter(p -> "Original source".equals(p.get("name"))).findFirst().orElseThrow();
                    java.util.Map<String, Object> current = people.stream().filter(p -> "Replacement source".equals(p.get("name"))).findFirst().orElseThrow();
                    java.util.Map<String, Object> target = people.stream().filter(p -> "Victim".equals(p.get("name"))).findFirst().orElseThrow();
                    java.util.Map<String, Object> previous = people.stream().filter(p -> "Prior baseline".equals(p.get("name"))).findFirst().orElseThrow();
                    require(hostJsonNumber(original, "shots") == 4 && hostJsonNumber(original, "kills") == 0
                                    && hostJsonNumber(current, "shots") == 7 && hostJsonNumber(current, "hits") == 1
                                    && hostJsonNumber(current, "kills") == 1 && hostJsonNumber(current, "bestkillstreak") == 1
                                    && hostJsonNumber(current, "skillbefore") == 150 && hostJsonNumber(current, "skillafter") == 152
                                    && hostJsonNumber(previous, "skillbefore") == 147 && hostJsonNumber(previous, "skillafter") == 147
                                    && hostJsonNumber(target, "deaths") == 2 && Boolean.FALSE.equals(report.get("complete")),
                            "archived source identity, replacement statistics or stale-source partial flag failed");
                    List<java.util.Map<String, Object>> events = (List<java.util.Map<String, Object>>) report.get("events");
                    require(events.stream().filter(e -> "hit".equals(e.get("kind"))).count() == 1
                                    && events.stream().filter(e -> "kill".equals(e.get("kind"))).count() == 1
                                    && events.stream().filter(e -> "death".equals(e.get("kind")) && e.get("target") == null).count() == 1,
                            "timeline invented a replacement source for old queued hits / kills");
                }
            }
        } finally { stop.invoke(host); }
    }

    private static void sendScopedTelemetry(FakePhone phone, String token, long sequence, int life,
                                            String kind, String extra) throws IOException {
        if (("hit".equals(kind) || "elimination".equals(kind)) && !extra.contains("attackergeneration"))
            extra += ",\"attackergeneration\":0";
        phone.send(JSON_PREFIX + "{\"telemetry\":\"" + kind + "\",\"roundtoken\":\"" + token
                + "\",\"generation\":" + phone.seatGeneration + ",\"life\":" + life
                + ",\"telemetryseq\":" + sequence + extra + "}");
    }

    @SuppressWarnings("unchecked")
    private static void verifyRoundReportBounds() throws Exception {
        Class<?> reviewClass = Class.forName("com.simplecoil.laptophost.LaptopHost$RoundReview");
        Class<?> playerClass = Class.forName("com.simplecoil.laptophost.LaptopHost$Player");
        Class<?> settingsClass = Class.forName("com.simplecoil.laptophost.LaptopHost$Settings");
        java.lang.reflect.Method settings = settingsClass.getDeclaredMethod("tournament"); settings.setAccessible(true);
        java.lang.reflect.Constructor<?> playerCtor = playerClass.getDeclaredConstructor(int.class, String.class,
                InetAddress.class, settingsClass); playerCtor.setAccessible(true);
        Object player = playerCtor.newInstance(1, "Player", InetAddress.getLoopbackAddress(), settings.invoke(null));
        java.lang.reflect.Constructor<?> reviewCtor = reviewClass.getDeclaredConstructor(String.class, long.class,
                long.class, String.class); reviewCtor.setAccessible(true);
        Object review = reviewCtor.newInstance(UUID.randomUUID().toString(), 1L, 1000L, "teams");
        java.lang.reflect.Method enroll = reviewClass.getDeclaredMethod("enroll", playerClass, int.class);
        java.lang.reflect.Method started = reviewClass.getDeclaredMethod("started");
        java.lang.reflect.Method shots = reviewClass.getDeclaredMethod("shots", long.class, playerClass, int.class);
        java.lang.reflect.Method event = reviewClass.getDeclaredMethod("event", long.class, String.class,
                playerClass, playerClass, int.class, String.class);
        java.lang.reflect.Method freeze = reviewClass.getDeclaredMethod("freeze", long.class, String.class);
        enroll.setAccessible(true); started.setAccessible(true); shots.setAccessible(true);
        event.setAccessible(true); freeze.setAccessible(true);
        enroll.invoke(review, player, 1); started.invoke(review);
        for (int n = 0; n < 2005; n++) shots.invoke(review, 1000L + n * 1000L, player, 1);
        for (int n = 0; n < 10005; n++) event.invoke(review, 1000L + n, "hit", player, null, 1, "Observed hit");
        Object returning = playerCtor.newInstance(9, "Player returns", InetAddress.getLoopbackAddress(), settings.invoke(null));
        setHostField(returning, "identity", getHostField(player, "identity"));
        enroll.invoke(review, returning, 1);
        Object seatReplacement = playerCtor.newInstance(1, "Different player", InetAddress.getLoopbackAddress(), settings.invoke(null));
        enroll.invoke(review, seatReplacement, 1);
        Object result = freeze.invoke(review, 3_000_000L, "bounds");
        String json = (String) getHostField(result, "json");
        java.util.Map<String, Object> report = parseHostObject(json);
        List<java.util.Map<String, Object>> events = (List<java.util.Map<String, Object>>) report.get("events");
        List<java.util.Map<String, Object>> people = (List<java.util.Map<String, Object>>) report.get("players");
        require(events.size() == 12001 && "end".equals(events.get(events.size() - 1).get("kind"))
                        && hostJsonNumber(report, "omittedEvents") == 11
                        && hostJsonNumber(people.get(0), "shots") == 2005
                        && people.size() == 2 && hostJsonNumber(people.get(0), "playerID") == 9
                        && ((List<?>) people.get(0).get("seatIDs")).size() == 2
                        && ((Number) ((List<?>) people.get(0).get("seatIDs")).get(0)).intValue() == 1
                        && ((Number) ((List<?>) people.get(0).get("seatIDs")).get(1)).intValue() == 9
                        && hostJsonNumber(people.get(1), "shots") == 0,
                "timeline bounds / identity preservation failed: events=" + events.size()
                        + " omitted=" + report.get("omittedEvents") + " players=" + people);
        shots.invoke(review, 3_000_001L, player, 9);
        require(freeze.invoke(review, 3_000_002L, "duplicate") == null
                        && json.equals(getHostField(result, "json")), "frozen report remained mutable");
        Object empty = reviewCtor.newInstance(UUID.randomUUID().toString(), 2L, 1000L, "teams");
        enroll.invoke(empty, player, 1);
        Object cancelled = freeze.invoke(empty, 500L, "cancelled");
        java.util.Map<String, Object> noPlay = parseHostObject((String) getHostField(cancelled, "json"));
        require(hostJsonNumber(noPlay, "durationMs") == 0 && Boolean.FALSE.equals(noPlay.get("started"))
                        && ((List<?>) noPlay.get("events")).size() == 1,
                "cancelled countdown invented actual play or combat events");
    }

    private static void verifyBalancedTeamRetention() throws Exception {
        Class<?> configClass = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
        java.lang.reflect.Method parse = configClass.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Object config = parse.invoke(null, (Object) new String[]{"--no-event-mode", "--balanced-qr", "--no-browser",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--tile-port", String.valueOf(TILE_PORT)});
        java.lang.reflect.Constructor<LaptopHost> constructor = LaptopHost.class.getDeclaredConstructor(configClass);
        constructor.setAccessible(true);
        LaptopHost host = constructor.newInstance(config);
        java.lang.reflect.Method startHost = LaptopHost.class.getDeclaredMethod("start");
        java.lang.reflect.Method stopHost = LaptopHost.class.getDeclaredMethod("stop");
        java.lang.reflect.Method endRound = LaptopHost.class.getDeclaredMethod("endRound", String.class);
        startHost.setAccessible(true);
        stopHost.setAccessible(true);
        endRound.setAccessible(true);
        List<FakePhone> phones = new ArrayList<>();
        String[] addresses = {"127.0.0.3", "127.0.0.2", "127.0.0.5", "127.0.0.6"};
        int[] oldIDs = {1, 17, 2, 18};
        int[] newIDs = {5, 21, 6, 22};
        String[] identities = new String[4];
        int[] teams = new int[4];
        try {
            startHost.invoke(host);
            waitForHost();
            require(get("/map").contains("Rebalance teams"), "dashboard omitted the rebalance button");
            require(responseCode("/api/rebalance") == 405, "GET could mutate team assignments");
            for (int index = 0; index < oldIDs.length; index++) {
                identities[index] = UUID.nameUUIDFromBytes((addresses[index] + ":" + oldIDs[index])
                        .getBytes(StandardCharsets.UTF_8)).toString();
                FakePhone phone = new FakePhone(addresses[index], oldIDs[index], "Player " + index);
                phones.add(phone);
                phone.synchronizeClock();
            }
            require(post("/api/start").contains("90 seconds"), "first round did not prepare its QR check-ins");
            String assigned = get("/api/state");
            for (int index = 0; index < oldIDs.length; index++)
                teams[index] = (int) jsonNumber(currentPlayerState(assigned, oldIDs[index]), "team");
            phones.get(0).send(JSON_PREFIX + "{\"balancedcheckin\":true}");
            require(waitForDashboard(value -> value.contains("\"checkedInCount\":1")) != null,
                    "first scan was not recorded");
            long deadline = (Long) getHostField(host, "balancedCheckInDeadline");
            require(post("/api/start").contains("90 seconds")
                            && get("/api/state").contains("\"checkedInCount\":1")
                            && (Long) getHostField(host, "balancedCheckInDeadline") == deadline,
                    "repeated Start reset a scan or extended its timeout");
            for (int index = 1; index < phones.size(); index++)
                phones.get(index).send(JSON_PREFIX + "{\"balancedcheckin\":true}");
            require(waitForDashboard(value -> value.contains("\"state\":\"countdown\"")) != null,
                    "initial round did not start");
            long firstRoundID = (Long) getHostField(host, "roundID");
            require(post("/api/rebalance").contains("\"ok\":false"),
                    "rebalance moved players during countdown");
            endRound.invoke(host, "retention regression");
            require(waitForDashboard(value -> value.contains("\"playerCount\":0")) != null,
                    "round-end connections did not close");
            for (FakePhone phone : phones) phone.close();
            phones.clear();
            // Keep the production cooldown intact; advance this isolated test
            // fixture instead of making the suite wait thirty seconds.
            setHostField(host, "nextRoundStartAllowedAt", 0L);
            for (int index = 0; index < newIDs.length; index++) {
                FakePhone phone = new FakePhone(addresses[index], newIDs[index], "Player " + index,
                        identities[index], 0);
                phones.add(phone);
                phone.synchronizeClock();
            }
            String retained = get("/api/state");
            require(retained.contains("\"canStart\":true")
                            && retained.contains("\"checkedInCount\":0"),
                    "next round retained old QR readiness or could not start");
            require(phones.get(0).lastRoster.contains("\"balancedprepared\":false"),
                    "next lobby prematurely opened team QR preparation");
            for (int index = 0; index < newIDs.length; index++)
                require(jsonNumber(currentPlayerState(retained, newIDs[index]), "team") == teams[index],
                        "a reconnect with a new player ID moved the player's team");
            try (FakePhone newcomer = new FakePhone("127.0.0.7", 7, "Newcomer")) {
                String joined = get("/api/state");
                for (int index = 0; index < newIDs.length; index++)
                    require(jsonNumber(currentPlayerState(joined, newIDs[index]), "team") == teams[index],
                            "a newcomer moved an existing teammate");
            }
            require(waitForDashboard(value -> value.contains("\"playerCount\":4")) != null,
                    "newcomer was not pruned from the active lobby");
            require(post("/api/start").contains("90 seconds"), "next round did not prepare fresh scans");
            retained = get("/api/state");
            for (int index = 0; index < newIDs.length; index++)
                require(jsonNumber(currentPlayerState(retained, newIDs[index]), "team") == teams[index],
                        "starting the next round automatically reorganized teams");
            phones.get(0).send(JSON_PREFIX + "{\"balancedcheckin\":true}");
            require(waitForDashboard(value -> value.contains("\"checkedInCount\":1")) != null,
                    "second-round scan was not recorded");
            // Make the retained split deliberately uneven by skill so the
            // manual button must produce a genuinely different split.
            synchronized (getHostField(host, "stateLock")) {
                java.util.Map<?, ?> players = (java.util.Map<?, ?>) getHostField(host, "players");
                for (int index = 0; index < newIDs.length; index++) {
                    Object player = players.get(newIDs[index]);
                    setHostField(player, "priorKills", teams[index] == 1 ? 1_000 : 0);
                    setHostField(player, "priorDeaths", teams[index] == 1 ? 0 : 1_000);
                }
            }
            for (FakePhone phone : phones) phone.probeClock("");
            require(post("/api/rebalance").contains("\"ok\":true"), "manual rebalance was refused in the lobby");
            require(phones.get(0).readUntil(frame -> frame.contains("\"balancedprepared\":false")) != null,
                    "rebalance did not disarm the phone's QR preparation");
            String rebalanced = get("/api/state");
            require(rebalanced.contains("\"checkedInCount\":0")
                            && rebalanced.contains("\"checkInRemainingMs\":0")
                            && rebalanced.contains("\"canStart\":true")
                            && (Long) getHostField(host, "roundID") == firstRoundID,
                    "rebalance started a round or retained previous check-ins");
            int[] strong = new int[3];
            int[] counts = new int[3];
            for (int index = 0; index < newIDs.length; index++) {
                int team = (int) jsonNumber(currentPlayerState(rebalanced, newIDs[index]), "team");
                counts[team]++;
                if (teams[index] == 1) strong[team]++;
            }
            require(strong[1] == 1 && strong[2] == 1 && Math.abs(counts[1] - counts[2]) <= 2,
                    "manual rebalance did not use skill with the team-size bound");
            for (FakePhone phone : phones) phone.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
            // A clock probe fences earlier frames on each TCP connection.
            for (FakePhone phone : phones) phone.probeClock("");
            require(get("/api/state").contains("\"state\":\"lobby\"")
                            && get("/api/state").contains("\"checkedInCount\":0"),
                    "scans before Start triggered a round after rebalance");
            require(post("/api/start").contains("90 seconds"), "rebalance left Start unusable");
            require(phones.get(0).readUntil(frame -> frame.contains("\"balancedprepared\":true")) != null,
                    "armed QR preparation was not sent to phones");
            for (FakePhone phone : phones) phone.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
            require(waitForDashboard(value -> value.contains("\"state\":\"countdown\"")) != null
                            && (Long) getHostField(host, "roundID") == firstRoundID + 1,
                    "fresh QR check-ins did not start exactly one next round");
            require(post("/api/rebalance").contains("\"ok\":false"),
                    "rebalance was enabled during the next countdown");
            setHostField(host, "roundStartAt", 0L);
            require(get("/api/state").contains("\"state\":\"running\"")
                            && post("/api/rebalance").contains("\"ok\":false"),
                    "rebalance was enabled during gameplay");
        } finally {
            for (FakePhone phone : phones) phone.close();
            stopHost.invoke(host);
        }
    }

    private static void requireCountdownDuration(FakePhone phone, String before, String start,
                                                 long durationMs) throws IOException {
        String after = phone.probeClock("");
        require(before != null && after != null, "countdown clock probes were not answered");
        double deadline = jsonNumber(start, "gamestart");
        require(deadline >= jsonNumber(before, "clockreceive") + durationMs
                        && deadline <= jsonNumber(after, "clockreceive") + durationMs,
                "start deadline did not include the required " + durationMs + " ms countdown");
    }

    private static void verifyBalancedQr() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT), "--balanced-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            verifyBalancedJoinCanCrossOldTeamBoundary();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("Waiting up to 90 seconds"),
                        "balanced QR mode did not wait for check-in");
                require(first.readUntil(frame -> frame.contains("\"balancedrandom\":true")
                        && frame.contains("\"team\":")) != null,
                        "balanced assignments were not sent to the phones");
                first.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
                require(!first.receivesFor(frame -> frame.contains("\"gamestart\""), 300),
                        "one QR check-in started the game early");
                String before = first.probeClock("");
                second.send(JSON_PREFIX + "{\"balancedcheckin\":true}");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                require(start != null,
                        "the final QR check-in did not start the shared countdown");
                requireCountdownDuration(first, before, start, GameStartCountdown.DURATION_MS);
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBalancedJoinCanCrossOldTeamBoundary() throws Exception {
        List<DatagramSocket> pendingJoins = new ArrayList<>();
        try {
            for (int index = 0; index < 17; index++) {
                DatagramSocket socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0." + (20 + index), UDP_PORT));
                socket.setSoTimeout(1_000);
                pendingJoins.add(socket);
                byte[] request = ("SimpleCoil:JOIN" + NETWORK_VERSION + "1").getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(request, request.length,
                        InetAddress.getByName("127.0.0.1"), UDP_PORT));
                byte[] reply = new byte[128];
                DatagramPacket packet = new DatagramPacket(reply, reply.length);
                socket.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                int expectedID = index + 1;
                String expected = "SimpleCoil:SERVERREPLY" + (expectedID == 1 ? "" : ":" + expectedID);
                require(expected.equals(message), "balanced lobby remained capped at sixteen IDs per team");
            }
        } finally {
            for (DatagramSocket socket : pendingJoins)
                socket.close();
        }
    }

    private static void verifyBalancedNoQr() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT), "--balanced-no-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/rebalance").contains("\"ok\":true")
                                && get("/api/state").contains("\"state\":\"lobby\"")
                                && get("/api/state").contains("\"canStart\":true"),
                        "no-QR rebalance started a round or disabled Start");
                String before = first.probeClock("");
                require(post("/api/start").contains("\"ok\":true"),
                        "balanced no-QR mode did not start without check-in");
                String start = first.readUntil(frame -> frame.contains("\"gamestart\""));
                require(start != null,
                        "balanced no-QR countdown was not sent");
                requireCountdownDuration(first, before, start, GameStartCountdown.DURATION_MS);
                require(get("/api/state").contains("\"balancedQr\":false"),
                        "balanced no-QR state was not exposed");
                verifyDirectLateJoinBlocked();
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBalancedQrTimeout() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT), "--balanced-qr",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT))
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                require(post("/api/start").contains("90 seconds"),
                        "QR check-in timeout was not announced");
                long assignedAt = System.nanoTime();
                require(get("/api/state").contains("\"checkedInCount\":0"),
                        "the unscanned lobby was not retained");
                boolean started = false;
                while (System.nanoTime() - assignedAt < 96_000_000_000L) {
                    String state = get("/api/state");
                    if (state.contains("\"state\":\"countdown\"")
                            || state.contains("\"state\":\"running\"")) {
                        require(System.nanoTime() - assignedAt >= 89_000_000_000L,
                                "QR timeout started the round too early");
                        require(state.contains("\"playerCount\":2")
                                && state.contains("\"checkedInCount\":0"),
                                "unscanned players lost their assigned places");
                        started = true;
                        break;
                    }
                    first.send("pong");
                    second.send("pong");
                    Thread.sleep(1_000);
                }
                require(started, "an unscanned player kept the game from starting after 90 seconds");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "QR timeout start was not delivered to a phone");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyDeadRejoin() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--timer-respawn", "--tcp-port", String.valueOf(TCP_PORT), "--udp-port",
                String.valueOf(UDP_PORT), "--dashboard-port", String.valueOf(DASHBOARD_PORT),
                "--start-delay", "1").redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                first.synchronizeClock();
                second.synchronizeClock();
                long readyDeadline = System.nanoTime() + 3_000_000_000L;
                while (!get("/api/state").contains("\"canStart\":true")
                        && System.nanoTime() < readyDeadline)
                    Thread.sleep(25);
                require(post("/api/start").contains("\"ok\":true"), "reconnect test could not start");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "countdown was not delivered");
                Thread.sleep(1_150);
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                require(first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17")) != null,
                        "the death was not recorded");
                second.close();
                long disconnectDeadline = System.nanoTime() + 3_000_000_000L;
                String disconnected;
                do {
                    disconnected = get("/api/state");
                    if (disconnected.contains("\"connected\":false")) break;
                    Thread.sleep(25);
                } while (System.nanoTime() < disconnectDeadline);
                require(disconnected.contains("\"connected\":false")
                                && disconnected.contains("\"awaitingRespawn\":true"),
                        "a connection drop erased the player's pending death");

                try (FakePhone rejoined = new FakePhone("127.0.0.2", 17, "Red",
                        UUID.nameUUIDFromBytes("127.0.0.2:17".getBytes(StandardCharsets.UTF_8))
                                .toString(), 0, true)) {
                    String state = get("/api/state");
                    require(state.contains("\"awaitingRespawn\":true"),
                            "rejoining erased the pending death");
                    rejoined.send(MESSAGE_PREFIX + "ELIMINATED1");
                    require(!first.receivesFor(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17"), 350),
                            "a still-dead player scored another elimination after rejoining");
                }
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyRespawnMode(boolean qrEnabled) throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                qrEnabled ? "--qr-respawn" : "--timer-respawn",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                require(first.readUntil(frame -> frame.contains("\"respawnqrenabled\":" + qrEnabled)) != null,
                        "host did not publish the respawn rule");
                require(get("/api/state").contains("\"respawnQrEnabled\":" + qrEnabled),
                        "dashboard did not expose the selected respawn rule");
                first.synchronizeClock();
                second.synchronizeClock();
                long readyDeadline = System.nanoTime() + 3_000_000_000L;
                while (!get("/api/state").contains("\"canStart\":true")
                        && System.nanoTime() < readyDeadline)
                    Thread.sleep(25);
                require(post("/api/start").contains("\"ok\":true"), "respawn test could not start");
                require(first.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "countdown was not delivered");
                Thread.sleep(1_150);
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                require(first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17")) != null,
                        "elimination was not recorded");
                second.send(MESSAGE_PREFIX + "RESPAWNREQUEST");
                if (qrEnabled) {
                    require(second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                            "QR mode did not allow checkpoint respawn");
                } else {
                    require(!second.receivesFor(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED"), 350),
                            "timer-only mode accepted a checkpoint request");
                    require(get("/api/state").contains("\"awaitingRespawn\":true"),
                            "timer-only player revived before the timer completed");
                    second.send(MESSAGE_PREFIX + "RESPAWNCOMPLETE");
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    while (get("/api/state").contains("\"awaitingRespawn\":true")
                            && System.nanoTime() < deadline)
                        Thread.sleep(25);
                    require(!get("/api/state").contains("\"awaitingRespawn\":true"),
                            "timer completion did not clear the player's respawn state");
                }
                second.send(MESSAGE_PREFIX + "ELIMINATED1");
                require(first.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "ELIMINATED17")) != null,
                        "respawned player could not be eliminated again");
                require(post("/api/respawn?id=17").contains("\"ok\":true"),
                        "Game Master override was blocked by the respawn rule");
                require(second.readUntil(frame -> frame.equals(MESSAGE_PREFIX + "RESPAWNGRANTED")) != null,
                        "Game Master respawn was not delivered");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyBossMode() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT), "--boss",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone boss = new FakePhone("127.0.0.3", 1, "Boss");
                 FakePhone hunter = new FakePhone("127.0.0.2", 2, "Hunter")) {
                String roster = boss.readUntil(frame -> frame.contains("\"bossmode\":true")
                        && frame.contains("\"playerID\":2"));
                require(roster != null, "Boss Mode was not advertised to the phones");
                require(roster.contains("\"playerID\":1,\"health\":6,\"reloadshots\":120")
                                && roster.contains("\"shotmodeburst3\":true,\"shotmodeauto\":true,\"firingmode\":1"),
                        "boss did not receive scaled health, 120 rounds, unlocked fire modes, and a wide cone");
                require(roster.contains("\"playerID\":2,\"health\":2,\"reloadshots\":30")
                                && roster.contains("\"playerID\":2,\"health\":2")
                                && roster.contains("\"shotmodeburst3\":false,\"shotmodeauto\":false,\"firingmode\":0"),
                        "hunter did not receive the locked single-shot, no-cone profile");
                boss.synchronizeClock();
                hunter.synchronizeClock();
                long readyDeadline = System.nanoTime() + 3_000_000_000L;
                while (!get("/api/state").contains("\"canStart\":true")
                        && System.nanoTime() < readyDeadline)
                    Thread.sleep(25);
                String startResponse = post("/api/start");
                require(startResponse.contains("\"ok\":true"),
                        "a valid Boss roster did not start: " + startResponse);
                require(boss.readUntil(frame -> frame.contains("\"gamestart\"")) != null,
                        "Boss Mode countdown was not delivered");
                verifyDirectLateJoinBlockedAs(3);
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyCaptureTheFlagMode() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT), "--ctf",
                "--tcp-port", String.valueOf(TCP_PORT), "--udp-port", String.valueOf(UDP_PORT),
                "--dashboard-port", String.valueOf(DASHBOARD_PORT), "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone first = new FakePhone("127.0.0.3", 1, "Blue");
                 FakePhone second = new FakePhone("127.0.0.2", 17, "Red")) {
                String roster = first.readUntil(frame -> frame.contains("\"capturetheflag\":true")
                        && frame.contains("\"tournamentmode\":true"));
                require(roster != null, "Capture the Flag mode was not advertised to phones");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void verifyInfectionMode() throws Exception {
        Path classes = Path.of("laptop-host", "out").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process host = new ProcessBuilder(javaExecutable, "-Dsimplecoil.reports=" + System.getProperty("simplecoil.reports"), "-Dsimplecoil.players.file=" + Files.createTempDirectory("simplecoil-profiles-").resolve("players.json"), "-Dsimplecoil.event.file=" + Files.createTempDirectory("simplecoil-event-").resolve("event.properties"), "--add-modules", "jdk.httpserver", "-cp",
                classes.toString(), "com.simplecoil.laptophost.LaptopHost", "--no-browser", "--no-event-mode", "--tile-port", String.valueOf(TILE_PORT),
                "--infection", "--tcp-port", String.valueOf(TCP_PORT), "--udp-port",
                String.valueOf(UDP_PORT), "--dashboard-port", String.valueOf(DASHBOARD_PORT),
                "--start-delay", "1")
                .redirectErrorStream(true).start();
        try {
            waitForHost();
            try (FakePhone zombie = new FakePhone("127.0.0.3", 1, "Zombie");
                 FakePhone survivor = new FakePhone("127.0.0.2", 2, "Survivor");
                 FakePhone lastSurvivor = new FakePhone("127.0.0.4", 3, "Last Survivor")) {
                String roster = zombie.readUntil(frame -> frame.contains("\"infectionmode\":true")
                        && frame.contains("\"tournamentmode\":true"));
                require(roster != null, "Infection mode was not advertised to phones");
                zombie.synchronizeClock();
                survivor.synchronizeClock();
                lastSurvivor.synchronizeClock();
                require(post("/api/start").contains("\"ok\":true"),
                        "a valid Infection roster did not start");
                String start = survivor.readUntil(frame -> frame.contains("\"gamestart\""));
                require(start != null, "Infection countdown was not delivered");
                require(start.contains("\"gameduration\":300000"),
                        "Infection did not force the five-minute survival limit");
                String token = jsonString(start, "roundtoken");
                Thread.sleep(1_100);
                survivor.sendStateGossip(token, 1, 0, 1 << 3);
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (!get("/api/state").contains("\"state\":\"finished\"")
                        && System.nanoTime() < deadline)
                    Thread.sleep(25);
                require(get("/api/state").contains("\"state\":\"finished\""),
                        "host did not award the last uninfected survivor");
            }
        } finally {
            host.destroy();
            if (!host.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
                host.destroyForcibly();
        }
    }

    private static void waitForHost() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (get("/api/state").contains("\"state\""))
                    return;
            } catch (IOException ignored) {
            }
            Thread.sleep(50);
        }
        throw new AssertionError("laptop host did not start");
    }

    private static String waitForDashboard(Predicate<String> wanted) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            String state = get("/api/state");
            if (wanted.test(state))
                return state;
            Thread.sleep(25);
        }
        return null;
    }

    private static void verifyUdpDiscovery() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.3", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = ("SimpleCoil:JOIN" + NETWORK_VERSION + "1").getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SERVERREPLY".equals(message),
                    "UDP discovery reply was incompatible: " + message);
        }
    }

    private static void verifySharedLobbyDiscovery() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.9", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] query = ("SimpleCoil:LOBBYQUERY:" + NETWORK_VERSION).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getLoopbackAddress(), UDP_PORT));
            DatagramPacket reply = new DatagramPacket(new byte[128], 128);
            socket.receive(reply);
            require(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8)
                            .matches("SimpleCoil:LOBBYSTATE:" + NETWORK_VERSION + ":L:OPEN:[0-9]+"),
                    "shared lobby did not advertise a compatible dedicated host");
            byte[] join = ("SimpleCoil:JOIN" + NETWORK_VERSION + "0").getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(join, join.length, InetAddress.getLoopbackAddress(), UDP_PORT));
            socket.receive(reply);
            String assignment = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            // The preceding explicit discovery reserved blue player 1.
            require(assignment.equals("SimpleCoil:SERVERREPLY:17"),
                    "automatic join did not balance teams: " + assignment);
            socket.send(new DatagramPacket(join, join.length, InetAddress.getLoopbackAddress(), UDP_PORT));
            socket.receive(reply);
            require(assignment.equals(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8)),
                    "automatic join retry changed the reserved identity");
        }
    }

    private static void verifyPreviousProtocolRejected() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.10", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = ("SimpleCoil:JOIN" + (NETWORK_VERSION - 1) + "1")
                    .getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), UDP_PORT));
            DatagramPacket reply = new DatagramPacket(new byte[128], 128);
            socket.receive(reply);
            require("VERSIONERROR".equals(new String(reply.getData(), reply.getOffset(), reply.getLength(),
                            StandardCharsets.UTF_8)),
                    "the previous network protocol was not rejected by UDP discovery");
        }
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.10", 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(1_000);
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeUTF("SimpleCoil:" + (NETWORK_VERSION - 1)
                    + "JSON{\"playerID\":1,\"playername\":\"Old phone\"}");
            output.flush();
            require("VERSIONERROR".equals(new DataInputStream(socket.getInputStream()).readUTF()),
                    "the previous network protocol was not rejected by TCP registration");
        }
    }

    private static void verifyLateJoinBlocked() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.4", UDP_PORT));
            socket.setSoTimeout(1_000);
            byte[] request = ("SimpleCoil:JOIN" + NETWORK_VERSION + "2").getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), UDP_PORT));
            byte[] reply = new byte[128];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            require("SimpleCoil:SAMETEAM".equals(message), "late discovery join was not refused");
        }
    }

    private static void verifyDirectLateJoinBlocked() throws Exception {
        verifyDirectLateJoinBlockedAs(2);
    }

    private static void verifyDirectLateJoinBlockedAs(int playerID) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.4", 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(1_000);
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeUTF(JSON_PREFIX + "{\"playerID\":" + playerID
                    + ",\"playername\":\"Late\"}");
            output.flush();
            try {
                String reply = new DataInputStream(socket.getInputStream()).readUTF();
                require(reply.equals(JSON_PREFIX + "{\"joinrejected\":true}"),
                        "late direct TCP join was not refused");
            } catch (EOFException expected) {
                // The host closes a direct registration that bypasses discovery.
            }
        }
    }

    private static int wifiResponseCode(String method, String body, String origin, boolean controlHeader)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + DASHBOARD_PORT + "/api/wifi")).timeout(Duration.ofSeconds(30));
        if (origin != null) request.header("Origin", origin);
        if (controlHeader) request.header("X-SimpleCoil-Control", "1");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return WIFI_HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String get(String path) throws IOException {
        return request(path, "GET");
    }

    private static byte[] getBytes(String path) throws IOException {
        return getBytes(DASHBOARD_PORT, path);
    }

    private static byte[] getBytes(int port, String path) throws IOException {
        HttpURLConnection connection = open(port, path, "GET");
        require(connection.getResponseCode() == 200, "GET " + path + " was not successful");
        byte[] body = connection.getInputStream().readAllBytes();
        connection.disconnect();
        return body;
    }

    private static int responseCode(String path) throws IOException {
        HttpURLConnection connection = open(path, "GET");
        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
    }

    private static String post(String path) throws IOException {
        return request(path, "POST");
    }

    private static String request(String path, String method) throws IOException {
        HttpURLConnection connection = open(path, method);
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
        connection.disconnect();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String path, String method) throws IOException {
        return open(DASHBOARD_PORT, path, method);
    }

    private static HttpURLConnection open(int port, String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(500);
        connection.setReadTimeout(1_000);
        return connection;
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static boolean gpsFrameContainsPlayer(String frame, int playerID) {
        return frame.startsWith(JSON_PREFIX) && frame.contains("\"gpsupdate\"")
                && frame.contains("\"playerID\":" + playerID);
    }

    private static String jsonString(String frame, String key) {
        String prefix = "\"" + key + "\":\"";
        int start = frame.indexOf(prefix);
        if (start < 0)
            return null;
        start += prefix.length();
        int end = frame.indexOf('"', start);
        return end < 0 ? null : frame.substring(start, end);
    }

    private static final class FakePhone implements AutoCloseable {
        private static final int STATE_PACKET_BYTES = 1_264;
        private static final int STATE_PACKET_MAGIC = 0x53434F49;
        private final Socket socket = new Socket();
        private final DatagramSocket udp;
        private final DataInputStream input;
        private final DataOutputStream output;
        private int playerID;
        private int seatGeneration;
        private String lastRoster;
        private long statePacketSequence;

        FakePhone(String localAddress, int playerID, String name) throws IOException {
            this(localAddress, playerID, name, UUID.nameUUIDFromBytes((localAddress + ":" + playerID)
                    .getBytes(StandardCharsets.UTF_8)).toString(), 0);
        }

        FakePhone(String localAddress, int playerID, String name, String identity, int joinTeam) throws IOException {
            this(localAddress, playerID, name, identity, joinTeam, false);
        }

        FakePhone(String localAddress, int playerID, String name, String identity,
                  int joinTeam, boolean rejoin) throws IOException {
            this(localAddress, playerID, name, identity, joinTeam, rejoin, false);
        }

        FakePhone(String localAddress, int playerID, String name, String identity,
                  int joinTeam, boolean rejoin, boolean analytics) throws IOException {
            this(localAddress, playerID, name, identity, joinTeam, rejoin, analytics, "");
        }

        FakePhone(String localAddress, int playerID, String name, String identity,
                  int joinTeam, boolean rejoin, boolean analytics, String registrationExtra) throws IOException {
            this.playerID = playerID;
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(localAddress, UDP_PORT));
            udp.setSoTimeout(3_000);
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.connect(new InetSocketAddress("127.0.0.1", TCP_PORT), 1_000);
            socket.setSoTimeout(2_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            send(JSON_PREFIX + "{\"playerID\":" + playerID + ",\"playername\":\""
                    + name.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"playeridentity\":\"" + identity + "\",\"jointeam\":" + joinTeam
                    + ",\"lobbybenched\":" + (joinTeam > 0) + ",\"lobbyready\":true"
                    + ",\"roundanalytics\":" + analytics
                    + (rejoin ? ",\"rejoin\":true" : "") + registrationExtra + "}");
            if (joinTeam > 0) {
                String assigned = readUntil(frame -> frame.contains("\"joinedplayerid\""));
                require(assigned != null, "team QR admission was not acknowledged");
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"joinedplayerid\":([0-9]+)")
                        .matcher(assigned);
                require(matcher.find(), "missing assigned player ID");
                this.playerID = Integer.parseInt(matcher.group(1));
            }
            String roster = readUntil(frame -> frame.startsWith(JSON_PREFIX + "{\"players\""));
            lastRoster = roster;
            if (joinTeam > 0) seatGeneration = com.simplecoil.protocol.RoundRoster.decode(
                    jsonString(roster, "roundroster")).generation(this.playerID);
            require(roster != null && roster.contains("\"maptileport\":"),
                    "laptop host did not advertise its phone map tile service");
            require(roster.contains("\"powerupqrrequired\":"),
                    "laptop host did not advertise its power-up setting");
        }

        void sendStateGossip(String roundToken, long ownerSequence, int score) throws IOException {
            sendStateGossip(roundToken, ownerSequence, score, 0);
        }

        void sendStateGossip(String roundToken, long ownerSequence, int score, int extraFlags)
                throws IOException {
            sendStateGossip(roundToken, ownerSequence, score, extraFlags, null, 0, 0);
        }

        void sendStateGossip(String roundToken, long ownerSequence, int score, int extraFlags,
                             GpsOffset origin, int north, int east) throws IOException {
            ByteBuffer packet = ByteBuffer.allocate(STATE_PACKET_BYTES).order(ByteOrder.BIG_ENDIAN);
            UUID token = UUID.fromString(roundToken);
            packet.putInt(STATE_PACKET_MAGIC);
            packet.put((byte) 4); // state-packet format
            packet.put((byte) NETWORK_VERSION); // network protocol
            packet.put((byte) playerID);
            packet.put((byte) 2); // two-team mode
            packet.putLong(++statePacketSequence);
            packet.putLong(token.getMostSignificantBits());
            packet.putLong(token.getLeastSignificantBits());
            packet.putDouble(origin == null ? Double.NaN : origin.latitude);
            packet.putDouble(origin == null ? Double.NaN : origin.longitude);
            for (int id = 1; id <= 32; id++) {
                boolean present = id == playerID;
                packet.put((byte) (present ? 1 | extraFlags : 0));
                packet.put((byte) id);
                packet.put((byte) (present ? 1 : 0)); // running
                packet.put((byte) 0); // grenade
                packet.put((byte) 0); // event
                packet.put((byte) 0); // target
                packet.putShort((short) 0);
                packet.putInt((int) (present ? ownerSequence : 0));
                packet.putInt(0);
                packet.putInt(present ? score : 0);
                packet.putInt(0);
                packet.putShort((short) (present ? 5 : 0));
                packet.putShort((short) (present ? 10 : 0));
                packet.putShort((short) (present ? 29 : 0));
                packet.putShort((short) 0);
                packet.putShort((short) (present ? north : 0));
                packet.putShort((short) (present ? east : 0));
                packet.put((byte) (present ? seatGeneration : 0));
                packet.put((byte) 0); // event-target generation
            }
            byte[] payload = packet.array();
            udp.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("127.0.0.1"), UDP_PORT));
        }

        void sendCombat(String roundToken, long eventSequence, int eventType, int targetID)
                throws IOException {
            ByteBuffer packet = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
            UUID token = UUID.fromString(roundToken);
            packet.putInt(0x53434F43); // SCOC
            packet.put((byte) 2); // combat-packet format
            packet.put((byte) NETWORK_VERSION); // network protocol
            packet.put((byte) 1); // event, not ACK
            packet.put((byte) eventType);
            packet.put((byte) playerID);
            packet.put((byte) targetID);
            packet.putShort((short) 0);
            packet.putInt((int) eventSequence);
            packet.putLong(token.getMostSignificantBits());
            packet.putLong(token.getLeastSignificantBits());
            byte[] payload = packet.array();
            udp.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("127.0.0.1"), UDP_PORT));
        }

        boolean receivesAuthorityState(String roundToken, long ownerSequence, int score)
                throws IOException {
            return receivesAuthorityState(roundToken, ownerSequence, score, null);
        }

        boolean receivesAuthorityState(String roundToken, long ownerSequence, int score,
                                       Boolean gpsValid) throws IOException {
            UUID expectedToken = UUID.fromString(roundToken);
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] payload = new byte[STATE_PACKET_BYTES];
                DatagramPacket datagram = new DatagramPacket(payload, payload.length);
                try {
                    udp.receive(datagram);
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (datagram.getLength() != STATE_PACKET_BYTES)
                    continue;
                ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                if (packet.getInt() != STATE_PACKET_MAGIC || (packet.get(6) & 0xff) != 0)
                    continue;
                UUID token = new UUID(packet.getLong(16), packet.getLong(24));
                if (!expectedToken.equals(token))
                    continue;
                int rowOffset = 48 + (playerID - 1) * 38;
                if ((packet.get(rowOffset) & 1) != 0
                        && (packet.getInt(rowOffset + 8) & 0xffffffffL) == ownerSequence
                        && packet.getInt(rowOffset + 16) == score
                        && (gpsValid == null || ((packet.get(rowOffset) & 4) != 0) == gpsValid))
                    return true;
            }
            return false;
        }

        boolean receivesAuthorityCombat(String roundToken, int senderID, long eventSequence,
                                        int eventType, int targetID) throws IOException {
            UUID expectedToken = UUID.fromString(roundToken);
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] payload = new byte[STATE_PACKET_BYTES];
                DatagramPacket datagram = new DatagramPacket(payload, payload.length);
                try {
                    udp.receive(datagram);
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (datagram.getLength() != STATE_PACKET_BYTES)
                    continue;
                ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                if (packet.getInt() != STATE_PACKET_MAGIC || (packet.get(6) & 0xff) != 0)
                    continue;
                UUID token = new UUID(packet.getLong(16), packet.getLong(24));
                if (!expectedToken.equals(token))
                    continue;
                int rowOffset = 48 + (senderID - 1) * 38;
                if ((packet.get(rowOffset) & 1) != 0
                        && (packet.getInt(rowOffset + 12) & 0xffffffffL) == eventSequence
                        && (packet.get(rowOffset + 4) & 0xff) == eventType
                        && (packet.get(rowOffset + 5) & 0xff) == targetID)
                    return true;
            }
            return false;
        }

        boolean receivesAuthorityGps(String roundToken, int north, int east) throws IOException {
            return receivesAuthorityGps(roundToken, north, east, 0);
        }

        boolean receivesAuthorityGps(String roundToken, int north, int east, int minimumAgeSeconds)
                throws IOException {
            UUID token = UUID.fromString(roundToken);
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] bytes = new byte[STATE_PACKET_BYTES];
                DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
                try { udp.receive(datagram); } catch (SocketTimeoutException e) { continue; }
                if (datagram.getLength() != STATE_PACKET_BYTES) continue;
                ByteBuffer packet = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                int row = 48 + (playerID - 1) * 38;
                if (packet.getInt(0) == STATE_PACKET_MAGIC && packet.get(6) == 0
                        && new UUID(packet.getLong(16), packet.getLong(24)).equals(token)
                        && packet.getDouble(32) == 47.6 && packet.getDouble(40) == -122.1
                        && (packet.get(row) & 4) != 0 && packet.getShort(row + 32) == north
                        && packet.getShort(row + 34) == east
                        && (packet.getShort(row + 30) & 0xffff) >= minimumAgeSeconds) return true;
            }
            return false;
        }

        String probeClock(String additionalFields) throws IOException {
            long stamp = System.nanoTime() / 1_000_000L;
            send(JSON_PREFIX + "{\"clocksync\":" + stamp + additionalFields + "}");
            return readUntil(frame -> frame.contains("\"clockreceive\""));
        }

        void synchronizeClock() throws IOException {
            for (int sample = 0; sample < 12; sample++) {
                long stamp = System.nanoTime() / 1_000_000L;
                send(JSON_PREFIX + "{\"clocksync\":" + stamp + ",\"gpsclock\":"
                        + (GPS_UTC_BASE + stamp) + "}");
                String reply = readUntil(frame -> frame.startsWith(JSON_PREFIX) && frame.contains("\"clockreceive\""));
                require(reply != null, "clock sample was not answered");
            }
            send(JSON_PREFIX + "{\"clockready\":true}");
            // The HTTP start request uses another connection. Fence the readiness
            // frame on this ordered TCP stream before that request can overtake it.
            String readinessFence = probeClock("");
            require(readinessFence != null && jsonNumber(readinessFence, "clockreceive") >= 0
                            && jsonNumber(readinessFence, "clocksend") >= jsonNumber(readinessFence, "clockreceive"),
                    "the host did not acknowledge the clock-readiness fence");
        }

        void send(String frame) throws IOException {
            output.writeUTF(frame);
            output.flush();
        }

        String readUntil(Predicate<String> wanted) throws IOException {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline) {
                String frame = input.readUTF();
                if (wanted.test(frame))
                    return frame;
            }
            return null;
        }

        boolean receivesFor(Predicate<String> wanted, long durationMillis) throws IOException {
            long deadline = System.nanoTime() + durationMillis * 1_000_000L;
            int previousTimeout = socket.getSoTimeout();
            try {
                while (System.nanoTime() < deadline) {
                    long remainingMillis = Math.max(1,
                            (deadline - System.nanoTime() + 999_999L) / 1_000_000L);
                    socket.setSoTimeout((int) Math.min(previousTimeout, remainingMillis));
                    try {
                        if (wanted.test(input.readUTF()))
                            return true;
                    } catch (SocketTimeoutException expected) {
                        return false;
                    }
                }
                return false;
            } finally {
                socket.setSoTimeout(previousTimeout);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                socket.close();
            } finally {
                udp.close();
            }
        }
    }
}
