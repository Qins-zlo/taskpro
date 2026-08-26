package io.taskpro;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时日志: 正在运行的脚本的实时输出缓冲区。
 * 脚本跑完时保留日志, 标记为已完成, 供 UI 查看最终输出。
 */
public class LiveLog {

    /** 脚本名 → 实时日志内容 */
    private static final Map<String, StringBuilder> LOGS = new ConcurrentHashMap<String, StringBuilder>();
    /** 已完成的脚本 (按完成顺序, 保留日志不删除) */
    private static final java.util.Set<String> DONE = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    /** 实时日志最多保留条目数 (超出清理最旧的已完成条目) */
    private static final int MAX_RETAIN = 20;

    /** 开始记录: 清空旧日志, 返回 append 用的 StringBuilder */
    public static StringBuilder start(String scriptName) {
        StringBuilder sb = new StringBuilder();
        LOGS.put(scriptName, sb);
        DONE.remove(scriptName);
        return sb;
    }

    /** 追加一行 (带换行) */
    public static void append(String scriptName, String line) {
        StringBuilder sb = LOGS.get(scriptName);
        if (sb != null) {
            sb.append(line).append("\n");
            if (sb.length() > 50000) {
                String rest = sb.substring(sb.length() - 30000);
                sb.setLength(0);
                sb.append("...(日志过长已截断, 只保留末尾 30000 字符)...\n").append(rest);
            }
        }
    }

    /** 获取当前实时日志 (可能为空) */
    public static String get(String scriptName) {
        StringBuilder sb = LOGS.get(scriptName);
        return sb == null ? "" : sb.toString();
    }

    /** 标记脚本结束 (保留日志) */
    public static void markDone(String scriptName) {
        DONE.add(scriptName);
        trim();
    }

    /** 脚本是否已完成 */
    public static boolean isDone(String scriptName) {
        return DONE.contains(scriptName);
    }

    /** 移除脚本的实时日志 (不保留, 因为历史日志已有完整输出) */
    public static void purge(String scriptName) {
        LOGS.remove(scriptName);
        DONE.remove(scriptName);
    }

    /** 移除已完成脚本的日志 (日志页已展示过) */
    public static void removeDone(String scriptName) {
        LOGS.remove(scriptName);
        DONE.remove(scriptName);
    }

    /** 是否有正在运行的脚本 */
    public static boolean hasRunning() {
        for (String name : LOGS.keySet()) {
            if (!DONE.contains(name)) return true;
        }
        return false;
    }

    /** 所有有实时日志的脚本名 (含已完成的) */
    public static java.util.Set<String> allScripts() {
        return LOGS.keySet();
    }

    /** 清理: 超过 MAX_RETAIN 时移除最旧的已完成条目 (运行中的永不清理) */
    private static synchronized void trim() {
        while (LOGS.size() > MAX_RETAIN) {
            String oldest = null;
            synchronized (DONE) {
                java.util.Iterator<String> it = DONE.iterator();
                while (it.hasNext()) {
                    String k = it.next();
                    if (LOGS.containsKey(k)) { oldest = k; break; }
                }
            }
            if (oldest == null) break;   // 剩下的都是运行中, 不清理
            removeDone(oldest);
        }
    }
}