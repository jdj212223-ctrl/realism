package com.jdj125.realism.client;

import com.jdj125.realism.Tech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The computer screen: a desktop drawn in the style of its operating system (our own look-alikes made of colored
 * shapes, no real logos or artwork) with an icon for each installed app. Apps: Notes and Paint save back into the
 * computer when you close them; Calculator; Terminal (a few pretend commands); Snake (a good score lifts your mood);
 * Music (a little keyboard you play yourself).
 */
public class OsScreen extends Screen {
    private final Tech.OpenComputer computer;
    private final int os;
    private Tech.App open; // null = the desktop
    private String notes;
    private final byte[] paint;
    private MultiLineEditBox notesBox;
    private EditBox terminalInput;
    private final List<String> terminal = new ArrayList<>();
    private String calc = "0";
    private double calcAcc;
    private char calcOp = 0;
    private boolean calcFresh = true;
    private int paintColor = 1;
    private static final int[] PALETTE = {0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00};

    // Snake
    private static final int SW = 20, SH = 14;
    private final List<int[]> snake = new ArrayList<>();
    private int dx = 1, dy = 0, foodX, foodY, score, snakeTick;
    private boolean snakeDead;
    private final Random rnd = new Random();

    public OsScreen(Tech.OpenComputer computer) {
        super(Component.translatable("realism.os." + Tech.OSES[Math.floorMod(computer.os(), Tech.OSES.length)]));
        this.computer = computer;
        this.os = Math.floorMod(computer.os(), Tech.OSES.length);
        this.notes = computer.notes();
        this.paint = computer.paint().clone();
    }

    public static void open(Tech.OpenComputer computer) {
        Minecraft.getInstance().setScreen(new OsScreen(computer));
    }

    private boolean has(Tech.App a) {
        return (computer.apps() & (1 << a.ordinal())) != 0;
    }

    // ---- Layout ----

    private int wx() { return width / 2 - 130; }
    private int wy() { return height / 2 - 80; }

    @Override
    protected void init() {
        clearWidgets();
        if (open == null) desktop();
        else app();
    }

    private void desktop() {
        int x = width / 2 - 120, y = height / 2 - 50, i = 0;
        for (Tech.App a : Tech.App.values()) {
            if (!has(a)) continue;
            Tech.App app = a;
            addRenderableWidget(Button.builder(Component.translatable("realism.app." + a.name().toLowerCase()), b -> openApp(app))
                    .bounds(x + (i % 3) * 82, y + (i / 3) * 26, 78, 20).build());
            i++;
        }
        addRenderableWidget(Button.builder(Component.translatable("realism.pc.shutdown"), b -> onClose())
                .bounds(width / 2 - 40, height / 2 + 40, 80, 20).build());
    }

    private void openApp(Tech.App a) {
        open = a;
        if (a == Tech.App.SNAKE) resetSnake();
        if (a == Tech.App.TERMINAL && terminal.isEmpty()) terminal.add(Component.translatable("realism.term.hello").getString());
        init();
    }

    private void app() {
        int x = wx(), y = wy();
        addRenderableWidget(Button.builder(Component.literal("x"), b -> back()).bounds(x + 246, y + 2, 12, 12).build());
        switch (open) {
            case NOTES -> {
                notesBox = new MultiLineEditBox(font, x + 6, y + 18, 248, 136, Component.translatable("realism.notes.hint"), Component.empty());
                notesBox.setCharacterLimit(4000);
                notesBox.setValue(notes);
                notesBox.setValueListener(v -> notes = v);
                addRenderableWidget(notesBox);
                setInitialFocus(notesBox);
            }
            case CALC -> {
                String keys = "789/456*123-C0=+";
                for (int i = 0; i < keys.length(); i++) {
                    char k = keys.charAt(i);
                    addRenderableWidget(Button.builder(Component.literal(String.valueOf(k)), b -> calcKey(k))
                            .bounds(x + 70 + (i % 4) * 30, y + 46 + (i / 4) * 24, 26, 20).build());
                }
            }
            case TERMINAL -> {
                terminalInput = new EditBox(font, x + 6, y + 140, 248, 14, Component.empty());
                terminalInput.setMaxLength(80);
                addRenderableWidget(terminalInput);
                setInitialFocus(terminalInput);
            }
            case PAINT -> {
                for (int c = 0; c < PALETTE.length; c++) {
                    int col = c;
                    addRenderableWidget(Button.builder(Component.literal(c == paintColor ? "•" : ""), b -> { paintColor = col; init(); })
                            .bounds(x + 200, y + 18 + c * 17, 14, 14).build());
                }
            }
            case MUSIC -> {
                String names = "CDEFGAB";
                for (int i = 0; i < 15; i++) {
                    int note = i;
                    addRenderableWidget(Button.builder(Component.literal(String.valueOf(names.charAt(i % 7))), b -> playNote(note))
                            .bounds(x + 8 + i * 16, y + 70, 15, 40).build());
                }
            }
            default -> {
            }
        }
    }

    private void back() {
        open = null;
        init();
    }

    // ---- Apps ----

    private void calcKey(char k) {
        if (Character.isDigit(k)) {
            calc = calcFresh || calc.equals("0") ? String.valueOf(k) : calc + k;
            calcFresh = false;
            return;
        }
        double v = Double.parseDouble(calc);
        if (k == 'C') { calc = "0"; calcAcc = 0; calcOp = 0; calcFresh = true; return; }
        if (calcOp != 0) v = switch (calcOp) {
            case '+' -> calcAcc + v;
            case '-' -> calcAcc - v;
            case '*' -> calcAcc * v;
            case '/' -> v == 0 ? Double.NaN : calcAcc / v;
            default -> v;
        };
        calc = Double.isNaN(v) ? "Error" : v == Math.rint(v) && Math.abs(v) < 1e12 ? String.valueOf((long) v) : String.valueOf(v);
        calcAcc = Double.isNaN(v) ? 0 : v;
        calcOp = k == '=' ? 0 : k;
        calcFresh = true;
        if (calc.equals("Error")) calc = "0";
    }

    private void runCommand(String line) {
        terminal.add("$ " + line);
        String[] w = line.trim().split("\\s+", 2);
        String user = minecraft.player == null ? "player" : minecraft.player.getGameProfile().getName();
        switch (w[0]) {
            case "" -> { }
            case "help" -> terminal.add("help ls date whoami echo apps neofetch clear");
            case "ls" -> terminal.add("Desktop  Documents  Music  notes.txt  drawing.png");
            case "date" -> terminal.add(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")));
            case "whoami" -> terminal.add(user);
            case "echo" -> terminal.add(w.length > 1 ? w[1] : "");
            case "clear" -> terminal.clear();
            case "apps" -> {
                StringBuilder sb = new StringBuilder();
                for (Tech.App a : Tech.App.values()) if (has(a)) sb.append(a.name().toLowerCase()).append("  ");
                terminal.add(sb.toString().trim());
            }
            case "neofetch" -> {
                terminal.add(user + "@" + Tech.OSES[os]);
                terminal.add("OS: " + title.getString());
                terminal.add("RAM: lots (the AI wants it)");
            }
            default -> terminal.add(Component.translatable("realism.term.unknown", w[0]).getString());
        }
        while (terminal.size() > 11) terminal.remove(0);
    }

    private void playNote(int note) {
        float pitch = (float) Math.pow(2, (new int[]{0, 2, 4, 5, 7, 9, 11}[note % 7] + 12 * (note / 7) - 12) / 12.0);
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), Math.min(2f, Math.max(0.5f, pitch))));
    }

    private void resetSnake() {
        snake.clear();
        snake.add(new int[]{5, 7});
        snake.add(new int[]{4, 7});
        dx = 1; dy = 0; score = 0; snakeDead = false;
        placeFood();
    }

    private void placeFood() {
        foodX = rnd.nextInt(SW);
        foodY = rnd.nextInt(SH);
    }

    @Override
    public void tick() {
        if (open != Tech.App.SNAKE || snakeDead || ++snakeTick % 3 != 0) return;
        int[] head = snake.get(0);
        int nx = head[0] + dx, ny = head[1] + dy;
        boolean hit = nx < 0 || ny < 0 || nx >= SW || ny >= SH;
        for (int[] s : snake) if (s[0] == nx && s[1] == ny) hit = true;
        if (hit) {
            snakeDead = true;
            // A decent game cheers you up (the server decides, once a minute).
            if (score >= 3) PacketDistributor.sendToServer(new Tech.PlayGame());
            return;
        }
        snake.add(0, new int[]{nx, ny});
        if (nx == foodX && ny == foodY) {
            score++;
            placeFood();
        } else snake.remove(snake.size() - 1);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (open == Tech.App.SNAKE) {
            if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) && dy == 0) { dx = 0; dy = -1; return true; }
            if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) && dy == 0) { dx = 0; dy = 1; return true; }
            if ((key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) && dx == 0) { dx = -1; dy = 0; return true; }
            if ((key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) && dx == 0) { dx = 1; dy = 0; return true; }
            if (key == GLFW.GLFW_KEY_SPACE && snakeDead) { resetSnake(); return true; }
        }
        if (open == Tech.App.TERMINAL && terminalInput != null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            runCommand(terminalInput.getValue());
            terminalInput.setValue("");
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && open != null) {
            back();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (open == Tech.App.PAINT && paintAt(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
        if (open == Tech.App.PAINT && paintAt(mx, my)) return true;
        return super.mouseDragged(mx, my, button, ddx, ddy);
    }

    private boolean paintAt(double mx, double my) {
        int px = (int) ((mx - wx() - 50) / 9), py = (int) ((my - wy() - 18) / 9);
        if (mx < wx() + 50 || my < wy() + 18 || px >= 16 || py >= 16) return false;
        paint[py * 16 + px] = (byte) paintColor;
        return true;
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new Tech.SaveComputer(computer.pos(), notes, paint));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Drawing ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (open == null) return;
        int x = wx(), y = wy();
        switch (open) {
            case CALC -> {
                g.fill(x + 70, y + 20, x + 186, y + 40, 0xFF102010);
                g.drawString(font, calc, x + 182 - font.width(calc), y + 26, 0xFF8FF08F, false);
            }
            case TERMINAL -> {
                g.fill(x + 4, y + 16, x + 256, y + 136, 0xFF0C0C0C);
                for (int i = 0; i < terminal.size(); i++) g.drawString(font, terminal.get(i), x + 8, y + 20 + i * 10, 0xFF8FD18F, false);
            }
            case PAINT -> {
                for (int i = 0; i < 256; i++) {
                    int px = x + 50 + (i % 16) * 9, py = y + 18 + (i / 16) * 9;
                    g.fill(px, py, px + 9, py + 9, PALETTE[Math.floorMod(paint[i], PALETTE.length)]);
                }
                for (int c = 0; c < PALETTE.length; c++) g.fill(x + 218, y + 18 + c * 17, x + 232, y + 32 + c * 17, PALETTE[c]);
            }
            case SNAKE -> {
                int ox = x + 10, oy = y + 18, cell = 12;
                g.fill(ox, oy, ox + SW * cell, oy + SH * cell, 0xFF1D3B1D);
                g.fill(ox + foodX * cell + 2, oy + foodY * cell + 2, ox + foodX * cell + cell - 2, oy + foodY * cell + cell - 2, 0xFFE53935);
                for (int[] s : snake) g.fill(ox + s[0] * cell + 1, oy + s[1] * cell + 1, ox + s[0] * cell + cell - 1, oy + s[1] * cell + cell - 1, 0xFF7CE07C);
                g.drawString(font, Component.translatable("realism.snake.score", score), ox, oy - 12, 0xFFFFFFFF);
                if (snakeDead) g.drawCenteredString(font, Component.translatable("realism.snake.over"), ox + SW * cell / 2, oy + SH * cell / 2, 0xFFFFFFFF);
            }
            case MUSIC -> g.drawCenteredString(font, Component.translatable("realism.music.hint"), x + 130, y + 40, 0xFFFFFFFF);
            default -> {
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int w = width, h = height, cx = w / 2, cy = h / 2;
        switch (Tech.OSES[os]) {
            case "classic_mac" -> {
                // A flat grey desktop (a dotted pattern drawn pixel by pixel was tens of thousands of fills per frame: lag).
                g.fill(0, 0, w, h, 0xFF9A9A9A);
                g.fill(0, 0, w, 12, 0xFFFFFFFF);
                g.fill(0, 12, w, 13, 0xFF000000);
            }
            case "mac_x" -> {
                g.fillGradient(0, 0, w, h, 0xFF1E4FA8, 0xFF7FB4F0);
                g.fill(0, 0, w, 11, 0xCCF2F2F2);
                g.fill(cx - 90, h - 22, cx + 90, h - 4, 0x99FFFFFF);
                int[] icons = {0xFF3D8BF0, 0xFF46C06A, 0xFFF0B13D, 0xFFE0524F, 0xFF9A6BE0};
                for (int i = 0; i < icons.length; i++) g.fill(cx - 80 + i * 34, h - 20, cx - 66 + i * 34, h - 6, icons[i]);
            }
            case "be" -> g.fill(0, 0, w, h, 0xFF336698);
            case "next" -> {
                g.fill(0, 0, w, h, 0xFF505050);
                for (int i = 0; i < 5; i++) g.fill(w - 26, 6 + i * 26, w - 4, 28 + i * 26, 0xFF2A2A2A);
            }
            case "windows" -> {
                g.fill(0, 0, w, h, 0xFF008080);
                g.fill(0, h - 16, w, h, 0xFFC0C0C0);
                g.fill(2, h - 14, 46, h - 2, 0xFFD4D0C8);
                g.drawString(font, Component.translatable("realism.pc.menu"), 6, h - 12, 0xFF000000, false);
            }
            default -> {
                g.fill(0, 0, w, h, 0xFF1B1F27);
                g.fill(0, 0, w, 10, 0xFF2E3440);
            }
        }
        // The window everything sits in, in the OS's colors.
        int[][] look = {{0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000}, {0xFFECECEC, 0xFFB8B8B8, 0xFFE8E8E8, 0xFF333333},
                {0xFFDDDDDD, 0xFF888888, 0xFFFFCB08, 0xFF000000}, {0xFFAAAAAA, 0xFF000000, 0xFF000000, 0xFFFFFFFF},
                {0xFFC0C0C0, 0xFF808080, 0xFF000080, 0xFFFFFFFF}, {0xFF3B4252, 0xFF2E3440, 0xFF434C5E, 0xFFECEFF4}};
        int[] c = look[os];
        int x = wx(), y = wy();
        g.fill(x - 1, y - 1, x + 261, y + 161, c[1]);
        g.fill(x, y, x + 260, y + 160, c[0]);
        g.fill(x, y, x + 260, y + 14, c[2]);
        Component t = open == null ? title : Component.translatable("realism.app." + open.name().toLowerCase());
        g.drawCenteredString(font, t, x + 130, y + 3, c[3]);
    }
}
