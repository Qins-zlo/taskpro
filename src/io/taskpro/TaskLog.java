package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志: 保存最近 200 条, 每条约 1KB 上限, 防止膨胀。
 */
public class TaskLog {
    private static final String PREFS = "taskrun_log";
    private static final String KEY = "logs";
    private static final int MAX = 200;

    public static synchronized void append(Context ctx, String taskName, String body) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr;
        String raw = sp.getString(KEY, null);
        try {
            arr = raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            arr = new JSONArray();
        }
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        if (body.length() > 3000) body = body.substring(0, 3000) + "...";
        String entry = "[" + ts + "] " + taskName + "\n" + body;
        arr.put(entry);
        while (arr.length() > MAX) {
            int n = arr.length();
            JSONArray na = new JSONArray();
            for (int i = 1; i < n; i++) na.put(arr.optString(i));
            arr = na;
        }
        sp.edit().putString(KEY, arr.toString()).apply();
        versionCounter++;   // 通知 UI 有新日志
    }

    public static String load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) return "暂无日志";
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                sb.append(arr.optString(i)).append("\n\n");
            }
        } catch (Exception e) {
            return raw;
        }
        return sb.toString();
    }

    public static void clear(Context ctx) {
        synchronized (TaskLog.class) {   // 与 append 互斥, 防止并发写覆盖
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit();
            versionCounter++;   // 通知 UI 日志已清空
        }
    }

    /**
     * 成功后清理失败日志: 删除指定任务名下的失败记录 (失败→自动重试→成功后, 旧的失败日志不再残留)
     * taskNames: 可传多个名称变体 (如 task.name 与 task.name+"(重试)")
     * 只删除"失败特征"的条目, 保留触发/开始/成功等正常日志
     */
    public static synchronized void removeFailedLogs(Context ctx, String... taskNames) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            JSONArray na = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                String e = arr.optString(i);
                if (isFailedEntry(e, taskNames)) { changed = true; continue; }
                na.put(e);
            }
            if (changed) {
                sp.edit().putString(KEY, na.toString()).apply();
                versionCounter++;   // 通知 UI 日志已清理
            }
        } catch (Exception ex) { try { android.util.Log.w("TaskPro","catch: "+ex.getMessage()); } catch(Exception __){} }
    }

    /** 判断单条日志是否属于指定任务名且为失败记录 */
    private static boolean isFailedEntry(String entry, String[] taskNames) {
        if (entry == null || taskNames == null || taskNames.length == 0) return false;
        int nl = entry.indexOf('\n');
        String head = nl > 0 ? entry.substring(0, nl) : entry;
        int br = head.indexOf(']');
        String task = br > 0 ? head.substring(br + 1).trim() : head.trim();
        // 匹配任一任务名变体 (精确 或 带"(重试)"后缀)
        boolean nameMatch = false;
        for (String tn : taskNames) {
            if (tn == null || tn.isEmpty()) continue;
            if (task.equals(tn) || task.startsWith(tn + "(重试)")) { nameMatch = true; break; }
        }
        if (!nameMatch) return false;
        String body = nl > 0 ? entry.substring(nl + 1) : "";
        if (body == null) return false;
        // 失败特征: 明确的失败/退出码非0/超时/网络顺延/异常
        if (body.contains("执行失败")) return true;
        if (body.contains("退出码") && !body.contains("退出码 0")) return true;
        if (body.contains("超时") || body.contains("网络不可用, 顺延") || body.contains("异常:")) return true;
        return false;
    }

    /** 单条日志条目 */
    public static class Entry {
        public String ts;      // 时间戳 MM-dd HH:mm:ss
        public String task;    // 任务/脚本名
        public String body;    // 正文
        public boolean ok;     // 是否成功 (正文含"退出码 0"且无"执行失败")
    }

    /** 历史日志版本号 (Append 新日志时 +1, 供 UI 检测变化) */
    private static volatile int versionCounter = 0;
    public static int version() { return versionCounter; }

    /** 结构化读取所有日志 (不拼接) */
    public static List<Entry> listEntries(Context ctx) {
        List<Entry> list = new java.util.ArrayList<Entry>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String e = arr.optString(i);
                Entry en = new Entry();
                int nl = e.indexOf('\n');
                if (nl > 0) {
                    String head = e.substring(0, nl);
                    // 格式: [MM-dd HH:mm:ss] 任务名
                    en.ts = head.replaceAll("^\\[|\\]$", "").trim();
                    int sp2 = en.ts.indexOf(']');
                    String rest = head;
                    int br = rest.indexOf(']');
                    if (br > 0) {
                        en.ts = rest.substring(1, br).trim();
                        en.task = rest.substring(br + 1).trim();
                    }
                    en.body = e.substring(nl + 1);
                } else {
                    en.task = "";
                    en.body = e;
                }
                en.ok = en.body != null && !en.body.contains("执行失败")
                        && en.body.contains("退出码 0");
                list.add(en);
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        return list;
    }
}
