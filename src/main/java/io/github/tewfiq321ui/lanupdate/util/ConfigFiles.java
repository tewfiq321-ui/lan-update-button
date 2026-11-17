package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

/**
 * The text config files in the {@code config} folder that hosts share and friends copy.
 *
 * <p>Only common config formats are included (no programs, no binaries), each at most 2 MB.
 * This mod's own files and FML's loader settings ({@code fml.toml}) are never shared, and the
 * same rules are checked again on the receiving side, so a host can't write anything else.
 */
public final class ConfigFiles {
    /** One config file; {@code relative} uses "/" separators, e.g. "jei/jei-client.ini". */
    public record ConfigFile(Path path, String relative, long size, String sha256) {}

    private record CacheKey(Path path, long size, long modified) {}

    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_FILES = 3000;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> EXTENSIONS = Set.of(
            "toml", "json", "json5", "jsonc", "cfg", "conf", "ini", "properties", "txt", "yml", "yaml", "snbt");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_+\\-()\\[\\] ][A-Za-z0-9._+\\-()\\[\\] ]{0,99}");
    private static final Map<CacheKey, String> HASHES = new ConcurrentHashMap<>();

    private ConfigFiles() {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Whether a config path may be shared and copied. Checked on both host and friend. */
    public static boolean isShareable(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.startsWith(LanUpdate.MODID + "/") || lower.startsWith(LanUpdate.MODID + "-") || lower.equals("fml.toml")) {
            return false;
        }
        int dot = lower.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(lower.substring(dot + 1)) && resolveInside(configDir(), relative) != null;
    }

    /**
     * Turns a relative path received from the network into a path inside {@code root}, or null if it
     * is unsafe (empty parts, "..", hidden files, odd characters, reserved Windows names).
     */
    public static @Nullable Path resolveInside(Path root, String relative) {
        if (relative.isEmpty() || relative.length() > 240) {
            return null;
        }
        Path base = root.toAbsolutePath().normalize();
        Path result = base;
        for (String segment : relative.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches() || segment.endsWith(".") || segment.endsWith(" ")
                    || ModJars.isWindowsReserved(segment)) {
                return null;
            }
            result = result.resolve(segment);
        }
        Path normalized = result.normalize();
        return normalized.startsWith(base) && !normalized.equals(base) ? normalized : null;
    }

    /** All shareable config files under the folder. Hashes are cached between scans. */
    public static List<ConfigFile> scan(Path root) throws IOException {
        List<ConfigFile> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path path : files) {
            if (result.size() >= MAX_FILES) {
                LanUpdate.LOGGER.warn("More than {} config files; the rest are not shared", MAX_FILES);
                break;
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (!isShareable(relative)) {
                continue;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (attributes.size() > MAX_FILE_BYTES) {
                    continue;
                }
                CacheKey key = new CacheKey(path.toAbsolutePath().normalize(), attributes.size(), attributes.lastModifiedTime().toMillis());
                String sha256 = HASHES.get(key);
                if (sha256 == null) {
                    sha256 = ModJars.sha256(path);
                    HASHES.put(key, sha256);
                }
                result.add(new ConfigFile(path, relative, attributes.size(), sha256));
            } catch (IOException e) {
                LanUpdate.LOGGER.debug("Skipping config file {}: {}", relative, e.toString());
            }
        }
        return result;
    }
}
