package com.notifybridge.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻量 UI 工具：双主题统一组件库。
 * 所有颜色取自 ThemeManager（按当前主题自动切换深色玻璃 / 浅色白卡观感）。
 * 提供渐变主按钮、描边次按钮、卡片、输入框、统计胶囊等。
 */
public final class UiKit {

    private UiKit() {}

    /** 主题化卡片/胶囊背景：顶部高光 → 透明 → 卡片底，+ 主题描边。 */
    public static GradientDrawable glassBg(Context ctx, int radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ThemeManager.glare(ctx), 0x00000000, ThemeManager.color(ctx, ThemeManager.IDX_CARD)});
        g.setCornerRadius(px(ctx, radiusDp));
        g.setStroke(1, ThemeManager.border(ctx));
        return g;

    }

    /** 半透明主题输入框/条目底色。 */
    public static GradientDrawable inputBg(Context ctx, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeManager.color(ctx, ThemeManager.IDX_INPUT_BG));
        g.setCornerRadius(px(ctx, radiusDp));
        g.setStroke(1, ThemeManager.border(ctx));
        return g;
    }

    /**
     * 主操作按钮：主渐变实心胶囊 + 白字（贴近设计稿的渐变 CTA）。
     * @param emphasized true=渐变实心主按钮，false=描边次按钮
     */
    public static Button glassButton(Context ctx, String text, boolean emphasized) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(ctx, 14), 0, px(ctx, 14), 0);
        b.setMinHeight(px(ctx, 46));
        b.setAllCaps(false);
        if (emphasized) {
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ThemeManager.color(ctx, ThemeManager.IDX_GRAD_S),
                              ThemeManager.color(ctx, ThemeManager.IDX_GRAD_E)});
            g.setCornerRadius(px(ctx, 24));
            b.setBackground(g);
            b.setTextColor(ThemeManager.color(ctx, ThemeManager.IDX_ON_ACCENT));
        } else {
            b.setBackground(glassBg(ctx, 24));
            b.setTextColor(ThemeManager.accent(ctx));
        }
        return b;
    }

    /** 兼容旧调用：主操作渐变胶囊按钮。 */
    public static Button primary(Context ctx, String text) {
        return glassButton(ctx, text, true);
    }

    /** 兼容旧调用：次要描边按钮。 */
    public static Button ghost(Context ctx, String text) {
        return glassButton(ctx, text, false);
    }

    /** 主题化内容卡片；调用后放入纵向 padding 的视图即可。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassBg(ctx, 18));
        int pad = px(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    /** 小节标题：主题主文本粗体。 */
    public static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ThemeManager.text(ctx));
        tv.setPadding(px(ctx, 2), 0, px(ctx, 2), px(ctx, 8));
        return tv;
    }

    /** 主题化输入框：主题底色、主题文字色、次级 hint。 */
    public static EditText glassInput(Context ctx) {
        EditText et = new EditText(ctx);
        et.setTextColor(ThemeManager.text(ctx));
        et.setHintTextColor(ThemeManager.secondary(ctx));
        et.setTextSize(14);
        et.setPadding(px(ctx, 12), px(ctx, 10), px(ctx, 12), px(ctx, 10));
        et.setBackground(inputBg(ctx, 14));
        return et;
    }

    /** 统计胶囊：主题胶囊底 + 强调色文字。 */
    public static TextView glassTag(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ThemeManager.accent(ctx));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(px(ctx, 10), px(ctx, 5), px(ctx, 10), px(ctx, 5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ThemeManager.color(ctx, ThemeManager.IDX_CHIP_BG));
        g.setCornerRadius(px(ctx, 16));
        g.setStroke(1, ThemeManager.withAlpha(ThemeManager.accent(ctx), 0x26));
        tv.setBackground(g);
        return tv;
    }

    private static int px(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
