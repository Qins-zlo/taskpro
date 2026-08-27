package io.taskpro;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 后端 API 客户端: 版本检查 / 公告拉取
 * 公告去重: 内容未变化(与上次看到的一致)则返回 null, 不打扰用户
 */
public class Backend {
    private static JSONArray fetchScriptsCache = null;  // 缓存脚本列表, 供 fetchScriptContent 直接取 content
    private static final String PREFS = "backend";
    private static final String KEY_ANN_HASH = "ann_hash";
    /** 服务器地址 (内置固定, 用户不可修改) */
    public static final String DEFAULT_BASE = "https://qins.7r.fit/api";

    // ════════════════════════════════════════════════════════
    // GitHub 脚本市场 (方案B: 读取走 raw 匿名, 上传走 Issue 审核)
    // ════════════════════════════════════════════════════════
    /** 脚本市场 GitHub 仓库 (owner/repo) */
    public static final String GH_REPO = "Qins-zlo/taskpro-scripts";
    /** GitHub raw 基础地址 */
    private static final String GH_RAW = "https://raw.githubusercontent.com/" + GH_REPO + "/main/";
    /** GitHub API 基础地址 */
    private static final String GH_API = "https://api.github.com/repos/" + GH_REPO;
    /** 上传用的 bot token.
     *  源码用占位符, 构建时通过 build.sh 从环境变量 GH_BOT_TOKEN 注入真实 token,
     *  避免把真实 token 提交到公开仓库。
     *  若未注入, 则从 BuildConfig 读取 (兼容旧构建). 仅需 scripts 仓库 issues 写权限。 */
    private static final String GH_BOT_TOKEN = "REPLACE_WITH_BUILD_INJECTED_TOKEN";
    /** 脚本前缀标记, 用于区分脚本提交 Issue */
    private static final String ISSUE_PREFIX = "[script] ";
    /** 提交审核状态: 通过后由作者把 Issue 关闭并把脚本合入 scripts/ 目录 */
    private static final String ISSUE_MARK = "<!-- taskpro-script -->";

    /** GitHub 读取列表: 读 index.json. 优先 API(带token, 高配额), 失败回退 raw */
    public static JSONArray fetchScriptsGithub() {
        try {
            JSONObject o = new JSONObject(getAuth(GH_API + "/contents/index.json"));
            String content = decodeBase64(o.optString("content", ""));
            if (!content.isEmpty()) {
                JSONObject idx = new JSONObject(content);
                return idx.optJSONArray("scripts");
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 回退: raw (可能有 CDN 缓存延迟)
        try {
            JSONObject idx = new JSONObject(get(GH_RAW + "index.json"));
            return idx.optJSONArray("scripts");
        } catch (Exception e) { return null; }
    }

    /** GitHub 读取脚本内容: 匿名读 scripts/<name>/index.json. 优先 API(带token), 失败回退 raw */
    public static String fetchScriptContentGithub(String name) {
        String enc = urlEnc(name);
        try {
            JSONObject o = new JSONObject(getAuth(GH_API + "/contents/scripts/" + enc + "/index.json"));
            String content = decodeBase64(o.optString("content", ""));
            if (!content.isEmpty()) {
                JSONObject idx = new JSONObject(content);
                return idx.optString("content", null);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 回退: raw (URL编码后的路径)
        try {
            JSONObject idx = new JSONObject(get(GH_RAW + "scripts/" + enc + "/index.json"));
            return idx.optString("content", null);
        } catch (Exception e) { return null; }
    }

    /** URL 编码文件名 (安全拼接到路径) */
    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    /** GET 请求带 bot token (用于 GitHub API, 提升限流配额到 5000/h) */
    private static String getAuth(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "taskpro-android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Authorization", "token " + GH_BOT_TOKEN);
            int code = c.getResponseCode();
            if (code != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception e) { return null; }
        finally { if (c != null) { try { c.disconnect(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} } } }
    }

    /** base64 解码 (兼容 Android) */
    private static String decodeBase64(String s) {
        try {
            byte[] b = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            return new String(b, "UTF-8");
        } catch (Exception e) {
            try {
                byte[] b = java.util.Base64.getDecoder().decode(s);
                return new String(b, "UTF-8");
            } catch (Exception e2) { return ""; }
        }
    }

    /**
     * GitHub 提交脚本: 创建带标记的 Issue, 待作者审核
     * 返回结果消息
     */
    public static String submitScriptGithub(String name, String type, String ver,
                                            String note, String content, String author, String uid) {
        // 检查 token 是否已注入 (占位符表示构建时未配置 GH_BOT_TOKEN)
        if (GH_BOT_TOKEN.contains("REPLACE_WITH")) {
            return "上传功能未配置 (构建时缺少 GH_BOT_TOKEN)";
        }
        // 脚本名校验: 禁止路径分隔符等会破坏 scripts/<name> 目录的字符
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.contains(" ") || name.contains("#") || name.contains("?") || name.contains("%")) {
            return "文件名包含非法字符 (不能含 / \\ 空格 # ? % ..)";
        }
        try {
            String title = ISSUE_PREFIX + name + " v" + (ver.isEmpty() ? "1.0" : ver);
            // 若脚本内容包含 ``` 代码围栏, 用更长的围栏包裹避免破坏 Markdown
            String fence = "```";
            while (content.contains(fence)) fence += "`";
            String body = ISSUE_MARK + "\n\n"
                    + "**名称**: " + name + "\n"
                    + "**类型**: " + type + "\n"
                    + "**版本**: " + (ver.isEmpty() ? "1.0" : ver) + "\n"
                    + "**作者**: " + (author.isEmpty() ? "匿名" : author) + "\n"
                    + "**UID**: " + uid + "\n"
                    + "**说明**: " + note + "\n\n"
                    + fence + type + "\n" + content + "\n" + fence;
            URL u = new URL(GH_API + "/issues");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Authorization", "token " + GH_BOT_TOKEN);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject payload = new JSONObject();
            payload.put("title", title);
            payload.put("body", body);
            java.io.OutputStream os = c.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code == 201) return "已提交, 待作者审核";
            // 读取错误信息
            java.io.InputStream es = c.getErrorStream();
            String err = "";
            if (es != null) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(es, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                err = sb.toString();
            }
            return "提交失败 (" + code + ")";
        } catch (Exception e) {
            return "提交失败: " + e.toString();
        }
    }

    /**
     * GitHub 查询我的提交: 用 API 列出所有脚本 Issue, 过滤 uid
     * 返回 {name, ver, time, status, reason} 列表
     */
    public static JSONArray myScriptsGithub(String uid) {
        try {
            JSONArray out = new JSONArray();
            // 分页拉取 (GitHub issues API 每页最多100, 支持多页)
            int page = 1;
            while (true) {
                String urlStr = GH_API + "/issues?state=all&per_page=100&page=" + page;
                String raw = getAuth(urlStr);
                if (raw == null) break;
                JSONArray arr = new JSONArray(raw);
                if (arr.length() == 0) break;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject issue = arr.optJSONObject(i);
                    if (issue == null) continue;
                    String title = issue.optString("title", "");
                    if (!title.startsWith(ISSUE_PREFIX)) continue;
                    String body = issue.optString("body", "");
                    if (body.indexOf("**UID**: " + uid) < 0) continue;
                    JSONObject item = new JSONObject();
                    // 提取纯名称 (去掉版本号)
                    String fullName = title.substring(ISSUE_PREFIX.length());
                    String pureName = fullName;
                    int sp = fullName.indexOf(" v");
                    if (sp > 0) pureName = fullName.substring(0, sp);
                    item.put("name", pureName);
                    // 提取版本
                    int vIdx = fullName.indexOf(" v");
                    String ver = "1.0";
                    if (vIdx >= 0) ver = fullName.substring(vIdx + 2);
                    item.put("ver", ver);
                    String created = issue.optString("created_at", "");
                    item.put("time", created.length() >= 10 ? created.substring(0, 10) : created);
                    String state = issue.optString("state", "open");
                    String status;
                    String reason = "";
                    // 从 body 提取驳回理由 (审核工具追加的标记)
                    int rejIdx = body.indexOf("**rejected**:");
                    if (rejIdx >= 0) {
                        reason = body.substring(rejIdx + "**rejected**:".length()).trim();
                    }
                    // 若 Issue 被关闭且带 [merged] 标记, 视为已上架
                    if ("closed".equals(state) && body.contains("[merged]")) {
                        status = "published";
                    } else if ("closed".equals(state)) {
                        status = "rejected";
                        // 兼容旧格式: 整个 body 被覆盖成"未通过审核: xxx"时提取
                        if (reason.isEmpty() && body.startsWith("未通过审核")) {
                            reason = body.substring("未通过审核".length()).trim();
                            if (reason.startsWith(":")) reason = reason.substring(1).trim();
                        }
                        if (reason.isEmpty()) reason = "未通过审核";
                    } else {
                        status = "pending";
                    }
                    item.put("status", status);
                    item.put("reason", reason);
                    out.put(item);
                }
                if (arr.length() < 100) break;  // 已到最后一页
                page++;
            }
            return out;
        } catch (Exception e) { return null; }
    }

    public static String baseUrl(Context ctx) {
        return DEFAULT_BASE;
    }

    /** 用系统下载管理器下载 APK: 通知栏显示进度, 完成后点击通知直接安装 */
    public static void download(Context ctx, String url, String fileName) {
        try {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("定时任务Pro 更新");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            dm.enqueue(req);
        } catch (Exception e) {
            // 兜底: 系统浏览器打开下载
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }

    /** GET 请求, 失败返回 null */
    private static String get(String urlStr) {        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "taskpro-android");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} } }
        }
    }

    /**
     * 检查更新: 有新版本返回版本信息 JSONObject (含 version_name/version_code/url/log/force)
     * 无更新/未配置/请求失败返回 null
     * 数据源: GitHub Release (releases/latest), 兼容旧字段结构
     */
    public static JSONObject checkVersion(Context ctx) {
        try {
            // 走 GitHub Release 最新版
            String raw = getAuth("https://api.github.com/repos/Qins-zlo/taskpro/releases/latest");
            if (raw == null) return null;
            JSONObject release = new JSONObject(raw);
            String tag = release.optString("tag_name", "");
            if (tag.isEmpty()) return null;
            String verName = tag.startsWith("v") ? tag.substring(1) : tag;
            String body = release.optString("body", "");
            String downloadUrl = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                downloadUrl = assets.optJSONObject(0).optString("browser_download_url", "");
            }
            // 当前版本名
            String curName = "";
            try {
                curName = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            if (curName.isEmpty()) return null;
            // 语义化版本比较
            if (compareVersion(verName, curName) <= 0) return null;
            JSONObject data = new JSONObject();
            data.put("version_name", verName);
            data.put("url", downloadUrl);
            data.put("log", body);
            data.put("force", false);
            return data;
        } catch (Exception e) { return null; }
    }

    /** 语义化版本比较: a>b 返回>0, a<b 返回<0, 相等返回0 (正确处理 7.10>7.9) */
    private static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? pv(pa[i]) : 0;
            int y = i < pb.length ? pv(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }
    private static int pv(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    /**
     * 拉取公告: 内容与上次看到的一致(未变化)返回 null; 变化了返回公告数组
     * 注: 返回后已记录"已看到", 下次内容不变则不再返回
     */
    /** 拉取脚本市场列表 (同步, 调用方开线程)
     *  返回列表 JSONArray, 保留 content 在缓存中供安装时使用
     *  优先走 GitHub raw (匿名, 免费), 失败回退到旧后端 */
    public static JSONArray fetchScripts(Context ctx) {
        // GitHub 方案: 匿名读 index.json
        try {
            JSONArray arr = fetchScriptsGithub();
            if (arr != null) {
                fetchScriptsCache = arr;
                return arr;
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 回退: 旧后端
        String base = baseUrl(ctx);
        if (base.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(get(base + "/api.php?action=scripts"));
            if (o.optInt("code") != 0) return null;
            JSONArray arr = o.optJSONArray("data");
            fetchScriptsCache = arr;  // 缓存供安装时取 content
            return arr;
        } catch (Exception e) { return null; }
    }

    /** 从缓存列表中获取脚本内容 (不再单独请求后端, 因为后端可能不支持 action=script 端点)
     *  调用方需确保 fetchScripts 已被调用并缓存
     *  优先走 GitHub raw */
    public static String fetchScriptContent(Context ctx, String name) {
        // GitHub 方案: 匿名读 scripts/<name>/index.json
        try {
            String content = fetchScriptContentGithub(name);
            if (content != null) return content;
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 尝试从缓存列表中提取
        JSONArray cached = fetchScriptsCache;
        if (cached != null) {
            for (int i = 0; i < cached.length(); i++) {
                JSONObject s = cached.optJSONObject(i);
                if (s != null && name.equals(s.optString("name", ""))) {
                    String content = s.optString("content", null);
                    if (content != null && !content.isEmpty()) return content;
                    break;
                }
            }
        }
        // 回退: 单独请求后端 (如果后端支持 action=script 端点)
        String base = baseUrl(ctx);
        if (base.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(get(base + "/api.php?action=script&name="
                    + URLEncoder.encode(name, "UTF-8")));
            if (o.optInt("code") != 0) return null;
            JSONObject data = o.optJSONObject("data");
            if (data == null) return null;
            return data.optString("content", null);
        } catch (Exception e) { return null; }
    }

    /** 后台静默检查 (24h 定时): 只读, 不消费公告 hash.
     *  返回 {update: JSONObject|null, announceChanged: bool, announceHash: String, announce: JSONArray|null} */
    public static JSONObject backgroundCheck(Context ctx) {
        JSONObject result = new JSONObject();
        try {
            result.put("update", checkVersion(ctx));
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        try {
            String base = baseUrl(ctx);
            if (!base.isEmpty()) {
                String raw = get(base + "/api.php?action=announce");
                if (raw != null) {
                    JSONObject o = new JSONObject(raw);
                    if (o.optInt("code", -1) == 0) {
                        JSONArray arr = o.optJSONArray("data");
                        String h = md5(raw);
                        SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
                        String last = sp.getString(KEY_ANN_HASH, "");
                        result.put("announceChanged", !h.equals(last));
                        result.put("announceHash", h);
                        result.put("announce", arr == null ? new JSONArray() : arr);
                    }
                }
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return result;
    }

    /** 提交脚本到市场 (GitHub Issue 审核流, 待作者审核): 返回结果消息 */
    public static String submitScript(Context ctx, String name, String type, String ver,
                                      String note, String content, String author, String uid) {
        // GitHub 方案: 创建 Issue
        String gh = submitScriptGithub(name, type, ver, note, content, author, uid);
        if (gh != null && !gh.startsWith("提交失败")) return gh;
        // 回退: 旧后端
        String base = baseUrl(ctx);
        if (base.isEmpty()) return "未设置服务器地址 (更多页 → 检查更新)";
        try {
            URL u = new URL(base + "/api.php?action=submit_script");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            StringBuilder body = new StringBuilder();
            body.append("name=").append(URLEncoder.encode(name, "UTF-8"));
            body.append("&type=").append(URLEncoder.encode(type, "UTF-8"));
            body.append("&ver=").append(URLEncoder.encode(ver, "UTF-8"));
            body.append("&note=").append(URLEncoder.encode(note, "UTF-8"));
            body.append("&content=").append(URLEncoder.encode(content, "UTF-8"));
            body.append("&author=").append(URLEncoder.encode(author, "UTF-8"));
            body.append("&uid=").append(URLEncoder.encode(uid, "UTF-8"));
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            return o.optInt("code") == 0 ? o.optString("msg", "已提交") : o.optString("msg", "提交失败");
        } catch (Exception e) { return "提交失败: " + e.toString(); }
    }

    /** 查询我的提交 (POST uid): 返回脚本状态列表或 null (GitHub Issue 审核流) */
    public static JSONArray myScripts(Context ctx, String uid) {
        // GitHub 方案: 查询脚本 Issue
        try {
            JSONArray gh = myScriptsGithub(uid);
            if (gh != null) return gh;
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 回退: 旧后端
        String base = baseUrl(ctx);
        if (base.isEmpty()) return null;
        try {
            URL u = new URL(base + "/api.php?action=my_scripts");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            java.io.OutputStream os = c.getOutputStream();
            os.write(("uid=" + URLEncoder.encode(uid, "UTF-8")).getBytes("UTF-8"));
            os.close();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            if (o.optInt("code") != 0) return null;
            return o.optJSONArray("data");
        } catch (Exception e) { return null; }
    }

    public static JSONArray checkAnnounce(Context ctx) {        String base = baseUrl(ctx);
        if (base.isEmpty()) return null;
        String raw = get(base + "/api.php?action=announce");
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("code", -1) != 0) return null;
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) return null;
            String h = md5(raw);
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
            String last = sp.getString(KEY_ANN_HASH, "");
            if (h.equals(last)) return null;   // 公告未变化, 不显示
            sp.edit().putString(KEY_ANN_HASH, h).apply();
            return arr;
        } catch (Exception e) { return null; }
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
