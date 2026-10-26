package com.simplecoil.laptophost;

import com.simplecoil.protocol.EventMode;
import com.simplecoil.protocol.GunBatteryHistory;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/** Exercises real TCP phones, persistence, transfers, host rules, and local-only controls. */
public final class PlayerControlsTest {
    private static final int HTTP = 19711, TCP = 19710;
    private static final String JSON = "SimpleCoil:31JSON", MESSAGE = "SimpleCoil:31MESG";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static Path root;
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("simplecoil-player-controls");
        verifyDirectory();
        verifyReturningIdentityHasOnlyOneSeat();
        verifyRelay();
        verifyManualTeamsOverrideBalancedRandom();
        String firstDevice = UUID.randomUUID().toString(), secondDevice = UUID.randomUUID().toString(), replacementDevice = UUID.randomUUID().toString();
        Process host = start();
        try (Phone first = new Phone("127.0.0.2", 1, "Blue", firstDevice);
             Phone second = new Phone("127.0.0.3", 17, "Red", secondDevice);
             Phone replacement = new Phone("127.0.0.4", 2, "Replacement", replacementDevice)) {
            first.send(MESSAGE + "PLAYERDATAREQUEST");
            Map<String,Object> requestedScores = first.until(game -> game.containsKey("playerdata"));
            check(((List<?>)requestedScores.get("playerdata")).size() == 3, "Manual player list did not contain the actual roster");
            check(get("/players").contains("Move to another phone"), "Player editor unavailable");
            check(get("/assets/players.js").contains("settingsValues"), "Player editor script unavailable");
            check(control("/api/players", "{}", "https://example.com", true).statusCode() == 403, "Cross-origin edit accepted");
            check(control("/api/players", "{}", null, false).statusCode() == 403, "Control header not required");
            check(control("/api/players", "x".repeat(4097), null, true).statusCode() == 413, "Oversized edit accepted");
            check(control("/api/players", "[]", null, true).statusCode() == 400, "Non-object edit accepted");
            Map<String,Object> firstRow = active(1), replacementRow = active(2);
            String person = (String) firstRow.get("identity");
            Map<String,Object> settings = new LinkedHashMap<>(object(firstRow.get("settings")));settings.remove("playerID");
            settings.putAll(Map.of("health",12,"reloadshots",60,"reloadtime",2400,"spawntime",17,"damage",-3,"shotmodeauto",true,"firingmode",1,"liveslimit",2));
            Map<String,Object> edit = new LinkedHashMap<>(Map.of("id",1,"identity",person,"name","Pilot","team",2,"settings",settings));
            check(post("/api/players",edit).statusCode() == 200, "Host could not edit name, team and rules");
            first.until(game -> rosterName(game,1).equals("Pilot") && Boolean.TRUE.equals(game.get(EventMode.CUSTOM_RULES)) && Boolean.FALSE.equals(game.get("tournamentmode")));
            check(number(active(1).get("team")) == 2, "Manual team did not update");
            first.send(JSON + "{\"playernamechange\":\"Phone overwrite\"}");
            first.until(game -> rosterName(game,1).equals("Pilot"));
            check(active(1).get("name").equals("Pilot"), "Phone overwrote the host name");
            Map<String,Object> invalid = new LinkedHashMap<>(edit);invalid.put("name", " ");invalid.put("benched",true);
            check(post("/api/players",invalid).statusCode() == 400 && Boolean.FALSE.equals(active(1).get("benched")), "Invalid edit partially committed");
            check(post("/api/players",Map.of("id",1,"identity",UUID.randomUUID().toString(),"name","Wrong person")).statusCode() == 409, "Stale player identity accepted");
            check(post("/api/event",Map.of("title","Night Games","enabled",true,"kiosk",true,"pin","123456")).statusCode() == 200, "Event settings failed");
            Map<String,Object> policy = first.until(game -> "Night Games".equals(game.get(EventMode.TITLE)));
            check(EventMode.matchesPin(person,"123456",(String) policy.get(EventMode.PIN_HASH)), "Organizer recovery PIN was not bound to the player");
            check(!get("/api/state").contains("organizerPin") && !get("/api/state").contains("eventpinhash"), "Public state exposed event credentials");
            String secondPolicy = (String) second.until(game -> "Night Games".equals(game.get(EventMode.TITLE))).get(EventMode.REVISION);
            check(post("/api/players",Map.of("id",1,"identity",person,"eventLocked",false)).statusCode() == 200, "Could not release a phone");
            first.until(game -> Boolean.FALSE.equals(game.get(EventMode.PINNING)));
            check(secondPolicy.equals(second.until(game -> game.containsKey("players")).get(EventMode.REVISION)), "A per-phone release changed another phone policy");
            first.send(JSON + "{\"eventlock\":\"locked\"}");
            await(() -> active(1).get("lockStatus").equals("locked"), "Lock status not published");
            first.send(JSON + "{\"batterystatus\":{\"phone\":17,\"charging\":true,\"guns\":[{\"type\":2,\"connected\":true,\"lowPower\":false,\"level\":\"good\",\"voltageTenths\":48}]}}");
            await(() -> active(1).get("battery") != null, "Battery status missing");
            check(number(object(active(1).get("battery")).get("phone")) == 17, "Battery percent changed");
            GunBatteryHistory readings = new GunBatteryHistory(20);
            readings.begin(UUID.randomUUID().toString(), person, System.currentTimeMillis());
            readings.observe("AA:BB:CC:DD:EE:01",0,2,48);
            readings.observe("AA:BB:CC:DD:EE:02",1,2,50);
            battery(first, readings);
            await(() -> history(person).size()==1, "Round battery history missing");
            readings.observe("AA:BB:CC:DD:EE:01",0,2,42);battery(first,readings);
            await(() -> minimum(person,0,0)==42, "Round low did not update");
            check(minimum(person,0,1)==50, "Second gun lost its independent minimum");
            check(!get("/api/state").contains("AA:BB:CC:DD:EE:01"), "Public dashboard exposed private gun history");
            check(post("/api/players",Map.of("action","battery","id",1,"identity",person)).statusCode() == 200, "Battery refresh failed");
            first.until(game -> Boolean.TRUE.equals(game.get(EventMode.BATTERY_REQUEST)));
            check(post("/api/players",Map.of("action","transfer","identity",person,"targetId",2,"targetIdentity",replacementRow.get("identity"))).statusCode() == 200, "Phone transfer failed");
            replacement.until(game -> person.equals(game.get(EventMode.PLAYER_IDENTITY)) && rosterName(game,2).equals("Pilot"));
            check(active(2).get("identity").equals(person) && number(active(2).get("team")) == 2, "Transfer lost identity/team");
            check(number(object(active(2).get("settings")).get("health")) == 12, "Transfer lost gameplay settings");
            check(minimum(person,0,0)==42, "Phone transfer lost gun history");
            check(post("/api/players",Map.of("id",17,"identity",active(17).get("identity"),"team",1)).statusCode() == 200, "Opponent team edit failed");
            second.syncClock();replacement.syncClock();
            replacement.send(MESSAGE + "STARTGAME");
            replacement.send(JSON + "{\"clocksync\":99}");replacement.until(game -> game.get("clocksync") instanceof Number && number(game.get("clocksync")) == 99);
            check(state().get("state").equals("lobby"), "Phone started an event round");
            check(post("/api/start",Map.of()).statusCode() == 200, "Host could not start custom round");
            String nextRound = (String) replacement.until(game -> game.containsKey("gamestart")).get("roundtoken");
            readings.begin(nextRound, person, System.currentTimeMillis());
            readings.observe("AA:BB:CC:DD:EE:01",0,2,55);battery(replacement,readings);
            await(() -> history(person).size()==2, "New round overwrote the previous round's low");
            check(minimum(person,0,0)==42&&minimum(person,1,0)==55, "Round minima mixed together");
            await(() -> state().get("state").equals("running"), "Round did not start");
            check(post("/api/players",Map.of("id",2,"identity",person,"name","Live pilot")).statusCode() == 200, "Live rename failed");
            check(post("/api/players",Map.of("id",2,"identity",person,"name","Must not commit","settings",settings)).statusCode() == 409, "Live gameplay edit accepted");
            check(active(2).get("name").equals("Live pilot"), "Rejected live edit changed the name");
            check(post("/api/players",Map.of("action","transfer","identity",person,"targetId",17,"targetIdentity",active(17).get("identity"))).statusCode() == 409, "Mid-round phone transfer accepted");
            replacement.send(MESSAGE + "ELIMINATED17");
            await(() -> number(active(2).get("deaths")) == 1, "Elimination was not recorded");
            check(post("/api/respawn?id=2",Map.of()).statusCode() == 200, "Eligible respawn blocked");
            replacement.send(MESSAGE + "ELIMINATED17");
            await(() -> number(active(2).get("deaths")) == 2, "Second elimination was not recorded");
            check(post("/api/respawn?id=2",Map.of()).statusCode() == 409, "Host bypassed the edited lives limit");
            check(post("/api/end?roundtoken=" + nextRound + "&action=end-round",Map.of()).statusCode() == 200,
                    "Event host could not end the round");
            check(state().get("state").equals("finished"), "Event end waited for phone approval");
        } finally { stop(host); }
        host = start();
        try (Phone restored = new Phone("127.0.0.4",2,"Fresh phone name",replacementDevice);
             Phone oldPhone = new Phone("127.0.0.2",3,"Unassigned phone",firstDevice)) {
            check(active(2).get("name").equals("Live pilot"), "Restart lost the saved name or binding");
            check(number(object(active(2).get("settings")).get("reloadshots")) == 60, "Restart lost gameplay settings");
            check(state().get("eventTitle").equals("Night Games"), "Restart lost event name");
            Map<String,Object> saved = saved((String) active(2).get("identity"));
            check(number(saved.get("totalDeaths")) == 2, "Player history was lost or double counted");
            check(history((String) active(2).get("identity")).size()==2, "Restart lost gun battery rounds");
            check(minimum((String) active(2).get("identity"),0,0)==42, "Restart raised a saved minimum");
            check(!active(2).get("identity").equals(active(3).get("identity")), "Original phone reclaimed a transferred person");
        } finally { stop(host); }
        host = start("--boss");
        try (Phone boss = new Phone("127.0.0.2",1,"Boss",firstDevice);
             Phone hunter = new Phone("127.0.0.4",2,"Hunter",replacementDevice)) {
            check(Boolean.TRUE.equals(hunter.lastRoster.get("tournamentmode")), "Saved custom profiles disabled Boss rules");
            check(Boolean.FALSE.equals(hunter.lastRoster.get("balancedrandom")), "Saved manual teams broke Boss mode");
            for (Object row : (List<?>) hunter.lastRoster.get("players")) check(!object(row).containsKey("team"), "Boss roster advertised incompatible balanced team assignments");
            check(number(object(active(2).get("settings")).get("health")) == 2, "Saved custom health overwrote the hunter profile");
        } finally { stop(host); }
        System.out.println("Player controls, battery rounds, event policies, phone transfers, and persistence passed");
    }
    private static void verifyReturningIdentityHasOnlyOneSeat() throws Exception {
        Path previousRoot = root;root = Files.createTempDirectory("simplecoil-returning-player");
        Process host = start();String device = UUID.randomUUID().toString();
        try (Phone original = new Phone("127.0.0.2",1,"Steph",device)) {
            String person = (String) active(1).get("identity");
            Map<String,Object> settings = new LinkedHashMap<>(object(active(1).get("settings")));
            settings.remove("playerID");settings.put("health",12);
            check(post("/api/players",Map.of("id",1,"identity",person,"name","Saved Steph","settings",settings)).statusCode()==200,
                    "Could not prepare the returning player's saved profile");
            original.close();
            await(() -> Boolean.FALSE.equals(active(1).get("connected")), "Old admitted seat did not disconnect");
            // A DHCP change or fresh discovery can assign a new slot before
            // the host learns which saved player is registering over TCP.
            try (Phone returned = new Phone("127.0.0.3",3,"Phone name",device)) {
                await(() -> uniqueIdentitySeat(person,3), "Returning phone left a duplicate admitted player row");
                check(active(3).get("name").equals("Saved Steph"), "New seat lost the saved player name");
                check(number(object(active(3).get("settings")).get("health"))==12, "New seat lost host gameplay edits");
                checkRelayAddress(returned,device,"127.0.0.3");
                // The older socket can still look connected when the new
                // registration arrives. It must retire without deleting the
                // replacement row when its delayed disconnect callback runs.
                try (Phone replacedAgain = new Phone("127.0.0.4",5,"Phone name",device)) {
                    await(() -> uniqueIdentitySeat(person,5), "A still-connected previous seat was duplicated");
                    checkRelayAddress(replacedAgain,device,"127.0.0.4");
                    replacedAgain.send(JSON+"{\"batterystatus\":{\"phone\":42,\"charging\":true,\"guns\":[]}}");
                    await(() -> active(5).get("battery")!=null, "Retiring the old connection removed its replacement");
                    try (Phone newcomer = new Phone("127.0.0.5",1,"New player",UUID.randomUUID().toString())) {
                        check(Boolean.TRUE.equals(active(1).get("connected")), "Retired identity kept its old seat reserved");
                        check(uniqueIdentitySeat(person,5), "Reusing a retired seat duplicated the returning player");
                    }
                }
            }
        } finally {stop(host);root=previousRoot;}
    }
    private static boolean uniqueIdentitySeat(String identity,int id) throws Exception {
        int count=0;boolean expected=false;
        for(Object value:(List<?>)object(LaptopHost.Json.parse(get("/api/players"))).get("players")) {
            Map<String,Object> player=object(value);
            if(identity.equals(player.get("identity"))) {
                count++;
                expected=number(player.get("id"))==id && Boolean.TRUE.equals(player.get("connected"));
            }
        }
        return count==1&&expected;
    }
    private static void checkRelayAddress(Phone phone,String device,String address) throws Exception {
        Map<String,Object> roster=phone.lastRoster;
        if(!roster.containsKey(com.simplecoil.protocol.RelayConfig.WIRE))
            roster=phone.until(game->game.containsKey(com.simplecoil.protocol.RelayConfig.WIRE));
        com.simplecoil.protocol.RelayConfig config=com.simplecoil.protocol.RelayConfig.decode(
                (String)roster.get(com.simplecoil.protocol.RelayConfig.WIRE));
        check(config.members.get(UUID.fromString(device)).address.getHostAddress().equals(address),
                "An old admitted seat overwrote the returning phone's relay endpoint");
    }
    private static void verifyRelay() throws Exception {
        Path previousRoot=root;root=Files.createTempDirectory("simplecoil-relay-controls");
        Process host=start();String bridgeId=UUID.randomUUID().toString(), runnerId=UUID.randomUUID().toString();
        try (Phone bridge=new Phone("127.0.0.2",1,"Bridge",bridgeId);
             Phone nativeRunner=new Phone("127.0.0.3",17,"Runner",runnerId)) {
            Map<String,Object> roster=bridge.until(game->game.containsKey(com.simplecoil.protocol.RelayConfig.WIRE));
            com.simplecoil.protocol.RelayConfig config=com.simplecoil.protocol.RelayConfig.decode((String)roster.get(com.simplecoil.protocol.RelayConfig.WIRE));
            // The first snapshot can precede the second registration.
            if(!config.members.containsKey(UUID.fromString(runnerId)))
                config=com.simplecoil.protocol.RelayConfig.decode((String)nativeRunner.until(game->game.containsKey(com.simplecoil.protocol.RelayConfig.WIRE)).get(com.simplecoil.protocol.RelayConfig.WIRE));
            check(config.owner.equals(config.host) && config.members.values().stream().allMatch(member -> member.p2p.isEmpty()),
                    "Legacy phones were instructed to prepare Wi-Fi Direct invitations");
            nativeRunner.close();
            Map<String,Object> disconnected = bridge.until(game -> game.containsKey("players")
                    && rosterName(game,17).isEmpty() && game.containsKey(com.simplecoil.protocol.RelayConfig.WIRE));
            config = com.simplecoil.protocol.RelayConfig.decode((String)disconnected.get(com.simplecoil.protocol.RelayConfig.WIRE));
            check(config.members.containsKey(UUID.fromString(runnerId)), "Lobby dropout revoked the phone's fallback membership");
            check(Boolean.FALSE.equals(active(17).get("connected")), "Disconnected relay phone still appeared online");
            try (DatagramSocket newcomer = new DatagramSocket(null)) {
                newcomer.setReuseAddress(true);
                newcomer.bind(new InetSocketAddress("127.0.0.4",19700));
                newcomer.setSoTimeout(1000);
                byte[] request = "SimpleCoil:JOIN3117".getBytes(StandardCharsets.UTF_8);
                newcomer.send(new DatagramPacket(request,request.length,InetAddress.getByName("127.0.0.1"),19700));
                DatagramPacket reply = new DatagramPacket(new byte[128],128);
                newcomer.receive(reply);
                check(new String(reply.getData(),reply.getOffset(),reply.getLength(),StandardCharsets.UTF_8)
                        .equals("SimpleCoil:SERVERREPLY:18"), "A new phone took the reconnecting relay player's seat");
            }
            com.simplecoil.protocol.RelayMesh.Listener unused=new com.simplecoil.protocol.RelayMesh.Listener(){
                public void datagram(InetAddress from,InetAddress to,int port,byte[] bytes){}
                public boolean accept(Socket socket,int port){return false;}
            };
            try(com.simplecoil.protocol.RelayMesh nearby=new com.simplecoil.protocol.RelayMesh(UUID.fromString(bridgeId),config,new InetSocketAddress("127.0.0.1",0),unused);
                com.simplecoil.protocol.RelayMesh outOfRange=new com.simplecoil.protocol.RelayMesh(UUID.fromString(runnerId),config,new InetSocketAddress("127.0.0.1",0),unused)) {
                nearby.connect(new InetSocketAddress("127.0.0.1",19714));
                outOfRange.connect(new InetSocketAddress("127.0.0.1",nearby.port()));
                await(outOfRange::hostReachable,"Host was not reachable through the nearby phone");
                try(Phone relayed=new Phone(outOfRange.open(config.members.get(config.host).address,TCP,3000),17,"Runner",runnerId,true)) {
                    await(()->Boolean.TRUE.equals(active(17).get("viaRelay")),"Host did not accept the relayed phone");
                    // A quiet application must not inherit a transport's old
                    // five-second read timeout. Leave the same virtual socket
                    // idle, then complete a host command and a fresh report.
                    long quietUntil = System.nanoTime() + 6_500_000_000L;
                    while (System.nanoTime() < quietUntil) {
                        check(Boolean.TRUE.equals(active(17).get("connected")), "Host dropped an idle relay before its heartbeat deadline");
                        Thread.sleep(100);
                    }
                    String relayPerson = (String) active(17).get("identity");
                    check(post("/api/players",Map.of("action","battery","id",17,"identity",relayPerson)).statusCode()==200,
                            "Host could not request a battery report after relay idle");
                    relayed.until(game -> Boolean.TRUE.equals(game.get(EventMode.BATTERY_REQUEST)));
                    relayed.send(JSON + "{\"batterystatus\":{\"phone\":17,\"charging\":true,\"guns\":[{\"type\":2,\"connected\":true,\"lowPower\":false,\"level\":\"good\",\"voltageTenths\":48}]}}");
                    await(() -> active(17).get("battery") != null, "Fresh battery report was lost after relay idle");
                    check(Boolean.TRUE.equals(active(17).get("viaRelay")), "Relay idle recovery replaced the virtual connection");
                    bridge.syncClock();relayed.syncClock();
                    check(post("/api/start",Map.of()).statusCode()==200,"Relayed phone could not start with the shared game");
                    await(()->state().get("state").equals("running"),"Relayed round did not start");
                    String person=(String)active(17).get("identity");
                    check(post("/api/players",Map.of("id",17,"identity",person,"name","Relay runner")).statusCode()==200,"Host could not edit the relayed player");
                    relayed.until(game->rosterName(game,17).equals("Relay runner"));
                    relayed.send(MESSAGE+"ELIMINATED1");
                    await(()->number(active(17).get("deaths"))==1,"Relayed game event was lost");
                    check(post("/api/end?roundtoken=" + state().get("roundtoken") + "&action=end-round",Map.of()).statusCode()==200,
                            "Host could not end the relayed game");
                    check(state().get("state").equals("finished"),"Relay created a separate round");
                }
            }
        } finally {stop(host);root=previousRoot;}
    }
    private static void verifyDirectory() throws Exception {
        Path file=root.resolve("unit/players.json");PlayerDirectory directory=new PlayerDirectory(file);
        String old=UUID.randomUUID().toString(), replacement=UUID.randomUUID().toString();
        PlayerDirectory.Profile profile=directory.forDevice(old,"Saved player");profile.record("10000000-0000-0000-0000-000000000001",2,1,9,4);profile.record(profile.lastRound,2,1,9,4);
        directory.bind(profile.id,replacement);directory.save();PlayerDirectory loaded=new PlayerDirectory(file);
        check(loaded.forDevice(replacement,"new").id.equals(profile.id),"Directory lost phone binding");
        check(!loaded.forDevice(old,"unassigned").id.equals(profile.id),"Old phone retained binding");
        check(loaded.profiles.get(profile.id).totalKills==2,"Repeated stat saves counted a round twice");
        if(Files.getFileStore(file).supportsFileAttributeView("posix"))check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"Player store is not private");
        Files.writeString(file,"{broken");try{new PlayerDirectory(file);throw new AssertionError("Corrupt directory silently replaced");}catch(IOException expected){}
        check(Files.readString(file).equals("{broken"),"Corrupt directory overwritten");
    }
    private static void verifyManualTeamsOverrideBalancedRandom() throws Exception {
        Path previousRoot = root;
        root = Files.createTempDirectory("simplecoil-manual-balanced-teams");
        Process host = start("--balanced-qr");
        try (Phone first = new Phone("127.0.0.2", 1, "First", UUID.randomUUID().toString());
             Phone second = new Phone("127.0.0.3", 17, "Second", UUID.randomUUID().toString())) {
            check(post("/api/players", Map.of("id", 1, "identity", active(1).get("identity"), "team", 2))
                    .statusCode() == 200, "Operator team assignment failed in a balanced lobby");
            check(post("/api/players", Map.of("id", 17, "identity", active(17).get("identity"), "team", 1))
                    .statusCode() == 200, "Operator opponent assignment failed");
            first.syncClock(); second.syncClock();
            Map<String, Object> lobby = state();
            check(Boolean.FALSE.equals(lobby.get("balancedQr"))
                            && Boolean.FALSE.equals(lobby.get("canRebalance")),
                    "Operator teams still required random-team QR check-ins or rebalancing");
            check(post("/api/rebalance", Map.of()).statusCode() == 409,
                    "Random rebalancing overrode the operator's teams");
            check(post("/api/start", Map.of()).statusCode() == 200,
                    "Operator teams could not start without random-team QR scans");
            first.until(game -> game.containsKey("gamestart"));
            check(number(active(1).get("team")) == 2 && number(active(17).get("team")) == 1,
                    "Starting the round replaced the operator's team assignments");
        } finally {
            stop(host);
            root = previousRoot;
        }
    }

    private static Process start(String... extra) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-Dsimplecoil.players.file="+root.resolve("players.json"),"-Dsimplecoil.event.file="+root.resolve("event.properties"),
                "-Dsimplecoil.reports="+root.resolve("reports"),
                "--add-modules","jdk.httpserver","-cp",Path.of("laptop-host/out").toAbsolutePath().toString(),
                "com.simplecoil.laptophost.LaptopHost","--no-browser","--tcp-port","19710","--udp-port","19700","--dashboard-port","19711","--tile-port","19712","--start-delay","1");
        builder.command().addAll(Arrays.asList(extra));
        Process p=builder.redirectErrorStream(true).redirectOutput(root.resolve("host.log").toFile()).start();
        try {await(() -> {try{return state().containsKey("players");}catch(Exception ignored){return false;}},"Host did not start");}
        catch(Exception|AssertionError failure){stop(p);throw new AssertionError(Files.readString(root.resolve("host.log")),failure);}return p;
    }
    private static void stop(Process process)throws Exception{process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();}
    private static HttpResponse<String> control(String path,String body,String origin,boolean header)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+HTTP+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");if(header)request.header("X-SimpleCoil-Control","1");if(origin!=null)request.header("Origin",origin);return HTTP_CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(String path,Map<String,Object> body)throws Exception{return control(path,LaptopHost.Json.stringify(body),null,true);}
    private static String get(String path)throws Exception{return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+HTTP+path)).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString()).body();}
    private static Map<String,Object> state()throws Exception{return object(LaptopHost.Json.parse(get("/api/state")));}
    private static Map<String,Object> active(int id)throws Exception{for(Object value:(List<?>)object(LaptopHost.Json.parse(get("/api/players"))).get("players")){Map<String,Object> row=object(value);if(number(row.get("id"))==id)return row;}throw new AssertionError("Missing player #"+id);}
    private static Map<String,Object> saved(String id)throws Exception{for(Object value:(List<?>)object(LaptopHost.Json.parse(get("/api/players"))).get("savedPlayers")){Map<String,Object> row=object(value);if(row.get("identity").equals(id))return row;}throw new AssertionError("Missing saved player");}
    private static List<?> history(String person)throws Exception{return(List<?>)saved(person).get("gunBatteryRounds");}
    private static int minimum(String person,int round,int gun)throws Exception{return number(object(((List<?>)object(history(person).get(round)).get("guns")).get(gun)).get("minimumTenths"));}
    private static void battery(Phone phone,GunBatteryHistory history)throws Exception{phone.send(JSON+LaptopHost.Json.stringify(Map.of("batterystatus",Map.of("phone",17,"charging",true,"guns",List.of(Map.of("id","AA:BB:CC:DD:EE:01","type",2,"connected",true,"lowPower",false,"level","good","voltageTenths",55)),"rounds",history.snapshot()))));}
    private static String rosterName(Map<String,Object> game,int id){if(!(game.get("players") instanceof List))return "";for(Object value:(List<?>)game.get("players")){Map<String,Object> row=object(value);if(number(row.get("playerID"))==id)return(String)row.get("playername");}return "";}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){return(Map<String,Object>)value;}
    private static int number(Object value){return((Number)value).intValue();}
    private static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
    private interface Condition{boolean get()throws Exception;}
    private static void await(Condition condition,String message)throws Exception{long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){if(condition.get())return;Thread.sleep(25);}throw new AssertionError(message);}
    private static final class Phone implements AutoCloseable{
        private final Socket socket;private final DataInputStream input;private final DataOutputStream output;private Map<String,Object> lastRoster;private boolean scoreboardRequested, clockSynchronized;
        Phone(String address,int id,String name,String identity)throws Exception{this(connectSocket(address),id,name,identity,false);}
        private static Socket connectSocket(String address)throws Exception{Socket socket=new Socket();socket.bind(new InetSocketAddress(address,0));socket.connect(new InetSocketAddress("127.0.0.1",TCP));return socket;}
        Phone(Socket socket,int id,String name,String identity,boolean rejoin)throws Exception{this.socket=socket;socket.setSoTimeout(4000);input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());Map<String,Object> registration=new LinkedHashMap<>(Map.of("playerID",id,"playername",name,"playeridentity",identity,"deviceidentity",identity,"eventcontrol",2,"lobbyready",true,"relaynode",identity,"relayp2p","02:11:22:33:44:55"));registration.put("rejoin",rejoin);send(JSON+LaptopHost.Json.stringify(registration));lastRoster=until(game->game.containsKey("players"));syncClock();}
        void send(String frame)throws IOException{if(frame.equals(MESSAGE+"PLAYERDATAREQUEST"))scoreboardRequested=true;output.writeUTF(frame);output.flush();}
        Map<String,Object> until(Predicate<Map<String,Object>> predicate)throws Exception{long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){String frame=input.readUTF();if(frame.equals(MESSAGE+"ping")){send(MESSAGE+"pong");continue;}if(frame.startsWith(JSON)){Map<String,Object> game=object(LaptopHost.Json.parse(frame.substring(JSON.length())));if(game.containsKey("playerdata")){check(scoreboardRequested,"Host pushed an unsolicited modal scoreboard");scoreboardRequested=false;}if(predicate.test(game))return game;}}throw new AssertionError("Expected phone frame was not received");}
        // Match real phones: calibrate after initial metadata before expecting
        // subsequent organizer edits, which are coalesced during calibration.
        void syncClock()throws Exception{if(clockSynchronized)return;for(int i=1;i<=12;i++){send(JSON+"{\"clocksync\":"+i+"}");final int sequence=i;until(game->game.get("clocksync") instanceof Number&&number(game.get("clocksync"))==sequence);}send(JSON+"{\"clockready\":true}");until(game->game.containsKey("players"));clockSynchronized=true;}
        public void close()throws IOException{socket.close();}
    }
}
