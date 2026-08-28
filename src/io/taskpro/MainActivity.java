package io.taskpro;

import android.content.ClipData;
import android.content.SharedPreferences;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import io.taskpro.md.MdButton;
import io.taskpro.md.MdCard;
import io.taskpro.md.MdDialog;
import io.taskpro.md.MdSnackbar;
import io.taskpro.md.MdSwitch;
import io.taskpro.md.MdTextField;
import io.taskpro.md.MdTheme;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面 v1.3:
 *  - 底部固定 3 Tab(任务/日志/更多) + 悬浮添加按钮(FAB)
 *  - 深色模式(跟随系统)
 *  - 任务卡片带状态行(上次结果/下次执行/连续天数)
 *  - 日志页一键复制
 *  - 更多页: 引导/白名单/开发文档/帮助/成功通知开关/导入导出
 */
public class MainActivity extends android.app.Activity {
    private LinearLayout root;
    private FrameLayout contentBox;   // 当前页面容器
    private Context ctx = this;
    private List<Task> tasks;
    private int currentTab = 0; // 0任务 1日志 2更多

    // 配色(浅色)
    private static final int INK = 0xFF111111;
    private static final int GRAY = 0xFF6B6B6B;
    private static final int GRAY2 = 0xFF9A9A9A;
    private static final int LINE = 0xFFE5E5E5;
    private static final int BG = 0xFFF5F5F5;
    private static final int CARD = 0xFFFFFFFF;
    private static final int RED = 0xFFDC2626;
    private static final int GREEN = 0xFF16A34A;
    private static final int ACCENT = 0xFF2563EB;
    // 深色
    private static final int INK_D = 0xFFE8E8E8;
    private static final int GRAY_D = 0xFF9A9A9A;
    private static final int GRAY2_D = 0xFF6B6B6B;
    private static final int LINE_D = 0xFF2A2A2A;
    private static final int BG_D = 0xFF121212;
    private static final int CARD_D = 0xFF1E1E1E;
    private static final int RED_D = 0xFFF87171;
    private static final int GREEN_D = 0xFF4ADE80;
    private static final int ACCENT_D = 0xFF60A5FA;

    private boolean isDark() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }
    private int ink() { return isDark() ? INK_D : INK; }
    private int gray() { return isDark() ? GRAY_D : GRAY; }
    private int gray2() { return isDark() ? GRAY2_D : GRAY2; }
    private int line() { return isDark() ? LINE_D : LINE; }
    private int bg() { return isDark() ? BG_D : BG; }
    private int card() { return isDark() ? CARD_D : CARD; }
    private int red() { return isDark() ? RED_D : RED; }
    private int green() { return isDark() ? GREEN_D : GREEN; }
    private int accent() { return isDark() ? ACCENT_D : ACCENT; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        TaskEngine.appContext = getApplicationContext();
        Notifier.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        tasks = TaskStore.load(this);
        AlarmScheduler.scheduleAll(this, tasks);
        // 确保高级模式 cron 分钟闹钟在运行
        try { CronAlarmReceiver.startMinuteAlarm(this); } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        // 后台静默准备运行时 (不影响 UI, cron 脚本执行需要)
        ensureRuntimeBackground();
        buildUI();
        // 电池优化检测 (后台执行可靠性核心)
        root.postDelayed(new Runnable() {
            public void run() {
                consumePendingChecks();       // 先消费后台 24h 检查结果 (新公告/每周公告)
                checkBatteryOptimization();   // 电池优化引导
                if (!Backend.baseUrl(MainActivity.this).isEmpty()) {
                    doBackendCheck(true);     // 实时检查 (新版本弹窗)
                    AlarmScheduler.scheduleCheckAlarm(MainActivity.this);  // 安排 24h 周期检查
                }
            }
        }, 1200);
    }

    /** 消费后台 24h 检查的结果: 新公告 / 每周公告 (一次弹一个, 其余下次启动再弹) */
    private void consumePendingChecks() {
        SharedPreferences sp = getSharedPreferences("check", MODE_PRIVATE);
        String pa = sp.getString("pending_announce", "");
        if (!pa.isEmpty()) {
            sp.edit().remove("pending_announce").apply();
            try {
                showAnnounceDialog(new JSONArray(pa));
                return;
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        String wa = sp.getString("weekly_announce", "");
        if (!wa.isEmpty()) {
            sp.edit().remove("weekly_announce").apply();
            try {
                showAnnounceDialog(new JSONArray(wa));
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }

    /** 检测电池优化: 未加入白名单时引导 (自动执行类 App 生存关键) */
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        } catch (Exception e) { return; }
        final SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (sp.getBoolean("battery_hint_off", false)) return;
        final MdDialog d = new MdDialog(this);
        d.title("电池优化未关闭");
        d.message("为让定时任务在后台准时执行, 建议将本应用加入「不优化电池」白名单。\n\n否则系统可能限制后台运行, 导致任务不执行或执行失败。");
        d.action("暂时忽略", new Runnable() {
            public void run() {
                sp.edit().putBoolean("battery_hint_off", true).apply();
            }
        });
        d.actionPrimary("去设置", new Runnable() {
            public void run() {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            }
        });
        d.show();
    }

    /** 后台静默解压运行时 (若未就绪) */
    private void ensureRuntimeBackground() {
        if (RuntimeManager.isReady(this)) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    RuntimeManager.ensureReady(MainActivity.this, null);
                } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        }).start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUI(); // 深色切换重建
    }

    // ---------------- UI 构建 ----------------
    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(card());
            int flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (isDark()) flag = 0;
            getWindow().getDecorView().setSystemUiVisibility(flag);
        }

        root.addView(header());
        // 内容区(weight=1) — FrameLayout: 页面与 FAB 悬浮层重叠, FAB 永远在最上层
        contentBox = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 0);
        lp.weight = 1f;
        contentBox.setLayoutParams(lp);
        root.addView(contentBox);
        // 底部 Tab 栏
        root.addView(bottomBar());
        setContentView(root);
        renderTab();
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(card());
        h.setPadding(dp(20), dp(24), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText("定时任务");
        title.setTextColor(MdTheme.onSurface(this));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        h.addView(title);
        TextView sub = new TextView(this);
        sub.setText("自动执行 HTTP 任务与 Shell 脚本");
        sub.setTextColor(MdTheme.onSurfaceVariant(this));
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, 0);
        h.addView(sub);
        return h;
    }

    /** 底部固定 Tab 栏 */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(card());
        bar.setPadding(dp(8), dp(8), dp(8), dp(10));
        // 顶部细线
        View line = new View(this);
        line.setBackgroundColor(line());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(1));
        bar.setLayoutParams(lp);
        // 3 个 tab
        bar.addView(tabItem("任务", R.drawable.ic_log, 0, 1f));
        bar.addView(tabItem("日志", R.drawable.ic_terminal, 1, 1f));
        bar.addView(tabItem("更多", R.drawable.ic_more, 2, 1f));
        return bar;
    }

    private View tabItem(String label, int iconRes, final int tab, float weight) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(6), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP);
        lp.weight = weight;
        item.setLayoutParams(lp);
        boolean active = currentTab == tab;
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        int tint = active ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this);
        icon.setColorFilter(tint);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(active ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(2), 0, 0);
        item.addView(tv);
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentTab != tab) {
                    currentTab = tab;
                    buildUI();
                }
            }
        });
        return item;
    }

    // FAB 悬浮添加按钮
    private void addFab() {
        FrameLayout fabLayer = new FrameLayout(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(MATCH, MATCH);
        fabLayer.setLayoutParams(flp);
        fabLayer.setClickable(false);
        contentBox.addView(fabLayer);
        TextView fab = new TextView(this);
        fab.setText("＋");
        fab.setTextSize(28);
        fab.setTextColor(MdTheme.onPrimary(this));
        fab.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.primary(this));
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        fab.setBackground(g);
        if (!MdTheme.isDark(this)) fab.setElevation(dp(6));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(56), dp(56));
        fl.gravity = Gravity.BOTTOM | Gravity.END;
        fl.setMargins(0, 0, dp(20), dp(20));
        fab.setLayoutParams(fl);
        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAddMenu(); }
        });
        fabLayer.addView(fab);
    }

    /** 任务页渲染 */
private void renderTasks() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        contentBox.addView(page);

        // 任务搜索栏 (固定, 不随列表滚动)
        final LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(16), dp(10), dp(16), dp(0));
        page.addView(searchBar);
        final android.widget.EditText searchEt = new android.widget.EditText(this);
        searchEt.setHint("🔍 搜索任务名称…");
        searchEt.setTextSize(14);
        searchEt.setTextColor(MdTheme.onSurface(this));
        searchEt.setSingleLine(true);
        searchEt.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.graphics.drawable.GradientDrawable sBg = new android.graphics.drawable.GradientDrawable();
        sBg.setColor(MdTheme.isDark(this) ? 0x141E1B16 : 0x0F49454F);
        sBg.setCornerRadius(dp(10));
        searchEt.setBackground(sBg);
        searchBar.addView(searchEt, new LinearLayout.LayoutParams(0, WRAP, 1));
        // 清空按钮
        final TextView clearSb = new TextView(this);
        clearSb.setText("✕");
        clearSb.setTextColor(MdTheme.onSurfaceVariant(this));
        clearSb.setTextSize(16);
        clearSb.setPadding(dp(10), dp(8), dp(10), dp(8));
        clearSb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searchEt.setText("");
            }
        });
        clearSb.setVisibility(View.GONE);
        searchBar.addView(clearSb);

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, 0);
        slp.weight = 1f;
        sc.setLayoutParams(slp);
        page.addView(sc);

        final LinearLayout[] listRef = new LinearLayout[1];
        final MainActivity ma = this;

        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int c, int a) {}
            public void afterTextChanged(android.text.Editable s) {
                if (listRef[0] == null) return;
                clearSb.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                String q = s.toString().trim().toLowerCase();
                LinearLayout list = listRef[0];
                list.removeAllViews();
                tasks = TaskStore.load(ma);
                int count = 0;
                for (Task t : tasks) {
                    if (q.isEmpty() || t.name.toLowerCase().contains(q)) {
                        list.addView(taskCard(t));
                        count++;
                    }
                }
                if (count == 0) {
                    TextView empty = new TextView(ma);
                    empty.setText("没有匹配的任务");
                    empty.setTextColor(MdTheme.onSurfaceVariant(ma));
                    empty.setGravity(Gravity.CENTER);
                    empty.setTextSize(15);
                    empty.setPadding(0, dp(60), 0, dp(10));
                    list.addView(empty);
                }
            }
        });
        listRef[0] = new LinearLayout(this);
        listRef[0].setOrientation(LinearLayout.VERTICAL);
        listRef[0].setPadding(dp(16), dp(14), dp(16), dp(16));
        sc.addView(listRef[0]);
        tasks = TaskStore.load(this);
        if (tasks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有任务\n点右下角 ＋ 添加第一个任务");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15);
            empty.setPadding(0, dp(80), 0, dp(10));
            listRef[0].addView(empty);
        } else {
            for (Task t : tasks) {
                listRef[0].addView(taskCard(t));
            }
        }
        addFab();
    }

    private View taskCard(final Task t) {
        MdCard card = new MdCard(this, MdCard.OUTLINED, false);
        LinearLayout.LayoutParams cpm = new LinearLayout.LayoutParams(MATCH, WRAP);
        cpm.bottomMargin = dp(8);
        card.setLayoutParams(cpm);

        // 第一行: 名称 + 开关
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);
        TextView name = new TextView(this);
        name.setText(t.name);
        name.setTextColor(MdTheme.onSurface(this));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        // 开关: 胶囊样式
        final TextView sw = new TextView(this);
        sw.setText(t.enabled ? "● 开启" : "○ 关闭");
        sw.setTextColor(t.enabled ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        sw.setTextSize(12);
        sw.setTypeface(Typeface.DEFAULT_BOLD);
        sw.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable swBg = new android.graphics.drawable.GradientDrawable();
        swBg.setColor(t.enabled
                ? (MdTheme.isDark(this) ? 0x30D0BCFF : 0x1F6750A4)
                : (MdTheme.isDark(this) ? 0x10CAC4D0 : 0x0F49454F));
        swBg.setCornerRadius(dp(10));
        sw.setBackground(swBg);
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                t.enabled = !t.enabled;
                TaskStore.save(ctx, tasks);
                AlarmScheduler.schedule(ctx, t);
                buildUI();
                MdSnackbar.show(root, t.enabled ? "已开启" : "已关闭");
            }
        });
        top.addView(sw);

        // 第二行: 类型 + 时间 + 重试配置
        TextView meta = new TextView(this);
        String type = "http".equals(t.type) ? "HTTP" : "SHELL";
        String time = String.format(Locale.CHINA, "%02d:%02d", t.hour, t.minute);
        String retry = t.retryTimes > 0 ? " · 失败重试×" + t.retryTimes : "";
        meta.setText(type + " · " + time + (t.repeatDaily ? " · 每天" : "") + retry);
        meta.setTextColor(MdTheme.onSurfaceVariant(this));
        meta.setTextSize(12);
        meta.setPadding(0, dp(6), 0, 0);
        card.addView(meta);

        // 第三行: 状态行
        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, dp(6), 0, 0);
        StringBuilder st = new StringBuilder();
        if (t.lastRunAt > 0) {
            String last = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t.lastRunAt));
            st.append(t.lastOk ? "成功 " : "失败 ").append(last);
            if (t.lastOk && t.streak > 0) st.append(" · 连续 ").append(t.streak).append(" 天");
            if (!t.lastOk) st.append(" · ").append(shortMsg(t.lastResult));
        } else {
            st.append("从未执行");
        }
        long nt = AlarmScheduler.nextRun(t);
        if (nt > 0) {
            String nx = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(nt));
            st.append(" · 下次 ").append(nx);
        }
        status.setText(st.toString());
        status.setTextColor(t.lastOk ? MdTheme.onSurfaceVariant(this) : MdTheme.error(this));
        card.addView(status);

        // 第四行: 按钮(M3 小号 text button, 图标+短文字)
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, dp(4), 0, 0);
        card.addView(btns);
        btns.addView(mdMiniBtn("执行", false, Icons.PLAY, new Runnable() {
            public void run() { runNow(t); }
        }));
        btns.addView(mdMiniBtn("脚本", false, new Runnable() {
            public void run() { showScriptEditor(t); }
        }));
        btns.addView(mdMiniBtn("编辑", false, new Runnable() {
            public void run() { showEditDialog(t); }
        }));
        btns.addView(mdMiniBtn("删除", true, new Runnable() {
            public void run() {
                final MdDialog d = new MdDialog(ctx);
                d.title("删除任务");
                d.message("确定删除「" + t.name + "」吗？");
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.actionPrimary("删除", new Runnable() {
                    public void run() {
                        AlarmScheduler.cancel(ctx, t.id);
                        tasks.remove(t);
                        TaskStore.save(ctx, tasks);
                        d.dismiss();
                        buildUI();
                    }
                });
                d.show();
            }
        }));
        return card;
    }

    private String shortMsg(String s) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /** 日志页 */
    private void renderLogs() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        contentBox.addView(sc);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(50), dp(16), dp(16));   // 顶部留出悬浮按钮空间
        sc.addView(page);
        MdCard logCard = new MdCard(this, MdCard.OUTLINED, false);
        logCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView tv = new TextView(this);
        tv.setText(TaskLog.load(this));
        tv.setTextColor(MdTheme.onSurfaceVariant(this));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        logCard.addView(tv);
        page.addView(logCard);
        // 右上角固定操作按钮 (不随滚动消失)
        LinearLayout btnLayer = new LinearLayout(this);
        btnLayer.setOrientation(LinearLayout.HORIZONTAL);
        btnLayer.setGravity(Gravity.TOP | Gravity.END);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(WRAP, WRAP);
        flp.gravity = Gravity.TOP | Gravity.END;
        flp.topMargin = dp(8);
        flp.rightMargin = dp(8);
        btnLayer.setLayoutParams(flp);
        TextView copyBtn = mdMiniBtn("一键复制", false, new Runnable() {
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("执行日志", TaskLog.load(ctx)));
                MdSnackbar.show(root, "日志已复制");
            }
        });
        copyBtn.setLayoutParams(new LinearLayout.LayoutParams(WRAP, WRAP));
        copyBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        btnLayer.addView(copyBtn);
        TextView clearBtn = mdMiniBtn("清空", true, new Runnable() {
            public void run() {
                final MdDialog d = new MdDialog(MainActivity.this);
                d.title("清空日志");
                d.message("确定清空全部执行日志吗？此操作不可恢复。");
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.actionPrimary("清空", new Runnable() {
                    public void run() {
                        TaskLog.clear(ctx);
                        d.dismiss();
                        buildUI();
                    }
                });
                d.show();
            }
        });
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(WRAP, WRAP));
        clearBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        btnLayer.addView(clearBtn);
        contentBox.addView(btnLayer);
        // 打开日志页自动滚到最下方 (最新日志)
        sc.post(new Runnable() {
            public void run() { sc.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    // 更多页 */
    private void renderMore() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        contentBox.addView(sc);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(20));
        sc.addView(page);

        // 高级模式入口(仿青龙面板)
        MdCard advCard = new MdCard(this, MdCard.FILLED, true);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(MATCH, WRAP);
        alp2.bottomMargin = dp(10);
        advCard.setLayoutParams(alp2);
        LinearLayout advRow = new LinearLayout(this);
        advRow.setOrientation(LinearLayout.HORIZONTAL);
        advRow.setGravity(Gravity.CENTER_VERTICAL);
        advCard.addView(advRow);
        LinearLayout advTxt = new LinearLayout(this);
        advTxt.setOrientation(LinearLayout.VERTICAL);
        advRow.addView(advTxt, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView advT1 = new TextView(this);
        advT1.setText("完整版");
        advT1.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.GEAR, MdTheme.primary(this), 15), null, null, null);
        advT1.setCompoundDrawablePadding(dp(4));
        advT1.setTextColor(MdTheme.primary(this));
        advT1.setTextSize(15);
        advT1.setTypeface(Typeface.DEFAULT_BOLD);
        advTxt.addView(advT1);
        TextView advT2 = new TextView(this);
        advT2.setText("仿青龙面板: cron定时/脚本库/环境变量/终端");
        advT2.setTextColor(MdTheme.onSurfaceVariant(this));
        advT2.setTextSize(11);
        advT2.setPadding(0, dp(2), 0, 0);
        advTxt.addView(advT2);
        TextView advGo = new TextView(this);
        advGo.setText("进入 →");
        advGo.setTextColor(MdTheme.primary(this));
        advGo.setTextSize(13);
        advRow.addView(advGo);
        advCard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, AdvActivity.class));
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
            }
        });
        page.addView(advCard);

        page.addView(menuItem("查看公告", "查看最新版本公告与通知", new Runnable() {
            public void run() { showAnnounceFromServer(); }
        }));
        page.addView(menuItem("后台常驻引导", "防被系统清理, 保证定时生效", new Runnable() {
            public void run() { guideBackground(); }
        }));
        page.addView(menuItem("电池优化白名单", "加入白名单, 后台不被杀", new Runnable() {
            public void run() { requestBatteryWhitelist(); }
        }));
        page.addView(menuItem("脚本开发文档", "完整文档, 可一键复制", new Runnable() {
            public void run() { showScriptDoc(); }
        }));
        page.addView(menuItem("导入任务", "粘贴其他人分享的任务文本", new Runnable() {
            public void run() { showImportDialog(); }
        }));
        page.addView(menuItem("导出任务", "分享全部任务给别人", new Runnable() {
            public void run() { exportTasks(); }
        }));
        page.addView(menuItem("导出日志", "分享运行日志, 便于排查问题", new Runnable() {
            public void run() { exportLogs(); }
        }));
        page.addView(menuItem("AI 配置", "配置 AI 助手接口 (Base URL / Key / 模型)", new Runnable() {
            public void run() { showAIConfigDialog(); }
        }));
        page.addView(menuItem("执行统计", "近 30 天任务成功/失败走势", new Runnable() {
            public void run() { showStats(); }
        }));
        page.addView(menuItem("错误日志", "查看崩溃记录, 可复制反馈", new Runnable() {
            public void run() { showCrashLog(); }
        }));
        page.addView(menuItem("赞助开发者", "觉得好用可以支持一下", new Runnable() {
            public void run() { showSponsor(); }
        }));

        // 成功通知开关
        MdCard swRow = new MdCard(this, MdCard.OUTLINED, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp.bottomMargin = dp(10);
        swRow.setLayoutParams(slp);
        LinearLayout rowIn = new LinearLayout(this);
        rowIn.setOrientation(LinearLayout.HORIZONTAL);
        rowIn.setGravity(Gravity.CENTER_VERTICAL);
        swRow.addView(rowIn);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        rowIn.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView t1 = new TextView(this);
        t1.setText("成功时也通知");
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(14);
        txt.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("关闭后: 成功静默, 仅失败通知");
        t2.setTextColor(MdTheme.onSurfaceVariant(this));
        t2.setTextSize(11);
        txt.addView(t2);
        final TextView swV = new TextView(this);
        final boolean on = Settings.notifyOnSuccess(this);
        swV.setText(on ? "● 开" : "○ 关");
        swV.setTextColor(on ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        swV.setTextSize(13);
        swV.setPadding(dp(8), dp(4), dp(8), dp(4));
        swV.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !Settings.notifyOnSuccess(ctx);
                Settings.setNotifyOnSuccess(ctx, nv);
                buildUI();
            }
        });
        rowIn.addView(swV);
        page.addView(swRow);

        // 脚本定时结果通知开关 (默认开)
        MdCard swRow2 = new MdCard(this, MdCard.OUTLINED, false);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp2.bottomMargin = dp(10);
        swRow2.setLayoutParams(slp2);
        LinearLayout rowIn2 = new LinearLayout(this);
        rowIn2.setOrientation(LinearLayout.HORIZONTAL);
        rowIn2.setGravity(Gravity.CENTER_VERTICAL);
        swRow2.addView(rowIn2);
        LinearLayout txt2 = new LinearLayout(this);
        txt2.setOrientation(LinearLayout.VERTICAL);
        rowIn2.addView(txt2, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView t1b = new TextView(this);
        t1b.setText("脚本定时结果通知");
        t1b.setTextColor(MdTheme.onSurface(this));
        t1b.setTextSize(14);
        txt2.addView(t1b);
        TextView t2b = new TextView(this);
        t2b.setText("脚本作为定时任务执行完后, 把输出摘要推送到通知栏");
        t2b.setTextColor(MdTheme.onSurfaceVariant(this));
        t2b.setTextSize(11);
        txt2.addView(t2b);
        final TextView swV2 = new TextView(this);
        final boolean on2 = Settings.notifyScriptCron(this);
        swV2.setText(on2 ? "● 开" : "○ 关");
        swV2.setTextColor(on2 ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        swV2.setTextSize(13);
        swV2.setPadding(dp(8), dp(4), dp(8), dp(4));
        swV2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !Settings.notifyScriptCron(ctx);
                Settings.setNotifyScriptCron(ctx, nv);
                buildUI();
            }
        });
        rowIn2.addView(swV2);
        page.addView(swRow2);

        page.addView(menuItem("使用帮助", "基本用法说明", new Runnable() {
            public void run() { showHelp(); }
        }));
    }

    /** 赞助开发者: 显示收款码 + 开发者QQ */
    /** 后端检查: 更新优先, 其次公告 (silent=true 时无更新无公告不打扰) */
    private void doBackendCheck(final boolean silent) {
        new Thread(new Runnable() {
            public void run() {
                final JSONObject v = Backend.checkVersion(MainActivity.this);
                final JSONArray a = (v == null) ? Backend.checkAnnounce(MainActivity.this) : null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (v != null) {
                            showUpdateDialog(v);
                        } else if (a != null) {
                            showAnnounceDialog(a);
                        } else if (!silent) {
                            MdSnackbar.show(root, "已是最新版本, 公告无更新");
                        }
                    }
                });
            }
        }).start();
    }

    /** 版本更新对话框 */
    private void showUpdateDialog(JSONObject v) {
        String name = v.optString("version_name", "?");
        final String url = v.optString("url", "");
        String log = v.optString("log", "");
        final boolean force = v.optBoolean("force", false);
        final MdDialog d = new MdDialog(this);
        d.title("发现新版本 v" + name);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText("更新日志:\n" + (log.isEmpty() ? "(无)" : log));
        tv.setTextColor(MdTheme.onSurface(this));
        tv.setTextSize(13);
        tv.setPadding(0, dp(4), 0, 0);
        f.addView(tv);
        d.content(f);
        if (!force) {
            d.action("以后再说", new Runnable() { public void run() { d.dismiss(); } });
        } else {
            d.setCancelable(false);
        }
        d.actionPrimary("立即下载", new Runnable() {
            public void run() {
                d.dismiss();
                if (!url.isEmpty()) {
                    Backend.download(MainActivity.this, url, "定时任务Pro-" + name + ".apk");
                    MdSnackbar.show(root, "已开始下载, 请查看通知栏进度");
                } else {
                    // 无直链时引导去 GitHub Release 页
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Qins-zlo/taskpro/releases/latest"));
                        startActivity(i);
                    } catch (Exception e) {
                        MdSnackbar.show(root, "未找到下载链接, 请到 GitHub Releases 下载");
                    }
                }
            }
        });
        d.show();
    }

    /** 公告对话框 (多条滚动显示) */
    private void showAnnounceDialog(JSONArray arr) {
        final MdDialog d = new MdDialog(this);
        d.title("公告");
        // 公告/广告弹窗稳定性: 禁止点外部遮罩和返回键关闭, 只能点按钮关闭
        // (避免用户误点弹窗旁边导致弹窗消失, 看不了公告/广告)
        try { d.setCanceledOnTouchOutside(false); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        try { d.setCancelable(false); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("title", "公告");
                String content = o.optString("content", "");
                String time = o.optString("time", "");
                TextView t = new TextView(this);
                t.setText((i > 0 ? "\n" : "") + "【" + title + "】"
                        + (time.isEmpty() ? "" : "  " + time) + "\n" + content);
                t.setTextColor(MdTheme.onSurface(this));
                t.setTextSize(13);
                t.setPadding(0, dp(2), 0, dp(2));
                list.addView(t);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        sc.addView(list);
        d.content(sc);
        d.action("知道了", new Runnable() { public void run() { d.dismiss(); } });
        // 查看广告按钮: 若公告带 ad_url/url 字段, 点击用浏览器打开广告
        final String adUrl = firstAdUrl(arr);
        if (!adUrl.isEmpty()) {
            d.actionPrimary("查看广告", new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(adUrl));
                        startActivity(i);
                    } catch (Exception e) {
                        try { MdSnackbar.show(root, "无法打开广告链接"); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                }
            });
        }
        d.show();
    }

    /** 从公告数组中取第一条携带的广告链接 (ad_url 优先, 其次 url) */
    private String firstAdUrl(JSONArray arr) {
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("ad_url", "");
                if (u.isEmpty()) u = o.optString("url", "");
                if (!u.isEmpty()) return u;
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return "";
    }

    /** 手动查看公告 (更多页入口): 强制拉取最新公告, 失败给提示 */
    private void showAnnounceFromServer() {
        final MdDialog loading = new MdDialog(this);
        loading.title("公告");
        loading.message("正在加载最新公告…");
        loading.hideActions();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                final JSONArray arr = Backend.fetchAnnounceForce(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { loading.dismiss(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        if (arr != null && arr.length() > 0) {
                            showAnnounceDialog(arr);
                        } else {
                            try { MdSnackbar.show(root, "暂无公告或网络异常"); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    }
                });
            }
        }).start();
    }

    /** 查看崩溃日志 (files/crash.log) */
    /** 导出运行日志 (分享给开发者排查) */
    private void exportLogs() {
        String logs = TaskLog.load(this);
        if ("暂无日志".equals(logs)) {
            MdSnackbar.show(root, "暂无日志");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "定时任务Pro 运行日志");
            i.putExtra(Intent.EXTRA_TEXT, logs);
            startActivity(Intent.createChooser(i, "导出日志"));
        } catch (Exception e) {
            MdSnackbar.show(root, "导出失败: " + e.toString());
        }
    }

    /** AI 接口配置 */
    private void showAIConfigDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("AI 配置");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final android.widget.EditText baseEt = new android.widget.EditText(this);
        baseEt.setHint("Base URL (OpenAI 兼容, 如 https://api.deepseek.com/v1)");
        baseEt.setText(AIConfig.baseUrl(this));
        box.addView(aiField(baseEt));
        final android.widget.EditText keyEt = new android.widget.EditText(this);
        keyEt.setHint("API Key");
        keyEt.setText(AIConfig.apiKey(this));
        keyEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(aiField(keyEt));
        final android.widget.EditText modelEt = new android.widget.EditText(this);
        modelEt.setHint("模型 (如 deepseek-chat / qwen-plus / gpt-4o-mini)");
        modelEt.setText(AIConfig.model(this));
        box.addView(aiField(modelEt));
        TextView tip = new TextView(this);
        tip.setText("兼容 OpenAI Chat Completions 格式的服务均可使用。\n配置后前往 AI 助手 使用。");
        tip.setTextColor(MdTheme.onSurfaceVariant(this));
        tip.setTextSize(12);
        tip.setPadding(dp(2), dp(8), dp(2), 0);
        box.addView(tip);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                String base = baseEt.getText().toString().trim();
                String key = keyEt.getText().toString().trim();
                String model = modelEt.getText().toString().trim();
                if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                    MdSnackbar.show(root, "请完整填写三项配置");
                    return;
                }
                AIConfig.save(MainActivity.this, base, key, model);
                MdSnackbar.show(root, "已保存, 可前往 AI 助手使用");
                d.dismiss();
            }
        });
        d.show();
    }

    private android.widget.EditText aiField(android.widget.EditText et) {
        et.setTextSize(14);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
        g.setCornerRadius(dp(10));
        et.setBackground(g);
        return et;
    }

    /** 执行统计: 近 30 天成功率柱状图 */
    private void showStats() {
        final JSONArray days = Stats.lastDays(this, 30);
        int ok = 0, fail = 0;
        for (int i = 0; i < days.length(); i++) {
            JSONObject o = days.optJSONObject(i);
            if (o == null) continue;
            ok += o.optInt("ok", 0);
            fail += o.optInt("fail", 0);
        }
        final MdDialog d = new MdDialog(this);
        d.title("执行统计 (近 30 天)");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int total = ok + fail;
        TextView sum = new TextView(this);
        sum.setText(total == 0 ? "近 30 天暂无执行记录" :
                ("共执行 " + total + " 次 · 成功 " + ok + " · 失败 " + fail
                        + " · 成功率 " + (int) (100f * ok / total) + "%"));
        sum.setTextColor(MdTheme.onSurface(this));
        sum.setTextSize(14);
        box.addView(sum);
        box.addView(new android.view.View(this) {
            {
                setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
                setPadding(dp(4), dp(4), dp(4), dp(4));
            }
            protected void onDraw(android.graphics.Canvas cv) {
                super.onDraw(cv);
                int w = getWidth(), h = getHeight();
                int padB = dp(16);
                android.graphics.Paint p = new android.graphics.Paint();
                p.setAntiAlias(true);
                float max = 1;
                for (int i = 0; i < days.length(); i++) {
                    JSONObject o = days.optJSONObject(i);
                    max = Math.max(max, o.optInt("ok", 0) + o.optInt("fail", 0));
                }
                float bw = w / (float) days.length();
                float barW = Math.max(2, bw * 0.62f);
                for (int i = 0; i < days.length(); i++) {
                    JSONObject o = days.optJSONObject(i);
                    float okH = (h - padB) * o.optInt("ok", 0) / max;
                    float failH = (h - padB) * o.optInt("fail", 0) / max;
                    float x = i * bw + (bw - barW) / 2;
                    float base = h - padB;
                    if (failH > 0) {
                        p.setColor(red());
                        cv.drawRect(x, base - okH - failH, x + barW, base - okH, p);
                    }
                    if (okH > 0) {
                        p.setColor(green());
                        cv.drawRect(x, base - okH, x + barW, base, p);
                    }
                    // 每 5 天标日期
                    if (i % 5 == 0) {
                        p.setColor(MdTheme.onSurfaceVariant(MainActivity.this));
                        p.setTextSize(dp(9));
                        cv.drawText(o.optString("date", ""), x, h - dp(2), p);
                    }
                }
            }
        });
        box.addView(new android.view.View(this) {
            {
                setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
                setPadding(dp(4), 0, dp(4), 0);
            }
            protected void onDraw(android.graphics.Canvas cv) {
                super.onDraw(cv);
                int h = getHeight();
                android.graphics.Paint p = new android.graphics.Paint();
                p.setAntiAlias(true);
                float y = h / 2f;
                p.setColor(green());
                cv.drawRect(dp(4), y - dp(5), dp(14), y + dp(5), p);
                p.setColor(MdTheme.onSurface(MainActivity.this));
                p.setTextSize(dp(11));
                cv.drawText("成功", dp(18), y + dp(4), p);
                p.setColor(red());
                cv.drawRect(dp(60), y - dp(5), dp(70), y + dp(5), p);
                p.setColor(MdTheme.onSurface(MainActivity.this));
                cv.drawText("失败", dp(74), y + dp(4), p);
            }
        });
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    private void showCrashLog() {
        java.io.File f = new java.io.File(getFilesDir(), "crash.log");
        String content = "";
        if (f.exists()) {
            try {
                content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            } catch (Exception e) {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] buf = new byte[(int) Math.min(f.length(), 200000)];
                    int n = in.read(buf);
                    in.close();
                    content = new String(buf, 0, Math.max(n, 0), "UTF-8");
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        }
        if (content.trim().isEmpty()) {
            MdSnackbar.show(root, "暂无错误日志 (说明一切正常)");
            return;
        }
        final MdDialog d = new MdDialog(this);
        d.title("错误日志");
        final ScrollView sc = new ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextColor(MdTheme.onSurface(this));
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        sc.addView(tv);
        d.content(sc);
        d.action("清空", new Runnable() {
            public void run() {
                try { new java.io.File(getFilesDir(), "crash.log").delete(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                d.dismiss();
                MdSnackbar.show(root, "已清空");
            }
        });
        d.actionPrimary("复制", new Runnable() {
            public void run() {
                try {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("crash", tv.getText().toString()));
                    MdSnackbar.show(root, "已复制, 可直接粘贴反馈");
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        });
        d.show();
    }

    private void showSponsor() {
        final MdDialog d = new MdDialog(this);
        d.title("赞助开发者");
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.sponsor);
        int size = dp(200);
        img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wrap.addView(img);
        TextView qq = new TextView(this);
        qq.setText("开发者QQ: 3666418726");
        qq.setTextColor(MdTheme.onSurfaceVariant(this));
        qq.setTextSize(13);
        qq.setGravity(Gravity.CENTER);
        qq.setPadding(0, dp(10), 0, dp(2));
        wrap.addView(qq);
        TextView tip = new TextView(this);
        tip.setText("长按或点击复制QQ, 感谢支持");
        tip.setTextColor(MdTheme.outline(this));
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, dp(4));
        wrap.addView(tip);
        d.content(wrap);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("复制QQ", new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("开发者QQ", "3666418726"));
                    MdSnackbar.show(root, "已复制 QQ: 3666418726");
                } catch (Exception e) {
                    MdSnackbar.show(root, "复制失败");
                }
            }
        });
        d.show();
    }

    private View menuItem(String title, String desc, final Runnable action) {
        MdCard row = new MdCard(this, MdCard.OUTLINED, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(15);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextColor(MdTheme.onSurfaceVariant(this));
        t2.setTextSize(12);
        t2.setPadding(0, dp(2), 0, 0);
        row.addView(t2);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private void renderTab() {
        contentBox.removeAllViews();
        if (currentTab == 0) renderTasks();
        else if (currentTab == 1) renderLogs();
        else renderMore();
    }

    // ---------------- 添加 ----------------
    private void showAddMenu() {
        final MdDialog d = new MdDialog(this);
        d.title("添加任务");
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(menuItem("HTTP 任务", "登录/签到类, 步骤 JSON", new Runnable() {
            public void run() { d.dismiss(); showAddDialog(); }
        }));
        list.addView(menuItem("Shell 脚本任务", "直接执行 sh 命令", new Runnable() {
            public void run() { d.dismiss(); showAddShellDialog(); }
        }));
        d.content(list);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    private void showAddDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("添加 HTTP 任务");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "任务名称", false);
        final MdTextField json = new MdTextField(this, "步骤 JSON (账号密码写在脚本内)", true);
        form.addView(name);
        form.addView(json);
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("添加", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                String j = json.getText().trim();
                if (n.isEmpty() || j.isEmpty()) {
                    MdSnackbar.show(root, "名称和 JSON 不能为空");
                    return;
                }
                Task t = new Task(TaskStore.newId(), n, "http");
                try {
                    t.steps = new JSONArray(j);
                } catch (Exception e) {
                    MdSnackbar.show(root, "JSON 解析失败: " + e.getMessage());
                    return;
                }
                t.hour = 8; t.minute = 0;
                tasks.add(t);
                TaskStore.save(ctx, tasks);
                AlarmScheduler.schedule(ctx, t);
                d.dismiss();
                buildUI();
            }
        });
        d.show();
    }

    // 常用 Shell 模板 (单引号包裹 python 代码, 避免与 sh -c 嵌套引号冲突)
    private static final String[][] SHELL_TEMPLATES = {
            {"签到请求", "python3 -c 'import urllib.request; req = urllib.request.Request(\"https://www.baidu.com\", headers={\"User-Agent\": \"Mozilla/5.0\"}); print(\"状态码:\", urllib.request.urlopen(req, timeout=10).status)'"},
            {"网络请求", "curl -s https://www.baidu.com | busybox head -3"},
            {"环境检查", "python3 --version && python3 -m pip --version"},
            {"系统信息", "echo \"时间: $(date)\"; busybox uname -a; busybox free | busybox head -2"},
    };

    private void showAddShellDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("添加 Shell 脚本任务");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "任务名称", false);
        final MdTextField script = new MdTextField(this, "sh 脚本内容 (支持 python3 / pip / busybox)", true);
        form.addView(name);
        form.addView(script);

        // 模板行
        LinearLayout tplRow = new LinearLayout(this);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        tplRow.setPadding(0, dp(6), 0, 0);
        form.addView(tplRow);
        TextView tplLabel = new TextView(this);
        tplLabel.setText("模板");
        tplLabel.setTextSize(12);
        tplLabel.setTextColor(MdTheme.onSurfaceVariant(this));
        tplLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tplLabel.setGravity(Gravity.CENTER_VERTICAL);
        tplRow.addView(tplLabel);
        for (int i = 0; i < SHELL_TEMPLATES.length; i++) {
            final String tpl = SHELL_TEMPLATES[i][1];
            final TextView b = new TextView(this);
            b.setText(SHELL_TEMPLATES[i][0]);
            b.setTextSize(11);
            b.setSingleLine(true);
            b.setTextColor(MdTheme.primary(this));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(6), dp(6), dp(6), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(isDark() ? 0x33D0BCFF : 0x14D0BCFF);
            bg.setCornerRadius(dp(14));
            b.setBackground(bg);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2);
            blp.weight = 1;
            blp.rightMargin = dp(5);
            b.setLayoutParams(blp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    script.setText(tpl);
                    MdSnackbar.show(root, "已填充模板: " + tpl);
                }
            });
            tplRow.addView(b);
        }

        // 测试输出区 (先声明, 供测试按钮回调引用)
        final TextView testOut = new TextView(this);
        testOut.setTextSize(11);
        testOut.setTypeface(Typeface.MONOSPACE);
        testOut.setTextColor(MdTheme.onSurfaceVariant(this));
        testOut.setVisibility(View.GONE);
        testOut.setBackgroundColor(MdTheme.surfaceContainer(this));
        testOut.setPadding(dp(10), dp(8), dp(10), dp(8));
        ScrollView testSc = new ScrollView(this);
        testSc.addView(testOut);
        LinearLayout.LayoutParams tsP = new LinearLayout.LayoutParams(-1, dp(120));
        tsP.topMargin = dp(6);
        testSc.setLayoutParams(tsP);
        form.addView(testSc);

        // 测试运行区
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setPadding(0, dp(10), 0, 0);
        form.addView(testRow);
        final TextView testBtn = new TextView(this);
        testBtn.setText("测试运行");
        testBtn.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.PLAY, MdTheme.primary(this), 13), null, null, null);
        testBtn.setCompoundDrawablePadding(dp(4));
        testBtn.setTextSize(12);
        testBtn.setTypeface(Typeface.DEFAULT_BOLD);
        testBtn.setTextColor(MdTheme.primary(this));
        testBtn.setGravity(Gravity.CENTER);
        testBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
        tbg.setColor(isDark() ? 0x33D0BCFF : 0x14D0BCFF);
        tbg.setCornerRadius(dp(16));
        testBtn.setBackground(tbg);
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = script.getText();
                if (s.isEmpty()) { MdSnackbar.show(root, "先填写脚本内容"); return; }
                runTestScript(s, testOut, testBtn);
            }
        });
        testRow.addView(testBtn);

        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("添加", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                String s = script.getText();
                if (n.isEmpty() || s.isEmpty()) {
                    MdSnackbar.show(root, "名称和脚本不能为空");
                    return;
                }
                Task t = new Task(TaskStore.newId(), n, "shell");
                t.script = s;
                t.hour = 8; t.minute = 0;
                tasks.add(t);
                TaskStore.save(ctx, tasks);
                AlarmScheduler.schedule(ctx, t);
                d.dismiss();
                buildUI();
            }
        });
        d.show();
    }

    // ---------------- 编辑/脚本 ----------------
    /** 后台测试执行脚本 (15s 超时), 结果显示到 testOut */
    private void runTestScript(final String scriptText, final TextView testOut, final TextView testBtn) {
        testBtn.setEnabled(false);
        testBtn.setTextColor(MdTheme.onSurfaceVariant(this));
        testOut.setVisibility(View.VISIBLE);
        testOut.setText("正在执行...\n");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final StringBuilder sb = new StringBuilder();
                    String cmd = RuntimeManager.buildCommand(getApplicationContext(), scriptText);
                    final Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                    Thread rt = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                                String line;
                                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    rt.start();
                    Thread et = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                                String line;
                                while ((line = r.readLine()) != null) sb.append("! ").append(line).append('\n');
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    et.start();
                    final boolean[] timedOut = {false};
                    Thread killer = new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(15000); if (p.isAlive()) { timedOut[0] = true; p.destroy(); } }
                            catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    killer.setDaemon(true);
                    killer.start();
                    int code = p.waitFor();
                    rt.join(2000); et.join(2000);
                    final String res = (timedOut[0] ? "[超时15s终止]\n" : "") + sb.toString() + "退出码: " + code;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            testOut.setText(res.length() > 1500 ? res.substring(0, 1500) + "..." : res);
                            testBtn.setEnabled(true);
                            testBtn.setTextColor(MdTheme.primary(MainActivity.this));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            testOut.setText("执行失败: " + e);
                            testBtn.setEnabled(true);
                            testBtn.setTextColor(MdTheme.primary(MainActivity.this));
                        }
                    });
                }
            }
        }).start();
    }

    private void showScriptEditor(final Task t) {
        final MdDialog d = new MdDialog(this);
        d.title("编辑脚本");
        final MdTextField ed = new MdTextField(this, t.name, true);
        ed.setText(t.type.equals("http") ? t.steps.toString() : t.script);

        if ("shell".equals(t.type)) {
            // Shell 任务: 加测试运行区
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(ed);

            final TextView testOut = new TextView(this);
            testOut.setTextSize(11);
            testOut.setTypeface(Typeface.MONOSPACE);
            testOut.setTextColor(MdTheme.onSurfaceVariant(this));
            testOut.setVisibility(View.GONE);
            testOut.setBackgroundColor(MdTheme.surfaceContainer(this));
            testOut.setPadding(dp(10), dp(8), dp(10), dp(8));
            ScrollView testSc = new ScrollView(this);
            testSc.addView(testOut);
            LinearLayout.LayoutParams tsP = new LinearLayout.LayoutParams(-1, dp(120));
            tsP.topMargin = dp(8);
            testSc.setLayoutParams(tsP);
            box.addView(testSc);

            final TextView testBtn = new TextView(this);
            testBtn.setText("测试运行");
        testBtn.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.PLAY, MdTheme.primary(this), 13), null, null, null);
        testBtn.setCompoundDrawablePadding(dp(4));
            testBtn.setTextSize(12);
            testBtn.setTypeface(Typeface.DEFAULT_BOLD);
            testBtn.setTextColor(MdTheme.primary(this));
            testBtn.setGravity(Gravity.CENTER);
            testBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
            android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
            tbg.setColor(isDark() ? 0x33D0BCFF : 0x14D0BCFF);
            tbg.setCornerRadius(dp(16));
            testBtn.setBackground(tbg);
            testBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String s = ed.getText();
                    if (s.isEmpty()) { MdSnackbar.show(root, "脚本为空"); return; }
                    runTestScript(s, testOut, testBtn);
                }
            });
            box.addView(testBtn);
            d.content(box);
        } else {
            d.content(ed);
        }
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                String txt = ed.getText().trim();
                if ("http".equals(t.type)) {
                    try {
                        t.steps = new JSONArray(txt);
                    } catch (Exception e) {
                        MdSnackbar.show(root, "JSON 解析失败: " + e.getMessage());
                        return;
                    }
                } else {
                    t.script = txt;
                }
                TaskStore.save(ctx, tasks);
                d.dismiss();
                MdSnackbar.show(root, "已保存");
                buildUI();
            }
        });
        d.show();
    }

    private void showEditDialog(final Task t) {
        final MdDialog d = new MdDialog(this);
        d.title("编辑任务");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "名称", false);
        name.setText(t.name);
        final MdTextField time = new MdTextField(this, "时间 (HH:MM)", false);
        time.setText(String.format(Locale.CHINA, "%02d:%02d", t.hour, t.minute));
        final MdTextField retry = new MdTextField(this, "失败重试次数 (0=不重试)", false);
        retry.setText(String.valueOf(t.retryTimes));
        form.addView(name);
        form.addView(time);
        form.addView(retry);
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                if (!n.isEmpty()) t.name = n;
                String[] hm = time.getText().trim().split(":");
                try {
                    t.hour = Integer.parseInt(hm[0]);
                    t.minute = Integer.parseInt(hm[1]);
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                try { t.retryTimes = Integer.parseInt(retry.getText().trim()); } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                TaskStore.save(ctx, tasks);
                AlarmScheduler.schedule(ctx, t);
                d.dismiss();
                buildUI();
            }
        });
        d.show();
    }

    // ---------------- 立即执行 ----------------
    private void runNow(final Task t) {
        MdSnackbar.show(root, "开始执行: " + t.name);
        new Thread(new Runnable() {
            public void run() {
                StringBuilder log = new StringBuilder();
                TaskEngine.Result r;
                try {
                    r = TaskEngine.run(t, new TaskEngine.Logger() {
                        public void log(String line) { log.append(line).append("\n"); }
                    });
                } catch (Exception e) {
                    log.append("内部异常: ").append(e).append("\n");
                    r = new TaskEngine.Result(false, "内部异常: " + e.getMessage());
                }
                final String summary = r.summary;
                t.lastOk = r.ok;
                t.lastRunAt = System.currentTimeMillis();
                t.lastResult = summary;
                if (r.ok) t.streak = t.streak + 1; else t.streak = 0;
                TaskStore.save(ctx, tasks);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            buildUI();
                        } catch (Exception e) {
                            MdSnackbar.show(root, "刷新界面异常: " + e.getMessage());
                        }
                        MdSnackbar.show(root, t.name + " → " + summary);
                    }
                });
                TaskLog.append(ctx, t.name + "(手动)", summary + "\n" + log.toString());
                if (!r.ok || Settings.notifyOnSuccess(ctx)) {
                    Notifier.post(ctx, t.name + (r.ok ? " 执行成功" : " 执行失败"), summary);
                }
            }
        }).start();
    }

    // ---------------- 导入/导出 ----------------
    private void showImportDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("导入任务");
        final MdTextField txt = new MdTextField(this, "粘贴其他人分享的任务文本", true);
        d.content(txt);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("导入", new Runnable() {
            public void run() {
                List<Task> added = TaskStore.importTasks(ctx, txt.getText());
                if (added.isEmpty()) {
                    MdSnackbar.show(root, "没有解析到任务, 请检查文本");
                } else {
                    MdSnackbar.show(root, "成功导入 " + added.size() + " 个任务");
                    tasks = TaskStore.load(ctx);
                    AlarmScheduler.scheduleAll(ctx, tasks);
                    d.dismiss();
                    buildUI();
                }
            }
        });
        d.show();
    }

    private void exportTasks() {
        if (tasks.isEmpty()) {
            MdSnackbar.show(root, "没有可导出的任务");
            return;
        }
        String text = TaskStore.exportAll(tasks);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("定时任务导出", text));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "分享任务"));
        } catch (Exception e) {
            MdSnackbar.show(root, "已复制到剪贴板");
        }
    }

    // ---------------- 引导 ----------------
    private void guideBackground() {
        String pkg = getPackageName();
        boolean opened = false;
        String[] intents = {
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool/.activity.LaunchManagerActivity"
        };
        for (String c : intents) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(c.split("/")[0], c.split("/")[1]);
                startActivity(i);
                opened = true;
                break;
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        if (!opened) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                startActivity(i);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
    }

    private void requestBatteryWhitelist() {
        String pkg = getPackageName();
        try {
            android.os.PowerManager pmgr = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (pmgr != null && !pmgr.isIgnoringBatteryOptimizations(pkg)) {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + pkg));
                        startActivity(i);
                        return;
                    } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e2) { try { android.util.Log.w("TaskPro","catch: "+e2.getMessage()); } catch(Exception __){} }
                } else {
                    MdSnackbar.show(root, "已在电池优化白名单中");
                }
            }
        } catch (Exception e) {
            MdSnackbar.show(root, "无法打开电池优化设置");
        }
    }

    private void showScriptDoc() {
        final MdDialog d = new MdDialog(this);
        d.title("脚本开发文档");
        d.messageScroll(ScriptDoc.DOC);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("一键复制", new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("脚本开发文档", ScriptDoc.DOC));
                    MdSnackbar.show(root, "文档已复制到剪贴板");
                } catch (Exception e) {
                    MdSnackbar.show(root, "复制失败");
                }
            }
        });
        d.show();
    }

    private void showHelp() {
        String help =
            "【定时任务】使用说明\n\n" +
            "1. 添加任务\n" +
            "   点右下角 ＋ → 选「HTTP 任务」或「Shell 脚本任务」\n" +
            "   · HTTP: 粘贴步骤 JSON(见「更多→脚本开发文档」)\n" +
            "   · Shell: 粘贴 sh 命令\n\n" +
            "2. 账号密码\n" +
            "   写死在脚本 JSON 里, 别人给你脚本直接粘贴即可。\n\n" +
            "3. 定时执行\n" +
            "   编辑任务设置 HH:MM, 到点自动执行;\n" +
            "   失败会自动延迟重试(可在编辑里改次数)。\n\n" +
            "4. 保证不被系统清理(重要)\n" +
            "   「更多」→ 后台常驻引导 + 电池优化白名单\n" +
            "   否则到点可能不执行。\n\n" +
            "5. 结果反馈\n" +
            "   默认成功静默、失败通知;\n" +
            "   可在「更多」打开「成功时也通知」。\n\n" +
            "6. 分发\n" +
            "   「更多」→ 导出任务, 分享文本给别人导入即可。";
        final MdDialog d = new MdDialog(this);
        d.title("使用帮助");
        d.message(help);
        d.actionPrimary("知道了", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    // ---------------- 工具 ----------------
    /** M3 紧凑 text button: 圆角胶囊, 弱底色, 短文字 */
    private TextView mdMiniBtn(String text, boolean danger, final Runnable action) {
        return mdMiniBtn(text, danger, 0, action);
    }

    private TextView mdMiniBtn(String text, boolean danger, int iconType, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        int fg = danger ? MdTheme.error(this) : MdTheme.primary(this);
        v.setTextColor(fg);
        if (iconType != 0) {
            v.setCompoundDrawablesWithIntrinsicBounds(
                    Icons.make(this, iconType, fg, 12), null, null, null);
            v.setCompoundDrawablePadding(dp(3));
        }
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(danger
                ? (MdTheme.isDark(this) ? 0x33CF6679 : 0x14CF6679)
                : (MdTheme.isDark(this) ? 0x33D0BCFF : 0x14D0BCFF));
        bg.setCornerRadius(dp(20));
        android.graphics.drawable.RippleDrawable rd = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), bg, null);
        v.setBackground(rd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP);
        lp.weight = 1;
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return v;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
}
