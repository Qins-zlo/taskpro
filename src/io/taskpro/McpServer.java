package io.taskpro;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP (Model Context Protocol) 服务器核心。
 * 让外部 AI (Claude Desktop / Cursor / 任意 MCP 客户端) 通过标准 JSON-RPC over HTTP
 * 连接本应用, 远程操作定时任务/脚本/环境变量/日志/执行命令。
 *
 * 协议 (简化 MCP Streamable HTTP):
 *  - POST /mcp, JSON body = JSON-RPC 2.0 请求
 *  - method: tools/list | tools/call
 *  - 认证: Authorization: Bearer <token> (可选)
 *
 * 对外暴露的工具集:
 *  - list_tasks / get_task / create_task / update_task / delete_task / run_task_now
 *  - list_scripts / read_script / write_script / delete_script / run_script
 *  - list_env / get_env / set_env / delete_env
 *  - read_logs / clear_logs / get_stats
 *  - run_command (沙箱内执行 shell 命令)
 */
public class McpServer {
    private final Context ctx;
    private final int port;
    private final String token;
    private volatile boolean running = false;
    private java.net.ServerSocket serverSocket;
    private ExecutorService pool;

    public McpServer(Context ctx, int port, String token) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.token = token;
        this.pool = Executors.newCachedThreadPool();
    }

    public boolean isRunning() { return running; }

    /** 启动服务器 (阻塞监听线程) */
    public void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    serverSocket = new java.net.ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    while (running) {
                        final java.net.Socket s = serverSocket.accept();
                        pool.execute(new Runnable() {
                            public void run() { handle(s); }
                        });
                    }
                } catch (Exception e) {
                    running = false;
                    try { android.util.Log.w("TaskPro","MCP server: "+e.toString()); } catch(Exception __){}
                }
            }
        }, "mcp-server").start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { pool.shutdownNow(); } catch (Exception ignored) {}
    }

    /** 处理单个 HTTP 连接 */
    private void handle(java.net.Socket s) {
        try {
            s.setSoTimeout(30000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            // 读请求行 + headers
            String reqLine = r.readLine();
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            String line;
            int contentLength = 0;
            String authHeader = null;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase();
                    String v = line.substring(c + 1).trim();
                    headers.put(k, v);
                    if ("content-length".equals(k)) contentLength = Integer.parseInt(v);
                    if ("authorization".equals(k)) authHeader = v;
                }
            }
            // 只处理 POST /mcp
            if (!"POST".equals(method) || !path.startsWith("/mcp")) {
                writeResponse(s, 404, "{\"error\":\"not found\"}");
                return;
            }
            // token 校验
            boolean authed = token == null || token.isEmpty() || token.equals("mcp_disabled_auth");
            if (!authed && authHeader != null) {
                String t = authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader;
                authed = t.equals(token);
            }
            if (!authed) {
                writeResponse(s, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            // 读 body
            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[Math.min(contentLength, 8192)];
                int n;
                int total = 0;
                while (total < contentLength && (n = r.read(buf, 0, Math.min(buf.length, contentLength - total))) > 0) {
                    body.append(buf, 0, n);
                    total += n;
                }
            }
            s.shutdownInput();
            JSONObject resp = dispatch(body.toString());
            writeResponse(s, 200, resp.toString());
        } catch (Exception e) {
            try { writeResponse(s, 500, "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}"); } catch (Exception ignored) {}
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** JSON-RPC 分发 */
    private JSONObject dispatch(String body) {
        try {
            JSONObject req = new JSONObject(body);
            String id = req.optString("id", "0");
            String m = req.optString("method", "");
            if ("tools/list".equals(m)) {
                JSONObject tl = new JSONObject();
                tl.put("tools", listTools());
                return rpc(id, tl);
            } else if ("tools/call".equals(m)) {
                JSONObject params = req.optJSONObject("params");
                String name = params == null ? "" : params.optString("name", "");
                JSONObject args = params == null ? new JSONObject() : params.optJSONObject("arguments");
                String result = executeTool(name, args);
                JSONObject r = new JSONObject();
                JSONArray content = new JSONArray();
                JSONObject ct = new JSONObject();
                ct.put("type", "text");
                ct.put("text", result);
                content.put(ct);
                r.put("content", content);
                r.put("isError", false);
                return rpc(id, r);
            } else if ("ping".equals(m)) {
                return rpc(id, new JSONObject());
            } else {
                JSONObject err = new JSONObject();
                err.put("code", -32601);
                err.put("message", "method not found: " + m);
                return error(id, err);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("code", -32700);
                err.put("message", "parse error: " + e.toString());
                return error("0", err);
            } catch (Exception e2) {
                return error("0", new JSONObject());
            }
        }
    }

    private JSONObject rpc(String id, JSONObject result) {
        try {
            JSONObject o = new JSONObject();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("result", result);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject error(String id, JSONObject errobj) {
        try {
            JSONObject o = new JSONObject();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("error", errobj);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    private void writeResponse(java.net.Socket s, int code, String body) throws Exception {
        OutputStream os = s.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 401 ? "Unauthorized" : "Error") + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.getBytes("UTF-8").length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        os.write(head.getBytes("UTF-8"));
        os.write(body.getBytes("UTF-8"));
        os.flush();
    }

    // ═══════════════ 工具清单 ═══════════════

    private JSONArray listTools() {
        JSONArray arr = new JSONArray();
        // ---- 定时任务 ----
        arr.put(toolDef("list_tasks", "列出所有定时任务", "[]"));
        arr.put(toolDef("get_task", "获取单个任务详情", "{\"id\":\"任务ID\"}"));
        arr.put(toolDef("create_task", "创建定时任务", "{\"name\":\"任务名\",\"type\":\"shell|http\",\"script\":\"执行的命令或脚本名\",\"url\":\"type=http时的URL\",\"hour\":8,\"minute\":0,\"repeatDaily\":true,\"repeatDays\":\"1111111\"}"));
        arr.put(toolDef("update_task", "更新任务", "{\"id\":\"任务ID\",\"enabled\":true,\"hour\":8,\"minute\":0,\"retryTimes\":2}"));
        arr.put(toolDef("delete_task", "删除任务", "{\"id\":\"任务ID\"}"));
        arr.put(toolDef("run_task_now", "立即执行任务(不等待定时)", "{\"id\":\"任务ID\"}"));
        // ---- 脚本库 ----
        arr.put(toolDef("list_scripts", "列出脚本库所有脚本", "[]"));
        arr.put(toolDef("read_script", "读取脚本内容", "{\"name\":\"脚本名\"}"));
        arr.put(toolDef("write_script", "写入/创建脚本", "{\"name\":\"脚本名\",\"content\":\"脚本内容\"}"));
        arr.put(toolDef("delete_script", "删除脚本", "{\"name\":\"脚本名\"}"));
        arr.put(toolDef("run_script", "执行脚本(异步, 记录到日志)", "{\"name\":\"脚本名\"}"));
        // ---- 环境变量 ----
        arr.put(toolDef("list_env", "列出环境变量(不显示敏感值)", "[]"));
        arr.put(toolDef("get_env", "读取环境变量值", "{\"name\":\"变量名\"}"));
        arr.put(toolDef("set_env", "设置环境变量", "{\"name\":\"变量名\",\"value\":\"值\",\"remark\":\"备注\"}"));
        arr.put(toolDef("delete_env", "删除环境变量", "{\"name\":\"变量名\"}"));
        // ---- 日志/统计 ----
        arr.put(toolDef("read_logs", "读取执行日志", "{\"count\":20}"));
        arr.put(toolDef("clear_logs", "清空日志", "[]"));
        arr.put(toolDef("get_stats", "获取最近N天执行统计", "{\"days\":7}"));
        // ---- 命令执行 ----
        arr.put(toolDef("run_command", "执行 shell 命令 (python3/node/sh 可用, 超时30秒)", "{\"cmd\":\"命令\",\"timeout\":30}"));
        return arr;
    }

    private JSONObject toolDef(String name, String desc, String example) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("description", desc + " 示例: " + example);
            o.put("inputSchema", new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()));
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    // ═══════════════ 工具执行 ═══════════════

    public String executeTool(String name, JSONObject args) {
        try {
            if ("list_tasks".equals(name)) return listTasks();
            if ("get_task".equals(name)) return getTask(args.optString("id", ""));
            if ("create_task".equals(name)) return createTask(args);
            if ("update_task".equals(name)) return updateTask(args);
            if ("delete_task".equals(name)) return deleteTask(args.optString("id", ""));
            if ("run_task_now".equals(name)) return runTaskNow(args.optString("id", ""));
            if ("list_scripts".equals(name)) return listScripts();
            if ("read_script".equals(name)) return readScript(args.optString("name", ""));
            if ("write_script".equals(name)) return writeScript(args.optString("name", ""), args.optString("content", ""));
            if ("delete_script".equals(name)) return deleteScript(args.optString("name", ""));
            if ("run_script".equals(name)) return runScript(args.optString("name", ""));
            if ("list_env".equals(name)) return listEnv();
            if ("get_env".equals(name)) return getEnv(args.optString("name", ""));
            if ("set_env".equals(name)) return setEnv(args.optString("name", ""), args.optString("value", ""), args.optString("remark", ""));
            if ("delete_env".equals(name)) return deleteEnv(args.optString("name", ""));
            if ("read_logs".equals(name)) return readLogs(args.optInt("count", 20));
            if ("clear_logs".equals(name)) return clearLogs();
            if ("get_stats".equals(name)) return getStats(args.optInt("days", 7));
            if ("run_command".equals(name)) return runCommand(args.optString("cmd", ""), args.optInt("timeout", 30));
            return "未知工具: " + name;
        } catch (Exception e) {
            return "工具执行失败: " + e.toString();
        }
    }

    // ---------- 定时任务 ----------

    private String listTasks() {
        List<Task> tasks = TaskStore.load(ctx);
        try {
            JSONArray arr = new JSONArray();
            for (Task t : tasks) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("name", t.name);
                o.put("type", t.type);
                o.put("enabled", t.enabled);
                o.put("hour", t.hour);
                o.put("minute", t.minute);
                o.put("repeatDaily", t.repeatDaily);
                o.put("repeatDays", t.repeatDays);
                o.put("retryTimes", t.retryTimes);
                o.put("retryDelayMin", t.retryDelayMin);
                o.put("lastOk", t.lastOk);
                o.put("lastRunAt", t.lastRunAt);
                o.put("lastResult", t.lastResult);
                o.put("streak", t.streak);
                arr.put(o);
            }
            return arr.toString(2);
        } catch (Exception e) { return "[]"; }
    }

    private String getTask(String id) {
        for (Task t : TaskStore.load(ctx)) {
            if (t.id.equals(id)) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", t.id);
                    o.put("name", t.name);
                    o.put("type", t.type);
                    o.put("enabled", t.enabled);
                    o.put("hour", t.hour);
                    o.put("minute", t.minute);
                    o.put("repeatDaily", t.repeatDaily);
                    o.put("repeatDays", t.repeatDays);
                    o.put("retryTimes", t.retryTimes);
                    o.put("retryDelayMin", t.retryDelayMin);
                    o.put("script", t.script);
                    o.put("steps", t.steps);
                    o.put("lastOk", t.lastOk);
                    o.put("lastResult", t.lastResult);
                    return o.toString(2);
                } catch (Exception e) { return "读取失败"; }
            }
        }
        return "任务不存在: " + id;
    }

    private String createTask(JSONObject args) {
        String name = args.optString("name", "").trim();
        if (name.isEmpty()) return "错误: name 不能为空";
        String type = "http".equals(args.optString("type", "shell")) ? "http" : "shell";
        Task t = new Task(TaskStore.newId(), name, type);
        t.script = args.optString("script", "");
        t.hour = args.optInt("hour", 8);
        t.minute = args.optInt("minute", 0);
        t.repeatDaily = args.optBoolean("repeatDaily", true);
        if (!t.repeatDaily) t.repeatDays = args.optString("repeatDays", "1111111");
        t.retryTimes = args.optInt("retryTimes", 2);
        t.retryDelayMin = args.optInt("retryDelayMin", 5);
        if ("http".equals(type)) {
            String url = args.optString("url", "");
            if (url.isEmpty()) return "错误: type=http 时 url 不能为空";
            JSONObject step = new JSONObject();
            try {
                step.put("method", "GET");
                step.put("url", url);
            } catch (Exception ignored) {}
            t.steps = new JSONArray();
            t.steps.put(step);
        } else {
            if (t.script.isEmpty()) return "错误: type=shell 时 script 不能为空";
        }
        List<Task> tasks = TaskStore.load(ctx);
        tasks.add(t);
        TaskStore.save(ctx, tasks);
        try { AlarmScheduler.schedule(ctx, t); } catch (Exception ignored) {}
        return "已创建任务: " + name + " (id=" + t.id + ", " + t.hour + ":" + String.format("%02d", t.minute) + ")";
    }

    private String updateTask(JSONObject args) {
        String id = args.optString("id", "");
        List<Task> tasks = TaskStore.load(ctx);
        for (Task t : tasks) {
            if (t.id.equals(id)) {
                if (args.has("name")) t.name = args.optString("name", t.name);
                if (args.has("enabled")) t.enabled = args.optBoolean("enabled", t.enabled);
                if (args.has("hour")) t.hour = args.optInt("hour", t.hour);
                if (args.has("minute")) t.minute = args.optInt("minute", t.minute);
                if (args.has("repeatDaily")) t.repeatDaily = args.optBoolean("repeatDaily", t.repeatDaily);
                if (args.has("repeatDays")) t.repeatDays = args.optString("repeatDays", t.repeatDays);
                if (args.has("retryTimes")) t.retryTimes = args.optInt("retryTimes", t.retryTimes);
                if (args.has("retryDelayMin")) t.retryDelayMin = args.optInt("retryDelayMin", t.retryDelayMin);
                TaskStore.save(ctx, tasks);
                try { AlarmScheduler.schedule(ctx, t); } catch (Exception ignored) {}
                return "已更新任务: " + t.name;
            }
        }
        return "任务不存在: " + id;
    }

    private String deleteTask(String id) {
        List<Task> tasks = TaskStore.load(ctx);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id.equals(id)) {
                try { AlarmScheduler.cancel(ctx, id); } catch (Exception ignored) {}
                tasks.remove(i);
                TaskStore.save(ctx, tasks);
                return "已删除任务: " + id;
            }
        }
        return "任务不存在: " + id;
    }

    private String runTaskNow(String id) {
        for (Task t : TaskStore.load(ctx)) {
            if (t.id.equals(id)) {
                new Thread(new Runnable() {
                    public void run() {
                        TaskEngine.appContext = ctx;
                        TaskEngine.Result r = TaskEngine.run(t, new TaskEngine.Logger() {
                            public void log(String line) {}
                        });
                        try {
                            TaskLog.append(ctx, t.name, (r.ok ? "手动执行成功" : "手动执行失败") + " | " + r.summary + "\n(由 MCP 触发)");
                            Stats.record(ctx, r.ok);
                        } catch (Exception ignored) {}
                    }
                }).start();
                return "任务已触发执行: " + t.name;
            }
        }
        return "任务不存在: " + id;
    }

    // ---------- 脚本库 ----------

    private String listScripts() {
        List<ScriptStore.Script> scripts = ScriptStore.list(ctx);
        StringBuilder sb = new StringBuilder("脚本库 (" + scripts.size() + " 个):\n");
        for (ScriptStore.Script s : scripts) {
            sb.append("  ").append(s.name).append(" (").append(s.type).append(")\n");
        }
        return sb.toString().trim();
    }

    private String readScript(String name) {
        String content = ScriptStore.read(ctx, name);
        if (content.isEmpty() && !ScriptStore.exists(ctx, name)) return "脚本不存在: " + name;
        return content;
    }

    private String writeScript(String name, String content) {
        if (name == null || name.isEmpty()) return "错误: name 不能为空";
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return "错误: 非法文件名";
        ScriptStore.write(ctx, name, content);
        return "已写入脚本: " + name + " (" + content.length() + " 字符)";
    }

    private String deleteScript(String name) {
        if (!ScriptStore.exists(ctx, name)) return "脚本不存在: " + name;
        ScriptStore.delete(ctx, name);
        return "已删除脚本: " + name;
    }

    private String runScript(String name) {
        if (!ScriptStore.exists(ctx, name)) return "脚本不存在: " + name;
        new Thread(new Runnable() {
            public void run() {
                try {
                    AdvActivity.runScriptByName(ctx, name, "[mcp] " + name, -1);
                } catch (Exception e) {
                    TaskLog.append(ctx, "[mcp] " + name, "执行异常: " + e.toString());
                }
            }
        }).start();
        return "脚本已触发执行 (记录到日志): " + name;
    }

    // ---------- 环境变量 ----------

    private String listEnv() {
        List<EnvStore.Env> envs = EnvStore.load(ctx);
        if (envs.isEmpty()) return "(环境变量库为空)";
        StringBuilder sb = new StringBuilder("环境变量 (" + envs.size() + " 个):\n");
        for (EnvStore.Env e : envs) {
            String display = EnvStore.isSensitive(e.name) ? "******" : e.value;
            sb.append("  ").append(e.name).append(" = ").append(display)
                    .append(e.remark != null && !e.remark.isEmpty() ? "  # " + e.remark : "").append("\n");
        }
        return sb.toString().trim();
    }

    private String getEnv(String name) {
        if (name.isEmpty()) return "错误: name 不能为空";
        String v = EnvStore.get(ctx, name);
        if (v == null) return "变量不存在: " + name;
        return EnvStore.isSensitive(name) ? "变量 " + name + " 是敏感变量, 值已隐藏 (长度 " + v.length() + ")" : v;
    }

    private String setEnv(String name, String value, String remark) {
        if (name.isEmpty()) return "错误: name 不能为空";
        List<EnvStore.Env> envs = EnvStore.load(ctx);
        boolean found = false;
        for (EnvStore.Env e : envs) {
            if (e.name.equals(name)) { e.value = value; e.remark = remark; found = true; break; }
        }
        if (!found) {
            EnvStore.Env ne = new EnvStore.Env(name, value);
            ne.remark = remark;
            envs.add(ne);
        }
        EnvStore.save(ctx, envs);
        return "已保存环境变量: " + name + " (长度 " + value.length() + ")";
    }

    private String deleteEnv(String name) {
        if (name.isEmpty()) return "错误: name 不能为空";
        List<EnvStore.Env> envs = EnvStore.load(ctx);
        List<EnvStore.Env> na = new java.util.ArrayList<EnvStore.Env>();
        boolean found = false;
        for (EnvStore.Env e : envs) {
            if (e.name.equals(name)) { found = true; continue; }
            na.add(e);
        }
        if (!found) return "变量不存在: " + name;
        EnvStore.save(ctx, na);
        return "已删除环境变量: " + name;
    }

    // ---------- 日志/统计 ----------

    private String readLogs(int count) {
        if (count <= 0) count = 20;
        if (count > 100) count = 100;
        String all = TaskLog.load(ctx);
        if ("暂无日志".equals(all)) return all;
        String[] entries = all.split("\n\n");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(count, entries.length);
        for (int i = entries.length - n; i < entries.length; i++) {
            sb.append(entries[i]).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String clearLogs() {
        TaskLog.clear(ctx);
        return "日志已清空";
    }

    private String getStats(int days) {
        try {
            JSONArray arr = Stats.lastDays(ctx, days);
            return arr.toString(2);
        } catch (Exception e) { return "读取统计失败: " + e.toString(); }
    }

    // ---------- 命令执行 ----------

    private String runCommand(String cmd, int timeout) {
        if (cmd == null || cmd.isEmpty()) return "错误: cmd 不能为空";
        if (timeout <= 0) timeout = 30;
        if (timeout > 120) timeout = 120;
        if (cmd.contains("rm -rf /") || cmd.contains("rm -fr /")
                || cmd.contains("format") || cmd.contains("mkfs")) {
            return "已拒绝危险命令 (rm -rf / 等)";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                    RuntimeManager.buildCommand(ctx, cmd));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (r.ready()) {
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append("\n");
                        if (sb.length() > 50000) { sb.append("...(输出过长已截断)\n"); p.destroy(); break; }
                    }
                } else {
                    try { p.exitValue(); break; } catch (IllegalThreadStateException e) {
                        Thread.sleep(100);
                    }
                }
            }
            boolean alive = true;
            try { p.exitValue(); alive = false; } catch (IllegalThreadStateException e) { alive = true; }
            if (alive) { p.destroy(); return "命令超时 (" + timeout + "s) 已终止:\n" + sb.toString().trim(); }
            return "退出码 " + p.exitValue() + ":\n" + sb.toString().trim();
        } catch (Exception e) {
            return "命令执行失败: " + e.toString();
        }
    }
}