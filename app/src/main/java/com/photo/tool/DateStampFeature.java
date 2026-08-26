package com.photo.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日期水印功能插件：结果页保存照片时，在图片右下角叠加当前时间戳。
 * 该插件是 {@link FeaturePlugin} 的官方示例——展示「不修改主流程、仅在既定接入点
 * 注入新能力」的写法（接入点为 {@link #onSave}）。
 */
public final class DateStampFeature extends FeaturePlugin {

    public static final String ID = "date_stamp";

    private static final DateStampFeature INSTANCE = new DateStampFeature();

    private DateStampFeature() { }

    public static DateStampFeature instance() {
        return INSTANCE;
    }

    private final SimpleDateFormat format =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "日期水印";
    }

    @Override
    public String description() {
        return "保存时在图片右下角叠加拍摄日期时间戳";
    }

    @Override
    public Bitmap onSave(Bitmap src, Context c) {
        if (!Prefs.pluginOn(c, id())) {
            return src;
        }
        try {
            Bitmap out = src.copy(src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(out);
            String stamp = format.format(new Date());

            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setColor(Color.parseColor("#20000000"));
            border.setTextSize(36f);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextSize(36f);

            float pad = 18f;
            int w = out.getWidth(), h = out.getHeight();
            Rect bounds = new Rect();
            text.getTextBounds(stamp, 0, stamp.length(), bounds);
            float tw = bounds.width();

            // 底部留白 + 半透明底条，保证任何背景可读
            float baseline = h - pad - bounds.height();
            float tx = w - pad - tw;
            canvas.drawRoundRect(tx - 14f, baseline - bounds.height() - 8f,
                    w - pad + 14f, h, 12f, 12f, border);
            canvas.drawText(stamp, tx, baseline, text);
            return out;
        } catch (Throwable t) {
            // 绘制失败回退：仍保存图片（不带水印），不让用户因此丢图
            return src;
        }
    }
}