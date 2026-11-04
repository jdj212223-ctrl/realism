package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent to a player once a second so their HUD can show their body: thirst and sleep as 0..1 of the way to death,
 * stamina 0..100, and which injuries they have.
 */
public record BodySync(float thirst, float awake, float stamina, int injuries) implements CustomPacketPayload {
    public static final int BROKEN_LEG = 1, ARM_TEAR = 2, BURN1 = 4, BURN2 = 8, BURN3 = 16, FROSTBITE = 32, BAD_TEAR = 64;
    public static final Type<BodySync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "body"));
    public static final StreamCodec<ByteBuf, BodySync> CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, BodySync::thirst,
            ByteBufCodecs.FLOAT, BodySync::awake,
            ByteBufCodecs.FLOAT, BodySync::stamina,
            ByteBufCodecs.VAR_INT, BodySync::injuries,
            BodySync::new);

    /** The latest values the client got (read by the HUD). */
    public static volatile BodySync latest = new BodySync(0, 0, 100, 0);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
