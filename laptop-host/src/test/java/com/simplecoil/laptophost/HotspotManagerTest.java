package com.simplecoil.laptophost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Exercises Wi-Fi control without changing the computer's real connections. */
public final class HotspotManagerTest {
    private static final String USB = "wlx-test";
    private static final String PREVIOUS = "7051d67c-c66c-44c7-b652-fc4e83bff6d1";
    private static final Map<String, Object> VALUES = Map.of("ssid", "Game: Field", "password", "Test: password 123",
            "interface", USB, "channel", "6", "security", "wpa2");

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("simplecoil-wifi-test-");
        try {
            verifyLifecycle(temporary.resolve("lifecycle/hotspot.properties"));
            verifyOpenMode(temporary.resolve("open/hotspot.properties"));
            verifyFailureRecovery(temporary.resolve("recovery/hotspot.properties"));
            verifyUnsupportedAndValidation(temporary.resolve("validation/hotspot.properties"));
            verifyUnavailable(temporary.resolve("unavailable/hotspot.properties"));
            require(HotspotManager.splitEscaped("NAME:Field\\: One\\\\Two").equals(List.of("NAME", "Field: One\\Two")),
                    "nmcli escaped names were parsed incorrectly");
            System.out.println("Hotspot lifecycle, password privacy, validation, and recovery tests passed");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verifyOpenMode(Path file) throws Exception {
        FakeNetwork network = new FakeNetwork();
        HotspotManager manager = new HotspotManager(file, network);
        require("open".equals(manager.status().get("security")), "new hotspots were not open by default");
        change(manager, "start", Map.of("interface", USB, "password", ""));
        require(network.active && !network.protectedProfile, "default hotspot required a password");
        require(network.commands.stream().noneMatch(command -> command.contains("passwd-file")
                        || command.contains("802-11-wireless-security.key-mgmt")),
                "open activation configured security or supplied credentials");
        require(network.passwordFile == null, "open activation created a password file");
        change(manager, "stop", Map.of());
        change(manager, "start", VALUES);
        require(network.active && network.protectedProfile, "optional WPA2 could not be enabled");
        change(manager, "stop", Map.of());
        change(manager, "start", Map.of("security", "open", "password", ""));
        require(network.active && !network.protectedProfile, "switching to open left WPA2 protection behind");
        require(network.commands.stream().anyMatch(command -> command.contains("remove")
                        && command.contains("802-11-wireless-security")),
                "switching to open did not remove the previous security setting");
        change(manager, "stop", Map.of());
        HotspotManager reopened = new HotspotManager(file, network);
        require("open".equals(reopened.status().get("security")), "open selection was not saved");
        expectFailure(reopened, "save", Map.of("security", "invalid"), "Choose open");
    }

    private static void verifyLifecycle(Path file) throws Exception {
        FakeNetwork network = new FakeNetwork();
        HotspotManager manager = new HotspotManager(file, network);
        require(Boolean.TRUE.equals(manager.status().get("available")), "NetworkManager was not detected");
        require(String.valueOf(manager.status().get("ssid")).startsWith("Recoil"),
                "default hotspot name was not recognized by the phone's Recoil Wi-Fi search");
        change(manager, "save", VALUES);
        require(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")),
                "saved Wi-Fi credentials were not private");
        require(Files.getPosixFilePermissions(file.getParent()).equals(PosixFilePermissions.fromString("rwx------")),
                "settings directory was not private");
        change(manager, "start", VALUES);
        require(network.active && Boolean.TRUE.equals(manager.status().get("active")), "AP did not activate");
        require(manager.status().get("addresses").equals(List.of("10.42.0.1")), "AP address was lost");
        require(!Files.exists(network.passwordFile), "activation password file was retained");
        require(network.passwordWasPrivate, "activation password file was readable by other users");
        require(network.passwordWasCorrect, "password file did not preserve punctuation and spaces");
        require(network.commands.stream().noneMatch(command -> command.stream()
                        .anyMatch(argument -> argument.contains((String) VALUES.get("password")))),
                "Wi-Fi password appeared in process arguments");
        require(network.commands.stream().anyMatch(command -> command.contains("802-11-wireless.ap-isolation")
                        && command.contains("shared") && command.contains("rsn") && command.contains("ccmp")),
                "AP did not request shared addressing and WPA2 with client communication");
        require(network.commands.stream().anyMatch(command -> command.contains("802-11-wireless-security.pmf")
                        && "1".equals(command.get(command.indexOf("802-11-wireless-security.pmf") + 1))),
                "AP requested management-frame protection unsupported by the TP-Link driver");
        int count = network.commands.size();
        change(manager, "start", VALUES);
        require(network.commands.subList(count, network.commands.size()).stream()
                        .noneMatch(command -> command.contains("up")), "starting an active AP restarted it");
        expectFailure(manager, "save", VALUES, "Stop the hotspot");
        HotspotManager reopened = new HotspotManager(file, network);
        require(Boolean.TRUE.equals(reopened.status().get("active")), "restart lost ownership of the AP");
        change(reopened, "stop", Map.of());
        require(!network.active && network.restored, "stop did not restore the original adapter connection");
        require(network.commands.stream().noneMatch(command -> command.contains("wlp-internet")
                        && !command.contains("show")), "control changed the other Wi-Fi adapter");
        network.restored = false;
        int beforeStop = network.commands.size();
        change(reopened, "stop", Map.of());
        require(network.commands.subList(beforeStop, network.commands.size()).stream()
                        .noneMatch(command -> command.contains("down")), "stop deactivated an unrelated connection");
        require(!network.restored, "idle stop reactivated an unrelated connection");
    }

    private static void verifyFailureRecovery(Path file) throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.failActivation = true;
        HotspotManager manager = new HotspotManager(file, network);
        expectFailure(manager, "start", VALUES, "activation failed");
        require(!manager.isBusy(), "failed activation left Wi-Fi controls busy");
        require(!network.active && network.restored, "failed activation did not restore the original connection");
        require(network.cancelled, "failed activation was not cancelled before restoring the connection");
        require(!Files.exists(network.passwordFile), "failed activation leaked its password file");
        Properties saved = new Properties();
        try (var reader = Files.newBufferedReader(file)) { saved.load(reader); }
        require(saved.getProperty("previousUuid") == null, "successful recovery retained stale restore metadata");
    }

    private static void verifyUnsupportedAndValidation(Path file) throws Exception {
        FakeNetwork network = new FakeNetwork();
        network.supported = false;
        HotspotManager manager = new HotspotManager(file, network);
        change(manager, "save", VALUES);
        require(USB.equals(manager.status().get("interface")), "saved USB selection fell back to the Intel adapter");
        expectFailure(manager, "start", VALUES, "does not support");
        expectFailure(manager, "save", Map.of("password", "short"), "Password");
        expectFailure(manager, "save", Map.of("ssid", "x\n"), "Network name");
        expectFailure(manager, "save", Map.of("ssid", "🌳".repeat(9)), "Network name");
        expectFailure(manager, "save", Map.of("channel", "13"), "channel");
        require(network.commands.stream().noneMatch(command -> command.contains("add") || command.contains("up")),
                "invalid or unsupported settings changed NetworkManager");
    }

    private static void verifyUnavailable(Path file) {
        HotspotManager manager = new HotspotManager(file, arguments -> { throw new IOException("nmcli missing"); });
        require(Boolean.FALSE.equals(manager.status().get("available")), "missing NetworkManager was not reported");
        require(String.valueOf(manager.status().get("message")).contains("nmcli missing"), "missing dependency had no explanation");
    }

    private static void change(HotspotManager manager, String action, Map<?, ?> values) throws IOException {
        require(manager.beginChange(), "could not acquire Wi-Fi control");
        require(!manager.beginChange(), "concurrent Wi-Fi control was allowed");
        try { manager.control(action, values); }
        finally { manager.endChange(); }
    }

    private static void expectFailure(HotspotManager manager, String action, Map<?, ?> values, String message)
            throws IOException {
        try { change(manager, action, values); }
        catch (IOException | IllegalArgumentException exception) {
            require(exception.getMessage().contains(message), "unexpected error: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeNetwork implements HotspotManager.CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        boolean supported = true, active, exists, restored, cancelled, failActivation, protectedProfile;
        boolean passwordWasPrivate, passwordWasCorrect;
        String uuid;
        Path passwordFile;

        @Override public HotspotManager.Result run(List<String> arguments) throws IOException {
            commands.add(List.copyOf(arguments));
            if (arguments.contains("device")) {
                return ok("GENERAL.DEVICE:wlp-internet\nGENERAL.TYPE:wifi\nGENERAL.PRODUCT:Intel\n"
                        + "GENERAL.CONNECTION:Internet\nGENERAL.CON-UUID:other-connection\nWIFI-PROPERTIES.AP:yes\n"
                        + "GENERAL.DEVICE:" + USB + "\nGENERAL.TYPE:wifi\nGENERAL.PRODUCT:TP-Link\\: USB\n"
                        + "GENERAL.CONNECTION:" + (active ? "SimpleCoil Hotspot" : "Previous") + "\n"
                        + "GENERAL.CON-UUID:" + (active ? uuid : PREVIOUS) + "\nWIFI-PROPERTIES.AP:"
                        + (supported ? "yes" : "no") + "\nIP4.ADDRESS[1]:" + (active ? "10.42.0.1" : "192.168.1.20") + "/24\n");
            }
            if (arguments.contains("UUID")) return ok(exists ? uuid + "\n" : "");
            if (arguments.contains("connection.id")) return ok("SimpleCoil Hotspot\n");
            if (arguments.contains("add")) {
                uuid = arguments.get(arguments.indexOf("connection.uuid") + 1);
                exists = true;
            }
            if (arguments.contains("802-11-wireless-security.key-mgmt")) protectedProfile = true;
            if (arguments.contains("remove") && arguments.contains("802-11-wireless-security")) protectedProfile = false;
            if (arguments.contains("up")) {
                String target = arguments.get(arguments.indexOf("uuid") + 1);
                if (PREVIOUS.equals(target)) { restored = true; active = false; }
                else {
                    if (arguments.contains("passwd-file")) {
                        passwordFile = Path.of(arguments.get(arguments.indexOf("passwd-file") + 1));
                        passwordWasPrivate = Files.getPosixFilePermissions(passwordFile)
                                .equals(PosixFilePermissions.fromString("rw-------"));
                        passwordWasCorrect = Files.readString(passwordFile)
                                .equals("802-11-wireless-security.psk:" + VALUES.get("password") + "\n");
                    } else require(!protectedProfile, "protected profile was activated without credentials");
                    active = true;
                    if (failActivation) return new HotspotManager.Result(4, "activation failed");
                }
            }
            if (arguments.contains("down")) { active = false; cancelled = true; }
            return ok("");
        }

        private HotspotManager.Result ok(String output) { return new HotspotManager.Result(0, output); }
    }
}
