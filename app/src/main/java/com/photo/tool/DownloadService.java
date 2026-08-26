package com.photo.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台下载服务骨架：承载应用内更新 APK 的下载、校验与通知栏进度展示。
 * 具体下载逻辑由后续任务补全。
 */
public class DownloadService extends Service {

    /** 下载进度通知渠道 ID */
    public static final String CH_ID = "download_progress";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CH_ID, getString(R.string.dn_nc_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.dn_nc_title));
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 构建下载通知。后续任务补全为真实通知。
     */
    public Notification buildNotification(boolean indeterminate, int pct, String text) {
        return null;
    }
}