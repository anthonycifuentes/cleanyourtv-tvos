package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private Scene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        // tvOS shell: the wallpapers with the date and clock at the top; otherwise the drifting clock on black.
        boolean rest = getResources().getBoolean(R.bool.tvos_shell)
                && RestScene.wallpapers(getResources(), getPackageName()).length > 0;
        setContentView(rest ? R.layout.dream_rest : R.layout.dream_clock);
        scene = rest ? new RestScene(getWindow().getDecorView()) : new ClockScene(getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
