package com.jdj125.realism;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /realism: shows your body's state. /realism set thirst|sleep|stamina|blood &lt;0..1&gt;, /realism burn &lt;1-3&gt; and
 * /realism bleed &lt;1-3&gt; are for testing (ops only).
 */
public final class RealismCommand {
    private RealismCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("realism")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    BodySync s = BodyEvents.sync(p);
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.status",
                            Math.round(s.thirst() * 100), Math.round(s.awake() * 100), Math.round(s.stamina()),
                            String.format(java.util.Locale.ROOT, "%.1f", s.blood()), Blood.type(p)), false);
                    return 1;
                })
                .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("stat", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((ctx, b) -> { b.suggest("thirst"); b.suggest("sleep"); b.suggest("stamina"); b.suggest("blood"); return b.buildFuture(); })
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 1)).executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Body b = BodyEvents.body(p);
                                    float v = FloatArgumentType.getFloat(ctx, "value");
                                    switch (com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "stat")) {
                                        case "thirst" -> b.thirst = (long) (v * RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks());
                                        case "sleep" -> b.awake = (long) (v * RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks());
                                        case "stamina" -> b.stamina = v * 100;
                                        case "blood" -> b.blood = v * Blood.FULL;
                                        default -> { return 0; }
                                    }
                                    return 1;
                                }))))
                .then(Commands.literal("bleed").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                            Blood.bleed(ctx.getSource().getPlayerOrException(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"));
                            return 1;
                        })))
                .then(Commands.literal("burn").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("degree", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                            BodyEvents.burn(ctx.getSource().getPlayerOrException(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "degree"));
                            return 1;
                        }))));
    }
}
