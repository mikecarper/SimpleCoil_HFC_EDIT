package com.simplecoil.laptophost;

import com.simplecoil.protocol.EventMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Properties;

/** Keep the organizer's recovery PIN private and stable across host restarts. */
final class EventSettings {
    private final Path file = Path.of(System.getProperty("simplecoil.event.file",
            Path.of(System.getProperty("user.home"), ".config", "simplecoil", "event.properties").toString()));
    private String pin;
    private String title;
    private boolean enabled, kiosk;

    EventSettings() {
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(file)) { saved.load(input); }
        catch (IOException ignored) { }
        pin = saved.getProperty("pin");
        title = saved.getProperty("title", EventMode.APP_NAME);
        if (!EventMode.validTitle(title)) title = EventMode.APP_NAME;
        enabled = Boolean.parseBoolean(saved.getProperty("enabled", "true"));
        kiosk = Boolean.parseBoolean(saved.getProperty("kiosk", "true"));
        if (!EventMode.validPin(pin)) {
            pin = String.format(java.util.Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
            try { save(pin, title, enabled, kiosk); }
            catch (IOException exception) {
                throw new IllegalStateException("Cannot save the event recovery PIN: " + exception.getMessage(), exception);
            }
        }
    }

    synchronized String pin() { return pin; }
    synchronized String title() { return title; }
    synchronized boolean enabled() { return enabled; }
    synchronized boolean kiosk() { return kiosk; }

    synchronized void save(String value, String title, boolean enabled, boolean kiosk) throws IOException {
        if (!EventMode.validPin(value)) throw new IllegalArgumentException("Use an organizer PIN with 6–8 digits.");
        if (!EventMode.validTitle(title)) throw new IllegalArgumentException("Use an event name with 1–80 characters.");
        Files.createDirectories(file.getParent());
        boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
        if (posix) Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
        Path temporary = posix ? Files.createTempFile(file.getParent(), "event-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(file.getParent(), "event-", ".properties");
        try {
            Properties saved = new Properties();saved.setProperty("pin", value);
            saved.setProperty("title", title.trim());saved.setProperty("enabled", String.valueOf(enabled));
            saved.setProperty("kiosk", String.valueOf(kiosk));
            try (OutputStream output = Files.newOutputStream(temporary)) { saved.store(output, "SimpleCoil organizer recovery"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            pin = value;
            this.title = title.trim();this.enabled = enabled;this.kiosk = kiosk;
        } finally { Files.deleteIfExists(temporary); }
    }
}
