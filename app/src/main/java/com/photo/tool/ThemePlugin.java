package com.photo.tool;

/** 主题换肤插件：提供一套可全局应用的主题 Style 资源。 */
public abstract class ThemePlugin extends Plugin {

    /** 主题资源 id（传给 Activity.setTheme，需在 super.onCreate 前调用）。 */
    public abstract int themedRes();

    @Override
    public final PluginKind kind() {
        return PluginKind.THEME;
    }

    @Override
    public final boolean canToggle() {
        return false;
    }
}