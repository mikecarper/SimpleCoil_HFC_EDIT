package com.simplecoil.simplecoil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real source selection, strict metadata parser and bounded transport with fake HTTP peers. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LaptopUpdateSourceTest {
    private static final byte[] APK = "abc".getBytes(StandardCharsets.US_ASCII);
    private static final String METADATA = "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"1.58\","
            + "\"assets\":[{\"id\":7,\"size\":3,\"updated_at\":\"2026-10-26T00:00:00Z\","
            + "\"state\":\"uploaded\",\"name\":\"LAZER-GUN-RUN-1.58.apk\","
            + "\"digest\":\"sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\","
            + "\"browser_download_url\":\"https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/"
            + "download/1.58/LAZER-GUN-RUN-1.58.apk\"}]}";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void offlinePhoneUsesOnlyConnectedLaptopForMetadataAndVerifiedBytes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.online = false;
        assertTrue(fixture.source.available(false));
        assertEquals(20_000, fixture.source.retryAfterMillis());
        AppUpdateController.Source selected = fixture.source.snapshot();
        ReleaseUpdate.Asset asset = selected.latest();
        assertEquals("github.com", asset.url.getHost());
        File destination = temporary.newFile();
        selected.download(asset, destination);
        assertArrayEquals(APK, Files.readAllBytes(destination.toPath()));
        assertEquals(2, fixture.transport.requests.size());
        for (URL url : fixture.transport.requests) {
            assertEquals("http", url.getProtocol());
            assertEquals("192.168.1.2", url.getHost());
            assertEquals(17512, url.getPort());
        }
        assertEquals("/api/app-update/apk/7", fixture.transport.requests.get(1).getPath());
    }

    @Test public void aBusyLaptopDoesNotSendAnOnlineGroupToGithub() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.localMetadataStatus = 503;
        expectFailure(() -> fixture.source.snapshot().latest());
        assertEquals(1, fixture.transport.requests.size());
        assertEquals("192.168.1.2", fixture.transport.requests.get(0).getHost());
    }

    @Test public void anOlderLaptopWithoutTheEndpointFallsBackToGithubWhenOnline() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.localMetadataStatus = 404;
        AppUpdateController.Source selected = fixture.source.snapshot();
        ReleaseUpdate.Asset asset = selected.latest();
        selected.download(asset, temporary.newFile());
        assertEquals(3, fixture.transport.requests.size());
        assertEquals("api.github.com", fixture.transport.requests.get(1).getHost());
        assertEquals("github.com", fixture.transport.requests.get(2).getHost());
        assertEquals(0, fixture.transport.localApkRequests);
    }

    @Test public void aRefusedLocalConnectionCanUseThePhonesInternet() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.refuseLocal = true;
        assertEquals("1.58", fixture.source.snapshot().latest().version);
        assertEquals(2, fixture.transport.requests.size());
        assertEquals("api.github.com", fixture.transport.requests.get(1).getHost());
    }

    @Test public void busyApkStreamSlotsRemainLocalEvenWhenThePhoneHasInternet() throws Exception {
        Fixture fixture = new Fixture();
        AppUpdateController.Source selected = fixture.source.snapshot();
        ReleaseUpdate.Asset asset = selected.latest();
        fixture.transport.localApkStatus = 503;
        File destination = temporary.newFile();
        expectFailure(() -> selected.download(asset, destination));
        assertFalse("An incomplete APK survived", destination.exists());
        assertEquals(2, fixture.transport.requests.size());
        assertEquals(1, fixture.transport.localApkRequests);
        assertEquals("192.168.1.2", fixture.transport.requests.get(1).getHost());
    }

    @Test public void aHostChangeRejectsTheOldSelectionBeforeAnyApkRequest() throws Exception {
        Fixture fixture = new Fixture();
        AppUpdateController.Source selected = fixture.source.snapshot();
        ReleaseUpdate.Asset asset = selected.latest();
        String oldKey = selected.key();
        fixture.host = new LaptopUpdateSource.Endpoint(InetAddress.getByName("192.168.1.3"), 19512);
        assertNotEquals(oldKey, fixture.source.key());
        assertEquals(oldKey, selected.key());
        expectFailure(() -> selected.download(asset, temporary.newFile()));
        assertEquals(1, fixture.transport.requests.size());
    }

    @Test public void aHandoffDuringMetadataReadCannotPublishThePreviousHostsAsset() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.metadataRead = () -> fixture.host = null;
        expectFailure(() -> fixture.source.snapshot().latest());
        assertEquals(1, fixture.transport.requests.size());
        assertEquals("github", fixture.source.key());
        assertFalse(fixture.source.available(false));
    }

    @Test public void startingAGameBeforeDownloadTakesNoLanOrInternetBandwidth() throws Exception {
        Fixture fixture = new Fixture();
        AppUpdateController.Source selected = fixture.source.snapshot();
        ReleaseUpdate.Asset asset = selected.latest();
        expectFailure(() -> selected.download(asset, temporary.newFile(), () -> false));
        assertEquals(1, fixture.transport.requests.size());
    }

    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run();fail("Expected an unavailable/stale update source"); }
        catch (IOException expected) { }
    }

    private static final class Fixture {
        LaptopUpdateSource.Endpoint host;
        boolean online = true;
        final Transport transport = new Transport();
        final LaptopUpdateSource source;
        Fixture() throws Exception {
            host = new LaptopUpdateSource.Endpoint(InetAddress.getByName("192.168.1.2"), 17512);
            source = new LaptopUpdateSource(() -> host, () -> online, new ReleaseUpdate(transport));
        }
    }

    private static final class Transport implements ReleaseUpdate.ConnectionOpener {
        final List<URL> requests = new ArrayList<>();
        int localMetadataStatus = 200;
        int localApkStatus = 200;
        int localApkRequests;
        boolean refuseLocal;
        Runnable metadataRead;
        @Override public HttpURLConnection open(URL url) throws IOException {
            requests.add(url);
            boolean local = "http".equals(url.getProtocol());
            if (local && refuseLocal) throw new IOException("The old laptop has no cache service");
            boolean metadata = url.getPath().endsWith("/latest");
            if (local && !metadata) localApkRequests++;
            int status = local ? metadata ? localMetadataStatus : localApkStatus : 200;
            byte[] bytes = status == 200
                    ? metadata ? METADATA.getBytes(StandardCharsets.UTF_8) : APK : new byte[0];
            return new HttpURLConnection(url) {
                @Override public int getResponseCode() { return status; }
                @Override public String getHeaderField(String name) {
                    return "Content-Length".equalsIgnoreCase(name) ? Integer.toString(bytes.length) : null;
                }
                @Override public InputStream getInputStream() {
                    if (local && metadata && metadataRead != null) metadataRead.run();
                    return new ByteArrayInputStream(bytes);
                }
                @Override public void connect() { }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
            };
        }
    }
}
