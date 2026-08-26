package io.taskpro;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

/**
 * 用 AlarmManager setExactAndAllowWhileIdle 调度每个 enabled 任务在其设定时刻触发。
 */
public class AlarmScheduler {

    /** requestCode: 用 data URI 区分, 彻底消除哈希碰撞 (不同任务 Intent 不同 → PendingIntent 不同) */
    static PendingIntent pending(Context ctx, String taskId) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("io.taskpro.RUN");
        i.setData(android.net.Uri.parse("taskpro://run/" + taskId));
        i.putExtra("taskId", taskId);
        return PendingIntent.getBroadcast(ctx, 1000, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 失败重试: 延迟 delayMin 分钟后一次性触发(不重排下次) */
    public static void scheduleRetry(Context ctx, Task task, int retryLeft) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + task.retryDelayMin * 60000L;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("io.taskpro.RETRY");
        i.setData(android.net.Uri.parse("taskpro://retry/" + task.id + "/" + retryLeft));
        i.putExtra("taskId", task.id);
        i.putExtra("retryLeft", retryLeft);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void schedule(Context ctx, Task task) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        cancel(ctx, task.id);
        if (!task.enabled) return;
        long next = nextRun(task);
        if (next <= 0) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(ctx, task.id));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, next, pending(ctx, task.id));
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, next, pending(ctx, task.id));
        }
    }

    public static void scheduleAll(Context ctx, List<Task> tasks) {
        for (Task t : tasks) schedule(ctx, t);
    }

    public static void cancel(Context ctx, String taskId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, taskId));
    }

    public static void cancelAll(Context ctx, List<Task> tasks) {
        for (Task t : tasks) cancel(ctx, t.id);
    }

    static long nextRun(Task task) {
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < 8; i++) {
            Calendar cand = (Calendar) now.clone();
            cand.set(Calendar.HOUR_OF_DAY, task.hour);
            cand.set(Calendar.MINUTE, task.minute);
            cand.set(Calendar.SECOND, 0);
            cand.set(Calendar.MILLISECOND, 0);
            cand.add(Calendar.DAY_OF_YEAR, i);
            int dow = cand.get(Calendar.DAY_OF_WEEK);
            int dowNum = (dow == Calendar.SUNDAY) ? 7 : dow - 1;
            if (task.onDay(dowNum) && cand.after(now)) {
                return cand.getTimeInMillis();
            }
        }
        return -1;
    }

    /** 24 小时周期检查闹钟 (版本/公告静默检查) */
    public static void scheduleCheckAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, CheckReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long interval = 24L * 3600 * 1000;
            long trigger = System.currentTimeMillis() + interval;
            am.setInexactRepeating(AlarmManager.RTC, trigger, interval, pi);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }
}
