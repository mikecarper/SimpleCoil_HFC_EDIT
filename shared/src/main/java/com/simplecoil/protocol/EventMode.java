package com.simplecoil.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Optional laptop event controls; older protocol-30 phones ignore these fields. */
public final class EventMode {
    public static final String APP_NAME = "LAZER GUN RUN";
    public static final String TITLE = "eventname";
    public static final String ENABLED = "eventmode";
    public static final String PINNING = "eventpinning";
    public static final String PIN_HASH = "eventpinhash";
    public static final String REVISION = "eventrevision";
    public static final String CAPABILITY = "eventcontrol";
    public static final String LOCK_STATUS = "eventlock";
    public static final String CUSTOM_RULES = "hostcustomrules";
    public static final String ASSIGNED_TEAMS = "hostassignedteams";
    public static final String PLAYER_IDENTITY = "hostplayeridentity";
    public static final String DEVICE_IDENTITY = "deviceidentity";
    public static final String TRANSFERRED_OUT = "playertransferred";
    public static final String BATTERY = "batterystatus";
    public static final String BATTERY_REQUEST = "requestbatterystatus";
    public static final int DEFAULT_PIN_DIGITS = 2;
    public static final int MIN_PIN_DIGITS = 2;
    public static final int MAX_PIN_DIGITS = 6;
    public static final int LEGACY_MAX_PIN_DIGITS = 8;

    private EventMode() { }

    public static boolean validPin(String pin) {
        return numericPin(pin, MAX_PIN_DIGITS);
    }

    /** Retain existing organizer PINs and offline unlocks from older releases. */
    public static boolean validStoredPin(String pin) {
        return numericPin(pin, LEGACY_MAX_PIN_DIGITS);
    }

    private static boolean numericPin(String pin, int maximum) {
        if (pin == null || pin.length() < MIN_PIN_DIGITS || pin.length() > maximum) return false;
        for (int index = 0; index < pin.length(); index++)
            if (pin.charAt(index) < '0' || pin.charAt(index) > '9') return false;
        return true;
    }

    public static boolean validTitle(String title) {
        if (title == null || title.trim().isEmpty() || title.length() > 80) return false;
        // String.chars() is unavailable on Android 5.x, including event phones.
        for (int index = 0; index < title.length(); index++)
            if (Character.isISOControl(title.charAt(index))) return false;
        return true;
    }

    public static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    public static String pinHash(String identity, String pin) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("SimpleCoil event unlock:" + identity + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static boolean matchesPin(String identity, String pin, String expectedHash) {
        return validStoredPin(pin) && validHash(expectedHash) && MessageDigest.isEqual(
                pinHash(identity, pin).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
