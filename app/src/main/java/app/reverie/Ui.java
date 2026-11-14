package app.reverie;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


/**
 * The "Glass" look: frosted panels over a soft dusk gradient. Plain Views, no layout XML.
 * Focus is the only "hover" a TV has, so every focusable shows a white ring and a slight scale-up.
 */
final class Ui {
    private Ui() { }

    static final int BG = 0xFF0E1116;
    static final int TEXT = 0xFFF4F6F8;
    static final int TEXT_75 = 0xBFF4F6F8;
    static final int TEXT_60 = 0x99F4F6F8;
    static final int TEXT_55 = 0x8CF4F6F8;
    static final int TEXT_45 = 0x73F4F6F8;
    static final int PANEL = 0x8C14181E;
    static final int PANEL_BORDER = 0x29FFFFFF;
    static final int ROW_FOCUS = 0x24FFFFFF;
    static final int DIVIDER = 0x1AFFFFFF;
    static final int OK = 0xFF5ED58A;
    static final int ERROR = 0xFFFF8A80;

    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---- page ----------------------------------------------------------------------------------------------

    /**
     * Backdrop plus a scrolling vertical list inside the TV safe area (96 dp sides, 48 dp top/bottom). With a
     * title it starts with the display title and a dim subtitle; the caller adds the rest.
     */
    static LinearLayout page(Activity a, String title, String subtitle) {
        FrameLayout root = new FrameLayout(a);
        root.setBackground(backdrop(a));
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(dp(a, 96), dp(a, 40), dp(a, 96), dp(a, 40));
        if (title != null) {
            list.addView(text(a, title, 46, TEXT, LIGHT));
            TextView sub = text(a, subtitle == null ? "" : subtitle, 17, TEXT_55, REGULAR);
            sub.setPadding(0, dp(a, 4), 0, dp(a, 20));
            list.addView(sub);
        }
        sv.addView(list, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        a.setContentView(root);
        return list;
    }

    /** Small dim line pinned bottom-left of the page (inside the safe area). */
    static TextView pageCaption(LinearLayout list, String s) {
        Context c = list.getContext();
        TextView t = text(c, s, 15, TEXT_45, REGULAR);
        FrameLayout root = (FrameLayout) list.getParent().getParent();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(c, 96), 0, 0, dp(c, 40));
        root.addView(t, lp);
        return t;
    }

    /** A fixed dusk wash: deep blue sky into a warm band, dark teal below, softened by a dark scrim. */
    static Drawable backdrop(Context c) {
        ShapeDrawable sky = new ShapeDrawable(new RectShape());
        sky.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, 0, h,
                        new int[]{0xFF1C2F5A, 0xFF5E5C84, 0xFFB08064, 0xFF35526A, 0xFF121E2D},
                        new float[]{0f, 0.3f, 0.52f, 0.68f, 1f}, Shader.TileMode.CLAMP);
            }
        });
        Drawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x660A0C10, 0xA60A0C10});
        return new LayerDrawable(new Drawable[]{sky, wash(0x66F0C080, 0.5f, 0.5f, 0.45f), scrim});
    }

    /** A soft radial glow fading to transparent; centre as fractions of the view, radius in 1080p pixels. */
    private static Drawable wash(int color, float cx, float cy, float radiusFraction) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(radiusFraction * 1920);
        return g;
    }

    // ---- text ----------------------------------------------------------------------------------------------

    static TextView text(Context c, String s, int sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    /** Rounded translucent chip for on-screen status text over the slideshow. */
    static void chip(TextView t) {
        Context c = t.getContext();
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setBackground(rounded(c, 0xD914181E, PANEL_BORDER, 1, 100));
    }

    // ---- pills and chips -----------------------------------------------------------------------------------

    /** A pill button. Primary = white (main action); otherwise translucent with a thin border. */
    static TextView pill(Context c, String s, boolean primary) {
        TextView t = text(c, s, 20, primary ? BG : TEXT, MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setBackground(focusRing(c, primary ? rounded(c, 0xEBFFFFFF, 0, 0, 100) : rounded(c, 0x1FFFFFFF, 0x4DFFFFFF, 1, 100)));
        t.setPadding(dp(c, 32), dp(c, 16), dp(c, 32), dp(c, 16));   // after the background: its insets would replace it
        focusable(t, 1.04f);
        return t;
    }

    /** Non-focusable pill-shaped status, e.g. "● Connected". */
    static TextView status(Context c, String s, int dotColor) {
        TextView t = text(c, "●  " + s, 20, TEXT, MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setBackground(focusRing(c, rounded(c, 0x1FFFFFFF, 0x4DFFFFFF, 1, 100)));
        t.setPadding(dp(c, 32), dp(c, 16), dp(c, 32), dp(c, 16));
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(dotColor), 0, 1, 0);
        t.setText(sp);
        return t;
    }

    /** Section chip in the Settings rail; {@link #setChipOn} marks the shown section. */
    static TextView railChip(Context c, String s) {
        TextView t = text(c, s, 18, TEXT_75, REGULAR);
        focusable(t, 1.04f);
        setChipOn(t, false);
        return t;
    }

    static void setChipOn(TextView t, boolean on) {
        Context c = t.getContext();
        t.setTextColor(on ? BG : TEXT_75);
        t.setTypeface(on ? MEDIUM : REGULAR);
        t.setBackground(focusRing(c, on ? rounded(c, 0xE6FFFFFF, 0, 0, 100) : rounded(c, 0x14FFFFFF, 0x2EFFFFFF, 1, 100)));
        t.setPadding(dp(c, 26), dp(c, 14), dp(c, 26), dp(c, 14));
    }

    // ---- panels and rows -----------------------------------------------------------------------------------

    /** Frosted panel; add rows to it. A thin divider is drawn between children. */
    static LinearLayout panel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(rounded(c, PANEL, PANEL_BORDER, 1, 24));
        p.setPadding(dp(c, 20), dp(c, 6), dp(c, 20), dp(c, 6));
        p.setClipToPadding(false);
        p.setClipChildren(false);
        GradientDrawable div = new GradientDrawable();
        div.setColor(DIVIDER);
        div.setSize(0, dp(c, 1));
        p.setDividerDrawable(div);
        p.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return p;
    }

    /** A focusable row: dim label left, value right. Children: 0 = label, 1 = value. */
    static LinearLayout row(Context c, String label, String value) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        TextView l = text(c, label, 18, TEXT_60, REGULAR);
        TextView v = text(c, value, 18, TEXT, MEDIUM);
        v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(c, ROW_FOCUS, 0x99FFFFFF, 2, 14));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        r.setBackground(bg);
        focusable(r, 1.01f);
        return r;
    }

    static void setRow(LinearLayout row, String label, String value) {
        ((TextView) row.getChildAt(0)).setText(label);
        ((TextView) row.getChildAt(1)).setText(value);
    }

    /** Locked rows are dim, cannot take focus and say why. */
    static void setLocked(LinearLayout row, boolean locked, String why) {
        row.setAlpha(locked ? 0.38f : 1f);
        row.setFocusable(!locked);
        row.setClickable(!locked);
        if (locked) ((TextView) row.getChildAt(1)).setText("🔒  " + why);
    }

    /** Right-aligned horizontal strip for buttons inside a panel. */
    static LinearLayout buttonRow(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 4), dp(c, 12), dp(c, 4), dp(c, 8));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        GradientDrawable gap = new GradientDrawable();
        gap.setSize(dp(c, 12), 0);
        r.setDividerDrawable(gap);
        return r;
    }

    static LinearLayout.LayoutParams margin(Context c, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, top);
        lp.bottomMargin = dp(c, bottom);
        return lp;
    }

    // ---- building blocks -----------------------------------------------------------------------------------

    private static void focusable(View v, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, focused) -> view.animate().scaleX(focused ? scale : 1f)
                .scaleY(focused ? scale : 1f).setDuration(120).start());
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** The pill inset by 4 dp; when focused a soft white ring fills that margin, so the size never changes. */
    private static StateListDrawable focusRing(Context c, Drawable pill) {
        int m = dp(c, 4);
        Drawable ring = new LayerDrawable(new Drawable[]{rounded(c, 0, 0x4DFFFFFF, 4, 100),
                new InsetDrawable(pill, m)});
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, ring);
        d.addState(new int[]{}, new InsetDrawable(pill, m));
        return d;
    }
}
