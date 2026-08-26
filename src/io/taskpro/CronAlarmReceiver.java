package io.taskpro;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * 每分钟触发一次, 检查高级模式的 cron 任务是否到点。
 * 注册为 manifest receiver, 由 AlarmScheduler 的每分钟闹钟驱动。
 */
public class CronAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        String action = intent == null ? "" : intent.getAction();
        // cron 脚本失败重试: 一次性闹钟触发; 网络不可用则顺延下一档, 不硬跑
        if ("io.taskpro.CRON_RETRY".equals(action)) {
            final String scriptName = intent.getStringExtra("scriptName");
            final String tag = intent.getStringExtra("tag");
            final int attempt = intent.getIntExtra("attempt", 1);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        // 等网络恢复 (夜间断网窗口可能很长, 最多等 25 秒)
                        AdvActivity.waitNetwork(25);
                        if (AdvActivity.networkOk()) {
                            AdvActivity.startExec(app, scriptName, tag, attempt);
                        } else if (attempt < 6) {
                            // 网络仍未恢复: 顺延到下一档, 不消耗执行机会
                            AdvActivity.scheduleCronRetry(app, scriptName, tag, attempt + 1);
                            TaskLog.append(app, "[cron] " + tag,
                                    "网络仍不可用, " + AdvActivity.CRON_RETRY_DELAYS[attempt] + " 分钟后继续等");
                        } else {
                            TaskLog.append(app, "[cron] " + tag,
                                    "网络长时间不可用, 放弃重试 | " + AdvActivity.netDiag(app));
                        }
                    } catch (Throwable t) {
                        TaskLog.append(app, "[cron] " + tag, "重试异常: " + t.toString());
                    } finally {
                        try { pr.finish(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                }
            }).start();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    AdvActivity.checkCron(app, Calendar.getInstance());
                } catch (Throwable t) {
                    TaskLog.append(app, "[cron]", "异常: " + t.toString());
                } finally {
                    // P0: 无论成功/失败都重排下一次闹钟, 否则一次性闹钟触发后永久失效
                    try { CronAlarmReceiver.reschedule(app); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    try { pr.finish(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            }
        }).start();
    }

    /** 注册每分钟循环闹钟 */
    public static void startMinuteAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, CronAlarmReceiver.class);
        i.setAction("io.taskpro.CRON_TICK");
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0xCAFE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long now = System.currentTimeMillis();
        long nextMin = (now / 60000 + 1) * 60000; // 下一个整分钟
        // 用 setWindow: 60秒窗口内触发, 无需精确闹钟权限, 不受 Android 12+ 限流
        // cron 是分钟级精度, 窗口内延迟可接受
        try {
            am.setWindow(AlarmManager.RTC_WAKEUP, nextMin, 60000, pi);
        } catch (SecurityException e) {
            // 兜底: 普通 set (可能延迟, 但不会崩)
            am.set(AlarmManager.RTC_WAKEUP, nextMin, pi);
        }
    }

    /** 重排下一次(每分钟) */
    public static void reschedule(Context ctx) {
        // 由于 PendingIntent 是 FLAG_UPDATE_CURRENT, 下次 set 自动覆盖; 每次触发后重排
        startMinuteAlarm(ctx);
    }
}
