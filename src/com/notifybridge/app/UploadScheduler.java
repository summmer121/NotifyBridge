package com.notifybridge.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * 定时上传调度器：每天固定 00:05（上传前一天全量）、12:00、18:00 各上传一次到 WebDAV。
 * 使用 AlarmManager 一次性闹钟 + 触发后重设下一天，避免 10 分钟级别的频繁网络请求。
 */
public final class UploadScheduler {
    public static final String ACTION_DAWN = "com.notifybridge.app.UPLOAD_DAWN";         // 00:05 上传前一天全量
    public static final String ACTION_NOON = "com.notifybridge.app.UPLOAD_NOON";      // 12:00
    public static final String ACTION_EVENING = "com.notifybridge.app.UPLOAD_EVENING";// 18:00

    private static final int REQ_DAWN = 1000;
    private static final int REQ_NOON = 1001;
    private static final int REQ_EVENING = 1002;

    private UploadScheduler() {}

    private static PendingIntent pending(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, UploadReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 安排今天（或明天）的 00:05 / 12:00 / 18:00 定时上传。 */
    public static void scheduleDaily(Context ctx) {
        if (!Config.getBool(ctx, Config.KEY_SYNC_ENABLED, false)) return;
        planSlot(ctx, ACTION_DAWN, REQ_DAWN, 0, 5);
        planSlot(ctx, ACTION_NOON, REQ_NOON, 12);
        planSlot(ctx, ACTION_EVENING, REQ_EVENING, 18, 0);
    }

    /** 给某个时刻槽安排下一次（今天若已过则安排明天）。 */
    private static void planSlot(Context ctx, String action, int req, int hour) {
        planSlot(ctx, action, req, hour, 0);
    }

    /** 给某个时刻槽安排下一次（今天若已过则安排明天）。 */
    private static void planSlot(Context ctx, String action, int req, int hour, int minute) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);  // 今天该时刻已过，安排明天
        }
        long trigger = c.getTimeInMillis();
        // 非精确闹钟：省电、规避国产 ROM 的精确闹钟权限限制
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(ctx, action, req));
        } else {
            am.set(AlarmManager.RTC_WAKEUP, trigger, pending(ctx, action, req));
        }
    }

    /** 定时触发后，由 receiver 调用以安排下一次对应时刻。 */
    public static void scheduleSlot(Context ctx, String action) {
        if (ACTION_DAWN.equals(action)) planSlot(ctx, ACTION_DAWN, REQ_DAWN, 0, 5);
        else if (ACTION_NOON.equals(action)) planSlot(ctx, ACTION_NOON, REQ_NOON, 12, 0);
        else if (ACTION_EVENING.equals(action)) planSlot(ctx, ACTION_EVENING, REQ_EVENING, 18, 0);
    }

    /** 取消所有定时上传。 */
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(ctx, ACTION_DAWN, REQ_DAWN));
        am.cancel(pending(ctx, ACTION_NOON, REQ_NOON));
        am.cancel(pending(ctx, ACTION_EVENING, REQ_EVENING));
    }
}
