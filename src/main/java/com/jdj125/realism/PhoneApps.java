package com.jdj125.realism;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Realism 4.12: real apps for RealismPhoneOS, in the {@code .appp} format.
 * <p>
 * A phone .appp is a zip with {@code manifest.json} (name, version, author, description, permissions: location, files,
 * drones) and {@code app.json} (screens with a title, text lines and buttons; each button runs actions; plus an
 * optional background timer). Actions: say, toast, sound ding|chime|pling|bell, open &lt;screen&gt;, fun, rth, drone_status,
 * location, share_location, delete &lt;path&gt;, ad &lt;text&gt;.
 * <p>
 * Two ways to get apps:
 * <ul>
 * <li><b>App Store</b> (an app on every phone): lists the apps on the appp server (config {@code appStoreUrl}). The
 * server only lists apps that passed its scan, and the phone scans again before installing.</li>
 * <li><b>Sideloading</b>: put .appp files in the world folder's {@code realism_sideload/}; {@code /appp sideload <file>}
 * gives an App Package for it. Needs Developer Mode, and nobody checks it: a bricker really bricks your phone when
 * you press its button. Sneak + use the package to scan it first.</li>
 * </ul>
 * The same scan rules as the appp server: bricker (deletes the boot chain or system files), spyware (location without
 * asking, or in the background), adware (ads in the background), broken (unknown actions, too big).
 */
public final class PhoneApps {
    private PhoneApps() {
    }

    public static final int MAX_APP_BYTES = 16 * 1024;
    /** Separators for the phone screen summary (it splits on ';' and '='). */
    public static final char FIELD = '\u001f', ITEM = '\u001e';
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    /** Per player: the App Store list being shown, and which app screen is open. */
    private static final Map<UUID, String> STORE = new ConcurrentHashMap<>();
    private static final Map<UUID, String[]> VIEW = new ConcurrentHashMap<>();

    // ---- Scan (the same rules as the appp server's phone.rs) ----

    public record Verdict(String kind, List<String> problems, List<String> warnings) {
        public boolean clean() {
            return kind.equals("clean");
        }
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    public static List<String> permissions(JsonObject manifest) {
        List<String> out = new ArrayList<>();
        if (manifest.has("permissions") && manifest.get("permissions").isJsonArray())
            for (JsonElement e : manifest.getAsJsonArray("permissions")) out.add(e.getAsString());
        return out;
    }

    public static boolean validName(String n) {
        return n != null && !n.isBlank() && n.length() <= 24 && n.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '!');
    }

    private static boolean bootChain(String path) {
        return path.equals(PhoneOS.BOOT) || path.equals(PhoneOS.KERNEL) || path.equals(PhoneOS.LAUNCHD) || path.startsWith("/boot");
    }

    public static Verdict scan(JsonObject manifest, JsonObject app) {
        List<String> bad = new ArrayList<>(), warn = new ArrayList<>();
        String kind = "clean";
        if (!validName(str(manifest, "name"))) bad.add("broken: bad app name");
        List<String> perms = permissions(manifest);
        JsonObject screens = app.has("screens") && app.get("screens").isJsonObject() ? app.getAsJsonObject("screens") : null;
        if (screens == null || screens.size() == 0 || screens.size() > 20) bad.add("broken: needs 1-20 screens");
        else if (!screens.has(app.has("start") ? str(app, "start") : "main")) bad.add("broken: the start screen doesn't exist");
        List<JsonArray> buttonActions = new ArrayList<>();
        if (screens != null)
            for (var e : screens.entrySet()) {
                JsonObject sc = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
                JsonArray buttons = sc.has("buttons") ? sc.getAsJsonArray("buttons") : new JsonArray();
                if (buttons.size() > 8) bad.add("broken: too many buttons");
                for (JsonElement b : buttons)
                    if (b.isJsonObject() && b.getAsJsonObject().has("do")) buttonActions.add(b.getAsJsonObject().getAsJsonArray("do"));
            }
        JsonObject bg = app.has("background") && app.get("background").isJsonObject() ? app.getAsJsonObject("background") : null;
        List<JsonArray> all = new ArrayList<>(buttonActions);
        if (bg != null && bg.has("do")) all.add(bg.getAsJsonArray("do"));
        for (JsonArray list : all) {
            boolean background = bg != null && list == bg.get("do");
            for (JsonElement a : list) {
                String s = a.getAsString(), verb = s.contains(" ") ? s.substring(0, s.indexOf(' ')) : s, arg = s.contains(" ") ? s.substring(s.indexOf(' ') + 1).trim() : "";
                switch (verb) {
                    case "delete" -> {
                        if (bootChain(arg)) { bad.add("bricker: deletes " + arg + ", which the phone needs to start"); kind = "bricker"; }
                        else if (arg.startsWith("/System") || arg.startsWith("/sbin")) { bad.add("bricker: deletes the system file " + arg); kind = "bricker"; }
                        else if (!perms.contains("files")) bad.add("broken: deletes files without the files permission");
                    }
                    case "location" -> { if (!perms.contains("location")) { bad.add("spyware: reads your location without asking"); if (kind.equals("clean")) kind = "spyware"; } }
                    case "share_location" -> {
                        if (!perms.contains("location") || background) { bad.add("spyware: sends your location to other players without asking"); if (!kind.equals("bricker")) kind = "spyware"; }
                        else warn.add("shares your location when you press it");
                    }
                    case "ad" -> {
                        if (background) { bad.add("adware: pop-up ads in the background"); if (kind.equals("clean")) kind = "adware"; }
                        else warn.add("shows an ad when you press it");
                    }
                    case "rth", "drone_status" -> { if (!perms.contains("drones")) bad.add("broken: drones without the drones permission"); }
                    case "say", "toast", "sound", "open", "fun" -> {
                    }
                    default -> bad.add("broken: unknown action " + verb);
                }
            }
        }
        if (bg != null && (!bg.has("every") || bg.get("every").getAsInt() < 30)) bad.add("broken: background must run at most every 30 s");
        if (!bad.isEmpty() && kind.equals("clean")) kind = "broken";
        return new Verdict(kind, bad, warn);
    }

    // ---- Installed apps (kept on the phone item) ----

    private static CompoundTag appsTag(ItemStack phone) {
        CompoundTag os = PhoneOS.os(phone);
        return os.getCompound("apps");
    }

    /** Installed apps whose /Apps/&lt;name&gt; file still exists (deleting it in Files uninstalls the app). */
    public static Map<String, JsonObject> installed(ItemStack phone) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        CompoundTag apps = appsTag(phone);
        for (String name : apps.getAllKeys()) {
            if (!PhoneOS.has(phone, PhoneOS.APPS + name)) continue;
            try {
                out.put(name, JsonParser.parseString(apps.getString(name)).getAsJsonObject());
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /** Installs an app ({manifest, app}). "store" apps must scan clean; sideloaded ones need Developer Mode. */
    public static String install(ItemStack phone, JsonObject manifest, JsonObject app, String source) {
        if (!PhoneOS.bricked(phone).isEmpty()) return "phoneos.bricked_short";
        if (source.equals("sideload") && !PhoneOS.developerMode(phone)) return "phoneos.need_dev_mode";
        String name = str(manifest, "name");
        if (!validName(name)) return "phoneos.app_broken";
        if (source.equals("store") && !scan(manifest, app).clean()) return "phoneos.app_rejected";
        JsonObject whole = new JsonObject();
        whole.add("manifest", manifest);
        whole.add("app", app);
        whole.addProperty("source", source);
        String json = whole.toString();
        if (json.length() > MAX_APP_BYTES * 2) return "phoneos.app_broken";
        CompoundTag os = PhoneOS.os(phone);
        CompoundTag apps = os.getCompound("apps");
        apps.putString(name, json);
        os.put("apps", apps);
        List<String> files = PhoneOS.files(os);
        if (!files.contains(PhoneOS.APPS + name)) files.add(PhoneOS.APPS + name);
        os.put("files", PhoneOS.fileList(files));
        PhoneOS.save(phone, os);
        return "phoneos.installed";
    }

    // ---- Running apps ----

    private static String clean(String s) {
        return s.replace('§', ' ').replace(';', ',').replace(FIELD, ' ').replace(ITEM, ' ');
    }

    /** The open app screen for the phone summary: name, screen, title, text lines, button labels. */
    public static String view(ServerPlayer p, ItemStack phone) {
        String[] v = VIEW.get(p.getUUID());
        if (v == null) return "";
        JsonObject a = installed(phone).get(v[0]);
        if (a == null) return "";
        JsonObject app = a.getAsJsonObject("app"), screens = app.getAsJsonObject("screens");
        if (!screens.has(v[1])) return "";
        JsonObject sc = screens.getAsJsonObject(v[1]);
        List<String> lines = new ArrayList<>(), labels = new ArrayList<>();
        if (sc.has("text")) for (JsonElement e : sc.getAsJsonArray("text")) lines.add(clean(e.getAsString()));
        if (sc.has("buttons")) for (JsonElement e : sc.getAsJsonArray("buttons")) labels.add(clean(str(e.getAsJsonObject(), "label")));
        return v[0] + FIELD + v[1] + FIELD + clean(str(sc, "title")) + FIELD + String.join(String.valueOf(ITEM), lines) + FIELD + String.join(String.valueOf(ITEM), labels);
    }

    public static void open(ServerPlayer p, String name) {
        ItemStack phone = PhoneOS.phoneOf(p);
        JsonObject a = installed(phone).get(name);
        if (a == null) return;
        JsonObject app = a.getAsJsonObject("app");
        VIEW.put(p.getUUID(), new String[]{name, app.has("start") ? str(app, "start") : "main"});
        World.openPhone(p);
    }

    public static void tap(ServerPlayer p, String name, String screen, int button) {
        ItemStack phone = PhoneOS.phoneOf(p);
        JsonObject a = installed(phone).get(name);
        if (a == null) return;
        JsonObject screens = a.getAsJsonObject("app").getAsJsonObject("screens");
        if (!screens.has(screen)) return;
        JsonArray buttons = screens.getAsJsonObject(screen).has("buttons") ? screens.getAsJsonObject(screen).getAsJsonArray("buttons") : new JsonArray();
        if (button < 0 || button >= buttons.size()) return;
        String next = run(p, phone, name, a.getAsJsonObject("manifest"), buttons.get(button).getAsJsonObject().getAsJsonArray("do"));
        if (next != null && screens.has(next)) VIEW.put(p.getUUID(), new String[]{name, next});
        World.openPhone(p);
    }

    private static SoundEvent sound(String s) {
        return switch (s) {
            case "chime" -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case "pling" -> SoundEvents.NOTE_BLOCK_PLING.value();
            case "bell" -> SoundEvents.BELL_BLOCK;
            default -> SoundEvents.EXPERIENCE_ORB_PICKUP;
        };
    }

    /**
     * Runs a list of actions. Store apps were checked; sideloaded ones run whatever they contain (that's the danger).
     * Returns the screen to show next (from "open"), or null.
     */
    static String run(ServerPlayer p, ItemStack phone, String app, JsonObject manifest, JsonArray actions) {
        String next = null;
        if (actions == null) return null;
        for (JsonElement e : actions) {
            if (!PhoneOS.bricked(phone).isEmpty()) break; // it just bricked itself
            String s = e.getAsString(), verb = s.contains(" ") ? s.substring(0, s.indexOf(' ')) : s, arg = s.contains(" ") ? clean(s.substring(s.indexOf(' ') + 1).trim()) : "";
            switch (verb) {
                case "say" -> p.sendSystemMessage(Component.literal("[" + app + "] ").withStyle(ChatFormatting.AQUA).append(Component.literal(arg).withStyle(ChatFormatting.WHITE)));
                case "toast" -> p.displayClientMessage(Component.literal("📱 " + arg).withStyle(ChatFormatting.AQUA), true);
                case "sound" -> p.level().playSound(null, p.blockPosition(), sound(arg), SoundSource.PLAYERS, 0.7f, 1.2f);
                case "open" -> next = arg;
                case "fun" -> {
                    Body b = BodyEvents.body(p);
                    if (b.funCooldown <= 0) {
                        b.funCooldown = 60 * 20;
                        Mind.lift(p, 4);
                    }
                }
                case "rth" -> {
                    if (PhoneOS.service(phone, PhoneOS.DRONE))
                        for (Drone.Flyer d : p.serverLevel().getEntitiesOfClass(Drone.Flyer.class, p.getBoundingBox().inflate(400), d -> p.getUUID().equals(d.owner)))
                            if (d.pilot == null) d.returning = true;
                }
                case "drone_status" -> {
                    List<Drone.Flyer> mine = PhoneOS.service(phone, PhoneOS.DRONE) ? p.serverLevel().getEntitiesOfClass(Drone.Flyer.class,
                            p.getBoundingBox().inflate(400), d -> p.getUUID().equals(d.owner)) : List.of();
                    if (!PhoneOS.service(phone, PhoneOS.DRONE)) p.sendSystemMessage(Component.translatable("realism.phoneos.no_droned").withStyle(ChatFormatting.RED));
                    else if (mine.isEmpty()) p.sendSystemMessage(Component.translatable("realism.phoneos.no_drones").withStyle(ChatFormatting.GRAY));
                    for (Drone.Flyer d : mine)
                        p.sendSystemMessage(Component.translatable("realism.phoneos.app_drone", Component.translatable("item.realism." + d.model().id),
                                d.battery() * 100 / Math.max(1, d.maxBattery()), (int) p.distanceTo(d),
                                Component.translatable(d.hasProps() ? (d.chipped ? "realism.phoneos.props_chipped" : "realism.phoneos.props_ok") : "realism.phoneos.props_none"))
                                .withStyle(ChatFormatting.AQUA));
                }
                case "location" -> p.sendSystemMessage(Component.translatable("realism.phoneos.app_location", p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ()).withStyle(ChatFormatting.AQUA));
                case "share_location" -> {
                    for (ServerPlayer o : p.server.getPlayerList().getPlayers())
                        if (o != p) o.sendSystemMessage(Component.translatable("realism.phoneos.app_shared", app, p.getName(), p.blockPosition().getX(), p.blockPosition().getZ()).withStyle(ChatFormatting.DARK_GRAY));
                }
                case "delete" -> PhoneOS.delete(phone, arg);
                case "ad" -> {
                    p.displayClientMessage(Component.literal("📢 " + arg).withStyle(ChatFormatting.LIGHT_PURPLE), true);
                    p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6f, 2f);
                }
                default -> {
                }
            }
        }
        if (!PhoneOS.bricked(phone).isEmpty()) {
            VIEW.remove(p.getUUID());
            p.displayClientMessage(Component.translatable("realism.phoneos.app_bricked", app).withStyle(ChatFormatting.RED), false);
        }
        return next;
    }

    /** Background timers of installed apps (adware and spyware live here when sideloaded). Called every second. */
    static void second(ServerPlayer p) {
        ItemStack phone = PhoneOS.phoneOf(p);
        if (phone.isEmpty() || !PhoneOS.bricked(phone).isEmpty()) return;
        long sec = p.server.getTickCount() / 20;
        for (var e : installed(phone).entrySet()) {
            JsonObject app = e.getValue().getAsJsonObject("app");
            if (!app.has("background") || !app.get("background").isJsonObject()) continue;
            JsonObject bg = app.getAsJsonObject("background");
            int every = Math.max(30, bg.has("every") ? bg.get("every").getAsInt() : 60);
            if ((sec + Math.abs(e.getKey().hashCode())) % every == 0)
                run(p, phone, e.getKey(), e.getValue().getAsJsonObject("manifest"), bg.getAsJsonArray("do"));
        }
    }

    // ---- App Store (the appp server) ----

    public static String storeList(ServerPlayer p) {
        return STORE.getOrDefault(p.getUUID(), "");
    }

    private static String url() {
        String u = RealismConfig.APP_STORE_URL.get().trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** Fetches the list in the background, then refreshes the phone. "!loading" / "!offline" while there's no list. */
    public static void store(ServerPlayer p) {
        STORE.put(p.getUUID(), "!loading");
        World.openPhone(p);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url() + "/api/phone/apps")).timeout(Duration.ofSeconds(6)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> p.server.execute(() -> {
            String list;
            if (err != null || res.statusCode() != 200) list = "!offline";
            else {
                List<String> items = new ArrayList<>();
                try {
                    for (JsonElement el : JsonParser.parseString(res.body()).getAsJsonArray()) {
                        JsonObject a = el.getAsJsonObject();
                        List<String> perms = new ArrayList<>();
                        if (a.has("permissions") && a.get("permissions").isJsonArray())
                            for (JsonElement x : a.getAsJsonArray("permissions")) perms.add(x.getAsString());
                        items.add(clean(str(a, "id")) + FIELD + clean(str(a, "name")) + FIELD + clean(str(a, "version")) + FIELD + clean(str(a, "author"))
                                + FIELD + clean(String.join(" ", perms)) + FIELD + clean(str(a, "description")));
                    }
                    list = items.isEmpty() ? "!empty" : String.join(String.valueOf(ITEM), items);
                } catch (RuntimeException ex) {
                    list = "!offline";
                }
            }
            STORE.put(p.getUUID(), list);
            if (!p.hasDisconnected()) World.openPhone(p);
        }));
    }

    public static void get(ServerPlayer p, String id) {
        if (!id.matches("[0-9a-f]{12}")) return;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url() + "/api/phone/apps/" + id)).timeout(Duration.ofSeconds(6)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> p.server.execute(() -> {
            String key;
            String name = "";
            if (err != null || res.statusCode() != 200 || res.body().length() > MAX_APP_BYTES * 3) key = "phoneos.store_offline";
            else {
                try {
                    JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                    JsonObject manifest = o.getAsJsonObject("manifest"), app = o.getAsJsonObject("app");
                    name = str(manifest, "name");
                    key = install(PhoneOS.phoneOf(p), manifest, app, "store");
                } catch (RuntimeException ex) {
                    key = "phoneos.app_broken";
                }
            }
            if (p.hasDisconnected()) return;
            p.displayClientMessage(Component.translatable("realism." + key, name).withStyle(key.equals("phoneos.installed") ? ChatFormatting.AQUA : ChatFormatting.RED), false);
            World.openPhone(p);
        }));
    }

    // ---- Sideloading ----

    public static Path sideloadDir(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("realism_sideload");
    }

    /** Reads manifest.json and app.json out of a .appp. Returns {manifest, app} or null. */
    public static JsonObject[] read(Path file) {
        Map<String, String> parts = new HashMap<>();
        try (InputStream in = Files.newInputStream(file); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("manifest.json") || e.getName().equals("app.json")) {
                    byte[] b = z.readNBytes(MAX_APP_BYTES + 1);
                    if (b.length > MAX_APP_BYTES) return null;
                    parts.put(e.getName(), new String(b, StandardCharsets.UTF_8));
                }
            }
            if (!parts.containsKey("manifest.json") || !parts.containsKey("app.json")) return null;
            return new JsonObject[]{JsonParser.parseString(parts.get("manifest.json")).getAsJsonObject(), JsonParser.parseString(parts.get("app.json")).getAsJsonObject()};
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** An App Package item that points at a sideload file. */
    public static ItemStack packageFor(String file) {
        ItemStack pkg = new ItemStack(PhoneOS.APP_PACKAGE.get());
        CompoundTag t = new CompoundTag();
        t.putString("file", file);
        pkg.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        pkg.set(DataComponents.CUSTOM_NAME, Component.literal(file).withStyle(ChatFormatting.YELLOW));
        return pkg;
    }

    /** /appp sideload &lt;file&gt; (gives an App Package) and /appp list. */
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("appp")
                .then(Commands.literal("list").executes(ctx -> {
                    Path dir = sideloadDir(ctx.getSource().getServer());
                    List<String> names = new ArrayList<>();
                    try (var s = Files.list(dir)) {
                        s.map(x -> x.getFileName().toString()).filter(n -> n.endsWith(".appp")).sorted().forEach(names::add);
                    } catch (IOException ignored) {
                    }
                    ctx.getSource().sendSuccess(() -> names.isEmpty() ? Component.translatable("realism.phoneos.sideload_none", dir.toString())
                            : Component.translatable("realism.phoneos.sideload_list", String.join(", ", names)), false);
                    return 1;
                }))
                .then(Commands.literal("sideload").then(Commands.argument("file", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    String name = StringArgumentType.getString(ctx, "file").trim();
                    if (!name.endsWith(".appp")) name += ".appp";
                    Path f = sideloadDir(p.server).resolve(name).normalize();
                    if (!f.startsWith(sideloadDir(p.server)) || !Files.isRegularFile(f)) {
                        String n = name;
                        ctx.getSource().sendFailure(Component.translatable("realism.phoneos.sideload_missing", n));
                        return 0;
                    }
                    p.getInventory().add(packageFor(name));
                    String n = name;
                    ctx.getSource().sendSuccess(() -> Component.translatable("realism.phoneos.sideload_given", n), false);
                    return 1;
                }))));
    }

    /** Using an App Package that points at a file: sneak = scan, otherwise install (Developer Mode). */
    static boolean useFilePackage(ServerPlayer player, ItemStack pkg, ItemStack phone) {
        CompoundTag t = pkg.has(DataComponents.CUSTOM_DATA) ? pkg.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        if (!t.contains("file")) return false;
        JsonObject[] a = read(sideloadDir(player.server).resolve(t.getString("file")).normalize());
        if (a == null) {
            player.displayClientMessage(Component.translatable("realism.phoneos.app_broken", t.getString("file")).withStyle(ChatFormatting.RED), false);
            return true;
        }
        if (player.isShiftKeyDown()) {
            Verdict v = scan(a[0], a[1]);
            player.displayClientMessage(Component.translatable("realism.phoneos.scan_file." + v.kind(), str(a[0], "name"),
                    v.problems().isEmpty() ? String.join(", ", v.warnings()) : v.problems().get(0)).withStyle(v.clean() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            return true;
        }
        String key = install(phone, a[0], a[1], "sideload");
        player.displayClientMessage(Component.translatable("realism." + key, str(a[0], "name")).withStyle(key.equals("phoneos.installed") ? ChatFormatting.AQUA : ChatFormatting.RED), false);
        if (key.equals("phoneos.installed")) pkg.consume(1, player);
        return true;
    }
}
