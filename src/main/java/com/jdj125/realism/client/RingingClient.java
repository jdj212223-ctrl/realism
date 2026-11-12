package com.jdj125.realism.client;

import com.jdj125.realism.Realism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Realism 4.2, client side: ringing ears after an explosion. A high ringing tone, and most other sounds are lost. */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class RingingClient {
    private RingingClient() {
    }

    private static int ticks;
    private static final Set<SoundInstance> OURS = Collections.newSetFromMap(new WeakHashMap<>());

    public static void start(int t) {
        ticks = Math.max(ticks, t);
        ring();
    }

    public static boolean ringing() {
        return ticks > 0;
    }

    private static void ring() {
        float volume = Math.min(0.5f, ticks / 600f + 0.08f);
        SoundInstance s = SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 2.0f, volume);
        OURS.add(s);
        Minecraft.getInstance().getSoundManager().play(s);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ticks <= 0) return;
        ticks--;
        if (ticks % 30 == 0 && ticks > 0) ring();
    }

    /** While your ears ring, most sounds don't get through (the louder the ringing, the fewer). */
    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (ticks <= 0 || event.getSound() == null || OURS.contains(event.getOriginalSound())) return;
        float lost = Math.min(0.85f, 0.4f + ticks / 1200f);
        if (Math.random() < lost) event.setSound(null);
    }
}
