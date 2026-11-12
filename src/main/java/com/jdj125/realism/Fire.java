package com.jdj125.realism;

import com.mojang.brigadier.Command;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Function;

/**
 * Realism 3.2: fire and heating.
 * <ul>
 * <li>Heating: a Coal Heater (burns coal), a flat electric Panel Radiator (runs on the power grid) and a Fireplace that
 * needs a Chimney above it. They warm the room with Cold Sweat.</li>
 * <li>Smoke: fire indoors (or a fireplace with no chimney) fills the room with smoke. Standing in it makes you cough
 * and hurts you; crouching keeps you below the smoke, like in real life.</li>
 * <li>A Smoke Detector beeps when there's smoke or fire nearby. A Fire Extinguisher puts fires out.</li>
 * <li>Kitchen fires: a burning Grandma's Stove left alone can set things on fire. A fireplace throws sparks onto
 * whatever burns right in front of it.</li>
 * <li>The firefighter job, and calling the fire department (phone button or /fire).</li>
 * </ul>
 */
public final class Fire {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    /** Fuel left (each step burns 30 seconds). */
    public static final IntegerProperty FUEL = IntegerProperty.create("fuel", 0, 15);
    public static final BooleanProperty ALARM = BooleanProperty.create("alarm");
    static final int FUEL_TICKS = 600, RADIATOR_W = 80, SMOKE_RANGE = 6, DETECTOR_RANGE = 8;

    public static final DeferredBlock<Block> COAL_HEATER = block("coal_heater",
            p -> new Burner(p, false), BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops()
                    .lightLevel(s -> s.getValue(BlockStateProperties.LIT) ? 9 : 0));
    public static final DeferredBlock<Block> FIREPLACE = block("fireplace",
            p -> new Burner(p, true), BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.STONE).requiresCorrectToolForDrops()
                    .lightLevel(s -> s.getValue(BlockStateProperties.LIT) ? 13 : 0));
    public static final DeferredBlock<Block> CHIMNEY = block("chimney", Chimney::new,
            BlockBehaviour.Properties.of().strength(2f).sound(SoundType.STONE).requiresCorrectToolForDrops());
    public static final DeferredBlock<Block> RADIATOR = block("panel_radiator", Radiator::new,
            BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion());
    public static final DeferredBlock<Block> SMOKE_DETECTOR = block("smoke_detector", Detector::new,
            BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.STONE).noOcclusion().noCollission());
    public static final DeferredItem<Item> EXTINGUISHER = ITEMS.register("fire_extinguisher", () -> new Extinguisher(new Item.Properties().durability(32)));

    private static DeferredBlock<Block> block(String name, Function<BlockBehaviour.Properties, Block> make, BlockBehaviour.Properties props) {
        DeferredBlock<Block> b = BLOCKS.register(name, () -> make.apply(props));
        ITEMS.register(name, () -> new BlockItem(b.get(), new Item.Properties()));
        return b;
    }

    private Fire() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    // ---- Smoke ----

    static boolean isFire(BlockState s) {
        return s.getBlock() instanceof BaseFireBlock;
    }

    /** A fireplace burning with no chimney on top fills the room with smoke. */
    public static boolean smokyFireplace(BlockGetter level, BlockPos pos, BlockState s) {
        return s.is(FIREPLACE.get()) && s.getValue(BlockStateProperties.LIT) && !level.getBlockState(pos.above()).is(CHIMNEY.get());
    }

    /** How much smoke is around: fire blocks and smoky fireplaces within the range. */
    public static int smokeAt(Level level, BlockPos center, int r) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, r, r))) {
            BlockState s = level.getBlockState(p);
            if (isFire(s)) n++;
            else if (smokyFireplace(level, p, s)) n += 3;
        }
        return n;
    }

    /** Indoors = a roof over your head (smoke has nowhere to go). */
    public static boolean indoors(Level level, BlockPos pos) {
        // A solid block somewhere above your head (the heightmap updates right away, unlike sky light).
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY() + 1;
    }

    /** One second in the smoke. Crouching keeps you under it: only a cough. Standing: it hurts and stings your eyes. */
    public static boolean breathe(ServerPlayer p) {
        if (!RealismConfig.SMOKE.get() || p.gameMode.getGameModeForPlayer().isCreative() || p.isSpectator()) return false;
        if (!indoors(p.level(), p.blockPosition()) || smokeAt(p.level(), p.blockPosition(), SMOKE_RANGE) == 0) return false;
        if (p.isCrouching()) {
            if (p.tickCount % 100 == 0) p.displayClientMessage(Component.translatable("realism.fire.smoke_low"), true);
            return true;
        }
        p.displayClientMessage(Component.translatable("realism.fire.smoke").withStyle(ChatFormatting.GRAY), true);
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, true, false, false));
        if (p.tickCount % 60 == 0) {
            p.hurt(Realism.source(p, Realism.SMOKE), 1);
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
        return true;
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 20 == 0) breathe(p);
    }

    // ---- Putting fires out ----

    /** Puts out the fire around a spot (3x3x3), and anything burning there. Returns how many fire blocks went out. */
    public static int extinguish(Level level, BlockPos at, Player by) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            BlockState s = level.getBlockState(p);
            if (isFire(s)) {
                level.removeBlock(p, false);
                n++;
            } else if ((s.is(FIREPLACE.get()) || s.is(COAL_HEATER.get()) || s.is(Blocks.CAMPFIRE)) && s.getValue(BlockStateProperties.LIT)) {
                level.setBlockAndUpdate(p, s.setValue(BlockStateProperties.LIT, false));
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(at).inflate(1.5), Entity::isOnFire)) e.clearFire();
        if (n > 0 && by instanceof ServerPlayer sp) Jobs.work(sp, "firefighter", n);
        return n;
    }

    static class Extinguisher extends Item {
        Extinguisher(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            ItemStack stack = p.getItemInHand(hand);
            Vec3 eye = p.getEyePosition(), to = eye.add(p.getLookAngle().scale(6));
            BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            BlockPos at = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection()) : BlockPos.containing(to);
            if (!level.isClientSide) {
                // Fire is often "in" the block you aim at, so cover the block itself too.
                int n = extinguish(level, at, p) + (hit.getType() == HitResult.Type.BLOCK ? extinguish(level, hit.getBlockPos(), p) : 0);
                if (p.isOnFire()) p.clearFire();
                level.playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, n > 0 ? 1f : 1.6f);
                stack.hurtAndBreak(1, p, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            } else {
                Vec3 look = p.getLookAngle();
                for (int i = 0; i < 20; i++)
                    level.addParticle(ParticleTypes.CLOUD, eye.x + look.x, eye.y - 0.2 + look.y, eye.z + look.z,
                            look.x * 0.6 + (level.random.nextDouble() - 0.5) * 0.15, look.y * 0.6 + (level.random.nextDouble() - 0.5) * 0.15,
                            look.z * 0.6 + (level.random.nextDouble() - 0.5) * 0.15);
            }
            p.getCooldowns().addCooldown(this, 8);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
    }

    // ---- Kitchen fires and sparks ----

    /** Sets fire to one air spot next to something that burns, around a spot. Returns whether it did. */
    public static boolean ignite(Level level, BlockPos near, RandomSource random) {
        for (int tries = 0; tries < 12; tries++) {
            BlockPos p = near.offset(random.nextInt(5) - 2, random.nextInt(3) - 1, random.nextInt(5) - 2);
            if (!level.getBlockState(p).isAir()) continue;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) {
                    level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
                    return true;
                }
            }
        }
        return false;
    }

    /** Called every 30 seconds by a burning Grandma's Stove: left alone (nobody within 8 blocks), it may start a fire. */
    public static void kitchenFire(ServerLevel level, BlockPos stove) {
        if (!RealismConfig.KITCHEN_FIRES.get() || level.random.nextInt(30) != 0) return;
        if (level.getNearestPlayer(stove.getX() + 0.5, stove.getY() + 0.5, stove.getZ() + 0.5, 8, false) != null) return;
        if (ignite(level, stove, level.random))
            com.mojang.logging.LogUtils.getLogger().info("Realism: a stove left alone started a fire at {}", stove);
    }

    // ---- The fire department ----

    /** Calling the fire department: every firefighter online hears where. You need a phone and signal. */
    public static String callFireDepartment(ServerPlayer caller) {
        if (!caller.getInventory().contains(new ItemStack(World.PHONE.get()))) return "phone.none";
        if (!World.Data.get(caller.serverLevel()).signal(caller.blockPosition())) return "phone.no_signal";
        BlockPos at = caller.blockPosition();
        int told = 0;
        for (ServerPlayer p : caller.server.getPlayerList().getPlayers()) {
            if (!"firefighter".equals(p.getData(Jobs.JOB))) continue;
            p.displayClientMessage(Component.translatable("realism.fire.call", caller.getName(), at.getX(), at.getY(), at.getZ())
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
            p.level().playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1f, 1.2f);
            told++;
        }
        caller.displayClientMessage(Component.translatable(told > 0 ? "realism.fire.called" : "realism.fire.nobody", told), false);
        return "phone.ok";
    }

    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fire").executes(ctx -> {
            String r = callFireDepartment(ctx.getSource().getPlayerOrException());
            if (!r.equals("phone.ok")) ctx.getSource().sendFailure(Component.translatable("realism." + r));
            return r.equals("phone.ok") ? Command.SINGLE_SUCCESS : 0;
        }));
    }

    // ---- Blocks ----

    /** Coal Heater and Fireplace: put fuel in (right-click) and they burn, 30 seconds per step of fuel. */
    public static class Burner extends HorizontalDirectionalBlock {
        final boolean fireplace;

        Burner(Properties props, boolean fireplace) {
            super(props);
            this.fireplace = fireplace;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BlockStateProperties.LIT, false).setValue(FUEL, 0));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return simpleCodec(p -> new Burner(p, fireplace));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, BlockStateProperties.LIT, FUEL);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        /** How many 30-second steps an item burns for here, or 0. */
        public int fuelValue(ItemStack stack) {
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) return 4;
            if (stack.is(Items.COAL_BLOCK)) return 15;
            if (!fireplace) return 0;
            if (stack.is(ItemTags.LOGS_THAT_BURN)) return 3;
            if (stack.is(ItemTags.PLANKS)) return 1;
            return 0;
        }

        public static void addFuel(Level level, BlockPos pos, BlockState state, int steps) {
            int fuel = Math.min(15, state.getValue(FUEL) + steps);
            level.setBlockAndUpdate(pos, state.setValue(FUEL, fuel).setValue(BlockStateProperties.LIT, fuel > 0));
            level.scheduleTick(pos, state.getBlock(), FUEL_TICKS);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player p, InteractionHand hand, BlockHitResult hit) {
            int value = fuelValue(stack);
            if (value == 0) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (state.getValue(FUEL) >= 15) return ItemInteractionResult.CONSUME;
            if (!level.isClientSide) {
                addFuel(level, pos, state, value);
                stack.consume(1, p);
                level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.5f, 1.2f);
                if (fireplace && !level.getBlockState(pos.above()).is(CHIMNEY.get()))
                    p.displayClientMessage(Component.translatable("realism.fire.no_chimney").withStyle(ChatFormatting.GOLD), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            if (!state.getValue(BlockStateProperties.LIT)) return;
            int fuel = state.getValue(FUEL) - 1;
            level.setBlockAndUpdate(pos, state.setValue(FUEL, Math.max(0, fuel)).setValue(BlockStateProperties.LIT, fuel > 0));
            if (fuel > 0) level.scheduleTick(pos, this, FUEL_TICKS);
            // A fireplace throws sparks: anything that burns right in front of it may catch fire.
            if (fireplace && fuel > 0 && RealismConfig.KITCHEN_FIRES.get() && random.nextInt(8) == 0) {
                BlockPos front = pos.relative(state.getValue(FACING));
                if (level.getBlockState(front).isAir()) {
                    for (Direction d : Direction.values())
                        if (d != state.getValue(FACING).getOpposite() && level.getBlockState(front.relative(d)).isFlammable(level, front.relative(d), d.getOpposite())) {
                            level.setBlockAndUpdate(front, BaseFireBlock.getState(level, front));
                            break;
                        }
                }
            }
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (!state.getValue(BlockStateProperties.LIT)) return;
            Direction f = state.getValue(FACING);
            double x = pos.getX() + 0.5 + f.getStepX() * 0.52, y = pos.getY() + 0.3, z = pos.getZ() + 0.5 + f.getStepZ() * 0.52;
            if (random.nextInt(6) == 0) level.playLocalSound(x, y, z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.7f, 1f, false);
            level.addParticle(ParticleTypes.FLAME, x + (random.nextDouble() - 0.5) * 0.4, y, z + (random.nextDouble() - 0.5) * 0.4, 0, 0.01, 0);
            // No chimney: the smoke pours into the room.
            if (fireplace && !level.getBlockState(pos.above()).is(CHIMNEY.get()))
                level.addParticle(ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, f.getStepX() * 0.03, 0.02, f.getStepZ() * 0.03);
        }
    }

    /** Stack chimneys on a fireplace; the top one lets the smoke out. */
    public static class Chimney extends Block {
        Chimney(Properties props) {
            super(props);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (level.getBlockState(pos.above()).is(this)) return;
            BlockPos p = pos.below();
            for (int i = 0; i < 16 && level.getBlockState(p).is(this); i++) p = p.below();
            BlockState fire = level.getBlockState(p);
            if (fire.is(FIREPLACE.get()) && fire.getValue(BlockStateProperties.LIT))
                level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1),
                        pos.getY() + 1.1, pos.getZ() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1), 0, 0.07, 0);
        }
    }

    /** A flat electric radiator on the wall: warm while the grid has power. */
    public static class Radiator extends HorizontalDirectionalBlock {
        private static final VoxelShape NORTH = Block.box(1, 2, 13, 15, 13, 16), SOUTH = Block.box(1, 2, 0, 15, 13, 3),
                WEST = Block.box(13, 2, 1, 16, 13, 15), EAST = Block.box(0, 2, 1, 3, 13, 15);

        Radiator(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BlockStateProperties.POWERED, false));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return simpleCodec(Radiator::new);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, BlockStateProperties.POWERED);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return switch (state.getValue(FACING)) {
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case EAST -> EAST;
                default -> NORTH;
            };
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            level.scheduleTick(pos, this, 2);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            boolean on = Power.powered(level, pos);
            if (state.getValue(BlockStateProperties.POWERED) != on) level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, on), Block.UPDATE_ALL);
            level.scheduleTick(pos, this, 20);
        }
    }

    /** On the ceiling: beeps when there's smoke or fire within 8 blocks. */
    public static class Detector extends Block {
        private static final VoxelShape SHAPE = Block.box(5, 14, 5, 11, 16, 11);

        Detector(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(ALARM, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(ALARM);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            level.scheduleTick(pos, this, 20);
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            boolean smoke = smokeAt(level, pos.below(DETECTOR_RANGE / 2), DETECTOR_RANGE) > 0;
            if (state.getValue(ALARM) != smoke) level.setBlockAndUpdate(pos, state.setValue(ALARM, smoke));
            if (smoke) level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 1.5f, 2f);
            level.scheduleTick(pos, this, smoke ? 10 : 40);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            // Press the test button.
            if (!level.isClientSide) level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 1.5f, 2f);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
