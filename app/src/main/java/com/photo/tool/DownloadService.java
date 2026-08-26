package com.photo.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Locale;

/**
 * 前台下载服务：承载应用内更新 APK 的下载与通知栏进度展示。
 * UpdateChecker 下载开始时通过 startForegroundService 启动本服务，
 * 期间调用 startForegroundNotify 同步进度，完成时调用 doneNotify。
 */
public class DownloadService extends Service {

    /** 下载进度通知渠道 ID */
    public static final String CH_ID = "download_progress";

    /** 通知栏「取消下载」动作 */
    public static final String ACTION_CANCEL = "com.photo.tool.action.CANCEL_DOWNLOAD";

    /** 下载进度通知 ID */
    private static final int NOTIF_ID = 1;

    /** 正在运行的前台下载服务实例（供 UpdateChecker 同步进度）。 */
    public static volatile DownloadService running;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏「取消」动作：仅置取消标志，下载线程会自行收尾（停前台服务并移除通知）。
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            UpdateChecker.cancelCurrent();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIF_ID, buildNotification(true, 0, getString(R.string.dn_nc_title)));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CH_ID, getString(R.string.dn_nc_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.dn_nc_title));
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * 构建下载进度通知：进度条 + 文案 + 点击跳回应用主界面。
     * @param indeterminate 是否不确定进度（总大小未知时为 true）
     * @param pct           0~100 百分比进度（indeterminate 时忽略）
     * @param text          通知内容文案
     */
    public Notification buildNotification(boolean indeterminate, int pct, String text) {
        createChannel();
        Intent notifyIntent = new Intent(this, CameraActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.dn_nc_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // 通知栏直达「取消下载」：免回应用即可中止，需有新下载动作时为 true
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.dn_cancel),
                        cancelPendingIntent());
        if (indeterminate) {
            b.setProgress(0, 0, true);
        } else {
            b.setProgress(100, pct, false);
        }
        return b.build();
    }

    /** 通知栏「取消」操作：发给同一前台服务，置取消标志收尾。 */
    private PendingIntent cancelPendingIntent() {
        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 前台同步下载进度到通知栏。 */
    public void startForegroundNotify(int cur, int total) {
        int pct = total > 0 ? (int) (cur * 100L / total) : 0;
        String text;
        if (total > 0) {
            text = String.format(Locale.US, "%.1f/%.1f MB (%d%%)",
                    cur / 1048576f, total / 1048576f, pct);
        } else {
            text = String.format(Locale.US, "%.1f MB", cur / 1048576f);
        }
        startForeground(NOTIF_ID, buildNotification(total <= 0, pct, text));
    }

    /** 前台以固定文案更新通知（如“正在校验完整性”）。 */
    public void notifyText(int pct, String text) {
        startForeground(NOTIF_ID, buildNotification(pct <= 0, pct, text));
    }

    /** 下载完成通知：进度归零转等待态；点击通知直达系统安装器（若提供了 APK 文件）。 */
    public void doneNotify(File apk) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.dn_nc_done))
                .setContentText(apk != null ? getString(R.string.dn_tap_install) : getString(R.string.dn_nc_done))
                .setProgress(0, 0, true)
                .setOngoing(false)
                .setAutoCancel(true);
        if (apk != null) {
            b.setContentIntent(installPendingIntent(apk));
        }
        nm.notify(NOTIF_ID, b.build());
    }

    /** 完成通知：构建直达系统安装器的 PendingIntent（授予 URI 读权限）。 */
    private PendingIntent installPendingIntent(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 5, install,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 结束前台服务并移除通知（取消下载时使用）。 */
    public void endNotify() {
        stopForeground(true);
        stopSelf();
    }
}