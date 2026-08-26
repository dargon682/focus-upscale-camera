package com.photo.tool;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用设置持久化：读取/写入用户的各项功能开关与超分参数。 */
public final class Prefs {

    private static final String FILE = "photo_tool_settings";

    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- 功能开关 ----
    public static boolean gridEnabled(Context c) { return sp(c).getBoolean("grid", false); }
    public static boolean levelEnabled(Context c) { return sp(c).getBoolean("level", false); }
    public static boolean compareEnabled(Context c) { return sp(c).getBoolean("compare", true); }

    // ---- 超分参数 ----
    public static int scale(Context c) { int v = sp(c).getInt("scale", 2); return v == 3 ? 3 : 2; }
    public static int frames(Context c) {
        int v = sp(c).getInt("frames", 7);
        return Math.max(3, Math.min(11, v));
    }
    public static float sharpen(Context c) {
        float f = sp(c).getFloat("sharpen", 0.9f);
        return Math.max(0.2f, Math.min(2.0f, f));
    }
    /** 采样画质档位：0 流畅 / 1 均衡 / 2 清晰 */
    public static int quality(Context c) {
        int v = sp(c).getInt("quality", 1);
        return Math.max(0, Math.min(2, v));
    }
    /** 网格样式：0 关 / 1 九宫格 / 2 黄金分割 */
    public static int gridStyle(Context c) {
        return Math.max(0, Math.min(2, sp(c).getInt("grid_style", 1)));
    }

    // ---- 滤镜插件 ----
    /** 滤镜插件是否启用（默认内置滤镜启用）。 */
    public static boolean pluginOn(Context c, String id) {
        return sp(c).getBoolean("plugin_on_" + id, true);
    }
    public static void putPluginOn(Context c, String id, boolean v) {
        sp(c).edit().putBoolean("plugin_on_" + id, v).apply();
    }

    /** 结果页当前选中的滤镜 id（默认无滤镜）。 */
    public static String currentFilter(Context c) {
        return sp(c).getString("current_filter", "none");
    }
    public static void putCurrentFilter(Context c, String id) {
        sp(c).edit().putString("current_filter", id == null ? "none" : id).apply();
    }

    // ---- 主题 ----
    /** 当前主题插件 id（默认暗色）。 */
    public static String themeId(Context c) {
        return sp(c).getString("theme_id", "theme_dark");
    }
    public static void putThemeId(Context c, String id) {
        sp(c).edit().putString("theme_id", id == null ? "theme_dark" : id).apply();
    }

    // ---- 写入 ----
    public static void putGrid(Context c, boolean v)   { sp(c).edit().putBoolean("grid", v).apply(); }
    public static void putLevel(Context c, boolean v)  { sp(c).edit().putBoolean("level", v).apply(); }
    public static void putCompare(Context c, boolean v){ sp(c).edit().putBoolean("compare", v).apply(); }
    public static void putScale(Context c, int v)      { sp(c).edit().putInt("scale", v).apply(); }
    public static void putFrames(Context c, int v)     { sp(c).edit().putInt("frames", v).apply(); }
    public static void putSharpen(Context c, float v)  { sp(c).edit().putFloat("sharpen", v).apply(); }
    public static void putQuality(Context c, int v)    { sp(c).edit().putInt("quality", v).apply(); }
    public static void putGridStyle(Context c, int v)  { sp(c).edit().putInt("grid_style", v).apply(); }

    // ---- 下载双通道 ----
    /** 是否接收 Beta 测试通道更新（默认 false）。 */
    public static boolean allowBeta(Context c) { return sp(c).getBoolean("allow_beta", false); }
    public static void putAllowBeta(Context c, boolean v) {
        sp(c).edit().putBoolean("allow_beta", v).apply();
    }

    // ---- 下载镜像源 ----
    /** 下载镜像源：0 自动（测速选取最快） / 1 直连 / 2 gh-proxy / 3 ghproxy */
    public static int downloadMirror(Context c) {
        return Math.max(0, Math.min(3, sp(c).getInt("mirror", 0)));
    }
    public static void putDownloadMirror(Context c, int v) {
        int val = Math.max(0, Math.min(3, v));
        sp(c).edit().putInt("mirror", val).apply();
    }

    // ---- 下载断点续传 ----
    /** 已下载的字节偏移（断点续传起点），0 表示无偏移从头下载。 */
    public static long downloadOffset(Context c) { return sp(c).getLong("dl_offset", 0L); }
    public static void putDownloadOffset(Context c, long v) {
        sp(c).edit().putLong("dl_offset", Math.max(0L, v)).apply();
    }
    public static void clearDownloadOffset(Context c) {
        sp(c).edit().remove("dl_offset").apply();
    }

    /**
     * 超分内存保护：根据设备堆内存等级与目标超分倍数，计算安全的单帧采样宽度，
     * 使输出高分辨率网格的 float 工作缓冲不超过可承受范围，防止低端机 OOM。
     */
    public static int safeSampleWidth(int heapMb, int scale, int defaultWidth) {
        // 峰值工作时约占 24B/输出像素（accRGB 3*4B + count 4B + tmp 4B + out/blur 各 4B，
        // 输入侧缓冲按 1/scale^2 折算），取堆内存的 55% 作为可用预算，降低 OOM 概率。
        double usable = heapMb * 1024d * 1024d * 0.55;
        double outPixels = usable / 24.0;
        double side = Math.sqrt(outPixels) / scale;
        int sw = (int) Math.floor(side);
        return Math.min(defaultWidth, Math.max(256, sw));
    }
}