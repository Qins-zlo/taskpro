package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 拉库订阅管理: 保存用户输入的远程仓库/文件地址,
 * 支持定时拉取、白名单过滤、自动注册变量和定时任务。
 */
public class SubscriptionStore {
    private static final String PREFS = "subscription_store";
    private static final String KEY = "subs";

    public static class Subscription {
        public String id;
        public String name;          // 别名, 如 "jd_scripts"
        public String url;           // 仓库 URL 或 raw 文件 URL
        public String type = "repo"; // "repo" 或 "raw"
        public String whitelist = "";
        public String blacklist = "";
        public String branch = "main";
        public String extensions = "py js ts tsx jsx mjs sh";
        public boolean autoAddCron = true;
        public boolean autoDelCron = true;
        public String schedule = ""; // cron 表达式, 空=不自动更新
        public long lastRunAt = 0;
        public String status = "idle"; // idle/running/error
        public int scriptCount = 0;
        public long createdAt = 0;
        public String lastError = "";
        public String lastLog = "";

        public Subscription() {
            this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            this.createdAt = System.currentTimeMillis();
        }
    }

    public static List<Subscription> load(Context ctx) {
        List<Subscription> list = new ArrayList<Subscription>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Subscription s = new Subscription();
                s.id = o.optString("id", s.id);
                s.name = o.optString("name", "");
                s.url = o.optString("url", "");
                s.type = o.optString("type", "repo");
                s.whitelist = o.optString("whitelist", "");
                s.blacklist = o.optString("blacklist", "");
                s.branch = o.optString("branch", "main");
                s.extensions = o.optString("extensions", "py js sh");
                s.autoAddCron = o.optBoolean("autoAddCron", true);
                s.autoDelCron = o.optBoolean("autoDelCron", true);
                s.schedule = o.optString("schedule", "");
                s.lastRunAt = o.optLong("lastRunAt", 0);
                s.status = o.optString("status", "idle");
                s.scriptCount = o.optInt("scriptCount", 0);
                s.createdAt = o.optLong("createdAt", 0);
                s.lastError = o.optString("lastError", "");
                s.lastLog = o.optString("lastLog", "");
                list.add(s);
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        return list;
    }

    public static void save(Context ctx, List<Subscription> list) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (Subscription s : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("type", s.type);
                o.put("whitelist", s.whitelist);
                o.put("blacklist", s.blacklist);
                o.put("branch", s.branch);
                o.put("extensions", s.extensions);
                o.put("autoAddCron", s.autoAddCron);
                o.put("autoDelCron", s.autoDelCron);
                o.put("schedule", s.schedule);
                o.put("lastRunAt", s.lastRunAt);
                o.put("status", s.status);
                o.put("scriptCount", s.scriptCount);
                o.put("createdAt", s.createdAt);
                o.put("lastError", s.lastError);
                o.put("lastLog", s.lastLog);
                arr.put(o);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    /** 脚本存放目录: files/subscriptions/<id>/ */
    public static java.io.File subDir(Context ctx, String id) {
        java.io.File d = new java.io.File(ctx.getFilesDir(), "subscriptions/" + id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 从订阅名推断别名: 提取仓库名 */
    public static String inferName(String url) {
        if (url == null || url.isEmpty()) return "unknown";
        // GitHub: https://github.com/user/repo => repo
        // Raw: https://raw.githubusercontent.com/.../file.py => file
        String n = url.trim();
        if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        int lastSlash = n.lastIndexOf('/');
        if (lastSlash >= 0) n = n.substring(lastSlash + 1);
        // 去掉 .git 后缀
        if (n.endsWith(".git")) n = n.substring(0, n.length() - 4);
        // 去掉常见文件扩展名
        for (String ext : new String[]{".py", ".js", ".sh", ".ts", ".json"}) {
            if (n.endsWith(ext)) return n.substring(0, n.length() - ext.length());
        }
        // 去掉版本号后缀如 v1.0
        return n.replaceAll("[._-]v?\\d+(\\.\\d+)*$", "");
    }
}