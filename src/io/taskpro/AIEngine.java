package io.taskpro;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;

/**
 * AI 引擎: OpenAI 兼容 Chat Completions + 工具调用循环。
 * AI 只能在独立工作区 (files/ai_workspace/) 内操作, 不直接触碰 scripts 目录。
 */
public class AIEngine {

    /** 工作区根目录 (AI 唯一可写区域) */
    public static File workspace(Context ctx) {
        File d = new File(ctx.getFilesDir(), "ai_workspace");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 清空工作区全部内容 (含子目录), 返回删除的文件数 */
    public static int clearWorkspace(Context ctx) {
        File ws = workspace(ctx);
        File[] files = ws.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            n += deleteRecursive(f);
        }
        return n;
    }
    private static int deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        return f.delete() ? 1 : 0;
    }

    /** 停止标志: 供 UI 设置, 中断 429 等待或重试 */
    public static volatile boolean stopRequested = false;
    public static void requestStop() { stopRequested = true; }
    public static void resetStop() { stopRequested = false; }

    /** 校验路径在工作区内 (防越权) */
    private static File safePath(Context ctx, String rel) throws Exception {
        File ws = workspace(ctx).getCanonicalFile();
        File f = new File(ws, rel == null ? "" : rel).getCanonicalFile();
        if (!f.getPath().startsWith(ws.getPath() + File.separator)
                && !f.getPath().equals(ws.getPath())) {
            throw new Exception("路径超出工作区: " + rel);
        }
        return f;
    }

    /** 系统提示词 */
    public static String systemPrompt(Context ctx) {
        String searchNote = "";
        if (AIConfig.searchEnabled(ctx)) {
            searchNote = "【联网搜索能力】\n"
                + "当用户要求查询实时信息(如天气、新闻、价格、公告等)时, 必须使用 web_search 工具搜索后再回答, 禁止编造数据。\n";
        }
        return "你是\"定时任务Pro\"内置的 AI 脚本助手, 专注帮助用户编写、调试和安装自动化脚本, 以及配置定时任务。\n"
                + "运行环境: Node.js 26 / Python 3.14 / Shell / 网络工具均可用。\n"
                + "工作目录(仅此目录可自由写): " + workspace(ctx).getAbsolutePath() + "\n"
                + searchNote
                + "【重要 - 必须先问用户】\n"
                + "用户要求写脚本时, 必须先问用户想用什么语言! 不要默认选! 直接问:\n"
                + "  \"你想用 Node.js (.js/.mjs)、Python (.py) 还是 Shell (.sh)？\"\n"
                + "等用户回复后再开始写, 不要自己决定。\n"
                + "【工作纪律 - 必须遵守】\n"
                + "1. 用户请求写脚本时, 必须用 write_file 创建 .js/.py/.sh 文件, 并用 run_command 实际运行测试, 禁止只给代码不测试。\n"
                + "2. 用户给账号密码/URL/Token 等敏感信息时, 直接写入脚本变量区并在顶部注释说明如何修改, 不要追问、不要明文发表在聊天里。\n"
                + "3. 脚本需要的非敏感配置(如签到 URL)也写入脚本变量区。\n"
                + "4. 需要保留的配置请用 set_env 存入环境变量库(敏感值不要写进脚本文件)。\n"
                + "5. 用户要求\"建个定时任务\"时, 用 create_task 创建; 用户要求\"装到脚本库\"时, 用 install_script 安装。\n"
                + "6. 不要读取或写入工作区以外的路径; 不要执行 rm -rf / 等危险命令。\n"
                + "7. 所有工具调用都必须在当前会话内完成, 不要建议用户手动操作。\n"
                + "8. 用户要求参考脚本开发文档时, 参考以下完整文档内容回答。\n"
                + "【完成报告格式】\n"
                + "完成后用要点回复: 文件名 / 用途 / 需要的环境变量(或已存入环境库的变量) / 测试结果(成功或失败原因) / 是否已安装。\n"
                + "如果调用了 install_script 或 create_task, 明确告知用户安装/创建结果。\n\n"
                + "【脚本开发文档】\n"
                + "以下是 App 内置的完整脚本开发文档, 用户可随时查阅。你应当熟知以下内容, 以便指导用户开发:\n\n"
                + getScriptDocSummary();
    }

    /** 脚本开发文档摘要(供 AI 系统提示词使用) */
    private static String getScriptDocSummary() {
        return ScriptDoc.DOC.substring(0, Math.min(ScriptDoc.DOC.length(), 6000))
                + "\n\n(以上为脚本开发文档摘要, 完整内容可在 App 内「更多 → 脚本开发文档」查看)";
    }

    /** 工具定义 (OpenAI tools 格式) */
    public static JSONArray tools() {
        JSONArray arr = new JSONArray();
        arr.put(tool("write_file", "在工作区写入/覆盖文件 (AI 创建脚本用)", new String[][]{
                {"path", "string", "相对工作区的文件路径, 如 checkin.js"},
                {"content", "string", "文件完整内容"}}));
        arr.put(tool("read_file", "读取工作区文件内容", new String[][]{
                {"path", "string", "相对工作区的文件路径"}}));
        arr.put(tool("list_files", "列出工作区目录下的文件", new String[][]{
                {"dir", "string", "相对工作区的目录, 留空表示根目录"}}));
        arr.put(tool("run_command", "执行 shell 命令 (node/python3/sh 可用, 工作目录=工作区, 超时60秒)",
                new String[][]{{"cmd", "string", "要执行的命令"}}));
        arr.put(tool("list_env", "列出环境变量库中所有变量名和备注 (不显示值)", new String[][]{
                {"noargs", "string", "留空即可"}}));
        arr.put(tool("set_env", "写入/更新一个环境变量 (脚本运行时自动注入, 敏感配置放这里)", new String[][]{
                {"name", "string", "变量名, 如 BAIDU_COOKIE"},
                {"value", "string", "变量值"},
                {"remark", "string", "备注说明(可选)"}}));
        arr.put(tool("install_script", "把工作区脚本安装到 App 脚本库 (用户可在脚本库中运行/定时/导出)", new String[][]{
                {"path", "string", "工作区内的脚本文件路径, 如 checkin.js"}}));
        arr.put(tool("create_task", "创建一个定时任务 (类似 cron, 到点自动执行脚本或命令)", new String[][]{
                {"name", "string", "任务名称"},
                {"type", "string", "任务类型: shell(执行脚本/命令) 或 http(请求URL)"},
                {"script", "string", "type=shell 时: 要执行的脚本文件名(工作区内, 如 checkin.js) 或直接写命令"},
                {"url", "string", "type=http 时要请求的 URL"},
                {"cron", "string", "cron 表达式, 如 0 8 * * * 表示每天8点; 支持 分 时 日 月 周"},
                {"note", "string", "备注(可选)"}}));
        arr.put(tool("web_search", "联网搜索 Bing 获取实时信息 (天气/新闻/价格/公告等), 搜索后基于结果回答",
                new String[][]{{"query", "string", "搜索关键词, 尽量简洁精确"}}));
        return arr;
    }

    private static JSONObject tool(String name, String desc, String[][] params) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "function");
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("description", desc);
            JSONObject p = new JSONObject();
            p.put("type", "object");
            JSONObject props = new JSONObject();
            JSONArray required = new JSONArray();
            for (String[] kv : params) {
                JSONObject pd = new JSONObject();
                pd.put("type", kv[1]);
                pd.put("description", kv[2]);
                props.put(kv[0], pd);
                required.put(kv[0]);
            }
            p.put("properties", props);
            p.put("required", required);
            f.put("parameters", p);
            o.put("function", f);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    /** 执行工具调用, 返回结果文本 */
    public static String executeTool(Context ctx, String name, String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson);
            if ("write_file".equals(name)) {
                File f = safePath(ctx, a.optString("path", ""));
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                Files.write(f.toPath(), a.optString("content", "").getBytes("UTF-8"));
                return "已写入 " + f.getName() + " (" + a.optString("content", "").length() + " 字符)";
            }
            if ("read_file".equals(name)) {
                File f = safePath(ctx, a.optString("path", ""));
                if (!f.exists()) return "文件不存在: " + a.optString("path");
                return new String(Files.readAllBytes(f.toPath()), "UTF-8");
            }
            if ("list_files".equals(name)) {
                File d = safePath(ctx, a.optString("dir", ""));
                if (!d.exists()) return "目录不存在: " + a.optString("dir");
                StringBuilder sb = new StringBuilder();
                File[] fs = d.listFiles();
                if (fs == null) return "(空)";
                for (File f : fs) {
                    sb.append(f.isDirectory() ? "[dir] " : "[file] ")
                            .append(f.getName()).append(" (").append(f.length()).append(" B)\n");
                }
                return sb.length() == 0 ? "(空目录)" : sb.toString().trim();
            }
            if ("run_command".equals(name)) {
                String cmd = a.optString("cmd", "");
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                        RuntimeManager.buildCommand(ctx, cmd));
                pb.directory(workspace(ctx));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (sb.length() > 20000) { sb.append("...(输出过长已截断)\n"); p.destroy(); break; }
                }
                if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy();
                    return "命令超时 (60秒) 已终止:\n" + sb;
                }
                return "退出码 " + p.exitValue() + ":\n" + sb.toString().trim();
            }
            if ("list_env".equals(name)) {
                List<EnvStore.Env> envs = EnvStore.load(ctx);
                if (envs.isEmpty()) return "(环境变量库为空)";
                StringBuilder sb = new StringBuilder();
                for (EnvStore.Env e : envs) {
                    sb.append(e.name);
                    if (e.remark != null && !e.remark.isEmpty()) sb.append("  # ").append(e.remark);
                    sb.append("\n");
                }
                return "环境变量列表 (" + envs.size() + " 个):\n" + sb.toString().trim();
            }
            if ("set_env".equals(name)) {
                String ename = a.optString("name", "").trim();
                if (ename.isEmpty()) return "错误: name 不能为空";
                String evalue = a.optString("value", "");
                String eremark = a.optString("remark", "");
                List<EnvStore.Env> envs = EnvStore.load(ctx);
                boolean found = false;
                for (EnvStore.Env e : envs) {
                    if (e.name.equals(ename)) { e.value = evalue; e.remark = eremark; found = true; break; }
                }
                if (!found) {
                    EnvStore.Env ne = new EnvStore.Env(ename, evalue);
                    ne.remark = eremark;
                    envs.add(ne);
                }
                EnvStore.save(ctx, envs);
                return "已保存环境变量: " + ename + " (长度 " + evalue.length() + ", 已隐藏值)";
            }
            if ("install_script".equals(name)) {
                String rel = a.optString("path", "").trim();
                if (rel.isEmpty()) return "错误: path 不能为空";
                File f = safePath(ctx, rel);
                if (!f.exists()) return "文件不存在: " + rel;
                String content = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                ScriptStore.write(ctx, f.getName(), content);
                return "已安装到脚本库: " + f.getName() + " (" + content.length() + " 字符)\n"
                        + "用户现在可以在「脚本库」中看到并运行/定时它。";
            }
            if ("create_task".equals(name)) {
                String tname = a.optString("name", "").trim();
                if (tname.isEmpty()) return "错误: name 不能为空";
                String ttype = a.optString("type", "shell").trim();
                String tcron = a.optString("cron", "").trim();
                String tnote = a.optString("note", "").trim();
                if (tcron.isEmpty()) tcron = "0 8 * * *";   // 默认每天 8 点
                Task t = new Task(TaskStore.newId(), tname, "shell".equals(ttype) ? "shell" : "http");
                t.script = "";
                if ("shell".equals(t.type)) {
                    String script = a.optString("script", "").trim();
                    if (script.isEmpty()) return "错误: type=shell 时 script 不能为空";
                    File sf = safePath(ctx, script);
                    if (sf.exists()) {
                        // 用户给的是工作区文件名 → 用 python3 运行它
                        t.script = "python3 " + sf.getAbsolutePath();
                    } else {
                        // 否则当作直接命令
                        t.script = script;
                    }
                } else {
                    String url = a.optString("url", "").trim();
                    if (url.isEmpty()) return "错误: type=http 时 url 不能为空";
                    JSONObject step = new JSONObject();
                    step.put("method", "GET");
                    step.put("url", url);
                    JSONArray steps = new JSONArray();
                    steps.put(step);
                    t.steps = steps;
                }
                // 解析 cron → hour/minute/repeatDays (支持 分 时 日 月 周)
                boolean cronOk = applyCron(t, tcron);
                if (!cronOk) return "cron 表达式无法解析: " + tcron
                        + " (支持格式: 分 时 日 月 周, 如 0 8 * * *)";
                List<Task> tasks = TaskStore.load(ctx);
                tasks.add(t);
                TaskStore.save(ctx, tasks);
                return "已创建定时任务: " + tname
                        + " (类型=" + t.type + ", 时间=" + t.hour + ":" + String.format("%02d", t.minute)
                        + (t.repeatDaily ? ", 每天" : ", 周" + t.repeatDays)
                        + (tnote.isEmpty() ? "" : ", 备注=" + tnote) + ")";
            }
            if ("web_search".equals(name)) {
                String query = a.optString("query", "").trim();
                if (query.isEmpty()) return "错误: query 不能为空";
                return searchWeb(ctx, query);
            }
            return "未知工具: " + name;
        } catch (Exception e) {
            return "工具执行失败: " + e.toString();
        }
    }

    /** 联网搜索 Bing, 返回摘要文本。使用 HttpURLConnection 直连, 不需额外依赖。 */
    private static String searchWeb(Context ctx, String query) {
        try {
            StringBuilder sb = new StringBuilder();
            String url = "https://www.bing.com/search?q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&count=10";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = c.getResponseCode();
            if (code != 200) return "搜索请求失败, HTTP " + code;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) html.append(line).append("\n");
            r.close();
            // 解析 Bing 搜索结果: 提取 <li class="b_algo"> ... </li>
            // 每个结果包含标题(h2>a) 和摘要(p)
            String h = html.toString();
            // 提取标题和摘要对
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<li class=\"b_algo\">(.*?)</li>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(h);
            int count = 0;
            while (m.find() && count < 8) {
                String block = m.group(1);
                // 提取标题
                java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                        "<h2>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                        java.util.regex.Pattern.DOTALL).matcher(block);
                if (tm.find()) {
                    String link = tm.group(1).replaceAll("&amp;", "&")
                            .replaceAll("&lt;", "<").replaceAll("&gt;", ">");
                    String title = tm.group(2).replaceAll("<[^>]+>", "")
                            .replaceAll("&amp;", "&").trim();
                    if (!title.isEmpty()) {
                        sb.append(count + 1).append(". ").append(title).append("\n");
                        sb.append("   ").append(link).append("\n");
                        // 提取摘要
                        java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                                "<p[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL).matcher(block);
                        if (sm.find()) {
                            String snippet = sm.group(1).replaceAll("<[^>]+>", "")
                                    .replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                                    .replaceAll("&gt;", ">").replaceAll("&nbsp;", " ").trim();
                            if (!snippet.isEmpty()) {
                                sb.append("   ").append(snippet.substring(0,
                                        Math.min(snippet.length(), 200))).append("\n");
                            }
                        }
                        sb.append("\n");
                        count++;
                    }
                }
            }
            if (sb.length() == 0) {
                // 没有解析到结果, 返回原始摘要
                java.util.regex.Pattern cap = java.util.regex.Pattern.compile(
                        "<p class=\"b_lineclamp2[^\"]*\">(.*?)</p>",
                        java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher cm = cap.matcher(h);
                while (cm.find() && count < 5) {
                    String s = cm.group(1).replaceAll("<[^>]+>", "").trim();
                    if (!s.isEmpty()) {
                        sb.append((count + 1)).append(". ").append(s).append("\n\n");
                        count++;
                    }
                }
            }
            if (sb.length() == 0) return "未找到相关结果。";
            return "Bing 搜索结果 (" + query + "):\n" + sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e.toString();
        }
    }

    /** 解析 cron 表达式 (分 时 日 月 周) → Task 的 hour/minute/repeatDays/repeatDaily
     *  复用项目内 CronParser (支持 * / 范围 a-b 步长 /n 逗号列表 完整语法)。
     *  仅取「时间+星期」维度, 日/月忽略。 */
    private static boolean applyCron(Task t, String cron) {
        try {
            if (cron == null) return false;
            CronParser.Field[] fs = CronParser.parse(cron.trim());
            if (fs == null) return false;
            // 提取第一个命中的 分/时
            int minute = -1, hour = -1;
            for (int i = 0; i < 60; i++) if (fs[0].bits[i]) { minute = i; break; }
            for (int i = 0; i < 24; i++) if (fs[1].bits[i]) { hour = i; break; }
            if (hour < 0 || minute < 0) return false;
            t.hour = hour;
            t.minute = minute;
            // 周 (cron: 0=周日..6=周六 → repeatDays: 周一在首位)
            if (fs[4].all) {
                t.repeatDaily = true;
            } else {
                boolean[] days = new boolean[7];
                boolean any = false;
                for (int i = 0; i < 7; i++) {   // i = cron dow (0=Sun..6=Sat)
                    if (fs[4].bits[i]) {
                        days[(i + 6) % 7] = true;   // 映射到 repeatDays 索引 (周一=0)
                        any = true;
                    }
                }
                if (any) {
                    t.repeatDaily = false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 7; i++) sb.append(days[i] ? '1' : '0');
                    t.repeatDays = sb.toString();
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 调用 LLM (OpenAI 兼容), 返回 choices[0].message */
    /** 429 频率限制异常: 携带建议等待秒数 */
    public static class RateLimitException extends Exception {
        public final long waitSeconds;
        public RateLimitException(long waitSeconds) {
            super("API 错误 429: 触发频率限制, 等待 " + waitSeconds + " 秒后重试");
            this.waitSeconds = waitSeconds;
        }
    }

    /** 流式回调 */
    public interface StreamListener {
        void onText(String delta);   // 回复文本增量 (主线程外, 调用方自行切 UI)
        void onError(String err);
        void onRetry(int attempt);   // 即将重试, 调用方应截断已输出内容
    }

    /** 流式调用 LLM: 文本增量经 listener 输出; 返回拼装完整的 tool_calls
     *  - 429 频率限制: 无限重试, 按 Retry-After 等待, 直到解除
     *  - 其它错误: 最多 MAX_RETRIES 次 (断连/超时/5xx) */
    public static JSONArray callLLMStream(Context ctx, JSONArray messages, JSONArray tools,
                                          StreamListener listener) throws Exception {
        if (!AIConfig.isConfigured(ctx)) throw new Exception("未配置 AI API");
        final int MAX_RETRIES = 2;
        Exception last = null;
        int normalAttempt = 0;     // 普通错误重试计数
        int rateLimitAttempt = 0;  // 429 重试计数
        while (true) {
            Exception attemptErr = null;
            try {
                return callLLMStreamOnce(ctx, messages, tools, listener);
            } catch (RateLimitException rle) {
                attemptErr = rle;
            } catch (Exception e) {
                attemptErr = e;
            }
            // 429 频率限制: 无限重试
            if (attemptErr instanceof RateLimitException) {
                RateLimitException rle = (RateLimitException) attemptErr;
                rateLimitAttempt++;
                long wait = rle.waitSeconds;
                listener.onText("\n\n[触发频率限制, 等待 " + wait + " 秒后自动重试 ("
                        + rateLimitAttempt + ")…]");
                for (long sec = 0; sec < wait; sec++) {
                    if (stopRequested) throw attemptErr;
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { throw attemptErr; }
                }
                listener.onRetry(rateLimitAttempt);
                continue;  // 无限循环直到成功或非 429 错误
            }
            // 普通错误: 有限重试
            normalAttempt++;
            boolean retriable = isRetriable(attemptErr);
            if (!retriable || normalAttempt >= MAX_RETRIES) {
                if (listener != null) listener.onError(
                        "重试 " + normalAttempt + " 次后仍失败: " + attemptErr.getMessage());
                throw attemptErr;
            }
            last = attemptErr;
            listener.onRetry(normalAttempt);
            listener.onText("\n\n[连接中断, 正在重试 " + normalAttempt + "/" + MAX_RETRIES + " …]");
            try { Thread.sleep(2000L * normalAttempt); } catch (InterruptedException ie) { break; }
        }
        throw last != null ? last : new Exception("未知错误");
    }

    /** 判断错误是否值得重试: 网络/超时/5xx */
    private static boolean isRetriable(Exception e) {
        String m = e.getMessage();
        if (m == null) return false;
        // 5xx 服务端错误可重试
        if (m.startsWith("API 错误 5")) return true;
        // 网络/超时
        if (m.contains("Socket") || m.contains("timeout") || m.contains("timed out")
                || m.contains("Connect") || m.contains("reset") || m.contains("unreachable")
                || m.contains("EOFException") || m.contains("Connection")
                || m.contains("InterruptedIOException") || m.contains("read error")
                || m.contains("broken pipe") || m.contains("ECONNRESET")
                || m.contains("ECONNABORTED") || m.contains("ECONNREFUSED")) return true;
        // 认证/参数错误不可重试
        if (m.startsWith("API 错误 4")) return false;
        return false;
    }

    /** 单次流式请求 */
    private static JSONArray callLLMStreamOnce(Context ctx, JSONArray messages, JSONArray tools,
                                               StreamListener listener) throws Exception {
        String base = AIConfig.baseUrl(ctx);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL u = new URL(base + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + AIConfig.apiKey(ctx));
        JSONObject body = new JSONObject();
        body.put("model", AIConfig.model(ctx));
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("tool_choice", "auto");
        body.put("stream", true);
        body.put("max_tokens", 8000);
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
        if (code == 429) {
            // 频率限制: 读取 Retry-After 头, 抛自定义异常
            long waitSec = 30; // 默认 30 秒
            String retryAfter = c.getHeaderField("Retry-After");
            if (retryAfter != null) {
                try {
                    // Retry-After 可能是秒数或 HTTP-date, 优先解析秒数
                    waitSec = Math.max(5, Long.parseLong(retryAfter.trim()));
                } catch (NumberFormatException ignored) {
                    try {
                        // HTTP-date 格式: 解析到秒数
                        java.util.Date d = new java.text.SimpleDateFormat(
                                "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                                .parse(retryAfter);
                        waitSec = Math.max(5, (d.getTime() - System.currentTimeMillis()) / 1000);
                    } catch (Exception ignored2) {
                        waitSec = 30;
                    }
                }
            }
            r.close();
            throw new RateLimitException(waitSec);
        }
        if (code >= 400) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            throw new Exception("API 错误 " + code + ": " + sb.toString().substring(0,
                    Math.min(sb.length(), 300)));
        }
        // SSE 解析: data: {chunk}
        JSONArray toolCalls = new JSONArray();
        java.util.Map<Integer, String[]> pending = new java.util.HashMap<Integer, String[]>();
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) break;
            try {
                JSONObject chunk = new JSONObject(data);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                // 注意: JSON null 必须用 isNull 判断, optString 会把 JSONObject.NULL 转成 "null" 字符串
                if (!delta.isNull("content")) {
                    String dc = delta.optString("content", "");
                    if (!dc.isEmpty() && listener != null) listener.onText(dc);
                }
                JSONArray dtc = delta.optJSONArray("tool_calls");
                if (dtc != null) {
                    for (int i = 0; i < dtc.length(); i++) {
                        JSONObject tc = dtc.optJSONObject(i);
                        int idx = tc.optInt("index", 0);
                        if (!pending.containsKey(idx)) pending.put(idx, new String[]{"", "", ""});
                        String[] p = pending.get(idx);
                        if (tc.has("id") && !tc.isNull("id")) p[0] += tc.optString("id", "");
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            // 必须 isNull 判断: has() 对 null 值也返回 true, optString 会转成 "null" 字符串
                            if (fn.has("name") && !fn.isNull("name")) p[1] += fn.optString("name", "");
                            if (fn.has("arguments") && !fn.isNull("arguments")) p[2] += fn.optString("arguments", "");
                        }
                    }
                }
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        try { r.close(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        java.util.List<Integer> idxs = new java.util.ArrayList<Integer>(pending.keySet());
        java.util.Collections.sort(idxs);
        for (int idx : idxs) {
            String[] p = pending.get(idx);
            JSONObject fn = new JSONObject();
            fn.put("name", p[1]);
            fn.put("arguments", p[2]);
            JSONObject tc = new JSONObject();
            tc.put("id", p[0].isEmpty() ? "call_" + idx : p[0]);
            tc.put("function", fn);
            toolCalls.put(tc);
        }
        return toolCalls.length() == 0 ? null : toolCalls;
    }
}
