package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Config files copied from a host wait in {@code config/lanupdate/incoming/} and are moved into
 * place when Minecraft closes. Many mods save their settings while shutting down, which would
 * otherwise overwrite the copied files. If the game was killed before that, the next start
 * applies them instead.
 */
public final class ConfigStaging {
    private ConfigStaging() {}

    public static Path stagingDir() {
        return ConfigFiles.configDir().resolve(LanUpdate.MODID).resolve("incoming");
    }

    /** Where a host's config file is downloaded to until Minecraft closes. Throws if the path is unsafe. */
    public static Path stagedPath(String relative) throws IOException {
        Path staged = ConfigFiles.resolveInside(stagingDir(), relative);
        if (staged == null || !ConfigFiles.isShareable(relative)) {
            throw new IOException("Refusing an unsafe config path from the host: " + relative);
        }
        Files.createDirectories(staged.getParent());
        return staged;
    }

    /** Deletes config files that were downloaded earlier but not applied yet. */
    public static synchronized void clear() throws IOException {
        deleteTree(stagingDir());
    }

    /** Moves every staged config file into the config folder. Returns how many were applied. */
    public static synchronized int apply() {
        Path staging = stagingDir();
        if (!Files.isDirectory(staging)) {
            return 0;
        }
        Path root = ConfigFiles.configDir();
        int applied = 0;
        try {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(staging)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            for (Path file : files) {
                String relative = staging.relativize(file).toString().replace('\\', '/');
                Path target = ConfigFiles.resolveInside(root, relative);
                if (relative.endsWith(".part") || target == null || !ConfigFiles.isShareable(relative)) {
                    Files.deleteIfExists(file);
                    continue;
                }
                try {
                    Files.createDirectories(target.getParent());
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    applied++;
                } catch (IOException e) {
                    LanUpdate.LOGGER.warn("Could not apply config file {}", relative, e);
                }
            }
            deleteTree(staging);
        } catch (IOException e) {
            LanUpdate.LOGGER.warn("Could not apply copied config files", e);
        }
        if (applied > 0) {
            LanUpdate.LOGGER.info("Applied {} config files copied from a LAN host", applied);
        }
        return applied;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
