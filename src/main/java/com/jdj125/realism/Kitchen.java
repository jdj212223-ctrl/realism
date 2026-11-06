package com.jdj125.realism;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 1.2.0: knives and Czech food.
 * <ul>
 * <li><b>Knives</b>: in ancient times there are swords; from 1900 (modern times) swords become knives of the same
 * material (keeping their enchantments and wear). Knives are quicker but weaker, often make people bleed, and cut
 * food: hold the knife, the food in your other hand, and right-click.</li>
 * <li><b>Czech food</b>, cooked on Grandma's Stove: houskový knedlík (bread dumplings, cut into slices), svíčková,
 * guláš, řízek, smažený sýr, bramboráky, kulajda, chlebíčky, koláče, buchty, rohlíky and párek v rohlíku. Czech home
 * cooking cheers you up.</li>
 * </ul>
 */
public final class Kitchen {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    // ---- Knives ----
    public static final DeferredItem<Item> WOODEN_KNIFE = knife("wooden_knife", Tiers.WOOD);
    public static final DeferredItem<Item> STONE_KNIFE = knife("stone_knife", Tiers.STONE);
    public static final DeferredItem<Item> IRON_KNIFE = knife("iron_knife", Tiers.IRON);
    public static final DeferredItem<Item> GOLDEN_KNIFE = knife("golden_knife", Tiers.GOLD);
    public static final DeferredItem<Item> DIAMOND_KNIFE = knife("diamond_knife", Tiers.DIAMOND);
    public static final DeferredItem<Item> NETHERITE_KNIFE = knife("netherite_knife", Tiers.NETHERITE);

    private static DeferredItem<Item> knife(String name, Tier tier) {
        Item.Properties props = new Item.Properties().attributes(SwordItem.createAttributes(tier, 1, -1.8f));
        if (tier == Tiers.NETHERITE) props = props.fireResistant();
        Item.Properties p = props;
        return ITEMS.register(name, () -> new Knife(tier, p));
    }

    /** The sword each knife replaces in modern times. */
    static Map<Item, Supplier<Item>> swordToKnife() {
        Map<Item, Supplier<Item>> m = new LinkedHashMap<>();
        m.put(Items.WOODEN_SWORD, WOODEN_KNIFE);
        m.put(Items.STONE_SWORD, STONE_KNIFE);
        m.put(Items.IRON_SWORD, IRON_KNIFE);
        m.put(Items.GOLDEN_SWORD, GOLDEN_KNIFE);
        m.put(Items.DIAMOND_SWORD, DIAMOND_KNIFE);
        m.put(Items.NETHERITE_SWORD, NETHERITE_KNIFE);
        return m;
    }

    // ---- Czech food ----
    public static final DeferredItem<Item> CHEESE = food("cheese", 3, 0.4f, false);
    public static final DeferredItem<Item> BREAD_SLICE = food("bread_slice", 1, 0.5f, false);
    public static final DeferredItem<Item> DUMPLING_DOUGH = food("dumpling_dough", 1, 0.1f, false);
    public static final DeferredItem<Item> BREAD_DUMPLING = food("bread_dumpling", 6, 0.6f, false);
    public static final DeferredItem<Item> DUMPLING_SLICE = food("dumpling_slice", 2, 0.5f, false);
    public static final DeferredItem<Item> SVICKOVA = food("svickova", 12, 0.9f, true);
    public static final DeferredItem<Item> GULAS = food("gulas", 10, 0.8f, true);
    public static final DeferredItem<Item> RAW_SCHNITZEL = food("raw_schnitzel", 2, 0.2f, false);
    public static final DeferredItem<Item> SCHNITZEL = food("schnitzel", 8, 0.8f, false);
    public static final DeferredItem<Item> RAW_FRIED_CHEESE = food("raw_fried_cheese", 2, 0.2f, false);
    public static final DeferredItem<Item> FRIED_CHEESE = food("fried_cheese", 7, 0.7f, false);
    public static final DeferredItem<Item> POTATO_BATTER = food("potato_batter", 1, 0.1f, false);
    public static final DeferredItem<Item> BRAMBORAK = food("bramborak", 5, 0.6f, false);
    public static final DeferredItem<Item> KULAJDA = food("kulajda", 8, 0.7f, true);
    public static final DeferredItem<Item> CHLEBICEK = food("chlebicek", 5, 0.6f, false);
    public static final DeferredItem<Item> KOLAC = food("kolac", 4, 0.5f, false);
    public static final DeferredItem<Item> BUCHTA = food("buchta", 4, 0.5f, false);
    public static final DeferredItem<Item> ROHLIK_DOUGH = food("rohlik_dough", 1, 0.1f, false);
    public static final DeferredItem<Item> ROHLIK = food("rohlik", 3, 0.5f, false);
    public static final DeferredItem<Item> PAREK = food("parek", 2, 0.4f, false);
    public static final DeferredItem<Item> PAREK_V_ROHLIKU = food("parek_v_rohliku", 7, 0.7f, false);

    /** Proper Czech meals (not dough or slices): eating one lifts your mood. */
    static final List<DeferredItem<Item>> MEALS = List.of(SVICKOVA, GULAS, SCHNITZEL, FRIED_CHEESE, BRAMBORAK, KULAJDA, CHLEBICEK, KOLAC, BUCHTA, PAREK_V_ROHLIKU);

    private static DeferredItem<Item> food(String name, int nutrition, float saturation, boolean bowl) {
        return ITEMS.register(name, () -> {
            FoodProperties.Builder f = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation);
            if (bowl) f.usingConvertsTo(Items.BOWL);
            return new Item(new Item.Properties().food(f.build()).stacksTo(bowl ? 16 : 64));
        });
    }

    /** What a knife cuts each food into. */
    public record Cut(Supplier<Item> into, int count) {
    }

    static Map<Item, Cut> cuts() {
        Map<Item, Cut> m = new LinkedHashMap<>();
        m.put(Items.BREAD, new Cut(BREAD_SLICE, 4));
        m.put(BREAD_DUMPLING.get(), new Cut(DUMPLING_SLICE, 6));
        m.put(Items.COOKED_PORKCHOP, new Cut(PAREK, 2));
        m.put(Items.MELON, new Cut(() -> Items.MELON_SLICE, 9));
        m.put(Items.PUMPKIN, new Cut(() -> Items.PUMPKIN_SEEDS, 4));
        return m;
    }

    private Kitchen() {
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(Kitchen::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT)
            for (var k : List.of(WOODEN_KNIFE, STONE_KNIFE, IRON_KNIFE, GOLDEN_KNIFE, DIAMOND_KNIFE, NETHERITE_KNIFE)) event.accept(k.get());
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS)
            for (var f : List.of(CHEESE, BREAD_SLICE, DUMPLING_DOUGH, BREAD_DUMPLING, DUMPLING_SLICE, SVICKOVA, GULAS, RAW_SCHNITZEL, SCHNITZEL,
                    RAW_FRIED_CHEESE, FRIED_CHEESE, POTATO_BATTER, BRAMBORAK, KULAJDA, CHLEBICEK, KOLAC, BUCHTA, ROHLIK_DOUGH, ROHLIK, PAREK, PAREK_V_ROHLIKU))
                event.accept(f.get());
    }

    // ---- Knives: cutting food ----

    static class Knife extends SwordItem {
        Knife(Tier tier, Properties props) {
            super(tier, props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            ItemStack knife = p.getItemInHand(hand);
            InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack food = p.getItemInHand(other);
            Cut cut = cuts().get(food.getItem());
            if (cut == null) return InteractionResultHolder.pass(knife);
            if (level.isClientSide) return InteractionResultHolder.success(knife);
            food.shrink(1);
            ItemStack pieces = new ItemStack(cut.into().get(), cut.count());
            if (!p.getInventory().add(pieces)) p.drop(pieces, false);
            knife.hurtAndBreak(1, p, hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            level.playSound(null, p.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 0.6f, 1.3f);
            p.getCooldowns().addCooldown(this, 8);
            return InteractionResultHolder.consume(knife);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.knife.tip"));
        }
    }

    public static boolean isKnife(ItemStack s) {
        return s.getItem() instanceof Knife;
    }

    /** Swords become knives in modern times (from 1900, in worlds that picked an era). */
    public static boolean modernKnives(Player p) {
        return RealismConfig.MODERN_KNIVES.get() && p.getServer() != null && Era.Data.get(p.getServer()).chosen && Era.year(p) >= Era.MEDICINE;
    }

    /** Turns every sword in this player's inventory into the knife of the same material, keeping enchantments and wear. */
    public static int swapSwords(Player p) {
        Map<Item, Supplier<Item>> map = swordToKnife();
        var inv = p.getInventory();
        int swapped = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            Supplier<Item> knife = map.get(s.getItem());
            if (knife == null) continue;
            inv.setItem(i, new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(knife.get()), s.getCount(), s.getComponentsPatch()));
            swapped++;
        }
        return swapped;
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 20 == 7 && modernKnives(p) && swapSwords(p) > 0)
            p.displayClientMessage(Component.translatable("realism.knife.modern"), true);
    }

    // A knife wound often bleeds.
    @SubscribeEvent
    public static void onHit(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof Player victim && !victim.level().isClientSide && RealismConfig.BLEEDING.get()
                && event.getSource().getEntity() instanceof Player attacker && isKnife(attacker.getMainHandItem())
                && victim.getRandom().nextFloat() < 0.4f) Blood.bleed(victim, 2);
    }

    // Czech home cooking cheers you up.
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide) return;
        for (var meal : MEALS)
            if (event.getItem().is(meal.get())) {
                Mind.lift(p, 4);
                p.displayClientMessage(Component.translatable("realism.food.dobrou_chut"), true);
                return;
            }
    }
}
