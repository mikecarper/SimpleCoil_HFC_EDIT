package com.simplecoil.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lowest calibrated, displayed voltage per physical gun and round. JSON-library neutral. */
public final class GunBatteryHistory {
    private final int limit;
    private final LinkedHashMap<String, Round> rounds = new LinkedHashMap<>();
    private Round current;

    public GunBatteryHistory(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid history limit");
        this.limit = limit;
    }

    public void begin(String token, String player, long startedAt) {
        if (!RoundRoster.validIdentity(token) || !RoundRoster.validIdentity(player) || startedAt <= 0)
            throw new IllegalArgumentException("Invalid battery round");
        String key = player + ":" + token;
        current = rounds.get(key);
        if (current == null) {
            current = new Round(token, player, startedAt);
            rounds.put(key, current);
            trim();
        }
    }

    public void finish() { current = null; }

    /** Unknown and uncalibrated readings cannot lower a round's minimum. */
    public boolean observe(String address, int slot, int type, int voltageTenths) {
        if (current == null || !validGun(address) || slot < 0 || slot > 1 || type != 2
                || voltageTenths < 19 || voltageTenths > 61) return false;
        String id = address.toUpperCase(Locale.ROOT);
        Gun previous = current.guns.get(id);
        if (previous != null && previous.minimum <= voltageTenths) return false;
        if (previous == null && current.guns.size() >= 8) return false;
        current.guns.put(id, new Gun(id, slot, voltageTenths));
        return true;
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Round round : rounds.values()) result.add(round.json());
        return result;
    }

    /** Validate the whole report before merging; retries only lower existing minima. */
    public boolean merge(Object value, String expectedPlayer) {
        if (!(value instanceof List) || ((List<?>) value).size() > 100)
            throw new IllegalArgumentException("Invalid gun battery history");
        List<Round> incoming = new ArrayList<>();
        for (Object item : (List<?>) value) incoming.add(read(item));
        boolean changed = false;
        for (Round round : incoming) {
            // A phone can retain readings for its previous player after a transfer.
            if (expectedPlayer != null && !expectedPlayer.equals(round.player)) continue;
            String key = round.player + ":" + round.token;
            Round existing = rounds.get(key);
            if (existing == null) { rounds.put(key, round);changed = true;continue; }
            for (Gun gun : round.guns.values()) {
                Gun previous = existing.guns.get(gun.id);
                if ((previous == null && existing.guns.size() < 8)
                        || previous != null && gun.minimum < previous.minimum) {
                    existing.guns.put(gun.id, gun);changed = true;
                }
            }
        }
        trim();
        return changed;
    }

    private void trim() {
        while (rounds.size() > limit) rounds.remove(rounds.keySet().iterator().next());
    }

    public static boolean validGun(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}");
    }

    private static Round read(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Invalid battery round");
        Map<?, ?> json = (Map<?, ?>) value;
        Object token = json.get("round"), player = json.get("player");
        if (!(token instanceof String) || !RoundRoster.validIdentity((String) token)
                || !(player instanceof String) || !RoundRoster.validIdentity((String) player))
            throw new IllegalArgumentException("Invalid battery round identity");
        long startedAt = number(json.get("startedAt"), 1, Long.MAX_VALUE);
        Round round = new Round((String) token, (String) player, startedAt);
        if (!(json.get("guns") instanceof List) || ((List<?>) json.get("guns")).size() > 8)
            throw new IllegalArgumentException("Invalid round guns");
        for (Object item : (List<?>) json.get("guns")) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Invalid round gun");
            Map<?, ?> gun = (Map<?, ?>) item;
            Object id = gun.get("id");
            if (!(id instanceof String) || !validGun((String) id)
                    || number(gun.get("type"), 2, 2) != 2)
                throw new IllegalArgumentException("Invalid calibrated gun");
            Gun record = new Gun(((String) id).toUpperCase(Locale.ROOT),
                    (int) number(gun.get("slot"), 0, 1),
                    (int) number(gun.get("minimumTenths"), 19, 61));
            if (round.guns.put(record.id, record) != null)
                throw new IllegalArgumentException("Duplicate round gun");
        }
        return round;
    }

    private static long number(Object value, long minimum, long maximum) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid battery number");
        Number number = (Number) value;
        long result = number.longValue();
        if (number.doubleValue() != result || result < minimum || result > maximum)
            throw new IllegalArgumentException("Invalid battery number");
        return result;
    }

    private static final class Round {
        final String token, player;
        final long startedAt;
        final Map<String, Gun> guns = new LinkedHashMap<>();
        Round(String token, String player, long startedAt) {
            this.token = token;this.player = player;this.startedAt = startedAt;
        }
        Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("round", token);json.put("player", player);json.put("startedAt", startedAt);
            List<Object> records = new ArrayList<>();
            for (Gun gun : guns.values()) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("id", gun.id);record.put("slot", gun.slot);record.put("type", 2);
                record.put("minimumTenths", gun.minimum);records.add(record);
            }
            json.put("guns", records);return json;
        }
    }

    private static final class Gun {
        final String id;
        final int slot, minimum;
        Gun(String id, int slot, int minimum) { this.id = id;this.slot = slot;this.minimum = minimum; }
    }
}
