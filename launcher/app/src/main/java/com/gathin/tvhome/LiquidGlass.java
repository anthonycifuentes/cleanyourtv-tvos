package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;

/**
 * Liquid Glass for a panel that sits still over Home (Control Center), on Android 11, which has no RenderEffect or
 * AGSL to refract the screen live. It follows the "Liquid Glass Apple" CodePen recipe (backdrop blur 4px, a lens
 * displacement strongest at the edges, saturate 120% and brightness 115%, and a soft drop shadow) with the dock's
 * smoked fill, and a light border with pink and cyan edge reflections from a second CSS glass recipe, done once instead of per frame: the screen behind is captured
 * when the panel opens, lightly blurred and colour-lifted on the CPU, and each control draws its own part of it.
 */
final class LiquidGlass {

    /** The backdrop is captured at 1/SCALE of the screen: 640x360 on a 1080p TV, under 1 MB. */
    private static final int SCALE = 3;

    private LiquidGlass() {
    }

    /**
     * The window as it is on screen, read back from the GPU at a third of its size (PixelCopy, so HARDWARE bitmaps
     * are included), then lightly blurred (~4 dp), saturated and brightened, and handed to done on the main thread;
     * null when the copy fails, which leaves the controls plain glass.
     */
    static void capture(android.app.Activity activity, View root, java.util.function.Consumer<Bitmap> done) {
        int w = root.getWidth() / SCALE, h = root.getHeight() / SCALE;
        if (w <= 0 || h <= 0) {
            done.accept(null);
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(activity.getWindow(), bmp, result ->
                    done.accept(result == android.view.PixelCopy.SUCCESS ? frost(bmp) : null),
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {   // no surface yet
            done.accept(null);
        }
    }

    private static Bitmap frost(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            px = HeroArt.boxBlur(px, w, h, 1, true);
            px = HeroArt.boxBlur(px, w, h, 1, false);
        }
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(lift(lum + (r - lum) * 1.2f), lift(lum + (g - lum) * 1.2f), lift(lum + (b - lum) * 1.2f));
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static int lift(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 1.15f)));
    }

    /** The soft drop shadow (CodePen: 0 6px 6px black 20%), cast by the system from the control's rounded outline. */
    static void castShadow(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                outline.setAlpha(0.35f);
            }
        });
        v.setElevation(5 * v.getResources().getDisplayMetrics().density);
    }

    /**
     * The glass edge, shared by Control Center's controls and the dock: a 1 dp light border (a 140deg gradient, bright
     * at the top left and again at the bottom right), a line of light along the top and a fainter one along the bottom,
     * and pink and cyan reflections that glow in from the top left and bottom right edges, as a CSS glass recipe at
     * --spec .85 draws them. Gradients are made once per size; drawing is a handful of strokes.
     */
    static final class Edge {
        private final float density;
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lines = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sized = new RectF();
        /**
         * The dock's softer edge: the earlier rim light (bright along the top, fading down) over a faint hairline,
         * with the pink and cyan reflections at under half strength, instead of the full CSS recipe.
         */
        private final boolean subtle;

        Edge(float density) {
            this(density, false);
        }

        Edge(float density, boolean subtle) {
            this.density = density;
            this.subtle = subtle;
            for (Paint p : new Paint[]{border, lines, tint}) p.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(density);
            lines.setStrokeWidth(subtle ? Math.max(1f, 0.5f * density) : density);
            tint.setStrokeWidth(density + 0.5f);
        }

        /** Remakes the gradients when the shape moves or changes size. */
        private void size(RectF b) {
            if (b.equals(sized)) return;
            sized.set(b);
            float spec = 0.85f;
            if (subtle) {
                border.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        new int[]{white(0.22f), white(0.03f), white(0.02f), white(0.07f)},
                        new float[]{0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));
                lines.setShader(null);
                lines.setColor(white(0.09f));
                tint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[]{argb(0.12f, 0xFFB4E6), argb(0f, 0xFFB4E6), argb(0f, 0x8CD7FF), argb(0.12f, 0x8CD7FF)},
                        new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
                return;
            }
            // ::before: linear-gradient(140deg) of white 45% > 7% at 28% > 2% at 55% > 22% at 100%.
            border.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{white(0.45f * spec), white(0.07f * spec), white(0.02f), white(0.22f * spec)},
                    new float[]{0f, 0.28f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            // inset 0 1px .5px white 32% and inset 0 -1px .5px white 9%. The band of light at each end stays about
            // 6 dp tall whatever the shape's height.
            float end = Math.min(0.3f, 6 * density / Math.max(1f, b.height()));
            lines.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{white(0.32f * spec), white(0f), white(0f), white(0.09f * spec)},
                    new float[]{0f, end, 1f - end, 1f}, Shader.TileMode.CLAMP));
            // inset 2px 3px 2px -2px pink 30% and inset -2px -3px 2px -2px cyan 30%, a little stronger than the CSS so
            // it shows on a TV across the room.
            tint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{argb(0.55f * spec, 0xFFB4E6), argb(0.12f, 0xFFB4E6), argb(0f, 0xFFFFFF),
                            argb(0.12f, 0x8CD7FF), argb(0.55f * spec, 0x8CD7FF)},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        }

        void draw(Canvas canvas, RectF r, float radius) {
            size(r);
            // The colour reflections: a glow that enters ~4 dp from the edge and fades, as the CSS inset shadows'
            // blur does, so it reads as light inside the glass rather than as another outline.
            int rings = subtle ? 3 : 4;
            for (int i = 0; i < rings; i++) {
                float t = 1f - (float) i / rings;
                tint.setAlpha(Math.round(255 * t * t));
                inset(canvas, r, radius, (i + 0.5f) * density, tint);
            }
            tint.setAlpha(255);
            // One contour: the light border and the top and bottom inner lines share the edge. Subtle: a faint
            // hairline at the edge and the rim light just inside it.
            inset(canvas, r, radius, (subtle ? 0.25f : 0.5f) * density, lines);
            inset(canvas, r, radius, (subtle ? 1f : 0.5f) * density, border);
        }

        /** A stroke whose centre runs this far inside the edge, with the corner radius following it in. */
        private static void inset(Canvas canvas, RectF r, float radius, float in, Paint paint) {
            canvas.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in,
                    Math.max(0, radius - in), Math.max(0, radius - in), paint);
        }

        private static int white(float alpha) {
            return argb(alpha, 0xFFFFFF);
        }

        private static int argb(float alpha, int rgb) {
            return Math.round(Math.max(0f, Math.min(1f, alpha)) * 255) << 24 | rgb;
        }
    }

    /** The glass for one control. */
    static final class GlassDrawable extends Drawable {
        private final Bitmap backdrop;
        private final View owner;
        private final float radius, density;
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Edge edge;
        private final Matrix matrix = new Matrix(), lensMatrix = new Matrix();
        private final RectF r = new RectF();
        private final Path ring = new Path();
        private final int[] at = new int[2];

        GlassDrawable(Bitmap backdrop, View owner, float radius, boolean light) {
            this.backdrop = backdrop;
            this.owner = owner;
            this.radius = radius;
            density = owner.getResources().getDisplayMetrics().density;
            // The same subtle edge as the dock.
            edge = new Edge(density, true);
            if (backdrop != null) {
                base.setShader(new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                lens.setShader(new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            // Dark: the dock band's #1E1E1E at 50%, so the controls read as the same smoked glass as the dock;
            // light: white at 25%, as the CodePen recipe has it.
            // Light (tvOS's light glass): a faint white veil, so the backdrop keeps its colours and white text reads.
            overlay.setColor(light ? 0x1AFFFFFF : 0x801E1E1E);
            // Light: the backdrop seen through the glass is dimmed first, as tvOS dims the screen under Control
            // Center, so white labels read even over a bright picture.
            shade.setColor(light ? 0x52000000 : 0);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            // The lens band: the outer 9 dp, where the CodePen's displacement (blurred alpha) bends the backdrop most.
            float band = Math.min(9 * density, Math.min(b.width(), b.height()) / 4f);
            ring.reset();
            ring.setFillType(Path.FillType.EVEN_ODD);
            ring.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
            ring.addRoundRect(new RectF(b.left + band, b.top + band, b.right - band, b.bottom - band),
                    Math.max(0, radius - band), Math.max(0, radius - band), Path.Direction.CW);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            r.set(b);
            if (backdrop != null) {
                owner.getLocationOnScreen(at);
                // The backdrop pixel under each point of the control: screen position, a third of the size.
                matrix.setScale(SCALE, SCALE);
                matrix.postTranslate(-at[0], -at[1]);
                matrix.postScale(1f / owner.getScaleX(), 1f / owner.getScaleY());
                base.getShader().setLocalMatrix(matrix);
                canvas.drawRoundRect(r, radius, radius, base);
                // At the edge the glass bends the light: the band shows the backdrop from a little further out, drawn
                // squeezed towards the middle, so lines crossing the edge kink as they do through a lens.
                lensMatrix.set(matrix);
                lensMatrix.postScale(0.9f, 0.9f, r.centerX(), r.centerY());
                lens.getShader().setLocalMatrix(lensMatrix);
                canvas.drawPath(ring, lens);
            }
            if (shade.getColor() != 0) canvas.drawRoundRect(r, radius, radius, shade);
            canvas.drawRoundRect(r, radius, radius, overlay);
            edge.draw(canvas, r, radius);
        }

        @Override public void setAlpha(int alpha) { }

        @Override public void setColorFilter(ColorFilter cf) { }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
