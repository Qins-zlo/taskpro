package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务存储(SharedPreferences)。首次启动为空列表(不内置任何任务)。
 * v2: 清除 v1 遗留数据(旧版内置三个任务的残留)。
 */
public class TaskStore {
    private static final String PREFS = "taskrun_store";
    private static final String KEY_TASKS = "tasks";
    private static final String KEY_VER = "ver";
    private static final int VER = 2;

    public static List<Task> load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // B4: 不再因版本号升级清空任务 (旧格式兼容读取, 避免用户数据静默丢失)
        String raw = sp.getString(KEY_TASKS, null);
        List<Task> list = new ArrayList<Task>();
        if (raw == null) {
            return list; // 空列表, 不内置
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Task t = Task.fromJson(arr.getString(i));
                if (t != null) {
                    // 解密密码(兼容旧明文)
                    t.authPass = Crypto.dec(t.authPass);
                    list.add(t);
                }
            }
        } catch (Exception e) {
            // 解析失败则重置为空
        }
        return list;
    }

    public static void save(Context ctx, List<Task> list) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (Task t : list) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(t.toJson());
                if (t.authPass != null && !t.authPass.isEmpty()) {
                    o.put("authPass", Crypto.enc(t.authPass));
                }
                arr.put(o.toString());
            } catch (Exception e) {
                arr.put(t.toJson());
            }
        }
        sp.edit().putString(KEY_TASKS, arr.toString()).apply();
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 复制任务(新 id, 名称加"副本") */
    public static Task duplicate(Task src) {
        Task t = Task.fromJson(src.toJson());
        if (t != null) {
            t.id = newId();
            t.name = src.name + " 副本";
        }
        return t;
    }

    /** 导出所有任务为分享文本 */
    public static String exportAll(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 定时任务导出 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.CHINA).format(new java.util.Date())).append("\n");
        sb.append("# 在 App 的「更多 → 导入任务」粘贴即可\n\n");
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Task t : tasks) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(t.toJson());
                o.remove("id");          // 导入时生成新 id
                o.remove("lastResult");  // 不带统计
                o.remove("lastRunAt");
                o.remove("lastOk");
                o.remove("streak");
                arr.put(o);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        try {
            sb.append(arr.toString(2));
        } catch (Exception ignored) {
            sb.append(arr.toString());
        }
        return sb.toString();
    }

    /** 从导出文本导入任务, 返回新增的任务列表 */
    public static List<Task> importTasks(Context ctx, String text) {
        List<Task> added = new ArrayList<Task>();
        if (text == null) return added;
        // B12: 从第一个 [ 起做括号配对扫描(跳过字符串内的括号), 取完整 JSON 数组
        int s = text.indexOf('[');
        if (s < 0) return added;
        int depth = 0; boolean inStr = false; boolean esc = false;
        int e = -1;
        for (int i = s; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { e = i; break; }
                }
            }
        }
        if (e <= s) return added;
        String json = text.substring(s, e + 1);
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            List<Task> existing = load(ctx);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Task t = Task.fromJson(o.toString());
                if (t == null) continue;
                t.id = newId();
                existing.add(t);
                added.add(t);
            }
            save(ctx, existing);
        } catch (Exception e2) {
            return added; // 解析失败返回空
        }
        return added;
    }
}
