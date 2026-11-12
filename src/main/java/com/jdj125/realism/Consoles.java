package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Realism 3.5: game consoles through the years. Original look-alikes (no real names or logos; each tooltip credits the
 * real console that inspired it): home consoles need power and a controller, the hybrid handhelds go anywhere. Two of
 * them take a Motion Camera for motion games: punch with your arms and dodge with your head, using your real webcam
 * if you turn it on (only on your computer: nothing is saved or sent), or the keyboard if you don't.
 */
public final class Consoles {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    /** A console: its id (look + year), the year, whether a Motion Camera fits it, whether it's a handheld, watts. */
    public record Model(String id, int year, boolean motion, boolean handheld, int watts) {}

    public static final List<Model> MODELS = List.of(
            new Model("grey_console_1994", 1994, false, false, 10),
            new Model("slim_black_console_2000", 2000, false, false, 50),
            new Model("big_black_box_2001", 2001, false, false, 70),
            new Model("white_curvy_console_2005", 2005, true, false, 120),
            new Model("glossy_black_console_2006", 2006, false, false, 150),
            new Model("black_slab_console_2013", 2013, true, false, 110),
            new Model("angled_console_2013", 2013, false, false, 140),
            new Model("hybrid_handheld_2017", 2017, false, true, 0),
            new Model("black_tower_console_2020", 2020, false, false, 160),
            new Model("white_wing_console_2020", 2020, false, false, 200),
            new Model("hybrid_handheld_2025", 2025, false, true, 0));

    public static final BooleanProperty CAMERA = BooleanProperty.create("camera");
    public static final BlockPos NO_POS = new BlockPos(0, -9999, 0);

    public static final List<DeferredBlock<Block>> BLOCKS_BY_MODEL = new ArrayList<>();
    public static final List<DeferredItem<? extends Item>> ITEMS_BY_MODEL = new ArrayList<>();

    static {
        for (int i = 0; i < MODELS.size(); i++) {
            Model m = MODELS.get(i);
            final int index = i;
            if (m.handheld()) {
                BLOCKS_BY_MODEL.add(null);
                ITEMS_BY_MODEL.add(ITEMS.register(m.id(), () -> new Handheld(new Item.Properties().stacksTo(1), index)));
            } else {
                DeferredBlock<Block> b = BLOCKS.register(m.id(), () -> new ConsoleBlock(BlockBehaviour.Properties.of().strength(1.0f)
                        .sound(SoundType.METAL).noOcclusion(), index));
                BLOCKS_BY_MODEL.add(b);
                ITEMS_BY_MODEL.add(ITEMS.register(m.id(), () -> new ConsoleItem(b.get(), new Item.Properties().stacksTo(1))));
            }
        }
    }

    public static final DeferredItem<Item> CONTROLLER = ITEMS.register("game_controller", () -> new Item(new Item.Properties().stacksTo(4)));
    public static final DeferredItem<Item> MOTION_CAMERA = ITEMS.register("motion_camera", () -> new Tip(new Item.Properties().stacksTo(1)));

    private Consoles() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Consoles::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
            for (var i : ITEMS_BY_MODEL) event.accept(i.get());
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CONTROLLER.get());
            event.accept(MOTION_CAMERA.get());
        }
    }

    public static int index(Block b) {
        for (int i = 0; i < BLOCKS_BY_MODEL.size(); i++)
            if (BLOCKS_BY_MODEL.get(i) != null && BLOCKS_BY_MODEL.get(i).get() == b) return i;
        return -1;
    }

    /** Power a console uses (0 for other blocks). */
    public static int watts(Block b) {
        int i = index(b);
        return i < 0 ? 0 : MODELS.get(i).watts();
    }

    static boolean hasController(Player p) {
        return p.getInventory().contains(new ItemStack(CONTROLLER.get()));
    }

    // ---- Tech Dealer trades: older consoles are cheap, new ones cost a lot ----

    @SubscribeEvent
    public static void trades(VillagerTradesEvent event) {
        if (event.getType() != Tech.TECH_DEALER.get()) return;
        var t = event.getTrades();
        t.get(1).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(CONTROLLER.get()), 12, 2, 0.05f));
        t.get(3).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 8), new ItemStack(MOTION_CAMERA.get()), 4, 10, 0.05f));
        for (int i = 0; i < MODELS.size(); i++) {
            Model m = MODELS.get(i);
            ItemStack stack = new ItemStack(ITEMS_BY_MODEL.get(i).get());
            int level = m.year() < 2001 ? 1 : m.year() < 2006 ? 2 : m.year() < 2014 ? 3 : m.year() < 2020 ? 4 : 5;
            int price = Math.min(64, 4 + (m.year() - 1994) * 2);
            t.get(level).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, price), stack.copy(), 3, 10, 0.05f));
        }
    }

    // ---- Opening a console ----

    static void open(ServerPlayer p, BlockPos pos, int model, boolean camera) {
        PacketDistributor.sendToPlayer(p, new OpenConsole(pos, model, camera));
    }

    public record OpenConsole(BlockPos pos, int model, boolean camera) implements CustomPacketPayload {
        public static final Type<OpenConsole> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "open_console"));
        public static final StreamCodec<ByteBuf, OpenConsole> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, OpenConsole::pos,
                ByteBufCodecs.VAR_INT, OpenConsole::model, ByteBufCodecs.BOOL, OpenConsole::camera, OpenConsole::new);

        public Model info() {
            return MODELS.get(Math.max(0, Math.min(MODELS.size() - 1, model)));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void say(Player p, String key) {
        p.displayClientMessage(Component.translatable("realism.consoles." + key), true);
    }

    // ---- Home consoles ----

    public static class ConsoleBlock extends Block {
        public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
        private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 6, 15), TALL = Block.box(4, 0, 4, 12, 14, 12);
        final int model;

        public ConsoleBlock(Properties props, int model) {
            super(props);
            this.model = model;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CAMERA, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, CAMERA);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return MODELS.get(model).year() >= 2020 ? TALL : SHAPE;
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player p,
                                                  InteractionHand hand, BlockHitResult hit) {
            if (!stack.is(MOTION_CAMERA.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (!MODELS.get(model).motion()) {
                if (!level.isClientSide) say(p, "camera_wont_fit");
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (state.getValue(CAMERA)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (!level.isClientSide) {
                level.setBlockAndUpdate(pos, state.setValue(CAMERA, true));
                if (!p.getAbilities().instabuild) stack.shrink(1);
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1, 1.2f);
                say(p, "camera_plugged");
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (!Power.powered(level, pos)) {
                say(p, "no_power");
                return InteractionResult.CONSUME;
            }
            if (!hasController(p)) {
                say(p, "no_controller");
                return InteractionResult.CONSUME;
            }
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.6f, 1.4f);
            if (p instanceof ServerPlayer sp) open(sp, pos, model, state.getValue(CAMERA));
            return InteractionResult.CONSUME;
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player p) {
            if (!level.isClientSide && state.getValue(CAMERA) && !p.getAbilities().instabuild)
                popResource(level, pos, new ItemStack(MOTION_CAMERA.get()));
            return super.playerWillDestroy(level, pos, state, p);
        }
    }

    /** Tooltip: which real console inspired it, and that it's a fan-made look-alike. */
    static class ConsoleItem extends BlockItem {
        ConsoleItem(Block block, Properties props) {
            super(block, props);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            credit(getDescriptionId(), lines);
            if (getBlock() instanceof ConsoleBlock c && MODELS.get(c.model).motion())
                lines.add(Component.translatable("realism.consoles.takes_camera").withStyle(ChatFormatting.AQUA));
        }
    }

    static void credit(String key, List<Component> lines) {
        lines.add(Component.translatable(key + ".tip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("realism.consoles.lookalike").withStyle(ChatFormatting.DARK_GRAY));
    }

    static class Tip extends Item {
        Tip(Properties props) {
            super(props);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            credit(getDescriptionId(), lines);
        }
    }

    // ---- Hybrid handhelds: play anywhere ----

    static class Handheld extends Item {
        final int model;

        Handheld(Properties props, int model) {
            super(props);
            this.model = model;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            if (p instanceof ServerPlayer sp) open(sp, NO_POS, model, false);
            return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), level.isClientSide);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            credit(getDescriptionId(), lines);
        }
    }
}
