package com.photo.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 原图 / 超分对比视图：以一条可拖动分隔线左右对比两张同内容的图片，
 * 支持双指缩放与单指平移。模式：仅原图 / 仅超分 / 分割对比。
 */
public class CompareView extends View {

    public enum Mode { SUPER, ORIG, COMPARE }

    private Bitmap origBitmap;
    private Bitmap superBitmap;
    private Mode mode = Mode.COMPARE;
    private boolean hasOrig = false;

    private final RectF img = new RectF();      // 适配后的图片矩形
    private final RectF drawRect = new RectF(); // 应用缩放/位移后的屏幕矩形
    private final RectF view = new RectF();

    private float scale = 1f;
    private float translateX = 0f, translateY = 0f;
    /** 分割线位置，0~1 */
    private float divider = 0.5f;

    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ScaleGestureDetector scaleDetector;
    private boolean draggingDivider = false;
    private float lastX = 0f, lastY = 0f;

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 8f;

    public CompareView(Context c) { this(c, null); }

    public CompareView(Context c, AttributeSet a) {
        super(c, a);
        dividerPaint.setColor(0xFFFFFFFF);
        dividerPaint.setStrokeWidth(dp(3));
        handleRing.setStyle(Paint.Style.FILL);
        handleRing.setColor(0xAAFFFFFF);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scale = clamp(scale * d.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                rebuildDrawRect();
                invalidate();
                return true;
            }
        });
    }

    public void setBitmaps(Bitmap superBmp, Bitmap origBmp) {
        superBitmap = superBmp;
        origBitmap = origBmp;
        hasOrig = origBmp != null;
        resetView();
        invalidate();
    }

    public void setMode(Mode m) {
        mode = m;
        invalidate();
    }

    public Mode getMode() { return mode; }

    public boolean hasOriginal() { return hasOrig; }

    /** 重置缩放/位移与分割线 */
    public void resetView() {
        scale = 1f;
        translateX = 0f;
        translateY = 0f;
        divider = 0.5f;
        computeImgRect();
        rebuildDrawRect();
    }

    private void computeImgRect() {
        Bitmap b = mode == Mode.ORIG && hasOrig ? origBitmap : superBitmap;
        if (b == null) { img.setEmpty(); return; }
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float ratio = Math.min(vw / b.getWidth(), vh / b.getHeight());
        float w = b.getWidth() * ratio;
        float h = b.getHeight() * ratio;
        img.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    private void rebuildDrawRect() {
        drawRect.set(img);
        float insetW = img.width() * (scale - 1) / 2f;
        float insetH = img.height() * (scale - 1) / 2f;
        drawRect.inset(-insetW, -insetH);
        drawRect.offset(translateX * img.width() / 2f, translateY * img.height() / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        view.set(0, 0, w, h);
        computeImgRect();
        rebuildDrawRect();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (superBitmap == null) return;

        canvas.save();
        canvas.clipRect(view);
        canvas.clipRect(drawRect);

        if (mode == Mode.SUPER) {
            canvas.drawBitmap(superBitmap, null, drawRect, null);
        } else if (mode == Mode.ORIG) {
            if (hasOrig) canvas.drawBitmap(origBitmap, null, drawRect, null);
            else canvas.drawBitmap(superBitmap, null, drawRect, null);
        } else {
            float dx = drawRect.left + divider * (drawRect.right - drawRect.left);
            // 左：原图
            canvas.save();
            canvas.clipRect(drawRect.left, drawRect.top, dx, drawRect.bottom);
            if (hasOrig) canvas.drawBitmap(origBitmap, null, drawRect, null);
            else canvas.drawBitmap(superBitmap, null, drawRect, null);
            canvas.restore();
            // 右：超分
            canvas.save();
            canvas.clipRect(dx, drawRect.top, drawRect.right, drawRect.bottom);
            canvas.drawBitmap(superBitmap, null, drawRect, null);
            canvas.restore();
            // 分割线 + 手柄
            float cy = drawRect.centerY();
            canvas.drawLine(dx, drawRect.top, dx, drawRect.bottom, dividerPaint);
            canvas.drawCircle(dx, cy, dp(10), handleRing);
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = ev.getX();
                lastY = ev.getY();
                if (mode == Mode.COMPARE) {
                    float dx = drawRect.left + divider * (drawRect.right - drawRect.left);
                    draggingDivider = Math.abs(ev.getX() - dx) <= dp(24);
                } else {
                    draggingDivider = false;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (ev.getPointerCount() > 1) { return true; }
                float cx = ev.getX(), cy = ev.getY();
                float dxm = cx - lastX, dym = cy - lastY;
                if (draggingDivider) {
                    if (drawRect.width() > 0) {
                        divider = clamp((cx - drawRect.left) / drawRect.width(), 0.05f, 0.95f);
                    }
                } else if (scale > MIN_SCALE) {
                    translateX = clamp(translateX + dxm / (img.width() / 2f), -2f, 2f);
                    translateY = clamp(translateY + dym / (img.height() / 2f), -2f, 2f);
                    rebuildDrawRect();
                }
                lastX = cx;
                lastY = cy;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                draggingDivider = false;
                return true;
            }
            default:
                return true;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }
}