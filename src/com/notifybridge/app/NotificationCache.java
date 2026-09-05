package com.notifybridge.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 通知本地缓存：将待上传的通知追加到应用私有目录的当日 Markdown 文件。
 * 每条通知格式化为可读的 Markdown 列表项，便于后续 AI 直接解析。
 */
public final class NotificationCache {
    private static final String TAG = "NotificationCache";
    private static final String CACHE_DIR = "pending";

    private NotificationCache() {}

    /** 追加一条通知到当天缓存文件。返回缓存文件绝对路径。 */
    public static synchronized void append(Context c, String pkg, String appName,
                                           String title, String text, long postTime) {
        try {
            File dir = new File(c.getFilesDir(), CACHE_DIR);
            if (!dir.exists()) dir.mkdirs();

            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(postTime));
            File f = new File(dir, date + "-notifications.md");

            StringBuilder sb = new StringBuilder();
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date(postTime));
            sb.append("- [").append(time).append("] ")
              .append("**").append(escape(appName)).append("** (")
              .append(escape(pkg)).append(")");
            if (title != null && !title.isEmpty()) {
                sb.append(" | ").append(escape(title));
            }
            if (text != null && !text.isEmpty()) {
                sb.append("\n  ").append(escape(text));
            }
            sb.append("\n");

            appendBytes(f, sb.toString().getBytes(StandardCharsets.UTF_8));
            int cnt = Config.getInt(c, Config.KEY_CACHED_COUNT, 0);
            Config.putInt(c, Config.KEY_CACHED_COUNT, cnt + 1);
        } catch (IOException e) {
            android.util.Log.w(TAG, "append failed", e);
        }
    }

    /** 待上传缓存文件列表（按日期名排序）。 */
    public static synchronized File[] pendingFiles(Context c) {
        File dir = new File(c.getFilesDir(), CACHE_DIR);
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        java.util.Arrays.sort(files);
        return files;
    }

    /** 读取指定缓存文件的完整内容。 */
    public static synchronized String readFile(Context c, File f) {
        return readFile(f);
    }

    /** 读取缓存文件全部内容。 */
    public static synchronized String readAll(Context c) {
        StringBuilder sb = new StringBuilder();
        for (File f : pendingFiles(c)) {
            sb.append("<!-- ").append(f.getName()).append(" -->\n");
            sb.append(readFile(f));
        }
        return sb.toString();
    }

    /** 删除指定缓存文件。 */
    public static synchronized boolean deleteFile(Context c, File f) {
        boolean ok = f.delete();
        if (ok) {
            int cnt = Config.getInt(c, Config.KEY_CACHED_COUNT, 0);
            Config.putInt(c, Config.KEY_CACHED_COUNT, Math.max(0, cnt - countLines(f)));
        }
        return ok;
    }

    /** 清空全部缓存。返回删除的文件数。 */
    public static synchronized int clearAll(Context c) {
        File[] files = pendingFiles(c);
        int n = 0;
        for (File f : files) if (f.delete()) n++;
        Config.putInt(c, Config.KEY_CACHED_COUNT, 0);
        return n;
    }

    private static int countLines(File f) {
        try {
            String s = readFile(f);
            int n = 0;
            for (String line : s.split("\n")) {
                if (line.trim().startsWith("- [")) n++;
            }
            return n;
        } catch (Exception e) { return 0; }
    }

    private static String readFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) baos.write(buf, 0, r);
            in.close();
            return baos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void appendBytes(File f, byte[] data) throws IOException {
        try (OutputStream os = new FileOutputStream(f, true)) {
            os.write(data);
        }
    }

    /** 转义 Markdown 特殊字符，防止注入/换行破坏结构。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("|", "\\|");
    }
}
