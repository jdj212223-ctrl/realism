package com.jdj125.realism.client;

import com.jdj125.realism.Drone;
import com.jdj125.realism.Realism;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Flying a drone: the camera moves to the drone, your keys steer it, your body stays put. */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class DroneClient {
    private DroneClient() {
    }

    static int piloting = -1;
    static CameraType before;
    static float forward, strafe, up;

    public static boolean flying() {
        return piloting >= 0;
    }

    static void start(Drone.Flyer d) {
        Minecraft mc = Minecraft.getInstance();
        piloting = d.getId();
        before = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(d);
        PacketDistributor.sendToServer(new Drone.Pilot(d.getId(), true));
    }

    static void stop(boolean tellServer) {
        Minecraft mc = Minecraft.getInstance();
        if (tellServer && piloting >= 0) PacketDistributor.sendToServer(new Drone.Pilot(piloting, false));
        piloting = -1;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (before != null) mc.options.setCameraType(before);
        before = null;
    }

    /** The server said no (not yours, too far, no controller) or the flight ended. */
    public static void serverSaid(Drone.Pilot msg) {
        if (msg.drone() < 0 && flying()) stop(false);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (flying()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.isUseItem()) stop(true);
            else if (event.isAttack() && mc.level.getEntity(piloting) instanceof Drone.Flyer d && !d.fpv()) {
                // Say cheese: a photo from the drone's camera (saved like a screenshot).
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> mc.execute(() -> mc.gui.getChat().addMessage(msg)));
                mc.player.playSound(SoundEvents.SPYGLASS_USE, 1f, 2f);
            }
            return;
        }
        if (!event.isUseItem() || !(mc.player.getItemInHand(event.getHand()).is(Drone.CONTROLLER.get()))) return;
        // The nearest drone within 64 blocks (the server checks it's really yours).
        Drone.Flyer best = null;
        for (Entity e : mc.level.entitiesForRendering())
            if (e instanceof Drone.Flyer d && d.distanceTo(mc.player) < 64 && (best == null || d.distanceTo(mc.player) < best.distanceTo(mc.player))) best = d;
        event.setCanceled(true);
        if (best == null) {
            mc.player.displayClientMessage(Component.translatable("realism.drone.none"), true);
            return;
        }
        start(best);
    }

    @SubscribeEvent
    public static void onMoveInput(MovementInputUpdateEvent event) {
        if (!flying()) return;
        var in = event.getInput();
        forward = in.forwardImpulse;
        strafe = in.leftImpulse;
        up = in.jumping ? 1 : in.shiftKeyDown ? -1 : 0;
        // Your body stays still while you fly.
        in.forwardImpulse = in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!flying()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || !(mc.level.getEntity(piloting) instanceof Drone.Flyer d) || d.isRemoved()) {
            stop(true);
            return;
        }
        if (mc.getCameraEntity() != d) mc.setCameraEntity(d);
        d.setYRot(mc.player.getYRot());
        d.setXRot(mc.player.getXRot());
        PacketDistributor.sendToServer(new Drone.Sticks(piloting, forward, strafe, up, mc.player.getYRot(), mc.player.getXRot()));
    }

    /** The view turns with your mouse right away (the drone's own rotation follows a tick later). */
    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (!flying() || mc.player == null) return;
        float pt = (float) event.getPartialTick();
        event.setYaw(mc.player.getViewYRot(pt));
        event.setPitch(mc.player.getViewXRot(pt));
    }

    @SubscribeEvent
    public static void onLeave(ClientPlayerNetworkEvent.LoggingOut event) {
        piloting = -1;
        before = null;
    }

    /** Draws a drone as its item model (a little quadcopter), turned the way it faces. */
    public static class Renderer extends EntityRenderer<Drone.Flyer> {
        public Renderer(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public void render(Drone.Flyer d, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
            pose.pushPose();
            pose.translate(0, 0.15, 0);
            pose.mulPose(Axis.YP.rotationDegrees(180 - net.minecraft.util.Mth.rotLerp(partialTick, d.yRotO, d.getYRot())));
            if (d.flying()) pose.translate(0, Math.sin((d.tickCount + partialTick) * 0.6) * 0.02, 0);
            pose.scale(1.4f, 1.4f, 1.4f);
            ItemStack stack = new ItemStack(d.fpv() ? Drone.FPV_DRONE.get() : Drone.CAMERA_DRONE.get());
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, d.level(), d.getId());
            pose.popPose();
            super.render(d, yaw, partialTick, pose, buffers, light);
        }

        @Override
        public ResourceLocation getTextureLocation(Drone.Flyer d) {
            return TextureAtlas.LOCATION_BLOCKS;
        }
    }
}
