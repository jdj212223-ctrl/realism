package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.Tags;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realism 4.13: the cold house. A house is only warm if something keeps it warm. Inside a house (a roof over your
 * head and house things around: doors, windows, beds, planks) with nothing insulating it, the weather outside creeps
 * in, slowly, the realistic way:
 * <ul>
 * <li>Cold: you get chilly (mood drops, stamina drains), then you shiver (stiff fingers, slow), then you catch a cold.
 * Sleeping in a freezing house is bad sleep.</li>
 * <li>Hot: a stuffy house makes you thirsty, then sweaty and tired, then sick from the heat.</li>
 * </ul>
 * What insulates a house: a Cold Sweat Hearth, Boiler or Icebox (their warmth/chill reaches you), Realism heating
 * (a burning Coal Heater, Fireplace, furnace or campfire, a powered Panel Radiator) and Realism Fiberglass Insulation in
 * the walls (it doesn't make heat, but it keeps most of the weather out). Leather clothes help with the cold.
 * <p>
 * Cold Sweat is optional. With it, the outside temperature is Cold Sweat's world temperature compared with its comfy
 * range. Without it, Realism uses the biome's climate (colder at night and up high).
 */
public final class ColdHouse {
    private ColdHouse() {
    }

    /** For tests: the outside weather (+1 = freezing, -1 = scorching, 0 = mild); null = the real weather. */
    public static Double testWeather;

    private static final Map<UUID, Integer> STAGE = new ConcurrentHashMap<>();

    static void say(ServerPlayer p, String key, ChatFormatting color) {
        p.displayClientMessage(Component.translatable("realism.coldhouse." + key).withStyle(color), true);
    }

    /** One second in (or out of) a house. Called from {@link BodyEvents#second}. */
    public static void second(ServerPlayer p, Body b) {
        if (!RealLife.on(RealismConfig.COLD_HOUSE) || p.getAbilities().instabuild || p.isSpectator()) {
            b.houseChill = 0;
            return;
        }
        double weather = weather(p);
        boolean inside = inHouse(p);
        float rate = 0;
        if (inside && Math.abs(weather) > 0.05) {
            boolean cold = weather > 0;
            Insulation ins = insulation(p, cold);
            if (ins != Insulation.HEATED) {
                rate = (float) (Math.abs(weather) * 0.35);
                if (ins == Insulation.WALLS) rate *= 0.3f;
                if (cold && Wave2.warmClothes(p)) rate *= 0.5f;
                if (!cold) rate = -rate;
            }
        }
        if (rate != 0) b.houseChill = Math.max(-100, Math.min(100, b.houseChill + rate));
        else b.houseChill = b.houseChill > 0 ? Math.max(0, b.houseChill - 1) : Math.min(0, b.houseChill + 1);
        effects(p, b, inside);
    }

    private static void effects(ServerPlayer p, Body b, boolean inside) {
        float c = b.houseChill;
        boolean cold = c > 0;
        float a = Math.abs(c);
        int stage = a >= 80 ? 3 : a >= 50 ? 2 : a >= 25 ? 1 : 0;
        int old = STAGE.getOrDefault(p.getUUID(), 0);
        int signed = cold ? stage : -stage;
        if (stage > Math.abs(old) || (stage > 0 && Integer.signum(old) != Integer.signum(signed)))
            say(p, (cold ? "cold." : "hot.") + stage, cold ? ChatFormatting.AQUA : ChatFormatting.GOLD);
        else if (stage == 0 && Math.abs(old) >= 1 && inside) say(p, old > 0 ? "cozy" : "cool_again", ChatFormatting.GREEN);
        STAGE.put(p.getUUID(), signed);
        if (stage == 0) return;

        var r = p.getRandom();
        if (cold) {
            if (RealismConfig.MOOD.get()) Mind.lift(p, -0.02f * stage);
            b.stamina = Math.max(0, b.stamina - 0.05f * stage);
            if (stage >= 2) {
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 50, 0, true, false, true));
                if (!Wave2.warmClothes(p)) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 0, true, false, true));
                if (r.nextInt(6) == 0) {
                    p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.25f, 1.6f);
                    ((ServerLevel) p.level()).sendParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getEyeY(), p.getZ(), 3, 0.2, 0.1, 0.2, 0.01);
                }
            }
            // A freezing house gives you a cold (Realism's flu), unless you already have one.
            if (stage >= 3 && RealismConfig.DISEASES.get() && b.flu == 0 && r.nextFloat() < 0.004f) {
                b.flu = (int) Math.min(Integer.MAX_VALUE, RealismConfig.dayTicks() * 2);
                say(p, "caught_cold", ChatFormatting.AQUA);
            }
        } else {
            b.thirst += 10L * stage; // sweating it out
            b.stamina = Math.max(0, b.stamina - 0.05f * stage);
            if (RealismConfig.MOOD.get()) Mind.lift(p, -0.01f * stage);
            if (stage >= 2 && r.nextInt(8) == 0)
                ((ServerLevel) p.level()).sendParticles(ParticleTypes.FALLING_WATER, p.getX(), p.getEyeY(), p.getZ(), 2, 0.2, 0.1, 0.2, 0);
            if (stage >= 3) {
                p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 50, 0, true, false, true));
                if (r.nextInt(60) == 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false, true));
            }
        }
    }

    /** After a night's sleep: a freezing or baking house means you slept badly. */
    static void wokeUp(ServerPlayer p, Body b) {
        if (!RealLife.on(RealismConfig.COLD_HOUSE) || Math.abs(b.houseChill) < 40) return;
        boolean cold = b.houseChill > 0;
        Mind.lift(p, -15);
        // Not really rested: you wake up as if you'd missed part of the night.
        b.awake = (long) (RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks() * 0.15);
        p.displayClientMessage(Component.translatable(cold ? "realism.coldhouse.bad_sleep_cold" : "realism.coldhouse.bad_sleep_hot")
                .withStyle(cold ? ChatFormatting.AQUA : ChatFormatting.GOLD), false);
    }

    // ---- Is this a house? ----

    /** A roof over your head and house things around you (a cave isn't a house). */
    public static boolean inHouse(ServerPlayer p) {
        Level level = p.level();
        BlockPos eyes = BlockPos.containing(p.getEyePosition());
        // A roof: some solid block above your eyes (the heightmap, not the sky light, which updates late).
        if (eyes.getY() >= level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, eyes.getX(), eyes.getZ())) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -2; dy <= 4; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockState s = level.getBlockState(m.setWithOffset(eyes, dx, dy, dz));
                    if (s.is(BlockTags.DOORS) || s.is(BlockTags.BEDS) || s.is(BlockTags.PLANKS) || s.is(Tags.Blocks.GLASS_BLOCKS)
                            || s.is(Tags.Blocks.GLASS_PANES)) return true;
                }
        return false;
    }

    // ---- What keeps the house comfy ----

    public enum Insulation { NONE, WALLS, HEATED }

    /** HEATED: something makes it comfy (a hearth, a heater). WALLS: fiberglass keeps most weather out. */
    public static Insulation insulation(ServerPlayer p, boolean cold) {
        if (coldSweatHearth(p, cold)) return Insulation.HEATED;
        Level level = p.level();
        BlockPos me = p.blockPosition();
        int fiberglass = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx++)
            for (int dy = -3; dy <= 5; dy++)
                for (int dz = -8; dz <= 8; dz++) {
                    BlockState s = level.getBlockState(m.setWithOffset(me, dx, dy, dz));
                    if (cold && heater(s)) return Insulation.HEATED;
                    if (s.is(Hospital.FIBERGLASS.get())) fiberglass++;
                }
        return fiberglass >= 6 ? Insulation.WALLS : Insulation.NONE;
    }

    static boolean heater(BlockState s) {
        boolean lit = s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT);
        if (lit && (s.is(Fire.COAL_HEATER.get()) || s.is(Fire.FIREPLACE.get()) || s.is(Blocks.CAMPFIRE) || s.getBlock() instanceof AbstractFurnaceBlock))
            return true;
        return s.is(Fire.RADIATOR.get()) && s.getValue(BlockStateProperties.POWERED);
    }

    private static Holder<MobEffect> warmth, frigidness;

    /** A Cold Sweat Hearth (warmth) or its cooling (frigidness) reaches you. */
    static boolean coldSweatHearth(LivingEntity e, boolean cold) {
        if (!ModList.get().isLoaded("cold_sweat")) return false;
        if (warmth == null) {
            warmth = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.fromNamespaceAndPath("cold_sweat", "warmth")).orElse(null);
            frigidness = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.fromNamespaceAndPath("cold_sweat", "frigidness")).orElse(null);
        }
        Holder<MobEffect> h = cold ? warmth : frigidness;
        return h != null && e.hasEffect(h);
    }

    // ---- The weather outside ----

    /** +1 = freezing outside, -1 = scorching, 0 = mild. */
    public static double weather(ServerPlayer p) {
        if (testWeather != null) return testWeather;
        double cs = coldSweatWeather(p);
        if (!Double.isNaN(cs)) return cs;
        BlockPos pos = p.blockPosition();
        float t = p.level().getBiome(pos).value().getBaseTemperature();
        if (pos.getY() > 80) t -= (pos.getY() - 80) * 0.05f / 40f; // colder up high, like vanilla snow
        if (!p.level().isDay()) t -= 0.4f;
        if (t < 0.3f) return Math.min(1, (0.3f - t) / 0.8f);
        if (t > 1.2f) return -Math.min(1, (t - 1.2f) / 0.8f);
        return 0;
    }

    private static boolean csTried;
    private static Method csGet, csHolderGet;
    private static Object csWorld, csMin, csMax;

    /** Cold Sweat's world temperature against a comfy indoor range, or NaN without Cold Sweat. */
    static double coldSweatWeather(LivingEntity e) {
        if (!csTried) {
            csTried = true;
            if (ModList.get() != null && ModList.get().isLoaded("cold_sweat")) try {
                Class<?> trait = Class.forName("com.momosoftworks.coldsweat.api.util.Temperature$Trait");
                csGet = Class.forName("com.momosoftworks.coldsweat.api.util.Temperature").getMethod("get", LivingEntity.class, trait);
                csWorld = trait.getField("WORLD").get(null);
                Class<?> config = Class.forName("com.momosoftworks.coldsweat.config.ConfigSettings");
                csMin = config.getField("MIN_TEMP").get(null);
                csMax = config.getField("MAX_TEMP").get(null);
                csHolderGet = csMin.getClass().getMethod("get");
            } catch (ReflectiveOperationException | RuntimeException ex) {
                csGet = null;
            }
        }
        if (csGet == null) return Double.NaN;
        try {
            double world = (double) csGet.invoke(null, e, csWorld);
            double min = ((Number) csHolderGet.invoke(csMin)).doubleValue(), max = ((Number) csHolderGet.invoke(csMax)).doubleValue();
            // Cold Sweat's min/max are where it starts to hurt. A home should be comfy, so the comfy band is the middle
            // half of that range (about 60-84 F with the defaults); past it, it gets worse over another quarter.
            double mid = (min + max) / 2, half = Math.max(0.05, (max - min) / 4);
            if (world < mid - half) return Math.min(1, (mid - half - world) / (2 * half));
            if (world > mid + half) return -Math.min(1, (world - mid - half) / (2 * half));
            return 0;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return Double.NaN;
        }
    }
}
