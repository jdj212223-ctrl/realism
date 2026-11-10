package com.jdj125.realism;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config (realism-server.toml in the world's serverconfig folder). */
public final class RealismConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue THIRST_DAYS, SLEEP_DAYS;
    public static final ModConfigSpec.BooleanValue REAL_TIME, MUSCLE_TEARS, BROKEN_LEGS, BURNS, FROSTBITE, STAMINA,
            BLEEDING, BLOOD_PARTICLES, MOOD, NO_REVIVING, PERMA_DEATH_SPECTATOR, BLACK_SLEEP,
            DISEASES, VOICE_ONLY_CHAT, NO_CRAFTING_TABLE, REMAINS, MODERN_KNIVES, AFTERLIFE, HELL_BURNS, REBORN, DAILY_LIFE, JOBS, CARS;
    public static final ModConfigSpec.DoubleValue HEAVEN_CHANCE;
    public static final ModConfigSpec.IntValue HEAVEN_MINUTES, HELL_MINUTES, DARK_MINUTES;
    /** Client settings (realism-client.toml): echo in caves, black and white vision in old times. */
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ECHO, OLD_TIMES_VISION;
    public static final ModConfigSpec.IntValue COLLAPSE_SECONDS;

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
        b.comment("Realism phase 6: no reviving").push("death");
        NO_REVIVING = b.comment("Instead of dying you collapse; someone must defib you or call an ambulance in time, or you're gone for good").define("noReviving", true);
        COLLAPSE_SECONDS = b.comment("How long a collapsed player can still be saved").defineInRange("collapseSeconds", 60, 10, 600);
        PERMA_DEATH_SPECTATOR = b.comment("Gone for good = spectator (like hardcore). false = you start over as a new person (new blood type)").define("permaDeathSpectator", true);
        b.pop();
        b.comment("Realism phase 4: the mind").push("mind");
        MOOD = b.comment("Mood and depression: loneliness, hunger and injuries bring it down; friends, pets, music, games, TV and therapy bring it up").define("mood", true);
        BLACK_SLEEP = b.comment("Black screen with drifting dreams (or nightmares) while you sleep").define("blackScreenWhenSleeping", true);
        b.pop();
        b.comment("Realism 1.0.0").push("life");
        DISEASES = b.comment("Radiation, flu, food poisoning, infections and cancer").define("diseases", true);
        VOICE_ONLY_CHAT = b.comment("With Simple Voice Chat installed, typed chat is off: talk with your voice (commands and phone texts still work). false = typing allowed")
                .define("voiceOnlyChat", true);
        NO_CRAFTING_TABLE = b.comment("Crafting tables don't open: craft at a Workbench holding a Hammer").define("noCraftingTable", true);
        REMAINS = b.comment("Players who are gone for good leave their remains where they fell").define("remains", true);
        MODERN_KNIVES = b.comment("From 1900 (in worlds that picked an era) swords become knives of the same material").define("modernKnives", true);
        b.pop();
        b.comment("Realism 2.0: the afterlife (replaces the spectator ghost when you're gone for good)").push("afterlife");
        AFTERLIFE = b.comment("Judgment, heaven, hell and the dark. false = the old behaviour (permaDeathSpectator)").define("afterlife", true);
        HEAVEN_CHANCE = b.comment("Chance that someone with no good deeds and no sins still goes to heaven (the rest see only darkness)")
                .defineInRange("heavenChance", 0.10, 0.0, 1.0);
        REBORN = b.comment("Coming back to life isn't real: false = you stay in the afterlife for good. true = born again after the minutes below")
                .define("bornAgain", false);
        HEAVEN_MINUTES = b.comment("Minutes in heaven before you're born again").defineInRange("heavenMinutes", 10, 1, 1440);
        HELL_MINUTES = b.comment("Minutes in hell before you're born again").defineInRange("hellMinutes", 30, 1, 1440);
        DARK_MINUTES = b.comment("Minutes in the dark before you're born again").defineInRange("darkMinutes", 15, 1, 1440);
        HELL_BURNS = b.comment("You keep burning in hell (it can't kill you)").define("hellBurns", true);
        b.pop();
        b.comment("Realism 2.0.2: daily life, jobs, cars").push("everyday");
        DAILY_LIFE = b.comment("Hygiene (stink), bladder (toilet) and teeth (toothbrush)").define("dailyLife", true);
        JOBS = b.comment("Jobs with a daily paycheck (/job)").define("jobs", true);
        CARS = b.comment("Cars need fuel, and crashes hurt").define("cars", true);
        b.pop();
        SPEC = b.build();
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        ECHO = c.comment("Sounds echo in big caves and halls").define("echo", true);
        OLD_TIMES_VISION = c.comment("Black and white vision until colour TV is invented (worlds that started in year 0)").define("oldTimesVision", true);
        CLIENT_SPEC = c.build();
    }

    private RealismConfig() {
    }

    /** Ticks in one "day" for thirst and sleep. */
    public static long dayTicks() {
        return REAL_TIME.get() ? 24L * 60 * 60 * 20 : 24000L;
    }
}
