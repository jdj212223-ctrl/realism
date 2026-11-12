package com.jdj125.realism.client;

import com.jdj125.realism.World;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The phone: signal bars, the time, your bank balance and country, and texting. Pick a contact (players online),
 * type a message, Send. Texts arrive as server messages, so nobody's chat filter hides them.
 */
public class PhoneScreen extends Screen {
    private final World.OpenPhone info;
    private final String[] contacts;
    private int contact;
    private EditBox message;

    public PhoneScreen(World.OpenPhone info) {
        super(Component.translatable("item.realism.phone"));
        this.info = info;
        this.contacts = info.contacts().isEmpty() ? new String[0] : info.contacts().split(",");
    }

    public static void open(World.OpenPhone info) {
        Minecraft.getInstance().setScreen(new PhoneScreen(info));
    }

    private int px() { return width / 2 - 60; }
    private int py() { return height / 2 - 100; }

    @Override
    protected void init() {
        int x = px(), y = py();
        if (contacts.length > 0) {
            addRenderableWidget(Button.builder(Component.literal(contacts[contact]), b -> {
                contact = (contact + 1) % contacts.length;
                b.setMessage(Component.literal(contacts[contact]));
            }).bounds(x + 8, y + 92, 104, 18).build());
            message = new EditBox(font, x + 8, y + 116, 104, 16, Component.translatable("realism.phone.type"));
            message.setMaxLength(200);
            addRenderableWidget(message);
            setInitialFocus(message);
            addRenderableWidget(Button.builder(Component.translatable("realism.phone.send"), b -> send()).bounds(x + 8, y + 138, 104, 18).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.call_ambulance"), b -> {
            PacketDistributor.sendToServer(new World.CallAmbulance());
            onClose();
        }).bounds(x + 8, y + 62, 51, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.call_fire"), b -> {
            PacketDistributor.sendToServer(new World.CallFire());
            onClose();
        }).bounds(x + 61, y + 62, 51, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.close"), b -> onClose()).bounds(x + 30, y + 176, 60, 16).build());
    }

    private void send() {
        if (message == null || message.getValue().isBlank() || contacts.length == 0) return;
        PacketDistributor.sendToServer(new World.SendText(contacts[contact], message.getValue()));
        message.setValue("");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (message != null && message.isFocused() && (key == 257 || key == 335)) {
            send();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = px(), y = py();
        // The phone body and screen.
        g.fill(x - 4, y - 4, x + 124, y + 204, 0xFF15151A);
        g.fill(x, y, x + 120, y + 200, 0xFF1F2C44);
        g.fill(x, y, x + 120, y + 12, 0xFF0F1626);
        g.drawString(font, World.now(), x + 4, y + 2, 0xFFFFFFFF, false);
        // Signal bars.
        for (int i = 0; i < 4; i++) {
            int h = 3 + i * 2;
            g.fill(x + 96 + i * 5, y + 10 - h, x + 99 + i * 5, y + 10, info.signal() ? 0xFFFFFFFF : 0xFF555555);
        }
        g.drawCenteredString(font, Component.translatable(info.signal() ? "realism.phone.signal" : "realism.phone.no_signal_bar"), x + 60, y + 18, info.signal() ? 0xFF8FE08F : 0xFFFF7070);
        g.drawCenteredString(font, Component.translatable("realism.phone.balance", info.balance()), x + 60, y + 36, 0xFFFFE07A);
        g.drawCenteredString(font, info.country().isEmpty() ? Component.translatable("realism.phone.no_country") : Component.literal(info.country()), x + 60, y + 50, 0xFFB0C4FF);
        g.drawCenteredString(font, Component.translatable(contacts.length == 0 ? "realism.phone.nobody" : "realism.phone.text_to"), x + 60, y + 78, 0xFFDDDDDD);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
