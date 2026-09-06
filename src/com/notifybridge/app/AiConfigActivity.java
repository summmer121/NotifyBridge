package com.notifybridge.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AI 模型配置（v2）。
 * 支持三种模式：
 *   - local ：本地规则（默认，不联网）
 *   - direct：OpenAI 兼容直连（Base URL + API Key + 模型名）
 *   - relay ：服务器/Hermes 中转（中转 URL）
 */
public class AiConfigActivity extends Activity {

    private RadioGroup rgMode;
    private EditText etBase, etKey, etModel, etRelay, etPrompt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));
        scroll.addView(root);
        LinearLayout.LayoutParams mp = m();

        TextView title = new TextView(this);
        title.setText("🤖 AI 模型配置");
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#37474F"));
        root.addView(title, mp);

        // 模式选择
        TextView lblMode = new TextView(this);
        lblMode.setText("分析模式");
        lblMode.setTextSize(15);
        lblMode.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblMode.setTextColor(Color.parseColor("#5A9BD5"));
        lblMode.setPadding(dp(4), dp(16), dp(4), dp(6));
        root.addView(lblMode, mp);

        rgMode = new RadioGroup(this);
        rgMode.setOrientation(LinearLayout.VERTICAL);
        RadioButton rbDirect = new RadioButton(this);
        rbDirect.setId(2); rbDirect.setText("OpenAI 兼容直连（DeepSeek/OpenRouter/Ollama等）");
        RadioButton rbRelay = new RadioButton(this);
        rbRelay.setId(3); rbRelay.setText("服务器 / Hermes 中转");
        rgMode.addView(rbDirect); rgMode.addView(rbRelay);
        String curMode = Config.get(this, Config.KEY_AI_MODE, "direct");
        rbDirect.setChecked(!"relay".equals(curMode));
        rbRelay.setChecked("relay".equals(curMode));
        root.addView(rgMode, mp);

        // OpenAI 直连配置
        TextView lblDirect = new TextView(this);
        lblDirect.setText("OpenAI 兼容接口");
        lblDirect.setTextSize(15);
        lblDirect.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblDirect.setTextColor(Color.parseColor("#5A9BD5"));
        lblDirect.setPadding(dp(4), dp(16), dp(4), dp(6));
        root.addView(lblDirect, mp);

        etBase = input("Base URL", "如 https://api.deepseek.com 或 http://192.168.x.x:11434/v1");
        String presetBase = Config.get(this, Config.KEY_AI_BASE_URL, "");
        etBase.setText(presetBase.isEmpty() ? AIAnalyzer.PRESET_BASE_URL : presetBase);
        root.addView(etBase, mp);
        etKey = input("API Key", "可留空（本地 Ollama 等无需 key）");
        String presetKey = Config.getAiKey(this);
        etKey.setText(presetKey.isEmpty() ? AIAnalyzer.PRESET_API_KEY : presetKey);
        root.addView(etKey, mp);
        etModel = input("模型名", "如 deepseek-chat / qwen3-vl:8b");
        String presetModel = Config.get(this, Config.KEY_AI_MODEL, "");
        etModel.setText(presetModel.isEmpty() ? AIAnalyzer.PRESET_MODEL : presetModel);
        root.addView(etModel, mp);

        // 中转配置
        TextView lblRelay = new TextView(this);
        lblRelay.setText("服务器 / Hermes 中转");
        lblRelay.setTextSize(15);
        lblRelay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblRelay.setTextColor(Color.parseColor("#5A9BD5"));
        lblRelay.setPadding(dp(4), dp(16), dp(4), dp(6));
        root.addView(lblRelay, mp);

        etRelay = input("中转 URL", "POST JSON {text:...}，返回文本或 {choices:[{message:{content}}]}");
        etRelay.setText(Config.get(this, Config.KEY_AI_RELAY_URL, ""));
        root.addView(etRelay, mp);

        // 分析提示词（可编辑，预填充默认；所有分析都用它）
        TextView lblPrompt = new TextView(this);
        lblPrompt.setText("📝 分析提示词（所有分析都用这个）");
        lblPrompt.setTextSize(15);
        lblPrompt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        lblPrompt.setTextColor(Color.parseColor("#5A9BD5"));
        lblPrompt.setPadding(dp(4), dp(16), dp(4), dp(6));
        root.addView(lblPrompt, mp);

        etPrompt = new EditText(this);
        etPrompt.setText(AIAnalyzer.getPrompt(this));
        etPrompt.setGravity(Gravity.TOP | Gravity.START);
        etPrompt.setMinLines(10);
        etPrompt.setTextSize(13);
        etPrompt.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(etPrompt, mp);

        // 测试按钮
        Button btnTest = new Button(this);
        btnTest.setText("🔗 测试 AI 连接");
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveConfig(); testAi(); }
        });
        root.addView(btnTest, mp);

        // 保存
        Button btnSave = new Button(this);
        btnSave.setText("💾 保存配置");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setBackgroundColor(Color.parseColor("#1976D2"));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveConfig(); showToast("已保存", false); finish(); }
        });
        root.addView(btnSave, mp);

        setContentView(scroll);
        applyEdgeToEdge(scroll);
    }

    private EditText input(String hint, String hintLine2) {
        EditText et = new EditText(this);
        et.setHint(hint + (hintLine2 == null ? "" : "（" + hintLine2 + "）"));
        et.setSingleLine(false);
        et.setMinLines(1);
        et.setPadding(dp(8), dp(8), dp(8), dp(8));
        return et;
    }

    private void saveConfig() {
        int id = rgMode.getCheckedRadioButtonId();
        String mode = id == 3 ? "relay" : "direct";
        Config.put(this, Config.KEY_AI_MODE, mode);
        Config.put(this, Config.KEY_AI_BASE_URL, etBase.getText().toString().trim());
        Config.setAiKey(this, etKey.getText().toString().trim());
        Config.put(this, Config.KEY_AI_MODEL, etModel.getText().toString().trim());
        Config.put(this, Config.KEY_AI_RELAY_URL, etRelay.getText().toString().trim());
        if (etPrompt != null) AIAnalyzer.savePrompt(this, etPrompt.getText().toString());
    }

    private void testAi() {
        // 记录配置到日志，供 AI/纪要页使用
        String mode = Config.get(this, Config.KEY_AI_MODE, "direct");
        LogStore.diag(this, "AI 模式: " + mode);
        if ("direct".equals(mode)) {
            AIAnalyzer.AIResult r = AIAnalyzer.testConnection(this);
            LogStore.diag(this, r.ok ? "✅ AI 直连测试成功: " + (r.text.length() > 60 ? r.text.substring(0, 60) : r.text)
                    : "❌ AI 测试失败: " + r.error);
            showToast(r.ok ? "✅ AI 连接成功" : "❌ " + r.error, !r.ok);
        } else if ("relay".equals(mode)) {
            AIAnalyzer.AIResult r = AIAnalyzer.testConnection(this);
            LogStore.diag(this, r.ok ? "✅ 中转测试成功" : "❌ 中转测试失败: " + r.error);
            showToast(r.ok ? "✅ 中转连接成功" : "❌ " + r.error, !r.ok);
        }
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
                    java.lang.reflect.Method mm = android.view.Window.class.getMethod(
                            "setDecorFitsSystemWindows", boolean.class);
                    mm.invoke(w, false);
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
                Toast t = Toast.makeText(AiConfigActivity.this, "", Toast.LENGTH_LONG);
                TextView v = new TextView(AiConfigActivity.this);
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
