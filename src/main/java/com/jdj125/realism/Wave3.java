package com.jdj125.realism;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.MapCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Realism 4.3: real life, wave 3. Car crashes, earthquakes, dogs that need walks, newspapers, school, weddings,
 * elections, taxes on payments, jet lag, and the tornado siren (tornadoes themselves: {@link Tornadoes}).
 */
public final class Wave3 {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);

    public static final DeferredBlock<Block> SIREN = BLOCKS.register("tornado_siren",
            () -> new Siren(BlockBehaviour.Properties.of().strength(3.0f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<Block> SCHOOL_DESK = BLOCKS.register("school_desk",
            () -> new SchoolDesk(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> SIREN_ITEM = ITEMS.registerSimpleBlockItem(SIREN);
    public static final DeferredItem<BlockItem> SCHOOL_DESK_ITEM = ITEMS.registerSimpleBlockItem(SCHOOL_DESK);
    public static final DeferredItem<Item> RING = ITEMS.register("wedding_ring", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> NEWSPAPER = ITEMS.register("newspaper", () -> new Newspaper(new Item.Properties()));
    public static final Supplier<BlockEntityType<SirenEntity>> SIREN_ENTITY = BLOCK_ENTITIES.register("tornado_siren",
            () -> BlockEntityType.Builder.of(SirenEntity::new, SIREN.get()).build(null));
    public static final int SIREN_W = 60;

    private Wave3() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                e.accept(SIREN_ITEM.get());
                e.accept(SCHOOL_DESK_ITEM.get());
            }
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(RING.get());
                e.accept(NEWSPAPER.get());
            }
        });
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        return RealLife.on(v);
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life3." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    // ---- The news ----

    public static class News extends SavedData {
        public final List<String> lines = new ArrayList<>(); // "day|translation key|arg1|arg2..."

        public static News get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(News::new, News::load, null), "realism_news");
        }

        static News load(CompoundTag tag, HolderLookup.Provider reg) {
            News n = new News();
            for (Tag t : tag.getList("l", Tag.TAG_STRING)) n.lines.add(t.getAsString());
            return n;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            ListTag l = new ListTag();
            for (String s : lines) l.add(StringTag.valueOf(s));
            tag.put("l", l);
            return tag;
        }
    }

    /** Adds a headline to today's paper. */
    public static void headline(MinecraftServer server, String key, Object... args) {
        if (server == null) return;
        News n = News.get(server);
        StringBuilder sb = new StringBuilder().append(server.overworld().getGameTime() / 24000L).append('|').append(key);
        for (Object a : args) sb.append('|').append(String.valueOf(a).replace('|', '/'));
        n.lines.add(sb.toString());
        while (n.lines.size() > 40) n.lines.remove(0);
        n.setDirty();
    }

    static class Newspaper extends Item {
        Newspaper(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
            if (p instanceof ServerPlayer sp) read(sp);
            return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), level.isClientSide);
        }
    }

    public static void read(ServerPlayer p) {
        News n = News.get(p.server);
        long today = p.server.overworld().getGameTime() / 24000L;
        p.sendSystemMessage(Component.translatable("realism.life3.paper.title", today + 1).withStyle(ChatFormatting.BOLD));
        List<String> recent = n.lines.subList(Math.max(0, n.lines.size() - 8), n.lines.size());
        if (recent.isEmpty()) p.sendSystemMessage(Component.translatable("realism.life3.paper.quiet").withStyle(ChatFormatting.GRAY));
        for (String line : recent) {
            String[] parts = line.split("\\|");
            Object[] args = Arrays.copyOfRange(parts, 2, parts.length);
            p.sendSystemMessage(Component.literal("• ").append(Component.translatable("realism.life3.news." + parts[1], args)));
        }
        Mind.lift(p, 1);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) headline(p.server, "death", p.getGameProfile().getName());
    }

    // ---- Earthquakes ----

    public record Shake(int ticks, int strength) implements CustomPacketPayload {
        public static final Type<Shake> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "shake"));
        public static final StreamCodec<ByteBuf, Shake> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Shake::ticks,
                ByteBufCodecs.VAR_INT, Shake::strength, Shake::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !on(RealismConfig.EARTHQUAKES)) return;
        if (level.getGameTime() % 1200 != 0 || level.players().isEmpty()) return;
        if (level.getRandom().nextFloat() < 1f / 200f) { // about once every 10 Minecraft days
            ServerPlayer near = level.players().get(level.getRandom().nextInt(level.players().size()));
            earthquake(level, near.blockPosition().offset(level.getRandom().nextInt(200) - 100, 0, level.getRandom().nextInt(200) - 100),
                    1 + level.getRandom().nextInt(3));
        }
    }

    /** An earthquake (strength 1-3): the ground shakes, glass breaks, loose sand and gravel slide. */
    public static void earthquake(ServerLevel level, BlockPos center, int strength) {
        for (ServerPlayer p : level.players()) {
            double d = Math.sqrt(p.blockPosition().distSqr(center));
            if (d > 150 * strength) continue;
            int ticks = 20 * (4 + strength * 4);
            if (p.connection != null && p.connection.hasChannel(Shake.TYPE)) PacketDistributor.sendToPlayer(p, new Shake(ticks, strength));
            p.displayClientMessage(Component.translatable("realism.life3.quake", strength + 3).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
            level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_EMERGE, SoundSource.AMBIENT, 0.6f, 0.5f);
            if (strength >= 3) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, ticks, 0, false, false, true));
            // Glass breaks and loose blocks slide near everyone who felt it.
            var r = level.getRandom();
            for (int i = 0; i < 40 * strength; i++) {
                BlockPos q = p.blockPosition().offset(r.nextInt(25) - 12, r.nextInt(9) - 4, r.nextInt(25) - 12);
                BlockState s = level.getBlockState(q);
                if ((s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE) || s.is(BlockTags.IMPERMEABLE) || s.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock)
                        && r.nextFloat() < 0.15f * strength) level.destroyBlock(q, false);
                else if (s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) level.scheduleTick(q, s.getBlock(), 2);
            }
        }
        headline(level.getServer(), "quake", strength + 3, center.getX(), center.getZ());
    }

    // ---- The tick: car crashes, dogs, jet lag, marriage ----

    private static final Map<UUID, double[]> TRACK = new ConcurrentHashMap<>(); // last car speed, lastX, lastZ, lastTime, lagUntil

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || p.getAbilities().instabuild) return;
        double[] t = TRACK.computeIfAbsent(p.getUUID(), u -> new double[]{0, p.getX(), p.getZ(), p.level().getGameTime(), 0});
        // Car crashes: going fast, then suddenly stopped by a wall.
        if (on(RealismConfig.CAR_CRASHES) && p.getVehicle() instanceof Cars.Car car) {
            double speed = car.getDeltaMovement().horizontalDistance();
            if (t[0] > 0.45 && speed < t[0] * 0.3 && car.horizontalCollision) crash(p, t[0]);
            t[0] = speed;
        } else t[0] = 0;
        if (p.tickCount % 20 != 0) return;
        long now = p.level().getGameTime();
        // Jet lag: crossing thousands of blocks in a minute (teleports, the Nether, fast flights).
        if (on(RealismConfig.JET_LAG) && p.level().dimension() == Level.OVERWORLD && now - (long) t[3] >= 1200) {
            double moved = Math.hypot(p.getX() - t[1], p.getZ() - t[2]);
            if (moved > 2500) {
                t[4] = now + RealismConfig.dayTicks();
                say(p, "jet_lag");
            }
            t[1] = p.getX();
            t[2] = p.getZ();
            t[3] = now;
        }
        if (t[4] > now && p.tickCount % 600 == 0) {
            p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 20 * 20, 0, false, false, true));
            BodyEvents.body(p).stamina = Math.max(0, BodyEvents.body(p).stamina - 8);
            say(p, "yawn");
        }
        // Married: being near your husband or wife makes you happy.
        Body b = BodyEvents.body(p);
        if (!b.spouse.isEmpty() && p.tickCount % 100 == 0) {
            try {
                Player s = p.level().getPlayerByUUID(UUID.fromString(b.spouse));
                if (s != null && s.distanceTo(p) < 8) Mind.lift(p, 0.5f);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // Dogs need walks.
        if (on(RealismConfig.DOG_WALKS) && p.tickCount % 100 == 0) dogs(p);
    }

    public static void crash(ServerPlayer p, double speed) {
        float hurt = (float) (speed * 14);
        p.hurt(p.damageSources().flyIntoWall(), hurt);
        p.level().playSound(null, p.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1, 0.6f);
        if (p.getRandom().nextFloat() < 0.5f) Blood.bleed(p, speed > 0.8 ? 2 : 1);
        if (speed > 0.8 && p.getRandom().nextFloat() < 0.3f) {
            Body b = BodyEvents.body(p);
            b.brokenLeg = (int) Math.max(b.brokenLeg, RealismConfig.dayTicks() * 2);
        }
        say(p, "crash");
        headline(p.server, "crash", p.getGameProfile().getName());
    }

    static final String WALK = "realismWalk", WALKED = "realismWalked";

    static void dogs(ServerPlayer p) {
        long now = p.level().getGameTime();
        double[] t = TRACK.get(p.getUUID());
        for (Wolf w : p.level().getEntitiesOfClass(Wolf.class, new AABB(p.blockPosition()).inflate(48), w -> w.isTame() && p.getUUID().equals(w.getOwnerUUID()))) {
            CompoundTag d = w.getPersistentData();
            if (!d.contains(WALKED)) d.putLong(WALKED, now);
            boolean walking = !w.isOrderedToSit() && w.distanceTo(p) < 12 && p.getDeltaMovement().horizontalDistance() > 0.05 && !p.isPassenger();
            if (walking) {
                d.putFloat(WALK, d.getFloat(WALK) + 5 * (float) p.getDeltaMovement().horizontalDistance() * 20);
                if (d.getFloat(WALK) >= 300) {
                    d.putFloat(WALK, 0);
                    d.putLong(WALKED, now);
                    ((ServerLevel) p.level()).sendParticles(ParticleTypes.HEART, w.getX(), w.getY() + 1, w.getZ(), 5, 0.3, 0.3, 0.3, 0);
                    Mind.lift(p, 8);
                    say(p, "dog_walked");
                }
            }
            if (now - d.getLong(WALKED) > 24000L * 2 && p.tickCount % 2400 == 0) {
                p.level().playSound(null, w.blockPosition(), SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1, 1);
                Mind.lift(p, -3);
                say(p, "dog_sad");
            }
        }
    }

    // ---- School ----

    public static void study(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        if (b.education >= 100) {
            say(p, "school.done");
            return;
        }
        if (b.funCooldown > 0) {
            say(p, "school.tired");
            return;
        }
        b.funCooldown = 20 * 60;
        int before = b.education;
        b.education = Math.min(100, b.education + 5);
        say(p, "school.studied", b.education);
        if (before < 50 && b.education >= 50) {
            p.displayClientMessage(Component.translatable("realism.life3.school.high_school").withStyle(ChatFormatting.AQUA), false);
            headline(p.server, "graduate", p.getGameProfile().getName());
        }
        if (before < 100 && b.education >= 100) {
            p.displayClientMessage(Component.translatable("realism.life3.school.university").withStyle(ChatFormatting.AQUA), false);
            headline(p.server, "degree", p.getGameProfile().getName());
        }
    }

    /** School pays off: +20% wages with a high-school diploma, +50% with a degree. */
    public static double payBonus(Player p) {
        int e = BodyEvents.body(p).education;
        return e >= 100 ? 1.5 : e >= 50 ? 1.2 : 1.0;
    }

    static class SchoolDesk extends Block {
        SchoolDesk(Properties props) {
            super(props);
        }

        @Override
        protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p,
                                                                     net.minecraft.world.phys.shapes.CollisionContext c) {
            return Block.box(1, 0, 1, 15, 12, 15);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (p instanceof ServerPlayer sp) {
                if (!sp.getInventory().contains(new ItemStack(net.minecraft.world.item.Items.BOOK))
                        && !sp.getInventory().contains(new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK))) say(sp, "school.need_book");
                else study(sp);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    // ---- Weddings ----

    private static final Map<UUID, UUID> PROPOSALS = new ConcurrentHashMap<>(); // asked -> asker
    private static final Map<UUID, Long> PROPOSED_AT = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRing(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof ServerPlayer other) || !on(RealismConfig.WEDDINGS)
                || event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(RING.get())) return;
        event.setCanceled(true);
        Body a = BodyEvents.body(p), b = BodyEvents.body(other);
        if (!a.spouse.isEmpty() || !b.spouse.isEmpty()) {
            say(p, "wedding.taken");
            return;
        }
        PROPOSALS.put(other.getUUID(), p.getUUID());
        PROPOSED_AT.put(other.getUUID(), p.level().getGameTime());
        other.displayClientMessage(Component.translatable("realism.life3.wedding.asked", p.getName()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        say(p, "wedding.waiting", other.getName());
    }

    /** The one who was asked says yes by crouching (within 30 seconds, close to the one who asked). */
    @SubscribeEvent
    public static void onYes(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 5 != 0 || !p.isShiftKeyDown()) return;
        UUID asker = PROPOSALS.get(p.getUUID());
        if (asker == null) return;
        if (p.level().getGameTime() - PROPOSED_AT.getOrDefault(p.getUUID(), 0L) > 600) {
            PROPOSALS.remove(p.getUUID());
            return;
        }
        if (!(p.level().getPlayerByUUID(asker) instanceof ServerPlayer a) || a.distanceTo(p) > 6) return;
        marry(a, p);
    }

    public static void marry(ServerPlayer a, ServerPlayer b) {
        PROPOSALS.remove(b.getUUID());
        BodyEvents.body(a).spouse = b.getUUID().toString();
        BodyEvents.body(b).spouse = a.getUUID().toString();
        ItemStack ring = a.getMainHandItem();
        if (ring.is(RING.get()) && !a.getAbilities().instabuild) ring.shrink(1);
        Mind.lift(a, 30);
        Mind.lift(b, 30);
        ServerLevel level = a.serverLevel();
        level.sendParticles(ParticleTypes.HEART, (a.getX() + b.getX()) / 2, a.getY() + 2, (a.getZ() + b.getZ()) / 2, 30, 1, 0.5, 1, 0);
        level.playSound(null, a.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1, 1.5f);
        a.server.getPlayerList().broadcastSystemMessage(Component.translatable("realism.life3.wedding.married", a.getName(), b.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        headline(a.server, "wedding", a.getGameProfile().getName(), b.getGameProfile().getName());
    }

    // ---- Elections and taxes ----

    public static class Votes extends SavedData {
        /** country name -> (voter -> candidate) */
        public final Map<String, Map<UUID, UUID>> votes = new HashMap<>();
        public long lastElectionDay;

        public static Votes get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Votes::new, Votes::load, null), "realism_votes");
        }

        static Votes load(CompoundTag tag, HolderLookup.Provider reg) {
            Votes v = new Votes();
            v.lastElectionDay = tag.getLong("last");
            CompoundTag all = tag.getCompound("v");
            for (String c : all.getAllKeys()) {
                Map<UUID, UUID> m = new HashMap<>();
                CompoundTag ct = all.getCompound(c);
                for (String voter : ct.getAllKeys()) m.put(UUID.fromString(voter), ct.getUUID(voter));
                v.votes.put(c, m);
            }
            return v;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            tag.putLong("last", lastElectionDay);
            CompoundTag all = new CompoundTag();
            votes.forEach((c, m) -> {
                CompoundTag ct = new CompoundTag();
                m.forEach((voter, cand) -> ct.putUUID(voter.toString(), cand));
                all.put(c, ct);
            });
            tag.put("v", all);
            return tag;
        }
    }

    public static final int ELECTION_DAYS = 20;

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vote").then(Commands.argument("candidate", EntityArgument.player()).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException(), cand = EntityArgument.getPlayer(ctx, "candidate");
            String r = vote(p, cand);
            ctx.getSource().sendSystemMessage(Component.translatable("realism.life3.vote." + r, cand.getName()));
            return r.equals("ok") ? 1 : 0;
        })));
    }

    /** Votes for a leader of your country (the candidate must be a citizen too). */
    public static String vote(ServerPlayer p, ServerPlayer candidate) {
        World.Data data = World.Data.get(p.serverLevel());
        World.Country c = data.countryOf(p.getUUID());
        if (c == null) return "no_country";
        if (!c.citizens.contains(candidate.getUUID())) return "not_citizen";
        Votes v = Votes.get(p.server);
        v.votes.computeIfAbsent(c.name, k -> new HashMap<>()).put(p.getUUID(), candidate.getUUID());
        v.setDirty();
        return "ok";
    }

    @SubscribeEvent
    public static void onElectionTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.getGameTime() % 1200 != 0
                || !on(RealismConfig.ELECTIONS)) return;
        long day = level.getGameTime() / 24000L;
        Votes v = Votes.get(level.getServer());
        if (day - v.lastElectionDay < ELECTION_DAYS) return;
        v.lastElectionDay = day;
        countVotes(level);
        v.setDirty();
    }

    /** Election day: in every country, whoever has the most votes (at least 2) becomes the leader. */
    public static void countVotes(ServerLevel level) {
        World.Data data = World.Data.get(level);
        Votes v = Votes.get(level.getServer());
        for (World.Country c : data.countries.values()) {
            Map<UUID, UUID> ballots = v.votes.getOrDefault(c.name, Map.of());
            Map<UUID, Integer> count = new HashMap<>();
            ballots.forEach((voter, cand) -> {
                if (c.citizens.contains(voter) && c.citizens.contains(cand)) count.merge(cand, 1, Integer::sum);
            });
            count.entrySet().stream().max(Map.Entry.comparingByValue()).filter(e -> e.getValue() >= 2).ifPresent(win -> {
                c.leader = win.getKey();
                var winner = level.getServer().getPlayerList().getPlayer(win.getKey());
                String name = winner == null ? "?" : winner.getGameProfile().getName();
                level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("realism.life3.election.won", name, c.name, win.getValue())
                        .withStyle(ChatFormatting.AQUA), false);
                headline(level.getServer(), "election", name, c.name);
            });
            v.votes.remove(c.name);
        }
        data.setDirty();
        v.setDirty();
    }

    /** Sales tax: money you pay someone is taxed by your country (it goes to the treasury). Returns what arrives. */
    public static long taxed(ServerPlayer from, long amount) {
        if (!on(RealismConfig.TAX_EVERYTHING)) return amount;
        World.Data data = World.Data.get(from.serverLevel());
        World.Country c = data.countryOf(from.getUUID());
        if (c == null || c.tax <= 0) return amount;
        long tax = amount * c.tax / 100;
        c.treasury += tax;
        data.setDirty();
        return amount - tax;
    }

    // ---- The tornado siren ----

    public static class Siren extends BaseEntityBlock {
        static final MapCodec<Siren> CODEC = simpleCodec(Siren::new);

        public Siren(Properties props) {
            super(props);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected RenderShape getRenderShape(BlockState s) {
            return RenderShape.MODEL;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SirenEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide || type != SIREN_ENTITY.get() ? null : (l, p, s, be) -> {
                if (l.getGameTime() % 120 == 0 && Tornadoes.sirenShouldWail((ServerLevel) l, p))
                    l.playSound(null, p, SoundEvents.RAID_HORN.value(), SoundSource.BLOCKS, 8, 0.8f);
            };
        }
    }

    public static class SirenEntity extends BlockEntity {
        public SirenEntity(BlockPos pos, BlockState state) {
            super(SIREN_ENTITY.get(), pos, state);
        }
    }
}
