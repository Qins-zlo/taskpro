package io.taskpro;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 桌面小组件: 显示下次执行时间与最近状态。点击打开 App。
 */
public class WidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        refresh(context);
    }

    public static void refresh(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(
                    new android.content.ComponentName(context, WidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            List<Task> tasks = TaskStore.load(context);
            // 找下一个要执行的任务
            Task next = null;
            long nextT = Long.MAX_VALUE;
            for (Task t : tasks) {
                if (!t.enabled) continue;
                long nt = AlarmScheduler.nextRun(t);
                if (nt > 0 && nt < nextT) { nextT = nt; next = t; }
            }
            String label, time, task, status;
            int dotColor;
            if (next == null) {
                label = "定时任务 Pro";
                time = "暂无任务";
                task = "去添加一个定时任务吧";
                status = "";
                dotColor = 0xFF9E9E9E;
            } else {
                label = "定时任务 Pro";
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
                time = sdf.format(new java.util.Date(nextT));
                task = "任务: " + next.name;
                status = (next.lastOk ? "上次成功" : "上次失败");
                if (next.streak > 0) status += " · 连签" + next.streak + "天";
                dotColor = next.lastOk ? 0xFF2E9E5B : 0xFFD3452B;
            }
            for (int id : ids) {
                RemoteViews rv = new RemoteViews(context.getPackageName(),
                        R.layout.widget_layout);
                rv.setTextViewText(R.id.w_label, label);
                rv.setTextViewText(R.id.w_time, time);
                rv.setTextViewText(R.id.w_task, task);
                rv.setTextViewText(R.id.w_status, status);
                rv.setInt(R.id.w_dot, "setTextColor", dotColor);
                Intent open = new Intent(context, AdvActivity.class);
                PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(R.id.w_label, pi);
                rv.setOnClickPendingIntent(R.id.w_time, pi);
                rv.setOnClickPendingIntent(R.id.w_task, pi);
                rv.setOnClickPendingIntent(R.id.w_status, pi);
                mgr.updateAppWidget(id, rv);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }
}
