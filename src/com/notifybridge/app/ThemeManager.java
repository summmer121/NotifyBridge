package com.notifybridge.app;

import android.content.Context;

/**
 * 主题引擎：整体固定为「深色毛玻璃」暗夜风格。
 * 4 个主题选项仅改变强调色微差，底色统一为深炭黑 #121218，
 * 保证全局一致的 Glassmorphism 观感。所有页面取色统一走这里。
 */
public final class ThemeManager {

    public static final String[] THEME_NAMES = {"深色毛玻璃", "冰蓝冷调", "薄荷清新", "紫夜科技"};

    // 固定背景：深炭黑
    public static final int BG = 0xFF121218;
    // 毛玻璃卡片底色：半透深灰
    public static final int GLASS = 0xFF1E202D;
    // 卡片边框：细半透明白
    public static final int GLASS_BORDER = 0x14FFFFFF; // ~8%
    // 卡片顶部高光（玻璃感）
    public static final int GLASS_GLARE = 0x14FFFFFF;

    // 文本层级
    public static final int TEXT = 0xFFF5F6FA;      // 主文本 白
    public static final int SECONDARY = 0xFF9AA3B2; // 次级文本 浅灰
    public static final int MUTED = 0xFF6C7484;     // 辅助文本 淡灰

    // 强调色（低饱和，暗夜友好）
    public static final int ICE = 0xFF8BC7FF;   // 淡冰蓝
    public static final int MINT = 0xFF82E0AA;  // 薄荷绿
    public static final int VIOLET = 0xFFC3A6FF;// 浅紫
    public static final int ORANGE = 0xFFE5B57A;// 浅橙（警示）
    public static final int RED = 0xFFE08A8A;   // 暗红（错误）

    // 每套主题强调色（primary, bg, card, text, secondary, accent, gradStart, gradEnd, tabOn, tabBg）
    private static final int[][] C = {
        // 0 深色毛玻璃（默认）
        {0xFF8BC7FF, 0xFF121218, 0xFF1E202D, 0xFFF5F6FA, 0xFF9AA3B2, 0xFF8BC7FF, 0xFF6FA8DC, 0xFF8BC7FF, 0xFF8BC7FF, 0xFF1E202D},
        // 1 冰蓝冷调
        {0xFF9BD4FF, 0xFF121218, 0xFF1E202D, 0xFFF5F6FA, 0xFF9AA3B2, 0xFF6FA8DC, 0xFF5F9EE8, 0xFF9BD4FF, 0xFF9BD4FF, 0xFF1E202D},
        // 2 薄荷清新
        {0xFF7FE0B0, 0xFF121218, 0xFF1E202D, 0xFFF5F6FA, 0xFF9AA3B2, 0xFF7FE0B0, 0xFF5CC88F, 0xFF9BE8C4, 0xFF7FE0B0, 0xFF1E202D},
        // 3 紫夜科技
        {0xFFC9ADFF, 0xFF121218, 0xFF1E202D, 0xFFF5F6FA, 0xFF9AA3B2, 0xFFC9ADFF, 0xFF8A6BDE, 0xFFC9ADFF, 0xFFC9ADFF, 0xFF1E202D},
    };

    public static final int IDX_PRIMARY=0, IDX_CARD=2, IDX_TEXT=3, IDX_SECONDARY=4,
            IDX_ACCENT=5, IDX_GRAD_S=6, IDX_GRAD_E=7, IDX_TAB_ON=8, IDX_TAB_BG=9;

    public static int themeIndex(Context ctx) {
        String s = Config.get(ctx, Config.KEY_THEME, "0");
        try { int i = Integer.parseInt(s.trim()); if (i < 0 || i >= C.length) return 0; return i; }
        catch (Exception e) { return 0; }
    }

    public static void setTheme(Context ctx, int idx) {
        Config.put(ctx, Config.KEY_THEME, String.valueOf(idx));
    }

    /** 当前主题的强调配色（int）。 */
    public static int color(Context ctx, int which) {
        return C[themeIndex(ctx)][which];
    }

    public static int color(int idx, int which) {
        return C[idx][which];
    }

    public static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }

    // ------- 辅助：带圆角 drawable -------
    public static android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * 3);
        return g;
    }

    public static android.graphics.drawable.GradientDrawable rounded(int strokeColor, int fill, int radiusDp, int strokeW) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * 3);
        if (strokeW > 0) g.setStroke(strokeW * 3, strokeColor);
        return g;
    }

    // ------- 玻璃质感升级助手 -------

    /** 统一毛玻璃卡片圆角（dp）。所有玻璃卡片用同一半径，呈现统一层级。 */
    public static final int CARD_RADIUS = 18;

    /**
     * 毛玻璃卡片背景：半透深灰填充 + 顶部细白高光渐变 + 1dp 半透明白描边。
     * 模拟 Glassmorphism 的玻璃质感（真模糊受 API 限制，用高光+半透明近似）。
     */
    public static android.graphics.drawable.GradientDrawable cardBgStroked(Context ctx, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{GLASS_GLARE, 0x00000000, GLASS});
        g.setCornerRadius(radiusDp * 3);
        g.setStroke(3, GLASS_BORDER);
        return g;
    }

    /** 给任意 View 应用柔和浮起。毛玻璃依赖高光与描边，故阴影极轻。 */
    public static void elevate(android.view.View v, int dpShadow) {
        try { v.setElevation(dpShadow * 3); }
        catch (Throwable ignored) { }
    }

    /** 一键毛玻璃卡片：统一圆角 + 玻璃高光描边 + 轻微浮起（纯 Java 动态 UI 用）。 */
    public static void styleCard(android.view.View v, Context ctx, int radiusDp) {
        v.setBackground(cardBgStroked(ctx, radiusDp));
        v.setClipToOutline(true);
        elevate(v, 1);
    }
}
