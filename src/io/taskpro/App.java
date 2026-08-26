package io.taskpro;

import android.app.Application;

/** 全局异常捕获: 崩溃堆栈写入 files/crash.log, 可在"更多→错误日志"查看 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                saveCrash(t, e);
                // 简单退出, 避免残留 ANR 弹窗; 下次启动恢复正常
                try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        });
    }

    private void saveCrash(Thread t, Throwable e) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "crash.log");
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date())).append("] ").append(t.getName()).append("\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw.toString()).append("\n---\n");
            String old = "";
            if (f.exists()) {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] b = new byte[(int) f.length()];
                    int off = 0;
                    while (off < b.length) {
                        int r = in.read(b, off, b.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                    in.close();
                    old = new String(b, 0, off, "UTF-8");
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
            String all = old + sb.toString();
            if (all.length() > 200000) all = all.substring(all.length() - 200000);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(all.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }
}
