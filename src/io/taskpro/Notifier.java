package io.taskpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 通知助手(利用系统 NotificationManager 静态方法, 不触发权限对话框)
 */
public class Notifier {
    public static final String CHANNEL = "tasks";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CHANNEL, "任务执行",
                    NotificationManager.IMPORTANCE_DEFAULT);
            c.setDescription("定时任务执行结果");
            nm.createNotificationChannel(c);
        }
    }

    /**
     * 稳定的通知 ID: 对标题做 hash, 保证"同一任务/同一脚本"的通知互相替换,
     * 不会因为每次都 notify(时间戳) 导致通知栏堆积刷屏。
     * 不同任务/脚本的标题不同 → ID 不同 → 各自独立通知。
     */
    private static int stableId(String title) {
        int h = 0;
        if (title != null) {
            for (int i = 0; i < title.length(); i++) h = h * 31 + title.charAt(i);
        }
        return 1000 + Math.abs(h) % 1000000;
    }
    public static void post(Context ctx, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean granted = ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
                if (!granted) return; // 未授权则跳过, 避免 SecurityException
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_sync);
            b.setContentTitle(title);
            b.setContentText(text);
            b.setAutoCancel(true);
            // 点击通知打开主界面 (按当前模式: 有高级模式入口则打开 AdvActivity, 否则 MainActivity)
            try {
                Intent i;
                try {
                    Class<?> cls = Class.forName("io.taskpro.AdvActivity");
                    i = new Intent(ctx, cls);
                } catch (ClassNotFoundException e) {
                    i = new Intent(ctx, MainActivity.class);
                }
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, stableId(title), i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.setContentIntent(pi);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(stableId(title), b.build());
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 脚本执行结果通知: 输出尾部摘要 + 退出码 */
    public static void postOutput(Context ctx, String title, String output, int code) {
        String tail = tail(output, 700);
        String text = tail.isEmpty() ? "脚本执行完成"
                : tail + "\n\n(退出码 " + code + " · 点开查看 App)";
        post(ctx, title, text);
    }

    /** 取输出尾部 max 字符 (尽量从行首开始) */
    private static String tail(String s, int max) {
        if (s == null) return "";
        while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.isEmpty()) return "";
        if (s.length() <= max) return s;
        String cut = s.substring(s.length() - max);
        int nl = cut.indexOf('\n');
        if (nl > 0 && nl < max / 2) cut = cut.substring(nl + 1);
        return "…(前略)\n" + cut;
    }
}
