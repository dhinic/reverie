package app.reverie;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The Reverie mark: a rounded tile with a "moon" set into its top-right corner. Still, it is the solid logo;
 * spinning, the tile opens into a frame that a bright segment runs around while the moon breathes, which is the
 * app's loading indicator.
 */
final class LoadingView extends View {
    private static final long PERIOD_MS = 1600;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint run = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path notch = new Path();      // the gap cut out of the tile around the moon
    private final Path frame = new Path();
    private final Path segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private ValueAnimator anim;
    private float phase;        // 0..1 position of the bright segment along the frame

    LoadingView(Context c) {
        super(c);
        track.setStrokeCap(Paint.Cap.ROUND);
        run.setStyle(Paint.Style.STROKE);
        run.setStrokeCap(Paint.Cap.ROUND);
        run.setColor(0xFFFFFFFF);
        moon.setColor(0xFFFFFFFF);
        setLayerType(LAYER_TYPE_SOFTWARE, null);    // shadow layers on strokes need it on older GPUs paths
    }

    void setSpinning(boolean on) {
        if (on == (anim != null)) return;
        if (on) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(PERIOD_MS);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        } else {
            anim.cancel();
            anim = null;
            phase = 0;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setSpinning(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(w, h);
        float inset = s * 0.12f, r = s * 0.22f, sw = s * 0.07f;
        frame.reset();
        frame.addRoundRect(new RectF(inset, inset, s - inset, s - inset), r, r, Path.Direction.CW);
        measure.setPath(frame, true);
        notch.reset();
        notch.addCircle(s * 0.785f, s * 0.215f, s * 0.2f, Path.Direction.CW);
        track.setStrokeWidth(sw);
        run.setStrokeWidth(sw);
        run.setShadowLayer(sw * 0.8f, 0, 0, 0x99FFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        boolean spinning = anim != null;
        float len = measure.getLength();
        c.save();
        c.clipOutPath(notch);
        if (spinning) {
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x24FFFFFF);
            c.drawPath(frame, track);
            // a quarter of the perimeter, starting at the moon's corner (the rect path starts at the top-right arc)
            float start = phase * len, end = start + len * 0.25f;
            segment.reset();
            if (end <= len) {
                measure.getSegment(start, end, segment, true);
            } else {
                measure.getSegment(start, len, segment, true);
                measure.getSegment(0, end - len, segment, true);
            }
            c.drawPath(segment, run);
        } else {
            track.setStyle(Paint.Style.FILL_AND_STROKE);
            track.setColor(0xFFFFFFFF);
            c.drawPath(frame, track);
        }
        c.restore();
        float cx = s * 0.785f, cy = s * 0.215f;
        float breathe = spinning ? (float) Math.sin(phase * Math.PI) : 0f;
        float k = 1f - 0.2f * breathe * breathe;
        moon.setAlpha(Math.round(255 * (1f - 0.65f * breathe * breathe)));
        c.drawCircle(cx, cy, s * 0.13f * k, moon);
    }
}
