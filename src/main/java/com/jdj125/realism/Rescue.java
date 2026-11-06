package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 6: no reviving, unless someone saves you.
 * <p>
 * Instead of dying you collapse: you lie on the ground (pose SLEEPING, outside a bed, which is how other players and
 * Claude's bot recognise it), your screen goes black, and you can't do anything. Someone has
 * {@link RealismConfig#COLLAPSE_SECONDS} to save you:
 * <ul>
 * <li>a defibrillator: hold it, look at the patient within 3 blocks, press charge → clear → shock
 * (key.realism.defib_charge / defib_clear / defib_shock);</li>
 * <li>an Ambulance Radio: used near the patient, it rushes them to the nearest Operating Table, and if a Doctor is
 * there they get surgery straight away.</li>
 * </ul>
 * If nobody makes it, you're gone for good (spectator, or start over as a new person, per config). The void and
 * /kill skip the collapse. While collapsed you can't be hurt again: the clock decides.
 */
public final class Rescue {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredItem<Item> DEFIBRILLATOR = ITEMS.register("defibrillator", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> AMBULANCE_RADIO = ITEMS.register("ambulance_radio", () -> new AmbulanceRadio(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<Block> AED_HOLDER = BLOCKS.register("aed_holder",
            () -> new AedHolder(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<?> AED_HOLDER_ITEM = ITEMS.registerSimpleBlockItem(AED_HOLDER);

    /** Seconds the defib steps may be apart before you have to start over. */
    private static final int STEP_WINDOW = 6 * 20;

    private Rescue() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Rescue::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DEFIBRILLATOR.get());
            event.accept(AMBULANCE_RADIO.get());
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(AED_HOLDER.get());
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    public static boolean collapsed(Player p) {
        return BodyEvents.body(p).collapse > 0;
    }

    // ---- Collapsing instead of dying ----

    private static boolean skipsCollapse(DamageSource src) {
        return src.is(DamageTypes.FELL_OUT_OF_WORLD) || src.is(DamageTypes.GENERIC_KILL) || src.is(Realism.EXECUTED);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !RealismConfig.NO_REVIVING.get()) return;
        Body b = BodyEvents.body(p);
        if (b.collapse > 0 || skipsCollapse(event.getSource())) {
            // The real end: the clock ran out, the neck broke, or it was the void.
            markGone(p);
            if (b.collapse <= 1 && b.collapseCause.isEmpty() || event.getSource().is(Realism.EXECUTED)) b.collapseCause = event.getSource().getMsgId();
            if (event.getSource().is(Realism.EXECUTED) || (RealismConfig.REMAINS.get() && !event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)))
                Justice.leaveRemains(p);
            return;
        }
        event.setCanceled(true);
        b.collapseCause = event.getSource().getMsgId();
        collapse(p);
    }

    public static void collapse(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        p.setHealth(1);
        b.collapse = Era.collapseSeconds(p) * 20;
        b.defibStep = 0;
        p.setForcedPose(Pose.SLEEPING);
        p.stopRiding();
        p.removeAllEffects();
        p.displayClientMessage(Component.translatable("realism.collapse.you"), false);
        for (ServerPlayer other : p.serverLevel().players())
            if (other != p && other.distanceToSqr(p) < 512 * 512)
                other.displayClientMessage(Component.translatable("realism.collapse.other", p.getName(), p.getBlockX(), p.getBlockY(), p.getBlockZ()), false);
    }

    /** One second of being collapsed (called from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        if (b.collapse <= 0) return;
        // In an ambulance the paramedics keep you going: the clock runs 4 times slower.
        b.collapse -= Ambulance.inAmbulance(p) ? 5 : 20;
        p.setForcedPose(Pose.SLEEPING);
        p.setSprinting(false);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 10, true, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.JUMP, 40, 250, true, false, false));
        if (b.collapse <= 0) {
            b.collapse = 1; // still "collapsed" for onDeath, so this death is the final one
            p.hurt(Realism.source(p, Realism.COLLAPSED), Float.MAX_VALUE);
        }
    }

    // Nothing hurts you while you're collapsed: only the clock decides.
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player p && collapsed(p) && !event.getSource().is(Realism.COLLAPSED)
                && !event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD) && !event.getSource().is(DamageTypes.GENERIC_KILL)) event.setCanceled(true);
    }

    /** Brought back: stabilised just enough to survive (still needs water, sleep and care). */
    public static void revive(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        if (b.collapse <= 0) return;
        b.collapse = 0;
        b.defibStep = 0;
        p.setForcedPose(null);
        p.setHealth(6);
        long day = RealismConfig.dayTicks();
        b.thirst = Math.min(b.thirst, (long) (RealismConfig.THIRST_DAYS.get() * day * 0.9));
        b.awake = Math.min(b.awake, (long) (RealismConfig.SLEEP_DAYS.get() * day * 0.9));
        b.blood = Math.max(b.blood, Blood.COLLAPSE);
        b.stamina = Math.max(b.stamina, 30);
        b.overexertion = 0;
        b.unconscious = 3 * 20;
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0, true, false));
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1200, 0, true, false));
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 0.6f);
        p.displayClientMessage(Component.translatable("realism.revived"), false);
    }

    // ---- Gone for good ----

    private static void markGone(ServerPlayer p) {
        BodyEvents.body(p).collapse = 0;
        p.setForcedPose(null);
        p.setData(Realism.GONE, true);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.isEndConquered() || !p.getData(Realism.GONE)) return;
        p.setData(Realism.GONE, false);
        if (RealismConfig.PERMA_DEATH_SPECTATOR.get()) {
            p.setGameMode(GameType.SPECTATOR);
            p.displayClientMessage(Component.translatable("realism.gone.spectator"), false);
        } else {
            p.setData(Realism.BLOOD_TYPE, ""); // a new person gets a new body
            p.displayClientMessage(Component.translatable("realism.gone.new_person"), false);
        }
    }

    // ---- The defibrillator ----

    /** A defib step from a client: 1 charge, 2 clear, 3 shock, aimed at the entity with this id. */
    public record DefibStep(int step, int target) implements CustomPacketPayload {
        public static final Type<DefibStep> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "defib"));
        public static final StreamCodec<ByteBuf, DefibStep> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DefibStep::step, ByteBufCodecs.VAR_INT, DefibStep::target, DefibStep::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void defib(ServerPlayer user, int step, Entity target) {
        if (Era.noMedicineYet(user)) return;
        if (!user.getMainHandItem().is(DEFIBRILLATOR.get())) {
            say(user, "defib.hold");
            return;
        }
        if (!(target instanceof ServerPlayer patient) || !collapsed(patient) || user.distanceToSqr(patient) > 9) {
            say(user, "defib.no_patient");
            return;
        }
        Body b = BodyEvents.body(patient);
        long now = user.level().getGameTime();
        boolean inTime = now - b.defibTime <= STEP_WINDOW;
        if (step == 1) {
            b.defibStep = 1;
            b.defibTime = now;
            user.level().playSound(null, patient.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8f, 1.8f);
            say(user, "defib.charged");
        } else if (step == 2 && b.defibStep == 1 && inTime) {
            b.defibStep = 2;
            b.defibTime = now;
            say(user, "defib.clear");
        } else if (step == 3 && b.defibStep == 2 && inTime) {
            user.level().playSound(null, patient.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.6f, 2.0f);
            revive(patient);
            say(user, "defib.saved", patient.getName());
        } else {
            b.defibStep = 0;
            say(user, "defib.order");
        }
    }

    // ---- The ambulance ----

    static class AmbulanceRadio extends Item {
        AmbulanceRadio(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide || !(level instanceof ServerLevel sl)) return InteractionResultHolder.success(stack);
            if (!(player instanceof ServerPlayer caller) || !dispatch(caller, 8)) return InteractionResultHolder.fail(stack);
            player.getCooldowns().addCooldown(this, 20 * 30);
            return InteractionResultHolder.consume(stack);
        }
    }

    /**
     * Sends an ambulance for the nearest collapsed player within {@code range} blocks of the caller: they're rushed
     * to the nearest Operating Table (placed, or in a hospital building), with surgery if a Doctor is there.
     */
    public static boolean dispatch(ServerPlayer caller, int range) {
        return dispatchWhy(caller, range).equals("ok");
    }

    /** Like {@link #dispatch}, but says what happened: "ok", "nobody" or "no_hospital". */
    public static String dispatchWhy(ServerPlayer caller, int range) {
        ServerLevel sl = caller.serverLevel();
        if (Era.year(caller) < 1930) {
            say(caller, "ambulance.not_invented");
            return "not_invented";
        }
        ServerPlayer patient = sl.players().stream().filter(o -> collapsed(o) && o.distanceToSqr(caller) <= (double) range * range)
                .min(java.util.Comparator.comparingDouble(o -> o.distanceToSqr(caller))).orElse(null);
        if (patient == null) {
            say(caller, "ambulance.nobody");
            return "nobody";
        }
        // The nearest remembered table that is really still there (one blown up or /fill-ed away is forgotten).
        Tables tables = Tables.get(sl);
        BlockPos table = tables.nearest(patient.blockPosition(), 1024);
        while (table != null && !sl.getBlockState(table).is(Hospital.OPERATING_TABLE.get())) {
            tables.remove(table);
            table = tables.nearest(patient.blockPosition(), 1024);
        }
        if (table == null) table = findHospital(sl, patient.blockPosition());
        if (table == null) {
            say(caller, "ambulance.no_hospital");
            return "no_hospital";
        }
        patient.teleportTo(table.getX() + 0.5, table.getY() + 1, table.getZ() + 0.5);
        caller.displayClientMessage(Component.translatable("realism.ambulance.sent", patient.getName()), false);
        boolean doctor = !sl.getEntitiesOfClass(Villager.class, new AABB(table).inflate(8),
                v -> v.getVillagerData().getProfession() == Hospital.DOCTOR.get()).isEmpty();
        if (doctor) {
            revive(patient);
            Hospital.operate(patient);
            patient.displayClientMessage(Component.translatable("realism.ambulance.surgery"), false);
        } else patient.displayClientMessage(Component.translatable("realism.ambulance.no_doctor"), false);
        return "ok";
    }

    /**
     * Finds the nearest hospital building that generated in the world (it isn't in {@link Tables}, since no player
     * placed its Operating Table), and remembers its table for next time.
     */
    static BlockPos findHospital(ServerLevel sl, BlockPos from) {
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE,
                ResourceLocation.fromNamespaceAndPath(Realism.MODID, "hospital"));
        var holder = sl.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getHolder(key);
        if (holder.isEmpty()) return null;
        var found = sl.getChunkSource().getGenerator().findNearestMapStructure(sl, net.minecraft.core.HolderSet.direct(holder.get()), from, 32, false);
        if (found == null) return null;
        BlockPos center = found.getFirst();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-20, -10, -20), center.offset(20, 30, 20)))
            if (sl.getBlockState(p).is(Hospital.OPERATING_TABLE.get())) {
                BlockPos table = p.immutable();
                Tables.get(sl).add(table);
                return table;
            }
        return null;
    }

    /** Remembers where Operating Tables are in each world, so the ambulance knows where the hospital is. */
    public static class Tables extends SavedData {
        private final List<BlockPos> tables = new ArrayList<>();

        public static Tables get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Tables::new, Tables::load), "realism_hospitals");
        }

        static Tables load(CompoundTag tag, HolderLookup.Provider provider) {
            Tables t = new Tables();
            for (Tag e : tag.getList("tables", Tag.TAG_COMPOUND)) NbtUtils.readBlockPos((CompoundTag) e, "pos").ifPresent(t.tables::add);
            return t;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag list = new ListTag();
            for (BlockPos p : tables) {
                CompoundTag e = new CompoundTag();
                e.put("pos", NbtUtils.writeBlockPos(p));
                list.add(e);
            }
            tag.put("tables", list);
            return tag;
        }

        public void add(BlockPos p) {
            if (!tables.contains(p)) tables.add(p.immutable());
            setDirty();
        }

        void remove(BlockPos p) {
            tables.remove(p);
            setDirty();
        }

        public int size() {
            return tables.size();
        }

        public BlockPos nearest(BlockPos from, int range) {
            BlockPos best = null;
            double bestD = (double) range * range;
            for (BlockPos p : tables) {
                double d = p.distSqr(from);
                if (d < bestD) { bestD = d; best = p; }
            }
            return best;
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel sl && event.getPlacedBlock().is(Hospital.OPERATING_TABLE.get())) Tables.get(sl).add(event.getPos());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel sl && event.getState().is(Hospital.OPERATING_TABLE.get())) Tables.get(sl).remove(event.getPos());
    }

    // ---- The AED wall holder ----

    static class AedHolder extends Block {
        static final BooleanProperty LOADED = BooleanProperty.create("loaded");

        AedHolder(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(LOADED, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(LOADED);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, BlockHitResult hit) {
            if (!stack.is(DEFIBRILLATOR.get()) || state.getValue(LOADED)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            if (!level.isClientSide) {
                stack.consume(1, player);
                level.setBlock(pos, state.setValue(LOADED, true), 3);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!state.getValue(LOADED)) return InteractionResult.PASS;
            if (!level.isClientSide) {
                level.setBlock(pos, state.setValue(LOADED, false), 3);
                ItemStack defib = new ItemStack(DEFIBRILLATOR.get());
                if (!player.getInventory().add(defib)) player.drop(defib, false);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
