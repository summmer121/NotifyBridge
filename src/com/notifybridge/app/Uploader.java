package com.notifybridge.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 上传执行器：把本地按天生成的 daily note Markdown 批量上传到 WebDAV。
 * 上传成功不删除本地（本地文件由 7 天滚动清理），目标目录可在配置里指定。
 * 在后台线程执行，通过回调回到主线程通知 UI。
 */
public final class Uploader {
    private static final String TAG = "Uploader";

    /**
     * 单线程串行执行所有上传任务：自动定时、手动同步、batch 触发共用同一队列，
     * 避免并发线程对同一批文件重复/交错上传。
     */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private Uploader() {}

    /** 生成上传到云端文件的第一行状态说明。 */
    private static String statusLine(File f, long now) {
        boolean full = !isToday(f.getName(), now);
        String label = full ? "全量日志" : "非全量（今日）";
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
        return "> 日志状态：" + label + " · 上传时间 " + ts + "\n\n";
    }

    /** 通过文件名 dailynoteYYYYMMDD.md 判断是否属于"今天"。 */
    private static boolean isToday(String fileName, long now) {
        try {
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(now));
            String ymd = fileName.replace("dailynote", "").replace(".md", "");
            return today.equals(ymd);
        } catch (Exception e) {
            return true;  // 解析失败按"今天"处理，标记为当日新增
        }
    }

    /** 测试 WebDAV 连接（子线程执行）。 */
    public static void test(final Context ctx, final String url, final String user,
                            final String pass, final Callback cb) {
        EXEC.submit(new Runnable() {
            @Override public void run() {
                boolean ok;
                String msg;
                try {
                    WebDavClient client = new WebDavClient(url, user, pass);
                    if (url.startsWith("http://")) {
                        LogStore.diag(ctx, "⚠️ 使用明文 HTTP，WebDAV 凭证会以 Base64 明文传输，请优先启用 HTTPS");
                    }
                    String r = client.test();
                    ok = true;
                    msg = "连接成功 " + r;
                    LogStore.diag(ctx, "✅ 连接成功: " + client.getBaseUrl() + " → " + r);
                } catch (Exception e) {
                    ok = false;
                    String em = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    msg = "连接失败：" + em;
                    LogStore.diag(ctx, "❌ 连接失败: " + em);
                }
                final boolean fOk = ok;
                final String fMsg = msg;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { if (cb != null) cb.onResult(fOk, fMsg); }
                });
            }
        });
    }

    /** 执行上传。可在任意线程调用；网络在子线程进行。 */
    public static void run(final Context ctx, final Callback cb) {
        EXEC.submit(new Runnable() {
            @Override public void run() {
                boolean ok;
                String msg;
                try {
                    String url = Config.get(ctx, Config.KEY_URL, "");
                    String user = Config.get(ctx, Config.KEY_USER, "");
                    String pass = Config.getPassword(ctx);
                    if (url.isEmpty() || user.isEmpty()) {
                        throw new IllegalStateException("请先配置 WebDAV 服务器与账号");
                    }
                    WebDavClient client = new WebDavClient(url, user, pass);
                    if (url.startsWith("http://")) {
                        LogStore.diag(ctx, "⚠️ 使用明文 HTTP，WebDAV 凭证会以 Base64 明文传输，请优先启用 HTTPS");
                    }
                    // 记录目标服务器
                    LogStore.diag(ctx, "WebDAV 目标: " + client.getBaseUrl());
                    // 上传前清理已上传的过期文件；未上传的旧数据不会被删
                    DailyLog.sweepNow(ctx);
                    File[] files = DailyLog.files(ctx);
                    if (files.length == 0) {
                        ok = true;
                        msg = "没有待上传数据";
                    } else {
                        int up = 0;
                        long totalBytes = 0;
                        long now = System.currentTimeMillis();
                        for (File f : files) {
                            String body = DailyLog.readFile(f);
                            if (body.isEmpty()) continue;
                            if (totalBytes + body.length() > 1024 * 1024) break;  // 累积上限 ~1MB
                            // 昨日的文件若已作为"全量"上传过，定时任务不再重复覆盖/重复标注
                            if (!isToday(f.getName(), now) && DailyLog.isFullUploaded(ctx, f.getName())) {
                                LogStore.diag(ctx, "⏭ 昨日已全量上传，定时同步跳过: " + f.getName());
                                continue;
                            }
                            // 云端文件第一行标注是否全量 + 上传时间，然后附上正文
                            String cloud = statusLine(f, now) + body;
                            client.uploadText(f.getName(), cloud);
                            DailyLog.markUploaded(ctx, f.getName(), true);
                            LogStore.diag(ctx, "✅ 上传成功: " + f.getName()
                                    + "（" + body.length() + "字节 → " + client.getBaseUrl() + f.getName() + "）");
                            up++;
                            totalBytes += body.length();
                        }
                        ok = true;
                        msg = "已上传 " + up + " 个文件";
                        Config.put(ctx, Config.KEY_LAST_SYNC, String.valueOf(System.currentTimeMillis()));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "upload error", e);
                    ok = false;
                    String em = e.getMessage();
                    // 诊断日志：记录失败详情（含 HTTP 状态码）
                    LogStore.diag(ctx, "❌ 上传失败: " + (em == null ? e.getClass().getSimpleName() : em));
                    msg = "上传失败：" + (em == null ? e.getClass().getSimpleName() : em);
                }

                final boolean fOk = ok;
                final String fMsg = msg;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { if (cb != null) cb.onResult(fOk, fMsg); }
                });
            }
        });
    }

    /**
     * 手动上传：按用户要求处理"昨天 + 今天"两份日志。
     * 1. 昨天：若已作为全量上传过则跳过；否则上传昨天全量并标记。
     * 2. 今天：总是上传，首行标注"非全量"，并显示上传时间。
     * 供次日首次登录、开机补传、手动同步调用。
     */
    public static void uploadManual(final Context ctx, final Callback cb) {
        EXEC.submit(new Runnable() {
            @Override public void run() {
                boolean ok;
                String msg;
                try {
                    String url = Config.get(ctx, Config.KEY_URL, "");
                    String user = Config.get(ctx, Config.KEY_USER, "");
                    String pass = Config.getPassword(ctx);
                    if (url.isEmpty() || user.isEmpty()) {
                        throw new IllegalStateException("请先配置 WebDAV 服务器与账号");
                    }
                    WebDavClient client = new WebDavClient(url, user, pass);
                    if (url.startsWith("http://")) {
                        LogStore.diag(ctx, "⚠️ 使用明文 HTTP，WebDAV 凭证会以 Base64 明文传输，请优先启用 HTTPS");
                    }
                    LogStore.diag(ctx, "WebDAV 目标: " + client.getBaseUrl());

                    StringBuilder parts = new StringBuilder();

                    String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date(System.currentTimeMillis() - 24L * 3600L * 1000L));
                    File f = DailyLog.fileForDay(ctx, dayKey);
                    if (f == null || !f.exists()) {
                        parts.append("昨日无日志，");
                    } else if (DailyLog.isFullUploaded(ctx, f.getName())) {
                        parts.append("昨日日志已全量上传，跳过；");
                        LogStore.diag(ctx, "⏭ 昨日日志已全量上传，跳过: " + f.getName());
                    } else {
                        String body = DailyLog.readFile(f);
                        String cloud = statusLine(f, System.currentTimeMillis()) + body;
                        client.uploadText(f.getName(), cloud);
                        DailyLog.markUploaded(ctx, f.getName(), true);
                        DailyLog.markFullUploaded(ctx, f.getName(), true);
                        LogStore.diag(ctx, "✅ 昨天全量日志已上传: " + f.getName()
                                + "（" + body.length() + "字节 → " + client.getBaseUrl() + f.getName() + "）");
                        parts.append("已上传昨日全量 ").append(f.getName()).append("；");
                    }

                    String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date());
                    File t = DailyLog.fileForDay(ctx, todayKey);
                    if (t == null || !t.exists()) {
                        parts.append("今日暂无日志。");
                    } else {
                        String body = DailyLog.readFile(t);
                        String cloud = statusLine(t, System.currentTimeMillis()) + body;
                        client.uploadText(t.getName(), cloud);
                        DailyLog.markUploaded(ctx, t.getName(), true);
                        LogStore.diag(ctx, "✅ 今日非全量日志已上传: " + t.getName()
                                + "（" + body.length() + "字节 → " + client.getBaseUrl() + t.getName() + "）");
                        parts.append("已上传今日非全量 ").append(t.getName()).append("。");
                    }

                    Config.put(ctx, Config.KEY_LAST_SYNC, String.valueOf(System.currentTimeMillis()));
                    ok = true;
                    msg = parts.toString();
                } catch (Exception e) {
                    Log.w(TAG, "upload yesterday error", e);
                    ok = false;
                    String em = e.getMessage();
                    LogStore.diag(ctx, "❌ 手动上传失败: " + (em == null ? e.getClass().getSimpleName() : em));
                    msg = "上传失败：" + (em == null ? e.getClass().getSimpleName() : em);
                }

                final boolean fOk = ok;
                final String fMsg = msg;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { if (cb != null) cb.onResult(fOk, fMsg); }
                });
            }
        });
    }
}
