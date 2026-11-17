package com.jdj125.realism;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * Realism 4.11: RealismPhoneOS. Every phone has its own little (pretend) file system, saved on the phone item:
 * <pre>
 * /boot/realboot                    the bootloader
 * /System/realkernel                the kernel
 * /sbin/realism-launchd             the first program: it starts everything else
 * /System/LaunchDaemons/telephonyd  calls and texts
 * /System/LaunchDaemons/droned      the drone link (SkyPilot app)
 * /System/LaunchDaemons/bankd       the bank balance
 * /System/LaunchDaemons/springboard the home screen
 * /Apps/...                         installed apps
 * </pre>
 * The phone boots realboot, then realkernel, then realism-launchd, which starts the services. Delete realboot,
 * realkernel or realism-launchd (in the Files app, or with a nasty app) and the phone is BRICKED: it won't start. Delete
 * just one service and only that feature breaks. Water can brick it too. Fix: sneak + right-click a computer (PC case or
 * any computer) with the phone: a full restore (it wipes your apps and settings).
 * <p>
 * Sideloading: App Packages (.appp) from outside the store. Turn on Developer Mode in Settings first. Sneak + use an
 * App Package to scan it before installing. Some are fine (a game), some are adware (pop-up ads), some are spyware
 * (they leak where you are) and some are brickers.
 */
public final class PhoneOS {
    private PhoneOS() {
    }

    public static final String BOOT = "/boot/realboot", KERNEL = "/System/realkernel", LAUNCHD = "/sbin/realism-launchd",
            DAEMONS = "/System/LaunchDaemons/", TELEPHONY = DAEMONS + "telephonyd", DRONE = DAEMONS + "droned",
            BANK = DAEMONS + "bankd", SPRINGBOARD = DAEMONS + "springboard", APPS = "/Apps/";
    public static final List<String> FACTORY = List.of(BOOT, KERNEL, LAUNCHD, TELEPHONY, DRONE, BANK, SPRINGBOARD,
            APPS + "Files", APPS + "Settings", APPS + "SkyPilot");
    /** Sideloaded app kinds (the hidden truth inside an App Package). */
    public static final int FINE = 1, ADWARE = 2, SPYWARE = 3, BRICKER = 4;
    public static final String[] SIDELOAD_APPS = {"", "BlockDash", "FreeDiamonds", "FlashlightPro", "SuperBoost"};

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    public static final DeferredItem<Item> APP_PACKAGE = ITEMS.register("app_package", () -> new AppPackage(new Item.Properties().stacksTo(1)));

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    // ---- The state on the phone item ----

    /** The phone's OS state (a copy: change it, then {@link #save}). */
    public static CompoundTag os(ItemStack phone) {
        CompoundTag all = phone.has(DataComponents.CUSTOM_DATA) ? phone.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        if (all.contains("os")) return all.getCompound("os");
        CompoundTag t = new CompoundTag();
        ListTag files = new ListTag();
        FACTORY.forEach(f -> files.add(StringTag.valueOf(f)));
        t.put("files", files);
        t.putBoolean("dev", false);
        return t;
    }

    public static void save(ItemStack phone, CompoundTag os) {
        CompoundTag all = phone.has(DataComponents.CUSTOM_DATA) ? phone.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        all.put("os", os);
        phone.set(DataComponents.CUSTOM_DATA, CustomData.of(all));
    }

    public static List<String> files(CompoundTag os) {
        List<String> out = new ArrayList<>();
        os.getList("files", Tag.TAG_STRING).forEach(t -> out.add(t.getAsString()));
        return out;
    }

    public static boolean has(ItemStack phone, String file) {
        return files(os(phone)).contains(file);
    }

    /** Why the phone won't boot ("" = it boots fine): a missing boot file, or water damage. */
    public static String bricked(ItemStack phone) {
        CompoundTag os = os(phone);
        if (os.getBoolean("water")) return "water";
        List<String> f = files(os);
        if (!f.contains(BOOT)) return "realboot";
        if (!f.contains(KERNEL)) return "realkernel";
        if (!f.contains(LAUNCHD)) return "realism-launchd";
        return "";
    }

    /** A service works when the phone boots and its daemon file is there. */
    public static boolean service(ItemStack phone, String daemon) {
        return bricked(phone).isEmpty() && has(phone, daemon);
    }

    /** The phone a player is carrying (main hand, off hand, then inventory), or empty. */
    public static ItemStack phoneOf(Player p) {
        if (p.getMainHandItem().is(World.PHONE.get())) return p.getMainHandItem();
        if (p.getOffhandItem().is(World.PHONE.get())) return p.getOffhandItem();
        for (ItemStack s : p.getInventory().items) if (s.is(World.PHONE.get())) return s;
        return ItemStack.EMPTY;
    }

    /** Whether this player's phone can make calls and send texts (it's there, it boots, telephonyd runs). */
    public static boolean canCall(Player p) {
        ItemStack phone = phoneOf(p);
        return !phone.isEmpty() && service(phone, TELEPHONY);
    }

    // ---- Actions ----

    /** Deletes a file (Files app). Returns the message key. */
    public static String delete(ItemStack phone, String file) {
        CompoundTag os = os(phone);
        List<String> f = files(os);
        if (!f.remove(file)) return "phoneos.no_such_file";
        ListTag list = new ListTag();
        f.forEach(s -> list.add(StringTag.valueOf(s)));
        os.put("files", list);
        save(phone, os);
        return !bricked(phone).isEmpty() ? "phoneos.deleted_bricked" : "phoneos.deleted";
    }

    public static void setDeveloperMode(ItemStack phone, boolean on) {
        CompoundTag os = os(phone);
        os.putBoolean("dev", on);
        save(phone, os);
    }

    public static boolean developerMode(ItemStack phone) {
        return os(phone).getBoolean("dev");
    }

    /** Full restore at a computer: factory files, no apps, Developer Mode off, water damage fixed. */
    public static void restore(ItemStack phone) {
        CompoundTag all = phone.has(DataComponents.CUSTOM_DATA) ? phone.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        all.remove("os");
        if (all.isEmpty()) phone.remove(DataComponents.CUSTOM_DATA);
        else phone.set(DataComponents.CUSTOM_DATA, CustomData.of(all));
    }

    /** Installs a sideloaded app of this kind. Returns the message key. */
    public static String install(ItemStack phone, int kind) {
        if (!bricked(phone).isEmpty()) return "phoneos.bricked_short";
        if (!developerMode(phone)) return "phoneos.need_dev_mode";
        CompoundTag os = os(phone);
        List<String> f = files(os);
        String app = APPS + SIDELOAD_APPS[kind];
        if (!f.contains(app)) f.add(app);
        if (kind == BRICKER) f.remove(LAUNCHD); // it "optimizes" your phone by deleting the most important file
        ListTag list = new ListTag();
        f.forEach(s -> list.add(StringTag.valueOf(s)));
        os.put("files", list);
        save(phone, os);
        return kind == BRICKER ? "phoneos.installed_bricker" : "phoneos.installed";
    }

    /** What's installed from outside the store, by kind. */
    static boolean hasApp(ItemStack phone, int kind) {
        return has(phone, APPS + SIDELOAD_APPS[kind]);
    }

    /** A short summary for the phone screen: boot state, dev mode, files, and your drones. */
    public static String summary(ServerPlayer p, ItemStack phone) {
        StringBuilder sb = new StringBuilder();
        sb.append("boot=").append(bricked(phone)).append(';');
        sb.append("dev=").append(developerMode(phone) ? 1 : 0).append(';');
        sb.append("files=").append(String.join(",", files(os(phone)))).append(';');
        List<String> drones = new ArrayList<>();
        if (service(phone, DRONE)) {
            for (Drone.Flyer d : p.serverLevel().getEntitiesOfClass(Drone.Flyer.class, p.getBoundingBox().inflate(400),
                    d -> p.getUUID().equals(d.owner)))
                drones.add(d.model().id + ":" + d.battery() * 100 / Math.max(1, d.maxBattery()) + ":" + (int) p.distanceTo(d) + ":" + (d.hasProps() ? (d.chipped ? "chipped" : "ok") : "none"));
        }
        sb.append("drones=").append(String.join(",", drones));
        return sb.toString();
    }

    // ---- Every second: water, adware, spyware ----

    static void second(ServerPlayer p) {
        ItemStack phone = phoneOf(p);
        if (phone.isEmpty()) return;
        // Water damage: swimming with your phone.
        if (p.isUnderWater() && p.getRandom().nextInt(30) == 0 && !os(phone).getBoolean("water")) {
            CompoundTag os = os(phone);
            os.putBoolean("water", true);
            save(phone, os);
            p.displayClientMessage(Component.translatable("realism.phoneos.water").withStyle(ChatFormatting.RED), false);
        }
        if (!bricked(phone).isEmpty()) return;
        if (hasApp(phone, ADWARE) && p.getRandom().nextInt(150) == 0) {
            p.displayClientMessage(Component.translatable("realism.phoneos.ad." + p.getRandom().nextInt(4)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6f, 2f);
        }
        if (hasApp(phone, SPYWARE) && p.getRandom().nextInt(300) == 0) {
            for (ServerPlayer o : p.server.getPlayerList().getPlayers())
                if (o != p) o.displayClientMessage(Component.translatable("realism.phoneos.spyware_leak", p.getName(),
                        p.blockPosition().getX(), p.blockPosition().getZ()).withStyle(ChatFormatting.DARK_GRAY), false);
        }
    }

    // ---- Restoring at a computer ----

    private static boolean isComputer(Block b) {
        return b == Tech.PC_CASE.get() || Tech.MODEL_BLOCKS.stream().anyMatch(m -> m.get() == b);
    }

    @SubscribeEvent
    public static void onComputer(PlayerInteractEvent.RightClickBlock event) {
        Player p = event.getEntity();
        ItemStack held = p.getItemInHand(event.getHand());
        if (!p.isShiftKeyDown() || !held.is(World.PHONE.get()) || !isComputer(p.level().getBlockState(event.getPos()).getBlock())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (p.level().isClientSide) return;
        restore(held);
        p.displayClientMessage(Component.translatable("realism.phoneos.restored").withStyle(ChatFormatting.GREEN), false);
        p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    // ---- App Packages (.appp) ----

    /** The kind hidden in an App Package (decided the first time anyone looks at it). */
    public static int kind(ItemStack pkg, net.minecraft.util.RandomSource random) {
        CompoundTag t = pkg.has(DataComponents.CUSTOM_DATA) ? pkg.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        if (!t.contains("kind")) {
            float r = random.nextFloat();
            t.putInt("kind", r < 0.4f ? FINE : r < 0.65f ? ADWARE : r < 0.85f ? SPYWARE : BRICKER);
            pkg.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        }
        return t.getInt("kind");
    }

    static class AppPackage extends Item {
        AppPackage(Properties props) {
            super(props);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack pkg = player.getItemInHand(hand);
            if (level.isClientSide) return InteractionResultHolder.success(pkg);
            ItemStack phone = phoneOf(player);
            if (phone.isEmpty()) {
                player.displayClientMessage(Component.translatable("realism.phoneos.need_phone"), true);
                return InteractionResultHolder.fail(pkg);
            }
            int kind = kind(pkg, player.getRandom());
            if (player.isShiftKeyDown()) {
                // The scanner: says what it really is.
                player.displayClientMessage(Component.translatable("realism.phoneos.scan." + kind).withStyle(kind == FINE ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                return InteractionResultHolder.success(pkg);
            }
            String result = install(phone, kind);
            player.displayClientMessage(Component.translatable("realism." + result, SIDELOAD_APPS[kind]).withStyle(
                    result.equals("phoneos.installed") ? ChatFormatting.AQUA : ChatFormatting.RED), false);
            if (result.startsWith("phoneos.installed")) pkg.consume(1, player);
            return InteractionResultHolder.consume(pkg);
        }
    }

    // ---- Network ----

    /** Client to server, from the phone's apps: "delete <path>", "dev on|off", "rth" (drones home), "play" (BlockDash). */
    public record Action(String action) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "phone_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = ByteBufCodecs.stringUtf8(256).map(Action::new, Action::action);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void handle(ServerPlayer p, String action) {
        ItemStack phone = phoneOf(p);
        if (phone.isEmpty() || !bricked(phone).isEmpty()) return;
        String key;
        if (action.startsWith("delete ")) key = delete(phone, action.substring(7));
        else if (action.equals("dev on") || action.equals("dev off")) {
            setDeveloperMode(phone, action.endsWith("on"));
            key = action.endsWith("on") ? "phoneos.dev_on" : "phoneos.dev_off";
        } else if (action.equals("rth") && service(phone, DRONE)) {
            for (Drone.Flyer d : p.serverLevel().getEntitiesOfClass(Drone.Flyer.class, p.getBoundingBox().inflate(400), d -> p.getUUID().equals(d.owner)))
                if (d.pilot == null) d.returning = true;
            key = "phoneos.rth";
        } else if (action.equals("play") && hasApp(phone, FINE)) {
            Body b = BodyEvents.body(p);
            if (b.funCooldown > 0) key = "phoneos.play_tired";
            else {
                b.funCooldown = 60 * 20;
                Mind.lift(p, 6);
                key = "phoneos.played";
            }
        } else return;
        p.displayClientMessage(Component.translatable("realism." + key).withStyle(key.contains("bricked") ? ChatFormatting.RED : ChatFormatting.AQUA), false);
        World.openPhone(p); // refresh the screen
    }
}
