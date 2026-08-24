# Checklist — rebuild-allround-camera

- [x] 应用显示名为「全能相机」，versionName 含 BETA、versionCode=4
- [x] colors/themes/drawable 设计系统就位（色板、圆角、快门样式、图标按钮背景）
- [x] 相机界面重写：顶部图标工具栏 + 底部控制栏（大圆形快门、拍照/超分模式切换、EV、延时）齐备
- [x] 拍摄模式切换可用：拍照 / 超分主按钮各自执行正确动作（定时/取消不回归）
- [x] 网格样式可选（关/九宫格/黄金分割）并在相机即时生效
- [x] 相机界面右下角最近照片缩略角标存在且点击进入相册
- [x] 保存成功使用统一轻提示
- [x] 设置页为分组卡片布局且含「网格样式」选项，选择持久化
- [x] 相册页、结果页与新设计一致
- [x] 原有功能（对焦、变焦、EV、闪光、前后摄、连拍进度/取消、相册、对比、更新检查）全部保留且可构建
- [x] `./gradlew assembleRelease` 成功，产出 BETA APK 并放入 `dist/`（构建期产物不入库）
- [x] 源码推送 GitHub，BETA Release 附 APK，version.json 已更新且更新 URL 可达
- [x] README 更新为「全能相机」与 BETA 版本信息