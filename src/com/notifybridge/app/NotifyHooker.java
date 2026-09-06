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
    /** 群消息解析诊断：最多打印前 20 条原始字段，帮助定位群名/发言人错标问题。 */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 群消息发送者 username 前缀：兼容 wxid_、纯数字(手机号)、字母组合、@chatroom 等。 */
    private static final java.util.regex.Pattern GROUP_SENDER =
            java.util.regex.Pattern.compile("^([A-Za-z0-9_@.\\-]+)\\s*[:：\\r\\n]");

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
                                        logRawSkip(parsed);
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
                                        logRawSkip(parsed);
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
                                if (isMessageTable(table) && cv != null) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        logRawSkip(parsed);
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
                                if (isMessageTable(table) && cv != null) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        logRawSkip(parsed);
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
                                if (isMessageTable(table) && cv != null && cv.size() > 0) {
                                    String parsed = extractBody(cv);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        logRawSkip(parsed);
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
        // Do NOT install on lpparam.classLoader here: under MIUI/Tinker this loader can
        // point to ContentCatcher or the Tinker patch shell, and once tryHookWeChatWcdb
        // succeeds there it sets wcdbHooked=true, which would prevent the real WeChat
        // Application ClassLoader from ever being hooked. Always wait for WeChat's real
        // Application and install with its final merged ClassLoader (mirrors xposed-logger).
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
        tryHookWcdbStatement(classLoader);

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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("insertWithOnConflict", table, cv);
                                // 只关心消息/聊天会话表，过滤掉其他业务表
                                if (!isMessageTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv, p.thisObject);
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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("insert", table, cv);
                                if (!isMessageTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv, p.thisObject);
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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("update", table, cv);
                                if (!isMessageTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv, p.thisObject);
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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("insertOrThrow", table, cv);
                                if (!isMessageTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv, p.thisObject);
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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("replace", table, cv);
                                if (!isMessageTable(table)) return;
                                String parsed = formatWeChatMessage(table, cv, p.thisObject);
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
                                scheduleSelfTest(p.thisObject);
                                wcdbProbe("replaceOrThrow", table, cv);
                                if (isMessageTable(table)) {
                                    String parsed = formatWeChatMessage(table, cv, p.thisObject);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        broadcastWeChat("com.tencent.mm", parsed);
                                    }
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
                                    scheduleSelfTest(p.thisObject);
                                    wcdbProbe("updateWithOnConflict", table, cv);
                                    if (isMessageTable(table)) {
                                        String parsed = formatWeChatMessage(table, cv, p.thisObject);
                                        if (parsed != null && !parsed.isEmpty()) {
                                            broadcastWeChat("com.tencent.mm", parsed);
                                        }
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

        // 诊断自检：缓存首次可用的 WCDB 连接，用于离线验证群名/格式，不依赖新消息落库。
        try {
            XposedHelpers.findAndHookMethod(wcdb, "rawQuery",
                    String.class, String[].class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            scheduleSelfTest(p.thisObject);
                        }
                    });
            try { XposedBridge.log("[NotifyX][SELFTEST]   hooked WCDB.rawQuery 用于自检连接缓存"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][SELFTEST]   - hook WCDB.rawQuery fail: " + t); } catch (Throwable ignored) {}
        }
    }

    private static volatile boolean selfTestScheduled = false;
    private static volatile boolean selfTestDone = false;
    private static void selfLog(String msg) {
        try { XposedBridge.log("[NotifyX][SELFTEST] " + msg); } catch (Throwable ignored) {}
    }

    /** 首次拿到微信 WCDB 连接后，延时抓一次自检样例（仅一次，失败可重试）。 */
    private static void scheduleSelfTest(final Object db) {
        if (db == null || selfTestDone) return;
        synchronized (NotifyHooker.class) {
            if (selfTestScheduled) return;
            selfTestScheduled = true;
        }
        // 独立线程执行，避免阻塞微信自己的数据库写线程。
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(1200L);
                } catch (Throwable ignore) {}
                try {
                    runSelfTest(db);
                } catch (Throwable t2) {
                    selfLog("schedule throw=" + t2);
                    selfTestScheduled = false;
                }
            }
        }, "notify-selftest");
        try {
            t.setDaemon(true);
            t.start();
        } catch (Throwable t2) {
            selfLog("schedule start throw=" + t2);
            selfTestScheduled = false;
        }
    }

    /** 消息格式化路径上的一次性自检：只要真实处理过一条消息就同步输出样例，最可靠。 */
    private static void maybeSelfTest(final Object db) {
        if (db == null || selfTestDone) return;
        synchronized (NotifyHooker.class) {
            if (selfTestDone) return;
            selfTestDone = true;
        }
        try {
            runSelfTest(db);
        } catch (Throwable t) {
            selfLog("maybeSelfTest err=" + t);
            selfTestDone = false;
        }
    }

    /** 打印离线自检样例：群文本/个人文本/图片占位，并附真实群名、联系人解析结果。 */
    private static void runSelfTest(final Object db) {
        try {
            ChatNameResolver.rememberDb(db);

            String groupId = firstRowString(db, "chatroom", "chatroomname",
                    "chatroomname LIKE '%@chatroom' LIMIT 1");
            String contactId = firstRowString(db, "rcontact", "username",
                    "username LIKE 'wxid_%' LIMIT 1");
            String groupName = groupId == null || groupId.isEmpty()
                    ? "" : ChatNameResolver.resolve(db, groupId);
            String contactName = contactId == null || contactId.isEmpty()
                    ? "" : ChatNameResolver.resolveContact(db, contactId, contactId);

            String g = groupId == null ? "(no group)" : groupId;
            String c = contactId == null ? "(no contact)" : contactId;
            selfLog("group=" + g + " groupName=" + groupName
                    + " contact=" + c + " contactName=" + contactName);

            selfLog("群文本 => 【群】" + groupName + " - " + contactName + "：这是一条自检文本");
            selfLog("个人文本 => 【个人】" + contactName + "：这是一条自检文本");
            selfLog("群图片 => 【群】" + groupName + " - " + contactName + "：收到图片");
            selfTestDone = true;
        } catch (Throwable t) {
            selfLog("runSelfTest fail err=" + t);
            selfTestScheduled = false;
        }
    }

    /** 从 WCDB 连接跑一条只读 SQL，返回第一行第一列字符串；失败返回 ""。 */
    private static String firstRowString(Object db, String table, String col, String where) {
        String sql = "SELECT " + col + " FROM " + table + " WHERE " + where;
        try {
            Object cursor = null;
            try {
                cursor = XposedHelpers.callMethod(db, "rawQuery",
                        sql, new String[0]);
                if (cursor != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(cursor, "moveToFirst"))) {
                    Object v = XposedHelpers.callMethod(cursor, "getString", 0);
                    return v == null ? "" : String.valueOf(v);
                }
            } finally {
                if (cursor != null) {
                    try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            selfLog("firstRowString fail sql=[" + sql + "] err=" + t);
        }
        return "";
    }

    /** 反射 dump WCDB 类所有潜在"写库"方法的真实签名，供 8.0.77 切点精确对齐。 */
    /**
     * ?? 8.0.77 ????????? SQLiteDatabase.insertWithOnConflict?
     * ???? SQLiteStatement ??? executeInsert / executeUpdateDelete?
     * ??????????? SQL ?? + bindArgs ?? ContentValues?
     */
    private void tryHookWcdbStatement(ClassLoader classLoader) {
        try {
            Class<?> stmt = XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteStatement", classLoader);
            Class<?> signal = XposedHelpers.findClass("com.tencent.wcdb.support.CancellationSignal", classLoader);

            XC_MethodHook handler = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        String sql = invokeString(p.thisObject, "getSql");
                        if (sql == null) return;
                        String table = sqlInsertTable(sql);
                        if (table == null) return;
                        if (!"message".equalsIgnoreCase(table) && !"AppMessage".equalsIgnoreCase(table)) return;

                        Object[] args = invokeObjectArray(p.thisObject, "getBindArgs");
                        ContentValues cv = contentValuesFromInsertSql(sql, args);
                        String keys = cv == null ? "-" : String.join(",", cv.keySet());
                        XposedBridge.log("[NotifyX][STMT] " + p.method.getName()
                                + " table=" + table + " keys=[" + keys + "] sql=" + normalizeSql(sql));
                    } catch (Throwable t) {
                        try { XposedBridge.log("[NotifyX][STMT] process fail: " + t); } catch (Throwable ignored) {}
                    }
                }
            };

            hookStatementMethod(stmt, "executeInsert", handler);
            hookStatementMethod(stmt, "executeInsert", signal, handler);
            hookStatementMethod(stmt, "executeUpdateDelete", handler);
            hookStatementMethod(stmt, "executeUpdateDelete", signal, handler);
            try { XposedBridge.log("[NotifyX][STMT] + hooked SQLiteStatement.executeInsert/executeUpdateDelete"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][STMT] - hook SQLiteStatement fail: " + t); } catch (Throwable ignored) {}
        }
    }

    private void hookStatementMethod(Class<?> type, String name, XC_MethodHook hook) {
        try {
            XposedHelpers.findAndHookMethod(type, name, hook);
            try { XposedBridge.log("[NotifyX][STMT]   ? hooked " + name + "()"); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void hookStatementMethod(Class<?> type, String name, Class<?> signal, XC_MethodHook hook) {
        try {
            XposedHelpers.findAndHookMethod(type, name, signal, hook);
            try { XposedBridge.log("[NotifyX][STMT]   ? hooked " + name + "(CancellationSignal)"); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private String invokeString(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            Object value = m.invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][STMT] invoke " + method + " fail: " + t); } catch (Throwable ignored) {}
            return null;
        }
    }

    private Object[] invokeObjectArray(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            Object value = m.invoke(target);
            return value instanceof Object[] ? (Object[]) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String sqlInsertTable(String sql) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\binsert\\s+(?:or\\s+[a-z]+\\s+)?into\\s+[\"'`\\[]?([a-zA-Z0-9_$]+)[\"'`\\]]?",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
            return m.find() ? m.group(1) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ContentValues contentValuesFromInsertSql(String sql, Object[] args) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\binsert\\s+(?:or\\s+[a-z]+\\s+)?into\\s+[\"'`\\[]?[a-zA-Z0-9_$]+[\"'`\\]]?\\s*\\((.*?)\\)\\s*values\\s*\\(",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(sql);
            if (!m.find()) return null;
            String[] cols = m.group(1).split(",");
            if (args == null) return null;
            ContentValues cv = new ContentValues();
            int count = Math.min(cols.length, args.length);
            for (int i = 0; i < count; i++) {
                String key = unquoteSqlName(cols[i].trim());
                if (key.isEmpty()) continue;
                putContentValue(cv, key, args[i]);
            }
            return cv;
        } catch (Throwable t) {
            try { XposedBridge.log("[NotifyX][STMT] rebuild ContentValues fail: " + t); } catch (Throwable ignored) {}
            return null;
        }
    }

    private String unquoteSqlName(String value) {
        String s = value == null ? "" : value.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private void putContentValue(ContentValues cv, String key, Object value) {
        if (value == null) cv.putNull(key);
        else if (value instanceof String) cv.put(key, (String) value);
        else if (value instanceof Integer) cv.put(key, (Integer) value);
        else if (value instanceof Long) cv.put(key, (Long) value);
        else if (value instanceof Boolean) cv.put(key, (Boolean) value);
        else if (value instanceof Byte) cv.put(key, (Byte) value);
        else if (value instanceof Double) cv.put(key, (Double) value);
        else if (value instanceof Float) cv.put(key, (Float) value);
        else if (value instanceof Short) cv.put(key, (Short) value);
        else if (value instanceof byte[]) cv.put(key, (byte[]) value);
        else cv.put(key, String.valueOf(value));
    }

    private String normalizeSql(String sql) {
        String s = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

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
        return formatWeChatMessage(table, cv, null);
    }

    private String formatWeChatMessage(String table, ContentValues cv, Object sqliteDb) {
        maybeSelfTest(sqliteDb);
        if (cv == null) return null;
        if ("message".equalsIgnoreCase(table)) {
            String talker = strValue(cv.get("talker"));
            int type = intValue(cv.get("type"));
            String content = strValue(cv.get("content"));
            if (type == 0 && content.isEmpty()) return null;
            boolean isGroup = talker.endsWith("@chatroom");
            String body;
            switch (type) {
                case 3:  body = "\u6536\u5230\u56fe\u7247"; break;
                case 34: body = "\u6536\u5230\u8bed\u97f3"; break;
                case 43: body = "\u6536\u5230\u89c6\u9891"; break;
                case 47: body = "\u6536\u5230\u8868\u60c5"; break;
                case 49: body = "\u6536\u5230\u94fe\u63a5/\u5361\u7247"; break;
                case 436207665: body = "\u6536\u5230\u7ea2\u5305"; break;
                case 419430449: body = "\u6536\u5230\u8f6c\u8d26"; break;
                default:
                    body = cleanGroupContent(content);
                    if (body == null || body.isEmpty()) return null;
            }
            String talkerName = ChatNameResolver.resolve(sqliteDb, talker);
            String sender;
            if (isGroup) {
                String wxid = extractSenderWxid(content);
                sender = (wxid == null || wxid.isEmpty())
                        ? "" : cleanName(ChatNameResolver.resolveContact(sqliteDb, wxid, wxid));
            } else {
                sender = cleanName(talkerName);
            }
            if (isGroup && DIAG_COUNT.get() < 20) {
                DIAG_COUNT.incrementAndGet();
                String wxid = extractSenderWxid(content);
                try {
                    XposedBridge.log("[NotifyX][DIAG] type=" + type
                            + " talker=" + shortStr(talker)
                            + " content=[" + shortStr(content)
                            + "] wxid=[" + wxid
                            + "] sender=[" + sender + "]");
                } catch (Throwable ignored) {}
            }
            StringBuilder prefix = new StringBuilder();
            if (isGroup && talkerName != null && !talkerName.isEmpty()
                    && !talker.equals(talkerName)) {
                prefix.append("\u3010\u7fa4\u3011").append(talkerName);
                if (!sender.isEmpty() && !sender.equals(talkerName)) {
                    prefix.append(" - ").append(sender);
                }
            } else if (!sender.isEmpty()) {
                prefix.append("\u3010\u4e2a\u4eba\u3011").append(sender);
            } else {
                prefix.append("\u5fae\u4fe1");
            }
            return prefix.toString() + "\uff1a" + body;
        }
        if ("AppMessage".equalsIgnoreCase(table)) {
            String talker = strValue(cv.get("talker"));
            if (talker.isEmpty()) talker = "\u5fae\u4fe1\u516c\u4f17\u53f7/\u5c0f\u7a0b\u5e8f"; // 微信公众号/小程序
            String display = ChatNameResolver.resolve(sqliteDb, talker);
            String title = cleanText(strValue(cv.get("title")));
            String desc = cleanText(strValue(cv.get("description")));
            String content = cleanText(strValue(cv.get("content")));
            String body = title == null ? null : title;
            if (body == null || body.isEmpty()) body = desc;
            if (body == null || body.isEmpty()) body = content;
            if (body == null || body.isEmpty()) body = "\uff08\u65e0\u5185\u5bb9\uff09";
            return display + ": " + body;
        }
        String ex = cleanText(extractBody(cv));
        return ex == null ? null : ex;
    }

    /**
     * 群消息 content 形如 "wxid_xxx: 内容" 或 "wxid_xxx\n内容"，
     * 发送者前缀也可能是手机号/字母组合（a553965160: 内容），
     * 统一把 username 前缀剥离掉，只留下正文。
     */
    private static String cleanGroupContent(String content) {
        if (content == null) return null;
        String c = content;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Za-z0-9_@.\\-]+)\\s*[:：\\r\\n]+\\s*").matcher(c);
        if (m.find()) {
            c = c.substring(m.end());
        }
        return cleanText(c);
    }

    private static String extractSenderWxid(String content) {
        if (content == null) return "";
        java.util.regex.Matcher m = GROUP_SENDER.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    /** 只保留文字、数字与标点，去掉 XML/HTML 标签、实体、imgaskey 等格式代码，并压缩空白，限长 300。 */
    private static String cleanText(String text) {
        if (text == null) return null;
        String s = text;
        s = s.replaceAll("<!\\[CDATA\\[|\\]\\]>", " ");
        s = s.replaceAll("<[^>]*>", " ");
        // 常见微信格式键值对：xxx="..." 或 xxx='...'
        s = s.replaceAll("(?i)\\b[a-z0-9_]{2,}\\s*=\\s*[\"'][^\"']*[\"']", " ");
        // 常见图片/文件格式代码及 URL
        s = s.replaceAll("(?i)\\b(?:imgaskey|ciphertext|cdnurl|md5|aeskey|encryptver|format|length|filenamesvr|haha|aeskey|mmsight|rawKey)\\s*[:=]?\\s*[^\\s<>\"'\uFF0C\uFF1B\uFF0E]*", " ");
        s = s.replaceAll("(?i)\\bhttps?://\\S+", " ");
        s = s.replaceAll("(?i)\\bwxid_[a-z0-9]+\\s*[:\\uFF1A]?", " ");
        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");
        // 只保留字母、数字、标点、空白，去掉 emoji 及其他符号
        s = s.replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Z}\\s]", " ");
        s = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    /** 姓名净化：去掉昵称里常见的隐形/装饰字符；净化后为空则保留原样，避免丢失姓名。 */
    private static String cleanName(String name) {
        if (name == null || name.isEmpty()) return name;
        String c = cleanText(name);
        if (c == null || c.isEmpty()) return name;
        String d = c.replaceAll("[\\p{M}\\u00AD\\u2060\\u2061\\u2062\\u2063\\u2064\\s]+", "");
        return d.isEmpty() ? c : d;
    }

    private static void logRawSkip(String parsed) {
        try {
            XposedBridge.log("[NotifyX][RAW] no-chat-name skip: " + parsed);
        } catch (Throwable ignored) {}
    }

    private static String strValue(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String shortStr(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
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

    /** 只认可真正的消息表（微信 8.x message 与 AppMessage），用于过滤非消息表的杂广播。 */
    private boolean isMessageTable(String s) {
        if (s == null) return false;
        return "message".equalsIgnoreCase(s) || "AppMessage".equalsIgnoreCase(s);
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
        // 内容净化：去标签、HTML 实体、控制字符、限长 300
        best = cleanText(best);
        return best == null || best.length() < 2 ? null : best;
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
                                if (text.length() > 2) {
                                    // 无 sqliteDb，无法解析群名/人名，仅留诊断日志，避免无前缀脏条目。
                                    try { XposedBridge.log("[NotifyX][FALLBACK] buildContent (no chat name): " + text); } catch (Throwable ignored) {}
                                }
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
            // 无 sqliteDb，无法解析群名/人名，仅留诊断日志，避免无前缀脏条目。
            try { XposedBridge.log("[NotifyX][FALLBACK] " + cls + "." + method + " (no chat name): " + body); } catch (Throwable ignored) {}
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
            try { XposedBridge.log("[NotifyX][BROADCAST] " + text); } catch (Throwable ignored) {}
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
