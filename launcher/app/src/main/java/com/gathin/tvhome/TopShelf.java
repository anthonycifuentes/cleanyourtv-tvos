package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * The tvOS Top Shelf: the artwork and title an app publishes to the system's preview channels and Continue
 * watching (TvContract.PreviewPrograms and WatchNextPrograms), shown full screen while its dock tile is focused.
 * Needs READ_TV_LISTINGS (build-and-install.sh grants it); without it, or for an app that publishes nothing,
 * there is no entry and the shelf falls back to the app's own softened artwork.
 *
 * Read once per Home start on one background thread; each picture is decoded once, at most 960 pixels wide in
 * RGB_565 (about 1 MB), and only for dock apps.
 */
final class TopShelf {
    private static final String TAG = "TopShelf";
    /** Pictures are decoded at the screen's width (1080p), not below it: a half-size decode looked soft. */
    private static final int MAX_W = 1920;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int CACHE_FILES = 40;
    /** Programs kept per app for the shelf to cycle through, and decoded pictures held at once (~1 MB each). */
    static final int PER_APP = 6;
    private static final int PICTURES_HELD = 4;   // 1920x1080 in RGB_565 is 4 MB each

    /** A program with artwork; an app's list starts with its 16:9 pictures. */
    static final class Program {
        final String title;
        final String art;
        /** The app's own link to this title (TvContract intent_uri), or null. */
        final String intent;

        Program(String title, String art) {
            this(title, art, null);
        }

        Program(String title, String art, String intent) {
            this.title = title;
            this.art = art;
            this.intent = intent;
        }
    }

    /** The app's program at index, or null. */
    Program programAt(String pkg, int index) {
        java.util.List<Program> list = pkg == null ? null : programs.get(pkg);
        return list == null || index < 0 || index >= list.size() ? null : list.get(index);
    }

    /** An app's published link (TvContract intent_uri) as an Intent, or null when it is not one. */
    static android.content.Intent intentOf(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            return uri.startsWith("intent:") ? android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
                    : new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The app's own link to a title, looked up by name among everything the app publishes to the system (preview
     * channels and Continue watching), off the main thread; done gets null when the app does not publish it.
     */
    void findLink(String pkg, String title, java.util.function.Consumer<android.content.Intent> done) {
        String want = normalize(title);
        worker.execute(() -> {
            String found = null;
            String[] cols = {TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                    TvContract.PreviewPrograms.COLUMN_INTENT_URI};
            for (Uri uri : new Uri[]{TvContract.WatchNextPrograms.CONTENT_URI, TvContract.PreviewPrograms.CONTENT_URI}) {
                if (found != null) break;
                try (Cursor c = resolver.query(uri, cols, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        if (pkg.equals(c.getString(0)) && !want.isEmpty() && want.equals(normalize(c.getString(1)))
                                && c.getString(2) != null) {
                            found = c.getString(2);
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    Log.i(TAG, "link lookup failed: " + e);
                }
            }
            android.content.Intent intent = intentOf(found);
            main.post(() -> done.accept(intent));
        });
    }

    /** Lowercase letters and digits only, accents removed: "La Tormenta" and "la tormenta." match. */
    static String normalize(String s) {
        if (s == null) return "";
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return d.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ContentResolver resolver;
    private final android.content.res.Resources res;
    private final File cacheDir;
    /** Each held picture's blurred backdrop (HeroArt), made with it on the worker: a HARDWARE bitmap can't be read. */
    private final Map<Bitmap, Bitmap> blurs = new java.util.WeakHashMap<>();
    private final Map<String, java.util.List<Program>> programs = new HashMap<>();
    /** Decoded pictures by "package#index", least recently used first, so only the last few stay in memory. */
    private final Map<String, Bitmap> pictures = new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > PICTURES_HELD;
        }
    };

    /** config.xml shelf_art: apps whose shelf shows these bundled pictures instead of what the app publishes. */
    private final Map<String, java.util.List<Program>> bundled = new HashMap<>();

    TopShelf(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        res = context.getResources();
        cacheDir = context.getCacheDir();
        for (String item : context.getResources().getStringArray(R.array.shelf_art)) {
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            java.util.List<Program> list = new java.util.ArrayList<>();
            for (String name : item.substring(eq + 1).split("\\|")) {
                int id = context.getResources().getIdentifier(name.trim(), "drawable", context.getPackageName());
                if (id != 0) list.add(new Program(null, ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                        + context.getPackageName() + "/" + id));
            }
            if (!list.isEmpty()) bundled.put(item.substring(0, eq).trim(), list);
        }
    }

    /** Reads the programs in the background, then runs done on the main thread. */
    void refresh(Runnable done) {
        worker.execute(() -> {
            Map<String, java.util.List<Program>> wide = new HashMap<>(), other = new HashMap<>();
            read(TvContract.PreviewPrograms.CONTENT_URI, wide, other);
            read(TvContract.WatchNextPrograms.CONTENT_URI, wide, other);
            Map<String, java.util.List<Program>> found = new HashMap<>();
            for (Map<String, java.util.List<Program>> m : java.util.Arrays.asList(wide, other)) {
                for (Map.Entry<String, java.util.List<Program>> e : m.entrySet()) {
                    java.util.List<Program> list = found.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>());
                    for (Program pr : e.getValue()) {
                        if (list.size() >= PER_APP) break;
                        boolean dup = false;
                        for (Program q : list) dup |= q.art.equals(pr.art);
                        if (!dup) list.add(pr);
                    }
                }
            }
            found.putAll(bundled);
            main.post(() -> {
                programs.clear();
                programs.putAll(found);
                pictures.clear();
                if (done != null) done.run();
            });
        });
    }

    /** The app's first program, or null when it publishes none. */
    Program programFor(String pkg) {
        java.util.List<Program> list = pkg == null ? null : programs.get(pkg);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** How many programs the shelf can cycle through for this app. */
    int programCount(String pkg) {
        java.util.List<Program> list = pkg == null ? null : programs.get(pkg);
        return list == null ? 0 : list.size();
    }

    /** The first program's artwork for this app. */
    void pictureFor(String pkg, BiConsumer<String, Bitmap> done) {
        pictureFor(pkg, 0, done);
    }

    /** The artwork of the app's program at index, from memory or decoded in the background; nothing when it fails. */
    void pictureFor(String pkg, int index, BiConsumer<String, Bitmap> done) {
        java.util.List<Program> list = pkg == null ? null : programs.get(pkg);
        if (list == null || index < 0 || index >= list.size()) return;
        Program p = list.get(index);
        String key = pkg + "#" + index;
        Bitmap cached = pictures.get(key);
        if (cached != null) {
            done.accept(pkg, cached);
            return;
        }
        worker.execute(() -> {
            Bitmap soft = decode(p.art);
            if (soft == null) return;
            // The blur is made here from the decoded pixels, and the picture then moves to the GPU as a HARDWARE
            // bitmap: its 4 MB upload happens on this thread instead of in the first frame that draws it, which is
            // where navigation used to stutter.
            Bitmap blur = HeroArt.from(new android.graphics.drawable.BitmapDrawable(res, soft));
            Bitmap shown = soft.copy(Bitmap.Config.HARDWARE, false);
            if (shown != null) soft.recycle();
            else shown = soft;
            final Bitmap bmp = shown;
            main.post(() -> {
                pictures.put(key, bmp);
                if (blur != null) blurs.put(bmp, blur);
                done.accept(pkg, bmp);
            });
        });
    }

    /**
     * Programs the launcher supplies itself for a package (the Explore tile's TMDB picks), replacing whatever was there;
     * kept across refresh() like the bundled shelf_art.
     */
    void putPrograms(String pkg, java.util.List<Program> list) {
        bundled.put(pkg, list);
        programs.put(pkg, list);
        pictures.keySet().removeIf(k -> k.startsWith(pkg + "#"));
    }

    /** A picture at this address (a title's logo), decoded off the main thread; nothing when it fails. */
    void image(String address, java.util.function.Consumer<Bitmap> done) {
        image(address, true, done);
    }

    /** The same; alpha false decodes a photo in RGB_565 (half the memory). */
    void image(String address, boolean alpha, java.util.function.Consumer<Bitmap> done) {
        worker.execute(() -> {
            Bitmap soft = decode(address, alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            if (soft == null) return;
            Bitmap shown = soft.copy(Bitmap.Config.HARDWARE, false);
            if (shown != null) soft.recycle();
            else shown = soft;
            final Bitmap bmp = shown;
            main.post(() -> done.accept(bmp));
        });
    }

    /** The blurred backdrop made with this picture, or null. */
    Bitmap blurOf(Bitmap picture) {
        return picture == null ? null : blurs.get(picture);
    }

    void shutdown() {
        worker.shutdownNow();
    }

    /** Adds each app's programs with artwork, 16:9 pictures to wide and the rest to other. */
    private void read(Uri uri, Map<String, java.util.List<Program>> wide, Map<String, java.util.List<Program>> other) {
        String[] cols = {TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.COLUMN_INTENT_URI};
        // No selection: without ACCESS_ALL_EPG_DATA one throws a SecurityException (docs/watch-next.md).
        try (Cursor c = resolver.query(uri, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                if (pkg == null) continue;
                String poster = c.getString(2), thumb = c.getString(3);
                boolean posterWide = !c.isNull(4) && c.getInt(4) == TvContract.PreviewPrograms.ASPECT_RATIO_16_9;
                String art = posterWide ? poster : thumb != null ? thumb : poster;
                if (art == null) continue;
                boolean isWide = posterWide || art.equals(thumb);
                java.util.List<Program> list = (isWide ? wide : other).computeIfAbsent(pkg, k -> new java.util.ArrayList<>());
                if (list.size() < PER_APP) list.add(new Program(c.getString(1), art, c.getString(5)));
            }
            Log.i(TAG, uri.getLastPathSegment() + ": " + c.getCount() + " rows, wide art for " + wide.keySet());
        } catch (RuntimeException e) {
            Log.i(TAG, uri.getLastPathSegment() + " unreadable: " + e);
        }
    }

    private Bitmap decode(String art) {
        return decode(art, Bitmap.Config.RGB_565);
    }

    /** RGB_565 for photos (half the memory); ARGB_8888 for logos, whose transparency 565 would lose. */
    private Bitmap decode(String art, Bitmap.Config config) {
        Uri uri = Uri.parse(art);
        String scheme = uri.getScheme();
        try {
            byte[] bytes;
            if ("https".equals(scheme) || "http".equals(scheme)) {
                // Apps publish their artwork as web addresses. It is fetched once, only for a dock app, from the
                // address that app wrote, and kept in the cache so Home works offline after that. YouTube thumbnails
                // (SmartTube publishes the 480x360 hqdefault) are asked for in 1280x720 first.
                bytes = null;
                for (String address : largerSizes(art)) {
                    bytes = cached(address);
                    if (bytes != null) break;
                }
            } else {
                try (InputStream in = resolver.openInputStream(uri)) {
                    bytes = readAll(in);
                }
            }
            if (bytes == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= MAX_W) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = config;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception | OutOfMemoryError e) {
            Log.i(TAG, "art failed: " + e);
            return null;
        }
    }

    private static final java.util.regex.Pattern YT_THUMB = java.util.regex.Pattern.compile(
            "^https?://i\\d?\\.ytimg\\.com/vi(?:_webp)?/([^/]+)/(?:default|mqdefault|hqdefault|sddefault|hq720)(_live)?\\.(?:jpg|webp)(?:\\?.*)?$");

    /**
     * For a YouTube thumbnail, the same picture's larger sizes then the address itself: maxresdefault (1280x720,
     * missing on some videos, which answer 404) and sddefault (640x480). Any other address alone.
     */
    /** HBO Max's image service sizes a picture by its w= parameter; apps ask it for 600 px. */
    private static final java.util.regex.Pattern WIDTH_PARAM = java.util.regex.Pattern.compile("([?&]w=)(\\d+)");
    /** Apple's image service names the size in the path, up to 3840x2160 for the shelf; 1080p is enough. */
    private static final java.util.regex.Pattern MZSTATIC = java.util.regex.Pattern.compile(
            "^(https?://is\\d+-ssl\\.mzstatic\\.com/image/thumb/.+/)(\\d+)x(\\d+)([a-z]*)\\.(jpg|webp|png)$");

    /**
     * The same picture at a size made for a 1080p screen, where the address can say so, then the address itself:
     * YouTube thumbnails (youtubeSizes), HBO Max's w= (to 1920), Apple's WxH path (to 1920x1080).
     */
    static java.util.List<String> largerSizes(String art) {
        java.util.List<String> yt = youtubeSizes(art);
        if (yt.size() > 1) return yt;
        if (art.contains("discomax.com")) {
            java.util.regex.Matcher w = WIDTH_PARAM.matcher(art);
            if (w.find() && Integer.parseInt(w.group(2)) < 1920) {
                return java.util.Arrays.asList(w.replaceFirst("$11920"), art);
            }
        }
        java.util.regex.Matcher m = MZSTATIC.matcher(art);
        if (m.matches() && Integer.parseInt(m.group(2)) != 1920) {
            return java.util.Arrays.asList(m.group(1) + "1920x1080" + m.group(4) + "." + m.group(5), art);
        }
        return java.util.Collections.singletonList(art);
    }

    static java.util.List<String> youtubeSizes(String art) {
        java.util.regex.Matcher m = YT_THUMB.matcher(art);
        if (!m.matches()) return java.util.Collections.singletonList(art);
        String live = m.group(2) == null ? "" : m.group(2);
        String base = "https://i.ytimg.com/vi/" + m.group(1) + "/";
        return java.util.Arrays.asList(base + "maxresdefault" + live + ".jpg", base + "sddefault" + live + ".jpg", art);
    }

    /** The picture at this address from the disk cache, downloading it the first time. */
    private byte[] cached(String address) throws IOException {
        File dir = new File(cacheDir, "topshelf");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, Integer.toHexString(address.hashCode()) + "-" + address.length());
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                return readAll(in);
            }
        }
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(5000);
        http.setReadTimeout(8000);
        http.setInstanceFollowRedirects(true);
        try {
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] bytes;
            try (InputStream in = http.getInputStream()) {
                bytes = readAll(in);
            }
            if (bytes == null) return null;
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) tmp.delete();
            trim(dir);
            return bytes;
        } finally {
            http.disconnect();
        }
    }

    /** Up to MAX_BYTES; null for anything larger, which is not a picture worth a TV's memory. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }

    /** Keeps the newest CACHE_FILES pictures. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= CACHE_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = CACHE_FILES; i < files.length; i++) files[i].delete();
    }
}
