package com.notifybridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收 AlarmManager 触发的定时上传广播，启动实际上传任务。
 */
public class UploadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (UploadScheduler.ACTION_DAWN.equals(action)) {
            // 00:05：上传前一天全量（有则跳过）+ 今日非全量
            Uploader.uploadManual(context, null);
        } else if (UploadScheduler.ACTION_NOON.equals(action)
                || UploadScheduler.ACTION_EVENING.equals(action)) {
            Uploader.run(context, null); // 后台静默上传
        } else {
            return; // 未知 action 不重设
        }
        // 安排下一次同一时刻
        UploadScheduler.scheduleSlot(context, action);
    }
}
