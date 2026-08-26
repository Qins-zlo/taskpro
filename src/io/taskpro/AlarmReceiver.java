package io.taskpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.PowerManager;

import java.util.List;

/**
 * 闹钟接收器:
 *  - 正常触发(RUN): 执行任务, 更新统计; 失败则安排延迟重试(RETRY); 成功按设置静默/通知
 *  - 重试触发(RETRY): 再次执行, 剩余次数减一
 *  执行完重排该任务的下一轮正常闹钟。
 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final String taskId = intent.getStringExtra("taskId");
        final int retryLeft = intent.getIntExtra("retryLeft", -1); // -1 = 正常触发
        if (taskId == null) {
            AlarmScheduler.scheduleAll(context, TaskStore.load(context));
            return;
        }
        final Context app = context.getApplicationContext();
        TaskEngine.appContext = app;
        Notifier.ensureChannel(app);
        final boolean isRetry = retryLeft >= 0;

        // 重排下一轮正常闹钟(重试不重排)
        if (!isRetry) {
            AlarmScheduler.scheduleAll(app, TaskStore.load(app));
        }

        // P0 修复: 走前台服务执行, 豁免 App Standby 后台网络限制 (否则凌晨自动执行 DNS 全挂)
        Intent svc = new Intent(app, ExecService.class);
        svc.putExtra("mode", "task");
        svc.putExtra("taskId", taskId);
        svc.putExtra("retryLeft", retryLeft);
        svc.putExtra("isRetry", isRetry);
        boolean started = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(svc);
            } else {
                app.startService(svc);
            }
            started = true;
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        if (started) return;

        // 兜底: 服务启动失败时直接执行 (原逻辑)
        final PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "taskpro:exec");
        wl.setReferenceCounted(false);
        wl.acquire(15 * 60 * 1000L);
        final List<Task> tasks = TaskStore.load(app);
        Task target = null;
        for (Task t : tasks) if (t.id.equals(taskId)) { target = t; break; }
        if (target == null) {
            AlarmScheduler.scheduleAll(app, tasks);
            return;
        }
        final Task task = target;
        final boolean canRetry = isRetry ? (retryLeft > 0) : (task.retryTimes > 0);
        final int retryCount = retryLeft; // 传给线程

        // goAsync: 保证 onReceive 返回后进程不被回收, 线程执行完再 finish()
        final PendingResult pendingResult = goAsync();

        new Thread(new Runnable() {
            public void run() {
                try {
                    runTask(app, task, tasks, isRetry, canRetry, retryCount);
                } catch (Throwable t) {
                    try {
                        TaskLog.append(app, task.name, "异常: " + t.toString());
                        Notifier.post(app, task.name + " 执行异常", t.toString());
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                } finally {
                    try { pendingResult.finish(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    try { wl.release(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            }
        }).start();

        // 重排下一轮正常闹钟(重试不重排)
        if (!isRetry) {
            AlarmScheduler.scheduleAll(app, tasks);
        }
    }

    static void runTask(Context app, Task task, List<Task> tasks,
                         boolean isRetry, boolean canRetry, int retryLeft) {
        StringBuilder log = new StringBuilder();
        // 网络预检: 无可用网络则顺延执行, 避免必失败的请求
        if (!isNetworkAvailable(app)) {
            try {
                TaskLog.append(app, task.name, "网络不可用, 顺延 " + task.retryDelayMin + " 分钟后重试");
                AlarmScheduler.scheduleRetry(app, task, Math.max(1, task.retryTimes));
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            return;
        }
        TaskEngine.Result r = TaskEngine.run(task, new TaskEngine.Logger() {
            public void log(String line) { log.append(line).append("\n"); }
        });
        boolean ok = r.ok;
        String summary = r.summary;
        long now = System.currentTimeMillis();

        // 执行统计 (30 天成功率)
        Stats.record(app, ok);

        // 更新任务统计(连续成功天数: 粗粒度按最近执行成功累积)
        task.lastOk = ok;
        task.lastRunAt = now;
        task.lastResult = summary;
        if (ok) {
            task.streak = task.streak + 1;
        } else {
            task.streak = 0;
        }
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id.equals(task.id)) { tasks.set(i, task); break; }
        }
        TaskStore.save(app, tasks);
        // 刷新小组件
        try { WidgetProvider.refresh(app); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }

        String tag = isRetry ? "(重试)" : "";
        String logName = task.name + tag;
        TaskLog.append(app, logName, summary + "\n" + log.toString());

        if (ok) {
            // 成功: 按设置决定是否通知
            if (Settings.notifyOnSuccess(app)) {
                Notifier.post(app, task.name + " 执行成功", summary);
            }
        } else {
            if (canRetry) {
                // 安排重试
                int left = isRetry ? retryLeft - 1 : task.retryTimes - 1;
                AlarmScheduler.scheduleRetry(app, task, left);
                Notifier.post(app, task.name + " 执行失败, 即将重试",
                        summary + "\n" + task.retryDelayMin + " 分钟后第 "
                        + (task.retryTimes - left) + " 次重试");
            } else {
                Notifier.post(app, task.name + " 执行失败", summary
                        + "\n(若定时执行持续失败: 设置→应用→定时任务Pro→电池→无限制)");
            }
        }
    }

    private static boolean isNetworkAvailable(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return true; }
    }
}
