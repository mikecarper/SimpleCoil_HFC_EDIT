/* Copyright (C) 2026 SimpleCoil contributors. Licensed under Apache-2.0. */
package com.simplecoil.laptophost;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional Linux hotspot control. Game networking itself does not depend on nmcli. */
final class HotspotManager {
    private static final String PROFILE_NAME = "SimpleCoil Hotspot";
    private static final String DEVICE_FIELDS = "GENERAL.DEVICE,GENERAL.TYPE,GENERAL.PRODUCT,"
            + "GENERAL.STATE,GENERAL.CONNECTION,GENERAL.CON-UUID,WIFI-PROPERTIES.AP,IP4.ADDRESS";
    private final Path settingsFile;
    private final CommandRunner runner;
    private final Properties settings = new Properties();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object lock = new Object();
    private volatile Map<String, Object> cached = Map.of();
    private volatile long cachedAt;
    private String settingsError;

    record Result(int code, String output) {}
    interface CommandRunner { Result run(List<String> arguments) throws IOException; }

    HotspotManager() {
        this(Path.of(System.getProperty("simplecoil.wifi.settings",
                Path.of(System.getProperty("user.home"), ".config", "simplecoil", "hotspot.properties")
                        .toString())), HotspotManager::runNmcli);
    }

    HotspotManager(Path settingsFile, CommandRunner runner) {
        this.settingsFile = settingsFile;
        this.runner = runner;
        settings.setProperty("ssid", "Recoil-SimpleCoil");
        settings.setProperty("security", "open");
        settings.setProperty("password", newPassword());
        settings.setProperty("interface", "");
        settings.setProperty("channel", "6");
        settings.setProperty("uuid", UUID.randomUUID().toString());
        try {
            if (Files.exists(settingsFile)) {
                try (var reader = Files.newBufferedReader(settingsFile)) { settings.load(reader); }
                UUID.fromString(settings.getProperty("uuid"));
            }
        } catch (IOException | IllegalArgumentException exception) {
            settingsError = "Could not read saved hotspot settings: " + exception.getMessage();
        }
    }

    boolean isBusy() { return busy.get(); }
    boolean beginChange() { return busy.compareAndSet(false, true); }
    void endChange() { cachedAt = 0; busy.set(false); }

    Map<String, Object> status() {
        if (!busy.get() && (cachedAt == 0 || System.nanoTime() - cachedAt > TimeUnit.SECONDS.toNanos(3))) {
            synchronized (lock) {
                if (!busy.get()) refresh();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(cached);
        result.put("busy", busy.get());
        return result;
    }

    void control(String action, Map<?, ?> values) throws IOException {
        synchronized (lock) {
            if (settingsError != null) throw new IOException(settingsError);
            if ("stop".equals(action)) {
                stop();
                return;
            }
            if (!"save".equals(action) && !"start".equals(action))
                throw new IllegalArgumentException("Choose start, stop, or save.");
            List<Map<String, Object>> devices = devices();
            if (devices.stream().anyMatch(device -> settings.getProperty("uuid").equals(device.get("uuid")))) {
                if ("start".equals(action)) return;
                throw new IllegalArgumentException("Stop the hotspot before changing its settings.");
            }
            String ssid = text(values, "ssid", settings.getProperty("ssid"));
            String password = text(values, "password", settings.getProperty("password"));
            String security = text(values, "security", settings.getProperty("security"));
            String deviceName = text(values, "interface", selectedInterface(devices));
            String channel = text(values, "channel", settings.getProperty("channel"));
            validate(ssid, password, channel, security);
            Map<String, Object> device = devices.stream().filter(d -> deviceName.equals(d.get("interface")))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose a Wi-Fi adapter."));
            if ("start".equals(action) && !Boolean.TRUE.equals(device.get("apSupported")))
                throw new IllegalArgumentException("This adapter's current driver does not support hotspot mode.");
            settings.setProperty("ssid", ssid);
            settings.setProperty("password", password);
            settings.setProperty("security", security);
            settings.setProperty("interface", deviceName);
            settings.setProperty("channel", channel);
            save();
            if ("save".equals(action)) return;

            String previous = String.valueOf(device.get("uuid"));
            settings.setProperty("previousUuid", previous);
            settings.setProperty("previousInterface", deviceName);
            save();
            try {
                configureProfile();
                checked("radio", "wifi", "on");
                if ("open".equals(security)) {
                    checked("connection", "up", "uuid", settings.getProperty("uuid"), "ifname", deviceName);
                } else {
                    Path passwordFile = Files.createTempFile(settingsFile.getParent(), "wifi-password-", ".txt",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    try {
                        Files.writeString(passwordFile, "802-11-wireless-security.psk:" + password + "\n");
                        checked("connection", "up", "uuid", settings.getProperty("uuid"), "ifname", deviceName,
                                "passwd-file", passwordFile.toString());
                    } finally {
                        Files.deleteIfExists(passwordFile);
                    }
                }
            } catch (IOException failure) {
                try { runner.run(List.of("connection", "down", "uuid", settings.getProperty("uuid"))); }
                catch (IOException ignored) { /* Preserve the original activation error. */ }
                try { restorePrevious(); }
                catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
                throw failure;
            }
        }
    }

    private void configureProfile() throws IOException {
        String uuid = settings.getProperty("uuid");
        boolean exists = Arrays.asList(checked("-g", "UUID", "connection", "show").split("\\R"))
                .contains(uuid);
        List<String> command = new ArrayList<>();
        if (exists) {
            if (!PROFILE_NAME.equals(checked("-g", "connection.id", "connection", "show", "uuid", uuid).trim()))
                throw new IOException("The saved hotspot profile belongs to another connection.");
            if ("open".equals(settings.getProperty("security")))
                checked("connection", "modify", "uuid", uuid, "remove", "802-11-wireless-security");
            command.addAll(List.of("connection", "modify", "uuid", uuid));
        } else {
            command.addAll(List.of("connection", "add", "type", "wifi", "con-name", PROFILE_NAME,
                    "connection.uuid", uuid));
        }
        command.addAll(List.of("connection.interface-name", settings.getProperty("interface"),
                "connection.autoconnect", "no", "connection.permissions", "user:" + System.getProperty("user.name"),
                "802-11-wireless.ssid", settings.getProperty("ssid"), "802-11-wireless.mode", "ap",
                "802-11-wireless.band", "bg", "802-11-wireless.channel", settings.getProperty("channel"),
                "802-11-wireless.ap-isolation", "0", "802-11-wireless.powersave", "2",
                "ipv4.method", "shared", "ipv4.addresses", "10.42.0.1/24",
                "ipv4.never-default", "yes", "ipv6.method", "disabled"));
        if ("wpa2".equals(settings.getProperty("security"))) command.addAll(List.of(
                "802-11-wireless-security.key-mgmt", "wpa-psk", "802-11-wireless-security.psk-flags", "2",
                "802-11-wireless-security.proto", "rsn", "802-11-wireless-security.pairwise", "ccmp",
                "802-11-wireless-security.group", "ccmp", "802-11-wireless-security.pmf", "1"));
        checked(command.toArray(String[]::new));
    }

    private void stop() throws IOException {
        for (Map<String, Object> device : devices()) {
            if (settings.getProperty("uuid").equals(device.get("uuid"))) {
                checked("connection", "down", "uuid", settings.getProperty("uuid"));
                restorePrevious();
                return;
            }
        }
    }

    private void restorePrevious() throws IOException {
        String previous = settings.getProperty("previousUuid", "");
        String device = settings.getProperty("previousInterface", "");
        if (!previous.isBlank() && !previous.equals(settings.getProperty("uuid")))
            checked("connection", "up", "uuid", previous, "ifname", device);
        settings.remove("previousUuid");
        settings.remove("previousInterface");
        save();
    }

    private void refresh() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("active", false);
        result.put("devices", List.of());
        result.put("addresses", List.of());
        result.put("ssid", settings.getProperty("ssid"));
        result.put("security", settings.getProperty("security"));
        result.put("password", settings.getProperty("password"));
        result.put("channel", settings.getProperty("channel"));
        try {
            if (settingsError != null) throw new IOException(settingsError);
            List<Map<String, Object>> devices = devices();
            String selected = selectedInterface(devices);
            result.put("available", true);
            result.put("devices", devices);
            result.put("interface", selected);
            for (Map<String, Object> device : devices) {
                if (settings.getProperty("uuid").equals(device.get("uuid"))) {
                    result.put("active", true);
                    result.put("interface", device.get("interface"));
                    result.put("addresses", device.get("addresses"));
                }
            }
        } catch (IOException exception) {
            result.put("message", exception.getMessage());
        }
        cached = result;
        cachedAt = System.nanoTime();
    }

    private String selectedInterface(List<Map<String, Object>> devices) {
        String selected = settings.getProperty("interface", "");
        if (!selected.isBlank()) return selected;
        return devices.stream().filter(d -> Boolean.TRUE.equals(d.get("apSupported")))
                .sorted((a, b) -> Boolean.compare(!String.valueOf(a.get("uuid")).isBlank(),
                        !String.valueOf(b.get("uuid")).isBlank()))
                .map(d -> String.valueOf(d.get("interface"))).findFirst().orElse("");
    }

    private List<Map<String, Object>> devices() throws IOException {
        String output = checked("-f", DEVICE_FIELDS, "device", "show");
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> addresses = new ArrayList<>();
        for (String line : (output + "\nGENERAL.DEVICE:\n").split("\\R")) {
            List<String> pair = splitEscaped(line);
            if (pair.size() < 2) continue;
            if ("GENERAL.DEVICE".equals(pair.get(0))) {
                if ("wifi".equals(fields.get("GENERAL.TYPE"))) {
                    Map<String, Object> device = new LinkedHashMap<>();
                    device.put("interface", fields.get("GENERAL.DEVICE"));
                    device.put("label", fields.getOrDefault("GENERAL.PRODUCT", fields.get("GENERAL.DEVICE")));
                    device.put("connection", fields.getOrDefault("GENERAL.CONNECTION", ""));
                    device.put("uuid", fields.getOrDefault("GENERAL.CON-UUID", ""));
                    device.put("apSupported", "yes".equals(fields.get("WIFI-PROPERTIES.AP")));
                    device.put("addresses", List.copyOf(addresses));
                    result.add(device);
                }
                fields.clear();
                addresses.clear();
            }
            fields.put(pair.get(0), pair.get(1));
            if (pair.get(0).startsWith("IP4.ADDRESS")) addresses.add(pair.get(1).split("/", 2)[0]);
        }
        return result;
    }

    static List<String> splitEscaped(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean escaped = false;
        for (char character : line.toCharArray()) {
            if (escaped) { part.append(character); escaped = false; }
            else if (character == '\\') escaped = true;
            else if (character == ':') { parts.add(part.toString()); part.setLength(0); }
            else part.append(character);
        }
        if (escaped) part.append('\\');
        parts.add(part.toString());
        return parts;
    }

    private String checked(String... arguments) throws IOException {
        Result result = runner.run(List.of(arguments));
        if (result.code != 0) throw new IOException(result.output.isBlank()
                ? "NetworkManager could not complete the Wi-Fi action." : result.output.trim());
        return result.output;
    }

    private void save() throws IOException {
        Files.createDirectories(settingsFile.getParent(), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Path temporary = Files.createTempFile(settingsFile.getParent(), "hotspot-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            StringWriter contents = new StringWriter();
            settings.store(contents, "SimpleCoil hotspot settings; keep this file private");
            Files.writeString(temporary, contents.toString());
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Result runNmcli(List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("nmcli", "--wait", "20", "--colors", "no",
                "--terse", "--escape", "yes"));
        command.addAll(arguments);
        Path output = Files.createTempFile("simplecoil-nmcli-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("Wi-Fi operation timed out. Check the adapter and try again.");
            }
            try (var stream = Files.newInputStream(output)) {
                return new Result(process.exitValue(), new String(stream.readNBytes(16_384), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Wi-Fi operation was interrupted.", exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private static String text(Map<?, ?> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static void validate(String ssid, String password, String channel, String security) {
        if (ssid.isBlank() || ssid.getBytes(StandardCharsets.UTF_8).length > 32
                || ssid.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Network name must contain 1–32 UTF-8 bytes, without control characters.");
        if (!List.of("open", "wpa2").contains(security))
            throw new IllegalArgumentException("Choose open Wi-Fi or WPA2 password protection.");
        if ("wpa2".equals(security) && (password.length() < 8 || password.length() > 63
                || password.chars().anyMatch(character -> character < 32 || character > 126)))
            throw new IllegalArgumentException("Password must contain 8–63 printable ASCII characters.");
        if (!List.of("1", "6", "11").contains(channel))
            throw new IllegalArgumentException("Choose Wi-Fi channel 1, 6, or 11.");
    }

    private static String newPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder("coil-");
        for (int i = 0; i < 12; i++) password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return password.toString();
    }
}
