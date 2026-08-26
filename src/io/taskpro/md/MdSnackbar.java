package io.taskpro.md;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 Snackbar: 底部浮出圆角深色条, 自动消失, 替代 Toast。
 */
public class MdSnackbar {
    private static Handler h = new Handler(Looper.getMainLooper());

    public static void show(View anchor, String msg) {
        show(anchor, msg, 2600);
    }

    public static void show(final View anchor, final String msg, int duration) {
        h.post(new Runnable() {
            public void run() {
                try {
                    Context c = anchor.getContext();
                    ViewGroup root = (ViewGroup) anchor.getRootView();
                    FrameLayout host = findHost(root);
                    if (host == null) return;
                    final TextView tv = new TextView(c);
                    tv.setText(msg);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(13);
                    tv.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(MdTheme.isDark(c) ? 0xFF332D41 : 0xFF322F35);
                    g.setCornerRadius(dp(c, 6));
                    tv.setBackground(g);
                    tv.setTypeface(Typeface.DEFAULT);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.BOTTOM;
                    lp.leftMargin = dp(c, 16);
                    lp.rightMargin = dp(c, 16);
                    lp.bottomMargin = dp(c, 88);
                    host.addView(tv, lp);
                    tv.setAlpha(0f);
                    tv.animate().alpha(1f).setDuration(180).start();
                    h.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                tv.animate().alpha(0f).setDuration(200)
                                        .withEndAction(new Runnable() {
                                            public void run() {
                                                ViewGroup p = (ViewGroup) tv.getParent();
                                                if (p != null) p.removeView(tv);
                                            }
                                        }).start();
                            } catch (Exception ignored) {}
                        }
                    }, duration);
                } catch (Exception ignored) {}
            }
        });
    }

    private static FrameLayout findHost(ViewGroup root) {
        if (root instanceof FrameLayout) return (FrameLayout) root;
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v instanceof FrameLayout) return (FrameLayout) v;
        }
        return null;
    }

    private static int dp(Context c, int v) {
        return (int) (c.getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
