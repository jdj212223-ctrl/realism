package com.jdj125.realism.client;

import com.jdj125.realism.BodyLook;
import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realism 4.9: draws injuries on top of a player's skin. Each overlay is a 64x64 texture in the normal skin layout
 * (mostly see-through), drawn over the player model, sometimes tinted (bruises change colour as they heal).
 */
public class SkinLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    /** What each player looks like (entity id to {@link BodyLook} bits), sent by the server. */
    public static final Map<Integer, Integer> LOOKS = new ConcurrentHashMap<>();

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(Realism.MODID, "textures/entity/skin/" + name + ".png");
    }

    private static final ResourceLocation CUTS_1 = tex("cuts_1"), CUTS_2 = tex("cuts_2"), CUTS_3 = tex("cuts_3"),
            BANDAGE = tex("bandage"), TOURNIQUET = tex("tourniquet"), LEG_CAST = tex("leg_cast"), ARM_WRAP = tex("arm_wrap"),
            BURN = tex("burn"), FROSTBITE = tex("frostbite"), PALE = tex("pale"), EYE_BAGS = tex("eye_bags"),
            SWEAT = tex("sweat"), WET = tex("wet_pants"), BRUISE = tex("bruise");

    /** Forget everyone's look when leaving a world (entity ids are reused). */
    @net.neoforged.fml.common.EventBusSubscriber(modid = Realism.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Reset {
        private Reset() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            LOOKS.clear();
        }
    }

    public SkinLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!RealismConfig.SKIN_VISUALS.get() || player.isInvisible()) return;
        Integer look = LOOKS.get(player.getId());
        if (look == null || look == 0) return;
        int f = look;
        boolean mild = RealismConfig.MILD_VISUALS.get();
        // Colour first (under everything else): pale skin, bruises, burns, frostbite, wet pants.
        if ((f & BodyLook.PALE) != 0) draw(pose, buffers, light, player, PALE, 0xA0FFFFFF);
        int bruise = BodyLook.bruise(f);
        if (bruise > 0) draw(pose, buffers, light, player, BRUISE, switch (bruise) {
            case 1 -> 0xC0C03030; // fresh: red
            case 2 -> 0xC0603080; // healing: purple
            default -> 0x90B0A030; // almost gone: yellow
        });
        int burn = BodyLook.burn(f);
        if (burn > 0) draw(pose, buffers, light, player, BURN, burn == 1 ? 0x90FF6040 : burn == 2 ? 0xC0D03020 : 0xE0401818);
        if ((f & BodyLook.FROSTBITE) != 0) draw(pose, buffers, light, player, FROSTBITE, 0xC0B0D8FF);
        if ((f & BodyLook.WET) != 0) draw(pose, buffers, light, player, WET, 0x90000000);
        if ((f & BodyLook.EYE_BAGS) != 0) draw(pose, buffers, light, player, EYE_BAGS, 0xB0302040);
        if ((f & BodyLook.SWEAT) != 0) draw(pose, buffers, light, player, SWEAT, 0xFFFFFFFF);
        // Then wounds (not in mild mode) and what's wrapped over them.
        int bleeding = BodyLook.bleeding(f);
        if (!mild && bleeding > 0) draw(pose, buffers, light, player, bleeding == 1 ? CUTS_1 : bleeding == 2 ? CUTS_2 : CUTS_3, 0xFFFFFFFF);
        if ((f & BodyLook.ARM_TEAR) != 0) draw(pose, buffers, light, player, ARM_WRAP, 0xFFFFFFFF);
        if ((f & BodyLook.LEG_CAST) != 0) draw(pose, buffers, light, player, LEG_CAST, 0xFFFFFFFF);
        if ((f & BodyLook.BANDAGE) != 0) draw(pose, buffers, light, player, BANDAGE, 0xFFFFFFFF);
        if ((f & BodyLook.TOURNIQUET) != 0) draw(pose, buffers, light, player, TOURNIQUET, 0xFFFFFFFF);
    }

    private void draw(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, ResourceLocation texture, int argb) {
        getParentModel().renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(texture)), light,
                LivingEntityRenderer.getOverlayCoords(player, 0), argb);
    }
}
