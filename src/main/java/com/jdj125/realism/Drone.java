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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * Realism 3.4 drones, grown up in 4.10. Place one, then hold a controller and right-click to fly it: you see through its
 * camera while your body stays where it is (W/S/A/D, jump = up, sneak = down, the mouse turns it; right-click again to
 * stop). Five drones (our own designs, inspired by real ones):
 * <ul>
 * <li>Camera Drone: slow and steady, hovers by itself. Left-click while flying to take a photo. 6 minutes.</li>
 * <li>FPV Drone: a fast racer. Hold jump for thrust or it drops. Hit a wall fast and it breaks. 2 minutes. Needs goggles.</li>
 * <li>Pocket Drone: tiny, launch it from your palm (use it in the air) and it follows you. 4 minutes, short range.</li>
 * <li>Cinewhoop FPV: an FPV drone with prop guards: it bounces off walls instead of breaking. 4 minutes. Needs goggles.</li>
 * <li>Enterprise Drone: big, slow, wind-proof, a thermal camera that shows living things through walls. 15 minutes,
 * long range, only pairs with the Enterprise Controller.</li>
 * </ul>
 * Controllers: the basic Drone Controller, the Screen Controller (+25% range), the Pro Controller (+60%), and the
 * Enterprise Controller (+60%, the only one that flies the Enterprise Drone, and it flies nothing else).
 * <p>
 * Propellers: two marked (coloured tips) and two plain. Hitting things chips them (wobbly flight, more battery) or
 * breaks them (it can't fly). Put new ones on in the right order: the marked ones go on the marked motors FIRST. Get it
 * wrong and the drone flips over the moment it takes off. Rain and wind push drones around and cold drains batteries
 * faster. When the signal is lost, or the battery gets low, the drone flies home by itself.
 */
public final class Drone {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    /** The drone models. The order is saved and synced: only ever add new ones at the end. */
    public enum Model {
        CAMERA("camera_drone", false, false, 7200, 160),
        FPV("fpv_drone", true, false, 2400, 160),
        POCKET("pocket_drone", false, true, 4800, 60),
        CINE("cine_fpv_drone", true, true, 4800, 200),
        ENTERPRISE("enterprise_drone", false, false, 18000, 320);

        public final String id;
        /** Flown with goggles and real thrust (no hovering). */
        public final boolean fpv;
        /** Prop guards: it bounces off walls instead of breaking its propellers. */
        public final boolean guarded;
        public final int battery, range;

        Model(String id, boolean fpv, boolean guarded, int battery, int range) {
            this.id = id;
            this.fpv = fpv;
            this.guarded = guarded;
            this.battery = battery;
            this.range = range;
        }

        public static Model of(int i) {
            return i >= 0 && i < values().length ? values()[i] : CAMERA;
        }
    }

    public static final Supplier<EntityType<Flyer>> TYPE = ENTITIES.register("drone",
            () -> EntityType.Builder.<Flyer>of(Flyer::new, MobCategory.MISC).sized(0.7f, 0.3f).eyeHeight(0.15f)
                    .clientTrackingRange(12).updateInterval(1).build("drone"));
    public static final DeferredItem<Item> CAMERA_DRONE = ITEMS.register("camera_drone", () -> new Spawner(Model.CAMERA));
    public static final DeferredItem<Item> FPV_DRONE = ITEMS.register("fpv_drone", () -> new Spawner(Model.FPV));
    public static final DeferredItem<Item> POCKET_DRONE = ITEMS.register("pocket_drone", () -> new Spawner(Model.POCKET));
    public static final DeferredItem<Item> CINE_FPV_DRONE = ITEMS.register("cine_fpv_drone", () -> new Spawner(Model.CINE));
    public static final DeferredItem<Item> ENTERPRISE_DRONE = ITEMS.register("enterprise_drone", () -> new Spawner(Model.ENTERPRISE));
    public static final DeferredItem<Item> CONTROLLER = ITEMS.register("drone_controller", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> SCREEN_CONTROLLER = ITEMS.register("screen_controller", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> PRO_CONTROLLER = ITEMS.register("pro_controller", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> ENTERPRISE_CONTROLLER = ITEMS.register("enterprise_controller", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> FPV_GOGGLES = ITEMS.register("fpv_goggles", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> PROPS_MARKED = ITEMS.register("propellers_marked", () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> PROPS_PLAIN = ITEMS.register("propellers_plain", () -> new Item(new Item.Properties().stacksTo(16)));

    /** The base range of the first drones (the Camera and FPV drones). */
    static final int RANGE = 160, CAMERA_BATTERY = 7200, FPV_BATTERY = 2400;

    private Drone() {
    }

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    public static Item item(Model m) {
        return switch (m) {
            case CAMERA -> CAMERA_DRONE.get();
            case FPV -> FPV_DRONE.get();
            case POCKET -> POCKET_DRONE.get();
            case CINE -> CINE_FPV_DRONE.get();
            case ENTERPRISE -> ENTERPRISE_DRONE.get();
        };
    }

    /** Any of the four controllers. */
    public static boolean isController(ItemStack s) {
        return s.is(CONTROLLER.get()) || s.is(SCREEN_CONTROLLER.get()) || s.is(PRO_CONTROLLER.get()) || s.is(ENTERPRISE_CONTROLLER.get());
    }

    /** How much further a controller reaches (the basic one: 1.0). */
    public static float controllerRange(ItemStack s) {
        if (s.is(SCREEN_CONTROLLER.get())) return 1.25f;
        if (s.is(PRO_CONTROLLER.get()) || s.is(ENTERPRISE_CONTROLLER.get())) return 1.6f;
        return 1f;
    }

    /** Whether this controller pairs with this drone: the Enterprise Controller only with the Enterprise Drone, and back. */
    public static boolean pairs(ItemStack controller, Model m) {
        if (!isController(controller)) return false;
        return controller.is(ENTERPRISE_CONTROLLER.get()) == (m == Model.ENTERPRISE);
    }

    /** The controller a player is holding (main hand first), or empty. */
    static ItemStack heldController(Player p) {
        if (isController(p.getMainHandItem())) return p.getMainHandItem();
        if (isController(p.getOffhandItem())) return p.getOffhandItem();
        return ItemStack.EMPTY;
    }

    /** Places a drone where you click. The Pocket Drone can also be launched straight from your palm (use it in the air). */
    static class Spawner extends Item {
        final Model model;

        Spawner(Model model) {
            super(new Item.Properties().stacksTo(1));
            this.model = model;
        }

        private static Flyer make(ServerLevel sl, Model model, Player p) {
            Flyer d = new Flyer(TYPE.get(), sl);
            d.setModel(model);
            d.setBattery(Traits.droneBattery(p, model.battery));
            if (p != null) {
                d.owner = p.getUUID();
                d.setYRot(p.getYRot());
            }
            return d;
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
            Vec3 at = Vec3.atBottomCenterOf(ctx.getClickedPos().relative(ctx.getClickedFace()));
            Flyer d = make(sl, model, ctx.getPlayer());
            d.moveTo(at.x, at.y, at.z, d.getYRot(), 0);
            sl.addFreshEntity(d);
            ctx.getItemInHand().shrink(1);
            return InteractionResult.CONSUME;
        }

        /** Palm launch (Pocket Drone only): it lifts off your hand and follows you. */
        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (model != Model.POCKET) return InteractionResultHolder.pass(stack);
            if (!(level instanceof ServerLevel sl)) return InteractionResultHolder.success(stack);
            Flyer d = make(sl, model, player);
            Vec3 look = player.getLookAngle();
            d.moveTo(player.getX() + look.x, player.getEyeY(), player.getZ() + look.z, player.getYRot(), 0);
            d.follow = true;
            sl.addFreshEntity(d);
            player.displayClientMessage(Component.translatable("realism.drone.palm_launch").withStyle(ChatFormatting.AQUA), true);
            stack.shrink(1);
            return InteractionResultHolder.consume(stack);
        }
    }

    // ---- The drone itself ----

    public static class Flyer extends Entity {
        private static final EntityDataAccessor<Integer> MODEL = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Integer> BATTERY = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(Flyer.class, EntityDataSerializers.BOOLEAN);

        public UUID owner;
        /** Who's flying it right now (server side), and their latest stick input. */
        public UUID pilot;
        float forward, strafe, up;
        int pilotTicks, quietTicks;
        /** How far the pilot's controller reaches (set when they start flying). */
        float controllerRange = 1;
        /** Propellers: are the marked / plain ones on, were they put on the wrong motors, are they chipped. */
        public boolean markedOn = true, plainOn = true, wrongMotors, chipped;
        /** Flying home by itself (signal lost or low battery), and following its owner (Pocket Drone palm launch). */
        public boolean returning, follow;

        public Flyer(EntityType<? extends Flyer> type, Level level) {
            super(type, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {
            b.define(MODEL, 0).define(BATTERY, CAMERA_BATTERY).define(FLYING, false);
        }

        public Model model() {
            return Model.of(entityData.get(MODEL));
        }

        void setModel(Model m) {
            entityData.set(MODEL, m.ordinal());
        }

        public boolean fpv() {
            return model().fpv;
        }

        public int battery() {
            return entityData.get(BATTERY);
        }

        void setBattery(int v) {
            entityData.set(BATTERY, v);
        }

        public int maxBattery() {
            return model().battery;
        }

        /** Both propeller sets on (it can fly at all). */
        public boolean hasProps() {
            return markedOn && plainOn;
        }

        /** True while someone is flying it (synced: the rotors spin and it buzzes). */
        public boolean flying() {
            return entityData.get(FLYING);
        }

        /** How far this drone's signal reaches for this pilot (model, controller, trait). */
        public int range(Player p) {
            return Math.round(Traits.droneRange(p, model().range) * controllerRange);
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag t) {
            setModel(t.contains("model") ? Model.of(t.getInt("model")) : t.getBoolean("fpv") ? Model.FPV : Model.CAMERA);
            setBattery(t.getInt("battery"));
            if (t.hasUUID("owner")) owner = t.getUUID("owner");
            if (t.contains("markedOn")) {
                markedOn = t.getBoolean("markedOn");
                plainOn = t.getBoolean("plainOn");
                wrongMotors = t.getBoolean("wrongMotors");
                chipped = t.getBoolean("chipped");
            }
            follow = t.getBoolean("follow");
            returning = t.getBoolean("returning");
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag t) {
            t.putInt("model", model().ordinal());
            t.putBoolean("fpv", fpv());
            t.putInt("battery", battery());
            if (owner != null) t.putUUID("owner", owner);
            t.putBoolean("markedOn", markedOn);
            t.putBoolean("plainOn", plainOn);
            t.putBoolean("wrongMotors", wrongMotors);
            t.putBoolean("chipped", chipped);
            t.putBoolean("follow", follow);
            t.putBoolean("returning", returning);
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

        /** Right-click with propellers to put them on. Marked ones go on the marked motors first! */
        @Override
        public InteractionResult interact(Player player, InteractionHand hand) {
            ItemStack s = player.getItemInHand(hand);
            boolean marked = s.is(PROPS_MARKED.get()), plain = s.is(PROPS_PLAIN.get());
            if (!marked && !plain) return InteractionResult.PASS;
            if (level().isClientSide) return InteractionResult.SUCCESS;
            String msg = installProps(marked);
            if (msg == null) return InteractionResult.PASS;
            s.consume(1, player);
            player.displayClientMessage(Component.translatable("realism.drone.props." + msg), true);
            return InteractionResult.CONSUME;
        }

        /** Puts on one propeller set. Returns the message key, or null if there's nowhere to put it. */
        public String installProps(boolean marked) {
            if (hasProps() && !chipped && !wrongMotors) return null;
            if (hasProps()) {
                // Swapping a chipped or wrongly mounted set: off with the old ones, the new set goes on.
                markedOn = plainOn = false;
                wrongMotors = chipped = false;
            }
            if (!markedOn && !plainOn) {
                // The first set goes on the marked motors: right only if it's the marked set.
                if (marked) markedOn = true;
                else {
                    plainOn = true;
                    wrongMotors = true;
                }
                return marked ? "first_ok" : "first_wrong";
            }
            if (marked == markedOn) return null;
            if (marked) markedOn = true;
            else plainOn = true;
            chipped = false;
            return wrongMotors ? "done_wrong" : "done";
        }

        /** Drops back to its item (picked up), or breaks apart into a little scrap (a crash). */
        void crash(boolean pickedUp) {
            if (pickedUp) spawnAtLocation(new ItemStack(item(model())));
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

        /** The propellers snap: it can't fly until new ones are put on. */
        void breakProps(ServerPlayer p) {
            markedOn = plainOn = false;
            wrongMotors = chipped = false;
            level().playSound(null, blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.8f, 1.4f);
            if (p != null) p.displayClientMessage(Component.translatable("realism.drone.props.broken").withStyle(ChatFormatting.RED), true);
        }

        public ServerPlayer pilot() {
            return pilot == null || !(level() instanceof ServerLevel sl) ? null : (ServerPlayer) sl.getPlayerByUUID(pilot);
        }

        ServerPlayer ownerPlayer() {
            return owner == null || !(level() instanceof ServerLevel sl) ? null : (ServerPlayer) sl.getPlayerByUUID(owner);
        }

        @Override
        public void tick() {
            super.tick();
            if (level().isClientSide) return; // the server moves it; positions arrive every tick
            ServerPlayer p = pilot();
            if (p != null && (!p.isAlive() || p.distanceTo(this) > range(p) || p.level() != level())) {
                p.displayClientMessage(Component.translatable("realism.drone.signal_lost").withStyle(ChatFormatting.RED), true);
                stopFlying(p);
                returning = true;
                p = null;
            }
            Model m = model();
            boolean powered = battery() > 0;
            Vec3 v = getDeltaMovement();
            if (p != null && powered && hasProps()) {
                drain(m);
                float yaw = getYRot() * Mth.DEG_TO_RAD;
                double fx = -Mth.sin(yaw) * forward + Mth.cos(yaw) * strafe, fz = Mth.cos(yaw) * forward + Mth.sin(yaw) * strafe;
                if (m.fpv) {
                    // Racing drone: momentum, gravity, real thrust.
                    v = v.add(fx * 0.12, up > 0 ? 0.11 : -0.06, fz * 0.12).multiply(0.94, 0.94, 0.94);
                    if (up < 0) v = v.add(0, -0.06, 0);
                    if (v.length() > 1.4) v = v.normalize().scale(1.4);
                } else {
                    // Camera, pocket and enterprise drones: steady, stop when you let go. The big one is slower.
                    double speed = m == Model.ENTERPRISE ? 0.25 : 0.35;
                    v = new Vec3(fx * speed, up * 0.25, fz * speed).add(v.scale(0.5)).scale(0.6);
                }
                v = weather(m, v);
                if (chipped) v = v.add((random.nextFloat() - 0.5) * 0.08, (random.nextFloat() - 0.5) * 0.05, (random.nextFloat() - 0.5) * 0.08);
                pilotTicks++;
                if (m.fpv) motionSickness(p);
                if (m == Model.ENTERPRISE && tickCount % 20 == 0) thermal();
                if (battery() < maxBattery() / 10 && tickCount % 20 == 0) {
                    p.displayClientMessage(Component.translatable("realism.drone.low_battery_rth").withStyle(ChatFormatting.RED), false);
                    stopFlying(p);
                    returning = true;
                }
                boolean weak = p.distanceTo(this) > range(p) * 0.85f;
                if (tickCount % 20 == 0) p.displayClientMessage(Component.translatable(weak ? "realism.drone.hud_weak" : "realism.drone.hud",
                        battery() * 100 / maxBattery(), (int) p.distanceTo(this)).withStyle(battery() < maxBattery() / 5 || weak ? ChatFormatting.RED : ChatFormatting.AQUA), true);
                if (tickCount % 6 == 0) level().playSound(null, blockPosition(), SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, m.fpv ? 0.5f : 0.3f, m.fpv ? 2f : 1.6f);
            } else if (p == null && powered && hasProps() && (returning || follow) && ownerPlayer() != null) {
                v = autopilot(m, ownerPlayer(), v);
            } else {
                // Nobody flying (or a flat battery, or no propellers): a camera drone with power hovers; anything else falls.
                if (!m.fpv && powered && hasProps() && p == null && !onGround() && pilotTicks > 0) v = v.scale(0.8);
                else v = v.add(0, -0.08, 0).multiply(0.9, 0.98, 0.9);
                if (p != null && !powered && tickCount % 40 == 0)
                    p.displayClientMessage(Component.translatable("realism.drone.battery_flat").withStyle(ChatFormatting.RED), true);
                if (p != null && !hasProps() && tickCount % 40 == 0)
                    p.displayClientMessage(Component.translatable("realism.drone.props.missing").withStyle(ChatFormatting.RED), true);
                if (p == null) pilotTicks = 0;
                // Charging while it sits still.
                if (onGround() && p == null && battery() < maxBattery()) setBattery(Math.min(maxBattery(), battery() + 2));
            }
            double speed = v.horizontalDistance();
            setDeltaMovement(v);
            move(MoverType.SELF, v);
            if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.6, 0, 0.6));
            if (horizontalCollision) collide(m, p, speed);
        }

        /** Hitting a wall: guarded drones bounce, the racer breaks, others lose (or chip) their propellers. */
        private void collide(Model m, ServerPlayer p, double speed) {
            if (m.guarded) {
                if (speed > 0.3) {
                    setDeltaMovement(getDeltaMovement().scale(-0.5));
                    level().playSound(null, blockPosition(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.6f, 1.6f);
                }
                return;
            }
            if (m == Model.FPV && speed > 0.55) crash(false);
            else if (speed > 0.55 && hasProps()) breakProps(p);
            else if (speed > 0.3 && hasProps() && !chipped) {
                chipped = true;
                if (p != null) p.displayClientMessage(Component.translatable("realism.drone.props.chipped").withStyle(ChatFormatting.YELLOW), true);
            }
        }

        /** Battery use: double with chipped propellers, more in the cold. */
        private void drain(Model m) {
            int use = chipped ? 2 : 1;
            if (level().getBiome(blockPosition()).value().coldEnoughToSnow(blockPosition()) && tickCount % 2 == 0) use++;
            setBattery(Math.max(0, battery() - use));
        }

        /** Rain and storms push small drones around (the Enterprise Drone doesn't care). */
        private Vec3 weather(Model m, Vec3 v) {
            if (m == Model.ENTERPRISE || !level().isRainingAt(blockPosition())) return v;
            double gust = level().isThundering() ? 0.06 : 0.025;
            return v.add((random.nextFloat() - 0.5) * gust * 2, -0.01, (random.nextFloat() - 0.4) * gust * 2);
        }

        /** Return to home (back to its owner, then lands) or follow-me (Pocket Drone: stays near its owner). */
        private Vec3 autopilot(Model m, ServerPlayer home, Vec3 v) {
            setBattery(Math.max(0, battery() - 1));
            Vec3 target = follow && !returning ? home.position().add(home.getLookAngle().scale(-2.5)).add(0, 2.2, 0) : home.position().add(0, 1.2, 0);
            Vec3 to = target.subtract(position());
            if (returning && to.horizontalDistance() < 2.5) {
                returning = false;
                follow = false;
                home.displayClientMessage(Component.translatable("realism.drone.home").withStyle(ChatFormatting.GREEN), true);
                return v.add(0, -0.08, 0);
            }
            double speed = returning ? 0.4 : Math.min(0.5, to.length() * 0.15);
            Vec3 step = to.length() > 0.2 ? to.normalize().scale(speed) : Vec3.ZERO;
            if (horizontalCollision) step = step.add(0, 0.3, 0); // climb over things in the way
            setYRot((float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90);
            return v.scale(0.5).add(step.scale(0.5));
        }

        /** Thermal camera: living things near the drone glow (you see them through walls). */
        private void thermal() {
            for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(24), e -> e.isAlive() && !(e instanceof Player pl && pl.getUUID().equals(pilot))))
                e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, true, false));
        }

        private void motionSickness(ServerPlayer p) {
            if (pilotTicks > 20 * 60 && pilotTicks % 200 == 0) {
                p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false, true));
                p.displayClientMessage(Component.translatable("realism.drone.dizzy").withStyle(ChatFormatting.GREEN), false);
            }
            if (pilotTicks > 20 * 150 && pilotTicks % 600 == 0) Food.vomit(p);
        }

        void startFlying(ServerPlayer p, float controllerRange) {
            pilot = p.getUUID();
            this.controllerRange = controllerRange;
            returning = false;
            follow = false;
            entityData.set(FLYING, true);
            forward = strafe = up = 0;
            if (wrongMotors && hasProps()) {
                // Props on the wrong motors: it flips the moment it lifts off.
                setDeltaMovement(0, 0.25, 0);
                setXRot(180);
                breakProps(p);
                p.displayClientMessage(Component.translatable("realism.drone.props.flipped").withStyle(ChatFormatting.RED), false);
            }
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

    /**
     * Server: someone asks to fly (or stop flying) a drone. It must be theirs, in range, with a controller that pairs
     * with it, and FPV drones need goggles.
     */
    public static void handlePilot(ServerPlayer p, Pilot msg) {
        if (!(p.level().getEntity(msg.drone()) instanceof Flyer d)) return;
        if (!msg.on()) {
            d.stopFlying(p);
            return;
        }
        String problem = canFly(p, d);
        if (problem != null) {
            p.displayClientMessage(Component.translatable("realism.drone." + problem).withStyle(ChatFormatting.RED), true);
            Pilot.tell(p, -1);
            return;
        }
        d.startFlying(p, controllerRange(heldController(p)));
        p.displayClientMessage(Component.translatable("realism.drone.start_" + d.model().id).withStyle(ChatFormatting.AQUA), false);
    }

    /** Why this player can't fly this drone right now (a message key), or null if they can. */
    public static String canFly(ServerPlayer p, Flyer d) {
        ItemStack controller = heldController(p);
        if (controller.isEmpty()) return "need_controller";
        if (!pairs(controller, d.model())) return d.model() == Model.ENTERPRISE ? "need_enterprise_controller" : "enterprise_controller_only";
        if (d.model().fpv && !p.getInventory().contains(new ItemStack(FPV_GOGGLES.get()))) return "need_goggles";
        if (d.owner != null && !d.owner.equals(p.getUUID())) return "not_yours";
        if (d.pilot != null && !d.pilot.equals(p.getUUID())) return "not_yours";
        if (p.distanceTo(d) > Math.round(Traits.droneRange(p, d.model().range) * controllerRange(controller))) return "too_far";
        return null;
    }

    public static void handleSticks(ServerPlayer p, Sticks s) {
        if (p.level().getEntity(s.drone()) instanceof Flyer d && p.getUUID().equals(d.pilot)) d.steer(s.forward(), s.strafe(), s.up(), s.yaw(), s.pitch());
    }
}
