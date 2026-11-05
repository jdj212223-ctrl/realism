package com.jdj125.realism.client;

import com.jdj125.realism.Realism;
import com.jdj125.realism.Rescue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The defibrillator keys: charge, clear, shock (Z, X, C by default, so they don't drop, walk or open the inventory
 * like Q/W/E would; rebind them in Controls). Each press sends the step for the patient you're looking at, or the
 * nearest collapsed player within 3 blocks. Claude's bot presses them by name through its keys.txt.
 */
public final class DefibKeys {
    static final String CATEGORY = "key.categories.realism";
    static final KeyMapping CHARGE = new KeyMapping("key.realism.defib_charge", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    static final KeyMapping CLEAR = new KeyMapping("key.realism.defib_clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);
    static final KeyMapping SHOCK = new KeyMapping("key.realism.defib_shock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);

    private DefibKeys() {
    }

    @EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
    public static final class Mod {
        private Mod() {
        }

        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(CHARGE);
            event.register(CLEAR);
            event.register(SHOCK);
        }
    }

    @EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
    public static final class Game {
        private Game() {
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            press(CHARGE, 1);
            press(CLEAR, 2);
            press(SHOCK, 3);
        }
    }

    private static void press(KeyMapping key, int step) {
        while (key.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || !mc.player.getMainHandItem().is(Rescue.DEFIBRILLATOR.get())) continue;
            Entity target = mc.crosshairPickEntity instanceof Player p ? p : null;
            if (target == null) {
                double best = 9;
                for (Player p : mc.level.players()) {
                    if (p == mc.player || p.getPose() != Pose.SLEEPING || p.isSleeping()) continue;
                    double d = p.distanceToSqr(mc.player);
                    if (d < best) { best = d; target = p; }
                }
            }
            if (target != null) PacketDistributor.sendToServer(new Rescue.DefibStep(step, target.getId()));
        }
    }
}
