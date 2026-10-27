package com.simplecoil.simplecoil;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

/** Real PKIX validation and native TLS checks; no live network or production private keys. */
public class ReleaseUpdateTlsTest {
    @Test public void bundledRootMatchesTheOfficialFingerprintAndRejectsReplacementAnchors() throws Exception {
        X509Certificate root = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(
                        ReleaseUpdateTls.ROOT_PEM.getBytes(StandardCharsets.US_ASCII)));
        assertEquals("CN=ISRG Root X1,O=Internet Security Research Group,C=US",
                root.getSubjectX500Principal().getName());
        assertEquals(ReleaseUpdateTls.ROOT_SHA256, hex(MessageDigest.getInstance("SHA-256").digest(root.getEncoded())));
        root.verify(root.getPublicKey());
        assertNotNull(ReleaseUpdateTls.compatibilityFactory(ReleaseUpdateTls.ROOT_PEM));
        assertNull("Malformed trust setup must retain platform-only HTTPS",
                ReleaseUpdateTls.compatibilityFactory("not a certificate"));
        assertNull("An unrelated valid CA must not replace the compatibility root",
                ReleaseUpdateTls.compatibilityFactory(text("root.pem")));
    }

    @Test public void aValidPlatformChainNeverNeedsTheAdditionalTrustAnchor() throws Exception {
        CountingTrustManager platform = new CountingTrustManager(trust("root.pem"));
        CountingTrustManager extra = new CountingTrustManager(trust("unrelated-root.pem"));
        X509TrustManager combined = ReleaseUpdateTls.combinedTrustManager(platform, extra);
        combined.checkServerTrusted(chain("localhost.pem"), "RSA");
        assertEquals(1, platform.serverChecks);
        assertEquals(0, extra.serverChecks);
        assertEquals(2, combined.getAcceptedIssuers().length);
    }

    @Test public void missingPlatformRootFallsBackToFullPkixValidationWithoutTrustingAllChains() throws Exception {
        CountingTrustManager platform = new CountingTrustManager(trust("unrelated-root.pem"));
        CountingTrustManager extra = new CountingTrustManager(trust("root.pem"));
        X509TrustManager combined = ReleaseUpdateTls.combinedTrustManager(platform, extra);
        combined.checkServerTrusted(chain("localhost.pem"), "RSA");
        assertEquals(1, platform.serverChecks);
        assertEquals(1, extra.serverChecks);
        X509TrustManager neither = ReleaseUpdateTls.combinedTrustManager(
                ReleaseUpdateTls.trustManager(null), extra);
        expectCertificateFailure(() -> neither.checkServerTrusted(
                new X509Certificate[] {certificate("unrelated-root.pem")}, "RSA"));
        expectCertificateFailure(() -> combined.checkServerTrusted(chain("expired.pem"), "RSA"));
        expectCertificateFailure(() -> combined.checkServerTrusted(chain("future.pem"), "RSA"));
    }

    @Test public void extraServerTrustDoesNotChangeClientCertificateTrust() throws Exception {
        CountingTrustManager platform = new CountingTrustManager(trust("unrelated-root.pem"));
        CountingTrustManager extra = new CountingTrustManager(trust("root.pem"));
        X509TrustManager combined = ReleaseUpdateTls.combinedTrustManager(platform, extra);
        expectCertificateFailure(() -> combined.checkClientTrusted(chain("localhost.pem"), "RSA"));
        assertEquals(1, platform.clientChecks);
        assertEquals(0, extra.clientChecks);
    }

    @Test public void compatibilityIsPerConnectionAndLimitedToOldAndroidReleaseHosts() throws Exception {
        SSLSocketFactory globalFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier globalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLContext globalContext = SSLContext.getDefault();
        for (int sdk : new int[] {21, 22, 23, 24, 25}) {
            for (String host : new String[] {"api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com"}) {
                HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + host + "/").openConnection();
                HostnameVerifier verifier = connection.getHostnameVerifier();
                assertTrue(ReleaseUpdateTls.configure(connection, sdk));
                assertSame(verifier, connection.getHostnameVerifier());
                assertNotSame(globalFactory, connection.getSSLSocketFactory());
                connection.disconnect();
            }
        }
        for (int sdk : new int[] {-1, 0, 20, 26, 34}) {
            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://github.com/").openConnection();
            assertFalse(ReleaseUpdateTls.configure(connection, sdk));
            assertSame(globalFactory, connection.getSSLSocketFactory());
            connection.disconnect();
        }
        for (String url : new String[] {"http://github.com/", "https://github.com.example.com/",
                "https://example.com/", "https://user@github.com/", "https://github.com:444/"}) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            assertFalse(ReleaseUpdateTls.configure(connection, 22));
            if (connection instanceof HttpsURLConnection)
                assertSame(globalFactory, ((HttpsURLConnection) connection).getSSLSocketFactory());
            connection.disconnect();
        }
        assertSame(globalFactory, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(globalVerifier, HttpsURLConnection.getDefaultHostnameVerifier());
        assertSame(globalContext, SSLContext.getDefault());
    }

    @Test public void realTlsFallbackAcceptsOnlyTheMatchingHostname() throws Exception {
        runIsolatedTls("hostname");
    }

    private static void verifyMatchingHostname() throws Exception {
        SSLSocketFactory factory = ReleaseUpdateTls.socketFactory(trust("unrelated-root.pem"), trust("root.pem"));
        try (LocalServer server = new LocalServer("localhost.pem", true)) {
            HttpsURLConnection connection = server.connection("localhost", factory);
            try (InputStream input = connection.getInputStream()) {
                assertEquals('o', input.read());assertEquals('k', input.read());assertEquals(-1, input.read());
            } finally { connection.disconnect(); }
        }
        try (LocalServer server = new LocalServer("localhost.pem")) {
            HttpsURLConnection connection = server.connection("127.0.0.1", factory);
            try {
                connection.getInputStream().close();
                fail("A trusted certificate for localhost authenticated a different hostname");
            } catch (IOException expected) { assertCertificateFailure(expected); }
            finally { connection.disconnect(); }
        }
    }

    @Test public void realTlsFallbackRejectsExpiredAndNotYetValidCertificates() throws Exception {
        runIsolatedTls("validity");
    }

    private static void verifyCertificateValidity() throws Exception {
        SSLSocketFactory factory = ReleaseUpdateTls.socketFactory(trust("unrelated-root.pem"), trust("root.pem"));
        for (String certificate : new String[] {"expired.pem", "future.pem"}) {
            try (LocalServer server = new LocalServer(certificate)) {
                HttpsURLConnection connection = server.connection("localhost", factory);
                try {
                    connection.getInputStream().close();
                    fail("A certificate outside its validity period authenticated the TLS server");
                } catch (IOException expected) { assertCertificateFailure(expected); }
                finally { connection.disconnect(); }
            }
        }
    }

    // Robolectric's global desktop Conscrypt provider cannot run a TLS server
    // on Java 17 without illegal reflective access, and it also changes JDK EC
    // initialization. Use a clean bounded JVM for native sockets instead of
    // changing global providers or opening JDK internals for the full suite.
    // The client still uses the real production trust/factory implementation.
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Missing TLS fixture selection");
        if ("hostname".equals(args[0])) verifyMatchingHostname();
        else if ("validity".equals(args[0])) verifyCertificateValidity();
        else throw new IllegalArgumentException("Unknown TLS fixture selection");
    }

    private static void runIsolatedTls(String check) throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> type : new Class<?>[] {ReleaseUpdateTlsTest.class, ReleaseUpdateTls.class,
                Test.class, Class.forName("org.hamcrest.Matcher")})
            entries.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        URL fixture = ReleaseUpdateTlsTest.class.getResource("/release-tls/localhost.pem");
        assertNotNull("Missing native TLS fixtures", fixture);
        if ("jar".equals(fixture.getProtocol())) {
            entries.add(new File(((java.net.JarURLConnection) fixture.openConnection())
                    .getJarFileURL().toURI()).getAbsolutePath());
        } else {
            entries.add(new File(fixture.toURI()).getParentFile().getParentFile().getAbsolutePath());
        }
        StringBuilder classpath = new StringBuilder();
        for (String entry : entries) {
            if (classpath.length() > 0) classpath.append(File.pathSeparator);
            classpath.append(entry);
        }
        File log = File.createTempFile("simplecoil-native-tls-", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                    "-cp", classpath.toString(), ReleaseUpdateTlsTest.class.getName(), check)
                    .redirectErrorStream(true).redirectOutput(log).start();
            assertTrue("Native TLS fixture exceeded its 30-second bound", child.waitFor(30, TimeUnit.SECONDS));
            assertEquals("Native TLS fixture failed:\n" + new String(Files.readAllBytes(log.toPath()),
                    StandardCharsets.UTF_8), 0, child.exitValue());
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();child.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log.toPath());
        }
    }

    private static final class LocalServer implements AutoCloseable {
        final SSLServerSocket listener;
        final Thread worker;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile SSLSocket accepted;
        volatile Throwable serverFailure;
        volatile boolean requestReceived;
        final boolean expectRequest;
        LocalServer(String leaf) throws Exception {
            this(leaf, false);
        }
        LocalServer(String leaf, boolean expectRequest) throws Exception {
            this.expectRequest = expectRequest;
            KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null, null);
            String encoded = text("localhost-key.pem").replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getMimeDecoder().decode(encoded)));
            keys.setKeyEntry("localhost-test-only", key, "test-only".toCharArray(), chain(leaf));
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, "test-only".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1,
                    InetAddress.getByName("127.0.0.1"));
            // Match the TLS version negotiated by the supported legacy phones.
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            listener.setSoTimeout(10_000);
            worker = new Thread(() -> {
                ready.countDown();
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(10_000);socket.startHandshake();
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    if (line != null) {
                        requestReceived = true;
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                .getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                    }
                } catch (IOException failure) {
                    if (expectRequest) serverFailure = failure;
                } catch (RuntimeException | Error failure) { serverFailure = failure; }
            }, "Updater TLS regression localhost server");
            worker.setDaemon(true);worker.start();
            if (!ready.await(5, TimeUnit.SECONDS)) {
                listener.close();
                throw new AssertionError("Local TLS server did not reach accept readiness");
            }
        }
        HttpsURLConnection connection(String hostname, SSLSocketFactory factory) throws IOException {
            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + hostname + ":"
                    + listener.getLocalPort() + "/").openConnection();
            connection.setSSLSocketFactory(factory);connection.setConnectTimeout(5000);connection.setReadTimeout(10_000);
            return connection;
        }
        @Override public void close() throws Exception {
            listener.close();
            SSLSocket socket = accepted;
            if (socket != null) socket.close();
            worker.join(11_000);assertFalse("Local TLS fixture survived cleanup", worker.isAlive());
            if (serverFailure != null) throw new AssertionError("Local TLS server failed", serverFailure);
            if (expectRequest) assertTrue("The positive TLS fixture never received the HTTPS request", requestReceived);
        }
    }

    private static final class CountingTrustManager implements X509TrustManager {
        final X509TrustManager delegate;
        int serverChecks, clientChecks;
        CountingTrustManager(X509TrustManager delegate) { this.delegate = delegate; }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            serverChecks++;delegate.checkServerTrusted(chain, auth);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            clientChecks++;delegate.checkClientTrusted(chain, auth);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }
    private static X509TrustManager trust(String root) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());store.load(null, null);
        store.setCertificateEntry("fixture-only", certificate(root));return ReleaseUpdateTls.trustManager(store);
    }
    private static X509Certificate[] chain(String leaf) throws Exception {
        return new X509Certificate[] {certificate(leaf), certificate("root.pem")};
    }
    private static X509Certificate certificate(String name) throws Exception {
        try (InputStream input = resource(name)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }
    private static String text(String name) throws IOException {
        try (InputStream input = resource(name);ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.US_ASCII);
        }
    }
    private static InputStream resource(String name) throws IOException {
        InputStream input = ReleaseUpdateTlsTest.class.getResourceAsStream("/release-tls/" + name);
        if (input == null) throw new IOException("Missing public TLS fixture");return input;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void assertCertificateFailure(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof CertificateException) return;
        String message = failure.getMessage();
        assertTrue("TLS rejection did not identify a certificate or hostname failure: " + failure,
                failure instanceof SSLException && message != null
                        && (message.contains("certificate") || message.contains("Certificate") || message.contains("subject alternative"))
                        || message != null && message.startsWith("HTTPS hostname wrong"));
    }
    private interface Checked { void run() throws Exception; }
    private static void expectCertificateFailure(Checked action) throws Exception {
        try { action.run();fail("Untrusted certificate chain was accepted"); }
        catch (CertificateException expected) { }
    }
}
