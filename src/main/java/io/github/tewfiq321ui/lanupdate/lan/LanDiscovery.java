package io.github.tewfiq321ui.lanupdate.lan;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.util.Background;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Listens for hosts announcing their mods on the LAN (see {@link LanProtocol}). */
public final class LanDiscovery implements AutoCloseable {
    /** A host that announced itself recently. {@link #key()} is the "ip:port" to connect to. */
    public record Found(String address, int port, String name, long lastSeen) {
        public String key() {
            return address + ":" + port;
        }
    }

    private static final long FORGET_AFTER_MILLIS = 6_000;

    private final Map<String, Found> found = new ConcurrentHashMap<>();
    private final MulticastSocket socket;
    private volatile boolean closed;

    public LanDiscovery() throws IOException {
        socket = new MulticastSocket(LanProtocol.ANNOUNCE_PORT);
        socket.setSoTimeout(1000);
        InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(LanProtocol.MULTICAST_GROUP), LanProtocol.ANNOUNCE_PORT);
        boolean joined = false;
        for (NetworkInterface networkInterface : LanProtocol.lanInterfaces()) {
            try {
                socket.joinGroup(group, networkInterface);
                joined = true;
            } catch (IOException ignored) {
                // this adapter can't receive multicast
            }
        }
        if (!joined) {
            try {
                socket.joinGroup(group, null);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }
        Background.run("lanupdate-discovery", this::listen);
    }

    private void listen() {
        byte[] buffer = new byte[1024];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                LanProtocol.Announcement announcement = LanProtocol.parseAnnouncement(message);
                if (announcement != null) {
                    Found host = new Found(packet.getAddress().getHostAddress(), announcement.port(), announcement.name(), System.currentTimeMillis());
                    found.put(host.key(), host);
                }
            } catch (SocketTimeoutException ignored) {
                // check `closed` again
            } catch (IOException e) {
                if (!closed) {
                    LanUpdate.LOGGER.debug("LAN discovery error: {}", e.toString());
                }
            }
        }
    }

    /** Hosts heard from in the last few seconds, sorted by world name. */
    public List<Found> hosts() {
        long now = System.currentTimeMillis();
        return found.values().stream()
                .filter(host -> now - host.lastSeen() < FORGET_AFTER_MILLIS)
                .sorted(Comparator.comparing(Found::name).thenComparing(Found::key))
                .toList();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
