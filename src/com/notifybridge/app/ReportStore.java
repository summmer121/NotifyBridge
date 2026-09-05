package com.notifybridge.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI 纪要缓存（按日期持久化）。
 * 设计目标：
 *  - 每天纪要在"当天"只由用户主动触发生成一次；
 *  - 历史日期（如昨天）每个日期全量只生成一次，App 启动时自动补生成；
 *  - 同一天重复生成时以"最晚更新"为准（覆盖写）。
 * 存储用 SharedPreferences（key 前缀 report:yyyy-MM-dd）。
 */
public final class ReportStore {

    public static final String PREF = "notifybridge_reports";

    private ReportStore() {}

    public static String dateKey(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }

    public static String dateKeyNow() {
        return dateKey(new Date());
    }

    /** 昨天日期字符串 yyyy-MM-dd。 */
    public static String yesterdayKey() {
        return dateKey(new Date(System.currentTimeMillis() - 24L * 3600 * 1000));
    }

    /** 读取某天纪要；没有则返回 null。 */
    public static String getReport(Context ctx, String dateKey) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("report:" + dateKey, null);
    }

    /** 某天是否已生成过纪要。 */
    public static boolean hasReport(Context ctx, String dateKey) {
        return getReport(ctx, dateKey) != null;
    }

    /** 保存某天纪要（覆盖写，即以最晚生成为主）。返回写入时间戳。 */
    public static long saveReport(Context ctx, String dateKey, String content) {
        long now = System.currentTimeMillis();
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString("report:" + dateKey, content)
                .putLong("report_ts:" + dateKey, now)
                .apply();
        return now;
    }

    /** 读取某天纪要的生成时间戳；无则返回 0。 */
    public static long getReportTs(Context ctx, String dateKey) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("report_ts:" + dateKey, 0);
    }

    /** 记录某天"已用全量通知生成"。用于避免重复全量生成。 */
    public static void markGenerated(Context ctx, String dateKey, int sourceCount) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt("gen_count:" + dateKey, sourceCount).apply();
    }

    /** 当天是否已用全量生成过。 */
    public static boolean generatedForToday(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt("gen_count:" + dateKeyNow(), 0) > 0;
    }

    /** 返回所有已有纪要的日期（yyyy-MM-dd），按时间倒序（最新在前）。 */
    public static java.util.List<String> allDates(Context ctx) {
        java.util.List<String> dates = new java.util.ArrayList<>();
        java.util.Map<String, ?> all = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getAll();
        for (String k : all.keySet()) {
            if (k.startsWith("report:") && all.get(k) instanceof String) {
                dates.add(k.substring("report:".length()));
            }
        }
        java.util.Collections.sort(dates, java.util.Collections.reverseOrder());
        return dates;
    }
}
