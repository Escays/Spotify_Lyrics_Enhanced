package kr.ivlis.ivlyricsandroid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small vector icons with stable geometry independent of the selected lyric font. */
final class LyricsToolbarIcon extends Drawable {
    static final int CHEVRON_DOWN = 0;
    static final int MORE = 1;
    static final int BULB = 2;
    static final int TUNE = 3;

    private final int type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final Path shape = new Path();

    LyricsToolbarIcon(int type) {
        this.type = type;
        paint.setColor(0xedffffff);
        paint.setStrokeWidth(2.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        chevron.moveTo(5f, 9f);
        chevron.lineTo(12f, 16f);
        chevron.lineTo(19f, 9f);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        if (type == MORE) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(5f, 12f, 1.7f, paint);
            canvas.drawCircle(12f, 12f, 1.7f, paint);
            canvas.drawCircle(19f, 12f, 1.7f, paint);
        } else if (type == BULB) {
            paint.setStyle(Paint.Style.STROKE);
            // A symmetric bulb: dome, short neck, two base lines. The previous arc left an open
            // wedge on one side and read as a broken circle once it was drawn small.
            shape.reset();
            shape.moveTo(9.3f, 15.1f);
            shape.cubicTo(9.3f, 12.9f, 6.6f, 11.6f, 6.6f, 9.0f);
            shape.cubicTo(6.6f, 5.6f, 9.0f, 3.2f, 12f, 3.2f);
            shape.cubicTo(15.0f, 3.2f, 17.4f, 5.6f, 17.4f, 9.0f);
            shape.cubicTo(17.4f, 11.6f, 14.7f, 12.9f, 14.7f, 15.1f);
            shape.close();
            canvas.drawPath(shape, paint);
            shape.reset();
            shape.moveTo(9.8f, 17.5f);
            shape.lineTo(14.2f, 17.5f);
            shape.moveTo(10.6f, 20.1f);
            shape.lineTo(13.4f, 20.1f);
            canvas.drawPath(shape, paint);
        } else if (type == TUNE) {
            paint.setStyle(Paint.Style.STROKE);
            shape.reset();
            shape.moveTo(4f, 7f);
            shape.lineTo(20f, 7f);
            shape.moveTo(4f, 12f);
            shape.lineTo(20f, 12f);
            shape.moveTo(4f, 17f);
            shape.lineTo(20f, 17f);
            canvas.drawPath(shape, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(9f, 7f, 2.1f, paint);
            canvas.drawCircle(16f, 12f, 2.1f, paint);
            canvas.drawCircle(11f, 17f, 2.1f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(chevron, paint);
        }
        canvas.restoreToCount(saved);
    }

    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
