package com.photo.tool;

/**
 * 全方面插件系统根类型。所有插件（滤镜／功能／主题）统一继承本类，
 * 由 {@link PluginRegistry} 集中登记并在设置页「插件」入口统一展示与开关。
 */
public abstract class Plugin {

    /** 插件唯一标识（小写下划线，用于持久化开关）。 */
    public abstract String id();

    /** 插件展示名称。 */
    public abstract String name();

    /** 插件类别。 */
    public abstract PluginKind kind();

    /** 是否可在设置页独立开关（滤镜默认可开关；功能默认可开关；主题为单选不适用）。 */
    public boolean canToggle() {
        return kind() != PluginKind.THEME;
    }
}