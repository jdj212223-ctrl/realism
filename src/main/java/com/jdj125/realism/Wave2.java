package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Realism 4.2: real life, wave 2.
 * <ul>
 * <li>Germs: raw meat, rotten flesh and punching things with bare hands put germs on your hands. Eat without washing
 * them (sink, shower, swimming) and you can get sick.</li>
 * <li>Getting older: every life starts at 18 and you have a birthday every 7 Minecraft days. Past 65 you're slower
 * and weaker, and past 95 you can die peacefully of old age.</li>
 * <li>Ringing ears: an explosion close by leaves you half-deaf with a ringing for a while.</li>
 * <li>Mosquitoes: swamps at dusk and night bite you. Itchy, and once in a while a fever.</li>
 * <li>Torches burn out: a torch you place burns for 3 Minecraft days, then it's just a stick.</li>
 * <li>Warm clothes: leather clothes (a tunic and trousers) keep the cold out: no catching the flu from being soaked
 * or out in the snow without a coat.</li>
 * <li>Haircuts: your hair grows. After 10 days it's messy (a bit less happy); sneak and use shears on yourself (or on
 * a friend) for a haircut.</li>
 * </ul>
 */
public final class Wave2 {
    private Wave2() {
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("realism.life2." + key, args).withStyle(ChatFormatting.GOLD), true);
    }

    static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        return RealLife.on(v);
    }

    // ---- Germs ----

    static final int GERMS = 20 * 60 * 10; // 10 minutes unless you wash

    public static void dirtyHands(Player p) {
        BodyEvents.body(p).germs = GERMS;
    }

    public static void washHands(Player p) {
        BodyEvents.body(p).germs = 0;
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (on(RealismConfig.GERMS) && event.getEntity().getMainHandItem().isEmpty()) dirtyHands(event.getEntity());
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.GERMS) || !event.getItem().has(net.minecraft.core.component.DataComponents.FOOD)) return;
        Body b = BodyEvents.body(p);
        if (b.germs <= 0) return;
        float r = p.getRandom().nextFloat();
        if (r < 0.15f) {
            b.foodPoisoning = (int) Math.max(b.foodPoisoning, RealismConfig.dayTicks() / 2);
            say(p, "germs_sick");
        } else if (r < 0.2f && b.flu <= 0) {
            b.flu = (int) RealismConfig.dayTicks();
            say(p, "germs_flu");
        } else say(p, "germs_lucky");
    }

    // ---- Getting older ----

    public static final int START_AGE = 18, YEAR_DAYS = 7, OLD = 65, VERY_OLD = 80, OLDEST = 95;
    static final ResourceLocation OLD_AGE = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "old_age");

    public static int age(Player p) {
        Body b = BodyEvents.body(p);
        if (b.born == Long.MIN_VALUE) return START_AGE;
        return START_AGE + (int) ((p.level().getGameTime() - b.born) / (24000L * YEAR_DAYS));
    }

    static void tickAge(ServerPlayer p) {
        Body b = BodyEvents.body(p);
        if (b.born == Long.MIN_VALUE) {
            b.born = p.level().getGameTime();
            b.birthday = START_AGE;
        }
        int age = age(p);
        if (age > b.birthday) {
            b.birthday = age;
            Mind.lift(p, 15);
            p.displayClientMessage(Component.translatable("realism.life2.birthday", age).withStyle(ChatFormatting.LIGHT_PURPLE), false);
            p.serverLevel().sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 2, p.getZ(), 40, 0.6, 0.6, 0.6, 0.1);
            p.level().playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1, 1);
            if (age == OLD) say(p, "getting_old");
        }
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            if (age >= OLD) speed.addOrUpdateTransientModifier(new AttributeModifier(OLD_AGE, age >= VERY_OLD ? -0.25 : -0.1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else speed.removeModifier(OLD_AGE);
        }
        if (age >= OLDEST && on(RealismConfig.OLD_AGE_DEATH) && p.getRandom().nextFloat() < 0.002f) {
            p.displayClientMessage(Component.translatable("realism.life2.old_age_death").withStyle(ChatFormatting.GRAY), false);
            p.hurt(p.damageSources().generic(), Float.MAX_VALUE);
        }
    }

    // ---- Ringing ears ----

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!on(RealismConfig.RINGING_EARS) || !(event.getLevel() instanceof ServerLevel level)) return;
        var c = event.getExplosion().center();
        for (ServerPlayer p : level.players()) {
            double d = p.position().distanceTo(c);
            if (d > 10 || p.isSpectator()) continue;
            int ticks = (int) (20 * (10 - d) * 3); // up to 30 seconds right next to it
            Body b = BodyEvents.body(p);
            b.ringing = Math.max(b.ringing, ticks);
            if (p.connection != null && p.connection.hasChannel(Ringing.TYPE)) PacketDistributor.sendToPlayer(p, new Ringing(b.ringing));
            say(p, "ringing");
        }
    }

    public record Ringing(int ticks) implements CustomPacketPayload {
        public static final Type<Ringing> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "ringing"));
        public static final StreamCodec<ByteBuf, Ringing> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Ringing::ticks, Ringing::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- Warm clothes ----

    /** A leather tunic and trousers (any colour): warm clothes. */
    public static boolean warmClothes(Player p) {
        return isLeather(p.getItemBySlot(EquipmentSlot.CHEST)) && isLeather(p.getItemBySlot(EquipmentSlot.LEGS));
    }

    static boolean isLeather(ItemStack s) {
        return s.getItem() instanceof ArmorItem a && a.getMaterial().is(ArmorMaterials.LEATHER);
    }

    // ---- The tick: germs, age, ringing, mosquitoes, cold, hair ----

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || p.getAbilities().instabuild) return;
        Body b = BodyEvents.body(p);
        if (b.germs > 0) b.germs--;
        if (b.ringing > 0) b.ringing--;
        if (p.isInWater()) b.germs = 0;
        if (p.tickCount % 20 != 0) return;
        ServerLevel level = p.serverLevel();
        // Holding raw meat or rotten flesh: germs.
        ItemStack hand = p.getMainHandItem();
        if (on(RealismConfig.GERMS) && (hand.is(Items.ROTTEN_FLESH) || hand.is(Items.BEEF) || hand.is(Items.CHICKEN) || hand.is(Items.PORKCHOP)
                || hand.is(Items.MUTTON) || hand.is(Items.RABBIT))) b.germs = GERMS;
        if (on(RealismConfig.AGING)) tickAge(p);
        // Mosquitoes: swamps, from dusk to dawn.
        long time = level.getDayTime() % 24000;
        if (on(RealismConfig.MOSQUITOES) && (time > 12000 && time < 23500) && level.canSeeSky(p.blockPosition())
                && (level.getBiome(p.blockPosition()).is(Biomes.SWAMP) || level.getBiome(p.blockPosition()).is(Biomes.MANGROVE_SWAMP))
                && p.getRandom().nextFloat() < 0.04f) bite(p);
        // Cold without warm clothes (snowy biomes, outdoors at night): the flu can catch you.
        if (on(RealismConfig.WARM_CLOTHES) && p.tickCount % 600 == 0 && !warmClothes(p) && b.flu <= 0
                && level.getBiome(p.blockPosition()).value().getBaseTemperature() < 0.15f && level.canSeeSky(p.blockPosition())
                && !level.isDay() && !RealLife.nearHeat(p) && p.getRandom().nextFloat() < 0.1f) {
            b.flu = (int) (RealismConfig.dayTicks() * 2);
            say(p, "cold_no_coat");
        }
        // Hair
        if (on(RealismConfig.HAIR)) {
            if (b.hairCut == Long.MIN_VALUE) b.hairCut = level.getGameTime();
            long days = (level.getGameTime() - b.hairCut) / 24000L;
            if (days >= 10) {
                Mind.lift(p, -0.02f);
                if (p.tickCount % (20 * 600) == 0) say(p, "messy_hair", days);
            }
        }
    }

    public static void bite(ServerPlayer p) {
        p.hurt(p.damageSources().generic(), 0.5f);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEE_LOOP_AGGRESSIVE, SoundSource.HOSTILE, 0.5f, 2.0f);
        Mind.lift(p, -2);
        Body b = BodyEvents.body(p);
        if (p.getRandom().nextFloat() < 0.03f && b.flu <= 0) {
            b.flu = (int) RealismConfig.dayTicks();
            say(p, "mosquito_fever");
        } else say(p, "mosquito");
    }

    // ---- Haircuts ----

    public static long hairDays(Player p) {
        Body b = BodyEvents.body(p);
        return b.hairCut == Long.MIN_VALUE ? 0 : (p.level().getGameTime() - b.hairCut) / 24000L;
    }

    public static void haircut(ServerPlayer cutter, Player who) {
        Body b = BodyEvents.body(who);
        boolean messy = hairDays(who) >= 10;
        b.hairCut = who.level().getGameTime();
        Mind.lift(who, messy ? 10 : 3);
        who.level().playSound(null, who.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1, 1.3f);
        if (who.level() instanceof ServerLevel sl)
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.BROWN_WOOL)),
                    who.getX(), who.getEyeY() + 0.3, who.getZ(), 12, 0.25, 0.1, 0.25, 0.02);
        say(who, "haircut");
        cutter.getMainHandItem().hurtAndBreak(1, cutter, EquipmentSlot.MAINHAND);
    }

    @SubscribeEvent
    public static void onShearsSelf(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.HAIR) || !p.isShiftKeyDown()
                || event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Items.SHEARS)) return;
        haircut(p, p);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onShearsFriend(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !on(RealismConfig.HAIR) || !(event.getTarget() instanceof Player friend)
                || event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Items.SHEARS)) return;
        haircut(p, friend);
        event.setCanceled(true);
    }

    // ---- Torches burn out ----

    static final long TORCH_LIFE = 24000L * 3;

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!on(RealismConfig.TORCHES_BURN_OUT) || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player)) return;
        var s = event.getPlacedBlock();
        if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH))
            Torches.get(level).put(event.getPos(), level.getGameTime());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 200 != 0 || !on(RealismConfig.TORCHES_BURN_OUT)) return;
        Torches t = Torches.get(level);
        if (t.placed.isEmpty()) return;
        long now = level.getGameTime();
        int checked = 0;
        for (Iterator<Map.Entry<Long, Long>> it = t.placed.entrySet().iterator(); it.hasNext() && checked < 64; checked++) {
            var e = it.next();
            BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) continue;
            var s = level.getBlockState(pos);
            if (!(s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH))) {
                it.remove();
                t.setDirty();
                continue;
            }
            if (now - e.getValue() < TORCH_LIFE) continue;
            level.removeBlock(pos, false);
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 6, 0.05, 0.1, 0.05, 0.01);
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, new ItemStack(Items.STICK)));
            it.remove();
            t.setDirty();
        }
    }

    /** Where player-placed torches are and when they were lit. */
    public static class Torches extends SavedData {
        final Map<Long, Long> placed = new LinkedHashMap<>();

        public static Torches get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Torches::new, Torches::load, null), "realism_torches");
        }

        static Torches load(CompoundTag tag, HolderLookup.Provider reg) {
            Torches t = new Torches();
            for (Tag e : tag.getList("t", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                t.placed.put(c.getLong("p"), c.getLong("at"));
            }
            return t;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            ListTag list = new ListTag();
            placed.forEach((p, at) -> {
                CompoundTag c = new CompoundTag();
                c.putLong("p", p);
                c.putLong("at", at);
                list.add(c);
            });
            tag.put("t", list);
            return tag;
        }

        public void put(BlockPos pos, long at) {
            placed.put(pos.asLong(), at);
            setDirty();
        }
    }

}
