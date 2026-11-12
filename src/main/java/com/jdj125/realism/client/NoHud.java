package com.jdj125.realism.client;

import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Set;

/**
 * Realism 3.1, client side: real life has no HUD (no hearts, hunger, armor, air or XP bars; the hotbar and crosshair
 * stay), and Create World only offers the Game tab, with creative mode and cheats locked off.
 */
@EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
public final class NoHud {
    private NoHud() {
    }

    static final Set<ResourceLocation> HIDDEN = Set.of(VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.EXPERIENCE_BAR,
            VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH);

    public static boolean on() {
        try {
            return RealismConfig.NO_HUD.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (on() && HIDDEN.contains(event.getName())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;
        boolean lock;
        try {
            lock = RealismConfig.LOCK_WORLD_CREATION.get();
        } catch (IllegalStateException notLoadedYet) {
            lock = true;
        }
        if (!lock || com.jdj125.realism.NoCheats.devGame()) return;
        // Only the Game tab: hide the World and More tab buttons.
        for (var child : screen.children())
            if (child instanceof TabNavigationBar bar) {
                int i = 0;
                for (var tab : bar.children()) {
                    if (i++ > 0 && tab instanceof AbstractWidget w) {
                        w.visible = false;
                        w.active = false;
                    }
                }
            }
        // Survival (or hardcore) only, and no cheats, whatever buttons get pressed.
        WorldCreationUiState ui = screen.getUiState();
        ui.addListener(NoHud::force);
        force(ui);
    }

    private static void force(WorldCreationUiState ui) {
        if (ui.getGameMode() == WorldCreationUiState.SelectedGameMode.CREATIVE) ui.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        else if (ui.isAllowCommands()) ui.setAllowCommands(false);
    }
}
