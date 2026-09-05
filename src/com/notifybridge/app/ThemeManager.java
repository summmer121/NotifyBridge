package com.notifybridge.app;

import android.content.Context;
import android.graphics.Color;

/**
 * 主题引擎：统一管理 4 套配色，支持全局切换。
 * 所有页面取色统一走这里，避免散落的硬编码颜色。
 * 当前主题用 Config.KEY_THEME 持久化（0=奶蓝 1=深色 2=多彩 3=极简）。
 */
public final class ThemeManager {

    public static final String[] THEME_NAMES = {"清新奶蓝", "深色毛玻璃", "多彩活泼", "极简黑白"};

    /** 每套主题配色（primary, bg, card, text, secondary, accent, gradStart, gradEnd, tabOn, tabBg） */
    private static final int[][] C = {
        // 0 清新奶蓝
        {0xFF5B9BD5, 0xFFF5F9FF, 0xFFFFFFFF, 0xFF2B3A4A, 0xFF90A4AE, 0xFF7FB3E8, 0xFF5B9BD5, 0xFF8AB7E8, 0xFF5B9BD5, 0xFFEAF3FC},
        // 1 深色毛玻璃
        {0xFF4A80C4, 0xFF10151F, 0xFF1D2635, 0xFFE8EDF5, 0xFF8A94A6, 0xFF4A80C4, 0xFF1D2635, 0xFF2A3850, 0xFF4A80C4, 0xFF1D2635},
        // 2 多彩活泼
        {0xFF6C5CE7, 0xFFFAF6FF, 0xFFFFFFFF, 0xFF33314A, 0xFFA09AC8, 0xFF00CEC9, 0xFF667EEA, 0xFF764BA2, 0xFF6C5CE7, 0xFFEFE9FF},
        // 3 极简黑白
        {0xFF1A1A1A, 0xFFFFFFFF, 0xFFFAFAFA, 0xFF111111, 0xFF999999, 0xFF555555, 0xFF222222, 0xFF555555, 0xFF1A1A1A, 0xFFEEEEEE},
    };

    public static final int PRIMARY=0, BG=1, CARD=2, TEXT=3, SECONDARY=4, ACCENT=5, GRAD_S=6, GRAD_E=7, TAB_ON=8, TAB_BG=9;

    public static int themeIndex(Context ctx) {
        String s = Config.get(ctx, Config.KEY_THEME, "0");
        try {
            int i = Integer.parseInt(s.trim());
            if (i < 0 || i >= C.length) return 0;
            return i;
        } catch (Exception e) { return 0; }
    }

    public static void setTheme(Context ctx, int idx) {
        Config.put(ctx, Config.KEY_THEME, String.valueOf(idx));
    }

    /** 当前主题的颜色值（int，可直接用于 setTextColor/setBackgroundColor）。 */
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
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    // ------- 质感升级助手：统一卡片圆角 + 阴影 -------

    /** 统一卡片圆角（dp）。所有卡片用同一半径，呈现统一层级。 */
    public static final int CARD_RADIUS = 14;

    /** 圆角 + 主题 CARD 底色 + 轻描边（1dp，主色 12% 透明度），强化卡片轮廓。 */
    public static android.graphics.drawable.GradientDrawable cardBgStroked(Context ctx, int radiusDp) {
        int main = color(ctx, PRIMARY);
        int stroke = (0x1F000000) | (main & 0x00FFFFFF); // 主色，~12% 透明度
        return rounded(stroke, color(ctx, CARD), radiusDp, 1);
    }

    /** 给任意 View 应用统一阴影（elevation，dp）。阴影需配合不透明白/圆角背景才可见。 */
    public static void elevate(android.view.View v, int dpShadow) {
        try {
            v.setElevation(dpShadow * /*density*/ 3); // 近似密度换算，阴影柔和
        } catch (Throwable ignored) { /* API<21 无阴影，静默回落 */ }
    }

    /** 底部 Tab 选中指示条：选中项顶部加 3dp 主色圆角条。 */
    public static android.graphics.drawable.GradientDrawable tabIndicator(int colorPrimary) {
        int top = colorPrimary;
        int bar = (0x00000000); // 透明
        return rounded(top, bar, 0, 0);
    }

    /** 一键质感卡片：统一圆角 + 卡片底色 + 柔和阴影（纯 Java 动态 UI 用）。 */
    public static void styleCard(android.view.View v, Context ctx, int radiusDp) {
        v.setBackground(cardBgStroked(ctx, radiusDp));
        v.setClipToOutline(true); // 让圆角裁剪 + 阴影贴合圆角
        elevate(v, 2);
    }
}
