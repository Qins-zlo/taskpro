package io.taskpro;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本运行状态追踪器。
 * 以「脚本名」为 key 记录正在运行的 Process, 供 UI 卡片显示"运行中"状态。
 * 注意: ConcurrentHashMap 不允许 null key/value, 用哨兵 SENTINEL 表示"已登记但未关联进程"。
 */
public class ScriptRunner {

    /** 哨兵: 表示已登记运行, 但进程还未关联 (不能为 null, ConcurrentHashMap 禁 null) */
    private static final Object SENTINEL = new Object();

    /** 脚本名 → 正在运行的进程 (SENTINEL 或 Process) */
    private static final Map<String, Object> RUNNING = new ConcurrentHashMap<String, Object>();

    /** 登记脚本开始运行 */
    public static void markRunning(String scriptName) {
        if (scriptName == null) return;
        RUNNING.put(scriptName, SENTINEL);
    }

    /** 关联实际进程 (开始执行后调用) */
    public static void attachProcess(String scriptName, Process p) {
        if (scriptName == null || p == null) return;
        RUNNING.put(scriptName, p);
    }

    /** 脚本是否正在运行 */
    public static boolean isRunning(String scriptName) {
        if (scriptName == null) return false;
        Object v = RUNNING.get(scriptName);
        if (v == null) return false;
        if (v == SENTINEL) return true;   // 已登记但进程未关联, 视为运行中
        return ((Process) v).isAlive();
    }

    /** 标记脚本运行结束 */
    public static void markDone(String scriptName) {
        if (scriptName == null) return;
        RUNNING.remove(scriptName);
    }

    /** 当前正在运行的脚本名集合 */
    public static java.util.Set<String> runningNames() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (Map.Entry<String, Object> e : RUNNING.entrySet()) {
            Object v = e.getValue();
            if (v == SENTINEL || ((Process) v).isAlive()) out.add(e.getKey());
        }
        return out;
    }

    /** 清理: 检查并移除已结束的条目 (防止泄漏) */
    public static void sweep() {
        java.util.Iterator<Map.Entry<String, Object>> it = RUNNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> e = it.next();
            Object v = e.getValue();
            if (v != SENTINEL && !((Process) v).isAlive()) it.remove();
        }
    }
}