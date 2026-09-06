package com.notifybridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机广播：设备重启后重新调度定时上传，确保监听与同步持续生效。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            UploadScheduler.scheduleRepeating(context);
            // 重启后主动补传一次，避免要等下一个 interval 才同步
            UploadScheduler.triggerUpload(context);
        }
    }
}
