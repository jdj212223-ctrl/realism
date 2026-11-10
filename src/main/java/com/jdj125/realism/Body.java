package com.jdj125.realism;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Everything the realism mod tracks about one player's body. Times are in game ticks (20 per second). It's saved
 * with the player and starts fresh after a death.
 */
public class Body implements INBTSerializable<CompoundTag> {
    /** Ticks since the player last drank. */
    public long thirst;
    /** Ticks since the player last slept a full night. */
    public long awake;
    /** 0..100: drained by sprinting, fighting, mining and jumping, refilled by resting. */
    public float stamina = 100;
    /** Ticks spent pushing on with no stamina left; too much of this kills (exhaustion). */
    public int overexertion;
    /** Ticks left on each injury (0 = healthy). */
    public int brokenLeg, armTear, burn, frostbite;
    /** 1, 2 or 3 while burned. */
    public int burnDegree;
    /** True when the arm tear is bad enough to need a doctor (phase 2): it heals much slower until then. */
    public boolean badTear;
    /** Ticks spent with Cold Sweat body temperature at freezing. */
    public int freezing;
    /** Recent hand actions (breaks/attacks), for muscle tears: a short rolling count that decays. */
    public float handStrain;
    /** Blood in liters (a healthy adult has about 5). */
    public float blood = 5;
    /** 0 = not bleeding, 1 = light, 2 = heavy, 3 = severe. */
    public int bleeding;
    /** Ticks a tourniquet has been on (it stops severe bleeding but slows you while on). */
    public int tourniquet;
    /** 0..100: how you feel (phase 4). */
    public float mood = Mind.START;
    /** The last mood warning shown (0 fine, 1 low, 2 depressed), and ticks until games/TV cheer you up again. */
    public int moodStage, funCooldown;
    /** Phase 6: ticks left while collapsed (0 = not collapsed), and ticks of black screen after waking from surgery. */
    public int collapse, unconscious;
    /** Defibrillator progress on this patient (0 none, 1 charged, 2 cleared) and the game time of the last step. */
    public int defibStep;
    public long defibTime;
    /** A broken neck (until surgery), and spinal cord damage on top of it: paralysed until surgery. */
    public boolean brokenNeck, paralysed;
    /** The country whose land you were last standing in ("" = none), for the "Welcome to..." message. Not saved. */
    public String lastCountry = "";
    /** The last warning stage shown for thirst and sleep, so each warning is said once. */
    public int thirstStage, sleepStage;
    /** 1.0.0: this life already got its period clothes (old times). */
    public boolean dressed;
    /** 1.0.0 radiation: the dose so far (sieverts), the rate right now (Sv/s, not saved), ticks of anti-radiation pills left. */
    public float dose, rate;
    public int antiRad;
    /** 1.0.0 illnesses: ticks of flu and food poisoning left, ticks an infection has been growing, ticks bleeding unbandaged. */
    public int flu, foodPoisoning, infection, untreatedBleed;
    /** 1.0.0 cancer: stage 0-4, ticks into the current stage, and whether a Medical Scanner found it. */
    public int cancer, cancerTime;
    public boolean cancerFound;
    /** 1.0.0 justice: ticks left of a death sentence being carried out (the screen fading to black). */
    public int sentenced;
    /** 1.2.0 evidence: who last hurt you (UUID and name), with what (item id), and when (game time). */
    public String attacker = "", attackerName = "", attackerWeapon = "";
    public long attackerTime;
    /** 1.2.0: what made you collapse (a damage type message id), for the forensics kit. */
    public String collapseCause = "";
    /** 2.0.2 daily life: how clean you are (100 = just showered), how full your bladder is, how healthy your teeth are. */
    public float hygiene = 100, bladder, teeth = 100;
    public int lifeStage;

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putLong("thirst", thirst);
        t.putLong("awake", awake);
        t.putFloat("stamina", stamina);
        t.putInt("overexertion", overexertion);
        t.putInt("brokenLeg", brokenLeg);
        t.putInt("armTear", armTear);
        t.putBoolean("badTear", badTear);
        t.putInt("burn", burn);
        t.putInt("burnDegree", burnDegree);
        t.putInt("frostbite", frostbite);
        t.putInt("freezing", freezing);
        t.putInt("thirstStage", thirstStage);
        t.putInt("sleepStage", sleepStage);
        t.putFloat("blood", blood);
        t.putInt("bleeding", bleeding);
        t.putInt("tourniquet", tourniquet);
        t.putFloat("mood", mood);
        t.putInt("moodStage", moodStage);
        t.putInt("funCooldown", funCooldown);
        t.putInt("collapse", collapse);
        t.putBoolean("brokenNeck", brokenNeck);
        t.putBoolean("paralysed", paralysed);
        t.putInt("unconscious", unconscious);
        t.putBoolean("dressed", dressed);
        t.putFloat("dose", dose);
        t.putInt("antiRad", antiRad);
        t.putInt("flu", flu);
        t.putInt("foodPoisoning", foodPoisoning);
        t.putInt("infection", infection);
        t.putInt("untreatedBleed", untreatedBleed);
        t.putInt("cancer", cancer);
        t.putInt("cancerTime", cancerTime);
        t.putBoolean("cancerFound", cancerFound);
        t.putInt("sentenced", sentenced);
        t.putString("attacker", attacker);
        t.putString("attackerName", attackerName);
        t.putString("attackerWeapon", attackerWeapon);
        t.putLong("attackerTime", attackerTime);
        t.putString("collapseCause", collapseCause);
        t.putFloat("hygiene", hygiene);
        t.putFloat("bladder", bladder);
        t.putFloat("teeth", teeth);
        t.putInt("lifeStage", lifeStage);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        thirst = t.getLong("thirst");
        awake = t.getLong("awake");
        stamina = t.contains("stamina") ? t.getFloat("stamina") : 100;
        overexertion = t.getInt("overexertion");
        brokenLeg = t.getInt("brokenLeg");
        armTear = t.getInt("armTear");
        badTear = t.getBoolean("badTear");
        burn = t.getInt("burn");
        burnDegree = t.getInt("burnDegree");
        frostbite = t.getInt("frostbite");
        freezing = t.getInt("freezing");
        thirstStage = t.getInt("thirstStage");
        sleepStage = t.getInt("sleepStage");
        blood = t.contains("blood") ? t.getFloat("blood") : 5;
        bleeding = t.getInt("bleeding");
        tourniquet = t.getInt("tourniquet");
        mood = t.contains("mood") ? t.getFloat("mood") : Mind.START;
        moodStage = t.getInt("moodStage");
        funCooldown = t.getInt("funCooldown");
        collapse = t.getInt("collapse");
        brokenNeck = t.getBoolean("brokenNeck");
        paralysed = t.getBoolean("paralysed");
        unconscious = t.getInt("unconscious");
        dressed = t.getBoolean("dressed");
        dose = t.getFloat("dose");
        antiRad = t.getInt("antiRad");
        flu = t.getInt("flu");
        foodPoisoning = t.getInt("foodPoisoning");
        infection = t.getInt("infection");
        untreatedBleed = t.getInt("untreatedBleed");
        cancer = t.getInt("cancer");
        cancerTime = t.getInt("cancerTime");
        cancerFound = t.getBoolean("cancerFound");
        sentenced = t.getInt("sentenced");
        attacker = t.getString("attacker");
        attackerName = t.getString("attackerName");
        attackerWeapon = t.getString("attackerWeapon");
        attackerTime = t.getLong("attackerTime");
        collapseCause = t.getString("collapseCause");
        hygiene = t.contains("hygiene") ? t.getFloat("hygiene") : 100;
        bladder = t.getFloat("bladder");
        teeth = t.contains("teeth") ? t.getFloat("teeth") : 100;
        lifeStage = t.getInt("lifeStage");
    }
}
