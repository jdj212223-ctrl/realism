package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1.0.0: eras. The first time an operator opens a world with Realism, they pick where history starts:
 * <ul>
 * <li><b>Year 0</b>: the start of history. Everything has to be invented, and the first time anyone in the world makes
 * an invention (a book, a clock, a Generator, a Medical Station, a TV...) the year jumps forward to when it was
 * invented in real life. Until the TV is invented the world is black and white; until modern medicine (a Medical
 * Station) is invented there's no surgery, defibrillators or transfusions, you get hurt worse and you have half as
 * long to be saved when you collapse; everybody wears fancy period clothes; and the law can sentence criminals to
 * death ({@link Justice}).</li>
 * <li><b>2026</b>: today. Everything is already invented.</li>
 * </ul>
 */
public final class Era {
    private static final DeferredRegister<ArmorMaterial> MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final int YEAR_0 = 0, TODAY = 2026;
    /** The year modern medicine arrives (surgery, defibs, transfusions), and the year colour comes back (colour TV). */
    public static final int MEDICINE = 1900, COLOUR = 1950;

    /** An invention: the item that invents it, its real-life year, and its name key (realism.invention.<key>). */
    public record Invention(String item, int year, String key) {
    }

    public static final List<Invention> INVENTIONS = List.of(
            new Invention("minecraft:book", 1450, "printing"),
            new Invention("minecraft:compass", 1600, "compass"),
            new Invention("minecraft:clock", 1700, "clock"),
            new Invention("realism:generator", 1880, "electricity"),
            new Invention("realism:medical_station", 1900, "medicine"),
            new Invention("realism:ambulance", 1930, "ambulance"),
            new Invention("realism:television", 1950, "television"),
            new Invention("realism:pc_case", 1980, "computer"),
            new Invention("realism:phone", 2007, "smartphone"),
            new Invention("realism:data_center", 2026, "ai"));

    // ---- Fancy period clothes ----

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> FANCY = MATERIALS.register("fancy", () -> new ArmorMaterial(
            Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
                m.put(ArmorItem.Type.BOOTS, 1);
                m.put(ArmorItem.Type.LEGGINGS, 1);
                m.put(ArmorItem.Type.CHESTPLATE, 2);
                m.put(ArmorItem.Type.HELMET, 1);
                m.put(ArmorItem.Type.BODY, 2);
            }), 12, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.WHITE_WOOL),
            List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "fancy"))), 0, 0));
    public static final DeferredItem<Item> TOP_HAT = clothes("top_hat", ArmorItem.Type.HELMET);
    public static final DeferredItem<Item> TAILCOAT = clothes("tailcoat", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<Item> FANCY_TROUSERS = clothes("fancy_trousers", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<Item> BUCKLED_SHOES = clothes("buckled_shoes", ArmorItem.Type.BOOTS);

    private static DeferredItem<Item> clothes(String name, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new ArmorItem(FANCY, type, new Item.Properties().durability(type.getDurability(8))));
    }

    private Era() {
    }

    static void register(IEventBus modBus) {
        MATERIALS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Era::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(TOP_HAT.get());
            event.accept(TAILCOAT.get());
            event.accept(FANCY_TROUSERS.get());
            event.accept(BUCKLED_SHOES.get());
        }
    }

    // ---- The world's era ----

    public static class Data extends SavedData {
        public boolean chosen;
        public int year = TODAY;
        /** Where in the real world this world is (a country code like "CZ"), picked with the era. "" = not set. */
        public String country = "";
        public final Set<String> invented = new HashSet<>();

        public static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Data::new, Data::load), "realism_era");
        }

        static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data d = new Data();
            d.chosen = tag.getBoolean("chosen");
            d.year = tag.contains("year") ? tag.getInt("year") : TODAY;
            d.country = tag.getString("country");
            for (Tag t : tag.getList("invented", Tag.TAG_STRING)) d.invented.add(t.getAsString());
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putBoolean("chosen", chosen);
            tag.putInt("year", year);
            tag.putString("country", country);
            ListTag l = new ListTag();
            for (String s : invented) l.add(StringTag.valueOf(s));
            tag.put("invented", l);
            return tag;
        }
    }

    /** The world's current year (2026 until someone picks year 0). */
    public static int year(MinecraftServer server) {
        return server == null ? TODAY : Data.get(server).year;
    }

    public static int year(Player p) {
        return p.getServer() == null ? TODAY : year(p.getServer());
    }

    /** True before modern medicine is invented: no surgery, defibs or blood bags, and life is more dangerous. */
    public static boolean oldTimes(Player p) {
        return year(p) < MEDICINE;
    }

    /** Says why modern medicine doesn't work yet, and returns true, when it isn't invented. */
    public static boolean noMedicineYet(Player p) {
        if (!oldTimes(p)) return false;
        p.displayClientMessage(Component.translatable("realism.era.no_medicine", year(p)), true);
        return true;
    }

    /** Picks the era (an operator's choice, once). Year 0 dresses everyone online in period clothes. */
    public static void choose(MinecraftServer server, int startYear) {
        choose(server, startYear, "");
    }

    /** Country codes you can pick for a world (the client pre-selects the computer's own region). */
    public static final java.util.List<String> COUNTRIES = java.util.List.of("CZ", "SK", "DE", "AT", "PL", "HU", "GB", "IE", "FR", "ES", "PT",
            "IT", "NL", "BE", "CH", "DK", "SE", "NO", "FI", "UA", "RO", "HR", "SI", "GR", "TR", "US", "CA", "MX", "BR", "AR", "JP", "KR", "CN",
            "IN", "AU", "NZ", "ZA", "EG");

    public static String countryName(String code) {
        return code.isEmpty() ? "?" : java.util.Locale.of("", code).getDisplayCountry(java.util.Locale.ENGLISH);
    }

    /** Puts the world in a real country (a known code), and tells everyone. */
    public static boolean setCountry(MinecraftServer server, String code) {
        String c = code.toUpperCase(java.util.Locale.ROOT);
        if (!COUNTRIES.contains(c)) return false;
        Data d = Data.get(server);
        d.country = c;
        d.setDirty();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            p.displayClientMessage(Component.translatable("realism.era.country", countryName(c)), false);
        return true;
    }

    public static void choose(MinecraftServer server, int startYear, String country) {
        if (!country.isEmpty()) setCountry(server, country);
        Data d = Data.get(server);
        d.chosen = true;
        d.year = startYear;
        d.invented.clear();
        if (startYear >= TODAY) for (Invention i : INVENTIONS) d.invented.add(i.key());
        d.setDirty();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.displayClientMessage(Component.translatable(startYear < TODAY ? "realism.era.chose_0" : "realism.era.chose_today"), false);
            dress(p);
            sync(p);
        }
    }

    /** Someone made something: if it's an invention nobody has made yet, history moves forward. */
    public static void invent(ServerPlayer p, ItemStack made) {
        if (made.isEmpty() || p.getServer() == null) return;
        String id = BuiltInRegistries.ITEM.getKey(made.getItem()).toString();
        Data d = Data.get(p.getServer());
        for (Invention i : INVENTIONS) {
            if (!i.item().equals(id) || d.invented.contains(i.key())) continue;
            d.invented.add(i.key());
            boolean jumped = i.year() > d.year;
            if (jumped) d.year = i.year();
            d.setDirty();
            for (ServerPlayer o : p.getServer().getPlayerList().getPlayers()) {
                o.displayClientMessage(Component.translatable("realism.era.invented", p.getName(),
                        Component.translatable("realism.invention." + i.key()), d.year), false);
                if (jumped && i.year() == COLOUR) o.displayClientMessage(Component.translatable("realism.era.colour"), false);
                if (jumped && i.year() == MEDICINE) o.displayClientMessage(Component.translatable("realism.era.medicine"), false);
                sync(o);
            }
        }
    }

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) invent(p, event.getCrafting());
    }

    @SubscribeEvent
    public static void onSmelt(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) invent(p, event.getSmelting());
    }

    // ---- Joining: choose the era, dress for it ----

    /** The era choice screen, sent to the first operator who joins a world that hasn't picked one. */
    public record OpenChoice() implements CustomPacketPayload {
        public static final Type<OpenChoice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "era_choice"));
        public static final StreamCodec<ByteBuf, OpenChoice> CODEC = StreamCodec.unit(new OpenChoice());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The operator's answer: 0 or 2026. */
    public record Choose(int year, String country) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "era_choose"));
        public static final StreamCodec<ByteBuf, Choose> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Choose::year,
                ByteBufCodecs.stringUtf8(8), Choose::country, Choose::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void handleChoice(ServerPlayer p, int year, String country) {
        if (p.getServer() == null || !mayChoose(p) || Data.get(p.getServer()).chosen) return;
        choose(p.getServer(), year <= YEAR_0 ? YEAR_0 : TODAY, country);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.getServer() == null) return;
        Data d = Data.get(p.getServer());
        dress(p);
        sync(p);
    }

    /** The world's owner (singleplayer, even without cheats) or an operator picks the era. */
    static boolean mayChoose(ServerPlayer p) {
        return p.hasPermissions(2) || p.getServer().isSingleplayerOwner(p.getGameProfile());
    }

    // Ask for the era a few seconds after joining (not at login: the "loading terrain" screen would close it), and
    // again every 30 seconds until someone picks, in case the screen was closed some other way.
    @SubscribeEvent
    public static void askForEra(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.getServer() == null) return;
        if (p.tickCount < 60 || p.tickCount % 600 != 60) return;
        if (!Data.get(p.getServer()).chosen && mayChoose(p) && p.connection.hasChannel(OpenChoice.TYPE))
            PacketDistributor.sendToPlayer(p, new OpenChoice());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            dress(p);
            sync(p);
        }
    }

    /** In old times everyone starts each life in fancy period clothes (once per life, only into empty slots). */
    static void dress(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        if (b.dressed || year(p) >= MEDICINE || !Data.get(p.getServer()).chosen) return;
        b.dressed = true;
        put(p, EquipmentSlot.HEAD, TOP_HAT.get());
        put(p, EquipmentSlot.CHEST, TAILCOAT.get());
        put(p, EquipmentSlot.LEGS, FANCY_TROUSERS.get());
        put(p, EquipmentSlot.FEET, BUCKLED_SHOES.get());
    }

    private static void put(ServerPlayer p, EquipmentSlot slot, Item item) {
        if (p.getItemBySlot(slot).isEmpty()) p.setItemSlot(slot, new ItemStack(item));
        else if (!p.getInventory().add(new ItemStack(item))) p.drop(new ItemStack(item), false);
    }

    /** Wearing the whole fancy outfit feels nice. */
    static boolean fullyFancy(Player p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).is(TOP_HAT.get()) && p.getItemBySlot(EquipmentSlot.CHEST).is(TAILCOAT.get())
                && p.getItemBySlot(EquipmentSlot.LEGS).is(FANCY_TROUSERS.get()) && p.getItemBySlot(EquipmentSlot.FEET).is(BUCKLED_SHOES.get());
    }

    // Old times are brutal: without modern medicine, everything hurts more.
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && oldTimes(p) && Data.get(p.getServer()).chosen)
            event.setAmount(event.getAmount() * 1.5f);
    }

    /** How long a collapsed player can be saved, in seconds (half as long in old times). */
    public static int collapseSeconds(Player p) {
        int s = RealismConfig.COLLAPSE_SECONDS.get();
        return oldTimes(p) && p.getServer() != null && Data.get(p.getServer()).chosen ? Math.max(10, s / 2) : s;
    }

    // ---- Telling clients the year (for black and white vision and the HUD) ----

    static void sync(ServerPlayer p) {
        if (p.connection != null && p.connection.hasChannel(LifeSync.TYPE)) PacketDistributor.sendToPlayer(p, Health.sync(p));
    }

    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("era")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    Data d = Data.get(server);
                    ctx.getSource().sendSuccess(() -> Component.translatable(d.chosen ? "realism.era.info" : "realism.era.not_chosen", d.year), false);
                    if (!d.country.isEmpty()) ctx.getSource().sendSuccess(() -> Component.translatable("realism.era.country", countryName(d.country)), false);
                    for (Invention i : INVENTIONS)
                        ctx.getSource().sendSuccess(() -> Component.translatable(d.invented.contains(i.key()) ? "realism.era.done" : "realism.era.todo",
                                Component.translatable("realism.invention." + i.key()), i.year(),
                                Component.translatable(BuiltInRegistries.ITEM.get(ResourceLocation.parse(i.item())).getDescriptionId())), false);
                    return d.year;
                })
                .then(Commands.literal("country").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            if (setCountry(ctx.getSource().getServer(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code"))) return 1;
                            ctx.getSource().sendFailure(Component.literal(String.join(" ", COUNTRIES)));
                            return 0;
                        })))
                .then(Commands.literal("start").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("year0").executes(ctx -> {
                            choose(ctx.getSource().getServer(), YEAR_0);
                            return 1;
                        }))
                        .then(Commands.literal("today").executes(ctx -> {
                            choose(ctx.getSource().getServer(), TODAY);
                            return 1;
                        }))));
    }
}
