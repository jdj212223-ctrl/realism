package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Realism 4.9: you can SEE how someone is doing. Wounds, bandages, tourniquets, bruises that change colour as they
 * heal, burns, frostbitten fingers, a pale face when you've lost blood, dark eye bags when you haven't slept, sweat
 * when you're exhausted, and dark wet pants after an accident. Everyone nearby sees it on your skin (the client can
 * switch it to a mild look: bandages and colour only, no cuts).
 * <p>
 * Each second the server packs what shows into a few bits and sends it to you and everyone who can see you, only when
 * it changed.
 */
public final class BodyLook {
    private BodyLook() {
    }

    public static final int BLEEDING_MASK = 3, TOURNIQUET = 1 << 2, BANDAGE = 1 << 3, LEG_CAST = 1 << 4, ARM_TEAR = 1 << 5,
            BURN_SHIFT = 6, FROSTBITE = 1 << 8, PALE = 1 << 9, EYE_BAGS = 1 << 10, SWEAT = 1 << 11, WET = 1 << 12, BRUISE_SHIFT = 13;
    /** How long a bruise lasts: 3 Minecraft days. Red at first, then purple, then yellow. */
    static long bruiseTicks() {
        return RealismConfig.dayTicks() * 3;
    }

    /** What shows on this player's skin right now, packed into bits. */
    public static int flags(Player p, Body b) {
        int f = Math.min(3, b.bleeding);
        if (b.tourniquet > 0) f |= TOURNIQUET;
        if (b.bandaged > 0) f |= BANDAGE;
        if (b.brokenLeg > 0) f |= LEG_CAST;
        if (b.armTear > 0) f |= ARM_TEAR;
        if (b.burn > 0) f |= Math.min(3, Math.max(1, b.burnDegree)) << BURN_SHIFT;
        if (b.frostbite > 0) f |= FROSTBITE;
        if (b.blood < 4.2f) f |= PALE;
        if (b.sleepStage >= 1) f |= EYE_BAGS;
        if (b.stamina < 25 || b.overexertion > 0) f |= SWEAT;
        if (b.wet > 0) f |= WET;
        if (b.bruise > 0) {
            float left = b.bruise / (float) bruiseTicks();
            f |= (left > 0.66f ? 1 : left > 0.33f ? 2 : 3) << BRUISE_SHIFT;
        }
        return f;
    }

    public static int bleeding(int f) {
        return f & BLEEDING_MASK;
    }

    public static int burn(int f) {
        return (f >> BURN_SHIFT) & 3;
    }

    /** 0 none, 1 fresh (red), 2 healing (purple), 3 almost gone (yellow). */
    public static int bruise(int f) {
        return (f >> BRUISE_SHIFT) & 3;
    }

    /** One second: timers tick down, and the look is sent if it changed (from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        if (b.bandaged > 0) b.bandaged = b.bleeding > 0 ? 0 : Math.max(0, b.bandaged - 20);
        if (b.bruise > 0) b.bruise = Math.max(0, b.bruise - 20);
        if (b.wet > 0) b.wet = Math.max(0, b.wet - (p.level().isDay() && p.level().canSeeSky(p.blockPosition()) ? 40 : 20));
        int f = flags(p, b);
        if (f != b.lastLook) {
            b.lastLook = f;
            Look look = new Look(p.getId(), f);
            // Only to players who have Realism (others can't read it) and are close enough to see you.
            for (ServerPlayer o : p.serverLevel().players())
                if ((o == p || o.distanceToSqr(p) < 160 * 160) && o.connection.hasChannel(Look.TYPE)) PacketDistributor.sendToPlayer(o, look);
        }
    }

    /** Someone comes into view: send them how you look right now. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer seen && event.getEntity() instanceof ServerPlayer viewer && viewer.connection.hasChannel(Look.TYPE))
            PacketDistributor.sendToPlayer(viewer, new Look(seen.getId(), flags(seen, BodyEvents.body(seen))));
    }

    /** Blunt hits (falls, punches, getting hit by things) leave a bruise. */
    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide || event.getNewDamage() < 2) return;
        var src = event.getSource();
        boolean blunt = src.is(DamageTypes.FALL) || src.is(DamageTypes.FLY_INTO_WALL) || src.is(DamageTypes.FALLING_BLOCK)
                || src.is(DamageTypes.FALLING_ANVIL) || src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.PLAYER_ATTACK)
                || src.is(DamageTypeTags.IS_EXPLOSION);
        if (blunt) BodyEvents.body(p).bruise = (int) Math.min(Integer.MAX_VALUE, bruiseTicks());
    }

    public record Look(int entity, int flags) implements CustomPacketPayload {
        public static final Type<Look> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "body_look"));
        public static final StreamCodec<ByteBuf, Look> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Look::entity,
                ByteBufCodecs.VAR_INT, Look::flags, Look::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
