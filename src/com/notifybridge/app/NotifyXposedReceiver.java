package com.notifybridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收 Xposed 模块（NotifyHooker）广播过来的"非系统通知"消息
 * （典型：微信电脑登录时不走系统通知，由 Xposed Hook 进程内消息转发而来）。
 * 写入统一缓存与日志，供统计 / AI 纪要 / 上传使用。
 */
public class NotifyXposedReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.notifybridge.app.XP_MSG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!ACTION.equals(intent.getAction())) return;
        // 安全：仅接受显式投递给本包的有界广播（NotifyHooker 已 setPackage）。
        // 未指定包名的隐式广播直接丢弃，避免被其他应用塞入伪造消息。
        try {
            if (intent.getPackage() != null && !NotifyHooker.PKG.equals(intent.getPackage())) return;
        } catch (Throwable ignored) {}
        String from = intent.getStringExtra("from");
        String body = intent.getStringExtra("body");
        long ts = intent.getLongExtra("ts", System.currentTimeMillis());
        if (from == null) from = "xposed";
        if (body == null) return;
        // 内容净化：限长、去控制字符，防止超长/异常内容破坏缓存与统计
        if (body.length() > 2000) body = body.substring(0, 2000);

        // 记诊断日志 + 统一缓存（与通知监听通道一致，供统计/纪要/上传）
        try {
            String tag = "com.tencent.mm".equals(from) ? "[微信] " : ("[" + from + "] ");
            LogStore.diag(context, tag + body);
            // 面向用户的日更 Markdown 日志：时间戳 + 内容
            DailyLog.append(context, ts, tag + body);
        } catch (Throwable ignored) {}
        try {
            NotificationCache.append(context, from, appLabel(context, from), body, body, ts);
        } catch (Throwable ignored) {}
        try {
            StatsStore.record(context, ts, from, categorize(body));
        } catch (Throwable ignored) {}
    }

    /** 轻量分类（与 NotifyListener 一致）：work/todo/urgent/meeting/other。 */
    static String categorize(String body) {
        if (body == null) return "other";
        String b = body.toLowerCase();
        String[] work = {"工作", "任务", "报表", "需求", "项目", "邮件", "会议纪要", "deadline", "审批", "okr", "汇报"};
        String[] todo = {"待办", "提醒", "记得", "别忘了", "完成", "提交", "确认", "安排", "处理", "回复", "todo", "ddl", "截止"};
        String[] urgent = {"紧急", "告警", "故障", "宕机", "崩溃", "发热", "停", "异常", "错误", "error", "alert"};
        String[] meeting = {"会议", "开会", "与会", "腾讯会议", "zoom", "meeting", "日程", "预约", "提醒会议"};
        for (String w : urgent) if (b.contains(w)) return "urgent";
        for (String w : meeting) if (b.contains(w)) return "meeting";
        for (String w : todo) if (b.contains(w)) return "todo";
        for (String w : work) if (b.contains(w)) return "work";
        return "other";
    }

    private String appLabel(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) { return pkg; }
    }

    private int hrefOf(long ts) { return 0; }  // unused
}
