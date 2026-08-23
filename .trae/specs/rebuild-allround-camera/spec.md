# 全能相机（All-Round Camera）重品牌 + 前端重写 Spec

## Why
应用名/版本/视觉样式均为早期版本遗留，UI 用原生默认按钮「一列到底」，无统一配色、层级、圆角与图标，与「全能相机」的定位不符。需要一次整体重品牌并重写前端（Android 界面层），结合代码评估补充少量拍摄能力，保持功能不退化。

## What Changes
- **品牌重命名**：应用显示名「对焦超分相机」→「**全能相机**」；版本名称 → **BETA**（`versionName "2.1-BETA"`，`versionCode 4`）；`update.json`/README/仓库描述同步。
- **前端重写（重设计 Android UI）**：建立统一设计系统（深色主题 + 主/强调/背景色板 + 圆角形状 + 层级与间距），重写 4 个界面（相机、设置、相册、结果）的布局与样式，用图标化/圆形快门/分组卡片替代默认按钮堆叠。
- **新增拍摄能力**（评估增益、低风险）：
  - 拍摄模式切换（拍照 / 超分合一，底部模式选择器）
  - 网格构图样式选择（关 / 九宫格 / 黄金分割）
  - 最近照片缩略角标（点击直达相册）
  - 保存成功的轻提示
- **结构/可维护性**：`CameraActivity` 顶部按钮工具栏重构为图标控件；统一 `colors/themes/drawable` 资源；移除重复/未用资源；功能与算法（`SuperResolution` 等）**不改动行为**。
- **发布闭环**：重新构建 release APK（BETA），推送 GitHub 并新建 BETA Release，更新 `version.json`（含 BETA 版本号）。

## Impact
- 受影响规格：品牌、Android 前端（界面设计）、拍摄交互、自动更新。
- 受影响代码：
  - 布局：`res/layout/activity_camera.xml`、`activity_settings.xml`、`activity_gallery.xml`、`activity_result.xml`
  - 资源：`res/values/strings.xml`、`colors.xml`、`themes.xml`、`arrays.xml`、`res/drawable/*`
  - 代码：`CameraActivity.java`（模式/角标/工具栏重构）、`SettingsActivity.java`（网格样式项）、`AidOverlayView.java`（网格样式）、`Prefs.java`（新增设置持久化）、`ResultActivity.java`（保存提示）
  - 构建/发布：`app/build.gradle`（版本）、`version.json`、`README.md`

## ADDED Requirements

### Requirement: 品牌与版本重命名
系统 SHALL 将应用显示名改为「全能相机」，并将版本标识改为 BETA。
#### Scenario: 应用显示与版本
- **WHEN** 用户查看桌面图标与应用信息
- **THEN** 显示名为「全能相机」，版本号为 `2.1-BETA`（`versionCode 4`）

### Requirement: 前端可视化重写
系统 SHALL 建立统一的深色设计系统并重写全部四个界面，替换默认按钮堆叠，提供清晰的层级、间距与图标。
#### Scenario: 相机主界面
- **WHEN** 用户打开相机
- **THEN** 看到全屏预览、顶部图标工具栏、底部控制栏（大圆形快门、拍摄模式、EV、延时、变焦控制）
#### Scenario: 设置界面
- **WHEN** 用户打开设置
- **THEN** 看到分组卡片化布局（取景辅助 / 超分参数 / 应用），并新增「网格样式」选项

### Requirement: 拍摄模式切换
系统 SHALL 在底部提供「拍照 / 超分」模式选择，切换后主按钮执行对应动作。
#### Scenario: 切换模式
- **WHEN** 用户点选「超分」模式并按下主按钮
- **THEN** 执行多帧超分辨率拍摄（定时/取消逻辑保持不变）；选「拍照」则单张拍摄

### Requirement: 网格样式选择
系统 SHALL 支持「关 / 九宫格 / 黄金分割」三种取景网格，选择即时生效。
#### Scenario: 选择网格样式
- **WHEN** 用户在设置页切换网格样式
- **THEN** 相机取景覆盖层按所选样式绘制，且立即生效

### Requirement: 最近照片缩略角标
系统 SHALL 在相机界面右下角显示最近一张拍摄结果缩略图，点击进入相册。
#### Scenario: 查看最近照片
- **WHEN** 用户点击缩略角标
- **THEN** 打开历史相册页

## MODIFIED Requirements

### Requirement: 拍流保存提示
保存成功时在原 Toast 提示基础上统一为设计系统内的轻提示样式，文案与现有一致。

## REMOVED Requirements
无（本轮不删除任何既有功能；本次为纯增强与重写，行为保持兼容）。

## 假设
- 「版本号为 BATA」按 **BETA** 处理：`versionName = "2.1-BETA"`、`versionCode = 4`。
- 「重写前端」指 Android 原生界面层（Activity 布局/样式/资源）的重设计，不改变应用技术栈；本轮实现以 project 根目录 `/workspace/photo-tool` 为上下文。