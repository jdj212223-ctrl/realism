package com.jdj125.realism;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 2 medical items. Each works on yourself (use it) or on another player (use it on them):
 * bandage (stops light/heavy bleeding), tourniquet (holds severe bleeding, slows you while on), syringe (draws
 * 0.5 l into a blood bag), blood bag (gives 0.5 l: the blood type must match).
 */
public final class RealismItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Realism.MODID);

    /** The blood type inside a filled blood bag. */
    public static final Supplier<DataComponentType<String>> BLOOD_TYPE = COMPONENTS.registerComponentType("blood_type",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredItem<Item> BANDAGE = ITEMS.register("bandage", () -> new Medical(new Item.Properties().stacksTo(16), Medical.Kind.BANDAGE));
    public static final DeferredItem<Item> TOURNIQUET = ITEMS.register("tourniquet", () -> new Medical(new Item.Properties().stacksTo(4), Medical.Kind.TOURNIQUET));
    public static final DeferredItem<Item> SYRINGE = ITEMS.register("syringe", () -> new Medical(new Item.Properties().stacksTo(16), Medical.Kind.SYRINGE));
    public static final DeferredItem<Item> BLOOD_BAG = ITEMS.register("blood_bag", () -> new Medical(new Item.Properties().stacksTo(1), Medical.Kind.BLOOD_BAG));

    private RealismItems() {
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(RealismItems::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(BANDAGE.get());
            event.accept(TOURNIQUET.get());
            event.accept(SYRINGE.get());
        }
    }

    static class Medical extends Item {
        enum Kind { BANDAGE, TOURNIQUET, SYRINGE, BLOOD_BAG }

        private final Kind kind;

        Medical(Properties props, Kind kind) {
            super(props);
            this.kind = kind;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            return apply(player, player, stack) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
        }

        @Override
        public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity target, InteractionHand hand) {
            if (!(target instanceof Player patient)) return InteractionResult.PASS;
            if (user.level().isClientSide) return InteractionResult.SUCCESS;
            return apply(user, patient, user.getItemInHand(hand)) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }

        /** Uses this item from {@code user} on {@code patient} (the same player when used on yourself). */
        private boolean apply(Player user, Player patient, ItemStack stack) {
            Body b = BodyEvents.body(patient);
            switch (kind) {
                case BANDAGE -> {
                    if (b.bleeding == 0 || b.bleeding == 3) {
                        user.displayClientMessage(Component.translatable(b.bleeding == 3 ? "realism.bandage.too_much" : "realism.bandage.none"), true);
                        return false;
                    }
                    b.bleeding = 0;
                    patient.displayClientMessage(Component.translatable("realism.bandage.done"), true);
                }
                case TOURNIQUET -> {
                    if (b.bleeding != 3) {
                        user.displayClientMessage(Component.translatable("realism.tourniquet.not_needed"), true);
                        return false;
                    }
                    b.tourniquet = 1;
                    patient.displayClientMessage(Component.translatable("realism.tourniquet.done"), true);
                }
                case SYRINGE -> {
                    String type = Blood.draw(patient);
                    if (type.isEmpty()) {
                        user.displayClientMessage(Component.translatable("realism.syringe.too_little"), true);
                        return false;
                    }
                    ItemStack bag = new ItemStack(BLOOD_BAG.get());
                    bag.set(BLOOD_TYPE.get(), type);
                    if (!user.getInventory().add(bag)) user.drop(bag, false);
                    user.displayClientMessage(Component.translatable("realism.syringe.done", type), true);
                }
                case BLOOD_BAG -> {
                    if (Era.noMedicineYet(user)) return false;
                    String type = stack.getOrDefault(BLOOD_TYPE.get(), "");
                    if (b.blood >= Blood.FULL) {
                        user.displayClientMessage(Component.translatable("realism.transfusion.full"), true);
                        return false;
                    }
                    Blood.transfuse(patient, type);
                }
            }
            user.level().playSound(null, patient.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
            stack.consume(1, user);
            return true;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
            String type = stack.get(BLOOD_TYPE.get());
            if (kind == Kind.BLOOD_BAG && type != null) lines.add(Component.translatable("realism.blood_bag.type", type));
            lines.add(Component.translatable("item.realism." + kind.name().toLowerCase(java.util.Locale.ROOT) + ".tip"));
        }
    }
}
