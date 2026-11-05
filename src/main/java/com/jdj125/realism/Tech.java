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
 * The PC and the Laptop can play games for a mood boost. The AI Data Center eats RAM, GPUs and CPUs, and always wants
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

    public static final DeferredBlock<Block> PC_CASE = BLOCKS.register("pc_case",
            () -> new PcCase(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> TECH_COUNTER = BLOCKS.register("tech_counter",
            () -> new Block(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.WOOD)));
    public static final DeferredBlock<Block> DATA_CENTER = BLOCKS.register("data_center",
            () -> new DataCenter(BlockBehaviour.Properties.of().strength(4.0f).sound(SoundType.METAL).lightLevel(s -> 9).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> PC_CASE_ITEM = ITEMS.registerSimpleBlockItem(PC_CASE);
    public static final DeferredItem<BlockItem> TECH_COUNTER_ITEM = ITEMS.registerSimpleBlockItem(TECH_COUNTER);
    public static final DeferredItem<BlockItem> DATA_CENTER_ITEM = ITEMS.registerSimpleBlockItem(DATA_CENTER);

    public static final Supplier<BlockEntityType<PcCaseEntity>> PC_CASE_ENTITY = BLOCK_ENTITIES.register("pc_case",
            () -> BlockEntityType.Builder.of(PcCaseEntity::new, PC_CASE.get()).build(null));

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
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(LAPTOP.get());
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

    // ---- The PC ----

    public static class PcCaseEntity extends BlockEntity {
        /** Bit i set = Part.values()[i] installed. */
        public int parts;
        public int os, sagBoots;

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
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            parts = tag.getInt("parts");
            os = tag.getInt("os");
            sagBoots = tag.getInt("sagBoots");
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

        // Right-click with a part: install it.
        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, BlockHitResult hit) {
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
            if (!level.hasNeighborSignal(pos)) {
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
            PacketDistributor.sendToPlayer(sp, new OpenOs(pc.os));
            return InteractionResult.CONSUME;
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
            if (!level.isClientSide && player instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new OpenOs(1));
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

    public record OpenOs(int os) implements CustomPacketPayload {
        public static final Type<OpenOs> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "open_os"));
        public static final StreamCodec<ByteBuf, OpenOs> CODEC = ByteBufCodecs.VAR_INT.map(OpenOs::new, OpenOs::os);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
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
