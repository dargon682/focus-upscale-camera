package com.photo.tool;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 滤镜插件注册表：集中登记所有可用滤镜并提供查询与启用过滤。
 * <p>新增滤镜：在 {@link #buildBuiltins()} 中追加一个 {@link FilterPlugin} 即可，
 * 无需改动其他调用方。插件的启用开关持久化在 {@link Prefs}（键 {@code plugin_on_<id>}）。
 */
public final class FilterPluginRegistry {

    private FilterPluginRegistry() { }

    /** 内置滤镜注册表（顺序即展示顺序）。 */
    private static final List<FilterPlugin> BUILTINS = buildBuiltins();

    /** 全部滤镜（不可变）。 */
    public static List<FilterPlugin> all() {
        return BUILTINS;
    }

    /** 当前已启用的滤镜（根据 Prefs 开关过滤；「无滤镜」恒返回）。 */
    public static List<FilterPlugin> enabled(Context ctx) {
        List<FilterPlugin> out = new ArrayList<>(BUILTINS.size());
        for (FilterPlugin p : BUILTINS) {
            if (p.isNone() || Prefs.pluginOn(ctx, p.id())) out.add(p);
        }
        return out;
    }

    /** 按 id 查询插件；找不到返回 null。 */
    public static FilterPlugin byId(String id) {
        if (id == null) return null;
        for (FilterPlugin p : BUILTINS) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** 注册表主条目：真源内置插件（名称来自字符串资源，避免硬编码）。 */
    private static List<FilterPlugin> buildBuiltins() {
        List<FilterPlugin> list = new ArrayList<>();

        // 无滤镜（默认直出，不可关闭）
        list.add(new FilterPlugin() {
            @Override public String id()   { return FilterPlugin.ID_NONE; }
            @Override public String name() { return Localized.none(); }
            @Override public Bitmap apply(Bitmap src) { return src; }
        });

        // 黑白：BT.601 灰度
        list.add(new FilterPlugin() {
            @Override public String id()   { return "bw"; }
            @Override public String name() { return Localized.bw(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    int y = (299 * r + 587 * g + 114 * b) / 1000;
                    p[i] = (a << 24) | (y << 16) | (y << 8) | y;
                }
                return put(src, p);
            }
        });

        // 复古：sepia 棕色调
        list.add(new FilterPlugin() {
            @Override public String id()   { return "sepia"; }
            @Override public String name() { return Localized.sepia(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    int tr = (int) (r * 0.393 + g * 0.769 + b * 0.189);
                    int tg = (int) (r * 0.349 + g * 0.686 + b * 0.168);
                    int tb = (int) (r * 0.272 + g * 0.534 + b * 0.131);
                    p[i] = (a << 24) | (clamp(tr) << 16) | (clamp(tg) << 8) | clamp(tb);
                }
                return put(src, p);
            }
        });

        // 冷色调：偏蓝
        list.add(new FilterPlugin() {
            @Override public String id()   { return "cool"; }
            @Override public String name() { return Localized.cool(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    int nr = r * 9 / 10, ng = g * 9 / 10, nb = Math.min(255, b * 115 / 100);
                    p[i] = (a << 24) | (nr << 16) | (ng << 8) | nb;
                }
                return put(src, p);
            }
        });

        // 暖色调：偏黄
        list.add(new FilterPlugin() {
            @Override public String id()   { return "warm"; }
            @Override public String name() { return Localized.warm(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    int nr = Math.min(255, r * 115 / 100), ng = g * 105 / 100, nb = b * 9 / 10;
                    p[i] = (a << 24) | (nr << 16) | (ng << 8) | nb;
                }
                return put(src, p);
            }
        });

        // 柔和：轻微降对比、提亮（胶片感）
        list.add(new FilterPlugin() {
            @Override public String id()   { return "soft"; }
            @Override public String name() { return Localized.soft(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    r = (r + 24) * 88 / 100 + (r * 12 / 100);
                    g = (g + 24) * 88 / 100 + (g * 12 / 100);
                    b = (b + 24) * 88 / 100 + (b * 12 / 100);
                    p[i] = (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
                }
                return put(src, p);
            }
        });

        // 鲜艳：提高对比与饱和度
        list.add(new FilterPlugin() {
            @Override public String id()   { return "vivid"; }
            @Override public String name() { return Localized.vivid(); }
            @Override public Bitmap apply(Bitmap src) {
                int w = src.getWidth(), h = src.getHeight();
                int[] p = pixels(src);
                for (int i = 0; i < p.length; i++) {
                    int a = (p[i] >>> 24) & 0xff, r = (p[i] >> 16) & 0xff,
                            g = (p[i] >> 8) & 0xff, b = p[i] & 0xff;
                    // 对比增强 + 饱和度增强（系数 1.25）
                    float gv = 0.299f * r + 0.587f * g + 0.114f * b;
                    r = (int) (gv + (r - gv) * 1.25f);
                    g = (int) (gv + (g - gv) * 1.25f);
                    b = (int) (gv + (b - gv) * 1.25f);
                    r = (int) ((r - 128) * 1.15f + 128);
                    g = (int) ((g - 128) * 1.15f + 128);
                    b = (int) ((b - 128) * 1.15f + 128);
                    p[i] = (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
                }
                return put(src, p);
            }
        });

        return Collections.unmodifiableList(list);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 滤镜名称本地化（避免在无 Context 的静态注册表里耦合资源）。 */
    private static final class Localized {
        static String none()  { return "无滤镜"; }
        static String bw()    { return "黑白"; }
        static String sepia() { return "复古"; }
        static String cool()  { return "冷色调"; }
        static String warm()  { return "暖色调"; }
        static String soft()  { return "柔和"; }
        static String vivid() { return "鲜艳"; }
    }
}