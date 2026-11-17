package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.nio.file.Path;

/**
 * After an update the mods folder briefly holds two copies of this mod. NeoForge loads the
 * newest one, so on startup the running (newest) copy removes the older ones.
 */
public final class SelfCleanup {
    private SelfCleanup() {}

    public static void removeOldCopies() {
        Path own = ModJars.ownJar();
        if (own == null) {
            return; // running from an IDE
        }
        try {
            for (Path jar : ModJars.listJars(ModJars.modsDir())) {
                Path path = jar.toAbsolutePath().normalize();
                if (!path.equals(own) && ModJars.readModIds(path).contains(LanUpdate.MODID)) {
                    LanUpdate.LOGGER.info("Removing old copy of LAN Update Button: {}", path.getFileName());
                    PendingDeletes.delete(path);
                }
            }
        } catch (Exception e) {
            LanUpdate.LOGGER.warn("Could not clean up old copies of LAN Update Button", e);
        }
    }
}
