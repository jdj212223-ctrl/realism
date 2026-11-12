package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Realism 3.4: drones. Place one, then hold a Drone Controller and right-click to fly it: you see through its camera
 * while your body stays where it is (W/S/A/D, jump = up, sneak = down, the mouse turns it; right-click again to stop).
 * <ul>
 * <li>Camera Drone: slow and steady, it hovers by itself. Left-click while flying to take a photo (a screenshot).
 * 6 minutes of battery.</li>
 * <li>FPV Drone: a fast racing drone. No hovering: hold jump for thrust or it drops. Hit a wall fast and it breaks.
 * 2 minutes of battery, and flying FPV for too long makes you dizzy.</li>
 * </ul>
 * Batteries charge while the drone sits still. Punch a drone to pick it up. The signal reaches 160 blocks.
 */
public final class Drone {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final Supplier<EntityType<Flyer>> TYPE = ENTITIES.register("drone",
            () -> EntityType.Builder.<Flyer>of(Flyer::new, MobCategory.MISC).sized(0.7f, 0.3f).eyeHeight(0.15f)
                    .clientTrackingRange(12).updateInterval(1).build("drone"));
    public static final DeferredItem<Item> CAMERA_DRONE = ITEMS.register("camera_drone", () -> new Spawner(false));
    public static final DeferredItem<Item> FPV_DRONE = ITEMS.register("fpv_drone", () -> new Spawner(true));
    public static final DeferredItem<Item> CONTROLLER = ITEMS.register("drone_controller", () -> new Item(new Item.Properties().stacksTo(1)));

    static final int RANGE = 160, CAMERA_BATTERY = 7200, FPV_BATTERY = 2400;

    private Drone() {
    }

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    /** Places a drone where you click. */
    static class Spawner extends Item {
        final boolean fpv;

        Spawner(boolean fpv) {
            super(new Item.Properties().stacksTo(1));
            this.fpv = fpv;
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
            Vec3 at = Vec3.atBottomCenterOf(ctx.getClickedPos().relative(ctx.getClickedFace()));
            Flyer d = new Flyer(TYPE.get(), sl);
            d.setFpv(fpv);
            d.setBattery(fpv ? FPV_BATTERY : CAMERA_BATTERY);
            if (ctx.getPlayer() != null) {
                d.owner = ctx.getPlayer().getUUID();
                d.setYRot(ctx.getPlayer().getYRot());
            }
            d.moveTo(at.x, at.y, at.z, d.getYRot(), 0);
            sl.addFreshEntity(d);
            ctx.getItemInHand().shrink(1);
            return InteractionResult.CONSUME;
        }
    }

    // ---- The drone itself ----

    public static class Flyer extends Entity {
        private static final EntityDataAccessor<Boolean> FPV = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.BOOLEAN);
        private static final EntityDataAccessor<Integer> BATTERY = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.BOOLEAN);

        public UUID owner;
        /** Who's flying it right now (server side), and their latest stick input. */
        public UUID pilot;
        float forward, strafe, up;
        int pilotTicks, quietTicks;

        public Flyer(EntityType<? extends Flyer> type, Level level) {
            super(type, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {
            b.define(FPV, false).define(BATTERY, CAMERA_BATTERY).define(FLYING, false);
        }

        public boolean fpv() {
            return entityData.get(FPV);
        }

        void setFpv(boolean v) {
            entityData.set(FPV, v);
        }

        public int battery() {
            return entityData.get(BATTERY);
        }

        void setBattery(int v) {
            entityData.set(BATTERY, v);
        }

        public int maxBattery() {
            return fpv() ? FPV_BATTERY : CAMERA_BATTERY;
        }

        /** True while someone is flying it (synced: the rotors spin and it buzzes). */
        public boolean flying() {
            return entityData.get(FLYING);
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag t) {
            setFpv(t.getBoolean("fpv"));
            setBattery(t.getInt("battery"));
            if (t.hasUUID("owner")) owner = t.getUUID("owner");
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag t) {
            t.putBoolean("fpv", fpv());
            t.putInt("battery", battery());
            if (owner != null) t.putUUID("owner", owner);
        }

        @Override
        public boolean isPickable() {
            return !isRemoved();
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        /** Punch it to pick it up. */
        @Override
        public boolean hurt(DamageSource source, float amount) {
            if (level().isClientSide || isRemoved()) return true;
            crash(source.getEntity() instanceof Player);
            return true;
        }

        /** Drops back to its item (picked up), or breaks apart into a little scrap (a crash). */
        void crash(boolean pickedUp) {
            if (pickedUp) spawnAtLocation(new ItemStack(fpv() ? FPV_DRONE.get() : CAMERA_DRONE.get()));
            else {
                level().playSound(null, blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 1f, 0.7f);
                ((ServerLevel) level()).sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, getX(), getY(), getZ(), 10, 0.2, 0.1, 0.2, 0.02);
                spawnAtLocation(new ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 3));
            }
            ServerPlayer p = pilot();
            if (p != null) p.displayClientMessage(Component.translatable(pickedUp ? "realism.drone.picked_up" : "realism.drone.crashed")
                    .withStyle(ChatFormatting.RED), true);
            discard();
        }

        public ServerPlayer pilot() {
            return pilot == null || !(level() instanceof ServerLevel sl) ? null : (ServerPlayer) sl.getPlayerByUUID(pilot);
        }

        @Override
        public void tick() {
            super.tick();
            if (level().isClientSide) return; // the server moves it; positions arrive every tick
            ServerPlayer p = pilot();
            if (p != null && (!p.isAlive() || p.distanceTo(this) > RANGE || p.level() != level())) {
                p.displayClientMessage(Component.translatable("realism.drone.signal_lost").withStyle(ChatFormatting.RED), true);
                stopFlying(p);
                p = null;
            }
            boolean powered = battery() > 0;
            Vec3 v = getDeltaMovement();
            if (p != null && powered) {
                setBattery(battery() - 1);
                float yaw = getYRot() * Mth.DEG_TO_RAD;
                double fx = -Mth.sin(yaw) * forward + Mth.cos(yaw) * strafe, fz = Mth.cos(yaw) * forward + Mth.sin(yaw) * strafe;
                if (fpv()) {
                    // Racing drone: momentum, gravity, real thrust.
                    v = v.add(fx * 0.12, up > 0 ? 0.11 : -0.06, fz * 0.12).multiply(0.94, 0.94, 0.94);
                    if (up < 0) v = v.add(0, -0.06, 0);
                    if (v.length() > 1.4) v = v.normalize().scale(1.4);
                } else {
                    // Camera drone: steady, stops when you let go.
                    v = new Vec3(fx * 0.35, up * 0.25, fz * 0.35).add(v.scale(0.5)).scale(0.6);
                }
                pilotTicks++;
                if (fpv()) motionSickness(p);
                if (tickCount % 20 == 0) p.displayClientMessage(Component.translatable("realism.drone.hud",
                        battery() * 100 / maxBattery(), (int) p.distanceTo(this)).withStyle(battery() < maxBattery() / 5 ? ChatFormatting.RED : ChatFormatting.AQUA), true);
                if (tickCount % 6 == 0) level().playSound(null, blockPosition(), SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, fpv() ? 0.5f : 0.3f, fpv() ? 2f : 1.6f);
            } else {
                // Nobody flying (or a flat battery): a camera drone with power hovers; anything else falls.
                if (!fpv() && powered && p == null && !onGround() && pilotTicks > 0) v = v.scale(0.8);
                else v = v.add(0, -0.08, 0).multiply(0.9, 0.98, 0.9);
                if (p != null && !powered && tickCount % 40 == 0)
                    p.displayClientMessage(Component.translatable("realism.drone.battery_flat").withStyle(ChatFormatting.RED), true);
                if (p == null) pilotTicks = 0;
                // Charging while it sits still.
                if (onGround() && p == null && battery() < maxBattery()) setBattery(Math.min(maxBattery(), battery() + 2));
            }
            double speed = v.horizontalDistance();
            setDeltaMovement(v);
            move(MoverType.SELF, v);
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.6, 0, 0.6));
            // An FPV drone that slams into a wall breaks.
            if (fpv() && horizontalCollision && speed > 0.55) crash(false);
        }

        private void motionSickness(ServerPlayer p) {
            if (pilotTicks > 20 * 60 && pilotTicks % 200 == 0) {
                p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false, true));
                p.displayClientMessage(Component.translatable("realism.drone.dizzy").withStyle(ChatFormatting.GREEN), false);
            }
            if (pilotTicks > 20 * 150 && pilotTicks % 600 == 0) Food.vomit(p);
        }

        void startFlying(ServerPlayer p) {
            pilot = p.getUUID();
            entityData.set(FLYING, true);
            forward = strafe = up = 0;
        }

        void stopFlying(ServerPlayer p) {
            if (p != null && p.getUUID().equals(pilot)) pilot = null;
            entityData.set(FLYING, false);
            forward = strafe = up = 0;
            if (p != null) Pilot.tell(p, -1);
        }

        void steer(float forward, float strafe, float up, float yaw, float pitch) {
            this.forward = Mth.clamp(forward, -1, 1);
            this.strafe = Mth.clamp(strafe, -1, 1);
            this.up = Mth.clamp(up, -1, 1);
            setYRot(yaw);
            setXRot(Mth.clamp(pitch, -90, 90));
        }
    }

    // ---- Network ----

    /** Client to server: start (on = true) or stop flying a drone. Server to client: -1 = you stopped flying. */
    public record Pilot(int drone, boolean on) implements CustomPacketPayload {
        public static final Type<Pilot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "drone_pilot"));
        public static final StreamCodec<ByteBuf, Pilot> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Pilot::drone, ByteBufCodecs.BOOL, Pilot::on, Pilot::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void tell(ServerPlayer p, int drone) {
            if (p.connection != null && p.connection.hasChannel(TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new Pilot(drone, drone >= 0));
        }
    }

    /** Client to server, every tick while flying: the sticks. */
    public record Sticks(int drone, float forward, float strafe, float up, float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<Sticks> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "drone_sticks"));
        public static final StreamCodec<ByteBuf, Sticks> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Sticks::drone,
                ByteBufCodecs.FLOAT, Sticks::forward, ByteBufCodecs.FLOAT, Sticks::strafe, ByteBufCodecs.FLOAT, Sticks::up,
                ByteBufCodecs.FLOAT, Sticks::yaw, ByteBufCodecs.FLOAT, Sticks::pitch, Sticks::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server: someone asks to fly (or stop flying) a drone. It must be theirs, in range, and they need a controller. */
    public static void handlePilot(ServerPlayer p, Pilot msg) {
        if (!(p.level().getEntity(msg.drone()) instanceof Flyer d)) return;
        if (!msg.on()) {
            d.stopFlying(p);
            return;
        }
        boolean hasController = p.getMainHandItem().is(CONTROLLER.get()) || p.getOffhandItem().is(CONTROLLER.get());
        if (!hasController || p.distanceTo(d) > RANGE || (d.owner != null && !d.owner.equals(p.getUUID())) || (d.pilot != null && !d.pilot.equals(p.getUUID()))) {
            Pilot.tell(p, -1);
            return;
        }
        d.startFlying(p);
        p.displayClientMessage(Component.translatable(d.fpv() ? "realism.drone.start_fpv" : "realism.drone.start_camera").withStyle(ChatFormatting.AQUA), false);
    }

    public static void handleSticks(ServerPlayer p, Sticks s) {
        if (p.level().getEntity(s.drone()) instanceof Flyer d && p.getUUID().equals(d.pilot)) d.steer(s.forward(), s.strafe(), s.up(), s.yaw(), s.pitch());
    }
}
