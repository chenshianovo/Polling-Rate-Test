package com.gamepad.rate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class StickView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lx, ly, rx, ry;
    private boolean leftSupported, rightSupported;

    public StickView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setAxes(float lx, float ly, float rx, float ry, boolean leftSupported, boolean rightSupported) {
        this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry;
        this.leftSupported = leftSupported; this.rightSupported = rightSupported;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float radius = Math.min(getWidth() / 5.5f, getHeight() / 3.3f);
        drawStick(canvas, getWidth() * 0.25f, getHeight() * 0.42f, radius, lx, ly, leftSupported, "左摇杆", density);
        drawStick(canvas, getWidth() * 0.75f, getHeight() * 0.42f, radius, rx, ry, rightSupported, "右摇杆", density);
    }

    private void drawStick(Canvas canvas, float x, float y, float radius, float ax, float ay, boolean supported, String label, float density) {
        paint.setColor(Appearance.color(getContext(), R.attr.lineColor));
        paint.setStrokeWidth(1.5f * density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x, y, radius, paint);
        canvas.drawLine(x - radius, y, x + radius, y, paint);
        canvas.drawLine(x, y - radius, x, y + radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(supported ? Appearance.color(getContext(), R.attr.accentColor) : Appearance.color(getContext(), R.attr.secondaryText));
        canvas.drawCircle(x + clamp(ax) * radius, y + clamp(ay) * radius, 6 * density, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(label + (supported ? "" : " / 无映射"), x, y + radius + 22 * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static float clamp(float value) { return Math.max(-1, Math.min(1, value)); }
}
