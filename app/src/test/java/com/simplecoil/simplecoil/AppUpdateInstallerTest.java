package com.simplecoil.simplecoil;

import android.app.admin.DevicePolicyManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageInstaller;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSigningInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Runs archive policy and real installer-session/status handling without installing an app. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdateInstallerTest {
    private Context context;
    private ShadowPackageManager packages;
    private PackageInstaller sessions;
    private AppUpdateInstaller installer;
    private File apk;
    private final Signature release = new Signature(new byte[] {1, 2, 3});
    private final Signature other = new Signature(new byte[] {4, 5, 6});

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("AppUpdateInstall", Context.MODE_PRIVATE).edit().clear().commit();
        AppUpdateInstaller.setListener(null);
        resetProcessState();
        packages = Shadows.shadowOf(context.getPackageManager());
        packages.installPackage(info(context.getPackageName(), 53, release));
        packages.setCanRequestPackageInstalls(true);
        sessions = context.getPackageManager().getPackageInstaller();
        apk = File.createTempFile("update-installer-", ".apk", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(new byte[] {80, 75, 3, 4, 1, 2, 3, 4});
        }
        archive(54, release);
        installer = new AppUpdateInstaller(context);
    }

    @After public void tearDown() throws Exception {
        AppUpdateInstaller.setListener(null);
        for (PackageInstaller.SessionInfo session : sessions.getMySessions())
            sessions.abandonSession(session.getSessionId());
        resetProcessState();
        if (apk != null) apk.delete();
    }

    @Test public void inspectionAcceptsSignedOlderAndEqualVersionsButBeginRejectsThem() throws Exception {
        for (int version : new int[] {52, 53}) {
            archive(version, release);
            assertEquals(version, installer.inspect(apk));
            rejected(() -> installer.begin(apk));
            assertFalse(installer.isInstalling());
            assertTrue(sessions.getMySessions().isEmpty());
        }
        archive(54, release);
        assertEquals(54, installer.inspect(apk));
    }

    @Test public void wrongPackageMissingSignerAndDifferentSignerNeverCreateSessions() throws Exception {
        packages.setPackageArchiveInfo(apk.getAbsolutePath(), info("another.application", 54, release));
        rejected(() -> installer.begin(apk));
        archive(54, other);
        rejected(() -> installer.begin(apk));
        PackageInfo unsigned = info(context.getPackageName(), 54, release);
        unsigned.signingInfo = null;
        packages.setPackageArchiveInfo(apk.getAbsolutePath(), unsigned);
        rejected(() -> installer.begin(apk));
        assertFalse(installer.isInstalling());
        assertTrue(sessions.getMySessions().isEmpty());
    }

    @Test public void signerOrderIsIrrelevantButAnExtraSignerIsRejected() throws Exception {
        packages.installPackage(info(context.getPackageName(), 53, release, other));
        packages.setPackageArchiveInfo(apk.getAbsolutePath(), info(context.getPackageName(), 54, other, release));
        assertEquals(54, installer.inspect(apk));
        packages.installPackage(info(context.getPackageName(), 53, release));
        rejected(() -> installer.inspect(apk));
        assertTrue(sessions.getMySessions().isEmpty());
    }

    @Test public void deviceOwnerBypassesUnknownSourceGateButAnOrdinaryAppDoesNot() throws Exception {
        packages.setCanRequestPackageInstalls(false);
        assertTrue(installer.needsUnknownSourcePermission());
        assertFalse(installer.canInstallSilently());
        rejected(() -> installer.begin(apk));
        DevicePolicyManager policy = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Shadows.shadowOf(policy).setDeviceOwner(new ComponentName(context, EventDeviceAdminReceiver.class));
        assertTrue(installer.canInstallSilently());
        assertFalse(installer.needsUnknownSourcePermission());
        installer.begin(apk);
        assertTrue(installer.isInstalling());
    }

    @Test public void confirmationWaitsForControllerAndStaleStatusesCannotFinishCurrentSession() throws Exception {
        installer.begin(apk);
        int sessionId = sessionId();
        assertEquals("installing", installer.status());
        Intent confirm = new Intent("system.confirm.install");
        new AppUpdateInstallReceiver().onReceive(context,
                status(sessionId, PackageInstaller.STATUS_PENDING_USER_ACTION)
                        .putExtra(Intent.EXTRA_INTENT, confirm));
        assertEquals("confirmation", installer.status());
        assertTrue(installer.isInstalling());
        assertEquals(confirm.getAction(), installer.takePendingAction().getAction());
        assertNull(installer.takePendingAction());
        assertTrue(new AppUpdateInstaller(context).isInstalling());
        AppUpdateInstaller.onInstallStatus(context, status(sessionId + 1, PackageInstaller.STATUS_SUCCESS));
        assertTrue(installer.isInstalling());
        AppUpdateInstaller.onInstallStatus(context, status(sessionId, PackageInstaller.STATUS_SUCCESS));
        assertFalse(installer.isInstalling());
        assertEquals("success", installer.status());
        AppUpdateInstaller.onInstallStatus(context, status(sessionId, PackageInstaller.STATUS_FAILURE));
        assertEquals("success", installer.status());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void missingConfirmationAndInstallFailureClearPendingState() throws Exception {
        installer.begin(apk);
        AppUpdateInstaller.onInstallStatus(context, status(sessionId(), PackageInstaller.STATUS_PENDING_USER_ACTION));
        assertFalse(installer.isInstalling());
        assertEquals("failure", installer.status());
        assertFalse(installer.error().isEmpty());
        assertTrue(sessions.getMySessions().isEmpty());
        installer.begin(apk);
        int sessionId = sessionId();
        AppUpdateInstaller.onInstallStatus(context, status(sessionId, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("system.confirm.install")));
        AppUpdateInstaller.onInstallStatus(context, status(sessionId, PackageInstaller.STATUS_FAILURE_STORAGE)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Not enough storage"));
        assertFalse(installer.isInstalling());
        assertNull(installer.takePendingAction());
        assertEquals("failure", installer.status());
        assertEquals("Not enough storage", installer.error());
    }

    @Test public void processLossAbandonsOnlyTheRecordedOwnedSessionAndAllowsRetry() throws Exception {
        installer.begin(apk);
        int orphanId = sessionId();
        int unrelated = sessions.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        resetProcessState();
        installer = new AppUpdateInstaller(context);
        assertNull(sessions.getSessionInfo(orphanId));
        assertNotNull(sessions.getSessionInfo(unrelated));
        assertFalse(installer.isInstalling());
        assertEquals("interrupted", installer.status());
        installer.begin(apk);
        int nextId = sessionId();
        assertTrue(nextId != orphanId);
        AppUpdateInstaller.onInstallStatus(context, status(orphanId, PackageInstaller.STATUS_FAILURE_ABORTED));
        assertTrue(installer.isInstalling());
        assertEquals(nextId, sessionId());
    }

    @Test public void gameStartingBeforeCommitAbandonsCopiedSessionWithoutAnInstall() throws Exception {
        final int[] checks = {0};
        installer.begin(apk, () -> {
            checks[0]++;
            assertEquals(1, sessions.getMySessions().size());
            assertTrue(installer.isInstalling());
            return false;
        });
        assertEquals(1, checks[0]);
        assertTrue(sessions.getMySessions().isEmpty());
        assertFalse(installer.isInstalling());
        assertEquals("idle", installer.status());
        assertEquals("", installer.error());
    }

    @Test @Config(shadows = FailingSession.class)
    public void failedSessionWriteIsAbandonedAndDoesNotStrandTheUpdater() throws Exception {
        rejected(() -> installer.begin(apk));
        assertTrue(sessions.getMySessions().isEmpty());
        assertFalse(installer.isInstalling());
        assertEquals("failure", installer.status());
    }

    @Test public void clearingAListenerPreventsAnAlreadyPostedCallbackFromReachingAnOldActivity() throws Exception {
        installer.begin(apk);
        int[] calls = {0};
        Runnable first = () -> calls[0]++;
        AppUpdateInstaller.setListener(first);
        AppUpdateInstaller.onInstallStatus(context, status(sessionId(), PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("system.confirm.install")));
        Runnable second = () -> calls[0] += 10;
        AppUpdateInstaller.setListener(second);
        AppUpdateInstaller.clearListener(first);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, calls[0]);
        AppUpdateInstaller.onInstallStatus(context, status(sessionId(), PackageInstaller.STATUS_SUCCESS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(10, calls[0]);
    }

    @Test @Config(sdk = 34)
    public void modernAndroidGetsAnExplicitMutableStatusReceiver() throws Exception {
        installer.begin(apk);
        int sessionId = sessionId();
        Intent result = new Intent(context, AppUpdateInstallReceiver.class)
                .setAction(context.getPackageName() + ".UPDATE_INSTALL." + sessionId);
        PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_MUTABLE);
        assertNotNull(callback);
        assertTrue((Shadows.shadowOf(callback).getFlags() & PendingIntent.FLAG_MUTABLE) != 0);
        assertFalse(Shadows.shadowOf(callback).isImmutable());
        assertEquals(new ComponentName(context, AppUpdateInstallReceiver.class),
                Shadows.shadowOf(callback).getSavedIntent().getComponent());
        assertTrue(installer.isInstalling());
    }

    private int sessionId() {
        return context.getSharedPreferences("AppUpdateInstall", Context.MODE_PRIVATE).getInt("session", -1);
    }

    private Intent status(int sessionId, int status) {
        return new Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }

    private void archive(int version, Signature signer) {
        packages.setPackageArchiveInfo(apk.getAbsolutePath(), info(context.getPackageName(), version, signer));
    }

    private PackageInfo info(String name, int version, Signature... signers) {
        PackageInfo result = new PackageInfo();
        result.packageName = name;
        result.versionCode = version;
        result.applicationInfo = new ApplicationInfo();
        result.applicationInfo.packageName = name;
        result.applicationInfo.targetSdkVersion = 29;
        result.signatures = signers;
        result.signingInfo = new SigningInfo();
        ((ShadowSigningInfo) Shadow.extract(result.signingInfo)).setSignatures(signers);
        return result;
    }

    private static void resetProcessState() throws Exception {
        Field owned = AppUpdateInstaller.class.getDeclaredField("ownedSessionId");
        owned.setAccessible(true);owned.setInt(null, -1);
        Field pending = AppUpdateInstaller.class.getDeclaredField("pendingAction");
        pending.setAccessible(true);pending.set(null, null);
    }

    private interface Operation { void run() throws IOException; }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run();fail("An invalid update was accepted"); }
        catch (IOException expected) { }
    }

    @Implements(PackageInstaller.Session.class)
    public static class FailingSession extends ShadowPackageInstaller.ShadowSession {
        @Implementation protected void fsync(OutputStream output) throws IOException {
            throw new IOException("Controlled storage failure");
        }
    }
}
