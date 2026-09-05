package com.notifybridge.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 AI 分析引擎（v2 第一版：规则驱动，可插拔）。
 * 后续可替换为真实大模型（服务器中转或 App 直连 API），只需保持方法签名一致。
 *
 * 数据源：由外部传入的"通知文本行"（来自 LogStore 捕获日志，格式如：
 *   [14:32:15] 微信 (com.tencent.mm) | 张总 | 项目进度如何
 *   [[14:30:00]] 诊断消息
 *   >> RAW [14:29:00] com.tencent.mm
 * ）
 */
public final class AiAnalyzerCore {

    /** 一条通知条目（解析后）。 */
    public static class Notice {
        public String time = "";
        public String app = "";
        public String pkg = "";
        public String title = "";
        public String body = "";
        public boolean isDiag = false;      // 是诊断日志还是真实通知
        public boolean isRaw = false;       // 是 RAW 原始痕迹
        public String category = "other";   // work / todo / urgent / other
        public String fullText = "";
    }

    /** 分析结果汇总。 */
    public static class Summary {
        public int totalNotices = 0;      // 今日通知总数
        public int workCount = 0;         // 工作相关
        public int todoCount = 0;         // 待办
        public int urgentCount = 0;       // 紧急
        public int meetingCount = 0;      // 会议提醒
        public String digest = "";        // 文本摘要
        public List<String> todos = new ArrayList<>();   // 待办清单
        public List<String> meetings = new ArrayList<>(); // 会议提醒
        public List<String> workRecords = new ArrayList<>(); // 工作记录
        public List<String> suggestions = new ArrayList<>(); // 建议
    }

    private AiAnalyzerCore() {}

    // 关键词库（可扩展；后续可从 Config 读取用户自定义关键词）
    private static final String[] KW_WORK = {"工作","项目","汇报","报告","需求","评审","方案","预算","会议记录","周报","邮件","审批","合同","技术","客户","版本","发版","测试","代码","上线","部署"};
    private static final String[] KW_TODO = {"待办","待处理","请","麻烦","尽快","回复","跟进","确认","提交","安排","预约","记得","别忘了","优先"};
    private static final String[] KW_URGENT = {"紧急","加急","立即","马上","今天必须","特急","now","urgent","尽快处理","立刻"};
    private static final String[] KW_MEETING = {"会议","开会","参会","邀请","月度会","例会","评审会","沙龙","交流会","讨论会","15:00","14:00","10:00","09:00","16:30","17:00"};

    /** 解析一行日志文本为 Notice 对象。 */
    public static Notice parseLine(String line) {
        Notice n = new Notice();
        n.fullText = line;
        String s = line == null ? "" : line.trim();
        if (s.isEmpty()) { n.isRaw = true; return n; }

        // 诊断日志 [[HH:mm:ss]] msg
        if (s.startsWith("[[")) {
            n.isDiag = true;
            int e = s.indexOf("]]");
            if (e > 0) {
                n.time = s.substring(2, Math.min(e, s.indexOf(']')));
                n.body = s.substring(e + 2).trim();
            } else {
                n.body = s.substring(2).trim();
            }
            return n;
        }
        // RAW 原始痕迹
        if (s.startsWith(">> RAW")) {
            n.isRaw = true;
            n.body = s;
            return n;
        }
        // 通知 [HH:mm:ss] 应用 (pkg) | 标题 | 正文
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0) n.time = s.substring(1, close);
            String rest = s.substring(close + 1).trim();
            // 应用 (pkg) | 标题 | 正文
            String[] parts = rest.split("\\|");
            if (parts.length > 0) {
                String appPart = parts[0].trim();
                // 提取 pkg
                int lp = appPart.lastIndexOf('(');
                int rp = appPart.lastIndexOf(')');
                if (lp > 0 && rp > lp) {
                    n.app = appPart.substring(0, lp).trim();
                    n.pkg = appPart.substring(lp + 1, rp).trim();
                } else {
                    n.app = appPart;
                }
            }
            if (parts.length > 1) n.title = parts[1].trim();
            if (parts.length > 2) n.body = parts[2].trim();
            return n;
        }
        n.app = s;
        return n;
    }

    private static boolean containsAny(String text, String[] kws) {
        if (text == null) return false;
        String t = text.toLowerCase();
        for (String k : kws) if (t.contains(k.toLowerCase())) return true;
        return false;
    }

    /** 分析一组通知文本行，返回分类统计 + 摘要 + 待办 + 会议 + 建议。 */
    public static Summary analyze(List<String> lines) {
        Summary s = new Summary();
        if (lines == null) return s;

        for (String line : lines) {
            Notice n = parseLine(line);
            if (n.isRaw) continue;               // 原始痕迹不参与统计
            if (n.isDiag) {
                // 诊断日志：含成功/失败的也能计，但默认不参与"通知"统计
                if (containsAny(n.body, KW_URGENT)) s.urgentCount++;
                continue;
            }
            s.totalNotices++;
            String text = (n.title + " " + n.body).toLowerCase();

            if (containsAny(text, KW_URGENT)) { n.category = "urgent"; s.urgentCount++; }
            if (containsAny(text, KW_WORK)) { s.workCount++; }
            if (containsAny(text, KW_TODO)) {
                if (n.category.equals("other")) n.category = "todo";
                s.todoCount++;
                s.todos.add(todoText(n));
            }
            if (containsAny(text, KW_MEETING)) {
                s.meetingCount++;
                s.meetings.add((n.time.isEmpty() ? "" : n.time + " ") + (n.title.isEmpty() ? n.body : n.title));
            }
            // 工作记录：有时间的通知
            if (!n.time.isEmpty() && !n.title.isEmpty()) {
                s.workRecords.add(n.time + " - " + n.title);
            }
        }
        // 摘要
        s.digest = buildDigest(s);
        // 建议
        buildSuggestions(s);
        return s;
    }

    private static String todoText(Notice n) {
        String t = n.title.isEmpty() ? n.body : n.title;
        if (t.isEmpty()) t = n.app;
        if (n.category.equals("urgent")) t = "[🔴紧急] " + t;
        if (!n.time.isEmpty()) t = n.time + " " + t;
        return t;
    }

    private static String buildDigest(Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("今日共收到 ").append(s.totalNotices).append(" 条通知");
        if (s.workCount > 0) sb.append("，其中工作相关 ").append(s.workCount).append(" 条");
        if (s.todoCount > 0) sb.append("，识别出 ").append(s.todoCount).append(" 项待办");
        if (s.meetingCount > 0) sb.append("，").append(s.meetingCount).append(" 条会议提醒");
        sb.append("。");
        return sb.toString();
    }

    private static void buildSuggestions(Summary s) {
        if (s.urgentCount > 0) {
            s.suggestions.add("检测到 " + s.urgentCount + " 条紧急信息，建议优先处理");
        }
        if (s.meetingCount > 0) {
            s.suggestions.add("今日有 " + s.meetingCount + " 条会议相关通知，建议提前做好准备");
        }
        if (s.todoCount > 0) {
            s.suggestions.add("你有 " + s.todoCount + " 项待办，建议按时间先后处理");
        }
        if (s.suggestions.isEmpty()) {
            s.suggestions.add("今日暂无高频待办，保持关注微信/邮件消息即可");
        }
    }

    /** 生成本地纪要 Markdown（用于纪要看板页）。 */
    public static String buildReport(String dateStr, Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 📋 ").append(dateStr).append(" 工作纪要\n\n");
        sb.append("## 📌 今日待办事项\n");
        if (s.todos.isEmpty()) {
            sb.append("- 暂无识别到待办事项\n");
        } else {
            for (String t : s.todos) sb.append("- [ ] ").append(t).append("\n");
        }
        sb.append("\n## 📝 工作记录\n");
        if (s.workRecords.isEmpty()) {
            sb.append("1. 今日无有效工作通知记录\n");
        } else {
            int i = 1;
            for (String r : s.workRecords) sb.append(i++).append(". ").append(r).append("\n");
        }
        sb.append("\n## 🔔 会议与提醒\n");
        if (s.meetings.isEmpty()) {
            sb.append("- 今日无会议提醒\n");
        } else {
            for (String m : s.meetings) sb.append("- ").append(m).append("\n");
        }
        sb.append("\n## 💡 AI智能建议\n");
        for (String g : s.suggestions) sb.append("- ").append(g).append("\n");
        return sb.toString();
    }

    /** 从 LogStore 读取日志并分析（便捷入口）。 */
    public static Summary analyzeFromLog(Context ctx, int maxLines) {
        List<String> lines = LogStore.read(ctx, maxLines);
        return analyze(lines);
    }
}
