package com.jdj125.realism;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Realism 4.1: real life, wave 1.
 * <ul>
 * <li>Food goes bad: raw meat and fish after 2 days, most food after 4, bread after 5, vegetables after 10. Spoiled
 * food gives you food poisoning. A powered Fridge keeps everything in it fresh.</li>
 * <li>Sunburn: hours in a hot place's sun without a hat gives a 1st degree burn.</li>
 * <li>Allergies: some people are allergic to pollen (sneezing near flowers), cats (sneezing near cats) or bees (a
 * sting makes you swell up). Everyone finds out what they're allergic to when they join.</li>
 * <li>Trees fall: chop the bottom log of a tree and the whole trunk comes down (crouch to chop just one log).</li>
 * <li>Ice is slippery: running on ice can make you fall.</li>
 * <li>Wet clothes: swimming or rain soaks you; being soaked somewhere cold can give you the flu.</li>
 * </ul>
 */
public final class RealLife {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Realism.MODID);

    /** The (Minecraft) day a food was made or last kept cold. */
    public static final Supplier<DataComponentType<Long>> MADE = COMPONENTS.registerComponentType("made_day",
            b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));

    public static final DeferredBlock<Block> FRIDGE = BLOCKS.register("fridge",
            () -> new Fridge(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> FRIDGE_ITEM = ITEMS.registerSimpleBlockItem(FRIDGE);
    public static final Supplier<BlockEntityType<FridgeEntity>> FRIDGE_ENTITY = BLOCK_ENTITIES.register("fridge",
            () -> BlockEntityType.Builder.of(FridgeEntity::new, FRIDGE.get()).build(null));
    public static final int FRIDGE_W = 100;

    private RealLife() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(FRIDGE_ITEM.get());
        });
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    public static long today(Level level) {
        return level.getGameTime() / 24000L;
    }

    // ---- Food goes bad ----

    private static final Set<Item> NEVER = Set.of(Items.ROTTEN_FLESH, Items.HONEY_BOTTLE, Items.DRIED_KELP, Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.CHORUS_FRUIT,
            Items.SUSPICIOUS_STEW, Items.OMINOUS_BOTTLE);
    private static final Set<Item> RAW = Set.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD,
            Items.SALMON, Items.TROPICAL_FISH, Items.MILK_BUCKET);
    private static final Set<Item> VEG = Set.of(Items.CARROT, Items.POTATO, Items.BEETROOT, Items.APPLE, Items.COOKIE);

    /** How many days this food stays good, or -1 if it never goes bad (or isn't food). */
    public static int shelfDays(ItemStack s) {
        Item i = s.getItem();
        if (NEVER.contains(i)) return -1;
        if (RAW.contains(i)) return 2;
        if (i == Items.BREAD) return 5;
        if (VEG.contains(i)) return 10;
        if (!s.has(DataComponents.FOOD)) return -1;
        return 4;
    }

    /** Days old (0 if it isn't stamped yet). */
    public static long age(ItemStack s, Level level) {
        Long made = s.get(MADE.get());
        return made == null ? 0 : Math.max(0, today(level) - made);
    }

    public static boolean spoiled(ItemStack s, Level level) {
        int shelf = shelfDays(s);
        return shelf >= 0 && age(s, level) > shelf;
    }

    /** New food gets the day it was made, so it can age (even in a chest, like real life). */
    static void stamp(ItemStack s, Level level) {
        if (!s.isEmpty() && shelfDays(s) >= 0 && !s.has(MADE.get())) s.set(MADE.get(), today(level));
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.FOOD_SPOILS)) return;
        if (!spoiled(event.getItem(), p.level())) return;
        Body b = BodyEvents.body(p);
        b.foodPoisoning = (int) Math.max(b.foodPoisoning, RealismConfig.dayTicks());
        say(p, "spoiled_eaten");
        Food.vomit(p);
    }

    // ---- The fridge ----

    public static class Fridge extends BaseEntityBlock {
        static final MapCodec<Fridge> CODEC = simpleCodec(Fridge::new);
        public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

        public Fridge(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
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
        protected RenderShape getRenderShape(BlockState s) {
            return RenderShape.MODEL;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FridgeEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide || type != FRIDGE_ENTITY.get() ? null : (l, p, s, be) -> ((FridgeEntity) be).tick(l, p);
        }

        @Override
        protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
            if (level.getBlockEntity(pos) instanceof FridgeEntity f) {
                p.openMenu(f);
                if (!Power.powered(level, pos)) say(p, "fridge_off");
                level.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.5f, 1.4f);
            }
            return net.minecraft.world.InteractionResult.CONSUME;
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof FridgeEntity f)
                net.minecraft.world.Containers.dropContents(level, pos, f);
            super.onRemove(state, level, pos, newState, moved);
        }
    }

    public static class FridgeEntity extends BaseContainerBlockEntity {
        private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);

        public FridgeEntity(BlockPos pos, BlockState state) {
            super(FRIDGE_ENTITY.get(), pos, state);
        }

        private long lastDay = -1;

        /** While it has power, the clock stops for everything inside (food doesn't get older). */
        void tick(Level level, BlockPos pos) {
            if (level.getGameTime() % 100 != 0) return;
            long now = today(level);
            if (lastDay < 0) lastDay = now;
            boolean cold = Power.powered(level, pos);
            for (ItemStack s : items) {
                if (s.isEmpty() || shelfDays(s) < 0) continue;
                Long made = s.get(MADE.get());
                if (made == null) s.set(MADE.get(), now);
                else if (cold && now > lastDay) s.set(MADE.get(), made + (now - lastDay));
            }
            if (now != lastDay) setChanged();
            lastDay = now;
        }

        @Override
        protected Component getDefaultName() {
            return Component.translatable("block.realism.fridge");
        }

        @Override
        protected NonNullList<ItemStack> getItems() {
            return items;
        }

        @Override
        protected void setItems(NonNullList<ItemStack> list) {
            items = list;
        }

        @Override
        protected AbstractContainerMenu createMenu(int id, Inventory inv) {
            return ChestMenu.threeRows(id, inv, this);
        }

        @Override
        public int getContainerSize() {
            return 27;
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
            super.loadAdditional(tag, reg);
            items = NonNullList.withSize(27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items, reg);
            lastDay = tag.contains("lastDay") ? tag.getLong("lastDay") : -1;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
            super.saveAdditional(tag, reg);
            ContainerHelper.saveAllItems(tag, items, reg);
            tag.putLong("lastDay", lastDay);
        }
    }

    // ---- Allergies ----

    public enum Allergy { POLLEN, CATS, BEES }

    /** What this person is allergic to: the same every time (like real life, you're born with it). */
    public static EnumSet<Allergy> allergies(Player p) {
        EnumSet<Allergy> out = EnumSet.noneOf(Allergy.class);
        long h = p.getUUID().getLeastSignificantBits() ^ p.getUUID().getMostSignificantBits();
        if (Math.floorMod(h, 4) == 0) out.add(Allergy.POLLEN);
        if (Math.floorMod(h >> 8, 5) == 0) out.add(Allergy.CATS);
        if (Math.floorMod(h >> 16, 7) == 0) out.add(Allergy.BEES);
        return out;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.ALLERGIES)) return;
        var a = allergies(p);
        Component what = a.isEmpty() ? Component.translatable("realism.life.allergy.none")
                : Component.literal(String.join(", ", a.stream().map(x -> Component.translatable("realism.life.allergy." + x.name().toLowerCase()).getString()).toList()));
        p.displayClientMessage(Component.translatable("realism.life.allergy.yours", what).withStyle(ChatFormatting.YELLOW), false);
    }

    public static void sneeze(ServerPlayer p) {
        p.serverLevel().sendParticles(ParticleTypes.SNEEZE, p.getX() + p.getLookAngle().x * 0.5, p.getEyeY() - 0.1, p.getZ() + p.getLookAngle().z * 0.5,
                6, 0.1, 0.05, 0.1, 0.02);
        p.level().playSound(null, p.blockPosition(), SoundEvents.PANDA_SNEEZE, SoundSource.PLAYERS, 0.9f, 1.3f);
        BodyEvents.body(p).mood = Math.max(0, BodyEvents.body(p).mood - 1);
        say(p, "sneeze");
    }

    static boolean nearFlowers(ServerPlayer p) {
        BlockPos at = p.blockPosition();
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, -1, -4), at.offset(4, 2, 4)))
            if (p.level().getBlockState(q).is(BlockTags.FLOWERS) && ++n >= 3) return true;
        return false;
    }

    @SubscribeEvent
    public static void onSting(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getSource().getEntity() instanceof Bee) || !on(RealismConfig.ALLERGIES)) return;
        if (!allergies(p).contains(Allergy.BEES)) return;
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 40, 1, false, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 12, 0, false, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 0, false, false, true));
        p.displayClientMessage(Component.translatable("realism.life.allergy.stung").withStyle(ChatFormatting.RED), false);
    }

    // ---- The tick: sun, allergies, ice, wet clothes, fresh food ----

    private static final Map<UUID, int[]> STATE = new ConcurrentHashMap<>(); // sun seconds, wet ticks, sneeze cooldown

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || p.getAbilities().instabuild) return;
        int[] st = STATE.computeIfAbsent(p.getUUID(), u -> new int[3]);
        ServerLevel level = p.serverLevel();
        // Ice: running on it can make you slip (crouch-walking is safe).
        if (on(RealismConfig.SLIPPERY_ICE) && p.isSprinting() && p.onGround() && p.tickCount % 10 == 0
                && level.getBlockState(p.getOnPos()).is(BlockTags.ICE) && p.getRandom().nextFloat() < 0.06f) slip(p);
        // Wet clothes
        if (p.isInWaterOrRain()) {
            if (st[1] <= 0 && on(RealismConfig.WET_CLOTHES)) say(p, "soaked");
            st[1] = 20 * 120;
        } else if (st[1] > 0) {
            st[1] -= p.isOnFire() || nearHeat(p) ? 6 : 1;
            if (st[1] <= 0) say(p, "dry");
        }
        if (st[2] > 0) st[2]--;
        if (p.tickCount % 20 != 0) return;
        float temp = level.getBiome(p.blockPosition()).value().getBaseTemperature();
        // Sunburn: an hour (in game) of hot sun with no hat.
        if (on(RealismConfig.SUNBURN) && temp >= 1.0f && level.isDay() && !level.isRaining() && level.canSeeSky(p.blockPosition().above())
                && p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            if (++st[0] == 300) say(p, "sun_hot");
            if (st[0] >= 600) {
                st[0] = 0;
                BodyEvents.burn(p, 1);
                say(p, "sunburn");
            }
        } else st[0] = Math.max(0, st[0] - 2);
        // Wet and cold: the flu can catch you.
        if (on(RealismConfig.WET_CLOTHES) && st[1] > 0 && temp < 0.3f && !Wave2.warmClothes(p) && p.tickCount % 400 == 0 && p.getRandom().nextFloat() < 0.15f) {
            Body b = BodyEvents.body(p);
            if (b.flu <= 0) {
                b.flu = (int) Math.min(Integer.MAX_VALUE, RealismConfig.dayTicks() * 2);
                say(p, "cold_flu");
            }
        }
        // Allergies
        if (on(RealismConfig.ALLERGIES) && st[2] == 0) {
            var a = allergies(p);
            boolean pollen = a.contains(Allergy.POLLEN) && level.isDay() && nearFlowers(p);
            boolean cats = a.contains(Allergy.CATS) && !level.getEntitiesOfClass(Cat.class, new AABB(p.blockPosition()).inflate(4)).isEmpty();
            if ((pollen || cats) && p.getRandom().nextFloat() < 0.3f) {
                sneeze(p);
                st[2] = 20 * 15;
            }
        }
        // Food: stamp anything new so it can age.
        if (on(RealismConfig.FOOD_SPOILS) && p.tickCount % 100 == 0)
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) stamp(p.getInventory().getItem(i), level);
    }

    static boolean nearHeat(ServerPlayer p) {
        BlockPos at = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 1, 2))) {
            BlockState s = p.level().getBlockState(q);
            if (s.is(BlockTags.CAMPFIRES) || s.is(BlockTags.FIRE) || s.getLightEmission() >= 13 && s.getBlock() != net.minecraft.world.level.block.Blocks.GLOWSTONE)
                return true;
        }
        return false;
    }

    public static void slip(ServerPlayer p) {
        p.setSprinting(false);
        p.hurt(p.damageSources().fall(), 1);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 3, false, false, true));
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_SMALL_FALL, SoundSource.PLAYERS, 1, 0.8f);
        say(p, "slip");
        SnackSecurity.record(p.serverLevel(), p.blockPosition(), "ice_fail", p.getGameProfile().getName()); // caught on camera
    }

    public static boolean wet(Player p) {
        int[] st = STATE.get(p.getUUID());
        return st != null && st[1] > 0;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATE.remove(event.getEntity().getUUID());
    }

    // ---- Trees fall ----

    @SubscribeEvent
    public static void onChop(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (!on(RealismConfig.TREES_FALL) || p.isShiftKeyDown() || p.getAbilities().instabuild || !event.getState().is(BlockTags.LOGS)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<BlockPos> trunk = trunk(level, event.getPos());
        if (trunk.isEmpty()) return;
        for (BlockPos q : trunk) level.destroyBlock(q, true, p);
        ItemStack axe = p.getMainHandItem();
        if (axe.isDamageableItem() && p instanceof ServerPlayer sp) axe.hurtAndBreak(Math.min(trunk.size(), 20), sp, EquipmentSlot.MAINHAND);
        level.playSound(null, event.getPos(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
        Body b = BodyEvents.body(p);
        b.stamina = Math.max(0, b.stamina - Math.min(30, trunk.size()));
        say(p, "timber");
    }

    /** The logs above this one, if it's part of a real tree (with leaves on top). Up to 160 logs. */
    public static List<BlockPos> trunk(Level level, BlockPos start) {
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(start);
        seen.add(start);
        boolean leaves = false;
        while (!todo.isEmpty() && logs.size() < 160) {
            BlockPos at = todo.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = at.offset(dx, dy, dz);
                        if (q.getY() < start.getY() || !seen.add(q.immutable())) continue;
                        BlockState s = level.getBlockState(q);
                        if (s.is(BlockTags.LOGS)) {
                            logs.add(q.immutable());
                            todo.add(q.immutable());
                        } else if (s.is(BlockTags.LEAVES) && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) leaves = true;
                    }
        }
        return leaves ? logs : List.of(); // no natural leaves = a log house, not a tree
    }
}
