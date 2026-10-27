package com.simplecoil.simplecoil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real metadata parsing and HTTP download handling with only the remote server replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ReleaseUpdateTest {
    private static final String APK_URL =
            "https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/download/1.52/LAZER-GUN-RUN-1.52.apk";
    private static final String CDN_URL = "https://release-assets.githubusercontent.com/asset?signature=example";
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void selectsOnlyExactPublishedApkAndUsesAssetRevisionForCacheIdentity() throws Exception {
        JSONObject release = release();
        JSONArray assets = release.getJSONArray("assets");
        assets.put(new JSONObject(asset().toString()).put("name", "LAZER-GUN-RUN-1.52.zip"));
        assets.put(new JSONObject(asset().toString()).put("name", "app-debug.apk"));
        assets.put(new JSONObject(asset().toString()).put("name", "LAZER-GUN-RUN-1.52.apk.sha256"));
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release.toString());
        assertNotNull(selected);
        assertEquals(25L, selected.id);
        assertEquals("1.52", selected.version);
        assertEquals("LAZER-GUN-RUN-1.52.apk", selected.name);
        assertEquals(APK_URL, selected.url.toExternalForm());
        assertEquals(3L, selected.size);
        assertEquals(ABC_SHA256, selected.sha256);
        assertEquals("2026-10-25T18:25:00Z", selected.updatedAt);
        String original = selected.cacheKey();
        JSONObject changed = release();
        changed.getJSONArray("assets").getJSONObject(0).put("id", 26L);
        assertNotEquals(original, ReleaseUpdate.parseLatest(changed.toString()).cacheKey());
        changed = release();
        changed.getJSONArray("assets").getJSONObject(0).put("updated_at", "2026-10-25T18:26:00Z");
        assertNotEquals(original, ReleaseUpdate.parseLatest(changed.toString()).cacheKey());
        changed = release();
        changed.getJSONArray("assets").getJSONObject(0).put("size", 4);
        assertNotEquals(original, ReleaseUpdate.parseLatest(changed.toString()).cacheKey());
        changed = release();
        changed.getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL);
        assertNotEquals(original, ReleaseUpdate.parseLatest(changed.toString()).cacheKey());
    }

    @Test public void skipsUnpublishedReleasesAndDoesNotGuessFromAnotherApk() throws Exception {
        assertNull(ReleaseUpdate.parseLatest(release().put("draft", true).toString()));
        assertNull(ReleaseUpdate.parseLatest(release().put("prerelease", true).toString()));
        JSONObject wrongName = release();
        wrongName.getJSONArray("assets").getJSONObject(0).put("name", "LAZER-GUN-RUN-1.51.apk");
        assertNull(ReleaseUpdate.parseLatest(wrongName.toString()));
        JSONObject uploading = release();
        uploading.getJSONArray("assets").getJSONObject(0).put("state", "new");
        assertNull(ReleaseUpdate.parseLatest(uploading.toString()));
        assertNull(ReleaseUpdate.parseLatest(release().put("assets", new JSONArray()).toString()));
    }

    @Test public void permitsOptionalDigestAndVTagWithoutComparingVersionNames() throws Exception {
        JSONObject release = release().put("tag_name", "v1.52");
        release.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", APK_URL.replace("/1.52/", "/v1.52/"))
                .remove("digest");
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release.toString());
        assertEquals("1.52", selected.version);
        assertNull(selected.sha256);
        assertFalse(ReleaseUpdate.isStrictlyNewer(53, 53));
        assertFalse(ReleaseUpdate.isStrictlyNewer(52, 53));
        assertFalse(ReleaseUpdate.isStrictlyNewer(0, 53));
        assertFalse(ReleaseUpdate.isStrictlyNewer(54, -1));
        assertTrue(ReleaseUpdate.isStrictlyNewer(54, 53));
        assertTrue(ReleaseUpdate.isStrictlyNewer((long) Integer.MAX_VALUE + 1L, Integer.MAX_VALUE));
    }

    @Test public void rejectsAmbiguousMalformedOversizedOrUntrustedMetadata() throws Exception {
        expectFailure(() -> ReleaseUpdate.parseLatest("[]"));
        expectFailure(() -> ReleaseUpdate.parseLatest(release().put("draft", "false").toString()));
        expectFailure(() -> ReleaseUpdate.parseLatest(release().put("tag_name", "../1.52").toString()));
        JSONObject duplicate = release();
        duplicate.getJSONArray("assets").put(asset());
        expectFailure(() -> ReleaseUpdate.parseLatest(duplicate.toString()));
        for (Object invalidSize : new Object[] {0, -1, 1.5, "3", ReleaseUpdate.MAX_APK_BYTES + 1}) {
            JSONObject candidate = release();
            candidate.getJSONArray("assets").getJSONObject(0).put("size", invalidSize);
            expectFailure(() -> ReleaseUpdate.parseLatest(candidate.toString()));
        }
        for (String invalidDigest : new String[] {"", "sha256:abc", "md5:" + ABC_SHA256,
                "sha256:" + ABC_SHA256.substring(1) + "z"}) {
            JSONObject candidate = release();
            candidate.getJSONArray("assets").getJSONObject(0).put("digest", invalidDigest);
            expectFailure(() -> ReleaseUpdate.parseLatest(candidate.toString()));
        }
        for (String invalidUrl : new String[] {APK_URL.replace("https:", "http:"),
                APK_URL.replace("github.com", "github.com.attacker.example"),
                APK_URL.replace("github.com", "user@github.com"),
                APK_URL.replace("github.com", "github.com:444"),
                APK_URL.replace("mikecarper/", "other/"), APK_URL + "?download=1", APK_URL + "#part",
                APK_URL.replace("/1.52/", "/1.51/")}) {
            JSONObject candidate = release();
            candidate.getJSONArray("assets").getJSONObject(0).put("browser_download_url", invalidUrl);
            expectFailure(() -> ReleaseUpdate.parseLatest(candidate.toString()));
        }
    }

    @Test public void fetchIsUnauthenticatedBoundedAndHandlesMissingOrFailedRelease() throws Exception {
        FakeConnection success = response(200, release().toString().getBytes(StandardCharsets.UTF_8));
        Server server = new Server(success);
        assertNotNull(new ReleaseUpdate(server).fetchLatest());
        assertEquals(ReleaseUpdate.LATEST_URL, server.urls.get(0).toExternalForm());
        assertEquals("GET", success.getRequestMethod());
        assertEquals("application/vnd.github+json", success.getRequestProperty("Accept"));
        assertEquals("2026-03-10", success.getRequestProperty("X-GitHub-Api-Version"));
        assertNull(success.getRequestProperty("Authorization"));
        assertTrue(success.getConnectTimeout() > 0 && success.getConnectTimeout() <= 5000);
        assertTrue(success.getReadTimeout() > 0 && success.getReadTimeout() <= 10000);
        assertFalse(success.getInstanceFollowRedirects());
        assertTrue(success.closed);
        assertTrue(success.disconnected);
        FakeConnection missing = response(404, new byte[0]);
        assertNull(new ReleaseUpdate(new Server(missing)).fetchLatest());
        assertTrue(missing.disconnected);
        FakeConnection unavailable = response(503, new byte[0]);
        expectFailure(() -> new ReleaseUpdate(new Server(unavailable)).fetchLatest());
        assertTrue(unavailable.disconnected);
        FakeConnection redirect = response(302, new byte[0]);
        redirect.location = "https://api.github.com/repos/other/repo/releases/latest";
        Server wrongRepo = new Server(redirect);
        expectFailure(() -> new ReleaseUpdate(wrongRepo).fetchLatest());
        assertEquals(1, wrongRepo.urls.size());
        assertTrue(redirect.disconnected);
    }

    @Test public void fetchRejectsOversizedOrTruncatedResponsesAndClosesThem() throws Exception {
        FakeConnection oversizedHeader = response(200, new byte[0]);
        oversizedHeader.length = String.valueOf(ReleaseUpdate.MAX_METADATA_BYTES + 1);
        expectFailure(() -> new ReleaseUpdate(new Server(oversizedHeader)).fetchLatest());
        assertTrue(oversizedHeader.disconnected);
        FakeConnection oversizedBody = response(200, new byte[ReleaseUpdate.MAX_METADATA_BYTES + 1]);
        expectFailure(() -> new ReleaseUpdate(new Server(oversizedBody)).fetchLatest());
        assertTrue(oversizedBody.closed);
        assertTrue(oversizedBody.disconnected);
        FakeConnection truncated = response(200, release().toString().getBytes(StandardCharsets.UTF_8));
        truncated.length = String.valueOf(truncated.body.length + 1);
        expectFailure(() -> new ReleaseUpdate(new Server(truncated)).fetchLatest());
        assertTrue(truncated.closed);
        assertTrue(truncated.disconnected);
    }

    @Test public void downloadsOnlyThroughTrustedHttpsAndVerifiesBytesAndDigest() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        FakeConnection redirect = response(302, new byte[0]);
        redirect.location = CDN_URL;
        FakeConnection data = response(200, "abc".getBytes(StandardCharsets.US_ASCII));
        data.length = "3";
        Server server = new Server(redirect, data);
        File destination = temporary.newFile("release.apk");
        new ReleaseUpdate(server).download(selected, destination);
        assertEquals(APK_URL, server.urls.get(0).toExternalForm());
        assertEquals(CDN_URL, server.urls.get(1).toExternalForm());
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), read(destination));
        assertTrue(redirect.disconnected);
        assertTrue(data.closed);
        assertTrue(data.disconnected);
        assertEquals("application/octet-stream", data.getRequestProperty("Accept"));
    }

    @Test public void refusesDowngradedUntrustedOrExcessiveRedirectsBeforeRequestingThem() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        for (String location : new String[] {CDN_URL.replace("https:", "http:"),
                "https://attacker.example/asset", "https://github.com/other/asset",
                "https://release-assets.githubusercontent.com.attacker.example/asset",
                "https://user@release-assets.githubusercontent.com/asset",
                "https://release-assets.githubusercontent.com:444/asset", CDN_URL + "#part"}) {
            FakeConnection redirect = response(302, new byte[0]);
            redirect.location = location;
            Server server = new Server(redirect);
            File destination = temporary.newFile();
            expectFailure(() -> new ReleaseUpdate(server).download(selected, destination));
            assertFalse(destination.exists());
            assertEquals(1, server.urls.size());
            assertTrue(redirect.disconnected);
        }
        FakeConnection[] loop = new FakeConnection[6];
        for (int i = 0; i < loop.length; i++) {
            loop[i] = response(302, new byte[0]);
            loop[i].location = CDN_URL;
        }
        Server server = new Server(loop);
        File destination = temporary.newFile();
        expectFailure(() -> new ReleaseUpdate(server).download(selected, destination));
        assertFalse(destination.exists());
        assertEquals(6, server.urls.size());
        for (FakeConnection connection : loop) assertTrue(connection.disconnected);
    }

    @Test public void deletesPartialOrUnverifiedApkForEveryFailedDownload() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        for (byte[] bytes : new byte[][] {new byte[] {'a', 'b'}, new byte[] {'a', 'b', 'c', 'd'},
                new byte[] {'b', 'a', 'd'}}) {
            FakeConnection data = response(200, bytes);
            File destination = temporary.newFile();
            expectFailure(() -> new ReleaseUpdate(new Server(data)).download(selected, destination));
            assertFalse(destination.exists());
            assertTrue(data.closed);
            assertTrue(data.disconnected);
        }
        FakeConnection wrongHeader = response(200, new byte[0]);
        wrongHeader.length = "4";
        File wrongLength = temporary.newFile();
        expectFailure(() -> new ReleaseUpdate(new Server(wrongHeader)).download(selected, wrongLength));
        assertFalse(wrongLength.exists());
        assertTrue(wrongHeader.disconnected);
        FakeConnection failed = response(403, new byte[0]);
        File denied = temporary.newFile();
        expectFailure(() -> new ReleaseUpdate(new Server(failed)).download(selected, denied));
        assertFalse(denied.exists());
        assertTrue(failed.disconnected);
        FakeConnection interruptedRead = response(200, new byte[] {'a', 'b', 'c'});
        interruptedRead.failAfterFirstByte = true;
        File partial = temporary.newFile();
        expectFailure(() -> new ReleaseUpdate(new Server(interruptedRead)).download(selected, partial));
        assertFalse(partial.exists());
        assertTrue(interruptedRead.closed);
        assertTrue(interruptedRead.disconnected);
    }

    @Test public void interruptionStopsBeforeOpeningAndRemovesAnyPendingFile() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        File destination = temporary.newFile();
        Server server = new Server();
        try {
            Thread.currentThread().interrupt();
            expectFailure(() -> new ReleaseUpdate(server).download(selected, destination));
        } finally {
            Thread.interrupted();
        }
        assertFalse(destination.exists());
        assertTrue(server.urls.isEmpty());
    }

    @Test public void laptopMetadataUsesOnlyTheConnectedNumericPeerAndOriginalGithubValidation() throws Exception {
        FakeConnection data = response(200, release().toString().getBytes(StandardCharsets.UTF_8));
        Server server = new Server(data);
        ReleaseUpdate.Asset selected = new ReleaseUpdate(server).fetchLaptopLatest(
                InetAddress.getByName("192.168.42.1"), 17512);
        assertEquals(APK_URL, selected.url.toExternalForm());
        assertEquals("http://192.168.42.1:17512/api/app-update/latest", server.urls.get(0).toExternalForm());
        assertEquals("GET", data.getRequestMethod());
        assertNull(data.getRequestProperty("Authorization"));
        assertFalse(data.getInstanceFollowRedirects());
        assertTrue(data.getConnectTimeout() > 0 && data.getConnectTimeout() <= 2000);
        assertTrue(data.closed && data.disconnected);
        JSONObject forged = release();
        forged.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "http://192.168.42.9/evil.apk");
        FakeConnection untrusted = response(200, forged.toString().getBytes(StandardCharsets.UTF_8));
        expectFailure(() -> new ReleaseUpdate(new Server(untrusted)).fetchLaptopLatest(
                InetAddress.getLoopbackAddress(), 17512));
        assertTrue(untrusted.disconnected);
    }

    @Test public void laptopMissingAndWarmingCachesStayDistinctAndNeverFollowRedirects() throws Exception {
        FakeConnection unavailable = response(404, new byte[0]);
        assertNull(new ReleaseUpdate(new Server(unavailable)).fetchLaptopLatest(
                InetAddress.getLoopbackAddress(), 17512));
        assertTrue(unavailable.disconnected);
        FakeConnection warming = response(503, new byte[0]);
        try {
            new ReleaseUpdate(new Server(warming)).fetchLaptopLatest(InetAddress.getLoopbackAddress(), 17512);
            fail("Cache warming must defer rather than look like an old laptop endpoint");
        } catch (ReleaseUpdate.LaptopHttpException expected) {
            assertEquals(503, expected.status);
        }
        assertTrue(warming.disconnected);
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            FakeConnection redirect = response(status, new byte[0]);
            redirect.location = "http://192.168.42.9/api/app-update/latest";
            Server server = new Server(redirect);
            expectFailure(() -> new ReleaseUpdate(server).fetchLaptopLatest(InetAddress.getLoopbackAddress(), 17512));
            assertEquals(1, server.urls.size());
            assertTrue(redirect.disconnected);
        }
    }

    @Test public void laptopMetadataRequiresDigestAndRemainsSizeBounded() throws Exception {
        JSONObject noDigest = release();
        noDigest.getJSONArray("assets").getJSONObject(0).remove("digest");
        FakeConnection missing = response(200, noDigest.toString().getBytes(StandardCharsets.UTF_8));
        expectFailure(() -> new ReleaseUpdate(new Server(missing)).fetchLaptopLatest(
                InetAddress.getLoopbackAddress(), 17512));
        FakeConnection oversized = response(200, new byte[ReleaseUpdate.MAX_METADATA_BYTES + 1]);
        expectFailure(() -> new ReleaseUpdate(new Server(oversized)).fetchLaptopLatest(
                InetAddress.getLoopbackAddress(), 17512));
        assertTrue(oversized.closed && oversized.disconnected);
        FakeConnection truncated = response(200, release().toString().getBytes(StandardCharsets.UTF_8));
        truncated.length = String.valueOf(truncated.body.length + 1);
        expectFailure(() -> new ReleaseUpdate(new Server(truncated)).fetchLaptopLatest(
                InetAddress.getLoopbackAddress(), 17512));
        assertTrue(truncated.closed && truncated.disconnected);
    }

    @Test public void laptopApkComesFromTheSamePeerByAssetIdWithThePublishedDigest() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        FakeConnection bytes = response(200, "abc".getBytes(StandardCharsets.US_ASCII));
        bytes.length = "3";
        Server server = new Server(bytes);
        File target = temporary.newFile("laptop.apk");
        new ReleaseUpdate(server).downloadFromLaptop(selected, target, InetAddress.getByName("192.168.42.1"), 17512);
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), read(target));
        assertEquals(1, server.urls.size());
        assertEquals("http://192.168.42.1:17512/api/app-update/apk/25", server.urls.get(0).toExternalForm());
        assertFalse(bytes.getInstanceFollowRedirects());
        assertTrue(bytes.closed && bytes.disconnected);
        FakeConnection ipv6 = response(200, release().toString().getBytes(StandardCharsets.UTF_8));
        Server ipv6Server = new Server(ipv6);
        assertNotNull(new ReleaseUpdate(ipv6Server).fetchLaptopLatest(InetAddress.getByName("::1"), 17512));
        assertEquals("http://[0:0:0:0:0:0:0:1]:17512/api/app-update/latest",
                ipv6Server.urls.get(0).toExternalForm());
    }

    @Test public void laptopDownloadRejectsCorruptionRedirectsAndMissingDigestsWithoutLeavingAnApk() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        for (String body : new String[] {"ab", "abcd", "xyz"}) {
            File target = temporary.newFile();
            FakeConnection bad = response(200, body.getBytes(StandardCharsets.US_ASCII));
            expectFailure(() -> new ReleaseUpdate(new Server(bad)).downloadFromLaptop(
                    selected, target, InetAddress.getLoopbackAddress(), 17512));
            assertFalse(target.exists());
            assertTrue(bad.closed && bad.disconnected);
        }
        for (int status : new int[] {302, 503}) {
            FakeConnection bad = response(status, new byte[0]);
            bad.location = APK_URL;
            Server server = new Server(bad);
            File target = temporary.newFile();
            expectFailure(() -> new ReleaseUpdate(server).downloadFromLaptop(
                    selected, target, InetAddress.getLoopbackAddress(), 17512));
            assertFalse(target.exists());
            assertEquals(1, server.urls.size());
            assertTrue(bad.disconnected);
        }
        JSONObject noDigest = release();
        noDigest.getJSONArray("assets").getJSONObject(0).remove("digest");
        ReleaseUpdate.Asset unsignedMetadata = ReleaseUpdate.parseLatest(noDigest.toString());
        File target = temporary.newFile();
        Server server = new Server();
        expectFailure(() -> new ReleaseUpdate(server).downloadFromLaptop(unsignedMetadata, target,
                InetAddress.getLoopbackAddress(), 17512));
        assertFalse(target.exists());
        assertTrue(server.urls.isEmpty());
    }

    @Test public void invalidLaptopEndpointsCannotOpenConnectionsOrRetainCandidateFiles() throws Exception {
        ReleaseUpdate.Asset selected = ReleaseUpdate.parseLatest(release().toString());
        for (InetAddress address : new InetAddress[] {null, InetAddress.getByName("0.0.0.0"),
                InetAddress.getByName("224.0.0.1")}) {
            Server server = new Server();
            expectFailure(() -> new ReleaseUpdate(server).fetchLaptopLatest(address, 17512));
            File target = temporary.newFile();
            expectFailure(() -> new ReleaseUpdate(server).downloadFromLaptop(selected, target, address, 17512));
            assertFalse(target.exists());
            assertTrue(server.urls.isEmpty());
        }
        for (int port : new int[] {0, -1, 65536}) {
            Server server = new Server();
            expectFailure(() -> new ReleaseUpdate(server).fetchLaptopLatest(InetAddress.getLoopbackAddress(), port));
            assertTrue(server.urls.isEmpty());
        }
    }

    @Test public void defaultLaptopTransportContactsThePeerWithoutUsingTheSystemWebProxy() throws Exception {
        byte[] body = release().toString().getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            Thread server = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    if (line == null) throw new IOException("No complete laptop metadata request");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Throwable failure) { serverFailure.set(failure); }
            }, "Laptop update direct-peer regression");
            server.setDaemon(true);
            server.start();
            ProxySelector previous = ProxySelector.getDefault();
            try {
                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        throw new AssertionError("A local update attempted to use the system proxy: " + uri);
                    }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                });
                assertNotNull(new ReleaseUpdate().fetchLaptopLatest(
                        InetAddress.getByName("127.0.0.1"), listener.getLocalPort()));
            } finally {
                ProxySelector.setDefault(previous);
                listener.close();
                server.join(6000);
            }
            assertFalse("Local metadata fixture survived cleanup", server.isAlive());
            if (serverFailure.get() != null) throw new AssertionError("Local metadata fixture failed", serverFailure.get());
        }
    }

    private static JSONObject release() throws Exception {
        return new JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "1.52")
                .put("assets", new JSONArray().put(asset()));
    }

    private static JSONObject asset() throws Exception {
        return new JSONObject().put("id", 25L).put("name", "LAZER-GUN-RUN-1.52.apk")
                .put("state", "uploaded").put("size", 3L).put("digest", "sha256:" + ABC_SHA256)
                .put("updated_at", "2026-10-25T18:25:00Z").put("browser_download_url", APK_URL);
    }

    private static FakeConnection response(int status, byte[] body) throws Exception {
        return new FakeConnection(status, body);
    }

    private static byte[] read(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void expectFailure(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected an IOException");
        } catch (IOException expected) {
            // The update caller can safely continue launching the installed application.
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class Server implements ReleaseUpdate.ConnectionOpener {
        final ArrayDeque<FakeConnection> responses = new ArrayDeque<>();
        final List<URL> urls = new ArrayList<>();

        Server(FakeConnection... responses) {
            for (FakeConnection response : responses) this.responses.add(response);
        }

        @Override public HttpURLConnection open(URL url) throws IOException {
            urls.add(url);
            if (responses.isEmpty()) throw new IOException("Unexpected connection");
            return responses.remove();
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        final byte[] body;
        String location;
        String length;
        boolean closed;
        boolean disconnected;
        boolean failAfterFirstByte;

        FakeConnection(int status, byte[] body) throws Exception {
            super(new URL("https://example.test/"));
            this.status = status;
            this.body = body;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) {
            if ("Location".equalsIgnoreCase(name)) return location;
            if ("Content-Length".equalsIgnoreCase(name)) return length;
            return null;
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                private final ByteArrayInputStream bytes = new ByteArrayInputStream(body);
                private boolean readOnce;
                @Override public int read() throws IOException {
                    byte[] single = new byte[1];
                    return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
                }
                @Override public int read(byte[] output, int offset, int count) throws IOException {
                    if (failAfterFirstByte) {
                        if (readOnce) throw new IOException("Download connection closed");
                        readOnce = true;
                        return bytes.read(output, offset, Math.min(count, 1));
                    }
                    return bytes.read(output, offset, count);
                }
                @Override public void close() throws IOException {
                    closed = true;
                    bytes.close();
                }
            };
        }
        @Override public InputStream getErrorStream() { return null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
