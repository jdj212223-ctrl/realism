package com.jdj125.realism;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 1.0.0: the law (it builds on {@link World}'s countries: the leader is the government).
 * <ul>
 * <li>Crimes are noticed by themselves: murder (killing a player or a villager) and theft (opening someone else's
 * chest in a country you're not a citizen of). They go on your record in the country where it happened.</li>
 * <li>The leader runs the law: /law setjail, /law arrest (within 8 blocks, 5 minutes in jail per crime), /law
 * release, /law pardon, /law record. Jailed players are pulled back if they walk more than 5 blocks away.</li>
 * <li>In old times (before modern medicine, in a world that started in year 0) the leader can sentence a jailed
 * murderer to death with /law sentence. There's no gore: the screen fades to black, they're gone for good, and their
 * remains stay where they fell (blocky pixel blood only, and only if bloodParticles is on). Modern times have no
 * death penalty, like Czechia since 1990.</li>
 * </ul>
 */
public final class Justice {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);

    public static final BooleanProperty BLOODY = BooleanProperty.create("bloody");
    /** No item: remains only appear in the world (and /setblock). */
    public static final DeferredBlock<Block> REMAINS = BLOCKS.register("remains",
            () -> new Remains(BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.BONE_BLOCK).noOcclusion()));
    public static final Supplier<BlockEntityType<RemainsEntity>> REMAINS_ENTITY = BLOCK_ENTITIES.register("remains",
            () -> BlockEntityType.Builder.of(RemainsEntity::new, REMAINS.get()).build(null));

    public static final int JAIL_MINUTES_PER_CRIME = 5, ARREST_RANGE = 8, JAIL_RADIUS = 5, SENTENCE_TICKS = 5 * 20;

    private Justice() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    // ---- Saved data: records, jails, prisoners ----

    public record Crime(String kind, String country) {
    }

    public record Prisoner(BlockPos jail, long until) {
    }

    public static class Data extends SavedData {
        public final Map<UUID, List<Crime>> records = new HashMap<>();
        public final Map<String, BlockPos> jails = new HashMap<>();
        public final Map<UUID, Prisoner> prisoners = new HashMap<>();

        public static Data get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(new Factory<>(Data::new, Data::load), "realism_justice");
        }

        static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data d = new Data();
            for (Tag t : tag.getList("records", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                List<Crime> list = new ArrayList<>();
                for (Tag x : c.getList("crimes", Tag.TAG_STRING)) {
                    String[] kc = x.getAsString().split("\\|", 2);
                    list.add(new Crime(kc[0], kc.length > 1 ? kc[1] : ""));
                }
                d.records.put(c.getUUID("who"), list);
            }
            CompoundTag jails = tag.getCompound("jails");
            for (String k : jails.getAllKeys()) NbtUtils.readBlockPos(jails, k).ifPresent(p -> d.jails.put(k, p));
            for (Tag t : tag.getList("prisoners", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                NbtUtils.readBlockPos(c, "jail").ifPresent(p -> d.prisoners.put(c.getUUID("who"), new Prisoner(p, c.getLong("until"))));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag recs = new ListTag();
            records.forEach((who, crimes) -> {
                CompoundTag c = new CompoundTag();
                c.putUUID("who", who);
                ListTag l = new ListTag();
                for (Crime cr : crimes) l.add(StringTag.valueOf(cr.kind() + "|" + cr.country()));
                c.put("crimes", l);
                recs.add(c);
            });
            tag.put("records", recs);
            CompoundTag jt = new CompoundTag();
            jails.forEach((k, p) -> jt.put(k, NbtUtils.writeBlockPos(p)));
            tag.put("jails", jt);
            ListTag pr = new ListTag();
            prisoners.forEach((who, p) -> {
                CompoundTag c = new CompoundTag();
                c.putUUID("who", who);
                c.put("jail", NbtUtils.writeBlockPos(p.jail()));
                c.putLong("until", p.until());
                pr.add(c);
            });
            tag.put("prisoners", pr);
            return tag;
        }

        public List<Crime> crimesIn(UUID who, String country) {
            return records.getOrDefault(who, List.of()).stream().filter(c -> c.country().equalsIgnoreCase(country)).toList();
        }
    }

    public static boolean jailed(ServerPlayer p) {
        return Data.get(p.serverLevel()).prisoners.containsKey(p.getUUID());
    }

    /** Puts a crime on someone's record in the country where it happened (if anywhere). */
    public static void crime(ServerPlayer p, String kind, BlockPos where) {
        World.Country c = World.Data.get(p.serverLevel()).countryAt(where);
        if (c == null) return;
        Data d = Data.get(p.serverLevel());
        d.records.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(new Crime(kind, c.name));
        d.setDirty();
        p.displayClientMessage(Component.translatable("realism.law.crime", Component.translatable("realism.crime." + kind), c.name), false);
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        if (event.getEntity() == killer) return;
        if (event.getEntity() instanceof Player || event.getEntity() instanceof Villager) crime(killer, "murder", event.getEntity().blockPosition());
    }

    private static final Map<UUID, Long> lastTheft = new HashMap<>();

    @SubscribeEvent
    public static void onOpen(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isCreative()) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof Container)) return;
        World.Country c = World.Data.get(p.serverLevel()).countryAt(event.getPos());
        if (c == null || c.citizens.contains(p.getUUID())) return;
        long now = p.level().getGameTime();
        if (now - lastTheft.getOrDefault(p.getUUID(), -10000L) < 1200) return;
        lastTheft.put(p.getUUID(), now);
        crime(p, "theft", event.getPos());
    }

    /** One second of justice for this player: jail walls, release, and a sentence being carried out. */
    static void second(ServerPlayer p, Body b) {
        Data d = Data.get(p.serverLevel());
        Prisoner pr = d.prisoners.get(p.getUUID());
        if (pr != null) {
            if (p.level().getGameTime() >= pr.until() && b.sentenced == 0) {
                d.prisoners.remove(p.getUUID());
                d.setDirty();
                p.displayClientMessage(Component.translatable("realism.law.released"), false);
            } else if (p.blockPosition().distSqr(pr.jail()) > JAIL_RADIUS * JAIL_RADIUS && p.level().dimension() == Level.OVERWORLD) {
                p.teleportTo(pr.jail().getX() + 0.5, pr.jail().getY(), pr.jail().getZ() + 0.5);
                p.displayClientMessage(Component.translatable("realism.law.no_escape"), true);
            }
        }
        if (b.sentenced > 0) {
            b.sentenced -= 20;
            if (b.sentenced <= 0) {
                b.sentenced = 0;
                d.prisoners.remove(p.getUUID());
                d.setDirty();
                p.hurt(Realism.source(p, Realism.EXECUTED), Float.MAX_VALUE);
            }
        }
    }

    // ---- Remains ----

    /** Leaves someone's remains where they fell: after an execution, or when they're gone for good. */
    public static void leaveRemains(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        BlockPos pos = p.blockPosition();
        for (int i = 0; i < 3 && !level.getBlockState(pos).canBeReplaced(); i++) pos = pos.above();
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return;
        level.setBlock(pos, REMAINS.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, p.getDirection())
                .setValue(BLOODY, RealismConfig.BLOOD_PARTICLES.get()), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof RemainsEntity r) {
            r.name = p.getGameProfile().getName();
            r.setChanged();
        }
    }

    static class Remains extends HorizontalDirectionalBlock implements EntityBlock {
        static final com.mojang.serialization.MapCodec<Remains> CODEC = simpleCodec(Remains::new);
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 3, 16);

        Remains(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BLOODY, false));
        }

        @Override
        protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, BLOODY);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new RemainsEntity(pos, state);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            String name = level.getBlockEntity(pos) instanceof RemainsEntity r ? r.name : "";
            player.displayClientMessage(Component.translatable(name.isEmpty() ? "realism.remains.unknown" : "realism.remains.of", name), true);
            return InteractionResult.CONSUME;
        }
    }

    public static class RemainsEntity extends BlockEntity {
        public String name = "";

        public RemainsEntity(BlockPos pos, BlockState state) {
            super(REMAINS_ENTITY.get(), pos, state);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.saveAdditional(tag, provider);
            tag.putString("name", name);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
            super.loadAdditional(tag, provider);
            name = tag.getString("name");
        }
    }

    // ---- /law ----

    private static World.Country leaderCountry(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        World.Country c = World.Data.get(p.serverLevel()).countryOf(p.getUUID());
        if (c == null || !p.getUUID().equals(c.leader)) {
            ctx.getSource().sendFailure(Component.translatable("realism.law.not_leader"));
            return null;
        }
        return c;
    }

    /** The result of an arrest, as a translation key under realism.law. */
    public static String arrest(ServerPlayer leader, World.Country c, ServerPlayer target) {
        Data d = Data.get(leader.serverLevel());
        List<Crime> crimes = d.crimesIn(target.getUUID(), c.name);
        if (crimes.isEmpty()) return "clean";
        if (leader.distanceToSqr(target) > ARREST_RANGE * ARREST_RANGE) return "too_far";
        BlockPos jail = d.jails.get(c.name.toLowerCase());
        if (jail == null) return "no_jail";
        d.prisoners.put(target.getUUID(), new Prisoner(jail, leader.level().getGameTime() + (long) crimes.size() * JAIL_MINUTES_PER_CRIME * 1200));
        d.setDirty();
        target.teleportTo(leader.serverLevel(), jail.getX() + 0.5, jail.getY(), jail.getZ() + 0.5, target.getYRot(), target.getXRot());
        target.displayClientMessage(Component.translatable("realism.law.arrested", c.name, crimes.size() * JAIL_MINUTES_PER_CRIME), false);
        return "arrest_ok";
    }

    /** Sentences a jailed murderer to death. Only in old times. */
    public static String sentence(ServerPlayer leader, World.Country c, ServerPlayer target) {
        if (!Era.oldTimes(leader) || !Era.Data.get(leader.getServer()).chosen) return "no_death_penalty";
        Data d = Data.get(leader.serverLevel());
        if (!d.prisoners.containsKey(target.getUUID())) return "not_jailed";
        if (d.crimesIn(target.getUUID(), c.name).stream().noneMatch(cr -> cr.kind().equals("murder"))) return "not_murderer";
        BodyEvents.body(target).sentenced = SENTENCE_TICKS;
        for (ServerPlayer o : leader.getServer().getPlayerList().getPlayers())
            o.displayClientMessage(Component.translatable("realism.law.sentenced", target.getName(), c.name), false);
        leader.level().playSound(null, target.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1f, 0.5f);
        return "sentence_ok";
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        if (key.endsWith("_ok")) ctx.getSource().sendSuccess(() -> Component.translatable("realism.law." + key, args), true);
        else ctx.getSource().sendFailure(Component.translatable("realism.law." + key, args));
        return key.endsWith("_ok") ? 1 : 0;
    }

    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("law")
                .then(Commands.literal("record").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    List<Crime> crimes = Data.get(t.serverLevel()).records.getOrDefault(t.getUUID(), List.of());
                    if (crimes.isEmpty()) return reply(ctx, "record_clean_ok", t.getName());
                    for (Crime c : crimes)
                        ctx.getSource().sendSuccess(() -> Component.translatable("realism.law.record_line", Component.translatable("realism.crime." + c.kind()), c.country()), false);
                    return crimes.size();
                })))
                .then(Commands.literal("setjail").executes(ctx -> {
                    World.Country c = leaderCountry(ctx);
                    if (c == null) return 0;
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (World.Data.get(p.serverLevel()).countryAt(p.blockPosition()) != c || p.level().dimension() != Level.OVERWORLD) return reply(ctx, "jail_outside");
                    Data d = Data.get(p.serverLevel());
                    d.jails.put(c.name.toLowerCase(), p.blockPosition());
                    d.setDirty();
                    return reply(ctx, "jail_ok", c.name);
                }))
                .then(Commands.literal("arrest").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    World.Country c = leaderCountry(ctx);
                    return c == null ? 0 : reply(ctx, arrest(ctx.getSource().getPlayerOrException(), c, EntityArgument.getPlayer(ctx, "player")),
                            EntityArgument.getPlayer(ctx, "player").getName());
                })))
                .then(Commands.literal("release").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    World.Country c = leaderCountry(ctx);
                    if (c == null) return 0;
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    Data d = Data.get(t.serverLevel());
                    if (d.prisoners.remove(t.getUUID()) == null) return reply(ctx, "not_jailed", t.getName());
                    BodyEvents.body(t).sentenced = 0;
                    d.setDirty();
                    t.displayClientMessage(Component.translatable("realism.law.released"), false);
                    return reply(ctx, "release_ok", t.getName());
                })))
                .then(Commands.literal("pardon").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    World.Country c = leaderCountry(ctx);
                    if (c == null) return 0;
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    Data d = Data.get(t.serverLevel());
                    List<Crime> list = d.records.get(t.getUUID());
                    if (list != null) list.removeIf(cr -> cr.country().equalsIgnoreCase(c.name));
                    d.setDirty();
                    return reply(ctx, "pardon_ok", t.getName());
                })))
                .then(Commands.literal("sentence").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    World.Country c = leaderCountry(ctx);
                    return c == null ? 0 : reply(ctx, sentence(ctx.getSource().getPlayerOrException(), c, EntityArgument.getPlayer(ctx, "player")),
                            EntityArgument.getPlayer(ctx, "player").getName());
                }))));
    }
}
