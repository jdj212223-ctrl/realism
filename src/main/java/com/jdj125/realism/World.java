package com.jdj125.realism;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 8: the world. Countries (found one, claim land with Country Flags, citizens, a leader who sets the tax),
 * money (a bank balance, coins, the ATM that swaps emeralds and banknotes, /pay) and phones (text other players
 * when you both have signal from a Signal Tower; messages arrive as server messages, so nobody's chat filter hides
 * them).
 */
public final class World {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredBlock<Block> FLAG = BLOCKS.register("country_flag",
            () -> new Block(BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.WOOL).noOcclusion()));
    public static final DeferredBlock<Block> ATM = BLOCKS.register("atm",
            () -> new Atm(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> TOWER = BLOCKS.register("signal_tower",
            () -> new Block(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredItem<?> FLAG_ITEM = ITEMS.registerSimpleBlockItem(FLAG);
    public static final DeferredItem<?> ATM_ITEM = ITEMS.registerSimpleBlockItem(ATM);
    public static final DeferredItem<?> TOWER_ITEM = ITEMS.registerSimpleBlockItem(TOWER);
    public static final DeferredItem<Item> BANKNOTE = ITEMS.register("banknote", () -> new Item(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<Item> PHONE = ITEMS.register("phone", () -> new Phone(new Item.Properties().stacksTo(1)));

    /** Money per emerald at the ATM, and the value of one banknote. */
    public static final int PER_EMERALD = 10, NOTE = 100;
    /** Territory: chunks this far from a country's flag. Phone signal: blocks from a tower. */
    public static final int CLAIM_CHUNKS = 2, SIGNAL_RANGE = 160;

    private World() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(World::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(FLAG_ITEM.get());
            event.accept(ATM_ITEM.get());
            event.accept(TOWER_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PHONE.get());
            event.accept(BANKNOTE.get());
        }
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    // ---- Saved world data: countries, flags, towers ----

    public static class Country {
        public final String name;
        public UUID leader;
        public final Set<UUID> citizens = new HashSet<>();
        public final List<BlockPos> flags = new ArrayList<>();
        public long treasury;
        public int tax = 10;

        public Country(String name, UUID leader) {
            this.name = name;
            this.leader = leader;
            citizens.add(leader);
        }
    }

    public static class Data extends SavedData {
        public final Map<String, Country> countries = new LinkedHashMap<>();
        public final List<BlockPos> towers = new ArrayList<>();

        public static Data get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(new Factory<>(Data::new, Data::load), "realism_world");
        }

        static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data d = new Data();
            for (Tag t : tag.getList("countries", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Country country = new Country(c.getString("name"), c.getUUID("leader"));
                for (Tag u : c.getList("citizens", Tag.TAG_STRING)) country.citizens.add(UUID.fromString(u.getAsString()));
                for (Tag f : c.getList("flags", Tag.TAG_COMPOUND)) NbtUtils.readBlockPos((CompoundTag) f, "p").ifPresent(country.flags::add);
                country.treasury = c.getLong("treasury");
                country.tax = c.getInt("tax");
                d.countries.put(country.name.toLowerCase(), country);
            }
            for (Tag t : tag.getList("towers", Tag.TAG_COMPOUND)) NbtUtils.readBlockPos((CompoundTag) t, "p").ifPresent(d.towers::add);
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag list = new ListTag();
            for (Country c : countries.values()) {
                CompoundTag ct = new CompoundTag();
                ct.putString("name", c.name);
                ct.putUUID("leader", c.leader);
                ListTag cit = new ListTag();
                for (UUID u : c.citizens) cit.add(StringTag.valueOf(u.toString()));
                ct.put("citizens", cit);
                ct.put("flags", positions(c.flags));
                ct.putLong("treasury", c.treasury);
                ct.putInt("tax", c.tax);
                list.add(ct);
            }
            tag.put("countries", list);
            tag.put("towers", positions(towers));
            return tag;
        }

        private static ListTag positions(List<BlockPos> ps) {
            ListTag l = new ListTag();
            for (BlockPos p : ps) {
                CompoundTag e = new CompoundTag();
                e.put("p", NbtUtils.writeBlockPos(p));
                l.add(e);
            }
            return l;
        }

        public Country countryOf(UUID player) {
            for (Country c : countries.values()) if (c.citizens.contains(player)) return c;
            return null;
        }

        public Country countryAt(BlockPos pos) {
            ChunkPos at = new ChunkPos(pos);
            for (Country c : countries.values())
                for (BlockPos f : c.flags) {
                    ChunkPos fc = new ChunkPos(f);
                    if (Math.abs(fc.x - at.x) <= CLAIM_CHUNKS && Math.abs(fc.z - at.z) <= CLAIM_CHUNKS) return c;
                }
            return null;
        }

        public boolean signal(BlockPos pos) {
            for (BlockPos t : towers) if (t.distSqr(pos) <= (double) SIGNAL_RANGE * SIGNAL_RANGE) return true;
            return false;
        }
    }

    // ---- Money ----

    public static long balance(Player p) {
        return p.getData(Realism.MONEY);
    }

    public static void addMoney(Player p, long amount) {
        p.setData(Realism.MONEY, Math.max(0, balance(p) + amount));
    }

    /** Money earned (an emerald exchange): your country takes its tax into the treasury. Returns what you keep. */
    public static long earn(ServerPlayer p, long amount) {
        Data d = Data.get(p.serverLevel());
        Country c = d.countryOf(p.getUUID());
        long tax = c == null ? 0 : amount * c.tax / 100;
        if (c != null) {
            c.treasury += tax;
            d.setDirty();
        }
        addMoney(p, amount - tax);
        return amount - tax;
    }

    public static boolean pay(ServerPlayer from, ServerPlayer to, long amount) {
        if (amount <= 0 || balance(from) < amount || from == to) return false;
        addMoney(from, -amount);
        addMoney(to, amount);
        return true;
    }

    static class Atm extends Block {
        Atm(Properties props) {
            super(props);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, BlockHitResult hit) {
            if (!stack.is(Items.EMERALD) && !stack.is(BANKNOTE.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (level.isClientSide || !(player instanceof ServerPlayer sp)) return ItemInteractionResult.SUCCESS;
            int n = stack.getCount();
            if (stack.is(Items.EMERALD)) {
                long kept = earn(sp, (long) n * PER_EMERALD);
                say(player, "atm.exchanged", n, kept, balance(player));
            } else {
                addMoney(player, (long) n * NOTE);
                say(player, "atm.deposited", n * NOTE, balance(player));
            }
            stack.shrink(n);
            level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.6f, 1.2f);
            return ItemInteractionResult.CONSUME;
        }

        // Empty hand: show the balance; sneaking: withdraw one banknote.
        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player.isShiftKeyDown()) {
                if (balance(player) < NOTE) {
                    say(player, "atm.too_little", NOTE);
                    return InteractionResult.CONSUME;
                }
                addMoney(player, -NOTE);
                ItemStack note = new ItemStack(BANKNOTE.get());
                if (!player.getInventory().add(note)) player.drop(note, false);
                say(player, "atm.withdrew", NOTE, balance(player));
            } else say(player, "atm.balance", balance(player));
            return InteractionResult.CONSUME;
        }
    }

    // ---- Flags and towers ----

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl) || !(event.getEntity() instanceof ServerPlayer p)) return;
        Data d = Data.get(sl);
        if (event.getPlacedBlock().is(TOWER.get())) {
            d.towers.add(event.getPos().immutable());
            d.setDirty();
        }
        if (event.getPlacedBlock().is(FLAG.get())) {
            Country c = d.countryOf(p.getUUID());
            if (c == null || !c.leader.equals(p.getUUID())) {
                say(p, "flag.not_leader");
                event.setCanceled(true);
                return;
            }
            Country there = d.countryAt(event.getPos());
            if (there != null && there != c) {
                say(p, "flag.taken", there.name);
                event.setCanceled(true);
                return;
            }
            c.flags.add(event.getPos().immutable());
            d.setDirty();
            say(p, "flag.claimed", c.name);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        Data d = Data.get(sl);
        if (event.getState().is(TOWER.get()) && d.towers.remove(event.getPos())) d.setDirty();
        if (event.getState().is(FLAG.get())) for (Country c : d.countries.values()) if (c.flags.remove(event.getPos())) d.setDirty();
    }

    // "Welcome to <country>!" when you walk into its land.
    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        Country c = Data.get(p.serverLevel()).countryAt(p.blockPosition());
        String name = c == null ? "" : c.name;
        Body b = BodyEvents.body(p);
        if (!name.equals(b.lastCountry)) {
            if (c != null) p.displayClientMessage(Component.translatable("realism.country.welcome", c.name).withStyle(ChatFormatting.GOLD), true);
            else if (!b.lastCountry.isEmpty()) p.displayClientMessage(Component.translatable("realism.country.left", b.lastCountry), true);
            b.lastCountry = name;
        }
    }

    // ---- Phones ----

    static class Phone extends Item {
        Phone(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                Data d = Data.get(sp.serverLevel());
                Country c = d.countryOf(sp.getUUID());
                List<String> contacts = sp.server.getPlayerList().getPlayers().stream().filter(o -> o != sp).map(o -> o.getGameProfile().getName()).toList();
                PacketDistributor.sendToPlayer(sp, new OpenPhone(d.signal(sp.blockPosition()), balance(sp), c == null ? "" : c.name, String.join(",", contacts)));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
    }

    public record OpenPhone(boolean signal, long balance, String country, String contacts) implements CustomPacketPayload {
        public static final Type<OpenPhone> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "open_phone"));
        public static final StreamCodec<ByteBuf, OpenPhone> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, OpenPhone::signal,
                ByteBufCodecs.VAR_LONG, OpenPhone::balance, ByteBufCodecs.STRING_UTF8, OpenPhone::country, ByteBufCodecs.STRING_UTF8,
                OpenPhone::contacts, OpenPhone::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SendText(String to, String text) implements CustomPacketPayload {
        public static final Type<SendText> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "send_text"));
        public static final StreamCodec<ByteBuf, SendText> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(16), SendText::to,
                ByteBufCodecs.stringUtf8(256), SendText::text, SendText::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Delivers a text if the sender holds a phone and both ends have signal. Returns the result key. */
    public static String text(ServerPlayer from, String toName, String text) {
        if (!from.getInventory().contains(new ItemStack(PHONE.get()))) return "phone.none";
        ServerPlayer to = from.server.getPlayerList().getPlayerByName(toName);
        if (to == null) return "phone.offline";
        Data d = Data.get(from.serverLevel());
        if (!d.signal(from.blockPosition())) return "phone.no_signal";
        if (!d.signal(to.blockPosition()) || !to.getInventory().contains(new ItemStack(PHONE.get()))) return "phone.unreachable";
        String clean = text.length() > 200 ? text.substring(0, 200) : text;
        to.displayClientMessage(Component.translatable("realism.phone.received", from.getName(), clean).withStyle(ChatFormatting.AQUA), false);
        Afterlife.said(from, clean);
        to.level().playSound(null, to.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, 1.8f);
        from.displayClientMessage(Component.translatable("realism.phone.sent", toName, clean).withStyle(ChatFormatting.GRAY), false);
        return "phone.ok";
    }

    /** Calling the ambulance by phone: you need a phone and signal; it goes to the nearest collapsed player within 256 blocks. */
    public static String callAmbulance(ServerPlayer caller) {
        if (!caller.getInventory().contains(new ItemStack(PHONE.get()))) return "phone.none";
        if (!Data.get(caller.serverLevel()).signal(caller.blockPosition())) return "phone.no_signal";
        return Rescue.dispatch(caller, 256) ? "phone.ok" : "phone.ambulance_failed";
    }

    public record CallAmbulance() implements CustomPacketPayload {
        public static final Type<CallAmbulance> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "call_ambulance"));
        public static final StreamCodec<ByteBuf, CallAmbulance> CODEC = StreamCodec.unit(new CallAmbulance());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- /country, /pay and /ambulance ----

    public static void commands(RegisterCommandsEvent event) {
        var d = event.getDispatcher();
        d.register(Commands.literal("ambulance").executes(ctx -> {
            String r = callAmbulance(ctx.getSource().getPlayerOrException());
            if (!r.equals("phone.ok")) ctx.getSource().sendFailure(Component.translatable("realism." + r));
            return r.equals("phone.ok") ? 1 : 0;
        }));
        d.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> {
                    ServerPlayer from = ctx.getSource().getPlayerOrException(), to = EntityArgument.getPlayer(ctx, "player");
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    if (!pay(from, to, amount)) {
                        ctx.getSource().sendFailure(Component.translatable("realism.pay.failed", balance(from)));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.pay.sent", amount, to.getName(), balance(from)), false);
                    to.displayClientMessage(Component.translatable("realism.pay.got", from.getName(), amount), false);
                    return 1;
                }))));
        d.register(Commands.literal("country")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> { for (String s : List.of("Czechia", "Germany", "Austria", "Croatia", "USA", "Australia", "Slovakia", "Poland")) b.suggest(s); return b.buildFuture(); })
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Data data = Data.get(p.serverLevel());
                            String name = StringArgumentType.getString(ctx, "name");
                            if (data.countryOf(p.getUUID()) != null || data.countries.containsKey(name.toLowerCase())) {
                                ctx.getSource().sendFailure(Component.translatable("realism.country.cant_create"));
                                return 0;
                            }
                            data.countries.put(name.toLowerCase(), new Country(name, p.getUUID()));
                            data.setDirty();
                            ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.created", name), true);
                            return 1;
                        })))
                .then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Data data = Data.get(p.serverLevel());
                    Country c = data.countries.get(StringArgumentType.getString(ctx, "name").toLowerCase());
                    if (c == null || data.countryOf(p.getUUID()) != null) {
                        ctx.getSource().sendFailure(Component.translatable("realism.country.cant_join"));
                        return 0;
                    }
                    c.citizens.add(p.getUUID());
                    data.setDirty();
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.joined", c.name), true);
                    return 1;
                })))
                .then(Commands.literal("leave").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Data data = Data.get(p.serverLevel());
                    Country c = data.countryOf(p.getUUID());
                    if (c == null || c.leader.equals(p.getUUID())) {
                        ctx.getSource().sendFailure(Component.translatable("realism.country.cant_leave"));
                        return 0;
                    }
                    c.citizens.remove(p.getUUID());
                    data.setDirty();
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.left_country", c.name), false);
                    return 1;
                }))
                .then(Commands.literal("tax").then(Commands.argument("percent", IntegerArgumentType.integer(0, 50)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Data data = Data.get(p.serverLevel());
                    Country c = data.countryOf(p.getUUID());
                    if (c == null || !c.leader.equals(p.getUUID())) {
                        ctx.getSource().sendFailure(Component.translatable("realism.country.not_leader"));
                        return 0;
                    }
                    c.tax = IntegerArgumentType.getInteger(ctx, "percent");
                    data.setDirty();
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.tax_set", c.tax), false);
                    return 1;
                })))
                .then(Commands.literal("withdraw").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Data data = Data.get(p.serverLevel());
                    Country c = data.countryOf(p.getUUID());
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    if (c == null || !c.leader.equals(p.getUUID()) || c.treasury < amount) {
                        ctx.getSource().sendFailure(Component.translatable("realism.country.cant_withdraw"));
                        return 0;
                    }
                    c.treasury -= amount;
                    addMoney(p, amount);
                    data.setDirty();
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.withdrew", amount, c.treasury), false);
                    return 1;
                })))
                .then(Commands.literal("info").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    Country c = Data.get(p.serverLevel()).countryOf(p.getUUID());
                    if (c == null) ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.none"), false);
                    else {
                        String leader = p.server.getProfileCache() == null ? "?" : p.server.getProfileCache().get(c.leader).map(g -> g.getName()).orElse("?");
                        ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.info", c.name, leader, c.citizens.size(), c.tax, c.treasury, c.flags.size()), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    Data data = Data.get(ctx.getSource().getLevel());
                    String all = data.countries.isEmpty() ? "-" : String.join(", ", data.countries.values().stream().map(c -> c.name).toList());
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.country.list", all), false);
                    return 1;
                })));
    }

    /** The phone's clock. */
    public static String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
