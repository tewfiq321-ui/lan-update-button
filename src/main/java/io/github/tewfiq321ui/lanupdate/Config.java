package io.github.tewfiq321ui.lanupdate;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config. Editable in game: Mods > LAN Update Button > Config. */
public final class Config {
    public static final String DEFAULT_REPO = "tewfiq321-ui/lan-update-button";
    public static final int DEFAULT_PORT = 25580;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> GITHUB_REPO = BUILDER
            .comment("GitHub repository (owner/name) that the Update button checks for new versions of this mod.")
            .define("githubRepo", DEFAULT_REPO);

    public static final ModConfigSpec.BooleanValue CHECK_ON_STARTUP = BUILDER
            .comment("Check GitHub for a newer version when Minecraft starts. The Update button turns yellow when one is found.")
            .define("checkOnStartup", true);

    public static final ModConfigSpec.BooleanValue SHARE_ON_LAN = BUILDER
            .comment("While your world is open to LAN, let friends on the same network copy your mods with their Update button.")
            .define("shareModsOnLan", true);

    public static final ModConfigSpec.BooleanValue SHARE_CONFIGS_ON_LAN = BUILDER
            .comment("Also share your config files (mod settings) with friends who copy your mods.",
                    "Turn this off if a mod keeps private data, such as a login token, in its config file.")
            .define("shareConfigsOnLan", true);

    public static final ModConfigSpec.IntValue SHARE_PORT = BUILDER
            .comment("TCP port used to share your mods on the LAN. Friends who type your address by hand need this port.")
            .defineInRange("lanSharePort", DEFAULT_PORT, 1024, 65535);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    // The getters fall back to defaults if the config file has not been loaded yet.

    public static String githubRepo() {
        try {
            String repo = GITHUB_REPO.get().trim();
            return repo.isEmpty() ? DEFAULT_REPO : repo;
        } catch (RuntimeException notLoaded) {
            return DEFAULT_REPO;
        }
    }

    public static boolean checkOnStartup() {
        try {
            return CHECK_ON_STARTUP.getAsBoolean();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    public static boolean shareOnLan() {
        try {
            return SHARE_ON_LAN.getAsBoolean();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    public static boolean shareConfigsOnLan() {
        try {
            return SHARE_CONFIGS_ON_LAN.getAsBoolean();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    public static int sharePort() {
        try {
            return SHARE_PORT.getAsInt();
        } catch (RuntimeException notLoaded) {
            return DEFAULT_PORT;
        }
    }
}
