package com.jdj125.realism.client;

import com.jdj125.realism.Health;
import com.jdj125.realism.LifeSync;
import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.0 on the client: black and white vision in old times (until colour TV is invented), the Geiger counter's
 * clicks, and echoes: in a big enclosed space (a cave or a hall) sounds come back off the walls a moment later.
 */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class LifeClient {
    private static final ResourceLocation OLD_TIMES = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "shaders/post/old_times.json");
    private static PostChain ours;
    private static double clicks;

    /** The room around the player: average distance to the walls, or 0 if it isn't an enclosed space. */
    private static double room;
    private static final List<Echo> pending = new ArrayList<>();

    private record Echo(long due, SoundInstance sound) {
    }

    /** An echo is a sound too: mark them so they don't echo again. */
    private static class EchoSound extends SimpleSoundInstance {
        EchoSound(ResourceLocation id, SoundSource source, float volume, double x, double y, double z) {
            super(id, source, volume, 1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.LINEAR, x, y, z, false);
        }
    }

    private LifeClient() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            ours = null;
            pending.clear();
            return;
        }
        LifeSync s = LifeSync.latest;

        // Old times: the world is black and white.
        boolean bw = s.blackAndWhite() && RealismConfig.OLD_TIMES_VISION.get();
        PostChain current = mc.gameRenderer.currentEffect();
        if (bw && current == null) {
            // (Only when no other effect is on, like a spectated creeper's: ours comes back after.)
            mc.gameRenderer.loadEffect(OLD_TIMES);
            ours = mc.gameRenderer.currentEffect();
        } else if (!bw && ours != null) {
            if (current == ours) mc.gameRenderer.shutdownEffect();
            ours = null;
        }

        // The Geiger counter clicks faster the more radiation comes in (one Uranium Ore right next to you: ~2 a second).
        if (mc.player.isHolding(Health.GEIGER_COUNTER.get())) {
            clicks += s.rate() / 0.0001 / 20 + (mc.player.getRandom().nextFloat() < 0.01f ? 1 : 0);
            int n = 0;
            while (clicks >= 1 && n++ < 4) {
                clicks -= 1;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.8f + mc.player.getRandom().nextFloat() * 0.4f, 0.6f));
            }
            clicks = Math.min(clicks, 3);
        }

        // Measure the room once a second, and play echoes that are due.
        if (mc.player.tickCount % 20 == 0) room = measureRoom(mc);
        long now = mc.level.getGameTime();
        pending.removeIf(e -> {
            if (e.due() > now) return false;
            mc.getSoundManager().play(e.sound());
            return true;
        });
    }

    /** Casts 14 rays from the player's eyes: if most hit a wall and nothing sees the sky, it's an echoing room. */
    private static double measureRoom(Minecraft mc) {
        if (!RealismConfig.ECHO.get() || mc.level.canSeeSky(mc.player.blockPosition())) return 0;
        Vec3 eye = mc.player.getEyePosition();
        double total = 0;
        int hits = 0, rays = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes != 1 && axes != 3) continue;
                    rays++;
                    Vec3 dir = new Vec3(dx, dy, dz).normalize();
                    HitResult hit = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(48)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        hits++;
                        total += hit.getLocation().distanceTo(eye);
                    }
                }
        double avg = hits == 0 ? 0 : total / hits;
        return hits >= rays - 2 && avg >= 7 ? avg : 0;
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        Minecraft mc = Minecraft.getInstance();
        if (s == null || s instanceof EchoSound || room == 0 || mc.player == null || mc.level == null || s.isRelative()) return;
        SoundSource src = s.getSource();
        if (src != SoundSource.BLOCKS && src != SoundSource.PLAYERS && src != SoundSource.HOSTILE && src != SoundSource.NEUTRAL) return;
        if (mc.player.distanceToSqr(s.getX(), s.getY(), s.getZ()) > 24 * 24) return;
        // Sound travels about 343 m/s: there and back to the walls.
        long delay = Math.max(3, Math.round(room * 2 / 343 * 20 * 2.5));
        long now = mc.level.getGameTime();
        pending.add(new Echo(now + delay, new EchoSound(s.getLocation(), src, 0.35f, s.getX(), s.getY(), s.getZ())));
        pending.add(new Echo(now + delay * 2, new EchoSound(s.getLocation(), src, 0.15f, s.getX(), s.getY(), s.getZ())));
        if (pending.size() > 64) pending.subList(0, pending.size() - 64).clear();
    }
}
