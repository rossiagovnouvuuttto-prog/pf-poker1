package com.mod.overlay;

import android.graphics.PixelFormat;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RootOverlay {
    private static native void nativeRun(Surface surface, int width, int height, int pid);
    private static native void nativeStop();
    private static Object control;
    private static Surface surface;

    private static void hiddenApiAccess() throws Exception {
        Class<?> cls = Class.forName("dalvik.system.VMRuntime");
        Object runtime = cls.getDeclaredMethod("getRuntime").invoke(null);
        cls.getDeclaredMethod("setHiddenApiExemptions", String[].class)
            .invoke(runtime, (Object)new String[]{"L"});
    }

    private static int[] displaySize() throws Exception {
        java.lang.Process wm = new ProcessBuilder("/system/bin/wm", "size")
            .redirectErrorStream(true).start();
        String lastSize = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(wm.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null)
                if (line.contains("size:")) lastSize = line;
        }
        if (wm.waitFor() != 0 || lastSize == null)
            throw new IllegalStateException("Cannot query display size");
        Matcher matcher = Pattern.compile("(\\d+)x(\\d+)").matcher(lastSize);
        if (!matcher.find()) throw new IllegalStateException("Invalid display size");
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw new IllegalStateException("Invalid display dimensions");
        return new int[]{width, height};
    }

    private static void createSurface(int width, int height) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Class<?> bc = Class.forName("android.view.SurfaceControl$Builder");
        Constructor<?> ctor = bc.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object builder = ctor.newInstance();
        bc.getMethod("setName", String.class).invoke(builder, "OverlayMod");
        bc.getMethod("setBufferSize", int.class, int.class).invoke(builder, width, height);
        bc.getMethod("setFormat", int.class).invoke(builder, PixelFormat.RGBA_8888);
        control = bc.getMethod("build").invoke(builder);

        Class<?> tc = Class.forName("android.view.SurfaceControl$Transaction");
        Object transaction = tc.getConstructor().newInstance();
        try {
            tc.getMethod("setLayer", sc, int.class)
                .invoke(transaction, control, Integer.MAX_VALUE - 100);
            tc.getMethod("setPosition", sc, float.class, float.class)
                .invoke(transaction, control, 0.0f, 0.0f);
            try {
                tc.getMethod("setLayerStack", sc, int.class)
                    .invoke(transaction, control, 0);
            } catch (NoSuchMethodException ignored) {
                // Older builds use the default stack.
            }
            tc.getMethod("show", sc).invoke(transaction, control);
            tc.getMethod("apply").invoke(transaction);
        } finally {
            tc.getMethod("close").invoke(transaction);
        }
        Constructor<Surface> surfaceConstructor = Surface.class.getDeclaredConstructor();
        surfaceConstructor.setAccessible(true);
        surface = surfaceConstructor.newInstance();
        Method copy = Surface.class.getDeclaredMethod("copyFrom", sc);
        copy.setAccessible(true);
        copy.invoke(surface, control);
        if (!surface.isValid()) throw new IllegalStateException("Invalid native surface");
    }

    private static void releaseSurface() {
        if (surface != null) { surface.release(); surface = null; }
        if (control == null) return;
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Class<?> tc = Class.forName("android.view.SurfaceControl$Transaction");
            Object transaction = tc.getConstructor().newInstance();
            try {
                tc.getMethod("remove", sc).invoke(transaction, control);
                tc.getMethod("apply").invoke(transaction);
            } finally {
                tc.getMethod("close").invoke(transaction);
            }
            sc.getMethod("release").invoke(control);
        } catch (Exception error) {
            System.err.println("Surface cleanup: " + error);
        }
        control = null;
    }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new IllegalStateException("Root UID required");
            if (args.length != 2) throw new IllegalArgumentException("Library and PID required");
            Looper.prepareMainLooper();
            hiddenApiAccess();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method systemMain = at.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            systemMain.invoke(null);
            System.load(args[0]);
            int pid = Integer.parseInt(args[1]);
            if (pid == 0) pid = Process.myPid();
            int[] size = displaySize();
            createSurface(size[0], size[1]);
            Thread input = new Thread(() -> {
                try {
                    new BufferedReader(new InputStreamReader(
                        System.in, StandardCharsets.UTF_8)).readLine();
                } catch (Exception ignored) {
                } finally {
                    nativeStop();
                }
            }, "stop-input");
            input.setDaemon(true);
            input.start();
            System.out.println("Root overlay running; PID=" + pid);
            nativeRun(surface, size[0], size[1], pid);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
        } finally {
            releaseSurface();
        }
        System.exit(0);
    }
}
