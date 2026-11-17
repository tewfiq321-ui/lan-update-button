package io.github.tewfiq321ui.lanupdate.gui;

import io.github.tewfiq321ui.lanupdate.Config;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.lan.LanDiscovery;
import io.github.tewfiq321ui.lanupdate.lan.LanProtocol;
import io.github.tewfiq321ui.lanupdate.lan.LanSync;
import io.github.tewfiq321ui.lanupdate.util.Background;
import io.github.tewfiq321ui.lanupdate.util.ModJars.ModJar;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Finds a friend's LAN world, shows what would change, and copies their mods. */
public final class LanSyncScreen extends BaseScreen {
    private enum State { SEARCHING, LOADING, PLAN, SYNCING, DONE, ERROR }

    private static final int LIST_LIMIT = 40;
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.\\-]+(:\\d{1,5})?");

    private State state = State.SEARCHING;
    private @Nullable LanDiscovery discovery;
    private @Nullable String discoveryError;
    private List<String> shownHosts = List.of();
    private String manualAddress = "";
    private boolean badAddress;
    private String target = "";
    private LanSync.@Nullable Plan plan;
    private boolean removeExtras;
    private boolean copyConfigs = true;
    private Component progress = Component.empty();
    private LanSync.@Nullable Result result;
    private Component error = Component.empty();

    public LanSyncScreen(@Nullable Screen parent) {
        super(Component.translatable("lanupdate.lan.title"), parent);
    }

    @Override
    protected void init() {
        if (this.discovery == null && this.discoveryError == null) {
            startDiscovery();
        }
        super.init();
    }

    private void startDiscovery() {
        LanDiscovery listener;
        try {
            listener = new LanDiscovery();
        } catch (IOException e) {
            this.discoveryError = Background.message(e);
            return;
        }
        this.discovery = listener;
        // Refresh the host list once a second; stop listening once this screen is gone.
        Background.run("lanupdate-lan-search-ui", () -> {
            while (!listener.isClosed()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().gui.screen() != this) {
                        listener.close();
                    } else if (this.state == State.SEARCHING && !hostKeys(listener.hosts()).equals(this.shownHosts)) {
                        refresh();
                    }
                });
            }
        });
    }

    private static List<String> hostKeys(List<LanDiscovery.Found> hosts) {
        return hosts.stream().map(LanDiscovery.Found::key).toList();
    }

    // ---- actions ----

    private void connectManual() {
        String address = this.manualAddress.strip();
        if (!ADDRESS.matcher(address).matches()) {
            this.badAddress = true;
            refresh();
            return;
        }
        this.badAddress = false;
        if (!address.contains(":")) {
            address = address + ":" + Config.sharePort();
        }
        connect(address);
    }

    private void connect(String address) {
        this.target = address;
        this.state = State.LOADING;
        refresh();
        Background.run("lanupdate-lan-load", () -> {
            try {
                LanSync.Plan loaded = LanSync.prepare(address);
                ui(() -> {
                    this.plan = loaded;
                    this.removeExtras = false;
                    this.copyConfigs = true;
                    this.state = State.PLAN;
                });
            } catch (Exception e) {
                ui(() -> {
                    this.error = connectError(address, e);
                    this.state = State.ERROR;
                });
            }
        });
    }

    private static Component connectError(String address, Exception e) {
        if (e instanceof ConnectException || e instanceof SocketTimeoutException
                || e instanceof NoRouteToHostException || e instanceof UnknownHostException) {
            return Component.translatable("lanupdate.lan.connect_failed", address);
        }
        return Component.translatable("lanupdate.common.error", Background.message(e));
    }

    private void startSync() {
        LanSync.Plan chosen = this.plan;
        if (chosen == null) {
            return;
        }
        boolean extras = this.removeExtras;
        boolean configs = this.copyConfigs;
        this.state = State.SYNCING;
        this.progress = Component.translatable("lanupdate.lan.removing");
        refresh();
        Background.run("lanupdate-lan-sync", () -> {
            try {
                String[] lastShown = {""};
                LanSync.Result done = LanSync.apply(chosen, extras, configs, (index, count, fileName, bytes, total) -> {
                    int percent = total > 0 ? (int) (bytes * 100 / total) : 0;
                    String key = index + ":" + percent;
                    if (!key.equals(lastShown[0])) {
                        lastShown[0] = key;
                        Component line = Component.translatable("lanupdate.lan.syncing", index, count, fileName, percent);
                        ui(() -> this.progress = line);
                    }
                });
                ui(() -> {
                    this.result = done;
                    this.state = State.DONE;
                });
            } catch (Exception e) {
                ui(() -> {
                    this.error = Component.translatable("lanupdate.common.error", Background.message(e));
                    this.state = State.ERROR;
                });
            }
        });
    }

    private void backToSearch() {
        this.plan = null;
        this.error = Component.empty();
        this.state = State.SEARCHING;
        refresh();
    }

    // ---- layout ----

    @Override
    protected void buildBody(LinearLayout body) {
        switch (this.state) {
            case SEARCHING -> buildSearching(body);
            case LOADING -> body.addChild(text(Component.translatable("lanupdate.lan.loading", this.target)));
            case PLAN -> buildPlan(body);
            case SYNCING -> body.addChild(text(this.progress));
            case DONE -> buildDone(body);
            case ERROR -> body.addChild(text(this.error.copy().withStyle(ChatFormatting.RED)));
        }
    }

    private void buildSearching(LinearLayout body) {
        body.addChild(text(Component.translatable("lanupdate.lan.intro")));
        if (this.discoveryError != null) {
            body.addChild(text(Component.translatable("lanupdate.lan.discovery_failed", this.discoveryError).withStyle(ChatFormatting.RED)));
        }

        List<LanDiscovery.Found> hosts = this.discovery != null ? this.discovery.hosts() : List.of();
        this.shownHosts = hostKeys(hosts);
        if (hosts.isEmpty() && this.discoveryError == null) {
            body.addChild(text(Component.translatable("lanupdate.lan.searching").withStyle(ChatFormatting.GRAY)));
        }
        for (LanDiscovery.Found host : hosts) {
            body.addChild(button(Component.translatable("lanupdate.lan.host_button", host.name(), host.key()), _ -> connect(host.key())));
        }

        body.addChild(text(Component.translatable("lanupdate.lan.manual")));
        EditBox box = new EditBox(this.font, BUTTON_WIDTH, 20, Component.translatable("lanupdate.lan.address"));
        box.setMaxLength(64);
        box.setValue(this.manualAddress);
        box.setResponder(value -> this.manualAddress = value);
        body.addChild(box);
        if (this.badAddress) {
            body.addChild(text(Component.translatable("lanupdate.lan.bad_address").withStyle(ChatFormatting.RED)));
        }
        body.addChild(button(Component.translatable("lanupdate.lan.connect"), _ -> connectManual()));
    }

    private void buildPlan(LinearLayout body) {
        LanSync.Plan current = Objects.requireNonNull(this.plan);
        LanProtocol.Manifest manifest = current.manifest();
        body.addChild(text(Component.translatable("lanupdate.lan.host", manifest.host(), current.address())));

        if (!manifest.minecraft().equals(LanUpdate.minecraftVersion()) || !manifest.neoforge().equals(LanUpdate.neoForgeVersion())) {
            body.addChild(text(Component.translatable("lanupdate.lan.version_mismatch",
                    manifest.minecraft(), manifest.neoforge(), LanUpdate.minecraftVersion(), LanUpdate.neoForgeVersion())
                    .withStyle(ChatFormatting.YELLOW)));
        }

        if (current.nothingToDo(this.removeExtras, this.copyConfigs)) {
            body.addChild(text(Component.translatable("lanupdate.lan.same").withStyle(ChatFormatting.GREEN)));
        } else {
            body.addChild(text(Component.translatable("lanupdate.lan.summary",
                    current.toDownload().size(), Background.formatBytes(current.downloadBytes()), current.toReplace().size(), current.same())));
            if (!current.configs().isEmpty()) {
                body.addChild(text(Component.translatable("lanupdate.lan.configs_summary", current.configs().size(), current.configsSame())));
            }
            body.addChild(text(Component.translatable("lanupdate.lan.trust").withStyle(ChatFormatting.GOLD)));
        }

        if (!current.extras().isEmpty()) {
            String key = this.removeExtras ? "lanupdate.lan.extras_remove" : "lanupdate.lan.extras_keep";
            body.addChild(button(Component.translatable(key, current.extras().size()), _ -> {
                this.removeExtras = !this.removeExtras;
                refresh();
            }));
        }
        if (!current.configs().isEmpty()) {
            String key = this.copyConfigs ? "lanupdate.lan.configs_copy" : "lanupdate.lan.configs_keep";
            body.addChild(button(Component.translatable(key, current.configs().size()), _ -> {
                this.copyConfigs = !this.copyConfigs;
                refresh();
            }));
        } else if (current.manifest().configs().isEmpty()) {
            body.addChild(text(Component.translatable("lanupdate.lan.configs_not_shared").withStyle(ChatFormatting.GRAY)));
        }

        addList(body, "lanupdate.lan.list_download", current.toDownload().stream().map(LanProtocol.RemoteFile::name).toList(), ChatFormatting.GREEN);
        List<String> removing = new ArrayList<>(current.toReplace().stream().map(ModJar::fileName).toList());
        if (this.removeExtras) {
            removing.addAll(current.extras().stream().map(ModJar::fileName).toList());
        } else {
            addList(body, "lanupdate.lan.list_keep", current.extras().stream().map(ModJar::fileName).toList(), ChatFormatting.GRAY);
        }
        addList(body, "lanupdate.lan.list_remove", removing, ChatFormatting.RED);
        if (this.copyConfigs) {
            addList(body, "lanupdate.lan.list_configs", current.configs().stream().map(LanProtocol.RemoteConfig::path).toList(), ChatFormatting.AQUA);
        }
    }

    private void buildDone(LinearLayout body) {
        LanSync.Result done = Objects.requireNonNull(this.result);
        body.addChild(text(Component.translatable("lanupdate.lan.done", done.downloaded(), done.removedNow()).withStyle(ChatFormatting.GREEN)));
        if (done.removeAfterExit() > 0) {
            body.addChild(text(Component.translatable("lanupdate.lan.done_later", done.removeAfterExit())));
        }
        if (done.configsCopied() > 0) {
            body.addChild(text(Component.translatable("lanupdate.lan.done_configs", done.configsCopied())));
        }
        body.addChild(text(Component.translatable("lanupdate.lan.restart")));
    }

    private void addList(LinearLayout body, String headingKey, List<String> names, ChatFormatting color) {
        if (names.isEmpty()) {
            return;
        }
        body.addChild(text(Component.translatable(headingKey).withStyle(ChatFormatting.BOLD)));
        int shown = Math.min(names.size(), LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            body.addChild(text(Component.literal(names.get(i)).withStyle(color)));
        }
        if (names.size() > shown) {
            body.addChild(text(Component.translatable("lanupdate.lan.more", names.size() - shown).withStyle(ChatFormatting.GRAY)));
        }
    }

    @Override
    protected void buildFooter(LinearLayout footer) {
        switch (this.state) {
            case PLAN -> {
                Button sync = footer.addChild(Button.builder(Component.translatable("lanupdate.lan.sync"), _ -> startSync()).build());
                sync.active = this.plan != null && !this.plan.nothingToDo(this.removeExtras, this.copyConfigs);
                footer.addChild(Button.builder(CommonComponents.GUI_BACK, _ -> backToSearch()).build());
            }
            case LOADING, SYNCING -> {
                Button wait = footer.addChild(Button.builder(Component.translatable("lanupdate.common.wait"), _ -> {}).build());
                wait.active = false;
            }
            case ERROR -> footer.addChild(Button.builder(CommonComponents.GUI_BACK, _ -> backToSearch()).build());
            case DONE -> {
                footer.addChild(Button.builder(Component.translatable("lanupdate.common.quit"), _ -> this.minecraft.stop()).build());
                footer.addChild(Button.builder(Component.translatable("lanupdate.common.later"), _ -> onClose()).build());
            }
            case SEARCHING -> super.buildFooter(footer);
        }
    }

    @Override
    public void onClose() {
        if (this.state == State.LOADING || this.state == State.SYNCING) {
            return; // let the download finish
        }
        if (this.discovery != null) {
            this.discovery.close();
        }
        super.onClose();
    }
}
