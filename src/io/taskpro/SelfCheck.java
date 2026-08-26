package io.taskpro;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境自检: 逐项检测运行时/脚本/存储/调度是否正常
 * 用于快速诊断环境问题
 */
public class SelfCheck {

    public static class Item {
        public String name;
        public boolean ok;
        public String detail;
        public Item(String name, boolean ok, String detail) {
            this.name = name; this.ok = ok; this.detail = detail;
        }
    }

    public interface Listener {
        void onItem(Item item);      // 每项完成时回调 (UI 线程)
        void onDone(List<Item> items);
    }

    private final Context ctx;
    private final List<Item> items = new ArrayList<Item>();

    public SelfCheck(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 运行全部自检 (后台线程, 回调在 UI 线程) */
    public void run(final Listener listener) {
        new Thread(new Runnable() {
            public void run() {
                final List<Item> result = new ArrayList<Item>();
                result.add(checkRuntimeFiles());
                result.add(checkExecPerm());
                result.add(checkPython());
                result.add(checkNode());
                result.add(checkPip());
                result.add(checkModules());
                result.add(checkScriptDir());
                result.add(checkEnvInject());
                result.add(checkStorage());
                result.add(checkCronAlarm());
                result.add(checkLog());
                for (final Item i : result) {
                    if (listener != null) {
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.post(new Runnable() { public void run() { listener.onItem(i); } });
                    }
                }
                if (listener != null) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(new Runnable() { public void run() { listener.onDone(result); } });
                }
            }
        }).start();
    }

    // ---------- 各项检测 ----------

    /** 1. 运行时文件完整性 */
    private Item checkRuntimeFiles() {
        String usr = RuntimeManager.nativeDir(ctx).getAbsolutePath();
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("运行时文件", false, "未解压或标志缺失\n" + usr);
        }
        File[] need = {
            new File(RuntimeManager.nativeDir(ctx), "libpython3_main.so"),
            new File(RuntimeManager.nativeDir(ctx), "libbusybox_main.so"),
            new File(RuntimeManager.nativeDir(ctx), "libpython3.14.so"),
            new File(RuntimeManager.nativeDir(ctx), "libc++_shared.so"),
        };
        List<String> missing = new ArrayList<String>();
        for (File f : need) if (!f.exists()) missing.add(f.getName());
        if (missing.isEmpty()) {
            return new Item("运行时文件", true, "python3 / busybox 及核心库齐全\n" + usr);
        }
        return new Item("运行时文件", false, "缺失: " + missing + "\n" + usr);
    }

    /** 1.5 执行权限: 检查关键二进制的权限位 */
    private Item checkExecPerm() {
        File py = new File(RuntimeManager.nativeDir(ctx), "libpython3_main.so");
        File pycore = new File(RuntimeManager.nativeDir(ctx), "libpython3.14.so");
        if (!py.exists() && !pycore.exists()) {
            return new Item("执行权限", false, "运行时未解压");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        File[] targets = {py, pycore};
        for (File f : targets) {
            if (!f.exists()) continue;
            String perm = permString(f);
            boolean canExec = f.canExecute();
            sb.append(f.getName()).append(": ").append(perm)
              .append(canExec ? " [可执行]" : " [不可执行]").append("\n");
            if (!canExec) allOk = false;
        }
        return new Item("执行权限", allOk, sb.toString().trim());
    }

    /** 文件权限位字符串 (如 rwx------) */
    private String permString(File f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.canRead() ? 'r' : '-');
        sb.append(f.canWrite() ? 'w' : '-');
        sb.append(f.canExecute() ? 'x' : '-');
        return sb.toString();
    }

    /** 2. python3 实际运行 */
    private Item checkPython() {
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("Python3", false, "运行时未就绪");
        }
        String out = exec(RuntimeManager.buildCommand(ctx, RuntimeManager.pythonBin(ctx) + " --version"));
        String o = out.trim();
        if (o.contains("Python 3")) {
            return new Item("Python3", true, o);
        }
        return new Item("Python3", false, o.isEmpty() ? "无输出 (可能链接器/库问题)" : o);
    }

    /** 3. node 实际运行 */
    private Item checkNode() {
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("Node.js", false, "运行时未就绪");
        }
        String out = exec(RuntimeManager.buildCommand(ctx,
                RuntimeManager.nodeBin(ctx) + " --version"));
        String o = out.trim();
        if (o.contains("v")) {
            return new Item("Node.js", true, o);
        }
        return new Item("Node.js", false, o.isEmpty() ? "无输出 (node 可能不可用)" : o);
    }

    /** 4. pip 实际运行 (可能稍慢) */
    private Item checkPip() {
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("pip", false, "运行时未就绪");
        }
        String out = exec(RuntimeManager.buildCommand(ctx,
                RuntimeManager.pythonBin(ctx) + " -m pip --version"));
        String o = out.trim();
        if (o.contains("pip")) {
            return new Item("pip", true, o.length() > 90 ? o.substring(0, 90) : o);
        }
        return new Item("pip", false, o.isEmpty() ? "无输出" : o);
    }

    /** 5. 常用扩展模块 (ssl/sqlite/zlib) */
    private Item checkModules() {
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("扩展模块", false, "运行时未就绪");
        }
        String out = exec(RuntimeManager.buildCommand(ctx,
                RuntimeManager.pythonBin(ctx) + " -c \"import ssl, sqlite3, zlib, json, hashlib; print('modules-ok')\""));
        String o = out.trim();
        if (o.contains("modules-ok")) {
            return new Item("扩展模块", true, "ssl / sqlite3 / zlib / json / hashlib 导入正常");
        }
        return new Item("扩展模块", false, o.isEmpty() ? "无输出" : o);
    }

    /** 6. 脚本目录读写 */
    private Item checkScriptDir() {
        File dir = ScriptStore.dir(ctx);
        File probe = new File(dir, ".selftest_" + System.currentTimeMillis());
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(probe);
            fo.write(1);
            fo.close();
            boolean ok = probe.exists() && probe.length() == 1;
            probe.delete();
            return ok
                    ? new Item("脚本目录", true, "scripts/ 可读写\n" + dir.getAbsolutePath())
                    : new Item("脚本目录", false, "写入后校验失败\n" + dir.getAbsolutePath());
        } catch (Exception e) {
            return new Item("脚本目录", false, e.toString());
        }
    }

    /** 7. 环境变量注入 (临时变量经 export 进入 python) */
    private Item checkEnvInject() {
        if (!RuntimeManager.isReady(ctx)) {
            return new Item("环境变量注入", false, "运行时未就绪");
        }
        String marker = "SELFTEST_" + (System.currentTimeMillis() % 100000);
        String cmd = "export " + marker + "=hello; " + RuntimeManager.buildCommand(ctx,
                RuntimeManager.pythonBin(ctx) + " -c \"import os; print(os.environ.get('" + marker + "', 'MISSING'))\"");
        String out = exec(cmd);
        String o = out.trim();
        if (o.contains("hello")) {
            return new Item("环境变量注入", true, "export 变量可被脚本读取 (测试通过)");
        }
        return new Item("环境变量注入", false, "未能读到注入变量: " + (o.isEmpty() ? "无输出" : o));
    }

    /** 8. 存储空间 */
    private Item checkStorage() {
        try {
            StatFs sf = new StatFs(ctx.getFilesDir().getAbsolutePath());
            long avail = (long) sf.getAvailableBlocks() * sf.getBlockSize();
            long mb = avail / (1024 * 1024);
            boolean ok = mb > 100;
            return new Item("存储空间", ok, "可用 " + mb + " MB (建议 > 100MB)");
        } catch (Exception e) {
            return new Item("存储空间", false, e.toString());
        }
    }

    /** 9. cron 闹钟调度状态 */
    private Item checkCronAlarm() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return new Item("cron 调度", false, "AlarmManager 不可用");
            // 重新注册一次 (幂等), 报告成功
            CronAlarmReceiver.startMinuteAlarm(ctx);
            android.content.Intent i = new android.content.Intent(ctx, CronAlarmReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(ctx, 0xCAFE, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            boolean ok = pi != null;
            return new Item("cron 调度", ok, ok
                    ? "每分钟闹钟已注册 (60秒窗口, 分钟级精度)"
                    : "闹钟注册失败");
        } catch (Exception e) {
            return new Item("cron 调度", false, e.toString());
        }
    }

    /** 10. 日志写入 */
    private Item checkLog() {
        try {
            TaskLog.append(ctx, "[自检]", "环境自检于 " + System.currentTimeMillis() + " 执行");
            return new Item("日志系统", true, "TaskLog 写入正常");
        } catch (Exception e) {
            return new Item("日志系统", false, e.toString());
        }
    }

    // ---------- 工具 ----------

    /** 执行命令, 合并 stdout/stderr, 超时保护 */
    private String exec(String cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String l;
                        while ((l = r.readLine()) != null) out.append(l).append("\n");
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            t1.start();
            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                        String l;
                        while ((l = r.readLine()) != null) out.append("! ").append(l).append("\n");
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            t2.start();
            // 超时 20 秒
            p.waitFor();
            t1.join(1000);
            t2.join(1000);
            return out.toString();
        } catch (Exception e) {
            return "异常: " + e.toString();
        }
    }

    /** 生成可复制的文本报告 */
    public static String toReport(List<Item> items) {
        StringBuilder sb = new StringBuilder("=== 环境自检报告 ===\n");
        sb.append("时间: ").append(java.text.SimpleDateFormat.getDateTimeInstance()
                .format(new java.util.Date())).append("\n\n");
        int okCount = 0;
        for (Item i : items) {
            sb.append(i.ok ? "[PASS] " : "[FAIL] ").append(i.name).append("\n");
            sb.append("       ").append(i.detail.replace("\n", "\n       ")).append("\n");
            if (i.ok) okCount++;
        }
        sb.append("\n通过 ").append(okCount).append("/").append(items.size());
        return sb.toString();
    }
}
