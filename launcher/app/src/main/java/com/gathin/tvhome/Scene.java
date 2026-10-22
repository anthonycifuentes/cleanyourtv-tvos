package com.gathin.tvhome;

/** A screensaver scene: ClockScene (clock on black) or RestScene (the tvOS shell wallpapers). */
interface Scene {
    void start();

    void stop();
}
