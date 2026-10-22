package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tvOS shell's screensaver (layout dream_rest): one of the bundled wallpapers full screen, chosen at random, with
 * the date and a large clock at the top centre. Every minute another wallpaper fades in and the clock shifts a few
 * pixels, so nothing burns in. Each picture is decoded once, off the main thread, in RGB_565 at the screen's size
 * (4 MB at 1080p); at most two are held while one fades over the other.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Control Center's Screensaver button).
 */
final class RestScene implements Scene {

    private static final long CHANGE_MS = 60_000L;
    /** Your own wallpapers: res/drawable-nodpi/rest_*.jpg (git-ignored; see docs/tvos-shell.md). */
    private static final String[] NAMES = {"rest_dark", "rest_sunset", "rest_day", "rest_afternoon", "rest_night",
            "rest_1", "rest_2", "rest_3", "rest_4", "rest_5", "rest_6"};
    private final int[] WALLPAPERS;

    /** The rest_* wallpapers this build has; ClockDream and ClockActivity fall back to the clock scene without any. */
    static int[] wallpapers(Resources res, String pkg) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (String n : NAMES) {
            int id = res.getIdentifier(n, "drawable", pkg);
            if (id != 0) ids.add(id);
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Resources res;
    private final ImageView back, front;
    private final View content;
    private final float density;
    private int current = -1;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showNext(true);
            nudgeClock();
            handler.postDelayed(this, CHANGE_MS);
        }
    };

    /** root is the inflated dream_rest layout. */
    RestScene(View root) {
        res = root.getResources();
        WALLPAPERS = wallpapers(res, root.getContext().getPackageName());
        density = res.getDisplayMetrics().density;
        back = root.findViewById(R.id.restBack);
        front = root.findViewById(R.id.restFront);
        content = root.findViewById(R.id.restContent);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59000000, 0x00000000, 0x00000000});
        root.findViewById(R.id.restShade).setBackground(shade);
    }

    @Override
    public void start() {
        running = true;
        content.setAlpha(0f);
        showNext(false);
        content.animate().alpha(1f).setStartDelay(400).setDuration(1200).start();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, CHANGE_MS);
    }

    @Override
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
        front.animate().cancel();
        decoder.shutdownNow();
    }

    /** Another wallpaper than the one on screen, decoded in the background, then faded in over it. */
    private void showNext(boolean fade) {
        int next = random.nextInt(WALLPAPERS.length);
        if (next == current) next = (next + 1) % WALLPAPERS.length;
        current = next;
        int id = WALLPAPERS[next];
        int w = res.getDisplayMetrics().widthPixels, h = res.getDisplayMetrics().heightPixels;
        if (decoder.isShutdown()) return;
        decoder.execute(() -> {
            Bitmap bmp = decode(id, w, h);
            handler.post(() -> {
                if (!running || bmp == null) return;
                if (!fade) {
                    back.setImageBitmap(bmp);
                    return;
                }
                front.setImageBitmap(bmp);
                front.setAlpha(0f);
                front.animate().alpha(1f).setDuration(2000).withEndAction(() -> {
                    // The new picture moves to the back view, and the old one is let go.
                    back.setImageBitmap(bmp);
                    front.setAlpha(0f);
                    front.setImageDrawable(null);
                }).start();
            });
        });
    }

    private Bitmap decode(int id, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, id, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeResource(res, id, o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** A few dp in any direction from the clock's place, so its pixels never sit still for long. */
    private void nudgeClock() {
        float range = 10 * density;
        content.animate().translationX((random.nextFloat() * 2 - 1) * range)
                .translationY((random.nextFloat() * 2 - 1) * range * 0.6f).setDuration(2000).start();
    }
}
