package com.simplecoil.simplecoil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Updater-only missing-root compatibility for Android 5.x through 7.1. */
final class ReleaseUpdateTls {
    static final String ROOT_SHA256 =
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6";
    // Official public root: https://letsencrypt.org/certs/isrgrootx1.pem
    // Independently identical to AOSP android-7.1.1_r15/files/6187b673.0.
    // This is a CA trust anchor, not a GitHub leaf/intermediate certificate.
    static final String ROOT_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n"
            + "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n"
            + "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n"
            + "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n"
            + "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n"
            + "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n"
            + "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n"
            + "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n"
            + "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n"
            + "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n"
            + "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n"
            + "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n"
            + "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n"
            + "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n"
            + "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n"
            + "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n"
            + "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n"
            + "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n"
            + "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n"
            + "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n"
            + "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n"
            + "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n"
            + "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n"
            + "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n"
            + "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n"
            + "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n"
            + "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n"
            + "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n"
            + "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n"
            + "-----END CERTIFICATE-----\n";

    private ReleaseUpdateTls() { }

    static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        configure(connection, androidSdk());
        return connection;
    }

    static boolean configure(HttpURLConnection connection, int sdk) {
        if (!(connection instanceof HttpsURLConnection) || sdk < 21 || sdk > 25
                || !isReleaseHost(connection.getURL())) return false;
        SSLSocketFactory factory = CompatibilityHolder.FACTORY;
        if (factory == null) return false; // Keep the original platform trust on setup failure.
        ((HttpsURLConnection) connection).setSSLSocketFactory(factory);
        // Leave this connection's normal hostname verifier unchanged.
        return true;
    }

    private static int androidSdk() {
        try {
            return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return -1; // Plain Java tools and unknown runtimes use platform HTTPS unchanged.
        }
    }

    private static boolean isReleaseHost(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                || url.getPort() != -1 && url.getPort() != 443) return false;
        String host = url.getHost().toLowerCase(Locale.US);
        return host.equals("api.github.com") || host.equals("github.com")
                || host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com");
    }

    private static final class CompatibilityHolder {
        static final SSLSocketFactory FACTORY = compatibilityFactory(ROOT_PEM);
    }

    static SSLSocketFactory compatibilityFactory(String pem) {
        try {
            X509Certificate root = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(root.getEncoded());
            StringBuilder fingerprint = new StringBuilder(64);
            for (byte value : digest) fingerprint.append("0123456789abcdef".charAt((value & 255) >>> 4))
                    .append("0123456789abcdef".charAt(value & 15));
            if (!ROOT_SHA256.contentEquals(fingerprint) || root.getBasicConstraints() < 0)
                throw new CertificateException("Invalid updater compatibility root");
            root.checkValidity();
            root.verify(root.getPublicKey());
            KeyStore extraRoot = KeyStore.getInstance(KeyStore.getDefaultType());
            extraRoot.load(null, null);
            extraRoot.setCertificateEntry("isrg-root-x1", root);
            return socketFactory(trustManager(null), trustManager(extraRoot));
        } catch (GeneralSecurityException | IOException | RuntimeException unavailable) {
            return null;
        }
    }

    static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers())
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new GeneralSecurityException("No X.509 trust manager available");
    }

    static SSLSocketFactory socketFactory(X509TrustManager platform, X509TrustManager extra)
            throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {combinedTrustManager(platform, extra)}, null);
        return context.getSocketFactory();
    }

    static X509TrustManager combinedTrustManager(final X509TrustManager platform, final X509TrustManager extra) {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                platform.checkClientTrusted(chain, authType);
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    platform.checkServerTrusted(chain, authType);
                } catch (CertificateException missingRoot) {
                    extra.checkServerTrusted(chain, authType);
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] primary = platform.getAcceptedIssuers();
                X509Certificate[] fallback = extra.getAcceptedIssuers();
                X509Certificate[] issuers = Arrays.copyOf(primary, primary.length + fallback.length);
                System.arraycopy(fallback, 0, issuers, primary.length, fallback.length);
                return issuers;
            }
        };
    }
}
