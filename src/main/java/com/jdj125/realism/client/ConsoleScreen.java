package com.jdj125.realism.client;

import com.jdj125.realism.Consoles;
import com.jdj125.realism.RealismConfig;
import com.jdj125.realism.Tech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A console's screen: a game menu, the games, and the credits. Older consoles draw with chunky pixels and fewer
 * colours; newer ones are smooth. Every game is original:
 * <ul>
 * <li>Block Dash: jump over blocks (Space / W / Up).</li>
 * <li>Paddle Duel: first to 5 against the console (W/S or Up/Down).</li>
 * <li>Tangerine Catch: catch the tangerines, dodge the rocks (A/D or Left/Right).</li>
 * <li>Box Punch (motion): punch the boxes that pop up left, right or over your head (arms, or Left/Right/Up).</li>
 * <li>Wall Dodge (motion): lean through the hole in the wall (head and body, or A/D).</li>
 * </ul>
 */
public class ConsoleScreen extends Screen {
    enum Game { RUNNER, PADDLE, CATCH, PUNCH, DODGE }

    static final int GW = 200, GH = 120; // the game's own little screen

    private final Consoles.OpenConsole console;
    private final Consoles.Model model;
    private final RandomSource random = RandomSource.create();
    private Game game; // null = menu
    private boolean credits, over, won, rewarded;
    private int score, lives, tick;
    private final boolean[] keys = new boolean[512];

    // Block Dash
    private float y, vy;
    private final List<float[]> things = new ArrayList<>(); // x, y, kind (and speed)
    // Paddle Duel
    private float me, cpu, bx, by, bvx, bvy;
    private int cpuScore;
    // Tangerine Catch
    private float basket;
    // Box Punch
    private int target = -1, targetTime, timeLeft;
    private int punchCooldown;
    // Wall Dodge
    private float wallX;
    private int gap, lane;

    public ConsoleScreen(Consoles.OpenConsole console) {
        super(Component.translatable("block.realism." + console.info().id()));
        this.console = console;
        this.model = console.info();
    }

    public static void open(Consoles.OpenConsole console) {
        Minecraft.getInstance().setScreen(new ConsoleScreen(console));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int px() {
        return model.year() < 1998 ? 4 : model.year() < 2008 ? 2 : 1;
    }

    private int left() {
        return (width - GW * scale()) / 2;
    }

    private int top() {
        return (height - GH * scale()) / 2 - 6;
    }

    private int scale() {
        return Math.max(1, Math.min((width - 20) / GW, (height - 60) / GH));
    }

    private boolean motionGames() {
        return console.camera();
    }

    @Override
    protected void init() {
        clearWidgets();
        if (game != null) {
            addRenderableWidget(Button.builder(Component.translatable("realism.consoles.menu"), b -> toMenu())
                    .bounds(width / 2 - 40, height - 24, 80, 20).build());
            if (game == Game.PUNCH || game == Game.DODGE) webcamButton(width / 2 + 44, height - 24);
            return;
        }
        if (credits) {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                credits = false;
                init();
            }).bounds(width / 2 - 40, height - 30, 80, 20).build());
            return;
        }
        List<Game> games = new ArrayList<>(List.of(Game.RUNNER, Game.PADDLE));
        if (model.year() >= 2000) games.add(Game.CATCH);
        if (motionGames()) {
            games.add(Game.PUNCH);
            games.add(Game.DODGE);
        }
        int y0 = height / 2 - games.size() * 12;
        for (int i = 0; i < games.size(); i++) {
            Game g = games.get(i);
            addRenderableWidget(Button.builder(Component.translatable("realism.consoles.game." + g.name().toLowerCase()), b -> start(g))
                    .bounds(width / 2 - 70, y0 + i * 24, 140, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("realism.consoles.credits"), b -> {
            credits = true;
            init();
        }).bounds(width / 2 - 70, y0 + games.size() * 24 + 6, 68, 20).build());
        if (motionGames()) webcamButton(width / 2 + 2, y0 + games.size() * 24 + 6);
    }

    private void webcamButton(int x, int y) {
        addRenderableWidget(Button.builder(webcamLabel(), b -> {
            boolean on = !webcamOn();
            RealismConfig.MOTION_WEBCAM.set(on);
            RealismConfig.MOTION_WEBCAM.save();
            if (on && game != null) MotionCam.start();
            if (!on) MotionCam.stop();
            b.setMessage(webcamLabel());
        }).bounds(x, y, 68, 20).build());
    }

    private static boolean webcamOn() {
        try {
            return RealismConfig.MOTION_WEBCAM.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Component webcamLabel() {
        return Component.translatable(webcamOn() ? "realism.consoles.webcam_on" : "realism.consoles.webcam_off");
    }

    private void toMenu() {
        game = null;
        MotionCam.stop();
        init();
    }

    @Override
    public void removed() {
        MotionCam.stop();
        super.removed();
    }

    private void start(Game g) {
        game = g;
        over = won = rewarded = false;
        score = 0;
        lives = 3;
        tick = 0;
        things.clear();
        y = 0;
        vy = 0;
        me = cpu = GH / 2f;
        cpuScore = 0;
        serve(1);
        basket = GW / 2f;
        target = -1;
        targetTime = 0;
        timeLeft = 30 * 20;
        wallX = GW + 20;
        gap = 1;
        lane = 1;
        if ((g == Game.PUNCH || g == Game.DODGE) && webcamOn()) MotionCam.start();
        init();
    }

    private void serve(int dir) {
        bx = GW / 2f;
        by = GH / 2f;
        bvx = 2.2f * dir;
        bvy = random.nextFloat() * 2 - 1;
    }

    private void beep(float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), pitch, 0.4f));
    }

    private void finish(boolean good) {
        over = true;
        won = good;
        beep(good ? 2.0f : 0.5f);
        if (good && !rewarded) {
            rewarded = true;
            PacketDistributor.sendToServer(new Tech.PlayGame()); // fun: +mood (once a minute)
        }
    }

    private boolean down(int... ks) {
        for (int k : ks) if (k >= 0 && k < keys.length && keys[k]) return true;
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= 0 && key < keys.length) keys[key] = true;
        if (game != null && over && (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_R)) {
            start(game);
            return true;
        }
        if (game == Game.RUNNER && !over && (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP) && y <= 0) {
            vy = 5.2f;
            beep(1.5f);
        }
        if (game == Game.PUNCH && !over) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) punch(0);
            if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) punch(1);
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) punch(2);
        }
        if (game == Game.DODGE && !over && !MotionCam.live()) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) lane = Math.max(0, lane - 1);
            if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) lane = Math.min(2, lane + 1);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && game != null) {
            toMenu();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key >= 0 && key < keys.length) keys[key] = false;
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public void tick() {
        if (game == null || over) return;
        tick++;
        switch (game) {
            case RUNNER -> tickRunner();
            case PADDLE -> tickPaddle();
            case CATCH -> tickCatch();
            case PUNCH -> tickPunch();
            case DODGE -> tickDodge();
        }
    }

    private void tickRunner() {
        vy -= 0.45f;
        y = Math.max(0, y + vy);
        if (y == 0) vy = 0;
        float speed = 2.5f + tick / 400f;
        if (tick % Math.max(18, 45 - tick / 60) == 0 && random.nextFloat() < 0.8f)
            things.add(new float[]{GW + 4, 0, random.nextInt(3)});
        for (var it = things.iterator(); it.hasNext(); ) {
            float[] t = it.next();
            t[0] -= speed;
            float h = 8 + t[2] * 4;
            if (t[0] < 34 && t[0] + 8 > 26 && y < h) {
                finish(score >= 5);
                return;
            }
            if (t[0] < -10) {
                it.remove();
                score++;
            }
        }
    }

    private void tickPaddle() {
        if (down(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP)) me -= 3.5f;
        if (down(GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN)) me += 3.5f;
        me = Math.max(12, Math.min(GH - 12, me));
        cpu += Math.max(-2.2f, Math.min(2.2f, by - cpu));
        cpu = Math.max(12, Math.min(GH - 12, cpu));
        bx += bvx;
        by += bvy;
        if (by < 2 || by > GH - 2) bvy = -bvy;
        if (bx < 12 && bx > 6 && Math.abs(by - me) < 13 && bvx < 0) {
            bvx = -bvx * 1.06f;
            bvy += (by - me) / 6;
            beep(1.2f);
        }
        if (bx > GW - 12 && bx < GW - 6 && Math.abs(by - cpu) < 13 && bvx > 0) {
            bvx = -bvx * 1.04f;
            bvy += (by - cpu) / 6;
            beep(1.0f);
        }
        if (bx < 0) {
            cpuScore++;
            serve(1);
        }
        if (bx > GW) {
            score++;
            serve(-1);
            beep(1.8f);
        }
        if (score >= 5) finish(true);
        else if (cpuScore >= 5) finish(false);
    }

    private void tickCatch() {
        if (down(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT)) basket -= 4;
        if (down(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT)) basket += 4;
        basket = Math.max(12, Math.min(GW - 12, basket));
        if (tick % Math.max(10, 28 - tick / 100) == 0)
            things.add(new float[]{8 + random.nextInt(GW - 16), 0, random.nextFloat() < 0.75f ? 0 : 1, 1.2f + random.nextFloat() + tick / 900f});
        for (var it = things.iterator(); it.hasNext(); ) {
            float[] t = it.next();
            t[1] += t[3];
            if (t[1] > GH - 14 && t[1] < GH - 6 && Math.abs(t[0] - basket) < 13) {
                it.remove();
                if (t[2] == 0) {
                    score++;
                    beep(1.6f);
                } else {
                    lives--;
                    beep(0.6f);
                    if (lives <= 0) {
                        finish(score >= 10);
                        return;
                    }
                }
            } else if (t[1] > GH) it.remove();
        }
    }

    private void punch(int where) {
        if (target == where) {
            score++;
            target = -1;
            targetTime = 8 + random.nextInt(10);
            beep(1.4f + where * 0.2f);
        }
    }

    private void tickPunch() {
        if (--timeLeft <= 0) {
            finish(score >= 10);
            return;
        }
        if (punchCooldown > 0) punchCooldown--;
        if (MotionCam.live() && punchCooldown == 0 && target >= 0) {
            float hit = target == 0 ? MotionCam.leftArm() : target == 1 ? MotionCam.rightArm() : MotionCam.handsUp();
            if (hit > 1.0f) {
                punch(target);
                punchCooldown = 6;
            }
        }
        if (target < 0) {
            if (--targetTime <= 0) {
                target = random.nextInt(3);
                targetTime = Math.max(16, 40 - score * 2);
            }
        } else if (--targetTime <= 0) {
            target = -1;
            targetTime = 10;
        }
    }

    private void tickDodge() {
        if (MotionCam.live()) lane = MotionCam.lean() < -0.33f ? 0 : MotionCam.lean() > 0.33f ? 2 : 1;
        wallX -= 2.0f + score * 0.15f;
        if (wallX < 40 && wallX > 30) {
            if (lane != gap) {
                lives--;
                beep(0.5f);
                wallX = 29;
                if (lives <= 0) {
                    finish(score >= 8);
                    return;
                }
            }
        }
        if (wallX < -10) {
            if (lives > 0) score++;
            wallX = GW + 10;
            gap = random.nextInt(3);
            beep(1.5f);
        }
    }

    // ---- Drawing ----

    /** A rectangle in game units, snapped to this console's pixel size (old consoles are chunky). */
    private void box(GuiGraphics g, float x, float y, float w, float h, int color) {
        int p = px(), s = scale();
        int x0 = Math.round(x / p) * p, y0 = Math.round(y / p) * p, x1 = Math.round((x + w) / p) * p, y1 = Math.round((y + h) / p) * p;
        if (x1 <= x0) x1 = x0 + p;
        if (y1 <= y0) y1 = y0 + p;
        g.fill(left() + x0 * s, top() + y0 * s, left() + x1 * s, top() + y1 * s, color(color));
    }

    /** Old consoles have fewer colours. */
    private int color(int c) {
        if (model.year() >= 2005) return c;
        int step = model.year() < 2000 ? 0x55 : 0x33;
        int r = ((c >> 16) & 255) / step * step, gg = ((c >> 8) & 255) / step * step, b = (c & 255) / step * step;
        return (c & 0xFF000000) | r << 16 | gg << 8 | b;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int s = scale();
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        if (credits) {
            renderCredits(g);
            return;
        }
        if (game == null) {
            g.drawCenteredString(font, Component.translatable("realism.consoles.pick"), width / 2, 20, 0xAAAAAA);
            if (motionGames()) g.drawCenteredString(font, Component.translatable("realism.consoles.webcam_privacy"), width / 2, height - 14, 0x777777);
            return;
        }
        int bg = model.year() < 2000 ? 0xFF102030 : model.year() < 2010 ? 0xFF14203A : 0xFF0E1726;
        g.fill(left() - 2, top() - 2, left() + GW * s + 2, top() + GH * s + 2, 0xFF333333);
        g.fill(left(), top(), left() + GW * s, top() + GH * s, color(bg));
        switch (game) {
            case RUNNER -> drawRunner(g);
            case PADDLE -> drawPaddle(g);
            case CATCH -> drawCatch(g);
            case PUNCH -> drawPunch(g);
            case DODGE -> drawDodge(g);
        }
        String hud = game == Game.PADDLE ? score + " : " + cpuScore
                : Component.translatable("realism.consoles.score", score).getString()
                + (game == Game.CATCH || game == Game.DODGE ? "   " + "❤".repeat(Math.max(0, lives)) : "")
                + (game == Game.PUNCH ? "   " + (timeLeft / 20) + "s" : "");
        g.drawString(font, hud, left() + 4, top() + 4, 0xFFFFFF);
        if ((game == Game.PUNCH || game == Game.DODGE)) drawCamera(g);
        if (over) {
            g.fill(left(), top() + GH * s / 2 - 16, left() + GW * s, top() + GH * s / 2 + 18, 0xC0000000);
            g.drawCenteredString(font, Component.translatable(won ? "realism.consoles.won" : "realism.consoles.lost"), width / 2, top() + GH * s / 2 - 10, won ? 0x55FF55 : 0xFF5555);
            g.drawCenteredString(font, Component.translatable("realism.consoles.again"), width / 2, top() + GH * s / 2 + 4, 0xCCCCCC);
        } else if (tick < 60) {
            g.drawCenteredString(font, Component.translatable("realism.consoles.how." + game.name().toLowerCase()), width / 2, top() + GH * s + 6, 0xCCCCCC);
        }
    }

    private void drawRunner(GuiGraphics g) {
        box(g, 0, GH - 20, GW, 20, 0xFF3D7A2B);
        box(g, 26, GH - 20 - 10 - y, 8, 10, 0xFFFFD34D);
        box(g, 31, GH - 20 - 8 - y, 2, 2, 0xFF000000);
        for (float[] t : things) {
            float h = 8 + t[2] * 4;
            box(g, t[0], GH - 20 - h, 8, h, 0xFF8B5A2B);
        }
    }

    private void drawPaddle(GuiGraphics g) {
        for (int i = 0; i < GH; i += 8) box(g, GW / 2f - 1, i, 2, 4, 0xFF555555);
        box(g, 6, me - 12, 4, 24, 0xFFFFFFFF);
        box(g, GW - 10, cpu - 12, 4, 24, 0xFFFF6666);
        box(g, bx - 2, by - 2, 4, 4, 0xFFFFFF55);
    }

    private void drawCatch(GuiGraphics g) {
        box(g, 0, GH - 6, GW, 6, 0xFF3D7A2B);
        for (float[] t : things) {
            if (t[2] == 0) {
                box(g, t[0] - 4, t[1] - 4, 8, 8, 0xFFFF8A1C); // a tangerine
                box(g, t[0] - 1, t[1] - 6, 3, 2, 0xFF2E8B2E);
            } else box(g, t[0] - 4, t[1] - 4, 8, 8, 0xFF7A7A7A); // a rock
        }
        box(g, basket - 12, GH - 14, 24, 8, 0xFFA0703C);
        box(g, basket - 10, GH - 12, 20, 2, 0xFF6B4523);
    }

    private void drawPunch(GuiGraphics g) {
        float cx = GW / 2f;
        box(g, cx - 5, 50, 10, 10, 0xFFFFD34D); // you
        box(g, cx - 4, 60, 8, 18, 0xFF4D8BFF);
        box(g, cx - 12, 62, 6, 4, 0xFFFFD34D);
        box(g, cx + 6, 62, 6, 4, 0xFFFFD34D);
        int[][] spots = {{30, 55}, {GW - 50, 55}, {(int) cx - 10, 10}};
        for (int i = 0; i < 3; i++) {
            int col = target == i ? 0xFFFF5533 : 0x33FFFFFF;
            box(g, spots[i][0], spots[i][1], 20, 20, col);
        }
    }

    private void drawDodge(GuiGraphics g) {
        float laneW = GW / 3f;
        for (int i = 0; i < 3; i++) box(g, i * laneW + laneW / 2 - 1, GH - 30, 2, 30, 0xFF333344);
        if (wallX < GW && wallX > -10)
            for (int i = 0; i < 3; i++) if (i != gap) box(g, i * laneW + 4, 20 + (GW - wallX) / GW * 40, laneW - 8, 30 + (GW - wallX) / GW * 30, 0xFF9B4DFF);
        box(g, lane * laneW + laneW / 2 - 6, GH - 26, 12, 10, 0xFFFFD34D);
        box(g, lane * laneW + laneW / 2 - 5, GH - 16, 10, 14, 0xFF4D8BFF);
    }

    /** Camera status, and the motion it sees (a tiny grid of what moved, never the picture itself). */
    private void drawCamera(GuiGraphics g) {
        int x = left() + GW * scale() - MotionCam.W * 2 - 4, y = top() + 4;
        if (MotionCam.live()) {
            g.fill(x - 1, y - 1, x + MotionCam.W * 2 + 1, y + MotionCam.H * 2 + 1, 0xFF000000);
            for (int i = 0; i < MotionCam.W * MotionCam.H; i++) {
                int v = MotionCam.motion[i];
                if (v > 0) g.fill(x + (i % MotionCam.W) * 2, y + (i / MotionCam.W) * 2, x + (i % MotionCam.W) * 2 + 2, y + (i / MotionCam.W) * 2 + 2,
                        0xFF000000 | v << 8 | (v / 2));
            }
        } else {
            String key = webcamOn() ? "realism.consoles.cam." + MotionCam.status().replace(' ', '_') : "realism.consoles.cam.keys";
            // Under the game screen, wrapped so it never sticks out of it.
            var lines = font.split(Component.translatable(key), GW * scale());
            for (int i = 0; i < lines.size(); i++)
                g.drawCenteredString(font, lines.get(i), width / 2, top() + GH * scale() + 18 + i * 10, 0xAAAAAA);
        }
    }

    private void renderCredits(GuiGraphics g) {
        int y0 = 26;
        for (int i = 1; i <= 9; i++) {
            g.drawCenteredString(font, Component.translatable("realism.consoles.credit." + i), width / 2, y0, i == 1 ? 0xFFD34D : 0xCCCCCC);
            y0 += i == 1 ? 16 : 12;
        }
    }
}
