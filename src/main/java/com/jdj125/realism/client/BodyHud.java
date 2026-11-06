package com.jdj125.realism.client;

import com.jdj125.realism.BodySync;
import com.jdj125.realism.LifeSync;
import com.jdj125.realism.Mind;
import com.jdj125.realism.Realism;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * A small body panel in the top-left corner: water and sleep (how close you are to dying of each), a stamina bar,
 * blood (liters and type) and any injuries or bleeding. While collapsed, asleep or under surgery the whole screen is
 * black instead. Stays out of the way: water/sleep only show once they matter.
 */
@EventBusSubscriber(modid = Realism.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BodyHud {
    private BodyHud() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "body"), BodyHud::render);
    }

    private static int color(float danger) {
        return danger >= 0.9f ? 0xFFFF4040 : danger >= 0.66f ? 0xFFFF9F30 : danger >= 0.33f ? 0xFFFFE060 : 0xFFE0E0E0;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BodySync s = BodySync.latest;
        // Collapsed, or under surgery: nothing but black. Asleep: black with drifting dreams (or nightmares, when
        // you're feeling low), unless the server turned that off.
        if ((s.injuries() & (BodySync.COLLAPSED | BodySync.UNCONSCIOUS)) != 0) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
            return;
        }
        LifeSync life = LifeSync.latest;
        // Sentenced: the screen fades to black. No gore, nothing to see.
        if ((life.flags() & LifeSync.SENTENCED) != 0) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xF0000000);
            Component c = Component.translatable("realism.law.sentenced_screen");
            g.drawString(mc.font, c, g.guiWidth() / 2 - mc.font.width(c) / 2, g.guiHeight() / 2, 0xFF909090, false);
            return;
        }
        if (mc.player.isSleeping() && (s.injuries() & BodySync.SLEEP_BLACK) != 0) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
            boolean nightmare = s.mood() < 30;
            long t = mc.player.getSleepTimer();
            int line = (int) ((t / 40) % 8) + 1;
            int alpha = (int) (Math.sin((t % 40) / 40.0 * Math.PI) * 200) + 20;
            Component c = Component.translatable("realism.sleep_drift." + (nightmare ? "bad." : "good.") + line);
            int x = g.guiWidth() / 2 - mc.font.width(c) / 2 + (int) (Math.sin(t / 15.0) * 20);
            g.drawString(mc.font, c, x, g.guiHeight() / 2, (alpha << 24) | (nightmare ? 0xC06060 : 0x9090D0), false);
            return;
        }
        if (mc.options.hideGui || mc.player.isCreative() || mc.player.isSpectator()) return;
        Font font = mc.font;
        int x = 4, y = 4;
        if (s.thirst() >= 0.2f) {
            g.drawString(font, Component.translatable("realism.hud.water", Math.round((1 - s.thirst()) * 100)), x, y, color(s.thirst()));
            y += 10;
        }
        if (s.awake() >= 0.2f) {
            g.drawString(font, Component.translatable("realism.hud.sleep", Math.round((1 - s.awake()) * 100)), x, y, color(s.awake()));
            y += 10;
        }
        if (s.stamina() < 100) {
            int w = 50;
            g.fill(x, y + 1, x + w, y + 5, 0x80000000);
            int fill = Math.round(w * s.stamina() / 100f);
            g.fill(x, y + 1, x + fill, y + 5, s.stamina() < 20 ? 0xFFFF5050 : 0xFF60D060);
            y += 8;
        }
        int f = s.injuries();
        if (s.mood() < 50) {
            float danger = s.mood() < Mind.DEPRESSED ? 1 : s.mood() < Mind.LOW ? 0.7f : 0.4f;
            g.drawString(font, Component.translatable("realism.hud.mood", s.mood()), x, y, color(danger));
            y += 10;
        }
        if (s.blood() < 4.95f || (f & (BodySync.BLEED_LIGHT | BodySync.BLEED_HEAVY | BodySync.BLEED_SEVERE)) != 0) {
            float danger = s.blood() <= 2.6f ? 1 : s.blood() <= 3.5f ? 0.7f : s.blood() <= 4.2f ? 0.4f : 0;
            g.drawString(font, Component.translatable("realism.hud.blood", String.format(java.util.Locale.ROOT, "%.1f", s.blood()), s.bloodType()),
                    x, y, color(danger));
            y += 10;
        }
        // 1.0.0: illnesses, radiation, jail, and the year in old times.
        int lf = life.flags();
        if ((lf & LifeSync.CHOSEN) != 0 && life.year() < com.jdj125.realism.Era.TODAY) {
            g.drawString(font, Component.translatable("realism.hud.year", life.year()), x, y, 0xFFD8C8A0);
            y += 10;
        }
        if (life.dose() >= 0.05f || mc.player.isHolding(com.jdj125.realism.Health.GEIGER_COUNTER.get())) {
            float danger = life.dose() >= 4 ? 1 : life.dose() >= 2 ? 0.7f : life.dose() >= 1 ? 0.4f : 0;
            g.drawString(font, Component.translatable("realism.hud.radiation", String.format(java.util.Locale.ROOT, "%.2f", life.dose()),
                    Math.round(life.rate() * 3600 * 1000)), x, y, color(danger));
            y += 10;
        }
        String[] lkeys = {"flu", "food_poisoning", "infection", "jailed"};
        int[] lbits = {LifeSync.FLU, LifeSync.FOOD_POISONING, LifeSync.INFECTION, LifeSync.JAILED};
        for (int i = 0; i < lbits.length; i++) {
            if ((lf & lbits[i]) == 0) continue;
            g.drawString(font, Component.translatable("realism.hud." + lkeys[i]), x, y, 0xFFFFB070);
            y += 10;
        }
        if (life.cancerStage() > 0) {
            g.drawString(font, Component.translatable("realism.hud.cancer", life.cancerStage()), x, y, 0xFFFF9FD0);
            y += 10;
        }
        String[] keys = {"neck", "paralysed", "bleed1", "bleed2", "bleed3", "tourniquet", "leg", "tear", "burn1", "burn2", "burn3", "frostbite", "tear_bad"};
        int[] bits = {BodySync.BROKEN_NECK, BodySync.PARALYSED, BodySync.BLEED_LIGHT, BodySync.BLEED_HEAVY, BodySync.BLEED_SEVERE, BodySync.TOURNIQUET, BodySync.BROKEN_LEG,
                BodySync.ARM_TEAR, BodySync.BURN1, BodySync.BURN2, BodySync.BURN3, BodySync.FROSTBITE, BodySync.BAD_TEAR};
        for (int i = 0; i < bits.length; i++) {
            if ((f & bits[i]) == 0 || (bits[i] == BodySync.ARM_TEAR && (f & BodySync.BAD_TEAR) != 0)) continue;
            g.drawString(font, Component.translatable("realism.hud." + keys[i]), x, y, 0xFFFF7070);
            y += 10;
        }
    }
}
