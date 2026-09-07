package com.notifybridge.app;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 通知监听服务。系统授予“通知使用权”后，在后台/锁屏也能稳定捕获通知。
 * 按黑白名单过滤，提取关键字段写入本地缓存，达到批量阈值后触发上传。
 */
public class NotifyListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;

            Context ctx = getApplicationContext();
            String pkg = sbn.getPackageName();
            String appName = appNameOf(ctx, pkg);

            // ★ 无条件记录原始捕获痕迹（诊断用）：通知是否到达监听器一眼可见
            LogStore.raw(ctx, pkg, sbn.getPostTime());

            // 过滤：系统包一律略过（避免把系统通知也传走，默认黑名单模式）
            if (isSystem(pkg)) return;
            if (!isAllowed(ctx, pkg)) {
                LogStore.diag(ctx, "[" + appName + "] 通知被过滤（黑白名单拦截）");
                return;
            }

            Notification n = sbn.getNotification();
            if (n == null) {
                LogStore.diag(ctx, "[" + appName + "] 收到通知但 Notification 对象为空");
                return;
            }

            // 提取所有可能的文本字段（微信转账等敏感通知常在副标题/大文本/多行里）
            android.os.Bundle extras = n.extras;
            String title = "", text = "", sub = "", big = "", bigTitle = "", lines = "";
            String category = n.category;
            String channel = "";
            if (extras != null) {
                CharSequence ch = extras.getCharSequence(Notification.EXTRA_CHANNEL_ID);
                if (ch != null) channel = ch.toString();
                title = cs(extras.getCharSequence(Notification.EXTRA_TITLE));
                text = cs(extras.getCharSequence(Notification.EXTRA_TEXT));
                sub = cs(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
                big = cs(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
                bigTitle = cs(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
                CharSequence[] tl = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (tl != null) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence s : tl) { if (s != null) { if (sb.length() > 0) sb.append(" | "); sb.append(s); } }
                    lines = sb.toString();
                }
            }

            long postTime = sbn.getPostTime();

            // 组装可读内容（优先取最丰富字段）
            String merged = pick(title, bigTitle) + (sub.isEmpty() ? "" : "\n" + sub);
            String body = pick(text, big, lines);
            if (title.isEmpty() && text.isEmpty() && big.isEmpty() && sub.isEmpty() && lines.isEmpty()) {
                // 收到通知但没有任何文本字段：多半是转账/红包/交易等敏感通知
                LogStore.diag(ctx, "[" + appName + "(" + pkg + ")] 收到通知但无可读文本"
                        + (category != null ? "，category=" + category : "")
                        + (channel.isEmpty() ? "" : "，channel=" + channel));
            } else {
                LogStore.log(ctx, appName, pkg, merged, body, postTime);
            }

            // 有正文才写日志（避免把空通知上传）
            if (!title.isEmpty() || !body.isEmpty()) {
                // 面向用户的日更 Markdown 日志
                String readable = (merged.isEmpty() ? "" : merged) +
                        (merged.isEmpty() || body.isEmpty() ? "" : " ") + body;
                DailyLog.append(ctx, postTime, "[" + appName + "] " + readable.trim());
                // 累加当日统计（图表看板数据源）
                String cat = classify(body + title);
                StatsStore.record(ctx, postTime, pkg, cat);
            }

        } catch (Exception e) {
            android.util.Log.w("NotifyListener", "onPosted err", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 无需处理
    }

    @Override
    public void onListenerConnected() {
        // 连接成功（用户授予通知使用权），可在此重排定时任务，并记录诊断日志
        LogStore.diag(getApplicationContext(), "通知监听已连接，开始捕获");
        UploadScheduler.scheduleDaily(this);
        // 启动前台保活，降低后台被杀概率
        startKeepAlive();
    }

    private void startKeepAlive() {
        try {
            android.content.Intent i = new android.content.Intent(this, KeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            android.util.Log.w("NotifyListener", "keepalive start err", e);
        }
    }

    private boolean isSystem(String pkg) {
        return pkg == null || pkg.startsWith("android") || pkg.startsWith("com.android")
                || pkg.startsWith("com.google.android")
                || pkg.equals(getPackageName());
    }

    /** 黑白名单判断。黑名单模式：默认放行，仅拦截黑名单；白名单模式：仅放行白名单。 */
    private boolean isAllowed(Context ctx, String pkg) {
        int mode = Config.getInt(ctx, Config.KEY_FILTER_MODE, Config.FILTER_MODE_BLACKLIST);
        Set<String> set = pkgSet(Config.get(ctx, Config.KEY_BLOCKED, ""), Config.get(ctx, Config.KEY_ALLOWED, ""), mode);
        if (mode == Config.FILTER_MODE_WHITELIST) {
            return set.contains(pkg);
        } else {
            return !set.contains(pkg);
        }
    }

    private Set<String> pkgSet(String blocked, String allowed, int mode) {
        Set<String> s = new HashSet<>();
        String src = (mode == Config.FILTER_MODE_WHITELIST) ? allowed : blocked;
        if (src != null) s.addAll(Arrays.asList(src.split(",")));
        return s;
    }

    /** 简易分类（用于统计图表）：work/todo/urgent/meeting/other。 */
    private String classify(String text) {
        if (text == null) return "other";
        String t = text.toLowerCase();
        String[] urg = {"告警", "警告", "故障", "失败", "爆满", "预警", "urgent", "磁盘", "超时", "error"};
        String[] meet = {"会议", "meeting", "参加", "腾讯会议", "zoom", "评审"};
        String[] work = {"工作", "任务", "项目", "提交", "审批", "加班", "日报", "周报", "需求"};
        String[] todo = {"待办", "提醒", "todo", "完成", "记得", "别忘", "截止", "到期"};
        for (String w : urg) if (t.contains(w)) return "urgent";
        for (String w : meet) if (t.contains(w)) return "meeting";
        for (String w : work) if (t.contains(w)) return "work";
        for (String w : todo) if (t.contains(w)) return "todo";
        return "other";
    }

    private String appNameOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (Exception e) {
            return pkg;
        }
    }

    /** CharSequence 安全转 String。 */
    private static String cs(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /** 取首个非空字符串。 */
    private static String pick(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
