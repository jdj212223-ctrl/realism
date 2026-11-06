package com.jdj125.realism;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Phase 3: the hospital, plus fiberglass insulation.
 * <ul>
 * <li>Medical Station: the job block for the new Doctor villager, who sells bandages, tourniquets, syringes,
 * burn cream and splints.</li>
 * <li>Operating Table: right-click it with a Doctor within 8 blocks and 3 emeralds, and you get surgery: broken
 * legs, torn muscles, burns and frostbite fixed, bleeding stopped and half a liter of blood back.</li>
 * <li>Burn cream (heals 1st/2nd degree burns, halves a 3rd degree) and splints (a broken leg heals 3x faster).</li>
 * <li>Fiberglass insulation: warm pink fluff for walls that looks like cotton candy. Touching or breaking it
 * bare-handed makes you itch, breaking it with no helmet makes you cough, and trying to eat it is a very bad idea.</li>
 * </ul>
 */
public final class Hospital {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<PoiType> POIS = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Realism.MODID);
    private static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);

    public static final DeferredBlock<Block> MEDICAL_STATION = BLOCKS.register("medical_station",
            () -> new Block(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> OPERATING_TABLE = BLOCKS.register("operating_table",
            () -> new OperatingTable(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> FIBERGLASS = BLOCKS.register("fiberglass_insulation",
            () -> new Block(BlockBehaviour.Properties.of().strength(0.6f).sound(SoundType.WOOL).ignitedByLava()));

    /** 1.0.0: the patient monitor beside a hospital bed (needs electricity). */
    public static final DeferredBlock<Block> MONITOR = BLOCKS.register("patient_monitor",
            () -> new Monitor(BlockBehaviour.Properties.of().strength(2f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> MONITOR_ITEM = ITEMS.registerSimpleBlockItem(MONITOR);
    public static final Supplier<BlockEntityType<MonitorEntity>> MONITOR_ENTITY = BLOCK_ENTITIES.register("patient_monitor",
            () -> BlockEntityType.Builder.of(MonitorEntity::new, MONITOR.get()).build(null));
    /** Implant surgery (with the Chromeflesh mod) costs more than fixing bones. */
    public static final int IMPLANT_PRICE = 8;

    public static final DeferredItem<BlockItem> MEDICAL_STATION_ITEM = ITEMS.registerSimpleBlockItem(MEDICAL_STATION);
    public static final DeferredItem<BlockItem> OPERATING_TABLE_ITEM = ITEMS.registerSimpleBlockItem(OPERATING_TABLE);
    public static final DeferredItem<Item> FIBERGLASS_ITEM = ITEMS.register("fiberglass_insulation",
            () -> new FiberglassItem(FIBERGLASS.get(), new Item.Properties()));
    public static final DeferredItem<Item> BURN_CREAM = ITEMS.register("burn_cream", () -> new Treatment(new Item.Properties().stacksTo(16), true));
    public static final DeferredItem<Item> SPLINT = ITEMS.register("splint", () -> new Treatment(new Item.Properties().stacksTo(8), false));

    // Phase 4 (the mind) things live here too, so all the mod's blocks and items share one register.
    public static final DeferredBlock<Block> GAME_CONSOLE = BLOCKS.register("game_console",
            () -> new Mind.FunBlock(BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.STONE), "console"));
    public static final DeferredBlock<Block> TELEVISION = BLOCKS.register("television",
            () -> new Mind.FunBlock(BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.GLASS).lightLevel(s -> 7), "tv"));
    public static final DeferredItem<BlockItem> GAME_CONSOLE_ITEM = ITEMS.registerSimpleBlockItem(GAME_CONSOLE);
    public static final DeferredItem<BlockItem> TELEVISION_ITEM = ITEMS.registerSimpleBlockItem(TELEVISION);
    public static final DeferredItem<Item> CALENDAR = ITEMS.register("calendar", () -> new Mind.Paper(new Item.Properties().stacksTo(1), false));
    public static final DeferredItem<Item> THERAPY_VOUCHER = ITEMS.register("therapy_voucher", () -> new Mind.Paper(new Item.Properties().stacksTo(8), true));

    private static final ResourceKey<PoiType> DOCTOR_POI = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE,
            ResourceLocation.fromNamespaceAndPath(Realism.MODID, "doctor"));
    public static final Supplier<PoiType> DOCTOR_POI_TYPE = POIS.register("doctor",
            () -> new PoiType(ImmutableSet.copyOf(MEDICAL_STATION.get().getStateDefinition().getPossibleStates()), 1, 1));
    public static final Supplier<VillagerProfession> DOCTOR = PROFESSIONS.register("doctor",
            () -> new VillagerProfession("doctor", h -> h.is(DOCTOR_POI), h -> h.is(DOCTOR_POI), ImmutableSet.of(), ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_CLERIC));

    public static final int SURGERY_PRICE = 3;

    private Hospital() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        POIS.register(modBus);
        PROFESSIONS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(Hospital::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MEDICAL_STATION_ITEM.get());
            event.accept(OPERATING_TABLE_ITEM.get());
            event.accept(MONITOR_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) event.accept(FIBERGLASS_ITEM.get());
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(GAME_CONSOLE_ITEM.get());
            event.accept(TELEVISION_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CALENDAR.get());
            event.accept(THERAPY_VOUCHER.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(BURN_CREAM.get());
            event.accept(SPLINT.get());
        }
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    // ---- Doctor trades ----

    @SubscribeEvent
    public static void trades(VillagerTradesEvent event) {
        if (event.getType() != DOCTOR.get()) return;
        var t = event.getTrades();
        t.get(1).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(RealismItems.BANDAGE.get(), 4), 16, 2, 0.05f));
        t.get(1).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(BURN_CREAM.get(), 2), 12, 2, 0.05f));
        t.get(2).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(SPLINT.get()), 8, 5, 0.05f));
        t.get(2).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 3), new ItemStack(RealismItems.TOURNIQUET.get()), 8, 5, 0.05f));
        t.get(3).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(RealismItems.SYRINGE.get(), 3), 12, 10, 0.05f));
        t.get(3).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.WHITE_WOOL, 4), new ItemStack(Items.EMERALD), 16, 10, 0.05f));
        t.get(2).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 4), new ItemStack(THERAPY_VOUCHER.get()), 8, 5, 0.05f));
        // 1.0.0 medicine
        t.get(1).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Health.FLU_MEDICINE.get(), 2), 12, 2, 0.05f));
        t.get(2).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 4), new ItemStack(Health.ANTIBIOTICS.get(), 2), 8, 5, 0.05f));
        t.get(3).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 5), new ItemStack(Health.ANTI_RADIATION_PILLS.get(), 3), 8, 10, 0.05f));
        t.get(4).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 12), new ItemStack(Health.CHEMOTHERAPY.get()), 4, 15, 0.05f));
        t.get(4).add((trader, rand) -> new MerchantOffer(new ItemCost(Items.EMERALD, 30), new ItemStack(Ambulance.ITEM.get()), 1, 20, 0.05f));
    }

    // ---- Surgery ----

    /** Fixes every serious injury on this player: what a surgeon does on the Operating Table. */
    public static void operate(Player p) {
        Body b = BodyEvents.body(p);
        b.brokenLeg = 0;
        b.brokenNeck = false;
        b.paralysed = false;
        b.armTear = 0;
        b.badTear = false;
        b.burn = 0;
        b.burnDegree = 0;
        b.frostbite = 0;
        b.bleeding = 0;
        b.tourniquet = 0;
        b.blood = Math.min(Blood.FULL, b.blood + 0.5f);
        Health.operate(p);
        // Under anesthetic (a black screen for a few seconds), then waking up groggy.
        b.unconscious = 3 * 20;
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 0, true, false));
    }

    static boolean needsSurgery(Body b) {
        return b.infection > 0 || (b.cancerFound && b.cancer > 0 && b.cancer <= 2) || b.brokenNeck || b.brokenLeg > 0 || b.armTear > 0 || b.burn > 0 || b.frostbite > 0 || b.bleeding > 0 || b.blood < Blood.PALE;
    }

    static class OperatingTable extends Block {
        OperatingTable(Properties props) {
            super(props);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (Era.noMedicineYet(player)) return InteractionResult.CONSUME;
            Body b = BodyEvents.body(player);
            if (!needsSurgery(b)) {
                say(player, "surgery.healthy");
                return InteractionResult.CONSUME;
            }
            boolean doctor = !level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(8),
                    v -> v.getVillagerData().getProfession() == DOCTOR.get()).isEmpty();
            if (!doctor) {
                say(player, "surgery.no_doctor");
                return InteractionResult.CONSUME;
            }
            if (!player.isCreative()) {
                if (player.getInventory().countItem(Items.EMERALD) < SURGERY_PRICE) {
                    say(player, "surgery.price", SURGERY_PRICE);
                    return InteractionResult.CONSUME;
                }
                player.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), SURGERY_PRICE, player.inventoryMenu.getCraftSlots());
            }
            operate(player);
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.3f, 1.6f);
            say(player, "surgery.done");
            return InteractionResult.CONSUME;
        }

        // Holding a Chromeflesh implant: the Doctor puts it in properly (under anesthetic), instead of DIY surgery.
        @Override
        protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                                   InteractionHand hand, BlockHitResult hit) {
            if (!Chrome.isImplant(stack)) return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide) return net.minecraft.world.ItemInteractionResult.SUCCESS;
            if (Era.noMedicineYet(player)) return net.minecraft.world.ItemInteractionResult.CONSUME;
            boolean doctor = !level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(8),
                    v -> v.getVillagerData().getProfession() == DOCTOR.get()).isEmpty();
            if (!doctor) {
                say(player, "surgery.no_doctor");
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!player.isCreative() && player.getInventory().countItem(Items.EMERALD) < IMPLANT_PRICE) {
                say(player, "surgery.price", IMPLANT_PRICE);
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp) || !Chrome.install(sp, stack)) {
                say(player, "surgery.implant_failed");
                return net.minecraft.world.ItemInteractionResult.CONSUME;
            }
            if (!player.isCreative()) {
                player.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), IMPLANT_PRICE, player.inventoryMenu.getCraftSlots());
                stack.consume(1, player);
            }
            Body b = BodyEvents.body(player);
            b.unconscious = 4 * 20;
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false));
            // Every operation carries a small risk of infection, like in real life.
            if (player.getRandom().nextFloat() < 0.1f) Health.infect(player);
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.3f, 1.4f);
            say(player, "surgery.implant_done");
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
    }

    // ---- Patient monitor ----

    static class Monitor extends net.minecraft.world.level.block.HorizontalDirectionalBlock implements net.minecraft.world.level.block.EntityBlock {
        static final com.mojang.serialization.MapCodec<Monitor> CODEC = simpleCodec(Monitor::new);

        Monitor(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
        }

        @Override
        protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.HorizontalDirectionalBlock> codec() {
            return CODEC;
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
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new MonitorEntity(pos, state);
        }

        @Override
        public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
                Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide || type != MONITOR_ENTITY.get() ? null : (l, p, s, be) -> ((MonitorEntity) be).tick(l, p);
        }
    }

    /**
     * Watches the nearest player within 4 blocks: heart rate (faster when you've lost blood, are out of breath or have a
     * fever; a flat line when you've collapsed), blood and oxygen. Beeps with every heartbeat. Needs electricity.
     */
    public static class MonitorEntity extends net.minecraft.world.level.block.entity.BlockEntity {
        public boolean on;
        public String patient = "";
        public int bpm, oxygen;
        public float blood;
        private int nextBeat;

        public MonitorEntity(BlockPos pos, BlockState state) {
            super(MONITOR_ENTITY.get(), pos, state);
        }

        public static int heartRate(Player p) {
            Body b = BodyEvents.body(p);
            if (b.collapse > 0) return 0;
            float bpm = 72 + Math.max(0, Blood.FULL - b.blood) * 30 + (100 - b.stamina) * 0.4f;
            if (b.infection > 0) bpm += 20;
            if (b.flu > 0) bpm += 10;
            if (p.isSleeping()) bpm -= 12;
            return Math.round(Math.min(190, bpm));
        }

        void tick(Level level, BlockPos pos) {
            if (level.getGameTime() % 20 == 0) {
                boolean wasOn = on;
                String was = patient;
                int wasBpm = bpm;
                on = Power.powered(level, pos);
                Player p = on ? level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, e -> !((Player) e).isSpectator()) : null;
                patient = p == null ? "" : p.getGameProfile().getName();
                if (p != null) {
                    bpm = heartRate(p);
                    blood = BodyEvents.body(p).blood;
                    oxygen = Math.round(98 * Math.min(1f, p.getAirSupply() / (float) p.getMaxAirSupply()) * Math.min(1f, blood / 4f + 0.2f));
                }
                if (wasOn != on || !was.equals(patient) || wasBpm != bpm) {
                    setChanged();
                    level.sendBlockUpdated(pos, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (!on || patient.isEmpty()) return;
            if (bpm == 0) {
                if (level.getGameTime() % 10 == 0) level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.5f, 1.6f);
            } else if (--nextBeat <= 0) {
                nextBeat = Math.max(6, 1200 / bpm);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.35f, 2f);
            }
        }

        @Override
        protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            tag.putBoolean("on", on);
            tag.putString("patient", patient);
            tag.putInt("bpm", bpm);
            tag.putInt("oxygen", oxygen);
            tag.putFloat("blood", blood);
        }

        @Override
        protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            on = tag.getBoolean("on");
            patient = tag.getString("patient");
            bpm = tag.getInt("bpm");
            oxygen = tag.getInt("oxygen");
            blood = tag.getFloat("blood");
        }

        @Override
        public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider provider) {
            return saveWithoutMetadata(provider);
        }

        @Override
        public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
            return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
        }
    }

    // ---- Burn cream and splints ----

    static class Treatment extends Item {
        private final boolean cream;

        Treatment(Properties props, boolean cream) {
            super(props);
            this.cream = cream;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            Body b = BodyEvents.body(player);
            if (cream) {
                if (b.burn == 0) {
                    say(player, "cream.none");
                    return InteractionResultHolder.fail(stack);
                }
                if (b.burnDegree < 3) {
                    b.burn = 0;
                    b.burnDegree = 0;
                } else b.burn /= 2;
                say(player, b.burn == 0 ? "cream.healed" : "cream.helped");
            } else {
                if (b.brokenLeg == 0) {
                    say(player, "splint.none");
                    return InteractionResultHolder.fail(stack);
                }
                b.brokenLeg /= 3;
                say(player, "splint.done");
            }
            stack.consume(1, player);
            return InteractionResultHolder.consume(stack);
        }
    }

    // ---- Fiberglass ("the forbidden cotton candy") ----

    private static void itch(Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0, true, false, true));
        say(p, "fiberglass.itch");
    }

    private static void cough(Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 0, true, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 400, 0, true, false, true));
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 0.6f, 1.4f);
        say(p, "fiberglass.cough");
    }

    @SubscribeEvent
    public static void onTouch(PlayerInteractEvent.LeftClickBlock event) {
        Player p = event.getEntity();
        if (p.level().isClientSide || p.isCreative() || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        if (event.getLevel().getBlockState(event.getPos()).is(FIBERGLASS.get()) && p.getMainHandItem().isEmpty()) {
            itch(p);
            cut(p);
        }
    }

    /** 1.0.0: the glass fibres can cut bare hands: a little bleeding, and sometimes an infection. */
    static void cut(Player p) {
        if (p.getRandom().nextFloat() >= 0.25f) return;
        Body b = BodyEvents.body(p);
        if (RealismConfig.BLEEDING.get() && b.bleeding == 0) b.bleeding = 1;
        say(p, "fiberglass.cut");
        if (p.getRandom().nextFloat() < 0.2f) Health.infect(p);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (p.isCreative() || !event.getState().is(FIBERGLASS.get())) return;
        if (p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) cough(p);
        if (p.getMainHandItem().isEmpty()) cut(p);
    }

    static class FiberglassItem extends BlockItem {
        FiberglassItem(Block block, Properties props) {
            super(block, props);
        }

        // Right-clicking the air with it: you try to eat it. Don't.
        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            player.hurt(player.damageSources().generic(), 4);
            player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 1));
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
            cough(player);
            say(player, "fiberglass.eat");
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResultHolder.consume(stack);
        }
    }
}
