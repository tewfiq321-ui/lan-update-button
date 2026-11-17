package io.github.tewfiq321ui.lanupdate.gui;

import io.github.tewfiq321ui.lanupdate.Config;
import io.github.tewfiq321ui.lanupdate.LanUpdate;
import io.github.tewfiq321ui.lanupdate.github.GithubUpdater;
import io.github.tewfiq321ui.lanupdate.util.Background;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Checks GitHub Releases for a newer version of this mod and installs it. */
public final class GithubUpdateScreen extends BaseScreen {
    private enum State { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, DONE, ERROR }

    private State state = State.CHECKING;
    private boolean started;
    private GithubUpdater.@Nullable Release release;
    private int percent;
    private Component error = Component.empty();

    public GithubUpdateScreen(@Nullable Screen parent) {
        super(Component.translatable("lanupdate.github.title"), parent);
    }

    @Override
    protected void init() {
        if (!this.started) {
            this.started = true;
            check();
        }
        super.init();
    }

    private void check() {
        this.state = State.CHECKING;
        Background.run("lanupdate-github-check", () -> {
            try {
                GithubUpdater.Release newest = GithubUpdater.findNewest();
                ui(() -> {
                    this.release = newest;
                    this.state = newest != null && GithubUpdater.isNewer(newest) ? State.AVAILABLE : State.UP_TO_DATE;
                });
            } catch (Exception e) {
                ui(() -> {
                    this.error = Component.translatable("lanupdate.common.error", Background.message(e));
                    this.state = State.ERROR;
                });
            }
        });
    }

    private void install() {
        GithubUpdater.Release target = Objects.requireNonNull(this.release);
        this.state = State.DOWNLOADING;
        this.percent = 0;
        refresh();
        Background.run("lanupdate-github-download", () -> {
            try {
                int[] lastShown = {0};
                GithubUpdater.install(target, (done, total) -> {
                    int now = total > 0 ? (int) (done * 100 / total) : 0;
                    if (now != lastShown[0]) {
                        lastShown[0] = now;
                        ui(() -> this.percent = now);
                    }
                });
                ui(() -> this.state = State.DONE);
            } catch (Exception e) {
                ui(() -> {
                    this.error = Component.translatable("lanupdate.common.error", Background.message(e));
                    this.state = State.ERROR;
                });
            }
        });
    }

    @Override
    protected void buildBody(LinearLayout body) {
        switch (this.state) {
            case CHECKING -> body.addChild(text(Component.translatable("lanupdate.github.checking", Config.githubRepo())));
            case UP_TO_DATE -> {
                if (this.release == null) {
                    body.addChild(text(Component.translatable("lanupdate.github.no_release", LanUpdate.minecraftVersion(), Config.githubRepo())));
                } else {
                    body.addChild(text(Component.translatable("lanupdate.github.up_to_date", LanUpdate.modVersion()).withStyle(ChatFormatting.GREEN)));
                }
            }
            case AVAILABLE -> {
                GithubUpdater.Release available = Objects.requireNonNull(this.release);
                body.addChild(text(Component.translatable("lanupdate.github.available", available.version(), LanUpdate.modVersion()).withStyle(ChatFormatting.YELLOW)));
                body.addChild(button(Component.translatable("lanupdate.github.install"), _ -> install()));
            }
            case DOWNLOADING -> body.addChild(text(Component.translatable("lanupdate.github.downloading",
                    Objects.requireNonNull(this.release).version(), this.percent)));
            case DONE -> body.addChild(text(Component.translatable("lanupdate.github.done",
                    Objects.requireNonNull(this.release).version()).withStyle(ChatFormatting.GREEN)));
            case ERROR -> {
                body.addChild(text(this.error.copy().withStyle(ChatFormatting.RED)));
                body.addChild(button(Component.translatable("lanupdate.github.retry"), _ -> {
                    check();
                    refresh();
                }));
            }
        }
    }

    @Override
    protected void buildFooter(LinearLayout footer) {
        switch (this.state) {
            case DOWNLOADING -> {
                Button wait = footer.addChild(Button.builder(Component.translatable("lanupdate.common.wait"), _ -> {}).build());
                wait.active = false;
            }
            case DONE -> {
                footer.addChild(Button.builder(Component.translatable("lanupdate.common.quit"), _ -> this.minecraft.stop()).build());
                footer.addChild(Button.builder(Component.translatable("lanupdate.common.later"), _ -> onClose()).build());
            }
            default -> footer.addChild(Button.builder(CommonComponents.GUI_BACK, _ -> onClose()).build());
        }
    }

    @Override
    public void onClose() {
        if (this.state != State.DOWNLOADING) {
            super.onClose();
        }
    }
}
