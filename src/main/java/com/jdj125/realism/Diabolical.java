package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realism 4.7: the diabolical update.
 * <ul>
 * <li>No breaking wood with your bare hands: logs need an axe. To start, leaves drop sticks and gravel drops flint more
 * often, and a Flint Hatchet (flint over a stick) chops your first tree. Punching a tree hurts.</li>
 * <li>Hiding a body: a Body Bag picks up remains (70 kg!) and puts them down somewhere else, like underground. Anyone who
 * sees you do it reports it (a crime on your record). Bodies smell for days, even buried, so people nearby notice. And
 * evidence fades: after 3 days the fingerprints are gone, after 7 even the cause of death.</li>
 * </ul>
 */
public final class Diabolical {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredItem<Item> FLINT_HATCHET = ITEMS.register("flint_hatchet",
            () -> new AxeItem(Tiers.WOOD, new Item.Properties().attributes(AxeItem.createAttributes(Tiers.WOOD, 5.0f, -3.2f))));
    public static final DeferredItem<Item> BODY_BAG = ITEMS.register("body_bag", () -> new BodyBag(new Item.Properties().stacksTo(1)));

    public static final long PRINTS_FADE = 24000L * 3, CAUSE_FADE = 24000L * 7, SMELL_DAYS = 24000L * 10;
    public static final float FULL_BAG_KG = 70;

    private Diabolical() {
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(FLINT_HATCHET.get());
                e.accept(BODY_BAG.get());
            }
        });
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        return RealLife.on(v);
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.diabolical." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    // ---- No breaking wood with your hands ----

    public static boolean canChop(Player p) {
        return p.getAbilities().instabuild || p.getMainHandItem().canPerformAction(ItemAbilities.AXE_DIG);
    }

    private static final Map<UUID, Long> LAST_OUCH = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!on(RealismConfig.NO_HAND_WOOD) || !event.getState().is(BlockTags.LOGS) || canChop(event.getEntity())) return;
        event.setNewSpeed(0); // it just won't break
        Player p = event.getEntity();
        if (p instanceof ServerPlayer sp) {
            long now = sp.level().getGameTime();
            if (now - LAST_OUCH.getOrDefault(sp.getUUID(), 0L) > 40) {
                LAST_OUCH.put(sp.getUUID(), now);
                if (p.getMainHandItem().isEmpty()) sp.hurt(sp.damageSources().generic(), 0.5f);
                say(sp, "need_axe");
            }
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        BlockState s = event.getState();
        if (s.is(BlockTags.LOGS) && on(RealismConfig.NO_HAND_WOOD) && !canChop(p)) {
            event.setCanceled(true);
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || p.getAbilities().instabuild || !on(RealismConfig.NO_HAND_WOOD)) return;
        var r = level.getRandom();
        BlockPos at = event.getPos();
        // Something to start with: sticks from leaves, flint from gravel.
        if (s.is(BlockTags.LEAVES) && r.nextFloat() < 0.2f) drop(level, at, new ItemStack(Items.STICK));
        if (s.is(Blocks.GRAVEL) && r.nextFloat() < 0.15f) drop(level, at, new ItemStack(Items.FLINT));
    }

    static void drop(ServerLevel level, BlockPos at, ItemStack s) {
        level.addFreshEntity(new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, s));
    }

    // ---- Hiding a body ----

    /** The remains' details, kept in the bag (and how long ago the person died). */
    static CompoundTag remainsData(Justice.RemainsEntity r, long died) {
        CompoundTag t = new CompoundTag();
        t.putString("name", r.name);
        t.putString("cause", r.cause);
        t.putString("killer", r.killer);
        t.putString("weapon", r.weapon);
        t.putLong("died", died);
        return t;
    }

    public static boolean full(ItemStack bag) {
        return bag.is(BODY_BAG.get()) && bag.has(DataComponents.CUSTOM_DATA) && bag.get(DataComponents.CUSTOM_DATA).copyTag().contains("died");
    }

    static class BodyBag extends Item {
        BodyBag(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            if (!(ctx.getPlayer() instanceof ServerPlayer p) || !(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
            ItemStack bag = ctx.getItemInHand();
            BlockPos at = ctx.getClickedPos();
            if (!full(bag)) {
                if (!(level.getBlockEntity(at) instanceof Justice.RemainsEntity r)) return InteractionResult.PASS;
                return bagUp(p, level, at, r, bag) ? InteractionResult.CONSUME : InteractionResult.PASS;
            }
            BlockPos put = level.getBlockState(at).canBeReplaced() ? at : at.relative(ctx.getClickedFace());
            return putDown(p, level, put, bag) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable(full(stack) ? "item.realism.body_bag.full" : "item.realism.body_bag.tip").withStyle(ChatFormatting.GRAY));
        }
    }

    public static boolean bagUp(ServerPlayer p, ServerLevel level, BlockPos at, Justice.RemainsEntity r, ItemStack bag) {
        if (!on(RealismConfig.HIDING_BODIES)) return false;
        bag.set(DataComponents.CUSTOM_DATA, CustomData.of(remainsData(r, r.died == Long.MIN_VALUE ? level.getGameTime() : r.died)));
        level.removeBlock(at, false);
        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1, 0.7f);
        say(p, "bagged");
        // Anyone who sees it reports it.
        boolean seen = false;
        for (ServerPlayer w : level.players()) {
            if (w == p || w.isSpectator() || w.distanceToSqr(p) > 32 * 32 || !w.hasLineOfSight(p)) continue;
            w.displayClientMessage(Component.translatable("realism.diabolical.witness", p.getName()).withStyle(ChatFormatting.RED), false);
            seen = true;
        }
        if (seen) Justice.crime(level, p.getUUID(), p, "hiding_body", at, true);
        return true;
    }

    public static boolean putDown(ServerPlayer p, ServerLevel level, BlockPos at, ItemStack bag) {
        if (!level.getBlockState(at).canBeReplaced()) return false;
        CompoundTag t = bag.get(DataComponents.CUSTOM_DATA).copyTag();
        level.setBlockAndUpdate(at, Justice.REMAINS.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, p.getDirection()));
        if (level.getBlockEntity(at) instanceof Justice.RemainsEntity r) {
            r.name = t.getString("name");
            r.cause = t.getString("cause");
            r.killer = t.getString("killer");
            r.weapon = t.getString("weapon");
            r.died = t.getLong("died");
            r.setChanged();
        }
        bag.remove(DataComponents.CUSTOM_DATA);
        say(p, hidden(level, at) ? "hidden" : "put_down");
        return true;
    }

    /** Underground: 3+ blocks of ground over it. */
    public static boolean hidden(Level level, BlockPos at) {
        return at.getY() <= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 4;
    }

    /** What the forensics kit can still find, given how long ago they died: "all", "no_prints" or "too_old". */
    public static String evidence(Level level, Justice.RemainsEntity r) {
        if (r.died == Long.MIN_VALUE) return "all";
        long age = level.getGameTime() - r.died;
        return age >= CAUSE_FADE ? "too_old" : age >= PRINTS_FADE ? "no_prints" : "all";
    }

    // ---- Bodies smell ----

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 200 != 0 || !on(RealismConfig.HIDING_BODIES) || p.isSpectator()) return;
        ServerLevel level = p.serverLevel();
        BlockPos me = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-6, -5, -6), me.offset(6, 3, 6))) {
            if (!(level.getBlockEntity(q) instanceof Justice.RemainsEntity r) || r.died == Long.MIN_VALUE) continue;
            long age = level.getGameTime() - r.died;
            if (age < 24000L / 2 || age > SMELL_DAYS) continue; // starts after half a day, gone after 10
            if (hidden(level, q.immutable())) {
                say(p, "smell_buried");
            } else {
                say(p, "smell");
                level.sendParticles(ParticleTypes.ASH, q.getX() + 0.5, q.getY() + 0.6, q.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
            }
            Mind.lift(p, -1);
            return;
        }
    }
}
