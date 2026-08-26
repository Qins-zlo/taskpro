package io.taskpro.md;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Material 3 风格弹窗:
 *  - 圆角 28dp 卡片, 带轻微阴影
 *  - 标题 + 内容(自定义视图) + 操作按钮(Text Button)
 *  - 背景半透明遮罩, 淡入动画
 */
public class MdDialog extends Dialog {
    private Context ctx;
    private LinearLayout body;
    private LinearLayout actionBar;
    private boolean hasTitle = false;
    private TextView titleTv = null;
    private ScrollView contentScroll;
    private LinearLayout contentBox;

    public MdDialog(Context c) {
        super(c);
        ctx = c;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0.5f);
        }
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(16));
        body.setBackground(roundedCard());
        // 轻微阴影(深色下用描边)
        if (!MdTheme.isDark(c)) {
            body.setElevation(dp(8));
        }
        actionBar = new LinearLayout(c);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        actionBar.setLayoutParams(alp);
        setContentView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (getWindow() != null) {
            getWindow().setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 创建可滚动内容区 (内容过长时限制高度, 保证操作按钮可见) */
    private LinearLayout getContentBox() {
        if (contentScroll == null) {
            contentScroll = new ScrollView(ctx);
            // fillViewport 让内容区占满宽度; 嵌套 EditText 已在 MdTextField 中
            // 改为内容完全展开(不设固定高度), 滚动权交还本 ScrollView, 避免抢事件
            contentScroll.setFillViewport(true);
            contentBox = new LinearLayout(ctx);
            contentBox.setOrientation(LinearLayout.VERTICAL);
            contentScroll.addView(contentBox);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            body.addView(contentScroll, slp);
        }
        return contentBox;
    }

    private GradientDrawable roundedCard() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(MdTheme.surface(ctx));
        g.setCornerRadius(dp(28));
        if (MdTheme.isDark(ctx)) g.setStroke(dp(1), MdTheme.outlineVariant(ctx));
        return g;
    }

    public MdDialog title(String t) {
        // 如果已设置过标题, 更新文本而不是重复添加 (用于确认→进度切换)
        if (hasTitle && titleTv != null) {
            titleTv.setText(t);
            return this;
        }
        titleTv = new TextView(ctx);
        titleTv.setText(t);
        titleTv.setTextColor(MdTheme.onSurface(ctx));
        titleTv.setTextSize(22);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        body.addView(titleTv, lp);
        hasTitle = true;
        return this;
    }

    /** 设置内容视图(填充整个内容区) */
    public MdDialog content(View v) {
        getContentBox().addView(v);
        return this;
    }

    public MdDialog message(String msg) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(MdTheme.onSurfaceVariant(ctx));
        tv.setTextSize(15);
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        getContentBox().addView(tv, lp);
        return this;
    }

    /** 清空内容区并放入新视图 (用于把确认对话框切换为进度视图) */
    public MdDialog replaceContent(View v) {
        if (contentBox != null) {
            contentBox.removeAllViews();
            contentBox.addView(v);
        }
        return this;
    }

    /** 清空内容区 (配合 replaceContent 使用) */
    public MdDialog clearContent() {
        if (contentBox != null) contentBox.removeAllViews();
        return this;
    }

    /** 隐藏操作按钮栏 (进度阶段不需要按钮) */
    public MdDialog hideActions() {
        if (actionBar != null) actionBar.setVisibility(View.GONE);
        return this;
    }

    /** 显示操作按钮栏 */
    public MdDialog showActions() {
        if (actionBar != null) actionBar.setVisibility(View.VISIBLE);
        return this;
    }

    public MdDialog messageScroll(String msg) {
        ScrollView sc = new ScrollView(ctx);
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(MdTheme.onSurfaceVariant(ctx));
        tv.setTextSize(13);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTypeface(Typeface.MONOSPACE);
        sc.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280));
        getContentBox().addView(sc, lp);
        return this;
    }

    /** 添加操作按钮(Text button, M3 规范) */
    public MdDialog action(String label, final Runnable onClick) {
        MdButton b = new MdButton(ctx, label, MdButton.TEXT);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onClick != null) onClick.run();
            }
        });
        actionBar.addView(b);
        return this;
    }

    /** 添加高亮操作按钮(Filled tonal) */
    public MdDialog actionPrimary(String label, final Runnable onClick) {
        MdButton b = new MdButton(ctx, label, MdButton.TONAL);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onClick != null) onClick.run();
            }
        });
        actionBar.addView(b);
        return this;
    }

    private int dp(int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ═══════════ 全屏模式 (脚本编辑器等需要大面积编辑的场景) ═══════════
    private boolean fullscreenMode = false;

    /** 切换为全屏对话框: 铺满窗口, 用主题背景色铺底, 内容区占满剩余空间 */
    public MdDialog fullscreen() {
        fullscreenMode = true;
        // 铺满整个窗口, 用主题 surface 色作背景 (不能设 null, 否则露出透明窗口=空白一片)
        body.setPadding(0, 0, 0, 0);
        body.setBackground(new ColorDrawable(MdTheme.surface(ctx)));
        body.setElevation(0);
        return this;
    }

    /** 全屏模式下设置顶部工具栏 (替换默认标题, 放在 body 最顶端) */
    public MdDialog toolbar(View toolbarView) {
        if (!fullscreenMode) return this;
        // 移除默认标题 (如果有)
        if (hasTitle && titleTv != null) { body.removeView(titleTv); hasTitle = false; }
        // 工具栏作为第一个子 View 插入
        body.addView(toolbarView, 0);
        return this;
    }

    @Override
    public void show() {
        // 最后才把操作栏加进去(放在 content 之后)
        if (actionBar.getParent() == null) {
            body.addView(actionBar);
        }
        if (fullscreenMode) {
            // 全屏模式: 内容区占满剩余空间(可滚动), 操作栏固定在底部
            if (contentScroll != null && contentScroll.getLayoutParams() != null) {
                // 让内容区填充剩余高度, 撑满整个对话框
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0);
                clp.weight = 1f;
                contentScroll.setLayoutParams(clp);
                // 内层内容也填充宽度
                // 注意: ScrollView 继承 FrameLayout, 其子 View 必须是 FrameLayout 系 LayoutParams,
                // 用 LinearLayout.LayoutParams 会在 onMeasure 时 ClassCastException 闪退!
                contentBox.setLayoutParams(new android.widget.ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (getWindow() != null) {
                getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                getWindow().setGravity(Gravity.TOP);
                getWindow().setDimAmount(0f);
                // 全屏模式: 内容区应铺满, 无需限制高度 (注释掉原高度限制逻辑)
                // 移到 super.show() 之后处理
            }
        }
        super.show();
        // 非全屏模式才做高度限制
        if (!fullscreenMode) {
            // 内容过长时限制内容区高度为屏幕 60%, 保证操作按钮始终可见
            // EditText 已完全展开, 外层 ScrollView 是唯一滚动容器, 只需 clamp 高度即可
            if (contentScroll != null) {
                contentScroll.post(new Runnable() {
                    public void run() {
                        int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f);
                        if (contentScroll.getHeight() > maxH) {
                            LinearLayout.LayoutParams lp =
                                    (LinearLayout.LayoutParams) contentScroll.getLayoutParams();
                            lp.height = maxH;
                            lp.weight = 0f; // 固定高度, 不再参与权重分配
                            contentScroll.setLayoutParams(lp);
                        }
                    }
                });
            }
        } else {
            // 全屏模式: 确保内容区填充整个窗口
            if (contentScroll != null) {
                contentScroll.post(new Runnable() {
                    public void run() {
                        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                    }
                });
            }
        }
    }
}
