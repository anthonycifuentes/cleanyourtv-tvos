package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Liquid Glass specular rim just inside the edge: bright along the top, fading towards the bottom. */
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();
    /**
     * tvOS shell Liquid Glass (setGlass): the screen's backdrop, blurred, seen through the band, under the smoked fill
     * and the shared glass edge (LiquidGlass.Edge) instead of the plain rim and hairline.
     */
    private LiquidGlass.Edge glassEdge;
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private Bitmap backdropBitmap;
    private final int[] at = new int[2];

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass) {
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        // tvOS 18 UI Kit dock: Dark Background #1E1E1E under a 40% white hairline.
        band.setColor(translucent ? 0x801E1E1E : (Math.round(opacity * 2.55f) << 24) | 0x1E1E1E);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density * 0.5f));
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x66FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
    }

    /**
     * Space under the dock row's items, in dp. The tvOS shell (setGlass) shows no names under dock icons, so it gives back
     * most of the name's room (negative): the next row sits just under the band and the dock can sit lower.
     */
    float bottomDp = 12;

    /** Draws the band as Liquid Glass from now on. */
    void setGlass(boolean light) {
        glassEdge = new LiquidGlass.Edge(density, true);
        bottomDp = -19;
        // Clearer than the controls' glass: the dock is large and should let the shelf show through. Light: a faint
        // white veil instead of the smoked one.
        band.setShader(null);
        band.setColor(light ? 0x1FFFFFFF : 0x4D1E1E1E);
        backdrop.setAlpha(light ? 0xCC : 0xB3);
    }

    /**
     * The picture behind the dock, already blurred (HeroArt), stretched over the whole screen; null shows only the
     * smoked fill. The caller redraws the grid after changing it.
     */
    void setBackdrop(Bitmap blurred) {
        if (blurred == backdropBitmap) return;
        backdropBitmap = blurred;
        backdrop.setShader(blurred == null ? null
                : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (bottomDp * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        float tileW = 0f;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            tileW = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        // tvOS 18 UI Kit dock padding: 24px above and below the icons, 31px at the sides (at 2x), the sides measured
        // from a focused end tile, which grows past its slot by half its zoom.
        float pad = 12 * density, sidePad = 15.5f * density + tileW * (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        // The grid's parent no longer clips it (activity_main.xml), so the band may reach past the grid into the
        // screen's 56dp side margin; clipped, its rounded ends were sliced flat (#454). Stay inside that margin.
        float margin = 56 * density - density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) + margin));
        rect.inset(-side, -pad);
        rect.bottom += namesRoom;
        // tvOS 18 UI Kit: 65px corners on the 228px dock (at 2x).
        float radius = 32.5f * density;
        if (rect.top != shaderTop || rect.bottom != shaderBottom) {
            if (lightGlass) {
                // The pills' glass (pill_bg: white, 18% fading to 8%), a little fainter over this much larger area.
                band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                        Shader.TileMode.CLAMP));
            }
            // Liquid Glass rim light: a static gradient instead of a live blur, so the band costs the same to draw
            // as before.
            rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, new int[]{0x8CFFFFFF, 0x14FFFFFF, 0x26FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        if (glassEdge != null) {
            if (backdropBitmap != null) {
                // The blurred backdrop pixel under each point of the band, from the screen's size and the grid's place.
                parent.getLocationOnScreen(at);
                View root = parent.getRootView();
                backdropMatrix.setScale(root.getWidth() / (float) backdropBitmap.getWidth(),
                        root.getHeight() / (float) backdropBitmap.getHeight());
                backdropMatrix.postTranslate(-at[0], -at[1]);
                backdrop.getShader().setLocalMatrix(backdropMatrix);
                c.drawRoundRect(rect, radius, radius, backdrop);
            }
            c.drawRoundRect(rect, radius, radius, band);
            glassEdge.draw(c, rect, radius);
            return;
        }
        c.drawRoundRect(rect, radius, radius, band);
        rimRect.set(rect);
        rimRect.inset(density, density);
        c.drawRoundRect(rimRect, radius - density, radius - density, rim);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
