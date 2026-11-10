package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 1.2.0: you can be the surgeon, and carry the hurt to the hospital yourself.
 * <ul>
 * <li><b>Dragging</b>: sneak + right-click a collapsed player with an empty hand to drag them behind you (again to
 * let go). Right-click an Operating Table while dragging to lay them on it.</li>
 * <li><b>Surgery Kit</b>: right-click a patient on (or next to) an Operating Table and keep holding the kit close to
 * them for 5 seconds. It fixes everything a Doctor's surgery does, and brings a collapsed patient back. Every surgery
 * makes you better at it: from a 60% success chance up to 98%. A failed one makes them bleed. Hold a Chromeflesh
 * implant in your other hand and you put it in instead. Needs modern medicine (old times: not yet).</li>
 * </ul>
 */
public final class Surgeon {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Realism.MODID);

    public static final DeferredItem<Item> SURGERY_KIT = ITEMS.register("surgery_kit", () -> new Kit(new Item.Properties().stacksTo(1).durability(64)));
    /** How many surgeries this player has done: experience, kept for life (and after death: it's your skill). */
    public static final Supplier<AttachmentType<Integer>> SURGERIES = ATTACHMENTS.register("surgeries",
            () -> AttachmentType.builder(() -> 0).serialize(com.mojang.serialization.Codec.INT).copyOnDeath().build());

    public static final int SURGERY_TICKS = 5 * 20, TABLE_RANGE = 2;
    public static final double REACH = 3, DRAG_GAP = 1.1;

    private Surgeon() {
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        ATTACHMENTS.register(modBus);
        modBus.addListener(Surgeon::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(SURGERY_KIT.get());
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    /** Is there an Operating Table within 2 blocks of this player? */
    public static boolean nearTable(Player p) {
        BlockPos at = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-TABLE_RANGE, -2, -TABLE_RANGE), at.offset(TABLE_RANGE, 1, TABLE_RANGE)))
            if (p.level().getBlockState(pos).is(Hospital.OPERATING_TABLE.get())) return true;
        return false;
    }

    /** The surgeon's chance that it goes well: 60%, +4% per surgery done, up to 98%. */
    public static float successChance(Player surgeon) {
        return Math.min(0.98f, 0.6f + 0.04f * surgeon.getData(SURGERIES));
    }

    // ---- Dragging ----

    /** Who is dragging whom: dragger -> patient. */
    static final Map<UUID, UUID> DRAGGING = new HashMap<>();

    public static ServerPlayer dragged(ServerPlayer dragger) {
        UUID id = DRAGGING.get(dragger.getUUID());
        return id == null ? null : dragger.server.getPlayerList().getPlayer(id);
    }

    public static boolean startDrag(ServerPlayer dragger, ServerPlayer patient) {
        if (!Rescue.collapsed(patient) || dragger == patient || Rescue.collapsed(dragger)) return false;
        DRAGGING.values().remove(patient.getUUID());
        DRAGGING.put(dragger.getUUID(), patient.getUUID());
        patient.stopRiding();
        say(dragger, "drag.start", patient.getName());
        patient.displayClientMessage(Component.translatable("realism.drag.you", dragger.getName()), true);
        return true;
    }

    public static void stopDrag(ServerPlayer dragger) {
        DRAGGING.remove(dragger.getUUID());
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof ServerPlayer target)) return;
        if (!p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (target.getUUID().equals(DRAGGING.get(p.getUUID()))) {
            stopDrag(p);
            say(p, "drag.stop");
        } else if (!startDrag(p, target)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    // Right-clicking an Operating Table while dragging lays the patient on it.
    @SubscribeEvent
    public static void onTable(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !event.getLevel().getBlockState(event.getPos()).is(Hospital.OPERATING_TABLE.get())) return;
        ServerPlayer patient = dragged(p);
        if (patient == null) return;
        BlockPos t = event.getPos();
        patient.teleportTo(t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5);
        stopDrag(p);
        say(p, "drag.on_table", patient.getName());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ServerPlayer patient = dragged(p);
        if (patient != null) {
            // Let go when they wake up, are too far (a teleport), or the dragger collapses too.
            if (!Rescue.collapsed(patient) || Rescue.collapsed(p) || patient.level() != p.level() || patient.distanceToSqr(p) > 36) stopDrag(p);
            else {
                Vec3 back = Vec3.directionFromRotation(0, p.getYRot()).scale(-DRAG_GAP);
                patient.teleportTo(p.getX() + back.x, p.getY(), p.getZ() + back.z);
                if (p.tickCount % 10 == 0) p.level().playSound(null, p.blockPosition(), SoundEvents.WOOL_STEP, SoundSource.PLAYERS, 0.4f, 0.7f);
            }
        }
        surgeryTick(p);
    }

    // ---- Surgery by a player ----

    private record Operation(UUID patient, long started) {
    }

    private static final Map<UUID, Operation> OPERATIONS = new HashMap<>();

    /** Starts an operation; returns the reason key if it can't (or "" when it started). */
    public static String begin(ServerPlayer surgeon, ServerPlayer patient) {
        if (surgeon == patient) return "surgeon.self";
        if (Era.oldTimes(surgeon) && Era.Data.get(surgeon.getServer()).chosen) return "surgeon.no_medicine";
        if (!nearTable(patient)) return "surgeon.no_table";
        Body b = BodyEvents.body(patient);
        boolean implant = Chrome.isImplant(surgeon.getOffhandItem());
        if (!implant && !Hospital.needsSurgery(b) && b.collapse == 0) return "surgeon.healthy";
        Afterlife.surgery(surgeon, patient); // no consent (conscious and not crouching) goes on the record God reads
        OPERATIONS.put(surgeon.getUUID(), new Operation(patient.getUUID(), surgeon.level().getGameTime()));
        b.unconscious = Math.max(b.unconscious, SURGERY_TICKS + 40); // under anesthetic for the whole thing
        say(surgeon, "surgeon.started", patient.getName());
        return "";
    }

    private static void surgeryTick(ServerPlayer surgeon) {
        Operation op = OPERATIONS.get(surgeon.getUUID());
        if (op == null) return;
        ServerPlayer patient = surgeon.server.getPlayerList().getPlayer(op.patient());
        if (patient == null || !surgeon.isHolding(SURGERY_KIT.get()) || patient.distanceToSqr(surgeon) > REACH * REACH) {
            OPERATIONS.remove(surgeon.getUUID());
            say(surgeon, "surgeon.stopped");
            return;
        }
        long t = surgeon.level().getGameTime() - op.started();
        if (t % 20 == 0) surgeon.level().playSound(null, patient.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 0.5f, 1.4f);
        if (t >= SURGERY_TICKS) finish(surgeon, patient);
    }

    /** The end of an operation: success (by the surgeon's skill) or a mistake. */
    public static boolean finish(ServerPlayer surgeon, ServerPlayer patient) {
        OPERATIONS.remove(surgeon.getUUID());
        boolean ok = surgeon.getRandom().nextFloat() < successChance(surgeon);
        surgeon.setData(SURGERIES, surgeon.getData(SURGERIES) + 1);
        ItemStack kit = surgeon.getMainHandItem().is(SURGERY_KIT.get()) ? surgeon.getMainHandItem() : surgeon.getOffhandItem();
        kit.hurtAndBreak(1, surgeon, surgeon.getMainHandItem() == kit ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        Body b = BodyEvents.body(patient);
        if (!ok) {
            Blood.bleed(patient, 2);
            b.blood = Math.max(Blood.COLLAPSE, b.blood - 0.5f);
            surgeon.displayClientMessage(Component.translatable("realism.surgeon.failed", patient.getName()), false);
            return false;
        }
        ItemStack implant = surgeon.getOffhandItem();
        if (Chrome.isImplant(implant) && Chrome.install(patient, implant)) {
            if (!surgeon.isCreative()) implant.shrink(1);
            surgeon.displayClientMessage(Component.translatable("realism.surgeon.implant", patient.getName()), false);
        } else {
            if (b.collapse > 0) {
                Rescue.revive(patient);
                Afterlife.saved(surgeon);
            }
            Hospital.operate(patient);
            surgeon.displayClientMessage(Component.translatable("realism.surgeon.done", patient.getName(), surgeon.getData(SURGERIES),
                    Math.round(successChance(surgeon) * 100)), false);
        }
        Jobs.work(surgeon, "doctor", 1);
        patient.displayClientMessage(Component.translatable("realism.surgeon.patient", surgeon.getName()), false);
        surgeon.level().playSound(null, patient.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 1.6f);
        return true;
    }

    static class Kit extends Item {
        Kit(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity target, InteractionHand hand) {
            if (!(target instanceof ServerPlayer patient) || !(user instanceof ServerPlayer surgeon)) return target instanceof Player ? InteractionResult.SUCCESS : InteractionResult.PASS;
            String why = begin(surgeon, patient);
            if (!why.isEmpty()) say(surgeon, why);
            return InteractionResult.CONSUME;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.translatable("item.realism.surgery_kit.tip"));
        }
    }

    /** For the GameTests: is this surgeon operating right now? */
    public static boolean operating(Player surgeon) {
        return OPERATIONS.containsKey(surgeon.getUUID());
    }

    static boolean levelOk(Level level) {
        return !level.isClientSide;
    }
}
