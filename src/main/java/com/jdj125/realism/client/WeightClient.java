package com.jdj125.realism.client;

import com.jdj125.realism.Realism;
import com.jdj125.realism.Weight;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Realism 4.0, client side: every item's weight on its tooltip, and what you carry in the inventory screen. */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class WeightClient {
    private WeightClient() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (!Weight.on() || event.getItemStack().isEmpty()) return;
        var s = event.getItemStack();
        String text = s.getCount() > 1 ? Weight.kg(Weight.of(s)) + " (" + Weight.kg(Weight.stack(s)) + ")" : Weight.kg(Weight.of(s));
        event.getToolTip().add(Component.translatable("realism.weight.tooltip", text).withStyle(ChatFormatting.DARK_GRAY));
        var inside = s.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (inside != null && (s.getItem() instanceof net.minecraft.world.item.BlockItem b)
                && !(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) {
            int count = 0;
            for (var ignored : inside.nonEmptyItems()) count++;
            event.getToolTip().add(Component.translatable("realism.weight.packed", count).withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public static void onInventory(ScreenEvent.Render.Post event) {
        if (!Weight.on() || !(event.getScreen() instanceof InventoryScreen screen)) return;
        var p = Minecraft.getInstance().player;
        if (p == null || p.getAbilities().instabuild) return;
        float w = Weight.carried(p);
        int color = w > Weight.MAX ? 0xFF5555 : w > Weight.NO_JUMP ? 0xFF8844 : w > Weight.NO_SPRINT ? 0xFFCC44 : w > Weight.SLOW ? 0xFFFF88 : 0xCCCCCC;
        var g = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        Component text = Component.translatable("realism.weight.carrying", Weight.kg(w), (int) Weight.MAX);
        g.drawString(font, text, screen.getGuiLeft() + (176 - font.width(text)) / 2, screen.getGuiTop() - 11, color);
    }
}
