package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Realism 4.8: bullying, like real life. A villager kid or teen at school (or in town) can start picking on you: mean
 * words, shoving, stealing your stuff, leaving you out. Each time it happens your mood drops.
 * <p>
 * How it stops, also like real life:
 * <ul>
 * <li>Friends: a friend (another player) standing next to you makes the bully back off. Three times and they give up.</li>
 * <li>Telling an adult (crouch + right-click a villager): teachers (librarians) and school counselors (clerics) have a
 * hidden attitude, and most of them just say "ignore them", or even make it worse by telling the bully who reported.
 * Only some really help. Other grown-ups (your neighbours, the people around you) help more often. Keep trying.</li>
 * <li>The helpline on your phone: anonymous, always answers, and always does something.</li>
 * </ul>
 * The police don't handle normal bullying. Players are never rewarded for bullying anyone.
 */
public final class Bullying {
    private Bullying() {
    }

    /** One player's bully (empty = none), how many times friends stood up for them, and timers (ticks). */
    public static class Data implements INBTSerializable<CompoundTag> {
        public String bully = "";
        public int standUps, untilNext = 60 * 20, worse, tellCooldown;

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider provider) {
            CompoundTag t = new CompoundTag();
            t.putString("bully", bully);
            t.putInt("standUps", standUps);
            t.putInt("untilNext", untilNext);
            t.putInt("worse", worse);
            return t;
        }

        @Override
        public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
            bully = t.getString("bully");
            standUps = t.getInt("standUps");
            untilNext = t.getInt("untilNext");
            worse = t.getInt("worse");
        }
    }

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Realism.MODID);
    public static final Supplier<AttachmentType<Data>> DATA = ATTACHMENTS.register("bullying", () -> AttachmentType.serializable(Data::new).build());

    static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    public static Data data(Player p) {
        return p.getData(DATA);
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.bully." + key, args), true);
    }

    private static void tell(Player p, String key, ChatFormatting color) {
        p.displayClientMessage(Component.translatable("realism.bully." + key).withStyle(color), false);
    }

    public static boolean bullied(Player p) {
        return !data(p).bully.isEmpty();
    }

    // ---- Starting ----

    /** A villager near you becomes your bully (if you don't have one). Returns whether it happened. */
    public static boolean start(ServerPlayer p, Villager v) {
        if (!RealismConfig.BULLYING.get() || bullied(p) || v == null) return false;
        Data d = data(p);
        d.bully = v.getUUID().toString();
        d.standUps = 0;
        d.untilNext = 20 * 20;
        d.worse = 0;
        tell(p, "started", ChatFormatting.GRAY);
        return true;
    }

    /** At school (studying at a desk): sometimes a kid starts picking on you. */
    public static void atSchool(ServerPlayer p) {
        if (!RealismConfig.BULLYING.get() || bullied(p) || p.getRandom().nextFloat() >= 0.2f) return;
        Villager v = nearestVillager(p, 16, null);
        if (v != null) start(p, v);
    }

    private static Villager nearestVillager(ServerPlayer p, double range, UUID not) {
        return p.level().getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(range),
                        v -> v.isAlive() && !v.getUUID().equals(not) && v.getVillagerData().getProfession() != Hospital.DOCTOR.get())
                .stream().min((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p))).orElse(null);
    }

    public static Villager bully(ServerPlayer p) {
        Data d = data(p);
        if (d.bully.isEmpty() || !(p.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(UUID.fromString(d.bully));
        return e instanceof Villager v && v.isAlive() ? v : null;
    }

    // ---- Every second ----

    static void second(ServerPlayer p) {
        if (!RealismConfig.BULLYING.get()) return;
        Data d = data(p);
        if (d.tellCooldown > 0) d.tellCooldown -= 20;
        if (d.worse > 0) d.worse -= 20;
        if (d.bully.isEmpty()) {
            // Now and then, in a busy village, someone starts picking on you.
            if (p.getRandom().nextInt(20 * 60 * 30) == 0 && p.level().getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(16)).size() >= 3)
                start(p, nearestVillager(p, 16, null));
            return;
        }
        Villager v = bully(p);
        if (v == null) {
            // Loaded somewhere else, or gone for good (a bully that died is gone).
            if (p.level() instanceof ServerLevel sl && sl.getEntity(UUID.fromString(d.bully)) == null && p.getRandom().nextInt(600) == 0) stop(p, null);
            return;
        }
        if (v.distanceTo(p) > 12) return;
        d.untilNext -= 20;
        if (d.untilNext > 0) return;
        d.untilNext = (d.worse > 0 ? 30 : 60 + p.getRandom().nextInt(60)) * 20;
        // A friend standing with you: the bully backs off.
        boolean friend = !p.level().getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(5), o -> o != p && o.isAlive() && !o.isSpectator()).isEmpty();
        if (friend) {
            d.standUps++;
            say(p, "friend");
            Mind.lift(p, 3);
            if (d.standUps >= 3) stop(p, "gave_up");
            return;
        }
        incident(p, v);
    }

    private static void incident(ServerPlayer p, Villager v) {
        switch (p.getRandom().nextInt(4)) {
            case 0 -> {
                say(p, "mean." + p.getRandom().nextInt(5));
                Mind.lift(p, -4);
            }
            case 1 -> {
                // A shove.
                double dx = p.getX() - v.getX(), dz = p.getZ() - v.getZ();
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                p.push(dx / len * 0.6, 0.2, dz / len * 0.6);
                p.hurtMarked = true;
                say(p, "shove");
                Mind.lift(p, -3);
            }
            case 2 -> {
                // Steals something (they give it back when it stops).
                int slot = -1;
                for (int i = 9; i < 36; i++) {
                    if (!p.getInventory().getItem(i).isEmpty()) {
                        slot = i;
                        if (p.getRandom().nextBoolean()) break;
                    }
                }
                if (slot >= 0 && v.getInventory().canAddItem(p.getInventory().getItem(slot))) {
                    ItemStack stolen = p.getInventory().removeItem(slot, 1);
                    v.getInventory().addItem(stolen);
                    say(p, "stole", stolen.getHoverName());
                } else say(p, "mean." + p.getRandom().nextInt(5));
                Mind.lift(p, -4);
            }
            default -> {
                say(p, "left_out");
                Mind.lift(p, -3);
            }
        }
    }

    /** The bullying ends: the bully gives back what they took. {@code why} is the message key (null = say nothing). */
    public static void stop(ServerPlayer p, String why) {
        Villager v = bully(p);
        if (v != null) {
            for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
                ItemStack s = v.getInventory().getItem(i);
                if (!s.isEmpty() && !p.getInventory().add(s.copy())) p.drop(s.copy(), false);
                v.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        Data d = data(p);
        d.bully = "";
        d.standUps = 0;
        d.worse = 0;
        if (why != null) {
            tell(p, why, ChatFormatting.GREEN);
            Mind.lift(p, 10);
        }
    }

    // ---- Telling someone ----

    /** What kind of adult a villager is, for telling. */
    public enum Role {TEACHER, COUNSELOR, ADULT}

    public static Role role(Villager v) {
        VillagerProfession job = v.getVillagerData().getProfession();
        if (job == VillagerProfession.LIBRARIAN) return Role.TEACHER;
        if (job == VillagerProfession.CLERIC) return Role.COUNSELOR;
        return Role.ADULT;
    }

    /**
     * Each adult has a hidden attitude that never changes (from their UUID). Teachers and counselors: 6 in 10 brush it
     * off, 2 in 10 make it worse, 2 in 10 help. Other grown-ups: half of them help.
     */
    public enum Reaction {HELP, IGNORE, WORSE}

    public static Reaction attitude(Villager v) {
        int roll = Math.floorMod(v.getUUID().hashCode(), 10);
        if (role(v) == Role.ADULT) return roll < 5 ? Reaction.HELP : Reaction.IGNORE;
        return roll < 6 ? Reaction.IGNORE : roll < 8 ? Reaction.WORSE : Reaction.HELP;
    }

    @SubscribeEvent
    public static void onTell(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !p.isShiftKeyDown() || !(event.getTarget() instanceof Villager v)) return;
        if (!RealismConfig.BULLYING.get() || !bullied(p) || !p.getMainHandItem().isEmpty()) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        tellAdult(p, v);
    }

    public static Reaction tellAdult(ServerPlayer p, Villager v) {
        Data d = data(p);
        if (v.getUUID().toString().equals(d.bully)) {
            say(p, "tell.bully");
            return Reaction.IGNORE;
        }
        if (d.tellCooldown > 0) {
            say(p, "tell.wait");
            return Reaction.IGNORE;
        }
        d.tellCooldown = 30 * 20;
        Role role = role(v);
        Reaction r = attitude(v);
        String who = role.name().toLowerCase(java.util.Locale.ROOT);
        switch (r) {
            case HELP -> stop(p, "tell." + who + ".help");
            case WORSE -> {
                d.worse = 10 * 60 * 20;
                d.untilNext = 10 * 20;
                tell(p, "tell." + who + ".worse", ChatFormatting.RED);
                Mind.lift(p, -5);
            }
            default -> tell(p, "tell." + who + ".ignore", ChatFormatting.GRAY);
        }
        return r;
    }

    /** The helpline: anonymous, always answers, always does something. */
    public static void helpline(ServerPlayer p) {
        if (PhoneOS.phoneOf(p).isEmpty() || !PhoneOS.canCall(p)) {
            p.displayClientMessage(Component.translatable("realism.phoneos.no_service").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!bullied(p)) {
            p.displayClientMessage(Component.translatable("realism.bully.helpline.talk").withStyle(ChatFormatting.AQUA), false);
            Mind.lift(p, 5);
            return;
        }
        stop(p, "helpline.help");
    }

    public record CallHelpline() implements CustomPacketPayload {
        public static final Type<CallHelpline> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "call_helpline"));
        public static final StreamCodec<ByteBuf, CallHelpline> CODEC = StreamCodec.unit(new CallHelpline());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
