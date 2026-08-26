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

    /** 后台下载并实时回调进度。 */
    private static void downloadRun(final Activity act, final String baseUrl,
                                    final File target, final Spinner spinner,
                                    final ProgressBar bar, final TextView tvPct,
                                    final AlertDialog dialog) {
        currentCancel.set(false);
        final int mirrorIdx = spinner.getSelectedItemPosition();
        final String urlStr = MIRROR_PREFIXES[mirrorIdx] + baseUrl;

        main.post(() -> {
            spinner.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(true);
            tvPct.setText(R.string.upd_connecting);
        });

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = open(urlStr);
            long len = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(target);
            // 已知总长则改用确定进度
            if (len > 0) main.post(() -> bar.setIndeterminate(false));

            final byte[] buf = new byte[8192];
            long total = 0;
            long lastUi = System.currentTimeMillis();
            int r;
            while ((r = in.read(buf)) != -1) {
                if (currentCancel.get()) throw new InterruptedDownload();
                out.write(buf, 0, r);
                total += r;
                // 节流刷新进度：至少间隔 120ms 更新一次，避免主线程频繁刷新
                long now = System.currentTimeMillis();
                if (now - lastUi >= 120) {
                    lastUi = now;
                    updateProgress(bar, tvPct, len > 0, len, total);
                }
            }
            out.flush();
            updateProgress(bar, tvPct, len > 0, len, total);

            final File downloaded = target;
            main.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                installApk(act, downloaded);
            });
        } catch (InterruptedDownload e) {
            main.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(act, R.string.upd_cancelled, Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable t) {
            main.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(act, act.getString(R.string.upd_download_fail)
                        + " " + t.getMessage(), Toast.LENGTH_LONG).show();
            });
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
}