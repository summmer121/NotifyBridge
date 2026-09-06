package com.notifybridge.app;

import android.content.Context;

/**
 * 主题引擎 v2：双主题体系。
 *  0 = 「霓虹暗夜」：深蓝黑底 + 青/紫霓虹渐变 + 玻璃拟态卡片（参考深色设计稿）
 *  1 = 「晴空白日」：米白底 + 白色圆角卡片 + 蓝色主色 + 彩色渐变统计卡（参考浅色设计稿）
 *
 * 所有页面取色统一走 color(ctx, idx)，禁止在页面里硬编码颜色。
 * 每套主题 16 个索引位，覆盖 bg/card/border/input/tab 等全部场景。
 */
public final class ThemeManager {

    // ---- 索引定义 ----
    public static final int IDX_PRIMARY = 0;   // 主强调（统计数字、高亮文字）
    public static final int IDX_BG      = 1;   // 页面背景
    public static final int IDX_CARD    = 2;   // 卡片底色
    public static final int IDX_TEXT    = 3;   // 主文本
    public static final int IDX_SECONDARY = 4; // 次级文本
    public static final int IDX_ACCENT  = 5;   // 强调（链接、按钮文字、tab 选中）
    public static final int IDX_GRAD_S  = 6;   // 主渐变起（按钮/胶囊）
    public static final int IDX_GRAD_E  = 7;   // 主渐变止
    public static final int IDX_TAB_ON  = 8;   // 底部 tab 选中色
    public static final int IDX_TAB_BG  = 9;   // 底部 tab 容器底
    public static final int IDX_BORDER  = 10;  // 卡片描边
    public static final int IDX_GLARE   = 11;  // 卡片顶部高光（玻璃感）
    public static final int IDX_MUTED   = 12;  // 辅助/弱化文本
    public static final int IDX_INPUT_BG = 13; // 输入框底色
    public static final int IDX_CHIP_BG = 14;  // 小胶囊/选中块底色（半透明）
    public static final int IDX_ON_ACCENT = 15; // 渐变按钮上的文字色

    public static final String[] THEME_NAMES = {"🌃 霓虹暗夜", "🌤️ 晴空白日"};

    // 每套主题 16 色板
    private static final int[][] C = {
        // 0 霓虹暗夜（深蓝黑 + 霓虹青/紫）
        new int[]{
            0xFF4DD8FF,   // PRIMARY 霓虹青
            0xFF0A0F1E,   // BG 深蓝黑
            0xFF141C31,   // CARD 深蓝卡片
            0xFFEDF2FF,   // TEXT
            0xFF8A93B2,   // SECONDARY
            0xFF4DD8FF,   // ACCENT
            0xFF6D5DF6,   // GRAD_S 靛蓝紫
            0xFFB44DF0,   // GRAD_E 亮紫
            0xFF4DD8FF,   // TAB_ON
            0xFF141C31,   // TAB_BG
            0x334DD8FF,   // BORDER 霓虹青 20%
            0x1A4DD8FF,   // GLARE 玻璃高光
            0xFF5A6478,   // MUTED
            0x1A16203A,   // INPUT_BG 半透深蓝
            0x1A2A3A5A,   // CHIP_BG
            0xFFFFFFFF,   // ON_ACCENT
        },
        // 1 晴空白日（米白 + 白卡 + 蓝）
        new int[]{
            0xFF4A7DF0,   // PRIMARY 主蓝
            0xFFF6F2EA,   // BG 米白
            0xFFFFFFFF,   // CARD 白卡
            0xFF23283B,   // TEXT 深蓝灰
            0xFF7A8090,   // SECONDARY
            0xFF4A7DF0,   // ACCENT
            0xFF5B8DEF,   // GRAD_S
            0xFF7B9FF5,   // GRAD_E
            0xFF4A7DF0,   // TAB_ON
            0xFFFFFFFF,   // TAB_BG
            0x16000000,   // BORDER 淡灰描边
            0x00FFFFFF,   // GLARE 浅色不需要高光
            0xFFA8AEBB,   // MUTED
            0xFFF1EEE6,   // INPUT_BG 浅米灰
            0x0F000000,   // CHIP_BG
            0xFFFFFFFF,   // ON_ACCENT
        },
    };

    /** 彩色统计卡渐变（每套主题 4 组：start/end 成对）。用于首页统计格，贴近设计稿。 */
    private static final int[][][] STAT_GRADS = {
        // 霓虹暗夜：青 / 紫 / 粉 / 绿 霓虹系
        {{0xFF22D3EE, 0xFF4DD8FF}, {0xFF8B5CF6, 0xFFA78BFA}, {0xFFF472B6, 0xFFC084FC}, {0xFF34D399, 0xFF6EE7B7}},
        // 晴空白日：蓝 / 紫 / 红橙 / 绿 多彩系
        {{0xFF5B8DEF, 0xFF7B9FF5}, {0xFF8B5CF6, 0xFFA78BFA}, {0xFFF0645A, 0xFFF59E73}, {0xFF34B77F, 0xFF6FD5A8}},
    };

    /** 环形图分类色（工作/待办/紧急/会议/其他）。 */
    private static final int[][] CAT_COLORS = {
        {0xFF4DD8FF, 0xFFA78BFA, 0xFFF472B6, 0xFF34D399, 0xFF64748B},  // 霓虹
        {0xFF5B8DEF, 0xFFF59E73, 0xFFE5484D, 0xFF8E24AA, 0xFFA8AEBB},  // 浅色多彩
    };

    public static int themeIndex(Context ctx) {
        String s = Config.get(ctx, Config.KEY_THEME, "0");
        try { int i = Integer.parseInt(s.trim()); if (i < 0 || i >= C.length) return 0; return i; }
        catch (Exception e) { return 0; }
    }

    public static void setTheme(Context ctx, int idx) {
        Config.put(ctx, Config.KEY_THEME, String.valueOf(idx));
    }

    /** 当前主题是否为浅色（状态栏图标、Toast 风格判断用）。 */
    public static boolean isLight(Context ctx) {
        return themeIndex(ctx) == 1;
    }

    /** 当前主题取色。 */
    public static int color(Context ctx, int which) {
        return C[themeIndex(ctx)][which];
    }

    /** 指定主题取色（主题预览 swatch 用）。 */
    public static int color(int idx, int which) {
        return C[idx][which];
    }

    /** 当前主题第 i 组统计卡渐变 {start, end}。 */
    public static int[] statGrad(Context ctx, int i) {
        return STAT_GRADS[themeIndex(ctx)][i % 4];
    }

    /** 当前主题环形图分类色组（5 色）。 */
    public static int[] catColors(Context ctx) {
        return CAT_COLORS[themeIndex(ctx)];
    }

    /** 给颜色换 alpha（0~255），保留 RGB。 */
    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (0x00FFFFFF & color);
    }

    public static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }

    // ---- 兼容旧代码的语义方法（新代码也可用，可读性更好） ----
    public static int bg(Context ctx)        { return color(ctx, IDX_BG); }
    public static int text(Context ctx)      { return color(ctx, IDX_TEXT); }
    public static int secondary(Context ctx) { return color(ctx, IDX_SECONDARY); }
    public static int accent(Context ctx)    { return color(ctx, IDX_ACCENT); }
    public static int muted(Context ctx)     { return color(ctx, IDX_MUTED); }
    public static int card(Context ctx)      { return color(ctx, IDX_CARD); }
    public static int border(Context ctx)    { return color(ctx, IDX_BORDER); }

    // ------- 带 alpha 的语义色（玻璃高光/描边随主题） -------
    public static int glare(Context ctx)     { return color(ctx, IDX_GLARE); }

    // ------- 辅助：带圆角 drawable -------
    public static android.graphics.drawable.GradientDrawable rounded(Context ctx, int fill, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * 3);
        return g;
    }

    /** 兼容旧调用：不带 ctx 的 rounded（用霓虹主题卡片色）。 */
    public static android.graphics.drawable.GradientDrawable rounded(int fill, int radiusDp) {
        return rounded(fill, radiusDp, 0, 0);
    }

    public static android.graphics.drawable.GradientDrawable rounded(int strokeColor, int fill, int radiusDp, int strokeW) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * 3);
        if (strokeW > 0) g.setStroke(strokeW * 3, strokeColor);
        return g;
    }

    // ------- 卡片背景 -------

    /** 统一卡片圆角（dp）。 */
    public static final int CARD_RADIUS = 18;

    /**
     * 卡片背景：顶部高光 → 透明 → 卡片底色 三段渐变 + 主题描边。
     * 深色主题呈玻璃质感；浅色主题为白卡 + 细灰描边。
     */
    public static android.graphics.drawable.GradientDrawable cardBgStroked(Context ctx, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{glare(ctx), 0x00000000, color(ctx, IDX_CARD)});
        g.setCornerRadius(radiusDp * 3);
        g.setStroke(3, border(ctx));
        return g;
    }

    /** 给任意 View 应用柔和浮起。 */
    public static void elevate(android.view.View v, int dpShadow) {
        try { v.setElevation(dpShadow * 3); }
        catch (Throwable ignored) { }
    }

    /** 一键卡片：统一圆角 + 主题描边 + 轻微浮起（纯 Java 动态 UI 用）。 */
    public static void styleCard(android.view.View v, Context ctx, int radiusDp) {
        v.setBackground(cardBgStroked(ctx, radiusDp));
        v.setClipToOutline(true);
        elevate(v, 1);
    }
}
