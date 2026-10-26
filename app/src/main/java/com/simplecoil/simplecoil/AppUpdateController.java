package com.simplecoil.simplecoil;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Checks releases without blocking game startup; only an idle foreground may install. */
final class AppUpdateController implements AutoCloseable {
    static final String CACHE_PREFS = "AppUpdateCache";
    private static final long RECHECK_AFTER_MS = 60_000;
    private static final long DRAIN_AFTER_MS = 2_000;
    private static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "app-update");
        thread.setDaemon(true);
        return thread;
    });

    interface Internet {
        boolean available();
        void start(Runnable changed);
        void stop();
    }

    interface Source {
        ReleaseUpdate.Asset latest() throws IOException;
        void download(ReleaseUpdate.Asset asset, File destination) throws IOException;
    }

    interface Installation {
        long inspect(File apk) throws IOException;
        boolean needsUnknownSourcePermission();
        void begin(File apk, BooleanSupplier stillAllowed) throws IOException;
        Intent takePendingAction();
        String status();
        String error();
        boolean isInstalling();
        boolean canInstallSilently();
        void setListener(Runnable listener);
        void clearListener(Runnable listener);
    }

    interface Ui {
        void requestInstallPermission();
        void confirmInstall(Intent action);
        void installFailed();
    }

    private final long installedCode;
    private final BooleanSupplier eligible;
    private final Internet internet;
    private final Source source;
    private final Installation installer;
    private final Ui ui;
    private final Executor worker;
    private final File apk;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile boolean resumed;
    private boolean checked;
    private boolean checking;
    private boolean beginning;
    private boolean attempted;
    private boolean permissionRequested;
    private boolean permissionWaiting;
    private boolean permissionDenied;
    private boolean wasOnline;
    private long pausedAt = -1;
    private long candidateCode = -1;
    private String shownFailure;
    private ReleaseUpdate.Asset pendingAsset;
    private Intent pendingConfirmation;
    private FutureTask<Void> ownWork;
    private final Runnable installationListener = () -> post(() -> {
        drain();
        // A destroyed activity may have been copying the APK when this
        // controller resumed. Its final gate can abandon that older session.
        // Start this launch's deferred lookup when the session becomes idle.
        check();
    });
    private final Runnable networkListener = () -> post(this::networkChanged);
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!closed && resumed) drain();
        }
    };

    AppUpdateController(Activity activity, BooleanSupplier eligible) {
        this(activity, BuildConfig.DEBUG ? -1 : installedVersion(activity), eligible, new AndroidInternet(activity),
                new Source() {
                    private final ReleaseUpdate releases = new ReleaseUpdate();
                    @Override public ReleaseUpdate.Asset latest() throws IOException {
                        return releases.fetchLatest();
                    }
                    @Override public void download(ReleaseUpdate.Asset asset, File file) throws IOException {
                        releases.download(asset, file);
                    }
                }, installation(activity), new AndroidUi(activity), WORKER);
    }

    AppUpdateController(Context context, long installedCode, BooleanSupplier eligible,
                        Internet internet, Source source, Installation installer, Ui ui, Executor worker) {
        this.installedCode = installedCode;
        this.eligible = eligible;
        this.internet = internet;
        this.source = source;
        this.installer = installer;
        this.ui = ui;
        this.worker = worker;
        Context application = context.getApplicationContext();
        cache = application.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        apk = new File(new File(application.getNoBackupFilesDir(), "updates"), "latest.apk");
        installer.setListener(installationListener);
    }

    boolean canInstallSilently() { return installer.canInstallSilently(); }

    void onResume() {
        if (closed) return;
        boolean returnedFromPermission = permissionWaiting;
        resumed = true;
        if (returnedFromPermission) {
            permissionWaiting = false;
            permissionDenied = installer.needsUnknownSourcePermission();
        } else if (pausedAt >= 0 && SystemClock.elapsedRealtime() - pausedAt >= RECHECK_AFTER_MS
                && !installer.isInstalling() && !beginning) {
            checked = false;
            attempted = false;
            permissionRequested = false;
            permissionDenied = false;
            shownFailure = null;
        }
        pausedAt = -1;
        wasOnline = online();
        try { internet.start(networkListener); }
        catch (RuntimeException unavailable) { /* The normal resume check can still run. */ }
        if (!returnedFromPermission) check();
        drain();
    }

    void onPause() {
        if (closed) return;
        resumed = false;
        pausedAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(pulse);
        try { internet.stop(); }
        catch (RuntimeException unavailable) { /* Connectivity is optional. */ }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        resumed = false;
        installer.clearListener(installationListener);
        try { internet.stop(); }
        catch (RuntimeException unavailable) { /* The observer may already be stopped. */ }
        main.removeCallbacksAndMessages(null);
        if (ownWork != null) ownWork.cancel(true);
    }

    private void networkChanged() {
        if (closed || !resumed) return;
        boolean available = online();
        if (available && !wasOnline) checked = false;
        wasOnline = available;
        check();
        drain();
    }

    private boolean online() {
        try { return internet.available(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void check() {
        if (closed || !resumed || checked || checking || beginning || attempted
                || installer.isInstalling() || !online() || installedCode < 0) return;
        checked = true;
        checking = true;
        // A fresh foreground must consult current release metadata before
        // retrying an older candidate retained by this same activity.
        candidateCode = -1;
        pendingAsset = null;
        execute(() -> {
            long verified = -1;
            ReleaseUpdate.Asset toPrepare = null;
            try {
                ReleaseUpdate.Asset asset = source.latest();
                if (asset != null && !closed) {
                    String key = asset.cacheKey();
                    String knownKey = cache.getString("asset", "");
                    long knownCode = cache.getLong("code", -1);
                    if (key.equals(knownKey) && knownCode >= 0 && knownCode <= installedCode) {
                        verified = knownCode;
                    } else {
                        toPrepare = asset;
                    }
                }
            } catch (IOException | RuntimeException unavailable) {
                // Internet, TLS, metadata and archive failures never hold the lobby open.
                verified = -1;
            }
            final long result = verified;
            final ReleaseUpdate.Asset next = toPrepare;
            post(() -> {
                checking = false;
                pendingAsset = next;
                if (ReleaseUpdate.isStrictlyNewer(result, installedCode)) candidateCode = result;
                // Connectivity may have returned while this older request was
                // still running. Its checked=false retry must survive completion.
                check();
                drain();
            });
        }, () -> checking = false);
    }

    private void prepareIfReady() {
        if (pendingAsset == null || checking || beginning || attempted || !allowed()) return;
        ReleaseUpdate.Asset asset = pendingAsset;
        pendingAsset = null;
        checking = true;
        execute(() -> {
            long verified = -1;
            boolean deferred = false;
            try {
                // A game can start after the metadata callback queued this job.
                // Check again before taking bandwidth from its live Wi-Fi session.
                if (!freshAllowed()) {
                    deferred = true;
                } else {
                    String key = asset.cacheKey();
                    boolean reusable = key.equals(cache.getString("asset", "")) && apk.isFile()
                            && apk.length() == asset.size;
                    if (reusable) {
                        try { verified = installer.inspect(apk); }
                        catch (IOException | RuntimeException invalidCache) { reusable = false; }
                    }
                    if (!reusable) {
                        File directory = apk.getParentFile();
                        if (!directory.isDirectory() && !directory.mkdirs())
                            throw new IOException("Cannot prepare the private update directory");
                        source.download(asset, apk);
                        if (closed || Thread.currentThread().isInterrupted()) return;
                        verified = installer.inspect(apk);
                    }
                    if (verified < 0) throw new IOException("Invalid update archive version");
                    if (!closed) cache.edit().putString("asset", key).putLong("code", verified).commit();
                }
            } catch (IOException | RuntimeException unavailable) {
                // A failed download or archive verification never opens installation UI.
            }
            final long result = verified;
            final boolean retryWhenIdle = deferred;
            post(() -> {
                checking = false;
                if (retryWhenIdle) pendingAsset = asset;
                if (ReleaseUpdate.isStrictlyNewer(result, installedCode)) candidateCode = result;
                check();
                drain();
            });
        }, () -> { checking = false;pendingAsset = asset; });
    }

    private void drain() {
        if (closed || !resumed) return;
        String failure = "installer:" + installer.error();
        if ("failure".equals(installer.status()) && allowed()
                && !failure.equals(shownFailure)) {
            showFailure(failure);
        }
        if (allowed()) {
            if (pendingConfirmation == null) pendingConfirmation = installer.takePendingAction();
            if (pendingConfirmation != null) {
                try {
                    ui.confirmInstall(pendingConfirmation);
                    pendingConfirmation = null;
                } catch (UiDeferred blocked) {
                    // Retain Android's confirmation until the organizer leaves lock task.
                } catch (RuntimeException unavailable) {
                    pendingConfirmation = null;
                    ui.installFailed();
                }
            }
            prepareIfReady();
            beginIfReady();
        }
        main.removeCallbacks(pulse);
        if (!closed && resumed && (pendingConfirmation != null || installer.isInstalling()
                || (pendingAsset != null || candidateCode > installedCode) && !attempted && !permissionDenied))
            main.postDelayed(pulse, DRAIN_AFTER_MS);
    }

    private void beginIfReady() {
        if (candidateCode <= installedCode || checking || beginning || attempted
                || installer.isInstalling() || permissionWaiting || permissionDenied || !allowed()) return;
        if (installer.needsUnknownSourcePermission()) {
            if (!permissionRequested) {
                permissionRequested = true;
                permissionWaiting = true;
                try { ui.requestInstallPermission(); }
                catch (UiDeferred blocked) { permissionRequested = permissionWaiting = false; }
                catch (RuntimeException unavailable) {
                    permissionWaiting = false;
                    permissionDenied = true;
                }
            }
            return;
        }
        beginning = true;
        attempted = true;
        execute(() -> {
            boolean failed = false;
            try {
                if (!ReleaseUpdate.isStrictlyNewer(installer.inspect(apk), installedCode))
                    throw new IOException("The verified update is no longer newer");
                if (!closed) installer.begin(apk, this::freshAllowed);
            } catch (IOException | RuntimeException failure) {
                failed = true;
            }
            final boolean didFail = failed;
            post(() -> {
                beginning = false;
                if (didFail) {
                    if (allowed()) showFailure("failure".equals(installer.status())
                            ? "installer:" + installer.error() : "begin");
                } else if (!installer.isInstalling() && "idle".equals(installer.status())) {
                    attempted = false; // The final main-thread gate deferred the session.
                }
                drain();
            });
        }, () -> { beginning = false;attempted = false; });
    }

    private void showFailure(String failure) {
        if (failure.equals(shownFailure)) return;
        shownFailure = failure;
        ui.installFailed();
    }

    private boolean allowed() {
        if (closed || !resumed) return false;
        try { return eligible.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** PackageInstaller calls this worker-side after copying the APK, just before commit. */
    private boolean freshAllowed() {
        if (closed || !resumed) return false;
        if (Looper.myLooper() == main.getLooper()) return allowed();
        FutureTask<Boolean> check = new FutureTask<>(this::allowed);
        if (!main.post(check)) return false;
        try { return check.get(1, TimeUnit.SECONDS); }
        catch (Exception interruptedOrBusy) {
            check.cancel(false);
            if (interruptedOrBusy instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private void post(Runnable task) {
        if (!closed) main.post(() -> { if (!closed) task.run(); });
    }

    private void execute(Runnable work, Runnable rejected) {
        ownWork = new FutureTask<>(work, null);
        try { worker.execute(ownWork); }
        catch (RuntimeException stopped) { rejected.run(); }
    }

    private static long installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return -1;
        }
    }

    private static Installation installation(Context context) {
        AppUpdateInstaller delegate = new AppUpdateInstaller(context);
        return new Installation() {
            @Override public long inspect(File file) throws IOException { return delegate.inspect(file); }
            @Override public boolean needsUnknownSourcePermission() { return delegate.needsUnknownSourcePermission(); }
            @Override public void begin(File file, BooleanSupplier allowed) throws IOException { delegate.begin(file, allowed); }
            @Override public Intent takePendingAction() { return delegate.takePendingAction(); }
            @Override public String status() { return delegate.status(); }
            @Override public String error() { return delegate.error(); }
            @Override public boolean isInstalling() { return delegate.isInstalling(); }
            @Override public boolean canInstallSilently() { return delegate.canInstallSilently(); }
            @Override public void setListener(Runnable listener) { AppUpdateInstaller.setListener(listener); }
            @Override public void clearListener(Runnable listener) { AppUpdateInstaller.clearListener(listener); }
        };
    }

    private static final class UiDeferred extends RuntimeException { }

    private static final class AndroidUi implements Ui {
        private final Activity activity;
        AndroidUi(Activity activity) { this.activity = activity; }

        private void requireExternalUi() {
            if (activity.isFinishing() || activity.isDestroyed()) throw new UiDeferred();
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && (Build.VERSION.SDK_INT >= 23
                    ? manager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
                    : manager.isInLockTaskMode())) throw new UiDeferred();
        }

        @Override public void requestInstallPermission() {
            requireExternalUi();
            if (Build.VERSION.SDK_INT < 26) return;
            Toast.makeText(activity, R.string.app_update_permission_hint, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        }

        @Override public void confirmInstall(Intent action) {
            requireExternalUi();
            activity.startActivity(new Intent(action));
        }

        @Override public void installFailed() {
            if (!activity.isFinishing() && !activity.isDestroyed())
                Toast.makeText(activity, R.string.app_update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static final class AndroidInternet implements Internet {
        private final ConnectivityManager manager;
        private ConnectivityManager.NetworkCallback callback;
        AndroidInternet(Context context) {
            manager = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
        }

        @Override public boolean available() {
            if (manager == null) return false;
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    Network network = manager.getActiveNetwork();
                    NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
                    return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
                NetworkInfo active = manager.getActiveNetworkInfo();
                return active != null && active.isConnected();
            } catch (RuntimeException unavailable) { return false; }
        }

        @Override public void start(Runnable changed) {
            if (manager == null || callback != null) return;
            ConnectivityManager.NetworkCallback next = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { changed.run(); }
                @Override public void onLost(Network network) { changed.run(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { changed.run(); }
            };
            try {
                manager.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), next);
                callback = next;
            } catch (RuntimeException unavailable) { /* Foreground resume remains a retry point. */ }
        }

        @Override public void stop() {
            ConnectivityManager.NetworkCallback previous = callback;
            callback = null;
            if (manager != null && previous != null) {
                try { manager.unregisterNetworkCallback(previous); }
                catch (RuntimeException alreadyStopped) { /* No observer is left for this controller. */ }
            }
        }
    }
}
