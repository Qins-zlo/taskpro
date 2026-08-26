package io.taskpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 前台服务: 后台自动执行脚本时持前台通知,
 * 豁免 Android App Standby / Doze 对后台进程的网络限制
 * (受限时 netd 拒绝 DNS 解析, 表现为脚本全部 "Could not resolve host")
 */
public class ExecService extends Service {
    private static final String CH = "exec_run";

    @Override
    public void onCreate() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationChannel c = new NotificationChannel(CH, "脚本执行", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String mode = intent.getStringExtra("mode");

        // 基础模式任务: 前台服务执行 TaskEngine (豁免 App Standby 网络限制)
        if ("task".equals(mode)) {
            final String taskId = intent.getStringExtra("taskId");
            final int retryLeft = intent.getIntExtra("retryLeft", -1);
            final boolean isRetry = intent.getBooleanExtra("isRetry", false);
            if (taskId == null) { stopSelf(); return START_NOT_STICKY; }
            try {
                startForeground(1, notif("正在执行定时任务"));
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.util.List<Task> tasks = TaskStore.load(ExecService.this);
                        Task target = null;
                        for (Task t : tasks) {
                            if (t.id != null && t.id.equals(taskId)) { target = t; break; }
                        }
                        if (target != null) {
                            TaskEngine.appContext = ExecService.this;
                            Notifier.ensureChannel(ExecService.this);
                            if (isRetry) {
                                AlarmReceiver.runTask(ExecService.this, target, tasks,
                                        true, retryLeft > 0, retryLeft);
                            } else {
                                AlarmReceiver.runTask(ExecService.this, target, tasks,
                                        false, target.retryTimes > 0, -1);
                            }
                        }
                    } catch (Throwable t) {
                        TaskLog.append(ExecService.this, "[任务]", "执行异常: " + t.toString());
                    } finally {
                        try { stopSelf(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                }
            }).start();
            return START_NOT_STICKY;
        }

        // 高级模式脚本
        final String scriptName = intent.getStringExtra("scriptName");
        final String tag = intent.getStringExtra("tag");
        final int attempt = intent.getIntExtra("attempt", -1);
        if (scriptName == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(1, notif("正在执行: " + tag));
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        new Thread(new Runnable() {
            public void run() {
                AdvActivity.runScriptByName(ExecService.this, scriptName, tag, attempt, new Runnable() {
                    public void run() {
                        try { stopSelf(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                });
            }
        }).start();
        return START_NOT_STICKY;
    }

    private Notification notif(String text) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("定时任务Pro");
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.stat_notify_sync);
        b.setOngoing(true);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
