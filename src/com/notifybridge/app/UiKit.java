package com.notifybridge.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻量 UI 工具：深色毛玻璃风格的统一组件。
 * 所有颜色取自 ThemeManager。提供主/次玻璃按钮、玻璃卡片、玻璃输入框、统计胶囊等。
 */
public final class UiKit {

    private UiKit() {}

    /** 玻璃圆角矩形容器背景：半透深灰 + 顶部高光 + 细白描边。 */
    public static GradientDrawable glassBg(Context ctx, int radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ThemeManager.GLASS_GLARE, 0x00000000, ThemeManager.GLASS});
        g.setCornerRadius(px(ctx, radiusDp));
        g.setStroke(1, ThemeManager.GLASS_BORDER);
        return g;
    }

    /**
     * 主操作玻璃按钮：冰蓝渐变 + 半透冰蓝底 + 冰蓝描边。
     * @param emphasized true=强主按钮（冰蓝描边+淡冰蓝底），false=次级描边玻璃按钮
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
        int accent = ThemeManager.color(ctx, ThemeManager.IDX_ACCENT);
        if (emphasized) {
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x262F6FA8, 0x1A8BC7FF});
            g.setCornerRadius(px(ctx, 24));
            g.setStroke(1, 0x4D8BC7FF);
            b.setBackground(g);
            b.setTextColor(ThemeManager.ICE);
        } else {
            b.setBackground(glassBg(ctx, 24));
            b.setTextColor(accent);
        }
        return b;
    }

    /** 兼容旧调用：主操作胶囊按钮（毛玻璃冰蓝）。 */
    public static Button primary(Context ctx, String text) {
        return glassButton(ctx, text, true);
    }

    /** 兼容旧调用：次要描边玻璃按钮。 */
    public static Button ghost(Context ctx, String text) {
        return glassButton(ctx, text, false);
    }

    /** 玻璃内容卡片；调用后放入纵向 padding 的视图即可。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassBg(ctx, 18));
        int pad = px(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    /** 小节标题：白色粗体主标题 + 底部淡灰下划线。 */
    public static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ThemeManager.TEXT);
        tv.setPadding(px(ctx, 2), 0, px(ctx, 2), px(ctx, 8));
        return tv;
    }

    /** 玻璃输入框：半透深底、白字、浅灰 hint、细白边框。 */
    public static EditText glassInput(Context ctx) {
        EditText et = new EditText(ctx);
        et.setTextColor(ThemeManager.TEXT);
        et.setHintTextColor(ThemeManager.SECONDARY);
        et.setTextSize(14);
        et.setPadding(px(ctx, 12), px(ctx, 10), px(ctx, 12), px(ctx, 10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14303440);
        g.setCornerRadius(px(ctx, 14));
        g.setStroke(1, ThemeManager.GLASS_BORDER);
        et.setBackground(g);
        return et;
    }

    /** 统计胶囊：半透深底 + 细白边 + 强调色文字。 */
    public static TextView glassTag(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ThemeManager.ICE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(px(ctx, 10), px(ctx, 5), px(ctx, 10), px(ctx, 5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1A2A2F3E);
        g.setCornerRadius(px(ctx, 16));
        g.setStroke(1, 0x148BC7FF);
        tv.setBackground(g);
        return tv;
    }

    private static int px(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
