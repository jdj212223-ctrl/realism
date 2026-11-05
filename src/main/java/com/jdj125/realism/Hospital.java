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

    public static final DeferredBlock<Block> MEDICAL_STATION = BLOCKS.register("medical_station",
            () -> new Block(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> OPERATING_TABLE = BLOCKS.register("operating_table",
            () -> new OperatingTable(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> FIBERGLASS = BLOCKS.register("fiberglass_insulation",
            () -> new Block(BlockBehaviour.Properties.of().strength(0.6f).sound(SoundType.WOOL).ignitedByLava()));

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
        modBus.addListener(Hospital::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MEDICAL_STATION_ITEM.get());
            event.accept(OPERATING_TABLE_ITEM.get());
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
        // Under anesthetic (a black screen for a few seconds), then waking up groggy.
        b.unconscious = 3 * 20;
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 0, true, false));
    }

    static boolean needsSurgery(Body b) {
        return b.brokenNeck || b.brokenLeg > 0 || b.armTear > 0 || b.burn > 0 || b.frostbite > 0 || b.bleeding > 0 || b.blood < Blood.PALE;
    }

    static class OperatingTable extends Block {
        OperatingTable(Properties props) {
            super(props);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
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
        if (event.getLevel().getBlockState(event.getPos()).is(FIBERGLASS.get()) && p.getMainHandItem().isEmpty()) itch(p);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (p.isCreative() || !event.getState().is(FIBERGLASS.get())) return;
        if (p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) cough(p);
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
