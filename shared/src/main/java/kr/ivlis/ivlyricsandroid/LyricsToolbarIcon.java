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
            // Vertical, like the reference: three dots stacked, not a row.
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(12f, 5.5f, 2f, paint);
            canvas.drawCircle(12f, 12f, 2f, paint);
            canvas.drawCircle(12f, 18.5f, 2f, paint);
        } else if (type == BULB) {
            // Material's outlined light bulb: a hollow dome on a plated neck, drawn even-odd so the
            // glass reads as an outline instead of a blob.
            paint.setStyle(Paint.Style.FILL);
            paint.setPathEffect(null);
            shape.reset();
            shape.setFillType(Path.FillType.EVEN_ODD);
            shape.moveTo(9f, 21f);
            shape.cubicTo(9f, 21.55f, 9.45f, 22f, 10f, 22f);
            shape.lineTo(14f, 22f);
            shape.cubicTo(14.55f, 22f, 15f, 21.55f, 15f, 21f);
            shape.lineTo(15f, 20f);
            shape.lineTo(9f, 20f);
            shape.close();
            shape.moveTo(12f, 2f);
            shape.cubicTo(8.14f, 2f, 5f, 5.14f, 5f, 9f);
            shape.cubicTo(5f, 11.38f, 6.19f, 13.47f, 8f, 14.74f);
            shape.lineTo(8f, 17f);
            shape.cubicTo(8f, 17.55f, 8.45f, 18f, 9f, 18f);
            shape.lineTo(15f, 18f);
            shape.cubicTo(15.55f, 18f, 16f, 17.55f, 16f, 17f);
            shape.lineTo(16f, 14.74f);
            shape.cubicTo(17.81f, 13.47f, 19f, 11.38f, 19f, 9f);
            shape.cubicTo(19f, 5.14f, 15.86f, 2f, 12f, 2f);
            shape.close();
            shape.moveTo(14.85f, 13.1f);
            shape.lineTo(14f, 13.7f);
            shape.lineTo(14f, 16f);
            shape.lineTo(10f, 16f);
            shape.lineTo(10f, 13.7f);
            shape.lineTo(9.15f, 13.1f);
            shape.cubicTo(7.8f, 12.16f, 7f, 10.63f, 7f, 9f);
            shape.cubicTo(7f, 6.24f, 9.24f, 4f, 12f, 4f);
            shape.cubicTo(14.76f, 4f, 17f, 6.24f, 17f, 9f);
            shape.cubicTo(17f, 10.63f, 16.2f, 12.16f, 14.85f, 13.1f);
            shape.close();
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
