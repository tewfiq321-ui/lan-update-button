package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

/** Reads the .jar files in the mods folder: size, SHA-256 and the mod IDs they contain. */
public final class ModJars {
    /** One .jar file in a mods folder. */
    public record ModJar(Path path, String fileName, long size, String sha256, List<String> modIds) {}

    private record CacheKey(Path path, long size, long modified) {}

    private static final Pattern MOD_ID = Pattern.compile("^modId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?");
    private static final Map<CacheKey, ModJar> CACHE = new ConcurrentHashMap<>();

    private ModJars() {}

    public static Path modsDir() {
        return FMLPaths.MODSDIR.get();
    }

    /** The jar this mod was loaded from, or null when running from an IDE. */
    public static @Nullable Path ownJar() {
        try {
            Path path = ModList.get().getModFileById(LanUpdate.MODID).getFile().getFilePath().toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Lists the .jar files directly inside the folder, without hashing them. */
    public static List<Path> listJars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(ModJars::isJar).sorted().toList();
        }
    }

    /** Describes every .jar in the folder. Hashes are cached, so repeated scans are cheap. */
    public static List<ModJar> scan(Path dir) throws IOException {
        List<ModJar> result = new ArrayList<>();
        for (Path path : listJars(dir)) {
            try {
                result.add(describe(path));
            } catch (IOException e) {
                LanUpdate.LOGGER.warn("Could not read mod file {}", path, e);
            }
        }
        return result;
    }

    public static ModJar describe(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        CacheKey key = new CacheKey(path.toAbsolutePath().normalize(), attributes.size(), attributes.lastModifiedTime().toMillis());
        ModJar cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ModJar jar = new ModJar(path, path.getFileName().toString(), attributes.size(), sha256(path), readModIds(path));
        CACHE.put(key, jar);
        return jar;
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Mod IDs declared in the jar's [[mods]] sections. Empty for library jars. */
    public static List<String> readModIds(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return List.of();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parseModIds(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    static List<String> parseModIds(String toml) {
        List<String> ids = new ArrayList<>();
        boolean inModsSection = false;
        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("[")) {
                inModsSection = line.replace(" ", "").startsWith("[[mods]]");
                continue;
            }
            if (inModsSection) {
                Matcher matcher = MOD_ID.matcher(line);
                if (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Turns a file name received from the network into a safe name inside the mods folder. */
    public static String safeFileName(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._+\\-()\\[\\] ]", "_").strip();
        while (base.startsWith(".")) {
            base = "_" + base.substring(1);
        }
        if (!base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            base = base + ".jar";
        }
        if (base.length() > 150) {
            base = base.substring(0, 146) + ".jar";
        }
        if (WINDOWS_RESERVED.matcher(base).matches()) {
            base = "_" + base;
        }
        return base;
    }

    /** A file name next to {@code name} in the folder that is not taken yet, e.g. "jei-lan1.jar". */
    public static String freeName(Path dir, String name) {
        String stem = name.substring(0, name.length() - ".jar".length());
        for (int i = 1; ; i++) {
            String candidate = stem + "-lan" + i + ".jar";
            if (!Files.exists(dir.resolve(candidate))) {
                return candidate;
            }
        }
    }
}
