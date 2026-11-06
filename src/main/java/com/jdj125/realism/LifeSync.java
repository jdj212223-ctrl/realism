package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.0.0's half of the HUD data, sent once a second next to {@link BodySync}: the world's year (black and white vision
 * before colour TV is invented), illnesses, radiation (the dose so far in sieverts, and how fast it's coming in, for
 * the Geiger counter's clicks), and whether you're in jail.
 */
public record LifeSync(int year, int flags, float dose, float rate) implements CustomPacketPayload {
    public static final int CHOSEN = 1, FLU = 2, FOOD_POISONING = 4, INFECTION = 8, CANCER_FOUND = 16, JAILED = 32,
            RADIATION_SICK = 64, SENTENCED = 128;
    public static final Type<LifeSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "life"));
    public static final StreamCodec<ByteBuf, LifeSync> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LifeSync::year,
            ByteBufCodecs.VAR_INT, LifeSync::flags,
            ByteBufCodecs.FLOAT, LifeSync::dose,
            ByteBufCodecs.FLOAT, LifeSync::rate,
            LifeSync::new);

    public static volatile LifeSync latest = new LifeSync(Era.TODAY, 0, 0, 0);

    /** The cancer stage (1-4), only once a scan found it; 0 otherwise. Kept in bits 8-10 of the flags. */
    public int cancerStage() {
        return (flags >>> 8) & 7;
    }

    /** Black and white: the world started in year 0 and colour TV isn't invented yet. */
    public boolean blackAndWhite() {
        return (flags & CHOSEN) != 0 && year < Era.COLOUR;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
