package com.jdj125.realism.client;

import com.jdj125.realism.Bullying;
import com.jdj125.realism.PhoneApps;
import com.jdj125.realism.PhoneOS;
import com.jdj125.realism.Realism;
import com.jdj125.realism.World;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The phone, running RealismPhoneOS 2 (4.14). It boots once (realboot, realkernel, realism-launchd, then the services;
 * a missing boot file stops it there: bricked), then it shows a lock screen with your notifications and a home screen
 * full of apps: Phone, Messages, Bank, Health, Weather, Maps, Camera, Clock, Files, Settings, SkyPilot, the App Store and
 * anything you installed. Everything is drawn by hand (no stock buttons) so it looks like a real phone.
 */
public class PhoneScreen extends Screen {
    private enum Mode {BOOT, LOCK, HOME, APP, BRICKED, DEAD}

    /** Phone size in GUI pixels (fits the smallest normal GUI height, 240). */
    private static final int W = 132, H = 232, SCREEN_TOP = 14, SCREEN_BOTTOM = 14;
    private static final int TEXT = 0xFFEDEDF2, DIM = 0xFF9AA0B4, RED = 0xFFFF6B6B, GREEN = 0xFF7EE08F, GOLD = 0xFFFFD66B, BLUE = 0xFF6FB6FF;

    private final World.OpenPhone info;
    private final String[] contacts;
    private final Map<String, String> os = new HashMap<>();
    private final List<String> files;
    private Mode mode;
    private String app = "";
    private String thread = "";
    private int bootTicks, scroll, contact, ticks;
    private EditBox input;
    /** The system file clicked once in Files (a second click deletes it). */
    private String confirm = "";
    /** Click areas drawn this frame. */
    private final List<Tap> taps = new ArrayList<>();

    private record Tap(int x, int y, int w, int h, Runnable run) {
        boolean hit(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private PhoneScreen(World.OpenPhone info) {
        super(Component.translatable("item.realism.phone"));
        this.info = info;
        this.contacts = info.contacts().isEmpty() ? new String[0] : info.contacts().split(",");
        for (String part : info.os().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) os.put(part.substring(0, eq), part.substring(eq + 1));
        }
        this.files = new ArrayList<>(List.of(os.getOrDefault("files", "").split(",")));
        files.removeIf(String::isEmpty);
    }

    public static void open(World.OpenPhone info) {
        Minecraft mc = Minecraft.getInstance();
        PhoneScreen s = new PhoneScreen(info);
        if (mc.screen instanceof PhoneScreen old && old.mode != Mode.BOOT) {
            // A refresh (an app asked the server): stay where you are.
            s.mode = old.mode;
            s.app = old.app;
            s.thread = old.thread;
            s.scroll = old.scroll;
            s.contact = old.contact;
            s.draft = old.input != null ? old.input.getValue() : "";
        } else s.mode = Mode.BOOT;
        if (s.battery() <= 0) s.mode = Mode.DEAD;
        else if (!s.bricked().isEmpty() && s.mode != Mode.BOOT) s.mode = Mode.BRICKED;
        else if (s.mode == Mode.BOOT && "1".equals(s.os.get("on")) && s.bricked().isEmpty()) s.mode = Mode.LOCK; // already on: no boot
        else if (s.mode == Mode.BRICKED && s.bricked().isEmpty()) s.mode = Mode.LOCK; // restored while looking
        mc.setScreen(s);
    }

    private String draft = "";

    private String bricked() {
        return os.getOrDefault("boot", "");
    }

    private int battery() {
        return num("battery", 100);
    }

    private int num(String key, int def) {
        try {
            return Integer.parseInt(os.getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean has(String file) {
        return files.contains(file);
    }

    private int px() { return width / 2 - W / 2; }
    private int py() { return Math.max(4, height / 2 - H / 2); }

    private void action(String a) {
        PacketDistributor.sendToServer(new PhoneOS.Action(a));
    }

    private void go(Mode m, String newApp) {
        mode = m;
        app = newApp;
        scroll = 0;
        confirm = "";
        draft = "";
        rebuildWidgets();
    }

    private void openApp(String id) {
        go(Mode.APP, id);
        switch (id) {
            case "messages" -> action("read");
            case "store" -> action("store");
            case "maps" -> MiniMap.refresh();
            default -> {
                if (id.startsWith("appp:")) action("app " + id.substring(5));
            }
        }
    }

    // ---- Text input (only Messages and Bank need it) ----

    @Override
    protected void init() {
        input = null;
        int x = px(), y = py();
        boolean chat = mode == Mode.APP && app.equals("thread");
        boolean bank = mode == Mode.APP && app.equals("bank") && contacts.length > 0;
        if (chat || bank) {
            int iy = y + H - SCREEN_BOTTOM - (bank ? 58 : 20);
            input = new EditBox(font, x + 8, iy, bank ? 60 : 88, 14, Component.translatable("realism.phone.type"));
            input.setMaxLength(bank ? 9 : 200);
            input.setBordered(true);
            if (bank) input.setFilter(s -> s.chars().allMatch(Character::isDigit));
            input.setValue(draft);
            addRenderableWidget(input);
            setInitialFocus(input);
        }
    }

    private void send() {
        if (input == null || input.getValue().isBlank()) return;
        if (app.equals("thread")) {
            PacketDistributor.sendToServer(new World.SendText(thread, input.getValue()));
            input.setValue("");
            draft = "";
            action("refresh");
        } else if (app.equals("bank") && contacts.length > 0) {
            action("pay " + contacts[contact % contacts.length] + " " + input.getValue());
            input.setValue("");
            draft = "";
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (input != null && input.isFocused() && (key == 257 || key == 335)) {
            send();
            return true;
        }
        if (mode == Mode.LOCK && key != 256) {
            go(Mode.HOME, "");
            return true;
        }
        if (key == 259 && mode == Mode.APP && (input == null || !input.isFocused())) { // backspace = back
            back();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void back() {
        if (app.equals("thread")) go(Mode.APP, "messages");
        else go(Mode.HOME, "");
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = taps.size() - 1; i >= 0; i--) {
            Tap t = taps.get(i);
            if (t.hit(mx, my)) {
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f, 0.25f));
                t.run.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, scroll - (int) Math.signum(sy) * 12);
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (mode == Mode.BOOT) {
            bootTicks++;
            int failAt = switch (bricked()) {
                case "realboot", "water", "update" -> 1;
                case "realkernel" -> 2;
                case "realism-launchd" -> 3;
                default -> 99;
            };
            if (bootTicks >= Math.min(failAt, 8) * 6 + 6) {
                if (failAt < 99) go(Mode.BRICKED, "");
                else {
                    action("booted");
                    go(Mode.LOCK, "");
                }
            }
        }
        // Live apps ask for fresh numbers every 2 seconds.
        if (mode == Mode.APP && ticks % 40 == 0 && List.of("health", "weather", "settings", "skypilot", "messages", "thread").contains(app)) action("refresh");
        if (mode == Mode.APP && app.equals("maps") && ticks % 10 == 0) MiniMap.refresh();
    }

    // ---- Drawing ----

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        taps.clear();
        int x = px(), y = py();
        // The phone body: a rounded dark frame with a camera notch.
        round(g, x - 4, y - 4, W + 8, H + 8, 0xFF0B0B0E);
        round(g, x - 3, y - 3, W + 6, H + 6, 0xFF2A2A31);
        boolean dark = mode == Mode.BOOT || mode == Mode.BRICKED || mode == Mode.DEAD;
        if (dark) g.fill(x, y, x + W, y + H, 0xFF000000);
        else wallpaper(g, x, y);
        switch (mode) {
            case BOOT -> drawBoot(g, x, y);
            case BRICKED -> drawBricked(g, x, y);
            case DEAD -> drawDead(g, x, y);
            case LOCK -> drawLock(g, x, y);
            case HOME -> drawHome(g, x, y, mouseX, mouseY);
            case APP -> drawApp(g, x, y, mouseX, mouseY);
        }
        if (!dark) statusBar(g, x, y);
        g.fill(x + W / 2 - 14, y - 1, x + W / 2 + 14, y + 4, 0xFF0B0B0E); // the notch
        // The home bar: tap it to go home.
        if (mode == Mode.APP || mode == Mode.LOCK) {
            int hx = x + W / 2 - 20, hy = y + H - 7;
            g.fill(hx, hy, hx + 40, hy + 3, 0xCCFFFFFF);
            taps.add(new Tap(hx - 6, hy - 5, 52, 12, () -> go(Mode.HOME, "")));
        }
    }

    private void wallpaper(GuiGraphics g, int x, int y) {
        g.fillGradient(x, y, x + W, y + H, 0xFF1B2850, 0xFF3A1F4F);
    }

    private static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private void statusBar(GuiGraphics g, int x, int y) {
        g.drawString(font, World.now(), x + 6, y + 3, TEXT, false);
        boolean service = has(PhoneOS.TELEPHONY);
        int sx = x + W - 58;
        for (int i = 0; i < 4; i++) {
            int h = 2 + i * 2;
            g.fill(sx + i * 4, y + 10 - h, sx + i * 4 + 3, y + 10, info.signal() && service ? TEXT : 0xFF555566);
        }
        // Battery: outline, fill, percent.
        int bat = battery(), bx = x + W - 40;
        int col = bat <= 10 ? RED : bat <= 20 ? GOLD : GREEN;
        g.fill(bx, y + 3, bx + 14, y + 10, 0xFF8A8A99);
        g.fill(bx + 1, y + 4, bx + 13, y + 9, 0xFF101014);
        g.fill(bx + 1, y + 4, bx + 1 + Math.max(1, bat * 12 / 100), y + 9, col);
        g.fill(bx + 14, y + 5, bx + 15, y + 8, 0xFF8A8A99);
        small(g, bat + "%", bx + 16, y + 4, TEXT);
    }

    /** Small text (scaled down), for labels under icons and the status bar. */
    private void small(GuiGraphics g, String s, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    private void center(GuiGraphics g, Component c, int cx, int y, int color) {
        g.drawString(font, c, cx - font.width(c) / 2, y, color, false);
    }

    private int wrap(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (var line : font.split(text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    private void big(GuiGraphics g, String s, int cx, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, -font.width(s) / 2, 0, color, false);
        g.pose().popPose();
    }

    /** A rounded button drawn by hand. */
    private void button(GuiGraphics g, int x, int y, int w, int h, Component label, int color, int mx, int my, Runnable run) {
        boolean hover = mx >= x && my >= y && mx < x + w && my < y + h;
        round(g, x, y, w, h, hover ? brighter(color) : color);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, false);
        taps.add(new Tap(x, y, w, h, run));
    }

    private static int brighter(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 30), gr = Math.min(255, ((c >> 8) & 255) + 30), b = Math.min(255, (c & 255) + 30);
        return (c & 0xFF000000) | r << 16 | gr << 8 | b;
    }

    // ---- Boot, bricked, dead, lock ----

    private void drawBoot(GuiGraphics g, int x, int y) {
        big(g, "◆", x + W / 2, y + 40, 3, 0xFFFFFFFF);
        center(g, Component.literal("RealismPhoneOS"), x + W / 2, y + 72, TEXT);
        String[] lines = {"realboot 1.0", "realkernel " + os.getOrDefault("version", "2.0"), "realism-launchd", "telephonyd", "droned", "bankd", "springboard"};
        String[] need = {PhoneOS.BOOT, PhoneOS.KERNEL, PhoneOS.LAUNCHD, PhoneOS.TELEPHONY, PhoneOS.DRONE, PhoneOS.BANK, PhoneOS.SPRINGBOARD};
        int shown = Math.min(lines.length, bootTicks / 6 + 1);
        boolean brokenBoot = !bricked().isEmpty() && List.of("water", "update").contains(bricked());
        for (int i = 0; i < shown; i++) {
            boolean ok = has(need[i]) && !(i == 0 && brokenBoot);
            small(g, (ok ? "[ ok ] " : "[FAIL] ") + lines[i], x + 10, y + 100 + i * 9, ok ? GREEN : RED);
        }
        int bar = Math.min(W - 40, bootTicks * (W - 40) / 48);
        g.fill(x + 20, y + 176, x + W - 20, y + 178, 0xFF333333);
        g.fill(x + 20, y + 176, x + 20 + bar, y + 178, 0xFFFFFFFF);
    }

    private void drawBricked(GuiGraphics g, int x, int y) {
        big(g, "⚠", x + W / 2, y + 24, 3, RED);
        int ty = wrap(g, Component.translatable("realism.phoneos.bricked." + bricked()), x + 8, y + 64, W - 16, RED);
        wrap(g, Component.translatable("realism.phoneos.restore_hint"), x + 8, Math.max(ty + 10, y + 140), W - 16, DIM);
    }

    private void drawDead(GuiGraphics g, int x, int y) {
        int bx = x + W / 2 - 18, by = y + 80;
        g.fill(bx, by, bx + 36, by + 18, 0xFF666666);
        g.fill(bx + 2, by + 2, bx + 34, by + 16, 0xFF000000);
        g.fill(bx + 3, by + 3, bx + 6, by + 15, RED);
        g.fill(bx + 36, by + 5, bx + 39, by + 13, 0xFF666666);
        wrap(g, Component.translatable("realism.phone2.dead"), x + 10, y + 112, W - 20, DIM);
    }

    private void drawLock(GuiGraphics g, int x, int y) {
        big(g, World.now(), x + W / 2, y + 34, 3, 0xFFFFFFFF);
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH));
        center(g, Component.literal(date), x + W / 2, y + 64, TEXT);
        int ny = y + 86;
        int unread = num("unread", 0);
        if (unread > 0) ny = notification(g, x, ny, "✉", Component.translatable("realism.phone2.unread", unread), () -> openApp("messages"));
        if (battery() <= 20) ny = notification(g, x, ny, "⚡", Component.translatable("realism.phone2.low_battery", battery()), () -> go(Mode.HOME, ""));
        if (num("updating", -1) >= 0) ny = notification(g, x, ny, "⚙", Component.translatable("realism.phone2.updating", num("updating", 0)), () -> openApp("settings"));
        if (!info.signal()) notification(g, x, ny, "✕", Component.translatable("realism.phone.no_signal_bar"), () -> go(Mode.HOME, ""));
        if (ticks / 10 % 2 == 0) center(g, Component.translatable("realism.phone2.unlock"), x + W / 2, y + H - 24, DIM);
        taps.add(new Tap(x, y + 14, W, H - 30, () -> go(Mode.HOME, "")));
    }

    private int notification(GuiGraphics g, int x, int y, String icon, Component text, Runnable run) {
        round(g, x + 6, y, W - 12, 20, 0xAA20202A);
        g.drawString(font, icon, x + 10, y + 6, GOLD, false);
        String s = font.plainSubstrByWidth(text.getString(), W - 34);
        g.drawString(font, s, x + 22, y + 6, TEXT, false);
        taps.add(new Tap(x + 6, y, W - 12, 20, run));
        return y + 24;
    }

    // ---- Home screen ----

    private record AppIcon(String id, String glyph, int color, Component name) {
    }

    private List<AppIcon> icons() {
        List<AppIcon> list = new ArrayList<>();
        list.add(new AppIcon("phone", "☎", 0xFF3FBF5F, Component.translatable("realism.phone2.app.phone")));
        list.add(new AppIcon("messages", "✉", 0xFF34C759, Component.translatable("realism.phone2.app.messages")));
        list.add(new AppIcon("bank", "$", 0xFFE0A526, Component.translatable("realism.phone2.app.bank")));
        list.add(new AppIcon("health", "♥", 0xFFFF4D6D, Component.translatable("realism.phone2.app.health")));
        list.add(new AppIcon("weather", "☀", 0xFF3D8BFF, Component.translatable("realism.phone2.app.weather")));
        list.add(new AppIcon("maps", "⌖", 0xFF2EB88A, Component.translatable("realism.phone2.app.maps")));
        list.add(new AppIcon("camera", "◉", 0xFF5A5A66, Component.translatable("realism.phone2.app.camera")));
        list.add(new AppIcon("clock", "◷", 0xFF22222A, Component.translatable("realism.phone2.app.clock")));
        if (has(PhoneOS.APPS + "Files")) list.add(new AppIcon("files", "▤", 0xFF2F7BEA, Component.translatable("realism.phoneos.app.files")));
        if (has(PhoneOS.APPS + "Settings")) list.add(new AppIcon("settings", "⚙", 0xFF8E8E99, Component.translatable("realism.phoneos.app.settings")));
        if (has(PhoneOS.APPS + "SkyPilot")) list.add(new AppIcon("skypilot", "✈", 0xFF1F9BD6, Component.translatable("realism.phoneos.app.skypilot")));
        list.add(new AppIcon("store", "⬇", 0xFF1E7CF2, Component.translatable("realism.phoneos.app.store")));
        if (has(PhoneOS.APPS + PhoneOS.SIDELOAD_APPS[PhoneOS.FINE]))
            list.add(new AppIcon("blockdash", "▶", 0xFFEF6C2F, Component.literal(PhoneOS.SIDELOAD_APPS[PhoneOS.FINE])));
        for (int k = PhoneOS.ADWARE; k <= PhoneOS.BRICKER; k++)
            if (has(PhoneOS.APPS + PhoneOS.SIDELOAD_APPS[k]))
                list.add(new AppIcon("junk:" + PhoneOS.SIDELOAD_APPS[k], "★", 0xFFB044D8, Component.literal(PhoneOS.SIDELOAD_APPS[k])));
        int n = 0;
        for (String name : split(os.getOrDefault("apps", ""), ",")) {
            int[] colors = {0xFF6A5ACD, 0xFF00A0A0, 0xFFD2691E, 0xFF4682B4, 0xFFC71585};
            list.add(new AppIcon("appp:" + name, name.isEmpty() ? "▣" : name.substring(0, 1).toUpperCase(Locale.ROOT), colors[n++ % colors.length], Component.literal(name)));
        }
        return list;
    }

    private void drawHome(GuiGraphics g, int x, int y, int mx, int my) {
        if (!has(PhoneOS.SPRINGBOARD)) {
            g.fill(x, y, x + W, y + H, 0xFF000000);
            wrap(g, Component.translatable("realism.phoneos.no_springboard"), x + 8, y + 60, W - 16, RED);
            return;
        }
        List<AppIcon> list = icons();
        int cols = 4, cell = (W - 8) / cols, top = y + SCREEN_TOP + 8;
        int rows = (list.size() + cols - 1) / cols, maxScroll = Math.max(0, rows * 34 - (H - 70));
        scroll = Math.min(scroll, maxScroll);
        g.enableScissor(x, y + SCREEN_TOP, x + W, y + H - 40);
        for (int i = 0; i < list.size(); i++) {
            AppIcon a = list.get(i);
            int ix = x + 4 + (i % cols) * cell + (cell - 22) / 2, iy = top + (i / cols) * 34 - scroll;
            boolean hover = mx >= ix && my >= iy && mx < ix + 22 && my < iy + 22;
            round(g, ix, iy, 22, 22, hover ? brighter(a.color) : a.color);
            g.drawString(font, a.glyph, ix + 11 - font.width(a.glyph) / 2, iy + 7, 0xFFFFFFFF, false);
            String label = a.name.getString();
            while (font.width(label) * 0.75f > cell - 1 && label.length() > 2) label = label.substring(0, label.length() - 1);
            int lw = (int) (font.width(label) * 0.75f);
            small(g, label, ix + 11 - lw / 2, iy + 24, TEXT);
            if (a.id.equals("messages") && num("unread", 0) > 0) {
                g.fill(ix + 16, iy - 2, ix + 24, iy + 6, RED);
                small(g, String.valueOf(Math.min(9, num("unread", 0))), ix + 18, iy, 0xFFFFFFFF);
            }
            if (iy + 22 > y + SCREEN_TOP && iy < y + H - 40) taps.add(new Tap(ix, iy, 22, 22, () -> launch(a.id)));
        }
        g.disableScissor();
        // The dock.
        round(g, x + 6, y + H - 38, W - 12, 26, 0x66FFFFFF);
        String[] dock = {"phone", "messages", "camera", "settings"};
        for (int i = 0; i < dock.length; i++) {
            String want = dock[i];
            AppIcon a = list.stream().filter(ic -> ic.id.equals(want)).findFirst().orElse(null);
            if (a == null) continue;
            int ix = x + 12 + i * ((W - 24) / 4) + 2, iy = y + H - 36;
            round(g, ix, iy, 22, 22, a.color);
            g.drawString(font, a.glyph, ix + 11 - font.width(a.glyph) / 2, iy + 7, 0xFFFFFFFF, false);
            taps.add(new Tap(ix, iy, 22, 22, () -> launch(a.id)));
        }
    }

    private void launch(String id) {
        if (id.equals("blockdash")) {
            action("play");
            return;
        }
        if (id.equals("camera")) {
            Camera.take();
            onClose();
            return;
        }
        openApp(id);
    }

    // ---- Apps ----

    private void appBar(GuiGraphics g, int x, int y, Component title, int mx, int my) {
        g.fill(x, y + SCREEN_TOP, x + W, y + SCREEN_TOP + 14, 0x66000000);
        center(g, title, x + W / 2, y + SCREEN_TOP + 3, 0xFFFFFFFF);
        boolean hover = mx >= x && my >= y + SCREEN_TOP && mx < x + 24 && my < y + SCREEN_TOP + 14;
        g.drawString(font, "‹", x + 5, y + SCREEN_TOP + 3, hover ? GOLD : BLUE, false);
        taps.add(new Tap(x, y + SCREEN_TOP, 26, 14, this::back));
    }

    private void drawApp(GuiGraphics g, int x, int y, int mx, int my) {
        int top = y + SCREEN_TOP + 18;
        switch (app) {
            case "phone" -> phoneApp(g, x, y, top, mx, my);
            case "messages" -> messages(g, x, y, top, mx, my);
            case "thread" -> thread(g, x, y, top, mx, my);
            case "bank" -> bank(g, x, y, top, mx, my);
            case "health" -> health(g, x, y, top, mx, my);
            case "weather" -> weather(g, x, y, top, mx, my);
            case "maps" -> maps(g, x, y, top, mx, my);
            case "clock" -> clock(g, x, y, top, mx, my);
            case "files" -> filesApp(g, x, y, top, mx, my);
            case "settings" -> settings(g, x, y, top, mx, my);
            case "skypilot" -> skyPilot(g, x, y, top, mx, my);
            case "store" -> store(g, x, y, top, mx, my);
            default -> {
                if (app.startsWith("appp:")) appp(g, x, y, top, mx, my);
                else if (app.startsWith("junk:")) {
                    appBar(g, x, y, Component.literal(app.substring(5)), mx, my);
                    wrap(g, Component.translatable("realism.phone2.junk"), x + 8, top + 4, W - 16, DIM);
                }
            }
        }
    }

    private void phoneApp(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.phone"), mx, my);
        boolean canCall = has(PhoneOS.TELEPHONY) && info.signal();
        if (!canCall) wrap(g, Component.translatable(has(PhoneOS.TELEPHONY) ? "realism.phone.no_signal_bar" : "realism.phoneos.no_service_bar"), x + 8, top, W - 16, RED);
        int by = top + 20;
        button(g, x + 10, by, W - 20, 24, Component.translatable("realism.phone.call_ambulance"), 0xFFD93636, mx, my, () -> {
            PacketDistributor.sendToServer(new World.CallAmbulance());
            onClose();
        });
        button(g, x + 10, by + 30, W - 20, 24, Component.translatable("realism.phone.call_fire"), 0xFFE07020, mx, my, () -> {
            PacketDistributor.sendToServer(new World.CallFire());
            onClose();
        });
        button(g, x + 10, by + 60, W - 20, 24, Component.translatable("realism.phone.helpline"), 0xFF3A8DDE, mx, my, () -> {
            PacketDistributor.sendToServer(new Bullying.CallHelpline());
            onClose();
        });
        wrap(g, Component.translatable("realism.phone2.helpline_note"), x + 8, by + 94, W - 16, DIM);
    }

    /** People to text: everyone online, plus anyone you've texted before. */
    private List<String> threads() {
        Set<String> names = new LinkedHashSet<>();
        List<String[]> msgs = messagesList();
        for (int i = msgs.size() - 1; i >= 0; i--) names.add(msgs.get(i)[0]);
        names.addAll(List.of(contacts));
        return new ArrayList<>(names);
    }

    private List<String[]> messagesList() {
        List<String[]> out = new ArrayList<>();
        for (String m : split(os.getOrDefault("msgs", ""), String.valueOf(PhoneApps.ITEM))) {
            String[] f = m.split(String.valueOf(PhoneApps.FIELD), -1);
            if (f.length == 4) out.add(f);
        }
        return out;
    }

    private void messages(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.messages"), mx, my);
        List<String> people = threads();
        if (people.isEmpty()) {
            wrap(g, Component.translatable("realism.phone.nobody"), x + 8, top + 4, W - 16, DIM);
            return;
        }
        List<String[]> msgs = messagesList();
        int row = top;
        for (String who : people) {
            if (row > y + H - 40) break;
            String last = "";
            for (String[] m : msgs) if (m[0].equals(who)) last = (m[1].equals("1") ? "› " : "") + m[2];
            boolean online = List.of(contacts).contains(who);
            boolean hover = mx >= x + 4 && my >= row && mx < x + W - 4 && my < row + 24;
            round(g, x + 4, row, W - 8, 24, hover ? 0x5530304A : 0x3320202A);
            g.fill(x + 8, row + 4, x + 24, row + 20, online ? 0xFF34C759 : 0xFF555566);
            g.drawString(font, who.substring(0, 1).toUpperCase(Locale.ROOT), x + 13, row + 8, 0xFFFFFFFF, false);
            g.drawString(font, font.plainSubstrByWidth(who, W - 40), x + 28, row + 3, TEXT, false);
            small(g, font.plainSubstrByWidth(last.isEmpty() ? Component.translatable(online ? "realism.phone2.online" : "realism.phone2.offline").getString() : last, (int) ((W - 40) / 0.75f)), x + 28, row + 14, DIM);
            taps.add(new Tap(x + 4, row, W - 8, 24, () -> {
                thread = who;
                go(Mode.APP, "thread");
            }));
            row += 27;
        }
    }

    private void thread(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.literal(thread), mx, my);
        List<String[]> mine = new ArrayList<>();
        for (String[] m : messagesList()) if (m[0].equals(thread)) mine.add(m);
        // Bubbles from the bottom up, newest at the bottom.
        int bottom = y + H - SCREEN_BOTTOM - 26, maxW = W - 36;
        g.enableScissor(x, top - 2, x + W, bottom + 2);
        int row = bottom;
        for (int i = mine.size() - 1; i >= 0 && row > top; i--) {
            String[] m = mine.get(i);
            boolean me = m[1].equals("1");
            var lines = font.split(Component.literal(m[2]), maxW);
            int w = 0;
            for (var l : lines) w = Math.max(w, font.width(l));
            int h = lines.size() * 10 + 6;
            row -= h + 4;
            int bx = me ? x + W - 8 - w - 8 : x + 8;
            round(g, bx, row, w + 8, h, me ? 0xFF2F7BEA : 0xFF3A3A48);
            int ly = row + 3;
            for (var l : lines) {
                g.drawString(font, l, bx + 4, ly, 0xFFFFFFFF, false);
                ly += 10;
            }
        }
        if (mine.isEmpty()) center(g, Component.translatable("realism.phone2.say_hi"), x + W / 2, top + 30, DIM);
        g.disableScissor();
        button(g, x + W - 38, y + H - SCREEN_BOTTOM - 21, 30, 16, Component.translatable("realism.phone.send"), 0xFF2F7BEA, mx, my, this::send);
    }

    private void bank(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.bank"), mx, my);
        if (info.balance() < 0) {
            wrap(g, Component.translatable("realism.phoneos.no_bank"), x + 8, top + 4, W - 16, RED);
            return;
        }
        round(g, x + 6, top, W - 12, 46, 0xFF1E3A2A);
        small(g, Component.translatable("realism.phone2.balance").getString(), x + 12, top + 5, DIM);
        big(g, "$" + info.balance(), x + W / 2, top + 18, 2, GOLD);
        long debt = Long.parseLong(os.getOrDefault("debt", "0"));
        if (debt > 0) center(g, Component.translatable("realism.phone2.debt", debt), x + W / 2, top + 50, RED);
        int sy = y + H - SCREEN_BOTTOM - 82;
        g.drawString(font, Component.translatable("realism.phone2.send_money"), x + 8, sy, TEXT, false);
        if (contacts.length == 0) {
            wrap(g, Component.translatable("realism.phone.nobody"), x + 8, sy + 14, W - 16, DIM);
            return;
        }
        String to = contacts[contact % contacts.length];
        button(g, x + 8, sy + 12, W - 16, 14, Component.literal("→ " + to + " ⟳"), 0xFF3A3A48, mx, my, () -> contact = (contact + 1) % contacts.length);
        button(g, x + 72, y + H - SCREEN_BOTTOM - 58, W - 80, 14, Component.translatable("realism.phone.send"), 0xFF2EA043, mx, my, this::send);
        small(g, Component.translatable("realism.phone2.taxed").getString(), x + 8, y + H - SCREEN_BOTTOM - 40, DIM);
    }

    private void bar(GuiGraphics g, int x, int y, String label, int pct, String value, int color) {
        g.drawString(font, label, x, y, TEXT, false);
        g.drawString(font, value, x + W - 16 - font.width(value), y, DIM, false);
        g.fill(x, y + 10, x + W - 16, y + 14, 0xFF2A2A36);
        g.fill(x, y + 10, x + (W - 16) * Mth.clamp(pct, 0, 100) / 100, y + 14, color);
    }

    private void health(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.health"), mx, my);
        String[] h = os.getOrDefault("health", "").split(String.valueOf(PhoneApps.FIELD), -1);
        if (h.length < 7) return;
        int water = parse(h[0]), rest = parse(h[1]), stamina = parse(h[2]), blood = parse(h[3]), mood = parse(h[4]), bladder = parse(h[5]);
        int row = top;
        bar(g, x + 8, row, tr("water"), water, water + "%", water < 30 ? RED : BLUE);
        bar(g, x + 8, row += 19, tr("rest"), rest, rest + "%", rest < 30 ? RED : 0xFF9B7BFF);
        bar(g, x + 8, row += 19, tr("stamina"), stamina, stamina + "%", GREEN);
        bar(g, x + 8, row += 19, tr("blood"), blood * 2, blood / 10 + "." + blood % 10 + " L", blood < 40 ? RED : 0xFFE0404A);
        bar(g, x + 8, row += 19, tr("mood"), mood, mood + "%", mood < 30 ? GOLD : 0xFFFFC85C);
        bar(g, x + 8, row += 19, tr("bladder"), bladder, bladder + "%", bladder >= 80 ? RED : 0xFFE8D44D);
        row += 22;
        String[] wrong = h[6].isEmpty() ? new String[0] : h[6].split(String.valueOf(PhoneApps.ITEM));
        if (wrong.length == 0) wrap(g, Component.translatable("realism.phone2.health_ok"), x + 8, row, W - 16, GREEN);
        for (String w : wrong) {
            if (row > y + H - 26) break;
            g.drawString(font, "• " + Component.translatable("realism.phone2.problem." + w).getString(), x + 8, row, RED, false);
            row += 10;
        }
    }

    private String tr(String key) {
        return Component.translatable("realism.phone2.health." + key).getString();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void weather(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.weather"), mx, my);
        String[] w = os.getOrDefault("weather", "").split(String.valueOf(PhoneApps.FIELD), -1);
        if (w.length < 5) return;
        int outside = parse(w[0]), chill = parse(w[3]), sky = parse(w[4]);
        boolean inside = w[1].equals("1");
        // A rough temperature from how cold/hot it is out there.
        int celsius = outside > 0 ? 15 - outside * 35 / 100 : outside < 0 ? 25 - outside * 25 / 100 : 20;
        String icon = sky == 2 ? "⚡" : sky == 1 ? "☂" : outside > 50 ? "❄" : outside < -50 ? "☀" : "⛅";
        big(g, icon, x + W / 2, top + 4, 3, sky > 0 ? BLUE : outside > 0 ? 0xFFBFE6FF : GOLD);
        big(g, celsius + "°C", x + W / 2, top + 34, 2, 0xFFFFFFFF);
        String feel = outside > 60 ? "freezing" : outside > 25 ? "cold" : outside < -60 ? "scorching" : outside < -25 ? "hot" : "mild";
        center(g, Component.translatable("realism.phone2.weather." + feel), x + W / 2, top + 54, TEXT);
        if (sky > 0) center(g, Component.translatable(sky == 2 ? "realism.phone2.weather.storm" : "realism.phone2.weather.rain"), x + W / 2, top + 65, BLUE);
        int row = top + 80;
        round(g, x + 6, row, W - 12, 64, 0x55000000);
        g.drawString(font, Component.translatable("realism.phone2.house"), x + 10, row + 4, GOLD, false);
        Component house = !inside ? Component.translatable("realism.phone2.house.outside")
                : Component.translatable("realism.phone2.house." + w[2].toLowerCase(Locale.ROOT));
        int ty = wrap(g, house, x + 10, row + 16, W - 20, TEXT);
        String you = chill >= 50 ? "shivering" : chill >= 25 ? "chilly" : chill <= -50 ? "overheating" : chill <= -25 ? "warm" : "comfy";
        wrap(g, Component.translatable("realism.phone2.you." + you), x + 10, ty + 2, W - 20, Math.abs(chill) >= 25 ? RED : GREEN);
    }

    private void maps(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.maps"), mx, my);
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int size = MiniMap.SIZE, mapX = x + (W - size) / 2, mapY = top;
        g.fill(mapX - 1, mapY - 1, mapX + size + 1, mapY + size + 1, 0xFF000000);
        int[] px = MiniMap.pixels;
        if (px != null)
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++) g.fill(mapX + i, mapY + j, mapX + i + 1, mapY + j + 1, px[j * size + i]);
        // You: a dot with a little arrow for where you face.
        int cx = mapX + size / 2, cy = mapY + size / 2;
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        for (int s = 1; s <= 5; s++) g.fill(cx - (int) (Mth.sin(yaw) * s), cy + (int) (Mth.cos(yaw) * s), cx - (int) (Mth.sin(yaw) * s) + 1, cy + (int) (Mth.cos(yaw) * s) + 1, 0xFFFFFFFF);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFF3030);
        g.drawString(font, "N", mapX + size / 2 - 2, mapY + 1, 0xFFFFFFFF, true);
        BlockPos p = player.blockPosition();
        int row = mapY + size + 5;
        g.drawString(font, "X " + p.getX() + "  Y " + p.getY() + "  Z " + p.getZ(), x + 8, row, TEXT, false);
        g.drawString(font, Component.translatable("realism.phone2.facing", player.getDirection().getName()), x + 8, row + 11, DIM, false);
        g.drawString(font, info.country().isEmpty() ? Component.translatable("realism.phone.no_country") : Component.literal(info.country()), x + 8, row + 22, 0xFFB0C4FF, false);
    }

    private void clock(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phone2.app.clock"), mx, my);
        big(g, World.now(), x + W / 2, top + 20, 3, 0xFFFFFFFF);
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
        center(g, Component.literal(date), x + W / 2, top + 52, TEXT);
        var level = Minecraft.getInstance().level;
        if (level != null) {
            long t = level.getDayTime();
            int day = (int) (t / 24000) + 1, hour = (int) ((t % 24000) / 1000 + 6) % 24, min = (int) ((t % 1000) * 60 / 1000);
            round(g, x + 6, top + 74, W - 12, 34, 0x55000000);
            g.drawString(font, Component.translatable("realism.phone2.mc_day", day), x + 12, top + 79, GOLD, false);
            g.drawString(font, String.format(Locale.ROOT, "%02d:%02d", hour, min), x + 12, top + 92, TEXT, false);
        }
    }

    private static boolean system(String f) {
        return f.equals(PhoneOS.BOOT) || f.equals(PhoneOS.KERNEL) || f.equals(PhoneOS.LAUNCHD);
    }

    private void filesApp(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phoneos.app.files"), mx, my);
        int bottom = y + H - SCREEN_BOTTOM - (confirm.isEmpty() ? 4 : 34);
        int max = Math.max(0, files.size() * 12 - (bottom - top));
        scroll = Math.min(scroll, max);
        g.enableScissor(x, top, x + W, bottom);
        int row = top - scroll;
        for (String f : files) {
            String shortName = f.length() > 20 ? "…" + f.substring(f.length() - 19) : f;
            small(g, shortName, x + 6, row + 2, system(f) ? GOLD : TEXT);
            boolean hover = mx >= x + W - 16 && my >= row && mx < x + W - 4 && my < row + 10 && my >= top && my < bottom;
            g.fill(x + W - 16, row, x + W - 4, row + 10, hover ? 0xFFFF5050 : 0xFF8A2A2A);
            g.drawString(font, "×", x + W - 13, row + 1, 0xFFFFFFFF, false);
            if (row >= top && row + 10 <= bottom) taps.add(new Tap(x + W - 16, row, 12, 10, () -> {
                if (system(f) && !confirm.equals(f)) {
                    confirm = f;
                    return;
                }
                action("delete " + f);
            }));
            row += 12;
        }
        g.disableScissor();
        if (!confirm.isEmpty()) wrap(g, Component.translatable("realism.phoneos.confirm_system", confirm), x + 6, bottom + 4, W - 12, RED);
    }

    private void settings(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phoneos.app.settings"), mx, my);
        boolean dev = "1".equals(os.get("dev"));
        int row = top;
        // Battery.
        round(g, x + 6, row, W - 12, 20, 0x55000000);
        g.drawString(font, Component.translatable("realism.phone2.battery", battery()), x + 10, row + 6, battery() <= 20 ? RED : TEXT, false);
        row += 24;
        // Software update.
        round(g, x + 6, row, W - 12, 46, 0x55000000);
        String version = os.getOrDefault("version", "2.0");
        g.drawString(font, "RealismPhoneOS " + version, x + 10, row + 4, TEXT, false);
        int updating = num("updating", -1);
        if (updating >= 0) {
            int total = 30, done = total - updating;
            g.drawString(font, Component.translatable("realism.phone2.updating", updating), x + 10, row + 16, GOLD, false);
            g.fill(x + 10, row + 30, x + W - 10, row + 34, 0xFF333344);
            g.fill(x + 10, row + 30, x + 10 + (W - 20) * Mth.clamp(done, 0, total) / total, row + 34, GOLD);
        } else if (version.equals("2.0")) {
            button(g, x + 10, row + 24, W - 20, 16, Component.translatable("realism.phone2.update_button"), 0xFF2F7BEA, mx, my, () -> action("update"));
            small(g, Component.translatable("realism.phone2.update_available").getString(), x + 10, row + 15, DIM);
        } else g.drawString(font, Component.translatable("realism.phone2.up_to_date"), x + 10, row + 18, GREEN, false);
        row += 50;
        // Developer Mode.
        button(g, x + 6, row, W - 12, 16, Component.translatable(dev ? "realism.phoneos.dev_is_on" : "realism.phoneos.dev_is_off"),
                dev ? 0xFFB03030 : 0xFF3A3A48, mx, my, () -> action(dev ? "dev off" : "dev on"));
        row += 20;
        row = wrap(g, Component.translatable("realism.phoneos.dev_info"), x + 8, row, W - 16, DIM);
        small(g, Component.translatable("realism.phone2.storage", files.size()).getString(), x + 8, Math.min(row + 4, y + H - 24), DIM);
    }

    private void skyPilot(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phoneos.app.skypilot"), mx, my);
        if (!has(PhoneOS.DRONE)) {
            wrap(g, Component.translatable("realism.phoneos.no_droned"), x + 8, top + 4, W - 16, RED);
            return;
        }
        String list = os.getOrDefault("drones", "");
        if (list.isEmpty()) wrap(g, Component.translatable("realism.phoneos.no_drones"), x + 8, top + 4, W - 16, DIM);
        int row = top;
        for (String d : split(list, ",")) {
            String[] p = d.split(":");
            if (p.length < 4 || row > y + H - 70) continue;
            round(g, x + 6, row, W - 12, 26, 0x55000000);
            g.drawString(font, Component.translatable("item.realism." + p[0]), x + 10, row + 3, TEXT, false);
            small(g, "⚡" + p[1] + "%   ⌖ " + p[2] + " m   ✣ " + p[3], x + 10, row + 15, 0xFFB0C4FF);
            row += 30;
        }
        button(g, x + 8, y + H - SCREEN_BOTTOM - 22, W - 16, 16, Component.translatable("realism.phoneos.rth_button"), 0xFF1F9BD6, mx, my, () -> action("rth"));
    }

    private void store(GuiGraphics g, int x, int y, int top, int mx, int my) {
        appBar(g, x, y, Component.translatable("realism.phoneos.app.store"), mx, my);
        String list = os.getOrDefault("store", "");
        String status = switch (list) {
            case "", "!loading" -> "realism.phoneos.store_loading";
            case "!offline" -> "realism.phoneos.store_offline";
            case "!empty" -> "realism.phoneos.store_empty";
            default -> "realism.phoneos.store_scanned";
        };
        int row = wrap(g, Component.translatable(status), x + 8, top, W - 16, list.startsWith("!") && !list.equals("!loading") ? RED : GREEN) + 4;
        if (!list.startsWith("!"))
            for (String item : split(list, String.valueOf(PhoneApps.ITEM))) {
                String[] a = item.split(String.valueOf(PhoneApps.FIELD), -1);
                if (a.length < 6 || row > y + H - 60) continue;
                String id = a[0];
                boolean have = os.getOrDefault("apps", "").contains(a[1]);
                round(g, x + 6, row, W - 12, 30, 0x55000000);
                g.drawString(font, font.plainSubstrByWidth(a[1] + " " + a[2], W - 50), x + 10, row + 4, TEXT, false);
                small(g, font.plainSubstrByWidth(a[5], (int) ((W - 54) / 0.75f)), x + 10, row + 17, DIM);
                button(g, x + W - 38, row + 7, 28, 16, Component.literal(have ? "✓" : "Get"), have ? 0xFF3A3A48 : 0xFF1E7CF2, mx, my, () -> action("get " + id));
                row += 34;
            }
        button(g, x + 8, y + H - SCREEN_BOTTOM - 22, W - 16, 16, Component.translatable("realism.phoneos.store_refresh"), 0xFF3A3A48, mx, my, () -> action("store"));
    }

    /** A .appp app's screen: title, lines and its buttons (from the server). */
    private void appp(GuiGraphics g, int x, int y, int top, int mx, int my) {
        String[] v = split(os.getOrDefault("view", ""), String.valueOf(PhoneApps.FIELD));
        appBar(g, x, y, Component.literal(v.length == 5 && !v[2].isEmpty() ? v[2] : app.substring(5)), mx, my);
        if (v.length != 5) {
            wrap(g, Component.translatable("realism.phoneos.store_loading"), x + 8, top, W - 16, DIM);
            return;
        }
        String[] labels = split(v[4], String.valueOf(PhoneApps.ITEM));
        int buttonsTop = y + H - SCREEN_BOTTOM - 6 - 20 * labels.length, row = top;
        for (String line : split(v[3], String.valueOf(PhoneApps.ITEM)))
            for (var l : font.split(Component.literal(line), W - 16)) {
                if (row > buttonsTop - 10) break;
                g.drawString(font, l, x + 8, row, TEXT, false);
                row += 10;
            }
        for (int i = 0; i < labels.length; i++) {
            int idx = i;
            button(g, x + 8, buttonsTop + i * 20, W - 16, 16, Component.literal(labels[i]), 0xFF3A3A48, mx, my,
                    () -> action("tap " + v[0] + PhoneApps.FIELD + v[1] + PhoneApps.FIELD + idx));
        }
    }

    private static String[] split(String s, String sep) {
        return s.isEmpty() ? new String[0] : s.split(java.util.regex.Pattern.quote(sep), -1);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- The Maps app's mini map (drawn from the blocks around you) ----

    static final class MiniMap {
        static final int SIZE = 112;
        static int[] pixels;

        static void refresh() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            int[] out = new int[SIZE * SIZE];
            BlockPos me = mc.player.blockPosition();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int i = 0; i < SIZE; i++)
                for (int j = 0; j < SIZE; j++) {
                    int bx = me.getX() + i - SIZE / 2, bz = me.getZ() + j - SIZE / 2;
                    int top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
                    var state = mc.level.getBlockState(m.set(bx, top, bz));
                    MapColor c = state.getMapColor(mc.level, m);
                    int rgb = c == MapColor.NONE ? 0x101018 : c.col;
                    // Shade by height, so hills show.
                    int shade = Mth.clamp((top - me.getY()) * 3, -40, 40);
                    int r = Mth.clamp(((rgb >> 16) & 255) + shade, 0, 255), gr = Mth.clamp(((rgb >> 8) & 255) + shade, 0, 255), b = Mth.clamp((rgb & 255) + shade, 0, 255);
                    out[j * SIZE + i] = 0xFF000000 | r << 16 | gr << 8 | b;
                }
            pixels = out;
        }
    }

    // ---- The Camera app: a photo without the HUD (saved like a screenshot) ----

    @EventBusSubscriber(modid = Realism.MODID, value = Dist.CLIENT)
    public static final class Camera {
        private static int countdown = -1;
        private static boolean hudWasHidden;

        static void take() {
            Minecraft mc = Minecraft.getInstance();
            hudWasHidden = mc.options.hideGui;
            mc.options.hideGui = true;
            countdown = 3;
        }

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            if (countdown < 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (--countdown > 0) return;
            countdown = -1;
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> mc.execute(() -> {
                mc.options.hideGui = hudWasHidden;
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("realism.phone2.photo_saved"), true);
            }));
            if (mc.player != null) mc.player.playSound(SoundEvents.SPYGLASS_USE, 1f, 2f);
        }
    }
}
