package com.notifybridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务。在通知监听授权后启动运行（startForeground），
 * 以常驻通知的形式显著降低被系统（尤其小米等国产 ROM）回收后台进程的概率。
 * 前台服务的通知同时作为“运行中”的可视化提示。
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "keepalive";
    private static final int NOTIF_ID = 2001;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        return START_STICKY; // 被杀后尽量重启
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // API 26+ 需要通知渠道
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "监听保活", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("NotifyBridge 监听中")
         .setContentText("正在后台捕获通知并同步到 WebDAV")
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setOngoing(true);

        // API 29+ 需要指定前台服务类型（dataSync）；用反射兜底避免编译问题
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, b.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (Exception e) {
                startForeground(NOTIF_ID, b.build());
            }
        } else {
            startForeground(NOTIF_ID, b.build());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
