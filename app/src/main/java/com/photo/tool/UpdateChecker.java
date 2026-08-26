package com.photo.tool;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 检查更新 + 应用内下载：
 * <p>启动时拉取远端 version.json（托管于 GitHub 仓库）。若 versionCode 大于本应用即提示，
 * 确认后可选择「下载镜像源」（GitHub 直连 / gh-proxy、ghproxy 加速镜像），
 * 下载过程在应用内对话框实时显示进度条与百分比，完成后通过 FileProvider 引导安装。</p>
 */
public final class UpdateChecker {

    /** 版本清单地址（GitHub raw），会依次遍历镜像源获取。 */
    static final String VERSION_JSON_PATH = "https://raw.githubusercontent.com/dargon682/focus-upscale-camera/main/version.json";

    /** 可选择的下载镜像源：前缀 + 原始 GitHub URL = 实际下载地址。 */
    public static final String[] MIRROR_NAMES = {
            "GitHub 直连",
            "gh-proxy.com 加速镜像",
            "ghproxy.net 加速镜像",
            "GitHub 讯迅镜像",
            "镜像站(mirror.ghproxy.com)"
    };
    public static final String[] MIRROR_PREFIXES = {
            "",                          // 直连
            "https://gh-proxy.com/",     // gh-proxy 加速
            "https://ghproxy.net/",      // ghproxy 加速
            "https://gh.api.99988866.xyz/",   // GitHub 讯迅镜像
            "https://mirror.ghproxy.com/"     // mirror.ghproxy 镜像
    };

    /** 测速基准文件（仓库内 1MB 随机样本，raw 地址；固定大小便于稳定测得各源真实带宽）。 */
    private static final String SPEED_BASE =
            "https://raw.githubusercontent.com/dargon682/focus-upscale-camera/main/benchmark/1mb.bin";

    /** 测速读取量：每源读取 1MB 计时。 */
    private static final int SPEED_READ_BYTES = 1_048_576;

    /** 进程内缓存的最快镜像索引（由 testSpeeds 更新）。 */
    private static volatile int bestMirrorIdx = 0;

    private static final ExecutorService exec = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean hasChecked = false;

    /** 速度估算：最近一次进度回调的时间戳与已下载字节（用于计算窗口内 Mbps）。 */
    private static long lastProgMs = 0;
    private static long lastProgTotal = 0;

    /** 每个镜像源失败后的最大重试次数（指数退避：1s、2s）。 */
    private static final int RETRY_TIMES = 2;

    /** 多线程分片下载并发片数。 */
    private static final int PARALLEL_PARTS = 4;

    /** 多线程分片的最小文件长度阈值（字节）；过小或未知(Content-Length<=0)回退单线程。 */
    private static final long PARALLEL_MIN_LEN = 24;

    private UpdateChecker() { }

    /** 是否已该进程检查过（避免启动弹更新多次）。 */
    public static boolean checkedThisSession() { return hasChecked; }

    /**
     * 后台检查更新，从各镜像源依次尝试拉取 version.json。
     * onFail：全部源都失败 / 网络失败；listener.onResult(versionName, changeLog, apkUrl, isLatest)
     */
    public static void check(final Context ctx, final Runnable onFail, final UpdateListener listener) {
        hasChecked = true;
        exec.execute(() -> {
            Throwable last = null;
            for (String pre : MIRROR_PREFIXES) {
                try {
                    JSONObject o = readJson(pre + VERSION_JSON_PATH);
                    int remoteCode = o.optInt("versionCode", 0);
                    String name = o.optString("versionName", "");
                    String apk = o.optString("apkUrl", "");
                    String change = o.optString("changelog", "");
                    // 双通道：channel=beta 且本机既非 BETA 包也未开启接收 Beta 时，跳过并不提示
                    String channel = o.optString("channel", "");
                    boolean isBetaInstalled = BuildConfig.VERSION_NAME.toUpperCase().contains("BETA");
                    if ("beta".equalsIgnoreCase(channel) && !isBetaInstalled && !Prefs.allowBeta(ctx)) {
                        main.post(() -> Toast.makeText(ctx, R.string.dn_channel_beta, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    final boolean latest = remoteCode <= BuildConfig.VERSION_CODE;
                    main.post(() -> listener.onResult(name, change, apk, latest));
                    return;
                } catch (Throwable t) {
                    last = t;
                }
            }
            final Throwable f = last;
            main.post(() -> { if (onFail != null) onFail.run(); });
        });
    }

    private static JSONObject readJson(String urlStr) throws Exception {
        HttpURLConnection conn = open(urlStr);
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** 建立连接（统一超时与 UA）。 */
    private static HttpURLConnection open(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("User-Agent", "FocusUpscale-Android/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    /** 弹更新对话框；用户确认则进入带镜像源选择与进度条的下载对话框。 */
    public static void prompt(Activity act, String versionName, String apkUrl, String changelog) {
        new AlertDialog.Builder(act)
                .setTitle(R.string.btn_check_update)
                .setMessage(act.getString(R.string.upd_found, versionName, changelog))
                .setPositiveButton(R.string.btn_download, (d, w) -> downloadWithUi(act, apkUrl))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 应用内下载：弹出对话框，提供镜像源下拉选择 + 实时进度条 + 取消按钮，
     * 下载完成通过 FileProvider 引导安装。
     */
    public static void downloadWithUi(final Activity act, final String apkUrl) {
        final File target = new File(act.getFilesDir(), "focus-upscale-update.apk");
        // 依据设置决定镜像：自动则用测速缓存的最快源
        final int mode = Prefs.downloadMirror(act);
        final int defSel = effectiveMirrorIndex(mode);
        buildDownloadDialog(act, apkUrl, target, defSel);
    }

    private static void buildDownloadDialog(final Activity act, final String apkUrl,
                                                   final File target, final int defSel) {
        // —— 构建下载对话框 ——
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTip = new TextView(act);
        tvTip.setText(R.string.upd_choose_mirror);
        tvTip.setTextSize(13f);
        root.addView(tvTip, lp(true));

        final Spinner spinner = new Spinner(act);
        ArrayAdapter<String> adp = new ArrayAdapter<>(act,
                android.R.layout.simple_spinner_item, MIRROR_NAMES);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adp);
        spinner.setSelection(Math.max(0, Math.min(MIRROR_NAMES.length - 1, defSel)));
        root.addView(spinner, lp(true));

        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = lp(true);
        barLp.topMargin = pad / 2;
        root.addView(bar, barLp);

        final TextView tvPct = new TextView(act);
        tvPct.setText(R.string.upd_waiting);
        tvPct.setGravity(Gravity.CENTER);
        tvPct.setTextSize(14f);
        LinearLayout.LayoutParams pctLp = lp(true);
        pctLp.topMargin = pad / 2;
        root.addView(tvPct, pctLp);

        // 下载失败时显示的「重试」按钮（默认隐藏，点击重新构建下载对话框）
        final Button retryBtn = new Button(act);
        retryBtn.setText(R.string.btn_retry);
        retryBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryLp = lp(true);
        retryLp.topMargin = pad / 2;
        root.addView(retryBtn, retryLp);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle(R.string.upd_download_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> currentCancel.set(true))
                .setCancelable(false)
                .create();

        retryBtn.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            currentCancel.set(false);
            buildDownloadDialog(act, apkUrl, target, defSel);
        });

        // 取消按钮文本在开始后改为“取消下载”
        dialog.setOnShowListener(d -> {
            Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            neg.setText(R.string.upd_cancel_download);
        });

        // —— 开始下载 ——
        exec.execute(() -> downloadRun(act, apkUrl, target, spinner, bar, tvPct, retryBtn, dialog));
        dialog.show();
    }

    /** 取消下载标记。 */
    private static final AtomicBoolean currentCancel = new AtomicBoolean(false);

    /** 根据设置模式解析最终镜像前缀：0 自动（取测速缓存的最快源）/ 1~3 手动。 */
    static String resolveMirrorPrefix(int mode) {
        int idx;
        if (mode == 0) {
            idx = bestMirrorIdx;
        } else {
            idx = mode - 1;
        }
        idx = Math.max(0, Math.min(MIRROR_PREFIXES.length - 1, idx));
        return MIRROR_PREFIXES[idx];
    }

    /** 当前实际采用的镜像下标（0 自动时为测得的最快源）。 */
    static int effectiveMirrorIndex(int mode) {
        return mode == 0 ? bestMirrorIdx : Math.max(0, Math.min(MIRROR_PREFIXES.length - 1, mode - 1));
    }

    /** 并发测试各镜像源下载速度（MB/s）；回调时已更新 bestMirrorIdx 为最快源。 */
    public static void testSpeeds(final Runnable onFail, final SpeedListener listener) {
        exec.execute(() -> {
            final int n = MIRROR_PREFIXES.length;
            final float[] mbps = new float[n];
            final boolean[] done = new boolean[n];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                new Thread(() -> {
                    mbps[idx] = measureSpeed(MIRROR_PREFIXES[idx] + SPEED_BASE);
                    done[idx] = true;
                    latch.countDown();
                }).start();
            }
            try {
                // 单个源连接上限 10s，整体最多等待 12s
                latch.await(12, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }

            int best = 0;
            for (int i = 1; i < n; i++) {
                if (mbps[i] > mbps[best]) best = i;
            }
            // 全部失败时保持原缓存
            boolean any = false;
            for (boolean d : done) if (d) { any = true; break; }
            if (any) bestMirrorIdx = best;

            final float[] speeds = mbps.clone();
            final int bestIdx = best;
            final boolean success = any;
            main.post(() -> {
                if (success && listener != null) listener.onResult(speeds, bestIdx);
                else if (onFail != null) onFail.run();
            });
        });
    }

    /** 测量单源速度：读取 SPEED_READ_BYTES 字节计时，返回 MB/s；失败返回 0。 */
    private static float measureSpeed(String urlStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = open(urlStr);
            in = conn.getInputStream();
            final byte[] buf = new byte[8192];
            long start = System.currentTimeMillis();
            long read = 0;
            int r;
            while (read < SPEED_READ_BYTES && (r = in.read(buf, 0, (int) Math.min(buf.length, SPEED_READ_BYTES - read))) != -1) {
                read += r;
            }
            long ms = System.currentTimeMillis() - start;
            if (ms <= 0) ms = 1;
            if (read <= 0) return 0f;
            return read / 1048576f / (ms / 1000f);
        } catch (Throwable t) {
            return 0f;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    /** 后台下载并实时回调进度；单个源失败自动切换至下一可用源直至成功或全部失败。 */
    private static void downloadRun(final Activity act, final String baseUrl,
                                    final File target, final Spinner spinner,
                                    final ProgressBar bar, final TextView tvPct,
                                    final Button retryBtn, final AlertDialog dialog) {
        currentCancel.set(false);
        lastProgMs = 0;
        lastProgTotal = 0;
        // 下载启动：清理上一轮残留分片/临时文件，并启动前台下载服务同步通知
        cleanupStale(target);
        act.startForegroundService(new Intent(act, DownloadService.class));
        notifyProgress(0, -1);

        final int sel = Math.max(0, Math.min(MIRROR_PREFIXES.length - 1, spinner.getSelectedItemPosition()));
        final int[] order = buildMirrorOrder(sel);

        main.post(() -> {
            spinner.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(true);
            tvPct.setText(R.string.upd_connecting);
        });

        Throwable last = null;
        for (int idx : order) {
            if (currentCancel.get()) {
                final DownloadService ds = DownloadService.running;
                if (ds != null) ds.endNotify();
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(act, R.string.upd_cancelled, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            final String urlStr = MIRROR_PREFIXES[idx] + baseUrl;
            // 每个源开始前读取断点续传偏移；失败指数退避重试2次(1s、2s)后再切换下一源
            try {
                boolean ok = false;
                for (int retry = 0; retry <= RETRY_TIMES && !ok; retry++) {
                    if (currentCancel.get()) throw new InterruptedDownload();
                    try {
                        doDownloadAny(urlStr, target, bar, tvPct, Prefs.downloadOffset(act));
                        ok = true;
                    } catch (Throwable t) {
                        last = t;
                        if (retry < RETRY_TIMES) {
                            final String miss = MIRROR_NAMES[idx];
                            main.post(() -> tvPct.setText(act.getString(R.string.upd_mirror_retry, miss)));
                            Thread.sleep(1000L << retry); // 1s、2s
                        }
                    }
                }
                if (!ok) continue;

                Prefs.clearDownloadOffset(act);
                final DownloadService dsRun = DownloadService.running;
                if (dsRun != null) {
                    dsRun.notifyText(0, act.getString(R.string.dn_verify));
                }
                main.post(() -> {
                    bar.setIndeterminate(true);
                    tvPct.setText(R.string.dn_verify);
                });
                verifyIntegrity(target, probeLength(urlStr));
                final File downloaded = target;
                main.post(() -> {
                    try {
                        final DownloadService dsDone = DownloadService.running;
                        if (dsDone != null) dsDone.doneNotify();
                        if (dialog != null && dialog.isShowing()) dialog.dismiss();
                        installApk(act, downloaded);
                    } catch (Throwable t) {
                        // 完成后任何未捕获异常（如后台启动安装器被系统拒绝）都不允许崩溃，
                        // 改为提示用户从通知栏手动点击安装。
                        toastError(act, 2);
                        showDiagnostic(act, t);
                        safeNotifyTap(act, downloaded);
                    }
                });
                return;
            } catch (InterruptedDownload e) {
                final DownloadService ds2 = DownloadService.running;
                if (ds2 != null) ds2.endNotify();
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(act, R.string.upd_cancelled, Toast.LENGTH_SHORT).show();
                });
                return;
            } catch (Throwable t) {
                last = t;
            }
        }

        final Throwable f = last;
        main.post(() -> {
            // 失败：对话框内显示错误码诊断行，并给出「重试」按钮，可重新构建下载对话框
            bar.setIndeterminate(false);
            retryBtn.setVisibility(View.VISIBLE);
            String extra = (f != null && f.getMessage() != null) ? "\n" + f.getMessage() : "";
            tvPct.setText(act.getString(R.string.dn_err_code, "E1", errorText(act, 1)) + extra);
        });
    }

    /** 下载启动前清理上一轮残留的 .part* 分片与临时文件。 */
    private static void cleanupStale(File target) {
        for (int i = 0; i < PARALLEL_PARTS; i++) {
            File pf = new File(target.getAbsolutePath() + ".part" + i);
            if (pf.exists()) pf.delete();
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
    }

    /** 将进度同步到通知栏前台服务（cur=已下载，total=-1 表示未知总大小）。 */
    private static void notifyProgress(int cur, int total) {
        DownloadService ds = DownloadService.running;
        if (ds != null) ds.startForegroundNotify(cur, total);
    }

    /** 生成镜像访问顺序：用户所选优先，其余按序跟随，用于失败自动回退。 */
    private static int[] buildMirrorOrder(int sel) {
        int n = MIRROR_PREFIXES.length;
        int[] o = new int[n];
        o[0] = sel;
        int k = 1;
        for (int i = 0; i < n; i++) if (i != sel) o[k++] = i;
        return o;
    }

    /**
     * 统一下载入口：全新下载优先多线程分片，小文件/分片失败回退单线程；
     * 已有偏移则走单线程断点续传。成功方可返回；失败抛异常，取消抛 InterruptedDownload。
     */
    private static void doDownloadAny(String urlStr, File target, ProgressBar bar, TextView tvPct,
                                      long resumeFrom) throws Exception {
        if (resumeFrom <= 0) {
            long len = probeLength(urlStr);
            if (len > PARALLEL_MIN_LEN) {
                // 分片成功则直接返回；分片路径内部失败会抛出，由调用方重试/回退
                downloadParallel(urlStr, target, len, bar, tvPct);
                return;
            }
            // 大小不确定(-1)或过小：回退单线程从头下载
        }
        doDownload(urlStr, target, bar, tvPct, resumeFrom);
    }

    /** 探测远端 Content-Length（完整文件长度）；不可用返回 -1。 */
    private static long probeLength(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = open(urlStr);
            int code = conn.getResponseCode();
            if (code == 200 || code == 206) return conn.getContentLength();
            return -1L;
        } catch (Throwable t) {
            return -1L;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 完整性校验：对比本地文件长度与远端 Content-Length；未知大小跳过。 */
    private static void verifyIntegrity(File apk, long expectedLen) throws IOException {
        if (expectedLen <= 0) return;
        if (apk.length() != expectedLen) {
            throw new IOException("size mismatch: " + apk.length() + " != " + expectedLen);
        }
    }

    /**
     * 单源下载（支持断点续传）：
     * resumeFrom>0 时发起 Range，接受 206 追加写入、从 resumeFrom 累计进度；
     * 服务端返回 200 则视为不支持续传，覆盖重下。成功正常返回，失败抛异常，
     * 用户取消抛 InterruptedDownload。
     */
    private static void doDownload(String urlStr, File target, ProgressBar bar, TextView tvPct,
                                   long resumeFrom) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = open(urlStr);
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            int code = conn.getResponseCode();
            long total;
            boolean resumed;
            if (resumeFrom > 0 && code == 206) {
                // 服务器支持续传：追加写
                out = new FileOutputStream(target, true);
                total = resumeFrom;
                resumed = true;
            } else {
                if (code != 200) throw new IOException("HTTP " + code);
                // 200：覆盖重下
                out = new FileOutputStream(target);
                total = 0;
                resumed = false;
            }
            long remainder = conn.getContentLength();
            final long fullLen = resumed ? resumeFrom + remainder : remainder;
            in = conn.getInputStream();
            if (remainder > 0) main.post(() -> bar.setIndeterminate(false));

            final byte[] buf = new byte[8192];
            long lastUi = System.currentTimeMillis();
            int r;
            while ((r = in.read(buf)) != -1) {
                if (currentCancel.get()) throw new InterruptedDownload();
                out.write(buf, 0, r);
                total += r;
                long now = System.currentTimeMillis();
                if (now - lastUi >= 120) {
                    lastUi = now;
                    final long flen = fullLen;
                    final long ftotal = total;
                    main.post(() -> updateProgress(bar, tvPct, flen > 0, flen, ftotal));
                }
            }
            out.flush();
            final long flen = fullLen;
            final long ftotal = total;
            main.post(() -> updateProgress(bar, tvPct, flen > 0, flen, ftotal));
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 多线程分片下载（Task3）：并发 4 片写入 target.part0..part3，完成后合并删除分片。
     * 任一片失败抛异常（并清理分片）；文件过长到分片不值得/过小由调用方回退单线程。
     */
    public static void downloadParallel(String urlStr, File target, long fileLen) throws Exception {
        downloadParallel(urlStr, target, fileLen, null, null);
    }

    /** 多线程分片下载的实现（可携带进度 UI）。任一片失败抛异常；取消抛 InterruptedDownload。 */
    private static void downloadParallel(final String urlStr, final File target,
                                         final long fileLen, final ProgressBar bar, final TextView tvPct)
            throws Exception {
        if (fileLen <= PARALLEL_MIN_LEN) {
            throw new IOException("parallel size too small: " + fileLen);
        }
        final int parts = PARALLEL_PARTS;
        final long per = fileLen / parts;
        final AtomicLong done = new AtomicLong(0);
        final AtomicReference<Throwable> fail = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(parts);

        for (int i = 0; i < parts; i++) {
            final int idx = i;
            final long start = i * per;
            final long end = (i == parts - 1) ? fileLen - 1 : (i + 1) * per - 1;
            final File partFile = new File(target.getAbsolutePath() + ".part" + idx);
            new Thread(() -> {
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    conn = open(urlStr);
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    int c = conn.getResponseCode();
                    if (c != 206) throw new IOException("HTTP " + c);
                    in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(partFile);
                    byte[] buf = new byte[8192];
                    int r;
                    long wrote = 0;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                        wrote += r;
                        done.addAndGet(r);
                        if (currentCancel.get()) throw new InterruptedDownload();
                    }
                    out.flush();
                    out.close();
                    if (wrote != (end - start + 1)) throw new IOException("part incomplete: " + idx);
                } catch (Throwable t) {
                    fail.compareAndSet(null, t);
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                    if (conn != null) conn.disconnect();
                }
                latch.countDown();
            }).start();
        }

        // 轮询累计进度反馈到 UI
        long lastUi = System.currentTimeMillis();
        try {
            while (!latch.await(120, TimeUnit.MILLISECONDS)) {
                long now = System.currentTimeMillis();
                if (now - lastUi >= 120) {
                    lastUi = now;
                    if (bar != null) {
                        final long d = done.get();
                        main.post(() -> updateProgress(bar, tvPct, true, fileLen, d));
                    }
                }
            }
        } finally {
            if (fail.get() == null && !currentCancel.get()) {
                mergeParts(target, parts);
            } else {
                deleteParts(target, parts);
                if (currentCancel.get()) throw new InterruptedDownload();
                throw new IOException(fail.get());
            }
        }
    }

    /** 顺序合并 target.part0..N 到 target 后校验总大小。 */
    private static void mergeParts(File target, int parts) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            for (int i = 0; i < parts; i++) {
                File pf = new File(target.getAbsolutePath() + ".part" + i);
                try (InputStream in = new FileInputStream(pf)) {
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
            }
        }
        deleteParts(target, parts);
    }

    /** 删除 target.part0..N 分片文件。 */
    private static void deleteParts(File target, int parts) {
        for (int i = 0; i < parts; i++) {
            File pf = new File(target.getAbsolutePath() + ".part" + i);
            if (pf.exists()) pf.delete();
        }
    }

    /** 更新进度条与百分比文本（含速度与剩余时间），并同步通知栏进度。 */
    private static void updateProgress(ProgressBar bar, TextView tvPct,
                                       boolean knowsLen, long len, long total) {
        if (knowsLen && len > 0) {
            int pct = (int) (total * 100 / len);
            bar.setProgress(pct);

            // 计算最近窗口内的下载速度（MB/s）与估计剩余时间
            float mbps = 0;
            long now = System.currentTimeMillis();
            if (lastProgMs != 0) {
                long dt = now - lastProgMs;
                long db = total - lastProgTotal;
                if (dt > 0 && db > 0) mbps = db / 1048576f / (dt / 1000f);
            }
            lastProgMs = now;
            lastProgTotal = total;

            String speedTxt = "";
            if (mbps > 0 && len > total) {
                long remainSec = (long) ((len - total) / 1048576f / mbps);
                speedTxt = String.format(java.util.Locale.US, " | 速度 %.1fMB/s 剩余0m%ds", mbps, remainSec);
            }
            tvPct.setText(String.format(java.util.Locale.US, "%.1f/%.1fMB %d%%%s",
                    total / 1048576f, len / 1048576f, pct, speedTxt));
            notifyProgress((int) total, (int) len);
        } else {
            bar.setIndeterminate(true);
            tvPct.setText(String.format(java.util.Locale.US, "%.1f MB", total / 1048576f));
        }
    }

    /**
     * 通过 FileProvider 引导安装 APK：先做签名校验，再检测“安装未知来源”授权，
     * 未授权自动引导开启；错误统一按错误码诊断（toast 用 dn_err_code，异常信息进可滚动诊断对话框）。
     */
    private static void installApk(Activity act, File apk) {
        // 签名校验：不一致时删除该 APK 并提示，不再进入安装
        if (!signaturesMatch(act, apk)) {
            apk.delete();
            Toast.makeText(act, R.string.dn_sig_fail, Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
        } catch (Exception e) {
            toastError(act, 4);
            showDiagnostic(act, e);
            return;
        }

        // Android 8+（API 26）：需要“安装未知应用”授权，否则打开安装器会失败
        if (Build.VERSION.SDK_INT >= 26) {
            PackageManager pm = act.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                toastError(act, 2);
                guideToUnknownSources(act);
                return;
            }
        }

        // 优先用 ACTION_INSTALL_PACKAGE 打开系统安装器
        try {
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setData(uri);
            install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(install);
            // 安装器已接管，稍后删除本次下载的 APK 临时文件
            scheduleDeleteApk(apk);
            return;
        } catch (ActivityNotFoundException e) {
            // 回退到 ACTION_VIEW
            try {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, "application/vnd.android.package-archive");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(view);
                scheduleDeleteApk(apk);
                return;
            } catch (ActivityNotFoundException e2) {
                toastError(act, 3);
                showDiagnostic(act, e2);
            } catch (Exception e2) {
                toastError(act, classifyInstallErr(e2));
                showDiagnostic(act, e2);
            }
        } catch (Exception e) {
            toastError(act, classifyInstallErr(e));
            showDiagnostic(act, e);
        }
    }

    /** 将安装抛出的一般异常归类为错误码：存储不足→5，其余→4。 */
    private static int classifyInstallErr(Throwable e) {
        String m = e.getMessage();
        if (m != null && (m.contains("space") || m.contains("ENOSPC")
                || m.contains("存储空间") || m.contains("no space"))) {
            return 5;
        }
        return 4;
    }

    /** 安装完成后删除下载的 APK 临时文件（延迟以避开安装器读取）。 */
    private static void scheduleDeleteApk(final File apk) {
        main.postDelayed(apk::delete, 15000);
    }

    /**
     * 兜底安装引导：当后台直接拉起系统安装器被系统限制（API 29+ BackgroundActivityStartNotAllowedException）
     * 或其他异常致使自动安装失败时，发一条「点击安装」通知；用户在通知栏点击（此时已回前台）再走系统安装器。
     */
    private static void safeNotifyTap(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 3, install, flags);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = new NotificationCompat.Builder(ctx, DownloadService.CH_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(ctx.getString(R.string.dn_nc_done))
                    .setContentText(ctx.getString(R.string.dn_tap_install))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(4, n);
        } catch (Throwable ignored) {
            // 兜底失败也无害：不影响进程，错误信息已通过 showDiagnostic 展示。
        }
    }

    /** 错误码诊断文本映射：1 下载失败 / 2 安装权限 / 3 无安装器 / 4 安装包无效 / 5 存储不足 / 6 校验失败。 */
    static String errorText(Activity act, int code) {
        switch (code) {
            case 1:
                return act.getString(R.string.upd_download_fail);
            case 2:
                return act.getString(R.string.upd_need_install_perm);
            case 3:
                return act.getString(R.string.upd_install_no_activity);
            case 4:
                return act.getString(R.string.upd_install_invalid);
            case 5:
                return act.getString(R.string.upd_install_no_space);
            case 6:
                return act.getString(R.string.upd_install_bad_signature);
            default:
                return "";
        }
    }

    /** 以统一格式弹出带错误码的诊断 Toast：错误码 Ex：<诊断文本>。 */
    private static void toastError(Activity act, int code) {
        Toast.makeText(act,
                act.getString(R.string.dn_err_code, "E" + code, errorText(act, code)),
                Toast.LENGTH_LONG).show();
    }

    /** 可滚动的安装错误详情对话框（展示异常 msg）。 */
    private static void showDiagnostic(Activity act, Throwable e) {
        String msg = e != null && e.getMessage() != null ? e.getMessage() : String.valueOf(e);
        ScrollView sv = new ScrollView(act);
        TextView tv = new TextView(act);
        tv.setText(msg);
        tv.setTextIsSelectable(true);
        int pad = (int) (24 * act.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle(R.string.upd_install_fail)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * 签名一致性校验：用 PackageManager 比对待安装 APK 与当前已安装包的签名是否深度一致。
     * 任一环节异常均返回 false（不可信，拒绝安装）。
     */
    static boolean signaturesMatch(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo apkInfo = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo curInfo = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (apkInfo == null || curInfo == null
                    || apkInfo.signatures == null || curInfo.signatures == null
                    || apkInfo.signatures.length != curInfo.signatures.length) {
                return false;
            }
            for (int i = 0; i < curInfo.signatures.length; i++) {
                if (!curInfo.signatures[i].equals(apkInfo.signatures[i])) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳转引导页开启“安装未知应用”授权。 */
    private static void guideToUnknownSources(Activity act) {
        try {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            s.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(s);
        } catch (Exception e) {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName())));
        }
    }

    /** 下载被用户打断。 */
    private static final class InterruptedDownload extends Exception { }

    /** 垂直布局参数。 */
    private static LinearLayout.LayoutParams lp(boolean span) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                span ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!span) p.gravity = Gravity.CENTER;
        return p;
    }

    /** 回调：isLatest=true 表示已是最新。 */
    public interface UpdateListener {
        void onResult(String versionName, String changeLog, String apkUrl, boolean isLatest);
    }

    /** 测速回调：speeds 为各源速度（MB/s，失败为 0），bestIdx 为最快源下标。 */
    public interface SpeedListener {
        void onResult(float[] speeds, int bestIdx);
    }
}