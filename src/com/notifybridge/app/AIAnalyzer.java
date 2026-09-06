package com.notifybridge.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 可插拔 AI 接入层（v2）。
 * 根据 Config.KEY_AI_MODE 选择：
 *   - "direct"：直接调 OpenAI 兼容 /v1/chat/completions（DeepSeek/OpenRouter/Ollama/本地等）
 *   - "relay" ：调服务器/Hermes 中转接口（POST JSON：{"platform":"html","data":"./file"}）
 *   - 未配置 / 失败 ：不降级本地规则，返回明确错误提示。
 *
 * 结构：analyze() / buildReport() 只接受真实 AI 接口返回的结果（direct / relay）。
 */
public final class AIAnalyzer {

    // 预置默认 AI（OpenAI 兼容直连）
    public static final String PRESET_BASE_URL = "http://your-ai-endpoint-host:port/v1/chat/completions";
    public static final String PRESET_MODEL = "DeepSeek-V4-Flash-0731-w8a8";
    public static final String PRESET_API_KEY = "";

    // 默认通知沉淀提示词（用户可在 AI 配置里修改，修改后存 Config.KEY_AI_PROMPT）
    public static final String DEFAULT_PROMPT =
        "# 角色\n" +
        "你是一名专业的信息整理与沉淀助手。你的任务是基于用户手机收到的**原始通知原始信息**，生成一份高信息密度、结构化、便于日后检索与回顾的“通知沉淀”文档。你善于筛选重点、归类去重、保留关键数据，并以简洁的条目化语言呈现。\n\n" +
        "# 输入\n" +
        "用户会提供一段时间（通常是一天）内手机收到的各类通知原文或摘要，包括但不限于：工作沟通（微信/钉钉/企业微信）、日程提醒、系统告警、智能家居提醒、招聘猎头邀约、消费支付、物流包裹、应用更新、群聊消息等。\n\n" +
        "# 输出格式（严格遵守）\n" +
        "生成一份 Markdown 文档，结构顺序固定如下（从前往后依次是：摘要 → 待办 → 建议 → 分项统计 → 分类明细）：\n" +
        "## 标题\n" +
        "格式固定为：`# YYYY-MM-DD 通知沉淀`（日期为通知所属的当天）\n" +
        "## 今日智能摘要\n" +
        "用 3-6 条无序列表概括当天最重要的信息与结论，覆盖重点事件、关键进展与关注点，放在整个文档最前面。\n" +
        "## 今日待办事项\n" +
        "提炼当天需要行动的待办事项，每条为可执行的动作/事项，格式：`- **<事项>**：<动作/截止/细节>`。无可执行待办时写一句\"今日暂无待办事项\"。\n" +
        "## AI 智能建议\n" +
        "给出需要特别关注、提醒或有行动价值的建议，每条用 `- ⚠️ <建议>`。\n" +
        "## 分项统计\n" +
        "给出当天各类通知的数量统计（数字来自输入通知，如实统计）：\n" +
        "- 工作 N 条\n" +
        "- 待办 N 条\n" +
        "- 紧急 N 条\n" +
        "- 会议 N 条\n" +
        "- 其他 N 条\n" +
        "## 分类明细\n" +
        "在分项统计之后再按主题归类当天通知明细，常见板块及命名建议（可按实际内容增删）：\n" +
        "- `## 工作 / <公司> / <项目> / <具体方向>`\n" +
        "- `## <某项目>（开发中/进行中）`\n" +
        "- `## 家庭 / 社区`\n" +
        "- `## 系统/运维`\n" +
        "- `## 家庭 / 安防 / 智能家居`\n" +
        "- `## 招聘/猎头 (可能关注)`\n" +
        "- `## 消费/物流`\n" +
        "- `## 其他`\n" +
        "板块顺序可根据重要性或通知量调整，工作类优先靠前。\n" +
        "每个板块内用无序列表，条目格式为：\n" +
        "`- **<关键对象/人物/项目名>**：<动作或细节>，补充信息`\n\n" +
        "# 去重要求（铁律，必须严格执行）\n" +
        "1. **同一件事、同一个群、同一项目的多条重复通知，必须合并为一条**。例如同一群消息或同一提醒出现 3 次（哪怕时间不同），只保留信息最全的一条，并在条目里注明\"收到 N 条\"（如 `飞书 OPG群 扩容讨论（收到3条）`）。\n" +
        "2. **绝对禁止把同样的内容重复列多条**。如果最终板块里出现内容几乎相同的两条，视为失败。\n" +
        "3. **待办事项 ≠ 原始通知行**。待办必须是提炼后的**动作/事项**（如\"回复张总邮件\"、\"下周三14:00参加评审会\"），**禁止**把\"`20:41:12 某群名`\"、\"`飞书`\"这种只有时间戳+群名/App名的原始行原样列为待办。无法提炼成动作的纯消息提示不要放进待办。\n\n" +
        "# 排版与标记规则\n" +
        "1. **加粗** 突出一段信息中的关键实体：人名、公司名、项目名、App/服务名、会议名、金额、地点等。\n" +
        "2. 用 `⚠️` 标记需要用户**特别关注**或**有警告性质**的事项（如磁盘告警、门锁未关、升级计划、催缴等）。放在该条最前面或紧随加粗项。\n" +
        "3. 尽量保留原始通知中的**具体数据**：时间（含日期/星期）、金额、地点、人名、联系方式、版本号、百分比等，不臆测、不编造。\n" +
        "4. 信息要**精简去重**：同一件事的多条通知合并为一条，去掉寒暄、营销话术、无关闲聊；群聊只保留值得关注的结论或事件。\n" +
        "5. 语言：中文，条理清晰，口语化但专业，不啰嗦。\n" +
        "6. 对于不确定、缺失或模棱两可的信息，如实保留原始表述，不作无依据的推断。\n\n" +
        "# 处理原则\n" +
        "- **只整理，不编造**：所有内容必须来自用户提供的通知，不添加通知之外的信息。\n" +
        "- **标注关注项**：涉及风险、DDL、需要行动或决策的内容，用 ⚠️ 或说明文字突出。\n" +
        "- **可检索**：命名、时间、地点等尽量规范化，方便日后搜索和回顾。\n" +
        "- **保密意识**：如通知含敏感信息（密码、验证码、银行卡号等），只做概要性记录，不原样输出敏感值。\n" +
        "- 若某条通知与已有条目重复，合并而非重复列出。\n\n" +
        "# 开场动作\n" +
        "收到原始通知后，先简要确认你已读取当日通知内容，然后直接生成符合上述格式的通知沉淀文档，不要输出多余解释。";

    public static class AIResult {
        public boolean ok = false;
        public String text = "";
        public String error = "";
    }

    private AIAnalyzer() {}

    /** 是否为 OpenAI 兼容直连模式。 */
    public static boolean isDirect(Context ctx) {
        return "direct".equals(Config.get(ctx, Config.KEY_AI_MODE, "direct"));
    }

    /** 是否真实AI模式（direct 或 relay），且已配置（含预置默认）。 */
    public static boolean isRealAiConfigured(Context ctx) {
        String mode = Config.get(ctx, Config.KEY_AI_MODE, "direct");
        if ("direct".equals(mode)) {
            String base = Config.get(ctx, Config.KEY_AI_BASE_URL, "").trim();
            String model = Config.get(ctx, Config.KEY_AI_MODEL, "").trim();
            return !(base.isEmpty() && model.isEmpty())  // 用户有配置
                    || !PRESET_MODEL.isEmpty();          // 或使用预置默认
        }
        if ("relay".equals(mode)) {
            return !Config.get(ctx, Config.KEY_AI_RELAY_URL, "").trim().isEmpty();
        }
        return false;
    }

    /** 分析通知（只使用真实 AI；失败或未配置返回错误提示，不降级本地规则）。返回可展示摘要 + 分类。 */
    public static AiAnalyzerCore.Summary analyze(Context ctx, List<String> lines) {
        AIResult r = tryRealAi(ctx, lines);
        AiAnalyzerCore.Summary s = AiAnalyzerCore.analyze(lines);   // 仅统计，不做摘要
        if (r.ok && r.text != null && !r.text.trim().isEmpty()) {
            s.digest = r.text.trim();
        } else {
            s.digest = "⚠️ 未配置或 AI 生成失败：" + (r.error == null ? "" : r.error)
                    + "\n请先在 AI 配置中填写可用的接口后重新生成（本地摘要已关闭）。";
        }
        return s;
    }

    /** 生成纪要（只使用真实 AI；失败或未配置返回错误提示，不降级本地规则）。 */
    public static String buildReport(Context ctx, String dateStr, List<String> lines) {
        AIResult r = tryRealAi(ctx, lines);
        if (r.ok && r.text != null && !r.text.trim().isEmpty()) {
            LogStore.diag(ctx, "✅ AI 纪要生成成功 (" + r.text.length() + " 字符)，保存 AI 内容");
            return r.text.trim();
        }
        String err = r.error == null ? "" : r.error;
        LogStore.diag(ctx, "❌ AI 纪要生成失败（本地摘要已关闭，不降级）：" + err);
        return "# " + dateStr + " 通知沉淀\n\n"
                + "> ⚠️ 未配置或 AI 接口调用失败，无法生成纪要（本地摘要已关闭）。\n"
                + "> 错误：" + err + "\n\n"
                + "请检查 AI 接口配置后点击「🤖 手动生成今日纪要」重试。";
    }

    private static AIResult tryRealAi(Context ctx, List<String> lines) {
        String mode = Config.get(ctx, Config.KEY_AI_MODE, "direct");
        if ("relay".equals(mode)) return callRelay(ctx, lines);
        if ("direct".equals(mode)) return callOpenAi(ctx, lines);
        return new AIResult();
    }

    /** 测试 AI 连接（用一条假数据请求，验证配置可用）。 */
    public static AIResult testConnection(Context ctx) {
        java.util.List<String> dummy = new java.util.ArrayList<>();
        dummy.add("[12:00] 测试 | 这是一条 AI 连接测试通知，请简短回复OK");
        return tryRealAi(ctx, dummy);
    }

    /** 获取当前提示词（用户自定义 或 默认）。供 AI 配置页预填充显示。 */
    public static String getPrompt(Context ctx) {
        String p = Config.get(ctx, Config.KEY_AI_PROMPT, "");
        return (p == null || p.trim().isEmpty()) ? DEFAULT_PROMPT : p;
    }

    /** 保存提示词。 */
    public static void savePrompt(Context ctx, String prompt) {
        Config.put(ctx, Config.KEY_AI_PROMPT, prompt == null ? "" : prompt.trim());
    }

    private static String buildPrompt(Context ctx, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        // 用户自定义提示词（含"# 角色/输入/输出格式"等），缺省用默认
        String prompt = getPrompt(ctx);
        sb.append(prompt).append("\n\n");
        sb.append("以下是【今日（").append(new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date())).append("）】手机收到的原始通知（每行一条）：\n\n");
        sb.append("---\n");
        if (lines == null || lines.isEmpty()) {
            sb.append("（暂无通知数据）");
        } else {
            // 只喂真实通知，过滤诊断/原始痕迹行（[[ ]] 和 >> RAW 是排查用，非工作内容），
            // 避免系统通知/诊断行挤占行数导致微信等真实通知被截断丢失。
            int count = 0, cap = Math.min(lines.size(), 300);
            for (int i = 0; i < lines.size() && count < cap; i++) {
                String l = lines.get(i);
                if (l == null) continue;
                String t = l.trim();
                if (t.startsWith("[[") || t.startsWith(">> RAW")) continue;  // 跳过诊断/原始痕迹
                sb.append(l).append("\n");
                count++;
            }
        }
        return sb.toString();
    }

    private static AIResult callOpenAi(Context ctx, List<String> lines) {
        AIResult r = new AIResult();
        try {
            String base = Config.get(ctx, Config.KEY_AI_BASE_URL, "").trim();
            String key = Config.getAiKey(ctx);
            String model = Config.get(ctx, Config.KEY_AI_MODEL, "").trim();
            // 用户未配置时，回退预置默认
            if (base.isEmpty() && model.isEmpty()) {
                base = PRESET_BASE_URL;
                model = PRESET_MODEL;
                key = key.isEmpty() ? PRESET_API_KEY : key;
            }
            if (base.isEmpty() || model.isEmpty()) { r.error = "未配置 AI 地址/模型"; return r; }
            // 确保 /v1/chat/completions
            String urlStr = base;
            if (!urlStr.endsWith("/chat/completions")) {
                if (!urlStr.endsWith("/")) urlStr += "/";
                if (urlStr.contains("/v1/")) urlStr += "chat/completions";
                else urlStr += "v1/chat/completions";
            }
            String prompt = buildPrompt(ctx, lines);
            String payload = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":"
                    + jsonEscape(prompt) + "}],\"temperature\":0.3}";

            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);   // 长纪要生成可能较慢，放宽到 120s
            c.setRequestProperty("Content-Type", "application/json");
            if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            LogStore.diag(ctx, "AI 请求已发出 → " + urlStr + " (model=" + model + ", 输入" + prompt.length() + "字符)");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                String body = readStream(c.getInputStream());
                r.text = extractContent(body);
                r.ok = true;
                LogStore.diag(ctx, "AI 返回 HTTP" + code + " 长度" + body.length() + " → 解析出 " + r.text.length() + " 字符"
                        + (r.text.trim().isEmpty() ? " [⚠️ 内容为空！可能解析失败]" : ""));
            } else {
                r.error = "AI HTTP " + code + ": " + readStream(c.getErrorStream());
                LogStore.diag(ctx, "❌ AI 请求失败 HTTP" + code + ": " + r.error);
            }
            c.disconnect();
        } catch (Exception e) {
            r.error = "AI 调用失败: " + e.getMessage();
            LogStore.diag(ctx, "❌ AI 调用异常: " + e);
        }
        return r;
    }

    private static AIResult callRelay(Context ctx, List<String> lines) {
        AIResult r = new AIResult();
        try {
            String relay = Config.get(ctx, Config.KEY_AI_RELAY_URL, "").trim();
            if (relay.isEmpty()) { r.error = "未配置服务器中转地址"; return r; }
            StringBuilder sb = new StringBuilder();
            sb.append("请对以下手机通知生成一份中文 Markdown 纪要，并严格按此顺序输出：\n")
              .append("1. ## 今日智能摘要（3-6条要点）\n")
              .append("2. ## 今日待办事项（可执行动作清单）\n")
              .append("3. ## AI 智能建议（需要关注/提醒的事项）\n")
              .append("4. ## 分项统计（工作/待办/紧急/会议/其他 各类条数）\n")
              .append("5. ## 分类明细（按主题归类的通知详情）\n\n");
            if (lines != null) {
                int n = Math.min(lines.size(), 80);
                for (int i = 0; i < n; i++) sb.append(lines.get(i)).append("\n");
            }
            String prompt = sb.toString();
            String payload = "{\"text\":" + jsonEscape(prompt) + "}";

            HttpURLConnection c = (HttpURLConnection) new URL(relay).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                String body = readStream(c.getInputStream());
                r.text = extractContent(body);
                if (r.text.isEmpty()) r.text = body; // 若返回即纯文本
                r.ok = true;
            } else {
                r.error = "中转 HTTP " + code;
            }
            c.disconnect();
        } catch (Exception e) {
            r.error = "中转调用失败: " + e.getMessage();
        }
        return r;
    }

    private static String readStream(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        try { br.close(); } catch (Exception ignore) {}
        return sb.toString();
    }

    /** 从 OpenAI 兼容 JSON 响应提取 content。 */
    private static String extractContent(String body) {
        try {
            int ci = body.indexOf("\"content\"");
            if (ci < 0) return body; // 可能是纯文本
            int start = body.indexOf(':', ci + 8);
            if (start < 0) return body;
            start = body.indexOf('"', start + 1);
            if (start < 0) return body;
            int end = start;
            StringBuilder sb = new StringBuilder();
            // 手动解析带转义的 JSON 字符串
            for (int i = start + 1; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '\\' && i + 1 < body.length()) {
                    char nx = body.charAt(i + 1);
                    if (nx == 'n') sb.append('\n');
                    else if (nx == 't') sb.append('\t');
                    else sb.append(nx);
                    i++;
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return body;
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
