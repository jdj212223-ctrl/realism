package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Method;

/**
 * The phase 1 body rules. Everything runs on the server, once a second per player (plus a few one-off events):
 * <ul>
 * <li>Thirst: drink water (bottle, or crouch in water with an empty hand) or die after thirstDays.</li>
 * <li>Sleep: sleep through a night or die after sleepDays awake, with hallucinations near the end.</li>
 * <li>Stamina: sprinting and hard work drain it; pushing on with none left blacks you out, then kills.</li>
 * <li>Muscle tears: mining or hitting too fast tears your arm (weaker, slower) for days.</li>
 * <li>Broken legs: big falls break them, worse on rock.</li>
 * <li>Burns: punching a working furnace bare-handed is a 1st-3rd degree burn; a Cold Sweat boiler is 3rd degree.</li>
 * <li>Frostbite: freezing too long in Cold Sweat.</li>
 * </ul>
 */
public final class BodyEvents {
    private static final int SECOND = 20;

    private BodyEvents() {
    }

    public static Body body(Player p) {
        return p.getData(Realism.BODY);
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    /** Refreshes an effect for a little over a second, so it lasts exactly as long as the condition does. */
    private static void keep(Player p, Holder<MobEffect> effect, int amplifier) {
        p.addEffect(new MobEffectInstance(effect, 50, amplifier, true, false, true));
    }

    private static void die(Player p, net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> type) {
        p.hurt(Realism.source(p, type), Float.MAX_VALUE);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % SECOND != 0 || !p.isAlive()) return;
        Body b = body(p);
        if (!p.isCreative() && !p.isSpectator()) second(p);
        b.handStrain *= 0.8f;
        if (p.connection != null && p.connection.hasChannel(BodySync.TYPE)) PacketDistributor.sendToPlayer(p, sync(b));
    }

    /** One second of every body rule for this player (also called directly by the GameTests). */
    public static void second(ServerPlayer p) {
        Body b = body(p);
        thirst(p, b);
        sleep(p, b);
        if (RealismConfig.STAMINA.get()) stamina(p, b);
        injuries(p, b);
        if (RealismConfig.FROSTBITE.get()) frostbite(p, b);
    }

    public static BodySync sync(Body b) {
        long limitT = RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks();
        long limitS = RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks();
        int flags = (b.brokenLeg > 0 ? BodySync.BROKEN_LEG : 0) | (b.armTear > 0 ? BodySync.ARM_TEAR : 0)
                | (b.badTear ? BodySync.BAD_TEAR : 0) | (b.frostbite > 0 ? BodySync.FROSTBITE : 0)
                | (b.burn > 0 ? (b.burnDegree == 1 ? BodySync.BURN1 : b.burnDegree == 2 ? BodySync.BURN2 : BodySync.BURN3) : 0);
        return new BodySync(Math.min(1f, b.thirst / (float) limitT), Math.min(1f, b.awake / (float) limitS), b.stamina, flags);
    }

    // ---- Thirst ----

    private static void thirst(ServerPlayer p, Body b) {
        // Crouching in water with an empty hand: drink gulps from it.
        if (p.isShiftKeyDown() && p.isInWater() && p.getMainHandItem().isEmpty()) {
            if (b.thirst > 0) p.playNotifySound(SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.5f, 1);
            b.thirst = Math.max(0, b.thirst - 30 * SECOND);
        } else {
            b.thirst += SECOND;
        }
        float f = b.thirst / (float) (RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks());
        int stage = f >= 0.9f ? 3 : f >= 0.66f ? 2 : f >= 0.33f ? 1 : 0;
        if (stage > b.thirstStage) say(p, "thirst." + stage);
        b.thirstStage = stage;
        if (stage >= 2) {
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, stage - 2);
            if (p.getRandom().nextInt(stage == 3 ? 8 : 30) == 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false));
        }
        if (stage == 3) {
            keep(p, MobEffects.WEAKNESS, 0);
            if (p.getRandom().nextInt(10) == 0) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, true, false));
        }
        if (f >= 1) die(p, Realism.DEHYDRATION);
    }

    @SubscribeEvent
    public static void onDrink(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide) return;
        ItemStack item = event.getItem();
        Body b = body(p);
        long day = RealismConfig.dayTicks();
        PotionContents potion = item.get(DataComponents.POTION_CONTENTS);
        if (item.is(Items.POTION) && potion != null && potion.is(Potions.WATER)) b.thirst = 0;
        else if (item.is(Items.MILK_BUCKET)) b.thirst = Math.max(0, b.thirst - day / 2);
        else if (item.is(Items.HONEY_BOTTLE) || item.is(Items.MUSHROOM_STEW) || item.is(Items.BEETROOT_SOUP)
                || item.is(Items.RABBIT_STEW) || item.is(Items.SUSPICIOUS_STEW)) b.thirst = Math.max(0, b.thirst - day / 4);
        else if (item.is(Items.MELON_SLICE) || item.is(Items.APPLE) || item.is(Items.SWEET_BERRIES) || item.is(Items.GLOW_BERRIES))
            b.thirst = Math.max(0, b.thirst - day / 8);
        else return;
        if (b.thirst == 0) b.thirstStage = 0;
    }

    // ---- Sleep ----

    private static final SoundEvent[] HALLUCINATIONS = {SoundEvents.CREEPER_PRIMED, SoundEvents.ZOMBIE_AMBIENT,
            SoundEvents.SKELETON_AMBIENT, SoundEvents.ENDERMAN_STARE, SoundEvents.WOODEN_DOOR_OPEN, SoundEvents.SPIDER_AMBIENT};

    private static void sleep(ServerPlayer p, Body b) {
        if (p.isSleepingLongEnough()) {
            if (b.awake > 0) say(p, "sleep.rested");
            b.awake = 0;
            b.sleepStage = 0;
            return;
        }
        if (!p.isSleeping()) b.awake += SECOND;
        float f = b.awake / (float) (RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks());
        int stage = f >= 0.8f ? 3 : f >= 0.55f ? 2 : f >= 0.27f ? 1 : 0;
        if (stage > b.sleepStage) say(p, "sleep." + stage);
        b.sleepStage = stage;
        if (stage >= 2) {
            keep(p, MobEffects.DIG_SLOWDOWN, 0);
            // Micro-naps: the eyes close for a moment.
            if (p.getRandom().nextInt(stage == 3 ? 10 : 40) == 0) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, true, false));
        }
        if (stage == 3) {
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
            if (p.getRandom().nextInt(6) == 0)
                p.playNotifySound(HALLUCINATIONS[p.getRandom().nextInt(HALLUCINATIONS.length)], SoundSource.HOSTILE, 1, 1);
        }
        if (f >= 1) die(p, Realism.SLEEPLESSNESS);
    }

    // ---- Stamina and exhaustion ----

    private static void stamina(ServerPlayer p, Body b) {
        boolean working = p.isSprinting() || b.handStrain > 2;
        if (working) {
            float drain = (p.isSprinting() ? 4 : 0) + b.handStrain * 0.6f;
            // Thirst, hunger, freezing and heavy injuries make you tire faster.
            if (b.thirstStage >= 2 || p.getFoodData().getFoodLevel() <= 6) drain *= 1.6f;
            if (b.freezing > 0 || b.frostbite > 0) drain *= 1.4f;
            b.stamina = Math.max(0, b.stamina - drain);
        } else {
            b.stamina = Math.min(100, b.stamina + (p.isShiftKeyDown() || p.isPassenger() ? 10 : 6));
            b.overexertion = Math.max(0, b.overexertion - 2 * SECOND);
        }
        if (b.stamina <= 0) {
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, 1);
            keep(p, MobEffects.WEAKNESS, 0);
            p.setSprinting(false);
            if (working) {
                b.overexertion += SECOND;
                if (b.overexertion == 10 * SECOND) say(p, "stamina.breathing");
                if (b.overexertion == 30 * SECOND) say(p, "stamina.shaky");
                if (b.overexertion >= 45 * SECOND && b.overexertion % (10 * SECOND) == 0) {
                    say(p, "stamina.blackout");
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0, true, false));
                }
                if (b.overexertion >= 90 * SECOND) die(p, Realism.EXHAUSTION);
            }
        } else if (b.stamina < 20) keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
    }

    // ---- Muscle tears ----

    private static void strainHand(Player p) {
        if (p.level().isClientSide || p.isCreative() || !RealismConfig.MUSCLE_TEARS.get()) return;
        Body b = body(p);
        b.handStrain += 1;
        if (b.handStrain > 10 && p.getRandom().nextInt(4) == 0) {
            long day = RealismConfig.dayTicks();
            if (b.armTear > 0 && !b.badTear) {
                b.badTear = true;
                b.armTear = (int) Math.min(Integer.MAX_VALUE, day * 6);
                say(p, "tear.bad");
            } else if (b.armTear == 0) {
                b.armTear = (int) Math.min(Integer.MAX_VALUE, day * 2);
                say(p, "tear");
            }
            b.handStrain = 0;
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        strainHand(event.getPlayer());
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        strainHand(event.getEntity());
    }

    // ---- Broken legs ----

    private static boolean soft(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.SNOW) || s.is(BlockTags.LEAVES) || s.is(BlockTags.WOOL)
                || s.is(Blocks.HAY_BLOCK) || s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.HONEY_BLOCK) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.MUD) || s.is(BlockTags.BEDS) || s.isAir();
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide || p.isCreative() || !RealismConfig.BROKEN_LEGS.get()) return;
        if (p.isInWater() || event.getDistance() < 7) return;
        BlockState landed = p.level().getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ()));
        boolean rock = !soft(landed);
        float d = event.getDistance();
        if (rock) event.setDamageMultiplier(event.getDamageMultiplier() * 1.5f);
        float chance = d >= 12 ? 1 : (d - 6) / 6f * (rock ? 1.5f : 1);
        if (p.getRandom().nextFloat() < chance) {
            Body b = body(p);
            b.brokenLeg = (int) (RealismConfig.dayTicks() * (rock ? 5 : 3));
            say(p, rock ? "leg.rock" : "leg");
        }
    }

    // ---- Burns ----

    @SubscribeEvent
    public static void onTouch(PlayerInteractEvent.LeftClickBlock event) {
        Player p = event.getEntity();
        if (p.level().isClientSide || p.isCreative() || !RealismConfig.BURNS.get() || !p.getMainHandItem().isEmpty()) return;
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        BlockState s = event.getLevel().getBlockState(event.getPos());
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        boolean lit = s.hasProperty(BlockStateProperties.LIT) && s.getValue(BlockStateProperties.LIT);
        int degree;
        if (id.equals("cold_sweat:boiler") && (lit || !s.hasProperty(BlockStateProperties.LIT))) degree = 3;
        else if (s.getBlock() instanceof AbstractFurnaceBlock && lit) {
            float r = p.getRandom().nextFloat();
            degree = r < 0.6f ? 1 : r < 0.9f ? 2 : 3;
        } else return;
        burn(p, degree);
    }

    public static void burn(Player p, int degree) {
        Body b = body(p);
        long day = RealismConfig.dayTicks();
        b.burnDegree = Math.max(b.burn > 0 ? b.burnDegree : 0, degree);
        b.burn = (int) Math.max(b.burn, degree == 1 ? 60 * SECOND : degree == 2 ? day : day * 3);
        p.hurt(Realism.source(p, Realism.BURN), degree == 1 ? 1 : degree == 2 ? 3 : 6);
        p.playNotifySound(SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 1.2f);
        say(p, "burn." + degree);
    }

    // ---- Frostbite (Cold Sweat) ----

    private static Method coldGet;
    private static Object coldBody;
    private static boolean coldTried;

    private static double coldSweatBody(LivingEntity e) {
        if (!coldTried) {
            coldTried = true;
            try {
                Class<?> trait = Class.forName("com.momosoftworks.coldsweat.api.util.Temperature$Trait");
                coldGet = Class.forName("com.momosoftworks.coldsweat.api.util.Temperature").getMethod("get", LivingEntity.class, trait);
                coldBody = trait.getField("BODY").get(null);
            } catch (ReflectiveOperationException ex) {
                coldGet = null;
            }
        }
        if (coldGet == null) return 0;
        try {
            return (double) coldGet.invoke(null, e, coldBody);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return 0;
        }
    }

    private static void frostbite(ServerPlayer p, Body b) {
        double t = coldSweatBody(p);
        if (t <= -100) {
            b.freezing += SECOND;
            if (b.freezing == 60 * SECOND && b.frostbite == 0) {
                b.frostbite = (int) (RealismConfig.dayTicks() * 2);
                say(p, "frostbite");
            }
            if (b.frostbite > 0 && b.freezing % (5 * SECOND) == 0) p.hurt(Realism.source(p, Realism.FROSTBITE), 1);
        } else b.freezing = 0;
    }

    // ---- Injury effects and healing ----

    private static void injuries(ServerPlayer p, Body b) {
        if (b.brokenLeg > 0) {
            b.brokenLeg = Math.max(0, b.brokenLeg - SECOND);
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, 2);
            keep(p, MobEffects.JUMP, 250); // amplifier 250 = can't jump
            p.setSprinting(false);
            if (b.brokenLeg == 0) say(p, "leg.healed");
        }
        if (b.armTear > 0) {
            b.armTear = Math.max(0, b.armTear - (b.badTear ? SECOND / 4 : SECOND));
            keep(p, MobEffects.WEAKNESS, b.badTear ? 1 : 0);
            keep(p, MobEffects.DIG_SLOWDOWN, b.badTear ? 1 : 0);
            if (b.armTear == 0) {
                b.badTear = false;
                say(p, "tear.healed");
            }
        }
        if (b.burn > 0) {
            b.burn = Math.max(0, b.burn - SECOND);
            if (b.burnDegree >= 2) keep(p, MobEffects.WEAKNESS, b.burnDegree - 2);
            if (b.burnDegree == 3) keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
            if (b.burn == 0) b.burnDegree = 0;
        }
        if (b.frostbite > 0) {
            b.frostbite = Math.max(0, b.frostbite - SECOND);
            keep(p, MobEffects.DIG_SLOWDOWN, 0);
            keep(p, MobEffects.MOVEMENT_SLOWDOWN, 0);
        }
    }
}
