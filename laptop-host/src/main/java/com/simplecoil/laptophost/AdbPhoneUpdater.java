package com.simplecoil.laptophost;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.Set;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit USB enrollment and serialized, verified updates. Never scans network addresses. */
final class AdbPhoneUpdater implements AutoCloseable {
    static final String PACKAGE = "com.simplecoil.simplecoil";
    static final String RELEASE_CERT = "032dff6972b3ea091868c1dc76b4fa0a79d4e62d6b08e5fbd831e589535c6cb3";
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final int TEXT_LIMIT = 256 * 1024;
    private static final Pattern VERSION = Pattern.compile("\\bversionCode=([0-9]+)\\b");
    private static final Pattern ADDRESS = Pattern.compile("(?:^|\\s)inet\\s+([0-9.]+)/[0-9]+(?:\\s|$)");
    private static final Pattern APK_PATH = Pattern.compile("/data/app/(?:~~[A-Za-z0-9_+=-]+/)?"
            + "com\\.simplecoil\\.simplecoil-[A-Za-z0-9_+=-]+(?:/base)?\\.apk");
    private final Path stateFile;
    private final BooleanSupplier hostIdle;
    private final Supplier<ReleaseApk> releaseSupplier;
    private final PhoneSafety phoneSafety;
    private final CommandRunner commands;
    private final ApkInspector inspector;
    private final LongSupplier now;
    private final String adb;
    private final boolean operational;
    private final Object lock = new Object();
    private final Map<String, Phone> phones = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> usbPhones = new LinkedHashMap<>();
    private final ExecutorService work = Executors.newSingleThreadExecutor(task -> daemon(task, "Phone ADB update"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            task -> daemon(task, "Phone ADB polling"));
    private volatile boolean closed, enabled, busy, available;
    private volatile String message = "Enable updates, then enroll each USB-authorized game phone.";
    private volatile String adbVersion = "";
    private volatile ApkInfo cachedInfo;
    private ReleaseApk inspectedRelease;
    private volatile Set<String> enrolledAddresses = Set.of();
    private volatile PendingUpdate pending;
    private boolean started;

    record ReleaseApk(Path apk, long size, String sha256) { }
    record ApkInfo(String packageName, int versionCode, String version, String certificateSha256) { }
    record CommandResult(int exitCode, byte[] stdout, String stderr) {
        String text() { return new String(stdout, StandardCharsets.UTF_8).trim(); }
    }
    /** Must be fresh, fail closed, and include this phone's own idle/update permission. */
    interface PhoneSafety { Lease acquire(String serial, String numericAddress) throws IOException; }
    interface Lease extends AutoCloseable {
        String hostAddress();
        boolean stillAllowed();
        @Override void close();
    }
    interface CommandRunner {
        CommandResult run(List<String> command, long timeoutMillis, int maxOutput,
                          BooleanSupplier allowed) throws IOException;
    }
    interface ApkInspector { ApkInfo inspect(Path apk) throws IOException; }

    AdbPhoneUpdater(Path stateFile, BooleanSupplier hostIdle, Supplier<ReleaseApk> releaseSupplier,
                    PhoneSafety phoneSafety) {
        this(stateFile, hostIdle, releaseSupplier, phoneSafety, new ProcessCommands(),
                AdbPhoneUpdater::inspectApk, System::currentTimeMillis,
                System.getProperty("simplecoil.adb", "adb"));
    }

    AdbPhoneUpdater(Path stateFile, BooleanSupplier hostIdle, Supplier<ReleaseApk> releaseSupplier,
                    PhoneSafety phoneSafety, CommandRunner commands, ApkInspector inspector,
                    LongSupplier now, String adb) {
        this.stateFile = stateFile;this.hostIdle = hostIdle;this.releaseSupplier = releaseSupplier;
        this.phoneSafety = phoneSafety;this.commands = commands;this.inspector = inspector;
        this.now = now;this.adb = adb;
        this.operational = Boolean.parseBoolean(System.getProperty("simplecoil.adb.updates.enabled", "true"));
        if (operational) load();
    }

    void start() {
        synchronized (lock) {
            if (started || closed || !operational) return;
            started = true;
            timer.scheduleWithFixedDelay(this::queuePoll, 0, 15, TimeUnit.SECONDS);
        }
    }

    boolean setEnabled(boolean value) {
        synchronized (lock) {
            if (closed || !operational || (busy || pending != null) && value) return false;
            boolean previous = enabled;enabled = value;
            try { save(); }
            catch (IOException failed) {
                if (!value) {
                    message = "Automatic updates are disabled for this session, but settings could not be saved.";
                    return true;
                }
                enabled = previous;message = "Could not save phone update settings.";return false;
            }
            message = value ? "Waiting for an idle, enrolled phone and a verified cached update."
                    : "Automatic ADB updates are disabled.";
        }
        queuePoll();return true;
    }

    boolean enrollUsb(String serial) {
        if (!validSerial(serial)) return false;
        synchronized (lock) {
            if (!available || !operational || closed || busy || pending != null || !safeHost()
                    || !usbPhones.containsKey(serial) || !"device".equals(usbPhones.get(serial).get("state"))) return false;
            busy = true;
        }
        work.execute(() -> {
            try { enroll(serial); }
            catch (IOException | RuntimeException failed) { message = safeMessage(failed); }
            finally { busy = false; }
        });
        return true;
    }

    boolean retry(String serial) {
        if (!validSerial(serial)) return false;
        synchronized (lock) {
            Phone phone = phones.get(serial);
            if (phone == null || closed || busy || !enabled && pending == null) return false;
            if (pending != null && !pending.phone().serial.equals(serial)) return false;
            phone.nextAttempt = 0;phone.connectionBackoff = 0;
        }
        queuePoll();return true;
    }

    boolean remove(String serial) {
        synchronized (lock) {
            if (closed || busy || pending != null) return false;
            Phone old = phones.remove(serial);
            if (old == null) return false;
            try { save(); }
            catch (IOException failed) { phones.put(serial, old);message = "Could not save phone update settings.";return false; }
            return true;
        }
    }

    boolean busy() { return busy || pending != null; }

    boolean managesAddress(String address) {
        return operational && enabled && !closed && enrolledAddresses.contains(address);
    }

    Map<String, Object> snapshot() {
        synchronized (lock) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", enabled);result.put("available", available);result.put("busy", busy || pending != null);
            result.put("gameActive", !safeHost());result.put("message", message);result.put("adbVersion", adbVersion);
            ApkInfo info = cachedInfo;
            result.put("cachedUpdate", info == null ? null
                    : Map.of("version", info.version(), "versionCode", info.versionCode()));
            List<Map<String, Object>> enrolled = new ArrayList<>();
            for (Phone phone : phones.values()) enrolled.add(phone.snapshot());
            result.put("phones", enrolled);result.put("usbPhones", new ArrayList<>(usbPhones.values()));
            return result;
        }
    }

    private void queuePoll() {
        synchronized (lock) {
            if (closed || !operational || busy) return;
            busy = true;
        }
        try {
            work.execute(() -> {
                try { poll(); }
                catch (IOException | RuntimeException failed) { message = safeMessage(failed); }
                finally { busy = false; }
            });
        } catch (RuntimeException stopped) { busy = false; }
    }

    private void poll() throws IOException {
        if (closed) return;
        if (pending != null) { reconcilePending();return; }
        if (!safeHost()) return;
        try {
            CommandResult version = run(null, 5000, TEXT_LIMIT, this::safeHost, "version");
            requireSuccess(version, "ADB is unavailable.");
            available = true;adbVersion = firstLine(version.text());
        } catch (IOException missing) {
            available = false;message = "ADB is unavailable. Install Android platform-tools to enable Wi-Fi updates.";return;
        }
        discoverUsb();
        if (!enabled) return;
        ReleaseApk release = releaseSupplier.get();
        ApkInfo info = cachedInfo;
        if (release == null) { info = null;cachedInfo = null;inspectedRelease = null; }
        else if (!release.equals(inspectedRelease)) {
            info = inspectRelease(release);cachedInfo = info;inspectedRelease = release;
        }
        if (release == null) { message = "Waiting for the laptop's verified GitHub APK cache.";return; }
        List<Phone> enrolled;
        synchronized (lock) { enrolled = new ArrayList<>(phones.values()); }
        for (Phone phone : enrolled) {
            if (closed || !enabled || !safeHost()) return;
            if (phone.connectionBackoff > now.getAsLong() || phone.nextAttempt > now.getAsLong()) continue;
            try { update(phone, release, info); }
            catch (IOException | RuntimeException failed) {
                phone.connectionBackoff = now.getAsLong() + RETRY_MS;
                status(phone, "waiting", safeMessage(failed));
            }
        }
    }

    private void discoverUsb() throws IOException {
        CommandResult devices = run(null, 10000, TEXT_LIMIT, this::safeHost, "devices", "-l");
        requireSuccess(devices, "Could not list USB-authorized phones.");
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (String line : devices.text().split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || !validSerial(fields[0])) continue;
            String serial = fields[0];
            if ("unauthorized".equals(fields[1])) {
                found.put(serial, Map.of("serial", serial, "state", "unauthorized", "label", "USB device awaiting authorization"));
                if (found.size() >= 64) break;
                continue;
            }
            if (!"device".equals(fields[1])) continue;
            CommandResult path = run(serial, 5000, TEXT_LIMIT, this::safeHost, "get-devpath");
            if (path.exitCode() != 0 || !path.text().startsWith("usb:")) continue;
            if (packagePath(serial, this::safeHost) == null) continue;
            found.put(serial, Map.of("serial", serial, "state", "device", "label", serial));
            if (found.size() >= 64) break;
        }
        synchronized (lock) { usbPhones.clear();usbPhones.putAll(found); }
    }

    private void enroll(String serial) throws IOException {
        checkHost();
        CommandResult path = run(serial, 5000, TEXT_LIMIT, this::safeHost, "get-devpath");
        if (path.exitCode() != 0 || !path.text().startsWith("usb:") || packagePath(serial, this::safeHost) == null)
            throw new IOException("Connect and authorize this game phone by USB before enrollment.");
        String identity = getProperty(serial, "ro.serialno", this::safeHost);
        if (!serial.equals(identity)) throw new IOException("The USB phone identity could not be verified.");
        CommandResult addresses = run(serial, 10000, TEXT_LIMIT, this::safeHost,
                "shell", "ip", "-f", "inet", "addr", "show", "wlan0");
        requireSuccess(addresses, "Could not read the phone's Wi-Fi address.");
        Matcher match = ADDRESS.matcher(addresses.text());String address = null;
        while (match.find()) {
            String candidate = match.group(1);
            if (!validAddress(candidate)) continue;
            if (address != null && !address.equals(candidate)) throw new IOException("The phone has multiple Wi-Fi addresses.");
            address = candidate;
        }
        if (address == null) throw new IOException("Connect the phone to the private game Wi-Fi first.");
        Phone phone = new Phone(serial, address);
        synchronized (lock) {
            if (!phones.containsKey(serial) && phones.size() >= 64) throw new IOException("At most 64 phones can be enrolled.");
            for (Phone existing : phones.values()) if (!existing.serial.equals(serial) && existing.address.equals(phone.address))
                throw new IOException("Another enrolled phone already uses this address.");
        }
        try (Lease lease = phoneSafety.acquire(serial, address)) {
            if (lease == null) throw new IOException("Join this laptop's idle lobby before enrollment.");
            BooleanSupplier allowed = () -> safeHost() && lease.stillAllowed();
            requireSuccess(run(serial, 15000, TEXT_LIMIT, allowed, "tcpip", "5555"), "Could not enable Wi-Fi ADB.");
            confirmEnrollmentConnection(phone, allowed);
            check(allowed);
        }
        synchronized (lock) {
            for (Phone existing : phones.values()) if (!existing.serial.equals(serial) && existing.address.equals(phone.address))
                throw new IOException("Another enrolled phone already uses this address.");
            Phone previous = phones.put(serial, phone);
            try { save(); }
            catch (IOException failed) {
                if (previous == null) phones.remove(serial);else phones.put(serial, previous);
                throw new IOException("Could not save phone enrollment.", failed);
            }
        }
        status(phone, "enrolled", "Wi-Fi ADB connection and phone identity verified. USB can be unplugged.");
        message = "Phone enrolled. Wi-Fi ADB may need USB enrollment again after a reboot.";
    }

    private void confirmEnrollmentConnection(Phone phone, BooleanSupplier allowed) throws IOException {
        String endpoint = phone.address + ":5555";long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            check(allowed);
            try {
                CommandResult connection = run(null, 5000, TEXT_LIMIT, allowed, "connect", endpoint);
                requireSuccess(connection, "Wi-Fi ADB is not ready yet.");
                CommandResult identity = run(endpoint, 5000, TEXT_LIMIT, allowed, "shell", "getprop", "ro.serialno");
                requireSuccess(identity, "Wi-Fi ADB is not ready yet.");
                if (!phone.serial.equals(identity.text())) throw new PhoneIdentityException();
                if (packagePath(endpoint, allowed) == null) throw new PhoneIdentityException();
                return;
            } catch (PhoneIdentityException wrong) {
                throw new IOException("Wi-Fi ADB reached a different phone or the expected game is missing.");
            } catch (IOException unavailable) { last = unavailable; }
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException("Enrollment interrupted.", interrupted); }
        }
        throw new IOException("Wi-Fi ADB did not become ready. Keep USB connected and try enrollment again.", last);
    }

    private static final class PhoneIdentityException extends IOException { }

    private void update(Phone phone, ReleaseApk release, ApkInfo info) throws IOException {
        Lease acquired = phoneSafety.acquire(phone.serial, phone.address);
        if (acquired == null) { status(phone, "waiting", "Join this laptop's idle lobby to permit updates.");return; }
        try { updateWithLease(phone, release, info, acquired); }
        catch (UncertainInstallException uncertain) {
            pending = uncertain.pending;
            status(phone, "uncertain", "Installation is unconfirmed. Reconnect this phone for verification; new games stay blocked.");
            message = "Waiting to verify " + phone.serial + ". Reconnect it before starting another game.";
        } finally {
            boolean shouldRelease;
            synchronized (lock) {
                PendingUpdate unresolved = pending;
                shouldRelease = unresolved == null || unresolved.lease() != acquired || closed;
                if (closed && unresolved != null && unresolved.lease() == acquired) pending = null;
            }
            if (shouldRelease) acquired.close();
        }
    }

    private void updateWithLease(Phone phone, ReleaseApk release, ApkInfo info, Lease lease) throws IOException {
        AtomicBoolean installing = new AtomicBoolean();
        BooleanSupplier allowed = () -> safeHost() && lease.stillAllowed() && (enabled || installing.get());
        if (!validAddress(lease.hostAddress())) throw new IOException("The laptop's private Wi-Fi address is unavailable.");
        check(allowed);
        status(phone, "connecting", "Connecting only to the enrolled Wi-Fi address.");
        String endpoint = phone.address + ":5555";
        requireSuccess(run(null, 15000, TEXT_LIMIT, allowed, "connect", endpoint), "Could not connect to enrolled Wi-Fi ADB.");
        String identity = getProperty(endpoint, "ro.serialno", allowed);
        if (!phone.serial.equals(identity)) throw new IOException("Wi-Fi address belongs to a different phone. Re-enroll by USB.");
        phone.nextAttempt = now.getAsLong() + RETRY_MS;
        String installedPath = packagePath(endpoint, allowed);
        if (installedPath == null) throw new IOException("The enrolled phone no longer has the game installed.");
        int oldCode = installedCode(endpoint, allowed);
        synchronized (lock) { phone.installedVersion = Integer.toString(oldCode); }
        if (oldCode >= info.versionCode()) {
            status(phone, "current", oldCode == info.versionCode() ? "Already on the cached release." : "A newer version is already installed.");
            return;
        }
        phonePreflight(endpoint, lease.hostAddress(), oldCode, allowed);
        status(phone, "checking", "Verifying the installed game's signing certificate.");
        CommandResult previousResult = run(endpoint, 120000, (int) MAX_APK, allowed, "exec-out", "cat", installedPath);
        requireSuccess(previousResult, "Could not verify the installed game's signing certificate.");
        byte[] previous = previousResult.stdout();
        Path temporary = Files.createTempFile(stateFile.toAbsolutePath().getParent(), ".phone-apk-", ".apk");
        try {
            Files.write(temporary, previous);ApkInfo installed = inspector.inspect(temporary);
            if (!PACKAGE.equals(installed.packageName()) || !RELEASE_CERT.equals(installed.certificateSha256())
                    || installed.versionCode() != oldCode)
                throw new IOException("Installed game package or signing certificate is not the expected release.");
        } finally { Files.deleteIfExists(temporary); }
        check(allowed);
        // Copy the immutable cache into our own verified file so cache eviction cannot race ADB.
        Path prepared = Files.createTempFile(stateFile.toAbsolutePath().getParent(), ".phone-install-", ".apk");
        String nonce = UUID.randomUUID().toString().replace("-", "");boolean preparedPhone = false;
        String remoteApk = "/data/local/tmp/simplecoil-update-" + nonce + ".apk";
        boolean beganInstall = false, definiteFailure = false, finished = false, uncertain = false;
        try {
            Files.copy(release.apk(), prepared, StandardCopyOption.REPLACE_EXISTING);
            ReleaseApk privateRelease = new ReleaseApk(prepared, release.size(), release.sha256());
            ApkInfo ready = inspectRelease(privateRelease);
            if (!ready.equals(info)) throw new IOException("The cached update changed while checking the phone.");
            status(phone, "staging", "Sending the verified APK before asking the phone to enter update maintenance.");
            requireSuccess(run(endpoint, 120000, TEXT_LIMIT, allowed, "push", prepared.toAbsolutePath().toString(), remoteApk),
                    "Could not stage the update on the enrolled phone.");
            preparedPhone = true;
            phoneHandshake(endpoint, "PREPARE", nonce, lease.hostAddress(), oldCode, allowed);
            phoneHandshake(endpoint, "STATUS", nonce, lease.hostAddress(), oldCode, allowed);
            check(allowed);installing.set(true);
            status(phone, "installing", "Installing the verified update without clearing game data.");
            beganInstall = true;
            CommandResult installed = run(endpoint, 120000, TEXT_LIMIT, allowed,
                    "shell", "pm", "install", "-r", remoteApk);
            if (installed.text().lines().anyMatch(line -> line.startsWith("Failure [") && line.endsWith("]"))) {
                definiteFailure = true;throw new IOException("Android rejected the update; game data was preserved.");
            }
            requireSuccess(installed, "ADB update installation failed.");
            if (!Arrays.asList(installed.text().split("\\R")).contains("Success"))
                throw new IOException("Android did not report a successful installation.");
            status(phone, "verifying", "Verifying the installed version and exact public APK bytes.");
            if (!phone.serial.equals(getProperty(endpoint, "ro.serialno", allowed)))
                throw new IOException("Phone identity changed during installation.");
            String actualPath = packagePath(endpoint, allowed);
            if (actualPath == null || installedCode(endpoint, allowed) != info.versionCode())
                throw new IOException("The installed version did not match the update.");
            CommandResult actual = run(endpoint, 120000, (int) MAX_APK, allowed, "exec-out", "cat", actualPath);
            requireSuccess(actual, "Could not verify the installed APK.");
            if (actual.stdout().length != release.size() || !digest(actual.stdout()).equals(release.sha256()))
                throw new IOException("The installed APK did not match the exact public release.");
            startGame(endpoint, allowed);finished = true;
            synchronized (lock) { phone.installedVersion = info.version(); }
            status(phone, "updated", "Update installed, exact APK verified, and game relaunched.");
            message = "Updated " + phone.serial + " over Wi-Fi ADB.";
        } catch (IOException | RuntimeException failed) {
            if (beganInstall && !definiteFailure && !finished) {
                uncertain = true;
                throw new UncertainInstallException(new PendingUpdate(phone, release, info, lease, nonce, remoteApk), failed);
            }
            throw failed;
        } finally {
            if (!uncertain && preparedPhone) {
                try { releasePhone(endpoint, nonce, lease.hostAddress()); }
                catch (IOException ignored) { /* The old app process was replaced; its bounded lease dies with it. */ }
            }
            if (!uncertain) removeStagedApk(endpoint, remoteApk);
            try { Files.deleteIfExists(prepared); }
            catch (IOException ignored) { /* A local cleanup failure must not lose an unresolved install reservation. */ }
        }
    }

    private record PendingUpdate(Phone phone, ReleaseApk release, ApkInfo info, Lease lease, String nonce, String remoteApk) { }
    private static final class UncertainInstallException extends IOException {
        final PendingUpdate pending;
        UncertainInstallException(PendingUpdate pending, Exception cause) { super("Android install outcome is unconfirmed.", cause);this.pending = pending; }
    }

    private void reconcilePending() {
        PendingUpdate unresolved = pending;if (unresolved == null) return;
        String endpoint = unresolved.phone().address + ":5555";
        BooleanSupplier allowed = () -> safeHost() && unresolved.lease().stillAllowed();
        try {
            requireSuccess(run(null, 15000, TEXT_LIMIT, allowed, "connect", endpoint), "Reconnect the phone to verify its installation.");
            if (!unresolved.phone().serial.equals(getProperty(endpoint, "ro.serialno", allowed)))
                throw new IOException("The enrolled Wi-Fi address belongs to a different phone.");
            String actualPath = packagePath(endpoint, allowed);
            if (actualPath == null || installedCode(endpoint, allowed) != unresolved.info().versionCode())
                throw new IOException("The installation is still unconfirmed. Reconnect the original phone and wait.");
            CommandResult actual = run(endpoint, 120000, (int) MAX_APK, allowed, "exec-out", "cat", actualPath);
            requireSuccess(actual, "Could not verify the installed APK.");
            if (actual.stdout().length != unresolved.release().size()
                    || !digest(actual.stdout()).equals(unresolved.release().sha256()))
                throw new IOException("The installed APK does not match the pending public update.");
            startGame(endpoint, allowed);
            try { releasePhone(endpoint, unresolved.nonce(), unresolved.lease().hostAddress()); }
            catch (IOException ignored) { /* A replaced process has no old maintenance lease. */ }
            removeStagedApk(endpoint, unresolved.remoteApk());
            synchronized (lock) { unresolved.phone().installedVersion = unresolved.info().version(); }
            status(unresolved.phone(), "updated", "Recovered the installation, verified exact APK bytes, and relaunched the game.");
            message = "Verified the completed Wi-Fi update for " + unresolved.phone().serial + ".";
            boolean release;
            synchronized (lock) { release = pending == unresolved;if (release) pending = null; }
            if (release) unresolved.lease().close();
        } catch (IOException | RuntimeException unavailable) {
            status(unresolved.phone(), "uncertain", "Installation unconfirmed. Reconnect this phone; new games remain blocked until verified.");
            message = "Update verification is pending for " + unresolved.phone().serial + "; reconnect it or stop the host for manual recovery.";
        }
    }

    private void startGame(String endpoint, BooleanSupplier allowed) throws IOException {
        CommandResult started = run(endpoint, 15000, TEXT_LIMIT, allowed, "shell", "am", "start", "-n", PACKAGE + "/.FullscreenActivity");
        requireSuccess(started, "The update installed but the game could not be relaunched.");
        if (!started.text().contains("Starting:") || started.text().contains("Error:") || started.text().contains("Exception"))
            throw new IOException("The update installed but Android did not confirm the game launch.");
    }

    private void removeStagedApk(String endpoint, String remoteApk) {
        try { run(endpoint, 10000, TEXT_LIMIT, () -> !Thread.currentThread().isInterrupted(), "shell", "rm", "-f", remoteApk); }
        catch (IOException ignored) { /* Delete only this operation's generated APK; an offline phone may retain it. */ }
    }

    private void phonePreflight(String endpoint, String host, int installedCode, BooleanSupplier allowed) throws IOException {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Map<?, ?> data = handshakeData(broadcast(endpoint, "STATUS", nonce, host, allowed));
        if (!"absent".equals(data.get("status")) || !nonce.equals(data.get("nonce")) || !host.equals(data.get("host"))
                || !PACKAGE.equals(data.get("package")) || !Boolean.TRUE.equals(data.get("eligible"))
                || !(data.get("versionCode") instanceof Number version) || version.intValue() != installedCode)
            throw new IOException("The phone is not currently eligible for this laptop's safe update.");
    }

    private Map<?, ?> handshakeData(CommandResult result) throws IOException {
        requireSuccess(result, "The phone did not confirm safe update permission.");
        String output = result.text();int marker = output.indexOf("SCADB1 ");
        if (marker < 0) throw new IOException("Install version 1.59 once to enable safe automatic Wi-Fi updates on this phone.");
        if (output.indexOf("SCADB1 ", marker + 7) >= 0) throw new IOException("The phone update reply is ambiguous.");
        int start = output.indexOf('{', marker), end = output.lastIndexOf('}');
        try {
            Object parsed = LaptopHost.Json.parse(output.substring(start, end + 1));
            if (!(parsed instanceof Map<?, ?> data) || !(data.get("protocol") instanceof Number protocol)
                    || protocol.intValue() != 1) throw new IOException("The phone update protocol is unsupported.");
            return data;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new IOException("The phone's safe update permission is invalid.", invalid);
        }
    }

    private void phoneHandshake(String endpoint, String action, String nonce, String host, int installedCode,
                                BooleanSupplier allowed) throws IOException {
        CommandResult result = broadcast(endpoint, action, nonce, host, allowed);
        Map<?, ?> data = handshakeData(result);
        if (!Pattern.compile("(?:^|[\\s,])result=-1(?:[,\\s]|$)").matcher(result.text()).find() || !"ready".equals(data.get("status"))
                || !nonce.equals(data.get("nonce")) || !host.equals(data.get("host"))
                || !PACKAGE.equals(data.get("package")) || !(data.get("versionCode") instanceof Number version)
                || version.intValue() != installedCode || !(data.get("expiresInMs") instanceof Number expires)
                || expires.longValue() < 130000 || expires.longValue() > 180000)
            throw new IOException("The phone's safe update permission is invalid or expired.");
    }

    private CommandResult broadcast(String endpoint, String action, String nonce, String host,
                                    BooleanSupplier allowed) throws IOException {
        return run(endpoint, 15000, TEXT_LIMIT, allowed, "shell", "am", "broadcast", "-a",
                PACKAGE + ".ADB_UPDATE_" + action, "-n", PACKAGE + "/.AdbUpdateReceiver",
                "--es", "nonce", nonce, "--es", "host", host);
    }

    private void releasePhone(String endpoint, String nonce, String host) throws IOException {
        // Releasing a maintenance lease is safe even if the host just started shutting down.
        broadcast(endpoint, "RELEASE", nonce, host, () -> !Thread.currentThread().isInterrupted());
    }

    private ApkInfo inspectRelease(ReleaseApk release) throws IOException {
        if (release.apk() == null || release.size() <= 0 || release.size() > MAX_APK
                || release.sha256() == null || !release.sha256().matches("[0-9a-f]{64}")
                || !Files.isRegularFile(release.apk(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(release.apk()) != release.size()) throw new IOException("The cached APK is unavailable or invalid.");
        if (!digest(Files.readAllBytes(release.apk())).equals(release.sha256()))
            throw new IOException("The cached APK failed its public release SHA-256 check.");
        ApkInfo info = inspector.inspect(release.apk());
        if (!PACKAGE.equals(info.packageName()) || !RELEASE_CERT.equals(info.certificateSha256()) || info.versionCode() <= 0)
            throw new IOException("The cached APK has an unexpected package or release signing certificate.");
        return info;
    }

    private int installedCode(String endpoint, BooleanSupplier allowed) throws IOException {
        CommandResult result = run(endpoint, 10000, TEXT_LIMIT, allowed, "shell", "dumpsys", "package", PACKAGE);
        requireSuccess(result, "Could not inspect the installed game version.");
        Matcher found = VERSION.matcher(result.text());
        if (!found.find()) throw new IOException("The installed game version is unavailable.");
        try { int code = Integer.parseInt(found.group(1));if (code > 0) return code; }
        catch (NumberFormatException ignored) { }
        throw new IOException("The installed game version is invalid.");
    }

    private String packagePath(String endpoint, BooleanSupplier allowed) throws IOException {
        CommandResult result = run(endpoint, 10000, TEXT_LIMIT, allowed, "shell", "pm", "path", PACKAGE);
        if (result.exitCode() != 0 || result.text().isEmpty()) return null;
        String text = result.text();
        if (!text.startsWith("package:") || text.indexOf('\n') >= 0) return null;
        String path = text.substring(8).trim();return APK_PATH.matcher(path).matches() ? path : null;
    }

    private String getProperty(String endpoint, String property, BooleanSupplier allowed) throws IOException {
        CommandResult result = run(endpoint, 10000, TEXT_LIMIT, allowed, "shell", "getprop", property);
        requireSuccess(result, "Could not verify the phone identity.");return result.text();
    }

    private CommandResult run(String endpoint, long timeout, int limit, BooleanSupplier allowed,
                              String... arguments) throws IOException {
        check(allowed);List<String> command = new ArrayList<>();command.add(adb);
        if (endpoint != null) { command.add("-s");command.add(endpoint); }
        Collections.addAll(command, arguments);return commands.run(List.copyOf(command), timeout, limit, allowed);
    }

    private boolean safeHost() {
        if (closed) return false;
        try { return hostIdle.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void checkHost() throws IOException { check(this::safeHost); }
    private static void check(BooleanSupplier allowed) throws IOException {
        if (!allowed.getAsBoolean()) throw new IOException("Update deferred because the game or phone is no longer idle.");
    }

    private void status(Phone phone, String state, String text) {
        synchronized (lock) { phone.state = state;phone.message = text;phone.updatedAt = now.getAsLong(); }
    }

    private void load() {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.size(stateFile) > 65536)
                throw new IOException("Invalid enrollment settings");
            Object parsed = LaptopHost.Json.parse(Files.readString(stateFile, StandardCharsets.US_ASCII));
            if (!(parsed instanceof Map<?, ?> data) || !(data.get("phones") instanceof List<?> list) || list.size() > 64)
                throw new IOException("Invalid enrollment settings");
            Map<String, Phone> loaded = new LinkedHashMap<>();List<String> addresses = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("serial") instanceof String serial)
                        || !(entry.get("address") instanceof String address) || !validSerial(serial)
                        || !validAddress(address) || loaded.containsKey(serial) || addresses.contains(address))
                    throw new IOException("Invalid enrollment settings");
                loaded.put(serial, new Phone(serial, address));addresses.add(address);
            }
            enabled = Boolean.TRUE.equals(data.get("enabled"));phones.putAll(loaded);enrolledAddresses = Set.copyOf(addresses);
        } catch (IOException | RuntimeException bad) {
            enabled = false;phones.clear();enrolledAddresses = Set.of();message = "Enrollment settings could not be loaded. Automatic updates remain disabled.";
        }
    }

    private void save() throws IOException {
        Path parent = stateFile.toAbsolutePath().getParent();Files.createDirectories(parent);
        List<Map<String, String>> entries = new ArrayList<>();
        for (Phone phone : phones.values()) entries.add(Map.of("serial", phone.serial, "address", phone.address));
        String json = LaptopHost.Json.stringify(Map.of("enabled", enabled, "phones", entries)) + "\n";
        Path temporary = Files.createTempFile(parent, ".phone-enrollment-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.US_ASCII);
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            enrolledAddresses = Set.copyOf(phones.values().stream().map(phone -> phone.address).toList());
        } finally { Files.deleteIfExists(temporary); }
    }

    static boolean validSerial(String serial) {
        return serial != null && serial.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && !serial.startsWith("emulator-");
    }

    static boolean validAddress(String address) {
        if (address == null || !address.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) return false;
        String[] parts = address.split("\\.");int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 1 && parts[i].charAt(0) == '0') return false;
            octets[i] = Integer.parseInt(parts[i]);if (octets[i] > 255) return false;
        }
        return octets[3] > 0 && octets[3] < 255 && (octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168);
    }

    private static void requireSuccess(CommandResult result, String text) throws IOException {
        if (result.exitCode() != 0) throw new IOException(text);
    }
    private static String firstLine(String text) { return text.lines().findFirst().orElse(""); }
    private static String safeMessage(Exception failed) {
        if (failed instanceof IOException && failed.getMessage() != null && failed.getMessage().matches("[ -~]{1,240}"))
            return failed.getMessage();
        return "Phone update failed. Check USB authorization, Wi-Fi, and idle game state.";
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name);thread.setDaemon(true);return thread; }
    private static String digest(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    @Override public void close() {
        closed = true;timer.shutdownNow();work.shutdownNow();
        try { work.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        PendingUpdate unresolved;
        synchronized (lock) { unresolved = pending;pending = null; }
        if (unresolved != null) unresolved.lease().close();
    }

    private static final class Phone {
        final String serial, address;
        String state = "enrolled", message = "Waiting for an idle phone.", installedVersion = "";
        long updatedAt, nextAttempt, connectionBackoff;
        Phone(String serial, String address) { this.serial = serial;this.address = address; }
        Map<String, Object> snapshot() {
            return Map.of("serial", serial, "label", serial, "address", address, "state", state,
                    "message", message, "installedVersion", installedVersion, "updatedAt", updatedAt);
        }
    }

    /** No shell, no global ADB server changes, and bounded independent stdout/stderr readers. */
    static final class ProcessCommands implements CommandRunner {
        @Override public CommandResult run(List<String> command, long timeoutMillis, int maxOutput,
                                           BooleanSupplier allowed) throws IOException {
            check(allowed);Process process = new ProcessBuilder(command).start();
            Reader output = new Reader(process.getInputStream(), maxOutput);
            Reader error = new Reader(process.getErrorStream(), 65536);
            Thread outThread = daemon(output, "ADB stdout"), errThread = daemon(error, "ADB stderr");
            outThread.start();errThread.start();long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            try {
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    check(allowed);
                    if (System.nanoTime() >= deadline) throw new IOException("ADB command timed out.");
                    if (output.failure != null || error.failure != null) throw new IOException("ADB command output exceeded its limit.");
                }
                outThread.join(1000);errThread.join(1000);check(allowed);
                if (outThread.isAlive() || errThread.isAlive() || output.failure != null || error.failure != null)
                    throw new IOException("ADB command output could not be read safely.");
                return new CommandResult(process.exitValue(), output.bytes.toByteArray(), error.bytes.toString(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new IOException("ADB command interrupted.", interrupted);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();
            }
        }
    }

    private static final class Reader implements Runnable {
        final InputStream input;final int limit;final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile IOException failure;
        Reader(InputStream input, int limit) { this.input = input;this.limit = limit; }
        @Override public void run() {
            byte[] buffer = new byte[16384];
            try {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > limit) throw new IOException("Output limit exceeded");
                    bytes.write(buffer, 0, count);
                }
            } catch (IOException failed) { failure = failed; }
        }
    }

    /** The public APK has a v1 signature for Android 5; verify every payload entry. */
    static ApkInfo inspectApk(Path apk) throws IOException {
        byte[] manifest = null;String signer = null;long total = 0;
        try (JarFile jar = new JarFile(apk.toFile(), true)) {
            var entries = jar.entries();byte[] buffer = new byte[32768];int countEntries = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (++countEntries > 50000) throw new IOException("APK has too many entries.");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream xml = entry.getName().equals("AndroidManifest.xml") ? new ByteArrayOutputStream() : null;
                try (InputStream input = jar.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        total += count;
                        if (total > 300L * 1024 * 1024 || xml != null && xml.size() + count > 1024 * 1024)
                            throw new IOException("APK expands beyond the verification limit.");
                        if (xml != null) xml.write(buffer, 0, count);
                    }
                }
                if (entry.getName().startsWith("META-INF/")) continue;
                Certificate[] certificates = entry.getCertificates();
                if (certificates == null || certificates.length != 1) throw new IOException("APK payload lacks the single release signature.");
                String current = digest(certificates[0].getEncoded());
                if (signer == null) signer = current;
                else if (!signer.equals(current)) throw new IOException("APK entries have different signers.");
                if (xml != null) manifest = xml.toByteArray();
            }
        } catch (java.security.cert.CertificateEncodingException | SecurityException invalid) {
            throw new IOException("APK signing certificate verification failed.", invalid);
        }
        if (manifest == null || signer == null) throw new IOException("APK manifest or signature is missing.");
        return parseManifest(manifest, signer);
    }

    private static ApkInfo parseManifest(byte[] xml, String signer) throws IOException {
        try {
            ByteBuffer data = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
            if (xml.length < 8 || Short.toUnsignedInt(data.getShort(0)) != 3 || data.getInt(4) != xml.length)
                throw new IOException("APK manifest is not valid binary Android XML.");
            List<String> strings = null;int offset = Short.toUnsignedInt(data.getShort(2));
            while (offset < xml.length) {
                if (offset < 8 || offset > xml.length - 8) throw new IOException("APK manifest chunk is invalid.");
                int type = Short.toUnsignedInt(data.getShort(offset));
                int header = Short.toUnsignedInt(data.getShort(offset + 2)), size = data.getInt(offset + 4);
                if (header < 8 || size < header || size > xml.length - offset) throw new IOException("APK manifest chunk is invalid.");
                if (type == 1) {
                    if (header < 28) throw new IOException("APK string pool is invalid.");
                    int count = data.getInt(offset + 8), flags = data.getInt(offset + 16), start = data.getInt(offset + 20);
                    if (count < 0 || count > 65536 || header + (long) count * 4 > size || start < header || start > size)
                        throw new IOException("APK string pool is invalid.");
                    strings = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        int position = offset + start + data.getInt(offset + header + i * 4);
                        strings.add(readString(data, position, offset + size, (flags & 256) != 0));
                    }
                } else if (type == 0x102 && strings != null) {
                    int ext = offset + 16;
                    if (header != 16 || size < 36) throw new IOException("APK manifest element is invalid.");
                    String name = stringAt(strings, data.getInt(ext + 4));
                    if ("manifest".equals(name)) {
                        int start = Short.toUnsignedInt(data.getShort(ext + 8));
                        int width = Short.toUnsignedInt(data.getShort(ext + 10));
                        int count = Short.toUnsignedInt(data.getShort(ext + 12));
                        if (start < 20 || width < 20 || ext + (long) start + (long) width * count > offset + size)
                            throw new IOException("APK manifest attributes are invalid.");
                        String packageName = null, version = null;int versionCode = 0;boolean seenCode = false, seenMajor = false;
                        for (int i = 0; i < count; i++) {
                            int attr = ext + start + width * i;String key = stringAt(strings, data.getInt(attr + 4));
                            int namespace = data.getInt(attr);String uri = namespace < 0 ? "" : stringAt(strings, namespace);
                            int raw = data.getInt(attr + 8), valueType = Byte.toUnsignedInt(data.get(attr + 15)), value = data.getInt(attr + 16);
                            if ("package".equals(key) && namespace < 0) {
                                if (packageName != null || valueType != 3) throw new IOException("APK package attribute is duplicated or invalid.");
                                packageName = stringAt(strings, raw >= 0 ? raw : value);
                            }
                            if ("http://schemas.android.com/apk/res/android".equals(uri)) {
                                if ("versionCode".equals(key)) {
                                    if (seenCode || valueType != 16 && valueType != 17) throw new IOException("APK version code is duplicated or invalid.");
                                    seenCode = true;versionCode = value;
                                }
                                if ("versionCodeMajor".equals(key)) {
                                    if (seenMajor || valueType != 16 && valueType != 17 || value != 0)
                                        throw new IOException("APK long version codes are unsupported.");
                                    seenMajor = true;
                                }
                                if ("versionName".equals(key)) {
                                    if (version != null || valueType != 3) throw new IOException("APK version name is duplicated or invalid.");
                                    version = stringAt(strings, raw >= 0 ? raw : value);
                                }
                            }
                        }
                        if (packageName == null || versionCode <= 0 || version == null || !version.matches("[ -~]{1,80}"))
                            throw new IOException("APK manifest package or version is missing.");
                        return new ApkInfo(packageName, versionCode, version, signer);
                    }
                }
                offset += size;
            }
            throw new IOException("APK manifest element is missing.");
        } catch (IndexOutOfBoundsException | IllegalArgumentException invalid) {
            throw new IOException("APK manifest is invalid.", invalid);
        }
    }

    private static String stringAt(List<String> strings, int index) throws IOException {
        if (index < 0 || index >= strings.size()) throw new IOException("APK string index is invalid.");
        return strings.get(index);
    }

    private static String readString(ByteBuffer data, int position, int end, boolean utf8) throws IOException {
        if (position < 0 || position >= end) throw new IOException("APK string offset is invalid.");
        int[] cursor = {position};int length = readLength(data, cursor, end, utf8);
        if (utf8) length = readLength(data, cursor, end, true);
        long bytes = utf8 ? length : (long) length * 2;
        if (bytes > 1024 * 1024 || cursor[0] + bytes + (utf8 ? 1 : 2) > end)
            throw new IOException("APK string length is invalid.");
        byte[] encoded = new byte[(int) bytes];ByteBuffer copy = data.duplicate();copy.position(cursor[0]);copy.get(encoded);
        return new String(encoded, utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
    }

    private static int readLength(ByteBuffer data, int[] cursor, int end, boolean utf8) throws IOException {
        if (cursor[0] + (utf8 ? 1 : 2) > end) throw new IOException("APK string length is missing.");
        int first = utf8 ? Byte.toUnsignedInt(data.get(cursor[0]++)) : Short.toUnsignedInt(data.getShort(cursor[0]));
        if (!utf8) cursor[0] += 2;
        int mask = utf8 ? 128 : 32768;
        if ((first & mask) == 0) return first;
        if (cursor[0] + (utf8 ? 1 : 2) > end) throw new IOException("APK extended string length is missing.");
        int second = utf8 ? Byte.toUnsignedInt(data.get(cursor[0]++)) : Short.toUnsignedInt(data.getShort(cursor[0]));
        if (!utf8) cursor[0] += 2;
        long length = ((long) (first & (mask - 1)) << (utf8 ? 8 : 16)) | second;
        if (length > Integer.MAX_VALUE) throw new IOException("APK string is too long.");
        return (int) length;
    }
}
