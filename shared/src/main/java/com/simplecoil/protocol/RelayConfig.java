package com.simplecoil.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Membership issued by the existing game host, independent of physical network addresses. */
public final class RelayConfig {
    public static final String WIRE = "relayconfig", NODE = "relaynode", P2P = "relayp2p", BLUETOOTH = "relaybt";
    public final UUID key, host, owner;
    public final Map<UUID, Member> members;

    public static final class Member {
        public final UUID id;
        public final InetAddress address;
        public final String p2p, bluetooth;
        public Member(UUID id, InetAddress address, String p2p) {
            this(id, address, p2p, "");
        }
        public Member(UUID id, InetAddress address, String p2p, String bluetooth) {
            if (id == null || !(address instanceof Inet4Address) || address.isAnyLocalAddress()
                    || address.isMulticastAddress() || address.getHostAddress().equals("255.255.255.255"))
                throw new IllegalArgumentException("Invalid relay member");
            this.id = id;this.address = address;
            this.p2p = validMac(p2p) ? p2p.toLowerCase(java.util.Locale.ROOT) : "";
            this.bluetooth = validMac(bluetooth) && !"02:00:00:00:00:00".equalsIgnoreCase(bluetooth)
                    ? bluetooth.toUpperCase(java.util.Locale.ROOT) : "";
        }
    }

    public RelayConfig(UUID key, UUID host, UUID owner, Map<UUID, Member> members) {
        if (key == null || host == null || owner == null || members == null
                || members.isEmpty() || members.size() > 33 || !members.containsKey(host)
                || !members.containsKey(owner)) throw new IllegalArgumentException("Invalid relay configuration");
        java.util.Set<InetAddress> addresses = new java.util.HashSet<>();
        for (Map.Entry<UUID, Member> entry : members.entrySet())
            if (!entry.getKey().equals(entry.getValue().id) || !addresses.add(entry.getValue().address))
                throw new IllegalArgumentException("Duplicate relay endpoint");
        this.key = key;this.host = host;this.owner = owner;
        this.members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public String encode() {
        StringBuilder wire = new StringBuilder("1|").append(key).append('|').append(host).append('|').append(owner);
        for (Member member : members.values()) wire.append('|').append(member.id).append(',')
                .append(member.address.getHostAddress()).append(',').append(member.p2p).append(',').append(member.bluetooth);
        return wire.toString();
    }

    public static RelayConfig decode(String wire) {
        if (wire == null || wire.length() > 4096) throw new IllegalArgumentException("Invalid relay configuration");
        String[] fields = wire.split("\\|", -1);
        if (fields.length < 5 || fields.length > 37 || !fields[0].equals("1"))
            throw new IllegalArgumentException("Invalid relay configuration");
        Map<UUID, Member> members = new LinkedHashMap<>();
        for (int i = 4; i < fields.length; i++) {
            String[] member = fields[i].split(",", -1);
            if ((member.length != 3 && member.length != 4) || !member[2].isEmpty() && !validMac(member[2])
                    || member.length == 4 && !member[3].isEmpty() && !validMac(member[3]))
                throw new IllegalArgumentException("Invalid relay member");
            UUID id = uuid(member[0]);
            if (members.put(id, new Member(id, ipv4(member[1]), member[2], member.length == 4 ? member[3] : "")) != null)
                throw new IllegalArgumentException("Duplicate relay member");
        }
        return new RelayConfig(uuid(fields[1]), uuid(fields[2]), uuid(fields[3]), members);
    }

    public boolean accepts(UUID node, InetAddress source) {
        Member member = members.get(node);return member != null && member.address.equals(source);
    }

    public static UUID uuid(String value) {
        if (!RoundRoster.validIdentity(value)) throw new IllegalArgumentException("Invalid relay identity");
        return UUID.fromString(value);
    }

    public static InetAddress ipv4(String value) {
        if (value == null) throw new IllegalArgumentException("Missing relay address");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid relay address");
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid relay address");
            int number = Integer.parseInt(parts[i]);
            if (number > 255) throw new IllegalArgumentException("Invalid relay address");
            address[i] = (byte) number;
        }
        try { return InetAddress.getByAddress(address); }
        catch (UnknownHostException impossible) { throw new IllegalArgumentException(impossible); }
    }

    public static boolean validMac(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}");
    }
}
