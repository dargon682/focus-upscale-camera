# 插件接入文档

本应用提供**全方面（滤镜 · 功能 · 主题）可扩展插件系统**。开发者无需改动主流程，即可：
- **滤镜特效**：新增一款像素级滤镜，结果页即时挑选应用；
- **功能扩展**：在既定接入点注入新能力（如保存前叠加日期水印）；
- **主题换肤**：提供一套全局 UI Token 主题，一键整体换肤。

> 适用版本：v0.8.40 及以上；实现类位于 `app/src/main/java/com/photo/tool/`。

---

## 1. 架构总览

```
                       ┌─ FilterPluginRegistry（内置滤镜登记）
PluginRegistry ────────┼─ features（内置功能插件清单）
（全类型注册表）        └─ themes（内置主题插件清单）
        │
        ├── Prefs.pluginOn(switch) / Prefs.themeId(单选)
        │
        ├── ResultActivity  滤镜条 + 保存钩子(onSave)
        ├── SettingsActivity 插件卡片（功能开关 / 主题单选 / 滤镜开关）
        └── ThemeManager → setTheme 切换全局主题
```

| 类型 `PluginKind` | 抽象基类 | 职责 |
| --- | --- | --- |
| `FILTER` | `FilterPlugin` | 像素级滤镜/特效 |
| `FEATURE` | `FeaturePlugin` | 应用功能扩展 |
| `THEME` | `ThemePlugin` | UI 主题换肤 |

各基类都继承自 `Plugin`（提供 `id()` / `name()` / `kind()` / `canToggle()`），
并统一经 `PluginRegistry` 登记、在设置页「插件」卡片统一管理。

---

## 2. 接入一个「滤镜插件」

在 `FilterPluginRegistry.buildBuiltins()` 末尾追加一个 `new FilterPlugin() { ... }`：

```java
list.add(new FilterPlugin() {
    @Override public String id()   { return "invert"; }   // 全局唯一
    @Override public String name() { return "反转色"; }    // 展示名
    @Override public Bitmap apply(Bitmap src) {
        int[] p = pixels(src);                            // 读 ARGB 数组
        for (int i = 0; i < p.length; i++) {
            int a = (p[i] >>> 24) & 0xff;
            p[i] = (a << 24) | ((255 - ((p[i] >> 16) & 0xff)) << 16)
                 | ((255 - ((p[i] >> 8)  & 0xff)) << 8) | (255 - (p[i] & 0xff));
        }
        return put(src, p);                               // 回写新位图
    }
});
```

工具：`pixels` / `put` / `copy`，默认回退 `ID_NONE` 表示不处理。

---

## 3. 接入一个「功能插件」

继承 `FeaturePlugin`，覆盖 `description()` 与所需钩子（目前提供保存钩子 `onSave(Bitmap, Context)`），
然后在 `PluginRegistry.buildFeatures()` 登记。内置示例「日期水印」：

```java
public final class DateStampFeature extends FeaturePlugin {
    public static final String ID = "date_stamp";
    @Override public String id()          { return ID; }
    @Override public String name()        { return "日期水印"; }
    @Override public String description() { return "保存时在右下角叠加时间戳"; }

    @Override public Bitmap onSave(Bitmap src, Context c) {
        if (!Prefs.pluginOn(c, id())) return src;       // 遵循用户开关
        Bitmap out = src.copy(src.getConfig(), true);   // 绘制在副本上
        Canvas canvas = new Canvas(out);
        // ...drawText...
        return out;                                     // 主程序负责回收
    }
}
```

> 主程序在 `ResultActivity.saveToGallery()` 保存前依次调用所有功能插件 `onSave`，
> 返回值即落盘位图；可用 `Bitmap src` 原样返回以跳过本次处理。

---

## 4. 接入一个「主题插件」

继承 `ThemePlugin`，实现 `themedRes()` 返回一个 Style 资源，并在 `PluginRegistry` 登记并以
`Prefs.putThemeId` 选中。主题 Style 通过 `res/values/attrs.xml` 提供的 Token 赋值布局颜色：

```xml
<style name="AppThemeOcean" parent="android:Theme.Black.NoTitleBar.Fullscreen">
    <item name="tokWindowBg">#062B3A</item>
    <item name="tokCardBg">#0E3A4E</item>
    <item name="tokTextPrimary">#EAF6FB</item>
    <item name="tokTextSecondary">#9FC3D0</item>
    <item name="tokTextOnAccent">#FFFFFF</item>
    <item name="tokDivider">#1E4D60</item>
    <item name="tokAccent">#FFC93C</item>
    <item name="tokAccentSoft">#26FFC93C</item>
    <item name="tokDanger">#FF5A5F</item>
</style>
```

```java
public static final ThemePlugin THEME_OCEAN = new ThemePlugin() {
    @Override public String id()     { return "theme_ocean"; }
    @Override public String name()   { return "海洋主题"; }
    @Override public int themedRes() { return R.style.AppThemeOcean; }
};
```

默认内置两套：暗色 `AppTheme`（默认）、亮色 `AppThemeLight`。

---

## 5. 内置插件清单

| 类别 | id | 名称 | 说明 |
| --- | --- | --- | --- |
| 滤镜 | `none` | 无滤镜 | 直出，恒不可关闭 |
| 滤镜 | `bw` | 黑白 | 灰度 |
| 滤镜 | `sepia` | 复古 | sepia 棕色调 |
| 滤镜 | `cool` | 冷色调 | 偏蓝 |
| 滤镜 | `warm` | 暖色调 | 偏黄 |
| 滤镜 | `soft` | 柔和 | 低对比提亮 |
| 滤镜 | `vivid` | 鲜艳 | 增强对比/饱和 |
| 功能 | `date_stamp` | 日期水印 | 保存叠加时间戳 |
| 主题 | `theme_dark` | 暗色主题 | 默认 |
| 主题 | `theme_light` | 亮色主题 | 经典浅色 |

---

## 6. 设置与展示接入点

- **设置页**「插件（滤镜 · 功能 · 主题）」卡片：功能插件与滤镜插件以开关行展示（`SettingRow`），主题以单选按钮展示，点击主题即时 `recreate()` 换肤。
- **结果页**：进入时自动应用 `Prefs.currentFilter` 选中滤镜；保存时经 `onSave` 钩子执行功能插件。
- **主题生效范围**：设置页、结果页、相册页随主题 Token 全局换肤；相机取景区因取景可读性保持深色专业基调（不变随亮色主题）。

---

## 7. 关键约定

- **内存**：`apply` / `onSave` 正常情况下返回全新位图；主程序在替换/写盘后回收。**严禁在插件内 recycle 入参位图**；异常回退原样返回 `src`。
- **线程**：滤镜在结果页后台单线程执行；功能插件 `onSave` 在主线程调用，需保证轻量。
- **开关**：滤镜/功能开关用 `Prefs.pluginOn(id)`（键 `plugin_on_<id>`）；主题为单选，用 `Prefs.themeId`。
- **自包含**：插件实例应无状态，不要保存跨调用可变字段。

---

*疑问可提 Issues；总览见 `README.md`。*