package com.simplecoil.laptophost;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real dashboard routes with isolated files, no ADB, and no real Wi-Fi commands. */
public final class PhoneUpdateControlsTest {
    private static final String API = "/api/phone-updates";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        try (Fixture fixture = new Fixture()) {
            verifyPageAndReadOnlyService(fixture);
            verifyLocalRequestGuards(fixture);
            verifyCommandValidation(fixture);
            verifyMaintenanceGate(fixture);
            check(!Files.exists(fixture.root.resolve("adb-phone-updates.json")),
                    "Disabled ADB backend wrote enrollment state");
        }
        System.out.println("Phone update pages, local-only controls, strict commands, read-only LAN routes, and maintenance gates passed");
    }

    private static void verifyPageAndReadOnlyService(Fixture fixture) throws Exception {
        HttpResponse<String> page = fixture.get("/phone-updates");
        check(page.statusCode() == 200 && page.body().contains("One-time USB setup")
                && page.body().contains("Automatically update enrolled phones")
                && page.body().contains("/api/phone-updates"), "Phone update controls page is unavailable");
        check(page.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "Phone update controls page can be served stale");
        check(fixture.get("/").body().contains("href=\"/phone-updates\""),
                "Host launcher does not link to phone updates");
        check(fixture.request(fixture.dashboardPort, "/phone-updates", "POST", "", Map.of()).statusCode() == 405,
                "Phone update page accepted a non-GET request");
        HttpResponse<String> status = fixture.get(API);
        check(status.statusCode() == 200, "Local update status is unavailable");
        Map<?, ?> state = object(status.body());
        for (String field : List.of("enabled", "available", "busy", "gameActive", "message", "adbVersion",
                "cachedUpdate", "phones", "usbPhones"))
            check(state.containsKey(field), "Update status omitted " + field);
        check(Boolean.FALSE.equals(state.get("enabled")) && Boolean.FALSE.equals(state.get("available")),
                "Isolated disabled backend became active");
        check(status.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"),
                "Update status omitted its content-type guard");
        for (String path : List.of("/phone-updates", API, "/api/wifi")) {
            check(fixture.request(fixture.tilePort, path, "GET", "", Map.of()).statusCode() == 404,
                    "Operator route leaked onto the read-only LAN server: " + path);
            check(fixture.request(fixture.tilePort, path, "POST", "{}", controlHeaders()).statusCode() == 404,
                    "Operator mutation leaked onto the read-only LAN server: " + path);
        }
        check(fixture.request(fixture.tilePort, "/api/app-update/latest", "GET", "", Map.of()).statusCode() == 503,
                "Empty disabled cache unexpectedly offered an update");
        check(fixture.request(fixture.tilePort, "/api/app-update/latest", "POST", "{}", controlHeaders()).statusCode() == 405,
                "Read-only cached-update service accepted a mutation");
    }

    private static void verifyLocalRequestGuards(Fixture fixture) throws Exception {
        String command = "{\"action\":\"set-enabled\",\"enabled\":true}";
        check(fixture.request(fixture.dashboardPort, API, "GET", "",
                Map.of("Origin", "https://example.invalid")).statusCode() == 403,
                "Cross-origin update status was accepted");
        check(fixture.request(fixture.dashboardPort, "/phone-updates", "GET", "",
                Map.of("Sec-Fetch-Site", "cross-site")).statusCode() == 403,
                "Cross-site phone update page was accepted");
        Map<String, String> crossOrigin = new LinkedHashMap<>(controlHeaders());
        crossOrigin.put("Origin", "http://127.0.0.1:" + (fixture.dashboardPort == 65535 ? 1 : fixture.dashboardPort + 1));
        check(fixture.request(fixture.dashboardPort, API, "POST", command, crossOrigin).statusCode() == 403,
                "A different origin port was accepted");
        Map<String, String> sameOrigin = new LinkedHashMap<>(controlHeaders());
        sameOrigin.put("Origin", "http://127.0.0.1:" + fixture.dashboardPort);
        sameOrigin.put("Sec-Fetch-Site", "same-origin");
        check(fixture.request(fixture.dashboardPort, API, "POST", command, sameOrigin).statusCode() == 409,
                "Valid same-origin request did not reach the disabled backend");
        check(fixture.request(fixture.dashboardPort, API, "POST", command,
                Map.of("Content-Type", "application/json")).statusCode() == 403,
                "Update command did not require the control header");
        check(fixture.request(fixture.dashboardPort, API, "POST", command,
                Map.of("Content-Type", "text/plain", "X-SimpleCoil-Control", "1")).statusCode() == 403,
                "Update command accepted a non-JSON content type");
        check(fixture.spoofedHost("example.invalid:" + fixture.dashboardPort) == 403,
                "Untrusted Host header was accepted");
        check(fixture.spoofedHost("127.0.0.1:" + (fixture.dashboardPort == 65535 ? 1 : fixture.dashboardPort + 1)) == 403,
                "Mismatched Host port was accepted");
        Method guard = method(fixture.host.getClass(), "localWifiRequest", HttpExchange.class);
        check(Boolean.FALSE.equals(guard.invoke(fixture.host,
                new RemoteExchange(fixture.dashboardPort, "192.0.2.20"))),
                "Non-loopback client passed the operator request guard");
        check(Boolean.TRUE.equals(guard.invoke(fixture.host,
                new RemoteExchange(fixture.dashboardPort, "127.0.0.1"))),
                "Valid loopback client failed the operator request guard");
    }

    private static void verifyCommandValidation(Fixture fixture) throws Exception {
        check(fixture.request(fixture.dashboardPort, API, "DELETE", "", Map.of()).statusCode() == 405,
                "Update API accepted an unsupported method");
        check(fixture.post(API, "x".repeat(4097)).statusCode() == 413,
                "Oversized phone update request was accepted");
        for (String invalid : List.of("[]", "null", "{", "{}",
                "{\"action\":\"shell\",\"serial\":\"phone\"}",
                "{\"action\":\"set-enabled\",\"enabled\":\"true\"}",
                "{\"action\":\"set-enabled\",\"enabled\":true,\"command\":\"adb install\"}",
                "{\"action\":\"enroll-usb\",\"serial\":1}",
                "{\"action\":\"retry\",\"url\":\"http://example.invalid/\"}",
                "{\"action\":\"remove\",\"serial\":\"phone\",\"path\":\"/tmp/other.apk\"}"))
            check(fixture.post(API, invalid).statusCode() == 400,
                    "Invalid phone update command was accepted: " + invalid);
        for (String valid : List.of("{\"action\":\"set-enabled\",\"enabled\":true}",
                "{\"action\":\"enroll-usb\",\"serial\":\"phone-123\"}",
                "{\"action\":\"retry\",\"serial\":\"phone-123\"}",
                "{\"action\":\"remove\",\"serial\":\"phone-123\"}")) {
            HttpResponse<String> reply = fixture.post(API, valid);
            check(reply.statusCode() == 409 && Boolean.FALSE.equals(object(reply.body()).get("ok")),
                    "Disabled backend accepted an update mutation");
        }
    }

    private static void verifyMaintenanceGate(Fixture fixture) throws Exception {
        Field lease = field(fixture.host.getClass(), "adbPhoneLease");
        Object lock = field(fixture.host.getClass(), "stateLock").get(fixture.host);
        synchronized (lock) { lease.set(fixture.host, new Object()); }
        try {
            Map<?, ?> state = object(fixture.get("/api/state").body());
            check(Boolean.TRUE.equals(state.get("phoneUpdateInProgress"))
                    && Boolean.FALSE.equals(state.get("canStart")), "Dashboard did not disable Start during phone maintenance");
            Object result = method(fixture.host.getClass(), "startRound").invoke(fixture.host);
            check(Boolean.FALSE.equals(field(result.getClass(), "ok").get(result))
                    && ((String) field(result.getClass(), "message").get(result)).contains("phone update"),
                    "START did not reject phone maintenance before ordinary lobby readiness checks");
            HttpResponse<String> wifi = fixture.post("/api/wifi", "{\"action\":\"invalid-test-action\"}");
            check(wifi.statusCode() == 409 && wifi.body().contains("phone update"),
                    "Wi-Fi changes were allowed during phone maintenance");
            check(fixture.networkCommands.get() == 0, "Maintenance rejection reached Wi-Fi commands");
        } finally {
            synchronized (lock) { lease.set(fixture.host, null); }
        }
        check(Boolean.FALSE.equals(object(fixture.get("/api/state").body()).get("phoneUpdateInProgress")),
                "Clearing maintenance did not reset the dashboard flag");
        Object result = method(fixture.host.getClass(), "startRound").invoke(fixture.host);
        check(((String) field(result.getClass(), "message").get(result)).contains("At least two phones"),
                "Clearing maintenance did not restore normal START validation");
        check(fixture.post("/api/wifi", "{\"action\":\"invalid-test-action\"}").statusCode() == 400,
                "Clearing maintenance did not restore normal Wi-Fi validation");
    }

    private static Map<String, String> controlHeaders() {
        return Map.of("Content-Type", "application/json", "X-SimpleCoil-Control", "1");
    }
    private static Map<?, ?> object(String json) {
        Object parsed = LaptopHost.Json.parse(json);
        check(parsed instanceof Map<?, ?>, "Expected a JSON object");
        return (Map<?, ?>) parsed;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);method.setAccessible(true);return method;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("simplecoil-phone-update-controls-");
        final Map<String, String> previous = new LinkedHashMap<>();
        final AtomicInteger networkCommands = new AtomicInteger();
        Object host;
        int dashboardPort, tilePort;

        Fixture() throws Exception {
            Path web = Files.createDirectories(root.resolve("host/web"));
            Files.copy(Path.of("laptop-host/web/phone-updates.html"), web.resolve("phone-updates.html"));
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("user.home", root.toString());
            properties.put("simplecoil.home", root.resolve("host").toString());
            properties.put("simplecoil.event.file", root.resolve("event.properties").toString());
            properties.put("simplecoil.players.file", root.resolve("players.json").toString());
            properties.put("simplecoil.wifi.settings", root.resolve("hotspot.properties").toString());
            properties.put("simplecoil.phoneupdates.cache", root.resolve("cache").toString());
            properties.put("simplecoil.adb.updates.file", root.resolve("adb-phone-updates.json").toString());
            properties.put("simplecoil.phoneupdates.enabled", "false");
            properties.put("simplecoil.adb.updates.enabled", "false");
            for (var property : properties.entrySet()) {
                previous.put(property.getKey(), System.getProperty(property.getKey()));
                System.setProperty(property.getKey(), property.getValue());
            }
            try {
                Class<?> configType = Class.forName("com.simplecoil.laptophost.LaptopHost$HostConfig");
                Object config = method(configType, "parse", String[].class).invoke(null,
                        (Object) new String[]{"--no-browser", "--no-event-mode", "--reports", root.resolve("reports").toString()});
                field(configType, "tcpPort").setInt(config, 0);
                try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                    field(configType, "udpPort").setInt(config, reservation.getLocalPort());
                }
                field(configType, "dashboardPort").setInt(config, 0);
                field(configType, "tilePort").setInt(config, 0);
                field(configType, "tileBind").set(config, "127.0.0.1");
                field(configType, "webDirectory").set(config, web);
                field(configType, "tileDirectory").set(config, root.resolve("maps"));
                Constructor<?> constructor = LaptopHost.class.getDeclaredConstructor(configType);
                constructor.setAccessible(true);host = constructor.newInstance(config);
                field(LaptopHost.class, "hotspot").set(host, new HotspotManager(root.resolve("hotspot.properties"), command -> {
                    networkCommands.incrementAndGet();return new HotspotManager.Result(1, "No network tools in this isolated test.");
                }));
                method(LaptopHost.class, "start").invoke(host);
                dashboardPort = ((HttpServer) field(LaptopHost.class, "dashboard").get(host)).getAddress().getPort();
                tilePort = ((HttpServer) field(LaptopHost.class, "tileServer").get(host)).getAddress().getPort();
            } catch (Exception failure) { close();throw failure; }
        }

        HttpResponse<String> get(String path) throws Exception { return request(dashboardPort, path, "GET", "", Map.of()); }
        HttpResponse<String> post(String path, String body) throws Exception {
            return request(dashboardPort, path, "POST", body, controlHeaders());
        }
        HttpResponse<String> request(int port, String path, String verb, String body, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5));
            headers.forEach(request::header);
            return HTTP.send(request.method(verb, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        int spoofedHost(String value) throws Exception {
            try (Socket socket = new Socket("127.0.0.1", dashboardPort)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("GET " + API + " HTTP/1.1\r\nHost: " + value
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                return Integer.parseInt(status.split(" ")[1]);
            }
        }
        @Override public void close() throws Exception {
            try { if (host != null) method(LaptopHost.class, "stop").invoke(host); }
            finally {
                for (var property : previous.entrySet()) {
                    if (property.getValue() == null) System.clearProperty(property.getKey());
                    else System.setProperty(property.getKey(), property.getValue());
                }
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    /** Exercises the peer-address check independently of which interfaces a CI machine has. */
    private static final class RemoteExchange extends HttpExchange {
        final int port;final String address;final Headers headers = new Headers();
        RemoteExchange(int port, String address) {
            this.port = port;this.address = address;headers.set("Host", "127.0.0.1:" + port);
        }
        @Override public Headers getRequestHeaders() { return headers; }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create(API); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int status, long length) { }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(address, 12345); }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", port); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
