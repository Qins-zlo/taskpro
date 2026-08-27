package io.taskpro;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import io.taskpro.md.MdButton;
import io.taskpro.md.MdCard;
import io.taskpro.md.MdDialog;
import io.taskpro.md.MdSnackbar;
import io.taskpro.md.MdTextField;
import io.taskpro.md.MdTheme;

/**
 * 主界面 (原高级模式): 仿青龙面板
 *  Tab: 面板(任务) / 脚本 / AI助手 / 日志 / 更多
 *  更多: 环境变量 / 终端 / AI配置 / 基础模式
 */
public class AdvActivity extends Activity {

    private LinearLayout root;
    // 更多页子页面状态
    private static final int MORE_NONE = 0, MORE_ENVS = 1, MORE_TERM = 2, MORE_ART = 3, MORE_DOC = 4;
    private int morePage = MORE_NONE;
    private java.util.Set<Integer> artSelected = null;
    // 脚本市场页状态
    private boolean marketVisible = false;
    private LinearLayout marketPage = null;
    private LinearLayout marketList = null;
    private TextView marketStatus = null;
    private EditText marketSearch = null;
    private String taskSearchQuery = "";  // 任务搜索关键词
    private String scriptSearchQuery = "";  // 脚本搜索关键词
    private boolean scriptSearchLock = false;  // 防止搜索框递归
    private boolean taskSearchLock = false;  // 防止搜索框递归
    private JSONArray cachedMarketArr = null;  // 市场列表缓存, 搜索时本地过滤
    // 上传脚本表单 (文件选择回调填充)
    private static final int REQ_UPLOAD_FILE = 3102;
    private android.widget.EditText upAuthor = null, upName = null, upType = null,
            upVer = null, upNote = null, upContent = null;
    private LinearLayout contentWrap;
    private LinearLayout tabBar;
    private FrameLayout contentFrame;
    private View fab;
    private int currentTab = 0;
    private FrameLayout frame;
    private Handler ui = new Handler(Looper.getMainLooper());
    private boolean runtimeReady = false;
    private boolean quickExpanded = true;
    // —— 日志页（全新架构）——
    // 实时日志 TextView 缓存 (按脚本名索引, 自动刷新器只更新文本不重建页面)
    private java.util.Map<String, TextView> liveLogTextViews = new java.util.HashMap<String, TextView>();
    // 已渲染成"已完成"状态的脚本名 (避免每次 poll 都重建)
    private java.util.Set<String> renderedDoneScripts = new java.util.HashSet<String>();
    // 上次渲染时历史日志版本
    private int renderedLogVersion = -1;
    // 上次渲染时的实时脚本集合快照
    private java.util.Set<String> renderedLiveSet = new java.util.HashSet<String>();
    // 上次渲染时每个脚本的运行状态 (true=运行中) — 检测状态翻转 (完成→再次运行)
    private java.util.Map<String, Boolean> renderedScriptRunning = new java.util.HashMap<String, Boolean>();
    // 用户手动展开的实时日志卡片 (点击卡片切换)
    private java.util.Set<String> expandedLiveCards = new java.util.HashSet<String>();

    // 高级任务: id/name/scriptName/cron/enabled
    private static final String PREFS = "adv_tasks";
    private List<JSONObject> advTasks = new ArrayList<JSONObject>();
    private static final int REQ_IMPORT_FILE = 1001;
    private static final int REQ_BACKUP_SAVE = 1002;
    private static final int REQ_BACKUP_RESTORE = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadAdvTasks();
        // 启动时申请通知权限 (Android 13+ 脚本结果通知需要)
        Notifier.ensureChannel(this);
        TaskEngine.appContext = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        // 确保 cron 分钟闹钟在运行 + 基础任务闹钟全部重排 (主界面启动必做)
        try { CronAlarmReceiver.startMinuteAlarm(this); } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        try {
            AlarmScheduler.scheduleAll(this, TaskStore.load(this));
        } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MdTheme.surface(this));

        // 顶部
        root.addView(header());

        // 内容区 (FrameLayout 用于叠加 FAB)
        contentFrame = new FrameLayout(this);
        contentWrap = new LinearLayout(this);
        contentWrap.setOrientation(LinearLayout.VERTICAL);
        contentFrame.addView(contentWrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        addFab();

        // 底部 Tab
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(MdTheme.surfaceContainer(this));
        tabBar.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.addView(tabBar);

        setContentView(root);
        buildTabBar();
        switchTab(0);

        // 启动时有崩溃记录则轻提示 (不打断, 用户可到 更多→错误日志 查看)
        try {
            final java.io.File cf = new java.io.File(getFilesDir(), "crash.log");
            root.postDelayed(new Runnable() {
                public void run() {
                    if (cf.exists() && cf.length() > 0) {
                        io.taskpro.md.MdSnackbar.show(root, "检测到上次运行异常, 可到 更多→错误日志 查看详情");
                    }
                }
            }, 2500);
        } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }

        // 检查运行时
        runtimeReady = RuntimeManager.isReady(this);
        if (!runtimeReady) {
            showRuntimeInit();
        }
        // ═══ 启动自愈: 后台静默检查第三方包完整性 (含 pip) ═══
        // 背景: 旧版本 ensurePkgs 曾删除整个 site-packages 导致 pip 丢失,
        // 新版 termux_pkgs.tar.gz 已内含完整 pip; 这里每次启动后台恢复,
        // 保证用户随时点「安装依赖」都有 pip 可用, 无需等首次执行脚本。
        try {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (!RuntimeManager.isReady(AdvActivity.this)) return;
                        RuntimeManager.ensurePkgs(AdvActivity.this);
                        RuntimeManager.ensureDynload(AdvActivity.this);
                    } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            }).start();
        } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 顶部: 大标题 + 副标题 + 退出按钮 */
    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(MdTheme.surfaceContainer(this));
        h.setPadding(dp(16), dp(8), dp(16), dp(6));
        TextView title = new TextView(this);
        title.setText("TaskPro");
        title.setTextColor(MdTheme.onSurface(this));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        h.addView(title);
        return h;
    }



    /** FAB 悬浮添加按钮 (按当前 Tab 分发动作为添加任务/新建脚本/添加变量) */
    private void addFab() {
        ImageView f = new ImageView(this);
        f.setImageResource(R.drawable.ic_add);
        f.setScaleType(ImageView.ScaleType.CENTER);
        f.setColorFilter(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.primary(this));
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        f.setBackground(g);
        if (!MdTheme.isDark(this)) f.setElevation(dp(4));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(56), dp(56));
        fl.gravity = Gravity.BOTTOM | Gravity.END;
        fl.setMargins(0, 0, dp(18), dp(18));
        f.setLayoutParams(fl);
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentTab == 0) addAdvTask();
                else if (currentTab == 1) newScript();
                else if (currentTab == 2) addSubscription();
                else if (morePage == MORE_ENVS) addEnv();
                else MdSnackbar.show(root, "当前页面无需添加");
            }
        });
        fab = f;
        contentFrame.addView(f);
    }

    /** 首次使用: 解压捆绑的 Python 标准库 (可执行文件已随安装就绪) */
    private void showRuntimeInit() {
        final MdDialog d = new MdDialog(this);
        d.title("准备运行时");
        d.message("首次使用需要解压 Python 标准库 (~6MB)\n很快完成, 只需一次。");
        d.setCancelable(false);
        final TextView status = new TextView(this);
        status.setText("正在解压... 0%");
        status.setTextColor(MdTheme.onSurfaceVariant(this));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        d.content(status);
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final long start = System.currentTimeMillis();
                final int[] doneCount = {0};
                boolean ok = RuntimeManager.ensureReady(AdvActivity.this, new TarExtractor.Progress() {
                    public void onProgress(String file, int done, int total) {
                        doneCount[0]++;
                        final String fn = file;
                        ui.post(new Runnable() {
                            public void run() {
                                String s = fn;
                                if (s.length() > 34) s = s.substring(0, 34) + "...";
                                status.setText("正在解压: " + s);
                            }
                        });
                    }
                });
                final long ms = System.currentTimeMillis() - start;
                ui.post(new Runnable() {
                    public void run() {
                        d.dismiss();
                        if (ok) {
                            runtimeReady = true;
                            MdSnackbar.show(root, "运行时就绪 (" + (ms / 1000) + " 秒)");
                        } else {
                            MdSnackbar.show(root, "运行时解压失败, 请检查存储空间");
                        }
                    }
                });
            }
        }).start();
    }

    private void buildTabBar() {
        tabBar.removeAllViews();
        String[] names = {"面板", "脚本", "拉库", "AI 助手", "日志", "更多"};
        int[] icons = {R.drawable.ic_log, R.drawable.ic_import, R.drawable.ic_download, R.drawable.ic_ai, R.drawable.ic_list, R.drawable.ic_more};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean active = currentTab == idx;
            int tint = active ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            item.setLayoutParams(lp);

            // 胶囊选中背景 + 涟漪
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(active ? (MdTheme.isDark(this) ? 0x33D0BCFF : 0x1AD0BCFF) : Color.TRANSPARENT);
            bg.setCornerRadius(dp(18));
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(MdTheme.primary(this) & 0x2AFFFFFF),
                    bg, null);
            item.setBackground(ripple);
            item.setClickable(true);

            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(tint);
            item.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));

            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(10.5f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(tint);
            int pad = dp(3);
            t.setPadding(pad, pad, pad, 0);
            t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            item.addView(t);

            // 选中指示条 (顶部小圆点)
            if (active) {
                View dot = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(16), dp(3));
                dlp.bottomMargin = dp(3);
                dot.setLayoutParams(dlp);
                android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
                dg.setColor(MdTheme.primary(this));
                dg.setCornerRadius(dp(2));
                dot.setBackground(dg);
                item.addView(dot, 0);
            }

            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (idx == 3) {
                        try {
                            startActivity(new Intent(AdvActivity.this, AIActivity.class));
                        } catch (Exception e) {
                            MdSnackbar.show(root, "无法打开 AI 助手: " + e.toString());
                        }
                        return;
                    }
                    switchTab(idx);
                }
            });
            tabBar.addView(item);
        }
    }


    private void switchTab(int idx) {
        closeMarket();  // 切 tab 时关闭脚本市场浮层
        currentTab = idx;
        morePage = MORE_NONE;
        buildTabBar();
        contentWrap.removeAllViews();
        // 日志/更多页不需要 FAB
        if (fab != null) fab.setVisibility(idx == 4 || idx == 5 ? View.GONE : View.VISIBLE);
        if (idx == 0) renderTasks();
        else if (idx == 1) renderScripts();
        else if (idx == 2) renderSubscriptions();
        else if (idx == 4) {
            renderLogs2();   // 日志
            startLogAutoRefresh();
        } else if (idx == 5) renderMore();    // 更多
        else renderTerminal();              // 兜底
        // 非日志页停止自动刷新
        if (idx != 4) stopLogAutoRefresh();
        // 页面切换淡入动画
        try {
            contentWrap.setAlpha(0f);
            contentWrap.animate().alpha(1f).setDuration(180).start();
        } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    @Override
    public void onBackPressed() {
        if (marketVisible) {
            closeMarket();
            return;
        }
        super.onBackPressed();
    }


    // ================= 日志页自动刷新（轻量级）=================
    // 每秒执行一次, 做两件事:
    //  1. 检测是否需要重建页面 (新脚本出现/脚本状态变化/历史日志新增)
    //  2. 实时更新运行中脚本的文本 (不重建, 不打断用户操作)
    private Handler logRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable logRefresher = null;

    private void startLogAutoRefresh() {
        stopLogAutoRefresh();
        logRefresher = new Runnable() {
            public void run() {
                if (currentTab != 4) { stopLogAutoRefresh(); return; }
                // —— 判断是否需要重建页面 ——
                boolean needRebuild = false;
                // 1. 历史日志有新数据
                if (renderedLogVersion != TaskLog.version()) needRebuild = true;
                // 2. 实时脚本集合变化 (新增/移除)
                java.util.Set<String> allScripts = LiveLog.allScripts();
                if (!renderedLiveSet.equals(allScripts)) needRebuild = true;
                // 3. 有脚本刚跑完 (需要切到"已完成"状态 + 加隐藏按钮)
                for (String rn : allScripts) {
                    if (LiveLog.isDone(rn) && !renderedDoneScripts.contains(rn)) {
                        needRebuild = true;
                        break;
                    }
                }
                // 4. 脚本运行状态翻转 (已完成→再次运行 / 运行中→完成)
                if (!needRebuild) {
                    for (String rn : allScripts) {
                        boolean nowRunning = ScriptRunner.isRunning(rn);
                        Boolean prev = renderedScriptRunning.get(rn);
                        if (prev != null && prev.booleanValue() != nowRunning) {
                            needRebuild = true;
                            break;
                        }
                    }
                }
                if (needRebuild) {
                    renderLogs2();
                } else {
                    // —— 只更新运行中脚本的文本 ——
                    for (String rn : allScripts) {
                        TextView tv = liveLogTextViews.get(rn);
                        if (tv != null && ScriptRunner.isRunning(rn)) {
                            String newText = LiveLog.get(rn);
                            if (!newText.equals(tv.getText().toString())) {
                                tv.setText(newText.isEmpty() ? "(等待输出...)" : newText);
                            }
                        }
                    }
                }
                logRefreshHandler.postDelayed(this, 1000);
            }
        };
        logRefreshHandler.postDelayed(logRefresher, 600);
    }

    private void stopLogAutoRefresh() {
        if (logRefresher != null) {
            logRefreshHandler.removeCallbacks(logRefresher);
            logRefresher = null;
        }
    }

    // ================= 日志页 =================

    // 日志页筛选状态
    private int logFilterState = 0;      // 0全部 1成功 2失败
    private String logFilterTask = "";   // 空=全部脚本

    private void renderLogs2() {
        liveLogTextViews.clear();      // 清空缓存, 重建后重新填充
        renderedDoneScripts.clear();   // 清空完成标记, 重建后重新填充
        contentWrap.removeAllViews();
        // 整个日志页装进一个 ScrollView, 筛选栏+实时卡片+历史日志全部可滚动
        ScrollView rootSc = new ScrollView(this);
        rootSc.setFillViewport(true);
        contentWrap.addView(rootSc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(8), dp(14), dp(14));
        rootSc.addView(page);
        // —— 筛选栏 第一行: 全部/成功/失败 ——
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, 0, 0, dp(4));
        page.addView(filterRow);
        final int[] state = {logFilterState};
        final String[] selTask = {logFilterTask};
        final TextView[] stTv = new TextView[3];
        String[] labels = {"全部", "成功", "失败"};
        for (int i = 0; i < 3; i++) {
            final int fi = i;
            stTv[i] = new TextView(this);
            stTv[i].setText(labels[i]);
            stTv[i].setTextSize(12);
            stTv[i].setPadding(dp(14), dp(6), dp(14), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
            bg.setCornerRadius(dp(16));
            stTv[i].setBackground(bg);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2);
            lp2.weight = 1f;
            lp2.rightMargin = dp(6);
            stTv[i].setLayoutParams(lp2);
            stTv[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    state[0] = fi;
                    logFilterState = fi;
                    renderLogs2();
                }
            });
            filterRow.addView(stTv[i]);
        }
        // 刷新筛选按钮高亮
        for (int i = 0; i < 3; i++) {
            android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) stTv[i].getBackground();
            bg.setColor(i == state[0] ? MdTheme.primary(this) : (MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5));
            stTv[i].setTextColor(i == state[0] ? MdTheme.onPrimary(this) : MdTheme.onSurfaceVariant(this));
            stTv[i].setBackground(bg);
        }
        // —— 筛选栏 第二行: 脚本名筛选 + 操作按钮 ——
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, 0, 0, dp(4));
        page.addView(subRow);
        // 脚本名筛选 (动态收集出现过的任务名)
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        java.util.List<TaskLog.Entry> allE = TaskLog.listEntries(this);
        for (TaskLog.Entry en : allE) if (en.task != null && !en.task.isEmpty()) names.add(en.task);
        final TextView taskFilter = new TextView(this);
        taskFilter.setText(selTask[0].isEmpty() ? "全部脚本 ▾" : selTask[0]);
        taskFilter.setTextSize(12);
        taskFilter.setTextColor(MdTheme.primary(this));
        taskFilter.setPadding(dp(10), dp(5), dp(10), dp(5));
        taskFilter.setSingleLine(true);
        taskFilter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
        tbg.setColor(MdTheme.isDark(this) ? 0x33D0BCFF : 0x14D0BCFF);
        tbg.setCornerRadius(dp(16));
        taskFilter.setBackground(tbg);
        taskFilter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final MdDialog d = new MdDialog(AdvActivity.this);
                d.title("筛选脚本");
                LinearLayout box = new LinearLayout(AdvActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                TextView all = dlgChoiceText("全部脚本", selTask[0].isEmpty());
                all.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { selTask[0] = ""; logFilterTask = ""; d.dismiss(); renderLogs2(); }
                });
                box.addView(all);
                for (String n : names) {
                    TextView t = dlgChoiceText(n, n.equals(selTask[0]));
                    t.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            selTask[0] = n;
                            logFilterTask = n;
                            d.dismiss();
                            renderLogs2();
                        }
                    });
                    box.addView(t);
                }
                d.content(box);
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.show();
            }
        });
        subRow.addView(taskFilter, new LinearLayout.LayoutParams(0, -2, 1f));
        // 操作按钮 (移到第二行右侧, 不再浮动, 避免与筛选重叠)
        subRow.addView(miniBtn("一键复制", false, new Runnable() {
            public void run() {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "执行日志", TaskLog.load(AdvActivity.this)));
                MdSnackbar.show(root, "日志已复制");
            }
        }));
        subRow.addView(miniBtn("清空", true, new Runnable() {
            public void run() {
                final MdDialog d = new MdDialog(AdvActivity.this);
                d.title("清空日志");
                d.message("确定清空全部执行日志吗？此操作不可恢复。");
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.actionPrimary("清空", new Runnable() {
                    public void run() {
                        TaskLog.clear(AdvActivity.this);
                        d.dismiss();
                        switchTab(4);
                    }
                });
                d.show();
            }
        }));
        // —— 实时日志 (只显示运行中的脚本, 跑完自动转历史日志) ——
        final java.util.Set<String> allScripts = LiveLog.allScripts();
        boolean hasLive = false;
        for (String rn : allScripts) if (ScriptRunner.isRunning(rn)) { hasLive = true; break; }
        if (hasLive) {
            for (final String rn : allScripts) {
                boolean running = ScriptRunner.isRunning(rn);
                if (!running) continue;   // 已完成的直接在历史日志里看, 不占实时卡片
                MdCard liveCard = new MdCard(this, MdCard.OUTLINED, false);
                liveCard.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                liveCard.setLayoutParams(lp);
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                liveCard.addView(col);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView dot = new TextView(this);
                dot.setText("●");
                dot.setTextColor(0xFFFF9800);
                dot.setTextSize(10);
                dot.setPadding(0, 0, dp(6), 0);
                head.addView(dot);
                TextView nameTv = new TextView(this);
                nameTv.setText("⏵ " + rn + " (运行中) · 点击展开/收起");
                nameTv.setTextColor(MdTheme.onSurface(this));
                nameTv.setTextSize(13);
                nameTv.setTypeface(Typeface.DEFAULT_BOLD);
                head.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
                // 默认: 运行中自动展开, 用户手动收起则保持
                final boolean expandedNow = expandedLiveCards.contains(rn);
                // 展开/收起指示
                final TextView chevron = new TextView(this);
                chevron.setText(expandedNow ? "▾" : "▸");
                chevron.setTextColor(MdTheme.onSurfaceVariant(this));
                chevron.setTextSize(14);
                chevron.setPadding(dp(6), 0, dp(2), 0);
                head.addView(chevron);
                col.addView(head);
                TextView liveOut = new TextView(this);
                String log = LiveLog.get(rn);
                liveOut.setText(log.isEmpty() ? "(等待输出...)" : log);
                liveOut.setTextColor(MdTheme.onSurfaceVariant(this));
                liveOut.setTextSize(11);
                liveOut.setTypeface(Typeface.MONOSPACE);
                liveOut.setPadding(0, dp(4), 0, 0);
                liveOut.setMaxLines(expandedNow ? 100 : 3);
                liveOut.setEllipsize(android.text.TextUtils.TruncateAt.END);
                liveLogTextViews.put(rn, liveOut);    // 缓存, 供自动刷新器更新文本
                col.addView(liveOut);
                // 点击卡片: 展开/收起切换
                final boolean[] expanded = {expandedNow};
                liveCard.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        expanded[0] = !expanded[0];
                        if (expanded[0]) expandedLiveCards.add(rn);
                        else expandedLiveCards.remove(rn);
                        liveOut.setMaxLines(expanded[0] ? 100 : 3);
                        chevron.setText(expanded[0] ? "▾" : "▸");
                    }
                });
                page.addView(liveCard);
            }
        }
        // —— 日志列表 ——
        java.util.List<TaskLog.Entry> entries = new java.util.ArrayList<TaskLog.Entry>();
        for (TaskLog.Entry en : allE) {
            if (!selTask[0].isEmpty() && !selTask[0].equals(en.task)) continue;
            if (state[0] == 1 && !en.ok) continue;
            if (state[0] == 2 && en.ok) continue;
            entries.add(en);
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有符合条件的日志");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            page.addView(empty);
        }
        for (final TaskLog.Entry en : entries) {
            MdCard card = new MdCard(this, MdCard.OUTLINED, true);  // clickable=true 才能响应长按事件
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(6);
            card.setLayoutParams(cp);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            card.addView(col);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView dot = new TextView(this);
            dot.setText(en.ok ? "●" : "○");
            dot.setTextColor(en.ok ? 0xFF4CAF50 : 0xFFE53935);
            dot.setTextSize(10);
            dot.setPadding(0, 0, dp(6), 0);
            head.addView(dot);
            TextView taskTv = new TextView(this);
            taskTv.setText(en.task == null || en.task.isEmpty() ? "(未知)" : en.task);
            taskTv.setTextColor(MdTheme.onSurface(this));
            taskTv.setTextSize(13);
            taskTv.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(taskTv, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView tsTv = new TextView(this);
            tsTv.setText(en.ts);
            tsTv.setTextColor(MdTheme.onSurfaceVariant(this));
            tsTv.setTextSize(11);
            head.addView(tsTv);
            col.addView(head);
            final TextView bodyTv = new TextView(this);
            bodyTv.setText(extractLogTail(en.body));
            bodyTv.setTextColor(MdTheme.onSurfaceVariant(this));
            bodyTv.setTextSize(11);
            bodyTv.setTypeface(Typeface.MONOSPACE);
            bodyTv.setPadding(0, dp(4), 0, 0);
            // 收起长正文: 最多显示 6 行, 点击展开
            bodyTv.setMaxLines(6);
            bodyTv.setOnClickListener(new View.OnClickListener() {
                boolean expanded = false;
                public void onClick(View v) {
                    expanded = !expanded;
                    bodyTv.setMaxLines(expanded ? 1000 : 6);
                }
            });
            col.addView(bodyTv);
            // 长按复制单条 (加到 bodyTv 上, 因为 bodyTv 的 onClick 会拦截触摸事件,
            // 长按事件不会冒泡到父 View card, 所以必须直接绑在 bodyTv 上)
            bodyTv.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "执行日志", "[" + en.ts + "] " + en.task + "\n" + en.body));
                    MdSnackbar.show(root, "已复制该条日志");
                    return true;
                }
            });
            // 卡片本身(标题区/空白区)也支持长按复制
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "执行日志", "[" + en.ts + "] " + en.task + "\n" + en.body));
                    MdSnackbar.show(root, "已复制该条日志");
                    return true;
                }
            });
            page.addView(card);
        }
        // —— 保存渲染快照, 供自动刷新器判断是否需要重建 ——
        renderedLogVersion = TaskLog.version();
        renderedLiveSet.clear();
        renderedLiveSet.addAll(LiveLog.allScripts());
        renderedScriptRunning.clear();
        for (String rn : renderedLiveSet) {
            renderedScriptRunning.put(rn, ScriptRunner.isRunning(rn));
            if (LiveLog.isDone(rn)) renderedDoneScripts.add(rn);
        }
        }

    /** 弹窗内选项文本 (筛选脚本用) */
    private TextView dlgChoiceText(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText((active ? "● " : "○ ") + text);
        t.setTextColor(active ? MdTheme.primary(this) : MdTheme.onSurface(this));
        t.setTextSize(14);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    /** 提取日志正文中「退出码」之后的内容 (cmd 前缀 + 退出码之前的过程信息对用户无意义) */
    private static String extractLogTail(String body) {
        if (body == null || body.isEmpty()) return "";
        int idx = body.indexOf("退出码");
        if (idx >= 0) {
            return body.substring(idx);
        }
        // 没有退出码标记 (可能还在执行/被中断), 取最后 300 字符
        return body.length() > 300 ? "… " + body.substring(body.length() - 300) : body;
    }

    private TextView miniBtn(String text, boolean danger, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(danger ? 0xFFFFFFFF : MdTheme.primary(this));
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(danger ? 0xFFD32F2F : (MdTheme.isDark(this) ? 0xFF37474F : 0xFFECEFF1));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    // ================= 更多页 =================

    private void renderMore() {
        morePage = MORE_NONE;
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        contentWrap.addView(sc);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // 预留底部安全区 (Tab 导航栏 + 系统导航栏)
        page.setPadding(dp(12), dp(8), dp(12), dp(24));
        sc.addView(page);

        // ════════════════════════════════════════
        // 组1: 工具与功能
        // ════════════════════════════════════════
        addSectionHeader(page, "工具与功能");
        LinearLayout card1 = buildMoreCard();
        card1.addView(moreItem(IconFont.TERMINAL_IC, "终端", "交互式 Shell", new Runnable() {
            public void run() { openMore(MORE_TERM); }
        }));
        card1.addView(divider());
        card1.addView(moreItem(IconFont.KEY, "环境变量", "自定义 key=value", new Runnable() {
            public void run() { openMore(MORE_ENVS); }
        }));
        card1.addView(divider());
        card1.addView(moreItem(IconFont.ARCHIVE, "我的产物", "下载/生成的文件", new Runnable() {
            public void run() { openMore(MORE_ART); }
        }));
        card1.addView(divider());
        card1.addView(moreItem(IconFont.DOC, "脚本开发文档", "含 py/js/sh 教程 · Markdown 排版", new Runnable() {
            public void run() { openMore(MORE_DOC); }
        }));
        page.addView(card1);
        page.addView(spacer(dp(12)));

        // ════════════════════════════════════════
        // 组2: 数据管理
        // ════════════════════════════════════════
        addSectionHeader(page, "数据管理");
        LinearLayout card2 = buildMoreCard();
        card2.addView(moreItem(IconFont.IMPORT, "导入任务", "粘贴分享文本", new Runnable() {
            public void run() { showImportDialog(); }
        }));
        card2.addView(divider());
        card2.addView(moreItem(IconFont.SHARE, "导出任务", "分享全部任务", new Runnable() {
            public void run() { exportTasks(); }
        }));
        card2.addView(divider());
        card2.addView(moreItem(IconFont.SHARE, "导出日志", "分享运行日志", new Runnable() {
            public void run() { exportLogs(); }
        }));
        card2.addView(divider());
        card2.addView(moreItem(IconFont.ARCHIVE, "数据备份/恢复", "一键备份/恢复全部数据", new Runnable() {
            public void run() { showBackupDialog(); }
        }));
        page.addView(card2);
        page.addView(spacer(dp(12)));

        // ════════════════════════════════════════
        // 组3: 系统与优化
        // ════════════════════════════════════════
        addSectionHeader(page, "系统与优化");
        LinearLayout card3 = buildMoreCard();
        card3.addView(moreItem(IconFont.SHIELD, "后台常驻引导", "防被系统清理", new Runnable() {
            public void run() { guideBackground(); }
        }));
        card3.addView(divider());
        card3.addView(moreItem(IconFont.BATTERY, "电池优化白名单", "加入白名单, 后台不被杀", new Runnable() {
            public void run() { requestBatteryWhitelist(); }
        }));
        card3.addView(divider());
        card3.addView(moreItem(IconFont.CHART, "执行统计", "近 30 天任务走势", new Runnable() {
            public void run() { showStats(); }
        }));
        card3.addView(divider());
        card3.addView(moreItem(IconFont.SHIELD, "运行时修复", "自检+修复 Python 依赖", new Runnable() {
            public void run() { showRuntimeRepair(); }
        }));
        card3.addView(divider());
        card3.addView(moreItem(IconFont.BUG, "错误日志", "查看崩溃记录", new Runnable() {
            public void run() { showCrashLog(); }
        }));
        card3.addView(divider());
        card3.addView(moreItem(IconFont.SYSTEM_UPDATE, "检查更新", "GitHub 最新发布版", new Runnable() {
            public void run() { checkGitHubUpdate(); }
        }));
        page.addView(card3);
        page.addView(spacer(dp(12)));

        // ════════════════════════════════════════
        // 组4: 开关设置
        // ════════════════════════════════════════
        addSectionHeader(page, "通知与导出");
        LinearLayout card4 = buildMoreCard();
        card4.addView(moreSwitch("成功时也通知",
                "关闭后: 成功静默, 仅失败通知",
                Settings.notifyOnSuccess(this),
                new MoreSwitchCallback() {
                    public boolean onToggle(boolean newValue) {
                        Settings.setNotifyOnSuccess(AdvActivity.this, newValue);
                        return true;
                    }
                }));
        card4.addView(divider());
        card4.addView(moreSwitch("脚本定时结果通知",
                "定时脚本执行结果推送",
                Settings.notifyScriptCron(this),
                new MoreSwitchCallback() {
                    public boolean onToggle(boolean newValue) {
                        Settings.setNotifyScriptCron(AdvActivity.this, newValue);
                        return true;
                    }
                }));
        card4.addView(divider());
        card4.addView(moreSwitch("运行后自动导出",
                "产物自动复制到 Download",
                Settings.autoExportArtifacts(this),
                new MoreSwitchCallback() {
                    public boolean onToggle(boolean newValue) {
                        Settings.setAutoExportArtifacts(AdvActivity.this, newValue);
                        return true;
                    }
                }));
        page.addView(card4);
        page.addView(spacer(dp(12)));

        // ════════════════════════════════════════
        // 组5: 应用
        // ════════════════════════════════════════
        addSectionHeader(page, "应用");
        LinearLayout card5 = buildMoreCard();
        card5.addView(moreItem(IconFont.SETTINGS, "AI 配置", "Base URL / Key / 模型", new Runnable() {
            public void run() { openAIConfig(); }
        }));
        card5.addView(divider());
        card5.addView(moreItem(IconFont.BATTERY, "主题色", "自定义强调色", new Runnable() {
            public void run() { showAccentPicker(); }
        }));
        card5.addView(divider());
        card5.addView(moreItem(IconFont.HOME, "基础模式", "切换到简洁版主界面", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(AdvActivity.this, MainActivity.class));
                } catch (Exception e) {
                    MdSnackbar.show(root, "无法打开基础模式: " + e.toString());
                }
            }
        }));
        card5.addView(divider());
        card5.addView(moreItem(IconFont.STAR, "赞助开发者", "觉得好用可以支持一下", new Runnable() {
            public void run() { showSponsor(); }
        }));
        card5.addView(divider());
        card5.addView(moreItem(IconFont.INFO, "关于", "v" + appVersion() + " · 查看详情", new Runnable() {
            public void run() {
                MdDialog d = new MdDialog(AdvActivity.this);
                d.title("关于 定时任务Pro");
                d.message("版本: v" + appVersion() + "\n"
                        + "引擎: 纯 Java 程序化 UI\n"
                        + "运行时: Python 3 + Shell\n"
                        + "AI 助手: OpenAI 兼容 API\n"
                        + "任务: cron HTTP/Shell 定时执行\n\n"
                        + "构建于 Sandbox (qemu-x86_64 + JRE17)");
                d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
                d.show();
            }
        }));
        page.addView(card5);
        updateFab();
    }

    // ─── 辅助方法 ───

    /** 当前 App 版本名 (从 PackageInfo 动态读取, 与 AndroidManifest 保持同步) */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "7.65";
        }
    }

    /** 语义化版本比较: 按点分数字逐段比较, 正确处理 7.10 > 7.9
     *  返回 >0 表示 a>b, <0 表示 a<b, 0 表示相等 */
    private static int compareVersions(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }
    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    /** 构建统一卡片容器 */
    private LinearLayout buildMoreCard() {
        MdCard card = new MdCard(this, MdCard.OUTLINED, false);
        card.setPadding(dp(2), dp(4), dp(2), dp(4));
        return card;
    }

    /** 分组标题 */
    private void addSectionHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(MdTheme.primary(this));
        tv.setPadding(dp(16), dp(16), dp(16), dp(8));
        parent.addView(tv);
    }

    /** 垂直间距 */
    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp));
        return v;
    }

    /** 卡片内分割线 */
    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        v.setBackgroundColor(MdTheme.outlineVariant(this));
        return v;
    }

    /** 更多页菜单项 (精简版) */
    private View moreItem(String icon, String title, String sub, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(8), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        // 图标
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTypeface(IconFont.get(this));
        ic.setTextSize(20);
        ic.setTextColor(MdTheme.primary(this));
        ic.setMinWidth(dp(36));
        ic.setGravity(Gravity.CENTER);
        row.addView(ic);
        // 文本区
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(14);
        txt.addView(t1);
        if (sub != null && !sub.isEmpty()) {
            TextView t2 = new TextView(this);
            t2.setText(sub);
            t2.setTextColor(MdTheme.onSurfaceVariant(this));
            t2.setTextSize(11);
            txt.addView(t2);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Chevron 箭头
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(MdTheme.onSurfaceVariant(this));
        row.addView(arrow);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return row;
    }

    /** 开关回调接口 */
    private interface MoreSwitchCallback {
        boolean onToggle(boolean newValue);
    }

    /** 开关行 (使用原生 Switch) */
    private View moreSwitch(String title, String sub, boolean initialValue, final MoreSwitchCallback cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        // 文本区
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(14);
        txt.addView(t1);
        if (sub != null && !sub.isEmpty()) {
            TextView t2 = new TextView(this);
            t2.setText(sub);
            t2.setTextColor(MdTheme.onSurfaceVariant(this));
            t2.setTextSize(11);
            txt.addView(t2);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 原生 Switch
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(initialValue);
        sw.setThumbTintList(initialValue
                ? android.content.res.ColorStateList.valueOf(MdTheme.primary(this))
                : null);
        sw.setTrackTintList(initialValue
                ? android.content.res.ColorStateList.valueOf(
                        MdTheme.primaryContainer(this))
                : null);
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean newVal = !sw.isChecked();
                if (cb.onToggle(newVal)) {
                    sw.setChecked(newVal);
                    sw.setThumbTintList(newVal
                            ? android.content.res.ColorStateList.valueOf(MdTheme.primary(AdvActivity.this))
                            : null);
                    sw.setTrackTintList(newVal
                            ? android.content.res.ColorStateList.valueOf(
                                    MdTheme.primaryContainer(AdvActivity.this))
                            : null);
                }
            }
        });
        return row;
    }

    /** AI 配置对话框 (更多 → AI 配置) */
    private void openAIConfig() {
        final MdDialog d = new MdDialog(this);
        d.title("AI 配置");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 提供商选择
        final android.widget.Spinner providerSp = new android.widget.Spinner(this);
        String[] provNames = new String[AIConfig.PROVIDERS.length];
        String[] provTips = new String[AIConfig.PROVIDERS.length];
        for (int i = 0; i < AIConfig.PROVIDERS.length; i++) {
            provNames[i] = AIConfig.PROVIDERS[i][0];
            provTips[i] = AIConfig.PROVIDERS[i][3];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, provNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSp.setAdapter(adapter);
        providerSp.setSelection(AIConfig.providerIndex(this));
        // 提示
        final TextView provTip = new TextView(this);
        int curIdx = AIConfig.providerIndex(this);
        provTip.setText(AIConfig.PROVIDERS[curIdx][3]);
        provTip.setTextColor(MdTheme.onSurfaceVariant(this));
        provTip.setTextSize(11);
        provTip.setPadding(dp(2), dp(2), dp(2), dp(6));
        box.addView(providerSp);
        box.addView(provTip);

        // Base URL
        final android.widget.EditText baseEt = new android.widget.EditText(this);
        baseEt.setHint("Base URL (OpenAI 兼容, 如 https://api.deepseek.com/v1)");
        baseEt.setText(AIConfig.baseUrl(this));
        box.addView(aiField(baseEt));

        // API Key
        final android.widget.EditText keyEt = new android.widget.EditText(this);
        keyEt.setHint("API Key");
        keyEt.setText(AIConfig.apiKey(this));
        keyEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(aiField(keyEt));

        // 模型
        final android.widget.EditText modelEt = new android.widget.EditText(this);
        modelEt.setHint("模型 (如 deepseek-chat / qwen-plus / gpt-4o-mini)");
        modelEt.setText(AIConfig.model(this));
        box.addView(aiField(modelEt));
        // 联网搜索开关
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, dp(8), 0, dp(4));
        final android.widget.CheckBox searchCb = new android.widget.CheckBox(this);
        searchCb.setChecked(AIConfig.searchEnabled(AdvActivity.this));
        searchCb.setText(" 联网搜索 (Bing)");
        searchCb.setTextColor(MdTheme.onSurface(AdvActivity.this));
        searchCb.setTextSize(14);
        searchRow.addView(searchCb);
        box.addView(searchRow);

        // 切换提供商时自动填充
        providerSp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent,
                    android.view.View view, int pos, long id) {
                provTip.setText(AIConfig.PROVIDERS[pos][3]);
                if (pos < AIConfig.PROVIDERS.length - 1) {
                    // 非自定义: 自动填充 Base URL 和模型
                    baseEt.setText(AIConfig.PROVIDERS[pos][1]);
                    modelEt.setText(AIConfig.PROVIDERS[pos][2]);
                }
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        TextView tip = new TextView(this);
        tip.setText("兼容 OpenAI Chat Completions 格式的服务均可使用。\n选择提供商后自动填充, 也可手动修改。\n配置后前往 AI 助手 使用。");
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
                if (base.isEmpty() || model.isEmpty()) {
                    MdSnackbar.show(root, "请填写 Base URL 和模型名称");
                    return;
                }
                AIConfig.save(AdvActivity.this, base, key, model);
                AIConfig.setSearchEnabled(AdvActivity.this, searchCb.isChecked());
                MdSnackbar.show(root, "已保存, 可前往 AI 助手使用" + (searchCb.isChecked() ? "" : " (联网搜索已关闭)"));
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

    /** 打开更多页的子页面 (环境变量/终端) */
    private void openMore(int page) {        morePage = page;
        contentWrap.removeAllViews();
        // 顶部返回行
        addBackRow("返回");
        if (page == MORE_ENVS) renderEnvs();
        else if (page == MORE_TERM) renderTerminal();
        else if (page == MORE_ART) renderArtifacts();
        else if (page == MORE_DOC) renderScriptDocPage();
        else renderMore();
        updateFab();
    }

    // ================= 我的产物页 =================

    private void renderArtifacts() {
        final java.util.List<ProdFile> arts = scanArtifacts(this, 200);
        if (artSelected == null) artSelected = new java.util.HashSet<Integer>();
        final java.util.Set<Integer> selected = artSelected;
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        contentWrap.addView(sc);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(8), dp(14), dp(14));
        sc.addView(page);
        // 顶部信息行
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(topRow);
        TextView topTxt = new TextView(this);
        topTxt.setText("共 " + arts.size() + " 个产物文件");
        topTxt.setTextColor(MdTheme.onSurface(this));
        topTxt.setTextSize(15);
        topTxt.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(topTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 自动导出开关
        final boolean autoOn = Settings.autoExportArtifacts(this);
        final TextView swV = new TextView(this);
        swV.setText(autoOn ? "自动导出: 开" : "自动导出: 关");
        swV.setTextColor(autoOn ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        swV.setTextSize(12);
        swV.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable swBg = new android.graphics.drawable.GradientDrawable();
        swBg.setColor(MdTheme.isDark(this) ? 0xFF333333 : 0xFFEEEEEE);
        swBg.setCornerRadius(dp(12));
        swV.setBackground(swBg);
        swV.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Settings.setAutoExportArtifacts(AdvActivity.this, !autoOn);
                openMore(MORE_ART);
            }
        });
        topRow.addView(swV);
        if (arts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无产物\n\n脚本运行产生的文件 (图片/下载/打包等) 会自动出现在这里,\n可随时导出到手机 Download 或删除。");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(50), 0, dp(40));
            page.addView(empty);
            return;
        }
        // 批量操作栏
        LinearLayout batchRow = new LinearLayout(this);
        batchRow.setOrientation(LinearLayout.HORIZONTAL);
        batchRow.setPadding(0, dp(6), 0, dp(6));
        page.addView(batchRow);
        final TextView selInfo = new TextView(this);
        String selText = "已选 " + selected.size() + " 个" + (selected.isEmpty() ? "" : "  (点击导出)");
        selInfo.setText(selText);
        selInfo.setTextColor(selected.isEmpty() ? MdTheme.onSurfaceVariant(this) : 0xFFFFFFFF);
        selInfo.setTextSize(12);
        selInfo.setTypeface(Typeface.DEFAULT_BOLD);
        selInfo.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
        selBg.setColor(selected.isEmpty() ? 0x00000000 : MdTheme.primary(this));
        selBg.setCornerRadius(dp(8));
        selInfo.setBackground(selBg);
        selInfo.setTextColor(MdTheme.onSurfaceVariant(this));
        selInfo.setTextSize(12);
        batchRow.addView(selInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        batchRow.addView(miniBtn("全选", false, new Runnable() {
            public void run() {
                for (int i = 0; i < arts.size(); i++) selected.add(i);
                openMore(MORE_ART);
            }
        }));
        batchRow.addView(miniBtn("全不选", false, new Runnable() {
            public void run() {
                selected.clear();
                openMore(MORE_ART);
            }
        }));
        batchRow.addView(miniBtn("导出选中", false, new Runnable() {
            public void run() {
                if (selected.isEmpty()) { MdSnackbar.show(root, "请先选择文件"); return; }
                java.util.List<ProdFile> sel = new java.util.ArrayList<ProdFile>();
                for (int i : selected) sel.add(arts.get(i));
                int n = exportToDownload(AdvActivity.this, sel, "脚本产物");
                MdSnackbar.show(root, "已导出 " + n + "/" + sel.size() + " 个 → Download/脚本产物/");
                selected.clear();
                openMore(MORE_ART);
            }
        }));
        batchRow.addView(miniBtn("删除选中", true, new Runnable() {
            public void run() {
                if (selected.isEmpty()) { MdSnackbar.show(root, "请先选择文件"); return; }
                final MdDialog d = new MdDialog(AdvActivity.this);
                d.title("批量删除");
                d.message("确定删除选中的 " + selected.size() + " 个文件？不可恢复。");
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.actionPrimary("删除", new Runnable() {
                    public void run() {
                        d.dismiss();
                        for (int i : selected) arts.get(i).file.delete();
                        selected.clear();
                        openMore(MORE_ART);
                    }
                });
                d.show();
            }
        }));
        // 文件列表
        MdCard card = new MdCard(this, MdCard.OUTLINED, false);
        card.setPadding(dp(4), dp(2), dp(4), dp(2));
        page.addView(card);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (int idx = 0; idx < arts.size(); idx++) {
            final int fi = idx;
            final ProdFile p = arts.get(idx);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(6), dp(8));
            // 选中行高亮背景
            if (selected.contains(fi)) {
                android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                rowBg.setColor(MdTheme.isDark(this) ? 0x332A6FDB : 0x14A5C8FF);
                rowBg.setCornerRadius(dp(8));
                row.setBackground(rowBg);
            }
            // 选择框
            final TextView chk = new TextView(this);
            chk.setText(selected.contains(fi) ? "☑" : "☐");
            chk.setTextColor(selected.contains(fi) ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
            chk.setTextSize(16);
            chk.setMinWidth(dp(28));
            chk.setGravity(Gravity.CENTER);
            chk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (selected.contains(fi)) selected.remove(fi);
                    else selected.add(fi);
                    chk.setText(selected.contains(fi) ? "☑" : "☐");
                    chk.setTextColor(selected.contains(fi) ? MdTheme.primary(AdvActivity.this) : MdTheme.onSurfaceVariant(AdvActivity.this));
                    // 更新选中行高亮
                    row.setBackground(null);
                    if (selected.contains(fi)) {
                        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                        rowBg.setColor(MdTheme.isDark(AdvActivity.this) ? 0x332A6FDB : 0x14A5C8FF);
                        rowBg.setCornerRadius(dp(8));
                        row.setBackground(rowBg);
                    }
                    // 刷新选中计数
                    selInfo.setText("已选 " + selected.size() + " 个" + (selected.isEmpty() ? "" : "  (点击导出)"));
                    selInfo.setTextColor(selected.isEmpty() ? MdTheme.onSurfaceVariant(AdvActivity.this) : 0xFFFFFFFF);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(selected.isEmpty() ? 0x00000000 : MdTheme.primary(AdvActivity.this));
                    bg.setCornerRadius(dp(8));
                    selInfo.setBackground(bg);
                    selInfo.setText("已选 " + selected.size() + " 个");
                }
            });
            row.addView(chk);
            // 图标 (图片文件显示缩略图, 点击全屏预览)
            if (p.isImage) {
                ImageView thumb = imageThumb(p.file, dp(40), dp(40));
                thumb.setPadding(dp(2), 0, dp(6), 0);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(44), dp(44));
                thumb.setLayoutParams(tlp);
                thumb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showImagePreview(p.file); }
                });
                row.addView(thumb);
            } else {
                TextView ic = new TextView(this);
                ic.setText(p.icon);
                ic.setTypeface(IconFont.get(this));
                ic.setTextSize(16);
                ic.setTextColor(MdTheme.primary(this));
                ic.setMinWidth(dp(28));
                ic.setGravity(Gravity.CENTER);
                row.addView(ic);
            }
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(p.file.getName());
            t1.setTextColor(MdTheme.onSurface(this));
            t1.setTextSize(13);
            txt.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(p.size + " · " + sdf.format(new java.util.Date(p.mtime))
                    + " · " + p.parent);
            t2.setTextColor(MdTheme.onSurfaceVariant(this));
            t2.setTextSize(11);
            txt.addView(t2);
            row.addView(txt, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(miniBtn("导出", false, new Runnable() {
                public void run() {
                    if (exportFileToDownload(AdvActivity.this, p.file, p.parent)) {
                        MdSnackbar.show(root, "已导出 → Download/" + p.parent + "/");
                    } else {
                        MdSnackbar.show(root, "导出失败");
                    }
                }
            }));
            row.addView(miniBtn("删除", true, new Runnable() {
                public void run() {
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title("删除产物");
                    d.message("确定删除 " + p.file.getName() + " ?\n删除后不可恢复。");
                    d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                    d.actionPrimary("删除", new Runnable() {
                        public void run() {
                            d.dismiss();
                            p.file.delete();
                            selected.remove(fi);
                            openMore(MORE_ART);
                        }
                    });
                    d.show();
                }
            }));
            // 行点击 = 选中
            final LinearLayout rowRef = row;
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 切换选中状态
                    if (selected.contains(fi)) selected.remove(fi);
                    else selected.add(fi);
                    chk.setText(selected.contains(fi) ? "☑" : "☐");
                    chk.setTextColor(selected.contains(fi) ? MdTheme.primary(AdvActivity.this) : MdTheme.onSurfaceVariant(AdvActivity.this));
                    selInfo.setText("已选 " + selected.size() + " 个");
                }
            });
            card.addView(row);
        }
    }

    /** 生成图片缩略图 (按目标尺寸采样, 防 OOM) */
    private ImageView imageThumb(File f, int w, int h) {
        ImageView iv = new ImageView(this);
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / sample > w * 2 || opts.outHeight / sample > h * 2) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setClipToOutline(true);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFF000000);
                bg.setCornerRadius(dp(6));
                iv.setBackground(bg);
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        return iv;
    }

    /** 全屏图片预览弹窗 */
    private void showImagePreview(final File f) {
        try {
            final MdDialog d = new MdDialog(this);
            d.title(f.getName());
            ImageView big = new ImageView(this);
            // 按屏幕宽高采样加载, 避免大图 OOM
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int sample = 1;
            while (opts.outWidth / sample > sw || opts.outHeight / sample > sh * 0.6f) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, sample);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp == null) { MdSnackbar.show(root, "无法解析该图片"); return; }
            big.setImageBitmap(bmp);
            big.setScaleType(ImageView.ScaleType.FIT_CENTER);
            big.setAdjustViewBounds(true);
            d.content(big);
            d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
            d.action("导出", new Runnable() {
                public void run() {
                    if (exportFileToDownload(AdvActivity.this, f, "脚本产物")) {
                        MdSnackbar.show(root, "已导出 → Download/脚本产物/");
                    } else {
                        MdSnackbar.show(root, "导出失败");
                    }
                    d.dismiss();
                }
            });
            d.show();
        } catch (Exception e) {
            MdSnackbar.show(root, "预览失败: " + e.getMessage());
        }
    }

    /** 添加返回行 */
    private void addBackRow(String text) {
        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView backIc = new TextView(this);
        backIc.setText("\uE5C4");   // arrow_back
        backIc.setTypeface(IconFont.get(this));
        backIc.setTextSize(20);
        backIc.setTextColor(MdTheme.primary(this));
        backIc.setPadding(dp(8), dp(4), dp(6), dp(4));
        backRow.addView(backIc);
        TextView backTxt = new TextView(this);
        backTxt.setText(text);
        backTxt.setTextColor(MdTheme.primary(this));
        backTxt.setTextSize(14);
        backRow.addView(backTxt);
        backRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchTab(5); }
        });
        contentWrap.addView(backRow);
    }

    /** 根据当前页面状态更新 FAB */
    private void updateFab() {
        if (fab == null) return;
        if (morePage == MORE_ENVS) fab.setVisibility(View.VISIBLE);
        else fab.setVisibility((currentTab == 4 || currentTab == 5)
                ? View.GONE : View.VISIBLE);
    }

    // ================= 面板(任务) =================

    /** 下拉刷新手势: 在 ScrollView 顶部下拉超过阈值(60dp)触发回调 (防误触: 拖动距离超过70%才触发) */
    private void addPullToRefresh(final ScrollView sc, final Runnable onRefresh) {
        sc.setOnTouchListener(new View.OnTouchListener() {
            float startY = 0;
            float startX = 0;
            boolean tracking = false;
            long downTime = 0;
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        startX = event.getX();
                        tracking = true;
                        downTime = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!tracking) break;
                        float dy = event.getY() - startY;
                        // 只在滚动容器处于顶部且明显下拉时触发
                        if (dy > dp(60) && sc.getScrollY() <= 0) {
                            tracking = false; // 只触发一次
                            if (onRefresh != null) onRefresh.run();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        tracking = false;
                        break;
                }
                return false; // 不拦截, 交给 ScrollView 正常处理
            }
        });
    }

    private void renderTasks() {
        contentWrap.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView sc = new ScrollView(this);
        sc.addView(page);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(-1, -1));
        // —— 下拉刷新手势: 列表在顶部时下拉超过阈值 → 刷新 ——
        addPullToRefresh(sc, new Runnable() {
            public void run() {
                MdSnackbar.show(root, "刷新中...");
                renderTasks();
                MdSnackbar.show(root, "已刷新");
            }
        });


        // 搜索框（紧贴顶部栏）
        final LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.HORIZONTAL);
        searchWrap.setGravity(Gravity.CENTER_VERTICAL);
        searchWrap.setPadding(dp(10), dp(6), dp(10), dp(6));
        android.graphics.drawable.GradientDrawable swbg = new android.graphics.drawable.GradientDrawable();
        swbg.setColor(MdTheme.surfaceContainerHigh(this));
        swbg.setCornerRadius(dp(22));
        swbg.setStroke(dp(1), MdTheme.outlineVariant(this));
        searchWrap.setBackground(swbg);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(-1, -2);
        swlp.bottomMargin = dp(8);
        searchWrap.setLayoutParams(swlp);
        // 放大镜图标
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageDrawable(Icons.make(this, Icons.DOC, MdTheme.onSurfaceVariant(this), 16));
        searchIcon.setPadding(dp(8), 0, dp(2), 0);
        searchWrap.addView(searchIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        // 输入框
        final android.widget.EditText searchBox = new android.widget.EditText(this);
        searchBox.setHint("搜索任务");
        searchBox.setText(taskSearchQuery);
        searchBox.setTextSize(13);
        searchBox.setTextColor(MdTheme.onSurface(this));
        searchBox.setHintTextColor(MdTheme.onSurfaceVariant(this));
        searchBox.setSingleLine(true);
        searchBox.setPadding(dp(6), 0, dp(6), 0);
        searchBox.setBackgroundColor(Color.TRANSPARENT);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchWrap.addView(searchBox, new LinearLayout.LayoutParams(0, dp(40), 1f));
        // 清空按钮（有文字时显示）
        final ImageView clearBtn = new ImageView(this);
        clearBtn.setImageDrawable(Icons.make(this, Icons.CROSS, MdTheme.onSurfaceVariant(this), 14));
        clearBtn.setPadding(dp(6), dp(6), dp(8), dp(6));
        clearBtn.setVisibility(taskSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                taskSearchQuery = "";
                searchBox.setText("");
            }
        });
        searchWrap.addView(clearBtn);
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (taskSearchLock) return;
                taskSearchQuery = s.toString().trim();
                clearBtn.setVisibility(taskSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                taskSearchLock = true;
                renderTasks();
                taskSearchLock = false;
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        page.addView(searchWrap);

        if (advTasks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无高级任务\n点右下角 ＋ 添加");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            page.addView(empty);
        }
        for (int i = 0; i < advTasks.size(); i++) {
            final int idx = i;
            final JSONObject o = advTasks.get(i);
            // 搜索过滤
            if (!taskSearchQuery.isEmpty()) {
                String hay = (o.optString("name", "") + " " + o.optString("script", "") + " " + o.optString("cron", "")).toLowerCase();
                if (!hay.contains(taskSearchQuery.toLowerCase())) continue;
            }
            MdCard card = new MdCard(this, MdCard.OUTLINED, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(8);
            card.setLayoutParams(cp);
            // 整体横向：左侧状态色条 + 内容
            LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.HORIZONTAL);
            outer.setGravity(Gravity.TOP);
            card.addView(outer);
            boolean en0 = o.optBoolean("enabled", true);
            View accent = new View(this);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(4), -1);
            alp.rightMargin = dp(10);
            accent.setLayoutParams(alp);
            android.graphics.drawable.GradientDrawable ag = new android.graphics.drawable.GradientDrawable();
            ag.setColor(en0 ? MdTheme.primary(this) : MdTheme.outlineVariant(this));
            ag.setCornerRadius(dp(2));
            accent.setBackground(ag);
            outer.addView(accent);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            outer.addView(row, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView name = new TextView(this);
            name.setText(o.optString("name", "?"));
            name.setTextColor(MdTheme.onSurface(this));
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);
            TextView meta = new TextView(this);
            String cron = o.optString("cron", "");
            String script = o.optString("script", "");
            boolean en = o.optBoolean("enabled", true);
            meta.setText((en ? "●" : "○") + " cron: " + cron + "  |  脚本: " + script);
            meta.setTextColor(en ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
            meta.setTextSize(11);
            meta.setPadding(0, dp(4), 0, 0);
            row.addView(meta);
            // 操作行
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(6), 0, 0);
            row.addView(ops);
            ops.addView(smallBtn(en ? "停用" : "启用", false, new Runnable() {
                public void run() {
                    try {
                        o.put("enabled", !en);
                        saveAdvTasks();
                        // 同步到调度器
                        try { AlarmScheduler.scheduleAll(AdvActivity.this, TaskStore.load(AdvActivity.this)); } catch (Throwable ig) { try { android.util.Log.w("TaskPro","catch: "+ig.getMessage()); } catch(Exception __){} }
                        MdSnackbar.show(root, (en ? "已停用" : "已启用") + "「" + o.optString("name") + "」");
                        renderTasks();
                    } catch (Exception e) {
                        MdSnackbar.show(root, "操作失败: " + e.toString());
                    }
                }
            }));
            ops.addView(smallBtn("立即执行", false, new Runnable() {
                public void run() { runAdvTask(idx); }
            }));
            ops.addView(smallBtn("编辑", false, new Runnable() {
                public void run() { editAdvTask(idx); }
            }));
            ops.addView(smallBtn("删除", true, new Runnable() {
                public void run() {
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title("删除任务");
                    d.message("确定删除「" + o.optString("name") + "」吗?");
                    d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                    d.actionPrimary("删除", new Runnable() {
                        public void run() {
                            advTasks.remove(idx);
                            saveAdvTasks();
                            d.dismiss();
                            renderTasks();
                        }
                    });
                    d.show();
                }
            }));
            // ==== 拖拽排序支持 ====
            final int dragFrom = idx;
            card.setLongClickable(true);
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    if (!taskSearchQuery.isEmpty()) return false;  // 搜索中不拖拽, 避免索引错位
                    android.content.ClipData cd = android.content.ClipData.newPlainText("taskpro", String.valueOf(dragFrom));
                    v.startDrag(cd, new View.DragShadowBuilder(v), null, 0);
                    v.setAlpha(0.4f);
                    MdSnackbar.show(root, "拖动到目标位置松手");
                    return true;
                }
            });
            card.setOnDragListener(new View.OnDragListener() {
                public boolean onDrag(View v, android.view.DragEvent e) {
                    switch (e.getAction()) {
                        case android.view.DragEvent.ACTION_DRAG_STARTED:
                            return true;
                        case android.view.DragEvent.ACTION_DRAG_ENTERED:
                            v.setAlpha(0.55f);
                            return true;
                        case android.view.DragEvent.ACTION_DRAG_EXITED:
                            v.setAlpha(1f);
                            return true;
                        case android.view.DragEvent.ACTION_DROP:
                            v.setAlpha(1f);
                            try {
                                int from = Integer.parseInt(e.getClipData().getItemAt(0).getText().toString());
                                int to = dragFrom;
                                if (from != to) {
                                    JSONObject moved = advTasks.remove(from);
                                    advTasks.add(to, moved);
                                    saveAdvTasks();
                                    renderTasks();
                                    MdSnackbar.show(root, "已调整顺序: " + moved.optString("name"));
                                }
                            } catch (Exception ex) { try { android.util.Log.w("TaskPro","catch: "+ex.getMessage()); } catch(Exception __){} }
                            return true;
                        case android.view.DragEvent.ACTION_DRAG_ENDED:
                            v.setAlpha(1f);
                            return true;
                    }
                    return false;
                }
            });
            page.addView(card);
        }

        // 底部留白 (给 FAB 让位)
        TextView pad = new TextView(this);
        pad.setHeight(dp(72));
        page.addView(pad);
    }

    /** 运行环境自检, 对话框展示结果 */
    private void runSelfCheck() {
        if (!RuntimeManager.isReady(this)) {
            // 先解压运行时
            final MdDialog prep = new MdDialog(this);
            prep.title("准备运行时");
            prep.message("首次使用需要解压 Python 标准库 (~6MB)\n很快完成。");
            prep.setCancelable(false);
            prep.show();
            new Thread(new Runnable() {
                public void run() {
                    final boolean ok = RuntimeManager.ensureReady(AdvActivity.this, null);
                    ui.post(new Runnable() {
                        public void run() {
                            prep.dismiss();
                            if (ok) {
                                MdSnackbar.show(root, "运行时就绪, 开始自检");
                                doSelfCheckDialog();
                            } else {
                                MdSnackbar.show(root, "运行时解压失败, 无法自检");
                            }
                        }
                    });
                }
            }).start();
            return;
        }
        doSelfCheckDialog();
    }

    private void doSelfCheckDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("环境自检");
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final TextView waiting = new TextView(this);
        waiting.setText("正在检测...");
        waiting.setTextColor(MdTheme.onSurfaceVariant(this));
        waiting.setTextSize(13);
        waiting.setPadding(0, dp(8), 0, dp(4));
        list.addView(waiting);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        d.content(sc);
        d.setCancelable(false);

        // 复制按钮 (结果出来后启用)
        final Runnable[] copyAction = new Runnable[1];
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("复制报告", new Runnable() {
            public void run() {
                if (copyAction[0] != null) copyAction[0].run();
                else MdSnackbar.show(root, "检测未完成");
            }
        });
        d.show();

        new SelfCheck(this).run(new SelfCheck.Listener() {
            public void onItem(SelfCheck.Item item) {
                // 移除 waiting
                if (list.indexOfChild(waiting) >= 0) list.removeView(waiting);
                LinearLayout row = new LinearLayout(AdvActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(4), dp(6));
                TextView mark = new TextView(AdvActivity.this);
                mark.setText("");
                int mkColor = item.ok ? 0xFF4CAF50 : MdTheme.error(AdvActivity.this);
                mark.setCompoundDrawablesWithIntrinsicBounds(
                        Icons.make(AdvActivity.this, item.ok ? Icons.CHECK : Icons.CROSS, mkColor, 14),
                        null, null, null);
                mark.setTextSize(16);
                mark.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(mark, new LinearLayout.LayoutParams(dp(28), -2));
                LinearLayout txt = new LinearLayout(AdvActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                TextView nm = new TextView(AdvActivity.this);
                nm.setText(item.name);
                nm.setTextColor(MdTheme.onSurface(AdvActivity.this));
                nm.setTextSize(13);
                nm.setTypeface(Typeface.DEFAULT_BOLD);
                txt.addView(nm);
                TextView dt = new TextView(AdvActivity.this);
                dt.setText(item.detail);
                dt.setTextColor(item.ok ? MdTheme.onSurfaceVariant(AdvActivity.this) : MdTheme.error(AdvActivity.this));
                dt.setTextSize(11);
                txt.addView(dt);
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
                list.addView(row);
            }
            public void onDone(java.util.List<SelfCheck.Item> items) {
                // 汇总
                int okCount = 0;
                for (SelfCheck.Item i : items) if (i.ok) okCount++;
                TextView sum = new TextView(AdvActivity.this);
                sum.setText("通过 " + okCount + "/" + items.size() + (okCount == items.size() ? " · 全部正常" : " (见上方失败项)"));
                sum.setTextColor(okCount == items.size() ? 0xFF4CAF50 : MdTheme.error(AdvActivity.this));
                sum.setTextSize(13);
                sum.setTypeface(Typeface.DEFAULT_BOLD);
                sum.setPadding(dp(4), dp(10), dp(4), dp(2));
                list.addView(sum, 0);
                // 复制报告
                final String report = SelfCheck.toReport(items);
                copyAction[0] = new Runnable() {
                    public void run() {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("自检报告", report));
                        MdSnackbar.show(root, "报告已复制到剪贴板");
                    }
                };
                d.setCancelable(true);
            }
        });
    }

    /** cron 快捷设置: 选频率+时间 → 自动生成 cron 表达式填入文本框 (不懂 cron 的用户用) */
    private void showCronQuick(final MdTextField cronField) {
        final MdDialog d = new MdDialog(this);
        d.title("cron 快捷设置");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        final String[] freqs = {"每小时", "每 2 小时", "每天", "每周", "每月"};
        final int[] sel = {2}; // 默认每天
        final MdButton[] btns = new MdButton[freqs.length];
        TextView freqLabel = new TextView(this);
        freqLabel.setText("1. 选频率");
        freqLabel.setTextColor(MdTheme.onSurfaceVariant(this));
        freqLabel.setTextSize(12);
        form.addView(freqLabel);
        for (int i = 0; i < freqs.length; i++) {
            final int fi = i;
            MdButton b = new MdButton(this, freqs[i], MdButton.TONAL);
            b.setHeight(dp(36)); b.setMinHeight(dp(36));
            b.setTextSize(13);
            btns[i] = b;
            b.setAlpha(fi == sel[0] ? 1f : 0.4f);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sel[0] = fi;
                    for (int j = 0; j < btns.length; j++) btns[j].setAlpha(j == fi ? 1f : 0.4f);
                }
            });
            form.addView(b);
        }

        TextView timeLabel = new TextView(this);
        timeLabel.setText("2. 选时间");
        timeLabel.setTextColor(MdTheme.onSurfaceVariant(this));
        timeLabel.setTextSize(12);
        timeLabel.setPadding(0, dp(8), 0, 0);
        form.addView(timeLabel);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        final MdTextField hour = new MdTextField(this, "时 0-23", false);
        hour.setText("8");
        final MdTextField minute = new MdTextField(this, "分 0-59", false);
        minute.setText("0");
        timeRow.addView(hour, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        timeRow.addView(minute, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        form.addView(timeRow);

        final MdTextField dow = new MdTextField(this, "周几 1-7 (1=周一, 逗号分隔, 如 1,3,5; 留空=每天)", false);
        final MdTextField dom = new MdTextField(this, "每月几号 1-31 (如 1 或 15)", false);
        form.addView(dow);
        form.addView(dom);

        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("生成", new Runnable() {
            public void run() {
                try {
                    int h = Integer.parseInt(hour.getText().trim());
                    int m = Integer.parseInt(minute.getText().trim());
                    if (h < 0 || h > 23 || m < 0 || m > 59) {
                        MdSnackbar.show(root, "时间无效, 时 0-23 分 0-59");
                        return;
                    }
                    String cron;
                    switch (sel[0]) {
                        case 0: cron = "0 * * * *"; break;
                        case 1: cron = "0 */2 * * *"; break;
                        case 2: cron = m + " " + h + " * * *"; break;
                        case 3: {
                            String t = dow.getText().trim();
                            if (t.isEmpty()) { MdSnackbar.show(root, "请填周几, 如 1,3,5"); return; }
                            String cd = parseDow(t);
                            if (cd == null) { MdSnackbar.show(root, "周几无效, 填 1-7 逗号分隔"); return; }
                            cron = m + " " + h + " * * " + cd;
                            break;
                        }
                        default: {
                            String t = dom.getText().trim();
                            if (t.isEmpty()) t = "1";
                            int day = Integer.parseInt(t);
                            if (day < 1 || day > 31) { MdSnackbar.show(root, "日期无效 1-31"); return; }
                            cron = m + " " + h + " " + day + " * *";
                        }
                    }
                    if (!CronParser.isValid(cron)) { MdSnackbar.show(root, "生成的 cron 无效"); return; }
                    cronField.setText(cron);
                    d.dismiss();
                    MdSnackbar.show(root, "已生成: " + cron);
                } catch (NumberFormatException ex) {
                    MdSnackbar.show(root, "请填写数字");
                }
            }
        });
        d.show();
    }

    /** 周几 1-7(周一=1) → cron 0-6(周日=0), 支持逗号分隔与 a-b 范围 */
    private static String parseDow(String s) {
        StringBuilder sb = new StringBuilder();
        try {
            for (String p : s.split(",")) {
                p = p.trim();
                if (p.isEmpty()) continue;
                if (p.contains("-")) {
                    String[] r = p.split("-");
                    int a = Integer.parseInt(r[0].trim());
                    int b = Integer.parseInt(r[1].trim());
                    if (a < 1 || b > 7 || a > b) return null;
                    for (int i = a; i <= b; i++) {
                        int c = (i == 7) ? 0 : i;
                        if (sb.length() > 0) sb.append(",");
                        sb.append(c);
                    }
                } else {
                    int v = Integer.parseInt(p);
                    if (v < 1 || v > 7) return null;
                    int c = (v == 7) ? 0 : v;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(c);
                }
            }
        } catch (Exception e) { return null; }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void addAdvTask() {
        final MdDialog d = new MdDialog(this);
        d.title("添加定时任务");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "任务名称", false);
        final MdTextField cron = new MdTextField(this, "cron 表达式 (分 时 日 月 周)", false);
        cron.setText("0 8 * * *");
        final MdTextField script = new MdTextField(this, "脚本文件名 (scripts 目录)", false);
        form.addView(name);
        form.addView(cron);
        MdButton quickBtn = new MdButton(this, "快捷设置 (不懂 cron 用这个)", MdButton.TONAL);
        quickBtn.setIcon(Icons.make(this, Icons.GEAR, MdTheme.primary(this), 16));
        form.addView(quickBtn);
        quickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCronQuick(cron); }
        });
        TextView cronHint = new TextView(this);
        cronHint.setText("格式: 分 时 日 月 周   例: 0 8 * * * = 每天 8:00\n0 */2 * * * = 每 2 小时  0 9 * * 1 = 每周一 9:00");
        cronHint.setTextColor(MdTheme.onSurfaceVariant(this));
        cronHint.setTextSize(11);
        cronHint.setPadding(0, dp(2), 0, dp(8));
        form.addView(cronHint);
        form.addView(script);
        MdButton pickBtn = new MdButton(this, "从脚本库选择", MdButton.TONAL);
        pickBtn.setIcon(Icons.make(this, Icons.FOLDER, MdTheme.primary(this), 18));
        form.addView(pickBtn);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickScript(script); }
        });
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("添加", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                String c = cron.getText().trim();
                String s = script.getText().trim();
                if (n.isEmpty() || c.isEmpty() || s.isEmpty()) {
                    MdSnackbar.show(root, "请填写完整");
                    return;
                }
                if (!CronParser.isValid(c)) {
                    MdSnackbar.show(root, "cron 格式错误, 示例: 0 8 * * *");
                    return;
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", n);
                    o.put("cron", c);
                    o.put("script", s);
                    o.put("enabled", true);
                    advTasks.add(o);
                    saveAdvTasks();
                    d.dismiss();
                    renderTasks();
                    MdSnackbar.show(root, "已添加 (每分钟检查触发)");
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
            }
        });
        d.show();
    }

    private void editAdvTask(final int idx) {
        final JSONObject o = advTasks.get(idx);
        final MdDialog d = new MdDialog(this);
        d.title("编辑任务");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "任务名称", false);
        name.setText(o.optString("name"));
        final MdTextField cron = new MdTextField(this, "cron 表达式 (分 时 日 月 周)", false);
        cron.setText(o.optString("cron"));
        final MdTextField script = new MdTextField(this, "脚本文件名", false);
        script.setText(o.optString("script"));
        form.addView(name);
        form.addView(cron);
        MdButton quickBtn = new MdButton(this, "快捷设置 (不懂 cron 用这个)", MdButton.TONAL);
        quickBtn.setIcon(Icons.make(this, Icons.GEAR, MdTheme.primary(this), 16));
        form.addView(quickBtn);
        quickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCronQuick(cron); }
        });
        TextView cronHint = new TextView(this);
        cronHint.setText("格式: 分 时 日 月 周   例: 0 8 * * * = 每天 8:00\n0 */2 * * * = 每 2 小时  0 9 * * 1 = 每周一 9:00");
        cronHint.setTextColor(MdTheme.onSurfaceVariant(this));
        cronHint.setTextSize(11);
        cronHint.setPadding(0, dp(2), 0, dp(8));
        form.addView(cronHint);
        form.addView(script);
        MdButton pickBtn = new MdButton(this, "从脚本库选择", MdButton.TONAL);
        pickBtn.setIcon(Icons.make(this, Icons.FOLDER, MdTheme.primary(this), 18));
        form.addView(pickBtn);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickScript(script); }
        });
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                String c = cron.getText().trim();
                if (!CronParser.isValid(c)) {
                    MdSnackbar.show(root, "cron 格式错误");
                    return;
                }
                try {
                    o.put("name", name.getText().trim());
                    o.put("cron", c);
                    o.put("script", script.getText().trim());
                    saveAdvTasks();
                    d.dismiss();
                    renderTasks();
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
            }
        });
        d.show();
    }

    /** 立即执行高级任务: 用对应解释器跑脚本 */
    private void runAdvTask(int idx) {
        if (!RuntimeManager.isReady(this)) {
            MdSnackbar.show(root, "运行时未就绪, 请先完成解压");
            return;
        }
        final JSONObject o = advTasks.get(idx);
        final String scriptName = o.optString("script", "");
        final String content = ScriptStore.read(this, scriptName);
        if (content.isEmpty()) {
            MdSnackbar.show(root, "脚本不存在: " + scriptName);
            return;
        }
        final String type = ScriptStore.typeOf(scriptName);
        MdSnackbar.show(root, "开始执行: " + scriptName + " (" + type + ")");
        new Thread(new Runnable() {
            public void run() {
                String interp;
                String scriptPath = new java.io.File(ScriptStore.dir(AdvActivity.this), scriptName).getAbsolutePath();
                if ("js".equals(type)) {
                    runShell(RuntimeManager.buildCommand(AdvActivity.this,
                            RuntimeManager.nodeBin(AdvActivity.this) + " '" + scriptPath + "'"), scriptName);
                    return;
                }
                // 生成带环境变量的执行脚本
                StringBuilder sb = new StringBuilder();
                if ("py".equals(type)) {
                    sb.append(RuntimeManager.pythonBin(AdvActivity.this)).append(" ").append(scriptPath);
                } else {
                    sb.append("/system/bin/sh ").append(scriptPath);
                }
                runShell(RuntimeManager.buildCommand(AdvActivity.this, sb.toString()), scriptName);
            }
        }).start();
    }

    private void runShell(final String cmd, final String tag) {
        final StringBuilder out = LiveLog.start(tag);   // 改用 LiveLog, 实时记录
        try {
            // 工作目录设为 files, 保证脚本相对路径写入可用 (与基础模式一致)
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(new java.io.File(getFilesDir(), ""));
            final Process p = pb.start();
            ScriptRunner.attachProcess(tag, p);   // 关联进程 → 卡片转圈
            ScriptRunner.sweep();
            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                        // 注意: out 就是 LiveLog 内部的那个 sb (LiveLog.start 返回同一个对象),
                        // 只能二选一, 否则每行会追加两遍 → 日志重复!
                        String l; while ((l = r.readLine()) != null) { LiveLog.append(tag, l); }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            t1.start();
            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                        String l; while ((l = r.readLine()) != null) { LiveLog.append(tag, "! " + l); }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            });
            t2.start();
            int code;
            // 脚本超时: 默认 180s, 可在脚本库「编辑」里按脚本自定义
            long toSec = 180L;
            try { toSec = ScriptStore.getTimeout(AdvActivity.this, tag); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            if (toSec < 1) toSec = 180L;
            try {
                if (!p.waitFor(toSec, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy();
                    String timeoutMsg = "! 超时(" + toSec + "s), 已终止";
                    LiveLog.append(tag, timeoutMsg);   // out 与 LiveLog 同一对象, 只写一次
                    code = 124;
                } else {
                    code = p.exitValue();
                }
            } catch (InterruptedException ie) {
                p.destroy();
                code = 124;
            }
            t1.join(2000); t2.join(2000);
            ScriptRunner.markDone(tag);   // 运行结束 → 卡片取消转圈
            // 历史日志 TaskLog.append 会保存完整输出, 实时缓存直接清掉, 避免堆积
            LiveLog.purge(tag);
            final int fcode = code;
            ui.post(new Runnable() {
                public void run() {
                    String diag = "cmd: " + cmd;
                    if (diag.length() > 400) diag = diag.substring(0, 400) + "...";
                    TaskLog.append(AdvActivity.this, tag, diag + "\n退出码 " + fcode + "\n" + out.toString());
                    // ═══════ 运行时依赖自动修复 (2026-08 新功能) ═══════
                    // 背景: Python C 扩展 (如 _struct/_ssl/_sqlite3) 打包在
                    // nativeLibraryDir, 若 lib-dynload 缺失会报 ModuleNotFoundError。
                    // 检测到此类错误时, 弹窗让用户选择是否自动修复。
String outStr = out.toString();
                    if (fcode != 0 && (outStr.contains("ModuleNotFoundError")
                            || outStr.contains("No module named"))) {
                        final String ftag = tag;
                        final String fout = outStr;
                        // 提取缺失模块名 (跳过已知误报: _zlib/_itertools 等)
                        java.util.regex.Matcher mm = java.util.regex.Pattern.compile(
                                "No module named '([^']+)'").matcher(fout);
                        StringBuilder mods = new StringBuilder();
                        java.util.Set<String> seen = new java.util.HashSet<String>();
                        java.util.Set<String> falseP = new java.util.HashSet<String>();
                        while (mm.find()) {
                            String mod = mm.group(1);
                            if (RuntimeManager.isKnownFalsePositive(mod)) {
                                falseP.add(mod);
                                continue;
                            }
                            if (seen.add(mod)) mods.append("· ").append(mod).append("\n");
                        }
                        // 全部是误报 → 运行时健康, 不弹修复, 只提示
                        if (mods.length() == 0) {
                            String fpNote = falseP.isEmpty() ? "内置模块" : "(" + falseP.toString() + ")";
                            MdSnackbar.show(root, "运行时依赖正常 ✓ 报错的 " + fpNote + " 是解释器内置/不存在的模块, 无需修复");
                            return;
                        }
                        String modText = mods.toString();
                        MdDialog fixDlg = new MdDialog(AdvActivity.this);
                        fixDlg.title("检测到运行时依赖缺失");
                        // 识别哪些是需要 pip 下载的第三方包
                        final StringBuilder pipPkgs = new StringBuilder();
                        java.util.Set<String> pipSeen = new java.util.HashSet<String>();
                        for (String mm2 : new String[]{modText}) {
                            java.util.regex.Matcher mm2m = java.util.regex.Pattern.compile(
                                    "· ([^\\n]+)").matcher(mm2);
                            while (mm2m.find()) {
                                String mod = mm2m.group(1).trim();
                                if (RuntimeManager.isThirdPartyModule(mod) && pipSeen.add(mod)) {
                                    if (pipPkgs.length() > 0) pipPkgs.append(" ");
                                    pipPkgs.append(mod);
                                }
                            }
                        }
                        fixDlg.message("脚本「" + ftag + "」运行时报错:\n"
                                + modText
                                + "\n这通常是运行时依赖缺失导致。\n"
                                + (pipPkgs.length() > 0
                                    ? "检测到第三方包缺失: " + pipPkgs + "\n将使用内置 pip 从 PyPI 下载安装。"
                                    : "这通常是 C 扩展模块未就位, 将补齐内置运行时模块。")
                                + "\n是否继续？");
                        fixDlg.action("忽略", new Runnable() { public void run() { fixDlg.dismiss(); } });
                        fixDlg.actionPrimary("自动修复", new Runnable() {
                            public void run() {
                                final String pipArg = pipPkgs.toString();
                                // ── 复用同一对话框, 切换为进度视图 (避免 dismiss+show 竞争) ──
                                fixDlg.title(pipArg.isEmpty() ? "正在修复运行时..." : "正在下载安装依赖");
                                fixDlg.hideActions();
                                LinearLayout pl = new LinearLayout(AdvActivity.this);
                                pl.setOrientation(LinearLayout.VERTICAL);
                                ProgressBar pb = new ProgressBar(AdvActivity.this, null,
                                        android.R.attr.progressBarStyleHorizontal);
                                pb.setIndeterminate(true);
                                pl.addView(pb);
                                final TextView statusTv = new TextView(AdvActivity.this);
                                statusTv.setText("正在连接 PyPI...");
                                statusTv.setTextSize(12);
                                statusTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                statusTv.setPadding(0, dp(8), 0, 0);
                                pl.addView(statusTv);
                                final TextView logTv = new TextView(AdvActivity.this);
                                logTv.setTextSize(11);
                                logTv.setTypeface(Typeface.MONOSPACE);
                                logTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                logTv.setPadding(dp(6), dp(8), dp(6), 0);
                                ScrollView logSc = new ScrollView(AdvActivity.this);
                                logSc.addView(logTv);
                                android.widget.FrameLayout logWrap = new android.widget.FrameLayout(AdvActivity.this);
                                logWrap.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, dp(240)));
                                logWrap.addView(logSc);
                                pl.addView(logWrap);
                                fixDlg.replaceContent(pl);
                                new Thread(new Runnable() {
                                    public void run() {
                                        final String detail;
                                        if (!pipArg.isEmpty()) {
                                            detail = RuntimeManager.pipInstall(AdvActivity.this, pipArg,
                                                    new RuntimeManager.LineListener() {
                                                        public void onLine(final String line) {
                                                            runOnUiThread(new Runnable() {
                                                                public void run() {
                                                                    statusTv.setText(line);
                                                                    logTv.append(line + "\n");
                                                                    logSc.post(new Runnable() {
                                                                        public void run() { logSc.fullScroll(View.FOCUS_DOWN); }
                                                                    });
                                                                }
                                                            });
                                                        }
                                                        public void onDone(boolean success) {}
                                                    });
                                        } else {
                                            int copied = RuntimeManager.fixPythonExts(AdvActivity.this);
                                            detail = "已复制 " + copied + " 个本地扩展模块";
                                        }
                                        final String fdetail = detail;
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                fixDlg.dismiss();
                                                if (fdetail.contains("Successfully installed")
                                                        || fdetail.contains("already satisfied")
                                                        || (fdetail.startsWith("已复制") && !fdetail.contains("已复制 0"))) {
                                                    MdSnackbar.show(root, "依赖已就绪 ✓ 请重新运行「" + ftag + "」");
                                                } else {
                                                    MdSnackbar.show(root, fdetail.length() > 140 ? fdetail.substring(0, 140) : fdetail);
                                                }
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
                        fixDlg.show();
                    }
                    // ═══════ Node.js 依赖自动修复 ═══════
                    else if (fcode != 0 && outStr.contains("Error: Cannot find module")) {
                        final String ftag = tag;
                        final String fout = outStr;
                        java.util.regex.Matcher nm = java.util.regex.Pattern.compile(
                                "Cannot find module '([^']+)'").matcher(fout);
                        StringBuilder mods = new StringBuilder();
                        java.util.Set<String> seen = new java.util.HashSet<String>();
                        while (nm.find()) {
                            String mod = nm.group(1);
                            if (seen.add(mod)) mods.append("· ").append(mod).append("\n");
                        }
                        if (mods.length() == 0) return;
                        String modText = mods.toString();
                        MdDialog fixDlg = new MdDialog(AdvActivity.this);
                        fixDlg.title("检测到 Node.js 依赖缺失");
                        fixDlg.message("脚本「" + ftag + "」运行时报错:\n"
                                + modText
                                + "\n这是 Node.js 模块缺失导致。\n"
                                + "将使用 npm 从 npmmirror 镜像下载安装。\n是否继续？");
                        fixDlg.action("忽略", new Runnable() { public void run() { fixDlg.dismiss(); } });
                        fixDlg.actionPrimary("自动修复", new Runnable() {
                            public void run() {
                                final String pkgs = modText.replaceAll("· ", "").replace("\n", " ").trim();
                                fixDlg.title("正在下载 Node.js 依赖");
                                fixDlg.hideActions();
                                LinearLayout pl = new LinearLayout(AdvActivity.this);
                                pl.setOrientation(LinearLayout.VERTICAL);
                                ProgressBar pb = new ProgressBar(AdvActivity.this, null,
                                        android.R.attr.progressBarStyleHorizontal);
                                pb.setIndeterminate(true);
                                pl.addView(pb);
                                final TextView statusTv = new TextView(AdvActivity.this);
                                statusTv.setText("正在连接 npm 镜像...");
                                statusTv.setTextSize(12);
                                statusTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                statusTv.setPadding(0, dp(8), 0, 0);
                                pl.addView(statusTv);
                                final TextView logTv = new TextView(AdvActivity.this);
                                logTv.setTextSize(11);
                                logTv.setTypeface(Typeface.MONOSPACE);
                                logTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                logTv.setPadding(dp(6), dp(8), dp(6), 0);
                                ScrollView logSc = new ScrollView(AdvActivity.this);
                                logSc.addView(logTv);
                                android.widget.FrameLayout logWrap = new android.widget.FrameLayout(AdvActivity.this);
                                logWrap.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, dp(240)));
                                logWrap.addView(logSc);
                                pl.addView(logWrap);
                                fixDlg.replaceContent(pl);
                                new Thread(new Runnable() {
                                    public void run() {
                                        final String detail = RuntimeManager.npmInstall(AdvActivity.this, pkgs,
                                                new RuntimeManager.LineListener() {
                                                    public void onLine(final String line) {
                                                        runOnUiThread(new Runnable() {
                                                            public void run() {
                                                                statusTv.setText(line);
                                                                logTv.append(line + "\n");
                                                                logSc.post(new Runnable() {
                                                                    public void run() { logSc.fullScroll(View.FOCUS_DOWN); }
                                                                });
                                                            }
                                                        });
                                                    }
                                                    public void onDone(boolean success) {}
                                                });
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                fixDlg.dismiss();
                                                if (detail.contains("added ") || detail.contains("up to date")) {
                                                    MdSnackbar.show(root, "Node.js 依赖已就绪 ✓ 请重新运行「" + ftag + "」");
                                                } else {
                                                    MdSnackbar.show(root, detail.length() > 140 ? detail.substring(0, 140) : detail);
                                                }
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
                        fixDlg.show();
                    }
                    // 联动: 脚本执行结果同步到同名任务卡片状态
                    try {
                        List<Task> tasks = TaskStore.load(AdvActivity.this);
                        for (Task t : tasks) {
                            if (t.name != null && t.name.equals(tag)) {
                                t.lastOk = (fcode == 0);
                                t.lastRunAt = System.currentTimeMillis();
                                t.lastResult = (fcode == 0) ? "完成(退出码0)" : "退出码 " + fcode;
                                if (fcode == 0) t.streak = t.streak + 1; else t.streak = 0;
                                break;
                            }
                        }
                        TaskStore.save(AdvActivity.this, tasks);
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    // 刷新脚本卡片 (取消转圈)
                    try { if (currentTab == 1) renderScripts(); } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    MdSnackbar.show(root, tag + " 执行完成 (退出码 " + fcode + ")\n" + truncate(out.toString(), 200));
                    // 检测本次运行产出的文件, 提供一键导出到手机 Download (文件管理器可见)
                    if (fcode == 0) maybeOfferFileExport(tag);
                }
            });
        } catch (final Exception e) {
            ScriptRunner.markDone(tag);
            ui.post(new Runnable() {
                public void run() { MdSnackbar.show(root, tag + " 失败: " + e.getMessage()); }
            });
        }
    }

    
    /** Public bridge for panels */
    public void runShellCommand(final String cmd, final String tag) {
        runShell(cmd, tag);
    }
    public void refreshScripts() {
        if (currentTab == 1) renderScripts();
    }
    public void refreshTab(int newTab) {
        switchTab(newTab);
    }private String truncate(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }

    // ================= 文件导出到 Download =================

    /** 本次运行产出的文件特征 */
    private static class ProdFile {
        File file;
        String icon;
        String size;
        String parent;
        long mtime;
        boolean isImage;
        ProdFile(File f) {
            file = f;
            icon = fileIcon(f.getName());
            size = humSize(f.length());
            mtime = f.lastModified();
            File p = f.getParentFile();
            parent = (p == null || "files".equals(p.getName())) ? "脚本产物" : p.getName();
            String n = f.getName().toLowerCase();
            isImage = n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp")
                    || n.endsWith(".svg") || n.endsWith(".ico");
        }
    }

    /** 扫描 files/ 下本次运行(10分钟内)产出的文件, 静默自动导出 */
    private void maybeOfferFileExport(final String tag) {
        final java.util.List<ProdFile> files = findRecentFiles(
                getFilesDir(), 3, System.currentTimeMillis() - 10 * 60000L);
        if (files.isEmpty()) return;
        final String sub = safeDirName(tag);
        // 静默自动导出到 Download/<脚本名>/
        new Thread(new Runnable() {
            public void run() {
                final int n = exportToDownload(AdvActivity.this, files, sub);
                runOnUiThread(new Runnable() {
                    public void run() {
                        MdSnackbar.show(root, "产物已导出 " + n + " 个 → Download/" + sub
                                + "/\n(更多 → 我的产物 可管理)");
                    }
                });
            }
        }).start();
    }

    /** 导出确认弹窗 (产物页也可复用: onDone 非空时导出后回调刷新) */
    private void showExportDialog(final String title, final java.util.List<ProdFile> files,
                                  final String sub, final Runnable onDone) {
        long total = 0;
        for (ProdFile p : files) total += p.file.length();
        final MdDialog d = new MdDialog(this);
        d.title(title);
        // 自定义内容: 每行 [类型图标] 文件名 (大小)
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setText("共 " + files.size() + " 个文件 (合计 " + humSize(total) + "):");
        head.setTextColor(MdTheme.onSurface(this));
        head.setTextSize(13);
        box.addView(head);
        View spc = new View(this);
        spc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        box.addView(spc);
        int show = Math.min(files.size(), 8);
        for (int i = 0; i < show; i++) {
            final ProdFile p = files.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView ic = new TextView(this);
            ic.setText(p.icon);
            ic.setTypeface(IconFont.get(this));
            ic.setTextSize(15);
            ic.setTextColor(MdTheme.onSurfaceVariant(this));
            ic.setMinWidth(dp(24));
            ic.setGravity(Gravity.CENTER);
            row.addView(ic);
            TextView nm = new TextView(this);
            nm.setText(p.file.getName() + "  (" + p.size + ")");
            nm.setTextColor(MdTheme.onSurface(this));
            nm.setTextSize(13);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(row);
        }
        if (files.size() > show) {
            TextView more = new TextView(this);
            more.setText("…共 " + files.size() + " 个文件");
            more.setTextColor(MdTheme.onSurfaceVariant(this));
            more.setTextSize(12);
            box.addView(more);
        }
        TextView tip = new TextView(this);
        tip.setText("导出到手机 Download/" + sub + "/ 后, 可在文件管理器/图库中直接查看?");
        tip.setTextColor(MdTheme.onSurfaceVariant(this));
        tip.setTextSize(12);
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("导出到 Download", new Runnable() {
            public void run() {
                d.dismiss();
                final int n = exportToDownload(AdvActivity.this, files, sub);
                MdSnackbar.show(root, "已导出 " + n + "/" + files.size() + " 个文件 → Download/" + sub + "/");
                if (onDone != null) onDone.run();
            }
        });
        d.show();
    }

    private static String safeDirName(String tag) {
        String s = tag == null ? "脚本产物" : tag;
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "脚本产物" : s;
    }

    /** BFS 扫描目录(深度<=maxDepth), 找本次运行新增的可导出文件 */
    private java.util.List<ProdFile> findRecentFiles(File dir, int maxDepth, long since) {
        java.util.List<File> raw = new java.util.ArrayList<File>();
        java.util.List<File> queue = new java.util.ArrayList<File>();
        queue.add(dir);
        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            java.util.List<File> next = new java.util.ArrayList<File>();
            for (File f : queue) {
                if (!f.isDirectory()) continue;
                if (f.getName().equals("termux") || f.getName().equals("__pycache__")
                        || f.getName().equals("tmp") || f.getName().equals("scripts")
                        || f.getName().equals("ai_workspace")) continue;
                File[] kids = f.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isDirectory()) next.add(k);
                    else if (k.lastModified() >= since && isExportable(k)) raw.add(k);
                }
            }
            queue = next;
            depth++;
        }
        if (raw.size() > 200) raw = new java.util.ArrayList<File>(raw.subList(0, 200));
        java.util.List<ProdFile> res = new java.util.ArrayList<ProdFile>();
        for (File f : raw) res.add(new ProdFile(f));
        return res;
    }

    /** 可导出的判断: 非空、非运行时垃圾、大小合理 */
    private static boolean isExportable(File f) {
        if (f.length() == 0) return false;
        if (f.length() > 500L * 1024 * 1024) return false;   // 超大文件跳过
        String n = f.getName().toLowerCase();
        if (n.endsWith(".pyc") || n.endsWith(".pyo") || n.endsWith(".tmp")
                || n.endsWith(".part") || n.endsWith(".whl") || n.endsWith(".log")) return false;
        return true;
    }

    private static String humSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /** 文件类型矢量图标 */
    private static String fileIcon(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp")
                || n.endsWith(".svg") || n.endsWith(".ico")) return IconFont.IMAGE;
        if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv")
                || n.endsWith(".avi") || n.endsWith(".webm")) return IconFont.MOVIE;
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".flac") || n.endsWith(".ogg")) return IconFont.MUSIC;
        if (n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx")
                || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return IconFont.DOC;
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
                || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".bz2")) return IconFont.ARCHIVE;
        return IconFont.FOLDER;
    }

    /** MediaStore (API 29+) / 直接复制 (API 28-) 导出到公共 Download/<sub>/ */
    private int exportToDownload(java.util.List<ProdFile> files, String sub) {
        return exportToDownload(AdvActivity.this, files, sub);
    }

    /** 静态导出 (可后台线程调用, cron 自动导出用) */
    public static int exportToDownload(Context ctx, java.util.List<ProdFile> files, String sub) {
        int ok = 0;
        for (ProdFile p : files) {
            if (copyToDownload(ctx, p.file, sub)) ok++;
        }
        return ok;
    }

    /** 单文件导出 */
    public static boolean exportFileToDownload(Context ctx, File f, String sub) {
        return copyToDownload(ctx, f, sub);
    }

    private static boolean copyToDownload(Context ctx, File f, String sub) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(f.getName()));
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/" + sub);
                android.net.Uri uri = ctx.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                in.close();
                os.close();
                return true;
            } else {
                File outDir = new File(android.os.Environment
                        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), sub);
                outDir.mkdirs();
                java.nio.file.Files.copy(f.toPath(),
                        new File(outDir, f.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return false;
    }

    /** 扫描 files/ 全部可导出产物, 按修改时间倒序 (排除运行时与垃圾文件) */
    public static java.util.List<ProdFile> scanArtifacts(Context ctx, int maxCount) {
        java.util.List<File> raw = new java.util.ArrayList<File>();
        java.util.List<File> queue = new java.util.ArrayList<File>();
        queue.add(ctx.getFilesDir());
        int depth = 0;
        while (!queue.isEmpty() && depth < 6) {
            java.util.List<File> next = new java.util.ArrayList<File>();
            for (File f : queue) {
                if (!f.isDirectory()) continue;
                if (f.getName().equals("termux") || f.getName().equals("__pycache__")
                        || f.getName().equals("tmp") || f.getName().equals("scripts")
                        || f.getName().equals("ai_workspace")) continue;
                File[] kids = f.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isDirectory()) next.add(k);
                    else if (isExportable(k)) raw.add(k);
                }
            }
            queue = next;
            depth++;
        }
        java.util.Collections.sort(raw, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return (int) Math.max(-1, Math.min(1, (b.lastModified() - a.lastModified()) / 1000L));
            }
        });
        if (raw.size() > maxCount) raw = new java.util.ArrayList<File>(raw.subList(0, maxCount));
        java.util.List<ProdFile> res = new java.util.ArrayList<ProdFile>();
        for (File f : raw) res.add(new ProdFile(f));
        return res;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".tar")) return "application/x-tar";
        if (n.endsWith(".gz")) return "application/gzip";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".py")) return "text/x-python";
        if (n.endsWith(".sh")) return "application/x-sh";
        return "application/octet-stream";
    }

    // ================= 脚本 =================
    private void renderScripts() {
        contentWrap.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView sc = new ScrollView(this);
        sc.addView(page);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("脚本库 (scripts 目录)");
        hint.setTextColor(MdTheme.onSurfaceVariant(this));
        hint.setTextSize(13);
        LinearLayout hintRow = new LinearLayout(this);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // 导出全部 (打包 ZIP 分享/迁移)
        TextView expAll = new TextView(this);
        expAll.setText("导出全部");
        expAll.setTextColor(MdTheme.primary(this));
        expAll.setTextSize(13);
        expAll.setPadding(dp(8), dp(2), dp(6), dp(2));
        expAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportAllScriptsZip(); }
        });
        hintRow.addView(expAll);
        TextView checkUpd = new TextView(this);
        checkUpd.setText("检查更新");
        checkUpd.setTextColor(MdTheme.primary(this));
        checkUpd.setTextSize(13);
        checkUpd.setPadding(dp(10), dp(2), dp(2), dp(2));
        checkUpd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkMarketUpdates(); }
        });
        hintRow.addView(checkUpd);
        page.addView(hintRow);

        // 脚本市场入口横幅 (主入口)
        LinearLayout mktBanner = new LinearLayout(this);
        mktBanner.setOrientation(LinearLayout.HORIZONTAL);
        mktBanner.setGravity(Gravity.CENTER_VERTICAL);
        mktBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        mktBanner.setBackground(bannerBg(0x332A6FDB, 0x14A5C8FF, dp(16)));
        LinearLayout mktLeft = new LinearLayout(this);
        mktLeft.setOrientation(LinearLayout.HORIZONTAL);
        mktLeft.setGravity(Gravity.CENTER);
        mktLeft.setPadding(dp(8), dp(8), dp(8), dp(8));
        mktLeft.setBackground(bannerBg(MdTheme.isDark(this) ? 0x663A7BDB : 0xFFFFFFFF,
                MdTheme.isDark(this) ? 0x663A7BDB : 0xFFFFFFFF, dp(12)));
        TextView mktIc = new TextView(this);
        mktIc.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.DOWNLOAD, MdTheme.primary(this), 22), null, null, null);
        mktLeft.addView(mktIc);
        mktBanner.addView(mktLeft);
        LinearLayout mktTxt = new LinearLayout(this);
        mktTxt.setOrientation(LinearLayout.VERTICAL);
        mktTxt.setPadding(dp(12), 0, dp(8), 0);
        TextView mktT = new TextView(this);
        mktT.setText("脚本市场");
        mktT.setTextColor(MdTheme.onSurface(this));
        mktT.setTextSize(15);
        mktT.setTypeface(Typeface.DEFAULT_BOLD);
        mktTxt.addView(mktT);
        TextView mktS = new TextView(this);
        mktS.setText("安装后端发布的脚本 · 版本对比更新");
        mktS.setTextColor(MdTheme.onSurfaceVariant(this));
        mktS.setTextSize(12);
        mktTxt.addView(mktS);
        mktBanner.addView(mktTxt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView mktArrow = new TextView(this);
        mktArrow.setText("›");
        mktArrow.setTextColor(MdTheme.onSurfaceVariant(this));
        mktArrow.setTextSize(22);
        mktBanner.addView(mktArrow);
        mktBanner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMarketPage(); }
        });
        page.addView(mktBanner);
        page.addView(new Spacer(this, 0, dp(4)));

        // 文档入口
        TextView docLink = new TextView(this);
        docLink.setText("脚本开发文档 (基础/高级模式)");
        docLink.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.DOC, MdTheme.primary(this), 14), null, null, null);
        docLink.setCompoundDrawablePadding(dp(4));
        docLink.setTextColor(MdTheme.primary(this));
        docLink.setTextSize(13);
        docLink.setPadding(0, dp(6), 0, dp(8));
        docLink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openMore(MORE_DOC); }
        });
        page.addView(docLink);

        // 脚本搜索框（优化样式）
        final LinearLayout scriptWrap = new LinearLayout(this);
        scriptWrap.setOrientation(LinearLayout.HORIZONTAL);
        scriptWrap.setGravity(Gravity.CENTER_VERTICAL);
        scriptWrap.setPadding(dp(10), dp(6), dp(10), dp(6));
        android.graphics.drawable.GradientDrawable sswbg = new android.graphics.drawable.GradientDrawable();
        sswbg.setColor(MdTheme.surfaceContainerHigh(this));
        sswbg.setCornerRadius(dp(22));
        sswbg.setStroke(dp(1), MdTheme.outlineVariant(this));
        scriptWrap.setBackground(sswbg);
        LinearLayout.LayoutParams sswlp = new LinearLayout.LayoutParams(-1, -2);
        sswlp.bottomMargin = dp(8);
        scriptWrap.setLayoutParams(sswlp);
        ImageView ssIcon = new ImageView(this);
        ssIcon.setImageDrawable(Icons.make(this, Icons.DOC, MdTheme.onSurfaceVariant(this), 16));
        ssIcon.setPadding(dp(8), 0, dp(2), 0);
        scriptWrap.addView(ssIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        final android.widget.EditText scriptBox = new android.widget.EditText(this);
        scriptBox.setHint("搜索脚本 (支持拼音, 如 dds=度大师)");
        scriptBox.setText(scriptSearchQuery);
        scriptBox.setTextSize(13);
        scriptBox.setTextColor(MdTheme.onSurface(this));
        scriptBox.setHintTextColor(MdTheme.onSurfaceVariant(this));
        scriptBox.setSingleLine(true);
        scriptBox.setPadding(dp(6), 0, dp(6), 0);
        scriptBox.setBackgroundColor(Color.TRANSPARENT);
        scriptBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        scriptWrap.addView(scriptBox, new LinearLayout.LayoutParams(0, dp(40), 1f));
        final ImageView ssClear = new ImageView(this);
        ssClear.setImageDrawable(Icons.make(this, Icons.CROSS, MdTheme.onSurfaceVariant(this), 14));
        ssClear.setPadding(dp(6), dp(6), dp(8), dp(6));
        ssClear.setVisibility(scriptSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        ssClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scriptSearchQuery = "";
                scriptBox.setText("");
            }
        });
        scriptWrap.addView(ssClear);
        scriptBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (scriptSearchLock) return;
                scriptSearchQuery = s.toString().trim();
                ssClear.setVisibility(scriptSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                scriptSearchLock = true;
                renderScripts();
                scriptSearchLock = false;
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        page.addView(scriptWrap);
        List<ScriptStore.Script> list = ScriptStore.list(this);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无脚本\n点右下角 ＋ 新建");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            page.addView(empty);
        }
        for (final ScriptStore.Script s : list) {
            if (!scriptSearchQuery.isEmpty()) {
                String hay = s.name + " " + s.type;
                if (!PinyinUtil.matches(hay, scriptSearchQuery)) continue;
            }
            final boolean running = ScriptRunner.isRunning(s.name);
            MdCard card = new MdCard(this, MdCard.OUTLINED, true);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(8);
            card.setLayoutParams(cp);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            card.addView(row);
            // 标题行: 脚本名 + [运行中转圈]
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(s.name + "  [" + s.type + "]");
            name.setTextColor(MdTheme.onSurface(this));
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            nameRow.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (running) {
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
                try {
                    android.graphics.drawable.Drawable id = pb.getIndeterminateDrawable();
                    if (id != null) id.setColorFilter(
                            MdTheme.primary(this), android.graphics.PorterDuff.Mode.SRC_IN);
                } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(18), dp(18));
                pbp.leftMargin = dp(6);
                nameRow.addView(pb, pbp);
                TextView runTv = new TextView(this);
                runTv.setText("运行中");
                runTv.setTextColor(MdTheme.primary(this));
                runTv.setTextSize(11);
                runTv.setPadding(0, 0, 0, dp(2));
                nameRow.addView(runTv);
            }
            row.addView(nameRow);
            TextView meta = new TextView(this);
            String sched = schedDesc(this, s.name);
            meta.setText(java.text.SimpleDateFormat.getDateTimeInstance().format(new java.util.Date(s.mtime))
                    + (sched.isEmpty() ? "" : "  ·  " + sched));
            meta.setTextColor(MdTheme.onSurfaceVariant(this));
            meta.setTextSize(11);
            meta.setPadding(0, dp(4), 0, 0);
            row.addView(meta);
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(6), 0, 0);
            row.addView(ops);
            ops.addView(smallBtn("定时", false, new Runnable() {
                public void run() { schedDialog(s.name); }
            }));
            ops.addView(smallBtn("编辑", false, new Runnable() {
                public void run() { editScript(s.name); }
            }));
            ops.addView(smallBtn("运行", false, new Runnable() {
                public void run() { runScriptFile(s.name); }
            }));
            ops.addView(smallBtn("导出", false, new Runnable() {
                public void run() { exportScriptFile(s.name); }
            }));
            ops.addView(smallBtn("删除", true, new Runnable() {
                public void run() {
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title("删除脚本");
                    d.message("确定删除 " + s.name + " ?");
                    d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                    d.actionPrimary("删除", new Runnable() {
                        public void run() {
                            ScriptStore.delete(AdvActivity.this, s.name);
                            d.dismiss();
                            renderScripts();
                        }
                    });
                    d.show();
                }
            }));
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showScriptPreview(s.name); }
            });
            page.addView(card);
        }
        // 底部留白 (给 FAB 让位)
        TextView pad = new TextView(this);
        pad.setHeight(dp(72));
        page.addView(pad);

        // 运行中检测: 若有脚本正在运行, 定时重绘脚本卡片以实时更新转圈状态
        if (!ScriptRunner.runningNames().isEmpty() && currentTab == 1) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (currentTab != 1) return;              // 已离开脚本页, 停止
                    if (ScriptRunner.runningNames().isEmpty()) return;  // 全部结束, 停止
                    renderScripts();                           // 重绘 (内部会再调度下一次)
                }
            }, 1500);
        }
    }

        /** 带图标的胶囊按钮 (脚本页专用) */
    private TextView iconSmallBtn(String text, boolean danger, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(danger ? MdTheme.error(this) : MdTheme.primary(this));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(6), dp(6), dp(6), dp(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        int fill = danger ? (MdTheme.isDark(this) ? 0x33CF6679 : 0x14CF6679)
                           : (MdTheme.isDark(this) ? 0x33D0BCFF : 0x14D0BCFF);
        bg.setColor(fill);
        bg.setCornerRadius(dp(14));
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(MdTheme.primary(this) & 0x2AFFFFFF),
                bg, null);
        v.setBackground(ripple);
        v.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2);
        lp.weight = 1;
        lp.rightMargin = dp(4);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return v;
    }

    /** 从文件系统导入文件为脚本 (SAF 文件选择器) */
    private void importScriptFromUri(Uri uri) {
        try {
            String displayName = null;
            try {
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) displayName = c.getString(idx);
                    c.close();
                }
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            if (displayName == null || displayName.isEmpty()) displayName = "imported.sh";
            StringBuilder sb = new StringBuilder();
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) { MdSnackbar.show(root, "无法读取所选文件"); return; }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            // 重名时自动加序号
            String finalName = displayName;
            int seq = 1;
            while (ScriptStore.exists(this, finalName)) {
                int dot = displayName.lastIndexOf('.');
                String base = dot > 0 ? displayName.substring(0, dot) : displayName;
                String ext = dot > 0 ? displayName.substring(dot) : "";
                finalName = base + "(" + seq + ")" + ext;
                seq++;
            }
            ScriptStore.write(this, finalName, sb.toString());
            MdSnackbar.show(root, "已导入脚本: " + finalName);
            renderScripts();
        } catch (Exception e) {
            MdSnackbar.show(root, "导入失败: " + e.getMessage());
        }
    }

    /** 导出脚本文件 (分享为文件) */
    private void exportScriptFile(String name) {
        try {
            android.net.Uri uri = FileShareProvider.uriFor(name);
            Intent i = new Intent(Intent.ACTION_SEND);
i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "导出 " + name));
        } catch (Exception e) {
            MdSnackbar.show(root, "导出失败: " + e.toString());
        }
    }

    /** 导出全部脚本为 ZIP (含配置), 分享/换机迁移用 */
    private void exportAllScriptsZip() {
        try {
            java.util.List<ScriptStore.Script> list = ScriptStore.list(this);
            if (list.isEmpty()) { MdSnackbar.show(root, "没有可导出的脚本"); return; }
            java.io.File src = ScriptStore.dir(this);
            java.io.File zipF = new java.io.File(getCacheDir(), "taskpro_scripts_" + System.currentTimeMillis() + ".zip");
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipF));
            java.io.File[] files = src.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isFile() && !ScriptStore.isMetaFile(f.getName())
                            && !f.getName().endsWith(".conf.json")) {
                        // 脚本文件
                        putZipEntry(zos, f, f.getName());
                    }
                }
                // 配置 (conf.json 一起带上, 含变量/超时)
                for (java.io.File f : files) {
                    if (f.isFile() && f.getName().endsWith(".conf.json")) {
                        putZipEntry(zos, f, f.getName());
                    }
                }
            }
            zos.close();
            android.net.Uri uri = FileShareProvider.cacheUriFor(zipF.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_TEXT, "TaskPro 脚本备份 (" + list.size() + " 个脚本)");
            startActivity(Intent.createChooser(i, "导出全部脚本"));
        } catch (Exception e) {
            MdSnackbar.show(root, "导出失败: " + e.toString());
        }
    }

    private void putZipEntry(java.util.zip.ZipOutputStream zos, java.io.File f, String entryName) {
        try {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536];
            int r;
            while ((r = fis.read(buf)) > 0) zos.write(buf, 0, r);
            fis.close();
            zos.closeEntry();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 从脚本库选择脚本填入输入框 */
    private void pickScript(final MdTextField scriptField) {
        final List<ScriptStore.Script> list = ScriptStore.list(this);
        final MdDialog d = new MdDialog(this);
        d.title("从脚本库选择");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 顶部: 在线脚本市场入口 (脚本库为空也能进市场)
        LinearLayout mktRow = new LinearLayout(this);
        mktRow.setOrientation(LinearLayout.HORIZONTAL);
        mktRow.setGravity(Gravity.CENTER_VERTICAL);
        mktRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        mktRow.setBackground(bannerBg(0x332A6FDB, 0x14A5C8FF, dp(12)));
        TextView mktIc = new TextView(this);
        mktIc.setCompoundDrawablesWithIntrinsicBounds(
                Icons.make(this, Icons.DOWNLOAD, MdTheme.primary(this), 16), null, null, null);
        mktRow.addView(mktIc);
        TextView mktTx = new TextView(this);
        mktTx.setText("在线脚本市场 (安装后端发布的脚本)");
        mktTx.setTextColor(MdTheme.primary(this));
        mktTx.setTextSize(14);
        mktTx.setTypeface(Typeface.DEFAULT_BOLD);
        mktTx.setPadding(dp(8), 0, 0, 0);
        mktRow.addView(mktTx);
        mktRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); showMarketPage(); }
        });
        box.addView(mktRow);
        box.addView(new Spacer(this, 0, dp(6)));

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("脚本库为空 — 可从上方市场安装, 或新建脚本");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            box.addView(empty);
        }
        for (ScriptStore.Script s : list) {
            TextView tv = new TextView(this);
            tv.setText(s.name);
            tv.setCompoundDrawablesWithIntrinsicBounds(
                    Icons.make(this, Icons.FILE, MdTheme.primary(this), 14), null, null, null);
            tv.setCompoundDrawablePadding(dp(6));
            tv.setTextColor(MdTheme.primary(this));
            tv.setTextSize(14);
            tv.setPadding(dp(8), dp(10), dp(8), dp(10));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    scriptField.setText(s.name);
                    d.dismiss();
                }
            });
            box.addView(tv);
        }
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    /** 脚本市场全屏页: 覆盖内容区, 卡片式列表 */
    private void showMarketPage() {
        if (marketVisible) return;
        marketVisible = true;
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(MdTheme.surface(this));

        // 市场顶栏: 返回 + 标题 + 搜索 + 刷新 + 上传
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MdTheme.surfaceContainer(this));
        bar.setPadding(dp(4), dp(10), dp(4), dp(10));
        // 底部细线
        View barLine = new View(this);
        barLine.setBackgroundColor(MdTheme.isDark(this) ? 0xFF322F35 : 0xFFE0DAE8);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MdTheme.onSurface(this));
        back.setTextSize(28);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(14), dp(2), dp(14), dp(2));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeMarket(); }
        });
        bar.addView(back);
        // 标题+搜索框
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(0, dp(2), dp(8), dp(2));
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView title = new TextView(this);
        title.setText("脚本市场");
        title.setTextColor(MdTheme.onSurface(this));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        final EditText searchInput = new EditText(this);
        marketSearch = searchInput;
        searchInput.setHint("搜索脚本名称…");
        searchInput.setTextSize(12);
        searchInput.setPadding(dp(8), dp(3), dp(8), dp(3));
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
        sg.setCornerRadius(dp(8));
        searchInput.setBackground(sg);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 本地过滤已有数据, 不重新请求后端
                if (cachedMarketArr != null) renderMarketList(cachedMarketArr);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        titleBox.addView(searchInput);
        bar.addView(titleBox);
        // 刷新按钮
        TextView refresh = new TextView(this);
        refresh.setText("⟳");
        refresh.setTextColor(MdTheme.primary(this));
        refresh.setTextSize(18);
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(dp(12), dp(4), dp(8), dp(4));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { reloadMarket(); }
        });
        bar.addView(refresh);
        // 我的提交按钮 (人形图标)
        TextView mine = new TextView(this);
        mine.setText(IconFont.PERSON);
        mine.setTypeface(IconFont.get(this));
        mine.setTextSize(16);
        mine.setTextColor(MdTheme.onSurfaceVariant(this));
        mine.setGravity(Gravity.CENTER);
        mine.setPadding(dp(8), dp(4), dp(6), dp(4));
        mine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMySubmissions(); }
        });
        bar.addView(mine);
        // 上传按钮
        TextView upload = new TextView(this);
        upload.setText(IconFont.UPLOAD);
        upload.setTypeface(IconFont.get(this));
        upload.setTextSize(16);
        upload.setTextColor(MdTheme.primary(this));
        upload.setGravity(Gravity.CENTER);
        upload.setPadding(dp(8), dp(4), dp(12), dp(4));
        upload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showUploadDialog(); }
        });
        bar.addView(upload);
        page.addView(bar);
        page.addView(barLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // 列表区
        marketList = new LinearLayout(this);
        marketList.setOrientation(LinearLayout.VERTICAL);
        marketList.setPadding(dp(16), dp(12), dp(16), dp(16));
        ScrollView sc = new ScrollView(this);
        sc.addView(marketList);
        page.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部状态条
        marketStatus = new TextView(this);
        marketStatus.setText("加载中…");
        marketStatus.setTextColor(MdTheme.onSurfaceVariant(this));
        marketStatus.setTextSize(11);
        marketStatus.setGravity(Gravity.CENTER);
        marketStatus.setPadding(0, dp(8), 0, dp(8));
        marketStatus.setBackgroundColor(MdTheme.surfaceContainer(this));
        page.addView(marketStatus);

        marketPage = page;
        contentFrame.addView(marketPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        contentFrame.bringChildToFront(marketPage);
        reloadMarket();
    }

    private void closeMarket() {
        marketVisible = false;
        if (marketPage != null) {
            contentFrame.removeView(marketPage);
            marketPage = null;
        }
    }

    private void reloadMarket() {
        if (marketList == null) return;
        marketList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(MdTheme.onSurfaceVariant(this));
        loading.setTextSize(13);
        loading.setPadding(dp(8), dp(24), dp(8), dp(24));
        marketList.addView(loading);
        marketStatus.setText("正在加载 GitHub 脚本库…");
        new Thread(new Runnable() {
            public void run() {
                final JSONArray arr = Backend.fetchScripts(AdvActivity.this);
                cachedMarketArr = arr;  // 缓存供搜索过滤
                runOnUiThread(new Runnable() {
                    public void run() { renderMarketList(arr); }
                });
            }
        }).start();
    }

    /** 上传者设备标识: 首次生成并持久化, 用于查询提交审核状态 */
    private String uploadUid() {
        SharedPreferences sp = getSharedPreferences("upload", MODE_PRIVATE);
        String uid = sp.getString("uid", "");
        if (uid.isEmpty()) {
            uid = "u" + Long.toHexString(System.currentTimeMillis())
                    + Long.toHexString((long) (Math.random() * 0xFFFFFF));
            sp.edit().putString("uid", uid).apply();
        }
        return uid;
    }

    /** 查看我的提交: 状态 + 驳回理由 */
    private void showMySubmissions() {
        final MdDialog d = new MdDialog(this);
        d.title("我的提交");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(MdTheme.onSurfaceVariant(this));
        loading.setTextSize(13);
        loading.setPadding(dp(8), dp(12), dp(8), dp(12));
        box.addView(loading);
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
        final String uid = uploadUid();
        new Thread(new Runnable() {
            public void run() {
                final JSONArray arr = Backend.myScripts(AdvActivity.this, uid);
                runOnUiThread(new Runnable() {
                    public void run() {
                        box.removeAllViews();
                        if (arr == null) {
                            TextView tv = new TextView(AdvActivity.this);
                            tv.setText("查询失败, 请检查网络后重试");
                            tv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                            tv.setTextSize(13);
                            tv.setPadding(dp(8), dp(12), dp(8), dp(12));
                            box.addView(tv);
                            return;
                        }
                        if (arr.length() == 0) {
                            TextView tv = new TextView(AdvActivity.this);
                            tv.setText("暂无提交记录\n(上传脚本后在此查看审核状态)");
                            tv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                            tv.setTextSize(13);
                            tv.setPadding(dp(8), dp(16), dp(8), dp(16));
                            box.addView(tv);
                            return;
                        }
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            String st = s.optString("status", "pending");
                            String stTxt;
                            int stColor;
                            if ("published".equals(st)) {
                                stTxt = "已上架"; stColor = 0xFF2E7D32;
                            } else if ("rejected".equals(st)) {
                                stTxt = "已驳回"; stColor = 0xFFC62828;
                            } else {
                                stTxt = "审核中"; stColor = 0xFFF57F17;
                            }
                            LinearLayout row = new LinearLayout(AdvActivity.this);
                            row.setOrientation(LinearLayout.VERTICAL);
                            row.setPadding(dp(4), dp(8), dp(4), dp(8));
                            LinearLayout head = new LinearLayout(AdvActivity.this);
                            head.setOrientation(LinearLayout.HORIZONTAL);
                            head.setGravity(Gravity.CENTER_VERTICAL);
                            TextView nm = new TextView(AdvActivity.this);
                            nm.setText(s.optString("name", "") + "  v" + s.optString("ver", "")
                                    + "  " + s.optString("time", ""));
                            nm.setTextColor(MdTheme.onSurface(AdvActivity.this));
                            nm.setTextSize(14);
                            head.addView(nm, new LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                            TextView badge = new TextView(AdvActivity.this);
                            badge.setText(stTxt);
                            badge.setTextColor(stColor);
                            badge.setTextSize(12);
                            badge.setTypeface(Typeface.DEFAULT_BOLD);
                            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
                            android.graphics.drawable.GradientDrawable bg =
                                    new android.graphics.drawable.GradientDrawable();
                            bg.setColor((stColor & 0x00FFFFFF) | 0x1F000000);
                            bg.setCornerRadius(dp(10));
                            badge.setBackground(bg);
                            head.addView(badge);
                            row.addView(head);
                            if ("rejected".equals(st) && !s.optString("reason", "").isEmpty()) {
                                TextView rs = new TextView(AdvActivity.this);
                                rs.setText("驳回理由: " + s.optString("reason", ""));
                                rs.setTextColor(0xFFC62828);
                                rs.setTextSize(12);
                                rs.setPadding(dp(2), dp(4), dp(2), 0);
                                row.addView(rs);
                            }
                            box.addView(row);
                            if (i < arr.length() - 1) {
                                View div = new View(AdvActivity.this);
                                div.setBackgroundColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                div.setAlpha(0.15f);
                                box.addView(div, new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /** 上传脚本到市场 (提交后待管理员审核) */
    private void showUploadDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("上传脚本到市场");
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), 0, dp(2), 0);

        // 温馨提示卡片 (醒目)
        LinearLayout warn = new LinearLayout(this);
        warn.setOrientation(LinearLayout.VERTICAL);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        warn.setBackground(bannerBg(0x33F4511E, 0x14F4511E, dp(12)));
        TextView wt = new TextView(this);
        wt.setText("上传须知 (必读)");
        wt.setTextColor(MdTheme.isDark(this) ? 0xFFFFB74D : 0xFFB26A00);
        wt.setTextSize(14);
        wt.setTypeface(Typeface.DEFAULT_BOLD);
        warn.addView(wt);
        TextView wb = new TextView(this);
        wb.setText("1. 请删除脚本中账号、密码、Token 等个人信息\n"
                + "2. 敏感信息改为环境变量: process.env.XXX / os.environ[\"XXX\"]\n"
                + "3. 在文件头注释声明变量:  // 变量: XXX=说明, YYY=说明2\n"
                + "4. 提交后经作者审核, 通过后自动上架到 GitHub 脚本库");
        wb.setTextColor(MdTheme.onSurfaceVariant(this));
        wb.setTextSize(12);
        wb.setPadding(0, dp(4), 0, 0);
        warn.addView(wb);
        box.addView(warn);

        final android.widget.EditText author = new android.widget.EditText(this);
        author.setHint("昵称 (可选, 默认匿名)");
        author.setTextSize(14);
        box.addView(fieldPad(author));
        upAuthor = author;
        final android.widget.EditText nameEt = new android.widget.EditText(this);
        nameEt.setHint("文件名, 如 qiandao.py (不含路径)");
        nameEt.setTextSize(14);
        box.addView(fieldPad(nameEt));
        upName = nameEt;
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.EditText typeEt = new android.widget.EditText(this);
        typeEt.setHint("类型 py/js/sh");
        typeEt.setTextSize(14);
        row1.addView(fieldPad(typeEt), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        upType = typeEt;
        final android.widget.EditText verEt = new android.widget.EditText(this);
        verEt.setHint("版本 1.0");
        verEt.setTextSize(14);
        row1.addView(fieldPad(verEt), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        upVer = verEt;
        box.addView(row1);
        final android.widget.EditText noteEt = new android.widget.EditText(this);
        noteEt.setHint("说明 (一句话描述用途)");
        noteEt.setTextSize(14);
        box.addView(fieldPad(noteEt));
        upNote = noteEt;
        // 内容区: 文件选择按钮 + 文本框
        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pickIcon = new TextView(this);
        pickIcon.setText(IconFont.UPLOAD);
        pickIcon.setTypeface(IconFont.get(this));
        pickIcon.setTextSize(16);
        pickIcon.setTextColor(MdTheme.primary(this));
        pickIcon.setPadding(dp(4), dp(8), dp(2), dp(8));
        pickRow.addView(pickIcon);
        TextView pickBtn = new TextView(this);
        pickBtn.setText("从文件选择");
        pickBtn.setTextColor(MdTheme.primary(this));
        pickBtn.setTextSize(14);
        pickBtn.setTypeface(Typeface.DEFAULT_BOLD);
        pickBtn.setPadding(dp(4), dp(8), dp(12), dp(8));
        pickBtn.setBackground(bannerBg(0x332A6FDB, 0x14A5C8FF, dp(10)));
        pickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "选择脚本文件"), REQ_UPLOAD_FILE);
                } catch (Exception e) {
                    MdSnackbar.show(root, "无法打开文件选择器");
                }
            }
        });
        pickRow.addView(pickBtn);
        TextView pickTip = new TextView(this);
        pickTip.setText("或直接粘贴内容到下方");
        pickTip.setTextColor(MdTheme.onSurfaceVariant(this));
        pickTip.setTextSize(12);
        pickTip.setPadding(dp(8), 0, 0, 0);
        pickRow.addView(pickTip);
        box.addView(pickRow);
        final android.widget.EditText contentEt = new android.widget.EditText(this);
        contentEt.setHint("脚本内容");
        contentEt.setTextSize(13);
        contentEt.setMinLines(8);
        contentEt.setGravity(Gravity.TOP | Gravity.START);
        box.addView(fieldPad(contentEt));
        upContent = contentEt;
        sc.addView(box);
        d.content(sc);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("提交审核", new Runnable() {
            public void run() {
                String name = nameEt.getText().toString().trim();
                String type = typeEt.getText().toString().trim().toLowerCase();
                String content = contentEt.getText().toString();
                if (name.isEmpty() || content.isEmpty()) {
                    MdSnackbar.show(root, "请填写文件名和脚本内容");
                    return;
                }
                if (!type.equals("py") && !type.equals("js") && !type.equals("sh")) {
                    MdSnackbar.show(root, "类型仅支持 py / js / sh");
                    return;
                }
                if (content.length() > 200000) {
                    MdSnackbar.show(root, "脚本内容过大 (限 200KB)");
                    return;
                }
                final String fName = name, fType = type, fVer = verEt.getText().toString().trim(),
                        fNote = noteEt.getText().toString().trim(),
                        fContent = content, fAuthor = author.getText().toString().trim();
                MdSnackbar.show(root, "正在提交…");
                new Thread(new Runnable() {
                    public void run() {
                        final String msg = Backend.submitScript(AdvActivity.this,
                                fName, fType, fVer, fNote, fContent, fAuthor, uploadUid());
                        runOnUiThread(new Runnable() {
                            public void run() {
                                MdSnackbar.show(root, msg);
                                if (msg.startsWith("已提交")) d.dismiss();
                            }
                        });
                    }
                }).start();
            }
        });
        d.show();
    }

    /** 输入框统一样式包装 */
    private android.widget.EditText fieldPad(android.widget.EditText et) {
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
        g.setCornerRadius(dp(10));
        et.setBackground(g);
        return et;
    }

    /** 检查市场脚本更新: 对比已装版本, 弹窗列出可更新项 */
    private void checkMarketUpdates() {
        final MdDialog d = new MdDialog(this);
        d.title("检查更新");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView loading = new TextView(this);
        loading.setText("正在对比市场版本…");
        loading.setTextColor(MdTheme.onSurfaceVariant(this));
        loading.setTextSize(13);
        loading.setPadding(dp(8), dp(12), dp(8), dp(12));
        box.addView(loading);
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final JSONArray arr = Backend.fetchScripts(AdvActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        box.removeAllViews();
                        if (arr == null || arr.length() == 0) {
                            TextView tv = new TextView(AdvActivity.this);
                            tv.setText("GitHub 脚本库暂无脚本");
                            tv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                            tv.setTextSize(13);
                            tv.setPadding(dp(8), dp(12), dp(8), dp(12));
                            box.addView(tv);
                            return;
                        }
                        int updatable = 0;
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            final String name = s.optString("name", "");
                            final String mver = s.optString("ver", "");
                            final String lver = ScriptStore.verOf(AdvActivity.this, name);
                            final boolean installed = ScriptStore.exists(AdvActivity.this, name);
                            boolean hasNew = installed && !mver.isEmpty() && !mver.equals(lver);
                            if (hasNew) updatable++;
                            LinearLayout row = new LinearLayout(AdvActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dp(4), dp(6), dp(4), dp(6));
                            TextView info = new TextView(AdvActivity.this);
                            info.setTextColor(MdTheme.onSurface(AdvActivity.this));
                            info.setTextSize(14);
                            if (hasNew) {
                                info.setText(name + "  v" + lver + " → v" + mver);
                                final MdButton btn = new MdButton(AdvActivity.this, "更新", MdButton.FILLED);
                                btn.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        // 动态获取脚本内容再更新
                                        btn.setEnabled(false);
                                        btn.setText("获取中…");
                                        new Thread(new Runnable() {
                                            public void run() {
                                                final String content = Backend
                                                        .fetchScriptContent(AdvActivity.this, name);
                                                runOnUiThread(new Runnable() {
                                                    public void run() {
                                                        if (content == null) {
                                                            btn.setText("重试");
                                                            btn.setEnabled(true);
                                                            return;
                                                        }
                                                        installMarketScript(name, content, mver, "已更新", new Runnable() {
                                                            public void run() {
                                                                reloadMarket();
                                                                switchTab(1);
                                                            }
                                                        });
                                                        btn.setText("已更新");
                                                        btn.setEnabled(false);
                                                    }
                                                });
                                            }
                                        }).start();
                                    }
                                });
                                row.addView(info, new LinearLayout.LayoutParams(0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                                row.addView(btn);
                            } else {
                                info.setText(installed ? name + "  v" + lver + "  (已是最新)"
                                        : name + "  v" + mver + "  (未安装)");
                                info.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                row.addView(info);
                            }
                            row.setClickable(true);
                            row.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    showMarketPreview(name, s.optString("author", ""),
                                            mver, s.optString("note", ""), s.optString("content", ""));
                                }
                            });
                            box.addView(row);
                        }
                        if (updatable == 0) {
                            TextView ok = new TextView(AdvActivity.this);
                            ok.setText("已安装脚本均为最新版本");
                            ok.setTextColor(MdTheme.isDark(AdvActivity.this) ? 0xFF81C784 : 0xFF2E7D32);
                            ok.setTextSize(13);
                            ok.setPadding(dp(8), dp(12), dp(8), dp(12));
                            box.addView(ok, 0);
                        }
                    }
                });
            }
        }).start();
    }

    /** 市场脚本内容预览 (安装前查看) */
    private void showMarketPreview(final String name, String author, String ver,
                                   String note, String contentIgnored) {
        // 列表不携带脚本内容, 预览时动态请求, 降低服务器压力
        final MdDialog d = new MdDialog(this);
        d.title(name + (ver.isEmpty() ? "" : "  v" + ver));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (!author.isEmpty() || !note.isEmpty()) {
            TextView meta = new TextView(this);
            meta.setText((author.isEmpty() ? "" : "作者: " + author + "  ")
                    + (note.isEmpty() ? "" : note));
            meta.setTextColor(MdTheme.onSurfaceVariant(this));
            meta.setTextSize(12);
            meta.setPadding(dp(2), 0, dp(2), dp(6));
            box.addView(meta);
        }
        final TextView loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(MdTheme.onSurfaceVariant(this));
        loading.setTextSize(12);
        box.addView(loading);
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        final String[] contentRef = new String[1];
        d.actionPrimary("安装", new Runnable() {
            public void run() {
                if (contentRef[0] == null) return;
                d.dismiss();
                installMarketScript(name, contentRef[0], ver, "已安装", new Runnable() {
                    public void run() {
                        reloadMarket();
                        switchTab(1);
                    }
                });
            }
        });
        d.show();
        // 异步加载脚本内容
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String content = Backend.fetchScriptContent(AdvActivity.this, name);
                    contentRef[0] = content;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            box.removeView(loading);
                            ScrollView sc = new ScrollView(AdvActivity.this);
                            TextView tv = new TextView(AdvActivity.this);
                            tv.setText(content == null ? "无法加载脚本内容" : content);
                            tv.setTextColor(MdTheme.onSurface(AdvActivity.this));
                            tv.setTextSize(10);
                            tv.setTypeface(Typeface.MONOSPACE);
                            tv.setPadding(dp(2), dp(2), dp(2), dp(2));
                            sc.addView(tv);
                            box.addView(sc, new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));
                        }
                    });
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
        }).start();
    }

    /** 安装市场脚本: 解析头部注释变量声明, 有变量则先弹配置表单再安装 */
    private void installMarketScript(final String name, final String content,
                                      final String ver, final String doneText) {
        installMarketScript(name, content, ver, doneText, null);
    }

    private void installMarketScript(final String name, final String content,
                                      final String ver, final String doneText,
                                      final Runnable onDone) {
        final org.json.JSONArray vars = ScriptStore.parseVars(content);
        if (vars.length() == 0) {
            try {
                ScriptStore.write(this, name, content);
                ScriptStore.saveVer(this, name, ver);
                MdSnackbar.show(root, "已安装: " + name);
            } catch (Exception e) {
                MdSnackbar.show(root, "安装失败: " + e.toString());
            }
            if (onDone != null) onDone.run();
            return;
        }
        // 脚本声明了变量: 弹配置表单 (已有配置回填)
        final MdDialog d = new MdDialog(this);
        d.title("填写配置: " + name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tip = new TextView(this);
        tip.setText("该脚本在注释中声明了以下变量, 填写后自动注入为环境变量 (脚本内 process.env.XXX / os.environ 读取)");
        tip.setTextColor(MdTheme.onSurfaceVariant(this));
        tip.setTextSize(12);
        tip.setPadding(dp(2), dp(2), dp(2), dp(10));
        box.addView(tip);
        java.util.Map<String, String> old = ScriptStore.confOf(this, name);
        final java.util.List<android.widget.EditText> inputs =
                new java.util.ArrayList<android.widget.EditText>();
        for (int i = 0; i < vars.length(); i++) {
            org.json.JSONObject v = vars.optJSONObject(i);
            final String key = v.optString("key", "");
            final String label = v.optString("label", key);
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(MdTheme.onSurface(this));
            tv.setTextSize(14);
            box.addView(tv);
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setHint(key);
            et.setTextSize(14);
            et.setPadding(dp(10), dp(8), dp(10), dp(8));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
            g.setCornerRadius(dp(10));
            et.setBackground(g);
            if (v.optBoolean("password")) {
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            if (old != null) et.setText(old.containsKey(key) ? old.get(key) : "");
            box.addView(et);
            inputs.add(et);
        }
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存并安装", new Runnable() {
            public void run() {
                try {
                    // 合并保留旧配置 (含 __timeout__ 超时字段), 避免覆盖丢失
                    org.json.JSONObject conf = new org.json.JSONObject();
                    try {
                        java.util.Map<String, String> oldConf = ScriptStore.confOfRaw(AdvActivity.this, name);
                        if (oldConf != null) {
                            java.util.Iterator<java.util.Map.Entry<String, String>> it = oldConf.entrySet().iterator();
                            while (it.hasNext()) {
                                java.util.Map.Entry<String, String> en = it.next();
                                if ("__timeout__".equals(en.getKey())) {
                                    try { conf.put("__timeout__", Long.parseLong(en.getValue())); } catch (Exception ee) {}
                                }
                            }
                        }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    // 存到 .conf.json (兼容后台执行) + 同时同步到 EnvStore (前台/后台都可见)
                    java.util.List<EnvStore.Env> envs = EnvStore.load(AdvActivity.this);
                    for (int i = 0; i < vars.length(); i++) {
                        org.json.JSONObject v = vars.optJSONObject(i);
                        String key = v.optString("key", "");
                        String val = inputs.get(i).getText().toString().trim();
                        conf.put(key, val);
                        // 同步到 EnvStore (有则改, 无则加)
                        if (!val.isEmpty()) {
                            boolean found = false;
                            for (EnvStore.Env e : envs) {
                                if (e.name.equals(key)) { e.value = val; found = true; break; }
                            }
                            if (!found) envs.add(new EnvStore.Env(key, val));
                        }
                    }
                    EnvStore.save(AdvActivity.this, envs);
                    ScriptStore.saveConf(AdvActivity.this, name, conf);
                    ScriptStore.write(AdvActivity.this, name, content);
                    ScriptStore.saveVer(AdvActivity.this, name, ver);
                    MdSnackbar.show(root, doneText + ": " + name);
                    d.dismiss();
                    if (onDone != null) onDone.run();
                } catch (Exception e) {
                    MdSnackbar.show(root, "安装失败: " + e.toString());
                }
            }
        });
        d.show();
    }

    private void renderMarketList(JSONArray arr) {
        if (marketList == null) return;
        marketList.removeAllViews();
        String q = marketSearch == null ? "" : marketSearch.getText().toString().trim()
                .toLowerCase(java.util.Locale.US);
        if (arr == null || arr.length() == 0) {
            // 空态
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(60), dp(8), dp(40));
            TextView ic = new TextView(this);
            ic.setCompoundDrawablesWithIntrinsicBounds(
                    Icons.make(this, Icons.DOWNLOAD, MdTheme.onSurfaceVariant(this), 40), null, null, null);
            ic.setGravity(Gravity.CENTER);
            empty.addView(ic);
            TextView t1 = new TextView(this);
            t1.setText("暂无可用脚本");
            t1.setTextColor(MdTheme.onSurface(this));
            t1.setTextSize(15);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setGravity(Gravity.CENTER);
            t1.setPadding(0, dp(12), 0, 0);
            empty.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText("GitHub 脚本库暂无脚本\n上传脚本经审核后自动上架, 点右上角刷新即可看到");
            t2.setTextColor(MdTheme.onSurfaceVariant(this));
            t2.setTextSize(12);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(6), 0, 0);
            empty.addView(t2);
            marketList.addView(empty);
            marketStatus.setText("共 0 个脚本 · GitHub 脚本库");
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            final String name = s.optString("name", "");
            final String note = s.optString("note", "");
            final String ver = s.optString("ver", "");
            final String type = s.optString("type", "py");
            // 搜索过滤
            if (!q.isEmpty()) {
                String hay = (name + " " + note).toLowerCase(java.util.Locale.US);
                if (!hay.contains(q)) continue;
            }
            final boolean installed = ScriptStore.exists(this, name);
            final boolean updated = installed && ver.equals(ScriptStore.verOf(this, name));

            // 卡片
            MdCard card = new MdCard(this, MdCard.OUTLINED, false);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            // 第一行: 类型徽标 + 名称 + 版本 + 安装状态
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(row1);
            // 类型徽标 (圆角彩色标签)
            TextView badge = new TextView(this);
            badge.setText(type.toLowerCase());
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setGravity(Gravity.CENTER);
            int badgeBg, badgeFg;
            if (type.equals("py")) { badgeBg = 0xFF3776AB; badgeFg = 0xFFFFFFFF; }
            else if (type.equals("js")) { badgeBg = 0xFFF0DB4F; badgeFg = 0xFF000000; }
            else if (type.equals("sh")) { badgeBg = 0xFF4E9A51; badgeFg = 0xFFFFFFFF; }
            else { badgeBg = 0xFF757575; badgeFg = 0xFFFFFFFF; }
            badge.setPadding(dp(6), dp(3), dp(6), dp(3));
            android.graphics.drawable.GradientDrawable badgeBgDrawable =
                    new android.graphics.drawable.GradientDrawable();
            badgeBgDrawable.setColor(badgeBg);
            badgeBgDrawable.setCornerRadius(dp(5));
            badge.setBackground(badgeBgDrawable);
            badge.setTextColor(badgeFg);
            badge.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row1.addView(badge);
            // 名称
            TextView n = new TextView(this);
            n.setText(name.length() > 18 ? name.substring(0, 18) + "…" : name);
            n.setTextColor(MdTheme.onSurface(this));
            n.setTextSize(15);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setPadding(dp(8), 0, dp(4), 0);
            row1.addView(n, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            // 版本号
            if (!ver.isEmpty()) {
                TextView v = new TextView(this);
                v.setText("v" + ver);
                v.setTextSize(10);
                v.setTypeface(Typeface.DEFAULT_BOLD);
                v.setTextColor(MdTheme.primary(this));
                v.setPadding(dp(6), dp(2), dp(6), dp(2));
                android.graphics.drawable.GradientDrawable vBg =
                        new android.graphics.drawable.GradientDrawable();
                vBg.setColor(MdTheme.isDark(this) ? 0x332A6FDB : 0x14A5C8FF);
                vBg.setCornerRadius(dp(6));
                v.setBackground(vBg);
                v.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                vlp.setMarginEnd(dp(6));
                v.setLayoutParams(vlp);
                row1.addView(v);
            }
            // 安装状态标签
            if (updated) {
                TextView ok = new TextView(this);
                ok.setText("✓ 已安装");
                ok.setTextSize(10);
                ok.setTypeface(Typeface.DEFAULT_BOLD);
                ok.setTextColor(0xFF2E7D32);
                ok.setPadding(dp(6), dp(2), dp(6), dp(2));
                android.graphics.drawable.GradientDrawable okBg =
                        new android.graphics.drawable.GradientDrawable();
                okBg.setColor(0x1F2E7D32);
                okBg.setCornerRadius(dp(6));
                ok.setBackground(okBg);
                ok.setGravity(Gravity.CENTER);
                row1.addView(ok);
            }
            // 第二行: 说明 (可展开/收起)
            final boolean hasLongNote = note.length() > 50;
            final String noteShort = hasLongNote ? note.substring(0, 50) + "…" : note;
            final TextView nt = new TextView(this);
            nt.setText(hasLongNote ? noteShort : note);
            nt.setTextColor(MdTheme.onSurfaceVariant(this));
            nt.setTextSize(12);
            nt.setPadding(0, dp(4), 0, dp(6));
            nt.setMaxLines(hasLongNote ? 2 : 10);
            nt.setEllipsize(hasLongNote ? android.text.TextUtils.TruncateAt.END : null);
            card.addView(nt);
            if (hasLongNote) {
                // 展开/收起按钮
                final TextView expandBtn = new TextView(this);
                expandBtn.setText(IconFont.EXPAND_MORE);
                expandBtn.setTypeface(IconFont.get(this));
                expandBtn.setTextSize(16);
                expandBtn.setTextColor(MdTheme.primary(this));
                expandBtn.setGravity(Gravity.CENTER);
                expandBtn.setPadding(0, dp(2), 0, dp(4));
                expandBtn.setOnClickListener(new View.OnClickListener() {
                    boolean expanded = false;
                    public void onClick(View v) {
                        expanded = !expanded;
                        nt.setMaxLines(expanded ? 100 : 2);
                        nt.setText(note);  // 展开时显示完整内容
                        expandBtn.setText(expanded ? IconFont.EXPAND_LESS : IconFont.EXPAND_MORE);
                    }
                });
                card.addView(expandBtn);
            }
            // 第三行: 右侧按钮 + 安装量/作者
            LinearLayout row3 = new LinearLayout(this);
            row3.setOrientation(LinearLayout.HORIZONTAL);
            row3.setGravity(Gravity.CENTER_VERTICAL);
            // 作者信息
            String author = s.optString("author", "");
            if (!author.isEmpty()) {
                TextView authTv = new TextView(this);
                authTv.setText("👤 " + author);
                authTv.setTextColor(MdTheme.onSurfaceVariant(this));
                authTv.setTextSize(11);
                row3.addView(authTv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            } else {
                Spacer sp = new Spacer(this, 0, 0);
                sp.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1));
                row3.addView(sp);
            }
            // 安装按钮
            final MdButton btn = new MdButton(this,
                    updated ? "已安装" : (installed ? "更新" : "安装"),
                    updated ? MdButton.TEXT : (installed ? MdButton.TONAL : MdButton.FILLED));
            btn.setEnabled(!updated);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 安装时动态请求脚本内容, 降低列表加载压力
                    final String scriptName = name;
                    final String target = ver;
                    btn.setEnabled(false);
                    btn.setText("获取中…");
                    new Thread(new Runnable() {
                        public void run() {
                            final String content = Backend.fetchScriptContent(AdvActivity.this,
                                    scriptName);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (content == null) {
                                        btn.setText("失败");
                                        btn.setEnabled(true);
                                        return;
                                    }
                                    installMarketScript(scriptName, content, target,
                                            installed ? "已更新" : "已安装");
                                    btn.setEnabled(false);
                                }
                            });
                        }
                    }).start();
                }
            });
            card.addView(btn);
            // 点击卡片预览脚本内容 (点击标题行区域)
            row1.setClickable(true);
            row1.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 预览时动态请求脚本内容
                    final String scriptName = name;
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title(scriptName + (ver.isEmpty() ? "" : "  v" + ver));
                    LinearLayout box = new LinearLayout(AdvActivity.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    if (!author.isEmpty() || !note.isEmpty()) {
                        TextView meta = new TextView(AdvActivity.this);
                        meta.setText((author.isEmpty() ? "" : "作者: " + author + "  ")
                                + (note.isEmpty() ? "" : note));
                        meta.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                        meta.setTextSize(12);
                        meta.setPadding(dp(2), 0, dp(2), dp(6));
                        box.addView(meta);
                    }
                    final TextView loading = new TextView(AdvActivity.this);
                    loading.setText("加载中…");
                    loading.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                    loading.setTextSize(12);
                    box.addView(loading);
                    d.content(box);
                    d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
                    d.show();
                    // 异步加载脚本内容
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                final String content = Backend.fetchScriptContent(AdvActivity.this,
                                        scriptName);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        loading.setText("");
                                        box.removeView(loading);
                                        ScrollView sc = new ScrollView(AdvActivity.this);
                                        TextView tv = new TextView(AdvActivity.this);
                                        tv.setText(content == null ? "无法加载脚本内容" : content);
                                        tv.setTextColor(MdTheme.onSurface(AdvActivity.this));
                                        tv.setTextSize(10);
                                        tv.setTypeface(Typeface.MONOSPACE);
                                        tv.setPadding(dp(2), dp(2), dp(2), dp(2));
                                        sc.addView(tv);
                                        box.addView(sc, new LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                dp(280)));
                                    }
                                });
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    }).start();
                }
            });
            marketList.addView(card);
            marketList.addView(new Spacer(this, 0, dp(8)));
        }
        marketStatus.setText("共 " + arr.length() + " 个脚本 · GitHub 脚本库");
    }

    private JSONArray fetchAgain() {
        return Backend.fetchScripts(this);
    }

    /** 圆角背景工具 */
    private android.graphics.drawable.GradientDrawable bannerBg(int dark, int light, float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.isDark(this) ? dark : light);
        g.setCornerRadius(radius);
        return g;
    }

    private void newScript() {        final MdDialog d = new MdDialog(this);
        d.title("新建脚本");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "文件名 (如 test.py / test.sh / test.js)", false);
        form.addView(name);
        MdButton importBtn = new MdButton(this, "从文件导入", MdButton.TONAL);
        importBtn.setIcon(Icons.make(this, Icons.FOLDER, MdTheme.primary(this), 18));
        form.addView(importBtn);
        importBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_IMPORT_FILE); } catch (Exception e) { MdSnackbar.show(root, "无法打开文件选择器"); }
            }
        });
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("创建", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                if (n.isEmpty()) { MdSnackbar.show(root, "文件名不能为空"); return; }
                if (ScriptStore.exists(AdvActivity.this, n)) {
                    MdSnackbar.show(root, "文件已存在");
                    return;
                }
                // 按类型写正确的注释头 (sh/py 用 #, js 用 //)
                String lower = n.toLowerCase();
                String header = (lower.endsWith(".js") || lower.endsWith(".mjs"))
                        ? "// " + n + "\n" : "# " + n + "\n";
                ScriptStore.write(AdvActivity.this, n, header);
                d.dismiss();
                editScript(n);
            }
        });
        d.show();
    }

    private void editScript(final String scriptName) {
        final MdDialog d = new MdDialog(this);
        d.fullscreen();   // 全屏模式: 铺满屏幕, 用主题背景色铺底
        // —— 顶部工具栏: 返回 + 标题 + 保存按钮 ——
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setGravity(Gravity.CENTER_VERTICAL);
        toolBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        // 状态栏高度适配
        int statusBarH = 0;
        try {
            int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (rid > 0) statusBarH = getResources().getDimensionPixelSize(rid);
        } catch (Exception ignored) {}
        toolBar.setPadding(dp(4), dp(4) + statusBarH, dp(4), dp(4));
        // 返回按钮
        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextSize(22);
        backBtn.setTextColor(MdTheme.onSurface(this));
        backBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        final Runnable doDismiss = new Runnable() { public void run() { d.dismiss(); } };
        backBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doDismiss.run(); } });
        toolBar.addView(backBtn);
        // 脚本名标题
        TextView titleTv = new TextView(this);
        titleTv.setText(scriptName);
        titleTv.setTextColor(MdTheme.onSurface(this));
        titleTv.setTextSize(16);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setPadding(dp(8), 0, dp(8), 0);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        toolBar.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        // 保存按钮
        TextView saveBtn = new TextView(this);
        saveBtn.setText("保存");
        saveBtn.setTextSize(14);
        saveBtn.setTextColor(MdTheme.primary(this));
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setPadding(dp(16), dp(8), dp(12), dp(8));
        toolBar.addView(saveBtn);
        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(MdTheme.outlineVariant(this));
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        // ── 编辑区 (多行输入框, 全屏撑满) ──
        final android.widget.EditText edit = new android.widget.EditText(this);
        edit.setText(ScriptStore.read(this, scriptName));
        edit.setTextSize(13);
        edit.setTextColor(MdTheme.onSurface(this));
        edit.setHintTextColor(MdTheme.onSurfaceVariant(this) & 0xAAFFFFFF);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setBackgroundColor(Color.TRANSPARENT);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setVerticalScrollBarEnabled(true);
        edit.setMinLines(8);
        // 底部操作栏: 超时设置 + 变量开关
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(14), dp(6), dp(14), dp(12));
        bottomBar.setBackgroundColor(MdTheme.surfaceContainer(this));
        // 超时输入
        final android.widget.EditText toInput = new android.widget.EditText(this);
        long oldTo = ScriptStore.getTimeout(this, scriptName);
        toInput.setHint("超时(秒)");
        toInput.setText(oldTo != 180L ? String.valueOf(oldTo) : "");
        toInput.setTextSize(12);
        toInput.setTextColor(MdTheme.onSurface(this));
        toInput.setHintTextColor(MdTheme.onSurfaceVariant(this));
        toInput.setBackgroundColor(Color.TRANSPARENT);
        toInput.setPadding(dp(8), dp(4), dp(8), dp(4));
        toInput.setSingleLine(true);
        toInput.setLayoutParams(new LinearLayout.LayoutParams(dp(100), -2));
        toInput.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable toBg = new android.graphics.drawable.GradientDrawable();
        toBg.setColor(MdTheme.surfaceContainerHigh(this));
        toBg.setCornerRadius(dp(8));
        toInput.setBackground(toBg);
        bottomBar.addView(toInput);
        // 超时标签
        TextView toLabel = new TextView(this);
        toLabel.setText("秒");
        toLabel.setTextSize(12);
        toLabel.setTextColor(MdTheme.onSurfaceVariant(this));
        toLabel.setPadding(dp(4), 0, dp(12), 0);
        bottomBar.addView(toLabel);
        // 变量编辑区 (直接在编辑器底部, 可折叠展开)
        final org.json.JSONArray vars = ScriptStore.parseVars(ScriptStore.read(this, scriptName));
        final boolean hasVars = vars.length() > 0;
        String varHint = hasVars ? "变量(" + vars.length() + ")" : "无变量";
        final TextView varInfo = new TextView(this);
        varInfo.setText(varHint);
        varInfo.setTextSize(12);
        varInfo.setTextColor(MdTheme.primary(this));
        varInfo.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.graphics.drawable.GradientDrawable varBg = new android.graphics.drawable.GradientDrawable();
        varBg.setColor(MdTheme.primaryContainer(this));
        varBg.setCornerRadius(dp(8));
        varInfo.setBackground(varBg);
        bottomBar.addView(varInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        // 变量编辑面板 (直接展开在编辑器下方, 不弹窗)
        final LinearLayout varPanel = new LinearLayout(this);
        varPanel.setOrientation(LinearLayout.VERTICAL);
        varPanel.setPadding(dp(14), dp(6), dp(14), dp(6));
        varPanel.setBackgroundColor(MdTheme.surfaceContainerHigh(this));
        // 标题行
        LinearLayout varHeader = new LinearLayout(this);
        varHeader.setOrientation(LinearLayout.HORIZONTAL);
        varHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView varTitle = new TextView(this);
        varTitle.setText("脚本变量 (保存时同步到环境变量库)");
        varTitle.setTextSize(12);
        varTitle.setTextColor(MdTheme.primary(this));
        varTitle.setTypeface(Typeface.DEFAULT_BOLD);
        varHeader.addView(varTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView varChevron = new TextView(this);
        varChevron.setText("▾");
        varChevron.setTextColor(MdTheme.onSurfaceVariant(this));
        varChevron.setTextSize(16);
        varChevron.setPadding(dp(8), 0, 0, 0);
        varHeader.addView(varChevron);
        varPanel.addView(varHeader);
        // 变量条目
        final java.util.List<String> varKeys = new java.util.ArrayList<String>();
        final java.util.List<android.widget.EditText> varInputs = new java.util.ArrayList<android.widget.EditText>();
        for (int i = 0; i < vars.length(); i++) {
            try {
                org.json.JSONObject vo = vars.optJSONObject(i);
                if (vo == null) continue;
                String key = vo.optString("key", "");
                if (key.isEmpty()) continue;
                varKeys.add(key);
                // 变量名标签
                TextView vLabel = new TextView(this);
                vLabel.setText(key + "  " + vo.optString("label", ""));
                vLabel.setTextSize(11);
                vLabel.setTextColor(MdTheme.onSurfaceVariant(this));
                vLabel.setPadding(0, dp(6), 0, dp(2));
                varPanel.addView(vLabel);
                // 输入框
                final android.widget.EditText vInp = new android.widget.EditText(this);
                String oldVal = EnvStore.get(this, key);
                vInp.setText(oldVal == null ? "" : oldVal);
                vInp.setTextSize(13);
                vInp.setTextColor(MdTheme.onSurface(this));
                vInp.setHint(vo.optString("label", key));
                vInp.setPadding(dp(10), dp(6), dp(10), dp(6));
                vInp.setSingleLine(true);
                android.graphics.drawable.GradientDrawable ibg = new android.graphics.drawable.GradientDrawable();
                ibg.setColor(MdTheme.surface(this));
                ibg.setCornerRadius(dp(6));
                ibg.setStroke(dp(1), MdTheme.outlineVariant(this));
                vInp.setBackground(ibg);
                varInputs.add(vInp);
                varPanel.addView(vInp);
            } catch (Exception ignored) {}
        }
        // 默认收起 (无变量时不显示, 有变量时默认展开)
        final boolean[] varExpanded = {true};
        varPanel.setVisibility(hasVars ? View.VISIBLE : View.GONE);
        varInfo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (varKeys.isEmpty()) {
                    MdSnackbar.show(root, "脚本未声明变量");
                    return;
                }
                varExpanded[0] = !varExpanded[0];
                varPanel.setVisibility(varExpanded[0] ? View.VISIBLE : View.GONE);
                varChevron.setText(varExpanded[0] ? "▾" : "▸");
            }
        });
        // ── 组装 ──
        LinearLayout editorRoot = new LinearLayout(this);
        editorRoot.setOrientation(LinearLayout.VERTICAL);
        editorRoot.addView(toolBar);
        editorRoot.addView(divider);
        editorRoot.addView(edit, new LinearLayout.LayoutParams(-1, 0, 1f));
        editorRoot.addView(varPanel);   // 变量编辑区 (可折叠)
        editorRoot.addView(bottomBar);
        // 放到对话框 (替换默认内容区)
        d.content(editorRoot);
        // 不显示默认操作栏, 隐藏 MdDialog 的 actionBar
        d.hideActions();
        d.show();
        // 保存按钮逻辑
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 超时解析
                long timeout = 0;
                String toStr = toInput.getText().toString().trim();
                if (!toStr.isEmpty()) {
                    try { timeout = Long.parseLong(toStr); } catch (Exception e) { MdSnackbar.show(root, "超时时间需为整数(秒)"); return; }
                    if (timeout < 1 || timeout > 86400) { MdSnackbar.show(root, "超时范围 1-86400 秒"); return; }
                }
                ScriptStore.write(AdvActivity.this, scriptName, edit.getText().toString());
                ScriptStore.saveTimeout(AdvActivity.this, scriptName, timeout);
                // 变量 upsert: 有则改, 无则加
                int added = 0, updated = 0;
                if (!varKeys.isEmpty()) {
                    java.util.List<EnvStore.Env> envs = EnvStore.load(AdvActivity.this);
                    for (int i = 0; i < varKeys.size(); i++) {
                        String val = varInputs.get(i).getText().toString().trim();
                        if (val.isEmpty()) continue; // 空值不写入
                        boolean found = false;
                        for (EnvStore.Env e : envs) {
                            if (e.name.equals(varKeys.get(i))) {
                                if (!val.equals(e.value)) { e.value = val; updated++; }
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            envs.add(new EnvStore.Env(varKeys.get(i), val));
                            added++;
                        }
                    }
                    if (added + updated > 0) {
                        EnvStore.save(AdvActivity.this, envs);
                    }
                }
                d.dismiss();
                String msg = "已保存 " + scriptName;
                if (added > 0) msg += " · 新增 " + added + " 个变量";
                else if (updated > 0) msg += " · 更新 " + updated + " 个变量";
                MdSnackbar.show(root, msg);
                renderScripts();
            }
        });
        // 返回键也能保存吗？不保存直接退
        // 点返回按钮 = 关闭，不保存
    }

    /** 定时执行设置: 每隔几分钟/几小时/几天 */
    private void schedDialog(final String scriptName) {
        final MdDialog d = new MdDialog(this);
        d.title("定时执行: " + scriptName);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final JSONObject cfg = getSched(this).optJSONObject(scriptName);
        final MdTextField every = new MdTextField(this, "间隔数值 (1-999)", false);
        if (cfg != null && cfg.optInt("every", 0) > 0) every.setText(String.valueOf(cfg.optInt("every")));
        box.addView(every);
        final int[] cur = {cfg != null && "hour".equals(cfg.optString("unit")) ? 1
                : (cfg != null && "day".equals(cfg.optString("unit")) ? 2 : 0)};
        LinearLayout units = new LinearLayout(this);
        units.setOrientation(LinearLayout.HORIZONTAL);
        units.setPadding(0, dp(4), 0, 0);
        final MdButton[] btns = new MdButton[3];
        String[] labels = {"分钟", "小时", "天"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            btns[i] = new MdButton(this, labels[i], idx == cur[0] ? MdButton.FILLED : MdButton.OUTLINED);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2);
            bp.weight = 1f;
            bp.rightMargin = dp(6);
            units.addView(btns[i], bp);
            btns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cur[0] = idx;
                    for (int j = 0; j < 3; j++)
                        btns[j].applyVariant(j == cur[0] ? MdButton.FILLED : MdButton.OUTLINED);
                }
            });
        }
        box.addView(units);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.action("关闭定时", new Runnable() {
            public void run() {
                JSONObject s = getSched(AdvActivity.this);
                s.remove(scriptName);
                saveSched(AdvActivity.this, s);
                MdSnackbar.show(root, "已关闭定时: " + scriptName);
                d.dismiss();
                renderScripts();
            }
        });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                int v = 0;
                try { v = Integer.parseInt(every.getText().toString().trim()); } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                if (v < 1 || v > 999) { MdSnackbar.show(root, "请输入 1-999 的整数"); return; }
                String unit = cur[0] == 1 ? "hour" : cur[0] == 2 ? "day" : "min";
                try {
                    JSONObject s = getSched(AdvActivity.this);
                    JSONObject c = new JSONObject();
                    c.put("every", v);
                    c.put("unit", unit);
                    c.put("last", 0);       // 0 → 下次轮询立即执行一次
                    c.put("enabled", true);
                    s.put(scriptName, c);
                    saveSched(AdvActivity.this, s);
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                String u = cur[0] == 1 ? " 小时" : cur[0] == 2 ? " 天" : " 分钟";
                MdSnackbar.show(root, "已设置: 每 " + v + u + " 执行一次");
                d.dismiss();
                renderScripts();
            }
        });
        d.show();
    }

    /** 脚本代码预览: 等宽字体展示 + 复制/运行/编辑 */
    private void showScriptPreview(final String name) {
        String content = ScriptStore.read(this, name);
        final MdDialog d = new MdDialog(this);
        d.title("脚本预览 · " + name);
        ScrollView sc = new ScrollView(this);
        sc.setHorizontalScrollBarEnabled(true);
        TextView code = new TextView(this);
        code.setText(content.isEmpty() ? "(空文件)" : content);
        code.setTextColor(MdTheme.onSurface(this));
        code.setTextSize(11);
        code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(dp(12), dp(12), dp(12), dp(12));
        code.setTextIsSelectable(true);
        android.graphics.drawable.GradientDrawable cdg = new android.graphics.drawable.GradientDrawable();
        cdg.setColor(MdTheme.isDark(this) ? 0xFF1E1E28 : 0xFFF6F5F2);
        cdg.setCornerRadius(dp(8));
        code.setBackground(cdg);
        sc.addView(code);
        android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
        fl.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, dp(320)));
        fl.addView(sc);
        d.content(fl);
        d.action("复制", new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("script", content));
                    MdSnackbar.show(root, "已复制到剪贴板");
                } catch (Exception e) {
                    MdSnackbar.show(root, "复制失败");
                }
            }
        });
        d.action("运行", new Runnable() {
            public void run() { d.dismiss(); runScriptFile(name); }
        });
        d.actionPrimary("编辑", new Runnable() {
            public void run() { d.dismiss(); editScript(name); }
        });
        d.show();
    }

    private void runScriptFile(String name) {
        if (name == null || ScriptStore.isMetaFile(name)) {
            MdSnackbar.show(root, "该文件是脚本配置文件, 不能直接运行");
            return;
        }
        if (!RuntimeManager.isReady(this)) {
            MdSnackbar.show(root, "运行时未就绪, 请先完成解压");
            return;
        }
        final String type = ScriptStore.typeOf(name);
        final String content = ScriptStore.read(this, name);
        if (content.isEmpty()) { MdSnackbar.show(root, "脚本为空"); return; }
        MdSnackbar.show(root, "运行 " + name);
        ScriptRunner.markRunning(name);   // 标记运行中 → 卡片显示转圈
        // 立即刷新卡片, 让转圈马上出现 (无需等执行结束)
        try { if (currentTab == 1) renderScripts(); } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        final String scriptCmd = buildScriptCommand(this, name, content, type);
        new Thread(new Runnable() {
            public void run() {
                runShell(RuntimeManager.buildCommand(AdvActivity.this, scriptCmd), name);
            }
        }).start();
    }

    // 构建脚本执行命令: sh 脚本先经 rewriteScript 重写(/tmp 映射 + curl 重写), 写入 files/tmp 临时文件
    // 路径一律单引号包裹, 兼容含空格/括号/特殊字符的文件名
    private static String buildScriptCommand(Context ctx, String name, String content, String type) {
        String scriptPath = new java.io.File(ScriptStore.dir(ctx), name).getAbsolutePath();
        if ("py".equals(type)) return RuntimeManager.pythonBin(ctx) + " '" + scriptPath + "'";
        if ("js".equals(type)) return RuntimeManager.nodeBin(ctx) + " '" + scriptPath + "'";
        String rewritten = RuntimeManager.rewriteScript(ctx, content);
        String target = scriptPath;
        if (!rewritten.equals(content)) {
            try {
                java.io.File d = new java.io.File(ctx.getFilesDir(), "tmp");
                d.mkdirs();
                java.io.File f = new java.io.File(d, "adv_" + name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(rewritten.getBytes("UTF-8"));
                fos.close();
                target = f.getAbsolutePath();
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        return "/system/bin/sh '" + target + "'";
    }

    // ================= 拉库 (订阅管理) =================
    private void renderSubscriptions() {
        contentWrap.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView sc = new ScrollView(this);
        sc.addView(page);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("订阅管理 (拉库)");
        hint.setTextColor(MdTheme.onSurfaceVariant(this));
        hint.setTextSize(13);
        page.addView(hint);

        List<SubscriptionStore.Subscription> list = SubscriptionStore.load(this);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无订阅\n点右下角 + 添加远程仓库或文件地址");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            page.addView(empty);
        }
        for (final SubscriptionStore.Subscription sub : list) {
            MdCard card = new MdCard(this, MdCard.OUTLINED, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(8);
            card.setLayoutParams(cp);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            card.addView(row);
            // 标题行
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView nameTv = new TextView(this);
            nameTv.setText(sub.name);
            nameTv.setTextColor(MdTheme.onSurface(this));
            nameTv.setTextSize(14);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameRow.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
            // 状态标签
            TextView statusTv = new TextView(this);
            statusTv.setText(sub.status.equals("running") ? "运行中" : sub.status.equals("error") ? "失败" : "空闲");
            statusTv.setTextSize(11);
            statusTv.setTypeface(Typeface.DEFAULT_BOLD);
            statusTv.setPadding(dp(8), dp(3), dp(8), dp(3));
            int stColor = sub.status.equals("running") ? 0xFF26A69A : sub.status.equals("error") ? 0xFFE53935 : MdTheme.onSurfaceVariant(this);
            statusTv.setTextColor(stColor);
            android.graphics.drawable.GradientDrawable stBg = new android.graphics.drawable.GradientDrawable();
            stBg.setColor((stColor & 0x00FFFFFF) | 0x1F000000);
            stBg.setCornerRadius(dp(10));
            statusTv.setBackground(stBg);
            nameRow.addView(statusTv);
            if (sub.status.equals("running")) {
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
                LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(16), dp(16));
                pbp.leftMargin = dp(4);
                nameRow.addView(pb, pbp);
            }
            row.addView(nameRow);
            // 元信息
            TextView meta = new TextView(this);
            String typeLabel = sub.type.equals("repo") ? "仓库" : "文件";
            meta.setText(typeLabel + "  |  脚本 " + sub.scriptCount + " 个  |  "
                    + (sub.lastRunAt > 0 ? new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(sub.lastRunAt)) : "未拉取"));
            meta.setTextColor(MdTheme.onSurfaceVariant(this));
            meta.setTextSize(11);
            meta.setPadding(0, dp(2), 0, 0);
            row.addView(meta);
            // URL
            TextView urlTv = new TextView(this);
            urlTv.setText(sub.url);
            urlTv.setTextColor(MdTheme.onSurfaceVariant(this));
            urlTv.setTextSize(10);
            urlTv.setSingleLine(true);
            urlTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            urlTv.setPadding(0, dp(2), 0, 0);
            row.addView(urlTv);
            // 按钮
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(6), 0, 0);
            row.addView(ops);
            ops.addView(smallBtn("拉取", false, new Runnable() {
                public void run() { syncSubscription(sub); }
            }));
            ops.addView(smallBtn("定时", false, new Runnable() {
                public void run() { subSchedDialog(sub); }
            }));
            ops.addView(smallBtn("编辑", false, new Runnable() {
                public void run() { editSubscription(sub); }
            }));
            ops.addView(smallBtn("删除", true, new Runnable() {
                public void run() {
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title("删除订阅");
                    d.message("确定删除 " + sub.name + " ?\n已拉取的脚本不会删除。");
                    d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                    d.actionPrimary("删除", new Runnable() {
                        public void run() {
                            List<SubscriptionStore.Subscription> all = SubscriptionStore.load(AdvActivity.this);
                            // 按 ID 删除 (load 返回新对象, 不能直接用 remove)
                            SubscriptionStore.Subscription toRemove = null;
                            for (SubscriptionStore.Subscription s : all) {
                                if (s.id.equals(sub.id)) { toRemove = s; break; }
                            }
                            if (toRemove != null) all.remove(toRemove);
                            SubscriptionStore.save(AdvActivity.this, all);
                            d.dismiss();
                            renderSubscriptions();
                        }
                    });
                    d.show();
                }
            }));
            // 错误信息
            if (!sub.lastError.isEmpty()) {
                TextView errTv = new TextView(this);
                errTv.setText(sub.lastError);
                errTv.setTextColor(0xFFE53935);
                errTv.setTextSize(10);
                errTv.setPadding(0, dp(4), 0, 0);
                row.addView(errTv);
            }
            // 日志查看按钮
            if (!sub.lastLog.isEmpty()) {
                TextView logBtn = new TextView(this);
                logBtn.setText("查看日志");
                logBtn.setTextSize(10);
                logBtn.setTextColor(MdTheme.primary(this));
                logBtn.setPadding(0, dp(2), 0, 0);
                logBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        final MdDialog d = new MdDialog(AdvActivity.this);
                        d.title("拉取日志: " + sub.name);
                        d.message(sub.lastLog.substring(0, Math.min(sub.lastLog.length(), 2000)));
                        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
                        d.show();
                    }
                });
                row.addView(logBtn);
            }
            page.addView(card);
        }
        // 底部留白
        TextView pad = new TextView(this);
        pad.setHeight(dp(72));
        page.addView(pad);
    }

    /** 添加订阅弹窗 */
    private void addSubscription() {
        final MdDialog d = new MdDialog(this);
        d.title("添加订阅");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 类型选择
        final LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] selType = {0};
        String[] typeLabels2 = {"仓库 (repo)", "文件 (raw)"};
        final TextView[] typeTvs = new TextView[2];
        // 先声明所有输入框，供点击监听器引用
        final MdTextField urlField = new MdTextField(this, "Git 仓库 URL", false);
        final MdTextField wlField = new MdTextField(this, "白名单 (正则, 留空=全部)", false);
        final MdTextField blField = new MdTextField(this, "黑名单 (正则, 留空=无)", false);
        final MdTextField brField = new MdTextField(this, "分支 (默认 main)", false);
        final MdTextField cronField = new MdTextField(this, "定时更新 (cron, 留空=手动)", false);
        for (int i = 0; i < 2; i++) {
            final int fi = i;
            final TextView tv = new TextView(this);
            typeTvs[i] = tv;
            tv.setText(typeLabels2[i]);
            tv.setTextSize(12);
            tv.setPadding(dp(12), dp(6), dp(12), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(i == 0 ? MdTheme.primary(this) : (MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5));
            bg.setCornerRadius(dp(14));
            tv.setBackground(bg);
            tv.setTextColor(i == 0 ? MdTheme.onPrimary(this) : MdTheme.onSurfaceVariant(this));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selType[0] = fi;
                    // 更新两个按钮颜色
                    for (int j = 0; j < 2; j++) {
                        android.graphics.drawable.GradientDrawable b = new android.graphics.drawable.GradientDrawable();
                        b.setColor(j == selType[0] ? MdTheme.primary(AdvActivity.this) : (MdTheme.isDark(AdvActivity.this) ? 0xFF2A2A2E : 0xFFF1F3F5));
                        b.setCornerRadius(dp(14));
                        typeTvs[j].setBackground(b);
                        typeTvs[j].setTextColor(j == selType[0] ? MdTheme.onPrimary(AdvActivity.this) : MdTheme.onSurfaceVariant(AdvActivity.this));
                    }
                    // 切换 UI: 仓库显示分支, 文件隐藏分支
                    if (selType[0] == 0) {
                        urlField.setHint("Git 仓库 URL");
                        urlField.setPlaceholder("https://github.com/user/repo");
                        brField.setVisibility(View.VISIBLE);
                    } else {
                        urlField.setHint("文件 URL");
                        urlField.setPlaceholder("https://raw.githubusercontent.com/.../xxx.py");
                        brField.setVisibility(View.GONE);
                    }
                }
            });
            typeRow.addView(tv);
        }
        box.addView(typeRow);
        // URL 输入
        box.addView(urlField);
        // 白名单
        box.addView(wlField);
        // 黑名单
        box.addView(blField);
        // 分支
        box.addView(brField);
        // 定时更新
        box.addView(cronField);

        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("添加", new Runnable() {
            public void run() {
                String url = urlField.getText().trim();
                if (url.isEmpty()) { MdSnackbar.show(root, "请输入 URL"); return; }
                SubscriptionStore.Subscription sub = new SubscriptionStore.Subscription();
                sub.type = selType[0] == 0 ? "repo" : "raw";
                sub.url = url;
                sub.name = SubscriptionStore.inferName(url);
                sub.whitelist = wlField.getText().trim();
                sub.blacklist = blField.getText().trim();
                sub.branch = brField.getText().trim();
                if (sub.branch.isEmpty()) sub.branch = "main";
                sub.schedule = cronField.getText().trim();
                List<SubscriptionStore.Subscription> all = SubscriptionStore.load(AdvActivity.this);
                all.add(sub);
                SubscriptionStore.save(AdvActivity.this, all);
                d.dismiss();
                // 立即拉取
                syncSubscription(sub);
                renderSubscriptions();
            }
        });
        d.show();
    }

    /** 编辑订阅 */
    private void editSubscription(final SubscriptionStore.Subscription sub) {
        final MdDialog d = new MdDialog(this);
        d.title("编辑: " + sub.name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final MdTextField urlField = new MdTextField(this, "URL", false);
        urlField.setText(sub.url);
        box.addView(urlField);
        final MdTextField wlField = new MdTextField(this, "白名单 (留空=全部)", false);
        wlField.setText(sub.whitelist);
        box.addView(wlField);
        final MdTextField blField = new MdTextField(this, "黑名单 (留空=无)", false);
        blField.setText(sub.blacklist);
        box.addView(blField);
        final MdTextField brField = new MdTextField(this, "分支", false);
        brField.setText(sub.branch);
        box.addView(brField);
        final MdTextField cronField = new MdTextField(this, "定时更新 (cron, 留空=手动)", false);
        cronField.setText(sub.schedule);
        box.addView(cronField);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                sub.url = urlField.getText().trim();
                sub.whitelist = wlField.getText().trim();
                sub.blacklist = blField.getText().trim();
                sub.branch = brField.getText().trim();
                if (sub.branch.isEmpty()) sub.branch = "main";
                sub.schedule = cronField.getText().trim();
                List<SubscriptionStore.Subscription> all = SubscriptionStore.load(AdvActivity.this);
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(sub.id)) { all.set(i, sub); break; }
                }
                SubscriptionStore.save(AdvActivity.this, all);
                d.dismiss();
                renderSubscriptions();
            }
        });
        d.show();
    }

    /** 定时设置弹窗 */
    private void subSchedDialog(final SubscriptionStore.Subscription sub) {
        final MdDialog d = new MdDialog(this);
        d.title("定时更新: " + sub.name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final MdTextField cronField = new MdTextField(this, "cron 表达式 (留空=手动)", false);
        cronField.setText(sub.schedule);
        box.addView(cronField);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.action("关闭定时", new Runnable() {
            public void run() {
                sub.schedule = "";
                List<SubscriptionStore.Subscription> all = SubscriptionStore.load(AdvActivity.this);
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(sub.id)) { all.set(i, sub); break; }
                }
                SubscriptionStore.save(AdvActivity.this, all);
                d.dismiss();
                renderSubscriptions();
            }
        });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                sub.schedule = cronField.getText().trim();
                List<SubscriptionStore.Subscription> all = SubscriptionStore.load(AdvActivity.this);
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(sub.id)) { all.set(i, sub); break; }
                }
                SubscriptionStore.save(AdvActivity.this, all);
                d.dismiss();
                renderSubscriptions();
            }
        });
        d.show();
    }

    /** 执行拉取: 下载远程仓库/文件 + 过滤脚本 + 注册变量 */
    private void syncSubscription(final SubscriptionStore.Subscription sub) {
        if (sub.status.equals("running")) { MdSnackbar.show(root, "该订阅正在拉取中"); return; }
        sub.status = "running";
        sub.lastError = "";
        saveSub(sub);
        renderSubscriptions();
        MdSnackbar.show(root, "开始拉取: " + sub.name);
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder log = new StringBuilder();
                int count = 0;
                try {
                    java.io.File targetDir = SubscriptionStore.subDir(AdvActivity.this, sub.id);
                    if (sub.type.equals("repo")) {
                        // 仓库: 下载 GitHub ZIP 并解压
                        java.io.File repoDir = new java.io.File(targetDir, "repo");
                        repoDir.mkdirs();
                        // 构造下载 URL: 先去掉 .git, 再去掉尾巴 /
                        String dlUrl = sub.url.trim();
                        dlUrl = dlUrl.replaceAll("\\.git$", "");
                        dlUrl = dlUrl.replaceAll("/$", "");
                        dlUrl = dlUrl + "/archive/refs/heads/" + sub.branch + ".zip";
                        java.io.File zipFile = new java.io.File(targetDir, "repo.zip");
                        // 下载 ZIP
                        byte[] zipData = httpGet(dlUrl, log);
                        if (zipData != null && zipData.length > 0) {
                            java.nio.file.Files.write(zipFile.toPath(), zipData);
                            log.append("下载成功: " + dlUrl + " (" + (zipData.length / 1024) + " KB)\n");
                            // 解压到 repoDir
                            unzip(zipData, repoDir, log);
                            // 解压后第一层目录是 repo-branch, 需要进去
                            java.io.File[] subDirs = repoDir.listFiles();
                            java.io.File actualDir = repoDir;
                            if (subDirs != null && subDirs.length == 1 && subDirs[0].isDirectory()) {
                                actualDir = subDirs[0]; // 跳过顶层目录 (repo-branch)
                            }
                            count = scanAndCopy(actualDir, sub, AdvActivity.this, log);
                            zipFile.delete();
                        } else {
                            log.append("下载失败: " + dlUrl + "\n");
                        }
                    } else {
                        // raw 文件: 直接下载到 scripts 目录
                        String fileName = sub.url.substring(sub.url.lastIndexOf('/') + 1);
                        if (fileName.isEmpty()) fileName = sub.name + ".py";
                        // 去掉 URL 查询参数
                        int qIdx = fileName.indexOf('?');
                        if (qIdx > 0) fileName = fileName.substring(0, qIdx);
                        java.io.File target = new java.io.File(ScriptStore.dir(AdvActivity.this), fileName);
                        byte[] data = httpGet(sub.url, log);
                        if (data != null && data.length > 0) {
                            java.nio.file.Files.write(target.toPath(), data);
                            log.append("下载成功: " + fileName + " (" + (data.length / 1024) + " KB)\n");
                            // 解析变量声明 → 自动注册到 EnvStore
                            String content = new String(data, "UTF-8");
                            org.json.JSONArray vars = ScriptStore.parseVars(content);
                            if (vars.length() > 0) {
                                java.util.List<EnvStore.Env> envs = EnvStore.load(AdvActivity.this);
                                boolean changed = false;
                                for (int i = 0; i < vars.length(); i++) {
                                    org.json.JSONObject vo = vars.optJSONObject(i);
                                    String key = vo.optString("key", "");
                                    if (key.isEmpty()) continue;
                                    boolean found = false;
                                    for (EnvStore.Env e : envs) {
                                        if (e.name.equals(key)) { found = true; break; }
                                    }
                                    if (!found) {
                                        envs.add(new EnvStore.Env(key, ""));
                                        changed = true;
                                        log.append("添加变量: ").append(key).append("\n");
                                    }
                                }
                                if (changed) EnvStore.save(AdvActivity.this, envs);
                            }
                            count = 1;
                        } else {
                            log.append("下载失败: " + sub.url + "\n");
                        }
                    }
                    sub.scriptCount = count;
                    sub.status = "idle";
                    sub.lastRunAt = System.currentTimeMillis();
                    sub.lastError = "";
                    sub.lastLog = log.toString();
                    saveSub(sub);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            MdSnackbar.show(root, "拉取完成: " + sub.name + " (" + sub.scriptCount + " 个脚本)");
                            renderSubscriptions();
                        }
                    });
                } catch (final Exception e) {
                    sub.status = "error";
                    sub.lastError = e.getMessage() != null ? e.getMessage() : "未知错误";
                    sub.lastLog = log.toString();
                    saveSub(sub);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            MdSnackbar.show(root, "拉取失败: " + sub.name);
                            renderSubscriptions();
                        }
                    });
                }
            }
        }).start();
    }

    /** HTTP GET 下载, 返回字节数组 */
    private byte[] httpGet(String urlStr, StringBuilder log) {
        try {
            log.append("开始下载: " + urlStr + "\n");
            java.net.URL url = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "TaskPro/1.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            int len = conn.getContentLength();
            log.append("HTTP " + code + " (" + (len > 0 ? (len / 1024) + " KB" : "未知大小") + ")\n");
            if (code != 200) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0, lastLog = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                // 每 500KB 打一次日志
                if (total - lastLog > 512 * 1024) {
                    log.append("下载进度: " + (total / 1024) + " KB\n");
                    lastLog = total;
                }
            }
            in.close();
            log.append("下载完成: " + (total / 1024) + " KB\n");
            return out.toByteArray();
        } catch (java.net.SocketTimeoutException e) {
            log.append("! 下载超时: ").append(e.getMessage()).append("\n");
            try { android.util.Log.w("TaskPro","httpGet timeout: " + e.getMessage()); } catch(Exception __){}
            return null;
        } catch (Exception e) {
            log.append("! 下载异常: ").append(e.getMessage()).append("\n");
            try { android.util.Log.w("TaskPro","httpGet error: " + e.getMessage()); } catch(Exception __){}
            return null;
        }
    }

    /** 解压 ZIP 字节数组到目标目录 */
    private void unzip(byte[] zipData, java.io.File destDir, StringBuilder log) {
        try {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(zipData));
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                java.io.File outFile = new java.io.File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close();
                }
                zis.closeEntry();
            }
            zis.close();
        } catch (Exception e) {
            log.append("! 解压失败: ").append(e.getMessage()).append("\n");
        }
    }

    /** 扫描仓库目录, 按白名单/黑名单过滤, 复制到 scripts, 解析变量和定时任务 */
    private int scanAndCopy(java.io.File dir, SubscriptionStore.Subscription sub,
                            Context ctx, StringBuilder log) {
        int count = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        String[] exts = sub.extensions.split("\\s+");
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                count += scanAndCopy(f, sub, ctx, log);
                continue;
            }
            String name = f.getName();
            // 扩展名过滤
            boolean okExt = false;
            for (String ext : exts) {
                if (name.endsWith("." + ext)) { okExt = true; break; }
            }
            if (!okExt) continue;
            // 白名单
            if (!sub.whitelist.isEmpty() && !name.matches(sub.whitelist)) continue;
            // 黑名单
            if (!sub.blacklist.isEmpty() && name.matches(sub.blacklist)) continue;
            // 复制到 scripts 目录
            try {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                ScriptStore.write(ctx, name, content);
                log.append("复制: ").append(name).append("\n");
                count++;
                // 解析变量声明 → 自动添加到 EnvStore
                org.json.JSONArray vars = ScriptStore.parseVars(content);
                if (vars.length() > 0) {
                    java.util.List<EnvStore.Env> envs = EnvStore.load(ctx);
                    boolean changed = false;
                    for (int i = 0; i < vars.length(); i++) {
                        org.json.JSONObject vo = vars.optJSONObject(i);
                        String key = vo.optString("key", "");
                        if (key.isEmpty()) continue;
                        boolean found = false;
                        for (EnvStore.Env e : envs) {
                            if (e.name.equals(key)) { found = true; break; }
                        }
                        if (!found) {
                            envs.add(new EnvStore.Env(key, ""));
                            changed = true;
                            log.append("添加变量: ").append(key).append("\n");
                        }
                    }
                    if (changed) EnvStore.save(ctx, envs);
                }
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        return count;
    }

    /** 执行命令并收集输出 */
    private void execCmd(String cmd, StringBuilder log) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) log.append(l).append("\n");
            r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream(), "UTF-8"));
            while ((l = r.readLine()) != null) log.append("! ").append(l).append("\n");
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) { log.append("! 执行失败: ").append(e.getMessage()).append("\n"); }
    }

    /** 保存单个订阅 */
    private void saveSub(SubscriptionStore.Subscription sub) {
        List<SubscriptionStore.Subscription> all = SubscriptionStore.load(this);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(sub.id)) { all.set(i, sub); found = true; break; }
        }
        if (!found) all.add(sub);
        SubscriptionStore.save(this, all);
    }

    // ================= 环境变量 =================
    private void renderEnvs() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(16));
        ScrollView sc = new ScrollView(this);
        sc.addView(page);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("环境变量 (执行脚本时自动注入 export)");
        hint.setTextColor(MdTheme.onSurfaceVariant(this));
        hint.setTextSize(13);
        page.addView(hint);

        final List<EnvStore.Env> envs = EnvStore.load(this);
        if (envs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无环境变量\n点右下角 ＋ 添加");
            empty.setTextColor(MdTheme.onSurfaceVariant(this));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(20));
            page.addView(empty);
        }
        for (int i = 0; i < envs.size(); i++) {
            final int idx = i;
            final EnvStore.Env e = envs.get(i);
            MdCard card = new MdCard(this, MdCard.OUTLINED, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(8);
            card.setLayoutParams(cp);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            card.addView(row);
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextColor(MdTheme.onSurface(this));
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);
            TextView val = new TextView(this);
            String v = e.value.length() > 40 ? e.value.substring(0, 40) + "..." : e.value;
            val.setText(v.isEmpty() ? "(空)" : v);
            val.setTextColor(MdTheme.onSurfaceVariant(this));
            val.setTextSize(11);
            val.setPadding(0, dp(4), 0, 0);
            row.addView(val);
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(6), 0, 0);
            row.addView(ops);
            ops.addView(smallBtn("编辑", false, new Runnable() {
                public void run() { editEnv(idx); }
            }));
            ops.addView(smallBtn("复制值", false, new Runnable() {
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("env", e.value));
                    MdSnackbar.show(root, "已复制 " + e.name);
                }
            }));
            ops.addView(smallBtn("删除", true, new Runnable() {
                public void run() {
                    final MdDialog d = new MdDialog(AdvActivity.this);
                    d.title("删除变量");
                    d.message("确定删除 " + e.name + " ?");
                    d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                    d.actionPrimary("删除", new Runnable() {
                        public void run() {
                            envs.remove(idx);
                            EnvStore.save(AdvActivity.this, envs);
                            d.dismiss();
                            renderEnvs();
                        }
                    });
                    d.show();
                }
            }));
            page.addView(card);
        }
        // 底部留白 (给 FAB 让位)
        TextView pad = new TextView(this);
        pad.setHeight(dp(72));
        page.addView(pad);
    }

    private void addEnv() {
        final MdDialog d = new MdDialog(this);
        d.title("添加环境变量");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "变量名 (如 JD_COOKIE)", false);
        final MdTextField val = new MdTextField(this, "变量值", true);
        form.addView(name);
        form.addView(val);
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("添加", new Runnable() {
            public void run() {
                String n = name.getText().trim();
                String v = val.getText();
                if (n.isEmpty()) { MdSnackbar.show(root, "变量名不能为空"); return; }
                List<EnvStore.Env> envs = EnvStore.load(AdvActivity.this);
                for (EnvStore.Env e : envs) {
                    if (e.name.equals(n)) { MdSnackbar.show(root, "变量已存在"); return; }
                }
                envs.add(new EnvStore.Env(n, v));
                EnvStore.save(AdvActivity.this, envs);
                d.dismiss();
                renderEnvs();
            }
        });
        d.show();
    }

    private void editEnv(final int idx) {
        List<EnvStore.Env> envs = EnvStore.load(this);
        final EnvStore.Env e = envs.get(idx);
        final MdDialog d = new MdDialog(this);
        d.title("编辑变量");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField name = new MdTextField(this, "变量名", false);
        name.setText(e.name);
        final MdTextField val = new MdTextField(this, "变量值", true);
        val.setText(e.value);
        form.addView(name);
        form.addView(val);
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                List<EnvStore.Env> envs = EnvStore.load(AdvActivity.this);
                EnvStore.Env target = envs.get(idx);
                target.name = name.getText().trim();
                target.value = val.getText();
                EnvStore.save(AdvActivity.this, envs);
                d.dismiss();
                renderEnvs();
            }
        });
        d.show();
    }

    // ================= 沉浸式终端 =================
    private void renderTerminal() {
        // 纯黑全屏, 去掉所有浅色边距
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF000000);
        contentWrap.addView(page, new LinearLayout.LayoutParams(-1, -1));

        // 终端主体 (填满可用空间)
        final TerminalView term = new TerminalView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, 0, 1);
        page.addView(term, tlp);

        // 右上角 ⋮ 菜单 (复制/清屏/中断)
        final View menuBtn = new View(this);
        {
            // 用文字 ⋮
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.END);
            topBar.setPadding(0, dp(4), dp(8), 0);
            page.addView(topBar);
            TextView menu = new TextView(this);
            menu.setText("⋮");
            menu.setTextSize(20);
            menu.setTextColor(0xFF888888);
            menu.setGravity(Gravity.CENTER);
            menu.setPadding(dp(8), dp(2), dp(8), dp(2));
            menu.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final MdDialog m = new MdDialog(AdvActivity.this);
                    m.title("终端操作");
                    LinearLayout ml = new LinearLayout(AdvActivity.this);
                    ml.setOrientation(LinearLayout.VERTICAL);
                    String[][] items = {
                        {"复制输出", "content_copy"},
                        {"清屏", "backspace"},
                        {"中断运行", "stop"},
                        {"字体大小", "text_fields"},
                        {"快捷命令", "keyboard"},
                    };
                    for (String[] item : items) {
                        TextView tv = new TextView(AdvActivity.this);
                        tv.setText(item[0]);
                        tv.setTextSize(15);
                        tv.setTextColor(MdTheme.onSurface(AdvActivity.this));
                        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
                        tv.setClickable(true);
                        tv.setBackground(new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
                        tv.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v2) {
                                m.dismiss();
                                String label = tv.getText().toString();
                                if (label.equals("复制输出")) {
                                    String txt = term.getFullText();
                                    if (txt.isEmpty()) { MdSnackbar.show(root, "暂无输出"); return; }
                                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("terminal", txt));
                                    MdSnackbar.show(root, "已复制 " + txt.length() + " 字符");
                                } else if (label.equals("清屏")) {
                                    term.execute("clear");
                                } else if (label.equals("中断运行")) {
                                    term.interrupt();
                                } else if (label.equals("字体大小")) {
                                    showTermFontSize(term);
                                } else if (label.equals("快捷命令")) {
                                    showQuickCmds(term);
                                }
                            }
                        });
                        ml.addView(tv);
                    }
                    m.content(ml);
                    m.action("关闭", new Runnable() { public void run() { m.dismiss(); } });
                    m.show();
                }
            });
            topBar.addView(menu);
        }

        // 底部 Termux 式 extra-keys: 深色键帽
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER);
        keyRow.setPadding(dp(4), dp(4), dp(4), dp(6));
        keyRow.setBackgroundColor(0xFF000000);
        page.addView(keyRow);

        String[] keys = {"ESC", "TAB", "CTRL", "ALT", "↑", "↓", "←", "→", "↵"};
        int[] keyColors = {0xFF35565B, 0xFF35565B, 0xFF35565B, 0xFF35565B,
                           0xFF26A69A, 0xFF26A69A, 0xFF26A69A, 0xFF26A69A, 0xFF26A69A};
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            final int bgC = keyColors[i];
            TextView key = new TextView(this);
            key.setText(k);
            key.setTextSize(11);
            key.setTypeface(Typeface.MONOSPACE);
            key.setTextColor(0xFFD6E9EA);
            key.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable kg = new android.graphics.drawable.GradientDrawable();
            kg.setColor(bgC);
            kg.setCornerRadius(dp(6));
            kg.setStroke(dp(1), 0xFF52777C);
            key.setBackground(kg);
            key.setPadding(dp(6), dp(8), dp(6), dp(8));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, dp(36));
            klp.weight = 1;
            klp.leftMargin = dp(2);
            klp.rightMargin = dp(2);
            key.setLayoutParams(klp);
            key.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (k.equals("↵")) {
                        term.inputChar('\n');
                    } else if (k.equals("↑")) {
                        term.historyNav(-1);
                    } else if (k.equals("↓")) {
                        term.historyNav(1);
                    } else if (k.equals("←")) {
                        // 无插入光标位置管理, 忽略
                    } else if (k.equals("→")) {
                        // 忽略
                    } else if (k.equals("ESC") || k.equals("TAB") || k.equals("CTRL") || k.equals("ALT")) {
                        // 发送按键给终端? 先忽略, 可以扩展
                    }
                }
            });
            keyRow.addView(key);
        }
    }

    /** 快捷命令弹层 (保留之前设计, 走深色) */
    private void showQuickCmds(final TerminalView term) {
        final MdDialog d = new MdDialog(AdvActivity.this);
        d.title("快捷命令");
        LinearLayout box = new LinearLayout(AdvActivity.this);
        box.setOrientation(LinearLayout.VERTICAL);
        String[][] groups = {
                {"Python", "py3 -V", "pip -V", "pip list", "自检"},
                {"常用", "ls scripts", "pwd", "date", "echo $PATH"},
                {"系统", "busybox df -h", "busybox free", "busybox ps", "busybox uname -a"},
                {"网络", "wget baidu", "pip install requests", "env", "py3 -c"},
        };
        for (String[] g : groups) {
            TextView gt = new TextView(AdvActivity.this);
            gt.setText(g[0]);
            gt.setTextSize(11);
            gt.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
            gt.setTypeface(Typeface.DEFAULT_BOLD);
            gt.setPadding(dp(2), dp(8), dp(2), dp(2));
            box.addView(gt);
            LinearLayout row = new LinearLayout(AdvActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 1; i < g.length; i++) {
                final String label = g[i];
                final String cmd = g[i].equals("自检") ? "python3 --version && python3 -m pip --version"
                        : g[i].equals("ls scripts") ? "ls -la " + ScriptStore.dir(AdvActivity.this).getAbsolutePath()
                        : g[i].equals("py3 -V") ? "python3 -V"
                        : g[i].equals("pip -V") ? "python3 -m pip --version"
                        : g[i].equals("pip list") ? "python3 -m pip list"
                        : g[i].equals("py3 -c") ? "python3 -c \\\"import sys; print(sys.version)\\\""
                        : g[i];
                TextView b = new TextView(AdvActivity.this);
                b.setText(label);
                b.setTextSize(10.5f);
                b.setSingleLine(true);
                b.setTextColor(0xFF26A69A);
                b.setGravity(Gravity.CENTER);
                b.setPadding(dp(4), dp(7), dp(4), dp(7));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFF1A2A2D);
                bg.setCornerRadius(dp(14));
                b.setBackground(bg);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2);
                blp.weight = 1;
                blp.rightMargin = dp(5);
                b.setLayoutParams(blp);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        term.execute(cmd);
                        d.dismiss();
                    }
                });
                row.addView(b);
            }
            box.addView(row);
        }
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    /** 终端字体大小调节 (9-24sp, 持久化) */
    private void showTermFontSize(final TerminalView term) {
        final android.content.SharedPreferences sp = getSharedPreferences("term_font", MODE_PRIVATE);
        final float[] cur = {sp.getFloat("size", 0f)};
        if (cur[0] < 9f) cur[0] = term.getFontSize() <= 0 ? 13f : term.getFontSize();
        final MdDialog d = new MdDialog(AdvActivity.this);
        d.title("终端字体大小");
        LinearLayout box = new LinearLayout(AdvActivity.this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView sizeTv = new TextView(AdvActivity.this);
        sizeTv.setText(String.format("%.0f sp", cur[0]));
        sizeTv.setTextSize(18);
        sizeTv.setTypeface(Typeface.DEFAULT_BOLD);
        sizeTv.setTextColor(MdTheme.onSurface(AdvActivity.this));
        sizeTv.setGravity(Gravity.CENTER);
        sizeTv.setPadding(0, dp(4), 0, dp(12));
        box.addView(sizeTv);
        LinearLayout row = new LinearLayout(AdvActivity.this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView minus = new TextView(AdvActivity.this);
        minus.setText("－");
        minus.setTextSize(24);
        minus.setTextColor(MdTheme.onSurface(AdvActivity.this));
        minus.setGravity(Gravity.CENTER);
        minus.setPadding(dp(20), dp(4), dp(20), dp(4));
        minus.setClickable(true);
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (cur[0] > 9f) {
                    cur[0] -= 1f;
                    applyTermFont(term, sp, cur[0]);
                    sizeTv.setText(String.format("%.0f sp", cur[0]));
                }
            }
        });
        TextView plus = new TextView(AdvActivity.this);
        plus.setText("＋");
        plus.setTextSize(24);
        plus.setTextColor(MdTheme.onSurface(AdvActivity.this));
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(dp(20), dp(4), dp(20), dp(4));
        plus.setClickable(true);
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (cur[0] < 24f) {
                    cur[0] += 1f;
                    applyTermFont(term, sp, cur[0]);
                    sizeTv.setText(String.format("%.0f sp", cur[0]));
                }
            }
        });
        row.addView(minus, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(plus, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    /** 应用终端字号并持久化 */
    private void applyTermFont(final TerminalView term, final android.content.SharedPreferences sp, float size) {
        term.setFontSize(size);
        sp.edit().putFloat("size", size).apply();
    }
    private TextView toolChip(String text, boolean danger, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(danger ? MdTheme.error(this) : MdTheme.primary(this));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(danger ? (MdTheme.isDark(this) ? 0x33CF6679 : 0x14CF6679)
                : (MdTheme.isDark(this) ? 0x33D0BCFF : 0x14D0BCFF));
        bg.setCornerRadius(dp(16));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2);
        lp.weight = 1;
        lp.rightMargin = dp(6);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return v;
    }

    // ================= 工具 =================
    private TextView smallBtn(String text, boolean danger, final Runnable action) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(danger ? MdTheme.error(this) : MdTheme.primary(this));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(6), dp(8), dp(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        int fill = danger ? (MdTheme.isDark(this) ? 0x33CF6679 : 0x14CF6679)
                           : (MdTheme.isDark(this) ? 0x33D0BCFF : 0x14D0BCFF);
        bg.setColor(fill);
        bg.setCornerRadius(dp(14));
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(MdTheme.primary(this) & 0x2AFFFFFF),
                bg, null);
        v.setBackground(ripple);
        v.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2);
        lp.weight = 1;
        lp.rightMargin = dp(6);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return v;
    }


    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 占位空白 */
    public static class Spacer extends android.view.View {
        public Spacer(Context c, int w, int h) {
            super(c);
            setLayoutParams(new ViewGroup.LayoutParams(w, h));
        }
    }

    // ================= 高级任务存储 =================
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importScriptFromUri(data.getData());
        }
        if (requestCode == REQ_UPLOAD_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            fillUploadFromUri(data.getData());
        }
        if (requestCode == REQ_BACKUP_SAVE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            writeBackupToUri(data.getData());
        }
        if (requestCode == REQ_BACKUP_RESTORE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            doRestore(data.getData());
        }
    }

    /** 文件选择结果填入上传表单 */
    private void fillUploadFromUri(android.net.Uri uri) {
        String text = readUriText(uri);
        if (text == null) {
            MdSnackbar.show(root, "读取文件失败");
            return;
        }
        String fname = uriFileName(uri);
        if (upContent != null) upContent.setText(text);
        if (upName != null && upName.getText().toString().trim().isEmpty() && !fname.isEmpty()) {
            upName.setText(fname);
        }
        if (upType != null && upType.getText().toString().trim().isEmpty()) {
            int dot = fname.lastIndexOf('.');
            if (dot >= 0) {
                String ext = fname.substring(dot + 1).toLowerCase();
                if (ext.equals("py") || ext.equals("js") || ext.equals("sh")) upType.setText(ext);
            }
        }
        MdSnackbar.show(root, "已读取: " + fname + " (" + text.length() + " 字符)");
    }

    /** 读取 Uri 文本内容 */
    private String readUriText(android.net.Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    /** 从 Uri 取显示文件名 */
    private String uriFileName(android.net.Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver().query(
                    uri, new String[]{"_display_name"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        if (name == null || name.isEmpty()) {
            String p = uri.getLastPathSegment();
            name = p == null ? "" : p.substring(p.lastIndexOf('/') + 1);
        }
        return name == null ? "" : name;
    }

    private void loadAdvTasks() {
        advTasks.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString("tasks", null);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) advTasks.add(arr.getJSONObject(i));
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
    }

    private void saveAdvTasks() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : advTasks) arr.put(o);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tasks", arr.toString()).apply();
    }

    // ===== 脚本间隔调度 (每隔几分钟/几小时/几天) =====
    private static final String SCHED_PREFS = "script_sched";

    public static JSONObject getSched(Context ctx) {
        String raw = ctx.getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE).getString("sched", "{}");
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    public static void saveSched(Context ctx, JSONObject o) {
        ctx.getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE).edit().putString("sched", o.toString()).apply();
    }

    /** 描述如 "每 6 小时" / "每 30 分钟" / 空串表示未定时 */
    public static String schedDesc(Context ctx, String name) {
        JSONObject cfg = getSched(ctx).optJSONObject(name);
        if (cfg == null || !cfg.optBoolean("enabled", true)) return "";
        int every = cfg.optInt("every", 0);
        if (every <= 0) return "";
        String unit = cfg.optString("unit", "min");
        String u = unit.equals("hour") ? "小时" : unit.equals("day") ? "天" : "分钟";
        return "每 " + every + " " + u;
    }

    /** 供 CronAlarmReceiver 调用: 检查并触发到点的 cron 任务 */
    public static void checkCron(Context ctx, Calendar now) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("tasks", null);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optBoolean("enabled", true)) continue;
                String cron = o.optString("cron", "");
                if (cron.isEmpty()) continue;
                if (CronParser.matches(cron, now)) {
                    String name = o.optString("name", "?");
                    String script = o.optString("script", "");
                    TaskLog.append(ctx, "[cron] " + name, "触发, 脚本: " + script);
                    // 触发执行: 走前台服务, 豁免后台网络限制 (App Standby 会拒绝后台进程 DNS)
                    startExec(ctx, script, name, -1);
                }
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        // 间隔调度: 每隔 N 分钟/小时/天 执行脚本
        try {
            long nowMs = System.currentTimeMillis();
            JSONObject sched = getSched(ctx);
            JSONArray names = sched.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String sname = names.optString(i);
                    JSONObject cfg = sched.optJSONObject(sname);
                    if (cfg == null || !cfg.optBoolean("enabled", true)) continue;
                    int every = cfg.optInt("every", 0);
                    if (every <= 0) continue;
                    long intervalMs = "hour".equals(cfg.optString("unit"))
                            ? every * 3600000L
                            : "day".equals(cfg.optString("unit")) ? every * 86400000L : every * 60000L;
                    long last = cfg.optLong("last", 0);
                    if (nowMs - last >= intervalMs) {
                        cfg.put("last", nowMs);   // 先更新再执行, 防每分钟轮询重复触发
                        sched.put(sname, cfg);
                        saveSched(ctx, sched);
                        TaskLog.append(ctx, "[定时] " + sname, "间隔到期(" + schedDesc(ctx, sname) + "), 开始执行");
                        startExec(ctx, sname, "[定时] " + sname, 0);
                    }
                }
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
    }

    /** 后台执行脚本: 启动前台服务 (豁免 App Standby 网络限制), 服务内执行完自动停止 */
    static void startExec(Context ctx, String scriptName, String tag, int attempt) {
        Intent i = new Intent(ctx, ExecService.class);
        i.putExtra("scriptName", scriptName);
        i.putExtra("tag", tag);
        i.putExtra("attempt", attempt);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            // 兜底: 直接执行 (部分 ROM 限制前台服务启动)
            runScriptByName(ctx, scriptName, tag, attempt);
        }
    }

    public static void runScriptByName(final Context ctx, final String scriptName, final String tag) {
        runScriptByName(ctx, scriptName, tag, -1, null);
    }

    /** attempt: 0=首次执行; 1..6=失败重试档位 (间隔 15分→8小时递增, 覆盖夜间长断网窗口) */
    public static void runScriptByName(final Context ctx, final String scriptName, final String tag, final int attempt) {
        runScriptByName(ctx, scriptName, tag, attempt, null);
    }

    /** onDone: 执行结束(无论成败)回调, 供 ExecService 停止自己 */
    public static void runScriptByName(final Context ctx, final String scriptName, final String tag,
                                       final int attempt, final Runnable onDone) {
        if (scriptName == null || ScriptStore.isMetaFile(scriptName)) {
            TaskLog.append(ctx, "[cron] " + tag, "配置类文件, 跳过执行: " + scriptName);
            if (onDone != null) { try { onDone.run(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} } }
            return;
        }
        final String content = ScriptStore.read(ctx, scriptName);
        if (content.isEmpty()) {
            TaskLog.append(ctx, "[cron] " + tag, "脚本不存在: " + scriptName);
            if (onDone != null) { try { onDone.run(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} } }
            return;
        }
        final String type = ScriptStore.typeOf(scriptName);
        new Thread(new Runnable() {
            public void run() {
                try {
                // 执行期间持 Wi-Fi 锁, 防止执行中 Wi-Fi 休眠导致断网
                WifiManager.WifiLock wl = null;
                try {
                    WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "taskpro:net");
                        wl.setReferenceCounted(false);
                        wl.acquire();
                    }
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    // 确保运行时就绪 (首次可能需解压)
                    if (!RuntimeManager.isReady(ctx)) {
                        boolean ok = RuntimeManager.ensureReady(ctx, null);
                        if (!ok) {
                            TaskLog.append(ctx, "[cron] " + tag, "运行时未就绪, 无法执行");
                            return;
                        }
                    }
                final StringBuilder out = new StringBuilder();
                // 记录运行开始时间: 用于识别本次新增产物 (自动导出只导新文件)
                final long runStart = System.currentTimeMillis();
                try {
                    String scriptCmd = buildScriptCommand(ctx, scriptName, content, type);
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                            RuntimeManager.buildCommand(ctx, scriptCmd));
                    pb.directory(new java.io.File(ctx.getFilesDir(), ""));
                    // 注入脚本参数配置 (脚本头注释声明的变量, 用户安装时填写; 执行时注入为环境变量)
                    try {
                        java.util.Map<String, String> conf = ScriptStore.confOf(ctx, scriptName);
                        if (conf != null && !conf.isEmpty()) pb.environment().putAll(conf);
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    Process p = pb.start();
                    ScriptRunner.markRunning(scriptName);              // 后台执行也标记运行中
                    ScriptRunner.attachProcess(scriptName, p);         // 关联进程
                    ScriptRunner.sweep();
                    Thread rt1 = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                                String l; while ((l = r.readLine()) != null) out.append(l).append("\n");
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    Thread rt2 = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                                String l; while ((l = r.readLine()) != null) out.append("! ").append(l).append("\n");
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    rt1.start(); rt2.start();
                    int code;
                    // 脚本超时: 默认 180s, 可在脚本库「编辑」里按脚本自定义
                    long toSec = 180L;
                    try { toSec = ScriptStore.getTimeout(ctx, scriptName); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    if (toSec < 1) toSec = 180L;
                    try {
                        if (!p.waitFor(toSec, java.util.concurrent.TimeUnit.SECONDS)) {
                            p.destroy();
                            out.append("! 超时(" + toSec + "s), 已终止\n");
                            code = 124;
                        } else {
                            code = p.exitValue();
                        }
                    } catch (InterruptedException ie) {
                        p.destroy();
                        code = 124;
                    }
                    rt1.join(2000); rt2.join(2000);
                    ScriptRunner.markDone(scriptName);
                    TaskLog.append(ctx, "[cron] " + tag, "退出码 " + code + "\n" + out.toString());
                    // 执行统计 (30 天成功率)
                    Stats.record(ctx, code == 0);
                    if (code != 0) {
                        if (attempt >= 0 && attempt < 6) {
                            // 失败自动重试: 夜间断网窗口可能长达数小时(宿舍断网/校园网会话过期), 间隔递增
                            scheduleCronRetry(ctx, scriptName, tag, attempt + 1);
                            TaskLog.append(ctx, "[cron] " + tag,
                                    "执行失败, " + CRON_RETRY_DELAYS[attempt] + " 分钟后第 " + (attempt + 1) + " 次重试"
                                            + " | " + netDiag(ctx));
                        } else {
                            // 最终失败: 通知并附输出尾部, 便于快速定位
                            Notifier.postOutput(ctx, tag + " 执行失败", out.toString(), code);
                        }
                    } else {
                        // 成功: 把脚本输出摘要推送到通知栏 (按设置, 默认开)
                        if (Settings.notifyScriptCron(ctx)) {
                            Notifier.postOutput(ctx, tag + " 执行完成", out.toString(), code);
                        }
                        // 自动导出产物 (按设置, 默认开): 只导本次新增, 后台静默复制到 Download/<脚本名>/
                        try {
                            if (Settings.autoExportArtifacts(ctx)) {
                                java.util.List<ProdFile> all = scanArtifacts(ctx, 50);
                                java.util.List<ProdFile> fresh = new java.util.ArrayList<ProdFile>();
                                for (ProdFile pf : all) {
                                    if (pf.mtime >= runStart - 120000L) fresh.add(pf);
                                }
                                if (!fresh.isEmpty()) {
                                    final String sub = safeDirName(tag);
                                    new Thread(new Runnable() {
                                        public void run() {
                                            int n = exportToDownload(ctx, fresh, sub);
                                            TaskLog.append(ctx, "[产物] " + tag,
                                                    "自动导出 " + n + " 个文件 → Download/" + sub + "/");
                                        }
                                    }).start();
                                }
                            }
                        } catch (Throwable ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                } catch (Exception e) {
                    TaskLog.append(ctx, "[cron] " + tag, "异常: " + e.toString());
                    Stats.record(ctx, false);
                } finally {
                    try { if (wl != null) wl.release(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
                } finally {
                    if (onDone != null) { try { onDone.run(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} } }
                }
            }
        }).start();
    }

    /** 重试间隔 (分钟): 第 1..6 次重试的等待时间, 递增覆盖夜间长断网窗口 */
    static final int[] CRON_RETRY_DELAYS = {15, 30, 60, 120, 240, 480};

    /** cron 脚本失败重试: 一次性闹钟 (数据 URI 区分, 无碰撞) */
    static void scheduleCronRetry(Context ctx, String scriptName, String tag, int attempt) {
        if (attempt < 1 || attempt > 6) return;
        int delayMin = CRON_RETRY_DELAYS[attempt - 1];
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, CronAlarmReceiver.class);
        i.setAction("io.taskpro.CRON_RETRY");
        i.setData(android.net.Uri.parse("taskpro://cronretry/" + scriptName + "/" + attempt));
        i.putExtra("scriptName", scriptName);
        i.putExtra("tag", tag);
        i.putExtra("attempt", attempt);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1002, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMin * 60000L, pi);
        } catch (SecurityException ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 网络诊断 (失败日志附带, 用于区分断网/脚本错误): Wi-Fi 状态/网络状态/外网连通性 */
    static String netDiag(Context ctx) {
        String wifi = "未知";
        String net = "未知";
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()
                    && wm.getConnectionInfo() != null && wm.getConnectionInfo().getSSID() != null) {
                wifi = "已连接(" + wm.getConnectionInfo().getSSID() + ")";
            } else {
                wifi = "未连接";
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            net = (ni != null && ni.isConnected()) ? "已连接" : "断开";
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return "Wi-Fi:" + wifi + " 网络:" + net + " 外网:" + (networkOk() ? "可达" : "不可达");
    }

    /** 等待网络恢复: 连接 connectivitycheck.gstatic.com:443, 最多 maxSec 秒 */
    static void waitNetwork(int maxSec) {
        long end = System.currentTimeMillis() + maxSec * 1000L;
        while (System.currentTimeMillis() < end) {
            if (networkOk()) return;
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    static boolean networkOk() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("connectivitycheck.gstatic.com", 443), 3000);
            s.close();
            return true;
        } catch (Exception e) { return false; }
    }

    // ================= 以下方法从基础模式移植 =================

    private int red() { return MdTheme.isDark(this) ? 0xFFF87171 : 0xFFDC2626; }
    private int green() { return MdTheme.isDark(this) ? 0xFF4ADE80 : 0xFF16A34A; }

    /** 分割线 */
    private View line() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(MdTheme.outlineVariant(this));
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    /** 成功通知开关行 */
    private View notifySwitchRow() {
        LinearLayout rowIn = new LinearLayout(this);
        rowIn.setOrientation(LinearLayout.HORIZONTAL);
        rowIn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        rlp.bottomMargin = dp(4);
        rowIn.setLayoutParams(rlp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        rowIn.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
                boolean nv = !Settings.notifyOnSuccess(AdvActivity.this);
                Settings.setNotifyOnSuccess(AdvActivity.this, nv);
                openMore(MORE_NONE);
            }
        });
        rowIn.addView(swV);
        return rowIn;
    }

    /** 脚本定时结果通知开关行 */
    private View scriptCronSwitchRow() {
        LinearLayout rowIn = new LinearLayout(this);
        rowIn.setOrientation(LinearLayout.HORIZONTAL);
        rowIn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        rlp.bottomMargin = dp(4);
        rowIn.setLayoutParams(rlp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        rowIn.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t1 = new TextView(this);
        t1.setText("脚本定时结果通知");
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(14);
        txt.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("脚本作为定时任务执行完后, 把输出摘要推送到通知栏");
        t2.setTextColor(MdTheme.onSurfaceVariant(this));
        t2.setTextSize(11);
        txt.addView(t2);
        final TextView swV = new TextView(this);
        final boolean on = Settings.notifyScriptCron(this);
        swV.setText(on ? "● 开" : "○ 关");
        swV.setTextColor(on ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        swV.setTextSize(13);
        swV.setPadding(dp(8), dp(4), dp(8), dp(4));
        swV.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !Settings.notifyScriptCron(AdvActivity.this);
                Settings.setNotifyScriptCron(AdvActivity.this, nv);
                openMore(MORE_NONE);
            }
        });
        rowIn.addView(swV);
        return rowIn;
    }

    /** 自动导出开关行 */
    private View autoExportSwitchRow() {
        LinearLayout rowIn = new LinearLayout(this);
        rowIn.setOrientation(LinearLayout.HORIZONTAL);
        rowIn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        rlp.bottomMargin = dp(4);
        rowIn.setLayoutParams(rlp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        rowIn.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t1 = new TextView(this);
        t1.setText("运行后自动导出到 Download");
        t1.setTextColor(MdTheme.onSurface(this));
        t1.setTextSize(14);
        txt.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("开启后, 脚本每次运行完产物自动复制到 Download/脚本名/");
        t2.setTextColor(MdTheme.onSurfaceVariant(this));
        t2.setTextSize(11);
        txt.addView(t2);
        final TextView swV = new TextView(this);
        final boolean on = Settings.autoExportArtifacts(this);
        swV.setText(on ? "● 开" : "○ 关");
        swV.setTextColor(on ? MdTheme.primary(this) : MdTheme.onSurfaceVariant(this));
        swV.setTextSize(13);
        swV.setPadding(dp(8), dp(4), dp(8), dp(4));
        swV.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !Settings.autoExportArtifacts(AdvActivity.this);
                Settings.setAutoExportArtifacts(AdvActivity.this, nv);
                openMore(MORE_NONE);
            }
        });
        rowIn.addView(swV);
        return rowIn;
    }

    /** 后台常驻引导 */
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

    /** 电池优化白名单 */
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

    /** 脚本开发文档 (整页 Markdown 渲染) */
    private void renderScriptDocPage() {
        // 顶部操作条: 一键复制全文 (方便粘贴给 AI 辅助写脚本)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView copyBtn = smallBtn("一键复制文档", false, new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("TaskPro 脚本开发文档", ScriptDoc.DOC));
                    MdSnackbar.show(root, "已复制全文, 可直接粘贴给 AI 帮写脚本");
                } catch (Exception e) {
                    MdSnackbar.show(root, "复制失败");
                }
            }
        });
        copyBtn.setText("一键复制文档");
        topBar.addView(copyBtn);
        contentWrap.addView(topBar);
        ScrollView sc = new ScrollView(this);
        ScriptDocView docView = new ScriptDocView(this, ScriptDoc.DOC);
        sc.addView(docView);
        contentWrap.addView(sc, new LinearLayout.LayoutParams(-1, -1));
    }

    /** 导入任务 */
    private void showImportDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("导入任务");
        final MdTextField txt = new MdTextField(this, "粘贴其他人分享的任务文本", true);
        d.content(txt);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("导入", new Runnable() {
            public void run() {
                List<Task> added = TaskStore.importTasks(AdvActivity.this, txt.getText());
                if (added.isEmpty()) {
                    MdSnackbar.show(root, "没有解析到任务, 请检查文本");
                } else {
                    MdSnackbar.show(root, "成功导入 " + added.size() + " 个任务");
                    d.dismiss();
                }
            }
        });
        d.show();
    }

    /** 导出任务 */
    private void exportTasks() {
        List<Task> tasks = TaskStore.load(this);
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

    /** 导出日志 */
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

    /** 执行统计 */
    /** 主题色选择器 */
    private void showAccentPicker() {
        final int[] colors = {
            0xFF6750A4,  // 默认紫
            0xFF2A6FDB,  // 蓝
            0xFF2E9E5B,  // 绿
            0xFFF57C00,  // 橙
            0xFFE91E63,  // 粉
            0xFF00ACC1,  // 青
        };
        final String[] names = {"默认紫", "海蓝", "翠绿", "暖橙", "粉红", "青色"};
        final MdDialog d = new MdDialog(this);
        d.title("选择主题色");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(Gravity.CENTER);
        grid.setPadding(0, dp(8), 0, dp(4));
        for (int i = 0; i < colors.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), 0, dp(4), 0);
            TextView sw = new TextView(this);
            sw.setText("●");
            sw.setTextColor(colors[i]);
            sw.setTextSize(32);
            sw.setGravity(Gravity.CENTER);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    MdTheme.setAccent(AdvActivity.this, colors[idx]);
                    d.dismiss();
                    MdSnackbar.show(root, "主题色已切换为 " + names[idx]);
                    // 重建界面
                    switchTab(currentTab);
                }
            });
            cell.addView(sw);
            TextView nm = new TextView(this);
            nm.setText(names[i]);
            nm.setTextColor(MdTheme.onSurfaceVariant(this));
            nm.setTextSize(10);
            nm.setGravity(Gravity.CENTER);
            cell.addView(nm);
            grid.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        d.content(grid);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }


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
                    if (i % 5 == 0) {
                        p.setColor(MdTheme.onSurfaceVariant(AdvActivity.this));
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
                p.setColor(MdTheme.onSurface(AdvActivity.this));
                p.setTextSize(dp(11));
                cv.drawText("成功", dp(18), y + dp(4), p);
                p.setColor(red());
                cv.drawRect(dp(60), y - dp(5), dp(70), y + dp(5), p);
                p.setColor(MdTheme.onSurface(AdvActivity.this));
                cv.drawText("失败", dp(74), y + dp(4), p);
            }
        });
        d.content(box);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
    }

    /** 错误日志 */
    /** 检查 GitHub 最新发布版更新 */
    private void checkGitHubUpdate() {
        final String curVer = appVersion();
        final MdDialog d = new MdDialog(this);
        d.title("检查更新");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView status = new TextView(this);
        status.setText("正在检查 GitHub 最新版本…");
        status.setTextColor(MdTheme.onSurfaceVariant(this));
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(8));
        box.addView(status);
        d.content(box);
        d.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.URL url = new java.net.URL("https://api.github.com/repos/Qins-zlo/taskpro/releases/latest");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        ui.post(new Runnable() { public void run() { status.setText("无法检查更新 (HTTP " + code + ")"); } });
                        return;
                    }
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    conn.disconnect();
                    org.json.JSONObject releaseJson = new org.json.JSONObject(sb.toString());
                    final String tag = releaseJson.optString("tag_name", "");
                    final String releaseName = releaseJson.optString("name", tag);
                    final String body = releaseJson.optString("body", "");
                    final String htmlUrl = releaseJson.optString("html_url", "https://github.com/Qins-zlo/taskpro/releases");
                    // 获取下载链接 (第一个 APK asset)
                    org.json.JSONArray assets = releaseJson.optJSONArray("assets");
                    final String downloadUrl;
                    if (assets != null && assets.length() > 0) {
                        downloadUrl = assets.optJSONObject(0).optString("browser_download_url", "");
                    } else {
                        downloadUrl = "";
                    }
                    // 当前版本号 (从方法外层 curVer 读取)
                    final String tagVer = tag.startsWith("v") ? tag.substring(1) : tag;
                    final boolean hasNew = compareVersions(tagVer, curVer) > 0;
                    final String verTag = tag.isEmpty() ? "未知" : tag;
                    ui.post(new Runnable() {
                        public void run() {
                            d.dismiss();
                            final MdDialog rd = new MdDialog(AdvActivity.this);
                            rd.title("检查更新");
                            LinearLayout rbox = new LinearLayout(AdvActivity.this);
                            rbox.setOrientation(LinearLayout.VERTICAL);
                            TextView tv = new TextView(AdvActivity.this);
                            if (hasNew) {
                                tv.setText("当前版本: v" + curVer + "\n"
                                        + "最新版本: " + verTag + "\n\n"
                                        + "发现新版本! 建议更新。");
                                tv.setTextColor(0xFFE53935);
                            } else {
                                tv.setText("当前版本: v" + curVer + "\n"
                                        + "最新版本: " + verTag + "\n\n"
                                        + "已是最新版本。");
                                tv.setTextColor(MdTheme.onSurface(AdvActivity.this));
                            }
                            tv.setTextSize(14);
                            tv.setPadding(0, 0, 0, dp(8));
                            rbox.addView(tv);
                            if (!body.isEmpty()) {
                                TextView bodyTv = new TextView(AdvActivity.this);
                                String shortBody = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                                bodyTv.setText(shortBody);
                                bodyTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                bodyTv.setTextSize(11);
                                bodyTv.setTypeface(Typeface.MONOSPACE);
                                bodyTv.setPadding(0, dp(4), 0, dp(6));
                                bodyTv.setMaxLines(8);
                                rbox.addView(bodyTv);
                            }
                            rd.content(rbox);
                            rd.action("关闭", new Runnable() { public void run() { rd.dismiss(); } });
                            if (!downloadUrl.isEmpty()) {
                                rd.actionPrimary("下载", new Runnable() {
                                    public void run() {
                                        rd.dismiss();
                                        // 用浏览器打开下载链接
                                        try {
                                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                                            startActivity(i);
                                        } catch (Exception e) {
                                            MdSnackbar.show(root, "无法打开浏览器: " + e.getMessage());
                                        }
                                    }
                                });
                            }
                            if (hasNew && !htmlUrl.isEmpty()) {
                                rd.action("查看详情", new Runnable() {
                                    public void run() {
                                        try {
                                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl));
                                            startActivity(i);
                                        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                                    }
                                });
                            }
                            rd.show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            d.dismiss();
                            MdSnackbar.show(root, "检查更新失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showCrashLog() {
        java.io.File f = new java.io.File(getFilesDir(), "crash.log");
        String content = "";
        if (f.exists()) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) Math.min(f.length(), 200000)];
                int n = in.read(buf);
                in.close();
                content = new String(buf, 0, Math.max(n, 0), "UTF-8");
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
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

    /** 赞助开发者 */
    /** 数据备份/恢复 对话框 */
    private void showBackupDialog() {
        final MdDialog d = new MdDialog(this);
        d.title("数据备份 / 恢复");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView desc = new TextView(this);
        desc.setText("备份内容包括:\n"
                + "· 高级任务 + 基础任务\n"
                + "· 脚本 (scripts 目录全部文件)\n"
                + "· 环境变量 / AI 配置 / 主题色\n"
                + "· 通知开关 / 脚本调度 / 运行日志 / 统计\n\n"
                + "备份文件为 .taskpro 格式, 可放在任何位置, 换机恢复无忧。");
        desc.setTextColor(MdTheme.onSurfaceVariant(this));
        desc.setTextSize(13);
        desc.setPadding(0, 0, 0, dp(6));
        box.addView(desc);
        d.content(box);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.action("恢复", new Runnable() {
            public void run() {
                d.dismiss();
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_BACKUP_RESTORE); }
                catch (Exception e) { MdSnackbar.show(root, "无法打开文件选择器"); }
            }
        });
        d.actionPrimary("立即备份", new Runnable() {
            public void run() {
                d.dismiss();
                String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
                        .format(new java.util.Date());
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE, "TaskPro备份-" + ts + ".taskpro");
                try { startActivityForResult(i, REQ_BACKUP_SAVE); }
                catch (Exception e) { MdSnackbar.show(root, "无法打开保存选择器"); }
            }
        });
        d.show();
    }

    /** 读取一个 SharedPreferences 的全部内容为 JSON */
    private JSONObject dumpPrefs(String prefsName) {
        JSONObject out = new JSONObject();
        try {
            SharedPreferences sp = getSharedPreferences(prefsName, MODE_PRIVATE);
            java.util.Map<String, ?> all = sp.getAll();
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) out.put(e.getKey(), (String) v);
                else if (v instanceof Integer) out.put(e.getKey(), (Integer) v);
                else if (v instanceof Boolean) out.put(e.getKey(), (Boolean) v);
                else if (v instanceof Long) out.put(e.getKey(), (Long) v);
                else if (v instanceof Float) out.put(e.getKey(), (Float) v);
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return out;
    }

    /** 把 JSON 写回 SharedPreferences */
    private void restorePrefs(String prefsName, JSONObject data) {
        try {
            SharedPreferences.Editor ed = getSharedPreferences(prefsName, MODE_PRIVATE).edit();
            ed.clear();
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = data.opt(k);
                if (v instanceof String) ed.putString(k, (String) v);
                else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                else if (v instanceof Long) ed.putLong(k, (Long) v);
                else if (v instanceof Double) ed.putFloat(k, (float) ((Double) v).doubleValue());
            }
            ed.apply();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 执行备份: 打包所有数据写入 Uri */
    private void writeBackupToUri(final Uri uri) {
        try {
            java.io.OutputStream os = getContentResolver().openOutputStream(uri, "w");
            if (os == null) { MdSnackbar.show(root, "无法写入备份文件"); return; }
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os);
            // 1. manifest
            JSONObject manifest = new JSONObject();
            manifest.put("app", "taskpro");
            manifest.put("version", appVersion());
            manifest.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(new java.util.Date()));
            zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zos.write(manifest.toString(2).getBytes("UTF-8"));
            zos.closeEntry();
            // 2. 各 SharedPreferences
            String[] prefsNames = {
                "adv_tasks", "taskrun_store", "env_store", "ai_config",
                "taskrun_settings", "md_theme_accent", "script_sched",
                "taskrun_log", "upload"
            };
            for (String pn : prefsNames) {
                JSONObject o = dumpPrefs(pn);
                if (o.length() == 0) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry("prefs/" + pn + ".json"));
                zos.write(o.toString(2).getBytes("UTF-8"));
                zos.closeEntry();
            }
            // 3. scripts 目录
            File sdir = ScriptStore.dir(this);
            File[] files = sdir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    zos.putNextEntry(new java.util.zip.ZipEntry("scripts/" + f.getName()));
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    fis.close();
                    zos.closeEntry();
                }
            }
            // 4. stats.json
            File sf = new File(getFilesDir(), "stats.json");
            if (sf.exists()) {
                zos.putNextEntry(new java.util.zip.ZipEntry("stats.json"));
                java.io.FileInputStream fis = new java.io.FileInputStream(sf);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
            }
            zos.finish();
            zos.close();
            os.close();
            MdSnackbar.show(root, "备份完成 ✓ 全部数据已保存");
        } catch (Exception e) {
            MdSnackbar.show(root, "备份失败: " + e.toString());
        }
    }

    /** 执行恢复: 从备份文件读取并写回 */
    private void doRestore(final Uri uri) {
        final MdDialog prog = new MdDialog(this);
        prog.title("恢复中...");
        TextView tv = new TextView(this);
        tv.setText("正在读取备份文件...");
        tv.setTextColor(MdTheme.onSurface(this));
        prog.content(tv);
        prog.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) { err = "无法读取备份文件"; }
                    else {
                        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(is);
                        java.util.zip.ZipEntry ze;
                        java.util.Map<String, byte[]> entries = new java.util.HashMap<String, byte[]>();
                        byte[] buf = new byte[8192];
                        while ((ze = zis.getNextEntry()) != null) {
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            int n;
                            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                            entries.put(ze.getName(), bos.toByteArray());
                            zis.closeEntry();
                        }
                        zis.close();
                        is.close();
                        // 校验 manifest
                        byte[] mb = entries.get("manifest.json");
                        if (mb == null) {
                            err = "不是有效的 TaskPro 备份文件";
                        } else {
                            final JSONObject manifest = new JSONObject(new String(mb, "UTF-8"));
                            if (!"taskpro".equals(manifest.optString("app"))) {
                                err = "不是 TaskPro 备份文件";
                            } else {
                                // 恢复 prefs
                                for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
                                    String name = e.getKey();
                                    if (name.startsWith("prefs/") && name.endsWith(".json")) {
                                        String pn = name.substring(6, name.length() - 5);
                                        JSONObject o = new JSONObject(new String(e.getValue(), "UTF-8"));
                                        restorePrefs(pn, o);
                                    }
                                }
                                // 恢复 scripts
                                for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
                                    String name = e.getKey();
                                    if (name.startsWith("scripts/")) {
                                        String fn = name.substring(8);
                                        if (fn.isEmpty() || fn.contains("/") || fn.contains("..")) continue;
                                        ScriptStore.write(AdvActivity.this, fn, new String(e.getValue(), "UTF-8"));
                                    }
                                }
                                // 恢复 stats.json
                                byte[] sb = entries.get("stats.json");
                                if (sb != null) {
                                    java.io.FileOutputStream fos = new java.io.FileOutputStream(
                                            new File(getFilesDir(), "stats.json"));
                                    fos.write(sb);
                                    fos.close();
                                }
                                // 恢复完成后重新加载
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        try { loadAdvTasks(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                                        try {
                                            AlarmScheduler.scheduleAll(AdvActivity.this, TaskStore.load(AdvActivity.this));
                                        } catch (Throwable ig) { try { android.util.Log.w("TaskPro","catch: "+ig.getMessage()); } catch(Exception __){} }
                                        MdSnackbar.show(root, "恢复完成 ✓ 来自 " + manifest.optString("time", "备份文件"));
                                    }
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    err = "恢复失败: " + e.toString();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        prog.dismiss();
                        if (ferr != null) MdSnackbar.show(root, ferr);
                        else renderMore();
                    }
                });
            }
        }).start();
    }

    /** 运行时修复对话框: 自检缺失模块 → 一键修复 → 实时日志 */
    private void showRuntimeRepair() {
        final MdDialog d = new MdDialog(this);
        d.title("运行时修复");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView status = new TextView(this);
        status.setText("正在自检 Python 运行时...");
        status.setTextColor(MdTheme.onSurfaceVariant(this));
        status.setTextSize(13);
        box.addView(status);
        d.content(box);
        final MdButton repairBtn = new MdButton(this, "安装 Python 依赖", MdButton.TONAL);
        repairBtn.setIcon(Icons.make(this, Icons.GEAR, MdTheme.primary(this), 18));
        box.addView(repairBtn);
        d.action("关闭", new Runnable() { public void run() { d.dismiss(); } });
        d.show();
        // 子线程自检
        new Thread(new Runnable() {
            public void run() {
                final java.util.List<String> missing = RuntimeManager.verifyPythonExts(AdvActivity.this);
                int soCount = 0;
                try {
                    File[] fs = RuntimeManager.dynloadDir(AdvActivity.this).listFiles();
                    if (fs != null) soCount = fs.length;
                } catch (Exception e) { soCount = 0; }
                final int fsoCount = soCount;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (missing.isEmpty()) {
                            status.setText("✓ Python C 扩展完整 (" + fsoCount + " 个 .so)\n如需安装第三方包, 点下方按钮输入包名 (如 requests)");
                        } else {
                            status.setText("发现 " + missing.size() + " 个 C 扩展缺失:\n" + missing.toString() + "\n点下方按钮安装缺失模块");
                        }
                    }
                });
            }
        }).start();
        repairBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // ── 第一步: 自动修复本地缺失的 C 扩展 (.so 镜像) ──
                // 先复制 nativeLib 里的 .so 到 lib-dynload, 修复 _struct/_ssl 等内置扩展
                repairBtn.setEnabled(false);
                repairBtn.setText("修复中...");
                new Thread(new Runnable() {
                    public void run() {
                        final int copied = RuntimeManager.fixPythonExts(AdvActivity.this);
                        // 修复后再自检一次, 确认缺失清单
                        final java.util.List<String> stillMissing = RuntimeManager.verifyPythonExts(AdvActivity.this);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                repairBtn.setEnabled(true);
                                repairBtn.setText("安装 Python 依赖");
                                String fixMsg = copied > 0
                                        ? ("✓ 已修复 " + copied + " 个 C 扩展 (复制 .so 到 lib-dynload)\n")
                                        : "✓ C 扩展已完整, 无需复制\n";
                                if (!stillMissing.isEmpty()) {
                                    fixMsg += "仍缺失: " + stillMissing.toString() + "\n";
                                }
                                MdSnackbar.show(root, fixMsg.trim());
                                // ── 第二步: 弹包名输入框装第三方包 ──
                                final MdDialog pkgDlg = new MdDialog(AdvActivity.this);
                                pkgDlg.title("安装 Python 依赖");
                                LinearLayout pl = new LinearLayout(AdvActivity.this);
                                pl.setOrientation(LinearLayout.VERTICAL);
                                final EditText pkgEt = new EditText(AdvActivity.this);
                                pkgEt.setHint("包名, 如 requests beautifulsoup4");
                                pkgEt.setTextColor(MdTheme.onSurface(AdvActivity.this));
                                android.graphics.drawable.GradientDrawable pkgBg = new android.graphics.drawable.GradientDrawable();
                                pkgBg.setColor(MdTheme.isDark(AdvActivity.this) ? 0xFF2A2A2E : 0xFFF1F3F5);
                                pkgBg.setCornerRadius(dp(8));
                                pkgEt.setBackground(pkgBg);
                                pkgEt.setPadding(dp(10), dp(8), dp(10), dp(8));
                                pl.addView(pkgEt);
                                pkgDlg.content(pl);
                                pkgDlg.action("取消", new Runnable() { public void run() { pkgDlg.dismiss(); } });
                                pkgDlg.actionPrimary("安装", new Runnable() {
                                    public void run() {
                                        final String pkgs = pkgEt.getText().toString().trim();
                                        if (pkgs.isEmpty()) return;
                                        pkgDlg.dismiss();
                                        // 复用 d 显示进度
                                        d.title("正在下载安装: " + pkgs);
                                        d.hideActions();
                                        LinearLayout progBox = new LinearLayout(AdvActivity.this);
                                        progBox.setOrientation(LinearLayout.VERTICAL);
                                        ProgressBar pb = new ProgressBar(AdvActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                                        pb.setIndeterminate(true);
                                        progBox.addView(pb);
                                        final TextView logTv = new TextView(AdvActivity.this);
                                        logTv.setTextSize(11);
                                        logTv.setTypeface(Typeface.MONOSPACE);
                                        logTv.setTextColor(MdTheme.onSurfaceVariant(AdvActivity.this));
                                        logTv.setPadding(dp(4), dp(8), dp(4), 0);
                                        ScrollView sc = new ScrollView(AdvActivity.this);
                                        sc.addView(logTv);
                                        android.widget.FrameLayout fw = new android.widget.FrameLayout(AdvActivity.this);
                                        fw.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, dp(260)));
                                        fw.addView(sc);
                                        progBox.addView(fw);
                                        d.replaceContent(progBox);
                                        new Thread(new Runnable() {
                                            public void run() {
                                                String detail = RuntimeManager.pipInstall(AdvActivity.this, pkgs,
                                                        new RuntimeManager.LineListener() {
                                                            public void onLine(final String line) {
                                                                runOnUiThread(new Runnable() {
                                                                    public void run() {
                                                                        logTv.append(line + "\n");
                                                                        sc.post(new Runnable() {
                                                                            public void run() { sc.fullScroll(View.FOCUS_DOWN); }
                                                                        });
                                                                    }
                                                                });
                                                            }
                                                            public void onDone(boolean success) {}
                                                        });
                                                final String fdetail = detail;
                                                runOnUiThread(new Runnable() {
                                                    public void run() {
                                                        d.dismiss();
                                                        MdSnackbar.show(root, fdetail.length() > 140 ? fdetail.substring(0, 140) : fdetail);
                                                    }
                                                });
                                            }
                                        }).start();
                                    }
                                });
                                pkgDlg.show();
                            }
                        });
                    }
                }).start();
            }
        });
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
}
