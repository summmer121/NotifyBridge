package com.notifybridge.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * 上传执行器：把本地缓存的通知 Markdown 批量上传到 WebDAV，成功后删除本地缓存。
 * 在后台线程执行，通过回调回到主线程通知 UI。
 */
public final class Uploader {
    private static final String TAG = "Uploader";

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private Uploader() {}

    /** 测试 WebDAV 连接（子线程执行）。 */
    public static void test(final Context ctx, final String url, final String user,
                            final String pass, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                String msg;
                try {
                    WebDavClient client = new WebDavClient(url, user, pass);
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
        }).start();
    }

    /** 执行上传。可在任意线程调用；网络在子线程进行。 */
    public static void run(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
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
                    // 记录目标服务器
                    LogStore.diag(ctx, "WebDAV 目标: " + client.getBaseUrl());

                    File[] files = NotificationCache.pendingFiles(ctx);
                    if (files.length == 0) {
                        ok = true;
                        msg = "没有待上传数据";
                    } else {
                        int up = 0;
                        long totalBytes = 0;
                        for (File f : files) {
                            if (up >= 5) break;            // 单次最多批量传 5 个文件，保持节奏
                            String content = NotificationCache.readFile(ctx, f);
                            if (totalBytes + content.length() > 1024 * 1024) break;  // 累积上限 ~1MB
                            String brief = content.length() > 80 ? content.substring(0, 80) + "…" : content;
                            LogStore.diag(ctx, "开始上传: " + f.getName() + "（" + content.length() + "字节）：" + brief);

                            client.ensureDir("notifybridge");
                            int before = content.length();
                            client.uploadText("notifybridge/" + f.getName(), content);
                            LogStore.diag(ctx, "✅ 上传成功: " + f.getName() + "（" + before + "字节 → " + client.getBaseUrl() + "notifybridge/" + f.getName() + "）");
                            NotificationCache.deleteFile(ctx, f);
                            up++;
                            totalBytes += content.length();
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
        }).start();
    }
}
