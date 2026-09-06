package com.notifybridge.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 定时批量上传调度器。使用 AlarmManager 在后台周期性触发上传，
 * 避免频繁网络请求，也无需依赖 WorkManager（AndroidX）。
 */
public final class UploadScheduler {
    private static final int REQ_UPLOAD = 1001;
    private static volatile long lastTriggerMs = 0L;
    private static final long TRIGGER_THROTTLE_MS = 60 * 1000L;

    private UploadScheduler() {}

    /** 广播触发的 intent-factory。 */
    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, UploadReceiver.class).setAction("com.notifybridge.app.UPLOAD");
        // FLAG_IMMUTABLE 为 Android 12+ 必需
        return PendingIntent.getBroadcast(ctx, REQ_UPLOAD, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 设置周期性定时上传（默认每 10 分钟）。 */
    public static void scheduleRepeating(Context ctx) {
        if (!Config.getBool(ctx, Config.KEY_SYNC_ENABLED, false)) return;
        int interval =
                Config.getInt(ctx, Config.KEY_UPLOAD_INTERVAL_MS, 10 * 60 * 1000);
        if (interval < 60 * 1000) interval = 60 * 1000; // 下限 1 分钟

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        // 用 setInexactRepeating 避免 exact alarm 权限限制，省电且适配国产 ROM
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + interval, interval, pending(ctx));
    }

    /** 取消定时上传。 */
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(ctx));
    }

    /** 立即触发一次上传（写缓存阈值或手动同步）。 */
    public static void triggerUpload(Context ctx) {
        if (!Config.getBool(ctx, Config.KEY_SYNC_ENABLED, false)) {
            // 自动触发仅在开启同步时生效；手动按钮单独走 MainActivity
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < TRIGGER_THROTTLE_MS) return; // 节流：至少间隔 60s
        lastTriggerMs = now;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 100, pending(ctx));
    }
}
