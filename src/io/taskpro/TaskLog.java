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
