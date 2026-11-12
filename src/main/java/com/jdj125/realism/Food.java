package com.jdj125.realism;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Realism 3.3: fruit and throwing up.
 * <ul>
 * <li>Fruit: tangerines, oranges, bananas, plums... They drop from leaves now and then, depending on the climate
 * (tropical fruit in hot places, citrus in warm ones, pears and plums where it's cooler). Strawberries hide in grass.
 * Fruit lifts your mood a little.</li>
 * <li>Everyone has a favorite fruit: eating it makes you really happy. jdj125's is the tangerine, of course.</li>
 * <li>Throwing up: food poisoning, or spinning around until you're dizzy. You lose your food and feel awful for a bit.
 * Blocky and quick, nothing gross.</li>
 * </ul>
 */
public final class Food {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Realism.MODID);

    /** Every fruit: nutrition, saturation. */
    public static final Map<String, DeferredItem<Item>> FRUITS = new LinkedHashMap<>();

    static {
        fruit("tangerine", 3, 0.4f);
        fruit("orange", 4, 0.4f);
        fruit("lemon", 1, 0.2f);
        fruit("banana", 4, 0.5f);
        fruit("pear", 4, 0.3f);
        fruit("plum", 2, 0.3f);
        fruit("cherries", 2, 0.2f);
        fruit("peach", 3, 0.4f);
        fruit("strawberry", 2, 0.3f);
        fruit("grapes", 3, 0.3f);
        fruit("kiwi", 3, 0.4f);
        fruit("mango", 4, 0.5f);
        fruit("pineapple", 5, 0.5f);
    }

    private static void fruit(String name, int food, float saturation) {
        FRUITS.put(name, ITEMS.register(name, () -> new Item(new Item.Properties()
                .food(new FoodProperties.Builder().nutrition(food).saturationModifier(saturation).fast().build()))));
    }

    public static final Supplier<MapCodec<FruitDrops>> FRUIT_DROPS = LOOT.register("fruit_drops", () -> FruitDrops.CODEC);

    static final List<String> HOT = List.of("banana", "mango", "pineapple", "orange", "tangerine", "lemon"),
            WARM = List.of("tangerine", "orange", "lemon", "peach", "grapes", "kiwi", "cherries"),
            COOL = List.of("pear", "plum", "cherries", "grapes", "pear", "plum");

    private Food() {
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        LOOT.register(modBus);
    }

    public static boolean isFruit(ItemStack s) {
        return FRUITS.values().stream().anyMatch(f -> s.is(f.get()));
    }

    /** A fruit for leaves at this temperature (the biome's base temperature). */
    public static String fruitFor(float temperature, net.minecraft.util.RandomSource random) {
        List<String> pick = temperature >= 0.95f ? HOT : temperature >= 0.6f ? WARM : COOL;
        return pick.get(random.nextInt(pick.size()));
    }

    // ---- Favorite fruit ----

    public static boolean isJdj(Player p) {
        return p.getGameProfile().getName().equalsIgnoreCase("jdj125");
    }

    /** Everyone has one, the same every time. jdj125's is the tangerine. */
    public static String favorite(Player p) {
        if (p.getGameProfile().getName().equalsIgnoreCase("jdj125")) return "tangerine";
        List<String> names = List.copyOf(FRUITS.keySet());
        return names.get(Math.floorMod(p.getUUID().hashCode(), names.size()));
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !isFruit(event.getItem())) return;
        String fav = favorite(p);
        // jdj125's body hates fruit. Every fruit... except the tangerine: its sourness and sweetness are perfect.
        if (isJdj(p) && !event.getItem().is(FRUITS.get("tangerine").get())) {
            p.displayClientMessage(Component.translatable("realism.food.jdj_hates_fruit", event.getItem().getHoverName()).withStyle(ChatFormatting.GREEN), false);
            LAST.remove(p.getUUID()); // no cooldown: the body says NO every time
            vomit(p);
            return;
        }
        if (event.getItem().is(FRUITS.get(fav).get())) {
            Mind.lift(p, 20);
            p.displayClientMessage(Component.translatable(fav.equals("tangerine") && p.getGameProfile().getName().equalsIgnoreCase("jdj125")
                    ? "realism.food.tangerine_jdj" : "realism.food.favorite", Component.translatable("item.realism." + fav)).withStyle(ChatFormatting.GOLD), true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 1.6f);
        } else Mind.lift(p, 4);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p)
            p.displayClientMessage(Component.translatable("realism.food.your_favorite", Component.translatable("item.realism." + favorite(p)))
                    .withStyle(ChatFormatting.GOLD), false);
    }

    // ---- Throwing up ----

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, float[]> SPIN = new ConcurrentHashMap<>(); // last yaw, dizziness, dizzy seconds

    /** Throws up (at most once a minute). Returns whether it happened. */
    public static boolean vomit(ServerPlayer p) {
        if (!RealismConfig.VOMITING.get() || p.isSpectator() || p.gameMode.getGameModeForPlayer().isCreative()) return false;
        long now = p.serverLevel().getGameTime();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < 1200) return false;
        LAST.put(p.getUUID(), now);
        p.getFoodData().setFoodLevel(Math.max(0, p.getFoodData().getFoodLevel() - 4));
        p.getFoodData().setSaturation(0);
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 140, 0, true, false, true));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1, true, false, true));
        Vec3 at = p.getEyePosition().add(p.getLookAngle().multiply(0.6, 0, 0.6)).add(0, -0.3, 0);
        p.serverLevel().sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y, at.z, 14, 0.15, 0.1, 0.15, 0.05);
        p.level().playSound(null, p.blockPosition(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.PLAYERS, 0.8f, 0.6f);
        p.displayClientMessage(Component.translatable("realism.food.vomit").withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    /** Spinning around: dizziness builds up; keep spinning for long enough and you throw up. */
    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        float[] s = SPIN.computeIfAbsent(p.getUUID(), u -> new float[]{p.getYRot(), 0, 0});
        float turn = Math.abs(Mth.wrapDegrees(p.getYRot() - s[0]));
        s[0] = p.getYRot();
        s[1] = s[1] * 0.95f + turn; // ~20 x degrees per tick when spinning steadily
        if (p.tickCount % 20 != 0) return;
        if (s[1] > 400) { // over ~20 degrees every tick, for a while
            s[2]++;
            if (s[2] == 3) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false, true));
            if (s[2] >= 8) {
                s[2] = 0;
                vomit(p);
            }
        } else s[2] = Math.max(0, s[2] - 1);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SPIN.remove(event.getEntity().getUUID());
    }

    // ---- Fruit from leaves and grass ----

    /** Leaves sometimes drop a fruit that grows in that climate; grass sometimes hides a strawberry. */
    public static class FruitDrops extends LootModifier {
        public static final MapCodec<FruitDrops> CODEC = RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, FruitDrops::new));

        public FruitDrops(LootItemCondition[] conditions) {
            super(conditions);
        }

        @Override
        protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
            BlockState state = ctx.getParamOrNull(LootContextParams.BLOCK_STATE);
            Vec3 origin = ctx.getParamOrNull(LootContextParams.ORIGIN);
            if (state == null || origin == null || !RealismConfig.FRUITS.get()) return loot;
            ServerLevel level = ctx.getLevel();
            if (state.is(BlockTags.LEAVES) && ctx.getRandom().nextFloat() < 0.04f) {
                float t = level.getBiome(BlockPos.containing(origin)).value().getBaseTemperature();
                loot.add(new ItemStack(FRUITS.get(fruitFor(t, ctx.getRandom())).get()));
            } else if ((state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN)) && ctx.getRandom().nextFloat() < 0.015f) {
                loot.add(new ItemStack(FRUITS.get("strawberry").get()));
            }
            return loot;
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC;
        }
    }
}
