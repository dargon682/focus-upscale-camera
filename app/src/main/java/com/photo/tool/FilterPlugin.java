package com.photo.tool;

import android.graphics.Bitmap;

/**
 * 滤镜插件抽象。任何图标级可扩展的滤镜/特效处理器都应继承本类并实现三个抽象方法：
 * <ul>
 *   <li>{@link #id()}：全局唯一的插件标识（建议小写下划线命名，用于持久化开关）。</li>
 *   <li>{@link #name()}：展示名称（展示给用户，无需唯一）。</li>
 *   <li>{@link #apply(Bitmap)}：对输入位图执行一次滤镜，返回处理后的位图。</li>
 * </ul>
 *
 * <p>接入新滤镜的完整步骤见仓库 {@code docs/PLUGIN.md}。约定：
 * <ul>
 *   <li>默认回退值「none」表示不应用任何处理（原图直出）。</li>
 *   <li>{@code apply} 若无法产生新图（如内存不足）应返回输入位图本身；正常情况下返回一张新位图。</li>
 *   <li>调用方负责在切换滤镜时正确回收上一张已不再使用的位图。</li>
 *   <li>颜色处理应使用 {@link #pixels(Bitmap)} / {@link #put(Bitmap, int[])} 读取与回写 ARGB 数组。</li>
 * </ul>
 */
public abstract class FilterPlugin {

    /** 默认无滤镜标识。 */
    public static final String ID_NONE = "none";

    /** 插件唯一标识。 */
    public abstract String id();

    /** 插件展示名称。 */
    public abstract String name();

    /**
     * 对输入位图应用滤镜。
     * @param src 输入位图，调用方保证非空且已回收前不修改其引用语义。
     * @return 处理后的新位图；若无法处理或希望直出，可直接返回 src 本身。
     */
    public abstract Bitmap apply(Bitmap src);

    /** 读取位图 ARGB 像素数组（高效批处理入口）。 */
    protected static int[] pixels(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    /** 将处理后的 ARGB 像素数组写回新位图，并返回该位图（尺寸与原图一致）。 */
    protected static Bitmap put(Bitmap src, int[] px) {
        Bitmap.Config cfg = src.getConfig();
        if (cfg == null) cfg = Bitmap.Config.ARGB_8888;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), cfg);
        out.setPixels(px, 0, src.getWidth(), 0, 0, src.getWidth(), src.getHeight());
        return out;
    }

    /** 创建一张与原图同尺寸同配置的可写副本。 */
    protected static Bitmap copy(Bitmap src) {
        Bitmap.Config cfg = src.getConfig();
        if (cfg == null) cfg = Bitmap.Config.ARGB_8888;
        return src.copy(cfg, true);
    }

    /** 是否为默认「无滤镜」插件。 */
    public final boolean isNone() {
        return ID_NONE.equals(id());
    }
}