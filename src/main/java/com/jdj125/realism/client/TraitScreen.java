package com.jdj125.realism.client;

import com.jdj125.realism.Traits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Realism 4.8: "Who are you?" Pick ONE trait. Each card shows its perks (+) and flaws (-). It can't be skipped with
 * Esc: you need a trait to start (The Average Joe has no perks and no flaws).
 */
public class TraitScreen extends Screen {
    private final List<Traits.Trait> traits;
    private Traits.Trait selected;
    private Button confirm;

    public TraitScreen(boolean depression) {
        super(Component.translatable("realism.trait.screen.title"));
        this.traits = Traits.available(depression);
        this.selected = traits.get(0);
    }

    public static void open(boolean depression) {
        Minecraft.getInstance().setScreen(new TraitScreen(depression));
    }

    @Override
    protected void init() {
        int x = width / 2 - 200, y = 50;
        for (Traits.Trait t : traits) {
            addRenderableWidget(Button.builder(Component.translatable("realism.trait." + t.id + ".name"), b -> {
                selected = t;
            }).bounds(x, y, 150, 20).build());
            y += 24;
        }
        confirm = addRenderableWidget(Button.builder(Component.translatable("realism.trait.screen.confirm"), b -> {
            PacketDistributor.sendToServer(new Traits.ChooseTrait(selected.id));
            onClose();
        }).bounds(width / 2 - 20, height - 40, 220, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 18, 0xFFFFE07A);
        g.drawCenteredString(font, Component.translatable("realism.trait.screen.ask"), width / 2, 32, 0xFFB0B0B0);
        // The card of the selected trait.
        int x = width / 2 - 20, y = 50, w = 220;
        g.fill(x - 6, y - 6, x + w + 6, height - 50, 0xAA000000);
        g.drawString(font, Component.translatable("realism.trait." + selected.id + ".name"), x, y, 0xFFFFE07A);
        y += 14;
        y = wrap(g, Component.translatable("realism.trait." + selected.id + ".desc"), x, y, w, 0xFFFFFFFF) + 6;
        y = wrap(g, Component.translatable("realism.trait." + selected.id + ".perk"), x, y, w, 0xFF7CFC7C) + 4;
        wrap(g, Component.translatable("realism.trait." + selected.id + ".flaw"), x, y, w, 0xFFFF7C7C);
        confirm.setMessage(Component.translatable("realism.trait.screen.confirm_named", Component.translatable("realism.trait." + selected.id + ".name")));
    }

    private int wrap(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, w)) {
            g.drawString(font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
