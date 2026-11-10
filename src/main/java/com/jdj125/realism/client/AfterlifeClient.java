package com.jdj125.realism.client;

import com.jdj125.realism.Afterlife;
import com.jdj125.realism.Realism;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The judgment cutscene: the camera is stuck in third person (pressing F5 just snaps back), the screen fades to black,
 * "Your life is being judged..." appears, and then a light (heaven), red glow (hell) or nothing at all (the dark)
 * swirls in like a portal opening before you're taken there.
 */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class AfterlifeClient {
    private AfterlifeClient() {}

    private static String verdict = "";
    private static int total, left;
    private static CameraType before;

    public static void start(Afterlife.Judgment j) {
        Minecraft mc = Minecraft.getInstance();
        verdict = j.verdict();
        total = left = j.ticks();
        if (before == null) before = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    public static boolean playing() {
        return left > 0;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (left <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        // F5 is cancelled: whatever the player presses, the camera goes straight back to third person.
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        if (--left == 0) {
            mc.options.setCameraType(before == null ? CameraType.FIRST_PERSON : before);
            before = null;
        }
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        if (left <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float t = 1f - (left - event.getPartialTick().getGameTimeDeltaPartialTick(false)) / total; // 0 -> 1
        // 1) fade to black, 2) the words, 3) the "portal": a growing glow of the verdict's colour.
        float black = Mth.clamp(t / 0.25f, 0, 1) * (verdict.equals("dark") ? 1f : 1f - Mth.clamp((t - 0.6f) / 0.4f, 0, 1));
        g.fill(0, 0, w, h, ((int) (black * 255) << 24));
        if (t > 0.6f && !verdict.equals("dark")) {
            float p = (t - 0.6f) / 0.4f;
            int rgb = verdict.equals("heaven") ? 0xFFF6D8 : 0xC21807;
            int cx = w / 2, cy = h / 2;
            // Rings of light that open up from the middle, like stepping through a portal.
            for (int i = 6; i >= 1; i--) {
                int r = (int) (p * Math.max(w, h) * i / 6f);
                int a = (int) (Mth.clamp(p * 1.4f, 0, 1) * (40 + 20 * (6 - i)));
                g.fill(cx - r, cy - r / 2, cx + r, cy + r / 2, (a << 24) | rgb);
            }
        }
        Component line = t < 0.35f ? Component.translatable("realism.afterlife.cutscene.died")
                : t < 0.6f ? Component.translatable("realism.afterlife.cutscene.judged")
                : Component.translatable("realism.afterlife.cutscene." + verdict);
        int color = verdict.equals("hell") && t >= 0.6f ? 0xFF5544 : verdict.equals("heaven") && t >= 0.6f ? 0xFFF2B0 : 0xFFFFFF;
        g.pose().pushPose();
        g.pose().scale(2f, 2f, 1f);
        g.drawCenteredString(mc.font, line, w / 4, h / 4 - 6, color);
        g.pose().popPose();
    }
}
