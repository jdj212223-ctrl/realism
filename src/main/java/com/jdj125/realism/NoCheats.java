package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Locale;

/**
 * Realism 3.1: no cheating in real life. Players (ops too) can't run commands like /give or /gamemode, only the ones
 * the server allows (Realism's own and talking). The server console, command blocks and datapacks still work. And
 * creative mode switches back to survival by itself.
 */
public final class NoCheats {
    private NoCheats() {
    }

    /** Realism's own commands always work: they're part of the game, not cheats. */
    static final java.util.Set<String> REALISM = java.util.Set.of("pay", "country", "job", "law", "ambulance", "era", "realism", "fire", "vote", "tip", "911", "loan");

    /** The command's name without the slash, e.g. "give" for "/give @s dirt". */
    public static String root(String input) {
        String s = input.strip();
        if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        String name = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':'); // minecraft:give
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /**
     * The developer's test game (run from the code, not a real install): cheats stay on there, so new things can be
     * tested with /give and creative. Never true in a normal game, on a server, or in the automatic tests.
     */
    public static boolean devGame() {
        return !net.neoforged.fml.loading.FMLEnvironment.production && net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
    }

    public static boolean allowed(String input) {
        if (!RealismConfig.NO_COMMANDS.get() || devGame()) return true;
        String name = root(input);
        if (REALISM.contains(name)) return true;
        return RealismConfig.ALLOWED_COMMANDS.get().stream().anyMatch(a -> a.equalsIgnoreCase(name));
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer p)) return; // console, command blocks, functions: fine
        if (allowed(event.getParseResults().getReader().getString())) return;
        event.setCanceled(true);
        p.displayClientMessage(Component.translatable("realism.rules.no_commands").withStyle(ChatFormatting.RED), false);
    }

    /** Creative back to survival. (Uses the real game mode: test players always say they're creative.) */
    public static boolean fixGameMode(ServerPlayer p) {
        if (!RealismConfig.SURVIVAL_ONLY.get() || devGame() || p.gameMode.getGameModeForPlayer() != GameType.CREATIVE) return false;
        p.setGameMode(GameType.SURVIVAL);
        p.displayClientMessage(Component.translatable("realism.rules.no_creative").withStyle(ChatFormatting.RED), false);
        return true;
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 20 == 0) fixGameMode(p);
    }
}
