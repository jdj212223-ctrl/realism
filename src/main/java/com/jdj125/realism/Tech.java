package com.jdj125.realism;

import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.mojang.serialization.MapCodec;

import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 5: tech. Buy parts from the Tech Dealer villager (job block: Tech Counter), install them one by one in a PC
 * Case by right-clicking it with each part, give it redstone power, and it boots into an operating system (sneak +
 * right-click with an empty hand switches OS). A GPU with no support bracket sags, and after a few boots it cracks.
 * Every computer has apps (Notes, Calculator, Terminal; Paint, Snake and Music from Software Discs) and saves your
 * notes and drawings. Ready-built look-alikes of Macs through the years (1984 to the Mini Desktop M6) just need power.
 * Games lift your mood. The AI Data Center eats RAM, GPUs and CPUs, and always wants
 * more (that's why GPUs and RAM cost so much).
 */
public final class Tech {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);
    private static final DeferredRegister<PoiType> POIS = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Realism.MODID);
    private static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, Realism.MODID);

    /** The parts a PC needs, in install order (the bracket is optional but saves your GPU). */
    public enum Part { MOTHERBOARD, CPU, RAM, GPU, STORAGE, POWER_SUPPLY, GPU_BRACKET }

    /** Apps a computer can run. The first three come with every computer; the rest are installed from Software Discs. */
    public enum App { NOTES, CALC, TERMINAL, PAINT, SNAKE, MUSIC }
    public static final int DEFAULT_APPS = (1 << App.NOTES.ordinal()) | (1 << App.CALC.ordinal()) | (1 << App.TERMINAL.ordinal());
    public static final int ALL_APPS = (1 << App.values().length) - 1;

    /**
     * Ready-built computers, look-alikes of real Macs through the years (original pixel art, no Apple logo, and named
     * by what they are; the tooltip says which real Mac inspired each). Each boots its era's OS style.
     */
    public record Model(String id, int os) {}
    public static final List<Model> MODELS = List.of(new Model("compact_1984", 0), new Model("color_classic_1993", 0),
            new Model("colorful_1998", 0), new Model("cube_2000", 1), new Model("lamp_2002", 1), new Model("tower_2006", 1),
            new Model("slim_2012", 1), new Model("mini_m1", 1), new Model("mini_m2", 1), new Model("mini_m3", 1),
            new Model("mini_m4", 1), new Model("mini_m5", 1), new Model("mini_m6", 1));

    /** The operating systems a PC can run: original look-alikes (no real logos or artwork). */
    public static final String[] OSES = {"classic_mac", "mac_x", "be", "next", "windows", "linux"};

    public static final DeferredItem<Item> MOTHERBOARD = part("motherboard", Part.MOTHERBOARD);
    public static final DeferredItem<Item> CPU = part("cpu", Part.CPU);
    public static final DeferredItem<Item> RAM = part("ram", Part.RAM);
    public static final DeferredItem<Item> GPU = part("gpu", Part.GPU);
    public static final DeferredItem<Item> STORAGE = part("ssd", Part.STORAGE);
    public static final DeferredItem<Item> POWER_SUPPLY = part("power_supply", Part.POWER_SUPPLY);
    public static final DeferredItem<Item> GPU_BRACKET = part("gpu_bracket", Part.GPU_BRACKET);
    public static final DeferredItem<Item> LAPTOP = ITEMS.register("laptop", () -> new Laptop(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> DISC_PAINT = disc("software_paint", App.PAINT);
    public static final DeferredItem<Item> DISC_SNAKE = disc("software_snake", App.SNAKE);
    public static final DeferredItem<Item> DISC_MUSIC = disc("software_music", App.MUSIC);

    public static final DeferredBlock<Block> PC_CASE = BLOCKS.register("pc_case",
            () -> new PcCase(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> TECH_COUNTER = BLOCKS.register("tech_counter",
            () -> new Block(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.WOOD)));
    public static final DeferredBlock<Block> DATA_CENTER = BLOCKS.register("data_center",
            () -> new DataCenter(BlockBehaviour.Properties.of().strength(4.0f).sound(SoundType.METAL).lightLevel(s -> 9).requiresCorrectToolForDrops()));
    public static final List<DeferredBlock<Block>> MODEL_BLOCKS = MODELS.stream().map(m -> BLOCKS.register(m.id(),
            () -> (Block) new Prebuilt(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).noOcclusion(), m.os()))).toList();
    public static final List<DeferredItem<BlockItem>> MODEL_ITEMS = MODEL_BLOCKS.stream().map(b -> ITEMS.register(b.getId().getPath(),
            () -> (BlockItem) new ModelItem(b.get(), new Item.Properties().stacksTo(1)))).toList();
    public static final DeferredItem<BlockItem> PC_CASE_ITEM = ITEMS.registerSimpleBlockItem(PC_CASE);
    public static final DeferredItem<BlockItem> TECH_COUNTER_ITEM = ITEMS.registerSimpleBlockItem(TECH_COUNTER);
    public static final DeferredItem<BlockItem> DATA_CENTER_ITEM = ITEMS.registerSimpleBlockItem(DATA_CENTER);

    public static final Supplier<BlockEntityType<PcCaseEntity>> PC_CASE_ENTITY = BLOCK_ENTITIES.register("pc_case",
            () -> BlockEntityType.Builder.of(PcCaseEntity::new, java.util.stream.Stream.concat(java.util.stream.Stream.of(PC_CASE.get()),
                    MODEL_BLOCKS.stream().map(DeferredBlock::get)).toArray(Block[]::new)).build(null));

    private static final ResourceKey<PoiType> DEALER_POI = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE,
            ResourceLocation.fromNamespaceAndPath(Realism.MODID, "tech_dealer"));
    public static final Supplier<PoiType> DEALER_POI_TYPE = POIS.register("tech_dealer",
            () -> new PoiType(ImmutableSet.copyOf(TECH_COUNTER.get().getStateDefinition().getPossibleStates()), 1, 1));
    public static final Supplier<VillagerProfession> TECH_DEALER = PROFESSIONS.register("tech_dealer",
            () -> new VillagerProfession("tech_dealer", h -> h.is(DEALER_POI), h -> h.is(DEALER_POI), ImmutableSet.of(), ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_TOOLSMITH));

    /** Boots a GPU survives with no support bracket before it cracks. */
    public static final int SAG_LIMIT = 3;

    private Tech() {
    }

    private static DeferredItem<Item> disc(String name, App app) {
        return ITEMS.register(name, () -> new SoftwareDisc(new Item.Properties().stacksTo(1), app));
    }

    private static DeferredItem<Item> part(String name, Part part) {
        return ITEMS.register(name, () -> new PartItem(new Item.Properties().stacksTo(16), part));
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        POIS.register(modBus);
        PROFESSIONS.register(modBus);
        modBus.addListener(Tech::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(PC_CASE_ITEM.get());
            event.accept(TECH_COUNTER_ITEM.get());
            event.accept(DATA_CENTER_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS)
            for (DeferredItem<Item> i : List.of(MOTHERBOARD, CPU, RAM, GPU, STORAGE, POWER_SUPPLY, GPU_BRACKET)) event.accept(i.get());
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(LAPTOP.get());
            for (DeferredItem<Item> d : List.of(DISC_PAINT, DISC_SNAKE, DISC_MUSIC)) event.accept(d.get());
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) for (DeferredItem<BlockItem> m : MODEL_ITEMS) event.accept(m.get());
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    // ---- Tech Dealer trades (GPUs and RAM are expensive: blame the AI) ----

    @SubscribeEvent
    public static void trades(VillagerTradesEvent event) {
        if (event.getType() != TECH_DEALER.get()) return;
        var t = event.getTrades();
        t.get(1).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 6), new ItemStack(RAM.get()), 12, 2, 0.05f));
        t.get(1).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 4), new ItemStack(STORAGE.get()), 12, 2, 0.05f));
        t.get(1).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(GPU_BRACKET.get()), 12, 2, 0.05f));
        t.get(2).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 6), new ItemStack(MOTHERBOARD.get()), 8, 5, 0.05f));
        t.get(2).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 5), new ItemStack(POWER_SUPPLY.get()), 8, 5, 0.05f));
        t.get(3).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 10), new ItemStack(CPU.get()), 6, 10, 0.05f));
        t.get(3).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 16), new ItemStack(GPU.get()), 4, 10, 0.05f));
        t.get(4).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 20), new ItemStack(LAPTOP.get()), 3, 15, 0.05f));
        t.get(1).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(DISC_PAINT.get()), 8, 2, 0.05f));
        t.get(2).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 4), new ItemStack(DISC_SNAKE.get()), 8, 5, 0.05f));
        t.get(2).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(DISC_MUSIC.get()), 8, 5, 0.05f));
        t.get(5).add((v, r) -> new MerchantOffer(new ItemCost(Items.EMERALD, 30), new ItemStack(MODEL_ITEMS.get(MODELS.size() - 3).get()), 2, 20, 0.05f));
    }

    // ---- Parts ----

    static class PartItem extends Item {
        final Part part;

        PartItem(Properties props, Part part) {
            super(props);
            this.part = part;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, net.minecraft.world.item.TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.part.tip"));
        }
    }

    // ---- Software ----

    public static class SoftwareDisc extends Item {
        public final App app;

        SoftwareDisc(Properties props, App app) {
            super(props);
            this.app = app;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, net.minecraft.world.item.TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.software.tip"));
        }
    }

    // ---- The PC ----

    public static class PcCaseEntity extends BlockEntity {
        /** Bit i set = Part.values()[i] installed. */
        public int parts;
        public int os, sagBoots;
        public int apps = DEFAULT_APPS;
        public String notes = "";
        /** 16x16 drawing from the Paint app: one palette index (0-7) per pixel. */
        public byte[] paint = new byte[256];

        public boolean hasApp(App a) {
            return (apps & (1 << a.ordinal())) != 0;
        }

        public PcCaseEntity(BlockPos pos, BlockState state) {
            super(PC_CASE_ENTITY.get(), pos, state);
        }

        public boolean has(Part p) {
            return (parts & (1 << p.ordinal())) != 0;
        }

        /** Everything but the bracket installed: the PC can boot. */
        public boolean complete() {
            for (Part p : Part.values()) if (p != Part.GPU_BRACKET && !has(p)) return false;
            return true;
        }

        /** Installs a part; returns false if it's already in. */
        public boolean install(Part p) {
            if (has(p)) return false;
            parts |= 1 << p.ordinal();
            if (p == Part.GPU_BRACKET) sagBoots = 0;
            setChanged();
            return true;
        }

        /**
         * One boot. Returns "ok", "sag" (no bracket, GPU sagging) or "cracked" (the GPU broke and was removed).
         */
        public String boot() {
            if (has(Part.GPU_BRACKET)) return "ok";
            sagBoots++;
            setChanged();
            if (sagBoots >= SAG_LIMIT) {
                parts &= ~(1 << Part.GPU.ordinal());
                sagBoots = 0;
                return "cracked";
            }
            return "sag";
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("parts", parts);
            tag.putInt("os", os);
            tag.putInt("sagBoots", sagBoots);
            tag.putInt("apps", apps);
            tag.putString("notes", notes);
            tag.putByteArray("paint", paint);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            parts = tag.getInt("parts");
            os = tag.getInt("os");
            sagBoots = tag.getInt("sagBoots");
            apps = tag.contains("apps") ? tag.getInt("apps") : DEFAULT_APPS;
            notes = tag.getString("notes");
            byte[] px = tag.getByteArray("paint");
            paint = px.length == 256 ? px : new byte[256];
        }
    }

    static class PcCase extends BaseEntityBlock {
        static final MapCodec<PcCase> CODEC = simpleCodec(PcCase::new);

        PcCase(Properties props) {
            super(props);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new PcCaseEntity(pos, state);
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return RenderShape.MODEL;
        }

        // Right-click with a part: install it. With a Software Disc: install that app.
        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, BlockHitResult hit) {
            if (stack.getItem() instanceof SoftwareDisc disc) {
                if (level.isClientSide) return ItemInteractionResult.SUCCESS;
                if (!(level.getBlockEntity(pos) instanceof PcCaseEntity pc)) return ItemInteractionResult.FAIL;
                if (pc.hasApp(disc.app)) {
                    say(player, "app.already");
                    return ItemInteractionResult.CONSUME;
                }
                pc.apps |= 1 << disc.app.ordinal();
                pc.setChanged();
                stack.consume(1, player);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.7f, 1.5f);
                say(player, "app.installed", Component.translatable("realism.app." + disc.app.name().toLowerCase()));
                return ItemInteractionResult.CONSUME;
            }
            if (!(stack.getItem() instanceof PartItem part)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            if (!(level.getBlockEntity(pos) instanceof PcCaseEntity pc)) return ItemInteractionResult.FAIL;
            if (!pc.install(part.part)) {
                say(player, "pc.already");
                return ItemInteractionResult.CONSUME;
            }
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8f, 1.4f);
            say(player, "pc.installed", Component.translatable(stack.getDescriptionId()));
            return ItemInteractionResult.CONSUME;
        }

        // Right-click with an empty hand: boot (or, sneaking, switch OS).
        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (!(level.getBlockEntity(pos) instanceof PcCaseEntity pc) || !(player instanceof ServerPlayer sp)) return InteractionResult.FAIL;
            if (player.isShiftKeyDown()) {
                pc.os = (pc.os + 1) % OSES.length;
                pc.setChanged();
                say(player, "pc.os", Component.translatable("realism.os." + OSES[pc.os]));
                return InteractionResult.CONSUME;
            }
            if (!pc.complete()) {
                StringBuilder missing = new StringBuilder();
                for (Part p : Part.values()) if (p != Part.GPU_BRACKET && !pc.has(p)) missing.append(missing.isEmpty() ? "" : ", ").append(p.name().toLowerCase());
                say(player, "pc.missing", missing.toString());
                return InteractionResult.CONSUME;
            }
            if (!level.hasNeighborSignal(pos) && !Power.powered(level, pos)) {
                say(player, "pc.no_power");
                return InteractionResult.CONSUME;
            }
            String result = pc.boot();
            if (result.equals("cracked")) {
                level.playSound(null, pos, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 1, 0.8f);
                say(player, "pc.cracked");
                return InteractionResult.CONSUME;
            }
            if (result.equals("sag")) player.displayClientMessage(Component.translatable("realism.pc.sag", SAG_LIMIT - pc.sagBoots), false);
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.7f, 1.6f);
            PacketDistributor.sendToPlayer(sp, new OpenComputer(pos, pc.os, pc.apps, pc.notes, pc.paint));
            return InteractionResult.CONSUME;
        }
    }

    /** A ready-built computer: every part already inside, it faces you, and it boots its era's OS. */
    static class Prebuilt extends PcCase {
        static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
                net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;
        private final int defaultOs;

        Prebuilt(Properties props, int defaultOs) {
            super(props);
            this.defaultOs = defaultOs;
            registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            PcCaseEntity pc = new PcCaseEntity(pos, state);
            pc.parts = (1 << Part.values().length) - 1;
            pc.os = defaultOs;
            return pc;
        }
    }

    /** Tooltip: the year and which real Mac inspired this look-alike. */
    static class ModelItem extends BlockItem {
        ModelItem(Block block, Properties props) {
            super(block, props);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, net.minecraft.world.item.TooltipFlag flag) {
            lines.add(Component.translatable(getDescriptionId() + ".tip"));
            lines.add(Component.translatable("item.realism.model.tip"));
        }
    }

    // ---- Laptop: a computer in your pocket ----

    static class Laptop extends Item {
        Laptop(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            // A laptop has every app, but keeps nothing (no saved notes or drawings): its position is "none".
            if (!level.isClientSide && player instanceof ServerPlayer sp)
                PacketDistributor.sendToPlayer(sp, new OpenComputer(NO_POS, 1, ALL_APPS, "", new byte[256]));
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
    }

    // ---- The AI Data Center: always hungry for more ----

    static class DataCenter extends Block {
        DataCenter(Properties props) {
            super(props);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, BlockHitResult hit) {
            if (!stack.is(RAM.get()) && !stack.is(GPU.get()) && !stack.is(CPU.get())) {
                if (!level.isClientSide) say(player, "datacenter.hungry");
                return ItemInteractionResult.CONSUME;
            }
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.GENERIC_EAT, SoundSource.BLOCKS, 1, 0.6f);
            say(player, "datacenter.ate");
            return ItemInteractionResult.CONSUME;
        }
    }

    // ---- Network: server tells a client to open the OS screen; the client asks to play a game ----

    /** Laptops have no block: this position means "don't save". */
    public static final BlockPos NO_POS = new BlockPos(0, -10000, 0);

    /** Server → client: open this computer's screen with its OS, installed apps, notes and drawing. */
    public record OpenComputer(BlockPos pos, int os, int apps, String notes, byte[] paint) implements CustomPacketPayload {
        public static final Type<OpenComputer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "open_computer"));
        public static final StreamCodec<ByteBuf, OpenComputer> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenComputer::pos, ByteBufCodecs.VAR_INT, OpenComputer::os, ByteBufCodecs.VAR_INT, OpenComputer::apps,
                ByteBufCodecs.stringUtf8(4096), OpenComputer::notes, ByteBufCodecs.byteArray(256), OpenComputer::paint, OpenComputer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: save the notes and drawing back into the computer. */
    public record SaveComputer(BlockPos pos, String notes, byte[] paint) implements CustomPacketPayload {
        public static final Type<SaveComputer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "save_computer"));
        public static final StreamCodec<ByteBuf, SaveComputer> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SaveComputer::pos, ByteBufCodecs.stringUtf8(4096), SaveComputer::notes,
                ByteBufCodecs.byteArray(256), SaveComputer::paint, SaveComputer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void save(ServerPlayer p, SaveComputer msg) {
        if (msg.pos().equals(NO_POS) || p.distanceToSqr(msg.pos().getCenter()) > 64) return;
        if (p.level().getBlockEntity(msg.pos()) instanceof PcCaseEntity pc && msg.paint().length == 256) {
            pc.notes = msg.notes().length() > 4000 ? msg.notes().substring(0, 4000) : msg.notes();
            pc.paint = msg.paint();
            pc.setChanged();
        }
    }

    public record PlayGame() implements CustomPacketPayload {
        public static final Type<PlayGame> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "play_game"));
        public static final StreamCodec<ByteBuf, PlayGame> CODEC = StreamCodec.unit(new PlayGame());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A game played on a PC or laptop: like the Game Console, +15 mood once a minute. */
    static void playGame(Player p) {
        Body b = BodyEvents.body(p);
        if (b.funCooldown > 0) {
            say(p, "console.again");
            return;
        }
        b.funCooldown = 60 * 20;
        Mind.lift(p, 15);
        say(p, "pc.game");
    }
}
