package com.photo.tool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 取景辅助覆盖层：可绘制构图网格（九宫格 / 黄金分割）与基于设备姿态的水平仪。
 * 开关由外部通过 {@link #enable(boolean, boolean)} 控制，姿态由 {@link #setTilt(float)} 更新。
 * 网格样式由 {@link Prefs#gridStyle(Context)} 决定：0 关 / 1 九宫格 / 2 黄金分割。
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
            drawGrid(canvas, w, h);
        }

        if (showLevel) {
            drawLevel(canvas, w, h);
        }
    }

    /** 按用户设置的网格样式绘制构图辅助线（0 关 / 1 九宫格 / 2 黄金分割）。 */
    private void drawGrid(Canvas canvas, int w, int h) {
        int style = Prefs.gridStyle(getContext());
        if (style <= 0) return; // 0 = 不画网格
        // 1/3 线：九宫格与黄金分割两种样式均保留
        for (int i = 1; i < 3; i++) {
            float x = w * i / 3f;
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (int i = 1; i < 3; i++) {
            float y = h * i / 3f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        if (style == 2) {
            // 黄金分割点（左上 0.382 + 右下 0.618）
            gridPaint.setStyle(Paint.Style.FILL);
            gridPaint.setColor(Color.argb(200, 255, 255, 255));
            float r = dp(6);
            canvas.drawCircle(w * 0.382f, h * 0.382f, r, gridPaint);
            canvas.drawCircle(w * 0.618f, h * 0.618f, r, gridPaint);
            gridPaint.setColor(Color.argb(140, 255, 255, 255));
        }
    }

    private void drawLevel(Canvas canvas, int w, int h) {
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

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }

    private float sp(float v) {
        return getResources().getDisplayMetrics().scaledDensity * v;
    }
}