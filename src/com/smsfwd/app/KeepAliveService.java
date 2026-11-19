package com.smsfwd.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务：常驻通知，确保无障碍服务在后台持续绑定，
 * 收到短信时无需等待进程被拉起。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "smsfwd_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("短信转发运行中")
                .setContentText("收到短信将自动转发到微信")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "短信转发", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setDescription("保持短信转发服务常驻");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
