package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realism 4.0: weight, and chests that keep their stuff.
 * <ul>
 * <li>Everything weighs something (kilograms, shown on the tooltip). Carry too much and you slow down: over 25 kg
 * you're slower, over 40 kg you can't run, over 60 kg you can't jump and you sink in water, and over 100 kg you can't
 * pick anything else up. Armor counts, and so does what's inside a full chest you're carrying.</li>
 * <li>Breaking a chest, trapped chest or barrel doesn't spill it: the items stay inside, like picking up a real box.
 * Place it again and everything is still there. (A full chest is heavy!)</li>
 * <li>Falling into water from very high hurts: from 40+ blocks, water is as hard as concrete.</li>
 * </ul>
 */
public final class Weight {
    private Weight() {
    }

    public static final float SLOW = 25, NO_SPRINT = 40, NO_JUMP = 60, MAX = 100;
    static final ResourceLocation HEAVY = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "heavy_load"),
            HEAVY_JUMP = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "heavy_load_jump");

    public static boolean on() {
        try {
            return RealismConfig.WEIGHT.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    // ---- How heavy things are ----

    /** One item's weight in kg (not the stack). */
    public static float of(ItemStack s) {
        if (s.isEmpty()) return 0;
        Item i = s.getItem();
        float w = base(s, i);
        ItemContainerContents inside = s.get(DataComponents.CONTAINER); // full chests, shulker boxes
        if (inside != null) for (ItemStack in : inside.nonEmptyItems()) w += of(in) * in.getCount();
        return w;
    }

    public static float stack(ItemStack s) {
        return of(s) * s.getCount();
    }

    private static float base(ItemStack s, Item i) {
        if (i instanceof ArmorItem a) {
            float per = switch (a.getType()) {
                case HELMET -> 0.25f;
                case CHESTPLATE -> 0.4f;
                case LEGGINGS -> 0.25f;
                case BOOTS -> 0.1f;
                case BODY -> 0.5f;
            };
            String m = a.getMaterial().getRegisteredName();
            float full = m.contains("leather") ? 4 : m.contains("chain") ? 12 : m.contains("iron") ? 22 : m.contains("gold") ? 30
                    : m.contains("diamond") ? 20 : m.contains("netherite") ? 26 : m.contains("turtle") ? 6 : 15;
            return full * per;
        }
        if (i instanceof TieredItem t) {
            String n = t.getTier().toString().toLowerCase();
            float tool = n.contains("wood") ? 0.8f : n.contains("stone") ? 1.5f : n.contains("gold") ? 2.5f : n.contains("netherite") ? 2.2f : 1.4f;
            return i instanceof SwordItem ? tool * 0.9f : tool;
        }
        if (i == Items.WATER_BUCKET || i == Items.MILK_BUCKET || i == Items.POWDER_SNOW_BUCKET || i instanceof MobBucketItem) return 11;
        if (i == Items.LAVA_BUCKET) return 31;
        if (i == Items.BUCKET) return 1;
        if (i == Items.ANVIL || i == Items.CHIPPED_ANVIL || i == Items.DAMAGED_ANVIL) return 50;
        if (i instanceof BlockItem b) return block(b.getBlock());
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
        if (id.endsWith("_nugget")) return 0.05f;
        if (id.endsWith("_ingot") || id.equals("netherite_scrap")) return 0.5f;
        if (id.startsWith("raw_")) return 0.8f;
        if (id.contains("minecart")) return 30;
        if (id.contains("boat") || id.contains("raft")) return 25;
        if (i == Items.ELYTRA) return 3;
        if (i == Items.TRIDENT || i == Items.MACE) return 3;
        if (i instanceof PotionItem || i == Items.GLASS_BOTTLE || i == Items.HONEY_BOTTLE) return 0.4f;
        if (s.has(DataComponents.FOOD)) return 0.2f;
        if (i == Items.FEATHER || i == Items.PAPER || i == Items.STRING) return 0.01f;
        return 0.2f;
    }

    /** A block's weight, by what it's made of. */
    static float block(Block b) {
        if (b instanceof ShulkerBoxBlock) return 4;
        if (b instanceof ChestBlock || b instanceof BarrelBlock) return 6;
        BlockState s = b.defaultBlockState();
        var sound = s.getSoundType();
        float size = s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 1 : 0.3f;
        float kind;
        if (sound == net.minecraft.world.level.block.SoundType.METAL || sound == net.minecraft.world.level.block.SoundType.NETHERITE_BLOCK
                || sound == net.minecraft.world.level.block.SoundType.COPPER) kind = 25;
        else if (sound == net.minecraft.world.level.block.SoundType.WOOD || sound == net.minecraft.world.level.block.SoundType.BAMBOO_WOOD
                || sound == net.minecraft.world.level.block.SoundType.CHERRY_WOOD || sound == net.minecraft.world.level.block.SoundType.NETHER_WOOD) kind = 1.5f;
        else if (sound == net.minecraft.world.level.block.SoundType.GRASS || sound == net.minecraft.world.level.block.SoundType.WOOL
                || sound == net.minecraft.world.level.block.SoundType.AZALEA_LEAVES || sound == net.minecraft.world.level.block.SoundType.CHERRY_LEAVES) kind = 0.3f;
        else if (sound == net.minecraft.world.level.block.SoundType.GLASS) kind = 1;
        else if (sound == net.minecraft.world.level.block.SoundType.GRAVEL || sound == net.minecraft.world.level.block.SoundType.SAND
                || sound == net.minecraft.world.level.block.SoundType.ROOTED_DIRT || sound == net.minecraft.world.level.block.SoundType.MUD) kind = 2;
        else if (s.getBlock().defaultDestroyTime() >= 1.5f) kind = 3; // stone and friends
        else kind = 1;
        return Math.max(0.05f, kind * size);
    }

    /** Everything a player carries, armor included. */
    public static float carried(Player p) {
        float total = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) total += stack(inv.getItem(i));
        return total;
    }

    public static String kg(float w) {
        return w >= 10 ? String.format("%.0f kg", w) : w >= 1 ? String.format("%.1f kg", w) : String.format("%.2f kg", w);
    }

    // ---- What a heavy load does ----

    private static final Map<UUID, Integer> LAST_WARNING = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        apply(p);
    }

    /** Sets the slowdown for what the player carries; returns the weight. */
    public static float apply(ServerPlayer p) {
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        var jump = p.getAttribute(Attributes.JUMP_STRENGTH);
        boolean free = !on() || p.isSpectator() || p.getAbilities().instabuild;
        float w = free ? 0 : carried(p);
        if (speed != null) {
            if (w > SLOW) speed.addOrUpdateTransientModifier(new AttributeModifier(HEAVY,
                    -Math.min(0.85, (w - SLOW) / (MAX - SLOW) * 0.7), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else speed.removeModifier(HEAVY);
        }
        if (jump != null) {
            if (w > NO_JUMP) jump.addOrUpdateTransientModifier(new AttributeModifier(HEAVY_JUMP, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (w > NO_SPRINT) jump.addOrUpdateTransientModifier(new AttributeModifier(HEAVY_JUMP, -0.3, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else jump.removeModifier(HEAVY_JUMP);
        }
        if (w > NO_SPRINT && p.isSprinting()) p.setSprinting(false);
        if (w > NO_JUMP && p.isInWater() && !p.onGround()) // too heavy to swim: you sink
            p.setDeltaMovement(p.getDeltaMovement().add(0, -0.04, 0));
        if (w > NO_SPRINT) {
            Body b = BodyEvents.body(p);
            b.stamina = Math.max(0, b.stamina - (w - NO_SPRINT) / 400f); // carrying heavy stuff is tiring
        }
        int level = w > MAX ? 4 : w > NO_JUMP ? 3 : w > NO_SPRINT ? 2 : w > SLOW ? 1 : 0;
        int was = LAST_WARNING.getOrDefault(p.getUUID(), 0);
        if (level > was)
            p.displayClientMessage(Component.translatable("realism.weight.level" + level, kg(w)).withStyle(ChatFormatting.GOLD), true);
        LAST_WARNING.put(p.getUUID(), level);
        return w;
    }

    /** Can't pick up more than you can carry. */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (!on() || !(event.getPlayer() instanceof ServerPlayer p) || p.getAbilities().instabuild) return;
        ItemEntity e = event.getItemEntity();
        float now = carried(p);
        if (now + of(e.getItem()) > MAX) {
            event.setCanPickup(TriState.FALSE);
            if (p.tickCount % 40 == 0)
                p.displayClientMessage(Component.translatable("realism.weight.too_heavy", kg(now)).withStyle(ChatFormatting.RED), true);
        }
    }

    // ---- Falling into water from very high ----

    private static final Map<UUID, Float> FALLING = new ConcurrentHashMap<>();

    /** Water resets the fall the moment you touch it, so remember how far you were falling just before. */
    @SubscribeEvent
    public static void onFallTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        float before = FALLING.getOrDefault(p.getUUID(), 0f);
        if (p.isInWater() && before >= 40 && !p.getAbilities().invulnerable) {
            p.hurt(p.damageSources().fall(), (before - 3) * 0.8f);
            p.displayClientMessage(Component.translatable("realism.weight.water_hard").withStyle(ChatFormatting.RED), true);
            before = 0;
        }
        FALLING.put(p.getUUID(), p.isInWater() ? 0 : p.fallDistance);
    }

    // ---- Chests keep their stuff ----

    private static final Map<Long, ItemStack> PACKED = new ConcurrentHashMap<>();

    static boolean keepsItems(BlockState s) {
        Block b = s.getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock;
    }

    public static boolean keepChests() {
        try {
            return RealismConfig.CHESTS_KEEP_ITEMS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Packs the contents into the chest item before the block is removed (so they don't spill out). */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!keepChests() || event.getPlayer().getAbilities().instabuild || !keepsItems(event.getState())) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockEntity be = level.getBlockEntity(event.getPos());
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) || !(be instanceof Container c)) return;
        ItemStack packed = pack(event.getState(), c);
        if (packed == null) return;
        c.clearContent();
        PACKED.put(event.getPos().asLong(), packed);
    }

    /** The packed chest replaces the plain one in the drops. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent event) {
        ItemStack packed = PACKED.remove(event.getPos().asLong());
        if (packed == null) return;
        Item chest = event.getState().getBlock().asItem();
        var drops = event.getDrops();
        for (var e : drops)
            if (e.getItem().is(chest) && e.getItem().getCount() == 1) {
                e.setItem(packed);
                return;
            }
        // The chest itself didn't drop (e.g. broken by hand with a tool rule): drop the contents in their box anyway.
        Vec3 at = event.getPos().getCenter();
        drops.add(new ItemEntity(event.getLevel(), at.x, at.y, at.z, packed));
    }

    /** The chest as an item with everything still inside, or null if it's empty. */
    public static ItemStack pack(BlockState state, Container c) {
        List<ItemStack> items = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i).copy();
            any |= !s.isEmpty();
            items.add(s);
        }
        if (!any) return null;
        ItemStack out = new ItemStack(state.getBlock().asItem());
        out.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return out;
    }
}
