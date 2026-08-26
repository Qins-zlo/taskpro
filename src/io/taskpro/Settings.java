package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局设置。
 */
public class Settings {
    private static final String PREFS = "taskrun_settings";

    public static boolean notifyOnSuccess(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("notify_success", false);
    }
    public static void setNotifyOnSuccess(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("notify_success", v).apply();
    }

    /** 脚本定时执行结果通知 (默认开): 执行完成后把脚本输出推送到通知栏 */
    public static boolean notifyScriptCron(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("notify_script_cron", true);
    }
    public static void setNotifyScriptCron(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("notify_script_cron", v).apply();
    }

    /** 自动导出产物到 Download (默认开): 脚本运行完产物自动复制到 Download/<脚本名>/ */
    public static boolean autoExportArtifacts(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("auto_export_artifacts", true);
    }
    public static void setAutoExportArtifacts(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("auto_export_artifacts", v).apply();
    }
}
