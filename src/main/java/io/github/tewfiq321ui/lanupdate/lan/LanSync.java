package io.github.tewfiq321ui.lanupdate.lan;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.lan.LanProtocol.Manifest;
import io.github.tewfiq321ui.lanupdate.lan.LanProtocol.RemoteConfig;
import io.github.tewfiq321ui.lanupdate.lan.LanProtocol.RemoteFile;
import io.github.tewfiq321ui.lanupdate.util.ConfigFiles;
import io.github.tewfiq321ui.lanupdate.util.ConfigFiles.ConfigFile;
import io.github.tewfiq321ui.lanupdate.util.ConfigStaging;
import io.github.tewfiq321ui.lanupdate.util.Http;
import io.github.tewfiq321ui.lanupdate.util.ModJars;
import io.github.tewfiq321ui.lanupdate.util.ModJars.ModJar;
import io.github.tewfiq321ui.lanupdate.util.PendingDeletes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Compares your mods and config folders with a host's and makes them match. */
public final class LanSync {
    /**
     * What a sync would change.
     *
     * @param toDownload  host mod files you don't have
     * @param toReplace   your files for the same mods but a different version (or duplicate copies)
     * @param extras      mods only you have; kept unless you choose to remove them
     * @param same        mod files you already have exactly
     * @param configs     host config files that are missing or different on your PC; your other configs are never removed
     * @param configsSame config files you already have exactly
     */
    public record Plan(String address, Manifest manifest, List<RemoteFile> toDownload, List<ModJar> toReplace, List<ModJar> extras, int same,
            List<RemoteConfig> configs, int configsSame) {
        public long downloadBytes() {
            return toDownload.stream().mapToLong(RemoteFile::size).sum();
        }

        public boolean nothingToDo(boolean removeExtras, boolean copyConfigs) {
            return toDownload.isEmpty() && toReplace.isEmpty() && (!removeExtras || extras.isEmpty()) && (!copyConfigs || configs.isEmpty());
        }
    }

    /**
     * @param removeAfterExit mod files still in use; they are deleted after Minecraft closes
     * @param configsCopied   config files waiting to be put in place when Minecraft closes
     */
    public record Result(int downloaded, int removedNow, int removeAfterExit, int configsCopied) {}

    @FunctionalInterface
    public interface Listener {
        void progress(int index, int count, String fileName, long done, long total);
    }

    private LanSync() {}

    private static String baseUrl(String address) {
        return "http://" + address;
    }

    public static Plan prepare(String address) throws IOException {
        Manifest manifest = LanProtocol.parseManifest(Http.getText(baseUrl(address) + LanProtocol.MANIFEST_PATH, 60_000, "application/json"));
        List<ModJar> local = ModJars.scan(ModJars.modsDir());

        Set<String> localHashes = local.stream().map(ModJar::sha256).collect(Collectors.toSet());
        Set<String> hostHashes = manifest.files().stream().map(RemoteFile::sha256).collect(Collectors.toSet());
        Set<String> hostModIds = manifest.files().stream().flatMap(file -> file.modIds().stream()).collect(Collectors.toSet());

        List<RemoteFile> toDownload = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        for (RemoteFile file : manifest.files()) {
            if (!localHashes.contains(file.sha256()) && queued.add(file.sha256())) {
                toDownload.add(file);
            }
        }

        List<ModJar> toReplace = new ArrayList<>();
        List<ModJar> extras = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        int same = 0;
        for (ModJar jar : local) {
            if (hostHashes.contains(jar.sha256())) {
                if (kept.add(jar.sha256())) {
                    same++;
                } else {
                    toReplace.add(jar); // a second identical copy would be a duplicate mod
                }
            } else if (jar.modIds().stream().anyMatch(hostModIds::contains)) {
                toReplace.add(jar);
            } else {
                extras.add(jar);
            }
        }
        // Config files: copy the host's version of every file that is missing or different here.
        Map<String, String> localConfigs = new HashMap<>();
        for (ConfigFile config : ConfigFiles.scan(ConfigFiles.configDir())) {
            localConfigs.put(config.relative().toLowerCase(Locale.ROOT), config.sha256());
        }
        List<RemoteConfig> configs = new ArrayList<>();
        int configsSame = 0;
        for (RemoteConfig config : manifest.configs()) {
            if (config.sha256().equals(localConfigs.get(config.path().toLowerCase(Locale.ROOT)))) {
                configsSame++;
            } else {
                configs.add(config);
            }
        }
        return new Plan(address, manifest, List.copyOf(toDownload), List.copyOf(toReplace), List.copyOf(extras), same,
                List.copyOf(configs), configsSame);
    }

    public static Result apply(Plan plan, boolean removeExtras, boolean copyConfigs, Listener listener) throws IOException {
        Path mods = ModJars.modsDir();
        Files.createDirectories(mods);

        List<ModJar> remove = new ArrayList<>(plan.toReplace());
        if (removeExtras) {
            remove.addAll(plan.extras());
        }
        int removedNow = 0;
        int removeLater = 0;
        for (ModJar jar : remove) {
            if (PendingDeletes.delete(jar.path())) {
                removedNow++;
            } else {
                removeLater++;
            }
        }

        List<RemoteConfig> configs = copyConfigs ? plan.configs() : List.of();
        int index = 0;
        int count = plan.toDownload().size() + configs.size();
        for (RemoteFile file : plan.toDownload()) {
            index++;
            String name = ModJars.safeFileName(file.name());
            Path target = mods.resolve(name);
            if (Files.exists(target)) {
                // A different file with the same name. Replace it, or use a new name while it's in use.
                boolean alreadyQueued = PendingDeletes.isPending(target);
                if (!alreadyQueued && PendingDeletes.delete(target)) {
                    removedNow++;
                } else {
                    if (!alreadyQueued) {
                        removeLater++; // delete() above queued it for after Minecraft closes
                    }
                    name = ModJars.freeName(mods, name);
                    target = mods.resolve(name);
                }
            }
            int current = index;
            String shownName = name;
            Http.download(baseUrl(plan.address()) + LanProtocol.FILE_PATH + file.sha256(), target, file.size(), file.sha256(),
                    (done, total) -> listener.progress(current, count, shownName, done, total));
        }

        // Configs wait in a staging folder until Minecraft closes (see ConfigStaging).
        // A config that fails (say the host changed it a moment ago) is skipped, not fatal.
        int configsCopied = 0;
        if (!configs.isEmpty()) {
            ConfigStaging.clear(); // drop leftovers from an earlier sync that was never applied
        }
        for (RemoteConfig config : configs) {
            index++;
            int current = index;
            try {
                Path staged = ConfigStaging.stagedPath(config.path());
                Http.download(baseUrl(plan.address()) + LanProtocol.CONFIG_PATH + config.sha256(), staged, config.size(), config.sha256(),
                        (done, total) -> listener.progress(current, count, config.path(), done, total));
                configsCopied++;
            } catch (IOException e) {
                LanUpdate.LOGGER.warn("Skipped config file {} from the host: {}", config.path(), e.toString());
            }
        }
        return new Result(plan.toDownload().size(), removedNow, removeLater, configsCopied);
    }
}
