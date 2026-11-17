package io.github.tewfiq321ui.lanupdate;

import com.mojang.logging.LogUtils;
import io.github.tewfiq321ui.lanupdate.github.GithubUpdater;
import io.github.tewfiq321ui.lanupdate.gui.UpdateMenuScreen;
import io.github.tewfiq321ui.lanupdate.lan.LanShareServer;
import io.github.tewfiq321ui.lanupdate.util.ConfigStaging;
import io.github.tewfiq321ui.lanupdate.util.PendingDeletes;
import io.github.tewfiq321ui.lanupdate.util.SelfCleanup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

/**
 * LAN Update Button: adds an "Update" button to the title, multiplayer and pause menus.
 * The button can update this mod from GitHub Releases, or copy the mods of a friend whose
 * world is open to LAN. Everything here is client-only.
 */
@Mod(value = LanUpdate.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = LanUpdate.MODID, value = Dist.CLIENT)
public final class LanUpdate {
    public static final String MODID = "lanupdate";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCounter;

    public LanUpdate(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Finish anything left over from last time: files that were still in use, and copied
        // config files that weren't applied because Minecraft didn't close normally.
        PendingDeletes.retryPending();
        SelfCleanup.removeOldCopies();
        ConfigStaging.apply();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LanShareServer.stop();
            ConfigStaging.apply(); // after mods have saved their own settings on the way out
            PendingDeletes.onShutdown();
        }, "lanupdate-shutdown"));
    }

    public static String version(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
    }

    public static String modVersion() {
        return version(MODID);
    }

    public static String minecraftVersion() {
        return version("minecraft");
    }

    public static String neoForgeVersion() {
        return version("neoforge");
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (Config.checkOnStartup()) {
            GithubUpdater.checkInBackground();
        }
    }

    @SubscribeEvent
    static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen || screen instanceof PauseScreen)) {
            return;
        }

        MutableComponent label = GithubUpdater.availableUpdate() != null
                ? Component.translatable("lanupdate.button.update_available").withStyle(ChatFormatting.YELLOW)
                : Component.translatable("lanupdate.button.update");
        Button button = Button.builder(label, _ -> Minecraft.getInstance().gui.setScreen(new UpdateMenuScreen(screen)))
                .bounds(4, 4, 80, 20)
                .build();
        button.setTooltip(Tooltip.create(Component.translatable("lanupdate.button.update.tooltip")));
        event.addListener(button);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter % 20 != 0) {
            return;
        }
        // Share our mods folder only while the integrated server is open to LAN.
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        boolean share = server != null && server.isPublished() && Config.shareOnLan();
        LanShareServer.setActive(share, share ? server.getMotd() : "");
    }
}
