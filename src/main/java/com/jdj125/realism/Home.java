package com.jdj125.realism;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 1.0.0 around the house:
 * <ul>
 * <li><b>Grandma's Stove</b>: the old cast-iron wood stove from grandparents' houses. It cooks food twice as fast
 * (like a smoker), warms the room (Cold Sweat), makes everyone nearby cosy (mood), and food from it is "made with
 * love" (extra mood when you eat it). Touch it while it's burning and you get burned, like any furnace.</li>
 * <li><b>No crafting table</b>: real life has no magic crafting box. Build a Workbench and hold a Hammer to craft
 * (each craft wears the hammer down). The 2x2 grid in your inventory still works.</li>
 * <li><b>Voice-only chat</b>: with Simple Voice Chat installed, typed chat is turned off: talk with your voice.
 * Commands and phone texts still work (voiceOnlyChat = false brings typing back).</li>
 * <li><b>VR burns</b>: with Vivecraft, your real hand touching a burning furnace or a boiler burns you.</li>
 * </ul>
 */
public final class Home {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Realism.MODID);

    public static final DeferredBlock<Block> GRANDMA_STOVE = BLOCKS.register("grandma_stove",
            () -> new Stove(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops()
                    .lightLevel(s -> s.getValue(BlockStateProperties.LIT) ? 11 : 0)));
    public static final DeferredBlock<Block> WORKBENCH = BLOCKS.register("workbench",
            () -> new Workbench(BlockBehaviour.Properties.of().strength(2.5f).sound(SoundType.WOOD).ignitedByLava()));
    public static final DeferredItem<BlockItem> GRANDMA_STOVE_ITEM = ITEMS.registerSimpleBlockItem(GRANDMA_STOVE);
    public static final DeferredItem<BlockItem> WORKBENCH_ITEM = ITEMS.registerSimpleBlockItem(WORKBENCH);
    public static final DeferredItem<Item> HAMMER = ITEMS.register("hammer", () -> new Item(new Item.Properties().durability(120)) {
        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.hammer.tip"));
        }
    });

    public static final Supplier<BlockEntityType<StoveEntity>> STOVE_ENTITY = BLOCK_ENTITIES.register("grandma_stove",
            () -> BlockEntityType.Builder.of(StoveEntity::new, GRANDMA_STOVE.get()).build(null));
    /** Food cooked on Grandma's Stove. */
    public static final Supplier<DataComponentType<Boolean>> MADE_WITH_LOVE = COMPONENTS.registerComponentType("made_with_love",
            b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    private Home() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(Home::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(GRANDMA_STOVE_ITEM.get());
            event.accept(WORKBENCH_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(HAMMER.get());
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    // ---- Grandma's Stove ----

    static class Stove extends AbstractFurnaceBlock {
        static final MapCodec<Stove> CODEC = simpleCodec(Stove::new);

        Stove(Properties props) {
            super(props);
        }

        @Override
        protected MapCodec<? extends AbstractFurnaceBlock> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new StoveEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            BlockEntityTicker<T> furnace = createFurnaceTicker(level, type, STOVE_ENTITY.get());
            if (level.isClientSide || furnace == null) return furnace;
            // Besides cooking: once every 2 seconds a burning stove makes everyone within 6 blocks feel cosy.
            return (l, p, s, be) -> {
                furnace.tick(l, p, s, be);
                if (l.getGameTime() % 40 == 0 && s.getValue(LIT))
                    for (Player pl : l.getEntitiesOfClass(Player.class, new AABB(p).inflate(6))) Mind.lift(pl, 0.08f);
            };
        }

        @Override
        protected void openContainer(Level level, BlockPos pos, Player player) {
            if (level.getBlockEntity(pos) instanceof StoveEntity be) player.openMenu(be);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
            if (!state.getValue(LIT)) return;
            if (random.nextInt(8) == 0)
                level.playLocalSound(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.6f, 1f, false);
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 0, 0.03, 0);
        }
    }

    public static class StoveEntity extends AbstractFurnaceBlockEntity {
        public StoveEntity(BlockPos pos, BlockState state) {
            super(STOVE_ENTITY.get(), pos, state, RecipeType.SMOKING);
        }

        @Override
        protected Component getDefaultName() {
            return Component.translatable("block.realism.grandma_stove");
        }

        @Override
        protected AbstractContainerMenu createMenu(int id, Inventory inv) {
            return new SmokerMenu(id, inv, this, this.dataAccess);
        }
    }

    // Taking food out of Grandma's Stove: it's made with love.
    @SubscribeEvent
    public static void onTakeFood(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity().containerMenu instanceof SmokerMenu menu && menu.slots.get(0).container instanceof StoveEntity)
            event.getSmelting().set(MADE_WITH_LOVE.get(), true);
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof Player p && !p.level().isClientSide && Boolean.TRUE.equals(event.getItem().get(MADE_WITH_LOVE.get()))) {
            Mind.lift(p, 6);
            say(p, "stove.love");
        }
    }

    // ---- Workbench and hammer (no crafting table) ----

    static boolean holdsHammer(Player p) {
        return p.getMainHandItem().is(HAMMER.get()) || p.getOffhandItem().is(HAMMER.get());
    }

    static class Workbench extends Block {
        Workbench(Properties props) {
            super(props);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (!holdsHammer(player)) {
                say(player, "workbench.hammer");
                return InteractionResult.CONSUME;
            }
            player.openMenu(new SimpleMenuProvider((id, inv, pl) -> new WorkbenchMenu(id, inv, ContainerLevelAccess.create(level, pos)),
                    Component.translatable("block.realism.workbench")));
            return InteractionResult.CONSUME;
        }
    }

    /** The crafting grid, kept open only while you're at a Workbench. */
    public static class WorkbenchMenu extends CraftingMenu {
        private final ContainerLevelAccess access;

        public WorkbenchMenu(int id, Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
            this.access = access;
        }

        @Override
        public boolean stillValid(Player player) {
            return stillValid(access, player, WORKBENCH.get());
        }
    }

    // Every craft at the workbench wears the hammer down a little.
    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        Player p = event.getEntity();
        if (p.level().isClientSide || !(p.containerMenu instanceof WorkbenchMenu)) return;
        InteractionHand hand = p.getMainHandItem().is(HAMMER.get()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack hammer = p.getItemInHand(hand);
        if (hammer.is(HAMMER.get())) hammer.hurtAndBreak(1, p, hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
    }

    @SubscribeEvent
    public static void onUseTable(PlayerInteractEvent.RightClickBlock event) {
        if (!RealismConfig.NO_CRAFTING_TABLE.get() || event.getEntity().isShiftKeyDown()) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(Blocks.CRAFTING_TABLE)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (!event.getLevel().isClientSide) say(event.getEntity(), "workbench.no_table");
    }

    // ---- Voice-only chat ----

    public static boolean voiceOnly() {
        return RealismConfig.VOICE_ONLY_CHAT.get() && ModList.get().isLoaded("voicechat");
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (!voiceOnly()) return;
        event.setCanceled(true);
        event.getPlayer().displayClientMessage(Component.translatable("realism.chat.voice_only"), true);
    }

    // ---- VR burns (Vivecraft) ----

    private static Method vrInstance, vrPose, vrMain, vrOff, vrPos;
    private static boolean vrTried;
    private static final Map<UUID, Long> lastVrBurn = new HashMap<>();

    /** Where this VR player's real hands are, or null if they aren't in VR (or Vivecraft isn't installed). */
    static Vec3[] vrHands(Player p) {
        if (!vrTried) {
            vrTried = true;
            try {
                Class<?> api = Class.forName("org.vivecraft.api.VRAPI");
                vrInstance = api.getMethod("instance");
                vrPose = api.getMethod("getVRPose", Player.class);
                Class<?> pose = Class.forName("org.vivecraft.api.data.VRPose");
                vrMain = pose.getMethod("getMainHand");
                vrOff = pose.getMethod("getOffHand");
                vrPos = Class.forName("org.vivecraft.api.data.VRBodyPartData").getMethod("getPos");
            } catch (ReflectiveOperationException e) {
                vrInstance = null;
            }
        }
        if (vrInstance == null) return null;
        try {
            Object pose = vrPose.invoke(vrInstance.invoke(null), p);
            if (pose == null) return null;
            Object main = vrMain.invoke(pose), off = vrOff.invoke(pose);
            return new Vec3[]{main == null ? null : (Vec3) vrPos.invoke(main), off == null ? null : (Vec3) vrPos.invoke(off)};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** How badly touching this block burns a bare hand: 0 none, 1-3 degrees (a working furnace), 3 (a boiler). */
    public static int burnDegree(BlockState s, net.minecraft.util.RandomSource random) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        boolean lit = s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT);
        if (id.equals("cold_sweat:boiler") && (lit || !s.hasProperty(BlockStateProperties.LIT))) return 3;
        if (s.getBlock() instanceof AbstractFurnaceBlock && lit) {
            float r = random.nextFloat();
            return r < 0.6f ? 1 : r < 0.9f ? 2 : 3;
        }
        return 0;
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 5 != 0 || !RealismConfig.BURNS.get() || p.isCreative()) return;
        Vec3[] hands = vrHands(p);
        if (hands == null) return;
        long now = p.level().getGameTime();
        if (now - lastVrBurn.getOrDefault(p.getUUID(), -1000L) < 60) return;
        for (int i = 0; i < 2; i++) {
            if (hands[i] == null || !(i == 0 ? p.getMainHandItem() : p.getOffhandItem()).isEmpty()) continue;
            // The hand is inside the block, or pressed against it (within a few centimetres).
            for (Vec3 at : new Vec3[]{hands[i], hands[i].add(0, -0.08, 0)}) {
                int degree = burnDegree(p.level().getBlockState(BlockPos.containing(at)), p.getRandom());
                if (degree == 0) continue;
                lastVrBurn.put(p.getUUID(), now);
                BodyEvents.burn(p, degree);
                return;
            }
        }
    }
}
