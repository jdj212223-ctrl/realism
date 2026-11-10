package com.jdj125.realism;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.Codec;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Realism 2.0.2, jobs. Pick one with /job join; do the work; get paid once a day (your country takes its tax).
 * <ul>
 * <li>Doctor: surgeries and defib saves. Chef: cooking food (furnace, smoker, Grandma's Stove).</li>
 * <li>Farmer: harvesting fully grown crops. Miner: mining ores. Builder: placing blocks.</li>
 * <li>Police: catching criminals (hitting someone with a crime on their record).</li>
 * </ul>
 */
public final class Jobs {
    private Jobs() {}

    /** Pay per piece of work, and the most one day can pay. */
    public static final Map<String, Integer> PAY = Map.of("doctor", 30, "chef", 4, "farmer", 2, "miner", 3, "builder", 1, "police", 10);
    public static final List<String> NAMES = List.of("doctor", "chef", "farmer", "miner", "builder", "police");
    public static final int DAILY_CAP = 250;

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Realism.MODID);
    public static final Supplier<AttachmentType<String>> JOB = ATTACHMENTS.register("job",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());
    public static final Supplier<AttachmentType<Integer>> WORK = ATTACHMENTS.register("work_today",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    public static final Supplier<AttachmentType<Long>> LAST_PAY = ATTACHMENTS.register("last_pay",
            () -> AttachmentType.builder(() -> 0L).serialize(Codec.LONG).build());

    static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    /** Someone did a piece of work: it only counts if it's their job. */
    public static void work(Player p, String job, int pieces) {
        if (!RealismConfig.JOBS.get() || !(p instanceof ServerPlayer) || !job.equals(p.getData(JOB))) return;
        p.setData(WORK, p.getData(WORK) + pieces);
    }

    /** Today's pay before tax. */
    public static long wage(Player p) {
        Integer rate = PAY.get(p.getData(JOB));
        return rate == null ? 0 : Math.min(DAILY_CAP, (long) rate * p.getData(WORK));
    }

    /** Payday: the day's wage (taxed by your country), then a fresh day. Returns what was paid. */
    public static long payday(ServerPlayer p) {
        long gross = wage(p);
        p.setData(WORK, 0);
        p.setData(LAST_PAY, p.serverLevel().getGameTime());
        if (gross <= 0) return 0;
        long kept = World.earn(p, gross);
        p.displayClientMessage(Component.translatable("realism.job.payday", gross, kept, Component.translatable("realism.job." + p.getData(JOB))), false);
        Mind.lift(p, 5);
        return kept;
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 100 != 0 || p.getData(JOB).isEmpty()) return;
        long now = p.serverLevel().getGameTime();
        if (p.getData(LAST_PAY) == 0) p.setData(LAST_PAY, now);
        else if (now - p.getData(LAST_PAY) >= RealismConfig.dayTicks()) payday(p);
    }

    // ---- What counts as work ----

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        BlockState s = event.getState();
        if (s.is(Tags.Blocks.ORES)) work(p, "miner", 1);
        if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) work(p, "farmer", 1);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player p) work(p, "builder", 1);
    }

    @SubscribeEvent
    public static void onSmelt(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getSmelting().has(net.minecraft.core.component.DataComponents.FOOD)) work(event.getEntity(), "chef", event.getSmelting().getCount());
    }

    @SubscribeEvent
    public static void onHit(LivingDamageEvent.Post event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer cop) || !(event.getEntity() instanceof ServerPlayer suspect) || cop == suspect) return;
        if (!"police".equals(cop.getData(JOB))) return;
        if (Justice.Data.get(cop.serverLevel()).records.getOrDefault(suspect.getUUID(), List.of()).isEmpty()) return;
        work(cop, "police", 1);
    }

    // ---- /job ----

    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("job")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    String job = p.getData(JOB);
                    if (job.isEmpty()) ctx.getSource().sendSuccess(() -> Component.translatable("realism.job.none"), false);
                    else ctx.getSource().sendSuccess(() -> Component.translatable("realism.job.status",
                            Component.translatable("realism.job." + job), p.getData(WORK), wage(p)), false);
                    return 1;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    for (String j : NAMES)
                        ctx.getSource().sendSuccess(() -> Component.translatable("realism.job.line", Component.translatable("realism.job." + j),
                                PAY.get(j), Component.translatable("realism.job." + j + ".how")), false);
                    return 1;
                }))
                .then(Commands.literal("join").then(Commands.argument("job", StringArgumentType.word())
                        .suggests((c, b) -> { NAMES.forEach(b::suggest); return b.buildFuture(); })
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String job = StringArgumentType.getString(ctx, "job").toLowerCase(java.util.Locale.ROOT);
                            if (!NAMES.contains(job)) {
                                ctx.getSource().sendFailure(Component.translatable("realism.job.unknown"));
                                return 0;
                            }
                            if (!p.getData(JOB).isEmpty()) payday(p); // paid for what you did before switching
                            p.setData(JOB, job);
                            p.setData(WORK, 0);
                            p.setData(LAST_PAY, p.serverLevel().getGameTime());
                            ctx.getSource().sendSuccess(() -> Component.translatable("realism.job.joined", Component.translatable("realism.job." + job)), false);
                            return 1;
                        })))
                .then(Commands.literal("quit").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    payday(p);
                    p.setData(JOB, "");
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.job.quit"), false);
                    return 1;
                })));
    }
}
