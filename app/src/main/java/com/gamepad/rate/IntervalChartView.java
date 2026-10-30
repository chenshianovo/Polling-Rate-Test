package com.gamepad.rate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

public final class IntervalChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private double[] values = new double[0];

    public IntervalChartView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setIntervals(double[] values) {
        this.values = values;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 52 * density;
        float top = 16 * density;
        float right = getWidth() - 6 * density;
        float bottom = getHeight() - 24 * density;
        if (right <= left || bottom <= top) return;
        double ceiling = 10;
        for (double value : values) ceiling = Math.max(ceiling, value * 1.1);
        paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        paint.setStrokeWidth(density);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i <= 2; i++) {
            float y = top + (bottom - top) * i / 2;
            paint.setColor(Appearance.color(getContext(), R.attr.lineColor));
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(Appearance.color(getContext(), R.attr.secondaryText));
            canvas.drawText(String.format(Locale.US, "%.1f", ceiling * (1 - i / 2.0)), 0, y + 4 * density, paint);
        }
        if (values.length == 0) {
            paint.setColor(Appearance.color(getContext(), R.attr.secondaryText));
            canvas.drawText("收到采样后显示间隔", left + 8 * density, (top + bottom) / 2, paint);
            return;
        }
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float x = left + (right - left) * i / Math.max(1, values.length - 1);
            float y = bottom - (float) (values[i] / ceiling) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setColor(Appearance.color(getContext(), R.attr.accentColor));
        paint.setStrokeWidth(2 * density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (values.length == 1) canvas.drawCircle(left, bottom - (float) (values[0] / ceiling) * (bottom - top), 3 * density, paint);
        paint.setColor(Appearance.color(getContext(), R.attr.secondaryText));
        canvas.drawText("较早", left, getHeight() - 3 * density, paint);
        canvas.drawText("最新", right - 28 * density, getHeight() - 3 * density, paint);
    }
}
