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
     */
    public static JSONObject checkVersion(Context ctx) {
        String base = baseUrl(ctx);
        if (base.isEmpty()) return null;
        String raw = get(base + "/api.php?action=version");
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("code", -1) != 0) return null;
            JSONObject data = o.optJSONObject("data");
            if (data == null) return null;
            int vc = data.optInt("version_code", -1);
            if (vc <= 0) return null;
            // 当前版本号: 取包信息 (无 BuildConfig, 手写构建)
            int myCode = 0;
            try {
                myCode = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionCode;
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            if (vc <= myCode) return null;
            return data;
        } catch (Exception e) { return null; }
    }

    /**
     * 拉取公告: 内容与上次看到的一致(未变化)返回 null; 变化了返回公告数组
     * 注: 返回后已记录"已看到", 下次内容不变则不再返回
     */
    /** 拉取脚本市场列表 (同步, 调用方开线程)
     *  返回列表 JSONArray, 保留 content 在缓存中供安装时使用 */
    public static JSONArray fetchScripts(Context ctx) {
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
     *  调用方需确保 fetchScripts 已被调用并缓存 */
    public static String fetchScriptContent(Context ctx, String name) {
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

    /** 提交脚本到市场 (待审核): 返回结果消息 */
    public static String submitScript(Context ctx, String name, String type, String ver,
                                      String note, String content, String author, String uid) {
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

    /** 查询我的提交 (POST uid): 返回脚本状态列表或 null */
    public static JSONArray myScripts(Context ctx, String uid) {
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
