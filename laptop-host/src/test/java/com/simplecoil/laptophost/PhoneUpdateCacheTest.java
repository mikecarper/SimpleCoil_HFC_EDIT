package com.simplecoil.laptophost;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** No GitHub or real host mutations: injected upstream, private temporary cache, real LAN HTTP. */
public final class PhoneUpdateCacheTest {
    private static final String CDN = "https://release-assets.githubusercontent.com/test.apk?signature=test";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        verifyColdWarmCoalescingAndReadOnlyRoutes();
        verifyOfflineRestartAndCorruption();
        verifyFailedRefreshPreservesGoodCacheAndImmutableIDs();
        verifyInvalidMetadataAndRedirects();
        verifyGameDeferralAndBoundedSlowTransfers();
        System.out.println("Phone update cache, atomic publication, offline integrity, strict upstream, and isolated HTTP streams passed");
    }

    private static void verifyColdWarmCoalescingAndReadOnlyRoutes() throws Exception {
        Network network = new Network(1, data(128 * 1024, 7));
        network.metadataGate = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(Files.createTempDirectory("phone-update-warm-"), network)) {
            Path orphan = Files.createDirectory(fixture.path.resolve(".download-12345"));
            Files.write(orphan.resolve("app.apk"), new byte[1024]);
            Files.writeString(orphan.resolve("release.json"), "partial");
            Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11)));
            Path recent = Files.createDirectory(fixture.path.resolve(".download-12346"));
            Path pointer = Files.writeString(fixture.path.resolve(".latest-12345.tmp"), "partial");
            Files.setLastModifiedTime(pointer, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11)));
            fixture.cache.start();
            await(() -> network.apiCalls.get() == 1, "Startup did not fetch release metadata");
            for (int count = 0; count < 8; count++) {
                HttpResponse<byte[]> reply = fixture.get(PhoneUpdateCache.LATEST_PATH);
                require(reply.statusCode() == 503 && text(reply).contains("unavailable"), "Cold cache blocked or published partial metadata");
                require(reply.headers().firstValue("Retry-After").orElse("").equals("20"), "Cold cache lacked retry guidance");
            }
            require(network.apiCalls.get() == 1, "Concurrent cold requests duplicated the upstream lookup");
            network.metadataGate.countDown();fixture.ready();
            require(!Files.exists(orphan) && !Files.exists(pointer), "Startup retained stale interrupted-cache debris");
            require(Files.exists(recent), "Startup removed another recent cache transfer");
            HttpResponse<byte[]> metadata = fixture.get(PhoneUpdateCache.LATEST_PATH);
            require(text(metadata).equals(network.metadata), "The laptop rewrote the original GitHub metadata");
            require(text(metadata).contains("https://github.com/mikecarper/"), "Metadata replaced the trusted GitHub APK URL");
            require(Arrays.equals(network.apk, fixture.get(PhoneUpdateCache.APK_PATH + "1").body()), "Cached APK bytes changed");
            require(network.apiCalls.get() == 1 && network.apkCalls.get() == 1, "The cache downloaded more than once");
            require(fixture.get(PhoneUpdateCache.APK_PATH + "999").statusCode() == 404, "An unknown asset ID was exposed");
            for (String route : List.of("/api/app-update/apk/%2e%2e/latest", "/api/app-update/apk/1?file=/etc/passwd",
                    "/api/app-update/latest/extra", "/api/app-update/apk/0"))
                require(fixture.get(route).statusCode() == 404, "An invalid cache route was accepted");
            require(fixture.request(PhoneUpdateCache.LATEST_PATH, "POST").statusCode() == 405, "A writable update endpoint was exposed");
            fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
            require(network.apiCalls.get() == 2 && network.apkCalls.get() == 1, "Refresh did not reuse the immutable verified APK");
        } finally { network.release(); }
    }

    private static void verifyOfflineRestartAndCorruption() throws Exception {
        Path cache = Files.createTempDirectory("phone-update-offline-");
        Network network = new Network(11, data(64 * 1024, 11));String metadata = network.metadata;
        try (Fixture fixture = new Fixture(cache, network)) { fixture.cache.start();fixture.ready(); }
        network.offline = true;
        try (Fixture fixture = new Fixture(cache, network)) {
            fixture.cache.start();fixture.ready();
            require(text(fixture.get(PhoneUpdateCache.LATEST_PATH)).equals(metadata), "Offline restart lost verified metadata");
            require(Arrays.equals(network.apk, fixture.get(PhoneUpdateCache.APK_PATH + "11").body()), "Offline restart lost APK bytes");
        }
        byte[] corrupt = network.apk.clone();corrupt[0] ^= 1;Files.write(cache.resolve("11/app.apk"), corrupt);
        try (Fixture fixture = new Fixture(cache, network)) {
            fixture.cache.start();fixture.settled();
            require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "Corrupt cached bytes were advertised after restart");
            require(fixture.get(PhoneUpdateCache.APK_PATH + "11").statusCode() == 404, "Corrupt cached bytes remained downloadable");
        }
    }

    private static void verifyFailedRefreshPreservesGoodCacheAndImmutableIDs() throws Exception {
        Network network = new Network(21, data(48 * 1024, 21));
        try (Fixture fixture = new Fixture(Files.createTempDirectory("phone-update-refresh-"), network)) {
            fixture.cache.start();fixture.ready();String first = network.metadata;byte[] oldAPK = network.apk;
            network.set(22, data(64 * 1024, 22));
            network.metadata = network.metadata.replace(digest(network.apk), "0".repeat(64));
            fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
            require(text(fixture.get(PhoneUpdateCache.LATEST_PATH)).equals(first), "A bad refresh replaced the last good metadata");
            require(fixture.get(PhoneUpdateCache.APK_PATH + "22").statusCode() == 404, "A bad digest published a partial APK");
            try (var paths = Files.list(fixture.path)) {
                require(paths.noneMatch(path -> path.getFileName().toString().startsWith(".download-")), "Failed download left a partial staging directory");
            }
            network.set(22, data(64 * 1024, 22));
            network.apkGate = new CountDownLatch(1);
            fixture.advance();fixture.cache.refreshIfStale();
            await(() -> network.apkCalls.get() == 3, "The replacement download did not start");
            require(text(fixture.get(PhoneUpdateCache.LATEST_PATH)).equals(first), "Metadata advanced before the replacement APK verified");
            network.apkGate.countDown();fixture.settled();
            require(text(fixture.get(PhoneUpdateCache.LATEST_PATH)).equals(network.metadata), "Verified replacement did not publish");
            require(Arrays.equals(oldAPK, fixture.get(PhoneUpdateCache.APK_PATH + "21").body()), "A refresh invalidated the earlier metadata's asset ID");
            byte[] secondAPK = network.apk;String second = network.metadata;int downloads = network.apkCalls.get();
            network.set(22, data(64 * 1024, 99));
            fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
            require(text(fixture.get(PhoneUpdateCache.LATEST_PATH)).equals(second), "An existing asset ID changed its metadata/body");
            require(Arrays.equals(secondAPK, fixture.get(PhoneUpdateCache.APK_PATH + "22").body()), "An immutable asset ID changed bytes");
            require(network.apkCalls.get() == downloads, "Changed immutable identity caused an unnecessary replacement download");
            network.set(22, secondAPK);network.metadataGate = new CountDownLatch(1);
            Files.write(fixture.path.resolve("22/app.apk"), new byte[3]);fixture.advance();
            require(fixture.get(PhoneUpdateCache.APK_PATH + "22").statusCode() == 503, "A truncated live cache entry stayed downloadable");
            require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "A broken live cache entry stayed advertised");
            network.metadataGate.countDown();fixture.settled();fixture.ready();
            require(Arrays.equals(secondAPK, fixture.get(PhoneUpdateCache.APK_PATH + "22").body()),
                    "A live missing/truncated cache entry could not be repaired without restarting the host");
            require(network.apkCalls.get() == downloads + 1, "A broken live cache did not redownload the exact verified asset");
        } finally { network.release(); }
    }

    private static void verifyInvalidMetadataAndRedirects() throws Exception {
        Network network = new Network(31, data(16 * 1024, 31));
        try (Fixture fixture = new Fixture(Files.createTempDirectory("phone-update-invalid-"), network)) {
            String valid = network.metadata;
            for (String invalid : List.of(
                    valid.replace("\"digest\":\"sha256:" + digest(network.apk) + "\"", "\"digest\":null"),
                    valid.replace("\"size\":" + network.apk.length, "\"size\":" + (PhoneUpdateCache.MAX_APK_BYTES + 1)),
                    valid.replace("\"id\":31", "\"id\":31.5"),
                    valid.replace("mikecarper/", "other/"),
                    valid.replace("https://github.com/", "http://github.com/"),
                    valid.replace("\"draft\":false", "\"draft\":true"),
                    valid.replace("\"prerelease\":false", "\"prerelease\":true"))) {
                network.metadata = invalid;fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
                require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "Invalid stable metadata was published");
                require(network.apkCalls.get() == 0, "An invalid candidate downloaded APK bytes");
            }
            network.metadata = valid;
            for (String redirect : List.of("http://release-assets.githubusercontent.com/test.apk", "https://evil.example/test.apk",
                    "https://user@objects.githubusercontent.com/test.apk", "https://objects.githubusercontent.com:444/test.apk")) {
                network.redirect = redirect;fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
                require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "An untrusted redirect published an APK");
                require(network.requests.stream().noneMatch(url -> url.equals(redirect)), "Untrusted redirect was requested before validation");
            }
            network.redirect = CDN;network.loopRedirect = true;
            int before = network.requests.size();fixture.advance();fixture.cache.refreshIfStale();fixture.settled();
            require(network.requests.size() - before == 7, "The five-redirect bound was not enforced");
            require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "An endless redirect chain published metadata");
        }
    }

    private static void verifyGameDeferralAndBoundedSlowTransfers() throws Exception {
        Network network = new Network(41, data(4 * 1024 * 1024, 41));
        try (Fixture fixture = new Fixture(Files.createTempDirectory("phone-update-busy-"), network)) {
            fixture.idle.set(false);fixture.cache.start();
            require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503 && network.apiCalls.get() == 0,
                    "An active game started a background upstream transfer");
            fixture.idle.set(true);fixture.ready();
            try (Socket first = fixture.slowAPK(41);Socket second = fixture.slowAPK(41)) {
                require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "Update stream concurrency was not bounded");
                long began = System.nanoTime();
                require(fixture.get("/health").statusCode() == 200, "Slow APK phones blocked unrelated HTTP work");
                require(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2), "Slow APK phones delayed unrelated HTTP work");
                fixture.idle.set(false);
                require(fixture.get(PhoneUpdateCache.LATEST_PATH).statusCode() == 503, "A running game still advertised large updates");
                await(() -> ((Semaphore) field(fixture.cache,"streamSlots")).availablePermits() == 2,
                        "Starting the game did not abort the slow update streams");
                fixture.idle.set(true);fixture.ready();
            }
            int calls = network.apiCalls.get();
            fixture.idle.set(false);fixture.advance();fixture.cache.refreshIfStale();Thread.sleep(50);
            require(network.apiCalls.get() == calls, "A running game refreshed the upstream cache");
            fixture.idle.set(true);
            fixture.busyAt.set(fixture.idleChecks.get() + 20);fixture.cache.refreshIfStale();fixture.settled();
            require(!fixture.idle.get(), "The revalidation fixture did not enter an active game");
            require(Files.size(fixture.path.resolve("41/app.apk")) == network.apk.length,
                    "A game interrupting cache revalidation deleted the last verified APK");
            fixture.busyAt.set(Integer.MAX_VALUE);fixture.idle.set(true);fixture.ready();fixture.settled();
            network.set(42, data(1024, 42));network.apkGate = new CountDownLatch(1);
            fixture.advance();fixture.cache.refreshIfStale();await(() -> network.apkCalls.get() == 2, "The deferred replacement did not start");
            fixture.idle.set(false);network.apkGate.countDown();fixture.settled();fixture.idle.set(true);
            require(fixture.get(PhoneUpdateCache.APK_PATH + "42").statusCode() == 404, "A game-interrupted download was published");
            require(Arrays.equals(data(4 * 1024 * 1024, 41), fixture.get(PhoneUpdateCache.APK_PATH + "41").body()),
                    "An interrupted replacement damaged the last good cache");
        } finally { network.release(); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path path;
        final AtomicBoolean idle = new AtomicBoolean(true);
        final AtomicInteger idleChecks = new AtomicInteger(), busyAt = new AtomicInteger(Integer.MAX_VALUE);
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final PhoneUpdateCache cache;
        final HttpServer server;
        final ExecutorService http = Executors.newFixedThreadPool(4);
        Fixture(Path path, Network network) throws Exception {
            this.path = path;cache = new PhoneUpdateCache(path, () -> {
                if (idleChecks.incrementAndGet() >= busyAt.get()) idle.set(false);
                return idle.get();
            }, network, clock::get);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(http);server.createContext("/api/app-update/",cache::handle);
            server.createContext("/health", exchange -> {
                exchange.sendResponseHeaders(200,2);try (OutputStream output = exchange.getResponseBody()) {output.write(new byte[]{'o','k'});}
            });server.start();
        }
        HttpResponse<byte[]> get(String route) throws Exception { return request(route,"GET"); }
        HttpResponse<byte[]> request(String route, String method) throws Exception {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + route))
                    .timeout(Duration.ofSeconds(3)).method(method,HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        void ready() throws Exception { await(() -> get(PhoneUpdateCache.LATEST_PATH).statusCode() == 200, "Cache never became ready"); }
        void advance() { clock.addAndGet(TimeUnit.SECONDS.toNanos(61)); }
        void settled() throws Exception {
            await(() -> { synchronized(field(cache,"workLock")) { return !(boolean) field(cache,"queued"); } }, "Cache worker did not settle");
        }
        Socket slowAPK(long id) throws Exception {
            Socket socket = new Socket();socket.setReceiveBufferSize(1024);socket.connect(server.getAddress());socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET " + PhoneUpdateCache.APK_PATH + id + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            ByteArrayOutputStream headers = new ByteArrayOutputStream();int matched = 0;
            while (matched < 4) {
                int value = socket.getInputStream().read();if(value<0)throw new IOException("Missing APK response headers");headers.write(value);
                matched = value == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : value == '\r' ? 1 : 0;
            }
            require(headers.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"), "Slow APK request did not start");return socket;
        }
        @Override public void close() { cache.close();server.stop(0);http.shutdownNow(); }
    }

    private static final class Network implements PhoneUpdateCache.ConnectionOpener {
        volatile byte[] apk;
        volatile String metadata;
        volatile String redirect = CDN;
        volatile boolean offline, loopRedirect;
        volatile CountDownLatch metadataGate, apkGate;
        final AtomicInteger apiCalls = new AtomicInteger(), apkCalls = new AtomicInteger();
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        Network(long id, byte[] apk) throws Exception { set(id,apk); }
        void set(long id, byte[] apk) throws Exception {
            this.apk = apk;
            Map<String,Object> asset = new LinkedHashMap<>();asset.put("id",id);asset.put("name","LAZER-GUN-RUN-1.58.apk");
            asset.put("state","uploaded");asset.put("size",apk.length);asset.put("digest","sha256:"+digest(apk));
            asset.put("updated_at","2026-10-26T20:00:00Z");asset.put("browser_download_url",
                    "https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/download/1.58/LAZER-GUN-RUN-1.58.apk");
            metadata = LaptopHost.Json.stringify(Map.of("draft",false,"prerelease",false,"tag_name","1.58","assets",List.of(asset)));
        }
        @Override public HttpURLConnection open(URL url) throws IOException {
            requests.add(url.toExternalForm());if(offline)throw new IOException("Isolated upstream offline");
            if (url.toExternalForm().equals(PhoneUpdateCache.LATEST_URL)) {
                apiCalls.incrementAndGet();return new Response(url,200,metadata.getBytes(StandardCharsets.UTF_8),null,metadataGate);
            }
            if (url.getHost().equals("github.com")) return new Response(url,302,new byte[0],redirect,null);
            apkCalls.incrementAndGet();
            return new Response(url,loopRedirect?302:200,apk,loopRedirect?CDN:null,apkGate);
        }
        void release() { if(metadataGate!=null)metadataGate.countDown();if(apkGate!=null)apkGate.countDown(); }
    }
    private static final class Response extends HttpURLConnection {
        final int status;final byte[] body;final String location;final CountDownLatch gate;
        Response(URL url,int status,byte[] body,String location,CountDownLatch gate) {super(url);this.status=status;this.body=body;this.location=location;this.gate=gate;}
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String key) {return key.equalsIgnoreCase("Location")?location:key.equalsIgnoreCase("Content-Length")?String.valueOf(body.length):null;}
        @Override public InputStream getInputStream() throws IOException {
            if(gate!=null)try{if(!gate.await(5,TimeUnit.SECONDS))throw new IOException("Test gate expired");}catch(InterruptedException interrupted){throw new IOException(interrupted);}
            return new ByteArrayInputStream(body);
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    private static byte[] data(int size,int seed) {byte[] result=new byte[size];for(int i=0;i<size;i++)result[i]=(byte)(i*31+seed);return result;}
    private static String digest(byte[] data) throws Exception {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static String text(HttpResponse<byte[]> response) {return new String(response.body(),StandardCharsets.UTF_8);}
    private static Object field(Object owner,String name)throws Exception{Field field=PhoneUpdateCache.class.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private interface Check {boolean get()throws Exception;}
    private static void await(Check check,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(check.get())return;Thread.sleep(10);}throw new AssertionError(message);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
