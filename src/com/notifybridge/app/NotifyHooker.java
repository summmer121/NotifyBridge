package com.notifybridge.app;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NotifyBridge Xposed 模块入口。
 * <p>
 * 目标：在"电脑登录也不推送系统通知"的 App（典型如微信 com.tencent.mm）进程内
 * Hook 其新消息的【数据写入层】——静音/勿扰/电脑登录时系统通知被跳过、但消息照样
 * 落库，所以 hook 写库必经切点，与是否推送通知无关，静音消息也能被捕获。取到内容后
 * 以广播回传给 NotifyBridge App（写入统一缓存/日志）。
 * <p>
 * 切点策略（由稳到稳、多层兜底，跨版本尽量覆盖）：
 *  1. 数据层主切点：ContentResolver.insert/update（ContentProvider 写库必经）
 *  2. 数据层兜底：SQLiteDatabase.insert/insertWithOnConflict/update（新版本微信
 *     走本地 .db 直写，不走 ContentProvider 时靠它捕获）
 *  3. 原消息组装层候选方法 + 通知构建（尽力而为）
 * 用 RecentBroadcast 去重，避免同一条消息被多层切点重复广播刷屏。
 * <p>
 * 跨进程通信用 sendBroadcast（微信 host 的 SELinux 下广播是唯一可靠通道），
 * 对应 App 内 NotifyXposedReceiver，App 日志分页即可看到 [Xposed] 条目。
 */
public class NotifyHooker implements IXposedHookLoadPackage {

    public static final String PKG = "com.notifybridge.app";

    /** 简单去重：最近广播过的文本 → 时间戳。防止多层切点/重复写库重复广播刷屏。 */
    private static final Map<String, Long> recent = new LinkedHashMap<String, Long>(200) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 250;
        }
    };
    private static final long DEDUP_WINDOW_MS = 8000L;

    private static volatile boolean wcdbHooked = false;
    private static volatile Context wechatContext;

    /** 探针去重：每种 (方法,表名) 只打第一条，给出真实表名 + ContentValues key 集合（诊断用，避免刷屏）。 */
    private static final java.util.Set<String> WCDB_PROBED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void wcdbProbe(String tag, String table, ContentValues cv) {
        try {
            if (table == null) return;
            String key = tag + "|" + table;
            if (!WCDB_PROBED.add(key)) return;
            String keys = "-";
            if (cv != null) {
                java.util.Set<String> ks = cv.keySet();
                if (ks != null && !ks.isEmpty()) keys = String.join(",", ks);
            }
            XposedBridge.log("[NotifyX][WCDB]  probe[once] " + tag + " table=" + table + " keys=[" + keys + "]");
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        // 自己：不做 hook，仅记录（用于确认模块已被 LSPosed 加载）
        if (PKG.equals(pkg)) {
            hookSelf(lpparam);
            return;
        }
        // 目标 App：微信（主钩点）+ 企业微信/钉钉可扩展
        String proc = lpparam.processName;
        boolean isWeChat = "com.tencent.mm".equals(pkg)
                || "com.tencent.mm".equals(proc)
                || (proc != null && proc.startsWith("com.tencent.mm:"));
        if (isWeChat) {
            hookWeChat(lpparam);
        } else if ("com.tencent.wework".equals(pkg)) {
            hookGeneric(lpparam, "com.tencent.wework");
        }
    }

    /** 钩入自己进程（确认模块被 LSPosed 加载的标记）。 */
    private void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        try { XposedBridge.log("[NotifyX][INIT] 模块已加载(pkg=" + lpparam.packageName + ") 自身包体/测试"); } catch (Throwable ignored) {}
    }

    /** 微信：Hook 消息【数据写入层】(ContentResolver + SQLite) + 原消息组装层候选兜底。 */
    private void hookWeChat(final XC_LoadPackage.LoadPackageParam lpparam) {
        try { XposedBridge.log("[NotifyX][INIT] 注入微信 pkg=com.tencent.mm"); } catch (Throwable ignored) {}

        // —— 主切点 A：ContentResolver 数据写入层（ContentProvider 路径）——
        try {
            XposedHelpers.findAndHookMethod(ContentResolver.class, "insert",
                    Uri.class, ContentValues.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Uri uri = (Uri) p.args[0];
                                ContentValues cv = p.args[1] instanceof ContentValues ? (ContentValues) p.args[1] : null;
                                if (uri == null) return;
                                String us = uri.toString();
                                if (isMsgTable(us)) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked ContentResolver.insert 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook ContentResolver.insert fail: " + t); } catch (Throwable ignored) {}
        }

        try {
            XposedHelpers.findAndHookMethod(ContentResolver.class, "update",
                    Uri.class, ContentValues.class, String.class,
                    String[].class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Uri uri = (Uri) p.args[0];
                                ContentValues cv = p.args[1] instanceof ContentValues ? (ContentValues) p.args[1] : null;
                                if (uri == null) return;
                                String us = uri.toString();
                                if (isMsgTable(us) && cv != null && cv.size() > 0) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked ContentResolver.update 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook ContentResolver.update fail: " + t); } catch (Throwable ignored) {}
        }

        // —— 主切点 B'：微信 WCDB 直写（决定性）——
        // 微信 8.x 写消息走的是 com.tencent.wcdb.database.SQLiteDatabase（WCDB 封装），
        // 不经 android.database.sqlite.SQLiteDatabase 标准类，所以标准类 hook 永不触发。
        // 这里用反射按类名 findClass 再 hook，编译期无需引入 WCDB 依赖。
        hookWeChatWcdb(lpparam);

        // —— 主切点 B：SQLiteDatabase 直写（新版本微信本地 .db 直接落库，不经过 ContentProvider）——
        try {
            XposedHelpers.findAndHookMethod(android.database.sqlite.SQLiteDatabase.class, "insert",
                    String.class, String.class, ContentValues.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null) return;
                                if (isMsgTable(table) && cv != null) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked SQLiteDatabase.insert 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook SQLiteDatabase.insert fail: " + t); } catch (Throwable ignored) {}
        }

        try {
            XposedHelpers.findAndHookMethod(android.database.sqlite.SQLiteDatabase.class, "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null) return;
                                if (isMsgTable(table) && cv != null) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked SQLiteDatabase.insertWithOnConflict 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook SQLiteDatabase.insertWithOnConflict fail: " + t); } catch (Throwable ignored) {}
        }

        try {
            XposedHelpers.findAndHookMethod(android.database.sqlite.SQLiteDatabase.class, "update",
                    String.class, ContentValues.class, String.class, Object[].class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[1] instanceof ContentValues ? (ContentValues) p.args[1] : null;
                                if (table == null) return;
                                if (isMsgTable(table) && cv != null && cv.size() > 0) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked SQLiteDatabase.update 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook SQLiteDatabase.update fail: " + t); } catch (Throwable ignored) {}
        }

        // —— 兜底：原消息组装层候选（部分版本消息不走上面两层时）——
        String[][] candidates = {
                {"com.tencent.mm.msg.model.MsgInfoStorageLogic", "getDisplayName"},
                {"com.tencent.mm.modelmulti.NewXml", "newXmlToStr"},
                {"com.tencent.mm.storage.ContactsStorageLogic", "getDisplayName"},
        };
        for (String[] c : candidates) {
            final String cls = c[0];
            final String m = c[1];
            try {
                Class<?> k = XposedHelpers.findClass(cls, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(k, m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        handleWeChatMethod(cls, m, p);
                    }
                });
                try { XposedBridge.log("[NotifyX][INIT]   ✓ hooked " + cls + "." + m); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                // 该类/方法不在当前版本——正常，跳过
            }
        }

        // 兜底：hook 微信通知构建相关类（若存在）
        tryHookGenericNotifySetters(lpparam);
    }

    /**
     * Hook 微信真实的 WCDB 写库类 com.tencent.wcdb.database.SQLiteDatabase。
     * 参考开源成熟方案（自动记账X net.ankio.auto DatabaseHooker）：
     * 微信 8.x 消息落库走 WCDB 的 insertWithOnConflict，切点只在 message/AppMessage 表，
     * after 拿 ContentValues 解析——数据层切点能覆盖静音/勿扰/电脑登录等不发系统通知的场景。
     */
    private void hookWeChatWcdb(XC_LoadPackage.LoadPackageParam lpparam) {
        // Fast path: a child process loader may already point to WeChat base.apk.
        tryHookWeChatWcdb(lpparam.classLoader);

        // Critical fallback for MIUI/Tinker: the loader at handleLoadPackage can point
        // to ContentCatcher or the patch shell. Wait for WeChat's real Application,
        // then install WCDB hooks with its final merged ClassLoader.
        try {
            XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callApplicationOnCreate",
                    Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length == 0
                                        || !(param.args[0] instanceof Application)) return;
                                Application app = (Application) param.args[0];
                                try {
                                    Context ctx = app.getApplicationContext();
                                    wechatContext = ctx == null ? app : ctx;
                                } catch (Throwable ignored) {}
                                tryHookWeChatWcdb(app.getClassLoader());
                            } catch (Throwable t) {
                                try { XposedBridge.log("[NotifyX][WCDB] callApplicationOnCreate callback failed: " + t); } catch (Throwable ignored) {}
                            }
                        }
                    });
            try { XposedBridge.log("[NotifyX][INIT]   + hooked Instrumentation.callApplicationOnCreate (wait final ClassLoader)"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][INIT]   - hook callApplicationOnCreate fail: " + t); } catch (Throwable ignored) {}
        }
    }

    private void tryHookWeChatWcdb(ClassLoader classLoader) {
        if (wcdbHooked || classLoader == null) return;
        final String WCDB = "com.tencent.wcdb.database.SQLiteDatabase";
        Class<?> wcdb;
        try {
            wcdb = XposedHelpers.findClass(WCDB, classLoader);
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB] class not ready, wait real Application loader: " + classLoader); } catch (Throwable ignored) {}
            return;
        }
        try { XposedBridge.log("[NotifyX][WCDB] + install with WeChat real ClassLoader: " + classLoader); } catch (Throwable ignored) {}

        dumpWcdbWriteMethods(wcdb);

        // insertWithOnConflict(String table, String nullColumnHack, ContentValues v, int conflictAlgorithm)
        try {
            XposedHelpers.findAndHookMethod(wcdb, "insertWithOnConflict",
                    String.class, String.class, ContentValues.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null || cv == null) return;
                                wcdbProbe("insertWithOnConflict", table, cv);
                                // 只关心消息/聊天会话表，过滤掉其他业务表
                                if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)
                                        && !isMsgTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv);
                                if (parsed != null && !parsed.isEmpty()) {
                                    broadcastWeChat("com.tencent.mm", parsed);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        wcdbHooked = true; // Avoid duplicate installation after Application retries.
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.insertWithOnConflict 数据层(message/AppMessage)"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.insertWithOnConflict fail: " + t); } catch (Throwable ignored) {}
        }

        // insert(String table, String nullColumnHack, ContentValues v)
        try {
            XposedHelpers.findAndHookMethod(wcdb, "insert",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null || cv == null) return;
                                wcdbProbe("insert", table, cv);
                                if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)
                                        && !isMsgTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv);
                                if (parsed != null && !parsed.isEmpty()) {
                                    broadcastWeChat("com.tencent.mm", parsed);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.insert 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.insert fail: " + t); } catch (Throwable ignored) {}
        }

        // update(String table, ContentValues v, String where, String[] args)
        try {
            XposedHelpers.findAndHookMethod(wcdb, "update",
                    String.class, ContentValues.class, String.class, String[].class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[1] instanceof ContentValues ? (ContentValues) p.args[1] : null;
                                if (table == null || cv == null) return;
                                wcdbProbe("update", table, cv);
                                if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)
                                        && !isMsgTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv);
                                if (parsed != null && !parsed.isEmpty()) {
                                    broadcastWeChat("com.tencent.mm", parsed);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.update 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.update fail: " + t); } catch (Throwable ignored) {}
        }

        // 宽覆盖：insertOrThrow(String,String,ContentValues) —— 某些路径走此重载
        try {
            XposedHelpers.findAndHookMethod(wcdb, "insertOrThrow",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null || cv == null) return;
                                wcdbProbe("insertOrThrow", table, cv);
                                if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)
                                        && !isMsgTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv);
                                if (parsed != null && !parsed.isEmpty()) {
                                    broadcastWeChat("com.tencent.mm", parsed);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.insertOrThrow 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.insertOrThrow fail: " + t); } catch (Throwable ignored) {}
        }

        // 宽覆盖：replace(String,String,ContentValues) —— WCDB 有 replace 语义重载
        try {
            XposedHelpers.findAndHookMethod(wcdb, "replace",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = p.args[2] instanceof ContentValues ? (ContentValues) p.args[2] : null;
                                if (table == null || cv == null) return;
                                wcdbProbe("replace", table, cv);
                                if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)
                                        && !isMsgTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv);
                                if (parsed != null && !parsed.isEmpty()) {
                                    broadcastWeChat("com.tencent.mm", parsed);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.replace 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.replace fail: " + t); } catch (Throwable ignored) {}
        }

        // v11 全覆盖：replaceOrThrow(String,String,ContentValues) —— 微信可能走此重载
        try {
            XposedHelpers.findAndHookMethod(wcdb, "replaceOrThrow",
                    String.class, String.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                String table = (String) p.args[0];
                                ContentValues cv = (ContentValues) p.args[2];
                                if (table == null || cv == null) return;
                                wcdbProbe("replaceOrThrow", table, cv);
                                if (isMsgTable(table)) {
                                    broadcastWeChat("com.tencent.mm", extractBody(cv));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.replaceOrThrow 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.replaceOrThrow fail: " + t); } catch (Throwable ignored) {}
        }

        // v11 全覆盖：updateWithOnConflict(String,ContentValues,String,String[],int) —— 微信可能走此重载
        try {
            XposedHelpers.findAndHookMethod(wcdb, "updateWithOnConflict",
                    String.class, ContentValues.class, String.class, String[].class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                String table = (String) p.args[0];
                                if (table == null) return;
                                if (p.args.length > 1 && p.args[1] instanceof ContentValues) {
                                    ContentValues cv = (ContentValues) p.args[1];
                                    wcdbProbe("updateWithOnConflict", table, cv);
                                    if (isMsgTable(table)) {
                                        broadcastWeChat("com.tencent.mm", extractBody(cv));
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.updateWithOnConflict 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.updateWithOnConflict fail: " + t); } catch (Throwable ignored) {}
        }

        // v11 全覆盖：execSQL(String,Object[]) + update(String,ContentValues,String,String[]) 兜底已由 execSQL 覆盖写操作
        // 微信若直接拼 SQL 写消息表，会走 execSQL；探针记录 SQL 以便在 verbose 里定位真实表名
        try {
            XposedHelpers.findAndHookMethod(wcdb, "execSQL",
                    String.class, Object[].class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                String sql = (String) p.args[0];
                                if (sql == null) return;
                                String low = sql.toLowerCase();
                                if (low.contains("insert") || low.contains("update") || low.contains("replace")) {
                                    String table = "";
                                    // 粗略提取 SQL 里的表名（insert/update/replace into xxx）
                                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                            "\\b(?:insert\\s+into|insert\\s+or\\s+replace\\s+into|update|replace\\s+into)\\s+([\"`]?\\w+[\"`]?)",
                                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(low);
                                    if (m.find()) table = m.group(1);
                                    try { XposedBridge.log("[NotifyX][SQL] execSQL: " + table + " :: " + sql); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[NotifyX][WCDB]   ✓ hooked WCDB.execSQL 数据层"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][WCDB]   - hook WCDB.execSQL fail: " + t); } catch (Throwable ignored) {}
        }
    }

    /** 反射 dump WCDB 类所有潜在"写库"方法的真实签名，供 8.0.77 切点精确对齐。 */
    private void dumpWcdbWriteMethods(Class<?> wcdb) {
        try {
            java.lang.reflect.Method[] ms = wcdb.getDeclaredMethods();
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : ms) {
                String n = m.getName();
                if (n.startsWith("insert") || n.startsWith("replace") || n.startsWith("update") || n.equals("execSQL")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(n).append("(");
                    Class<?>[] pt = m.getParameterTypes();
                    for (int i = 0; i < pt.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(pt[i].getName());
                    }
                    sb.append(")");
                    hits.add(sb.toString());
                }
            }
            java.util.Collections.sort(hits);
            try {
                XposedBridge.log("[NotifyX][SIG]   == WCDB 写方法真实签名(" + hits.size() + "个) ==");
                for (String s : hits) XposedBridge.log("[NotifyX][SIG]     " + s);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][SIG]   - dump WCDB 签名失败: " + t); } catch (Throwable ignored) {}
        }
    }

    /** 是否为消息/聊天相关的表或 URI（小写关键词匹配，尽量宽）。 */
    private String formatWeChatMessage(String table, ContentValues cv) {
        if (cv == null) return null;
        if ("message".equalsIgnoreCase(table)) {
            String talker = strValue(cv.get("talker"));
            String content = strValue(cv.get("content"));
            int type = intValue(cv.get("type"));
            int isSend = intValue(cv.get("isSend"));
            String dir = isSend == 1 ? "\u53d1\u51fa" : "\u6536\u5230";
            String kind;
            if (type == 1) kind = "\u6587\u672c";
            else if (type == 3) kind = "\u56fe\u7247";
            else if (type == 34) kind = "\u8bed\u97f3";
            else if (type == 43) kind = "\u89c6\u9891";
            else if (type == 47) kind = "\u8868\u60c5";
            else if (type == 49) kind = "\u94fe\u63a5/\u5361\u7247";
            else if (type == 436207665) kind = "\u7ea2\u5305";
            else if (type == 419430449) kind = "\u8f6c\u8d26";
            else if (type == 10000) kind = "\u7cfb\u7edf\u6d88\u606f";
            else kind = "\u6d88\u606f";
            String body = friendlyWeChatText(content);
            if (body == null || body.isEmpty()) body = "\uff08\u5185\u5bb9\u89c1\u5fae\u4fe1\uff09";
            return (talker.isEmpty() ? "WeChat" : talker) + " / " + dir + kind + ": " + body;
        }
        if ("AppMessage".equalsIgnoreCase(table)) {
            String talker = strValue(cv.get("talker"));
            String title = strValue(cv.get("title"));
            String desc = strValue(cv.get("description"));
            String content = strValue(cv.get("content"));
            String body = desc == null || desc.isEmpty() ? content : desc;
            body = friendlyWeChatText(body);
            if (body == null || body.isEmpty()) body = "\uff08\u516c\u4f17\u53f7/\u5c0f\u7a0b\u5e8f\u6d88\u606f\uff09";
            return (talker.isEmpty() ? "AppMessage" : talker) + " / "
                    + (title == null || title.isEmpty() ? "message" : title) + ": " + body;
        }
        return extractBody(cv);
    }

    private static String friendlyWeChatText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String s = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (s.startsWith("<") && s.contains(">")) {
            s = s.replaceAll("<[^>]+>", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        return s.length() > 1200 ? s.substring(0, 1200) : s;
    }

    private static String strValue(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intValue(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private boolean isMsgTable(String s) {
        if (s == null) return false;
        final String low = s.toLowerCase();
        return low.contains("msg") || low.contains("chat") || low.contains("message")
                || low.contains("conversation") || low.contains("rconversation")
                || low.contains("contact") || low.contains("recent");
    }

    /**
     * 从 ContentValues 里尽量提取消息正文。微信消息落库字段名随版本变化，
     * 枚举常见 key，取到最长可见文本即返回。
     */
    private String extractBody(ContentValues cv) {
        if (cv == null) return null;
        String best = "";
        String[] keys = {
                "content", "msgContent", "contentXml", "newMsg", "text", "body",
                "message", "msg", "description", "digest", "talker", "displayName",
                "msgSvrId", "contentMsg",
                "field_content", "field_talker", "field_status", "username",
                "chatroomName", "createTime", "summary", "title"
        };
        for (String k : keys) {
            try {
                Object v = cv.get(k);
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (s.length() > best.length() && !s.matches("[0-9\\-]+") && s.length() > 2) best = s;
                } else if (v instanceof byte[]) {
                    String s = new String((byte[]) v, java.nio.charset.StandardCharsets.UTF_8);
                    if (s.length() > best.length() && s.indexOf('\0') < 0 && s.length() > 2) best = s;
                }
            } catch (Throwable ignored) {}
        }
        if (best.length() < 2) return null;
        // 内容净化：去控制字符、截断 XML 头尾、限长 1200
        best = best.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (best.length() > 1200) best = best.substring(0, 1200);
        if (best.startsWith("<") && best.contains(">")) {
            int s = best.indexOf('>');
            int e = best.indexOf('<', s);
            if (e > s) best = best.substring(s + 1, e).trim();
        }
        if (best.length() < 2) return null;
        return best;
    }

    /** 兜底：hook 微信通知构建相关的类（若存在），从 Parcelable 通知对象取文本。 */
    private void tryHookGenericNotifySetters(XC_LoadPackage.LoadPackageParam lpparam) {
        final String[] notifyClasses = {
                "com.tencent.mm.plugin.reminder.ui.task.FutureTaskTimingManager",
                "com.tencent.mm.platformtools.NotificationChannel",
                "com.tencent.mm.model.LocalNotifStg",
        };
        for (String cls : notifyClasses) {
            try {
                final Class<?> k = XposedHelpers.findClass(cls, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(k, "buildContent", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object r = p.getResult();
                            if (r != null) {
                                String text = String.valueOf(r);
                                if (text.length() > 2) broadcastWeChat("com.tencent.mm", text);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) { /* skip */ }
        }
    }

    private String pkgOfParam() { return "com.tencent.mm"; }

    /** 通用 hook（企业微信等）：退化到日志即可，具体切点按需扩展。 */
    private void hookGeneric(XC_LoadPackage.LoadPackageParam lpparam, String pkg) {
        try { XposedBridge.log("[NotifyX][INIT] 注入 " + pkg + "（通用占位，切点后续扩展）"); } catch (Throwable ignored) {}
    }

    /** 微信候选方法命中回调——尽力从参数/返回值提取 sender + body，广播给 App。 */
    private void handleWeChatMethod(String cls, String method, XC_MethodHook.MethodHookParam p) {
        try {
            String body = "";
            for (Object a : p.args) {
                if (a == null) continue;
                if (a instanceof CharSequence) { String s = a.toString(); if (s.length() > body.length()) body = s; }
                else if (a instanceof String) { String s = (String) a; if (s.length() > body.length()) body = s; }
            }
            Object r = p.getResult();
            if (r != null && (r instanceof CharSequence)) { String s = r.toString(); if (s.length() > body.length()) body = s; }
            if (body.isEmpty()) return;
            if (body.length() > 1200) body = body.substring(0, 1200);
            broadcastWeChat("com.tencent.mm", body);
        } catch (Throwable ignored) {}
    }

    /** 把微信消息以广播发给 NotifyBridge App（跨进程 IPC）。带基础去重，防刷屏。 */
    public static void broadcastWeChat(String fromPkg, String text) {
        try {
            if (text == null || text.isEmpty()) return;
            // 轻量去重：同文本且 8 秒内已广播过则跳过（多切点/重复写库场景）
            long now = System.currentTimeMillis();
            synchronized (recent) {
                Long last = recent.get(text);
                if (last != null && (now - last) < DEDUP_WINDOW_MS) return;
                recent.put(text, now);
            }
            Context sc = wechatContext;
            if (sc == null) sc = systemContext();
            if (sc == null) return;
            Intent i = new Intent(PKG + ".XP_MSG");
            i.setPackage(PKG);
            i.setComponent(new ComponentName(PKG, PKG + ".NotifyXposedReceiver"));
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("from", fromPkg);
            i.putExtra("body", text);
            i.putExtra("ts", System.currentTimeMillis());
            sc.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    /** 获取系统 Context（钩子运行在目标 App 进程内，用 ActivityThread 的系统上下文发广播）。 */
    public static Context systemContext() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object act = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(act, "getSystemContext");
        } catch (Throwable t) { return null; }
    }
}
