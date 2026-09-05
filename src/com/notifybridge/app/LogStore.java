package com.notifybridge.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 通知捕获日志存储。NotifyListener 每次捕获到一条通知，追加写入本地日志文件，
 * 供“日志”分页实时展示，便于验证监听链路是否工作。
 */
public final class LogStore {
    private static final String TAG = "LogStore";
    private static final String LOG_NAME = "capture.log";
    private static final int MAX_LINES = 500; // 最多保留行数，防止无限增长

    private LogStore() {}

    /** 追加一条捕获日志。 */
    public static synchronized void log(Context c, String appName, String pkg,
                                        String title, String text, long postTime) {
        try {
            File f = logFile(c);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(postTime));
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(time).append("] ")
              .append(appName).append(" (").append(pkg).append(")");
            if (title != null && !title.isEmpty()) sb.append(" | ").append(title);
            if (text != null && !text.isEmpty()) sb.append(" | ").append(text);
            sb.append("\n");

            // 追加写入
            try (OutputStream os = new FileOutputStream(f, true)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            trim(c, f);
        } catch (Exception e) {
            android.util.Log.w(TAG, "log err", e);
        }
    }

    /** 追加一条诊断日志（非通知，例如监听连接/上传触发）。 */
    public static synchronized void diag(Context c, String msg) {
        try {
            File f = logFile(c);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            try (OutputStream os = new FileOutputStream(f, true)) {
                os.write(("[[" + time + "]] " + msg + "\n").getBytes(StandardCharsets.UTF_8));
            }
            trim(c, f);
        } catch (Exception e) {
            android.util.Log.w(TAG, "diag err", e);
        }
    }

    /** 追加一条原始捕获痕迹（未过滤前记录，用于诊断“通知是否到达监听器”）。 */
    public static synchronized void raw(Context c, String pkg, long postTime) {
        try {
            File f = logFile(c);
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(postTime));
            try (OutputStream os = new FileOutputStream(f, true)) {
                os.write((">> RAW [" + time + "] " + pkg + "\n").getBytes(StandardCharsets.UTF_8));
            }
            trim(c, f);
        } catch (Exception e) {
            android.util.Log.w(TAG, "raw err", e);
        }
    }

    /** 读取日志（最新在最前）。 */
    public static synchronized List<String> read(Context c, int max) {
        List<String> lines = new ArrayList<>();
        try {
            File f = logFile(c);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String l;
            while ((l = br.readLine()) != null) {
                if (!l.trim().isEmpty()) lines.add(l);
            }
            br.close();
        } catch (Exception e) {
            // 文件不存在当作空
        }
        // 倒序（最新在前）
        java.util.Collections.reverse(lines);
        if (lines.size() > max) lines = new ArrayList<>(lines.subList(0, max));
        return lines;
    }

    /** 清空日志。 */
    public static synchronized void clear(Context c) {
        File f = logFile(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static File logFile(Context c) {
        File dir = new File(c.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, LOG_NAME);
    }

    /** 截断到 MAX_LINES 行。 */
    private static void trim(Context c, File f) {
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = br.readLine()) != null) lines.add(l);
            br.close();
            if (lines.size() > MAX_LINES) {
                List<String> tail = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
                // 重写
                StringBuilder sb = new StringBuilder();
                for (String s : tail) sb.append(s).append("\n");
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {}
    }
}
