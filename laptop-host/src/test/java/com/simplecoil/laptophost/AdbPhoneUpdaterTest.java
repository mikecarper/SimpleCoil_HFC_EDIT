package com.simplecoil.laptophost;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** All ADB and phone behavior is injected. No real devices or network scans. */
public final class AdbPhoneUpdaterTest {
    private static final String SERIAL = "USB01", IP = "192.168.1.72", HOST = "192.168.1.44";
    private static final String APK = "/data/app/com.simplecoil.simplecoil-1/base.apk";
    private static final byte[] OLD_APK = "signed old APK fixture".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NEW_APK = "signed new APK fixture".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].startsWith("child-")) { child(arguments[0]);return; }
        verifyExplicitEnrollmentAndPersistence();
        verifyNewerUpdateAndExactPostInstallProof();
        verifyFailClosedGatesAndNoDowngrades();
        verifyUncertainInstallKeepsHostReservationUntilReconciled();
        verifyIndependentBackoffAndManagement();
        verifyDisableDuringInstallAndSingleWorker();
        verifyMissingAdbAndInvalidState();
        verifyRealProcessBoundsAndCancellation();
        verifyApkVerification(arguments);
        verifyManifestAmbiguityIsRejected();
        System.out.println("Wi-Fi ADB explicit enrollment, phone leases, exact signed APK checks, idle gates, backoff, and process bounds passed");
    }

    private static void verifyExplicitEnrollmentAndPersistence() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.release = null;f.poll();
            require(f.updater.setEnabled(false), "Enrollment setup could not disable automatic installs");
            await(() -> !f.updater.busy(), "Disable setup did not finish");
            require(!f.updater.enrollUsb("192.168.1.9:5555"), "An arbitrary network address enrolled");
            require(!f.updater.enrollUsb("USBNOAPP"), "A non-game USB device enrolled");
            require(!f.updater.enrollUsb("USBUNAUTHORIZED"), "An unauthorized USB device enrolled");
            require(!f.updater.enrollUsb("not-connected"), "An unseen serial enrolled");
            List<?> usb = (List<?>) f.updater.snapshot().get("usbPhones");
            require(usb.size() == 2, "USB candidates included non-game or network devices");
            require(f.commands.calls.stream().noneMatch(call -> call.contains("tcpip") || call.contains("connect")),
                    "Read-only discovery enabled ADB or connected to a network device");
            require(f.updater.enrollUsb(SERIAL), "An explicit authorized USB game phone did not enroll");
            await(() -> !f.updater.busy(), "Enrollment did not finish");
            require(f.commands.count("tcpip") == 1, "Enrollment did not enable TCP mode exactly once");
            Map<?, ?> stored = (Map<?, ?>) LaptopHost.Json.parse(Files.readString(f.state));
            require(stored.keySet().equals(java.util.Set.of("enabled", "phones")), "Enrollment persisted unrelated or sensitive data");
            Map<?, ?> row = (Map<?, ?>) ((List<?>) stored.get("phones")).get(0);
            require(row.equals(Map.of("serial", SERIAL, "address", IP)), "Enrollment did not persist only serial and numeric IP");
            require(f.leasesClosed.get() == 1 && f.commands.count("connect") == 1,
                    "Enrollment leaked the host reservation or did not confirm wireless connectivity");
            require(!f.updater.managesAddress(IP), "Enrollment enabled automatic installs before the explicit toggle");
            require(f.updater.remove(SERIAL), "An explicit remove action failed");
            require(((List<?>) f.updater.snapshot().get("phones")).isEmpty(), "Removed phone remained enrolled");
            require(!f.updater.remove(SERIAL), "Unknown phone removal succeeded");
        }
        for (String address : List.of("127.0.0.1", "8.8.8.8", "192.168.1.0", "192.168.1.255", "192.168.01.4",
                "192.168.1.300", "host.example", "192.168.1.4;rm", "::1", "169.254.1.2"))
            require(!AdbPhoneUpdater.validAddress(address), "An unsafe network address was accepted: " + address);
        require(AdbPhoneUpdater.validAddress("10.4.2.1") && AdbPhoneUpdater.validAddress("172.31.255.254"), "Private Wi-Fi addresses were rejected");
    }

    private static void verifyNewerUpdateAndExactPostInstallProof() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.poll();
            require(f.commands.count("install") == 1 && f.phoneState().equals("updated"), "The newer update did not complete");
            require(f.commands.actualInstalledCode == 60, "Version verification did not see the new version");
            require(f.commands.count("exec-out") == 2, "Installed signer and exact final bytes were not both checked");
            require(f.commands.count("start") == 1, "The verified updated game was not relaunched");
            require(f.commands.count("connect") == 1 && f.commands.calls.stream().filter(c -> c.contains("connect"))
                    .allMatch(c -> c.get(c.size() - 1).equals(IP + ":5555")), "The updater connected to an unenrolled address");
            require(f.commands.count("install") == f.leasesClosed.get(), "The host install reservation leaked");
            require(f.commands.calls.stream().noneMatch(c -> c.contains("uninstall") || c.contains("root") || c.contains("kill-server")
                    || c.contains("-d") || c.contains("-t")), "The updater used a destructive or downgrade command");
            require(f.commands.calls.stream().filter(c -> c.contains("install")).allMatch(c -> c.contains("-r")), "The update did not preserve app data");
            try (var files = Files.list(f.directory)) {
                require(files.noneMatch(path -> path.getFileName().toString().startsWith(".phone-")), "A temporary installed APK remained on disk");
            }
        }
        for (String failure : List.of("install-no-success")) {
            try (Fixture f = new Fixture(true)) {
                f.commands.failure = failure;f.poll();
                require(!f.phoneState().equals("updated"), "An unverified installation was reported as success: " + failure);
                require(f.commands.count("start") == 0, "An unverified installed game was relaunched: " + failure);
                require(f.commands.count("RELEASE") == 1 && f.leasesClosed.get() == 1, "A failed installation leaked maintenance permission");
            }
        }
    }

    private static void verifyUncertainInstallKeepsHostReservationUntilReconciled() throws Exception {
        for (String failure : List.of("install-exit-failure", "post-version", "post-sha", "identity-after", "install-timeout", "install-timeout-before-commit")) {
            try (Fixture f = new Fixture(true)) {
                f.commands.failure = failure;f.poll();
                require(f.phoneState().equals("uncertain") && f.updater.busy() && f.leasesClosed.get() == 0,
                        "An unresolved install released the host START reservation: " + failure);
                require(f.commands.count("RELEASE") == 0 && !f.updater.remove(SERIAL),
                        "An unresolved install released the phone or allowed forgetting its reservation");
                require(f.commands.count("start") == 0, "An unverified installed game was relaunched");
                f.commands.failure = "";
                if (failure.equals("install-timeout-before-commit")) {
                    f.poll();
                    require(f.phoneState().equals("uncertain") && f.leasesClosed.get() == 0 && f.commands.count("install") == 1,
                            "A pending old version retried the install or released the host");
                    f.commands.actualInstalledCode = 60;
                    require(f.updater.setEnabled(false), "An unresolved installation prevented disabling future updates");
                    await(() -> !f.updater.busy(), "A disabled updater stopped reconciling its earlier installation");
                } else f.poll();
                require(f.phoneState().equals("updated") && f.leasesClosed.get() == 1 && !f.updater.busy(),
                        "A verified background commit did not release the host reservation: " + failure);
                require(f.commands.count("install") == 1 && f.commands.count("start") == 1 && f.commands.count("RELEASE") == 1,
                        "Reconciliation reinstalled instead of verifying and relaunching the exact committed APK");
            }
        }
        Fixture shutdown = new Fixture(true);
        shutdown.commands.failure = "install-timeout-before-commit";shutdown.poll();
        require(shutdown.leasesClosed.get() == 0, "A pending reservation closed early");
        shutdown.close();require(shutdown.leasesClosed.get() == 1, "Host shutdown leaked its unresolved local reservation");
    }

    private static void verifyFailClosedGatesAndNoDowngrades() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.hostIdle.set(false);f.poll();require(f.commands.calls.isEmpty(), "An active game ran ADB commands");
        }
        try (Fixture f = new Fixture(true)) {
            f.phonePermits.set(false);f.poll();require(f.commands.count("connect") == 0, "A phone without host permission was contacted");
        }
        for (String failure : List.of("identity-before", "old-signer", "missing-handshake", "wrong-handshake-host", "status-denied", "expired-lease")) {
            try (Fixture f = new Fixture(true)) {
                f.commands.failure = failure;f.poll();
                require(f.commands.count("install") == 0, "An unsafe phone was updated: " + failure);
                require(f.leasesClosed.get() == 1, "A refused phone leaked a host reservation");
            }
        }
        for (int code : List.of(60, 61)) {
            try (Fixture f = new Fixture(true)) {
                f.commands.actualInstalledCode = code;f.poll();
                require(f.commands.count("install") == 0 && f.phoneState().equals("current"), "An equal or newer app was reinstalled/downgraded");
                require(f.updater.managesAddress(IP) && f.commands.count("STATUS") == 0,
                        "An enrolled current phone lost roster management or needlessly entered maintenance");
            }
        }
        try (Fixture f = new Fixture(true)) {
            Files.write(f.release.apk(), OLD_APK);expectIOException(f::poll);
            require(f.commands.count("install") == 0, "Corrupt cached APK bytes reached an installer");
        }
        try (Fixture f = new Fixture(true)) {
            f.badReleaseCert = true;expectIOException(f::poll);
            require(f.commands.count("install") == 0, "An APK signed by another key reached an installer");
        }
    }

    private static void verifyIndependentBackoffAndManagement() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.commands.actualInstalledCode = 60;f.poll();int connections = f.commands.count("connect");
            f.clock.addAndGet(15000);f.poll();
            require(f.commands.count("connect") == connections && f.updater.managesAddress(IP),
                    "Install backoff lost roster management or reconnected unnecessarily");
            require(f.commands.count("install") == 0, "A managed current phone was reinstalled");
        }
        try (Fixture f = new Fixture(true)) {
            f.release = null;f.poll();
            require(f.commands.count("STATUS") == 0 && f.commands.count("install") == 0 && f.updater.managesAddress(IP),
                    "An empty cache lost enrolled roster management or started an installation");
        }
        try (Fixture f = new Fixture(true)) {
            f.commands.failure = "identity-before";f.poll();int connections = f.commands.count("connect");
            f.clock.addAndGet(15000);f.poll();
            require(f.commands.count("connect") == connections, "A failed connection ignored per-phone retry backoff");
            f.commands.failure = "";require(f.updater.retry(SERIAL), "An explicit retry was refused");
            await(() -> !f.updater.busy(), "Retry did not finish");
            require(f.phoneState().equals("updated"), "An explicit retry did not clear failure backoff");
        }
    }

    private static void verifyDisableDuringInstallAndSingleWorker() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.commands.installEntered = new CountDownLatch(1);f.commands.installContinue = new CountDownLatch(1);
            invoke(f.updater, "queuePoll");
            require(f.commands.installEntered.await(5, TimeUnit.SECONDS), "The test installation did not start");
            require(f.updater.busy(), "Installing status was not busy");
            require(!f.updater.enrollUsb(SERIAL) && !f.updater.retry(SERIAL) && !f.updater.remove(SERIAL), "An operation raced a live installation");
            require(f.updater.setEnabled(false), "The user could not disable automatic updates during installation");
            f.commands.installContinue.countDown();await(() -> !f.updater.busy(), "Disabling interrupted or stalled an active installation");
            require(f.phoneState().equals("updated") && !Boolean.TRUE.equals(f.updater.snapshot().get("enabled")),
                    "Disabling did not finish the current verified installation and prevent later updates");
            int installs = f.commands.count("install");f.poll();require(installs == f.commands.count("install"), "Disabled automatic updates installed another update");
        }
    }

    private static void verifyMissingAdbAndInvalidState() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.commands.missing = true;f.poll();
            require(Boolean.FALSE.equals(f.updater.snapshot().get("available")), "Missing ADB was not visible in status");
            require(f.updater.setEnabled(false), "Missing ADB prevented disabling automatic updates");
            await(() -> !f.updater.busy(), "Disable probe did not finish");
            require(f.updater.remove(SERIAL), "Missing ADB prevented forgetting an enrolled phone");
        }
        try (Fixture f = new Fixture(true)) {
            Files.delete(f.state);Files.createDirectory(f.state);Files.writeString(f.state.resolve("block"), "block");
            require(f.updater.setEnabled(false) && !Boolean.TRUE.equals(f.updater.snapshot().get("enabled")),
                    "A settings write failure prevented the user from disabling automatic updates");
            require(!f.updater.managesAddress(IP), "A failed settings write left the phone managed after disabling");
        }
        Path directory = Files.createTempDirectory("adb-invalid-state-");Path state = directory.resolve("state.json");
        Files.writeString(state, "{\"enabled\":true,\"phones\":[{\"serial\":\"USB01\",\"address\":\"evil.example\"}]}");
        try (AdbPhoneUpdater updater = new AdbPhoneUpdater(state, () -> true, () -> null, (serial, address) -> null)) {
            require(Boolean.FALSE.equals(updater.snapshot().get("enabled")) && ((List<?>) updater.snapshot().get("phones")).isEmpty(),
                    "Invalid persisted enrollment enabled network access");
        }
        String previous = System.getProperty("simplecoil.adb.updates.enabled");
        try {
            System.setProperty("simplecoil.adb.updates.enabled", "false");
            Files.writeString(state, "{\"enabled\":true,\"phones\":[{\"serial\":\"USB01\",\"address\":\"192.168.1.72\"}]}");
            try (AdbPhoneUpdater updater = new AdbPhoneUpdater(state, () -> true, () -> null, (serial, address) -> null)) {
                updater.start();require(!updater.setEnabled(true) && !Boolean.TRUE.equals(updater.snapshot().get("enabled")),
                        "The test-isolation property loaded or enabled real enrollment state");
            }
        } finally {
            if (previous == null) System.clearProperty("simplecoil.adb.updates.enabled");
            else System.setProperty("simplecoil.adb.updates.enabled", previous);
        }
    }

    private static void verifyRealProcessBoundsAndCancellation() throws Exception {
        AdbPhoneUpdater.ProcessCommands runner = new AdbPhoneUpdater.ProcessCommands();
        var okay = runner.run(childCommand("child-output"), 5000, 32, () -> true);
        require(okay.exitCode() == 0 && okay.stdout().length == 4 && okay.stdout()[0] == 0 && okay.stderr().equals("diagnostic"),
                "Process runner mixed binary APK bytes with stderr");
        expectIOException(() -> runner.run(childCommand("child-output"), 5000, 2, () -> true));
        expectIOException(() -> runner.run(childCommand("child-wait"), 150, 32, () -> true));
        AtomicLong calls = new AtomicLong();
        expectIOException(() -> runner.run(childCommand("child-wait"), 5000, 32, () -> calls.incrementAndGet() < 3));
    }

    private static List<String> childCommand(String argument) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), AdbPhoneUpdaterTest.class.getName(), argument);
    }

    private static void child(String argument) throws Exception {
        if (argument.equals("child-wait")) { Thread.sleep(5000);return; }
        System.out.write(new byte[]{0, 1, 2, (byte) 255});System.err.print("diagnostic");
    }

    private static void verifyApkVerification(String[] arguments) throws Exception {
        Path unsigned = Files.createTempFile("unsigned-game-", ".apk");
        try {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(unsigned))) {
                jar.putNextEntry(new JarEntry("AndroidManifest.xml"));jar.write(new byte[]{3, 0, 8, 0, 8, 0, 0, 0});jar.closeEntry();
            }
            expectIOException(() -> AdbPhoneUpdater.inspectApk(unsigned));
        } finally { Files.deleteIfExists(unsigned); }
        if (arguments.length > 0) {
            AdbPhoneUpdater.ApkInfo info = AdbPhoneUpdater.inspectApk(Path.of(arguments[0]));
            require(info.packageName().equals(AdbPhoneUpdater.PACKAGE) && info.certificateSha256().equals(AdbPhoneUpdater.RELEASE_CERT)
                    && info.versionCode() > 0, "The real public APK signer/manifest could not be verified");
            System.out.println("Real public APK verified: " + info.version() + " / code " + info.versionCode());
        }
    }

    private static void verifyManifestAmbiguityIsRejected() throws Exception {
        AdbPhoneUpdater.ApkInfo info = parseManifest(manifest("valid"));
        require(info.packageName().equals(AdbPhoneUpdater.PACKAGE) && info.versionCode() == 60 && info.version().equals("1.59"),
                "A valid namespaced manifest could not be read");
        for (String failure : List.of("duplicate-package", "duplicate-code", "duplicate-name", "major", "wrong-namespace"))
            expectIOException(() -> parseManifest(manifest(failure)));
        byte[] corrupt = manifest("valid");ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Integer.MAX_VALUE);
        expectIOException(() -> parseManifest(corrupt));
    }

    private static AdbPhoneUpdater.ApkInfo parseManifest(byte[] bytes) throws Exception {
        Method parser = AdbPhoneUpdater.class.getDeclaredMethod("parseManifest", byte[].class, String.class);parser.setAccessible(true);
        try { return (AdbPhoneUpdater.ApkInfo) parser.invoke(null, bytes, AdbPhoneUpdater.RELEASE_CERT); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            throw wrapped;
        }
    }

    private static byte[] manifest(String mutation) {
        List<String> strings = List.of("http://schemas.android.com/apk/res/android", "manifest", "package",
                AdbPhoneUpdater.PACKAGE, "versionCode", "versionName", "1.59", "versionCodeMajor", "wrong.namespace");
        int dataSize = 0;List<byte[]> encoded = new ArrayList<>();
        for (String value : strings) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8);encoded.add(bytes);dataSize += 3 + bytes.length; }
        int poolSize = (28 + strings.size() * 4 + dataSize + 3) & ~3;
        boolean extra = mutation.startsWith("duplicate-") || mutation.equals("major");int count = extra ? 4 : 3;
        int elementSize = 36 + count * 20;ByteBuffer data = ByteBuffer.allocate(8 + poolSize + elementSize).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 3).putShort((short) 8).putInt(data.capacity());
        data.putShort((short) 1).putShort((short) 28).putInt(poolSize).putInt(strings.size()).putInt(0).putInt(256)
                .putInt(28 + strings.size() * 4).putInt(0);
        int position = 0;
        for (byte[] bytes : encoded) { data.putInt(position);position += bytes.length + 3; }
        for (byte[] bytes : encoded) data.put((byte) bytes.length).put((byte) bytes.length).put(bytes).put((byte) 0);
        data.position(8 + poolSize);
        data.putShort((short) 0x102).putShort((short) 16).putInt(elementSize).putInt(1).putInt(-1);
        data.putInt(-1).putInt(1).putShort((short) 20).putShort((short) 20).putShort((short) count)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        attribute(data, -1, 2, 3, 3, 3);
        attribute(data, mutation.equals("wrong-namespace") ? 8 : 0, 4, -1, 16, 60);
        attribute(data, 0, 5, 6, 3, 6);
        if (mutation.equals("duplicate-package")) attribute(data, -1, 2, 3, 3, 3);
        if (mutation.equals("duplicate-code")) attribute(data, 0, 4, -1, 16, 59);
        if (mutation.equals("duplicate-name")) attribute(data, 0, 5, 6, 3, 6);
        if (mutation.equals("major")) attribute(data, 0, 7, -1, 16, 1);
        return data.array();
    }

    private static void attribute(ByteBuffer data, int namespace, int name, int raw, int type, int value) {
        data.putInt(namespace).putInt(name).putInt(raw).putShort((short) 8).put((byte) 0).put((byte) type).putInt(value);
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("adb-update-test-");
        final Path state = directory.resolve("enrollment.json");
        final AtomicBoolean hostIdle = new AtomicBoolean(true), phonePermits = new AtomicBoolean(true);
        final AtomicInteger leasesClosed = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1000000);
        final FakeCommands commands = new FakeCommands();
        final AdbPhoneUpdater updater;
        volatile AdbPhoneUpdater.ReleaseApk release;
        boolean badReleaseCert;

        Fixture(boolean enrolled) throws Exception {
            Files.writeString(state, LaptopHost.Json.stringify(Map.of("enabled", true, "phones", enrolled
                    ? List.of(Map.of("serial", SERIAL, "address", IP)) : List.of())));
            Path apk = Files.write(directory.resolve("release.apk"), NEW_APK);
            release = new AdbPhoneUpdater.ReleaseApk(apk, NEW_APK.length, sha(NEW_APK));
            updater = new AdbPhoneUpdater(state, hostIdle::get, () -> release, (serial, address) -> {
                if (!phonePermits.get()) return null;
                require(serial.equals(SERIAL) && address.equals(IP), "Safety gate was called for an unrecognized phone");
                return new AdbPhoneUpdater.Lease() {
                    public String hostAddress() { return HOST; }
                    public boolean stillAllowed() { return hostIdle.get(); }
                    public void close() { leasesClosed.incrementAndGet(); }
                };
            }, commands, path -> {
                byte[] bytes = Files.readAllBytes(path);boolean old = java.util.Arrays.equals(bytes, OLD_APK);
                return new AdbPhoneUpdater.ApkInfo(AdbPhoneUpdater.PACKAGE, old ? 59 : 60, old ? "1.58" : "1.59",
                        old && commands.failure.equals("old-signer") || !old && badReleaseCert ? "0".repeat(64) : AdbPhoneUpdater.RELEASE_CERT);
            }, clock::get, "fake-adb");
        }
        void poll() throws Exception { invoke(updater, "poll"); }
        String phoneState() { return (String) ((Map<?, ?>) ((List<?>) updater.snapshot().get("phones")).get(0)).get("state"); }
        @Override public void close() { updater.close(); }
    }

    private static final class FakeCommands implements AdbPhoneUpdater.CommandRunner {
        final List<List<String>> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        String failure = "";boolean missing, prepared;
        int actualInstalledCode = 59;
        CountDownLatch installEntered, installContinue;
        @Override public AdbPhoneUpdater.CommandResult run(List<String> command, long timeout, int limit,
                                                           java.util.function.BooleanSupplier allowed) throws IOException {
            if (missing) throw new IOException("No adb executable");
            if (!allowed.getAsBoolean()) throw new IOException("Not idle");
            calls.add(command);int first = command.size() > 2 && command.get(1).equals("-s") ? 3 : 1;
            List<String> args = command.subList(first, command.size());String endpoint = first == 3 ? command.get(2) : "";
            if (args.equals(List.of("version"))) return text("Android Debug Bridge version fixture");
            if (args.equals(List.of("devices", "-l"))) return text("List of devices attached\nUSB01 device usb:1-2\nUSBNOAPP device usb:1-3\nUSBUNAUTHORIZED unauthorized usb:1-4\n192.168.1.99:5555 device\nemulator-5554 device\n");
            if (args.equals(List.of("get-devpath"))) return text("usb:1-2");
            if (args.equals(List.of("shell", "pm", "path", AdbPhoneUpdater.PACKAGE)))
                return text(endpoint.equals("USBNOAPP") ? "" : "package:" + APK);
            if (args.equals(List.of("shell", "ip", "-f", "inet", "addr", "show", "wlan0"))) return text("2: wlan0\n    inet " + IP + "/24 brd 192.168.1.255 scope global wlan0");
            if (args.equals(List.of("shell", "getprop", "ro.serialno")))
                return text(failure.equals("identity-before") || failure.equals("identity-after") && actualInstalledCode == 60 ? "OTHERPHONE" : SERIAL);
            if (args.get(0).equals("tcpip")) return text("restarting in TCP mode port: 5555");
            if (args.get(0).equals("connect")) return text("connected to " + args.get(1));
            if (args.equals(List.of("shell", "dumpsys", "package", AdbPhoneUpdater.PACKAGE)))
                return text("Packages:\n  versionCode=" + (failure.equals("post-version") && actualInstalledCode == 60 ? 59 : actualInstalledCode) + " targetSdk=29\n");
            if (args.get(0).equals("exec-out")) return bytes(actualInstalledCode >= 60
                    ? failure.equals("post-sha") ? OLD_APK : NEW_APK : OLD_APK);
            if (args.get(0).equals("push")) return text("1 file pushed");
            if (args.size() > 2 && args.subList(0, 3).equals(List.of("shell", "rm", "-f"))) return text("");
            if (args.size() > 2 && args.subList(0, 3).equals(List.of("shell", "pm", "install"))) {
                if (installEntered != null) {
                    installEntered.countDown();
                    try { if (!installContinue.await(5, TimeUnit.SECONDS)) throw new IOException("Test install gate timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException(interrupted); }
                }
                if (!allowed.getAsBoolean()) throw new IOException("Install lost permission");
                if (failure.equals("install-timeout-before-commit")) throw new IOException("ADB command timed out.");
                actualInstalledCode = 60;
                if (failure.equals("install-timeout")) throw new IOException("ADB command timed out.");
                if (failure.equals("install-exit-failure")) return new AdbPhoneUpdater.CommandResult(1, new byte[0], "Failure");
                return text(failure.equals("install-no-success") ? "Failure [INSTALL_FAILED]" : "Success");
            }
            if (args.size() > 2 && args.subList(0, 3).equals(List.of("shell", "am", "broadcast"))) {
                String action = args.get(args.indexOf("-a") + 1), nonce = args.get(args.indexOf("nonce") + 1), host = args.get(args.indexOf("host") + 1);
                if (failure.equals("missing-handshake")) return text("Broadcast completed: result=0");
                if (action.endsWith("PREPARE")) prepared = true;
                String status = action.endsWith("RELEASE") ? "released" : prepared ? "ready" : "absent";
                if (failure.equals("status-denied") && action.endsWith("STATUS") && prepared) status = "denied";
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("protocol", 1);reply.put("status", status);reply.put("nonce", nonce);
                reply.put("host", failure.equals("wrong-handshake-host") ? "192.168.1.99" : host);
                reply.put("expiresInMs", failure.equals("expired-lease") ? 1 : 180000);
                reply.put("package", AdbPhoneUpdater.PACKAGE);reply.put("versionCode", actualInstalledCode);
                reply.put("managed", true);reply.put("eligible", true);
                if (action.endsWith("RELEASE")) prepared = false;
                return text("Broadcast completed: result=" + (status.equals("ready") || status.equals("released") ? "-1" : "0")
                        + ", data=\"SCADB1 " + LaptopHost.Json.stringify(reply) + "\"");
            }
            if (args.size() > 2 && args.subList(0, 3).equals(List.of("shell", "am", "start"))) return text("Starting: Intent");
            throw new IOException("Unexpected fake ADB command: " + args);
        }
        int count(String token) {
            synchronized (calls) { return (int) calls.stream().filter(command -> command.stream().anyMatch(value -> value.equals(token) || value.endsWith("ADB_UPDATE_" + token))).count(); }
        }
        private AdbPhoneUpdater.CommandResult text(String text) { return bytes(text.getBytes(StandardCharsets.UTF_8)); }
        private AdbPhoneUpdater.CommandResult bytes(byte[] bytes) { return new AdbPhoneUpdater.CommandResult(0, bytes, ""); }
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);method.setAccessible(true);
        try { method.invoke(target); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            throw wrapped;
        }
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void expectIOException(Throwing operation) throws Exception {
        try { operation.run();throw new AssertionError("Expected an IOException"); }
        catch (IOException expected) { }
    }
    private static void await(java.util.function.BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        require(condition.getAsBoolean(), failure);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Throwing { void run() throws Exception; }
}
