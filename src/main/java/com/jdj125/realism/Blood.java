package com.jdj125.realism;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.joml.Vector3f;

/**
 * Phase 2: blood. You have 5 liters. Sharp hits make you bleed (light, heavy or severe); bandages stop light and
 * heavy bleeding, a tourniquet stops severe. Losing blood makes you pale, then dizzy, then you collapse, and under
 * 2 liters you bleed out. Blood slowly comes back while you're fed. Everyone has a real blood type that stays with
 * them after death; transfusions with blood bags must match it.
 */
public final class Blood {
    public static final float FULL = 5, PALE = 4.2f, DIZZY = 3.5f, COLLAPSE = 2.6f, DEAD = 2.0f;
    /** Liters lost per second for light, heavy and severe bleeding. */
    private static final float[] RATE = {0, 0.002f, 0.01f, 0.03f};
    /** Real-world blood type shares (roughly), for picking a new player's type. */
    private static final String[] TYPES = {"O+", "A+", "B+", "AB+", "O-", "A-", "B-", "AB-"};
    private static final int[] SHARE = {38, 34, 9, 3, 7, 6, 2, 1};
    private static final DustParticleOptions DROP = new DustParticleOptions(new Vector3f(0.55f, 0.02f, 0.02f), 1.0f);

    private Blood() {
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    public static String randomType(RandomSource r) {
        int roll = r.nextInt(100);
        for (int i = 0; i < TYPES.length; i++) {
            roll -= SHARE[i];
            if (roll < 0) return TYPES[i];
        }
        return "O+";
    }

    /** This player's blood type, picked once and kept for good (it survives death). */
    public static String type(Player p) {
        String t = p.getData(Realism.BLOOD_TYPE);
        if (t.isEmpty()) {
            t = randomType(p.getRandom());
            p.setData(Realism.BLOOD_TYPE, t);
        }
        return t;
    }

    /** Can someone with blood type {@code patient} safely get blood of type {@code donor}? (ABO + Rh rules.) */
    public static boolean compatible(String donor, String patient) {
        if (donor.isEmpty() || patient.isEmpty()) return false;
        boolean donorNeg = donor.endsWith("-"), patientNeg = patient.endsWith("-");
        if (patientNeg && !donorNeg) return false;
        String d = donor.substring(0, donor.length() - 1), pt = patient.substring(0, patient.length() - 1);
        return d.equals("O") || pt.equals("AB") || d.equals(pt);
    }

    /** Starts (or worsens) bleeding: the worst wound wins. */
    public static void bleed(Player p, int level) {
        Body b = BodyEvents.body(p);
        if (level <= b.bleeding) return;
        b.bleeding = level;
        if (level == 3) b.tourniquet = 0;
        say(p, "bleed." + level);
    }

    // Sharp hits make you bleed; how badly depends on how hard the hit was.
    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide || p.isCreative() || !RealismConfig.BLEEDING.get()) return;
        DamageSource src = event.getSource();
        float amount = event.getNewDamage();
        if (amount <= 0 || !sharp(src)) return;
        RandomSource r = p.getRandom();
        int level = amount >= 7 ? 3 : amount >= 4 ? 2 : r.nextFloat() < 0.4f ? 1 : 0;
        if (level > 0) bleed(p, level);
    }

    private static boolean sharp(DamageSource src) {
        return src.is(DamageTypeTags.IS_PROJECTILE) || src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || src.is(DamageTypes.PLAYER_ATTACK) || src.is(DamageTypes.CACTUS) || src.is(DamageTypes.SWEET_BERRY_BUSH)
                || src.is(DamageTypes.THORNS) || src.is(DamageTypes.STING) || src.is(DamageTypes.TRIDENT)
                || src.is(DamageTypes.EXPLOSION) || src.is(DamageTypes.PLAYER_EXPLOSION);
    }

    /** One second of blood for this player (called from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        type(p);
        if (b.bleeding > 0) {
            boolean held = b.bleeding == 3 && b.tourniquet > 0;
            if (!held) b.blood -= RATE[b.bleeding];
            // Light bleeding clots by itself after a while.
            if (b.bleeding == 1 && p.getRandom().nextInt(45) == 0) {
                b.bleeding = 0;
                say(p, "bleed.stopped");
            }
            if (!held && RealismConfig.BLOOD_PARTICLES.get() && p.level() instanceof ServerLevel level)
                level.sendParticles(DROP, p.getX(), p.getY() + 0.9, p.getZ(), b.bleeding * 2, 0.2, 0.4, 0.2, 0);
        } else if (b.blood < FULL && p.getFoodData().getFoodLevel() >= 10) {
            b.blood = Math.min(FULL, b.blood + 0.0005f);
        }
        if (b.tourniquet > 0) {
            b.tourniquet += 20;
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 0, true, false, true));
        }
        if (b.blood < PALE) p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 50, 0, true, false, true));
        if (b.blood < DIZZY) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 1, true, false, true));
            if (p.getRandom().nextInt(12) == 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false));
        }
        if (b.blood < COLLAPSE) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 0, true, false, true));
            p.setSprinting(false);
        }
        if (b.blood <= DEAD) p.hurt(Realism.source(p, Realism.BLED_OUT), Float.MAX_VALUE);
    }

    /** Takes 0.5 l from {@code donor} (if they can spare it) and returns its blood type, or "" if not. */
    public static String draw(Player donor) {
        Body b = BodyEvents.body(donor);
        if (b.blood - 0.5f < DIZZY) return "";
        b.blood -= 0.5f;
        return type(donor);
    }

    /** Gives {@code patient} 0.5 l of {@code donorType} blood. Returns false if the types don't match (a reaction). */
    public static boolean transfuse(Player patient, String donorType) {
        Body b = BodyEvents.body(patient);
        if (!compatible(donorType, type(patient))) {
            patient.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
            patient.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 1));
            patient.hurt(patient.damageSources().magic(), 4);
            say(patient, "transfusion.reaction", donorType, type(patient));
            return false;
        }
        b.blood = Math.min(FULL, b.blood + 0.5f);
        say(patient, "transfusion.ok", String.format(java.util.Locale.ROOT, "%.1f", b.blood));
        return true;
    }
}
