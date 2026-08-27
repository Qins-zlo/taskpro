package io.taskpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 24 小时周期检查: 静默拉取版本/公告。
 *  - 发现新版本 → 通知 (打开 App 后弹更新框)
 *  - 公告有变化 → 通知 + 存 pending (App 打开时弹窗)
 *  - 每周一次 → 无论公告是否变化都提醒 (存 weekly)
 */
public class CheckReceiver extends BroadcastReceiver {
    /** 语义化版本比较: a>b 返回>0 (正确处理 7.10>7.9) */
    private static int compareVer(String a, String b) {
        String[] pa = (a == null ? "" : a).split("\\.");
        String[] pb = (b == null ? "" : b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? pvi(pa[i]) : 0;
            int y = i < pb.length ? pvi(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }
    private static int pvi(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (Backend.baseUrl(app).isEmpty()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject r = Backend.backgroundCheck(app);
                    SharedPreferences sp = app.getSharedPreferences("check", 0);
                    String curName = "";
                    try {
                        curName = app.getPackageManager()
                                .getPackageInfo(app.getPackageName(), 0).versionName;
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }

                    // 1. 新版本 → 通知 (同版本只提醒一次)
                    JSONObject v = r.optJSONObject("update");
                    if (v != null) {
                        String vn = v.optString("version_name", "");
                        if (!vn.isEmpty() && !vn.equals(curName) && compareVer(vn, curName) > 0) {
                            String reminded = sp.getString("reminded_version", "");
                            if (!reminded.equals(vn)) {
                                sp.edit().putString("reminded_version", vn).apply();
                                Notifier.post(app, "发现新版本 v" + vn,
                                        "点击打开应用查看更新内容");
                            }
                        }
                    }

                    // 2. 公告有变化 → 通知 + 存 pending (App 打开时弹窗)
                    JSONArray arr = r.optJSONArray("announce");
                    if (r.optBoolean("announceChanged") && arr != null && arr.length() > 0) {
                        String h = r.optString("announceHash", "");
                        String title = arr.optJSONObject(0).optString("title", "新公告");
                        sp.edit().putString("pending_announce", arr.toString()).apply();
                        sp.edit().putString("announce_hash", h).apply();
                        Notifier.post(app, "新公告: " + title, "点击查看");
                    }

                    // 3. 每周公告 (不管是否变化, 有公告就提醒一次)
                    long lastWeekly = sp.getLong("last_weekly", 0);
                    if (System.currentTimeMillis() - lastWeekly > 7L * 24 * 3600 * 1000
                            && arr != null && arr.length() > 0) {
                        String title = arr.optJSONObject(0).optString("title", "公告");
                        sp.edit().putLong("last_weekly", System.currentTimeMillis()).apply();
                        sp.edit().putString("weekly_announce", arr.toString()).apply();
                        Notifier.post(app, "每周公告: " + title, "点击查看");
                    }
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        }).start();
    }
}
