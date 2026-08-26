# 滤镜插件接入文档

本应用提供了**内置可扩展的滤镜/特效插件系统**。开发者无需改动相机主流程，即可往应用新增一款像素级滤镜，并在结果页与设置页自动生效。

> 适用版本：v0.8.30 及以上；对应实现类位于 `app/src/main/java/com/photo/tool/`。

---

## 1. 架构概览

```
CameraActivity/结果页(ResultActivity) ──> FilterPluginRegistry ──> List<FilterPlugin>
        │                                          │
        │ 预置开关(Prefs.pluginOn)                  │ 逐帧 apply(Bitmap)
        ▼                                          ▼
   滤镜选择条/设置页                           像素级特效处理
```

- **`FilterPlugin`**：滤镜抽象基类，定义插件唯一 `id()`、展示 `name()`、处理入口 `apply(Bitmap)`。
- **`FilterPluginRegistry`**：集中登记内置滤镜、按 id 查询、按启用状态过滤。
- **`Prefs`**：持久化每个插件的启用开关（键 `plugin_on_<id>`）与结果页当前选中的滤镜（`current_filter`）。
- **展示层**：`ResultActivity` 动态生成横向滤镜 chips；`SettingsActivity` 动态生成插件开关行。

> 插件的启用/停用不依赖任何注册中心之外的配置——新增一个类实例即可完成接入。

---

## 2. 四步接入一个新滤镜

### 第 1 步：在注册表登记

打开 `FilterPluginRegistry.java`，在 `buildBuiltins()` 中追加一个 `new FilterPlugin() { ... }`（`none` 为默认原图，不要动）。

### 第 2 步：实现三个抽象方法

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `id()` | `String` | 全宇宙唯一，小写下划线，如 `"vintage"`；用于持久化开关与查询。 |
| `name()` | `String` | 用户可见名称，如 `"复古"`；无需全局唯一。 |
| `apply(Bitmap)` | `Bitmap` | 返回处理后的位图；异常或无法处理时直接返回入参 `src`。 |

### 第 3 步：写像素处理（可选）

- `pixels(src)`：把位图读成 `int[]`（ARGB 8888），适合逐像素变换。
- `put(src, px)`：把处理完的 `int[]` 写回一张新位图并返回。
- `copy(src)`：生成同尺寸同配置的可写副本。

### 第 4 步：构建运行

结果页与设置页会自动从注册表发现新滤镜，无需再改展示层代码。

---

## 3. 集成示例（“反转色”滤镜）

```java
list.add(new FilterPlugin() {
    @Override public String id()   { return "invert"; }
    @Override public String name() { return "反转色"; }
    @Override public Bitmap apply(Bitmap src) {
        int[] p = pixels(src);
        for (int i = 0; i < p.length; i++) {
            int a = (p[i] >>> 24) & 0xff;
            int r = 255 - ((p[i] >> 16) & 0xff);
            int g = 255 - ((p[i] >> 8)  & 0xff);
            int b = 255 - (p[i] & 0xff);
            p[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return put(src, p);
    }
});
```

将上述 `list.add(...)` 追加到 `FilterPluginRegistry.buildBuiltins()` 返回列表 `list` 的末尾（即其他内置滤镜条目之后）即可。

---

## 4. 内置滤镜

| id | name | 效果 |
| --- | --- | --- |
| `none` | 无滤镜 | 原图直出（`apply` 直接返回 `src`，不可关闭） |
| `bw` | 黑白 | BT.601 标准灰度 |
| `sepia` | 复古 | 经典 Sepia 棕色调 |
| `cool` | 冷色调 | 降红绿、提蓝 |
| `warm` | 暖色调 | 提红绿、降蓝 |
| `soft` | 柔和 | 轻微降对比 + 提亮，胶片感 |
| `vivid` | 鲜艳 | 提高对比度与饱和度 |

---

## 5. 关键约定与注意事项

- **内存回收**：`apply` 正常情况下应返回**新位图**。展示层会在切换滤镜时回收上一张结果；若返回 `src` 本身（如 `none` 或异常回退），展示层不会回收 `src`，避免双重释放。**不要在插件内部 recycle 入参 `src`。**
- **像素格式**：`pixels/put/copy` 均按 ARGB8888 处理。颜色分量阈值务必运用 `clamp(v)`（0~255），避免溢出后 `int setPixels` 得到异常值。
- **后台线程**：滤镜在结果页由单线程执行器在后台执行，切勿在主线程做长循环；插件自身应是无状态的（不要持有跨调用状态）。
- **线性/伽马空间**：当前内置滤镜直接在 sRGB 整数域计算（简单、低开销）。对画面质量要求极高时，可先转线性空间再处理，但这会增加开销，普通滤镜不推荐。
- **开关持久化**：关闭某滤镜（设置页）后，结果页将不再展示该 chip；若结果页此前正使用已关闭的滤镜，会自动回退到“无滤镜”。

---

## 6. 效果覆盖范围

- **结果页**：进入后自动应用历史选择的滤镜，点任一 chip 即时替换效果，支持与原图/超分对比与保存。
- **设置页**：“滤镜插件（强大可扩展）”卡片中逐项列出（`none` 除外），可独立启用/停用。

---

*接入疑问可在本仓库 Issues 提出；索引见根目录 `README` 与 `TESTING.md`。*