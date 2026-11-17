package io.github.tewfiq321ui.lanupdate.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A title, a scrollable body and a row of footer buttons. Subclasses describe the body and
 * footer for their current state and call {@link #refresh()} whenever the state changes.
 */
public abstract class BaseScreen extends Screen {
    protected static final int BODY_WIDTH = 310;
    protected static final int BUTTON_WIDTH = 220;

    protected final @Nullable Screen parent;
    private @Nullable HeaderAndFooterLayout layout;

    protected BaseScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
        this.layout = layout;
        layout.addTitleHeader(this.title, this.font);

        LinearLayout footer = layout.addToFooter(new LinearLayout(0, 0, LinearLayout.Orientation.HORIZONTAL));
        footer.spacing(6);
        footer.defaultCellSetting().paddingTop(5);
        buildFooter(footer);

        LinearLayout body = new LinearLayout(0, 0, LinearLayout.Orientation.VERTICAL);
        body.spacing(6);
        body.defaultCellSetting().alignHorizontallyCenter();
        buildBody(body);

        layout.addToContents(new ScrollableLayout(this.minecraft, body, layout.getContentHeight())).setMinWidth(BODY_WIDTH);
        layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
        }
    }

    protected abstract void buildBody(LinearLayout body);

    protected void buildFooter(LinearLayout footer) {
        footer.addChild(Button.builder(CommonComponents.GUI_BACK, _ -> onClose()).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Rebuilds the widgets if this screen is the one being shown. Call on the render thread. */
    protected void refresh() {
        if (Minecraft.getInstance().gui.screen() == this) {
            this.rebuildWidgets();
        }
    }

    /** Runs a state change on the render thread (safe from background threads), then refreshes. */
    protected void ui(Runnable change) {
        Minecraft.getInstance().execute(() -> {
            change.run();
            refresh();
        });
    }

    protected AbstractWidget text(Component message) {
        return FocusableTextWidget.builder(message, this.font)
                .alwaysShowBorder(false)
                .backgroundFill(FocusableTextWidget.BackgroundFill.NEVER)
                .maxWidth(BODY_WIDTH)
                .build();
    }

    protected Button button(Component label, Button.OnPress onPress) {
        return Button.builder(label, onPress).width(BUTTON_WIDTH).build();
    }
}
