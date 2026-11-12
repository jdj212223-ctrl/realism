package com.jdj125.realism;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;

/**
 * Realism 4.4: real life, wave 4. Trains and buses, restaurants and tips, adopting a kid, TV news with live tornado
 * coverage, wildfires, storms knocking out power lines, and a thermometer for fevers.
 */
public final class Wave4 {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredBlock<Block> TRAIN_STATION = BLOCKS.register("train_station",
            () -> new Station(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).requiresCorrectToolForDrops(), true));
    public static final DeferredBlock<Block> BUS_STOP = BLOCKS.register("bus_stop",
            () -> new Station(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion(), false));
    public static final DeferredBlock<Block> DINER_TABLE = BLOCKS.register("diner_table",
            () -> new Block(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> TRAIN_STATION_ITEM = ITEMS.registerSimpleBlockItem(TRAIN_STATION);
    public static final DeferredItem<BlockItem> BUS_STOP_ITEM = ITEMS.registerSimpleBlockItem(BUS_STOP);
    public static final DeferredItem<BlockItem> DINER_TABLE_ITEM = ITEMS.registerSimpleBlockItem(DINER_TABLE);
    public static final DeferredItem<Item> THERMOMETER = ITEMS.register("thermometer", () -> new Thermometer(new Item.Properties().stacksTo(1)));

    private Wave4() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                e.accept(TRAIN_STATION_ITEM.get());
                e.accept(BUS_STOP_ITEM.get());
                e.accept(DINER_TABLE_ITEM.get());
            }
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(THERMOMETER.get());
        });
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        return RealLife.on(v);
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life4." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    // ---- Trains and buses ----

    public static class Stations extends SavedData {
        /** Every station and bus stop: position, and whether it's a train station. */
        public final Map<BlockPos, Boolean> all = new LinkedHashMap<>();
        /** Where each player is about to travel to (picked by right-clicking). */
        public final transient Map<UUID, BlockPos> picked = new HashMap<>();

        public static Stations get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Stations::new, Stations::load, null), "realism_stations");
        }

        static Stations load(CompoundTag tag, HolderLookup.Provider reg) {
            Stations s = new Stations();
            for (Tag t : tag.getList("s", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                NbtUtils.readBlockPos(c, "p").ifPresent(p -> s.all.put(p, c.getBoolean("train")));
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            ListTag l = new ListTag();
            all.forEach((p, train) -> {
                CompoundTag c = new CompoundTag();
                c.put("p", NbtUtils.writeBlockPos(p));
                c.putBoolean("train", train);
                l.add(c);
            });
            tag.put("s", l);
            return tag;
        }
    }

    public static final int BUS_RANGE = 1500;

    /** The fare from one stop to another: buses are cheap and short, trains cost more and go anywhere. */
    public static long fare(boolean train, BlockPos from, BlockPos to) {
        double d = Math.sqrt(from.distSqr(to));
        return train ? 5 + (long) (d / 100) : 2 + (long) (d / 300);
    }

    /** The stops you can travel to from here (same kind, buses within range), nearest first. */
    public static List<BlockPos> destinations(Stations s, BlockPos from, boolean train) {
        List<BlockPos> out = new ArrayList<>();
        s.all.forEach((p, t) -> {
            if (t == train && !p.equals(from) && (train || p.distSqr(from) <= (double) BUS_RANGE * BUS_RANGE)) out.add(p);
        });
        out.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return out;
    }

    static class Station extends Block {
        final boolean train;

        Station(Properties props, boolean train) {
            super(props);
            this.train = train;
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (level instanceof ServerLevel sl && level.dimension() == Level.OVERWORLD) {
                Stations s = Stations.get(sl.getServer());
                s.all.put(pos.immutable(), train);
                s.setDirty();
            }
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) {
                Stations s = Stations.get(sl.getServer());
                if (s.all.remove(pos) != null) s.setDirty();
            }
            super.onRemove(state, level, pos, newState, moved);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (p instanceof ServerPlayer sp && on(RealismConfig.TRANSIT)) use(sp, pos, train);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /** Right-click: pick the next destination. Sneak + right-click: pay and go. */
    public static void use(ServerPlayer p, BlockPos here, boolean train) {
        Stations s = Stations.get(p.server);
        List<BlockPos> dest = destinations(s, here, train);
        if (dest.isEmpty()) {
            say(p, train ? "no_trains" : "no_buses");
            return;
        }
        BlockPos chosen = s.picked.get(p.getUUID());
        if (!p.isShiftKeyDown() || chosen == null || !dest.contains(chosen)) {
            int i = chosen == null ? -1 : dest.indexOf(chosen);
            BlockPos next = dest.get((i + 1) % dest.size());
            s.picked.put(p.getUUID(), next);
            say(p, "pick", Component.translatable(train ? "block.realism.train_station" : "block.realism.bus_stop"),
                    next.getX(), next.getZ(), fare(train, here, next));
            return;
        }
        travel(p, here, chosen, train);
    }

    public static boolean travel(ServerPlayer p, BlockPos from, BlockPos to, boolean train) {
        long cost = fare(train, from, to);
        if (World.balance(p) < cost) {
            say(p, "no_money", cost);
            return false;
        }
        World.addMoney(p, -cost);
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 0, false, false, false));
        p.level().playSound(null, from, train ? SoundEvents.NOTE_BLOCK_BELL.value() : SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1, 0.8f);
        p.teleportTo(to.getX() + 0.5, to.getY() + 1, to.getZ() + 0.5);
        p.displayClientMessage(Component.translatable("realism.life4." + (train ? "arrived_train" : "arrived_bus"), cost).withStyle(ChatFormatting.GREEN), false);
        Mind.lift(p, 2);
        return true;
    }

    // ---- Restaurants and tips ----

    /** Eating at a Diner Table: a nicer meal (more mood), and the restaurant owner (the player who set the table nearby) gets paid. */
    @SubscribeEvent
    public static void onMeal(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.RESTAURANTS) || !event.getItem().has(net.minecraft.core.component.DataComponents.FOOD))
            return;
        BlockPos table = nearTable(p);
        if (table == null) return;
        Mind.lift(p, 6);
        say(p, "meal");
        ServerPlayer owner = TABLES.get(table) == null ? null : p.server.getPlayerList().getPlayer(TABLES.get(table));
        if (owner != null && owner != p && World.balance(p) >= BILL) {
            World.pay(p, owner, BILL);
            owner.displayClientMessage(Component.translatable("realism.life4.customer", p.getName(), BILL).withStyle(ChatFormatting.GREEN), true);
        }
    }

    public static final long BILL = 5;
    /** Who set each Diner Table (the restaurant owner). Remembered while the server runs. */
    static final Map<BlockPos, UUID> TABLES = new HashMap<>();

    @SubscribeEvent
    public static void onPlaceTable(BlockEvent.EntityPlaceEvent event) {
        if (event.getPlacedBlock().is(DINER_TABLE.get()) && event.getEntity() instanceof Player p) TABLES.put(event.getPos().immutable(), p.getUUID());
    }

    static BlockPos nearTable(Player p) {
        for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-2, -1, -2), p.blockPosition().offset(2, 1, 2)))
            if (p.level().getBlockState(q).is(DINER_TABLE.get())) return q.immutable();
        return null;
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tip").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> {
                    ServerPlayer from = ctx.getSource().getPlayerOrException(), to = EntityArgument.getPlayer(ctx, "player");
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    if (!tip(from, to, amount)) {
                        ctx.getSource().sendFailure(Component.translatable("realism.pay.failed", World.balance(from)));
                        return 0;
                    }
                    return 1;
                }))));
    }

    /** A tip: no tax, and it makes you both happy. */
    public static boolean tip(ServerPlayer from, ServerPlayer to, long amount) {
        if (amount <= 0 || from == to || World.balance(from) < amount) return false;
        World.addMoney(from, -amount);
        World.addMoney(to, amount);
        Mind.lift(from, 2);
        Mind.lift(to, 4);
        to.displayClientMessage(Component.translatable("realism.life4.tipped", from.getName(), amount).withStyle(ChatFormatting.GREEN), false);
        from.displayClientMessage(Component.translatable("realism.life4.tip_sent", to.getName(), amount), false);
        return true;
    }

    // ---- Adopting a kid ----

    static final String PARENT = "realismParent", ADOPTED = "realismAdopted", FED = "realismFed";
    public static final long GROW_UP_DAYS = 10;

    @SubscribeEvent
    public static void onKid(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof Villager v) || !on(RealismConfig.ADOPTION)
                || event.getHand() != InteractionHand.MAIN_HAND) return;
        CompoundTag d = v.getPersistentData();
        ItemStack hand = p.getMainHandItem();
        if (p.getUUID().toString().equals(d.getString(PARENT)) && hand.is(Items.BREAD)) {
            d.putLong(FED, p.level().getGameTime());
            if (!p.getAbilities().instabuild) hand.shrink(1);
            ((ServerLevel) p.level()).sendParticles(ParticleTypes.HEART, v.getX(), v.getY() + 1, v.getZ(), 4, 0.2, 0.2, 0.2, 0);
            say(p, "kid_fed");
            event.setCanceled(true);
            return;
        }
        if (!v.isBaby() || !p.isShiftKeyDown() || !hand.isEmpty() || d.contains(PARENT)) return;
        adopt(p, v);
        event.setCanceled(true);
    }

    public static void adopt(ServerPlayer p, Villager v) {
        CompoundTag d = v.getPersistentData();
        d.putString(PARENT, p.getUUID().toString());
        d.putLong(ADOPTED, p.level().getGameTime());
        d.putLong(FED, p.level().getGameTime());
        v.setCustomName(Component.translatable("realism.life4.kid_name", p.getName()));
        v.setPersistenceRequired();
        Mind.lift(p, 25);
        ((ServerLevel) p.level()).sendParticles(ParticleTypes.HEART, v.getX(), v.getY() + 1, v.getZ(), 15, 0.4, 0.4, 0.4, 0);
        p.displayClientMessage(Component.translatable("realism.life4.adopted").withStyle(ChatFormatting.LIGHT_PURPLE), false);
        Wave3.headline(p.server, "adoption", p.getGameProfile().getName());
    }

    static void kids(ServerPlayer p) {
        long now = p.level().getGameTime();
        for (Villager v : p.level().getEntitiesOfClass(Villager.class, new AABB(p.blockPosition()).inflate(32),
                v -> p.getUUID().toString().equals(v.getPersistentData().getString(PARENT)))) {
            CompoundTag d = v.getPersistentData();
            boolean grown = now - d.getLong(ADOPTED) >= 24000L * GROW_UP_DAYS;
            if (!grown && v.getAge() > -1200) v.setAge(-24000); // still a kid
            if (grown && !d.getBoolean("realismGrown")) {
                d.putBoolean("realismGrown", true);
                v.setAge(0);
                p.displayClientMessage(Component.translatable("realism.life4.kid_grew_up").withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
            if (v.distanceTo(p) < 8) Mind.lift(p, 0.3f);
            if (!grown && now - d.getLong(FED) > 24000L * 2 && p.tickCount % 2400 == 0) {
                p.level().playSound(null, v.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1, 1.5f);
                Mind.lift(p, -3);
                say(p, "kid_hungry");
            }
        }
    }

    // ---- The thermometer ----

    /** Body temperature in °C: 36.6 healthy, up with the flu, infections and food poisoning, down when freezing. */
    public static double temperature(Player p) {
        Body b = BodyEvents.body(p);
        double t = 36.6;
        if (b.flu > 0) t = Math.max(t, 38.4);
        if (b.infection > 0) t = Math.max(t, 39.2);
        if (b.foodPoisoning > 0) t = Math.max(t, 37.6);
        if (b.freezing > 0) t -= Math.min(4, b.freezing / 600.0);
        if (b.burn > 0 && b.burnDegree >= 2) t = Math.max(t, 37.9);
        return Math.round(t * 10) / 10.0;
    }

    static class Thermometer extends Item {
        Thermometer(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            if (p instanceof ServerPlayer sp) show(sp, sp);
            return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), level.isClientSide);
        }

        @Override
        public net.minecraft.world.InteractionResult interactLivingEntity(ItemStack stack, Player p, net.minecraft.world.entity.LivingEntity target, InteractionHand hand) {
            if (p instanceof ServerPlayer sp && target instanceof Player other) {
                show(sp, other);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
    }

    static void show(ServerPlayer reader, Player who) {
        double t = temperature(who);
        String key = t >= 39 ? "high_fever" : t >= 38 ? "fever" : t >= 37.3 ? "slight" : t < 35 ? "cold" : "normal";
        reader.displayClientMessage(Component.translatable("realism.life4.temp." + key, who.getName(), String.format(Locale.ROOT, "%.1f", t))
                .withStyle(t >= 38 || t < 35 ? ChatFormatting.RED : ChatFormatting.GREEN), false);
    }

    // ---- TV news ----

    @SubscribeEvent
    public static void onTv(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getHand() != InteractionHand.MAIN_HAND || !on(RealismConfig.TV_NEWS)
                || !p.level().getBlockState(event.getPos()).is(Hospital.TELEVISION.get())) return;
        tvNews(p);
    }

    public static void tvNews(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable("realism.life4.tv.title").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA));
        var t = Tornadoes.nearest(p.level(), p.position());
        if (t != null) {
            double d = Tornadoes.flat(t.position(), p.position());
            p.sendSystemMessage(Component.translatable("realism.life4.tv.tornado", "EF" + t.ef(), t.windspeed(), (int) d,
                    Tornadoes.compass(t.position().subtract(p.position()))).withStyle(ChatFormatting.RED));
        } else if (p.level().isThundering()) p.sendSystemMessage(Component.translatable("realism.life4.tv.storm").withStyle(ChatFormatting.YELLOW));
        else p.sendSystemMessage(Component.translatable(p.level().isRaining() ? "realism.life4.tv.rain" : "realism.life4.tv.clear"));
        List<String> lines = Wave3.News.get(p.server).lines;
        for (String line : lines.subList(Math.max(0, lines.size() - 3), lines.size())) {
            String[] parts = line.split("\\|");
            p.sendSystemMessage(Component.literal("📺 ").append(Component.translatable("realism.life3.news." + parts[1], (Object[]) Arrays.copyOfRange(parts, 2, parts.length))));
        }
    }

    // ---- Wildfires and storms knocking out power lines ----

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.players().isEmpty()) return;
        var r = level.getRandom();
        if (level.getGameTime() % 1200 == 0 && on(RealismConfig.WILDFIRES) && level.isDay() && !level.isRaining() && r.nextFloat() < 1f / 60f) {
            ServerPlayer p = level.players().get(r.nextInt(level.players().size()));
            if (level.getBiome(p.blockPosition()).value().getBaseTemperature() >= 1.2f) wildfire(level, p.blockPosition());
        }
        if (level.getGameTime() % 600 == 0 && on(RealismConfig.STORM_OUTAGES) && level.isThundering() && r.nextFloat() < 0.25f) {
            ServerPlayer p = level.players().get(r.nextInt(level.players().size()));
            stormHitsLines(level, p.blockPosition());
        }
    }

    /** A wildfire starts somewhere dry nearby (grass and bushes catch first). Returns where, or null. */
    public static BlockPos wildfire(ServerLevel level, BlockPos near) {
        var r = level.getRandom();
        for (int i = 0; i < 30; i++) {
            int x = near.getX() + r.nextInt(97) - 48, z = near.getZ() + r.nextInt(97) - 48;
            BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            BlockState below = level.getBlockState(top.below());
            if (level.getBlockState(top).isAir() && (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.COARSE_DIRT))) {
                level.setBlockAndUpdate(top, Blocks.FIRE.defaultBlockState());
                for (ServerPlayer p : level.players())
                    if (p.blockPosition().distSqr(top) < 200 * 200)
                        p.displayClientMessage(Component.translatable("realism.life4.wildfire", top.getX(), top.getZ()).withStyle(ChatFormatting.RED), false);
                Wave3.headline(level.getServer(), "wildfire", top.getX(), top.getZ());
                return top;
            }
        }
        return null;
    }

    /** Wind and lightning knock down a power cable that's out in the open. Returns where, or null. */
    public static BlockPos stormHitsLines(ServerLevel level, BlockPos near) {
        var r = level.getRandom();
        for (int i = 0; i < 60; i++) {
            BlockPos q = near.offset(r.nextInt(49) - 24, r.nextInt(17) - 8, r.nextInt(49) - 24);
            if (level.getBlockState(q).is(Power.CABLE.get()) && level.canSeeSky(q.above())) {
                level.destroyBlock(q, true);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, q.getX() + 0.5, q.getY() + 0.5, q.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.1);
                level.playSound(null, q, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 1, 1.4f);
                for (ServerPlayer p : level.players())
                    if (p.blockPosition().distSqr(q) < 64 * 64) say(p, "outage");
                return q;
            }
        }
        return null;
    }

    // ---- The tick ----

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 100 == 0 && on(RealismConfig.ADOPTION)) kids(p);
    }
}
