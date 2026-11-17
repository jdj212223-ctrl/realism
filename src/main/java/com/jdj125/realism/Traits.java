package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Realism 4.8: who you are. On your first join you pick ONE trait, and it stays with you (even after death). Each
 * trait has perks and flaws, like real life. A Change Trait item (single use) lets you pick again.
 * <p>
 * "Living With Depression" is handled with care: it brings bad days (low energy, mood sinks), and the things that help
 * in real life help here too: friends nearby, sunlight, sleep and therapy (each therapy session makes bad days rarer).
 * There is never any self-harm, and server owners can turn the trait off.
 */
public final class Traits {
    private Traits() {
    }

    public enum Trait {
        AVERAGE("average"), PARAMEDIC("paramedic"), DRONE_PILOT("drone_pilot"), NIGHT_SHIFT("night_shift"),
        IRON_STOMACH("iron_stomach"), DEPRESSION("depression"), ANXIOUS("anxious");

        public final String id;

        Trait(String id) {
            this.id = id;
        }

        public static Trait byId(String id) {
            return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst().orElse(null);
        }
    }

    /** The traits a player may pick right now (the server can switch the depression trait off). */
    public static List<Trait> available(boolean depression) {
        return Arrays.stream(Trait.values()).filter(t -> depression || t != Trait.DEPRESSION).toList();
    }

    /** What we remember about a player's trait. Kept through death, like your blood type. */
    public static class Data implements INBTSerializable<CompoundTag> {
        /** The trait id, or "" while not chosen yet. */
        public String trait = "";
        /** Depression: the Minecraft day we last rolled for a bad day, whether today is one, and therapy sessions so far. */
        public long day = -1;
        public boolean badDay;
        public int therapy;
        /** Ticks until the next uneasy feeling / panic attack (anxious) or woozy spell (paramedic). Not saved. */
        public int cooldown;

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider provider) {
            CompoundTag t = new CompoundTag();
            t.putString("trait", trait);
            t.putLong("day", day);
            t.putBoolean("badDay", badDay);
            t.putInt("therapy", therapy);
            return t;
        }

        @Override
        public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
            trait = t.getString("trait");
            day = t.getLong("day");
            badDay = t.getBoolean("badDay");
            therapy = t.getInt("therapy");
        }
    }

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Realism.MODID);
    public static final Supplier<AttachmentType<Data>> DATA = ATTACHMENTS.register("trait",
            () -> AttachmentType.serializable(Data::new).copyOnDeath().build());

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    public static final DeferredItem<Item> CHANGE_TRAIT = ITEMS.register("change_trait", () -> new ChangeTrait(new Item.Properties().stacksTo(1)));

    static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        ITEMS.register(modBus);
    }

    public static Data data(Player p) {
        return p.getData(DATA);
    }

    public static Trait trait(Player p) {
        if (!RealismConfig.TRAITS.get()) return Trait.AVERAGE;
        Trait t = Trait.byId(data(p).trait);
        if (t == Trait.DEPRESSION && !RealismConfig.DEPRESSION_TRAIT.get()) return Trait.AVERAGE;
        return t == null ? Trait.AVERAGE : t;
    }

    public static boolean is(Player p, Trait t) {
        return trait(p) == t;
    }

    private static void say(Player p, String key) {
        p.displayClientMessage(Component.translatable("realism.trait." + key), true);
    }

    // ---- Choosing ----

    /** Opens the trait screen for a player who hasn't picked one yet (after the world's era is chosen). */
    public static void prompt(ServerPlayer p) {
        if (!RealismConfig.TRAITS.get() || !data(p).trait.isEmpty() || p.getServer() == null) return;
        if (!Era.Data.get(p.getServer()).chosen && Era.mayChoose(p)) return; // the era screen comes first
        if (p.connection.hasChannel(OpenTraits.TYPE)) PacketDistributor.sendToPlayer(p, new OpenTraits(RealismConfig.DEPRESSION_TRAIT.get()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) prompt(p);
    }

    public static void handleChoice(ServerPlayer p, String id) {
        Trait t = Trait.byId(id);
        Data d = data(p);
        if (t == null || !d.trait.isEmpty() || !RealismConfig.TRAITS.get()) return;
        if (t == Trait.DEPRESSION && !RealismConfig.DEPRESSION_TRAIT.get()) return;
        d.trait = t.id;
        d.day = -1;
        d.badDay = false;
        if (t == Trait.DEPRESSION) BodyEvents.body(p).mood = Math.min(BodyEvents.body(p).mood, 45);
        p.displayClientMessage(Component.translatable("realism.trait.chosen", Component.translatable("realism.trait." + t.id + ".name")), false);
    }

    /** Called when a therapy voucher is used: for the depression trait, each session makes bad days rarer. */
    public static void therapy(Player p) {
        if (!is(p, Trait.DEPRESSION)) return;
        Data d = data(p);
        d.therapy++;
        d.badDay = false;
        p.displayClientMessage(Component.translatable("realism.trait.depression.therapy"), false);
    }

    /** Chance that a new day is a bad day: 45%, 7% less per therapy session, never below 10%. */
    public static float badDayChance(Data d) {
        return Math.max(0.10f, 0.45f - 0.07f * d.therapy);
    }

    // ---- Every second ----

    /** One second of the player's trait (from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        Trait t = trait(p);
        Data d = data(p);
        if (d.cooldown > 0) d.cooldown -= 20;
        Level level = p.level();
        switch (t) {
            case PARAMEDIC -> {
                // Perk: you know how to look after yourself: light bleeding stops sooner, blood comes back faster.
                if (b.bleeding == 1 && p.getRandom().nextInt(20) == 0) b.bleeding = 0;
                if (b.blood < 5 && b.bleeding == 0) b.blood = Math.min(5, b.blood + 0.002f);
                // Flaw: seeing someone bleed badly makes you woozy.
                if (d.cooldown <= 0 && level.getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(6),
                        o -> o != p && o.isAlive() && BodyEvents.body(o).bleeding >= 2).size() > 0 && p.getRandom().nextInt(15) == 0) {
                    p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false));
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, true, false));
                    say(p, "paramedic.woozy");
                    d.cooldown = 90 * 20;
                }
            }
            case DRONE_PILOT -> {
                // Flaw: motion sickness in fast cars and boats.
                if (p.getVehicle() != null && p.getVehicle().getDeltaMovement().horizontalDistance() > 0.4
                        && (p.getVehicle() instanceof Boat || p.getVehicle() instanceof Cars.Car) && p.getRandom().nextInt(25) == 0) {
                    p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 140, 0, true, false));
                    if (d.cooldown <= 0) {
                        say(p, "drone_pilot.carsick");
                        d.cooldown = 60 * 20;
                    }
                }
            }
            case NIGHT_SHIFT -> {
                // Perk: at night you need less sleep. Flaw: the daylight wears you out.
                if (!level.isDay() && !p.isSleeping()) b.awake = Math.max(0, b.awake - 7);
                if (level.isDay() && level.canSeeSky(p.blockPosition())) b.stamina = Math.max(0, b.stamina - 2);
            }
            case IRON_STOMACH -> {
                // Perk: food poisoning doesn't stick. Flaw: you get thirsty faster.
                if (b.foodPoisoning > 0) b.foodPoisoning = 0;
                b.thirst += 5;
            }
            case DEPRESSION -> depression(p, b, d);
            case ANXIOUS -> anxious(p, b, d);
            default -> {
            }
        }
    }

    private static void depression(ServerPlayer p, Body b, Data d) {
        Level level = p.level();
        long day = level.getDayTime() / 24000;
        if (day != d.day) {
            d.day = day;
            d.badDay = p.getRandom().nextFloat() < badDayChance(d);
            say(p, d.badDay ? "depression.bad_day" : "depression.good_day");
        }
        if (!d.badDay) return;
        boolean friend = !level.getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(8), o -> o != p && o.isAlive() && !o.isSpectator()).isEmpty();
        boolean sun = level.isDay() && level.canSeeSky(p.blockPosition());
        // A bad day: the mood sinks and the energy is low. Friends and sunlight push back, like real life.
        float drift = -0.03f + (friend ? 0.04f : 0) + (sun ? 0.015f : 0);
        b.mood = Math.max(0, Math.min(100, b.mood + drift));
        b.stamina = Math.max(0, b.stamina - 1.5f);
    }

    private static void anxious(ServerPlayer p, Body b, Data d) {
        Level level = p.level();
        var near = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(20), m -> m instanceof Enemy && m.isAlive());
        if (near.isEmpty() || d.cooldown > 0) return;
        boolean close = near.stream().anyMatch(m -> m.distanceTo(p) < 10);
        if (close && (p.getHealth() < 8 || b.mood < Mind.LOW) && !p.isShiftKeyDown()) {
            // Flaw: a panic attack. Crouching (slowing down and breathing) helps.
            p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, true, false));
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 0, true, false));
            b.stamina = Math.max(0, b.stamina - 30);
            say(p, "anxious.panic");
            d.cooldown = 120 * 20;
        } else {
            // Perk: you notice danger before you see it.
            say(p, "anxious.uneasy");
            d.cooldown = 30 * 20;
        }
    }

    /** Drone Pilot perk: 50% more range (for the first drones' range). */
    public static int droneRange(Player p) {
        return droneRange(p, Drone.RANGE);
    }

    /** Drone Pilot perk: 50% more range on top of a drone's own range. */
    public static int droneRange(Player p, int base) {
        return p != null && is(p, Trait.DRONE_PILOT) ? base * 3 / 2 : base;
    }

    /** Drone Pilot perk: 50% more battery. */
    public static int droneBattery(Player p, int battery) {
        return p != null && is(p, Trait.DRONE_PILOT) ? battery * 3 / 2 : battery;
    }

    // ---- The Change Trait item ----

    static class ChangeTrait extends Item {
        ChangeTrait(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(stack);
            if (!RealismConfig.TRAITS.get()) return InteractionResultHolder.fail(stack);
            data(sp).trait = "";
            stack.consume(1, player);
            prompt(sp);
            return InteractionResultHolder.consume(stack);
        }
    }

    // ---- Network ----

    public record OpenTraits(boolean depression) implements CustomPacketPayload {
        public static final Type<OpenTraits> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "open_traits"));
        public static final StreamCodec<ByteBuf, OpenTraits> CODEC = ByteBufCodecs.BOOL.map(OpenTraits::new, OpenTraits::depression);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ChooseTrait(String id) implements CustomPacketPayload {
        public static final Type<ChooseTrait> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "choose_trait"));
        public static final StreamCodec<ByteBuf, ChooseTrait> CODEC = ByteBufCodecs.STRING_UTF8.map(ChooseTrait::new, ChooseTrait::id);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
