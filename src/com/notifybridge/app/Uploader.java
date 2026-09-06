package com.notifybridge.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
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
                        int up = 0, skipped = 0;
                        long totalBytes = 0;
                        for (File f : files) {
                            String content = DailyLog.readFile(f);
                            if (totalBytes + content.length() > 1024 * 1024) break;  // 累积上限 ~1MB
                            // 直接上传到 WebDAV 地址所指的目录（不再额外拼子目录）
                            String remotePath = f.getName();
                            // 与云端一致性比对：一致跳过，不一致或云端不存在则上传
                            String remote = client.fetchText(remotePath);
                            if (remote != null && remote.equals(content)) {
                                skipped++;
                                DailyLog.markUploaded(ctx, f.getName(), true);
                                LogStore.diag(ctx, "⏭️ 与云端一致，跳过: " + f.getName());
                                continue;
                            }
                            String brief = content.length() > 80 ? content.substring(0, 80) + "…" : content;
                            LogStore.diag(ctx, "开始上传: " + f.getName() + "（" + content.length() + "字节）：" + brief);

                            int before = content.length();
                            client.uploadText(remotePath, content);
                            DailyLog.markUploaded(ctx, f.getName(), true);
                            LogStore.diag(ctx, "✅ 上传成功: " + f.getName() + "（" + before + "字节 → " + client.getBaseUrl() + remotePath + "）");
                            up++;
                            totalBytes += content.length();
                        }
                        ok = true;
                        msg = "一致跳过 " + skipped + "，已上传 " + up + " 个文件";
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
}
