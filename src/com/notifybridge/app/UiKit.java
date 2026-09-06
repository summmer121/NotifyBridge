package com.notifybridge.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻量 UI 工具：统一胶囊按钮、圆角卡片、小节标题的观感。
 * 所有颜色取自 ThemeManager，随主题切换自动变化。
 */
public final class UiKit {

    private UiKit() {}

    /** 主操作胶囊按钮：渐变主色底 + 白色文字 + 圆角 + 阴影。 */
    public static Button primary(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(ctx, 14), 0, px(ctx, 14), 0);
        b.setMinHeight(px(ctx, 46));
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ThemeManager.color(ctx, ThemeManager.GRAD_S),
                        ThemeManager.color(ctx, ThemeManager.GRAD_E)});
        g.setCornerRadius(px(ctx, 22));
        b.setBackground(g);
        try { b.setElevation(px(ctx, 2)); } catch (Throwable ignored) {}
        return b;
    }

    /** 次要胶囊按钮：卡片底 + 主色文字 + 主色描边。 */
    public static Button ghost(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(ThemeManager.color(ctx, ThemeManager.PRIMARY));
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(ctx, 14), 0, px(ctx, 14), 0);
        b.setMinHeight(px(ctx, 46));
        int main = ThemeManager.color(ctx, ThemeManager.PRIMARY);
        int stroke = (0x33000000) | (main & 0x00FFFFFF); // 主色 ~20% 透明描边
        b.setBackground(ThemeManager.rounded(stroke, ThemeManager.color(ctx, ThemeManager.CARD), 22, 1));
        return b;
    }

    /** 圆角内容卡片；调用后放入纵向 padding 的视图即可。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        ThemeManager.styleCard(card, ctx, ThemeManager.CARD_RADIUS);
        int pad = px(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    /** 小节标题：主色小字 + 深色正文标题，保持统一间距。 */
    public static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ThemeManager.color(ctx, ThemeManager.TEXT));
        tv.setPadding(px(ctx, 4), 0, px(ctx, 4), px(ctx, 6));
        return tv;
    }

    private static int px(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
