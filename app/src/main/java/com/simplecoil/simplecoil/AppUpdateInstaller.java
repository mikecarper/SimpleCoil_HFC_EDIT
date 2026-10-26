package com.simplecoil.simplecoil;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Verifies private update APKs and hands installation to Android, including its confirmation UI. */
final class AppUpdateInstaller {
    private static final String PREFS = "AppUpdateInstall";
    private static final String SESSION = "session";
    private static final String STATE = "state";
    private static final String ERROR = "error";
    private static final Object LOCK = new Object();
    private static int ownedSessionId = -1;
    private static Intent pendingAction;
    private static Runnable listener;
    private final Context context;
    private final SharedPreferences prefs;

    AppUpdateInstaller(Context context) {
        this.context = context.getApplicationContext();
        prefs = preferences(this.context);
        recoverInterruptedSession();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Older/equal signed archives are valid inspection results, but begin() will not install them. */
    long inspect(File apk) throws IOException {
        if (apk == null || !apk.isFile() || apk.length() == 0)
            throw new IOException("The update APK is missing or empty");
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        try {
            PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null || !context.getPackageName().equals(archive.packageName))
                throw new IOException("The update APK belongs to a different application");
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            if (!signers(installed).equals(signers(archive)))
                throw new IOException("The update APK has a different signing certificate");
            long version = version(archive);
            if (version < 0) throw new IOException("The update APK has an invalid version");
            return version;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            throw new IOException("Cannot verify the update APK", failure);
        }
    }

    private static Set<Signature> signers(PackageInfo info) throws IOException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28)
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        else signatures = info.signatures;
        if (signatures == null || signatures.length == 0)
            throw new IOException("The application signing certificate is missing");
        Set<Signature> result = new HashSet<>(Arrays.asList(signatures));
        if (result.contains(null)) throw new IOException("The application signing certificate is invalid");
        return result;
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    boolean needsUnknownSourcePermission() {
        if (Build.VERSION.SDK_INT < 26) return false;
        if (canInstallSilently()) return false;
        return !context.getPackageManager().canRequestPackageInstalls();
    }

    boolean canInstallSilently() {
        if (Build.VERSION.SDK_INT < 23) return false;
        DevicePolicyManager policy = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return policy != null && policy.isDeviceOwnerApp(context.getPackageName());
    }

    /** Called on a worker only after the controller has selected an idle foreground install. */
    void begin(File apk) throws IOException {
        begin(apk, () -> true);
    }

    void begin(File apk, BooleanSupplier stillAllowed) throws IOException {
        long archiveVersion = inspect(apk);
        try {
            if (archiveVersion <= version(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)))
                throw new IOException("The update APK is not newer than the installed application");
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("Cannot read the installed application version", failure);
        }
        if (needsUnknownSourcePermission())
            throw new IOException("Android has not allowed this application to install updates");

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        boolean committed = false;
        boolean deferred = false;
        try {
            synchronized (LOCK) {
                if (prefs.getInt(SESSION, -1) != -1)
                    throw new IOException("An update installation is already in progress");
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                sessionId = installer.createSession(params);
                // Persist before commit: Android may finish by replacing this running process.
                if (!prefs.edit().putInt(SESSION, sessionId).putString(STATE, "installing")
                        .remove(ERROR).commit())
                    throw new IOException("Cannot save the update installation state");
                ownedSessionId = sessionId;
                pendingAction = null;
            }
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (FileInputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    session.fsync(output);
                }
                Intent result = new Intent(context, AppUpdateInstallReceiver.class)
                        .setAction(context.getPackageName() + ".UPDATE_INSTALL." + sessionId);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent receiver = PendingIntent.getBroadcast(context, sessionId, result, flags);
                // Copying an APK takes time on the event phones. Recheck the game
                // immediately before the irreversible handoff to Android.
                if (stillAllowed == null || !stillAllowed.getAsBoolean()) {
                    deferred = true;
                    return;
                }
                session.commit(receiver.getIntentSender());
                committed = true;
            }
            notifyListener();
        } catch (RuntimeException failure) {
            throw new IOException("Android could not start the update installation", failure);
        } finally {
            if (!committed && sessionId != -1) {
                try { installer.abandonSession(sessionId); }
                catch (RuntimeException ignored) { }
                synchronized (LOCK) {
                    if (prefs.getInt(SESSION, -1) == sessionId) {
                        SharedPreferences.Editor state = prefs.edit().remove(SESSION)
                                .putString(STATE, deferred ? "idle" : "failure");
                        if (deferred) state.remove(ERROR);
                        else state.putString(ERROR, "The update installation could not be started");
                        state.commit();
                        ownedSessionId = -1;
                        pendingAction = null;
                    }
                }
                notifyListener();
            }
        }
    }

    private void recoverInterruptedSession() {
        synchronized (LOCK) {
            int sessionId = prefs.getInt(SESSION, -1);
            if (sessionId == -1 || ownedSessionId == sessionId) return;
            // A confirmation Intent is deliberately not persisted. Retire only our own
            // orphaned session, then let the controller re-verify and retry its cached APK.
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try {
                for (PackageInstaller.SessionInfo session : installer.getMySessions())
                    if (session.getSessionId() == sessionId) installer.abandonSession(sessionId);
            } catch (RuntimeException failure) {
                prefs.edit().putString(ERROR, "Cannot recover the previous update installation").commit();
                return;
            }
            prefs.edit().remove(SESSION).putString(STATE, "interrupted").remove(ERROR).commit();
            ownedSessionId = -1;
            pendingAction = null;
        }
    }

    Intent takePendingAction() {
        synchronized (LOCK) {
            if (ownedSessionId != prefs.getInt(SESSION, -1) || pendingAction == null) return null;
            Intent result = new Intent(pendingAction);
            pendingAction = null;
            return result;
        }
    }

    String status() { return prefs.getString(STATE, "idle"); }
    String error() { return prefs.getString(ERROR, ""); }
    boolean isInstalling() { return prefs.getInt(SESSION, -1) != -1; }

    static void setListener(Runnable callback) {
        synchronized (LOCK) { listener = callback; }
    }

    static void clearListener(Runnable callback) {
        synchronized (LOCK) { if (listener == callback) listener = null; }
    }

    private static void notifyListener() {
        final Runnable callback;
        synchronized (LOCK) { callback = listener; }
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (LOCK) { if (listener != callback) return; }
            callback.run();
        });
    }

    static void onInstallStatus(Context context, Intent event) {
        if (event == null || !event.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
        SharedPreferences prefs = preferences(context);
        synchronized (LOCK) {
            int sessionId = prefs.getInt(SESSION, -1);
            if (sessionId == -1 || event.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId)
                return;
            int status = event.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent action = event.getParcelableExtra(Intent.EXTRA_INTENT);
                if (action != null) {
                    ownedSessionId = sessionId;
                    pendingAction = new Intent(action);
                    prefs.edit().putString(STATE, "confirmation").remove(ERROR).commit();
                } else {
                    finishStatus(prefs, "failure", "Android did not supply its installation confirmation");
                    try { context.getPackageManager().getPackageInstaller().abandonSession(sessionId); }
                    catch (RuntimeException ignored) { }
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                finishStatus(prefs, "success", "");
            } else if (status >= PackageInstaller.STATUS_FAILURE) {
                String message = event.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                finishStatus(prefs, "failure", message == null ? "Android could not install the update" : message);
            } else return;
        }
        notifyListener();
    }

    private static void finishStatus(SharedPreferences prefs, String state, String error) {
        prefs.edit().remove(SESSION).putString(STATE, state).putString(ERROR, error).commit();
        ownedSessionId = -1;
        pendingAction = null;
    }
}
