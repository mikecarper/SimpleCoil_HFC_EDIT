package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real controller lifecycle with only internet, release source, installer and UI replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateControllerTest {
    @Test public void launchQueuesAllNetworkAndArchiveWorkWithoutBlockingTheCaller() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.controller.onResume();
            assertEquals(0, fixture.source.lookups);
            assertEquals(0, fixture.source.downloads);
            assertEquals(0, fixture.installer.inspections);
            assertEquals(0, fixture.installer.begins);
            assertEquals(1, fixture.worker.tasks.size());
            fixture.controller.onResume();
            assertEquals("Repeated resume queued another fetch", 1, fixture.worker.tasks.size());
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
            assertEquals(0, fixture.ui.permissions);
        }
    }

    @Test public void offlineLaunchContinuesAndReturningInternetChecksAutomatically() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.internet.available = false;
            fixture.controller.onResume();
            assertTrue(fixture.worker.tasks.isEmpty());
            assertEquals(0, fixture.ui.total());
            fixture.internet.available = true;
            fixture.internet.changed.run();
            fixture.idle();
            fixture.finishWork();
            assertEquals(1, fixture.installer.commits);
            fixture.controller.onPause();
            assertFalse(fixture.internet.watching);
        }
    }

    @Test public void reconnectDuringFailedMetadataLookupRetriesOnceAfterItSettles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.duringLookup = () -> {
                fixture.source.duringLookup = null;
                fixture.reconnect();
                throw new IOException("The old metadata connection failed after Internet returned");
            };
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.source.lookups);
            assertEquals(1, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
            fixture.advance(120_000);
            fixture.finishWork();
            assertEquals("A settled retry must not queue more checks", 2, fixture.source.lookups);
        }
    }

    @Test public void reconnectDuringFailedDownloadRetriesAfterItSettles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.beforeDownload = () -> {
                fixture.source.beforeDownload = null;
                fixture.reconnect();
                throw new IOException("The old APK connection failed after Internet returned");
            };
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.source.lookups);
            assertEquals(2, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
            assertEquals(0, fixture.ui.total());
        }
    }

    @Test public void reconnectRetryWaitsForTheActiveGameBeforeDownloading() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.duringLookup = () -> {
                fixture.source.duringLookup = null;
                fixture.eligible = false;
                fixture.reconnect();
                throw new IOException("The old metadata connection failed during game startup");
            };
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.source.lookups);
            assertEquals(0, fixture.source.downloads);
            assertEquals(0, fixture.installer.begins);
            fixture.advance(120_000);
            fixture.finishWork();
            assertEquals(0, fixture.source.downloads);
            fixture.eligible = true;
            fixture.advance(2_000);
            fixture.finishWork();
            assertEquals(1, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void reconnectRetryWaitsForResumeWhenTheOldRequestCompletesPaused() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.duringLookup = () -> {
                fixture.source.duringLookup = null;
                fixture.reconnect();
                fixture.controller.onPause();
                throw new IOException("The old request failed after this activity paused");
            };
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(0, fixture.source.downloads);
            assertEquals(0, fixture.ui.total());
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.source.lookups);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void metadataAndDownloadFailuresRemainQuietAndNeverReachInstallation() throws Exception {
        for (boolean download : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.source.failLookup = !download;
                fixture.source.failDownload = download;
                fixture.controller.onResume();
                fixture.finishWork();
                fixture.advance(120_000);
                fixture.finishWork();
                assertEquals("A stable online failure must not trigger a retry loop", 1,
                        fixture.source.lookups);
                assertEquals(download ? 1 : 0, fixture.source.downloads);
                assertEquals(0, fixture.installer.begins);
                assertEquals(0, fixture.ui.total());
            }
        }
    }

    @Test public void absentReleaseOrUnverifiableArchiveDoesNotLaunchSettingsOrInstaller() throws Exception {
        for (boolean noRelease : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.source.noRelease = noRelease;
                fixture.installer.failInspection = !noRelease;
                fixture.installer.permissionNeeded = true;
                fixture.controller.onResume();
                fixture.finishWork();
                assertEquals(0, fixture.installer.begins);
                assertEquals(0, fixture.ui.total());
            }
        }
    }

    @Test public void pausedCheckCompletionWaitsUntilAnEligibleResume() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.controller.onResume();
            fixture.controller.onPause();
            fixture.finishWork();
            assertEquals(0, fixture.installer.begins);
            assertEquals(0, fixture.ui.total());
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void anIneligibleGameFetchesOnlySmallMetadataUntilItBecomesIdle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.eligible = false;
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(0, fixture.source.downloads);
            assertEquals(0, fixture.installer.inspections);
            assertEquals(0, fixture.installer.begins);
            fixture.eligible = true;
            fixture.advance(2_000);
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void closeDiscardsQueuedAndLateCallbacksAndOnlyClearsItsOwnListener() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.controller.onResume();
            Runnable lateNetwork = fixture.internet.changed;
            Runnable lateInstaller = fixture.installer.listener;
            fixture.controller.close();
            fixture.finishWork();
            lateNetwork.run();
            lateInstaller.run();
            fixture.idle();
            fixture.controller.onResume();
            assertEquals(0, fixture.source.lookups);
            assertEquals(0, fixture.installer.begins);
            assertEquals(0, fixture.ui.total());
            assertFalse(fixture.internet.watching);
            assertNull(fixture.installer.listener);
        }
    }

    @Test public void aSavedMatchOrGameThatStartsDuringDownloadDefersTheReadyUpdate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.duringDownload = () -> fixture.eligible = false;
            fixture.installer.permissionNeeded = true;
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(0, fixture.installer.begins);
            assertEquals(0, fixture.ui.total());
            fixture.eligible = true;
            fixture.advance(2_000);
            assertEquals("Ready update did not drain when the game became idle", 1,
                    fixture.ui.permissions);
        }
    }

    @Test public void finalCommitRechecksEligibilityAfterInstallerPreparation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installer.beforeCommit = () -> fixture.eligible = false;
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.installer.begins);
            assertEquals(0, fixture.installer.commits);
            assertEquals(0, fixture.ui.total());
            fixture.installer.beforeCommit = null;
            fixture.eligible = true;
            fixture.advance(2_000);
            fixture.finishWork();
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void deniedUnknownSourcePermissionDoesNotLoopOrRefetchOnReturn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installer.permissionNeeded = true;
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.ui.permissions);
            fixture.controller.onPause();
            fixture.advance(61_000);
            fixture.controller.onResume();
            fixture.finishWork();
            fixture.advance(5_000);
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.ui.permissions);
            assertEquals(0, fixture.installer.begins);
        }
    }

    @Test public void grantingPermissionInstallsTheVerifiedFileOnceWithoutAnotherDownload() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installer.permissionNeeded = true;
            fixture.controller.onResume();
            fixture.finishWork();
            fixture.controller.onPause();
            fixture.installer.permissionNeeded = false;
            fixture.controller.onResume();
            fixture.finishWork();
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.source.downloads);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void aLaterFreshForegroundCanRetryAfterPermissionDenial() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installer.permissionNeeded = true;
            fixture.controller.onResume();
            fixture.finishWork();
            fixture.controller.onPause();
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.ui.permissions);
            fixture.controller.onPause();
            fixture.advance(60_000);
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.ui.permissions);
            assertEquals(2, fixture.source.lookups);
            assertEquals(1, fixture.source.downloads);
        }
    }

    @Test public void pendingConfirmationIsRetainedWhilePausedOrPlayingThenDeliveredOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.internet.available = false;
            fixture.eligible = false;
            fixture.installer.installing = true;
            fixture.controller.onResume();
            fixture.controller.onPause();
            fixture.installer.pending = new Intent("android.install.CONFIRM");
            fixture.installer.listener.run();
            fixture.idle();
            assertNotNull(fixture.installer.pending);
            fixture.controller.onResume();
            assertNotNull(fixture.installer.pending);
            assertEquals(0, fixture.ui.confirmations);
            fixture.eligible = true;
            fixture.advance(2_000);
            assertNull(fixture.installer.pending);
            assertEquals(1, fixture.ui.confirmations);
            fixture.advance(4_000);
            assertEquals(1, fixture.ui.confirmations);
        }
    }

    @Test public void retiringAnOlderControllersInstallUnblocksThisLaunchsLookup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.installer.installing = true;
            fixture.installer.state = "installing";
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(0, fixture.source.lookups);
            fixture.installer.installing = false;
            fixture.installer.state = "idle";
            fixture.installer.listener.run();
            fixture.idle();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void knownEqualOrOlderAssetSkipsTheLargeDownloadOnAnotherLaunch() throws Exception {
        for (long code : new long[] {52, 53}) {
            try (Fixture fixture = new Fixture()) {
                fixture.installer.archiveCode = code;
                fixture.controller.onResume();
                fixture.finishWork();
                assertEquals(1, fixture.source.downloads);
                assertEquals(0, fixture.installer.begins);
                fixture.reopen();
                fixture.controller.onResume();
                fixture.finishWork();
                assertEquals(2, fixture.source.lookups);
                assertEquals(1, fixture.source.downloads);
                assertEquals(0, fixture.installer.begins);
            }
        }
    }

    @Test public void cachedNewerFileIsReverifiedButNotDownloadedAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.duringDownload = () -> fixture.eligible = false;
            fixture.controller.onResume();
            fixture.finishWork();
            int inspections = fixture.installer.inspections;
            fixture.reopen();
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.downloads);
            fixture.eligible = true;
            fixture.advance(2_000);
            fixture.finishWork();
            assertTrue(fixture.installer.inspections > inspections);
            assertEquals(1, fixture.installer.commits);
        }
    }

    @Test public void aMinuteAwayAllowsFreshMetadataButBriefResumeDoesNot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.noRelease = true;
            fixture.controller.onResume();
            fixture.finishWork();
            fixture.controller.onPause();
            fixture.advance(1_000);
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(1, fixture.source.lookups);
            fixture.controller.onPause();
            fixture.advance(60_000);
            fixture.controller.onResume();
            fixture.finishWork();
            assertEquals(2, fixture.source.lookups);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = RuntimeEnvironment.getApplication();
        final ManualExecutor worker = new ManualExecutor();
        final FakeInternet internet = new FakeInternet();
        final FakeSource source = new FakeSource();
        final FakeInstallation installer = new FakeInstallation();
        final FakeUi ui = new FakeUi();
        boolean eligible = true;
        AppUpdateController controller;

        Fixture() throws Exception {
            context.getSharedPreferences(AppUpdateController.CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            File apk = new File(context.getNoBackupFilesDir(), "updates/latest.apk");
            if (apk.exists()) assertTrue(apk.delete());
            controller = create();
        }

        AppUpdateController create() {
            return new AppUpdateController(context, 53, () -> eligible,
                    internet, source, installer, ui, worker);
        }

        void reopen() {
            controller.close();
            controller = create();
        }

        void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

        void reconnect() {
            internet.available = false;
            internet.changed.run();
            idle();
            internet.available = true;
            internet.changed.run();
            idle();
        }

        void advance(long millis) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
        }

        void finishWork() {
            for (int turn = 0; turn < 20; turn++) {
                worker.runAll();
                idle();
                if (worker.tasks.isEmpty()) return;
            }
            throw new AssertionError("Updater queued work repeatedly without finishing");
        }

        @Override public void close() { controller.close();idle(); }
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class FakeInternet implements AppUpdateController.Internet {
        boolean available = true;
        boolean watching;
        Runnable changed;
        @Override public boolean available() { return available; }
        @Override public void start(Runnable changed) { this.changed = changed;watching = true; }
        @Override public void stop() { watching = false; }
    }

    private static final class FakeSource implements AppUpdateController.Source {
        interface NetworkStep { void run() throws IOException; }

        int lookups;
        int downloads;
        boolean noRelease;
        boolean failLookup;
        boolean failDownload;
        NetworkStep duringLookup;
        NetworkStep beforeDownload;
        Runnable duringDownload;
        @Override public ReleaseUpdate.Asset latest() throws IOException {
            lookups++;
            if (duringLookup != null) duringLookup.run();
            if (failLookup) throw new IOException("Offline or release service unavailable");
            if (noRelease) return null;
            return ReleaseUpdate.parseLatest("{\"draft\":false,\"prerelease\":false,"
                    + "\"tag_name\":\"1.53\",\"assets\":[{\"id\":7,\"size\":3,"
                    + "\"updated_at\":\"2026-10-25T00:00:00Z\",\"state\":\"uploaded\","
                    + "\"name\":\"LAZER-GUN-RUN-1.53.apk\",\"browser_download_url\":"
                    + "\"https://github.com/mikecarper/SimpleCoil_HFC_EDIT/releases/download/1.53/"
                    + "LAZER-GUN-RUN-1.53.apk\"}]}");
        }
        @Override public void download(ReleaseUpdate.Asset asset, File destination) throws IOException {
            downloads++;
            if (beforeDownload != null) beforeDownload.run();
            if (failDownload) throw new IOException("Connection lost during download");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write(new byte[] {1, 2, 3});
            }
            if (duringDownload != null) duringDownload.run();
        }
    }

    private static final class FakeInstallation implements AppUpdateController.Installation {
        long archiveCode = 54;
        int inspections;
        int begins;
        int commits;
        boolean failInspection;
        boolean permissionNeeded;
        boolean installing;
        String state = "idle";
        Intent pending;
        Runnable listener;
        Runnable beforeCommit;
        @Override public long inspect(File file) throws IOException {
            inspections++;
            if (failInspection) throw new IOException("Wrong package or signing certificate");
            if (!file.isFile() || file.length() != 3) throw new IOException("Incomplete APK");
            return archiveCode;
        }
        @Override public boolean needsUnknownSourcePermission() { return permissionNeeded; }
        @Override public void begin(File file, BooleanSupplier stillAllowed) {
            begins++;
            if (beforeCommit != null) beforeCommit.run();
            if (stillAllowed.getAsBoolean()) {
                commits++;
                installing = true;
                state = "installing";
            }
        }
        @Override public Intent takePendingAction() {
            Intent action = pending;
            pending = null;
            return action;
        }
        @Override public String status() { return state; }
        @Override public String error() { return ""; }
        @Override public boolean isInstalling() { return installing; }
        @Override public boolean canInstallSilently() { return false; }
        @Override public void setListener(Runnable listener) { this.listener = listener; }
        @Override public void clearListener(Runnable listener) {
            if (this.listener == listener) this.listener = null;
        }
    }

    private static final class FakeUi implements AppUpdateController.Ui {
        int permissions;
        int confirmations;
        int failures;
        @Override public void requestInstallPermission() { permissions++; }
        @Override public void confirmInstall(Intent action) { confirmations++; }
        @Override public void installFailed() { failures++; }
        int total() { return permissions + confirmations + failures; }
    }
}
