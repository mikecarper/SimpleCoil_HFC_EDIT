package com.simplecoil.simplecoil;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded end-of-round receipts that outlive gameplay and lobby socket changes. */
final class RoundEndSync {
    static final String END = "ROUNDEND:";
    static final String ACK = "ROUNDENDACK:";
    static final long RETRY_WINDOW_MS = 30_000;
    static final long RETAIN_MS = 10 * 60_000;
    static final int MAX_ROUNDS = 8;

    static final class Packet {
        final String token;
        final long votes;
        final boolean acknowledgement;

        Packet(String token, long votes, boolean acknowledgement) {
            this.token = token;
            this.votes = votes;
            this.acknowledgement = acknowledgement;
        }
    }

    static final class Delivery {
        final String message;
        final InetAddress destination;
        Delivery(String message, InetAddress destination) {
            this.message = message;
            this.destination = destination;
        }
    }

    private static final class Receipt {
        final String token;
        final long votes;
        final long createdAt;
        final Map<InetAddress, Integer> participants;
        final Map<InetAddress, Integer> pending = new HashMap<>();
        final Map<InetAddress, Long> lastRepair = new HashMap<>();
        long nextRetry;

        Receipt(String token, long votes, Map<InetAddress, Integer> participants,
                InetAddress local, long now, boolean deliver) {
            this.token = token;
            this.votes = votes;
            this.participants = new HashMap<>(participants);
            createdAt = now;
            nextRetry = now;
            if (deliver) pending.putAll(participants);
            pending.remove(local);
        }

        String message() { return END + NetMsg.NETWORK_VERSION + ":" + token + ":" + Long.toHexString(votes); }
    }

    private final Map<String, Receipt> receipts = new LinkedHashMap<>();

    static Packet parse(String message) {
        if (message == null) return null;
        String[] fields = message.split(":", -1);
        boolean ack = fields.length == 3 && "ROUNDENDACK".equals(fields[0]);
        if ((!ack && (fields.length != 4 || !"ROUNDEND".equals(fields[0])))
                || !NetMsg.NETWORK_VERSION.equals(fields[1]) || !validToken(fields[2])) return null;
        if (ack) return new Packet(fields[2], 0, true);
        if (!fields[3].matches("[0-9a-f]{1,8}")) return null;
        return new Packet(fields[2], Long.parseLong(fields[3], 16), false);
    }

    static boolean validToken(String token) {
        try { return token != null && UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException e) { return false; }
    }

    static String ack(String token) { return ACK + NetMsg.NETWORK_VERSION + ":" + token; }

    synchronized boolean remember(String token, long votes, Map<InetAddress, Integer> participants,
                                  InetAddress local, long now, boolean deliver) {
        prune(now);
        if (!validToken(token) || votes < 0 || (votes & ~EndGameVotes.VALID_MASK) != 0
                || receipts.containsKey(token)) return false;
        while (receipts.size() >= MAX_ROUNDS) receipts.remove(receipts.keySet().iterator().next());
        receipts.put(token, new Receipt(token, votes, participants, local, now, deliver));
        return true;
    }

    synchronized boolean contains(String token, long now) {
        prune(now);
        return receipts.containsKey(token);
    }

    synchronized boolean knows(String token, InetAddress sender, long now) {
        prune(now);
        Receipt receipt = receipts.get(token);
        return receipt != null && receipt.participants.containsKey(sender);
    }

    synchronized void acknowledge(String token, InetAddress sender, long now) {
        prune(now);
        Receipt receipt = receipts.get(token);
        if (receipt != null) receipt.pending.remove(sender);
    }

    synchronized List<Delivery> due(long now) {
        prune(now);
        List<Delivery> deliveries = new ArrayList<>();
        for (Receipt receipt : receipts.values()) {
            if (now - receipt.createdAt >= RETRY_WINDOW_MS) receipt.pending.clear();
            if (receipt.pending.isEmpty() || now < receipt.nextRetry) continue;
            for (InetAddress destination : receipt.pending.keySet())
                deliveries.add(new Delivery(receipt.message(), destination));
            receipt.nextRetry = now + (now - receipt.createdAt < 1000 ? 100 : 1000);
        }
        return deliveries;
    }

    synchronized boolean retrying(long now) {
        prune(now);
        for (Receipt receipt : receipts.values())
            if (!receipt.pending.isEmpty() && now - receipt.createdAt < RETRY_WINDOW_MS) return true;
        return false;
    }

    /** Repair a returning player's stale game even after active retries expired. */
    synchronized Delivery repair(String token, InetAddress sender, long now) {
        prune(now);
        Receipt receipt = receipts.get(token);
        if (receipt == null || !receipt.participants.containsKey(sender)) return null;
        Long previous = receipt.lastRepair.get(sender);
        if (previous != null && now - previous < 1000) return null;
        receipt.lastRepair.put(sender, now);
        return new Delivery(receipt.message(), sender);
    }

    private void prune(long now) {
        Iterator<Receipt> iterator = receipts.values().iterator();
        while (iterator.hasNext())
            if (now - iterator.next().createdAt >= RETAIN_MS) iterator.remove();
    }
}
