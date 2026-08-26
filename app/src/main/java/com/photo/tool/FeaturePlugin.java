package com.photo.tool;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * 功能插件：向应用注入新的能力/行为。子类通过覆盖 {@link #onResult} 等方式
 * 在主流程的既定接入点扩展功能（如保存前叠加日期水印）。
 */
public abstract class FeaturePlugin extends Plugin {

    /** 插件功能的一段说明，用于设置页展示。 */
    public abstract String description();

    @Override
    public final PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public final boolean canToggle() {
        return true;
    }

    /**
     * 结果页「保存」前的后处理钩子：对即将落盘的位图做一次增强后返回。
     * 默认原样返回；子类如需绘制（如水印）应返回一张新位图，且不得回收入参 src。
     *
     * @param src 即将保存的位图（滤镜后的结果）
     * @param c   宿主上下文
     * @return 实际要写入文件的位图
     */
    public Bitmap onSave(Bitmap src, Context c) {
        return src;
    }
}