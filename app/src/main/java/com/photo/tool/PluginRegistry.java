package com.photo.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「全方面插件系统」统一注册表：集中登记滤镜、功能、主题三类插件，
 * 并提供查询与启用过滤。新增插件只需在下方对应集合追加一个实例。
 */
public final class PluginRegistry {

    private PluginRegistry() { }

    /* ---- 主题插件 ---- */
    public static final ThemePlugin THEME_DARK = new ThemePlugin() {
        @Override public String id()         { return "theme_dark"; }
        @Override public String name()       { return "暗色主题"; }
        @Override public int themedRes()     { return R.style.AppTheme; }
    };

    public static final ThemePlugin THEME_LIGHT = new ThemePlugin() {
        @Override public String id()         { return "theme_light"; }
        @Override public String name()       { return "亮色主题"; }
        @Override public int themedRes()     { return R.style.AppThemeLight; }
    };

    /* ---- 功能插件 ---- */
    private static final List<FeaturePlugin> FEATURES = buildFeatures();

    /** 全部滤镜插件（未启用过滤）。 */
    public static List<FilterPlugin> filters() {
        return FilterPluginRegistry.all();
    }

    /** 全部功能插件（未启用过滤）。 */
    public static List<FeaturePlugin> features() {
        return FEATURES;
    }

    /** 全部主题插件。 */
    public static List<ThemePlugin> themes() {
        List<ThemePlugin> t = new ArrayList<>();
        t.add(THEME_LIGHT);
        t.add(THEME_DARK);
        return t;
    }

    /** 全部已启用插件（滤镜按开关过滤；功能按开关过滤；主题为单选、全部列出）。 */
    public static List<Plugin> enabled(android.content.Context ctx) {
        List<Plugin> out = new ArrayList<>();
        for (FilterPlugin p : filters()) {
            if (p.isNone() || Prefs.pluginOn(ctx, p.id())) out.add(p);
        }
        for (FeaturePlugin p : features()) {
            if (Prefs.pluginOn(ctx, p.id())) out.add(p);
        }
        out.addAll(themes());
        return out;
    }

    /** 按 id 在全量注册表中查找；找不到返回 null。 */
    public static Plugin byId(String id) {
        if (id == null) return null;
        for (Plugin p : all()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** 全部插件（滤镜 + 功能 + 主题）。 */
    public static List<Plugin> all() {
        List<Plugin> out = new ArrayList<>();
        out.addAll(filters());
        out.addAll(features());
        out.addAll(themes());
        return out;
    }

    private static List<FeaturePlugin> buildFeatures() {
        List<FeaturePlugin> f = new ArrayList<>();
        f.add(DateStampFeature.instance());
        return Collections.unmodifiableList(f);
    }
}