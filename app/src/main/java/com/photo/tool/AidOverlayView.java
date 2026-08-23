package com.photo.tool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 取景辅助覆盖层：可绘制九宫格构图线，以及基于设备姿态的水平仪。
 * 开关由外部通过 {@link #enable(boolean, boolean)} 控制，姿态由 {@link #setTilt(float)} 更新。
 */
public class AidOverlayView extends View {

    private boolean showGrid = false;
    private boolean showLevel = false;
    private float tiltDeg = 0f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AidOverlayView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.argb(140, 255, 255, 255));
        gridPaint.setStrokeWidth(dp(1));
        levelPaint.setStyle(Paint.Style.STROKE);
        levelPaint.setStrokeWidth(dp(3));
        levelTick.setStyle(Paint.Style.STROKE);
        levelTick.setStrokeWidth(dp(2));
        levelTick.setColor(Color.argb(110, 255, 255, 255));
        levelText.setColor(Color.argb(230, 255, 255, 255));
        levelText.setTextSize(sp(12));
        levelText.setTextAlign(Paint.Align.CENTER);
    }

    public void enable(boolean grid, boolean level) {
        showGrid = grid;
        showLevel = level;
        invalidate();
    }

    public void setTilt(float tiltDeg) {
        this.tiltDeg = tiltDeg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        if (showGrid) {
            for (int i = 1; i < 3; i++) {
                float x = w * i / 3f;
                canvas.drawLine(x, 0, x, h, gridPaint);
            }
            for (int i = 1; i < 3; i++) {
                float y = h * i / 3f;
                canvas.drawLine(0, y, w, y, gridPaint);
            }
        }

        if (showLevel) {
            float cx = w / 2f;
            float cy = h / 2f;
            boolean flat = Math.abs(tiltDeg) < 6f;
            levelPaint.setColor(flat ? 0xFF00E676 : 0xFFFFEA00);

            canvas.save();
            canvas.rotate(-tiltDeg, cx, cy);
            canvas.drawLine(cx - w / 3f, cy, cx + w / 3f, cy, levelPaint);
            canvas.restore();

            // 中心基准刻度 + 指示
            canvas.drawCircle(cx, cy, dp(10), levelTick);
            canvas.drawText(flat ? getResources().getString(R.string.level_flat)
                    : getResources().getString(R.string.level_hint), cx, getHeight() - dp(28), levelText);
        }
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }

    private float sp(float v) {
        return getResources().getDisplayMetrics().scaledDensity * v;
    }
}