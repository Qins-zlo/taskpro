package io.taskpro;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务执行引擎。
 *  - HTTP 任务: 按 steps 逐步骤发请求, 提取字段进 vars, 支持 {{var}} 占位
 *  - Shell 任务: 通过 /system/bin/sh 执行 script
 * 线程安全: 每个任务独立调用 run()(不共享状态)
 */
public class TaskEngine {
    /** Shell execution timeout, configurable */
    public static int SHELL_TIMEOUT_SEC = 60;
    /** App 上下文 (由 MainActivity/Receiver 启动时设置, 供 shell 任务注入运行时环境) */
    public static android.content.Context appContext;

    public static CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    static {
        CookieHandler.setDefault(cookieManager);
    }

    /** 执行结果 */
    public static class Result {
        public boolean ok;
        public String summary;
        public Result(boolean ok, String summary) {
            this.ok = ok;
            this.summary = summary;
        }
    }

    public interface Logger {
        void log(String line);
    }

    /** 串行执行锁: 防止多个任务并发共享 cookie 仓库互相污染 */
    private static final Object EXEC_LOCK = new Object();

    /** 运行任务, 返回结果(成功/失败 + 摘要) */
    public static Result run(Task task, Logger logger) {
        synchronized (EXEC_LOCK) {
            // 每次执行前清空 cookie 仓库, 保证干净的新会话
            // (解决: 旧 session cookie 残留导致 jianidc 等站点拿不到登录表单/CSRF)
            try {
                cookieManager.getCookieStore().removeAll();
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            if ("http".equals(task.type)) {
                return runHttp(task, logger);
            } else {
                return runShell(task, logger);
            }
        }
    }

    // ---------- HTTP 多步骤 ----------
    private static Result runHttp(Task task, Logger logger) {
        final Map<String, String> vars = new HashMap<String, String>();
        JSONArray steps = task.steps;
        if (steps == null) steps = new JSONArray();
        int n = steps.length();
        boolean ok = true;
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < n; i++) {
            try {
                JSONObject step = steps.getJSONObject(i);
                String method = step.optString("method", "GET").toUpperCase(Locale.US);
                String urlTpl = step.optString("url", "");
                String url = fill(urlTpl, vars);
                logger.log("步骤" + (i + 1) + ": " + method + " " + url);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(SHELL_TIMEOUT_SEC * 1000);
                conn.setReadTimeout(90000);
                conn.setRequestMethod(method);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36");
                conn.setRequestProperty("Accept", "*/*");

                JSONObject headers = step.optJSONObject("headers");
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String hk = it.next();
                        String hv = fill(headers.optString(hk), vars);
                        conn.setRequestProperty(hk, hv);
                    }
                }

                String body = null;
                if (step.has("body")) {
                    body = fill(step.optString("body"), vars);
                    conn.setDoOutput(true);
                } else if ("POST".equals(method) || "PUT".equals(method)) {
                    conn.setDoOutput(true);
                }

                conn.connect();
                if (body != null) {
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (resp == null) resp = "";
                resp = resp.trim();
                logger.log("  -> HTTP " + code);
                String shortResp = resp.length() > 400 ? resp.substring(0, 400) : resp;
                logger.log("  -> " + shortResp);

                JSONObject extract = step.optJSONObject("extract");
                if (extract != null) {
                    Iterator<String> it = extract.keys();
                    while (it.hasNext()) {
                        String varName = it.next();
                        String regex = extract.optString(varName);
                        String val = extractFirst(resp, regex);
                        if (val != null) {
                            vars.put(varName, val);
                            logger.log("  提取 " + varName + "=" + val);
                        }
                    }
                }
                if (code >= 400) {
                    ok = false;
                    summary.append("步骤").append(i + 1).append(" HTTP ").append(code).append("; ");
                } else {
                    summary.append("步骤").append(i + 1).append(" OK; ");
                }
                conn.disconnect();
            } catch (Exception e) {
                logger.log("步骤" + (i + 1) + " 失败: " + e.toString());
                ok = false;
                summary.append("失败@步骤").append(i + 1).append(": ").append(e.getMessage()).append("; ");
            }
        }
        summary.append("完成");
        return new Result(ok, summary.toString());
    }

    public static String fill(String tpl, Map<String, String> vars) {
        if (tpl == null) return "";
        String out = tpl;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        out = out.replaceAll("\\{\\{[a-zA-Z0-9_]+\\}\\}", "");
        return out;
    }

    static String extractFirst(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return null;
    }

    static String readStream(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                total += line.length() + 1;
                if (total > 2 * 1024 * 1024) { // B10: 响应超 2MB 截断, 防 OOM
                    sb.append("... (响应超过 2MB, 已截断)");
                    break;
                }
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Shell 执行 ----------
    private static Result runShell(Task task, Logger logger) {
        String script = task.script;
        logger.log("执行 shell 脚本");
        // 注入完整运行时环境: python3/pip/busybox/curl 命令, 用户环境变量, CA 证书
        // 并切换到可写工作目录 (脚本里 ./xxx.log 相对路径才能落盘)
        String cmd = script;
        if (appContext != null) {
            cmd = "cd \"" + appContext.getFilesDir().getAbsolutePath() + "\"; "
                    + RuntimeManager.buildCommand(appContext, script);
            // 诊断: 记录重写后的脚本 (tmp 映射 + 命令重写), 便于定位执行问题
            try {
                String rewritten = RuntimeManager.rewriteScript(appContext, script);
                if (rewritten.length() > 600) rewritten = rewritten.substring(0, 600) + "...";
                logger.log("重写后: " + rewritten.replace("\n", " "));
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        final StringBuilder out = new StringBuilder();
        try {
            final Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            Thread outT = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) { out.append(line).append('\n'); logger.log("  " + line); }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            outT.start();
            Thread errT = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) { out.append("! ").append(line).append('\n'); logger.log("  ! " + line); }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            errT.start();
            // 超时保护: 最多 60 秒
            final boolean[] timedOut = {false};
            Thread killer = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(SHELL_TIMEOUT_SEC * 1000);
                        if (p.isAlive()) {
                            timedOut[0] = true;
                            p.destroy();
                        }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            killer.setDaemon(true);
            killer.start();
            int code = p.waitFor();
            outT.join(2000);
            errT.join(2000);
            logger.log("退出码: " + code);
            if (timedOut[0]) {
                logger.log("!! 执行超时(60s), 已强制终止");
                return new Result(false, "超时终止(60s)");
            }
            if (code == 0) return new Result(true, "完成(退出码0)");
            return new Result(false, "退出码 " + code);
        } catch (Exception e) {
            logger.log("shell 失败: " + e.toString());
            return new Result(false, "失败: " + e.getMessage());
        }
    }
}
