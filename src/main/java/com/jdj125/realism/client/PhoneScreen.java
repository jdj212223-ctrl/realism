package com.jdj125.realism.client;

import com.jdj125.realism.Bullying;
import com.jdj125.realism.PhoneApps;
import com.jdj125.realism.PhoneOS;
import com.jdj125.realism.World;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone, running RealismPhoneOS (4.11). It boots (realboot, realkernel, realism-launchd, then the services), and if
 * a boot file is missing it stops there: bricked. Home screen: signal, time, bank balance, country, texting, calls and
 * the helpline. Apps: Files (delete files... carefully), Settings (Developer Mode for sideloading), SkyPilot (your
 * drones) and any sideloaded apps.
 */
public class PhoneScreen extends Screen {
    private enum Mode {BOOT, HOME, APPS, FILES, SETTINGS, SKYPILOT, BRICKED, STORE, APP}

    private final World.OpenPhone info;
    private final String[] contacts;
    private final Map<String, String> os = new HashMap<>();
    private final List<String> files;
    private Mode mode;
    private int contact, bootTicks;
    private EditBox message;
    /** The system file the player clicked delete on once (a second click deletes it). */
    private String confirm = "";

    private PhoneScreen(World.OpenPhone info, Mode mode) {
        super(Component.translatable("item.realism.phone"));
        this.info = info;
        this.contacts = info.contacts().isEmpty() ? new String[0] : info.contacts().split(",");
        for (String part : info.os().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) os.put(part.substring(0, eq), part.substring(eq + 1));
        }
        this.files = new ArrayList<>(List.of(os.getOrDefault("files", "").split(",")));
        files.removeIf(String::isEmpty);
        this.mode = mode;
    }

    public static void open(World.OpenPhone info) {
        Minecraft mc = Minecraft.getInstance();
        // Already looking at the phone (an app refreshed it): stay in the same app, no boot animation.
        Mode keep = mc.screen instanceof PhoneScreen old && old.mode != Mode.BOOT ? old.mode : Mode.BOOT;
        PhoneScreen s = new PhoneScreen(info, keep);
        if (!s.bricked().isEmpty()) s.mode = keep == Mode.BOOT ? Mode.BOOT : Mode.BRICKED;
        mc.setScreen(s);
    }

    private String bricked() {
        return os.getOrDefault("boot", "");
    }

    private boolean has(String file) {
        return files.contains(file);
    }

    private int px() { return width / 2 - 60; }
    private int py() { return height / 2 - 100; }

    private void go(Mode m) {
        mode = m;
        confirm = "";
        rebuildWidgets();
    }

    private void action(String a) {
        PacketDistributor.sendToServer(new PhoneOS.Action(a));
    }

    @Override
    protected void init() {
        int x = px(), y = py();
        switch (mode) {
            case BOOT -> {
            }
            case BRICKED -> close(x + 30, y + 178);
            case HOME -> home(x, y);
            case APPS -> apps(x, y);
            case FILES -> files(x, y);
            case SETTINGS -> settings(x, y);
            case SKYPILOT -> skyPilot(x, y);
            case STORE -> store(x, y);
            case APP -> app(x, y);
        }
    }

    // ---- Realism 4.12: .appp apps ----

    private static final String FIELD = String.valueOf(PhoneApps.FIELD), ITEM = String.valueOf(PhoneApps.ITEM);

    private static String[] parts(String s, String sep) {
        return s.isEmpty() ? new String[0] : s.split(sep, -1);
    }

    /** The open app screen: name, screen id, title, lines, button labels (from the server). */
    private String[] view() {
        String[] v = parts(os.getOrDefault("view", ""), FIELD);
        return v.length == 5 ? v : null;
    }

    private void store(int x, int y) {
        String list = os.getOrDefault("store", "");
        int row = y + 40;
        if (!list.startsWith("!"))
            for (String item : parts(list, ITEM)) {
                String[] a = parts(item, FIELD);
                if (a.length < 6 || row > y + 150) continue;
                String id = a[0];
                boolean have = os.getOrDefault("apps", "").contains(a[1]);
                addRenderableWidget(Button.builder(Component.literal((have ? "✓ " : "⬇ ") + a[1] + " " + a[2]), b -> action("get " + id))
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(a[1] + " by " + a[3] + "\n" + a[5]
                                + (a[4].isEmpty() ? "" : "\nPermissions: " + a[4]))))
                        .bounds(x + 8, row, 104, 16).build());
                row += 20;
            }
        addRenderableWidget(Button.builder(Component.translatable("realism.phoneos.store_refresh"), b -> action("store")).bounds(x + 8, y + 160, 104, 14).build());
        back(x + 8, y + 182);
    }

    private void app(int x, int y) {
        String[] v = view();
        if (v != null) {
            String[] labels = parts(v[4], ITEM);
            int row = y + 178 - 18 * labels.length - 4;
            for (int i = 0; i < labels.length; i++) {
                int idx = i;
                addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> action("tap " + v[0] + FIELD + v[1] + FIELD + idx))
                        .bounds(x + 8, row, 104, 16).build());
                row += 18;
            }
        }
        back(x + 8, y + 182);
    }

    private void drawStore(GuiGraphics g, int x, int y) {
        title(g, x, y, "realism.phoneos.app.store");
        String list = os.getOrDefault("store", "");
        String status = switch (list) {
            case "", "!loading" -> "realism.phoneos.store_loading";
            case "!offline" -> "realism.phoneos.store_offline";
            case "!empty" -> "realism.phoneos.store_empty";
            default -> "realism.phoneos.store_scanned";
        };
        wrap(g, Component.translatable(status), x + 6, y + 16, 108, list.startsWith("!") && !list.equals("!loading") ? 0xFFFF7070 : 0xFF8FE08F);
    }

    private void drawApp(GuiGraphics g, int x, int y) {
        String[] v = view();
        if (v == null) {
            title(g, x, y, "realism.phoneos.apps");
            wrap(g, Component.translatable("realism.phoneos.store_loading"), x + 6, y + 20, 108, 0xFFDDDDDD);
            return;
        }
        g.fill(x, y, x + 120, y + 12, 0xFF0F1626);
        g.drawCenteredString(font, Component.literal(v[2].isEmpty() ? v[0] : v[2]), x + 60, y + 2, 0xFFFFFFFF);
        int row = y + 18;
        for (String line : parts(v[3], ITEM)) {
            for (var l : font.split(Component.literal(line), 108)) {
                if (row > y + 100) break;
                g.drawString(font, l, x + 6, row, 0xFFDDDDDD, false);
                row += 10;
            }
        }
    }

    private void close(int x, int y) {
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.close"), b -> onClose()).bounds(x, y, 60, 16).build());
    }

    private void back(int x, int y) {
        addRenderableWidget(Button.builder(Component.translatable("realism.phoneos.back"), b -> go(Mode.APPS)).bounds(x, y, 104, 14).build());
    }

    private void home(int x, int y) {
        if (!has(PhoneOS.SPRINGBOARD)) {
            close(x + 30, y + 178);
            return;
        }
        if (contacts.length > 0) {
            addRenderableWidget(Button.builder(Component.literal(contacts[contact]), b -> {
                contact = (contact + 1) % contacts.length;
                b.setMessage(Component.literal(contacts[contact]));
            }).bounds(x + 8, y + 92, 104, 18).build());
            message = new EditBox(font, x + 8, y + 116, 104, 16, Component.translatable("realism.phone.type"));
            message.setMaxLength(200);
            addRenderableWidget(message);
            setInitialFocus(message);
            addRenderableWidget(Button.builder(Component.translatable("realism.phone.send"), b -> send()).bounds(x + 8, y + 138, 104, 18).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.call_ambulance"), b -> {
            PacketDistributor.sendToServer(new World.CallAmbulance());
            onClose();
        }).bounds(x + 8, y + 62, 51, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.call_fire"), b -> {
            PacketDistributor.sendToServer(new World.CallFire());
            onClose();
        }).bounds(x + 61, y + 62, 51, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.helpline"), b -> {
            PacketDistributor.sendToServer(new Bullying.CallHelpline());
            onClose();
        }).bounds(x + 8, y + 158, 104, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phoneos.apps"), b -> go(Mode.APPS)).bounds(x + 8, y + 178, 50, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.close"), b -> onClose()).bounds(x + 62, y + 178, 50, 16).build());
    }

    private void apps(int x, int y) {
        int row = y + 22;
        if (has(PhoneOS.APPS + "Files")) row = appButton(x, row, "realism.phoneos.app.files", () -> go(Mode.FILES));
        if (has(PhoneOS.APPS + "Settings")) row = appButton(x, row, "realism.phoneos.app.settings", () -> go(Mode.SETTINGS));
        if (has(PhoneOS.APPS + "SkyPilot")) row = appButton(x, row, "realism.phoneos.app.skypilot", () -> go(Mode.SKYPILOT));
        row = appButton(x, row, "realism.phoneos.app.store", () -> { go(Mode.STORE); action("store"); });
        for (String name : parts(os.getOrDefault("apps", ""), ",")) {
            if (row > y + 160) break;
            addRenderableWidget(Button.builder(Component.literal("📱 " + name), b -> { go(Mode.APP); action("app " + name); }).bounds(x + 8, row, 104, 16).build());
            row += 20;
        }
        if (has(PhoneOS.APPS + PhoneOS.SIDELOAD_APPS[PhoneOS.FINE])) row = appButton(x, row, "realism.phoneos.app.blockdash", () -> action("play"));
        for (int k = PhoneOS.ADWARE; k <= PhoneOS.BRICKER; k++)
            if (has(PhoneOS.APPS + PhoneOS.SIDELOAD_APPS[k])) {
                String name = PhoneOS.SIDELOAD_APPS[k];
                addRenderableWidget(Button.builder(Component.literal(name), b -> { }).bounds(x + 8, row, 104, 16).build());
                row += 20;
            }
        addRenderableWidget(Button.builder(Component.translatable("realism.phoneos.home"), b -> go(Mode.HOME)).bounds(x + 8, y + 178, 50, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.phone.close"), b -> onClose()).bounds(x + 62, y + 178, 50, 16).build());
    }

    private int appButton(int x, int row, String key, Runnable onPress) {
        addRenderableWidget(Button.builder(Component.translatable(key), b -> onPress.run()).bounds(x + 8, row, 104, 16).build());
        return row + 20;
    }

    private static boolean system(String f) {
        return f.equals(PhoneOS.BOOT) || f.equals(PhoneOS.KERNEL) || f.equals(PhoneOS.LAUNCHD);
    }

    private void files(int x, int y) {
        int row = y + 16;
        for (String f : files) {
            if (row > y + 160) break;
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                if (system(f) && !confirm.equals(f)) {
                    confirm = f;
                    return;
                }
                action("delete " + f);
            }).bounds(x + 104, row, 12, 10).build());
            row += 11;
        }
        back(x + 8, y + 182);
    }

    private void settings(int x, int y) {
        boolean dev = "1".equals(os.get("dev"));
        addRenderableWidget(Button.builder(Component.translatable(dev ? "realism.phoneos.dev_is_on" : "realism.phoneos.dev_is_off"),
                b -> action(dev ? "dev off" : "dev on")).bounds(x + 8, y + 30, 104, 16).build());
        back(x + 8, y + 182);
    }

    private void skyPilot(int x, int y) {
        addRenderableWidget(Button.builder(Component.translatable("realism.phoneos.rth_button"), b -> action("rth")).bounds(x + 8, y + 160, 104, 16).build());
        back(x + 8, y + 182);
    }

    private void send() {
        if (message == null || message.getValue().isBlank() || contacts.length == 0) return;
        PacketDistributor.sendToServer(new World.SendText(contacts[contact], message.getValue()));
        message.setValue("");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (message != null && message.isFocused() && (key == 257 || key == 335)) {
            send();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void tick() {
        super.tick();
        if (mode != Mode.BOOT) return;
        bootTicks++;
        int failAt = switch (bricked()) {
            case "realboot", "water" -> 1;
            case "realkernel" -> 2;
            case "realism-launchd" -> 3;
            default -> 99;
        };
        if (bootTicks >= Math.min(failAt, 6) * 7 + 4) go(failAt < 99 ? Mode.BRICKED : Mode.HOME);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = px(), y = py();
        g.fill(x - 4, y - 4, x + 124, y + 204, 0xFF15151A);
        boolean dark = mode == Mode.BOOT || mode == Mode.BRICKED || (mode == Mode.HOME && !has(PhoneOS.SPRINGBOARD));
        g.fill(x, y, x + 120, y + 200, dark ? 0xFF000000 : 0xFF1F2C44);
        switch (mode) {
            case BOOT -> drawBoot(g, x, y);
            case BRICKED -> drawBricked(g, x, y);
            case HOME -> drawHome(g, x, y);
            case APPS -> title(g, x, y, "realism.phoneos.apps");
            case FILES -> drawFiles(g, x, y);
            case SETTINGS -> {
                title(g, x, y, "realism.phoneos.app.settings");
                wrap(g, Component.translatable("realism.phoneos.dev_info"), x + 6, y + 52, 108, 0xFFDDDDDD);
            }
            case SKYPILOT -> drawSkyPilot(g, x, y);
            case STORE -> drawStore(g, x, y);
            case APP -> drawApp(g, x, y);
        }
    }

    private void title(GuiGraphics g, int x, int y, String key) {
        g.fill(x, y, x + 120, y + 12, 0xFF0F1626);
        g.drawCenteredString(font, Component.translatable(key), x + 60, y + 2, 0xFFFFFFFF);
    }

    private void wrap(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (var line : font.split(text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
    }

    private void drawBoot(GuiGraphics g, int x, int y) {
        String[] lines = {"realboot 1.0", "realkernel loading...", "realism-launchd: starting", "telephonyd", "droned", "bankd", "springboard"};
        String[] files = {PhoneOS.BOOT, PhoneOS.KERNEL, PhoneOS.LAUNCHD, PhoneOS.TELEPHONY, PhoneOS.DRONE, PhoneOS.BANK, PhoneOS.SPRINGBOARD};
        int shown = Math.min(lines.length, bootTicks / 6 + 1);
        for (int i = 0; i < shown; i++) {
            boolean ok = has(files[i]) && !(i == 0 && "water".equals(bricked()));
            g.drawString(font, (ok ? "[ok] " : "[!!] ") + lines[i], x + 4, y + 20 + i * 11, ok ? 0xFF7CFC7C : 0xFFFF6060, false);
        }
    }

    private void drawBricked(GuiGraphics g, int x, int y) {
        String why = bricked();
        int color = 0xFFFF6060;
        wrap(g, Component.translatable("realism.phoneos.bricked." + why), x + 6, y + 40, 108, color);
        wrap(g, Component.translatable("realism.phoneos.restore_hint"), x + 6, y + 120, 108, 0xFFB0B0B0);
    }

    private void drawHome(GuiGraphics g, int x, int y) {
        if (!has(PhoneOS.SPRINGBOARD)) {
            wrap(g, Component.translatable("realism.phoneos.no_springboard"), x + 6, y + 60, 108, 0xFFFF6060);
            return;
        }
        g.fill(x, y, x + 120, y + 12, 0xFF0F1626);
        g.drawString(font, World.now(), x + 4, y + 2, 0xFFFFFFFF, false);
        boolean service = has(PhoneOS.TELEPHONY);
        for (int i = 0; i < 4; i++) {
            int h = 3 + i * 2;
            g.fill(x + 96 + i * 5, y + 10 - h, x + 99 + i * 5, y + 10, info.signal() && service ? 0xFFFFFFFF : 0xFF555555);
        }
        Component sig = !service ? Component.translatable("realism.phoneos.no_service_bar")
                : Component.translatable(info.signal() ? "realism.phone.signal" : "realism.phone.no_signal_bar");
        g.drawCenteredString(font, sig, x + 60, y + 18, info.signal() && service ? 0xFF8FE08F : 0xFFFF7070);
        g.drawCenteredString(font, info.balance() < 0 ? Component.translatable("realism.phoneos.no_bank")
                : Component.translatable("realism.phone.balance", info.balance()), x + 60, y + 36, 0xFFFFE07A);
        g.drawCenteredString(font, info.country().isEmpty() ? Component.translatable("realism.phone.no_country") : Component.literal(info.country()), x + 60, y + 50, 0xFFB0C4FF);
        g.drawCenteredString(font, Component.translatable(contacts.length == 0 ? "realism.phone.nobody" : "realism.phone.text_to"), x + 60, y + 78, 0xFFDDDDDD);
    }

    private void drawFiles(GuiGraphics g, int x, int y) {
        title(g, x, y, "realism.phoneos.app.files");
        int row = y + 16;
        for (String f : files) {
            if (row > y + 160) break;
            String shortName = f.length() > 18 ? "…" + f.substring(f.length() - 17) : f;
            g.drawString(font, shortName, x + 3, row + 1, system(f) ? 0xFFFFC060 : 0xFFDDDDDD, false);
            row += 11;
        }
        if (!confirm.isEmpty()) wrap(g, Component.translatable("realism.phoneos.confirm_system", confirm), x + 4, y + 166, 112, 0xFFFF6060);
    }

    private void drawSkyPilot(GuiGraphics g, int x, int y) {
        title(g, x, y, "realism.phoneos.app.skypilot");
        if (!has(PhoneOS.DRONE)) {
            wrap(g, Component.translatable("realism.phoneos.no_droned"), x + 6, y + 30, 108, 0xFFFF6060);
            return;
        }
        String list = os.getOrDefault("drones", "");
        if (list.isEmpty()) {
            wrap(g, Component.translatable("realism.phoneos.no_drones"), x + 6, y + 30, 108, 0xFFDDDDDD);
            return;
        }
        int row = y + 18;
        for (String d : list.split(",")) {
            String[] p = d.split(":");
            if (p.length < 4 || row > y + 150) continue;
            g.drawString(font, Component.translatable("item.realism." + p[0]), x + 4, row, 0xFFFFFFFF, false);
            g.drawString(font, "🔋" + p[1] + "%  📡" + p[2] + "m  🌀" + p[3], x + 4, row + 10, 0xFFB0C4FF, false);
            row += 24;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
