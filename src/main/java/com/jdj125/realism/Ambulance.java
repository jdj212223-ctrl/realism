package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 1.0.0: a real, drivable ambulance. Place it, right-click to get in the driver's seat, drive with W/S and steer with
 * A/D (sneak to get out). Drive up to a collapsed player and they're loaded in the back by themselves: the siren goes
 * on, and the paramedics keep them alive (their collapse clock runs 4 times slower). Reach an Operating Table with a
 * Doctor nearby and they get emergency surgery. Sneak-punch an empty ambulance to pick it back up.
 */
public final class Ambulance {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final Supplier<EntityType<Van>> TYPE = ENTITIES.register("ambulance",
            () -> EntityType.Builder.of(Van::new, MobCategory.MISC).sized(1.8f, 2.0f).clientTrackingRange(10).build("ambulance"));
    public static final DeferredItem<Item> ITEM = ITEMS.register("ambulance", () -> new Spawner(new Item.Properties().stacksTo(1)));

    /** How close the ambulance must get to a collapsed player, and to the hospital's Operating Table. */
    public static final double PICKUP = 3.5, HOSPITAL = 12;

    private Ambulance() {
    }

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Ambulance::attributes);
        modBus.addListener(Ambulance::tabs);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(TYPE.get(), Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 200).add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.STEP_HEIGHT, 1.1).add(Attributes.ARMOR, 10).add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.FALL_DAMAGE_MULTIPLIER, 0).build());
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ITEM.get());
    }

    static class Spawner extends Item {
        Spawner(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (level.isClientSide) return InteractionResult.SUCCESS;
            Van van = TYPE.get().create(level);
            if (van == null) return InteractionResult.FAIL;
            BlockPos at = ctx.getClickedPos().relative(ctx.getClickedFace());
            float yaw = ctx.getPlayer() == null ? 0 : ctx.getPlayer().getYRot();
            van.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
            van.setYHeadRot(yaw);
            van.setYBodyRot(yaw);
            van.setPersistenceRequired();
            level.addFreshEntity(van);
            ctx.getItemInHand().consume(1, ctx.getPlayer());
            return InteractionResult.CONSUME;
        }
    }

    public static class Van extends Mob {
        private static final EntityDataAccessor<Boolean> SIREN = SynchedEntityData.defineId(Van.class, EntityDataSerializers.BOOLEAN);

        public Van(EntityType<? extends Mob> type, Level level) {
            super(type, level);
            setPersistenceRequired();
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {
            super.defineSynchedData(b);
            b.define(SIREN, false);
        }

        public boolean siren() {
            return entityData.get(SIREN);
        }

        @Override
        public boolean removeWhenFarAway(double distance) {
            return false;
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        @Override
        protected boolean canAddPassenger(Entity passenger) {
            return getPassengers().size() < 3;
        }

        /** The driver is the first passenger who isn't a patient. */
        @Override
        public LivingEntity getControllingPassenger() {
            for (Entity e : getPassengers()) if (e instanceof Player p && !Rescue.collapsed(p)) return p;
            return null;
        }

        @Override
        protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dims, float partialTick) {
            // Driver up front on the left, patients lying in the back.
            boolean patient = passenger instanceof Player p && Rescue.collapsed(p);
            return new Vec3(patient ? 0 : 0.35, patient ? 0.55 : 0.75, patient ? -0.7 : 0.55).yRot(-getYRot() * ((float) Math.PI / 180));
        }

        @Override
        public InteractionResult mobInteract(Player player, InteractionHand hand) {
            if (player.isSecondaryUseActive()) return InteractionResult.PASS;
            if (!level().isClientSide && getControllingPassenger() == null) player.startRiding(this);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        // Driving: W/S go, A/D steer (a car can only turn while it's moving).
        @Override
        protected void tickRidden(Player driver, Vec3 input) {
            super.tickRidden(driver, input);
            float turn = driver.xxa * (Math.abs(driver.zza) > 0 ? 4.5f : 0) * Math.signum(driver.zza == 0 ? 1 : driver.zza);
            setYRot(getYRot() - turn);
            yRotO = getYRot();
            setYBodyRot(getYRot());
            setYHeadRot(getYRot());
        }

        @Override
        protected Vec3 getRiddenInput(Player driver, Vec3 input) {
            return new Vec3(0, 0, driver.zza > 0 ? 1 : driver.zza < 0 ? -0.35 : 0);
        }

        @Override
        protected float getRiddenSpeed(Player driver) {
            return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
        }

        // It's a vehicle: only explosions, lava and the void can wreck it. Sneak-punch an empty one to pick it up.
        @Override
        public boolean hurt(DamageSource src, float amount) {
            if (level().isClientSide) return false;
            if (src.getEntity() instanceof Player p && p.isShiftKeyDown() && getPassengers().isEmpty()) {
                if (!p.isCreative()) spawnAtLocation(new ItemStack(ITEM.get()));
                discard();
                return true;
            }
            if (src.is(DamageTypes.EXPLOSION) || src.is(DamageTypes.PLAYER_EXPLOSION) || src.is(DamageTypes.LAVA)
                    || src.is(DamageTypes.FELL_OUT_OF_WORLD) || src.is(DamageTypes.GENERIC_KILL)) return super.hurt(src, amount);
            return false;
        }

        @Override
        public void tick() {
            super.tick();
            if (!(level() instanceof ServerLevel sl)) return;
            boolean carrying = getPassengers().stream().anyMatch(e -> e instanceof Player p && Rescue.collapsed(p));
            if (siren() != carrying) entityData.set(SIREN, carrying);
            if (carrying && tickCount % 20 == 0)
                sl.playSound(null, blockPosition(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.NEUTRAL, 1.5f, tickCount % 40 == 0 ? 1.2f : 0.9f);
            if (tickCount % 10 != 0) return;
            // Load collapsed players nearby.
            for (ServerPlayer p : sl.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(PICKUP), Rescue::collapsed))
                if (p.getVehicle() != this && getPassengers().size() < 3 && p.startRiding(this, true)) {
                    p.displayClientMessage(Component.translatable("realism.van.loaded"), false);
                    if (getControllingPassenger() instanceof Player d) d.displayClientMessage(Component.translatable("realism.van.patient", p.getName()), true);
                }
            // At the hospital: hand the patients over for surgery.
            if (!carrying) return;
            BlockPos table = Rescue.Tables.get(sl).nearest(blockPosition(), (int) HOSPITAL);
            if (table == null || !sl.getBlockState(table).is(Hospital.OPERATING_TABLE.get())) return;
            boolean doctor = !sl.getEntitiesOfClass(Villager.class, new AABB(table).inflate(8),
                    v -> v.getVillagerData().getProfession() == Hospital.DOCTOR.get()).isEmpty();
            if (!doctor) return;
            for (Entity e : getPassengers().toArray(new Entity[0]))
                if (e instanceof ServerPlayer p && Rescue.collapsed(p)) {
                    p.stopRiding();
                    p.teleportTo(table.getX() + 0.5, table.getY() + 1, table.getZ() + 0.5);
                    Rescue.revive(p);
                    Hospital.operate(p);
                    p.displayClientMessage(Component.translatable("realism.ambulance.surgery"), false);
                }
        }
    }

    /** Patients riding in an ambulance: the paramedics keep them going (collapse clock 4x slower). */
    static boolean inAmbulance(Player p) {
        return p.getVehicle() instanceof Van;
    }
}
