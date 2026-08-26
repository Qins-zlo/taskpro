package io.taskpro.md;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.LinearLayout;

/**
 * Material 3 卡片容器: 圆角 12dp, 可选描边/填充/涟漪。
 */
public class MdCard extends LinearLayout {
    public static final int FILLED = 0;   // surfaceContainer 填充
    public static final int OUTLINED = 1; // surface + outline 描边
    public static final int ELEVATED = 2; // 白色 + 阴影

    public MdCard(Context c, int variant, boolean clickable) {
        super(c);
        setOrientation(LinearLayout.VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(14));
        int radius = dp(12);
        GradientDrawable g = new GradientDrawable();
        if (variant == OUTLINED) {
            g.setColor(MdTheme.surface(c));
            g.setStroke(dp(1), MdTheme.outlineVariant(c));
        } else if (variant == FILLED) {
            g.setColor(MdTheme.surfaceContainerHigh(c));
        } else {
            g.setColor(MdTheme.surface(c));
        }
        g.setCornerRadius(radius);
        if (clickable) {
            RippleDrawable r = new RippleDrawable(
                    ColorStateList.valueOf(MdTheme.primary(c) & 0x33FFFFFF),
                    g, null);
            setBackground(r);
        } else {
            setBackground(g);
        }
        if (variant == ELEVATED && !MdTheme.isDark(c)) {
            setElevation(dp(2));
        }
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
