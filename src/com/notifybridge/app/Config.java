package com.notifybridge.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用配置常量与读写。敏感字段（密码）经 CryptoStore 加密后存储。
 */
public final class Config {
    private static final String PREFS = "notifybridge_prefs";

    // WebDAV 配置键
    public static final String KEY_URL = "webdav_url";
    public static final String KEY_USER = "webdav_user";
    public static final String KEY_PASS = "webdav_pass";      // 加密存储
    public static final String KEY_SERVER_VERIFIED = "server_verified";

    // 过滤配置键
    public static final String KEY_FILTER_MODE = "filter_mode";   // 0=黑名单 1=白名单
    public static final String KEY_ALLOWED = "filter_allowed";    // 逗号分隔包名
    public static final String KEY_BLOCKED = "filter_blocked";    // 逗号分隔包名

    // v2 AI 配置键
    public static final String KEY_AI_ENABLED = "ai_enabled";          // AI 智能分类开关
    public static final String KEY_AI_TODO = "ai_todo";                // 提取待办
    public static final String KEY_AI_URGENT = "ai_urgent";            // 标记紧急
    public static final String KEY_AI_REPORT = "ai_report";            // 生成每日纪要
    public static final String KEY_KEYWORDS = "ai_keywords";           // 逗号分隔关键词

    // v2 AI 模型配置
    public static final String KEY_AI_MODE = "ai_mode";                // "direct"=OpenAI兼容直连  "relay"=服务器中转  "local"=本地规则
    public static final String KEY_AI_BASE_URL = "ai_base_url";        // OpenAI 兼容 Base URL
    public static final String KEY_AI_API_KEY = "ai_api_key";          // API Key（加密存储）
    public static final String KEY_AI_API_KEY_ENC = "ai_api_key_enc";  // API Key（加密密文）
    public static final String KEY_AI_MODEL = "ai_model";              // 模型名
    public static final String KEY_AI_RELAY_URL = "ai_relay_url";      // 服务器/Hermes 中转 URL
    public static final String KEY_AI_PROMPT = "ai_prompt";            // 自定义分析提示词（可为空则用默认）
    public static final String KEY_THEME = "theme_index";              // 主题：0奶蓝 1深色 2多彩 3极简
    public static final String KEY_XPOSED_ENABLED = "xposed_enabled";  // Xposed 模块模式开关（占位）

    // 上传配置
    public static final String KEY_UPLOAD_INTERVAL_MS = "upload_interval_ms";
    public static final String KEY_UPLOAD_BATCH_SIZE = "upload_batch_size";
    public static final String KEY_SYNC_ENABLED = "sync_enabled";
    public static final String KEY_WEBDAV_DIR = "webdav_dir";
    public static final String KEY_DAILY_KEEP_DAYS = "daily_keep_days";

    // 其他状态
    public static final String KEY_LAST_SYNC = "last_sync_time";
    public static final String KEY_CACHED_COUNT = "cached_count";
    public static final String KEY_KEEP_RECORDS = "keep_records";   // 滚动保留：最多保留多少条通知记录(超出自动删最旧)，默认2000

    public static final int FILTER_MODE_BLACKLIST = 0;
    public static final int FILTER_MODE_WHITELIST = 1;

    private Config() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) {
        return prefs(c).getString(key, def);
    }

    public static void put(Context c, String key, String val) {
        prefs(c).edit().putString(key, val).apply();
    }

    public static int getInt(Context c, String key, int def) {
        return prefs(c).getInt(key, def);
    }

    public static void putInt(Context c, String key, int val) {
        prefs(c).edit().putInt(key, val).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return prefs(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean val) {
        prefs(c).edit().putBoolean(key, val).apply();
    }

    /** 读取加密的 WebDAV 密码；未设置或解密失败返回空串。 */
    public static String getPassword(Context c) {
        String enc = prefs(c).getString(KEY_PASS, null);
        if (enc == null || enc.isEmpty()) return "";
        try {
            return CryptoStore.decrypt(c, enc);
        } catch (Exception e) {
            return "";
        }
    }

    /** 加密后存储 WebDAV 密码。 */
    public static void setPassword(Context c, String plain) {
        if (plain == null) plain = "";
        try {
            String enc = CryptoStore.encrypt(c, plain);
            prefs(c).edit().putString(KEY_PASS, enc).apply();
        } catch (Exception e) {
            prefs(c).edit().putString(KEY_PASS, "").apply();
        }
    }

    /** 读取 AI API Key（优先加密存储；兼容历史明文）。 */
    public static String getAiKey(Context c) {
        String enc = prefs(c).getString(KEY_AI_API_KEY_ENC, null);
        if (enc != null && !enc.isEmpty()) {
            try { return CryptoStore.decrypt(c, enc); } catch (Exception ignored) {}
        }
        // 兼容历史明文存储
        return prefs(c).getString(KEY_AI_API_KEY, "");
    }

    /** 加密后存储 AI API Key（迁移历史明文）。 */
    public static void setAiKey(Context c, String plain) {
        if (plain == null) plain = "";
        android.content.SharedPreferences.Editor e = prefs(c).edit();
        e.putString(KEY_AI_API_KEY, "");   // 清掉旧明文
        try {
            e.putString(KEY_AI_API_KEY_ENC, CryptoStore.encrypt(c, plain));
        } catch (Exception ex) {
            e.putString(KEY_AI_API_KEY_ENC, "");
        }
        e.apply();
    }
}
