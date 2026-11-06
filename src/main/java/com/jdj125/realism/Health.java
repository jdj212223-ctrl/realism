package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * 1.0.0: radiation and diseases.
 * <ul>
 * <li><b>Radiation</b>: Uranium Ore (deep underground) and Raw Uranium give off radiation. The dose adds up in
 * sieverts (Sv): 1 Sv makes you feel sick, 2 Sv weak, 4 Sv starts hurting you, and 8 Sv is deadly. Away from it your
 * body slowly repairs. A Geiger counter clicks faster the more radiation there is. Anti-radiation pills halve what
 * you take in for 10 minutes. Radiation also raises the chance of cancer.</li>
 * <li><b>Flu</b>: catch it from the freezing cold, the rain, or a coughing friend nearby. Slow and tired for 2 days;
 * flu medicine makes it pass quickly. It never kills you.</li>
 * <li><b>Food poisoning</b>: rotten flesh, raw chicken and spider eyes. Sick to your stomach and thirsty for a
 * day; drinking water helps.</li>
 * <li><b>Infection</b>: a wound left bleeding for 2 minutes, zombie bites, fiberglass cuts. A fever that gets worse
 * for 2 days and then becomes deadly (sepsis). Antibiotics or surgery cure it.</li>
 * <li><b>Cancer</b>: rare, much likelier after radiation. It grows slowly through 4 stages (3 days each); you only
 * feel tired at first. A Medical Scanner finds it. Stages 1-2: surgery removes it. Later: chemotherapy, which often
 * pushes it back a stage (with side effects). Handled gently: no gross pictures, and there's always treatment.</li>
 * </ul>
 */
public final class Health {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredBlock<Block> URANIUM_ORE = BLOCKS.register("uranium_ore", () -> new DropExperienceBlock(UniformInt.of(2, 5),
            BlockBehaviour.Properties.of().strength(4.5f, 3f).sound(SoundType.DEEPSLATE).requiresCorrectToolForDrops().lightLevel(s -> 3)));
    public static final DeferredBlock<Block> MEDICAL_SCANNER = BLOCKS.register("medical_scanner",
            () -> new Scanner(BlockBehaviour.Properties.of().strength(3f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> URANIUM_ORE_ITEM = ITEMS.registerSimpleBlockItem(URANIUM_ORE);
    public static final DeferredItem<BlockItem> MEDICAL_SCANNER_ITEM = ITEMS.registerSimpleBlockItem(MEDICAL_SCANNER);
    public static final DeferredItem<Item> RAW_URANIUM = ITEMS.register("raw_uranium", () -> new Tip(new Item.Properties()));
    public static final DeferredItem<Item> GEIGER_COUNTER = ITEMS.register("geiger_counter", () -> new Tip(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> ANTI_RADIATION_PILLS = ITEMS.register("anti_radiation_pills", () -> new Medicine(new Item.Properties().stacksTo(16), Medicine.Kind.ANTI_RAD));
    public static final DeferredItem<Item> ANTIBIOTICS = ITEMS.register("antibiotics", () -> new Medicine(new Item.Properties().stacksTo(16), Medicine.Kind.ANTIBIOTICS));
    public static final DeferredItem<Item> FLU_MEDICINE = ITEMS.register("flu_medicine", () -> new Medicine(new Item.Properties().stacksTo(16), Medicine.Kind.FLU));
    public static final DeferredItem<Item> CHEMOTHERAPY = ITEMS.register("chemotherapy", () -> new Medicine(new Item.Properties().stacksTo(4), Medicine.Kind.CHEMO));

    /** Sieverts per second from one Uranium Ore block at distance d is ORE / (1 + d²); each Raw Uranium you carry adds CARRIED. */
    public static final float ORE = 0.0004f, CARRIED = 0.0005f, REPAIR = 0.0002f;
    public static final float SICK = 1, WEAK = 2, HURTING = 4, LETHAL = 8;

    private Health() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Health::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) event.accept(URANIUM_ORE_ITEM.get());
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(RAW_URANIUM.get());
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(MEDICAL_SCANNER_ITEM.get());
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(GEIGER_COUNTER.get());
            event.accept(ANTI_RADIATION_PILLS.get());
            event.accept(ANTIBIOTICS.get());
            event.accept(FLU_MEDICINE.get());
            event.accept(CHEMOTHERAPY.get());
        }
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    private static void keep(Player p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int amplifier) {
        p.addEffect(new MobEffectInstance(effect, 50, amplifier, true, false, true));
    }

    // ---- Radiation ----

    /** Sieverts per second reaching this player right now (before pills). */
    public static float radiationRate(Player p) {
        float rate = 0;
        BlockPos me = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(me.offset(-5, -5, -5), me.offset(5, 6, 5)))
            if (p.level().getBlockState(pos).is(URANIUM_ORE.get())) rate += ORE / (1 + (float) pos.distSqr(me));
        int carried = p.getInventory().countItem(RAW_URANIUM.get());
        rate += CARRIED * carried;
        return rate;
    }

    private static void radiation(ServerPlayer p, Body b) {
        float rate = radiationRate(p);
        if (b.antiRad > 0) {
            b.antiRad -= 20;
            rate /= 2;
        }
        b.rate = rate;
        b.dose += rate;
        // Radiation damages cells: the bigger the dose, the likelier cancer becomes.
        if (b.cancer == 0 && p.getRandom().nextFloat() < 1e-8f + b.dose * 1.5e-6f) b.cancer = 1;
        if (b.dose >= SICK && p.getRandom().nextInt(30) == 0) {
            p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false, true));
            say(p, "radiation.sick");
        }
        if (b.dose >= WEAK) {
            keep(p, MobEffects.WEAKNESS, 0);
            keep(p, MobEffects.HUNGER, 0);
        }
        if (b.dose >= LETHAL) p.hurt(Realism.source(p, Realism.RADIATION), Float.MAX_VALUE);
        else if (b.dose >= HURTING && p.tickCount % 400 < 20) p.hurt(Realism.source(p, Realism.RADIATION), 1);
        // Away from radiation the body slowly repairs itself.
        if (rate == 0) b.dose = Math.max(0, b.dose - REPAIR);
    }

    // ---- Illnesses ----

    /** One second of radiation and illness (called from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        if (!RealismConfig.DISEASES.get()) return;
        radiation(p, b);
        long day = RealismConfig.dayTicks();
        var rand = p.getRandom();

        // Flu: from the freezing cold, the rain without a hat, or someone coughing nearby.
        if (b.flu == 0) {
            float chance = 0;
            if (BodyEvents.coldSweatBody(p) < -50) chance += 0.0005f;
            if (p.isInWaterOrRain() && !p.isInWater() && p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) chance += 0.0002f;
            if (!p.level().getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(4), o -> o != p && BodyEvents.body(o).flu > 0).isEmpty()) chance += 0.002f;
            if (rand.nextFloat() < chance) {
                b.flu = (int) Math.min(Integer.MAX_VALUE, day * 2);
                say(p, "flu.caught");
            }
        } else {
            b.flu = Math.max(0, b.flu - 20);
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
            b.stamina = Math.max(0, b.stamina - 0.1f);
            if (rand.nextInt(25) == 0) p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 0.5f, 1.5f);
            if (b.flu == 0) say(p, "flu.better");
        }

        if (b.foodPoisoning > 0) {
            b.foodPoisoning = Math.max(0, b.foodPoisoning - 20);
            b.thirst += 20; // throwing up: you get thirsty twice as fast
            keep(p, MobEffects.HUNGER, 0);
            if (rand.nextInt(40) == 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false, true));
            if (b.foodPoisoning == 0) say(p, "food_poisoning.better");
        }

        // A wound left bleeding too long gets infected.
        if (b.bleeding > 0) {
            b.untreatedBleed += 20;
            if (b.untreatedBleed >= 120 * 20 && b.infection == 0) infect(p);
        } else b.untreatedBleed = 0;
        if (b.infection > 0) {
            b.infection += 20;
            float t = b.infection / (float) day;
            if (t > 0.5f) keep(p, MobEffects.WEAKNESS, 0);
            if (t > 1) keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
            if (t > 2) p.hurt(Realism.source(p, Realism.INFECTION), Float.MAX_VALUE); // sepsis
        }

        // Cancer grows slowly, a stage every 3 days.
        if (b.cancer > 0) {
            b.cancerTime += 20;
            if (b.cancerTime >= day * 3) {
                b.cancerTime = 0;
                if (b.cancer < 4) {
                    b.cancer++;
                    if (b.cancer == 2) p.displayClientMessage(Component.translatable("realism.cancer.tired"), false);
                } else p.hurt(Realism.source(p, Realism.CANCER), Float.MAX_VALUE);
            }
            if (b.cancer >= 2) b.stamina = Math.max(0, b.stamina - 0.05f);
            if (b.cancer >= 3) keep(p, MobEffects.WEAKNESS, b.cancer - 3);
            if (b.cancer >= 4) keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
        }
    }

    public static void infect(Player p) {
        Body b = BodyEvents.body(p);
        if (b.infection > 0 || !RealismConfig.DISEASES.get()) return;
        b.infection = 1;
        say(p, "infection.start");
    }

    // Some food makes you sick; water helps your stomach.
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide || !RealismConfig.DISEASES.get()) return;
        ItemStack s = event.getItem();
        float chance = s.is(Items.ROTTEN_FLESH) ? 0.7f : s.is(Items.CHICKEN) ? 0.3f : s.is(Items.SPIDER_EYE) ? 1 : s.is(Items.PUFFERFISH) ? 1 : 0;
        Body b = BodyEvents.body(p);
        if (chance > 0 && p.getRandom().nextFloat() < chance && b.foodPoisoning == 0) {
            b.foodPoisoning = (int) RealismConfig.dayTicks();
            say(p, "food_poisoning.start");
        }
        if (s.is(Items.POTION) && b.foodPoisoning > 0) b.foodPoisoning = b.foodPoisoning * 3 / 4;
    }

    // Zombie bites can get infected.
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getSource().getEntity() instanceof Zombie && p.getRandom().nextFloat() < 0.1f) infect(p);
    }

    /** Surgery also cures infections and removes cancer that was found early (stage 1-2). */
    static void operate(Player p) {
        Body b = BodyEvents.body(p);
        b.infection = 0;
        b.untreatedBleed = 0;
        if (b.cancer > 0 && b.cancer <= 2 && b.cancerFound) {
            b.cancer = 0;
            b.cancerTime = 0;
            b.cancerFound = false;
            p.displayClientMessage(Component.translatable("realism.cancer.removed"), false);
        }
    }

    public static LifeSync sync(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        Era.Data era = Era.Data.get(p.getServer());
        int flags = (era.chosen ? LifeSync.CHOSEN : 0) | (b.flu > 0 ? LifeSync.FLU : 0) | (b.foodPoisoning > 0 ? LifeSync.FOOD_POISONING : 0)
                | (b.infection > 0 ? LifeSync.INFECTION : 0) | (b.cancer > 0 && b.cancerFound ? LifeSync.CANCER_FOUND | (b.cancer << 8) : 0)
                | (Justice.jailed(p) ? LifeSync.JAILED : 0) | (b.dose >= SICK ? LifeSync.RADIATION_SICK : 0)
                | (b.sentenced > 0 ? LifeSync.SENTENCED : 0);
        return new LifeSync(era.year, flags, b.dose, b.rate);
    }

    // ---- Items and the scanner ----

    static class Tip extends Item {
        Tip(Properties props) {
            super(props);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable(getDescriptionId() + ".tip"));
        }
    }

    static class Medicine extends Tip {
        enum Kind { ANTI_RAD, ANTIBIOTICS, FLU, CHEMO }

        private final Kind kind;

        Medicine(Properties props, Kind kind) {
            super(props);
            this.kind = kind;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            ItemStack stack = p.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            if (kind != Kind.FLU && Era.noMedicineYet(p)) return InteractionResultHolder.fail(stack);
            Body b = BodyEvents.body(p);
            switch (kind) {
                case ANTI_RAD -> {
                    b.antiRad = 10 * 60 * 20;
                    say(p, "pills.anti_rad");
                }
                case ANTIBIOTICS -> {
                    if (b.infection == 0) {
                        say(p, "pills.no_infection");
                        return InteractionResultHolder.fail(stack);
                    }
                    b.infection = 0;
                    say(p, "pills.antibiotics");
                }
                case FLU -> {
                    if (b.flu == 0) {
                        say(p, "pills.no_flu");
                        return InteractionResultHolder.fail(stack);
                    }
                    b.flu = Math.min(b.flu, 5 * 60 * 20);
                    say(p, "pills.flu");
                }
                case CHEMO -> {
                    if (b.cancer == 0) {
                        say(p, "chemo.none");
                        return InteractionResultHolder.fail(stack);
                    }
                    // Like real chemotherapy: often works, makes you feel rough for a while.
                    p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 600, 0, true, false, true));
                    p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6000, 0, true, false, true));
                    p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 1200, 0, true, false, true));
                    if (p.getRandom().nextFloat() < 0.7f) {
                        b.cancer--;
                        b.cancerTime = 0;
                        p.displayClientMessage(Component.translatable(b.cancer == 0 ? "realism.chemo.cured" : "realism.chemo.worked"), false);
                        if (b.cancer == 0) b.cancerFound = false;
                    } else p.displayClientMessage(Component.translatable("realism.chemo.again"), false);
                }
            }
            p.level().playSound(null, p.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.6f, 1.4f);
            stack.consume(1, p);
            return InteractionResultHolder.consume(stack);
        }
    }

    /** Medical Scanner: needs electricity. Right-click for a full check-up; it finds cancer early. */
    static class Scanner extends Block {
        Scanner(Properties props) {
            super(props);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (!Power.powered(level, pos)) {
                say(p, "scanner.no_power");
                return InteractionResult.CONSUME;
            }
            if (Era.noMedicineYet(p)) return InteractionResult.CONSUME;
            Body b = BodyEvents.body(p);
            level.playSound(null, pos, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1f, 1.5f);
            p.displayClientMessage(Component.translatable("realism.scanner.title"), false);
            p.displayClientMessage(Component.translatable("realism.scanner.blood", String.format(java.util.Locale.ROOT, "%.1f", b.blood),
                    p.getData(Realism.BLOOD_TYPE)), false);
            p.displayClientMessage(Component.translatable("realism.scanner.dose", String.format(java.util.Locale.ROOT, "%.2f", b.dose)), false);
            if (b.flu > 0) p.displayClientMessage(Component.translatable("realism.scanner.flu"), false);
            if (b.foodPoisoning > 0) p.displayClientMessage(Component.translatable("realism.scanner.food"), false);
            if (b.infection > 0) p.displayClientMessage(Component.translatable("realism.scanner.infection"), false);
            if (b.cancer > 0) {
                b.cancerFound = true;
                p.displayClientMessage(Component.translatable("realism.scanner.cancer", b.cancer), false);
                p.displayClientMessage(Component.translatable(b.cancer <= 2 ? "realism.scanner.cancer_surgery" : "realism.scanner.cancer_chemo"), false);
            }
            if (b.flu == 0 && b.foodPoisoning == 0 && b.infection == 0 && b.cancer == 0 && b.dose < SICK && !Hospital.needsSurgery(b))
                p.displayClientMessage(Component.translatable("realism.scanner.healthy"), false);
            return InteractionResult.CONSUME;
        }
    }

    /** For the GameTests and /realism: true if this block is radioactive. */
    public static boolean radioactive(BlockState s) {
        return s.is(URANIUM_ORE.get());
    }

    static boolean doctorNear(Level level, BlockPos pos) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, new AABB(pos).inflate(8),
                v -> v.getVillagerData().getProfession() == Hospital.DOCTOR.get()).isEmpty();
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
