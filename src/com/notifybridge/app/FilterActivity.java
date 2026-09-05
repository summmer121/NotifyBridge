package com.notifybridge.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v2 通知过滤设置：AI 智能分类开关 + 黑/白名单 + 应用多选 + 关键词优先级。
 */
public class FilterActivity extends Activity {

    static class AppItem {
        String pkg, label;
        AppItem(String p, String l) { pkg = p; label = l; }
        @Override public String toString() { return label + "\n" + pkg; }
    }

    private List<AppItem> apps = new ArrayList<>();
    private LinearLayout appList;
    private List<CheckBox> appChecks = new ArrayList<>();
    private EditText etKeyword;
    private LinearLayout kwFlow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();

        PackageManager pm = getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                try {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    String pkg = ai.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    String label;
                    try { label = (String) ai.loadLabel(pm); }
                    catch (Throwable t) { label = pkg; }   // 个别应用 label 读取失败不中断
                    if (label == null) label = pkg;
                    apps.add(new AppItem(pkg, label));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            android.util.Log.w("FilterActivity", "load apps err", t);
        }
        // 避免一次性渲染过多导致卡顿/内存问题，仅保留最多 400 个
        if (apps.size() > 400) apps = new java.util.ArrayList<>(apps.subList(0, 400));
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        // 彻底修复：不再嵌套多个 ScrollView（嵌套滚动手势冲突是"已安装应用无法滚动"的根因）。
        // 用 fill-root 垂直布局：顶部固定区(小 ScrollView) + 应用列表(占满剩余、独立滚动)。
        ScrollView topScroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        topScroll.addView(root);
        LinearLayout.LayoutParams mp = m();
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);

        // 外层根：垂直填满屏幕
        LinearLayout pageRoot = new LinearLayout(this);
        pageRoot.setOrientation(LinearLayout.VERTICAL);
        pageRoot.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        // 应用列表是主角：占满剩余高度；顶部设置区用固定紧凑高度（内部可滚），
        // 避免 weight 与 WRAP 混算导致的高层错乱/遮挡
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));   // 顶部设置区固定 240dp(容纳开关+模式+关键词)
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);   // 应用列表占满剩余

        TextView title = new TextView(this);
        title.setText("🔍 通知过滤设置");
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#37474F"));
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        // 标题固定放页面顶部（不随设置区滚动），后续在组装处加入 pageRoot

        // === AI 智能分类 ===
        section(root, "🤖 AI 智能分类", mp);
        root.addView(swRow("自动识别通知、提取待办、标记紧急", Config.KEY_AI_ENABLED, true), mp);
        root.addView(swRow("📌 提取待办事项", Config.KEY_AI_TODO, true), mp);
        root.addView(swRow("🚨 标记紧急信息", Config.KEY_AI_URGENT, true), mp);
        root.addView(swRow("📊 生成每日纪要", Config.KEY_AI_REPORT, true), mp);

        // === 过滤模式 ===
        section(root, "📋 过滤模式", mp);
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbBlack = new RadioButton(this);
        rbBlack.setId(1);
        rbBlack.setText("黑名单（屏蔽所选）");
        RadioButton rbWhite = new RadioButton(this);
        rbWhite.setId(2);
        rbWhite.setText("白名单（仅放行所选）");
        rg.addView(rbBlack);
        rg.addView(rbWhite);
        int curMode = Config.getInt(this, Config.KEY_FILTER_MODE, Config.FILTER_MODE_BLACKLIST);
        rbBlack.setChecked(curMode == Config.FILTER_MODE_BLACKLIST);
        rbWhite.setChecked(curMode == Config.FILTER_MODE_WHITELIST);
        root.addView(rg, mp);

        // === 应用列表：独立 ScrollView 占满剩余高度（不嵌套外层 Scroll，彻底可滚）===
        section(root, "📍 已安装应用（下方列表滚动选择）", mp);

        android.widget.ScrollView appScroll = new android.widget.ScrollView(this);
        appScroll.setSmoothScrollingEnabled(true);
        appScroll.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(dp(4), dp(4), dp(4), dp(8));
        appChecks.clear();

        // 检索框：应用列表最顶部，实时过滤下方 CheckBox（只显隐，不重建，保留勾选状态）
        EditText etSearch = new EditText(this);
        etSearch.setHint("🔍 搜索应用名称 / 包名，如 微信 / tencent");
        etSearch.setTextSize(14);
        etSearch.setSingleLine(true);
        etSearch.setPadding(dp(8), dp(4), dp(8), dp(4));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                applyAppFilter(s == null ? "" : s.toString().trim());
            }
        });
        // 注意：etSearch 不加入 appList，而是在布局组装阶段固定到 pageRoot（topScroll 与 appScroll 之间），
        // 这样搜索框固定不随应用列表滚动。

        for (AppItem a : apps) {
            CheckBox cb = new CheckBox(this);
            cb.setText(a.label);
            cb.setTag(a.pkg);
            cb.setTextSize(13);
            cb.setTextColor(Color.parseColor("#37474F"));
            cb.setPadding(0, dp(2), 0, dp(2));   // 压缩行高，一屏显示更多 App
            appList.addView(cb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            appChecks.add(cb);
        }
        appScroll.addView(appList, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
        markCheckedFromConfig();
        // appScroll 加到 pageRoot，weight=3 占满屏幕剩余（唯一滚动区之一，不嵌套）

        // === 关键词优先级 ===
        section(root, "🏷️ 关键词优先级", mp);
        LinearLayout kwRow = new LinearLayout(this);
        kwRow.setOrientation(LinearLayout.HORIZONTAL);
        etKeyword = new EditText(this);
        etKeyword.setHint("输入关键词");
        Button btnAdd = new Button(this);
        btnAdd.setText("添加");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addKeyword(etKeyword.getText().toString().trim()); }
        });
        kwRow.addView(etKeyword, new LinearLayout.LayoutParams(0, -2, 1f));
        kwRow.addView(btnAdd);
        root.addView(kwRow, mp);

        kwFlow = new LinearLayout(this);
        kwFlow.setOrientation(LinearLayout.VERTICAL);
        root.addView(kwFlow, mp);
        renderKeywords();

        // === 保存 ===
        Button btnSave = new Button(this);
        btnSave.setText("💾 保存过滤设置");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setBackgroundColor(Color.parseColor("#1976D2"));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int mode = rg.getCheckedRadioButtonId() == 2
                        ? Config.FILTER_MODE_WHITELIST : Config.FILTER_MODE_BLACKLIST;
                saveSelection(mode);
                showToast("过滤设置已保存", false);
                finish();
            }
        });
        // 注意：btnSave 不加入 root(顶部区)，直接放 pageRoot 底部固定栏，避免同一视图被两个父容器添加
        // 组装：标题(固定) + 顶部设置区 + 应用列表（各自独立滚动，不嵌套，彻底消除手势冲突）
        // 保存按钮固定放页面底部，始终可见，不占应用列表高度
        pageRoot.addView(title, mp);
        pageRoot.addView(topScroll, topLp);

        // 搜索框固定行：置于顶部设置区与可滚动应用列表之间，不随应用列表滚动
        etSearch.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(8), dp(4), dp(8), dp(2));
        pageRoot.addView(etSearch, searchLp);

        pageRoot.addView(appScroll, listLp);

        // 底部固定保存栏
        btnSave.setPadding(dp(0), dp(12), dp(0), dp(12));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(dp(16), dp(4), dp(16), dp(8));
        pageRoot.addView(btnSave, saveLp);

        setContentView(pageRoot);
        applyEdgeToEdge(pageRoot);
    }

    private void section(LinearLayout root, String t, LinearLayout.LayoutParams mp) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(16);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#5A9BD5"));
        tv.setPadding(dp(4), dp(20), dp(4), dp(6));
        root.addView(tv, mp);
    }

    private LinearLayout swRow(String label, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(15);
        lbl.setTextColor(Color.parseColor("#37474F"));
        Switch sw = new Switch(this);
        sw.setChecked(Config.getBool(this, key, def));
        sw.setOnCheckedChangeListener((v, isOn) -> Config.putBool(this, key, isOn));
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw);
        return row;
    }

    private void renderKeywords() {
        kwFlow.removeAllViews();
        String kw = Config.get(this, Config.KEY_KEYWORDS, "");
        if (kw.isEmpty()) {
            addKwText("（暂无自定义关键词，可在下方添加）", "#90A4AE");
        } else {
            for (String k : kw.split(",")) {
                if (!k.trim().isEmpty()) addKwText(k.trim(), "#5A9BD5");
            }
        }
    }

    private void addKwText(String text, String color) {
        TextView tv = new TextView(this);
        tv.setText("🏷 " + text);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor(color));
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        kwFlow.addView(tv, m());
    }

    private void addKeyword(String kw) {
        if (kw.isEmpty()) { showToast("请输入关键词", true); return; }
        Set<String> set = new HashSet<>();
        String cur = Config.get(this, Config.KEY_KEYWORDS, "");
        if (!cur.isEmpty()) for (String s : cur.split(",")) if (!s.trim().isEmpty()) set.add(s.trim());
        set.add(kw);
        Config.put(this, Config.KEY_KEYWORDS, String.join(",", set));
        etKeyword.setText("");
        renderKeywords();
        showToast("已添加关键词：" + kw, false);
    }

    /**
     * 按关键词实时过滤应用列表：只显隐 CheckBox，不重建视图，确保已勾选状态不丢失。
     * @param kw 关键词（空 = 显示全部）
     */
    private void applyAppFilter(String kw) {
        boolean empty = (kw == null || kw.isEmpty());
        String k = kw == null ? "" : kw.toLowerCase();
        for (int i = 0; i < appChecks.size() && i < apps.size(); i++) {
            CheckBox cb = appChecks.get(i);
            boolean match;
            if (empty) {
                match = true;
            } else {
                String label = apps.get(i).label == null ? "" : apps.get(i).label.toLowerCase();
                String pkg = apps.get(i).pkg == null ? "" : apps.get(i).pkg.toLowerCase();
                match = label.contains(k) || pkg.contains(k);
            }
            cb.setVisibility(match ? View.VISIBLE : View.GONE);
        }
    }

    /** 把当前配置模式下已选中的应用在列表中勾选出来。 */
    private void markCheckedFromConfig() {
        int mode = Config.getInt(this, Config.KEY_FILTER_MODE, Config.FILTER_MODE_BLACKLIST);
        String key = (mode == Config.FILTER_MODE_WHITELIST) ? Config.KEY_ALLOWED : Config.KEY_BLOCKED;
        Set<String> sel = toSet(Config.get(this, key, ""));
        for (int i = 0; i < appChecks.size() && i < apps.size(); i++) {
            appChecks.get(i).setChecked(sel.contains(apps.get(i).pkg));
        }
    }

    /** 保存勾选应用到所选模式。 */
    private void saveSelection(int mode) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appChecks.size() && i < apps.size(); i++) {
            if (appChecks.get(i).isChecked()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(apps.get(i).pkg);
            }
        }
        Config.putInt(this, Config.KEY_FILTER_MODE, mode);
        Config.put(this,
                mode == Config.FILTER_MODE_WHITELIST ? Config.KEY_ALLOWED : Config.KEY_BLOCKED,
                sb.toString());
    }

    private Set<String> toSet(String csv) {
        Set<String> s = new HashSet<>();
        if (csv != null) for (String x : csv.split(",")) if (!x.isEmpty()) s.add(x);
        return s;
    }

    private LinearLayout.LayoutParams m() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupSystemBars() {
        try {
            android.view.Window w = getWindow();
            final int sdk = android.os.Build.VERSION.SDK_INT;
            int flags = 0;
            if (sdk >= 23) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (sdk >= 26) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            try { w.setStatusBarColor(0xFF7CB9E8); } catch (Throwable ignore) {}
            try { w.setNavigationBarColor(0xFFFFFDF7); } catch (Throwable ignore) {}
            final android.view.View decor = w.getDecorView();
            decor.setSystemUiVisibility(flags);
            if (sdk >= 30) {
                try {
                    java.lang.reflect.Method m = android.view.Window.class.getMethod(
                            "setDecorFitsSystemWindows", boolean.class);
                    m.invoke(w, false);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    private void applyEdgeToEdge(final android.view.View root) {
        try {
            root.setFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(
                        android.view.View v, android.view.WindowInsets insets) {
                    try {
                        int top = 0;
                        try { top = insets.getSystemWindowInsetTop(); } catch (Throwable ignore) {}
                        v.setPadding(insets.getSystemWindowInsetLeft(), top,
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom() + dp(2));
                    } catch (Throwable ignore) {}
                    return android.view.WindowInsets.CONSUMED;
                }
            });
            root.requestApplyInsets();
        } catch (Throwable ignore) {}
    }

    private void showToast(final String msg, final boolean err) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast t = Toast.makeText(FilterActivity.this, "", Toast.LENGTH_LONG);
                TextView v = new TextView(FilterActivity.this);
                v.setText(msg);
                v.setTextSize(14);
                v.setPadding(dp(20), dp(12), dp(20), dp(12));
                v.setTextColor(android.graphics.Color.parseColor(err ? "#C0392B" : "#2E7D32"));
                v.setBackgroundColor(android.graphics.Color.parseColor(err ? "#FDEDEC" : "#F1F8E9"));
                v.setGravity(Gravity.CENTER);
                t.setView(v);
                t.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, dp(60));
                t.show();
            }
        });
    }
}
