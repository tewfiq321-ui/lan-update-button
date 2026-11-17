package io.github.tewfiq321ui.lanupdate.lan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.util.ModJars.ModJar;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How hosts and friends talk to each other on the LAN.
 *
 * <ul>
 * <li>Discovery: the host sends {@code LANUPDATE;1;<port>;<world name>} by UDP multicast to
 * 224.0.2.60:4446 every 1.5 seconds (the same group Minecraft uses for its own LAN games, one port up).</li>
 * <li>Mod list: {@code GET /lanupdate/manifest} on the host's TCP port returns JSON with every
 * .jar in the host's mods folder (name, size, SHA-256, mod IDs).</li>
 * <li>Files: {@code GET /lanupdate/file/<sha256>} returns one jar. Files are requested by hash,
 * so a friend can never ask for anything outside the host's mods folder.</li>
 * </ul>
 */
public final class LanProtocol {
    public static final String MULTICAST_GROUP = "224.0.2.60";
    public static final int ANNOUNCE_PORT = 4446;
    public static final String MAGIC = "LANUPDATE";
    public static final int VERSION = 1;
    public static final String MANIFEST_PATH = "/lanupdate/manifest";
    public static final String FILE_PATH = "/lanupdate/file/";

    public record Announcement(int port, String name) {}

    public record RemoteFile(String name, long size, String sha256, List<String> modIds) {}

    public record Manifest(String host, String minecraft, String neoforge, List<RemoteFile> files) {}

    private LanProtocol() {}

    public static String announcement(int port, String worldName) {
        String name = worldName.replace(';', ',').replaceAll("\\p{Cntrl}", " ");
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        return MAGIC + ";" + VERSION + ";" + port + ";" + name;
    }

    public static @Nullable Announcement parseAnnouncement(String message) {
        String[] parts = message.split(";", 4);
        if (parts.length < 4 || !parts[0].equals(MAGIC)) {
            return null;
        }
        try {
            int port = Integer.parseInt(parts[2]);
            if (port < 1 || port > 65535) {
                return null;
            }
            String name = parts[3].isBlank() ? "LAN world" : parts[3].strip();
            return new Announcement(port, name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String manifestJson(List<ModJar> jars, String worldName) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", VERSION);
        root.addProperty("host", worldName);
        root.addProperty("minecraft", LanUpdate.minecraftVersion());
        root.addProperty("neoforge", LanUpdate.neoForgeVersion());
        JsonArray files = new JsonArray();
        for (ModJar jar : jars) {
            JsonObject file = new JsonObject();
            file.addProperty("name", jar.fileName());
            file.addProperty("size", jar.size());
            file.addProperty("sha256", jar.sha256());
            JsonArray ids = new JsonArray();
            jar.modIds().forEach(ids::add);
            file.add("modIds", ids);
            files.add(file);
        }
        root.add("files", files);
        return root.toString();
    }

    public static Manifest parseManifest(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<RemoteFile> files = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                String sha256 = file.get("sha256").getAsString().toLowerCase(java.util.Locale.ROOT);
                long size = file.get("size").getAsLong();
                if (!sha256.matches("[0-9a-f]{64}") || size < 0) {
                    throw new IOException("The host sent a broken mod list");
                }
                List<String> ids = new ArrayList<>();
                if (file.has("modIds")) {
                    for (JsonElement id : file.getAsJsonArray("modIds")) {
                        ids.add(id.getAsString());
                    }
                }
                files.add(new RemoteFile(file.get("name").getAsString(), size, sha256, List.copyOf(ids)));
            }
            return new Manifest(text(root, "host"), text(root, "minecraft"), text(root, "neoforge"), List.copyOf(files));
        } catch (RuntimeException e) {
            throw new IOException("The host sent a mod list this version cannot read. Make sure you both have the newest LAN Update Button.", e);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "?" : element.getAsString();
    }

    /** Network adapters that can reach the LAN (up, not loopback, IPv4, multicast capable). */
    public static List<NetworkInterface> lanInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface candidate = interfaces.nextElement();
                try {
                    if (candidate.isUp() && !candidate.isLoopback() && candidate.supportsMulticast()
                            && candidate.inetAddresses().anyMatch(address -> address instanceof Inet4Address)) {
                        result.add(candidate);
                    }
                } catch (SocketException ignored) {
                    // adapter disappeared while we looked at it
                }
            }
        } catch (SocketException ignored) {
            // no network
        }
        return result;
    }

    /** This PC's LAN IPv4 address (like 192.168.1.23) to show the host, or null if none is found. */
    public static @Nullable String localAddress() {
        String fallback = null;
        for (NetworkInterface candidate : lanInterfaces()) {
            for (InetAddress address : candidate.inetAddresses().toList()) {
                if (address instanceof Inet4Address) {
                    if (address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }
        }
        return fallback;
    }
}
