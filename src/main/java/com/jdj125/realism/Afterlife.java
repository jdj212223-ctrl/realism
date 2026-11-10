package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Realism 2.0, the Afterlife. When you're gone for good there's no floating ghost any more (that isn't real):
 * <ul>
 * <li><b>Judgment</b>: a short cutscene (camera locked in third person, F5 does nothing) while your life is judged.</li>
 * <li><b>Hell</b> 🔥 for murderers and for surgery on someone who didn't agree (a conscious patient who wasn't
 * crouching to say yes). Killing someone who attacked you first is self-defense and doesn't count.</li>
 * <li><b>Heaven</b> ☁️ for anyone who saved a life (defib, surgery) or only killed in self-defense, and a 10% chance
 * for everyone else.</li>
 * <li><b>The dark</b> ⬛ for everyone else: you see nothing at all, like real death.</li>
 * </ul>
 * The record God reads is kept everywhere (not only inside countries, and not only if someone saw it). After a while
 * in the afterlife you're born again as a new person (new blood type, clean record) at the world spawn.
 */
public final class Afterlife {
    private Afterlife() {}

    public static final ResourceKey<Level> HEAVEN = dim("heaven"), HELL = dim("hell"), DARK = dim("dark");
    public static final int CUTSCENE_TICKS = 200;

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Realism.MODID, name));
    }

    // ---- The record of a life (survives death, works for offline players too) ----

    public static final class Soul {
        public int murders, defense, noConsent, saves, lies;
        /** "" = alive; "judgment", "heaven", "hell" or "dark". */
        public String place = "";
        public long until;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("murders", murders);
            t.putInt("defense", defense);
            t.putInt("noConsent", noConsent);
            t.putInt("saves", saves);
            t.putInt("lies", lies);
            t.putString("place", place);
            t.putLong("until", until);
            return t;
        }

        static Soul load(CompoundTag t) {
            Soul s = new Soul();
            s.murders = t.getInt("murders");
            s.defense = t.getInt("defense");
            s.noConsent = t.getInt("noConsent");
            s.saves = t.getInt("saves");
            s.lies = t.getInt("lies");
            s.place = t.getString("place");
            s.until = t.getLong("until");
            return s;
        }
    }

    public static class Data extends SavedData {
        final Map<UUID, Soul> souls = new HashMap<>();

        public static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Data::new, Data::load), "realism_afterlife");
        }

        static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data d = new Data();
            for (String k : tag.getAllKeys()) {
                try {
                    d.souls.put(UUID.fromString(k), Soul.load(tag.getCompound(k)));
                } catch (IllegalArgumentException ignored) {}
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            souls.forEach((who, s) -> tag.put(who.toString(), s.save()));
            return tag;
        }
    }

    public static Soul soul(MinecraftServer server, UUID who) {
        Data d = Data.get(server);
        d.setDirty();
        return d.souls.computeIfAbsent(who, k -> new Soul());
    }

    /** A player killed another player (or a villager). Self-defense: the victim hurt the killer in the last minute. */
    public static void killed(MinecraftServer server, UUID killer, net.minecraft.world.entity.LivingEntity victim) {
        Soul s = soul(server, killer);
        ServerPlayer k = server.getPlayerList().getPlayer(killer);
        boolean defense = false;
        if (k != null && victim instanceof ServerPlayer) {
            Body kb = BodyEvents.body(k);
            defense = victim.getUUID().toString().equals(kb.attacker) && k.level().getGameTime() - kb.attackerTime < 60 * 20;
        }
        if (defense) s.defense++;
        else s.murders++;
    }

    /** Surgery on a conscious patient who isn't crouching (= didn't say yes). Returns true if it had no consent. */
    public static boolean surgery(ServerPlayer surgeon, ServerPlayer patient) {
        if (Rescue.collapsed(patient) || patient.isShiftKeyDown()) return false;
        soul(surgeon.server, surgeon.getUUID()).noConsent++;
        surgeon.displayClientMessage(Component.translatable("realism.afterlife.no_consent", patient.getName()), false);
        return true;
    }

    /** Someone saved a life (defib, or surgery on a collapsed patient). */
    public static void saved(ServerPlayer saver) {
        soul(saver.server, saver.getUUID()).saves++;
    }

    /**
     * Where this soul goes. Hell: murder, surgery without consent, or 3+ lies. Heaven: saved a life, self-defense only,
     * or 10% luck, but never for a liar. Everyone else: the dark.
     */
    public static String verdict(Soul s, float roll) {
        if (s.murders > 0 || s.noConsent > 0 || s.lies >= 3) return "hell";
        if (s.lies == 0 && (s.saves > 0 || s.defense > 0 || roll < RealismConfig.HEAVEN_CHANCE.get())) return "heaven";
        return "dark";
    }

    // ---- Lies ----

    /** "It wasn't me", "I'm innocent", "nebyl jsem to"... */
    private static final java.util.regex.Pattern DENIAL = java.util.regex.Pattern.compile(
            "(wasn'?t me|was not me|not me|i didn'?t do|i did not do|i'?m innocent|i am innocent|i didn'?t kill|i did not kill"
                    + "|i didn'?t steal|i never|nebyl jsem to|nebyla jsem to|to jsem nebyl|to jsem nebyla|já ne\\b|jsem nevinn|nezabil jsem|nezabila jsem|neukradl|neukradla)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** Did this player commit something they could lie about? Murder, no-consent surgery, or any crime on record. */
    static boolean guilty(ServerPlayer p) {
        Soul s = soul(p.server, p.getUUID());
        return s.murders > 0 || s.noConsent > 0 || !Justice.Data.get(p.serverLevel()).records.getOrDefault(p.getUUID(), java.util.List.of()).isEmpty();
    }

    /** Someone said (chat) or texted (phone) something. Denying what you did is a lie, and it's written down up there. */
    public static boolean said(ServerPlayer p, String text) {
        if (!DENIAL.matcher(text).find() || !guilty(p)) return false;
        soul(p.server, p.getUUID()).lies++;
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChat(net.neoforged.neoforge.event.ServerChatEvent event) {
        said(event.getPlayer(), event.getRawText());
    }

    public static boolean inAfterlife(ServerPlayer p) {
        return !soul(p.server, p.getUUID()).place.isEmpty();
    }

    // ---- Judgment ----

    public record Judgment(String verdict, int ticks) implements CustomPacketPayload {
        public static final Type<Judgment> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "judgment"));
        public static final StreamCodec<ByteBuf, Judgment> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Judgment::verdict, ByteBufCodecs.VAR_INT, Judgment::ticks, Judgment::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Called when someone gone for good respawns: starts the judgment cutscene. */
    public static void judge(ServerPlayer p) {
        Soul s = soul(p.server, p.getUUID());
        String v = verdict(s, p.getRandom().nextFloat());
        s.place = "judgment:" + v;
        s.until = p.server.overworld().getGameTime() + CUTSCENE_TICKS;
        p.setGameMode(GameType.ADVENTURE);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, CUTSCENE_TICKS + 20, 10, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, CUTSCENE_TICKS + 20, 0, false, false));
        if (v.equals("heaven")) p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, CUTSCENE_TICKS, 0, false, false));
        if (p.connection != null && p.connection.hasChannel(Judgment.TYPE)) PacketDistributor.sendToPlayer(p, new Judgment(v, CUTSCENE_TICKS));
        p.level().playSound(null, p.blockPosition(), v.equals("hell") ? SoundEvents.WITHER_SPAWN : SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6f, 0.7f);
    }

    /** After the cutscene: into heaven, hell or the dark, until it's time to be born again. */
    public static void arrive(ServerPlayer p, Soul s, String v) {
        s.place = v;
        int minutes = switch (v) {
            case "heaven" -> RealismConfig.HEAVEN_MINUTES.get();
            case "hell" -> RealismConfig.HELL_MINUTES.get();
            default -> RealismConfig.DARK_MINUTES.get();
        };
        s.until = RealismConfig.REBORN.get() ? p.server.overworld().getGameTime() + minutes * 60L * 20 : Long.MAX_VALUE;
        ServerLevel to = p.server.getLevel(v.equals("heaven") ? HEAVEN : v.equals("hell") ? HELL : DARK);
        if (to == null) {
            reborn(p, s);
            return;
        }
        BlockPos spot = new BlockPos(0, groundY(v), 0);
        // A small landing pad, so nobody falls through an unloaded or broken floor.
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                if (to.getBlockState(spot.offset(x, -1, z)).isAir())
                    to.setBlockAndUpdate(spot.offset(x, -1, z), v.equals("heaven") ? Blocks.SNOW_BLOCK.defaultBlockState()
                            : v.equals("hell") ? Blocks.NETHERRACK.defaultBlockState() : Blocks.BARRIER.defaultBlockState());
        p.removeAllEffects();
        p.teleportTo(to, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, p.getYRot(), 0);
        p.setGameMode(GameType.ADVENTURE);
        p.displayClientMessage(RealismConfig.REBORN.get() ? Component.translatable("realism.afterlife.arrive." + v, minutes)
                : Component.translatable("realism.afterlife.forever." + v), false);
    }

    static int groundY(String v) {
        return v.equals("dark") ? 1 : 4;
    }

    /** Born again: a new person at the world spawn, with a new body and a clean record. */
    public static void reborn(ServerPlayer p, Soul s) {
        s.place = "";
        s.until = 0;
        s.murders = s.defense = s.noConsent = s.saves = 0;
        ServerLevel world = p.server.overworld();
        BlockPos spawn = world.getSharedSpawnPos();
        p.removeAllEffects();
        p.teleportTo(world, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, p.getYRot(), 0);
        p.setGameMode(GameType.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setData(Realism.BLOOD_TYPE, "");
        p.setData(Realism.BODY, new Body());
        p.displayClientMessage(Component.translatable("realism.afterlife.reborn"), false);
    }

    @SubscribeEvent
    public static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        MinecraftServer s = event.getServer();
        com.mojang.logging.LogUtils.getLogger().info("Realism afterlife: heaven={} hell={} dark={}",
                s.getLevel(HEAVEN) != null, s.getLevel(HELL) != null, s.getLevel(DARK) != null);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        Soul s = Data.get(p.server).souls.get(p.getUUID());
        boolean inOurDim = p.level().dimension() == HEAVEN || p.level().dimension() == HELL || p.level().dimension() == DARK;
        if (s == null || s.place.isEmpty()) {
            // Somehow in the afterlife without a reason (a command, an old save): back to the living.
            if (inOurDim) reborn(p, s == null ? soul(p.server, p.getUUID()) : s);
            return;
        }
        long now = p.server.overworld().getGameTime();
        if (s.place.startsWith("judgment:")) {
            if (now >= s.until) arrive(p, s, s.place.substring("judgment:".length()));
            return;
        }
        if (now >= s.until) {
            reborn(p, s);
            return;
        }
        // Wandered into the wrong world (a /tp?): back to where this soul belongs.
        if (!inOurDim) {
            arrive(p, s, s.place);
            return;
        }
        if (s.until == Long.MAX_VALUE) p.displayClientMessage(Component.translatable("realism.afterlife.place." + s.place), true);
        else {
            long left = (s.until - now) / 20;
            p.displayClientMessage(Component.translatable("realism.afterlife.timer." + s.place, left / 60, String.format("%02d", left % 60)), true);
        }
        switch (s.place) {
            case "dark" -> {
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
            }
            case "hell" -> {
                if (RealismConfig.HELL_BURNS.get()) p.igniteForSeconds(2);
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1, false, false));
            }
            case "heaven" -> p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false));
            default -> {}
        }
    }

    // Nobody dies twice: in the afterlife you can't die, and in heaven nothing hurts.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && inAfterlife(p)) {
            event.setCanceled(true);
            p.setHealth(1);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !inAfterlife(p)) return;
        Soul s = soul(p.server, p.getUUID());
        if (!s.place.equals("hell")) event.setCanceled(true);
    }

    // Used by /realism afterlife (testing and admins).
    public static Tag describe(ServerPlayer p) {
        return soul(p.server, p.getUUID()).save();
    }
}
