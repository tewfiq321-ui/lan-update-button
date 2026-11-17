package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Deletes mod files, even ones Minecraft is still using.
 *
 * <p>On Windows a loaded mod jar is locked until Minecraft exits. Such files are remembered in
 * {@code config/lanupdate/pending-deletes.txt}. When Minecraft closes, a small helper script
 * waits for the game to exit and deletes them, and the next start retries anything left over.
 */
public final class PendingDeletes {
    private static final Set<Path> PENDING = new LinkedHashSet<>();
    private static boolean loaded;

    private PendingDeletes() {}

    /** Deletes the file now if possible, otherwise after Minecraft closes. Returns true if it is gone now. */
    public static synchronized boolean delete(Path file) {
        load();
        Path path = file.toAbsolutePath().normalize();
        if (tryDelete(path)) {
            PENDING.remove(path);
            save();
            return true;
        }
        PENDING.add(path);
        save();
        return false;
    }

    public static synchronized boolean isPending(Path file) {
        load();
        return PENDING.contains(file.toAbsolutePath().normalize());
    }

    public static synchronized void retryPending() {
        load();
        if (PENDING.removeIf(PendingDeletes::tryDelete)) {
            save();
        }
    }

    public static synchronized void onShutdown() {
        load();
        PENDING.removeIf(PendingDeletes::tryDelete);
        save();
        if (!PENDING.isEmpty() && isWindows()) {
            startWindowsCleaner(new ArrayList<>(PENDING));
        }
    }

    private static boolean tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException stillInUse) {
            return false;
        }
    }

    private static Path listFile() {
        return FMLPaths.CONFIGDIR.get().resolve(LanUpdate.MODID).resolve("pending-deletes.txt");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = listFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    PENDING.add(Path.of(line.strip()).toAbsolutePath().normalize());
                }
            }
        } catch (IOException | RuntimeException e) {
            LanUpdate.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        Path file = listFile();
        try {
            if (PENDING.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, PENDING.stream().map(Path::toString).toList(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LanUpdate.LOGGER.warn("Could not write {}", file, e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Starts a hidden cmd script that keeps trying to delete the files for about 5 minutes after exit. */
    private static void startWindowsCleaner(List<Path> files) {
        try {
            Path script = Files.createTempFile("lanupdate-cleanup-", ".cmd");
            StringBuilder cmd = new StringBuilder();
            cmd.append("@echo off\r\n");
            cmd.append("chcp 65001 >nul\r\n");
            cmd.append("set n=0\r\n");
            cmd.append(":again\r\n");
            cmd.append("set /a n+=1\r\n");
            for (Path file : files) {
                cmd.append("del /f /q \"").append(escape(file)).append("\" >nul 2>&1\r\n");
            }
            cmd.append("set left=0\r\n");
            for (Path file : files) {
                cmd.append("if exist \"").append(escape(file)).append("\" set left=1\r\n");
            }
            cmd.append("if %left%==0 goto end\r\n");
            cmd.append("if %n% geq 150 goto end\r\n");
            cmd.append("ping -n 3 127.0.0.1 >nul\r\n");
            cmd.append("goto again\r\n");
            cmd.append(":end\r\n");
            cmd.append("(goto) 2>nul & del \"%~f0\"\r\n");
            Files.writeString(script, cmd.toString(), StandardCharsets.UTF_8);

            new ProcessBuilder("cmd.exe", "/c", script.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            LanUpdate.LOGGER.warn("Could not start cleanup for {}", files, e);
        }
    }

    private static String escape(Path file) {
        return file.toString().replace("%", "%%");
    }
}
