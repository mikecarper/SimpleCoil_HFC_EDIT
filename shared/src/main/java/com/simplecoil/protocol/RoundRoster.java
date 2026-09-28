package com.simplecoil.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Round-scoped seats. A seat may be reused, but its old packets may not. */
public final class RoundRoster {
    public static final int CAPACITY = 32;
    public static final int HISTORY_LIMIT = 512;
    private static final int MAX_SCORE = Integer.MAX_VALUE / CAPACITY;
    public final String token;
    private final Member[] seats = new Member[CAPACITY + 1];
    private final int[] generations = new int[CAPACITY + 1];
    private final Map<String, Member> people = new LinkedHashMap<>();

    public static final class Member {
        public final String identity;
        public int id, team, generation, kills, deaths, captures;
        public boolean active, infected;
        public Member(String identity, int id, int team, int generation) {
            this.identity = identity;
            this.id = id;
            this.team = team;
            this.generation = generation;
            active = true;
        }
    }

    public RoundRoster(String token) {
        if (!validIdentity(token)) throw new IllegalArgumentException("Invalid round token");
        this.token = token;
    }

    public static boolean validIdentity(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }

    public synchronized Member seat(int id) { return id > 0 && id <= CAPACITY ? seats[id] : null; }
    public synchronized Member person(String identity) { return people.get(identity); }
    public synchronized int generation(int id) {
        return id > 0 && id <= CAPACITY ? generations[id] : 0;
    }
    public synchronized boolean accepts(int id, int generation) {
        Member member = seat(id);
        return member != null && member.generation == generation;
    }
    public synchronized List<Member> members() { return new ArrayList<>(people.values()); }

    /** Used when starting a round or decoding a validated full TCP snapshot. */
    public synchronized void restore(Member member) {
        if (!validIdentity(member.identity) || member.id < 1 || member.id > CAPACITY
                || member.team < 0 || member.team > 4 || member.generation < 0
                || member.generation > 255 || member.kills < 0 || member.kills > MAX_SCORE
                || member.deaths < 0 || member.deaths > MAX_SCORE || member.captures < 0
                || member.captures > 255 || people.containsKey(member.identity)
                || people.size() >= HISTORY_LIMIT)
            throw new IllegalArgumentException("Invalid round member");
        people.put(member.identity, member);
        if (seats[member.id] == null || member.generation > generations[member.id]) {
            seats[member.id] = member;
            generations[member.id] = member.generation;
        } else if (member.generation == generations[member.id]) {
            throw new IllegalArgumentException("Duplicate round seat");
        }
    }

    /** team is authoritative: returning players cannot switch sides by leaving. */
    public synchronized Member join(String identity, int team, int teamCount,
                                    boolean boss, boolean infection, int lives) {
        return join(identity, team, teamCount, boss, infection, lives, false, false);
    }

    public synchronized Member join(String identity, int team, int teamCount,
                                    boolean boss, boolean infection, int lives,
                                    boolean balanced, boolean resumeActive) {
        if (!validIdentity(identity) || team < 1 || team > teamCount) return null;
        Member previous = people.get(identity);
        if (previous != null && (previous.active && !resumeActive || (lives > 0 && previous.deaths >= lives)
                || (infection ? previous.infected ? 1 : 2 : previous.team) != team)) return null;
        if (previous == null && people.size() >= HISTORY_LIMIT) return null;
        int selected = 0;
        for (int id = 1; id <= CAPACITY; id++) {
            Member occupant = seats[id];
            if (occupant != null && occupant.active && !(resumeActive && occupant == previous)
                    || generations[id] >= 255) continue;
            boolean eligible = balanced || (infection ? id != 1 || previous != null && previous.id == 1
                    : boss ? (team == 1 ? id == 1 : id > 1)
                    : (id - 1) / (CAPACITY / teamCount) + 1 == team);
            if (eligible && (selected == 0 || previous != null && previous.id == id)) selected = id;
            if (selected == id && previous != null && previous.id == id) break;
        }
        if (selected == 0) return null;
        // Remove the old seat if this returning person has moved to another one.
        // Its counters now travel with the person, never with both seats.
        if (previous != null && seats[previous.id] == previous) seats[previous.id] = null;
        Member joined = new Member(identity, selected, team, ++generations[selected]);
        if (previous != null) {
            joined.kills = previous.kills;
            joined.deaths = previous.deaths;
            joined.captures = previous.captures;
            joined.infected = previous.infected;
        }
        if (infection) joined.infected = team == 1;
        seats[selected] = joined;
        people.put(identity, joined);
        return joined;
    }

    public synchronized void leave(int id) {
        Member member = seat(id);
        if (member != null) member.active = false;
    }

    public synchronized void update(int id, int generation, int kills, int deaths,
                                    int captures, boolean infected) {
        Member member = seat(id);
        if (member == null || member.generation != generation) return;
        member.kills = Math.max(member.kills, Math.min(MAX_SCORE, Math.max(0, kills)));
        member.deaths = Math.max(member.deaths, Math.min(MAX_SCORE, Math.max(0, deaths)));
        member.captures = Math.max(member.captures, Math.min(255, Math.max(0, captures)));
        member.infected |= infected;
    }

    /** Contributions from players whose seats have since been reassigned. */
    public synchronized int archivedScore(int team, boolean captures) {
        long total = 0;
        for (Member member : people.values())
            if (seats[member.id] != member && member.team == team)
                total += captures ? member.captures : member.kills;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public synchronized String encode() {
        StringBuilder value = new StringBuilder(token).append(';');
        for (int id = 1; id <= CAPACITY; id++) value.append(generations[id]).append(',');
        for (Member m : people.values()) value.append(';').append(m.identity).append(',')
                .append(m.id).append(',').append(m.team).append(',').append(m.generation).append(',')
                .append(m.kills).append(',').append(m.deaths).append(',').append(m.captures).append(',')
                .append(m.active ? 1 : 0).append(',').append(m.infected ? 1 : 0);
        return value.toString();
    }

    public static RoundRoster decode(String value) {
        if (value == null || value.length() > 60000) throw new IllegalArgumentException("Invalid roster");
        String[] parts = value.split(";", -1);
        if (parts.length < 2 || parts.length > HISTORY_LIMIT + 2)
            throw new IllegalArgumentException("Invalid roster size");
        RoundRoster roster = new RoundRoster(parts[0]);
        String[] epochs = parts[1].split(",", -1);
        if (epochs.length != CAPACITY + 1 || !epochs[CAPACITY].isEmpty())
            throw new IllegalArgumentException("Invalid seat generations");
        for (int n = 2; n < parts.length; n++) {
            String[] fields = parts[n].split(",", -1);
            if (fields.length != 9) throw new IllegalArgumentException("Invalid member fields");
            Member m = new Member(fields[0], Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
            m.kills = Integer.parseInt(fields[4]); m.deaths = Integer.parseInt(fields[5]);
            m.captures = Integer.parseInt(fields[6]);
            if (!("0".equals(fields[7]) || "1".equals(fields[7]))
                    || !("0".equals(fields[8]) || "1".equals(fields[8])))
                throw new IllegalArgumentException("Invalid member flags");
            m.active = "1".equals(fields[7]); m.infected = "1".equals(fields[8]);
            roster.restore(m);
        }
        for (int id = 1; id <= CAPACITY; id++) {
            int generation = Integer.parseInt(epochs[id - 1]);
            if (generation < roster.generations[id] || generation > 255)
                throw new IllegalArgumentException("Invalid generation");
            roster.generations[id] = generation;
            if (roster.seats[id] != null && roster.seats[id].generation < generation)
                roster.seats[id] = null;
        }
        return roster;
    }
}
