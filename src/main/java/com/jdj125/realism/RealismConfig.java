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
    public static final ModConfigSpec.BooleanValue ECHO, OLD_TIMES_VISION, NO_HUD, LOCK_WORLD_CREATION, MOTION_WEBCAM;
    /** Realism 3.1: no cheating in real life. */
    public static final ModConfigSpec.BooleanValue NO_COMMANDS, SURVIVAL_ONLY, SMOKE, KITCHEN_FIRES, FRUITS, VOMITING, WEIGHT, CHESTS_KEEP_ITEMS,
            FOOD_SPOILS, SUNBURN, ALLERGIES, TREES_FALL, SLIPPERY_ICE, WET_CLOTHES,
            GERMS, AGING, OLD_AGE_DEATH, RINGING_EARS, MOSQUITOES, TORCHES_BURN_OUT, WARM_CLOTHES, HAIR;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> ALLOWED_COMMANDS;
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
        b.comment("Realism 3.1: no cheating in real life").push("rules");
        NO_COMMANDS = b.comment("Players (even ops) can't use commands like /give or /gamemode. The server console still can.")
                .define("noCommands", true);
        ALLOWED_COMMANDS = b.comment("Commands players may still use: Realism's own (money, countries, jobs, the law, the ambulance) and talking")
                .defineListAllowEmpty("allowedCommands", java.util.List.of("pay", "country", "job", "law", "ambulance", "era", "realism",
                        "botpass", "msg", "tell", "w", "me", "list", "help", "trigger"), () -> "", o -> o instanceof String);
        SURVIVAL_ONLY = b.comment("Creative mode switches back to survival by itself (real life has no creative mode)").define("survivalOnly", true);
        b.pop();
        b.comment("Realism 3.2: fire and heating").push("fire");
        SMOKE = b.comment("Fire indoors (or a fireplace with no chimney) fills the room with smoke that hurts unless you crouch").define("smoke", true);
        KITCHEN_FIRES = b.comment("A burning stove left alone, or sparks from a fireplace, can set things on fire").define("kitchenFires", true);
        b.pop();
        b.comment("Realism 3.3: food").push("food");
        FRUITS = b.comment("Leaves sometimes drop fruit (depending on the climate) and grass hides strawberries").define("fruits", true);
        VOMITING = b.comment("Food poisoning and spinning around too much make you throw up").define("vomiting", true);
        b.pop();
        b.comment("Realism 4.0: weight and boxes").push("carrying");
        WEIGHT = b.comment("Everything you carry weighs something: over 25 kg you slow down, over 40 kg you can't run, over 60 kg you can't jump and you sink, over 100 kg you can't pick anything up").define("weight", true);
        CHESTS_KEEP_ITEMS = b.comment("Breaking a chest or barrel keeps the items inside it (like picking up a real box)").define("chestsKeepItems", true);
        b.pop();
        b.comment("Realism 4.1: real life, wave 1").push("reallife");
        FOOD_SPOILS = b.comment("Food goes bad over a few days (spoiled food = food poisoning); a powered Fridge keeps it fresh").define("foodSpoils", true);
        SUNBURN = b.comment("Lots of hot sun with no hat gives a sunburn").define("sunburn", true);
        ALLERGIES = b.comment("Some players are allergic to pollen, cats or bee stings").define("allergies", true);
        TREES_FALL = b.comment("Chopping the bottom log brings the whole tree down (crouch to chop one log)").define("treesFall", true);
        SLIPPERY_ICE = b.comment("Running on ice can make you slip and fall").define("slipperyIce", true);
        WET_CLOTHES = b.comment("Swimming and rain soak you; soaked in the cold can give you the flu").define("wetClothes", true);
        b.pop();
        b.comment("Realism 4.2: real life, wave 2").push("reallife2");
        GERMS = b.comment("Raw meat, rotten flesh and bare-hand punching put germs on your hands; eating before washing can make you sick").define("germs", true);
        AGING = b.comment("Every life starts at 18, with a birthday every 7 Minecraft days; old age makes you slower").define("aging", true);
        OLD_AGE_DEATH = b.comment("Past 95 you can die peacefully of old age").define("oldAgeDeath", true);
        RINGING_EARS = b.comment("Explosions close by leave your ears ringing (most sounds muffled for a while)").define("ringingEars", true);
        MOSQUITOES = b.comment("Mosquitoes bite in swamps from dusk to dawn").define("mosquitoes", true);
        TORCHES_BURN_OUT = b.comment("Torches you place burn out after 3 Minecraft days (lanterns don't)").define("torchesBurnOut", true);
        WARM_CLOTHES = b.comment("Leather clothes keep you warm; out in the snow at night without them you can catch the flu").define("warmClothes", true);
        HAIR = b.comment("Hair grows: after 10 days it's messy; sneak-use shears on yourself or use them on a friend for a haircut").define("hair", true);
        b.pop();
        SPEC = b.build();
        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        ECHO = c.comment("Sounds echo in big caves and halls").define("echo", true);
        OLD_TIMES_VISION = c.comment("Black and white vision until colour TV is invented (worlds that started in year 0)").define("oldTimesVision", true);
        NO_HUD = c.comment("No health, hunger, armor, air or XP bars and no Realism status panel: real life has no HUD (the hotbar stays)")
                .define("noHud", true);
        LOCK_WORLD_CREATION = c.comment("Create World shows only the Game tab, with creative mode and cheats locked off")
                .define("lockWorldCreation", true);
        MOTION_WEBCAM = c.comment("Motion games use your real webcam (arms and head). Off until you turn it on in a console. Frames stay on your computer: nothing is saved or sent.")
                .define("motionWebcam", false);
        CLIENT_SPEC = c.build();
    }

    private RealismConfig() {
    }

    /** Ticks in one "day" for thirst and sleep. */
    public static long dayTicks() {
        return REAL_TIME.get() ? 24L * 60 * 60 * 20 : 24000L;
    }
}
