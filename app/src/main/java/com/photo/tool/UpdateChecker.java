package com.photo.tool;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检查更新：
 * 启动时拉取远端 version.json（托管于 GitHub 仓库），
 * 若 versionCode 大于本应用即提示，确认后用 DownloadManager 下载并引导安装。
 */
public final class UpdateChecker {

    /** 版本信息清单地址（GitHub raw）。 */
    public static String VERSION_JSON_URL = "https://raw.githubusercontent.com/dargon682/focus-upscale-camera/main/version.json";

    private static final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean hasChecked = false;

    private UpdateChecker() { }

    /** 是否已该进程检查过（避免启动弹更新多次）。 */
    public static boolean checkedThisSession() { return hasChecked; }

    /**
     * 后台检查更新。found=null 表示网络失败；cb.onResult(foundVersionName, changeLog, apkUrl, isLatest)
     */
    public static void check(final Context ctx, final Runnable onFail, final UpdateListener listener) {
        hasChecked = true;
        exec.execute(() -> {
            try {
                JSONObject o = readJson(VERSION_JSON_URL);
                int remoteCode = o.optInt("versionCode", 0);
                String name = o.optString("versionName", "");
                String apk = o.optString("apkUrl", "");
                String change = o.optString("changelog", "");
                final boolean latest = remoteCode <= BuildConfig.VERSION_CODE;
                main.post(() -> listener.onResult(name, change, apk, latest));
            } catch (Throwable t) {
                main.post(() -> { if (onFail != null) onFail.run(); });
            }
        });
    }

    private static JSONObject readJson(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FocusUpscale-Android");
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return new JSONObject(sb.toString());
    }

    /** 弹更新对话框；用户确认则调用 download()。 */
    public static void prompt(Activity act, String versionName, String apkUrl, String changelog) {
        new AlertDialog.Builder(act)
                .setTitle(R.string.btn_check_update)
                .setMessage(act.getString(R.string.upd_found, versionName, changelog))
                .setPositiveButton(R.string.btn_download, (d, w) -> download(act, apkUrl))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 用系统 DownloadManager 下载（完成后系统通知提供“安装”入口，避免 File 授权问题）。
     */
    public static void download(Context ctx, String apkUrl) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
        req.setTitle("对焦超分相机更新");
        req.setDescription("正在下载新版本…");
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "focus-upscale-v" + BuildConfig.VERSION_NAME + ".apk");
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        long id = dm.enqueue(req);
        // 用户可从系统通知栏的下载完成通知点击安装
        String msg = ctx.getString(R.string.upd_downloading);
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    /** 回调：isLatest=true 表示已是最新。 */
    public interface UpdateListener {
        void onResult(String versionName, String changeLog, String apkUrl, boolean isLatest);
    }
}