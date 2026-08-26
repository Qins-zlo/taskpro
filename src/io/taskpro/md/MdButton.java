package io.taskpro.md;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Material 3 按钮。变体: FILLED / TONAL / OUTLINED / TEXT / ERROR
 * 高度 40dp, 圆角 20dp, 带涟漪。
 */
public class MdButton extends TextView {
    public static final int FILLED = 0;
    public static final int TONAL = 1;
    public static final int OUTLINED = 2;
    public static final int TEXT = 3;
    public static final int ERROR = 4;

    public MdButton(Context c, String label, int variant) {
        super(c);
        setText(label);
        setGravity(Gravity.CENTER);
        setTextSize(14);
        setTypeface(Typeface.DEFAULT_BOLD);
        int h = (int) (c.getResources().getDisplayMetrics().density * 40 + 0.5f);
        setHeight(h);
        setMinHeight(h);
        setPadding(dp(16), 0, dp(16), 0);
        setAllCaps(false);
        applyVariant(variant);
    }

    public void setIcon(android.graphics.drawable.Drawable d) {
        setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        setCompoundDrawablePadding(dp(6));
    }

    public void applyVariant(int variant) {
        Context c = getContext();
        int fg, bg, stroke = 0;
        switch (variant) {
            case FILLED:
                fg = MdTheme.onPrimary(c);
                bg = MdTheme.primary(c);
                break;
            case TONAL:
                fg = MdTheme.onSecondaryContainer(c);
                bg = MdTheme.secondaryContainer(c);
                break;
            case OUTLINED:
                fg = MdTheme.primary(c);
                bg = Color.TRANSPARENT;
                stroke = MdTheme.outline(c);
                break;
            case TEXT:
                fg = MdTheme.primary(c);
                bg = Color.TRANSPARENT;
                break;
            default: // ERROR
                fg = MdTheme.onErrorContainer(c);
                bg = MdTheme.errorContainer(c);
                break;
        }
        setTextColor(fg);
        setBg(bg, stroke);
    }

    private void setBg(int fill, int stroke) {
        Context c = getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(20));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        RippleDrawable r = new RippleDrawable(
                ColorStateList.valueOf(adjustAlpha(fill, 0.28f)), g, null);
        setBackground(r);
    }

    static int adjustAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
