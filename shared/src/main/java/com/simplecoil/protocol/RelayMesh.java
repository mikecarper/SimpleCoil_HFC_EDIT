package com.simplecoil.protocol;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded game-only overlay over Wi-Fi and nearby Bluetooth links. */
public final class RelayMesh implements Closeable {
    private static final int MAGIC = 0x4c475231, MAX_PAYLOAD = 8192, HOPS = 6;
    private static final int PING = 0, UDP = 1, OPEN = 2, OPEN_ACK = 3, DATA = 4, ACK = 5, CLOSE = 6;
    private static final long ROUTE_LIFETIME_MS = 3500;
    private static final UUID ZERO = new UUID(0, 0);
    public interface Listener {
        void datagram(InetAddress source, InetAddress destination, int port, byte[] payload);
        boolean accept(Socket socket, int port);
        default void linkOpened(UUID peer) { }
        default void linkClosed(UUID peer, String reason) { }
        default void linkCongested(UUID peer) { }
        default void handshakeFailed(String reason) { }
        default void streamAborted(String reason) { }
    }
    private final UUID self;
    private final Listener listener;
    private final ServerSocket server;
    private volatile RelayConfig config;
    private volatile boolean closed;
    private volatile long hostSeenAt;
    // The origin UUID and cohort key survive app updates. Starting again at
    // zero would collide with neighbors' retained duplicate history. Android's
    // system monotonic clock advances across processes within one boot; its
    // nanosecond seed also makes an accidental overlap after reboot negligible.
    private final AtomicLong sequence = new AtomicLong(System.nanoTime() & Long.MAX_VALUE);
    private final Semaphore handshakes = new Semaphore(8);
    private final CopyOnWriteArrayList<Link> links = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, VirtualSocket> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LearnedRoute> routes = new ConcurrentHashMap<>();
    private final Set<String> connecting = Collections.synchronizedSet(new HashSet<>());
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "LGR relay clock"));
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> daemon(r, "LGR relay link"));
    private long lastPing;

    public RelayMesh(UUID self, RelayConfig config, InetSocketAddress bind, Listener listener) throws IOException {
        if (!config.members.containsKey(self)) throw new IllegalArgumentException("Relay self is not admitted");
        this.self = self;this.config = config;this.listener = listener;
        server = new ServerSocket();server.setReuseAddress(true);server.bind(bind, 16);
        workers.execute(() -> {
            while (!closed) {
                try { handshake(server.accept()); }
                catch (IOException failure) { if (closed) return; }
            }
        });
        ticker.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable task, String name) { Thread t = new Thread(task, name);t.setDaemon(true);return t; }
    private static long now() { return System.nanoTime() / 1_000_000; }
    public int port() { return server.getLocalPort(); }
    public int links() { return links.size(); }
    public boolean hasPeer(UUID peer) { for (Link link : links) if (link.peer.equals(peer)) return true;return false; }
    public boolean hostReachable() { return !closed && (self.equals(config.host) || now() - hostSeenAt < 3500 && hostSeenAt != 0); }
    public InetAddress localAddress() { return config.members.get(self).address; }

    public void update(RelayConfig next) {
        if (!config.key.equals(next.key) || !next.members.containsKey(self)
                || !next.members.get(self).address.equals(localAddress()))
            throw new IllegalArgumentException("A changed relay session requires a new mesh");
        synchronized (routes) {
            config = next;
            for (Map.Entry<UUID, LearnedRoute> entry : routes.entrySet())
                if (!next.accepts(entry.getKey(), entry.getValue().source)) routes.remove(entry.getKey(), entry.getValue());
        }
        for (Link link : links) if (!next.accepts(link.peer, link.logical)) link.close("membership changed");
    }

    /** Nonblocking and idempotent for a physical endpoint. */
    public void connect(InetSocketAddress endpoint) {
        if (closed || links.size() >= 40) return;
        String address = endpoint.toString();
        for (Link link : links) if (address.equals(link.endpoint)) return;
        if (!connecting.add(address)) return;
        try { workers.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(endpoint, 1500);
                try { handshake(socket, address); }
                catch (IOException | RuntimeException failure) {
                    listener.handshakeFailed(failure.getClass().getSimpleName());throw failure;
                }
            } catch (IOException | RuntimeException failure) { closeQuietly(socket); }
            finally { connecting.remove(address); }
        }); } catch (RejectedExecutionException stopped) { connecting.remove(address); }
    }

    public void attach(Socket socket) { handshake(socket); }
    private void handshake(Socket socket) {
        if (closed || !handshakes.tryAcquire()) { closeQuietly(socket);return; }
        try { workers.execute(() -> {
        try { handshake(socket, null); }
        catch (IOException | RuntimeException failure) {
            listener.handshakeFailed(failure.getClass().getSimpleName());closeQuietly(socket);
        }
        finally { handshakes.release(); }
        }); } catch (RejectedExecutionException stopped) { handshakes.release();closeQuietly(socket); }
    }

    private void handshake(Socket socket, String endpoint) throws IOException {
        socket.setSoTimeout(3000);socket.setTcpNoDelay(true);socket.setKeepAlive(true);
        // Flush each complete frame together instead of sending its primitive
        // header fields as separate writes on an RFCOMM radio link.
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                socket.getOutputStream(), MAX_PAYLOAD + 128));
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.writeInt(MAGIC);writeUuid(out, config.key);writeUuid(out, self);out.write(localAddress().getAddress());out.flush();
        if (in.readInt() != MAGIC || !readUuid(in).equals(config.key)) throw new IOException("Wrong relay session");
        UUID peer = readUuid(in);byte[] ip = new byte[4];in.readFully(ip);InetAddress logical = InetAddress.getByAddress(ip);
        if (closed || peer.equals(self) || !config.accepts(peer, logical) || links.size() >= 40)
            throw new IOException("Unknown relay peer");
        socket.setSoTimeout(0);
        Link link = new Link(socket, in, out, peer, logical, endpoint);links.add(link);
        listener.linkOpened(peer);
        workers.execute(link::read);workers.execute(link::write);
    }

    public boolean datagram(InetAddress destination, int port, byte[] payload) {
        if (closed || destination == null || !(destination instanceof Inet4Address) || payload == null
                || payload.length > 1472 || port < 1 || port > 65535 || links.isEmpty()) return false;
        publish(UDP, destination, port, ZERO, 0, payload);return true;
    }

    public Socket open(InetAddress destination, int port, int timeout) throws IOException {
        if (closed || !hostReachable() || !destination.equals(config.members.get(config.host).address))
            throw new ConnectException("Game host is not reachable over the relay");
        if (streams.size() >= 64) throw new ConnectException("Too many game relay streams");
        VirtualSocket socket = new VirtualSocket(UUID.randomUUID(), destination, port, false);streams.put(socket.id, socket);
        long end = now() + timeout;
        synchronized (socket) {
            while (!socket.opened && !socket.socketClosed && now() < end) {
                socket.announceOpen();socket.waitFor(Math.min(250, Math.max(1, end - now())));
            }
            if (!socket.opened || socket.socketClosed) { socket.close();throw new ConnectException("Game relay connection timed out"); }
        }
        return socket;
    }

    private void tick() {
        if (closed) return;
        long time = now();
        if (time - lastPing >= 1000) { lastPing = time;publish(PING, RelayConfig.ipv4("255.255.255.255"), 0, ZERO, 0, new byte[0]); }
        for (VirtualSocket socket : streams.values()) {
            try { socket.retry(time); }
            catch (IOException failure) { socket.abort("retry: " + failure.getClass().getSimpleName()); }
        }
    }

    private void publish(int type, InetAddress destination, int port, UUID stream, long offset, byte[] payload) {
        Frame frame = new Frame(type, self, sequence.incrementAndGet(), localAddress(), destination,
                port, stream, offset, payload.clone(), HOPS);
        remember(frame);forward(frame, null);
    }

    private boolean remember(Frame frame) {
        String key = frame.origin + ":" + frame.sequence;
        synchronized (seen) {
            if (seen.containsKey(key)) return false;
            seen.put(key, true);
            while (seen.size() > 8192) seen.remove(seen.keySet().iterator().next());
            return true;
        }
    }

    private void receive(Frame frame, Link incoming) {
        RelayConfig current = config;
        if (closed || !current.accepts(frame.origin, frame.source) || frame.origin.equals(self)
                || frame.hops < 1 || frame.hops > HOPS) return;
        long time = now();incoming.lastReadAt = time;
        learnRoute(frame, incoming, time);
        if (!remember(frame)) return;
        if (frame.origin.equals(current.host)) hostSeenAt = now();
        boolean broadcast = frame.destination.getHostAddress().equals("255.255.255.255");
        if (broadcast || frame.destination.equals(localAddress())) {
            if (frame.type == UDP) {
                if (frame.payload.length <= 1472) listener.datagram(frame.source, frame.destination, frame.port, frame.payload);
            } else if (frame.type != PING) handleStream(frame);
            // A delivered unicast has reached its endpoint. Sending it onward
            // turns every host reply and ACK into needless Bluetooth traffic.
            if (!broadcast) return;
        }
        if (frame.hops > 1) forward(frame.nextHop(), incoming);
    }

    private void handleStream(Frame frame) {
        VirtualSocket socket = streams.get(frame.stream);
        if (frame.type == OPEN) {
            if (socket == null && streams.size() < 64) {
                VirtualSocket created = new VirtualSocket(frame.stream, frame.source, frame.port, true);
                socket = streams.putIfAbsent(frame.stream, created);
                if (socket == null) {
                    socket = created;
                    if (!listener.accept(socket, frame.port)) { socket.abort("port rejected");return; }
                }
            }
            if (socket != null && socket.remote.equals(frame.source) && !socket.socketClosed)
                publish(OPEN_ACK, frame.source, frame.port, frame.stream, 0, new byte[0]);
            return;
        }
        if (socket == null || !socket.remote.equals(frame.source) || socket.remotePort != frame.port) return;
        synchronized (socket) {
            switch (frame.type) {
                case OPEN_ACK: socket.opened = true;socket.notifyAll();break;
                case DATA: socket.receive(frame.offset, frame.payload);break;
                case ACK: socket.acknowledge(frame.offset);break;
                case CLOSE: socket.remoteEnd = frame.offset;socket.notifyAll();break;
                default: break;
            }
        }
    }

    private void forward(Frame frame, Link incoming) {
        if (!frame.destination.getHostAddress().equals("255.255.255.255")) {
            long time = now();
            // An old native TCP connection can remain established after its
            // phone leaves Wi-Fi coverage. Require recent traffic before using
            // it as the preferred direct route.
            for (Link link : links)
                if (link != incoming && link.logical.equals(frame.destination) && link.routable(time)) {
                    if (enqueue(link, frame)) return;
                }
            for (RelayConfig.Member member : config.members.values()) {
                if (!member.address.equals(frame.destination)) continue;
                LearnedRoute route = routes.get(member.id);
                if (route != null && route.via != incoming && time - route.seenAt < ROUTE_LIFETIME_MS
                        && route.via.routable(time) && config.accepts(member.id, route.source)) {
                    if (enqueue(route.via, frame)) return;
                }
                break;
            }
        }
        for (Link link : links) {
            if (link == incoming) continue;
            enqueue(link, frame);
        }
    }

    private boolean enqueue(Link link, Frame frame) {
        // End-to-end retries recover a copy deferred by a full physical queue.
        boolean accepted = link.queue.offer(frame);
        if (!accepted && frame.type != UDP && frame.type != PING) link.congested();
        return accepted;
    }

    private void learnRoute(Frame frame, Link incoming, long time) {
        synchronized (routes) {
            if (!config.accepts(frame.origin, frame.source) || incoming.stopped) return;
            LearnedRoute previous = routes.get(frame.origin);
            if (previous != null && time - previous.seenAt < ROUTE_LIFETIME_MS && previous.via.fresh(time)) {
                if (frame.sequence < previous.sequence
                        || frame.sequence == previous.sequence && frame.hops <= previous.hops) return;
            }
            // Learning precedes duplicate suppression: a later copy of the
            // same frame can reveal a shorter path without redelivering it.
            routes.put(frame.origin, new LearnedRoute(incoming, frame.source, time, frame.sequence, frame.hops));
        }
    }

    private final class LearnedRoute {
        final Link via;final InetAddress source;final long seenAt, sequence;final int hops;
        LearnedRoute(Link via, InetAddress source, long seenAt, long sequence, int hops) {
            this.via = via;this.source = source;this.seenAt = seenAt;this.sequence = sequence;this.hops = hops;
        }
    }

    @Override public void close() {
        closed = true;routes.clear();closeQuietly(server);ticker.shutdownNow();
        for (VirtualSocket socket : streams.values()) socket.abort("mesh closed");
        for (Link link : links) link.close("mesh closed");
        workers.shutdownNow();
    }
    private static void closeQuietly(Closeable closeable) { try { closeable.close(); } catch (IOException ignored) { } }
    private static void writeUuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private final class Link {
        final Socket socket;final DataInputStream in;final DataOutputStream out;
        final UUID peer;final InetAddress logical;final String endpoint;
        final FrameQueue queue = new FrameQueue();
        volatile boolean stopped;
        volatile long lastReadAt = now();
        private volatile long congestedUntil;
        private long lastCongestionAt;
        Link(Socket socket, DataInputStream in, DataOutputStream out, UUID peer, InetAddress logical, String endpoint) {
            this.socket = socket;this.in = in;this.out = out;this.peer = peer;this.logical = logical;this.endpoint = endpoint;
        }
        void read() {
            try { while (!closed && !stopped) receive(Frame.read(in), this); }
            catch (IOException | RuntimeException failure) { close("read: " + failure.getClass().getSimpleName()); }
            finally { close("reader stopped"); }
        }
        void write() {
            try { while (!closed && !stopped) {
                Frame frame = queue.poll(1000);
                if (frame != null) { frame.write(out);out.flush(); }
            } }
            catch (IOException | InterruptedException failure) { close("write: " + failure.getClass().getSimpleName()); }
            finally { close("writer stopped"); }
        }
        void close(String reason) {
            synchronized (this) { if (stopped) return;stopped = true; }
            for (Map.Entry<UUID, LearnedRoute> entry : routes.entrySet())
                if (entry.getValue().via == this) routes.remove(entry.getKey(), entry.getValue());
            links.remove(this);closeQuietly(socket);listener.linkClosed(peer, reason);
        }
        boolean fresh(long time) { return !stopped && time - lastReadAt < ROUTE_LIFETIME_MS; }
        boolean routable(long time) { return fresh(time) && time >= congestedUntil; }
        void congested() {
            long time = now();
            // A queued retry is not evidence that the stalled writer made
            // progress. Give pending byte gaps a chance to use another route.
            congestedUntil = time + 1000;
            synchronized (this) {
                if (lastCongestionAt != 0 && time - lastCongestionAt < 5000) return;
                lastCongestionAt = time;
            }
            listener.linkCongested(peer);
        }
    }

    /** Bounded lanes with end-to-end retransmission instead of radio disconnection. */
    private static final class FrameQueue {
        private static final int CAPACITY = 128;
        private final ArrayDeque<Frame> metadata = new ArrayDeque<>(), data = new ArrayDeque<>(), state = new ArrayDeque<>();
        private int dataSinceState;
        synchronized boolean offer(Frame frame) {
            if (frame.type == DATA) {
                // A retry gets a new flooding sequence number but retains its
                // stream offset. Keep only one queued copy of those bytes.
                for (Frame queued : data)
                    if (sameFlow(queued, frame) && queued.offset == frame.offset) return true;
            } else if (frame.type != UDP) {
                Iterator<Frame> iterator = metadata.iterator();
                while (iterator.hasNext()) {
                    Frame queued = iterator.next();
                    if (!sameFlow(queued, frame)) continue;
                    // ACKs are cumulative: an older acknowledgement must not
                    // replace newer progress while the writer is stalled.
                    if (frame.type == ACK && queued.offset > frame.offset) return true;
                    iterator.remove();break;
                }
            }
            if (metadata.size() + data.size() + state.size() >= CAPACITY) {
                if (frame.type == UDP) return false;
                if (!state.isEmpty()) state.removeFirst();
                else if (frame.type != DATA && !data.isEmpty()) data.removeLast();
                else return false;
            }
            (frame.type == UDP ? state : frame.type == DATA ? data : metadata).addLast(frame);
            notifyAll();return true;
        }
        synchronized Frame poll(long timeout) throws InterruptedException {
            long deadline = now() + timeout;
            while (metadata.isEmpty() && data.isEmpty() && state.isEmpty()) {
                long remaining = deadline - now();
                if (remaining <= 0) return null;
                wait(remaining);
            }
            if (!metadata.isEmpty()) return metadata.removeFirst();
            if (!data.isEmpty() && (state.isEmpty() || dataSinceState < 8)) {
                if (dataSinceState < 8) dataSinceState++;
                return data.removeFirst();
            }
            dataSinceState = 0;return state.removeFirst();
        }
        private static boolean sameFlow(Frame first, Frame second) {
            return first.type == second.type && first.origin.equals(second.origin)
                    && first.stream.equals(second.stream) && first.destination.equals(second.destination)
                    && first.port == second.port;
        }
    }

    private static final class Frame {
        final int type, port, hops;final UUID origin, stream;final long sequence, offset;
        final InetAddress source, destination;final byte[] payload;
        Frame(int type, UUID origin, long sequence, InetAddress source, InetAddress destination,
              int port, UUID stream, long offset, byte[] payload, int hops) {
            this.type = type;this.origin = origin;this.sequence = sequence;this.source = source;
            this.destination = destination;this.port = port;this.stream = stream;this.offset = offset;
            this.payload = payload;this.hops = hops;
        }
        Frame nextHop() { return new Frame(type, origin, sequence, source, destination, port, stream, offset, payload, hops - 1); }
        void write(DataOutputStream out) throws IOException {
            out.writeByte(type);out.writeByte(hops);writeUuid(out, origin);out.writeLong(sequence);
            out.write(source.getAddress());out.write(destination.getAddress());out.writeShort(port);
            writeUuid(out, stream);out.writeLong(offset);out.writeInt(payload.length);out.write(payload);
        }
        static Frame read(DataInputStream in) throws IOException {
            int type = in.readUnsignedByte(), hops = in.readUnsignedByte();UUID origin = readUuid(in);
            long sequence = in.readLong();byte[] source = new byte[4], target = new byte[4];in.readFully(source);in.readFully(target);
            int port = in.readUnsignedShort();UUID stream = readUuid(in);long offset = in.readLong();int size = in.readInt();
            if (type > CLOSE || hops < 1 || hops > HOPS || sequence < 1 || offset < 0 || size < 0 || size > MAX_PAYLOAD)
                throw new IOException("Invalid game relay frame");
            byte[] payload = new byte[size];in.readFully(payload);
            return new Frame(type, origin, sequence, InetAddress.getByAddress(source), InetAddress.getByAddress(target), port, stream, offset, payload, hops);
        }
    }

    /** A reliable, ordered byte stream over the relay graph with its original logical endpoint. */
    public final class VirtualSocket extends Socket {
        final UUID id;final InetAddress remote;final int remotePort;
        volatile boolean socketClosed;boolean opened;int timeout;
        long received, consumed, written, remoteEnd = -1, closedAt;
        final TreeMap<Long, byte[]> incoming = new TreeMap<>();
        final TreeMap<Long, Pending> outgoing = new TreeMap<>();
        int incomingBytes, outgoingBytes;
        VirtualSocket(UUID id, InetAddress remote, int remotePort, boolean opened) {
            this.id = id;this.remote = remote;this.remotePort = remotePort;this.opened = opened;
        }
        private void waitFor(long millis) throws IOException {
            try { wait(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new InterruptedIOException(); }
        }
        void announceOpen() { publish(OPEN, remote, remotePort, id, 0, new byte[0]); }
        synchronized void receive(long offset, byte[] data) {
            if (data.length == 0 || offset > consumed + 262144 || offset + data.length < offset) {
                abort("invalid receive position");return;
            }
            if (offset >= consumed && !incoming.containsKey(offset)) {
                if (incomingBytes + data.length > 262144) { abort("receive buffer full");return; }
                incoming.put(offset, data);incomingBytes += data.length;
                while (incoming.containsKey(received)) received += incoming.get(received).length;
            }
            publish(ACK, remote, remotePort, id, received, new byte[0]);notifyAll();
        }
        synchronized void acknowledge(long offset) {
            if (offset > written) { abort("invalid acknowledgement");return; }
            Iterator<Map.Entry<Long, Pending>> iterator = outgoing.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Pending> entry = iterator.next();
                if (entry.getKey() + entry.getValue().bytes.length > offset) break;
                outgoingBytes -= entry.getValue().bytes.length;iterator.remove();
            }
            notifyAll();
        }
        synchronized void retry(long time) throws IOException {
            // close() can publish its final offset before a slow radio has
            // transmitted every byte. Retain unacknowledged data for its full
            // retry window, even after the application's socket has closed.
            if (socketClosed && outgoing.isEmpty() && time - closedAt > 5000) {
                streams.remove(id, this);return;
            }
            for (Map.Entry<Long, Pending> entry : outgoing.entrySet()) {
                Pending pending = entry.getValue();
                if (time - pending.created > 15_000) { abort("acknowledgement timed out");return; }
                if (time - pending.lastSent >= 300) {
                    pending.lastSent = time;publish(DATA, remote, remotePort, id, entry.getKey(), pending.bytes);
                }
            }
            if (socketClosed) publish(CLOSE, remote, remotePort, id, written, new byte[0]);
        }
        synchronized void abort(String reason) {
            if (!socketClosed) { socketClosed = true;closedAt = now(); }
            boolean removed = streams.remove(id, this);notifyAll();
            if (removed) listener.streamAborted(reason);
        }
        @Override public synchronized void close() {
            if (!socketClosed) { socketClosed = true;closedAt = now();publish(CLOSE, remote, remotePort, id, written, new byte[0]);notifyAll(); }
        }
        @Override public boolean isClosed() { return socketClosed; }
        @Override public boolean isConnected() { return opened && !socketClosed; }
        @Override public InetAddress getInetAddress() { return remote; }
        @Override public InetAddress getLocalAddress() { return localAddress(); }
        @Override public int getPort() { return remotePort; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(remote, remotePort); }
        @Override public void setTcpNoDelay(boolean enabled) { }
        @Override public void setKeepAlive(boolean enabled) { }
        @Override public synchronized void setSoTimeout(int timeout) { if (timeout < 0) throw new IllegalArgumentException();this.timeout = timeout; }
        @Override public synchronized int getSoTimeout() { return timeout; }
        @Override public InputStream getInputStream() { return new InputStream() {
            @Override public int read() throws IOException { byte[] one = new byte[1];return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > target.length - length) throw new IndexOutOfBoundsException();
                if (length == 0) return 0;
                synchronized (VirtualSocket.this) {
                    long end = timeout == 0 ? Long.MAX_VALUE : now() + timeout;
                    while (consumed >= received) {
                        if (socketClosed || remoteEnd >= 0 && consumed >= remoteEnd) return -1;
                        if (now() >= end) throw new SocketTimeoutException("Game relay read timed out");
                        waitFor(Math.min(1000, Math.max(1, end - now())));
                    }
                    Map.Entry<Long, byte[]> entry = incoming.floorEntry(consumed);
                    if (entry == null) throw new IOException("Invalid relay stream position");
                    int at = (int) (consumed - entry.getKey()), take = Math.min(length, entry.getValue().length - at);
                    System.arraycopy(entry.getValue(), at, target, offset, take);consumed += take;
                    if (at + take == entry.getValue().length) { incomingBytes -= entry.getValue().length;incoming.remove(entry.getKey()); }
                    return take;
                }
            }
            @Override public int available() { synchronized (VirtualSocket.this) { return (int) Math.min(Integer.MAX_VALUE, received - consumed); } }
            @Override public void close() { VirtualSocket.this.close(); }
        }; }
        @Override public OutputStream getOutputStream() { return new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                while (length > 0) {
                    int take = Math.min(MAX_PAYLOAD, length);
                    synchronized (VirtualSocket.this) {
                        long end = now() + 15_000;
                        while (!socketClosed && outgoingBytes + take > 262144 && now() < end) waitFor(250);
                        if (socketClosed || now() >= end) throw new SocketException("Game relay stream is unavailable");
                        byte[] copy = Arrays.copyOfRange(bytes, offset, offset + take);
                        outgoing.put(written, new Pending(copy));outgoingBytes += take;
                        publish(DATA, remote, remotePort, id, written, copy);written += take;
                    }
                    offset += take;length -= take;
                }
            }
            @Override public void close() { VirtualSocket.this.close(); }
        }; }
    }
    private static final class Pending {
        final byte[] bytes;final long created = now();long lastSent = created;
        Pending(byte[] bytes) { this.bytes = bytes; }
    }
}
