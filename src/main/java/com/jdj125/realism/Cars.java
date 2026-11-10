package com.jdj125.realism;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Realism 2.0.2, cars and roads.
 * <ul>
 * <li><b>Car</b> (red, blue, black or white): W/S drive, A/D steer while moving, 2 seats. Faster on asphalt.</li>
 * <li><b>Fuel</b>: a full tank lasts about 10 minutes of driving. Fill a Gas Can at a Gas Pump (costs money), then
 * right-click the car with it. Empty tank = it won't move.</li>
 * <li><b>Crashes</b>: driving into a wall fast hurts the driver and dents the car; hitting people or mobs hurts them
 * (and it's on the driver: hit and run counts as assault or murder).</li>
 * <li><b>Roads</b>: asphalt, asphalt with a white line, and traffic lights that cycle green, yellow, red.</li>
 * </ul>
 */
public final class Cars {
    private Cars() {}

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final Supplier<EntityType<Car>> CAR = ENTITIES.register("car",
            () -> EntityType.Builder.of(Car::new, MobCategory.MISC).sized(1.8f, 1.5f).clientTrackingRange(10).build("car"));
    public static final String[] COLORS = {"red", "blue", "black", "white"};
    public static final DeferredItem<Item> CAR_RED = carItem(0), CAR_BLUE = carItem(1), CAR_BLACK = carItem(2), CAR_WHITE = carItem(3);
    public static final DeferredItem<Item> GAS_CAN = ITEMS.register("gas_can", () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> GAS_CAN_FULL = ITEMS.register("gas_can_full", () -> new Item(new Item.Properties().stacksTo(1)));

    public static final DeferredBlock<Block> ASPHALT = BLOCKS.registerSimpleBlock("asphalt",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5f, 6).sound(SoundType.STONE).requiresCorrectToolForDrops());
    public static final DeferredBlock<Block> ROAD_LINE = BLOCKS.register("road_line",
            () -> new RoadLine(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5f, 6).sound(SoundType.STONE).requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> TRAFFIC_LIGHT = BLOCKS.register("traffic_light",
            () -> new TrafficLight(BlockBehaviour.Properties.of().strength(2).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7)));
    public static final DeferredBlock<Block> GAS_PUMP = BLOCKS.register("gas_pump",
            () -> new GasPump(BlockBehaviour.Properties.of().strength(2).sound(SoundType.METAL).noOcclusion()));

    static {
        ITEMS.registerSimpleBlockItem(ASPHALT);
        ITEMS.registerSimpleBlockItem(ROAD_LINE);
        ITEMS.registerSimpleBlockItem(TRAFFIC_LIGHT);
        ITEMS.registerSimpleBlockItem(GAS_PUMP);
    }

    public static final int TANK = 12000, FUEL_PRICE = 20;
    public static final float CRASH_SPEED = 0.35f;

    private static DeferredItem<Item> carItem(int color) {
        return ITEMS.register("car_" + COLORS[color], () -> new CarItem(new Item.Properties().stacksTo(1), color));
    }

    static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Cars::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(CAR.get(), Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 60).add(Attributes.MOVEMENT_SPEED, 0.36)
                .add(Attributes.STEP_HEIGHT, 1.1).add(Attributes.ARMOR, 6).add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.FALL_DAMAGE_MULTIPLIER, 0).build());
    }

    static class CarItem extends Item {
        final int color;

        CarItem(Properties props, int color) {
            super(props);
            this.color = color;
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (level.isClientSide) return InteractionResult.SUCCESS;
            Car car = CAR.get().create(level);
            if (car == null) return InteractionResult.FAIL;
            BlockPos at = ctx.getClickedPos().relative(ctx.getClickedFace());
            float yaw = ctx.getPlayer() == null ? 0 : ctx.getPlayer().getYRot();
            car.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
            car.setYBodyRot(yaw);
            car.setYHeadRot(yaw);
            car.setColor(color);
            car.setFuel(TANK / 2);
            level.addFreshEntity(car);
            ctx.getItemInHand().consume(1, ctx.getPlayer());
            return InteractionResult.CONSUME;
        }
    }

    public static class Car extends Mob {
        private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(Car.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(Car.class, EntityDataSerializers.INT);
        private Vec3 lastMove = Vec3.ZERO;
        private int crashCooldown;

        public Car(EntityType<? extends Mob> type, Level level) {
            super(type, level);
            setPersistenceRequired();
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder b) {
            super.defineSynchedData(b);
            b.define(COLOR, 0);
            b.define(FUEL, TANK / 2);
        }

        public int color() {
            return entityData.get(COLOR);
        }

        public void setColor(int c) {
            entityData.set(COLOR, Math.floorMod(c, COLORS.length));
        }

        public int fuel() {
            return entityData.get(FUEL);
        }

        public void setFuel(int f) {
            entityData.set(FUEL, Math.max(0, Math.min(TANK, f)));
        }

        @Override
        public void addAdditionalSaveData(CompoundTag t) {
            super.addAdditionalSaveData(t);
            t.putInt("Color", color());
            t.putInt("Fuel", fuel());
        }

        @Override
        public void readAdditionalSaveData(CompoundTag t) {
            super.readAdditionalSaveData(t);
            setColor(t.getInt("Color"));
            setFuel(t.contains("Fuel") ? t.getInt("Fuel") : TANK / 2);
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
            return getPassengers().size() < 2;
        }

        @Override
        public LivingEntity getControllingPassenger() {
            return getFirstPassenger() instanceof Player p ? p : null;
        }

        @Override
        protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dims, float partialTick) {
            boolean driver = getPassengers().indexOf(passenger) == 0;
            return new Vec3(driver ? 0.35 : -0.35, 0.45, 0.1).yRot(-getYRot() * ((float) Math.PI / 180));
        }

        @Override
        public InteractionResult mobInteract(Player player, InteractionHand hand) {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(GAS_CAN_FULL.get())) {
                if (!level().isClientSide) {
                    setFuel(fuel() + TANK / 2);
                    if (!player.isCreative()) player.setItemInHand(hand, new ItemStack(GAS_CAN.get()));
                    level().playSound(null, blockPosition(), SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.NEUTRAL, 0.8f, 1.2f);
                    player.displayClientMessage(Component.translatable("realism.car.refueled", fuel() * 100 / TANK), true);
                }
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
            if (player.isSecondaryUseActive()) return InteractionResult.PASS;
            if (!level().isClientSide) player.startRiding(this);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        @Override
        protected void tickRidden(Player driver, Vec3 input) {
            super.tickRidden(driver, input);
            float turn = driver.xxa * (Math.abs(driver.zza) > 0 ? 4f : 0) * Math.signum(driver.zza == 0 ? 1 : driver.zza);
            setYRot(getYRot() - turn);
            yRotO = getYRot();
            setYBodyRot(getYRot());
            setYHeadRot(getYRot());
        }

        @Override
        protected Vec3 getRiddenInput(Player driver, Vec3 input) {
            if (RealismConfig.CARS.get() && fuel() <= 0) return Vec3.ZERO;
            return new Vec3(0, 0, driver.zza > 0 ? 1 : driver.zza < 0 ? -0.35 : 0);
        }

        @Override
        protected float getRiddenSpeed(Player driver) {
            float speed = (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
            return onAsphalt() ? speed * 1.3f : speed;
        }

        boolean onAsphalt() {
            BlockState below = level().getBlockState(blockPosition().below());
            return below.is(ASPHALT.get()) || below.is(ROAD_LINE.get());
        }

        /** Sneak-punch an empty car to pick it up. Otherwise only heavy stuff hurts it. */
        @Override
        public boolean hurt(DamageSource src, float amount) {
            if (level().isClientSide) return false;
            if (src.getEntity() instanceof Player p && p.isShiftKeyDown() && getPassengers().isEmpty()) {
                if (!p.isCreative()) spawnAtLocation(new ItemStack(switch (color()) {
                    case 1 -> CAR_BLUE.get();
                    case 2 -> CAR_BLACK.get();
                    case 3 -> CAR_WHITE.get();
                    default -> CAR_RED.get();
                }));
                discard();
                return true;
            }
            if (src.getEntity() instanceof Player) return false;
            return super.hurt(src, amount);
        }

        @Override
        public void tick() {
            super.tick();
            if (!(level() instanceof ServerLevel sl)) return;
            Vec3 move = getDeltaMovement();
            double speed = move.horizontalDistance();
            boolean driving = getControllingPassenger() != null;
            if (driving && speed > 0.01 && RealismConfig.CARS.get()) setFuel(fuel() - 1);
            if (crashCooldown > 0) crashCooldown--;
            // Crash: we were going fast and suddenly stopped against something solid.
            double before = lastMove.horizontalDistance();
            if (RealismConfig.CARS.get() && crashCooldown == 0 && before > CRASH_SPEED && horizontalCollision && speed < before * 0.4) crash(sl, (float) before);
            // Running people and mobs over.
            if (RealismConfig.CARS.get() && speed > CRASH_SPEED * 0.8 && getControllingPassenger() instanceof ServerPlayer driver)
                for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.3).move(move.scale(2)),
                        e -> e != this && !hasPassenger(e) && !(e instanceof Car) && e.invulnerableTime == 0)) {
                    e.hurt(damageSources().playerAttack(driver), (float) (speed * 25));
                    e.push(move.x * 2, 0.4, move.z * 2);
                }
            if (driving && tickCount % 20 == 0 && getControllingPassenger() instanceof ServerPlayer d)
                d.displayClientMessage(Component.translatable("realism.car.dash", fuel() * 100 / TANK, Math.round(speed * 20 * 3.6)), true);
            if (driving && fuel() == 0 && tickCount % 100 == 0 && getControllingPassenger() instanceof ServerPlayer d)
                d.displayClientMessage(Component.translatable("realism.car.empty"), false);
            lastMove = move;
        }

        void crash(ServerLevel sl, float speed) {
            crashCooldown = 20;
            float hurt = speed * 30;
            sl.playSound(null, blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 1f, 0.6f);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, getX(), getY() + 1, getZ(), 15, 0.6, 0.3, 0.6, 0.02);
            super.hurt(damageSources().generic(), hurt / 2);
            for (Entity e : getPassengers())
                if (e instanceof LivingEntity l) {
                    l.hurt(damageSources().flyIntoWall(), hurt);
                    if (l instanceof Player p) p.displayClientMessage(Component.translatable("realism.car.crash"), true);
                }
        }

        @Override
        protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        }

        @Override
        public boolean causeFallDamage(float distance, float mult, DamageSource src) {
            return false;
        }
    }

    // ---- Roads ----

    static class RoadLine extends HorizontalDirectionalBlock {
        static final MapCodec<RoadLine> CODEC = simpleCodec(RoadLine::new);

        RoadLine(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
        }
    }

    /** Green (0), yellow (1), red (2): green 8 s, yellow 2 s, red 8 s. */
    public static class TrafficLight extends HorizontalDirectionalBlock {
        static final MapCodec<TrafficLight> CODEC = simpleCodec(TrafficLight::new);
        public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 2);
        private static final VoxelShape SHAPE = Shapes.or(Block.box(7, 0, 7, 9, 10, 9), Block.box(5, 10, 5, 11, 16, 11));

        TrafficLight(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIGHT, 0));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, LIGHT);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            if (!old.is(this)) level.scheduleTick(pos, this, 160);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            int next = (state.getValue(LIGHT) + 1) % 3;
            level.setBlockAndUpdate(pos, state.setValue(LIGHT, next));
            level.scheduleTick(pos, this, next == 1 ? 40 : 160);
        }
    }

    // ---- Fuel ----

    static class GasPump extends HorizontalDirectionalBlock {
        static final MapCodec<GasPump> CODEC = simpleCodec(GasPump::new);
        private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 16, 13);

        GasPump(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        // An empty gas can in hand: fill it up (and pay for the fuel).
        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player p, InteractionHand hand, BlockHitResult hit) {
            if (!stack.is(GAS_CAN.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            if (!p.isCreative()) {
                if (World.balance(p) < FUEL_PRICE) {
                    p.displayClientMessage(Component.translatable("realism.car.no_money", FUEL_PRICE), true);
                    return ItemInteractionResult.CONSUME;
                }
                World.addMoney(p, -FUEL_PRICE);
                stack.shrink(1);
            }
            if (!p.getInventory().add(new ItemStack(GAS_CAN_FULL.get()))) p.drop(new ItemStack(GAS_CAN_FULL.get()), false);
            level.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 0.8f, 1.2f);
            p.displayClientMessage(Component.translatable("realism.car.filled", FUEL_PRICE), true);
            return ItemInteractionResult.CONSUME;
        }
    }
}
