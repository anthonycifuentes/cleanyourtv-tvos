package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tvOS shell's Explore shelf: what is trending this week and new in the last weeks, only on the streaming apps this
 * TV has, from TMDB (themoviedb.org, which knows what streams on which service in each country). Three picks an hour
 * per tab (Explore, Movies, Shows), the tab's list rotating with the hour.
 *
 * Uses your own TMDB key (launcher/tmdb.properties, built into BuildConfig; empty turns Explore off). Fetched at most
 * twice a day (REFRESH_MS) on one background thread and kept in the cache directory, so Home shows the last picks offline.
 */
final class TmdbPicks {

    static final int EXPLORE = 0, MOVIES = 1, SHOWS = 2;
    private static final String TAG = "TmdbPicks";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final int PER_KIND = 12, PER_TAB = 4;
    private static final long HOUR_MS = 3_600_000L;
    /**
     * How old the cached picks may get before TMDB is asked again. The hourly change of picks is local (picks() walks
     * the cached lists by the hour), and TMDB's weekly trending moves about once a day, so twice a day is plenty: one
     * fetch is ~2 list calls plus one call per title checked, 30-60 requests.
     */
    private static final long REFRESH_MS = 12 * HOUR_MS;

    /**
     * TMDB provider id to the TV app that plays it. Star+ is part of Disney+ in Latin America now; Prime Video's id
     * also covers its channels' titles only when they are on Prime itself.
     */
    private static final Map<Integer, String[]> PROVIDERS = new LinkedHashMap<>();
    static {
        PROVIDERS.put(8, new String[]{"com.netflix.ninja", "Netflix"});
        PROVIDERS.put(1899, new String[]{"com.wbd.stream", "HBO Max"});
        PROVIDERS.put(384, new String[]{"com.wbd.stream", "HBO Max"});
        PROVIDERS.put(350, new String[]{"com.apple.atve.androidtv.appletv", "Apple TV"});
        PROVIDERS.put(337, new String[]{"com.disney.disneyplus", "Disney+"});
        PROVIDERS.put(119, new String[]{"com.amazon.amazonvideo.livingroom", "Prime Video"});
        PROVIDERS.put(9, new String[]{"com.amazon.amazonvideo.livingroom", "Prime Video"});
        PROVIDERS.put(531, new String[]{"com.cbs.ott", "Paramount+"});
    }

    /** One title: what the shelf shows and which app opens it. */
    static final class Pick {
        final boolean movie;
        final String title, year, overview, app, appName, backdrop, logo, poster;

        Pick(boolean movie, String title, String year, String overview, String app, String appName, String backdrop,
             String logo, String poster) {
            this.movie = movie;
            this.title = title;
            this.year = year;
            this.overview = overview;
            this.app = app;
            this.appName = appName;
            this.backdrop = backdrop;
            this.logo = logo;
            this.poster = poster;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("movie", movie).put("title", title).put("year", year).put("overview", overview)
                    .put("app", app).put("appName", appName).put("backdrop", backdrop).put("logo", logo == null ? "" : logo)
                    .put("poster", poster == null ? "" : poster);
        }

        static Pick from(JSONObject o) {
            String logo = o.optString("logo"), poster = o.optString("poster");
            return new Pick(o.optBoolean("movie"), o.optString("title"), o.optString("year"), o.optString("overview"),
                    o.optString("app"), o.optString("appName"), o.optString("backdrop"), logo.isEmpty() ? null : logo,
                    poster.isEmpty() ? null : poster);
        }
    }

    private final Context context;
    private final File cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Pick> movies = Collections.emptyList(), shows = Collections.emptyList();
    private long fetchedAt;
    private boolean busy;

    TmdbPicks(Context context) {
        this.context = context.getApplicationContext();
        cache = new File(context.getCacheDir(), "explore2.json");   // 2: picks carry a poster
    }

    static boolean enabled() {
        return !BuildConfig.TMDB_TOKEN.isEmpty();
    }

    boolean hasPicks() {
        return !movies.isEmpty() || !shows.isEmpty();
    }

    /** This hour's picks for a tab: four movies, four shows, or for Explore two of each, alternating. */
    List<Pick> picks(int tab) {
        long hour = System.currentTimeMillis() / HOUR_MS;
        if (tab == MOVIES) return window(movies, hour, PER_TAB);
        if (tab == SHOWS) return window(shows, hour, PER_TAB);
        List<Pick> s = window(shows, hour + 1, 2), m = window(movies, hour + 1, 2);
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (i < s.size()) out.add(s.get(i));
            if (i < m.size()) out.add(m.get(i));
        }
        return out;
    }

    private static List<Pick> window(List<Pick> all, long hour, int n) {
        if (all.isEmpty()) return Collections.emptyList();
        List<Pick> out = new ArrayList<>();
        int start = (int) ((hour * n) % all.size());
        for (int i = 0; i < Math.min(n, all.size()); i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    /** Loads the cached picks, then fetches new ones when they are REFRESH_MS old; done runs on the main thread. */
    void refresh(Runnable done) {
        if (!enabled() || busy) return;
        busy = true;
        worker.execute(() -> {
            boolean cached = readCache();
            if (cached) main.post(done);
            if (!cached || System.currentTimeMillis() - fetchedAt >= REFRESH_MS) {
                try {
                    fetch();
                    writeCache();
                    main.post(done);
                } catch (IOException | JSONException | RuntimeException e) {
                    Log.w(TAG, "fetch failed: " + e);
                }
            }
            main.post(() -> busy = false);
        });
    }

    void shutdown() {
        worker.shutdownNow();
    }

    // ---- TMDB ---------------------------------------------------------------------------------------------------

    private void fetch() throws IOException, JSONException {
        PackageManager pm = context.getPackageManager();
        StringBuilder ids = new StringBuilder();
        for (Map.Entry<Integer, String[]> e : PROVIDERS.entrySet()) {
            if (installed(pm, e.getValue()[0])) ids.append(ids.length() == 0 ? "" : "|").append(e.getKey());
        }
        if (ids.length() == 0) throw new IOException("none of the TV's streaming apps is known to TMDB");
        List<Pick> m = collect("movie", ids.toString(), pm), t = collect("tv", ids.toString(), pm);
        synchronized (this) {
            movies = m;
            shows = t;
            fetchedAt = System.currentTimeMillis();
        }
    }

    /** Trending this week first, then this season's releases, keeping titles that stream on an installed app. */
    private List<Pick> collect(String kind, String providers, PackageManager pm) throws IOException, JSONException {
        List<Integer> candidates = new ArrayList<>();
        addIds(candidates, get("/trending/" + kind + "/week?language=" + BuildConfig.TMDB_LANGUAGE));
        String dateField = kind.equals("movie") ? "primary_release_date" : "first_air_date";
        String since = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                .format(new java.util.Date(System.currentTimeMillis() - 60L * 24 * HOUR_MS));
        addIds(candidates, get("/discover/" + kind + "?watch_region=" + BuildConfig.TMDB_REGION
                + "&with_watch_providers=" + providers + "&with_watch_monetization_types=flatrate"
                + "&sort_by=popularity.desc&include_adult=false&" + dateField + ".gte=" + since
                + "&language=" + BuildConfig.TMDB_LANGUAGE));
        List<Pick> out = new ArrayList<>();
        for (int id : candidates) {
            if (out.size() >= PER_KIND) break;
            Pick p = details(kind, id, pm);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void addIds(List<Integer> into, JSONObject page) {
        JSONArray results = page.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < results.length(); i++) {
            int id = results.optJSONObject(i).optInt("id");
            if (id > 0 && !into.contains(id)) into.add(id);
        }
    }

    /** A title with its app, backdrop and logo, or null when it does not stream on this TV or lacks a translation. */
    private Pick details(String kind, int id, PackageManager pm) throws IOException, JSONException {
        String lang = BuildConfig.TMDB_LANGUAGE, short2 = lang.length() >= 2 ? lang.substring(0, 2) : lang;
        JSONObject d = get("/" + kind + "/" + id + "?language=" + lang
                + "&append_to_response=watch/providers,images&include_image_language=" + short2 + ",en,null");
        String overview = d.optString("overview").trim();
        String backdrop = d.optString("backdrop_path");
        if (overview.isEmpty() || backdrop.isEmpty() || "null".equals(backdrop)) return null;
        JSONObject region = d.optJSONObject("watch/providers") == null ? null
                : d.getJSONObject("watch/providers").getJSONObject("results").optJSONObject(BuildConfig.TMDB_REGION);
        JSONArray flat = region == null ? null : region.optJSONArray("flatrate");
        if (flat == null) return null;
        String[] app = null;
        for (int i = 0; i < flat.length() && app == null; i++) {
            String[] a = PROVIDERS.get(flat.getJSONObject(i).optInt("provider_id"));
            if (a != null && installed(pm, a[0])) app = a;
        }
        if (app == null) return null;
        boolean movie = kind.equals("movie");
        String title = d.optString(movie ? "title" : "name");
        String date = d.optString(movie ? "release_date" : "first_air_date");
        JSONObject images = d.optJSONObject("images");
        // A backdrop without words (iso_639_1 null) where there is one: the logo is drawn over it.
        String clean = pickImage(images == null ? null : images.optJSONArray("backdrops"), null);
        String logo = pickImage(images == null ? null : images.optJSONArray("logos"), short2);
        if (logo == null) logo = pickImage(images == null ? null : images.optJSONArray("logos"), "en");
        String poster = d.optString("poster_path");
        return new Pick(movie, title, date.length() >= 4 ? date.substring(0, 4) : "", overview, app[0], app[1],
                IMG + "w1280" + (clean != null ? clean : backdrop), logo == null ? null : IMG + "w500" + logo,
                poster.isEmpty() || "null".equals(poster) ? null : IMG + "w342" + poster);
    }

    /** The best-rated image in this language ("null" for none), as a TMDB path. */
    private static String pickImage(JSONArray images, String lang) {
        if (images == null) return null;
        String best = null;
        double score = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            Object iso = im.opt("iso_639_1");
            boolean match = lang == null ? (iso == null || iso == JSONObject.NULL) : lang.equals(iso);
            if (match && im.optDouble("vote_average") > score) {
                score = im.optDouble("vote_average");
                best = im.optString("file_path");
            }
        }
        return best;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection http = (HttpURLConnection) new URL(API + path).openConnection();
        http.setConnectTimeout(6000);
        http.setReadTimeout(10000);
        http.setRequestProperty("Authorization", "Bearer " + BuildConfig.TMDB_TOKEN);
        http.setRequestProperty("Accept", "application/json");
        try {
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("TMDB " + http.getResponseCode() + " for " + path.split("\\?")[0]);
            }
            try (InputStream in = http.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            http.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- cache ----------------------------------------------------------------------------------------------------

    private boolean readCache() {
        if (!cache.isFile()) return false;
        try (InputStream in = new FileInputStream(cache)) {
            JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            List<Pick> m = list(o.getJSONArray("movies")), t = list(o.getJSONArray("shows"));
            synchronized (this) {
                movies = m;
                shows = t;
                fetchedAt = o.getLong("at");
            }
            return !m.isEmpty() || !t.isEmpty();
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private static List<Pick> list(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(Pick.from(a.optJSONObject(i)));
        return out;
    }

    private void writeCache() throws IOException, JSONException {
        JSONArray m = new JSONArray(), t = new JSONArray();
        for (Pick p : movies) m.put(p.json());
        for (Pick p : shows) t.put(p.json());
        File tmp = new File(cache.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("at", fetchedAt).put("movies", m).put("shows", t).toString()
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(cache)) tmp.delete();
    }
}
