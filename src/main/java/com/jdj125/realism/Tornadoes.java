package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realism 4.3: tornadoes, with ProtoManly's Weather (PMWeather makes the tornadoes; Realism makes them dangerous like
 * real ones). Nothing happens without PMWeather installed.
 * <ul>
 * <li>Inside a tornado, flying debris hits you: cuts and bleeding, broken legs in strong ones (EF3+). The stronger the
 * tornado, the worse.</li>
 * <li>Shelter: a basement (underground, 3+ blocks under the surface) or an Interceptors vehicle keeps you safe. A
 * house helps against weak tornadoes, but not EF3 and up.</li>
 * <li>Heavy loads keep you on the ground: carrying over 60 kg, the wind lifts you much less.</li>
 * <li>Warnings: anyone with a Phone gets a Tornado Warning when a tornado is within 1500 blocks, and powered Tornado
 * Sirens wail.</li>
 * </ul>
 * Everything about PMWeather is read through reflection (it's closed source and optional), so a newer PMWeather that
 * changes its insides just turns this off instead of crashing.
 */
public final class Tornadoes {
    private Tornadoes() {
    }

    /** A tornado as Realism sees it: position, wind (mph), width (blocks), id. */
    public record Twister(long id, Vec3 position, int windspeed, float width) {
        public int ef() {
            return windspeed < 65 ? 0 : windspeed < 86 ? 0 : windspeed < 111 ? 1 : windspeed < 136 ? 2 : windspeed < 166 ? 3 : windspeed < 201 ? 4 : 5;
        }
    }

    private static boolean tried, works;
    private static Map<?, ?> managers;
    private static Method getStorms, isTornadic;
    private static Field fId, fPos, fWind, fWidth, fDead;

    private static boolean init() {
        if (tried) return works;
        tried = true;
        try {
            Class<?> events = Class.forName("dev.protomanly.pmweather.event.GameBusEvents");
            managers = (Map<?, ?>) events.getField("MANAGERS").get(null);
            Class<?> handler = Class.forName("dev.protomanly.pmweather.weather.WeatherHandler");
            getStorms = handler.getMethod("getStorms");
            Class<?> storm = Class.forName("dev.protomanly.pmweather.weather.Storm");
            isTornadic = storm.getMethod("isTornadic");
            fId = storm.getField("ID");
            fPos = storm.getField("position");
            fWind = storm.getField("windspeed");
            fWidth = storm.getField("width");
            fDead = storm.getField("dead");
            works = true;
        } catch (Throwable t) {
            works = false; // no PMWeather (or a version we don't understand): no tornadoes
        }
        return works;
    }

    public static boolean available() {
        return init();
    }

    /** For tests: tornadoes Realism should pretend exist (empty in normal play). */
    public static final List<Twister> FAKE = new ArrayList<>();

    /** Live tornadoes in this level. */
    public static List<Twister> all(Level level) {
        List<Twister> out = new ArrayList<>(FAKE);
        if (!init()) return out;
        try {
            Object handler = managers.get(level.dimension());
            if (handler == null) return out;
            for (Object s : new ArrayList<>((List<?>) getStorms.invoke(handler))) {
                if (s == null || fDead.getBoolean(s) || !(Boolean) isTornadic.invoke(s)) continue;
                Vec3 pos = (Vec3) fPos.get(s);
                if (pos == null) continue;
                out.add(new Twister(fId.getLong(s), pos, fWind.getInt(s), fWidth.getFloat(s)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static Twister nearest(Level level, Vec3 from) {
        Twister best = null;
        double bd = Double.MAX_VALUE;
        for (Twister t : all(level)) {
            double d = flat(t.position(), from);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    static double flat(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Safe from debris here? */
    public static boolean sheltered(ServerPlayer p, Twister t) {
        if (p.getVehicle() != null && p.getVehicle().getClass().getName().startsWith("com.interceptors")) return true;
        BlockPos at = p.blockPosition();
        int surface = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        if (at.getY() <= surface - 4) return true; // a basement: solid ground (or floors) 3+ blocks over your head
        return t.ef() < 3 && at.getY() + 1 < surface; // a roof (a house) holds against weak ones
    }

    private static final Set<Long> SEEN = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Set<Long>> WARNED = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || !RealLife.on(RealismConfig.TORNADO_DANGER)) return;
        if (p.tickCount % 10 != 0) return;
        Twister t = nearest(p.level(), p.position());
        if (t == null) return;
        double d = flat(t.position(), p.position());
        if (SEEN.add(t.id())) Wave3.headline(p.server, "tornado", t.ef());
        // Warnings on the phone
        if (d < 1500 && p.getInventory().contains(new net.minecraft.world.item.ItemStack(World.PHONE.get()))
                && WARNED.computeIfAbsent(p.getUUID(), u -> new HashSet<>()).add(t.id())) {
            p.displayClientMessage(Component.translatable("realism.tornado.warning", "EF" + t.ef(), t.windspeed(), (int) d,
                    compass(t.position().subtract(p.position()))).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
            p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1, 0.5f);
        }
        if (p.getAbilities().instabuild || d > t.width() / 2 + 8) return;
        // Inside it: heavy loads keep your feet on the ground.
        if (Weight.carried(p) > Weight.NO_JUMP && p.getDeltaMovement().y > 0)
            p.setDeltaMovement(p.getDeltaMovement().multiply(0.6, 0.35, 0.6));
        if (sheltered(p, t)) return;
        if (p.tickCount % 20 == 0 && p.getRandom().nextFloat() < 0.15f + t.ef() * 0.12f) debris(p, t);
    }

    public static void debris(ServerPlayer p, Twister t) {
        int ef = t.ef();
        p.hurt(p.damageSources().flyIntoWall(), 1 + ef * 1.5f);
        p.level().playSound(null, p.blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.WEATHER, 1, 0.7f);
        if (p.getRandom().nextFloat() < 0.25f + ef * 0.1f) Blood.bleed(p, ef >= 4 ? 3 : ef >= 2 ? 2 : 1);
        if (ef >= 3 && p.getRandom().nextFloat() < 0.12f) {
            Body b = BodyEvents.body(p);
            b.brokenLeg = (int) Math.max(b.brokenLeg, RealismConfig.dayTicks() * 2);
            p.displayClientMessage(Component.translatable("realism.tornado.leg"), true);
        } else p.displayClientMessage(Component.translatable("realism.tornado.debris"), true);
    }

    static String compass(Vec3 d) {
        double a = Math.toDegrees(Math.atan2(d.x, -d.z));
        if (a < 0) a += 360;
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[Math.floorMod((int) Math.round(a / 45.0), 8)];
    }

    // ---- The siren ----

    /** Whether a powered siren here should wail. */
    public static boolean sirenShouldWail(ServerLevel level, BlockPos pos) {
        Twister t = nearest(level, pos.getCenter());
        return t != null && flat(t.position(), pos.getCenter()) < 1500 && Power.powered(level, pos);
    }
}
