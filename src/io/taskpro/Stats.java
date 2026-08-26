package io.taskpro;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 执行统计: 按天记录成功/失败次数 (基础模式 + 高级模式统一) */
public class Stats {
    private static File f(Context ctx) {
        return new File(ctx.getFilesDir(), "stats.json");
    }

    /** 记录一次执行结果 */
    public static synchronized void record(Context ctx, boolean ok) {
        try {
            JSONObject root = new JSONObject();
            File file = f(ctx);
            if (file.exists()) {
                root = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
            }
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            JSONObject day = root.optJSONObject(today);
            if (day == null) day = new JSONObject();
            day.put("ok", day.optInt("ok", 0) + (ok ? 1 : 0));
            day.put("fail", day.optInt("fail", 0) + (ok ? 0 : 1));
            root.put(today, day);
            // 只保留最近 45 天, 防止无限增长
            java.util.Iterator<String> it = root.keys();
            java.util.List<String> dates = new java.util.ArrayList<String>();
            while (it.hasNext()) dates.add(it.next());
            java.util.Collections.sort(dates);
            while (dates.size() > 45) root.remove(dates.remove(0));
            FileOutputStream out = new FileOutputStream(file);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 近 N 天: 返回 [{date:"MM-dd", ok, fail}], 无记录日期填 0 */
    public static JSONArray lastDays(Context ctx, int n) {
        JSONArray out = new JSONArray();
        try {
            JSONObject root = new JSONObject();
            File file = f(ctx);
            if (file.exists()) {
                root = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            for (int i = n - 1; i >= 0; i--) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -i);
                String d = sdf.format(cal.getTime());
                JSONObject day = root.optJSONObject(d);
                JSONObject o = new JSONObject();
                o.put("date", d.substring(5));
                o.put("ok", day == null ? 0 : day.optInt("ok", 0));
                o.put("fail", day == null ? 0 : day.optInt("fail", 0));
                out.put(o);
                cal.add(java.util.Calendar.DAY_OF_YEAR, i);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return out;
    }
}
