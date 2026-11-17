package io.github.tewfiq321ui.lanupdate.gui;

import com.mojang.blaze3d.Blaze3D;
import io.github.tewfiq321ui.lanupdate.Config;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.github.GithubUpdater;
import io.github.tewfiq321ui.lanupdate.lan.LanProtocol;
import io.github.tewfiq321ui.lanupdate.lan.LanShareServer;
import io.github.tewfiq321ui.lanupdate.util.ModJars;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The screen behind the Update button: pick GitHub update or LAN sync, and see LAN sharing status. */
public final class UpdateMenuScreen extends BaseScreen {
    public UpdateMenuScreen(@Nullable Screen parent) {
        super(Component.translatable("lanupdate.menu.title"), parent);
    }

    @Override
    protected void buildBody(LinearLayout body) {
        body.addChild(text(Component.translatable("lanupdate.menu.versions",
                LanUpdate.modVersion(), LanUpdate.minecraftVersion(), LanUpdate.neoForgeVersion()).withStyle(ChatFormatting.GRAY)));

        GithubUpdater.Release update = GithubUpdater.availableUpdate();
        if (update != null) {
            body.addChild(text(Component.translatable("lanupdate.menu.update_available", update.version()).withStyle(ChatFormatting.YELLOW)));
        }

        body.addChild(button(Component.translatable("lanupdate.menu.github"), _ -> this.minecraft.gui.setScreen(new GithubUpdateScreen(this))));
        body.addChild(button(Component.translatable("lanupdate.menu.lan"), _ -> this.minecraft.gui.setScreen(new LanSyncScreen(this))));

        LanShareServer.Status sharing = LanShareServer.status();
        if (sharing != null) {
            String ip = LanProtocol.localAddress();
            String address = (ip == null ? "?" : ip) + ":" + sharing.port();
            body.addChild(text(Component.translatable("lanupdate.menu.sharing", address).withStyle(ChatFormatting.GREEN)));
            String configKey = Config.shareConfigsOnLan() ? "lanupdate.menu.sharing_configs" : "lanupdate.menu.sharing_no_configs";
            body.addChild(text(Component.translatable(configKey).withStyle(ChatFormatting.GRAY)));
        } else {
            String key = Config.shareOnLan() ? "lanupdate.menu.not_sharing" : "lanupdate.menu.sharing_disabled";
            body.addChild(text(Component.translatable(key).withStyle(ChatFormatting.GRAY)));
        }

        body.addChild(button(Component.translatable("lanupdate.menu.open_mods"), _ -> Blaze3D.openPath(ModJars.modsDir())));
    }

    @Override
    protected void buildFooter(LinearLayout footer) {
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, _ -> onClose()).build());
    }
}
