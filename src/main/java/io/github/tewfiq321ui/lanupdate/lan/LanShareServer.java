package io.github.tewfiq321ui.lanupdate.lan;

import io.github.tewfiq321ui.lanupdate.Config;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.util.Background;
import io.github.tewfiq321ui.lanupdate.util.ConfigFiles;
import io.github.tewfiq321ui.lanupdate.util.ConfigFiles.ConfigFile;
import io.github.tewfiq321ui.lanupdate.util.ModJars;
import io.github.tewfiq321ui.lanupdate.util.ModJars.ModJar;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Runs on the host while their world is open to LAN: announces itself on the network and
 * serves the .jar files of the host's mods folder, plus shareable config files if allowed,
 * read-only to friends who ask for them.
 */
public final class LanShareServer {
    /** What the host's Update menu shows. */
    public record Status(int port, int modCount) {}

    private static @Nullable LanShareServer instance;
    private static boolean failed;

    private final ServerSocket socket;
    private volatile String worldName;
    private volatile boolean closed;
    private volatile int modCount;

    private LanShareServer(ServerSocket socket, String worldName) {
        this.socket = socket;
        this.worldName = worldName;
    }

    /** Called every second from the client tick. Starts or stops sharing to match the LAN state. */
    public static synchronized void setActive(boolean active, String worldName) {
        if (!active) {
            failed = false;
            if (instance != null) {
                instance.close();
                instance = null;
                LanUpdate.LOGGER.info("Stopped sharing mods on the LAN");
            }
            return;
        }
        if (instance != null) {
            instance.worldName = worldName;
            return;
        }
        if (failed) {
            return; // don't retry every second; try again the next time the world is opened to LAN
        }
        try {
            instance = new LanShareServer(bind(Config.sharePort()), worldName);
            instance.start();
            LanUpdate.LOGGER.info("Sharing mods on the LAN on port {}", instance.socket.getLocalPort());
        } catch (IOException e) {
            failed = true;
            LanUpdate.LOGGER.warn("Could not start sharing mods on the LAN", e);
        }
    }

    public static synchronized void stop() {
        setActive(false, "");
    }

    public static synchronized @Nullable Status status() {
        return instance == null ? null : new Status(instance.socket.getLocalPort(), instance.modCount);
    }

    private static ServerSocket bind(int port) throws IOException {
        try {
            return new ServerSocket(port, 50);
        } catch (IOException portTaken) {
            // Another program uses the port. A random free port still works with automatic discovery.
            return new ServerSocket(0, 50);
        }
    }

    private void start() {
        Background.run("lanupdate-share", this::acceptLoop);
        Background.run("lanupdate-announce", this::announceLoop);
        // Hash the mods now so the first friend who connects doesn't have to wait.
        Background.run("lanupdate-share-scan", () -> {
            try {
                modCount = ModJars.scan(ModJars.modsDir()).size();
            } catch (IOException e) {
                LanUpdate.LOGGER.warn("Could not read the mods folder", e);
            }
        });
    }

    private void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().name("lanupdate-share-client").start(() -> handle(client));
            } catch (IOException e) {
                if (!closed) {
                    LanUpdate.LOGGER.debug("LAN share accept failed: {}", e.toString());
                }
            }
        }
    }

    private void announceLoop() {
        try (MulticastSocket multicast = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(LanProtocol.MULTICAST_GROUP);
            int rounds = 0;
            List<NetworkInterface> interfaces = List.of();
            while (!closed) {
                if (rounds++ % 20 == 0) {
                    interfaces = LanProtocol.lanInterfaces(); // adapters can change (WiFi reconnects)
                }
                byte[] data = LanProtocol.announcement(socket.getLocalPort(), worldName).getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, group, LanProtocol.ANNOUNCE_PORT);
                boolean sent = false;
                for (NetworkInterface networkInterface : interfaces) {
                    try {
                        multicast.setNetworkInterface(networkInterface);
                        multicast.send(packet);
                        sent = true;
                    } catch (IOException ignored) {
                        // this adapter can't send multicast; try the next one
                    }
                }
                if (!sent) {
                    try {
                        multicast.send(packet);
                    } catch (IOException ignored) {
                        // no network right now
                    }
                }
                Thread.sleep(1500);
            }
        } catch (IOException e) {
            LanUpdate.LOGGER.warn("LAN announcements stopped", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String header;
            do {
                header = readLine(in);
            } while (header != null && !header.isEmpty());

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                sendText(out, 405, "Only GET is supported");
                return;
            }
            String path = parts[1];
            if (path.equals(LanProtocol.MANIFEST_PATH)) {
                List<ModJar> jars = ModJars.scan(ModJars.modsDir());
                modCount = jars.size();
                List<ConfigFile> configs = Config.shareConfigsOnLan() ? ConfigFiles.scan(ConfigFiles.configDir()) : List.of();
                byte[] body = LanProtocol.manifestJson(jars, configs, worldName).getBytes(StandardCharsets.UTF_8);
                sendHeaders(out, 200, "application/json; charset=utf-8", body.length);
                out.write(body);
            } else if (path.startsWith(LanProtocol.CONFIG_PATH)) {
                String sha256 = path.substring(LanProtocol.CONFIG_PATH.length());
                ConfigFile config = Config.shareConfigsOnLan() && sha256.matches("[0-9a-f]{64}") ? findConfigBySha256(sha256) : null;
                if (config == null) {
                    sendText(out, 404, "No such config file");
                    return;
                }
                byte[] body = Files.readAllBytes(config.path());
                sendHeaders(out, 200, "application/octet-stream", body.length);
                out.write(body);
            } else if (path.startsWith(LanProtocol.FILE_PATH)) {
                String sha256 = path.substring(LanProtocol.FILE_PATH.length());
                if (!sha256.matches("[0-9a-f]{64}")) {
                    sendText(out, 400, "Bad file id");
                    return;
                }
                ModJar jar = findBySha256(sha256);
                if (jar == null) {
                    sendText(out, 404, "No such mod");
                    return;
                }
                sendHeaders(out, 200, "application/java-archive", Files.size(jar.path()));
                Files.copy(jar.path(), out);
            } else {
                sendText(out, 404, "Not found");
            }
            out.flush();
        } catch (IOException e) {
            LanUpdate.LOGGER.debug("LAN share request failed: {}", e.toString());
        }
    }

    private static @Nullable ModJar findBySha256(String sha256) throws IOException {
        for (ModJar jar : ModJars.scan(ModJars.modsDir())) {
            if (jar.sha256().equals(sha256)) {
                return jar;
            }
        }
        return null;
    }

    private static @Nullable ConfigFile findConfigBySha256(String sha256) throws IOException {
        for (ConfigFile config : ConfigFiles.scan(ConfigFiles.configDir())) {
            if (config.sha256().equals(sha256)) {
                return config;
            }
        }
        return null;
    }

    private static @Nullable String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 8192) {
                throw new IOException("Request line too long");
            }
        }
        if (b == -1 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void sendHeaders(OutputStream out, int code, String contentType, long length) throws IOException {
        String reason = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Error";
        };
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendText(OutputStream out, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        sendHeaders(out, code, "text/plain; charset=utf-8", body.length);
        out.write(body);
        out.flush();
    }
}
