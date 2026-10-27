package com.simplecoil.laptophost;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Read-only, verified GitHub APK cache. No game or dashboard worker streams APK bytes. */
final class PhoneUpdateCache implements AutoCloseable {
    static final String LATEST_PATH = "/api/app-update/latest";
    static final String APK_PATH = "/api/app-update/apk/";
    static final String LATEST_URL = "https://api.github.com/repos/mikecarper/SimpleCoil_HFC_EDIT/releases/latest";
    static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final long REFRESH_NS = TimeUnit.SECONDS.toNanos(60);
    private static final long TRANSFER_NS = TimeUnit.SECONDS.toNanos(120);
    private static final int RETAINED_ASSETS = 3;
    private final Path directory;
    private final BooleanSupplier idle;
    private final ConnectionOpener connections;
    private final LongSupplier clock;
    private final boolean enabled;
    private final Object workLock = new Object();
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<HttpExchange, Transfer> transfers = new ConcurrentHashMap<>();
    private final Semaphore streamSlots = new Semaphore(2);
    private final ThreadPoolExecutor work = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> daemon(task, "Phone update cache"));
    private final ThreadPoolExecutor streams = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> daemon(task, "Phone update download"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            task -> daemon(task, "Phone update watchdog"));
    private volatile Snapshot latest;
    private volatile HttpURLConnection upstream;
    private volatile long upstreamDeadline;
    private volatile boolean closed;
    private boolean started, queued, attempted, loaded;
    private long lastAttempt;

    interface ConnectionOpener { HttpURLConnection open(URL url) throws IOException; }
    private record Asset(long id, long size, String digest, String name, URL url) { }
    private record Snapshot(Asset asset, byte[] metadata, Path apk) { }
    private record Transfer(long id, long deadline) { }
    private static final class GameActiveException extends IOException {
        GameActiveException() { super("Game is active"); }
    }

    PhoneUpdateCache(BooleanSupplier idle) {
        this(Path.of(System.getProperty("simplecoil.phoneupdates.cache",
                Path.of(System.getProperty("user.home"), ".cache", "simplecoil", "phone-updates").toString())),
                idle, url -> (HttpURLConnection) url.openConnection(), System::nanoTime,
                Boolean.parseBoolean(System.getProperty("simplecoil.phoneupdates.enabled", "true")));
    }

    PhoneUpdateCache(Path directory, BooleanSupplier idle, ConnectionOpener connections, LongSupplier clock) {
        this(directory, idle, connections, clock, true);
    }

    private PhoneUpdateCache(Path directory, BooleanSupplier idle, ConnectionOpener connections,
                             LongSupplier clock, boolean enabled) {
        this.directory = directory;this.idle = idle;this.connections = connections;this.clock = clock;this.enabled = enabled;
    }

    void start() {
        synchronized (workLock) {
            if (started || closed || !enabled) return;
            started = true;
            watchdog.scheduleAtFixedRate(this::cancelUnavailableTransfers, 250, 250, TimeUnit.MILLISECONDS);
        }
        refreshIfStale();
    }

    void refreshIfStale() {
        if (!enabled || unavailable()) return;
        synchronized (workLock) {
            long now = clock.getAsLong();
            if (closed || queued || attempted && now - lastAttempt < REFRESH_NS) return;
            queued = true;attempted = true;lastAttempt = now;
            try { work.execute(this::refresh); }
            catch (RejectedExecutionException stopped) { queued = false; }
        }
    }

    /** The ADB worker copies and re-verifies these immutable bytes before installation. */
    AdbPhoneUpdater.ReleaseApk adbRelease() {
        refreshIfStale();
        Snapshot snapshot = latest;
        return snapshot == null ? null : new AdbPhoneUpdater.ReleaseApk(
                snapshot.apk, snapshot.asset.size, snapshot.asset.digest);
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (!"GET".equals(exchange.getRequestMethod())) { error(exchange, 405, "Use GET");return; }
        if (exchange.getRequestURI().getRawQuery() != null
                || !path.equals(LATEST_PATH) && !path.matches(APK_PATH + "[1-9][0-9]{0,18}")) {
            error(exchange, 404, "not found");return;
        }
        refreshIfStale();
        if (unavailable()) { error(exchange, 503, "busy");return; }
        if (!streamSlots.tryAcquire()) { error(exchange, 503, "busy");return; }
        try {
            streams.execute(() -> {
                try { serve(exchange, path); }
                catch (IOException | RuntimeException ignored) { exchange.close(); }
                finally { transfers.remove(exchange);exchange.close();streamSlots.release(); }
            });
        } catch (RejectedExecutionException stopped) {
            streamSlots.release();error(exchange, 503, "busy");
        }
    }

    private void serve(HttpExchange exchange, String path) throws IOException {
        if (unavailable()) { error(exchange, 503, "busy");return; }
        Snapshot snapshot;
        if (path.equals(LATEST_PATH)) snapshot = latest;
        else {
            try { snapshot = snapshots.get(Long.parseLong(path.substring(APK_PATH.length()))); }
            catch (NumberFormatException invalid) { error(exchange, 404, "not found");return; }
        }
        if (snapshot == null) { error(exchange, path.equals(LATEST_PATH) ? 503 : 404, "unavailable");return; }
        transfers.put(exchange, new Transfer(snapshot.asset.id, clock.getAsLong() + TRANSFER_NS));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (path.equals(LATEST_PATH)) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, snapshot.metadata.length);
            exchange.getResponseBody().write(snapshot.metadata);
        } else {
            if (!Files.isRegularFile(snapshot.apk, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(snapshot.apk) != snapshot.asset.size) {
                if (latest == snapshot) latest = null;
                error(exchange, 503, "unavailable");return;
            }
            try (InputStream input = Files.newInputStream(snapshot.apk, LinkOption.NOFOLLOW_LINKS)) {
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.android.package-archive");
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + snapshot.asset.name + "\"");
                exchange.sendResponseHeaders(200, snapshot.asset.size);
                copy(input, exchange.getResponseBody(), snapshot.asset.size,
                        clock.getAsLong() + TRANSFER_NS, null, null);
            }
        }
    }

    private void refresh() {
        try {
            checkIdle();
            if (!loaded) { loadCache();loaded = true; }
            byte[] metadata = fetchMetadata();
            Asset asset = parse(metadata);
            if (asset == null) return;
            Snapshot snapshot = snapshots.get(asset.id);
            Path target = directory.resolve(String.valueOf(asset.id));
            boolean verified = false;
            if (snapshot == null && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                try { snapshot = readSnapshot(target);verified = true; }
                catch (GameActiveException deferred) { throw deferred; }
                catch (IOException invalid) { removeSnapshotFiles(target); }
            }
            if (snapshot != null) {
                if (snapshot.asset.id != asset.id || snapshot.asset.size != asset.size
                        || !snapshot.asset.digest.equals(asset.digest) || !snapshot.asset.name.equals(asset.name)
                        || !snapshot.asset.url.toExternalForm().equals(asset.url.toExternalForm()))
                    throw new IOException("An immutable release asset changed");
                if (!verified) {
                    try { snapshot = readSnapshot(target); }
                    catch (GameActiveException deferred) { throw deferred; }
                    catch (IOException invalid) {
                        if (latest != null && latest.asset.id == asset.id) latest = null;
                        snapshots.remove(asset.id);removeSnapshotFiles(target);snapshot = null;
                    }
                }
            }
            if (snapshot == null) {
                Path staging = Files.createTempDirectory(directory, ".download-");
                try {
                    Path apk = staging.resolve("app.apk");download(asset, apk);
                    Files.write(staging.resolve("release.json"), metadata);
                    checkIdle();
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                    snapshot = new Snapshot(asset, metadata, target.resolve("app.apk"));
                } finally { removeSnapshotFiles(staging); }
            }
            checkIdle();
            Path pointer = Files.createTempFile(directory, ".latest-", ".tmp");
            try {
                Files.writeString(pointer, String.valueOf(asset.id), StandardCharsets.US_ASCII);
                Files.move(pointer, directory.resolve("latest"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(pointer); }
            snapshots.put(asset.id, snapshot);latest = snapshot;
            trimCache();
        } catch (GameActiveException deferred) {
            synchronized (workLock) { attempted = false; }
        } catch (IOException | RuntimeException unavailable) {
            // The game stays usable, and a previously verified snapshot stays available offline.
        } finally {
            synchronized (workLock) { queued = false; }
        }
    }

    private void loadCache() throws IOException {
        Files.createDirectories(directory);
        String current = "";
        Path pointer = directory.resolve("latest");
        if (Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS) && Files.size(pointer) <= 32)
            current = Files.readString(pointer, StandardCharsets.US_ASCII);
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                checkIdle();String name = entry.getFileName().toString();
                // A process can die before its finally block deletes staging.
                // Leave recent work alone for another live host process; every
                // normal transfer has a two-minute bound.
                if ((name.matches("\\.download-[0-9]+") || name.matches("\\.latest-[0-9]+\\.tmp"))
                        && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis()
                        < System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)) {
                    try {
                        if (name.startsWith(".download-")) removeSnapshotFiles(entry);
                        else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(entry);
                    } catch (IOException ignored) { /* Unrelated files are never recursively removed. */ }
                } else if (name.matches("[1-9][0-9]{0,18}")
                        && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) directories.add(entry);
            }
        }
        final String currentID = current;
        directories.sort(Comparator.<Path>comparingInt(path -> path.getFileName().toString().equals(currentID) ? 0 : 1)
                .thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));
        for (Path path : directories) {
            checkIdle();
            if (snapshots.size() >= RETAINED_ASSETS) { removeSnapshotFiles(path);continue; }
            try {
                Snapshot snapshot = readSnapshot(path);snapshots.put(snapshot.asset.id, snapshot);
                if (path.getFileName().toString().equals(currentID)) latest = snapshot;
            } catch (GameActiveException deferred) { throw deferred; }
            catch (IOException | IllegalArgumentException invalid) { /* Never serve an unverified disk entry. */ }
        }
    }

    private Snapshot readSnapshot(Path path) throws IOException {
        Path json = path.resolve("release.json"), apk = path.resolve("app.apk");
        if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS) || Files.size(json) > MAX_METADATA_BYTES
                || !Files.isRegularFile(apk, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid cached release");
        byte[] metadata = Files.readAllBytes(json);Asset asset = parse(metadata);
        if (asset == null || !path.getFileName().toString().equals(String.valueOf(asset.id))
                || Files.size(apk) != asset.size) throw new IOException("Invalid cached APK");
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(apk, LinkOption.NOFOLLOW_LINKS)) {
            copy(input, OutputStream.nullOutputStream(), asset.size, clock.getAsLong() + TRANSFER_NS, digest, null);
        }
        if (!asset.digest.equals(hex(digest.digest()))) throw new IOException("Invalid cached APK digest");
        return new Snapshot(asset, metadata, apk);
    }

    private byte[] fetchMetadata() throws IOException {
        long deadline = clock.getAsLong() + TimeUnit.SECONDS.toNanos(15);
        HttpURLConnection connection = response(new URL(LATEST_URL), false, deadline);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("No release metadata available");
            long length = length(connection);
            if (length > MAX_METADATA_BYTES) throw new IOException("Release metadata too large");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                long count = copy(input, output, MAX_METADATA_BYTES, deadline, null, connection);
                if (length >= 0 && count != length) throw new IOException("Incomplete release metadata");
            }
            return output.toByteArray();
        } finally { disconnect(connection); }
    }

    private void download(Asset asset, Path apk) throws IOException {
        long deadline = clock.getAsLong() + TRANSFER_NS;
        HttpURLConnection connection = response(asset.url, true, deadline);
        try {
            if (connection.getResponseCode() != 200 || length(connection) >= 0 && length(connection) != asset.size)
                throw new IOException("Invalid release download response");
            MessageDigest digest = sha256();
            try (InputStream input = connection.getInputStream();OutputStream output = Files.newOutputStream(apk)) {
                if (copy(input, output, asset.size, deadline, digest, connection) != asset.size)
                    throw new IOException("Incomplete release APK");
            }
            if (!asset.digest.equals(hex(digest.digest()))) throw new IOException("Release APK digest mismatch");
        } finally { disconnect(connection); }
    }

    private HttpURLConnection response(URL initial, boolean download, long deadline) throws IOException {
        URL url = initial;
        for (int redirects = 0; ; redirects++) {
            checkIdle();
            HttpURLConnection connection = connections.open(url);upstreamDeadline = deadline;upstream = connection;
            boolean retained = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(5000, remaining(deadline)));
                connection.setReadTimeout(Math.min(10_000, remaining(deadline)));
                connection.setUseCaches(false);connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "LAZER-GUN-RUN-laptop-updater");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", download ? "application/octet-stream" : "application/vnd.github+json");
                if (!download) connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    retained = true;return connection;
                }
                if (!download || redirects >= 5) throw new IOException("Unexpected release redirect");
                String location = connection.getHeaderField("Location");
                if (location == null || location.length() > 16384) throw new IOException("Invalid release redirect");
                URL next = new URL(url, location);validateHttps(next);
                if (!List.of("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
                        .contains(next.getHost().toLowerCase(Locale.ROOT)) || next.getRef() != null)
                    throw new IOException("Untrusted release CDN");
                url = next;
            } finally { if (!retained) disconnect(connection); }
        }
    }

    private long copy(InputStream input, OutputStream output, long limit, long deadline,
                      MessageDigest digest, HttpURLConnection connection) throws IOException {
        byte[] buffer = new byte[16_384];long count = 0;
        while (true) {
            checkIdle();int remaining = remaining(deadline);
            if (connection != null) connection.setReadTimeout(Math.min(10_000, remaining));
            int read = input.read(buffer);
            if (read < 0) return count;
            if (read > limit - count) throw new IOException("Release exceeds allowed size");
            output.write(buffer, 0, read);count += read;
            if (digest != null) digest.update(buffer, 0, read);
        }
    }

    private static Asset parse(byte[] metadata) throws IOException {
        if (metadata.length > MAX_METADATA_BYTES) throw new IOException("Release metadata too large");
        try {
            Object parsed = LaptopHost.Json.parse(new String(metadata, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> release) || !(release.get("draft") instanceof Boolean)
                    || !(release.get("prerelease") instanceof Boolean)) throw new IOException("Invalid release metadata");
            if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return null;
            String tag = string(release.get("tag_name")), version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!version.matches("[0-9][0-9A-Za-z._+-]{0,63}")) throw new IOException("Invalid release tag");
            String name = "LAZER-GUN-RUN-" + version + ".apk";Asset selected = null;
            if (!(release.get("assets") instanceof List<?> assets)) throw new IOException("Invalid release assets");
            for (Object value : assets) {
                if (!(value instanceof Map<?, ?> asset)) throw new IOException("Invalid release asset");
                if (!name.equals(asset.get("name")) || !"uploaded".equals(asset.get("state"))) continue;
                if (selected != null) throw new IOException("Ambiguous release APK");
                long id = positive(asset.get("id")), size = positive(asset.get("size"));
                String digest = string(asset.get("digest"));
                if (size > MAX_APK_BYTES || !digest.matches("sha256:[0-9a-fA-F]{64}")
                        || !string(asset.get("updated_at")).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))
                    throw new IOException("Invalid release integrity metadata");
                URL url = new URL(string(asset.get("browser_download_url")));validateHttps(url);
                if (!"github.com".equalsIgnoreCase(url.getHost()) || url.getQuery() != null || url.getRef() != null
                        || !("/mikecarper/SimpleCoil_HFC_EDIT/releases/download/" + tag + "/" + name).equals(url.getPath()))
                    throw new IOException("Untrusted release APK URL");
                selected = new Asset(id, size, digest.substring(7).toLowerCase(Locale.ROOT), name, url);
            }
            return selected;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid release metadata", invalid); }
    }

    private static String string(Object value) throws IOException {
        if (!(value instanceof String text) || text.isEmpty()) throw new IOException("Missing release field");return text;
    }
    private static long positive(Object value) throws IOException {
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() <= 0)
            throw new IOException("Invalid release integer");return ((Number) value).longValue();
    }
    private static void validateHttps(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                || url.getPort() != -1 && url.getPort() != 443) throw new IOException("Release requires trusted HTTPS");
    }
    private static long length(HttpURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try { long length = Long.parseLong(value);if (length < 0) throw new NumberFormatException();return length; }
        catch (NumberFormatException invalid) { throw new IOException("Invalid response length", invalid); }
    }
    private int remaining(long deadline) throws IOException {
        long ns = deadline - clock.getAsLong();
        if (ns <= 0) throw new IOException("Release transfer timed out");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(ns)));
    }
    private boolean unavailable() { return closed || !idle.getAsBoolean(); }
    private void checkIdle() throws IOException {
        if (unavailable() || Thread.currentThread().isInterrupted()) throw new GameActiveException();
    }
    private void disconnect(HttpURLConnection connection) {
        if (upstream == connection) upstream = null;connection.disconnect();
    }
    private void cancelUnavailableTransfers() {
        boolean busy = unavailable();
        HttpURLConnection connection = upstream;
        long now = clock.getAsLong();
        if (connection != null && (busy || now >= upstreamDeadline)) connection.disconnect();
        for (Map.Entry<HttpExchange, Transfer> entry : transfers.entrySet())
            if (busy || now >= entry.getValue().deadline) entry.getKey().close();
    }
    private void trimCache() throws IOException {
        List<Snapshot> retained = new ArrayList<>(snapshots.values());
        retained.sort(Comparator.comparingLong((Snapshot value) -> value.asset.id).reversed());
        int count = snapshots.size();
        for (int i = retained.size() - 1; i >= 0 && count > RETAINED_ASSETS; i--) {
            Snapshot snapshot = retained.get(i);
            if (snapshot == latest || transfers.values().stream().anyMatch(value -> value.id == snapshot.asset.id)) continue;
            if (snapshots.remove(snapshot.asset.id, snapshot)) { removeSnapshotFiles(snapshot.apk.getParent());count--; }
        }
    }
    private static void removeSnapshotFiles(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.deleteIfExists(path.resolve("app.apk"));Files.deleteIfExists(path.resolve("release.json"));Files.deleteIfExists(path);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name);thread.setDaemon(true);return thread; }
    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (status == 503) exchange.getResponseHeaders().set("Retry-After", "20");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); } finally { exchange.close(); }
    }
    @Override public void close() {
        closed = true;
        HttpURLConnection connection = upstream;if (connection != null) connection.disconnect();
        for (HttpExchange exchange : transfers.keySet()) exchange.close();
        work.shutdownNow();streams.shutdownNow();watchdog.shutdownNow();
    }
}
