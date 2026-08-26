package com.photo.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * 前台下载服务：承载应用内更新 APK 的下载与通知栏进度展示。
 * UpdateChecker 下载开始时通过 startForegroundService 启动本服务，
 * 期间调用 startForegroundNotify 同步进度，完成时调用 doneNotify。
 */
public class DownloadService extends Service {

    /** 下载进度通知渠道 ID */
    public static final String CH_ID = "download_progress";

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
                .setOnlyAlertOnce(true);
        if (indeterminate) {
            b.setProgress(0, 0, true);
        } else {
            b.setProgress(100, pct, false);
        }
        return b.build();
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

    /** 下载完成通知：进度归零转等待态，提示点击安装。 */
    public void doneNotify() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.dn_nc_done))
                .setContentText(getString(R.string.dn_nc_done))
                .setProgress(0, 0, true)
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    /** 结束前台服务并移除通知（取消下载时使用）。 */
    public void endNotify() {
        stopForeground(true);
        stopSelf();
    }
}