package com.jdj125.realism;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * Realism 4.14: RealismPhoneOS 2, the phone's everyday life.
 * <ul>
 * <li>Battery: it drains slowly while you carry the phone (about 1% a minute). Charge it overnight (sleep in a bed) or
 * stand next to a computer. At 0% it's dead: no calls, no texts, no apps.</li>
 * <li>Software Update (Settings): takes 30 seconds. If the battery dies in the middle, the update is corrupted and the
 * phone is bricked (restore it at a computer). The phone warns you below 20%.</li>
 * <li>Messages: your texts are saved on the phone (the last 40), with unread ones on the lock screen.</li>
 * <li>Bank app: send money to someone (like /pay, taxed the same way).</li>
 * <li>Health and Weather apps read your body and the weather outside (and whether your house is warm, see
 * {@link ColdHouse}).</li>
 * </ul>
 */
public final class PhoneLife {
    private PhoneLife() {
    }

    public static final String VERSION = "2.0", NEXT_VERSION = "2.1";
    public static final int UPDATE_SECONDS = 30, MAX_MESSAGES = 40, DRAIN_SECONDS = 60;
    /** Seconds since the battery last dropped, per player (not saved: at most a minute lost on a restart). */
    private static final java.util.Map<java.util.UUID, Integer> DRAIN = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- Battery ----

    public static int battery(CompoundTag os) {
        return os.contains("battery") ? os.getInt("battery") : 100;
    }

    public static int battery(ItemStack phone) {
        return battery(PhoneOS.os(phone));
    }

    public static void setBattery(ItemStack phone, int pct) {
        CompoundTag os = PhoneOS.os(phone);
        os.putInt("battery", Math.max(0, Math.min(100, pct)));
        PhoneOS.save(phone, os);
    }

    public static boolean dead(ItemStack phone) {
        return battery(phone) <= 0;
    }

    /** Charges the phone you carry (sleeping: a full night on the charger). */
    public static void charge(ServerPlayer p, int pct) {
        ItemStack phone = PhoneOS.phoneOf(p);
        if (!phone.isEmpty()) setBattery(phone, battery(phone) + pct);
    }

    static boolean nearComputer(ServerPlayer p) {
        BlockPos me = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(me.offset(-3, -2, -3), me.offset(3, 2, 3)))
            if (PhoneOS.isComputer(p.level().getBlockState(pos).getBlock())) return true;
        return false;
    }

    public static String version(CompoundTag os) {
        return os.contains("version") ? os.getString("version") : VERSION;
    }

    /** One second with the phone in your pocket: battery, charging, a running update. */
    public static void second(ServerPlayer p, ItemStack phone) {
        CompoundTag os = PhoneOS.os(phone);
        int battery = battery(os);
        long now = p.level().getGameTime();
        boolean changed = false;
        int ticker = DRAIN.merge(p.getUUID(), 1, Integer::sum);
        boolean updating = os.contains("updateEnd");
        if (battery < 100 && now % 2 == 0 && nearComputer(p)) {
            battery = Math.min(100, battery + 2);
            changed = true;
            if (battery == 100) p.displayClientMessage(Component.translatable("realism.phonelife.charged").withStyle(ChatFormatting.GREEN), true);
        } else if (battery > 0 && ticker >= (updating ? 3 : DRAIN_SECONDS)) {
            // An update works the phone hard: the battery goes 20 times faster.
            battery--;
            ticker = 0;
            changed = true;
            if (battery == 10) p.displayClientMessage(Component.translatable("realism.phonelife.low").withStyle(ChatFormatting.RED), true);
            if (battery == 0) p.displayClientMessage(Component.translatable("realism.phonelife.died").withStyle(ChatFormatting.RED), false);
        }
        DRAIN.put(p.getUUID(), ticker);
        os.putInt("battery", battery);
        if (updating) {
            if (battery <= 0) {
                // The battery died mid-update: a half-written system.
                os.remove("updateEnd");
                os.putBoolean("badUpdate", true);
                os.putBoolean("on", false);
                p.displayClientMessage(Component.translatable("realism.phonelife.update_bricked").withStyle(ChatFormatting.RED), false);
                p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.6f, 0.6f);
            } else if (now >= os.getLong("updateEnd")) {
                os.remove("updateEnd");
                os.putString("version", NEXT_VERSION);
                os.putBoolean("on", false); // it restarts into the new version
                p.displayClientMessage(Component.translatable("realism.phonelife.updated", NEXT_VERSION).withStyle(ChatFormatting.GREEN), false);
                p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
            }
            changed = true;
        }
        if (changed) PhoneOS.save(phone, os);
    }

    /** Starts the software update. Returns the message key. */
    public static String startUpdate(ServerPlayer p, ItemStack phone) {
        CompoundTag os = PhoneOS.os(phone);
        if (version(os).equals(NEXT_VERSION)) return "phonelife.up_to_date";
        if (os.contains("updateEnd")) return "phonelife.updating";
        os.putLong("updateEnd", p.level().getGameTime() + UPDATE_SECONDS * 20L);
        PhoneOS.save(phone, os);
        return battery(os) < 20 ? "phonelife.update_low" : "phonelife.update_started";
    }

    // ---- Messages ----

    /** Saves a text on a phone: who it's with, whether you sent it, and the words. */
    public static void record(ItemStack phone, String who, boolean mine, String text) {
        if (phone.isEmpty()) return;
        CompoundTag os = PhoneOS.os(phone);
        ListTag list = os.getList("msgs", Tag.TAG_COMPOUND);
        CompoundTag m = new CompoundTag();
        m.putString("who", who);
        m.putBoolean("me", mine);
        m.putString("text", text);
        m.putString("at", World.now());
        list.add(m);
        while (list.size() > MAX_MESSAGES) list.remove(0);
        os.put("msgs", list);
        if (!mine) os.putInt("unread", os.getInt("unread") + 1);
        PhoneOS.save(phone, os);
    }

    /** Text from players can't contain the phone summary's separators. */
    public static String safe(String s) {
        return s.replace(';', ';').replace('=', '═').replace(PhoneApps.FIELD, ' ').replace(PhoneApps.ITEM, ' ').replace(',', '‚');
    }

    // ---- What the apps show ----

    /** Extra phone state for the 4.14 apps, appended to {@link PhoneOS#summary}. */
    public static String summary(ServerPlayer p, ItemStack phone) {
        CompoundTag os = PhoneOS.os(phone);
        Body b = BodyEvents.body(p);
        String F = String.valueOf(PhoneApps.FIELD), I = String.valueOf(PhoneApps.ITEM);
        StringBuilder sb = new StringBuilder();
        sb.append(";battery=").append(battery(os));
        sb.append(";on=").append(os.getBoolean("on") ? 1 : 0);
        sb.append(";version=").append(version(os));
        sb.append(";updating=").append(os.contains("updateEnd") ? Math.max(0, (os.getLong("updateEnd") - p.level().getGameTime()) / 20) : -1);
        sb.append(";unread=").append(os.getInt("unread"));
        StringBuilder msgs = new StringBuilder();
        for (Tag t : os.getList("msgs", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) t;
            if (!msgs.isEmpty()) msgs.append(I);
            msgs.append(safe(m.getString("who"))).append(F).append(m.getBoolean("me") ? 1 : 0).append(F)
                    .append(safe(m.getString("text"))).append(F).append(safe(m.getString("at")));
        }
        sb.append(";msgs=").append(msgs);
        sb.append(";debt=").append(b.debt);
        // Health: water, rest, stamina, blood (dl), mood, bladder, and what's wrong.
        long thirstLimit = RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks(), sleepLimit = RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks();
        int water = (int) Math.max(0, 100 - b.thirst * 100 / Math.max(1, thirstLimit)), rest = (int) Math.max(0, 100 - b.awake * 100 / Math.max(1, sleepLimit));
        StringBuilder wrong = new StringBuilder();
        if (b.bleeding > 0) wrong.append(I).append("bleeding");
        if (b.brokenLeg > 0) wrong.append(I).append("broken_leg");
        if (b.armTear > 0) wrong.append(I).append("arm_tear");
        if (b.burn > 0) wrong.append(I).append("burn");
        if (b.frostbite > 0) wrong.append(I).append("frostbite");
        if (b.flu > 0) wrong.append(I).append("flu");
        if (b.foodPoisoning > 0) wrong.append(I).append("food_poisoning");
        if (b.infection > 0) wrong.append(I).append("infection");
        sb.append(";health=").append(water).append(F).append(rest).append(F).append(Math.round(b.stamina)).append(F)
                .append(Math.round(b.blood * 10)).append(F).append(Math.round(b.mood)).append(F).append(Math.round(b.bladder)).append(F)
                .append(wrong.isEmpty() ? "" : wrong.substring(1));
        // Weather: outside (-100 scorching .. +100 freezing), in a house, its insulation, how chilled you are, rain/storm.
        double w = ColdHouse.weather(p);
        boolean inside = ColdHouse.inHouse(p);
        // A room Cold Sweat already keeps comfy (a Hearth, a Boiler) counts as heated.
        String ins = !inside ? "" : Math.abs(w) < 0.05 ? "HEATED" : ColdHouse.insulation(p, w >= 0).name();
        sb.append(";weather=").append(Math.round(w * 100)).append(F).append(inside ? 1 : 0).append(F).append(ins).append(F)
                .append(Math.round(b.houseChill)).append(F).append(p.level().isThundering() ? 2 : p.level().isRaining() ? 1 : 0);
        return sb.toString();
    }

    /** The 4.14 phone actions. Returns the message key to show, "" to just refresh, or null if it's not one of ours. */
    public static String handle(ServerPlayer p, ItemStack phone, String action) {
        CompoundTag os = PhoneOS.os(phone);
        switch (action) {
            case "booted" -> {
                os.putBoolean("on", true);
                PhoneOS.save(phone, os);
                return "";
            }
            case "read" -> {
                os.putInt("unread", 0);
                PhoneOS.save(phone, os);
                return "";
            }
            case "refresh" -> {
                return "";
            }
            case "update" -> {
                return startUpdate(p, phone);
            }
            default -> {
            }
        }
        if (action.startsWith("pay ")) {
            String[] a = action.substring(4).split(" ");
            if (a.length != 2) return "phonelife.pay_failed";
            if (!PhoneOS.service(phone, PhoneOS.BANK)) return "phoneos.no_bank";
            ServerPlayer to = p.server.getPlayerList().getPlayerByName(a[0]);
            long amount;
            try {
                amount = Long.parseLong(a[1]);
            } catch (NumberFormatException e) {
                return "phonelife.pay_failed";
            }
            if (to == null) return "phone.offline";
            if (!World.pay(p, to, amount)) return "phonelife.pay_failed";
            to.displayClientMessage(Component.translatable("realism.pay.got", p.getName(), amount), false);
            p.displayClientMessage(Component.translatable("realism.pay.sent", amount, to.getName(), World.balance(p)), false);
            return "";
        }
        return null;
    }
}
