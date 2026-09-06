package com.notifybridge.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Daily-markdown log storage. Each day produces one file named
 * dailynoteYYYYMMDD.md (e.g. dailynote20260906.md) under files/dailylogs.
 * Old files are pruned to keep only the most recent N days (default 7).
 */
public final class DailyLog {
    private static final String TAG = "DailyLog";
    private static final String DIR = "dailylogs";
    private static final String PREFIX = "dailynote";

    // Avoid re-scanning the directory on every append within the same day.
    private static volatile String lastSweepDay = "";

    private DailyLog() {}

    /** Append one timestamped markdown line to today's file (for timestamp ts). */
    public static synchronized void append(Context c, long ts, String line) {
        try {
            File f = fileFor(c, ts);
            ensureHeader(f, ts);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts));
            String clean = line == null ? "" : line.replace("\n", " ").replace("\r", " ");
            byte[] data = ("- **" + time + "**  " + clean + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = new FileOutputStream(f, true)) {
                os.write(data);
            }
            Log.i(TAG, "written " + f.getName() + " <- " + time + " " +
                    (clean.length() > 60 ? clean.substring(0, 60) + "…" : clean));
            sweep(c, keepDays(c));
        } catch (Exception e) {
            Log.w(TAG, "append err", e);
        }
    }

    /** Convenience for appending with the current wall-clock time. */
    public static void appendNow(Context c, String line) {
        append(c, System.currentTimeMillis(), line);
    }

    /** List dailynote files, oldest first. */
    public static synchronized File[] files(Context c) {
        File dir = dir(c);
        File[] list = dir.listFiles();
        if (list == null) return new File[0];
        List<File> out = new ArrayList<>();
        for (File f : list) {
            if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(".md")) {
                out.add(f);
            }
        }
        List<File> sorted = new ArrayList<>(out);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
        return sorted.toArray(new File[0]);
    }

    /** Read a dailynote file's full content. */
    public static String readFile(File f) {
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) sb.append(l).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Prune dailynote files older than keepDays. */
    public static synchronized void sweep(Context c, int keepDays) {
        try {
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new Date(System.currentTimeMillis()));
            if (today.equals(lastSweepDay)) return;
            lastSweepDay = today;

            long cutoff = System.currentTimeMillis() - (long) keepDays * 24L * 3600L * 1000L;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
            for (File f : files(c)) {
                String name = f.getName();
                if (!name.startsWith(PREFIX)) continue;
                String ymd = name.substring(PREFIX.length(), name.length() - ".md".length())
                        .replace("-", "");
                if (ymd.length() != 8) continue;
                try {
                    Date d = sdf.parse(ymd);
                    if (d != null && d.getTime() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "sweep err", e);
        }
    }

    private static int keepDays(Context c) {
        int d = Config.getInt(c, Config.KEY_DAILY_KEEP_DAYS, 7);
        return Math.max(1, d);
    }

    private static void ensureHeader(File f, long ts) throws Exception {
        if (f.exists() && f.length() > 0) return;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts));
        StringBuilder sb = new StringBuilder();
        sb.append("# Daily Note ").append(day).append("\n\n");
        try (OutputStream os = new FileOutputStream(f, true)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, long ts) {
        String ymd = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ts));
        return new File(dir(c), PREFIX + ymd + ".md");
    }
}
