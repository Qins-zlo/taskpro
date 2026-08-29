package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * MCP 服务配置: 让外部 AI (Claude Desktop / Cursor / 其他 MCP 客户端)
 * 通过标准 MCP 协议连接本应用, 远程操作定时任务/脚本/日志/环境变量。
 */
public class McpConfig {
    private static final String PREFS = "mcp_config";
    private static final String K_ENABLED = "enabled";
    private static final String K_PORT = "port";
    private static final String K_TOKEN = "token";

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, 0).getBoolean(K_ENABLED, false);
    }
    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(K_ENABLED, v).apply();
    }

    public static int port(Context c) {
        return c.getSharedPreferences(PREFS, 0).getInt(K_PORT, 8898);
    }
    public static void setPort(Context c, int p) {
        c.getSharedPreferences(PREFS, 0).edit().putInt(K_PORT, p).apply();
    }

    /** 访问令牌: 未设置时自动生成 (防止局域网内他人访问) */
    public static String token(Context c) {
        String t = c.getSharedPreferences(PREFS, 0).getString(K_TOKEN, "");
        if (t.isEmpty()) {
            t = genToken();
            c.getSharedPreferences(PREFS, 0).edit().putString(K_TOKEN, t).apply();
        }
        return t;
    }
    public static void setToken(Context c, String t) {
        c.getSharedPreferences(PREFS, 0).edit().putString(K_TOKEN, t).apply();
    }

    private static String genToken() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 获取本机局域网 IP (供外部客户端连接) */
    public static String lanIp(Context c) {
        try {
            java.net.NetworkInterface ni = null;
            java.util.Enumeration<java.net.NetworkInterface> ens =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ens.hasMoreElements()) {
                java.net.NetworkInterface e = ens.nextElement();
                if (!e.isUp() || e.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = e.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        return "127.0.0.1";
    }

    /** MCP endpoint 地址 */
    public static String endpoint(Context c) {
        return "http://" + lanIp(c) + ":" + port(c) + "/mcp";
    }
}