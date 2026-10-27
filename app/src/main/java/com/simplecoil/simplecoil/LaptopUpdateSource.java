package com.simplecoil.simplecoil;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Reads the connected laptop's verified cache before using the phone's Internet. */
final class LaptopUpdateSource implements AppUpdateController.Source {
    static final int DEFAULT_PORT = 17512;
    static final long RETRY_AFTER_MS = 20_000;

    static final class Endpoint {
        final InetAddress host;
        final int port;

        Endpoint(InetAddress host, int port) {
            if (host == null || host.isAnyLocalAddress() || host.isMulticastAddress()
                    || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid laptop endpoint");
            this.host = host;
            this.port = port;
        }

        String key() { return "laptop:" + host.getHostAddress() + ":" + port; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Endpoint)) return false;
            Endpoint endpoint = (Endpoint) other;
            return host.equals(endpoint.host) && port == endpoint.port;
        }

        @Override public int hashCode() { return 31 * host.hashCode() + port; }
    }

    private final Supplier<Endpoint> connectedLaptop;
    private final BooleanSupplier internetAvailable;
    private final ReleaseUpdate releases;

    LaptopUpdateSource(Supplier<Endpoint> connectedLaptop, BooleanSupplier internetAvailable) {
        this(connectedLaptop, internetAvailable, new ReleaseUpdate());
    }

    LaptopUpdateSource(Supplier<Endpoint> connectedLaptop, BooleanSupplier internetAvailable,
                       ReleaseUpdate releases) {
        this.connectedLaptop = connectedLaptop;
        this.internetAvailable = internetAvailable;
        this.releases = releases;
    }

    private Endpoint endpoint() {
        try { return connectedLaptop.get(); }
        catch (RuntimeException unavailable) { return null; }
    }

    private boolean online() {
        try { return internetAvailable.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    @Override public String key() {
        Endpoint endpoint = endpoint();
        return endpoint == null ? "github" : endpoint.key();
    }

    @Override public boolean available(boolean internet) { return endpoint() != null || internet; }

    @Override public long retryAfterMillis() { return endpoint() == null ? 0 : RETRY_AFTER_MS; }

    @Override public AppUpdateController.Source snapshot() { return new Selection(endpoint()); }

    @Override public ReleaseUpdate.Asset latest() throws IOException { return snapshot().latest(); }

    @Override public void download(ReleaseUpdate.Asset asset, File destination) throws IOException {
        snapshot().download(asset, destination);
    }

    private final class Selection implements AppUpdateController.Source {
        private final Endpoint selected;
        private boolean localMetadata;
        Selection(Endpoint selected) { this.selected = selected;localMetadata = selected != null; }

        @Override public String key() { return selected == null ? "github" : selected.key(); }
        @Override public boolean available(boolean internet) { return selected != null || internet; }
        @Override public AppUpdateController.Source snapshot() { return this; }

        private void requireCurrent() throws IOException {
            if (!Objects.equals(selected, endpoint())) throw new IOException("The update laptop changed");
        }

        @Override public ReleaseUpdate.Asset latest() throws IOException {
            requireCurrent();
            if (selected != null) {
                try {
                    ReleaseUpdate.Asset asset = releases.fetchLaptopLatest(selected.host, selected.port);
                    requireCurrent();
                    if (asset != null) return asset;
                } catch (IOException unavailable) {
                    requireCurrent();
                    if (unavailable instanceof ReleaseUpdate.LaptopHttpException
                            && ((ReleaseUpdate.LaptopHttpException) unavailable).status == 503)
                        throw unavailable;
                    if (!online()) throw unavailable;
                }
            }
            localMetadata = false;
            if (!online()) throw new IOException("No update source is available");
            ReleaseUpdate.Asset asset = releases.fetchLatest();
            requireCurrent();
            return asset;
        }

        @Override public void download(ReleaseUpdate.Asset asset, File destination) throws IOException {
            download(asset, destination, () -> true);
        }

        @Override public void download(ReleaseUpdate.Asset asset, File destination,
                                       BooleanSupplier stillAllowed) throws IOException {
            requireCurrent();
            requireAllowed(stillAllowed);
            if (selected != null && localMetadata) {
                // Busy stream slots and a warming cache retry locally; a group
                // of phones must not all switch to large Internet downloads.
                releases.downloadFromLaptop(asset, destination, selected.host, selected.port);
                requireCurrent();
                return;
            }
            requireAllowed(stillAllowed);
            if (!online()) throw new IOException("No update source is available");
            releases.download(asset, destination);
            requireCurrent();
        }

        private void requireAllowed(BooleanSupplier allowed) throws IOException {
            if (!allowed.getAsBoolean()) throw new IOException("Update download deferred while playing");
        }
    }
}
