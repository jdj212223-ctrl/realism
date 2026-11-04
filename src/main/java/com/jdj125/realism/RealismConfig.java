package com.jdj125.realism;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config (realism-server.toml in the world's serverconfig folder). */
public final class RealismConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue THIRST_DAYS, SLEEP_DAYS;
    public static final ModConfigSpec.BooleanValue REAL_TIME, MUSCLE_TEARS, BROKEN_LEGS, BURNS, FROSTBITE, STAMINA,
            BLEEDING, BLOOD_PARTICLES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Realism phase 1: the body").push("body");
        THIRST_DAYS = b.comment("Days without drinking before you die of dehydration").defineInRange("thirstDays", 3, 1, 30);
        SLEEP_DAYS = b.comment("Days without sleeping before you die").defineInRange("sleepDays", 11, 1, 60);
        REAL_TIME = b.comment("true = those days are real-life days (counted while you're online); false = Minecraft days (20 minutes each)")
                .define("realTimeDays", false);
        STAMINA = b.comment("Stamina, and death by exhaustion when you keep pushing with none left").define("stamina", true);
        MUSCLE_TEARS = b.comment("Mining/fighting too fast can tear a muscle").define("muscleTears", true);
        BROKEN_LEGS = b.comment("Big falls break your legs (worse when you land on rock)").define("brokenLegs", true);
        BURNS = b.comment("Touching a working furnace or Cold Sweat boiler with a bare hand burns you").define("burns", true);
        FROSTBITE = b.comment("Freezing too long in Cold Sweat gives frostbite").define("frostbite", true);
        b.pop();
        b.comment("Realism phase 2: blood").push("blood");
        BLEEDING = b.comment("Hits from mobs, weapons, arrows and cactus can make you bleed; lose too much blood and you die").define("bleeding", true);
        BLOOD_PARTICLES = b.comment("Blocky red pixel particles while bleeding (turn off for younger audiences/streams)").define("bloodParticles", true);
        b.pop();
        SPEC = b.build();
    }

    private RealismConfig() {
    }

    /** Ticks in one "day" for thirst and sleep. */
    public static long dayTicks() {
        return REAL_TIME.get() ? 24L * 60 * 60 * 20 : 24000L;
    }
}
