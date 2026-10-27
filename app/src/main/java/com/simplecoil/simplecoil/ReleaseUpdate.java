package com.simplecoil.simplecoil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Bounded, unauthenticated release lookup and download. APK identity is checked by the caller. */
final class ReleaseUpdate {
    static final long MAX_APK_BYTES = 100L * 1024L * 1024L;
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    static final String LATEST_URL =
            "https://api.github.com/repos/mikecarper/SimpleCoil_HFC_EDIT/releases/latest";
    private static final String RELEASE_PATH =
            "/mikecarper/SimpleCoil_HFC_EDIT/releases/download/";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final long METADATA_TIMEOUT_MS = 15000L;
    private static final long LAPTOP_METADATA_TIMEOUT_MS = 5000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 120000L;
    private static final int MAX_REDIRECTS = 5;
    private final ConnectionOpener connections;

    interface ConnectionOpener {
        HttpURLConnection open(URL url) throws IOException;
        default HttpURLConnection openLocal(URL url) throws IOException { return open(url); }
    }

    static final class LaptopHttpException extends IOException {
        final int status;

        LaptopHttpException(int status) {
            super("Laptop update server returned HTTP " + status);
            this.status = status;
        }
    }

    static final class Asset {
        final long id;
        final String version;
        final String name;
        final URL url;
        final long size;
        final String sha256;
        final String updatedAt;
        private final String tag;

        private Asset(long id, String tag, String version, String name, URL url, long size,
                      String sha256, String updatedAt) {
            this.id = id;
            this.tag = tag;
            this.version = version;
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
            this.updatedAt = updatedAt;
        }

        String cacheKey() {
            return id + ":" + updatedAt + ":" + size + ":" + (sha256 == null ? "" : sha256);
        }
    }

    ReleaseUpdate() {
        this(new ConnectionOpener() {
            @Override public HttpURLConnection open(URL url) throws IOException {
                return ReleaseUpdateTls.open(url);
            }
            @Override public HttpURLConnection openLocal(URL url) throws IOException {
                // Only the literal joined peer is contacted; a system web proxy must not relay LAN updates.
                return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            }
        });
    }

    ReleaseUpdate(ConnectionOpener connections) {
        if (connections == null) throw new IllegalArgumentException("Missing connection opener");
        this.connections = connections;
    }

    /** Returns null when GitHub has no stable release with the exact release APK. */
    Asset fetchLatest() throws IOException {
        long deadline = deadlineAfter(METADATA_TIMEOUT_MS);
        HttpURLConnection connection = openResponse(new URL(LATEST_URL), false, deadline);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            requireSuccess(status);
            long declaredLength = contentLength(connection);
            if (declaredLength > MAX_METADATA_BYTES) throw new IOException("Release metadata is too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                copyBounded(connection, input, bytes, MAX_METADATA_BYTES, deadline, null);
            }
            if (declaredLength >= 0 && bytes.size() != declaredLength) {
                throw new IOException("Incomplete release metadata");
            }
            return parseLatest(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            disconnect(connection);
        }
    }

    /** Reads only the joined laptop's cache; its original GitHub metadata stays strictly validated. */
    Asset fetchLaptopLatest(InetAddress host, int port) throws IOException {
        long deadline = deadlineAfter(LAPTOP_METADATA_TIMEOUT_MS);
        HttpURLConnection connection = openLaptopResponse(
                laptopUrl(host, port, "/api/app-update/latest"), false, deadline);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            requireLaptopSuccess(status);
            long declaredLength = contentLength(connection);
            if (declaredLength > MAX_METADATA_BYTES) throw new IOException("Release metadata is too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                copyBounded(connection, input, bytes, MAX_METADATA_BYTES, deadline, null);
            }
            if (declaredLength >= 0 && bytes.size() != declaredLength)
                throw new IOException("Incomplete laptop release metadata");
            Asset asset = parseLatest(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (asset != null && asset.sha256 == null)
                throw new IOException("The laptop update requires a SHA-256 digest");
            return asset;
        } finally {
            disconnect(connection);
        }
    }

    static Asset parseLatest(String json) throws IOException {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new IOException("Invalid release metadata size");
        }
        try {
            JSONObject release = new JSONObject(json);
            boolean draft = requiredBoolean(release, "draft");
            boolean prerelease = requiredBoolean(release, "prerelease");
            if (draft || prerelease) return null;
            String tag = requiredString(release, "tag_name");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!version.matches("[0-9][0-9A-Za-z._+-]{0,63}")) {
                throw new IOException("Invalid release version");
            }
            String expectedName = "LAZER-GUN-RUN-" + version + ".apk";
            JSONArray assets = release.getJSONArray("assets");
            Asset selected = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject candidate = assets.getJSONObject(i);
                if (!expectedName.equals(candidate.optString("name", ""))) continue;
                if (!"uploaded".equals(requiredString(candidate, "state"))) continue;
                if (selected != null) throw new IOException("Ambiguous release APK");
                long id = requiredPositiveLong(candidate, "id");
                long size = requiredPositiveLong(candidate, "size");
                if (size > MAX_APK_BYTES) throw new IOException("Release APK is too large");
                String updatedAt = requiredString(candidate, "updated_at");
                if (!updatedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) {
                    throw new IOException("Invalid release asset timestamp");
                }
                String digest = null;
                if (candidate.has("digest") && !candidate.isNull("digest")) {
                    String value = requiredString(candidate, "digest");
                    if (!value.matches("sha256:[0-9a-fA-F]{64}")) {
                        throw new IOException("Invalid release APK digest");
                    }
                    digest = value.substring(7).toLowerCase(Locale.US);
                }
                URL url = new URL(requiredString(candidate, "browser_download_url"));
                validateInitialAssetUrl(url, tag, expectedName);
                selected = new Asset(id, tag, version, expectedName, url, size, digest, updatedAt);
            }
            return selected;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid release metadata", e);
        }
    }

    /** Downloads into a private caller-owned file, removing it on every unsuccessful attempt. */
    void download(Asset asset, File destination) throws IOException {
        downloadAsset(asset, destination, false, null, 0);
    }

    /** Local transport is restricted to the connected peer; APK signer/version checks remain mandatory. */
    void downloadFromLaptop(Asset asset, File destination, InetAddress host, int port) throws IOException {
        downloadAsset(asset, destination, true, host, port);
    }

    private void downloadAsset(Asset asset, File destination, boolean fromLaptop, InetAddress host, int port)
            throws IOException {
        if (destination == null) throw new IllegalArgumentException("Missing download destination");
        boolean complete = false;
        HttpURLConnection connection = null;
        try {
            if (asset == null || asset.size <= 0 || asset.size > MAX_APK_BYTES) {
                throw new IOException("Invalid release APK");
            }
            validateInitialAssetUrl(asset.url, asset.tag, asset.name);
            if (fromLaptop && asset.sha256 == null)
                throw new IOException("The laptop update requires a SHA-256 digest");
            long deadline = deadlineAfter(DOWNLOAD_TIMEOUT_MS);
            connection = fromLaptop
                    ? openLaptopResponse(laptopUrl(host, port, "/api/app-update/apk/" + asset.id), true, deadline)
                    : openResponse(asset.url, true, deadline);
            if (fromLaptop) requireLaptopSuccess(connection.getResponseCode());
            else requireSuccess(connection.getResponseCode());
            long declaredLength = contentLength(connection);
            if (declaredLength >= 0 && declaredLength != asset.size) {
                throw new IOException("Release APK length does not match metadata");
            }
            MessageDigest digest = sha256Digest();
            long count;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                count = copyBounded(connection, input, output, asset.size, deadline, digest);
            }
            if (count != asset.size) throw new IOException("Incomplete release APK");
            if (asset.sha256 != null && !asset.sha256.equals(hex(digest.digest()))) {
                throw new IOException("Release APK digest does not match metadata");
            }
            complete = true;
        } finally {
            if (connection != null) disconnect(connection);
            if (!complete && destination.exists() && !destination.delete()) {
                // A failed artifact must not remain eligible for installation.
                try (FileOutputStream ignored = new FileOutputStream(destination, false)) {
                    // Truncate if the filesystem refused deletion of the private file.
                }
            }
        }
    }

    static boolean isStrictlyNewer(long archiveVersionCode, long installedVersionCode) {
        return archiveVersionCode > 0 && installedVersionCode >= 0
                && archiveVersionCode > installedVersionCode;
    }

    private static URL laptopUrl(InetAddress host, int port, String path) throws IOException {
        if (host == null || host.isAnyLocalAddress() || host.isMulticastAddress() || port < 1 || port > 65535)
            throw new IOException("Invalid connected laptop endpoint");
        // A numeric address from the TCP peer avoids DNS rebinding and metadata-selected destinations.
        return new URL("http", host.getHostAddress(), port, path);
    }

    private HttpURLConnection openLaptopResponse(URL url, boolean download, long deadline) throws IOException {
        HttpURLConnection connection = connections.openLocal(url);
        if (connection == null) throw new IOException("Could not open laptop release connection");
        boolean retained = false;
        try {
            int remaining = remainingMillis(deadline);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.min(2000, remaining));
            connection.setReadTimeout(Math.min(download ? READ_TIMEOUT_MS : 2000, remaining));
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "LAZER-GUN-RUN-updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", download ? "application/octet-stream" : "application/json");
            if (isRedirect(connection.getResponseCode()))
                throw new IOException("Laptop update redirects are not allowed");
            retained = true;
            return connection;
        } finally {
            if (!retained) disconnect(connection);
        }
    }

    private HttpURLConnection openResponse(URL initial, boolean download, long deadline)
            throws IOException {
        URL current = initial;
        for (int redirects = 0; ; redirects++) {
            int remaining = remainingMillis(deadline);
            HttpURLConnection connection = connections.open(current);
            if (connection == null) throw new IOException("Could not open release connection");
            boolean retained = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, remaining));
                connection.setReadTimeout(Math.min(READ_TIMEOUT_MS, remaining));
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "LAZER-GUN-RUN-updater");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", download ? "application/octet-stream"
                        : "application/vnd.github+json");
                if (!download) connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
                int status = connection.getResponseCode();
                if (!isRedirect(status)) {
                    retained = true;
                    return connection;
                }
                if (!download || redirects >= MAX_REDIRECTS) {
                    throw new IOException("Unexpected release redirect");
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.length() > 16384) {
                    throw new IOException("Missing release redirect destination");
                }
                URL next = new URL(current, location);
                validateCdnUrl(next);
                current = next;
            } finally {
                if (!retained) disconnect(connection);
            }
        }
    }

    private static long copyBounded(HttpURLConnection connection, InputStream input,
                                    java.io.OutputStream output, long limit, long deadline,
                                    MessageDigest digest) throws IOException {
        byte[] buffer = new byte[16384];
        long count = 0;
        while (true) {
            connection.setReadTimeout(Math.min(READ_TIMEOUT_MS, remainingMillis(deadline)));
            int read = input.read(buffer);
            if (read < 0) return count;
            if (read == 0) continue;
            if (read > limit - count) throw new IOException("Release response exceeds allowed size");
            output.write(buffer, 0, read);
            if (digest != null) digest.update(buffer, 0, read);
            count += read;
        }
    }

    private static void validateInitialAssetUrl(URL url, String tag, String name) throws IOException {
        validateHttps(url);
        if (!"github.com".equalsIgnoreCase(url.getHost())
                || !(RELEASE_PATH + tag + "/" + name).equals(url.getPath())
                || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Untrusted release APK URL");
        }
    }

    private static void validateCdnUrl(URL url) throws IOException {
        validateHttps(url);
        String host = url.getHost().toLowerCase(Locale.US);
        if (!(host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com")) || url.getRef() != null) {
            throw new IOException("Untrusted release download redirect");
        }
    }

    private static void validateHttps(URL url) throws IOException {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IOException("Release connections require trusted HTTPS");
        }
    }

    private static boolean requiredBoolean(JSONObject object, String key) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw new IOException("Invalid release flag: " + key);
        return (Boolean) value;
    }

    private static String requiredString(JSONObject object, String key) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IOException("Invalid release field: " + key);
        }
        return (String) value;
    }

    private static long requiredPositiveLong(JSONObject object, String key) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IOException("Invalid release number: " + key);
        long number = ((Number) value).longValue();
        if (number <= 0 || ((Number) value).doubleValue() != (double) number) {
            throw new IOException("Invalid release number: " + key);
        }
        return number;
    }

    private static long contentLength(HttpURLConnection connection) throws IOException {
        String header = connection.getHeaderField("Content-Length");
        if (header == null) return -1;
        try {
            long length = Long.parseLong(header);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid release response length", e);
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = hex[(bytes[i] & 0xff) >>> 4];
            result[i * 2 + 1] = hex[bytes[i] & 0xf];
        }
        return new String(result);
    }

    private static long deadlineAfter(long millis) {
        return System.nanoTime() + millis * 1000000L;
    }

    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Release check interrupted");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new InterruptedIOException("Release connection timed out");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, nanos / 1000000L));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void requireSuccess(int status) throws IOException {
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Release server returned HTTP " + status);
        }
    }

    private static void requireLaptopSuccess(int status) throws LaptopHttpException {
        if (status != HttpURLConnection.HTTP_OK) throw new LaptopHttpException(status);
    }

    private static void disconnect(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error != null) {
            try {
                error.close();
            } catch (IOException ignored) {
                // Preserve the original response or download failure.
            }
        }
        connection.disconnect();
    }
}
