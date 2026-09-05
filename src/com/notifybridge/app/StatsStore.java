package com.notifybridge.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每日通知统计采集器。
 * 按天累积：总数、分类（work/todo/urgent/meeting）、各App计数、小时分布。
 * 用 SharedPreferences 持久化，供首页图表看板读取。
 */
public final class StatsStore {
    private static final String PREF = "notify_stats";
    private StatsStore() {}

    public static String dateKey(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }
    public static String todayKey() { return dateKey(System.currentTimeMillis()); }
    public static String dateKeyMMDD(String key) { return key == null ? "" : key.substring(5); }

    /** 记录一条通知到当天统计。 */
    public static void record(Context ctx, long postTime, String pkg, String category) {
        try {
            String day = dateKey(postTime);
            android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = sp.edit();

            // 总数
            e.putLong("total:" + day, sp.getLong("total:" + day, 0) + 1);

            // 分类
            if (category != null) {
                String k = "cat:" + category + ":" + day;
                e.putLong(k, sp.getLong(k, 0) + 1);
            }

            // App
            String appKey = "app:" + day + ":" + pkg;
            e.putLong(appKey, sp.getLong(appKey, 0) + 1);

            // 小时
            int hour = Integer.parseInt(
                    new java.text.SimpleDateFormat("HH", Locale.US).format(new Date(postTime)));
            String hKey = "hour:" + day + ":" + hour;
            e.putLong(hKey, sp.getLong(hKey, 0) + 1);

            // 记录该日期已存在
            String dates = sp.getString("dates", "");
            if (!dates.contains(day)) e.putString("dates", dates.isEmpty() ? day : dates + "," + day);

            e.apply();
        } catch (Exception ignored) {}
    }

    // ---- 读取 ----

    /** 当天某分类计数。 */
    public static long cat(Context ctx, String day, String category) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("cat:" + category + ":" + day, 0);
    }

    /** 当天总数。 */
    public static long total(Context ctx, String day) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("total:" + day, 0);
    }

    /** 当天 App 计数（按 count 倒序）。 */
    public static List<String[]> appTop(Context ctx, String day, int n) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Map<String, Long> m = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("app:" + day + ":")) {
                m.put(k.substring(("app:" + day + ":").length()), (Long) e.getValue());
            }
        }
        List<Map.Entry<String, Long>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            out.add(new String[]{list.get(i).getKey(), String.valueOf(list.get(i).getValue())});
        }
        return out;
    }

    /** 当天小时分布（24 个值）。 */
    public static long[] hourDist(Context ctx, String day) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long[] h = new long[24];
        for (int i = 0; i < 24; i++) h[i] = sp.getLong("hour:" + day + ":" + i, 0);
        return h;
    }

    /** 最近 n 天（含今天）每天总通知数，附带日期标签；不足 n 天的用 0 填充。 */
    public static String[][] lastNDays(Context ctx, int n) {
        String[][] out = new String[n][2];
        Calendar c = Calendar.getInstance();
        Long[] vals = new Long[n];
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        for (int i = n - 1; i >= 0; i--) {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
            vals[i] = sp.getLong("total:" + day, 0);
            out[i][0] = day.substring(5).replace("-", "/");   // MM/dd
            out[i][1] = String.valueOf(vals[i]);
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        return out;
    }

    /** 是否有任何真实统计数据（任意日期 total>0）。用于判断是否需用演示数据。 */
    public static boolean hasAnyData(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("total:") && ((Number) e.getValue()).longValue() > 0) return true;
        }
        return false;
    }
}