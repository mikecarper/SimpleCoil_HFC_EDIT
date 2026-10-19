package com.simplecoil.laptophost;

import com.simplecoil.protocol.RoundRoster;
import com.simplecoil.protocol.GunBatteryHistory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Laptop-owned people and phone bindings. A replacement phone is not a new person. */
final class PlayerDirectory {
    final Map<String, Profile> profiles = new LinkedHashMap<>();
    private final Map<String, String> devices = new LinkedHashMap<>();
    private final Path file;

    PlayerDirectory(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (!Files.exists(file)) return;
        if (Files.size(file) > 4_194_304) throw new IOException("Player directory is too large: " + file);
        try {
            Map<?, ?> saved = (Map<?, ?>) LaptopHost.Json.parse(Files.readString(file));
            if (!Long.valueOf(1).equals(saved.get("version"))) throw new IllegalArgumentException("Unsupported player directory version");
            for (Object value : (List<?>) saved.get("profiles")) {
                Profile profile = Profile.read((Map<?, ?>) value);
                if (profiles.put(profile.id, profile) != null) throw new IllegalArgumentException("Duplicate player profile");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) saved.get("devices")).entrySet()) {
                String device = (String) entry.getKey(), profile = (String) entry.getValue();
                if (!RoundRoster.validIdentity(device) || !profiles.containsKey(profile)
                        || devices.containsValue(profile)) throw new IllegalArgumentException("Invalid phone binding");
                devices.put(device, profile);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cannot read saved players at " + file + ": " + exception.getMessage(), exception);
        }
    }

    Profile forDevice(String device, String name) {
        String known = devices.get(device);
        if (known != null) return profiles.get(known);
        String id = profiles.containsKey(device) ? UUID.randomUUID().toString() : device;
        Profile profile = new Profile(id, name);
        profiles.put(id, profile);devices.put(device, id);
        return profile;
    }

    void bind(String profile, String device) {
        if (!profiles.containsKey(profile) || !RoundRoster.validIdentity(device))
            throw new IllegalArgumentException("Invalid player transfer");
        devices.entrySet().removeIf(entry -> entry.getValue().equals(profile));
        devices.put(device, profile);
    }

    void save() throws IOException {
        List<Object> savedProfiles = new ArrayList<>();
        for (Profile profile : profiles.values()) savedProfiles.add(profile.toJson());
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("version", 1);saved.put("profiles", savedProfiles);saved.put("devices", devices);
        Files.createDirectories(file.getParent());
        boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
        Path temporary = posix ? Files.createTempFile(file.getParent(), "players-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(file.getParent(), "players-", ".json");
        try {
            Files.writeString(temporary, LaptopHost.Json.stringify(saved), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static final class Profile {
        final String id;
        String name;
        int team, lastTeam;
        boolean hostNamed;
        Boolean benched;
        Map<String, Object> settings;
        int totalKills, totalDeaths, lastKills, lastDeaths, lastShots, lastHits;
        String lastRound;
        final GunBatteryHistory gunBatteryRounds = new GunBatteryHistory(100);

        Profile(String id, String name) { this.id = id;this.name = name; }

        void record(String round, int kills, int deaths, int shots, int hits) {
            if (round == null) return;
            boolean sameRound = round.equals(lastRound);
            totalKills = (int) Math.min(Integer.MAX_VALUE, (long) totalKills + Math.max(0, kills - (sameRound ? lastKills : 0)));
            totalDeaths = (int) Math.min(Integer.MAX_VALUE, (long) totalDeaths + Math.max(0, deaths - (sameRound ? lastDeaths : 0)));
            lastRound = round;lastKills = kills;lastDeaths = deaths;lastShots = shots;lastHits = hits;
        }

        Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("identity", id);value.put("name", name);value.put("team", team);
            value.put("lastTeam", lastTeam);
            value.put("hostNamed", hostNamed);value.put("benched", benched);value.put("settings", settings);
            value.put("totalKills", totalKills);value.put("totalDeaths", totalDeaths);
            value.put("lastKills", lastKills);value.put("lastDeaths", lastDeaths);
            value.put("lastShots", lastShots);value.put("lastHits", lastHits);value.put("lastRound", lastRound);
            value.put("gunBatteryRounds", gunBatteryRounds.snapshot());
            return value;
        }

        @SuppressWarnings("unchecked")
        static Profile read(Map<?, ?> value) {
            String id = (String) value.get("identity"), name = (String) value.get("name");
            if (!RoundRoster.validIdentity(id) || name == null || name.trim().isEmpty()
                    || name.length() > 20 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid saved player");
            Profile profile = new Profile(id, name);
            profile.team = number(value, "team");
            profile.lastTeam = value.containsKey("lastTeam") ? number(value, "lastTeam") : profile.team;
            if (profile.team > 2) throw new IllegalArgumentException("Invalid saved team");
            profile.hostNamed = Boolean.TRUE.equals(value.get("hostNamed"));
            profile.benched = value.get("benched") instanceof Boolean ? (Boolean) value.get("benched") : null;
            profile.settings = value.get("settings") == null ? null : (Map<String, Object>) value.get("settings");
            profile.totalKills = number(value, "totalKills");profile.totalDeaths = number(value, "totalDeaths");
            profile.lastKills = number(value, "lastKills");profile.lastDeaths = number(value, "lastDeaths");
            profile.lastShots = number(value, "lastShots");profile.lastHits = number(value, "lastHits");
            profile.lastRound = (String) value.get("lastRound");
            if (profile.lastRound != null && !RoundRoster.validIdentity(profile.lastRound))
                throw new IllegalArgumentException("Invalid saved round");
            if (value.containsKey("gunBatteryRounds"))
                profile.gunBatteryRounds.merge(value.get("gunBatteryRounds"), id);
            return profile;
        }

        private static int number(Map<?, ?> value, String key) {
            Object number = value.get(key);
            if (!(number instanceof Number) || ((Number) number).doubleValue() < 0
                    || ((Number) number).doubleValue() > Integer.MAX_VALUE
                    || ((Number) number).doubleValue() != ((Number) number).intValue())
                throw new IllegalArgumentException("Invalid saved " + key);
            return ((Number) number).intValue();
        }
    }
}
