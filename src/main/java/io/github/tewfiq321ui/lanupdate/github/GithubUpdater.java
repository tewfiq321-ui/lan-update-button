package io.github.tewfiq321ui.lanupdate.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tewfiq321ui.lanupdate.Config;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.util.Background;
import io.github.tewfiq321ui.lanupdate.util.Http;
import io.github.tewfiq321ui.lanupdate.util.ModJars;
import io.github.tewfiq321ui.lanupdate.util.PendingDeletes;
import io.github.tewfiq321ui.lanupdate.util.Versions;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Finds and installs new versions of this mod from GitHub Releases.
 *
 * <p>Release jars are named {@code lanupdate-<minecraft version>-<mod version>.jar}, so a client
 * only ever picks a build made for the Minecraft version it is running.
 */
public final class GithubUpdater {
    /** A release jar that can be installed. */
    public record Release(String version, String tag, String assetName, String downloadUrl, long size, @Nullable String sha256) {}

    private static volatile @Nullable Release available;

    private GithubUpdater() {}

    /** A newer version found by the startup check, or null. Used to highlight the Update button. */
    public static @Nullable Release availableUpdate() {
        return available;
    }

    public static void checkInBackground() {
        Background.run("lanupdate-update-check", () -> {
            try {
                Release newest = findNewest();
                if (newest != null && isNewer(newest)) {
                    available = newest;
                    LanUpdate.LOGGER.info("LAN Update Button {} is available (you have {})", newest.version(), LanUpdate.modVersion());
                }
            } catch (Exception e) {
                LanUpdate.LOGGER.info("Could not check GitHub for updates: {}", Background.message(e));
            }
        });
    }

    public static boolean isNewer(Release release) {
        return Versions.compare(release.version(), LanUpdate.modVersion()) > 0;
    }

    /** The newest stable release with a jar for this Minecraft version, or null if there is none yet. */
    public static @Nullable Release findNewest() throws IOException {
        String repo = Config.githubRepo();
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IOException("The GitHub repository in the config is not valid: " + repo);
        }
        String json = Http.getText("https://api.github.com/repos/" + repo + "/releases?per_page=30", 15_000, "application/vnd.github+json");
        String prefix = LanUpdate.MODID + "-" + LanUpdate.minecraftVersion() + "-";

        Release best = null;
        try {
            for (JsonElement releaseElement : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject release = releaseElement.getAsJsonObject();
                if (bool(release, "draft") || bool(release, "prerelease") || !release.has("assets")) {
                    continue;
                }
                JsonArray assets = release.getAsJsonArray("assets");
                for (JsonElement assetElement : assets) {
                    JsonObject asset = assetElement.getAsJsonObject();
                    String name = string(asset, "name");
                    String url = string(asset, "browser_download_url");
                    if (name == null || url == null || !name.startsWith(prefix) || !name.endsWith(".jar") || name.endsWith("-sources.jar")) {
                        continue;
                    }
                    String version = name.substring(prefix.length(), name.length() - ".jar".length());
                    if (best != null && Versions.compare(version, best.version()) <= 0) {
                        continue;
                    }
                    String digest = string(asset, "digest");
                    String sha256 = digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null;
                    long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
                    String tag = string(release, "tag_name");
                    best = new Release(version, tag == null ? version : tag, name, url, size, sha256);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("GitHub sent an answer this mod could not read", e);
        }
        return best;
    }

    /**
     * Downloads the release into the mods folder. The running copy is removed right away when
     * possible, otherwise after Minecraft closes. NeoForge loads the newest copy next time either way.
     */
    public static void install(Release release, Http.@Nullable Progress progress) throws IOException {
        if (!release.downloadUrl().startsWith("https://")) {
            throw new IOException("Refusing a download link that is not HTTPS: " + release.downloadUrl());
        }
        Path target = ModJars.modsDir().resolve(ModJars.safeFileName(release.assetName())).toAbsolutePath().normalize();
        Http.download(release.downloadUrl(), target, release.size(), release.sha256(), progress);

        Path own = ModJars.ownJar();
        if (own != null && !own.equals(target)) {
            PendingDeletes.delete(own);
        }
        available = null;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && !element.isJsonNull() && element.getAsBoolean();
    }

    private static @Nullable String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
