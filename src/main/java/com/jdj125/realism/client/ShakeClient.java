package com.jdj125.realism.client;

import com.jdj125.realism.Realism;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Realism 4.3, client side: the camera shakes during an earthquake (stronger quakes shake harder, then it fades). */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class ShakeClient {
    private ShakeClient() {
    }

    private static int ticks, total, strength;

    public static void start(int t, int s) {
        ticks = total = Math.max(1, t);
        strength = s;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ticks > 0) ticks--;
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (ticks <= 0) return;
        float fade = (float) ticks / total, amount = strength * 0.9f * fade;
        double t = event.getPartialTick() + System.nanoTime() / 2.0e7;
        event.setPitch(event.getPitch() + (float) Math.sin(t * 1.7) * amount);
        event.setYaw(event.getYaw() + (float) Math.sin(t * 2.3) * amount);
        event.setRoll(event.getRoll() + (float) Math.sin(t * 3.1) * amount * 0.6f);
    }
}
