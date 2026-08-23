# 拍照工具：二次对焦 + 多帧超分辨率合成

一个基于 Android CameraX 的拍照工具，支持自动对焦、点按二次对焦、单张拍照、多帧连拍超分辨率合成、前后摄切换与闪光灯控制，内置取景构图辅助（九宫格 / 水平仪）、数字变焦、画质档位、历史相册与原图对比，所有功能可通过设置页开关配置，并在设置页支持线上检查更新。

## 功能特性

- **自动对焦**：点击「自动对焦」按钮，以取景框中心为测光/对焦点触发自动对焦。
- **数字变焦**：双指捏合放大（至相机最大变焦），双击复位到 1×；变焦后拍照同样生效。
- **二次对焦（tap-to-focus）**：在预览画面上点按任意位置，即在该处重新对焦并显示对焦框动画（2 秒自动消除，对焦成功有 Toast 提示）。
- **单张拍照**：以最高质量模式采集单帧，预览并保存。
- **连拍进度与取消**：多帧连拍时底部实时显示「捕捉中 n/总帧」，任意时刻可点「取消连拍」中止。
- **多帧超分辨率合成**：连拍 N 帧（可调），经亚像素配准、亚像素交错双三次放大、多帧平均降噪、可调 Unsharp Mask 锐化，输出 `scale` 倍（2×/3×）超分结果。
- **拍摄能力增强**：前后摄像头切换、闪光灯（关 / 自动 / 开）循环切换。
- **画质档位**：采样分辨率三档可选（流畅 960 / 均衡 1200 / 清晰 1440），影响单帧采样宽度与合成耗时，受内存保护约束。
- **取景构图辅助**：九宫格构图线 + 加速度传感器水平仪，辅助取景构图与端平。
- **原图 / 超分对比**：结果页支持分割线滑块对比、仅显超分 / 仅显原图三种模式，双指缩放、单指平移。
- **历史相册**：相册页浏览本应用已保存到 `Pictures/FocusUpscale` 的全部结果图（网格缩略图 + 点击全图查看）。
- **设置页**：一键开关上述功能，并调整超分倍数、连拍帧数、锐化强度、采样画质档位。
- **检查更新**：设置页「检查更新」从 GitHub 拉取 `version.json`，有新版本即以系统下载器下载并引导安装。
- **相册保存**：结果以 PNG 写入系统相册 `Pictures/FocusUpscale` 目录（Android 10+ 走 MediaStore，无需存储权限）。

## 技术栈

| 项 | 说明 |
| --- | --- |
| 语言 | Java 17 |
| 相机框架 | Android CameraX（camera-core / camera-camera2 / camera-lifecycle / camera-view 1.4.1） |
| UI | 原生 View + XML 布局（无 AppCompat 依赖，自实现 `LifecycleOwner`） |
| 构建 | Gradle 8.14.3 + Android Gradle Plugin |
| 最低系统 | Android 7.0（minSdk 24） |

## 项目结构

```
photo-tool
├── README.md                          # 项目说明
├── version.json                       # 在线更新清单（GitHub raw）
├── dist/app-release-v2.0.apk          # 已签名的 release 安装包（同时随 GitHub Release 分发）
└── app/src/main
    ├── AndroidManifest.xml              # 权限声明、Activity 注册
    ├── java/com/photo/tool
    │   ├── CameraActivity.java          # 相机预览、对焦、拍照、变焦、连拍、超分流程、传感器水平仪
    │   ├── SuperResolution.java         # 多帧超分辨率合成算法（亚像素配准 / 流式处理 / 可调参数）
    │   ├── Prefs.java                   # 设置持久化 + 超分内存保护估算
    │   ├── UpdateChecker.java           # 线上检查更新（version.json + DownloadManager）
    │   ├── SettingsActivity.java        # 设置页（功能开关 + 超分参数 + 画质档位 + 检查更新）
    │   ├── SettingRow.java              # 设置行控件（标签 + 开关）
    │   ├── AidOverlayView.java          # 取景辅助覆盖层（九宫格 + 水平仪）
    │   ├── CompareView.java             # 原图/超分对比视图（分割线滑块 + 缩放平移）
    │   ├── GalleryActivity.java         # 历史相册页（网格缩略图 + 全图查看）
    │   └── ResultActivity.java          # 结果展示、对比与保存相册
    └── res
        ├── layout/activity_camera.xml   # 相机界面（预览 + 对焦框 + 工具栏 + 连拍进度/取消）
        ├── layout/activity_result.xml   # 结果比对与保存界面
        ├── layout/activity_settings.xml # 设置界面
        ├── layout/activity_gallery.xml  # 历史相册界面
        ├── values/arrays.xml            # 倍率/帧数/锐化/画质 下拉项
        ├── drawable/bg_focus_box.xml    # 对焦框样式
        ├── drawable/ic_launcher.xml     # 应用图标
        └── values/                      # 字符串、颜色、主题
```

## 核心流程

### 二次对焦（CameraActivity）

- 自动对焦：对预览中心生成 `MeteringPoint`，通过 `CameraControl.startFocusAndMetering(FocusMeteringAction.FLAG_AF)` 触发。
- 点按二次对焦：监听 `PreviewView` 触摸事件，将触摸坐标经 `MeteringPointFactory` 转为测光点，设置 2 秒自动取消时长后触发对焦，成功后 Toast 提示。

### 多帧超分辨率合成（SuperResolution）

1. **亚像素配准**：以中间帧为参考，先 4 倍下采样粗搜 ±8 整数平移，再整幅 ±2 细搜，最后对 SSD 代价做三段抛物线拟合，求得**亚像素级**平移量。
2. **亚像素交错放大**：对每帧用 Catmull-Rom 双三次插值放大 `scale` 倍，并按亚像素位移偏移到高分辨率网格——不同帧落在网格的不同亚像素位置，形成真正意义的超分辨率交叠采样而非普通平均。
3. **累积平均**：对同一高分辨率位置多次采样取均值，抑制噪声、填补插值无法覆盖的高频细节。
4. **锐化**：做盒子模糊取低频，按可调 `Unsharp Mask`（amount 0.4 ~ 1.4）提升高频细节。

连拍间隔约 180ms（单帧失败自动重试 2 次，避免个别机型快速连拍偶发失败），以获取散落的亚像素位移帧。合成采用**流式处理**：逐帧解码→亚像素配准→累加→立即回收，全程仅持有参考帧 + 当前帧，避免同时加载 N 帧导致 OOM；输入采样宽度受设备堆内存与超分倍数共同约束（见下方「内存保护」）。当相机几乎无位移时退化为"放大 + 降噪 + 锐化"。

### 设置项与内存保护（Prefs）

- 功能开关：九宫格、水平仪、原图对比（默认开）。
- 超分参数：倍数（2×/3×）、连拍帧数（5/7/9）、锐化强度（柔和/适中/强烈），均持久化于 `SharedPreferences`。
- `safeSampleWidth(heapMb, scale, default)`：按峰值约 24B/输出像素、堆内存 55% 预算估算可承受的输出像素规模，动态收紧单帧采样宽度，从根源上避免低端机在超分分配大数组时 OOM（此前该项曾被低估导致"超分合成失败"）。

### 取景辅助（AidOverlayView + 传感器）

- 九宫格：三等分构图线，取材对齐的常用辅助线。
- 水平仪：注册 `TYPE_ACCELEROMETER` 监听，以 `atan2(gx, -gy)` 计算横向倾角，旋转水平线，接近水平（±6°）时变绿提示「水平」。

### 原图 / 超分对比（CompareView）

- 结果页以超分图为基准加载同帧采样原图，提供 **分割对比 / 仅超分 / 仅原图** 三种模式。
- 对比模式拖拽中央手柄白线即可左右滑动对比；三种模式均支持双指缩放（1×~8×）与单指平移，便于观察细节差异。
- 是否默认进入对比模式受设置中「原图 / 超分对比」开关控制；单张拍照则仅显示原图。

## 构建与打包

在项目根目录下执行：

```bash
./gradlew assembleRelease
```

产出已签名 APK：`app/build/outputs/apk/release/app-release.apk`（约 0.95 MB，release 启用了 R8 代码压缩与资源收缩，较 debug 的 ~4.3MB 显著减少）。

> 说明：沙箱/容器环境构建若遇到 JDK 17 `CgroupV2Subsystem` 空指针问题，已在 `gradle.properties` 中通过 `org.gradle.jvmargs` 追加 `-XX:-UseContainerSupport` 规避。

## 权限说明

- `android.permission.CAMERA`：调用摄像头（运行时申请，必需）。
- `android.permission.WRITE_EXTERNAL_STORAGE`（maxSdkVersion 28）：仅 Android 10 以下保存结果到相册使用。