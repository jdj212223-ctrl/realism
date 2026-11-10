package com.jdj125.realism;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Function;

/**
 * Realism 2.0.2, daily life:
 * <ul>
 * <li><b>Hygiene</b>: you get dirty over about two Minecraft days (faster when you sprint, slower in rain or water).
 * Dirty means you stink: green fumes around you that everyone sees, and your mood drops. Shower to fix it, or wash
 * your hands at a sink.</li>
 * <li><b>Bladder</b>: it fills over about a day. Use a toilet. Wait too long and you have an accident (gross).</li>
 * <li><b>Teeth</b>: they go bad over a few days, faster with sweets. Brush with a toothbrush or get toothache.</li>
 * </ul>
 */
public final class Life {
    private Life() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredBlock<Block> SHOWER = fixture("shower", Shower::new);
    public static final DeferredBlock<Block> TOILET = fixture("toilet", Toilet::new);
    public static final DeferredBlock<Block> SINK = fixture("sink", Sink::new);
    public static final DeferredItem<Item> TOOTHBRUSH = ITEMS.register("toothbrush", () -> new Toothbrush(new Item.Properties().durability(60)));

    private static DeferredBlock<Block> fixture(String name, Function<BlockBehaviour.Properties, Block> make) {
        DeferredBlock<Block> b = BLOCKS.register(name, () -> make.apply(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.STONE).noOcclusion()));
        ITEMS.registerSimpleBlockItem(b);
        return b;
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    // Per second (a minute of real time is 60 of these). Rates per second.
    static final float DIRTY = 100f / 2400, BLADDER = 100f / 1200, TEETH = 100f / 4800;

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life." + key, args), true);
    }

    /** One second of daily life (from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        if (!RealismConfig.DAILY_LIFE.get()) return;
        float dirt = DIRTY * (p.isSprinting() ? 2 : 1);
        if (p.isInWaterOrRain()) dirt = -0.5f;
        b.hygiene = Math.max(0, Math.min(100, b.hygiene - dirt));
        b.bladder = Math.min(100, b.bladder + BLADDER);
        b.teeth = Math.max(0, b.teeth - TEETH);
        // Stinky: everyone can see it.
        if (b.hygiene < 30) {
            if (p.tickCount % 40 == 0 && p.level() instanceof ServerLevel sl)
                sl.sendParticles(ParticleTypes.SNEEZE, p.getX(), p.getY() + 1.2, p.getZ(), 4, 0.35, 0.4, 0.35, 0.01);
            Mind.lift(p, -0.02f);
            if ((b.lifeStage & 1) == 0) {
                b.lifeStage |= 1;
                say(p, "stinky");
            }
        } else b.lifeStage &= ~1;
        if (b.bladder >= 80 && (b.lifeStage & 2) == 0) {
            b.lifeStage |= 2;
            say(p, "bladder_full");
        }
        if (b.bladder >= 100) accident(p, b);
        if (b.teeth < 25 && p.tickCount % (60 * 20) == 0) {
            Mind.lift(p, -3);
            say(p, "toothache");
        }
    }

    /** Waited too long for the toilet. */
    static void accident(ServerPlayer p, Body b) {
        b.bladder = 0;
        b.lifeStage &= ~2;
        b.hygiene = Math.max(0, b.hygiene - 50);
        Mind.lift(p, -15);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 0, true, false));
        p.displayClientMessage(Component.translatable("realism.life.accident"), false);
    }

    public static void shower(Player p) {
        Body b = BodyEvents.body(p);
        b.hygiene = 100;
        b.lifeStage &= ~1;
        p.clearFire();
        Mind.lift(p, 3);
    }

    public static void toilet(Player p) {
        Body b = BodyEvents.body(p);
        b.bladder = 0;
        b.lifeStage &= ~2;
        Mind.lift(p, 2);
    }

    public static void brush(Player p) {
        BodyEvents.body(p).teeth = 100;
    }

    // Sweets are bad for your teeth.
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack s = event.getItem();
        if (s.is(Items.CAKE) || s.is(Items.COOKIE) || s.is(Items.HONEY_BOTTLE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.SUGAR) || s.is(Items.GLOW_BERRIES) || s.is(Items.SWEET_BERRIES))
            BodyEvents.body(p).teeth = Math.max(0, BodyEvents.body(p).teeth - 6);
    }

    // ---- Bathroom fixtures ----

    abstract static class Fixture extends HorizontalDirectionalBlock {
        private final VoxelShape shape;

        Fixture(Properties props, VoxelShape shape) {
            super(props);
            this.shape = shape;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
            return shape;
        }

        abstract void use(ServerPlayer p, BlockPos pos);

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) use(sp, pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    static class Shower extends Fixture {
        static final MapCodec<Shower> CODEC = simpleCodec(Shower::new);

        Shower(Properties props) {
            super(props, Block.box(0, 0, 0, 16, 2, 16));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        void use(ServerPlayer p, BlockPos pos) {
            shower(p);
            ServerLevel sl = p.serverLevel();
            sl.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 2.2, pos.getZ() + 0.5, 60, 0.3, 0.8, 0.3, 0.1);
            sl.playSound(null, pos, SoundEvents.WEATHER_RAIN, SoundSource.BLOCKS, 0.8f, 1.4f);
            p.displayClientMessage(Component.translatable("realism.life.showered"), true);
        }
    }

    static class Toilet extends Fixture {
        static final MapCodec<Toilet> CODEC = simpleCodec(Toilet::new);

        Toilet(Properties props) {
            super(props, Block.box(3, 0, 2, 13, 12, 15));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        void use(ServerPlayer p, BlockPos pos) {
            toilet(p);
            p.serverLevel().playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8f, 0.8f);
            p.displayClientMessage(Component.translatable("realism.life.toilet"), true);
        }
    }

    static class Sink extends Fixture {
        static final MapCodec<Sink> CODEC = simpleCodec(Sink::new);

        Sink(Properties props) {
            super(props, Block.box(2, 0, 2, 14, 14, 14));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        void use(ServerPlayer p, BlockPos pos) {
            Body b = BodyEvents.body(p);
            b.hygiene = Math.min(100, b.hygiene + 20);
            p.serverLevel().sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 15, 0.2, 0.1, 0.2, 0.05);
            p.serverLevel().playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6f, 1.3f);
            p.displayClientMessage(Component.translatable("realism.life.washed_hands"), true);
        }
    }

    static class Toothbrush extends Item {
        Toothbrush(Properties props) {
            super(props);
        }

        @Override
        public int getUseDuration(ItemStack stack, LivingEntity entity) {
            return 50;
        }

        @Override
        public UseAnim getUseAnimation(ItemStack stack) {
            return UseAnim.BRUSH;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            p.startUsingItem(hand);
            return InteractionResultHolder.consume(p.getItemInHand(hand));
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity e) {
            if (!level.isClientSide && e instanceof ServerPlayer p) {
                brush(p);
                stack.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                p.displayClientMessage(Component.translatable("realism.life.brushed"), true);
                level.playSound(null, p.blockPosition(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.8f, 1.5f);
            }
            return stack;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.toothbrush.tip"));
        }
    }

    /** For /realism: your daily-life numbers. */
    public static String status(Player p) {
        Body b = BodyEvents.body(p);
        return Math.round(b.hygiene) + "/" + Math.round(b.bladder) + "/" + Math.round(b.teeth);
    }
}
