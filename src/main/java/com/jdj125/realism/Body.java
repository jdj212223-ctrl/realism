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
    /** The last warning stage shown for thirst and sleep, so each warning is said once. */
    public int thirstStage, sleepStage;

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
    }
}
