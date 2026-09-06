package com.notifybridge.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 主界面：顶部 Tab（配置 / 日志 左右分页）。
 * 配置页：WebDAV 设置、通知监听授权、自动同步、立即同步、清空缓存。
 * 日志页：实时展示 NotifyListener 捕获到的通知，用于验证监听链路。
 */
public class MainActivity extends Activity {

    private EditText etUrl, etUser, etPass, etDir;
    private TextView tvStatus, tvCached;
    private Switch swSync;

    // 首页 + 四个主页面容器
    private View homePageView, logPage, aiPage, settingsPage;
    private TextView tvTabConfig, tvTabLog, tvLogHint;
    private android.widget.FrameLayout contentContainer;
    private LinearLayout bottomBar;
    private TextView[] bottomTabs = new TextView[4];
    private TextView tvBarSub;
    private android.view.View[] tabIndicators = new android.view.View[4];
    // AI 页面引用
    private TextView tvAiCount, tvAiDigest, tvAiWork, tvAiTodo, tvAiUrgent, tvAiGenTime;
    private LinearLayout aiTodoList, aiSuggestList;
    private Button btnManualGen;
    // 纪要看板页引用
    private TextView tvKanbanDate, tvKanbanNotice, tvKanbanTodo, tvKanbanDone, tvKanbanUrgent;
    private TextView tvKanbanContent;
    private TextView tvKanbanLog;
    private ListView lvLog;
    // AI 模型配置（设置页内嵌输入框）
    private EditText aiBase, aiKey, aiModel, aiRelay, aiPrompt;
    private android.widget.RadioGroup aiModeGroup;
    // 首页信息统计
    private TextView statToday, statNotice, statReport, statCumNotice, statCumDays, statExtra;
    private Charts.BarChartView chart7d;
    private Charts.RingChartView chartCat;
    private TextView catLegend;
    private LinearLayout appTopList;
    private Charts.BarChartView chartHour;
    private TextView vsToday, vsYesterday;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refresher;
    private final java.util.List<android.widget.TextView> menuArrows = new java.util.ArrayList<>();
    private int currentSettingsPanel = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();
        LinearLayout root = buildUi();
        setContentView(root);
        // 真·edge-to-edge：内容延伸到状态栏+手势条下，并按系统栏 inset 给根部局加 padding
        applyEdgeToEdge(root);
        switchMainTab(0);
        refreshCachedCount();
        refreshListenerState();
        startLogRefresh();
        autoGenYesterdayIfMissing(); // 检查昨天纪要，缺失则自动补生成
    }

    /**
     * 设置状态栏/导航栏为小清新浅色，并让内容真正铺满屏幕。
     * 小米15 / Android 15 正解：反射调用 Window.setDecorFitsSystemWindows(false)，
     * 让内容延伸到状态栏+手势条区域（真·edge-to-edge），再用手势条 inset 做底部 padding。
     */
    private void setupSystemBars() {
        try {
            android.view.Window w = getWindow();
            final int sdk = android.os.Build.VERSION.SDK_INT;

            // 状态栏 / 导航栏配色（深色图标）
            int flags = 0;
            int bg = ThemeManager.color(this, ThemeManager.BG);
            if (sdk >= 23) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (sdk >= 26) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            try { w.setStatusBarColor(bg); } catch (Throwable ignore) {}
            try { w.setNavigationBarColor(bg); } catch (Throwable ignore) {}
            if (sdk >= 28) { try { w.setNavigationBarDividerColor(0x00000000); } catch (Throwable ignore) {} }
            final android.view.View decor = w.getDecorView();
            decor.setSystemUiVisibility(flags);

            // Android 11+ (API 30+) 真·edge-to-edge：内容延伸到系统栏，杜绝底部留空
            if (sdk >= 30) {
                try {
                    java.lang.reflect.Method m = android.view.Window.class.getMethod(
                            "setDecorFitsSystemWindows", boolean.class);
                    m.invoke(w, false);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 在根内容视图上应用系统栏 inset padding，并消费掉 insets。
     * - top = 状态栏高度：内容从状态栏下方开始，Tab 栏不顶到状态栏里（修"翘上天"）
     * - bottom = 手势条高度：内容不被底部手势条遮挡，底部显示奶白
     */
    private void applyEdgeToEdge(final android.view.View root) {
        try {
            root.setFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
                @Override
                public android.view.WindowInsets onApplyWindowInsets(
                        android.view.View v, android.view.WindowInsets insets) {
                    try {
                        int bottom = insets.getSystemWindowInsetBottom();
                        int left = insets.getSystemWindowInsetLeft();
                        int right = insets.getSystemWindowInsetRight();
                        int top = 0;
                        try {
                            top = insets.getSystemWindowInsetTop();
                        } catch (Throwable ignore) {}
                        v.setPadding(left, top, right, bottom);
                    } catch (Throwable ignore) {}
                    // 消费掉 insets，防止 framework 重复应用导致内容被顶回去
                    return android.view.WindowInsets.CONSUMED;
                }
            });
            root.requestApplyInsets();
        } catch (Throwable ignore) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshListenerState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogRefresh();
    }

    // ============ UI 构建 ============

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));

        // 顶部品牌栏：渐变底 + 标题 + 小副标题
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(18), dp(14), dp(18), dp(14));
        android.graphics.drawable.GradientDrawable topGrad = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ThemeManager.color(this, ThemeManager.GRAD_S),
                        ThemeManager.color(this, ThemeManager.GRAD_E)});
        topGrad.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, dp(20), dp(20)});
        topBar.setBackground(topGrad);
        TextView tvBarTitle = new TextView(this);
        tvBarTitle.setText("NotifyBridge");
        tvBarTitle.setTextSize(21);
        tvBarTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvBarTitle.setTextColor(Color.WHITE);
        tvBarSub = new TextView(this);
        tvBarSub.setText("通知察觉 · 每日纪要 · AI 智能助手");
        tvBarSub.setTextSize(12);
        tvBarSub.setTextColor(0xB3FFFFFF);
        tvBarSub.setPadding(0, dp(2), 0, 0);
        topBar.addView(tvBarTitle);
        topBar.addView(tvBarSub);
        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 内容容器（承载 4 个主页面）
        contentContainer = new android.widget.FrameLayout(this);
        root.addView(contentContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 构建四个页面
        homePageView = buildHomePage();   // 首页（信息统计 + 配置）
        logPage = buildLogPage();          // 日志页（第2个Tab）
        aiPage = buildAiPage();
        settingsPage = buildSettingsPage();

        contentContainer.addView(homePageView, matcher());
        contentContainer.addView(logPage, matcher());
        contentContainer.addView(aiPage, matcher());
        contentContainer.addView(settingsPage, matcher());

        // 底部 4-Tab 导航
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        bottomBar.setElevation(dp(8));
        String[] labels = {"🏠 首页", "📋 日志", "🤖 AI", "👤 我的"};
        tabIndicators = new android.view.View[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            // 每个 Tab 用 FrameLayout 容器：顶部指示条 + 文字，实现「选中上滑指示条」
            android.widget.FrameLayout tabCell = new android.widget.FrameLayout(this);
            bottomTabs[i] = new TextView(this);
            bottomTabs[i].setText(labels[i]);
            bottomTabs[i].setTextSize(13);
            bottomTabs[i].setGravity(Gravity.CENTER);
            bottomTabs[i].setPadding(0, dp(14), 0, dp(12));
            bottomTabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchMainTab(idx); }
            });
            tabCell.addView(bottomTabs[i], new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            // 顶部指示条：默认透明，选中时主色
            android.view.View ind = new android.view.View(this);
            ind.setBackgroundColor(ThemeManager.color(this, ThemeManager.TAB_ON));
            ind.setVisibility(View.INVISIBLE);
            tabIndicators[i] = ind;
            tabCell.addView(ind, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(3),
                    android.view.Gravity.TOP));
            bottomBar.addView(tabCell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(bottomBar);

        return root;
    }

    /** 底部主 Tab 切换。 */
    private void switchMainTab(int idx) {
        homePageView.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        logPage.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        aiPage.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        if (idx == 2) refreshAiPage();
        if (idx == 1) refreshLog();
        if (idx == 0) refreshHomeStats();   // 回首页刷新统计
        // 底部 tab 高亮：顶部指示条 + 圆角选中胶囊 + 加粗
        int on = ThemeManager.color(this, ThemeManager.TAB_ON);
        int off = ThemeManager.color(this, ThemeManager.SECONDARY);
        int card = ThemeManager.color(this, ThemeManager.CARD);
        for (int i = 0; i < bottomTabs.length; i++) {
            if (bottomTabs[i] != null) {
                boolean sel = (i == idx);
                bottomTabs[i].setTextColor(sel ? on : off);
                bottomTabs[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                // 选中：圆角主色淡底胶囊；未选中：透明
                if (sel) {
                    int pill = (0x20FFFFFF) & (on); // 主色调透明胶囊底，浅色主题下柔和
                    bottomTabs[i].setBackground(ThemeManager.rounded(pill, 0, 12, 0));
                } else {
                    bottomTabs[i].setBackgroundColor(Color.TRANSPARENT);
                }
            }
            if (tabIndicators != null && tabIndicators[i] != null) {
                tabIndicators[i].setVisibility(idx == i ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    // ---- 首页（信息统计 + 配置 + 日志，整页滚动）----

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        wrap.setPadding(dp(12), dp(10), dp(12), dp(12));
        scroll.addView(wrap);
        LinearLayout.LayoutParams mp = matcher();

        // ① 信息统计区
        TextView statTitle = new TextView(this);
        statTitle.setText("📊 信息总览");
        statTitle.setTextSize(17);
        statTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statTitle.setTextColor(Color.parseColor("#37474F"));
        wrap.addView(statTitle, mp);

        LinearLayout statCard = new LinearLayout(this);
        statCard.setOrientation(LinearLayout.HORIZONTAL);
        ThemeManager.styleCard(statCard, this, ThemeManager.CARD_RADIUS);
        statCard.setPadding(dp(4), dp(6), dp(4), dp(6));
        statToday = statCellHome(statCard, "📈", "今天通知");
        statReport = statCellHome(statCard, "📝", "今日纪要");
        wrap.addView(statCard, mp);

        LinearLayout statCard2 = new LinearLayout(this);
        statCard2.setOrientation(LinearLayout.HORIZONTAL);
        ThemeManager.styleCard(statCard2, this, ThemeManager.CARD_RADIUS);
        statCard2.setPadding(dp(4), dp(6), dp(4), dp(6));
        statCumNotice = statCellHome(statCard2, "🗂️", "累计通知");
        statCumDays = statCellHome(statCard2, "📅", "纪要天数");
        wrap.addView(statCard2, mp);

        statExtra = new TextView(this);
        statExtra.setTextSize(13);
        statExtra.setTextColor(Color.parseColor("#37474F"));
        statExtra.setPadding(dp(4), dp(8), dp(4), dp(8));
        wrap.addView(statExtra, mp);

        // ---- 📊 数据看板 ----
        TextView dashTitle = new TextView(this);
        dashTitle.setText("📊 数据看板");
        dashTitle.setTextSize(17);
        dashTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dashTitle.setTextColor(Color.parseColor("#37474F"));
        dashTitle.setPadding(0, dp(14), 0, dp(6));
        wrap.addView(dashTitle, mp);

        // 近7天趋势
        TextView lbl7 = new TextView(this);
        lbl7.setText("近7天通知趋势");
        lbl7.setTextSize(14);
        lbl7.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lbl7.setTextColor(ThemeManager.color(this, ThemeManager.TAB_ON));
        lbl7.setPadding(dp(4), dp(4), dp(4), dp(4));
        wrap.addView(lbl7, mp);
        chart7d = new Charts.BarChartView(this, new String[7][2]);
        chart7d.setMinimumHeight(dp(150));
        ThemeManager.styleCard(chart7d, this, ThemeManager.CARD_RADIUS);
        chart7d.setPadding(dp(6), dp(6), dp(6), dp(6));
        wrap.addView(chart7d, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));

        // 今日分类占比（环形图居中 + 下方居中的彩色图例）
        TextView lblCat = new TextView(this);
        lblCat.setText("今日通知分类占比");
        lblCat.setTextSize(14);
        lblCat.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblCat.setTextColor(ThemeManager.color(this, ThemeManager.TAB_ON));
        lblCat.setPadding(dp(4), dp(14), dp(4), dp(4));
        wrap.addView(lblCat, mp);

        android.widget.FrameLayout catBox = new android.widget.FrameLayout(this);
        ThemeManager.styleCard(catBox, this, ThemeManager.CARD_RADIUS);
        catBox.setPadding(dp(6), dp(4), dp(6), dp(4));
        chartCat = new Charts.RingChartView(this, new String[5][3]);
        chartCat.setMinimumHeight(dp(140));
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(dp(140), dp(140));
        clp.gravity = android.view.Gravity.CENTER;
        catBox.addView(chartCat, clp);
        wrap.addView(catBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(140)));

        // 分类图例（居中：彩色圆点 + 名称 + 数量）
        catLegend = new TextView(this);
        catLegend.setTextSize(12);
        catLegend.setTextColor(Color.parseColor("#37474F"));
        catLegend.setGravity(Gravity.CENTER);
        catLegend.setPadding(dp(4), dp(6), dp(4), dp(2));
        wrap.addView(catLegend, mp);

        // 今日 vs 昨日
        LinearLayout vsRow = new LinearLayout(this);
        vsRow.setOrientation(LinearLayout.HORIZONTAL);
        vsRow.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        vsToday = vsCell(vsRow, "今日");
        vsYesterday = vsCell(vsRow, "昨日");
        wrap.addView(vsRow, mp);

        // App TOP
        TextView lblTop = new TextView(this);
        lblTop.setText("通知来源 TOP5");
        lblTop.setTextSize(14);
        lblTop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblTop.setTextColor(ThemeManager.color(this, ThemeManager.TAB_ON));
        lblTop.setPadding(dp(4), dp(14), dp(4), dp(4));
        wrap.addView(lblTop, mp);
        appTopList = new LinearLayout(this);
        appTopList.setOrientation(LinearLayout.VERTICAL);
        appTopList.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        wrap.addView(appTopList, mp);

        // 小时分布
        TextView lblHour = new TextView(this);
        lblHour.setText("按小时分布");
        lblHour.setTextSize(14);
        lblHour.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblHour.setTextColor(ThemeManager.color(this, ThemeManager.TAB_ON));
        lblHour.setPadding(dp(4), dp(14), dp(4), dp(4));
        wrap.addView(lblHour, mp);
        chartHour = new Charts.BarChartView(this, new String[24][2]);
        chartHour.setMinimumHeight(dp(90));
        chartHour.setBackgroundColor(ThemeManager.color(this, ThemeManager.CARD));
        wrap.addView(chartHour, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));

        // 配置已移至「👤我的」页二级菜单
        TextView cfgTip = new TextView(this);
        cfgTip.setText("⚙️ 配置（WebDAV/AI/过滤器等）已移至「👤 我的」页设置中心");
        cfgTip.setTextSize(12);
        cfgTip.setTextColor(ThemeManager.color(this, ThemeManager.SECONDARY));
        cfgTip.setGravity(Gravity.CENTER);
        cfgTip.setPadding(dp(4), dp(14), dp(4), dp(6));
        wrap.addView(cfgTip, mp);

        fillDashboard();   // 构建即用真实统计数据填充图表
        return scroll;
    }

    // ---- 日志页（第2个 Tab）----

    private View buildLogPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        LinearLayout.LayoutParams mp = matcher();

        TextView title = UiKit.sectionTitle(this, "📄 通知日志");
        root.addView(title, mp);

        tvLogHint = new TextView(this);
        tvLogHint.setTextSize(12);
        tvLogHint.setTextColor(ThemeManager.color(this, ThemeManager.SECONDARY));
        tvLogHint.setPadding(dp(4), 0, dp(4), dp(10));
        root.addView(tvLogHint, mp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button btnRefresh = UiKit.primary(this, "🔄 刷新");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshLog(); }
        });
        Button btnClearLog = UiKit.ghost(this, "🗑️ 清空");
        btnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LogStore.clear(MainActivity.this);
                refreshLog();
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp1.setMargins(0, 0, dp(8), 0);
        row.addView(btnRefresh, lp1);
        row.addView(btnClearLog, lp2);
        root.addView(row, mp);

        lvLog = new ListView(this);
        lvLog.setDivider(null);
        lvLog.setPadding(dp(4), dp(6), dp(4), dp(4));
        lvLog.setClipToPadding(false);
        root.addView(lvLog, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    /** 今日/昨日 对比单元格。 */
    private TextView vsCell(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView big = new TextView(this);
        big.setTextSize(20);
        big.setTextColor(ThemeManager.color(this, ThemeManager.TAB_ON));
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setText("0");
        cell.addView(big);
        TextView lb = new TextView(this);
        lb.setTextSize(11);
        lb.setTextColor(ThemeManager.color(this, ThemeManager.SECONDARY));
        lb.setText(label);
        lb.setGravity(Gravity.CENTER);
        cell.addView(lb);
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        return big;
    }

    /** 首页统计单元格。 */
    private TextView statCellHome(LinearLayout row, String icon, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView big = new TextView(this);
        big.setTextSize(20);
        big.setTextColor(Color.parseColor("#1565C0"));
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setText("--");
        TextView small = new TextView(this);
        small.setText(label);
        small.setTextSize(12);
        small.setTextColor(Color.parseColor("#90A4AE"));
        small.setGravity(Gravity.CENTER);
        cell.addView(big);
        cell.addView(small);
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        return big;
    }

    /** 刷新首页信息统计。 */
    private void refreshHomeStats() {
        if (statToday == null) return;
        List<String> lines = LogStore.read(this, 1000);
        AiAnalyzerCore.Summary s = AiAnalyzerCore.analyze(lines);
        statToday.setText(String.valueOf(s.totalNotices));
        statNotice = null;

        String dayKey = ReportStore.dateKeyNow();
        boolean reportDone = ReportStore.hasReport(this, dayKey);
        statReport.setText(reportDone ? "✅" : "⏳");
        statReport.setContentDescription(reportDone ? "今日纪要已生成" : "今日纪要未生成");

        // 累计通知 / 纪要天数
        int todayCount = lines.size();
        statCumNotice.setText(String.valueOf(todayCount));
        statCumDays.setText(String.valueOf(countReportDays()));

        // 补充信息：待办/紧急/工作 + 上次同步
        String lastSync = Config.get(this, Config.KEY_LAST_SYNC, "");
        statExtra.setText("工作 " + s.workCount + " · 待办 " + s.todoCount + " · 紧急 " + s.urgentCount
                + "　|　上次同步：" + fmtLastSync(lastSync)
                + (reportDone ? "" : "　|　今日纪要未生成"));

        fillDashboard();
    }

    /** 填充首页数据看板（用 StatsStore 每日统计，全部真实数据，无演示数据）。 */
    private void fillDashboard() {
        try {
            String today = StatsStore.todayKey();
            String yesterday = StatsStore.dateKey(System.currentTimeMillis() - 86400000L);

            // === 近7天趋势（真实历史数据）===
            String[][] fwd = StatsStore.lastNDays(this, 7);
            chart7d.setColors(ThemeManager.color(this, ThemeManager.GRAD_S),
                    ThemeManager.color(this, ThemeManager.TEXT));
            chart7d.setData(fwd);

            // === 今日分类占比（真实）===
            long w = StatsStore.cat(this, today, "work");
            long t = StatsStore.cat(this, today, "todo");
            long u = StatsStore.cat(this, today, "urgent");
            long m = StatsStore.cat(this, today, "meeting");
            long o = Math.max(0, StatsStore.total(this, today) - w - t - u - m);
            String[][] cats = {
                    {"工作", String.valueOf(w), "#5B9BD5"},
                    {"待办", String.valueOf(t), "#F9A825"},
                    {"紧急", String.valueOf(u), "#E53935"},
                    {"会议", String.valueOf(m), "#8E24AA"},
                    {"其他", String.valueOf(o), "#90A4AE"},
            };
            chartCat.setData(cats);
            String[] names = {"工作", "待办", "紧急", "会议", "其他"};
            String[] colsDot = {"\u25CF", "\u25CF", "\u25CF", "\u25CF", "\u25CF"};
            long[] vals = {w, t, u, m, o};
            StringBuilder lg = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                if (i > 0) lg.append("   ");
                lg.append(colsDot[i]).append(names[i]).append(" ").append(vals[i]);
            }
            catLegend.setText(lg.toString());

            // === 今日 vs 昨日（真实）===
            vsToday.setText(String.valueOf(StatsStore.total(this, today)));
            vsYesterday.setText(String.valueOf(StatsStore.total(this, yesterday)));

            // === App TOP5（真实）===
            appTopList.removeAllViews();
            java.util.List<String[]> top = StatsStore.appTop(this, today, 5);
            if (top.isEmpty()) addLineText(appTopList, "暂无今日数据", "#90A4AE");
            else for (String[] row : top) addLineText(appTopList, row[0] + "　——　" + row[1] + " 条", "#37474F");

            // === 小时分布（真实）===
            String[][] hd = new String[24][2];
            long[] hrs = StatsStore.hourDist(this, today);
            for (int i = 0; i < 24; i++) { hd[i][0] = String.valueOf(i); hd[i][1] = String.valueOf(hrs[i]); }
            chartHour.setColors(ThemeManager.color(this, ThemeManager.ACCENT),
                    ThemeManager.color(this, ThemeManager.TEXT));
            chartHour.setData(hd);
        } catch (Exception ignored) {}
    }

    /** 统计已有纪要到天数（含今天，最晚更新）。 */
    private int countReportDays() {
        int count = 0;
        for (java.io.File f : getFilesDir().listFiles()) { /* placeholder */ }
        // 用 SharedPreferences 里 report: 前缀 key 计数
        try {
            java.util.Map<String, ?> all = getSharedPreferences(ReportStore.PREF, MODE_PRIVATE).getAll();
            for (String k : all.keySet()) {
                if (k.startsWith("report:") && all.get(k) instanceof String) count++;
            }
        } catch (Exception ignore) {}
        return count;
    }

    // ---- 配置页 ----

    /** 首页配置表单：把 WebDAV 等配置控件直接加进传入的容器。 */
    private View buildConfigPageContent(LinearLayout wrap, LinearLayout.LayoutParams mp) {
        LinearLayout root = wrap;   // 复用传入容器，不新建滚动
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        TextView title = new TextView(this);
        title.setText("NotifyBridge for AI");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title, mp);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setPadding(0, 0, 0, dp(12));
        root.addView(tvStatus, mp);

        Button btnListener = UiKit.primary(this, "🔔 开启通知使用权");
        btnListener.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotificationAccess(); }
        });
        root.addView(btnListener, mp);

        root.addView(section("WebDAV 配置"), mp);

        etUrl = new EditText(this);
        etUrl.setHint("服务器地址（含协议，以 / 结尾）");
        etUrl.setText(Config.get(this, Config.KEY_URL, ""));
        root.addView(etUrl, mp);

        etUser = new EditText(this);
        etUser.setHint("账号");
        etUser.setText(Config.get(this, Config.KEY_USER, ""));
        root.addView(etUser, mp);

        etPass = new EditText(this);
        etPass.setHint("密码");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setText(Config.getPassword(this));
        root.addView(etPass, mp);

        etDir = new EditText(this);
        etDir.setHint("上传目录（远端路径，默认 notifybridge）");
        etDir.setText(Config.get(this, Config.KEY_WEBDAV_DIR, "notifybridge"));
        root.addView(etDir, mp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        Button btnTest = UiKit.ghost(this, "测试连接");
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });
        Button btnSave = UiKit.primary(this, "保存配置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveConfig(); }
        });
        LinearLayout.LayoutParams lpTest = new LinearLayout.LayoutParams(0, -2, 1f);
        lpTest.setMargins(0, 0, dp(8), 0);
        row1.addView(btnTest, lpTest);
        row1.addView(btnSave, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row1, mp);

        root.addView(section("自动同步"), mp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblSync = new TextView(this);
        lblSync.setText("定时批量上传到 WebDAV");
        lblSync.setTextSize(16);
        swSync = new Switch(this);
        swSync.setChecked(Config.getBool(this, Config.KEY_SYNC_ENABLED, false));
        swSync.setOnCheckedChangeListener((v, isOn) -> {
            Config.putBool(this, Config.KEY_SYNC_ENABLED, isOn);
            if (isOn) UploadScheduler.scheduleRepeating(this);
            else UploadScheduler.cancel(this);
            showToast(isOn ? "已开启自动同步" : "已关闭自动同步", false);
        });
        row2.addView(lblSync, new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(swSync);
        root.addView(row2, mp);

        root.addView(section("数据管理"), mp);

        tvCached = new TextView(this);
        tvCached.setTextSize(14);
        tvCached.setPadding(0, 0, 0, dp(8));
        root.addView(tvCached, mp);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        Button btnSyncNow = UiKit.primary(this, "⚡ 立即同步");
        btnSyncNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { syncNow(); }
        });
        Button btnFilter = UiKit.ghost(this, "🔍 通知过滤");
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FilterActivity.class));
            }
        });
        LinearLayout.LayoutParams lpSync = new LinearLayout.LayoutParams(0, -2, 1f);
        lpSync.setMargins(0, 0, dp(8), 0);
        row3.addView(btnSyncNow, lpSync);
        row3.addView(btnFilter, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row3, mp);

        Button btnClear = UiKit.ghost(this, "🗑️ 清空缓存");
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = NotificationCache.clearAll(MainActivity.this);
                refreshCachedCount();
                showToast("已清空 " + n + " 条缓存", false);
            }
        });
        root.addView(btnClear, mp);

        root.addView(section("近期同步"), mp);
        TextView tvSync = new TextView(this);
        String last = Config.get(this, Config.KEY_LAST_SYNC, "");
        tvSync.setText("上次同步：" + fmtLastSync(last));
        tvSync.setTextSize(14);
        root.addView(tvSync, mp);

        return root;
    }

    private void refreshLog() {
        if (lvLog == null) return;
        List<String> lines = LogStore.read(this, 300);
        tvLogHint.setText("共 " + lines.size() + " 条 ｜ 监听授权：" + (isListenerEnabled() ? "✅" : "⚠️未开启"));
        lvLog.setAdapter(new LogListAdapter(lines));
    }

    /**
     * 彩色日志适配器：根据日志内容分类着色（小清新可读配色）。
     *  通知 [HH:mm]  -> 紫
     *  RAW >>       -> 灰（原始捕获痕迹）
     *  诊断 [[HH]]  -> 按内容：失败=红 / 警告=橙 / 成功或已上传=绿 / 其他=蓝
     */
    private class LogListAdapter extends android.widget.BaseAdapter {
        private final List<String> data;

        LogListAdapter(List<String> lines) { data = lines; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView tv = (convertView instanceof TextView)
                    ? (TextView) convertView : new TextView(MainActivity.this);
            String line = data.get(position);
            tv.setText(line);
            tv.setTextSize(13);
            tv.setPadding(0, dp(3), 0, dp(3));
            tv.setTextColor(logColor(line));
            return tv;
        }

        private int logColor(String line) {
            // 通知捕获（普通格式 [HH:mm:ss] 开头，非 RAW/[[）
            if (line.startsWith("[[") && line.contains("]]")) {
                // 诊断日志 [[HH:mm:ss]]
                String l = line.toLowerCase();
                if (l.contains("失败") || l.contains("错误") || l.contains("exception")
                        || l.contains("error") || l.contains("认证失败") || l.contains("上传失败")) {
                    return Color.parseColor("#D32F2F"); // 红
                }
                if (l.contains("警告") || l.contains("warn") || l.contains("超时")) {
                    return Color.parseColor("#F57C00"); // 橙
                }
                if (l.contains("成功") || l.contains("已上传") || l.contains("连接成功")
                        || l.contains("ok")) {
                    return Color.parseColor("#2E7D32"); // 绿
                }
                return Color.parseColor("#0288D1"); // 蓝（普通诊断）
            }
            if (line.startsWith(">> RAW")) {
                return Color.parseColor("#90A4AE"); // 灰（原始痕迹）
            }
            if (line.startsWith("[")) {
                return Color.parseColor("#7B1FA2"); // 紫（真实通知）
            }
            return Color.parseColor("#37474F"); // 深蓝灰（其他）
        }
    }

    // ============ AI 智能助手页 ============

    private View buildAiPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        scroll.addView(root);
        LinearLayout.LayoutParams mp = matcher();

        // 顶部渐变标题
        TextView aiTitle = new TextView(this);
        aiTitle.setText("🤖 AI 今日简报");
        aiTitle.setTextSize(20);
        aiTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        aiTitle.setTextColor(Color.WHITE);
        aiTitle.setGravity(Gravity.CENTER);
        aiTitle.setGravity(Gravity.CENTER);
        aiTitle.setPadding(dp(16), dp(24), dp(16), dp(24));
        android.graphics.drawable.GradientDrawable aiGrad = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ThemeManager.color(this, ThemeManager.GRAD_S), ThemeManager.color(this, ThemeManager.GRAD_E)});
        aiGrad.setCornerRadius(dp(14));
        aiTitle.setBackground(aiGrad);
        root.addView(aiTitle, mp);

        // 手动生成 + 查看历史：一行两个按钮（左/右）
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        btnManualGen = UiKit.primary(this, "🤖 手动生成");
        btnManualGen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                generateReport(ReportStore.dateKeyNow(), true);
            }
        });
        LinearLayout.LayoutParams lpGen = new LinearLayout.LayoutParams(0, -2, 1f);
        lpGen.setMargins(0, 0, dp(8), 0);
        actionRow.addView(btnManualGen, lpGen);

        Button btnHistory = UiKit.ghost(this, "📅 查看历史");
        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistoryDialog(); }
        });
        actionRow.addView(btnHistory, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actionRow, mp);

        // 统计三列
        LinearLayout statRow = new LinearLayout(this);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setPadding(dp(4), dp(16), dp(4), dp(4));
        tvAiWork = statCell(statRow, "📊 工作");
        tvAiTodo = statCell(statRow, "✅ 待办");
        tvAiUrgent = statCell(statRow, "🔴 紧急");
        root.addView(statRow, mp);

        // 摘要卡
        root.addView(cardTitle("📋 今日智能摘要", mp), mp);
        LinearLayout digestCard = UiKit.card(this);
        tvAiGenTime = new TextView(this);
        tvAiGenTime.setTextSize(12);
        tvAiGenTime.setTextColor(ThemeManager.color(this, ThemeManager.ACCENT));
        tvAiGenTime.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAiGenTime.setPadding(0, 0, 0, dp(6));
        digestCard.addView(tvAiGenTime, mp);

        tvAiDigest = new TextView(this);
        tvAiDigest.setTextSize(14);
        tvAiDigest.setTextColor(ThemeManager.color(this, ThemeManager.TEXT));
        tvAiDigest.setPadding(0, dp(2), 0, dp(2));
        digestCard.addView(tvAiDigest, mp);
        root.addView(digestCard, mp);

        // 待办清单
        root.addView(cardTitle("✅ 今日待办事项", mp), mp);
        LinearLayout todoCard = UiKit.card(this);
        aiTodoList = new LinearLayout(this);
        aiTodoList.setOrientation(LinearLayout.VERTICAL);
        todoCard.addView(aiTodoList, mp);
        root.addView(todoCard, mp);

        // 智能建议
        root.addView(cardTitle("💡 AI 智能建议", mp), mp);
        LinearLayout suggCard = UiKit.card(this);
        aiSuggestList = new LinearLayout(this);
        aiSuggestList.setOrientation(LinearLayout.VERTICAL);
        suggCard.addView(aiSuggestList, mp);
        root.addView(suggCard, mp);

        root.addView(cardTitle("📊 通知计数", mp), mp);
        LinearLayout countCard = UiKit.card(this);
        tvAiCount = new TextView(this);
        tvAiCount.setTextSize(14);
        tvAiCount.setTextColor(ThemeManager.color(this, ThemeManager.TEXT));
        tvAiCount.setPadding(0, dp(2), 0, dp(2));
        countCard.addView(tvAiCount, mp);
        root.addView(countCard, mp);

        return scroll;
    }

    private TextView statCell(LinearLayout row, String label) {
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#1565C0"));
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(8));
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        return tv;
    }

    private TextView cardTitle(String text, LinearLayout.LayoutParams mp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#37474F"));
        tv.setPadding(dp(4), dp(16), dp(4), dp(6));
        return tv;
    }

    private void refreshAiPage() {
        if (tvAiDigest == null) return;
        // AI 助手默认显示当天已缓存的纪要，不重复调用 AI（效率优化）
        String dayKey = ReportStore.dateKeyNow();
        String cached = ReportStore.getReport(this, dayKey);
        boolean real = AIAnalyzer.isRealAiConfigured(this);
        if (cached != null) {
            // 生成时间（精确到秒；若未记录则用当前时间兜底）
            long ts = ReportStore.getReportTs(this, dayKey);
            if (ts <= 0) ts = System.currentTimeMillis();
            String timeStr = fmtTime(ts);
            String suffix = (real ? "\n【AI 智能模式】" : "\n【未配置 AI】");
            tvAiDigest.setText(android.text.Html.fromHtml(renderMarkdown(cached) + "<br/><small style=\"color:#90A4AE\">"
                    + suffix + "</small>"));
            // 生成时间明确显示到 时:分:秒，放在摘要卡标题下
            setStat(tvAiGenTime, "⏱ 生成于 " + timeStr + "　" + suffix.replace("\n",""));
        } else {
            tvAiDigest.setText("今天还没生成 AI 纪要。\n点击上方「🤖 手动生成今日纪要」按钮生成。");
            setStat(tvAiGenTime, "⏱ --");
        }
        // 用本地规则做概览统计（轻量，不联网）
        List<String> lines = LogStore.read(this, 300);
        AiAnalyzerCore.Summary s = AiAnalyzerCore.analyze(lines);
        String modeTxt = real ? (AIAnalyzer.isDirect(this) ? "AI直连" : "中转") : "未配置 AI";
        tvAiCount.setText("今日 " + s.totalNotices + " 条通知 · " + modeTxt);
        setStat(tvAiWork, "📊 " + s.workCount + " 工作");
        setStat(tvAiTodo, "✅ " + s.todoCount + " 待办");
        setStat(tvAiUrgent, "🔴 " + s.urgentCount + " 紧急");

        // 待办 & 建议：优先从 AI 纪要点提取（更准），否则用本地规则
        aiTodoList.removeAllViews();
        java.util.List<String> todos = (cached != null) ? extractTodos(cached) : cleanTodos(s.todos);
        if (todos.isEmpty()) {
            addLineText(aiTodoList, "暂无识别到待办事项", "#90A4AE");
        } else {
            for (String t : todos) addLineText(aiTodoList, (t.startsWith("✔️") ? "✅ " : "· ") + t.replaceFirst("^✔️ ?", "").replaceFirst("^⚠️ ?", "⚠️ "), "#37474F");
        }

        aiSuggestList.removeAllViews();
        java.util.List<String> sugg = (cached != null) ? extractSuggestions(cached) : s.suggestions;
        if (sugg.isEmpty()) {
            addLineText(aiSuggestList, "暂无智能建议", "#90A4AE");
        } else {
            for (String g : sugg) addLineText(aiSuggestList, "💡 " + g, "#F57C00");
        }
    }

    private void setStat(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    /** 显示历史纪要列表对话框；点选某日后查看对应纪要。 */
    private void showHistoryDialog() {
        final java.util.List<String> dates = ReportStore.allDates(this);
        if (dates.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("📅 历史纪要")
                    .setMessage("暂无历史纪要。\n可先在 AI 页点击「🤖 手动生成今日纪要」。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        String[] items = new String[dates.size()];
        for (int i = 0; i < dates.size(); i++) items[i] = dates.get(i);
        new android.app.AlertDialog.Builder(this)
                .setTitle("📅 历史纪要（" + dates.size() + " 天）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        showHistoryDetail(dates.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 显示某天纪要内容（Markdown 渲染）。 */
    private void showHistoryDetail(final String date) {
        String content = ReportStore.getReport(this, date);
        if (content == null || content.trim().isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("📅 " + date)
                    .setMessage("该日暂无纪要内容。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        long ts = ReportStore.getReportTs(this, date);
        String head = "📅 " + date + (ts > 0 ? "　·　生成于 " + fmtTime(ts) : "");
        ScrollView sv = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(android.text.Html.fromHtml(renderMarkdown(content)));
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        body.setTextSize(14);
        body.setLineSpacing(dp(2), 1f);
        sv.addView(body);
        new android.app.AlertDialog.Builder(this)
                .setTitle(head)
                .setView(sv)
                .setPositiveButton("确定", null)
                .show();
    }

    private void addLineText(LinearLayout parent, String text, String color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor(color));
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        parent.addView(tv, matcher());
    }

    /** 待办去重 + 过滤无用字段（如只有 App 名、过短、纯状态词）。 */
    private java.util.List<String> cleanTodos(java.util.List<String> raw) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String t : raw) {
                if (t == null) continue;
                String s = t.trim();
                // 过滤明显无用：只有时间+app名、过短、纯"状态词"
                String body = s.replaceAll("^\\d{1,2}:\\d{2}\\s*", "").replaceAll("^\\[.*?\\]\\s*", "").trim();
                if (body.isEmpty()) continue;
                if (body.length() < 3) continue;
                // 过滤纯App名/纯状态词
                if (body.matches("(飞书|微信|钉钉|QQ|短信|邮件|系统|服务)")) continue;
                // 过滤"无/暂无/已读/已查看"等无意义
                if (body.matches("[无暂无不存在未处理完成已读已忽略已关闭已查看已读了然确认]+") ) continue;
                set.add(s);
            }
        }
        return new java.util.ArrayList<>(set);
    }

    /** 从 AI 纪要 Markdown 提取"待办"条目（含时间/动作/截止 特征的）。 */
    private java.util.List<String> extractTodos(String md) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (md == null) return new java.util.ArrayList<>();
        String[] lines = md.split("\n");
        // 动作/时间特征词
        String act = "完成|提交|处理|回复|准备|确认|记得|需要|截止|ddl|待办|安排|评审|开会|到场|预约|写|做|更新|跟进|明天|今天|下午|上午|本周|下周|前|复查|审核|开会|参会|提醒|别忘|记得";
        boolean inTodoSection = false;
        for (String raw : lines) {
            String t = raw.trim();
            String low = t.toLowerCase();
            // 板块标题判断
            if (low.contains("#") && (low.contains("待办") || low.contains("todo") || low.contains("任务"))) { inTodoSection = true; continue; }
            if (low.startsWith("## ")) inTodoSection = false;
            // 只处理列表条目
            if (!t.startsWith("- ") && !t.startsWith("• ") && !t.startsWith("* ")) continue;
            String body = t.replaceFirst("^[-•*]\\s*", "").trim();
            // 去 markdown 加粗标记
            body = body.replaceAll("\\*\\*", "").replace("`", "");
            if (body.isEmpty()) continue;
            // ⚠️ 开头 → 归为待办关注项
            boolean liu = body.startsWith("⚠️");
            // 判定是否像待办：板块标题待办，或含动作/时间词，或 ⚠️
            if (inTodoSection || liu || containsWord(body, act)) {
                // 过滤"时间戳+纯群名/App名"式伪待办（如"20:41:12 理想之地三批次装修团购交流群"、"飞书"）
                String stripped = body.replaceAll("^\\d{1,2}:\\d{2}(:\\d{2})?\\s*", "").trim();
                // 去时间戳后，若内容不含冒号分隔(无实质细节)、又匹配纯App/群名词表，则丢弃
                if (stripped.matches("(飞书|微信|钉钉|QQ|短信|邮件|系统|服务|企业微信|群聊|通知)$")) continue;
                // 去掉时间戳后若还是直接以群名/App名结尾且长度很短的碎片，丢弃
                if (stripped.length() < 5 && !stripped.contains("|") && !liu) continue;
                if (body.length() >= 3) out.add(body);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** 从 AI 纪要 Markdown 提取"建议"（⚠️ 关注项 / 建议/提醒板块）。 */
    private java.util.List<String> extractSuggestions(String md) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (md == null) return new java.util.ArrayList<>();
        String[] lines = md.split("\n");
        for (String raw : lines) {
            String t = raw.trim();
            if (t.contains("⚠️")) {
                String body = t.replaceFirst("^[-•*]\\s*", "").replaceAll("\\*\\*", "").replace("`", "").trim();
                if (!body.isEmpty() && body.length() >= 3) out.add(body);
            } else if (t.startsWith("- ") && (t.toLowerCase().contains("建议") || t.toLowerCase().contains("提醒") || t.toLowerCase().contains("注意"))) {
                String body = t.replaceFirst("^[-•*]\\s*", "").replaceAll("\\*\\*", "").replace("`", "").trim();
                if (!body.isEmpty()) out.add(body);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /** 判断 body 是否含 act 词列表中的任一（中文包含判断）。 */
    private boolean containsWord(String body, String act) {
        for (String w : act.split("\\|")) {
            if (body.contains(w)) return true;
        }
        return false;
    }

    /** 时间戳格式化为 年月日 时分秒。 */
    private String fmtTime(long millis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        return sdf.format(new java.util.Date(millis));
    }

    /** 把 last_sync 的时间戳字符串格式化为年月日时分秒。 */
    private String fmtLastSync(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "从未";
        try {
            return fmtTime(Long.parseLong(raw.trim()));
        } catch (Exception e) {
            return raw;  // 若不是纯数字，原样返回
        }
    }

    /** 简单 Markdown → HTML 渲染（粗体/标题/列表/高亮），供 Html.fromHtml 使用。 */
    private String renderMarkdown(String md) {
        if (md == null) return "";
        String s = md;
        // 反引号代码移除
        s = s.replaceAll("`", "");
        // 序数列表 "1. xxx" -> "1. xxx"（保留）
        // 无序列表 "- **xxx**：yyy" -> 加缩进圆点
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            String t = ln.trim();
            if (t.isEmpty()) { sb.append("<br/>"); continue; }
            if (t.startsWith("### ")) { sb.append("<b style=\"color:#37474F\">").append(t.substring(4)).append("</b><br/>"); }
            else if (t.startsWith("## ")) { sb.append("<b style=\"color:#1565C0;font-size:16sp\">").append(t.substring(3)).append("</b><br/>"); }
            else if (t.startsWith("# ")) { sb.append("<b style=\"color:#1565C0;font-size:18sp\">").append(t.substring(2)).append("</b><br/>"); }
            else if (t.startsWith("- ") || t.startsWith("• ")) {
                String body = t.substring(t.startsWith("- ") ? 2 : 1).trim();
                // 去掉 Markdown 复选框 "[ ] / [x] / [X]" 占位，改成一个圆点，避免 UI 显示空方框显得乱
                if (body.startsWith("[") && body.length() >= 3 && body.indexOf(']') == 2) {
                    char st = Character.toLowerCase(body.charAt(1));
                    String rest = body.substring(3).trim();
                    body = ((st == 'x') ? "✔ " : "◦ ") + rest;
                }
                sb.append("&nbsp;&nbsp;•&nbsp;").append(body).append("<br/>");
            }
            else { sb.append(t).append("<br/>"); }
        }
        // 处理 **加粗**
        return sb.toString().replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
    }

    // ============ 纪要看板页 ============

    /** @deprecated 纪要看板页（已弃用，保留备用，无调用）。 */
    private View buildKanbanPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        scroll.addView(root);
        LinearLayout.LayoutParams mp = matcher();

        // 日期标题
        tvKanbanDate = new TextView(this);
        tvKanbanDate.setTextSize(18);
        tvKanbanDate.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvKanbanDate.setTextColor(Color.parseColor("#37474F"));
        tvKanbanDate.setPadding(0, dp(4), 0, dp(4));
        root.addView(tvKanbanDate, mp);

        // 概览统计四列
        LinearLayout statRow = new LinearLayout(this);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setBackgroundColor(Color.parseColor("#FFFFFF"));
        tvKanbanNotice = kanbanCell(statRow, "📊 通知");
        tvKanbanTodo = kanbanCell(statRow, "✅ 待办");
        tvKanbanDone = kanbanCell(statRow, "📌 工作");
        tvKanbanUrgent = kanbanCell(statRow, "🔴 紧急");
        root.addView(statRow, mp);

        // 纪要内容
        root.addView(cardTitle("📝 AI 工作纪要", mp), mp);
        tvKanbanContent = new TextView(this);
        tvKanbanContent.setTextSize(14);
        tvKanbanContent.setTextColor(Color.parseColor("#37474F"));
        tvKanbanContent.setBackground(ThemeManager.rounded(
                ThemeManager.color(this, ThemeManager.CARD), ThemeManager.CARD_RADIUS));
        tvKanbanContent.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(tvKanbanContent, mp);

        // 生成 / 重新生成按钮（仅用户主动点击才生成）
        Button btnGen = UiKit.primary(this, "🤖 生成 / 重新生成纪要");
        btnGen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                generateReport(ReportStore.dateKeyNow(), true);
            }
        });
        LinearLayout.LayoutParams lpGenK = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpGenK.setMargins(0, dp(6), 0, dp(6));
        root.addView(btnGen, lpGenK);

        // 日志（放在纪要下方）
        root.addView(cardTitle("📄 通知日志", mp), mp);
        tvKanbanLog = new TextView(this);
        tvKanbanLog.setTextSize(12);
        tvKanbanLog.setTextColor(Color.parseColor("#90A4AE"));
        tvKanbanLog.setBackground(ThemeManager.rounded(
                ThemeManager.color(this, ThemeManager.CARD), ThemeManager.CARD_RADIUS));
        tvKanbanLog.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(tvKanbanLog, mp);

        return scroll;
    }

    private TextView kanbanCell(LinearLayout row, String label) {
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#1565C0"));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(12), 0, dp(12));
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        return tv;
    }

    /** 刷新纪要看板：默认读取缓存的纪要，不自动生成。 */
    private void refreshKanban() {
        if (tvKanbanContent == null) return;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy年MM月dd日 EEEE", java.util.Locale.CHINA);
        String dateStr = sdf.format(new java.util.Date());
        tvKanbanDate.setText("📅 " + dateStr);

        String dayKey = ReportStore.dateKeyNow();
        String cached = ReportStore.getReport(this, dayKey);
        if (cached != null) {
            tvKanbanContent.setText(cached);
        } else {
            tvKanbanContent.setText("今天还没生成纪要。\n点击上方「🤖 生成 / 重新生成纪要」按钮生成。");
        }
        // 日志放下方
        List<String> lines = LogStore.read(this, 120);
        StringBuilder sb = new StringBuilder();
        int n = Math.min(lines.size(), 60);
        for (int i = 0; i < n; i++) sb.append(lines.get(i)).append("\n");
        tvKanbanLog.setText(sb.length() == 0 ? "暂无通知日志" : sb.toString());
    }

    /**
     * 生成某天的 AI 纪要（真实 AI，失败降级本地规则），并缓存。
     * @param dayKey   yyyy-MM-dd
     * @param force    是否强制重新生成（用户主动点击 true；自动补生成 false，已有则跳过）
     */
    /**
     * 加载纪要输入：**合并**完整日志(capture.log) + 按天缓存文件，去重，
     * 确保 capture.log 里的通知（含微信等，最全）一定进入纪要。
     * 之前"命中按天缓存就 return、直接忽略日志"导致缓存残缺时纪要丢微信——已修复。
     */
    private List<String> loadDayReportLines(String dayKey) {
        java.util.List<String> lines = LogStore.read(this, 2000);   // 完整日志(全量,含微信)
        // 追加按天缓存文件（补充结构化按天数据）
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "pending");
            java.io.File f = new java.io.File(dir, dayKey + "-notifications.md");
            if (f.exists()) {
                String content = NotificationCache.readFile(this, f);
                for (String line : content.split("\\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) lines.add(t);
                }
            }
        } catch (Throwable ignored) {}
        // 语义去重 + 过滤诊断/RAW/纯App名垃圾行，避免 AI 拿到大量重复输入
        return dedupeReportLines(lines);
    }

    /**
     * 纪要输入语义去重与清洗：
     *  - 剔除诊断行([[..]])、RAW 痕迹(>> RAW)、以及只有时间戳+纯App名/群名的“占位行”
     *  - 同一“应用/群 + 标题/正文”的多条通知（时间戳不同）合并为最早一条，杜绝飞书×4、opg×3 这类重复
     * 去重键 = 去掉时间戳/加粗标记后的“可检索文本”，忽略时间差异只按内容合并。
     */
    private List<String> dedupeReportLines(java.util.List<String> raw) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (raw != null) {
            for (String s : raw) {
                if (s == null) continue;
                String t = s.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("[[") || t.startsWith(">> RAW")) continue;   // 诊断/原始痕迹
                // 归一化：去前导 "- [" / "[" + 时间戳，去 ** 加粗
                String norm = t.replaceAll("^[-•*]?\\s*\\[?\\d{1,2}:\\d{2}(:\\d{2})?\\]?\\s*", "")
                        .replaceAll("\\*\\*", "").trim();
                if (norm.isEmpty()) continue;
                // 若去掉前缀后只剩纯 App/群名（无 "|" 分隔、无实质内容），判定为占位行丢弃
                boolean hasContent = norm.contains("|") || norm.length() >= 6;
                if (!hasContent) continue;
                // 去重键：标题正文主体（取 "|" 之后内容；若无 | 则整体），去掉首尾空白
                String key;
                int pipe = norm.indexOf('|');
                if (pipe >= 0) key = norm.substring(pipe + 1).trim();
                else key = norm;
                // 键为空（只有 app 名的占位）直接丢弃
                if (key.isEmpty()) continue;
                map.putIfAbsent(key, t);   // 同一内容只保留最早一条完整原文
            }
        }
        return new java.util.ArrayList<>(map.values());
    }

    private void generateReport(final String dayKey, final boolean force) {
        if (aiPage == null) return;
        if (!force && ReportStore.hasReport(this, dayKey)) return; // 已有则跳过
        // 提示生成中
        showToast("正在用全量通知生成 " + dayKey + " 的纪要...", false);
        final List<String> lines = loadDayReportLines(dayKey);
        new Thread(new Runnable() {
            @Override public void run() {
                final String report = AIAnalyzer.buildReport(MainActivity.this, dayKey, lines);
                ReportStore.saveReport(MainActivity.this, dayKey, report);
                ReportStore.markGenerated(MainActivity.this, dayKey, lines.size());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshAiPage();      // 刷新 AI 页显示
                        refreshHomeStats();   // 刷新首页统计
                        showToast("「" + dayKey + "」纪要已生成", false);
                    }
                });
            }
        }).start();
    }

    /** App 启动时：检查昨天纪要是否已生成，没有则自动补生成（后台，前台提示）。 */
    private void autoGenYesterdayIfMissing() {
        final String ykey = ReportStore.yesterdayKey();
        if (ReportStore.hasReport(this, ykey)) return; // 已有则跳过
        final List<String> lines = loadDayReportLines(ykey);
        if (lines.isEmpty()) return;
        showToast("检测到昨天(" + ykey + ")还没生成纪要，正在后台生成...", false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String dateStr = ykey;
                final String report = AIAnalyzer.buildReport(MainActivity.this, dateStr, lines);
                ReportStore.saveReport(MainActivity.this, ykey, report);
                ReportStore.markGenerated(MainActivity.this, ykey, lines.size());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        showToast("昨天(" + ykey + ")纪要已自动生成", false);
                    }
                });
            }
        }).start();
    }

    // ============ 设置中心页（我的）============

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(ThemeManager.color(this, ThemeManager.BG));
        scroll.addView(root);
        LinearLayout.LayoutParams mp = matcher();

        TextView user = UiKit.sectionTitle(this, "👤 我的");
        root.addView(user, mp);

        // ---- 二级菜单（竖栏，点击展开/收起对应面板）----
        final String[] menuNames = {"🎨 主题", "✨ AI 设置", "☁️ 同步设置",
                "📝 纪要设置", "🔍 通知过滤", "🔑 权限获取", "🗄️ 存储管理"};
        final LinearLayout[] panels = new LinearLayout[menuNames.length];
        menuArrows.clear();
        currentSettingsPanel = -1;
        for (int i = 0; i < menuNames.length; i++) {
            // 菜单行
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ThemeManager.styleCard(row, this, ThemeManager.CARD_RADIUS);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView lbl = new TextView(this);
            lbl.setText(menuNames[i]);
            lbl.setTextSize(15);
            lbl.setTextColor(ThemeManager.color(this, ThemeManager.TEXT));
            android.widget.TextView arrow = new android.widget.TextView(this);
            arrow.setText("\u203A");   // › 向右折叠
            arrow.setTextSize(20);
            arrow.setTextColor(ThemeManager.color(this, ThemeManager.SECONDARY));
            arrow.setGravity(Gravity.CENTER_VERTICAL);
            menuArrows.add(arrow);   // 记录箭头，切换时改方向
            row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(arrow);
            // 面板
            panels[i] = new LinearLayout(this);
            panels[i].setOrientation(LinearLayout.VERTICAL);
            panels[i].setVisibility(View.GONE);
            panels[i].setPadding(dp(6), dp(4), dp(6), dp(8));
            final int fi = i;
            row.setOnClickListener(v -> switchSettingsPanel(fi, panels));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(8));
            root.addView(row, rp);
            root.addView(panels[i], mp);
        }
        // 填充各面板内容
        buildThemePanel(panels[0], mp);
        buildAiPanel(panels[1], mp);
        buildSyncPanel(panels[2], mp);
        buildReportPanel(panels[3], mp);
        buildFilterPanel(panels[4], mp);
        buildPermPanel(panels[5], mp);
        buildStoragePanel(panels[6], mp);
        return scroll;
    }

    /** 手风琴：点选展开该面板并收起其他；再点当前展开项则收起。箭头方向同步。 */
    private void switchSettingsPanel(int idx, LinearLayout[] panels) {
        boolean close = (currentSettingsPanel == idx);   // 再点当前展开项 -> 收起
        currentSettingsPanel = close ? -1 : idx;
        for (int i = 0; i < panels.length; i++) {
            boolean show = !close && i == idx;
            panels[i].setVisibility(show ? View.VISIBLE : View.GONE);
            if (i < menuArrows.size()) {
                menuArrows.get(i).setText(show ? "\u2304" : "\u203A");   // ⌄ / ›
            }
        }
    }

    // ---- 二级菜单面板 ----

    /** 🎨 主题面板。 */
    private void buildThemePanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        TextView lbl = new TextView(this);
        lbl.setText("选择主题风格");
        lbl.setTextSize(14);
        lbl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lbl.setTextColor(Color.parseColor("#5A9BD5"));
        lbl.setPadding(dp(4), dp(6), dp(4), dp(6));
        p.addView(lbl, mp);
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        int curTheme = ThemeManager.themeIndex(this);
        for (int i = 0; i < ThemeManager.THEME_NAMES.length; i++) {
            final int ti = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            cell.setBackground(ThemeManager.rounded(
                    curTheme == ti ? ThemeManager.color(ti, ThemeManager.ACCENT) : Color.TRANSPARENT,
                    Color.WHITE, 10, curTheme == ti ? 3 : 0));
            android.widget.FrameLayout sw = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ThemeManager.color(ti, ThemeManager.GRAD_S), ThemeManager.color(ti, ThemeManager.GRAD_E)});
            gd.setCornerRadius(dp(6));
            sw.setBackground(gd);
            cell.addView(sw, new android.widget.FrameLayout.LayoutParams(dp(36), dp(24)));
            TextView nm = new TextView(this);
            nm.setText(ThemeManager.THEME_NAMES[ti]);
            nm.setTextSize(11);
            nm.setTextColor(Color.parseColor("#37474F"));
            nm.setGravity(Gravity.CENTER);
            cell.addView(nm);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ThemeManager.setTheme(MainActivity.this, ti);
                    showToast("已切换到「" + ThemeManager.THEME_NAMES[ti] + "」主题", false);
                    recreate();
                }
            });
            themeRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        p.addView(themeRow, mp);
    }

    /** ✨ AI 设置面板。 */
    private void buildAiPanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        LinearLayout rowAi = rowSwitch("🤖 AI 智能分析", "ai_enabled", true);
        p.addView(rowAi, mp);
        addLineText(p, "模式：" + aiModeSuffix(), "#90A4AE");
        aiModeGroup = new android.widget.RadioGroup(this);
        aiModeGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton rbD = new android.widget.RadioButton(this);
        rbD.setId(101); rbD.setText("直连");
        android.widget.RadioButton rbR = new android.widget.RadioButton(this);
        rbR.setId(102); rbR.setText("中转");
        aiModeGroup.addView(rbD); aiModeGroup.addView(rbR);
        String md = Config.get(this, Config.KEY_AI_MODE, "direct");
        rbD.setChecked(!"relay".equals(md)); rbR.setChecked("relay".equals(md));
        p.addView(aiModeGroup, mp);

        String pb = Config.get(this, Config.KEY_AI_BASE_URL, ""); pb = pb.isEmpty() ? AIAnalyzer.PRESET_BASE_URL : pb;
        String pk = Config.getAiKey(this); pk = pk.isEmpty() ? AIAnalyzer.PRESET_API_KEY : pk;
        String pm = Config.get(this, Config.KEY_AI_MODEL, ""); pm = pm.isEmpty() ? AIAnalyzer.PRESET_MODEL : pm;
        String pr = Config.get(this, Config.KEY_AI_RELAY_URL, "");
        aiBase = new EditText(this);
        aiBase.setHint("Base URL"); aiBase.setText(pb); p.addView(aiBase, mp);
        aiKey = new EditText(this);
        aiKey.setHint("API Key"); aiKey.setText(pk); p.addView(aiKey, mp);
        aiModel = new EditText(this);
        aiModel.setHint("模型名"); aiModel.setText(pm); p.addView(aiModel, mp);
        aiRelay = new EditText(this);
        aiRelay.setHint("中转 URL（中转模式用）"); aiRelay.setText(pr); p.addView(aiRelay, mp);

        TextView lblPrompt = new TextView(this);
        lblPrompt.setText("📝 分析提示词（所有分析都用这个）");
        lblPrompt.setTextSize(13);
        lblPrompt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblPrompt.setTextColor(Color.parseColor("#5A9BD5"));
        lblPrompt.setPadding(dp(4), dp(8), dp(4), dp(4));
        p.addView(lblPrompt, mp);
        aiPrompt = new EditText(this);
        aiPrompt.setText(AIAnalyzer.getPrompt(this));
        aiPrompt.setGravity(Gravity.TOP | Gravity.START);
        aiPrompt.setMinLines(6);
        aiPrompt.setTextSize(12);
        aiPrompt.setPadding(dp(6), dp(6), dp(6), dp(6));
        p.addView(aiPrompt, mp);

        Button btnAiSave = UiKit.primary(this, "💾 保存 AI 配置并测试");
        btnAiSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAiConfigFromSettings(); }
        });
        LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSave.setMargins(0, dp(6), 0, dp(4));
        p.addView(btnAiSave, lpSave);
        Button btnAiDetail = UiKit.ghost(this, "⚙️ 高级 AI 配置");
        btnAiDetail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AiConfigActivity.class));
            }
        });
        LinearLayout.LayoutParams lpDetail = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpDetail.setMargins(0, dp(4), 0, 0);
        p.addView(btnAiDetail, lpDetail);
    }

    /** ☁️ 同步设置面板（WebDAV 表单 + 自动同步 + 立即同步 + 近期同步）。 */
    private void buildSyncPanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        buildConfigPageContent(p, mp);   // 复用 WebDAV 表单与同步逻辑
    }

    /** 📝 纪要设置面板。 */
    private void buildReportPanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        LinearLayout rowGen = rowSwitch("🤖 AI 智能分析", "ai_enabled", true);
        p.addView(rowGen, mp);
        LinearLayout rowRep = rowSwitch("📝 自动生成纪要", Config.KEY_AI_REPORT, true);
        p.addView(rowRep, mp);
        addLineText(p, "「AI 智能分析」开启后，每天自动/手动生成通知沉淀纪要。", "#90A4AE");
        TextView tip = new TextView(this);
        tip.setText("生成入口：首页「📊数据看板」上方或「🤖AI」页「手动生成今日纪要」");
        tip.setTextSize(12);
        tip.setTextColor(Color.parseColor("#90A4AE"));
        tip.setPadding(dp(4), dp(6), dp(4), dp(2));
        p.addView(tip, mp);
    }

    /** 🔍 通知过滤面板。 */
    private void buildFilterPanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        addLineText(p, "设置黑白名单、关键词优先级，控制哪些应用的通知进入统计与纪要。", "#90A4AE");
        Button btnFilter = UiKit.primary(this, "🔍 打开通知过滤设置");
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FilterActivity.class));
            }
        });
        LinearLayout.LayoutParams lpFilter = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpFilter.setMargins(0, dp(6), 0, dp(2));
        p.addView(btnFilter, lpFilter);
    }

    /** 🔑 权限获取面板：通知监听 + 无障碍 + Xposed（占位）。 */
    private void buildPermPanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        TextView t = new TextView(this);
        t.setText("消息获取方式");
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#5A9BD5"));
        t.setPadding(dp(4), dp(4), dp(4), dp(6));
        p.addView(t, mp);

        // 通知监听授权状态 + 入口
        final TextView stListener = new TextView(this);
        stListener.setText("通知监听状态：检测中…");
        stListener.setTextSize(13);
        stListener.setPadding(dp(4), dp(4), dp(4), dp(2));
        p.addView(stListener, mp);
        Button btnListener = UiKit.primary(this, "🔔 开启通知使用权");
        btnListener.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotificationAccess(); }
        });
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpL.setMargins(0, dp(6), 0, dp(4));
        p.addView(btnListener, lpL);

        // 无障碍辅助服务入口（补充通道，可捕获更多特殊通知）
        Button btnAcc = UiKit.ghost(this, "♿ 开启无障碍服务");
        btnAcc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    showToast("请手动到 设置-无障碍 开启 NotifyBridge 辅助", true);
                }
            }
        });
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpA.setMargins(0, dp(4), 0, dp(4));
        p.addView(btnAcc, lpA);

        // ---- 获取模式切换：普通（通知监听+无障碍） vs Xposed ----
        TextView modeTip = new TextView(this);
        modeTip.setText("消息获取模式");
        modeTip.setTextSize(13);
        modeTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        modeTip.setTextColor(Color.parseColor("#5A9BD5"));
        modeTip.setPadding(dp(4), dp(10), dp(4), dp(4));
        p.addView(modeTip, mp);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeLabel = new TextView(this);
        modeLabel.setText("🛠️ Xposed 模块");
        modeLabel.setTextSize(14);
        Switch swXposed = new Switch(this);
        swXposed.setChecked(Config.getBool(this, Config.KEY_XPOSED_ENABLED, false));
        swXposed.setOnCheckedChangeListener((v, on) -> {
            Config.putBool(this, Config.KEY_XPOSED_ENABLED, on);
            showToast(on ? "Xposed 模式已开启。需在 LSPosed 启用本模块并勾选作用域" : "已切回普通通知模式", false);
        });
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        modeRow.addView(swXposed);
        p.addView(modeRow, mp);
        addLineText(p, "开启后在 LSPosed 启用本模块 → 作用域勾选微信(com.tencent.mm) → 强制停止微信。可 Hook 到“电脑登录也不推送”的消息。", "#90A4AE");

        // 去 LSPosed 引导
        Button btnLsposed = UiKit.ghost(this, "🧩 打开 LSPosed 管理器");
        btnLsposed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(getPackageManager().getLaunchIntentForPackage("org.lsposed.manager"));
                } catch (Throwable t) {
                    showToast("未检测到 LSPosed 管理器，请先安装", true);
                }
            }
        });
        LinearLayout.LayoutParams lpLsp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpLsp.setMargins(0, dp(4), 0, dp(4));
        p.addView(btnLsposed, lpLsp);

        // ---- 后台保活引导（锁屏/后台收不到的核心是 MIUI 后台限制）----
        TextView keepTip = new TextView(this);
        keepTip.setText("🔋 后台/锁屏收不到？多为小米后台限制");
        keepTip.setTextSize(13);
        keepTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        keepTip.setTextColor(Color.parseColor("#5A9BD5"));
        keepTip.setPadding(dp(4), dp(10), dp(4), dp(4));
        p.addView(keepTip, mp);
        Button btnAutostart = UiKit.primary(this, "⚙️ 允许自启动");
        btnAutostart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent("miui.intent.action.OP_AUTO_START"));
                } catch (Throwable t) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()))); }
                    catch (Throwable t2) { showToast("请手动到 设置-应用管理-自启动 里允许", true); }
                }
            }
        });
        LinearLayout.LayoutParams lpAuto = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpAuto.setMargins(0, dp(4), 0, dp(4));
        p.addView(btnAutostart, lpAuto);
        addLineText(p, "小米还需：设置-应用-NotifyBridge-省电策略设为「无限制」，并允许后台运行与自启动。锁屏收不到通常因后台被限制。", "#90A4AE");

        // Xposed 状态提示（读 /data/local/tmp 或广播收不到时提示排查）
        addLineText(p, "提示：Xposed 消息会经广播进入日志与统计，若收不到请在 LSPosed 日志确认已注入微信。", "#90A4AE");

        // 刷新监听状态
        stListener.post(new Runnable() {
            @Override public void run() {
                stListener.setText("通知监听状态：" + (isListenerEnabled() ? "✅ 已授权" : "⚠️ 未开启"));
            }
        });
    }

    /** 🗄️ 存储管理面板。 */
    private void buildStoragePanel(LinearLayout p, LinearLayout.LayoutParams mp) {
        TextView title = UiKit.sectionTitle(this, "📁 每日 MD 日志");
        p.addView(title, mp);

        final LinearLayout logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        p.addView(logList, mp);

        Button btnRefresh = UiKit.ghost(this, "🔄 刷新列表");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                renderDailyFiles(logList);
                showToast("已刷新", false);
            }
        });
        LinearLayout.LayoutParams lpRef = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRef.setMargins(0, dp(4), 0, dp(6));
        p.addView(btnRefresh, lpRef);

        renderDailyFiles(logList);

        Button btnClearCache = UiKit.ghost(this, "🗑️ 清空通知缓存");
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = NotificationCache.clearAll(MainActivity.this);
                refreshCachedCount();
                showToast("已清空 " + n + " 条缓存", false);
            }
        });
        LinearLayout.LayoutParams lpCc = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCc.setMargins(0, dp(4), 0, dp(4));
        p.addView(btnClearCache, lpCc);
        Button btnClearLog = UiKit.ghost(this, "🗑️ 清空日志");
        btnClearLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LogStore.clear(MainActivity.this);
                showToast("日志已清空", false);
            }
        });
        LinearLayout.LayoutParams lpCl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCl.setMargins(0, dp(4), 0, 0);
        p.addView(btnClearLog, lpCl);
    }

    /** 在存储管理面板里列出 dailynote md 文件，点击可预览内容。 */
    private void renderDailyFiles(final LinearLayout list) {
        list.removeAllViews();
        File[] files = DailyLog.files(MainActivity.this);
        if (files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无每日 MD 日志");
            empty.setTextSize(14);
            empty.setTextColor(Color.parseColor("#90A4AE"));
            empty.setPadding(dp(2), dp(8), dp(2), dp(8));
            list.addView(empty, matcher());
            return;
        }
        final java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        for (final File f : files) {
            final String name = f.getName(); // e.g. dailynote20260906.md
            String ymd = name.replace("dailynote", "").replace(".md", "");
            String date = "----";
            try { date = day.format(new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(ymd)); } catch (Exception ignored) {}
            long kb = f.length() / 1024;
            String size = kb > 0 ? kb + " KB" : (f.length() + " B");

            TextView row = new TextView(this);
            row.setText(date + "   " + size);
            row.setTextSize(14);
            row.setTextColor(Color.parseColor("#37474F"));
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setBackground(ThemeManager.rounded(Color.WHITE, Color.parseColor("#F4F8FA"), 8, 1));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    previewDaily(f);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            list.addView(row, lp);
        }
    }

    /** 弹窗预览单个 md 文件内容。 */
    private void previewDaily(File f) {
        String content = DailyLog.readFile(f);
        if (content.isEmpty()) content = "（空文件）";
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#37474F"));
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 当前 AI 模式后缀（用于入口按钮显示）。 */
    private String aiModeSuffix() {
        String mode = Config.get(this, Config.KEY_AI_MODE, "direct");
        if ("relay".equals(mode)) return "（中转）";
        if ("direct".equals(mode)) return "（AI 直连）";
        return "（未配置 AI）";
    }

    /** 保存设置页内嵌的 AI 配置并测试连接。 */
    private void saveAiConfigFromSettings() {
        int id = aiModeGroup.getCheckedRadioButtonId();
        String mode = id == 102 ? "relay" : "direct";
        Config.put(this, Config.KEY_AI_MODE, mode);
        Config.put(this, Config.KEY_AI_BASE_URL, aiBase.getText().toString().trim());
        Config.setAiKey(this, aiKey.getText().toString().trim());
        Config.put(this, Config.KEY_AI_MODEL, aiModel.getText().toString().trim());
        Config.put(this, Config.KEY_AI_RELAY_URL, aiRelay.getText().toString().trim());
        if (aiPrompt != null) AIAnalyzer.savePrompt(this, aiPrompt.getText().toString());
        Config.putBool(this, Config.KEY_AI_ENABLED, true);
        showToast("AI 配置已保存，正在测试连接...", false);
        new Thread(new Runnable() {
            @Override public void run() {
                final AIAnalyzer.AIResult r = AIAnalyzer.testConnection(MainActivity.this);
                LogStore.diag(MainActivity.this, r.ok ? "✅ AI 连接成功" : "❌ AI 连接失败: " + r.error);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        showToast(r.ok ? "✅ AI 已连通：" + (r.text.length() > 30 ? r.text.substring(0, 30) : r.text) : "❌ " + r.error, !r.ok);
                    }
                });
            }
        }).start();
    }

    /** 带 Switch 的设置行。 */
    private LinearLayout rowSwitch(String label, final String prefKey, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(16);
        lbl.setTextColor(Color.parseColor("#37474F"));
        Switch sw = new Switch(this);
        sw.setChecked(Config.getBool(this, prefKey, def));
        sw.setOnCheckedChangeListener((v, isOn) -> Config.putBool(this, prefKey, isOn));
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw);
        return row;
    }

    // 每 2 秒自动刷新（可选开启；这里在进入日志页时启动）
    private void startLogRefresh() {
        stopLogRefresh();
        refresher = new Runnable() {
            @Override public void run() {
                if (logPage != null && logPage.getVisibility() == View.VISIBLE) {
                    refreshLog();
                }
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(refresher, 1000);
    }

    private void stopLogRefresh() {
        if (refresher != null) handler.removeCallbacks(refresher);
    }

    // ============ 工具 ============

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(17);
        tv.setTextColor(Color.parseColor("#5A9BD5")); // 清新蓝
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private LinearLayout.LayoutParams matcher() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 小清新自定义 Toast：浅色圆角底 + 深色文字，清晰可读（不受系统深色模式影响）。
     * @param err true=错误风格（浅粉底/深红字），false=普通风格（浅薄荷底/深绿字）
     */
    private void showToast(final String msg, final boolean err) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast t = Toast.makeText(MainActivity.this, "", Toast.LENGTH_LONG);
                TextView v = new TextView(MainActivity.this);
                v.setText(msg);
                v.setTextSize(14);
                v.setPadding(dp(20), dp(12), dp(20), dp(12));
                v.setTextColor(Color.parseColor(err ? "#C0392B" : "#2E7D32"));
                v.setBackgroundColor(Color.parseColor(err ? "#FDEDEC" : "#F1F8E9"));
                v.setGravity(Gravity.CENTER);
                t.setView(v);
                t.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, dp(60));
                t.show();
            }
        });
    }

    private void openNotificationAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                showToast("请手动到 设置-通知使用权 开启", true);
            }
        } else {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isListenerEnabled() {
        ComponentName cn = new ComponentName(this, NotifyListener.class);
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private void refreshListenerState() {
        boolean on = isListenerEnabled();
        tvStatus.setText(on
                ? "✅ 通知监听已授权 · 前台保活已启动"
                : "⚠️ 通知监听未开启，请先在系统设置中授权");
        tvStatus.setTextColor(on ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
        if (on) {
            UploadScheduler.scheduleRepeating(this);
            startKeepAlive();
        }
    }

    private void startKeepAlive() {
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "keepalive err", e);
        }
    }

    private void refreshCachedCount() {
        int n = Config.getInt(this, Config.KEY_CACHED_COUNT, 0);
        if (tvCached != null) tvCached.setText("本地缓存待上传： " + n + " 条");
    }

    private void saveConfig() {
        String url = etUrl.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString();
        if (url.isEmpty() || user.isEmpty()) {
            showToast("服务器地址和账号不能为空", true);
            return;
        }
        Config.put(this, Config.KEY_URL, url);
        Config.put(this, Config.KEY_USER, user);
        Config.setPassword(this, pass);
        if (etDir != null) {
            String dir = etDir.getText().toString().trim();
            if (dir.isEmpty()) dir = "notifybridge";
            Config.put(this, Config.KEY_WEBDAV_DIR, dir);
        }
        Config.putBool(this, Config.KEY_SERVER_VERIFIED, false);
        showToast("配置已保存", false);
    }

    private void testConnection() {
        final String url = etUrl.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (url.isEmpty() || user.isEmpty()) {
            showToast("请先填写服务器地址与账号", true);
            return;
        }
        showToast("测试中…", false);
        Uploader.test(this, url, user, pass, new Uploader.Callback() {
            @Override public void onResult(boolean ok, String msg) {
                showToast((ok ? "✅ 连接成功 " : "❌ ") + msg, !ok);
            }
        });
    }

    private void syncNow() {
        Uploader.run(this, new Uploader.Callback() {
            @Override public void onResult(boolean ok, String msg) {
                refreshCachedCount();
                showToast(msg, !ok);
            }
        });
    }
}
