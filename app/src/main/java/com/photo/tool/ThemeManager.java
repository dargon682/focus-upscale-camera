package com.photo.tool;

import android.content.Context;
import android.content.res.TypedArray;

/**
 * 主题管理器：根据 {@link Prefs#themeId} 解析当前主题插件的 Style 资源。
 * Activity 必须在 {@code super.onCreate} 之前调用 {@link #res(Context)} 并 setTheme，
 * 才能触发全局换肤生效。
 */
public final class ThemeManager {

    private ThemeManager() { }

    /** 当前主题的 Style 资源 id。 */
    public static int res(Context c) {
        ThemePlugin p = current(c);
        return p != null ? p.themedRes() : R.style.AppTheme;
    }

    /** 当前生效的主题插件实例。 */
    public static ThemePlugin current(Context c) {
        String id = Prefs.themeId(c);
        for (ThemePlugin p : PluginRegistry.themes()) {
            if (p.id().equals(id)) return p;
        }
        return PluginRegistry.THEME_DARK;
    }

    /** 切换主题并持久化（返回是否发生了变更）。 */
    public static boolean apply(Context c, String themeId) {
        String cur = Prefs.themeId(c);
        if (cur.equals(themeId)) return false;
        Prefs.putThemeId(c, themeId);
        return true;
    }

    /** 读取当前主题下的某个 Token 颜色（attrId 见 res/values/attrs.xml）。 */
    public static int color(Context c, int attrId) {
        TypedArray a = c.getTheme().obtainStyledAttributes(new int[]{attrId});
        int v = a.getColor(0, 0);
        a.recycle();
        return v;
    }
}