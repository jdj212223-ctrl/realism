package com.jdj125.realism;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Phase 4: the mind. Mood goes from 0 to 100 (you start at 70). Being alone, hungry, thirsty, hurt or out in the
 * dark brings it down; friends, pets, music, sunshine, food, sleep, games, TV and therapy bring it back up. Low mood
 * makes you slower and gives you less energy; very low mood is depression, which also makes you weaker. It never
 * hurts you by itself, and there is always a way back up.
 */
public final class Mind {
    public static final float START = 70, LOW = 30, DEPRESSED = 15;
    /** Ticks between two fun activities (games, TV) that still lift your mood. */
    private static final int FUN_COOLDOWN = 60 * 20;

    private Mind() {
    }

    private static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism." + key, args), true);
    }

    public static void lift(Player p, float amount) {
        Body b = BodyEvents.body(p);
        b.mood = Math.max(0, Math.min(100, b.mood + amount));
        if (b.mood >= LOW + 5) b.moodStage = 0;
    }

    /** One second of mood for this player (called from {@link BodyEvents#second}). */
    static void second(ServerPlayer p, Body b) {
        Level level = p.level();
        float d = 0;
        AABB near = p.getBoundingBox().inflate(8), far = p.getBoundingBox().inflate(24);
        boolean friend = !level.getEntitiesOfClass(Player.class, near, o -> o != p && o.isAlive() && !o.isSpectator()).isEmpty();
        boolean anyone = friend || !level.getEntitiesOfClass(Player.class, far, o -> o != p && o.isAlive() && !o.isSpectator()).isEmpty();
        boolean pet = !level.getEntitiesOfClass(TamableAnimal.class, near, a -> a.isTame() && p.getUUID().equals(a.getOwnerUUID())).isEmpty();
        if (friend) d += 0.03f;
        else if (!anyone) d -= 0.02f;
        if (pet) d += 0.03f;
        if (music(p)) d += 0.05f;
        boolean sky = level.canSeeSky(p.blockPosition());
        if (sky && level.isDay()) d += 0.01f;
        if (sky && !level.isDay()) d -= 0.01f;
        if (b.thirstStage >= 2 || p.getFoodData().getFoodLevel() <= 6) d -= 0.03f;
        if (p.getHealth() < 6) d -= 0.03f;
        if (b.bleeding > 0 || b.brokenLeg > 0 || b.burn > 0 || b.frostbite > 0 || b.armTear > 0) d -= 0.02f;
        b.mood = Math.max(0, Math.min(100, b.mood + d));
        if (b.funCooldown > 0) b.funCooldown -= 20;

        int stage = b.mood < DEPRESSED ? 2 : b.mood < LOW ? 1 : 0;
        if (stage > b.moodStage) say(p, "mood." + stage);
        b.moodStage = stage;
        if (stage >= 1) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 0, true, false, true));
        if (stage == 2) p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 50, 0, true, false, true));
    }

    /** Is a jukebox playing within 12 blocks? */
    private static boolean music(Player p) {
        BlockPos me = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(me.offset(-12, -4, -12), me.offset(12, 4, 12))) {
            BlockState s = p.level().getBlockState(pos);
            if (s.getBlock() instanceof JukeboxBlock && s.getValue(JukeboxBlock.HAS_RECORD)) return true;
        }
        return false;
    }

    /**
     * What you dreamed about, once you've slept: low mood or a long time awake makes nightmares likely (-5 mood),
     * otherwise it's usually a nice dream (+5).
     */
    static void dream(Player p, Body b) {
        float tired = b.awake / (float) (RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks());
        float nightmareChance = b.mood < LOW ? 0.6f : tired > 0.5f ? 0.4f : 0.15f;
        boolean nightmare = p.getRandom().nextFloat() < nightmareChance;
        int n = p.getRandom().nextInt(8) + 1;
        p.displayClientMessage(Component.translatable("realism." + (nightmare ? "nightmare." : "dream.") + n), false);
        lift(p, nightmare ? -5 : 5);
    }

    // Eating a meal cheers you up a little.
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof Player p && !p.level().isClientSide && event.getItem().getFoodProperties(p) != null) lift(p, 2);
    }

    /** A block you can use for fun (game console, TV): lifts your mood, once a minute. */
    static class FunBlock extends Block {
        private final String kind;

        FunBlock(Properties props, String kind) {
            super(props);
            this.kind = kind;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            Body b = BodyEvents.body(player);
            if (b.funCooldown > 0) {
                say(player, kind + ".again");
                return InteractionResult.CONSUME;
            }
            b.funCooldown = FUN_COOLDOWN;
            lift(player, 15);
            level.playSound(null, pos, kind.equals("console") ? SoundEvents.NOTE_BLOCK_BIT.value() : SoundEvents.NOTE_BLOCK_CHIME.value(),
                    SoundSource.BLOCKS, 0.8f, 1.2f);
            say(player, kind + ".fun");
            return InteractionResult.CONSUME;
        }
    }

    /** Calendar: shows the real date (and the Minecraft day). Therapy voucher: a session with the Doctor. */
    static class Paper extends Item {
        private final boolean therapy;

        Paper(Properties props, boolean therapy) {
            super(props);
            this.therapy = therapy;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            if (therapy) {
                lift(player, 40);
                player.displayClientMessage(Component.translatable("realism.therapy.done"), false);
                stack.consume(1, player);
                return InteractionResultHolder.consume(stack);
            }
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
            player.displayClientMessage(Component.translatable("realism.calendar", today, level.getDayTime() / 24000 + 1), false);
            return InteractionResultHolder.success(stack);
        }
    }
}
