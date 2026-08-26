package io.taskpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * 开机自启 / 应用更新后: 重新调度所有任务。
 * 安卓重启会清空 AlarmManager 闹钟, 必须重建, 否则任务永远不再触发。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Context app = context.getApplicationContext();
            List<Task> tasks = TaskStore.load(app);
            AlarmScheduler.scheduleAll(app, tasks);
            // 启动高级模式每分钟 cron 检查
            CronAlarmReceiver.startMinuteAlarm(app);
            // 24 小时周期检查 (版本/公告)
            AlarmScheduler.scheduleCheckAlarm(app);
            // 同时刷新小组件
            try {
                WidgetProvider.refresh(app);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }
}
