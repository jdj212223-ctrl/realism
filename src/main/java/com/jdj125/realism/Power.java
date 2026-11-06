package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 1.0.0: electricity. A grid is everything joined together by Power Cables (or touching each other): generators make
 * watts, devices use them. If the grid makes at least as much as it uses, every device on it has power; if you plug in
 * too much, the whole grid blacks out until you add another generator or unplug something.
 * <ul>
 * <li>Generator (100 W): burns coal or charcoal (one lump lasts 2 minutes). Making the first one invents
 * electricity.</li>
 * <li>Solar Panel (40 W): only in daylight, under the open sky.</li>
 * <li>Devices: Electric Lamp (10 W), PC Case and ready-built computers (60 W, redstone still works too), Medical
 * Scanner (50 W), Patient Monitor (20 W).</li>
 * </ul>
 */
public final class Power {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);

    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public static final DeferredBlock<Block> GENERATOR = BLOCKS.register("generator",
            () -> new Generator(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops()
                    .lightLevel(s -> s.getValue(BlockStateProperties.LIT) ? 8 : 0)));
    public static final DeferredBlock<Block> SOLAR_PANEL = BLOCKS.register("solar_panel",
            () -> new Solar(BlockBehaviour.Properties.of().strength(2f).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredBlock<Block> CABLE = BLOCKS.register("power_cable",
            () -> new Cable(BlockBehaviour.Properties.of().strength(0.3f).sound(SoundType.COPPER).noOcclusion().noCollission()));
    public static final DeferredBlock<Block> LAMP = BLOCKS.register("electric_lamp",
            () -> new Lamp(BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.GLASS).lightLevel(s -> s.getValue(POWERED) ? 15 : 0)));
    public static final DeferredItem<BlockItem> GENERATOR_ITEM = ITEMS.registerSimpleBlockItem(GENERATOR);
    public static final DeferredItem<BlockItem> SOLAR_PANEL_ITEM = ITEMS.registerSimpleBlockItem(SOLAR_PANEL);
    public static final DeferredItem<BlockItem> CABLE_ITEM = ITEMS.registerSimpleBlockItem(CABLE);
    public static final DeferredItem<BlockItem> LAMP_ITEM = ITEMS.registerSimpleBlockItem(LAMP);

    public static final Supplier<BlockEntityType<GeneratorEntity>> GENERATOR_ENTITY = BLOCK_ENTITIES.register("generator",
            () -> BlockEntityType.Builder.of(GeneratorEntity::new, GENERATOR.get()).build(null));

    public static final int GENERATOR_W = 100, SOLAR_W = 40, LAMP_W = 10, COMPUTER_W = 60, SCANNER_W = 50, MONITOR_W = 20;
    /** Ticks one piece of coal keeps a generator running. */
    public static final int COAL_TICKS = 2 * 60 * 20;

    private Power() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(Power::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS || event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(GENERATOR_ITEM.get());
            event.accept(SOLAR_PANEL_ITEM.get());
            event.accept(CABLE_ITEM.get());
            event.accept(LAMP_ITEM.get());
        }
    }

    // ---- The grid ----

    /** Watts a block makes (positive), uses (negative), or 0 if it isn't electrical. Cables carry power but don't count. */
    static int watts(Level level, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        if (b == GENERATOR.get()) return s.getValue(BlockStateProperties.LIT) ? GENERATOR_W : 0;
        if (b == SOLAR_PANEL.get()) return level.isDay() && !level.isRaining() && level.canSeeSky(pos.above()) ? SOLAR_W : 0;
        if (b == LAMP.get()) return -LAMP_W;
        if (b == Health.MEDICAL_SCANNER.get()) return -SCANNER_W;
        if (b == Hospital.MONITOR.get()) return -MONITOR_W;
        if (b == Tech.PC_CASE.get() || Tech.MODEL_BLOCKS.stream().anyMatch(m -> m.get() == b)) return -COMPUTER_W;
        return 0;
    }

    static boolean electrical(Level level, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        return b == CABLE.get() || b == GENERATOR.get() || b == SOLAR_PANEL.get() || b == LAMP.get() || b == Health.MEDICAL_SCANNER.get()
                || b == Hospital.MONITOR.get() || b == Tech.PC_CASE.get() || Tech.MODEL_BLOCKS.stream().anyMatch(m -> m.get() == b);
    }

    /** What a grid makes and uses. */
    public record Grid(int made, int used) {
        public boolean on() {
            return made > 0 && made >= used;
        }
    }

    // One grid walk per block per tick is plenty: remember results until the game time moves on.
    private static final Map<Level, Map<BlockPos, Grid>> CACHE = new java.util.WeakHashMap<>();
    private static final Map<Level, Long> CACHE_TIME = new java.util.WeakHashMap<>();

    /** The grid this block belongs to (up to 1024 connected blocks). */
    public static Grid grid(Level level, BlockPos start) {
        long now = level.getGameTime();
        if (!Long.valueOf(now).equals(CACHE_TIME.get(level))) {
            CACHE.put(level, new HashMap<>());
            CACHE_TIME.put(level, now);
        }
        Map<BlockPos, Grid> cache = CACHE.get(level);
        Grid known = cache.get(start);
        if (known != null) return known;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(start.immutable());
        seen.add(start.immutable());
        int made = 0, used = 0;
        while (!todo.isEmpty() && seen.size() < 1024) {
            BlockPos p = todo.poll();
            BlockState s = level.getBlockState(p);
            if (!electrical(level, p, s)) continue;
            int w = watts(level, p, s);
            if (w > 0) made += w;
            else used -= w;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.add(n) && electrical(level, n, level.getBlockState(n))) todo.add(n);
            }
        }
        Grid g = new Grid(made, used);
        for (BlockPos p : seen) cache.put(p, g);
        return g;
    }

    /** Does the device at this position have power? */
    public static boolean powered(Level level, BlockPos pos) {
        return grid(level, pos).on();
    }

    // ---- Blocks ----

    static class Generator extends Block implements EntityBlock {
        Generator(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(BlockStateProperties.LIT);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new GeneratorEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide || type != GENERATOR_ENTITY.get() ? null : (l, p, s, be) -> ((GeneratorEntity) be).tick(l, p, s);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            boolean fuel = stack.is(Items.COAL) || stack.is(Items.CHARCOAL) || stack.is(Items.COAL_BLOCK);
            if (!fuel) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            if (level.getBlockEntity(pos) instanceof GeneratorEntity g) {
                if (g.fuel > COAL_TICKS * 40) {
                    player.displayClientMessage(Component.translatable("realism.power.full"), true);
                    return ItemInteractionResult.CONSUME;
                }
                g.fuel += stack.is(Items.COAL_BLOCK) ? COAL_TICKS * 9 : COAL_TICKS;
                g.setChanged();
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.5f, 0.8f);
            }
            return ItemInteractionResult.CONSUME;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            Grid g = grid(level, pos);
            int minutes = level.getBlockEntity(pos) instanceof GeneratorEntity e ? e.fuel / 1200 : 0;
            player.displayClientMessage(Component.translatable(g.on() ? "realism.power.grid" : "realism.power.blackout", g.made(), g.used(), minutes), true);
            return InteractionResult.CONSUME;
        }
    }

    public static class GeneratorEntity extends BlockEntity {
        public int fuel;

        public GeneratorEntity(BlockPos pos, BlockState state) {
            super(GENERATOR_ENTITY.get(), pos, state);
        }

        void tick(Level level, BlockPos pos, BlockState state) {
            boolean lit = fuel > 0;
            if (lit) fuel--;
            if (state.getValue(BlockStateProperties.LIT) != lit) level.setBlock(pos, state.setValue(BlockStateProperties.LIT, lit), Block.UPDATE_ALL);
            if (lit && level.getGameTime() % 80 == 0) level.playSound(null, pos, SoundEvents.MINECART_RIDING, SoundSource.BLOCKS, 0.08f, 0.5f);
            if (level.getGameTime() % 200 == 0) setChanged();
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            tag.putInt("fuel", fuel);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            fuel = tag.getInt("fuel");
        }
    }

    static class Solar extends Block {
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

        Solar(Properties props) {
            super(props);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }
    }

    static class Cable extends Block {
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);

        Cable(Properties props) {
            super(props);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }
    }

    /** A lamp that checks its grid once a second. */
    static class Lamp extends Block {
        Lamp(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(POWERED, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(POWERED);
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            level.scheduleTick(pos, this, 2);
        }

        @Override
        protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
            boolean on = powered(level, pos);
            if (state.getValue(POWERED) != on) level.setBlock(pos, state.setValue(POWERED, on), Block.UPDATE_ALL);
            level.scheduleTick(pos, this, 20);
        }
    }
}
