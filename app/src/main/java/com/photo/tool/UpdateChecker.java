package com.photo.tool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
            "ghproxy.net 加速镜像"
    };
    public static final String[] MIRROR_PREFIXES = {
            "",                       // 直连
            "https://gh-proxy.com/",  // gh-proxy 加速
            "https://ghproxy.net/"    // ghproxy 加速
    };

    /** 测速基准文件（仓库内 APK raw 地址，较大以便稳定测得各源真实带宽）。 */
    private static final String SPEED_BASE =
            "https://raw.githubusercontent.com/dargon682/focus-upscale-camera/main/apk/photo-tool-v0.6.300.apk";

    /** 测速读取量：每源读取 1MB 计时。 */
    private static final int SPEED_READ_BYTES = 1_048_576;

    /** 进程内缓存的最快镜像索引（由 testSpeeds 更新）。 */
    private static volatile int bestMirrorIdx = 0;

    private static final ExecutorService exec = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean hasChecked = false;

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

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle(R.string.upd_download_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> currentCancel.set(true))
                .setCancelable(false)
                .create();

        // 取消按钮文本在开始后改为“取消下载”
        dialog.setOnShowListener(d -> {
            Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            neg.setText(R.string.upd_cancel_download);
        });

        // —— 开始下载 ——
        exec.execute(() -> downloadRun(act, apkUrl, target, spinner, bar, tvPct, dialog));
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
                                    final AlertDialog dialog) {
        currentCancel.set(false);
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
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(act, R.string.upd_cancelled, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            final String urlStr = MIRROR_PREFIXES[idx] + baseUrl;
            try {
                doDownload(urlStr, target, bar, tvPct);
                final File downloaded = target;
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    installApk(act, downloaded);
                });
                return;
            } catch (InterruptedDownload e) {
                main.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(act, R.string.upd_cancelled, Toast.LENGTH_SHORT).show();
                });
                return;
            } catch (Throwable t) {
                last = t;
                final String miss = MIRROR_NAMES[idx];
                final boolean hasNext = idx != order[order.length - 1];
                main.post(() -> tvPct.setText(act.getString(
                        hasNext ? R.string.upd_mirror_retry : R.string.upd_download_fail, miss)));
            }
        }

        final Throwable f = last;
        main.post(() -> {
            if (dialog.isShowing()) dialog.dismiss();
            Toast.makeText(act, act.getString(R.string.upd_download_fail)
                    + " " + (f != null ? f.getMessage() : ""), Toast.LENGTH_LONG).show();
        });
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

    /** 单源下载：成功正常返回，失败抛异常，用户取消抛 InterruptedDownload。 */
    private static void doDownload(String urlStr, File target,
                                   ProgressBar bar, TextView tvPct) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = open(urlStr);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code);
            }
            long len = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(target);
            if (len > 0) main.post(() -> bar.setIndeterminate(false));

            final byte[] buf = new byte[8192];
            long total = 0;
            long lastUi = System.currentTimeMillis();
            int r;
            while ((r = in.read(buf)) != -1) {
                if (currentCancel.get()) throw new InterruptedDownload();
                out.write(buf, 0, r);
                total += r;
                long now = System.currentTimeMillis();
                if (now - lastUi >= 120) {
                    lastUi = now;
                    final long flen = len;
                    final long ftotal = total;
                    main.post(() -> updateProgress(bar, tvPct, flen > 0, flen, ftotal));
                }
            }
            out.flush();
            final long flen = len;
            final long ftotal = total;
            main.post(() -> updateProgress(bar, tvPct, flen > 0, flen, ftotal));
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    /** 更新进度条与百分比文本。 */
    private static void updateProgress(ProgressBar bar, TextView tvPct,
                                       boolean knowsLen, long len, long total) {
        if (knowsLen && len > 0) {
            int pct = (int) (total * 100 / len);
            bar.setProgress(pct);
            // 以 MB 显示已下载 / 总大小
            tvPct.setText(String.format(java.util.Locale.US, "%.1f / %.1f MB  (%d%%)",
                    total / 1048576f, len / 1048576f, pct));
        } else {
            bar.setIndeterminate(true);
            tvPct.setText(String.format(java.util.Locale.US, "%.1f MB", total / 1048576f));
        }
    }

    /** 通过 FileProvider 引导安装 APK。 */
    private static void installApk(Activity act, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(act, R.string.upd_install_fail, Toast.LENGTH_LONG).show();
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