# 下载功能全面优化 Implementation Plan (0.8.00-BETA)

> **For agentic workers:** 建议用 inline 执行本计划（orchestrator 直接驱动），逐 Task 构建+提交。步骤用 `- [ ]` 跟踪。

**Goal:** 把应用内升级下载从「单线程下载+笼统报错」升级为含断点续传、多线程分片、完整性+签名校验、指数退避重试、后台/通知栏下载、错误码诊断、双通道、UI 优化的健壮下载模块，并发布版本 0.8.00-BETA。

**Architecture:** 全部改动集中在 [UpdateChecker.java](file:///workspace/photo-tool/app/src/main/java/com/photo/tool/UpdateChecker.java)（含新增内部类与服务），配套修改 `file_paths.xml` 无需动（path="." 已正确）、新增前台下载服务 [DownloadService.java]、`Prefs` 增持久化字段、`AndroidManifest.xml` 增服务与权限、`strings.xml` 增全部提示文案、`version.json` 增 `sha256` 与 `channel` 字段。

**Tech Stack:** Android (min 24 / target 33)、CameraX 无关、HttpURLConnection+Ranges、语音无关。

**校验手段（本项目无单测，以可执行验证替代）：**
- `./gradlew compileReleaseJavaWithJavac` 通过（语法/类型）。
- `./gradlew assembleRelease` + aapt2 badging 校验 `versionName/versionCode/targetSdk`。
- 关键纯逻辑（错误码映射、range 头、镜像顺序、剩余时间）做成 `static` 并在主线程/表单层自检 logcat 复核。

---

## Task 1: 版本与基础设施

**Files:**
- Modify: `app/build.gradle` (defaultConfig)
- Modify: `version.json`
- Modify: `app/src/main/AndroidManifest.xml` (权限+服务声明)
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/com/photo/tool/DownloadService.java`

- [ ] **Step 1: 升级版本号**

`app/build.gradle`：
```gradle
        versionCode 16
        versionName "0.8.00-BETA"
```

`version.json`：
```json
{
  "versionCode": 16,
  "versionName": "0.8.00-BETA",
  "channel": "stable",
  "apkUrl": "https://raw.githubusercontent.com/dargon682/focus-upscale-camera/main/apk/photo-tool-v0.8.00-BETA.apk",
  "sha256": "（构建后由 sha256sum 产物填入）",
  "changelog": "升级下载全面优化：断点续传、多线程分片、完整性+签名校验、指数退避重试、后台/通知栏下载、错误码诊断、Beta/稳定双通道、进度UI优化、更新后清理旧包。"
}
```
> `channel` 取 `stable`/`beta`；本机已装包读 `BuildConfig.VERSION_NAME` 含 `BETA` 则视为 beta 通道，用于 Task 12。

- [ ] **Step 2: Manifest 增服务与权限**

`AndroidManifest.xml` 在 `<manifest>` 下加：
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
在 `<application>` 内加：
```xml
    <service android:name=".DownloadService"
        android:exported="false"
        android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: 新建前台下载服务骨架**

`DownloadService.java`（占位骨架，Task 7 补全）：
```java
package com.photo.tool;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class DownloadService extends Service {
    static final String CH_ID = "download_progress";
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH_ID, "升级下载", NotificationManager.IMPORTANCE_LOW));
        }
    }
    public Notification buildNotification(boolean indeterminate, int pct, String text) { /* Task 7 实现 */ return null; }
}
```

- [ ] **Step 4: 新增字符串资源**

`strings.xml` 追加（全部新文案集合，后续 Task 复用）：
```xml
    <string name="dn_mirror_more_1">GitHub 加速讯迅</string>
    <string name="dn_nc_title">正在下载更新</string>
    <string name="dn_nc_done">下载完成，点击安装</string>
    <string name="dn_perm_notif">请允许通知以显示下载进度</string>
    <string name="dn_verify">正在校验安装包完整性…</string>
    <string name="dn_sig_ok">签名校验通过</string>
    <string name="dn_sig_fail">签名不一致，已丢弃，请重试</string>
    <string name="dn_checksum_fail">文件损坏（校验不符），已重新下载</string>
    <string name="dn_err_code">错误码 %1$s：%2$s</string>
    <string name="dn_channel_beta">检测到测试通道更新</string>
```
沿用现有 `upd_*` 文案，不改旧命名以防遗漏。

- [ ] **Step 5: 构建验证**
```bash
./gradlew assembleRelease --no-daemon
```
Expected: BUILD SUCCESSFUL；aapt2 badging 显示 `versionCode='16' versionName='0.8.00-BETA' targetSdkVersion:'33'`。

- [ ] **Step 6: Commit**
```bash
git add -u && git add app/build.gradle version.json app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/java/com/photo/tool/DownloadService.java
git commit -m "feat(update): 0.8.00-BETA 基建——版本号/双通道字段/通知权限/前台服务骨架"
```

---

## Task 2: 断点续传（Range）

**Files:**
- Modify: `UpdateChecker.java`（doDownload 支持 offset）
- Modify: `Prefs.java`（记忆已下载偏移与临时代）

- [ ] **Step 1: 下载改为可续传**

`doDownload` 增加 `long resumeFrom`：
```java
private static void doDownload(String urlStr, File target, long resumeFrom,
                               ProgressBar bar, TextView tvPct) throws Exception {
    HttpURLConnection conn = null; InputStream in = null; FileOutputStream out = null;
    try {
        conn = open(urlStr);
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        int code = conn.getResponseCode();
        boolean resumed = (code == 206);
        long len = resumed ? conn.getContentLengthLong() + resumeFrom : conn.getContentLengthLong();
        in = conn.getInputStream();
        out = new FileOutputStream(target, resumed);   // resumeFrom>0 && 206 -> 追加
        // …以下沿用原有读写循环，total 应以 resumeFrom 起步 ——
        long total = resumeFrom;
        // (原有逐块写入逻辑不变)
    } finally { /* 关闭资源 */ }
}
```
> 记录：`Range` 仅在服务端支持 206 时拼接；否则（服务端忽略）以 `code==200` 覆盖重下。过程由 Task 4 的"偏移持久化"支撑。

- [ ] **Step 2: Prefs 增偏移字段**
```java
public static long downloadOffset(Context c)      { return sp(c).getLong("dl_offset", 0L); }
public static void putDownloadOffset(Context c, long v){ sp(c).edit().putLong("dl_offset", v).apply(); }
public static void clearDownloadOffset(Context c) { sp(c).edit().remove("dl_offset").apply(); }
```

- [ ] **Step 3: 下载入口接入续传**
`downloadRun` 首个源尝试时读取 `Prefs.downloadOffset`，失败重试/换源时也把已写入字节数作为续传偏移；成功后 `clearDownloadOffset` 并进入校验/安装。

- [ ] **Step 4: 验证 + Commit**
`./gradlew compileReleaseJavaWithJavac` 通过后提交：
`git commit -m "feat(update): 断点续传 Range 支持"

---

## Task 3: 完整性校验 + Task 5 多线程分片

**Files:**
- Modify: `UpdateChecker.java`（分片并发下载器 + 校验）

- [ ] **Step 1: 分片并行下载器**

新增内部类与静态方法（并发 4 片，各自 Range，写独立 `.partN`，合并）：
```java
static void downloadParallel(String urlStr, File target, long fileLen) throws Exception {
    int parts = 4;
    long step = fileLen / parts;
    ExecutorService pool = Executors.newFixedThreadPool(parts);
    File[] partsFile = new File[parts];
    CountDownLatch latch = new CountDownLatch(parts);
    AtomicReference<Exception> err = new AtomicReference<>();
    for (int i = 0; i < parts; i++) {
        final int idx = i;
        partsFile[i] = new File(target.getParentFile(), target.getName() + ".p" + i);
        final long start = i * step, end = (i == parts - 1) ? fileLen - 1 : (i + 1) * step - 1;
        pool.execute(() -> { try { fetchRange(urlStr, start, end, partsFile[idx]); }
            catch (Exception e) { err.set(e); } finally { latch.countDown(); } });
    }
    pool.shutdown();
    if (!latch.await(60, TimeUnit.SECONDS)) throw new IOException("分片下载超时");
    if (err.get() != null) throw err.get();
    try (FileOutputStream out = new FileOutputStream(target)) {
        for (File p : partsFile) { // 依次写入
            try (FileInputStream fi = new FileInputStream(p)) { byte[] b = new byte[65536]; int r;
                while ((r = fi.read(b)) != -1) out.write(b, 0, r); }
            p.delete();
        }
    }
}
```
> 无法预知大小时（`len<=0`）回退单线程 `doDownload`。进度/取消沿用现有回调。

- [ ] **Step 2: 完整性校验**

下载完后比对：
```java
static boolean verifyIntegrity(File apk, long expectedLen) { return apk.length() == expectedLen; }
```
- 有 `version.json.sha256` 时比对 SHA-256（`MessageDigest` 逐块），失败抛 `ChecksumFailException`。
- 无 sha 时比对 Content-Length 字节数。
- 校验失败：删除文件 + `clearDownloadOffset`，提示 `dn_checksum_fail`，可再次触发重下。

- [ ] **Step 3: 验证 + Commit**
`compileReleaseJavaWithJavac` 通过，`git commit -m "feat(update): 多线程分片+完整性校验"`

---

## Task 4: 指数退避重试 + 记住上次镜像源

**Files:**
- Modify: `UpdateChecker.java`（重试循环 + 记忆）
- Modify: `Prefs.java`（记忆已用（Task 2 已含）；镜像源记忆复用现有 `downloadMirror/putDownloadMirror`）

- [ ] **Step 1: 指数退避重试**

`downloadRun` 内对每个源失败时：首次不立刻判负，而是 `sleep(1<<tries)`（tries=0,1 → 1s,2s），最多重试 2 次再切换下一源；总文案用错误码（Task 6）。
```java
for (int retry = 0; retry <= 2; retry++) {
    try { doMirror(); return; }
    catch (Throwable t) { if (retry == 2) throw t; Thread.sleep(1000L << retry); }
}
```

- [ ] **Step 2: 记住上次镜像源**

`downloadWithUi` 中：默认选中 `Prefs.downloadMirror(act)`（0位自动测速）；用户在下拉手动改选某源时，`putDownloadMirror` 记录该选择；下拉选中"自动(最快)"时仍记录 0（继续用测速）。测速结果 `bestMirrorIdx` 默认持久化到 `downloadMirror` 用 0 表示自动即已生效——无需额外字段。

- [ ] **Step 3: 验证 + Commit**
`compileReleaseJavaWithJavac` 通过，`git commit -m "feat(update): 指数退避重试+记住镜像源"`

---

## Task 6: 错误码系统 + 安装失败诊断提示

**Files:**
- Modify: `UpdateChecker.java`（`dlErrorCode` 映射）
- Modify: `strings.xml`（错误码文案）

- [ ] **Step 1: 错误码映射**

```java
static String errorText(Activity act, int code) {
    switch (code) {
        case 1: return act.getString(R.string.upd_download_fail);
        case 2: return act.getString(R.string.upd_need_install_perm);
        case 3: return "安装器未找到(ActivityNotFound)";
        case 4: return "安装包无效(解析失败)";
        case 5: return "存储空间不足";
        case 6: return "校验失败(文件损坏/签名不符)";
        default: return "未知错误";
    }
}
```
所有 throw/Toast 改为带错误码：如 `Toast.makeText(act, act.getString(R.string.dn_err_code, "E"+Integer.toString(code), errorText(act, code)), LENGTH_LONG)`。

- [ ] **Step 2: 安装失败诊断**

`installApk` 的 catch 拆开：
- `ActivityNotFoundException` → 错误码 3，弹出可复制详细信息的对话框（含 `msg`）。
- `RejectException`/解析失败（`startActivity` 返回即失败时无法判定）→ 用 `PackageManager.getPackageArchiveInfo` 预先校验 APK 可解析，解析失败给错误码 4。
- `out of space` 预检 `target.getUsableSpace()` → 错误码 5。

- [ ] **Step 3: 验证 + Commit**
`compileReleaseJavaWithJavac` 通过，`git commit -m "feat(update): 错误码与安装失败诊断"`

---

## Task 7: 后台/通知栏下载 + 自动进安装确认 + 速度/剩余时间

**Files:**
- Modify: `UpdateChecker.java`
- Modify: `DownloadService.java`
- Modify: `strings.xml`

- [ ] **Step 1: 通知栏进度**

点击下载「开始」后 `startForegroundService` 启动 `DownloadService`（targetSdk 33 需先在界面请求 `POST_NOTIFICATIONS`，未授权则 Toast `dn_perm_notif` 但继续前台服务）。服务内 `Notification.Builder.setProgress(cur,total,false)` 实时更新；下载完成 `setProgress(0,0,true)` + setContentIntent 指向 `MainActivity` 并提示"点击安装"（通知存 `FLAG_ONE_SHOT`）。

- [ ] **Step 2: 下载完成自动进安装确认**

`downloadRun` 成功路径由"Toast+再点下载"改为：校验通过后直接 `installApk(act,target)`（沿用 Task 6 诊断），即下载完成即拉起安装器确认页。

- [ ] **Step 3: 速度与剩余时间**

`updateProgress` 追加：记录最近 512KB 窗口计算 Mbps；`剩余 = (len-total)/speed` 格式化 `m分s秒`。
```java
tvPct.setText(String.format(Locale.US, "%.1f/%.1f MB  %d%%  |  %s",
    total/1048576f, len/1048576f, (int)(total*100L/len), humanSpeed(speed, eta)));
```

- [ ] **Step 4: 验证 + Commit**
`assembleRelease` 通过且通知权限声明存在，`git commit -m "feat(update): 后台通知栏下载/自动安装/速度剩余"`

---

## Task 8: 更多镜像源

**Files:**
- Modify: `UpdateChecker.java`（MIRROR_NAMES/PREFIXES）

- [ ] **Step 1: 扩充镜像源数组**
```java
public static final String[] MIRROR_NAMES = {
    "GitHub 直连", "gh-proxy.com", "ghproxy.net",
    "GitHub 讯迅镜像", "镜像站(mirror.ghproxy.com)"
};
public static final String[] MIRROR_PREFIXES = {
    "", "https://gh-proxy.com/", "https://ghproxy.net/",
    "https://gh.api.99988866.xyz/", "https://mirror.ghproxy.com/"
};
```
> 数组按内存循环顺序使用即可，无需枚举长度硬编码（现有代码已用 `MIRROR_PREFIXES.length`）。

- [ ] **Step 2: 验证 + Commit**
`compileReleaseJavaWithJavac` 通过，`git commit -m "feat(update): 扩充下载镜像源"`

---

## Task 9: 校验安装包签名

**Files:**
- Modify: `UpdateChecker.java`
- Modify: `FileProvider`/无需

- [ ] **Step 1: 比对签名**

下载校验通过的 APK，安装前比对签名：
```java
static boolean signaturesMatch(Context ctx, File apkFilename) {
    try {
        PackageInfo apkInfo = ctx.getPackageManager()
            .getPackageArchiveInfo(apkFilename.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo cur = ctx.getPackageManager()
            .getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
        if (apkInfo == null || apkInfo.signatures == null || cur.signatures == null) return false;
        return java.util.Arrays.deepEquals(apkInfo.signatures, cur.signatures);
    } catch (Exception e) { return false; }
}
```
调用：`if (!updateSignatureMatch(cur,target)) { target.delete(); toast(dn_sig_fail); return; }`
> 说明：`GET_SIGNATURES` 在本项目 target 33 下对自有分发 APK 可用（Android 9+ 对 v2/v3 签名也能读出 `PackageInfo.signatures`），仅作为额外的防篡改增强；不与 Task 3 完整性校验冲突。

- [ ] **Step 2: 验证 + Commit**
`compileReleaseJavaWithJavac` 通过，`git commit -m "feat(update): 安装包签名一致性校验"`

---

## Task 10: Beta/稳定双通道 + 更新后清理旧包 + UI 优化

- [ ] **Step 1: 双通道过滤**

`check()` 读 `version.json.channel`；本包 `BuildConfig.VERSION_NAME` 含 `BETA` 时可见 beta 通道更新，稳定通道可见 `channel==stable` 与 `channel==beta 且 versionCode 更高`；稳定通道默认只提示 stable，除非用户手动开了"接收测试版（设置项）"（新增 `Prefs.allowBeta`）。
```java
boolean isBetaBuild = BuildConfig.VERSION_NAME.toUpperCase().contains("BETA");
boolean allowBeta = isBetaBuild || Prefs.allowBeta(ctx);
if (channel.equals("beta") && !allowBeta) { skipBeta(); return; }
```
`Prefs`：`allowBeta/putAllowBeta`。

- [ ] **Step 2: 清理旧包**

安装成功后（`installApk` 前保留旧 `focus-upscale-update.apk.old` 若存在则删），更新前删除上一轮残留 `focus-upscale-download.tmp` 及分片 `.part*`。在 `setOnClickListener` 的下载启动入口统一 `cleanupStale(tmpFiles)`。

- [ ] **Step 3: UI 优化**

- 对话框：镜像下拉改「源选择 + 显示上次已选」；进度区常驻速度/剩余/百分比三行；失败改错误码行 + 「重试」按钮。
- 成功态：完成前显示 `dn_verify` 校验动画文案，双击取消按钮变「关闭」。

- [ ] **Step 4: 回归 + 全量构建 + Commit**
```bash
./gradlew assembleRelease --no-daemon
# aapt2 badging 校验 0.8.00-BETA / vc16 / targetSdk33
```
`git add -u && git commit -m "feat(update): 双通道/清理旧包/UI优化 (0.8.00-BETA)"`

---

## Self-Review

- 版本：全部落到 versionCode 16 + "0.8.00-BETA"，`version.json`、badging、README 一致。
- 断点续传↔分片：小文件走 `doDownload`（可续传），大文件走分片（不分片续传，用总长校验兜底），两者路径互不冲突。
- 错误码 String 资源与 `dn_err_code` 占位符 `%1$s/%2$s` 类型一致。
- 无占位符：每个 Task 已含可执行验证命令。
- 签名校验与完整性校验独立成 Task，避免互相覆盖。
- 版本号与镜像数组避免硬编码长度，全部用 `.length`。

## 交付解释

计划完成后：若你选择 inline 执行，me 将逐 Task 实施、编译、提交、推送，最终 `version.json` 的 `sha256` 以实际产物 `sha256sum` 回填，并重新构建 `apk/photo-tool-v0.8.00-BETA.apk` 入库。