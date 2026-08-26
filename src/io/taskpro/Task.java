package io.taskpro;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 任务数据模型。
 * 类型 type:
 *   "http"  -> steps 为 HTTP 步骤数组, 结果回填到 vars
 *   "shell" -> script 为 sh 命令文本
 */
public class Task {
    public String id;
    public String name;
    public String type;          // "http" | "shell"
    public boolean enabled = true;
    // 调度
    public int hour = 8;
    public int minute = 0;
    public boolean repeatDaily = true;
    public String repeatDays = "1111111"; // 7位, 周一..周日, '1'=执行
    // http 任务: JSON 数组描述步骤
    public JSONArray steps = new JSONArray();
    // shell 任务: 命令文本
    public String script = "";
    // 每个任务独立的账号(覆盖 {{user}}/{{pass}}), 为空则用空白
    public String authUser = "";
    public String authPass = "";
    // 失败重试
    public int retryTimes = 2;      // 失败后重试次数
    public int retryDelayMin = 5;   // 重试间隔(分钟)
    // 执行统计(最近结果)
    public String lastResult = "";  // 最近一次结果摘要
    public long lastRunAt = 0;      // 最近执行时间戳
    public boolean lastOk = true;   // 最近是否成功
    public int streak = 0;          // 连续成功天数

    public Task(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    // 从 JSON 反序列化
    public static Task fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Task t = new Task(o.optString("id"), o.optString("name", "任务"), o.optString("type", "shell"));
            t.enabled = o.optBoolean("enabled", true);
            t.hour = o.optInt("hour", 8);
            t.minute = o.optInt("minute", 0);
            t.repeatDaily = o.optBoolean("repeatDaily", true);
            t.repeatDays = o.optString("repeatDays", "1111111");
            t.steps = o.optJSONArray("steps");
            if (t.steps == null) t.steps = new JSONArray();
            t.script = o.optString("script", "");
            t.authUser = o.optString("authUser", "");
            t.authPass = o.optString("authPass", "");
            t.retryTimes = o.optInt("retryTimes", 2);
            t.retryDelayMin = o.optInt("retryDelayMin", 5);
            t.lastResult = o.optString("lastResult", "");
            t.lastRunAt = o.optLong("lastRunAt", 0);
            t.lastOk = o.optBoolean("lastOk", true);
            t.streak = o.optInt("streak", 0);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("type", type);
            o.put("enabled", enabled);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("repeatDaily", repeatDaily);
            o.put("repeatDays", repeatDays);
            o.put("steps", steps);
            o.put("script", script);
            o.put("authUser", authUser);
            o.put("authPass", authPass);
            o.put("retryTimes", retryTimes);
            o.put("retryDelayMin", retryDelayMin);
            o.put("lastResult", lastResult);
            o.put("lastRunAt", lastRunAt);
            o.put("lastOk", lastOk);
            o.put("streak", streak);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** 该任务在周几(1=周一..7=周日)是否应执行 */
    public boolean onDay(int dow) {
        if (repeatDaily) return true;
        if (repeatDays == null || repeatDays.length() < 7) return true;
        int idx = dow - 1;
        if (idx < 0 || idx >= 7) return true;
        return repeatDays.charAt(idx) == '1';
    }
}
