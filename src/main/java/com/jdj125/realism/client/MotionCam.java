package com.jdj125.realism.client;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The webcam for motion games, like a motion camera: it only looks at what MOVES. Each frame is shrunk to a 32x24
 * grid of brightness, compared with the last one, and thrown away. Nothing is saved, shown or sent anywhere; the camera
 * only runs while a motion game is open and you turned it on.
 * <p>
 * Out of the motion it reads: your left arm, your right arm, hands up over your head, and which way you lean (mirror
 * view, like a real motion camera: your left is the left of the screen).
 * <p>
 * macOS only for now, using the native capture library from webcam-capture-driver-native by Eduardo Ramos (Apache 2.0).
 */
public final class MotionCam {
    private MotionCam() {
    }

    public static final int W = 32, H = 24;

    /** The native library's functions (AVFoundation). */
    interface Lib extends Library {
        boolean vcavf_initialize();

        int vcavf_has_videocapture_auth();

        void vcavf_ask_videocapture_auth();

        int vcavf_devices_count();

        void vcavf_get_device_name(int device, Pointer buf, int len);

        int vcavf_get_device_formats_count(int device);

        void vcavf_get_device_format(int device, int format, Pointer buf, int len);

        int vcavf_start_capture(int device, int width, int height);

        int vcavf_stop_capture(int device);

        boolean vcavf_has_new_frame(int device);

        int vcavf_frame_width(int device);

        int vcavf_frame_height(int device);

        int vcavf_frame_bytes_per_row(int device);

        boolean vcavf_grab_frame(int device, Pointer buf, int len);
    }

    private static Lib lib;
    private static boolean loadFailed;
    private static volatile Thread thread;
    private static volatile boolean running;
    private static volatile String status = "off";

    /** Motion in each cell (0-255), mirrored; read by the game screen. */
    public static final int[] motion = new int[W * H];
    private static volatile float left, right, up, lean;
    private static volatile long lastFrame;

    public static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac");
    }

    /** "off", "starting", "on", "no camera", "not allowed", or "not on this computer". */
    public static String status() {
        return status;
    }

    public static boolean live() {
        return running && System.currentTimeMillis() - lastFrame < 1000;
    }

    /** Arm swings and hands-up (0 = still, 1+ = lots of movement there). */
    public static float leftArm() {
        return left;
    }

    public static float rightArm() {
        return right;
    }

    public static float handsUp() {
        return up;
    }

    /** Where you are, -1 (left) to 1 (right). */
    public static float lean() {
        return lean;
    }

    private static synchronized Lib lib() {
        if (lib != null || loadFailed) return lib;
        try {
            String arch = System.getProperty("os.arch", "").contains("aarch64") || System.getProperty("os.arch", "").contains("arm")
                    ? "darwin-aarch64" : "darwin-x86-64";
            Path file = Files.createTempFile("realism-videocapture", ".dylib");
            file.toFile().deleteOnExit();
            try (InputStream in = MotionCam.class.getResourceAsStream("/natives/" + arch + "/libvideocapture.dylib")) {
                if (in == null) throw new IllegalStateException("missing native library");
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lib = Native.load(file.toAbsolutePath().toString(), Lib.class);
            if (!lib.vcavf_initialize()) throw new IllegalStateException("camera library didn't start");
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("Realism motion camera unavailable: {}", t.toString());
            loadFailed = true;
            lib = null;
        }
        return lib;
    }

    public static synchronized void start() {
        if (running) return;
        if (!supported()) {
            status = "not on this computer";
            return;
        }
        running = true;
        status = "starting";
        thread = new Thread(MotionCam::run, "Realism motion camera");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        thread = null;
        left = right = up = lean = 0;
        java.util.Arrays.fill(motion, 0);
        if (!status.startsWith("no") && !status.startsWith("not")) status = "off";
    }

    private static final Pattern SIZE = Pattern.compile("(\\d{2,5})\\s*[xX]\\s*(\\d{2,5})");

    private static void run() {
        Lib l = lib();
        if (l == null) {
            status = "not on this computer";
            running = false;
            return;
        }
        int auth = l.vcavf_has_videocapture_auth();
        if (auth == -2) { // not asked yet: macOS shows its own permission pop-up; wait for the answer
            l.vcavf_ask_videocapture_auth();
            for (int i = 0; i < 300 && running && (auth = l.vcavf_has_videocapture_auth()) == -2; i++) sleep(100);
        }
        {
            if (auth != 0) { // 0 = allowed
                status = "not allowed";
                running = false;
                return;
            }
        }
        if (l.vcavf_devices_count() <= 0) {
            status = "no camera";
            running = false;
            return;
        }
        int device = 0, w = 640, h = 480, best = Integer.MAX_VALUE;
        Memory text = new Memory(512);
        for (int f = 0; f < l.vcavf_get_device_formats_count(device); f++) {
            text.clear();
            l.vcavf_get_device_format(device, f, text, 511);
            Matcher m = SIZE.matcher(text.getString(0, StandardCharsets.UTF_8.name()));
            if (!m.find()) continue;
            int fw = Integer.parseInt(m.group(1)), fh = Integer.parseInt(m.group(2));
            int score = Math.abs(fw - 640) + Math.abs(fh - 480); // small and quick is all we need
            if (score < best) {
                best = score;
                w = fw;
                h = fh;
            }
        }
        if (l.vcavf_start_capture(device, w, h) != 0) {
            status = "no camera";
            running = false;
            return;
        }
        status = "on";
        int[] prev = null;
        Memory frame = null;
        float smoothLean = 0;
        try {
            while (running) {
                if (!l.vcavf_has_new_frame(device)) {
                    sleep(15);
                    continue;
                }
                int fw = l.vcavf_frame_width(device), fh = l.vcavf_frame_height(device), row = l.vcavf_frame_bytes_per_row(device);
                long size = (long) row * fh;
                if (fw <= 0 || fh <= 0 || size <= 0) continue;
                if (frame == null || frame.size() != size) frame = new Memory(size);
                if (!l.vcavf_grab_frame(device, frame, (int) size)) continue;
                int[] cur = shrink(frame, fw, fh, row);
                if (prev != null) {
                    float lsum = 0, rsum = 0, usum = 0, total = 0, cx = 0;
                    for (int y = 0; y < H; y++)
                        for (int x = 0; x < W; x++) {
                            int i = y * W + x;
                            int d = Math.abs(cur[i] - prev[i]);
                            d = d < 18 ? 0 : Math.min(255, d * 2); // ignore camera noise
                            int mx = W - 1 - x; // mirror
                            motion[y * W + mx] = d;
                            if (d == 0) continue;
                            total += d;
                            cx += d * mx;
                            if (y < 6 && mx >= 9 && mx <= 22) usum += d;
                            else if (y < 18 && mx < 9) lsum += d;
                            else if (y < 18 && mx > 22) rsum += d;
                        }
                    left = lsum / 4000f;
                    right = rsum / 4000f;
                    up = usum / 3000f;
                    if (total > 3000) smoothLean = smoothLean * 0.8f + 0.2f * ((cx / total) / (W - 1) * 2 - 1);
                    lean = Math.max(-1, Math.min(1, smoothLean * 1.6f));
                }
                prev = cur;
                lastFrame = System.currentTimeMillis();
            }
        } finally {
            l.vcavf_stop_capture(device);
        }
    }

    /** RGB bytes to a 32x24 brightness grid. */
    private static int[] shrink(Pointer frame, int fw, int fh, int row) {
        int[] out = new int[W * H];
        int bpp = Math.max(1, Math.min(4, row / fw));
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int sum = 0, n = 0;
                for (int sy = 0; sy < 3; sy++)
                    for (int sx = 0; sx < 3; sx++) {
                        int px = (x * fw / W) + sx * fw / W / 3, py = (y * fh / H) + sy * fh / H / 3;
                        long o = (long) py * row + (long) px * bpp;
                        int r = frame.getByte(o) & 255, g = frame.getByte(o + Math.min(1, bpp - 1)) & 255, b = frame.getByte(o + Math.min(2, bpp - 1)) & 255;
                        sum += (r * 3 + g * 6 + b) / 10;
                        n++;
                    }
                out[y * W + x] = sum / n;
            }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
