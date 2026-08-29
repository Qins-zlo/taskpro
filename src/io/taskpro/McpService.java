package io.taskpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * MCP 服务 (前台服务): 保活 MCP 服务器进程, 支持外部 AI 通过 LAN 连接。
 * 启动条件: McpConfig.enabled = true (更多页 MCP 配置开启)。
 * 开关变化时由 McpManager 调用 start/stop。
 */
public class McpService extends Service {
    private static final String CH = "mcp_run";
    private static volatile McpServer server = null;

    /** 当前是否已运行 */
    public static boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** 启动服务 (前台 + 服务器) */
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, McpService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","McpService start: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 停止服务 */
    public static void stop(Context ctx) {
        try {
            Intent i = new Intent(ctx, McpService.class);
            ctx.stopService(i);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","McpService stop: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 获取端口 (无论是否运行都返回配置端口) */
    public static int port(Context ctx) {
        return McpConfig.port(ctx);
    }

    /** 获取 endpoint 地址 */
    public static String endpoint(Context ctx) {
        return McpConfig.endpoint(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationChannel c = new NotificationChannel(CH, "MCP 服务", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 检查开关: 关闭则停止
        if (!McpConfig.enabled(this)) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(1, notif("MCP 服务运行中 · 端口 " + McpConfig.port(this)));

            // 构造并启动服务器 (幂等)
            if (server == null || !server.isRunning()) {
                server = new McpServer(this, McpConfig.port(this), McpConfig.token(this));
                server.start();
            }
            android.util.Log.i("TaskPro", "MCP server listening on port " + McpConfig.port(this));
        } catch (Exception e) {
            try { android.util.Log.w("TaskPro","MCP service err: "+e.toString()); } catch(Exception __){}
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private void stopServer() {
        try {
            if (server != null) { server.stop(); server = null; }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    private Notification notif(String text) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("TaskPro MCP");
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.stat_notify_sync);
        b.setOngoing(true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}