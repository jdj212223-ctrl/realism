package com.jdj125.realism.client;

import com.jdj125.realism.Tech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The computer screen: a desktop drawn in the style of the chosen operating system (our own simple look-alikes made
 * of colored shapes, no real logos or artwork), with a window that can start a game (mood boost) or shut down.
 */
public class OsScreen extends Screen {
    private final int os;

    public OsScreen(int os) {
        super(Component.translatable("realism.os." + Tech.OSES[os]));
        this.os = os;
    }

    public static void open(int os) {
        Minecraft.getInstance().setScreen(new OsScreen(Math.floorMod(os, Tech.OSES.length)));
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;
        addRenderableWidget(Button.builder(Component.translatable("realism.pc.play"), b -> {
            PacketDistributor.sendToServer(new Tech.PlayGame());
            onClose();
        }).bounds(cx - 70, cy + 8, 140, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.pc.shutdown"), b -> onClose())
                .bounds(cx - 70, cy + 32, 140, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int w = width, h = height;
        int cx = w / 2, cy = h / 2;
        switch (Tech.OSES[os]) {
            case "classic_mac" -> {
                for (int y = 0; y < h; y += 2) for (int x = (y / 2) % 2 * 2; x < w; x += 4) g.fill(x, y, x + 2, y + 2, 0xFF8A8A8A);
                g.fill(0, 0, w, 12, 0xFFFFFFFF);
                g.fill(0, 12, w, 13, 0xFF000000);
                window(g, cx, cy, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000);
            }
            case "mac_x" -> {
                for (int y = 0; y < h; y++) g.fill(0, y, w, y + 1, 0xFF000000 | lerp(0x1E4FA8, 0x7FB4F0, y / (float) h));
                g.fill(0, 0, w, 11, 0xCCF2F2F2);
                g.fill(cx - 90, h - 22, cx + 90, h - 4, 0x99FFFFFF);
                int[] icons = {0xFF3D8BF0, 0xFF46C06A, 0xFFF0B13D, 0xFFE0524F, 0xFF9A6BE0};
                for (int i = 0; i < icons.length; i++) g.fill(cx - 80 + i * 34, h - 20, cx - 66 + i * 34, h - 6, icons[i]);
                window(g, cx, cy, 0xFFECECEC, 0xFFB8B8B8, 0xFFE8E8E8, 0xFF333333);
            }
            case "be" -> {
                g.fill(0, 0, w, h, 0xFF336698);
                window(g, cx, cy, 0xFFDDDDDD, 0xFF888888, 0xFFFFCB08, 0xFF000000);
            }
            case "next" -> {
                g.fill(0, 0, w, h, 0xFF505050);
                for (int i = 0; i < 5; i++) g.fill(w - 26, 6 + i * 26, w - 4, 28 + i * 26, 0xFF2A2A2A);
                window(g, cx, cy, 0xFFAAAAAA, 0xFF000000, 0xFF000000, 0xFFFFFFFF);
            }
            case "windows" -> {
                g.fill(0, 0, w, h, 0xFF008080);
                g.fill(0, h - 16, w, h, 0xFFC0C0C0);
                g.fill(0, h - 16, w, h - 15, 0xFFFFFFFF);
                g.fill(2, h - 14, 46, h - 2, 0xFFD4D0C8);
                g.drawString(font, Component.translatable("realism.pc.menu"), 6, h - 12, 0xFF000000, false);
                window(g, cx, cy, 0xFFC0C0C0, 0xFF808080, 0xFF000080, 0xFFFFFFFF);
            }
            default -> { // linux
                g.fill(0, 0, w, h, 0xFF1B1F27);
                g.fill(0, 0, w, 10, 0xFF2E3440);
                g.fill(cx - 110, cy - 70, cx + 110, cy - 22, 0xFF0C0C0C);
                g.drawString(font, "$ echo hello", cx - 104, cy - 64, 0xFF8FD18F, false);
                g.drawString(font, "hello", cx - 104, cy - 54, 0xFFDDDDDD, false);
                g.drawString(font, "$ _", cx - 104, cy - 44, 0xFF8FD18F, false);
                window(g, cx, cy + 16, 0xFF3B4252, 0xFF2E3440, 0xFF434C5E, 0xFFECEFF4);
            }
        }
    }

    /** A simple window frame around the buttons: body, border, title bar and title text colors. */
    private void window(GuiGraphics g, int cx, int cy, int body, int border, int bar, int text) {
        g.fill(cx - 82, cy - 16, cx + 82, cy + 60, border);
        g.fill(cx - 81, cy - 15, cx + 81, cy + 59, body);
        g.fill(cx - 81, cy - 15, cx + 81, cy - 3, bar);
        g.drawCenteredString(font, title, cx, cy - 13, text);
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int gr = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (gr << 8) | bl;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
