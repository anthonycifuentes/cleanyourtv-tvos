# tvOS shell (Apple TV look)

An optional home screen mode for the launcher, on by default in this fork (`tvos_shell` in
`launcher/app/src/main/res/values/config.xml`). Built and tested on a Xiaomi MiTV-MOOQ2 (Android TV 11, MediaTek).

| Part | What it does |
|---|---|
| **Top Shelf** | The focused dock app's own artwork (what it publishes to Android's preview channels and Continue watching), full screen, with a slow Ken Burns drift and a new program every 9 s |
| **Shelf focus** | Up from a dock app hides the dock; OK opens the program shown in its app, Left/Right step through the app's programs |
| **Liquid Glass dock** | The shelf's picture blurred behind a smoked glass band with a light rim and pink/cyan edge reflections. Static layers, no live blur (Android 11 has no RenderEffect or AGSL) |
| **Explore** | First dock tile: trending and new titles from TMDB, only those on the streaming apps installed, with Explore / Movies / Shows tabs and a carousel. OK opens the title's link when the app publishes it, else the app's search (Prime Video, HBO Max), else the app |
| **Control Center** | Clock and avatar pill at the top right: Inputs, Wi-Fi, Appearance (dark/light), Picture, Sound, Settings, Launcher settings, Screensaver, Search |
| **Screensaver** | Your wallpapers with the date and a large clock (falls back to the drifting clock without any) |

## Your own TMDB key (Explore)

Explore needs a free TMDB key. Create `launcher/tmdb.properties` (git-ignored):

```properties
tmdb_token=<your TMDB "API Read Access Token">
tmdb_region=US        # two-letter country: which titles stream where
tmdb_language=en-US
```

Without it the build simply has no Explore tile. Picks are fetched at most twice a day (30-60 requests each) and cached
on the TV.

## Your own pictures (git-ignored)

Put these in `launcher/app/src/main/res/drawable-nodpi/`; each is optional and has a built-in fallback.

| File | Used for | Fallback |
|---|---|---|
| `rest_1.jpg` ... `rest_6.jpg` (or `rest_dark`, `rest_day`, ...) | Screensaver wallpapers, 1920x1080 | The clock screensaver |
| `banner_home.png` | The Home/Explore tile, 16:9 | Dark tile with a sparkle |
| `avatar_photo.jpg` | The avatar in the clock pill, square | A generic person icon |
| `shelf_<anything>.jpg` | Top Shelf pictures for an app, listed in `shelf_art` in `config.xml` | The app's own artwork |
| `banner_<anything>.jpg` | A tile's artwork, listed in `app_banners` | The app's own banner |

## Turning it off

Set `tvos_shell` to `false` in `config.xml` and run `launcher/build-and-install.sh`: the original cleanyourtv home
(black, big clock, pills) comes back.
