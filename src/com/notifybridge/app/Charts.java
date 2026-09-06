package com.notifybridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘图表组件（纯代码，无第三方依赖）。
 * BarChartView —— 柱状图（柱子上方标注数值）
 * RingChartView —— 环形占比图（中间显示总数）
 * 所有 onDraw 对 null / 空数据都容错，绝不崩溃、绝不空白。
 */
public final class Charts {

    public static final class BarChartView extends View {
        private String[][] data;
        private int barColor = 0xFF8BC7FF;
        private int textColor = 0xFFF5F6FA;

        public BarChartView(Context c, String[][] data) {
            super(c); setData0(data);
        }
        public void setColors(int bar, int text) { this.barColor = bar; this.textColor = text; invalidate(); }
        public void setData(String[][] d) { setData0(d); invalidate(); }

        private void setData0(String[][] d) {
            if (d == null) return;
            this.data = d;
        }

        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (data == null || data.length == 0) return;
            float padB = dp(20), padT = dp(18), padL = dp(6), padR = dp(6);
            float chartH = h - padT - padB;
            if (chartH <= 0) return;
            float slot = (w - padL - padR) / (float) data.length;

            long max = 1;
            for (String[] d : data) {
                if (d == null || d.length < 2) continue;
                try { long v = Long.parseLong(d[1]); if (v > max) max = v; } catch (Exception e) {}
            }

            Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
            bar.setColor(barColor);
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setColor(textColor);
            txt.setTextSize(dp(9));
            txt.setTextAlign(Paint.Align.CENTER);

            for (int i = 0; i < data.length; i++) {
                String[] d = data[i];
                if (d == null) continue;
                long v = 0;
                try { v = Long.parseLong(d[1]); } catch (Exception e) {}
                String label = d.length > 0 && d[0] != null ? d[0] : "";
                float bh = (float) v / max * (chartH - dp(4));
                float cx = padL + slot * i + slot / 2f;
                if (bh > 0) {
                    RectF r = new RectF(cx - slot * 0.27f, padT + (chartH - bh), cx + slot * 0.27f, padT + chartH);
                    cv.drawRoundRect(r, dp(3), dp(3), bar);
                    cv.drawText(String.valueOf(v), cx, r.top - dp(3), txt);
                }
                if (label != null && (data.length <= 12 || i % 2 == 0)) {
                    cv.drawText(label, cx, h - dp(4), txt);
                }
            }
        }
        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }

    public static final class RingChartView extends View {
        private String[][] data;
        private int textColor = 0xFF37474F;
        private int emptyColor = 0xFF3A3F4C;

        public RingChartView(Context c, String[][] data) {
            super(c); if (data != null) this.data = data;
        }
        public void setData(String[][] d) { if (d != null) this.data = d; invalidate(); }
        /** 主题注入：中心文字颜色与空数据底环颜色。 */
        public void setThemeColors(int text, int empty) {
            this.textColor = text; this.emptyColor = empty; invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - dp(8);
            if (r <= 0) return;
            float thick = dp(18);
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);

            long total = 0;
            if (data != null) for (String[] d : data) { if (d == null) continue; try { total += Long.parseLong(d[1]); } catch (Exception e) {} }

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thick);
            float start = -90f;
            if (total > 0 && data != null) {
                for (String[] d : data) {
                    if (d == null || d.length < 3) continue;
                    long v;
                    try { v = Long.parseLong(d[1]); } catch (Exception e) { v = 0; }
                    if (v <= 0) continue;
                    float sweep = (float) v / total * 360f;
                    try { p.setColor(Color.parseColor(d[2])); } catch (Exception e) { p.setColor(0xFF90A4AE); }
                    cv.drawArc(oval, start, sweep - 1f, false, p);
                    start += sweep;
                }
            } else {
                p.setColor(emptyColor);
                cv.drawArc(oval, 0, 360, false, p);
            }

            Paint tb = new Paint(Paint.ANTI_ALIAS_FLAG);
            tb.setColor(textColor);
            tb.setTextAlign(Paint.Align.CENTER);
            tb.setTextSize(dp(16));
            tb.setFakeBoldText(true);
            cv.drawText(String.valueOf(total), cx, cy + dp(2), tb);
            tb.setTextSize(dp(9));
            tb.setFakeBoldText(false);
            cv.drawText("总数", cx, cy + dp(16), tb);
        }
        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
