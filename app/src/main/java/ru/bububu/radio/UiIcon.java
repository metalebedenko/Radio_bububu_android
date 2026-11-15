package ru.bububu.radio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Sized, state-aware vector for the native controls. */
final class UiIcon extends Drawable {
    private final Drawable vector;
    private final int size;
    private ColorStateList colors;
    private int tint;

    UiIcon(Context context, String name, int color, int dp) {
        this(context, name, ColorStateList.valueOf(color), dp);
    }

    UiIcon(Context context, String name, ColorStateList colors, int dp) {
        size = UiStyle.dp(context, dp);
        vector = context.getDrawable(resource(name)).mutate();
        this.colors = colors;
        tint = colors.getColorForState(getState(), colors.getDefaultColor());
        vector.setTint(tint);
    }

    private static int resource(String name) {
        switch (name) {
            case "arrow": return R.drawable.ux1_arrow;
            case "back": return R.drawable.ux1_back;
            case "check": return R.drawable.ux1_check;
            case "edit": return R.drawable.ux1_edit;
            case "error": return R.drawable.ux1_error;
            case "settings":
            case "gear": return R.drawable.ux1_gear;
            case "mic": return R.drawable.ux1_mic;
            case "more": return R.drawable.ux1_more;
            case "text":
            case "news": return R.drawable.ux1_news;
            case "next": return R.drawable.ux1_next;
            case "notes": return R.drawable.ux1_notes;
            case "pause": return R.drawable.ux1_pause;
            case "play": return R.drawable.ux1_play;
            case "plus": return R.drawable.ux1_plus;
            case "previous":
            case "prev": return R.drawable.ux1_prev;
            case "queue": return R.drawable.ux1_queue;
            case "radio": return R.drawable.ux1_radio;
            case "refresh": return R.drawable.ux1_refresh;
            case "search": return R.drawable.ux1_search;
            case "shuffle": return R.drawable.ux1_shuffle;
            case "sources": return R.drawable.ux1_sources;
            case "stop": return R.drawable.ux1_stop;
            case "sourceBack": return R.drawable.source_previous;
            case "sourceNext": return R.drawable.source_next;
            default: return R.drawable.ux1_radio;
        }
    }

    void setColors(ColorStateList value) {
        colors = value;
        onStateChange(getState());
    }

    @Override public boolean isStateful() { return colors.isStateful(); }

    @Override protected boolean onStateChange(int[] state) {
        int color = colors.getColorForState(state, colors.getDefaultColor());
        if (tint == color) return false;
        tint = color;
        vector.setTint(color);
        invalidateSelf();
        return true;
    }

    @Override protected void onBoundsChange(Rect bounds) { vector.setBounds(bounds); }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void draw(Canvas canvas) { vector.draw(canvas); }
    @Override public void setAlpha(int alpha) { vector.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) { vector.setColorFilter(colorFilter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
