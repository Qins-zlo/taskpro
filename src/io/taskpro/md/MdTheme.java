package io.taskpro.md;

import android.content.Context;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Material 3 主题色板(浅色/深色)。基于 M3 baseline 紫色系。
 */
public class MdTheme {
    // ---------- 浅色 ----------
    public static final int L_PRIMARY = 0xFF6750A4;
    public static final int L_ON_PRIMARY = 0xFFFFFFFF;
    public static final int L_PRIMARY_CONTAINER = 0xFFEADDFF;
    public static final int L_ON_PRIMARY_CONTAINER = 0xFF21005D;
    public static final int L_SECONDARY = 0xFF625B71;
    public static final int L_ON_SECONDARY = 0xFFFFFFFF;
    public static final int L_SECONDARY_CONTAINER = 0xFFE8DEF8;
    public static final int L_ON_SECONDARY_CONTAINER = 0xFF1D192B;
    public static final int L_TERCIARY = 0xFF7D5260;
    public static final int L_SURFACE = 0xFFFEF7FF;
    public static final int L_SURFACE_VARIANT = 0xFFE7E0EC;
    public static final int L_ON_SURFACE = 0xFF1D1B20;
    public static final int L_ON_SURFACE_VARIANT = 0xFF49454F;
    public static final int L_OUTLINE = 0xFF79747E;
    public static final int L_OUTLINE_VARIANT = 0xFFCAC4D0;
    public static final int L_ERROR = 0xFFB3261E;
    public static final int L_ON_ERROR = 0xFFFFFFFF;
    public static final int L_ERROR_CONTAINER = 0xFFF9DEDC;
    public static final int L_ON_ERROR_CONTAINER = 0xFF410E0B;
    public static final int L_SURFACE_CONTAINER = 0xFFF3EDF7;
    public static final int L_SURFACE_CONTAINER_HIGH = 0xFFECE6F0;

    // ---------- 深色 ----------
    public static final int D_PRIMARY = 0xFFD0BCFF;
    public static final int D_ON_PRIMARY = 0xFF381E72;
    public static final int D_PRIMARY_CONTAINER = 0xFF4F378B;
    public static final int D_ON_PRIMARY_CONTAINER = 0xFFEADDFF;
    public static final int D_SECONDARY = 0xFFCCC2DC;
    public static final int D_ON_SECONDARY = 0xFF332D41;
    public static final int D_SECONDARY_CONTAINER = 0xFF4A4458;
    public static final int D_ON_SECONDARY_CONTAINER = 0xFFE8DEF8;
    public static final int D_TERCIARY = 0xFFEFB8C8;
    public static final int D_SURFACE = 0xFF141218;
    public static final int D_SURFACE_VARIANT = 0xFF49454F;
    public static final int D_ON_SURFACE = 0xFFE6E0E9;
    public static final int D_ON_SURFACE_VARIANT = 0xFFCAC4D0;
    public static final int D_OUTLINE = 0xFF938F99;
    public static final int D_OUTLINE_VARIANT = 0xFF49454F;
    public static final int D_ERROR = 0xFFF2B8B5;
    public static final int D_ON_ERROR = 0xFF601410;
    public static final int D_ERROR_CONTAINER = 0xFF8C1D18;
    public static final int D_ON_ERROR_CONTAINER = 0xFFF9DEDC;
    public static final int D_SURFACE_CONTAINER = 0xFF211F26;
    public static final int D_SURFACE_CONTAINER_HIGH = 0xFF2B2930;

    public static boolean isDark(Context c) {
        int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    // 主题色自定义
    private static final String ACCENT_PREFS = "md_theme_accent";
    public static void setAccent(Context c, int color) {
        c.getSharedPreferences(ACCENT_PREFS, Context.MODE_PRIVATE).edit().putInt("accent", color).apply();
    }
    public static int getAccent(Context c) {
        return c.getSharedPreferences(ACCENT_PREFS, Context.MODE_PRIVATE).getInt("accent", 0);
    }
    private static int mix(int c1, int c2, float w1) {
        float r = Color.red(c1) * w1 + Color.red(c2) * (1f - w1);
        float g = Color.green(c1) * w1 + Color.green(c2) * (1f - w1);
        float b = Color.blue(c1) * w1 + Color.blue(c2) * (1f - w1);
        return Color.rgb(Math.round(r), Math.round(g), Math.round(b));
    }
    public static int primary(Context c) {
        int a = getAccent(c);
        if (a != 0) return a;
        return isDark(c) ? D_PRIMARY : L_PRIMARY;
    }
    public static int onPrimary(Context c) { return isDark(c) ? D_ON_PRIMARY : L_ON_PRIMARY; }
    public static int primaryContainer(Context c) {
        int a = getAccent(c);
        if (a != 0) {
            return isDark(c) ? mix(a, D_SURFACE, 0.32f) : mix(a, L_SURFACE, 0.14f);
        }
        return isDark(c) ? D_PRIMARY_CONTAINER : L_PRIMARY_CONTAINER;
    }
    public static int onPrimaryContainer(Context c) { return isDark(c) ? D_ON_PRIMARY_CONTAINER : L_ON_PRIMARY_CONTAINER; }
    public static int secondary(Context c) { return isDark(c) ? D_SECONDARY : L_SECONDARY; }
    public static int onSecondary(Context c) { return isDark(c) ? D_ON_SECONDARY : L_ON_SECONDARY; }
    public static int secondaryContainer(Context c) { return isDark(c) ? D_SECONDARY_CONTAINER : L_SECONDARY_CONTAINER; }
    public static int onSecondaryContainer(Context c) { return isDark(c) ? D_ON_SECONDARY_CONTAINER : L_ON_SECONDARY_CONTAINER; }
    public static int surface(Context c) { return isDark(c) ? D_SURFACE : L_SURFACE; }
    public static int surfaceVariant(Context c) { return isDark(c) ? D_SURFACE_VARIANT : L_SURFACE_VARIANT; }
    public static int onSurface(Context c) { return isDark(c) ? D_ON_SURFACE : L_ON_SURFACE; }
    public static int onSurfaceVariant(Context c) { return isDark(c) ? D_ON_SURFACE_VARIANT : L_ON_SURFACE_VARIANT; }
    public static int outline(Context c) { return isDark(c) ? D_OUTLINE : L_OUTLINE; }
    public static int outlineVariant(Context c) { return isDark(c) ? D_OUTLINE_VARIANT : L_OUTLINE_VARIANT; }
    public static int error(Context c) { return isDark(c) ? D_ERROR : L_ERROR; }
    public static int onError(Context c) { return isDark(c) ? D_ON_ERROR : L_ON_ERROR; }
    public static int errorContainer(Context c) { return isDark(c) ? D_ERROR_CONTAINER : L_ERROR_CONTAINER; }
    public static int onErrorContainer(Context c) { return isDark(c) ? D_ON_ERROR_CONTAINER : L_ON_ERROR_CONTAINER; }
    public static int surfaceContainer(Context c) { return isDark(c) ? D_SURFACE_CONTAINER : L_SURFACE_CONTAINER; }
    public static int surfaceContainerHigh(Context c) { return isDark(c) ? D_SURFACE_CONTAINER_HIGH : L_SURFACE_CONTAINER_HIGH; }
}
