package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * tvOS Control Center, opened from the clock pill: one square tile (Inputs, where tvOS has Power Off), pills with a
 * title and a status line (Wi-Fi, Appearance, Picture, Sound) and a row of round buttons, at tvOS's proportions
 * (a 113 dp square beside 108 x 48 dp pills). Plain Liquid Glass, dark or light with the launcher's appearance, not
 * tinted by the app behind. Focus turns a control white with dark content, as tvOS does. Static drawables, no blur.
 */
final class ControlCenter {

    interface Actions {
        void inputs();
        void wifi();
        void appearance();
        void picture();
        void sound();
        void settings();
        void launcherSettings();
        void screensaver();
        void search();
    }

    private static final int FOCUSED = 0xF5FFFFFF, ON_FOCUSED = 0xFF1E1E1E;

    private final Context context;
    private final float density;
    private final boolean light;
    private final int fill, rim, onGlass, onGlassDim, disc;
    private final Typeface medium;
    /** The screen behind the panel, captured and blurred when it opened (LiquidGlass); null draws plain glass. */
    private final android.graphics.Bitmap backdrop;

    private ControlCenter(Context context, boolean light, android.graphics.Bitmap backdrop) {
        this.context = context;
        this.light = light;
        this.backdrop = backdrop;
        density = context.getResources().getDisplayMetrics().density;
        // Dark: smoked glass as tvOS draws it over a dimmed screen. Light: frosted white with dark content.
        fill = light ? 0x33FFFFFF : 0xA6383839;
        rim = light ? 0x80FFFFFF : 0x40FFFFFF;
        onGlass = 0xFFFFFFFF;
        onGlassDim = light ? 0xD9FFFFFF : 0xB3FFFFFF;
        disc = light ? 0x33FFFFFF : 0x26FFFFFF;
        medium = ResourcesCompat.getFont(context, R.font.outfit_medium);
    }

    /** The panel; its first control (Inputs) is focused when shown. */
    static View build(Context context, boolean light, android.graphics.Bitmap backdrop, CharSequence network,
                      Actions actions) {
        return new ControlCenter(context, light, backdrop).panel(network, actions);
    }

    private View panel(CharSequence network, Actions a) {
        int gap = dp(9), big = dp(113), pillW = dp(124), pillH = dp(48);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        LinearLayout top = row();
        View inputs = square(R.drawable.ic_input, context.getString(R.string.inputs), a::inputs);
        top.addView(inputs, new LinearLayout.LayoutParams(big, big));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(pillW, big);
        colLp.leftMargin = gap;
        top.addView(column, colLp);
        String net = network == null || network.length() == 0 ? context.getString(R.string.wifi) : network.toString();
        column.addView(pill(R.drawable.ic_wifi, context.getString(R.string.wifi), net, 0xFF0A84FF, a::wifi),
                new LinearLayout.LayoutParams(pillW, pillH));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(pillW, pillH);
        second.topMargin = big - 2 * pillH;
        column.addView(pill(light ? R.drawable.ic_sun : R.drawable.ic_moon, context.getString(R.string.cc_appearance),
                context.getString(light ? R.string.cc_light : R.string.cc_dark), 0, a::appearance), second);
        panel.addView(top);

        LinearLayout middle = row();
        middle.addView(pill(R.drawable.ic_picture, context.getString(R.string.quick_picture),
                context.getString(R.string.cc_picture_hint), 0, a::picture), new LinearLayout.LayoutParams(big, pillH));
        LinearLayout.LayoutParams soundLp = new LinearLayout.LayoutParams(pillW, pillH);
        soundLp.leftMargin = gap;
        middle.addView(pill(R.drawable.ic_sound, context.getString(R.string.cc_sound),
                context.getString(R.string.cc_sound_hint), 0, a::sound), soundLp);
        panel.addView(middle, spaced(gap));

        LinearLayout buttons = row();
        int round = dp(48);
        int space = (big + gap + pillW - 4 * round) / 3;
        int[][] specs = {
                {R.drawable.ic_settings, R.string.settings},
                {R.drawable.ic_home_settings, R.string.launcher_settings},
                {R.drawable.ic_moon, R.string.cc_screensaver},
                {R.drawable.ic_search, R.string.cc_search}};
        Runnable[] runs = {a::settings, a::launcherSettings, a::screensaver, a::search};
        for (int i = 0; i < specs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(round, round);
            if (i > 0) lp.leftMargin = space;
            buttons.addView(roundButton(specs[i][0], context.getString(specs[i][1]), runs[i]), lp);
        }
        panel.addView(buttons, spaced(gap));

        inputs.post(inputs::requestFocus);
        return panel;
    }

    private LinearLayout.LayoutParams spaced(int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        return lp;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        return row;
    }

    /** The square control: a large glyph over its name. tvOS puts Power Off here. */
    private View square(int icon, String label, Runnable action) {
        LinearLayout tile = control(dp(24), action);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        ImageView glyph = glyph(icon, 0);
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = text(label, 12, onGlass);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tile.addView(name, lp);
        follow(tile, new ImageView[]{glyph}, new TextView[]{name}, 0);
        return tile;
    }

    /** A pill: an icon (in a disc unless it has its own colour), a title and a status line. */
    private View pill(int icon, String title, String status, int iconColor, Runnable action) {
        LinearLayout pill = control(dp(24), action);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), 0, dp(10), 0);
        ImageView glyph = glyph(icon, iconColor);
        if (iconColor == 0) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(disc);
            glyph.setBackground(d);
        }
        glyph.setPadding(dp(6), dp(6), dp(6), dp(6));
        pill.addView(glyph, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 11.5f, onGlass);
        TextView s = text(status, 10.5f, onGlassDim);
        lines.addView(t);
        lines.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(7);
        pill.addView(lines, lp);
        follow(pill, new ImageView[]{glyph}, new TextView[]{t, s}, iconColor);
        return pill;
    }

    private View roundButton(int icon, String label, Runnable action) {
        LinearLayout button = control(dp(24), action);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        ImageView glyph = glyph(icon, 0);
        button.addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
        follow(button, new ImageView[]{glyph}, new TextView[0], 0);
        return button;
    }

    private LinearLayout control(float radius, Runnable action) {
        LinearLayout v = new LinearLayout(context);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(background(radius, v));
        if (backdrop != null) LiquidGlass.castShadow(v, radius);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    /** White when focused; otherwise glass: the fill, a sheen bright at the top, and a hairline rim. */
    private Drawable background(float radius, View owner) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(FOCUSED);
        focused.setCornerRadius(radius);

        GradientDrawable base = new GradientDrawable();
        base.setColor(fill);
        base.setCornerRadius(radius);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{light ? 0x66FFFFFF : 0x29FFFFFF, 0x00FFFFFF, light ? 0x14FFFFFF : 0x0AFFFFFF});
        sheen.setCornerRadius(radius);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(Color.TRANSPARENT);
        edge.setStroke(Math.max(1, Math.round(density * 0.5f)), rim);
        edge.setCornerRadius(radius);
        Drawable glass = backdrop != null ? new LiquidGlass.GlassDrawable(backdrop, owner, radius, light)
                : new LayerDrawable(new Drawable[]{base, sheen, edge});

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], glass);
        states.setExitFadeDuration(120);
        return states;
    }

    /** Dark content on the white focused control, the glass's own content colour otherwise. */
    private void follow(View control, ImageView[] glyphs, TextView[] texts, int iconColor) {
        int[] rest = new int[texts.length];
        for (int i = 0; i < texts.length; i++) rest[i] = texts[i].getCurrentTextColor();
        control.setOnFocusChangeListener((v, focused) -> {
            int c = iconColor != 0 ? iconColor : focused ? ON_FOCUSED : onGlass;
            for (ImageView g : glyphs) g.setImageTintList(ColorStateList.valueOf(c));
            for (int i = 0; i < texts.length; i++) {
                texts[i].setTextColor(focused ? (i == 0 ? ON_FOCUSED : 0x991E1E1E) : rest[i]);
            }
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(150).start();
        });
    }

    private ImageView glyph(int icon, int color) {
        ImageView v = new ImageView(context);
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImageTintList(ColorStateList.valueOf(color != 0 ? color : onGlass));
        return v;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(medium);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
