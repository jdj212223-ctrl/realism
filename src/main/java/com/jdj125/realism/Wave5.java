package com.jdj125.realism;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Realism 4.5: real life, wave 5. Loans and debt, 911 calls with an operator, chores, floods, the dentist and libraries
 * (two more console games are in the console screen).
 */
public final class Wave5 {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);

    public static final DeferredBlock<Block> DENTIST_CHAIR = BLOCKS.register("dentist_chair",
            () -> new DentistChair(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> DENTIST_CHAIR_ITEM = ITEMS.registerSimpleBlockItem(DENTIST_CHAIR);
    public static final DeferredItem<Item> BROOM = ITEMS.register("broom", () -> new Broom(new Item.Properties().durability(200)));

    private Wave5() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(DENTIST_CHAIR_ITEM.get());
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(BROOM.get());
        });
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        return RealLife.on(v);
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life5." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    // ---- Loans and debt ----

    public static final long MAX_LOAN = 1000, INTEREST_PERCENT = 3;

    /** Takes a loan from the bank. Returns a result key. */
    public static String borrow(ServerPlayer p, long amount) {
        Body b = BodyEvents.body(p);
        if (amount <= 0) return "loan.bad";
        if (b.debt + amount > MAX_LOAN) return "loan.too_much";
        b.debt += amount + amount / 10; // a 10% fee up front
        if (b.loanDay == 0) b.loanDay = p.level().getGameTime() / 24000L;
        World.addMoney(p, amount);
        return "loan.ok";
    }

    public static String repay(ServerPlayer p, long amount) {
        Body b = BodyEvents.body(p);
        long pay = Math.min(Math.min(amount, b.debt), World.balance(p));
        if (pay <= 0) return b.debt <= 0 ? "loan.none" : "loan.broke";
        World.addMoney(p, -pay);
        b.debt -= pay;
        if (b.debt <= 0) b.loanDay = 0;
        return b.debt <= 0 ? "loan.paid_off" : "loan.repaid";
    }

    /** Payday sends half your (already paid) wage to the bank until the debt is gone. Returns how much it took. */
    public static long garnish(ServerPlayer p, long kept) {
        Body b = BodyEvents.body(p);
        if (b.debt <= 0 || kept <= 0 || !on(RealismConfig.LOANS)) return 0;
        long take = Math.min(b.debt, kept / 2);
        World.addMoney(p, -take);
        b.debt -= take;
        if (b.debt <= 0) b.loanDay = 0;
        say(p, "loan.garnished", take, b.debt);
        return take;
    }

    // ---- 911 ----

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var d = event.getDispatcher();
        d.register(Commands.literal("911").executes(ctx -> {
            operator(ctx.getSource().getPlayerOrException());
            return 1;
        }).then(Commands.argument("service", StringArgumentType.word()).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            String r = dispatch(p, StringArgumentType.getString(ctx, "service"));
            p.sendSystemMessage(Component.translatable("realism.life5.911." + r).withStyle(ChatFormatting.AQUA));
            return 1;
        })));
        d.register(Commands.literal("loan").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.sendSystemMessage(Component.translatable("realism.life5.loan.status", BodyEvents.body(p).debt, MAX_LOAN));
            return 1;
        }).then(Commands.literal("take").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.sendSystemMessage(Component.translatable("realism.life5." + borrow(p, IntegerArgumentType.getInteger(ctx, "amount")), BodyEvents.body(p).debt));
            return 1;
        }))).then(Commands.literal("pay").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.sendSystemMessage(Component.translatable("realism.life5." + repay(p, IntegerArgumentType.getInteger(ctx, "amount")), BodyEvents.body(p).debt));
            return 1;
        }))));
    }

    /** "911, what's your emergency?" with buttons to click in chat. */
    public static void operator(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable("realism.life5.911.hello").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        Component line = Component.empty();
        for (String s : new String[]{"ambulance", "fire", "police"}) {
            line = line.copy().append(Component.translatable("realism.life5.911.button." + s).withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/911 " + s))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("realism.life5.911.hover." + s))))).append("  ");
        }
        p.sendSystemMessage(line);
    }

    /** What the operator does: sends an ambulance, the fire department, or tells the police (your country's leader). */
    public static String dispatch(ServerPlayer p, String service) {
        if (!on(RealismConfig.EMERGENCY_911)) return "off";
        switch (service.toLowerCase()) {
            case "ambulance" -> {
                String r = World.callAmbulance(p);
                return r.equals("phone.ok") ? "sent_ambulance" : "cant_send";
            }
            case "fire" -> {
                String r = Fire.callFireDepartment(p);
                return r.equals("phone.ok") ? "sent_fire" : "cant_send";
            }
            case "police" -> {
                World.Data data = World.Data.get(p.serverLevel());
                World.Country c = data.countryAt(p.blockPosition());
                if (c == null) c = data.countryOf(p.getUUID());
                ServerPlayer chief = c == null ? null : p.server.getPlayerList().getPlayer(c.leader);
                if (chief == null) return "no_police";
                chief.sendSystemMessage(Component.translatable("realism.life5.911.police_call", p.getName(), p.getBlockX(), p.getBlockY(), p.getBlockZ())
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                return "sent_police";
            }
            default -> {
                return "unknown";
            }
        }
    }

    // ---- Chores ----

    public static void choreDone(ServerPlayer p, String what) {
        Body b = BodyEvents.body(p);
        if (b.chores <= 0) {
            say(p, "chores.clean");
            return;
        }
        b.chores--;
        Mind.lift(p, 2);
        say(p, "chores." + what, b.chores);
    }

    static class Broom extends Item {
        Broom(Properties props) {
            super(props);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            if (ctx.getPlayer() instanceof ServerPlayer p && !p.getCooldowns().isOnCooldown(this)) {
                p.getCooldowns().addCooldown(this, 100);
                ((ServerLevel) p.level()).sendParticles(ParticleTypes.POOF, ctx.getClickLocation().x, ctx.getClickLocation().y + 0.1, ctx.getClickLocation().z,
                        8, 0.4, 0.05, 0.4, 0.01);
                p.level().playSound(null, ctx.getClickedPos(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 1, 1);
                choreDone(p, "swept");
                ctx.getItemInHand().hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
    }

    /** Washing a bowl at the sink: dishes done. Reading at a bookshelf: the library. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getHand() != InteractionHand.MAIN_HAND) return;
        BlockState s = p.level().getBlockState(event.getPos());
        ItemStack hand = p.getMainHandItem();
        if (s.is(Life.SINK.get()) && hand.is(Items.BOWL) && on(RealismConfig.CHORES)) {
            choreDone(p, "dishes");
            event.setCanceled(true);
            return;
        }
        if (s.is(Blocks.BOOKSHELF) && hand.isEmpty() && on(RealismConfig.LIBRARIES)) {
            read(p, event.getPos());
            event.setCanceled(true);
        }
    }

    // ---- Libraries ----

    public static final int FACTS = 24;

    static int shelvesNear(Level level, BlockPos at) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(6, 3, 6)))
            if (level.getBlockState(q).is(Blocks.BOOKSHELF)) n++;
        return n;
    }

    public static void read(ServerPlayer p, BlockPos shelf) {
        if (p.getCooldowns().isOnCooldown(Items.BOOKSHELF)) {
            say(p, "library.slow");
            return;
        }
        p.getCooldowns().addCooldown(Items.BOOKSHELF, 20 * 30);
        int fact = p.getRandom().nextInt(FACTS) + 1;
        p.sendSystemMessage(Component.literal("📚 ").append(Component.translatable("realism.life5.fact." + fact)).withStyle(ChatFormatting.GRAY));
        Body b = BodyEvents.body(p);
        boolean library = shelvesNear(p.level(), shelf) >= 10;
        b.education = Math.min(100, b.education + (library ? 2 : 1));
        Mind.lift(p, 1);
        if (library) say(p, "library.read", b.education);
    }

    // ---- The dentist ----

    public static final long DENTIST_PRICE = 20;

    static class DentistChair extends Block {
        DentistChair(Properties props) {
            super(props);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (p instanceof ServerPlayer sp) dentist(sp);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public static boolean dentist(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        if (b.teeth >= 95) {
            say(p, "dentist.healthy");
            return false;
        }
        if (World.balance(p) < DENTIST_PRICE) {
            say(p, "dentist.money", DENTIST_PRICE);
            return false;
        }
        World.addMoney(p, -DENTIST_PRICE);
        b.teeth = 100;
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEE_LOOP, SoundSource.PLAYERS, 1, 2f); // the drill
        p.displayClientMessage(Component.translatable("realism.life5.dentist.done", DENTIST_PRICE).withStyle(ChatFormatting.GREEN), false);
        return true;
    }

    // ---- Floods ----

    public static class Floods extends SavedData {
        public final Set<Long> water = new LinkedHashSet<>();

        public static Floods get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Floods::new, Floods::load, null), "realism_floods");
        }

        static Floods load(CompoundTag tag, HolderLookup.Provider reg) {
            Floods f = new Floods();
            for (long l : tag.getLongArray("w")) f.water.add(l);
            return f;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            tag.put("w", new LongArrayTag(water.stream().mapToLong(Long::longValue).toArray()));
            return tag;
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.getGameTime() % 400 != 0) return;
        Floods f = Floods.get(level);
        if (level.isThundering() && on(RealismConfig.FLOODS) && !level.players().isEmpty() && level.getRandom().nextFloat() < 0.3f) {
            ServerPlayer p = level.players().get(level.getRandom().nextInt(level.players().size()));
            flood(level, p.blockPosition(), 6);
        } else if (!level.isRaining() && !f.water.isEmpty()) recede(level);
        // Chores pile up: a few new ones every day.
        if (level.getGameTime() % 24000 == 0 && on(RealismConfig.CHORES))
            for (ServerPlayer p : level.players()) {
                Body b = BodyEvents.body(p);
                b.chores = Math.min(10, b.chores + 3);
                if (b.chores >= 6) say(p, "chores.pile", b.chores);
            }
    }

    /** Rising water near rivers, swamps and the coast: low spots next to water flood. Returns how many. */
    public static int flood(ServerLevel level, BlockPos near, int max) {
        if (!level.getBiome(near).is(BiomeTags.IS_RIVER) && !level.getBiome(near).is(BiomeTags.IS_BEACH)
                && !level.getBiome(near).is(net.minecraft.world.level.biome.Biomes.SWAMP)) return 0;
        Floods f = Floods.get(level);
        int placed = 0;
        var r = level.getRandom();
        for (int i = 0; i < 80 && placed < max; i++) {
            int x = near.getX() + r.nextInt(49) - 24, z = near.getZ() + r.nextInt(49) - 24;
            BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (top.getY() > level.getSeaLevel() + 1 || !level.getBlockState(top).isAir()) continue;
            boolean byWater = false;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) byWater |= level.getFluidState(top.relative(d)).isSource() || level.getFluidState(top.below()).isSource();
            if (!byWater) continue;
            level.setBlockAndUpdate(top, Blocks.WATER.defaultBlockState());
            f.water.add(top.asLong());
            placed++;
        }
        if (placed > 0) {
            f.setDirty();
            for (ServerPlayer p : level.players())
                if (p.blockPosition().distSqr(near) < 96 * 96) say(p, "flood");
        }
        return placed;
    }

    /** After the storm, the flood water drains away. */
    public static void recede(ServerLevel level) {
        Floods f = Floods.get(level);
        int n = 0;
        for (var it = f.water.iterator(); it.hasNext() && n < 64; n++) {
            BlockPos p = BlockPos.of(it.next());
            if (!level.isLoaded(p)) continue;
            if (level.getBlockState(p).is(Blocks.WATER)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            it.remove();
        }
        f.setDirty();
    }

    // ---- The tick: debt and chores ----

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 1200 != 0) return;
        Body b = BodyEvents.body(p);
        long day = p.level().getGameTime() / 24000L;
        // Interest: 3% a day, added once a day.
        if (b.debt > 0 && on(RealismConfig.LOANS) && b.loanDay > 0 && day > b.loanDay) {
            long days = day - b.loanDay;
            for (int i = 0; i < Math.min(days, 30); i++) b.debt += Math.max(1, b.debt * INTEREST_PERCENT / 100);
            b.loanDay = day;
            if (b.debt > MAX_LOAN) {
                Mind.lift(p, -5);
                say(p, "loan.collectors", b.debt);
            }
        }
        if (b.chores >= 9 && on(RealismConfig.CHORES)) Mind.lift(p, -1);
    }
}
