package io.taskpro;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.taskpro.md.MdButton;
import io.taskpro.md.MdTheme;

public class UIHelper {
    public static int dp(Activity a, int d) {
        return (int) (d * a.getResources().getDisplayMetrics().density + 0.5f);
    }
    public static String truncate(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }
    public static View smallBtn(final Activity a, String text, boolean danger, final Runnable action) {
        MdButton btn = new MdButton(a, text, MdButton.TEXT);
        if (danger) btn.setTextColor(MdTheme.error(a));
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(a, 4);
        btn.setLayoutParams(lp);
        return btn;
    }
    public static View line(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(MdTheme.outlineVariant(a));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }
    public static View backRow(Activity a, String text, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        row.setBackgroundColor(MdTheme.surfaceContainerHigh(a));
        TextView t = new TextView(a);
        t.setText("\u2190 " + text);
        t.setTextColor(MdTheme.primary(a));
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        return row;
    }
    public static String safeDirName(String name) {
        if (name == null) return "unknown";
        String s = name.replaceAll("[^a-zA-Z0-9_\\-.]", "_").replaceAll("_{2,}", "_");
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "unknown" : s;
    }
    public static String humSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
    public static boolean networkOk(Activity a) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) a.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return false; }
    }
    public static void waitNetwork(int maxSec) {
        for (int i = 0; i < maxSec * 2; i++) {
            try { if (java.net.InetAddress.getByName("8.8.8.8").isReachable(1000)) return; } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            try { Thread.sleep(500); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }
}
