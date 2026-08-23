# Tasks

> 本清单对应 change-id：`rebuild-allround-camera`。实现阶段使用 sub-agent 按任务逐项落地，并在完成后勾选。

## Task 1: 品牌与版本重命名（依赖：无）
- [x] 1.1 将 `res/values/strings.xml` 的 `app_name` 改为「全能相机」（各语言无需多语言，统一中文）
- [x] 1.2 `app/build.gradle`：`versionName "2.1-BETA"`、`versionCode 4`
- [x] 1.3 同步 `version.json`（版本名 BETA、versionCode 4、apkUrl/更新地址对应 BETA release）
- [x] 1.4 更新 README 标题与项目说明（README 其余同步放 Task 7 发布循环；仓库描述随发布更新）

## Task 2: 设计系统（主题 / 色板 / 形状）
- [x] 2.1 重写 `res/values/colors.xml`：深色背景分层、主色/强调色、文本/分隔/成功色
- [x] 2.2 重写/扩展 `res/values/themes.xml`：统一根主题（状态栏/导航栏/背景），新增圆角、间距随用资源（dimens）
- [x] 2.3 新增 drawable：圆角卡片背景、胶囊/圆形按钮背景、快门环样式、工具栏图标背景等

## Task 3: 相机前端重写（CameraActivity + activity_camera.xml）
- [x] 3.1 重写 `activity_camera.xml`：全屏预览 + 顶部图标工具栏（切换/闪光/EV/定时/设置/相册）+ 底部分组控制栏（大圆形快门、拍照/超分模式切换、缩放提示）+ 对焦框/取景覆盖层 + 连拍进度与取消
- [x] 3.2 CameraActivity 代码适配：引用新控件 id、模式切换逻辑、最近照片缩略角标、保存成功轻提示、支持网格样式
- [x] 3.3 保留原有全部行为：对焦、变焦、EV、定时、连拍进度/取消、前后摄、闪光

## Task 4: 设置页重写与新增项
- [x] 4.1 重写 `activity_settings.xml`：分组卡片化（取景辅助 / 超分参数 / 应用 / 检查更新）
- [x] 4.2 SettingsActivity 适配新布局 id；新增「网格样式」下拉（关 / 九宫格 / 黄金分割 → `Prefs` 持久化）

## Task 5: 相册页与结果页重写
- [x] 5.1 重写 `activity_gallery.xml` + GalleryActivity 样式（深色网格、空态、标题栏与新设计一致）
- [x] 5.2 重写 `activity_result.xml` + ResultActivity 样式（对比/超分/原图按钮、保存、轻提示）

## Task 6: 拍摄能力增强与可维护性
- [x] 6.1 AidOverlayView 支持按 `Prefs` 绘制「关 / 九宫格 / 黄金分割」网格样式（默认九宫格，行为不回归）
- [x] 6.2 相机工具栏重构为图标控件，移除顶部默认大按钮堆叠
- [x] 6.3 清理重复/未用资源，统一通过 colors/dimens/drawable 引用

## Task 7: 构建、发布闭环
- [ ] 7.1 `./gradlew assembleRelease` 编译通过，产出 BETA APK 并置入 `dist/`
- [ ] 7.2 本地冒烟：资源引用、布局 id、字符串占位（`%1$s/%1$d`）无缺失；ProGuard release 可构建
- [ ] 7.3 推送 GitHub：提交源码、创建 BETA Release 附 APK、更新 `version.json` 并验证更新 URL 可达

# Task Dependencies
- Task 2 依赖 Task 1 的版本/命名上下文（可选，可并行）
- Task 3/4 依赖 Task 2 设计系统资源
- Task 5 依赖 Task 2（可并行于 3/4）
- Task 6 依赖 Task 3/4（网格样式与重构在同一界面联合修改）
- Task 7 依赖 Task 1-6（全部完成后统一构建与发布）